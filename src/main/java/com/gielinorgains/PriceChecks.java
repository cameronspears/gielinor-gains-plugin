package com.gielinorgains;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * The latest GE trades of items with no flip, for the item box: so a player buying something for
 * themselves still sees what to offer. One request per item a minute at most, which the CDN answers
 * (the site caches each item's price check for a minute). Any thread.
 */
@Singleton
class PriceChecks
{
	static final long KEEP_MS = 60_000;
	private static final int MAX_ITEMS = 200;

	private final TraderApi api;
	private final Map<Integer, Held> held = new ConcurrentHashMap<>();

	private static final class Held
	{
		final TraderApi.Price price;
		final long at;
		final boolean asking;

		Held(TraderApi.Price price, long at, boolean asking)
		{
			this.price = price;
			this.at = at;
			this.asking = asking;
		}
	}

	@Inject
	PriceChecks(TraderApi api)
	{
		this.api = api;
	}

	/** The item's latest price check, or null until one arrives. Asks again once it's a minute old. */
	TraderApi.Price get(int itemId, long now)
	{
		Held last = held.get(itemId);
		if (last == null || !last.asking && now - last.at >= KEEP_MS)
		{
			if (held.size() >= MAX_ITEMS)
			{
				held.clear();
			}
			TraderApi.Price kept = last == null ? null : last.price;
			held.put(itemId, new Held(kept, now, true));
			api.price(itemId, result ->
			{
				// A failed read keeps the last price; an item the site doesn't price has none.
				TraderApi.Price next = result.status == 200 ? result.body : result.status == 404 ? null : kept;
				held.put(itemId, new Held(next, System.currentTimeMillis(), false));
			});
		}
		return last == null ? null : last.price;
	}
}
