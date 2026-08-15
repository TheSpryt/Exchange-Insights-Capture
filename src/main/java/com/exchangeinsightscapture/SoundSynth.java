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
 * <p>NOT FINISHED, and not wired to anything. The parse is verified - a coin tinkle decomposes
 * into three staggered voices at 0, 200 and 340ms, and a door into one 150ms voice, which a wrong
 * parser does not produce. The synthesis is a reconstruction from field ordering and is known to
 * be wrong.
 *
 * <p>What was measured, for whoever picks this up: the UI beep (2266) renders correctly, and it is
 * the one sound where this model has nothing to get wrong - its oscillator pitch is 0 and its
 * pitch comes entirely from the envelope. The door (62) and the coin tinkle (3924) both have an
 * oscillator pitch of 120 and both come out wrong, so how that field combines with the envelope is
 * the missing piece. The tinkle's pitch envelope is 0 to 0, so its pitch cannot come from the
 * envelope at all - something unmodelled supplies it, and this code only makes a noise there
 * because of the 20Hz floor below.
 *
 * <p>The filter is deliberately not implemented yet. It shapes the timbre, and leaving it out
 * makes a sound duller than the real thing, but everything that decides whether a sound is
 * RECOGNISABLE - its oscillators, pitch and envelope - is here. If a door does not sound like a
 * door without the filter, the filter was never going to save it.
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
		 */
		double[] render()
		{
			final int samples = Math.max(1, duration * SAMPLE_RATE / 1000);
			final double[] out = new double[samples];

			// The envelope's start and end bound the pitch in the format's own units; 128 units to
			// the semitone against a middle reference is what makes a door land near 80Hz and a
			// UI beep near 350Hz, which is where they audibly belong.
			for (int osc = 0; osc < oscillatorVolume.length; osc++)
			{
				final int oscVolume = oscillatorVolume[osc];
				if (oscVolume == 0)
				{
					continue;
				}
				final double detune = Math.pow(2, oscillatorPitch[osc] / 1536.0);
				final int delay = oscillatorDelay[osc] * SAMPLE_RATE / 1000;
				double phase = 0;

				for (int i = delay; i < samples; i++)
				{
					final double t = (double) i / samples;
					final double pitchAt = pitch.start + (pitch.end - pitch.start) * pitch.at(t);
					final double hz = Math.max(20, Math.min(SAMPLE_RATE / 2.0, pitchAt * detune));
					phase += hz / SAMPLE_RATE;

					// Volume is a 0-100 level shaped by its envelope, and the oscillator's own
					// volume is a share of that rather than a second multiplier in the same units.
					final double level = (volume.start + (volume.end - volume.start) * volume.at(t)) / 100.0;
					out[i] += wave(pitch.form, phase) * level * volume.at(t) * (oscVolume / 100.0);
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
			case 4: // noise, held for a whole cycle so it has pitch rather than being hiss
				return ((int) (phase * 977) % 2 == 0) ? 1 : -1;
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
