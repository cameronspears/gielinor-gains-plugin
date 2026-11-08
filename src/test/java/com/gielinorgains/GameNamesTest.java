package com.gielinorgains;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import org.junit.Test;

public class GameNamesTest
{
	private static final int TABLET = 8013;
	private final Map<Integer, String> game = new HashMap<>();
	private int lookups;
	private final GameNames names = new GameNames(id ->
	{
		lookups++;
		return game.get(id);
	});

	{
		game.put(TABLET, "Teleport to house");
		game.put(560, "Death rune");
		game.put(1, "null");
	}

	@Test
	public void theGameSpellingWinsOverTheWikis()
	{
		assertEquals("Teleport to house", names.of(TABLET, "Teleport to house (tablet)"));
		assertEquals("Teleport to house", names.of(TABLET, "Teleport to house (tablet)"));
		assertEquals("An item's name is looked up once", 1, lookups);
	}

	@Test
	public void keepsTheGivenNameWhenTheGameHasNone()
	{
		assertEquals("Odd item", names.of(99_999, "Odd item"));
		assertEquals("Odd item", names.of(1, "Odd item"));
		assertEquals("Odd item", names.of(-1, "Odd item"));
	}

	@Test
	public void fixesEveryPickAndSwapLegOncePerFeed()
	{
		PlanFeed.Feed feed = new PlanFeed.Feed();
		feed.picks = new ArrayList<>(Arrays.asList(TraderTest.pick(TABLET, "Teleport to house (tablet)", 400, 500, 100)));
		PlanFeed.Leg leg = new PlanFeed.Leg();
		leg.id = TABLET;
		leg.name = "Teleport to house (tablet)";
		PlanFeed.Swap swap = new PlanFeed.Swap();
		swap.buy = new ArrayList<>(Arrays.asList(leg));
		feed.makeSell = new PlanFeed.MakeSell();
		feed.makeSell.swaps = new ArrayList<>(Arrays.asList(swap));

		assertSame(feed, names.fix(feed));
		assertEquals("Teleport to house", feed.picks.get(0).name);
		assertEquals("Teleport to house", leg.name);

		feed.picks.get(0).name = "changed";
		names.fix(feed);
		assertEquals("The same feed isn't walked again", "changed", feed.picks.get(0).name);
	}

	@Test
	public void fixesThePlanAndReboundFromASync()
	{
		Trader.Reply reply = new Trader.Reply();
		reply.plan = new Trader.Plan();
		Trader.PlanSlot slot = new Trader.PlanSlot();
		slot.item = new Trader.PlanItem();
		slot.item.id = TABLET;
		slot.item.name = "Teleport to house (tablet)";
		reply.plan.slots.add(slot);
		reply.plan.slots.add(new Trader.PlanSlot());
		reply.rebound = new Trader.Rebound();
		reply.rebound.id = 560;
		reply.rebound.name = "Death rune";

		names.fix(reply);
		assertEquals("Teleport to house", slot.item.name);
		assertEquals("Death rune", reply.rebound.name);
	}
}
