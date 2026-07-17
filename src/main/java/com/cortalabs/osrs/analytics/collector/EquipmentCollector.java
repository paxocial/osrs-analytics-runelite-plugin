/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.EquipmentState;
import com.cortalabs.osrs.analytics.dto.ItemEntry;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Snapshots worn equipment and inventory. Container changes are debounced so a
 * burst of updates (e.g. re-equipping a set) yields at most one send per
 * {@link #DEBOUNCE_MS}.
 */
@Singleton
public class EquipmentCollector
{
	private static final long DEBOUNCE_MS = 3_000L;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;

	private boolean dirty;
	private long lastSendMs;

	@Inject
	public EquipmentCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		if (id == InventoryID.EQUIPMENT.getId() || id == InventoryID.INVENTORY.getId())
		{
			dirty = true;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackEquipment() || !dirty)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		long nowMs = System.currentTimeMillis();
		if (nowMs - lastSendMs < DEBOUNCE_MS)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		dirty = false;
		lastSendMs = nowMs;
		snapshot(rsn);
	}

	private void snapshot(String rsn)
	{
		EquipmentState payload = new EquipmentState();
		Payloads.base(payload, client, rsn);
		payload.equipment = readEquipment();
		payload.inventory = readInventory();
		analytics.enqueue(EventCategory.EQUIPMENT, payload);
	}

	private Map<String, Integer> readEquipment()
	{
		Map<String, Integer> equipment = new LinkedHashMap<>();
		ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
		if (container == null)
		{
			return equipment;
		}
		Item[] items = container.getItems();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			int idx = slot.getSlotIdx();
			if (idx < 0 || idx >= items.length)
			{
				continue;
			}
			Item item = items[idx];
			if (item != null && item.getId() > 0)
			{
				equipment.put(slot.name().toLowerCase(Locale.ROOT), item.getId());
			}
		}
		return equipment;
	}

	private List<ItemEntry> readInventory()
	{
		List<ItemEntry> inventory = new ArrayList<>();
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container == null)
		{
			return inventory;
		}
		for (Item item : container.getItems())
		{
			if (item != null && item.getId() > 0 && item.getQuantity() > 0)
			{
				inventory.add(new ItemEntry(item.getId(), item.getQuantity()));
			}
		}
		return inventory;
	}
}
