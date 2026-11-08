package com.gielinorgains;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * Trader in the game: the account's saved plan, the GE as the game shows it, and the one next
 * step. It runs the website's rules (TraderRules) on the website's picks (PlanFeed), saves the
 * plan and offers to the account, and links trades to the picks they came from, as Trader on the
 * website does. It only reads the game; the player does every click and key press.
 *
 * Client thread only. Pure apart from the clock it's given, so the tests drive it directly.
 */
class Trader
{
	static final String GUIDANCE_VERSION = "runelite-plugin-v1";
	static final String SELECTION_VERSION = "plan-feed-v1";
	/** A sync waits at least this long after the last, unless a new pick needs linking. */
	static final long SYNC_GAP_MS = 3 * 60_000L;
	/** Why the plan is empty when prices are fresh: the budget or slots leave no pick that fits. */
	static final String NO_PICK = "No pick fits your budget and slots.";
	/** Even with nothing new, a sync this often tells the website the plugin is running. */
	static final long HEARTBEAT_MS = 10 * 60_000L;
	private static final long FOUR_HOURS_MS = 4 * 3_600_000L;
	private static final int MAX_TRADES = 400;
	/** The website takes a new pick link only within a minute of showing it; this leaves a margin. */
	private static final long LINK_WINDOW_MS = 50_000L;

	// --- What the account keeps, in the shapes lib/plugin/sync.ts uses ---

	static final class PlanItem
	{
		int id;
		String name;
		long qty;
		long buy;
		long sell;
		String origin;
		Boolean rebound;
		/** A Rebound's drop, in ms: its plan's day to buy and 2 days to sell count from here. */
		Long reboundAt;
	}

	static final class PlanSlot
	{
		int slot;
		PlanItem item;
	}

	static final class Plan
	{
		int revision;
		List<PlanSlot> slots = new ArrayList<>();
		Long budget;
		List<Integer> rejected = new ArrayList<>();
	}

	/** The collect step's title after a sale, when coins wait in the slot. */
	static final String COLLECT_COINS = "Collect your coins";

	static final class Rebound
	{
		String key;
		int id;
		String name;
		double drop;
		long buy;
		long sell;
		long qty;
		long profitIfAllSells;
		Long detectedAt;
	}

	@lombok.EqualsAndHashCode
	static final class Today
	{
		long profit;
		int flips;
		/** Of the profit, what's from items still selling: not saved yet (todaySoFar). */
		long selling;
	}

	static final class Offer
	{
		int slot;
		int itemId;
		long filled;
		long total;
		long price;
		long spent;
		String state;
		Long startedAt;
		Long listedAt;
	}

	static final class Trade
	{
		boolean buy;
		int itemId;
		long quantity;
		double price;
		long time;
	}

	static final class Observation
	{
		String id;
		int itemId;
		String origin;
		String selectionVersion = SELECTION_VERSION;
		String guidanceVersion = GUIDANCE_VERSION;
		long shownAt;
		long quantity;
		long buyPrice;
		long sellPrice;
		String stage = "shown";
		Long placedAt;
		Long buyTotal;
		Long purchaseTime;
		String roundId;
		Long closedAt;
		/** The account has this link, so later stages may follow; until then only "shown" may go. */
		transient boolean registered;
		/** Not registered within the minute the website allows, so never sent. */
		transient boolean dead;
	}

	/**
	 * What's kept for each game account in our own RuneLite settings, as RuneLite's Grand Exchange
	 * plugin keeps its trade history, so a restart loses no trade or pick link.
	 */
	static final class Saved
	{
		int v = 1;
		List<Trade> trades;
		List<Observation> links;
		List<String> registered;
		List<String> dead;
		List<String> unsent;
		Offer[] offers;
		long trackingSince;
		List<String> dismissed;
		/** Coins and platinum tokens in the bank when it was last open, so Trader can plan before it opens. */
		Long bankGp;
	}

	/** Progress in short, as lib/plugin/progressSummary.ts sends it: the website's own figures. */
	@lombok.EqualsAndHashCode
	static final class Summary
	{
		boolean enabled;
		/** Recorded net profit, all time, after estimated tax. */
		long profit;
		int trades;
		/** The last 14 UTC days, oldest first. */
		List<Day> days;
		Achievements achievements;
		/** Of the profit, what's from items still selling: not saved yet (progressSoFar). */
		long selling;
	}

	@lombok.EqualsAndHashCode
	static final class Day
	{
		String day;
		long profit;
		int trades;
		/** Of the day's profit, what's from items still selling. */
		long selling;
	}

	@lombok.EqualsAndHashCode
	static final class Achievements
	{
		int earned;
		int total;
		Achievement latest;
		Achievement next;
	}

	@lombok.EqualsAndHashCode
	static final class Achievement
	{
		String id;
		String title;
		String text;
		/** The UTC day it was earned; the latest only. */
		String earnedAt;
		/** How far along it is; the next only. */
		long current;
		long target;
	}

	static final class Reply
	{
		Plan plan;
		Summary summary;
		Rebound rebound;
		List<Trade> trades;
		List<Offer> offers;
		Today today;
		/** How saving Progress went: "saved", or why not ("unavailable" and "conflict" pass). */
		String progress;
		String error;
	}

	// --- The next step ---

	enum Kind
	{
		/** Set a budget first. */
		BUDGET,
		BUY,
		COLLECT,
		SELL,
		LOWER,
		/** A Rebound's day to buy is over: cancel the rest of the buy. */
		STOP,
		/** Offers are in; nothing to do but wait. */
		WAIT,
		/** A Make and Sell swap's step in the game, between its buys and its sales. */
		MAKE,
		/** Nothing to trade right now. */
		EMPTY,
		/** Our prices are too old to act on. */
		STALE
	}

	/** How a status reads at a glance; the words always say the same thing. */
	enum Tone
	{
		/** Under way: an offer filling, a step being watched. */
		BLUE,
		/** Making money: a sale listed. */
		GREEN,
		/** Needs you: something to collect or a price to check. */
		AMBER,
		/** Waiting its turn. */
		GREY
	}

	@Value
	@AllArgsConstructor
	static class Step
	{
		Kind kind;
		/** "Buy Cannonball". */
		String title;
		/** "11,000 at 259 each · GE slot 1". */
		String detail;
		/** What Trader is watching for, like "Watching for your buy in slot 1"; null for none. */
		String status;
		Tone tone;
		int itemId;
		String name;
		long qty;
		long price;
		/** The GE slot to point at, or -1. */
		int geSlot;
		/** Identifies this step for "Already done" and "Keep my current price". */
		String key;
		/** For a Make and Sell buy, what comes after it fills, in the box: "Cast High Level Alchemy on each one."; else null. */
		String then;
	}

	/** One plan slot as Your slots shows it. */
	@Value
	static class SlotRow
	{
		String name;
		int itemId;
		String status;
		Tone tone;
		long expected;
		/** GP the plan puts into it. */
		long cost;
		/** The slot's plan, label and value, in the website's words: prices, how much so far, profit. */
		List<Fact> plan;
		/** What happens next, in a sentence or two. */
		String note;
	}

	/** One line of a slot's plan: "Sell · each" and "280 GP". */
	@Value
	static class Fact
	{
		String label;
		String value;
	}

	/** A buy that filled or a flip that finished, for a moment of celebration. */
	@Value
	static class Moment
	{
		boolean sale;
		int itemId;
		String name;
		long qty;
		long price;
		/** After tax, against what the items cost; sales only. */
		long profit;
		/** How long the offer took to fill, or -1 when unknown. */
		long tookMs;
		int geSlot;
		/** Whether the profit is known: what was paid is on record for the whole sale. */
		boolean known;
		/** A Rebound, which gets its own name when it finishes. */
		boolean rebound;

		/** "+72,000 GP" when the profit is known, else what sold: "Sold 18,000". */
		String profitText()
		{
			return known ? (profit >= 0 ? "+" : "") + BoxText.whole(profit) + " GP" : "Sold " + BoxText.whole(qty);
		}
	}

	/** The label inside a GE slot. */
	@Value
	static class Badge
	{
		String text;
		Tone tone;
		/** The share of a sale's 8 hours still to go, from 1 to 0, or -1 for no ring. */
		double patience;
	}

	private final Gson gson;
	private Plan plan;
	private boolean planChanged;
	/** Counts plan edits, so a reply can tell whether the plan changed while its request was out. */
	private int planEdits;
	private int sentEdits;
	/** Whether the request in flight carried the plan, and the items it carried. */
	private boolean sentPlan;
	private final Set<Integer> sentIds = new HashSet<>();
	/**
	 * Picks the website saved as empty because they'd left its live list. Our feed may still have
	 * them for a minute or two, so they aren't picked again until it updates.
	 */
	private final Set<Integer> unlisted = new HashSet<>();
	private Long unlistedFeed;
	private Long feedAt;
	private boolean urgent;
	private boolean offersChanged;
	private boolean first = true;
	private boolean syncing;
	private long lastSync;
	private long retryAt;
	private final Offer[] offers = new Offer[8];
	private final long[] slotUpdatedAt = new long[8];
	private final long[] lastProgressAt = new long[8];
	/**
	 * A new price Trader asked for, kept a while after its step leaves the screen. The game's Modify
	 * cancels the offer and empties the slot before the new one is placed, so without it the next
	 * step would fall back to the plan's old price, or offer to sell a partly filled buy.
	 */
	private int changingItem = -1;
	private long changingPrice;
	private long changingUntil;
	static final long CHANGING_MS = 10 * 60_000L;
	private final List<Trade> trades = new ArrayList<>();
	private long trackingSince;
	/** Pick links by item, newest pick of each item, and which changed since the last sync. */
	private final Map<Integer, Observation> links = new LinkedHashMap<>();
	private final Set<String> unsent = new HashSet<>();
	/** The stage each link had in the request in flight. */
	private final Map<String, String> inFlight = new HashMap<>();
	/** Offers seen saved on the account (first sync), for when a sale was listed. */
	private final Map<String, Long> savedListedAt = new HashMap<>();
	private Today today;
	/** When the request behind the last saved Progress was sent: trades up to then are in its figures. */
	private long savedThrough;
	/** When the request in flight was sent, if it carried trades; else 0. */
	private long sentTradesAt;
	private Rebound rebound;
	private String profileId;
	private boolean members = true;
	/** Steps the player said were already done, or whose price they kept, by step key. */
	private final Set<String> dismissed = new HashSet<>();
	private final List<Moment> moments = new ArrayList<>();
	private Summary summary;
	private final List<Achievement> earned = new ArrayList<>();
	/** Whether the game has sent any offer since this account was loaded. */
	private boolean sawOffers;

