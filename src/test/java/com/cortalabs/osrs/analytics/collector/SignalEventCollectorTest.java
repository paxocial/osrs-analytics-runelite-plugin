/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.collector.SignalEventCollector.ParsedSignal;
import com.cortalabs.osrs.analytics.collector.SignalEventCollector.SignalSessionState;
import com.cortalabs.osrs.analytics.dto.SignalEvent.SignalType;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards {@link SignalEventCollector#parseSignal} (the witnessed-only chat taxonomy,
 * I2 — a line that only half-matches emits nothing, never a fabricated signal) and
 * {@link SignalSessionState} (same-tick re-render dedup, the per-session budget cap,
 * and the account-switch clear, I3/I4). All with plain strings, no live client.
 */
public class SignalEventCollectorTest
{
	private static ParsedSignal parse(String message)
	{
		return SignalEventCollector.parseSignal(message);
	}

	// --- Witnessed-only taxonomy: each pattern classifies its line, and only its line ---

	@Test
	public void levelUpParsesSkillAndTheNewLevelWhenTheMessageCarriesIt()
	{
		ParsedSignal p = parse("Congratulations, you just advanced your Cooking level. You are now level 43.");
		assertEquals(SignalType.LEVEL_UP, p.signalType);
		assertEquals("Cooking", p.subject);
		assertEquals(Integer.valueOf(43), p.value);
	}

	@Test
	public void levelUpValueIsAbsentWhenTheLineHasNoLevelNumber()
	{
		// The split-message form carries only the skill; the level is honestly absent, not guessed.
		ParsedSignal p = parse("Congratulations, you just advanced your Attack level.");
		assertEquals(SignalType.LEVEL_UP, p.signalType);
		assertEquals("Attack", p.subject);
		assertNull("no level in the line => absent value, never a default", p.value);
	}

	@Test
	public void petLinesParseWithNoSubjectOrValue()
	{
		ParsedSignal p = parse("You have a funny feeling like you're being followed.");
		assertEquals(SignalType.PET, p.signalType);
		assertNull(p.subject);
		assertNull(p.value);
		assertEquals(SignalType.PET, parse("You feel something weird sneaking into your backpack.").signalType);
	}

	@Test
	public void petMissLineEmitsNothing()
	{
		// "...would have been followed" means the pet rolled but was NOT received (full inventory).
		// Signalling a pet here would fabricate an acquisition that never happened.
		assertNull("a rolled-but-lost pet must not signal an acquisition",
			parse("You have a funny feeling like you would have been followed."));
	}

	@Test
	public void clueCompletionParsesTierAndCountIncludingThousandsSeparators()
	{
		ParsedSignal p = parse("You have completed 42 medium Treasure Trails.");
		assertEquals(SignalType.CLUE_COMPLETION, p.signalType);
		assertEquals("medium", p.subject);
		assertEquals(Integer.valueOf(42), p.value);
		assertEquals(Integer.valueOf(1234), parse("You have completed 1,234 easy Treasure Trails.").value);
	}

	@Test
	public void bossKcParsesBossAndCountFromTheChatCommandsCorpusWithTagsStripped()
	{
		ParsedSignal p = parse("Your <col=ff0000>Zulrah</col> kill count is: <col=ff0000>1,412</col>");
		assertEquals(SignalType.BOSS_KC, p.signalType);
		assertEquals("Zulrah", p.subject);
		assertEquals(Integer.valueOf(1412), p.value);
		// The corpus covers kill/harvest/lap/completion verbs.
		assertEquals(SignalType.BOSS_KC, parse("Your Zalcano harvest count is: 50").signalType);
		assertEquals(SignalType.BOSS_KC, parse("Your Prifddinas Agility Course lap count is: 200").signalType);
	}

