package com.gielinorgains;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.Text;

/**
 * The box beside the GE offer setup. Each frame it reads which item is being set up, with no
 * stored game state, and draws nothing when no buy or sell is being set up.
 */
@Singleton
class ItemCheckOverlay extends Overlay
{
	/** The plugin's only link. The site redirects it to the item's page. */
	static final String ITEM_PAGE = TraderApi.SITE + "/items/id/";

	/** Worlds with their own economy or no GE, where the box never shows and nothing is fetched. */
	private static final Set<WorldType> OTHER_ECONOMY = EnumSet.of(
		WorldType.SEASONAL, WorldType.DEADMAN, WorldType.FRESH_START_WORLD, WorldType.BETA_WORLD,
		WorldType.NOSAVE_MODE, WorldType.TOURNAMENT_WORLD, WorldType.QUEST_SPEEDRUNNING);

	/** The box's padding: roomier than RuneLite's 4px, like the site's cards. */
	private static final Rectangle PADDING = new Rectangle(8, 7, 8, 7);

	/** Sets a panel up as the box: one width, our padding, and no RuneLite background (we draw the card). */
	static void prepare(PanelComponent panel)
	{
		panel.setPreferredSize(new Dimension(WIDTH, 0));
		panel.setBackgroundColor(null);
		panel.setBorder(PADDING);
	}

	/** One width in every state, so the box never jumps sideways. */
	static final int WIDTH = 218;
	static final int GAP = 6;
	/** How often the words are worked out again while nothing else changes, so the age keeps counting. */
	private static final long TEXT_REFRESH_MS = 1_000;

	private final Client client;
	private final ItemManager itemManager;
	private final GielinorGainsConfig config;
	private final ItemCheckFeed feed;
	private final TooltipManager tooltipManager;
	private final Guide guide;
	private final PanelComponent panel = new PanelComponent();
	private final Point at = new Point();
	private final Guide.GameView game = new Guide.GameView();
	private static final BasicStroke OUTLINE = new BasicStroke(2f);
	private static final BasicStroke HALO = new BasicStroke(5f);
	static final String GUIDE_TIP = "We point and explain. You do every click and key press yourself.";

	private long guideKey;
	private Color haloOf;
	private Color halo;
	private Guide.Step step;
	private Guide.Step shownStep;
	private final ConfigManager configManager;
	/** The cursor tooltip over the outlined thing, made once per step. */
	private Tooltip hoverTip;
	private boolean tipShown;
	private int inventoryIndex = -1;

	private int shownItemId = -1;
	private BoxText.Box box;
	private Tooltip tooltip;
	private Object boxSnapshot;
	private boolean boxFailed;
	private boolean boxSell;
	private long boxAt;

	/** The chatbox mode of the GE's item search (RuneLite's InputType.GE). */
	private static final int GE_SEARCH_MODE = 14;

	private final PriceChecks prices;
	private int searchKeysFor = -1;
	private String searchKeys;
	private TraderApi.Price boxPrice;

	private int planFlipId = -1;
	private ItemCheck.Flip planFlip;

	@Inject
	ItemCheckOverlay(Client client, ItemManager itemManager, GielinorGainsConfig config, ItemCheckFeed feed,
		TooltipManager tooltipManager, Guide guide, ConfigManager configManager, PriceChecks prices)
	{
		this.prices = prices;
		this.guide = guide;
		this.configManager = configManager;
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		this.feed = feed;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setMovable(false);
		setResettable(false);
		prepare(panel);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Open", "Gielinor Gains item page", e -> openItemPage());
	}

	/** Client thread: the Trader plan item the guide follows, at the plan's prices, or null. */
	void setPlanFlip(int itemId, ItemCheck.Flip flip)
	{
		planFlipId = itemId;
		planFlip = flip;
	}

	/** Whether the GE's offer setup is a sale. */
	boolean setupSell()
	{
		return isSell();
	}

	/** Whether this world uses the main GE and at least one setting shows the box. */
	boolean canShow()
	{
		return (config.showWhenBuying() || config.showWhenSelling()) && mainEconomy(client);
	}

	/** Whether this world trades on the main GE, so our prices apply. */
	static boolean mainEconomy(Client client)
	{
		return Collections.disjoint(client.getWorldType(), OTHER_ECONOMY);
	}

