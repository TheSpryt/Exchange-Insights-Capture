package com.exchangeinsightscapture.h264;

/**
 * CAVLC, the entropy coder baseline H.264 uses.
 *
 * <p>It codes a 4x4 block of quantised coefficients backwards, from the highest frequency down,
 * exploiting three things that hold for nearly every real block: most coefficients are zero, the
 * last non-zero ones are almost always 1 or -1, and the number of non-zero coefficients is close
 * to the number in the neighbouring blocks. That last one is why the tables are chosen by
 * neighbour context rather than fixed - a block beside a busy one is coded with a table that
 * expects to be busy.
 *
 * <p>The tables are transcribed from the standard as bit strings. Packed into integers they are
 * unreadable, and a single wrong digit produces a stream that decodes into plausible-looking
 * rubbish rather than failing, so they are kept in the form they can be checked in.
 */
final class Cavlc
{
	private Cavlc()
	{
	}

	/** A code and its length, parsed once from the readable form. */
	private static int[] parse(String bits)
	{
		return new int[]{Integer.parseInt(bits, 2), bits.length()};
	}

	private static int[][] parseAll(String... rows)
	{
		final int[][] out = new int[rows.length][];
		for (int i = 0; i < rows.length; i++)
		{
			out[i] = rows[i].isEmpty() ? null : parse(rows[i]);
		}
		return out;
	}

	// coeff_token. Laid out exactly as the standard tabulates it: one row per context band,
	// indexed by 4 * totalCoeff + trailingOnes, with a length of zero marking a combination that
	// cannot occur (more trailing ones than coefficients). Held as lengths and codes rather than
	// as bit strings because that is the form it can be checked against the standard in without a
	// conversion step in between - and a conversion step is where the first attempt went wrong.

	private static final int[][] COEFF_TOKEN_LEN = {
		{1, 0, 0, 0, 6, 2, 0, 0, 8, 6, 3, 0, 9, 8, 7, 5, 10, 9, 8, 6, 11, 10, 9, 7, 13, 11, 10, 8,
			13, 13, 11, 9, 13, 13, 13, 10, 14, 14, 13, 11, 14, 14, 14, 13, 15, 15, 14, 14, 15, 15,
			15, 14, 16, 15, 15, 15, 16, 16, 16, 15, 16, 16, 16, 16, 16, 16, 16, 16},
		{2, 0, 0, 0, 6, 2, 0, 0, 6, 5, 3, 0, 7, 6, 6, 4, 8, 6, 6, 4, 8, 7, 7, 5, 9, 8, 8, 6,
			11, 9, 9, 6, 11, 11, 11, 7, 12, 11, 11, 9, 12, 12, 12, 11, 12, 12, 12, 11, 13, 13,
			13, 12, 13, 13, 13, 13, 13, 14, 13, 13, 14, 14, 14, 13, 14, 14, 14, 14},
		{4, 0, 0, 0, 6, 4, 0, 0, 6, 5, 4, 0, 6, 5, 5, 4, 7, 5, 5, 4, 7, 5, 5, 4, 7, 6, 6, 4,
			7, 6, 6, 4, 8, 7, 7, 5, 8, 8, 7, 6, 9, 8, 8, 7, 9, 9, 8, 8, 9, 9, 9, 8, 10, 9, 9, 9,
			10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10},
		{6, 0, 0, 0, 6, 6, 0, 0, 6, 6, 6, 0, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
			6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
			6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6},
	};

	private static final int[][] COEFF_TOKEN_BITS = {
		{1, 0, 0, 0, 5, 1, 0, 0, 7, 4, 1, 0, 7, 6, 5, 3, 7, 6, 5, 3, 7, 6, 5, 4, 15, 6, 5, 4,
			11, 14, 5, 4, 8, 10, 13, 4, 15, 14, 9, 4, 11, 10, 13, 12, 15, 14, 9, 12, 11, 10, 13,
			8, 15, 1, 9, 12, 11, 14, 13, 8, 7, 10, 9, 12, 4, 6, 5, 8},
		{3, 0, 0, 0, 11, 2, 0, 0, 7, 7, 3, 0, 7, 10, 9, 5, 7, 6, 5, 4, 4, 6, 5, 6, 7, 6, 5, 8,
			15, 6, 5, 4, 11, 14, 13, 4, 15, 10, 9, 4, 11, 14, 13, 12, 8, 10, 9, 8, 15, 14, 13,
			12, 11, 10, 9, 12, 7, 11, 6, 8, 9, 8, 10, 1, 7, 6, 5, 4},
		{15, 0, 0, 0, 15, 14, 0, 0, 11, 15, 13, 0, 8, 12, 14, 12, 15, 10, 11, 11, 11, 8, 9, 10,
			9, 14, 13, 9, 8, 10, 9, 8, 15, 14, 13, 13, 11, 14, 10, 12, 15, 10, 13, 12, 11, 14, 9,
			12, 8, 10, 13, 8, 13, 7, 9, 12, 9, 12, 11, 10, 5, 8, 7, 6, 1, 4, 3, 2},
		{3, 0, 0, 0, 0, 1, 0, 0, 4, 5, 6, 0, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
			21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41,
			42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63},
	};

