/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * Bank contents snapshot. Mirrors {@code BankSnapshot}.
 *
 * <p>Each item carries {@code item_id}, {@code quantity} and {@code value}
 * (see {@link ItemEntry}). {@link #totalValue} is a long because a full bank
 * can exceed the 32-bit range.
 */
public class BankSnapshot extends PluginPayload
{
	public List<ItemEntry> items;

	@SerializedName("total_value")
	public long totalValue;
}
