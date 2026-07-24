/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.SnapshotCapture;
import com.cortalabs.osrs.analytics.transport.SnapshotPage;
import com.cortalabs.osrs.analytics.transport.SnapshotSummary;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Headless render + honesty tests for the three in-game tab contents and the shared
 * {@link ReportCopier}. No network: the panels are constructed with an unconfigured
 * {@link AnalyticsClient} (null executor, so its async calls are inert) and the
 * package-private render seams are driven directly. The clipboard honesty guarantee —
 * copy the EXACT report string, and claim success ONLY when the clipboard took it — is
 * proven against a capturing fake {@link ClipboardSink}.
 */
public class SnapshotTabsTest
{
	@BeforeClass
	public static void headless()
	{
		System.setProperty("java.awt.headless", "true");
	}

	private static AnalyticsClient inertClient()
	{
		// Null executor => captureSnapshot/listSnapshots/fetchReport are no-ops; the
		// tests exercise the render seams, not the transport (that is covered in
		// SnapshotActionsClientTest).
		return new AnalyticsClient(new OkHttpClient(), null, new Gson());
	}

	// --- Clipboard honesty (the acceptance-critical guarantee) ---

	/** A ClipboardSink that records the exact string it was handed and its own verdict. */
	private static final class FakeClipboard implements ClipboardSink
	{
		private final boolean succeed;
		private String captured;
		private int calls;

		FakeClipboard(boolean succeed)
		{
			this.succeed = succeed;
		}

		@Override
		public boolean copy(String text)
		{
			this.captured = text;
			this.calls++;
			return succeed;
		}
	}

	private static final class RecordingSink implements ReportCopier.Sink
	{
		String copiedId;
		boolean copyFailed;
		ActionOutcome failure;

		@Override
		public void onCopying()
		{
		}

		@Override
		public void onCopied(String snapshotId)
		{
			this.copiedId = snapshotId;
		}

		@Override
		public void onCopyFailed()
		{
			this.copyFailed = true;
		}

		@Override
		public void onFailure(ActionOutcome outcome, String message)
		{
			this.failure = outcome;
		}
	}

	@Test
	public void clipboardReceivesTheExactReportStringOnSuccess()
	{
		FakeClipboard clipboard = new FakeClipboard(true);
		ReportCopier copier = new ReportCopier(inertClient(), clipboard);
		RecordingSink sink = new RecordingSink();

		String report = "# Zezima\n\n**Total level:** 2000\n\n- one\n- two\n";
		copier.applyToClipboard(report, "snap-1", sink);

		assertEquals("the exact report string reaches the clipboard", report, clipboard.captured);
		assertEquals(1, clipboard.calls);
		assertEquals("snap-1", sink.copiedId);
		assertFalse(sink.copyFailed);
	}

	@Test
	public void noFalseSuccessWhenTheClipboardRefuses()
	{
		FakeClipboard clipboard = new FakeClipboard(false);
		ReportCopier copier = new ReportCopier(inertClient(), clipboard);
		RecordingSink sink = new RecordingSink();

		String report = "report body";
		copier.applyToClipboard(report, "snap-1", sink);

		// The exact string was still attempted, but success is NOT claimed.
		assertEquals(report, clipboard.captured);
		assertTrue(sink.copyFailed);
		assertEquals(null, sink.copiedId);
	}

	// --- Capture tab render seams ---

	@Test
	public void captureTabPrefillsAccountAndRendersHonestStates()
	{
		CaptureContent capture = new CaptureContent(inertClient(), () -> "Zezima");
		assertEquals("Zezima", capture.accountText());
		assertFalse(capture.isBusy());

		capture.onCaptured(new SnapshotCapture("snap-1", false));
		assertTrue(capture.statusText().contains("Snapshot taken"));
		assertEquals(PanelWidgets.OK, capture.statusTone());

		capture.onCaptured(new SnapshotCapture("snap-1", true));
		assertTrue(capture.statusText().contains("Already up to date"));

		capture.onFailure(ActionOutcome.NOT_FOUND, "not one this key owns");
		assertTrue(capture.statusText().contains("not one this key owns"));
		assertEquals(PanelWidgets.WARN, capture.statusTone());

		capture.onFailure(ActionOutcome.NETWORK, "network down");
		assertEquals(PanelWidgets.ERROR, capture.statusTone());
	}

