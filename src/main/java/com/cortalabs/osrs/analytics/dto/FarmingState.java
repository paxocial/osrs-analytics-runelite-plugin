/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * A witnessed farming patch state transition (one discrete change).
 *
 * <p>The JSON shape mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: FarmingState}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code patch}, {@code state} and {@code plant} may appear on the wire.
 *
 * <p><b>Discrete transitions only (I2/I3).</b> One payload per witnessed
 * {@code CropState} edge — the plugin reads the FP-SPIKE-FARMING transmit varbits
 * via {@code VarbitChanged} and emits only when a patch's state actually changes,
 * never per varbit tick. An empty (weeds/raked) patch is not a planted crop and
 * emits nothing (witnessed-or-absent), and {@code plant} is left {@code null}
 * (Gson omits it) when the patch carries no identifiable produce (e.g. a dead
 * patch, whose produce the game encodes generically).
 */
public class FarmingState extends PluginPayload
{
	/** Patch identity (region/slot-derived, e.g. "Catherby herb"); required (server {@code min_length=1}). */
	public String patch;

	/** Witnessed patch transition; required. */
	public FarmingPatchState state;

	/** Produce growing in the patch (e.g. "Ranarr"); absent when none/unknown. */
	public String plant;

	/**
	 * A witnessed farming patch transition. The string values match the backend's
	 * growable {@code FarmingPatchState} enum exactly ({@code plugin.py}), which
	 * maps RuneLite's {@code CropState} to the four discrete transitions this lane
	 * persists (GROWING→planted, HARVESTABLE→ready, DISEASED→diseased,
	 * DEAD→dead). RuneLite's remaining {@code CropState} values (EMPTY, FILLING)
	 * are deliberately absent — they are not captured as transitions, so they are
	 * honestly absent from this domain rather than invented. Gson serializes each
	 * via its {@link SerializedName}.
	 */
	public enum FarmingPatchState
	{
		@SerializedName("planted")
		PLANTED,
		@SerializedName("ready")
		READY,
		@SerializedName("diseased")
		DISEASED,
		@SerializedName("dead")
		DEAD
	}
}
