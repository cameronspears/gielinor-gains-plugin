package com.gielinorgains;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * A short label inside each GE slot that holds a plan item ("Buying · 40%", "Collect"), and
 * "Next: Dragon bones" in the free slot the next buy goes to: a coloured dot and words, with a
 * ring that empties as a sale's 8 hours run down. It only draws; the game's own slots and
 * buttons are untouched. Client thread only.
 */
@Singleton
class TraderOverlay extends Overlay
{
	private static final Stroke RING = new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private final Client client;
	private final GielinorGainsConfig config;
	/** How long a profit drop rises and fades, like the game's XP drops. */
	private static final long DROP_MS = 2600;
	private final ItemManager itemManager;
	private Trader trader;
	private Trader.Step next;
	private final java.util.List<Drop> drops = new java.util.ArrayList<>();

	private static final class Drop
	{
		final Trader.Moment moment;
		final long at;

		Drop(Trader.Moment moment, long at)
		{
			this.moment = moment;
			this.at = at;
		}
	}

	@Inject
	TraderOverlay(Client client, GielinorGainsConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	void setTrader(Trader trader)
	{
		this.trader = trader;
	}

	/** Client thread: a finished flip rises out of its GE slot as "+5,820 GP"; a filled buy as "Bought". */
	void drop(Trader.Moment moment)
	{
		drops.add(new Drop(moment, System.currentTimeMillis()));
	}

	/** Client thread: the step the Trade tab shows, so the slot it points at can say so. */
	void setNext(Trader.Step next)
	{
		this.next = next;
	}

	static Color color(Trader.Tone tone)
	{
		switch (tone)
		{
			case BLUE:
				return Brand.BLUE;
			case GREEN:
				return Brand.MADE_MONEY;
			case AMBER:
				return Brand.AMBER;
			default:
				return Brand.MUTED;
		}
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (trader == null || trader.plan() == null || !config.guideOffers())
		{
			return null;
		}
		long now = System.currentTimeMillis();
		graphics.setFont(FontManager.getRunescapeSmallFont());
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		FontMetrics metrics = graphics.getFontMetrics();
		for (int slot = 0; slot < 8; slot++)
		{
			Widget widget = client.getWidget(InterfaceID.GeOffers.INDEX_0 + slot);
			Trader.Badge badge = !ItemCheckOverlay.onScreen(widget) ? null : trader.badge(slot, now, next);
			if (badge == null)
			{
				continue;
			}
			Rectangle r = widget.getBounds();
			Color color = color(badge.getTone());
			boolean ring = badge.getPatience() >= 0;
			int mark = ring ? 9 : 6;
			int textWidth = Math.min(metrics.stringWidth(badge.getText()), r.width - mark - 16);
			int width = textWidth + mark + 12;
			int height = metrics.getHeight() + 3;
			int x = r.x + (r.width - width) / 2;
			int y = r.y + r.height - height - 4;
			Brand.card(graphics, x, y, width, height, badge.getTone() == Trader.Tone.AMBER ? color : Brand.BORDER);
			int cy = y + height / 2;
			if (ring)
			{
				// The ring empties clockwise as the sale's patience runs down; the words give the hours.
				Stroke before = graphics.getStroke();
				graphics.setStroke(RING);
				graphics.setColor(Brand.BORDER);
				graphics.drawOval(x + 5, cy - mark / 2, mark, mark);
				graphics.setColor(color);
				graphics.drawArc(x + 5, cy - mark / 2, mark, mark, 90, (int) Math.round(-360 * badge.getPatience()));
				graphics.setStroke(before);
			}
			else
			{
				graphics.setColor(color);
				graphics.fillOval(x + 5, cy - mark / 2, mark, mark);
			}
			graphics.setColor(Brand.TEXT);
			Shape clip = graphics.getClip();
			graphics.clipRect(x, y, width - 4, height);
			graphics.drawString(badge.getText(), x + mark + 9, y + metrics.getAscent() + 1);
			graphics.setClip(clip);
		}
		drops(graphics, now);
		return null;
	}

	private void drops(Graphics2D graphics, long now)
	{
		drops.removeIf(d -> now - d.at > DROP_MS);
		for (Drop d : drops)
		{
			Widget widget = client.getWidget(InterfaceID.GeOffers.INDEX_0 + d.moment.getGeSlot());
			if (!ItemCheckOverlay.onScreen(widget))
			{
				continue;
			}
			double t = (now - d.at) / (double) DROP_MS;
			// Rises fast then settles, and fades over its last third.
			double rise = config.reduceMotion() ? 0.5 : 1 - Math.pow(1 - t, 3);
			float alpha = (float) Math.max(0, Math.min(1, (1 - t) * 3));
			boolean sale = d.moment.isSale();
			String text = sale ? d.moment.profitText() : "Bought " + BoxText.whole(d.moment.getQty());
			graphics.setFont(sale ? FontManager.getRunescapeBoldFont() : FontManager.getRunescapeSmallFont());
			FontMetrics fm = graphics.getFontMetrics();
			Rectangle r = widget.getBounds();
			BufferedImage coins = sale ? itemManager.getImage(ItemID.COINS, (int) Math.min(Integer.MAX_VALUE,
				Math.max(1, d.moment.getProfit())), true) : null;
			int iconW = coins == null ? 0 : 22;
			int width = iconW + fm.stringWidth(text);
			int x = r.x + (r.width - width) / 2;
			int y = r.y + r.height / 2 - (int) Math.round(rise * 34);
			java.awt.Composite before = graphics.getComposite();
			graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
			if (coins != null)
			{
				graphics.drawImage(coins, x - 6, y - fm.getAscent() - 6, 27, 24, null);
			}
			// The game's text shadow, so it reads over any slot.
			graphics.setColor(Color.BLACK);
			graphics.drawString(text, x + iconW + 1, y + 1);
			graphics.setColor(sale ? (d.moment.getProfit() >= 0 ? Brand.MADE_MONEY : Brand.AMBER) : Brand.BLUE);
			graphics.drawString(text, x + iconW, y);
			graphics.setComposite(before);
		}
	}

	/** Outlines plan items to sell in the bank and inventory, so they're easy to find. */
	@Singleton
	static class Held extends WidgetItemOverlay
	{
		private final GielinorGainsConfig config;
		private Set<Integer> held = java.util.Collections.emptySet();

		@Inject
		Held(GielinorGainsConfig config)
		{
			this.config = config;
			showOnBank();
			showOnInventory();
		}

		/** Client thread: the items bought for the plan and not yet sold. */
		void setHeld(Set<Integer> held)
		{
			this.held = held;
		}

		@Override
		public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem item)
		{
			// In the GE the guide outlines what to sell, so this mark would be a second box. Elsewhere
			// it shows only on an item that's really on screen, not in a closed side panel.
			if (!config.guideOffers() || !held.contains(itemId) || item.getWidget() == null
				|| item.getWidget().getId() >>> 16 == net.runelite.api.gameval.InterfaceID.GE_OFFERS_SIDE
				|| !ItemCheckOverlay.onScreen(item.getWidget()))
			{
				return;
			}
			Rectangle r = item.getCanvasBounds();
			Color color = config.guideColor();
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 200));
			graphics.drawRoundRect(r.x - 1, r.y - 1, r.width + 1, r.height + 1, 6, 6);
		}
	}
}
