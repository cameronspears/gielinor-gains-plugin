package com.gielinorgains;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemVariationMapping;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The Gear tab: reads the bank (when the player opens it), bag and worn gear, keeps the Gear Up
 * items among them, and opens Gear Up on the website with them in the link. Only reads; it never
 * sends what the player owns anywhere itself. Client thread, except the catalog download.
 */
@Slf4j
@Singleton
class GearUpController
{
	static final HttpUrl CATALOG_URL = HttpUrl.get(TraderApi.SITE + "/api/gear-up/catalog");
	/** The catalog changes only when the website ships, so a day is plenty. */
	private static final long CATALOG_MAX_AGE_MS = 24 * 60 * 60_000L;
	private static final long CATALOG_RETRY_MS = 60_000L;
	// Per game account, in our own settings: the bank's Gear Up items and coins, and when it was seen.
	private static final String BANK_KEY = "gearUpBank";
	private static final String BANK_COINS_KEY = "gearUpBankCoins";
	private static final String BANK_AT_KEY = "gearUpBankAt";
	/** Knight Waves training, the second half of Piety's unlock, ends at this step. */
	static final int KNIGHT_WAVES_DONE = 8;
	/** RuneLite's quests by their names, which are the website's names too. */
	private static final Map<String, Quest> QUESTS = new HashMap<>();

	static
	{
		for (Quest quest : Quest.values())
		{
			QUESTS.put(quest.getName(), quest);
		}
	}

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ConfigManager configManager;
	private final GielinorGainsConfig config;
	private final OkHttpClient http;
	private final Gson gson;

	private GearPanel panel;
	private volatile GearUp.Catalog catalog;
	private volatile boolean catalogFailed;
	// Guarded by this.
	private Call inFlight;
	private long catalogAt;
	private long retryAt;

	// Client thread only.
	private String loadedKey;
	/** The bank as last seen this session (game id to quantity), until the catalog arrives to match it. */
	private Map<Integer, Long> bankSeen;
	private Map<Integer, Long> bankItems = new HashMap<>();
	private Long bankCoins;
	private long bankAt;
	private boolean dirty = true;
	private GearPanel.View shown;
	private long shownMinute;

