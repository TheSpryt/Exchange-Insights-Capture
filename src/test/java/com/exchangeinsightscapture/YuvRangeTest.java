package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Picture;
import org.junit.Test;

/**
 * Reads the luma JCodec actually writes, and compares it with the value the H.264 spec says a
 * full-range frame should carry.
 *
 * <p>The conversion round trip loses ~7 per channel on every colour, but a round trip cannot say
 * WHICH half is wrong - and that matters enormously, because clips are decoded by VLC and Windows
 * Media Player, not by JCodec. If the encode half is wrong the file itself is dark and every
 * player shows it; if only the decode half is wrong the file is fine and this was never a real
 * bug.
 */
public class YuvRangeTest
{
	private static final int[] PATCHES = {0x206080, 0x2E8B57, 0x483D8B, 0x808080, 0xFFFFFF, 0x000000};

	@Test
	public void whichHalfIsWrong()
	{
		final int cell = 32;
		final BufferedImage src = new BufferedImage(cell * PATCHES.length, cell, BufferedImage.TYPE_INT_RGB);
		for (int i = 0; i < PATCHES.length; i++)
		{
			for (int y = 0; y < cell; y++)
			{
				for (int x = i * cell; x < i * cell + cell; x++)
				{
					src.setRGB(x, y, PATCHES[i]);
				}
			}
		}

		final Picture yuv = Picture.create(src.getWidth(), src.getHeight(), ColorSpace.YUV420J);
		H264Writer.toYuv420j(src, yuv);
		final byte[] luma = yuv.getPlaneData(0);

		System.out.println("=== luma written vs BT.601 full-range expectation ===");
		for (int i = 0; i < PATCHES.length; i++)
		{
			final int p = PATCHES[i];
			final int r = (p >> 16) & 0xFF;
			final int g = (p >> 8) & 0xFF;
			final int b = p & 0xFF;
			final double expectFull = 0.299 * r + 0.587 * g + 0.114 * b;
			final double expectLimited = 16 + expectFull * 219 / 255;

			// JCodec keeps samples signed, offset by -128; undo that to get the real 0-255 value.
			final int got = (luma[i * cell + cell / 2] & 0xFF) - 128 < -128
				? 0 : (luma[i * cell + cell / 2] + 128) & 0xFF;

			System.out.printf("#%06X  written Y=%3d   full-range=%5.1f   limited-range=%5.1f%n",
				p, got, expectFull, expectLimited);

			// The whole point: luma must be FULL range. JCodec's own converter wrote this at about
			// 0.945 gain - white landed on 241, mid-grey on 121 - and every clip came out dark.
			org.junit.Assert.assertEquals("luma for #" + Integer.toHexString(p), expectFull, got, 1.5);
		}
	}
}
