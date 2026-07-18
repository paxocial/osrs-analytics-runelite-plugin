/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.NpcKillCollector.NpcKillTally;
import com.cortalabs.osrs.analytics.dto.NpcKillCounts;
import com.cortalabs.osrs.analytics.dto.NpcKillCounts.NpcKill;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Mutation-proving tests for the NPC kill collector's pure pieces: the KC-line parse,
 * the two-source {@link NpcKillTally} (with its same-kill dedup and account-switch
 * clear), and the wire cap. Each test names the guarantee it pins.
 */
public class NpcKillCollectorTest
{
	private static final long ACCOUNT_A = 111L;
	private static final long ACCOUNT_B = 222L;
	private static final String RSN = "Zezima";

	/** Kill counts of the accumulator keyed by NPC, for concise assertions. */
	private static Map<String, Integer> countsOf(NpcKillTally tally)
	{
		Map<String, Integer> out = new LinkedHashMap<>();
		for (NpcKill k : tally.kills())
		{
			out.put(k.npc, k.count);
		}
		return out;
	}

	// --- KC line parsing ---

	@Test
	public void parsesKillCountSubject()
	{
		assertEquals("Zulrah",
			NpcKillCollector.parseKillCountSubject("Your Zulrah kill count is: 1,412"));
		assertEquals("stripped tags still match", "Vorkath",
			NpcKillCollector.parseKillCountSubject("Your <col=ff0000>Vorkath</col> kill count is: 50"));
	}

	@Test
	public void nonKillCountLineParsesToNull()
	{
		assertNull(NpcKillCollector.parseKillCountSubject("Oh dear, you are dead!"));
		assertNull(NpcKillCollector.parseKillCountSubject("You have completed 42 medium Treasure Trails."));
		assertNull(NpcKillCollector.parseKillCountSubject(null));
	}

	// --- Tally: single-source counting ---

	@Test
	public void actorDeathCountsOneKill()
	{
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		assertTrue(tally.hasData());
		assertEquals(Integer.valueOf(1), countsOf(tally).get("Goblin"));
	}

	@Test
	public void kcLineCountsOneKill()
	{
		NpcKillTally tally = new NpcKillTally();
		tally.recordKc(ACCOUNT_A, 5, "Zulrah", RSN);
		assertEquals(Integer.valueOf(1), countsOf(tally).get("Zulrah"));
	}

	@Test
	public void despawnBossCountsFromKcAlone()
	{
		// A boss that despawns instead of emitting an ActorDeath is still a witnessed kill
		// via its KC line.
		NpcKillTally tally = new NpcKillTally();
		tally.recordKc(ACCOUNT_A, 5, "Vorkath", RSN);
		tally.recordKc(ACCOUNT_A, 8, "Vorkath", RSN);
		assertEquals(Integer.valueOf(2), countsOf(tally).get("Vorkath"));
	}

	// --- Tally: same-kill dedup across the two sources ---

	@Test
	public void bossDeathAndKcSameTickCountOnce()
	{
		// A boss emits BOTH an ActorDeath and a KC line for one kill. Break the pairing and
		// this counts 2 -> fail.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordKc(ACCOUNT_A, 5, "Zulrah", RSN);
		assertEquals("one kill, not two", Integer.valueOf(1), countsOf(tally).get("Zulrah"));
	}

