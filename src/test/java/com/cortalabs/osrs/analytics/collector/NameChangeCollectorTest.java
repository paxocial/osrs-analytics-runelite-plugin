/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Guards the rename-detection predicate that decides whether a name change is
 * submitted. The full collector is event/config/ConfigManager-driven; this pins
 * the decision that must never fire spuriously (first login, same name, blanks).
 */
public class NameChangeCollectorTest
{
	@Test
	public void detectsAGenuineRename()
	{
		assertTrue(NameChangeCollector.isRename("OldName", "NewName"));
	}

	@Test
	public void firstLoginIsNotARename()
	{
		// No previously stored name -> nothing to compare against.
		assertFalse(NameChangeCollector.isRename(null, "NewName"));
	}

	@Test
	public void sameNameIsNotARename()
	{
		assertFalse(NameChangeCollector.isRename("Zezima", "Zezima"));
	}

	@Test
	public void caseOnlyDifferenceIsNotARename()
	{
		// RuneScape names are case-insensitive; casing alone is not a rename.
		assertFalse(NameChangeCollector.isRename("Zezima", "zezima"));
	}

	@Test
	public void blankNamesAreNotARename()
	{
		assertFalse(NameChangeCollector.isRename("", "NewName"));
		assertFalse(NameChangeCollector.isRename("OldName", "   "));
	}
}
