/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.ActivityUpdate;
import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.dto.CollectionPageSummary;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Captures collection log state two complementary ways (opt-in; gated by the
 * single {@code trackCollectionLog} toggle):
 *
 * <ul>
 *   <li><b>Incremental (chat)</b> — the game chat notice
 *       {@code "New item added to your collection log: X"}. The chat text has no
 *       item id, and there is no reliable, low-footprint client-side way to
 *       resolve a collection log item <i>name</i> to its id that also covers
 *       untradeables (RuneLite's {@link ItemManager#search} is GE-tradeables
 *       only; the reference {@code ChatCommandsPlugin} and WikiSync both treat
 *       the chat line purely as a <i>signal</i> and read ids from the log
 *       widgets, never from the name). So the chat catch is <b>not</b> sent into
 *       the keyed collection-log stream — where an {@code item_id == 0} row would
 *       collide with every other id-less row under the backend's
 *       {@code (account_id, item_id)} upsert and clobber it. Instead the moment
 *       is preserved losslessly as an append-only {@link ActivityUpdate}
 *       ({@code activity = "collection_log_drop"}, {@code detail = item name}),
 *       and the real id follows from the next full walk.</li>
 *   <li><b>Full-state walk</b> — when a collection log category page is drawn
 *       (RuneLite's {@link ScriptID#COLLECTION_DRAW_LIST}), the drawn item slots
 *       on that page are read from {@link InterfaceID.Collection#ITEMS_CONTENTS}
 *       (a slot is obtained when its widget opacity is {@code 0}). Each obtained
 *       item is synced with its real id + name into the keyed stream; a per-page
 *       {@link CollectionPageSummary} carries the completion denominator
 *       (obtained vs total slots) and any kill-count lines. Client-side de-dup
 *       keeps each item to one keyed send per session; the backend upserts by
 *       {@code (account_id, item_id)} so re-syncs are idempotent. Because a
 *       newly-obtained item is by definition not yet in the de-dup set, the next
 *       walk of its category carries the real-id truth.</li>
 * </ul>
 *
 * <p>This mirrors the WikiSync / RuneLite chat-commands approach to reading the
 * collection log (script + {@code HEADER_TEXT}/{@code ITEMS_CONTENTS} widgets).
 */
@Singleton
public class CollectionLogCollector
{
	private static final String PREFIX = "New item added to your collection log:";
	/** Activity value for a chat-caught collection log drop (append-only stream). */
	static final String DROP_ACTIVITY = "collection_log_drop";
	/** Child index of the category title inside the collection log header widget. */
	private static final int HEADER_TITLE_INDEX = 0;
	private static final int MAX_SOURCE_LENGTH = 100;

	/**
	 * A collection log page kill-count line: a label followed by its count, e.g.
	 * {@code "General Graardor kills: 1,234"} or {@code "Kills: 100"}. Anchored to
	 * end-of-line so the {@code "Obtained: x/y"} line (which has a {@code /}) never
	 * matches; the category title (no {@code ": <count>"}) never matches either.
	 */
	private static final Pattern KILL_COUNT_LINE = Pattern.compile("^(.+?):\\s*([\\d,]+)$");

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
		String itemName = parseCollectionLogItemName(event.getMessage());
		if (itemName == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}

		// The chat line has no item id, so it must NOT enter the keyed
		// (account_id, item_id) collection-log stream (every id-less row would
		// clobber the last). Preserve the moment losslessly in the append-only
		// activity stream; the real id follows from the next full walk.
		ActivityUpdate payload = new ActivityUpdate();
		Payloads.base(payload, client, rsn);
		payload.activity = DROP_ACTIVITY;
		payload.detail = itemName;
		analytics.enqueue(EventCategory.ACTIVITY, payload);
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

		List<DrawnSlot> slots = new ArrayList<>();
		for (Widget child : items.getChildren())
		{
			slots.add(new DrawnSlot(child.getItemId(), Math.max(1, child.getItemQuantity()), child.getOpacity()));
		}
		PageReduction page = reducePage(slots);

		// Per-item keyed entries — obtained only, real ids only (reducePage never
		// yields an itemId <= 0), de-duped once per RSN per session.
		for (DrawnSlot slot : page.obtained)
		{
			if (!syncedItems.add(category + '|' + slot.itemId))
			{
				continue; // already synced this item this session
			}
			emitObtained(rsn, category, slot.itemId, slot.quantity);
		}

		emitPageSummary(rsn, category, page, header);
	}

	private void emitObtained(String rsn, String category, int itemId, int quantity)
	{
		if (itemId <= 0)
		{
			// Belt-and-suspenders: the keyed stream must never carry an id <= 0.
			return;
		}
		CollectionLogEntry payload = new CollectionLogEntry();
		Payloads.base(payload, client, rsn);
		payload.itemId = itemId;
		payload.itemName = itemName(itemId);
		payload.quantity = quantity;
		payload.source = clamp(category);
		payload.obtainedAt = Payloads.isoNow();
		analytics.enqueue(EventCategory.COLLECTION_LOG, payload);
	}

	private void emitPageSummary(String rsn, String category, PageReduction page, Widget header)
	{
		CollectionPageSummary summary = new CollectionPageSummary();
		Payloads.base(summary, client, rsn);
		summary.category = clamp(category);
		summary.obtainedCount = page.obtainedCount();
		summary.totalSlots = page.totalSlots;
		List<CollectionPageSummary.KillCount> killCounts = parseKillCounts(header);
		summary.killCounts = killCounts.isEmpty() ? null : killCounts;
		analytics.enqueue(EventCategory.COLLECTION_PAGE, summary);
	}

	/**
	 * Read the kill-count lines from a collection log page header. The header's
	 * children are the category title (index {@link #HEADER_TITLE_INDEX}), the
	 * {@code "Obtained: x/y"} line, and zero or more kill-count lines; only the
	 * kill-count lines match {@link #KILL_COUNT_LINE}.
	 */
	private static List<CollectionPageSummary.KillCount> parseKillCounts(Widget header)
	{
		List<CollectionPageSummary.KillCount> out = new ArrayList<>();
		Widget[] children = header.getChildren();
		if (children == null)
		{
			return out;
		}
		for (int i = 0; i < children.length; i++)
		{
			if (i == HEADER_TITLE_INDEX)
			{
				continue; // category title, never a kill-count line
			}
			Widget child = children[i];
			if (child == null)
			{
				continue;
			}
			CollectionPageSummary.KillCount kc = parseKillCountLine(child.getText());
			if (kc != null)
			{
				out.add(kc);
			}
		}
		return out;
	}

	/**
	 * Parse one collection log header line into a kill-count pair, or {@code null}
	 * when the line is not a kill count (the title, the {@code "Obtained: x/y"}
	 * line, blanks, or anything else). Pure and side-effect free for testing.
	 */
	static CollectionPageSummary.KillCount parseKillCountLine(String text)
	{
		if (text == null)
		{
			return null;
		}
		String cleaned = Text.removeTags(text).trim();
		Matcher matcher = KILL_COUNT_LINE.matcher(cleaned);
		if (!matcher.matches())
		{
			return null;
		}
		String name = matcher.group(1).trim();
		String digits = matcher.group(2).replace(",", "");
		if (name.isEmpty() || digits.isEmpty())
		{
			return null;
		}
		try
		{
			return new CollectionPageSummary.KillCount(clamp(name), Integer.parseInt(digits));
		}
		catch (NumberFormatException ex)
		{
			// Out of int range (never a real KC) — treat as not-a-kill-count.
			return null;
		}
	}

	/**
	 * Extract the item name from a collection log chat notice, or {@code null}
	 * when the message is not such a notice. Strips tags, the fixed prefix, a
	 * trailing period, and clamps length. Pure and side-effect free for testing.
	 */
	static String parseCollectionLogItemName(String rawMessage)
	{
		if (rawMessage == null)
		{
			return null;
		}
		String message = Text.removeTags(rawMessage);
		if (message == null || !message.startsWith(PREFIX))
		{
			return null;
		}
		String itemName = message.substring(PREFIX.length()).trim();
		if (itemName.endsWith("."))
		{
			itemName = itemName.substring(0, itemName.length() - 1).trim();
		}
		if (itemName.isEmpty())
		{
			return null;
		}
		return clamp(itemName);
	}

	/**
	 * Reduce the drawn slots of a category page into the obtained items and the
	 * completion counts. A slot counts toward {@code totalSlots} only when it is a
	 * real item slot ({@code itemId > 0}); it is obtained when additionally its
	 * opacity is {@code 0}. This is the single guarantee that no {@code item_id
	 * <= 0} can ever reach the keyed collection-log stream. Pure for testing.
	 */
	static PageReduction reducePage(List<DrawnSlot> slots)
	{
		List<DrawnSlot> obtained = new ArrayList<>();
		int totalSlots = 0;
		for (DrawnSlot slot : slots)
		{
			if (slot.itemId <= 0)
			{
				continue; // empty/placeholder cell, not a real item slot
			}
			totalSlots++;
			if (slot.opacity == 0)
			{
				obtained.add(slot);
			}
		}
		return new PageReduction(obtained, totalSlots);
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

	/** One drawn collection log item cell: its item id, quantity, and opacity. */
	static final class DrawnSlot
	{
		final int itemId;
		final int quantity;
		final int opacity;

		DrawnSlot(int itemId, int quantity, int opacity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.opacity = opacity;
		}
	}

	/** Result of {@link #reducePage}: the obtained slots plus the total slot count. */
	static final class PageReduction
	{
		final List<DrawnSlot> obtained;
		final int totalSlots;

		PageReduction(List<DrawnSlot> obtained, int totalSlots)
		{
			this.obtained = obtained;
			this.totalSlots = totalSlots;
		}

		int obtainedCount()
		{
			return obtained.size();
		}
	}
}
