/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import java.util.Map;

/**
 * Live XP snapshot. The backend requires all 24 OSRS skills to be present in
 * {@link #skills} (skill name -> total xp), including {@code sailing}.
 */
public class XpSnapshot extends PluginPayload
{
	/** Skill name (lowercase) to total XP. Must contain all 24 required skills. */
	public Map<String, Integer> skills;
}
