package com.exchangeinsightscapture;

/**
 * How many frames a second to capture.
 *
 * <p>A CEILING, not a target. Capture follows the client's own drawing - there is no timer asking
 * for frames - so a client rendering at 40fps gives 40fps clips whatever is chosen here. What this
 * does is throw frames away when the client draws faster than the setting asks for.
 *
 * <p>Uncapped capture is why this exists. The buffer, the encode and the finished file all scale
 * with the number of frames, so a client running at 150fps costs two and a half times a 60fps one
 * for a difference nobody can see on a replay. The memory ceiling absorbed that by dropping the
 * oldest frames, which quietly shortened the lead-up that is the whole point of a replay buffer.
 *
 * <p>Since capture moved to the renderer, this setting also decides how much the game gives up.
 * Every requested frame costs a synchronous readback from the GPU plus a million-pixel conversion,
 * on the client's own draw, and none of it happens for a frame nobody asked for - so the cost is
 * linear in this number. Measured on a machine that runs the game comfortably: 60 is affordable,
 * and 120 takes around 80fps off the game. That is why the highest setting says so.
 *
 * <p>Public for the same reason {@link ClipQuality} is: RuneLite implements the config interface
 * with a dynamic proxy, and a proxy cannot reach a package-private type.
 */
public enum ClipFramerate
{
	/** Smallest files and the longest buffer window. Fine for anything that is not fast-moving. */
	FPS_30(30),
	/** The default, and the rate the client itself draws the game at. */
	FPS_50(50),
	/** Matches an ordinary display's refresh, for slightly smoother playback than the game runs. */
	FPS_60(60),
	/**
	 * For high-refresh monitors, and expensive: twice 60 in memory, encode time and disk, and
	 * enough readback to cost the game most of its own framerate.
	 */
	FPS_120(120);

	private final int fps;

	ClipFramerate(int fps)
	{
		this.fps = fps;
	}

	int fps()
	{
		return fps;
	}

	/**
	 * The gap between captures.
	 *
	 * <p>In nanoseconds because milliseconds cannot hold it: 60fps is 16.67ms, and rounding that
	 * to 17 would cap at 58.8fps instead - an error that compounds over a whole clip.
	 */
	long periodNanos()
	{
		return 1_000_000_000L / fps;
	}

	@Override
	public String toString()
	{
		// The cost of the top setting is not guessable from the number, so the menu says it.
		return fps + (this == FPS_120 ? " FPS (heavy)" : " FPS");
	}
}
