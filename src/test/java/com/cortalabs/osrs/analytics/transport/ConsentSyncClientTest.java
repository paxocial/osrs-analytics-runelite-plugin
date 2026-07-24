/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient.State;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Wire contract for the consent-sync lane (C5). Drives the public
 * {@link AnalyticsClient#syncConsent} through a real single-thread executor
 * against a {@link MockWebServer} — the same async idiom as
 * {@link SnapshotActionsClientTest} — so the PUT method, path, ownership header,
 * request body (server-vocabulary lane keys with their exact booleans), and the
 * fire-and-forget failure behaviour are all proven on the wire.
 *
 * <p>The lane-<em>map derivation</em> (the 20-key vocabulary and the
 * master-switch-off deny-all expansion) belongs to
 * {@code AnalyticsPlugin.consentLanes} and is covered by {@code ConsentLanesTest};
 * this test proves the client transmits whatever map it is handed, faithfully and
 * exactly once, and that it is decoupled from the telemetry master switch.
 */
public class ConsentSyncClientTest
{
	private MockWebServer server;
	private ScheduledExecutorService executor;
	private AnalyticsClient client;
	private boolean serverUp;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		serverUp = true;
		executor = Executors.newSingleThreadScheduledExecutor();
		client = new AnalyticsClient(new OkHttpClient(), executor, new Gson());
	}

	@After
	public void tearDown() throws Exception
	{
		executor.shutdownNow();
		if (serverUp)
		{
			server.shutdown();
		}
	}

	private String baseUrl()
	{
		return server.url("/api/v1/plugin").toString();
	}

	/**
	 * Block until every task already queued on the single-thread executor has run.
	 * Because the executor is FIFO and single-threaded, a marker submitted after a
	 * fire-and-forget {@code syncConsent} completes only once that sync's task has
	 * finished — giving deterministic assertions on a void, callback-less path.
	 */
	private void flushExecutor() throws Exception
	{
		executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
	}

	@SuppressWarnings("deprecation")
	private static JsonObject parse(String body)
	{
		return new JsonParser().parse(body).getAsJsonObject();
	}

	/** The server's full 20-lane consent vocabulary, every lane denied. */
	private static Map<String, Boolean> denyAllLanes()
	{
		String[] vocabulary = {
			"sessions", "xp_snapshots", "quests", "diaries", "combat_achievements",
			"equipment", "loot", "activity", "region_time", "efficiency",
			"activity_time", "signal_events", "ge_trades", "slayer_tasks", "npc_kills",
			"farming_state", "position", "collection_log", "collection_pages", "bank"
		};
		Map<String, Boolean> lanes = new LinkedHashMap<>();
		for (String lane : vocabulary)
		{
			lanes.put(lane, false);
		}
		return lanes;
	}

	// --- happy path: exactly one PUT with the mapped lane set ---

	@Test
	public void syncConsentFiresExactlyOnePutWithMappedLanesAndKey() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
		client.configure(baseUrl(), "secret-key", true, 256, 5_000);

		// A mixed grant map: the client must transmit precisely these keys/booleans.
		Map<String, Boolean> lanes = new LinkedHashMap<>();
		lanes.put("sessions", true);
		lanes.put("xp_snapshots", false);
		lanes.put("bank", true);
		lanes.put("collection_log", false);
		lanes.put("position", true);

		client.syncConsent(lanes);

		RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("a consent PUT must be sent", request);
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/v1/plugin/consent", request.getPath());
		assertEquals("secret-key", request.getHeader("X-API-Key"));

		JsonObject sentLanes = parse(request.getBody().readUtf8()).getAsJsonObject("lanes");
		assertEquals("no extra or missing lanes on the wire", lanes.size(), sentLanes.size());
		for (Map.Entry<String, Boolean> lane : lanes.entrySet())
		{
			assertEquals("lane " + lane.getKey() + " boolean mirrored exactly",
				lane.getValue(), Boolean.valueOf(sentLanes.get(lane.getKey()).getAsBoolean()));
		}

		flushExecutor();
		assertEquals("exactly one PUT, no duplicate sync", 1, server.getRequestCount());
	}

	// --- master switch OFF still syncs (deny-all), never zero requests ---

	@Test
	public void masterSwitchOffSyncsDenyAllNotZeroRequests() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
		// Telemetry master switch OFF — consent sync must STILL fire (the server's
		// deny-by-default record must be told the plugin is denying every lane).
		client.configure(baseUrl(), "secret-key", false, 256, 5_000);

		Map<String, Boolean> denyAll = denyAllLanes();
		client.syncConsent(denyAll);

		RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("master-off must sync deny-all, not skip the PUT", request);
		assertEquals("PUT", request.getMethod());
		assertEquals("/api/v1/plugin/consent", request.getPath());

		JsonObject sentLanes = parse(request.getBody().readUtf8()).getAsJsonObject("lanes");
		assertEquals("the full lane vocabulary is sent, not a partial map",
			denyAll.size(), sentLanes.size());
		for (String lane : denyAll.keySet())
		{
			assertFalse("deny-all: lane " + lane + " must be false on the wire",
				sentLanes.get(lane).getAsBoolean());
		}

		flushExecutor();
		assertEquals("exactly one deny-all PUT", 1, server.getRequestCount());
	}

	// --- failure honesty: a failed sync never fabricates success ---

	@Test
	public void serverErrorSyncDoesNotFabricateSuccessOrCorruptTransportState() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		client.configure(baseUrl(), "secret-key", true, 256, 5_000);
		assertEquals("baseline transport state after configure", State.IDLE, client.getState());

		client.syncConsent(denyAllLanes());

		RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
		assertNotNull("the failing PUT was actually attempted, not silently skipped", request);
		flushExecutor();

		// Consent sync is fire-and-forget and decoupled from the telemetry transport:
		// a 5xx must not flip the transport State to OK (a false "connected") nor to
		// AUTH_FAILED/ERROR (a false telemetry pause).
		assertEquals("a failed consent sync must not touch transport state",
			State.IDLE, client.getState());
	}

	@Test
	public void networkFailureSyncDoesNotCrashOrFabricateSuccess() throws Exception
	{
		client.configure(baseUrl(), "secret-key", true, 256, 5_000);
		server.shutdown();
		serverUp = false;

		client.syncConsent(denyAllLanes());

		// The IOException is caught inside sendConsent; the executor thread survives
		// (the marker below runs) and the plugin path never sees a crash.
		flushExecutor();
		assertEquals("a network-failed consent sync must not touch transport state",
			State.IDLE, client.getState());
	}

	// --- honest short-circuits: no pointless or keyless PUT ---

	@Test
	public void syncConsentBeforeConfigureSendsNoKeylessPut() throws Exception
	{
		// No configure(): base URL and key are empty. The task is queued but
		// sendConsent must refuse rather than PUT without credentials.
		client.syncConsent(denyAllLanes());

		flushExecutor();
		assertEquals("an unconfigured plugin must not fire a keyless consent PUT",
			0, server.getRequestCount());
	}

	@Test
	public void syncConsentWithEmptyLaneMapMakesNoRequest() throws Exception
	{
		client.configure(baseUrl(), "secret-key", true, 256, 5_000);

		client.syncConsent(new LinkedHashMap<>());

		flushExecutor();
		assertEquals("an empty lane map is a no-op, not a pointless PUT",
			0, server.getRequestCount());
	}

	@Test
	public void syncConsentWithoutExecutorMakesNoRequest() throws Exception
	{
		// A headless client (no scheduler) must never attempt a synchronous network
		// call on the caller's thread.
		AnalyticsClient headless = new AnalyticsClient(new OkHttpClient(), null, new Gson());
		headless.configure(baseUrl(), "secret-key", true, 256, 5_000);

		headless.syncConsent(denyAllLanes());

		assertEquals("no executor means no consent request", 0, server.getRequestCount());
	}
}
