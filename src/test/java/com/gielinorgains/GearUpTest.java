package com.gielinorgains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.game.ItemVariationMapping;
import org.junit.Test;

public class GearUpTest
{
	// Real game ids, so RuneLite's own variation groups are what's tested.
	private static final int MASORI_MASK = 27226;
	private static final int MASORI_MASK_F = 27235;
	private static final int TOXIC_BLOWPIPE = 12926;
	private static final int TOXIC_BLOWPIPE_EMPTY = 12924;
	private static final int ABYSSAL_WHIP = 4151;
	private static final int ABYSSAL_TENTACLE = 12006;
	private static final int RUNE_ARROW = 892;
	private static final int SALVE_EI = 12018;
	private static final int SALVE = 4081;

	private static GearUp.Catalog catalog()
	{
		GearUp.Catalog c = new GearUp.Catalog();
		c.v = 1;
		c.items = Arrays.asList(entry(MASORI_MASK, "Masori mask", null), entry(MASORI_MASK_F, "Masori mask (f)", null),
			entry(TOXIC_BLOWPIPE, "Toxic blowpipe", null), entry(ABYSSAL_WHIP, "Abyssal whip", null),
			entry(ABYSSAL_TENTACLE, "Abyssal tentacle", null), entry(RUNE_ARROW, "Rune arrow", 100),
			entry(SALVE_EI, "Salve amulet(ei)", null));
		return c;
	}

	private static GearUp.Entry entry(int id, String name, Integer min)
	{
		GearUp.Entry e = new GearUp.Entry();
		e.id = id;
		e.n = name;
		e.min = min;
		return e;
	}

	private static Map<Integer, Long> held(Object... idQty)
	{
		Map<Integer, Long> m = new HashMap<>();
		for (int i = 0; i < idQty.length; i += 2)
		{
			m.put((Integer) idQty[i], ((Number) idQty[i + 1]).longValue());
		}
		return m;
	}

	private static Map<Integer, String> names()
	{
		Map<Integer, String> n = new HashMap<>();
		n.put(TOXIC_BLOWPIPE_EMPTY, "Toxic blowpipe (empty)");
		n.put(SALVE, "Salve amulet");
		return n;
	}

	private static Map<Integer, Long> match(Map<Integer, Long> owned)
	{
		return GearUp.match(owned, catalog(), ItemVariationMapping::map, names()::get);
	}

	@Test
	public void countsAnEmptyBlowpipeAsTheBlowpipe()
	{
		assertEquals(held(TOXIC_BLOWPIPE, 1), match(held(TOXIC_BLOWPIPE_EMPTY, 1)));
	}

	@Test
	public void plainMasoriIsNotTheFortifiedOne()
	{
		assertEquals(held(MASORI_MASK, 1), match(held(MASORI_MASK, 1)));
		assertEquals(held(MASORI_MASK_F, 1), match(held(MASORI_MASK_F, 1)));
	}

	@Test
	public void aWhipIsNotATentacle()
	{
		assertEquals(held(ABYSSAL_WHIP, 1), match(held(ABYSSAL_WHIP, 1)));
	}

	@Test
	public void aPlainSalveIsNotTheImbuedOne()
	{
		assertEquals(Collections.emptyMap(), match(held(SALVE, 1)));
	}

	@Test
	public void ignoresWhatGearUpDoesNotList()
	{
		assertEquals(Collections.emptyMap(), match(held(995, 5_000_000, 385, 20)));
	}

	@Test
	public void ammoNeedsATripsWorthAcrossTheBankBagAndQuiver()
	{
		List<Map<Integer, Long>> few = Arrays.asList(match(held(RUNE_ARROW, 40)), match(held(RUNE_ARROW, 30)));
		assertEquals(Collections.emptyList(), GearUp.owned(catalog(), few));
		List<Map<Integer, Long>> enough = Arrays.asList(match(held(RUNE_ARROW, 70)), match(held(RUNE_ARROW, 30)),
			match(held(TOXIC_BLOWPIPE, 1)));
		assertEquals(Arrays.asList(RUNE_ARROW, TOXIC_BLOWPIPE), GearUp.owned(catalog(), enough));
	}

	@Test
	public void variantsThatNeedFixingDoNotCount()
	{
		assertFalse(GearUp.counts("Bow of faerdhinen (inactive)", "Bow of faerdhinen (c)"));
		assertFalse(GearUp.counts("Karil's crossbow 0", "Karil's crossbow"));
		assertFalse(GearUp.counts("Fire cape (broken)", "Fire cape"));
		assertFalse(GearUp.counts("Ruby bolts", "Ruby bolts (e)"));
		assertFalse(GearUp.counts("Slayer helmet", "Slayer helmet (i)"));
		assertTrue(GearUp.counts("Black slayer helmet (i)", "Slayer helmet (i)"));
		assertTrue(GearUp.counts("Karil's crossbow 75", "Karil's crossbow"));
		assertTrue(GearUp.counts("Trident of the seas (e)", "Trident of the seas"));
		assertTrue(GearUp.counts("Berserker ring (i)", "Berserker ring"));
	}

