package com.gielinorgains;

import static org.junit.Assert.assertTrue;

import java.awt.Color;
import org.junit.Test;

/** WCAG 2.2 AA: text needs a contrast ratio of at least 4.5:1 against what's behind it. */
public class ColorContrastTest
{
	@Test
	public void everyTextColourIsReadableOnOurSurfaces()
	{
		for (Color background : new Color[]{Brand.SURFACE, net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR})
		{
			for (Color text : new Color[]{Brand.TEXT, Brand.MUTED})
			{
				double ratio = contrast(text, background);
				assertTrue(text + " on " + background + " is only " + ratio + ":1", ratio >= 4.5);
			}
		}
		// Blue text (the Item page link) only ever sits on our dark card.
		assertTrue(contrast(Brand.BLUE, Brand.SURFACE) >= 4.5);
		// On RuneLite's grey, blue is only a bar or ring, which needs 3:1 (WCAG 1.4.11).
		assertTrue(contrast(Brand.BLUE, net.runelite.client.ui.ColorScheme.DARK_GRAY_COLOR) >= 3);
	}

	@Test
	public void theGuidesOutlineStandsOutFromTheGe()
	{
		// WCAG 1.4.11: a control's outline needs 3:1 against what's around it, here the GE's brown.
		assertTrue(contrast(new GielinorGainsConfig()
		{
		}.guideColor(), new Color(62, 53, 41)) >= 3);
		assertTrue("the squares read against the card", contrast(Brand.MADE_MONEY, Brand.SURFACE) >= 3
			&& contrast(Brand.NO_MONEY, Brand.SURFACE) >= 3);
	}

	static double contrast(Color a, Color b)
	{
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double luminance(Color c)
	{
		return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
	}

	private static double channel(int value)
	{
		double c = value / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
