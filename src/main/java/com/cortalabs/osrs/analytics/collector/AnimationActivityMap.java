/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AnimationID;

/**
 * Closed animation-id -> activity-bucket classifier for
 * {@link ActivityClassificationCollector}.
 *
 * <p><b>Source of truth:</b> FP-SPIKE-ANIM ({@code RESEARCH_FP_SPIKE_ANIM.md}), which
 * verified the closed 20-bucket vocabulary and the per-family match rules against
 * {@code net.runelite.api.AnimationID} at runelite-api 1.12.33.
 *
 * <p><b>Design (research recommendation X.1) — read live constants, do not hard-code
 * an id table.</b> The {@code id -> bucket} map is BUILT ONCE by reflecting the live
 * {@link AnimationID} constants and bucketing each by its constant-name family, rather
 * than transcribing a ~237-row {@code int} literal table. That is the honest choice
 * for two reasons: (1) every mapped id is the id the deployed plugin will actually
 * see, so nothing is guessed — the map author never has to invent an id they could not
 * read; and (2) the prefix rule stays in lockstep with whatever RuneLite version
 * resolves, so a constant renumber does not silently misclassify (only the two
 * collision ids and the gameval gap-fills need a re-verify on a version bump).
 *
 * <p><b>Honesty guarantees (I2):</b>
 * <ul>
 *   <li>The classifier can only ever emit a token from {@link #BUCKETS} (the closed
 *       20-value vocabulary). Nothing else can leave this class.</li>
 *   <li>Any playing animation not matched by a family rule resolves to
 *       {@link #UNKNOWN} — an honest "we could not classify this", never a guess.</li>
 *   <li>The two ids that are ambiguous by animation ALONE (827, 830; FP-SPIKE-ANIM
 *       Finding 3) are forced to {@link #UNKNOWN}. Any other id that two different
 *       families both claim is likewise poisoned to {@link #UNKNOWN}.</li>
 *   <li>{@code combat} and {@code agility} are in the vocabulary but are deliberately
 *       NOT populated from animation in v1 (per-weapon / per-obstacle ids are not
 *       centrally named — FP-SPIKE-ANIM Technical Analysis); they stay empty rather
 *       than being faked from unverified ids.</li>
 * </ul>
 *
 * <p>Immutable after construction; the per-tick {@link #classify(int)} is an O(1)
 * lookup. Not thread-safe to build, but built once on the client thread at collector
 * construction and only read afterwards.
 */
@Slf4j
public final class AnimationActivityMap
{
	// --- The closed bucket vocabulary: the ONLY tokens this classifier can emit. ---
	// Every token matches the server's ^[a-z_]{1,40}$ contract.
	public static final String WOODCUTTING = "woodcutting";
	public static final String MINING = "mining";
	public static final String FISHING = "fishing";
	public static final String COOKING = "cooking";
	public static final String FIREMAKING = "firemaking";
	public static final String FLETCHING = "fletching";
	public static final String SMITHING = "smithing";
	public static final String CRAFTING = "crafting";
	public static final String HERBLORE = "herblore";
	public static final String FARMING = "farming";
	public static final String CONSTRUCTION = "construction";
	public static final String RUNECRAFT = "runecraft";
	public static final String THIEVING = "thieving";
	public static final String HUNTER = "hunter";
	public static final String PRAYER = "prayer";
	public static final String MAGIC_UTILITY = "magic_utility";
	public static final String COMBAT = "combat";
	public static final String AGILITY = "agility";
	public static final String IDLE = "idle";
	public static final String UNKNOWN = "unknown";

