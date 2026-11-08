package com.gielinorgains;

import static com.gielinorgains.Fixtures.MINUTE;
import static com.gielinorgains.Fixtures.SOURCE_AT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Dispatcher;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;

public class ItemCheckFeedTest
{
	/** This computer's clock. */
	private final AtomicLong clock = new AtomicLong(SOURCE_AT + 6 * MINUTE);
	/** The server's clock minus ours; players' clocks can be minutes out. */
	private long serverOffset;
	private final AtomicInteger requests = new AtomicInteger();
	private int status = 200;
	private String retryAfter;
	private String body;

	@Before
	public void setUp() throws Exception
	{
		body = Fixtures.text("item-check-v1.json");
	}

	private ItemCheckFeed feed(ExecutorService executor)
	{
		OkHttpClient http = new OkHttpClient.Builder()
			.dispatcher(new Dispatcher(executor))
			.addInterceptor(chain ->
			{
				requests.incrementAndGet();
				assertEquals(ItemCheckFeed.URL, chain.request().url());
				assertNull("never a query string: the site answers 400", chain.request().url().query());
				assertNull("OkHttp adds gzip itself", chain.request().header("Accept-Encoding"));
				long now = clock.get();
				return new Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(status)
					.message("x")
					// Shaped like a CDN hit: Date is when the CDN answered, Age is ignored.
					.headers(headers(now))
					.body(ResponseBody.create(MediaType.get("application/json"), body))
					.sentRequestAtMillis(now)
					.receivedResponseAtMillis(now)
					.build();
			})
			.build();
		return new ItemCheckFeed(http, RuneLiteAPI.GSON, clock::get);
	}

	private Headers headers(long now)
	{
		Headers.Builder headers = new Headers.Builder().add("Date", new Date(now + serverOffset)).add("Age", "50");
		if (retryAfter != null)
		{
			headers.add("Retry-After", retryAfter);
		}
		return headers.build();
	}

	private ItemCheckFeed feed()
	{
		return feed(MoreExecutors.newDirectExecutorService());
	}

	@Test
	public void fetchesWhenTheNextPricesAreDueThenEachMinuteWhileLate()
	{
		ItemCheckFeed feed = feed();
		feed.refreshIfDue();
		assertNotNull(feed.snapshot());
		assertFalse(feed.lastFailed());
		assertEquals(1, requests.get());

		// The prices are 6 minutes old, so the next set is due in 4.5 minutes.
		clock.addAndGet(270_000 - 1);
		feed.refreshIfDue();
		assertEquals(1, requests.get());
		clock.addAndGet(1);
		feed.refreshIfDue();
		assertEquals(2, requests.get());

		// The same prices again: they're late, so it checks each minute.
		clock.addAndGet(ItemCheckFeed.LATE_POLL_MS - 1);
		feed.refreshIfDue();
		assertEquals(2, requests.get());
		clock.addAndGet(1);
		feed.refreshIfDue();
		assertEquals(3, requests.get());
	}

	@Test
	public void fetchesAsTheNextPricesAreDue()
	{
		long server = 1_800_000_000_000L;
		long now = server + 3_600_000; // this computer's clock an hour fast
		// Prices 6 minutes old: the next set is due in 4.5 minutes.
		assertEquals(now + 270_000, ItemCheckFeed.nextFetch(now, server - 360_000, server));
		// Just in: still never more than 5 minutes apart.
		assertEquals(now + ItemCheckFeed.SUCCESS_INTERVAL_MS, ItemCheckFeed.nextFetch(now, server, server));
		// Late, or no fresh prices: each minute.
		assertEquals(now + 60_000, ItemCheckFeed.nextFetch(now, server - 720_000, server));
		assertEquals(now + 60_000, ItemCheckFeed.nextFetch(now, null, server));
	}

	@Test
	public void backsOffAfterFailuresAndKeepsOldPrices()
	{
		ItemCheckFeed feed = feed();
		feed.refreshIfDue();
		ItemCheck.Snapshot good = feed.snapshot();
		clock.addAndGet(ItemCheckFeed.SUCCESS_INTERVAL_MS);

		status = 503;
		long[] waits = {15_000, 30_000, 60_000, 120_000, 240_000, 300_000, 300_000};
		int expected = 1;
		for (long wait : waits)
		{
			feed.refreshIfDue();
			assertEquals(++expected, requests.get());
			assertTrue(feed.lastFailed());
			assertSame(good, feed.snapshot());

			clock.addAndGet(wait - 1);
			feed.refreshIfDue();
			assertEquals("waits " + wait, expected, requests.get());
			clock.addAndGet(1);
		}

		status = 200;
		feed.refreshIfDue();
		assertFalse(feed.lastFailed());
		clock.addAndGet(15_000);
		feed.refreshIfDue();
		assertEquals("back to every 5 minutes", expected + 1, requests.get());
	}

