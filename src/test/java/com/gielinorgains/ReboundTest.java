package com.gielinorgains;

import static com.gielinorgains.TraderTest.GSON;
import static com.gielinorgains.TraderTest.T0;
import static com.gielinorgains.TraderTest.feed;
import static com.gielinorgains.TraderTest.pick;
import static com.gielinorgains.TraderTest.ready;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** A Rebound follows its plan end to end, as on the website (utils/planner/reboundTrade.ts). */
public class ReboundTest
{
	private static final long HOUR = 3_600_000L;
	private static final int TELEPORT = 12409;

	/** A trader whose 8 slots are full of unbought picks, with a new Rebound seen at T0. */
	private static Trader full(PlanFeed.Feed feed)
	{
		Trader trader = ready(100_000_000);
		trader.refill(feed, T0);
		trader.request(T0 + 10);
		Trader.Reply reply = new Trader.Reply();
		reply.plan = GSON.fromJson(GSON.toJson(trader.plan()), Trader.Plan.class);
		reply.rebound = new Trader.Rebound();
		reply.rebound.key = "r1";
		reply.rebound.id = TELEPORT;
		reply.rebound.name = "Tai bwo wannai teleport";
		reply.rebound.drop = 0.14;
		reply.rebound.buy = 3665;
		reply.rebound.sell = 4817;
		reply.rebound.qty = 70;
		reply.rebound.detectedAt = T0;
		trader.onReply(200, reply, T0 + 20);
		return trader;
	}

	private static PlanFeed.Feed eight()
	{
		PlanFeed.Feed feed = feed();
		for (int i = 0; i < 8; i++)
		{
			feed.picks.add(pick(100 + i, "Pick " + i, 1000, 1200 + 10 * i, 1000));
		}
		return feed;
	}

	private static Trader.PlanItem planned(Trader trader, int id)
	{
		for (Trader.PlanSlot slot : trader.plan().slots)
		{
			if (slot.item != null && slot.item.id == id)
			{
				return slot.item;
			}
		}
		return null;
	}

	@Test
	public void withEverySlotFullItTakesThePlaceOfTheUnboughtPickWorthLeast()
	{
		PlanFeed.Feed feed = eight();
		Trader trader = full(feed);
		assertFalse(trader.hasRoom());
		// Pick 0 makes the least, but its buy is in, so pick 1 goes.
		trader.onOffer(0, 100, "BUYING", 0, 1000, 1000, 0, T0 + 30, true);
		assertEquals("Pick 1", trader.reboundSwap().name);
		assertTrue(trader.add(TELEPORT, "Tai bwo wannai teleport", 70, 3665, 4817, true, T0 + 40));
		Trader.PlanItem rebound = planned(trader, TELEPORT);
		assertEquals(Long.valueOf(T0), rebound.reboundAt);
		assertNull(planned(trader, 101));
		assertNull(trader.rebound());
	}

	@Test
	public void saysWhyWhenEveryGeSlotHasAnOffer()
	{
		PlanFeed.Feed feed = eight();
		Trader trader = full(feed);
		for (int i = 0; i < 8; i++)
		{
			trader.onOffer(i, 100 + i, "BUYING", 0, 1000, 1000, 0, T0 + 30, true);
		}
		assertNull(trader.reboundSwap());
		assertFalse(trader.add(TELEPORT, "Tai bwo wannai teleport", 70, 3665, 4817, true, T0 + 40));
	}

