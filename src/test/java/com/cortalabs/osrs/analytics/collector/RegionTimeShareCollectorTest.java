/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.RegionTimeShareCollector.RegionAccumulator;
import com.cortalabs.osrs.analytics.dto.RegionTimeShare;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link RegionAccumulator} and {@link RegionTimeShareCollector#toCappedRegions}:
 * aggregate-and-flush (many ticks -> one per-region count, never per-tick rows), the
 * account-switch fabrication guard (I4), witnessed-or-absent (I1), and the 256-region
 * volume cap (I3).
 */
public class RegionTimeShareCollectorTest
{
	private static int ticksFor(List<RegionTimeShare.RegionTick> regions, int regionId)
	{
		for (RegionTimeShare.RegionTick r : regions)
		{
			if (r.regionId == regionId)
			{
				return r.ticks;
			}
		}
		return -1;
	}

	@Test
	public void aggregatesTicksPerRegionIntoOneFlushNotPerTickRows()
	{
		RegionAccumulator acc = new RegionAccumulator();
		acc.bindAccount(1L);
		for (int i = 0; i < 100; i++)
		{
			acc.recordTick(12850, "Zezima");
		}
		for (int i = 0; i < 40; i++)
		{
			acc.recordTick(12851, "Zezima");
		}

		List<RegionTimeShare.RegionTick> regions = acc.regions();
		assertEquals("140 ticks across 2 regions must aggregate to 2 rows, not 140",
			2, regions.size());
		assertEquals(100, ticksFor(regions, 12850));
		assertEquals(40, ticksFor(regions, 12851));
	}

	@Test
	public void witnessedOrAbsentEmitsNothingForAnEmptySession()
	{
		RegionAccumulator acc = new RegionAccumulator();
		acc.bindAccount(1L);
		assertFalse("no ticks means no data means no row (absence != zero)", acc.hasData());
	}

	@Test
	public void sessionIdIsStableWithinASessionAndPresent()
	{
		RegionAccumulator acc = new RegionAccumulator();
		acc.bindAccount(1L);
		acc.recordTick(12850, "Zezima");
		String first = acc.sessionId();
		acc.recordTick(12850, "Zezima");
		assertNotNull(first);
		assertEquals("session id must not change mid-session", first, acc.sessionId());
	}

	@Test
	public void accountSwitchClearsSoNoCrossAccountBleed()
	{
		RegionAccumulator acc = new RegionAccumulator();
		// Account A accrues 50 ticks in a region...
		acc.bindAccount(100L);
		for (int i = 0; i < 50; i++)
		{
			acc.recordTick(12850, "AccountA");
		}
		// ...then account B logs in on the same client instance.
		acc.bindAccount(200L);
		for (int i = 0; i < 5; i++)
		{
			acc.recordTick(13000, "AccountB");
		}

		List<RegionTimeShare.RegionTick> regions = acc.regions();
		assertEquals("account B's flush must carry ONLY account B's regions", 1, regions.size());
		assertEquals(5, ticksFor(regions, 13000));
		assertEquals("account A's ticks must not bleed into account B", -1, ticksFor(regions, 12850));
		assertEquals("account B's rsn owns the flush", "AccountB", acc.rsn());
	}

	@Test
	public void clearResetsSessionButKeepsAccountBound()
	{
		RegionAccumulator acc = new RegionAccumulator();
		acc.bindAccount(1L);
		acc.recordTick(12850, "Zezima");
		acc.clear();
		assertFalse(acc.hasData());
		// Same account after a flush: binding again must NOT re-clear a fresh session.
		acc.bindAccount(1L);
		acc.recordTick(12851, "Zezima");
		assertTrue(acc.hasData());
		assertEquals(1, acc.regions().size());
	}

	@Test
	public void capKeepsTop256RegionsByTicksAndDropsTheRest()
	{
		Map<Integer, Long> ticks = new LinkedHashMap<>();
		// 300 regions: region r gets (r+1) ticks, so higher ids have more ticks.
		for (int r = 0; r < 300; r++)
		{
			ticks.put(r, (long) (r + 1));
		}
		List<RegionTimeShare.RegionTick> capped = RegionTimeShareCollector.toCappedRegions(ticks);
		assertEquals("must cap at the wire max of 256", 256, capped.size());
		// The 256 kept must be the highest-tick regions (44..299, i.e. 45..300 ticks);
		// region 43 (44 ticks) is the first dropped.
		assertEquals("lowest-tick region kept is 44 (45 ticks)", 45, ticksFor(capped, 44));
		assertEquals("region 43 (44 ticks) is dropped", -1, ticksFor(capped, 43));
		assertEquals("highest-tick region kept is 299 (300 ticks)", 300, ticksFor(capped, 299));
	}

	@Test
	public void capIsANoOpUnderTheLimit()
	{
		Map<Integer, Long> ticks = new LinkedHashMap<>();
		ticks.put(12850, 10L);
		ticks.put(12851, 20L);
		List<RegionTimeShare.RegionTick> capped = RegionTimeShareCollector.toCappedRegions(ticks);
		assertEquals(2, capped.size());
		assertEquals(10, ticksFor(capped, 12850));
		assertEquals(20, ticksFor(capped, 12851));
	}
}
