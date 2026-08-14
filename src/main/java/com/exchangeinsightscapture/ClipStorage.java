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

	/** Clips in the folder, oldest first. Never null. */
	private static List<File> clips(File dir)
	{
		final File[] found = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".mp4"));
		final List<File> list = new ArrayList<>();
		if (found != null)
		{
			for (File f : found)
			{
				list.add(f);
			}
		}
		list.sort(Comparator.comparingLong(File::lastModified));
		return list;
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
