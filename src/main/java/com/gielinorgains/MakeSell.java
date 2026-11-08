package com.gielinorgains;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.Value;

/**
 * Make and Sell for Trader's overflow, as the website's Trader offers it (lib/makeAndSell/overflow.ts
 * and MakeSellOfferCard.tsx): when no automatic pick has room, the best swap with room left this
 * 4-hour window, sized to the player's free GP. The plan feed brings the swaps; taking one is one
 * request to the website, which keeps the room.
 */
final class MakeSell
{
	// The website's words (lib/makeAndSell/overflowCopy.ts), in ASCII; GoldenStringsTest checks them.
	static final String HEADING = "Make and Sell has room";
	static final String SUMMARY = "Trader's picks are full right now.";
	static final String EXPLAINER = "You can still use your GP. Buy on the GE, change it in the game, then sell.";
	static final String PLANNED = "Planned at today's prices, after tax";
	static final String GONE = "Prices moved and this swap no longer pays. Sell what you bought at the market price.";
	static final String DONE_TITLE = "Nice work.";
	static final String DONE_MORE = "Want more? You can pick any swap yourself in Make and Sell.";
	static final String FULL_TITLE = "Make and Sell is full too.";
	static final String ERROR_WINDOW = "Room just reset for the next 4 hours. Try again in a moment.";
	static final String ERROR_GONE = "Prices moved and that swap stopped paying. Here's the next one.";
	static final String ERROR_FULL = "That swap just filled up. Here's the next one with room.";
	static final String ERROR_SAVE = "We couldn't save that. Check your connection and try again.";
	static final String HREF = TraderApi.SITE + "/?view=make-and-sell";

	static String empty(String time)
	{
		return "Room opens again at " + time + ". You can still pick any swap yourself in Make and Sell.";
	}

	static String opens(String time)
	{
		return "New room opens at " + time + ".";
	}

	/** Fixed 4-hour UTC windows, the research's decision hours. */
	static final long WINDOW_MS = 4 * 60 * 60 * 1000L;
	/** What a batch must still make to be offered, as Make and Sell lists it. */
	static final long MIN_OFFER_PROFIT = 1_000;
	/** The research gave each player an hour of play a day and 8 GE slots. */
	private static final double PLAY_PER_DAY_SECONDS = 3_600;
	private static final double OVERHEAD_SECONDS = 180;
	private static final int GE_SLOTS = 8;

	static long window(long serverNow)
	{
		return Math.floorDiv(serverNow, WINDOW_MS);
	}

	static long windowEnd(long window)
	{
		return (window + 1) * WINDOW_MS;
	}

	/** A swap the player can take now: its size, planned profit, cost and play. */
	@Value
	static class Offer
	{
		PlanFeed.Swap swap;
		long units;
		long profit;
		long cost;
		long playSeconds;
	}

	/** A swap the player took, as GET and POST /api/trader/make-sell answer it. */
	static final class Claim
	{
		long window;
		String id;
		long units;
		/** Unix ms on the server's clock. */
		long at;
	}

	/** GET /api/trader/make-sell: whether this player is routed, and what they took. */
	static final class Status
	{
		Boolean on;
		Claim claim;
	}

	/** POST's answer: the claim, or why not ("window", "gone" or "full"). */
	static final class Taken
	{
		Claim claim;
		String error;
	}

	/**
	 * Swaps the player can take, best first: each within the room the feed gives and the free GP,
	 * worth at least 1,000 GP, members' swaps left out on a free world. The website's openSwaps
	 * with a budget, ranked by the same offerRate.
	 */
	static List<Offer> open(PlanFeed.MakeSell feed, long freeGp, boolean freeWorld, Collection<String> skipped)
	{
		List<Offer> open = new ArrayList<>();
		if (feed == null || feed.swaps == null)
		{
			return open;
		}
		for (PlanFeed.Swap swap : feed.swaps)
		{
			if (swap == null || swap.id == null || skipped.contains(swap.id) || freeWorld && swap.members
				|| !(swap.each > 0) || !(swap.cost > 0) || swap.buy == null || swap.sell == null)
			{
				continue;
			}
			long units = Math.min(swap.units, (long) Math.floor(Math.max(0, freeGp) / swap.cost));
			if (units < 1 || units * swap.each < MIN_OFFER_PROFIT)
			{
				continue;
			}
			open.add(new Offer(swap, units, (long) Math.floor(units * swap.each), (long) Math.ceil(units * swap.cost),
				Math.round(playFor(swap, units))));
		}
		open.sort((a, b) ->
		{
			int byRate = Double.compare(rate(b.swap, b.units), rate(a.swap, a.units));
			return byRate != 0 ? byRate : a.swap.id.compareTo(b.swap.id);
		});
		return open;
	}

