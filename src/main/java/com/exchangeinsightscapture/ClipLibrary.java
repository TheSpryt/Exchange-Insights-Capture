package com.exchangeinsightscapture;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.exchangeinsightscapture.h264.Mp4Reader;
import javax.imageio.ImageIO;

/**
 * The saved-clip library: what is on disk, and a preview frame for each entry.
 *
 * <p>Previews travel inside the clip, as cover art written when it was encoded. There is still
 * no second set of files to write, rename, delete or leave behind - the clip remains the only
 * artefact - but reading one back is now a walk through the container rather than a video frame
 * decode, which is both far cheaper and possible without a decoder at all.
 *
 * <p>Clips recorded before this carry no cover art and will show no preview. Nothing can be done
 * for them: recovering it would mean decoding H.264, which is exactly the dependency that had to
 * go.
 *
 * <p>Decoding is not free, so results are cached in memory, keyed by path AND modification
 * time so a renamed or overwritten clip can never show a stale image. The cache is bounded:
 * a decoded frame is a full-size raster, not a thumbnail.
 */
@Slf4j
final class ClipLibrary
{
	/**
	 * Width of the preview stored with a clip.
	 *
	 * <p>190 was sized for this plugin's own panel and nothing else. The same image is shown on the
	 * website, and a soft thumbnail is the most visible thing about a clip nobody has played yet.
	 * 1280 is what video sites use for the same job: sharp on a high-density display, and still a
	 * couple of hundred kilobytes against a clip of a hundred megabytes.
	 */
	static final int THUMB_WIDTH = 1280;

	/**
	 * Width the in-memory preview cache holds, which is NOT the thumbnail width.
	 *
	 * <p>The two were the same until the thumbnail grew. Caching at 1280 would put 3.6MB per entry
	 * back into the heap - forty of those is what this cache was just fixed for - and the panel
	 * draws them roughly two hundred pixels wide, so it would be paying for detail it discards
	 * immediately. The uploaded thumbnail decodes its own frame instead.
	 */
	private static final int PREVIEW_CACHE_WIDTH = 480;

	/**
	 * JPEG quality for that preview. ImageIO defaults to about 0.75, which shows on a still frame
	 * of a dark scene. A preview is tens of kilobytes against a clip of tens of megabytes, so the
	 * extra bytes are not worth economising on.
	 */
	private static final float THUMB_QUALITY = 0.9f;

	private static final int CACHE_SIZE = 40;

