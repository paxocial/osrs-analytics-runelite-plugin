package com.cortalabs.osrs.analytics;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class AnalyticsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AnalyticsPlugin.class);
		RuneLite.main(args);
	}
}