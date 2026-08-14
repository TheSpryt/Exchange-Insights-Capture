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

	RecordedFrame(long timestampMs, byte[] jpeg)
	{
		this.timestampMs = timestampMs;
		this.jpeg = jpeg;
	}
}
