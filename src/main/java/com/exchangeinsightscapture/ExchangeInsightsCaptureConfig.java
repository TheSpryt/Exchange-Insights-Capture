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
		description = "Clip length and quality",
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
		description = "Off, Automatic (always buffering), or Manual (hotkey to arm).",
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
		description = "Total length of a saved clip.",
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
		description = "Seconds to keep recording after the event.",
		section = recordingSection,
		position = 1
	)
	@Units(Units.SECONDS)
	default int postRoll()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "captureFps",
		name = "Framerate",
		description = "Frames a second to capture. A ceiling: clips never run faster than the client "
			+ "draws. Each captured frame is read back from the GPU, so this also costs game "
			+ "framerate - 60 is affordable, 120 is not on most machines.",
		section = recordingSection,
		position = 2
	)
	default ClipFramerate captureFps()
	{
		return ClipFramerate.FPS_50;
	}

	@ConfigItem(
		keyName = "clipQuality",
		name = "Quality",
		description = "Higher looks better and takes noticeably more disk.",
		section = recordingSection,
		position = 4
	)
	default ClipQuality clipQuality()
	{
		return ClipQuality.MEDIUM;
	}

	@Range(min = 64, max = 2048)
	@ConfigItem(
		keyName = "maxBufferMb",
		name = "Memory limit (MB)",
		description = "Memory ceiling for buffered frames. Past it, the oldest are dropped.",
		section = recordingSection,
		position = 6
	)
	default int maxBufferMb()
	{
		return 512;
	}

	@ConfigItem(
		keyName = "drawCursor",
		name = "Draw cursor",
		description = "Draw a marker at the mouse position in saved clips.",
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
		description = "Manual mode: press to arm, press again to save.",
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
		description = "Manual takes stop and save automatically at this length.",
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
		description = "Automatic mode: press to save the last few seconds.",
		section = triggersSection,
		position = 2
	)
	default Keybind manualHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "onBossKill",
		name = "Boss kills",
		description = "A boss or raid kill count message.",
		section = triggersSection,
		position = 10
	)
	default boolean onBossKill()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onChestLoot",
		name = "Chest loot",
		description = "Looting a raid or reward chest.",
		section = triggersSection,
		position = 11
	)
	default boolean onChestLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onClueScroll",
		name = "Clue scroll rewards",
		description = "Opening a clue scroll casket.",
		section = triggersSection,
		position = 12
	)
	default boolean onClueScroll()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onCollectionLog",
		name = "Collection log",
		description = "New collection log item. Needs the in-game notification on.",
		section = triggersSection,
		position = 13
	)
	default boolean onCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onCombatAchievement",
		name = "Combat achievements",
		description = "Combat achievement task completed.",
		section = triggersSection,
		position = 14
	)
	default boolean onCombatAchievement()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onDeath",
		name = "Deaths",
		description = "Your character dies.",
		section = triggersSection,
		position = 15
	)
	default boolean onDeath()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onFriendDeath",
		name = "Friend deaths",
		description = "A friend or friends chat member dies nearby. Off by default - in a raid or a busy area this fires constantly, and it is other people's deaths rather than yours.",
		section = triggersSection,
		position = 16
	)
	default boolean onFriendDeath()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onClanDeath",
		name = "Clan deaths",
		description = "A clan member dies nearby. Off by default for the same reason as friend deaths - your own team dying is a normal part of a raid.",
		section = triggersSection,
		position = 17
	)
	default boolean onClanDeath()
	{
		return false;
	}

	@ConfigItem(
		keyName = "onDuel",
		name = "Duels",
		description = "A duel ends.",
		section = triggersSection,
		position = 18
	)
	default boolean onDuel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onFriendsChatKick",
		name = "Friends chat kicks",
		description = "You kick someone from your friends chat.",
		section = triggersSection,
		position = 19
	)
	default boolean onFriendsChatKick()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onKingdom",
		name = "Kingdom rewards",
		description = "Collecting your Miscellania reward.",
		section = triggersSection,
		position = 20
	)
	default boolean onKingdom()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onLeagueTask",
		name = "League tasks",
		description = "A league task is completed.",
		section = triggersSection,
		position = 21
	)
	default boolean onLeagueTask()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onLevelUp",
		name = "Levels",
		description = "Level up in any skill.",
		section = triggersSection,
		position = 22
	)
	default boolean onLevelUp()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onPet",
		name = "Pets",
		description = "You receive a pet.",
		section = triggersSection,
		position = 23
	)
	default boolean onPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onPvpKill",
		name = "PvP kills",
		description = "You defeat another player.",
		section = triggersSection,
		position = 24
	)
	default boolean onPvpKill()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onQuestComplete",
		name = "Quests",
		description = "You complete a quest.",
		section = triggersSection,
		position = 25
	)
	default boolean onQuestComplete()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onUntradeableDrop",
		name = "Untradeable drops",
		description = "An untradeable item drops.",
		section = triggersSection,
		position = 26
	)
	default boolean onUntradeableDrop()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onValuableDrop",
		name = "Valuable drops",
		description = "Loot worth more than the threshold below.",
		section = triggersSection,
		position = 27
	)
	default boolean onValuableDrop()
	{
		return true;
	}

	@ConfigItem(
		keyName = "onWildernessLootChest",
		name = "Wilderness loot chest",
		description = "Opening the wilderness loot chest.",
		section = triggersSection,
		position = 28
	)
	default boolean onWildernessLootChest()
	{
		return true;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "valuableDropThreshold",
		name = "Valuable threshold",
		description = "Minimum drop value (gp) needed to save a clip.",
		section = triggersSection,
		position = 60
	)
	default int valuableDropThreshold()
	{
		return 100000;
	}

	@ConfigItem(
		keyName = "outputDirectory",
		name = "Save folder",
		description = "Where clips are saved. Clear to restore the default folder.",
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
		description = "Chat message when a clip finishes saving.",
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
		description = "Limit how much disk the clip folder may use.",
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
		description = "Maximum size of the clip folder.",
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
		description = "Over the limit: delete the oldest clips, or just warn in chat.",
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
		description = "Show a small on-screen recording indicator.",
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
		description = "Set automatically when you link. May stay empty if another plugin holds the token.",
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
		description = "Upload saved clips to your account. Needs a linked account.",
		warning = "This feature sends your clips and IP address to a 3rd-party server not controlled or verified by RuneLite developers",
		section = accountSection,
		position = 1
	)
	default boolean uploadClips()
	{
		return false;
	}
}
