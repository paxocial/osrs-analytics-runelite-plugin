/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.dto.PluginPayload;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Shared helpers for populating the common {@link PluginPayload} fields from
 * live client state. Kept in one place so every collector stamps rsn / world /
 * timestamp / version identically.
 */
public final class Payloads
{
	/** Plugin version; must satisfy the backend's {@code \d+\.\d+\.\d+} contract. */
	public static final String PLUGIN_VERSION = "1.0.0";

	/** RuneLite pads display names with a non-breaking space; normalize it out. */
	private static final char NBSP = ' ';

	private Payloads()
	{
	}

	public static String isoNow()
	{
		return Instant.now().toString();
	}

	/**
	 * Return the local player's display name, sanitized to the backend's 1-12
	 * char contract, or {@code null} when unavailable.
	 */
	public static String rsn(Client client)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		String name = player.getName();
		if (name == null)
		{
			return null;
		}
		name = name.replace(NBSP, ' ').trim();
		if (name.isEmpty() || name.length() > 12)
		{
			return null;
		}
		return name;
	}

	/**
	 * Stamp the common payload fields. {@code world} is included only when it is
	 * within the backend's accepted 300-600 range (otherwise left null, which is
	 * valid for every payload except session events).
	 */
	public static void base(PluginPayload payload, Client client, String rsn)
	{
		payload.rsn = rsn;
		int world = client.getWorld();
		payload.world = (world >= 300 && world <= 600) ? Integer.valueOf(world) : null;
		payload.timestamp = isoNow();
		payload.pluginVersion = PLUGIN_VERSION;
	}
}
