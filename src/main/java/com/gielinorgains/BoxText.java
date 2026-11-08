package com.gielinorgains;

import com.google.common.collect.ImmutableMap;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * Works out what the box says, with no game or network access. The words follow the site's item
 * page and Market rows, in ASCII, because RuneLite's fonts can't draw curly quotes or the minus sign.
 * No text from the server is ever shown: reasons come from the fixed table below, keyed by code.
 */
final class BoxText
{
	/**
	 * The title; plain text and values; the lead figures in bold (Expected profit, Chance and the
	 * guide's instruction), as the site's cards lead with them; quieter notes; and record squares.
	 */
	enum Kind
	{
		TITLE, PLAIN, LEAD, NOTE, SQUARES,
		/** A heads-up in amber, such as a price that just jumped. */
		WARN,
		/** The keys to type (left) and what's typed so far (right), drawn as keys that light up. */
		KEYS
	}

	@Value
	static class Line
	{
		String left;
		String right;
		Kind kind;
	}

	@Value
	static class Box
	{
		List<Line> lines;
		/** Lines for the hover tooltip, each short enough to read. */
		List<String> tooltip;
	}

	/** The site's reasonFallback: for an unknown code, or an item with no reason. */
	/** The item page's LATEST_TRADE_STALE_MS: trades over a day old are dated. */
	private static final long LATEST_TRADE_STALE_SECONDS = 24 * 60 * 60;

	static final String DEFAULT_REASON = "It isn't one of today's suggestions. The Market's checks on price, trading"
		+ " and the 2% tax don't support a flip right now.";

	/** Why the Market isn't suggesting an item, keyed by the codes in the site's skipReasons.ts. */
	static final Map<String, String> REASONS = ImmutableMap.<String, String>builder()
		.put("missing_prices", "The OSRS Wiki doesn't have both a buy and a sell price for it right now.")
		.put("low_volume", "Almost nobody traded it in the last day, so an offer might never fill.")
		.put("short_history", "There aren't enough recent trades to work out good prices for it.")
		.put("stale_prices", "Its latest prices aren't recent enough to trust right now.")
		.put("no_profit_after_tax", "The gap between its buy and sell prices is too small to make money after tax.")
		.put("small_profit", "Buying as many as recent trades can fill would make under 8,000 GP after tax.")
		.put("wide_spread", "Its buy and sell prices are too far apart to trust.")
		.put("zero_quantity", "Not enough has traded near these prices lately to suggest buying any.")
		.put("too_expensive", "One costs more than 3M GP, the most the Market suggests putting into one item.")
		.put("price_spike", "Its price just jumped, and too few sales at the new price back it up yet.")
		.put("thin_margin", "Each item makes so little profit that a tiny price drop would wipe it out.")
		.put("thin_exit", "Few players have bought it at the sell price lately, so it might not sell.")
		.build();

	/** When the list gives no staleAt, prices go stale this long after they're from, as the site's do. */
	static final long STALE_AFTER_MS = 15 * 60_000L;

	/** lib/items/selfTradeCopy.ts and describeSelfTrade, in ASCII. */
	static final String SELF_TRADE_TITLE = "Buying or selling for yourself?";
	static final String SELF_TRADE_CLAIM = "These offers did best when we tested them on past GE trades.";
	static final String SELF_TRADE_CROSSED = "Right now the buy offer is higher than the sell offer. One recent trade"
		+ " was probably unusual, so either number could be off. Check the price in the GE and change your offer if it"
		+ " looks different.";
	static final Map<String, String> SELF_TRADE_FILL = ImmutableMap.of(
		"busy", "In our tests, about 6 in 7 offers to buy or sell one completed within an hour.",
		"hourly", "In our tests, a little under half of offers to buy or sell one completed within an hour.",
		"rare", "This item trades less than once an hour, so your offer may wait a long time. On items this quiet,"
			+ " only about 1 in 10 offers completed within an hour in our tests.");
	/** The item page's no-flip reason for a rare item, when the fill line already says an offer may wait. */
	static final String RARE_REASON = "Almost nobody traded it in the last day.";

	static final String SHIFT_HINT = "Shift+right-click to open the item page.";
	static final String CANT_REACH = "Can't reach Gielinor Gains.";
	static final String TRY_AGAIN = "We'll try again soon.";
	static final String CHANCE_TIP = "Chance: how often trades like this made money within 4 hours, at our prices"
		+ " and quantity. It isn't the chance your offer fills.";
	private static final int TOOLTIP_WIDTH = 60;

