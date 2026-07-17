/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.dto.ActivityUpdate;
import com.cortalabs.osrs.analytics.dto.BankSnapshot;
import com.cortalabs.osrs.analytics.dto.BatchPayload;
import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.dto.CollectionPageSummary;
import com.cortalabs.osrs.analytics.dto.CombatAchievementProgress;
import com.cortalabs.osrs.analytics.dto.DiaryProgress;
import com.cortalabs.osrs.analytics.dto.EquipmentState;
import com.cortalabs.osrs.analytics.dto.LootDrop;
import com.cortalabs.osrs.analytics.dto.PluginPayload;
import com.cortalabs.osrs.analytics.dto.QuestStatus;
import com.cortalabs.osrs.analytics.dto.SessionEvent;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Buffers telemetry events and streams them to the Catherby backend via the
 * {@code POST /api/v1/plugin/batch} endpoint.
 *
 * <p>Design guarantees:
 * <ul>
 *   <li><b>Never blocks the client thread.</b> {@link #enqueue} is a cheap,
 *       thread-safe append; all HTTP runs on the injected scheduler thread.</li>
 *   <li><b>Rate-limit safe.</b> Sends are spaced at least {@code MIN_SPACING_MS}
 *       apart, keeping the plugin under the backend's 10 batch/min limit even
 *       when a backlog triggers early flushes.</li>
 *   <li><b>Resilient.</b> 429/5xx/IO failures requeue the batch (preserving each
 *       event's identity) and back off exponentially; 401/403 pause sending;
 *       404 (unknown RSN) requeues behind a long backoff so a session survives a
 *       late RSN registration; 422 (invalid payload) drops the batch instead of
 *       hammering the server.</li>
 *   <li><b>Never logs the API key.</b></li>
 * </ul>
 */
@Slf4j
@Singleton
public class AnalyticsClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final long BASE_BACKOFF_MS = 2_000L;
	private static final long MAX_BACKOFF_MS = 60_000L;
	/** Minimum spacing between sends: 6s => <= 10 requests/min (the batch limit). */
	private static final long MIN_SPACING_MS = 6_000L;
	/** Backoff after a 404 (unknown RSN): long, so a late registration is not lost but we don't hammer. */
	private static final long UNREGISTERED_BACKOFF_MS = 60_000L;

	/** Client transport state, surfaced for status/notifications. */
	public enum State
	{
		IDLE,
		OK,
		RATE_LIMITED,
		AUTH_FAILED,
		UNREGISTERED,
		ERROR
	}

	/** Sink for one-shot, user-facing notices (e.g. a single game chat line). */
	public interface Notifier
	{
		void notify(String message);
	}

	/**
	 * Immutable, thread-safe view of transport state for the status panel. Taken
	 * atomically under the queue lock so the panel never observes a torn state.
	 */
	public static final class Snapshot
	{
		public final State state;
		public final boolean enabled;
		public final int queueDepth;
		/** Wall-clock ms of the last accepted batch, or {@code 0} if none yet. */
		public final long lastAcceptedMs;
		/** Accepted-event counts indexed by {@link EventCategory#ordinal()}. */
		public final long[] acceptedByCategory;
		/** Backend base URL (never the API key). */
		public final String baseUrl;

		Snapshot(State state, boolean enabled, int queueDepth, long lastAcceptedMs,
			long[] acceptedByCategory, String baseUrl)
		{
			this.state = state;
			this.enabled = enabled;
			this.queueDepth = queueDepth;
			this.lastAcceptedMs = lastAcceptedMs;
			this.acceptedByCategory = acceptedByCategory;
			this.baseUrl = baseUrl;
		}

		public long acceptedFor(EventCategory category)
		{
			return acceptedByCategory[category.ordinal()];
		}

		public long totalAccepted()
		{
			long total = 0L;
			for (long n : acceptedByCategory)
			{
				total += n;
			}
			return total;
		}
	}

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final LongSupplier clockMs;
	private final Object lock = new Object();
	private final Deque<QueuedEvent> queue = new ArrayDeque<>();

	// Configuration (set via configure()).
	private volatile String baseUrl = "";
	private volatile String apiKey = "";
	private volatile boolean enabled = false;
	private volatile int maxBatchEvents = 256;
	private volatile int maxQueueSize = 5_000;

	// Runtime state (mutated on the scheduler thread; reset via configure()).
	private volatile State state = State.IDLE;
	private long nextAllowedSendMs = 0L;
	private long currentBackoffMs = BASE_BACKOFF_MS;
	private int consecutiveFailures = 0;
	private boolean notifiedAuth = false;
	private boolean notifiedUnregistered = false;

	// Status surface, read by the side panel on the EDT. Guarded by {@link #lock}
	// (except {@code lastAcceptedMs}, which is volatile for a cheap timestamp read).
	private volatile long lastAcceptedMs = 0L;
	private final long[] acceptedByCategory = new long[EventCategory.values().length];

	private Notifier notifier;
	private ScheduledFuture<?> flushTask;

	@Inject
	public AnalyticsClient(OkHttpClient httpClient, ScheduledExecutorService executor)
	{
		this(httpClient, executor, System::currentTimeMillis);
	}

	/** Test seam: inject a controllable clock. */
	AnalyticsClient(OkHttpClient httpClient, ScheduledExecutorService executor, LongSupplier clockMs)
	{
		this.httpClient = httpClient;
		this.executor = executor;
		this.clockMs = clockMs;
		this.gson = new GsonBuilder().create();
	}

	public void setNotifier(Notifier notifier)
	{
		this.notifier = notifier;
	}

	/**
	 * Update runtime configuration. Resets paused states so a corrected API key
	 * or a re-enable resumes sending on the next flush.
	 */
	public void configure(String baseUrl, String apiKey, boolean enabled, int maxBatchEvents, int maxQueueSize)
	{
		this.baseUrl = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
		this.apiKey = apiKey == null ? "" : apiKey.trim();
		this.enabled = enabled;
		this.maxBatchEvents = Math.max(1, maxBatchEvents);
		this.maxQueueSize = Math.max(1, maxQueueSize);
		// Clear paused/error latches; a new key or re-enable deserves a fresh try.
		this.state = State.IDLE;
		this.nextAllowedSendMs = 0L;
		this.currentBackoffMs = BASE_BACKOFF_MS;
		this.consecutiveFailures = 0;
		this.notifiedAuth = false;
		this.notifiedUnregistered = false;
	}

	/** Start the periodic flush loop. No-op if already running or no executor. */
	public synchronized void start(int flushIntervalSeconds)
	{
		if (executor == null || flushTask != null)
		{
			return;
		}
		long period = Math.max(1, flushIntervalSeconds);
		flushTask = executor.scheduleWithFixedDelay(this::safeFlush, period, period, TimeUnit.SECONDS);
	}

	/** Stop the flush loop and attempt one final best-effort flush if anything is queued. */
	public synchronized void stop()
	{
		if (flushTask != null)
		{
			flushTask.cancel(false);
			flushTask = null;
		}
		// Only spend a final send when there is actually something to deliver.
		if (executor != null && enabled && queueSize() > 0)
		{
			executor.execute(this::safeFlush);
		}
	}

	/**
	 * Trigger an out-of-band flush from the UI ("Flush now"). Runs on the shared
	 * executor — never the caller's (EDT/client) thread — and still honors the
	 * rate-limit spacing, so it can never breach the backend's batch limit.
	 */
	public void flushNow()
	{
		if (executor != null)
		{
			executor.execute(this::safeFlush);
		}
	}

	/**
	 * Queue an event for transport. Thread-safe and cheap; safe to call from the
	 * client thread. Drops the oldest event if the bounded queue is full.
	 */
	public void enqueue(EventCategory category, PluginPayload payload)
	{
		if (!enabled || payload == null || payload.rsn == null)
		{
			return;
		}
		QueuedEvent event = new QueuedEvent(category, payload);
		synchronized (lock)
		{
			while (queue.size() >= maxQueueSize)
			{
				queue.pollFirst();
				log.warn("Analytics queue full ({}); dropping oldest event", maxQueueSize);
			}
			queue.addLast(event);
		}
	}

	private void safeFlush()
	{
		try
		{
			flushOnce();
		}
		catch (RuntimeException ex)
		{
			log.warn("Analytics flush failed unexpectedly", ex);
		}
	}

	/**
	 * Perform a single flush cycle. Package-private so tests can drive it
	 * synchronously without the scheduler.
	 */
	void flushOnce()
	{
		if (!enabled || apiKey.isEmpty() || baseUrl.isEmpty())
		{
			return;
		}
		if (state == State.AUTH_FAILED)
		{
			// Retrying a bad key just wastes requests; wait for reconfigure().
			return;
		}
		if (now() < nextAllowedSendMs)
		{
			return;
		}

		List<QueuedEvent> drained;
		synchronized (lock)
		{
			if (queue.isEmpty())
			{
				return;
			}
			drained = new ArrayList<>();
			while (drained.size() < maxBatchEvents)
			{
				QueuedEvent e = queue.pollFirst();
				if (e == null)
				{
					break;
				}
				drained.add(e);
			}
		}

		// A batch may only carry one RSN (the backend resolves the account from
		// the batch-level rsn). Split off any events for other RSNs and requeue.
		String rsn = drained.get(0).rsn();
		List<QueuedEvent> group = new ArrayList<>();
		List<QueuedEvent> others = new ArrayList<>();
		for (QueuedEvent e : drained)
		{
			(Objects.equals(e.rsn(), rsn) ? group : others).add(e);
		}
		if (!others.isEmpty())
		{
			requeueFront(others);
		}
		if (rsn == null)
		{
			log.debug("Dropping {} telemetry event(s) with no RSN", group.size());
			return;
		}

		sendBatch(rsn, group);
	}

	private void sendBatch(String rsn, List<QueuedEvent> group)
	{
		for (QueuedEvent e : group)
		{
			e.markAttempt();
		}
		BatchPayload batch = assemble(rsn, group);
		Request request = new Request.Builder()
			.url(baseUrl + "/batch")
			.header("X-API-Key", apiKey)
			.header("Accept", "application/json")
			.post(RequestBody.create(JSON, gson.toJson(batch)))
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			handleResponse(response.code(), retryAfterMs(response), group);
		}
		catch (IOException ex)
		{
			// Never include the request (would expose the key); message only.
			log.debug("Analytics batch send failed: {}", ex.getMessage());
			requeueFront(group);
			backOff(nextBackoff());
			state = State.ERROR;
		}
	}

	private void handleResponse(int code, long retryAfterMs, List<QueuedEvent> group)
	{
		if (code >= 200 && code < 300)
		{
			onSuccess(group);
			return;
		}
		switch (code)
		{
			case 401:
			case 403:
				state = State.AUTH_FAILED;
				requeueFront(group);
				notifyOnceAuth();
				log.warn("Analytics auth rejected (HTTP {}); paused until API key is updated", code);
				break;
			case 404:
				// Unknown RSN: the account probably isn't registered YET. Keep the
				// batch (so a session start survives a late registration) and retry
				// behind a long backoff instead of hammering the server.
				state = State.UNREGISTERED;
				requeueFront(group);
				notifyOnceUnregistered();
				backOff(Math.max(nextBackoff(), UNREGISTERED_BACKOFF_MS));
				log.warn("Analytics account not registered (HTTP 404); requeued {} event(s) for retry after registration", group.size());
				break;
			case 429:
				state = State.RATE_LIMITED;
				requeueFront(group);
				backOff(retryAfterMs > 0 ? retryAfterMs : nextBackoff());
				log.debug("Analytics rate limited (HTTP 429); backing off");
				break;
			case 422:
				state = State.ERROR;
				backOff(nextBackoff());
				log.warn("Analytics rejected batch as invalid (HTTP 422); dropping {} event(s)", group.size());
				break;
			default:
				state = State.ERROR;
				requeueFront(group);
				backOff(nextBackoff());
				log.debug("Analytics batch failed (HTTP {}); requeued for retry", code);
				break;
		}
	}

	private void onSuccess(List<QueuedEvent> group)
	{
		// If we had told the user something was wrong (bad key / unregistered RSN),
		// let them know once that telemetry is flowing again.
		boolean recovered = notifiedAuth || notifiedUnregistered;
		state = State.OK;
		consecutiveFailures = 0;
		currentBackoffMs = BASE_BACKOFF_MS;
		nextAllowedSendMs = now() + MIN_SPACING_MS;
		lastAcceptedMs = now();
		synchronized (lock)
		{
			for (QueuedEvent e : group)
			{
				acceptedByCategory[e.getCategory().ordinal()]++;
			}
		}
		if (recovered)
		{
			notifyRecovered();
		}
		notifiedAuth = false;
		notifiedUnregistered = false;
		log.debug("Analytics batch accepted ({} event(s))", group.size());
	}

	private BatchPayload assemble(String rsn, List<QueuedEvent> group)
	{
		BatchPayload batch = new BatchPayload();
		batch.rsn = rsn;
		batch.timestamp = Instant.now().toString();
		PluginPayload first = group.get(0).getPayload();
		batch.world = first.world;
		batch.pluginVersion = first.pluginVersion;

		for (QueuedEvent e : group)
		{
			PluginPayload p = e.getPayload();
			switch (e.getCategory())
			{
				case SESSION:
					batch.sessions = add(batch.sessions, (SessionEvent) p);
					break;
				case XP:
					batch.xpSnapshots = add(batch.xpSnapshots, (XpSnapshot) p);
					break;
				case COLLECTION_LOG:
					batch.collectionLog = add(batch.collectionLog, (CollectionLogEntry) p);
					break;
				case COLLECTION_PAGE:
					batch.collectionPages = add(batch.collectionPages, (CollectionPageSummary) p);
					break;
				case QUEST:
					batch.quests = add(batch.quests, (QuestStatus) p);
					break;
				case DIARY:
					batch.diaries = add(batch.diaries, (DiaryProgress) p);
					break;
				case COMBAT_ACHIEVEMENT:
					batch.combatAchievements = add(batch.combatAchievements, (CombatAchievementProgress) p);
					break;
				case EQUIPMENT:
					batch.equipment = add(batch.equipment, (EquipmentState) p);
					break;
				case LOOT:
					batch.loot = add(batch.loot, (LootDrop) p);
					break;
				case ACTIVITY:
					batch.activity = add(batch.activity, (ActivityUpdate) p);
					break;
				case BANK:
					batch.bank = add(batch.bank, (BankSnapshot) p);
					break;
				default:
					break;
			}
		}
		return batch;
	}

	private static <T> List<T> add(List<T> list, T value)
	{
		if (list == null)
		{
			list = new ArrayList<>();
		}
		list.add(value);
		return list;
	}

	private void requeueFront(List<QueuedEvent> events)
	{
		synchronized (lock)
		{
			for (int i = events.size() - 1; i >= 0; i--)
			{
				queue.addFirst(events.get(i));
			}
			// Requeue must stay bounded by the queue cap. Unlike enqueue() (which
			// drops the OLDEST), here we drop the NEWEST excess so the just-requeued
			// events — the ones we are trying not to lose, e.g. a session start —
			// win over telemetry that arrived while we were backed off.
			while (queue.size() > maxQueueSize)
			{
				queue.pollLast();
				log.warn("Analytics queue full ({}) after requeue; dropping newest event", maxQueueSize);
			}
		}
	}

	private void backOff(long ms)
	{
		nextAllowedSendMs = now() + ms;
	}

	private long nextBackoff()
	{
		consecutiveFailures++;
		int shift = Math.min(consecutiveFailures - 1, 5);
		return Math.min(BASE_BACKOFF_MS * (1L << shift), MAX_BACKOFF_MS);
	}

	private void notifyOnceAuth()
	{
		if (!notifiedAuth && notifier != null)
		{
			notifier.notify("OSRS Analytics: API key rejected. Update it in the plugin config.");
		}
		notifiedAuth = true;
	}

	private void notifyOnceUnregistered()
	{
		if (!notifiedUnregistered && notifier != null)
		{
			notifier.notify("OSRS Analytics: this RSN is not registered with the backend yet. "
				+ "Register it in Catherby, then telemetry resumes automatically.");
		}
		notifiedUnregistered = true;
	}

	private void notifyRecovered()
	{
		if (notifier != null)
		{
			notifier.notify("OSRS Analytics: connected — telemetry flowing.");
		}
	}

	/**
	 * Parse the {@code Retry-After} header into milliseconds. Accepts the
	 * delta-seconds form ({@code "120"}) and the HTTP-date form
	 * ({@code "Wed, 21 Oct 2026 07:28:00 GMT"}); a past or unparseable value
	 * returns {@code 0}, letting the caller fall back to exponential backoff.
	 */
	private long retryAfterMs(Response response)
	{
		String header = response.header("Retry-After");
		if (header == null)
		{
			return 0L;
		}
		header = header.trim();
		try
		{
			return Long.parseLong(header) * 1000L;
		}
		catch (NumberFormatException ignored)
		{
			// Not integer seconds; try the HTTP-date form below.
		}
		try
		{
			long whenMs = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
				.toInstant().toEpochMilli();
			long deltaMs = whenMs - now();
			return deltaMs > 0 ? deltaMs : 0L;
		}
		catch (DateTimeParseException ignored)
		{
			return 0L;
		}
	}

	private static String stripTrailingSlash(String url)
	{
		while (url.endsWith("/"))
		{
			url = url.substring(0, url.length() - 1);
		}
		return url;
	}

	private long now()
	{
		return clockMs.getAsLong();
	}

	// --- Status surface (public: read by the side panel) ---

	/** Atomic, allocation-cheap snapshot of transport state for the status panel. */
	public Snapshot snapshot()
	{
		synchronized (lock)
		{
			return new Snapshot(
				state,
				enabled,
				queue.size(),
				lastAcceptedMs,
				acceptedByCategory.clone(),
				baseUrl);
		}
	}

	// --- Inspectors (package-private for tests) ---

	State getState()
	{
		return state;
	}

	int queueSize()
	{
		synchronized (lock)
		{
			return queue.size();
		}
	}

	List<String> queuedEventIds()
	{
		synchronized (lock)
		{
			List<String> ids = new ArrayList<>(queue.size());
			for (QueuedEvent e : queue)
			{
				ids.add(e.getEventId());
			}
			return ids;
		}
	}

	/** Advance the internal "next allowed send" gate (tests only). */
	void resetSendGate()
	{
		nextAllowedSendMs = 0L;
	}

	String describeState()
	{
		return state.name().toLowerCase(Locale.ROOT);
	}
}
