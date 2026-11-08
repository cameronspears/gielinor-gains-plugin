package com.gielinorgains;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Value;
import net.runelite.api.GrandExchangeOfferState;

/**
 * "Guide my GE offer": walks the player through buying their pinned flip, then selling it. It only
 * reads the game and says what to click and what to type. The player does every click and every
 * key press; nothing here types, clicks, changes a menu or changes the game.
 *
 * The overlay copies what it reads from the game into a {@link GameView} each frame, and
 * {@link #step} turns that into the next step. All of it runs on the client thread.
 */
@Singleton
class Guide
{
	enum Phase
	{
		/** Set up the buy. */
		BUY,
		/** The buy offer is in. */
		BUYING,
		/** Some or all bought: collect, then set up the sell. */
		SELL,
		/** The sell offer is in. */
		SELLING,
		/** Sold. */
		DONE
	}

	/** What the guide points at. */
	enum Target
	{
		NONE, BUY_BUTTON, SLOT, SEARCH_RESULT, ITEM_BUTTON, QUANTITY_BUTTON, PRICE_BUTTON, CONFIRM, INVENTORY_ITEM, MODIFY, COLLECT
	}

	/** Which chatbox prompt is open. */
	enum Prompt
	{
		NONE, SEARCH, QUANTITY, PRICE
	}

	/** What just happened to the flip's own offer, for a notification. Partial fills aren't events. */
	enum Event
	{
		NONE, FILLED, SOLD
	}

	/** One thing to do: the words for the box, what to outline, and which slot when that matters. */
	@Value
	@AllArgsConstructor
	static class Step
	{
		String action;
		/** A second line, such as the live check of what's typed. Null when there's none. */
		String detail;
		Target target;
		int slot;
		/** Which of the 5 steps this is, for "step 2 of 5"; 0 while waiting or done. */
		int stage;
		/** What the cursor tooltip says over the outlined thing, such as "Set price: type 182". */
		String hover;

		Step(String action, String detail, Target target, int slot, int stage)
		{
			this(action, detail, target, slot, stage, null);
		}
	}

	/** What the overlay read from the game this frame. Reused, so reading allocates nothing. */
	static class GameView
	{
		boolean setupOpen;
		/** An offer's own page is open, with its Modify button. */
		boolean detailsOpen;
		/** The slot whose page or offer setup is open, or -1. */
		int selectedSlot = -1;
		/** The pinned item is in the search results, on screen. */
		boolean resultShown;
		int setupItem;
		boolean setupSell;
		int setupQuantity;
		long setupPrice;
		Prompt prompt = Prompt.NONE;
		String typed = "";
		/** The shortest search that brings the pinned item up, from {@link #shortestSearch}; null for its name. */
		String searchKeys;
		/** Each slot's item (-1 when empty) and state; free worlds use only the first 3. */
		final int[] slotItem = new int[8];
		final GrandExchangeOfferState[] slotState = new GrandExchangeOfferState[8];
		int usableSlots = 8;
	}

	private int itemId = -1;
	private String name;
	private Phase phase = Phase.BUY;
	/** The one GE slot this flip's offer is in, or -1. Offers for the same item in other slots are ignored. */
	private int slot = -1;
	/** Bought so far while buying; left to sell once selling. */
	private int bought;
	/** Sold so far while the sale is in. */
	private int sold;
	/** After a sale with nothing next, a line from the plugin to show for it, such as today's total. */
	private String doneLine;
	/** A Rebound's plan in short, for the box; null for a flip. */
	private String planNote;
	/** Our prices when the buy went in, so a later change can be pointed out. -1 until then. */
	private long plannedSell = -1;
	/** Goes up with every change, so the overlay knows to work the step out again. */
	private int version;
	/** After a sale: the next flip to suggest, or null when there's none. Set by the plugin. */
	private String next;
	/** For a Make and Sell buy, what comes after the collect; null for a flip. */
	private String then;
	/** A price change Trader suggested: the offer's GE slot (-1 when none) and its new price. */
	private int repriceSlot = -1;
	private long repricePrice;

	int itemId()
	{
		return itemId;
	}

