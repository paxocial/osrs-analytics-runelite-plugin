/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Common fields shared by every plugin telemetry payload.
 *
 * <p>The JSON shape mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: PluginPayloadBase}). Optional
 * fields are left {@code null} so that Gson omits them from the wire, matching
 * pydantic's "absent means default" semantics.
 */
public class PluginPayload
{
	/** RuneScape display name (1-12 chars). */
	public String rsn;

	/** World number (300-600). Required for session events, optional otherwise. */
	public Integer world;

	/** Event time as an ISO-8601 UTC string (e.g. {@code 2026-07-16T18:41:02.123Z}). */
	public String timestamp;

	/** Plugin version in semver form ({@code \d+\.\d+\.\d+}). */
	@SerializedName("plugin_version")
	public String pluginVersion;
}
