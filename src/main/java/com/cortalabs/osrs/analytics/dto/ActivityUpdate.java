/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

/**
 * General, heuristic activity update. Mirrors {@code ActivityUpdate}.
 */
public class ActivityUpdate extends PluginPayload
{
	public String activity;

	/** Optional free-text detail; omitted when null. */
	public String detail;
}
