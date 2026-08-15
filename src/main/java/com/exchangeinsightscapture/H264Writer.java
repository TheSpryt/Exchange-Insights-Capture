package com.exchangeinsightscapture;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.encode.RateControl;
import org.jcodec.codecs.h264.io.model.SliceType;
import org.jcodec.codecs.h264.io.model.NALUnit;
import org.jcodec.codecs.h264.io.model.NALUnitType;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;

/**
 * Writes an H.264 MP4, driving JCodec's encoder directly instead of through SequenceEncoder.
 *
 * <p>SequenceEncoder builds its encoder with {@code H264Encoder.createH264Encoder()} and exposes
 * neither the rate control nor the keyframe interval, which caused two problems at once:
 *
 * <ul>
 *   <li>Its default DumbRateControl gives each macroblock a fixed bit budget. Detailed regions
 *       cannot be encoded within it, so they are refined over SUBSEQUENT frames - visible as
 *       bands of blocks slowly resolving toward the right colour.</li>
 *   <li>Predicted frames depend on the frame before them, forcing the whole encode to be serial
 *       and leaving most of the CPU idle.</li>
 * </ul>
 *
 * <p>Both go away by encoding every frame as a keyframe at a fixed quantiser: each frame is
 * complete in itself, so nothing refines late, and no frame depends on another. It also skips
 * motion estimation entirely, which is normally the most expensive part of encoding.
 *
 * <p>The trade is size. Without interframe compression a mostly-static scene no longer costs
 * almost nothing, so files are considerably larger.
 */
@Slf4j
final class H264Writer
{
	/**
	 * Quantiser. Lower is better quality and bigger; H.264 QP runs 0-51, and the low twenties
	 * is the usual "visually clean" range. 20 is deliberately generous - the whole point of
	 * this class is that the previous output was not good enough.
	 */


	/** Milliseconds. Frame times are recorded in ms, so this needs no conversion and no rounding. */
	private static final int TIMESCALE = 1000;

	// Full-range BT.601, in 16.16 fixed point. The luma coefficients sum to exactly 65536, so a
	// grey input comes back as itself rather than drifting.
	private static final int YR = 19595, YG = 38470, YB = 7471;
	private static final int CBR = -11056, CBG = -21712, CBB = 32768;
	private static final int CRR = 32768, CRG = -27440, CRB = -5328;
	private static final int HALF = 32768;
	private static final int CENTRE = 128 << 16;

	/**
	 * Hold a sample in 0-255.
	 *
	 * <p>Not optional: pure red puts Cr at exactly 256 and pure blue does the same to Cb, one past
	 * the top. Without this they wrap through the signed byte to -128 - the opposite end - so red
	 * encoded as green and blue as near-black.
	 */
	private static int clamp(int v)
	{
		return v < 0 ? 0 : Math.min(v, 255);
	}

