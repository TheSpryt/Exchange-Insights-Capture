package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.jcodec.api.awt.AWTFrameGrab;
import org.junit.Test;

/**
 * Measures what the encode pipeline actually does to pixel values.
 *
 * <p>The clips look wrong and there are two opposite explanations - the levels are being
 * compressed when they should not be, or not compressed when they should be. Arguing about it
 * from the JCodec source is guesswork; this pushes a known grey ramp through the real encoder
 * and reads the values back out, so the transfer function is measured rather than assumed.
 */
public class ColourRoundTripTest
{
	@Test
	public void reportTransferFunction() throws Exception
	{
		final int w = 256;
		final int h = 64;

		// A horizontal ramp: column x has value x, so the output at column x tells us exactly
		// what happened to input value x.
		final BufferedImage ramp = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int x = 0; x < w; x++)
		{
			final int v = (x << 16) | (x << 8) | x;
			for (int y = 0; y < h; y++)
			{
				ramp.setRGB(x, y, v);
			}
		}

		final ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(ramp, "jpg", jpeg);

		final List<RecordedFrame> frames = new ArrayList<>();
		for (int i = 0; i < 8; i++)
		{
			frames.add(new RecordedFrame(i * 33L, jpeg.toByteArray(), w, h));
		}

		// What the buffer alone costs, before the encoder sees anything.
		final BufferedImage afterJpeg = ClipEncoder.decodeFrame(frames.get(0));
		// decodeFrame no longer applies any curve - the shadow lift it used to carry was
		// compensating for a broken RGB-to-YUV conversion, since fixed at the source. So this
		// should now come back as close to the input as JPEG allows, and nothing more.
		System.out.println("=== after decodeFrame (JPEG only, no correction) ===");
		System.out.println("decoded type = " + afterJpeg.getType() + " (5 = 3BYTE_BGR, 1 = INT_RGB)");
		for (int x : new int[]{15, 30, 60, 120, 210})
		{
			System.out.printf("input %3d -> decoded %3d%n", x, afterJpeg.getRGB(x, h / 2) & 0xFF);
		}

		final File out = File.createTempFile("colour-roundtrip-", ".mp4");
		out.deleteOnExit();
		ClipEncoder.encode(out, frames, 30);

		final BufferedImage decoded = AWTFrameGrab.getFrame(out, 0);
		System.out.println("=== input -> decoded (grey ramp) ===");
		int sumDelta = 0;
		int samples = 0;
		for (int x = 0; x <= 255; x += 15)
		{
			final int got = decoded.getRGB(Math.min(x, decoded.getWidth() - 1), h / 2) & 0xFF;
			System.out.printf("in %3d -> out %3d  (delta %+d)%n", x, got, got - x);
			sumDelta += got - x;
			samples++;
		}
		System.out.printf("mean delta %+.1f%n", sumDelta / (double) samples);

		final int black = decoded.getRGB(0, h / 2) & 0xFF;
		final int white = decoded.getRGB(decoded.getWidth() - 1, h / 2) & 0xFF;
		System.out.printf("black end %d, white end %d%n", black, white);
	}
}
