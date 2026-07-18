/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.NpcKillCounts;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Per-session NPC kill collector: accumulates witnessed kills per NPC and flushes
 * a single {@link NpcKillCounts} payload at session end — the same aggregate-and-flush
 * shape as {@link RegionTimeShareCollector}.
 *
 * <p><b>Two witnessed sources, one kill each (I2).</b>
 * <ul>
 *   <li><b>{@link ActorDeath}</b> — an NPC that the local player was fighting dies.
 *       Attribution is interaction-based: the dying NPC is (or was) interacting with
 *       the local player, or vice-versa. This is a witnessed-but-heuristic bar — a
 *       multi-combat last-hit by someone else, or a tag-and-leave, can miss or
 *       misattribute — so it is deliberately conservative and never counts a death the
 *       player had no interaction with.</li>
 *   <li><b>KC chat</b> — the game's own "Your &lt;subject&gt; (kill|harvest|lap|
 *       completion) count is: N" line, which is authoritatively player-attributed by
 *       the game. This catches the kills {@code ActorDeath} misses (bosses that despawn
 *       rather than emit a clean death). The corpus is the same one
 *       {@link SignalEventCollector} matches for {@code boss_kc}; the pattern is kept
 *       local because that constant is private and {@code SignalEventCollector} is
 *       outside this collector's change scope.</li>
 * </ul>
 *
 * <p><b>Same-kill dedup ({@link NpcKillTally}).</b> A boss produces BOTH an
 * {@code ActorDeath} and a KC line for the one kill; counting both would double it. The
 * tally pairs an {@code ActorDeath} and a KC line for the same NPC within a one-tick
 * window (either event order) and counts the pair once — while an {@code ActorDeath}
 * with no KC (regular monsters, including several killed on one tick by AoE) each count,
 * and a KC with no {@code ActorDeath} (a despawn boss) counts via the chat line.
 *
 * <p><b>Account-switch clear (I4) + witnessed-or-absent (I1).</b> The tally is bound to
 * the current {@code accountHash} and cleared on change; a session that killed nothing
 * flushes no payload (never an empty accumulator). The wire list is capped at
 * {@link NpcKillCounts#MAX_KILLS} distinct NPCs — the highest-count NPCs win, with one
 * warning — so a pathological session cannot 422 the batch (I3).
 *
 * <p>Flush trigger mirrors {@link SessionCollector} / {@link RegionTimeShareCollector}:
 * {@code LOGIN_SCREEN} / {@code CONNECTION_LOST} flush; a world hop keeps the session.
 */
@Slf4j
@Singleton
public class NpcKillCollector
{
	/**
	 * The game's kill-count line — the same Chat-Commands corpus
	 * {@link SignalEventCollector} matches for {@code boss_kc}: "Your &lt;subject&gt;
	 * (kill|harvest|lap|completion) count is: N" (tags already stripped). Duplicated here
	 * with attribution because that constant is private and {@code SignalEventCollector}
	 * is outside this collector's change scope.
	 */
	private static final Pattern KILL_COUNT =
		Pattern.compile("Your (.+?) (?:kill|harvest|lap|completion) count is: ?[0-9,]+");

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final NpcKillTally tally = new NpcKillTally();

	@Inject
	public NpcKillCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (!config.enabled() || !config.trackNpcKills())
		{
			return;
		}
		Actor dead = event.getActor();
		if (!(dead instanceof NPC))
		{
			return;
		}
		if (!attributableKill((NPC) dead))
		{
			// Not a kill we witnessed the player make: never fabricate a kill from an
			// unrelated actor's death.
			return;
		}
		String name = cleanName(dead.getName());
		if (name == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		tally.recordDeath(client.getAccountHash(), client.getTickCount(), name, rsn);
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.enabled() || !config.trackNpcKills())
		{
			return;
		}
		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM)
		{
			return;
		}
		String subject = parseKillCountSubject(event.getMessage());
		if (subject == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		tally.recordKc(client.getAccountHash(), client.getTickCount(), subject, rsn);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			flush();
		}
	}

	/**
	 * Interaction-attributed: the dying NPC is (or was) interacting with the local
	 * player, or the local player with it. Actor identity is compared by reference, the
	 * way RuneLite's own combat features attribute a target.
	 */
	private boolean attributableKill(NPC npc)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return false;
		}
		return npc.getInteracting() == me || me.getInteracting() == npc;
	}

	/**
	 * Extract the subject of a kill-count line, or {@code null} when the line is not one.
	 * Tags are stripped first so the corpus (written against {@code <col=...>} markup)
	 * matches the plain text — mirroring {@link SignalEventCollector}.
	 */
	static String parseKillCountSubject(String rawMessage)
	{
		if (rawMessage == null)
		{
			return null;
		}
		String message = Text.removeTags(rawMessage).trim();
		if (message.isEmpty())
		{
			return null;
		}
		Matcher m = KILL_COUNT.matcher(message);
		if (!m.find())
		{
			return null;
		}
		return cleanName(m.group(1));
	}

	/** RuneLite pads names with a non-breaking space; normalize and reject empties. */
	private static String cleanName(String name)
	{
		if (name == null)
		{
			return null;
		}
		String cleaned = name.replace('\u00A0', ' ').trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private void flush()
	{
		if (!tally.hasData() || tally.rsn() == null)
		{
			// Witnessed-or-absent: nothing accumulated (or no attributable rsn) -> no row.
			tally.clear();
			return;
		}
		NpcKillCounts payload = new NpcKillCounts();
		Payloads.base(payload, client, tally.rsn());
		payload.sessionId = tally.sessionId();
		payload.kills = tally.kills();
		analytics.enqueue(EventCategory.NPC_KILLS, payload);
		tally.clear();
	}

	/**
	 * Build the flushed kill list, capped at {@link NpcKillCounts#MAX_KILLS} (I3). Below
	 * the cap entries keep first-seen order; above it the highest-count NPCs win (least
	 * information dropped), tie-broken by name for determinism, and the drop is logged.
	 * Static + client-free so the cap is testable with plain data, exactly like
	 * {@link RegionTimeShareCollector#toCappedRegions}.
	 */
	static List<NpcKillCounts.NpcKill> toCappedKills(Map<String, Integer> counts)
	{
		List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
		if (entries.size() > NpcKillCounts.MAX_KILLS)
		{
			entries.sort((a, b) ->
			{
				int byCount = Integer.compare(b.getValue(), a.getValue());
				return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
			});
			int dropped = entries.size() - NpcKillCounts.MAX_KILLS;
			entries = entries.subList(0, NpcKillCounts.MAX_KILLS);
			log.warn("NpcKillCounts flush had {} NPCs; kept top {}, dropped {}",
				dropped + NpcKillCounts.MAX_KILLS, NpcKillCounts.MAX_KILLS, dropped);
		}
		List<NpcKillCounts.NpcKill> out = new ArrayList<>(entries.size());
		for (Map.Entry<String, Integer> e : entries)
		{
			out.add(new NpcKillCounts.NpcKill(e.getKey(), e.getValue()));
		}
		return out;
	}

	/**
	 * In-memory, per-session per-NPC kill accumulator fed by two witnessed sources.
	 * Client-free so its three honesty guarantees — aggregate (no per-kill row),
	 * same-kill dedup across the two sources, and account-switch clear — are testable
	 * with plain data, exactly like {@link AccountKeyedDeltaGuard}.
	 *
	 * <p>Not thread-safe: called only from the client thread.
	 */
	static final class NpcKillTally
	{
		/**
		 * How many ticks apart an {@code ActorDeath} and its KC echo may be and still
		 * count as one kill. The game emits both on the same tick in the common case; a
		 * one-tick slack covers a KC line processed on the following tick.
		 */
		static final int PAIR_WINDOW_TICKS = 1;

		private final Map<String, Integer> counts = new LinkedHashMap<>();
		/** Recent unmatched ActorDeath ticks per pairing key, awaiting a KC echo. */
		private final Map<String, Deque<Integer>> pendingDeaths = new HashMap<>();
		/** Recent unmatched KC ticks per pairing key, awaiting an ActorDeath echo. */
		private final Map<String, Deque<Integer>> pendingKc = new HashMap<>();
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private String sessionId;
		private String rsn;
		private boolean active;

		/** Record a witnessed kill from an {@link ActorDeath}. */
		void recordDeath(long accountHash, int tick, String npcName, String rsn)
		{
			bindAccount(accountHash);
			startSession(rsn);
			if (tryConsume(pendingKc, npcName, tick))
			{
				// A KC line already counted this kill this tick: don't count it twice.
				return;
			}
			count(npcName);
			record(pendingDeaths, npcName, tick);
		}

		/** Record a witnessed kill from a KC chat line. */
		void recordKc(long accountHash, int tick, String subject, String rsn)
		{
			bindAccount(accountHash);
			startSession(rsn);
			if (tryConsume(pendingDeaths, subject, tick))
			{
				// An ActorDeath already counted this kill this tick: don't count it twice.
				return;
			}
			count(subject);
			record(pendingKc, subject, tick);
		}

		boolean hasData()
		{
			return active && !counts.isEmpty();
		}

		String sessionId()
		{
			return sessionId;
		}

		String rsn()
		{
			return rsn;
		}

		List<NpcKillCounts.NpcKill> kills()
		{
			return toCappedKills(counts);
		}

		/** Drop the session's accumulated state. Leaves the bound account intact. */
		void clear()
		{
			counts.clear();
			pendingDeaths.clear();
			pendingKc.clear();
			sessionId = null;
			rsn = null;
			active = false;
		}

		private void bindAccount(long accountHash)
		{
			if (accountHash != account)
			{
				account = accountHash;
				clear();
			}
		}

		private void startSession(String rsn)
		{
			if (!active)
			{
				active = true;
				sessionId = UUID.randomUUID().toString();
			}
			this.rsn = rsn;
		}

		private void count(String name)
		{
			counts.merge(name, 1, Integer::sum);
		}

		/** Pairing key: names differing only in case are the same NPC across the two sources. */
		private static String pairingKey(String name)
		{
			return name.toLowerCase(Locale.ROOT);
		}

		private static void record(Map<String, Deque<Integer>> pending, String name, int tick)
		{
			pending.computeIfAbsent(pairingKey(name), k -> new ArrayDeque<>()).addLast(tick);
		}

		/**
		 * Consume one pending opposite-source kill for {@code name} within the pairing
		 * window of {@code tick}, if any. Prunes entries older than the window first, so a
		 * stale death/KC can never suppress a genuinely later kill.
		 */
		private static boolean tryConsume(Map<String, Deque<Integer>> pending, String name, int tick)
		{
			Deque<Integer> deque = pending.get(pairingKey(name));
			if (deque == null)
			{
				return false;
			}
			while (!deque.isEmpty() && deque.peekFirst() < tick - PAIR_WINDOW_TICKS)
			{
				deque.pollFirst();
			}
			if (deque.isEmpty())
			{
				pending.remove(pairingKey(name));
				return false;
			}
			deque.pollLast();
			if (deque.isEmpty())
			{
				pending.remove(pairingKey(name));
			}
			return true;
		}
	}
}
