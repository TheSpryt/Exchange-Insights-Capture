package com.exchangeinsightscapture;

import java.awt.AWTError;
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
	private static final String PART_SUFFIX = ClipStorage.PART_SUFFIX;

	/** How long capture must return nothing but flat colour before Robot is set aside. */
	private static final long BLANK_MS_BEFORE_FALLBACK = 10_000;

	/** How long to stay on the fallback before giving Robot another chance. */
	private static final long SCREEN_GRAB_RETRY_MS = 60_000;

	private static final long COOLDOWN_MS = 1500;
	private static final long EVICT_SLACK_MS = 750;
	/** If a requested frame never arrives (client not rendering), re-arm after this long. */
	private static final long FRAME_REQUEST_TIMEOUT_MS = 2000;
	/** Floor for the adaptive buffer quality; below this the intermediate step starts to show. */
	private static final float MIN_BUFFER_QUALITY = 0.6f;

	/**
	 * Ceiling for the buffer's JPEG quality.
	 *
	 * <p>Not 1.0, which is what this used to sit at. Measured on real captured frames at
	 * 1310x720: quality 1.0 costs 629KB a frame against 134KB at 0.8 - nearly five times the
	 * memory - because the top of the JPEG scale all but disables quantisation. That is spent on
	 * an intermediate frame which H.264 then re-compresses anyway, so none of it reaches the clip.
	 *
	 * <p>It was also the reason the buffer kept hitting its ceiling and truncating the lead-up: at
	 * 80fps, fifteen seconds at 1.0 needs 736MB. At this setting the same window is under 200MB,
	 * and the adaptive step-down rarely has to intervene at all.
	 */
	private static final float MAX_BUFFER_QUALITY = 0.85f;

	private final ExchangeInsightsCaptureConfig config;
	private final DrawManager drawManager;
	private final BooleanSupplier canCapture;
	private final Supplier<java.awt.geom.Point2D.Double> mousePosition;
	/** The client canvas' position and size on screen, for the screen-capture source. */
	private Supplier<java.awt.Rectangle> canvasBounds = () -> null;
	private java.awt.Robot robot;

	/** Pointer position measured against the last screen grab; null when it was outside. */
	private volatile java.awt.geom.Point2D.Double screenMouse;
	/** True when the frame in flight came from the desktop rather than from the renderer. */
	private volatile boolean lastFrameWasScreen;
	private final Consumer<File> onSaved;
	private final Consumer<String> onError;
	/** Told when a pending clip changes state, so the side panel can redraw it. */
	private Runnable onPendingChanged = () -> { };

	void setPendingListener(Runnable listener)
	{
		this.onPendingChanged = listener;
	}

	/** Receives the file to upload plus whether it is a throwaway that should be deleted after. */
	private java.util.function.BiConsumer<File, Boolean> onUploadReady = (f, tmp) -> { };

	private ScheduledExecutorService scheduler;
	private ThreadPoolExecutor workers;
	/** Performs the screen grab, off the render thread. */
	private ThreadPoolExecutor grabber;
	private Runnable everyFrame;
	private ThreadPoolExecutor processor;
	private ThreadPoolExecutor encoder;

	// Mirrors the processor-thread `capturing` flag for cross-thread reads (overlay).
	private volatile boolean recording;
	// Mirrors manual-session state for the overlay and side panel.
	private volatile boolean sessionActive;
	private volatile int sessionFrameCount;
	private volatile long sessionStartedMs;
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
	private PendingClip activePending;
	private long lastClipMs;
	/** Bytes held in the rolling buffer, tracked rather than recomputed each frame. */
	private long bufferedBytes;
	private long lastTrimWarnMs;
	/**
	 * Quality of the in-memory JPEG buffer, adjusted automatically.
	 *
	 * <p>There is deliberately no setting for this. It was removed because at a fixed capture
	 * rate the memory cost was predictable, but capture now follows the client - so the same
	 * buffer holds four times as much at 240fps as at 60. Left at maximum it fills the memory
	 * ceiling and the oldest frames get dropped, which silently shortens the lead-up that is
	 * the entire point of a replay buffer.
	 *
	 * <p>Trading a little buffer quality for keeping the full window is the better bargain, and
	 * it only happens under pressure: with headroom this sits at maximum. The loss is in an
	 * intermediate step that H.264 re-compresses anyway.
	 */
	private volatile float bufferQuality = MAX_BUFFER_QUALITY;

	/**
	 * The folder clips file under for the logged-in character, e.g. "Spryt-Demonic Pacts League".
	 *
	 * <p>Pushed in by the plugin rather than read on demand: the client's player can only be read
	 * safely on the client thread, and clips finish encoding long after - often once the player
	 * has logged out entirely.
	 */
	private volatile String accountFolder;

	/**
	 * "Zulrah(150) 2026-08-14_21-30-00", matching how RuneLite names screenshots.
	 *
	 * <p>Subject first so a folder sorts by what happened and then by when; the timestamp both
	 * disambiguates and says at a glance which of two Zulrah clips is which. A clip with nothing
	 * to say for itself - a manual capture - is just the timestamp.
	 */
	private static String clipName(String subject)
	{
		final String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		final String clean = ClipStorage.safeName(subject == null ? "" : subject);
		return clean.isEmpty() ? stamp : clean + " " + stamp;
	}

	/** Told by the plugin whenever the logged-in character changes. */
	void setAccountFolder(String folder)
	{
		this.accountFolder = folder;
	}

	/** True while the character is still unknown, so the plugin knows to keep trying. */
	boolean needsAccountFolder()
	{
		return accountFolder == null || accountFolder.isEmpty();
	}

	/**
	 * A clip the user is waiting on: named and listable from the moment its trigger fires,
	 * long before a file exists. Giving it identity up front is what lets the side panel
	 * show, rename and cancel a clip that is still being captured or encoded.
	 */
	static final class PendingClip
	{
		final long id;
		final String reason;
		/** Which folder this clip files itself under, and under which account. */
		final ClipTrigger trigger;
		final String account;
		/** File name without extension. Mutable: the user may rename before it is written. */
		volatile String name;
		volatile boolean cancelled;
		/** True once the encoder has started writing, after which cancelling deletes the part-file. */
		volatile boolean encoding;

		PendingClip(long id, String reason, String name, ClipTrigger trigger, String account)
		{
			this.id = id;
			this.reason = reason;
			this.name = name;
			this.trigger = trigger;
			this.account = account;
		}
	}

	private final java.util.concurrent.atomic.AtomicLong pendingIds = new java.util.concurrent.atomic.AtomicLong();
	/** Copy-on-write: the panel iterates this from the EDT while the recorder mutates it. */
	private final java.util.List<PendingClip> pending = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Clips currently being captured or encoded, oldest first. */
	java.util.List<PendingClip> getPending()
	{
		return java.util.Collections.unmodifiableList(pending);
	}

	/** Rename a clip that has not been written yet. Safe from any thread. */
	void renamePending(long id, String name)
	{
		for (PendingClip p : pending)
		{
			if (p.id == id)
			{
				p.name = name;
				return;
			}
		}
	}

	/**
	 * Cancel a pending clip. If it is still being captured the take is abandoned; if it is
	 * queued it is skipped; if it is already encoding the encoder notices between frames and
	 * removes the part-file.
	 */
	void cancelPending(long id)
	{
		for (PendingClip p : pending)
		{
			if (p.id == id)
			{
				p.cancelled = true;
				break;
			}
		}
		final ThreadPoolExecutor proc = processor;
		if (proc != null && !proc.isShutdown())
		{
			proc.execute(() -> abortIfCancelled(id));
		}
	}

	/** Processor thread: drop an in-flight capture whose clip was cancelled. */
	private void abortIfCancelled(long id)
	{
		if (activePending != null && activePending.id == id && capturing)
		{
			capturing = false;
			recording = false;
			activeClip = null;
			pending.remove(activePending);
			activePending = null;
		}
	}

	// Manual mode: a single take that accumulates every frame from arm to disarm,
	// rather than the rolling window. Bounded by maxManualLength so a forgotten
	// recording cannot grow without limit.
	private List<RecordedFrame> sessionFrames;

	ClipRecorder(ExchangeInsightsCaptureConfig config, DrawManager drawManager, BooleanSupplier canCapture,
		Supplier<java.awt.geom.Point2D.Double> mousePosition, Consumer<File> onSaved, Consumer<String> onError)
	{
		this.config = config;
		this.drawManager = drawManager;
		this.canCapture = canCapture;
		this.mousePosition = mousePosition;
		this.onSaved = onSaved;
		this.onError = onError;
	}

	void setCanvasBounds(Supplier<java.awt.Rectangle> bounds)
	{
		this.canvasBounds = bounds;
	}

	void setUploadHandler(java.util.function.BiConsumer<File, Boolean> handler)
	{
		this.onUploadReady = handler;
	}

	void start()
	{
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> namedDaemon(r, "instant-replay-capture"));
		grabber = singleThread("instant-replay-grab");
		// Scale + JPEG for several frames at once. Capped well below the core count: this runs
		// alongside the game, and the goal is to stop frames queueing, not to hog the CPU.
		final int poolSize = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4));
		workers = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, r -> namedDaemon(r, "instant-replay-worker"));
		processor = singleThread("instant-replay-processor");
		encoder = singleThread("instant-replay-encoder");

		// Anything left wearing the in-progress suffix is from an encode that never finished -
		// a crash or a kill - and will never be resumed, so it is dead weight on disk.
		sweepPartials();

		// One capture per frame the client actually draws, rather than a timer guessing at the
		// rate. The listener runs ON THE RENDER THREAD, so it does the least work possible -
		// claim a slot and hand off - because anything heavier there is exactly the stall this
		// plugin spent so long removing.
		everyFrame = this::onClientFrame;
		drawManager.registerEveryFrameListener(everyFrame);

		// The scheduler now only finalises clips whose post-roll has elapsed, which still has
		// to happen once the client stops drawing entirely (logout, minimised).
		scheduler.scheduleAtFixedRate(this::postRollTick, 250, 250, TimeUnit.MILLISECONDS);
	}

	/** Delete half-written clips left behind by an encode that did not complete. */
	private void sweepPartials()
	{
		try
		{
			sweepPartials(outputDir(), 0);
		}
		catch (RuntimeException e)
		{
			log.debug("could not sweep unfinished clips", e);
		}
	}

	/** Walks the account and category folders clips are filed into. Bounded, like every walk here. */
	private void sweepPartials(File dir, int depth)
	{
		final File[] found = dir.listFiles();
		if (found == null)
		{
			return;
		}
		for (File f : found)
		{
			if (f.isFile() && f.getName().endsWith(PART_SUFFIX))
			{
				if (f.delete())
				{
					log.debug("removed unfinished clip {}", f.getName());
				}
			}
			else if (f.isDirectory() && depth < 2)
			{
				sweepPartials(f, depth + 1);
			}
		}
	}

	void stop()
	{
		if (everyFrame != null)
		{
			drawManager.unregisterEveryFrameListener(everyFrame);
			everyFrame = null;
		}

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
		shutdown(grabber);
		shutdown(workers);
		shutdown(processor);
		shutdown(encoder);
		scheduler = null;
		grabber = null;
		workers = null;
		processor = null;
		encoder = null;
		buffer.clear();
		bufferedBytes = 0;
		capturing = false;
		recording = false;
		activeClip = null;
		sessionFrames = null;
		sessionActive = false;
		sessionFrameCount = 0;
		pendingEncodes.set(0);
		pending.clear();
		activePending = null;
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

	/**
	 * Clips the user is waiting on: those encoding, PLUS one still collecting its post-event
	 * padding.
	 *
	 * <p>The padding is part of producing the clip, not a separate state - so from the moment a
	 * trigger fires the answer to "is it doing something about my keypress" is yes. Counting
	 * only queued encodes meant the overlay sat unchanged for the whole post-roll and the press
	 * looked like it had been ignored.
	 */
	int getSavingCount()
	{
		return pending.size();
	}

	/** Whether a manual take is armed and accumulating frames. */
	boolean isSessionActive()
	{
		return sessionActive;
	}

	/** Frames captured so far in the current manual take. */
	int getSessionFrameCount()
	{
		return sessionFrameCount;
	}

	/** Elapsed length of the current manual take, in seconds. Wall-clock rather than derived
	 *  from a frame count, because the capture rate now varies with the client. */
	int getSessionSeconds()
	{
		final long started = sessionStartedMs;
		return started <= 0 ? 0 : (int) ((System.currentTimeMillis() - started) / 1000L);
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
			final String account = accountFolder;
			p.execute(() -> finishSession(ClipTrigger.MANUAL, "", account));
		}
	}

	/**
	 * Discard the rolling buffer and start collecting again.
	 *
	 * <p>Kept for the cases that genuinely invalidate what is buffered - a client resize, or
	 * leaving a capture mode. Capture itself no longer needs re-arming, because it follows the
	 * client's frames rather than a timer that had to be rebuilt at a new period.
	 */
	void restartCapture()
	{
		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			p.execute(() ->
			{
				buffer.clear();
				bufferedBytes = 0;
				capturing = false;
				recording = false;
				activeClip = null;
			});
		}
		framePending.set(false);
	}

	/** Request a clip; safe to call from any thread (e.g. the client thread). */
	/**
	 * Request a clip.
	 *
	 * @param trigger  which category it files under.
	 * @param subject  what to call it - "Zulrah(150)", "Attack(99)" - or empty for just a time.
	 */
	void trigger(ClipTrigger trigger, String subject)
	{
		final ThreadPoolExecutor p = processor;
		if (p != null && !p.isShutdown())
		{
			final String account = accountFolder;
			p.execute(() -> beginClip(trigger, subject, account));
		}
	}

	// ------------------------------------------------------------------
	// Capture pipeline
	// ------------------------------------------------------------------

	/**
	 * Called once per rendered frame, ON THE RENDER THREAD. Deliberately trivial: claim the
	 * single outstanding-capture slot and hand the work to another thread.
	 *
	 * <p>When a grab is still in flight the frame is skipped rather than queued, so a client
	 * drawing faster than we can copy degrades to a lower capture rate instead of building a
	 * backlog it can never clear.
	 */
	private void onClientFrame()
	{
		if (!canCapture.getAsBoolean() || !claimFrameRequest())
		{
			return;
		}
		final ThreadPoolExecutor g = grabber;
		if (g == null || g.isShutdown())
		{
			framePending.set(false);
			return;
		}
		g.execute(this::grabAndProcess);
	}

	/**
	 * Whether Robot may be used for capture at all.
	 *
	 * <p>Turned off permanently for the session once screen capture is shown not to work, because
	 * the failure is silent. Robot does not throw on Wayland or on macOS without Screen Recording
	 * permission - it hands back a perfectly valid all-black image - so without this check the
	 * plugin would cheerfully record black clips and report success.
	 */
	private volatile boolean screenGrabUsable = !isWayland();

	/** When the current unbroken run of featureless grabs started, or 0 if there isn't one. */
	private long blankSinceMs;

	/** When to try Robot again after giving up on it, so a bad guess is not permanent. */
	private volatile long retryScreenGrabAtMs;

	/**
	 * Wayland does not let an application screenshot the desktop through X11 APIs, and Robot has
	 * no way to say so - it just returns black. Detected up front rather than by symptom.
	 */
	private static boolean isWayland()
	{
		final String session = System.getenv("XDG_SESSION_TYPE");
		return (session != null && session.toLowerCase().contains("wayland"))
			|| System.getenv("WAYLAND_DISPLAY") != null;
	}

	/**
	 * Reject a capture that carries no picture, and give up on Robot if they keep coming.
	 *
	 * <p>Measured in seconds, not frames. This first counted 30 blank frames in a row, which
	 * sounds like a lot and is not: capture follows the client, so at 100fps that is a third of a
	 * second, and a black window while the client is still starting up cleared it easily. It
	 * misfired on Windows, where screen capture works perfectly.
	 *
	 * <p>A broken setup returns black forever, so waiting several seconds costs those users
	 * nothing, while no loading screen or fade lasts that long. Giving up is also temporary now -
	 * Robot is retried later - so a wrong guess costs a stretch of slower capture rather than the
	 * whole session.
	 */
	private java.awt.image.BufferedImage checkNotBlank(java.awt.image.BufferedImage shot)
	{
		if (shot == null)
		{
			return null;
		}
		if (!uniform(shot))
		{
			blankSinceMs = 0;
			return shot;
		}

		final long now = System.currentTimeMillis();
		if (blankSinceMs == 0)
		{
			blankSinceMs = now;
		}
		if (now - blankSinceMs < BLANK_MS_BEFORE_FALLBACK)
		{
			return shot;
		}

		log.warn("Screen capture has returned blank frames for {}s; falling back to the client's own "
				+ "frames and retrying later. On Linux this usually means Wayland, on macOS a missing "
				+ "Screen Recording permission.", (now - blankSinceMs) / 1000);
		screenGrabUsable = false;
		retryScreenGrabAtMs = now + SCREEN_GRAB_RETRY_MS;
		blankSinceMs = 0;
		return null;
	}

	/** True when every sampled pixel matches, which no real frame of the game manages. */
	private static boolean uniform(java.awt.image.BufferedImage image)
	{
		final int w = image.getWidth();
		final int h = image.getHeight();
		if (w < 8 || h < 8)
		{
			return false;
		}
		final int first = image.getRGB(0, 0);
		for (int y = 0; y < 8; y++)
		{
			for (int x = 0; x < 8; x++)
			{
				if (image.getRGB(x * (w - 1) / 7, y * (h - 1) / 7) != first)
				{
					return false;
				}
			}
		}
		return true;
	}

	/** Grab thread: copy the client's rectangle, off the render loop. */
	private void grabAndProcess()
	{
		try
		{
			final java.awt.Image shot = grabScreen();
			if (shot != null)
			{
				lastFrameWasScreen = true;
				onFrameImage(shot);
			}
			else
			{
				// Not grabbable right now (minimised, not showing): ask the client for its own
				// frame instead, so capture does not simply stop.
				lastFrameWasScreen = false;
				drawManager.requestNextFrameListener(this::onFrameImage);
			}
		}
		catch (Exception e)
		{
			log.debug("capture failed", e);
			framePending.set(false);
		}
	}

	/** Finalises a clip whose post-roll has elapsed, even once the client stops drawing. */
	private void postRollTick()
	{
		try
		{
			final ThreadPoolExecutor p = processor;
			if (p != null && !p.isShutdown())
			{
				p.execute(this::checkPostRollTimeout);
			}
		}
		catch (Exception e)
		{
			log.debug("post-roll tick failed", e);
		}
	}

	/**
	 * Where the pointer sits inside {@code bounds}, as a 0-1 fraction, or null when it is
	 * outside the captured area entirely - in which case no marker should be drawn at all.
	 */
	private static java.awt.geom.Point2D.Double mouseWithin(java.awt.Rectangle bounds)
	{
		try
		{
			final java.awt.PointerInfo info = java.awt.MouseInfo.getPointerInfo();
			if (info == null)
			{
				return null;
			}
			final java.awt.Point at = info.getLocation();
			final double fx = (at.x - bounds.x) / (double) bounds.width;
			final double fy = (at.y - bounds.y) / (double) bounds.height;
			if (fx < 0 || fx > 1 || fy < 0 || fy > 1)
			{
				return null;
			}
			return new java.awt.geom.Point2D.Double(fx, fy);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	/**
	 * The rate a clip was actually captured at, measured from its own frame timestamps.
	 *
	 * <p>There is no configured rate to encode at any more, and frames are skipped whenever a
	 * grab is still in flight, so the only honest answer comes from the frames themselves.
	 * Getting this wrong makes clips play back too fast or too slow.
	 */
	private static int measuredRate(List<RecordedFrame> clip)
	{
		if (clip == null || clip.size() < 2)
		{
			return 30;
		}
		final long span = clip.get(clip.size() - 1).timestampMs - clip.get(0).timestampMs;
		if (span <= 0)
		{
			return 30;
		}
		return Math.max(1, Math.min(240, (int) Math.round((clip.size() - 1) * 1000.0 / span)));
	}

	/**
	 * Copy the client's on-screen rectangle straight from the desktop.
	 *
	 * <p>Runs on the capture thread, so the cost lands here rather than on the renderer. Null
	 * when the window is not showing or the OS refuses the grab, in which case the tick is
	 * simply skipped.
	 */
	private java.awt.Image grabScreen()
	{
		try
		{
			final java.awt.Rectangle bounds = canvasBounds.get();
			if (bounds == null || bounds.width <= 0 || bounds.height <= 0)
			{
				return null;
			}
			if (!screenGrabUsable)
			{
				// Whatever made capture blank may be gone - the window was minimised, or a
				// permission was granted - so try again occasionally rather than writing off
				// the fast path for the rest of the session on one bad stretch.
				if (System.currentTimeMillis() < retryScreenGrabAtMs)
				{
					return null;
				}
				screenGrabUsable = true;
			}
			if (robot == null)
			{
				robot = new java.awt.Robot();
			}
			// The exact rectangle is known here, so the real pointer position maps straight into
			// it - no canvas coordinate space to translate and no stretched-mode correction, which
			// is where the marker used to drift. Robot does not capture the cursor itself (the OS
			// composites it separately), so it still has to be drawn.
			screenMouse = mouseWithin(bounds);
			final java.awt.image.BufferedImage shot = robot.createScreenCapture(bounds);
			return checkNotBlank(shot);
		}
		catch (Exception | AWTError e)
		{
			log.debug("screen capture failed", e);
			return null;
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
		final java.awt.geom.Point2D.Double mouse = !config.drawCursor() ? null
			: lastFrameWasScreen ? screenMouse : mousePosition.get();
		w.execute(() -> processFrame(image, now, mouse));
	}

	private void processFrame(Image image, long now, java.awt.geom.Point2D.Double mouse)
	{
		try
		{
			BufferedImage scaled = scale(image, mouse);
			byte[] jpeg = toJpeg(scaled);
			RecordedFrame frame = new RecordedFrame(now, jpeg, scaled.getWidth(), scaled.getHeight());

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

			// The client was resized. Frames of two different shapes cannot go into one H.264
			// clip, so the older ones are discarded and the buffer refills at the new size -
			// the same trade the framerate change makes, and for the same reason.
			if (!buffer.isEmpty() && (buffer.peekLast().width != frame.width
				|| buffer.peekLast().height != frame.height))
			{
				buffer.clear();
				bufferedBytes = 0;
				capturing = false;
				recording = false;
				activeClip = null;
				if (sessionFrames != null)
				{
					sessionFrames.clear();
				}
				log.debug("client resized to {}x{}, buffer reset", frame.width, frame.height);
			}

			// A manual take keeps everything and bypasses the rolling window entirely.
			if (sessionFrames != null)
			{
				sessionFrames.add(frame);
				sessionFrameCount = sessionFrames.size();
				if (sessionFrames.size() >= maxSessionFrames())
				{
					finishSession(ClipTrigger.MANUAL, "", accountFolder);
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
			bufferedBytes += frame.jpeg.length;
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

	/**
	 * Nudge the buffer quality toward whatever keeps the full window in memory.
	 *
	 * <p>Small steps with a wide dead-band: the frames already buffered keep the quality they
	 * were captured at, so reacting sharply would make a clip visibly change quality partway
	 * through. Drifting gets there without a seam.
	 */
	private void adaptBufferQuality(long limit)
	{
		final float current = bufferQuality;
		if (bufferedBytes > limit * 0.75 && current > MIN_BUFFER_QUALITY)
		{
			bufferQuality = Math.max(MIN_BUFFER_QUALITY, current - 0.05f);
		}
		else if (bufferedBytes < limit * 0.5 && current < 1.0f)
		{
			bufferQuality = Math.min(MAX_BUFFER_QUALITY, current + 0.02f);
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
			bufferedBytes -= buffer.removeFirst().jpeg.length;
		}

		// Second ceiling, on memory. At full client resolution and maximum buffer quality a
		// few seconds of frames can run to hundreds of megabytes, which would take the client
		// down. Dropping the oldest frames shortens the lead-up; running out of heap loses
		// everything, so this is the better failure.
		final long limit = Math.max(64, config.maxBufferMb()) * 1024L * 1024L;
		adaptBufferQuality(limit);
		boolean trimmed = false;
		while (bufferedBytes > limit && buffer.size() > 1)
		{
			bufferedBytes -= buffer.removeFirst().jpeg.length;
			trimmed = true;
		}
		if (trimmed && now - lastTrimWarnMs > 60_000)
		{
			lastTrimWarnMs = now;
			// Say what was actually lost, not just that something was: the number that matters
			// is how much lead-up a clip would now contain versus the length that was asked for.
			final long heldMs = buffer.isEmpty() ? 0 : now - buffer.peekFirst().timestampMs;
			log.debug("buffer at the {}MB limit: holding {}s of lead-up, {}s was requested",
				config.maxBufferMb(), heldMs / 1000, Math.max(4, config.clipLength()));
		}
	}

	// ------------------------------------------------------------------
	// Clip lifecycle (processor thread)
	// ------------------------------------------------------------------

	private void beginClip(ClipTrigger trigger, String subject, String account)
	{
		long now = System.currentTimeMillis();
		if (capturing || now - lastClipMs < COOLDOWN_MS)
		{
			return;
		}
		activeClip = new ArrayList<>(buffer);
		activeReason = trigger.folder();
		// Name it now: the timestamp then reflects when the event happened rather than when
		// the encoder happened to reach it, and the panel has something to show and rename
		// while the clip is still being produced.
		activePending = new PendingClip(pendingIds.incrementAndGet(), trigger.folder(),
			clipName(subject), trigger, account);
		pending.add(activePending);
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
		bufferedBytes = 0;
		capturing = false;
		recording = false;
		activeClip = null;
		sessionFrames = new ArrayList<>();
		sessionFrameCount = 0;
		sessionStartedMs = System.currentTimeMillis();
		sessionActive = true;
	}

	private void finishSession(ClipTrigger trigger, String subject, String account)
	{
		final PendingClip entry = new PendingClip(pendingIds.incrementAndGet(), trigger.folder(),
			clipName(subject), trigger, account);
		final List<RecordedFrame> clip = sessionFrames;
		sessionFrames = null;
		sessionActive = false;
		sessionFrameCount = 0;
		if (clip == null)
		{
			return;
		}
		lastClipMs = System.currentTimeMillis();

		final int fps = measuredRate(clip);
		final ThreadPoolExecutor e = encoder;
		if (e != null && !e.isShutdown() && !clip.isEmpty())
		{
			pending.add(entry);
			pendingEncodes.incrementAndGet();
			e.execute(() -> encodeAndSave(clip, entry, fps));
		}
		else if (clip.isEmpty())
		{
			onError.accept("Nothing was captured - the take was too short or the client was not rendering.");
		}
	}

	/** Frame ceiling for a manual take, from the configured length limit and framerate. */
	private int maxSessionFrames()
	{
		// No configured rate any more; maxBufferMb is the real bound, so assume a high
		// rate here and let the memory ceiling do the limiting.
		return Math.max(1, config.maxManualLength()) * 120;
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
		final PendingClip entry = activePending;
		final int fps = measuredRate(clip);
		activeClip = null;
		activeReason = null;
		activePending = null;

		final ThreadPoolExecutor e = encoder;
		if (e != null && !e.isShutdown() && clip != null && !clip.isEmpty() && entry != null)
		{
			pendingEncodes.incrementAndGet();
			e.execute(() -> encodeAndSave(clip, entry, fps));
		}
		else if (entry != null)
		{
			pending.remove(entry);
		}
	}

	private void encodeAndSave(List<RecordedFrame> frames, PendingClip entry, int fps)
	{
		File saved = null;
		String error = null;

		try
		{
			if (entry.cancelled)
			{
				return;
			}
			// Filed by account and category, the way RuneLite files screenshots. clipDir creates
			// the folders; a clip triggered before the character is known falls back to the root.
			File dir = ClipStorage.clipDir(config, entry.account, entry.trigger);
			// Read the name at the last moment: the user may have renamed it while it queued.
			// uniqueFile, not new File: the encoder truncates whatever path it is given, so a
			// name that collides with an existing clip would otherwise destroy it silently.
			File out = ClipStorage.uniqueFile(dir, entry.name, ".mp4");
			entry.encoding = true;
			// Tell the panel, or its card sits on "capturing..." for the whole encode - which for a
			// long clip is most of the minute the user spends watching it.
			onPendingChanged.run();

			// Encode beside the destination, then move into place.
			//
			// The muxer writes as it goes, so encoding straight to the .mp4 left a growing,
			// unplayable file sitting in the clips folder for the whole minute-long encode - and
			// the panel lists that folder, so the clip appeared twice: once as the pending card
			// and again as a real clip whose size crept upward. A suffix the listing ignores keeps
			// it invisible until it is finished, and also means a crash mid-encode cannot leave
			// something behind that looks like a playable clip.
			final File part = new File(dir, out.getName() + PART_SUFFIX);
			ClipEncoder.encode(part, frames, fps, config.clipQuality().quantiser(),
				() -> entry.cancelled);
			if (entry.cancelled)
			{
				//noinspection ResultOfMethodCallIgnored
				part.delete();
				return;
			}

			try
			{
				java.nio.file.Files.move(part.toPath(), out.toPath(),
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.io.IOException | UnsupportedOperationException moveFailed)
			{
				// Same directory, so this should not happen; fall back rather than lose the clip.
				log.debug("atomic move failed for {}, retrying plain", out.getName(), moveFailed);
				java.nio.file.Files.move(part.toPath(), out.toPath(),
					java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			saved = out;
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Failed to save Exchange Insights Capture clip", ex);
			error = ex.getMessage();
		}
		finally
		{
			// Drop the count and the pending entry BEFORE notifying. The listeners redraw the
			// side panel, which only repaints when told to - firing them while this still read
			// 1 left the panel stuck on "Saving clip..." forever, with nothing to correct it.
			pending.remove(entry);
			pendingEncodes.decrementAndGet();
		}

		if (saved == null)
		{
			onError.accept(error);
			return;
		}
		onSaved.accept(saved);

		if (config.uploadClips())
		{
			// Clips are H.264 throughout now, so the upload sends the file we just wrote -
			// no separate encode, and nothing temporary to clean up.
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

		// Capture at the client's own size. Downscaling only ever lost detail: the encoder is
		// the quality bottleneck, not the resolution, and a fixed target made a large client
		// blurry for no saving worth having.
		// H.264 requires even dimensions, so trim at most one pixel per axis.
		final int targetW = Math.max(2, sw - (sw % 2));
		final int targetH = Math.max(2, sh - (sh % 2));

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
		// Maximum while there is memory headroom, easing back under pressure - see bufferQuality.
		param.setCompressionQuality(bufferQuality);

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
