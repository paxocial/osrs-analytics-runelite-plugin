/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Per-session activity breakdown: witnessed seconds per activity bucket.
 *
 * <p>One payload per session. Mirrors the backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: ActivityBreakdown}), which is
 * {@code extra=forbid}. The server validates each {@code bucket} against
 * {@code ^[a-z_]{1,40}$} but does NOT depend on the animation->bucket map: the
 * plugin owns the closed vocabulary (see {@code AnimationActivityMap}); an unmapped
 * animation ships as {@code unknown}, honestly, never guessed (I2).
 *
 * <p>{@code buckets} is a witnessed accumulator — each entry is a bucket the session
 * actually spent time in. The server caps the list at 128 entries; the collector caps
 * to the same bound before enqueue (I3).
 */
public class ActivityBreakdown extends PluginPayload
{
	@SerializedName("session_id")
	public String sessionId;

	/** Witnessed per-bucket second accumulators for the session (1..128). */
	public List<ActivityBucket> buckets;

	/** Seconds a session spent in one activity bucket (one entry of {@link #buckets}). */
	public static class ActivityBucket
	{
		public String bucket;

		public int seconds;

		public ActivityBucket()
		{
		}

		public ActivityBucket(String bucket, int seconds)
		{
			this.bucket = bucket;
			this.seconds = seconds;
		}
	}
}
