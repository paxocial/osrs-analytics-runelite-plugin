/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.ActivityBreakdown;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Per-session activity-classification collector: classifies the local player's
 * animation each {@link GameTick} into a closed-vocabulary bucket
 * ({@link AnimationActivityMap}) and flushes one {@link ActivityBreakdown} of
 * seconds-per-bucket at session end.
 *
 * <p><b>Aggregate-and-flush, never stream (I3/L2).</b> Animation transitions are
 * high-volume; this collector samples the current animation once per tick, folds one
 * tick into its bucket, and emits only the flushed per-bucket aggregate — never a
 * per-animation-change row (FP-SPIKE-ANIM capture rule).
 *
 * <p><b>Honesty (I2).</b> An unmapped/ambiguous animation is {@code unknown} and an
 * empty animation ({@code -1}) is {@code idle} — both first-class, honest buckets, never
 * guessed. Only tokens from the closed taxonomy can be emitted (see
 * {@link AnimationActivityMap}); every one satisfies the server's {@code ^[a-z_]{1,40}$}.
 *
 * <p><b>Account-switch clear (I4), witnessed-or-absent (I1).</b> The accumulator is
 * bound to {@code accountHash} and cleared on a change; a session that observed no ticks
 * emits nothing.
 *
 * <p>Flush trigger mirrors {@link SessionCollector}: {@code LOGIN_SCREEN} /
 * {@code CONNECTION_LOST} flush; {@code HOPPING} does not.
 */
@Slf4j
@Singleton
public class ActivityClassificationCollector
{
	/** Wire cap (I3): matches the server's {@code buckets} max_length (far above the 20-token taxonomy). */
	static final int MAX_BUCKETS = 128;
	/** One game tick is 0.6 seconds. */
	static final double TICK_SECONDS = 0.6;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final ActivityAccumulator accumulator;

	@Inject
	public ActivityClassificationCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
		this.accumulator = new ActivityAccumulator(new AnimationActivityMap());
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackActivityClassification())
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
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		accumulator.bindAccount(client.getAccountHash());
		accumulator.recordTick(player.getAnimation(), rsn);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			flush();
		}
	}

	private void flush()
	{
		if (!accumulator.hasData() || accumulator.rsn() == null)
		{
			accumulator.clear();
			return;
		}
		List<ActivityBreakdown.ActivityBucket> buckets = accumulator.buckets();
		if (buckets.isEmpty())
		{
			// Every accumulated bucket rounded below one second (only possible with a
			// sub-tick session): honest absence, no row.
			accumulator.clear();
			return;
		}
		ActivityBreakdown payload = new ActivityBreakdown();
		Payloads.base(payload, client, accumulator.rsn());
		payload.sessionId = accumulator.sessionId();
		payload.buckets = buckets;
		analytics.enqueue(EventCategory.ACTIVITY_TIME, payload);
		accumulator.clear();
	}

	/**
	 * Convert the per-bucket tick accumulator to the flushed seconds list, capped at
	 * {@link #MAX_BUCKETS} (I3). Ticks -> seconds is {@code round(ticks * 0.6)}; a bucket
	 * with at least one tick always yields at least one second (satisfying the server's
	 * {@code seconds >= 1}). Static + client-free so it is testable with plain data.
	 */
	static List<ActivityBreakdown.ActivityBucket> toCappedBuckets(Map<String, Long> bucketTicks)
	{
		List<Map.Entry<String, Long>> entries = new ArrayList<>(bucketTicks.entrySet());
		if (entries.size() > MAX_BUCKETS)
		{
			entries.sort((a, b) ->
			{
				int bySeconds = Long.compare(b.getValue(), a.getValue());
				return bySeconds != 0 ? bySeconds : a.getKey().compareTo(b.getKey());
			});
			int dropped = entries.size() - MAX_BUCKETS;
			entries = entries.subList(0, MAX_BUCKETS);
			log.warn("ActivityBreakdown flush had {} buckets; kept top {}, dropped {}",
				dropped + MAX_BUCKETS, MAX_BUCKETS, dropped);
		}
		List<ActivityBreakdown.ActivityBucket> out = new ArrayList<>(entries.size());
		for (Map.Entry<String, Long> e : entries)
		{
			int seconds = (int) Math.round(e.getValue() * TICK_SECONDS);
			if (seconds >= 1)
			{
				out.add(new ActivityBreakdown.ActivityBucket(e.getKey(), seconds));
			}
		}
		return out;
	}

	/**
	 * In-memory, per-session activity accumulator. Client-free (it classifies through an
	 * injected {@link AnimationActivityMap}) so accumulation, the account-switch clear,
	 * and the closed-vocabulary guarantee are testable with plain data.
	 */
	static final class ActivityAccumulator
	{
		private final AnimationActivityMap map;
		private final Map<String, Long> bucketTicks = new LinkedHashMap<>();
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private String sessionId;
		private String rsn;
		private boolean active;

		ActivityAccumulator(AnimationActivityMap map)
		{
			this.map = map;
		}

		/** Bind the current account; a change clears the accumulator first (I4). */
		void bindAccount(long accountHash)
		{
			if (accountHash != account)
			{
				account = accountHash;
				clear();
			}
		}

		/** Classify one witnessed game tick's animation and fold it into its bucket. */
		void recordTick(int animationId, String rsn)
		{
			if (!active)
			{
				active = true;
				sessionId = UUID.randomUUID().toString();
			}
			this.rsn = rsn;
			bucketTicks.merge(map.classify(animationId), 1L, Long::sum);
		}

		boolean hasData()
		{
			return active && !bucketTicks.isEmpty();
		}

		String sessionId()
		{
			return sessionId;
		}

		String rsn()
		{
			return rsn;
		}

		List<ActivityBreakdown.ActivityBucket> buckets()
		{
			return toCappedBuckets(bucketTicks);
		}

		/** Drop the session's accumulated state. Leaves the bound account intact. */
		void clear()
		{
			bucketTicks.clear();
			sessionId = null;
			rsn = null;
			active = false;
		}
	}
}
