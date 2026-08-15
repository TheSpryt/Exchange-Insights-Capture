package com.exchangeinsightscapture;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One clip, wherever it happens to live.
 *
 * <p>A clip can exist on disk, on the account, or both, and the panel must show it exactly
 * once either way - two rows for one recording would be a bug, not a feature. So local files
 * and the account's listing are merged here on name, and the row carries which copies exist.
 *
 * <p>Name is the join key rather than any id, because the local file has no id: the plugin
 * uploads under the same filename it wrote, so the names correspond. A clip renamed in one
 * place and not the other separates into two rows, which is the honest result - they really
 * are two differently-named things until one is renamed to match.
 */
final class ClipEntry
{
	/** Display name, without the .mp4 extension. */
	final String name;
	/** The file on disk, or null when this clip only exists on the account. */
	final File local;
	/** The account's id for this clip, or null when it has not been uploaded. */
	final Long remoteId;
	final long bytes;
	/** Sort key: file mtime for local clips, upload time for remote-only ones. */
	final long sortAt;

	private ClipEntry(String name, File local, Long remoteId, long bytes, long sortAt)
	{
		this.name = name;
		this.local = local;
		this.remoteId = remoteId;
		this.bytes = bytes;
		this.sortAt = sortAt;
	}

	boolean isLocal()
	{
		return local != null;
	}

	boolean isUploaded()
	{
		return remoteId != null;
	}

	/** True when both copies exist - the state where deleting has to be explicit about both. */
	boolean isSynced()
	{
		return isLocal() && isUploaded();
	}

	private static String key(String name)
	{
		final String base = name.toLowerCase().endsWith(".mp4") ? name.substring(0, name.length() - 4) : name;
		return base.toLowerCase();
	}

	/** Merge what is on disk with what the account holds into one row per clip, newest first. */
	static List<ClipEntry> merge(List<ClipLibrary.Entry> localClips, List<ClipUploader.RemoteClip> remoteClips)
	{
		final Map<String, ClipEntry> byName = new LinkedHashMap<>();

		for (ClipLibrary.Entry e : localClips)
		{
			byName.put(key(e.name), new ClipEntry(e.displayName(), e.file, null, e.bytes, e.modifiedAt));
		}

		for (ClipUploader.RemoteClip r : remoteClips)
		{
			if (r == null || r.name == null)
			{
				continue;
			}
			final String k = key(r.name);
			final ClipEntry existing = byName.get(k);
			if (existing != null)
			{
				// Same clip in both places: keep the local file (it is what we can preview and
				// open) and attach the account id so the row can act on both copies.
				byName.put(k, new ClipEntry(existing.name, existing.local, r.id, existing.bytes, existing.sortAt));
			}
			else
			{
				final String display = r.name.toLowerCase().endsWith(".mp4")
					? r.name.substring(0, r.name.length() - 4)
					: r.name;
				// createdAt is in seconds server-side; local mtimes are millis.
				byName.put(k, new ClipEntry(display, null, r.id, r.bytes, r.createdAt * 1000L));
			}
		}

		final List<ClipEntry> out = new ArrayList<>(byName.values());
		out.sort(Comparator.comparingLong((ClipEntry e) -> e.sortAt).reversed());
		return out;
	}
}
