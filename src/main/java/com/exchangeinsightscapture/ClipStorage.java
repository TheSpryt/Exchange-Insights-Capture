package com.exchangeinsightscapture;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Owns the clip folder: where it is, and keeping it under the configured size.
 *
 * <p>Only files this plugin writes ({@code .mp4} directly in the folder) are ever
 * counted or deleted, so pointing the save folder at a directory holding other
 * files cannot cause those to be removed.
 */
@Slf4j
final class ClipStorage
{
	private static final long BYTES_PER_GB = 1024L * 1024L * 1024L;

	private ClipStorage()
	{
	}

	/**
	 * Suffix for a clip still being written, by an encode or a download.
	 *
	 * <p>The clip listing only matches .mp4, so anything wearing this is invisible to the panel
	 * until it is complete and renamed into place. Shared rather than duplicated: if the two
	 * writers ever disagreed about it, one of them would start leaking half-written clips into
	 * the list again.
	 */
	static final String PART_SUFFIX = ".part";

	/**
	 * Longest clip name accepted, matching the server's own limit.
	 *
	 * <p>Deliberately the same number on both sides. A shorter cap here would silently rename any
	 * longer clip on the way down from the account, and the local and remote copies would then
	 * stop matching by name - which is how the panel pairs them, so one clip would start showing
	 * up as two. Filesystems cap a path component near 255 bytes, leaving room for the extension,
	 * the "(2)" a collision adds, and multi-byte characters.
	 */
	private static final int MAX_NAME = 120;

