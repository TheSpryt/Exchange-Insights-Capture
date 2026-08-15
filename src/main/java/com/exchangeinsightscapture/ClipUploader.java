package com.exchangeinsightscapture;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Uploads saved clips to the player's Exchange Insights account.
 *
 * <p>The account token is shared across this developer's plugins: whichever of
 * this plugin, Exchange Insights or Bank Templates has one configured, the
 * others borrow it, so a player links once and every plugin is linked. The token
 * is read live on each use and never copied, so revoking it anywhere takes effect
 * immediately.
 *
 * <p>Uploading is strictly best-effort and always happens <em>after</em> the clip
 * is on disk, so a quota rejection or a network failure can never cost a recording.
 */
@Slf4j
class ClipUploader
{
	private static final String BASE_URL = "https://exchange-insights.gg";
	private static final MediaType MP4 = MediaType.parse("video/mp4");
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private final ExchangeInsightsCaptureConfig config;
	private final ConfigManager configManager;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;
	private final Consumer<String> notify;
	private final Gson gson = new Gson();

	ClipUploader(ExchangeInsightsCaptureConfig config, ConfigManager configManager, OkHttpClient http,
		ScheduledExecutorService executor, Consumer<String> notify)
	{
		this.config = config;
		this.configManager = configManager;
		this.http = http;
		this.executor = executor;
		this.notify = notify;
	}

	/** True when a config change could have changed which token we resolve to. */
	static boolean isSharedTokenKey(String group, String key)
	{
		return SharedAccountToken.isTokenKey(group, key)
			|| (ExchangeInsightsCaptureConfig.GROUP.equals(group) && "eiAccountToken".equals(key));
	}

	/**
	 * The token to present, or null when nothing is linked.
	 *
	 * <p>A token pasted into THIS plugin's settings wins, since that is an explicit instruction.
	 * Otherwise the shared store is used - see {@link SharedAccountToken} for why the plugins no
	 * longer read each other directly.
	 */
	String effectiveToken()
	{
		final String own = config.eiAccountToken();
		if (own != null && !own.trim().isEmpty())
		{
			// Promote it, so every other plugin picks it up too.
			SharedAccountToken.set(configManager, own.trim());
			return own.trim();
		}
		return SharedAccountToken.get(configManager);
	}

	/** Where the token came from, for the side panel's status row. */
	String tokenSource()
	{
		final String own = config.eiAccountToken();
		if (own != null && !own.trim().isEmpty())
		{
			return "this plugin";
		}
		return SharedAccountToken.get(configManager) != null ? "your linked account" : null;
	}

	/**
	 * Upload progress per clip file name, 0-1. Present only while an upload is in flight, so a
	 * missing entry means "not uploading" rather than "0%".
	 */
	private final java.util.Map<String, Float> progress =
		java.util.Collections.synchronizedMap(new java.util.HashMap<>());

	/** Progress for a clip, or null when it is not currently uploading. */
	Float uploadProgress(String fileName)
	{
		return progress.get(fileName);
	}

	/** As above, for a clip coming down from the account. */
	private final java.util.Map<String, Float> downloads =
		java.util.Collections.synchronizedMap(new java.util.HashMap<>());

	/** Progress for a clip, or null when it is not currently downloading. */
	Float downloadProgress(String fileName)
	{
		return downloads.get(fileName);
	}

	/** Streams a file while reporting how much has gone, so the panel can show a bar. */
	private final class ProgressBody extends RequestBody
	{
		private final File file;

		ProgressBody(File file)
		{
			this.file = file;
		}

		@Override
		public MediaType contentType()
		{
			return MP4;
		}

		@Override
		public long contentLength()
		{
			return file.length();
		}

		@Override
		public void writeTo(okio.BufferedSink sink) throws java.io.IOException
		{
			final long total = Math.max(1, file.length());
			long written = 0;
			final byte[] buf = new byte[64 * 1024];
			try (java.io.InputStream in = new java.io.FileInputStream(file))
			{
				int read;
				while ((read = in.read(buf)) != -1)
				{
					sink.write(buf, 0, read);
					written += read;
					progress.put(file.getName(), (float) written / total);
					if (onProgress != null)
					{
						onProgress.run();
					}
				}
			}
		}
	}