	/** Chroma DC carries only four coefficients, so it gets a table of its own. */
	private static final int[][][] COEFF_TOKEN_CHROMA = {
		parseAll("01", "000111", "000100", "000011", "000010"),
		parseAll("", "1", "000110", "0000011", "00000011"),
		parseAll("", "", "001", "0000010", "00000010"),
		parseAll("", "", "", "000101", "0000000"),
	};

	// total_zeros, indexed [totalCoeff - 1][totalZeros].

	private static final int[][][] TOTAL_ZEROS = {
		parseAll("1", "011", "010", "0011", "0010", "00011", "00010", "000011", "000010",
			"0000011", "0000010", "00000011", "00000010", "000000011", "000000010", "000000001"),
		parseAll("111", "110", "101", "100", "011", "0101", "0100", "0011", "0010", "00011",
			"00010", "000011", "000010", "000001", "000000"),
		parseAll("0101", "111", "110", "101", "0100", "0011", "100", "011", "0010", "00011",
			"00010", "000001", "00001", "000000"),
		parseAll("00011", "111", "0101", "0100", "110", "101", "100", "0011", "011", "0010",
			"00010", "00001", "00000"),
		parseAll("0101", "0100", "0011", "111", "110", "101", "100", "011", "0010", "00001",
			"0001", "00000"),
		parseAll("000001", "00001", "111", "110", "101", "100", "011", "010", "0001", "001",
			"000000"),
		parseAll("000001", "00001", "101", "100", "011", "11", "010", "0001", "001", "000000"),
		parseAll("000001", "0001", "00001", "011", "11", "10", "010", "001", "000000"),
		parseAll("000001", "000000", "0001", "11", "10", "001", "01", "00001"),
		parseAll("00001", "00000", "001", "11", "10", "01", "0001"),
		parseAll("0000", "0001", "001", "010", "1", "011"),
		parseAll("0000", "0001", "01", "1", "001"),
		parseAll("000", "001", "1", "01"),
		parseAll("00", "01", "1"),
		parseAll("0", "1"),
	};

	private static final int[][][] TOTAL_ZEROS_CHROMA = {
		parseAll("1", "01", "001", "000"),
		parseAll("1", "01", "00"),
		parseAll("1", "0"),
	};

	/** run_before, indexed [zerosLeft - 1][runBefore]; the last row covers zerosLeft above 6. */
	private static final int[][][] RUN_BEFORE = {
		parseAll("1", "0"),
		parseAll("1", "01", "00"),
		parseAll("11", "10", "01", "00"),
		parseAll("11", "10", "01", "001", "000"),
		parseAll("11", "10", "011", "010", "001", "000"),
		parseAll("11", "000", "001", "011", "010", "101", "100"),
		parseAll("111", "110", "101", "100", "011", "010", "001", "0001", "00001", "000001",
			"0000001", "00000001", "000000001", "0000000001", "00000000001"),
	};

	/**
	 * Write one block of coefficients, which must already be in zig-zag order.
	 *
	 * @param count how many coefficients the block carries: 16 normally, 15 when the DC is coded
	 *              separately, and 4 for chroma DC.
	 * @param nC    the neighbour context, or -1 for chroma DC.
	 * @return the count of non-zero coefficients, which becomes context for later blocks.
	 */
	static int block(Bits out, int[] zigzag, int count, int nC)
	{
		int totalCoeff = 0;
		int lastNonZero = -1;
		for (int i = 0; i < count; i++)
		{
			if (zigzag[i] != 0)
			{
				totalCoeff++;
				lastNonZero = i;
			}
		}

		// Trailing ones: the run of 1 and -1 at the high-frequency end, at most three of them.
		int trailingOnes = 0;
		for (int i = lastNonZero; i >= 0 && trailingOnes < 3; i--)
		{
			if (zigzag[i] == 0)
			{
				continue;
			}
			if (Math.abs(zigzag[i]) != 1)
			{
				break;
			}
			trailingOnes++;
		}

		writeCoeffToken(out, trailingOnes, totalCoeff, nC);
		if (totalCoeff == 0)
		{
			return 0;
		}

		// Signs of the trailing ones, then the remaining levels, high frequency first throughout.
		int written = 0;
		int i = lastNonZero;
		for (; i >= 0 && written < trailingOnes; i--)
		{
			if (zigzag[i] != 0)
			{
				out.u1(zigzag[i] < 0 ? 1 : 0);
				written++;
			}
		}

		// suffixLength adapts as levels are written: once a large one appears, the rest are coded
		// expecting to be large too, which is what keeps a busy block affordable.
		int suffixLength = totalCoeff > 10 && trailingOnes < 3 ? 1 : 0;
		boolean first = true;
		for (; i >= 0; i--)
		{
			if (zigzag[i] == 0)
			{
				continue;
			}
			final int level = zigzag[i];
			// Levels are coded as an index rather than a value: 1, -1, 2, -2 become 0, 1, 2, 3.
			int levelCode = level > 0 ? 2 * level - 2 : -2 * level - 1;
			// When there were fewer than three trailing ones, this first level cannot itself be
			// 1 or -1 - it would have been counted as a trailing one - so the whole range shifts
			// down by two rather than reserving codes for values that cannot occur.
			if (first && trailingOnes < 3)
			{
				levelCode -= 2;
			}
			first = false;
			writeLevel(out, levelCode, suffixLength);
			if (suffixLength == 0)
			{
				suffixLength = 1;
			}
			if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6)
			{
				suffixLength++;
			}
		}

