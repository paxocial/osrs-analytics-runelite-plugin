/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.collector.ActivityClassificationCollector;
import com.cortalabs.osrs.analytics.collector.ActivityCollector;
import com.cortalabs.osrs.analytics.collector.BankCollector;
import com.cortalabs.osrs.analytics.collector.CollectionLogCollector;
import com.cortalabs.osrs.analytics.collector.CombatAchievementCollector;
import com.cortalabs.osrs.analytics.collector.DiaryCollector;
import com.cortalabs.osrs.analytics.collector.EfficiencyCollector;
import com.cortalabs.osrs.analytics.collector.EquipmentCollector;
import com.cortalabs.osrs.analytics.collector.FarmingCollector;
import com.cortalabs.osrs.analytics.collector.GeTradeCollector;
import com.cortalabs.osrs.analytics.collector.LootCollector;
import com.cortalabs.osrs.analytics.collector.NameChangeCollector;
import com.cortalabs.osrs.analytics.collector.NpcKillCollector;
import com.cortalabs.osrs.analytics.collector.PanelStateTracker;
import com.cortalabs.osrs.analytics.collector.QuestCollector;
import com.cortalabs.osrs.analytics.collector.RegionTimeShareCollector;
import com.cortalabs.osrs.analytics.collector.SessionCollector;
import com.cortalabs.osrs.analytics.collector.SignalEventCollector;
import com.cortalabs.osrs.analytics.collector.SlayerCollector;
import com.cortalabs.osrs.analytics.collector.XpCollector;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.LookupClient;
import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

/**
 * Opt-in OSRS gameplay telemetry plugin.
 *
 * <p>Collectors capture events on the client thread and hand them to
 * {@link AnalyticsClient}, which batches and streams them to the local Catherby
 * backend off the client thread. All capture is config-gated; disabling the
 * plugin produces zero network traffic.
 */
@Slf4j
@PluginDescriptor(
	name = "Catherby Analytics",
	description = "Opt-in telemetry: streams gameplay snapshots to a local Catherby analytics backend.",
	tags = {"analytics", "telemetry", "xp", "clan", "osrs", "cortalabs"}
)
public class AnalyticsPlugin extends Plugin
{
	private static final int MAX_BATCH_EVENTS = 256;
	private static final int MAX_QUEUE_SIZE = 5_000;
	private static final String LOOKUP = "Analytics lookup";
	/** Player-only right-click options after which the lookup entry is offered. */
	private static final Set<String> AFTER_OPTIONS = new HashSet<>(Arrays.asList(
		"Message", "Add friend", "Remove friend", "Add ignore", "Remove ignore"));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private MenuManager menuManager;

	@Inject
	private AnalyticsConfig config;

	@Inject
	private AnalyticsClient analyticsClient;

	@Inject
	private LookupClient lookupClient;

	@Inject
	private SessionCollector sessionCollector;

	@Inject
	private XpCollector xpCollector;

	@Inject
	private QuestCollector questCollector;

	@Inject
	private DiaryCollector diaryCollector;

	@Inject
	private CombatAchievementCollector combatAchievementCollector;

	@Inject
	private EquipmentCollector equipmentCollector;

	@Inject
	private LootCollector lootCollector;

	@Inject
	private ActivityCollector activityCollector;

	@Inject
	private RegionTimeShareCollector regionTimeShareCollector;

	@Inject
	private EfficiencyCollector efficiencyCollector;

	@Inject
	private ActivityClassificationCollector activityClassificationCollector;

	@Inject
	private SignalEventCollector signalEventCollector;

	@Inject
	private GeTradeCollector geTradeCollector;

	@Inject
	private SlayerCollector slayerCollector;

	@Inject
	private NpcKillCollector npcKillCollector;

	@Inject
	private FarmingCollector farmingCollector;

	@Inject
	private CollectionLogCollector collectionLogCollector;

	@Inject
	private BankCollector bankCollector;

	@Inject
	private NameChangeCollector nameChangeCollector;

	@Inject
	private PanelStateTracker panelStateTracker;

