package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.jcodec.api.awt.AWTFrameGrab;
import org.junit.Test;

/**
 * Separates where colour is actually being lost.
 *
 * <p>The earlier round-trip test used a grey ramp, which carries no colour at all - so it
 * measured luma and was blind to chroma damage by construction. Frames pass through TWO 4:2:0
 * subsamplings on their way to a clip (the JPEG buffer, then H.264), and each one throws away
 * three quarters of the colour resolution. This measures each stage separately so the blame
 * lands on the right one.
 */
public class ChromaLossTest
{
	/** Saturated patches, where chroma subsampling does its most visible damage. */
	private static final int[] PATCHES = {
		0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
		0x804020, 0x206080, 0x408020, 0x8B4513, 0x2E8B57, 0x483D8B,
	};

	private static BufferedImage patchwork()
	{
		final int cell = 32;
		final int cols = 4;
		final int rows = 3;
		final BufferedImage img = new BufferedImage(cell * cols, cell * rows, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < PATCHES.length; i++)
		{
			final int cx = (i % cols) * cell;
			final int cy = (i / cols) * cell;
			for (int y = cy; y < cy + cell; y++)
			{
				for (int x = cx; x < cx + cell; x++)
				{
					img.setRGB(x, y, PATCHES[i]);
				}
			}
		}
		return img;
	}

	private static int centreOf(BufferedImage img, int i)
	{
		final int cell = 32;
		return img.getRGB((i % 4) * cell + cell / 2, (i / 4) * cell + cell / 2) & 0xFFFFFF;
	}

	private static double error(int a, int b)
	{
		return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
			+ Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
			+ Math.abs((a & 0xFF) - (b & 0xFF));
	}

	@Test
	public void whereIsColourLost() throws Exception
	{
		final BufferedImage source = patchwork();

		// Stage 1: the JPEG buffer alone, at the quality the recorder actually uses.
		final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(source, "jpg", jpeg);
		final BufferedImage afterJpeg = ImageIO.read(new ByteArrayInputStream(jpeg.toByteArray()));

		// Stage 2: the whole pipeline, buffer plus H.264.
		final List<RecordedFrame> frames = new ArrayList<>();
		for (int i = 0; i < 8; i++)
		{
			frames.add(new RecordedFrame(i * 33L, jpeg.toByteArray(), source.getWidth(), source.getHeight()));
		}
		final File out = File.createTempFile("chroma-", ".mp4");
		out.deleteOnExit();
		ClipEncoder.encode(out, frames, 30);
		final BufferedImage afterAll = AWTFrameGrab.getFrame(out, 0);

		System.out.println("=== colour error per stage (sum of |R|+|G|+|B| deltas) ===");
		double jpegTotal = 0;
		double allTotal = 0;
		for (int i = 0; i < PATCHES.length; i++)
		{
			final int src = PATCHES[i];
			final double eJpeg = error(src, centreOf(afterJpeg, i));
			final double eAll = error(src, centreOf(afterAll, i));
			jpegTotal += eJpeg;
			allTotal += eAll;
			System.out.printf("#%06X -> #%06X   jpeg %5.0f   full %5.0f%n",
				src, centreOf(afterAll, i), eJpeg, eAll);
		}
		System.out.printf("MEAN  jpeg-only %.1f   full-pipeline %.1f%n",
			jpegTotal / PATCHES.length, allTotal / PATCHES.length);
		System.out.printf("=> JPEG buffer accounts for %.0f%% of the total colour error%n",
			allTotal == 0 ? 0 : 100 * jpegTotal / allTotal);
	}
}