	/** The box for an item, or the loading, error and out-of-date boxes that don't depend on it. */
	static Box forItem(ItemCheck.Snapshot snapshot, boolean lastFailed, int itemId, boolean sell, long clientNow)
	{
		return forItem(snapshot, lastFailed, itemId, sell, clientNow, null);
	}

	/** The item page's note on a price jump (lib/items/pumpWarning.ts PUMP_WARNING_COPY). */
	static final String PUMP_TITLE = "You may be buying at the top";
	static final String PUMP_BODY = "Jumps like this often fall back. In past jumps, about half dropped by more than a"
		+ " tenth within a week. Waiting a week got a lower price more than half the time. About 1 in 4 kept rising,"
		+ " so it's your call.";

	/** describePumpRise: "It's up about 40% on the week before, after jumping on far more trades than usual." */
	static String pumpRise(double rise)
	{
		// The list drops an item once it's less than 15% up, so this never reads below 15%.
		long percent = Math.max(15, Math.round(rise * 100 / 5) * 5);
		return "It's up about " + percent + "% on the week before, after jumping on far more trades than usual.";
	}

	/**
	 * A buy the player picks themselves on an item whose price just jumped leads with the item page's
	 * note, under the title; the rest of the box is as it was. It never blocks the buy.
	 */
	private static Box withPump(Box box, ItemCheck.Pump pump)
	{
		List<Line> lines = new ArrayList<>(box.getLines());
		lines.add(Math.min(1, lines.size()), new Line(PUMP_TITLE, null, Kind.WARN));
		lines.add(Math.min(2, lines.size()), note(pumpRise(pump.rise)));
		List<String> tooltip = new ArrayList<>();
		wrap(tooltip, PUMP_BODY);
		tooltip.addAll(box.getTooltip());
		return new Box(Collections.unmodifiableList(lines), Collections.unmodifiableList(tooltip));
	}

	/**
	 * {@code price} is the item's latest GE trades and tested offers, or null. With no flip, the box leads
	 * with the tested offers as the item page does (else the latest trades), so a player buying for
	 * themselves still sees what to offer.
	 */
	static Box forItem(ItemCheck.Snapshot snapshot, boolean lastFailed, int itemId, boolean sell, long clientNow,
		TraderApi.Price price)
	{
		Box box = itemBox(snapshot, lastFailed, itemId, sell, clientNow, price);
		ItemCheck.Entry entry = snapshot == null || snapshot.minPlugin > ItemCheck.PLUGIN_VERSION ? null
			: snapshot.items.get(itemId);
		return sell || entry == null || entry.pump == null ? box : withPump(box, entry.pump);
	}

	private static Box itemBox(ItemCheck.Snapshot snapshot, boolean lastFailed, int itemId, boolean sell, long clientNow,
		TraderApi.Price price)
	{
		ItemCheck.Entry entry = snapshot == null ? null : snapshot.items.get(itemId);
		ItemCheck.Record record = entry == null ? null : entry.record;
		Line[] status = status(snapshot, lastFailed, clientNow);
		if (snapshot == null || snapshot.minPlugin > ItemCheck.PLUGIN_VERSION)
		{
			return box(null, status);
		}
		Line recordLine = recordLine(snapshot, record);
		if (status != null)
		{
			return box(record, status[0], status[1], recordLine, squares(record));
		}
		if (entry == null || entry.flip == null)
		{
			String reason = entry == null || entry.skip == null ? null : REASONS.get(entry.skip);
			if (price != null && price.offers != null && price.offers.buy > 0 && price.offers.sell > 0)
			{
				return selfTrade(price.offers, itemId, entry == null ? null : entry.skip, reason, record, recordLine,
					snapshot.serverNow(clientNow));
			}
			Line[] gePrice = gePrice(price, snapshot.serverNow(clientNow));
			return box(record, gePrice[0], gePrice[1], gePrice[2], plain("No flip suggested right now."),
				note(reason == null ? DEFAULT_REASON : reason), recordLine, squares(record));
		}
		return flip(entry.flip, record, recordLine, itemId == ItemID.OSRS_BOND, sell,
			snapshot.serverNow(clientNow) - snapshot.sourceAt);
	}

