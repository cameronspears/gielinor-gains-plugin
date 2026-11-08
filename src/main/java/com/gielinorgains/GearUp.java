package com.gielinorgains;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;
import net.runelite.api.Skill;

/**
 * Gear Up on the website, fed from the game: which of Gear Up's items the player owns, and the link
 * that opens Gear Up with them. Pure, so the matching can be tested without a game.
 *
 * <p>Nothing here goes to a server. The link carries the player's name, coins and catalog ids after
 * the #, which browsers keep to themselves; the website reads it in the page and stores nothing.
 * The website's lib/gearUp/runeliteGear.ts reads the same format.
 */
final class GearUp
{
	static final String PAGE = TraderApi.SITE + "/gear-up";

	/** The website's item list, from /api/gear-up/catalog. */
	static final class Catalog
	{
		int v;
		List<Entry> items;
		/** The quests and unlocks Gear Up asks about, by the website's names. Null from an older site. */
		List<String> done;
	}

	static final class Entry
	{
		/** The game id Gear Up keys the item by: the version you wear. */
		int id;
		String n;
		/** How many of a stack count as owning it (ammo); null for one. */
		Integer min;
	}

	/** The account the game shows: levels in {@link #SKILLS} order, type, and finished quests and unlocks. */
	static final class Account
	{
		final int[] levels;
		final String type;
		final List<String> done;

		Account(int[] levels, String type, List<String> done)
		{
			this.levels = levels;
			this.type = type;
			this.done = done;
		}
	}

	/** The skills Gear Up reads, in the website's order (lib/gearUp/account.ts ACCOUNT_SKILLS). */
	static final Skill[] SKILLS = {
		Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC, Skill.HITPOINTS, Skill.PRAYER,
		Skill.SLAYER, Skill.AGILITY,
	};

	/**
	 * Variants that need more than wearing to use (a bow that needs crystal shards, a cape that was
	 * broken in PvP, fully worn Barrows) aren't counted: Gear Up would call them free.
	 */
	private static final String[] NOT_READY = {"(inactive)", "broken", "(broken)"};
	/** An item named with one of these is a stronger version; a variant must have it too. */
	private static final String[] MARKERS = {"(ei)", "(i)", "(e)", "imbued"};

	private GearUp()
	{
	}

	/**
	 * Gear Up's items among what the player holds, with how many of each. {@code owned} is game id to
	 * quantity, already un-noted and without bank placeholders. A variant (degraded, charged,
	 * ornament, a coloured slayer helmet) counts for the catalog item in its RuneLite variation group,
	 * unless it's another catalog item itself or misses the item's marker: plain Masori isn't (f),
	 * and a plain salve amulet isn't (ei).
	 *
	 * @param base RuneLite's ItemVariationMapping.map
	 * @param name the game's name for an id, or null
	 */
	static Map<Integer, Long> match(Map<Integer, Long> owned, Catalog catalog, IntUnaryOperator base, IntFunction<String> name)
	{
		Map<Integer, Long> found = new LinkedHashMap<>();
		if (catalog == null || catalog.items == null)
		{
			return found;
		}
		Map<Integer, Entry> byId = new HashMap<>();
		Map<Integer, List<Entry>> byBase = new HashMap<>();
		for (Entry e : catalog.items)
		{
			if (e == null || e.n == null || e.id <= 0)
			{
				continue;
			}
			byId.put(e.id, e);
			byBase.computeIfAbsent(base.applyAsInt(e.id), k -> new ArrayList<>()).add(e);
		}
		for (Map.Entry<Integer, Long> held : owned.entrySet())
		{
			int id = held.getKey();
			long quantity = held.getValue() == null ? 0 : held.getValue();
			if (quantity <= 0)
			{
				continue;
			}
			Entry exact = byId.get(id);
			if (exact != null)
			{
				found.merge(exact.id, quantity, Long::sum);
				continue;
			}
			List<Entry> group = byBase.get(base.applyAsInt(id));
			if (group == null)
			{
				continue;
			}
			String variant = name.apply(id);
			for (Entry e : group)
			{
				if (counts(variant, e.n))
				{
					found.merge(e.id, quantity, Long::sum);
				}
			}
		}
		return found;
	}

	/** Whether a variant named {@code variant} counts as owning the catalog item {@code item}. */
	static boolean counts(String variant, String item)
	{
		if (variant == null)
		{
			return false;
		}
		String v = variant.toLowerCase(Locale.ROOT);
		String i = item.toLowerCase(Locale.ROOT);
		for (String word : NOT_READY)
		{
			if (v.contains(word))
			{
				return false;
			}
		}
		// Barrows gear at 0 is broken until repaired.
		if (v.endsWith(" 0"))
		{
			return false;
		}
		for (String marker : MARKERS)
		{
			if (i.contains(marker) && !v.contains(marker))
			{
				return false;
			}
		}
		return true;
	}

