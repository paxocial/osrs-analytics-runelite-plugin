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
}
