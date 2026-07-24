/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.CaptureCallback;
import com.cortalabs.osrs.analytics.transport.SnapshotCapture;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.function.Supplier;
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
 * The "Capture" tab: take a fresh hiscores snapshot for one of the calling key's own
 * accounts ({@code POST /api/v1/plugin/snapshot}). The account field is prefilled with
 * the watched RSN when one is known, but stays editable so any owned account can be
 * captured.
 *
 * <p>Honest states throughout: an idempotent no-op reads "Already up to date" rather
 * than implying fresh work; a 404 says the account isn't one this key owns; auth and
 * config problems are actionable warnings, faults are errors. The network call runs on
 * the client's executor and its result is marshalled onto the EDT via the
 * package-private {@link #onCaptured}/{@link #onFailure} render seams, which the tests
 * drive directly.
 */
final class CaptureContent extends JPanel
{
	private final AnalyticsClient client;
	private final JTextField accountField = new JTextField();
	private final JButton takeButton = new JButton("Take snapshot");
	private final JLabel status = PanelWidgets.wrappingLabel();

	CaptureContent(AnalyticsClient client, Supplier<String> currentAccount)
	{
		this.client = client;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel column = new JPanel();
		column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
		column.setBackground(ColorScheme.DARK_GRAY_COLOR);

		column.add(PanelWidgets.heading("Take a snapshot"));
		column.add(Box.createVerticalStrut(6));

		accountField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		accountField.setForeground(PanelWidgets.INK);
		accountField.setCaretColor(PanelWidgets.INK);
		accountField.setBorder(new EmptyBorder(4, 6, 4, 6));
		accountField.setAlignmentX(Component.LEFT_ALIGNMENT);
		accountField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
		String prefill = currentAccount == null ? null : currentAccount.get();
		if (prefill != null && !prefill.trim().isEmpty())
		{
			accountField.setText(prefill.trim());
		}
		accountField.addActionListener(e -> take());
		column.add(accountField);
		column.add(Box.createVerticalStrut(6));

		takeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
		takeButton.addActionListener(e -> take());
		column.add(takeButton);
		column.add(Box.createVerticalStrut(8));

		column.add(status);

		add(column, BorderLayout.NORTH);
		showIdle();
	}

	/** Prefill the account field with {@code account} only when the user hasn't typed one. */
	void prefillIfEmpty(String account)
	{
		if (account == null || account.trim().isEmpty())
		{
			return;
		}
		String current = accountField.getText();
		if (current == null || current.trim().isEmpty())
		{
			accountField.setText(account.trim());
		}
	}

	private void take()
	{
		String account = accountField.getText() == null ? "" : accountField.getText().trim();
		if (account.isEmpty())
		{
			setStatus("Enter an account name to snapshot.", PanelWidgets.WARN);
			return;
		}
		setBusy(true);
		setStatus("Taking a snapshot of " + account + "…", PanelWidgets.INK_DIM);
		client.captureSnapshot(account, new CaptureCallback()
		{
			@Override
			public void onCaptured(SnapshotCapture result)
			{
				SwingUtilities.invokeLater(() -> CaptureContent.this.onCaptured(result));
			}

			@Override
			public void onFailure(ActionOutcome outcome, String message)
			{
				SwingUtilities.invokeLater(() -> CaptureContent.this.onFailure(outcome, message));
			}
		});
	}

	// --- Render seams (package-private; invoked on the EDT) ---

	void showIdle()
	{
		setBusy(false);
		setStatus("Capture a fresh hiscores snapshot for one of your accounts.", PanelWidgets.INK_DIM);
	}

	void onCaptured(SnapshotCapture result)
	{
		setBusy(false);
		if (result.alreadyIngested)
		{
			setStatus("Already up to date — no new snapshot was needed.", PanelWidgets.IDLE);
		}
		else
		{
			setStatus("Snapshot taken.", PanelWidgets.OK);
		}
	}

	void onFailure(ActionOutcome outcome, String message)
	{
		setBusy(false);
		setStatus(message, PanelWidgets.outcomeTone(outcome));
	}

	private void setBusy(boolean busy)
	{
		takeButton.setEnabled(!busy);
		accountField.setEnabled(!busy);
	}

	private void setStatus(String message, Color tone)
	{
		status.setText(PanelWidgets.wrap(message));
		status.setForeground(tone);
	}

	// --- Test inspectors ---

	String accountText()
	{
		return accountField.getText();
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
		return !takeButton.isEnabled();
	}
}
