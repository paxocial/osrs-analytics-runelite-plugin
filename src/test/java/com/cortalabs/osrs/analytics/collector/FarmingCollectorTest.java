/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.FarmingCollector.PatchReading;
import com.cortalabs.osrs.analytics.dto.FarmingState.FarmingPatchState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Mutation-proving tests for {@link FarmingCollector#decodeHerb(int)}, transcribed
 * 1:1 from {@code PatchImplementation.HERB} (PatchImplementation.java L435-720). The
 * value ranges are spot-checked at each state and at the range boundaries, and the
 * two honesty guarantees — an empty patch is not a planted crop, and an unmapped
 * value is silence not a guess — are pinned so a transcription slip is caught.
 */
public class FarmingCollectorTest
{
	private static void assertReading(int value, FarmingPatchState state, String plant)
	{
		PatchReading r = FarmingCollector.decodeHerb(value);
		assertEquals("state for value " + value, state, r.state);
		assertEquals("plant for value " + value, plant, r.plant);
	}

	@Test
	public void growingRangesDecodeToPlanted()
	{
		assertReading(4, FarmingPatchState.PLANTED, "Guam");   // first GUAM growing value
		assertReading(7, FarmingPatchState.PLANTED, "Guam");   // last GUAM growing value
		assertReading(32, FarmingPatchState.PLANTED, "Ranarr");
		assertReading(103, FarmingPatchState.PLANTED, "Torstol");
		assertReading(192, FarmingPatchState.PLANTED, "Goutweed");
	}

	@Test
	public void harvestableRangesDecodeToReady()
	{
		assertReading(8, FarmingPatchState.READY, "Guam");     // first GUAM harvestable value
		assertReading(10, FarmingPatchState.READY, "Guam");    // last GUAM harvestable value
		assertReading(36, FarmingPatchState.READY, "Ranarr");
		assertReading(196, FarmingPatchState.READY, "Goutweed");
	}

	@Test
	public void diseasedRangesDecodeToDiseased()
	{
		assertReading(128, FarmingPatchState.DISEASED, "Guam");
		assertReading(140, FarmingPatchState.DISEASED, "Ranarr");
		assertReading(173, FarmingPatchState.DISEASED, "Huasca"); // Huasca's out-of-order diseased band
		assertReading(198, FarmingPatchState.DISEASED, "Goutweed");
	}

	@Test
	public void deadHerbLosesProduceIdentity()
	{
		// Generic "Dead herbs" (Produce.ANYHERB): the specific herb is not witnessable, so
		// plant is honestly absent. Goutweed keeps its identity on death (a specific range).
		assertReading(170, FarmingPatchState.DEAD, null);
		assertReading(172, FarmingPatchState.DEAD, null);
		assertReading(201, FarmingPatchState.DEAD, "Goutweed");
	}

	@Test
	public void weedsPatchIsNotAPlantedCrop()
	{
		// The load-bearing honesty check: WEEDS/GROWING is an empty raked patch, NOT a
		// planted crop. Map it to PLANTED and every raked patch fabricates a crop -> fail.
		assertNull("value 0 is a raked/empty patch", FarmingCollector.decodeHerb(0));
		assertNull("value 3 is a raked/empty patch", FarmingCollector.decodeHerb(3));
		assertNull("value 67 is a raked/empty patch", FarmingCollector.decodeHerb(67));
		assertNull("value 176 is a raked/empty patch", FarmingCollector.decodeHerb(176));
		assertNull("value 255 is a raked/empty patch", FarmingCollector.decodeHerb(255));
	}

	@Test
	public void unmappedValuesAreSilenceNotAGuess()
	{
		// Drift safety: a value outside every transcribed range (a future herb, a garbage
		// read) must degrade to nothing, never a defaulted state.
		assertNull("value 220 is unmapped", FarmingCollector.decodeHerb(220));
		assertNull("value 256 is out of range", FarmingCollector.decodeHerb(256));
		assertNull("a negative value is out of range", FarmingCollector.decodeHerb(-1));
		assertNull("a large value is out of range", FarmingCollector.decodeHerb(10_000));
	}

	@Test
	public void produceBlockBoundariesAreExact()
	{
		// The growing/harvestable blocks interleave per herb; a one-off transcription slip
		// would smear a boundary. Pin the GUAM->MARRENTILL handoff.
		assertReading(11, FarmingPatchState.PLANTED, "Marrentill"); // first MARRENTILL growing
		assertReading(14, FarmingPatchState.PLANTED, "Marrentill"); // last MARRENTILL growing
		assertReading(15, FarmingPatchState.READY, "Marrentill");   // first MARRENTILL harvestable
		assertReading(17, FarmingPatchState.READY, "Marrentill");   // last MARRENTILL harvestable
	}
}
