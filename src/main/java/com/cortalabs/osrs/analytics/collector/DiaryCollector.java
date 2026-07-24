/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.DiaryProgress;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Achievement diary progress collector.
 *
 * <p>Reads the per-tier completion varbits for all twelve diary regions once a
 * minute (throttled), emits only on change, and resets its diff state when the
 * logged-in RSN changes so account-hopping never cross-contaminates.
 *
 * <p><b>Varbit ids are source-verified</b> against the RuneLite client API,
 * {@code net.runelite.api.Varbits} L212-270 (the {@code DIARY_*_EASY..ELITE}
 * constants). A tier is "complete" when its varbit is {@code >= 1}, except the
 * three Karamja task varbits (3578/3599/3611), which are 3-state
 * ({@code net.runelite.api.gameval.VarbitID.ATJUN_*_DONE}: 0 none, 1 tasks done,
 * 2 reward claimed) and only count as complete at {@code >= 2}. Karamja elite
 * (4566) is a standard completion flag. See README coverage table.
 *
 * <p>Live-validated 2026-07-17: a 12-region session snapshot matched the
 * operator's real diary state 12-for-12 (including all-false Karamja/Kourend).
 */
@Singleton
public class DiaryCollector
{
	private static final long SCAN_INTERVAL_MS = 60_000L;

	/**
	 * Region display name -> {easy, medium, hard, elite} completion varbit ids.
	 * Ids from {@code net.runelite.api.Varbits} (reference L212-270):
	 * Ardougne L212-215, Desert L217-220, Falador L222-225, Fremennik L227-230,
	 * Kandarin L232-235, Karamja L237-240, Kourend L242-245, Lumbridge L247-250,
	 * Morytania L252-255, Varrock L257-260, Western L262-265, Wilderness L267-270.
	 */
	private static final Map<String, int[]> REGION_TIER_VARBITS = new LinkedHashMap<>();

	/**
	 * Karamja easy/medium/hard task varbits ({@code ATJUN_*_DONE}, Varbits
	 * L237-239): 3-state, so completion is {@code >= 2} rather than {@code >= 1}.
	 */
	private static final Set<Integer> THREE_STATE_VARBITS = new HashSet<>(Arrays.asList(3578, 3599, 3611));

	static
	{
		REGION_TIER_VARBITS.put("Ardougne", new int[]{4458, 4459, 4460, 4461});
		REGION_TIER_VARBITS.put("Desert", new int[]{4483, 4484, 4485, 4486});
		REGION_TIER_VARBITS.put("Falador", new int[]{4462, 4463, 4464, 4465});
		REGION_TIER_VARBITS.put("Fremennik", new int[]{4491, 4492, 4493, 4494});
		REGION_TIER_VARBITS.put("Kandarin", new int[]{4475, 4476, 4477, 4478});
		REGION_TIER_VARBITS.put("Karamja", new int[]{3578, 3599, 3611, 4566});
		REGION_TIER_VARBITS.put("Kourend", new int[]{7925, 7926, 7927, 7928});
		REGION_TIER_VARBITS.put("Lumbridge", new int[]{4495, 4496, 4497, 4498});
		REGION_TIER_VARBITS.put("Morytania", new int[]{4487, 4488, 4489, 4490});
		REGION_TIER_VARBITS.put("Varrock", new int[]{4479, 4480, 4481, 4482});
		REGION_TIER_VARBITS.put("Western Provinces", new int[]{4471, 4472, 4473, 4474});
		REGION_TIER_VARBITS.put("Wilderness", new int[]{4466, 4467, 4468, 4469});
	}

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final LongSupplier clockMs;
	/** Bounded re-observation timer that refreshes observed_at while the lane is idle. */
	private final ObservationWatermark watermark;

	private final Map<String, String> lastSignature = new HashMap<>();
	private String lastRsn;
	private long lastScanMs;

	/** Package-private for tests: region -> tier varbit ids (immutable view of shipped map). */
	static Map<String, int[]> regionTierVarbits()
	{
		return REGION_TIER_VARBITS;
	}

	@Inject
	public DiaryCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this(client, analytics, config, System::currentTimeMillis);
	}

	/** Test seam: inject a controllable clock so the re-observation cadence is deterministic. */
	DiaryCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config, LongSupplier clockMs)
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
		if (!config.enabled() || !config.trackDiaries())
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
		// account changes so one player's completions/staleness clock are never
		// attributed to another.
		if (!rsn.equals(lastRsn))
		{
			lastRsn = rsn;
			lastSignature.clear();
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
		// Snapshot the re-observation decision once so a due sweep re-emits every region
		// consistently within this scan; markObserved() at the end restarts the interval.
		boolean reobserveDue = watermark.dueForReobservation();
		boolean emittedAny = false;
		for (Map.Entry<String, int[]> entry : REGION_TIER_VARBITS.entrySet())
		{
			String region = entry.getKey();
			int[] varbits = entry.getValue();
			if (varbits.length != 4)
			{
				continue;
			}
			boolean easy = isComplete(varbits[0]);
			boolean medium = isComplete(varbits[1]);
			boolean hard = isComplete(varbits[2]);
			boolean elite = isComplete(varbits[3]);

			String signature = easy + "|" + medium + "|" + hard + "|" + elite;
			boolean changed = !signature.equals(lastSignature.get(region));
			// Emit on a real change, or re-observe the unchanged region on the bounded
			// cadence. A re-observation re-sends the identical tier flags (with a fresh
			// witness timestamp), refreshing observed_at without a fabricated change row.
			if (!changed && !reobserveDue)
			{
				continue;
			}
			lastSignature.put(region, signature);

			DiaryProgress payload = new DiaryProgress();
			Payloads.base(payload, client, rsn);
			payload.region = region;
			payload.easy = easy;
			payload.medium = medium;
			payload.hard = hard;
			payload.elite = elite;
			analytics.enqueue(EventCategory.DIARY, payload);
			emittedAny = true;
		}
		if (emittedAny)
		{
			watermark.markObserved();
		}
	}

	/**
	 * A diary tier is complete when its varbit reaches the completion value: 1 for
	 * standard flags, 2 for the 3-state Karamja task varbits.
	 */
	private boolean isComplete(int varbitId)
	{
		return client.getVarbitValue(varbitId) >= completionThreshold(varbitId);
	}

	/**
	 * The varbit value at which a tier counts as complete: {@code 2} for the 3-state
	 * Karamja task varbits ({@code ATJUN_*_DONE}: 0 none, 1 tasks done, 2 reward
	 * claimed), {@code 1} for every standard completion flag. Package-private so the
	 * panel's at-a-glance tier count reads through this same source-verified rule
	 * instead of duplicating the Karamja special case.
	 */
	static int completionThreshold(int varbitId)
	{
		return THREE_STATE_VARBITS.contains(varbitId) ? 2 : 1;
	}
}
