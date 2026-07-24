/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

/**
 * Configuration for the Catherby Analytics telemetry plugin.
 *
 * <p><b>Privacy-first, opt-in only.</b> Every telemetry category is OFF by
 * default, and the master {@link #enabled()} switch is OFF by default too, so a
 * fresh install produces zero network traffic until the user explicitly turns it
 * on. Enabling the master switch shows the required third-party-server warning;
 * the user then chooses individual categories one at a time. Each category names
 * exactly what data leaves the client — nothing is bundled inside an innocuous
 * option, and nothing is pre-checked. Data is sent only to the Catherby backend
 * URL the user configures, which is not controlled or verified by the RuneLite
 * developers.
 */
@ConfigGroup(AnalyticsConfig.GROUP)
public interface AnalyticsConfig extends Config
{
	String GROUP = "osrsanalytics";

	/**
	 * Shown when the master switch is toggled on. This is the RuneLite-required
	 * third-party-server warning (Plugin Hub policy): a plugin that sends data to
	 * a third-party server must warn, on the enabling option, what is being sent.
	 */
	String THIRD_PARTY_WARNING =
		"This turns on Catherby Analytics, which sends your Old School RuneScape "
		+ "gameplay data to a third-party server.\n\n"
		+ "Only the categories you enable below are collected, and everything is "
		+ "sent to the Catherby backend at the API base URL you configure. That "
		+ "server is run by you / Corta Labs and is NOT controlled or verified by "
		+ "the RuneLite developers.\n\n"
		+ "Nothing is sent while this switch is off. Continue?";

	@ConfigSection(
		name = "Connection",
		description = "Backend endpoint and API key. Everything is OFF by default: the plugin sends "
			+ "nothing until you enable it and choose categories. Data goes to the third-party "
			+ "Catherby backend at the URL you set, which is not controlled or verified by RuneLite.",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Categories",
		description = "Gameplay telemetry categories. All OFF by default — turn on only the data you want sent.",
		position = 1
	)
	String categoriesSection = "categories";

	@ConfigSection(
		name = "Private data",
		description = "Sensitive categories (your bank and collection log). OFF by default; turn on "
			+ "only if you want this data sent.",
		position = 2
	)
	String privateSection = "private";

	@ConfigSection(
		name = "Lookup & sync",
		description = "Player lookup and RSN name-change submission. OFF by default.",
		position = 3
	)
	String lookupSection = "lookup";

	// --- Connection ---

