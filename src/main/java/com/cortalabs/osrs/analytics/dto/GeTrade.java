/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * A completed Grand Exchange trade — one filled buy or sell offer, the
 * wealth-<i>flow</i> ledger beside our wealth-<i>stock</i> bank snapshots.
 *
 * <p>The JSON shape mirrors the Catherby backend pydantic contract 1:1
 * ({@code src/catherby/api/schemas/plugin.py :: GeTrade}), which is
 * {@code extra=forbid}: only the base {@link PluginPayload} fields plus
 * {@code item_id}, {@code state}, {@code quantity}, {@code spent},
 * {@code price_each} and {@code slot} may appear on the wire.
 *
 * <p><b>Completed offers only, once each (I6).</b> The collector emits exactly one
 * payload when an offer reaches {@code bought}/{@code sold}; in-progress and
 * cancelled offers are not trades. {@code spent} is the total coins moved — a
 * large trade exceeds 32-bit range, so it is carried as a {@code long} (the server
 * column is BIGINT). {@code price_each} and {@code slot} are left {@code null}
 * (Gson omits them) when not carried.
 */
public class GeTrade extends PluginPayload
{
	@SerializedName("item_id")
	public int itemId;

	public GeTradeState state;

	/** Quantity filled on the completed offer (>= 1). */
	public int quantity;

	/** Total coins moved on the completed offer; BIGINT range at rest, hence {@code long}. */
	public long spent;

	/** Per-item price of the offer; absent when not carried. */
	@SerializedName("price_each")
	public Integer priceEach;

	/** GE slot index the offer filled in; absent when not carried. */
	public Integer slot;

	/**
	 * Completed offer direction. The string values match the backend's
	 * {@code GeTradeState} enum exactly ({@code plugin.py}); only completed offers
	 * are ever signalled (bought/sold).
	 */
	public enum GeTradeState
	{
		@SerializedName("bought")
		BOUGHT,
		@SerializedName("sold")
		SOLD
	}
}
