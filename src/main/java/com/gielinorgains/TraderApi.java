package com.gielinorgains;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Collection;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The account half of the plugin: sign-in by code, the one sync request, the daily Trader steps
 * and Unlink (app/api/plugin-account/v1 on the website), plus the public price check for an item
 * that's left the plan feed. Replies arrive on OkHttp's threads; callers hop to their own.
 */
@Slf4j
@Singleton
class TraderApi
{
	/** The website. A developer can point a local build at a local site with -Dgielinorgains.site=... */
	static final String SITE = System.getProperty("gielinorgains.site", "https://www.gielinorgains.com");
	private static final HttpUrl BASE = HttpUrl.get(SITE + "/api/");
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	static final class Start
	{
		String code;
		String deviceCode;
		int expiresIn;
		int interval;
	}

	static final class Poll
	{
		String status;
		String token;
	}

	static final class Price
	{
		int id;
		boolean fresh;
		Long sell;
		/** The latest instant-buy and instant-sell trades, GP, and their unix seconds. */
		Long low;
		Long high;
		Long lowAt;
		Long highAt;
		/** The item page's tested offers for buying or selling for yourself; null unless both sides traded today. */
		Offers offers;
	}

	/** lib/items/selfTrade.ts: the tested buy and sell offers, the quieter side's activity, and their unix seconds. */
	static final class Offers
	{
		long buy;
		long sell;
		long sellAfterTax;
		/** "rare", "hourly" or "busy". */
		String activity;
		long at;
	}

	/** A reply: its HTTP status (0 when the website couldn't be reached) and its body, if read. */
	static final class Result<T>
	{
		final int status;
		final T body;

		Result(int status, T body)
		{
			this.status = status;
			this.body = body;
		}
	}

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	TraderApi(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	void start(Consumer<Result<Start>> done)
	{
		post("plugin-account/v1/link/start", null, "{}", Start.class, done);
	}

	void poll(String deviceCode, Consumer<Result<Poll>> done)
	{
		JsonObject body = new JsonObject();
		body.addProperty("deviceCode", deviceCode);
		post("plugin-account/v1/link/poll", null, body.toString(), Poll.class, done);
	}

	void sync(String key, String body, Consumer<Result<Trader.Reply>> done)
	{
		post("plugin-account/v1/sync", key, body, Trader.Reply.class, done);
	}

	/** Which Trader steps the player reached today: yes/no facts, as the website records them. */
	void activity(String key, Collection<String> steps)
	{
		JsonObject body = new JsonObject();
		JsonArray list = new JsonArray();
		steps.forEach(list::add);
		body.add("steps", list);
		post("trader/activity", key, body.toString(), JsonObject.class, r -> { });
	}

	/** Ends this RuneLite's key on the website. Signing out here happens either way. */
	void unlink(String key)
	{
		post("plugin-account/v1/unlink", key, "{}", JsonObject.class, r -> { });
	}

	/** Make and Sell for Trader's overflow: whether this player is routed, and the swap they took this window. */
	void makeSell(String key, Consumer<Result<MakeSell.Status>> done)
	{
		Request request = new Request.Builder().url(BASE.resolve("trader/make-sell")).get()
			.header("Authorization", "Bearer " + key).build();
		call(request, MakeSell.Status.class, done);
	}

	/** Takes a swap's room for this window; the website sizes it again and answers the claim. */
	void takeSwap(String key, String id, long units, Consumer<Result<MakeSell.Taken>> done)
	{
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("units", units);
		post("trader/make-sell", key, body.toString(), MakeSell.Taken.class, done);
	}

	/** Gives this window's swap back. */
	void giveBackSwap(String key, Consumer<Result<MakeSell.Taken>> done)
	{
		JsonObject body = new JsonObject();
		body.add("id", JsonNull.INSTANCE);
		post("trader/make-sell", key, body.toString(), MakeSell.Taken.class, done);
	}

	void price(int itemId, Consumer<Result<Price>> done)
	{
		Request request = new Request.Builder().url(BASE.resolve("plugin/v1/price/" + itemId)).get().build();
		call(request, Price.class, done);
	}

	private <T> void post(String path, String key, String body, Class<T> type, Consumer<Result<T>> done)
	{
		Request.Builder request = new Request.Builder().url(BASE.resolve(path)).post(RequestBody.create(JSON, body));
		if (key != null)
		{
			request.header("Authorization", "Bearer " + key);
		}
		call(request.build(), type, done);
	}

	private <T> void call(Request request, Class<T> type, Consumer<Result<T>> done)
	{
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				done.accept(new Result<>(0, null));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				T read = null;
				try (ResponseBody body = response.body())
				{
					if (body != null)
					{
						read = gson.fromJson(body.charStream(), type);
					}
				}
				catch (RuntimeException e)
				{
					log.debug("Gielinor Gains reply couldn't be read: {}", e.toString());
				}
				done.accept(new Result<>(response.code(), read));
			}
		});
	}
}
