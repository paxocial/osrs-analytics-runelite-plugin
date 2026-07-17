/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Collection log item obtained event. Mirrors {@code CollectionLogEntry}.
 *
 * <p>When derived from a chat notification the numeric {@code item_id} is not
 * available; {@code 0} is sent (the backend accepts {@code item_id >= 0}).
 */
public class CollectionLogEntry extends PluginPayload
{
	@SerializedName("item_id")
	public int itemId;

	@SerializedName("item_name")
	public String itemName;

	public int quantity;

	public String source;

	/**
	 * ISO-8601 UTC timestamp when the item was <b>first observed</b> by the plugin
	 * (chat notice or the walk that first synced it), <b>not</b> the historical
	 * date the item was originally acquired — the collection log does not expose
	 * per-item acquisition dates, so items obtained before the plugin was running
	 * are stamped with their first-observed time.
	 */
	@SerializedName("obtained_at")
	public String obtainedAt;
}
