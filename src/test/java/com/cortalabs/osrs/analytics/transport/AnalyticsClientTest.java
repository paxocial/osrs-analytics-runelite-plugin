/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.dto.LootDrop;
import com.cortalabs.osrs.analytics.dto.SessionEvent;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for {@link AnalyticsClient} transport: batch send shape,
 * category grouping, retry/backoff, rate-limit handling, auth/404 handling,
 * bounded queue, and the "disabled = no traffic" guarantee. Uses a controllable
 * clock so backoff windows are deterministic.
 */
public class AnalyticsClientTest
{
	private MockWebServer server;
	private AnalyticsClient client;
	private final long[] clock = {1_000_000L};

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		LongSupplier clockMs = () -> clock[0];
		client = new AnalyticsClient(new OkHttpClient(), null, clockMs);
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private String baseUrl()
	{
		return server.url("/api/v1/plugin").toString();
	}

	private void configure(boolean enabled)
	{
		client.configure(baseUrl(), "secret-key", enabled, 256, 5_000);
	}

	private static XpSnapshot xp(String rsn)
	{
		XpSnapshot snap = new XpSnapshot();
		snap.rsn = rsn;
		snap.world = 330;
		snap.timestamp = "2026-07-16T18:41:02Z";
		snap.pluginVersion = "1.0.0";
		return snap;
	}

	@SuppressWarnings("deprecation")
	private static JsonObject parse(String body)
	{
		return new JsonParser().parse(body).getAsJsonObject();
	}

	@Test
	public void flushSendsBatchWithApiKeyAndContractBody() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();

		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("/api/v1/plugin/batch", request.getPath());
		assertEquals("secret-key", request.getHeader("X-API-Key"));

