/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Request/response client for player lookups and name-change submission.
 * Lookups hit the backend's WOM-compat surface at the API <b>root</b>
 * ({@code GET /players/{name}}, not under {@code /api/v1/plugin}); name changes
 * post to the authenticated plugin intake ({@code {base}/name-changes}) — never
 * the inert WOM-compat {@code /names/bulk} stub.
 *
 * <p>All network runs on the shared executor, never the caller's (EDT/client)
 * thread. Lookups deliver their result through a {@link LookupCallback} on the
 * executor thread; the panel marshals that onto the EDT.
 */
@Slf4j
@Singleton
public class LookupClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Result sink for an async lookup. Invoked on the executor thread. */
	public interface LookupCallback
	{
		void onSuccess(PlayerLookup result);

		void onNotFound(String username);

		void onError(String message);
	}

	/** Disposition of a name-change submission. Invoked on the executor thread. */
	public interface NameChangeOutcome
	{
		/** Server gave a definitive answer (200 outcomes / 422) — stop, persist. */
		void onFinal();

		/** Transient (401/403/404-not-built/429/5xx/network) — retry on a later login. */
		void onRetryLater();
	}

	/** Thrown by the synchronous fetch to carry the failure kind. */
	static final class LookupException extends Exception
	{
		final boolean notFound;

		LookupException(boolean notFound, String message)
		{
			super(message);
			this.notFound = notFound;
		}
	}

	private final OkHttpClient httpClient;
	private final ScheduledExecutorService executor;
	private final Supplier<String> baseUrlSupplier;
	private final Supplier<String> apiKeySupplier;
	private final Gson gson;

	@Inject
	public LookupClient(OkHttpClient httpClient, ScheduledExecutorService executor, AnalyticsConfig config, Gson gson)
	{
		this(httpClient, executor, config::apiBaseUrl, config::apiKey, gson);
	}

	/** Test seam: supply base URL / key directly instead of a RuneLite config. */
	LookupClient(OkHttpClient httpClient, ScheduledExecutorService executor,
		Supplier<String> baseUrlSupplier, Supplier<String> apiKeySupplier, Gson gson)
	{
		this.httpClient = httpClient;
		this.executor = executor;
		this.baseUrlSupplier = baseUrlSupplier;
		this.apiKeySupplier = apiKeySupplier;
		this.gson = gson;
	}

	/** Look up a player asynchronously; the callback fires on the executor thread. */
	public void lookup(String username, LookupCallback callback)
	{
		if (executor == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				callback.onSuccess(fetchPlayer(username));
			}
			catch (LookupException ex)
			{
				if (ex.notFound)
				{
					callback.onNotFound(username);
				}
				else
				{
					callback.onError(ex.getMessage());
				}
			}
			catch (RuntimeException ex)
			{
				callback.onError("Lookup failed");
			}
		});
	}

	/**
	 * Submit a single RSN name change asynchronously to {@code {base}/name-changes}
	 * (authenticated). The outcome tells the caller whether to persist (final) or
	 * retry on a later login (transient) — see {@link #isFinalNameChangeCode(int)}.
	 */
	public void submitNameChange(String oldName, String newName, NameChangeOutcome outcome)
	{
		if (executor == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				int code = postNameChange(oldName, newName);
				if (isFinalNameChangeCode(code))
				{
					log.debug("Name-change {}->{} final (HTTP {})", oldName, newName, code);
					outcome.onFinal();
				}
				else
				{
					log.debug("Name-change {}->{} deferred (HTTP {}); will retry", oldName, newName, code);
					outcome.onRetryLater();
				}
			}
			catch (IOException ex)
			{
				log.debug("Name-change submit failed: {}", ex.getMessage());
				outcome.onRetryLater();
			}
		});
	}

	// --- Synchronous transport (package-private test seams) ---

	PlayerLookup fetchPlayer(String username) throws LookupException
	{
		HttpUrl root = rootUrl();
		if (root == null)
		{
			throw new LookupException(false, "Backend URL is not configured");
		}
		HttpUrl url = root.newBuilder().addPathSegment("players").addPathSegment(username).build();
		Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json").get();
		String apiKey = apiKeySupplier.get();
		if (apiKey != null && !apiKey.trim().isEmpty())
		{
			builder.header("X-API-Key", apiKey.trim());
		}
		try (Response response = httpClient.newCall(builder.build()).execute())
		{
			if (response.code() == 404)
			{
				throw new LookupException(true, "Player not found");
			}
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new LookupException(false, "Backend returned HTTP " + response.code());
			}
			return parsePlayer(username, response.body().string());
		}
		catch (IOException ex)
		{
			throw new LookupException(false, "Network error: " + ex.getMessage());
		}
	}

	/**
	 * POST a name change to {@code {base}/name-changes} (the authenticated plugin
	 * intake, BP-C20 — NOT the inert WOM-compat {@code /names/bulk} stub). Returns
	 * the HTTP status code; {@code 0} means the base URL was unset.
	 */
	int postNameChange(String oldName, String newName) throws IOException
	{
		HttpUrl base = baseUrl();
		if (base == null)
		{
			return 0;
		}
		HttpUrl url = base.newBuilder().addPathSegment("name-changes").build();
		NameChange change = new NameChange(oldName, newName);
		String body = gson.toJson(Collections.singletonList(change));
		Request.Builder builder = new Request.Builder()
			.url(url)
			.header("Accept", "application/json")
			.post(RequestBody.create(JSON, body));
		String apiKey = apiKeySupplier.get();
		if (apiKey != null && !apiKey.trim().isEmpty())
		{
			builder.header("X-API-Key", apiKey.trim());
		}
		try (Response response = httpClient.newCall(builder.build()).execute())
		{
			return response.code();
		}
	}

	/**
	 * A name-change POST is "final" (do not retry) on a definitive server answer:
	 * 200 (per-item outcomes renamed/not_found/conflict/noop) or 422 (rejected).
	 * Everything else — 401/403 (auth), 404 (endpoint not built yet, BP-C20), 429,
	 * 5xx, network — is transient and is retried on a later login.
	 */
	static boolean isFinalNameChangeCode(int code)
	{
		return code == 200 || code == 422;
	}

	private PlayerLookup parsePlayer(String username, String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		String displayName = optString(root, "displayName", username);
		String type = optString(root, "type", "");
		String updatedAt = optString(root, "updatedAt", "");

		List<PlayerLookup.SkillRow> skills = new ArrayList<>();
		List<PlayerLookup.ActivityRow> activities = new ArrayList<>();
		PlayerLookup.SkillRow overall = null;

		JsonObject data = nested(root, "latestSnapshot", "data");
		if (data != null)
		{
			JsonObject skillsObj = data.getAsJsonObject("skills");
			if (skillsObj != null)
			{
				for (Map.Entry<String, JsonElement> entry : skillsObj.entrySet())
				{
					JsonObject s = entry.getValue().getAsJsonObject();
					PlayerLookup.SkillRow row = new PlayerLookup.SkillRow(
						entry.getKey(),
						optInt(s, "level"),
						optLong(s, "experience"),
						optLong(s, "rank"));
					if ("overall".equals(entry.getKey()))
					{
						overall = row;
					}
					else
					{
						skills.add(row);
					}
				}
			}
			JsonObject activitiesObj = data.getAsJsonObject("activities");
			if (activitiesObj != null)
			{
				for (Map.Entry<String, JsonElement> entry : activitiesObj.entrySet())
				{
					JsonObject a = entry.getValue().getAsJsonObject();
					activities.add(new PlayerLookup.ActivityRow(
						entry.getKey(),
						optLong(a, "score"),
						optLong(a, "rank")));
				}
			}
		}
		return new PlayerLookup(username, displayName, type, updatedAt, overall, skills, activities);
	}

	/** The configured plugin base URL (keeps its {@code /api/v1/plugin} path). */
	private HttpUrl baseUrl()
	{
		String base = baseUrlSupplier.get();
		if (base == null || base.trim().isEmpty())
		{
			return null;
		}
		return HttpUrl.parse(base.trim());
	}

	/** Derive the API root ({@code scheme://host:port}) from the plugin base URL. */
	HttpUrl rootUrl()
	{
		String base = baseUrlSupplier.get();
		if (base == null || base.trim().isEmpty())
		{
			return null;
		}
		HttpUrl parsed = HttpUrl.parse(base.trim());
		if (parsed == null)
		{
			return null;
		}
		return new HttpUrl.Builder()
			.scheme(parsed.scheme())
			.host(parsed.host())
			.port(parsed.port())
			.build();
	}

	private static JsonObject nested(JsonObject obj, String... path)
	{
		JsonObject current = obj;
		for (String key : path)
		{
			if (current == null || !current.has(key) || !current.get(key).isJsonObject())
			{
				return null;
			}
			current = current.getAsJsonObject(key);
		}
		return current;
	}

	private static String optString(JsonObject obj, String key, String fallback)
	{
		return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
	}

	private static int optInt(JsonObject obj, String key)
	{
		return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
	}

	private static long optLong(JsonObject obj, String key)
	{
		return obj != null && obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0L;
	}

	/** Wire shape for {@code POST /names/bulk} entries. */
	private static final class NameChange
	{
		final String oldName;
		final String newName;

		NameChange(String oldName, String newName)
		{
			this.oldName = oldName;
			this.newName = newName;
		}
	}
}
