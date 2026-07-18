/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.ActivityClassificationCollector.ActivityAccumulator;
import com.cortalabs.osrs.analytics.dto.ActivityBreakdown;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link ActivityAccumulator} and {@link ActivityClassificationCollector#toCappedBuckets}:
 * per-bucket tick accumulation and the ticks->seconds transform, honest handling of
 * unmapped ({@code unknown}) and empty ({@code idle}) animations (I2), the account-switch
 * fabrication guard (I4), the 128-bucket cap (I3), and the wire-pattern validity of the
 * closed vocabulary.
 */
public class ActivityClassificationCollectorTest
{
	/** The server contract for an activity bucket token. */
	private static final Pattern BUCKET_PATTERN = Pattern.compile("^[a-z_]{1,40}$");

	private static int secondsFor(List<ActivityBreakdown.ActivityBucket> buckets, String bucket)
	{
		for (ActivityBreakdown.ActivityBucket b : buckets)
		{
			if (b.bucket.equals(bucket))
			{
				return b.seconds;
			}
		}
		return -1;
	}

	@Test
	public void accumulatesPerBucketAndConvertsTicksToSeconds()
	{
		ActivityAccumulator acc = new ActivityAccumulator(new AnimationActivityMap());
		acc.bindAccount(1L);
		for (int i = 0; i < 10; i++)
		{
			acc.recordTick(879, "Zezima"); // WOODCUTTING_BRONZE -> woodcutting
		}
		for (int i = 0; i < 5; i++)
		{
			acc.recordTick(624, "Zezima"); // MINING_RUNE_PICKAXE -> mining
		}

		List<ActivityBreakdown.ActivityBucket> buckets = acc.buckets();
		assertEquals(2, buckets.size());
		assertEquals("10 ticks * 0.6 = 6s", 6, secondsFor(buckets, AnimationActivityMap.WOODCUTTING));
		assertEquals("5 ticks * 0.6 = 3s", 3, secondsFor(buckets, AnimationActivityMap.MINING));
	}

	@Test
	public void unmappedAnimationsAreUnknownAndEmptyAnimationIsIdleNeverGuessed()
	{
		ActivityAccumulator acc = new ActivityAccumulator(new AnimationActivityMap());
		acc.bindAccount(1L);
		for (int i = 0; i < 5; i++)
		{
			acc.recordTick(999999, "Zezima"); // no family -> unknown
		}
		for (int i = 0; i < 10; i++)
		{
			acc.recordTick(-1, "Zezima"); // empty animation -> idle
		}

		List<ActivityBreakdown.ActivityBucket> buckets = acc.buckets();
		assertEquals(3, secondsFor(buckets, AnimationActivityMap.UNKNOWN)); // 5 * 0.6 = 3
		assertEquals(6, secondsFor(buckets, AnimationActivityMap.IDLE));    // 10 * 0.6 = 6
	}

	@Test
	public void everyEmittedBucketSatisfiesTheServerPattern()
	{
		// The closed vocabulary is the only thing the accumulator can emit; each token
		// must satisfy the server's ^[a-z_]{1,40}$ (extra=forbid would 422 otherwise).
		for (String bucket : AnimationActivityMap.BUCKETS)
		{
			assertTrue("bucket '" + bucket + "' violates the wire pattern",
				BUCKET_PATTERN.matcher(bucket).matches());
		}
	}

	@Test
	public void witnessedOrAbsentEmitsNothingForAnEmptySession()
	{
		ActivityAccumulator acc = new ActivityAccumulator(new AnimationActivityMap());
		acc.bindAccount(1L);
		assertFalse(acc.hasData());
	}

	@Test
	public void accountSwitchClearsSoNoCrossAccountBleed()
	{
		ActivityAccumulator acc = new ActivityAccumulator(new AnimationActivityMap());
		acc.bindAccount(100L);
		for (int i = 0; i < 20; i++)
		{
			acc.recordTick(879, "AccountA"); // woodcutting
		}
		acc.bindAccount(200L);
		for (int i = 0; i < 5; i++)
		{
			acc.recordTick(624, "AccountB"); // mining
		}

		List<ActivityBreakdown.ActivityBucket> buckets = acc.buckets();
		assertEquals("only account B's bucket survives", 1, buckets.size());
		assertEquals(3, secondsFor(buckets, AnimationActivityMap.MINING));
		assertEquals("account A's woodcutting must not bleed in", -1,
			secondsFor(buckets, AnimationActivityMap.WOODCUTTING));
		assertEquals("AccountB", acc.rsn());
	}

	@Test
	public void capKeepsTop128BucketsBySeconds()
	{
		Map<String, Long> bucketTicks = new LinkedHashMap<>();
		// 130 synthetic buckets; higher index -> more ticks. Cap mechanics only.
		for (int i = 0; i < 130; i++)
		{
			bucketTicks.put("b_" + (char) ('a' + i % 26) + "_" + i, (long) (i + 10));
		}
		List<ActivityBreakdown.ActivityBucket> capped =
			ActivityClassificationCollector.toCappedBuckets(bucketTicks);
		assertEquals("must cap at the wire max of 128", 128, capped.size());
	}

	@Test
	public void everyAccumulatedBucketRoundsToAtLeastOneSecond()
	{
		// A single tick (0.6s) must still round to >= 1s so the server's seconds >= 1 holds.
		ActivityAccumulator acc = new ActivityAccumulator(new AnimationActivityMap());
		acc.bindAccount(1L);
		acc.recordTick(-1, "Zezima"); // one idle tick
		List<ActivityBreakdown.ActivityBucket> buckets = acc.buckets();
		assertEquals(1, buckets.size());
		assertTrue("one tick must still be >= 1 second", secondsFor(buckets, AnimationActivityMap.IDLE) >= 1);
	}
}
