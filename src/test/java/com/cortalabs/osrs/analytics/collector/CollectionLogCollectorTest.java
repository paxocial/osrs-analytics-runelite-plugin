/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.Capture;
import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.DrawnSlot;
import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.NamedSlot;
import com.cortalabs.osrs.analytics.collector.CollectionLogCollector.PageReduction;
import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.dto.CollectionPageSummary;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

	// ------------------------------------------------------------------
	// The chat -> walk join: obtained_at is a real moment or it is absent.
	// ------------------------------------------------------------------

	private static NamedSlot slot(int itemId, String itemName)
	{
		return new NamedSlot(itemId, itemName, 1);
	}

	@Test
	public void walkOnlyItemCannotClaimADate()
	{
		// THE defect this whole feature exists to kill: an item the plugin merely
		// found already-obtained has NO knowable acquisition time. Not the scrape
		// time, not zero, not epoch — absent. A years-old pet must never be datable
		// to the moment the player opened their log.
		Map<String, String> pending = new HashMap<>();

		Map<Integer, Capture> captures = CollectionLogCollector.resolveCaptures(
			Arrays.asList(slot(11832, "Bandos chestplate"), slot(11834, "Bandos tassets")), pending);

		for (Capture capture : captures.values())
		{
			assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, capture.provenance);
			assertNull("a walk-only item must not carry any obtained_at", capture.obtainedAt);
		}
	}

	@Test
	public void chatCaughtItemCarriesItsRealChatMoment()
	{
		// The other half: when the acquisition WAS witnessed, the row must carry the
		// chat timestamp itself — not the walk's clock, which may be hours later.
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");

		Map<Integer, Capture> captures = CollectionLogCollector.resolveCaptures(
			Arrays.asList(slot(11832, "Bandos chestplate"), slot(11834, "Bandos tassets")), pending);

		Capture witnessed = captures.get(11832);
		assertEquals(CollectionLogEntry.PROVENANCE_CHAT_OBSERVED, witnessed.provenance);
		assertEquals("2026-07-17T04:12:00Z", witnessed.obtainedAt);

		// ...and the moment does not bleed onto its neighbours on the same page.
		Capture neighbour = captures.get(11834);
		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, neighbour.provenance);
		assertNull(neighbour.obtainedAt);
	}

	@Test
	public void chatMomentIsConsumedSoItCannotStampASecondItem()
	{
		// One chat notice = one acquisition. Once spent, a later walk of another
		// category must not reuse the moment for a different item.
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");

		CollectionLogCollector.resolveCaptures(
			Collections.singletonList(slot(11832, "Bandos chestplate")), pending);
		assertTrue("matched chat moment must be consumed", pending.isEmpty());

		Map<Integer, Capture> second = CollectionLogCollector.resolveCaptures(
			Collections.singletonList(slot(11832, "Bandos chestplate")), pending);
		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, second.get(11832).provenance);
		assertNull(second.get(11832).obtainedAt);
	}

	@Test
	public void ambiguousNameMatchDatesNeitherItem()
	{
		// Two distinct ids sharing a display name, both newly obtained, one chat
		// moment: which one dropped is unknowable. Guessing is a 50/50 fabrication,
		// so NEITHER is dated — ambiguity resolves to absence, never to a guess.
		Map<String, String> pending = new HashMap<>();
		pending.put("Ancient shard", "2026-07-17T04:12:00Z");

		Map<Integer, Capture> captures = CollectionLogCollector.resolveCaptures(
			Arrays.asList(slot(19677, "Ancient shard"), slot(19678, "Ancient shard")), pending);

		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, captures.get(19677).provenance);
		assertNull(captures.get(19677).obtainedAt);
		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, captures.get(19678).provenance);
		assertNull(captures.get(19678).obtainedAt);
		assertTrue("an unattributable moment must not linger for a later page", pending.isEmpty());
	}

	@Test
	public void unmatchedChatMomentSurvivesForALaterWalk()
	{
		// The chat fired for an item this page does not carry (different category).
		// The moment must stay parked — the walk of ITS category may still come.
		Map<String, String> pending = new LinkedHashMap<>();
		pending.put("Pet chaos elemental", "2026-07-17T04:12:00Z");

		CollectionLogCollector.resolveCaptures(
			Collections.singletonList(slot(11832, "Bandos chestplate")), pending);

		assertEquals("2026-07-17T04:12:00Z", pending.get("Pet chaos elemental"));

		Map<Integer, Capture> later = CollectionLogCollector.resolveCaptures(
			Collections.singletonList(slot(11995, "Pet chaos elemental")), pending);
		assertEquals(CollectionLogEntry.PROVENANCE_CHAT_OBSERVED, later.get(11995).provenance);
		assertEquals("2026-07-17T04:12:00Z", later.get(11995).obtainedAt);
	}

	@Test
	public void everyCaptureIsEitherWitnessedWithATimeOrInferredWithout()
	{
		// The invariant the backend contract depends on: obtained_at is non-null if
		// and only if provenance is chat_observed. No third shape exists.
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");
		pending.put("Ancient shard", "2026-07-17T05:00:00Z");

		Map<Integer, Capture> captures = CollectionLogCollector.resolveCaptures(
			Arrays.asList(
				slot(11832, "Bandos chestplate"),
				slot(19677, "Ancient shard"),
				slot(19678, "Ancient shard"),
				slot(11834, "Bandos tassets")),
			pending);

		assertEquals(4, captures.size());
		for (Capture capture : captures.values())
		{
			if (CollectionLogEntry.PROVENANCE_CHAT_OBSERVED.equals(capture.provenance))
			{
				assertNotNull("chat_observed must carry its moment", capture.obtainedAt);
			}
			else
			{
				assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, capture.provenance);
				assertNull("walk_inferred must carry no moment", capture.obtainedAt);
			}
		}
	}

	@Test
	public void emitNeverSubstitutesTheWalkClockForAnUnknownMoment()
	{
		// Guards the exact line this bug was: emitObtained once did
		// `payload.obtainedAt = Payloads.isoNow()`. A capture with no moment must
		// reach the wire with no moment — no fallback clock, at any layer.
		CollectionLogEntry payload = new CollectionLogEntry();

		CollectionLogCollector.applyCapture(
			payload, slot(11832, "Bandos chestplate"), "General Graardor", Capture.walkInferred());

		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, payload.captureProvenance);
		assertNull("emit must not fall back to the walk's own clock", payload.obtainedAt);
		assertEquals(11832, payload.itemId);
		assertEquals("General Graardor", payload.source);
	}

	@Test
	public void emitCarriesTheWitnessedMomentVerbatim()
	{
		CollectionLogEntry payload = new CollectionLogEntry();

		CollectionLogCollector.applyCapture(
			payload, slot(11995, "Pet chaos elemental"), "Chaos Elemental",
			Capture.chatObserved("2026-07-17T04:12:00Z"));

		assertEquals(CollectionLogEntry.PROVENANCE_CHAT_OBSERVED, payload.captureProvenance);
		assertEquals("2026-07-17T04:12:00Z", payload.obtainedAt);
	}

	@Test
	public void prospectorLegsCaptureEmitsTheNamedObtainedItem()
	{
		// Prospector legs uses the same real-id named-slot path as every obtained
		// collection item; pin this concrete account-report signal against accidental
		// filtering or loss of the resolved display name.
		CollectionLogEntry payload = new CollectionLogEntry();

		CollectionLogCollector.applyCapture(
			payload, slot(12020, "Prospector legs"), "Motherlode Mine", Capture.walkInferred());

		assertEquals(12020, payload.itemId);
		assertEquals("Prospector legs", payload.itemName);
		assertEquals("Motherlode Mine", payload.source);
		assertEquals(CollectionLogEntry.PROVENANCE_WALK_INFERRED, payload.captureProvenance);
		assertNull("a log walk must not invent an acquisition time", payload.obtainedAt);
	}

	@Test
	public void emptyWalkConsumesNothing()
	{
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");

		Map<Integer, Capture> captures =
			CollectionLogCollector.resolveCaptures(Collections.emptyList(), pending);

		assertTrue(captures.isEmpty());
		assertFalse("an empty walk must not spend a pending moment", pending.isEmpty());
	}

	@Test
	public void switchingAccountDropsThePreviousAccountsPendingMoments()
	{
		// The pending map is keyed by item name only, so without this clear a drop
		// witnessed on account A would date the same item on account B — an
		// acquisition B never made. Per-RSN state, enforced on every handler entry.
		Set<String> synced = new HashSet<>(Collections.singletonList("General Graardor|11832"));
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");

		boolean cleared = CollectionLogCollector.clearIfAccountChanged("Bravo", "Alpha", synced, pending);

		assertTrue("an account change must clear per-account state", cleared);
		assertTrue("account A's moment must never survive to date account B's item", pending.isEmpty());
		assertTrue(synced.isEmpty());
	}

	@Test
	public void stayingOnOneAccountKeepsItsPendingMoments()
	{
		// The other side of the guard: a drop caught minutes before opening the log
		// must still be there when the walk arrives.
		Set<String> synced = new HashSet<>(Collections.singletonList("General Graardor|11832"));
		Map<String, String> pending = new HashMap<>();
		pending.put("Bandos chestplate", "2026-07-17T04:12:00Z");

		boolean cleared = CollectionLogCollector.clearIfAccountChanged("Alpha", "Alpha", synced, pending);

		assertFalse(cleared);
		assertEquals("2026-07-17T04:12:00Z", pending.get("Bandos chestplate"));
		assertEquals(1, synced.size());
	}

	@Test
	public void firstLoginOfASessionClearsNothingItDoesNotHave()
	{
		// lastRsn is null before the first login; the guard must treat that as a
		// change (and not NPE) rather than trusting stale state.
		Set<String> synced = new HashSet<>();
		Map<String, String> pending = new HashMap<>();

		assertTrue(CollectionLogCollector.clearIfAccountChanged("Alpha", null, synced, pending));
	}
}
