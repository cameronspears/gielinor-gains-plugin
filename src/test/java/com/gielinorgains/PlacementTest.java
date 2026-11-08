package com.gielinorgains;

import static org.junit.Assert.assertEquals;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import org.junit.Test;

public class PlacementTest
{
	private static final Dimension BOX = new Dimension(ItemCheckOverlay.WIDTH, 150);

	private static Point place(Rectangle frame, Dimension canvas, boolean resized)
	{
		Point out = new Point();
		ItemCheckOverlay.place(frame, canvas.width, canvas.height, BOX.width, BOX.height, resized, false, out);
		return out;
	}

	@Test
	public void fixedModeSitsOverTheMinimap()
	{
		Point at = place(new Rectangle(8, 10, 488, 304), new Dimension(765, 503), false);
		assertEquals(new Point(765 - ItemCheckOverlay.WIDTH - 6, 6), at);
	}

	@Test
	public void resizableGoesRightOfTheGeWhenThereIsRoom()
	{
		Point at = place(new Rectangle(400, 200, 488, 304), new Dimension(1400, 900), true);
		assertEquals(new Point(400 + 488 + 6, 200), at);
	}

	@Test
	public void resizableGoesLeftWhenTheRightIsFull()
	{
		Point at = place(new Rectangle(700, 200, 488, 304), new Dimension(1300, 900), true);
		assertEquals(new Point(700 - 6 - ItemCheckOverlay.WIDTH, 200), at);
	}

	@Test
	public void staysOnScreenWhenNeitherSideFits()
	{
		Point at = place(new Rectangle(50, 800, 488, 304), new Dimension(600, 900), true);
		assertEquals(new Point(600 - ItemCheckOverlay.WIDTH - 6, 900 - 150 - 6), at);
	}

	@Test
	public void whileTypingTheBoxSitsLowNearTheChatbox()
	{
		Point out = new Point();
		ItemCheckOverlay.place(new Rectangle(400, 200, 488, 304), 1400, 900, BOX.width, BOX.height, true, true, out);
		assertEquals(new Point(400 + 488 + 6, 200 + 304 - 150), out);
	}
}
