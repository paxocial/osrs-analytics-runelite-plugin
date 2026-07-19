/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.LivePosition;
import java.util.function.LongSupplier;
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
 * Captures the local player's exact position each {@link GameTick} and hands the
 * latest witnessed tile to {@code AnalyticsClient} for the batch envelope's
 * {@code position} block (task #17 — live position on the wire).
 *
 * <p><b>Read live, off the batch thread.</b> The position must be read on the
 * client thread ({@link #onGameTick} reads the {@code WorldPoint}), but the batch
 * is assembled off the client thread. So each tick stores an immutable snapshot
 * into a client-free {@link PositionHolder}; {@code AnalyticsClient} pulls the
 * current value at flush time via {@link #currentPosition()} — a pure
 * holder + clock read that never touches the client.
 *
 * <p><b>Witnessed-or-absent, never stale (I1/I2).</b> The tracker holds a position
 * ONLY while it is fresh and witnessed:
 * <ul>
 *   <li>A tick captures only when telemetry is enabled, the position lane is on,
 *       the client is {@code LOGGED_IN}, and a local player with a world location
 *       actually exists. Any other tick {@link PositionHolder#invalidate() clears}
 *       the snapshot, so disabling the lane mid-session drops the position within
 *       one tick (~600ms).</li>
 *   <li>Session-ending game states ({@code LOGIN_SCREEN}, {@code CONNECTION_LOST})
 *       clear the snapshot immediately — the same flush trigger
 *       {@link RegionTimeShareCollector} uses. {@code HOPPING} does NOT clear: a
 *       world hop keeps the session and the player reappears on the same tile.</li>
 *   <li>{@link #currentPosition()} returns the snapshot only if it was captured
 *       within {@link PositionHolder#FRESHNESS_WINDOW_MS}; a capture older than
 *       that means game ticks stopped without a session-end transition (a hung
 *       client or an un-enumerated logged-out path), so the tile is treated as
 *       unwitnessed and omitted rather than emitted as current.</li>
 * </ul>
 * The result: the wire carries the tile from the most recent witnessed game tick
 * while the player is present, and nothing at all otherwise.
 *
 * <p>The honesty logic lives in {@link PositionHolder}, a client-free inner class,
 * so it is unit-testable with plain data and a fake clock — exactly like
 * {@link RegionTimeShareCollector.RegionAccumulator}.
 */
@Slf4j
@Singleton
public class LivePositionCollector
{
	private final Client client;
	private final AnalyticsConfig config;
	private final PositionHolder holder;

	@Inject
	public LivePositionCollector(Client client, AnalyticsConfig config)
	{
		this(client, config, System::currentTimeMillis);
	}

	/** Test seam: inject a controllable clock so freshness is deterministic. */
	LivePositionCollector(Client client, AnalyticsConfig config, LongSupplier clockMs)
	{
		this.client = client;
		this.config = config;
		this.holder = new PositionHolder(clockMs);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackLivePosition())
		{
			// Lane off: never carry a position captured while it was on.
			holder.invalidate();
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			holder.invalidate();
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			holder.invalidate();
			return;
		}
		WorldPoint location = player.getWorldLocation();
		if (location == null)
		{
			holder.invalidate();
			return;
		}
		// Read all four from ONE WorldPoint so region_id is consistent with x/y by
		// construction (getRegionID() == ((x>>6)<<8)|(y>>6)); no re-derivation here.
		holder.capture(location.getRegionID(), location.getX(), location.getY(), location.getPlane());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			// Session ended: no witnessed local player, so drop any cached tile.
			holder.invalidate();
		}
	}

	/**
	 * The current witnessed position, or {@code null} when there is none fresh.
	 * Thread-safe: read by {@code AnalyticsClient} on the batch/scheduler thread.
	 * Never touches the client — only the in-memory snapshot and the clock.
	 */
	public LivePosition currentPosition()
	{
		return holder.current();
	}

	/**
	 * In-memory holder for the latest witnessed position and the honesty gate that
	 * keeps it from ever emitting stale. Client-free so both guarantees — freshness
	 * and clear-on-invalidate — are testable with plain data and a fake clock,
	 * exactly like {@link RegionTimeShareCollector.RegionAccumulator}.
	 *
	 * <p>The single mutable snapshot is guarded by {@code this} because it is
	 * written on the client thread ({@code capture}/{@code invalidate}) and read on
	 * the batch thread ({@code current}).
	 */
	static final class PositionHolder
	{
		/**
		 * Max age of a capture that {@link #current()} will still emit. Game ticks
		 * fire ~every 600ms while logged in, so a live capture is always well under
		 * a second old; a capture older than this window means ticks stopped without
		 * a session-end clear (a hung client or an un-enumerated logged-out path), so
		 * the tile is no longer witnessed-current and is omitted. Comfortably clears
		 * loading/teleport tick pauses and the default 15s flush cadence.
		 */
		static final long FRESHNESS_WINDOW_MS = 30_000L;

		private final LongSupplier clockMs;

		// The latest witnessed tile, or null when none is currently held.
		private Integer regionId;
		private int x;
		private int y;
		private int plane;
		private long capturedAtMs;

		PositionHolder(LongSupplier clockMs)
		{
			this.clockMs = clockMs;
		}

		/** Store the tile witnessed on this game tick, stamped with the capture time. */
		synchronized void capture(int regionId, int x, int y, int plane)
		{
			this.regionId = regionId;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.capturedAtMs = clockMs.getAsLong();
		}

		/** Drop any held tile (no witnessed local player, or lane turned off). */
		synchronized void invalidate()
		{
			this.regionId = null;
		}

		/**
		 * The held tile if it is still fresh, else {@code null}. Freshness is judged
		 * against the same clock {@link #capture} stamps with, so the window is a
		 * real elapsed-time bound, not a wall-clock guess.
		 */
		synchronized LivePosition current()
		{
			if (regionId == null)
			{
				return null;
			}
			if (clockMs.getAsLong() - capturedAtMs > FRESHNESS_WINDOW_MS)
			{
				return null;
			}
			return new LivePosition(regionId, x, y, plane);
		}
	}
}
