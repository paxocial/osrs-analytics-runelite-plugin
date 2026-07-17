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
 * {@link #classify(String)}).
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

	private final Map<String, QuestStatus.State> lastState = new HashMap<>();
	private String lastRsn;
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
		// Reset the per-quest change baseline when the account changes, so one
		// account's quest states are never carried onto another.
		if (!rsn.equals(lastRsn))
		{
			lastRsn = rsn;
			lastState.clear();
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
		int questPoints = Math.max(0, client.getVarpValue(VarPlayerID.QP));
		for (Quest quest : Quest.values())
		{
			QuestStatus.State current = mapState(quest.getState(client));
			String name = quest.getName();
			QuestStatus.State previous = lastState.get(name);
			if (current == previous)
			{
				continue;
			}
			lastState.put(name, current);
			emit(rsn, name, current, questPoints);
		}
	}

	private void emit(String rsn, String questName, QuestStatus.State state, int questPoints)
	{
		QuestStatus payload = new QuestStatus();
		Payloads.base(payload, client, rsn);
		payload.questName = questName;
		payload.state = state;
		payload.questType = classify(questName);
		payload.questPoints = questPoints;
		analytics.enqueue(EventCategory.QUEST, payload);
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
}