	@Test
	public void diaryCompletionParsesTheRegionAndToleratesPhrasing()
	{
		ParsedSignal p = parse("Congratulations! You have completed all of the medium tasks in the Ardougne area.");
		assertEquals(SignalType.DIARY_COMPLETION, p.signalType);
		assertEquals("Ardougne", p.subject);
		// Tolerant of "all the" and a line with no "area" suffix.
		assertEquals(SignalType.DIARY_COMPLETION,
			parse("You have completed all the hard tasks in the Wilderness.").signalType);
	}

	@Test
	public void nonSignalLinesEmitNothing()
	{
		assertNull(parse("Zezima: hey what's up"));
		assertNull(parse("You have 5 more casks to open."));
		assertNull(parse(""));
		assertNull(parse(null));
	}

	@Test
	public void ambiguousHalfMatchesEmitNothing()
	{
		// "count is:" without the "Your <boss> (kill|...)" frame is not a boss KC.
		assertNull(parse("The current count is: 5"));
		// A bare mention of a level is not a level-up advance.
		assertNull(parse("Your combat level is now higher."));
		// "completed the quest" is not a diary "completed all the ... tasks in ..." line.
		assertNull(parse("You are close to completing the tasks."));
	}

	@Test
	public void questCompletionIsDeliberatelyNotChatMatched()
	{
		// OSRS marks a quest complete via a completion widget, not a reliable game message;
		// quest-completion provenance is owned by the quest-v2 lane. The enum value exists for
		// wire parity, but fabricating a fragile chat regex would break the witnessed-only rule.
		assertNull(parse("You have completed the quest Cook's Assistant."));
	}

	@Test
	public void detailCarriesTheWitnessedLineVerbatim()
	{
		ParsedSignal p = parse("Congratulations, you just advanced your Fishing level.");
		assertEquals("Congratulations, you just advanced your Fishing level.", p.detail);
	}

	// --- Dedup + per-session budget + account-switch (I3/I4) ---

	@Test
	public void sameTickRepeatIsSuppressedButALaterTickRepeatEmits()
	{
		SignalSessionState s = new SignalSessionState();
		assertTrue(s.admit(1L, 100, "advanced your Cooking level"));
		assertFalse("a re-rendered identical line on the same tick must not double-fire",
			s.admit(1L, 100, "advanced your Cooking level"));
		assertTrue("a genuine repeat on a later tick still emits",
			s.admit(1L, 101, "advanced your Cooking level"));
	}

	@Test
	public void differentLinesOnTheSameTickBothEmit()
	{
		SignalSessionState s = new SignalSessionState();
		assertTrue(s.admit(1L, 100, "line A"));
		assertTrue(s.admit(1L, 100, "line B"));
	}

	@Test
	public void perSessionCapCeilingHolds()
	{
		// Mutation-proof budget: past the cap, nothing more emits (break the cap check -> extra rows).
		SignalSessionState s = new SignalSessionState(3);
		assertTrue(s.admit(1L, 1, "a"));
		assertTrue(s.admit(1L, 2, "b"));
		assertTrue(s.admit(1L, 3, "c"));
		assertFalse("past the per-session cap, no more signals emit", s.admit(1L, 4, "d"));
		assertFalse(s.admit(1L, 5, "e"));
	}

	@Test
	public void accountSwitchClearsDedupAndBudget()
	{
		SignalSessionState s = new SignalSessionState(2);
		assertTrue(s.admit(100L, 1, "x"));
		assertTrue(s.admit(100L, 2, "y"));
		assertFalse("account A has hit its cap", s.admit(100L, 3, "z"));
		// Account B logs in on the same client: its budget and dedup start fresh.
		assertTrue("account B must not inherit account A's exhausted budget", s.admit(200L, 3, "x"));
		assertTrue(s.admit(200L, 4, "y"));
	}

	@Test
	public void endSessionResetsTheBudget()
	{
		SignalSessionState s = new SignalSessionState(1);
		assertTrue(s.admit(1L, 1, "a"));
		assertFalse(s.admit(1L, 2, "b"));
		s.endSession();
		assertTrue("a new session gets a fresh budget", s.admit(1L, 3, "c"));
	}
}
