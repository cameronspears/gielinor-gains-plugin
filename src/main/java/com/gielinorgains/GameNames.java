package com.gielinorgains;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Item names as the game spells them. The website's names come from the OSRS Wiki, which adds words
 * to tell its pages apart ("Teleport to house (tablet)" for the game's "Teleport to house"). The
 * GE search can't find those, and they read wrong in game, so Trader swaps in the game's name by
 * item id as the website's plan and picks arrive. Client thread only.
 */
final class GameNames
{
	/** The game's name for an id, or null when it has none (the cache isn't loaded, or no such item). */
	private final IntFunction<String> lookup;
	private final Map<Integer, String> names = new HashMap<>();
	/** The last plan feed fixed, so each new feed is walked once. */
	private PlanFeed.Feed fixed;

	GameNames(IntFunction<String> lookup)
	{
		this.lookup = lookup;
	}

	/** The game's name for an item, else the name given. Remembered: an item's name never changes. */
	String of(int id, String given)
	{
		if (id <= 0)
		{
			return given;
		}
		String name = names.get(id);
		if (name == null)
		{
			String looked = lookup.apply(id);
			if (looked == null || looked.isEmpty() || looked.equals("null"))
			{
				return given;
			}
			name = looked;
			names.put(id, name);
		}
		return name;
	}

	/** The feed with every item's name in the game's spelling; walked once per feed. */
	PlanFeed.Feed fix(PlanFeed.Feed feed)
	{
		if (feed == null || feed == fixed)
		{
			return feed;
		}
		fixed = feed;
		if (feed.picks != null)
		{
			for (PlanFeed.Pick pick : feed.picks)
			{
				pick.name = of(pick.id, pick.name);
			}
		}
		if (feed.makeSell != null && feed.makeSell.swaps != null)
		{
			for (PlanFeed.Swap swap : feed.makeSell.swaps)
			{
				fix(swap);
			}
		}
		return feed;
	}

	/** A Make and Sell swap's legs in the game's spelling. */
	void fix(PlanFeed.Swap swap)
	{
		if (swap != null)
		{
			fix(swap.buy);
			fix(swap.sell);
		}
	}

	/** The website's plan and Rebound in the game's spelling. */
	void fix(Trader.Reply reply)
	{
		if (reply == null)
		{
			return;
		}
		if (reply.plan != null && reply.plan.slots != null)
		{
			for (Trader.PlanSlot slot : reply.plan.slots)
			{
				if (slot != null && slot.item != null)
				{
					slot.item.name = of(slot.item.id, slot.item.name);
				}
			}
		}
		if (reply.rebound != null)
		{
			reply.rebound.name = of(reply.rebound.id, reply.rebound.name);
		}
	}

	private void fix(List<PlanFeed.Leg> legs)
	{
		if (legs != null)
		{
			for (PlanFeed.Leg leg : legs)
			{
				leg.name = of(leg.id, leg.name);
			}
		}
	}
}
