/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ReportCallback;
import javax.swing.SwingUtilities;

/**
 * Fetches a snapshot's rendered report and copies it to the clipboard, reporting an
 * honest outcome at every step. Shared by the Report tab (copy by id / latest) and the
 * Snapshots tab (per-row "Copy report"), so the fetch-then-clipboard flow — and its
 * honesty rule — lives in exactly one place.
 *
 * <p>The rule: {@link Sink#onCopied} fires only when the {@link ClipboardSink}
 * confirms the exact report string reached the clipboard. A successful fetch whose
 * clipboard write is refused is {@link Sink#onCopyFailed}, never a false "copied".
 * The network fetch runs off the EDT (on the client's executor); every {@code Sink}
 * callback is marshalled back onto the EDT so callers render safely.
 */
final class ReportCopier
{
	/** Render sink for a copy attempt. Every method is invoked on the EDT. */
	interface Sink
	{
		/** The fetch has started. */
		void onCopying();

		/** The report was fetched and the clipboard verifiably holds it. */
		void onCopied(String snapshotId);

		/** The report was fetched but the clipboard refused it — no false success. */
		void onCopyFailed();

		/** The report could not be fetched (auth/not-found/rate-limit/network/...). */
		void onFailure(ActionOutcome outcome, String message);
	}

	private final AnalyticsClient client;
	private final ClipboardSink clipboard;

	ReportCopier(AnalyticsClient client, ClipboardSink clipboard)
	{
		this.client = client;
		this.clipboard = clipboard;
	}

	/** Fetch {@code snapshotId}'s report and copy it; {@code sink} renders each step on the EDT. */
	void copy(String snapshotId, Sink sink)
	{
		sink.onCopying();
		client.fetchReport(snapshotId, new ReportCallback()
		{
			@Override
			public void onReport(String report)
			{
				SwingUtilities.invokeLater(() -> applyToClipboard(report, snapshotId, sink));
			}

			@Override
			public void onFailure(ActionOutcome outcome, String message)
			{
				SwingUtilities.invokeLater(() -> sink.onFailure(outcome, message));
			}
		});
	}

	/**
	 * Copy the fetched report to the clipboard and report the honest verdict. Runs on
	 * the EDT (marshalled by {@link #copy}, or called directly by tests). Reports
	 * {@link Sink#onCopied} only when the clipboard confirms it holds the exact string.
	 */
	void applyToClipboard(String report, String snapshotId, Sink sink)
	{
		if (clipboard.copy(report))
		{
			sink.onCopied(snapshotId);
		}
		else
		{
			sink.onCopyFailed();
		}
	}
}