	/**
	 * The two lines that stand in for prices we can't show: loading, can't reach, out of date or
	 * stale. Null when the prices are fine. The box and the Market list say the same things.
	 */
	static Line[] status(ItemCheck.Snapshot snapshot, boolean lastFailed, long clientNow)
	{
		if (snapshot == null)
		{
			return lastFailed ? new Line[]{plain(CANT_REACH), note(TRY_AGAIN)} : new Line[]{note("Getting prices..."), null};
		}
		if (snapshot.minPlugin > ItemCheck.PLUGIN_VERSION)
		{
			return new Line[]{plain("This version is out of date."), note("Restart RuneLite to update it.")};
		}
		long serverNow = snapshot.serverNow(clientNow);
		boolean stale = !snapshot.fresh || snapshot.sourceAt == null
			|| serverNow >= (snapshot.staleAt != null ? snapshot.staleAt : snapshot.sourceAt + STALE_AFTER_MS);
		if (!stale)
		{
			return null;
		}
		if (lastFailed)
		{
			return new Line[]{plain(CANT_REACH), note(TRY_AGAIN)};
		}
		String age = snapshot.sourceAt == null ? null : staleAge(serverNow - snapshot.sourceAt);
		return new Line[]{
			plain(age == null ? "Our prices aren't up to date right now." : "Our prices are " + age + " old."),
			note("We'll show them once they update.")};
	}

	/** The item page's record squares, under the record line; none without stored tests. */
	static Line squares(ItemCheck.Record record)
	{
		return record == null || record.flips == 0 || record.checks.isEmpty() ? null : new Line(record.checks, null, Kind.SQUARES);
	}

	/** The record line, or none when the site couldn't read records and this item has none: unknown, not empty. */
	static Line recordLine(ItemCheck.Snapshot snapshot, ItemCheck.Record record)
	{
		return record == null && !snapshot.recordsComplete ? null : note(recordLine(record));
	}

	/**
	 * The item page's "GE price" with no flip: the latest trades, and when they were. Trades over a day
	 * old are dated, as the page dates them. Nulls when either price is missing.
	 */
	static Line[] gePrice(TraderApi.Price price, long serverNow)
	{
		if (price == null || price.low == null || price.high == null || price.low <= 0 || price.high <= 0)
		{
			return new Line[3];
		}
		long nowSeconds = serverNow / 1000;
		boolean stale = old(price.lowAt, nowSeconds) || old(price.highAt, nowSeconds);
		String source = "From the latest trades";
		if (stale)
		{
			String days = tradeDays(price.lowAt, price.highAt, serverNow);
			source = days == null ? "From older trades" : "From trades on " + days;
		}
		return new Line[]{
			new Line("Buy price", whole(price.low), Kind.LEAD),
			new Line("Sell price", whole(price.high), Kind.PLAIN),
			note(source),
		};
	}

	/**
	 * With no flip, the item page's "Buying or selling for yourself?" (#607): the tested offers, what
	 * the sale keeps, how old they are, and how often offers like them filled, or, when the prices cross,
	 * why they may be off. The claim and the right-away prices go in the hover tooltip, so the box stays short.
	 */
	static Box selfTrade(TraderApi.Offers offers, int itemId, String skip, String reason, ItemCheck.Record record,
		Line recordLine, long serverNow)
	{
		boolean crossed = offers.buy > offers.sell;
		boolean rare = "rare".equals(offers.activity);
		String keeps = whole(offers.sellAfterTax) + " GP";
		String sellKeeps = itemId == ItemID.OSRS_BOND ? "After the conversion fee, you keep " + keeps + "."
			: offers.sellAfterTax == offers.sell ? "There's no GE tax, so you keep " + keeps + "."
			: "After the 2% tax, you keep " + keeps + ".";
		String fill = SELF_TRADE_FILL.get(offers.activity);
		List<String> tooltip = new ArrayList<>();
		wrap(tooltip, SELF_TRADE_CLAIM);
		// Crossed prices need the room in the box, so how often offers filled moves to the tooltip.
		if (crossed)
		{
			wrap(tooltip, fill);
		}
		// About 2% was measured on items trading at least once an hour; a rare item may have nobody to trade with.
		if (!crossed && !rare)
		{
			wrap(tooltip, "Need it now? Buying right away costs about " + whole(offers.sell) + " GP, what buyers have"
				+ " been paying. Selling right away gets about " + whole(offers.buy) + " GP, what sellers have been"
				+ " taking. In our tests, waiting up to an hour for an offer saved about 2% compared with trading"
				+ " right away.");
		}
		return box(record, tooltip,
			plain(SELF_TRADE_TITLE),
			new Line("Buy offer", whole(offers.buy), Kind.LEAD),
			new Line("Sell offer", whole(offers.sell), Kind.PLAIN),
			note(sellKeeps),
			note("Prices from " + pricesAge(offers.at * 1000, serverNow)),
			crossed ? note(SELF_TRADE_CROSSED) : fill == null ? null : note(fill),
			plain("No flip suggested right now."),
			note("low_volume".equals(skip) && rare ? RARE_REASON : reason == null ? DEFAULT_REASON : reason),
			recordLine, squares(record));
	}

