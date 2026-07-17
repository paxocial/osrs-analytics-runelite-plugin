/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

/**
 * Achievement diary progress for a single region. Mirrors {@code DiaryProgress}.
 * Each tier flag defaults to {@code false} (serialized explicitly).
 */
public class DiaryProgress extends PluginPayload
{
	public String region;

	public boolean easy;

	public boolean medium;

	public boolean hard;

	public boolean elite;
}
