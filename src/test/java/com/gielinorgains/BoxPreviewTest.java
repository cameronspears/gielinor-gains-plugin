package com.gielinorgains;

import static com.gielinorgains.Fixtures.MINUTE;
import static com.gielinorgains.Fixtures.SOURCE_AT;
import static com.gielinorgains.Fixtures.STALE_AT;
import static org.junit.Assert.assertEquals;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import javax.imageio.ImageIO;
import javax.swing.UIManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.http.api.RuneLiteAPI;
import net.runelite.client.config.FontType;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * Draws every box with RuneLite's own panel, lines and fonts, the way the overlay does, into
 * build/previews/. The README's pictures are copied from there (docs/images/). Also checks the
 * box keeps one width in every state and font.
 */
public class BoxPreviewTest
{
	private static final int SCALE = 2;

	@Test
	public void drawsEveryState() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		ItemCheck.Response outdated = Fixtures.itemCheck();
		outdated.minPlugin = ItemCheck.PLUGIN_VERSION + 1;
		long now = SOURCE_AT + 6 * MINUTE;

		Map<String, BoxText.Box> boxes = new LinkedHashMap<>();
		boxes.put("flip", BoxText.forItem(snapshot, false, 2, false, now));
		boxes.put("not-suggested", BoxText.forItem(snapshot, false, 4151, false, now));
		TraderApi.Price ge = new TraderApi.Price();
		ge.low = 1_402_110L;
		ge.high = 1_431_000L;
		ge.lowAt = now / 1000 - 60;
		ge.highAt = now / 1000 - 120;
		boxes.put("not-suggested-ge-price", BoxText.forItem(snapshot, false, 4151, false, now, ge));
		TraderApi.Price tested = new TraderApi.Price();
		tested.offers = new TraderApi.Offers();
		tested.offers.buy = 1_405_000;
		tested.offers.sell = 1_428_000;
		tested.offers.sellAfterTax = 1_399_440;
		tested.offers.activity = "busy";
		tested.offers.at = now / 1000 - 240;
		boxes.put("not-suggested-tested-offers", BoxText.forItem(snapshot, false, 4151, false, now, tested));
		TraderApi.Price crossed = new TraderApi.Price();
		crossed.offers = new TraderApi.Offers();
		crossed.offers.buy = 10_344_754;
		crossed.offers.sell = 9_563_919;
		crossed.offers.sellAfterTax = 8_607_527;
		crossed.offers.activity = "hourly";
		crossed.offers.at = now / 1000 - 60;
		boxes.put("not-suggested-crossed", BoxText.forItem(snapshot, false, ItemID.OSRS_BOND, false, now, crossed));
		boxes.put("too-expensive", BoxText.forItem(snapshot, false, 20997, false, now));
		boxes.put("stale", BoxText.forItem(snapshot, false, 2, false, STALE_AT + 7 * MINUTE));
		boxes.put("cant-reach", BoxText.forItem(snapshot, true, 2, false, STALE_AT + 7 * MINUTE));
		boxes.put("loading", BoxText.forItem(null, false, 2, false, now));
		boxes.put("out-of-date", BoxText.forItem(ItemCheck.Snapshot.of(outdated, 0, 0), false, 2, false, now));

