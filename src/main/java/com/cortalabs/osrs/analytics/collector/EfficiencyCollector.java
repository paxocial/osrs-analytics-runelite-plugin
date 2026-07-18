/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.EfficiencyEnvelope;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Per-session efficiency-envelope collector: accumulates the active/idle tick split,
 * world hops, and duration for a session, flushing one {@link EfficiencyEnvelope} at
 * session end.
 *
 * <p><b>Active vs idle (IdleNotifier rule).</b> Each {@link GameTick} is classified
 * from {@code min(getMouseIdleTicks(), getKeyboardIdleTicks())}: the player is idle for
 * the tick only when BOTH input channels have been still past {@link #AFK_IDLE_THRESHOLD_TICKS}
 * — any recent mouse OR keyboard activity counts the tick as active. This is the same
 * min()-of-input signal RuneLite's IdleNotifier uses. The threshold is a v1 tuning
 * constant; the plugin's job is to witness honest active/idle counts, and the SERVER
 * owns the final {@code active_ratio} interpretation (FP-A3 {@code get_efficiency_series}).
 *
 * <p><b>Aggregate-and-flush (I3), witnessed-or-absent (I1/I2), account-switch clear
 * (I4).</b> Counts are folded per tick and only the flushed envelope leaves the client;
 * a session with no observed ticks emits nothing; an {@code accountHash} change clears
 * the accumulator before the next tick.
 *
 * <p>{@code skillRates} is left null in v1 — the plugin does not fabricate per-skill
 * XP/hour here; the server derives rates from the XP snapshot stream.
 *
 * <p>Flush trigger mirrors {@link SessionCollector}: {@code LOGIN_SCREEN} /
 * {@code CONNECTION_LOST} flush; {@code HOPPING} does not (a hop keeps the session and
 * is counted as a world hop).
 */
@Singleton
public class EfficiencyCollector
{
	/**
	 * Input-idle threshold (in client idle ticks) past which a game tick counts as
	 * idle. A v1 heuristic mirroring RuneLite's IdleNotifier min()-of-input convention;
	 * tunable. The value classifies ticks; the server owns the active-ratio verdict.
	 */
	static final int AFK_IDLE_THRESHOLD_TICKS = 50;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final EfficiencyAccumulator accumulator = new EfficiencyAccumulator();

	@Inject
	public EfficiencyCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackEfficiency())
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
			// Transient out-of-range read (state transition): skip so a spurious value
			// never fabricates a world hop or a mis-attributed tick.
			return;
		}
		accumulator.bindAccount(client.getAccountHash());
		accumulator.recordTick(client.getMouseIdleTicks(), client.getKeyboardIdleTicks(), world, rsn);
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
		EfficiencyEnvelope payload = new EfficiencyEnvelope();
		Payloads.base(payload, client, accumulator.rsn());
		payload.sessionId = accumulator.sessionId();
		payload.activeTicks = accumulator.activeTicks();
		payload.idleTicks = accumulator.idleTicks();
		payload.worldHops = accumulator.worldHops();
		payload.durationTicks = accumulator.durationTicks();
		payload.skillRates = null; // v1: server derives XP/hour from snapshots; never fabricated here.
		analytics.enqueue(EventCategory.EFFICIENCY, payload);
		accumulator.clear();
	}

	/**
	 * In-memory, per-session efficiency accumulator. Client-free so the active/idle
	 * split, world-hop count, and account-switch clear are testable with plain data.
	 */
	static final class EfficiencyAccumulator
	{
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private String sessionId;
		private String rsn;
		private boolean active;
		private long activeTicks;
		private long idleTicks;
		private int worldHops;
		private int lastWorld = -1;

		/** A game tick is idle only when BOTH input channels are still past the threshold. */
		static boolean isIdle(int mouseIdleTicks, int keyboardIdleTicks)
		{
			return Math.min(mouseIdleTicks, keyboardIdleTicks) >= AFK_IDLE_THRESHOLD_TICKS;
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

		/** Fold one witnessed game tick: classify active/idle and track world hops. */
		void recordTick(int mouseIdleTicks, int keyboardIdleTicks, int world, String rsn)
		{
			if (!active)
			{
				active = true;
				sessionId = UUID.randomUUID().toString();
				lastWorld = world;
			}
			else if (world != lastWorld)
			{
				worldHops++;
				lastWorld = world;
			}
			this.rsn = rsn;
			if (isIdle(mouseIdleTicks, keyboardIdleTicks))
			{
				idleTicks++;
			}
			else
			{
				activeTicks++;
			}
		}

		boolean hasData()
		{
			return active && (activeTicks + idleTicks) > 0;
		}

		String sessionId()
		{
			return sessionId;
		}

		String rsn()
		{
			return rsn;
		}

		int activeTicks()
		{
			return (int) Math.min(activeTicks, Integer.MAX_VALUE);
		}

		int idleTicks()
		{
			return (int) Math.min(idleTicks, Integer.MAX_VALUE);
		}

		int worldHops()
		{
			return worldHops;
		}

		int durationTicks()
		{
			return (int) Math.min(activeTicks + idleTicks, Integer.MAX_VALUE);
		}

		/** Drop the session's accumulated state. Leaves the bound account intact. */
		void clear()
		{
			sessionId = null;
			rsn = null;
			active = false;
			activeTicks = 0;
			idleTicks = 0;
			worldHops = 0;
			lastWorld = -1;
		}
	}
}
