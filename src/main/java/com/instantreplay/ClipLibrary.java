package com.instantreplay;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * The saved-clip library: what is on disk, plus the thumbnails shown beside each entry.
 *
 * <p>Thumbnails are written <em>at save time</em> from a frame we already hold in memory,
 * rather than decoded back out of the finished video later. Decoding a frame from an MP4
 * would mean a full H.264 decode per thumbnail (and would not work at all for the Motion
 * JPEG clips), so a small sidecar JPEG is both faster and format-independent.
 *
 * <p>Sidecars live in a dot-directory beside the clips so they never appear in the folder
 * the user browses, and are never counted or pruned as clips.
 */
@Slf4j
final class ClipLibrary
{
	private static final String THUMB_DIR = ".thumbs";
	/** Sized to fill a card in the side panel, which is a little under 200px of usable width. */
	static final int THUMB_WIDTH = 190;

	private ClipLibrary()
	{
	}

	/** One clip on disk, with its thumbnail if we have one. */
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
	}

	private static File thumbDir(InstantReplayConfig config)
	{
		return new File(ClipStorage.outputDir(config), THUMB_DIR);
	}

	private static File thumbFor(InstantReplayConfig config, String clipFileName)
	{
		final int dot = clipFileName.lastIndexOf('.');
		final String base = dot > 0 ? clipFileName.substring(0, dot) : clipFileName;
		return new File(thumbDir(config), base + ".jpg");
	}

	/** Saved clips, newest first. */
	static List<Entry> list(InstantReplayConfig config)
	{
		final File dir = ClipStorage.outputDir(config);
		final File[] found = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".mp4"));
		final List<Entry> out = new ArrayList<>();
		if (found != null)
		{
			for (File f : found)
			{
				out.add(new Entry(f));
			}
		}
		out.sort(Comparator.comparingLong((Entry e) -> e.modifiedAt).reversed());
		return out;
	}

	/**
	 * Write the thumbnail sidecar for a clip from one of its own buffered frames.
	 * Best-effort: a missing thumbnail costs a placeholder in the panel, nothing more.
	 */
	static void writeThumbnail(InstantReplayConfig config, File clip, byte[] jpegFrame)
	{
		if (jpegFrame == null || jpegFrame.length == 0)
		{
			return;
		}
		try
		{
			final BufferedImage full = ImageIO.read(new ByteArrayInputStream(jpegFrame));
			if (full == null || full.getWidth() <= 0)
			{
				return;
			}
			final int w = THUMB_WIDTH;
			final int h = Math.max(1, Math.round((float) full.getHeight() * w / full.getWidth()));

			final BufferedImage thumb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			final java.awt.Graphics2D g = thumb.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(full, 0, 0, w, h, null);
			g.dispose();

			final File dir = thumbDir(config);
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
			ImageIO.write(thumb, "jpg", thumbFor(config, clip.getName()));
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("thumbnail write failed for {}", clip.getName(), e);
		}
	}

	/** The clip's thumbnail, or null when there isn't one (clips saved before this existed). */
	static Image thumbnail(InstantReplayConfig config, Entry entry)
	{
		final File f = thumbFor(config, entry.name);
		if (!f.isFile())
		{
			return null;
		}
		try
		{
			return ImageIO.read(f);
		}
		catch (IOException | RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Rename a clip and its thumbnail together. The new name is sanitised and keeps the
	 * original extension, so a rename can neither escape the folder nor change the format.
	 *
	 * @return the new file, or null if the rename could not be performed.
	 */
	static File rename(InstantReplayConfig config, Entry entry, String requested)
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
		if (!entry.file.renameTo(target))
		{
			return null;
		}
		// Move the sidecar too, so the thumbnail follows its clip.
		final File oldThumb = thumbFor(config, entry.name);
		if (oldThumb.isFile())
		{
			//noinspection ResultOfMethodCallIgnored
			oldThumb.renameTo(thumbFor(config, target.getName()));
		}
		return target;
	}

	/** Delete a clip and its thumbnail. */
	static boolean delete(InstantReplayConfig config, Entry entry)
	{
		final File thumb = thumbFor(config, entry.name);
		if (thumb.isFile())
		{
			//noinspection ResultOfMethodCallIgnored
			thumb.delete();
		}
		return entry.file.delete();
	}

	/** Strip anything that could escape the folder or upset the filesystem. */
	private static String sanitise(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
	}
}
