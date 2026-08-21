package com.exchangeinsightscapture;

import com.exchangeinsightscapture.h264.H264Encoder;
import com.exchangeinsightscapture.h264.Mp4Writer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns buffered frames into an MP4.
 *
 * <p>The encoder and the container are both ours - see {@link H264Encoder} and {@link Mp4Writer}.
 * They replaced JCodec, which the Plugin Hub does not allow, and which had cost a long chase
 * through colour and quality problems of its own before that.
 *
 * <p>The first frame is coded whole and every frame after it as a difference from the one before,
 * which is what keeps a clip to tens of megabytes rather than hundreds. There are no further
 * keyframes: a clip is seconds long, so seeking within one is not something anyone does, and each
 * additional keyframe costs several times what a predicted frame does.
 */
@Slf4j
final class H264Writer
{
	/** Durations are milliseconds, matching the timestamps frames are captured with. */
	private static final int TIMESCALE = Mp4Writer.TIMESCALE;

	/**
	 * Full-range BT.601, in fixed point.
	 *
	 * <p>These are the coefficients the stream declares in its own colour description, so the two
	 * cannot drift apart: change one and the other has to change with it.
	 */
	private static final int YR = 19595, YG = 38470, YB = 7471;
	private static final int CBR = -11056, CBG = -21712, CBB = 32768;
	private static final int CRR = 32768, CRG = -27440, CRB = -5328;
	private static final int HALF = 32768;
	private static final int CENTRE = 128 << 16;

	/** How wide the preview carried inside the clip is. Enough for the side panel, and cheap. */
	private static final int THUMBNAIL_WIDTH = 640;

	private H264Writer()
	{
	}

	private static int clamp(int v)
	{
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	/**
	 * Convert a frame to full-range BT.601 YUV 4:2:0.
	 *
	 * <p>This began as a replacement for JCodec's RgbToYuv420j, which measurably wrote luma at
	 * about 0.945 gain with no offset - white landing on 241, mid-grey on 121. That is neither
	 * full range nor limited range, just a wrong matrix, and it made every clip about 5.5% dark:
	 * some seven levels off on all three channels at midtones, which is what made colours look
	 * muddy and near shades blend together. The damage was done on the encode side, so it was
	 * baked into the file and no player could undo it. It outlived JCodec because it was correct.
	 */
	static void toYuv(BufferedImage image, byte[] luma, byte[] cb, byte[] cr, int w, int h)
	{
		final int chromaStride = (w + 1) / 2;
		final int[] top = new int[w];
		final int[] bottom = new int[w];
		final Rows rows = rowsOf(image, w);

		// A row pair at a time: chroma is averaged over each 2x2 block, so both rows must be in
		// hand together.
		for (int y = 0; y < h; y += 2)
		{
			rows.read(y, top);
			final boolean hasBottom = y + 1 < h;
			if (hasBottom)
			{
				rows.read(y + 1, bottom);
			}

			for (int x = 0; x < w; x += 2)
			{
				int cbSum = 0;
				int crSum = 0;
				int count = 0;

				for (int dy = 0; dy < (hasBottom ? 2 : 1); dy++)
				{
					final int[] row = dy == 0 ? top : bottom;
					for (int dx = 0; dx < 2 && x + dx < w; dx++)
					{
						final int p = row[x + dx];
						final int r = (p >> 16) & 0xFF;
						final int g = (p >> 8) & 0xFF;
						final int b = p & 0xFF;

						luma[(y + dy) * w + x + dx] =
							(byte) clamp((YR * r + YG * g + YB * b + HALF) >> 16);

						cbSum += clamp((CBR * r + CBG * g + CBB * b + CENTRE + HALF) >> 16);
						crSum += clamp((CRR * r + CRG * g + CRB * b + CENTRE + HALF) >> 16);
						count++;
					}
				}

				final int at = (y >> 1) * chromaStride + (x >> 1);
				cb[at] = (byte) clamp(cbSum / count);
				cr[at] = (byte) clamp(crSum / count);
			}
		}
	}

	/** Reads one row of a frame as packed RGB. */
	private interface Rows
	{
		void read(int y, int[] out);
	}

	/**
	 * Pick the cheapest way to read rows out of this particular image.
	 *
	 * <p>{@code getRGB} goes through the colour model one pixel at a time, which is a real cost
	 * at nearly a million pixels a frame, fifty times a second. Decoding a JPEG produces
	 * three-byte BGR, and for that layout the bytes can be read straight out of the raster
	 * instead. Anything else falls back to the general path, which is always correct and merely
	 * slower - so an unexpected image type costs speed rather than pixels.
	 */
	private static Rows rowsOf(BufferedImage image, int w)
	{
		if (image.getType() == BufferedImage.TYPE_3BYTE_BGR
			&& image.getSampleModel() instanceof java.awt.image.ComponentSampleModel
			&& image.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte)
		{
			final java.awt.image.ComponentSampleModel model =
				(java.awt.image.ComponentSampleModel) image.getSampleModel();
			final java.awt.image.DataBufferByte buffer =
				(java.awt.image.DataBufferByte) image.getRaster().getDataBuffer();
			if (model.getPixelStride() == 3 && buffer.getNumBanks() == 1
				&& image.getRaster().getMinX() == 0 && image.getRaster().getMinY() == 0)
			{
				final byte[] data = buffer.getData();
				final int scanline = model.getScanlineStride();
				final int origin = buffer.getOffset();
				return (y, out) ->
				{
					int at = origin + y * scanline;
					for (int x = 0; x < w; x++, at += 3)
					{
						out[x] = ((data[at + 2] & 0xFF) << 16)
							| ((data[at + 1] & 0xFF) << 8)
							| (data[at] & 0xFF);
					}
				};
			}
		}
		return (y, out) -> image.getRGB(0, y, w, 1, out, 0, w);
	}

	/**
	 * A preview to carry inside the clip.
	 *
	 * <p>Made here because this is the one place the first frame already exists as an image. The
	 * alternative is decoding it back out of the finished clip, which needs an H.264 decoder -
	 * several times the work of the encoder, for a thumbnail.
	 */
	private static byte[] thumbnail(BufferedImage first)
	{
		try
		{
			final int w = Math.min(THUMBNAIL_WIDTH, first.getWidth());
			final int h = Math.max(1, Math.round((float) first.getHeight() * w / first.getWidth()));
			final BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			final java.awt.Graphics2D g = small.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(first, 0, 0, w, h, null);
			g.dispose();

			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(small, "jpg", out);
			return out.toByteArray();
		}
		catch (IOException | RuntimeException e)
		{
			// A clip without a preview is worth far more than no clip.
			log.debug("could not build a preview", e);
			return null;
		}
	}

	/**
	 * Encode {@code frames} into {@code out}.
	 *
	 * @param cancelled checked between frames so a cancelled clip stops promptly.
	 */
	static void write(File out, List<RecordedFrame> frames, int fps, int quantiser,
		BooleanSupplier cancelled)
		throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to encode");
		}

