package com.instantreplay;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Compact on-screen indicator showing whether Instant Replay is armed, actively
 * recording a triggered clip, or has just finished saving one (a brief flash).
 */
class InstantReplayOverlay extends OverlayPanel
{
	private static final long SAVE_FLASH_MS = 2500;

	private static final Color ARMED_COLOR = new Color(160, 160, 160);
	private static final Color RECORDING_COLOR = new Color(220, 40, 40);
	private static final Color SAVED_COLOR = new Color(60, 200, 90);

	private static final Color SAVING_COLOR = new Color(230, 170, 40);

	private final InstantReplayConfig config;
	private final BooleanSupplier armed;
	private final BooleanSupplier recording;
	private final IntSupplier savingCount;
	private final LongSupplier lastSavedAt;

	InstantReplayOverlay(net.runelite.client.plugins.Plugin plugin, InstantReplayConfig config,
		BooleanSupplier armed, BooleanSupplier recording, IntSupplier savingCount, LongSupplier lastSavedAt)
	{
		super(plugin);
		this.config = config;
		this.armed = armed;
		this.recording = recording;
		this.savingCount = savingCount;
		this.lastSavedAt = lastSavedAt;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		final int saving = savingCount.getAsInt();
		// Stay visible while a clip is still being written even if capture has stopped -
		// encoding outlasts the recording phase by far, and that wait needs a state of its own.
		if (!config.showStatusOverlay() || (!armed.getAsBoolean() && saving == 0))
		{
			return null;
		}

		long sinceSave = System.currentTimeMillis() - lastSavedAt.getAsLong();
		boolean flashing = sinceSave >= 0 && sinceSave < SAVE_FLASH_MS;

		final Color color;
		final String status;
		if (flashing && saving == 0)
		{
			color = SAVED_COLOR;
			status = "Saved";
		}
		else if (saving > 0)
		{
			// Distinct from "Recording": the clip is captured, this is the encode/write.
			color = SAVING_COLOR;
			status = saving > 1 ? "Saving " + saving + " clips" : "Saving...";
		}
		else if (recording.getAsBoolean())
		{
			// Only a MANUAL take earns its own "Recording" state. In Automatic mode the plugin is
			// already buffering continuously, so a separate post-trigger "Recording" said nothing
			// that "Armed" had not already said - it just looked like a state change that wasn't one.
			color = RECORDING_COLOR;
			status = "Recording";
		}
		else
		{
			color = ARMED_COLOR;
			status = "Armed";
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(125, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Instant Replay")
			.color(color)
			.build());
		panelComponent.getChildren().add(LineComponent.builder()
			.left("●")
			.leftColor(color)
			.right(status)
			.rightColor(color)
			.build());

		return super.render(graphics);
	}
}
