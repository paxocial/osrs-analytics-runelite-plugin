/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.GeTradeCollector.GeSlotTracker;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link GeSlotTracker}: exactly one row per completed offer despite the many
 * fires GE emits (partial fills + login re-sync) — the named partial-fill dedup
 * Crucible target (I6) — and the account-switch re-arm (I4). Driven with plain
 * {@link GrandExchangeOfferState} constants, no live client.
 */
public class GeTradeCollectorTest
{
	private static final long ACC = 1L;

	@Test
	public void partialFillsThenBoughtEmitExactlyOnce()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertFalse("a partial fill is not a completed trade", t.shouldEmit(ACC, 0, GrandExchangeOfferState.BUYING));
		assertFalse(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BUYING));
		assertTrue("the completion emits exactly one row", t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
		// A re-synced/re-fired completed offer must not double-emit.
		assertFalse(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
		assertFalse(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
	}

	@Test
	public void aCompletedOfferReFiredThreeTimesYieldsExactlyOneTrade()
	{
		// Mutation-proof once-per-offer guard: break the emitted flag and this returns 3.
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		int emits = 0;
		for (int i = 0; i < 3; i++)
		{
			if (t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT))
			{
				emits++;
			}
		}
		assertEquals("a completed offer fired 3x must yield exactly one trade", 1, emits);
	}

	@Test
	public void soldPathIsSymmetric()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertFalse(t.shouldEmit(ACC, 1, GrandExchangeOfferState.SELLING));
		assertTrue(t.shouldEmit(ACC, 1, GrandExchangeOfferState.SOLD));
		assertFalse(t.shouldEmit(ACC, 1, GrandExchangeOfferState.SOLD));
	}

	@Test
	public void cancelledOffersNeverEmit()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertFalse(t.shouldEmit(ACC, 2, GrandExchangeOfferState.BUYING));
		assertFalse("a cancelled buy is not a trade", t.shouldEmit(ACC, 2, GrandExchangeOfferState.CANCELLED_BUY));
		assertFalse("a cancelled sell is not a trade", t.shouldEmit(ACC, 3, GrandExchangeOfferState.CANCELLED_SELL));
	}

	@Test
	public void aNewOfferInTheSameSlotEmitsAgainAfterTheSlotIsCleared()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertTrue(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
		assertFalse("collecting the offer clears the slot", t.shouldEmit(ACC, 0, GrandExchangeOfferState.EMPTY));
		assertTrue("a new offer completing in the same slot emits again",
			t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
	}

	@Test
	public void aFreshBuyingStateBetweenTwoCompletionsReArmsTheSlot()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertTrue(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
		assertFalse("a new offer placed in the slot re-arms it", t.shouldEmit(ACC, 0, GrandExchangeOfferState.BUYING));
		assertTrue(t.shouldEmit(ACC, 0, GrandExchangeOfferState.BOUGHT));
	}

	@Test
	public void accountSwitchReArmsEverySlot()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertTrue(t.shouldEmit(100L, 0, GrandExchangeOfferState.BOUGHT));
		assertFalse("the same completed offer must not re-emit for the same account",
			t.shouldEmit(100L, 0, GrandExchangeOfferState.BOUGHT));
		// Account B logs in on the same client: its first completion on the same slot must emit.
		assertTrue("account B's first completion must not be suppressed by account A's flag",
			t.shouldEmit(200L, 0, GrandExchangeOfferState.BOUGHT));
		assertFalse(t.shouldEmit(200L, 0, GrandExchangeOfferState.BOUGHT));
	}

	@Test
	public void unknownSlotIndicesAreIgnored()
	{
		GeSlotTracker t = new GeSlotTracker(GeTradeCollector.GE_SLOTS);
		assertFalse(t.shouldEmit(ACC, 99, GrandExchangeOfferState.BOUGHT));
		assertFalse(t.shouldEmit(ACC, -1, GrandExchangeOfferState.BOUGHT));
	}
}
