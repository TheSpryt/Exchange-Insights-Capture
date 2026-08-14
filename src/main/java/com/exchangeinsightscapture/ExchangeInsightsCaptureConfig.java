package com.exchangeinsightscapture;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ExchangeInsightsCaptureConfig.GROUP)
public interface ExchangeInsightsCaptureConfig extends Config
{
	String GROUP = "exchangeinsightscapture";

	@ConfigSection(
		name = "Recording",
		description = "Clip length, framerate and quality settings",
		position = 0
	)
	String recordingSection = "recording";

	@ConfigSection(
		name = "Triggers",
		description = "Which in-game events automatically save a clip",
		position = 1
	)
	String triggersSection = "triggers";

	@ConfigSection(
		name = "Output",
		description = "Where clips are saved and how you are notified",
		position = 2
	)
	String outputSection = "output";

	@ConfigSection(
		name = "Exchange Insights",
		description = "Link this client to your Exchange Insights account and upload clips",
		position = 3
	)
	String accountSection = "account";

	// ------------------------------------------------------------------
	// Recording
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "captureMode",
		name = "Capture mode",
		description = "Off: nothing is captured. Automatic: always buffers the last few seconds so triggers can save the lead-up. Manual: idle until you press the arm hotkey, then records the whole take until you press it again. Manual costs nothing while disarmed.",
		section = recordingSection,
		position = 0
	)
	default CaptureMode captureMode()
	{
		return CaptureMode.AUTO;
	}

	@Range(min = 4, max = 120)
	@ConfigItem(
		keyName = "clipLength",
		name = "Clip length",
		description = "Total length of a saved clip, including the seconds before and after the event.",
		section = recordingSection,
		position = 0
	)
	@Units(Units.SECONDS)
	default int clipLength()
	{
		return 15;
	}

	@Range(min = 0, max = 30)
	@ConfigItem(
		keyName = "postRoll",
		name = "Post-event padding",
		description = "How many seconds to keep recording after the event fires. The rest of the clip is the lead-up to the event.",
		section = recordingSection,
		position = 1
	)
	@Units(Units.SECONDS)
	default int postRoll()
	{
		return 2;
	}

	@Range(min = 5, max = 60)
	@ConfigItem(
		keyName = "framerate",
		name = "Framerate",
		description = "Frames per second to capture. THIS IS THE MAIN PERFORMANCE SETTING: each captured frame forces the client to hand back a rendered frame, which on the GPU and 117HD renderers means a GPU readback that stalls rendering. If your in-game FPS drops, lower this first. 15 is smooth enough for clips; above 30 is expensive.",
		section = recordingSection,
		position = 2
	)
	@Units("fps")
	default int framerate()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "resolution",
		name = "Resolution",
		description = "Vertical resolution of saved clips. The client is downscaled to this height and never upscaled.",
		section = recordingSection,
		position = 3
	)
	default ResolutionMode resolution()
	{
		return ResolutionMode.P720;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(
		keyName = "quality",
		name = "JPEG buffer quality",
		description = "Quality of the in-memory frame buffer (10-100). Lower values reduce memory use at the cost of clip quality.",
		section = recordingSection,
		position = 4
	)
	default int quality()
	{
		return 85;
	}

	@ConfigItem(
		keyName = "fastSave",
		name = "Fast save (Motion JPEG)",
		description = "Write clips without re-compressing them. Saving finishes almost instantly instead of taking tens of seconds, at the cost of roughly 2-3x larger files and narrower playback support (VLC and most desktop players are fine; browsers often are not). Leave off for small, widely-playable H.264 clips.",
		section = recordingSection,
		position = 5
	)
	default boolean fastSave()
	{
		return false;
	}

	@ConfigItem(
		keyName = "drawCursor",
		name = "Draw cursor",
		description = "Draw a marker at the mouse position in saved clips. The operating system cursor is not part of captured frames, so this is rendered by the plugin.",
		section = recordingSection,
		position = 5
	)
	default boolean drawCursor()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Triggers
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "manualToggleHotkey",
		name = "Manual arm / disarm hotkey",
		description = "Manual mode only. Press once to arm and start recording; press again to stop and save the whole take. Click the box, then press the key you want to bind.",
		section = triggersSection,
		position = 0
	)
	default Keybind manualToggleHotkey()
	{
		return Keybind.NOT_SET;
	}

	@Range(min = 10, max = 1800)
	@ConfigItem(
		keyName = "maxManualLength",
		name = "Manual recording limit",
		description = "Safety cap for Manual mode: a take is stopped and saved automatically once it reaches this length, so a forgotten recording cannot fill memory. Roughly 70MB per minute at 15fps/720p.",
		section = triggersSection,
		position = 1
	)
	@Units(Units.SECONDS)
	default int maxManualLength()
	{
		return 300;
	}

	@ConfigItem(
		keyName = "manualHotkey",
		name = "Instant save hotkey",
		description = "Automatic mode only. Press to instantly save a clip of the last few seconds, like a ShadowPlay manual capture. Unset by default: click the box, then press the key you want. If it still reads 'Not set' afterwards nothing was bound and the hotkey will do nothing.",
		section = triggersSection,
		position = 2
	)
	default Keybind manualHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "onDeath",
		name = "On death",
		description = "Save a clip when your character dies.",
		section = triggersSection,
		position = 10
	)
	default boolean onDeath()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onCollectionLog",
		name = "On collection log unlock",
		description = "Save a clip when a new item is added to your collection log. Requires the in-game collection log notification to be enabled.",
		section = triggersSection,
		position = 11
	)
	default boolean onCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onLevelUp",
		name = "On level up",
		description = "Save a clip when you reach a new level in any skill.",
		section = triggersSection,
		position = 12
	)
	default boolean onLevelUp()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onValuableDrop",
		name = "On valuable drop",
		description = "Save a clip when you receive loot worth more than the value threshold below.",
		section = triggersSection,
		position = 13
	)
	default boolean onValuableDrop()
	{
		return false;
	}

	@Range(min = 1)
	@ConfigItem(
		keyName = "valuableDropThreshold",
		name = "Valuable drop value",
		description = "Minimum total Grand Exchange value of a drop (in gp) needed to save a clip.",
		section = triggersSection,
		position = 14
	)
	default int valuableDropThreshold()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "onPet",
		name = "On pet drop",
		description = "Save a clip when you receive a pet.",
		section = triggersSection,
		position = 15
	)
	default boolean onPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onQuestComplete",
		name = "On quest completion",
		description = "Save a clip when you complete a quest.",
		section = triggersSection,
		position = 16
	)
	default boolean onQuestComplete()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onCombatAchievement",
		name = "On combat task",
		description = "Save a clip when you complete a combat achievement task.",
		section = triggersSection,
		position = 17
	)
	default boolean onCombatAchievement()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Output
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "outputDirectory",
		name = "Save folder",
		description = "Folder to save clips to. Leave blank to use the default RuneLite 'captures' folder, alongside 'screenshots'.",
		section = outputSection,
		position = 0
	)
	default String outputDirectory()
	{
		return "";
	}

	@ConfigItem(
		keyName = "notify",
		name = "Chat message on save",
		description = "Print a game chat message when a clip has finished saving.",
		section = outputSection,
		position = 1
	)
	default boolean notifyOnSave()
	{
		return true;
	}

	@ConfigItem(
		keyName = "limitLocalStorage",
		name = "Limit storage size",
		description = "Cap how much disk the clip folder is allowed to use. Without this, clips accumulate until you clear them out yourself.",
		section = outputSection,
		position = 3
	)
	default boolean limitLocalStorage()
	{
		return true;
	}

	@Range(min = 1, max = 500)
	@ConfigItem(
		keyName = "localStorageLimitGb",
		name = "Size limit (GB)",
		description = "Maximum total size of the clip folder. Only files this plugin writes are counted or removed.",
		section = outputSection,
		position = 4
	)
	default int localStorageLimitGb()
	{
		return 10;
	}

	@ConfigItem(
		keyName = "storageLimitMode",
		name = "Storage limit mode",
		description = "Auto delete: remove the oldest clips to make room for new ones. Warn only: keep everything and warn in chat when over the limit.",
		section = outputSection,
		position = 5
	)
	default StorageLimitMode storageLimitMode()
	{
		return StorageLimitMode.AUTO_DELETE;
	}

	@ConfigItem(
		keyName = "showStatusOverlay",
		name = "Show status overlay",
		description = "Show a small on-screen indicator when Exchange Insights Capture is armed, recording a clip, or has just saved one.",
		section = outputSection,
		position = 2
	)
	default boolean showStatusOverlay()
	{
		return true;
	}

	// ------------------------------------------------------------------
	// Exchange Insights
	// ------------------------------------------------------------------

	@ConfigItem(
		keyName = "eiAccountToken",
		name = "Account token",
		description = "Your Exchange Insights account token, used to upload clips. Get it free at exchange-insights.gg (Account -> RuneLite plugin). If you leave this empty and either the Exchange Insights or Bank Templates plugin has a token configured, that one is used automatically. Treat it like a password.",
		section = accountSection,
		position = 0,
		secret = true
	)
	default String eiAccountToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "uploadClips",
		name = "Upload clips",
		description = "Upload each saved clip to your Exchange Insights account. Requires a linked account token. Clips are always written to disk first, so a failed upload never loses a recording.",
		section = accountSection,
		position = 1
	)
	default boolean uploadClips()
	{
		return false;
	}
}