	@Inject
	GearUpController(Client client, ClientThread clientThread, ItemManager itemManager, ConfigManager configManager,
		GielinorGainsConfig config, OkHttpClient http, Gson gson)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.configManager = configManager;
		this.config = config;
		this.http = http;
		this.gson = gson;
	}

	GearPanel buildPanel(IntFunction<AsyncBufferedImage> icons)
	{
		panel = new GearPanel(icons, () -> clientThread.invokeLater(this::open), () ->
		{
			synchronized (this)
			{
				retryAt = 0;
			}
			clientThread.invokeLater(() ->
			{
				dirty = true;
				fetchCatalog();
			});
		});
		return panel;
	}

	/** The Gear tab came on screen: the catalog is fetched now, not before anyone looks. */
	void onShown()
	{
		clientThread.invokeLater(() ->
		{
			dirty = true;
			fetchCatalog();
		});
	}

	void onItems(int containerId)
	{
		if (!config.rememberGear() || !ItemCheckOverlay.mainEconomy(client))
		{
			return;
		}
		if (containerId == InventoryID.BANK)
		{
			loadAccount();
			bankSeen = read(InventoryID.BANK);
			bankAt = System.currentTimeMillis();
			bankCoins = coins(bankSeen);
			// The bank was opened: match it now if we can, else as soon as the list arrives.
			fetchCatalog();
			matchBank();
		}
		if (containerId == InventoryID.BANK || containerId == InventoryID.INV || containerId == InventoryID.WORN)
		{
			dirty = true;
		}
	}

	void onConfigChanged()
	{
		if (!config.rememberGear())
		{
			forget();
		}
		dirty = true;
	}

	void onTick()
	{
		if (panel == null || !panel.isShowing())
		{
			return;
		}
		long minute = System.currentTimeMillis() / 60_000;
		if (!dirty && minute == shownMinute)
		{
			return;
		}
		dirty = false;
		shownMinute = minute;
		GearPanel.View view = view(System.currentTimeMillis());
		if (!view.equals(shown))
		{
			shown = view;
			SwingUtilities.invokeLater(() -> panel.show(view));
		}
	}

	void reset()
	{
		synchronized (this)
		{
			if (inFlight != null)
			{
				inFlight.cancel();
				inFlight = null;
			}
		}
		loadedKey = null;
		bankSeen = null;
		bankItems = new HashMap<>();
		bankCoins = null;
		bankAt = 0;
	}

	private GearPanel.View view(long now)
	{
		boolean on = config.rememberGear();
		boolean loggedIn = client.getGameState() == GameState.LOGGED_IN;
		if (!on || !loggedIn)
		{
			return new GearPanel.View(on, loggedIn, GearPanel.Status.READY, java.util.Collections.emptyList(), 0, now);
		}
		loadAccount();
		GearUp.Catalog list = catalog;
		if (list == null)
		{
			return new GearPanel.View(true, true, catalogFailed ? GearPanel.Status.FAILED : GearPanel.Status.LOADING,
				java.util.Collections.emptyList(), bankAt, now);
		}
		Map<Integer, String> names = new HashMap<>();
		for (GearUp.Entry e : list.items)
		{
			names.put(e.id, e.n);
		}
		List<GearPanel.Owned> owned = new java.util.ArrayList<>();
		for (int id : ownedIds(list))
		{
			owned.add(new GearPanel.Owned(id, names.get(id)));
		}
		return new GearPanel.View(true, true, GearPanel.Status.READY, owned, bankAt, now);
	}

	private List<Integer> ownedIds(GearUp.Catalog list)
	{
		return GearUp.owned(list, Arrays.asList(bankItems, match(read(InventoryID.INV), list), match(read(InventoryID.WORN), list)));
	}

	/** Opens Gear Up with the player's gear, built from what the game shows right now. */
	private void open()
	{
		GearUp.Catalog list = catalog;
		boolean ready = config.rememberGear() && client.getGameState() == GameState.LOGGED_IN && list != null
			&& ItemCheckOverlay.mainEconomy(client);
		if (!ready)
		{
			LinkBrowser.browse(GearUp.PAGE);
			return;
		}
		loadAccount();
		Map<Integer, Long> bag = read(InventoryID.INV);
		Long carried = coins(bag);
		Long coins = bankCoins == null ? carried : bankCoins + carried;
		Player me = client.getLocalPlayer();
		LinkBrowser.browse(GearUp.link(me == null ? null : me.getName(), account(list), ownedIds(list), coins, bankAt,
			System.currentTimeMillis()));
	}

	/** Levels (not boosted), account type, and the catalog's quests and unlocks that are done. */
	private GearUp.Account account(GearUp.Catalog list)
	{
		int[] levels = new int[GearUp.SKILLS.length];
		for (int i = 0; i < levels.length; i++)
		{
			levels[i] = client.getRealSkillLevel(GearUp.SKILLS[i]);
		}
		return new GearUp.Account(levels, GearUp.accountType(client.getVarbitValue(VarbitID.IRONMAN)),
			GearUp.done(list, this::finished));
	}

	/** Whether the game says a quest or unlock from the catalog's list is done. Unknown names aren't. */
	private boolean finished(String name)
	{
		// The website's names for unlocks: UNLOCKS in lib/gearUp/types.ts.
		switch (name)
		{
			case "Piety":
				return finished(Quest.KINGS_RANSOM) && client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE) >= KNIGHT_WAVES_DONE;
			case "Rigour":
				return client.getVarbitValue(VarbitID.PRAYER_RIGOUR_UNLOCKED) == 1;
			case "Augury":
				return client.getVarbitValue(VarbitID.PRAYER_AUGURY_UNLOCKED) == 1;
			case "Deadeye":
				return client.getVarbitValue(VarbitID.PRAYER_DEADEYE_UNLOCKED) == 1;
			case "Mystic Vigour":
				return client.getVarbitValue(VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED) == 1;
			case "Kandarin hard diary":
				return client.getVarbitValue(VarbitID.KANDARIN_DIARY_HARD_COMPLETE) == 1;
			default:
				Quest quest = QUESTS.get(name);
				return quest != null && finished(quest);
		}
	}

	private boolean finished(Quest quest)
	{
		return quest.getState(client) == QuestState.FINISHED;
	}

	/** The account's saved bank, the first time it's seen after login. */
	private void loadAccount()
	{
		String key = configManager.getRSProfileKey();
		if (key == null || key.equals(loadedKey))
		{
			return;
		}
		loadedKey = key;
		bankSeen = null;
		bankItems = GearUp.load(configManager.getRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_KEY));
		String coins = configManager.getRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_COINS_KEY);
		String at = configManager.getRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_AT_KEY);
		bankCoins = parse(coins);
		Long seen = parse(at);
		bankAt = seen == null ? 0 : seen;
	}

	private static Long parse(String value)
	{
		try
		{
			return value == null ? null : Long.parseLong(value);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Matches the bank seen this session and saves it, once the catalog is here. */
	private void matchBank()
	{
		GearUp.Catalog list = catalog;
		if (list == null || bankSeen == null || loadedKey == null)
		{
			return;
		}
		Map<Integer, Long> matched = match(bankSeen, list);
		bankSeen = null;
		dirty = true;
		// Every deposit fires a bank change; the saved copy only changes when the gear or coins do,
		// though its time is kept fresh at most once a minute.
		String saved = configManager.getRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_AT_KEY);
		Long savedAt = parse(saved);
		if (matched.equals(bankItems) && savedAt != null && bankAt - savedAt < 60_000
			&& String.valueOf(bankCoins == null ? 0 : bankCoins).equals(configManager.getRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_COINS_KEY)))
		{
			return;
		}
		bankItems = matched;
		configManager.setRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_KEY, GearUp.save(bankItems));
		configManager.setRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_COINS_KEY, String.valueOf(bankCoins == null ? 0 : bankCoins));
		configManager.setRSProfileConfiguration(GielinorGainsConfig.GROUP, BANK_AT_KEY, String.valueOf(bankAt));
		dirty = true;
	}

	/** Turning the setting off forgets every account's saved bank, not just this one's. */
	private void forget()
	{
		for (String profile : configManager.getRSProfiles().stream().map(p -> p.getKey()).toArray(String[]::new))
		{
			for (String key : new String[]{BANK_KEY, BANK_COINS_KEY, BANK_AT_KEY})
			{
				configManager.unsetConfiguration(GielinorGainsConfig.GROUP, profile, key);
			}
		}
		reset();
	}

	private Map<Integer, Long> match(Map<Integer, Long> items, GearUp.Catalog list)
	{
		return GearUp.match(items, list, ItemVariationMapping::map, id -> itemManager.getItemComposition(id).getName());
	}

	/** A container as game id to quantity: noted items as the item, bank placeholders left out. */
	private Map<Integer, Long> read(int containerId)
	{
		Map<Integer, Long> items = new HashMap<>();
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return items;
		}
		for (Item item : container.getItems())
		{
			int id = item.getId();
			if (id <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition composition = itemManager.getItemComposition(id);
			if (composition.getPlaceholderTemplateId() != -1)
			{
				continue;
			}
			items.merge(itemManager.canonicalize(id), (long) item.getQuantity(), Long::sum);
		}
		return items;
	}

	/** Coins, with each platinum token worth 1,000. */
	static long coins(Map<Integer, Long> items)
	{
		return items.getOrDefault(ItemID.COINS, 0L) + 1000L * items.getOrDefault(ItemID.PLATINUM, 0L);
	}

	/** Downloads the catalog if it's missing or a day old. One GET with nothing about the player. */
	private synchronized void fetchCatalog()
	{
		long now = System.currentTimeMillis();
		boolean fresh = catalog != null && now - catalogAt < CATALOG_MAX_AGE_MS;
		if (fresh || inFlight != null || now < retryAt || !config.rememberGear())
		{
			return;
		}
		Call call = http.newCall(new Request.Builder().url(CATALOG_URL).get().build());
		inFlight = call;
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Gielinor Gains gear list failed: {}", e.toString());
				done(null);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				GearUp.Catalog read = null;
				try (ResponseBody body = response.body())
				{
					if (response.isSuccessful() && body != null)
					{
						read = gson.fromJson(body.charStream(), GearUp.Catalog.class);
					}
				}
				catch (Exception e)
				{
					log.debug("Gielinor Gains gear list unreadable: {}", e.toString());
				}
				done(read != null && read.v == 1 && read.items != null ? read : null);
			}
		});
	}

	private void done(GearUp.Catalog read)
	{
		synchronized (this)
		{
			inFlight = null;
			if (read == null)
			{
				retryAt = System.currentTimeMillis() + CATALOG_RETRY_MS;
			}
			else
			{
				catalogAt = System.currentTimeMillis();
			}
		}
		if (read != null)
		{
			catalog = read;
		}
		catalogFailed = read == null && catalog == null;
		clientThread.invokeLater(() ->
		{
			matchBank();
			dirty = true;
		});
	}
}
