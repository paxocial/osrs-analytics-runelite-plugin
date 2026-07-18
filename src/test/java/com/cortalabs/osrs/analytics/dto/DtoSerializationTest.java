/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Golden tests asserting that each DTO serializes to the exact JSON shape the
 * backend pydantic contract requires (field names, enum string values, nesting).
 * Contract source: catherby {@code src/catherby/api/schemas/plugin.py}.
 */
public class DtoSerializationTest
{
	private static final Gson GSON = new Gson();

	private static final List<String> REQUIRED_SKILLS = Arrays.asList(
		"attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction", "sailing");

	@SuppressWarnings("deprecation")
	private static JsonObject json(Object value)
	{
		return new JsonParser().parse(GSON.toJson(value)).getAsJsonObject();
	}

	@Test
	public void sessionEventUsesContractFields()
	{
		SessionEvent event = new SessionEvent();
		event.rsn = "Zezima";
		event.world = 330;
		event.timestamp = "2026-07-16T18:41:02.123Z";
		event.pluginVersion = "1.0.0";
		event.sessionId = "sid-1";
		event.event = SessionEvent.EventType.LOGIN;
		event.durationSeconds = null;

		JsonObject o = json(event);
		assertEquals("Zezima", o.get("rsn").getAsString());
		assertEquals(330, o.get("world").getAsInt());
		assertEquals("2026-07-16T18:41:02.123Z", o.get("timestamp").getAsString());
		assertEquals("1.0.0", o.get("plugin_version").getAsString());
		assertEquals("sid-1", o.get("session_id").getAsString());
		assertEquals("login", o.get("event").getAsString());
		// null optional must be absent, not null.
		assertFalse(o.has("duration_seconds"));
	}

	@Test
	public void sessionEnumValuesMatchContract()
	{
		assertEquals("\"login\"", GSON.toJson(SessionEvent.EventType.LOGIN));
		assertEquals("\"logout\"", GSON.toJson(SessionEvent.EventType.LOGOUT));
		assertEquals("\"world_hop\"", GSON.toJson(SessionEvent.EventType.WORLD_HOP));
	}

	@Test
	public void logoutCarriesDuration()
	{
		SessionEvent event = new SessionEvent();
		event.rsn = "Zezima";
		event.pluginVersion = "1.0.0";
		event.event = SessionEvent.EventType.LOGOUT;
		event.durationSeconds = 3600;

		JsonObject o = json(event);
		assertEquals("logout", o.get("event").getAsString());
		assertEquals(3600, o.get("duration_seconds").getAsInt());
	}

	@Test
	public void xpSnapshotContainsAllTwentyFourSkills()
	{
		Map<String, Integer> skills = new LinkedHashMap<>();
		for (String s : REQUIRED_SKILLS)
		{
			skills.put(s, 100);
		}
		XpSnapshot snap = new XpSnapshot();
		snap.rsn = "Zezima";
		snap.pluginVersion = "1.0.0";
		snap.skills = skills;

		JsonObject o = json(snap);
		JsonObject skillsJson = o.getAsJsonObject("skills");
		assertEquals(24, skillsJson.entrySet().size());
		for (String s : REQUIRED_SKILLS)
		{
			assertTrue("missing skill " + s, skillsJson.has(s));
		}
		assertTrue("sailing must be present", skillsJson.has("sailing"));
	}

	@Test
	public void collectionLogEntryUsesContractFields()
	{
		CollectionLogEntry entry = new CollectionLogEntry();
		entry.rsn = "Zezima";
		entry.pluginVersion = "1.0.0";
		entry.itemId = 11995;
		entry.itemName = "Pet chaos elemental";
		entry.quantity = 1;
		entry.source = "Collection Log";
		entry.captureProvenance = CollectionLogEntry.PROVENANCE_CHAT_OBSERVED;
		entry.obtainedAt = "2026-07-16T18:41:02.123Z";

		JsonObject o = json(entry);
		assertEquals(11995, o.get("item_id").getAsInt());
		assertEquals("Pet chaos elemental", o.get("item_name").getAsString());
		assertEquals(1, o.get("quantity").getAsInt());
		assertEquals("Collection Log", o.get("source").getAsString());
		assertEquals("chat_observed", o.get("capture_provenance").getAsString());
		assertEquals("2026-07-16T18:41:02.123Z", o.get("obtained_at").getAsString());
	}