	/**
	 * True once the server has refused an upload for lack of space.
	 *
	 * <p>Read by the panel, which says so at the top rather than leaving the player to wonder why
	 * clips stopped appearing on their account. Cleared as soon as an upload succeeds again.
	 */
	private volatile boolean cloudFull;

	private volatile long lastFullNoticeMs;

	boolean isCloudFull()
	{
		return cloudFull;
	}

	/**
	 * Say it once, not once per clip.
	 *
	 * <p>A full account refuses every upload that follows, and with automatic triggers that can be
	 * several a minute - repeating the same line each time would bury the game chat.
	 */
	private void notifyFullOnce()
	{
		final long now = System.currentTimeMillis();
		if (now - lastFullNoticeMs < FULL_NOTICE_INTERVAL_MS)
		{
			return;
		}
		lastFullNoticeMs = now;
		notify.accept("Exchange Insights Capture: your cloud storage is full, so this clip was not "
			+ "uploaded. It is still saved on your computer. Free up space at exchange-insights.gg.");
	}

	/** Notified as upload progress advances, so the panel can repaint. */
	private Runnable onProgress;

	void setProgressListener(Runnable listener)
	{
		this.onProgress = listener;
	}

	/** One clip as the server knows it. */
	static final class RemoteClip
	{
		long id;
		String name;
		long bytes;
		long createdAt;
	}

	private static final class ClipList
	{
		java.util.List<RemoteClip> clips;
	}

	/** The account's uploaded clips, or an empty list when nothing is linked or the call fails. */
	/**
	 * The account's clips.
	 *
	 * @return the listing, empty when the account genuinely has none, or null when the request
	 *         failed and the caller should keep whatever it already had.
	 */
	java.util.List<RemoteClip> listRemote()
	{
		final String token = effectiveToken();
		if (token == null)
		{
			// Not linked is a real answer: there are no clips up there.
			return java.util.Collections.emptyList();
		}
		try
		{
			final Request request = new Request.Builder()
				.url(BASE_URL + "/api/plugin/clips")
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.get()
				.build();
			try (Response response = http.newCall(request).execute())
			{
				final ResponseBody body = response.body();
				if (response.isSuccessful() && body != null)
				{
					final ClipList parsed = gson.fromJson(body.string(), ClipList.class);
					if (parsed != null && parsed.clips != null)
					{
						return parsed.clips;
					}
				}
			}
		}
		catch (Exception e)
		{
			log.debug("remote clip list failed", e);
		}
		// Null, not empty. An empty list is a real answer meaning "this account has no clips", and
		// a caller cannot tell the two apart - so a request that merely failed would wipe every
		// cloud clip out of the panel until the next poll put them back. Transient failures are
		// not rare either: OkHttp's shared disk cache throws on Windows when another client still
		// holds a cache file.
		return null;
	}

	/**
	 * Bumped whenever the preview generator changes in a way that makes old previews worse.
	 *
	 * <p>Previews are made by the client and only stored by the server, so a change here cannot
	 * reach clips already uploaded - the server has no video decoder and could not remake them if
	 * it wanted to. The only copy of the frame is the clip on someone's disk, so the client that
	 * still has it is the one that has to send a new preview.
	 */
	private static final int THUMB_VERSION = 2;

	/**
	 * Re-send previews for uploaded clips this computer still has, once per generator version.
	 *
	 * <p>Deliberately quiet and slow. It decodes a frame per clip and uploads a few hundred
	 * kilobytes each, so it runs on the background executor well after startup, one at a time, and
	 * gives up immediately if the account is not linked. Clips no longer on this disk keep the
	 * preview they have; nothing else can rebuild it.
	 */
	void backfillThumbnails()
	{
		final Integer done = configManager.getConfiguration(
			ExchangeInsightsCaptureConfig.GROUP, "thumbVersion", Integer.class);
		if (done != null && done >= THUMB_VERSION)
		{
			return;
		}
		if (effectiveToken() == null)
		{
			// Not linked, so there is nothing up there to improve. Left unmarked so it runs once an
			// account is linked rather than being skipped forever.
			return;
		}

		executor.schedule(() ->
		{
			try
			{
				final java.util.List<RemoteClip> remote = listRemote();
				if (remote == null)
				{
					return; // The request failed; try again next launch rather than marking it done.
				}
				final java.util.Map<String, ClipLibrary.Entry> local = new java.util.HashMap<>();
				for (ClipLibrary.Entry e : ClipLibrary.list(config))
				{
					local.put(e.name.toLowerCase(java.util.Locale.ROOT), e);
				}

				int sent = 0;
				for (RemoteClip r : remote)
				{
					if (r == null || r.name == null)
					{
						continue;
					}
					final ClipLibrary.Entry match = local.get(r.name.toLowerCase(java.util.Locale.ROOT));
					if (match == null)
					{
						continue;
					}
					final byte[] jpeg = ClipLibrary.previewJpeg(match);
					if (jpeg != null)
					{
						uploadThumb(r.id, jpeg);
						sent++;
					}
				}
				if (sent > 0)
				{
					log.debug("refreshed {} clip previews", sent);
				}
				configManager.setConfiguration(ExchangeInsightsCaptureConfig.GROUP, "thumbVersion",
					THUMB_VERSION);
			}
			catch (Exception e)
			{
				log.debug("preview refresh failed", e);
			}
		}, 20, java.util.concurrent.TimeUnit.SECONDS);
	}

