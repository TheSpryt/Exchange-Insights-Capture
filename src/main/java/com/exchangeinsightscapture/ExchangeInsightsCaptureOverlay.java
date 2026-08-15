package com.exchangeinsightscapture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * A single small icon saying what the plugin is doing: a blinking red dot while capturing, a
 * save icon while a clip is being written, and a dimmed marker when nothing is being captured.
 *
 * <p>Icons rather than a text panel because this sits over the game the whole time it is armed.
 * A recording indicator has to be readable at a glance and then ignorable, which a word is not.
 */
class ExchangeInsightsCaptureOverlay extends Overlay
{
	private static final int SIZE = 16;
	private static final int GAP = 3;
	/** Full blink cycle. Slow enough to read as "recording", not fast enough to nag. */
	private static final long BLINK_MS = 1000;

	private static final Color RECORD = new Color(0xE0, 0x2A, 0x2A);
	private static final Color SAVE = new Color(0xE6, 0xAA, 0x28);
	private static final Color IDLE = new Color(0x88, 0x88, 0x88, 0x9A);

	private final ExchangeInsightsCaptureConfig config;
	private final BooleanSupplier armed;
	private final BooleanSupplier recording;
	private final IntSupplier savingCount;

	ExchangeInsightsCaptureOverlay(Plugin plugin, ExchangeInsightsCaptureConfig config,
		BooleanSupplier armed, BooleanSupplier recording, IntSupplier savingCount)
	{
		super(plugin);
		this.config = config;
		this.armed = armed;
		this.recording = recording;
		this.savingCount = savingCount;
		setPosition(OverlayPosition.TOP_RIGHT);
		// Low priority so it sits under the client's own readouts in that corner rather than
		// displacing them.
		setPriority(OverlayPriority.LOW);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showStatusOverlay())
		{
			return null;
		}

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		// Capturing and saving are separate things that routinely happen together - a clip
		// encodes while the buffer keeps rolling - so they get their own marks side by side.
		// Replacing one with the other hid the fact that recording was still live.
		final boolean saving = savingCount.getAsInt() > 0;
		final boolean live = recording.getAsBoolean() || armed.getAsBoolean();

		drawDot(graphics, live ? RECORD : IDLE, live && blinkOn());

		if (saving)
		{
			final Graphics2D beside = (Graphics2D) graphics.create();
			beside.translate(SIZE + GAP, 0);
			drawSave(beside);
			beside.dispose();
			return new Dimension(SIZE * 2 + GAP, SIZE);
		}

		return new Dimension(SIZE, SIZE);
	}

	private static boolean blinkOn()
	{
		return System.currentTimeMillis() % BLINK_MS < BLINK_MS / 2;
	}

	/** The record dot. Solid on the lit half of the blink, outlined on the dark half, so the
	 *  indicator never disappears entirely and leave you wondering if it is still running. */
	private void drawDot(Graphics2D g, Color color, boolean solid)
	{
		final int d = 10;
		final int x = (SIZE - d) / 2;
		final int y = (SIZE - d) / 2;
		g.setColor(color);
		if (solid)
		{
			g.fillOval(x, y, d, d);
		}
		else
		{
			g.setStroke(new BasicStroke(1.6f));
			g.drawOval(x, y, d, d);
		}
	}

	/** A floppy disk: body, shutter at the top, label at the bottom. */
	private void drawSave(Graphics2D g)
	{
		g.setColor(SAVE);
		g.fill(new RoundRectangle2D.Float(1.5f, 1.5f, SIZE - 3f, SIZE - 3f, 3f, 3f));

		// Shutter - the metal slider across the top of a 3.5" disk.
		g.setColor(new Color(0x2A, 0x2A, 0x2A));
		g.fillRect(5, 3, 6, 4);

		// Label panel across the bottom.
		g.setColor(new Color(0xF0, 0xF0, 0xF0));
		g.fillRect(4, 9, SIZE - 8, 5);
	}
}
