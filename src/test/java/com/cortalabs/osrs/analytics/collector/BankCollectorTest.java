/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the bank debounce state machine: a change suppressed inside the
 * debounce window must be FLUSHED after the window (trailing edge), never
 * dropped. Dropping it loses the final bank state before the interface closes
 * — exactly the state a bank-delta comparison needs (mission 2026-07-24).
 * Mutation-proof: reverting to the old leading-edge-only debounce makes
 * {@link #suppressedChangeIsFlushedAfterTheWindow} fail.
 */
public class BankCollectorTest
{
	private static BankCollector collector()
	{
		// The state-machine seams never touch the injected collaborators.
		return new BankCollector(null, null, null, null);
	}

	@Test
	public void firstChangeSendsImmediately()
	{
		BankCollector c = collector();
		assertTrue("the first observed change sends", c.recordChange(10_000L));
		assertFalse("nothing pending after an immediate send", c.shouldFlushPending(10_000L));
	}

	@Test
	public void suppressedChangeIsFlushedAfterTheWindow()
	{
		BankCollector c = collector();
		assertTrue(c.recordChange(10_000L));
		assertFalse("inside the window the change is suppressed", c.recordChange(11_000L));
		assertFalse("no flush while the window is open", c.shouldFlushPending(14_900L));
		assertTrue("the suppressed final state flushes after the window", c.shouldFlushPending(15_000L));
	}

	@Test
	public void immediateSendClearsAnEarlierPendingFlag()
	{
		BankCollector c = collector();
		assertTrue(c.recordChange(10_000L));
		assertFalse(c.recordChange(11_000L));
		assertTrue("a post-window change sends directly", c.recordChange(16_000L));
		assertFalse("the direct send already carries the latest state", c.shouldFlushPending(30_000L));
	}

	@Test
	public void quietBankNeverFlushes()
	{
		BankCollector c = collector();
		assertTrue(c.recordChange(10_000L));
		assertFalse("no suppressed change means no trailing send", c.shouldFlushPending(60_000L));
	}

	// -------------------------------------------------------------------
	// Duplicate suppression: canonical content, never timing or identity
	// (mission 2026-07-24 required tests 7-9).
	// -------------------------------------------------------------------

	private static java.util.List<com.cortalabs.osrs.analytics.dto.ItemEntry> items(int... idQtyPairs)
	{
		java.util.List<com.cortalabs.osrs.analytics.dto.ItemEntry> out = new java.util.ArrayList<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			out.add(new com.cortalabs.osrs.analytics.dto.ItemEntry(idQtyPairs[i], idQtyPairs[i + 1]));
		}
		return out;
	}

	@Test
	public void changedStateIsNeverDiscardedByDeduplication()
	{
		BankCollector c = collector();
		assertTrue(c.shouldSendContent(BankCollector.contentKey(items(995, 100))));
		assertTrue("a quantity change always sends",
			c.shouldSendContent(BankCollector.contentKey(items(995, 250))));
		assertTrue("a new item always sends",
			c.shouldSendContent(BankCollector.contentKey(items(995, 250, 4151, 1))));
	}

	@Test
	public void identicalStateIsSafelyDeduplicatedWithinASession()
	{
		BankCollector c = collector();
		assertTrue(c.shouldSendContent(BankCollector.contentKey(items(995, 100, 4151, 1))));
		assertFalse("identical canonical content is a pointless duplicate",
			c.shouldSendContent(BankCollector.contentKey(items(995, 100, 4151, 1))));
		// Order independence: the SAME holdings in another walk order are the
		// same canonical content, not a change.
		assertFalse("content identity ignores enumeration order",
			c.shouldSendContent(BankCollector.contentKey(items(4151, 1, 995, 100))));
	}

	@Test
	public void openingTheBankReArmsOneFullReWitness()
	{
		BankCollector c = collector();
		assertTrue(c.shouldSendContent(BankCollector.contentKey(items(995, 100))));
		assertFalse(c.shouldSendContent(BankCollector.contentKey(items(995, 100))));
		c.newBankSession();
		assertTrue("a new bank visit re-witnesses even an identical bank — the "
			+ "server needs a DISTINCT observation to claim 'unchanged'",
			c.shouldSendContent(BankCollector.contentKey(items(995, 100))));
	}

	@Test
	public void restartFabricatesNothing()
	{
		BankCollector c = collector();
		// A fresh (restarted) collector has witnessed nothing: no pending
		// flush at any time, so no fabricated full-bank acquisition can be
		// sent before a real bank event occurs.
		assertFalse(c.shouldFlushPending(0L));
		assertFalse(c.shouldFlushPending(Long.MAX_VALUE));
	}
}