	String name()
	{
		return name;
	}

	Phase phase()
	{
		return phase;
	}

	int version()
	{
		return version;
	}

	/** A line under the item in the box, such as a Rebound's plan; null when there's none. */
	String planNote()
	{
		return planNote;
	}

	void setPlanNote(String note)
	{
		if (note == null ? planNote != null : !note.equals(planNote))
		{
			planNote = note;
			version++;
		}
	}

	/** The next flip to offer once this one has sold; null when there's none. */
	void setDoneLine(String line)
	{
		if (line == null ? doneLine != null : !line.equals(doneLine))
		{
			doneLine = line;
			version++;
		}
	}

	/**
	 * Makes the pinned buy end at its collect, for a Make and Sell swap: {@code then} is what comes
	 * after, such as the step in the game, in place of a sale. Null for a flip.
	 */
	void setThen(String then)
	{
		if (then == null ? this.then != null : !then.equals(this.then))
		{
			this.then = then;
			version++;
		}
	}

	void setNext(String name)
	{
		if (name == null ? next != null : !name.equals(next))
		{
			next = name;
			version++;
		}
	}

	/**
	 * Guides a new price for an offer that's in: open it, Modify, set the price, Confirm. The
	 * game's Modify keeps the offer's place, so nothing is aborted or collected.
	 */
	void reprice(int itemId, String name, int slot, long price)
	{
		pin(itemId, name);
		repriceSlot = slot;
		repricePrice = price;
	}

	boolean repricing()
	{
		return repriceSlot >= 0;
	}

	/** Guides selling {@code quantity} of a pinned item the player already holds. */
	void sell(int quantity)
	{
		phase = Phase.SELL;
		slot = -1;
		bought = quantity;
		sold = 0;
		version++;
	}

	/**
	 * Sets how many a sale still to set up sells, from what Trader counts as held. After a Modify,
	 * the cancelled offer's fills came back apart from the new offer's, so its own count is short.
	 */
	void sellQuantity(int quantity)
	{
		if (phase == Phase.SELL && quantity > 0 && bought != quantity)
		{
			bought = quantity;
			version++;
		}
	}

	/** Picks a flip up at its collect, as when RuneLite restarts or a price change ends: coins after a sale, else items. */
	void collect(int slot, boolean coins, int quantity)
	{
		repriceSlot = -1;
		phase = coins ? Phase.DONE : Phase.SELL;
		this.slot = slot;
		bought = coins ? 0 : quantity;
		sold = 0;
		version++;
	}

	/** Makes this item the flip to guide, starting at the buy. -1 clears it. */
	void pin(int itemId, String name)
	{
		repriceSlot = -1;
		this.itemId = itemId;
		this.name = name;
		phase = Phase.BUY;
		slot = -1;
		bought = 0;
		sold = 0;
		plannedSell = -1;
		next = null;
		then = null;
		version++;
	}

