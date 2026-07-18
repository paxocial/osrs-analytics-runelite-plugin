/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Per-session region time-share: where a session spent its game ticks.
 *
 * <p>One payload per session, flushed at session end. The JSON shape mirrors the
 * Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: RegionTimeShare}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code session_id} and {@code regions} may appear on the wire.
 *
 * <p>{@code regions} is a witnessed per-region tick accumulator — each entry is a
 * region the session was actually in, never a zero-filled default (absence != zero,
 * I1/I2). The server caps the list at 256 entries; the collector caps to the same
 * bound before enqueue so a pathological flush cannot 422 the batch (I3).
 */
public class RegionTimeShare extends PluginPayload
{
	@SerializedName("session_id")
	public String sessionId;

	/** Witnessed per-region tick accumulators for the session (1..256). */
	public List<RegionTick> regions;

	/** Ticks a session spent in one map region (one entry of {@link #regions}). */
	public static class RegionTick
	{
		@SerializedName("region_id")
		public int regionId;

		public int ticks;

		public RegionTick()
		{
		}

		public RegionTick(int regionId, int ticks)
		{
			this.regionId = regionId;
			this.ticks = ticks;
		}
	}
}
