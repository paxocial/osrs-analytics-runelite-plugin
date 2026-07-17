/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import com.cortalabs.osrs.analytics.transport.LookupClient;
import com.cortalabs.osrs.analytics.transport.PlayerLookup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;

/**
 * RuneLite side panel with two tabs:
 *
 * <ul>
 *   <li><b>Lookup</b> — a search box that queries the backend's WOM-style
 *       {@code /players/{name}} envelope and renders the player's skills and
 *       activities. Also driven by the right-click "Analytics lookup" menu.</li>
 *   <li><b>Status</b> — live transport state (connection, queue depth,
 *       last-accepted, per-category counts, backend URL) plus a Flush-now
 *       button.</li>
 * </ul>
 *
 * <p>Status refreshes once a second on the Swing EDT from an atomic
 * {@link AnalyticsClient.Snapshot}; lookups run on the transport executor and
 * marshal their result back onto the EDT. No client-thread work happens here.
 */
class AnalyticsPanel extends PluginPanel
{
	private static final int MAX_USERNAME_LENGTH = 12;
	private static final char NBSP = ' ';

	private static final Color OK_COLOR = new Color(76, 175, 80);
	private static final Color WARN_COLOR = new Color(255, 179, 0);
	private static final Color ERROR_COLOR = new Color(229, 57, 53);
	private static final Color IDLE_COLOR = ColorScheme.LIGHT_GRAY_COLOR;

	private final AnalyticsClient client;
	private final LookupClient lookupClient;
	private final Timer refreshTimer;

	private final MaterialTabGroup tabGroup;
	private final MaterialTab lookupTab;

	// Status tab widgets.
	private final JLabel stateDot = new JLabel("●");
	private final JLabel stateValue = new JLabel();
	private final JLabel queueValue = new JLabel();
	private final JLabel lastAcceptedValue = new JLabel();
	private final JLabel totalValue = new JLabel();
	private final JLabel urlValue = new JLabel();
	private final Map<EventCategory, JLabel> categoryValues = new EnumMap<>(EventCategory.class);

	// Lookup tab widgets.
	private final IconTextField searchBar = new IconTextField();
	private final JPanel lookupResults = new JPanel();

	AnalyticsPanel(AnalyticsClient client, LookupClient lookupClient)
	{
		super(false);
		this.client = client;
		this.lookupClient = lookupClient;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		tabGroup = new MaterialTabGroup(display);
		MaterialTab statusTab = new MaterialTab("Status", tabGroup, buildStatusTab());
		lookupTab = new MaterialTab("Lookup", tabGroup, buildLookupTab());
		tabGroup.setBorder(new EmptyBorder(0, 0, 8, 0));
		tabGroup.addTab(statusTab);
		tabGroup.addTab(lookupTab);
		tabGroup.select(statusTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		refreshTimer = new Timer(1000, e -> refreshStatus());
		refreshStatus();
	}

	/** Begin periodic status refresh. Call from the plugin's startUp. */
	void start()
	{
		refreshTimer.start();
	}

	/** Stop periodic status refresh. Call from the plugin's shutDown. */
	void stop()
	{
		refreshTimer.stop();
	}

	/** Open the Lookup tab and search for a player (called from the menu on the EDT). */
	void lookup(String username)
	{
		searchBar.setText(username);
		tabGroup.select(lookupTab);
		doLookup();
	}

	// --- Lookup tab ---

	private JPanel buildLookupTab()
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		searchBar.setIcon(IconTextField.Icon.SEARCH);
		searchBar.setPreferredSize(new Dimension(100, 30));
		searchBar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchBar.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchBar.addActionListener(e -> doLookup());
		searchBar.addClearListener(this::clearLookup);

		lookupResults.setLayout(new BoxLayout(lookupResults, BoxLayout.Y_AXIS));
		lookupResults.setBackground(ColorScheme.DARK_GRAY_COLOR);
		lookupResults.setBorder(new EmptyBorder(8, 0, 0, 0));
		setLookupMessage("Search a player to view their stats.");

		panel.add(searchBar, BorderLayout.NORTH);
		panel.add(lookupResults, BorderLayout.CENTER);
		return panel;
	}

	private void clearLookup()
	{
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		setLookupMessage("Search a player to view their stats.");
	}