	Trader(Gson gson, long now)
	{
		this.gson = gson;
		this.trackingSince = now;
	}

	Plan plan()
	{
		return plan;
	}

	Today today()
	{
		return today;
	}

	/** Progress in short, or null before a sync has saved any. */
	Summary summary()
	{
		return summary;
	}

	/** Today so far with the profit on what's sold of items still selling added, or null before Progress is saved. */
	Today todaySoFar()
	{
		long selling = today == null ? 0 : unsavedProfit();
		if (selling == 0)
		{
			return today;
		}
		Today t = new Today();
		t.profit = today.profit + selling;
		t.flips = today.flips;
		t.selling = selling;
		return t;
	}

	/** Progress in short with the same added to the total and today's bar, or null before Progress is saved. */
	Summary progressSoFar(long now)
	{
		long selling = summary == null || !summary.enabled ? 0 : unsavedProfit();
		if (selling == 0)
		{
			return summary;
		}
		Summary s = new Summary();
		s.enabled = summary.enabled;
		s.profit = summary.profit + selling;
		s.trades = summary.trades;
		s.achievements = summary.achievements;
		s.selling = selling;
		if (summary.days != null)
		{
			// The website's days are UTC days, and a trade counts on the day it finishes: today.
			String day = java.time.LocalDate.ofEpochDay(Math.floorDiv(now, 86_400_000L)).toString();
			s.days = new ArrayList<>();
			for (Day d : summary.days)
			{
				if (!day.equals(d.day))
				{
					s.days.add(d);
					continue;
				}
				Day live = new Day();
				live.day = d.day;
				live.profit = d.profit + selling;
				live.trades = d.trades;
				live.selling = selling;
				s.days.add(live);
			}
		}
		return s;
	}

	/**
	 * Profit on what's sold that the website's figures don't have yet. The website counts a trade
	 * once everything bought has sold, so until then this is the part that has: from a sale still in
	 * the GE (collected or not), and from finished sales of an item still being sold. A trade that just
	 * finished counts here until a save that carried it comes back. Only from what the GE says was paid
	 * and got: a sale without a record of its whole buy adds nothing, as the website can't price it either.
	 */
	long unsavedProfit()
	{
		Set<Integer> items = new LinkedHashSet<>();
		for (Offer o : offers)
		{
			if (o != null && o.state.equals("SELLING"))
			{
				items.add(o.itemId);
			}
		}
		for (Trade t : trades)
		{
			if (!t.buy && t.time >= trackingSince)
			{
				items.add(t.itemId);
			}
		}
		long profit = 0;
		for (int itemId : items)
		{
			profit += unsavedProfit(itemId);
		}
		return profit;
	}

	private long unsavedProfit(int itemId)
	{
		long held = 0;
		long cost = 0;
		// Sold in the trade still going, and in trades that finished after the last save came back.
		long open = 0;
		long finished = 0;
		for (Trade t : trades)
		{
			if (t.itemId != itemId || t.time < trackingSince)
			{
				continue;
			}
			if (t.buy)
			{
				held += t.quantity;
				cost += Math.round(t.price * t.quantity);
				continue;
			}
			if (t.quantity > held)
			{
				// More sold than recorded bought: the website can't price this trade, so neither do we.
				held = 0;
				cost = 0;
				open = 0;
				continue;
			}
			long basis = share(cost, t.quantity, held);
			open += Math.round(t.price * t.quantity) - TraderRules.tax(Math.round(t.price), false) * t.quantity - basis;
			cost -= basis;
			held -= t.quantity;
			if (held == 0)
			{
				// Everything bought has sold: the website counts it once a save carrying this sale comes back.
				finished += savedThrough > 0 && t.time > savedThrough ? open : 0;
				open = 0;
			}
		}
		Offer buying = openBuy(itemId);
		if (buying != null && buying.filled > 0)
		{
			// What a buy still open has bought can be sold while the rest buys.
			held += buying.filled;
			cost += buying.spent > 0 ? buying.spent : buying.filled * buying.price;
		}
		boolean selling = false;
		for (Offer o : offers)
		{
			if (o == null || o.itemId != itemId || !o.state.equals("SELLING"))
			{
				continue;
			}
			selling = true;
			if (o.filled == 0)
			{
				continue;
			}
			if (o.filled > held)
			{
				return finished;
			}
			long got = o.spent > 0 ? o.spent : o.filled * o.price;
			long basis = share(cost, o.filled, held);
			open += got - TraderRules.tax(got / o.filled, false) * o.filled - basis;
			cost -= basis;
			held -= o.filled;
		}
		// A sale taken down to change its price is still being sold while the item is in the plan.
		return finished + (selling || planItem(itemId) != null ? open : 0);
	}

	/** {@code part} of {@code whole}'s cost; in doubles, as cost times quantity can pass a long. */
	private static long share(long cost, long part, long whole)
	{
		return Math.round((double) cost * part / whole);
	}

	/** Achievements earned since the last call, newest last. */
	List<Achievement> takeEarned()
	{
		List<Achievement> taken = new ArrayList<>(earned);
		earned.clear();
		return taken;
	}

	Rebound rebound()
	{
		return rebound;
	}

	/**
	 * The game account's id as the website knows it from RuneLite's save file: ConfigManager gives
	 * "rsprofile.y3QWg8Nf", and the save names it "y3QWg8Nf". Null when it isn't one.
	 */
	static String profileId(String rsProfileKey)
	{
		String id = rsProfileKey == null ? null : rsProfileKey.substring(rsProfileKey.lastIndexOf('.') + 1);
		return id != null && id.matches("[\\w-]{4,40}") ? id : null;
	}

	/** RuneLite's id for the logged-in game account, and whether this world is members. */
	void setAccount(String profileId, boolean members)
	{
		if (profileId != null && !profileId.equals(this.profileId))
		{
			if (this.profileId != null)
			{
				// Another game account: its trades and links are its own.
				trades.clear();
				savedThrough = 0;
				links.clear();
				unsent.clear();
				dismissed.clear();
				packGp = -1;
				bankGp = -1;
				java.util.Arrays.fill(offers, null);
				sawOffers = false;
			}
			this.profileId = profileId;
			first = true;
		}
		this.members = members;
	}

	/** This game account's records as JSON, for our own settings; null before an account is known. */
	String save()
	{
		if (profileId == null)
		{
			return null;
		}
		Saved s = new Saved();
		s.trades = trades;
		s.links = new ArrayList<>(links.values());
		s.registered = new ArrayList<>();
		s.dead = new ArrayList<>();
		for (Observation link : links.values())
		{
			if (link.registered)
			{
				s.registered.add(link.id);
			}
			if (link.dead)
			{
				s.dead.add(link.id);
			}
		}
		s.unsent = new ArrayList<>(unsent);
		s.offers = offers;
		s.trackingSince = trackingSince;
		s.dismissed = new ArrayList<>(dismissed);
		s.bankGp = bankGp >= 0 ? bankGp : null;
		return gson.toJson(s);
	}

	/**
	 * Brings back this account's records after a restart. An offer that was in when RuneLite closed
	 * and is done now filled while it was closed: it's recorded as a trade, with what the GE says it
	 * cost, so its profit is exact.
	 */
	void load(String json, long now)
	{
		Saved s;
		try
		{
			s = json == null ? null : gson.fromJson(json, Saved.class);
		}
		catch (RuntimeException e)
		{
			s = null;
		}
		if (s == null || s.v != 1)
		{
			return;
		}
		Set<String> have = new HashSet<>();
		for (Trade t : trades)
		{
			have.add(t.itemId + ":" + t.buy + ":" + t.time);
		}
		List<Trade> merged = new ArrayList<>();
		for (Trade t : s.trades == null ? new ArrayList<Trade>() : s.trades)
		{
			if (have.add(t.itemId + ":" + t.buy + ":" + t.time))
			{
				merged.add(t);
			}
		}
		merged.addAll(trades);
		merged.sort(java.util.Comparator.comparingLong(t -> t.time));
		trades.clear();
		trades.addAll(merged.subList(Math.max(0, merged.size() - MAX_TRADES), merged.size()));
		trackingSince = Math.min(trackingSince, s.trackingSince > 0 ? s.trackingSince : trackingSince);
		if (bankGp < 0 && s.bankGp != null && s.bankGp >= 0)
		{
			bankGp = s.bankGp;
		}
		Set<String> registered = new HashSet<>(s.registered == null ? new ArrayList<String>() : s.registered);
		Set<String> dead = new HashSet<>(s.dead == null ? new ArrayList<String>() : s.dead);
		for (Observation link : s.links == null ? new ArrayList<Observation>() : s.links)
		{
			if (link != null && !links.containsKey(link.itemId))
			{
				link.registered = registered.contains(link.id);
				link.dead = dead.contains(link.id);
				links.put(link.itemId, link);
			}
		}
		if (s.unsent != null)
		{
			for (String id : s.unsent)
			{
				for (Observation link : links.values())
				{
					if (link.id.equals(id))
					{
						unsent.add(id);
					}
				}
			}
		}
		if (s.dismissed != null)
		{
			dismissed.addAll(s.dismissed);
		}
		for (int slot = 0; s.offers != null && slot < Math.min(s.offers.length, offers.length); slot++)
		{
			Offer was = s.offers[slot];
			if (was == null)
			{
				continue;
			}
			if (!sawOffers)
			{
				// The game's login burst compares with these, as it would with offers seen live.
				offers[slot] = was;
				continue;
			}
			Offer is = offers[slot];
			boolean active = was.state.equals("BUYING") || was.state.equals("SELLING");
			if (is != null && active && key(is).equals(key(was)) && done(is) && is.filled > was.filled)
			{
				addTrade(is, now);
			}
		}
	}

	private int usableSlots()
	{
		return members ? 8 : 3;
	}

	// --- Reading the GE ---

