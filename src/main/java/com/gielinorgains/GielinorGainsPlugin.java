package com.gielinorgains;

import com.google.inject.Provides;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SoundEffectID;
import net.runelite.api.ItemComposition;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
	name = "Gielinor Gains",
	description = "Free Trader and Market from Gielinor Gains, with step-by-step help at the GE. Trader needs a free sign-in",
	tags = {"ge", "grand exchange", "flipping", "prices", "profit", "market", "trader"}
)
public class GielinorGainsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private GielinorGainsConfig config;

	@Inject
	private ItemCheckOverlay overlay;

	@Inject
	private ItemCheckFeed feed;

	@Inject
	private Guide guide;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Notifier notifier;

	@Inject
	private TraderController trader;

	@Inject
	private TraderOverlay traderOverlay;

	@Inject
	private TraderOverlay.Held heldOverlay;

	@Inject
	private PlanFeed planFeed;

	@Inject
	private GearUpController gearUp;

	private MarketPanel panel;
	private GameState lastState;
	private NavigationButton button;

	// Client thread only: names and members flags from the game, and what the panel last showed.
	private final Map<Integer, MarketList.Item> items = new HashMap<>();
	private Object shownKey;
	private long shownMinute;
	private Object nextKey;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		// The guide's state lives on the client thread, so a pick in the panel is handed over to it.
		panel = new MarketPanel(itemManager::getImage, id -> clientThread.invokeLater(() ->
		{
			MarketList.Item item = id > 0 ? item(id) : null;
			guide.pin(item == null ? -1 : id, item == null ? null : item.getName());
			// A buy for it already in a GE slot is followed, rather than starting a second one.
			GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
			for (int slot = 0; offers != null && slot < offers.length; slot++)
			{
				if (offers[slot] != null)
				{
					follow(slot, offers[slot]);
				}
			}
		}));
		overlayManager.add(traderOverlay);
		overlayManager.add(heldOverlay);
		java.awt.image.BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		trader.start(this, icon);
		button = NavigationButton.builder()
			.tooltip("Gielinor Gains")
			.icon(icon)
			.priority(7)
			.panel(trader.buildPanel(panel, gearUp.buildPanel(itemManager::getImage), gearUp::onShown, itemManager::getImage, icon))
			.build();
		if (config.showMarketList())
		{
			clientToolbar.addNavigation(button);
		}
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(traderOverlay);
		overlayManager.remove(heldOverlay);
		clientToolbar.removeNavigation(button);
		feed.reset();
		planFeed.reset();
		gearUp.reset();
		clientThread.invoke(trader::stop);
		clientThread.invoke(() -> guide.pin(-1, null));
		shownKey = null;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (GielinorGainsConfig.GROUP.equals(event.getGroup()) && GielinorGainsConfig.GEAR_KEY.equals(event.getKey()))
		{
			clientThread.invokeLater(gearUp::onConfigChanged);
		}
		if (GielinorGainsConfig.GROUP.equals(event.getGroup()) && "showMarketList".equals(event.getKey()))
		{
			if (config.showMarketList())
			{
				clientToolbar.addNavigation(button);
			}
			else
			{
				clientToolbar.removeNavigation(button);
			}
		}
	}

	/** Opening the GE fetches early, so the numbers are ready by the time an item is picked. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS && overlay.canShow())
		{
			feed.refreshIfDue();
		}
	}

	/** The guide follows only the pinned flip's own offers, in memory. Nothing is saved or sent. */
	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (clearedByClient(event.getOffer().getState(), client.getGameState()))
		{
			return;
		}
		trader.onOffer(event.getSlot(), event.getOffer());
		follow(event.getSlot(), event.getOffer());
	}

	/**
	 * The client empties every slot while logging in or hopping, then sends the real offers. Those
	 * empties aren't collections, so they're ignored, as RuneLite's own GE plugin ignores them: else a
	 * sale that finished during a hop would never be recorded.
	 */
	static boolean clearedByClient(GrandExchangeOfferState state, GameState game)
	{
		return state == GrandExchangeOfferState.EMPTY && game != GameState.LOGGED_IN;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		trader.onItems(event.getContainerId());
		gearUp.onItems(event.getContainerId());
	}

	private void follow(int slot, GrandExchangeOffer offer)
	{
		ItemCheck.Snapshot snapshot = feed.snapshot();
		ItemCheck.Entry entry = snapshot == null || !snapshot.fresh ? null : snapshot.items.get(offer.getItemId());
		ItemCheck.Flip flip = entry == null ? null : entry.flip;
		Guide.Event event = guide.onOffer(slot, offer.getItemId(), offer.getState(), offer.getQuantitySold(), flip);
		// Trader tells the player about its own plan items, once.
		if (event == Guide.Event.NONE || trader.handles(offer.getItemId()))
		{
			return;
		}
		// The player's own RuneLite notification settings decide how this shows, or whether it does.
		notifier.notify(config.flipNotification(), event == Guide.Event.FILLED ? guide.filledMessage(flip) : guide.soldMessage());
		if (config.flipSound())
		{
			// The game's own GE sounds, at the player's sound effect volume.
			client.playSoundEffect(event == Guide.Event.FILLED ? SoundEffectID.GE_ADD_OFFER_DINGALING : SoundEffectID.GE_COIN_TINKLE);
		}
	}

	/**
	 * Once the pinned flip has sold, offers the next one: the highest expected profit not already in
	 * a GE slot. It's only offered; the player pins it with one click, or doesn't.
	 */
	private void updateNextFlip()
	{
		boolean sold = guide.itemId() > 0 && guide.phase() == Guide.Phase.DONE;
		if (!sold)
		{
			// Nothing sold: nothing to work out, beyond clearing an offer shown before.
			if (nextKey != null)
			{
				nextKey = null;
				guide.setNext(null);
				SwingUtilities.invokeLater(() -> panel.setSold(false, -1, null));
			}
			return;
		}
		ItemCheck.Snapshot snapshot = feed.snapshot();
		boolean members = client.getWorldType().contains(WorldType.MEMBERS);
		Set<Integer> skip = new HashSet<>();
		skip.add(guide.itemId());
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; offers != null && i < offers.length; i++)
		{
			if (offers[i] != null && offers[i].getState() != GrandExchangeOfferState.EMPTY)
			{
				skip.add(offers[i].getItemId());
			}
		}
		Object key = Arrays.asList(snapshot, members, skip);
		if (key.equals(nextKey))
		{
			return;
		}
		nextKey = key;
		boolean fresh = snapshot != null && BoxText.status(snapshot, feed.lastFailed(), System.currentTimeMillis()) == null;
		int next = fresh ? MarketList.next(snapshot, members, skip, this::item) : -1;
		String name = next > 0 ? item(next).getName() : null;
		guide.setNext(name);
		SwingUtilities.invokeLater(() -> panel.setSold(true, next, name));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Only a real login or world hop sends the burst of offers that aren't new trades.
		if (event.getGameState() == GameState.LOGGED_IN && (lastState == GameState.LOGGING_IN || lastState == GameState.HOPPING))
		{
			trader.onLoggedIn();
		}
		if (event.getGameState() != GameState.LOADING)
		{
			lastState = event.getGameState();
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			showView(MarketList.message(MarketList.LOG_IN), "log in");
		}
	}

	/**
	 * Fetches only while the GE offer setup or the Market list is open, and the feed itself waits
	 * at least 5 minutes between downloads. Nothing runs on a timer while both are closed.
	 */
	@Subscribe
	public void onGameTick(GameTick tick)
	{
		trader.onTick();
		gearUp.onTick();
		boolean listOpen = panel.isActive() && config.showMarketList();
		if (listOpen && !ItemCheckOverlay.mainEconomy(client))
		{
			showView(MarketList.message(MarketList.OTHER_WORLD), "other world");
			return;
		}
		if (listOpen || overlay.setupItem() > 0)
		{
			feed.refreshIfDue();
		}
		updateNextFlip();
		if (listOpen)
		{
			updatePanel();
		}
	}

	/** Rebuilds the rows when the prices or the world change; otherwise just counts the age up each minute. */
	private void updatePanel()
	{
		ItemCheck.Snapshot snapshot = feed.snapshot();
		boolean failed = feed.lastFailed();
		long now = System.currentTimeMillis();
		boolean members = client.getWorldType().contains(WorldType.MEMBERS);
		boolean fresh = BoxText.status(snapshot, failed, now) == null;
		if (!Arrays.asList(snapshot, failed, members, fresh).equals(shownKey))
		{
			showView(MarketList.build(snapshot, failed, now, members, this::item), Arrays.asList(snapshot, failed, members, fresh));
		}
		else if (now / 60_000 != shownMinute)
		{
			List<BoxText.Line> header = MarketList.header(snapshot, failed, now);
			SwingUtilities.invokeLater(() -> panel.setStatus(header));
		}
		shownMinute = now / 60_000;
	}

	private void showView(MarketList.View view, Object key)
	{
		if (!key.equals(shownKey))
		{
			shownKey = key;
			SwingUtilities.invokeLater(() -> panel.setView(view));
		}
	}

	/** The game's name and members flag for an item, remembered: they never change. */
	private MarketList.Item item(int id)
	{
		return items.computeIfAbsent(id, i ->
		{
			ItemComposition composition = client.getItemDefinition(i);
			String name = composition.getName();
			return name == null || name.equals("null") ? null : new MarketList.Item(name, composition.isMembers());
		});
	}

	@Provides
	GielinorGainsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GielinorGainsConfig.class);
	}
}
