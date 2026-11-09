package com.gielinorgains;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import net.runelite.http.api.RuneLiteAPI;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The plugin's rules against the cases the site made from its own Trader code (trader-rules-v1.json). */
public class TraderRulesTest
{
	private static JsonObject cases() throws Exception
	{
		return RuneLiteAPI.GSON.fromJson(Fixtures.text("trader-rules-v1.json"), JsonObject.class);
	}

	/** The plan feed's share of the plan one pick may use, as the website's fixture was made with. */
	private static Double pickShare() throws Exception
	{
		return cases().get("pickShare").getAsDouble();
	}

	@Test
	public void capsEachPickAtAFifthOfThePlan()
	{
		assertEquals(200_000, TraderRules.pickBudget(900_000, 1_000_000, 0.2));
		assertEquals(150_000, TraderRules.pickBudget(150_000, 1_000_000, 0.2));
		// A site without the cap sends no share.
		assertEquals(900_000, TraderRules.pickBudget(900_000, 1_000_000, null));
	}

	private static List<PlanFeed.Pick> picks(JsonElement json)
	{
		return RuneLiteAPI.GSON.fromJson(json, new TypeToken<List<PlanFeed.Pick>>()
		{
		}.getType());
	}

	@Test
	public void choosesWhatTraderChooses() throws Exception
	{
		JsonArray selection = cases().getAsJsonArray("selection");
		int chosen = 0;
		for (JsonElement element : selection)
		{
			JsonObject c = element.getAsJsonObject();
			Map<Integer, Integer> bought = new HashMap<>();
			for (JsonElement buy : c.getAsJsonArray("recentBuys"))
			{
				bought.put(buy.getAsJsonArray().get(0).getAsInt(), buy.getAsJsonArray().get(1).getAsInt());
			}
			long planningGp = c.get("planningGp").getAsLong();
			TraderRules.Fill fill = TraderRules.choose(picks(c.get("picks")),
				TraderRules.pickBudget(c.get("budget").getAsLong(), planningGp, pickShare()), planningGp, new HashSet<>(),
				p -> p.limit - bought.getOrDefault(p.id, 0), true, TraderRules.Room.NONE);
			String expected = c.get("expected").isJsonNull() ? "none"
				: c.getAsJsonObject("expected").get("id").getAsInt() + "x" + c.getAsJsonObject("expected").get("qty").getAsLong();
			assertEquals(c.get("name").getAsString(), expected, fill == null ? "none" : fill.getId() + "x" + fill.getQty());
			chosen += fill == null ? 0 : 1;
		}
		assertTrue(chosen > 20);
	}

