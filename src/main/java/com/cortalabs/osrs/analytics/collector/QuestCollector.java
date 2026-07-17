/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.QuestStatus;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Emits quest status. On first snapshot it sends every entry in the RuneLite
 * {@link Quest} enum — including {@code not_started} ones, so the backend knows
 * the full denominator (~208 rows, a one-time burst well under the transport's
 * 5000-event queue); afterwards it sends only quests whose state changed.
 *
 * <p>Each event also carries the account's current quest points
 * ({@code net.runelite.api.gameval.VarPlayerID.QP} = varp 101, reference L55;
 * legacy {@code net.runelite.api.VarPlayer.QUEST_POINTS}, L42) and a
 * {@code quest_type} classification. The RuneLite {@link Quest} enum mixes true
 * quests, miniquests, the ten Recipe for Disaster subquests, and Tutorial
 * Island with no type field, so the type is derived here (see
 * {@link #classify(String)}); the server manifest is authoritative and overrides
 * this guess at intake.
 *
 * <p><b>v2 identity + completion provenance.</b> Each event carries the quest's
 * stable {@code quest_id} ({@link Quest#getId()}), with the name demoted to a
 * display label. A completion is dated only when it was <i>witnessed</i>: when the
 * plugin sees a quest cross from a non-complete state into complete for the
 * account this session it emits {@code completion_provenance = live_witnessed}
 * with the observed {@code completed_at}; a quest already complete on the first
 * scan for the account is {@code walk_inferred} with no moment, because when it was
 * completed is genuinely unknown. This mirrors {@link CollectionLogCollector}'s
 * witnessed-vs-inferred split. Change detection and account scoping run through the
 * shared {@link AccountKeyedDeltaGuard}.
 */
@Singleton
public class QuestCollector
{
	private static final long SCAN_INTERVAL_MS = 30_000L;

	/**
	 * Miniquest names as they appear in the RuneLite {@link Quest} enum.
	 * Source: OSRS Wiki, https://oldschool.runescape.wiki/w/Miniquests
	 * (19 miniquests, list retrieved 2026-07-17). Names not in this set (and not
	 * matched by the subquest/other rules) default to {@code quest}, so a future
	 * miniquest missing here degrades to a mislabeled type, never a lost row.
	 */
	private static final Set<String> MINIQUESTS = new HashSet<>(Arrays.asList(
		"Alfred Grimhand's Barcrawl",
		"Enter the Abyss",
		"The General's Shadow",
		"Barbarian Training",
		"Skippy and the Mogres",
		"Curse of the Empty Lord",
		"Lair of Tarn Razorlor",
		"Bear Your Soul",
		"The Enchanted Key",
		"Mage Arena I",
		"Family Pest",
		"Mage Arena II",
		"In Search of Knowledge",
		"Daddy's Home",
		"The Frozen Door",
		"Hopespear's Will",
		"Into the Tombs",
		"His Faithful Servants",
		"Vale Totems"));

	/**
	 * The ten Recipe for Disaster subquests are enum entries of this form; the
	 * parent "Recipe for Disaster" (no trailing dash-segment) stays a quest.
	 */
	private static final String RFD_SUBQUEST_PREFIX = "Recipe for Disaster - ";

	/** In the quest journal but neither a quest nor a miniquest (no QP, not on wiki lists). */
	private static final String TUTORIAL_ISLAND = "Tutorial Island";

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	/** The single dedup/emit-gate: account-scoped change detection (the house pattern). */
	private final AccountKeyedDeltaGuard delta = new AccountKeyedDeltaGuard();
	/**
	 * Per-account provenance history: quest id -> last-seen state, read only to
	 * decide witnessed-vs-inferred completion. This is <b>not</b> a second dedup
	 * mechanism — {@link #delta} owns suppression — it is the previous-state lookup
	 * the transition test needs, which the guard does not expose. Cleared on account
	 * change in lockstep with the guard so one account's states can never witness a
	 * completion onto another. Mirrors CollectionLogCollector's pendingChatDrops
	 * sitting beside its dedup set.
	 */
	private final Map<String, QuestStatus.State> lastState = new HashMap<>();
	private long lastAccountHash = AccountKeyedDeltaGuard.NO_ACCOUNT;
	private long lastScanMs;

	@Inject
	public QuestCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackQuests())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastScanMs < SCAN_INTERVAL_MS)
		{
			return;
		}
		lastScanMs = nowMs;
		scan(rsn);
	}

	private void scan(String rsn)
	{
		long accountHash = client.getAccountHash();
		// Standardize account scoping on the rename-immune accountHash: forget the
		// previous account's last-seen quest states before evaluating this scan, so a
		// quest already complete for the new account is never mislabeled as a
		// witnessed completion off account A's stale state. Runs in lockstep with the
		// guard's own accountHash-keyed clear.
		lastAccountHash = clearHistoryIfAccountChanged(accountHash, lastAccountHash, lastState);

		int questPoints = Math.max(0, client.getVarpValue(VarPlayerID.QP));
		String now = Payloads.isoNow();
		for (Quest quest : Quest.values())
		{
			QuestStatus.State current = mapState(quest.getState(client));
			Completion completion = process(delta, lastState, accountHash, quest.getId(), current, now);
			if (completion == null)
			{
				continue; // unchanged since the last accepted emit — suppressed
			}
			emit(rsn, quest.getId(), quest.getName(), current, questPoints, completion);
		}
	}

	/**
	 * The whole per-quest emission pipeline, factored out of the client-driven
	 * {@link #scan} so it is testable with plain data: the {@link AccountKeyedDeltaGuard}
	 * gate (dedup + account-scoped clear) and the witnessed-vs-inferred provenance
	 * decision. Advances both the guard baseline and the provenance history when it
	 * emits. Package-private and client-free; mirrors the "take state as arguments"
	 * shape of {@link CollectionLogCollector#clearIfAccountChanged}.
	 *
	 * @return the completion to emit, or {@code null} when this quest is suppressed
	 *         as an unchanged re-report for the current account
	 */
	static Completion process(
		AccountKeyedDeltaGuard delta,
		Map<String, QuestStatus.State> lastState,
		long accountHash,
		int questId,
		QuestStatus.State current,
		String now)
	{
		String key = String.valueOf(questId);
		// Never suppress under an unknown account: an ambiguous identity must not let
		// a stale signature drop a real row (mirrors EquipmentCollector). Otherwise
		// the guard is the sole gate — first sighting of a quest for the account (the
		// first-scan census) and every genuine state change emit; a byte-identical
		// re-report is suppressed (the server also dedups — belt and suspenders).
		boolean emit = accountHash == AccountKeyedDeltaGuard.NO_ACCOUNT
			|| delta.changed(accountHash, key, current.name());
		if (!emit)
		{
			return null;
		}
		Completion completion = decideCompletion(lastState.get(key), current, now);
		lastState.put(key, current);
		return completion;
	}

	/**
	 * Decide a quest row's completion provenance from the state transition. A
	 * completion is {@link QuestStatus#PROVENANCE_LIVE_WITNESSED} — and only then
	 * carries a {@code completed_at} — exactly when the plugin saw the quest cross
	 * from a non-complete state into complete for this account during the session
	 * ({@code previous != null && previous != COMPLETE && current == COMPLETE}). A
	 * quest already complete on the first scan for the account ({@code previous ==
	 * null}) is {@link QuestStatus#PROVENANCE_WALK_INFERRED} with no moment: it is
	 * done, but when it was completed is unknown. Every non-complete row is likewise
	 * walk_inferred with no moment. Pure and client-free for direct testing; mirrors
	 * CollectionLogCollector.resolveCaptures' witnessed-vs-inferred split.
	 *
	 * @param now ISO-8601 instant to stamp on a witnessed completion (injected so the
	 *            decision is deterministic under test)
	 */
	static Completion decideCompletion(QuestStatus.State previous, QuestStatus.State current, String now)
	{
		if (current == QuestStatus.State.COMPLETE
			&& previous != null
			&& previous != QuestStatus.State.COMPLETE)
		{
			return Completion.liveWitnessed(now);
		}
		return Completion.walkInferred();
	}

	/**
	 * Forget the per-account provenance history when the logged-in account changes,
	 * so account A's last-seen quest states can never witness a completion onto
	 * account B (a quest already complete for B would be fabricated as
	 * {@code live_witnessed} off A's stale non-complete state). Returns the account
	 * to remember. Takes its state as an argument so the account-switch honesty rule
	 * is testable with plain data; mirrors CollectionLogCollector.clearIfAccountChanged.
	 *
	 * @return {@code accountHash}, to store as the new remembered account
	 */
	static long clearHistoryIfAccountChanged(
		long accountHash, long lastAccountHash, Map<String, QuestStatus.State> lastState)
	{
		if (accountHash != lastAccountHash)
		{
			lastState.clear();
		}
		return accountHash;
	}

	private void emit(String rsn, int questId, String questName, QuestStatus.State state, int questPoints, Completion completion)
	{
		QuestStatus payload = new QuestStatus();
		Payloads.base(payload, client, rsn);
		applyQuest(payload, questId, questName, state, questPoints, completion);
		analytics.enqueue(EventCategory.QUEST, payload);
	}

	/**
	 * Copy the resolved identity, state, and completion onto the outgoing payload.
	 * Split out from {@link #emit} and kept client-free so the v2 wire contract is
	 * directly testable: the {@code quest_id} is always carried, and
	 * {@code completed_at} is exactly the completion's moment — present iff the
	 * completion was witnessed, never a fallback clock. Mirrors
	 * CollectionLogCollector.applyCapture.
	 */
	static void applyQuest(
		QuestStatus payload,
		int questId,
		String questName,
		QuestStatus.State state,
		int questPoints,
		Completion completion)
	{
		payload.questId = questId;
		payload.questName = questName;
		payload.state = state;
		payload.questType = classify(questName);
		payload.questPoints = questPoints;
		payload.completionProvenance = completion.provenance;
		payload.completedAt = completion.completedAt;
	}

	/**
	 * Classify a {@link Quest} enum display name. Package-private for tests.
	 *
	 * @return {@code miniquest} for wiki-listed miniquests, {@code subquest} for
	 * the Recipe for Disaster sub-entries, {@code other} for Tutorial Island,
	 * else {@code quest} (the safe default for future additions).
	 */
	static String classify(String questName)
	{
		if (MINIQUESTS.contains(questName))
		{
			return "miniquest";
		}
		if (questName.startsWith(RFD_SUBQUEST_PREFIX))
		{
			return "subquest";
		}
		if (TUTORIAL_ISLAND.equals(questName))
		{
			return "other";
		}
		return "quest";
	}

	private static QuestStatus.State mapState(QuestState state)
	{
		if (state == null)
		{
			return QuestStatus.State.NOT_STARTED;
		}
		switch (state)
		{
			case FINISHED:
				return QuestStatus.State.COMPLETE;
			case IN_PROGRESS:
				return QuestStatus.State.IN_PROGRESS;
			default:
				return QuestStatus.State.NOT_STARTED;
		}
	}

	/**
	 * How one emitted quest row's completion was captured, and the witnessed moment
	 * when there is one. {@link #completedAt} is non-null if and only if
	 * {@link #provenance} is {@link QuestStatus#PROVENANCE_LIVE_WITNESSED} — the
	 * invariant the server's model validator also enforces. Mirrors
	 * CollectionLogCollector.Capture.
	 */
	static final class Completion
	{
		final String provenance;
		/** ISO-8601 witnessed moment; {@code null} for {@code walk_inferred}. */
		final String completedAt;

		private Completion(String provenance, String completedAt)
		{
			this.provenance = provenance;
			this.completedAt = completedAt;
		}

		static Completion liveWitnessed(String completedAt)
		{
			return new Completion(QuestStatus.PROVENANCE_LIVE_WITNESSED, completedAt);
		}

		static Completion walkInferred()
		{
			return new Completion(QuestStatus.PROVENANCE_WALK_INFERRED, null);
		}
	}
}
