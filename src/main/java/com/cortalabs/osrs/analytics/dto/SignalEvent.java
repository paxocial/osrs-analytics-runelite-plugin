/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * A discrete milestone signal witnessed from a live chat line — the emotional
 * beats a grind-timeline UI lives on (a level-up, a pet drop, a clue completion,
 * a per-kill boss KC, a diary tier finished).
 *
 * <p>The JSON shape mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: SignalEvent}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code signal_type}, {@code subject}, {@code value} and {@code detail} may
 * appear on the wire.
 *
 * <p><b>Witnessed-or-absent (I2).</b> One payload per signal actually seen in
 * chat; the collector never synthesizes a signal, and every optional field is
 * left {@code null} (Gson omits it) when the witnessed line carried no such
 * detail — never zero-filled. {@code value} is the signal's numeric payload (a
 * new level, a kill count, a clue count) and is honestly absent when the line
 * had none.
 */
public class SignalEvent extends PluginPayload
{
	/**
	 * The witnessed chat line, verbatim (clamped to the backend's 500-char
	 * contract). Server column {@code detail} CHECKs {@code <= 500}; the collector
	 * clamps here so a pathological line can never 422 the batch.
	 */
	public static final int MAX_DETAIL = 500;

	@SerializedName("signal_type")
	public SignalType signalType;

	/** What the signal is about (skill / boss / clue tier / diary region); absent when N/A. */
	public String subject;

	/** Numeric payload (new level, kill count, clue count); absent when the line had none. */
	public Integer value;

	/** The witnessed chat line verbatim; absent when N/A. Clamp with {@link #clampDetail}. */
	public String detail;

	/**
	 * Discrete signal kinds. The string values match the backend's growable
	 * {@code SignalType} enum exactly ({@code plugin.py}); Gson serializes each via
	 * its {@link SerializedName}, so drifting either side silently reclassifies rows.
	 */
	public enum SignalType
	{
		@SerializedName("level_up")
		LEVEL_UP,
		@SerializedName("pet")
		PET,
		@SerializedName("clue_completion")
		CLUE_COMPLETION,
		@SerializedName("boss_kc")
		BOSS_KC,
		@SerializedName("diary_completion")
		DIARY_COMPLETION,
		@SerializedName("quest_completion")
		QUEST_COMPLETION
	}

	/** Clamp a witnessed line to the wire contract's 500-char maximum. */
	public static String clampDetail(String line)
	{
		if (line == null)
		{
			return null;
		}
		return line.length() > MAX_DETAIL ? line.substring(0, MAX_DETAIL) : line;
	}
}
