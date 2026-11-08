package com.gielinorgains;

import static org.junit.Assert.assertEquals;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class UiTest
{
	/** A card scrolled partly under the tabs is clipped by the scroll pane; nothing may paint past that. */
	@Test
	public void aCardNeverPaintsOutsideItsClip()
	{
		Ui.Card card = new Ui.Card(Brand.BLUE, Brand.BORDER);
		card.setSize(120, 80);
		BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		// Only the bottom 30 rows are showing, as when the card's top is under the tabs.
		g.setClip(0, 50, 120, 30);
		card.paint(g);
		g.dispose();
		for (int y = 0; y < 50; y++)
		{
			for (int x = 0; x < 120; x++)
			{
				assertEquals("painted at " + x + "," + y, 0, image.getRGB(x, y) >>> 24);
			}
		}
	}
}