		final int totalZeros = lastNonZero + 1 - totalCoeff;
		if (totalCoeff < count)
		{
			writeTotalZeros(out, totalZeros, totalCoeff, nC == -1);
		}

		int zerosLeft = totalZeros;
		int seen = 0;
		for (int k = lastNonZero; k >= 0 && zerosLeft > 0; k--)
		{
			if (zigzag[k] == 0)
			{
				continue;
			}
			if (++seen == totalCoeff)
			{
				break; // the final coefficient's run is whatever is left over
			}
			int run = 0;
			while (k - 1 - run >= 0 && zigzag[k - 1 - run] == 0)
			{
				run++;
			}
			writeRunBefore(out, run, zerosLeft);
			zerosLeft -= run;
			k -= run;
		}

		return totalCoeff;
	}

	private static void writeCoeffToken(Bits out, int trailingOnes, int totalCoeff, int nC)
	{
		if (nC == -1)
		{
			emit(out, COEFF_TOKEN_CHROMA[trailingOnes][totalCoeff]);
			return;
		}
		final int table = nC < 2 ? 0 : nC < 4 ? 1 : nC < 8 ? 2 : 3;
		final int at = 4 * totalCoeff + trailingOnes;
		final int length = COEFF_TOKEN_LEN[table][at];
		if (length == 0)
		{
			// More trailing ones than coefficients: not a state the coder can reach, and writing
			// zero bits here would corrupt everything after it, so fail loudly instead.
			throw new IllegalStateException("coeff_token " + trailingOnes + "/" + totalCoeff);
		}
		out.u(COEFF_TOKEN_BITS[table][at], length);
	}

	/**
	 * A level, as a unary prefix and an optional suffix.
	 *
	 * <p>Small levels are pure unary, which is why the coder works so hard to make them small.
	 * Past that it escapes into a fixed suffix, and past THAT the suffix widens with the prefix -
	 * each extra prefix bit doubling the range it can reach. Without that last step a very low
	 * quantiser produces coefficients too large to code at all.
	 */
	private static void writeLevel(Bits out, int code, int suffixLength)
	{
		if (suffixLength == 0)
		{
			if (code < 14)
			{
				out.u(1, code + 1);
				return;
			}
			if (code < 30)
			{
				out.u(1, 15);
				out.u(code - 14, 4);
				return;
			}
		}
		else
		{
			final int prefix = code >> suffixLength;
			if (prefix < 15)
			{
				out.u(1, prefix + 1);
				out.u(code & ((1 << suffixLength) - 1), suffixLength);
				return;
			}
		}

		// Escape. The prefix grows until its suffix is wide enough to hold what is left.
		final int base = (15 << suffixLength) + (suffixLength == 0 ? 15 : 0);
		int prefix = 15;
		while (prefix < 28 && code > base + 2 * (1 << (prefix - 3)) - 4097)
		{
			prefix++;
		}
		final int first = base + (1 << (prefix - 3)) - 4096;
		final int suffix = code - first;
		if (suffix < 0 || suffix >= 1 << (prefix - 3))
		{
			throw new IllegalStateException("level " + code + " will not code");
		}
		out.u(1, prefix + 1);
		out.u(suffix, prefix - 3);
	}

	private static void writeTotalZeros(Bits out, int totalZeros, int totalCoeff, boolean chroma)
	{
		emit(out, chroma
			? TOTAL_ZEROS_CHROMA[totalCoeff - 1][totalZeros]
			: TOTAL_ZEROS[totalCoeff - 1][totalZeros]);
	}

	private static void writeRunBefore(Bits out, int run, int zerosLeft)
	{
		emit(out, RUN_BEFORE[Math.min(zerosLeft, 7) - 1][run]);
	}

	private static void emit(Bits out, int[] code)
	{
		out.u(code[0], code[1]);
	}
}
