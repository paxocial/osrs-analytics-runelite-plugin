/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Quest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Guards the quest-type classification the QuestCollector derives, since the
 * RuneLite {@link Quest} enum carries no type field. The miniquest list is
 * sourced from the OSRS Wiki (https://oldschool.runescape.wiki/w/Miniquests,
 * 19 miniquests as of 2026-07-17); Recipe for Disaster sub-entries are
 * classified by their "Recipe for Disaster - " display-name prefix; Tutorial
 * Island is neither a quest nor a miniquest.
 *
 * <p>Running the classifier over the live {@link Quest} enum also guards
 * against enum display-name drift: if RuneLite renames a miniquest, the
 * miniquest count drops below the wiki list size and this test fails, forcing
 * a list refresh instead of silently misclassifying.
 */
public class QuestClassificationTest
{
	@Test
	public void wikiMiniquestsClassifyAsMiniquest()
	{
		assertEquals("miniquest", QuestCollector.classify("Alfred Grimhand's Barcrawl"));
		assertEquals("miniquest", QuestCollector.classify("Enter the Abyss"));
		assertEquals("miniquest", QuestCollector.classify("Mage Arena I"));
		assertEquals("miniquest", QuestCollector.classify("Mage Arena II"));
		assertEquals("miniquest", QuestCollector.classify("Into the Tombs"));
		assertEquals("miniquest", QuestCollector.classify("Vale Totems"));
		assertEquals("miniquest", QuestCollector.classify("His Faithful Servants"));
	}

	@Test
	public void rfdSubEntriesClassifyAsSubquestButParentStaysQuest()
	{
		assertEquals("subquest", QuestCollector.classify("Recipe for Disaster - Another Cook's Quest"));
		assertEquals("subquest", QuestCollector.classify("Recipe for Disaster - Culinaromancer"));
		assertEquals("quest", QuestCollector.classify("Recipe for Disaster"));
	}

	@Test
	public void tutorialIslandIsOtherAndOrdinaryQuestsDefaultToQuest()
	{
		assertEquals("other", QuestCollector.classify("Tutorial Island"));
		assertEquals("quest", QuestCollector.classify("Cook's Assistant"));
		assertEquals("quest", QuestCollector.classify("Dragon Slayer II"));
		// Unknown future entries must degrade to the safe default, never drop.
		assertEquals("quest", QuestCollector.classify("Some Future Quest 2027"));
	}

	@Test
	public void liveQuestEnumClassifiesToExpectedTypeCounts()
	{
		Map<String, Integer> counts = new HashMap<>();
		for (Quest quest : Quest.values())
		{
			counts.merge(QuestCollector.classify(quest.getName()), 1, Integer::sum);
		}
		// All 19 wiki miniquests must resolve against live enum display names;
		// fewer means an enum rename (or removal) silently broke classification.
		assertEquals("wiki miniquest list resolves against Quest enum names",
			Integer.valueOf(19), counts.get("miniquest"));
		assertEquals("the ten RFD sub-entries classify as subquest",
			Integer.valueOf(10), counts.get("subquest"));
		// Tutorial Island is absent from released runelite-api 1.12.33 but
		// present in newer client source; either way it is the only possible
		// 'other' entry.
		assertTrue("at most Tutorial Island classifies as 'other'",
			counts.getOrDefault("other", 0) <= 1);
		assertTrue("everything else defaults to quest",
			counts.getOrDefault("quest", 0) >= 150);
	}
}