	/** The item in the offer setup, or -1 when there's none or the setting for this side is off. */
	int setupItem()
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden() || !canShow())
		{
			return -1;
		}
		int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		boolean shown = isSell() ? config.showWhenSelling() : config.showWhenBuying();
		return itemId > 0 && shown ? itemManager.canonicalize(itemId) : -1;
	}

	private boolean isSell()
	{
		return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Widget frame = client.getWidget(InterfaceID.GeOffers.FRAME);
		Guide.Step next = frame == null ? null : guideStep();
		int itemId = next != null ? guide.itemId() : setupItem();
		if (frame == null || itemId <= 0)
		{
			shownItemId = -1;
			return null;
		}
		boolean changed = next != null ? updateGuideText(next) : updateText(itemId, isSell());
		if (changed)
		{
			// Lay the new words out out of sight first. RuneLite's panel sizes its background from the
			// previous layout, so two passes give the right size before the box is shown or placed.
			Graphics2D measure = (Graphics2D) graphics.create();
			measure.setClip(0, 0, 0, 0);
			panel.render(measure);
			panel.render(measure);
			measure.dispose();
		}
		if (next != null)
		{
			Widget target = target(next);
			if (!onScreen(target))
			{
				target = null;
			}
			net.runelite.api.Point cursor = client.getMouseCanvasPosition();
			boolean hovered = target != null && cursor != null && target.getBounds().contains(cursor.getX(), cursor.getY());
			outline(graphics, target, hovered);
			if (hoverTip != null && hovered && !client.isMenuOpen())
			{
				tooltipManager.add(hoverTip);
			}
		}

		Rectangle bounds = getBounds();
		Rectangle size = panel.getBounds();
		boolean typing = next != null && (game.prompt == Guide.Prompt.QUANTITY || game.prompt == Guide.Prompt.PRICE);
		place(frame.getBounds(), client.getCanvasWidth(), client.getCanvasHeight(), size.width, size.height,
			client.isResized(), typing, at);
		// A dynamic overlay is drawn where it was last frame, so move it to where it belongs now.
		graphics.translate(at.x - bounds.x, at.y - bounds.y);
		bounds.setLocation(at);
		Brand.card(graphics, 0, 0, size.width, size.height, Brand.BORDER);
		Dimension drawn = panel.render(graphics);

		net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (drawn != null && mouse != null && !client.isMenuOpen()
			&& mouse.getX() >= at.x && mouse.getX() < at.x + drawn.width
			&& mouse.getY() >= at.y && mouse.getY() < at.y + drawn.height)
		{
			tooltipManager.add(tooltip);
		}
		return drawn;
	}

	/**
	 * The guide's next step for the pinned flip, or null to show the usual item check. Reads the
	 * game into the reused view, and works the step out again only when something it reads changed.
	 */
	private Guide.Step guideStep()
	{
		if (!config.guideOffers() || guide.itemId() <= 0)
		{
			return null;
		}
		Set<WorldType> world = client.getWorldType();
		if (!Collections.disjoint(world, OTHER_ECONOMY))
		{
			return null;
		}
		read(world);
		ItemCheck.Snapshot snapshot = feed.snapshot();
		boolean fresh = BoxText.status(snapshot, feed.lastFailed(), System.currentTimeMillis()) == null;
		// A Trader plan item is guided at the plan's own prices and quantity.
		ItemCheck.Flip plan = planFlip != null && planFlipId == guide.itemId() ? planFlip : null;
		long key = signature(snapshot, fresh) * 31 + System.identityHashCode(plan);
		if (key != guideKey || step == null)
		{
			guideKey = key;
			ItemCheck.Entry entry = fresh ? snapshot.items.get(guide.itemId()) : null;
			step = plan != null ? guide.step(game, plan, true) : guide.step(game, entry == null ? null : entry.flip, fresh);
		}
		return step;
	}

	/** Copies what the guide needs from the game. Only reads; nothing in the game changes. */
	private void read(Set<WorldType> world)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		game.setupOpen = setup != null && !setup.isHidden();
		Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
		game.detailsOpen = !game.setupOpen && details != null && !details.isHidden();
		// The game counts slots from 1, with 0 for none.
		game.selectedSlot = client.getVarbitValue(VarbitID.GE_SELECTEDSLOT) - 1;
		int item = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		game.setupItem = game.setupOpen && item > 0 ? itemManager.canonicalize(item) : -1;
		game.setupSell = isSell();
		game.setupQuantity = client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
		game.setupPrice = client.getVarpLongValue(VarPlayerID.GE_NEWOFFER_PRICE);
		game.prompt = prompt();
		game.typed = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
		game.searchKeys = searchKeys(guide.itemId(), guide.name());
		Widget result = game.prompt == Guide.Prompt.SEARCH ? searchResult() : null;
		game.resultShown = result != null && onScreen(result);
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		for (int i = 0; i < game.slotItem.length; i++)
		{
			GrandExchangeOffer offer = offers == null ? null : offers[i];
			boolean empty = offer == null || offer.getState() == GrandExchangeOfferState.EMPTY;
			game.slotItem[i] = empty ? -1 : offer.getItemId();
			game.slotState[i] = empty ? GrandExchangeOfferState.EMPTY : offer.getState();
		}
		// Free worlds open only the first 3 GE slots.
		game.usableSlots = world.contains(WorldType.MEMBERS) ? 8 : 3;
	}

	/**
	 * The shortest search for the pinned item, worked out once per item from RuneLite's list of GE
	 * items. Null until that list has loaded, so the guide asks for the whole name meanwhile.
	 */
	private String searchKeys(int itemId, String name)
	{
		if (itemId != searchKeysFor)
		{
			searchKeysFor = itemId;
			searchKeys = null;
		}
		if (searchKeys == null && itemId > 0 && name != null && !itemManager.search(name).isEmpty())
		{
			searchKeys = Guide.shortestSearch(name, search -> itemManager.search(search).stream()
				.map(ItemPrice::getName).collect(Collectors.toList()));
		}
		return searchKeys;
	}

	/** Which chatbox prompt is open: the GE search by its chatbox mode, as RuneLite's GE plugin tells it, else by its question. */
	private Guide.Prompt prompt()
	{
		Widget layer = client.getWidget(InterfaceID.Chatbox.MES_LAYER);
		Widget question = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (layer == null || layer.isHidden() || question == null || question.getText() == null)
		{
			return Guide.Prompt.NONE;
		}
		String text = question.getText();
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) == GE_SEARCH_MODE || has(text, "would you like to buy"))
		{
			return Guide.Prompt.SEARCH;
		}
		return has(text, "how many") ? Guide.Prompt.QUANTITY : has(text, "price") ? Guide.Prompt.PRICE : Guide.Prompt.NONE;
	}

	private static boolean has(String text, String part)
	{
		for (int i = 0; i + part.length() <= text.length(); i++)
		{
			if (text.regionMatches(true, i, part, 0, part.length()))
			{
				return true;
			}
		}
		return false;
	}

	/** A cheap fingerprint of everything the step depends on, so it's rebuilt only when that changes. */
	private long signature(ItemCheck.Snapshot snapshot, boolean fresh)
	{
		long h = System.identityHashCode(snapshot) * 31L + (fresh ? 1 : 0);
		h = h * 31 + guide.itemId();
		h = h * 31 + guide.phase().ordinal();
		h = h * 31 + guide.version();
		h = h * 31 + (game.setupOpen ? 1 : 0);
		h = h * 31 + (game.detailsOpen ? 1 : 0);
		h = h * 31 + game.selectedSlot;
		h = h * 31 + (game.resultShown ? 1 : 0);
		h = h * 31 + game.setupItem;
		h = h * 31 + (game.setupSell ? 1 : 0);
		h = h * 31 + game.setupQuantity;
		h = h * 31 + game.setupPrice;
		h = h * 31 + game.prompt.ordinal();
		h = h * 31 + (game.typed == null ? 0 : game.typed.hashCode());
		h = h * 31 + (game.searchKeys == null ? 0 : game.searchKeys.hashCode());
		h = h * 31 + game.usableSlots;
		for (int i = 0; i < game.slotItem.length; i++)
		{
			h = h * 31 + game.slotItem[i];
			h = h * 31 + game.slotState[i].ordinal();
		}
		return h;
	}

	/** The widget a step points at, or null. Widgets are only looked up and read. */
	private Widget target(Guide.Step next)
	{
		switch (next.getTarget())
		{
			case BUY_BUTTON:
			case SLOT:
			{
				// The 8 slots are consecutive components; a free slot's first child is its Buy button.
				Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + next.getSlot());
				return slot == null || next.getTarget() == Guide.Target.SLOT ? slot : slot.getChild(0);
			}
			case SEARCH_RESULT:
				return searchResult();
			case ITEM_BUTTON:
				return setupChild(0);
			// The offer setup's own buttons, as RuneLite 1.13 lays them out (no gameval names exist for them).
			case QUANTITY_BUTTON:
				return setupChild(51);
			case PRICE_BUTTON:
				return setupChild(54);
			case CONFIRM:
				return setupChild(58);
			case INVENTORY_ITEM:
				return inventoryItem();
			case MODIFY:
				return modifyButton();
			case COLLECT:
				return collectBox();
			default:
				return null;
		}
	}

	/**
	 * The open offer's Modify button. DETAILS_MODIFY spans the whole offer, so the button is found
	 * inside it by its "Modify" action; with none found nothing is outlined, and the box still says it.
	 */
	private Widget modifyButton()
	{
		Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS_MODIFY);
		return details == null || details.isHidden() ? null : withAction(details, "modify", 0);
	}

	/** The first visible widget at or under {@code w} with an action containing {@code word}, skipping {@code w}'s own. */
	private static Widget withAction(Widget w, String word, int depth)
	{
		String[] actions = depth == 0 ? null : w.getActions();
		for (int i = 0; actions != null && i < actions.length; i++)
		{
			if (actions[i] != null && Text.removeTags(actions[i]).toLowerCase().contains(word))
			{
				return w;
			}
		}
		if (depth > 3)
		{
			return null;
		}
		for (Widget[] children : new Widget[][]{w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
		{
			for (int i = 0; children != null && i < children.length; i++)
			{
				Widget found = children[i] == null || children[i].isHidden() ? null : withAction(children[i], word, depth + 1);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private Widget setupChild(int index)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		return setup == null ? null : setup.getChild(index);
	}

	/** The search result row for the pinned item. Each result is 3 widgets: the row, its name, its icon. */
	private Widget searchResult()
	{
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		if (results == null || results.isHidden())
		{
			return null;
		}
		Widget[] children = results.getDynamicChildren();
		for (int i = 2; children != null && i < children.length; i += 3)
		{
			if (children[i] != null && children[i].getItemId() == guide.itemId())
			{
				return children[i - 2];
			}
		}
		// Before anything is typed, the game may offer the last item searched.
		Widget last = results.getChild(3);
		return last != null && last.getItemId() == guide.itemId() ? last : null;
	}

	/** The box under an open offer holding what this step collects: coins after a sale, else the items. */
	private Widget collectBox()
	{
		Widget collect = client.getWidget(InterfaceID.GeOffers.DETAILS_COLLECT);
		if (collect == null || collect.isHidden())
		{
			return null;
		}
		boolean coins = guide.phase() == Guide.Phase.DONE;
		for (Widget[] boxes : new Widget[][]{collect.getDynamicChildren(), collect.getStaticChildren()})
		{
			for (int i = 0; boxes != null && i < boxes.length; i++)
			{
				int id = boxes[i] == null || boxes[i].isHidden() ? -1 : boxes[i].getItemId();
				if (id > 0 && (coins ? id == ItemID.COINS : itemManager.canonicalize(id) == guide.itemId()))
				{
					return boxes[i];
				}
			}
		}
		return null;
	}

	/** The pinned item in the GE's inventory, noted or not. */
	private Widget inventoryItem()
	{
		Widget items = client.getWidget(InterfaceID.GeOffersSide.ITEMS);
		Widget[] children = items == null ? null : items.getDynamicChildren();
		if (children == null)
		{
			return null;
		}
		// Where it was last frame is almost always where it is now, so that's checked first.
		if (inventoryIndex >= 0 && inventoryIndex < children.length && matches(children[inventoryIndex]))
		{
			return children[inventoryIndex];
		}
		for (int i = 0; i < children.length; i++)
		{
			if (matches(children[i]))
			{
				inventoryIndex = i;
				return children[i];
			}
		}
		return null;
	}

	private boolean matches(Widget item)
	{
		int id = item.getItemId();
		return id > 0 && itemManager.canonicalize(id) == guide.itemId();
	}

	/**
	 * Whether a widget is really on screen: shown, with a size, and inside every layer above it. A
	 * search result scrolled out of view, or a leftover child the game keeps off to one side, has
	 * bounds but can't be seen, and an outline there would ring empty ground.
	 */
	static boolean onScreen(Widget widget)
	{
		if (widget == null || widget.isHidden())
		{
			return false;
		}
		Rectangle r = widget.getBounds();
		if (r == null || r.width <= 0 || r.height <= 0)
		{
			return false;
		}
		int cx = (int) r.getCenterX();
		int cy = (int) r.getCenterY();
		for (Widget up = widget.getParent(); up != null; up = up.getParent())
		{
			Rectangle around = up.getBounds();
			if (around == null || !around.contains(cx, cy))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Draws a ring just outside the widget, so it never covers the words it points at: a soft halo
	 * and a crisp line, like the site's focus ring. The halo breathes slowly so the eye finds it,
	 * and holds still once the cursor is on it.
	 */
	private void outline(Graphics2D graphics, Widget widget, boolean hovered)
	{
		if (widget == null)
		{
			return;
		}
		// RuneLite has moved the origin to where this box was placed, so the widget's canvas bounds are
		// moved back by as much. Without this the outline lands off to the side by the box's position.
		Rectangle r = new Rectangle(widget.getBounds());
		r.translate(-getBounds().x, -getBounds().y);
		Color color = config.guideColor();
		if (!color.equals(haloOf))
		{
			haloOf = color;
			halo = new Color(color.getRed(), color.getGreen(), color.getBlue(), 70);
		}
		// One breath every 2.4 seconds, between a faint and a full halo.
		double breath = hovered || config.reduceMotion() ? 1 : 0.5 + 0.5 * Math.sin(System.currentTimeMillis() % 2400 / 2400.0 * 2 * Math.PI);
		int alpha = (int) Math.round(30 + 60 * breath);
		java.awt.Stroke before = graphics.getStroke();
		Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setStroke(HALO);
		graphics.setColor(new Color(halo.getRed(), halo.getGreen(), halo.getBlue(), alpha));
		graphics.drawRoundRect(r.x - 4, r.y - 4, r.width + 7, r.height + 7, Brand.RADIUS, Brand.RADIUS);
		graphics.setStroke(OUTLINE);
		graphics.setColor(color);
		graphics.drawRoundRect(r.x - 3, r.y - 3, r.width + 5, r.height + 5, Brand.RADIUS, Brand.RADIUS);
		graphics.setStroke(before);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	/** Hides the Collect tip for good, in our own settings. Menu Entry Swapper's are never read or changed. */
	private void hideCollectTip()
	{
		configManager.setConfiguration(GielinorGainsConfig.GROUP, GielinorGainsConfig.COLLECT_TIP_SEEN, true);
		shownStep = null;
	}

	/** The box for a guide step: the flip, what to do now, and the check of what's set or typed. */
	private boolean updateGuideText(Guide.Step next)
	{
		if (next == shownStep && box != null && shownItemId == guide.itemId())
		{
			return false;
		}
		shownStep = next;
		hoverTip = next.getHover() == null ? null : new Tooltip(next.getHover());
		// The Collect tip shows under the first collect step only, until it ends or is hidden.
		boolean collectTip = next.getTarget() == Guide.Target.SLOT && !config.collectTipSeen();
		if (tipShown && !collectTip)
		{
			hideCollectTip();
		}
		if (collectTip != tipShown)
		{
			tipShown = collectTip;
			if (collectTip)
			{
				addMenuEntry(MenuAction.RUNELITE_OVERLAY, "Hide", "Gielinor Gains tip", e -> hideCollectTip());
			}
			else
			{
				removeMenuEntry(MenuAction.RUNELITE_OVERLAY, "Hide", "Gielinor Gains tip");
			}
		}
		BoxText.Box nextBox = BoxText.guide(guide.name(), guide.phase(), next, GUIDE_TIP, collectTip, game.typed,
			guide.planNote());
		shownItemId = guide.itemId();
		boxSnapshot = null;
		if (nextBox.equals(box))
		{
			return false;
		}
		box = nextBox;
		tooltip = new Tooltip(String.join("</br>", box.getTooltip()));
		fill(panel, box);
		return true;
	}

	/** Works the words out again when anything they depend on changed. Returns whether they did. */
	private boolean updateText(int itemId, boolean sell)
	{
		ItemCheck.Snapshot snapshot = feed.snapshot();
		boolean failed = feed.lastFailed();
		long now = System.currentTimeMillis();
		ItemCheck.Entry entry = snapshot == null ? null : snapshot.items.get(itemId);
		// Only an item with no flip needs its GE price; one with a flip shows the flip's offers.
		TraderApi.Price price = snapshot != null && (entry == null || entry.flip == null) ? prices.get(itemId, now) : null;
		if (box != null && itemId == shownItemId && sell == boxSell && snapshot == boxSnapshot && failed == boxFailed
			&& price == boxPrice && now - boxAt < TEXT_REFRESH_MS)
		{
			return false;
		}
		shownStep = null;
		BoxText.Box next = BoxText.forItem(snapshot, failed, itemId, sell, now, price);
		shownItemId = itemId;
		boxPrice = price;
		boxSell = sell;
		boxSnapshot = snapshot;
		boxFailed = failed;
		boxAt = now;
		if (next.equals(box))
		{
			return false;
		}
		box = next;
		tooltip = new Tooltip(String.join("</br>", box.getTooltip()));

		fill(panel, box);
		return true;
	}

	/** Puts the box's lines into a panel, in Gielinor Gains' look. The README's pictures use this too. */
	static void fill(PanelComponent panel, BoxText.Box box)
	{
		panel.getChildren().clear();
		for (BoxText.Line line : box.getLines())
		{
			if (line.getKind() == BoxText.Kind.SQUARES)
			{
				panel.getChildren().add(new Brand.Squares(line.getLeft()));
				continue;
			}
			if (line.getKind() == BoxText.Kind.KEYS)
			{
				panel.getChildren().add(new Brand.Keys(line.getLeft(), line.getRight()));
				continue;
			}
			boolean lead = line.getKind() == BoxText.Kind.LEAD;
			boolean warn = line.getKind() == BoxText.Kind.WARN;
			boolean bold = line.getKind() == BoxText.Kind.TITLE || warn || (lead && line.getRight() == null);
			panel.getChildren().add(LineComponent.builder()
				.left(line.getLeft())
				.right(line.getRight())
				.leftColor(warn ? Brand.AMBER : line.getKind() == BoxText.Kind.NOTE || line.getRight() != null ? Brand.MUTED
					: Brand.TEXT)
				.rightColor(Brand.TEXT)
				.leftFont(bold ? FontManager.getRunescapeBoldFont() : null)
				.rightFont(lead ? FontManager.getRunescapeBoldFont() : null)
				.build());
		}
	}

	/**
	 * Where the box goes, written into {@code out}. Fixed mode: over the minimap and orbs, which an
	 * offer doesn't need. Resizable: beside the GE window, right if there's room, else left, with
	 * its top level with the window's, so a taller box grows down instead of moving.
	 */
	static void place(Rectangle frame, int canvasWidth, int canvasHeight, int boxWidth, int boxHeight,
		boolean resized, boolean nearChatbox, Point out)
	{
		if (!resized)
		{
			out.setLocation(canvasWidth - boxWidth - GAP, GAP);
			return;
		}
		int x;
		if (frame.x + frame.width + GAP + boxWidth <= canvasWidth - GAP)
		{
			x = frame.x + frame.width + GAP;
		}
		else if (frame.x - GAP - boxWidth >= GAP)
		{
			x = frame.x - GAP - boxWidth;
		}
		else
		{
			x = Math.max(GAP, canvasWidth - boxWidth - GAP);
		}
		// While a number is being typed, the box sits low, level with the GE window's foot, nearer the chatbox.
		int y = nearChatbox ? frame.y + frame.height - boxHeight : frame.y;
		out.setLocation(x, Math.max(GAP, Math.min(y, canvasHeight - boxHeight - GAP)));
	}

	private void openItemPage()
	{
		if (shownItemId > 0)
		{
			LinkBrowser.browse(ITEM_PAGE + shownItemId);
		}
	}
}