	@Test
	public void collectionLogEntryOmitsObtainedAtWhenWalkInferred()
	{
		// The honest wire shape for an item whose acquisition moment is unknown:
		// obtained_at is ABSENT, not null, not 0, not an epoch. Gson drops nulls,
		// and the backend contract treats the missing field as "unknown".
		CollectionLogEntry entry = new CollectionLogEntry();
		entry.rsn = "Zezima";
		entry.pluginVersion = "1.0.0";
		entry.itemId = 11832;
		entry.itemName = "Bandos chestplate";
		entry.quantity = 1;
		entry.source = "General Graardor";
		entry.captureProvenance = CollectionLogEntry.PROVENANCE_WALK_INFERRED;
		entry.obtainedAt = null;

		JsonObject o = json(entry);
		assertEquals("walk_inferred", o.get("capture_provenance").getAsString());
		assertFalse("walk_inferred must not put a date on the wire at all", o.has("obtained_at"));
	}

	@Test
	public void collectionLogProvenanceValuesMatchTheBackendContract()
	{
		// These two strings are the wire contract shared with the pydantic Literal
		// in catherby src/catherby/api/schemas/plugin.py; drifting either side
		// silently reclassifies every row.
		assertEquals("chat_observed", CollectionLogEntry.PROVENANCE_CHAT_OBSERVED);
		assertEquals("walk_inferred", CollectionLogEntry.PROVENANCE_WALK_INFERRED);
	}

	@Test
	public void collectionPageSummaryUsesContractFields()
	{
		CollectionPageSummary page = new CollectionPageSummary();
		page.rsn = "Zezima";
		page.world = 330;
		page.timestamp = "2026-07-16T18:41:02.123Z";
		page.pluginVersion = "1.0.0";
		page.category = "General Graardor";
		page.obtainedCount = 5;
		page.totalSlots = 12;
		page.killCounts = Arrays.asList(
			new CollectionPageSummary.KillCount("General Graardor kills", 1234),
			new CollectionPageSummary.KillCount("Kills", 10));

		JsonObject o = json(page);
		assertEquals("General Graardor", o.get("category").getAsString());
		assertEquals(5, o.get("obtained_count").getAsInt());
		assertEquals(12, o.get("total_slots").getAsInt());
		// timestamp is the observation time, carried by the base payload.
		assertEquals("2026-07-16T18:41:02.123Z", o.get("timestamp").getAsString());
		JsonArray kcs = o.getAsJsonArray("kill_counts");
		assertEquals(2, kcs.size());
		JsonObject first = kcs.get(0).getAsJsonObject();
		assertEquals("General Graardor kills", first.get("name").getAsString());
		assertEquals(1234, first.get("count").getAsInt());
	}

	@Test
	public void collectionPageSummaryOmitsNullKillCounts()
	{
		// A page with no kill-count lines omits the field entirely (not []),
		// matching the "absent means none" wire semantics.
		CollectionPageSummary page = new CollectionPageSummary();
		page.rsn = "Zezima";
		page.pluginVersion = "1.0.0";
		page.category = "Clue Scrolls (Beginner)";
		page.obtainedCount = 0;
		page.totalSlots = 20;
		page.killCounts = null;

		JsonObject o = json(page);
		assertFalse(o.has("kill_counts"));
		assertEquals(0, o.get("obtained_count").getAsInt());
		assertEquals(20, o.get("total_slots").getAsInt());
	}

	@Test
	public void batchCarriesCollectionPages()
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = "Zezima";
		batch.pluginVersion = "1.0.0";

		CollectionPageSummary page = new CollectionPageSummary();
		page.rsn = "Zezima";
		page.pluginVersion = "1.0.0";
		page.category = "General Graardor";
		page.obtainedCount = 1;
		page.totalSlots = 12;
		batch.collectionPages = Arrays.asList(page);

