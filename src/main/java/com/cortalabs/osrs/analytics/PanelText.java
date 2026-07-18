/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.runelite.api.vars.AccountType;

/**
 * Pure text and formatting for the Catherby panel — the steward's voice and the
 * honest clock, kept client-free so every string it produces is directly testable.
 *
 * <p>The steward register (design contract §24/§98): a fond, dry ledger-keeper.
 * Short sentences, contractions, no exclamations, no emoji, never a placeholder
 * system string. The panel narrates; it does not report status codes.
 */
final class PanelText
{
	private PanelText()
	{
	}

	/** Semantic weight of a connection state, mapped to a colour by the panel. */
	enum Tone
	{
		OK, WARN, ERROR, IDLE, OFF
	}

	/** A connection state's steward line and its tone. */
	static final class ConnectionView
	{
		final Tone tone;
		final String line;

		ConnectionView(Tone tone, String line)
		{
			this.tone = tone;
			this.line = line;
		}
	}

	/** Local wall-clock format, e.g. {@code 7:05 am}. */
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

	/**
	 * The steward's reading of the transport state. Every line is calm: an offline
	 * backend is a fact to state plainly, not an error to shout. When telemetry is
	 * switched off the panel says so rather than pretending to be idle.
	 */
	static ConnectionView connection(AnalyticsClient.State state, boolean enabled)
	{
		if (!enabled)
		{
			return new ConnectionView(Tone.OFF, "Telemetry is off.");
		}
		switch (state)
		{
			case OK:
				return new ConnectionView(Tone.OK, "The ledger is listening.");
			case ERROR:
				return new ConnectionView(Tone.ERROR, "Can't reach the ledger — retrying.");
			case RATE_LIMITED:
				return new ConnectionView(Tone.WARN, "Easing off for a moment.");
			case AUTH_FAILED:
				return new ConnectionView(Tone.ERROR, "The key was refused.");
			case UNREGISTERED:
				return new ConnectionView(Tone.WARN, "This name isn't on the books yet.");
			case IDLE:
			default:
				return new ConnectionView(Tone.IDLE, "Nothing sent yet.");
		}
	}

	/**
	 * A relative age like {@code 12s ago} / {@code 5m ago} / {@code 2h ago}. Returns
	 * {@code never} when the moment has not happened ({@code epochMs <= 0}); the caller
	 * never passes a fabricated timestamp to make this read fresher than the truth.
	 */
	static String since(long epochMs, long nowMs)
	{
		if (epochMs <= 0L)
		{
			return "never";
		}
		long deltaS = Math.max(0L, (nowMs - epochMs) / 1000L);
		if (deltaS < 5L)
		{
			return "just now";
		}
		if (deltaS < 60L)
		{
			return deltaS + "s ago";
		}
		if (deltaS < 3600L)
		{
			return (deltaS / 60L) + "m ago";
		}
		if (deltaS < 86_400L)
		{
			return (deltaS / 3600L) + "h ago";
		}
		return (deltaS / 86_400L) + "d ago";
	}

	/**
	 * The operator's own wall clock for a moment, e.g. {@code 7:05 am}. Rendered in the
	 * supplied zone and <b>never</b> labelled UTC: the panel shows local time because
	 * that is the clock the player reads, and a UTC-labelled local time is the exact
	 * mislabel this product shipped once. Returns {@code ""} for a moment that has not
	 * happened.
	 */
	static String clock(long epochMs, ZoneId zone)
	{
		if (epochMs <= 0L)
		{
			return "";
		}
		return CLOCK.format(Instant.ofEpochMilli(epochMs).atZone(zone)).toLowerCase(Locale.ENGLISH);
	}

	/** Group-separated integer, e.g. {@code 12,345}. */
	static String integer(long value)
	{
		return String.format(Locale.ENGLISH, "%,d", value);
	}

	/**
	 * A display label for the account type shown beside the name. {@code null} account
	 * type (not yet known) yields {@code null}, which the panel omits.
	 */
	static String accountType(AccountType type)
	{
		if (type == null)
		{
			return null;
		}
		switch (type)
		{
			case IRONMAN:
				return "Ironman";
			case HARDCORE_IRONMAN:
				return "Hardcore Ironman";
			case ULTIMATE_IRONMAN:
				return "Ultimate Ironman";
			case GROUP_IRONMAN:
				return "Group Ironman";
			case HARDCORE_GROUP_IRONMAN:
				return "Hardcore Group Ironman";
			case NORMAL:
			default:
				return "Main";
		}
	}
}
