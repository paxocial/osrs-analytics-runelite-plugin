/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.transport;

import com.cortalabs.osrs.analytics.dto.ActivityBreakdown;
import com.cortalabs.osrs.analytics.dto.ActivityUpdate;
import com.cortalabs.osrs.analytics.dto.BankSnapshot;
import com.cortalabs.osrs.analytics.dto.BatchPayload;
import com.cortalabs.osrs.analytics.dto.CollectionLogEntry;
import com.cortalabs.osrs.analytics.dto.CollectionPageSummary;
import com.cortalabs.osrs.analytics.dto.CombatAchievementProgress;
import com.cortalabs.osrs.analytics.dto.DiaryProgress;
import com.cortalabs.osrs.analytics.dto.EfficiencyEnvelope;
import com.cortalabs.osrs.analytics.dto.EquipmentState;
import com.cortalabs.osrs.analytics.dto.FarmingState;
import com.cortalabs.osrs.analytics.dto.GeTrade;
import com.cortalabs.osrs.analytics.dto.LivePosition;
import com.cortalabs.osrs.analytics.dto.LootDrop;
import com.cortalabs.osrs.analytics.dto.NpcKillCounts;
import com.cortalabs.osrs.analytics.dto.PluginPayload;
import com.cortalabs.osrs.analytics.dto.QuestStatus;
import com.cortalabs.osrs.analytics.dto.RegionTimeShare;
import com.cortalabs.osrs.analytics.dto.SessionEvent;
import com.cortalabs.osrs.analytics.dto.SignalEvent;
import com.cortalabs.osrs.analytics.dto.SlayerTaskUpdate;
import com.cortalabs.osrs.analytics.dto.XpSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

	/**
	 * Supplies the current witnessed player position at flush time, or {@code null}
	 * when there is none fresh. Set once at startup (see {@link #setPositionSupplier});
	 * read on the scheduler thread in {@link #positionEvent}. Never touches the client.
	 */
	private volatile Supplier<LivePosition> positionSupplier;
	/**
	 * Last valid account envelope witnessed by enqueue(). It gives a quiet-game
	 * position heartbeat the same RSN/world/version authority as the most recent
	 * real telemetry event without touching the RuneLite client off-thread.
	 */
	private volatile PluginPayload heartbeatContext;

	@Inject
	public AnalyticsClient(OkHttpClient httpClient, ScheduledExecutorService executor, Gson gson)
	{
		this(httpClient, executor, gson, System::currentTimeMillis);
	}

	/** Test seam: inject a controllable clock. */
	AnalyticsClient(OkHttpClient httpClient, ScheduledExecutorService executor, Gson gson, LongSupplier clockMs)
	{
		this.httpClient = httpClient;
		this.executor = executor;
		this.clockMs = clockMs;
		this.gson = gson;
	}

	public void setNotifier(Notifier notifier)
	{
		this.notifier = notifier;
	}

	/**
	 * Provide the source of the per-post {@code position} block. The supplier is
	 * polled once per batch on the scheduler thread and must return the current
	 * witnessed position or {@code null} when there is none — its freshness /
	 * witnessed-or-absent contract is owned entirely by the supplier
	 * ({@code LivePositionCollector}); this client just attaches whatever it returns.
	 */
	public void setPositionSupplier(Supplier<LivePosition> positionSupplier)
	{
		this.positionSupplier = positionSupplier;
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
		heartbeatContext = copyContext(payload);
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

		List<QueuedEvent> drained = new ArrayList<>();
		synchronized (lock)
		{
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
		if (drained.isEmpty())
		{
			QueuedEvent heartbeat = positionEvent(heartbeatContext);
			if (heartbeat == null)
			{
				return;
			}
			drained.add(heartbeat);
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
		if (!hasPosition(group) && group.size() < maxBatchEvents)
		{
			QueuedEvent position = positionEvent(group.get(0).getPayload());
			if (position != null)
			{
				// Put the retry-stable position marker first so a bounded retry drain
				// can never strand it behind a full category batch.
				group.add(0, position);
			}
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
				if (e.getCategory() != EventCategory.POSITION)
				{
					acceptedByCategory[e.getCategory().ordinal()]++;
				}
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

	/**
	 * Consent-sync (contract C5): PUT the per-lane grant map derived from the
	 * user's plugin toggles to {@code /consent}. The server's deny-by-default
	 * consent table is the record of truth — until this map is synced, the
	 * backend silently drops EVERY telemetry lane (each drop is audited
	 * server-side), so the sync runs at startup and on every config change.
	 * Fire-and-forget on the scheduler thread; a failed sync only means the
	 * server keeps its previous (possibly deny-all) state.
	 */
	public void syncConsent(java.util.Map<String, Boolean> lanes)
	{
		if (executor == null || lanes == null || lanes.isEmpty())
		{
			return;
		}
		java.util.Map<String, Boolean> copy = new java.util.LinkedHashMap<>(lanes);
		executor.execute(() -> sendConsent(copy));
	}

	private void sendConsent(java.util.Map<String, Boolean> lanes)
	{
		if (baseUrl.isEmpty() || apiKey.isEmpty())
		{
			return;
		}
		java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("lanes", lanes);
		Request request = new Request.Builder()
			.url(baseUrl + "/consent")
			.header("X-API-Key", apiKey)
			.header("Accept", "application/json")
			.put(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.code() == 200)
			{
				log.debug("Analytics consent sync accepted ({} lanes)", lanes.size());
			}
			else
			{
				log.warn("Analytics consent sync rejected (HTTP {})", response.code());
			}
		}
		catch (IOException ex)
		{
			// Never include the request (would expose the key); message only.
			log.debug("Analytics consent sync failed: {}", ex.getMessage());
		}
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
				case REGION_TIME:
					batch.regionTime = add(batch.regionTime, (RegionTimeShare) p);
					break;
				case EFFICIENCY:
					batch.efficiency = add(batch.efficiency, (EfficiencyEnvelope) p);
					break;
				case ACTIVITY_TIME:
					batch.activityTime = add(batch.activityTime, (ActivityBreakdown) p);
					break;
				case SIGNAL_EVENT:
					batch.signalEvents = add(batch.signalEvents, (SignalEvent) p);
					break;
				case GE_TRADE:
					batch.geTrades = add(batch.geTrades, (GeTrade) p);
					break;
				case SLAYER_TASK:
					batch.slayerTasks = add(batch.slayerTasks, (SlayerTaskUpdate) p);
					break;
				case NPC_KILLS:
					batch.npcKills = add(batch.npcKills, (NpcKillCounts) p);
					break;
				case FARMING_STATE:
					batch.farmingState = add(batch.farmingState, (FarmingState) p);
					break;
				case POSITION:
					batch.position = e.position();
					break;
				default:
					break;
			}
		}
		return batch;
	}

	private QueuedEvent positionEvent(PluginPayload context)
	{
		Supplier<LivePosition> supplier = positionSupplier;
		if (context == null || supplier == null)
		{
			return null;
		}
		LivePosition position = supplier.get();
		return position == null ? null : QueuedEvent.position(copyContext(context), position);
	}

	private static boolean hasPosition(List<QueuedEvent> events)
	{
		for (QueuedEvent event : events)
		{
			if (event.getCategory() == EventCategory.POSITION)
			{
				return true;
			}
		}
		return false;
	}

	private static PluginPayload copyContext(PluginPayload source)
	{
		PluginPayload copy = new PluginPayload();
		copy.rsn = source.rsn;
		copy.world = source.world;
		copy.timestamp = source.timestamp;
		copy.pluginVersion = source.pluginVersion;
		return copy;
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
			notifier.notify("Catherby Analytics: API key rejected. Update it in the plugin config.");
		}
		notifiedAuth = true;
	}

	private void notifyOnceUnregistered()
	{
		if (!notifiedUnregistered && notifier != null)
		{
			notifier.notify("Catherby Analytics: this RSN is not registered with the backend yet. "
				+ "Register it in Catherby, then telemetry resumes automatically.");
		}
		notifiedUnregistered = true;
	}

	private void notifyRecovered()
	{
		if (notifier != null)
		{
			notifier.notify("Catherby Analytics: connected — telemetry flowing.");
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

	// ------------------------------------------------------------------
	// On-demand plugin-tab actions (contract C6)
	//
	// The side panel's Capture / Report / Snapshots tabs drive these on user
	// action. Unlike the telemetry batch loop they are NOT gated on the master
	// switch (configure()'s `enabled`): capturing a snapshot or copying a report
	// is a deliberate user request, independent of whether background telemetry
	// is streaming. They require only a configured base URL + API key.
	//
	// Each call has a package-private synchronous seam (driven directly by the
	// transport tests against MockWebServer) and a public async wrapper that runs
	// it on the shared executor and delivers the outcome on the executor thread —
	// the panel marshals that onto the EDT, exactly like LookupClient.
	// ------------------------------------------------------------------

	/** Why an on-demand action could not complete, so the panel shows an honest reason. */
	public enum ActionOutcome
	{
		/** No base URL / API key configured yet. */
		NOT_CONFIGURED,
		/** 401/403 — the API key was rejected. */
		UNAUTHORIZED,
		/** 404 — the account or snapshot is unknown or not owned by this key. */
		NOT_FOUND,
		/** 429 — the plugin rate limit was hit; try again shortly. */
		RATE_LIMITED,
		/** 5xx or an unreadable response — the server could not serve it. */
		UNAVAILABLE,
		/** The request never reached the server (IO/network failure). */
		NETWORK
	}

	/** Carries the failure kind out of a synchronous action seam. */
	public static final class ActionException extends Exception
	{
		private final ActionOutcome outcome;

		ActionException(ActionOutcome outcome, String message)
		{
			super(message);
			this.outcome = outcome;
		}

		public ActionOutcome outcome()
		{
			return outcome;
		}
	}

	/** Result sink for {@link #captureSnapshot}. Invoked on the executor thread. */
	public interface CaptureCallback
	{
		void onCaptured(SnapshotCapture result);

		void onFailure(ActionOutcome outcome, String message);
	}

	/** Result sink for {@link #listSnapshots}. Invoked on the executor thread. */
	public interface SnapshotsCallback
	{
		void onSnapshots(SnapshotPage page);

		void onFailure(ActionOutcome outcome, String message);
	}

	/** Result sink for {@link #fetchReport}. Invoked on the executor thread. */
	public interface ReportCallback
	{
		void onReport(String report);

		void onFailure(ActionOutcome outcome, String message);
	}

	/** Take an on-demand snapshot for {@code player}; the callback fires on the executor thread. */
	public void captureSnapshot(String player, CaptureCallback callback)
	{
		if (executor == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				callback.onCaptured(postSnapshot(player));
			}
			catch (ActionException ex)
			{
				callback.onFailure(ex.outcome(), ex.getMessage());
			}
			catch (RuntimeException ex)
			{
				callback.onFailure(ActionOutcome.UNAVAILABLE, "Snapshot failed");
			}
		});
	}

	/** List one page of the calling key's snapshots; the callback fires on the executor thread. */
	public void listSnapshots(int limit, int offset, SnapshotsCallback callback)
	{
		if (executor == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				callback.onSnapshots(getSnapshotPage(limit, offset));
			}
			catch (ActionException ex)
			{
				callback.onFailure(ex.outcome(), ex.getMessage());
			}
			catch (RuntimeException ex)
			{
				callback.onFailure(ActionOutcome.UNAVAILABLE, "Could not load snapshots");
			}
		});
	}

	/** Fetch a snapshot's rendered report markdown; the callback fires on the executor thread. */
	public void fetchReport(String snapshotId, ReportCallback callback)
	{
		if (executor == null)
		{
			return;
		}
		executor.execute(() ->
		{
			try
			{
				callback.onReport(getReport(snapshotId));
			}
			catch (ActionException ex)
			{
				callback.onFailure(ex.outcome(), ex.getMessage());
			}
			catch (RuntimeException ex)
			{
				callback.onFailure(ActionOutcome.UNAVAILABLE, "Could not fetch the report");
			}
		});
	}

	// --- Synchronous action seams (package-private for tests) ---

	SnapshotCapture postSnapshot(String player) throws ActionException
	{
		if (player == null || player.trim().isEmpty())
		{
			throw new ActionException(ActionOutcome.NOT_FOUND, "Enter an account name to snapshot.");
		}
		HttpUrl url = pluginBaseUrl().newBuilder().addPathSegment("snapshot").build();
		JsonObject body = new JsonObject();
		body.addProperty("player", player.trim());
		Request request = authorized(new Request.Builder().url(url))
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		try (Response response = httpClient.newCall(request).execute())
		{
			requireSuccess(response.code());
			return parseCapture(bodyText(response));
		}
		catch (IOException ex)
		{
			throw network(ex);
		}
	}

	SnapshotPage getSnapshotPage(int limit, int offset) throws ActionException
	{
		HttpUrl url = pluginBaseUrl().newBuilder()
			.addPathSegment("snapshots")
			.addQueryParameter("limit", Integer.toString(Math.max(1, limit)))
			.addQueryParameter("offset", Integer.toString(Math.max(0, offset)))
			.build();
		Request request = authorized(new Request.Builder().url(url)).get().build();
		try (Response response = httpClient.newCall(request).execute())
		{
			requireSuccess(response.code());
			return parsePage(bodyText(response), limit, offset);
		}
		catch (IOException ex)
		{
			throw network(ex);
		}
	}

	String getReport(String snapshotId) throws ActionException
	{
		if (snapshotId == null || snapshotId.trim().isEmpty())
		{
			throw new ActionException(ActionOutcome.NOT_FOUND, "No snapshot selected.");
		}
		HttpUrl url = pluginBaseUrl().newBuilder()
			.addPathSegment("snapshots")
			.addPathSegment(snapshotId.trim())
			.addPathSegment("report")
			.build();
		Request request = authorized(new Request.Builder().url(url)).get().build();
		try (Response response = httpClient.newCall(request).execute())
		{
			requireSuccess(response.code());
			return parseReport(bodyText(response));
		}
		catch (IOException ex)
		{
			throw network(ex);
		}
	}

	// --- Action helpers ---

	/** The configured plugin base URL, or an honest NOT_CONFIGURED failure. */
	private HttpUrl pluginBaseUrl() throws ActionException
	{
		if (baseUrl.isEmpty() || apiKey.isEmpty())
		{
			throw new ActionException(ActionOutcome.NOT_CONFIGURED,
				"Set the backend URL and API key in the plugin config.");
		}
		HttpUrl parsed = HttpUrl.parse(baseUrl);
		if (parsed == null)
		{
			throw new ActionException(ActionOutcome.NOT_CONFIGURED, "The backend URL is not valid.");
		}
		return parsed;
	}

	private Request.Builder authorized(Request.Builder builder)
	{
		return builder.header("X-API-Key", apiKey).header("Accept", "application/json");
	}

	private static String bodyText(Response response) throws IOException
	{
		return response.body() == null ? "" : response.body().string();
	}

	private static ActionException network(IOException ex)
	{
		return new ActionException(ActionOutcome.NETWORK, "Network error: " + ex.getMessage());
	}

	/** Map a non-2xx status onto an honest {@link ActionOutcome}; a 2xx just returns. */
	private static void requireSuccess(int code) throws ActionException
	{
		if (code >= 200 && code < 300)
		{
			return;
		}
		switch (code)
		{
			case 401:
			case 403:
				throw new ActionException(ActionOutcome.UNAUTHORIZED,
					"The API key was rejected. Check it in the plugin config.");
			case 404:
				throw new ActionException(ActionOutcome.NOT_FOUND,
					"Not found — this account or snapshot isn't one this key owns.");
			case 429:
				throw new ActionException(ActionOutcome.RATE_LIMITED,
					"Rate limited. Wait a moment and try again.");
			default:
				throw new ActionException(ActionOutcome.UNAVAILABLE,
					"The server couldn't complete that (HTTP " + code + ").");
		}
	}

	private SnapshotCapture parseCapture(String json) throws ActionException
	{
		JsonObject root = asObject(json);
		String id = optString(root, "snapshot_db_id", null);
		if (id == null)
		{
			throw new ActionException(ActionOutcome.UNAVAILABLE, "The snapshot response was incomplete.");
		}
		return new SnapshotCapture(id, optBool(root, "already_ingested"));
	}

	private SnapshotPage parsePage(String json, int limit, int offset) throws ActionException
	{
		JsonObject root = asObject(json);
		List<SnapshotSummary> rows = new ArrayList<>();
		if (root.has("snapshots") && root.get("snapshots").isJsonArray())
		{
			for (JsonElement element : root.getAsJsonArray("snapshots"))
			{
				if (element != null && element.isJsonObject())
				{
					rows.add(parseSummary(element.getAsJsonObject()));
				}
			}
		}
		int total = optInt(root, "total", rows.size());
		int resolvedLimit = optInt(root, "limit", limit);
		int resolvedOffset = optInt(root, "offset", offset);
		return new SnapshotPage(rows, total, resolvedLimit, resolvedOffset);
	}

	private static SnapshotSummary parseSummary(JsonObject row)
	{
		return new SnapshotSummary(
			optString(row, "snapshot_id", ""),
			optString(row, "account_id", ""),
			optString(row, "account_name", ""),
			optString(row, "resolved_mode", null),
			optString(row, "fetched_at", null),
			optInteger(row, "total_level"),
			optLongOrNull(row, "total_xp"));
	}

	private String parseReport(String json) throws ActionException
	{
		JsonObject root = asObject(json);
		String report = optString(root, "report", null);
		if (report == null)
		{
			throw new ActionException(ActionOutcome.UNAVAILABLE, "The server returned no report text.");
		}
		return report;
	}

	private JsonObject asObject(String json) throws ActionException
	{
		try
		{
			JsonElement parsed = gson.fromJson(json, JsonElement.class);
			if (parsed != null && parsed.isJsonObject())
			{
				return parsed.getAsJsonObject();
			}
		}
		catch (RuntimeException ignored)
		{
			// Fall through to the honest failure below.
		}
		throw new ActionException(ActionOutcome.UNAVAILABLE, "The server response was not readable.");
	}

	private static String optString(JsonObject obj, String key, String fallback)
	{
		return has(obj, key) ? obj.get(key).getAsString() : fallback;
	}

	private static boolean optBool(JsonObject obj, String key)
	{
		return has(obj, key) && obj.get(key).getAsBoolean();
	}

	private static int optInt(JsonObject obj, String key, int fallback)
	{
		return has(obj, key) ? obj.get(key).getAsInt() : fallback;
	}

	private static Integer optInteger(JsonObject obj, String key)
	{
		return has(obj, key) ? obj.get(key).getAsInt() : null;
	}

	private static Long optLongOrNull(JsonObject obj, String key)
	{
		return has(obj, key) ? obj.get(key).getAsLong() : null;
	}

	private static boolean has(JsonObject obj, String key)
	{
		return obj != null && obj.has(key) && !obj.get(key).isJsonNull();
	}
}
