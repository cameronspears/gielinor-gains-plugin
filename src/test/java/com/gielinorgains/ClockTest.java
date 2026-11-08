package com.gielinorgains;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import javax.swing.SwingUtilities;
import org.junit.Test;

public class ClockTest
{
	/** A card dealt while the sidebar is closed never paints, so the clock must let it go. */
	@Test
	public void aCardNeverPutOnScreenStopsTheClock() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			boolean before = Ui.reduceMotion;
			Ui.reduceMotion = false;
			try
			{
				new Ui.Card().appear(0, false);
				assertTrue(Ui.Clock.running());
				Ui.Clock.tick();
				assertFalse(Ui.Clock.running());
			}
			finally
			{
				Ui.reduceMotion = before;
			}
		});
	}
}
