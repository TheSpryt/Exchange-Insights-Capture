package com.exchangeinsightscapture.h264;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads back the two things the side panel wants from a clip: its preview and its length.
 *
 * <p>Both are answered by walking the box tree, which is all an MP4 is - a length and a four
 * character type, nested. Neither needs a video decoder, which matters because this plugin no
 * longer has one and a thumbnail was never worth the several thousand lines that would take.
 *
 * <p>Only the front of the file is searched. The index is written before the media data, so
 * everything of interest is within the first few kilobytes, while the clip itself runs to tens of
 * megabytes.
 */
public final class Mp4Reader
{
	/** How far in to look before giving up. The index and cover art live well inside this. */
	private static final long SEARCH_LIMIT = 4 << 20;

	private Mp4Reader()
	{
	}

	/**
	 * The embedded cover art, or null when the clip has none.
	 *
	 * <p>Clips written before thumbnails were embedded do not have one. That is a normal answer
	 * rather than a failure, and there is no recovering it - the picture would have to be decoded
	 * out of the video.
	 */
	public static byte[] cover(File clip)
	{
		try (RandomAccessFile file = new RandomAccessFile(clip, "r"))
		{
			final long[] found = find(file, 0, Math.min(file.length(), SEARCH_LIMIT),
				new String[]{"moov", "udta", "meta", "ilst", "covr", "data"}, 0);
			if (found == null)
			{
				return null;
			}
			// The data box holds a well-known type and a locale before the image itself.
			final int length = (int) (found[1] - 16);
			if (length <= 0 || length > 8 << 20)
			{
				return null;
			}
			file.seek(found[0] + 16);
			final byte[] out = new byte[length];
			file.readFully(out);
			return out;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * The clip's length in seconds, or null when it cannot be read.
	 *
	 * <p>From the movie header, which carries a timescale and a duration counted in it.
	 */
	public static Double durationSeconds(File clip)
	{
		try (RandomAccessFile file = new RandomAccessFile(clip, "r"))
		{
			final long[] found = find(file, 0, Math.min(file.length(), SEARCH_LIMIT),
				new String[]{"moov", "mvhd"}, 0);
			if (found == null)
			{
				return null;
			}
			file.seek(found[0] + 8);
			final int version = file.readUnsignedByte();
			file.skipBytes(3); // flags
			final long timescale;
			final long duration;
			if (version == 1)
			{
				file.skipBytes(16); // creation and modification, both 64 bit here
				timescale = file.readInt() & 0xFFFFFFFFL;
				duration = file.readLong();
			}
			else
			{
				file.skipBytes(8); // creation and modification
				timescale = file.readInt() & 0xFFFFFFFFL;
				duration = file.readInt() & 0xFFFFFFFFL;
			}
			if (timescale <= 0 || duration <= 0)
			{
				return null;
			}
			return (double) duration / timescale;
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Find a nested box, returning its offset and size.
	 *
	 * <p>The meta box is the awkward one: it carries a version and flags before its children,
	 * where every other container here starts straight into them.
	 */
	private static long[] find(RandomAccessFile file, long from, long to, String[] path, int depth)
		throws IOException
	{
		long at = from;
		while (at + 8 <= to)
		{
			file.seek(at);
			final long size = file.readInt() & 0xFFFFFFFFL;
			final byte[] raw = new byte[4];
			file.readFully(raw);
			final String type = new String(raw, StandardCharsets.US_ASCII);
			if (size < 8 || at + size > to)
			{
				return null;
			}

			if (type.equals(path[depth]))
			{
				if (depth == path.length - 1)
				{
					return new long[]{at, size};
				}
				final long childrenAt = at + 8 + (type.equals("meta") ? 4 : 0);
				return find(file, childrenAt, at + size, path, depth + 1);
			}
			at += size;
		}
		return null;
	}
}
