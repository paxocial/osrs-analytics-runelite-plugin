/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.LivePositionCollector.PositionHolder;
import com.cortalabs.osrs.analytics.dto.LivePosition;
import java.util.function.LongSupplier;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Guards {@link PositionHolder}: the witnessed-or-absent + freshness contract that
 * keeps a stale or logged-out tile off the wire (I1/I2), and region_id ↔ tile
 * consistency. Client-free with a fake clock, exactly like
 * {@link RegionTimeShareCollectorTest} — the honesty logic is provable with plain
 * data, no RuneLite {@code Client} mock needed.
 *
 * <p>MUTATION PROOF: gut the freshness guard in {@code PositionHolder.current()}
 * (return the tile regardless of age) and {@link #staleCaptureIsOmitted} goes red;
 * make {@code invalidate()} a no-op and {@link #invalidateDropsHeldPositionEvenWhenFresh}
 * goes red. Either mutation lets a stale/logged-out position ride, which these
 * tests forbid.
 */
public class LivePositionCollectorTest
{
	// Lumbridge-ish tile: OSRS map region 12850 == ((3222>>6)<<8)|(3218>>6).
	private static final int LUMBRIDGE_X = 3222;
	private static final int LUMBRIDGE_Y = 3218;
	private static final int LUMBRIDGE_REGION = 12850;

	/** The OSRS region-id definition the wire's region_id must equal for a tile. */
	private static int osrsRegionId(int x, int y)
	{
		return ((x >> 6) << 8) | (y >> 6);
	}

	private static PositionHolder holder(long[] clock)
	{
		LongSupplier clockMs = () -> clock[0];
		return new PositionHolder(clockMs);
	}

	@Test
	public void noCaptureMeansNoPosition()
	{
		long[] clock = {1_000L};
		// Never witnessed a logged-in player: absence, not (0,0).
		assertNull("a holder that never captured emits nothing", holder(clock).current());
	}

	@Test
	public void capturedWitnessedPositionIsReturnedFresh()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);

		LivePosition pos = h.current();
		assertNotNull("a freshly witnessed tile is emitted", pos);
		assertEquals(LUMBRIDGE_REGION, pos.regionId);
		assertEquals(LUMBRIDGE_X, pos.x);
		assertEquals(LUMBRIDGE_Y, pos.y);
		assertEquals(0, pos.plane);
	}

	@Test
	public void regionIdMatchesTheOsrsRegionFormulaForKnownCoords()
	{
		// The wire's region_id is the canonical OSRS region for the tile; pin it to
		// ground truth so a drift in what region_id means is caught. The collector
		// reads getRegionID() and (x,y) from ONE WorldPoint, so runtime consistency
		// is guaranteed by RuneLite; here we prove the value we intend to carry.
		assertEquals("Lumbridge tile maps to region 12850",
			LUMBRIDGE_REGION, osrsRegionId(LUMBRIDGE_X, LUMBRIDGE_Y));

		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		int rid = osrsRegionId(LUMBRIDGE_X, LUMBRIDGE_Y);
		h.capture(rid, LUMBRIDGE_X, LUMBRIDGE_Y, 0);

		LivePosition pos = h.current();
		assertEquals("emitted region_id is the OSRS region for the emitted tile",
			osrsRegionId(pos.x, pos.y), pos.regionId);
	}

	@Test
	public void positionOnTheFreshnessBoundaryIsStillEmitted()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);

		// Exactly at the window edge: still witnessed-current (inclusive bound).
		clock[0] += PositionHolder.FRESHNESS_WINDOW_MS;
		assertNotNull("a capture exactly at the freshness window edge still emits", h.current());
	}

	@Test
	public void staleCaptureIsOmitted()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);

		// One ms past the window: ticks stopped without a session-end clear, so the
		// tile is no longer witnessed-current and must NOT ride as if it were.
		clock[0] += PositionHolder.FRESHNESS_WINDOW_MS + 1;
		assertNull("a stale tile (ticks stopped, no clear) must be omitted", h.current());
	}

	@Test
	public void invalidateDropsHeldPositionEvenWhenFresh()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);
		assertNotNull("captured tile is present before invalidate", h.current());

		// Logout / lane-off: no witnessed local player, so the tile is dropped now,
		// not left to age out of the freshness window.
		h.invalidate();
		assertNull("invalidate() drops the tile immediately, even while fresh", h.current());
	}

	@Test
	public void recaptureAfterInvalidateReturnsTheNewTile()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);
		h.invalidate();

		// A new session captures again: the holder re-arms cleanly.
		int otherRegion = osrsRegionId(2809, 3434); // Catherby-ish tile
		h.capture(otherRegion, 2809, 3434, 0);

		LivePosition pos = h.current();
		assertNotNull(pos);
		assertEquals(otherRegion, pos.regionId);
		assertEquals(2809, pos.x);
		assertEquals(3434, pos.y);
	}

	@Test
	public void latestCaptureWins()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 0);
		clock[0] += 600; // one game tick later, the player moved
		h.capture(osrsRegionId(3223, 3218), 3223, 3218, 0);

		LivePosition pos = h.current();
		assertNotNull(pos);
		assertEquals("the most recent witnessed tile is the one emitted", 3223, pos.x);
	}

	@Test
	public void planeIsCarriedFaithfully()
	{
		long[] clock = {1_000L};
		PositionHolder h = holder(clock);
		// Upstairs (plane 2) must not be flattened to 0.
		h.capture(LUMBRIDGE_REGION, LUMBRIDGE_X, LUMBRIDGE_Y, 2);
		assertEquals(2, h.current().plane);
	}
}
