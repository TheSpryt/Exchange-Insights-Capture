package com.instantreplay;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.DrawManager;

/**
 * Maintains a rolling buffer of recent frames and, when triggered, encodes a
 * clip spanning the lead-up to the trigger plus a configurable post-event tail.
 *
 * <p>All buffer state is confined to a single "processor" thread so no locking
 * is required: frame delivery, triggers and post-roll timing are all funnelled
 * through it as tasks. Capture sampling is driven by a scheduler, and the final
 * MP4 encode runs on its own thread so it never stalls capture.
 */
@Slf4j
class ClipRecorder
{
	private static final long COOLDOWN_MS = 1500;
	private static final long EVICT_SLACK_MS = 750;
	/** If a requested frame never arrives (client not rendering), re-arm after this long. */
	private static final long FRAME_REQUEST_TIMEOUT_MS = 2000;

	private final InstantReplayConfig config;
	private final DrawManager drawManager;
	private final BooleanSupplier canCapture;
	private final Supplier<java.awt.geom.Point2D.Double> mousePosition;
	private final Consumer<File> onSaved;
	private final Consumer<String> onError;
	/** Receives the file to upload plus whether it is a throwaway that should be deleted after. */
	private java.util.function.BiConsumer<File, Boolean> onUploadReady = (f, tmp) -> { };

	private ScheduledExecutorService scheduler;
	private ThreadPoolExecutor workers;
	private ThreadPoolExecutor processor;
	private ThreadPoolExecutor encoder;

	// Mirrors the processor-thread `capturing` flag for cross-thread reads (overlay).
	private volatile boolean recording;
	// Mirrors manual-session state for the overlay and side panel.
	private volatile boolean sessionActive;
	private volatile int sessionFrameCount;
	// Clips queued or currently being written. Encoding takes far longer than capture, so this
	// is what the user is actually waiting on once the red "recording" phase ends.
	private final AtomicInteger pendingEncodes = new AtomicInteger();

	// At most one outstanding frame request at a time. Capturing a frame forces a
	// GPU readback on the GPU/117HD renderers, so requesting faster than the client
	// renders would queue listeners and drain several against one rendered frame --
	// duplicate frames and wasted encodes exactly when the client is already slow.
	private final AtomicBoolean framePending = new AtomicBoolean();
	private volatile long frameRequestedAtMs;

	// Scale + JPEG encode is the CPU-heavy part of capture and is independent per frame, so it
	// runs on a small pool rather than the single processor thread. Each worker keeps its OWN
	// writer and raster: an ImageWriter is locked to the thread that uses it, and sharing one
	// raster across threads would tear. ThreadLocal gives each worker its own without locking.
	private final ThreadLocal<ImageWriter> jpegWriter = new ThreadLocal<>();
	private final ThreadLocal<BufferedImage> scratch = new ThreadLocal<>();

	// --- processor-thread-confined state ---
	private final Deque<RecordedFrame> buffer = new ArrayDeque<>();
	private boolean capturing;
	private long postRollEndMs;
	private List<RecordedFrame> activeClip;
	private String activeReason;
	private long lastClipMs;

	// Manual mode: a single take that accumulates every frame from arm to disarm,
	// rather than the rolling window. Bounded by maxManualLength so a forgotten
	// recording cannot grow without limit.
	private List<RecordedFrame> sessionFrames;

	ClipRecorder(InstantReplayConfig config, DrawManager drawManager, BooleanSupplier canCapture,
		Supplier<java.awt.geom.Point2D.Double> mousePosition, Consumer<File> onSaved, Consumer<String> onError)
	{
		this.config = config;
		this.drawManager = drawManager;
		this.canCapture = canCapture;
		this.mousePosition = mousePosition;
		this.onSaved = onSaved;
		this.onError = onError;
	}

	void setUploadHandler(java.util.function.BiConsumer<File, Boolean> handler)
	{
		this.onUploadReady = handler;
	}

