/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * A single item line used inside inventory and bank payloads.
 *
 * <p>Inventory items serialize as {@code {item_id, quantity}} (the backend
 * validator requires both). Bank items additionally carry {@code value}; when
 * {@code value} is {@code null} it is omitted from the wire.
 */
public class ItemEntry
{
	@SerializedName("item_id")
	public int itemId;

	public int quantity;

	/** Grand Exchange value for this stack (bank items only); omitted when null. */
	public Long value;

	public ItemEntry(int itemId, int quantity)
	{
		this(itemId, quantity, null);
	}

	public ItemEntry(int itemId, int quantity, Long value)
	{
		this.itemId = itemId;
		this.quantity = quantity;
		this.value = value;
	}
}
