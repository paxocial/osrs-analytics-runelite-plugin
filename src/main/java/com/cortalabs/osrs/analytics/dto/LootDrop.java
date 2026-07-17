/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.dto;

import com.google.gson.annotations.SerializedName;

/**
 * Loot drop received event. Mirrors {@code LootDrop}.
 */
public class LootDrop extends PluginPayload
{
	public enum SourceType
	{
		@SerializedName("npc")
		NPC,
		@SerializedName("boss")
		BOSS,
		@SerializedName("chest")
		CHEST,
		@SerializedName("clue")
		CLUE,
		@SerializedName("minigame")
		MINIGAME,
		@SerializedName("other")
		OTHER
	}

	@SerializedName("item_id")
	public int itemId;

	@SerializedName("item_name")
	public String itemName;

	public int quantity;

	/** Grand Exchange value (coins); optional. */
	@SerializedName("ge_value")
	public Long geValue;

	public String source;

	@SerializedName("source_type")
	public SourceType sourceType;
}