	/**
	 * Convert a frame to full-range BT.601 YUV 4:2:0, straight into JCodec's planes.
	 *
	 * <p>This replaces JCodec's own {@code RgbToYuv420j}, which measurably writes luma at about
	 * 0.945 gain with no offset - white lands on 241 and mid-grey on 121. That is neither full
	 * range nor limited range, just a wrong matrix, and it made every clip roughly 5.5% dark:
	 * about seven levels off on all three channels at midtones, which is what made colours look
	 * muddy and similar shades blend together. The damage was on the ENCODE side, so it was baked
	 * into the file and no player could undo it.
	 *
	 * <p>Samples go in the way JCodec stores them: signed, offset by -128.
	 */
	static void toYuv420j(BufferedImage image, Picture yuv)
	{
		final int w = Math.min(image.getWidth(), yuv.getWidth());
		final int h = Math.min(image.getHeight(), yuv.getHeight());
		final byte[] luma = yuv.getPlaneData(0);
		final byte[] cbPlane = yuv.getPlaneData(1);
		final byte[] crPlane = yuv.getPlaneData(2);
		final int lumaStride = yuv.getWidth();
		final int chromaStride = yuv.getPlaneWidth(1);

		// One row pair at a time: chroma is averaged over each 2x2 block, so the two rows have to
		// be in hand together.
		final int[] top = new int[w];
		final int[] bottom = new int[w];
		for (int y = 0; y < h; y += 2)
		{
			image.getRGB(0, y, w, 1, top, 0, w);
			final boolean hasBottom = y + 1 < h;
			if (hasBottom)
			{
				image.getRGB(0, y + 1, w, 1, bottom, 0, w);
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

						luma[(y + dy) * lumaStride + x + dx] =
							(byte) (clamp((YR * r + YG * g + YB * b + HALF) >> 16) - 128);

						cbSum += clamp((CBR * r + CBG * g + CBB * b + CENTRE + HALF) >> 16);
						crSum += clamp((CRR * r + CRG * g + CRB * b + CENTRE + HALF) >> 16);
						count++;
					}
				}

				final int at = (y >> 1) * chromaStride + (x >> 1);
				cbPlane[at] = (byte) (clamp(cbSum / count) - 128);
				crPlane[at] = (byte) (clamp(crSum / count) - 128);
			}
		}
	}

	/**
	 * Stamp the colour description onto the sequence parameter set.
	 *
	 * <p>Without this a clip is a full-range BT.601 file that says nothing about itself, and every
	 * player guesses - wrongly, twice over. An unflagged H.264 stream is assumed to be limited
	 * range, so 16-235 gets stretched to 0-255: blacks crush, and measured against the source that
	 * alone was seven levels of darkening across the whole frame. At 720p and above players also
	 * assume BT.709, while these frames are converted with BT.601 coefficients, which tilts every
	 * colour on top of that. Both are pure signalling; the samples were always right.
	 *
	 * <p>JCodec builds the SPS internally and offers no hook, so the encoded NAL is parsed back
	 * out, given a VUI, and written again. Only the SPS is touched - every other NAL is copied
	 * through byte for byte.
	 */
	private static ByteBuffer withColourInfo(ByteBuffer frame)
	{
		final ByteBuffer in = frame.duplicate();
		final ByteBuffer out = ByteBuffer.allocate(in.remaining() + 256);
		ByteBuffer nal;
		boolean patched = false;

		while ((nal = H264Utils.nextNALUnit(in)) != null)
		{
			final ByteBuffer header = nal.duplicate();
			final NALUnit nu = NALUnit.read(header);

			out.putInt(1);
			if (nu.type == NALUnitType.SPS)
			{
				final ByteBuffer rbsp = header.duplicate();
				H264Utils.unescapeNAL(rbsp);
				final SeqParameterSet sps = SeqParameterSet.read(rbsp);

				VUIParameters vui = sps.vuiParams;
				if (vui == null)
				{
					vui = new VUIParameters();
					sps.vuiParams = vui;
				}
				vui.videoSignalTypePresentFlag = true;
				vui.videoFormat = 5; // unspecified
				vui.videoFullRangeFlag = true;
				vui.colourDescriptionPresentFlag = true;
				vui.colourPrimaries = 1; // BT.709 primaries, what a desktop display shows
				vui.transferCharacteristics = 1;
				vui.matrixCoefficients = 6; // BT.601, matching toYuv420j

				final ByteBuffer raw = ByteBuffer.allocate(1024);
				sps.write(raw);
				raw.flip();
				final ByteBuffer escaped = ByteBuffer.allocate(raw.remaining() * 2 + 16);
				H264Utils.escapeNAL(raw, escaped);
				escaped.flip();

				nu.write(out);
				out.put(escaped);
				patched = true;
			}
			else
			{
				out.put(nal);
			}
		}

		out.flip();
		// A frame with no SPS in it - every non-keyframe - is returned untouched rather than
		// rebuilt, so the common path costs nothing and cannot be corrupted by this.
		return patched ? out : frame;
	}

	/**
	 * Constant quantiser, which is what short clips want.
	 *
	 * <p>JCodec's two stock rate controls both pin the quantiser somewhere unhelpful:
	 * H264FixedRateControl hard-codes QP 26 for keyframes and 30 for predicted frames regardless
	 * of what you pass it, and DumbRateControl chases a bits-per-macroblock target instead of a
	 * quality level. Neither is adjustable from outside, and QP 30 on a predicted frame is where
	 * the blockiness came from.
	 *
	 * <p>The interface is three methods, so quality can simply be stated: the same quantiser for
	 * every frame and every macroblock, accepting whatever bits that costs. Clips are seconds
	 * long and land on a local disk, so there is no bitrate ceiling worth defending - and a
	 * constant quantiser is what keeps quality from sagging during exactly the busy moments
	 * anyone bothers to clip.
	 */
	private static final class ConstantQp implements RateControl
	{
		private final int qp;

		ConstantQp(int qp)
		{
			this.qp = qp;
		}

		@Override
		public int startPicture(Size size, int maxSize, SliceType sliceType)
		{
			return qp;
		}

		@Override
		public int initialQpDelta()
		{
			return 0;
		}

		@Override
		public int accept(int bits)
		{
			return 0; // never trade quality away mid-frame
		}
	}

	private H264Writer()
	{
	}

	/**
	 * Encode {@code frames} into {@code out}.
	 *
	 * <p>Video only. There is deliberately no audio track here - see {@link AudioCapture} for why.
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
		final int rate = Math.max(1, fps);
		final long firstTimestamp = frames.get(0).timestampMs;
		// A guess only for the final frame, which has no successor to measure against.
		final long lastFrameDuration = Math.max(1, TIMESCALE / rate);

		final H264Encoder encoder = new H264Encoder(new ConstantQp(quantiser));
		// One keyframe, at the start.
		//
		// Every frame a keyframe made static areas shimmer, because each quantised on its own.
		// One a second replaced that with a visible "refresh" every second, as each keyframe
		// re-derived colours the predicted frames had been carrying along unchanged. Clips are
		// seconds long, so periodic keyframes buy nothing here - seeking within them is not a
		// thing anyone does - and a single one at the start removes the pulse entirely.
		// Bounded by the clip's own length rather than Integer.MAX_VALUE, which overflows
		// JCodec's frame_num arithmetic ("frame_num > -2147483648") and fails the encode.
		encoder.setKeyInterval(Math.max(1, frames.size()));

		final Picture yuv = Picture.create(width, height, ColorSpace.YUV420J);

		SeekableByteChannel channel = null;
		try
		{
			channel = NIOUtils.writableChannel(out);
			final MP4Muxer muxer = MP4Muxer.createMP4MuxerToChannel(channel);
			// The muxer track pulls SPS/PPS out of the first packets itself and builds the avcC
			// sample entry, so the bitstream headers do not have to be assembled by hand.
			final MuxerTrack track = muxer.addVideoTrack(Codec.H264,
				VideoCodecMeta.createSimpleVideoCodecMeta(new Size(width, height), ColorSpace.YUV420J));

			ByteBuffer buffer = ByteBuffer.allocate(width * height * 3);
			int index = 0;

			// Decoding the JPEG buffer and shifting its levels is independent per frame, so it
			// runs ahead on a small pool while the encoder works. Bounded look-ahead: enough to
			// keep the encoder fed, not so much that a long clip holds hundreds of full-size
			// decoded frames at once.
			final int workers = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors() / 3));
			final java.util.concurrent.ExecutorService pool =
				java.util.concurrent.Executors.newFixedThreadPool(workers, r ->
				{
					final Thread th = new Thread(r, "capture-decode");
					th.setDaemon(true);
					return th;
				});
			try
			{
				final int lookAhead = workers * 2;
				final java.util.ArrayDeque<java.util.concurrent.Future<BufferedImage>> queue =
					new java.util.ArrayDeque<>(lookAhead);
				// The frame each queued decode came from, so its timestamp can be recovered when
				// it surfaces - decodes finish in order here, but the index is not implicit.
				final java.util.ArrayDeque<Integer> sources = new java.util.ArrayDeque<>(lookAhead);
				int next = 0;
				while (next < frames.size() && queue.size() < lookAhead)
				{
					final int at = next++;
					final RecordedFrame f = frames.get(at);
					queue.add(pool.submit(() -> at == 0 ? first : ClipEncoder.decodeFrame(f)));
					sources.add(at);
				}

				while (!queue.isEmpty())
				{
					if (cancelled.getAsBoolean())
					{
						break;
					}
					final BufferedImage image = queue.poll().get();
					final int sourceIndex = sources.poll();
					if (next < frames.size())
					{
						final int at = next++;
						final RecordedFrame f = frames.get(at);
						queue.add(pool.submit(() -> ClipEncoder.decodeFrame(f)));
						sources.add(at);
					}
					if (image == null)
					{
						continue;
					}

					toYuv420j(image, yuv);

					// The encoder needs room for the worst case; a heavily detailed frame at a
					// low quantiser can exceed a first guess, so grow rather than truncate.
					final int needed = encoder.estimateBufferSize(yuv);
					if (buffer.capacity() < needed)
					{
						buffer = ByteBuffer.allocate(needed);
					}
					buffer.clear();

					final VideoEncoder.EncodedFrame encoded = encoder.encodeFrame(yuv, buffer);

					// Real timing, not a constant rate. Capture follows the client, so a clip can
					// span 240fps in a quiet area and 80fps in a busy one. Encoding that at one
					// average rate keeps the TOTAL duration right while playing the fast section
					// in slow motion and the slow section fast-forwarded. Timestamps in
					// milliseconds, with each frame lasting until the next one, play it back at
					// the speed it happened.
					final RecordedFrame src = frames.get(sourceIndex);
					final long pts = src.timestampMs - firstTimestamp;
					final long duration = sourceIndex + 1 < frames.size()
						? Math.max(1, frames.get(sourceIndex + 1).timestampMs - src.timestampMs)
						: lastFrameDuration;

					track.addFrame(Packet.createPacket(
						NIOUtils.clone(withColourInfo(encoded.getData())), pts,
						TIMESCALE, duration, index,
						encoded.isKeyFrame() ? Packet.FrameType.KEY : Packet.FrameType.INTER, null));
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

			muxer.finish();
		}
		finally
		{
			NIOUtils.closeQuietly(channel);
		}
	}
}
