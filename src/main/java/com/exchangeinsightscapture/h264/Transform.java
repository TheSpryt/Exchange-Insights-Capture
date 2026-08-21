package com.exchangeinsightscapture.h264;

/**
 * The 4x4 integer transform and quantiser H.264 is built on.
 *
 * <p>Not a DCT, though it plays the same role. The coefficients are small integers chosen so the
 * forward and inverse transforms are exact in 16-bit arithmetic, which is why every decoder
 * reconstructs identically - no floating point, so no drift between our reconstruction and the
 * decoder's. That matters here because intra prediction reads back reconstructed pixels: if our
 * idea of them differed from the decoder's by even one level, the error would compound across the
 * picture.
 */
final class Transform
{
	private Transform()
	{
	}

	/** Scaling factors, by qp%6 and coefficient class. */
	private static final int[][] MF = {
		{13107, 5243, 8066},
		{11916, 4660, 7490},
		{10082, 4194, 6554},
		{9362, 3647, 5825},
		{8192, 3355, 5243},
		{7282, 2893, 4559},
	};

	/** The matching dequantisation factors. */
	private static final int[][] V = {
		{10, 16, 13},
		{11, 18, 14},
		{13, 20, 16},
		{14, 23, 18},
		{16, 25, 20},
		{18, 29, 23},
	};

	/**
	 * Which of the three scaling classes a position falls in.
	 *
	 * <p>The transform's basis functions do not all have the same gain, so the quantiser has to
	 * compensate per position: corners, centres and the rest are scaled differently.
	 */
	private static final int[] CLASS = {
		0, 2, 0, 2,
		2, 1, 2, 1,
		0, 2, 0, 2,
		2, 1, 2, 1,
	};

	/** Forward core transform, in place, on a 4x4 block held row-major. */
	static void forward(int[] b)
	{
		for (int i = 0; i < 4; i++)
		{
			final int o = i * 4;
			final int a0 = b[o] + b[o + 3];
			final int a1 = b[o + 1] + b[o + 2];
			final int a2 = b[o + 1] - b[o + 2];
			final int a3 = b[o] - b[o + 3];
			b[o] = a0 + a1;
			b[o + 1] = 2 * a3 + a2;
			b[o + 2] = a0 - a1;
			b[o + 3] = a3 - 2 * a2;
		}
		for (int i = 0; i < 4; i++)
		{
			final int a0 = b[i] + b[i + 12];
			final int a1 = b[i + 4] + b[i + 8];
			final int a2 = b[i + 4] - b[i + 8];
			final int a3 = b[i] - b[i + 12];
			b[i] = a0 + a1;
			b[i + 4] = 2 * a3 + a2;
			b[i + 8] = a0 - a1;
			b[i + 12] = a3 - 2 * a2;
		}
	}

	/** Inverse core transform, in place, including the final rounding shift. */
	static void inverse(int[] b)
	{
		for (int i = 0; i < 4; i++)
		{
			final int o = i * 4;
			final int a0 = b[o] + b[o + 2];
			final int a1 = b[o] - b[o + 2];
			final int a2 = (b[o + 1] >> 1) - b[o + 3];
			final int a3 = b[o + 1] + (b[o + 3] >> 1);
			b[o] = a0 + a3;
			b[o + 1] = a1 + a2;
			b[o + 2] = a1 - a2;
			b[o + 3] = a0 - a3;
		}
		for (int i = 0; i < 4; i++)
		{
			final int a0 = b[i] + b[i + 8];
			final int a1 = b[i] - b[i + 8];
			final int a2 = (b[i + 4] >> 1) - b[i + 12];
			final int a3 = b[i + 4] + (b[i + 12] >> 1);
			b[i] = (a0 + a3 + 32) >> 6;
			b[i + 4] = (a1 + a2 + 32) >> 6;
			b[i + 8] = (a1 - a2 + 32) >> 6;
			b[i + 12] = (a0 - a3 + 32) >> 6;
		}
	}

