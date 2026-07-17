/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

/**
 * Telemetry categories. Each maps to one list on {@code BatchPayload} (and to
 * one single-event endpoint under {@code /api/v1/plugin}). The plugin sends via
 * the batch endpoint; the single endpoints exist server-side for parity.
 */
public enum EventCategory
{
	SESSION,
	XP,
	COLLECTION_LOG,
	QUEST,
	DIARY,
	COMBAT_ACHIEVEMENT,
	EQUIPMENT,
	LOOT,
	ACTIVITY,
	BANK,
	// Appended last on purpose: the status panel and AnalyticsClient index
	// accepted-event counts by ordinal(), so new categories must not shift the
	// existing ordinals.
	COLLECTION_PAGE
}
