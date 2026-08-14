package com.instantreplay;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;

/**
 * Encodes a list of buffered JPEG frames into an H.264 MP4 file using JCodec.
 * JCodec is pure Java, so no native libraries or external processes are needed.
 */
@Slf4j
class ClipEncoder
{
	/**
	 * Decode the buffered frames and write them to {@code out} as an MP4 at the
	 * given framerate. Runs on a background thread; may take a moment for long
	 * or high-resolution clips.
	 */
	/**
	 * Write the buffered frames as Motion JPEG without re-encoding them.
	 *
	 * <p>The rolling buffer already holds each frame as a JPEG, and H.264 encoding spends its
	 * time decoding those back to pixels and compressing them again. Muxing them straight into
	 * an MP4 as a JPEG track skips both halves: saving becomes a file copy and finishes in a
	 * fraction of a second rather than tens of seconds. The trade is file size - every frame is
	 * a keyframe, so there is no interframe compression - and narrower playback support (VLC and
	 * most desktop players are fine; browsers generally are not).
	 */
	static void encodeMjpeg(File out, List<RecordedFrame> frames, int fps) throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to encode");
		}

		// Dimensions come from the first frame; every frame in a clip is scaled identically.
		final BufferedImage first = ImageIO.read(new ByteArrayInputStream(frames.get(0).jpeg));
		if (first == null)
		{
			throw new IOException("Could not read the first frame");
		}
		final int width = first.getWidth();
		final int height = first.getHeight();
		final int rate = Math.max(1, fps);

		SeekableByteChannel channel = null;
		try
		{
			channel = NIOUtils.writableChannel(out);
			final MP4Muxer muxer = MP4Muxer.createMP4MuxerToChannel(channel);
			final MuxerTrack track = muxer.addVideoTrack(Codec.JPEG,
				VideoCodecMeta.createSimpleVideoCodecMeta(new Size(width, height), ColorSpace.YUV420J));

			int i = 0;
			for (RecordedFrame frame : frames)
			{
				// Every JPEG is self-contained, so each packet is a keyframe with a 1-tick duration
				// against a timescale of `rate` - which is what makes playback run at the right speed.
				track.addFrame(Packet.createPacket(ByteBuffer.wrap(frame.jpeg), i, rate, 1, i,
					Packet.FrameType.KEY, null));
				i++;
			}
			muxer.finish();
		}
		finally
		{
			NIOUtils.closeQuietly(channel);
		}
	}

	static void encode(File out, List<RecordedFrame> frames, int fps) throws IOException
	{
		if (frames.isEmpty())
		{
			throw new IOException("No frames to encode");
		}

		AWTSequenceEncoder encoder = AWTSequenceEncoder.createSequenceEncoder(out, Math.max(1, fps));
		try
		{
			for (RecordedFrame frame : frames)
			{
				BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame.jpeg));
				if (image == null)
				{
					continue;
				}
				encoder.encodeImage(image);
			}
		}
		finally
		{
			encoder.finish();
		}
	}
}
