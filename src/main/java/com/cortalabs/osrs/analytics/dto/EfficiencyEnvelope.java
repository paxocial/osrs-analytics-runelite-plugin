/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Per-session efficiency envelope: active/idle tick split, world hops, duration.
 *
 * <p>One payload per session. Mirrors the backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: EfficiencyEnvelope}), which is
 * {@code extra=forbid}.
 *
 * <p>{@code skillRates} is honestly absent ({@code null}) in v1: the plugin does not
 * fabricate per-skill XP/hour here — the server derives rates from the XP snapshot
 * stream. A {@code null} list is dropped by Gson (never an empty list standing in for
 * "we measured zero"), matching pydantic's {@code Optional[...] = None} default.
 */
public class EfficiencyEnvelope extends PluginPayload
{
	@SerializedName("session_id")
	public String sessionId;

	@SerializedName("active_ticks")
	public int activeTicks;

	@SerializedName("idle_ticks")
	public int idleTicks;

	@SerializedName("world_hops")
	public int worldHops;

	@SerializedName("duration_ticks")
	public int durationTicks;

	/** Per-skill XP/hour; left null in v1 so Gson omits it (server derives rates). */
	@SerializedName("skill_rates")
	public List<SkillRate> skillRates;

	/** One skill's XP/hour for a session (one entry of {@link #skillRates}). */
	public static class SkillRate
	{
		public String skill;

		@SerializedName("xp_per_hour")
		public int xpPerHour;

		public SkillRate()
		{
		}

		public SkillRate(String skill, int xpPerHour)
		{
			this.skill = skill;
			this.xpPerHour = xpPerHour;
		}
	}
}
