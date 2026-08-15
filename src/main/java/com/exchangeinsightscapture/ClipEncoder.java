package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns buffered frames into an MP4.
 *
 * <p>The encoding itself lives in {@link H264Writer}; this holds the frame preparation both it
 * and the preview path share.
 */
@Slf4j
final class ClipEncoder
{
	/**
	 * Decode one buffered frame. Safe to run on any thread.
	 *
	 * <p>No colour correction here, deliberately. This used to apply a measured curve to lift
	 * shadows back up, because clips came out visibly dark. That curve was treating a symptom:
	 * the real cause was JCodec's RgbToYuv420j writing luma at about 0.945 gain (see
	 * {@code H264Writer.toYuv420j}). With the conversion itself corrected the curve became pure
	 * over-brightening, measurably pushing a grey ramp nearly nine levels the other way.
	 */
	static BufferedImage decodeFrame(RecordedFrame frame)
	{
		try
		{
			final BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpeg));
			if (image == null)
			{
				return null;
			}
			return image;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("frame decode failed", e);
			return null;
		}
	}

	static void encode(File out, List<RecordedFrame> frames, int fps) throws IOException
	{
		encode(out, frames, fps, ClipQuality.MEDIUM.quantiser(), null, () -> false);
	}

	/**
	 * Write the frames to {@code out} as H.264.
	 *
	 * @param quantiser lower is better quality and a bigger file; see {@link ClipQuality}.
	 * @param audioPcm  the soundtrack, or null for a silent clip.
	 * @param cancelled checked between frames so a cancelled clip stops promptly instead of
	 *                  finishing an encode nobody wants.
	 */
	static void encode(File out, List<RecordedFrame> frames, int fps, int quantiser,
		byte[] audioPcm, BooleanSupplier cancelled)
		throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to encode");
		}
		H264Writer.write(out, frames, fps, quantiser, audioPcm, cancelled);
	}
}
