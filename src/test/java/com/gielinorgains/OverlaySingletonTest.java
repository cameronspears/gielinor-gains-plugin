package com.gielinorgains;

import static org.junit.Assert.assertTrue;

import javax.inject.Singleton;
import org.junit.Test;

public class OverlaySingletonTest
{
	/** Trader sets what these draw, so it must share the instance the plugin adds to the screen. */
	@Test
	public void overlaysAreShared()
	{
		for (Class<?> c : new Class<?>[]{ItemCheckOverlay.class, TraderOverlay.class, TraderOverlay.Held.class})
		{
			assertTrue(c.getSimpleName(), c.isAnnotationPresent(Singleton.class));
		}
	}
}
