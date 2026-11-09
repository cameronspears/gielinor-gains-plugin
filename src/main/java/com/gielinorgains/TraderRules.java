package com.gielinorgains;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import lombok.Value;

/**
 * The few Trader rules the plugin runs itself, ported line for line from the site's
 * lib/plugin/rules.ts and checked against the same cases (trader-rules-v1.json, made from the
 * website's own Trader code). Everything heavy comes worked out in the plan feed.
 */
final class TraderRules
{
	static final long LARGE_PLAN_GP = 50_000_000L;
	private static final long FALLBACK_MAX_CAPITAL = 5_000_000L;
	private static final long BIG_ITEM_MIN_PROFIT = 100_000L;
	private static final long MAX_GE_PRICE = 2_147_483_647L;
	/** A stalled sale is checked after this long without a fill. */
	static final long REPRICE_AFTER_MS = 3 * 60_000L;
	/** A sale keeps its price this long. */
	static final long SELL_PATIENCE_MS = 8 * 3_600_000L;

	private TraderRules()
	{
	}

	/** GE tax: 2% rounded down, at most 5M, none on exempt items. */
	/** A part of a buy is worth selling while the rest buys at this much profit, as on the website. */
	static final long PART_SALE_GP = 25_000;

	/**
	 * Whether to sell the unlisted part of a buy that's still buying: it makes at least
	 * {@link #PART_SALE_GP} after tax, or the buy is 95% bought with a quarter of it still to list.
	 */
	static boolean sellPartOfBuy(long unlisted, long filled, long total, long profitEach)
	{
		return unlisted > 0 && total > 0 && (unlisted * Math.max(0, profitEach) >= PART_SALE_GP
			|| filled * 100 >= total * 95 && unlisted * 4 >= total);
	}

	/** "21 minutes", "2 hours": how long an offer has waited, the same words as the website. */
	static String waitedFor(long ms)
	{
		long minutes = Math.max(0, ms) / 60_000;
		if (minutes < 1)
		{
			return "under a minute";
		}
		if (minutes < 60)
		{
			return minutes + (minutes == 1 ? " minute" : " minutes");
		}
		long hours = minutes / 60;
		return hours + (hours == 1 ? " hour" : " hours");
	}

	static long tax(long price, boolean taxFree)
	{
		return taxFree ? 0 : Math.min(price * 2 / 100, 5_000_000L);
	}

	static long profitEach(long sell, long buy, boolean taxFree)
	{
		return sell - tax(sell, taxFree) - buy;
	}

	/**
	 * Whether to stop adding to a buy (the website's "Stop adding to this trade"): at today's sale price the
	 * buy price leaves no profit after tax. False while today's price isn't known.
	 */
	static boolean stopBuy(long buyPrice, long sellNow, boolean taxFree)
	{
		return buyPrice > 0 && sellNow > 0 && profitEach(sellNow, buyPrice, taxFree) <= 0;
	}

	static String stopBuyWhy(long filled)
	{
		String why = "At today's price, this buy no longer makes a profit after tax. ";
		return filled > 0
			? why + "Cancel the rest of the offer. You keep the " + BoxText.whole(filled)
			+ " you bought, and we'll help you sell them."
			: why + "Cancel the offer and collect your GP.";
	}

	/** Profit after tax if the sale lands half a percent (at least 1 GP) lower. */
	static long reserveProfit(long buy, long sell, boolean taxFree)
	{
		long drop = Math.max(1, (long) Math.ceil(sell * 0.005));
		return profitEach(Math.max(1, sell - drop), buy, taxFree);
	}

	/** The lowest whole-GP sale price that gets back {@code cost} after tax. */
	static long breakEven(long cost, boolean taxFree)
	{
		long low = cost;
		long high = MAX_GE_PRICE;
		while (low < high)
		{
			long mid = low + (high - low) / 2;
			if (profitEach(mid, cost, taxFree) >= 0)
			{
				high = mid;
			}
			else
			{
				low = mid + 1;
			}
		}
		return low;
	}

	/**
	 * The room this player has left on each pick under Trader's shared limit: {@code left} caps a
	 * pick's plan size, and {@code full} picks have none. Made by {@link #room}.
	 */
	@Value
	static class Room
	{
		static final Room NONE = new Room(Collections.emptyMap(), Collections.emptySet());

		Map<Integer, Long> left;
		Set<Integer> full;

		long left(PlanFeed.Pick p)
		{
			return left.getOrDefault(p.id, Long.MAX_VALUE);
		}
	}

	/**
	 * The room left on each pick, as the website's Trader plans with it: other players' plans
	 * this window, less this player's own ({@code own}, units by item id), against {@code players}
	 * times the most one player can take. No room, or one whose window has ended by the server's
	 * clock, leaves every pick as it is.
	 */
	static Room room(List<PlanFeed.Pick> picks, PlanFeed.Room room, Map<Integer, Long> own, long serverNow)
	{
		if (room == null || room.reserved == null || serverNow >= room.ends)
		{
			return Room.NONE;
		}
		Map<Integer, Long> left = new HashMap<>();
		Set<Integer> full = new HashSet<>();
		for (PlanFeed.Pick p : picks)
		{
			Long planned = room.reserved.get(String.valueOf(p.id));
			long reserved = Math.max(0, (planned == null ? 0 : planned) - own.getOrDefault(p.id, 0L));
			if (reserved == 0)
			{
				continue;
			}
			long free = room.players * Math.min(p.qtyLarge, Math.max(0, p.qtyLimit)) - reserved;
			if (free < 1)
			{
				full.add(p.id);
			}
			else if (free < p.qtyLarge)
			{
				left.put(p.id, free);
			}
		}
		return new Room(left, full);
	}

