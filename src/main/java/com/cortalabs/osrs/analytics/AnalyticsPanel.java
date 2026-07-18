/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.collector.PanelStateTracker;
import com.cortalabs.osrs.analytics.collector.Payloads;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.LookupClient;
import com.cortalabs.osrs.analytics.transport.PlayerLookup;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.time.ZoneId;
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
 * The Catherby side panel. It answers one question plainly: is Catherby seeing my
 * play right now, and what has it seen? Two tabs:
 *
 * <ul>
 *   <li><b>Ledger</b> — connection truth, the account being watched, the session so
 *       far, and the account's progression at a glance. Every value is witnessed or
 *       honestly absent; nothing defaults to zero and no label answers a different
 *       question than its value.</li>
 *   <li><b>Lookup</b> — a hiscores search for any name, driven by the search box and
 *       the right-click "Analytics lookup" menu.</li>
 * </ul>
 *
 * <p>Rendering is thin: the panel reads a {@link PanelModel} built from the transport's
 * atomic snapshot and the client-thread {@link PanelStateTracker}, and updates its
 * labels in place. It never touches the game client. A single one-second timer keeps
 * the relative clock ("12s ago") honest and observes the transport, which changes on
 * its own scheduler thread with no event to hook; witnessed data changes push a
 * re-render immediately via the tracker's listener.
 */
class AnalyticsPanel extends PluginPanel
{
	private static final int MAX_USERNAME_LENGTH = 12;
	private static final char NBSP = ' ';

	private static final Color OK_COLOR = new Color(76, 175, 80);
	private static final Color WARN_COLOR = new Color(255, 179, 0);
	private static final Color ERROR_COLOR = new Color(229, 57, 53);
	private static final Color IDLE_COLOR = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color OFF_COLOR = new Color(124, 118, 110);
	private static final Color INK = Color.WHITE;
	private static final Color INK_DIM = ColorScheme.LIGHT_GRAY_COLOR;
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	private final AnalyticsClient client;
	private final LookupClient lookupClient;
	private final PanelStateTracker tracker;
	private final ZoneId zone = ZoneId.systemDefault();
	private final Timer refreshTimer;

	private final MaterialTabGroup tabGroup;
	private final MaterialTab lookupTab;

	// --- Ledger tab widgets (updated in place by refresh()) ---
	private final JLabel connDot = new JLabel("●");
	private final JLabel connLine = new JLabel();
	private final JLabel sentValue = new JLabel();
	private final JLabel queueValue = new JLabel();
	private final JLabel backendValue = new JLabel();

	private final JLabel acctName = new JLabel();
	private final JLabel acctType = new JLabel();

	private final JPanel sessionGrid = grid();
	private final JLabel sessionEmpty = stewardLine();
	private final JLabel xpGainedValue = valueLabel();
	private final JLabel skillsValue = valueLabel();
	private final JLabel snapshotsValue = valueLabel();

	private final JPanel progressionGrid = grid();
	private final JLabel progressionEmpty = stewardLine();
	private final JLabel questPointsValue = valueLabel();
	private final JLabel questsValue = valueLabel();
	private final JLabel diaryValue = valueLabel();

	private final JLabel versionValue = new JLabel();

	// Cards whose contents swap between a grid and a steward line; revalidated on flip.
	private final JPanel sessionCard;
	private final JPanel progressionCard;
	private final JPanel accountCard;

	// Last-rendered structural state, so we revalidate only when a layout actually changes.
	private Boolean lastSessionKnown;
	private Boolean lastProgressionKnown;
	private Boolean lastQueueVisible;
	private AccountPresence lastPresence;

	// --- Lookup tab widgets ---
	private final IconTextField searchBar = new IconTextField();
	private final JPanel lookupResults = new JPanel();

