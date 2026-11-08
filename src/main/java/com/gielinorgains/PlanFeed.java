package com.gielinorgains;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Trader's picks, in Trader's order, from the site's public plan feed: the same for everyone,
 * with nothing about the player in the request. Fetched at most every 5 minutes while Trader has
 * a plan or its tab is open.
 */
@Slf4j
@Singleton
class PlanFeed
{
	static final HttpUrl URL = HttpUrl.get(TraderApi.SITE + "/api/plugin/v1/plan-feed");
	private static final long FAILURE_INTERVAL_MS = 60_000L;

	/** One pick, as lib/plugin/planFeed.ts sends it. */
	static final class Pick
	{
		int id;
		String name;
		boolean members;
		int limit;
		long buy;
		long sell;
		long qty;
		long qtyLarge;
		long qtyLimit;
		long minQty;
		long maxQty;
		String tier;
		Boolean big;
		long reserveEach;
		double expectedEach;
		double chance;
		Integer proven;
		long thin;
		Record record;
		long low;
		long high;
		long lowAt;
		long highAt;
		Boolean taxFree;

		boolean isPreferred()
		{
			return "p".equals(tier);
		}

		boolean isBig()
		{
			return Boolean.TRUE.equals(big);
		}

		boolean isTaxFree()
		{
			return Boolean.TRUE.equals(taxFree);
		}
	}

	static final class Record
	{
		double avgReturn;
		int flips;
	}

	/**
	 * Trader's shared limit, sent only while it's on: the units of each listed pick other Trader
	 * players have planned this 2-hour window. An item takes {@code players} times the most one
	 * player can take. Not used from {@code ends} (unix ms, the server's clock).
	 */
	static final class Room
	{
		long window;
		long ends;
		int players;
		Map<String, Long> reserved;
	}

	/**
	 * Make and Sell's room when Trader's picks are full, sent only while the website has it on:
	 * swaps with room left this 4-hour window, best first, each sized to that room. Not used from
	 * {@code ends} (unix ms, the server's clock).
	 */
	static final class MakeSell
	{
		String mode;
		long window;
		long ends;
		List<Swap> swaps;
	}

	/** One buy or sale of a swap, per conversion. */
	static final class Leg
	{
		int id;
		String name;
		double qty;
		long price;
		/** Bought at once (a rune), so it takes no room. */
		Boolean instant;

		boolean isInstant()
		{
			return Boolean.TRUE.equals(instant);
		}
	}

	/** One Make and Sell swap: buy on the GE, change it in the game, sell. */
	static final class Swap
	{
		String id;
		/** "Alch Rune platebody". */
		String title;
		/** The in-game step, in the website's words: "Cast High Level Alchemy on each one." */
		String step;
		String where;
		String wikiUrl;
		boolean members;
		List<String> needs;
		List<Leg> buy;
		List<Leg> sell;
		/** Coins received per conversion, from High Alchemy or a shop. */
		long coins;
		/** Profit per conversion after tax and fees, at today's prices. */
		double each;
		/** Cost per conversion. */
		double cost;
		/** Conversions with room left, and seconds of play for them. */
		long units;
		double play;
	}

	static final class Feed
	{
		int v;
		Long computedAt;
		/** When the prices were traded (unix ms), as the Market list and item box measure it. */
		Long sourceAt;
		/** Until when (unix ms) these prices may be acted on; null when they're too old. */
		Long staleAt;
		List<Pick> picks;
		/** The most of the plan's GP one automatic pick may use (0.2); null from a site without the cap. */
		Double pickShare;
		/** Null while the shared limit is off. */
		Room room;
		/** Null while Make and Sell's crowd routing is off. */
		MakeSell makeSell;
		/** The server's clock when it answered, and when the answer arrived on this computer's. */
		transient long serverDate;
		transient long receivedAt;

		/** How old the prices are, on the server's clock so this computer's clock doesn't matter. */
		Long ageMs(long now)
		{
			return sourceAt == null || receivedAt == 0 ? null : serverDate - sourceAt + (now - receivedAt);
		}

		/** Whether the prices may be acted on, judged on the server's clock like {@link #ageMs}. */
		boolean fresh(long now)
		{
			return staleAt != null && serverNow(now) < staleAt && picks != null;
		}

		/** This computer's {@code now} on the server's clock. */
		long serverNow(long now)
		{
			return receivedAt == 0 ? now : serverDate + (now - receivedAt);
		}
	}

	private final OkHttpClient http;
	private final Gson gson;
	private volatile Feed feed;
	private volatile boolean lastFailed;
	// Guarded by this.
	private Call inFlight;
	private long nextFetchAt;

	@Inject
	PlanFeed(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	Feed feed()
	{
		return feed;
	}

	boolean lastFailed()
	{
		return lastFailed;
	}

	synchronized void refreshIfDue(long now)
	{
		if (inFlight != null || now < nextFetchAt)
		{
			return;
		}
		Call call = http.newCall(new Request.Builder().url(URL).get().build());
		inFlight = call;
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				finish(call, null);
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				Feed read = null;
				try (ResponseBody body = response.body())
				{
					if (response.isSuccessful() && body != null)
					{
						read = gson.fromJson(body.charStream(), Feed.class);
						if (read != null)
						{
							read.receivedAt = response.receivedResponseAtMillis();
							java.util.Date date = response.headers().getDate("Date");
							read.serverDate = date == null ? read.receivedAt : date.getTime();
						}
					}
				}
				catch (RuntimeException e)
				{
					log.debug("Gielinor Gains plan feed couldn't be read: {}", e.toString());
				}
				finish(call, read != null && read.v == 1 && read.picks != null ? read : null);
			}
		});
	}

	synchronized void reset()
	{
		if (inFlight != null)
		{
			inFlight.cancel();
			inFlight = null;
		}
		feed = null;
		nextFetchAt = 0;
	}

	private synchronized void finish(Call call, Feed read)
	{
		if (call != inFlight)
		{
			return;
		}
		inFlight = null;
		lastFailed = read == null;
		if (read != null)
		{
			feed = read;
		}
		long now = System.currentTimeMillis();
		nextFetchAt = read != null ? ItemCheckFeed.nextFetch(now, read.sourceAt, read.serverDate + (now - read.receivedAt))
			: now + FAILURE_INTERVAL_MS;
	}
}