	@Test
	public void neverRetriesSoonerThanTheServerAsks()
	{
		ItemCheckFeed feed = feed();
		feed.refreshIfDue();
		ItemCheck.Snapshot good = feed.snapshot();
		clock.addAndGet(ItemCheckFeed.SUCCESS_INTERVAL_MS);

		// The site's 503 when it has neither fresh prices nor records.
		status = 503;
		retryAfter = "60";
		feed.refreshIfDue();
		assertSame("old prices are kept", good, feed.snapshot());
		clock.addAndGet(59_999);
		feed.refreshIfDue();
		assertEquals(2, requests.get());
		clock.addAndGet(1);
		feed.refreshIfDue();
		assertEquals(3, requests.get());

		retryAfter = "86400";
		clock.addAndGet(ItemCheckFeed.SUCCESS_INTERVAL_MS);
		feed.refreshIfDue();
		clock.addAndGet(300_000);
		feed.refreshIfDue();
		assertEquals("waits at most 5 minutes", 5, requests.get());
	}

	@Test
	public void unreadableAnswersCountAsFailures()
	{
		ItemCheckFeed feed = feed();
		body = "{not json";
		feed.refreshIfDue();
		assertTrue(feed.lastFailed());
		assertNull(feed.snapshot());

		clock.addAndGet(15_000);
		body = "{\"v\":2,\"items\":{}}";
		feed.refreshIfDue();
		assertTrue(feed.lastFailed());
		assertEquals(2, requests.get());
	}

	@Test
	public void onlyOneDownloadAtATime()
	{
		QueueExecutor executor = new QueueExecutor();
		ItemCheckFeed feed = feed(executor);
		feed.refreshIfDue();
		feed.refreshIfDue();
		assertEquals(1, executor.tasks.size());
		executor.runAll();
		assertEquals(1, requests.get());
		assertNotNull(feed.snapshot());
	}

	@Test
	public void ignoresAnswersThatArriveAfterShutDown()
	{
		QueueExecutor executor = new QueueExecutor();
		ItemCheckFeed feed = feed(executor);
		feed.refreshIfDue();
		feed.reset();
		executor.runAll();
		assertNull(feed.snapshot());
		assertFalse(feed.lastFailed());

		// Starting again fetches straight away.
		feed.refreshIfDue();
		executor.runAll();
		assertNotNull(feed.snapshot());
	}

	@Test
	public void ageUsesTheServerClockWhateverTheComputerSays()
	{
		for (long offset : new long[]{0, 15 * MINUTE, -15 * MINUTE})
		{
			// The server answers 6 minutes after its prices; our clock is `offset` behind or ahead.
			serverOffset = offset;
			clock.set(SOURCE_AT + 6 * MINUTE - offset);
			ItemCheckFeed feed = feed();
			feed.refreshIfDue();
			assertEquals("offset " + offset, "Prices from 6 minutes ago", line(feed, 9));

			clock.addAndGet(2 * MINUTE);
			assertEquals("offset " + offset, "Prices from 8 minutes ago", line(feed, 9));
			clock.addAndGet(8 * MINUTE);
			assertEquals("offset " + offset, "Our prices are 16 minutes old.", line(feed, 1));
		}
	}

	private String line(ItemCheckFeed feed, int index)
	{
		return BoxText.forItem(feed.snapshot(), feed.lastFailed(), 2, false, clock.get()).getLines().get(index).getLeft();
	}

	/** Holds OkHttp's work until the test runs it, so a download can be left running. */
	private static class QueueExecutor extends AbstractExecutorService
	{
		final Queue<Runnable> tasks = new ArrayDeque<>();

		void runAll()
		{
			Runnable task;
			while ((task = tasks.poll()) != null)
			{
				task.run();
			}
		}

		@Override
		public void execute(Runnable command)
		{
			tasks.add(command);
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return null;
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}
	}
}
