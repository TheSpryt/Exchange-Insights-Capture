package com.exchangeinsightscapture;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders a game sound effect from its cache definition.
 *
 * <p>Sound effects are not stored as audio. They are synthesiser patches - a door opening is 159
 * bytes - so the client builds the sound each time it plays one. To put game audio in a clip
 * without recording the speakers, the same thing has to happen here.
 *
 * <p>The parse follows RuneLite's cache loaders, which are correct but name everything
 * {@code field1179} because they were produced by deobfuscation. The names here are the meanings
 * those fields have in the format, worked out from the order they are read in.
 *
 * <p>Not wired to anything yet - nothing can put audio into a clip a browser will play until there
 * is an encoder for it. See {@link AudioCapture} for that half of the problem.
 *
 * <p>The synthesis was fitted against the real client rather than reasoned out. The game's own
 * audio was recorded and its spectrum compared against this code's, which turned three wrong
 * guesses into measurements:
 *
 * <ul>
 *   <li>Pitch envelope values are frequencies in Hz, flatly and with no scaling. The UI beep holds
 *       a constant 1000 and the real sound measures 999Hz, with 94% of its energy there.</li>
 *   <li>Oscillator pitch is added in Hz. It was being applied as a 2^(n/1536) detune, which for
 *       the value every sound uses comes to 1.05 - inaudible - so it did nothing whatsoever.</li>
 *   <li>A flat zero pitch envelope means the third envelope pair gives the period in samples. The
 *       coin tinkle measures 5514Hz; the mixer runs at 22050 and that sound's third pair ends at
 *       4, and 22050/4 is 5512.5.</li>
 * </ul>
 *
 * <p>Two plain defects turned up alongside those. The volume envelope was applied twice, squaring
 * every decay, and waveform 4 was a deterministic square running 977 times too fast rather than
 * noise, which made the door hiss instead of rumble. Either alone would have made any pitch model
 * sound wrong, which is part of why this took as long as it did to corner.
 *
 * <p>Still unverified: the eighteen unnamed sounds sharing the tinkle's signature, two of which
 * have a third pair ending at 2 or 3 rather than 4. The rule predicts them; nothing has confirmed
 * it. The filter is still not implemented - its pole magnitudes measure 0.14 and 0.20 on the two
 * sounds carrying one, far too low to ring, so it tilts timbre rather than supplying pitch. Worth
 * having checked, because a resonant filter would have been a very reasonable place for a metallic
 * sound to come from, and that is exactly what it turned out not to be.
 */
@Slf4j
final class SoundSynth
{
	/** What the game mixes at. */
	static final int SAMPLE_RATE = 22050;


	private SoundSynth()
	{
	}

	/** A cursor over the cache bytes, with the odd little integer formats the format uses. */
	private static final class Reader
	{
		private final byte[] data;
		private int at;

		Reader(byte[] data)
		{
			this.data = data;
		}

		int u8()
		{
			return data[at++] & 0xFF;
		}

		int u16()
		{
			return (u8() << 8) | u8();
		}

		int i32()
		{
			return (u16() << 16) | u16();
		}

		/** One byte when small, two when the top bit is set. */
		int smart()
		{
			final int peek = data[at] & 0xFF;
			return peek < 128 ? u8() : u16() - 32768;
		}

		/** As above, but centred on zero so it can go negative. */
		int signedSmart()
		{
			final int peek = data[at] & 0xFF;
			return peek < 128 ? u8() - 64 : u16() - 49152;
		}

		void back()
		{
			at--;
		}

		boolean has(int n)
		{
			return at + n <= data.length;
		}
	}

	/** A shape over time: start and end values with segments in between. */
	private static final class Envelope
	{
		int form;
		int start;
		int end;
		int[] durations;
		int[] phases;

		static Envelope read(Reader r)
		{
			final Envelope e = new Envelope();
			e.form = r.u8();
			e.start = r.i32();
			e.end = r.i32();
			final int segments = r.u8();
			e.durations = new int[segments];
			e.phases = new int[segments];
			for (int i = 0; i < segments; i++)
			{
				e.durations[i] = r.u16();
				e.phases[i] = r.u16();
			}
			return e;
		}

		/**
		 * The value at {@code t}, where t runs 0..1 across the sound.
		 *
		 * <p>Segments are positions along that span with a level at each, so this finds the pair
		 * either side of t and interpolates. Returned 0..1 rather than in the format's own units,
		 * so callers can scale it to whatever they are driving.
		 */
		double at(double t)
		{
			if (durations.length == 0)
			{
				return 1;
			}
			final double x = Math.max(0, Math.min(1, t)) * 65536;
			int i = 0;
			while (i < durations.length && durations[i] < x)
			{
				i++;
			}
			if (i == 0)
			{
				return phases[0] / 65536.0;
			}
			if (i >= durations.length)
			{
				return phases[durations.length - 1] / 65536.0;
			}
			final double span = Math.max(1, durations[i] - durations[i - 1]);
			final double f = (x - durations[i - 1]) / span;
			return (phases[i - 1] + (phases[i] - phases[i - 1]) * f) / 65536.0;
		}
	}

