package com.exchangeinsightscapture;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import lombok.extern.slf4j.Slf4j;

/**
 * A rolling buffer of microphone audio, kept in step with the frame buffer.
 *
 * <p>Recorded continuously and thrown away continuously, exactly like the video: when a clip is
 * triggered, the seconds leading up to it have to already exist, and there is no way to go back
 * and ask for them.
 *
 * <p>Mono, because that is what a microphone is. Recording a mic as stereo through an interface
 * that presents a pair of inputs captures the mic on one channel and silence on the other, which
 * plays back in one ear.
 */
@Slf4j
final class AudioCapture
{
	static final int SAMPLE_RATE = 48000;
	static final int CHANNELS = 1;
	static final int BYTES_PER_FRAME = 2 * CHANNELS;
	private static final int BYTES_PER_SECOND = SAMPLE_RATE * BYTES_PER_FRAME;

	/** What is recorded and what is muxed: 48kHz, 16-bit, mono, signed, little-endian. */
	static final AudioFormat FORMAT =
		new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);

	/** Fallback when a device will only give a stereo line; downmixed to mono on the way in. */
	private static final AudioFormat STEREO =
		new AudioFormat(SAMPLE_RATE, 16, 2, true, false);

	/**
	 * One read from the line, placed by sample count rather than by clock.
	 *
	 * <p>The clock reading was the bug this replaced: it was taken when a read RETURNED, which is
	 * when that audio ended, and then used as the time it began. Every chunk landed a read-length
	 * late and the gap in front of it was filled with silence, so the recording stuttered once per
	 * read. Audio arrives at a known, fixed rate, so counting samples places it exactly and cannot
	 * drift with scheduling.
	 */
	private static final class Chunk
	{
		final long startFrame;
		final byte[] pcm;

		Chunk(long startFrame, byte[] pcm)
		{
			this.startFrame = startFrame;
			this.pcm = pcm;
		}
	}

	private final Deque<Chunk> buffer = new ArrayDeque<>();
	private final Object lock = new Object();
	private volatile TargetDataLine line;
	private volatile Thread reader;
	private volatile long windowMs = 30_000;
	private volatile boolean downmix;
	/** Wall-clock time of the first captured sample; the whole timeline hangs off this. */
	private volatile long baseMs;
	private long framesCaptured;
	private long bufferedBytes;

	/** Input devices that can actually be recorded from, for the panel's picker. */
	static java.util.List<String> devices()
	{
		final java.util.List<String> out = new java.util.ArrayList<>();
		for (Mixer.Info mi : AudioSystem.getMixerInfo())
		{
			try
			{
				final Mixer mixer = AudioSystem.getMixer(mi);
				if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMAT))
					|| mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, STEREO)))
				{
					out.add(mi.getName());
				}
			}
			catch (Exception e)
			{
				log.debug("could not query mixer {}", mi.getName(), e);
			}
		}
		return out;
	}

	/**
	 * Begin recording from {@code deviceName}, or the system default when it is blank or gone.
	 *
	 * @return true if a line was opened.
	 */
	boolean start(String deviceName, int windowSeconds)
	{
		stop();
		this.windowMs = Math.max(5, windowSeconds) * 1000L;
		try
		{
			TargetDataLine opened = open(deviceName, FORMAT);
			downmix = false;
			if (opened == null)
			{
				// Plenty of interfaces only offer their inputs as a stereo pair.
				opened = open(deviceName, STEREO);
				downmix = true;
			}
			if (opened == null)
			{
				log.warn("No usable microphone input was found");
				return false;
			}

			opened.open(downmix ? STEREO : FORMAT);
			opened.start();
			line = opened;
			synchronized (lock)
			{
				buffer.clear();
				bufferedBytes = 0;
				framesCaptured = 0;
			}
			baseMs = System.currentTimeMillis();

			final Thread t = new Thread(this::read, "instant-replay-audio");
			t.setDaemon(true);
			reader = t;
			t.start();
			return true;
		}
		catch (Exception e)
		{
			log.warn("Could not start microphone capture", e);
			stop();
			return false;
		}
	}

	private TargetDataLine open(String deviceName, AudioFormat want) throws Exception
	{
		final DataLine.Info info = new DataLine.Info(TargetDataLine.class, want);
		if (deviceName != null && !deviceName.trim().isEmpty())
		{
			for (Mixer.Info mi : AudioSystem.getMixerInfo())
			{
				if (mi.getName().equals(deviceName))
				{
					final Mixer mixer = AudioSystem.getMixer(mi);
					return mixer.isLineSupported(info) ? (TargetDataLine) mixer.getLine(info) : null;
				}
			}
			log.debug("microphone {} not available, using the default", deviceName);
		}
		return AudioSystem.isLineSupported(info) ? (TargetDataLine) AudioSystem.getLine(info) : null;
	}

	private void read()
	{
		// A fifth of a second per read: small enough that the window trims smoothly, large enough
		// that the thread is not woken constantly.
		final byte[] buf = new byte[BYTES_PER_SECOND / 5 * (downmix ? 2 : 1)];
		while (true)
		{
			final TargetDataLine l = line;
			if (l == null || !l.isOpen())
			{
				return;
			}
			final int read;
			try
			{
				read = l.read(buf, 0, buf.length);
			}
			catch (Exception e)
			{
				return; // closed underneath us
			}
			if (read <= 0)
			{
				continue;
			}

			final byte[] mono = downmix ? toMono(buf, read) : java.util.Arrays.copyOf(buf, read);
			synchronized (lock)
			{
				buffer.addLast(new Chunk(framesCaptured, mono));
				framesCaptured += mono.length / BYTES_PER_FRAME;
				bufferedBytes += mono.length;
				final long limit = windowMs * BYTES_PER_SECOND / 1000;
				while (bufferedBytes > limit && !buffer.isEmpty())
				{
					bufferedBytes -= buffer.removeFirst().pcm.length;
				}
			}
		}
	}

	/**
	 * Average the two channels rather than taking the left.
	 *
	 * <p>An interface that presents its inputs as a pair often has the microphone on one of them
	 * and nothing on the other, so keeping a single channel is a coin toss between the mic and
	 * silence. Averaging always carries whichever one has the signal.
	 */
	private static byte[] toMono(byte[] stereo, int length)
	{
		final int frames = length / 4;
		final byte[] out = new byte[frames * 2];
		for (int i = 0; i < frames; i++)
		{
			final int l = (short) ((stereo[i * 4] & 0xFF) | (stereo[i * 4 + 1] << 8));
			final int r = (short) ((stereo[i * 4 + 2] & 0xFF) | (stereo[i * 4 + 3] << 8));
			final int m = (l + r) / 2;
			out[i * 2] = (byte) (m & 0xFF);
			out[i * 2 + 1] = (byte) ((m >> 8) & 0xFF);
		}
		return out;
	}

	void stop()
	{
		final TargetDataLine l = line;
		line = null;
		if (l != null)
		{
			try
			{
				l.stop();
				l.close();
			}
			catch (Exception e)
			{
				log.debug("could not close the microphone", e);
			}
		}
		final Thread t = reader;
		reader = null;
		if (t != null)
		{
			t.interrupt();
		}
		synchronized (lock)
		{
			buffer.clear();
			bufferedBytes = 0;
			framesCaptured = 0;
		}
	}

	boolean isRunning()
	{
		final TargetDataLine l = line;
		return l != null && l.isOpen();
	}

	/**
	 * The audio recorded between two wall-clock times, as PCM in {@link #FORMAT}.
	 *
	 * <p>Cut on the sample timeline, so what comes back is one continuous run of audio with no
	 * joins in it. Only the very start can need padding, when a clip reaches further back than the
	 * microphone has been running.
	 *
	 * @return the samples, or null when nothing usable was recorded in that span.
	 */
	byte[] range(long startMs, long endMs)
	{
		if (endMs <= startMs)
		{
			return null;
		}
		final long base = baseMs;
		long wantFrom = (startMs - base) * SAMPLE_RATE / 1000;
		final long wantTo = (endMs - base) * SAMPLE_RATE / 1000;
		if (wantTo <= 0)
		{
			return null;
		}

		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		// Everything before the microphone started is silence rather than a shift: without it the
		// sound would begin early and stay out of step for the whole clip.
		if (wantFrom < 0)
		{
			final int pad = (int) Math.min(-wantFrom, SAMPLE_RATE * 60L) * BYTES_PER_FRAME;
			out.write(new byte[pad], 0, pad);
			wantFrom = 0;
		}

		boolean any = false;
		synchronized (lock)
		{
			for (Chunk c : buffer)
			{
				final long chunkFrames = c.pcm.length / BYTES_PER_FRAME;
				final long chunkEnd = c.startFrame + chunkFrames;
				if (chunkEnd <= wantFrom || c.startFrame >= wantTo)
				{
					continue;
				}
				final int from = (int) Math.max(0, wantFrom - c.startFrame) * BYTES_PER_FRAME;
				final int to = (int) Math.min(chunkFrames, wantTo - c.startFrame) * BYTES_PER_FRAME;
				if (to > from)
				{
					out.write(c.pcm, from, to - from);
					any = true;
				}
			}
		}
		return any ? out.toByteArray() : null;
	}
}
