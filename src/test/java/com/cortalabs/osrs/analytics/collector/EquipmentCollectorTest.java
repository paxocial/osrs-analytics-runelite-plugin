/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import com.cortalabs.osrs.analytics.dto.ItemEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Guards {@link EquipmentCollector#signature} — the fingerprint the equipment
 * change-guard compares. An unchanged loadout must produce the identical string
 * (so a net-zero change burst is suppressed), and any real difference in worn
 * gear or inventory must change it (so a real change is never dropped).
 */
public class EquipmentCollectorTest
{
	private static Map<String, Integer> equip(Object... slotIdPairs)
	{
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < slotIdPairs.length; i += 2)
		{
			m.put((String) slotIdPairs[i], (Integer) slotIdPairs[i + 1]);
		}
		return m;
	}

	private static List<ItemEntry> inv(int... idQtyPairs)
	{
		List<ItemEntry> items = new ArrayList<>();
		for (int i = 0; i < idQtyPairs.length; i += 2)
		{
			items.add(new ItemEntry(idQtyPairs[i], idQtyPairs[i + 1]));
		}
		return items;
	}

	@Test
	public void identicalLoadoutYieldsIdenticalSignature()
	{
		String a = EquipmentCollector.signature(equip("weapon", 1319, "shield", 1171), inv(995, 100, 385, 5));
		String b = EquipmentCollector.signature(equip("weapon", 1319, "shield", 1171), inv(995, 100, 385, 5));
		assertEquals("an unchanged loadout must fingerprint identically", a, b);
	}

	@Test
	public void changedInventoryQuantityChangesSignature()
	{
		String a = EquipmentCollector.signature(equip("weapon", 1319), inv(995, 100));
		String b = EquipmentCollector.signature(equip("weapon", 1319), inv(995, 101));
		assertNotEquals("a quantity change must change the fingerprint", a, b);
	}

	@Test
	public void changedEquipmentChangesSignature()
	{
		String a = EquipmentCollector.signature(equip("weapon", 1319), inv(995, 100));
		String b = EquipmentCollector.signature(equip("weapon", 1333), inv(995, 100));
		assertNotEquals("a worn-item change must change the fingerprint", a, b);
	}

	@Test
	public void addedInventoryItemChangesSignature()
	{
		String a = EquipmentCollector.signature(equip("weapon", 1319), inv(995, 100));
		String b = EquipmentCollector.signature(equip("weapon", 1319), inv(995, 100, 385, 1));
		assertNotEquals("gaining an item must change the fingerprint", a, b);
	}

	@Test
	public void emptyLoadoutIsStableAndDistinct()
	{
		String empty1 = EquipmentCollector.signature(new LinkedHashMap<>(), new ArrayList<>());
		String empty2 = EquipmentCollector.signature(new LinkedHashMap<>(), new ArrayList<>());
		String nonEmpty = EquipmentCollector.signature(equip("weapon", 1319), new ArrayList<>());
		assertEquals("two empty loadouts fingerprint identically", empty1, empty2);
		assertNotEquals("empty and non-empty must differ", empty1, nonEmpty);
	}

	@Test
	public void distinctItemsSameTotalCountStillDiffer()
	{
		// Two different inventories that happen to share item count must not collide.
		String a = EquipmentCollector.signature(new LinkedHashMap<>(), inv(995, 1, 385, 1));
		String b = EquipmentCollector.signature(new LinkedHashMap<>(), inv(4151, 1, 560, 1));
		assertNotEquals(a, b);
		// And order-stable construction (Arrays.asList sanity) keeps this deterministic.
		assertEquals(a, EquipmentCollector.signature(new LinkedHashMap<>(),
			new ArrayList<>(Arrays.asList(new ItemEntry(995, 1), new ItemEntry(385, 1)))));
	}
}
