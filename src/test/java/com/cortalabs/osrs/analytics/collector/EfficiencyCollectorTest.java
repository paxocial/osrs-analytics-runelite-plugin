/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.EfficiencyCollector.EfficiencyAccumulator;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link EfficiencyAccumulator}: the min()-of-input active/idle rule, the
 * per-session active/idle accumulation and duration, world-hop counting, and the
 * account-switch fabrication guard (I4).
 */
public class EfficiencyCollectorTest
{
	@Test
	public void isIdleUsesMinOfMouseAndKeyboardAgainstTheThreshold()
	{
		int t = EfficiencyCollector.AFK_IDLE_THRESHOLD_TICKS;
		// Both input channels idle past the threshold -> idle.
		assertTrue(EfficiencyAccumulator.isIdle(t, t));
		assertTrue(EfficiencyAccumulator.isIdle(t + 10, t + 100));
		// Either channel active (below threshold) -> active. min() is the AFK signal.
		assertFalse("recent keyboard input keeps the tick active",
			EfficiencyAccumulator.isIdle(t + 100, 0));
		assertFalse("recent mouse input keeps the tick active",
			EfficiencyAccumulator.isIdle(0, t + 100));
		assertFalse(EfficiencyAccumulator.isIdle(t - 1, t + 100));
	}

	@Test
	public void splitsActiveAndIdleTicksAndSumsDuration()
	{
		EfficiencyAccumulator acc = new EfficiencyAccumulator();
		acc.bindAccount(1L);
		int active = 0; // low idle-tick counts -> active
		int idle = EfficiencyCollector.AFK_IDLE_THRESHOLD_TICKS + 5; // above threshold -> idle
		for (int i = 0; i < 30; i++)
		{
			acc.recordTick(active, active, 330, "Zezima");
		}
		for (int i = 0; i < 12; i++)
		{
			acc.recordTick(idle, idle, 330, "Zezima");
		}

		assertEquals(30, acc.activeTicks());
		assertEquals(12, acc.idleTicks());
		assertEquals("duration is the total witnessed ticks", 42, acc.durationTicks());
		assertEquals("no world change in a single-world session", 0, acc.worldHops());
	}

	@Test
	public void countsWorldHopsOnWorldChangeNotOnSteadyWorld()
	{
		EfficiencyAccumulator acc = new EfficiencyAccumulator();
		acc.bindAccount(1L);
		acc.recordTick(0, 0, 330, "Zezima"); // first tick: establishes world, no hop
		acc.recordTick(0, 0, 330, "Zezima"); // same world, no hop
		acc.recordTick(0, 0, 331, "Zezima"); // hop 1
		acc.recordTick(0, 0, 331, "Zezima"); // steady, no hop
		acc.recordTick(0, 0, 301, "Zezima"); // hop 2
		assertEquals(2, acc.worldHops());
	}

	@Test
	public void witnessedOrAbsentEmitsNothingForAnEmptySession()
	{
		EfficiencyAccumulator acc = new EfficiencyAccumulator();
		acc.bindAccount(1L);
		assertFalse(acc.hasData());
	}

	@Test
	public void accountSwitchClearsAllCountersSoNoCrossAccountBleed()
	{
		EfficiencyAccumulator acc = new EfficiencyAccumulator();
		acc.bindAccount(100L);
		for (int i = 0; i < 20; i++)
		{
			acc.recordTick(0, 0, 330, "AccountA"); // 20 active ticks
		}
		acc.recordTick(0, 0, 331, "AccountA"); // + a hop
		// Account B logs in on the same client.
		acc.bindAccount(200L);
		acc.recordTick(0, 0, 500, "AccountB"); // one active tick, fresh world

		assertEquals("only account B's active tick survives", 1, acc.activeTicks());
		assertEquals(0, acc.idleTicks());
		assertEquals("account A's hop must not bleed into account B", 0, acc.worldHops());
		assertEquals(1, acc.durationTicks());
		assertEquals("AccountB", acc.rsn());
	}
}