		JsonObject body = parse(request.getBody().readUtf8());
		assertEquals("Zezima", body.get("rsn").getAsString());
		assertTrue(body.has("xp_snapshots"));
		assertEquals(1, body.getAsJsonArray("xp_snapshots").size());
		assertEquals(AnalyticsClient.State.OK, client.getState());
		assertEquals(0, client.queueSize());
	}

	@Test
	public void flushGroupsCategoriesIntoOneBatch() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		client.enqueue(EventCategory.LOOT, loot("Zezima"));
		client.enqueue(EventCategory.SESSION, session("Zezima"));

		client.flushOnce();

		assertEquals(1, server.getRequestCount());
		JsonObject body = parse(server.takeRequest().getBody().readUtf8());
		assertTrue(body.has("xp_snapshots"));
		assertTrue(body.has("loot"));
		assertTrue(body.has("sessions"));
		assertEquals(0, client.queueSize());
	}

	@Test
	public void serverErrorRequeuesWithStableEventIdThenSucceeds() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(500));
		server.enqueue(new MockResponse().setResponseCode(200));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		String originalId = client.queuedEventIds().get(0);

		client.flushOnce(); // 500 -> requeue
		assertEquals(1, client.queueSize());
		assertEquals("event id must survive retry (idempotency)", originalId, client.queuedEventIds().get(0));
		assertEquals(AnalyticsClient.State.ERROR, client.getState());

		clock[0] += 10_000; // clear backoff window
		client.flushOnce(); // 200 -> success

		assertEquals(2, server.getRequestCount());
		assertEquals(0, client.queueSize());
		assertEquals(AnalyticsClient.State.OK, client.getState());
	}

	@Test
	public void rateLimitRequeuesAndBacksOff() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "1"));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();

		assertEquals(AnalyticsClient.State.RATE_LIMITED, client.getState());
		assertEquals(1, client.queueSize());

		// Still inside the backoff window: no second request.
		client.flushOnce();
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void authFailurePausesSending() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();
		assertEquals(AnalyticsClient.State.AUTH_FAILED, client.getState());
		assertEquals(1, client.queueSize());

		// Even past any backoff, an auth failure stays paused until reconfigure.
		clock[0] += 120_000;
		client.flushOnce();
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void unknownAccountRequeuesBehindLongBackoff() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();

		assertEquals(AnalyticsClient.State.UNREGISTERED, client.getState());
		assertEquals("404 keeps the batch so a late registration is not lost", 1, client.queueSize());

		// Long backoff: still no second request a few seconds later.
		clock[0] += 10_000;
		client.flushOnce();
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void registeringAfter404ResumesAndNotifiesRecovery() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404));
		server.enqueue(new MockResponse().setResponseCode(200));
		List<String> notices = new ArrayList<>();
		client.setNotifier(notices::add);
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce(); // 404 -> unregistered notice, requeued
		assertEquals(AnalyticsClient.State.UNREGISTERED, client.getState());
		assertTrue(notices.get(0).toLowerCase().contains("not registered"));

		clock[0] += 70_000; // clear the long 404 backoff (user has now registered)
		client.flushOnce(); // 200 -> recovery notice

		assertEquals(AnalyticsClient.State.OK, client.getState());
		assertEquals(0, client.queueSize());
		assertEquals("recovery notice fires once on success after an interruption",
			"OSRS Analytics: connected — telemetry flowing.", notices.get(notices.size() - 1));
	}

	@Test
	public void retryAfterHttpDateIsHonored() throws Exception
	{
		// Retry-After as an HTTP-date 30s in the future (relative to the fake clock).
		String httpDate = DateTimeFormatter.RFC_1123_DATE_TIME.format(
			Instant.ofEpochMilli(clock[0] + 30_000L).atZone(ZoneOffset.UTC));
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", httpDate));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();
		assertEquals(AnalyticsClient.State.RATE_LIMITED, client.getState());
		assertEquals(1, client.queueSize());

		// 29s later: still inside the date-derived window, no second request.
		clock[0] += 29_000L;
		client.flushOnce();
		assertEquals(1, server.getRequestCount());

		// Past the window: it sends.
		server.enqueue(new MockResponse().setResponseCode(200));
		clock[0] += 2_000L;
		client.flushOnce();
		assertEquals(2, server.getRequestCount());
		assertEquals(AnalyticsClient.State.OK, client.getState());
	}

	@Test
	public void retryAfterUnparseableFallsBackToDefaultBackoff() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After", "soon-ish"));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();
		assertEquals(AnalyticsClient.State.RATE_LIMITED, client.getState());
		assertEquals(1, client.queueSize());
		// Fell back to a non-zero exponential backoff: no immediate re-send.
		client.flushOnce();
		assertEquals(1, server.getRequestCount());
	}

	@Test
	public void snapshotReportsStateQueueAndPerCategoryCounts() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		client.enqueue(EventCategory.LOOT, loot("Zezima"));

		AnalyticsClient.Snapshot before = client.snapshot();
		assertEquals(2, before.queueDepth);
		assertEquals(0L, before.totalAccepted());
		assertEquals(0L, before.lastAcceptedMs);
		assertTrue(before.baseUrl.endsWith("/api/v1/plugin"));

		client.flushOnce();

		AnalyticsClient.Snapshot after = client.snapshot();
		assertEquals(AnalyticsClient.State.OK, after.state);
		assertTrue(after.enabled);
		assertEquals(0, after.queueDepth);
		assertEquals(1L, after.acceptedFor(EventCategory.XP));
		assertEquals(1L, after.acceptedFor(EventCategory.LOOT));
		assertEquals(0L, after.acceptedFor(EventCategory.BANK));
		assertEquals(2L, after.totalAccepted());
		assertEquals("last-accepted stamped from the transport clock", clock[0], after.lastAcceptedMs);
	}

	@Test
	public void invalidPayloadDropsBatch() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(422).setBody("{\"detail\":\"bad\"}"));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));

		client.flushOnce();

		assertEquals(AnalyticsClient.State.ERROR, client.getState());
		assertEquals("poison batch is dropped", 0, client.queueSize());
	}

	@Test
	public void disabledClientProducesNoTraffic() throws Exception
	{
		configure(false);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		assertEquals(0, client.queueSize());

		client.flushOnce();
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void boundedQueueDropsOldest()
	{
		client.configure(baseUrl(), "secret-key", true, 256, 3);
		for (int i = 0; i < 5; i++)
		{
			client.enqueue(EventCategory.XP, xp("Player" + i));
		}
		assertEquals(3, client.queueSize());
	}

	@Test
	public void missingApiKeySkipsFlush() throws Exception
	{
		client.configure(baseUrl(), "", true, 256, 5_000);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		client.flushOnce();
		assertEquals(0, server.getRequestCount());
	}

	@Test
	public void eventWithoutRsnIsNotEnqueued()
	{
		configure(true);
		XpSnapshot noRsn = xp(null);
		client.enqueue(EventCategory.XP, noRsn);
		assertEquals(0, client.queueSize());
	}

	@Test
	public void reconfigureClearsAuthPause() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(401));
		server.enqueue(new MockResponse().setResponseCode(200));
		configure(true);
		client.enqueue(EventCategory.XP, xp("Zezima"));
		client.flushOnce();
		assertEquals(AnalyticsClient.State.AUTH_FAILED, client.getState());

		// Operator fixes the key -> reconfigure resets the pause.
		configure(true);
		assertEquals(AnalyticsClient.State.IDLE, client.getState());
		clock[0] += 10_000;
		client.flushOnce();
		assertEquals(2, server.getRequestCount());
		assertEquals(AnalyticsClient.State.OK, client.getState());
	}

	@Test
	public void fullCollectionLogSyncSpreadsAcrossBatches() throws Exception
	{
		// A full collection-log walk enqueues far more than one batch; it must drain
		// in bounded batches (<= maxBatchEvents) across successive flush cycles.
		configure(true);
		for (int i = 0; i < 600; i++)
		{
			client.enqueue(EventCategory.COLLECTION_LOG, clog("Zezima", 20000 + i));
		}
		assertEquals(600, client.queueSize());

		int flushes = 0;
		while (client.queueSize() > 0 && flushes < 10)
		{
			server.enqueue(new MockResponse().setResponseCode(200));
			client.flushOnce();
			clock[0] += 10_000; // clear the send-spacing window
			flushes++;
		}

		// 600 / 256 per batch => 3 flushes, each body bounded to <= 256 entries.
		assertEquals(3, server.getRequestCount());
		assertEquals(0, client.queueSize());
		for (int i = 0; i < 3; i++)
		{
			JsonObject body = parse(server.takeRequest().getBody().readUtf8());
			assertTrue(body.has("collection_log"));
			assertTrue("batch bounded to maxBatchEvents", body.getAsJsonArray("collection_log").size() <= 256);
		}
	}

	private static CollectionLogEntry clog(String rsn, int itemId)
	{
		CollectionLogEntry entry = new CollectionLogEntry();
		entry.rsn = rsn;
		entry.world = 330;
		entry.timestamp = "2026-07-16T18:41:02Z";
		entry.pluginVersion = "1.0.0";
		entry.itemId = itemId;
		entry.itemName = "Item " + itemId;
		entry.quantity = 1;
		entry.source = "Test";
		entry.obtainedAt = "2026-07-16T18:41:02Z";
		return entry;
	}

	private static SessionEvent session(String rsn)
	{
		SessionEvent event = new SessionEvent();
		event.rsn = rsn;
		event.world = 330;
		event.timestamp = "2026-07-16T18:41:02Z";
		event.pluginVersion = "1.0.0";
		event.sessionId = "sid";
		event.event = SessionEvent.EventType.LOGIN;
		return event;
	}

	private static LootDrop loot(String rsn)
	{
		LootDrop loot = new LootDrop();
		loot.rsn = rsn;
		loot.world = 330;
		loot.timestamp = "2026-07-16T18:41:02Z";
		loot.pluginVersion = "1.0.0";
		loot.itemId = 526;
		loot.itemName = "Bones";
		loot.quantity = 1;
		loot.geValue = 100L;
		loot.source = "Goblin";
		loot.sourceType = LootDrop.SourceType.NPC;
		return loot;
	}
}