	static final String NO_ROOM = "No automatic pick has room right now.";

	/** The website's note when no automatic pick has room, with when room opens in the player's time. */
	static String roomNotice(int full, long opensAt, ZoneId zone)
	{
		String time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(opensAt).atZone(zone));
		return NO_ROOM + " Other Trader players have already planned as much of "
			+ (full == 1 ? "one item" : full + " items") + " as the market can take. Room opens again at " + time
			+ ". You can choose items yourself until then.";
	}

	/**
	 * The GP one automatic pick may use: what's left, but no more than {@code share} of the whole
	 * plan's GP (the website's pickBudget, a fifth since #636). A null share leaves it uncapped.
	 */
	static long pickBudget(long left, long planningGp, Double share)
	{
		if (share == null || !(share > 0) || planningGp <= 0)
		{
			return left;
		}
		return Math.min(left, (long) Math.floor(planningGp * share));
	}

	/** A pick's plan size: the large-plan size at 50M or more, within the shared limit's room. */
	private static long planQty(PlanFeed.Pick p, long planningGp, long roomLeft)
	{
		return Math.min(planningGp >= LARGE_PLAN_GP ? Math.max(p.qty, p.qtyLarge) : p.qty, roomLeft);
	}

	/** How many to buy of an ordinary pick for a free slot, or 0 when Trader wouldn't pick it. */
	static long fitPick(PlanFeed.Pick p, long budget, long planningGp, long limitLeft, long roomLeft)
	{
		if (p.minQty <= 0 || budget < p.buy)
		{
			return 0;
		}
		long q = Math.min(Math.min(planQty(p, planningGp, roomLeft), p.qtyLimit), Math.min(limitLeft, budget / p.buy));
		if (q < 1)
		{
			return 0;
		}
		boolean preferred = p.isPreferred() && (!p.isBig() || (q <= 3 && p.reserveEach * q >= BIG_ITEM_MIN_PROFIT));
		if (!preferred)
		{
			q = Math.min(q, Math.min(budget, FALLBACK_MAX_CAPITAL) / p.buy);
		}
		return q >= p.minQty && q <= p.maxQty ? q : 0;
	}

	/** How many to buy of a proven flip for a slot nothing else fills, or 0. */
	static long fitProven(PlanFeed.Pick p, long budget, long planningGp, long limitLeft, long roomLeft)
	{
		if (p.proven == null || budget < p.buy || p.reserveEach <= 0)
		{
			return 0;
		}
		long q = Math.min(Math.min(planQty(p, planningGp, roomLeft), p.qtyLimit), Math.min(limitLeft, budget / p.buy));
		return q >= 1 && q <= p.thin ? q : 0;
	}

	@Value
	static class Fill
	{
		int id;
		long qty;
		boolean proven;
	}

	/**
	 * The pick for one free slot: the first ordinary pick that fits, preferring the safer tier, in
	 * the feed's order; else the best proven flip that fits. {@code limitLeft} is the GE limit left
	 * for an item after this account's buys in the last 4 hours. A pick with no shared-limit room
	 * is passed over, and one with a little is sized down to it ({@code room}).
	 */
	static Fill choose(List<PlanFeed.Pick> picks, long budget, long planningGp, Set<Integer> taken,
		ToIntFunction<PlanFeed.Pick> limitLeft, boolean members, Room room)
	{
		List<PlanFeed.Pick> open = new ArrayList<>();
		for (PlanFeed.Pick p : picks)
		{
			if (!taken.contains(p.id) && (members || !p.members) && !room.getFull().contains(p.id))
			{
				open.add(p);
			}
		}
		Fill first = null;
		for (PlanFeed.Pick p : open)
		{
			long qty = fitPick(p, budget, planningGp, limitLeft.applyAsInt(p), room.left(p));
			if (qty <= 0)
			{
				continue;
			}
			if (p.isPreferred() && (!p.isBig() || qty <= 3))
			{
				return new Fill(p.id, qty, false);
			}
			if (first == null)
			{
				first = new Fill(p.id, qty, false);
			}
		}
		if (first != null)
		{
			return first;
		}
		open.sort(Comparator.comparingInt(p -> p.proven == null ? Integer.MAX_VALUE : p.proven));
		for (PlanFeed.Pick p : open)
		{
			long qty = fitProven(p, budget, planningGp, limitLeft.applyAsInt(p), room.left(p));
			if (qty > 0)
			{
				return new Fill(p.id, qty, true);
			}
		}
		return null;
	}

	@Value
	static class Reprice
	{
		long price;
		long profitEach;
	}

	/**
	 * A sale's new price (the 8-hour rule): raised at once if it's listed at a loss and the market
	 * is higher; otherwise lowered only after 8 hours listed and stalled, and never below break-even
	 * unless it's already there. Null to keep the price.
	 */
	static Reprice repriceSale(long price, long filled, long total, long listedAt, long cost, long target,
		long stalledMs, long now, boolean taxFree)
	{
		if (filled >= total || cost <= 0 || target <= 0)
		{
			return null;
		}
		long minimum = Math.max(1, (long) Math.ceil(price * 0.001));
		if (profitEach(price, cost, taxFree) < 0 && target - price >= minimum)
		{
			return new Reprice(target, profitEach(target, cost, taxFree));
		}
		if (stalledMs < REPRICE_AFTER_MS || now - listedAt < SELL_PATIENCE_MS)
		{
			return null;
		}
		long floor = breakEven(cost, taxFree);
		long next = price > floor ? Math.max(target, floor) : target;
		if (price - next < minimum)
		{
			return null;
		}
		return new Reprice(next, profitEach(next, cost, taxFree));
	}
}
