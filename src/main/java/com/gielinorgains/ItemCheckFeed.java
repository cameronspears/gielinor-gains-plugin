package com.gielinorgains;

import com.google.common.primitives.Longs;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.Date;
import java.util.function.LongSupplier;
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
 * Downloads the item check list: the plugin's only network code. One GET with nothing about the
 * player in it. The client thread calls {@link #refreshIfDue()} and reads the volatile snapshot;
 * OkHttp's own thread downloads and parses.
 */
@Slf4j
@Singleton
class ItemCheckFeed
{
	static final HttpUrl URL = HttpUrl.get(TraderApi.SITE + "/api/plugin/v1/item-check");

	/** The longest wait between fetches. */
	static final long SUCCESS_INTERVAL_MS = 5 * 60_000L;
	/**
	 * New prices come in 5-minute sets: the set from a price time is served a little after the next
	 * set's 5 minutes end. So the next prices are due this long after the ones we have.
	 */
	static final long NEXT_PRICES_AFTER_MS = 10 * 60_000L + 30_000L;
	/** While the next prices are late, or there are none fresh. The CDN answers most of these. */
	static final long LATE_POLL_MS = 60_000L;

	/** Waits after the 1st, 2nd, ... failure in a row. The last one repeats. */
	static final long[] BACKOFF_MS = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L};

	private final OkHttpClient http;
	private final Gson gson;
	private final LongSupplier clock;

	private volatile ItemCheck.Snapshot snapshot;
	private volatile boolean lastFailed;

	// Guarded by this.
	private Call inFlight;
	private int generation;
	private int failures;
	private long nextFetchAt;

	@Inject
	ItemCheckFeed(OkHttpClient http, Gson gson)
	{
		this(http, gson, System::currentTimeMillis);
	}

	ItemCheckFeed(OkHttpClient http, Gson gson, LongSupplier clock)
	{
		this.http = http;
		this.gson = gson;
		this.clock = clock;
	}

	ItemCheck.Snapshot snapshot()
	{
		return snapshot;
	}

	/** Whether the latest download failed. Old prices are kept until they go stale. */
	boolean lastFailed()
	{
		return lastFailed;
	}

	/** Starts a download if none is running and the last one is old enough. Never blocks. */
	synchronized void refreshIfDue()
	{
		if (inFlight != null || clock.getAsLong() < nextFetchAt)
		{
			return;
		}

		final int callGeneration = generation;
		final Call call = http.newCall(new Request.Builder().url(URL).get().build());
		inFlight = call;
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Gielinor Gains item check failed: {}", e.toString());
				finish(callGeneration, null, 0);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				finish(callGeneration, read(response), retryAfterMs(response));
			}
		});
	}

	/** Forgets everything and cancels a running download, so a late answer is ignored. */
	synchronized void reset()
	{
		generation++;
		if (inFlight != null)
		{
			inFlight.cancel();
			inFlight = null;
		}
		snapshot = null;
		lastFailed = false;
		failures = 0;
		nextFetchAt = 0;
	}

	private ItemCheck.Snapshot read(Response response)
	{
		try (ResponseBody body = response.body())
		{
			if (!response.isSuccessful() || body == null)
			{
				log.debug("Gielinor Gains item check returned HTTP {}", response.code());
				return null;
			}
			ItemCheck.Response parsed = gson.fromJson(body.charStream(), ItemCheck.Response.class);
			long receivedAt = response.receivedResponseAtMillis();
			Date date = response.headers().getDate("Date");
			ItemCheck.Snapshot read = ItemCheck.Snapshot.of(parsed, date == null ? receivedAt : date.getTime(), receivedAt);
			if (read == null)
			{
				log.debug("Gielinor Gains item check had an unexpected shape");
			}
			return read;
		}
		catch (RuntimeException e)
		{
			// Gson's parse errors are runtime exceptions.
			log.debug("Gielinor Gains item check couldn't be read: {}", e.toString());
			return null;
		}
	}

	/** The server's Retry-After in seconds, as a wait of at most 5 minutes; 0 when it gave none. */
	static long retryAfterMs(Response response)
	{
		String header = response.header("Retry-After");
		Long seconds = header == null ? null : Longs.tryParse(header.trim());
		return seconds == null ? 0 : Math.min(Math.max(0, seconds), 300) * 1_000L;
	}

	/**
	 * When to fetch again: as the next prices are due, each minute while they're late, and never more
	 * than 5 minutes apart. {@code serverDate} is the server's clock at {@code now}.
	 */
	static long nextFetch(long now, Long sourceAt, long serverDate)
	{
		if (sourceAt == null)
		{
			return now + LATE_POLL_MS;
		}
		long due = now + sourceAt + NEXT_PRICES_AFTER_MS - serverDate;
		return Math.min(now + SUCCESS_INTERVAL_MS, Math.max(now + LATE_POLL_MS, due));
	}

	private synchronized void finish(int callGeneration, ItemCheck.Snapshot read, long retryAfterMs)
	{
		if (callGeneration != generation)
		{
			return;
		}
		inFlight = null;
		long now = clock.getAsLong();
		if (read != null)
		{
			snapshot = read;
			lastFailed = false;
			failures = 0;
			nextFetchAt = nextFetch(now, read.sourceAt, read.serverDate);
		}
		else
		{
			lastFailed = true;
			// Never sooner than the server asked.
			nextFetchAt = now + Math.max(BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)], retryAfterMs);
			failures++;
		}
	}
}
