/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link AnimationActivityMap} against the FP-SPIKE-ANIM honesty targets
 * (RESEARCH_FP_SPIKE_ANIM.md §Recommendations Z):
 *
 * <ul>
 *   <li><b>Closed-vocabulary guarantee</b> — every classify() result is one of the 20
 *       taxonomy tokens; an unmapped id is {@code unknown}, never a crash or a guess.</li>
 *   <li><b>Collision honesty</b> — the two ids ambiguous by animation alone (827, 830)
 *       resolve to {@code unknown}, not one-or-the-other; any other double-claimed id is
 *       likewise poisoned.</li>
 *   <li><b>Anchor correctness</b> — the cross-namespace-verified anchors map to the right
 *       bucket against the REAL RuneLite constants (the reflection path).</li>
 *   <li><b>Gap-fills</b> — the gameval-only ids (791 runecraft, 881 thieving) map.</li>
 * </ul>
 *
 * The controlled-source tests use the package-private {@code (Map)} constructor so the
 * bucketing logic is proven with plain data, independent of the resolved jar; the anchor
 * test uses the real reflected map so a RuneLite renumber that breaks an anchor is caught.
 */
public class AnimationActivityMapTest
{
	private static Map<String, Integer> constants(Object... namePairs)
	{
		Map<String, Integer> m = new HashMap<>();
		for (int i = 0; i < namePairs.length; i += 2)
		{
			m.put((String) namePairs[i], (Integer) namePairs[i + 1]);
		}
		return m;
	}

	// --- Controlled-source bucketing (plain data, no jar dependency) ---

	@Test
	public void prefixFamiliesBucketByConstantName()
	{
		AnimationActivityMap map = new AnimationActivityMap(constants(
			"WOODCUTTING_BRONZE", 879,
			"MINING_RUNE_PICKAXE", 624,
			"FISHING_NET", 621,
			"COOKING_RANGE", 896,
			"FIREMAKING", 733,
			"FLETCHING_BOW_CUTTING", 1248,
			"HERBLORE_POTIONMAKING", 363,
			"HUNTER_LAY_NETTRAP", 5215,
			"CONSTRUCTION", 3676,
			"MAGIC_CHARGING_ORBS", 726));

		assertEquals(AnimationActivityMap.WOODCUTTING, map.classify(879));
		assertEquals(AnimationActivityMap.MINING, map.classify(624));
		assertEquals(AnimationActivityMap.FISHING, map.classify(621));
		assertEquals(AnimationActivityMap.COOKING, map.classify(896));
		assertEquals(AnimationActivityMap.FIREMAKING, map.classify(733));
		assertEquals(AnimationActivityMap.FLETCHING, map.classify(1248));
		assertEquals(AnimationActivityMap.HERBLORE, map.classify(363));
		assertEquals(AnimationActivityMap.HUNTER, map.classify(5215));
		assertEquals(AnimationActivityMap.CONSTRUCTION, map.classify(3676));
		assertEquals(AnimationActivityMap.MAGIC_UTILITY, map.classify(726));
	}

	@Test
	public void exactNameExceptionsOverrideBroadFamilies()
	{
		AnimationActivityMap map = new AnimationActivityMap(constants(
			"CRAFTING_BATTLESTAVES", 7531,      // smithing, not crafting
			"DENSE_ESSENCE_CHIPPING", 7201,     // runecraft, not crafting
			"CRAFTING_SPINNING", 894,           // still crafting
			"USING_GILDED_ALTAR", 3705,         // prayer
			"MILKING_COW", 2305));              // farming

		assertEquals(AnimationActivityMap.SMITHING, map.classify(7531));
		assertEquals(AnimationActivityMap.RUNECRAFT, map.classify(7201));
		assertEquals(AnimationActivityMap.CRAFTING, map.classify(894));
		assertEquals(AnimationActivityMap.PRAYER, map.classify(3705));
		assertEquals(AnimationActivityMap.FARMING, map.classify(2305));
	}

