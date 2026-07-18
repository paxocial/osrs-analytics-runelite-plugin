/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.RegionTimeShare;
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
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Per-session region time-share collector: accumulates one witnessed tick per
 * {@link GameTick} into the player's current map region and flushes a single
 * {@link RegionTimeShare} payload at session end.
 *
 * <p><b>Aggregate-and-flush, never stream (I3/L2).</b> The region a player stands in
 * is read every tick (the same {@code getRegionID()} the coarse {@link ActivityCollector}
 * reads and discards) and folded into an in-memory {@code region -> ticks} accumulator;
 * only the flushed aggregate leaves the client, never a per-tick row.
 *
 * <p><b>Account-switch clear (I4).</b> The accumulator is bound to the current
 * {@code accountHash}; a change clears it before the next tick so account A's ticks can
 * never bleed into account B's row — the same fabrication guard the sibling collectors
 * carry ({@link AccountKeyedDeltaGuard}, {@link CollectionLogCollector#clearIfAccountChanged}).
 *
 * <p><b>Witnessed-or-absent (I1/I2).</b> A session that accrued no ticks emits nothing;
 * there is no zero-filled row. Only real accumulated ticks flush.
 *
 * <p>Flush trigger mirrors {@link SessionCollector}: session-ending game states
 * ({@code LOGIN_SCREEN}, {@code CONNECTION_LOST}) flush; {@code HOPPING} does not — a
 * world hop keeps the session.
 */
@Slf4j
@Singleton
public class RegionTimeShareCollector
{
	/** Wire cap (I3): matches the server's {@code regions} max_length. */
	static final int MAX_REGIONS = 256;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final RegionAccumulator accumulator = new RegionAccumulator();

	@Inject
	public RegionTimeShareCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackRegionTimeShare())
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
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		accumulator.bindAccount(client.getAccountHash());
		accumulator.recordTick(location.getRegionID(), rsn);
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
			// Witnessed-or-absent: nothing accumulated (or no attributable rsn) -> no row.
			accumulator.clear();
			return;
		}
		RegionTimeShare payload = new RegionTimeShare();
		Payloads.base(payload, client, accumulator.rsn());
		payload.sessionId = accumulator.sessionId();
		payload.regions = accumulator.regions();
		analytics.enqueue(EventCategory.REGION_TIME, payload);
		accumulator.clear();
	}

	/**
	 * Build the flushed region list, capped at {@link #MAX_REGIONS} (I3). Below the cap
	 * the entries keep first-seen order; above it the highest-tick regions win (least
	 * information dropped), tie-broken by region id for determinism, and the drop is
	 * logged. Static + client-free so the cap is testable with plain data.
	 */
	static List<RegionTimeShare.RegionTick> toCappedRegions(Map<Integer, Long> ticks)
	{
		List<Map.Entry<Integer, Long>> entries = new ArrayList<>(ticks.entrySet());
		if (entries.size() > MAX_REGIONS)
		{
			entries.sort((a, b) ->
			{
				int byTicks = Long.compare(b.getValue(), a.getValue());
				return byTicks != 0 ? byTicks : Integer.compare(a.getKey(), b.getKey());
			});
			int dropped = entries.size() - MAX_REGIONS;
			entries = entries.subList(0, MAX_REGIONS);
			log.warn("RegionTimeShare flush had {} regions; kept top {}, dropped {}",
				dropped + MAX_REGIONS, MAX_REGIONS, dropped);
		}
		List<RegionTimeShare.RegionTick> out = new ArrayList<>(entries.size());
		for (Map.Entry<Integer, Long> e : entries)
		{
			out.add(new RegionTimeShare.RegionTick(e.getKey(), (int) Math.min(e.getValue(), Integer.MAX_VALUE)));
		}
		return out;
	}

	/**
	 * In-memory, per-session region-tick accumulator. Client-free so its two honesty
	 * guarantees — aggregate (no per-tick emit) and account-switch clear — are testable
	 * with plain data, exactly like {@link AccountKeyedDeltaGuard}.
	 */
	static final class RegionAccumulator
	{
		private final Map<Integer, Long> ticks = new LinkedHashMap<>();
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private String sessionId;
		private String rsn;
		private boolean active;

		/** Bind the current account; a change clears the accumulator first (I4). */
		void bindAccount(long accountHash)
		{
			if (accountHash != account)
			{
				account = accountHash;
				clear();
			}
		}

		/** Fold one witnessed game tick into {@code region}, starting a session if needed. */
		void recordTick(int regionId, String rsn)
		{
			if (!active)
			{
				active = true;
				sessionId = UUID.randomUUID().toString();
			}
			this.rsn = rsn;
			ticks.merge(regionId, 1L, Long::sum);
		}

		boolean hasData()
		{
			return active && !ticks.isEmpty();
		}

		String sessionId()
		{
			return sessionId;
		}

		String rsn()
		{
			return rsn;
		}

		List<RegionTimeShare.RegionTick> regions()
		{
			return toCappedRegions(ticks);
		}

		/** Drop the session's accumulated state. Leaves the bound account intact. */
		void clear()
		{
			ticks.clear();
			sessionId = null;
			rsn = null;
			active = false;
		}
	}
}