	/** The 4x4 Hadamard applied to the sixteen luma DC coefficients of an I_16x16 macroblock. */
	static void hadamard4(int[] b)
	{
		for (int i = 0; i < 4; i++)
		{
			final int o = i * 4;
			final int a0 = b[o] + b[o + 3];
			final int a1 = b[o + 1] + b[o + 2];
			final int a2 = b[o + 1] - b[o + 2];
			final int a3 = b[o] - b[o + 3];
			b[o] = a0 + a1;
			b[o + 1] = a3 + a2;
			b[o + 2] = a0 - a1;
			b[o + 3] = a3 - a2;
		}
		for (int i = 0; i < 4; i++)
		{
			final int a0 = b[i] + b[i + 12];
			final int a1 = b[i + 4] + b[i + 8];
			final int a2 = b[i + 4] - b[i + 8];
			final int a3 = b[i] - b[i + 12];
			b[i] = a0 + a1;
			b[i + 4] = a3 + a2;
			b[i + 8] = a0 - a1;
			b[i + 12] = a3 - a2;
		}
	}

	/** The 2x2 Hadamard for the four chroma DC coefficients, which is its own inverse. */
	static void hadamard2(int[] b)
	{
		final int a = b[0], c = b[1], d = b[2], e = b[3];
		b[0] = a + c + d + e;
		b[1] = a - c + d - e;
		b[2] = a + c - d - e;
		b[3] = a - c - d + e;
	}

	/**
	 * Quantise one coefficient.
	 *
	 * <p>The rounding offset is deliberately a third rather than a half. Biasing toward zero
	 * costs a little accuracy per coefficient and saves a lot of bits, because a coefficient that
	 * rounds to zero disappears from the bitstream entirely instead of costing a level and a run.
	 */
	static int quant(int coeff, int pos, int qp)
	{
		final int qbits = 15 + qp / 6;
		final int mf = MF[qp % 6][CLASS[pos]];
		final int f = (1 << qbits) / 3;
		final int level = (Math.abs(coeff) * mf + f) >> qbits;
		return coeff < 0 ? -level : level;
	}

	/**
	 * Undo the quantiser, exactly as a decoder does.
	 *
	 * <p>The scale is the tabulated factor times sixteen. For these coefficients that sixteen
	 * cancels against the shift below, but it does not cancel for the DC paths, which is worth
	 * stating plainly because assuming it did is what made the first attempt wrong.
	 */
	static int dequant(int level, int pos, int qp)
	{
		final int scale = V[qp % 6][CLASS[pos]] * 16;
		return qp >= 24
			? (level * scale) << (qp / 6 - 4)
			: (level * scale + (1 << (3 - qp / 6))) >> (4 - qp / 6);
	}

	/**
	 * Quantise a luma DC coefficient.
	 *
	 * <p>One bit further than the chroma version, because the 4x4 Hadamard applied to the sixteen
	 * luma DCs has four times the gain of the 2x2 applied to the four chroma ones, and only half
	 * of that is taken back on the way out. Without the extra shift a flat residual comes back
	 * twice as large as it went in - which is invisible on flat colour, so it survives the obvious
	 * tests and shows up as luma that will not improve however low the quantiser goes.
	 */
	static int quantLumaDc(int coeff, int qp)
	{
		final int qbits = 17 + qp / 6;
		final int mf = MF[qp % 6][0];
		final int f = (1 << qbits) / 3;
		final int level = (Math.abs(coeff) * mf + f) >> qbits;
		return coeff < 0 ? -level : level;
	}

	/** Quantise a chroma DC coefficient, which uses the position-0 class for its whole block. */
	static int quantDc(int coeff, int qp)
	{
		final int qbits = 16 + qp / 6;
		final int mf = MF[qp % 6][0];
		final int f = (1 << qbits) / 3;
		final int level = (Math.abs(coeff) * mf + f) >> qbits;
		return coeff < 0 ? -level : level;
	}

	static int dequantLumaDc(int level, int qp)
	{
		final int scale = V[qp % 6][0] * 16;
		return qp >= 36
			? (level * scale) << (qp / 6 - 6)
			: (level * scale + (1 << (5 - qp / 6))) >> (6 - qp / 6);
	}

	static int dequantChromaDc(int level, int qp)
	{
		return ((level * V[qp % 6][0] * 16) << (qp / 6)) >> 5;
	}

	/** Zig-zag order: low frequencies first, so the trailing zeroes cluster at the end. */
	static final int[] ZIGZAG = {
		0, 1, 4, 8,
		5, 2, 3, 6,
		9, 12, 13, 10,
		7, 11, 14, 15,
	};

	/** Chroma runs at a lower quantiser than luma at high QP, because the eye notices sooner. */
	private static final int[] CHROMA_QP = {
		0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
		24, 25, 26, 27, 28, 29, 29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38,
		38, 38, 39, 39, 39, 39,
	};

	static int chromaQp(int qp)
	{
		return CHROMA_QP[Math.max(0, Math.min(51, qp))];
	}
}