	/** The finite bucket taxonomy (FP-SPIKE-ANIM Finding 1). */
	static final Set<String> BUCKETS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
		WOODCUTTING, MINING, FISHING, COOKING, FIREMAKING, FLETCHING, SMITHING, CRAFTING,
		HERBLORE, FARMING, CONSTRUCTION, RUNECRAFT, THIEVING, HUNTER, PRAYER, MAGIC_UTILITY,
		COMBAT, AGILITY, IDLE, UNKNOWN)));

	/** {@link net.runelite.api.Actor#getAnimation()} returns this when nothing is playing. */
	static final int IDLE_ANIMATION = -1;

	/**
	 * Ids that are ambiguous by animation ALONE (FP-SPIKE-ANIM Finding 3): two
	 * unrelated activities share the exact integer, so animation cannot decide the
	 * bucket. Forced to {@link #UNKNOWN} unless a future interaction disambiguation is
	 * wired. Never picked one-or-the-other — that would be a guess.
	 *   827 = SMITHING_CANNONBALL (smithing) vs BURYING_BONES (prayer)
	 *   830 = FARMING_HARVEST_ALLOTMENT (farming) vs DIG (spade; clues/quests)
	 */
	private static final Set<Integer> AMBIGUOUS_IDS = Collections.unmodifiableSet(
		new HashSet<>(Arrays.asList(827, 830)));

	/**
	 * Ids verified in the gameval namespace but not named in the legacy
	 * {@link AnimationID} spine (FP-SPIKE-ANIM Recommendation X.2). Applied
	 * put-if-absent so a legacy mapping (or a collision) always wins.
	 *   791 = HUMAN_RUNECRAFT (crafting runes at an altar)
	 *   881 = HUMAN_PICKPOCKET (thieving)
	 */
	private static final Map<Integer, String> GAMEVAL_GAP_FILLS;

	static
	{
		Map<Integer, String> gaps = new HashMap<>();
		gaps.put(791, RUNECRAFT);
		gaps.put(881, THIEVING);
		GAMEVAL_GAP_FILLS = Collections.unmodifiableMap(gaps);
	}

	private final Map<Integer, String> idToBucket;

	/** Production: build the map from the live {@link AnimationID} constants. */
	public AnimationActivityMap()
	{
		this(reflectAnimationConstants());
	}

	/**
	 * Test seam: build from an explicit {@code constantName -> value} source so the
	 * bucketing logic is verifiable with plain data, independent of the resolved
	 * RuneLite jar. Package-private.
	 */
	AnimationActivityMap(Map<String, Integer> constants)
	{
		this.idToBucket = build(constants);
	}

	/**
	 * Classify a raw animation id into a closed-vocabulary bucket. Never returns a
	 * value outside {@link #BUCKETS}; an unmapped playing animation is {@link #UNKNOWN},
	 * and {@link #IDLE_ANIMATION} is {@link #IDLE}.
	 */
	public String classify(int animationId)
	{
		if (animationId == IDLE_ANIMATION)
		{
			return IDLE;
		}
		String bucket = idToBucket.get(animationId);
		return bucket != null ? bucket : UNKNOWN;
	}

	/** Number of concretely-mapped ids (excludes the {@code idle}/{@code unknown} fallbacks). Tests. */
	int mappedIdCount()
	{
		return idToBucket.size();
	}

	/**
	 * Bucket an {@link AnimationID} constant by its name family. Returns {@code null}
	 * when no family claims it (the caller leaves it unmapped -> {@code unknown}).
	 *
	 * <p>Every rule below traces to FP-SPIKE-ANIM Finding 2. Order matters: the
	 * exact-name exceptions and the more-specific prefixes are checked before the
	 * broad families they would otherwise be swallowed by (e.g. {@code DENSE_ESSENCE_}
	 * is runecraft, not crafting; {@code CRAFTING_BATTLESTAVES} is smithing).
	 */
	static String bucketForConstant(String name)
	{
		// Exact-name overrides (cross-family exceptions), most specific first.
		switch (name)
		{
			case "CRAFTING_BATTLESTAVES":
				return SMITHING;
			case "SAND_COLLECTION":
				return CRAFTING;
			case "MILKING_COW":
				return FARMING;
			case "USING_GILDED_ALTAR":
				return PRAYER;
			case "SACRIFICE_BLESSED_BONE_SHARDS":
				return PRAYER;
			case "BURYING_BONES":
				// id 827 -> forced UNKNOWN by AMBIGUOUS_IDS; named here for provenance.
				return PRAYER;
			case "HOME_MAKE_TABLET":
				return MAGIC_UTILITY;
			default:
				break;
		}

		// Prefix families (specific prefixes before broad ones).
		if (name.startsWith("DENSE_ESSENCE_"))
		{
			return RUNECRAFT;
		}
		if (name.startsWith("WOODCUTTING_"))
		{
			return WOODCUTTING;
		}
		if (name.startsWith("MINING_"))
		{
			return MINING;
		}
		if (name.startsWith("FISHING_"))
		{
			return FISHING;
		}
		if (name.startsWith("COOKING_"))
		{
			return COOKING;
		}
		if (name.startsWith("FIREMAKING"))
		{
			return FIREMAKING;
		}
		if (name.startsWith("FLETCHING_"))
		{
			return FLETCHING;
		}
		if (name.startsWith("SMITHING_"))
		{
			return SMITHING;
		}
		if (name.startsWith("GEM_CUTTING_"))
		{
			return CRAFTING;
		}
		if (name.startsWith("CRAFTING_"))
		{
			return CRAFTING;
		}
		if (name.startsWith("HERBLORE_"))
		{
			return HERBLORE;
		}
		if (name.startsWith("CHURN_MILK"))
		{
			return FARMING;
		}
		if (name.startsWith("FARMING_"))
		{
			return FARMING;
		}
		if (name.startsWith("CONSTRUCTION"))
		{
			return CONSTRUCTION;
		}
		if (name.startsWith("HUNTER_"))
		{
			return HUNTER;
		}
		if (name.startsWith("ECTOFUNTUS_"))
		{
			return PRAYER;
		}
		if (name.startsWith("THIEVING_"))
		{
			return THIEVING;
		}
		if (name.startsWith("MAGIC_"))
		{
			return MAGIC_UTILITY;
		}
		return null;
	}

	/**
	 * Build the {@code id -> bucket} map from a {@code name -> value} constant source.
	 * Poisons the two known-ambiguous ids up front, poisons any further id that two
	 * families both claim, then applies the gameval gap-fills put-if-absent. A poisoned
	 * id is left OUT of the map so {@link #classify(int)} returns {@code unknown}.
	 */
	private static Map<Integer, String> build(Map<String, Integer> constants)
	{
		Map<Integer, String> map = new HashMap<>();
		Set<Integer> poisoned = new HashSet<>(AMBIGUOUS_IDS);

		for (Map.Entry<String, Integer> entry : constants.entrySet())
		{
			int id = entry.getValue();
			if (poisoned.contains(id))
			{
				map.remove(id);
				continue;
			}
			String bucket = bucketForConstant(entry.getKey());
			if (bucket == null)
			{
				continue;
			}
			String existing = map.get(id);
			if (existing != null && !existing.equals(bucket))
			{
				// Two families disagree on the same id: ambiguous by animation alone.
				map.remove(id);
				poisoned.add(id);
				continue;
			}
			map.put(id, bucket);
		}

		for (Map.Entry<Integer, String> gap : GAMEVAL_GAP_FILLS.entrySet())
		{
			int id = gap.getKey();
			if (!poisoned.contains(id) && !map.containsKey(id))
			{
				map.put(id, gap.getValue());
			}
		}
		return map;
	}

	/**
	 * Reflect the public {@code static final int} constants of {@link AnimationID}
	 * into a {@code name -> value} map. This reads the constants the deployed plugin
	 * actually links against, so no id is ever invented.
	 */
	private static Map<String, Integer> reflectAnimationConstants()
	{
		Map<String, Integer> constants = new HashMap<>();
		for (Field field : AnimationID.class.getFields())
		{
			if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class)
			{
				continue;
			}
			try
			{
				constants.put(field.getName(), field.getInt(null));
			}
			catch (IllegalAccessException ex)
			{
				// A public static field is accessible; if the JVM disagrees, skip it
				// rather than fail the whole map — the id simply falls through to unknown.
				log.debug("Skipping inaccessible AnimationID constant {}", field.getName());
			}
		}
		return constants;
	}
}
