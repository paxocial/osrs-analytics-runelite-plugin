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
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Snapshots bank contents (item ids, quantities, values, and total value)
 * when the bank container changes. Opt-in (privacy); debounced.
 *
 * <p><b>Valuation ({@code ge_then_ha_v1})</b> mirrors RuneLite's own bank
 * plugin: {@code ItemManager.getItemPrice} (ItemManager.java L282-334: coins
 * 1:1, platinum 1000:1, notes canonicalized, untradeable-variant mapping, GE /
 * wiki price per client config) with a high-alchemy fallback via
 * {@code ItemComposition.getHaPrice()} for items with no GE price — the same
 * HA source RuneLite's bank value uses ({@code BankPlugin.getHaPrice},
 * BankPlugin.java L618-629). Without the fallback every untradeable is worth 0
 * and the total under-reports the account (BUG: live session 2026-07-17).
 * Placeholders never contribute: they are quantity-0 entries, excluded by the
 * quantity filter exactly as {@code BankPlugin.calculate} (L591-616) excludes
 * them.
 *
 * <p><b>Debounce is trailing-edge complete</b>: a change suppressed inside the
 * debounce window marks the state dirty, and the next game tick after the
 * window flushes the LATEST cached bank container. Without the flush, the last
 * deposit/withdrawal before closing the bank was silently dropped until the
 * next bank-open — losing exactly the final state a bank-delta comparison
 * needs (mission 2026-07-24: "closing the bank doesn't lose the latest
 * complete state").
 *
 * <p><b>Duplicate suppression is canonical-content based, session-scoped</b>:
 * a send is skipped only when its canonical content (sorted item id +
 * quantity pairs — never timing, never object identity, never prices) equals
 * what was already sent THIS bank session. A changed bank therefore always
 * produces a distinct observation, while widget-refresh event storms cannot
 * spam identical rows. Opening the bank ({@link WidgetLoaded} of the bank
 * interface) re-arms one full re-witness, so each bank visit still yields a
 * fresh observation even when nothing moved — the server needs two DISTINCT
 * observations to honestly claim "unchanged". A plugin restart starts with no
 * cached content and no dirty flag: nothing is sent (and nothing fabricated)
 * until a real bank event is witnessed.
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
	private boolean pendingDirty;
	/** Canonical content of the last payload sent this bank session; null = none. */
	private String lastSentContentKey;

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
		if (!recordChange(System.currentTimeMillis()))
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		snapshot(rsn, event.getItemContainer());
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.trackBank())
		{
			return;
		}
		if (!shouldFlushPending(System.currentTimeMillis()))
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		// The cached container carries the latest complete bank state even
		// after the bank interface closes; a missing container means nothing
		// was witnessed, so nothing is sent (and nothing fabricated).
		ItemContainer container = client.getItemContainer(InventoryID.BANK);
		pendingDirty = false;
		if (container == null)
		{
			return;
		}
		lastSendMs = System.currentTimeMillis();
		snapshot(rsn, container);
	}

	/**
	 * Leading-edge debounce decision. Returns true when the change should be
	 * sent immediately (and marks it sent); a suppressed change flags the
	 * dirty state for the trailing flush instead of being dropped.
	 */
	boolean recordChange(long nowMs)
	{
		if (nowMs - lastSendMs < DEBOUNCE_MS)
		{
			pendingDirty = true;
			return false;
		}
		lastSendMs = nowMs;
		pendingDirty = false;
		return true;
	}

	/** True when a suppressed change is waiting and the debounce window passed. */
	boolean shouldFlushPending(long nowMs)
	{
		return pendingDirty && nowMs - lastSendMs >= DEBOUNCE_MS;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			newBankSession();
		}
	}

	/**
	 * Opening the bank starts a fresh witnessing session: the duplicate guard
	 * is re-armed so this visit's complete state is sent even when identical
	 * to the previous visit's — a NEW observation is exactly what lets the
	 * server distinguish a witnessed "unchanged" bank from a reused one.
	 */
	void newBankSession()
	{
		lastSentContentKey = null;
	}

	/**
	 * Canonical bank content: sorted {@code id:quantity} pairs. Deliberately
	 * excludes prices and timing so duplicate suppression can only ever
	 * compare what the bank actually holds.
	 */
	static String contentKey(List<ItemEntry> items)
	{
		List<String> pairs = new ArrayList<>(items.size());
		for (ItemEntry item : items)
		{
			pairs.add(item.itemId + ":" + item.quantity);
		}
		pairs.sort(null);
		return String.join(",", pairs);
	}

	/**
	 * Session-scoped duplicate decision: identical canonical content is
	 * suppressed, anything else sends and becomes the new session content. A
	 * changed bank can therefore never be discarded by deduplication.
	 */
	boolean shouldSendContent(String contentKey)
	{
		if (contentKey.equals(lastSentContentKey))
		{
			return false;
		}
		lastSentContentKey = contentKey;
		return true;
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
			long stackValue = unitValue(item.getId()) * item.getQuantity();
			items.add(new ItemEntry(item.getId(), item.getQuantity(), stackValue));
			totalValue += stackValue;
		}
		if (!shouldSendContent(contentKey(items)))
		{
			return;
		}

		BankSnapshot payload = new BankSnapshot();
		Payloads.base(payload, client, rsn);
		payload.items = items;
		payload.totalValue = totalValue;
		payload.valuationMethod = "ge_then_ha_v1";
		analytics.enqueue(EventCategory.BANK, payload);
	}

	/**
	 * Per-unit value: GE price when the item has one (coins/platinum are 1/1000
	 * inside {@code getItemPrice}), otherwise the high-alchemy price so
	 * untradeables are not reported as worthless. Never negative.
	 */
	private long unitValue(int itemId)
	{
		int gePrice = itemManager.getItemPrice(itemId);
		if (gePrice > 0)
		{
			return gePrice;
		}
		return Math.max(0, itemManager.getItemComposition(itemId).getHaPrice());
	}
}
