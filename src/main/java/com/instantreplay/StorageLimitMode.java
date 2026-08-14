package com.instantreplay;

/** What to do once saved clips exceed the local size limit. */
public enum StorageLimitMode
{
	/** Delete the oldest clips until the folder is back under the limit. */
	AUTO_DELETE("Auto delete"),
	/** Keep everything and warn in chat instead. */
	WARN("Warn only");

	private final String label;

	StorageLimitMode(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
