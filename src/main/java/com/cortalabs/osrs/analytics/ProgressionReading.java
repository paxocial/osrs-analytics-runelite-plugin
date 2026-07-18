/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import java.util.Objects;

/**
 * A witnessed reading of an account's at-a-glance progression: quest points, quests
 * complete, and diary tiers done, as read from live client state.
 *
 * <p><b>Absence is a first-class state.</b> {@link #UNKNOWN} means the plugin has not
 * read this account's progression yet (nobody is logged in, or the read has not run).
 * It is not a reading of zero. The panel renders {@code UNKNOWN} as "Not witnessed
 * yet.", never as {@code 0} — a zero we never read is the product's signature lie.
 * A real reading of an account with genuinely nothing done is {@code known} with
 * zeroed counts, and those honest zeros are shown.
 */
public final class ProgressionReading
{
	/** No reading taken. Renders as honestly absent, never as zero. */
	public static final ProgressionReading UNKNOWN = new ProgressionReading(false, 0, 0, 0, 0);

	final boolean known;
	final int questPoints;
	final int questsComplete;
	final int diaryTiersComplete;
	final int diaryTiersTotal;

	private ProgressionReading(boolean known, int questPoints, int questsComplete,
		int diaryTiersComplete, int diaryTiersTotal)
	{
		this.known = known;
		this.questPoints = questPoints;
		this.questsComplete = questsComplete;
		this.diaryTiersComplete = diaryTiersComplete;
		this.diaryTiersTotal = diaryTiersTotal;
	}

	/** A real, witnessed reading. Counts are floored at zero but never invented. */
	public static ProgressionReading of(int questPoints, int questsComplete, int diaryTiersComplete, int diaryTiersTotal)
	{
		return new ProgressionReading(
			true,
			Math.max(0, questPoints),
			Math.max(0, questsComplete),
			Math.max(0, diaryTiersComplete),
			Math.max(0, diaryTiersTotal));
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ProgressionReading))
		{
			return false;
		}
		ProgressionReading other = (ProgressionReading) o;
		return known == other.known
			&& questPoints == other.questPoints
			&& questsComplete == other.questsComplete
			&& diaryTiersComplete == other.diaryTiersComplete
			&& diaryTiersTotal == other.diaryTiersTotal;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(known, questPoints, questsComplete, diaryTiersComplete, diaryTiersTotal);
	}
}
