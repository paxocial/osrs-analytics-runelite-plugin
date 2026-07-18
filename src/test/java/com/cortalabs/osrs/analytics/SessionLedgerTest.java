/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Guards the session XP ledger's one job: report only XP it actually witnessed climb.
 * The FIRST sight of a skill is a baseline, never a gain (or the panel would fabricate
 * a session's worth of XP the instant it opened); a DOWNWARD reading re-baselines
 * rather than reporting negative progress; and a new account's ledger starts clean.
 */
public class SessionLedgerTest
{
	// ------------------------------------------------------------------
	// Baseline vs gain — THE honesty split.
	// ------------------------------------------------------------------

	/**
	 * MUTATION-PROVE (the baseline rule). The first time a skill is seen, we are only
	 * learning where it already stood — that is a gain of zero. Make the first
	 * observation count as gain from zero and xpGained jumps to the skill's whole total
	 * -> this fails, proving the baseline is never counted as session progress.
	 */
	@Test
	public void firstObservationIsBaselineNotGain()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("attack", 1_000_000L);
		assertEquals("first sight of a skill sets a baseline, not a gain", 0L, ledger.xpGained());
		assertEquals("nothing has been witnessed climbing yet", 0, ledger.skillsAdvanced());
	}

	@Test
	public void subsequentGainAccruesFromTheBaseline()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("attack", 1000L); // baseline
		ledger.observe("attack", 1500L); // +500 witnessed
		assertEquals(500L, ledger.xpGained());
		assertEquals(1, ledger.skillsAdvanced());
	}

	@Test
	public void multipleSkillsAreSummedAndCounted()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("attack", 1000L);   // baselines
		ledger.observe("magic", 2000L);
		ledger.observe("attack", 1500L);   // +500
		ledger.observe("magic", 2100L);    // +100
		assertEquals(600L, ledger.xpGained());
		assertEquals("two distinct skills witnessed climbing", 2, ledger.skillsAdvanced());
	}

	/**
	 * MUTATION-PROVE (the downward-reading rule). An account switch or client de-sync
	 * can report a LOWER total for a skill than the baseline. That is not negative
	 * progress — the ledger re-baselines and reports zero, never a minus. Drop the
	 * {@code xp < base} re-baseline branch and this reports negative "gain" -> fails.
	 */
	@Test
	public void aDownwardReadingRebaselinesRatherThanReportingNegative()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("attack", 1000L);
		ledger.observe("attack", 50L); // a lower total: re-baseline, not -950
		assertEquals("a lower reading is a new baseline, never negative gain", 0L, ledger.xpGained());
		assertEquals(0, ledger.skillsAdvanced());

		ledger.observe("attack", 100L); // now +50 from the new baseline
		assertEquals(50L, ledger.xpGained());
		assertEquals(1, ledger.skillsAdvanced());
	}

	@Test
	public void reobservingTheSameTotalIsNoGain()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("cooking", 5000L);
		ledger.observe("cooking", 5000L);
		assertEquals(0L, ledger.xpGained());
		assertEquals(0, ledger.skillsAdvanced());
	}

	@Test
	public void resetForgetsTheSessionSoFar()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe("attack", 1000L);
		ledger.observe("attack", 9000L);
		assertEquals(8000L, ledger.xpGained());

		ledger.reset();
		assertEquals("a fresh account's ledger starts clean", 0L, ledger.xpGained());
		assertEquals(0, ledger.skillsAdvanced());

		// After reset, the next sight of a skill is again a baseline, not a gain.
		ledger.observe("attack", 9000L);
		assertEquals(0L, ledger.xpGained());
	}

	@Test
	public void nullSkillIsIgnored()
	{
		SessionLedger ledger = new SessionLedger();
		ledger.observe(null, 1234L);
		assertEquals(0L, ledger.xpGained());
		assertEquals(0, ledger.skillsAdvanced());
	}
}
