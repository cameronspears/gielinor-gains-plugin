package com.gielinorgains;

import com.google.common.io.Resources;
import java.nio.charset.StandardCharsets;
import net.runelite.http.api.RuneLiteAPI;

/**
 * Test data, read with the same Gson RuneLite injects into the plugin.
 *
 * - item-check-v1.json and golden-strings.json are copied byte for byte from the site
 *   (tests/fixtures/plugin/ in the gielinor-gains repo), which generates them from its real
 *   builder and formatters. They are the source of truth: refresh them, don't edit them.
 * - item-check-edge-cases.json is hand-written: broken and unusual entries the site never sends.
 */
final class Fixtures
{
	/** Times in both item check fixtures. */
	static final long SOURCE_AT = 1792929600000L;
	static final long STALE_AT = 1792930500000L;
	static final long MINUTE = 60_000L;
	static final long SIX_MINUTES_IN = SOURCE_AT + 6 * MINUTE;

	private Fixtures()
	{
	}

	static String text(String name) throws Exception
	{
		return Resources.toString(Resources.getResource(Fixtures.class, name), StandardCharsets.UTF_8);
	}

	static ItemCheck.Response itemCheck() throws Exception
	{
		return RuneLiteAPI.GSON.fromJson(text("item-check-v1.json"), ItemCheck.Response.class);
	}

	static ItemCheck.Response edgeCases() throws Exception
	{
		return RuneLiteAPI.GSON.fromJson(text("item-check-edge-cases.json"), ItemCheck.Response.class);
	}

	/** The site's fixture as answered 6 minutes after its prices, on a computer whose clock agrees. */
	static ItemCheck.Snapshot snapshot() throws Exception
	{
		return ItemCheck.Snapshot.of(itemCheck(), SIX_MINUTES_IN, SIX_MINUTES_IN);
	}

	static ItemCheck.Snapshot edgeSnapshot() throws Exception
	{
		return ItemCheck.Snapshot.of(edgeCases(), SIX_MINUTES_IN, SIX_MINUTES_IN);
	}
}
