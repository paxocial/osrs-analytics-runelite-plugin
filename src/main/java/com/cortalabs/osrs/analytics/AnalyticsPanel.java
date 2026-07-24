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
import java.awt.Component;
import java.awt.Dimension;
import java.time.ZoneId;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
 *       far (with a per-skill XP breakdown), and progression at a glance. Every value
 *       is witnessed or honestly absent; nothing defaults to zero and no label answers
 *       a different question than its value. Rendered by {@link LedgerContent}.</li>
 *   <li><b>Lookup</b> — a hiscores search for any name, driven by the search box and
 *       the right-click "Analytics lookup" menu.</li>
 * </ul>
 *
 * <p>Rendering is thin: {@link #refresh()} builds a {@link PanelModel} from the
 * transport's atomic snapshot and the client-thread {@link PanelStateTracker}, then
 * hands it to {@link LedgerContent}. It never touches the game client. A single
 * one-second timer keeps the relative clock ("12s ago") honest and observes the
 * transport, which changes on its own scheduler thread with no event to hook;
 * witnessed data changes push a re-render immediately via the tracker's listener.
 */
class AnalyticsPanel extends PluginPanel
{
	private static final int MAX_USERNAME_LENGTH = 12;
	private static final char NBSP = ' ';

	private final AnalyticsClient client;
	private final LookupClient lookupClient;
	private final PanelStateTracker tracker;
	private final ZoneId zone = ZoneId.systemDefault();
	private final Timer refreshTimer;

	private final LedgerContent ledger;
	private final MaterialTabGroup tabGroup;
	private final MaterialTab lookupTab;
	private final CaptureContent captureContent;
	private final SnapshotsContent snapshotsContent;

	// Lookup tab widgets.
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

		ledger = new LedgerContent(() ->
		{
			client.flushNow();
			refresh();
		});

		// On-demand tab actions (C6): capture / report→clipboard / browse. They call
		// the backend directly through the injected transport and are independent of
		// the telemetry master switch. Reports copy through one honesty-checked sink.
		ReportCopier reportCopier = new ReportCopier(client, new SystemClipboardSink());
		captureContent = new CaptureContent(client, this::currentAccount);
		ReportContent reportContent = new ReportContent(client, reportCopier);
		snapshotsContent = new SnapshotsContent(client, reportCopier);

		tabGroup = new MaterialTabGroup(display);
		MaterialTab ledgerTab = new MaterialTab("Ledger", tabGroup, ledger);
		lookupTab = new MaterialTab("Lookup", tabGroup, buildLookupTab());
		MaterialTab captureTab = new MaterialTab("Capture", tabGroup, captureContent);
		// Prefill the account with the watched RSN each time the tab is opened (never
		// clobbering typed input); gather it on the EDT from published tracker state.
		captureTab.setOnSelectEvent(() ->
		{
			captureContent.prefillIfEmpty(currentAccount());
			return true;
		});
		MaterialTab reportTab = new MaterialTab("Report", tabGroup, reportContent);
		MaterialTab snapshotsTab = new MaterialTab("Snapshots", tabGroup, snapshotsContent);
		// Lazy first load: only hit the backend once the user actually opens the tab.
		snapshotsTab.setOnSelectEvent(() ->
		{
			snapshotsContent.ensureLoaded();
			return true;
		});
		tabGroup.setBorder(new EmptyBorder(0, 0, 8, 0));
		tabGroup.addTab(ledgerTab);
		tabGroup.addTab(lookupTab);
		tabGroup.addTab(captureTab);
		tabGroup.addTab(reportTab);
		tabGroup.addTab(snapshotsTab);
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

	/**
	 * Rebuild the render model from live transport + tracked client state and hand it to
	 * the ledger content. Always runs on the EDT (timer tick or the tracker's
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
			tracker.gains(),
			state.progression,
			Payloads.PLUGIN_VERSION);
		ledger.render(model, zone, System.currentTimeMillis());
	}

	/**
	 * The RSN currently witnessed by the tracker, or {@code null} when none is known.
	 * Reads only the tracker's published client-thread state, so it is safe to call on
	 * the EDT (tab select, panel build) — it never touches the game client.
	 */
	private String currentAccount()
	{
		return tracker.clientState().rsn;
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
		label.setForeground(PanelWidgets.INK_DIM);
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
		name.setForeground(PanelWidgets.INK);
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
			subLabel.setForeground(PanelWidgets.INK_DIM);
			subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			lookupResults.add(subLabel);
		}

		if (result.overall != null)
		{
			lookupResults.add(Box.createVerticalStrut(6));
			JLabel overall = new JLabel("Overall " + result.overall.level + "  (" + formatXp(result.overall.experience) + " xp)");
			overall.setForeground(PanelWidgets.OK);
			overall.setAlignmentX(Component.LEFT_ALIGNMENT);
			lookupResults.add(overall);
		}

		if (!result.skills.isEmpty())
		{
			lookupResults.add(Box.createVerticalStrut(8));
			lookupResults.add(PanelWidgets.heading("Skills"));
			JPanel grid = PanelWidgets.grid();
			for (PlayerLookup.SkillRow skill : result.skills)
			{
				grid.add(PanelWidgets.keyLabel(capitalize(skill.name)));
				grid.add(PanelWidgets.valueLabel(Integer.toString(skill.level)));
			}
			lookupResults.add(grid);
		}

		if (!result.activities.isEmpty())
		{
			lookupResults.add(Box.createVerticalStrut(8));
			lookupResults.add(PanelWidgets.heading("Activities"));
			JPanel grid = PanelWidgets.grid();
			for (PlayerLookup.ActivityRow activity : result.activities)
			{
				if (activity.score < 0)
				{
					continue; // unranked
				}
				grid.add(PanelWidgets.keyLabel(capitalize(activity.name)));
				grid.add(PanelWidgets.valueLabel(Long.toString(activity.score)));
			}
			lookupResults.add(grid);
		}

		lookupResults.revalidate();
		lookupResults.repaint();
	}

	// ------------------------------------------------------------------
	// Lookup helpers
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
}