	/** PricesAge's pricesAgeLabel: "the last minute", "4 minutes ago", "2 hours ago". */
	static String pricesAge(long at, long now)
	{
		long minutes = Math.max(0, Math.round((now - at) / 60_000.0));
		if (minutes <= 1)
		{
			return "the last minute";
		}
		if (minutes < 60)
		{
			return minutes + " minutes ago";
		}
		long hours = minutes / 60;
		return hours + (hours == 1 ? " hour ago" : " hours ago");
	}

	private static boolean old(Long at, long nowSeconds)
	{
		return at == null || at <= 0 || nowSeconds - at > LATEST_TRADE_STALE_SECONDS;
	}

	/** The site's describeTradeDays with sides: "Oct 17", or "Oct 17 (buy) and Oct 18 (sell)". UTC. */
	static String tradeDays(Long buyAt, Long sellAt, long clientNow)
	{
		int year = Instant.ofEpochMilli(clientNow).atZone(ZoneOffset.UTC).getYear();
		String buy = tradeDay(buyAt, year);
		String sell = tradeDay(sellAt, year);
		if (buy != null && buy.equals(sell))
		{
			return buy;
		}
		List<String> parts = new ArrayList<>();
		if (buy != null)
		{
			parts.add(buy + " (buy)");
		}
		if (sell != null)
		{
			parts.add(sell + " (sell)");
		}
		return parts.isEmpty() ? null : String.join(" and ", parts);
	}

	private static String tradeDay(Long at, int year)
	{
		if (at == null || at <= 0)
		{
			return null;
		}
		ZonedDateTime day = Instant.ofEpochSecond(at).atZone(ZoneOffset.UTC);
		return day.format(DateTimeFormatter.ofPattern(
			day.getYear() == year ? "MMM d" : "MMM d, yyyy", Locale.US));
	}

	private static Box flip(ItemCheck.Flip flip, ItemCheck.Record record, Line recordLine, boolean bond, boolean sell, long ageMs)
	{
		String buy = whole(flip.buy);
		String sellAt = whole(flip.sell);
		String qty = whole(flip.qty);
		List<Line> lines = new ArrayList<>();
		// The item page's labels. Its "Today's flip" heading is left out to keep the box short.
		lines.add(new Line("Gielinor Gains", null, Kind.TITLE));
		lines.add(new Line("Buy offer", buy, Kind.PLAIN));
		lines.add(new Line("Sell offer", sellAt, Kind.PLAIN));
		lines.add(new Line("Quantity", qty, Kind.PLAIN));
		lines.add(new Line("Expected profit", formatNumber(flip.expectedProfit), Kind.LEAD));
		lines.add(new Line("If it all sells", formatNumber(flip.profitIfAllSells), Kind.PLAIN));
		lines.add(new Line("Chance", percent(flip.chance), Kind.LEAD));
		if (recordLine != null)
		{
			lines.add(recordLine);
		}
		if (squares(record) != null)
		{
			lines.add(squares(record));
		}
		lines.add(note("Prices from " + pricesAge(ageMs)));

		String tax = bond ? "the conversion fee" : "tax";
		List<String> tooltip = new ArrayList<>();
		wrap(tooltip, CHANCE_TIP);
		wrap(tooltip, profitTip(tax));
		wrap(tooltip, recordTooltip(record, tax));
		if (sell)
		{
			wrap(tooltip, "These numbers are for a new flip: buying " + qty + " at " + buy + " and selling at " + sellAt
				+ ". They aren't about items you already have.");
		}
		tooltip.add("Estimates, not promises.");
		tooltip.add(SHIFT_HINT);
		return new Box(Collections.unmodifiableList(lines), Collections.unmodifiableList(tooltip));
	}

