/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Guards the panel's text: the steward's voice, the honest relative age, and — the
 * one this product broke before — the local wall clock that is never labelled UTC.
 */
public class PanelTextTest
{
	// ------------------------------------------------------------------
	// Relative age.
	// ------------------------------------------------------------------

	@Test
	public void relativeAgeBuckets()
	{
		long now = 1_000_000_000_000L;
		assertEquals("never", PanelText.since(0L, now));
		assertEquals("just now", PanelText.since(now - 3_000L, now));
		assertEquals("30s ago", PanelText.since(now - 30_000L, now));
		assertEquals("5m ago", PanelText.since(now - 5 * 60_000L, now));
		assertEquals("2h ago", PanelText.since(now - 2 * 3_600_000L, now));
		assertEquals("3d ago", PanelText.since(now - 3 * 86_400_000L, now));
	}

	// ------------------------------------------------------------------
	// The clock — local, never UTC. THE timezone honesty guard.
	// ------------------------------------------------------------------

	/**
	 * MUTATION-PROVE (timezone honesty). The same instant renders as the operator's
	 * OWN wall clock in their zone — 11:05 UTC is 7:05 am in New York — and the label
	 * never says UTC. Format the moment in UTC while claiming local (the shipped bug)
	 * and the New York assertion reads "11:05 am" instead of "7:05 am" -> this fails.
	 */
	@Test
	public void clockIsTheLocalWallClockAndNeverLabelledUtc()
	{
		long epochMs = Instant.parse("2026-07-17T11:05:00Z").toEpochMilli();

		String newYork = PanelText.clock(epochMs, ZoneId.of("America/New_York")); // UTC-4 in July
		assertEquals("7:05 am", newYork);
		assertFalse("the local clock must never be labelled UTC", newYork.contains("utc"));

		String utc = PanelText.clock(epochMs, ZoneId.of("UTC"));
		assertEquals("11:05 am", utc);
		assertFalse("even in the UTC zone the label carries no 'utc' text", utc.contains("utc"));
	}

	@Test
	public void clockForAnUnhappenedMomentIsEmpty()
	{
		assertEquals("", PanelText.clock(0L, ZoneId.of("UTC")));
	}

	// ------------------------------------------------------------------
	// Connection lines — the steward's reading of the transport.
	// ------------------------------------------------------------------

	@Test
	public void connectionLinesAndTonesPerState()
	{
		assertConn(AnalyticsClient.State.OK, true, PanelText.Tone.OK, "The ledger is listening.");
		assertConn(AnalyticsClient.State.ERROR, true, PanelText.Tone.ERROR, "Can't reach the ledger — retrying.");
		assertConn(AnalyticsClient.State.AUTH_FAILED, true, PanelText.Tone.ERROR, "The key was refused.");
		assertConn(AnalyticsClient.State.RATE_LIMITED, true, PanelText.Tone.WARN, "Easing off for a moment.");
		assertConn(AnalyticsClient.State.UNREGISTERED, true, PanelText.Tone.WARN, "This name isn't on the books yet.");
		assertConn(AnalyticsClient.State.IDLE, true, PanelText.Tone.IDLE, "Nothing sent yet.");
	}

	@Test
	public void disabledOverridesEveryState()
	{
		// Telemetry off is its own fact, not a pretend-idle. Even an OK state, when the
		// master switch is off, reads "Telemetry is off." — the panel never implies it
		// is listening when it is not.
		PanelText.ConnectionView view = PanelText.connection(AnalyticsClient.State.OK, false);
		assertEquals(PanelText.Tone.OFF, view.tone);
		assertEquals("Telemetry is off.", view.line);
	}

	private static void assertConn(AnalyticsClient.State state, boolean enabled, PanelText.Tone tone, String line)
	{
		PanelText.ConnectionView view = PanelText.connection(state, enabled);
		assertEquals("tone for " + state, tone, view.tone);
		assertEquals("line for " + state, line, view.line);
	}

	// ------------------------------------------------------------------
	// Formatting.
	// ------------------------------------------------------------------

	@Test
	public void integerIsGroupSeparated()
	{
		assertEquals("0", PanelText.integer(0L));
		assertEquals("12,345", PanelText.integer(12_345L));
		assertEquals("1,234,567", PanelText.integer(1_234_567L));
	}

	@Test
	public void accountTypeLabels()
	{
		assertEquals("Main", PanelText.accountType(net.runelite.api.vars.AccountType.NORMAL));
		assertEquals("Ironman", PanelText.accountType(net.runelite.api.vars.AccountType.IRONMAN));
		assertEquals("Hardcore Ironman", PanelText.accountType(net.runelite.api.vars.AccountType.HARDCORE_IRONMAN));
		assertEquals("Ultimate Ironman", PanelText.accountType(net.runelite.api.vars.AccountType.ULTIMATE_IRONMAN));
		assertEquals("Group Ironman", PanelText.accountType(net.runelite.api.vars.AccountType.GROUP_IRONMAN));
		assertEquals("Hardcore Group Ironman",
			PanelText.accountType(net.runelite.api.vars.AccountType.HARDCORE_GROUP_IRONMAN));
		assertNull("an unknown account type adds nothing to the identity line",
			PanelText.accountType(null));
	}
}
