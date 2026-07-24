/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.SnapshotsCallback;
import com.cortalabs.osrs.analytics.transport.SnapshotPage;
import com.cortalabs.osrs.analytics.transport.SnapshotSummary;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The "Snapshots" tab: browse the snapshots this key owns ({@code GET /snapshots}),
 * newest first, and copy any one's report from the row.
 *
 * <p>Every state is honest: loading, an empty account ("No snapshots yet"), a fetch
 * error with its reason, and populated rows. A row shows only witnessed values —
 * total level / xp render "—" when the server omitted them rather than a fabricated
 * zero. The first load is lazy ({@link #ensureLoaded}, fired when the tab is opened);
 * "Refresh" reloads. Per-row copy reuses {@link ReportCopier}, so the clipboard
 * honesty rule holds here too.
 */
final class SnapshotsContent extends JPanel
{
	private static final int PAGE_SIZE = 25;

	private final AnalyticsClient client;
	private final ReportCopier reportCopier;
	private final JButton refreshButton = new JButton("Refresh");
	private final JLabel status = PanelWidgets.wrappingLabel();
	private final JPanel list = new JPanel();

	private boolean loadedOnce;

	private final ReportCopier.Sink rowCopySink = new ReportCopier.Sink()
	{
		@Override
		public void onCopying()
		{
			setStatus("Fetching the report…", PanelWidgets.INK_DIM);
		}

		@Override
		public void onCopied(String snapshotId)
		{
			setStatus("Report copied to clipboard.", PanelWidgets.OK);
		}

		@Override
		public void onCopyFailed()
		{
			setStatus("Fetched the report, but the clipboard wouldn't take it.", PanelWidgets.ERROR);
		}

		@Override
		public void onFailure(ActionOutcome outcome, String message)
		{
			setStatus(message, PanelWidgets.outcomeTone(outcome));
		}
	};

	SnapshotsContent(AnalyticsClient client, ReportCopier reportCopier)
	{
		this.client = client;
		this.reportCopier = reportCopier;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(PanelWidgets.heading("My snapshots"), BorderLayout.WEST);
		refreshButton.addActionListener(e -> refresh());
		header.add(refreshButton, BorderLayout.EAST);
		header.setMaximumSize(new Dimension(Integer.MAX_VALUE, refreshButton.getPreferredSize().height));
		column.add(header);
		column.add(Box.createVerticalStrut(6));

		column.add(status);
		column.add(Box.createVerticalStrut(8));

		list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(list);

		add(column, BorderLayout.NORTH);
		setStatus("Open to load your snapshots.", PanelWidgets.INK_DIM);
	}

	/** Load once, the first time the tab is shown. Subsequent opens keep the last view. */
	void ensureLoaded()
	{
		if (!loadedOnce)
		{
			refresh();
		}
	}

	/** (Re)load the newest page of snapshots. */
	void refresh()
	{
		loadedOnce = true;
		setBusy(true);
		clearRows();
		setStatus("Loading your snapshots…", PanelWidgets.INK_DIM);
		client.listSnapshots(PAGE_SIZE, 0, new SnapshotsCallback()
		{
			@Override
			public void onSnapshots(SnapshotPage page)
			{
				SwingUtilities.invokeLater(() -> SnapshotsContent.this.onSnapshots(page));
			}

			@Override
			public void onFailure(ActionOutcome outcome, String message)
			{
				SwingUtilities.invokeLater(() -> SnapshotsContent.this.onFailure(outcome, message));
			}
		});
	}

	// --- Render seams (package-private; invoked on the EDT) ---

	void onSnapshots(SnapshotPage page)
	{
		setBusy(false);
		clearRows();
		if (page.snapshots.isEmpty())
		{
			setStatus("No snapshots yet — take one in the Capture tab.", PanelWidgets.IDLE);
			return;
		}
		setStatus("Showing " + page.snapshots.size() + " of " + page.total + ".", PanelWidgets.INK_DIM);
		for (SnapshotSummary summary : page.snapshots)
		{
			list.add(row(summary));
			list.add(Box.createVerticalStrut(6));
		}
		list.revalidate();
		list.repaint();
	}

	void onFailure(ActionOutcome outcome, String message)
	{
		setBusy(false);
		clearRows();
		setStatus(message, PanelWidgets.outcomeTone(outcome));
	}

	private JPanel row(SnapshotSummary summary)
	{
		JPanel card = PanelWidgets.card();

		JLabel name = new JLabel(summary.accountName == null || summary.accountName.isEmpty()
			? "(unnamed account)" : summary.accountName);
		name.setFont(FontManager.getRunescapeBoldFont());
		name.setForeground(PanelWidgets.INK);
		name.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(name);

		String sub = subLine(summary);
		if (!sub.isEmpty())
		{
			JLabel subLabel = new JLabel(sub);
			subLabel.setForeground(PanelWidgets.INK_DIM);
			subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.add(subLabel);
		}

		JLabel totals = new JLabel(totalsLine(summary));
		totals.setForeground(PanelWidgets.INK_DIM);
		totals.setAlignmentX(Component.LEFT_ALIGNMENT);
		card.add(totals);

		card.add(Box.createVerticalStrut(4));
		JButton copy = new JButton("Copy report");
		copy.setAlignmentX(Component.LEFT_ALIGNMENT);
		copy.addActionListener(e -> reportCopier.copy(summary.snapshotId, rowCopySink));
		card.add(copy);

		return card;
	}

	private static String subLine(SnapshotSummary summary)
	{
		StringBuilder sb = new StringBuilder();
		if (summary.resolvedMode != null && !summary.resolvedMode.isEmpty())
		{
			sb.append(summary.resolvedMode);
		}
		String date = shortDate(summary.fetchedAt);
		if (!date.isEmpty())
		{
			sb.append(sb.length() > 0 ? " · " : "").append(date);
		}
		return sb.toString();
	}

	private static String totalsLine(SnapshotSummary summary)
	{
		String level = summary.totalLevel == null ? "—" : Integer.toString(summary.totalLevel);
		String xp = summary.totalXp == null ? "—" : formatXp(summary.totalXp);
		return "Total level " + level + " · " + xp + " xp";
	}

	private void clearRows()
	{
		list.removeAll();
		list.revalidate();
		list.repaint();
	}

	private void setBusy(boolean busy)
	{
		refreshButton.setEnabled(!busy);
	}

	private void setStatus(String message, Color tone)
	{
		status.setText(PanelWidgets.wrap(message));
		status.setForeground(tone);
	}

	private static String shortDate(String iso)
	{
		if (iso == null)
		{
			return "";
		}
		int t = iso.indexOf('T');
		return t > 0 ? iso.substring(0, t) : iso;
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

	// --- Test inspectors ---

	int rowCount()
	{
		int rows = 0;
		for (Component c : list.getComponents())
		{
			if (c instanceof JPanel)
			{
				rows++;
			}
		}
		return rows;
	}

	ReportCopier.Sink rowCopySink()
	{
		return rowCopySink;
	}

	String statusText()
	{
		return status.getText();
	}

	Color statusTone()
	{
		return status.getForeground();
	}
}