	@ConfigItem(
		keyName = "enabled",
		name = "Enable telemetry",
		description = "Master switch, OFF by default. When off, the plugin sends nothing. When on, only "
			+ "the categories you enable below are collected and sent to your configured Catherby backend.",
		warning = THIRD_PARTY_WARNING,
		section = connectionSection,
		position = 0
	)
	default boolean enabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Base URL of the Catherby plugin API you send your data to (prefix /api/v1/plugin). "
			+ "This third-party server is not controlled or verified by RuneLite.",
		section = connectionSection,
		position = 1
	)
	default String apiBaseUrl()
	{
		return "http://localhost:8000/api/v1/plugin";
	}

	@ConfigItem(
		keyName = "apiKey",
		name = "API key",
		description = "Plugin API token; sent as the X-API-Key header. Never logged.",
		secret = true,
		section = connectionSection,
		position = 2
	)
	default String apiKey()
	{
		return "";
	}

	@Range(min = 5, max = 120)
	@ConfigItem(
		keyName = "flushIntervalSeconds",
		name = "Flush interval (seconds)",
		description = "How often queued events are sent as a batch (kept under the backend rate limit).",
		section = connectionSection,
		position = 3
	)
	default int flushIntervalSeconds()
	{
		return 15;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "xpSnapshotMinutes",
		name = "XP snapshot interval (minutes)",
		description = "How often a full XP snapshot is taken while logged in (only when XP tracking is on).",
		section = connectionSection,
		position = 4
	)
	default int xpSnapshotMinutes()
	{
		return 5;
	}

	// --- Categories (all OFF by default; each names exactly what is sent) ---

	@ConfigItem(keyName = "trackSessions", name = "Sessions", description = "Sends your login, logout and world-hop events (time and world).", section = categoriesSection, position = 0)
	default boolean trackSessions()
	{
		return false;
	}

	@ConfigItem(keyName = "trackXp", name = "XP", description = "Sends periodic snapshots of your XP and level in every skill.", section = categoriesSection, position = 1)
	default boolean trackXp()
	{
		return false;
	}

	@ConfigItem(keyName = "trackQuests", name = "Quests", description = "Sends your quest completion state whenever it changes.", section = categoriesSection, position = 2)
	default boolean trackQuests()
	{
		return false;
	}

	@ConfigItem(keyName = "trackDiaries", name = "Achievement diaries", description = "Sends your achievement-diary tier completion for all twelve regions.", section = categoriesSection, position = 3)
	default boolean trackDiaries()
	{
		return false;
	}

	@ConfigItem(keyName = "trackCombatAchievements", name = "Combat achievements", description = "Sends your completed combat-achievement task counts per tier.", section = categoriesSection, position = 4)
	default boolean trackCombatAchievements()
	{
		return false;
	}

	@ConfigItem(keyName = "trackEquipment", name = "Equipment & inventory", description = "Sends snapshots of your worn equipment and inventory contents.", section = categoriesSection, position = 5)
	default boolean trackEquipment()
	{
		return false;
	}

	@ConfigItem(keyName = "trackLoot", name = "Loot", description = "Sends the items you receive as loot from NPCs, chests, clues and minigames.", section = categoriesSection, position = 6)
	default boolean trackLoot()
	{
		return false;
	}

	@ConfigItem(keyName = "trackActivity", name = "Activity", description = "Sends a coarse activity signal derived from the map region you are in.", section = categoriesSection, position = 7)
	default boolean trackActivity()
	{
		return false;
	}

	@ConfigItem(keyName = "trackRegionTimeShare", name = "Region time-share", description = "Sends how many ticks you spent in each map region this session (aggregated, once at session end).", section = categoriesSection, position = 8)
	default boolean trackRegionTimeShare()
	{
		return false;
	}

	@ConfigItem(keyName = "trackEfficiency", name = "Efficiency (active/idle)", description = "Sends your active-vs-idle time, world hops and session length (aggregated, once at session end).", section = categoriesSection, position = 9)
	default boolean trackEfficiency()
	{
		return false;
	}

	@ConfigItem(keyName = "trackActivityClassification", name = "Activity classification", description = "Sends how long you spent on each activity (woodcutting, mining, ...), inferred from animations (aggregated, once at session end).", section = categoriesSection, position = 10)
	default boolean trackActivityClassification()
	{
		return false;
	}

	@ConfigItem(keyName = "trackSignalEvents", name = "Signal events", description = "Sends milestone lines read from your chat: level-ups, pet drops, clue completions, per-kill boss kill-counts, diary completions.", section = categoriesSection, position = 11)
	default boolean trackSignalEvents()
	{
		return false;
	}

	@ConfigItem(keyName = "trackGeTrades", name = "GE trades", description = "Sends your completed Grand Exchange trades (item, quantity, price, time).", section = categoriesSection, position = 12)
	default boolean trackGeTrades()
	{
		return false;
	}

	@ConfigItem(keyName = "trackSlayerTasks", name = "Slayer tasks", description = "Sends your Slayer task assignments and completions (monster, count, location, streak, points).", section = categoriesSection, position = 13)
	default boolean trackSlayerTasks()
	{
		return false;
	}

	@ConfigItem(keyName = "trackNpcKills", name = "NPC kills", description = "Sends your per-NPC kill counts for the session (aggregated, once at session end).", section = categoriesSection, position = 14)
	default boolean trackNpcKills()
	{
		return false;
	}

	@ConfigItem(keyName = "trackFarmingState", name = "Farming patches", description = "Sends your farming patch state changes (planted, grown, diseased, dead).", section = categoriesSection, position = 15)
	default boolean trackFarmingState()
	{
		return false;
	}

	@ConfigItem(keyName = "trackLivePosition", name = "Live position", description = "Sends your current map tile (region, x, y, plane) once per batch while you are logged in, so the dashboard can show where you are.", section = categoriesSection, position = 16)
	default boolean trackLivePosition()
	{
		return false;
	}

	// --- Private data (OFF by default) ---

	@ConfigItem(
		keyName = "trackCollectionLog",
		name = "Collection log",
		description = "Private data. Sends collection-log entries you unlock, detected live from your chat, plus full pages whenever you open the collection log. Live detection needs the game setting 'Collection log - New addition notification' (chat) turned ON.",
		section = privateSection,
		position = 0
	)
	default boolean trackCollectionLog()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackBank",
		name = "Bank contents",
		description = "Private data. Sends a full snapshot of your bank — every item and its value.",
		section = privateSection,
		position = 1
	)
	default boolean trackBank()
	{
		return false;
	}

	// --- Lookup & sync (OFF by default) ---

	@ConfigItem(
		keyName = "enableLookup",
		name = "Right-click player lookup",
		description = "Adds an 'Analytics lookup' option to player right-click menus and enables the panel "
			+ "Lookup tab. Sends the player name you look up to your configured backend.",
		section = lookupSection,
		position = 0
	)
	default boolean enableLookup()
	{
		return false;
	}

	@ConfigItem(
		keyName = "submitNameChanges",
		name = "Submit RSN name changes",
		description = "When your display name changes, sends your old and new name to the backend once so "
			+ "your history stays linked.",
		section = lookupSection,
		position = 1
	)
	default boolean submitNameChanges()
	{
		return false;
	}
}
