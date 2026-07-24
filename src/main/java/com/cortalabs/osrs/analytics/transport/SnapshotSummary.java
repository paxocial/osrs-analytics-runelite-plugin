/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

/**
 * One ownership-scoped snapshot row from {@code GET /api/v1/plugin/snapshots}.
 *
 * <p>Immutable transport value. The nullable fields ({@code resolvedMode},
 * {@code totalLevel}, {@code totalXp}) stay {@code null} when the server omits them
 * rather than defaulting to {@code 0} or an empty string — the panel renders "—" for
 * an absent value, never a fabricated zero (witnessed-or-absent).
 */
public final class SnapshotSummary
{
	public final String snapshotId;
	public final String accountId;
	public final String accountName;
	public final String resolvedMode;
	public final String fetchedAt;
	public final Integer totalLevel;
	public final Long totalXp;

	public SnapshotSummary(
		String snapshotId,
		String accountId,
		String accountName,
		String resolvedMode,
		String fetchedAt,
		Integer totalLevel,
		Long totalXp)
	{
		this.snapshotId = snapshotId;
		this.accountId = accountId;
		this.accountName = accountName;
		this.resolvedMode = resolvedMode;
		this.fetchedAt = fetchedAt;
		this.totalLevel = totalLevel;
		this.totalXp = totalXp;
	}
}
