/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import java.util.Collections;
import java.util.List;

/**
 * One page of the calling token's snapshots ({@code GET /api/v1/plugin/snapshots}).
 * Immutable transport value carrying the rows plus the pagination envelope
 * ({@code total}, {@code limit}, {@code offset}) so the panel can show "showing N of
 * total" honestly and page without re-counting.
 */
public final class SnapshotPage
{
	public final List<SnapshotSummary> snapshots;
	public final int total;
	public final int limit;
	public final int offset;

	public SnapshotPage(List<SnapshotSummary> snapshots, int total, int limit, int offset)
	{
		this.snapshots = snapshots == null
			? Collections.emptyList()
			: Collections.unmodifiableList(snapshots);
		this.total = total;
		this.limit = limit;
		this.offset = offset;
	}
}