	/** Ids from {@link #match}, merged across the bank, bag and worn gear, with enough of each. */
	static List<Integer> owned(Catalog catalog, Collection<Map<Integer, Long>> parts)
	{
		Map<Integer, Long> total = new LinkedHashMap<>();
		for (Map<Integer, Long> part : parts)
		{
			if (part != null)
			{
				part.forEach((id, q) -> total.merge(id, q, Long::sum));
			}
		}
		Map<Integer, Integer> min = new HashMap<>();
		if (catalog != null && catalog.items != null)
		{
			for (Entry e : catalog.items)
			{
				if (e != null && e.min != null)
				{
					min.put(e.id, e.min);
				}
			}
		}
		List<Integer> ids = new ArrayList<>();
		total.forEach((id, q) ->
		{
			if (q >= min.getOrDefault(id, 1))
			{
				ids.add(id);
			}
		});
		ids.sort(Integer::compare);
		return ids;
	}

	/**
	 * The Gear Up link. {@code bankSeenAt} is 0 when the bank hasn't been open on this account;
	 * {@code coins} is null when unknown; {@code account} is null when the game wasn't read.
	 */
	static String link(String playerName, Account account, List<Integer> ids, Long coins, long bankSeenAt, long now)
	{
		StringBuilder hash = new StringBuilder("#rl=1");
		String name = playerName == null ? "" : playerName.replace(' ', ' ').trim();
		if (!name.isEmpty())
		{
			hash.append("&n=").append(encode(name));
		}
		if (account != null)
		{
			hash.append("&lv=");
			for (int i = 0; i < account.levels.length; i++)
			{
				hash.append(i == 0 ? "" : ".").append(Math.max(1, Math.min(99, account.levels[i])));
			}
			if (account.type != null)
			{
				hash.append("&type=").append(account.type);
			}
			if (!account.done.isEmpty())
			{
				hash.append("&done=");
				for (int i = 0; i < account.done.size(); i++)
				{
					hash.append(i == 0 ? "" : ",").append(encode(account.done.get(i)));
				}
			}
		}
		hash.append("&have=");
		for (int i = 0; i < ids.size(); i++)
		{
			hash.append(i == 0 ? "" : ".").append(ids.get(i));
		}
		if (coins != null)
		{
			hash.append("&gp=").append(Math.max(0, coins));
		}
		hash.append("&bank=").append(bankSeenAt > 0 ? bankSeenAt / 1000 : 0);
		hash.append("&at=").append(now / 1000);
		return PAGE + hash;
	}

	private static String encode(String text)
	{
		return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
	}

	/**
	 * The website's account type for the game's ironman varbit (RuneLite's AccountType order).
	 * Group ironmen are "group"; the website treats every kind of ironman alike for prices.
	 */
	static String accountType(int ironmanVarbit)
	{
		switch (ironmanVarbit)
		{
			case 0:
				return "regular";
			case 1:
				return "ironman";
			case 2:
				return "ultimate";
			case 3:
				return "hardcore";
			case 4:
			case 5:
			case 6:
				return "group";
			default:
				return null;
		}
	}

	/** The names on the catalog's list the game says are done, in the list's order. */
	static List<String> done(Catalog catalog, Predicate<String> finished)
	{
		List<String> out = new ArrayList<>();
		if (catalog == null || catalog.done == null)
		{
			return out;
		}
		for (String name : catalog.done)
		{
			if (name != null && !name.isEmpty() && !name.contains(",") && finished.test(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	/** "id:qty,id:qty" for our saved settings. */
	static String save(Map<Integer, Long> items)
	{
		StringBuilder out = new StringBuilder();
		items.forEach((id, q) -> out.append(out.length() == 0 ? "" : ",").append(id).append(':').append(q));
		return out.toString();
	}

	static Map<Integer, Long> load(String saved)
	{
		Map<Integer, Long> items = new LinkedHashMap<>();
		if (saved == null || saved.isEmpty())
		{
			return items;
		}
		for (String part : saved.split(","))
		{
			String[] kv = part.split(":");
			try
			{
				if (kv.length == 2)
				{
					items.put(Integer.parseInt(kv[0]), Long.parseLong(kv[1]));
				}
			}
			catch (NumberFormatException e)
			{
				// A hand-edited or old value: skip that one item.
			}
		}
		return items;
	}
}
