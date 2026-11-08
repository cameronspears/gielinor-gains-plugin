package com.gielinorgains;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;

/**
 * Gielinor Gains' look, shared by the GE box, the Market list and the guide: the site's dark
 * surfaces with 8px corners, neutral greys, blue only for things you can act on, and the item
 * page's record squares. Text stays in RuneLite's own fonts, so it sits naturally in the game.
 * Every text colour is at least 4.5:1 on the surface (ColorContrastTest).
 */
final class Brand
{
	/** The site's dark surface and its border (neutral-800). */
	static final Color SURFACE = new Color(0x101010);
	static final Color BORDER = new Color(0x262626);
	/** Text, and quieter text for labels and notes (neutral-100 and neutral-400). */
	static final Color TEXT = new Color(0xf5f5f5);
	static final Color MUTED = new Color(0xa3a3a3);
	/** blue-500: links, focus, your flip and the guide's outline. Never decoration. */
	static final Color BLUE = new Color(0x3b82f6);
	/** The item page's record squares: a test that made money, and one that didn't. */
	static final Color MADE_MONEY = new Color(0x4ade80);
	static final Color NO_MONEY = new Color(0x737373);
	static final int RADIUS = 8;
	/** amber-400: "look at this", such as a key typed that isn't ours. */
	static final Color AMBER = new Color(0xfbbf24);

	private Brand()
	{
	}

	/** The site's card: a dark rounded surface with a hairline border. */
	static void card(Graphics2D g, int x, int y, int width, int height, Color border)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(SURFACE);
		g.fillRoundRect(x, y, width, height, RADIUS, RADIUS);
		g.setColor(border);
		g.drawRoundRect(x, y, width - 1, height - 1, RADIUS, RADIUS);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	/**
	 * An item's latest tests as squares, oldest first, green when it made money, as on its item
	 * page. Always shown under the words that say the same thing, so colour is never the only cue.
	 */
	static int squares(Graphics2D g, String checks, int x, int y, int size, int gap)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (int i = 0; i < checks.length(); i++)
		{
			g.setColor(checks.charAt(i) == '1' ? MADE_MONEY : NO_MONEY);
			g.fillRoundRect(x + i * (size + gap), y, size, size, 3, 3);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		return checks.isEmpty() ? 0 : checks.length() * (size + gap) - gap;
	}

	/**
	 * "Type 11k" as keys: each lights blue as it's pressed, and a wrong one turns amber, so typing
	 * feels like following along. The words above and below say the same thing, so colour is never
	 * the only cue. Drawn fresh each frame from what's typed; nothing moves on its own.
	 */
	static class Keys implements LayoutableRenderableEntity
	{
		private static final int SIZE = 16;
		private static final int GAP = 3;

		private final String keys;
		private final String typed;
		@Getter
		private final Rectangle bounds = new Rectangle();
		@Setter
		private Point preferredLocation = new Point();
		@Setter
		private Dimension preferredSize = new Dimension();

		Keys(String keys, String typed)
		{
			this.keys = keys;
			this.typed = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT);
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int x = preferredLocation.x;
			int y = preferredLocation.y + 2;
			int count = Math.max(keys.length(), typed.length());
			for (int i = 0; i < count; i++)
			{
				boolean pressed = i < typed.length();
				boolean right = pressed && i < keys.length() && typed.charAt(i) == keys.charAt(i);
				char shown = pressed ? typed.charAt(i) : keys.charAt(i);
				g.setColor(right ? BLUE : pressed ? AMBER : BORDER);
				g.fillRoundRect(x, y, SIZE, SIZE, 4, 4);
				g.setColor(pressed ? SURFACE : MUTED);
				g.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 4, 4);
				String key = String.valueOf(shown);
				int width = g.getFontMetrics().stringWidth(key);
				g.setColor(pressed ? SURFACE : TEXT);
				g.drawString(key, x + (SIZE - width) / 2, y + SIZE - 4);
				x += SIZE + GAP;
			}
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
			Dimension size = new Dimension(preferredSize.width, SIZE + 6);
			bounds.setLocation(preferredLocation);
			bounds.setSize(size);
			return size;
		}
	}

	/** The record squares as a line of the GE box. */
	static class Squares implements LayoutableRenderableEntity
	{
		private static final int SIZE = 7;
		private static final int GAP = 3;

		private final String checks;
		@Getter
		private final Rectangle bounds = new Rectangle();
		@Setter
		private Point preferredLocation = new Point();
		@Setter
		private Dimension preferredSize = new Dimension();

		Squares(String checks)
		{
			this.checks = checks;
		}

		@Override
		public Dimension render(Graphics2D g)
		{
			squares(g, checks, preferredLocation.x, preferredLocation.y + 2, SIZE, GAP);
			Dimension size = new Dimension(preferredSize.width, SIZE + 5);
			bounds.setLocation(preferredLocation);
			bounds.setSize(size);
			return size;
		}
	}
}
