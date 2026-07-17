/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.QuestCollector.Completion;
import com.cortalabs.osrs.analytics.dto.QuestStatus;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Guards the pure emission decisions the QuestCollector delegates to: the v2
 * identity {@code quest_id} is always carried, a completion is dated only when it
 * was witnessed, the {@link AccountKeyedDeltaGuard} is the sole suppression gate
 * (so the first-scan census is never dropped), and account switching forgets the
 * previous account's states so a completion is never fabricated onto a new
 * account. The full collector is client-driven; these pin the client-free helpers
 * that carry the wire contract, in the shape of {@code CollectionLogCollectorTest}.
 */
public class QuestCollectorTest
{
	private static final long ACCT_A = 100L;
	private static final long ACCT_B = 200L;
	private static final String NOW = "2026-07-17T14:12:00Z";

	// ------------------------------------------------------------------
	// Identity: quest_id is always on the wire.
	// ------------------------------------------------------------------

	@Test
	public void questIdIsAlwaysCarried()
	{
		QuestStatus payload = new QuestStatus();
		QuestCollector.applyQuest(payload, 210, "Dragon Slayer II", QuestStatus.State.COMPLETE, 6,
			Completion.walkInferred());
		assertEquals("the v2 identity quest_id must reach the payload",
			Integer.valueOf(210), payload.questId);
		assertEquals("the name stays as the display label", "Dragon Slayer II", payload.questName);
		assertEquals("the plugin type hint is still emitted", "quest", payload.questType);
	}

	// ------------------------------------------------------------------
	// Provenance: dated iff witnessed. THE honesty split.
	// ------------------------------------------------------------------

	/**
	 * MUTATION-PROVE (the witnessed branch). A completion is live_witnessed with a
	 * moment ONLY when a non-complete state was seen crossing into complete this
	 * session. Break the transition guard in decideCompletion (drop the
	 * {@code previous != COMPLETE} term, or return walkInferred unconditionally) and
	 * one of these assertions flips -> the test fails, proving the transition is what
	 * dates a completion.
	 */
	@Test
	public void witnessedTransitionIsLiveWitnessedWithTheObservedMoment()
	{
		Completion fromInProgress = QuestCollector.decideCompletion(
			QuestStatus.State.IN_PROGRESS, QuestStatus.State.COMPLETE, NOW);
		assertEquals(QuestStatus.PROVENANCE_LIVE_WITNESSED, fromInProgress.provenance);
		assertEquals("a witnessed completion carries the observed moment", NOW, fromInProgress.completedAt);

		Completion fromNotStarted = QuestCollector.decideCompletion(
			QuestStatus.State.NOT_STARTED, QuestStatus.State.COMPLETE, NOW);
		assertEquals(QuestStatus.PROVENANCE_LIVE_WITNESSED, fromNotStarted.provenance);
		assertEquals(NOW, fromNotStarted.completedAt);
	}

