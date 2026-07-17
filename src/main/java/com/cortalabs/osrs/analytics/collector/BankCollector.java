/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.BankSnapshot;
import com.cortalabs.osrs.analytics.dto.ItemEntry;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Snapshots bank contents (item ids, quantities, GE values, and total value)
 * when the bank container changes. Opt-in (privacy); debounced.
 */
@Singleton
public class BankCollector
{
	private static final long DEBOUNCE_MS = 5_000L;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final ItemManager itemManager;

	private long lastSendMs;

	@Inject
	public BankCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!config.enabled() || !config.trackBank())
		{
			return;
		}
		if (event.getContainerId() != InventoryID.BANK.getId())
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
		lastSendMs = nowMs;
		snapshot(rsn, event.getItemContainer());
	}

	private void snapshot(String rsn, ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		List<ItemEntry> items = new ArrayList<>();
		long totalValue = 0L;
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			long stackValue = (long) itemManager.getItemPrice(item.getId()) * item.getQuantity();
			items.add(new ItemEntry(item.getId(), item.getQuantity(), stackValue));
			totalValue += stackValue;
		}

		BankSnapshot payload = new BankSnapshot();
		Payloads.base(payload, client, rsn);
		payload.items = items;
		payload.totalValue = totalValue;
		analytics.enqueue(EventCategory.BANK, payload);
	}
}