	private List<Object> collectors;
	private int currentFlushInterval;
	private AnalyticsPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		collectors = Arrays.asList(
			sessionCollector,
			xpCollector,
			questCollector,
			diaryCollector,
			combatAchievementCollector,
			equipmentCollector,
			lootCollector,
			activityCollector,
			regionTimeShareCollector,
			efficiencyCollector,
			activityClassificationCollector,
			signalEventCollector,
			geTradeCollector,
			slayerCollector,
			npcKillCollector,
			farmingCollector,
			collectionLogCollector,
			bankCollector,
			nameChangeCollector,
			panelStateTracker);

		reconfigure();
		analyticsClient.setNotifier(this::notifyPlayer);
		panelStateTracker.reset();
		for (Object collector : collectors)
		{
			eventBus.register(collector);
		}
		currentFlushInterval = config.flushIntervalSeconds();
		analyticsClient.start(currentFlushInterval);

		panel = new AnalyticsPanel(analyticsClient, lookupClient, panelStateTracker);
		// The tracker witnesses on the client thread; hop its change signal onto the EDT.
		panelStateTracker.setChangeListener(() -> SwingUtilities.invokeLater(panel::refresh));
		navButton = NavigationButton.builder()
			.tooltip("Catherby Analytics")
			.icon(buildIcon())
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		panel.start();

		if (config.enableLookup())
		{
			menuManager.addPlayerMenuItem(LOOKUP);
		}

		log.debug("Catherby Analytics started (enabled={})", config.enabled());
	}

	@Override
	protected void shutDown()
	{
		menuManager.removePlayerMenuItem(LOOKUP);
		panelStateTracker.setChangeListener(null);
		if (panel != null)
		{
			panel.stop();
			panel = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (collectors != null)
		{
			for (Object collector : collectors)
			{
				eventBus.unregister(collector);
			}
			collectors = null;
		}
		analyticsClient.stop();
		log.debug("Catherby Analytics stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!AnalyticsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		reconfigure();
		int interval = config.flushIntervalSeconds();
		if (interval != currentFlushInterval)
		{
			analyticsClient.stop();
			analyticsClient.start(interval);
			currentFlushInterval = interval;
		}
		// Keep the right-click lookup option in sync with its toggle.
		menuManager.removePlayerMenuItem(LOOKUP);
		if (config.enableLookup())
		{
			menuManager.addPlayerMenuItem(LOOKUP);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() == MenuAction.RUNELITE_PLAYER && LOOKUP.equals(event.getMenuOption()))
		{
			Player player = client.getTopLevelWorldView().players().byIndex(event.getId());
			if (player != null)
			{
				lookupPlayer(player.getName());
			}
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!config.enableLookup() || !AFTER_OPTIONS.contains(event.getOption()))
		{
			return;
		}
		final String name = Text.toJagexName(Text.removeTags(event.getTarget()));
		if (name.isEmpty())
		{
			return;
		}
		client.getMenu().createMenuEntry(-2)
			.setTarget(event.getTarget())
			.setOption(LOOKUP)
			.setType(MenuAction.RUNELITE)
			.setIdentifier(event.getIdentifier())
			.onClick(e -> lookupPlayer(name));
	}

	/** Open the panel's Lookup tab for a player, off the client thread. */
	private void lookupPlayer(String name)
	{
		if (name == null || name.isEmpty() || panel == null || navButton == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() ->
		{
			clientToolbar.openPanel(navButton);
			panel.lookup(name);
		});
	}

	private void reconfigure()
	{
		analyticsClient.configure(
			config.apiBaseUrl(),
			config.apiKey(),
			config.enabled(),
			MAX_BATCH_EVENTS,
			MAX_QUEUE_SIZE);
	}

	/** Post a single user-facing notice on the client thread. */
	private void notifyPlayer(String message)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.CONSOLE, "", message, null);
			}
		});
	}

	/** Draw a small bar-chart glyph for the toolbar (no binary resource needed). */
	private static BufferedImage buildIcon()
	{
		BufferedImage icon = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = icon.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(new Color(76, 175, 80));
			g.fillRect(4, 13, 4, 7);
			g.fillRect(10, 9, 4, 11);
			g.fillRect(16, 5, 4, 15);
			g.setColor(new Color(200, 200, 200));
			g.setStroke(new BasicStroke(1f));
			g.drawLine(3, 20, 21, 20);
		}
		finally
		{
			g.dispose();
		}
		return icon;
	}

	@Provides
	AnalyticsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AnalyticsConfig.class);
	}
}