	@Test
	public void bossKcThenDeathSameTickCountOnce()
	{
		// The dedup must work in either event order (KC can be processed before the death).
		NpcKillTally tally = new NpcKillTally();
		tally.recordKc(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordDeath(ACCOUNT_A, 5, "Zulrah", RSN);
		assertEquals(Integer.valueOf(1), countsOf(tally).get("Zulrah"));
	}

	@Test
	public void bossDeathAndKcAdjacentTickCountOnce()
	{
		// The KC line can land on the tick after the death; the one-tick window still pairs.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordKc(ACCOUNT_A, 6, "Zulrah", RSN);
		assertEquals(Integer.valueOf(1), countsOf(tally).get("Zulrah"));
	}

	@Test
	public void caseDifferenceStillPairs()
	{
		// The ActorDeath name and KC subject can differ in case; they are the same NPC.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordKc(ACCOUNT_A, 5, "zulrah", RSN);
		assertEquals("case-insensitive pairing counts one kill", 1, tally.kills().size());
		assertEquals(Integer.valueOf(1), countsOf(tally).values().iterator().next());
	}

	@Test
	public void twoRapidBossKillsAcrossTicksCountTwice()
	{
		// Multiplicity: two full kills (each a paired death+KC) on different ticks are two
		// kills, not one — the pairing consumes exactly one pending death per KC.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordKc(ACCOUNT_A, 5, "Zulrah", RSN);
		tally.recordDeath(ACCOUNT_A, 7, "Zulrah", RSN);
		tally.recordKc(ACCOUNT_A, 7, "Zulrah", RSN);
		assertEquals(Integer.valueOf(2), countsOf(tally).get("Zulrah"));
	}

	@Test
	public void aoeMultiKillSameTickEachCount()
	{
		// Trash mobs produce ActorDeath and no KC line; several dying on one tick (AoE) are
		// each real kills. A blanket per-name-per-tick dedup would collapse them to 1 -> fail.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		assertEquals(Integer.valueOf(3), countsOf(tally).get("Goblin"));
	}

	// --- Tally: honesty guards ---

	@Test
	public void emptySessionHasNoData()
	{
		assertFalse("a session that killed nothing has no data to flush", new NpcKillTally().hasData());
	}

	@Test
	public void accountSwitchClearsCounts()
	{
		// I4 fabrication guard: account A's kills must never appear in account B's flush.
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		tally.recordDeath(ACCOUNT_B, 5, "Rat", "Other");

		Map<String, Integer> counts = countsOf(tally);
		assertFalse("account A's Goblins must be gone after the switch", counts.containsKey("Goblin"));
		assertEquals(Integer.valueOf(1), counts.get("Rat"));
		assertEquals("rsn is account B's", "Other", tally.rsn());
	}

	@Test
	public void sessionIdIsStableWithinASessionAndFreshAfterClear()
	{
		NpcKillTally tally = new NpcKillTally();
		tally.recordDeath(ACCOUNT_A, 5, "Goblin", RSN);
		String first = tally.sessionId();
		tally.recordDeath(ACCOUNT_A, 6, "Goblin", RSN);
		assertEquals("session id is stable within a session", first, tally.sessionId());

		tally.clear();
		tally.recordDeath(ACCOUNT_A, 7, "Goblin", RSN);
		assertFalse("a new session gets a fresh id", first.equals(tally.sessionId()));
	}

	// --- Wire cap ---

	@Test
	public void capBelowLimitKeepsEveryNpc()
	{
		Map<String, Integer> counts = new LinkedHashMap<>();
		counts.put("Goblin", 3);
		counts.put("Rat", 1);
		List<NpcKill> capped = NpcKillCollector.toCappedKills(counts);
		assertEquals(2, capped.size());
	}

	@Test
	public void capKeepsHighestCountNpcsWhenOverLimit()
	{
		// Over the 256-entry cap the highest-count NPCs win; a pathological session cannot
		// 422 the batch. Remove the cap and the list exceeds the server max -> fail.
		Map<String, Integer> counts = new LinkedHashMap<>();
		int total = NpcKillCounts.MAX_KILLS + 2;
		for (int i = 0; i < total; i++)
		{
			counts.put(String.format("npc%03d", i), i + 1); // npc000=1 .. highest count last
		}
		List<NpcKill> capped = NpcKillCollector.toCappedKills(counts);

		assertEquals("capped to the server max", NpcKillCounts.MAX_KILLS, capped.size());
		boolean keptHighest = false;
		boolean droppedLowest = true;
		for (NpcKill k : capped)
		{
			if (k.npc.equals(String.format("npc%03d", total - 1)))
			{
				keptHighest = true;
			}
			if (k.npc.equals("npc000"))
			{
				droppedLowest = false;
			}
		}
		assertTrue("the highest-count NPC is kept", keptHighest);
		assertTrue("the lowest-count NPC is dropped", droppedLowest);
	}
}