	@Test
	public void captureTabPrefillDoesNotClobberTypedInput()
	{
		CaptureContent capture = new CaptureContent(inertClient(), () -> "");
		assertEquals("", capture.accountText());
		capture.prefillIfEmpty("Zezima");
		assertEquals("Zezima", capture.accountText());
		// A second, different account must not overwrite the first.
		capture.prefillIfEmpty("Woox");
		assertEquals("Zezima", capture.accountText());
	}

	// --- Report tab render seams ---

	@Test
	public void reportTabRendersCopyOutcomesAndEmptyLatest()
	{
		AnalyticsClient client = inertClient();
		ReportContent report = new ReportContent(client, new ReportCopier(client, new FakeClipboard(true)));
		assertFalse(report.isBusy());

		report.copySink().onCopied("snap-1");
		assertTrue(report.statusText().contains("copied to clipboard"));
		assertEquals(PanelWidgets.OK, report.statusTone());

		report.copySink().onCopyFailed();
		assertTrue(report.statusText().contains("clipboard wouldn't take it"));
		assertEquals(PanelWidgets.ERROR, report.statusTone());

		report.copySink().onFailure(ActionOutcome.UNAUTHORIZED, "key rejected");
		assertTrue(report.statusText().contains("key rejected"));
		assertEquals(PanelWidgets.WARN, report.statusTone());

		report.onLatest(new SnapshotPage(new ArrayList<>(), 0, 1, 0));
		assertTrue(report.statusText().contains("No snapshots yet"));
	}

	// --- Snapshots tab render seams ---

	@Test
	public void snapshotsTabRendersRowsEmptyAndError()
	{
		AnalyticsClient client = inertClient();
		SnapshotsContent snapshots = new SnapshotsContent(client, new ReportCopier(client, new FakeClipboard(true)));

		snapshots.onSnapshots(new SnapshotPage(twoRows(), 2, 25, 0));
		assertEquals(2, snapshots.rowCount());
		assertTrue(snapshots.statusText().contains("Showing 2 of 2"));

		snapshots.onSnapshots(new SnapshotPage(new ArrayList<>(), 0, 25, 0));
		assertEquals(0, snapshots.rowCount());
		assertTrue(snapshots.statusText().contains("No snapshots yet"));

		snapshots.onFailure(ActionOutcome.UNAVAILABLE, "server down");
		assertEquals(0, snapshots.rowCount());
		assertTrue(snapshots.statusText().contains("server down"));
		assertEquals(PanelWidgets.ERROR, snapshots.statusTone());
	}

	@Test
	public void snapshotsRowCopyReportUsesTheHonestClipboardPath()
	{
		AnalyticsClient client = inertClient();
		SnapshotsContent snapshots = new SnapshotsContent(client, new ReportCopier(client, new FakeClipboard(true)));
		AtomicReference<String> status = new AtomicReference<>();

		// Drive the shared per-row sink directly: the same sink the row button feeds.
		snapshots.rowCopySink().onCopied("snap-1");
		status.set(snapshots.statusText());
		assertTrue(status.get().contains("copied to clipboard"));
		assertEquals(PanelWidgets.OK, snapshots.statusTone());

		snapshots.rowCopySink().onCopyFailed();
		assertTrue(snapshots.statusText().contains("clipboard wouldn't take it"));
	}

	private static List<SnapshotSummary> twoRows()
	{
		List<SnapshotSummary> rows = new ArrayList<>();
		rows.add(new SnapshotSummary("s1", "a1", "Zezima", "main", "2026-07-24T10:00:00Z", 2000, 500000000L));
		// Second row exercises honest absence: no mode, no totals.
		rows.add(new SnapshotSummary("s2", "a1", "Zezima", null, "2026-07-23T09:00:00Z", null, null));
		return rows;
	}
}