	/** One voice: a stack of oscillators shaped by a pitch and a volume envelope. */
	private static final class Instrument
	{
		Envelope pitch;
		Envelope volume;
		Envelope pitchModifier;
		Envelope pitchModifierAmplitude;
		Envelope volumeMultiplier;
		Envelope volumeMultiplierAmplitude;
		Envelope release;
		Envelope attack;
		/**
		 * The period, in samples, for a voice whose pitch envelope is flat zero.
		 *
		 * <p>Taken from the third envelope pair's end. See {@link #render} for the measurement
		 * this rests on.
		 */
		int flatPeriod;
		final int[] oscillatorVolume = new int[10];
		final int[] oscillatorPitch = new int[10];
		final int[] oscillatorDelay = new int[10];
		int delayTime;
		int delayDecay;
		int duration;
		int begin;

		static Instrument read(Reader r)
		{
			final Instrument in = new Instrument();
			in.pitch = Envelope.read(r);
			in.volume = Envelope.read(r);

			// Three optional pairs, each announced by a non-zero byte that is then rewound because
			// it is really the first byte of the envelope that follows.
			if (r.u8() != 0)
			{
				r.back();
				in.pitchModifier = Envelope.read(r);
				in.pitchModifierAmplitude = Envelope.read(r);
			}
			if (r.u8() != 0)
			{
				r.back();
				in.volumeMultiplier = Envelope.read(r);
				in.volumeMultiplierAmplitude = Envelope.read(r);
			}
			if (r.u8() != 0)
			{
				r.back();
				in.release = Envelope.read(r);
				in.attack = Envelope.read(r);
				in.flatPeriod = in.release.end;
			}

			for (int i = 0; i < 10; i++)
			{
				final int volume = r.smart();
				if (volume == 0)
				{
					break;
				}
				in.oscillatorVolume[i] = volume;
				in.oscillatorPitch[i] = r.signedSmart();
				in.oscillatorDelay[i] = r.smart();
			}

			in.delayTime = r.smart();
			in.delayDecay = r.smart();
			in.duration = r.u16();
			in.begin = r.u16();

			// Read even though it is not applied. The filter's bytes sit between this instrument
			// and the next one's presence byte, so skipping them leaves the cursor short and the
			// container then reads the filter as an instrument and walks off the end of the file.
			readFilter(r);
			return in;
		}

		/**
		 * Render this voice.
		 *
		 * <p>Accumulated as doubles and scaled once at the end. Summing into ints was what made
		 * the first attempt silent: the per-sample amplitude works out below 1, so every sample
		 * truncated to zero and three correctly-parsed sounds rendered as digital silence.
		 *
		 * <p>The pitch envelope's value is a frequency in Hz, flatly and with no scaling. That is
		 * measured, not deduced: the UI beep holds a constant 1000 and the real client plays it at
		 * 999Hz with 94% of its energy in that one peak. An earlier version treated the oscillator
		 * pitch as a detune of 2^(n/1536), which for the value every sound uses works out at 1.05 -
		 * inaudible - so it was doing nothing at all. It is added in Hz.
		 *
		 * <p>Then there is the case that took the longest. Nineteen of the cache's twelve thousand
		 * sounds have a pitch envelope of flat zero, and no reading of a zero can produce a pitch.
		 * All nineteen also carry the third envelope pair and an oscillator pitch of 120 - a
		 * signature that precise is not authoring coincidence. For the one of them the game names,
		 * the coin tinkle, the real sound measures 5514Hz, and the mixer runs at 22050 with that
		 * sound's third pair ending at 4. 22050/4 is 5512.5, inside a single analysis bin. So a
		 * flat zero pitch means the third pair gives the period in samples instead.
		 *
		 * <p>Deliberately narrow. The third pair is on 2828 sounds, and 2809 of those have a real
		 * pitch envelope, so letting it always win would throw away 2809 working envelopes -
		 * including the beep's, which is directly confirmed. This changes nineteen sounds and
		 * leaves the rest on the path that measurement backs.
		 */
		double[] render()
		{
			final int samples = Math.max(1, duration * SAMPLE_RATE / 1000);
			final double[] out = new double[samples];
			final boolean flat = pitch.start == 0 && pitch.end == 0 && flatPeriod > 0;

			for (int osc = 0; osc < oscillatorVolume.length; osc++)
			{
				final int oscVolume = oscillatorVolume[osc];
				if (oscVolume == 0)
				{
					continue;
				}
				final int delay = oscillatorDelay[osc] * SAMPLE_RATE / 1000;
				double phase = 0;
				// Noise state, per oscillator so two of them do not produce the same noise.
				long seed = 0x9E3779B97F4A7C15L * (osc + 1);
				double held = 0;
				double lastCycle = -1;

				for (int i = delay; i < samples; i++)
				{
					final double t = (double) i / samples;
					final double hz = flat
						? (double) SAMPLE_RATE / flatPeriod
						: pitch.start + (pitch.end - pitch.start) * pitch.at(t) + oscillatorPitch[osc];
					phase += Math.max(20, Math.min(SAMPLE_RATE / 2.0, hz)) / SAMPLE_RATE;

					double shape;
					if (pitch.form == NOISE)
					{
						// One new random value per cycle, so the pitch decides how coarse the
						// noise is. The previous attempt was ((int) (phase * 977) % 2), a
						// deterministic square running 977 times too fast, which turned the door -
						// whose waveform is noise - into a hiss.
						final double cycle = Math.floor(phase);
						if (cycle != lastCycle)
						{
							lastCycle = cycle;
							seed = seed * 6364136223846793005L + 1442695040888963407L;
							held = ((seed >>> 40) & 0xFFFFFF) / 8388608.0 - 1.0;
						}
						shape = held;
					}
					else
					{
						shape = wave(pitch.form, phase);
					}

					// Once, not twice. This used to fold volume.at(t) into a level that already
					// contained it, squaring the envelope, so every sound decayed harder and
					// faster than its definition asks for.
					final double level = (volume.start + (volume.end - volume.start) * volume.at(t)) / 100.0;
					out[i] += shape * level * (oscVolume / 100.0);
				}
			}
			return out;
		}
	}

