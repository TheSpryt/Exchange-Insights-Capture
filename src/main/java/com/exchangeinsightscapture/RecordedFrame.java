package com.exchangeinsightscapture;

/**
 * A single captured frame, held in the rolling buffer as JPEG-compressed bytes
 * (rather than a raw {@link java.awt.image.BufferedImage}) to keep memory use
 * bounded while several seconds of footage are retained.
 */
class RecordedFrame
{
	final long timestampMs;
	final byte[] jpeg;
	/** Encoded size. H.264 needs every frame in a clip to be the same shape, so a change
	 *  here means the buffered frames can no longer be encoded together. */
	final int width;
	final int height;

	RecordedFrame(long timestampMs, byte[] jpeg, int width, int height)
	{
		this.timestampMs = timestampMs;
		this.jpeg = jpeg;
		this.width = width;
		this.height = height;
	}
}