	AnalyticsPanel(AnalyticsClient client, LookupClient lookupClient, PanelStateTracker tracker)
	{
		super(false);
		this.client = client;
		this.lookupClient = lookupClient;
		this.tracker = tracker;

		setBorder(new EmptyBorder(10, 10, 10, 10));
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel display = new JPanel(new BorderLayout());
		display.setBackground(ColorScheme.DARK_GRAY_COLOR);

		// Cards that own a grid/steward-line swap are built before the tab so the fields exist.
		accountCard = buildAccountCard();
		sessionCard = buildSessionCard();
		progressionCard = buildProgressionCard();

		tabGroup = new MaterialTabGroup(display);
		MaterialTab ledgerTab = new MaterialTab("Ledger", tabGroup, buildLedgerTab());
		lookupTab = new MaterialTab("Lookup", tabGroup, buildLookupTab());
		tabGroup.setBorder(new EmptyBorder(0, 0, 8, 0));
		tabGroup.addTab(ledgerTab);
		tabGroup.addTab(lookupTab);
		tabGroup.select(ledgerTab);

		add(tabGroup, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		refreshTimer = new Timer(1000, e -> refresh());
		refresh();
	}

	/** Begin the one-second clock/transport refresh. Called from the plugin's startUp. */
	void start()
	{
		refreshTimer.start();
	}

	/** Stop the refresh timer. Called from the plugin's shutDown. */
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

	// ------------------------------------------------------------------
	// Ledger tab
	// ------------------------------------------------------------------

	private JPanel buildLedgerTab()
	{
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(buildConnectionCard());
		body.add(Box.createVerticalStrut(8));
		body.add(accountCard);
		body.add(Box.createVerticalStrut(8));
		body.add(sessionCard);
		body.add(Box.createVerticalStrut(8));
		body.add(progressionCard);
		body.add(Box.createVerticalStrut(10));
		body.add(buildFooter());
		return body;
	}

	private JPanel buildConnectionCard()
	{
		JPanel card = card();

		connDot.setForeground(IDLE_COLOR);
		connLine.setFont(FontManager.getRunescapeBoldFont());
		connLine.setForeground(INK);
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setBackground(card.getBackground());
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.add(connDot, BorderLayout.WEST);
		head.add(connLine, BorderLayout.CENTER);
		card.add(head);

		card.add(Box.createVerticalStrut(6));
		card.add(valueRow("Last sent", sentValue));

		queueValue.setFont(FontManager.getRunescapeSmallFont());
		queueValue.setForeground(INK_DIM);
		queueValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		queueValue.setBorder(new EmptyBorder(2, 0, 0, 0));
		card.add(queueValue);

		backendValue.setFont(FontManager.getRunescapeSmallFont());
		backendValue.setForeground(OFF_COLOR);
		backendValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		backendValue.setBorder(new EmptyBorder(6, 0, 0, 0));
		card.add(backendValue);

		return card;
	}

	private JPanel buildAccountCard()
	{
		JPanel card = card();
		card.add(heading("Account"));
		card.add(Box.createVerticalStrut(4));

		acctName.setFont(FontManager.getRunescapeBoldFont());
		acctName.setForeground(INK);
		acctName.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(acctName);

		acctType.setFont(FontManager.getRunescapeSmallFont());
		acctType.setForeground(INK_DIM);
		acctType.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(acctType);

		return card;
	}

	private JPanel buildSessionCard()
	{
		JPanel card = card();
		card.add(heading("This session"));
		card.add(Box.createVerticalStrut(4));

		sessionGrid.add(keyLabel("XP gained"));
		sessionGrid.add(xpGainedValue);
		sessionGrid.add(keyLabel("Skills advanced"));
		sessionGrid.add(skillsValue);
		sessionGrid.add(keyLabel("Snapshots sent"));
		sessionGrid.add(snapshotsValue);
		card.add(sessionGrid);
		card.add(sessionEmpty);

		return card;
	}

	private JPanel buildProgressionCard()
	{
		JPanel card = card();
		card.add(heading("At a glance"));
		card.add(Box.createVerticalStrut(4));

		progressionGrid.add(keyLabel("Quest points"));
		progressionGrid.add(questPointsValue);
		progressionGrid.add(keyLabel("Quests"));
		progressionGrid.add(questsValue);
		progressionGrid.add(keyLabel("Diary tiers"));
		progressionGrid.add(diaryValue);
		card.add(progressionGrid);
		card.add(progressionEmpty);

		return card;
	}

	private JPanel buildFooter()
	{
		JPanel footer = new JPanel();
		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		footer.setAlignmentX(Component.LEFT_ALIGNMENT);

		JButton send = new JButton("Send now");
		send.setFocusPainted(false);
		send.setForeground(INK);
		send.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		send.setAlignmentX(Component.LEFT_ALIGNMENT);
		send.setMaximumSize(new Dimension(Integer.MAX_VALUE, send.getPreferredSize().height));
		send.addActionListener(e ->
		{
			client.flushNow();
			refresh();
		});
		footer.add(send);

		versionValue.setFont(FontManager.getRunescapeSmallFont());
		versionValue.setForeground(OFF_COLOR);
		versionValue.setAlignmentX(Component.LEFT_ALIGNMENT);
		versionValue.setBorder(new EmptyBorder(6, 0, 0, 0));
		footer.add(versionValue);

		return footer;
	}

	/**
	 * Rebuild the render model from live transport + tracked client state and update
	 * every label in place. Always runs on the EDT (timer tick or the tracker's
	 * {@code invokeLater} push); reads only atomics and published volatiles, never the
	 * game client.
	 */
	void refresh()
	{
		PanelStateTracker.ClientState state = tracker.clientState();
		AnalyticsClient.Snapshot snapshot = client.snapshot();
		PanelModel model = PanelModel.of(
			snapshot.state,
			snapshot.enabled,
			snapshot.queueDepth,
			snapshot.lastAcceptedMs,
			snapshot.totalAccepted(),
			snapshot.baseUrl,
			state.presence,
			state.rsn,
			state.accountType,
			tracker.xpGained(),
			tracker.skillsAdvanced(),
			state.progression,
			Payloads.PLUGIN_VERSION);

		renderConnection(model);
		renderAccount(model);
		renderSession(model);
		renderProgression(model);
		versionValue.setText("Catherby v" + model.version);
	}

	private void renderConnection(PanelModel model)
	{
		connDot.setForeground(toneColor(model.connectionTone));
		connLine.setText(model.connectionLine);

		if (model.lastAcceptedMs <= 0L)
		{
			sentValue.setText("never");
		}
		else
		{
			long now = System.currentTimeMillis();
			sentValue.setText(PanelText.clock(model.lastAcceptedMs, zone)
				+ " · " + PanelText.since(model.lastAcceptedMs, now));
		}

		boolean queueVisible = model.queueDepth > 0;
		queueValue.setText(queueVisible ? model.queueDepth + " waiting to send" : "");
		queueValue.setVisible(queueVisible);
		if (lastQueueVisible == null || lastQueueVisible != queueVisible)
		{
			lastQueueVisible = queueVisible;
			if (queueValue.getParent() != null)
			{
				queueValue.getParent().revalidate();
			}
		}

		backendValue.setText(model.backendUrl.isEmpty() ? "No backend set" : model.backendUrl);
	}

	private void renderAccount(PanelModel model)
	{
		switch (model.presence)
		{
			case LIVE:
				acctName.setText(model.rsn);
				acctName.setForeground(INK);
				acctType.setText(model.accountType == null ? "" : model.accountType);
				acctType.setForeground(INK_DIM);
				acctType.setVisible(model.accountType != null);
				break;
			case AWAY:
				acctName.setText(model.rsn);
				acctName.setForeground(INK_DIM);
				acctType.setText(model.accountType == null ? "signed out" : model.accountType + " · signed out");
				acctType.setForeground(OFF_COLOR);
				acctType.setVisible(true);
				break;
			case NONE:
			default:
				acctName.setText("No account witnessed yet.");
				acctName.setForeground(INK_DIM);
				acctType.setText("");
				acctType.setVisible(false);
				break;
		}
		if (model.presence != lastPresence)
		{
			lastPresence = model.presence;
			accountCard.revalidate();
			accountCard.repaint();
		}
	}

	private void renderSession(PanelModel model)
	{
		boolean known = model.sessionKnown;
		if (known)
		{
			xpGainedValue.setText(PanelText.integer(model.xpGained));
			skillsValue.setText(PanelText.integer(model.skillsAdvanced));
			snapshotsValue.setText(PanelText.integer(model.snapshotsSent));
		}
		else
		{
			sessionEmpty.setText("Nothing recorded yet.");
		}
		swap(sessionCard, sessionGrid, sessionEmpty, known, lastSessionKnown);
		lastSessionKnown = known;
	}

	private void renderProgression(PanelModel model)
	{
		boolean known = model.progression.known;
		if (known)
		{
			questPointsValue.setText(PanelText.integer(model.progression.questPoints));
			questsValue.setText(PanelText.integer(model.progression.questsComplete));
			diaryValue.setText(PanelText.integer(model.progression.diaryTiersComplete)
				+ " / " + PanelText.integer(model.progression.diaryTiersTotal));
		}
		else
		{
			progressionEmpty.setText("Not witnessed yet.");
		}
		swap(progressionCard, progressionGrid, progressionEmpty, known, lastProgressionKnown);
		lastProgressionKnown = known;
	}

	/** Show the grid or the steward line, and revalidate the card only when it flips. */
	private static void swap(JPanel card, JPanel grid, JLabel empty, boolean showGrid, Boolean previous)
	{
		grid.setVisible(showGrid);
		empty.setVisible(!showGrid);
		if (previous == null || previous != showGrid)
		{
			card.revalidate();
			card.repaint();
		}
	}

	private static Color toneColor(PanelText.Tone tone)
	{
		switch (tone)
		{
			case OK:
				return OK_COLOR;
			case WARN:
				return WARN_COLOR;
			case ERROR:
				return ERROR_COLOR;
			case OFF:
				return OFF_COLOR;
			case IDLE:
			default:
				return IDLE_COLOR;
		}
	}

	// ------------------------------------------------------------------
	// Lookup tab
	// ------------------------------------------------------------------

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
		setLookupMessage("Type a name to read their hiscores.");

		panel.add(searchBar, BorderLayout.NORTH);
		panel.add(lookupResults, BorderLayout.CENTER);
		return panel;
	}

