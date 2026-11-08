package com.gielinorgains;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SoundEffectID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Trader in the game, wired up: sign-in by code, the sync, the sidebar's Trade tab, the guide on
 * the next plan step, slot badges, bank outlines, the info box and notifications. It reads the
 * game and draws over it; the player does every click and key press. Client thread unless noted.
 */
@Singleton
class TraderController
{
	private static final long PRICE_RETRY_MS = 2 * 60_000L;

	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final GielinorGainsConfig config;
	private final TraderApi api;
	private final PlanFeed feed;
	/** The game's spelling of item names, for the website's plan and picks. */
	private final GameNames names;
	private final Guide guide;
	private final ItemCheckOverlay itemOverlay;
	private final TraderOverlay overlay;
	private final TraderOverlay.Held held;
	private final InfoBoxManager infoBoxes;
	private final Notifier notifier;
	private final Gson gson;
	private final ItemCheckFeed marketFeed;
	private final ChatMessageManager chat;

	private Trader trader;
	private TradePanel tradePanel;
	private ProgressPanel progressPanel;
	/** An achievement earned while RuneLite has been open, marked New on the Progress tab. */
	private String newAchievement;
	private GielinorGainsPanel panel;
	private MarketPanel market;
	private InfoBox infoBox;
	private String deviceCode;
	private String code;
	private long pollAt;
	private int pollSeconds = 5;
	private String problem;
	/** The website couldn't be reached on the last try. */
	private boolean offline;
	private boolean asking;
	private Trader.Moment moment;
	/** The best finished flip this session. */
	private Trader.Moment best;
	private long linkedAt;
	/** Starts true, so a plan that's already empty when RuneLite opens isn't announced. */
	private boolean toldEmpty = true;
	private long momentAt;
	/** A finished flip or a filled buy stays on the Trade tab this long. */
	private static final long MOMENT_MS = 12_000;
	private long coins = -1;
	private int loginTick = -1;
	private int pinnedFor = -1;
	/** Our own setting, per game account, that keeps its trades and pick links across restarts. */
	private static final String STATE_KEY = "traderState";
	/** The Make and Sell swap taken and the window marked done, kept across restarts (the claim is the account's). */
	private static final String MAKE_SELL_KEY = "makeSellSwap";
	private static final long PERSIST_MS = 5_000;
	private String loadedKey;
	private String savedState;
	private long persistedAt;
	private Trader.Kind pinnedKind;
	private String repriceKey;
	private String lastLowerKey;
	private String lastReboundKey;
	/** The last "stop the rest of a Rebound's buy" step told, so it's told once. */
	private String lastStopKey;
	private Object shownKey;
	private final MakeSell makeSell = new MakeSell();
	private boolean makeSellLoaded;
	private long makeSellAskedAt;
	private TradePanel.MakeSellCard makeSellCard;
	/** The GE box's line for a swap's item: "Make and Sell: Alch Rune platebody"; else null. */
	private String makeSellNote;
	private final Map<Integer, Long> heldPrices = new HashMap<>();
	private final Map<Integer, Long> priceAskedAt = new HashMap<>();
	/** Daily Trader steps already reported, for the UTC day they were. */
	private final Set<String> stepsSent = new LinkedHashSet<>();
	private final Set<String> stepsDue = new LinkedHashSet<>();
	private String stepsDay;

	@Inject
	TraderController(Client client, ClientThread clientThread, ConfigManager configManager, GielinorGainsConfig config,
		TraderApi api, PlanFeed feed, Guide guide, ItemCheckOverlay itemOverlay, TraderOverlay overlay,
		TraderOverlay.Held held, InfoBoxManager infoBoxes, Notifier notifier, Gson gson, ItemCheckFeed marketFeed,
		ChatMessageManager chat)
	{
		this.chat = chat;
		this.client = client;
		this.clientThread = clientThread;
		this.configManager = configManager;
		this.config = config;
		this.api = api;
		this.feed = feed;
		this.names = new GameNames(id -> client.getItemDefinition(id).getName());
		this.guide = guide;
		this.itemOverlay = itemOverlay;
		this.overlay = overlay;
		this.held = held;
		this.infoBoxes = infoBoxes;
		this.notifier = notifier;
		this.gson = gson;
		this.marketFeed = marketFeed;
	}