	@Test
	public void buildsTheLinkTheWebsiteReads()
	{
		// lib/gearUp/runeliteGear.test.ts reads this same link.
		long now = 1_793_966_400_000L;
		String link = GearUp.link("Iron Zezima", null, Arrays.asList(861, 9185, 4151), 4_200_000L, now - 5_400_000L, now);
		assertEquals(TraderApi.SITE + "/gear-up#rl=1&n=Iron%20Zezima&have=861.9185.4151&gp=4200000&bank=1793961000&at=1793966400",
			link);
		assertEquals(TraderApi.SITE + "/gear-up#rl=1&have=&bank=0&at=1793966400", GearUp.link(null, null, Collections.emptyList(), null, 0, now));
	}

	@Test
	public void addsLevelsTheAccountTypeAndWhatIsDone()
	{
		// lib/gearUp/runeliteGear.test.ts reads this same link.
		long now = 1_793_966_400_000L;
		GearUp.Account account = new GearUp.Account(new int[]{75, 80, 70, 85, 75, 80, 74, 60, 50}, GearUp.accountType(4),
			Arrays.asList("Kandarin hard diary", "Rigour", "Desert Treasure II - The Fallen Empire"));
		assertEquals(TraderApi.SITE + "/gear-up#rl=1&n=Iron%20Zezima&lv=75.80.70.85.75.80.74.60.50&type=group"
				+ "&done=Kandarin%20hard%20diary,Rigour,Desert%20Treasure%20II%20-%20The%20Fallen%20Empire&have=&bank=0&at=1793966400",
			GearUp.link("Iron Zezima", account, Collections.emptyList(), null, 0, now));
	}

	@Test
	public void readsTheAccountTypeTheWayRuneLiteDoes()
	{
		assertEquals("regular", GearUp.accountType(0));
		assertEquals("ironman", GearUp.accountType(1));
		assertEquals("ultimate", GearUp.accountType(2));
		assertEquals("hardcore", GearUp.accountType(3));
		assertEquals("group", GearUp.accountType(5));
		assertEquals(null, GearUp.accountType(99));
		// The same order as RuneLite's AccountType.
	}

	@Test
	public void sendsOnlyWhatTheListAsksAboutAndTheGameSaysIsDone()
	{
		GearUp.Catalog c = catalog();
		c.done = Arrays.asList("Lost City", "Piety", "Rigour", "Bad, name");
		assertEquals(Arrays.asList("Lost City", "Rigour"), GearUp.done(c, n -> !n.equals("Piety")));
		c.done = null;
		assertTrue(GearUp.done(c, n -> true).isEmpty());
	}

	@Test
	public void everyQuestTheWebsiteAsksAboutHasARuneLiteName()
	{
		java.util.Set<String> names = new java.util.HashSet<>();
		for (net.runelite.api.Quest q : net.runelite.api.Quest.values())
		{
			names.add(q.getName());
		}
		// The catalog's quests today (lib/gearUp catalog `q`), plus Piety's.
		for (String quest : new String[]{"Animal Magnetism", "Beneath Cursed Sands", "Desert Treasure I",
			"Desert Treasure II - The Fallen Empire", "Dragon Slayer I", "Dragon Slayer II", "Haunted Mine",
			"Horror from the Deep", "Lost City", "Mage Arena I", "Mage Arena II", "Monkey Madness I", "Recipe for Disaster",
			"Shadow of the Storm", "Song of the Elves", "The Fremennik Isles", "Underground Pass", "King's Ransom"})
		{
			assertTrue(quest, names.contains(quest));
		}
	}

	@Test
	public void savesAndLoadsTheBank()
	{
		Map<Integer, Long> bank = new java.util.LinkedHashMap<>();
		bank.put(4151, 1L);
		bank.put(892, 500L);
		assertEquals("4151:1,892:500", GearUp.save(bank));
		assertEquals(bank, GearUp.load("4151:1,892:500"));
		assertEquals(held(4151, 1), GearUp.load("4151:1,junk,1:x"));
		assertTrue(GearUp.load(null).isEmpty());
	}

	@Test
	public void countsPlatinumTokensAsCoins()
	{
		assertEquals(3_005_000L, GearUpController.coins(held(995, 5_000, 13204, 3_000)));
	}

	@Test
	public void saysHowManyAndHowFreshTheBankIs()
	{
		long now = 1_793_966_400_000L;
		List<GearPanel.Owned> two = Arrays.asList(new GearPanel.Owned(1, "A"), new GearPanel.Owned(2, "B"));
		assertEquals("2 of your items are on Gear Up's list. Bank seen 2 hours ago.",
			GearPanel.status(new GearPanel.View(true, true, GearPanel.Status.READY, two, now - 2 * 3_600_000L, now)));
		assertEquals("1 of your items is on Gear Up's list. " + GearPanel.NO_BANK,
			GearPanel.status(new GearPanel.View(true, true, GearPanel.Status.READY, two.subList(0, 1), 0, now)));
		assertEquals(GearPanel.OFF, GearPanel.status(new GearPanel.View(false, true, GearPanel.Status.READY, two, 0, now)));
	}
}
