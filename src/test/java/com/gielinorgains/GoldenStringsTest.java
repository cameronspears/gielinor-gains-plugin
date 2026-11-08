package com.gielinorgains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.runelite.http.api.RuneLiteAPI;
import org.junit.Test;

/** The plugin's words and numbers against golden-strings.json, which the site makes from its own code. */
public class GoldenStringsTest
{
	@Test
	public void matchesTheSite() throws Exception
	{
		check();
	}

	@Test
	public void matchesTheSiteForAGermanPlayer() throws Exception
	{
		Locale before = Locale.getDefault();
		Locale.setDefault(Locale.GERMANY);
		try
		{
			check();
		}
		finally
		{
			Locale.setDefault(before);
		}
	}

	@Test
	public void reasonsAreTheSitesWordForWord() throws Exception
	{
		JsonObject golden = golden();
		Map<String, String> reasons = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : golden.getAsJsonObject("reasons").entrySet())
		{
			reasons.put(e.getKey(), e.getValue().getAsString());
		}
		assertEquals(reasons, BoxText.REASONS);
		assertEquals(golden.get("reasonFallback").getAsString(), BoxText.DEFAULT_REASON);
	}

	@Test
	public void sharedLimitNoteIsTheSitesWordForWord() throws Exception
	{
		// 14:00 UTC, which the website shows as "2:00 PM" in the player's own time.
		long opensAt = java.time.Instant.parse("2026-11-04T14:00:00Z").toEpochMilli();
		for (JsonElement e : golden().getAsJsonArray("sharedLimitNotices"))
		{
			JsonArray c = e.getAsJsonArray();
			assertEquals(c.get(1).getAsString().replace("{time}", "2:00 PM"),
				TraderRules.roomNotice(c.get(0).getAsInt(), opensAt, java.time.ZoneOffset.UTC));
		}
		assertEquals(2, golden().getAsJsonArray("sharedLimitNotices").size());
	}

	@Test
	public void priceJumpNoteIsTheSitesWordForWord() throws Exception
	{
		JsonObject pump = golden().getAsJsonObject("pump");
		assertEquals(pump.get("title").getAsString(), BoxText.PUMP_TITLE);
		assertEquals(pump.get("body").getAsString(), BoxText.PUMP_BODY);
		for (JsonElement e : pump.getAsJsonArray("rises"))
		{
			JsonArray c = e.getAsJsonArray();
			assertEquals(c.get(1).getAsString(), BoxText.pumpRise(c.get(0).getAsDouble()));
		}
	}

	@Test
	public void makeAndSellOfferIsTheSitesWordForWord() throws Exception
	{
		JsonObject makeSell = golden().getAsJsonObject("makeSell");
		assertEquals(makeSell.get("heading").getAsString(), MakeSell.HEADING);
		assertEquals(makeSell.get("summary").getAsString(), MakeSell.SUMMARY);
		assertEquals(makeSell.get("explainer").getAsString(), MakeSell.EXPLAINER);
		assertEquals(makeSell.get("planned").getAsString(), MakeSell.PLANNED);
		assertEquals(makeSell.get("empty").getAsString().replace("{time}", "6:00 PM"), MakeSell.empty("6:00 PM"));
	}

	private static JsonObject golden() throws Exception
	{
		JsonObject golden = RuneLiteAPI.GSON.fromJson(Fixtures.text("golden-strings.json"), JsonObject.class);
		assertEquals(1, golden.get("v").getAsInt());
		return golden;
	}

	private static void check() throws Exception
	{
		JsonObject golden = golden();
		int checked = 0;
		for (JsonElement e : golden.getAsJsonArray("numbers"))
		{
			JsonArray c = e.getAsJsonArray();
			assertEquals("formatNumber(" + c.get(0) + ")", c.get(1).getAsString(), BoxText.formatNumber(c.get(0).getAsDouble()));
			checked++;
		}
		for (JsonElement e : golden.getAsJsonArray("chances"))
		{
			JsonArray c = e.getAsJsonArray();
			assertEquals("chance(" + c.get(0) + ")", c.get(1).getAsString(), BoxText.percent(c.get(0).getAsDouble()));
			checked++;
		}
		for (JsonElement e : golden.getAsJsonArray("signedPercents"))
		{
			JsonArray c = e.getAsJsonArray();
			assertEquals("signedPercent(" + c.get(0) + ")", c.get(1).getAsString(),
				BoxText.signedPercent(c.get(0).getAsDouble()));
			checked++;
		}
		for (JsonElement e : golden.getAsJsonArray("trackRecords"))
		{
			JsonArray c = e.getAsJsonArray();
			JsonObject in = c.get(0).getAsJsonObject();
			ItemCheck.Record record = new ItemCheck.Record();
			record.avgReturn = in.get("avgReturn").getAsDouble();
			record.flips = in.get("flips").getAsInt();
			record.checks = "";
			// The box puts its "Record:" label before the site's words.
			assertEquals("Record: " + c.get(1).getAsString(), BoxText.recordLine(record));
			// The Market list shows the site's words alone.
			assertEquals(c.get(1).getAsString(), BoxText.recordLine(record, ""));
			checked++;
		}
		for (JsonElement e : golden.getAsJsonArray("recentChecks"))
		{
			JsonArray c = e.getAsJsonArray();
			JsonObject in = c.get(0).getAsJsonObject();
			int madeMoney = in.get("madeMoney").getAsInt();
			int total = in.get("total").getAsInt();
			ItemCheck.Record record = new ItemCheck.Record();
			record.avgReturn = 0.0;
			record.flips = total;
			record.checks = repeat("0", total - madeMoney) + repeat("1", madeMoney);
			assertEquals(c.get(1).getAsString(), BoxText.recordLine(record));
			checked++;
		}
		assertTrue(checked >= 50);
	}

	private static String repeat(String s, int times)
	{
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < times; i++)
		{
			out.append(s);
		}
		return out.toString();
	}
}
