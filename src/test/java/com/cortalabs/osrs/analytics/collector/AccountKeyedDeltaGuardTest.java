/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the two load-bearing behaviours of {@link AccountKeyedDeltaGuard}: the
 * delta comparison (unchanged state is suppressed) and the account-switch clear
 * (a new account is never suppressed by the previous account's cached state).
 * Both are mutation-prove targets — see the corresponding tests' notes.
 */
public class AccountKeyedDeltaGuardTest
{
	private static final long ACCT_A = 100L;
	private static final long ACCT_B = 200L;

	@Test
	public void firstObservationEmits()
	{
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue("a key never seen for this account must emit", guard.changed(ACCT_A, "k", "s1"));
	}

	/**
	 * MUTATION-PROVE #1 (the delta comparison). Break {@code previous.equals(signature)}
	 * in AccountKeyedDeltaGuard so it never suppresses, and this assertion flips to
	 * true -> the test fails, proving the comparison is what removes the redundancy.
	 */
	@Test
	public void identicalRepeatIsSuppressed()
	{
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "k", "s1"));
		assertFalse("re-reporting the identical signature must be suppressed", guard.changed(ACCT_A, "k", "s1"));
		assertFalse("still suppressed on a third identical report", guard.changed(ACCT_A, "k", "s1"));
	}

	@Test
	public void changedSignatureEmits()
	{
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "k", "s1"));
		assertTrue("a changed signature must emit", guard.changed(ACCT_A, "k", "s2"));
		assertFalse("and then the new signature is the baseline", guard.changed(ACCT_A, "k", "s2"));
	}

	@Test
	public void distinctKeysAreIndependent()
	{
		// The diary/quest shape: many keys tracked under one account.
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "Ardougne", "1|1|1|0"));
		assertTrue("a different key with the same signature still emits", guard.changed(ACCT_A, "Varrock", "1|1|1|0"));
		assertFalse(guard.changed(ACCT_A, "Ardougne", "1|1|1|0"));
		assertTrue("one key changing does not affect another", guard.changed(ACCT_A, "Ardougne", "1|1|1|1"));
		assertFalse(guard.changed(ACCT_A, "Varrock", "1|1|1|0"));
	}

	/**
	 * MUTATION-PROVE #2 (the account-switch clear). Remove the {@code lastSignature.clear()}
	 * on account change in AccountKeyedDeltaGuard, and the second assertion flips to
	 * false -> the test fails: account B's genuinely-first observation would be
	 * suppressed by account A's identical cached signature, fabricating absence for B.
	 */
	@Test
	public void accountSwitchReEmitsEvenWhenSignatureMatches()
	{
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "k", "same"));
		assertFalse("A repeat is suppressed", guard.changed(ACCT_A, "k", "same"));
		assertTrue("switching to B must emit B's first observation, even with an identical signature",
			guard.changed(ACCT_B, "k", "same"));
		assertFalse("then B's own repeat is suppressed", guard.changed(ACCT_B, "k", "same"));
	}

	@Test
	public void switchingBackToPriorAccountReEmits()
	{
		// The clear is total, so a prior account's baseline does not survive a round
		// trip: coming back to A after B re-establishes A's baseline from scratch.
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "k", "same"));
		assertTrue(guard.changed(ACCT_B, "k", "same"));
		assertTrue("returning to A re-emits because switching to B cleared A's cache",
			guard.changed(ACCT_A, "k", "same"));
	}

	@Test
	public void resetForgetsEverything()
	{
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(ACCT_A, "k", "s1"));
		assertFalse(guard.changed(ACCT_A, "k", "s1"));
		guard.reset();
		assertTrue("after reset, the same signature is a first observation again", guard.changed(ACCT_A, "k", "s1"));
	}

	@Test
	public void unknownAccountSentinelIsJustAnotherAccount()
	{
		// Callers decide whether to consult the guard under NO_ACCOUNT; when they do,
		// the sentinel behaves like any other account key (no special-casing here).
		AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();
		assertTrue(guard.changed(AccountKeyedDeltaGuard.NO_ACCOUNT, "k", "s1"));
		assertFalse(guard.changed(AccountKeyedDeltaGuard.NO_ACCOUNT, "k", "s1"));
		assertTrue("moving from the sentinel to a real account clears and re-emits",
			guard.changed(ACCT_A, "k", "s1"));
	}
}
