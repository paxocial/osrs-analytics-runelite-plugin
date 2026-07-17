/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.DrawnSlot;
import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.PageReduction;
import com.cortalabs.osrs.analytics.dto.CollectionPageSummary;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the pure decision logic behind the collection log collector — the parts
 * that must be correct for the capture to be trustworthy and, above all, for the
 * keyed {@code (account_id, item_id)} stream to never carry an id {@code <= 0}
 * (which would clobber other rows). The full collector is client/widget-driven;
 * these pin the reductions and parsers it delegates to.
 */
public class CollectionLogCollectorTest
{
	@Test
	public void reducePageNeverYieldsANonPositiveItemId()
	{
		// Slots with id 0 / negative id are drawn cells that are NOT real items
		// (empty/placeholder). Even when "obtained" (opacity 0) they must never
		// reach the obtained list — that is the whole data-loss guarantee.
		List<DrawnSlot> slots = Arrays.asList(
			new DrawnSlot(0, 1, 0),      // id 0, opaque -> excluded
			new DrawnSlot(-1, 1, 0),     // negative id, opaque -> excluded
			new DrawnSlot(995, 1, 0),    // real, obtained
			new DrawnSlot(4151, 1, 150), // real, unobtained (faded)
			new DrawnSlot(11832, 5, 0)); // real, obtained, qty 5

		PageReduction page = CollectionLogCollector.reducePage(slots);

		for (DrawnSlot obtained : page.obtained)
		{
			assertTrue("obtained item ids must be > 0", obtained.itemId > 0);
		}
		// Only the two real, opaque items are obtained.
		assertEquals(2, page.obtainedCount());
	}

	@Test
	public void reducePageCountsRealSlotsAndObtainedSubset()
	{
		List<DrawnSlot> slots = Arrays.asList(
			new DrawnSlot(995, 1, 0),    // obtained
			new DrawnSlot(4151, 1, 150), // unobtained
			new DrawnSlot(11832, 1, 150),// unobtained
			new DrawnSlot(0, 0, 0));     // empty cell, not a slot

		PageReduction page = CollectionLogCollector.reducePage(slots);

		assertEquals("total slots counts only real items", 3, page.totalSlots);
		assertEquals("obtained counts only opaque real items", 1, page.obtainedCount());
	}

	@Test
	public void reducePagePreservesObtainedQuantity()
	{
		PageReduction page = CollectionLogCollector.reducePage(
			Collections.singletonList(new DrawnSlot(11832, 7, 0)));
		assertEquals(1, page.obtainedCount());
		assertEquals(7, page.obtained.get(0).quantity);
	}

	@Test
	public void reducePageHandlesEmptyPage()
	{
		PageReduction page = CollectionLogCollector.reducePage(Collections.emptyList());
		assertEquals(0, page.totalSlots);
		assertEquals(0, page.obtainedCount());
	}

	@Test
	public void parseKillCountLineReadsLabelAndCount()
	{
		CollectionPageSummary.KillCount kc = CollectionLogCollector.parseKillCountLine("General Graardor kills: 1,234");
		assertNotNull(kc);
		assertEquals("General Graardor kills", kc.name);
		assertEquals(1234, kc.count);
	}

	@Test
	public void parseKillCountLineReadsBareKillsLabel()
	{
		CollectionPageSummary.KillCount kc = CollectionLogCollector.parseKillCountLine("Kills: 100");
		assertNotNull(kc);
		assertEquals("Kills", kc.name);
		assertEquals(100, kc.count);
	}

	@Test
	public void parseKillCountLinePreservesParentheticalLabels()
	{
		CollectionPageSummary.KillCount kc = CollectionLogCollector.parseKillCountLine("Clue scrolls (elite): 1,024");
		assertNotNull(kc);
		assertEquals("Clue scrolls (elite)", kc.name);
		assertEquals(1024, kc.count);
	}

	@Test
	public void parseKillCountLineRejectsObtainedFraction()
	{
		// "Obtained: 5/12" is the completion line, not a kill count.
		assertNull(CollectionLogCollector.parseKillCountLine("Obtained: 5/12"));
	}

	@Test
	public void parseKillCountLineRejectsTitleAndBlanks()
	{
		assertNull(CollectionLogCollector.parseKillCountLine("General Graardor")); // title
		assertNull(CollectionLogCollector.parseKillCountLine(""));
		assertNull(CollectionLogCollector.parseKillCountLine(null));
	}

	@Test
	public void parseKillCountLineStripsColourTags()
	{
		CollectionPageSummary.KillCount kc = CollectionLogCollector.parseKillCountLine("<col=ff9040>Kills: 42</col>");
		assertNotNull(kc);
		assertEquals("Kills", kc.name);
		assertEquals(42, kc.count);
	}

	@Test
	public void parseCollectionLogItemNameExtractsAndTrims()
	{
		assertEquals("Pet chaos elemental",
			CollectionLogCollector.parseCollectionLogItemName("New item added to your collection log: Pet chaos elemental."));
	}

	@Test
	public void parseCollectionLogItemNameStripsTags()
	{
		assertEquals("Bandos chestplate",
			CollectionLogCollector.parseCollectionLogItemName("New item added to your collection log: <col=ef1020>Bandos chestplate</col>"));
	}

	@Test
	public void parseCollectionLogItemNameRejectsNonNotices()
	{
		assertNull(CollectionLogCollector.parseCollectionLogItemName("You feel something weird."));
		assertNull(CollectionLogCollector.parseCollectionLogItemName(null));
		// Prefix present but no name -> nothing to record.
		assertNull(CollectionLogCollector.parseCollectionLogItemName("New item added to your collection log: "));
	}

	@Test
	public void dropActivityConstantIsStable()
	{
		// The append-only activity the chat catch rides; the moment must never
		// enter the keyed collection-log stream.
		assertEquals("collection_log_drop", CollectionLogCollector.DROP_ACTIVITY);
	}
}
