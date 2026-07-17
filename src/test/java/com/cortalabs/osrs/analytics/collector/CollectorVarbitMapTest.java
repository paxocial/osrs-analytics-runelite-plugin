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
import static org.junit.Assert.assertTrue;

/**
 * Guards the source-verified varbit maps that drive the diary and combat
 * achievement collectors. These maps are the whole reason those collectors emit
 * anything, so a regression here silently blinds the telemetry — hence a test.
 *
 * <p>Diary ids are cross-checked against two families in RuneLite's API:
 * tasks-done flags ({@code net.runelite.api.Varbits} L212-270, gameval
 * {@code *_DIARY_*_COMPLETE} / {@code ATJUN_*_DONE}) and reward-claimed flags
 * ({@code net.runelite.api.gameval.VarbitID.*_REWARD}: Karamja L2650/2671/2683,
 * 4499-4538 block L3265-3304, Karamja elite L3332, Kourend L4770-4773).
 * Combat-task counts: {@code CA_TOTAL_TASKS_COMPLETED_*} (VarbitID L8058-8063,
 * legacy Varbits {@code COMBAT_TASK_*} L970-975).
 */
public class CollectorVarbitMapTest
{
	private static final List<String> EXPECTED_REGIONS = Arrays.asList(
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja",
		"Kourend", "Lumbridge", "Morytania", "Varrock", "Western Provinces", "Wilderness");

	private static final List<String> EXPECTED_TIERS = Arrays.asList(
		"easy", "medium", "hard", "elite", "master", "grandmaster");

	@Test
	public void diaryMapsCoverEveryRegionAndTier()
	{
		for (Map<String, int[]> map : Arrays.asList(
			DiaryCollector.regionTierVarbits(), DiaryCollector.regionTierRewardVarbits()))
		{
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
	}

	@Test
	public void diaryTasksDoneMapMatchesSourceVerifiedIds()
	{
		Map<String, int[]> map = DiaryCollector.regionTierVarbits();
		// Spot-check against Varbits.java L212-270 (gameval *_DIARY_*_COMPLETE /
		// ATJUN_*_DONE): standard region, the Karamja legacy ids, the highest-id
		// region, and Wilderness.
		assertArrayEquals(new int[]{4458, 4459, 4460, 4461}, map.get("Ardougne"));
		assertArrayEquals(new int[]{3578, 3599, 3611, 4566}, map.get("Karamja"));
		assertArrayEquals(new int[]{7925, 7926, 7927, 7928}, map.get("Kourend"));
		assertArrayEquals(new int[]{4466, 4467, 4468, 4469}, map.get("Wilderness"));
	}

	@Test
	public void diaryRewardMapMatchesSourceVerifiedIds()
	{
		Map<String, int[]> map = DiaryCollector.regionTierRewardVarbits();
		// gameval VarbitID.java: ARDOUGNE_*_REWARD L3265-3268; ATJUN_EASY/MED/
		// HARD_REWARD L2650/2671/2683 + KARAMJA_ELITE_REWARD L3332;
		// KOUREND_*_REWARD L4770-4773; WILDERNESS_*_REWARD L3273-3276. These are
		// the ids WikiSync's achievementDiariesSpecs.json keys completion off.
		assertArrayEquals(new int[]{4499, 4500, 4501, 4502}, map.get("Ardougne"));
		assertArrayEquals(new int[]{3577, 3598, 3610, 4567}, map.get("Karamja"));
		assertArrayEquals(new int[]{7929, 7930, 7931, 7932}, map.get("Kourend"));
		assertArrayEquals(new int[]{4507, 4508, 4509, 4510}, map.get("Wilderness"));
		// Regression guard for the Karamja bug (live session 2026-07-17): the
		// reward family must differ from the tasks-done family so the OR check
		// actually reads a second signal.
		assertArrayEquals(new int[]{4511, 4512, 4513, 4514}, map.get("Western Provinces"));
	}

	@Test
	public void diaryVarbitIdsAreUniqueAcrossBothFamilies()
	{
		Set<Integer> seen = new HashSet<>();
		for (Map<String, int[]> map : Arrays.asList(
			DiaryCollector.regionTierVarbits(), DiaryCollector.regionTierRewardVarbits()))
		{
			for (int[] tiers : map.values())
			{
				for (int varbit : tiers)
				{
					assertTrue("duplicate diary varbit id " + varbit, seen.add(varbit));
				}
			}
		}
		assertEquals("12 regions x 4 tiers x 2 families = 96 distinct varbits", 96, seen.size());
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
}
