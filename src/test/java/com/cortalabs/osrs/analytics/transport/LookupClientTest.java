/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.function.Supplier;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Behavioral tests for {@link LookupClient}: the WOM-compat root-API surface
 * ({@code GET /players/{name}} and {@code POST /names/bulk}). Drives the
 * synchronous transport seams against a MockWebServer.
 */
public class LookupClientTest
{
	private MockWebServer server;
	private LookupClient client;

	@Before
	public void setUp() throws Exception
	{
		server = new MockWebServer();
		server.start();
		// The plugin base URL carries the /api/v1/plugin path; the lookup client must
		// derive the API ROOT from it (the WOM-compat routes live at the root).
		Supplier<String> baseUrl = () -> server.url("/api/v1/plugin").toString();
		client = new LookupClient(new OkHttpClient(), null, baseUrl, () -> "test-key");
	}

	@After
	public void tearDown() throws Exception
	{
		server.shutdown();
	}

	private static final String ENVELOPE = "{"
		+ "\"displayName\":\"Zezima\","
		+ "\"type\":\"regular\","
		+ "\"updatedAt\":\"2026-07-16T18:41:02Z\","
		+ "\"latestSnapshot\":{\"data\":{"
		+ "  \"skills\":{"
		+ "    \"overall\":{\"experience\":4600000,\"rank\":12345,\"level\":2000},"
		+ "    \"attack\":{\"experience\":500000,\"rank\":5000,\"level\":92}"
		+ "  },"
		+ "  \"activities\":{"
		+ "    \"clue_scrolls_all\":{\"score\":500,\"rank\":1000}"
		+ "  }"
		+ "}}}";

	@Test
	public void fetchPlayerParsesWomEnvelope() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200).setBody(ENVELOPE));

		PlayerLookup result = client.fetchPlayer("Zezima");

		RecordedRequest request = server.takeRequest();
		assertEquals("GET", request.getMethod());
		assertEquals("/players/Zezima", request.getPath());

		assertEquals("Zezima", result.displayName);
		assertEquals("regular", result.type);
		assertNotNull("overall is split out of the skills list", result.overall);
		assertEquals(2000, result.overall.level);
		assertEquals(1, result.skills.size());
		assertEquals("attack", result.skills.get(0).name);
		assertEquals(92, result.skills.get(0).level);
		assertEquals(1, result.activities.size());
		assertEquals(500L, result.activities.get(0).score);
	}

	@Test
	public void fetchPlayerUnknownIsNotFound() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(404).setBody("{\"detail\":\"not found\"}"));
		try
		{
			client.fetchPlayer("Nobody");
			fail("expected LookupException");
		}
		catch (LookupClient.LookupException ex)
		{
			assertTrue("404 maps to not-found", ex.notFound);
		}
	}

	@Test
	public void fetchPlayerServerErrorIsError() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503));
		try
		{
			client.fetchPlayer("Zezima");
			fail("expected LookupException");
		}
		catch (LookupClient.LookupException ex)
		{
			assertTrue("5xx is a generic error, not not-found", !ex.notFound);
		}
	}

	@Test
	public void submitNameChangePostsAuthenticatedListToNameChanges() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(200)
			.setBody("[{\"oldName\":\"OldName\",\"newName\":\"NewName\",\"outcome\":\"renamed\"}]"));

		int code = client.postNameChange("OldName", "NewName");
		assertEquals(200, code);

		RecordedRequest request = server.takeRequest();
		assertEquals("POST", request.getMethod());
		assertEquals("posts to the authenticated plugin intake, not the WOM-compat stub",
			"/api/v1/plugin/name-changes", request.getPath());
		assertEquals("carries the plugin API key", "test-key", request.getHeader("X-API-Key"));

		@SuppressWarnings("deprecation")
		JsonArray body = new JsonParser().parse(request.getBody().readUtf8()).getAsJsonArray();
		assertEquals(1, body.size());
		JsonObject change = body.get(0).getAsJsonObject();
		assertEquals("OldName", change.get("oldName").getAsString());
		assertEquals("NewName", change.get("newName").getAsString());
	}

	@Test
	public void nameChangeFinalityFollowsTheContract()
	{
		// Definitive answers -> stop and persist.
		assertTrue(LookupClient.isFinalNameChangeCode(200));
		assertTrue(LookupClient.isFinalNameChangeCode(422));
		// Transient -> retry on a later login (incl. 404 = intake not built yet).
		assertFalse(LookupClient.isFinalNameChangeCode(404));
		assertFalse(LookupClient.isFinalNameChangeCode(401));
		assertFalse(LookupClient.isFinalNameChangeCode(429));
		assertFalse(LookupClient.isFinalNameChangeCode(503));
	}

	@Test
	public void rootUrlStripsThePluginPath()
	{
		// Whatever base path the plugin config carries, the derived root is host:port only.
		assertEquals(server.url("/").toString(), client.rootUrl().toString());
	}
}
