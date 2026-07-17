/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.LootDrop;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Collection;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Captures loot drops. NPC kills come through {@link NpcLootReceived}; other
 * sources (chests, clues, minigames, events) come through {@link LootReceived},
 * where NPC records are skipped to avoid double-counting.
 */
@Singleton
public class LootCollector
{
	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final ItemManager itemManager;

	@Inject
	public LootCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!enabled())
		{
			return;
		}
		NPC npc = event.getNpc();
		String source = npc != null && npc.getName() != null ? npc.getName() : "Unknown";
		emitAll(event.getItems(), source, LootDrop.SourceType.NPC);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (!enabled())
		{
			return;
		}
		if (event.getType() == LootRecordType.NPC)
		{
			return; // handled by onNpcLootReceived
		}
		emitAll(event.getItems(), event.getName(), mapSource(event.getType()));
	}

	private boolean enabled()
	{
		return config.enabled() && config.trackLoot();
	}

	private void emitAll(Collection<ItemStack> items, String source, LootDrop.SourceType type)
	{
		if (items == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		for (ItemStack stack : items)
		{
			if (stack == null || stack.getId() <= 0 || stack.getQuantity() <= 0)
			{
				continue;
			}
			emit(rsn, stack, source, type);
		}
	}

	private void emit(String rsn, ItemStack stack, String source, LootDrop.SourceType type)
	{
		LootDrop payload = new LootDrop();
		Payloads.base(payload, client, rsn);
		payload.itemId = stack.getId();
		payload.itemName = itemName(stack.getId());
		payload.quantity = stack.getQuantity();
		payload.geValue = (long) itemManager.getItemPrice(stack.getId()) * stack.getQuantity();
		payload.source = clamp(source == null ? "Unknown" : source, 100);
		payload.sourceType = type;
		analytics.enqueue(EventCategory.LOOT, payload);
	}

	private String itemName(int itemId)
	{
		try
		{
			String name = itemManager.getItemComposition(itemId).getName();
			return clamp(name == null || name.isEmpty() ? "Unknown" : name, 100);
		}
		catch (RuntimeException ex)
		{
			return "Unknown";
		}
	}

	private static LootDrop.SourceType mapSource(LootRecordType type)
	{
		if (type == null)
		{
			return LootDrop.SourceType.OTHER;
		}
		switch (type)
		{
			case NPC:
				return LootDrop.SourceType.NPC;
			case EVENT:
				return LootDrop.SourceType.CHEST;
			case PICKPOCKET:
				return LootDrop.SourceType.OTHER;
			default:
				return LootDrop.SourceType.OTHER;
		}
	}

	private static String clamp(String value, int max)
	{
		return value.length() > max ? value.substring(0, max) : value;
	}
}
