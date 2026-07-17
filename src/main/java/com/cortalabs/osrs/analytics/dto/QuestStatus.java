/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Quest progress update. Mirrors {@code QuestStatus}.
 */
public class QuestStatus extends PluginPayload
{
	/** {@link #completionProvenance}: the {@code !=complete -> complete} transition was witnessed live. */
	public static final String PROVENANCE_LIVE_WITNESSED = "live_witnessed";
	/** {@link #completionProvenance}: the quest is complete; when it was completed is unknown. */
	public static final String PROVENANCE_WALK_INFERRED = "walk_inferred";

	public enum State
	{
		@SerializedName("not_started")
		NOT_STARTED,
		@SerializedName("in_progress")
		IN_PROGRESS,
		@SerializedName("complete")
		COMPLETE
	}

	@SerializedName("quest_name")
	public String questName;

	/**
	 * RuneLite {@code Quest.getId()} — the stable v2 identity, immune to display
	 * renames and diacritics (those live only in {@link #questName}). Always set by
	 * this plugin; the backend keeps the field nullable so pre-v2 payloads that
	 * carry no id stay keyed on name. Never 0-as-unknown: a value here is always a
	 * real id.
	 */
	@SerializedName("quest_id")
	public Integer questId;

	public State state;

	/**
	 * Classification of the entry: {@code quest}, {@code miniquest},
	 * {@code subquest} (Recipe for Disaster sub-entries) or {@code other}
	 * (Tutorial Island). The RuneLite Quest enum has no type field, so this is
	 * derived plugin-side (see QuestCollector#classify). Backend contract does
	 * not know this field yet; pydantic ignores it until the schema delta lands.
	 */
	@SerializedName("quest_type")
	public String questType;

	/**
	 * Account-wide quest points at emission time (varp 101,
	 * {@code net.runelite.api.gameval.VarPlayerID.QP}). Omitted when null.
	 * Backend contract does not know this field yet; pydantic ignores it until
	 * the schema delta lands.
	 */
	@SerializedName("quest_points")
	public Integer questPoints;

	/**
	 * How this quest's completion moment was captured — the discriminator that
	 * keeps {@link #completedAt} honest. One of {@link #PROVENANCE_LIVE_WITNESSED}
	 * (the plugin saw the {@code !=complete -> complete} transition this session) or
	 * {@link #PROVENANCE_WALK_INFERRED} (the quest was already complete on the first
	 * scan for the account, so the moment is unknown). Mirrors
	 * {@code CollectionLogEntry#captureProvenance}.
	 */
	@SerializedName("completion_provenance")
	public String completionProvenance;

	/**
	 * ISO-8601 UTC timestamp of the <b>witnessed completion moment</b>, set only
	 * when {@link #completionProvenance} is {@link #PROVENANCE_LIVE_WITNESSED} — i.e.
	 * the plugin observed the state cross into complete while it was running.
	 *
	 * <p>{@code null} for {@link #PROVENANCE_WALK_INFERRED}: a quest merely found
	 * already-complete has no knowable completion date, so the moment is left absent
	 * (Gson omits nulls, dropping the field from the wire). It is never backfilled
	 * with the scan clock — that would date a years-old completion to the moment the
	 * plugin first looked. The backend enforces the same invariant: a
	 * {@code walk_inferred} payload's timestamp is discarded, and a
	 * {@code live_witnessed} payload without one is rejected.
	 */
	@SerializedName("completed_at")
	public String completedAt;
}
