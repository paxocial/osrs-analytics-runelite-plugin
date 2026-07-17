/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.DiaryProgress;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p><b>Every tier has TWO source-verified varbits</b> in the RuneLite client
 * API ({@code net.runelite.api.gameval.VarbitID}): a tasks-done flag
 * ({@code *_DIARY_*_COMPLETE}, Karamja {@code ATJUN_*_DONE}; these are the
 * legacy {@code net.runelite.api.Varbits.DIARY_*} ids, L212-270) and a
 * reward-claimed flag ({@code *_REWARD}, VarbitID L2650/2671/2683 for Karamja,
 * L3265-3304 for the 4499-4538 block, L3332 Karamja elite, L4770-4773 Kourend).
 * A tier counts as complete when EITHER flag is {@code >= 1}: neither flag can
 * be set before every task in the tier is finished, and reading both makes the
 * collector robust to per-region quirks in which flag persists (the wiki's own
 * WikiSync spec, {@code achievementDiariesSpecs.json}, keys completion off the
 * reward-claimed family). This replaced a Karamja-only {@code >= 2} threshold
 * on {@code ATJUN_*_DONE} that reported Karamja as incomplete for accounts
 * whose done-flag never exceeds 1 (BUG: live session 2026-07-17, Karamja all
 * tiers false on an account with claimed rewards). See README coverage table.
 */
@Singleton
public class DiaryCollector
{
	private static final long SCAN_INTERVAL_MS = 60_000L;

	/**
	 * Region display name -> {easy, medium, hard, elite} tasks-done varbit ids.
	 * Ids from {@code net.runelite.api.Varbits} (reference L212-270):
	 * Ardougne L212-215, Desert L217-220, Falador L222-225, Fremennik L227-230,
	 * Kandarin L232-235, Karamja L237-240, Kourend L242-245, Lumbridge L247-250,
	 * Morytania L252-255, Varrock L257-260, Western L262-265, Wilderness L267-270.
	 * gameval names: {@code *_DIARY_*_COMPLETE} / Karamja {@code ATJUN_*_DONE}.
	 */
	private static final Map<String, int[]> REGION_TIER_VARBITS = new LinkedHashMap<>();

	/**
	 * Region display name -> {easy, medium, hard, elite} reward-claimed varbit
	 * ids ({@code net.runelite.api.gameval.VarbitID.*_REWARD}): Karamja
	 * ATJUN_EASY/MED/HARD_REWARD L2650/2671/2683 (3577/3598/3610), Ardougne
	 * L3265-3268, Falador L3269-3272, Wilderness L3273-3276, Western L3277-3280,
	 * Kandarin L3281-3284, Varrock L3285-3288, Desert L3289-3292, Morytania
	 * L3293-3296, Fremennik L3297-3300, Lumbridge L3301-3304, Karamja elite
	 * KARAMJA_ELITE_REWARD L3332 (4567), Kourend L4770-4773 (7929-7932).
	 */
	private static final Map<String, int[]> REGION_TIER_REWARD_VARBITS = new LinkedHashMap<>();

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

		REGION_TIER_REWARD_VARBITS.put("Ardougne", new int[]{4499, 4500, 4501, 4502});
		REGION_TIER_REWARD_VARBITS.put("Desert", new int[]{4523, 4524, 4525, 4526});
		REGION_TIER_REWARD_VARBITS.put("Falador", new int[]{4503, 4504, 4505, 4506});
		REGION_TIER_REWARD_VARBITS.put("Fremennik", new int[]{4531, 4532, 4533, 4534});
		REGION_TIER_REWARD_VARBITS.put("Kandarin", new int[]{4515, 4516, 4517, 4518});
		REGION_TIER_REWARD_VARBITS.put("Karamja", new int[]{3577, 3598, 3610, 4567});
		REGION_TIER_REWARD_VARBITS.put("Kourend", new int[]{7929, 7930, 7931, 7932});
		REGION_TIER_REWARD_VARBITS.put("Lumbridge", new int[]{4535, 4536, 4537, 4538});
		REGION_TIER_REWARD_VARBITS.put("Morytania", new int[]{4527, 4528, 4529, 4530});
		REGION_TIER_REWARD_VARBITS.put("Varrock", new int[]{4519, 4520, 4521, 4522});
		REGION_TIER_REWARD_VARBITS.put("Western Provinces", new int[]{4511, 4512, 4513, 4514});
		REGION_TIER_REWARD_VARBITS.put("Wilderness", new int[]{4507, 4508, 4509, 4510});
	}

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	private final Map<String, String> lastSignature = new HashMap<>();
	private String lastRsn;
	private long lastScanMs;

	/** Package-private for tests: region -> tasks-done tier varbit ids (shipped map). */
	static Map<String, int[]> regionTierVarbits()
	{
		return REGION_TIER_VARBITS;
	}

	/** Package-private for tests: region -> reward-claimed tier varbit ids (shipped map). */
	static Map<String, int[]> regionTierRewardVarbits()
	{
		return REGION_TIER_REWARD_VARBITS;
	}

	@Inject
	public DiaryCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
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
		// Reset the change-detection baseline when the account changes so one
		// player's completions are never attributed to another.
		if (!rsn.equals(lastRsn))
		{
			lastRsn = rsn;
			lastSignature.clear();
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
		for (Map.Entry<String, int[]> entry : REGION_TIER_VARBITS.entrySet())
		{
			String region = entry.getKey();
			int[] varbits = entry.getValue();
			int[] rewardVarbits = REGION_TIER_REWARD_VARBITS.get(region);
			if (varbits.length != 4 || rewardVarbits == null || rewardVarbits.length != 4)
			{
				continue;
			}
			boolean easy = isComplete(varbits[0], rewardVarbits[0]);
			boolean medium = isComplete(varbits[1], rewardVarbits[1]);
			boolean hard = isComplete(varbits[2], rewardVarbits[2]);
			boolean elite = isComplete(varbits[3], rewardVarbits[3]);

			String signature = easy + "|" + medium + "|" + hard + "|" + elite;
			if (signature.equals(lastSignature.get(region)))
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
		}
	}

	/**
	 * A diary tier is complete when its tasks-done flag OR its reward-claimed
	 * flag is set. Neither flag can be set before every task in the tier is
	 * finished, so OR-ing them never over-reports; reading both survives
	 * per-region differences in which flag the game persists.
	 */
	private boolean isComplete(int tasksDoneVarbitId, int rewardVarbitId)
	{
		return client.getVarbitValue(tasksDoneVarbitId) >= 1
			|| client.getVarbitValue(rewardVarbitId) >= 1;
	}
}
