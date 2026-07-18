/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.AnalyticsConfig;
import com.cortalabs.osrs.analytics.dto.FarmingState;
import com.cortalabs.osrs.analytics.dto.FarmingState.FarmingPatchState;
import com.cortalabs.osrs.analytics.transport.AnalyticsClient;
import com.cortalabs.osrs.analytics.transport.EventCategory;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Farming patch collector: turns witnessed farming-patch varbit changes into
 * append-only {@link FarmingState} rows — one per real {@code CropState} edge
 * (planted / ready / diseased / dead), never per varbit tick.
 *
 * <p><b>Source of truth:</b> FP-SPIKE-FARMING ({@code RESEARCH_FARMING_VARBIT_MAP.md}),
 * grounded in the RuneLite Time-Tracking plugin source. Two facts drive the design:
 * <ul>
 *   <li><b>Patch identity is {@code (regionID, transmit-slot)}, never the varbit
 *       alone.</b> The same transmit varbit (e.g. {@code FARMING_TRANSMIT_D} = 4774)
 *       carries the herb patch in Catherby, in Falador, in Ardougne, ...; the game
 *       writes the <i>currently-loaded region's</i> patch into it. {@link #HERB_PATCHES}
 *       is keyed by {@code (region, varbit)} so the same slot in two regions yields two
 *       distinct patch identities — a region-blind lookup would conflate every herb
 *       farm in the game (the research's #1 mutation-prove target).</li>
 *   <li><b>The decode is per-type and lives behind a package-private return type
 *       ({@code PatchState}) in RuneLite, so it cannot be called from here.</b>
 *       {@link #decodeHerb(int)} transcribes {@code PatchImplementation.HERB}
 *       (PatchImplementation.java L435-720) 1:1 — every value range copied from source,
 *       never re-derived. A value outside the transcribed ranges resolves to "nothing"
 *       (null), so a future herb the game adds degrades to honest silence, never a
 *       misclassification.</li>
 * </ul>
 *
 * <p><b>v1 scope — herb patches only.</b> Herb is the highest-value, most-tracked
 * farming activity (and Catherby's namesake). The 22 other {@code PatchImplementation}
 * types (allotment / tree / fruit-tree / hops / bush / ...) are additive later via the
 * same mechanism (a region map entry + a transcribed decoder) with no new research.
 * <b>Bird houses are intentionally out:</b> their EMPTY/BUILT/SEEDED states are not in
 * the backend's {@code FarmingPatchState} domain (planted/ready/diseased/dead), so a
 * bird-house row would 422 the batch — they belong to a separate lane, not this one.
 *
 * <p><b>Witnessed-or-absent (I2).</b> An empty (weeds/raked) patch is not a planted
 * crop and emits nothing; a patch attributed to a region the map does not cover emits
 * nothing; only a real, decoded state edge in a known {@code (region, slot)} is a row.
 *
 * <p><b>Account-switch clear (I4) + transition-only dedup.</b> {@link AccountKeyedDeltaGuard}
 * suppresses an unchanged re-report of the same patch and forgets every patch signature
 * when the {@code accountHash} changes, so account A's patch state can never suppress or
 * fabricate account B's first row.
 *
 * <p><b>No version-fragile eager load.</b> Ids are plain {@code int} literals and no
 * RuneLite constant class is reflected at construction — this collector cannot fail the
 * Guice graph (contrast {@link AnimationActivityMap}).
 */
@Singleton
public class FarmingCollector
{
	// --- Region ids (FP-SPIKE-FARMING Appendix A; FarmingWorld.java lines cited). ---
	static final int REGION_CATHERBY = 11062;
	static final int REGION_ARDOUGNE = 10548;
	static final int REGION_FALADOR = 12083;
	static final int REGION_HOSIDIUS = 6967;
	static final int REGION_MORYTANIA = 14391;
	static final int REGION_CIVITAS = 6192;
	static final int REGION_HARMONY = 15148;
	static final int REGION_TROLL_STRONGHOLD = 11321;
	static final int REGION_WEISS = 11325;
	static final int REGION_FARMING_GUILD = 4922;

	// --- Transmit varbit ids (FP-SPIKE-FARMING F2; VarbitID.java cited). ---
	static final int TRANSMIT_A = 4771;
	static final int TRANSMIT_B = 4772;
	static final int TRANSMIT_D = 4774;
	static final int TRANSMIT_E = 4775;

	/**
	 * {@code (regionID, transmitVarbit)} -> herb-patch label, keyed by a packed long so
	 * the same varbit in two regions is two distinct entries (region-keyed identity).
	 * Every row cites FP-SPIKE-FARMING Appendix A (the standard herb-farm layout puts
	 * herb on slot D=4774; the island/Troll/Weiss/Guild farms differ, as noted).
	 */
	private static final Map<Long, String> HERB_PATCHES = new LinkedHashMap<>();
	/** Distinct herb transmit varbit ids, for a cheap reject before the region lookup. */
	private static final Set<Integer> HERB_TRANSMIT_VARBITS;

	static
	{
		putPatch(REGION_CATHERBY, TRANSMIT_D, "Catherby herb");
		putPatch(REGION_ARDOUGNE, TRANSMIT_D, "Ardougne herb");
		putPatch(REGION_FALADOR, TRANSMIT_D, "Falador herb");
		putPatch(REGION_HOSIDIUS, TRANSMIT_D, "Hosidius herb");
		putPatch(REGION_MORYTANIA, TRANSMIT_D, "Morytania herb");
		putPatch(REGION_CIVITAS, TRANSMIT_D, "Civitas illa Fortis herb");
		putPatch(REGION_HARMONY, TRANSMIT_B, "Harmony herb");
		putPatch(REGION_TROLL_STRONGHOLD, TRANSMIT_A, "Troll Stronghold herb");
		putPatch(REGION_WEISS, TRANSMIT_A, "Weiss herb");
		putPatch(REGION_FARMING_GUILD, TRANSMIT_E, "Farming Guild herb");

		Set<Integer> varbits = new HashSet<>();
		for (long key : HERB_PATCHES.keySet())
		{
			varbits.add((int) (key & 0xFFFFFFFFL));
		}
		HERB_TRANSMIT_VARBITS = Collections.unmodifiableSet(varbits);
	}

	private static void putPatch(int regionId, int varbitId, String label)
	{
		HERB_PATCHES.put(patchKey(regionId, varbitId), label);
	}

	/** Pack a {@code (regionID, varbitId)} identity into one map key. Package-private for tests. */
	static long patchKey(int regionId, int varbitId)
	{
		return ((long) regionId << 32) | (varbitId & 0xFFFFFFFFL);
	}

	/** The herb-patch label for a {@code (region, varbit)} identity, or {@code null} when unmapped. */
	static String patchLabel(int regionId, int varbitId)
	{
		return HERB_PATCHES.get(patchKey(regionId, varbitId));
	}

	/** Package-private view of the shipped herb-patch map (for the id-map test). */
	static Map<Long, String> herbPatches()
	{
		return HERB_PATCHES;
	}

	private final Client client;
	private final AnalyticsClient analytics;
	private final AnalyticsConfig config;
	private final AccountKeyedDeltaGuard guard = new AccountKeyedDeltaGuard();

	@Inject
	public FarmingCollector(Client client, AnalyticsClient analytics, AnalyticsConfig config)
	{
		this.client = client;
		this.analytics = analytics;
		this.config = config;
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.enabled() || !config.trackFarmingState())
		{
			return;
		}
		// Farming transmit slots are varbits; a varp change (varbitId == -1) is never one.
		int varbit = event.getVarbitId();
		if (varbit == -1 || !HERB_TRANSMIT_VARBITS.contains(varbit))
		{
			return;
		}
		// Region-keyed attribution: the transmit slot carries the player's current region's
		// patch, so the patch identity is (currentRegion, varbit). An unmapped pairing (a herb
		// region we do not cover, or the player standing outside the primary farm region) emits
		// nothing rather than guessing which farm the shared slot belongs to.
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		WorldPoint location = player.getWorldLocation();
		if (location == null)
		{
			return;
		}
		String label = patchLabel(location.getRegionID(), varbit);
		if (label == null)
		{
			return;
		}
		String rsn = Payloads.rsn(client);
		if (rsn == null)
		{
			return;
		}
		PatchReading reading = decodeHerb(client.getVarbitValue(varbit));
		if (reading == null)
		{
			// Empty/weeds/undecodable value: not a captured transition (witnessed-or-absent).
			return;
		}
		// Transitions only: emit a row only when the patch's state (or produce) actually changed.
		String signature = reading.state.name() + '|' + (reading.plant == null ? "" : reading.plant);
		if (!guard.changed(client.getAccountHash(), label, signature))
		{
			return;
		}
		FarmingState payload = new FarmingState();
		Payloads.base(payload, client, rsn);
		payload.patch = label;
		payload.state = reading.state;
		payload.plant = reading.plant;
		analytics.enqueue(EventCategory.FARMING_STATE, payload);
	}

	/**
	 * Decode a herb-patch transmit varbit value into a captured state, transcribed 1:1
	 * from {@code PatchImplementation.HERB.forVarbitValue} (PatchImplementation.java
	 * L435-720). Returns {@code null} for an empty (weeds/raked) patch and for any value
	 * outside the transcribed ranges — both are "no captured transition", never a guess.
	 * Only the four captured {@code CropState} edges map to a wire state:
	 * GROWING→planted, HARVESTABLE→ready, DISEASED→diseased, DEAD→dead. Pure and
	 * client-free so the decode is verifiable against source with plain ints.
	 */
	static PatchReading decodeHerb(int value)
	{
		// WEEDS bands = an empty/raked patch, not a planted crop -> nothing.
		if (value >= 0 && value <= 3)
		{
			return null;
		}
		if (value >= 4 && value <= 7)
		{
			return planted("Guam");
		}
		if (value >= 8 && value <= 10)
		{
			return ready("Guam");
		}
		if (value >= 11 && value <= 14)
		{
			return planted("Marrentill");
		}
		if (value >= 15 && value <= 17)
		{
			return ready("Marrentill");
		}
		if (value >= 18 && value <= 21)
		{
			return planted("Tarromin");
		}
		if (value >= 22 && value <= 24)
		{
			return ready("Tarromin");
		}
		if (value >= 25 && value <= 28)
		{
			return planted("Harralander");
		}
		if (value >= 29 && value <= 31)
		{
			return ready("Harralander");
		}
		if (value >= 32 && value <= 35)
		{
			return planted("Ranarr");
		}
		if (value >= 36 && value <= 38)
		{
			return ready("Ranarr");
		}
		if (value >= 39 && value <= 42)
		{
			return planted("Toadflax");
		}
		if (value >= 43 && value <= 45)
		{
			return ready("Toadflax");
		}
		if (value >= 46 && value <= 49)
		{
			return planted("Irit");
		}
		if (value >= 50 && value <= 52)
		{
			return ready("Irit");
		}
		if (value >= 53 && value <= 56)
		{
			return planted("Avantoe");
		}
		if (value >= 57 && value <= 59)
		{
			return ready("Avantoe");
		}
		if (value >= 60 && value <= 63)
		{
			return planted("Huasca");
		}
		if (value >= 64 && value <= 66)
		{
			return ready("Huasca");
		}
		if (value == 67)
		{
			return null; // WEEDS (raked)
		}
		if (value >= 68 && value <= 71)
		{
			return planted("Kwuarm");
		}
		if (value >= 72 && value <= 74)
		{
			return ready("Kwuarm");
		}
		if (value >= 75 && value <= 78)
		{
			return planted("Snapdragon");
		}
		if (value >= 79 && value <= 81)
		{
			return ready("Snapdragon");
		}
		if (value >= 82 && value <= 85)
		{
			return planted("Cadantine");
		}
		if (value >= 86 && value <= 88)
		{
			return ready("Cadantine");
		}
		if (value >= 89 && value <= 92)
		{
			return planted("Lantadyme");
		}
		if (value >= 93 && value <= 95)
		{
			return ready("Lantadyme");
		}
		if (value >= 96 && value <= 99)
		{
			return planted("Dwarf weed");
		}
		if (value >= 100 && value <= 102)
		{
			return ready("Dwarf weed");
		}
		if (value >= 103 && value <= 106)
		{
			return planted("Torstol");
		}
		if (value >= 107 && value <= 109)
		{
			return ready("Torstol");
		}
		if (value >= 128 && value <= 130)
		{
			return diseased("Guam");
		}
		if (value >= 131 && value <= 133)
		{
			return diseased("Marrentill");
		}
		if (value >= 134 && value <= 136)
		{
			return diseased("Tarromin");
		}
		if (value >= 137 && value <= 139)
		{
			return diseased("Harralander");
		}
		if (value >= 140 && value <= 142)
		{
			return diseased("Ranarr");
		}
		if (value >= 143 && value <= 145)
		{
			return diseased("Toadflax");
		}
		if (value >= 146 && value <= 148)
		{
			return diseased("Irit");
		}
		if (value >= 149 && value <= 151)
		{
			return diseased("Avantoe");
		}
		if (value >= 152 && value <= 154)
		{
			return diseased("Kwuarm");
		}
		if (value >= 155 && value <= 157)
		{
			return diseased("Snapdragon");
		}
		if (value >= 158 && value <= 160)
		{
			return diseased("Cadantine");
		}
		if (value >= 161 && value <= 163)
		{
			return diseased("Lantadyme");
		}
		if (value >= 164 && value <= 166)
		{
			return diseased("Dwarf weed");
		}
		if (value >= 167 && value <= 169)
		{
			return diseased("Torstol");
		}
		if (value >= 170 && value <= 172)
		{
			// Dead herbs are encoded generically (Produce.ANYHERB): the specific herb is not
			// witnessable at death, so plant is honestly absent.
			return dead(null);
		}
		if (value >= 173 && value <= 175)
		{
			return diseased("Huasca");
		}
		if (value >= 176 && value <= 191)
		{
			return null; // WEEDS (raked)
		}
		if (value >= 192 && value <= 195)
		{
			return planted("Goutweed");
		}
		if (value >= 196 && value <= 197)
		{
			return ready("Goutweed");
		}
		if (value >= 198 && value <= 200)
		{
			return diseased("Goutweed");
		}
		if (value >= 201 && value <= 203)
		{
			return dead("Goutweed");
		}
		if (value >= 204 && value <= 219)
		{
			return null; // WEEDS (raked)
		}
		if (value >= 221 && value <= 255)
		{
			return null; // WEEDS (raked)
		}
		// value == 220 and anything outside 0..255: unmapped -> honest silence, never a guess.
		return null;
	}

	private static PatchReading planted(String plant)
	{
		return new PatchReading(FarmingPatchState.PLANTED, plant);
	}

	private static PatchReading ready(String plant)
	{
		return new PatchReading(FarmingPatchState.READY, plant);
	}

	private static PatchReading diseased(String plant)
	{
		return new PatchReading(FarmingPatchState.DISEASED, plant);
	}

	private static PatchReading dead(String plant)
	{
		return new PatchReading(FarmingPatchState.DEAD, plant);
	}

	/** A decoded, captured patch reading: a wire state plus an optional produce identity. */
	static final class PatchReading
	{
		final FarmingPatchState state;
		final String plant;

		PatchReading(FarmingPatchState state, String plant)
		{
			this.state = state;
			this.plant = plant;
		}
	}
}