	/**
	 * MUTATION-PROVE (the inferred branch — the honesty guarantee). A quest already
	 * complete on the FIRST scan for the account (previous == null) is walk_inferred
	 * with NO moment: it is done, but when is unknown. Make decideCompletion treat a
	 * null previous as witnessed and this flips -> the plugin would date a years-old
	 * completion to the moment it first looked.
	 */
	@Test
	public void alreadyCompleteOnFirstScanIsWalkInferredWithNoMoment()
	{
		Completion c = QuestCollector.decideCompletion(null, QuestStatus.State.COMPLETE, NOW);
		assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, c.provenance);
		assertNull("a completion found on first sight has no knowable moment", c.completedAt);
	}

	@Test
	public void nonCompletionStatesAreWalkInferredWithNoMoment()
	{
		for (QuestStatus.State current : new QuestStatus.State[]{
			QuestStatus.State.NOT_STARTED, QuestStatus.State.IN_PROGRESS})
		{
			Completion firstSight = QuestCollector.decideCompletion(null, current, NOW);
			assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, firstSight.provenance);
			assertNull(firstSight.completedAt);

			Completion afterChange = QuestCollector.decideCompletion(
				QuestStatus.State.NOT_STARTED, current, NOW);
			assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, afterChange.provenance);
			assertNull(afterChange.completedAt);
		}
	}

	@Test
	public void staleCompleteIsNotReWitnessed()
	{
		// A re-scan of an already-witnessed completion (previous already COMPLETE) is
		// not a fresh transition, so it must not manufacture a new moment.
		Completion c = QuestCollector.decideCompletion(
			QuestStatus.State.COMPLETE, QuestStatus.State.COMPLETE, NOW);
		assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, c.provenance);
		assertNull(c.completedAt);
	}

	@Test
	public void completionInvariantHolds()
	{
		// completed_at is non-null if and only if provenance is live_witnessed — the
		// invariant the server model validator also enforces. No third shape exists.
		Completion witnessed = Completion.liveWitnessed(NOW);
		assertEquals(QuestStatus.PROVENANCE_LIVE_WITNESSED, witnessed.provenance);
		assertNotNull("a witnessed completion must carry its moment", witnessed.completedAt);

		Completion inferred = Completion.walkInferred();
		assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, inferred.provenance);
		assertNull("an inferred completion must carry no moment", inferred.completedAt);
	}

	@Test
	public void applyQuestCarriesTheWitnessedMomentVerbatim()
	{
		QuestStatus payload = new QuestStatus();
		QuestCollector.applyQuest(payload, 210, "Dragon Slayer II", QuestStatus.State.COMPLETE, 6,
			Completion.liveWitnessed(NOW));
		assertEquals(QuestStatus.PROVENANCE_LIVE_WITNESSED, payload.completionProvenance);
		assertEquals(NOW, payload.completedAt);
		assertEquals(Integer.valueOf(210), payload.questId);
	}

	@Test
	public void applyQuestNeverSubstitutesAClockForAnInferredCompletion()
	{
		// The quest analogue of the collection-log fallback-clock bug: an inferred
		// completion must reach the wire with no completed_at, no fallback clock.
		QuestStatus payload = new QuestStatus();
		QuestCollector.applyQuest(payload, 29, "Cook's Assistant", QuestStatus.State.COMPLETE, 1,
			Completion.walkInferred());
		assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, payload.completionProvenance);
		assertNull("emit must not fall back to the scan clock", payload.completedAt);
	}

	// ------------------------------------------------------------------
	// The guard pipeline: dedup, census intact, account scoping.
	// ------------------------------------------------------------------

	@Test
	public void firstScanCensusEmitsEveryQuestAndNoneAreSuppressed()
	{
		// Adopting the guard must NOT suppress the first-sync burst: every quest is a
		// first observation for the account, so every one emits. An already-complete
		// quest in that burst is walk_inferred (there was no transition to witness).
		AccountKeyedDeltaGuard delta = new AccountKeyedDeltaGuard();
		Map<String, QuestStatus.State> history = new HashMap<>();

		Completion q1 = QuestCollector.process(delta, history, ACCT_A, 1, QuestStatus.State.COMPLETE, NOW);
		Completion q2 = QuestCollector.process(delta, history, ACCT_A, 2, QuestStatus.State.IN_PROGRESS, NOW);
		Completion q3 = QuestCollector.process(delta, history, ACCT_A, 3, QuestStatus.State.NOT_STARTED, NOW);

		assertNotNull("first-scan census must emit an already-complete quest", q1);
		assertNotNull("first-scan census must emit an in-progress quest", q2);
		assertNotNull("first-scan census must emit a not-started quest", q3);
		assertEquals("already-complete on the census is inferred, not witnessed",
			QuestStatus.PROVENANCE_WALK_INFERRED, q1.provenance);
		assertNull(q1.completedAt);
	}

	@Test
	public void identicalReReportIsSuppressed()
	{
		AccountKeyedDeltaGuard delta = new AccountKeyedDeltaGuard();
		Map<String, QuestStatus.State> history = new HashMap<>();
		assertNotNull(QuestCollector.process(delta, history, ACCT_A, 7, QuestStatus.State.COMPLETE, NOW));
		assertNull("an unchanged re-report must be suppressed by the guard",
			QuestCollector.process(delta, history, ACCT_A, 7, QuestStatus.State.COMPLETE, NOW));
	}

	@Test
	public void aCompletionIsWitnessedExactlyOnceThenSuppressed()
	{
		AccountKeyedDeltaGuard delta = new AccountKeyedDeltaGuard();
		Map<String, QuestStatus.State> history = new HashMap<>();

		// First seen in progress: emitted, but not a completion.
		Completion inProgress = QuestCollector.process(delta, history, ACCT_A, 42, QuestStatus.State.IN_PROGRESS, NOW);
		assertNotNull(inProgress);
		assertEquals(QuestStatus.PROVENANCE_WALK_INFERRED, inProgress.provenance);

		// The witnessed transition into complete.
		Completion complete = QuestCollector.process(delta, history, ACCT_A, 42, QuestStatus.State.COMPLETE, NOW);
		assertNotNull(complete);
		assertEquals(QuestStatus.PROVENANCE_LIVE_WITNESSED, complete.provenance);
		assertEquals(NOW, complete.completedAt);

		// The next identical scan is suppressed — the completion is emitted once.
		assertNull("a re-scan of the witnessed completion must be suppressed",
			QuestCollector.process(delta, history, ACCT_A, 42, QuestStatus.State.COMPLETE, NOW));
	}

	/**
	 * MUTATION-PROVE (account-switch fabrication guard). Account A left quest 42 in
	 * progress; account B has it already complete on B's first scan. Remove the
	 * clear in clearHistoryIfAccountChanged and B's already-complete quest reads
	 * A's stale IN_PROGRESS as its previous -> decideCompletion returns
	 * live_witnessed with a moment: a completion B never made, dated to now. With the
	 * clear, B's first sight is honestly walk_inferred with no moment.
	 */
	@Test
	public void accountSwitchClearsProvenanceHistorySoTheNewAccountIsNotFabricated()
	{
		Map<String, QuestStatus.State> history = new HashMap<>();
		history.put("42", QuestStatus.State.IN_PROGRESS); // account A's last-seen state

		long remembered = QuestCollector.clearHistoryIfAccountChanged(ACCT_B, ACCT_A, history);
		assertEquals(ACCT_B, remembered);
		assertNull("account A's state must not survive into account B", history.get("42"));

		Completion b = QuestCollector.decideCompletion(history.get("42"), QuestStatus.State.COMPLETE, NOW);
		assertEquals("B's already-complete quest is inferred, never fabricated as witnessed",
			QuestStatus.PROVENANCE_WALK_INFERRED, b.provenance);
		assertNull(b.completedAt);
	}

	@Test
	public void stayingOnOneAccountKeepsItsProvenanceHistorySoAGenuineTransitionWitnesses()
	{
		Map<String, QuestStatus.State> history = new HashMap<>();
		history.put("42", QuestStatus.State.IN_PROGRESS);

		long remembered = QuestCollector.clearHistoryIfAccountChanged(ACCT_A, ACCT_A, history);
		assertEquals(ACCT_A, remembered);
		assertEquals("the same account's in-session history must survive",
			QuestStatus.State.IN_PROGRESS, history.get("42"));

		Completion c = QuestCollector.decideCompletion(history.get("42"), QuestStatus.State.COMPLETE, NOW);
		assertEquals("a genuine in-session transition is witnessed",
			QuestStatus.PROVENANCE_LIVE_WITNESSED, c.provenance);
		assertEquals(NOW, c.completedAt);
	}

	@Test
	public void accountSwitchDoesNotSuppressTheNewAccountsFirstObservation()
	{
		// The guard side of the switch: account B's genuinely-first observation must
		// emit even when its state string is identical to A's suppressed one.
		AccountKeyedDeltaGuard delta = new AccountKeyedDeltaGuard();
		Map<String, QuestStatus.State> history = new HashMap<>();
		assertNotNull(QuestCollector.process(delta, history, ACCT_A, 42, QuestStatus.State.COMPLETE, NOW));
		assertNull(QuestCollector.process(delta, history, ACCT_A, 42, QuestStatus.State.COMPLETE, NOW));
		assertNotNull("account B's first observation must emit even with an identical state",
			QuestCollector.process(delta, history, ACCT_B, 42, QuestStatus.State.COMPLETE, NOW));
	}
}
