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
	// Appended on purpose: the status panel and AnalyticsClient index accepted-event
	// counts by ordinal(), so new categories must be added at the END and existing
	// ordinals must never shift.
	COLLECTION_PAGE,
	// Wave-A Full Picture aggregate lanes (FP-A4), appended after COLLECTION_PAGE.
	REGION_TIME,
	EFFICIENCY,
	ACTIVITY_TIME,
	// Wave-A Full Picture discrete-event lanes (FP-A5), appended after ACTIVITY_TIME.
	SIGNAL_EVENT,
	GE_TRADE
}
