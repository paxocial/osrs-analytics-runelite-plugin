/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.GeTrade;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Grand Exchange trade ledger: turns completed GE offers into append-only
 * {@link GeTrade} rows — the wealth-<i>flow</i> stream beside our wealth-<i>stock</i>
 * bank snapshots.
 *
 * <p><b>Completed offers only, once each (I6).</b> {@link GrandExchangeOfferChanged}
 * fires many times for one offer — every partial fill, and again on login when the
 * client re-syncs all slots. A trade is emitted exactly once, when a slot first
 * enters a terminal {@code BOUGHT}/{@code SOLD} state; in-progress
 * ({@code BUYING}/{@code SELLING}), empty and cancelled states are not trades and
 * emit nothing. The per-slot dedup is a client-free {@link GeSlotTracker} so the
 * once-per-offer guarantee is testable with plain data.
 *
 * <p><b>Account-switch clear (I4).</b> The tracker is bound to the current
 * {@code accountHash}; a change re-arms every slot before the next observation, so a
 * completed offer on account A can never suppress the first completion on account B's
 * same slot — the same fabrication guard the sibling collectors carry.
 *
 * <p>{@code spent} is widened to {@code long} on the wire: a large trade exceeds
 * 32-bit range and the server column is BIGINT, even though the client API exposes an
 * {@code int}.
 */
@Singleton
public class GeTradeCollector
{
	/** OSRS exposes at most 8 Grand Exchange slots. */
	static final int GE_SLOTS = 8;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final GeSlotTracker tracker = new GeSlotTracker(GE_SLOTS);

	@Inject
	public GeTradeCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!config.enabled() || !config.trackGeTrades())
		{
			return;
		}
		GrandExchangeOffer offer = event.getOffer();
		if (offer == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			// Not attributable yet: leave the dedup untouched so a later re-fire still emits.
			return;
		}
		int slot = event.getSlot();
		GrandExchangeOfferState state = offer.getState();
		if (!tracker.shouldEmit(client.getAccountHash(), slot, state))
		{
			return;
		}
		int quantity = offer.getQuantitySold();
		if (quantity <= 0)
		{
			// A terminal offer that filled nothing is not a trade; never emit a zero-qty row.
			return;
		}
		GeTrade payload = new GeTrade();
		Payloads.base(payload, client, rsn);
		payload.itemId = offer.getItemId();
		payload.state = state == GrandExchangeOfferState.BOUGHT
			? GeTrade.GeTradeState.BOUGHT
			: GeTrade.GeTradeState.SOLD;
		payload.quantity = quantity;
		payload.spent = offer.getSpent(); // int -> long: BIGINT range at rest
		payload.priceEach = offer.getPrice();
		payload.slot = slot;
		analytics.enqueue(EventCategory.GE_TRADE, payload);
	}

	/**
	 * Per-slot once-per-completed-offer dedup. Client-free so the two guarantees — one
	 * row per completed offer despite repeated fires (I6) and account-switch re-arm (I4)
	 * — are testable with plain {@link GrandExchangeOfferState} constants, exactly like
	 * {@link AccountKeyedDeltaGuard}.
	 *
	 * <p>Not thread-safe: called only from the client thread.
	 */
	static final class GeSlotTracker
	{
		/** Per slot: has the current completed offer already been emitted? */
		private final boolean[] emitted;
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;

		GeSlotTracker(int slots)
		{
			this.emitted = new boolean[slots];
		}

		/**
		 * Return {@code true} exactly once when {@code slot} first reaches a terminal
		 * {@code BOUGHT}/{@code SOLD} state for its current offer. Any non-terminal
		 * observation (buying/selling/empty/cancelled) re-arms the slot so a genuinely new
		 * offer completing there emits again; a repeat or login-resync fire of an
		 * already-emitted completed offer returns {@code false}. An account change re-arms
		 * every slot first.
		 */
		boolean shouldEmit(long accountHash, int slot, GrandExchangeOfferState state)
		{
			if (accountHash != account)
			{
				account = accountHash;
				Arrays.fill(emitted, false);
			}
			if (slot < 0 || slot >= emitted.length)
			{
				return false; // defensive: unknown slot
			}
			boolean completed = state == GrandExchangeOfferState.BOUGHT
				|| state == GrandExchangeOfferState.SOLD;
			if (!completed)
			{
				// In-progress, empty or cancelled: the current offer is not (or no longer) a
				// completed trade, so re-arm the slot for its next completion.
				emitted[slot] = false;
				return false;
			}
			if (emitted[slot])
			{
				return false; // repeat/resync fire of an offer we already emitted
			}
			emitted[slot] = true;
			return true;
		}
	}
}
