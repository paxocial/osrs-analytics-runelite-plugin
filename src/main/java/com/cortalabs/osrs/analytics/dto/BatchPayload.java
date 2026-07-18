/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Batch submission carrying multiple event categories in one request.
 * Mirrors {@code BatchPayload}: the wrapper itself carries the common
 * {@link PluginPayload} fields (the backend resolves the account from the
 * batch-level {@code rsn}), and every category list is optional.
 */
public class BatchPayload extends PluginPayload
{
	public List<SessionEvent> sessions;

	@SerializedName("xp_snapshots")
	public List<XpSnapshot> xpSnapshots;

	@SerializedName("collection_log")
	public List<CollectionLogEntry> collectionLog;

	@SerializedName("collection_pages")
	public List<CollectionPageSummary> collectionPages;

	public List<QuestStatus> quests;

	public List<DiaryProgress> diaries;

	@SerializedName("combat_achievements")
	public List<CombatAchievementProgress> combatAchievements;

	public List<EquipmentState> equipment;

	public List<LootDrop> loot;

	public List<ActivityUpdate> activity;

	public List<BankSnapshot> bank;

	@SerializedName("region_time")
	public List<RegionTimeShare> regionTime;

	public List<EfficiencyEnvelope> efficiency;

	@SerializedName("activity_time")
	public List<ActivityBreakdown> activityTime;

	@SerializedName("signal_events")
	public List<SignalEvent> signalEvents;

	@SerializedName("ge_trades")
	public List<GeTrade> geTrades;

	@SerializedName("slayer_tasks")
	public List<SlayerTaskUpdate> slayerTasks;

	@SerializedName("npc_kills")
	public List<NpcKillCounts> npcKills;

	@SerializedName("farming_state")
	public List<FarmingState> farmingState;
}