	/**
	 * One GE slot as the game shows it. {@code live} is false for the burst of offers the game
	 * sends at login, which aren't new events.
	 */
	void onOffer(int slot, int itemId, String state, long filled, long total, long price, long spent, long now, boolean live)
	{
		if (slot < 0 || slot >= offers.length)
		{
			return;
		}
		sawOffers = true;
		Offer before = offers[slot];
		boolean newOffer = before == null || before.itemId != itemId || before.price != price;
		if (itemId == changingItem && live && newOffer && (state.equals("BUYING") || state.equals("SELLING")))
		{
			// The new offer is in: the price change is done.
			changingItem = -1;
		}
		if ("EMPTY".equals(state) || itemId <= 0 || total <= 0 || price <= 0)
		{
			if (before != null)
			{
				offers[slot] = null;
				changed(slot, now);
				finishCollected(before.itemId, now);
			}
			return;
		}
		boolean same = before != null && before.itemId == itemId && before.price == price && before.total == total
			&& side(before.state) == side(state);
		Offer offer = new Offer();
		offer.slot = slot;
		offer.itemId = itemId;
		offer.filled = Math.min(filled, total);
		offer.total = total;
		offer.price = price;
		offer.spent = Math.max(0, spent);
		offer.state = state;
		offer.startedAt = same ? before.startedAt : live ? Long.valueOf(now) : null;
		Long saved = savedListedAt.get(key(offer));
		offer.listedAt = same ? before.listedAt : live ? Long.valueOf(now) : saved;
		if (same && before.state.equals(state) && before.filled == offer.filled)
		{
			return;
		}
		offers[slot] = offer;
		changed(slot, now);
		if (!same || before.filled != offer.filled)
		{
			lastProgressAt[slot] = now;
		}
		boolean done = state.equals("BOUGHT") || state.equals("SOLD") || state.startsWith("CANCELLED");
		boolean wasActive = same && (before.state.equals("BUYING") || before.state.equals("SELLING"));
		if (done && offer.filled > 0 && (wasActive || (live && !same)))
		{
			PlanItem item = planItem(itemId);
			if (live && item != null && (state.equals("BOUGHT") || state.equals("SOLD")))
			{
				boolean sale = state.equals("SOLD");
				long[] held = sale ? holding(itemId, since(itemId)) : new long[]{0, 0};
				// Profit only from what the GE says was paid; without a record of the whole buy there's none.
				boolean known = sale && held[0] >= offer.filled && held[1] > 0;
				long each = offer.spent > 0 ? offer.spent / offer.filled : price;
				long profit = known ? offer.spent - TraderRules.tax(each, false) * offer.filled - held[1] * offer.filled : 0;
				moments.add(new Moment(sale, itemId, item.name, offer.filled, sale ? each : item.sell, profit,
					offer.startedAt == null ? -1 : now - offer.startedAt, slot, known, Boolean.TRUE.equals(item.rebound)));
			}
			addTrade(offer, now);
		}
		link(offer, now, live);
	}

	private static boolean side(String state)
	{
		return state.contains("BUY") || state.equals("BOUGHT");
	}

	private static String key(Offer offer)
	{
		return offer.slot + ":" + offer.itemId + ":" + offer.price + ":" + offer.total;
	}

	private void changed(int slot, long now)
	{
		slotUpdatedAt[slot] = now;
		offersChanged = true;
	}

	private void addTrade(Offer offer, long now)
	{
		Trade trade = new Trade();
		trade.buy = side(offer.state);
		trade.itemId = offer.itemId;
		trade.quantity = offer.filled;
		trade.price = offer.spent > 0 ? (double) offer.spent / offer.filled : offer.price;
		trade.time = now;
		trades.add(trade);
		// Saved within seconds, so a restart soon after doesn't lose what was paid.
		urgent = true;
		while (trades.size() > MAX_TRADES)
		{
			trades.remove(0);
		}
	}

	/** Moves a pick's link forward as its offers go in, fill and sell, as website Trader does. */
	private void link(Offer offer, long now, boolean live)
	{
		Observation link = links.get(offer.itemId);
		if (link == null || link.dead || "completed".equals(link.stage) || "cancelled".equals(link.stage))
		{
			return;
		}
		String before = link.stage;
		if (side(offer.state))
		{
			if (link.placedAt == null && live && offer.state.equals("BUYING") && now > link.shownAt)
			{
				link.placedAt = now;
				link.buyTotal = offer.total;
				link.stage = "placed";
			}
			if (link.placedAt != null && offer.state.equals("BOUGHT"))
			{
				link.stage = "buy_filled";
				link.purchaseTime = now;
			}
		}
		else if (link.purchaseTime != null)
		{
			link.stage = offer.state.equals("SOLD") ? completed(link, offer, now) : "selling";
		}
		if (!link.stage.equals(before))
		{
			unsent.add(link.id);
		}
	}

	/** "completed" only for one buy sold in one go, the round the website can match exactly. */
	private String completed(Observation link, Offer sale, long now)
	{
		Trade buy = null;
		int buys = 0;
		for (Trade t : trades)
		{
			if (t.buy && t.itemId == link.itemId && t.time >= link.placedAt)
			{
				buy = t;
				buys++;
			}
		}
		if (buys != 1 || buy.time != link.purchaseTime || buy.quantity != sale.filled)
		{
			return "selling";
		}
		link.closedAt = now;
		link.roundId = link.itemId + ":" + buy.time + ":" + now;
		return "completed";
	}

	// --- The plan ---

	/**
	 * Items bought for this plan item and not yet sold, and their average cost. What a buy that's
	 * still open has bought counts too, so that part can be sold while the rest buys. A buy is only
	 * recorded once it finishes, so a sale of that part can be recorded first: it's taken off then.
	 */
	private long[] holding(int itemId, long since)
	{
		long held = 0;
		long cost = 0;
		long owed = 0;
		for (Trade t : trades)
		{
			if (t.itemId != itemId || t.time < since)
			{
				continue;
			}
			if (t.buy)
			{
				held += t.quantity;
				cost += Math.round(t.price * t.quantity);
				long settled = Math.min(owed, held);
				cost -= cost * settled / held;
				held -= settled;
				owed -= settled;
			}
			else
			{
				long sold = Math.min(held, t.quantity);
				if (sold > 0)
				{
					cost -= cost * sold / held;
					held -= sold;
				}
				owed += t.quantity - sold;
			}
		}
		Offer open = openBuy(itemId);
		if (open != null && open.filled > 0)
		{
			held += open.filled;
			cost += open.spent > 0 ? open.spent : open.filled * open.price;
			long settled = Math.min(owed, held);
			cost -= cost * settled / held;
			held -= settled;
		}
		return new long[]{held, held > 0 ? cost / held : 0};
	}

	/** This item's buy that's still buying, or null. */
	private Offer openBuy(int itemId)
	{
		for (Offer o : offers)
		{
			if (o != null && o.itemId == itemId && o.state.equals("BUYING"))
			{
				return o;
			}
		}
		return null;
	}

	/** How many of this item are in sales still listed: they've left the inventory. */
	private long listed(int itemId)
	{
		long listed = 0;
		for (Offer o : offers)
		{
			if (o != null && o.itemId == itemId && o.state.equals("SELLING"))
			{
				listed += o.total;
			}
		}
		return listed;
	}

	/** Whether every offer of this item is a sale still listed, or there are none. */
	private boolean onlyListedSales(int itemId)
	{
		for (Offer o : offers)
		{
			if (o != null && o.itemId == itemId && !o.state.equals("SELLING"))
			{
				return false;
			}
		}
		return true;
	}

	private long since(int itemId)
	{
		Observation link = links.get(itemId);
		return link != null ? link.shownAt : trackingSince;
	}

	private boolean sold(int itemId)
	{
		long since = since(itemId);
		for (Trade t : trades)
		{
			if (!t.buy && t.itemId == itemId && t.time >= since)
			{
				return true;
			}
		}
		return false;
	}

	private Offer offerFor(int itemId)
	{
		for (Offer o : offers)
		{
			if (o != null && o.itemId == itemId)
			{
				return o;
			}
		}
		return null;
	}

