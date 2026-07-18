/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.SessionLedger.SkillGain;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.vars.AccountType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Guards the panel's honesty gate — the one place display truth is decided. Nothing
 * defaults to zero, no name is invented, an unread progression stays absent rather than
 * reading as a real reading of nothing, and the events counter carries its real meaning.
 * This is the pure test of the product's signature failure (a value that answers a
 * different question than its label).
 */
public class PanelModelTest
{
	private static final String VERSION = "1.3.0";
	private static final List<SkillGain> NO_GAINS = Collections.emptyList();

	// ------------------------------------------------------------------
	// No account — honestly absent, never fabricated.
	// ------------------------------------------------------------------

	/**
	 * MUTATION-PROVE (identity honesty). With no account witnessed, the name, type and
	 * per-skill gains are dropped even when raw values are passed in. Leak the rsn
	 * through when presence is NONE and this reads "Zezima" for a name nobody has
	 * witnessed -> fails.
	 */
	@Test
	public void noAccountDropsNameTypeSessionAndGains()
	{
		PanelModel model = PanelModel.of(
			AnalyticsClient.State.IDLE, true, 0, 0L, 0L, "http://localhost:8000",
			AccountPresence.NONE, "Zezima", AccountType.IRONMAN,
			999_999L, 9, Arrays.asList(new SkillGain("Mining", 180L)),
			ProgressionReading.of(225, 114, 37, 48), VERSION);

		assertEquals(AccountPresence.NONE, model.presence);
		assertNull("no account witnessed: the name is not invented", model.rsn);
		assertNull("no account witnessed: the type is not invented", model.accountType);
		assertFalse("the session is not 'known' before an account is seen", model.sessionKnown);
		assertTrue("no account witnessed: no per-skill gains are shown", model.skillGains.isEmpty());
	}

	// ------------------------------------------------------------------
	// Progression — absent is not zero. THE signature-failure guard.
	// ------------------------------------------------------------------

	/**
	 * MUTATION-PROVE (never-zero progression). An unread progression stays
	 * {@link ProgressionReading#UNKNOWN} — {@code known == false} — so the panel renders
	 * "Not witnessed yet.", never "0 QP". Default a null progression to a zeroed KNOWN
	 * reading and {@code known} flips true -> the panel would show zeros it never read,
	 * and this fails.
	 */
	@Test
	public void unreadProgressionStaysAbsentNeverZero()
	{
		PanelModel fromNull = PanelModel.of(
			AnalyticsClient.State.OK, true, 0, 0L, 0L, "u",
			AccountPresence.LIVE, "Zezima", AccountType.NORMAL, 0L, 0, NO_GAINS, null, VERSION);
		assertSame("a null reading becomes the shared UNKNOWN sentinel",
			ProgressionReading.UNKNOWN, fromNull.progression);
		assertFalse("absent progression is not a reading of zero", fromNull.progression.known);

		PanelModel fromUnknown = PanelModel.of(
			AnalyticsClient.State.OK, true, 0, 0L, 0L, "u",
			AccountPresence.LIVE, "Zezima", AccountType.NORMAL, 0L, 0,
			NO_GAINS, ProgressionReading.UNKNOWN, VERSION);
		assertFalse(fromUnknown.progression.known);
	}

	// ------------------------------------------------------------------
	// The events counter carries its real meaning; witnessed values pass through.
	// ------------------------------------------------------------------

	@Test
	public void witnessedValuesAndEventsCounterPassThrough()
	{
		List<SkillGain> gains = Arrays.asList(new SkillGain("Mining", 180L), new SkillGain("Fishing", 40L));
		PanelModel model = PanelModel.of(
			AnalyticsClient.State.OK, true, 3, 123_456_789L, 228L, "http://localhost:8000",
			AccountPresence.LIVE, "Zezima", AccountType.NORMAL,
			5000L, 3, gains, ProgressionReading.of(225, 114, 37, 48), VERSION);

		assertEquals(PanelText.Tone.OK, model.connectionTone);
		assertEquals("The ledger is listening.", model.connectionLine);
		assertEquals("http://localhost:8000", model.backendUrl);
		assertEquals(3, model.queueDepth);
		assertEquals(123_456_789L, model.lastAcceptedMs);

		assertEquals("Zezima", model.rsn);
		assertEquals("Main", model.accountType);
		assertTrue(model.sessionKnown);
		assertEquals(5000L, model.xpGained);
		assertEquals(3, model.skillsAdvanced);
		assertEquals("the events counter is the transport's real accepted count, not a snapshot count",
			228L, model.eventsSent);
		assertEquals("the per-skill breakdown passes through in order", 2, model.skillGains.size());
		assertEquals("Mining", model.skillGains.get(0).skill);
		assertEquals(180L, model.skillGains.get(0).gained);

		assertTrue(model.progression.known);
		assertEquals(225, model.progression.questPoints);
		assertEquals(114, model.progression.questsComplete);
		assertEquals(37, model.progression.diaryTiersComplete);
		assertEquals(48, model.progression.diaryTiersTotal);

		assertEquals(VERSION, model.version);
	}

	// ------------------------------------------------------------------
	// Away keeps what was witnessed; connection reflects transport truth.
	// ------------------------------------------------------------------

	@Test
	public void awayKeepsTheWitnessedIdentity()
	{
		PanelModel model = PanelModel.of(
			AnalyticsClient.State.OK, true, 0, 10L, 1L, "u",
			AccountPresence.AWAY, "Zezima", AccountType.HARDCORE_IRONMAN,
			5000L, 3, NO_GAINS, ProgressionReading.of(30, 5, 2, 48), VERSION);

		assertEquals(AccountPresence.AWAY, model.presence);
		assertEquals("a witnessed account stays named after logout", "Zezima", model.rsn);
		assertEquals("Hardcore Ironman", model.accountType);
		assertTrue("a session that saw an account is still known after logout", model.sessionKnown);
	}

	@Test
	public void connectionReflectsTransportStateAndDisabledOverrides()
	{
		PanelModel auth = PanelModel.of(
			AnalyticsClient.State.AUTH_FAILED, true, 0, 0L, 0L, "u",
			AccountPresence.LIVE, "Zezima", AccountType.NORMAL, 0L, 0, NO_GAINS, ProgressionReading.UNKNOWN, VERSION);
		assertEquals(PanelText.Tone.ERROR, auth.connectionTone);
		assertEquals("The key was refused.", auth.connectionLine);

		PanelModel off = PanelModel.of(
			AnalyticsClient.State.OK, false, 0, 0L, 0L, "u",
			AccountPresence.LIVE, "Zezima", AccountType.NORMAL, 0L, 0, NO_GAINS, ProgressionReading.UNKNOWN, VERSION);
		assertEquals("telemetry off overrides an OK state", PanelText.Tone.OFF, off.connectionTone);
		assertEquals("Telemetry is off.", off.connectionLine);
	}

	@Test
	public void nullBackendUrlBecomesEmpty()
	{
		PanelModel model = PanelModel.of(
			AnalyticsClient.State.IDLE, true, 0, 0L, 0L, null,
			AccountPresence.NONE, null, null, 0L, 0, NO_GAINS, ProgressionReading.UNKNOWN, VERSION);
		assertEquals("", model.backendUrl);
	}
}
