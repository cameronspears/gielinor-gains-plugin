package com.gielinorgains;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import lombok.Value;
import net.runelite.api.gameval.ItemID;

/**
 * The free Market's flips as a list, worked out with no game or network access. Each row's
 * words are made here once per download, so the panel only paints them. Names come from the game,
 * never the server.
 */
final class MarketList
{
	static final String MEMBERS_HIDDEN = "Members' items are hidden on this free world.";
	static final String NO_FLIPS = "No flips are suggested right now. Check back soon.";
	static final String NO_FREE_FLIPS = "No free-to-play flips are suggested right now.";
	static final String LOG_IN = "Log in to see today's flips.";
	static final String OTHER_WORLD = "This world has its own prices, so the list is hidden here.";
	static final String CLICK_HINT = "Click to pick this flip. Use Item page to see its page.";

	/** What the game says about an item. */
	@Value
	static class Item
	{
		String name;
		boolean members;
	}

	@Value
	static class Row
	{
		int id;
		String name;
		String buy;
		String sell;
		String quantity;
		String profit;
		String chance;
		/** Null when the site couldn't read records and this item has none. */
		String record;
		/** Plain lines for the hover tooltip. */
		List<String> tooltip;
	}

	@Value
	static class View
	{
		/** One or two lines at the top: the price age, or why there are no prices. */
		List<BoxText.Line> status;
		/** The members note on a free world, else null. */
		String note;
		List<Row> rows;
		/** What to say when there are no rows, else null. */
		String empty;
	}

	/** A view with only a message, for when there's no list to show at all. */
	static View message(String text)
	{
		return new View(Collections.singletonList(BoxText.note(text)), null, Collections.emptyList(), null);
	}

	/** Every flip, highest expected profit first, as the Market sorts by default. */
	static View build(ItemCheck.Snapshot snapshot, boolean lastFailed, long clientNow, boolean membersWorld,
		IntFunction<Item> items)
	{
		List<BoxText.Line> header = header(snapshot, lastFailed, clientNow);
		if (BoxText.status(snapshot, lastFailed, clientNow) != null)
		{
			return new View(header, null, Collections.emptyList(), null);
		}

		List<Map.Entry<Integer, ItemCheck.Entry>> flips = new ArrayList<>();
		for (Map.Entry<Integer, ItemCheck.Entry> e : snapshot.items.entrySet())
		{
			if (e.getValue().flip != null)
			{
				flips.add(e);
			}
		}
		flips.sort(Comparator.comparingDouble((Map.Entry<Integer, ItemCheck.Entry> e) -> -e.getValue().flip.expectedProfit)
			.thenComparingInt(Map.Entry::getKey));

		List<Row> rows = new ArrayList<>();
		boolean hidden = false;
		for (Map.Entry<Integer, ItemCheck.Entry> e : flips)
		{
			Item item = items.apply(e.getKey());
			if (item == null)
			{
				continue;
			}
			if (item.isMembers() && !membersWorld)
			{
				hidden = true;
				continue;
			}
			rows.add(row(snapshot, e.getKey(), item.getName(), e.getValue()));
		}

		String empty = !rows.isEmpty() ? null : hidden ? NO_FREE_FLIPS : NO_FLIPS;
		return new View(header, hidden ? MEMBERS_HIDDEN : null, Collections.unmodifiableList(rows), empty);
	}

	/**
	 * The flip to suggest after a sale: the highest expected profit that isn't already in a GE slot
	 * (or just sold), and not a members' item on a free world. -1 when there's none.
	 */
	static int next(ItemCheck.Snapshot snapshot, boolean membersWorld, java.util.Set<Integer> skip, IntFunction<Item> items)
	{
		int best = -1;
		double bestProfit = Double.NEGATIVE_INFINITY;
		for (Map.Entry<Integer, ItemCheck.Entry> e : snapshot.items.entrySet())
		{
			ItemCheck.Flip flip = e.getValue().flip;
			if (flip == null || skip.contains(e.getKey()) || flip.expectedProfit < bestProfit
				|| (flip.expectedProfit == bestProfit && e.getKey() > best))
			{
				continue;
			}
			Item item = items.apply(e.getKey());
			if (item != null && (membersWorld || !item.isMembers()))
			{
				best = e.getKey();
				bestProfit = flip.expectedProfit;
			}
		}
		return best;
	}

	/** The lines at the top: the price age, or the box's words for why there are no prices. */
	static List<BoxText.Line> header(ItemCheck.Snapshot snapshot, boolean lastFailed, long clientNow)
	{
		BoxText.Line[] status = BoxText.status(snapshot, lastFailed, clientNow);
		if (status == null)
		{
			return Collections.singletonList(
				BoxText.note("Prices from " + BoxText.pricesAge(snapshot.serverNow(clientNow) - snapshot.sourceAt)));
		}
		List<BoxText.Line> lines = new ArrayList<>();
		Collections.addAll(lines, status);
		lines.removeIf(line -> line == null);
		return lines;
	}

	private static Row row(ItemCheck.Snapshot snapshot, int id, String name, ItemCheck.Entry entry)
	{
		ItemCheck.Flip flip = entry.flip;
		// The list is narrow, so totals drop the box's "Record:" label: "+1.1% after tax over 6 tests".
		String record = entry.record == null && !snapshot.recordsComplete ? null : BoxText.recordLine(entry.record, "");
		String tax = id == ItemID.OSRS_BOND ? "the conversion fee" : "tax";
		// Wrapped as the box wraps them, so a tooltip from the narrow sidebar stays narrow too.
		List<String> tooltip = new ArrayList<>();
		BoxText.wrap(tooltip, BoxText.CHANCE_TIP);
		BoxText.wrap(tooltip, BoxText.profitTip(tax));
		BoxText.wrap(tooltip, BoxText.recordTooltip(entry.record, tax));
		BoxText.wrap(tooltip, "Estimates, not promises. " + CLICK_HINT);
		return new Row(id, name, BoxText.whole(flip.buy), BoxText.whole(flip.sell), BoxText.whole(flip.qty),
			BoxText.formatNumber(flip.expectedProfit), BoxText.percent(flip.chance),
			record, Collections.unmodifiableList(tooltip));
	}
}
