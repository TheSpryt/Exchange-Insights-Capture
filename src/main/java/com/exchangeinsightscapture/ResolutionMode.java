package com.exchangeinsightscapture;

/**
 * Target vertical resolution for saved clips. Frames are downscaled to this
 * height (preserving the client's aspect ratio); the client is never upscaled,
 * so a smaller game window is captured at its native size.
 */
public enum ResolutionMode
{
	MATCH_CLIENT("Match client", 0),
	P1080("1080p", 1080),
	P720("720p", 720),
	P480("480p", 480),
	P360("360p", 360);

	private final String label;
	private final int height;

	ResolutionMode(String label, int height)
	{
		this.label = label;
		this.height = height;
	}

	/** Target height in pixels, or 0 to keep the captured size. */
	public int getHeight()
	{
		return height;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
