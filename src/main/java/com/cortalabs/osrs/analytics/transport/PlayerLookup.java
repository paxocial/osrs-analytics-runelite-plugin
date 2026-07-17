/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import java.util.List;

/**
 * Parsed, immutable result of a {@code GET /players/{username}} lookup against
 * the backend's WOM-style player envelope. Only the fields the panel renders are
 * kept; the raw envelope carries more (bosses, computed) that we do not surface.
 */
public final class PlayerLookup
{
	/** One skill row from {@code latestSnapshot.data.skills}. */
	public static final class SkillRow
	{
		public final String name;
		public final int level;
		public final long experience;
		public final long rank;

		public SkillRow(String name, int level, long experience, long rank)
		{
			this.name = name;
			this.level = level;
			this.experience = experience;
			this.rank = rank;
		}
	}

	/** One activity row from {@code latestSnapshot.data.activities}. */
	public static final class ActivityRow
	{
		public final String name;
		public final long score;
		public final long rank;

		public ActivityRow(String name, long score, long rank)
		{
			this.name = name;
			this.score = score;
			this.rank = rank;
		}
	}

	public final String username;
	public final String displayName;
	public final String type;
	public final String updatedAt;
	/** Overall skill row when present (else {@code null}). */
	public final SkillRow overall;
	public final List<SkillRow> skills;
	public final List<ActivityRow> activities;

	public PlayerLookup(String username, String displayName, String type, String updatedAt,
		SkillRow overall, List<SkillRow> skills, List<ActivityRow> activities)
	{
		this.username = username;
		this.displayName = displayName;
		this.type = type;
		this.updatedAt = updatedAt;
		this.overall = overall;
		this.skills = skills;
		this.activities = activities;
	}
}