	/** Play for {@code units} conversions, overhead included, from the feed's play for its own units. */
	static double playFor(PlanFeed.Swap swap, long units)
	{
		if (swap.units <= 0)
		{
			return OVERHEAD_SECONDS;
		}
		return OVERHEAD_SECONDS + Math.max(0, swap.play - OVERHEAD_SECONDS) / swap.units * units;
	}

	/** The research's routing order: planned profit per share of a day's play and GE slots. */
	static double rate(PlanFeed.Swap swap, long units)
	{
		int ge = 0;
		boolean instant = false;
		for (PlanFeed.Leg leg : swap.buy)
		{
			if (leg.isInstant())
			{
				instant = true;
			}
			else
			{
				ge++;
			}
		}
		int slots = Math.max(Math.max(ge + (instant ? 1 : 0), swap.sell.size()), 1);
		double slotHours = slots * (swap.sell.isEmpty() ? 4 : 28);
		double use = playFor(swap, units) / PLAY_PER_DAY_SECONDS;
		return swap.each * units / (use + slotHours / (GE_SLOTS * 24));
	}

	/** How many of an item one leg needs for {@code units} conversions. */
	static long need(PlanFeed.Leg leg, long units)
	{
		return (long) Math.ceil(leg.qty * units);
	}

	/** "Buy 1,000 x Nature rune at 95 GP each (it buys at once)", the website's step in ASCII. */
	static String legText(PlanFeed.Leg leg, boolean buy, long units)
	{
		return (buy ? "Buy " : "Sell ") + BoxText.whole(need(leg, units)) + " x " + leg.name + " at "
			+ BoxText.whole(leg.price) + " GP each" + (leg.isInstant() ? " (it buys at once)" : "");
	}

	/** The swap's steps as the website lists them: each buy, the in-game step, then each sale or the coins. */
	static List<String> steps(PlanFeed.Swap swap, long units)
	{
		List<String> steps = new ArrayList<>();
		for (PlanFeed.Leg leg : swap.buy)
		{
			steps.add(legText(leg, true, units));
		}
		steps.add(swap.step);
		if (swap.sell.isEmpty())
		{
			steps.add(keepCoins(swap, units));
		}
		for (PlanFeed.Leg leg : swap.sell)
		{
			steps.add(legText(leg, false, units));
		}
		return steps;
	}

	static String keepCoins(PlanFeed.Swap swap, long units)
	{
		return "Keep the coins: " + BoxText.whole(swap.coins * units) + " GP.";
	}

	/** "about 25 min", or "about 1.5 h", as the website rounds it. */
	static String minutes(long seconds)
	{
		long value = Math.max(1, Math.round(seconds / 60.0));
		if (value < 60)
		{
			return "about " + value + " min";
		}
		long tenths = Math.round(value / 6.0);
		return "about " + tenths / 10 + (tenths % 10 == 0 ? "" : "." + tenths % 10) + " h";
	}

	/** The item a swap shows: the one it changes, not a rune bought at once. */
	static PlanFeed.Leg art(PlanFeed.Swap swap)
	{
		for (PlanFeed.Leg leg : swap.buy)
		{
			if (!leg.isInstant())
			{
				return leg;
			}
		}
		return swap.buy.isEmpty() ? null : swap.buy.get(0);
	}

	/** "Make and Sell: Alch Rune platebody", under the item in the GE box. */
	static String boxNote(PlanFeed.Swap swap)
	{
		return "Make and Sell: " + swap.title;
	}

	// --- This session's state, on the client thread ---

	/** Whether this player is routed; null until the website has said. */
	Boolean eligible;
	boolean asked;
	Claim claim;
	/** The swap the claim is for, kept from when it was taken: a fully taken swap leaves the feed. */
	PlanFeed.Swap swap;
	/** The window the player marked done, or -1. */
	long doneWindow = -1;
	boolean busy;
	String error;
	final List<String> skipped = new ArrayList<>();

	/** The claim for this window, or null. */
	Claim claim(long window)
	{
		return claim != null && claim.window == window ? claim : null;
	}

	/** The taken swap's row, or null when it's gone from the feed and wasn't kept. */
	PlanFeed.Swap taken(PlanFeed.MakeSell feed)
	{
		if (claim == null)
		{
			return null;
		}
		if (feed != null && feed.swaps != null)
		{
			for (PlanFeed.Swap row : feed.swaps)
			{
				if (row != null && claim.id.equals(row.id))
				{
					return row;
				}
			}
		}
		return swap != null && claim.id.equals(swap.id) ? swap : null;
	}
}
