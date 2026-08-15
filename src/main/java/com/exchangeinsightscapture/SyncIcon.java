package com.exchangeinsightscapture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import javax.swing.Icon;

/**
 * The little cloud / upload / download marks on a clip row.
 *
 * <p>Drawn rather than bundled as images: they are three simple shapes in two states each,
 * and painting them keeps the plugin jar free of art assets that would need shipping,
 * scaling and hash-verifying for the plugin hub.
 */
final class SyncIcon implements Icon
{
	enum Kind
	{
		/** Clip is on the account. */
		CLOUD,
		/** Clip is local only - it can be pushed up. */
		UPLOAD,
		/** Clip can be pulled down. */
		DOWNLOAD,
		/** Show the clip in the OS file manager. */
		REVEAL
	}

	private static final Color ACTIVE = new Color(0x7E, 0xC8, 0xE3);
	private static final Color DISABLED = new Color(0x55, 0x55, 0x55);
	/** Safely on the account. */
	private static final Color SAFE = new Color(0x4C, 0xC3, 0x8C);
	/** On this computer only - one disk failure from gone. */
	private static final Color AT_RISK = new Color(0xC8, 0x4C, 0x4C);
	/** The box the shapes below are drawn in. Scaled up at paint time rather than redrawn. */
	private static final int W = 16;
	private static final int H = 13;

	/**
	 * How much bigger the marks are painted than they are drawn.
	 *
	 * <p>Applied as a transform so every shape, and the stroke with them, grows together. Editing
	 * the coordinates instead would mean re-tuning four separate glyphs by hand and getting their
	 * weights to match again afterwards.
	 */
	private static final double SCALE = 1.5;

	private final Kind kind;
	private final boolean enabled;

	SyncIcon(Kind kind, boolean enabled)
	{
		this.kind = kind;
		this.enabled = enabled;
	}

	@Override
	public int getIconWidth()
	{
		return (int) Math.round(W * SCALE);
	}

	@Override
	public int getIconHeight()
	{
		return (int) Math.round(H * SCALE);
	}

	@Override
	public void paintIcon(Component c, Graphics g, int x, int y)
	{
		final Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.translate(x, y);
		g2.scale(SCALE, SCALE);
		g2.setColor(colour());
		// Stroke is in user space, so scaling thickens the lines along with the shapes and the
		// glyphs keep their proportions instead of looking spindly once enlarged.
		g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		switch (kind)
		{
			case CLOUD:
				cloud(g2, true);
				break;
			case UPLOAD:
				cloud(g2, false);
				arrow(g2, true);
				break;
			case REVEAL:
				reveal(g2);
				break;
			default:
				cloud(g2, false);
				arrow(g2, false);
				break;
		}
		g2.dispose();
	}

	/**
	 * The mark's colour, which carries the meaning as much as its shape does.
	 *
	 * <p>Green for a clip that is safely on the account, red for one that exists only on this
	 * computer. Backed up or not is the thing worth reading at a glance across a list of clips,
	 * and it matches how the account button in the panel above already uses the two colours.
	 * The download and reveal marks stay neutral - they are actions, not states, and colouring
	 * them would dilute the signal.
	 */
	private Color colour()
	{
		if (!enabled)
		{
			return DISABLED;
		}
		switch (kind)
		{
			case CLOUD:
				return SAFE;
			case UPLOAD:
				return AT_RISK;
			default:
				return ACTIVE;
		}
	}

	/** A pane with an arrow leaving it: the usual "open this somewhere else" mark. */
	private void reveal(Graphics2D g2)
	{
		// Open-cornered box, so the arrow reads as leaving it rather than crossing it.
		final GeneralPath box = new GeneralPath();
		box.moveTo(9, 3);
		box.lineTo(3, 3);
		box.lineTo(3, 11);
		box.lineTo(12, 11);
		box.lineTo(12, 6.5);
		g2.draw(box);

		g2.drawLine(7, 7, 12, 2);
		final GeneralPath head = new GeneralPath();
		head.moveTo(8.5, 2);
		head.lineTo(13, 2);
		head.lineTo(13, 6.5);
		g2.draw(head);
	}

	/** The cloud body. Filled when the clip is actually stored up there, outlined when it isn't. */
	private void cloud(Graphics2D g2, boolean filled)
	{
		final GeneralPath p = new GeneralPath();
		p.moveTo(4, 10);
		p.curveTo(1.5, 10, 1.5, 6.5, 4.2, 6.4);
		p.curveTo(4.4, 3.2, 9.2, 2.8, 10.2, 5.8);
		p.curveTo(13.2, 5.6, 13.8, 10, 11, 10);
		p.closePath();
		if (filled)
		{
			g2.fill(p);
		}
		else
		{
			g2.draw(p);
		}
	}

	/** A small arrow through the cloud: up for "can upload", down for "can download". */
	private void arrow(Graphics2D g2, boolean up)
	{
		final int cx = 7;
		if (up)
		{
			g2.drawLine(cx, 11, cx, 5);
			g2.drawLine(cx, 5, cx - 2, 7);
			g2.drawLine(cx, 5, cx + 2, 7);
		}
		else
		{
			g2.drawLine(cx, 5, cx, 11);
			g2.drawLine(cx, 11, cx - 2, 9);
			g2.drawLine(cx, 11, cx + 2, 9);
		}
	}
}
