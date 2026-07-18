/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.SlayerTaskUpdate;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Slayer task collector: turns witnessed slayer-task varp/varbit changes into
 * append-only {@link SlayerTaskUpdate} rows — one on assignment, one on
 * completion, never per kill.
 *
 * <p><b>Source of truth:</b> FP-SPIKE-SLAYER ({@code RESEARCH_FP_SPIKE_SLAYER.md}),
 * which lifted every id and decode path to VERIFIED against RuneLite
 * {@code SlayerPlugin.java} at {@code master}. The ids below are the exact ones
 * that plugin reads; the DBTable decode in {@link #decodeCreature(int)} /
 * {@link #decodeArea(int)} is transcribed 1:1 from {@code SlayerPlugin.updateTask()}
 * (SlayerPlugin.java L358-403).
 *
 * <p><b>Transitions only, never per-kill (I2/I3).</b> The plugin recomputes the
 * task on the same trigger set it does — a change to {@code SLAYER_COUNT}/
 * {@code SLAYER_TARGET}/{@code SLAYER_AREA}/{@code SLAYER_COUNT_ORIGINAL} (varps) or
 * {@code SLAYER_TARGET_BOSSID}/{@code SLAYER_POINTS}/{@code SLAYER_MASTER}/streak
 * (varbits) — but the pure {@link SlayerTaskState} emits a row only on an
 * <i>assignment</i> edge (the task creature/location changed, or the first task we
 * witness for an account) or a <i>completion</i> edge (remaining count reaches 0).
 * A bare {@code SLAYER_COUNT} decrement — which fires on nearly every kill — is a
 * redundant re-observation of the same task and emits nothing, exactly the volume
 * blowout the SPEC flags for this lane.
 *
 * <p><b>Witnessed-or-absent (I2).</b> {@code SLAYER_TARGET} is a cache-internal
 * task id, not a name (and {@code 98} is a boss sentinel, not a monster); the
 * creature is resolved through the game DBTable API on the client thread. If any
 * required DBTable lookup returns empty (the cache is not loaded yet), the whole
 * update is dropped — a half-decoded task emits nothing rather than shipping a
 * meaningless id, mirroring {@code SlayerPlugin}'s own early-return.
 *
 * <p><b>Account-switch clear (I4).</b> The {@link SlayerTaskState} is bound to the
 * current {@code accountHash}; a change clears the tracked task before the next
 * observation, so account A's task can never suppress or fabricate account B's
 * first row — the same fabrication guard the sibling collectors carry.
 *
 * <p><b>No version-fragile eager load.</b> Ids are plain {@code int} literals (not
 * {@code VarPlayerID}/{@code VarbitID}/{@code DBTableID} constant references) and
 * the DBTable decode runs per-event on the client thread, so nothing is reflected
 * or read at construction — this collector cannot fail the Guice graph the way an
 * eager constant-class load could (contrast {@link AnimationActivityMap}).
 */
@Singleton
public class SlayerCollector
{
	// --- VarPlayer ids (FP-SPIKE-SLAYER id table; SlayerPlugin read-sites cited). ---
	/** {@code VarPlayerID.SLAYER_COUNT} = 394 — remaining count; {@code > 0} gates an active task. */
	static final int VARP_SLAYER_COUNT = 394;
	/** {@code VarPlayerID.SLAYER_TARGET} = 395 — task creature id (cache id, not a name; 98 = boss). */
	static final int VARP_SLAYER_TARGET = 395;
	/** {@code VarPlayerID.SLAYER_AREA} = 2096 — task area id ({@code 0} = no location). */
	static final int VARP_SLAYER_AREA = 2096;
	/** {@code VarPlayerID.SLAYER_COUNT_ORIGINAL} = 4258 — initial assigned count. */
	static final int VARP_SLAYER_COUNT_ORIGINAL = 4258;

	// --- Varbit ids. ---
	/** {@code VarbitID.SLAYER_MASTER} = 4067 — master enum index; only {@code 7} (Krystilia) is cited. */
	static final int VARBIT_SLAYER_MASTER = 4067;
	/** {@code VarbitID.SLAYER_POINTS} = 4068 — account slayer points. */
	static final int VARBIT_SLAYER_POINTS = 4068;
	/** {@code VarbitID.SLAYER_TASKS_COMPLETED} = 4069 — standard task streak. */
	static final int VARBIT_SLAYER_TASKS_COMPLETED = 4069;
	/** {@code VarbitID.SLAYER_TARGET_BOSSID} = 4723 — boss id when the task id is the 98 sentinel. */
	static final int VARBIT_SLAYER_TARGET_BOSSID = 4723;
	/** {@code VarbitID.SLAYER_WILDERNESS_TASKS_COMPLETED} = 5617 — Krystilia (wilderness) streak. */
	static final int VARBIT_SLAYER_WILDERNESS_TASKS_COMPLETED = 5617;

	/** {@code SlayerPlugin.KRYSTILIA_SLAYER_MASTER} = 7 — the only cited master index; scopes the streak varbit. */
	static final int KRYSTILIA_MASTER_INDEX = 7;
	/**
	 * {@code taskId == 98} = the "Bosses" task marker (SlayerPlugin L365, from cache
	 * clientscript {@code [proc,helper_slayer_current_assignment]}). A magic number by
	 * necessity — pinned behind a name so a renumber is a one-line fix, not a silent misroute.
	 */
	static final int TASK_ID_BOSS = 98;

	// --- DBTable ids (FP-SPIKE-SLAYER decode-table; DBTableID declarations cited). ---
	static final int DB_SLAYER_TASK = 113;
	static final int DB_SLAYER_TASK_COL_ID = 0;
	static final int DB_SLAYER_TASK_COL_NAME_UPPERCASE = 10;
	static final int DB_SLAYER_AREA = 115;
	static final int DB_SLAYER_AREA_COL_AREA_ID = 0;
	static final int DB_SLAYER_AREA_COL_NAME = 3;
	static final int DB_SLAYER_TASK_SUBLIST = 116;
	static final int DB_SUBLIST_COL_SUBTABLE_ID = 1;
	static final int DB_SUBLIST_COL_TASK = 4;

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final SlayerTaskState state = new SlayerTaskState();

	@Inject
	public SlayerCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.enabled() || !config.trackSlayerTasks())
		{
			return;
		}
		if (!isSlayerTrigger(event))
		{
			return;
		}
		recompute();
	}

	/**
	 * Match the exact trigger set {@code SlayerPlugin} recomputes on. A varp change
	 * reports {@code varbitId == -1} (branch on the varp id); a varbit change reports
	 * its varbit id (branch on that) — so the varp branch cannot be tripped by an
	 * unrelated varbit that happens to be backed by the same varp.
	 */
	private static boolean isSlayerTrigger(VarbitChanged event)
	{
		if (event.getVarbitId() == -1)
		{
			int varp = event.getVarpId();
			return varp == VARP_SLAYER_COUNT || varp == VARP_SLAYER_TARGET
				|| varp == VARP_SLAYER_AREA || varp == VARP_SLAYER_COUNT_ORIGINAL;
		}
		int varbit = event.getVarbitId();
		return varbit == VARBIT_SLAYER_TARGET_BOSSID || varbit == VARBIT_SLAYER_POINTS
			|| varbit == VARBIT_SLAYER_MASTER || varbit == VARBIT_SLAYER_TASKS_COMPLETED
			|| varbit == VARBIT_SLAYER_WILDERNESS_TASKS_COMPLETED;
	}

	/**
	 * Read the full current slayer state on the client thread and hand it to the pure
	 * {@link SlayerTaskState}, which decides whether this observation is an assignment,
	 * a completion, or a no-op re-fire. Runs directly in the {@code VarbitChanged}
	 * handler, which RuneLite dispatches on the client thread — the DBTable API and
	 * varp reads require that thread.
	 */
	private void recompute()
	{
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			// Not attributable yet; leave the tracked task untouched so a later fire still emits.
			return;
		}
		long accountHash = client.getAccountHash();
		Integer streak = readStreak();
		Integer points = client.getVarbitValue(VARBIT_SLAYER_POINTS);
		int amount = client.getVarpValue(VARP_SLAYER_COUNT);

		if (amount <= 0)
		{
			// SLAYER_COUNT == 0 => no active task. If we were tracking one, this is its completion.
			emit(rsn, state.observeCleared(accountHash, streak, points));
			return;
		}

		int taskId = client.getVarpValue(VARP_SLAYER_TARGET);
		String creature = decodeCreature(taskId);
		if (creature == null)
		{
			// Cache not loaded / undecodable task id: half-decoded state emits nothing (witnessed-only).
			return;
		}
		int areaId = client.getVarpValue(VARP_SLAYER_AREA);
		String location = null;
		if (areaId > 0)
		{
			location = decodeArea(areaId);
			if (location == null)
			{
				// An area is set but its name is not decodable yet: drop the whole update, exactly
				// like SlayerPlugin's early-return, rather than ship a task with a half-known location.
				return;
			}
		}
		emit(rsn, state.observeActive(accountHash, creature, amount, location, streak, points));
	}

	/** Streak is master-scoped: Krystilia (index 7) reports the wilderness streak varbit. */
	private Integer readStreak()
	{
		int master = client.getVarbitValue(VARBIT_SLAYER_MASTER);
		int streakVarbit = master == KRYSTILIA_MASTER_INDEX
			? VARBIT_SLAYER_WILDERNESS_TASKS_COMPLETED
			: VARBIT_SLAYER_TASKS_COMPLETED;
		return client.getVarbitValue(streakVarbit);
	}

	/**
	 * Decode the numeric task id to a monster name via the game DBTable API,
	 * transcribed from {@code SlayerPlugin.updateTask()} (L365-387). A {@code 98}
	 * task id is the "Bosses" sentinel: read {@code SLAYER_TARGET_BOSSID}, resolve it
	 * through {@code SlayerTaskSublist} to a {@code SlayerTask} row, then read the
	 * uppercase name. Returns {@code null} on any empty lookup (cache not loaded) so
	 * the caller drops the update rather than shipping a raw id.
	 */
	private String decodeCreature(int taskId)
	{
		int taskDBRow;
		if (taskId == TASK_ID_BOSS)
		{
			List<Integer> bossRows = client.getDBRowsByValue(
				DB_SLAYER_TASK_SUBLIST, DB_SUBLIST_COL_SUBTABLE_ID, 0,
				client.getVarbitValue(VARBIT_SLAYER_TARGET_BOSSID));
			if (bossRows.isEmpty())
			{
				return null;
			}
			Object[] taskField = client.getDBTableField(bossRows.get(0), DB_SUBLIST_COL_TASK, 0);
			if (taskField == null || taskField.length == 0)
			{
				return null;
			}
			taskDBRow = (Integer) taskField[0];
		}
		else
		{
			List<Integer> taskRows = client.getDBRowsByValue(DB_SLAYER_TASK, DB_SLAYER_TASK_COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return null;
			}
			taskDBRow = taskRows.get(0);
		}
		Object[] nameField = client.getDBTableField(taskDBRow, DB_SLAYER_TASK_COL_NAME_UPPERCASE, 0);
		if (nameField == null || nameField.length == 0)
		{
			return null;
		}
		return (String) nameField[0];
	}

	/**
	 * Decode a positive area id to a location name via {@code SlayerArea}
	 * (SlayerPlugin L392-401). Returns {@code null} when the row is not loaded so the
	 * caller drops the update; the caller never asks for {@code areaId <= 0} (no
	 * location qualifier).
	 */
	private String decodeArea(int areaId)
	{
		List<Integer> areaRows = client.getDBRowsByValue(DB_SLAYER_AREA, DB_SLAYER_AREA_COL_AREA_ID, 0, areaId);
		if (areaRows.isEmpty())
		{
			return null;
		}
		Object[] nameField = client.getDBTableField(areaRows.get(0), DB_SLAYER_AREA_COL_NAME, 0);
		if (nameField == null || nameField.length == 0)
		{
			return null;
		}
		return (String) nameField[0];
	}

	private void emit(String rsn, Transition transition)
	{
		if (transition == null)
		{
			return;
		}
		SlayerTaskUpdate payload = new SlayerTaskUpdate();
		Payloads.base(payload, client, rsn);
		payload.creature = transition.creature;
		payload.amount = transition.amount;
		payload.location = transition.location;
		payload.streak = transition.streak;
		payload.points = transition.points;
		payload.transition = transition.type;
		analytics.enqueue(EventCategory.SLAYER_TASK, payload);
	}

	/** One decided slayer transition to emit; carrier between {@link SlayerTaskState} and {@link #emit}. */
	static final class Transition
	{
		final SlayerTaskUpdate.SlayerTransition type;
		final String creature;
		final Integer amount;
		final String location;
		final Integer streak;
		final Integer points;

		Transition(SlayerTaskUpdate.SlayerTransition type, String creature, Integer amount,
			String location, Integer streak, Integer points)
		{
			this.type = type;
			this.creature = creature;
			this.amount = amount;
			this.location = location;
			this.streak = streak;
			this.points = points;
		}
	}

	/**
	 * Pure, client-free slayer transition state machine. Given a stream of decoded
	 * task observations it decides which are the two witnessed edges — assignment and
	 * completion — and suppresses the redundant per-kill re-observations in between.
	 * Client-free so both honesty guarantees (transitions-only dedup and account-switch
	 * clear) are testable with plain data, exactly like {@link AccountKeyedDeltaGuard}.
	 *
	 * <p>Not thread-safe: called only from the client thread.
	 */
	static final class SlayerTaskState
	{
		private long account = AccountKeyedDeltaGuard.NO_ACCOUNT;
		private boolean haveTask;
		private String creature;
		private String location;

		/**
		 * Observe an active, fully-decoded task ({@code amount > 0}). Emits an
		 * {@code assigned} transition when the task identity (creature + location)
		 * differs from the tracked task — including the first task witnessed for an
		 * account — and {@code null} for an unchanged re-observation (e.g. a bare
		 * {@code SLAYER_COUNT} decrement), which must not produce a row.
		 */
		Transition observeActive(long accountHash, String creature, Integer amount,
			String location, Integer streak, Integer points)
		{
			clearIfAccountChanged(accountHash);
			boolean newTask = !haveTask
				|| !Objects.equals(this.creature, creature)
				|| !Objects.equals(this.location, location);
			this.haveTask = true;
			this.creature = creature;
			this.location = location;
			if (!newTask)
			{
				return null;
			}
			return new Transition(SlayerTaskUpdate.SlayerTransition.ASSIGNED,
				creature, amount, location, streak, points);
		}

		/**
		 * Observe {@code SLAYER_COUNT <= 0} (no active task). Emits a {@code completed}
		 * transition for the previously-tracked task — using its stored creature/location,
		 * because at {@code amount == 0} a fresh decode reads a stale target — and clears
		 * the tracked task. Emits {@code null} when no task was being tracked, so an
		 * idle "0 tasks" state never fabricates a completion.
		 */
		Transition observeCleared(long accountHash, Integer streak, Integer points)
		{
			clearIfAccountChanged(accountHash);
			if (!haveTask)
			{
				return null;
			}
			Transition completion = new Transition(SlayerTaskUpdate.SlayerTransition.COMPLETED,
				creature, 0, location, streak, points);
			reset();
			return completion;
		}

		private void clearIfAccountChanged(long accountHash)
		{
			if (accountHash != account)
			{
				account = accountHash;
				reset();
			}
		}

		private void reset()
		{
			haveTask = false;
			creature = null;
			location = null;
		}
	}
}