	private static final Map<String, Image> CACHE = Collections.synchronizedMap(
		new LinkedHashMap<String, Image>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Image> eldest)
			{
				return size() > CACHE_SIZE;
			}
		});

	private ClipLibrary()
	{
	}

	/** One clip on disk. */
	static final class Entry
	{
		final File file;
		final String name;
		final long bytes;
		final long modifiedAt;

		Entry(File file)
		{
			this.file = file;
			this.name = file.getName();
			this.bytes = file.length();
			this.modifiedAt = file.lastModified();
		}

		/** Display name without the .mp4 suffix. */
		String displayName()
		{
			final int dot = name.lastIndexOf('.');
			return dot > 0 ? name.substring(0, dot) : name;
		}

		String cacheKey()
		{
			return file.getAbsolutePath() + "@" + modifiedAt;
		}
	}

	/**
	 * Saved clips, newest first, from anywhere under the save folder.
	 *
	 * <p>Clips are filed by account and category, so this walks rather than lists. The panel shows
	 * one flat list regardless: the folders exist for the player browsing them in a file manager,
	 * not as a hierarchy to navigate in a side panel two hundred pixels wide.
	 */
	static List<Entry> list(ExchangeInsightsCaptureConfig config)
	{
		final List<Entry> out = new ArrayList<>();
		collect(ClipStorage.outputDir(config), out, 0);
		out.sort(Comparator.comparingLong((Entry e) -> e.modifiedAt).reversed());
		return out;
	}

	private static void collect(File dir, List<Entry> into, int depth)
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
				into.add(new Entry(f));
			}
			else if (f.isDirectory() && depth < 2)
			{
				collect(f, into, depth + 1);
			}
		}
	}

	/**
	 * Clip lengths in seconds, keyed the same way as previews.
	 *
	 * <p>Read from the container rather than derived from file size or a frame count: capture
	 * rate varies with the client now, so nothing outside the file knows how long it runs.
	 */
	private static final Map<String, Double> DURATIONS = Collections.synchronizedMap(
		new LinkedHashMap<String, Double>(16, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Double> eldest)
			{
				return size() > CACHE_SIZE;
			}
		});

	/** Length in seconds, or null until it has been read. Never blocks. */
	static Double cachedDuration(Entry entry)
	{
		return DURATIONS.get(entry.cacheKey());
	}

	/** Read a clip's length from its container. Opens the file; call off the EDT. */
	static Double readDuration(Entry entry)
	{
		final Double known = DURATIONS.get(entry.cacheKey());
		if (known != null)
		{
			return known;
		}
		final Double seconds = Mp4Reader.durationSeconds(entry.file);
		if (seconds != null && seconds > 0)
		{
			DURATIONS.put(entry.cacheKey(), seconds);
			return seconds;
		}
		return null;
	}

	/** No preview this plugin writes is anywhere near this; a header claiming more is damaged. */
	private static final int MAX_COVER_PIXELS = 4096;

	/**
	 * Decode cover art, refusing anything with implausible dimensions.
	 *
	 * <p>The size is checked from the header before the image is decoded, not after, because the
	 * point is to avoid allocating the raster at all. A file in the clips folder is not
	 * necessarily one this plugin wrote and is not necessarily intact - a truncated write or a
	 * bad disk can leave a header claiming enormous dimensions, and decoding that would take the
	 * client down rather than lose a thumbnail.
	 */
	private static BufferedImage decodeCover(byte[] cover)
	{
		if (cover == null || cover.length == 0)
		{
			return null;
		}
		try (javax.imageio.stream.ImageInputStream in =
			ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(cover)))
		{
			final java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(in);
			if (!readers.hasNext())
			{
				return null;
			}
			final javax.imageio.ImageReader reader = readers.next();
			try
			{
				reader.setInput(in);
				if (reader.getWidth(0) > MAX_COVER_PIXELS || reader.getHeight(0) > MAX_COVER_PIXELS)
				{
					log.debug("ignoring a cover image claiming {}x{}",
						reader.getWidth(0), reader.getHeight(0));
					return null;
				}
				return reader.read(0);
			}
			finally
			{
				reader.dispose();
			}
		}
		catch (Exception | AssertionError e)
		{
			log.debug("could not decode cover art", e);
			return null;
		}
	}

	/** A already-decoded preview, or null if one has not been decoded yet. Never blocks. */
	static Image cachedPreview(Entry entry)
	{
		return CACHE.get(entry.cacheKey());
	}

	/**
	 * This clip's preview, read from the cover art the encoder wrote into it.
	 *
	 * <p>Cheap now - a few nested boxes at the front of the file and a small JPEG - but still off
	 * the EDT, because it is disk work and there may be a page of clips wanting one at once.
	 * Returns null for a clip with no cover art, which every clip recorded before this has, and
	 * the result is not cached in that case so a transient read failure can be retried.
	 */
	static Image decodePreview(Entry entry)
	{
		final Image cached = CACHE.get(entry.cacheKey());
		if (cached != null)
		{
			return cached;
		}
		try
		{
			final BufferedImage frame = decodeCover(Mp4Reader.cover(entry.file));
			if (frame != null)
			{
				// Cached at panel size, not at capture size. A full frame is a live raster - about
				// 3.8MB at 1310x720 and over 8MB at 1080p - so forty of them was a hundred and fifty
				// megabytes of heap held for images drawn two hundred pixels wide.
				final int w = Math.min(PREVIEW_CACHE_WIDTH, frame.getWidth());
				final int h = Math.max(1, Math.round((float) frame.getHeight() * w / frame.getWidth()));
				final BufferedImage preview = w == frame.getWidth()
					? frame
					: downscale(frame, frame.getWidth(), frame.getHeight(), w, h);
				CACHE.put(entry.cacheKey(), preview);
				return preview;
			}
		}
		catch (Exception | AssertionError e)
		{
			// A missing preview is cosmetic, so it must never propagate.
			log.debug("could not read a preview for {}", entry.name, e);
		}
		return null;
	}

	/**
	 * This clip's preview as a JPEG, ready to upload.
	 *
	 * <p>Deliberately the SAME code path the panel displays from - the clip's own cover art,
	 * scaled to THUMB_WIDTH - so the image stored on the account is what this client would have
	 * drawn. There is no second implementation to drift: the server never generates a thumbnail,
	 * it only keeps the one a client made.
	 */
	static byte[] previewJpeg(Entry entry)
	{
		try
		{
			// Read fresh rather than taken from the cache. The cache holds a panel-sized copy,
			// and scaling that up to thumbnail size would produce something blurrier than the
			// image it came from. This runs once per clip, on upload, so a second read is cheap.
			final Image full = decodeCover(Mp4Reader.cover(entry.file));
			if (full == null)
			{
				return null;
			}
			final int srcW = full.getWidth(null);
			final int srcH = full.getHeight(null);
			if (srcW <= 0 || srcH <= 0)
			{
				return null;
			}
			// Never enlarge. A client running in a small window produces frames narrower than the
			// target, and stretching one up spends bytes making it blurrier than the source.
			final int w = Math.min(THUMB_WIDTH, srcW);
			final int h = Math.max(1, Math.round((float) srcH * w / srcW));

			final BufferedImage scaled = downscale(full, srcW, srcH, w, h);
			return encodeJpeg(scaled);
		}
		catch (Exception | AssertionError e)
		{
			log.debug("could not build a preview jpeg for {}", entry.name, e);
			return null;
		}
	}

	/**
	 * Shrink an image in halving steps rather than one jump.
	 *
	 * <p>A single large reduction samples too few source pixels and throws away detail that was
	 * there - thin text and UI edges come out ragged, which is most of what a game frame contains.
	 * Halving repeatedly averages the pixels in between, so each step has something to work with.
	 */
	private static BufferedImage downscale(Image full, int srcW, int srcH, int targetW, int targetH)
	{
		BufferedImage current = new BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = current.createGraphics();
		g.drawImage(full, 0, 0, null);
		g.dispose();

		int w = srcW;
		int h = srcH;
		while (w / 2 > targetW)
		{
			w /= 2;
			h = Math.max(1, h / 2);
			final BufferedImage step = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			g = step.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(current, 0, 0, w, h, null);
			g.dispose();
			current = step;
		}

		final BufferedImage out = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
		g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
			java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
			java.awt.RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(current, 0, 0, targetW, targetH, null);
		g.dispose();
		return out;
	}

	/** Write a JPEG at a stated quality, rather than whatever ImageIO picks by default. */
	private static byte[] encodeJpeg(BufferedImage image) throws java.io.IOException
	{
		final javax.imageio.ImageWriter writer =
			javax.imageio.ImageIO.getImageWritersByFormatName("jpg").next();
		final javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
		param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
		param.setCompressionQuality(THUMB_QUALITY);

		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		try (javax.imageio.stream.ImageOutputStream ios =
			javax.imageio.ImageIO.createImageOutputStream(out))
		{
			writer.setOutput(ios);
			writer.write(null, new javax.imageio.IIOImage(image, null, null), param);
		}
		finally
		{
			// Disposed on the thread that created it; an ImageWriter is not shared between threads.
			writer.dispose();
		}
		return out.toByteArray();
	}

	/**
	 * Rename a clip. The new name is sanitised and keeps the original extension, so a rename
	 * can neither escape the folder nor change the format.
	 *
	 * @return the new file, or null if the rename could not be performed.
	 */
	static File rename(ExchangeInsightsCaptureConfig config, Entry entry, String requested)
	{
		final String clean = sanitise(requested);
		if (clean.isEmpty())
		{
			return null;
		}
		final int dot = entry.name.lastIndexOf('.');
		final String ext = dot > 0 ? entry.name.substring(dot) : ".mp4";
		final File target = new File(entry.file.getParentFile(), clean + ext);
		if (target.equals(entry.file))
		{
			return entry.file;
		}
		if (target.exists())
		{
			return null;
		}
		return entry.file.renameTo(target) ? target : null;
	}

	/** Delete a clip. */
	static boolean delete(ExchangeInsightsCaptureConfig config, Entry entry)
	{
		CACHE.remove(entry.cacheKey());
		return entry.file.delete();
	}

	/** Strip anything that could escape the folder or upset the filesystem. */
	private static String sanitise(String name)
	{
		return ClipStorage.safeName(name);
	}
}
