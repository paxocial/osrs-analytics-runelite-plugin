/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.ActivityUpdate;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Lightweight, heuristic activity signal. Emits an {@code region_change} update
 * when the player's map region changes (throttled). This is deliberately coarse
 * and clearly labelled as a heuristic rather than a precise activity classifier.
 */
@Singleton
public class ActivityCollector
{
	private static final long THROTTLE_MS = 10_000L;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	private int lastRegion = -1;
	private long lastEmitMs;

	@Inject
	public ActivityCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackActivity())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		WorldPoint location = player.getWorldLocation();
		if (location == null)
		{
			return;
		}
		int region = location.getRegionID();
		if (region == lastRegion)
		{
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastEmitMs < THROTTLE_MS)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		lastRegion = region;
		lastEmitMs = nowMs;

		ActivityUpdate payload = new ActivityUpdate();
		Payloads.base(payload, client, rsn);
		payload.activity = "region_change";
		payload.detail = "region:" + region + " (heuristic)";
		analytics.enqueue(EventCategory.ACTIVITY, payload);
	}
}