	/**
	 * Consume a filter definition without keeping it.
	 *
	 * <p>Its shape has to be followed exactly, because how many bytes it occupies depends on its
	 * own contents: a pair count packed into one nibble each, then optionally a second set of
	 * coefficients per pair, then optionally another envelope.
	 */
	private static void readFilter(Reader r)
	{
		final int packed = r.u8();
		final int[] pairs = {packed >> 4, packed & 15};
		if (packed == 0)
		{
			return;
		}
		final int unity0 = r.u16();
		final int unity1 = r.u16();
		final int migrated = r.u8();

		for (int dir = 0; dir < 2; dir++)
		{
			for (int p = 0; p < pairs[dir]; p++)
			{
				r.u16();
				r.u16();
			}
		}
		for (int dir = 0; dir < 2; dir++)
		{
			for (int p = 0; p < pairs[dir]; p++)
			{
				if ((migrated & 1 << dir * 4 << p) != 0)
				{
					r.u16();
					r.u16();
				}
			}
		}
		// A trailing envelope, present only when the filter actually changes over the sound.
		if (migrated != 0 || unity1 != unity0)
		{
			final int segments = r.u8();
			for (int i = 0; i < segments; i++)
			{
				r.u16();
				r.u16();
			}
		}
	}

	/** Waveform 4 is noise, which needs state and so is generated in {@link Instrument#render}. */
	private static final int NOISE = 4;

	/** The oscillator shapes the format uses, by form number. */
	private static double wave(int form, double phase)
	{
		final double p = phase - Math.floor(phase);
		switch (form)
		{
			case 1: // square
				return p < 0.5 ? 1 : -1;
			case 2: // sine
				return Math.sin(p * 2 * Math.PI);
			case 3: // saw
				return p * 2 - 1;
			default:
				return 0;
		}
	}

	/**
	 * Render a whole sound effect to mono 16-bit PCM at {@link #SAMPLE_RATE}.
	 *
	 * @param data the raw cache bytes for this sound id.
	 * @return little-endian PCM, or null if the definition could not be read.
	 */
	static byte[] render(byte[] data)
	{
		if (data == null || data.length < 8)
		{
			return null;
		}
		try
		{
			final Reader r = new Reader(data);
			final Instrument[] instruments = new Instrument[10];
			// A leading byte per slot says whether that instrument is present.
			for (int i = 0; i < 10; i++)
			{
				if (!r.has(1))
				{
					break;
				}
				if (r.u8() != 0)
				{
					r.back();
					instruments[i] = Instrument.read(r);
				}
			}

			int length = 0;
			for (Instrument in : instruments)
			{
				if (in != null)
				{
					length = Math.max(length, in.begin + in.duration);
				}
			}
			if (length <= 0)
			{
				return null;
			}

			final int total = length * SAMPLE_RATE / 1000;
			final double[] mix = new double[total];
			for (Instrument in : instruments)
			{
				if (in == null)
				{
					continue;
				}
				final double[] voice = in.render();
				final int at = in.begin * SAMPLE_RATE / 1000;
				for (int i = 0; i < voice.length && at + i < total; i++)
				{
					mix[at + i] += voice[i];
				}
			}

			final byte[] pcm = new byte[total * 2];
			for (int i = 0; i < total; i++)
			{
				// Scaled to 16-bit here, once, rather than per oscillator - and clamped rather
				// than wrapped, so an overloud mix gets louder and then stops instead of
				// inverting into a crack.
				final int s = (int) Math.max(-32768, Math.min(32767, mix[i] * 24000));
				pcm[i * 2] = (byte) (s & 0xFF);
				pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
			}
			return pcm;
		}
		catch (Exception | Error e)
		{
			log.debug("could not render sound", e);
			return null;
		}
	}
}