	/** Rename an uploaded clip. */
	void renameRemote(long id, String name, Runnable onDone)
	{
		final String token = effectiveToken();
		if (token == null)
		{
			onDone.run();
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("name", name);
		post("/api/plugin/clips/rename", token, body, null, ignored -> onDone.run(), err -> onDone.run());
	}

	/** Delete an uploaded clip. */
	void deleteRemote(long id, Runnable onDone, Consumer<String> onError)
	{
		final String token = effectiveToken();
		if (token == null)
		{
			onError.accept("no account linked");
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("id", id);
		post("/api/plugin/clips/delete", token, body, null, ignored -> onDone.run(), onError);
	}

	/** Download an uploaded clip to {@code target}. Blocking; call from a background thread. */
	boolean downloadRemote(long id, File target)
	{
		final String token = effectiveToken();
		if (token == null)
		{
			return false;
		}
		try
		{
			final Request request = new Request.Builder()
				.url(BASE_URL + "/api/plugin/clips/download?id=" + id)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.get()
				.build();
			// Downloaded beside the destination, for the same reason clips are encoded that way:
			// the panel lists the clips folder, so writing the .mp4 directly would show a growing,
			// unplayable file as though it were a finished clip for the whole download.
			final File part = new File(target.getParentFile(), target.getName() + ClipStorage.PART_SUFFIX);
			try (Response response = http.newCall(request).execute())
			{
				final ResponseBody body = response.body();
				if (!response.isSuccessful() || body == null)
				{
					return false;
				}
				final long total = body.contentLength();
				long written = 0;
				try (java.io.InputStream in = body.byteStream();
					java.io.OutputStream out = new java.io.FileOutputStream(part))
				{
					final byte[] buf = new byte[64 * 1024];
					int read;
					while ((read = in.read(buf)) != -1)
					{
						out.write(buf, 0, read);
						written += read;
						// A chunked response has no length; the bar then just shows it is running.
						if (total > 0)
						{
							downloads.put(target.getName(), (float) written / total);
							if (onProgress != null)
							{
								onProgress.run();
							}
						}
					}
				}
				java.nio.file.Files.move(part.toPath(), target.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				return true;
			}
		}
		catch (Exception e)
		{
			log.debug("clip download failed", e);
			//noinspection ResultOfMethodCallIgnored
			new File(target.getParentFile(), target.getName() + ClipStorage.PART_SUFFIX).delete();
			//noinspection ResultOfMethodCallIgnored
			target.delete();
			return false;
		}
		finally
		{
			downloads.remove(target.getName());
			if (onProgress != null)
			{
				onProgress.run();
			}
		}
	}

	private static final MediaType JPEG = MediaType.parse("image/jpeg");

	/** Send a clip's preview so other clients can show it without downloading the video. */
	void uploadThumb(long id, byte[] jpeg)
	{
		final String token = effectiveToken();
		if (token == null || jpeg == null || jpeg.length == 0)
		{
			return;
		}
		try
		{
			final Request request = new Request.Builder()
				.url(BASE_URL + "/api/plugin/clips/thumb?id=" + id)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.post(RequestBody.create(JPEG, jpeg))
				.build();
			try (Response ignored = http.newCall(request).execute())
			{
				// Best effort: a missing preview costs a placeholder, nothing more.
			}
		}
		catch (Exception e)
		{
			log.debug("preview upload failed", e);
		}
	}

	/** Fetch a stored preview. Blocking; call from a background thread. Null when there is none. */
	java.awt.Image fetchThumb(long id)
	{
		final String token = effectiveToken();
		if (token == null)
		{
			return null;
		}
		try
		{
			final Request request = new Request.Builder()
				.url(BASE_URL + "/api/plugin/clips/thumb?id=" + id)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.get()
				.build();
			try (Response response = http.newCall(request).execute())
			{
				final ResponseBody body = response.body();
				if (!response.isSuccessful() || body == null)
				{
					return null;
				}
				return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(body.bytes()));
			}
		}
		catch (Exception e)
		{
			log.debug("preview fetch failed", e);
			return null;
		}
	}

	boolean isLinked()
	{
		return effectiveToken() != null;
	}

	/**
	 * The account's upload allowance, exactly as the server reports it.
	 *
	 * <p>Deliberately NOT computed or cached against local constants: the tier lives on the
	 * server, changes there (upgrade, lapse), and is re-checked there on every upload. The
	 * plugin only ever displays what it is told, so a tampered client can mislead its own UI
	 * and gain nothing.
	 */
	static final class Quota
	{
		String tier = "free";
		Usage used = new Usage();
		Limits quota = new Limits();

		static final class Usage
		{
			int clips;
			long bytes;
		}

		/**
		 * The allowance, which is bytes and nothing else.
		 *
		 * <p>There was a clip-count limit here too. It never bound - space always ran out first -
		 * and R2 charges for stored bytes rather than objects, so it was capping something nobody
		 * is billed for. The server no longer sends it.
		 */
		static final class Limits
		{
			long bytes;
		}

		boolean isPremium()
		{
			return "premium".equals(tier);
		}
	}

	/** Last quota the server reported, or null until one arrives. */
	private volatile Quota lastQuota;
	/** Set when the server rejected our token, so the panel can say so instead of claiming linked. */
	private volatile boolean tokenRejected;

	Quota getLastQuota()
	{
		return lastQuota;
	}

	/** True when a token exists but the server refused it (revoked, rotated, or wrong account). */
	boolean isTokenRejected()
	{
		return tokenRejected;
	}

	// ------------------------------------------------------------------
	// Account linking (mirrors the Bank Templates plugin's device-link flow)
	// ------------------------------------------------------------------

	static final class LinkStart
	{
		String userCode;
		String deviceSecret;
		String verificationUrl;
		long expiresAt;
		int pollSeconds;
	}

	static final class LinkPoll
	{
		String status;
		String token;
	}

	/** Begin a browser device-link. The user approves on the site; poll until a token is issued. */
	void startDeviceLink(long accountHash, String rsn, Consumer<LinkStart> onStart, Consumer<String> onError)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		if (rsn != null && !rsn.isEmpty())
		{
			body.addProperty("accountName", rsn);
		}
		// Names the minted token on the website's account page after the plugin that asked for it.
		body.addProperty("label", "Exchange Insights Capture plugin");
		post("/api/plugin/link/start", null, body, LinkStart.class, onStart, onError);
	}

	void pollDeviceLink(String deviceSecret, Consumer<LinkPoll> onResult, Consumer<String> onError)
	{
		final JsonObject body = new JsonObject();
		body.addProperty("deviceSecret", deviceSecret);
		post("/api/plugin/link/poll", null, body, LinkPoll.class, onResult, onError);
	}

	/** Attach the logged-in character to the token's account. `explicit` lifts a prior unlink. */
	void linkIdentity(String token, long accountHash, String rsn, Runnable onDone, Consumer<String> onError)
	{
		if (token == null || rsn == null || rsn.isEmpty())
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		body.addProperty("rsn", rsn);
		body.addProperty("explicit", true);
		post("/api/plugin/identity", token, body, null, ignored -> onDone.run(), onError);
	}

	void unlinkIdentity(String token, long accountHash, Runnable onDone, Consumer<String> onError)
	{
		if (token == null)
		{
			return;
		}
		final JsonObject body = new JsonObject();
		body.addProperty("accountHash", Long.toString(accountHash));
		post("/api/plugin/unlink", token, body, null, ignored -> onDone.run(), onError);
	}

	/** Shared JSON POST. Parses into {@code type} when given, else just reports success. */
	private <T> void post(String path, String token, JsonObject body, Class<T> type,
		Consumer<T> onOk, Consumer<String> onError)
	{
		final Request.Builder b = new Request.Builder()
			.url(BASE_URL + path)
			.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
			.post(RequestBody.create(JSON, gson.toJson(body)));
		if (token != null && !token.isEmpty())
		{
			b.header("Authorization", "Bearer " + token);
		}

		http.newCall(b.build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, java.io.IOException e)
			{
				onError.accept("connection lost");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					final ResponseBody rb = r.body();
					final String text = rb == null ? "" : rb.string();
					if (!r.isSuccessful())
					{
						onError.accept(r.code() == 401 || r.code() == 403
							? "your account token was not accepted"
							: "the server returned an error (" + r.code() + ")");
						return;
					}
					onOk.accept(type == null || text.isEmpty() ? null : gson.fromJson(text, type));
				}
				catch (Exception e)
				{
					onError.accept("unexpected response from the server");
				}
			}
		});
	}

	/** Ask the server for the current allowance; updates {@link #getLastQuota()} then calls back. */
	void refreshQuota(Runnable onUpdated)
	{
		final String token = effectiveToken();
		if (token == null)
		{
			lastQuota = null;
			tokenRejected = false;
			onUpdated.run();
			return;
		}
		executor.execute(() ->
		{
			try
			{
				final Request request = new Request.Builder()
					.url(BASE_URL + "/api/plugin/clips/quota")
					.header("Authorization", "Bearer " + token)
					.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
					.get()
					.build();
				try (Response response = http.newCall(request).execute())
				{
					final ResponseBody body = response.body();
					if (response.isSuccessful() && body != null)
					{
						lastQuota = gson.fromJson(body.string(), Quota.class);
						tokenRejected = false;
					}
					else if (response.code() == 401 || response.code() == 403)
					{
						// A token the server refuses. Note this is only trusted from an endpoint
						// that definitely validates tokens - a missing route would 401 too, which
						// is how a perfectly good token got branded bad before the deploy landed.
						lastQuota = null;
						tokenRejected = true;
					}
					else
					{
						// Any other failure (route missing, server error) says nothing about the
						// token, so leave the previous verdict alone.
						lastQuota = null;
					}
				}
			}
			catch (Exception e)
			{
				log.debug("quota fetch failed", e);
			}
			onUpdated.run();
		});
	}

	void maybeUpload(File file)
	{
		maybeUpload(file, false);
	}

	/**
	 * Upload the clip if uploading is enabled and an account is linked. Never throws.
	 *
	 * @param deleteAfter true when the file is a throwaway H.264 copy made purely for upload
	 *                    (see ClipRecorder: Motion JPEG clips are far too large to send).
	 */
	void maybeUpload(File file, boolean deleteAfter)
	{
		if (!config.uploadClips())
		{
			return;
		}
		final String token = effectiveToken();
		if (token == null)
		{
			notify.accept("Exchange Insights Capture: upload skipped - no Exchange Insights account linked.");
			if (deleteAfter)
			{
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
			return;
		}
		executor.execute(() -> attemptUpload(file, token, deleteAfter, 1));
	}

	/** How many times a transient failure is retried before the clip is left local-only. */
	/** What a single request may carry, bounded by Cloudflare's edge rather than by us. */
	private static final int MAX_UPLOAD_MB = 95;

	/** How long between repeats of the storage-full notice. */
	private static final long FULL_NOTICE_INTERVAL_MS = 10 * 60 * 1000;

	/** Past this, a clip is sent in parts instead of one request. */
	private static final long SINGLE_REQUEST_LIMIT = 80L * 1024 * 1024;

	/** Part size. Well under the edge limit, so a retry of one part is never expensive. */
	private static final int PART_BYTES = 16 * 1024 * 1024;

	private static final int MAX_UPLOAD_ATTEMPTS = 4;

	/**
	 * Upload, retrying transient failures with a widening delay.
	 *
	 * <p>A server redeploy answers 503 for a few seconds, and a dropped connection throws -
	 * neither says anything is wrong with the clip. Without a retry those cases lost the upload
	 * silently and permanently, leaving a clip local-only with nothing to explain why.
	 */
	private void attemptUpload(File file, String token, boolean deleteAfter, int attempt)
	{
		final boolean transientFailure = upload(file, token);
		if (!transientFailure)
		{
			if (deleteAfter)
			{
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
			return;
		}

		if (attempt >= MAX_UPLOAD_ATTEMPTS)
		{
			log.debug("giving up uploading {} after {} attempts", file.getName(), attempt);
			notify.accept("Exchange Insights Capture: couldn't upload " + file.getName()
				+ " - you can retry it from the clip list.");
			if (deleteAfter)
			{
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
			return;
		}

		// 5s, 20s, 45s: long enough to outlast a deploy, short enough to still be this session.
		final long delaySeconds = 5L * attempt * attempt;
		log.debug("retrying upload of {} in {}s (attempt {})", file.getName(), delaySeconds, attempt + 1);
		executor.schedule(() -> attemptUpload(file, token, deleteAfter, attempt + 1),
			delaySeconds, java.util.concurrent.TimeUnit.SECONDS);
	}

	/** @return true when the attempt failed in a way that is worth retrying. */
	/** One slice of a clip file, streamed without ever holding the whole clip in memory. */
	private final class PartBody extends RequestBody
	{
		private final File file;
		private final long from;
		private final int length;
		private final long total;
		private final long alreadySent;

		PartBody(File file, long from, int length, long total, long alreadySent)
		{
			this.file = file;
			this.from = from;
			this.length = length;
			this.total = total;
			this.alreadySent = alreadySent;
		}

		@Override
		public MediaType contentType()
		{
			return MP4;
		}

		@Override
		public long contentLength()
		{
			return length;
		}

		@Override
		public void writeTo(okio.BufferedSink sink) throws java.io.IOException
		{
			final byte[] buf = new byte[64 * 1024];
			long remaining = length;
			try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r"))
			{
				in.seek(from);
				while (remaining > 0)
				{
					final int read = in.read(buf, 0, (int) Math.min(buf.length, remaining));
					if (read < 0)
					{
						break;
					}
					sink.write(buf, 0, read);
					remaining -= read;
					// Progress spans the whole clip, not this part, so the bar advances smoothly
					// across a multi-part upload instead of restarting at each one.
					progress.put(file.getName(), (float) (alreadySent + (length - remaining)) / total);
					if (onProgress != null)
					{
						onProgress.run();
					}
				}
			}
		}
	}

	/**
	 * Upload a clip too big for one request, as a server-tracked multipart upload.
	 *
	 * <p>The server decides the key and owns the running byte count, so this only supplies bytes
	 * and part numbers. A failure at any point aborts the upload rather than leaving parts behind:
	 * unfinished parts sit in storage billing like anything else.
	 */
	private boolean uploadInParts(File file, String token)
	{
		String key = null;
		String uploadId = null;
		try
		{
			final JsonObject start = new JsonObject();
			start.addProperty("name", file.getName());
			final String reason = reasonOf(file);
			if (reason != null)
			{
				start.addProperty("reason", reason);
			}

			final JsonObject started = postForJson("/api/plugin/clips/upload/start", token, start, file);
			if (started == null)
			{
				return true;
			}
			key = started.get("key").getAsString();
			uploadId = started.get("uploadId").getAsString();
			final int partSize = started.has("maxPartBytes")
				? Math.min(PART_BYTES, started.get("maxPartBytes").getAsInt())
				: PART_BYTES;

			final long total = file.length();
			final com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
			long sent = 0;
			int partNumber = 1;
			while (sent < total)
			{
				final int length = (int) Math.min(partSize, total - sent);
				final HttpUrl url = HttpUrl.parse(BASE_URL + "/api/plugin/clips/upload/part");
				if (url == null)
				{
					return false;
				}
				final Request request = new Request.Builder()
					.url(url.newBuilder()
						.addQueryParameter("key", key)
						.addQueryParameter("uploadId", uploadId)
						.addQueryParameter("part", String.valueOf(partNumber))
						.build())
					.header("Authorization", "Bearer " + token)
					.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
					.post(new PartBody(file, sent, length, total, sent))
					.build();

				try (Response response = http.newCall(request).execute())
				{
					if (!response.isSuccessful())
					{
						abortUpload(key, uploadId, token);
						return handlePartFailure(response, file);
					}
					final ResponseBody body = response.body();
					final JsonObject parsed = body == null ? null : gson.fromJson(body.string(), JsonObject.class);
					if (parsed == null || !parsed.has("etag"))
					{
						abortUpload(key, uploadId, token);
						return true;
					}
					final JsonObject part = new JsonObject();
					part.addProperty("partNumber", partNumber);
					part.addProperty("etag", parsed.get("etag").getAsString());
					parts.add(part);
				}
				sent += length;
				partNumber++;
			}

			final JsonObject finish = new JsonObject();
			finish.addProperty("key", key);
			finish.addProperty("uploadId", uploadId);
			finish.add("parts", parts);

			final HttpUrl finishUrl = HttpUrl.parse(BASE_URL + "/api/plugin/clips/upload/finish");
			final Request request = new Request.Builder()
				.url(finishUrl)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.post(RequestBody.create(JSON, gson.toJson(finish)))
				.build();
			try (Response response = http.newCall(request).execute())
			{
				return handleResponse(response, file);
			}
		}
		catch (Exception e)
		{
			log.debug("multipart upload failed for {}", file.getName(), e);
			if (key != null && uploadId != null)
			{
				abortUpload(key, uploadId, token);
			}
			return true;
		}
		finally
		{
			progress.remove(file.getName());
			if (onProgress != null)
			{
				onProgress.run();
			}
		}
	}

	/** Tell the server to discard an upload, so its parts stop billing straight away. */
	private void abortUpload(String key, String uploadId, String token)
	{
		try
		{
			final JsonObject body = new JsonObject();
			body.addProperty("key", key);
			body.addProperty("uploadId", uploadId);
			final Request request = new Request.Builder()
				.url(BASE_URL + "/api/plugin/clips/upload/abort")
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
			http.newCall(request).execute().close();
		}
		catch (Exception e)
		{
			log.debug("could not abort upload {}", key, e);
		}
	}

	/** A part rejected: 413 means the clip is simply too big, anything 5xx is worth another go. */
	private boolean handlePartFailure(Response response, File file)
	{
		if (response.code() == 413)
		{
			notify.accept(String.format(
				"Exchange Insights Capture: clip is %.0fMB, too large to upload. "
					+ "Lower the quality setting or shorten the clip. It is still saved on your computer.",
				file.length() / (1024.0 * 1024.0)));
			return false;
		}
		log.debug("clip part upload returned {} for {}", response.code(), file.getName());
		return response.code() >= 500;
	}

	/** POST a JSON body and return the parsed reply, or null when the call failed. */
	private JsonObject postForJson(String path, String token, JsonObject body, File file)
	{
		try
		{
			final Request request = new Request.Builder()
				.url(BASE_URL + path)
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
			try (Response response = http.newCall(request).execute())
			{
				if (!response.isSuccessful())
				{
					log.debug("{} returned {} for {}", path, response.code(), file.getName());
					return null;
				}
				final ResponseBody rb = response.body();
				return rb == null ? null : gson.fromJson(rb.string(), JsonObject.class);
			}
		}
		catch (Exception e)
		{
			log.debug("{} failed for {}", path, file.getName(), e);
			return null;
		}
	}

	/**
	 * Send a clip, in one request or in parts depending on its size.
	 *
	 * <p>Cloudflare refuses request bodies over 100MB at the edge, before the server sees them, so
	 * anything approaching that has to be split. Small clips still go in a single request: it is
	 * one round trip instead of several and covers the overwhelming majority of captures.
	 *
	 * @return true when the attempt failed in a way that is worth retrying.
	 */
	private boolean upload(File file, String token)
	{
		if (file.length() > SINGLE_REQUEST_LIMIT)
		{
			return uploadInParts(file, token);
		}
		try
		{
			final HttpUrl url = HttpUrl.parse(BASE_URL + "/api/plugin/clips");
			if (url == null)
			{
				return false;
			}

			// Clips are named <timestamp>_<reason>.mp4; pass the reason through so the
			// dashboard can group them without re-parsing filenames.
			final HttpUrl.Builder b = url.newBuilder().addQueryParameter("name", file.getName());
			final String reason = reasonOf(file);
			if (reason != null)
			{
				b.addQueryParameter("reason", reason);
			}

			final Request request = new Request.Builder()
				.url(b.build())
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "Exchange Insights Capture RuneLite plugin")
				.post(new ProgressBody(file))
				.build();

			try (Response response = http.newCall(request).execute())
			{
				return handleResponse(response, file);
			}
			finally
			{
				// Clear either way: a failed upload must not leave a stalled bar on the card.
				progress.remove(file.getName());
				if (onProgress != null)
				{
					onProgress.run();
				}
			}
		}
		catch (Exception e)
		{
			// The clip is safe on disk either way, but a dropped connection is transient.
			log.debug("clip upload failed for {}", file.getName(), e);
			return true;
		}
	}

	/**
	 * Generate and send this clip's preview after it uploads.
	 *
	 * <p>The upload response does not carry the new clip's id, so the id is taken from a fresh
	 * listing matched on name - cheap, and it also confirms the clip really landed rather than
	 * trusting the 200.
	 */
	private void sendPreviewFor(File file)
	{
		try
		{
			final byte[] jpeg = ClipLibrary.previewJpeg(new ClipLibrary.Entry(file));
			if (jpeg == null)
			{
				return;
			}
			// Null when the listing request failed, which is not the same as an empty account.
			// Either way there is no id to attach the preview to, so it waits for a later upload.
			final java.util.List<RemoteClip> listed = listRemote();
			if (listed == null)
			{
				return;
			}
			for (RemoteClip r : listed)
			{
				if (r != null && file.getName().equalsIgnoreCase(r.name))
				{
					uploadThumb(r.id, jpeg);
					return;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("could not send a preview for {}", file.getName(), e);
		}
	}

	/** The trigger name encoded in a clip filename, or null if it does not look like ours. */
	/**
	 * What kind of clip this is, for grouping on the dashboard.
	 *
	 * <p>Read from the folder rather than the file name. Clips used to be called
	 * "&lt;timestamp&gt;_&lt;reason&gt;.mp4" and the reason was parsed back out; they are now named
	 * and filed the way RuneLite names screenshots, so the category is the directory the clip sits
	 * in - "Boss Kills", "Pets" - and there is nothing to parse.
	 */
	private static String reasonOf(File file)
	{
		final File parent = file.getParentFile();
		if (parent == null)
		{
			return null;
		}
		final String folder = parent.getName();
		// A clip saved before the character was known sits in the root, which is not a category.
		return folder.isEmpty() || folder.equalsIgnoreCase("captures") ? null : folder;
	}

	/** @return true when this response means "try again later" rather than "this will never work". */
	private boolean handleResponse(Response response, File file)
	{
		switch (response.code())
		{
			case 200:
			case 201:
				log.debug("uploaded clip {}", file.getName());
				// Room again - whether they deleted something, upgraded, or turned rolling on.
				cloudFull = false;
				sendPreviewFor(file);
				// The upload reply carries fresh usage, so steady-state display costs no extra call.
				try
				{
					final ResponseBody body = response.body();
					if (body != null)
					{
						lastQuota = gson.fromJson(body.string(), Quota.class);
					}
				}
				catch (Exception e)
				{
					log.debug("could not read quota from upload response", e);
				}
				return false;
			case 401:
			case 403:
				notify.accept("Exchange Insights Capture: your Exchange Insights token was rejected. Re-link your account to resume uploads.");
				return false;
			case 413:
				// Name the actual size, because the useful part is knowing how far over it went -
				// the limit is fixed at 95MB by Cloudflare's edge and cannot be raised from here.
				notify.accept(String.format(
					"Exchange Insights Capture: clip is %.0fMB, over the %dMB upload limit. "
						+ "Lower the quality setting or shorten the clip. It is still saved on your computer.",
					file.length() / (1024.0 * 1024.0), MAX_UPLOAD_MB));
				return false;
			case 507:
				// Full, and this account is not set to replace its oldest clip. The clip stays on
				// disk and keeps its upload arrow, so it can be sent once room is made.
				cloudFull = true;
				notifyFullOnce();
				return false;
			default:
				log.debug("clip upload returned {} for {}", response.code(), file.getName());
				// 5xx is the server having a moment - a deploy, a restart - so it is worth
				// another go. A 4xx we have not named is about this request and will not fix
				// itself, so retrying would only waste the bandwidth.
				return response.code() >= 500;
		}
	}
}
