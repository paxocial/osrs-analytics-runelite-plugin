/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.function.LongSupplier;

/**
 * Bounded re-observation timer for a passively-scanned lane (achievement diary,
 * combat achievements, quests).
 *
 * <p>These lanes read their varbits/varps on a fixed scan and emit <b>only on
 * change</b>. That is correct for change detection but it starves the server-side
 * {@code observed_at} freshness watermark: an account that simply keeps a lane
 * unchanged never re-witnesses it, so {@code observed_at} ages until the lane
 * reads "stale — observed a day ago" even though it is being read every scan. An
 * unchanged read <i>is</i> a witness; it should refresh the observation watermark.
 *
 * <p>This helper is the bounded clock gate that decides when. It holds the
 * wall-clock ms of the lane's last emit (a real change <b>or</b> a re-observation)
 * and reports "due" once the bounded interval has elapsed since then. When due,
 * the collector re-sends the lane's current witnessed state: the same value with a
 * fresh witness time, so the server's compare-dedup refreshes {@code observed_at}
 * <b>without recording a change row</b> (witnessed-or-absent — the witness time is
 * real, the value did not change, no new change event is fabricated).
 *
 * <p>It never sends anything itself — the collector owns emission — and it
 * re-observes nothing until the lane has been witnessed at least once, so a
 * never-seen lane is never fabricated into existence. Reset on account change /
 * logout, in lockstep with the collector's change-detection baseline, so one
 * account's freshness clock can never carry into another.
 *
 * <p>Not thread-safe: collectors call it only from the client thread.
 */
final class ObservationWatermark
{
	/** Default bounded cadence: re-observe an unchanged lane at most once an hour. */
	static final long DEFAULT_REOBSERVE_INTERVAL_MS = 3_600_000L;

	/** Sentinel: the lane has not been witnessed yet for the current account. */
	private static final long NEVER = 0L;

	private final long reobserveIntervalMs;
	private final LongSupplier clockMs;
	private long lastObservedMs = NEVER;

	ObservationWatermark(long reobserveIntervalMs, LongSupplier clockMs)
	{
		this.reobserveIntervalMs = reobserveIntervalMs;
		this.clockMs = clockMs;
	}

	/**
	 * Record that the lane just emitted (a real change or a re-observation). This
	 * refreshes {@code observed_at} server-side and restarts the bounded interval.
	 */
	void markObserved()
	{
		lastObservedMs = clockMs.getAsLong();
	}

	/**
	 * True when the lane has been witnessed at least once but has not emitted within
	 * the bounded interval — i.e. it is going stale and should re-observe its current
	 * state now. False before the first emit (nothing to re-observe) and while still
	 * inside the interval.
	 */
	boolean dueForReobservation()
	{
		if (lastObservedMs == NEVER)
		{
			return false;
		}
		return clockMs.getAsLong() - lastObservedMs >= reobserveIntervalMs;
	}

	/**
	 * Whether this scan should emit for a key: either its state genuinely changed, or
	 * the lane is due for a bounded re-observation. The single decision the diary /
	 * combat-achievement / quest collectors share so none of them re-implements the
	 * "changed OR stale" rule inline.
	 */
	boolean shouldEmit(boolean changed)
	{
		return changed || dueForReobservation();
	}

	/** Forget the watermark on account change / logout. */
	void reset()
	{
		lastObservedMs = NEVER;
	}
}