	/** The guide's box: the pinned flip, what to do now, and a second line such as the live check. */
	static final String COLLECT_TIP = "Tip: RuneLite's Menu Entry Swapper can make Collect a left-click"
		+ " (GE item collect).";

	static Box guide(String name, Guide.Phase phase, Guide.Step step, String tip)
	{
		return guide(name, phase, step, tip, false);
	}

	/** As above; {@code collectTip} adds the one-time Collect tip under a collect step. */
	static Box guide(String name, Guide.Phase phase, Guide.Step step, String tip, boolean collectTip)
	{
		return guide(name, phase, step, tip, collectTip, null);
	}

	/** The most keys drawn under a step; a longer search is left in words. */
	private static final int MAX_KEYS = 12;

	/** As above; {@code typed} is what's in the chatbox, for the keys under a "Type 11k" step. */
	static Box guide(String name, Guide.Phase phase, Guide.Step step, String tip, boolean collectTip, String typed)
	{
		return guide(name, phase, step, tip, collectTip, typed, null);
	}

	/** As above; {@code planNote} is a Rebound's plan, shown under the item instead of the flip's words. */
	static Box guide(String name, Guide.Phase phase, Guide.Step step, String tip, boolean collectTip, String typed,
		String planNote)
	{
		List<Line> lines = new ArrayList<>();
		lines.add(new Line("Gielinor Gains", null, Kind.TITLE));
		String doing = phase == Guide.Phase.SELL || phase == Guide.Phase.SELLING ? "Selling "
			: phase == Guide.Phase.DONE ? (planNote != null ? "Your Rebound: " : "Your flip: ") : "Buying ";
		lines.add(note(doing + name + (step.getStage() > 0 ? ", step " + step.getStage() + " of 5" : "")));
		if (planNote != null && phase != Guide.Phase.DONE)
		{
			lines.add(note(planNote));
		}
		lines.add(new Line(step.getAction(), null, Kind.LEAD));
		// The keys to type, while they fit the box: an amount, or the shortest search for the item.
		if (typed != null && step.getStage() >= 2 && step.getStage() <= 4 && step.getAction().startsWith("Type ")
			&& step.getAction().length() - 5 <= MAX_KEYS)
		{
			String keys = step.getAction().substring(5);
			String text = typed.trim().toLowerCase(Locale.ROOT);
			// Any other part of the item's name brings it up too, so a search typed its own way stays blue.
			boolean ownWay = step.getStage() == 2 && !text.isEmpty() && !keys.startsWith(text)
				&& name.toLowerCase(Locale.ROOT).contains(text);
			if (!ownWay || text.length() <= MAX_KEYS)
			{
				lines.add(new Line(ownWay ? text : keys, typed, Kind.KEYS));
			}
		}
		if (step.getDetail() != null)
		{
			lines.add(note(step.getDetail()));
		}
		if (collectTip)
		{
			lines.add(note(COLLECT_TIP));
		}
		List<String> tooltip = new ArrayList<>();
		wrap(tooltip, tip);
		if (collectTip)
		{
			tooltip.add("Shift+right-click the box to hide the tip.");
		}
		tooltip.add(SHIFT_HINT);
		return new Box(Collections.unmodifiableList(lines), Collections.unmodifiableList(tooltip));
	}

	/** A box with the title, the given lines, and the record and Shift lines as its tooltip. */
	private static Box box(ItemCheck.Record record, Line... body)
	{
		return box(record, new ArrayList<>(), body);
	}

	/** {@code tooltip} holds lines to show on hover before the record and the shift hint. */
	private static Box box(ItemCheck.Record record, List<String> tooltip, Line... body)
	{
		List<Line> lines = new ArrayList<>();
		lines.add(new Line("Gielinor Gains", null, Kind.TITLE));
		Arrays.stream(body).filter(Objects::nonNull).forEach(lines::add);
		wrap(tooltip, recordTooltip(record, "tax"));
		tooltip.add(SHIFT_HINT);
		return new Box(Collections.unmodifiableList(lines), Collections.unmodifiableList(tooltip));
	}

	/** The record line, in the words of the Market's rows ("Last 10 tests: 7 made money"). */
	static String recordLine(ItemCheck.Record record)
	{
		return recordLine(record, "Record: ");
	}

