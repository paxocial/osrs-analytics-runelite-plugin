/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AccountPresence;
import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.ProgressionReading;
import com.cortalabs.osrs.analytics.SessionLedger;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.vars.AccountType;
import net.runelite.client.eventbus.Subscribe;

/**
 * Witnesses, on the client thread, everything the Catherby panel needs to speak the
 * truth: who is logged in, what their at-a-glance progression is, and how much XP the
 * session has earned. It writes a published, immutable {@link ClientState} plus a
 * {@link SessionLedger}; the panel reads those on the EDT and never touches the game
 * client itself.
 *
 * <p><b>Event-driven, never a Swing poll.</b> Session XP accrues from {@code StatChanged}
 * (a cheap map write, no render). Identity and progression are recomputed from live
 * client state on a throttled {@code GameTick} — the same client-thread cadence every
 * collector uses — and only when the reading actually changes does the tracker notify
 * the panel to re-render. Logout is caught on {@code GameStateChanged}. All game reads
 * happen here on the client thread; the panel consumes the results.
 *
 * <p>Everything shown is witnessed or honestly absent: progression starts
 * {@link ProgressionReading#UNKNOWN} (not a reading of zero), and the session ledger
 * only ever reports XP it actually saw climb.
 */
@Singleton
public class PanelStateTracker
{
	/** Throttle for the identity + progression recompute; matches collector cadence. */
	private static final long RECOMPUTE_INTERVAL_MS = 2_000L;

	private final Client client;
	private final AnalyticsConfig config;
	private final SessionLedger ledger = new SessionLedger();

	private volatile ClientState clientState = ClientState.NONE;
	private volatile Runnable changeListener = () -> { };

	/** The account the current session ledger belongs to; guards against cross-account mixing. */
	private String sessionRsn;
	private long lastRecomputeMs;

	@Inject
	public PanelStateTracker(Client client, AnalyticsConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/**
	 * Register the panel's re-render hook. Invoked (off the EDT) when a witnessed change
	 * lands; the panel wraps it in {@code SwingUtilities.invokeLater}.
	 */
	public void setChangeListener(Runnable listener)
	{
		this.changeListener = listener == null ? () -> { } : listener;
	}

	/** Wipe session state for a clean boundary when the plugin starts. */
	public void reset()
	{
		ledger.reset();
		sessionRsn = null;
		lastRecomputeMs = 0L;
		clientState = ClientState.NONE;
	}

	/** The last published identity + progression reading. One volatile read, self-consistent. */
	public ClientState clientState()
	{
		return clientState;
	}

	/** Session XP witnessed climbing (read on the EDT). */
	public long xpGained()
	{
		return ledger.xpGained();
	}

	/** Distinct skills witnessed climbing this session (read on the EDT). */
	public int skillsAdvanced()
	{
		return ledger.skillsAdvanced();
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Only accrue while an account is live; a cheap map write, deliberately no render
		// push — the panel's one-second clock reflects session XP within its next tick,
		// so high-rate skilling never floods the EDT.
		if (clientState.presence != AccountPresence.LIVE)
		{
			return;
		}
		Skill skill = event.getSkill();
		if (skill == null)
		{
			return;
		}
		ledger.observe(skill.getName(), event.getXp());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.CONNECTION_LOST)
		{
			ClientState current = clientState;
			if (current.presence == AccountPresence.LIVE)
			{
				// Keep what we witnessed, mark it no longer live, and tell the panel now.
				clientState = current.toAway();
				changeListener.run();
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		long now = System.currentTimeMillis();
		if (now - lastRecomputeMs < RECOMPUTE_INTERVAL_MS)
		{
			return;
		}
		lastRecomputeMs = now;
		recompute();
	}

	private void recompute()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		if (!rsn.equals(sessionRsn))
		{
			// A new account opened its ledger: the previous account's session XP is not
			// this account's, so start it fresh rather than mixing two players' gains.
			sessionRsn = rsn;
			ledger.reset();
		}

		ClientState next = new ClientState(AccountPresence.LIVE, rsn, client.getAccountType(), readProgression());
		if (!next.equals(clientState))
		{
			clientState = next;
			changeListener.run();
		}
	}

	/**
	 * Read the account's at-a-glance progression from live client state: quest points
	 * ({@code VarPlayerID.QP}), true quests complete (reusing {@link QuestCollector}'s
	 * classification so miniquests and subquests are not counted as quests), and diary
	 * tiers done (reusing {@link DiaryCollector}'s source-verified varbit map and
	 * completion thresholds). One reading, all witnessed — never a defaulted number.
	 */
	private ProgressionReading readProgression()
	{
		int questPoints = Math.max(0, client.getVarpValue(VarPlayerID.QP));

		int questsComplete = 0;
		for (Quest quest : Quest.values())
		{
			if (!"quest".equals(QuestCollector.classify(quest.getName())))
			{
				continue;
			}
			if (quest.getState(client) == QuestState.FINISHED)
			{
				questsComplete++;
			}
		}

		int diaryTiersComplete = 0;
		int diaryTiersTotal = 0;
		for (int[] tiers : DiaryCollector.regionTierVarbits().values())
		{
			for (int varbitId : tiers)
			{
				diaryTiersTotal++;
				if (client.getVarbitValue(varbitId) >= DiaryCollector.completionThreshold(varbitId))
				{
					diaryTiersComplete++;
				}
			}
		}

		return ProgressionReading.of(questPoints, questsComplete, diaryTiersComplete, diaryTiersTotal);
	}

	/**
	 * Immutable, published view of the witnessed account: who, what type, what
	 * progression. Read atomically by the panel via a single volatile field so identity
	 * and progression never tear apart.
	 */
	public static final class ClientState
	{
		/** No account witnessed yet. */
		public static final ClientState NONE =
			new ClientState(AccountPresence.NONE, null, null, ProgressionReading.UNKNOWN);

		public final AccountPresence presence;
		public final String rsn;
		public final AccountType accountType;
		public final ProgressionReading progression;

		ClientState(AccountPresence presence, String rsn, AccountType accountType, ProgressionReading progression)
		{
			this.presence = presence;
			this.rsn = rsn;
			this.accountType = accountType;
			this.progression = progression;
		}

		/** The same witnessed account, marked logged out. */
		ClientState toAway()
		{
			return new ClientState(AccountPresence.AWAY, rsn, accountType, progression);
		}

		@Override
		public boolean equals(Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof ClientState))
			{
				return false;
			}
			ClientState other = (ClientState) o;
			return presence == other.presence
				&& Objects.equals(rsn, other.rsn)
				&& accountType == other.accountType
				&& Objects.equals(progression, other.progression);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(presence, rsn, accountType, progression);
		}
	}
}