		JsonObject o = json(batch);
		assertTrue("batch must use collection_pages", o.has("collection_pages"));
		assertEquals(1, o.getAsJsonArray("collection_pages").size());
	}

	@Test
	public void questStatusUsesContractFields()
	{
		QuestStatus quest = new QuestStatus();
		quest.rsn = "Zezima";
		quest.pluginVersion = "1.0.0";
		quest.questName = "Cook's Assistant";
		quest.state = QuestStatus.State.IN_PROGRESS;
		quest.questType = "quest";
		quest.questPoints = 225;

		JsonObject o = json(quest);
		assertEquals("Cook's Assistant", o.get("quest_name").getAsString());
		assertEquals("in_progress", o.get("state").getAsString());
		assertEquals("quest", o.get("quest_type").getAsString());
		assertEquals(225, o.get("quest_points").getAsInt());
	}

	@Test
	public void questStatusOmitsNullTypeAndPoints()
	{
		// Pre-backend-delta compatibility: the additive fields must be absent
		// (not null) when unset so older payload shapes remain byte-identical.
		QuestStatus quest = new QuestStatus();
		quest.rsn = "Zezima";
		quest.pluginVersion = "1.0.0";
		quest.questName = "Cook's Assistant";
		quest.state = QuestStatus.State.COMPLETE;

		JsonObject o = json(quest);
		assertFalse(o.has("quest_type"));
		assertFalse(o.has("quest_points"));
	}

	@Test
	public void questEnumValuesMatchContract()
	{
		assertEquals("\"not_started\"", GSON.toJson(QuestStatus.State.NOT_STARTED));
		assertEquals("\"in_progress\"", GSON.toJson(QuestStatus.State.IN_PROGRESS));
		assertEquals("\"complete\"", GSON.toJson(QuestStatus.State.COMPLETE));
	}

	@Test
	public void diaryProgressSerializesAllTierBooleans()
	{
		DiaryProgress diary = new DiaryProgress();
		diary.rsn = "Zezima";
		diary.pluginVersion = "1.0.0";
		diary.region = "Varrock";
		diary.easy = true;
		diary.medium = true;
		diary.hard = false;
		diary.elite = false;

		JsonObject o = json(diary);
		assertEquals("Varrock", o.get("region").getAsString());
		assertTrue(o.get("easy").getAsBoolean());
		assertTrue(o.get("medium").getAsBoolean());
		assertFalse(o.get("hard").getAsBoolean());
		assertFalse(o.get("elite").getAsBoolean());
	}

	@Test
	public void combatAchievementProgressUsesContractFields()
	{
		Map<String, Integer> tiers = new LinkedHashMap<>();
		tiers.put("easy", 5);
		tiers.put("medium", 3);
		CombatAchievementProgress ca = new CombatAchievementProgress();
		ca.rsn = "Zezima";
		ca.pluginVersion = "1.0.0";
		ca.tierProgress = tiers;
		ca.completedTasks = Arrays.asList("Task A", "Task B");

		JsonObject o = json(ca);
		JsonObject tp = o.getAsJsonObject("tier_progress");
		assertEquals(5, tp.get("easy").getAsInt());
		assertEquals(3, tp.get("medium").getAsInt());
		JsonArray tasks = o.getAsJsonArray("completed_tasks");
		assertEquals(2, tasks.size());
		assertEquals("Task A", tasks.get(0).getAsString());
	}

	@Test
	public void equipmentStateSlotsMapToIntIds()
	{
		Map<String, Integer> equipment = new LinkedHashMap<>();
		equipment.put("weapon", 4151);
		equipment.put("shield", 8850);
		EquipmentState eq = new EquipmentState();
		eq.rsn = "Zezima";
		eq.pluginVersion = "1.0.0";
		eq.equipment = equipment;
		eq.inventory = Arrays.asList(new ItemEntry(995, 1000));

		JsonObject o = json(eq);
		JsonObject equip = o.getAsJsonObject("equipment");
		assertEquals(4151, equip.get("weapon").getAsInt());
		assertEquals(8850, equip.get("shield").getAsInt());
		JsonArray inv = o.getAsJsonArray("inventory");
		JsonObject item = inv.get(0).getAsJsonObject();
		assertEquals(995, item.get("item_id").getAsInt());
		assertEquals(1000, item.get("quantity").getAsInt());
		// inventory items must NOT carry a value key.
		assertFalse(item.has("value"));
	}

	@Test
	public void lootDropUsesContractFields()
	{
		LootDrop loot = new LootDrop();
		loot.rsn = "Zezima";
		loot.pluginVersion = "1.0.0";
		loot.itemId = 11832;
		loot.itemName = "Bandos chestplate";
		loot.quantity = 1;
		loot.geValue = 20_000_000L;
		loot.source = "General Graardor";
		loot.sourceType = LootDrop.SourceType.BOSS;

		JsonObject o = json(loot);
		assertEquals(11832, o.get("item_id").getAsInt());
		assertEquals("Bandos chestplate", o.get("item_name").getAsString());
		assertEquals(1, o.get("quantity").getAsInt());
		assertEquals(20_000_000L, o.get("ge_value").getAsLong());
		assertEquals("General Graardor", o.get("source").getAsString());
		assertEquals("boss", o.get("source_type").getAsString());
	}

	@Test
	public void lootSourceEnumValuesMatchContract()
	{
		assertEquals("\"npc\"", GSON.toJson(LootDrop.SourceType.NPC));
		assertEquals("\"boss\"", GSON.toJson(LootDrop.SourceType.BOSS));
		assertEquals("\"chest\"", GSON.toJson(LootDrop.SourceType.CHEST));
		assertEquals("\"clue\"", GSON.toJson(LootDrop.SourceType.CLUE));
		assertEquals("\"minigame\"", GSON.toJson(LootDrop.SourceType.MINIGAME));
		assertEquals("\"other\"", GSON.toJson(LootDrop.SourceType.OTHER));
	}

	@Test
	public void activityUpdateOmitsNullDetail()
	{
		ActivityUpdate activity = new ActivityUpdate();
		activity.rsn = "Zezima";
		activity.pluginVersion = "1.0.0";
		activity.activity = "region_change";
		activity.detail = null;

		JsonObject o = json(activity);
		assertEquals("region_change", o.get("activity").getAsString());
		assertFalse(o.has("detail"));
	}

	@Test
	public void bankSnapshotUsesContractFields()
	{
		BankSnapshot bank = new BankSnapshot();
		bank.rsn = "Zezima";
		bank.pluginVersion = "1.0.0";
		bank.items = Arrays.asList(new ItemEntry(995, 1_000_000, 1_000_000L));
		bank.totalValue = 5_000_000_000L; // exceeds 32-bit range
		bank.valuationMethod = "ge_then_ha_v1";

		JsonObject o = json(bank);
		JsonArray items = o.getAsJsonArray("items");
		JsonObject item = items.get(0).getAsJsonObject();
		assertEquals(995, item.get("item_id").getAsInt());
		assertEquals(1_000_000, item.get("quantity").getAsInt());
		assertEquals(1_000_000L, item.get("value").getAsLong());
		assertEquals(5_000_000_000L, o.get("total_value").getAsLong());
		assertEquals("ge_then_ha_v1", o.get("valuation_method").getAsString());
	}

	@Test
	public void bankSnapshotOmitsNullValuationMethod()
	{
		BankSnapshot bank = new BankSnapshot();
		bank.rsn = "Zezima";
		bank.pluginVersion = "1.0.0";
		bank.items = Arrays.asList(new ItemEntry(995, 1, 1L));
		bank.totalValue = 1L;

		JsonObject o = json(bank);
		assertFalse(o.has("valuation_method"));
	}

	@Test
	public void batchPayloadUsesContractListNames()
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = "Zezima";
		batch.world = 330;
		batch.pluginVersion = "1.0.0";

		XpSnapshot snap = new XpSnapshot();
		snap.rsn = "Zezima";
		snap.pluginVersion = "1.0.0";
		Map<String, Integer> skills = new LinkedHashMap<>();
		for (String s : REQUIRED_SKILLS)
		{
			skills.put(s, 1);
		}
		snap.skills = skills;
		batch.xpSnapshots = Arrays.asList(snap);

		CollectionLogEntry cl = new CollectionLogEntry();
		cl.rsn = "Zezima";
		cl.pluginVersion = "1.0.0";
		cl.itemId = 11995;
		cl.itemName = "Pet";
		cl.quantity = 1;
		cl.source = "Collection Log";
		cl.captureProvenance = CollectionLogEntry.PROVENANCE_CHAT_OBSERVED;
		cl.obtainedAt = "2026-07-16T18:41:02Z";
		batch.collectionLog = Arrays.asList(cl);

		JsonObject o = json(batch);
		assertEquals("Zezima", o.get("rsn").getAsString());
		assertEquals("1.0.0", o.get("plugin_version").getAsString());
		assertTrue("batch must use xp_snapshots", o.has("xp_snapshots"));
		assertTrue("batch must use collection_log", o.has("collection_log"));
		assertEquals(1, o.getAsJsonArray("xp_snapshots").size());
		// nested xp snapshot keeps its own contract skills object
		JsonObject nested = o.getAsJsonArray("xp_snapshots").get(0).getAsJsonObject();
		assertEquals(24, nested.getAsJsonObject("skills").entrySet().size());
	}

	@Test
	public void batchOmitsEmptyCategories()
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = "Zezima";
		batch.pluginVersion = "1.0.0";

		JsonObject o = json(batch);
		// Unset category lists are null -> omitted, matching "submit only what changed".
		assertFalse(o.has("sessions"));
		assertFalse(o.has("loot"));
		assertFalse(o.has("bank"));
	}

	// --- FP-A4 Wave-A aggregate lanes (region time-share, efficiency, activity) ---

	@Test
	public void regionTimeShareUsesContractFields()
	{
		RegionTimeShare region = new RegionTimeShare();
		region.rsn = "Zezima";
		region.pluginVersion = "1.0.0";
		region.sessionId = "sid-1";
		region.regions = Arrays.asList(
			new RegionTimeShare.RegionTick(12850, 100),
			new RegionTimeShare.RegionTick(12851, 40));

		JsonObject o = json(region);
		assertEquals("Zezima", o.get("rsn").getAsString());
		assertEquals("1.0.0", o.get("plugin_version").getAsString());
		assertEquals("sid-1", o.get("session_id").getAsString());
		JsonArray regions = o.getAsJsonArray("regions");
		assertEquals(2, regions.size());
		JsonObject first = regions.get(0).getAsJsonObject();
		assertEquals(12850, first.get("region_id").getAsInt());
		assertEquals(100, first.get("ticks").getAsInt());
		// nested RegionTick must NOT carry base payload fields (extra=forbid).
		assertFalse("nested region tick must not carry rsn", first.has("rsn"));
	}

	@Test
	public void efficiencyEnvelopeUsesContractFields()
	{
		EfficiencyEnvelope eff = new EfficiencyEnvelope();
		eff.rsn = "Zezima";
		eff.pluginVersion = "1.0.0";
		eff.sessionId = "sid-2";
		eff.activeTicks = 300;
		eff.idleTicks = 12;
		eff.worldHops = 2;
		eff.durationTicks = 312;
		eff.skillRates = Arrays.asList(new EfficiencyEnvelope.SkillRate("woodcutting", 45000));

		JsonObject o = json(eff);
		assertEquals("sid-2", o.get("session_id").getAsString());
		assertEquals(300, o.get("active_ticks").getAsInt());
		assertEquals(12, o.get("idle_ticks").getAsInt());
		assertEquals(2, o.get("world_hops").getAsInt());
		assertEquals(312, o.get("duration_ticks").getAsInt());
		JsonArray rates = o.getAsJsonArray("skill_rates");
		assertEquals(1, rates.size());
		JsonObject rate = rates.get(0).getAsJsonObject();
		assertEquals("woodcutting", rate.get("skill").getAsString());
		assertEquals(45000, rate.get("xp_per_hour").getAsInt());
	}

	@Test
	public void efficiencyOmitsNullSkillRates()
	{
		// v1 leaves skill_rates null: it must be ABSENT (not [] and not null), matching the
		// pydantic Optional[...] = None default. An empty list would misrepresent "measured zero".
		EfficiencyEnvelope eff = new EfficiencyEnvelope();
		eff.rsn = "Zezima";
		eff.pluginVersion = "1.0.0";
		eff.sessionId = "sid-3";
		eff.activeTicks = 100;
		eff.idleTicks = 0;
		eff.worldHops = 0;
		eff.durationTicks = 100;
		eff.skillRates = null;

		JsonObject o = json(eff);
		assertFalse("null skill_rates must be omitted from the wire", o.has("skill_rates"));
		// A real zero (0 idle/hops in a captured session) is still present, distinct from absence.
		assertEquals(0, o.get("idle_ticks").getAsInt());
		assertEquals(0, o.get("world_hops").getAsInt());
	}

	@Test
	public void activityBreakdownUsesContractFields()
	{
		ActivityBreakdown activity = new ActivityBreakdown();
		activity.rsn = "Zezima";
		activity.pluginVersion = "1.0.0";
		activity.sessionId = "sid-4";
		activity.buckets = Arrays.asList(
			new ActivityBreakdown.ActivityBucket("woodcutting", 3600),
			new ActivityBreakdown.ActivityBucket("unknown", 120));

		JsonObject o = json(activity);
		assertEquals("sid-4", o.get("session_id").getAsString());
		JsonArray buckets = o.getAsJsonArray("buckets");
		assertEquals(2, buckets.size());
		JsonObject first = buckets.get(0).getAsJsonObject();
		assertEquals("woodcutting", first.get("bucket").getAsString());
		assertEquals(3600, first.get("seconds").getAsInt());
		assertFalse("nested activity bucket must not carry rsn", first.has("rsn"));
	}

	@Test
	public void batchCarriesWaveAAggregateLists()
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = "Zezima";
		batch.pluginVersion = "1.0.0";

		RegionTimeShare region = new RegionTimeShare();
		region.rsn = "Zezima";
		region.pluginVersion = "1.0.0";
		region.sessionId = "sid-1";
		region.regions = Arrays.asList(new RegionTimeShare.RegionTick(12850, 10));
		batch.regionTime = Arrays.asList(region);

		EfficiencyEnvelope eff = new EfficiencyEnvelope();
		eff.rsn = "Zezima";
		eff.pluginVersion = "1.0.0";
		eff.sessionId = "sid-2";
		batch.efficiency = Arrays.asList(eff);

		ActivityBreakdown activity = new ActivityBreakdown();
		activity.rsn = "Zezima";
		activity.pluginVersion = "1.0.0";
		activity.sessionId = "sid-4";
		activity.buckets = Arrays.asList(new ActivityBreakdown.ActivityBucket("idle", 60));
		batch.activityTime = Arrays.asList(activity);

		JsonObject o = json(batch);
		assertTrue("batch must use region_time", o.has("region_time"));
		assertTrue("batch must use efficiency", o.has("efficiency"));
		assertTrue("batch must use activity_time", o.has("activity_time"));
		assertEquals(1, o.getAsJsonArray("region_time").size());
		assertEquals(1, o.getAsJsonArray("activity_time").size());
	}

	// --- FP-A5 Wave-A discrete-event lanes (signal events, GE trades) ---

	@Test
	public void signalEventUsesContractFields()
	{
		SignalEvent signal = new SignalEvent();
		signal.rsn = "Zezima";
		signal.pluginVersion = "1.0.0";
		signal.signalType = SignalEvent.SignalType.BOSS_KC;
		signal.subject = "Zulrah";
		signal.value = 1412;
		signal.detail = "Your Zulrah kill count is: 1,412";

		JsonObject o = json(signal);
		assertEquals("boss_kc", o.get("signal_type").getAsString());
		assertEquals("Zulrah", o.get("subject").getAsString());
		assertEquals(1412, o.get("value").getAsInt());
		assertEquals("Your Zulrah kill count is: 1,412", o.get("detail").getAsString());
	}

	@Test
	public void signalEventOmitsNullOptionalFields()
	{
		// A pet drop carries no subject/value/detail beyond the kind: each null must be ABSENT
		// (not null, not 0), matching the pydantic Optional[...] = None "honestly absent" contract.
		SignalEvent signal = new SignalEvent();
		signal.rsn = "Zezima";
		signal.pluginVersion = "1.0.0";
		signal.signalType = SignalEvent.SignalType.PET;
		signal.subject = null;
		signal.value = null;
		signal.detail = null;

		JsonObject o = json(signal);
		assertEquals("pet", o.get("signal_type").getAsString());
		assertFalse("null subject must be absent", o.has("subject"));
		assertFalse("null value must be absent, never 0", o.has("value"));
		assertFalse("null detail must be absent", o.has("detail"));
	}

	@Test
	public void signalTypeEnumValuesMatchContract()
	{
		// These six strings are the wire contract shared with the pydantic SignalType enum in
		// catherby src/catherby/api/schemas/plugin.py; drifting either side reclassifies rows.
		assertEquals("\"level_up\"", GSON.toJson(SignalEvent.SignalType.LEVEL_UP));
		assertEquals("\"pet\"", GSON.toJson(SignalEvent.SignalType.PET));
		assertEquals("\"clue_completion\"", GSON.toJson(SignalEvent.SignalType.CLUE_COMPLETION));
		assertEquals("\"boss_kc\"", GSON.toJson(SignalEvent.SignalType.BOSS_KC));
		assertEquals("\"diary_completion\"", GSON.toJson(SignalEvent.SignalType.DIARY_COMPLETION));
		assertEquals("\"quest_completion\"", GSON.toJson(SignalEvent.SignalType.QUEST_COMPLETION));
	}

	@Test
	public void signalEventDetailClampsToTheFiveHundredCharContract()
	{
		StringBuilder overlong = new StringBuilder();
		for (int i = 0; i < 600; i++)
		{
			overlong.append('x');
		}
		String clamped = SignalEvent.clampDetail(overlong.toString());
		assertEquals("detail must clamp to the server's 500-char max", 500, clamped.length());
		assertNull("null detail stays null (absent on the wire)", SignalEvent.clampDetail(null));
	}

	@Test
	public void geTradeUsesContractFields()
	{
		GeTrade trade = new GeTrade();
		trade.rsn = "Zezima";
		trade.pluginVersion = "1.0.0";
		trade.itemId = 4151;
		trade.state = GeTrade.GeTradeState.BOUGHT;
		trade.quantity = 100;
		trade.spent = 5_000_000_000L; // exceeds 32-bit range -> BIGINT / long on the wire
		trade.priceEach = 50_000_000;
		trade.slot = 3;

		JsonObject o = json(trade);
		assertEquals(4151, o.get("item_id").getAsInt());
		assertEquals("bought", o.get("state").getAsString());
		assertEquals(100, o.get("quantity").getAsInt());
		assertEquals(5_000_000_000L, o.get("spent").getAsLong());
		assertEquals(50_000_000, o.get("price_each").getAsInt());
		assertEquals(3, o.get("slot").getAsInt());
	}

	@Test
	public void geTradeOmitsNullOptionalFields()
	{
		GeTrade trade = new GeTrade();
		trade.rsn = "Zezima";
		trade.pluginVersion = "1.0.0";
		trade.itemId = 995;
		trade.state = GeTrade.GeTradeState.SOLD;
		trade.quantity = 1;
		trade.spent = 100L;
		trade.priceEach = null;
		trade.slot = null;

		JsonObject o = json(trade);
		assertEquals("sold", o.get("state").getAsString());
		assertFalse("null price_each must be absent", o.has("price_each"));
		assertFalse("null slot must be absent", o.has("slot"));
	}

	@Test
	public void geTradeStateEnumValuesMatchContract()
	{
		assertEquals("\"bought\"", GSON.toJson(GeTrade.GeTradeState.BOUGHT));
		assertEquals("\"sold\"", GSON.toJson(GeTrade.GeTradeState.SOLD));
	}

	@Test
	public void batchCarriesSignalEventAndGeTradeLists()
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = "Zezima";
		batch.pluginVersion = "1.0.0";

		SignalEvent signal = new SignalEvent();
		signal.rsn = "Zezima";
		signal.pluginVersion = "1.0.0";
		signal.signalType = SignalEvent.SignalType.LEVEL_UP;
		signal.subject = "Cooking";
		signal.value = 43;
		batch.signalEvents = Arrays.asList(signal);

		GeTrade trade = new GeTrade();
		trade.rsn = "Zezima";
		trade.pluginVersion = "1.0.0";
		trade.itemId = 4151;
		trade.state = GeTrade.GeTradeState.BOUGHT;
		trade.quantity = 1;
		trade.spent = 1L;
		batch.geTrades = Arrays.asList(trade);

		JsonObject o = json(batch);
		assertTrue("batch must use signal_events", o.has("signal_events"));
		assertTrue("batch must use ge_trades", o.has("ge_trades"));
		assertEquals(1, o.getAsJsonArray("signal_events").size());
		assertEquals(1, o.getAsJsonArray("ge_trades").size());
	}
}
