package com.gielinorgains;

import static com.gielinorgains.TraderTest.GSON;
import static com.gielinorgains.TraderTest.T0;
import static com.gielinorgains.TraderTest.ready;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class MakeSellTest
{
	/** High Alchemy: a Rune platebody and a nature rune bought at once, for 39,000 coins. Members. */
	static PlanFeed.Swap alch()
	{
		return GSON.fromJson("{\"id\":\"alch:1127\",\"title\":\"Alch Rune platebody\",\"step\":\"Cast High Level Alchemy"
			+ " on each one.\",\"where\":\"Anywhere\",\"members\":true,\"needs\":[\"55 Magic\"],\"buy\":[{\"id\":1127,"
			+ "\"name\":\"Rune platebody\",\"qty\":1,\"price\":38200},{\"id\":561,\"name\":\"Nature rune\",\"qty\":1,"
			+ "\"price\":95,\"instant\":true}],\"sell\":[],\"coins\":39000,\"each\":705,\"cost\":38295,\"units\":70,"
			+ "\"play\":390}", PlanFeed.Swap.class);
	}

	/** Decanting: four 3-dose potions make three 4-dose ones. Free to play. */
	static PlanFeed.Swap decant()
	{
		return GSON.fromJson("{\"id\":\"decant:139\",\"title\":\"Decant Prayer potion(3)\",\"step\":\"Decant them with Bob"
			+ " Barter.\",\"where\":\"Bob Barter\",\"members\":false,\"needs\":[],\"buy\":[{\"id\":139,\"name\":\"Prayer"
			+ " potion(3)\",\"qty\":4,\"price\":9000}],\"sell\":[{\"id\":2434,\"name\":\"Prayer potion(4)\",\"qty\":3,"
			+ "\"price\":12400}],\"coins\":0,\"each\":456,\"cost\":36000,\"units\":100,\"play\":600}", PlanFeed.Swap.class);
	}

	static PlanFeed.MakeSell feed(PlanFeed.Swap... swaps)
	{
		PlanFeed.MakeSell feed = new PlanFeed.MakeSell();
		feed.mode = "preview";
		feed.window = MakeSell.window(T0);
		feed.ends = MakeSell.windowEnd(feed.window);
		feed.swaps = new ArrayList<>(Arrays.asList(swaps));
		return feed;
	}

	private static String summary(List<MakeSell.Offer> open)
	{
		List<String> out = new ArrayList<>();
		for (MakeSell.Offer o : open)
		{
			out.add(o.getSwap().id + " " + o.getUnits() + " " + o.getProfit() + " " + o.getCost() + " " + o.getPlaySeconds());
		}
		return String.join(", ", out);
	}

	@Test
	public void offersSwapsAsTheWebsiteSizesAndRanksThem()
	{
		// The website's openSwaps on the same two swaps gave these, in this order, with these rates.
		PlanFeed.MakeSell feed = feed(decant(), alch());
		assertEquals("alch:1127 70 49350 2680650 390, decant:139 100 45600 3600000 600",
			summary(MakeSell.open(feed, 10_000_000, false, Collections.emptyList())));
		assertEquals("alch:1127 26 18330 995670 258, decant:139 27 12312 972000 293",
			summary(MakeSell.open(feed, 1_000_000, false, Collections.emptyList())));
		assertEquals(329_000.0, MakeSell.rate(alch(), 70), 0.001);
		assertEquals(145_920.0, MakeSell.rate(decant(), 100), 0.001);
		assertEquals(54_158.358, MakeSell.rate(decant(), 27), 0.001);
		assertEquals(11_305.785, MakeSell.rate(decant(), 5), 0.001);
	}

	@Test
	public void leavesOutMembersSwapsOnFreeWorldsSkippedOnesAndBatchesUnder1000Gp()
	{
		PlanFeed.MakeSell feed = feed(decant(), alch());
		assertEquals("decant:139 27 12312 972000 293", summary(MakeSell.open(feed, 1_000_000, true, Collections.emptyList())));
		assertEquals("decant:139 27 12312 972000 293",
			summary(MakeSell.open(feed, 1_000_000, false, Collections.singletonList("alch:1127"))));
		// One platebody makes 705: too little to offer.
		assertEquals("", summary(MakeSell.open(feed, 40_000, false, Collections.emptyList())));
		assertEquals("", summary(MakeSell.open(null, 10_000_000, false, Collections.emptyList())));
	}

	@Test
	public void listsTheStepsInTheWebsitesWords()
	{
		assertEquals(Arrays.asList("Buy 70 x Rune platebody at 38,200 GP each", "Buy 70 x Nature rune at 95 GP each (it buys at once)",
			"Cast High Level Alchemy on each one.", "Keep the coins: 2,730,000 GP."), MakeSell.steps(alch(), 70));
		assertEquals(Arrays.asList("Buy 108 x Prayer potion(3) at 9,000 GP each", "Decant them with Bob Barter.",
			"Sell 81 x Prayer potion(4) at 12,400 GP each"), MakeSell.steps(decant(), 27));
		assertEquals("about 7 min", MakeSell.minutes(390));
		assertEquals("about 1 h", MakeSell.minutes(3_600));
		assertEquals("about 1.5 h", MakeSell.minutes(5_400));
	}

	private static String words(Trader.Step step)
	{
		return step == null ? null : step.getKind() + " " + step.getTitle() + " / " + step.getDetail()
			+ (step.getStatus() == null ? "" : " / " + step.getStatus());
	}

	@Test
	public void walksASwapFromItsBuysToItsLastCoins()
	{
		Trader trader = ready(10_000_000);
		PlanFeed.Swap swap = decant();
		long since = T0;
		assertEquals("BUY Buy Prayer potion(3) / 400 at 9,000 each · GE slot 1 / Watching for your buy in slot 1",
			words(trader.swapStep(swap, 100, since)));
		trader.onOffer(0, 139, "BUYING", 150, 400, 9000, 1_350_000, T0 + 1000, true);
		assertEquals("WAIT Buying Prayer potion(3) / Bought 150 of 400 so far. / Watching your buy in GE slot 1",
			words(trader.swapStep(swap, 100, since)));
		trader.onOffer(0, 139, "BOUGHT", 400, 400, 9000, 3_600_000, T0 + 2000, true);
		Trader.Step collect = trader.swapStep(swap, 100, since);
		assertEquals("COLLECT Collect Prayer potion(3) / 400 bought. Then: Decant them with Bob Barter. / Ready in GE slot 1",
			words(collect));
		assertEquals("Decant them with Bob Barter.", collect.getThen());
		trader.onOffer(0, 139, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		Trader.Step make = trader.swapStep(swap, 100, since);
		assertEquals("MAKE Decant Prayer potion(3) / Decant them with Bob Barter. Then: Sell 300 x Prayer potion(4) at"
			+ " 12,400 GP each.", words(make));
		assertEquals(139, make.getItemId());
		trader.onOffer(0, 2434, "SELLING", 100, 300, 12400, 1_240_000, T0 + 4000, true);
		assertEquals("WAIT Selling Prayer potion(4) / Sold 100 of 300 so far. / Watching your sale in GE slot 1",
			words(trader.swapStep(swap, 100, since)));
		trader.onOffer(0, 2434, "SOLD", 300, 300, 12400, 3_720_000, T0 + 5000, true);
		assertEquals("COLLECT Collect your coins / From your Prayer potion(4) sale / Ready in GE slot 1",
			words(trader.swapStep(swap, 100, since)));
		trader.onOffer(0, 2434, "EMPTY", 0, 0, 0, 0, T0 + 6000, true);
		assertNull(trader.swapStep(swap, 100, since));
	}

	@Test
	public void alreadyDoneMovesPastAStepAndOldTradesDontCount()
	{
		Trader trader = ready(10_000_000);
		PlanFeed.Swap swap = decant();
		// Bought before the swap was taken: not this swap's.
		trader.onOffer(1, 139, "BOUGHT", 400, 400, 9000, 3_600_000, T0 + 1000, true);
		trader.onOffer(1, 139, "EMPTY", 0, 0, 0, 0, T0 + 1500, true);
		Trader.Step buy = trader.swapStep(swap, 100, T0 + 2000);
		assertEquals(Trader.Kind.BUY, buy.getKind());
		trader.dismiss(buy.getKey());
		Trader.Step make = trader.swapStep(swap, 100, T0 + 2000);
		assertEquals(Trader.Kind.MAKE, make.getKind());
		trader.dismiss(make.getKey());
		assertEquals("SELL Sell Prayer potion(4) / 300 at 12,400 each / Click it in your inventory at the GE",
			words(trader.swapStep(swap, 100, T0 + 2000)));
		// "Already done" on a wait moves past the whole buy, as on the buy itself.
		Trader waiting = ready(10_000_000);
		waiting.onOffer(0, 139, "BUYING", 10, 400, 9000, 90_000, T0 + 3000, true);
		Trader.Step wait = waiting.swapStep(swap, 100, T0 + 2000);
		assertEquals(Trader.Kind.WAIT, wait.getKind());
		waiting.dismiss(wait.getKey());
		assertEquals(Trader.Kind.MAKE, waiting.swapStep(swap, 100, T0 + 2000).getKind());
		// A sale listed before the swap was taken isn't the swap's: the step in the game still shows.
		Trader old = ready(10_000_000);
		old.onOffer(2, 2434, "SELLING", 0, 50, 12500, 0, T0 + 500, true);
		Trader.Step fresh = old.swapStep(swap, 100, T0 + 2000);
		assertEquals(Trader.Kind.BUY, fresh.getKind());
		old.dismiss(fresh.getKey());
		assertEquals(Trader.Kind.MAKE, old.swapStep(swap, 100, T0 + 2000).getKind());
		assertTrue(Trader.isSwap(buy));
		assertEquals(0, TraderController.swapIndex(swap, buy));
		assertEquals(1, TraderController.swapIndex(swap, make));
		assertEquals(2, TraderController.swapIndex(swap, trader.swapStep(swap, 100, T0 + 2000)));
	}

	@Test
	public void aSwapWithNoSalesEndsAtItsStepInTheGame()
	{
		Trader trader = ready(10_000_000);
		PlanFeed.Swap swap = alch();
		trader.onOffer(0, 1127, "BOUGHT", 70, 70, 38200, 2_674_000, T0 + 1000, true);
		trader.onOffer(0, 1127, "EMPTY", 0, 0, 0, 0, T0 + 1500, true);
		Trader.Step runes = trader.swapStep(swap, 70, T0);
		assertEquals("BUY Buy Nature rune / 70 at 95 each · GE slot 1 (it buys at once) / Watching for your buy in slot 1",
			words(runes));
		assertEquals("Cast High Level Alchemy on each one.", runes.getThen());
		trader.onOffer(0, 561, "BOUGHT", 70, 70, 95, 6_650, T0 + 2000, true);
		trader.onOffer(0, 561, "EMPTY", 0, 0, 0, 0, T0 + 2500, true);
		Trader.Step make = trader.swapStep(swap, 70, T0);
		assertEquals("MAKE Alch Rune platebody / Cast High Level Alchemy on each one. Keep the coins: 2,730,000 GP.",
			words(make));
		// The player says when it's done; nothing at the GE can.
		assertEquals(make.getKey(), trader.swapStep(swap, 70, T0).getKey());
		assertEquals(1127, make.getItemId());
	}

	private static Guide.GameView index()
	{
		Guide.GameView game = new Guide.GameView();
		Arrays.fill(game.slotItem, -1);
		Arrays.fill(game.slotState, GrandExchangeOfferState.EMPTY);
		return game;
	}

	@Test
	public void theGuideEndsASwapsBuyAtItsCollect()
	{
		Guide guide = new Guide();
		guide.pin(139, "Prayer potion(3)");
		guide.setThen("Decant them with Bob Barter.");
		ItemCheck.Flip flip = new ItemCheck.Flip();
		flip.buy = 9000.0;
		flip.sell = 0.0;
		flip.qty = 400.0;
		guide.onOffer(0, 139, GrandExchangeOfferState.BUYING, 0, flip);
		Guide.GameView game = index();
		game.slotItem[0] = 139;
		game.slotState[0] = GrandExchangeOfferState.BUYING;
		assertEquals("Your buy offer is in. / When it fills: Decant them with Bob Barter.", say(guide.step(game, flip, true)));
		guide.onOffer(0, 139, GrandExchangeOfferState.BOUGHT, 400, flip);
		game.slotState[0] = GrandExchangeOfferState.BOUGHT;
		assertEquals("Bought 400. Collect them. / Then: Decant them with Bob Barter.", say(guide.step(game, flip, true)));
		assertEquals("Your buy of 400 Prayer potion(3) filled. Collect them. Then: Decant them with Bob Barter.",
			guide.filledMessage(flip));
		guide.onOffer(0, -1, GrandExchangeOfferState.EMPTY, 0, flip);
		game.slotItem[0] = -1;
		game.slotState[0] = GrandExchangeOfferState.EMPTY;
		assertEquals("Bought 400. / Then: Decant them with Bob Barter.", say(guide.step(game, flip, true)));
		// A flip pinned after it is guided to its sale again.
		guide.pin(139, "Prayer potion(3)");
		flip.sell = 9500.0;
		guide.onOffer(0, 139, GrandExchangeOfferState.BUYING, 0, flip);
		assertEquals("Your buy offer is in. / When it fills, we'll sell at 9,500.", say(guide.step(game, flip, true)));
	}

	private static String say(Guide.Step step)
	{
		return step.getAction() + " / " + step.getDetail();
	}
}