	@Test
	public void collisionIdsResolveToUnknownNeverAGuess()
	{
		// 827 = SMITHING_CANNONBALL (smithing) vs BURYING_BONES (prayer)
		// 830 = FARMING_HARVEST_ALLOTMENT (farming) vs DIG (no family -> unmapped)
		AnimationActivityMap map = new AnimationActivityMap(constants(
			"SMITHING_CANNONBALL", 827,
			"BURYING_BONES", 827,
			"FARMING_HARVEST_ALLOTMENT", 830,
			"DIG", 830));

		assertEquals("827 must never be guessed as smithing or prayer",
			AnimationActivityMap.UNKNOWN, map.classify(827));
		assertEquals("830 must never be guessed as farming or dig",
			AnimationActivityMap.UNKNOWN, map.classify(830));
	}

	@Test
	public void anyDoubleClaimedIdIsPoisonedToUnknown()
	{
		// Two different families claiming one id (not in the known set) is still ambiguous.
		AnimationActivityMap map = new AnimationActivityMap(constants(
			"WOODCUTTING_MYSTERY", 4242,
			"MINING_MYSTERY", 4242));
		assertEquals(AnimationActivityMap.UNKNOWN, map.classify(4242));
	}

	@Test
	public void gamevalGapFillsMapWhenLegacyDoesNotCoverThem()
	{
		AnimationActivityMap map = new AnimationActivityMap(constants());
		assertEquals(AnimationActivityMap.RUNECRAFT, map.classify(791));
		assertEquals(AnimationActivityMap.THIEVING, map.classify(881));
	}

	@Test
	public void idleAndUnmappedAreHonestBuckets()
	{
		AnimationActivityMap map = new AnimationActivityMap(constants("WOODCUTTING_BRONZE", 879));
		assertEquals("empty animation is idle", AnimationActivityMap.IDLE, map.classify(-1));
		assertEquals("an unmapped playing animation is unknown, never guessed",
			AnimationActivityMap.UNKNOWN, map.classify(123456));
		assertEquals("CONSUMING/eating has no family -> unknown",
			AnimationActivityMap.UNKNOWN, map.classify(829));
	}

	@Test
	public void closedVocabularyGuaranteeHoldsAcrossTheIdSpace()
	{
		AnimationActivityMap map = new AnimationActivityMap();
		for (int id = -1; id <= 20_000; id++)
		{
			assertTrue("classify(" + id + ") escaped the closed taxonomy",
				AnimationActivityMap.BUCKETS.contains(map.classify(id)));
		}
	}

	// --- Real reflected map (the reflection path against the resolved RuneLite jar) ---

	@Test
	public void reflectedMapClassifiesTheVerifiedAnchors()
	{
		AnimationActivityMap map = new AnimationActivityMap();
		assertEquals(AnimationActivityMap.WOODCUTTING, map.classify(879)); // WOODCUTTING_BRONZE
		assertEquals(AnimationActivityMap.FISHING, map.classify(621));     // FISHING_NET
		assertEquals(AnimationActivityMap.MINING, map.classify(624));      // MINING_RUNE_PICKAXE
		assertEquals(AnimationActivityMap.COOKING, map.classify(896));     // COOKING_RANGE
		assertEquals(AnimationActivityMap.PRAYER, map.classify(3705));     // USING_GILDED_ALTAR
		assertEquals(AnimationActivityMap.IDLE, map.classify(-1));
	}

	@Test
	public void reflectedMapHasVerifiedCollisionsAsUnknown()
	{
		AnimationActivityMap map = new AnimationActivityMap();
		assertEquals(AnimationActivityMap.UNKNOWN, map.classify(827));
		assertEquals(AnimationActivityMap.UNKNOWN, map.classify(830));
	}

	@Test
	public void reflectionProducedASubstantialMap()
	{
		// FP-SPIKE-ANIM verified ~237 named skilling constants; a healthy count proves the
		// reflection path actually read the constants rather than returning an empty map.
		assertTrue("reflection should map many animation ids",
			new AnimationActivityMap().mappedIdCount() > 100);
	}
}
