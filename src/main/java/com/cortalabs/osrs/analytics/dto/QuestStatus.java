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
}
