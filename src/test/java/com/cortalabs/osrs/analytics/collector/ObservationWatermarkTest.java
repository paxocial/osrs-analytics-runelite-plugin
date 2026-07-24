/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.function.LongSupplier;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for {@link ObservationWatermark}: the bounded re-observation
 * gate that keeps a passively-scanned lane's {@code observed_at} fresh without
 * fabricating change events. Drives a controllable clock so the cadence is
 * deterministic, and mutation-proves the three load-bearing rules:
 *
 * <ul>
 *   <li>a real change always emits (freshness never suppresses a change);</li>
 *   <li>an unchanged lane re-observes only once the bounded interval has elapsed —
 *       so an idle logged-in hour reads {@code current}, not {@code stale};</li>
 *   <li>a lane that has never been witnessed is never re-observed (no fabrication),
 *       and an account switch resets the clock.</li>
 * </ul>
 */
public class ObservationWatermarkTest
{
	private static final long HOUR_MS = 3_600_000L;

	private final long[] clock = {1_000_000L};
	private final LongSupplier clockMs = () -> clock[0];

	private ObservationWatermark watermark()
	{
		return new ObservationWatermark(HOUR_MS, clockMs);
	}

	/**
	 * MUTATION-PROVE (no fabrication before the first witness). A brand-new lane —
	 * even with the clock arbitrarily far ahead — is NOT due to re-observe: there is
	 * nothing witnessed to re-send. Drop the {@code lastObservedMs == NEVER} guard and
	 * this flips, and the collector would emit garbage for a lane it never read.
	 */
	@Test
	public void neverObservedLaneIsNeverDueToReobserve()
	{
		ObservationWatermark w = watermark();
		assertFalse("an unwitnessed lane must not re-observe", w.dueForReobservation());
		clock[0] += 10 * HOUR_MS;
		assertFalse("time alone must not make an unwitnessed lane re-observe", w.dueForReobservation());
		assertFalse("shouldEmit(false) tracks dueForReobservation", w.shouldEmit(false));
	}

	/**
	 * MUTATION-PROVE (a change always emits). {@code shouldEmit(true)} is true
	 * regardless of the freshness clock — the re-observation cadence must never
	 * suppress a genuine change. Make shouldEmit ignore {@code changed} and this fails.
	 */
	@Test
	public void aRealChangeAlwaysEmits()
	{
		ObservationWatermark w = watermark();
		assertTrue("a change on a never-observed lane emits", w.shouldEmit(true));
		w.markObserved();
		assertTrue("a change well inside the interval still emits", w.shouldEmit(true));
	}

	/**
	 * The core cadence: after a first observation, the unchanged lane stays quiet for
	 * the whole bounded interval (observed_at is still fresh) and becomes due exactly
	 * once the interval has elapsed — the "idle logged-in hour reads current" contract.
	 */
	@Test
	public void unchangedLaneReobservesOnlyAfterTheBoundedInterval()
	{
		ObservationWatermark w = watermark();
		w.markObserved();

		clock[0] += HOUR_MS - 1;
		assertFalse("inside the interval the lane is fresh — no re-observation", w.dueForReobservation());
		assertFalse(w.shouldEmit(false));

		clock[0] += 1; // now exactly one hour since the last observation
		assertTrue("at the bounded interval an unchanged lane re-observes to refresh observed_at",
			w.dueForReobservation());
		assertTrue("shouldEmit(false) re-observes once due", w.shouldEmit(false));
	}

	/**
	 * MUTATION-PROVE (the interval is bounded, not one-shot). Re-observing restarts the
	 * clock: after a re-observation the lane goes quiet again for a full interval, so it
	 * re-observes at most once per interval rather than every scan. Remove
	 * {@code markObserved} from the re-observation path and the lane would re-emit on
	 * every scan forever — a fabricated flood.
	 */
	@Test
	public void reobservingRestartsTheInterval()
	{
		ObservationWatermark w = watermark();
		w.markObserved();

		clock[0] += HOUR_MS;
		assertTrue(w.dueForReobservation());

		// The collector emits and marks the lane observed again.
		w.markObserved();
		assertFalse("immediately after re-observing, the lane is fresh again", w.dueForReobservation());

		clock[0] += HOUR_MS - 1;
		assertFalse("still inside the next interval", w.dueForReobservation());
		clock[0] += 1;
		assertTrue("due again only after another full interval", w.dueForReobservation());
	}

	/**
	 * An account switch resets the freshness clock: the new account's lane is treated as
	 * never-witnessed, so it is not re-observed until it has been read and marked once —
	 * one account's staleness clock can never carry into another.
	 */
	@Test
	public void resetReturnsToTheNeverObservedState()
	{
		ObservationWatermark w = watermark();
		w.markObserved();
		clock[0] += 10 * HOUR_MS;
		assertTrue(w.dueForReobservation());

		w.reset();
		assertFalse("after reset the lane is never-observed and must not re-observe", w.dueForReobservation());
		assertFalse(w.shouldEmit(false));
		assertTrue("a change on the fresh account still emits", w.shouldEmit(true));
	}
}