	@Test
	public void followsThePlanTheDayToBuyThenTwoDaysToSell()
	{
		PlanFeed.Feed feed = eight();
		Trader trader = full(feed);
		trader.add(TELEPORT, "Tai bwo wannai teleport", 70, 3665, 4817, true, T0 + 40);
		feed.staleAt = T0 + 60 * HOUR;
		Trader.Step buy = trader.stepFor(feed, T0 + 60_000, id -> 0, TELEPORT);
		assertEquals(Trader.Kind.BUY, buy.getKind());
		assertEquals(3665, buy.getPrice());
		assertTrue(buy.getDetail().contains("Rebound: leave the buy up for a day, then sell at 4,817 within 2 days."));

		trader.onOffer(3, TELEPORT, "BUYING", 0, 70, 3665, 0, T0 + 2 * 60_000, true);
		Trader.SlotRow row = trader.slots(T0 + HOUR).get(0);
		assertTrue(row.getStatus(), row.getStatus().endsWith("· 23h left"));
		assertTrue(trader.badge(3, T0 + HOUR, null).getText().endsWith("23h left"));

		// A day after the buy went in, the rest is cancelled; what bought sells at the usual price.
		trader.onOffer(3, TELEPORT, "BUYING", 30, 70, 3665, 30 * 3665, T0 + 3 * HOUR, true);
		Trader.Step stop = trader.stepFor(feed, T0 + 25 * HOUR, id -> 0, TELEPORT);
		assertEquals(Trader.Kind.STOP, stop.getKind());
		assertEquals("Stop the rest of your Tai bwo wannai teleport buy", stop.getTitle());

		trader.onOffer(3, TELEPORT, "CANCELLED_BUY", 30, 70, 3665, 30 * 3665, T0 + 25 * HOUR, true);
		trader.onOffer(3, TELEPORT, "EMPTY", 0, 0, 0, 0, T0 + 25 * HOUR + 1000, true);
		Trader.Step sell = trader.stepFor(feed, T0 + 25 * HOUR + 2000, id -> 4000, TELEPORT);
		assertEquals(Trader.Kind.SELL, sell.getKind());
		assertEquals(4817, sell.getPrice());

		trader.onOffer(3, TELEPORT, "SELLING", 0, 30, 4817, 0, T0 + 26 * HOUR, true);
		assertTrue(trader.slots(T0 + 30 * HOUR).get(0).getStatus().endsWith("Selling · 18h left"));
		// It keeps its price past the 8 hours, until 2 days after the drop.
		assertNull(trader.stepFor(feed, T0 + 40 * HOUR, id -> 4000, TELEPORT));
		Trader.Step lower = trader.stepFor(feed, T0 + 49 * HOUR, id -> 3500, TELEPORT);
		assertEquals(Trader.Kind.LOWER, lower.getKind());
		assertEquals(3500, lower.getPrice());
		assertTrue(lower.getTitle().startsWith("The 2 days are up"));
	}

	@Test
	public void anUnboughtReboundLeavesThePlanAnHourAfterTheDrop()
	{
		PlanFeed.Feed feed = eight();
		Trader trader = full(feed);
		trader.add(TELEPORT, "Tai bwo wannai teleport", 70, 3665, 4817, true, T0 + 40);
		feed.staleAt = T0 + 3 * HOUR;
		trader.refill(feed, T0 + 30 * 60_000);
		assertTrue(trader.planned(TELEPORT));
		trader.refill(feed, T0 + 61 * 60_000);
		assertFalse(trader.planned(TELEPORT));
	}

	@Test
	public void buysFewerWhenTheBudgetIsSmaller()
	{
		Trader trader = full(eight());
		trader.setBudget(9_000_000);
		// The eight picks hold 8M at 1,000 each, less the one it replaces: 2M is left.
		assertTrue(trader.add(TELEPORT, "Tai bwo wannai teleport", 70_000, 3665, 4817, true, T0 + 40));
		assertEquals(545, planned(trader, TELEPORT).qty);
	}

	@Test
	public void countsDownInTheRebounds()
	{
		assertEquals(T0 + 25 * HOUR, Rebounds.buyEndsAt(T0, T0 + HOUR));
		assertEquals(T0 + 48 * HOUR, Rebounds.buyEndsAt(T0, T0 + 40 * HOUR));
		assertEquals(T0 + 24 * HOUR, Rebounds.buyEndsAt(T0, null));
		assertEquals("18h", Rebounds.left(18 * HOUR + 30 * 60_000));
		assertEquals("40m", Rebounds.left(40 * 60_000));
		assertEquals("Nothing bought yet. 19h left to buy. It often fills when the price dips again. Then sell at 115,000 GP"
			+ " within 2 days.", Rebounds.buying(0, 10, "19h", 115_000));
	}
}
