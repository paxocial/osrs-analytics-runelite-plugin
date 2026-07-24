/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionException;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.ActionOutcome;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient.SnapshotsCallback;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Transport contract for the on-demand plugin-tab actions (C6): capture, list, and
 * report fetch. Drives the package-private synchronous seams directly against a
 * {@link MockWebServer} — the same idiom as {@link AnalyticsClientTest} — so the wire
 * shape, ownership header, pagination query, exact-report round-trip, and the
 * status→{@link ActionOutcome} mapping are all proven without threads.
 */
public class SnapshotActionsClientTest
{
	private MockWebServer server;
	private AnalyticsClient client;
	private boolean serverUp;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		serverUp = true;
		client = new AnalyticsClient(new OkHttpClient(), null, new Gson());
	}

	@After
	public void tearDown() throws Exception
	{
		if (serverUp)
		{
			server.shutdown();
		}
	}

	private String baseUrl()
	{
		return server.url("/api/v1/plugin").toString();
	}

	/** Configure with the telemetry master switch OFF, proving tab actions ignore it. */
	private void configureMasterSwitchOff()
	{
		client.configure(baseUrl(), "secret-key", false, 256, 5_000);
	}

	@SuppressWarnings("deprecation")
	private static JsonObject parse(String body)
	{
		return new JsonParser().parse(body).getAsJsonObject();
	}

	// --- capture (POST /snapshot) ---

	@Test
	public void postSnapshotSendsContractBodyAndParsesResult() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"snapshot_db_id\":\"snap-1\",\"already_ingested\":false}"));
		configureMasterSwitchOff();

		SnapshotCapture result = client.postSnapshot("Zezima");

		assertEquals("snap-1", result.snapshotDbId);
		assertFalse(result.alreadyIngested);

		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/v1/plugin/snapshot", request.getPath());
		assertEquals("secret-key", request.getHeader("X-API-Key"));
		assertEquals("Zezima", parse(request.getBody().readUtf8()).get("player").getAsString());
	}

	@Test
	public void postSnapshotReportsIdempotentNoOpHonestly() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200)
			.setBody("{\"snapshot_db_id\":\"snap-1\",\"already_ingested\":true}"));
		configureMasterSwitchOff();

		assertTrue(client.postSnapshot("Zezima").alreadyIngested);
	}

	@Test
	public void postSnapshotMapsUnownedAccountToNotFound() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"Account not found\"}"));
		configureMasterSwitchOff();

		assertOutcome(ActionOutcome.NOT_FOUND, () -> client.postSnapshot("Someone"));
	}

	@Test
	public void postSnapshotMapsRejectedKeyToUnauthorized() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));
		configureMasterSwitchOff();

		assertOutcome(ActionOutcome.UNAUTHORIZED, () -> client.postSnapshot("Zezima"));
	}

	@Test
	public void postSnapshotMapsTransportFailureToNetwork() throws Exception
	{
		configureMasterSwitchOff();
		server.shutdown();
		serverUp = false;

		assertOutcome(ActionOutcome.NETWORK, () -> client.postSnapshot("Zezima"));
	}

	@Test
	public void actionsRefuseWhenNotConfigured()
	{
		// No configure(): base URL and key are empty — an honest, request-free refusal.
		assertOutcome(ActionOutcome.NOT_CONFIGURED, () -> client.postSnapshot("Zezima"));
	}

	// --- list (GET /snapshots) ---

	@Test
	public void getSnapshotPageParsesRowsAndPaginationQuery() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody(
			"{\"snapshots\":["
				+ "{\"snapshot_id\":\"s1\",\"account_id\":\"a1\",\"account_name\":\"Zezima\","
				+ "\"resolved_mode\":\"main\",\"fetched_at\":\"2026-07-24T10:00:00Z\","
				+ "\"total_level\":2000,\"total_xp\":500000000}"
				+ "],\"total\":7,\"limit\":25,\"offset\":0}"));
		configureMasterSwitchOff();

		SnapshotPage page = client.getSnapshotPage(25, 0);

		assertEquals(1, page.snapshots.size());
		assertEquals(7, page.total);
		SnapshotSummary row = page.snapshots.get(0);
		assertEquals("s1", row.snapshotId);
		assertEquals("Zezima", row.accountName);
		assertEquals("main", row.resolvedMode);
		assertEquals(Integer.valueOf(2000), row.totalLevel);
		assertEquals(Long.valueOf(500000000L), row.totalXp);

		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/v1/plugin/snapshots?limit=25&offset=0", request.getPath());
		assertEquals("secret-key", request.getHeader("X-API-Key"));
	}

	@Test
	public void getSnapshotPageKeepsAbsentValuesNullNotZero() throws Exception
	{
		// resolved_mode absent, total_level null: honest absence, never a fabricated 0.
		server.enqueue(new MockResponse().setResponseCode(200).setBody(
			"{\"snapshots\":["
				+ "{\"snapshot_id\":\"s1\",\"account_id\":\"a1\",\"account_name\":\"Zezima\","
				+ "\"resolved_mode\":null,\"fetched_at\":\"2026-07-24T10:00:00Z\","
				+ "\"total_level\":null,\"total_xp\":null}"
				+ "],\"total\":1,\"limit\":25,\"offset\":0}"));
		configureMasterSwitchOff();

		SnapshotSummary row = client.getSnapshotPage(25, 0).snapshots.get(0);
		assertNull(row.resolvedMode);
		assertNull(row.totalLevel);
		assertNull(row.totalXp);
	}

	@Test
	public void getSnapshotPageMapsRejectedKeyToUnauthorized() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));
		configureMasterSwitchOff();

		assertOutcome(ActionOutcome.UNAUTHORIZED, () -> client.getSnapshotPage(25, 0));
	}

	// --- report (GET /snapshots/{id}/report) ---

	@Test
	public void getReportReturnsTheExactStoredString() throws Exception
	{
		String report = "# Zezima\n\n**Total level:** 2000\n\n- line one\n- line two\n";
		JsonObject body = new JsonObject();
		body.addProperty("snapshot_id", "s1");
		body.addProperty("report_version_id", "v1");
		body.addProperty("schema_version", "report.v1");
		body.addProperty("report", report);
		server.enqueue(new MockResponse().setResponseCode(200).setBody(new Gson().toJson(body)));
		configureMasterSwitchOff();

		assertEquals(report, client.getReport("s1"));

		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/api/v1/plugin/snapshots/s1/report", request.getPath());
		assertEquals("secret-key", request.getHeader("X-API-Key"));
	}

	@Test
	public void getReportMapsAbsentReportToNotFound() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"Report not found\"}"));
		configureMasterSwitchOff();

		assertOutcome(ActionOutcome.NOT_FOUND, () -> client.getReport("s1"));
	}

	// --- async wrapper smoke (executor -> callback) ---

	@Test
	public void listSnapshotsAsyncDeliversPageToCallback() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody(
			"{\"snapshots\":["
				+ "{\"snapshot_id\":\"s1\",\"account_id\":\"a1\",\"account_name\":\"Zezima\","
				+ "\"resolved_mode\":\"main\",\"fetched_at\":\"2026-07-24T10:00:00Z\","
				+ "\"total_level\":2000,\"total_xp\":500000000}"
				+ "],\"total\":1,\"limit\":25,\"offset\":0}"));

		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		AnalyticsClient async = new AnalyticsClient(new OkHttpClient(), executor, new Gson());
		async.configure(baseUrl(), "secret-key", false, 256, 5_000);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<SnapshotPage> page = new AtomicReference<>();
		AtomicReference<ActionOutcome> failure = new AtomicReference<>();
		async.listSnapshots(25, 0, new SnapshotsCallback()
		{
			@Override
			public void onSnapshots(SnapshotPage result)
			{
				page.set(result);
				latch.countDown();
			}

			@Override
			public void onFailure(ActionOutcome outcome, String message)
			{
				failure.set(outcome);
				latch.countDown();
			}
		});

		assertTrue("callback fired within 5s", latch.await(5, TimeUnit.SECONDS));
		executor.shutdownNow();
		assertNull(failure.get());
		assertEquals(1, page.get().snapshots.size());
		assertEquals("Zezima", page.get().snapshots.get(0).accountName);
	}

	// --- helpers ---

	private interface Action
	{
		void run() throws ActionException;
	}

	private static void assertOutcome(ActionOutcome expected, Action action)
	{
		try
		{
			action.run();
			fail("Expected ActionException(" + expected + ")");
		}
		catch (ActionException ex)
		{
			assertEquals(expected, ex.outcome());
		}
	}
}
