/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the source-verified varbit maps that drive the diary and combat
 * achievement collectors. These maps are the whole reason those collectors emit
 * anything, so a regression here silently blinds the telemetry — hence a test.
 *
 * <p>Ids are cross-checked against RuneLite's {@code net.runelite.api.Varbits}
 * (diary L212-270; combat-task counts {@code COMBAT_TASK_*} L970-975, gameval
 * {@code CA_TOTAL_TASKS_COMPLETED_*} VarbitID L8058-8063). Citation spot-check
 * re-verified 2026-07-17 against reference checkout 2591ac6b, and the diary map
 * was live-validated the same day: a 12-region session snapshot matched the
 * operator's real diary state 12-for-12.
 */
public class CollectorVarbitMapTest
{
	private static final List<String> EXPECTED_REGIONS = Arrays.asList(
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend", "Lumbridge", "Morytania", "Varrock", "Western Provinces", "Wilderness");

	private static final List<String> EXPECTED_TIERS = Arrays.asList(
		"easy", "medium", "hard", "elite", "master", "grandmaster");

	@Test
	public void diaryMapCoversEveryRegionAndTier()
	{
		Map<String, int[]> map = DiaryCollector.regionTierVarbits();
		assertEquals("all twelve diary regions present", EXPECTED_REGIONS.size(), map.size());
		assertTrue("region set matches the OSRS diary regions",
			map.keySet().containsAll(EXPECTED_REGIONS));
		for (Map.Entry<String, int[]> entry : map.entrySet())
		{
			assertEquals(entry.getKey() + " must have easy/medium/hard/elite varbits",
				4, entry.getValue().length);
			for (int varbit : entry.getValue())
			{
				assertTrue(entry.getKey() + " varbit ids must be positive", varbit > 0);
			}
		}
	}

	@Test
	public void diaryMapMatchesSourceVerifiedIds()
	{
		Map<String, int[]> map = DiaryCollector.regionTierVarbits();
		// Spot-check against Varbits.java: standard region, the Karamja special case,
		// and the highest-id region.
		assertArrayEquals(new int[]{4458, 4459, 4460, 4461}, map.get("Ardougne"));
		assertArrayEquals(new int[]{3578, 3599, 3611, 4566}, map.get("Karamja"));
		assertArrayEquals(new int[]{7925, 7926, 7927, 7928}, map.get("Kourend"));
		assertArrayEquals(new int[]{4466, 4467, 4468, 4469}, map.get("Wilderness"));
	}

	@Test
	public void diaryVarbitIdsAreUnique()
	{
		Set<Integer> seen = new HashSet<>();
		for (int[] tiers : DiaryCollector.regionTierVarbits().values())
		{
			for (int varbit : tiers)
			{
				assertTrue("duplicate diary varbit id " + varbit, seen.add(varbit));
			}
		}
		assertEquals("12 regions x 4 tiers = 48 distinct varbits", 48, seen.size());
	}

	@Test
	public void combatAchievementMapUsesServerWhitelistTiers()
	{
		Map<String, Integer> map = CombatAchievementCollector.tierCountVarbits();
		assertEquals("six combat achievement tiers", EXPECTED_TIERS.size(), map.size());
		assertTrue("tier keys must be the backend's accepted set",
			map.keySet().containsAll(EXPECTED_TIERS));
	}

	@Test
	public void combatAchievementMapMatchesSourceVerifiedIds()
	{
		Map<String, Integer> map = CombatAchievementCollector.tierCountVarbits();
		// CA_TOTAL_TASKS_COMPLETED_{EASY..GRANDMASTER} = 12885..12890 (Varbits L970-975).
		assertEquals(Integer.valueOf(12885), map.get("easy"));
		assertEquals(Integer.valueOf(12886), map.get("medium"));
		assertEquals(Integer.valueOf(12887), map.get("hard"));
		assertEquals(Integer.valueOf(12888), map.get("elite"));
		assertEquals(Integer.valueOf(12889), map.get("master"));
		assertEquals(Integer.valueOf(12890), map.get("grandmaster"));
	}

	// --- FP-B4: slayer + farming source-verified ids ---

	/**
	 * Slayer varp/varbit + DBTable ids, cross-checked against RESEARCH_FP_SPIKE_SLAYER
	 * (each id cited to its {@code SlayerPlugin.java} read-site AND its gameval
	 * declaration). These constants are the whole reason the slayer collector can decode
	 * a task, so a drift here silently blinds or mislabels the lane — hence a test.
	 */
	@Test
	public void slayerIdsMatchSourceVerifiedValues()
	{
		assertEquals("SLAYER_COUNT varp", 394, SlayerCollector.VARP_SLAYER_COUNT);
		assertEquals("SLAYER_TARGET varp", 395, SlayerCollector.VARP_SLAYER_TARGET);
		assertEquals("SLAYER_AREA varp", 2096, SlayerCollector.VARP_SLAYER_AREA);
		assertEquals("SLAYER_COUNT_ORIGINAL varp", 4258, SlayerCollector.VARP_SLAYER_COUNT_ORIGINAL);
		assertEquals("SLAYER_MASTER varbit", 4067, SlayerCollector.VARBIT_SLAYER_MASTER);
		assertEquals("SLAYER_POINTS varbit", 4068, SlayerCollector.VARBIT_SLAYER_POINTS);
		assertEquals("SLAYER_TASKS_COMPLETED varbit", 4069, SlayerCollector.VARBIT_SLAYER_TASKS_COMPLETED);
		assertEquals("SLAYER_TARGET_BOSSID varbit", 4723, SlayerCollector.VARBIT_SLAYER_TARGET_BOSSID);
		assertEquals("SLAYER_WILDERNESS_TASKS_COMPLETED varbit", 5617,
			SlayerCollector.VARBIT_SLAYER_WILDERNESS_TASKS_COMPLETED);
		// Sentinels.
		assertEquals("Krystilia master index", 7, SlayerCollector.KRYSTILIA_MASTER_INDEX);
		assertEquals("Bosses task-id sentinel", 98, SlayerCollector.TASK_ID_BOSS);
		// DBTable decode ids.
		assertEquals("SlayerTask table", 113, SlayerCollector.DB_SLAYER_TASK);
		assertEquals("SlayerTask COL_ID", 0, SlayerCollector.DB_SLAYER_TASK_COL_ID);
		assertEquals("SlayerTask COL_NAME_UPPERCASE", 10, SlayerCollector.DB_SLAYER_TASK_COL_NAME_UPPERCASE);
		assertEquals("SlayerArea table", 115, SlayerCollector.DB_SLAYER_AREA);
		assertEquals("SlayerArea COL_AREA_ID", 0, SlayerCollector.DB_SLAYER_AREA_COL_AREA_ID);
		assertEquals("SlayerArea COL_NAME", 3, SlayerCollector.DB_SLAYER_AREA_COL_NAME);
		assertEquals("SlayerTaskSublist table", 116, SlayerCollector.DB_SLAYER_TASK_SUBLIST);
		assertEquals("SlayerTaskSublist COL_SUBTABLE_ID", 1, SlayerCollector.DB_SUBLIST_COL_SUBTABLE_ID);
		assertEquals("SlayerTaskSublist COL_TASK", 4, SlayerCollector.DB_SUBLIST_COL_TASK);
	}

	/**
	 * Farming herb-patch map is keyed by {@code (regionID, transmitVarbit)}, cross-checked
	 * against RESEARCH_FARMING_VARBIT_MAP Appendix A. Ten herb regions, and — the single
	 * most important guarantee — the SAME transmit varbit in two regions resolves to two
	 * DISTINCT patch identities (a region-blind lookup would conflate every herb farm).
	 */
	@Test
	public void farmingHerbMapIsRegionKeyed()
	{
		assertEquals("ten herb regions mapped", 10, FarmingCollector.herbPatches().size());
		assertEquals("Catherby herb", FarmingCollector.patchLabel(11062, 4774));
		assertEquals("Falador herb", FarmingCollector.patchLabel(12083, 4774));
		assertEquals("Farming Guild herb", FarmingCollector.patchLabel(4922, 4775));
		// Same transmit varbit (4774) in two regions -> two distinct identities (region-keyed).
		assertNotEquals("same slot in different regions must not share a patch identity",
			FarmingCollector.patchLabel(11062, 4774), FarmingCollector.patchLabel(12083, 4774));
		// An unmapped (region, varbit) pairing is honestly absent, not a fabricated patch.
		assertNull("unmapped region emits no patch identity", FarmingCollector.patchLabel(12345, 4774));
		assertNull("unmapped varbit emits no patch identity", FarmingCollector.patchLabel(11062, 9999));
	}
}