	/** Swing thread: builds the Trade tab, and the sidebar around it. */
	GielinorGainsPanel buildPanel(MarketPanel market, GearPanel gear, Runnable onGearShown, IntFunction<AsyncBufferedImage> icons,
		BufferedImage logo)
	{
		this.market = market;
		tradePanel = new TradePanel(new TradePanel.Actions()
		{
			@Override
			public void signIn()
			{
				clientThread.invokeLater(TraderController.this::startSignIn);
			}

			@Override
			public void setBudget(long gp)
			{
				clientThread.invokeLater(() -> trader.setBudget(gp));
			}

			@Override
			public void skip(int itemId)
			{
				clientThread.invokeLater(() -> trader.skip(itemId));
			}

			@Override
			public void dismiss(String stepKey)
			{
				clientThread.invokeLater(() -> trader.dismiss(stepKey));
			}

			@Override
			public void retry()
			{
				clientThread.invokeLater(() ->
				{
					offline = false;
					trader.retryNow();
					if (!signedIn())
					{
						startSignIn();
					}
				});
			}

			@Override
			public void openSettings()
			{
				panel.showSettingsTab();
			}

			@Override
			public void addRebound()
			{
				clientThread.invokeLater(TraderController.this::addRebound);
			}

			@Override
			public void dismissRebound()
			{
				clientThread.invokeLater(() -> trader.forgetRebound());
			}

			@Override
			public void takeSwap()
			{
				clientThread.invokeLater(TraderController.this::takeSwap);
			}

			@Override
			public void nextSwap()
			{
				clientThread.invokeLater(TraderController.this::nextSwap);
			}

			@Override
			public void giveBackSwap()
			{
				clientThread.invokeLater(TraderController.this::giveBackSwap);
			}

			@Override
			public void finishSwap()
			{
				clientThread.invokeLater(TraderController.this::finishSwap);
			}
		}, icons);
		progressPanel = new ProgressPanel();
		panel = new GielinorGainsPanel(market, tradePanel, gear, progressPanel, logo, () -> clientThread.invokeLater(this::unlink),
			() -> clientThread.invokeLater(this::onTradeShown), onGearShown);
		market.setTrader(false, id -> { });
		return panel;
	}

	void start(Plugin plugin, BufferedImage icon)
	{
		trader = new Trader(gson, System.currentTimeMillis());
		overlay.setTrader(trader);
		infoBox = new InfoBox(icon, plugin)
		{
			@Override
			public String getText()
			{
				return Integer.toString(trader.toCollect());
			}

			@Override
			public Color getTextColor()
			{
				return Brand.TEXT;
			}

			@Override
			public String getTooltip()
			{
				return "Gielinor Gains: GE offers ready to collect";
			}

			@Override
			public boolean render()
			{
				return signedIn() && config.traderInfoBox() && !otherWorld() && trader.toCollect() > 0;
			}
		};
		infoBoxes.addInfoBox(infoBox);
	}

	void stop()
	{
		persist();
		infoBoxes.removeInfoBox(infoBox);
		overlay.setTrader(null);
		itemOverlay.setPlanFlip(-1, null);
		trader = null;
		deviceCode = null;
		code = null;
	}

	private boolean signedIn()
	{
		return !config.traderKey().isEmpty();
	}

	// --- Sign-in by code ---

	/** Signed out with the Trade tab open: a code is fetched straight away, so there's nothing to press first. */
	private void onTradeShown()
	{
		if (trader != null && !signedIn() && code == null && !offline && problem == null)
		{
			startSignIn();
		}
	}

	private void startSignIn()
	{
		if (asking)
		{
			return;
		}
		asking = true;
		problem = null;
		api.start(r -> clientThread.invokeLater(() ->
		{
			asking = false;
			if (r.body == null || r.body.code == null || r.body.deviceCode == null)
			{
				offline = r.status == 0 || r.status >= 500;
				problem = offline ? null : "We couldn't start signing in. Try again in a minute.";
				return;
			}
			offline = false;
			code = r.body.code;
			deviceCode = r.body.deviceCode;
			pollSeconds = Math.max(5, r.body.interval);
			pollAt = System.currentTimeMillis() + pollSeconds * 1000L;
		}));
	}

	private void poll(long now)
	{
		if (deviceCode == null || now < pollAt)
		{
			return;
		}
		pollAt = Long.MAX_VALUE;
		String asked = deviceCode;
		api.poll(asked, r -> clientThread.invokeLater(() ->
		{
			if (!asked.equals(deviceCode))
			{
				return;
			}
			String status = r.body == null ? null : r.body.status;
			if ("linked".equals(status) && r.body.token != null)
			{
				configManager.setConfiguration(GielinorGainsConfig.GROUP, GielinorGainsConfig.TRADER_KEY, r.body.token);
				linkedAt = System.currentTimeMillis();
				deviceCode = null;
				code = null;
			}
			else if ("expired".equals(status))
			{
				// The old code ran out. A new one comes straight away while the Trade tab is on screen;
				// else when it's next opened, so a sign-in left waiting doesn't poll all day.
				deviceCode = null;
				code = null;
				if (panel != null && panel.tradeShown())
				{
					startSignIn();
				}
			}
			else
			{
				pollAt = System.currentTimeMillis() + pollSeconds * 1000L;
			}
		}));
	}

	/** Signs this RuneLite out: the key ends on the website and nothing of the account stays here. */
	private void unlink()
	{
		String key = config.traderKey();
		if (!key.isEmpty())
		{
			api.unlink(key);
		}
		signOut(null);
	}

	private void signOut(String why)
	{
		configManager.unsetConfiguration(GielinorGainsConfig.GROUP, GielinorGainsConfig.TRADER_KEY);
		trader.clear();
		makeSell.eligible = null;
		makeSell.claim = null;
		makeSell.swap = null;
		makeSell.skipped.clear();
		makeSellAskedAt = 0;
		saveMakeSell();
		itemOverlay.setPlanFlip(-1, null);
		held.setHeld(Collections.emptySet());
		problem = why;
	}

