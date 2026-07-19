/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * The local player's exact world position, carried ONCE PER POST on
 * {@link BatchPayload#position} — a top-level envelope field alongside
 * {@code rsn}/{@code world}/{@code timestamp}/{@code plugin_version}, never a
 * per-category row.
 *
 * <p>Wire shape (pinned contract):
 * <pre>"position": { "region_id": &lt;int&gt;, "x": &lt;int&gt;, "y": &lt;int&gt;, "plane": &lt;int&gt; }</pre>
 *
 * <p><b>Witnessed-or-absent (I1/I2).</b> The block is present only when a fresh,
 * witnessed local player exists at emission time; otherwise the whole block is
 * OMITTED (Gson drops a null {@link BatchPayload#position}) — never a null, never
 * a {@code 0,0} placeholder, never a stale cached tile. The freshness gate lives
 * in {@code LivePositionCollector} (capture on the client thread, invalidate on
 * session end); this DTO is a dumb value carrier.
 *
 * <p>{@code x}/{@code y} are world tile coordinates and {@code plane} is 0-3.
 * {@code region_id} is the canonical OSRS map region — {@code ((x>>6)<<8)|(y>>6)},
 * which is exactly what {@code WorldPoint.getRegionID()} returns — so it matches
 * the {@code region_id} carried by {@code RegionTick} in the region-time lane. It
 * is READ from the client {@code WorldPoint} at capture time, not re-derived here.
 *
 * <p>This is an additive envelope field: a backend that does not yet know
 * {@code position} ignores the unknown key (the batch model is {@code extra=ignore}),
 * so old and new backends both accept the payload.
 */
public class LivePosition
{
	@SerializedName("region_id")
	public int regionId;

	public int x;

	public int y;

	public int plane;

	public LivePosition()
	{
	}

	public LivePosition(int regionId, int x, int y, int plane)
	{
		this.regionId = regionId;
		this.x = x;
		this.y = y;
		this.plane = plane;
	}
}
