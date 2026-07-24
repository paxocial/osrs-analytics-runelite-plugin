/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.SnapshotsCallback;
import com.cortalabs.osrs.analytics.transport.SnapshotPage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The "Report" tab: put a snapshot's rendered report on the system clipboard.
 *
 * <p>Two paths, one honesty rule. "Copy latest report" fetches the newest snapshot
 * ({@code GET /snapshots?limit=1}) and copies its report; the id field copies a
 * specific snapshot's report. Both flow through {@link ReportCopier}, so "Report
 * copied to clipboard" is shown only when the clipboard verifiably holds the exact
 * string — a fetched-but-unclipboarded report reads "the clipboard refused it", never
 * a false success. All render seams run on the EDT and are driven directly by tests.
 */
final class ReportContent extends JPanel
{
	private final AnalyticsClient client;
	private final ReportCopier reportCopier;
	private final JTextField idField = new JTextField();
	private final JButton latestButton = new JButton("Copy latest report");
	private final JButton idButton = new JButton("Copy report for id");
	private final JLabel status = PanelWidgets.wrappingLabel();

	private final ReportCopier.Sink sink = new ReportCopier.Sink()
	{
		@Override
		public void onCopying()
		{
			setBusy(true);
			setStatus("Fetching the report…", PanelWidgets.INK_DIM);
		}

		@Override
		public void onCopied(String snapshotId)
		{
			setBusy(false);
			setStatus("Report copied to clipboard.", PanelWidgets.OK);
		}

		@Override
		public void onCopyFailed()
		{
			setBusy(false);
			setStatus("Fetched the report, but the clipboard wouldn't take it.", PanelWidgets.ERROR);
		}

		@Override
		public void onFailure(ActionOutcome outcome, String message)
		{
			setBusy(false);
			setStatus(message, PanelWidgets.outcomeTone(outcome));
		}
	};

	ReportContent(AnalyticsClient client, ReportCopier reportCopier)
	{
		this.client = client;
		this.reportCopier = reportCopier;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);

		column.add(PanelWidgets.heading("Generate a report"));
		column.add(Box.createVerticalStrut(6));

		latestButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		latestButton.addActionListener(e -> copyLatest());
		column.add(latestButton);
		column.add(Box.createVerticalStrut(8));

		JLabel or = new JLabel("Or copy a specific snapshot's report:");
		or.setForeground(PanelWidgets.INK_DIM);
		or.setAlignmentX(Component.LEFT_ALIGNMENT);
		column.add(or);
		column.add(Box.createVerticalStrut(4));

		idField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		idField.setForeground(PanelWidgets.INK);
		idField.setCaretColor(PanelWidgets.INK);
		idField.setBorder(new EmptyBorder(4, 6, 4, 6));
		idField.setAlignmentX(Component.LEFT_ALIGNMENT);
		idField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		idField.addActionListener(e -> copyById());
		column.add(idField);
		column.add(Box.createVerticalStrut(6));

		idButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		idButton.addActionListener(e -> copyById());
		column.add(idButton);
		column.add(Box.createVerticalStrut(8));

		column.add(status);

		add(column, BorderLayout.NORTH);
		showIdle();
	}

	/** Prefill the id field (e.g. from a Snapshots-tab row) without clobbering typed input. */
	void prefillId(String snapshotId)
	{
		if (snapshotId == null || snapshotId.trim().isEmpty())
		{
			return;
		}
		String current = idField.getText();
		if (current == null || current.trim().isEmpty())
		{
			idField.setText(snapshotId.trim());
		}
	}

	private void copyLatest()
	{
		setBusy(true);
		setStatus("Finding your latest snapshot…", PanelWidgets.INK_DIM);
		client.listSnapshots(1, 0, new SnapshotsCallback()
		{
			@Override
			public void onSnapshots(SnapshotPage page)
			{
				SwingUtilities.invokeLater(() -> onLatest(page));
			}

			@Override
			public void onFailure(ActionOutcome outcome, String message)
			{
				SwingUtilities.invokeLater(() -> sink.onFailure(outcome, message));
			}
		});
	}

	private void copyById()
	{
		String id = idField.getText() == null ? "" : idField.getText().trim();
		if (id.isEmpty())
		{
			setStatus("Paste a snapshot id, or use Copy latest report.", PanelWidgets.WARN);
			return;
		}
		reportCopier.copy(id, sink);
	}

	// --- Render seams (package-private; invoked on the EDT) ---

	void showIdle()
	{
		setBusy(false);
		setStatus("Copy a snapshot's full report to your clipboard.", PanelWidgets.INK_DIM);
	}

	/** Continue the "copy latest" flow once the newest snapshot is known (EDT). */
	void onLatest(SnapshotPage page)
	{
		if (page.snapshots.isEmpty())
		{
			setBusy(false);
			setStatus("No snapshots yet — take one in the Capture tab first.", PanelWidgets.IDLE);
			return;
		}
		// Busy stays on; ReportCopier.copy() re-asserts it via sink.onCopying().
		reportCopier.copy(page.snapshots.get(0).snapshotId, sink);
	}

	private void setBusy(boolean busy)
	{
		latestButton.setEnabled(!busy);
		idButton.setEnabled(!busy);
		idField.setEnabled(!busy);
	}

	private void setStatus(String message, Color tone)
	{
		status.setText(PanelWidgets.wrap(message));
		status.setForeground(tone);
	}

	// --- Test inspectors ---

	ReportCopier.Sink copySink()
	{
		return sink;
	}

	String statusText()
	{
		return status.getText();
	}

	Color statusTone()
	{
		return status.getForeground();
	}

	boolean isBusy()
	{
		return !latestButton.isEnabled();
	}
}
