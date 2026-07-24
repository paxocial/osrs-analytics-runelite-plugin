/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import java.lang.reflect.Method;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Consent-contract tests for {@link AnalyticsConfig}: the plugin ships OFF by
 * default and opt-in only. Every telemetry lane — and the master switch — must
 * default {@code false} so a fresh install sends nothing, and the master switch
 * must carry the RuneLite-required third-party-server warning.
 *
 * <p>The interface's {@code default} methods are invoked directly through an
 * empty anonymous implementation ({@code net.runelite.client.config.Config} is a
 * pure marker interface), so these assert the real shipped defaults, not a mock.
 */
public class AnalyticsConfigTest
{
	private final AnalyticsConfig config = new AnalyticsConfig()
	{
	};

	/** Every {@code boolean} lane, including the master switch, defaults OFF. */
	@Test
	public void everyLaneDefaultsOff()
	{
		assertFalse("master enable must default off (opt-in only)", config.enabled());

		assertFalse(config.trackSessions());
		assertFalse(config.trackXp());
		assertFalse(config.trackQuests());
		assertFalse(config.trackDiaries());
		assertFalse(config.trackCombatAchievements());
		assertFalse(config.trackEquipment());
		assertFalse(config.trackLoot());
		assertFalse(config.trackActivity());
		assertFalse(config.trackRegionTimeShare());
		assertFalse(config.trackEfficiency());
		assertFalse(config.trackActivityClassification());
		assertFalse(config.trackSignalEvents());
		assertFalse(config.trackGeTrades());
		assertFalse(config.trackSlayerTasks());
		assertFalse(config.trackNpcKills());
		assertFalse(config.trackFarmingState());
		assertFalse(config.trackLivePosition());

		assertFalse(config.trackCollectionLog());
		assertFalse(config.trackBank());

		assertFalse(config.enableLookup());
		assertFalse(config.submitNameChanges());
	}

	/**
	 * Structural guard: NO {@code boolean} {@code @ConfigItem} anywhere in the
	 * config may default {@code true}. This catches a future lane added ON by
	 * default (the "hidden gotcha" the consent contract forbids) even if the
	 * explicit list above is not updated.
	 */
	@Test
	public void noBooleanConfigItemDefaultsOn() throws Exception
	{
		for (Method method : AnalyticsConfig.class.getDeclaredMethods())
		{
			if (method.getReturnType() != boolean.class
				|| !method.isDefault()
				|| !method.isAnnotationPresent(ConfigItem.class))
			{
				continue;
			}
			boolean value = (boolean) method.invoke(config);
			assertFalse("Config lane '" + method.getName() + "' must default OFF (opt-in only)", value);
		}
	}

	/**
	 * The master enable carries the RuneLite-required third-party-server warning,
	 * naming that gameplay data is sent to a server not verified by RuneLite.
	 */
	@Test
	public void masterEnableCarriesThirdPartyWarning() throws Exception
	{
		ConfigItem item = AnalyticsConfig.class.getMethod("enabled").getAnnotation(ConfigItem.class);
		assertNotNull("master enable must be a @ConfigItem", item);
		String warning = item.warning();
		assertFalse("master enable must carry a non-empty third-party-server warning", warning.isEmpty());
		assertTrue("warning must state data goes to a third-party server",
			warning.toLowerCase().contains("third-party server"));
		assertTrue("warning must disclaim RuneLite verification",
			warning.toLowerCase().contains("runelite"));
	}

	/** The warning constant is exactly what is bound to the master enable item. */
	@Test
	public void warningConstantIsBoundToEnable() throws Exception
	{
		ConfigItem item = AnalyticsConfig.class.getMethod("enabled").getAnnotation(ConfigItem.class);
		assertEquals(AnalyticsConfig.THIRD_PARTY_WARNING, item.warning());
	}
}
