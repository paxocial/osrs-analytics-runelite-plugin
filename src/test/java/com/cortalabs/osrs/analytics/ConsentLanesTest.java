/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the consent-sync lane map (contract C5). The server's deny-by-default
 * consent table is the record of truth; this map is the only thing that can
 * flip a lane to granted, so it must mirror the user's toggles exactly — and
 * the master switch must gate every lane (an OFF plugin syncs deny-all).
 */
public class ConsentLanesTest
{
	private static AnalyticsConfig config(boolean enabled, boolean bank, boolean collectionLog)
	{
		return new AnalyticsConfig()
		{
			@Override
			public boolean enabled()
			{
				return enabled;
			}

			@Override
			public boolean trackBank()
			{
				return bank;
			}

			@Override
			public boolean trackCollectionLog()
			{
				return collectionLog;
			}
		};
	}

	@Test
	public void masterSwitchOffSyncsDenyAll()
	{
		Map<String, Boolean> lanes = AnalyticsPlugin.consentLanes(config(false, true, true));
		assertEquals(20, lanes.size());
		assertFalse("master OFF denies every lane", lanes.containsValue(true));
	}

	@Test
	public void enabledLanesMirrorTheUsersToggles()
	{
		Map<String, Boolean> lanes = AnalyticsPlugin.consentLanes(config(true, true, false));
		assertTrue(lanes.get("bank"));
		assertFalse(lanes.get("collection_log"));
		assertFalse(lanes.get("collection_pages"));
		assertFalse("untoggled lanes stay denied", lanes.get("equipment"));
	}

	@Test
	public void collectionToggleDrivesBothCollectionLanes()
	{
		Map<String, Boolean> lanes = AnalyticsPlugin.consentLanes(config(true, false, true));
		assertTrue(lanes.get("collection_log"));
		assertTrue(lanes.get("collection_pages"));
		assertFalse(lanes.get("bank"));
	}
}
