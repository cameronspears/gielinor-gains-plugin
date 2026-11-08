package com.gielinorgains;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TraderTest
{
	static final Gson GSON = new Gson();
	static final long T0 = 1_800_000_000_000L;

	static PlanFeed.Pick pick(int id, String name, long buy, long sell, long qty)
	{
		PlanFeed.Pick p = new PlanFeed.Pick();
		p.id = id;
		p.name = name;
		p.members = true;
		p.limit = 10_000;
		p.buy = buy;
		p.sell = sell;
		p.qty = qty;
		p.qtyLarge = qty;
		p.qtyLimit = qty;
		p.minQty = 1;
		p.maxQty = qty;
		p.tier = "p";
		p.reserveEach = sell - sell / 50 - buy - 1;
		p.thin = qty;
		p.high = sell;
		p.low = buy;
		p.lowAt = T0 / 1000;
		p.highAt = T0 / 1000;
		return p;
	}

	static PlanFeed.Feed feed(PlanFeed.Pick... picks)
	{
		PlanFeed.Feed feed = new PlanFeed.Feed();
		feed.v = 1;
		feed.staleAt = T0 + 3_600_000L;
		feed.picks = new ArrayList<>(Arrays.asList(picks));
		return feed;
	}

	/** A trader signed in to one game account, with its first sync answered and a budget set. */
	static Trader ready(long budget)
	{
		Trader trader = new Trader(GSON, T0);
		trader.setAccount("y3QWg8Nf", true);
		trader.request(T0);
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson("{\"revision\":3,\"slots\":[{\"slot\":1,\"item\":null},{\"slot\":2,\"item\":null}],"
			+ "\"budget\":null,\"rejected\":[]}", Trader.Plan.class);
		trader.onReply(200, reply, T0);
		trader.setBudget(budget);
		return trader;
	}

	static Trader.Step next(Trader trader, PlanFeed.Feed feed, long now)
	{
		return trader.next(feed, now, id -> 0);
	}

	@Test
	public void sendsTheGameAccountIdAsTheWebsiteKnowsIt()
	{
		assertEquals("y3QWg8Nf", Trader.profileId("rsprofile.y3QWg8Nf"));
		assertEquals("y3QWg8Nf", Trader.profileId("y3QWg8Nf"));
		assertNull(Trader.profileId(null));
		assertNull(Trader.profileId("rsprofile."));
	}

	@Test
	public void asksForABudgetFirst()
	{
		Trader trader = new Trader(GSON, T0);
		assertEquals(Trader.Kind.BUDGET, next(trader, feed(), T0).getKind());
	}

	/** Signed in with a plan but no typed budget. */
	static Trader untyped()
	{
		Trader trader = ready(1);
		trader.setBudget(0);
		return trader;
	}

	@Test
	public void plansWithTheGpItSeesUpToTheBestPlanSize()
	{
		Trader trader = untyped();
		assertEquals(Trader.Kind.BUDGET, next(trader, feed(), T0).getKind());
		assertNull(trader.budget());

		trader.setGpSeen(3_000_000, null);
		assertEquals(Long.valueOf(3_000_000), trader.budget());
		assertNotEquals(Trader.Kind.BUDGET, next(trader, feed(), T0).getKind());

		// GP in a buy offer is still the player's: placing a buy doesn't shrink the plan.
		trader.setGpSeen(2_741_000, null);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		assertEquals(Long.valueOf(3_000_000), trader.budget());

		trader.setGpSeen(400_000_000, null);
		assertEquals(Long.valueOf(Trader.BEST_PLAN_GP), trader.budget());

		// A typed budget is never capped or changed.
		trader.setBudget(50_000_000);
		assertEquals(Long.valueOf(50_000_000), trader.budget());
	}

	@Test
	public void remembersBankCoinsSoItCanPlanBeforeTheBankOpens()
	{
		Trader trader = untyped();
		trader.setGpSeen(5_000, 8_000_000L);
		String saved = trader.save();

		Trader restarted = untyped();
		restarted.load(saved, T0);
		restarted.setGpSeen(5_000, null);
		assertEquals(Long.valueOf(8_005_000), restarted.budget());
	}

	@Test
	public void theirOwnLootForSaleIsNotGpYet()
	{
		Trader trader = untyped();
		trader.setGpSeen(2_000_000, null);
		trader.onOffer(0, 4151, "SELLING", 0, 3, 2_000_000, 0, T0 + 5000, true);
		assertEquals(Long.valueOf(2_000_000), trader.budget());
	}

	@Test
	public void sendsOnlyThePlanSizeAndOnlyWhenNoBudgetIsTyped()
	{
		Trader trader = untyped();
		trader.setGpSeen(400_000_000, null);
		JsonObject body = GSON.fromJson(trader.request(T0 + 60_000), JsonObject.class);
		assertEquals(Trader.BEST_PLAN_GP, body.getAsJsonObject("offers").get("planGp").getAsLong());
		assertFalse(body.toString().contains("400000000"));

		Trader typed = ready(5_000_000);
		typed.setGpSeen(400_000_000, null);
		JsonObject typedBody = GSON.fromJson(typed.request(T0 + 60_000), JsonObject.class);
		assertFalse(typedBody.getAsJsonObject("offers").has("planGp"));
	}

	@Test
	public void fillsFromTheGpItSeesWithNoBudgetTyped()
	{
		Trader trader = untyped();
		trader.setGpSeen(300_000, null);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		assertEquals(2, trader.refill(feed, T0 + 1000));
		long planned = 0;
		for (Trader.PlanSlot slot : trader.plan().slots)
		{
			planned += slot.item == null ? 0 : slot.item.qty * slot.item.buy;
		}
		assertTrue(planned > 250_000 && planned <= 300_000);
	}

	@Test
	public void fillsSlotsInFeedOrderAndGuidesAFlipToTheEnd()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		assertEquals(2, trader.refill(feed, T0 + 1000));
		Trader.Step buy = next(trader, feed, T0 + 1000);
		assertEquals(Trader.Kind.BUY, buy.getKind());
		assertEquals(2, buy.getItemId());
		assertEquals(1000, buy.getQty());
		assertEquals(259, buy.getPrice());

		// The player places the buy, it fills, they collect and list the sale.
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 60_000, true);
		assertEquals(Trader.Kind.COLLECT, next(trader, feed, T0 + 61_000).getKind());
		assertEquals(1, trader.toCollect());
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 62_000, true);
		Trader.Step sell = next(trader, feed, T0 + 62_000);
		assertEquals(Trader.Kind.SELL, sell.getKind());
		assertEquals(280, sell.getPrice());
		assertTrue(trader.held().contains(2));

		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SOLD", 1000, 1000, 280, 280_000, T0 + 600_000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 601_000, true);
		// The finished item leaves the plan, so its slot is free for the next pick.
		assertFalse(trader.planned(2));
	}

	static PlanFeed.Room room(long ends, Object... reserved)
	{
		PlanFeed.Room room = new PlanFeed.Room();
		room.window = ends / 7_200_000L - 1;
		room.ends = ends;
		room.players = 2;
		room.reserved = new java.util.HashMap<>();
		for (int i = 0; i < reserved.length; i += 2)
		{
			room.reserved.put((String) reserved[i], (Long) reserved[i + 1]);
		}
		return room;
	}

	@Test
	public void plansWithTheRoomOtherTraderPlayersLeft()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000),
			pick(562, "Chaos rune", 83, 89, 1000));
		// Cannonballs are full (2 players' worth), and coal has 500 left of 4,000.
		feed.room = room(T0 + 3_600_000L, "2", 2000L, "453", 3500L);
		assertEquals(2, trader.refill(feed, T0 + 1000));
		assertFalse(trader.planned(2));
		Trader.Step buy = next(trader, feed, T0 + 1000);
		assertEquals(453, buy.getItemId());
		assertEquals(500, buy.getQty());
		assertTrue(trader.planned(562));
	}

	@Test
	public void saysWhenRoomOpensWhenNoPickHasAny()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		feed.staleAt = T0 + 7_200_000L;
		feed.room = room(T0 + 3_600_000L, "2", 2500L);
		assertEquals(0, trader.refill(feed, T0 + 1000));
		Trader.Step empty = next(trader, feed, T0 + 1000);
		assertEquals(Trader.Kind.EMPTY, empty.getKind());
		assertEquals(TraderRules.roomNotice(1, T0 + 3_600_000L, java.time.ZoneId.systemDefault()), empty.getDetail());
		assertTrue(Trader.outOfPicks(empty));
		assertTrue(trader.upNext(feed, T0 + 1000, 2).isEmpty());

		// Once the window ends, its room no longer applies and the pick comes back.
		assertEquals(1, trader.refill(feed, T0 + 3_600_000L));
		assertTrue(trader.planned(2));
	}

	@Test
	public void registersANewLinkAsShownOnlyThenSendsItsStages()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0 + 1000);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		assertTrue(trader.due(T0 + 5000));

		JsonObject body = GSON.fromJson(trader.request(T0 + 6000), JsonObject.class);
		JsonObject shown = body.getAsJsonObject("progress").getAsJsonArray("observations").get(0).getAsJsonObject();
		assertEquals("shown", shown.get("stage").getAsString());
		assertFalse(shown.has("placedAt"));
		assertEquals(Trader.GUIDANCE_VERSION, shown.get("guidanceVersion").getAsString());
		// The plan goes with the revision the website gave, and empty slots say so.
		JsonObject plan = body.getAsJsonObject("plan");
		assertEquals(3, plan.get("revision").getAsInt());
		assertTrue(plan.getAsJsonArray("slots").get(1).getAsJsonObject().get("item").isJsonNull());

		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson(plan.toString(), Trader.Plan.class);
		reply.plan.revision = 4;
		trader.onReply(200, reply, T0 + 7000);

		// Once saved, the next sync carries its placed stage.
		assertTrue(trader.due(T0 + 7000 + Trader.SYNC_GAP_MS));
		JsonObject again = GSON.fromJson(trader.request(T0 + 7000 + Trader.SYNC_GAP_MS), JsonObject.class);
		JsonObject placed = again.getAsJsonObject("progress").getAsJsonArray("observations").get(0).getAsJsonObject();
		assertEquals("placed", placed.get("stage").getAsString());
		assertEquals(T0 + 5000, placed.get("placedAt").getAsLong());
	}

	@Test
	public void neverSendsALinkTheWebsiteWouldRefuse()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 259, 280, 1000)), T0 + 1000);
		trader.request(T0 + 2000);
		trader.onReply(503, null, T0 + 2000);
		// Past the minute the website allows: the link is dropped rather than failing the whole save.
		JsonObject body = GSON.fromJson(trader.request(T0 + 120_000), JsonObject.class);
		assertEquals(0, body.getAsJsonObject("progress").getAsJsonArray("observations").size());
	}

	@Test
	public void sendsLinksAgainWhenProgressCouldNotBeSaved()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0 + 1000);
		JsonObject body = GSON.fromJson(trader.request(T0 + 2000), JsonObject.class);
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson(body.getAsJsonObject("plan").toString(), Trader.Plan.class);
		reply.plan.revision = 4;
		reply.progress = "conflict";
		trader.onReply(200, reply, T0 + 3000);

		// The website saved the plan but not Progress: the link goes again soon, inside its minute.
		assertFalse(trader.due(T0 + 4000));
		assertTrue(trader.due(T0 + 18_000));
		JsonObject again = GSON.fromJson(trader.request(T0 + 18_000), JsonObject.class);
		assertEquals(1, again.getAsJsonObject("progress").getAsJsonArray("observations").size());
		reply.progress = "saved";
		trader.onReply(200, reply, T0 + 19_000);
		JsonObject saved = GSON.fromJson(trader.request(T0 + 19_000 + Trader.SYNC_GAP_MS), JsonObject.class);
		assertFalse(saved.has("progress"));
	}

	@Test
	public void recordsASaleThatFinishedDuringAWorldHop()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 259, 280, 1000)), T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 4000, true);
		// While hopping the client empties every slot; those events are dropped (clearedByClient).
		assertTrue(GielinorGainsPlugin.clearedByClient(net.runelite.api.GrandExchangeOfferState.EMPTY,
			net.runelite.api.GameState.HOPPING));
		assertTrue(GielinorGainsPlugin.clearedByClient(net.runelite.api.GrandExchangeOfferState.EMPTY,
			net.runelite.api.GameState.LOGGING_IN));
		assertFalse(GielinorGainsPlugin.clearedByClient(net.runelite.api.GrandExchangeOfferState.EMPTY,
			net.runelite.api.GameState.LOGGED_IN));
		assertFalse(GielinorGainsPlugin.clearedByClient(net.runelite.api.GrandExchangeOfferState.SOLD,
			net.runelite.api.GameState.HOPPING));
		// The new world's first offers aren't live, but the sale that finished is still recorded.
		trader.onOffer(0, 2, "SOLD", 1000, 1000, 280, 280_000, T0 + 60_000, false);
		JsonObject body = GSON.fromJson(trader.request(T0 + 61_000), JsonObject.class);
		JsonArray trades = body.getAsJsonObject("offers").getAsJsonArray("trades");
		assertEquals(2, trades.size());
		assertFalse(trades.get(1).getAsJsonObject().get("buy").getAsBoolean());
	}

	@Test
	public void judgesPlanPricesOnTheServersClock()
	{
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		feed.staleAt = T0 + 10 * 60_000L;
		feed.serverDate = T0;
		// This computer's clock runs 30 minutes fast: the prices are still good for 10 minutes.
		feed.receivedAt = T0 + 30 * 60_000L;
		assertTrue(feed.fresh(feed.receivedAt + 60_000));
		assertFalse(feed.fresh(feed.receivedAt + 11 * 60_000L));
	}

	@Test
	public void aCancelledBuyWithNothingBoughtFollowsTheLatestPrices()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "CANCELLED_BUY", 0, 1000, 259, 0, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		feed.picks.get(0).buy = 300;
		feed.picks.get(0).sell = 320;
		trader.refill(feed, T0 + 4000);
		Trader.Step buy = next(trader, feed, T0 + 4000);
		assertEquals(Trader.Kind.BUY, buy.getKind());
		assertEquals(300, buy.getPrice());
		// No more GP than the pick had: 1,000 at 259 is 863 at 300.
		assertEquals(863, buy.getQty());
	}

	@Test
	public void keepsASaleEightHoursThenOffersALowerPrice()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 290, 0, T0 + 4000, true);
		feed.picks.get(0).sell = 275;
		long early = T0 + 7 * 3_600_000L;
		assertEquals(Trader.Kind.WAIT, next(trader, feed, early).getKind());

		long late = T0 + 8 * 3_600_000L + 10_000;
		feed.staleAt = late + 60_000;
		Trader.Step lower = next(trader, feed, late);
		assertEquals(Trader.Kind.LOWER, lower.getKind());
		assertEquals(275, lower.getPrice());
	}

	@Test
	public void skipForNowFreesTheSlotAndNeverPicksItAgain()
	{
		Trader trader = ready(300_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		trader.refill(feed, T0);
		trader.skip(2);
		assertFalse(trader.planned(2));
		trader.refill(feed, T0 + 1000);
		assertFalse(trader.planned(2));
		assertTrue(trader.planned(453));
		assertTrue(trader.plan().rejected.contains(2));
	}

	@Test
	public void loginOffersAreNotNewTrades()
	{
		Trader trader = ready(1_000_000);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0, false);
		JsonObject body = GSON.fromJson(trader.request(T0 + 1000), JsonObject.class);
		assertEquals(0, body.getAsJsonObject("offers").getAsJsonArray("trades").size());
		assertNull(trader.today());
	}

	@Test
	public void picksAddedWhileASyncIsOutAreKept()
	{
		Trader trader = ready(1_000_000);
		// The budget's save goes out with an empty plan...
		trader.request(T0 + 500);
		// ...and the picks land before it comes back.
		trader.refill(feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000)), T0 + 600);
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson("{\"revision\":4,\"slots\":[{\"slot\":1,\"item\":null}],\"budget\":1000000,"
			+ "\"rejected\":[]}", Trader.Plan.class);
		trader.onReply(200, reply, T0 + 700);
		assertTrue(trader.planned(2));
		assertTrue(trader.planned(453));
		// They go with the next save, on the revision the website just gave.
		JsonObject body = GSON.fromJson(trader.request(T0 + 700 + Trader.SYNC_GAP_MS), JsonObject.class);
		assertEquals(4, body.getAsJsonObject("plan").get("revision").getAsInt());
	}

	@Test
	public void aShortPlanFromTheWebsiteStillHasEightSlots()
	{
		Trader trader = ready(1_000_000);
		assertEquals(8, trader.plan().slots.size());
		assertEquals(8, trader.plan().slots.get(7).slot);
	}

	@Test
	public void alreadyDoneMovesToTheNextStep()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		trader.refill(feed, T0);
		Trader.Step first = next(trader, feed, T0);
		assertEquals("Buy Steel cannonball", first.getTitle());
		assertEquals("1,000 at 259 each · GE slot 1", first.getDetail());
		assertEquals("Watching for your buy in slot 1", first.getStatus());
		trader.dismiss(first.getKey());
		assertEquals(453, next(trader, feed, T0).getItemId());
	}

	@Test
	public void keepMyCurrentPriceHidesThePriceCheck()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 290, 0, T0 + 4000, true);
		feed.picks.get(0).sell = 275;
		long late = T0 + 8 * 3_600_000L + 10_000;
		feed.staleAt = late + 60_000;
		Trader.Step lower = next(trader, feed, late);
		assertEquals(Trader.Kind.LOWER, lower.getKind());
		assertEquals("Listed 8 hours at 290 with no sales. At 275 you make 15 GP less on each, but still more than you paid.",
			lower.getDetail());
		assertEquals("Lower to 275?", trader.badge(0, late, lower).getText());
		trader.dismiss(lower.getKey());
		assertEquals(Trader.Kind.WAIT, next(trader, feed, late).getKind());
	}

	@Test
	public void slotsListOffersInGeOrderThenWhatsNext()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		trader.refill(feed, T0);
		trader.onOffer(0, 453, "BUYING", 800, 2000, 150, 120_000, T0 + 1000, true);
		java.util.List<Trader.SlotRow> rows = trader.slots(T0 + 1000);
		assertEquals("Coal", rows.get(0).getName());
		assertEquals("Buying · 40%", rows.get(0).getStatus());
		assertEquals(Trader.Tone.BLUE, rows.get(0).getTone());
		assertEquals("Next: buy at 259", rows.get(1).getStatus());
		Trader.Step buy = next(trader, feed, T0 + 1000);
		assertEquals("Next: Steel cannonball", trader.badge(1, T0 + 1000, buy).getText());
	}

	@Test
	public void aSaleThatPartlySoldSaysHowMuch()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 4000, true);
		assertEquals("Sell at 280 · 8h", trader.badge(0, T0 + 4000, null).getText());
		trader.onOffer(0, 2, "SELLING", 250, 1000, 280, 70_000, T0 + 5000, true);
		assertEquals("Sold 25% · 7h left", trader.slots(T0 + 5000).get(0).getStatus());
		assertEquals("Sold 25% · 7h", trader.badge(0, T0 + 5000, null).getText());
	}

	private static String plan(Trader.SlotRow row)
	{
		StringBuilder s = new StringBuilder();
		for (Trader.Fact f : row.getPlan())
		{
			s.append(f.getLabel()).append(": ").append(f.getValue()).append(" | ");
		}
		return s.append(row.getNote()).toString();
	}

	@Test
	public void eachSlotShowsItsPlanFromBuyToSale()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		feed.picks.get(0).chance = 0.72;
		trader.refill(feed, T0);
		assertEquals("Buy · each: 259 GP | Quantity: 1,000 | Sell · each: 280 GP | You keep · after tax: 275 GP"
			+ " | Profit if it all sells: +16K GP | Chance: 72% | GP needed: 259K"
			+ " | Buy 1,000 at 259. Then sell at 280. A sell keeps its price for 8 hours.",
			plan(trader.slots(T0, feed).get(0)));

		trader.onOffer(0, 2, "BUYING", 390, 1000, 259, 101_010, T0 + 1000, true);
		assertEquals("Buy · each: 259 GP | Quantity: 1,000 | Bought so far: 390 of 1,000 | Sell · each: 280 GP"
			+ " | You keep · after tax: 275 GP | Profit if it all sells: +16K GP | Chance: 72% | GP needed: 259K"
			+ " | When it fills, collect it. Then sell at 280.", plan(trader.slots(T0 + 1000, feed).get(0)));

		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		trader.onOffer(0, 2, "SELLING", 670, 1000, 280, 187_600, T0 + 4000, true);
		long later = T0 + 4000 + 6 * 3_600_000L;
		assertEquals("Buy · each: 259 GP | Quantity: 1,000 | Sell · each: 280 GP"
			+ " | You keep · after tax: 275 GP | Sold so far: 670 of 1,000 | Profit if it all sells: +16K GP | Chance: 72%"
			+ " | GP needed: 259K | It keeps this price for 2h more. If it stalls after that, Trader lowers it,"
			+ " but not below break-even (264 GP).", plan(trader.slots(later, feed).get(0)));
	}

	@Test
	public void aBoughtSlotSaysToSellAllAndWhereTheFloorIs()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 2000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 3000, true);
		assertEquals("Sell all 1,000 at 280. It keeps that price for 8 hours. If it stalls after that, Trader lowers it,"
			+ " but not below break-even (264 GP).", trader.slots(T0 + 4000, feed).get(0).getNote());
	}

	@Test
	public void aReboundSlotGivesTheReboundPlan()
	{
		Trader trader = ready(1_000_000);
		assertTrue(trader.add(2, "Steel cannonball", 1000, 259, 280, true, T0));
		assertEquals("Rebound: leave the buy up for a day, then sell at 280 within 2 days.",
			trader.slots(T0).get(0).getNote());
	}

	@Test
	public void chanceShowsOnlyWhileThePlanHasThePicksPrices()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		feed.picks.get(0).chance = 0.72;
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		feed.picks.get(0).buy = 262;
		assertFalse(plan(trader.slots(T0 + 2000, feed).get(0)).contains("Chance"));
	}

	@Test
	public void aBoughtItemSellsAtItsPlanPriceEvenOnceItLeavesTheFeed()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(562, "Chaos rune", 83, 89, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 562, "BUYING", 0, 1000, 83, 0, T0 + 1000, true);
		trader.onOffer(0, 562, "BOUGHT", 1000, 1000, 83, 83_000, T0 + 5000, true);
		trader.onOffer(0, 562, "EMPTY", 0, 0, 0, 0, T0 + 6000, true);
		// Chaos rune is no longer a pick, and the price check knows nothing.
		Trader.Step sell = trader.next(feed(), T0 + 7000, id -> 0);
		assertEquals(Trader.Kind.SELL, sell.getKind());
		assertEquals(89, sell.getPrice());
	}

	@Test
	public void aFilledBuyAndAFinishedFlipAreMoments()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(562, "Chaos rune", 83, 89, 1000)), T0);
		trader.onOffer(0, 562, "BUYING", 0, 1000, 83, 0, T0 + 1000, true);
		trader.onOffer(0, 562, "BOUGHT", 1000, 1000, 83, 83_000, T0 + 5000, true);
		Trader.Moment bought = trader.takeMoments().get(0);
		assertFalse(bought.isSale());
		assertEquals(4000, bought.getTookMs());
		trader.onOffer(0, 562, "EMPTY", 0, 0, 0, 0, T0 + 6000, true);
		trader.onOffer(0, 562, "SELLING", 0, 1000, 89, 0, T0 + 7000, true);
		trader.onOffer(0, 562, "SOLD", 1000, 1000, 89, 89_000, T0 + 60_000, true);
		Trader.Moment sold = trader.takeMoments().get(0);
		assertTrue(sold.isSale());
		// 89,000 less 1 GP tax on each, less the 83,000 paid.
		assertEquals(5_000, sold.getProfit());
		assertTrue(trader.takeMoments().isEmpty());
	}

	@Test
	public void aWebsiteChangeDuringASyncWinsOverLocalPicks()
	{
		Trader trader = ready(1_000_000);
		// The budget's save goes back first, so the next request carries no plan.
		trader.request(T0 + 100);
		Trader.Reply saved = new Trader.Reply();
		saved.plan = GSON.fromJson("{\"revision\":4,\"slots\":[],\"budget\":1000000,\"rejected\":[]}", Trader.Plan.class);
		trader.onReply(200, saved, T0 + 200);
		trader.request(T0 + 300);
		trader.refill(feed(pick(2, "Steel cannonball", 259, 280, 1000)), T0 + 400);
		// Meanwhile the website saved a plan with Coal in it.
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson("{\"revision\":5,\"slots\":[{\"slot\":1,\"item\":{\"id\":453,\"name\":\"Coal\","
			+ "\"qty\":2000,\"buy\":150,\"sell\":165,\"origin\":\"manual\"}}],\"budget\":1000000,\"rejected\":[]}",
			Trader.Plan.class);
		trader.onReply(200, reply, T0 + 500);
		assertTrue(trader.planned(453));
		assertFalse(trader.planned(2));
	}

	@Test
	public void aPickNotBoughtYetFollowsTheLatestPrices()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 250, 280, 1000)), T0 + 100);
		PlanFeed.Feed newer = feed(pick(2, "Steel cannonball", 245, 270, 900));
		newer.computedAt = T0 + 60_000;
		// Arrived at T0 + 60s on a computer clock an hour fast; traded 7 minutes before the server answered.
		newer.receivedAt = T0 + 60_000;
		newer.serverDate = newer.receivedAt - 3_600_000;
		newer.sourceAt = newer.serverDate - 7 * 60_000;
		trader.refill(newer, T0 + 120_000);
		Trader.Step buy = next(trader, newer, T0 + 120_000);
		assertEquals(245, buy.getPrice());
		assertEquals(900, buy.getQty());
		assertTrue(buy.getDetail(), buy.getDetail().contains("Prices from 8 minutes ago."));
	}

	@Test
	public void anAutomaticPickThatLeftTheListIsSwapped()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 250, 280, 1000)), T0 + 100);
		trader.refill(feed(pick(453, "Coal", 150, 165, 2000)), T0 + 200);
		assertFalse(trader.planned(2));
		assertTrue(trader.planned(453));
	}

	@Test
	public void aPickTheWebsiteDroppedIsNotPickedAgainUntilTheFeedUpdates()
	{
		Trader trader = ready(1_000_000);
		trader.request(T0 + 50);
		Trader.Reply budget = new Trader.Reply();
		budget.plan = GSON.fromJson("{\"revision\":4,\"slots\":[],\"budget\":1000000,\"rejected\":[]}", Trader.Plan.class);
		trader.onReply(200, budget, T0 + 60);
		PlanFeed.Feed cached = feed(pick(2, "Steel cannonball", 250, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		cached.computedAt = T0;
		trader.refill(cached, T0 + 100);
		assertTrue(trader.planned(2));
		trader.request(T0 + 200);
		// The website saved the plan with Steel cannonball's slot empty: it had left the live list.
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson("{\"revision\":5,\"slots\":[{\"slot\":1,\"item\":null},{\"slot\":2,\"item\":{\"id\":453,"
			+ "\"name\":\"Coal\",\"qty\":2000,\"buy\":150,\"sell\":165,\"origin\":\"auto_fill\"}}],\"budget\":1000000,"
			+ "\"rejected\":[]}", Trader.Plan.class);
		trader.onReply(200, reply, T0 + 300);
		trader.refill(cached, T0 + 400);
		assertFalse(trader.planned(2));
		PlanFeed.Feed newer = feed(pick(2, "Steel cannonball", 250, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		newer.computedAt = T0 + 150_000;
		trader.refill(newer, T0 + 160_000);
		assertTrue(trader.planned(2));
	}

	@Test
	public void aRefusedPlanIsNotSentAgain()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 250, 280, 1000)), T0 + 100);
		assertTrue(trader.request(T0 + 200).contains("\"plan\""));
		trader.onReply(400, null, T0 + 300);
		assertFalse(trader.request(T0 + 400).contains("\"plan\""));
	}

	@Test
	public void givesTheStepForOnePlanItem()
	{
		Trader trader = ready(10_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 250, 280, 1000), pick(453, "Coal", 150, 165, 2000));
		trader.refill(feed, T0 + 100);
		Trader.Step coal = trader.stepFor(feed, T0 + 200, id -> 0, 453);
		assertEquals(453, coal.getItemId());
		assertEquals(Trader.Kind.BUY, coal.getKind());
		assertEquals(null, trader.stepFor(feed, T0 + 200, id -> 0, 999));
	}

	@Test
	public void aFlipBoughtBeforeARestartStillShowsItsProfit()
	{
		Trader before = ready(10_000_000);
		before.refill(feed(pick(564, "Cosmic rune", 105, 111, 18_000)), T0);
		before.onOffer(0, 564, "BUYING", 0, 18_000, 105, 0, T0 + 1000, true);
		before.onOffer(0, 564, "BOUGHT", 18_000, 18_000, 105, 1_890_000, T0 + 5000, true);
		before.onOffer(0, 564, "EMPTY", 0, 0, 0, 0, T0 + 6000, true);
		com.google.gson.JsonObject sent = GSON.fromJson(before.request(T0 + 7000), com.google.gson.JsonObject.class);

		// RuneLite restarts. The first sync gives back the saved trades and the plan.
		long later = T0 + 600_000;
		Trader after = new Trader(GSON, later);
		after.setAccount("y3QWg8Nf", true);
		after.request(later);
		com.google.gson.JsonObject reply = new com.google.gson.JsonObject();
		reply.add("trades", sent.getAsJsonObject("offers").get("trades"));
		reply.add("plan", GSON.fromJson("{\"revision\":4,\"slots\":[{\"slot\":1,\"item\":{\"id\":564,\"name\":\"Cosmic rune\","
			+ "\"qty\":18000,\"buy\":105,\"sell\":111,\"origin\":\"auto_fill\"}}],\"budget\":10000000,\"rejected\":[]}",
			com.google.gson.JsonObject.class));
		after.onReply(200, GSON.fromJson(reply, Trader.Reply.class), later);
		after.refill(feed(pick(564, "Cosmic rune", 103, 110, 18_000)), later + 1000);
		Trader.Step sell = next(after, feed(pick(564, "Cosmic rune", 103, 110, 18_000)), later + 1000);
		assertEquals(Trader.Kind.SELL, sell.getKind());
		assertEquals(111, sell.getPrice());
		after.onOffer(1, 564, "SELLING", 0, 18_000, 111, 0, later + 2000, true);
		after.onOffer(1, 564, "SOLD", 18_000, 18_000, 111, 1_998_000, later + 60_000, true);
		// 1,998,000 less 2 GP tax on each, less the 1,890,000 paid.
		assertEquals(72_000, after.takeMoments().get(0).getProfit());
	}

	@Test
	public void aSaleWhoseBuyWasNeverSeenShowsNoProfitRatherThanAGuess()
	{
		Trader trader = ready(10_000_000);
		trader.refill(feed(pick(564, "Cosmic rune", 105, 111, 18_000)), T0);
		// Bought on another device, so only the sale is seen.
		trader.onOffer(1, 564, "SELLING", 0, 18_000, 111, 0, T0 + 2000, true);
		trader.onOffer(1, 564, "SOLD", 18_000, 18_000, 111, 1_998_000, T0 + 60_000, true);
		Trader.Moment sold = trader.takeMoments().get(0);
		assertFalse(sold.isKnown());
		assertEquals("Sold 18,000", sold.profitText());
	}

	@Test
	public void aRestartKeepsTradesAndLinksAndCatchesABuyThatFilledWhileClosed()
	{
		Trader before = ready(10_000_000);
		before.refill(feed(pick(564, "Cosmic rune", 105, 111, 18_000)), T0);
		before.onOffer(0, 564, "BUYING", 0, 18_000, 105, 0, T0 + 1000, true);
		before.onOffer(0, 564, "BUYING", 6_000, 18_000, 105, 630_000, T0 + 2000, true);
		String saved = before.save();

		// RuneLite closes; the buy fills; RuneLite starts and the game sends the slot at login.
		long later = T0 + 3_600_000;
		Trader after = new Trader(GSON, later);
		after.setAccount("y3QWg8Nf", true);
		after.load(saved, later);
		after.request(later);
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson("{\"revision\":4,\"slots\":[{\"slot\":1,\"item\":{\"id\":564,\"name\":\"Cosmic rune\","
			+ "\"qty\":18000,\"buy\":105,\"sell\":111,\"origin\":\"auto_fill\"}}],\"budget\":10000000,\"rejected\":[]}",
			Trader.Plan.class);
		after.onReply(200, reply, later);
		after.onOffer(0, 564, "BOUGHT", 18_000, 18_000, 105, 1_890_000, later + 1000, false);
		after.onOffer(0, 564, "EMPTY", 0, 0, 0, 0, later + 2000, true);
		after.onOffer(0, 564, "SELLING", 0, 18_000, 111, 0, later + 3000, true);
		after.onOffer(0, 564, "SOLD", 18_000, 18_000, 111, 1_998_000, later + 60_000, true);
		Trader.Moment sold = after.takeMoments().get(0);
		assertTrue(sold.isKnown());
		assertEquals(72_000, sold.getProfit());
		assertTrue("the pick link came back too", after.save().contains("\"itemId\":564"));
	}

	@Test
	public void saysWhenThePlanHasNoRoom()
	{
		Trader trader = ready(10_000_000);
		assertTrue(trader.hasRoom());
		trader.setAccount("y3QWg8Nf", false);
		trader.refill(feed(free(2, "Steel cannonball"), free(453, "Coal"), free(440, "Iron ore")), T0 + 100);
		assertFalse(trader.hasRoom());
	}

	private static PlanFeed.Pick free(int id, String name)
	{
		PlanFeed.Pick p = pick(id, name, 100, 120, 1000);
		p.members = false;
		return p;
	}

	@Test
	public void announcesOnlyAchievementsEarnedWhileOpen()
	{
		Trader trader = ready(1_000_000);
		Trader.Reply first = new Trader.Reply();
		first.plan = trader.plan();
		first.summary = GSON.fromJson("{\"enabled\":true,\"profit\":40000,\"trades\":3,\"days\":[],"
			+ "\"achievements\":{\"earned\":1,\"total\":24,\"latest\":{\"id\":\"trades_1\",\"title\":\"First trade\","
			+ "\"text\":\"You completed your first trade.\",\"earnedAt\":\"2026-10-01\"}}}", Trader.Summary.class);
		trader.request(T0 + 1000);
		trader.onReply(200, first, T0 + 1000);
		// Earned before RuneLite opened: shown as Latest, not announced.
		assertEquals(40_000, trader.summary().profit);
		assertTrue(trader.takeEarned().isEmpty());

		Trader.Reply second = new Trader.Reply();
		second.plan = trader.plan();
		second.summary = GSON.fromJson("{\"enabled\":true,\"profit\":112000,\"trades\":4,\"days\":[],"
			+ "\"achievements\":{\"earned\":2,\"total\":24,\"latest\":{\"id\":\"net_100k\",\"title\":\"First 100K\","
			+ "\"text\":\"You made 100K GP.\",\"earnedAt\":\"2026-10-30\"}}}", Trader.Summary.class);
		trader.request(T0 + 2000);
		trader.onReply(200, second, T0 + 2000);
		assertEquals("net_100k", trader.takeEarned().get(0).id);
		assertTrue(trader.takeEarned().isEmpty());

		trader.request(T0 + 3000);
		trader.onReply(200, second, T0 + 3000);
		assertTrue(trader.takeEarned().isEmpty());
	}

	@Test
	public void aWaitShowsProgressAndWhatTheOffersShouldMake()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0 + 1000);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		trader.onOffer(0, 2, "BUYING", 400, 1000, 259, 103_600, T0 + 60_000, true);
		Trader.Step wait = next(trader, feed, T0 + 61_000);
		assertEquals(Trader.Kind.WAIT, wait.getKind());
		assertTrue(wait.getDetail(), wait.getDetail().startsWith("Steel cannonball: 400 of 1,000 bought in under a minute. Expected +"));
		assertTrue(wait.getDetail(), wait.getDetail().endsWith("We'll tell you when one fills."));
	}

	@Test
	public void sellsTheBoughtPartOfABuyWhileTheRestBuys()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 200, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 200, 0, T0 + 1000, true);
		// 100 would make about 7,500 after tax: too small a batch to use a slot on.
		trader.onOffer(0, 2, "BUYING", 100, 1000, 200, 20_000, T0 + 60_000, true);
		assertEquals(Trader.Kind.WAIT, next(trader, feed, T0 + 61_000).getKind());

		// 400 make about 30,000: sell them in the free slot and leave the buy at its price.
		trader.onOffer(0, 2, "BUYING", 400, 1000, 200, 80_000, T0 + 120_000, true);
		Trader.Step part = next(trader, feed, T0 + 121_000);
		assertEquals(Trader.Kind.SELL, part.getKind());
		assertEquals(400, part.getQty());
		assertEquals(280, part.getPrice());
		assertEquals(1, part.getGeSlot());
		assertEquals("400 at 280 each · GE slot 2. The rest can keep buying.", part.getDetail());

		// Listed: nothing more to sell until more is bought.
		trader.onOffer(1, 2, "SELLING", 0, 400, 280, 0, T0 + 180_000, true);
		Trader.Step wait = next(trader, feed, T0 + 21 * 60_000L + 1000);
		assertEquals(Trader.Kind.WAIT, wait.getKind());
		assertTrue(wait.getDetail(), wait.getDetail().startsWith("Steel cannonball: 400 of 1,000 bought in 21 minutes."));

		// The sale finishes before the buy: it's taken off the buy when the buy is recorded.
		trader.onOffer(1, 2, "SOLD", 400, 400, 280, 112_000, T0 + 30 * 60_000L, true);
		trader.onOffer(1, 2, "EMPTY", 0, 0, 0, 0, T0 + 31 * 60_000L, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 200, 200_000, T0 + 40 * 60_000L, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 41 * 60_000L, true);
		Trader.Step rest = next(trader, feed, T0 + 42 * 60_000L);
		assertEquals(Trader.Kind.SELL, rest.getKind());
		assertEquals(600, rest.getQty());
	}

	@Test
	public void aBuyNearlyDoneSellsItsBoughtPartEvenAtASmallProfit()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 1000, true);
		trader.onOffer(0, 2, "BUYING", 960, 1000, 259, 248_640, T0 + 60_000, true);
		Trader.Step part = next(trader, feed, T0 + 61_000);
		assertEquals(Trader.Kind.SELL, part.getKind());
		assertEquals(960, part.getQty());
	}

	@Test
	public void aStalledBuyKeepsItsPrice()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 250, 0, T0 + 1000, true);
		feed.picks.get(0).buy = 262;
		trader.refill(feed, T0 + 2000);
		Trader.Step wait = next(trader, feed, T0 + 30 * 60_000L);
		assertEquals(Trader.Kind.WAIT, wait.getKind());
	}

	@Test
	public void aBatchReadyWithEveryGeSlotFullSaysItWillAskWhenOneFrees()
	{
		Trader trader = ready(1_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 200, 280, 1000));
		trader.refill(feed, T0);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 200, 0, T0 + 1000, true);
		for (int slot = 1; slot < 8; slot++)
		{
			trader.onOffer(slot, 900 + slot, "BUYING", 0, 10, 100, 0, T0 + 1000, true);
		}
		trader.onOffer(0, 2, "BUYING", 400, 1000, 200, 80_000, T0 + 120_000, true);
		Trader.Step wait = next(trader, feed, T0 + 121_000);
		assertEquals(Trader.Kind.WAIT, wait.getKind());
		assertTrue(wait.getDetail(), wait.getDetail().contains(
			"Keep this buy running. We'll ask you to sell them when a slot is free."));
	}

	private static final String TODAY = java.time.LocalDate.ofEpochDay(T0 / 86_400_000L).toString();

	/** A trader holding 1,000 Steel cannonballs bought at 259 each, with Progress saved since the buy. */
	private static Trader holdingCannonballs()
	{
		Trader trader = ready(1_000_000);
		trader.refill(feed(pick(2, "Steel cannonball", 259, 280, 1000)), T0 + 1000);
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 60_000, true);
		trader.request(T0 + 61_000);
		trader.onReply(200, saved(trader, 0, 0, 50_000, 3), T0 + 62_000);
		return trader;
	}

	/** A reply with Progress saved: today so far, and an all-time total with today's bar. */
	private static Trader.Reply saved(Trader trader, long today, int flips, long profit, int trades)
	{
		Trader.Reply reply = new Trader.Reply();
		reply.plan = trader.plan();
		reply.progress = "saved";
		reply.today = new Trader.Today();
		reply.today.profit = today;
		reply.today.flips = flips;
		reply.summary = GSON.fromJson("{\"enabled\":true,\"profit\":" + profit + ",\"trades\":" + trades
			+ ",\"days\":[{\"day\":\"2000-01-01\",\"profit\":0,\"trades\":0},{\"day\":\"" + TODAY + "\",\"profit\":"
			+ today + ",\"trades\":" + flips + "}],\"achievements\":{\"earned\":0,\"total\":24}}", Trader.Summary.class);
		return reply;
	}

	@Test
	public void whatsSoldOfASaleStillGoingCountsTodayAndInProgress()
	{
		Trader trader = holdingCannonballs();
		assertEquals(0, trader.todaySoFar().profit);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 80_000, true);
		// 400 sold at 280, less 5 tax each, less the 259 each they cost.
		long sold = 112_000 - 400 * 5 - 400 * 259;
		assertEquals(6_400, sold);
		assertEquals(sold, trader.todaySoFar().profit);
		assertEquals(sold, trader.todaySoFar().selling);
		assertEquals(0, trader.todaySoFar().flips);
		Trader.Summary progress = trader.progressSoFar(T0 + 80_000);
		assertEquals(50_000 + sold, progress.profit);
		assertEquals(sold, progress.selling);
		assertEquals(3, progress.trades);
		assertEquals(sold, progress.days.get(1).profit);
		assertEquals(sold, progress.days.get(1).selling);
		assertEquals(0, progress.days.get(0).profit);
		// The website's own figures are left as they were.
		assertEquals(50_000, trader.summary().profit);

		// Collecting the coins leaves the offer as it was: the count stays.
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 90_000, true);
		assertEquals(sold, trader.todaySoFar().profit);
	}

	@Test
	public void aFinishedSaleCountsUntilASaveCarryingItComesBack()
	{
		Trader trader = holdingCannonballs();
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 80_000, true);
		// A save sent before the sale finished doesn't have it.
		trader.request(T0 + 85_000);
		trader.onOffer(0, 2, "SOLD", 1000, 1000, 280, 280_000, T0 + 90_000, true);
		trader.onReply(200, saved(trader, 0, 0, 50_000, 3), T0 + 91_000);
		long flip = 280_000 - 1000 * 5 - 259_000;
		assertEquals(flip, trader.todaySoFar().profit);
		assertEquals(50_000 + flip, trader.progressSoFar(T0 + 91_000).profit);
		// Collected: the slot empties and the plan item leaves; the sale still counts until it's saved.
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 95_000, true);
		assertEquals(flip, trader.todaySoFar().selling);

		// A save that carried it comes back with the website counting it: no double count.
		trader.request(T0 + 100_000);
		trader.onReply(200, saved(trader, flip, 1, 50_000 + flip, 4), T0 + 101_000);
		assertEquals(flip, trader.todaySoFar().profit);
		assertEquals(0, trader.todaySoFar().selling);
		assertEquals(50_000 + flip, trader.progressSoFar(T0 + 101_000).profit);
		assertEquals(0, trader.progressSoFar(T0 + 101_000).selling);
	}

	@Test
	public void aSaleTakenDownToChangeItsPriceStillCounts()
	{
		Trader trader = holdingCannonballs();
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 80_000, true);
		trader.onOffer(0, 2, "CANCELLED_SELL", 400, 1000, 280, 112_000, T0 + 90_000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 95_000, true);
		trader.request(T0 + 100_000);
		trader.onReply(200, saved(trader, 0, 0, 50_000, 3), T0 + 101_000);
		// Saved, but 600 are still to sell, so the website hasn't counted the 400.
		assertEquals(6_400, trader.todaySoFar().profit);
		trader.onOffer(0, 2, "SELLING", 0, 600, 275, 0, T0 + 110_000, true);
		trader.onOffer(0, 2, "SELLING", 100, 600, 275, 27_500, T0 + 120_000, true);
		assertEquals(6_400 + 27_500 - 100 * 5 - 100 * 259, trader.todaySoFar().profit);
	}

	@Test
	public void aSaleWithoutARecordOfItsBuyAddsNothing()
	{
		Trader trader = ready(1_000_000);
		trader.request(T0 + 1000);
		trader.onReply(200, saved(trader, 0, 0, 50_000, 3), T0 + 2000);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 80_000, true);
		assertEquals(0, trader.todaySoFar().profit);
		assertEquals(50_000, trader.progressSoFar(T0 + 80_000).profit);
	}

	@Test
	public void nothingIsAddedWhileSavingIsPaused()
	{
		Trader trader = holdingCannonballs();
		Trader.Reply paused = saved(trader, 0, 0, 50_000, 3);
		paused.summary.enabled = false;
		paused.today = null;
		trader.request(T0 + 63_000);
		trader.onReply(200, paused, T0 + 64_000);
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 400, 1000, 280, 112_000, T0 + 80_000, true);
		assertEquals(50_000, trader.progressSoFar(T0 + 80_000).profit);
	}

	@Test
	public void saysWhereTodaysProfitCameFrom()
	{
		assertEquals("3 flips", BoxText.soFarFrom(3, 0));
		assertEquals("1 flip and items still selling", BoxText.soFarFrom(1, 6_400));
		assertEquals("items still selling", BoxText.soFarFrom(0, -200));
	}
}
