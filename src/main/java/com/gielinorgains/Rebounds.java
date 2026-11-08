package com.gielinorgains;

/**
 * A Rebound in Trader follows the plan its email gives, the one the research scored, not the flip
 * rules: buy at the alert's price and leave the offer up for a day without raising it, sell at the
 * usual price until 2 days after the drop, then sell at the going price. Ported from the website's
 * utils/planner/reboundTrade.ts, with the same words.
 */
final class Rebounds
{
	static final long BUY_MS = 24 * 3_600_000L;
	static final long SELL_MS = 48 * 3_600_000L;
	/** A Rebound in the plan can be bought up to an hour after the drop, as its tests allowed. Then it leaves. */
	static final long PLACE_MS = 3_600_000L;

	private Rebounds()
	{
	}

	/** The buy stays up a day from when it went in (from the drop if that isn't known), never past selling's end. */
	static long buyEndsAt(long alertAt, Long buyStartedAt)
	{
		long start = buyStartedAt != null && buyStartedAt >= alertAt ? buyStartedAt : alertAt;
		return Math.min(start + BUY_MS, sellEndsAt(alertAt));
	}

	/** Selling at the usual price stops 2 days after the drop. */
	static long sellEndsAt(long alertAt)
	{
		return alertAt + SELL_MS;
	}

	/** "18h" or "40m", as the slot badges count down. */
	static String left(long ms)
	{
		long minutes = Math.max(1, ms / 60_000);
		return minutes >= 60 ? (minutes / 60) + "h" : minutes + "m";
	}

	static String buyingTitle(String name)
	{
		return "Leave your " + name + " buy up";
	}

	static String buying(long filled, long total, String left, long sell)
	{
		return (filled > 0 ? "Bought " + BoxText.whole(filled) + " of " + BoxText.whole(total) + " so far." : "Nothing bought yet.")
			+ " " + left + " left to buy. It often fills when the price dips again. Then sell at " + BoxText.whole(sell)
			+ " GP within 2 days.";
	}

	static String buyOverTitle(String name)
	{
		return "Stop the rest of your " + name + " buy";
	}

	static String buyOver(long filled)
	{
		return filled > 0
			? "The day to buy is over. Cancel the rest of the offer. You keep the " + BoxText.whole(filled)
			+ " you bought, and we'll help you sell them."
			: "Nothing bought in a day. Cancel the offer and collect your GP. This Rebound ends here.";
	}

	static String selling(long filled, long total, String left, long sell)
	{
		return "Sold " + BoxText.whole(filled) + " of " + BoxText.whole(total) + " so far. " + left + " left to sell at "
			+ BoxText.whole(sell) + " GP. " + SELL_NOTE;
	}

	static final String SELL_NOTE = "If it hasn't sold in 2 days, sell at the going price.";
	static final String SELL_OVER_TITLE = "The 2 days are up";

	static String sellOver(long price)
	{
		return "Change the sell offer to " + BoxText.whole(price) + " GP each, the going price, so your GP is free for your next trade.";
	}

	/** One line for the box and the buy step: the plan in short. */
	static String plan(long sell)
	{
		return "Rebound: leave the buy up for a day, then sell at " + BoxText.whole(sell) + " within 2 days.";
	}

	static String swap(String name)
	{
		return "Takes the place of " + name + ". You haven't bought it yet.";
	}

	static final String NO_ROOM = "Every GE slot has an offer in it. Cancel a buy that hasn't filled, then add it.";
	static final String FINISHED = "Rebound finished";
}
