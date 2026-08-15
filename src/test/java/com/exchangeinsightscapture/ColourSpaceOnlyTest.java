package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;
import org.jcodec.scale.Yuv420jToRgb;
import org.junit.Test;

/**
 * Splits the colour pipeline at the codec boundary.
 *
 * <p>The chroma test showed 97% of the colour error survives past JPEG, but it measured through
 * the whole encode/decode, so it could not say whether the damage happens in the RGB-to-YUV
 * conversion or in H.264 itself. This does the conversion round trip alone, with no codec in the
 * middle. Whatever error shows up here is the conversion's; whatever the full pipeline has on top
 * is the codec's.
 */
public class ColourSpaceOnlyTest
{
	private static final int[] PATCHES = {
		0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
		0x804020, 0x206080, 0x408020, 0x8B4513, 0x2E8B57, 0x483D8B,
	};

	@Test
	public void conversionRoundTrip()
	{
		final int cell = 32;
		final BufferedImage src = new BufferedImage(cell * 4, cell * 3, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < PATCHES.length; i++)
		{
			for (int y = (i / 4) * cell; y < (i / 4) * cell + cell; y++)
			{
				for (int x = (i % 4) * cell; x < (i % 4) * cell + cell; x++)
				{
					src.setRGB(x, y, PATCHES[i]);
				}
			}
		}

		final Picture yuv = Picture.create(src.getWidth(), src.getHeight(), ColorSpace.YUV420J);
		H264Writer.toYuv420j(src, yuv);

		final Picture back = Picture.create(src.getWidth(), src.getHeight(), ColorSpace.RGB);
		new Yuv420jToRgb().transform(yuv, back);
		final BufferedImage out = AWTUtil.toBufferedImage(back);

		System.out.println("=== conversion-only error (no codec) ===");
		double total = 0;
		for (int i = 0; i < PATCHES.length; i++)
		{
			final int got = out.getRGB((i % 4) * cell + cell / 2, (i / 4) * cell + cell / 2) & 0xFFFFFF;
			final double e = Math.abs(((PATCHES[i] >> 16) & 0xFF) - ((got >> 16) & 0xFF))
				+ Math.abs(((PATCHES[i] >> 8) & 0xFF) - ((got >> 8) & 0xFF))
				+ Math.abs((PATCHES[i] & 0xFF) - (got & 0xFF));
			total += e;
			System.out.printf("#%06X -> #%06X  err %5.0f%n", PATCHES[i], got, e);
		}
		final double mean = total / PATCHES.length;
		System.out.printf("MEAN conversion-only %.1f%n", mean);

		// Was 12.3 with JCodec's RgbToYuv420j, which lost about seven levels on every channel.
		// Anything above a couple of units means the conversion has drifted again - including the
		// signed-byte overflow that once turned pure red into green.
		org.junit.Assert.assertTrue("conversion drifted: mean error " + mean, mean < 3.0);
	}
}