	private void doLookup()
	{
		String username = sanitize(searchBar.getText());
		if (username.isEmpty())
		{
			return;
		}
		if (username.length() > MAX_USERNAME_LENGTH)
		{
			searchBar.setIcon(IconTextField.Icon.ERROR);
			setLookupMessage("That name is too long to be a valid RSN.");
			return;
		}
		searchBar.setIcon(IconTextField.Icon.LOADING_DARKER);
		searchBar.setEditable(false);
		setLookupMessage("Looking up " + username + "…");

		lookupClient.lookup(username, new LookupClient.LookupCallback()
		{
			@Override
			public void onSuccess(PlayerLookup result)
			{
				SwingUtilities.invokeLater(() ->
				{
					searchBar.setIcon(IconTextField.Icon.SEARCH);
					searchBar.setEditable(true);
					renderResult(result);
				});
			}

			@Override
			public void onNotFound(String name)
			{
				SwingUtilities.invokeLater(() ->
				{
					searchBar.setIcon(IconTextField.Icon.ERROR);
					searchBar.setEditable(true);
					setLookupMessage(name + " is not registered with the backend yet.");
				});
			}

			@Override
			public void onError(String message)
			{
				SwingUtilities.invokeLater(() ->
				{
					searchBar.setIcon(IconTextField.Icon.ERROR);
					searchBar.setEditable(true);
					setLookupMessage(message == null ? "Lookup failed." : message);
				});
			}
		});
	}

	private void setLookupMessage(String message)
	{
		lookupResults.removeAll();
		JLabel label = new JLabel(message);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		lookupResults.add(label);
		lookupResults.revalidate();
		lookupResults.repaint();
	}

	private void renderResult(PlayerLookup result)
	{
		lookupResults.removeAll();

		JLabel name = new JLabel(result.displayName);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(Color.WHITE);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		lookupResults.add(name);

		StringBuilder sub = new StringBuilder();
		if (result.type != null && !result.type.isEmpty())
		{
			sub.append(result.type);
		}
		if (result.updatedAt != null && !result.updatedAt.isEmpty())
		{
			sub.append(sub.length() > 0 ? " · " : "").append("updated ").append(shortDate(result.updatedAt));
		}
		if (sub.length() > 0)
		{
			JLabel subLabel = new JLabel(sub.toString());
			subLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			lookupResults.add(subLabel);
		}

		if (result.overall != null)
		{
			lookupResults.add(Box.createVerticalStrut(6));
			JLabel overall = new JLabel("Overall " + result.overall.level + "  (" + formatXp(result.overall.experience) + " xp)");
			overall.setForeground(OK_COLOR);
			overall.setAlignmentX(Component.LEFT_ALIGNMENT);
			lookupResults.add(overall);
		}

		if (!result.skills.isEmpty())
		{
			lookupResults.add(Box.createVerticalStrut(8));
			lookupResults.add(sectionHeading("Skills"));
			JPanel grid = grid();
			for (PlayerLookup.SkillRow skill : result.skills)
			{
				grid.add(keyLabel(capitalize(skill.name)));
				grid.add(valueLabel(Integer.toString(skill.level)));
			}
			lookupResults.add(grid);
		}

		if (!result.activities.isEmpty())
		{
			lookupResults.add(Box.createVerticalStrut(8));
			lookupResults.add(sectionHeading("Activities"));
			JPanel grid = grid();
			for (PlayerLookup.ActivityRow activity : result.activities)
			{
				if (activity.score < 0)
				{
					continue; // unranked
				}
				grid.add(keyLabel(capitalize(activity.name)));
				grid.add(valueLabel(Long.toString(activity.score)));
			}
			lookupResults.add(grid);
		}

		lookupResults.revalidate();
		lookupResults.repaint();
	}

	// --- Status tab ---