		final BufferedImage first = ClipEncoder.decodeFrame(frames.get(0));
		if (first == null)
		{
			throw new IOException("Could not read the first frame");
		}
		final int width = first.getWidth();
		final int height = first.getHeight();
		final long firstTimestamp = frames.get(0).timestampMs;
		// A guess only for the final frame, which has no successor to measure against.
		final int lastFrameDuration = Math.max(1, TIMESCALE / Math.max(1, fps));

		final H264Encoder encoder = new H264Encoder(width, height, quantiser);
		final byte[] luma = new byte[width * height];
		final byte[] cb = new byte[((width + 1) / 2) * ((height + 1) / 2)];
		final byte[] cr = new byte[cb.length];

		// Decoding the buffered JPEG is independent per frame, so it runs ahead on a small pool
		// while the encoder works. Bounded look-ahead: enough to keep the encoder fed, not so much
		// that a long clip holds hundreds of full-size decoded frames at once.
		final int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 3));
		final java.util.concurrent.ExecutorService pool =
			java.util.concurrent.Executors.newFixedThreadPool(workers, r ->
			{
				final Thread th = new Thread(r, "capture-decode");
				th.setDaemon(true);
				return th;
			});

		try (Mp4Writer mp4 = new Mp4Writer(out, width, height,
			encoder.sps(fps), encoder.pps(), thumbnail(first), frames.size()))
		{
			final int lookAhead = workers * 2;
			final java.util.ArrayDeque<java.util.concurrent.Future<BufferedImage>> queue =
				new java.util.ArrayDeque<>(lookAhead);
			int next = 0;
			int index = 0;

			while (next < frames.size() && queue.size() < lookAhead)
			{
				final int at = next++;
				final RecordedFrame f = frames.get(at);
				queue.add(pool.submit(() -> at == 0 ? first : ClipEncoder.decodeFrame(f)));
			}

			while (!queue.isEmpty())
			{
				if (cancelled.getAsBoolean())
				{
					return;
				}
				final BufferedImage image = queue.poll().get();
				if (next < frames.size())
				{
					final RecordedFrame f = frames.get(next++);
					queue.add(pool.submit(() -> ClipEncoder.decodeFrame(f)));
				}
				if (image == null)
				{
					index++;
					continue;
				}

				toYuv(image, luma, cb, cr, Math.min(width, image.getWidth()),
					Math.min(height, image.getHeight()));

				final RecordedFrame src = frames.get(index);
				final int duration = index + 1 < frames.size()
					? (int) Math.max(1, frames.get(index + 1).timestampMs - src.timestampMs)
					: lastFrameDuration;

				final byte[] nal = index == 0
					? encoder.encodeIdr(luma, cb, cr)
					: encoder.encodeP(luma, cb, cr);
				mp4.addFrame(nal, duration, index == 0);
				index++;
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (java.util.concurrent.ExecutionException e)
		{
			throw new IOException("frame decode failed", e);
		}
		finally
		{
			pool.shutdownNow();
		}
	}
}