	private void clearLookup()
	{
		searchBar.setIcon(IconTextField.Icon.SEARCH);
		setLookupMessage("Type a name to read their hiscores.");
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
			setLookupMessage("That's too long to be a RuneScape name.");
			return;
		}
		searchBar.setIcon(IconTextField.Icon.LOADING_DARKER);
		searchBar.setEditable(false);
		setLookupMessage("Reading " + username + "…");

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
					setLookupMessage(name + " isn't on the hiscores yet.");
				});
			}

			@Override
			public void onError(String message)
			{
				SwingUtilities.invokeLater(() ->
				{
					searchBar.setIcon(IconTextField.Icon.ERROR);
					searchBar.setEditable(true);
					setLookupMessage(message == null ? "Couldn't read that name." : message);
				});
			}
		});
	}

	private void setLookupMessage(String message)
	{
		lookupResults.removeAll();
		JLabel label = new JLabel(message);
		label.setForeground(INK_DIM);
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
		name.setForeground(INK);
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
			subLabel.setForeground(INK_DIM);
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
			lookupResults.add(heading("Skills"));
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
			lookupResults.add(heading("Activities"));
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

	// ------------------------------------------------------------------
	// Shared widget helpers
	// ------------------------------------------------------------------

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

	private JLabel heading(String text)
	{
		JLabel label = new JLabel(text.toUpperCase());
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(ACCENT);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private static JLabel stewardLine()
	{
		JLabel label = new JLabel();
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	private JPanel grid()
	{
		JPanel grid = new JPanel(new GridLayout(0, 2, 4, 3));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		grid.setAlignmentX(Component.LEFT_ALIGNMENT);
		return grid;
	}

	private JLabel keyLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(INK_DIM);
		return label;
	}

	private static JLabel valueLabel()
	{
		JLabel label = new JLabel();
		label.setForeground(INK);
		label.setHorizontalAlignment(SwingConstants.RIGHT);
		return label;
	}

	private JLabel valueLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(INK);
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

	private JPanel valueRow(String name, JLabel value)
	{
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		panel.setBorder(new EmptyBorder(2, 0, 2, 0));
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel label = new JLabel(name);
		label.setForeground(INK_DIM);
		panel.add(label, BorderLayout.WEST);
		value.setForeground(INK);
		value.setHorizontalAlignment(SwingConstants.RIGHT);
		panel.add(value, BorderLayout.EAST);
		return panel;
	}
}
