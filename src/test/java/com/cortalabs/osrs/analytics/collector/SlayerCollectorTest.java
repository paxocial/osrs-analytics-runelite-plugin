/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.SlayerCollector.SlayerTaskState;
import com.cortalabs.osrs.analytics.collector.SlayerCollector.Transition;
import com.cortalabs.osrs.analytics.dto.SlayerTaskUpdate.SlayerTransition;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Mutation-proving tests for {@link SlayerTaskState}, the pure transition state
 * machine behind the slayer collector. Each test names the honesty guarantee it
 * pins and the failure it catches; all run on plain data, no client.
 */
public class SlayerCollectorTest
{
	private static final long ACCOUNT_A = 111L;
	private static final long ACCOUNT_B = 222L;

	@Test
	public void firstWitnessedTaskEmitsAssigned()
	{
		SlayerTaskState state = new SlayerTaskState();
		Transition t = state.observeActive(ACCOUNT_A, "GARGOYLES", 185, "Catacombs of Kourend", 42, 1500);

		assertEquals("first witnessed task is an assignment", SlayerTransition.ASSIGNED, t.type);
		assertEquals("GARGOYLES", t.creature);
		assertEquals(Integer.valueOf(185), t.amount);
		assertEquals("Catacombs of Kourend", t.location);
		assertEquals(Integer.valueOf(42), t.streak);
		assertEquals(Integer.valueOf(1500), t.points);
	}

	@Test
	public void perKillDecrementEmitsNothing()
	{
		// The core volume law: SLAYER_COUNT fires on nearly every kill, but only the
		// assignment is a row. Break the identity dedup and each decrement emits -> fail.
		SlayerTaskState state = new SlayerTaskState();
		assertEquals(SlayerTransition.ASSIGNED,
			state.observeActive(ACCOUNT_A, "GARGOYLES", 185, null, 42, 1500).type);
		assertNull("a decrement to 184 is the same task, not a row",
			state.observeActive(ACCOUNT_A, "GARGOYLES", 184, null, 42, 1500));
		assertNull("a decrement to 1 is the same task, not a row",
			state.observeActive(ACCOUNT_A, "GARGOYLES", 1, null, 42, 1500));
	}

	@Test
	public void newTaskIdentityEmitsAssigned()
	{
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 185, null, 42, 1500);
		Transition t = state.observeActive(ACCOUNT_A, "ABYSSAL DEMONS", 130, null, 43, 1520);

		assertEquals("a different creature is a new assignment", SlayerTransition.ASSIGNED, t.type);
		assertEquals("ABYSSAL DEMONS", t.creature);
	}

	@Test
	public void locationChangeEmitsAssigned()
	{
		// Location is part of task identity: same creature at a new location is a new task.
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "BLACK DRAGONS", 140, null, 10, 100);
		Transition t = state.observeActive(ACCOUNT_A, "BLACK DRAGONS", 140, "Taverley Dungeon", 10, 100);

		assertEquals(SlayerTransition.ASSIGNED, t.type);
		assertEquals("Taverley Dungeon", t.location);
	}

	@Test
	public void amountToZeroEmitsCompletedWithStoredCreature()
	{
		// At amount==0 a fresh decode reads a stale target, so completion must use the
		// stored creature. Break the stored-identity carry and the completion is wrong/absent.
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 1, "Catacombs of Kourend", 42, 1500);
		Transition t = state.observeCleared(ACCOUNT_A, 43, 1515);

		assertEquals("count reaching 0 is a completion", SlayerTransition.COMPLETED, t.type);
		assertEquals("completion carries the stored creature", "GARGOYLES", t.creature);
		assertEquals("completion carries the stored location", "Catacombs of Kourend", t.location);
		assertEquals("completion remaining amount is 0", Integer.valueOf(0), t.amount);
		assertEquals("completion carries the current streak", Integer.valueOf(43), t.streak);
		assertEquals("completion carries the current points", Integer.valueOf(1515), t.points);
	}

	@Test
	public void clearedWithoutActiveTaskEmitsNothing()
	{
		// An idle "0 tasks" account must never fabricate a completion.
		SlayerTaskState state = new SlayerTaskState();
		assertNull(state.observeCleared(ACCOUNT_A, 0, 0));
	}

	@Test
	public void completionIsEmittedOnlyOnce()
	{
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 1, null, 42, 1500);
		assertEquals(SlayerTransition.COMPLETED, state.observeCleared(ACCOUNT_A, 43, 1500).type);
		assertNull("a second cleared observation has no task to complete",
			state.observeCleared(ACCOUNT_A, 43, 1500));
	}

	@Test
	public void assignAfterCompletionEmitsAssigned()
	{
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 185, null, 42, 1500);
		state.observeCleared(ACCOUNT_A, 43, 1500);
		Transition t = state.observeActive(ACCOUNT_A, "ABYSSAL DEMONS", 130, null, 43, 1520);

		assertEquals("a fresh task after a completion is an assignment", SlayerTransition.ASSIGNED, t.type);
		assertEquals("ABYSSAL DEMONS", t.creature);
	}

	@Test
	public void accountSwitchClearsTrackedTask()
	{
		// I4 fabrication guard: account B's identical task must be its OWN first assignment,
		// never suppressed by account A's cached task. Remove clearIfAccountChanged -> B is
		// deduped to null -> fail.
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 185, null, 42, 1500);
		Transition t = state.observeActive(ACCOUNT_B, "GARGOYLES", 185, null, 7, 90);

		assertEquals("account B's first task is a fresh assignment", SlayerTransition.ASSIGNED, t.type);
		assertEquals("account B's own streak, not A's", Integer.valueOf(7), t.streak);
	}

	@Test
	public void accountSwitchDropsPendingCompletion()
	{
		// After switching accounts, the previous account's task is forgotten: a cleared
		// observation on the new account fabricates nothing.
		SlayerTaskState state = new SlayerTaskState();
		state.observeActive(ACCOUNT_A, "GARGOYLES", 5, null, 42, 1500);
		assertNull("account B never had a task to complete",
			state.observeCleared(ACCOUNT_B, 0, 0));
	}
}
