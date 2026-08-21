package com.exchangeinsightscapture.h264;

import java.util.Arrays;

/**
 * A bit-level writer for H.264 syntax.
 *
 * <p>H.264 is not byte-aligned. Nearly every field is either a fixed run of bits or an
 * Exp-Golomb code whose length depends on its value, so the whole bitstream has to be built a
 * bit at a time and only padded out to a byte at the end of each NAL unit.
 */
public final class Bits
{
	private byte[] buf = new byte[1 << 16];
	private int at;
	/** Bits held in {@link #partial} but not yet written out; always 0..7. */
	private int pending;
	private int partial;

	/** Append the low {@code count} bits of {@code value}, most significant first. */
	public void u(int value, int count)
	{
		for (int i = count - 1; i >= 0; i--)
		{
			partial = (partial << 1) | ((value >>> i) & 1);
			if (++pending == 8)
			{
				push((byte) partial);
				pending = 0;
				partial = 0;
			}
		}
	}

	public void u1(int value)
	{
		u(value, 1);
	}

	/**
	 * Unsigned Exp-Golomb, the format's variable-length integer.
	 *
	 * <p>{@code value + 1} written in binary, preceded by one fewer zero than it has bits - so
	 * small numbers cost few bits and the decoder can find the end by counting leading zeroes.
	 */
	public void ue(int value)
	{
		final int shifted = value + 1;
		int bits = 32 - Integer.numberOfLeadingZeros(shifted);
		u(0, bits - 1);
		u(shifted, bits);
	}

	/** Signed Exp-Golomb: zig-zagged onto the unsigned codes, so 0, 1, -1, 2, -2 map to 0..4. */
	public void se(int value)
	{
		ue(value <= 0 ? -2 * value : 2 * value - 1);
	}

	/**
	 * Close the NAL: a 1 bit, then zeroes to the next byte boundary.
	 *
	 * <p>Without the stop bit a decoder cannot tell padding from data, because trailing zeroes
	 * are indistinguishable from more syntax.
	 */
	public void trailing()
	{
		u1(1);
		while (pending != 0)
		{
			u1(0);
		}
	}

	public int size()
	{
		return at;
	}

	/**
	 * The working buffer itself, valid up to {@link #size}.
	 *
	 * <p>Handed out rather than copied because the only caller immediately copies it again while
	 * inserting escape bytes, and a frame is a couple of hundred kilobytes.
	 */
	public byte[] raw()
	{
		return buf;
	}

	private void push(byte b)
	{
		if (at == buf.length)
		{
			buf = Arrays.copyOf(buf, buf.length * 2);
		}
		buf[at++] = b;
	}

	/**
	 * Insert emulation prevention bytes.
	 *
	 * <p>A NAL's payload may not contain 00 00 00, 00 00 01, 00 00 02 or 00 00 03, because a
	 * reader scanning for the 00 00 01 start code between NALs would find one inside a frame and
	 * cut it in half. Any such run gets an 03 inserted, which the decoder strips back out.
	 */
	public static byte[] escape(byte[] rbsp, int length)
	{
		final byte[] out = new byte[length + length / 2 + 8];
		int w = 0;
		int zeroes = 0;
		for (int i = 0; i < length; i++)
		{
			final int b = rbsp[i] & 0xFF;
			if (zeroes >= 2 && b <= 3)
			{
				out[w++] = 3;
				zeroes = 0;
			}
			out[w++] = (byte) b;
			zeroes = b == 0 ? zeroes + 1 : 0;
		}
		return Arrays.copyOf(out, w);
	}
}
