/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.SessionEvent;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.time.Instant;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Tracks session lifecycle: emits {@code login} when a valid player appears,
 * {@code world_hop} on world changes, and {@code logout} (with duration) when
 * the player leaves the game world.
 */
@Singleton
public class SessionCollector
{
	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	private boolean active;
	private String sessionId;
	private long loginEpochSeconds;
	private int lastWorld;

	@Inject
	public SessionCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	private boolean enabled()
	{
		return config.enabled() && config.trackSessions();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST || state == GameState.HOPPING)
		{
			if (state != GameState.HOPPING)
			{
				endSession();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!enabled())
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
		int world = client.getWorld();
		if (world < 300 || world > 600)
		{
			return;
		}
		if (!active)
		{
			startSession(rsn, world);
		}
		else if (world != lastWorld)
		{
			lastWorld = world;
			emit(rsn, world, SessionEvent.EventType.WORLD_HOP, null);
		}
	}

	private void startSession(String rsn, int world)
	{
		active = true;
		sessionId = UUID.randomUUID().toString();
		loginEpochSeconds = Instant.now().getEpochSecond();
		lastWorld = world;
		emit(rsn, world, SessionEvent.EventType.LOGIN, null);
	}

	private void endSession()
	{
		if (!active)
		{
			return;
		}
		active = false;
		if (!enabled())
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		int duration = (int) Math.max(0, Instant.now().getEpochSecond() - loginEpochSeconds);
		emit(rsn, lastWorld, SessionEvent.EventType.LOGOUT, duration);
		// WOM-style "update on logout": push the session end (and whatever else is
		// queued) promptly instead of waiting for the next periodic flush.
		analytics.flushNow();
	}

	private void emit(String rsn, int world, SessionEvent.EventType type, Integer durationSeconds)
	{
		SessionEvent event = new SessionEvent();
		Payloads.base(event, client, rsn);
		event.world = world; // session events require a world; use the validated value
		event.sessionId = sessionId;
		event.event = type;
		event.durationSeconds = durationSeconds;
		analytics.enqueue(EventCategory.SESSION, event);
	}
}
