/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import java.util.List;
import java.util.Map;

/**
 * Worn equipment and inventory snapshot. Mirrors {@code EquipmentState}.
 *
 * <p>{@link #equipment} maps a lowercase slot name to an item id (the backend
 * requires integer ids). {@link #inventory} is a list of {@link ItemEntry}
 * ({@code item_id}, {@code quantity}).
 */
public class EquipmentState extends PluginPayload
{
	public Map<String, Integer> equipment;

	public List<ItemEntry> inventory;
}
