/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.CombatAchievementProgress;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat achievement progress collector.
 *
 * <p>Reads the completed-task count for each of the six combat achievement tiers
 * once a minute (throttled), emits only on change, and resets its diff state when
 * the logged-in RSN changes.
 *
 * <p><b>Varbit ids are source-verified</b> against the RuneLite client API:
 * {@code net.runelite.api.Varbits} L970-975 ({@code COMBAT_TASK_EASY..GRANDMASTER}
 * = 12885-12890), whose cache names are
 * {@code net.runelite.api.gameval.VarbitID.CA_TOTAL_TASKS_COMPLETED_*} — i.e. the
 * number of tasks completed in that tier, which maps directly to
 * {@link com.cortalabs.osrs.analytics.dto.CombatAchievementProgress#tierProgress}.
 * See README coverage table.
 */
@Singleton
public class CombatAchievementCollector
{
	private static final long SCAN_INTERVAL_MS = 60_000L;

	/**
	 * Tier name -> completed-count varbit id ({@code CA_TOTAL_TASKS_COMPLETED_*},
	 * Varbits L970-975). Keys are the exact lowercase tiers the backend accepts
	 * ({@code easy/medium/hard/elite/master/grandmaster}).
	 */
	private static final Map<String, Integer> TIER_COUNT_VARBITS = new LinkedHashMap<>();

	static
	{
		TIER_COUNT_VARBITS.put("easy", 12885);
		TIER_COUNT_VARBITS.put("medium", 12886);
		TIER_COUNT_VARBITS.put("hard", 12887);
		TIER_COUNT_VARBITS.put("elite", 12888);
		TIER_COUNT_VARBITS.put("master", 12889);
		TIER_COUNT_VARBITS.put("grandmaster", 12890);
	}

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final LongSupplier clockMs;
	/** Bounded re-observation timer that refreshes observed_at while the lane is idle. */
	private final ObservationWatermark watermark;

	private String lastSignature;
	private String lastRsn;
	private long lastScanMs;

	/** Package-private for tests: tier -> completed-count varbit id (shipped map). */
	static Map<String, Integer> tierCountVarbits()
	{
		return TIER_COUNT_VARBITS;
	}

	@Inject
	public CombatAchievementCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this(client, analytics, config, System::currentTimeMillis);
	}

	/** Test seam: inject a controllable clock so the re-observation cadence is deterministic. */
	CombatAchievementCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config, LongSupplier clockMs)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
		this.clockMs = clockMs;
		this.watermark = new ObservationWatermark(ObservationWatermark.DEFAULT_REOBSERVE_INTERVAL_MS, clockMs);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackCombatAchievements())
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
		// Reset the change-detection baseline AND the freshness watermark when the
		// account changes, so one account's staleness clock never carries into another.
		if (!rsn.equals(lastRsn))
		{
			lastRsn = rsn;
			lastSignature = null;
			watermark.reset();
		}
		long nowMs = clockMs.getAsLong();
		if (nowMs - lastScanMs < SCAN_INTERVAL_MS)
		{
			return;
		}
		lastScanMs = nowMs;
		scan(rsn);
	}

	private void scan(String rsn)
	{
		Map<String, Integer> tierProgress = new LinkedHashMap<>();
		StringBuilder signature = new StringBuilder();
		for (Map.Entry<String, Integer> entry : TIER_COUNT_VARBITS.entrySet())
		{
			int count = Math.max(0, client.getVarbitValue(entry.getValue()));
			tierProgress.put(entry.getKey(), count);
			signature.append(entry.getKey()).append('=').append(count).append(';');
		}
		boolean changed = !signature.toString().equals(lastSignature);
		// Emit on a real change, or re-observe the unchanged lane on the bounded cadence
		// so observed_at stays fresh. A re-observation re-sends the identical tier counts
		// (with a fresh witness timestamp), so the server refreshes observed_at without a
		// fabricated change row.
		if (!watermark.shouldEmit(changed))
		{
			return;
		}
		lastSignature = signature.toString();

		CombatAchievementProgress payload = new CombatAchievementProgress();
		Payloads.base(payload, client, rsn);
		payload.tierProgress = tierProgress;
		// Always empty: RuneLite's API exposes no combat-achievement task
		// enumeration (per-task completion lives in cache enums/varp bitmasks the
		// client API does not surface), so task names cannot be honestly
		// populated. Kept on the wire only because the backend contract requires
		// the field; flagged for schema removal.
		payload.completedTasks = new ArrayList<>();
		analytics.enqueue(EventCategory.COMBAT_ACHIEVEMENT, payload);
		watermark.markObserved();
	}
}
