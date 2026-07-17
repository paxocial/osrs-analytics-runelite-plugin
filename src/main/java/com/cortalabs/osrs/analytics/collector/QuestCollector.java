/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.QuestStatus;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Emits quest status. On first snapshot it sends every started/completed quest
 * (unstarted quests are skipped to avoid a flood of no-op rows); afterwards it
 * sends only quests whose state changed.
 */
@Singleton
public class QuestCollector
{
	private static final long SCAN_INTERVAL_MS = 30_000L;

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
		for (Quest quest : Quest.values())
		{
			QuestStatus.State current = mapState(quest.getState(client));
			String name = quest.getName();
			QuestStatus.State previous = lastState.get(name);
			if (current == previous)
			{
				continue;
			}
			boolean firstSeen = previous == null;
			lastState.put(name, current);
			if (firstSeen && current == QuestStatus.State.NOT_STARTED)
			{
				// Seed baseline without emitting a no-op row.
				continue;
			}
			emit(rsn, name, current);
		}
	}

	private void emit(String rsn, String questName, QuestStatus.State state)
	{
		QuestStatus payload = new QuestStatus();
		Payloads.base(payload, client, rsn);
		payload.questName = questName;
		payload.state = state;
		analytics.enqueue(EventCategory.QUEST, payload);
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
