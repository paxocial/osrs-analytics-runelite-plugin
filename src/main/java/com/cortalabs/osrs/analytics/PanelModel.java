/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import net.runelite.api.vars.AccountType;

/**
 * The immutable, render-ready view of everything the Catherby panel shows, and the
 * one place the honesty rules are enforced. The Swing panel renders a {@code PanelModel}
 * and nothing else; {@link #of} is the pure gate that turns live transport + tracked
 * client state into display truth.
 *
 * <p>Honesty is structural here, not a rendering afterthought:
 * <ul>
 *   <li>With no account witnessed ({@link AccountPresence#NONE}) the name and type are
 *       {@code null} — the panel says "No account witnessed yet.", not a blank or a
 *       fabricated identity.</li>
 *   <li>{@code sessionKnown} is false until an account has been seen; the session
 *       figures are shown only once they mean something, so a fresh panel never claims
 *       "0 XP gained" before it has watched anything.</li>
 *   <li>Progression defaults to {@link ProgressionReading#UNKNOWN} — absent, never a
 *       zero we never read.</li>
 * </ul>
 */
final class PanelModel
{
	// Connection.
	final PanelText.Tone connectionTone;
	final String connectionLine;
	final String backendUrl;
	final int queueDepth;
	final long lastAcceptedMs;

	// Account.
	final AccountPresence presence;
	final String rsn;
	final String accountType;

	// This session.
	final boolean sessionKnown;
	final long xpGained;
	final int skillsAdvanced;
	final long snapshotsSent;

	// Progression at a glance.
	final ProgressionReading progression;

	// Plumbing.
	final String version;

	private PanelModel(PanelText.Tone connectionTone, String connectionLine, String backendUrl,
		int queueDepth, long lastAcceptedMs, AccountPresence presence, String rsn, String accountType,
		boolean sessionKnown, long xpGained, int skillsAdvanced, long snapshotsSent,
		ProgressionReading progression, String version)
	{
		this.connectionTone = connectionTone;
		this.connectionLine = connectionLine;
		this.backendUrl = backendUrl;
		this.queueDepth = queueDepth;
		this.lastAcceptedMs = lastAcceptedMs;
		this.presence = presence;
		this.rsn = rsn;
		this.accountType = accountType;
		this.sessionKnown = sessionKnown;
		this.xpGained = xpGained;
		this.skillsAdvanced = skillsAdvanced;
		this.snapshotsSent = snapshotsSent;
		this.progression = progression;
		this.version = version;
	}

	/**
	 * Combine the live transport state with the tracked client state into a render-ready
	 * model, applying the honesty rules once, here. Takes the transport fields as plain
	 * values (the panel unpacks them from {@link AnalyticsClient.Snapshot}) so the whole
	 * derivation is pure and directly testable.
	 *
	 * @param state         the transport's connection state
	 * @param enabled       whether telemetry is switched on
	 * @param queueDepth    events waiting to send
	 * @param lastAcceptedMs wall-clock of the last accepted batch, or {@code 0}
	 * @param snapshotsSent accepted events this session
	 * @param backendUrl    the backend base URL (never the key), or {@code null}
	 * @param presence      whether an account is live, away, or never seen
	 * @param rsn           the witnessed display name (ignored when {@code NONE})
	 * @param accountType   the witnessed account type (labelled here; ignored when {@code NONE})
	 * @param xpGained      session XP witnessed climbing
	 * @param skillsAdvanced distinct skills witnessed climbing
	 * @param progression   the last progression reading, or {@code null} for none
	 * @param version       the honest plugin version string
	 */
	static PanelModel of(AnalyticsClient.State state, boolean enabled, int queueDepth, long lastAcceptedMs,
		long snapshotsSent, String backendUrl, AccountPresence presence, String rsn, AccountType accountType,
		long xpGained, int skillsAdvanced, ProgressionReading progression, String version)
	{
		PanelText.ConnectionView conn = PanelText.connection(state, enabled);
		boolean known = presence != AccountPresence.NONE;
		return new PanelModel(
			conn.tone,
			conn.line,
			backendUrl == null ? "" : backendUrl,
			queueDepth,
			lastAcceptedMs,
			presence,
			known ? rsn : null,
			known ? PanelText.accountType(accountType) : null,
			known,
			xpGained,
			skillsAdvanced,
			snapshotsSent,
			progression == null ? ProgressionReading.UNKNOWN : progression,
			version);
	}
}
