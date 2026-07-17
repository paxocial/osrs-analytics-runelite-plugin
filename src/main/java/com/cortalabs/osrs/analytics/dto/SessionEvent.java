/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Player session lifecycle event (login / logout / world hop).
 *
 * <p>Mirrors {@code SessionEvent} in the backend contract. The backend requires
 * {@code world} to be present for session events.
 */
public class SessionEvent extends PluginPayload
{
	public enum EventType
	{
		@SerializedName("login")
		LOGIN,
		@SerializedName("logout")
		LOGOUT,
		@SerializedName("world_hop")
		WORLD_HOP
	}

	@SerializedName("session_id")
	public String sessionId;

	public EventType event;

	/** Session duration in seconds; populated for logout events only. */
	@SerializedName("duration_seconds")
	public Integer durationSeconds;
}
