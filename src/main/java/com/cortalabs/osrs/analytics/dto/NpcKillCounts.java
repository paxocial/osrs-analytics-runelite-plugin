/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Per-session NPC kill counts: how many of each NPC a session killed.
 *
 * <p>One payload per session, flushed at session end — the same aggregate-and-flush
 * shape as {@link RegionTimeShare} and {@link ActivityBreakdown}. The JSON shape
 * mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: NpcKillCounts}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code session_id} and {@code kills} may appear on the wire.
 *
 * <p><b>Witnessed-or-absent (I1/I2).</b> {@code kills} is a real per-NPC
 * accumulator — each entry an NPC the session actually killed, never a zero-filled
 * default. A session that killed nothing sends no payload at all (the backend
 * requires {@code min_length=1}); the collector never emits an empty accumulator.
 * The server caps the list at 256 entries (I3); the collector caps to the same
 * bound before enqueue so a pathological session cannot 422 the batch.
 */
public class NpcKillCounts extends PluginPayload
{
	/** Wire cap (I3): matches the server's {@code kills} {@code max_length}. */
	public static final int MAX_KILLS = 256;

	@SerializedName("session_id")
	public String sessionId;

	/** Witnessed per-NPC kill accumulators for the session (1..256). */
	public List<NpcKill> kills;

	/**
	 * One NPC's kill count for the session (one entry of {@link #kills}).
	 *
	 * <p>Nested and base-field-free: the backend {@code NpcKill} is a bare
	 * {@code BaseModel}, so this must NOT carry rsn/world/timestamp/version — only
	 * {@code npc} and {@code count}, exactly like {@link RegionTimeShare.RegionTick}.
	 */
	public static class NpcKill
	{
		/** NPC name the kills are for; required (server {@code min_length=1}). */
		public String npc;

		/** Witnessed kills of this NPC in the session; server-enforced {@code >= 1}. */
		public int count;

		public NpcKill()
		{
		}

		public NpcKill(String npc, int count)
		{
			this.npc = npc;
			this.count = count;
		}
	}
}
