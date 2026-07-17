/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Per-category collection log page summary. Emitted once each time a collection
 * log category page is drawn ({@code ScriptID.COLLECTION_DRAW_LIST}).
 *
 * <p>Where {@link CollectionLogEntry} carries the individual obtained items, this
 * carries the <b>completion denominator</b> for the page — how many of the
 * category's item slots are obtained ({@link #obtainedCount}) out of the total
 * drawn ({@link #totalSlots}) — plus any kill-count lines shown on the page.
 * Together those give "X of Y per category" and drops-per-KC analytics without a
 * per-unobtained-item row explosion.
 *
 * <p>The counts are read directly from the drawn item widgets (an item slot is
 * obtained when its widget opacity is {@code 0}), so {@code total_slots} is the
 * number of item slots on the page and {@code obtained_count} the opaque subset.
 * The inherited {@code timestamp} is the observation time (when the page was
 * walked), not a historical completion date.
 *
 * <p>JSON shape mirrors the intended Catherby backend contract; optional fields
 * are left {@code null} so Gson omits them from the wire.
 */
public class CollectionPageSummary extends PluginPayload
{
	/** Collection log category / page title (e.g. {@code "General Graardor"}). */
	public String category;

	@SerializedName("obtained_count")
	public int obtainedCount;

	@SerializedName("total_slots")
	public int totalSlots;

	/** Kill-count lines shown on the page; omitted when the page shows none. */
	@SerializedName("kill_counts")
	public List<KillCount> killCounts;

	/**
	 * One kill-count line from a collection log page, e.g. the label
	 * {@code "General Graardor kills"} with count {@code 100}. The label is kept
	 * verbatim (minus the trailing count) so the backend can normalize it.
	 */
	public static class KillCount
	{
		public String name;

		public int count;

		public KillCount()
		{
		}

		public KillCount(String name, int count)
		{
			this.name = name;
			this.count = count;
		}
	}
}