	@Test
	public void plansWithTheSharedLimitsRoomAsTraderDoes() throws Exception
	{
		long now = cases().get("now").getAsLong();
		int chosen = 0;
		int none = 0;
		for (JsonElement element : cases().getAsJsonArray("room"))
		{
			JsonObject c = element.getAsJsonObject();
			List<PlanFeed.Pick> picks = picks(c.get("picks"));
			PlanFeed.Room room = RuneLiteAPI.GSON.fromJson(c.get("room"), PlanFeed.Room.class);
			Map<Integer, Long> own = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : c.getAsJsonObject("own").entrySet())
			{
				own.put(Integer.parseInt(e.getKey()), e.getValue().getAsLong());
			}
			long planningGp = c.get("planningGp").getAsLong();
			TraderRules.Fill fill = TraderRules.choose(picks, TraderRules.pickBudget(c.get("budget").getAsLong(), planningGp,
				pickShare()), planningGp, new HashSet<>(), p -> p.limit, true, TraderRules.room(picks, room, own, now));
			String expected = c.get("expected").isJsonNull() ? "none"
				: c.getAsJsonObject("expected").get("id").getAsInt() + "x" + c.getAsJsonObject("expected").get("qty").getAsLong();
			assertEquals(c.get("name").getAsString(), expected, fill == null ? "none" : fill.getId() + "x" + fill.getQty());
			chosen += fill == null ? 0 : 1;
			none += fill == null ? 1 : 0;
		}
		assertTrue(chosen > 10 && none > 5);
	}

	@Test
	public void leavesEveryPickAsItIsOnceTheRoomsWindowHasEnded()
	{
		PlanFeed.Pick pick = TraderTest.pick(2, "Steel cannonball", 259, 280, 1000);
		PlanFeed.Room room = new PlanFeed.Room();
		room.players = 2;
		room.ends = 10_000;
		room.reserved = new HashMap<>();
		room.reserved.put("2", 5000L);
		List<PlanFeed.Pick> picks = java.util.Collections.singletonList(pick);
		assertTrue(TraderRules.room(picks, room, new HashMap<>(), 9_999).getFull().contains(2));
		assertEquals(TraderRules.Room.NONE, TraderRules.room(picks, room, new HashMap<>(), 10_000));
		assertEquals(TraderRules.Room.NONE, TraderRules.room(picks, null, new HashMap<>(), 0));
		// The player's own plan never counts against them.
		Map<Integer, Long> own = new HashMap<>();
		own.put(2, 3500L);
		TraderRules.Room left = TraderRules.room(picks, room, own, 0);
		assertTrue(left.getFull().isEmpty());
		assertEquals(500L, left.left(pick));
	}

	@Test
	public void saysHowLongAnOfferWaitedInTheWebsitesWords()
	{
		assertEquals("under a minute", TraderRules.waitedFor(59_999));
		assertEquals("1 minute", TraderRules.waitedFor(60_000));
		assertEquals("59 minutes", TraderRules.waitedFor(3_599_999));
		assertEquals("1 hour", TraderRules.waitedFor(3_600_000));
		assertEquals("2 hours", TraderRules.waitedFor(2 * 3_600_000 + 59 * 60_000));
	}

	@Test
	public void sellsPartOfABuyAtTheWebsitesSize()
	{
		assertFalse(TraderRules.sellPartOfBuy(100, 100, 1000, 75));
		assertTrue(TraderRules.sellPartOfBuy(400, 400, 1000, 75));
		assertTrue(TraderRules.sellPartOfBuy(950, 950, 1000, 1));
		assertFalse(TraderRules.sellPartOfBuy(200, 950, 1000, 1));
		assertFalse(TraderRules.sellPartOfBuy(0, 1000, 1000, 1000));
	}

	@Test
	public void keepsSalesEightHoursAsTraderDoes() throws Exception
	{
		long now = cases().get("now").getAsLong();
		for (JsonElement element : cases().getAsJsonArray("sale"))
		{
			JsonObject c = element.getAsJsonObject();
			JsonObject offer = c.getAsJsonObject("offer");
			TraderRules.Reprice reprice = TraderRules.repriceSale(offer.get("price").getAsLong(), offer.get("filled").getAsLong(),
				offer.get("total").getAsLong(), offer.get("listedAt").getAsLong(), c.get("cost").getAsLong(),
				c.get("target").getAsLong(), c.get("stalledMs").getAsLong(), now, false);
			JsonElement expected = c.get("expected");
			assertEquals(c.toString(), expected.isJsonNull() ? null : expected.getAsJsonObject().get("price").getAsLong()
					+ "/" + expected.getAsJsonObject().get("profitEach").getAsLong(),
				reprice == null ? null : reprice.getPrice() + "/" + reprice.getProfitEach());
		}
	}

	@Test
	public void stopsABuyWhenTraderDoes() throws Exception
	{
		int stops = 0;
		for (JsonElement element : cases().getAsJsonArray("stop"))
		{
			JsonObject c = element.getAsJsonObject();
			boolean expected = c.get("expected").getAsBoolean();
			assertEquals(c.toString(), expected, TraderRules.stopBuy(c.get("buy").getAsLong(), c.get("sellNow").getAsLong(),
				c.get("taxFree").getAsBoolean()));
			stops += expected ? 1 : 0;
		}
		assertTrue(stops > 20);
		assertFalse(TraderRules.stopBuy(100, 0, false));
	}

	@Test
	public void chargesTheWebsitesTax() throws Exception
	{
		for (JsonElement element : cases().getAsJsonArray("tax"))
		{
			JsonObject c = element.getAsJsonObject();
			long price = c.get("price").getAsLong();
			assertEquals(c.get("tax").getAsLong(), TraderRules.tax(price, false));
			assertEquals(c.get("breakEvenFromCost").getAsLong(), TraderRules.breakEven(price, false));
		}
	}
}
