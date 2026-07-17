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
}
