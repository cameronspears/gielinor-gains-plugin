package com.gielinorgains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class GuideTest
{
	/** The site fixture's Cannonball flip: buy 259, sell 269, quantity 11,000. */
	private static ItemCheck.Flip flip() throws Exception
	{
		return Fixtures.snapshot().items.get(2).flip;
	}

	private static Guide pinned()
	{
		Guide guide = new Guide();
		guide.pin(2, "Cannonball");
		return guide;
	}

	private static Guide.GameView index()
	{
		Guide.GameView game = new Guide.GameView();
		Arrays.fill(game.slotItem, -1);
		Arrays.fill(game.slotState, GrandExchangeOfferState.EMPTY);
		return game;
	}

	private static Guide.GameView setup(boolean sell, int quantity, long price)
	{
		Guide.GameView game = index();
		game.setupOpen = true;
		game.setupItem = 2;
		game.setupSell = sell;
		game.setupQuantity = quantity;
		game.setupPrice = price;
		return game;
	}

	private static String words(Guide.Step step)
	{
		return step.getAction() + (step.getDetail() == null ? "" : " / " + step.getDetail());
	}

	private static final List<String> GE_ITEMS = Arrays.asList("Wine of Zamorak", "Half full wine jug", "Jug of wine",
		"Bottle of wine", "Book of darkness", "Zamorak brew(4)", "Zamorak brew(3)", "Zamorak monk top",
		"Zamorak platebody", "Ring of wealth", "Bronze arrow", "Bronze bar", "Bronze dagger", "Anchovy pizza",
		"Pizza base", "Plain pizza", "Lizardman fang", "Cannonball", "Granite cannonball", "Cannon base");

	private static List<String> search(String query)
	{
		List<String> out = new ArrayList<>();
		for (String name : GE_ITEMS)
		{
			if (name.toLowerCase(java.util.Locale.ROOT).contains(query))
			{
				out.add(name);
			}
		}
		return out;
	}

	@Test
	public void shortestSearchPutsTheItemInTheFirstRow()
	{
		// "wine" lists 4 in name order, with Wine of Zamorak 4th; "z" and "za" list Bronze and pizza first.
		// "zam" lists it first, ahead of every Zamorak item.
		assertEquals("zam", Guide.shortestSearch("Wine of Zamorak", GuideTest::search));
		// Ties go to the search from the start of the name.
		assertEquals("j", Guide.shortestSearch("Jug of wine", GuideTest::search));
		// A search can start at any word: "d", for "dagger", lists it 2nd, after Book of darkness.
		assertEquals("d", Guide.shortestSearch("Bronze dagger", GuideTest::search));
		// An item the list doesn't know gets its whole name.
		assertEquals("dragon bones", Guide.shortestSearch("Dragon bones", GuideTest::search));
	}

	@Test
	public void fewestKeysForTheExactAmount()
	{
		assertEquals("0", Guide.keys(0));
		assertEquals("999", Guide.keys(999));
		assertEquals("1k", Guide.keys(1_000));
		assertEquals("1500", Guide.keys(1_500));
		assertEquals("11k", Guide.keys(11_000));
		assertEquals("16500", Guide.keys(16_500));
		assertEquals("1m", Guide.keys(1_000_000));
		assertEquals("2m", Guide.keys(2_000_000));
		assertEquals("1001k", Guide.keys(1_001_000));
		assertEquals("1234567", Guide.keys(1_234_567));
		assertEquals("3b", Guide.keys(3_000_000_000L));
		assertEquals("2147483647", Guide.keys(2_147_483_647L));
		for (long amount : new long[]{0, 7, 1_000, 11_000, 16_500, 2_000_000, 1_001_000, 3_000_000_000L, 2_147_483_647L})
		{
			assertEquals("round trip " + amount, amount, Guide.parse(Guide.keys(amount)));
			assertTrue("never longer than the digits", Guide.keys(amount).length() <= Long.toString(amount).length());
		}
	}

	@Test
	public void readsWhatThePlayerTypedAsTheGameWould()
	{
		assertEquals(11_000, Guide.parse("11k"));
		assertEquals(11_000, Guide.parse("11K"));
		assertEquals(2_000_000, Guide.parse("2m"));
		assertEquals(3_000_000_000L, Guide.parse("3b"));
		assertEquals(16_500, Guide.parse("16500"));
		assertEquals(-1, Guide.parse(""));
		assertEquals(-1, Guide.parse("k"));
		assertEquals("the game takes no decimals", -1, Guide.parse("1.5k"));
		assertEquals(-1, Guide.parse(null));
	}

	@Test
	public void checksTheTypedAmountInWords()
	{
		assertEquals("Then press Enter.", Guide.check("", 11_000, "quantity", ""));
		assertEquals("Matches our quantity. Press Enter.", Guide.check("11k", 11_000, "quantity", ""));
		assertEquals("That's 500 more than our quantity.", Guide.check("11500", 11_000, "quantity", ""));
		assertEquals("That's 1,000 less than our quantity.", Guide.check("10k", 11_000, "quantity", ""));
		assertEquals("That's 2 GP more than our price.", Guide.check("261", 259, "price", " GP"));
	}

	@Test
	public void handsOffWithoutAPinOrOnAnotherItem() throws Exception
	{
		assertNull(new Guide().step(index(), flip(), true));
		Guide.GameView other = setup(false, 0, 0);
		other.setupItem = 4151;
		assertNull("another item gets the usual check", pinned().step(other, flip(), true));
		assertNull("a sell while buying gets the usual check", pinned().step(setup(true, 0, 0), flip(), true));
	}

	@Test
	public void guidesTheBuyStepByStep() throws Exception
	{
		Guide guide = pinned();
		Guide.GameView game = index();
		game.slotItem[0] = 4151;
		game.slotState[0] = GrandExchangeOfferState.BUYING;
		Guide.Step step = guide.step(game, flip(), true);
		assertEquals(Guide.Target.BUY_BUTTON, step.getTarget());
		assertEquals("first empty slot", 1, step.getSlot());
		assertEquals("Click Buy on an empty GE slot. / We'll buy 11,000 Cannonball.", words(step));
		assertEquals(1, step.getStage());

		game.prompt = Guide.Prompt.SEARCH;
		step = guide.step(game, flip(), true);
		assertEquals(Guide.Target.SEARCH_RESULT, step.getTarget());
		assertEquals("Type cannonball / Then click it in the list.", words(step));
		// The search gets the same live check as a typed number.
		game.typed = "cann";
		assertEquals("Type cannonball / Keep typing.", words(guide.step(game, flip(), true)));
		game.typed = "canon";
		assertEquals("Type cannonball / Buying something else? Pick it, and we'll show its prices.", words(guide.step(game, flip(), true)));
		game.typed = "cannonb";
		game.resultShown = true;
		assertEquals("Type cannonball / It's in the list. Click it.", words(guide.step(game, flip(), true)));
		game.resultShown = false;
		game.typed = "";
		// With the shortest search worked out, the step asks for just that.
		game.searchKeys = "cannonb";
		assertEquals("Type cannonb / Then click it in the list.", words(guide.step(game, flip(), true)));
		game.searchKeys = null;

		step = guide.step(setup(false, 1, 255), flip(), true);
		assertEquals(Guide.Target.QUANTITY_BUTTON, step.getTarget());
		assertEquals("Set the quantity to 11,000. / It's 1 now. Click ... to change it.", words(step));

		Guide.GameView typing = setup(false, 1, 255);
		typing.prompt = Guide.Prompt.QUANTITY;
		typing.typed = "11k";
		step = guide.step(typing, flip(), true);
		assertEquals(Guide.Target.NONE, step.getTarget());
		assertEquals("Type 11k / Matches our quantity. Press Enter.", words(step));

		step = guide.step(setup(false, 11_000, 255), flip(), true);
		assertEquals(Guide.Target.PRICE_BUTTON, step.getTarget());
		assertEquals("Set the price to 259. / It's 255 now. Click ... to change it.", words(step));

		Guide.GameView price = setup(false, 11_000, 255);
		price.prompt = Guide.Prompt.PRICE;
		price.typed = "260";
		assertEquals("Type 259 / That's 1 GP more than our price.", words(guide.step(price, flip(), true)));

		step = guide.step(setup(false, 11_000, 259), flip(), true);
		assertEquals(Guide.Target.CONFIRM, step.getTarget());
		assertEquals(5, step.getStage());
		assertEquals("Buying Cannonball, step 5 of 5",
			BoxText.guide("Cannonball", guide.phase(), step, "tip").getLines().get(1).getLeft());
		assertEquals("Quantity and price match. / Click Confirm.", words(step));
	}

	@Test
	public void saysSoWhenEverySlotIsInUse() throws Exception
	{
		Guide.GameView free = index();
		free.usableSlots = 3;
		for (int i = 0; i < 3; i++)
		{
			free.slotItem[i] = 4151;
			free.slotState[i] = GrandExchangeOfferState.BUYING;
		}
		Guide.Step step = pinned().step(free, flip(), true);
		assertEquals("All your GE slots are in use. / Collect or cancel an offer first.", words(step));
		assertEquals(Guide.Target.NONE, step.getTarget());
	}

	@Test
	public void followsTheOfferThenGuidesTheSell() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(3, 4151, GrandExchangeOfferState.BOUGHT, 5, null);
		assertEquals("other items are ignored", Guide.Phase.BUY, guide.phase());

		guide.onOffer(3, 2, GrandExchangeOfferState.BUYING, 0, null);
		assertEquals("Your buy offer is in. / When it fills, we'll sell at 269.", words(guide.step(index(), flip(), true)));
		guide.onOffer(3, 2, GrandExchangeOfferState.BUYING, 4_000, null);
		assertEquals("Your buy offer is in. / Bought 4,000 of 11,000 so far. Then we'll sell at 269.",
			words(guide.step(index(), flip(), true)));

		guide.onOffer(3, 2, GrandExchangeOfferState.BOUGHT, 11_000, null);
		Guide.GameView uncollected = index();
		uncollected.slotItem[3] = 2;
		uncollected.slotState[3] = GrandExchangeOfferState.BOUGHT;
		Guide.Step step = guide.step(uncollected, flip(), true);
		assertEquals("Bought 11,000. Collect them. / Then sell at 269.", words(step));
		assertEquals(Guide.Target.SLOT, step.getTarget());
		assertEquals(3, step.getSlot());
		// With its own offer open, the guide points at the items to collect; another offer's page changes nothing.
		uncollected.detailsOpen = true;
		uncollected.selectedSlot = 3;
		step = guide.step(uncollected, flip(), true);
		assertEquals("Bought 11,000. Collect them. / Click them below the offer, then sell at 269.", words(step));
		assertEquals(Guide.Target.COLLECT, step.getTarget());
		uncollected.selectedSlot = 1;
		assertEquals(Guide.Target.SLOT, guide.step(uncollected, flip(), true).getTarget());
		uncollected.detailsOpen = false;
		uncollected.selectedSlot = -1;

		step = guide.step(index(), flip(), true);
		assertEquals(Guide.Target.INVENTORY_ITEM, step.getTarget());
		assertEquals("Click the Cannonball in your inventory. / We'll sell 11,000 at 269.", words(step));

		assertEquals(Guide.Target.QUANTITY_BUTTON, guide.step(setup(true, 1, 260), flip(), true).getTarget());
		assertEquals("Set the price to 269. / It's 260 now. Click ... to change it.",
			words(guide.step(setup(true, 11_000, 260), flip(), true)));
		assertEquals(Guide.Target.CONFIRM, guide.step(setup(true, 11_000, 269), flip(), true).getTarget());
		assertNull("a buy while selling gets the usual check", guide.step(setup(false, 1, 1), flip(), true));

		guide.onOffer(3, 2, GrandExchangeOfferState.SELLING, 0, null);
		assertEquals("Your sell offer is in. / We'll tell you when it sells.", words(guide.step(index(), flip(), true)));
		guide.onOffer(3, 2, GrandExchangeOfferState.SELLING, 2_500, null);
		assertEquals("Your sell offer is in. / Sold 2,500 of 11,000 so far.", words(guide.step(index(), flip(), true)));
		guide.onOffer(3, 2, GrandExchangeOfferState.SOLD, 11_000, null);
		uncollected.slotState[3] = GrandExchangeOfferState.SOLD;
		assertEquals("Sold! Collect your coins. / No other flips right now.", words(guide.step(uncollected, flip(), true)));
		uncollected.detailsOpen = true;
		uncollected.selectedSlot = 3;
		step = guide.step(uncollected, flip(), true);
		assertEquals("Sold! Collect your coins. / Click them below the offer.", words(step));
		assertEquals(Guide.Target.COLLECT, step.getTarget());
		assertEquals("Sold! / No other flips right now.", words(guide.step(index(), flip(), true)));
		guide.setDoneLine("Today so far: +38,670 GP from 1 flip.");
		assertEquals("Sold! / Today so far: +38,670 GP from 1 flip.", words(guide.step(index(), flip(), true)));
	}

	@Test
	public void aPartFillCanBeSold() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.CANCELLED_BUY, 4_000, null);
		assertEquals(Guide.Phase.SELL, guide.phase());
		assertEquals("Click the Cannonball in your inventory. / We'll sell 4,000 at 269.", words(guide.step(index(), flip(), true)));
		Guide nothing = pinned();
		nothing.onOffer(0, 2, GrandExchangeOfferState.CANCELLED_BUY, 0, null);
		assertEquals("nothing bought: back to the buy", Guide.Phase.BUY, nothing.phase());
	}

	@Test
	public void neverGuessesAPriceForAFlipThatLeftTheList()
	{
		assertEquals("Cannonball isn't in today's flips now. / Pick another flip.", words(pinned().step(index(), null, true)));
		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, null);
		assertEquals("Cannonball left today's flips. / We have no sell price now, and we won't guess one.",
			words(guide.step(index(), null, true)));
	}

	@Test
	public void anOlderOfferForTheSameItemCantTakeOver() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(5, 2, GrandExchangeOfferState.SELLING, 0, null);
		assertEquals("an old sell can't bind while buying", Guide.Phase.BUY, guide.phase());
		guide.onOffer(2, 2, GrandExchangeOfferState.BUYING, 0, flip());
		guide.onOffer(5, 2, GrandExchangeOfferState.SOLD, 900, null);
		guide.onOffer(6, 2, GrandExchangeOfferState.CANCELLED_SELL, 0, null);
		assertEquals("other slots are ignored once bound", Guide.Phase.BUYING, guide.phase());
	}

	@Test
	public void neverPointsAtConfirmWithoutBothNumbers()
	{
		Guide.Step step = pinned().step(setup(false, 11_000, 259), null, true);
		assertEquals(Guide.Target.NONE, step.getTarget());
		assertEquals("Cannonball left today's flips. / We have no price now, and we won't guess one.", words(step));
	}

	@Test
	public void aCancelledPartSellSellsOnlyWhatsLeft() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(1, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip());
		guide.onOffer(1, -1, GrandExchangeOfferState.EMPTY, 0, null);
		guide.onOffer(4, 2, GrandExchangeOfferState.SELLING, 0, flip());
		guide.onOffer(4, 2, GrandExchangeOfferState.CANCELLED_SELL, 4_000, flip());
		assertEquals(Guide.Phase.SELL, guide.phase());

		Guide.GameView waiting = index();
		waiting.slotItem[4] = 2;
		waiting.slotState[4] = GrandExchangeOfferState.CANCELLED_SELL;
		Guide.Step step = guide.step(waiting, flip(), true);
		assertEquals("Collect your Cannonball and coins. / Then sell at 269.", words(step));
		assertEquals(Guide.Target.SLOT, step.getTarget());
		assertEquals(4, step.getSlot());

		guide.onOffer(4, -1, GrandExchangeOfferState.EMPTY, 0, null);
		assertEquals("Click the Cannonball in your inventory. / We'll sell 7,000 at 269.", words(guide.step(index(), flip(), true)));
	}

	@Test
	public void everyChangeIsCountedSoTheBoxUpdates() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip());
		int before = guide.version();
		guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 4_000, flip());
		assertTrue(guide.version() > before);
		guide.onOffer(3, 4151, GrandExchangeOfferState.BUYING, 1, null);
		assertEquals("other items don't count", before + 1, guide.version());
	}

	@Test
	public void saysSoWhenOurSellPriceMoves() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip());
		guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip());
		guide.onOffer(0, -1, GrandExchangeOfferState.EMPTY, 0, null);
		ItemCheck.Flip moved = flip();
		moved.sell = 265.0;
		assertEquals("Click the Cannonball in your inventory. / We'll sell 11,000 at 265 (was 269).",
			words(guide.step(index(), moved, true)));
		assertEquals("Cannonball left today's flips. / We had planned to sell at 269. We have no newer price.",
			words(guide.step(index(), null, true)));
	}

	@Test
	public void stalePricesOnlyPauseStepsThatNeedThem() throws Exception
	{
		assertEquals(Guide.WAIT_FOR_PRICES, pinned().step(index(), flip(), false));
		assertEquals(Guide.WAIT_FOR_PRICES, pinned().step(setup(false, 1, 1), flip(), false));

		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip());
		// The sale price planned when the buy went in still shows while prices update.
		assertEquals("Your buy offer is in. / When it fills, we'll sell at 269.", words(guide.step(index(), null, false)));
		guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip());
		Guide.GameView uncollected = index();
		uncollected.slotItem[0] = 2;
		uncollected.slotState[0] = GrandExchangeOfferState.BOUGHT;
		assertEquals("Bought 11,000. Collect them. / Then sell them.", words(guide.step(uncollected, null, false)));
		assertEquals(Guide.WAIT_FOR_PRICES, guide.step(index(), flip(), false));
	}

	@Test
	public void oddTypingIsHandled()
	{
		assertEquals("too big for the game", -1, Guide.parse("9999999999b"));
		assertEquals(-1, Guide.parse("12345678901"));
		assertEquals("The game won't take a decimal here.", Guide.check("1.5k", 1_500, "quantity", ""));
	}

	@Test
	public void notifiesOnceForAFillAndOnceForASale() throws Exception
	{
		Guide guide = pinned();
		assertEquals(Guide.Event.NONE, guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip()));
		assertEquals("a part fill isn't news", Guide.Event.NONE, guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 4_000, flip()));
		assertEquals(Guide.Event.FILLED, guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip()));
		assertEquals("seen again at login", Guide.Event.NONE, guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip()));
		assertEquals("Your buy of 11,000 Cannonball filled. Collect them, then sell at 269.", guide.filledMessage(flip()));
		assertEquals("Your buy of 11,000 Cannonball filled. Collect them, then sell at 269.", guide.filledMessage(null));

		guide.onOffer(0, -1, GrandExchangeOfferState.EMPTY, 0, null);
		assertEquals(Guide.Event.NONE, guide.onOffer(2, 2, GrandExchangeOfferState.SELLING, 0, flip()));
		assertEquals(Guide.Event.SOLD, guide.onOffer(2, 2, GrandExchangeOfferState.SOLD, 11_000, flip()));
		assertEquals(Guide.Event.NONE, guide.onOffer(2, 2, GrandExchangeOfferState.SOLD, 11_000, flip()));
		assertEquals("Your Cannonball sold.", guide.soldMessage());

		Guide cancelled = pinned();
		cancelled.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip());
		assertEquals("a cancelled part fill is the player's own doing", Guide.Event.NONE,
			cancelled.onOffer(0, 2, GrandExchangeOfferState.CANCELLED_BUY, 300, flip()));
	}

	@Test
	public void theCursorTooltipSaysTheStep() throws Exception
	{
		Guide guide = pinned();
		assertEquals("Set quantity: type 11k", guide.step(setup(false, 1, 1), flip(), true).getHover());
		assertEquals("Set price: type 259", guide.step(setup(false, 11_000, 1), flip(), true).getHover());
		assertEquals("Confirm: your offer matches", guide.step(setup(false, 11_000, 259), flip(), true).getHover());
		assertEquals("Buy: start your offer here", guide.step(index(), flip(), true).getHover());
	}

	@Test
	public void afterASaleItOffersTheNextFlip() throws Exception
	{
		Guide guide = pinned();
		guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 11_000, flip());
		guide.onOffer(0, -1, GrandExchangeOfferState.EMPTY, 0, null);
		guide.onOffer(1, 2, GrandExchangeOfferState.SOLD, 11_000, flip());
		guide.onOffer(1, -1, GrandExchangeOfferState.EMPTY, 0, null);
		assertEquals("Sold! / No other flips right now.", words(guide.step(index(), flip(), true)));
		int before = guide.version();
		guide.setNext("Nature rune");
		assertTrue(guide.version() > before);
		assertEquals("Sold! / Next flip: Nature rune. Pin it in the list.", words(guide.step(index(), flip(), true)));
	}

	/** Every sentence the guide can show, for the reading-level and ASCII checks. */
	static List<String> allWords() throws Exception
	{
		List<String> out = new ArrayList<>();
		Guide guide = pinned();
		ItemCheck.Flip flip = flip();
		Guide.GameView full = index();
		full.usableSlots = 0;
		Guide.GameView search = index();
		search.prompt = Guide.Prompt.SEARCH;
		Guide.GameView typing = setup(false, 1, 1);
		typing.prompt = Guide.Prompt.QUANTITY;
		for (String typed : new String[]{"", "11k", "12k", "10k"})
		{
			typing.typed = typed;
			out.add(words(guide.step(typing, flip, true)));
		}
		for (Guide.GameView game : Arrays.asList(index(), full, search, setup(false, 1, 1), setup(false, 11_000, 1),
			setup(false, 11_000, 259)))
		{
			out.add(words(guide.step(game, flip, true)));
		}
		out.add(words(guide.step(index(), null, true)));
		out.add(guide.filledMessage(flip));
		out.add(guide.soldMessage());
		out.add(BoxText.COLLECT_TIP);
		for (GrandExchangeOfferState state : new GrandExchangeOfferState[]{GrandExchangeOfferState.BUYING,
			GrandExchangeOfferState.BOUGHT, GrandExchangeOfferState.SELLING, GrandExchangeOfferState.SOLD})
		{
			guide.onOffer(0, 2, state, 11_000, null);
			out.add(words(guide.step(index(), flip, true)));
			Guide.Step step = guide.step(setup(guide.phase() == Guide.Phase.SELL, 1, 1), flip, true);
			if (step != null && step.getHover() != null)
			{
				out.add(step.getHover() + ".");
			}
			out.add(words(guide.step(index(), null, true)));
		}
		return out;
	}

	@Test
	public void everyWordIsAscii() throws Exception
	{
		for (String s : allWords())
		{
			assertTrue("not ASCII: " + s, s.chars().allMatch(c -> c >= 0x20 && c < 0x7f));
		}
	}

	@Test
	public void walksAPriceChangeThroughModify()
	{
		Guide guide = new Guide();
		guide.reprice(2, "Cannonball", 3, 186);
		Guide.GameView game = index();
		Guide.Step open = guide.step(game, null, true);
		assertEquals(Guide.Target.SLOT, open.getTarget());
		assertEquals(3, open.getSlot());
		assertEquals("Click your Cannonball offer. / Then Modify it and set 186.", words(open));

		game.detailsOpen = true;
		assertEquals(Guide.Target.MODIFY, guide.step(game, null, true).getTarget());

		Guide.GameView modify = setup(true, 1000, 189);
		assertEquals(Guide.Target.PRICE_BUTTON, guide.step(modify, null, true).getTarget());
		modify.setupPrice = 186;
		assertEquals(Guide.Target.CONFIRM, guide.step(modify, null, true).getTarget());

		// The game can keep the last searched item after Modify; the open slot says it's our offer.
		Guide.GameView searchedElse = setup(false, 1000, 189);
		searchedElse.setupItem = 453;
		searchedElse.selectedSlot = 3;
		assertEquals("Set the price to 186. / It's 189 now. Click ... to change it.", words(guide.step(searchedElse, null, true)));
		searchedElse.selectedSlot = 1;
		assertEquals(Guide.Target.SLOT, guide.step(searchedElse, null, true).getTarget());

		// Pinning the item again ends the price change.
		guide.pin(2, "Cannonball");
		assertEquals(false, guide.repricing());
	}

	@Test
	public void picksAFlipUpAtItsCollect() throws Exception
	{
		Guide.GameView game = index();
		game.slotItem[2] = 2;
		game.slotState[2] = GrandExchangeOfferState.SOLD;
		Guide guide = pinned();
		guide.collect(2, true, 0);
		assertEquals("Sold! Collect your coins. / No other flips right now.", words(guide.step(game, flip(), true)));

		game.slotState[2] = GrandExchangeOfferState.BOUGHT;
		guide = pinned();
		guide.collect(2, false, 11_000);
		Guide.Step step = guide.step(game, flip(), true);
		assertEquals("Bought 11,000. Collect them. / Then sell at 269.", words(step));
		assertEquals(2, step.getSlot());
		// Once collected, the sale goes into whichever slot the player picks.
		guide.onOffer(2, -1, GrandExchangeOfferState.EMPTY, 0, null);
		guide.onOffer(5, 2, GrandExchangeOfferState.SELLING, 0, null);
		assertEquals("Your sell offer is in. / We'll tell you when it sells.", words(guide.step(index(), flip(), true)));
	}

	@Test
	public void afterAModifySellsAllThatsHeldNotJustTheNewOffer() throws Exception
	{
		Guide guide = pinned();
		// Modify on a partly filled buy: the new offer buys only the rest, 2,848.
		guide.onOffer(0, 2, GrandExchangeOfferState.BUYING, 0, flip());
		guide.onOffer(0, 2, GrandExchangeOfferState.BOUGHT, 2_848, flip());
		guide.onOffer(0, -1, GrandExchangeOfferState.EMPTY, 0, flip());
		guide.sellQuantity(18_000);
		Guide.Step step = guide.step(setup(true, 2_848, 269), flip(), true);
		assertEquals("Set the quantity to 18,000.", step.getAction());
		assertEquals("Set the price to 269. / It's 250 now. Click ... to change it.",
			words(guide.step(setup(true, 18_000, 250), flip(), true)));
	}

	@Test
	public void guidesASaleOfItemsBoughtEarlier() throws Exception
	{
		Guide guide = pinned();
		guide.sell(5_000);
		Guide.Step step = guide.step(setup(true, 5_000, 250), flip(), true);
		assertEquals("Set the price to 269. / It's 250 now. Click ... to change it.", words(step));
		assertEquals("A buy setup for it hands off to the item check", null, guide.step(setup(false, 5_000, 250), flip(), true));
	}
}
