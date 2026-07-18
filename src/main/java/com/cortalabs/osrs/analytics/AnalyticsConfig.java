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
 * <p>Everything is config-gated: with {@link #enabled()} off the plugin produces
 * zero network traffic. Every category is ON by default for this internal
 * deployment, including the two private categories (bank and collection log),
 * which stay clearly labelled so opting out is always one click.
 */
@ConfigGroup(AnalyticsConfig.GROUP)
public interface AnalyticsConfig extends Config
{
	String GROUP = "osrsanalytics";

	@ConfigSection(
		name = "Connection",
		description = "Backend endpoint and API key",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "Categories",
		description = "Which telemetry categories to collect",
		position = 1
	)
	String categoriesSection = "categories";

	@ConfigSection(
		name = "Private data",
		description = "Sensitive categories. On by default for this internal deployment; turn off any time.",
		position = 2
	)
	String privateSection = "private";

	@ConfigSection(
		name = "Lookup & sync",
		description = "Player lookup and RSN name-change submission",
		position = 3
	)
	String lookupSection = "lookup";

	// --- Connection ---

	@ConfigItem(
		keyName = "enabled",
		name = "Enable telemetry",
		description = "Master switch. When off, the plugin sends nothing.",
		section = connectionSection,
		position = 0
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Base URL of the local Catherby plugin API (prefix /api/v1/plugin).",
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
		description = "How often a full XP snapshot is taken while logged in.",
		section = connectionSection,
		position = 4
	)
	default int xpSnapshotMinutes()
	{
		return 5;
	}

	// --- Categories (on by default) ---

	@ConfigItem(keyName = "trackSessions", name = "Sessions", description = "Login, logout and world-hop events.", section = categoriesSection, position = 0)
	default boolean trackSessions()
	{
		return true;
	}

	@ConfigItem(keyName = "trackXp", name = "XP", description = "Periodic XP snapshots for all skills.", section = categoriesSection, position = 1)
	default boolean trackXp()
	{
		return true;
	}

	@ConfigItem(keyName = "trackQuests", name = "Quests", description = "Quest completion state changes.", section = categoriesSection, position = 2)
	default boolean trackQuests()
	{
		return true;
	}

	@ConfigItem(keyName = "trackDiaries", name = "Achievement diaries", description = "Diary tier completion for all twelve regions.", section = categoriesSection, position = 3)
	default boolean trackDiaries()
	{
		return true;
	}

	@ConfigItem(keyName = "trackCombatAchievements", name = "Combat achievements", description = "Completed-task counts per combat achievement tier.", section = categoriesSection, position = 4)
	default boolean trackCombatAchievements()
	{
		return true;
	}

	@ConfigItem(keyName = "trackEquipment", name = "Equipment & inventory", description = "Worn equipment and inventory snapshots.", section = categoriesSection, position = 5)
	default boolean trackEquipment()
	{
		return true;
	}

	@ConfigItem(keyName = "trackLoot", name = "Loot", description = "Loot drops from NPCs, chests, clues and minigames.", section = categoriesSection, position = 6)
	default boolean trackLoot()
	{
		return true;
	}

	@ConfigItem(keyName = "trackActivity", name = "Activity", description = "Coarse, heuristic activity signal (region changes).", section = categoriesSection, position = 7)
	default boolean trackActivity()
	{
		return true;
	}

	@ConfigItem(keyName = "trackRegionTimeShare", name = "Region time-share", description = "Per-session ticks spent in each map region, aggregated and sent once at session end.", section = categoriesSection, position = 8)
	default boolean trackRegionTimeShare()
	{
		return true;
	}

	@ConfigItem(keyName = "trackEfficiency", name = "Efficiency (active/idle)", description = "Per-session active vs idle time, world hops and duration, aggregated and sent once at session end.", section = categoriesSection, position = 9)
	default boolean trackEfficiency()
	{
		return true;
	}

	@ConfigItem(keyName = "trackActivityClassification", name = "Activity classification", description = "Per-session time by activity (woodcutting, mining, ...), classified from animations, aggregated and sent once at session end.", section = categoriesSection, position = 10)
	default boolean trackActivityClassification()
	{
		return true;
	}

	// --- Private data (off by default) ---

	@ConfigItem(
		keyName = "trackCollectionLog",
		name = "Collection log",
		description = "Collection log additions detected from chat. Private data; on by default, turn off any time.",
		section = privateSection,
		position = 0
	)
	default boolean trackCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackBank",
		name = "Bank contents",
		description = "Full bank snapshot (items and values). Private data; on by default, turn off any time.",
		section = privateSection,
		position = 1
	)
	default boolean trackBank()
	{
		return true;
	}

	// --- Lookup & sync ---

	@ConfigItem(
		keyName = "enableLookup",
		name = "Right-click player lookup",
		description = "Add an 'Analytics lookup' option to player right-click menus and enable the panel Lookup tab.",
		section = lookupSection,
		position = 0
	)
	default boolean enableLookup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "submitNameChanges",
		name = "Submit RSN name changes",
		description = "When your display name changes, submit the old/new name to the backend once.",
		section = lookupSection,
		position = 1
	)
	default boolean submitNameChanges()
	{
		return true;
	}
}
