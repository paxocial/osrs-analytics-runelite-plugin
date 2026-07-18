/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics;

/**
 * Whether the panel currently has an account to speak for. The shared vocabulary
 * between the client-thread tracker that witnesses the account and the panel that
 * renders it.
 *
 * <ul>
 *   <li>{@link #NONE} — no account has been witnessed this session. The panel says so
 *       plainly; it never invents a name or a zeroed reading to fill the space.</li>
 *   <li>{@link #LIVE} — an account is logged in and being watched right now.</li>
 *   <li>{@link #AWAY} — an account was watched this session but has since logged out.
 *       The last thing witnessed still stands, marked as no longer live.</li>
 * </ul>
 */
public enum AccountPresence
{
	NONE, LIVE, AWAY
}