	void start()
	{
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> namedDaemon(r, "instant-replay-capture"));
		// Scale + JPEG for several frames at once. Capped well below the core count: this runs
		// alongside the game, and the goal is to stop frames queueing, not to hog the CPU.
		final int poolSize = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
		workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, r -> namedDaemon(r, "instant-replay-worker"));
		processor = singleThread("instant-replay-processor");
		encoder = singleThread("instant-replay-encoder");

		long periodMs = Math.max(1, 1000L / Math.max(1, config.framerate()));
		scheduler.scheduleAtFixedRate(this::captureTick, 0, periodMs, TimeUnit.MILLISECONDS);
	}

	void stop()
	{
		// An ImageWriter locks itself to the thread that used it, so disposing the cached
		// JPEG writer from here (the client thread) throws IllegalStateException. Hand the
		// disposal back to the processor thread that owns it, before anything is shut down.
		final ThreadPoolExecutor w = workers;
		if (w != null && !w.isShutdown())
		{
			// One task per worker thread so every thread disposes its own writer; an
			// ImageWriter throws if disposed from a thread other than the one that used it.
			for (int i = 0; i < w.getMaximumPoolSize(); i++)
			{
				try
				{
					w.execute(this::releaseEncodeResources);
				}
				catch (RuntimeException ignored)
				{
					break;
				}
			}
		}

		shutdown(scheduler);
		shutdown(workers);
		shutdown(processor);
		shutdown(encoder);
		scheduler = null;
		workers = null;
		processor = null;
		encoder = null;
		buffer.clear();
		capturing = false;
		recording = false;
		activeClip = null;
		sessionFrames = null;
		sessionActive = false;
		sessionFrameCount = 0;
		pendingEncodes.set(0);
		framePending.set(false);
	}

	/** Release the cached encode resources. MUST run on the processor thread: an ImageWriter
	 *  is locked to the thread that used it and throws if disposed from anywhere else. */
	private void releaseEncodeResources()
	{
		scratch.remove();
		final ImageWriter writer = jpegWriter.get();
		if (writer != null)
		{
			try
			{
				writer.dispose();
			}
			catch (RuntimeException e)
			{
				log.debug("jpeg writer dispose failed", e);
			}
			jpegWriter.remove();
		}
	}

	/** Whether a triggered clip is currently being captured (including its post-roll tail). */
	boolean isRecording()
	{
		return recording;
	}

	/** Whether any clip is still being encoded/written to disk. */
	boolean isSaving()
	{
		return pendingEncodes.get() > 0;
	}

	/** How many clips are queued or being written, for a "saving 2 clips" style hint. */
	int getPendingEncodes()
	{
		return pendingEncodes.get();
	}

	/** Whether a manual take is armed and accumulating frames. */
	boolean isSessionActive()
	{
		return sessionActive;
	}

	/** Frames captured so far in the current manual take; divide by framerate for its length. */
	int getSessionFrameCount()
	{
		return sessionFrameCount;
	}

	/** Arm a manual take. Safe to call from any thread. */
	void startSession()
	{
		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			p.execute(this::beginSession);
		}
	}

	/** Disarm the manual take and encode everything captured. Safe to call from any thread. */
	void stopSession()
	{
		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			p.execute(() -> finishSession("manual"));
		}
	}

	/**
	 * Re-arm capture at a new framerate: the rolling buffer is discarded and refills at
	 * the new rate, which is what changing the setting should mean - a buffer holding a
	 * mix of two sample rates would play back at the wrong speed.
	 *
	 * <p>Unlike a full stop/start this keeps the processor and encoder pools alive, so a
	 * clip already being encoded survives the change, and the buffer is cleared ON the
	 * processor thread rather than from the caller's, which is the only thread that owns it.
	 */
	void restartCapture()
	{
		final ScheduledExecutorService old = scheduler;
		if (old != null)
		{
			old.shutdownNow();
		}

		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			p.execute(() ->
			{
				buffer.clear();
				capturing = false;
				recording = false;
				activeClip = null;
			});
		}

		framePending.set(false);
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> namedDaemon(r, "instant-replay-capture"));
		final long periodMs = Math.max(1, 1000L / Math.max(1, config.framerate()));
		scheduler.scheduleAtFixedRate(this::captureTick, 0, periodMs, TimeUnit.MILLISECONDS);
	}

	/** Request a clip; safe to call from any thread (e.g. the client thread). */
	void trigger(String reason)
	{
		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			p.execute(() -> beginClip(reason));
		}
	}

	// ------------------------------------------------------------------
	// Capture pipeline
	// ------------------------------------------------------------------

	private void captureTick()
	{
		try
		{
			if (canCapture.getAsBoolean() && claimFrameRequest())
			{
				drawManager.requestNextFrameListener(this::onFrameImage);
			}
			// Finalise on time even if frames stop arriving (e.g. on logout).
			final ThreadPoolExecutor p = processor;
			if (p != null && !p.isShutdown())
			{
				p.execute(this::checkPostRollTimeout);
			}
		}
		catch (Exception e)
		{
			log.debug("capture tick failed", e);
		}
	}

	/**
	 * Claims the single outstanding frame-request slot, re-arming it if a previous
	 * request was never fulfilled (e.g. the client stopped rendering while minimised).
	 */
	private boolean claimFrameRequest()
	{
		if (framePending.compareAndSet(false, true))
		{
			frameRequestedAtMs = System.currentTimeMillis();
			return true;
		}
		if (System.currentTimeMillis() - frameRequestedAtMs > FRAME_REQUEST_TIMEOUT_MS)
		{
			frameRequestedAtMs = System.currentTimeMillis();
			return true;
		}
		return false;
	}

	private void onFrameImage(Image image)
	{
		framePending.set(false);
		final ThreadPoolExecutor w = workers;
		if (w == null || w.isShutdown())
		{
			return;
		}
		// Drop frames if the pool is falling behind rather than pile up memory. A dropped frame
		// still cost us a GPU readback, so the pool is sized to make this rare.
		if (w.getQueue().size() > w.getMaximumPoolSize() * 2)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		// Read the mouse position on the render thread; the OS cursor is not part
		// of the captured frame, so we draw our own marker at this point.
		final java.awt.geom.Point2D.Double mouse = config.drawCursor() ? mousePosition.get() : null;
		w.execute(() -> processFrame(image, now, mouse));
	}

	private void processFrame(Image image, long now, java.awt.geom.Point2D.Double mouse)
	{
		try
		{
			BufferedImage scaled = scale(image, mouse);
			byte[] jpeg = toJpeg(scaled);
			RecordedFrame frame = new RecordedFrame(now, jpeg);

			// Buffer state stays single-threaded: workers only produce frames, the processor
			// thread owns where they go.
			final ThreadPoolExecutor p = processor;
			if (p != null && !p.isShutdown())
			{
				p.execute(() -> storeFrame(frame));
			}
		}
		catch (Exception e)
		{
			log.debug("frame processing failed", e);
		}
	}

	/** Processor thread only: file a finished frame into the buffer or the active take. */
	private void storeFrame(RecordedFrame frame)
	{
		try
		{
			final long now = frame.timestampMs;

			// A manual take keeps everything and bypasses the rolling window entirely.
			if (sessionFrames != null)
			{
				sessionFrames.add(frame);
				sessionFrameCount = sessionFrames.size();
				if (sessionFrames.size() >= maxSessionFrames())
				{
					finishSession("manual-limit");
				}
				return;
			}

			// Workers finish out of order under load, so keep the deque time-ordered - the
			// eviction check and playback both assume it. Disorder is tiny, so this walks
			// back a step or two at most.
			if (buffer.isEmpty() || buffer.peekLast().timestampMs <= now)
			{
				buffer.addLast(frame);
			}
			else
			{
				insertOrdered(frame);
			}
			evictOld(now);

			if (capturing)
			{
				activeClip.add(frame);
				if (now >= postRollEndMs)
				{
					finishClip();
				}
			}
		}
		catch (Exception e)
		{
			log.debug("frame processing failed", e);
		}
	}

	private void checkPostRollTimeout()
	{
		if (capturing && System.currentTimeMillis() >= postRollEndMs)
		{
			finishClip();
		}
	}

	/** Insert a late-arriving frame at its correct position. Processor thread only. */
	private void insertOrdered(RecordedFrame frame)
	{
		final java.util.ArrayList<RecordedFrame> tail = new java.util.ArrayList<>();
		while (!buffer.isEmpty() && buffer.peekLast().timestampMs > frame.timestampMs)
		{
			tail.add(buffer.removeLast());
		}
		buffer.addLast(frame);
		for (int i = tail.size() - 1; i >= 0; i--)
		{
			buffer.addLast(tail.get(i));
		}
	}

	private void evictOld(long now)
	{
		long preRollMs = preRollMs() + EVICT_SLACK_MS;
		while (!buffer.isEmpty() && now - buffer.peekFirst().timestampMs > preRollMs)
		{
			buffer.removeFirst();
		}
	}

	// ------------------------------------------------------------------
	// Clip lifecycle (processor thread)
	// ------------------------------------------------------------------

	private void beginClip(String reason)
	{
		long now = System.currentTimeMillis();
		if (capturing || now - lastClipMs < COOLDOWN_MS)
		{
			return;
		}
		activeClip = new ArrayList<>(buffer);
		activeReason = reason;
		capturing = true;
		recording = true;
		postRollEndMs = now + postRollMs();
		if (postRollMs() == 0)
		{
			finishClip();
		}
	}

	private void beginSession()
	{
		if (sessionFrames != null)
		{
			return;
		}
		// Drop any rolling-buffer state: a manual take starts from the arm press.
		buffer.clear();
		capturing = false;
		recording = false;
		activeClip = null;
		sessionFrames = new ArrayList<>();
		sessionFrameCount = 0;
		sessionActive = true;
	}

	private void finishSession(String reason)
	{
		final List<RecordedFrame> clip = sessionFrames;
		sessionFrames = null;
		sessionActive = false;
		sessionFrameCount = 0;
		if (clip == null)
		{
			return;
		}
		lastClipMs = System.currentTimeMillis();

		final int fps = config.framerate();
		final ThreadPoolExecutor e = encoder;
		if (e != null && !e.isShutdown() && !clip.isEmpty())
		{
			pendingEncodes.incrementAndGet();
			e.execute(() -> encodeAndSave(clip, reason, fps));
		}
		else if (clip.isEmpty())
		{
			onError.accept("Nothing was captured - the take was too short or the client was not rendering.");
		}
	}

	/** Frame ceiling for a manual take, from the configured length limit and framerate. */
	private int maxSessionFrames()
	{
		return Math.max(1, config.maxManualLength()) * Math.max(1, config.framerate());
	}

	private void finishClip()
	{
		if (!capturing)
		{
			return;
		}
		capturing = false;
		recording = false;
		lastClipMs = System.currentTimeMillis();

		final List<RecordedFrame> clip = activeClip;
		final String reason = activeReason;
		final int fps = config.framerate();
		activeClip = null;
		activeReason = null;

		final ThreadPoolExecutor e = encoder;
		if (e != null && !e.isShutdown() && clip != null && !clip.isEmpty())
		{
			pendingEncodes.incrementAndGet();
			e.execute(() -> encodeAndSave(clip, reason, fps));
		}
	}

	private void encodeAndSave(List<RecordedFrame> frames, String reason, int fps)
	{
		File saved = null;
		String error = null;
		boolean fast = false;

		try
		{
			File dir = outputDir();
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
			fast = config.fastSave();
			String name = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date())
				+ "_" + sanitise(reason) + ".mp4";
			File out = new File(dir, name);

			if (fast)
			{
				ClipEncoder.encodeMjpeg(out, frames, fps);
			}
			else
			{
				ClipEncoder.encode(out, frames, fps);
			}
			// A frame from the middle of the clip is the most representative still, and we
			// already hold it - no need to decode one back out of the finished video.
			ClipLibrary.writeThumbnail(config, out, frames.get(frames.size() / 2).jpeg);
			saved = out;
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Failed to save Instant Replay clip", ex);
			error = ex.getMessage();
		}
		finally
		{
			// Drop the count BEFORE notifying. The listeners redraw the side panel, which only
			// repaints when told to - firing them while this still read 1 left the panel stuck
			// on "Saving clip..." forever, with nothing to correct it.
			pendingEncodes.decrementAndGet();
		}

		if (saved == null)
		{
			onError.accept(error);
			return;
		}
		onSaved.accept(saved);

		if (!config.uploadClips())
		{
			return;
		}

		// Motion JPEG is enormous - every frame is a keyframe - and Cloudflare rejects request
		// bodies over 100MB regardless of our own cap, so a fast-saved clip is often physically
		// unuploadable. Encode a compact H.264 copy just for the upload: the local file stays
		// instant, and the upload stays within the limit.
		if (fast)
		{
			File temp = null;
			try
			{
				temp = File.createTempFile("instant-replay-", ".mp4");
				ClipEncoder.encode(temp, frames, fps);
				onUploadReady.accept(temp, Boolean.TRUE);
			}
			catch (IOException | RuntimeException ex)
			{
				log.debug("upload copy failed", ex);
				if (temp != null)
				{
					//noinspection ResultOfMethodCallIgnored
					temp.delete();
				}
			}
		}
		else
		{
			onUploadReady.accept(saved, Boolean.FALSE);
		}
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private long preRollMs()
	{
		int total = Math.max(4, config.clipLength());
		int post = Math.min(config.postRoll(), total - 1);
		return Math.max(1, total - post) * 1000L;
	}

	private long postRollMs()
	{
		int total = Math.max(4, config.clipLength());
		int post = Math.min(Math.max(0, config.postRoll()), total - 1);
		return post * 1000L;
	}

	private File outputDir()
	{
		return ClipStorage.outputDir(config);
	}

	private BufferedImage scale(Image image, java.awt.geom.Point2D.Double mouse)
	{
		int sw = image.getWidth(null);
		int sh = image.getHeight(null);
		if (sw <= 0 || sh <= 0)
		{
			throw new IllegalStateException("frame not ready");
		}

		int targetH = config.resolution().getHeight();
		if (targetH <= 0 || targetH >= sh)
		{
			targetH = sh; // never upscale
		}
		int targetW = Math.round((float) sw * targetH / sh);

		// H.264 requires even dimensions.
		targetW = Math.max(2, targetW - (targetW % 2));
		targetH = Math.max(2, targetH - (targetH % 2));

		BufferedImage dst = scratch.get();
		if (dst == null || dst.getWidth() != targetW || dst.getHeight() != targetH)
		{
			dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
			scratch.set(dst);
		}
		Graphics2D g = dst.createGraphics();
		// Bilinear keeps the downscale clean, but RENDER_QUALITY additionally asks Java2D for its
		// slowest paths for a difference that is invisible once the frame is JPEG-compressed.
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
		g.drawImage(image, 0, 0, targetW, targetH, null);

		// mouse is a fraction of the canvas, so it maps onto the target directly - no
		// dependency on the captured frame's size matching the canvas.
		if (mouse != null && mouse.x >= 0 && mouse.x <= 1 && mouse.y >= 0 && mouse.y <= 1)
		{
			drawCursor(g, (int) Math.round(mouse.x * targetW), (int) Math.round(mouse.y * targetH));
		}

		g.dispose();
		return dst;
	}

	/** Draws a simple arrow pointer with the tip at (x, y). */
	private static void drawCursor(Graphics2D g, int x, int y)
	{
		int[] xs = {x, x, x + 4, x + 7, x + 9, x + 6, x + 11};
		int[] ys = {y, y + 16, y + 12, y + 18, y + 17, y + 11, y + 11};
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillPolygon(xs, ys, xs.length);
		g.setColor(Color.BLACK);
		g.drawPolygon(xs, ys, xs.length);
	}

	private byte[] toJpeg(BufferedImage image) throws IOException
	{
		ImageWriter writer = jpegWriter.get();
		if (writer == null)
		{
			writer = ImageIO.getImageWritersByFormatName("jpg").next();
			jpegWriter.set(writer);
		}
		ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(Math.max(10, Math.min(100, config.quality())) / 100f);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos))
		{
			writer.setOutput(ios);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally
		{
			writer.setOutput(null);
		}
		return baos.toByteArray();
	}

	private static String sanitise(String reason)
	{
		if (reason == null)
		{
			return "clip";
		}
		return reason.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	private static ThreadPoolExecutor singleThread(String name)
	{
		return (ThreadPoolExecutor) Executors.newFixedThreadPool(1, r -> namedDaemon(r, name));
	}

	private static Thread namedDaemon(Runnable r, String name)
	{
		Thread t = new Thread(r, name);
		t.setDaemon(true);
		return t;
	}

	private static void shutdown(java.util.concurrent.ExecutorService service)
	{
		if (service != null)
		{
			service.shutdownNow();
		}
	}
}
