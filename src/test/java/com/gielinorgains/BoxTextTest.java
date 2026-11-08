package com.gielinorgains;

import static com.gielinorgains.Fixtures.MINUTE;
import static com.gielinorgains.Fixtures.SIX_MINUTES_IN;
import static com.gielinorgains.Fixtures.SOURCE_AT;
import static com.gielinorgains.Fixtures.STALE_AT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

public class BoxTextTest
{
	private static List<String> lines(BoxText.Box box)
	{
		return box.getLines().stream()
			.map(l -> l.getRight() == null ? l.getLeft() : l.getLeft() + " | " + l.getRight())
			.collect(Collectors.toList());
	}

	private static BoxText.Box box(int itemId, boolean sell) throws Exception
	{
		return BoxText.forItem(Fixtures.snapshot(), false, itemId, sell, SIX_MINUTES_IN);
	}

	private static BoxText.Box edge(int itemId) throws Exception
	{
		return BoxText.forItem(Fixtures.edgeSnapshot(), false, itemId, false, SIX_MINUTES_IN);
	}

	@Test
	public void suggestedFlip() throws Exception
	{
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Buy offer | 259",
			"Sell offer | 269",
			"Quantity | 11,000",
			"Expected profit | 21K",
			"If it all sells | 55K",
			"Chance | 69%",
			"Last 10 tests: 8 made money",
			"1101101111",
			"Prices from 6 minutes ago"), lines(box(2, false)));
		assertEquals("the item page's squares, under the words", BoxText.Kind.SQUARES,
			box(2, false).getLines().get(8).getKind());
		assertEquals(BoxText.Kind.LEAD, box(2, false).getLines().get(4).getKind());
	}

	@Test
	public void aPriceJumpLeadsABuyThePlayerPicksButNeverASale() throws Exception
	{
		// The fixture's jump is on an item with no flip: the note leads, the rest of the box follows.
		List<String> buy = lines(box(11832, false));
		assertEquals("Gielinor Gains", buy.get(0));
		assertEquals(BoxText.PUMP_TITLE, buy.get(1));
		assertEquals(BoxText.Kind.WARN, box(11832, false).getLines().get(1).getKind());
		assertEquals("It's up about 125% on the week before, after jumping on far more trades than usual.", buy.get(2));
		assertEquals("No flip suggested right now.", buy.get(3));
		assertTrue(String.join(" ", box(11832, false).getTooltip()).startsWith("Jumps like this often fall back."));
		assertFalse(lines(box(11832, true)).contains(BoxText.PUMP_TITLE));

		// On a flip, the flip's numbers stay as they were, under the note.
		ItemCheck.Response response = Fixtures.itemCheck();
		ItemCheck.Pump pump = new ItemCheck.Pump();
		pump.rise = 0.42;
		pump.since = 1L;
		response.items.get("2").pump = pump;
		BoxText.Box flip = BoxText.forItem(ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN), false, 2,
			false, SIX_MINUTES_IN);
		List<String> flipLines = lines(flip);
		assertEquals(Arrays.asList(BoxText.PUMP_TITLE,
			"It's up about 40% on the week before, after jumping on far more trades than usual.", "Buy offer | 259"),
			flipLines.subList(1, 4));
		assertEquals(lines(box(2, false)).size() + 2, flipLines.size());
	}

	@Test
	public void aFlipWithNoStoredHistoryShowsItsTotals() throws Exception
	{
		assertEquals("Record: +1.1% after tax over 6 tests", lines(box(561, false)).get(7));
	}

	@Test
	public void buyAndSellShowTheSameNumbersButOnlySellsExplainThem() throws Exception
	{
		BoxText.Box buy = box(2, false);
		BoxText.Box sell = box(2, true);
		assertEquals(buy.getLines(), sell.getLines());
		assertTrue(String.join(" ", sell.getTooltip()).contains("These numbers are for a new flip: buying 11,000 at 259"
			+ " and selling at 269. They aren't about items you already have."));
		assertFalse(String.join(" ", buy.getTooltip()).contains("new flip"));
	}

	@Test
	public void tooltipExplainsEachNumber() throws Exception
	{
		List<String> tip = box(2, false).getTooltip();
		String joined = String.join(" ", tip);
		assertTrue(joined.startsWith("Chance: how often trades like this made money within 4 hours, at our prices and"
			+ " quantity. It isn't the chance your offer fills."));
		assertTrue(joined.contains("Expected profit: about what you'd make after tax at this quantity."));
		assertTrue(joined.contains("Record: +3.0% average profit after tax, from 12 tests. Every 2 hours we save our"
			+ " suggested flip, then test it against the trades that came next."));
		assertEquals("Estimates, not promises.", tip.get(tip.size() - 2));
		assertEquals(BoxText.SHIFT_HINT, tip.get(tip.size() - 1));
		for (String line : tip)
		{
			assertTrue(line, line.length() <= 60);
		}
	}

	@Test
	public void theBondPaysAConversionFeeNotTax() throws Exception
	{
		BoxText.Box bond = edge(13190);
		String tip = String.join(" ", bond.getTooltip());
		assertTrue(tip.contains("you'd make after the conversion fee at this quantity"));
		assertTrue(tip.contains("average profit after the conversion fee, from 1 test."));
		assertTrue(lines(bond).contains("Buy offer | 9,800,000"));
		assertTrue(lines(bond).contains("Expected profit | 182K"));
		assertTrue(lines(bond).contains("Chance | 62%"));
		assertTrue(lines(bond).contains("Last test: made money"));
	}

	@Test
	public void notSuggested() throws Exception
	{
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"No flip suggested right now.",
			"The gap between its buy and sell prices is too small to make money after tax.",
			"Last test: didn't make money",
			"0"), lines(box(4151, false)));
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"No flip suggested right now.",
			"One costs more than 3M GP, the most the Market suggests putting into one item.",
			"No track record yet"), lines(box(20997, false)));
	}

	private static TraderApi.Price price(long low, long high, long lowAt, long highAt)
	{
		TraderApi.Price price = new TraderApi.Price();
		price.low = low;
		price.high = high;
		price.lowAt = lowAt;
		price.highAt = highAt;
		return price;
	}

	@Test
	public void noFlipLeadsWithTheGePriceAsTheItemPageDoes() throws Exception
	{
		long now = SIX_MINUTES_IN / 1000;
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Buy price | 1,402,110",
			"Sell price | 1,431,000",
			"From the latest trades",
			"No flip suggested right now.",
			"The gap between its buy and sell prices is too small to make money after tax.",
			"Last test: didn't make money",
			"0"), lines(BoxText.forItem(Fixtures.snapshot(), false, 4151, false, SIX_MINUTES_IN,
			price(1_402_110, 1_431_000, now - 60, now - 120))));
		// Over a day old, the trades are dated, in UTC, with the year when it isn't this one.
		assertEquals("From trades on Oct 23", lines(BoxText.forItem(Fixtures.snapshot(), false, 999_999, false,
			SIX_MINUTES_IN, price(5, 7, 1792757160L, 1792757160L))).get(3));
		assertEquals("From trades on Sep 20, 2025 (buy) and Oct 22 (sell)", lines(BoxText.forItem(Fixtures.snapshot(),
			false, 999_999, false, SIX_MINUTES_IN, price(5, 7, 1758369960L, 1792670760L))).get(3));
		// Without both prices there's nothing to lead with.
		TraderApi.Price half = price(5, 7, now, now);
		half.high = null;
		assertEquals("No flip suggested right now.", lines(BoxText.forItem(Fixtures.snapshot(), false, 999_999, false,
			SIX_MINUTES_IN, half)).get(1));
		// A flip shows its own offers, never the GE price.
		assertFalse(lines(BoxText.forItem(Fixtures.snapshot(), false, 2, false, SIX_MINUTES_IN, half)).toString()
			.contains("Buy price"));
	}

	@Test
	public void datesTheGePriceOnTheServersClock() throws Exception
	{
		// This computer's clock runs 2 days fast; trades a minute old are still the latest ones.
		long fast = SIX_MINUTES_IN + 2 * 86_400_000L;
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(Fixtures.itemCheck(), SIX_MINUTES_IN, fast);
		long now = SIX_MINUTES_IN / 1000;
		assertEquals("From the latest trades", lines(BoxText.forItem(snapshot, false, 4151, false, fast,
			price(1_402_110, 1_431_000, now - 60, now - 120))).get(3));
	}

	private static TraderApi.Offers offers(long buy, long sell, long sellAfterTax, String activity, long at)
	{
		TraderApi.Offers offers = new TraderApi.Offers();
		offers.buy = buy;
		offers.sell = sell;
		offers.sellAfterTax = sellAfterTax;
		offers.activity = activity;
		offers.at = at;
		return offers;
	}

	@Test
	public void noFlipLeadsWithTheTestedOffersAsTheItemPageDoes() throws Exception
	{
		long now = SIX_MINUTES_IN / 1000;
		TraderApi.Price price = price(1_402_110, 1_431_000, now - 60, now - 120);
		price.offers = offers(1_405_000, 1_428_000, 1_399_440, "busy", now - 240);
		BoxText.Box box = BoxText.forItem(Fixtures.snapshot(), false, 4151, false, SIX_MINUTES_IN, price);
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Buying or selling for yourself?",
			"Buy offer | 1,405,000",
			"Sell offer | 1,428,000",
			"After the 2% tax, you keep 1,399,440 GP.",
			"Prices from 4 minutes ago",
			"In our tests, about 6 in 7 offers to buy or sell one completed within an hour.",
			"No flip suggested right now.",
			"The gap between its buy and sell prices is too small to make money after tax.",
			"Last test: didn't make money",
			"0"), lines(box));
		assertEquals("These offers did best when we tested them on past GE trades.",
			String.join(" ", box.getTooltip()).substring(0, 60));
		assertTrue(String.join(" ", box.getTooltip()).contains("Need it now? Buying right away costs about 1,428,000 GP"));

		// Crossed: say so, and give no right-away prices.
		price.offers = offers(1_440_000, 1_428_000, 1_399_440, "hourly", now - 30);
		box = BoxText.forItem(Fixtures.snapshot(), false, 4151, false, SIX_MINUTES_IN, price);
		assertEquals("Prices from the last minute", lines(box).get(5));
		assertEquals(BoxText.SELF_TRADE_CROSSED, lines(box).get(6));
		assertEquals("No flip suggested right now.", lines(box).get(7));
		assertTrue(String.join(" ", box.getTooltip()).contains("a little under half of offers"));
		assertFalse(String.join(" ", box.getTooltip()).contains("Need it now?"));

		// Rare: the fill line warns, the no-flip reason shortens, and there's no right-away price.
		price.offers = offers(5, 7, 7, "rare", now - 7_200);
		box = BoxText.forItem(Fixtures.snapshot(), false, 999_999, false, SIX_MINUTES_IN, price);
		assertEquals("There's no GE tax, so you keep 7 GP.", lines(box).get(4));
		assertEquals("Prices from 2 hours ago", lines(box).get(5));
		assertTrue(lines(box).get(6).startsWith("This item trades less than once an hour"));
		assertFalse(String.join(" ", box.getTooltip()).contains("Need it now?"));
		assertEquals(BoxText.RARE_REASON, lines(BoxText.selfTrade(price.offers, 999_999, "low_volume",
			BoxText.REASONS.get("low_volume"), null, null, SIX_MINUTES_IN)).get(8));

		// The bond keeps what's left after its conversion fee.
		price.offers = offers(9_500_000, 9_600_000, 8_640_000, "busy", now - 240);
		assertEquals("After the conversion fee, you keep 8,640,000 GP.", lines(BoxText.forItem(Fixtures.snapshot(),
			false, ItemID.OSRS_BOND, false, SIX_MINUTES_IN, price)).get(4));
	}

	@Test
	public void noReasonGetsTheSitesFallback() throws Exception
	{
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"No flip suggested right now.",
			"It isn't one of today's suggestions. The Market's checks on price, trading and the 2% tax don't support"
				+ " a flip right now.",
			"Record: +4.0% after tax over 2 tests"), lines(box(11840, false)));
		assertEquals(Arrays.asList("Gielinor Gains", "No flip suggested right now.", BoxText.DEFAULT_REASON,
			"No track record yet"), lines(box(999_999, false)));
		assertEquals("unknown code", BoxText.DEFAULT_REASON, lines(edge(565)).get(2));
		assertEquals("dropped flip", BoxText.DEFAULT_REASON, lines(edge(13)).get(2));
	}

	@Test
	public void unreadableRecordsMeanNoRecordLineNotNone() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.recordsComplete = false;
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN);
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"No flip suggested right now.",
			"One costs more than 3M GP, the most the Market suggests putting into one item."),
			lines(BoxText.forItem(snapshot, false, 20997, false, SIX_MINUTES_IN)));
		assertEquals("a record we have still shows", "Last test: didn't make money",
			lines(BoxText.forItem(snapshot, false, 4151, false, SIX_MINUTES_IN)).get(3));
		assertFalse(lines(BoxText.forItem(snapshot, true, 20997, false, STALE_AT)).contains("No track record yet"));
	}

	@Test
	public void recordLines() throws Exception
	{
		assertEquals("Last 2 tests: 1 made money", lines(edge(561)).get(3));
		assertEquals("Last 10 tests: 5 made money", BoxText.recordLine(Fixtures.edgeSnapshot().items.get(12).record));
		ItemCheck.Record none = new ItemCheck.Record();
		none.avgReturn = 0.0;
		none.flips = 0;
		none.checks = "";
		assertEquals("No track record yet", BoxText.recordLine(none));
		assertEquals("No track record yet", BoxText.recordLine(null));
	}

	@Test
	public void pricesAndAgesReadAsOnTheSite()
	{
		// From the site's formatWhole and pricesAgeLabel (not in its golden file).
		Object[][] whole = {{1, "1"}, {812, "812"}, {5844, "5,844"}, {1234567, "1,234,567"},
			{2147483647, "2,147,483,647"}, {999.5, "1,000"}, {1499.5, "1,500"}};
		for (Object[] c : whole)
		{
			assertEquals(c[1], BoxText.whole(((Number) c[0]).doubleValue()));
		}
		Object[][] ages = {{0L, "the last minute"}, {89_999L, "the last minute"}, {90_000L, "2 minutes ago"},
			{360_000L, "6 minutes ago"}, {3_569_999L, "59 minutes ago"}, {3_570_000L, "1 hour ago"},
			{7_200_000L, "2 hours ago"}, {10_799_999L, "3 hours ago"}};
		for (Object[] c : ages)
		{
			assertEquals(c[1], BoxText.pricesAge((Long) c[0]));
		}
	}

	@Test
	public void stalePrices() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Our prices are 22 minutes old.",
			"We'll show them once they update.",
			"Last 10 tests: 8 made money",
			"1101101111"),
			lines(BoxText.forItem(snapshot, false, 2, false, SOURCE_AT + 22 * MINUTE)));
		assertEquals("Our prices are over an hour old.",
			lines(BoxText.forItem(snapshot, false, 2, false, SOURCE_AT + 60 * MINUTE)).get(1));
		assertEquals("just before staleAt", "Prices from 15 minutes ago",
			lines(BoxText.forItem(snapshot, false, 2, false, STALE_AT - 1)).get(9));

		ItemCheck.Response response = Fixtures.itemCheck();
		response.fresh = false;
		response.sourceAt = null;
		response.staleAt = null;
		ItemCheck.Snapshot notFresh = ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN);
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Our prices aren't up to date right now.",
			"We'll show them once they update.",
			"Last 10 tests: 8 made money",
			"1101101111"),
			lines(BoxText.forItem(notFresh, false, 2, false, SIX_MINUTES_IN)));
	}

	@Test
	public void aListWithoutStaleAtGoesStaleAfter15Minutes() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.staleAt = null;
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN);
		assertEquals("Prices from 15 minutes ago",
			lines(BoxText.forItem(snapshot, true, 2, false, SOURCE_AT + 15 * MINUTE - 1)).get(9));
		assertEquals("Can't reach Gielinor Gains.",
			lines(BoxText.forItem(snapshot, true, 2, false, SOURCE_AT + 15 * MINUTE)).get(1));
	}

	@Test
	public void failedDownloads() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		assertEquals("old numbers stay until they're stale", "Chance | 69%",
			lines(BoxText.forItem(snapshot, true, 2, false, SIX_MINUTES_IN)).get(6));
		assertEquals(Arrays.asList(
			"Gielinor Gains",
			"Can't reach Gielinor Gains.",
			"We'll try again soon.",
			"Last 10 tests: 8 made money",
			"1101101111"),
			lines(BoxText.forItem(snapshot, true, 2, false, STALE_AT)));
		assertEquals(Arrays.asList("Gielinor Gains", "Can't reach Gielinor Gains.", "We'll try again soon."),
			lines(BoxText.forItem(null, true, 2, false, 0)));
	}

	@Test
	public void loadingAndOutOfDate() throws Exception
	{
		assertEquals(Arrays.asList("Gielinor Gains", "Getting prices..."),
			lines(BoxText.forItem(null, false, 2, false, 0)));

		ItemCheck.Response response = Fixtures.itemCheck();
		response.minPlugin = ItemCheck.PLUGIN_VERSION + 1;
		assertEquals(Arrays.asList("Gielinor Gains", "This version is out of date.", "Restart RuneLite to update it."),
			lines(BoxText.forItem(ItemCheck.Snapshot.of(response, 0, 0), false, 2, false, 0)));
	}

	@Test
	public void everyStringIsAscii() throws Exception
	{
		Locale before = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try
		{
			ItemCheck.Response outdated = Fixtures.itemCheck();
			outdated.minPlugin = 2;
			ItemCheck.Response incomplete = Fixtures.itemCheck();
			incomplete.recordsComplete = false;
			List<ItemCheck.Snapshot> snapshots = Arrays.asList(Fixtures.snapshot(), Fixtures.edgeSnapshot(), null,
				ItemCheck.Snapshot.of(outdated, 0, 0), ItemCheck.Snapshot.of(incomplete, SIX_MINUTES_IN, SIX_MINUTES_IN));
			List<String> all = new ArrayList<>(BoxText.REASONS.values());
			for (ItemCheck.Snapshot snapshot : snapshots)
			{
				for (int id : new int[]{2, 561, 995, 4151, 11840, 20997, 13190, 12, 13, 14, 15, 565, 999_999})
				{
					for (long now : new long[]{SIX_MINUTES_IN, STALE_AT, SOURCE_AT + 3 * 60 * MINUTE})
					{
						for (boolean flag : new boolean[]{false, true})
						{
							BoxText.Box box = BoxText.forItem(snapshot, flag, id, flag, now);
							all.addAll(lines(box));
							all.addAll(box.getTooltip());
						}
					}
				}
			}
			for (String s : all)
			{
				assertTrue("not ASCII: " + s, s.chars().allMatch(c -> c >= 0x20 && c < 0x7f));
			}
			assertTrue(all.contains("Buy offer | 9,800,000"));
		}
		finally
		{
			Locale.setDefault(before);
		}
	}

	@Test
	public void showsTheKeysToTypeUnderATypeStep()
	{
		Guide.Step step = new Guide.Step("Type 11k", "Then press Enter.", Guide.Target.NONE, -1, 3);
		BoxText.Box box = BoxText.guide("Cannonball", Guide.Phase.BUY, step, "tip", false, "1");
		BoxText.Line keys = box.getLines().stream().filter(l -> l.getKind() == BoxText.Kind.KEYS).findFirst().orElse(null);
		assertEquals("11k", keys.getLeft());
		assertEquals("1", keys.getRight());
		// Steps that aren't typing have no keys.
		Guide.Step click = new Guide.Step("Click Confirm.", null, Guide.Target.CONFIRM, -1, 5);
		assertFalse(BoxText.guide("Cannonball", Guide.Phase.BUY, click, "tip", false, "1").getLines().stream()
			.anyMatch(l -> l.getKind() == BoxText.Kind.KEYS));
	}
}
