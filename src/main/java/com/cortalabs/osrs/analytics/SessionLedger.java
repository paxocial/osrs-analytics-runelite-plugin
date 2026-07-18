/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks XP gained since the current session's ledger opened, per skill.
 *
 * <p>Pure and client-free: {@link com.cortalabs.osrs.analytics.collector.PanelStateTracker}
 * feeds it {@code (skill, currentXp)} observations and it reports the honest delta.
 * The <b>first</b> observation of a skill sets that skill's baseline — a gain of
 * zero. It is never counted as gained XP, because on that first sight we are only
 * witnessing where the skill already stood, not watching it move; counting it as
 * gain would fabricate a session's worth of XP the moment the panel opened.
 * Subsequent higher observations accrue as real gain.
 *
 * <p>A <b>lower</b> observation for a skill (an account switch, or a client de-sync)
 * re-baselines that skill downward rather than reporting negative "gain": the ledger
 * only ever reports XP it actually witnessed climb.
 *
 * <p>Thread model: mutated on the client thread ({@code onStatChanged}) under
 * {@code this}; the two summary figures are {@code volatile} so the panel's refresh
 * reads a published, self-consistent value on the EDT without locking.
 */
public final class SessionLedger
{
	private final Map<String, Long> baseline = new HashMap<>();
	private final Map<String, Long> current = new HashMap<>();

	private volatile long xpGained;
	private volatile int skillsAdvanced;

	/** Record one skill's current total XP. See the class note for the baseline rule. */
	public synchronized void observe(String skill, long xp)
	{
		if (skill == null)
		{
			return;
		}
		Long base = baseline.get(skill);
		if (base == null || xp < base)
		{
			// First sight of this skill this session, or a downward reading: this is a
			// new baseline, not a gain. Never report negative movement as progress.
			baseline.put(skill, xp);
		}
		current.put(skill, xp);
		recompute();
	}

	/** Forget the session so far. Called when the ledger opens on a new account. */
	public synchronized void reset()
	{
		baseline.clear();
		current.clear();
		xpGained = 0L;
		skillsAdvanced = 0;
	}

	/** Total XP witnessed climbing since the ledger opened. */
	public long xpGained()
	{
		return xpGained;
	}

	/** How many distinct skills have been witnessed climbing since the ledger opened. */
	public int skillsAdvanced()
	{
		return skillsAdvanced;
	}

	private void recompute()
	{
		long total = 0L;
		int advanced = 0;
		for (Map.Entry<String, Long> entry : current.entrySet())
		{
			long base = baseline.getOrDefault(entry.getKey(), entry.getValue());
			long gain = entry.getValue() - base;
			if (gain > 0L)
			{
				total += gain;
				advanced++;
			}
		}
		xpGained = total;
		skillsAdvanced = advanced;
	}
}
