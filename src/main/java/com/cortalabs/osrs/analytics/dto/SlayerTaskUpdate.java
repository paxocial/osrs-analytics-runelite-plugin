/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * A witnessed slayer task transition — an assignment or a completion.
 *
 * <p>The JSON shape mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: SlayerTaskUpdate}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code creature}, {@code amount}, {@code location}, {@code streak},
 * {@code points} and {@code transition} may appear on the wire. A stray or
 * mistyped key 422s the whole batch, so every field below is exactly one of
 * those.
 *
 * <p><b>Discrete transitions only (I2/I3).</b> One payload per witnessed edge —
 * the plugin reads the FP-SPIKE-SLAYER varp/varbit ids via {@code VarbitChanged}
 * and emits only when a task is assigned or completed, never per {@code
 * SLAYER_COUNT} decrement. Every optional field is left {@code null} (Gson omits
 * it) when the transition carried no such detail — never zero-filled.
 *
 * <p><b>{@code creature} is always a decoded monster name, never a raw id.</b>
 * {@code SLAYER_TARGET} is a cache-internal task id (and {@code 98} is a boss
 * sentinel, not a monster); the collector decodes it through the game
 * DBTable API and, if that decode cannot resolve, emits nothing rather than
 * shipping a meaningless integer (witnessed-or-absent).
 */
public class SlayerTaskUpdate extends PluginPayload
{
	/** Slayer monster the task is for; decoded name, required (server {@code min_length=1}). */
	public String creature;

	/** Assigned/remaining task size; absent when the transition carried none. Server {@code >= 0}. */
	public Integer amount;

	/** Task location qualifier (e.g. "Catacombs of Kourend"); absent when the task is not area-restricted. */
	public String location;

	/** Account slayer task streak at the transition; absent when unavailable. Server {@code >= 0}. */
	public Integer streak;

	/** Account slayer points at the transition; absent when unavailable. Server {@code >= 0}. */
	public Integer points;

	/** Which discrete edge this row is. Required. */
	public SlayerTransition transition;

	/**
	 * Slayer task lifecycle transition. The string values match the backend's
	 * growable {@code SlayerTransition} enum exactly ({@code plugin.py}); only the
	 * two edges the plugin can actually witness — a task being assigned and a task
	 * being completed — are signalled. Gson serializes each via its
	 * {@link SerializedName}, so drifting either side silently reclassifies rows.
	 */
	public enum SlayerTransition
	{
		@SerializedName("assigned")
		ASSIGNED,
		@SerializedName("completed")
		COMPLETED
	}
}