		Path out = Paths.get("build", "previews");
		Files.createDirectories(out);
		Map<String, FontType> fonts = new LinkedHashMap<>();
		fonts.put("small", FontType.SMALL);
		fonts.put("regular", FontType.REGULAR);
		fonts.put("bold", FontType.BOLD);
		for (Map.Entry<String, FontType> font : fonts.entrySet())
		{
			for (Map.Entry<String, BoxText.Box> box : boxes.entrySet())
			{
				BufferedImage image = draw(box.getValue(), font.getValue());
				assertEquals(box.getKey() + " in " + font.getKey(), ItemCheckOverlay.WIDTH * SCALE, image.getWidth());
				ImageIO.write(image, "png", out.resolve(box.getKey() + "-" + font.getKey() + ".png").toFile());
			}
		}
	}

	@Test
	public void drawsTheGuideSteps() throws Exception
	{
		ItemCheck.Flip flip = Fixtures.snapshot().items.get(2).flip;
		Guide guide = new Guide();
		guide.pin(2, "Cannonball");
		Guide.GameView index = new Guide.GameView();
		java.util.Arrays.fill(index.slotItem, -1);
		java.util.Arrays.fill(index.slotState, net.runelite.api.GrandExchangeOfferState.EMPTY);
		Guide.GameView search = copy(index);
		search.prompt = Guide.Prompt.SEARCH;
		search.searchKeys = "cannonb";
		search.typed = "can";
		Guide.GameView typing = copy(index);
		typing.setupOpen = true;
		typing.setupItem = 2;
		typing.setupQuantity = 1;
		typing.prompt = Guide.Prompt.QUANTITY;
		typing.typed = "11500";
		Guide.GameView confirm = copy(index);
		confirm.setupOpen = true;
		confirm.setupItem = 2;
		confirm.setupQuantity = 11_000;
		confirm.setupPrice = 259;

		Map<String, BoxText.Box> boxes = new LinkedHashMap<>();
		boxes.put("guide-buy", BoxText.guide("Cannonball", guide.phase(), guide.step(index, flip, true), "tip"));
		boxes.put("guide-search", BoxText.guide("Cannonball", guide.phase(), guide.step(search, flip, true), "tip", false,
			search.typed));
		boxes.put("guide-type", BoxText.guide("Cannonball", guide.phase(), guide.step(typing, flip, true), "tip"));
		boxes.put("guide-confirm", BoxText.guide("Cannonball", guide.phase(), guide.step(confirm, flip, true), "tip"));
		guide.onOffer(0, 2, net.runelite.api.GrandExchangeOfferState.BOUGHT, 11_000, flip);
		Guide.GameView uncollected = copy(index);
		uncollected.slotItem[0] = 2;
		uncollected.slotState[0] = net.runelite.api.GrandExchangeOfferState.BOUGHT;
		boxes.put("guide-collect", BoxText.guide("Cannonball", guide.phase(), guide.step(uncollected, flip, true), "tip", true));
		guide.onOffer(0, -1, net.runelite.api.GrandExchangeOfferState.EMPTY, 0, null);
		boxes.put("guide-sell", BoxText.guide("Cannonball", guide.phase(), guide.step(index, flip, true), "tip"));
		guide.onOffer(1, 2, net.runelite.api.GrandExchangeOfferState.SOLD, 11_000, flip);
		guide.onOffer(1, -1, net.runelite.api.GrandExchangeOfferState.EMPTY, 0, null);
		guide.setNext("Saradomin brew(4)");
		boxes.put("guide-sold", BoxText.guide("Cannonball", guide.phase(), guide.step(index, flip, true), "tip"));

		Path out = Paths.get("build", "previews");
		Files.createDirectories(out);
		for (Map.Entry<String, BoxText.Box> box : boxes.entrySet())
		{
			BufferedImage image = draw(box.getValue(), FontType.SMALL);
			assertEquals(box.getKey(), ItemCheckOverlay.WIDTH * SCALE, image.getWidth());
			ImageIO.write(image, "png", out.resolve(box.getKey() + ".png").toFile());
		}
	}

	private static Guide.GameView copy(Guide.GameView from)
	{
		Guide.GameView to = new Guide.GameView();
		System.arraycopy(from.slotItem, 0, to.slotItem, 0, from.slotItem.length);
		System.arraycopy(from.slotState, 0, to.slotState, 0, from.slotState.length);
		return to;
	}

	@Test
	public void drawsTheMarketList() throws Exception
	{
		UIManager.setLookAndFeel(new RuneLiteLAF());
		ItemCheck.Response sample = RuneLiteAPI.GSON.fromJson(Fixtures.text("item-check-preview.json"),
			ItemCheck.Response.class);
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(sample, SOURCE_AT + 6 * MINUTE, SOURCE_AT + 6 * MINUTE);
		Map<Integer, MarketList.Item> game = new HashMap<>();
		game.put(2, new MarketList.Item("Cannonball", true));
		game.put(561, new MarketList.Item("Nature rune", false));
		game.put(12934, new MarketList.Item("Zulrah's scales", true));
		game.put(6685, new MarketList.Item("Saradomin brew(4)", true));
		game.put(5295, new MarketList.Item("Ranarr seed", true));
		game.put(536, new MarketList.Item("Dragon bones", false));
		long now = SOURCE_AT + 6 * MINUTE;

		Map<String, MarketList.View> views = new LinkedHashMap<>();
		views.put("list", MarketList.build(snapshot, false, now, true, game::get));
		views.put("list-free-world", MarketList.build(snapshot, false, now, false, game::get));
		views.put("list-pinned", MarketList.build(snapshot, false, now, true, game::get));
		views.put("list-sold", MarketList.build(snapshot, false, now, true, game::get));
		views.put("list-loading", MarketList.build(null, false, now, true, game::get));
		views.put("list-cant-reach", MarketList.build(snapshot, true, STALE_AT, true, game::get));
		views.put("list-log-in", MarketList.message(MarketList.LOG_IN));
		views.put("list-other-world", MarketList.message(MarketList.OTHER_WORLD));
		views.put("list-empty", MarketList.build(snapshot, false, now, false, id -> new MarketList.Item("x", true)));

		Path out = Paths.get("build", "previews");
		Files.createDirectories(out);
		for (Map.Entry<String, MarketList.View> view : views.entrySet())
		{
			MarketPanel panel = new MarketPanel(id -> placeholderIcon(), id -> { });
			panel.setView(view.getValue());
			if (view.getKey().equals("list-pinned"))
			{
				panel.pin(12934, "Zulrah's scales");
			}
			if (view.getKey().equals("list-sold"))
			{
				panel.pin(12934, "Zulrah's scales");
				panel.setSold(true, 6685, "Saradomin brew(4)");
			}
			panel.setSize(panel.getPreferredSize().width, 520);
			layout(panel);
			BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			panel.printAll(g);
			g.dispose();
			ImageIO.write(image, "png", out.resolve(view.getKey() + ".png").toFile());
		}
	}

	/** Game icons need the game, so the pictures show the empty lit tile where each icon goes. */
	private static AsyncBufferedImage placeholderIcon()
	{
		return new AsyncBufferedImage(null, 32, 32, BufferedImage.TYPE_INT_ARGB);
	}

	private static void layout(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layout(child);
			}
		}
	}

	private static BufferedImage draw(BoxText.Box box, FontType font)
	{
		PanelComponent panel = new PanelComponent();
		ItemCheckOverlay.prepare(panel);
		ItemCheckOverlay.fill(panel, box);

		BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = scratch.createGraphics();
		g.setFont(font.getFont());
		// As in the overlay: lay it out twice, then draw.
		panel.render(g);
		Dimension size = panel.render(g);
		g.dispose();

		BufferedImage image = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		g = image.createGraphics();
		g.setFont(font.getFont());
		Brand.card(g, 0, 0, size.width, size.height, Brand.BORDER);
		panel.render(g);
		g.dispose();

		// Twice the size, pixel for pixel, so the game font stays sharp on the page.
		BufferedImage big = new BufferedImage(size.width * SCALE, size.height * SCALE, BufferedImage.TYPE_INT_ARGB);
		g = big.createGraphics();
		g.drawImage(image.getScaledInstance(size.width * SCALE, size.height * SCALE, Image.SCALE_REPLICATE), 0, 0, null);
		g.dispose();
		return big;
	}
}