	// --- The game ---

	void onLoggedIn()
	{
		loginTick = client.getTickCount();
		if (!config.traderWelcomeShown() && config.showMarketList())
		{
			configManager.setConfiguration(GielinorGainsConfig.GROUP, GielinorGainsConfig.WELCOME_SHOWN, true);
			chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(
				new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append("Gielinor Gains: ")
					.append(ChatColorType.NORMAL).append("Trader, the Market and your Progress are in the sidebar.")
					.build()).build());
		}
	}

	/**
	 * Loads the logged-in game account's saved records the first time it's seen, before the game's
	 * login offers are read, so offers that finished while RuneLite was closed are caught.
	 */
	private void account(long now)
	{
		String key = configManager.getRSProfileKey();
		String profile = Trader.profileId(key);
		trader.setAccount(profile, client.getWorldType().contains(WorldType.MEMBERS));
		if (profile == null || key.equals(loadedKey))
		{
			return;
		}
		persist();
		loadedKey = key;
		savedState = configManager.getConfiguration(GielinorGainsConfig.GROUP, key, STATE_KEY);
		trader.load(savedState, now);
	}

	/** Saves the account's records in our own settings when they've changed. */
	private void persist()
	{
		String state = trader == null || loadedKey == null ? null : trader.save();
		if (state != null && !state.equals(savedState))
		{
			configManager.setConfiguration(GielinorGainsConfig.GROUP, loadedKey, STATE_KEY, state);
			savedState = state;
		}
	}

	/** Each GE slot as the game shows it. Offers the game sends at login aren't new trades. */
	void onOffer(int slot, GrandExchangeOffer offer)
	{
		if (trader == null || otherWorld())
		{
			return;
		}
		long now = System.currentTimeMillis();
		account(now);
		boolean live = loginTick >= 0 && client.getTickCount() > loginTick + 2;
		boolean planned = trader.planned(offer.getItemId());
		String state = offer.getState().name();
		trader.onOffer(slot, offer.getItemId(), state, offer.getQuantitySold(), offer.getTotalQuantity(), offer.getPrice(),
			offer.getSpent(), now, live);
		if (!live || !planned || !signedIn())
		{
			return;
		}
		String name = client.getItemDefinition(offer.getItemId()).getName();
		if (state.equals("BUYING") && offer.getQuantitySold() == 0)
		{
			step("buy_placed");
		}
		else if (state.equals("BOUGHT"))
		{
			notify("Your " + name + " buy filled. Collect it, then sell it.");
		}
		else if (state.equals("SOLD"))
		{
			step("trade_closed");
			notify("Your " + name + " sold. Collect your coins.");
		}
	}

	/** Coins and platinum tokens in the bank and inventory: what Trader plans with when no budget is typed. */
	void onItems(int containerId)
	{
		if (containerId != InventoryID.BANK && containerId != InventoryID.INV || otherWorld())
		{
			return;
		}
		long pack = gp(client.getItemContainer(InventoryID.INV));
		ItemContainer bankItems = client.getItemContainer(InventoryID.BANK);
		Long bank = bankItems == null ? null : gp(bankItems);
		coins = pack + (bank == null ? 0 : bank);
		if (trader != null)
		{
			trader.setGpSeen(pack, bank);
		}
	}

	/** Coins plus platinum tokens (1,000 coins each) in one container. */
	private static long gp(ItemContainer container)
	{
		long total = 0;
		for (Item item : container == null ? new Item[0] : container.getItems())
		{
			if (item.getId() == ItemID.COINS)
			{
				total += item.getQuantity();
			}
			else if (item.getId() == ItemID.PLATINUM)
			{
				total += 1000L * item.getQuantity();
			}
		}
		return total;
	}

	void onTick()
	{
		if (trader == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		poll(now);
		// Leagues, Deadman and beta worlds have their own GE, so Trader neither reads nor plans there.
		boolean otherWorld = otherWorld();
		if (!otherWorld)
		{
			account(now);
		}
		if (now - persistedAt > PERSIST_MS)
		{
			persistedAt = now;
			persist();
		}
		boolean signedIn = signedIn();
		if (signedIn && !otherWorld)
		{
			String profile = Trader.profileId(configManager.getRSProfileKey());
			// Until the game is in a world its world type isn't known, so nothing is planned yet.
			boolean inGame = client.getGameState() == GameState.LOGGED_IN;
			if (profile != null && inGame)
			{
				feed.refreshIfDue(now);
				if (trader.refill(planFeed(), now) > 0)
				{
					step("pick_shown");
				}
				sync(now);
			}
		}
		Trader.Step next = signedIn && !otherWorld && trader.plan() != null ? trader.next(planFeed(), now, this::heldPrice) : null;
		next = makeSell(signedIn && !otherWorld, next, now);
		Ui.reduceMotion = config.reduceMotion();
		for (Trader.Moment m : trader.takeMoments())
		{
			overlay.drop(m);
			moment = m;
			momentAt = now;
			if (m.isSale() && m.isKnown() && (best == null || m.getProfit() > best.getProfit()))
			{
				best = m;
			}
			if (config.flipSound())
			{
				// The game's own GE sounds, at the player's sound effect volume.
				client.playSoundEffect(m.isSale() ? SoundEffectID.GE_COIN_TINKLE : SoundEffectID.GE_ADD_OFFER_DINGALING);
			}
		}
		if (moment != null && (now - momentAt > MOMENT_MS || !moment.isSale() && (next == null
			|| next.getItemId() != moment.getItemId())))
		{
			moment = null;
		}
		alerts(next);
		// A plan item the player opened in the GE is the one to guide, so the box, the badges and
		// the tab all follow what's on screen rather than the plan's own next step.
		int setup = itemOverlay.setupItem();
		if (signedIn && setup > 0 && (next == null || next.getItemId() != setup) && trader.planned(setup))
		{
			Trader.Step opened = trader.stepFor(planFeed(), now, this::heldPrice, setup);
			if (opened != null && (opened.getKind() == Trader.Kind.SELL) == itemOverlay.setupSell())
			{
				next = opened;
			}
		}
		overlay.setNext(next);
		// After a sale with nothing next, the box shows the day's reward rather than nothing.
		Trader.Today day = signedIn ? trader.todaySoFar() : null;
		guide.setDoneLine(day != null && (day.flips > 0 || day.selling != 0) ? "Today so far: " + (day.profit >= 0 ? "+" : "")
			+ BoxText.whole(day.profit) + " GP from " + BoxText.soFarFrom(day.flips, day.selling) + "." : null);
		guideTo(next);
		held.setHeld(signedIn && !otherWorld ? trader.held() : Collections.emptySet());
		sendSteps(signedIn);
		updatePanel(signedIn, next, now, otherWorld);
	}

	private void sync(long now)
	{
		if (!trader.due(now))
		{
			return;
		}
		String body = trader.request(now);
		api.sync(config.traderKey(), body, r -> clientThread.invokeLater(() ->
		{
			if (trader == null)
			{
				return;
			}
			if (r.status == 401)
			{
				signOut("You're signed out. Sign in again to keep using Trader here.");
				return;
			}
			long at = System.currentTimeMillis();
			names.fix(r.body);
			trader.onReply(r.status, r.body, at);
			offline = r.status == 0 || r.status >= 500;
			if (r.status == 200 || r.status == 409)
			{
				problem = null;
				step("opened");
				step("connected");
			}
			else if (!offline)
			{
				problem = r.body != null && r.body.error != null && r.status == 400 ? r.body.error
					: "We couldn't save your plan just now. It's safe; we'll try again.";
			}
		}));
	}

	/** A held item's sale price once it's left the plan feed, from the price check; 0 while unknown. */
	private long heldPrice(int itemId)
	{
		Long price = heldPrices.get(itemId);
		long now = System.currentTimeMillis();
		Long asked = priceAskedAt.get(itemId);
		if (asked == null || now - asked > PRICE_RETRY_MS)
		{
			priceAskedAt.put(itemId, now);
			api.price(itemId, r -> clientThread.invokeLater(() ->
			{
				if (r.body != null && r.body.fresh && r.body.sell != null)
				{
					heldPrices.put(itemId, r.body.sell);
				}
			}));
		}
		return price == null ? 0 : price;
	}

	/** Pins the next plan step's item in the guide when the step moves to a new item, at the plan's prices. */
	private void guideTo(Trader.Step next)
	{
		int id = next == null ? -1 : next.getItemId();
		Trader.Kind kind = next == null ? null : next.getKind();
		if (kind == Trader.Kind.LOWER)
		{
			// A new price for an offer that's in: the guide walks Modify instead of a new offer. Modify
			// cancels the offer before the new one goes in, so Trader keeps the new price meanwhile.
			trader.changingPrice(id, next.getPrice(), System.currentTimeMillis());
			if (!next.getKey().equals(repriceKey))
			{
				repriceKey = next.getKey();
				guide.reprice(id, next.getName(), next.getGeSlot(), next.getPrice());
			}
			pinnedFor = -1;
			return;
		}
		if (repriceKey != null)
		{
			// The price change is done or set aside; the guide stops pointing at it.
			repriceKey = null;
			pinnedFor = -1;
			guide.pin(-1, null);
		}
		// A Rebound's box shows its plan, not the Market flip's words.
		if (kind == Trader.Kind.MAKE)
		{
			// The swap goes on in the game: the GE box goes back to its usual check.
			if (guide.itemId() > 0)
			{
				guide.pin(-1, null);
			}
			pinnedFor = -1;
			pinnedKind = kind;
			return;
		}
		Trader.PlanItem pinned = guide.itemId() > 0 ? item(guide.itemId()) : null;
		boolean swap = Trader.isSwap(next);
		guide.setPlanNote(swap && makeSellNote != null ? makeSellNote : pinned != null && pinned.reboundAt != null
			&& System.currentTimeMillis() < Rebounds.sellEndsAt(pinned.reboundAt) ? Rebounds.plan(pinned.sell) : null);
		boolean gePrice = next != null && next.getPrice() > 0;
		if (id > 0 && gePrice && next.getKind() != Trader.Kind.WAIT)
		{
			Trader.PlanItem item = item(id);
			ItemCheck.Flip flip = new ItemCheck.Flip();
			flip.buy = (double) (next.getKind() == Trader.Kind.BUY || item == null ? next.getPrice() : item.buy);
			flip.sell = (double) (next.getKind() == Trader.Kind.SELL || next.getKind() == Trader.Kind.LOWER
				|| item == null ? next.getPrice() : item.sell);
			flip.qty = (double) next.getQty();
			flip.expectedProfit = 0.0;
			flip.profitIfAllSells = 0.0;
			flip.chance = 0.0;
			itemOverlay.setPlanFlip(id, flip);
			boolean sell = next.getKind() == Trader.Kind.SELL;
			if (sell && guide.itemId() == id)
			{
				// The sale sells all Trader counts as held, not just what the last buy offer bought.
				guide.sellQuantity((int) Math.min(Integer.MAX_VALUE, next.getQty()));
			}
			if (id != pinnedFor || next.getKind() != pinnedKind)
			{
				// A buy starts the guide at the buy, even after a collect for the same item (a cancelled offer).
				boolean buying = guide.phase() == Guide.Phase.BUY || guide.phase() == Guide.Phase.BUYING;
				if (guide.itemId() != id || next.getKind() == Trader.Kind.BUY && !buying)
				{
					guide.pin(id, next.getName());
				}
				// Bought earlier and collected: the guide starts at the sale, not the buy.
				if (sell && guide.phase() == Guide.Phase.BUY)
				{
					guide.sell((int) Math.min(Integer.MAX_VALUE, next.getQty()));
				}
				// Filled while the guide was elsewhere: it starts at the collect, not the buy.
				if (next.getKind() == Trader.Kind.COLLECT)
				{
					boolean coins = Trader.COLLECT_COINS.equals(next.getTitle());
					if (guide.phase() != (coins ? Guide.Phase.DONE : Guide.Phase.SELL))
					{
						guide.collect(next.getGeSlot(), coins, (int) Math.min(Integer.MAX_VALUE, next.getQty()));
					}
				}
			}
		}
		// A swap's buy ends at its collect, with the step after it in place of a sale.
		guide.setThen(swap && guide.itemId() == id ? next.getThen() : null);
		pinnedFor = id;
		pinnedKind = next == null ? null : next.getKind();
	}

	// --- Make and Sell, when Trader's picks are full ---

	/**
	 * Make and Sell for Trader's overflow, as the website's Trader offers it: when no automatic
	 * pick has room, asks the website once whether this player is routed, then offers the best
	 * swap with room. A swap taken this window shows its own steps where Trader has nothing to do.
	 * Costs nothing until the note shows; then one GET a session, and a POST per take or give-back.
	 */
	private Trader.Step makeSell(boolean on, Trader.Step next, long now)
	{
		makeSellCard = null;
		makeSellNote = null;
		PlanFeed.Feed plan = planFeed();
		if (!on || plan == null || plan.makeSell == null || trader.plan() == null || trader.plan().budget == null)
		{
			return next;
		}
		loadMakeSell();
		long serverNow = plan.serverNow(now);
		long window = MakeSell.window(serverNow);
		MakeSell.Claim claim = makeSell.claim(window);
		boolean noRoom = next != null && next.getKind() == Trader.Kind.EMPTY && next.getDetail() != null
			&& next.getDetail().startsWith(TraderRules.NO_ROOM);
		if (!noRoom && claim == null)
		{
			return next;
		}
		if (makeSell.eligible == null)
		{
			askMakeSell(now);
			return next;
		}
		if (!makeSell.eligible)
		{
			return next;
		}
		// When the window ends on the server's clock, shown in this computer's time.
		String opens = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(
			MakeSell.windowEnd(window) - serverNow + now).atZone(ZoneId.systemDefault()));
		boolean done = makeSell.doneWindow == window;
		if (claim != null && !done)
		{
			PlanFeed.Swap swap = makeSell.taken(plan.makeSell);
			int current = -1;
			if (swap != null)
			{
				// The claim's time on this computer's clock, a minute early so an offer placed as it was taken counts.
				Trader.Step step = trader.swapStep(swap, claim.units, claim.at - serverNow + now - 60_000);
				if (step == null)
				{
					// Every sale is in and collected: the swap is done.
					finishSwap();
					done = true;
				}
				else
				{
					current = swapIndex(swap, step);
					makeSellNote = MakeSell.boxNote(swap);
					// Where Trader has nothing to do, or only plan prices to wait for: the swap's prices are its own.
					if (next == null || next.getKind() == Trader.Kind.EMPTY || next.getKind() == Trader.Kind.WAIT
						|| next.getKind() == Trader.Kind.STALE && next.getItemId() < 0)
					{
						next = step;
					}
				}
			}
			if (!done)
			{
				makeSellCard = new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.TAKEN, null, false, swap,
					claim.units, current, opens, makeSell.busy, makeSell.error);
				return next;
			}
		}
		if (!noRoom)
		{
			return next;
		}
		if (done)
		{
			makeSellCard = new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.DONE, null, false, null, 0, -1, opens,
				false, null);
			return next;
		}
		List<MakeSell.Offer> open = MakeSell.open(plan.makeSell, trader.freeGp(), freeWorld(), makeSell.skipped);
		makeSellCard = open.isEmpty()
			? new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.FULL, null, false, null, 0, -1, opens, false, null)
			: new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.OFFER, open.get(0), open.size() > 1, null, 0, -1,
			opens, makeSell.busy, makeSell.error);
		return next;
	}

	/** Which of the swap's listed steps a swap step is, counted as MakeSell.steps lists them. */
	static int swapIndex(PlanFeed.Swap swap, Trader.Step step)
	{
		String[] part = step.getKey().substring((Trader.SWAP_KEY + swap.id + ":").length()).split(":");
		if (part[0].equals("make"))
		{
			return swap.buy.size();
		}
		List<PlanFeed.Leg> legs = part[0].equals("buy") ? swap.buy : swap.sell;
		for (int i = 0; i < legs.size(); i++)
		{
			if (String.valueOf(legs.get(i).id).equals(part[1]))
			{
				return part[0].equals("buy") ? i : swap.buy.size() + 1 + i;
			}
		}
		return -1;
	}

	private boolean freeWorld()
	{
		return client.getGameState() == GameState.LOGGED_IN && !client.getWorldType().contains(WorldType.MEMBERS);
	}

	/** Once a session; again after 2 minutes if the website couldn't be reached. */
	private void askMakeSell(long now)
	{
		if (makeSell.asked || now - makeSellAskedAt < PRICE_RETRY_MS)
		{
			return;
		}
		makeSell.asked = true;
		makeSellAskedAt = now;
		api.makeSell(config.traderKey(), r -> clientThread.invokeLater(() ->
		{
			makeSell.asked = false;
			if (r.status == 200 && r.body != null)
			{
				makeSell.eligible = Boolean.TRUE.equals(r.body.on);
				claimed(r.body.claim);
			}
		}));
	}

	/** The claim the website answered; the swap's row is kept with it in case it leaves the feed. */
	private void claimed(MakeSell.Claim claim)
	{
		makeSell.claim = claim == null || claim.id == null ? null : claim;
		PlanFeed.Feed plan = planFeed();
		PlanFeed.Swap row = plan == null ? null : makeSell.taken(plan.makeSell);
		makeSell.swap = makeSell.claim == null ? null : row;
		saveMakeSell();
	}

	private void takeSwap()
	{
		PlanFeed.Feed plan = planFeed();
		if (makeSell.busy || plan == null || plan.makeSell == null || trader.plan() == null)
		{
			return;
		}
		List<MakeSell.Offer> open = MakeSell.open(plan.makeSell, trader.freeGp(), freeWorld(), makeSell.skipped);
		if (open.isEmpty())
		{
			return;
		}
		MakeSell.Offer offer = open.get(0);
		makeSell.busy = true;
		makeSell.error = null;
		api.takeSwap(config.traderKey(), offer.getSwap().id, offer.getUnits(), r -> clientThread.invokeLater(() ->
		{
			makeSell.busy = false;
			String error = r.body == null ? null : r.body.error;
			if (r.status == 200 && r.body != null && r.body.claim != null)
			{
				makeSell.claim = r.body.claim;
				makeSell.swap = offer.getSwap();
				makeSell.doneWindow = -1;
				saveMakeSell();
			}
			else if (r.status == 409 && "window".equals(error))
			{
				// A new window began before the feed caught up: it does within a minute.
				makeSell.error = MakeSell.ERROR_WINDOW;
			}
			else if (r.status == 409)
			{
				// Taken by others, or no longer paying, since the feed was worked out.
				makeSell.skipped.add(offer.getSwap().id);
				makeSell.error = "gone".equals(error) ? MakeSell.ERROR_GONE : MakeSell.ERROR_FULL;
			}
			else
			{
				makeSell.error = MakeSell.ERROR_SAVE;
			}
		}));
	}

	private void nextSwap()
	{
		PlanFeed.Feed plan = planFeed();
		List<MakeSell.Offer> open = plan == null || trader.plan() == null ? Collections.emptyList()
			: MakeSell.open(plan.makeSell, trader.freeGp(), freeWorld(), makeSell.skipped);
		makeSell.error = null;
		// After the last one, start again from the best.
		if (open.size() > 1)
		{
			makeSell.skipped.add(open.get(0).getSwap().id);
		}
		else
		{
			makeSell.skipped.clear();
		}
	}

	private void giveBackSwap()
	{
		if (makeSell.busy)
		{
			return;
		}
		makeSell.busy = true;
		makeSell.error = null;
		api.giveBackSwap(config.traderKey(), r -> clientThread.invokeLater(() ->
		{
			makeSell.busy = false;
			if (r.status != 200)
			{
				makeSell.error = MakeSell.ERROR_SAVE;
				return;
			}
			if (makeSell.claim != null)
			{
				makeSell.skipped.add(makeSell.claim.id);
			}
			makeSell.claim = null;
			makeSell.swap = null;
			saveMakeSell();
		}));
	}

	private void finishSwap()
	{
		if (makeSell.claim != null)
		{
			makeSell.doneWindow = makeSell.claim.window;
			saveMakeSell();
		}
	}

	/** What's kept across restarts: the claim, its swap and the window marked done. */
	private static final class MakeSellSaved
	{
		MakeSell.Claim claim;
		PlanFeed.Swap swap;
		long doneWindow = -1;
	}

	/** The website's plan feed, with item names as the game spells them. Client thread. */
	private PlanFeed.Feed planFeed()
	{
		return names.fix(feed.feed());
	}

	private void loadMakeSell()
	{
		if (makeSellLoaded)
		{
			return;
		}
		makeSellLoaded = true;
		String saved = configManager.getConfiguration(GielinorGainsConfig.GROUP, MAKE_SELL_KEY);
		try
		{
			MakeSellSaved read = saved == null ? null : gson.fromJson(saved, MakeSellSaved.class);
			if (read != null && read.claim != null && read.claim.id != null)
			{
				makeSell.claim = read.claim;
				names.fix(read.swap);
				makeSell.swap = read.swap;
				makeSell.doneWindow = read.doneWindow;
			}
		}
		catch (RuntimeException e)
		{
			// Unreadable: the website's GET brings the claim back, without its saved row.
		}
	}

	private void saveMakeSell()
	{
		if (makeSell.claim == null)
		{
			configManager.unsetConfiguration(GielinorGainsConfig.GROUP, MAKE_SELL_KEY);
			return;
		}
		MakeSellSaved saved = new MakeSellSaved();
		saved.claim = makeSell.claim;
		saved.swap = makeSell.swap;
		saved.doneWindow = makeSell.doneWindow;
		configManager.setConfiguration(GielinorGainsConfig.GROUP, MAKE_SELL_KEY, gson.toJson(saved));
	}

	private Trader.PlanItem item(int id)
	{
		for (Trader.PlanSlot slot : trader.plan().slots)
		{
			if (slot.item != null && slot.item.id == id)
			{
				return slot.item;
			}
		}
		return null;
	}

	/** The 8-hour rule and a new Rebound, each told once. */
	private void alerts(Trader.Step next)
	{
		// Once each time the plan runs out of things to do, not while it loads or prices are late.
		boolean empty = Trader.outOfPicks(next);
		if (empty && !toldEmpty)
		{
			notify("Your Trader plan has nothing left to do right now.");
		}
		// Only a real step resets it: late prices can come and go while the plan stays empty.
		if (next != null && (empty || next.getKind() != Trader.Kind.EMPTY))
		{
			toldEmpty = empty;
		}
		if (next != null && next.getKind() == Trader.Kind.LOWER)
		{
			String key = next.getItemId() + ":" + next.getPrice();
			if (!key.equals(lastLowerKey))
			{
				lastLowerKey = key;
				notify(next.getName() + ": " + next.getTitle());
			}
		}
		if (next != null && next.getKind() == Trader.Kind.STOP && !next.getKey().equals(lastStopKey))
		{
			lastStopKey = next.getKey();
			notify(next.getTitle() + ".");
		}
		Trader.Rebound rebound = trader.rebound();
		String key = rebound == null ? null : rebound.key;
		if (key != null && !key.equals(lastReboundKey))
		{
			notify("New Rebound: " + rebound.name + " dropped " + Math.round(rebound.drop * 100) + "%.");
		}
		lastReboundKey = key;
	}

	/** The unstarted pick a Rebound would replace, for its card; null when a slot is free or none can go. */
	private String swapName(boolean signedIn)
	{
		Trader.PlanItem swap = signedIn && trader.rebound() != null ? trader.reboundSwap() : null;
		return swap == null ? null : swap.name;
	}

	private void addRebound()
	{
		Trader.Rebound rebound = trader.rebound();
		if (rebound != null && !trader.add(rebound.id, rebound.name, rebound.qty, rebound.buy, rebound.sell, true,
			System.currentTimeMillis()))
		{
			problem = trader.hasRoom() || trader.reboundSwap() != null
				? "Your budget has no room for this Rebound. Raise your budget to add it." : Rebounds.NO_ROOM;
		}
	}

	/** "Add to Trader" from the Market list, at the Market's prices for that flip. */
	private void addFromMarket(int id)
	{
		ItemCheck.Snapshot snapshot = marketFeed.snapshot();
		ItemCheck.Entry entry = snapshot == null ? null : snapshot.items.get(id);
		if (entry == null || entry.flip == null || !entry.flip.isValid() || trader.plan() == null)
		{
			problem = "That flip isn't available right now.";
			return;
		}
		String name = client.getItemDefinition(id).getName();
		if (!trader.add(id, name, (long) (double) entry.flip.qty, (long) (double) entry.flip.buy,
			(long) (double) entry.flip.sell, false, System.currentTimeMillis()))
		{
			problem = "It's already in your plan, or your plan has no free slot.";
			return;
		}
		SwingUtilities.invokeLater(() -> panel.showTrade());
	}

	private void notify(String message)
	{
		notifier.notify(config.flipNotification(), message);
	}

	private void step(String name)
	{
		stepsDue.add(name);
	}

	/** Reports each daily Trader step once per UTC day, together. */
	private void sendSteps(boolean signedIn)
	{
		String day = LocalDate.now(ZoneOffset.UTC).toString();
		if (!day.equals(stepsDay))
		{
			stepsDay = day;
			stepsSent.clear();
		}
		stepsDue.removeAll(stepsSent);
		if (!signedIn || stepsDue.isEmpty())
		{
			return;
		}
		List<String> steps = new ArrayList<>(stepsDue);
		stepsSent.addAll(steps);
		stepsDue.clear();
		api.activity(config.traderKey(), steps);
	}

	/** Trader's own plan items notify through Trader, so the Market guide stays quiet about them. */
	boolean handles(int itemId)
	{
		return trader != null && signedIn() && trader.planned(itemId);
	}

	/** The next two picks a free slot would get. */
	private List<TradePanel.UpNext> upNext(boolean signedIn, long now)
	{
		List<TradePanel.UpNext> list = new ArrayList<>();
		if (signedIn)
		{
			for (PlanFeed.Pick pick : trader.upNext(planFeed(), now, 2))
			{
				list.add(new TradePanel.UpNext(pick.id, pick.name, Math.round(pick.expectedEach * pick.qty)));
			}
		}
		return list;
	}

	/** How old the prices are, from the plan feed while signed in, else the Market's; null when unknown. */
	private Long pricesAge(boolean signedIn, long now)
	{
		PlanFeed.Feed plan = planFeed();
		Long planAge = signedIn && plan != null ? plan.ageMs(now) : null;
		if (planAge != null)
		{
			return planAge;
		}
		ItemCheck.Snapshot snapshot = marketFeed.snapshot();
		if (snapshot != null && snapshot.sourceAt != null)
		{
			// Both on the server's clock, so this computer's clock doesn't matter.
			return snapshot.serverDate - snapshot.sourceAt + (now - snapshot.receivedAt);
		}
		return null;
	}

	/**
	 * In a Leagues, Deadman or beta world, which keep their own GE apart from the main game's. The
	 * world type is known from the world picked, so offers sent while logging in are caught too.
	 */
	private boolean otherWorld()
	{
		return !ItemCheckOverlay.mainEconomy(client);
	}

	private void updatePanel(boolean signedIn, Trader.Step next, long now, boolean otherWorld)
	{
		if (tradePanel == null)
		{
			return;
		}
		Long budget = trader.budget();
		Long typed = trader.plan() == null ? null : trader.plan().budget;
		boolean freeWorld = client.getGameState() == GameState.LOGGED_IN && !client.getWorldType().contains(WorldType.MEMBERS);
		TradePanel.View view = new TradePanel.View(signedIn, code, problem, offline, next,
			signedIn ? trader.slots(now, planFeed()) : Collections.emptyList(), signedIn ? trader.invested() : 0,
			signedIn ? trader.rebound() : null, signedIn ? trader.todaySoFar() : null, budget, coins, freeWorld, moment, upNext(signedIn, now),
			signedIn && now - linkedAt < MOMENT_MS, best, signedIn && trader.hasRoom(), swapName(signedIn), otherWorld,
			signedIn ? makeSellCard : null);
		String status;
		Color tone;
		Long age = pricesAge(signedIn, now);
		if (offline)
		{
			status = "Can't reach Gielinor Gains";
			tone = Brand.AMBER;
		}
		else if (age == null)
		{
			status = "Checking prices";
			tone = Brand.MUTED;
		}
		else
		{
			// Prices are usually 5 to 10 minutes old; amber as they near the 15 minutes they can be used for.
			status = "Prices from " + BoxText.pricesAge(age);
			tone = age <= 12 * 60_000L ? Brand.MADE_MONEY : Brand.AMBER;
		}
		SwingUtilities.invokeLater(() -> panel.setStatus(status, tone, tone == Brand.MADE_MONEY));
		for (Trader.Achievement earned : trader.takeEarned())
		{
			newAchievement = earned.id;
			chat.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE).runeLiteFormattedMessage(
				new ChatMessageBuilder().append(ChatColorType.HIGHLIGHT).append("Achievement earned: " + earned.title + ". ")
					.append(ChatColorType.NORMAL).append(earned.text).build()).build());
		}
		if (!signedIn)
		{
			newAchievement = null;
		}
		Trader.Summary summary = signedIn ? trader.progressSoFar(now) : null;
		String fresh = newAchievement;
		SwingUtilities.invokeLater(() -> progressPanel.show(signedIn, summary, fresh));
		// The slots' minutes left change each minute, so the view does too; equal views aren't redrawn.
		if (view.equals(shownKey))
		{
			return;
		}
		shownKey = view;
		boolean planLoaded = signedIn && trader.plan() != null;
		SwingUtilities.invokeLater(() ->
		{
			tradePanel.show(view);
			panel.showSettings(signedIn, typed);
			market.setTrader(planLoaded, id -> clientThread.invokeLater(() -> addFromMarket(id)));
		});
	}
}
