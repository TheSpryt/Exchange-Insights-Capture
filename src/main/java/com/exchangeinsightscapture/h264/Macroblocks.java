package com.exchangeinsightscapture.h264;

/**
 * Intra macroblock coding: prediction, transform, quantisation and reconstruction.
 *
 * <p>Every macroblock is I_16x16 with DC prediction - the whole block predicted as the average of
 * the pixels above and to its left, and only the difference coded. That is the simplest mode that
 * is genuinely competitive on this material: a game canvas is mostly flat panels and text on
 * still backgrounds, where a DC prediction is already close and the residual is nearly all zero.
 *
 * <p>The reconstruction here is not a convenience. Prediction reads back previously coded pixels,
 * so the encoder has to hold exactly what the decoder will hold - not the source. Predicting from
 * the source instead is the classic drift bug: each macroblock is slightly wrong, the next
 * predicts from that, and the error accumulates across the picture.
 */
final class Macroblocks
{
	/** Where each of the sixteen 4x4 luma blocks sits, in the order the standard codes them. */
	static final int[] BLK_X = {0, 1, 0, 1, 2, 3, 2, 3, 0, 1, 0, 1, 2, 3, 2, 3};
	static final int[] BLK_Y = {0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3};

	private Macroblocks()
	{
	}

	/**
	 * The DC prediction for a 16x16 luma block.
	 *
	 * <p>Falls back through both edges to a flat 128 in the top-left corner, where nothing has
	 * been coded yet.
	 */
	static int lumaDcPredict(byte[] rec, int stride, int x, int y, boolean left, boolean top)
	{
		int sum = 0;
		if (top)
		{
			for (int i = 0; i < 16; i++)
			{
				sum += rec[(y - 1) * stride + x + i] & 0xFF;
			}
		}
		if (left)
		{
			for (int i = 0; i < 16; i++)
			{
				sum += rec[(y + i) * stride + x - 1] & 0xFF;
			}
		}
		if (left && top)
		{
			return (sum + 16) >> 5;
		}
		if (left || top)
		{
			return (sum + 8) >> 4;
		}
		return 128;
	}

	/**
	 * The DC prediction for one 4x4 chroma block.
	 *
	 * <p>Chroma is predicted per 4x4 rather than per 8x8, and which edge each one prefers is not
	 * symmetric: the blocks along the top edge favour the row above, those down the left favour
	 * the column beside, and the corners use both. Getting this wrong is invisible on flat colour
	 * and shows up as blocking exactly where chroma changes.
	 */
	static int chromaDcPredict(byte[] rec, int stride, int x, int y, int bx, int by,
		boolean left, boolean top)
	{
		int topSum = 0;
		int leftSum = 0;
		if (top)
		{
			for (int i = 0; i < 4; i++)
			{
				topSum += rec[(y - 1) * stride + x + bx * 4 + i] & 0xFF;
			}
		}
		if (left)
		{
			for (int i = 0; i < 4; i++)
			{
				leftSum += rec[(y + by * 4 + i) * stride + x - 1] & 0xFF;
			}
		}

		final boolean preferBoth = (bx == 0 && by == 0) || (bx > 0 && by > 0);
		if (preferBoth)
		{
			if (left && top)
			{
				return (topSum + leftSum + 4) >> 3;
			}
			if (top)
			{
				return (topSum + 2) >> 2;
			}
			if (left)
			{
				return (leftSum + 2) >> 2;
			}
			return 128;
		}
		if (bx > 0)
		{
			if (top)
			{
				return (topSum + 2) >> 2;
			}
			if (left)
			{
				return (leftSum + 2) >> 2;
			}
			return 128;
		}
		if (left)
		{
			return (leftSum + 2) >> 2;
		}
		if (top)
		{
			return (topSum + 2) >> 2;
		}
		return 128;
	}

	/** Reorder a 4x4 block from raster into the zig-zag scan the entropy coder expects. */
	static void toZigzag(int[] block, int[] out, int skipDc)
	{
		final int count = skipDc == 0 ? 16 : 15;
		for (int i = 0; i < count; i++)
		{
			out[i] = block[Transform.ZIGZAG[i + skipDc]];
		}
	}

	static int clamp(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}
}