	/** A plan item whose sale is finished and collected leaves the plan, so its slot refills. */
	private void finishCollected(int itemId, long now)
	{
		if (plan == null || offerFor(itemId) != null || !sold(itemId) || holding(itemId, since(itemId))[0] > 0)
		{
			return;
		}
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null && slot.item.id == itemId)
			{
				slot.item = null;
				edited();
				links.remove(itemId);
			}
		}
	}

	private void edited()
	{
		planChanged = true;
		planEdits++;
	}

	/**
	 * The most Trader plans with when no budget is typed. It earned 95-100% of any bigger plan with
	 * smaller worst losses, and bigger plans made the same picks (research/trader-budget-2026-11).
	 */
	static final long BEST_PLAN_GP = 20_000_000L;

	/** Coins and platinum tokens in the inventory; -1 before the first reading. */
	private long packGp = -1;
	/** The same in the bank when it was last open, kept across restarts; -1 if never seen. */
	private long bankGp = -1;

	/** What the inventory holds now, and the bank too when it's loaded (null when it isn't). */
	void setGpSeen(long pack, Long bank)
	{
		packGp = pack;
		if (bank != null)
		{
			bankGp = bank;
		}
	}

	/**
	 * The budget Trader plans with: the one typed, or else the smaller of BEST_PLAN_GP and the GP it
	 * can see (coins, platinum tokens and what's in Grand Exchange offers). Null when it has neither.
	 */
	Long budget()
	{
		if (plan == null)
		{
			return null;
		}
		if (plan.budget != null)
		{
			return plan.budget;
		}
		long seen = packGp < 0 && bankGp < 0 ? 0 : Math.max(0, packGp) + Math.max(0, bankGp) + inOffers();
		return seen > 0 ? Math.min(BEST_PLAN_GP, seen) : null;
	}

	/**
	 * GP held in Grand Exchange offers: coins paid or received, plus GP still waiting in buys. Items
	 * still for sale count only when they're the plan's, since the player's own loot isn't GP yet.
	 */
	private long inOffers()
	{
		Set<Integer> planned = new HashSet<>();
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null)
			{
				planned.add(slot.item.id);
			}
		}
		long total = 0;
		for (Offer o : offers)
		{
			if (o != null && (side(o.state) || planned.contains(o.itemId)))
			{
				total += o.spent + Math.max(0, o.total - o.filled) * o.price;
			}
			else if (o != null)
			{
				total += o.spent;
			}
		}
		return total;
	}

	void setBudget(long budget)
	{
		if (plan == null)
		{
			plan = emptyPlan();
		}
		plan.budget = budget > 0 ? budget : null;
		edited();
	}

	/** A plan with all 8 slots, numbered 1 to 8, whatever came back. */
	private static Plan whole(Plan plan)
	{
		Plan full = emptyPlan();
		full.revision = plan.revision;
		full.budget = plan.budget;
		full.rejected = plan.rejected == null ? new ArrayList<>() : plan.rejected;
		if (plan.slots != null)
		{
			for (PlanSlot slot : plan.slots)
			{
				if (slot != null && slot.slot >= 1 && slot.slot <= 8)
				{
					full.slots.get(slot.slot - 1).item = slot.item;
				}
			}
		}
		return full;
	}

	private static Plan emptyPlan()
	{
		Plan plan = new Plan();
		for (int i = 1; i <= 8; i++)
		{
			PlanSlot slot = new PlanSlot();
			slot.slot = i;
			plan.slots.add(slot);
		}
		return plan;
	}

	/** GP the plan has given out to items not yet sold. */
	private long committed()
	{
		long total = 0;
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null)
			{
				total += slot.item.qty * slot.item.buy;
			}
		}
		return total;
	}

	/** Whether a GE limit allows more: the limit less this account's buys in the last 4 hours. */
	private int limitLeft(PlanFeed.Pick pick, long now)
	{
		long bought = 0;
		for (Trade t : trades)
		{
			if (t.buy && t.itemId == pick.id && t.time >= now - FOUR_HOURS_MS)
			{
				bought += t.quantity;
			}
		}
		return (int) Math.max(0, pick.limit - bought);
	}

	private Set<Integer> taken()
	{
		Set<Integer> taken = new HashSet<>(plan.rejected);
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null)
			{
				taken.add(slot.item.id);
			}
		}
		for (Offer o : offers)
		{
			if (o != null)
			{
				taken.add(o.itemId);
			}
		}
		return taken;
	}

	/**
	 * Fills free plan slots with Trader's picks: best expected profit first, sized to the budget
	 * left; proven flips when nothing else fits. Returns how many it added.
	 */
	int refill(PlanFeed.Feed feed, long now)
	{
		Long budget = budget();
		if (budget == null || feed == null || !feed.fresh(now))
		{
			return 0;
		}
		Map<Integer, PlanFeed.Pick> byId = byId(feed);
		feedAt = feed.computedAt;
		if (!java.util.Objects.equals(unlistedFeed, feed.computedAt))
		{
			unlisted.clear();
		}
		boolean changed = refresh(byId, now);
		Set<Integer> taken = taken();
		taken.addAll(unlisted);
		int added = 0;
		boolean empty = committed() == 0;
		TraderRules.Room room = room(feed, now);
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null || slot.slot > usableSlots())
			{
				continue;
			}
			TraderRules.Fill fill = TraderRules.choose(feed.picks,
				TraderRules.pickBudget(budget - committed(), budget, feed.pickShare), budget, taken,
				p -> limitLeft(p, now), members, room);
			if (fill == null)
			{
				break;
			}
			PlanFeed.Pick pick = byId.get(fill.getId());
			slot.item = item(pick, fill.getQty(), empty ? "auto_fill" : "refill");
			taken.add(pick.id);
			show(slot.item, now);
			added++;
		}
		if (added > 0 || changed)
		{
			edited();
		}
		return added;
	}

	/**
	 * Picks not bought yet follow the latest prices, as on the website, a cancelled one too: the new buy and sell, and
	 * no more than the pick's GP and the Market's size. An automatic pick that's left the list is
	 * dropped, so its slot gets a new one; a player's own pick stays. A Rebound keeps its plan.
	 */
	private boolean refresh(Map<Integer, PlanFeed.Pick> byId, long now)
	{
		boolean changed = false;
		for (PlanSlot slot : plan.slots)
		{
			PlanItem item = slot.item;
			Observation link = item == null ? null : links.get(item.id);
			if (item == null || offerFor(item.id) != null || holding(item.id, since(item.id))[0] > 0 || sold(item.id)
				|| link != null && bought(link))
			{
				continue;
			}
			if (Boolean.TRUE.equals(item.rebound))
			{
				// A Rebound keeps its plan's prices. An hour after the drop with no buy in, or after a cancelled
				// buy with nothing bought, it ends.
				if (item.reboundAt != null && now >= item.reboundAt + Rebounds.PLACE_MS)
				{
					slot.item = null;
					links.remove(item.id);
					changed = true;
				}
				continue;
			}
			boolean automatic = "auto_fill".equals(item.origin) || "refill".equals(item.origin);
			PlanFeed.Pick pick = byId.get(item.id);
			long qty = pick == null || pick.buy <= 0 ? 0 : Math.min(Math.min(item.qty, pick.qty),
				Math.min(item.qty * item.buy / pick.buy, limitLeft(pick, now)));
			if (qty <= 0)
			{
				if (automatic)
				{
					slot.item = null;
					changed = true;
				}
				continue;
			}
			if (item.buy != pick.buy || item.sell != pick.sell || item.qty != qty)
			{
				item.buy = pick.buy;
				item.sell = pick.sell;
				item.qty = qty;
				changed = true;
			}
		}
		return changed;
	}

	/** A link past "placed" bought something; a placed offer that's gone with nothing bought was cancelled. */
	private static boolean bought(Observation link)
	{
		return !"shown".equals(link.stage) && !"placed".equals(link.stage);
	}

	/** Whether a plan slot this world can use is free, for a Rebound or a pick from the Market. */
	boolean hasRoom()
	{
		if (plan == null)
		{
			return false;
		}
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item == null && slot.slot <= usableSlots())
			{
				return true;
			}
		}
		return false;
	}

	/** The next picks a free slot would get, for Up next. */
	List<PlanFeed.Pick> upNext(PlanFeed.Feed feed, long now, int count)
	{
		List<PlanFeed.Pick> next = new ArrayList<>();
		Long budget = budget();
		if (budget == null || feed == null || !feed.fresh(now))
		{
			return next;
		}
		Set<Integer> taken = taken();
		taken.addAll(unlisted);
		Map<Integer, PlanFeed.Pick> byId = byId(feed);
		TraderRules.Room room = room(feed, now);
		while (next.size() < count)
		{
			TraderRules.Fill fill = TraderRules.choose(feed.picks, TraderRules.pickBudget(budget, budget,
				feed.pickShare), budget, taken, p -> limitLeft(p, now), members, room);
			if (fill == null)
			{
				break;
			}
			next.add(byId.get(fill.getId()));
			taken.add(fill.getId());
		}
		return next;
	}

	/**
	 * The room Trader's shared limit leaves this player on each pick, as the website's Trader works
	 * it out: the plan's own items never count against it.
	 */
	private TraderRules.Room room(PlanFeed.Feed feed, long now)
	{
		Map<Integer, Long> own = new HashMap<>();
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null && slot.item.qty > 0)
			{
				own.merge(slot.item.id, slot.item.qty, Long::sum);
			}
		}
		return TraderRules.room(feed.picks, feed.room, own, feed.serverNow(now));
	}

	/**
	 * With nothing to trade, the website's note when other players have planned all the room on
	 * picks this player could take; null when that isn't why.
	 */
	private String noRoomNotice(PlanFeed.Feed feed, long now)
	{
		Set<Integer> none = room(feed, now).getFull();
		int full = 0;
		for (PlanFeed.Pick p : feed.picks)
		{
			if ((members || !p.members) && none.contains(p.id))
			{
				full++;
			}
		}
		return full == 0 ? null : TraderRules.roomNotice(full, feed.room.ends, java.time.ZoneId.systemDefault());
	}

	/** Whether this step says the plan has nothing left to pick: no pick fits, or none has room. */
	static boolean outOfPicks(Step step)
	{
		return step != null && step.getKind() == Kind.EMPTY && step.getDetail() != null
			&& (NO_PICK.equals(step.getDetail()) || step.getDetail().startsWith(TraderRules.NO_ROOM));
	}

	/** "Skip for now": the item leaves the plan and won't be picked again; its slot refills. */
	void skip(int itemId)
	{
		if (plan == null)
		{
			return;
		}
		if (!plan.rejected.contains(itemId))
		{
			plan.rejected.add(itemId);
		}
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null && slot.item.id == itemId && offerFor(itemId) == null
				&& holding(itemId, since(itemId))[0] == 0)
			{
				slot.item = null;
				links.remove(itemId);
			}
		}
		edited();
	}

	/**
	 * With every slot full, the plan item a Rebound takes the place of: the unstarted pick worth the
	 * least, never a Rebound or anything with an offer or items bought. Null when a slot is free or
	 * nothing can go. A queue would buy the Rebound late, and its plan's edge is buying near the drop.
	 */
	PlanItem reboundSwap()
	{
		if (plan == null || hasRoom())
		{
			return null;
		}
		PlanItem best = null;
		for (PlanSlot slot : plan.slots)
		{
			PlanItem item = slot.item;
			if (item != null && slot.slot <= usableSlots() && !Boolean.TRUE.equals(item.rebound) && offerFor(item.id) == null
				&& holding(item.id, since(item.id))[0] == 0 && !sold(item.id) && !unlisted.contains(item.id)
				&& (best == null || expected(item) < expected(best)))
			{
				best = item;
			}
		}
		return best;
	}

	private static long expected(PlanItem item)
	{
		return TraderRules.profitEach(item.sell, item.buy, false) * item.qty;
	}

	/** "Add to Trader" from the Market list, or a Rebound's "Add to plan". False when no slot is free. */
	boolean add(int id, String name, long qty, long buy, long sell, boolean isRebound, long now)
	{
		if (plan == null || taken().contains(id) && !plan.rejected.contains(id))
		{
			return false;
		}
		PlanItem swap = isRebound ? reboundSwap() : null;
		Long budget = budget();
		if (isRebound && budget != null)
		{
			// A smaller budget buys fewer, at the plan's prices, as on the website.
			long room = budget - committed() + (swap == null ? 0 : swap.qty * swap.buy);
			qty = Math.min(qty, Math.max(0, room) / Math.max(1, buy));
			if (qty < 1)
			{
				return false;
			}
		}
		plan.rejected.remove(Integer.valueOf(id));
		for (PlanSlot slot : plan.slots)
		{
			if (swap != null && slot.item == swap)
			{
				slot.item = null;
				links.remove(swap.id);
			}
			if (slot.item == null && slot.slot <= usableSlots())
			{
				PlanItem item = new PlanItem();
				item.id = id;
				item.name = name;
				item.qty = qty;
				item.buy = buy;
				item.sell = sell;
				item.origin = "manual";
				item.rebound = isRebound ? Boolean.TRUE : null;
				item.reboundAt = isRebound && rebound != null && rebound.id == id ? rebound.detectedAt : null;
				slot.item = item;
				show(item, now);
				edited();
				if (isRebound && rebound != null && rebound.id == id)
				{
					rebound = null;
				}
				return true;
			}
		}
		return false;
	}

	private static PlanItem item(PlanFeed.Pick pick, long qty, String origin)
	{
		PlanItem item = new PlanItem();
		item.id = pick.id;
		item.name = pick.name;
		item.qty = qty;
		item.buy = pick.buy;
		item.sell = pick.sell;
		item.origin = origin;
		return item;
	}

	/** A pick just shown: registered with the account within the minute, as the website requires. */
	private void show(PlanItem item, long now)
	{
		Observation link = new Observation();
		link.id = UUID.randomUUID().toString();
		link.itemId = item.id;
		link.origin = item.origin;
		link.shownAt = now;
		link.quantity = item.qty;
		link.buyPrice = item.buy;
		link.sellPrice = item.sell;
		links.put(item.id, link);
		unsent.add(link.id);
		urgent = true;
	}

	private static boolean taxFree(Map<Integer, PlanFeed.Pick> byId, int itemId)
	{
		PlanFeed.Pick pick = byId.get(itemId);
		return pick != null && pick.isTaxFree();
	}

	private static Map<Integer, PlanFeed.Pick> byId(PlanFeed.Feed feed)
	{
		Map<Integer, PlanFeed.Pick> map = new HashMap<>();
		if (feed != null && feed.picks != null)
		{
			for (PlanFeed.Pick p : feed.picks)
			{
				map.put(p.id, p);
			}
		}
		return map;
	}

	// --- The one next step ---

	private static Step step(Kind kind, String title, String detail, String status, Tone tone, int itemId, String name,
		long qty, long price, int geSlot)
	{
		return new Step(kind, title, detail, status, tone, itemId, name, qty, price, geSlot,
			kind + ":" + itemId + ":" + qty + ":" + price + ":" + geSlot, null);
	}

	private static Step note(Kind kind, String title, String detail)
	{
		return step(kind, title, detail, null, Tone.GREY, -1, null, 0, 0, -1);
	}

	// --- A Make and Sell swap ---

	/** Steps of a Make and Sell swap have keys that start with this. */
	static final String SWAP_KEY = "makesell:";

	static boolean isSwap(Step step)
	{
		return step != null && step.getKey().startsWith(SWAP_KEY);
	}

	/** GP the plan hasn't given out, which a Make and Sell swap can use. */
	long freeGp()
	{
		return plan == null || plan.budget == null ? 0 : Math.max(0, plan.budget - committed());
	}

	/**
	 * A Make and Sell swap's next step, from the GE since it was taken ({@code since}, this
	 * computer's clock): each buy, collecting it, the step in the game, then each sale and its coins.
	 * Null once every sale is collected. A swap with no sales ends at its step in the game, which
	 * the player marks done. "Already done" sets a step aside, as for Trader's own steps.
	 */
	Step swapStep(PlanFeed.Swap swap, long units, long since)
	{
		String key = SWAP_KEY + swap.id + ":";
		int free = freeGeSlot();
		for (int i = 0; i < swap.buy.size(); i++)
		{
			PlanFeed.Leg leg = swap.buy.get(i);
			String then = i + 1 < swap.buy.size() ? MakeSell.legText(swap.buy.get(i + 1), true, units) : swap.step;
			Step step = swapLeg(leg, true, units, since, key + "buy:" + leg.id, then, free);
			if (step != null)
			{
				return step;
			}
		}
		boolean made = !swap.sell.isEmpty() && swapStarted(swap, since);
		String makeKey = key + "make";
		if (!made && !dismissed.contains(makeKey))
		{
			String detail = swap.sell.isEmpty() ? swap.step + " " + MakeSell.keepCoins(swap, units)
				: swap.step + " Then: " + MakeSell.legText(swap.sell.get(0), false, units) + ".";
			PlanFeed.Leg art = MakeSell.art(swap);
			return new Step(Kind.MAKE, swap.title, detail, null, Tone.BLUE, art == null ? -1 : art.id,
				art == null ? null : art.name, units, 0, -1, makeKey, null);
		}
		for (PlanFeed.Leg leg : swap.sell)
		{
			Step step = swapLeg(leg, false, units, since, key + "sell:" + leg.id, null, free);
			if (step != null)
			{
				return step;
			}
		}
		return null;
	}

	/** One buy or sale of a swap: collect it, wait for it, or place it; null when it's done. */
	private Step swapLeg(PlanFeed.Leg leg, boolean buy, long units, long since, String key, String then, int free)
	{
		// "Already done" on any of the leg's steps (place, wait, collect) means the leg is done.
		if (dismissed.contains(key) || dismissed.contains(key + ":wait") || dismissed.contains(key + ":collect"))
		{
			return null;
		}
		long need = MakeSell.need(leg, units);
		long traded = 0;
		Offer open = null;
		Offer ready = null;
		for (Trade t : trades)
		{
			if (t.itemId == leg.id && t.buy == buy && t.time >= since)
			{
				traded += t.quantity;
			}
		}
		for (Offer o : offers)
		{
			if (o == null || o.itemId != leg.id || side(o.state) != buy || !since(o, since))
			{
				continue;
			}
			if (o.state.equals(buy ? "BUYING" : "SELLING"))
			{
				// Fills of an offer still in aren't trades yet.
				traded += o.filled;
				open = o;
			}
			else if (done(o) && o.filled > 0)
			{
				ready = o;
			}
		}
		if (ready != null && (traded >= need || open == null))
		{
			boolean coins = !buy;
			return new Step(Kind.COLLECT, coins ? COLLECT_COINS : "Collect " + leg.name, coins ? "From your " + leg.name
				+ " sale" : BoxText.whole(ready.filled) + " bought. Then: " + then, "Ready in GE slot " + (ready.slot + 1),
				Tone.AMBER, leg.id, leg.name, ready.filled, leg.price, ready.slot, key + ":collect", then);
		}
		if (traded >= need)
		{
			return null;
		}
		if (open != null)
		{
			return new Step(Kind.WAIT, (buy ? "Buying " : "Selling ") + leg.name, (buy ? "Bought " : "Sold ")
				+ BoxText.whole(traded) + " of " + BoxText.whole(need) + " so far.", "Watching your "
				+ (buy ? "buy" : "sale") + " in GE slot " + (open.slot + 1), Tone.BLUE, leg.id, leg.name, need - traded,
				leg.price, open.slot, key + ":wait", then);
		}
		long left = need - traded;
		return new Step(buy ? Kind.BUY : Kind.SELL, (buy ? "Buy " : "Sell ") + leg.name, BoxText.whole(left) + " at "
			+ BoxText.whole(leg.price) + " each" + (buy && free >= 0 ? " · GE slot " + (free + 1) : "")
			+ (leg.isInstant() ? " (it buys at once)" : ""), buy ? free >= 0 ? "Watching for your buy in slot " + (free + 1)
			: "Waiting for a free GE slot" : "Click it in your inventory at the GE", buy && free < 0 ? Tone.GREY : Tone.BLUE,
			leg.id, leg.name, left, leg.price, buy ? free : -1, key, then);
	}

	/** Whether an offer went in after {@code since}; one seen only at login, with no start time, counts. */
	private static boolean since(Offer o, long since)
	{
		return o.startedAt == null || o.startedAt >= since;
	}

	/** Whether a sale of the swap's output has started since it was taken: the step in the game is done. */
	private boolean swapStarted(PlanFeed.Swap swap, long since)
	{
		for (PlanFeed.Leg leg : swap.sell)
		{
			for (Trade t : trades)
			{
				if (t.itemId == leg.id && !t.buy && t.time >= since)
				{
					return true;
				}
			}
			for (Offer o : offers)
			{
				if (o != null && o.itemId == leg.id && !side(o.state) && since(o, since))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** "Already done" or "Keep my current price": this step is set aside and the next one shows. */
	void dismiss(String stepKey)
	{
		if (stepKey != null)
		{
			dismissed.add(stepKey);
		}
	}

	/** The first GE slot with nothing in it that this world lets you use, or -1. */
	int freeGeSlot()
	{
		for (int i = 0; i < usableSlots(); i++)
		{
			if (offers[i] == null)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * The one thing to do now: collect what's done, then fix a stalled price, then sell what's
	 * held, then buy the next pick; else wait. {@code prices} gives a held item's sell price when
	 * it's left the feed (the price check), or 0. Steps the player set aside are passed over.
	 */
	/** The player is shown a new price for a sale: while they change it, steps for that item use it. */
	void changingPrice(int itemId, long price, long now)
	{
		changingItem = itemId;
		changingPrice = price;
		changingUntil = now + CHANGING_MS;
	}

	/** The new sale price being set for this item, or 0. */
	private long changing(int itemId, long now)
	{
		return itemId == changingItem && now < changingUntil ? changingPrice : 0;
	}

	Step next(PlanFeed.Feed feed, long now, java.util.function.IntToLongFunction prices)
	{
		return step(feed, now, prices, -1);
	}

	/** The step for one plan item, such as a sale the player opened in the GE; null when it has none. */
	Step stepFor(PlanFeed.Feed feed, long now, java.util.function.IntToLongFunction prices, int itemId)
	{
		return itemId > 0 && budget() != null ? step(feed, now, prices, itemId) : null;
	}

	private Step step(PlanFeed.Feed feed, long now, java.util.function.IntToLongFunction prices, int only)
	{
		if (budget() == null)
		{
			return note(Kind.BUDGET, "Set your budget", "How much GP do you want to trade with?");
		}
		boolean fresh = feed != null && feed.fresh(now);
		Map<Integer, PlanFeed.Pick> byId = byId(feed);
		List<Step> steps = new ArrayList<>();
		List<PlanItem> items = new ArrayList<>();
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null)
			{
				items.add(slot.item);
			}
		}
		for (Offer o : offers)
		{
			PlanItem item = o == null ? null : planItem(o.itemId);
			if (item != null && done(o))
			{
				boolean coins = o.state.equals("SOLD") || o.state.equals("CANCELLED_BUY") && o.filled == 0;
				steps.add(step(Kind.COLLECT, coins ? COLLECT_COINS : "Collect " + item.name,
					coins ? "From your " + item.name + " sale" : BoxText.whole(o.filled) + " bought. Then sell at " + BoxText.whole(item.sell),
					"Ready in GE slot " + (o.slot + 1), Tone.AMBER, item.id, item.name, o.filled, item.sell, o.slot));
			}
		}
		// A stalled buy keeps its price: in Trader's tests, raising it to fill sooner earned less.
		for (Offer o : offers)
		{
			PlanItem item = o == null ? null : planItem(o.itemId);
			if (item == null || o.filled >= o.total || !o.state.equals("SELLING") || o.listedAt == null)
			{
				continue;
			}
			PlanFeed.Pick pick = byId.get(item.id);
			long cost = holding(item.id, since(item.id))[1];
			long target = pick != null && fresh ? pick.sell : prices.applyAsLong(item.id);
			boolean taxFree = taxFree(byId, item.id);
			if (item.reboundAt != null)
			{
				// A Rebound keeps the usual price for 2 days after the drop, then sells at the going price,
				// below what was paid or not, as the research's plan did.
				if (now >= Rebounds.sellEndsAt(item.reboundAt) && target > 0
					&& o.price - target >= Math.max(1, (o.price + 999) / 1000))
				{
					steps.add(step(Kind.LOWER, Rebounds.SELL_OVER_TITLE + ": lower " + item.name + " to "
						+ BoxText.whole(target) + "?", Rebounds.sellOver(target), "Your sale in GE slot " + (o.slot + 1),
						Tone.AMBER, item.id, item.name, o.total - o.filled, target, o.slot));
				}
				continue;
			}
			TraderRules.Reprice reprice = TraderRules.repriceSale(o.price, o.filled, o.total, o.listedAt,
				cost, target, now - lastProgressAt[o.slot], now, taxFree);
			if (reprice != null)
			{
				steps.add(step(Kind.LOWER, (reprice.getPrice() < o.price ? "Lower " : "Raise ") + item.name + " to "
					+ BoxText.whole(reprice.getPrice()) + "?", repriceWhy(o, reprice, now, taxFree),
					"Your sale in GE slot " + (o.slot + 1), Tone.AMBER, item.id, item.name, o.total - o.filled,
					reprice.getPrice(), o.slot));
			}
		}
		for (Offer o : offers)
		{
			PlanItem item = o == null ? null : planItem(o.itemId);
			if (item != null && item.reboundAt != null && o.state.equals("BUYING") && o.filled < o.total
				&& now >= Rebounds.buyEndsAt(item.reboundAt, o.startedAt))
			{
				steps.add(step(Kind.STOP, Rebounds.buyOverTitle(item.name), Rebounds.buyOver(o.filled),
					"Your buy in GE slot " + (o.slot + 1), Tone.AMBER, item.id, item.name, o.total - o.filled, 0, o.slot));
			}
		}
		int free = freeGeSlot();
		boolean queued = false;
		for (PlanItem item : items)
		{
			long[] holding = holding(item.id, since(item.id));
			long unlisted = holding[0] - listed(item.id);
			if (unlisted <= 0)
			{
				continue;
			}
			Offer buying = openBuy(item.id);
			if (onlyListedSales(item.id))
			{
				// The price Trader gave when it picked the item holds; only the 8-hour rule changes it.
				long lower = changing(item.id, now);
				boolean goingPrice = item.reboundAt != null && now >= Rebounds.sellEndsAt(item.reboundAt);
				long price = lower > 0 ? lower : item.sell > 0 && !goingPrice ? item.sell : prices.applyAsLong(item.id);
				steps.add(price > 0
					? step(Kind.SELL, "Sell " + item.name, BoxText.whole(unlisted) + " at " + BoxText.whole(price) + " each",
					"Click it in your inventory at the GE", Tone.BLUE, item.id, item.name, unlisted, price, -1)
					: step(Kind.STALE, "Checking a price for " + item.name, "We'll show your sale price in a moment.", null,
					Tone.GREY, item.id, item.name, unlisted, 0, -1));
			}
			else if (buying != null && item.sell > 0 && (item.reboundAt != null || TraderRules.sellPartOfBuy(unlisted,
				buying.filled, buying.total, item.sell - TraderRules.tax(item.sell, taxFree(byId, item.id)) - holding[1])))
			{
				if (free < 0)
				{
					queued = true;
					continue;
				}
				steps.add(step(Kind.SELL, "Sell " + item.name, BoxText.whole(unlisted) + " at " + BoxText.whole(item.sell)
					+ " each · GE slot " + (free + 1) + ". The rest can keep buying.",
					"Collect them from GE slot " + (buying.slot + 1) + " first", Tone.BLUE, item.id, item.name, unlisted,
					item.sell, free));
			}
		}
		for (PlanItem item : items)
		{
			if (offerFor(item.id) == null && holding(item.id, since(item.id))[0] == 0 && !sold(item.id))
			{
				if (!fresh)
				{
					steps.add(note(Kind.STALE, "Our prices aren't up to date",
						"We check for new ones every minute, and show your next buy as soon as they're in."));
					break;
				}
				steps.add(step(Kind.BUY, "Buy " + item.name, BoxText.whole(item.qty) + " at " + BoxText.whole(item.buy)
					+ " each" + (free >= 0 ? " · GE slot " + (free + 1) : "") + (item.reboundAt != null
					? "\n" + Rebounds.plan(item.sell) : priceAge(feed, now)), free >= 0
					? "Watching for your buy in slot " + (free + 1) : "Waiting for a free GE slot",
					free >= 0 ? Tone.BLUE : Tone.GREY, item.id, item.name, item.qty, item.buy, free));
			}
		}
		for (Step step : steps)
		{
			if (!dismissed.contains(step.getKey()) && (only < 0 || step.getItemId() == only))
			{
				return step;
			}
		}
		if (only > 0)
		{
			return null;
		}
		int working = 0;
		for (Offer o : offers)
		{
			if (o != null && planned(o.itemId))
			{
				working++;
			}
		}
		String noRoom = items.isEmpty() && fresh ? noRoomNotice(feed, now) : null;
		return items.isEmpty()
			? note(Kind.EMPTY, "Nothing to trade right now", noRoom != null ? noRoom : fresh ? NO_PICK
			: "Our prices aren't up to date. We check for new ones every minute.")
			: step(Kind.WAIT, "Your offers are in", waitingNews(items, now) + (queued
			? "Keep this buy running. We'll ask you to sell them when a slot is free. " : "") + (selling()
			? "We'll tell you when one fills. A sale keeps its price for 8 hours. Then, if it hasn't sold, we'll suggest "
			+ "a new price that still covers what you paid." : "We'll tell you when one fills."), working > 0
			? "Watching your " + working + (working == 1 ? " offer" : " offers") : null, Tone.BLUE, -1, null, 0, 0, -1);
	}

	/**
	 * Something to show for the wait: the offer furthest along, and what the offers are expected to
	 * make. Empty when there's neither.
	 */
	private String waitingNews(List<PlanItem> items, long now)
	{
		Offer lead = null;
		StringBuilder news = new StringBuilder();
		for (Offer o : offers)
		{
			PlanItem rebound = o == null ? null : planItem(o.itemId);
			String left = reboundLeft(rebound, o, now);
			if (left != null)
			{
				// A Rebound's wait has its own plan: say where it is and how long is left.
				String time = left.substring(0, left.length() - 5);
				news.append(rebound.name).append(": ").append(o.state.equals("BUYING")
					? Rebounds.buying(o.filled, o.total, time, rebound.sell)
					: Rebounds.selling(o.filled, o.total, time, o.price)).append(' ');
				continue;
			}
			if (o != null && planned(o.itemId) && o.total > 0 && o.filled > 0 && o.filled < o.total
				&& (lead == null || o.filled * lead.total > lead.filled * o.total))
			{
				lead = o;
			}
		}
		for (PlanItem item : items)
		{
			if (lead != null && item.id == lead.itemId)
			{
				news.append(item.name).append(": ").append(BoxText.whole(lead.filled)).append(" of ")
					.append(BoxText.whole(lead.total)).append(lead.state.startsWith("SELL") ? " sold" : " bought")
					.append(lead.startedAt != null ? " in " + TraderRules.waitedFor(now - lead.startedAt) : "").append(". ");
			}
		}
		long expected = 0;
		for (SlotRow row : slots(now))
		{
			expected += Math.max(0, row.expected);
		}
		if (expected > 0)
		{
			news.append("Expected +").append(Ui.gp(expected)).append(" GP from your offers. ");
		}
		return news.toString();
	}

	/** "Latest price, from 2 min ago." under a buy, so the player knows it's current. */
	private static String priceAge(PlanFeed.Feed feed, long now)
	{
		Long age = feed == null ? null : feed.ageMs(now);
		return age == null ? "" : "\nPrices from " + BoxText.pricesAge(age) + ".";
	}

	private boolean selling()
	{
		for (Offer o : offers)
		{
			if (o != null && o.state.equals("SELLING") && planned(o.itemId))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean done(Offer o)
	{
		return o.state.equals("BOUGHT") || o.state.equals("SOLD") || o.state.startsWith("CANCELLED");
	}

	/** Why a listed sale's price could change, and what it costs or gains on each. */
	private static String repriceWhy(Offer o, TraderRules.Reprice reprice, long now, boolean taxFree)
	{
		long hoursListed = Math.max(1, (now - o.listedAt) / 3_600_000L);
		String listed = "Listed " + hoursListed + (hoursListed == 1 ? " hour" : " hours") + " at " + BoxText.whole(o.price)
			+ (o.filled == 0 ? " with no sales." : " with " + BoxText.whole(o.filled) + " sold.");
		long change = TraderRules.profitEach(reprice.getPrice(), 0, taxFree) - TraderRules.profitEach(o.price, 0, taxFree);
		long each = reprice.getProfitEach();
		String at = " At " + BoxText.whole(reprice.getPrice()) + " you make ";
		if (change >= 0)
		{
			return listed + at + BoxText.whole(change) + " GP more on each.";
		}
		return listed + at + BoxText.whole(-change) + " GP less on each, " + (each >= 0
			? "but still more than you paid." : "and lose " + BoxText.whole(-each) + " on each. Or keep your price and wait.");
	}

	/** Each plan item as Your slots shows it: offers in GE slot order first, then what's next. */
	List<SlotRow> slots(long now)
	{
		return slots(now, null);
	}

	/** As {@link #slots(long)}, with each pick's Chance from {@code feed} when it's there. */
	List<SlotRow> slots(long now, PlanFeed.Feed feed)
	{
		Map<Integer, PlanFeed.Pick> picks = feed == null ? new HashMap<>() : byId(feed);
		List<SlotRow> rows = new ArrayList<>();
		if (plan == null)
		{
			return rows;
		}
		List<PlanItem> later = new ArrayList<>();
		for (Offer o : offers)
		{
			PlanItem item = o == null ? null : planItem(o.itemId);
			if (item == null)
			{
				continue;
			}
			String status;
			Tone tone;
			String countdown = reboundLeft(item, o, now);
			if (o.state.equals("BUYING"))
			{
				status = "Buying · " + (o.filled * 100 / o.total) + "%" + (countdown != null ? " · " + countdown : "");
				tone = Tone.BLUE;
			}
			else if (o.state.equals("SELLING") && countdown != null)
			{
				status = (o.filled > 0 ? "Sold " + (o.filled * 100 / o.total) + "%" : "Selling") + " · " + countdown;
				tone = Tone.GREEN;
			}
			else if (o.state.equals("SELLING"))
			{
				long left = o.listedAt == null ? -1 : TraderRules.SELL_PATIENCE_MS - (now - o.listedAt);
				// Once some has sold, say how much, so collecting part of a sale shows progress.
				String sold = o.filled > 0 ? "Sold " + (o.filled * 100 / o.total) + "%" : null;
				status = left > 0 ? (sold != null ? sold : "Selling") + " · " + hours(left) + " left"
					: sold != null ? sold + " at " + BoxText.whole(o.price) : "Selling at " + BoxText.whole(o.price);
				tone = Tone.GREEN;
			}
			else
			{
				status = "Ready to collect";
				tone = Tone.AMBER;
			}
			rows.add(row(item, o, status, tone, now, picks.get(item.id)));
		}
		for (PlanSlot slot : plan.slots)
		{
			if (slot.item != null && offerFor(slot.item.id) == null)
			{
				later.add(slot.item);
			}
		}
		for (PlanItem item : later)
		{
			boolean held = holding(item.id, since(item.id))[0] > 0;
			rows.add(row(item, null, held ? "Next: sell at " + BoxText.whole(item.sell) : "Next: buy at " + BoxText.whole(item.buy),
				held ? Tone.GREEN : Tone.GREY, now, picks.get(item.id)));
		}
		return rows;
	}

	/** A Rebound offer's time left, such as "18h left", while its plan's window runs; else null. */
	static String reboundLeft(PlanItem item, Offer o, long now)
	{
		if (item == null || item.reboundAt == null)
		{
			return null;
		}
		long end = o.state.equals("BUYING") ? Rebounds.buyEndsAt(item.reboundAt, o.startedAt)
			: o.state.equals("SELLING") ? Rebounds.sellEndsAt(item.reboundAt) : 0;
		return end > now ? Rebounds.left(end - now) + " left" : null;
	}

	private SlotRow row(PlanItem item, Offer o, String status, Tone tone, long now, PlanFeed.Pick pick)
	{
		long[] held = holding(item.id, since(item.id));
		boolean selling = o != null && !side(o.state);
		boolean buying = o != null && side(o.state);
		long sell = selling ? o.price : item.sell;
		long expected = TraderRules.profitEach(item.sell, item.buy, false) * item.qty;
		List<Fact> plan = new ArrayList<>();
		plan.add(new Fact("Buy · each", BoxText.whole(item.buy) + " GP"));
		plan.add(new Fact("Quantity", BoxText.whole(item.qty)));
		// How much is bought, while it's still coming in; once all of it is, the sale says the rest.
		if (buying || !selling && held[0] > 0 && held[0] < item.qty)
		{
			plan.add(new Fact("Bought so far", BoxText.whole(buying ? o.filled : held[0]) + " of "
				+ BoxText.whole(buying ? o.total : item.qty)));
		}
		plan.add(new Fact("Sell · each", BoxText.whole(sell) + " GP"));
		plan.add(new Fact("You keep · after tax", BoxText.whole(sell - TraderRules.tax(sell, false)) + " GP"));
		if (selling)
		{
			plan.add(new Fact("Sold so far", BoxText.whole(o.filled) + " of " + BoxText.whole(o.total)));
		}
		plan.add(new Fact("Profit if it all sells", (expected >= 0 ? "+" : "") + Ui.gp(expected) + " GP"));
		// Chance belongs to the pick's prices, so it shows only while the plan still has them.
		if (pick != null && pick.chance > 0 && pick.buy == item.buy && pick.sell == item.sell)
		{
			plan.add(new Fact("Chance", BoxText.percent(pick.chance)));
		}
		plan.add(new Fact("GP needed", Ui.gp(item.qty * item.buy)));
		return new SlotRow(item.name, item.id, status, tone, expected, item.qty * item.buy, plan,
			note(item, o, held, now));
	}

	/** What happens next for a slot, in Trader's words: the sell rule, and break-even once it's known. */
	private static String note(PlanItem item, Offer o, long[] held, long now)
	{
		String sell = BoxText.whole(item.sell);
		if (Boolean.TRUE.equals(item.rebound))
		{
			// A Rebound follows its own plan: the usual price for 2 days after the drop, then the going price.
			String left = o == null ? null : reboundLeft(item, o, now);
			if ((o == null || side(o.state)) && held[0] == 0)
			{
				return Rebounds.plan(item.sell) + (left != null ? " The buy has " + left + "." : "");
			}
			return "Rebound: sell at " + sell + " until 2 days after the drop" + (left != null ? " (" + left + ")" : "")
				+ ". Then Trader sells at the going price.";
		}
		String breakEven = held[1] > 0 ? BoxText.whole(TraderRules.breakEven(held[1], false)) + " GP" : null;
		String floor = breakEven == null ? "." : ", but not below break-even (" + breakEven + ").";
		if (o == null)
		{
			return held[0] > 0
				? "Sell all " + BoxText.whole(held[0]) + " at " + sell + ". It keeps that price for 8 hours. If it stalls"
				+ " after that, Trader lowers it" + floor
				: "Buy " + BoxText.whole(item.qty) + " at " + BoxText.whole(item.buy) + ". Then sell at " + sell
				+ ". A sell keeps its price for 8 hours.";
		}
		switch (o.state)
		{
			case "BUYING":
				return "When it fills, collect it. Then sell at " + sell + ".";
			case "SELLING":
				long left = o.listedAt == null ? -1 : TraderRules.SELL_PATIENCE_MS - (now - o.listedAt);
				return (left > 0 ? "It keeps this price for " + hours(left) + " more. If it stalls after that, Trader lowers it"
					: "Its 8 hours are up. If it stalls, Trader lowers it") + floor;
			case "SOLD":
				return "It sold. Collect your coins, and Trader picks the slot's next trade.";
			default:
				return side(o.state) ? "Collect it from the GE. Then sell at " + sell + "." : "Collect it from the GE.";
		}
	}

	private PlanItem planItem(int itemId)
	{
		if (plan != null)
		{
			for (PlanSlot slot : plan.slots)
			{
				if (slot.item != null && slot.item.id == itemId)
				{
					return slot.item;
				}
			}
		}
		return null;
	}

	/** GP the plan has put into its items, for the Your slots header. */
	long invested()
	{
		return plan == null ? 0 : committed();
	}

	/** The label inside a GE slot: a plan item's offer, or the next buy for an empty slot. */
	Badge badge(int geSlot, long now, Step next)
	{
		Offer o = geSlot >= 0 && geSlot < offers.length ? offers[geSlot] : null;
		if (plan == null)
		{
			return null;
		}
		if (o == null)
		{
			return next != null && next.getKind() == Kind.BUY && next.getGeSlot() == geSlot
				? new Badge("Next: " + next.getName(), Tone.BLUE, -1) : null;
		}
		PlanItem item = planItem(o.itemId);
		if (item == null)
		{
			return null;
		}
		if (next != null && next.getKind() == Kind.LOWER && next.getGeSlot() == geSlot)
		{
			return new Badge((next.getPrice() < o.price ? "Lower to " : "Raise to ") + BoxText.whole(next.getPrice()) + "?",
				Tone.AMBER, -1);
		}
		if (next != null && next.getKind() == Kind.STOP && next.getGeSlot() == geSlot)
		{
			return new Badge("Cancel the rest?", Tone.AMBER, -1);
		}
		String countdown = reboundLeft(item, o, now);
		if (o.state.equals("BUYING"))
		{
			return new Badge("Buying · " + (o.filled * 100 / o.total) + "%" + (countdown != null ? " · " + countdown : ""),
				Tone.BLUE, -1);
		}
		if (o.state.equals("SELLING") && countdown != null)
		{
			return new Badge((o.filled > 0 ? "Sold " + (o.filled * 100 / o.total) + "%" : "Rebound") + " · " + countdown,
				Tone.GREEN, -1);
		}
		if (o.state.equals("SELLING"))
		{
			long left = o.listedAt == null ? -1 : TraderRules.SELL_PATIENCE_MS - (now - o.listedAt);
			String head = o.filled > 0 ? "Sold " + (o.filled * 100 / o.total) + "%" : "Sell at " + BoxText.whole(o.price);
			return left > 0
				? new Badge(head + " · " + hours(left), Tone.GREEN,
				(double) left / TraderRules.SELL_PATIENCE_MS)
				: new Badge(head, Tone.GREEN, -1);
		}
		return new Badge("Collect", Tone.AMBER, -1);
	}

	/** How many GE slots hold something of the plan's to collect. */
	int toCollect()
	{
		int count = 0;
		for (Offer o : offers)
		{
			if (o != null && planned(o.itemId)
				&& (o.state.equals("BOUGHT") || o.state.equals("SOLD") || o.state.startsWith("CANCELLED")))
			{
				count++;
			}
		}
		return count;
	}

	/** Items bought for the plan and not yet sold, for the bank outlines. */
	Set<Integer> held()
	{
		Set<Integer> held = new HashSet<>();
		if (plan != null)
		{
			for (PlanSlot slot : plan.slots)
			{
				if (slot.item != null && holding(slot.item.id, since(slot.item.id))[0] > 0)
				{
					held.add(slot.item.id);
				}
			}
		}
		return held;
	}

	boolean planned(int itemId)
	{
		if (plan != null)
		{
			for (PlanSlot slot : plan.slots)
			{
				if (slot.item != null && slot.item.id == itemId)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String hours(long ms)
	{
		long minutes = Math.max(1, ms / 60_000);
		return minutes >= 60 ? (minutes / 60) + "h" : minutes + "m";
	}

	// --- Syncing with the account ---

	/** Whether to sync now: soon after a change, at once for a new pick, and every 10 minutes regardless. */
	boolean due(long now)
	{
		if (syncing || now < retryAt)
		{
			return false;
		}
		if (urgent || first)
		{
			return true;
		}
		boolean change = planChanged || offersChanged || !unsent.isEmpty();
		return change ? now - lastSync >= SYNC_GAP_MS : now - lastSync >= HEARTBEAT_MS;
	}

	/** The request body for /api/plugin-account/v1/sync, and marks it in flight. */
	String request(long now)
	{
		JsonObject body = new JsonObject();
		sentTradesAt = 0;
		if (first)
		{
			body.addProperty("first", true);
		}
		boolean withPlan = planChanged && plan != null;
		if (profileId != null)
		{
			JsonObject ge = new JsonObject();
			ge.addProperty("id", profileId);
			JsonArray list = new JsonArray();
			for (Offer o : offers)
			{
				if (o != null)
				{
					list.add(gson.toJsonTree(o));
				}
			}
			ge.add("offers", list);
			ge.add("trades", gson.toJsonTree(trades));
			ge.add("slotUpdatedAt", gson.toJsonTree(slotUpdatedAt));
			ge.addProperty("tradeTrackingSince", trackingSince);
			ge.addProperty("members", members);
			// Only the GP Trader plans with (at most BEST_PLAN_GP), never the coins themselves.
			Long planGp = plan != null && plan.budget == null ? budget() : null;
			if (planGp != null)
			{
				ge.addProperty("planGp", planGp);
			}
			body.add("offers", ge);

			if (!unsent.isEmpty() || !trades.isEmpty())
			{
				JsonObject progress = new JsonObject();
				progress.addProperty("profile", profileId);
				JsonArray observations = new JsonArray();
				inFlight.clear();
				for (Observation link : links.values())
				{
					if (!unsent.contains(link.id) || link.dead)
					{
						continue;
					}
					if (link.registered)
					{
						observations.add(gson.toJsonTree(link));
						inFlight.put(link.id, link.stage);
					}
					else if (now - link.shownAt <= LINK_WINDOW_MS)
					{
						// A new link may only say it was shown; its later stages follow once it's saved.
						Observation shown = shownOnly(link);
						observations.add(gson.toJsonTree(shown));
						inFlight.put(link.id, shown.stage);
					}
					else
					{
						link.dead = true;
						unsent.remove(link.id);
					}
				}
				progress.add("observations", observations);
				JsonArray events = new JsonArray();
				for (Trade t : trades)
				{
					JsonObject e = new JsonObject();
					e.addProperty("buy", t.buy);
					e.addProperty("itemId", t.itemId);
					e.addProperty("quantity", t.quantity);
					e.addProperty("price", t.price);
					e.addProperty("time", t.time);
					events.add(e);
				}
				progress.add("trades", events);
				sentTradesAt = trades.isEmpty() ? 0 : now;
				progress.addProperty("startedAt", trackingSince);
				progress.addProperty("clientNow", now);
				body.add("progress", progress);
			}
		}
		if (withPlan)
		{
			JsonObject p = new JsonObject();
			p.addProperty("revision", plan.revision);
			JsonArray slots = new JsonArray();
			for (PlanSlot slot : plan.slots)
			{
				JsonObject s = new JsonObject();
				s.addProperty("slot", slot.slot);
				if (slot.item == null)
				{
					s.add("item", JsonNull.INSTANCE);
				}
				else
				{
					JsonObject i = new JsonObject();
					i.addProperty("id", slot.item.id);
					i.addProperty("qty", slot.item.qty);
					i.addProperty("buy", slot.item.buy);
					i.addProperty("sell", slot.item.sell);
					i.addProperty("origin", slot.item.origin);
					if (Boolean.TRUE.equals(slot.item.rebound))
					{
						i.addProperty("rebound", true);
					}
					s.add("item", i);
				}
				slots.add(s);
			}
			p.add("slots", slots);
			if (plan.budget == null)
			{
				p.add("budget", JsonNull.INSTANCE);
			}
			else
			{
				p.addProperty("budget", plan.budget);
			}
			p.add("rejected", gson.toJsonTree(plan.rejected));
			body.add("plan", p);
		}
		sentEdits = planEdits;
		sentPlan = withPlan;
		sentIds.clear();
		for (PlanSlot slot : withPlan ? plan.slots : new ArrayList<PlanSlot>())
		{
			if (slot.item != null)
			{
				sentIds.add(slot.item.id);
			}
		}
		syncing = true;
		return body.toString();
	}

	private static Observation shownOnly(Observation link)
	{
		Observation shown = new Observation();
		shown.id = link.id;
		shown.itemId = link.itemId;
		shown.origin = link.origin;
		shown.shownAt = link.shownAt;
		shown.quantity = link.quantity;
		shown.buyPrice = link.buyPrice;
		shown.sellPrice = link.sellPrice;
		return shown;
	}

	/** What came back: 200 saved, 409 the website changed the plan first, else try again later. */
	void onReply(int status, Reply reply, long now)
	{
		syncing = false;
		if (status == 400 && sentPlan)
		{
			// The website can't save this plan. Rather than send it again forever, take the website's.
			planChanged = false;
		}
		if (status != 200 && status != 409 || reply == null || reply.plan == null)
		{
			// Sooner while a new pick waits, so it's registered inside its minute.
			retryAt = now + (urgent ? 15_000 : 60_000);
			inFlight.clear();
			return;
		}
		lastSync = now;
		urgent = false;
		offersChanged = false;
		// Progress couldn't be saved this time: the links stay unsent and go again soon, inside their minute.
		if ("unavailable".equals(reply.progress) || "conflict".equals(reply.progress))
		{
			if (!inFlight.isEmpty())
			{
				urgent = true;
				retryAt = now + 15_000;
			}
			inFlight.clear();
		}
		for (Map.Entry<String, String> sent : inFlight.entrySet())
		{
			for (Observation link : links.values())
			{
				if (link.id.equals(sent.getKey()))
				{
					link.registered = true;
					if (link.stage.equals(sent.getValue()))
					{
						unsent.remove(link.id);
					}
				}
			}
		}
		inFlight.clear();
		// Local edits made while the request was out are kept only if the website's plan is the one they
		// were made on: ours was saved, or nothing changed there. Otherwise the website's plan wins.
		boolean editedSince = planEdits != sentEdits;
		boolean sameBase = plan != null && (sentPlan || reply.plan.revision == plan.revision);
		if (status == 200 && sentPlan)
		{
			// A pick the website saved as empty had left its live list.
			Set<Integer> dropped = new HashSet<>(sentIds);
			for (PlanSlot slot : reply.plan.slots == null ? new ArrayList<PlanSlot>() : reply.plan.slots)
			{
				if (slot.item != null)
				{
					dropped.remove(slot.item.id);
				}
			}
			if (!dropped.isEmpty())
			{
				unlisted.addAll(dropped);
				unlistedFeed = feedAt;
			}
		}
		if (status == 200 && editedSince && sameBase)
		{
			// Picks added while this request was out stay, and go with the next sync on the new revision.
			plan.revision = reply.plan.revision;
			for (PlanSlot slot : plan.slots)
			{
				if (slot.item != null && unlisted.contains(slot.item.id) && offerFor(slot.item.id) == null)
				{
					slot.item = null;
				}
			}
		}
		else
		{
			// Either our plan was saved, or the website's newer one wins and refills start from it.
			plan = whole(reply.plan);
			planChanged = false;
		}
		if (reply.today != null)
		{
			today = reply.today;
		}
		if ("saved".equals(reply.progress) && sentTradesAt > 0)
		{
			// Its figures now hold every trade the request carried.
			savedThrough = sentTradesAt;
		}
		sentTradesAt = 0;
		rebound = reply.rebound;
		if (reply.summary != null)
		{
			// An achievement newer than the last one seen this session was just earned.
			Achievement was = summary == null || summary.achievements == null ? null : summary.achievements.latest;
			Achievement is = reply.summary.achievements == null ? null : reply.summary.achievements.latest;
			if (summary != null && is != null && (was == null || !is.id.equals(was.id)))
			{
				earned.add(is);
			}
			summary = reply.summary;
		}
		if (first)
		{
			first = false;
			restore(reply);
		}
	}

	/** On a first sync: the trades and when sales were listed, as the account saved them. */
	private void restore(Reply reply)
	{
		if (reply.trades != null)
		{
			Set<String> have = new HashSet<>();
			for (Trade t : trades)
			{
				have.add(t.itemId + ":" + t.buy + ":" + t.time);
			}
			List<Trade> merged = new ArrayList<>();
			for (Trade t : reply.trades)
			{
				if (!have.contains(t.itemId + ":" + t.buy + ":" + t.time))
				{
					merged.add(t);
					trackingSince = Math.min(trackingSince, t.time);
				}
			}
			merged.addAll(trades);
			trades.clear();
			trades.addAll(merged.subList(Math.max(0, merged.size() - MAX_TRADES), merged.size()));
		}
		if (reply.offers != null)
		{
			for (Offer o : reply.offers)
			{
				if (o.listedAt != null)
				{
					savedListedAt.put(key(o), o.listedAt);
					Offer now = o.slot >= 0 && o.slot < offers.length ? offers[o.slot] : null;
					if (now != null && key(now).equals(key(o)) && now.listedAt == null)
					{
						now.listedAt = o.listedAt;
					}
				}
			}
		}
	}

	/** Buys filled and flips finished since the last call. */
	List<Moment> takeMoments()
	{
		List<Moment> taken = new ArrayList<>(moments);
		moments.clear();
		return taken;
	}

	/** "Try again": the next tick syncs, whatever the wait was. */
	void retryNow()
	{
		retryAt = 0;
	}

	void forgetRebound()
	{
		rebound = null;
	}

	/** Signed out: nothing of the account stays in memory. */
	void clear()
	{
		plan = null;
		today = null;
		summary = null;
		savedThrough = 0;
		sentTradesAt = 0;
		rebound = null;
		links.clear();
		unsent.clear();
		dismissed.clear();
		first = true;
		planChanged = false;
		syncing = false;
	}
}
