/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.HashMap;
import java.util.Map;

/**
 * Reusable change-detection guard for account-scoped telemetry collectors.
 *
 * <p>Collectors call {@link #changed(long, String, String)} with the logged-in
 * account's {@link net.runelite.api.Client#getAccountHash() accountHash}, a
 * stable {@code key} identifying what is being tracked (a diary region name, a
 * quest name, or a single constant for whole-snapshot collectors), and a
 * {@code signature} string that captures the current state of that key. The
 * guard returns {@code true} exactly when the caller should emit — i.e. this is
 * the first time this key has been seen for the current account, or the state
 * changed since the last emit — and {@code false} when the state is byte-for-byte
 * identical to the last accepted one (a redundant re-report the caller should
 * suppress).
 *
 * <p><b>Two central guards, both mutation-proved in the test:</b>
 * <ul>
 *   <li><b>The delta comparison</b> ({@code prev.equals(signature)}) is what
 *       actually suppresses an unchanged re-report. Break it and unchanged states
 *       re-emit — the redundancy this component exists to remove returns.</li>
 *   <li><b>The account-switch clear.</b> A cached signature belongs to exactly one
 *       account. When the {@code accountHash} changes, every prior signature is
 *       forgotten <i>before</i> the comparison, so account B's genuinely-first
 *       observation of a key can never be suppressed by an identical-looking
 *       cached signature from account A. Skip the clear and a client-side cache
 *       that survives an account swap fabricates absence — it silently drops a
 *       real observation for the new account. This mirrors the house pattern in
 *       {@link CollectionLogCollector#clearIfAccountChanged} and
 *       {@link NameChangeCollector} (both keyed by the rename-immune accountHash).</li>
 * </ul>
 *
 * <p>This is an <b>in-memory, per-session</b> guard: it holds no disk state, so it
 * does not change any collector's cross-session (per-restart) emission behaviour,
 * and therefore does not touch the server-side {@code observed_at} freshness
 * contract. It exists so every collector can share one tested change-detection
 * primitive instead of re-implementing a {@code lastSignature} map inline. A
 * future delivery-acknowledged persistence backend can wrap this same comparison
 * without changing its contract.
 *
 * <p>Not thread-safe: collectors call it only from the client thread.
 */
final class AccountKeyedDeltaGuard
{
	/** Matches {@link net.runelite.api.Client#getAccountHash()} when no account is loaded. */
	static final long NO_ACCOUNT = -1L;

	private final Map<String, String> lastSignature = new HashMap<>();
	private long account = NO_ACCOUNT;

	/**
	 * Decide whether {@code (accountHash, key)} should be emitted, and advance the
	 * stored baseline when it should.
	 *
	 * @param accountHash the current account's stable hash; a value different from
	 *                    the last call clears all cached signatures first
	 * @param key         stable identifier for the tracked entity (region/quest
	 *                    name, or a constant for whole-snapshot collectors)
	 * @param signature   a string fully capturing the current state of {@code key}
	 * @return {@code true} if this is a first observation for the current account or
	 *         the signature changed (and the baseline was advanced); {@code false}
	 *         if it is identical to the last accepted signature for this key
	 */
	boolean changed(long accountHash, String key, String signature)
	{
		if (accountHash != account)
		{
			// The account changed: nothing cached says anything about the new
			// account, so forget it all before comparing. Without this, an identical
			// signature carried over from the previous account would suppress the new
			// account's real first observation.
			account = accountHash;
			lastSignature.clear();
		}
		String previous = lastSignature.get(key);
		if (previous != null && previous.equals(signature))
		{
			return false;
		}
		lastSignature.put(key, signature);
		return true;
	}

	/** Forget all cached signatures and the bound account (e.g. on shutdown). */
	void reset()
	{
		lastSignature.clear();
		account = NO_ACCOUNT;
	}
}
