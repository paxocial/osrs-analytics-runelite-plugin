/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.Map;

/**
 * Combat achievement progress. Mirrors {@code CombatAchievementProgress}.
 *
 * <p>{@link #tierProgress} keys must be a subset of
 * {@code easy, medium, hard, elite, master, grandmaster}.
 */
public class CombatAchievementProgress extends PluginPayload
{
	@SerializedName("tier_progress")
	public Map<String, Integer> tierProgress;

	@SerializedName("completed_tasks")
	public List<String> completedTasks;
}