	/**
	 * Follows this flip's own offer in one GE slot. Before a slot is bound, only an offer on the
	 * side we're on binds it (a buy while buying, a sell while selling), so an older offer for the
	 * same item can't take over. {@code flip} is our flip at that moment, to remember its sell price.
	 */
	Event onOffer(int slot, int itemId, GrandExchangeOfferState state, int quantitySold, ItemCheck.Flip flip)
	{
		if (this.itemId <= 0 || state == null)
		{
			return Event.NONE;
		}
		if (slot == this.slot && (state == GrandExchangeOfferState.EMPTY || itemId != this.itemId))
		{
			// Our slot was collected. A buy collected with nothing bought starts over.
			this.slot = -1;
			if (phase == Phase.BUYING || (phase == Phase.BUY && bought == 0))
			{
				phase = bought > 0 ? Phase.SELL : Phase.BUY;
			}
			version++;
			return Event.NONE;
		}
		if (itemId != this.itemId || (this.slot >= 0 && slot != this.slot))
		{
			return Event.NONE;
		}
		boolean buySide = state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		if (this.slot < 0 && (buySide ? phase != Phase.BUY : phase != Phase.SELL))
		{
			return Event.NONE;
		}
		this.slot = slot;
		version++;
		Phase before = phase;
		switch (state)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				bought = quantitySold;
				if (plannedSell < 0 && flip != null)
				{
					plannedSell = (long) (double) flip.sell;
				}
				phase = state == GrandExchangeOfferState.BUYING ? Phase.BUYING : quantitySold > 0 ? Phase.SELL : Phase.BUY;
				break;
			case SELLING:
				phase = Phase.SELLING;
				sold = quantitySold;
				break;
			case SOLD:
				phase = Phase.DONE;
				break;
			case CANCELLED_SELL:
				// Only what didn't sell is left to sell.
				bought = Math.max(0, bought - quantitySold);
				phase = bought > 0 ? Phase.SELL : Phase.DONE;
				break;
			default:
				break;
		}
		// Only a change of phase is news, so an offer seen again at login doesn't notify twice.
		if (phase != before && state == GrandExchangeOfferState.BOUGHT)
		{
			return Event.FILLED;
		}
		return phase != before && state == GrandExchangeOfferState.SOLD ? Event.SOLD : Event.NONE;
	}

	/** "Your buy of 16,500 Zulrah's scales filled. Collect them, then sell at 189." */
	String filledMessage(ItemCheck.Flip flip)
	{
		long sell = flip != null ? (long) (double) flip.sell : plannedSell;
		if (then != null)
		{
			return "Your buy of " + BoxText.whole(bought) + " " + name + " filled. Collect them. Then: " + then;
		}
		return "Your buy of " + BoxText.whole(bought) + " " + name + " filled. Collect them"
			+ (sell > 0 ? ", then sell at " + BoxText.whole(sell) + "." : ".");
	}

	String soldMessage()
	{
		return "Your " + name + " sold.";
	}

	/**
	 * The next step, or null when the guide has nothing to say: then the box shows the usual item
	 * check. {@code flip} is the latest flip for the pinned item, or null if it has left the list;
	 * {@code fresh} says whether our prices are up to date. Steps that need no price still show
	 * when they aren't.
	 */
	Step step(GameView game, ItemCheck.Flip flip, boolean fresh)
	{
		if (itemId <= 0)
		{
			return null;
		}
		if (repriceSlot >= 0)
		{
			return repriceStep(game);
		}
		boolean selling = phase == Phase.SELL;
		if (game.setupOpen && game.setupItem > 0)
		{
			// Hands off when the player chose something else: the box shows that item's check instead.
			if (game.setupItem != itemId || game.setupSell != selling || (phase != Phase.BUY && !selling))
			{
				return null;
			}
			return fresh ? setup(game, flip) : WAIT_FOR_PRICES;
		}
		switch (phase)
		{
			case BUYING:
			{
				// Progress and the sale to come, so a wait always has something to show.
				long total = flip == null ? 0 : (long) (double) flip.qty;
				long sellAt = plannedSell > 0 ? plannedSell : flip == null || !fresh ? -1 : (long) (double) flip.sell;
				String of = total > bought ? " of " + BoxText.whole(total) : "";
				if (then != null)
				{
					return new Step("Your buy offer is in.", (bought > 0 ? "Bought " + BoxText.whole(bought) + of
						+ " so far. Then: " : "When it fills: ") + then, Target.NONE, -1, 0);
				}
				if (bought > 0)
				{
					return new Step("Your buy offer is in.", "Bought " + BoxText.whole(bought) + of + " so far."
						+ (sellAt > 0 ? " Then we'll sell at " + BoxText.whole(sellAt) + "." : ""), Target.NONE, -1, 0);
				}
				return new Step("Your buy offer is in.", sellAt > 0 ? "When it fills, we'll sell at " + BoxText.whole(sellAt) + "."
					: "We'll guide the sell when it fills.", Target.NONE, -1, 0);
			}
			case SELLING:
				return new Step("Your sell offer is in.", sold > 0 && bought > sold ? "Sold " + BoxText.whole(sold) + " of "
					+ BoxText.whole(bought) + " so far." : "We'll tell you when it sells.", Target.NONE, -1, 0);
			case DONE:
				boolean uncollected = slot >= 0 && slot < game.slotItem.length && game.slotItem[slot] == itemId;
				String nextLine = next != null ? "Next flip: " + next + ". Pin it in the list."
					: doneLine != null ? doneLine : "No other flips right now.";
				if (uncollected && offerOpen(game))
				{
					return new Step("Sold! Collect your coins.", "Click them below the offer.", Target.COLLECT, slot, 0,
						"Collect your coins");
				}
				return uncollected
					? new Step("Sold! Collect your coins.", nextLine, Target.SLOT, slot, 0, "Collect your coins")
					: new Step("Sold!", nextLine, Target.NONE, -1, 0);
			case SELL:
				return sellStart(game, flip, fresh);
			default:
				return fresh ? buyStart(game, flip) : WAIT_FOR_PRICES;
		}
	}

	private Step repriceStep(GameView game)
	{
		String price = BoxText.whole(repricePrice);
		// After Modify the setup shows our offer, though the game may still hold another searched item.
		if (game.setupOpen && (game.setupItem == itemId || game.selectedSlot == repriceSlot))
		{
			if (game.prompt == Prompt.PRICE)
			{
				return new Step("Type " + keys(repricePrice), check(game.typed, repricePrice, "price", " GP"), Target.NONE, -1, 0);
			}
			if (game.setupPrice != repricePrice)
			{
				return new Step("Set the price to " + price + ".", "It's " + BoxText.whole(game.setupPrice)
					+ " now. Click ... to change it.", Target.PRICE_BUTTON, -1, 0, "Set price: type " + keys(repricePrice));
			}
			return new Step("The new price matches.", "Click Confirm.", Target.CONFIRM, -1, 0, "Confirm your new price");
		}
		if (game.detailsOpen)
		{
			return new Step("Click Modify.", "Then set the price to " + price + ".", Target.MODIFY, -1, 0,
				"Modify: change your price");
		}
		return new Step("Click your " + name + " offer.", "Then Modify it and set " + price + ".", Target.SLOT, repriceSlot, 0,
			"Open your offer");
	}

	static final Step WAIT_FOR_PRICES = new Step("Our prices aren't up to date.", "We check for new ones every minute.",
		Target.NONE, -1, 0);

	private Step buyStart(GameView game, ItemCheck.Flip flip)
	{
		if (flip == null)
		{
			return new Step(name + " isn't in today's flips now.", "Pick another flip.", Target.NONE, -1, 0);
		}
		String keys = game.searchKeys == null ? name.toLowerCase(Locale.ROOT) : game.searchKeys;
		if (game.prompt == Prompt.SEARCH)
		{
			return new Step("Type " + keys, searchCheck(game.typed, name, game.resultShown), Target.SEARCH_RESULT, -1, 2,
				"Pick " + name);
		}
		if (game.setupOpen)
		{
			return new Step("Search for " + name + ".", "Type \"" + keys + "\", then click it in the list.",
				game.prompt == Prompt.SEARCH ? Target.SEARCH_RESULT : Target.ITEM_BUTTON, -1, 2, "Pick " + name);
		}
		int free = freeSlot(game);
		if (free < 0)
		{
			return new Step("All your GE slots are in use.", "Collect or cancel an offer first.", Target.NONE, -1, 0);
		}
		return new Step("Click Buy on an empty GE slot.", "We'll buy " + BoxText.whole(flip.qty) + " " + name + ".",
			Target.BUY_BUTTON, free, 1, "Buy: start your offer here");
	}

	private Step sellStart(GameView game, ItemCheck.Flip flip, boolean fresh)
	{
		if (then != null)
		{
			// A swap's buy: collect it, then the swap goes on in the game, not at a sale.
			boolean waiting = slot >= 0 && slot < game.slotState.length && game.slotItem[slot] == itemId;
			String what = "Bought " + BoxText.whole(bought) + (waiting ? ". Collect them." : ".");
			if (!waiting)
			{
				return new Step(what, "Then: " + then, Target.NONE, -1, 0);
			}
			return offerOpen(game)
				? new Step(what, "Click them below the offer. Then: " + then, Target.COLLECT, slot, 1, "Collect your " + name)
				: new Step(what, "Then: " + then, Target.SLOT, slot, 1, "Collect your " + name);
		}
		String price = !fresh || flip == null ? null : " at " + sellPrice(flip);
		if (slot >= 0 && slot < game.slotState.length && game.slotItem[slot] == itemId)
		{
			// Bought, or a sell cancelled with some left: the items wait in the slot.
			String what = phase == Phase.SELL && game.slotState[slot] == GrandExchangeOfferState.CANCELLED_SELL
				? "Collect your " + name + " and coins." : "Bought " + BoxText.whole(bought) + ". Collect them.";
			String then = price == null ? "sell them." : "sell" + price + ".";
			return offerOpen(game)
				? new Step(what, "Click them below the offer, then " + then, Target.COLLECT, slot, 1, "Collect your " + name)
				: new Step(what, "Then " + then, Target.SLOT, slot, 1, "Collect your " + name);
		}
		if (!fresh)
		{
			return WAIT_FOR_PRICES;
		}
		if (flip == null)
		{
			return new Step(name + " left today's flips.", plannedSell > 0
				? "We had planned to sell at " + BoxText.whole(plannedSell) + ". We have no newer price."
				: "We have no sell price now, and we won't guess one.", Target.NONE, -1, 0);
		}
		if (freeSlot(game) < 0)
		{
			return new Step("All your GE slots are in use.", "Collect or cancel an offer first.", Target.NONE, -1, 0);
		}
		return new Step("Click the " + name + " in your inventory.", "We'll sell " + BoxText.whole(bought) + price + ".",
			Target.INVENTORY_ITEM, -1, 2, "Sell: click to offer your " + name);
	}

	/** Our sell price, saying so if it moved since the buy went in: "265 (was 269)". */
	private String sellPrice(ItemCheck.Flip flip)
	{
		long now = (long) (double) flip.sell;
		return BoxText.whole(now) + (plannedSell > 0 && plannedSell != now ? " (was " + BoxText.whole(plannedSell) + ")" : "");
	}

	/** The offer setup for the pinned item: quantity, then price, then Confirm. */
	private Step setup(GameView game, ItemCheck.Flip flip)
	{
		boolean selling = phase == Phase.SELL;
		long quantity = selling ? bought : flip == null ? -1 : (long) (double) flip.qty;
		long price = flip == null ? -1 : (long) (double) (selling ? flip.sell : flip.buy);
		if (quantity <= 0 || price <= 0)
		{
			// Never point at Confirm without both numbers to check.
			return new Step(name + " left today's flips.", "We have no price now, and we won't guess one.", Target.NONE, -1, 0);
		}

		if (game.prompt == Prompt.QUANTITY)
		{
			return new Step("Type " + keys(quantity), check(game.typed, quantity, "quantity", ""), Target.NONE, -1, 3);
		}
		if (game.prompt == Prompt.PRICE)
		{
			return new Step("Type " + keys(price), check(game.typed, price, "price", " GP"), Target.NONE, -1, 4);
		}
		if (game.setupQuantity != quantity)
		{
			return new Step("Set the quantity to " + BoxText.whole(quantity) + ".",
				game.setupQuantity > 0 ? "It's " + BoxText.whole(game.setupQuantity) + " now. Click ... to change it." : "Click ... to type it.",
				Target.QUANTITY_BUTTON, -1, 3, "Set quantity: type " + keys(quantity));
		}
		if (game.setupPrice != price)
		{
			return new Step("Set the price to " + BoxText.whole(price) + ".",
				"It's " + BoxText.whole(game.setupPrice) + " now. Click ... to change it.", Target.PRICE_BUTTON, -1, 4,
				"Set price: type " + keys(price));
		}
		return new Step("Quantity and price match.", "Click Confirm.", Target.CONFIRM, -1, 5, "Confirm: your offer matches");
	}

	private static int freeSlot(GameView game)
	{
		for (int i = 0; i < game.usableSlots; i++)
		{
			if (game.slotItem[i] <= 0)
			{
				return i;
			}
		}
		return -1;
	}

	/**
	 * The shortest text the game reads as exactly this amount. It takes k, m and b but no
	 * decimals: 11000 is "11k", 2000000 is "2m", 16500 stays "16500".
	 */
	static String keys(long amount)
	{
		if (amount != 0 && amount % 1_000_000_000L == 0)
		{
			return amount / 1_000_000_000L + "b";
		}
		if (amount != 0 && amount % 1_000_000L == 0)
		{
			return amount / 1_000_000L + "m";
		}
		if (amount != 0 && amount % 1_000L == 0)
		{
			return amount / 1_000L + "k";
		}
		return Long.toString(amount);
	}

	/** This flip's own offer page is open. */
	private boolean offerOpen(GameView game)
	{
		return game.detailsOpen && slot >= 0 && game.selectedSlot == slot;
	}

	/**
	 * The live check of a search, like the check of a typed number: in words, never by colour alone.
	 * A search for something else isn't a mistake: the player may be buying for themselves.
	 */
	static String searchCheck(String typed, String name, boolean shown)
	{
		if (shown)
		{
			return "It's in the list. Click it.";
		}
		String text = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
		if (text.isEmpty())
		{
			return "Then click it in the list.";
		}
		return name.toLowerCase(Locale.ROOT).contains(text) ? "Keep typing."
			: "Buying something else? Pick it, and we'll show its prices.";
	}

	/** The GE lists its search results in rows of 3. */
	static final int RESULTS_PER_ROW = 3;

	/**
	 * The fewest keys that bring {@code name} up in the first row of the GE's search results, so it's
	 * there to click without scrolling. The game lists every GE item whose name holds the search, in
	 * name order; {@code matches} gives those names for a search, as RuneLite's item search does.
	 * Searches start at the start of a word, as people type them. The whole name when none is shorter,
	 * or when {@code matches} doesn't know the item.
	 */
	static String shortestSearch(String name, Function<String, List<String>> matches)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		String best = lower;
		for (int start = 0; start < lower.length(); start++)
		{
			if (start > 0 && lower.charAt(start - 1) != ' ' || lower.charAt(start) == ' ')
			{
				continue;
			}
			for (int end = start + 1; end <= lower.length() && end - start < best.length(); end++)
			{
				String search = lower.substring(start, end);
				if (!search.endsWith(" ") && inFirstRow(lower, matches.apply(search)))
				{
					best = search;
					break;
				}
			}
		}
		return best;
	}

	private static boolean inFirstRow(String name, List<String> names)
	{
		boolean found = false;
		int before = 0;
		for (String other : names)
		{
			String lower = other.toLowerCase(Locale.ROOT);
			if (!found && lower.equals(name))
			{
				found = true;
			}
			else if (lower.compareTo(name) <= 0)
			{
				before++;
			}
		}
		return found && before < RESULTS_PER_ROW;
	}

	/** What the game makes of typed text, as {@link #keys} writes it, or -1 when it isn't a whole amount. */
	static long parse(String typed)
	{
		String text = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
		long multiplier = 1;
		if (text.endsWith("k") || text.endsWith("m") || text.endsWith("b"))
		{
			char unit = text.charAt(text.length() - 1);
			multiplier = unit == 'k' ? 1_000L : unit == 'm' ? 1_000_000L : 1_000_000_000L;
			text = text.substring(0, text.length() - 1);
		}
		if (text.isEmpty() || text.length() > 10 || !text.chars().allMatch(Character::isDigit))
		{
			return -1;
		}
		try
		{
			return Math.multiplyExact(Long.parseLong(text), multiplier);
		}
		catch (ArithmeticException e)
		{
			return -1;
		}
	}

	/** The live check of what's typed, in words, so it never relies on colour. */
	static String check(String typed, long ours, String what, String unit)
	{
		long value = parse(typed);
		if (value < 0)
		{
			return typed != null && typed.contains(".") ? "The game won't take a decimal here." : "Then press Enter.";
		}
		if (value == ours)
		{
			return "Matches our " + what + ". Press Enter.";
		}
		long gap = Math.abs(value - ours);
		return "That's " + BoxText.whole(gap) + unit + (value > ours ? " more" : " less") + " than our " + what + ".";
	}
}
