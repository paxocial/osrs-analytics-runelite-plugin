/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

/**
 * The result of an on-demand snapshot capture ({@code POST /api/v1/plugin/snapshot}).
 * Immutable transport value: the persisted snapshot's id plus whether the capture was
 * an idempotent no-op (an identical-progress snapshot already existed), so the panel
 * can say "already up to date" honestly instead of implying fresh work happened.
 */
public final class SnapshotCapture
{
	public final String snapshotDbId;
	public final boolean alreadyIngested;

	public SnapshotCapture(String snapshotDbId, boolean alreadyIngested)
	{
		this.snapshotDbId = snapshotDbId;
		this.alreadyIngested = alreadyIngested;
	}
}
