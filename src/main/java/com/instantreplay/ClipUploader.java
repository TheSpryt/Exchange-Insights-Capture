package com.instantreplay;

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
 * Instant Replay, Exchange Insights or Bank Templates has one configured, the
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

	private final InstantReplayConfig config;
	private final ConfigManager configManager;
	private final OkHttpClient http;
	private final ScheduledExecutorService executor;
	private final Consumer<String> notify;
	private final Gson gson = new Gson();

	ClipUploader(InstantReplayConfig config, ConfigManager configManager, OkHttpClient http,
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
			|| (InstantReplayConfig.GROUP.equals(group) && "eiAccountToken".equals(key));
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

		static final class Limits
		{
			int clips;
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
		body.addProperty("label", "Instant Replay plugin");
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
			.header("User-Agent", "InstantReplay RuneLite plugin")
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
					.header("User-Agent", "InstantReplay RuneLite plugin")
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
			notify.accept("Instant Replay: upload skipped - no Exchange Insights account linked.");
			if (deleteAfter)
			{
				//noinspection ResultOfMethodCallIgnored
				file.delete();
			}
			return;
		}
		executor.execute(() ->
		{
			try
			{
				upload(file, token);
			}
			finally
			{
				if (deleteAfter)
				{
					//noinspection ResultOfMethodCallIgnored
					file.delete();
				}
			}
		});
	}

	private void upload(File file, String token)
	{
		try
		{
			final HttpUrl url = HttpUrl.parse(BASE_URL + "/api/plugin/clips");
			if (url == null)
			{
				return;
			}

			// Clips are named <timestamp>_<reason>.mp4; pass the reason through so the
			// dashboard can group them without re-parsing filenames.
			final HttpUrl.Builder b = url.newBuilder().addQueryParameter("name", file.getName());
			final String reason = reasonOf(file.getName());
			if (reason != null)
			{
				b.addQueryParameter("reason", reason);
			}

			final Request request = new Request.Builder()
				.url(b.build())
				.header("Authorization", "Bearer " + token)
				.header("User-Agent", "InstantReplay RuneLite plugin")
				.post(RequestBody.create(MP4, file))
				.build();

			try (Response response = http.newCall(request).execute())
			{
				handleResponse(response, file);
			}
		}
		catch (Exception e)
		{
			// The clip is already safe on disk; an upload failure is not worth more than a log line.
			log.debug("clip upload failed for {}", file.getName(), e);
		}
	}

	/** The trigger name encoded in a clip filename, or null if it does not look like ours. */
	private static String reasonOf(String fileName)
	{
		final int underscore = fileName.indexOf('_', fileName.indexOf('_') + 1);
		final int dot = fileName.lastIndexOf('.');
		if (underscore < 0 || dot <= underscore + 1)
		{
			return null;
		}
		return fileName.substring(underscore + 1, dot);
	}

	private void handleResponse(Response response, File file)
	{
		switch (response.code())
		{
			case 200:
			case 201:
				log.debug("uploaded clip {}", file.getName());
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
				break;
			case 401:
			case 403:
				notify.accept("Instant Replay: your Exchange Insights token was rejected. Re-link your account to resume uploads.");
				break;
			case 413:
				notify.accept("Instant Replay: clip was too large to upload. Try a shorter clip or a lower resolution.");
				break;
			case 507:
				// Quota exhausted and nothing could be evicted to make room.
				notify.accept("Instant Replay: upload storage is full. Upgrade to premium or delete clips at exchange-insights.gg.");
				break;
			default:
				log.debug("clip upload returned {} for {}", response.code(), file.getName());
				break;
		}
	}
}
