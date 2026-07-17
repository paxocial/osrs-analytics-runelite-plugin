/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Text;

/**
 * Captures collection log state two complementary ways (opt-in, privacy):
 *
 * <ul>
 *   <li><b>Incremental</b> — the game chat notice
 *       {@code "New item added to your collection log: X"}. The chat text has no
 *       item id, so {@code item_id} is sent as {@code 0}; the name is reliable.</li>
 *   <li><b>Full-state walk</b> — when a collection log category page is drawn
 *       (RuneLite's {@link ScriptID#COLLECTION_DRAW_LIST}), the obtained items on
 *       that page are read from {@link InterfaceID.Collection#ITEMS_CONTENTS} (an
 *       item is obtained when its widget opacity is {@code 0}) and synced with
 *       real item ids + names. As the player clicks through categories the whole
 *       log is captured. Client-side de-dup keeps each item to one send per
 *       session; the backend upserts by {@code (account_id, item_id)} so re-syncs
 *       are idempotent.</li>
 * </ul>
 *
 * <p>This mirrors the WikiSync / RuneLite chat-commands approach to reading the
 * collection log (script + {@code HEADER_TEXT}/{@code ITEMS_CONTENTS} widgets).
 */
@Singleton
public class CollectionLogCollector
{
	private static final String PREFIX = "New item added to your collection log:";
	private static final String SOURCE = "Collection Log";
	/** Child index of the category title inside the collection log header widget. */
	private static final int HEADER_TITLE_INDEX = 0;
	private static final int MAX_SOURCE_LENGTH = 100;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final ItemManager itemManager;

	/** De-dup guard for the full-state walk: "category|itemId" already sent this RSN. */
	private final Set<String> syncedItems = new HashSet<>();
	private String lastRsn;

	@Inject
	public CollectionLogCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
		this.itemManager = itemManager;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.enabled() || !config.trackCollectionLog())
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		if (message == null || !message.startsWith(PREFIX))
		{
			return;
		}
		String itemName = message.substring(PREFIX.length()).trim();
		if (itemName.endsWith("."))
		{
			itemName = itemName.substring(0, itemName.length() - 1).trim();
		}
		if (itemName.isEmpty())
		{
			return;
		}
		if (itemName.length() > 100)
		{
			itemName = itemName.substring(0, 100);
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}

		CollectionLogEntry payload = new CollectionLogEntry();
		Payloads.base(payload, client, rsn);
		payload.itemId = 0; // not available from chat text
		payload.itemName = itemName;
		payload.quantity = 1;
		payload.source = SOURCE;
		payload.obtainedAt = Payloads.isoNow();
		analytics.enqueue(EventCategory.COLLECTION_LOG, payload);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (!config.enabled() || !config.trackCollectionLog())
		{
			return;
		}
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		if (!rsn.equals(lastRsn))
		{
			lastRsn = rsn;
			syncedItems.clear();
		}
		walkCurrentCategory(rsn);
	}

	private void walkCurrentCategory(String rsn)
	{
		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		if (header == null || header.getChildren() == null)
		{
			return;
		}
		Widget titleWidget = header.getChild(HEADER_TITLE_INDEX);
		String category = titleWidget != null ? titleWidget.getText() : null;
		if (category == null || category.isEmpty())
		{
			return;
		}
		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (items == null || items.getChildren() == null)
		{
			return;
		}
		for (Widget child : items.getChildren())
		{
			// Obtained items are drawn fully opaque; unobtained ones are faded.
			if (child.getOpacity() != 0)
			{
				continue;
			}
			int itemId = child.getItemId();
			if (itemId <= 0)
			{
				continue;
			}
			if (!syncedItems.add(category + '|' + itemId))
			{
				continue; // already synced this item this session
			}
			emitObtained(rsn, category, itemId, Math.max(1, child.getItemQuantity()));
		}
	}

	private void emitObtained(String rsn, String category, int itemId, int quantity)
	{
		CollectionLogEntry payload = new CollectionLogEntry();
		Payloads.base(payload, client, rsn);
		payload.itemId = itemId;
		payload.itemName = itemName(itemId);
		payload.quantity = quantity;
		payload.source = clamp(category);
		payload.obtainedAt = Payloads.isoNow();
		analytics.enqueue(EventCategory.COLLECTION_LOG, payload);
	}

	private String itemName(int itemId)
	{
		try
		{
			String name = itemManager.getItemComposition(itemId).getName();
			return name == null || name.isEmpty() ? "Unknown" : clamp(name);
		}
		catch (RuntimeException ex)
		{
			return "Unknown";
		}
	}

	private static String clamp(String value)
	{
		return value.length() > MAX_SOURCE_LENGTH ? value.substring(0, MAX_SOURCE_LENGTH) : value;
	}
}