	private JPanel buildStatusTab()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(statusCard());
		body.add(Box.createVerticalStrut(8));
		body.add(categoryCard());
		body.add(Box.createVerticalStrut(10));
		body.add(flushButton());
		return body;
	}

	private JPanel statusCard()
	{
		JPanel card = card();

		JPanel stateRow = row("Status");
		stateDot.setForeground(IDLE_COLOR);
		JPanel stateWrap = new JPanel(new BorderLayout(4, 0));
		stateWrap.setBackground(card.getBackground());
		stateWrap.add(stateDot, BorderLayout.WEST);
		stateValue.setForeground(Color.WHITE);
		stateWrap.add(stateValue, BorderLayout.CENTER);
		stateRow.add(stateWrap, BorderLayout.EAST);
		card.add(stateRow);

		card.add(valueRow("Queue depth", queueValue));
		card.add(valueRow("Last accepted", lastAcceptedValue));
		card.add(valueRow("Accepted (session)", totalValue));

		urlValue.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		JPanel urlRow = new JPanel(new BorderLayout());
		urlRow.setBackground(card.getBackground());
		urlRow.setBorder(new EmptyBorder(6, 0, 0, 0));
		JLabel urlName = new JLabel("Backend");
		urlName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		urlRow.add(urlName, BorderLayout.NORTH);
		urlRow.add(urlValue, BorderLayout.CENTER);
		card.add(urlRow);

		return card;
	}

	private JPanel categoryCard()
	{
		JPanel card = card();
		card.add(sectionHeading("Accepted by category"));
		card.add(Box.createVerticalStrut(4));

		JPanel grid = grid();
		for (EventCategory category : EventCategory.values())
		{
			grid.add(keyLabel(friendlyName(category)));
			JLabel value = valueLabel("0");
			categoryValues.put(category, value);
			grid.add(value);
		}
		card.add(grid);
		return card;
	}

	private JButton flushButton()
	{
		JButton button = new JButton("Flush now");
		button.setFocusPainted(false);
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
		button.addActionListener(e ->
		{
			client.flushNow();
			refreshStatus();
		});
		return button;
	}

	private void refreshStatus()
	{
		AnalyticsClient.Snapshot snapshot = client.snapshot();

		stateValue.setText(displayState(snapshot));
		stateDot.setForeground(stateColor(snapshot.state));
		queueValue.setText(Integer.toString(snapshot.queueDepth));
		lastAcceptedValue.setText(relativeTime(snapshot.lastAcceptedMs));
		totalValue.setText(Long.toString(snapshot.totalAccepted()));
		urlValue.setText(snapshot.baseUrl == null || snapshot.baseUrl.isEmpty() ? "(not set)" : snapshot.baseUrl);

		for (Map.Entry<EventCategory, JLabel> entry : categoryValues.entrySet())
		{
			entry.getValue().setText(Long.toString(snapshot.acceptedFor(entry.getKey())));
		}
	}

	private static String displayState(AnalyticsClient.Snapshot snapshot)
	{
		if (!snapshot.enabled)
		{
			return "Disabled";
		}
		switch (snapshot.state)
		{
			case OK:
				return "Connected";
			case RATE_LIMITED:
				return "Rate limited";
			case AUTH_FAILED:
				return "API key rejected";
			case UNREGISTERED:
				return "RSN not registered";
			case ERROR:
				return "Retrying";
			case IDLE:
			default:
				return "Idle";
		}
	}

	private static Color stateColor(AnalyticsClient.State state)
	{
		switch (state)
		{
			case OK:
				return OK_COLOR;
			case RATE_LIMITED:
			case UNREGISTERED:
				return WARN_COLOR;
			case AUTH_FAILED:
			case ERROR:
				return ERROR_COLOR;
			case IDLE:
			default:
				return IDLE_COLOR;
		}
	}

	private static String relativeTime(long epochMs)
	{
		if (epochMs <= 0L)
		{
			return "never";
		}
		long deltaS = Math.max(0L, (System.currentTimeMillis() - epochMs) / 1000L);
		if (deltaS < 60L)
		{
			return deltaS + "s ago";
		}
		if (deltaS < 3600L)
		{
			return (deltaS / 60L) + "m ago";
		}
		return (deltaS / 3600L) + "h ago";
	}

	private static String friendlyName(EventCategory category)
	{
		switch (category)
		{
			case SESSION:
				return "Sessions";
			case XP:
				return "XP";
			case COLLECTION_LOG:
				return "Collection log";
			case COLLECTION_PAGE:
				return "Collection pages";
			case QUEST:
				return "Quests";
			case DIARY:
				return "Diaries (regions)";
			case COMBAT_ACHIEVEMENT:
				return "Combat achv";
			case EQUIPMENT:
				return "Equipment";
			case LOOT:
				return "Loot";
			case ACTIVITY:
				return "Activity";
			case BANK:
				return "Bank";
			default:
				return category.name();
		}
	}

	// --- Shared widget helpers ---

	private static String sanitize(String value)
	{
		return value == null ? "" : value.replace(NBSP, ' ').trim();
	}

	private static String capitalize(String value)
	{
		if (value == null || value.isEmpty())
		{
			return "";
		}
		return Character.toUpperCase(value.charAt(0)) + value.substring(1);
	}

	private static String formatXp(long xp)
	{
		if (xp >= 1_000_000L)
		{
			return (xp / 1_000_000L) + "M";
		}
		if (xp >= 1_000L)
		{
			return (xp / 1_000L) + "K";
		}
		return Long.toString(xp);
	}

	private static String shortDate(String iso)
	{
		int t = iso.indexOf('T');
		return t > 0 ? iso.substring(0, t) : iso;
	}

	private JLabel sectionHeading(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel grid()
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 2));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	private JLabel keyLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	private JLabel valueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(Color.WHITE);
		label.setHorizontalAlignment(SwingConstants.RIGHT);
		return label;
	}

	private JPanel card()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			new EmptyBorder(8, 8, 8, 8)));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		return panel;
	}

	private JPanel row(String name)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 0, 2, 0));
		JLabel label = new JLabel(name);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		panel.add(label, BorderLayout.WEST);
		return panel;
	}

	private JPanel valueRow(String name, JLabel value)
	{
		JPanel panel = row(name);
		value.setForeground(Color.WHITE);
		value.setHorizontalAlignment(SwingConstants.RIGHT);
		panel.add(value, BorderLayout.EAST);
		return panel;
	}
}
