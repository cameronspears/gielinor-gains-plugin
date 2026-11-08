package com.gielinorgains;

import com.google.common.primitives.Ints;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The site's item check list (GET /api/plugin/v1/item-check), as Gson reads it, and the checked
 * {@link Snapshot} the box is drawn from. Fields the plugin doesn't know are ignored.
 */
final class ItemCheck
{
	/** The plugin's own version. The site raises {@code minPlugin} above it once this one is too old. */
	static final int PLUGIN_VERSION = 1;

	/** The list never has this many items; a bigger one is refused rather than held in memory. */
	static final int MAX_ITEMS = 20_000;

	/** Most recent tests a record line counts. */
	static final int MAX_CHECKS = 10;

	static final class Response
	{
		Integer v;
		Integer minPlugin;
		Boolean fresh;
		Long sourceAt;
		Long staleAt;
		/** False when the site couldn't read track records: a missing record is then unknown, not empty. */
		Boolean recordsComplete;
		Map<String, Entry> items;
	}

	static final class Entry
	{
		Flip flip;
		String skip;
		Record record;
		/** The price just jumped on heavy trading: a note on buys the player picks themselves. */
		Pump pump;
	}

	/** lib/items/pumpWarning.ts: the latest day's price over the price before the jump, less 1, and the jump's UTC day. */
	static final class Pump
	{
		Double rise;
		Long since;
	}

	/** The free Market's flip for an item: offer prices, quantity, expected profit and Chance. */
	static final class Flip
	{
		Double buy;
		Double sell;
		Double qty;
		Double expectedProfit;
		Double profitIfAllSells;
		Double chance;

		boolean isValid()
		{
			return positive(buy) && positive(sell) && positive(qty)
				&& finite(expectedProfit) && finite(profitIfAllSells)
				&& finite(chance) && chance >= 0 && chance <= 1;
		}
	}

	/** An item's track record: its weighted return, how many tests, and the latest tests (1 made money). */
	static final class Record
	{
		Double avgReturn;
		Integer flips;
		String checks;
	}

	/** One accepted response. Built on OkHttp's thread, then only read. */
	static final class Snapshot
	{
		final int minPlugin;
		final boolean fresh;
		/** When the prices are from, on the server's clock; null when they aren't up to date. */
		final Long sourceAt;
		/** When the server would call these prices stale, on its clock. */
		final Long staleAt;
		/** The server's clock when it answered (its Date header). */
		final long serverDate;
		/** This computer's clock when the answer arrived. */
		final long receivedAt;
		final boolean recordsComplete;
		final Map<Integer, Entry> items;

		private Snapshot(Response response, Map<Integer, Entry> items, long serverDate, long receivedAt)
		{
			this.minPlugin = response.minPlugin == null ? 1 : response.minPlugin;
			this.fresh = Boolean.TRUE.equals(response.fresh);
			this.sourceAt = response.sourceAt;
			this.staleAt = response.staleAt;
			this.serverDate = serverDate;
			this.receivedAt = receivedAt;
			this.recordsComplete = !Boolean.FALSE.equals(response.recordsComplete);
			this.items = Collections.unmodifiableMap(items);
		}

		/**
		 * The server's clock now. Measuring from its Date header, and only timing the gap since on
		 * this computer, keeps a player's wrong clock out of the age.
		 */
		long serverNow(long clientNow)
		{
			return serverDate + Math.max(0, clientNow - receivedAt);
		}

		/** Checks a response and keeps the parts the box can use, or returns null if it can't be used. */
		static Snapshot of(Response response, long serverDate, long receivedAt)
		{
			if (response == null || response.v == null || response.v != 1)
			{
				return null;
			}
			Map<String, Entry> raw = response.items == null ? Collections.emptyMap() : response.items;
			if (raw.size() > MAX_ITEMS)
			{
				return null;
			}

			Map<Integer, Entry> items = new HashMap<>(raw.size() * 2);
			for (Map.Entry<String, Entry> e : raw.entrySet())
			{
				Integer id = Ints.tryParse(e.getKey());
				Entry entry = e.getValue();
				if (id != null && id > 0 && entry != null)
				{
					items.put(id, clean(entry, Boolean.TRUE.equals(response.fresh)));
				}
			}
			return new Snapshot(response, items, serverDate, receivedAt);
		}

		private static Entry clean(Entry entry, boolean fresh)
		{
			Entry out = new Entry();
			out.flip = fresh && entry.flip != null && entry.flip.isValid() ? entry.flip : null;
			out.skip = fresh ? entry.skip : null;
			// A jump doesn't depend on fresh prices: the list is built from whole days and kept 2 days.
			out.pump = entry.pump != null && positive(entry.pump.rise) && entry.pump.since != null ? entry.pump : null;
			Record record = entry.record;
			if (record != null && finite(record.avgReturn) && record.flips != null && record.flips >= 0)
			{
				out.record = new Record();
				out.record.avgReturn = record.avgReturn;
				out.record.flips = record.flips;
				out.record.checks = cleanChecks(record.checks);
			}
			return out;
		}

		/** The last ten tests at most; anything but 0s and 1s means there's no usable history. */
		private static String cleanChecks(String checks)
		{
			if (checks == null || !checks.matches("[01]*"))
			{
				return "";
			}
			return checks.length() > MAX_CHECKS ? checks.substring(checks.length() - MAX_CHECKS) : checks;
		}
	}

	private static boolean finite(Double value)
	{
		return value != null && !value.isNaN() && !value.isInfinite();
	}

	private static boolean positive(Double value)
	{
		return finite(value) && value > 0;
	}
}
