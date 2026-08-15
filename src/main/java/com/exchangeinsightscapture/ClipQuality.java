package com.exchangeinsightscapture;

/**
 * How hard the encoder tries, as a quantiser.
 *
 * <p>Lower quantiser means better picture and a bigger file. The values are not evenly spaced,
 * because quality is not: measured on real captured footage, error per pixel sits around 3.7 at
 * QP 13 and 8.9 at 16, then falls off a cliff to 14.1 by QP 19 and barely moves after that. So the
 * useful range is narrow and the presets cluster around that knee rather than spreading evenly.
 *
 * <p>Every preset is a step sharper than it first shipped. The old spread put Medium at 19, on the
 * wrong side of that cliff, so the default looked worse than the encoder was capable of.
 *
 * <p>Public, and not by accident: RuneLite implements the config interface with a dynamic proxy,
 * and a proxy cannot reach a package-private type. Declaring this package-private compiled fine
 * and then threw IllegalAccessError from the encoder thread the first time a clip was saved.
 *
 * <p>Sizes depend far more on what is happening on screen than on this setting - a busy raid costs
 * multiples of a bank stand at the same quantiser - so the presets are named for how they look
 * rather than promising a megabyte figure they cannot honour. For real reference points at
 * 1310x720 and ~80fps, QP 15 - now HIGH - produced 75MB on quiet footage and 91MB on a raid, for
 * fifteen seconds. Sizes scale with what is happening on screen far more than with this setting.
 */
public enum ClipQuality
{
	/** Smallest files, softest picture. For long sessions or a small storage budget. */
	LOW(20),
	/** The default, now sitting just above the knee rather than below it. */
	MEDIUM(17),
	/** At the knee, where extra bits stop buying visible quality. */
	HIGH(15),
	/** Past the knee. Noticeably bigger files for a difference few will spot. */
	ULTRA(13);

	private final int quantiser;

	ClipQuality(int quantiser)
	{
		this.quantiser = quantiser;
	}

	int quantiser()
	{
		return quantiser;
	}

	@Override
	public String toString()
	{
		return name().charAt(0) + name().substring(1).toLowerCase();
	}
}