	/** As above; a record with no stored tests reads as the site's totals after {@code label}. */
	static String recordLine(ItemCheck.Record record, String label)
	{
		if (record == null || record.flips == 0)
		{
			return "No track record yet";
		}
		String checks = record.checks;
		if (checks.isEmpty())
		{
			return label + signedPercent(record.avgReturn) + " after tax over " + record.flips
				+ (record.flips == 1 ? " test" : " tests");
		}
		int madeMoney = checks.replace("0", "").length();
		if (checks.length() == 1)
		{
			return madeMoney == 1 ? "Last test: made money" : "Last test: didn't make money";
		}
		return "Last " + checks.length() + " tests: " + madeMoney + " made money";
	}

	static String profitTip(String tax)
	{
		return "Expected profit: about what you'd make after " + tax + " at this quantity."
			+ " It allows for offers that fill slowly or never fill.";
	}

	static String recordTooltip(ItemCheck.Record record, String tax)
	{
		if (record == null || record.flips == 0)
		{
			return null;
		}
		return "Record: " + signedPercent(record.avgReturn) + " average profit after " + tax + ", from "
			+ record.flips + (record.flips == 1 ? " test" : " tests") + ". Every 2 hours we save our suggested flip,"
			+ " then test it against the trades that came next.";
	}

	/** "the last minute", "6 minutes ago", "2 hours ago", as the item page words it. */
	static String pricesAge(long ageMs)
	{
		long minutes = Math.max(0, Math.round(ageMs / 60_000.0));
		if (minutes <= 1)
		{
			return "the last minute";
		}
		if (minutes < 60)
		{
			return minutes + " minutes ago";
		}
		long hours = minutes / 60;
		return hours + (hours == 1 ? " hour ago" : " hours ago");
	}

	private static String staleAge(long ageMs)
	{
		long minutes = Math.round(ageMs / 60_000.0);
		if (minutes < 2)
		{
			return null;
		}
		return minutes < 60 ? minutes + " minutes" : "over an hour";
	}

	/** The site's formatNumber: 812, 29K, 1.20M, 1B, -12K. K floors to whole thousands; M and B to 2 places. */
	static String formatNumber(double value)
	{
		double abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1_000_000_000)
		{
			return sign + hundredths(abs / 1_000_000_000) + "B";
		}
		if (abs >= 1_000_000)
		{
			return sign + hundredths(abs / 1_000_000) + "M";
		}
		return abs >= 1_000 ? sign + (long) Math.floor(abs / 1_000) + "K" : decimal("#,##0.###").format(value);
	}

	/** A whole number with commas: 5844 to "5,844". */
	static String whole(double value)
	{
		return decimal("#,##0").format(value);
	}

	/**
	 * Where today's profit came from: "3 flips", "items still selling" or both. Items still selling
	 * count what's sold of them so far, before the website saves the trade.
	 */
	static String soFarFrom(int flips, long selling)
	{
		String done = flips + (flips == 1 ? " flip" : " flips");
		if (selling == 0)
		{
			return done;
		}
		return flips > 0 ? done + " and items still selling" : "items still selling";
	}

	/** Chance as a whole percentage, rounded half up as JavaScript's Math.round does. */
	static String percent(double chance)
	{
		return Math.round(chance * 100) + "%";
	}

	/** A return as a signed one-decimal percentage, "+6.2%" or "-0.9%", with an ASCII minus. */
	static String signedPercent(double value)
	{
		double rounded = Math.round(value * 1_000) / 10.0;
		String sign = rounded > 0 ? "+" : rounded < 0 ? "-" : "";
		return sign + String.format(Locale.ROOT, "%.1f%%", Math.abs(rounded));
	}

	/** Rounded down to 2 places, without ".00" on a whole number, as JavaScript's toFixed prints it. */
	private static String hundredths(double value)
	{
		double floored = Math.floor(value * 100) / 100;
		return String.format(Locale.ROOT, floored == Math.rint(floored) ? "%.0f" : "%.2f", floored);
	}

	private static DecimalFormat decimal(String pattern)
	{
		DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
		format.setRoundingMode(RoundingMode.HALF_UP);
		return format;
	}

	/** Adds a sentence, if any, to the tooltip, split at spaces into lines of about 60 characters. */
	static void wrap(List<String> out, String text)
	{
		if (text == null)
		{
			return;
		}
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (line.length() > 0 && line.length() + 1 + word.length() > TOOLTIP_WIDTH)
			{
				out.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		out.add(line.toString());
	}

	static Line plain(String text)
	{
		return new Line(text, null, Kind.PLAIN);
	}

	static Line note(String text)
	{
		return new Line(text, null, Kind.NOTE);
	}
}
