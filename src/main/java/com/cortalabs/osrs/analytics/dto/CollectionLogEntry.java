/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Collection log item obtained event. Mirrors {@code CollectionLogEntry}.
 *
 * <p>Every entry in this keyed stream carries a real {@code item_id} — it is
 * only ever emitted from the full-state walk, which reads ids from the log
 * widgets. The chat notice carries no id and never enters this stream (see
 * {@code CollectionLogCollector}); it contributes only the <i>moment</i>, via
 * {@link #captureProvenance} / {@link #obtainedAt}.
 */
public class CollectionLogEntry extends PluginPayload
{
	/** {@link #captureProvenance}: the acquisition moment was witnessed in chat. */
	public static final String PROVENANCE_CHAT_OBSERVED = "chat_observed";
	/** {@link #captureProvenance}: the item is held; when it was obtained is unknown. */
	public static final String PROVENANCE_WALK_INFERRED = "walk_inferred";

	@SerializedName("item_id")
	public int itemId;

	@SerializedName("item_name")
	public String itemName;

	public int quantity;

	public String source;

	/**
	 * How this item's acquisition moment was captured — the discriminator that
	 * keeps {@link #obtainedAt} honest. One of {@link #PROVENANCE_CHAT_OBSERVED}
	 * or {@link #PROVENANCE_WALK_INFERRED}.
	 */
	@SerializedName("capture_provenance")
	public String captureProvenance;

	/**
	 * ISO-8601 UTC timestamp of the <b>real acquisition moment</b>, set only when
	 * {@link #captureProvenance} is {@link #PROVENANCE_CHAT_OBSERVED} — i.e. the
	 * chat notice fired while the plugin was running and the following walk
	 * resolved it to this item's id.
	 *
	 * <p>{@code null} for {@link #PROVENANCE_WALK_INFERRED}: the collection log
	 * interface exposes no per-item acquisition date, so for an item the plugin
	 * merely found already-obtained the moment is genuinely unknown and is left
	 * absent (Gson omits nulls, so the field is dropped from the wire entirely).
	 * It is never backfilled with the scrape time — that would date a years-old
	 * item to the moment the player last opened their log.
	 */
	@SerializedName("obtained_at")
	public String obtainedAt;
}
