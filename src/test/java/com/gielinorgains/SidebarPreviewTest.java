package com.gielinorgains;

import static com.gielinorgains.TraderTest.GSON;
import static com.gielinorgains.TraderTest.T0;
import static com.gielinorgains.TraderTest.feed;
import static com.gielinorgains.TraderTest.next;
import static com.gielinorgains.TraderTest.pick;
import static com.gielinorgains.TraderTest.ready;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.UIManager;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;

/**
 * Draws the whole sidebar, tabs and all, in each Trade and Progress state into build/previews/sidebar-*.png,
 * with fictional trades, so its screens can be checked without a game.
 */
public class SidebarPreviewTest
{
	private static final TradePanel.Actions NO_ACTIONS = new TradePanel.Actions()
	{
		public void signIn() { }
		public void setBudget(long gp) { }
		public void skip(int itemId) { }
		public void dismiss(String stepKey) { }
		public void addRebound() { }
		public void dismissRebound() { }
		public void retry() { }
		public void openSettings() { }
		public void takeSwap() { }
		public void nextSwap() { }
		public void giveBackSwap() { }
		public void finishSwap() { }
	};

	@Test
	public void drawsEveryTradeAndProgressState() throws Exception
	{
		UIManager.setLookAndFeel(new RuneLiteLAF());
		Ui.reduceMotion = true;
		Path out = Paths.get("build", "previews");
		Files.createDirectories(out);

		Trader trader = ready(10_000_000);
		PlanFeed.Feed feed = feed(pick(2, "Steel cannonball", 259, 280, 1000), pick(453, "Coal", 150, 165, 2000),
			pick(564, "Cosmic rune", 104, 111, 9000));
		trader.refill(feed, T0 + 1000);
		long now = T0 + 2000;
		Trader.Step buy = next(trader, feed, now);
		Trader.Today today = new Trader.Today();
		List<TradePanel.UpNext> upNext = Arrays.asList(new TradePanel.UpNext(561, "Nature rune", 41_000));

		trade(out, "signed-out", view(false, null, null, false, null, Collections.emptyList(), null, today, null, false, null, false));
		trade(out, "sign-in-code", view(false, "K7QA-M9XR", null, false, null, Collections.emptyList(), null, today, null, false, null, false));
		trade(out, "sign-in-problem", view(false, null, "We couldn't start signing in. Try again in a minute.", false, null,
			Collections.emptyList(), null, today, null, false, null, false));
		trade(out, "other-world", new TradePanel.View(true, null, null, false, null, Collections.emptyList(), 0, null, today,
			10_000_000L, -1, false, null, Collections.emptyList(), false, null, true, null, true, null));
		trade(out, "budget", view(true, null, null, false, next(new Trader(GSON, T0), feed(), T0), Collections.emptyList(), null, today, null, false, null, true));
		trade(out, "buy", view(true, null, null, false, buy, trader.slots(now), null, today, 10_000_000L, false, null, true, upNext));
		// Scrolled partway in a short window: cards slide under the tabs, never over them.
		TradePanel scrolled = new TradePanel(NO_ACTIONS, id -> icon());
		scrolled.show(view(true, null, null, false, buy, trader.slots(now), null, today, 10_000_000L, false, null, true, upNext));
		drawScrolled(out, "sidebar-trade-scrolled", sidebar(scrolled, new ProgressPanel(), false), 90);
		trade(out, "offline", view(true, null, null, true, buy, trader.slots(now), null, today, 10_000_000L, false, null, true));

		// Buy, collect and sell the cannonballs, for a sell step and then a finished flip.
		trader.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		trader.onOffer(0, 2, "BOUGHT", 1000, 1000, 259, 259_000, T0 + 60_000, true);
		trader.onOffer(0, 2, "EMPTY", 0, 0, 0, 0, T0 + 62_000, true);
		Trader.Step sell = next(trader, feed, T0 + 62_000);
		trade(out, "sell", view(true, null, null, false, sell, trader.slots(T0 + 62_000), null, today, 10_000_000L, false, null, true));
		trader.onOffer(0, 2, "SELLING", 0, 1000, 280, 0, T0 + 70_000, true);
		trader.onOffer(0, 2, "SELLING", 670, 1000, 280, 187_600, T0 + 3 * 3_600_000L, true);
		trader.onOffer(1, 453, "BUYING", 780, 2000, 150, 117_000, T0 + 3 * 3_600_000L, true);
		Trader.Step selling = next(trader, feed, T0 + 3 * 3_600_000L);
		TradePanel.View open = view(true, null, null, false, selling, trader.slots(T0 + 6 * 3_600_000L, feed), null, today,
			10_000_000L, false, null, true);
		tradeOpen(out, "plan-selling", open, 2);
		tradeOpen(out, "plan-buying", open, 453);
		// Part of the sale has sold: Today so far counts it before the website saves the trade.
		Trader.Today partSale = new Trader.Today();
		partSale.profit = 10_720;
		partSale.selling = 10_720;
		trade(out, "today-selling", view(true, null, null, false, selling, trader.slots(T0 + 6 * 3_600_000L, feed), null,
			partSale, 10_000_000L, false, null, true));
		trader.onOffer(0, 2, "SOLD", 1000, 1000, 280, 280_000, T0 + 600_000, true);
		Trader.Moment moment = null;
		for (Trader.Moment m : trader.takeMoments())
		{
			moment = m.isSale() ? m : moment;
		}
		today.profit = 16_000;
		today.flips = 1;
		trade(out, "flip-finished", view(true, null, null, false, next(trader, feed, T0 + 600_000), trader.slots(T0 + 600_000),
			null, today, 10_000_000L, false, moment, true));

		Trader waiting = ready(1_000_000);
		PlanFeed.Feed one = feed(pick(2, "Steel cannonball", 259, 280, 1000));
		waiting.refill(one, T0 + 1000);
		waiting.onOffer(0, 2, "BUYING", 0, 1000, 259, 0, T0 + 5000, true);
		waiting.onOffer(0, 2, "BUYING", 400, 1000, 259, 103_600, T0 + 60_000, true);
		trade(out, "waiting", view(true, null, null, false, next(waiting, one, T0 + 61_000), waiting.slots(T0 + 61_000),
			null, today, 1_000_000L, false, null, true));
		Trader empty = ready(1_000);
		trade(out, "empty", view(true, null, null, false, next(empty, feed(), T0 + 1000), Collections.emptyList(),
			null, today, 1_000L, false, null, true));

		Trader.Rebound rebound = new Trader.Rebound();
		rebound.key = "r1";
		rebound.id = 12934;
		rebound.name = "Zulrah's scales";
		rebound.drop = 0.14;
		rebound.buy = 151;
		rebound.sell = 178;
		rebound.qty = 20_000;
		rebound.profitIfAllSells = 472_000;
		rebound.detectedAt = System.currentTimeMillis() - 12 * 60_000L;
		trade(out, "rebound", view(true, null, null, false, buy, trader.slots(now), rebound, today, 10_000_000L, false, null, true));
		trade(out, "rebound-full", view(true, null, null, false, buy, trader.slots(now), rebound, today, 10_000_000L, false, null, false));
		trade(out, "rebound-swap", view(true, null, null, false, buy, trader.slots(now), rebound, today, 10_000_000L, false, null, false,
			Collections.emptyList(), "Iron bar"));

		// Make and Sell when Trader's picks are full: the offer, the swap under way, done, and none left.
		Trader full = ready(10_000_000);
		Trader.Step noRoom = new Trader.Step(Trader.Kind.EMPTY, "Nothing to trade right now",
			TraderRules.roomNotice(3, T0 + 3_600_000L, java.time.ZoneOffset.UTC), null, Trader.Tone.GREY, -1, null, 0, 0, -1,
			"EMPTY", null);
		List<MakeSell.Offer> swaps = MakeSell.open(MakeSellTest.feed(MakeSellTest.alch(), MakeSellTest.decant()), 1_000_000,
			false, Collections.emptyList());
		trade(out, "make-sell-offer", view(true, null, null, false, noRoom, Collections.emptyList(), null, today, 1_000_000L,
			false, null, true, Collections.emptyList(), null, new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.OFFER,
			swaps.get(0), true, null, 0, -1, "8:00 PM", false, null)));
		PlanFeed.Swap decant = MakeSellTest.decant();
		full.onOffer(0, 139, "BOUGHT", 108, 108, 9000, 972_000, T0 + 1000, true);
		Trader.Step collect = full.swapStep(decant, 27, T0);
		trade(out, "make-sell-taken", view(true, null, null, false, collect, Collections.emptyList(), null, today, 1_000_000L,
			false, null, true, Collections.emptyList(), null, new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.TAKEN,
			null, false, decant, 27, TraderController.swapIndex(decant, collect), "8:00 PM", false, null)));
		trade(out, "make-sell-error", view(true, null, null, false, noRoom, Collections.emptyList(), null, today, 1_000_000L,
			false, null, true, Collections.emptyList(), null, new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.OFFER,
			swaps.get(1), false, null, 0, -1, "8:00 PM", false, MakeSell.ERROR_FULL)));
		trade(out, "make-sell-done", view(true, null, null, false, noRoom, Collections.emptyList(), null, today, 1_000_000L,
			false, null, true, Collections.emptyList(), null, new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.DONE,
			null, false, null, 0, -1, "8:00 PM", false, null)));
		trade(out, "make-sell-full", view(true, null, null, false, noRoom, Collections.emptyList(), null, today, 1_000_000L,
			false, null, true, Collections.emptyList(), null, new TradePanel.MakeSellCard(TradePanel.MakeSellCard.State.FULL,
			null, false, null, 0, -1, "8:00 PM", false, null)));

		GielinorGainsPanel settings = sidebar(new TradePanel(NO_ACTIONS, id -> icon()), new ProgressPanel(), false);
		settings.showSettings(true, 10_000_000L);
		settings.showSettingsTab();
		draw(out, "sidebar-settings", settings);
		GielinorGainsPanel signedOut = sidebar(new TradePanel(NO_ACTIONS, id -> icon()), new ProgressPanel(), false);
		signedOut.showSettings(false, null);
		signedOut.showSettingsTab();
		draw(out, "sidebar-settings-signed-out", signedOut);

		long gearNow = 1_762_430_400_000L;
		java.util.List<GearPanel.Owned> owned = new java.util.ArrayList<>();
		String[] names = {"Rune crossbow", "Diamond bolts (e)", "Black d'hide body", "Black d'hide chaps", "Archers ring",
			"Amulet of glory", "Abyssal whip", "Dragon defender", "Fire cape", "Barrows gloves", "Dragon boots",
			"Helm of neitiznot", "Trident of the seas", "Mystic robe top"};
		for (int i = 0; i < names.length; i++)
		{
			owned.add(new GearPanel.Owned(1000 + i, names[i]));
		}
		gear(out, "ready", new GearPanel.View(true, true, GearPanel.Status.READY, owned, gearNow - 2 * 3_600_000L, gearNow));
		gear(out, "no-bank", new GearPanel.View(true, true, GearPanel.Status.READY, owned.subList(0, 3), 0, gearNow));
		gear(out, "none", new GearPanel.View(true, true, GearPanel.Status.READY, Collections.emptyList(), gearNow - 60_000L, gearNow));
		gear(out, "loading", new GearPanel.View(true, true, GearPanel.Status.LOADING, Collections.emptyList(), 0, gearNow));
		gear(out, "failed", new GearPanel.View(true, true, GearPanel.Status.FAILED, Collections.emptyList(), 0, gearNow));
		gear(out, "logged-out", new GearPanel.View(true, false, GearPanel.Status.READY, Collections.emptyList(), 0, gearNow));
		gear(out, "off", new GearPanel.View(false, true, GearPanel.Status.READY, Collections.emptyList(), 0, gearNow));

		progress(out, "progress-signed-out", false, null, null);
		progress(out, "progress-new", true, summary("{\"enabled\":true,\"profit\":0,\"trades\":0,\"days\":" + days(new long[14], new int[14])
			+ ",\"achievements\":{\"earned\":0,\"total\":24,\"next\":{\"id\":\"trades_1\",\"title\":\"First trade\","
			+ "\"text\":\"Complete your first trade.\",\"current\":0,\"target\":1}}}"), null);
		long[] profit = {0, 0, 31_000, 0, 54_000, -8_000, 0, 0, 12_500, 88_000, 0, 41_000, 72_000, -3_000};
		int[] trades = {0, 0, 2, 0, 3, 1, 0, 0, 1, 4, 0, 2, 1, 1};
		String active = "{\"enabled\":true,\"profit\":1287500,\"trades\":46,\"days\":" + days(profit, trades)
			+ ",\"achievements\":{\"earned\":7,\"total\":24,\"latest\":{\"id\":\"net_1m\",\"title\":\"First million\","
			+ "\"text\":\"You made 1M GP in recorded net profit.\",\"earnedAt\":\"2026-10-30\"},"
			+ "\"next\":{\"id\":\"trades_50\",\"title\":\"50 trades\",\"text\":\"Complete 50 trades.\",\"current\":46,\"target\":50}}}";
		progress(out, "progress-active", true, summary(active), null);
		progress(out, "progress-new-achievement", true, summary(active), "net_1m");
		Trader.Summary partSold = summary(active);
		partSold.profit += 10_720;
		partSold.selling = 10_720;
		partSold.days.get(13).profit += 10_720;
		partSold.days.get(13).selling = 10_720;
		progress(out, "progress-selling", true, partSold, null);
		progress(out, "progress-paused", true, summary(active.replace("\"enabled\":true", "\"enabled\":false")), null);
	}

	private static TradePanel.View view(boolean signedIn, String code, String problem, boolean offline, Trader.Step step,
		List<Trader.SlotRow> slots, Trader.Rebound rebound, Trader.Today today, Long budget, boolean freeWorld,
		Trader.Moment moment, boolean room)
	{
		return view(signedIn, code, problem, offline, step, slots, rebound, today, budget, freeWorld, moment, room,
			Collections.emptyList());
	}

	private static TradePanel.View view(boolean signedIn, String code, String problem, boolean offline, Trader.Step step,
		List<Trader.SlotRow> slots, Trader.Rebound rebound, Trader.Today today, Long budget, boolean freeWorld,
		Trader.Moment moment, boolean room, List<TradePanel.UpNext> upNext)
	{
		return view(signedIn, code, problem, offline, step, slots, rebound, today, budget, freeWorld, moment, room, upNext,
			null);
	}

	private static TradePanel.View view(boolean signedIn, String code, String problem, boolean offline, Trader.Step step,
		List<Trader.SlotRow> slots, Trader.Rebound rebound, Trader.Today today, Long budget, boolean freeWorld,
		Trader.Moment moment, boolean room, List<TradePanel.UpNext> upNext, String swap)
	{
		return view(signedIn, code, problem, offline, step, slots, rebound, today, budget, freeWorld, moment, room, upNext,
			swap, null);
	}

	private static TradePanel.View view(boolean signedIn, String code, String problem, boolean offline, Trader.Step step,
		List<Trader.SlotRow> slots, Trader.Rebound rebound, Trader.Today today, Long budget, boolean freeWorld,
		Trader.Moment moment, boolean room, List<TradePanel.UpNext> upNext, String swap, TradePanel.MakeSellCard makeSell)
	{
		return new TradePanel.View(signedIn, code, problem, offline, step, slots, 259_000, rebound, today, budget,
			12_400_000, freeWorld, moment, upNext, false, moment, room, room ? null : swap, false, makeSell);
	}

	private static String days(long[] profit, int[] trades)
	{
		StringBuilder s = new StringBuilder("[");
		for (int i = 0; i < 14; i++)
		{
			s.append(i == 0 ? "" : ",").append("{\"day\":\"2026-10-").append(17 + i).append("\",\"profit\":")
				.append(profit[i]).append(",\"trades\":").append(trades[i]).append('}');
		}
		return s.append(']').toString();
	}

	private static Trader.Summary summary(String json)
	{
		return GSON.fromJson(json, Trader.Summary.class);
	}

	private static void trade(Path out, String name, TradePanel.View view) throws Exception
	{
		TradePanel trade = new TradePanel(NO_ACTIONS, id -> icon());
		trade.show(view);
		draw(out, "sidebar-trade-" + name, sidebar(trade, new ProgressPanel(), false));
	}

	/** Trade with one slot's plan open, as after a click on its row. */
	private static void tradeOpen(Path out, String name, TradePanel.View view, int itemId) throws Exception
	{
		TradePanel trade = new TradePanel(NO_ACTIONS, id -> icon());
		trade.show(view);
		trade.toggle(itemId);
		draw(out, "sidebar-trade-" + name, sidebar(trade, new ProgressPanel(), false));
	}

	private static void gear(Path out, String name, GearPanel.View view) throws Exception
	{
		GearPanel gear = new GearPanel(id -> art(), () -> { }, () -> { });
		gear.show(view);
		GielinorGainsPanel panel = sidebar(new TradePanel(NO_ACTIONS, id -> icon()), gear, new ProgressPanel(), false);
		panel.showGearTab();
		draw(out, "sidebar-gear-" + name, panel);
	}

	private static void progress(Path out, String name, boolean signedIn, Trader.Summary summary, String fresh) throws Exception
	{
		ProgressPanel progress = new ProgressPanel();
		progress.show(signedIn, summary, fresh);
		draw(out, "sidebar-" + name, sidebar(new TradePanel(NO_ACTIONS, id -> icon()), progress, true));
	}

	private static GielinorGainsPanel sidebar(TradePanel trade, ProgressPanel progress, boolean showProgress)
	{
		return sidebar(trade, new GearPanel(id -> icon(), () -> { }, () -> { }), progress, showProgress);
	}

	private static GielinorGainsPanel sidebar(TradePanel trade, GearPanel gear, ProgressPanel progress, boolean showProgress)
	{
		BufferedImage logo = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		GielinorGainsPanel panel = new GielinorGainsPanel(new MarketPanel(id -> icon(), id -> { }), trade, gear, progress, logo,
			() -> { }, () -> { }, () -> { });
		panel.setStatus("Prices from 2 min ago", Brand.MADE_MONEY, false);
		if (showProgress)
		{
			panel.showProgressTab();
		}
		return panel;
	}

	private static void draw(Path out, String name, JComponent panel) throws Exception
	{
		panel.setSize(242, 900);
		for (int i = 0; i < 3; i++)
		{
			layout(panel);
		}
		BufferedImage image = new BufferedImage(panel.getWidth() * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.scale(2, 2);
		panel.printAll(g);
		g.dispose();
		ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
	}

	/** The sidebar 420 high, its visible tab scrolled down {@code by} pixels. */
	private static void drawScrolled(Path out, String name, JComponent panel, int by) throws Exception
	{
		panel.setSize(242, 420);
		for (int i = 0; i < 3; i++)
		{
			layout(panel);
		}
		for (javax.swing.JScrollPane scroll : scrollPanes(panel))
		{
			scroll.getViewport().setViewPosition(new java.awt.Point(0, by));
		}
		BufferedImage image = new BufferedImage(panel.getWidth() * 2, panel.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.scale(2, 2);
		panel.printAll(g);
		g.dispose();
		ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
	}

	private static java.util.List<javax.swing.JScrollPane> scrollPanes(java.awt.Container root)
	{
		java.util.List<javax.swing.JScrollPane> found = new java.util.ArrayList<>();
		for (java.awt.Component c : root.getComponents())
		{
			if (c instanceof javax.swing.JScrollPane)
			{
				found.add((javax.swing.JScrollPane) c);
			}
			else if (c instanceof java.awt.Container)
			{
				found.addAll(scrollPanes((java.awt.Container) c));
			}
		}
		return found;
	}

	/** A stand-in for item art, so the Gear tab's grid shows in its previews. */
	private static AsyncBufferedImage art()
	{
		AsyncBufferedImage image = new AsyncBufferedImage(null, 36, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new java.awt.Color(0x8a6d3b));
		g.fillOval(6, 4, 24, 24);
		g.dispose();
		return image;
	}

	private static AsyncBufferedImage icon()
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
}
