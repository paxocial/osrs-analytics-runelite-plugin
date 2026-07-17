/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Periodically snapshots XP for all 24 OSRS skills.
 *
 * <p>The map is seeded with every required skill at 0 and then overlaid from
 * {@link Skill#values()}, so a client build that does not yet know a skill
 * (e.g. {@code sailing}) still yields a contract-complete payload without a
 * compile-time dependency on that enum constant.
 */
@Singleton
public class XpCollector
{
	/** The 24 skills the backend requires, in contract order. */
	private static final List<String> REQUIRED_SKILLS = Arrays.asList(
		"attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction", "sailing");

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	private long lastSnapshotMs;

	@Inject
	public XpCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackXp())
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
		long intervalMs = Math.max(1, config.xpSnapshotMinutes()) * 60_000L;
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastSnapshotMs < intervalMs)
		{
			return;
		}
		lastSnapshotMs = nowMs;
		snapshot(rsn);
	}

	private void snapshot(String rsn)
	{
		Map<String, Integer> skills = new LinkedHashMap<>();
		for (String name : REQUIRED_SKILLS)
		{
			skills.put(name, 0);
		}
		for (Skill skill : Skill.values())
		{
			String key = skill.getName().toLowerCase(Locale.ROOT);
			if (skills.containsKey(key))
			{
				skills.put(key, client.getSkillExperience(skill));
			}
		}

		XpSnapshot payload = new XpSnapshot();
		Payloads.base(payload, client, rsn);
		payload.skills = skills;
		analytics.enqueue(EventCategory.XP, payload);
	}
}