	/** Names Windows refuses to give a file, whatever the extension. */
	private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile(
		"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

	/**
	 * Reduce a user-supplied name to one that is safe as a file name everywhere.
	 *
	 * <p>The single place this is decided. There used to be three near-copies of this expression,
	 * and they had already drifted: one of them escaped a forward slash where it meant to escape a
	 * backslash, so backslashes passed straight through into file names.
	 *
	 * <p>More than the filesystem's rules are enforced. {@code & % ^ ! ` $} are perfectly legal in
	 * a file name, but revealing a clip in Explorer has to go through cmd, which reads the path
	 * first - a clip named with an ampersand was measured opening the Desktop instead of the clip.
	 * Rather than carry that hazard around, such characters never reach a name to begin with.
	 *
	 * @return the cleaned name, or empty if nothing usable survived.
	 */
	static String safeName(String requested)
	{
		if (requested == null)
		{
			return "";
		}
		String name = requested;
		name = name.replaceAll("[\\x00-\\x1F\\x7F]", "");
		// An allowlist, not a list of things to remove.
		//
		// It has to be exactly the set the server leaves untouched, because the panel pairs a
		// local clip with its uploaded copy by name. Anything this lets through that the server
		// then rewrites - parentheses were the case that bit - makes the two names disagree, and
		// the same clip shows up twice: once as a local file, once as a cloud copy.
		//
		// This subsumes the old denylists. Path separators, the characters Windows forbids, and
		// the shell metacharacters that broke revealing a clip in Explorer are all simply not on
		// the list.
		name = name.replaceAll("[^A-Za-z0-9_.()\\- ]", "_");
		name = name.replaceAll("\\s+", " ");
		// Windows silently drops trailing dots and spaces, so a name ending in them is not the
		// name that would come back from the disk.
		name = name.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
		if (name.length() > MAX_NAME)
		{
			name = name.substring(0, MAX_NAME).trim();
		}
		if (RESERVED.matcher(name).matches())
		{
			name = name + "_";
		}
		return name;
	}

	/** The configured save folder, or the default {@code .runelite/captures} when blank. */
	static File outputDir(ExchangeInsightsCaptureConfig config)
	{
		final String configured = config.outputDirectory();
		if (configured != null && !configured.trim().isEmpty())
		{
			return new File(configured.trim());
		}
		return new File(RuneLite.RUNELITE_DIR, "captures");
	}

	/**
	 * How deep clips are filed: {@code <account>/<category>/clip.mp4}.
	 *
	 * <p>Walking is bounded rather than unlimited. The save folder is user-configurable and could
	 * be pointed at a home directory, and an unbounded walk there would be both slow and a way to
	 * find - and under auto-delete, remove - files that have nothing to do with this plugin.
	 */
	private static final int MAX_DEPTH = 2;

	/** Clips anywhere under the folder, oldest first. Never null. */
	private static List<File> clips(File dir)
	{
		final List<File> list = new ArrayList<>();
		collect(dir, list, 0);
		list.sort(Comparator.comparingLong(File::lastModified));
		return list;
	}

	private static void collect(File dir, List<File> into, int depth)
	{
		final File[] found = dir.listFiles();
		if (found == null)
		{
			return;
		}
		for (File f : found)
		{
			if (f.isFile() && f.getName().toLowerCase().endsWith(".mp4"))
			{
				into.add(f);
			}
			else if (f.isDirectory() && depth < MAX_DEPTH)
			{
				collect(f, into, depth + 1);
			}
		}
	}

	/**
	 * Where a clip of this kind belongs: {@code <save folder>/<account>/<category>}.
	 *
	 * <p>Mirrors how RuneLite files screenshots, deliberately. An account with a temporary game
	 * mode gets its own directory - "Spryt-Demonic Pacts League" beside "Spryt" - so a league
	 * character's clips never mix in with the main account's.
	 *
	 * @param account the player's folder name, or null when it is not known yet.
	 */
	static File clipDir(ExchangeInsightsCaptureConfig config, String account, ClipTrigger trigger)
	{
		File dir = outputDir(config);
		if (account != null && !account.isEmpty())
		{
			dir = new File(dir, safeName(account));
			if (trigger != null)
			{
				dir = new File(dir, safeName(trigger.folder()));
			}
		}
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		return dir;
	}

	/**
	 * A file in {@code dir} named after {@code base} that does not already exist, adding
	 * " (2)", " (3)" and so on as needed.
	 *
	 * <p>Writing a clip opens its path for writing, which TRUNCATES whatever is there. Since a
	 * clip can be renamed while it is still being produced - possibly to a name already taken -
	 * resolving the collision here is what stops a new clip silently destroying an old one.
	 */
	static File uniqueFile(File dir, String base, String extension)
	{
		File candidate = new File(dir, base + extension);
		for (int n = 2; candidate.exists() && n < 1000; n++)
		{
			candidate = new File(dir, base + " (" + n + ")" + extension);
		}
		return candidate;
	}

	/** Total bytes used by clips in the configured folder. */
	static long usedBytes(ExchangeInsightsCaptureConfig config)
	{
		long total = 0;
		for (File f : clips(outputDir(config)))
		{
			total += f.length();
		}
		return total;
	}

	/**
	 * Bring the folder back under the configured limit by deleting the oldest clips,
	 * or warn instead when the mode says so. No-op when limiting is off.
	 *
	 * @param onWarn receives a human-readable message when over the limit in warn mode,
	 *               or when clips had to be removed.
	 */
	static void enforceLimit(ExchangeInsightsCaptureConfig config, Consumer<String> onWarn)
	{
		if (!config.limitLocalStorage())
		{
			return;
		}

		final long limit = Math.max(1, config.localStorageLimitGb()) * BYTES_PER_GB;
		final File dir = outputDir(config);
		final List<File> files = clips(dir);

		long used = 0;
		for (File f : files)
		{
			used += f.length();
		}
		if (used <= limit)
		{
			return;
		}

		if (config.storageLimitMode() == StorageLimitMode.WARN)
		{
			onWarn.accept(String.format(
				"Exchange Insights Capture: clip folder is %.1fGB, over your %dGB limit. Delete some clips or switch to auto delete.",
				used / (double) BYTES_PER_GB, config.localStorageLimitGb()));
			return;
		}

		// Oldest first until we are back under the limit. Never delete the most recent
		// clip - the one just saved is the one the player actually wanted.
		int removed = 0;
		for (File f : files)
		{
			if (used <= limit || files.size() - removed <= 1)
			{
				break;
			}
			final long size = f.length();
			if (f.delete())
			{
				used -= size;
				removed++;
			}
			else
			{
				log.debug("could not delete old clip {}", f);
			}
		}

		if (removed > 0)
		{
			onWarn.accept("Exchange Insights Capture: removed " + removed
				+ (removed == 1 ? " old clip" : " old clips") + " to stay under your "
				+ config.localStorageLimitGb() + "GB limit.");
		}
	}
}
