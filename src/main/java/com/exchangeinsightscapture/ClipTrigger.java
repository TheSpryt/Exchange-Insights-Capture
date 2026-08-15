package com.exchangeinsightscapture;

/**
 * What caused a clip, and the folder it is filed under.
 *
 * <p>The names are RuneLite's, taken from its screenshot plugin rather than invented here, so a
 * player's captures folder ends up looking like their screenshots folder: the same account
 * directories, the same category names, in the same order. Anyone who already has
 * {@code .runelite/screenshots} knows where to look without being told.
 */
public enum ClipTrigger
{
	BOSS_KILLS("Boss Kills"),
	CHEST_LOOT("Chest Loot"),
	CLUE_SCROLL_REWARDS("Clue Scroll Rewards"),
	COLLECTION_LOG("Collection Log"),
	COMBAT_ACHIEVEMENTS("Combat Achievements"),
	DEATHS("Deaths"),
	DUELS("Duels"),
	FRIENDS_CHAT_KICKS("Friends Chat Kicks"),
	KINGDOM_REWARDS("Kingdom Rewards"),
	LEAGUE_TASKS("League Tasks"),
	LEVELS("Levels"),
	PETS("Pets"),
	PVP_KILLS("PvP Kills"),
	QUESTS("Quests"),
	UNTRADEABLE_DROPS("Untradeable Drops"),
	VALUABLE_DROPS("Valuable Drops"),
	WILDERNESS_LOOT_CHEST("Wilderness Loot Chest"),
	/** Not an event at all - the player pressed the hotkey. */
	MANUAL("Manual");

	private final String folder;

	ClipTrigger(String folder)
	{
		this.folder = folder;
	}

	/** The sub-folder clips of this kind are filed under. */
	String folder()
	{
		return folder;
	}

	@Override
	public String toString()
	{
		return folder;
	}
}
