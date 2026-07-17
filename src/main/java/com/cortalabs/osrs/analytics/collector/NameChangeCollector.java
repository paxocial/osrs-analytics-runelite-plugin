/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.transport.LookupClient;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;

/**
 * Detects a display-name (RSN) change for the logged-in account and submits it
 * once to the backend's {@code POST /names/bulk} surface.
 *
 * <p>The last-seen RSN is persisted per account via {@link ConfigManager},
 * keyed by {@link Client#getAccountHash()} so switching between two of your own
 * accounts is never mistaken for a rename. Submission is one-shot per change
 * (persisting the new name closes the gap) and config-gated.
 */
@Slf4j
@Singleton
public class NameChangeCollector
{
	private static final String LAST_SEEN_PREFIX = "lastseen.";
	private static final long NO_ACCOUNT_HASH = -1L;

	private final Client client;
	private final AnalyticsConfig config;
	private final LookupClient lookupClient;
	private final ConfigManager configManager;

	private boolean checkedThisLogin;

	@Inject
	public NameChangeCollector(Client client, AnalyticsConfig config, LookupClient lookupClient, ConfigManager configManager)
	{
		this.client = client;
		this.config = config;
		this.lookupClient = lookupClient;
		this.configManager = configManager;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!config.enabled() || !config.submitNameChanges())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			checkedThisLogin = false;
			return;
		}
		if (checkedThisLogin)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		long accountHash = client.getAccountHash();
		if (accountHash == NO_ACCOUNT_HASH)
		{
			return;
		}
		checkedThisLogin = true;

		final String key = LAST_SEEN_PREFIX + accountHash;
		String previous = configManager.getConfiguration(AnalyticsConfig.GROUP, key);
		if (previous == null)
		{
			// First time we see this account: record the baseline, nothing to submit.
			configManager.setConfiguration(AnalyticsConfig.GROUP, key, rsn);
			return;
		}
		if (!isRename(previous, rsn))
		{
			return;
		}
		// Rename detected. Persist the new name only once the server gives a final
		// answer, so a transient failure (e.g. the intake endpoint not built yet)
		// retries on the next login instead of being silently dropped.
		final String newName = rsn;
		log.debug("Detected RSN change {} -> {}", previous, newName);
		lookupClient.submitNameChange(previous, newName, new LookupClient.NameChangeOutcome()
		{
			@Override
			public void onFinal()
			{
				configManager.setConfiguration(AnalyticsConfig.GROUP, key, newName);
			}

			@Override
			public void onRetryLater()
			{
				// Keep the old name stored; the next login re-detects and retries.
			}
		});
	}

	/** True when {@code previous} is a real, different prior name from {@code current}. */
	static boolean isRename(String previous, String current)
	{
		return previous != null
			&& current != null
			&& !previous.trim().isEmpty()
			&& !current.trim().isEmpty()
			&& !previous.equalsIgnoreCase(current);
	}
}
