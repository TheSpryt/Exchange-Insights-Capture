package com.exchangeinsightscapture;

/**
 * How the plugin captures. The mode decides whether frames are being sampled at
 * all, which is the dominant performance factor: sampling a frame forces the
 * client to hand back what it rendered, and on the GPU/117HD renderers that
 * means a GPU readback.
 */
public enum CaptureMode
{
	/** Nothing is captured and no triggers fire. Zero cost. */
	OFF("Off"),
	/** Always buffering the last few seconds; configured triggers save clips. */
	AUTO("Automatic"),
	/** Idle until armed with the hotkey, then records until disarmed. */
	MANUAL("Manual");

	private final String label;

	CaptureMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
