package com.gielinorgains;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Notification;

@ConfigGroup(GielinorGainsConfig.GROUP)
public interface GielinorGainsConfig extends Config
{
	String GROUP = "gielinorgains";
	String COLLECT_TIP_SEEN = "collectTipSeen";
	String TRADER_KEY = "traderKey";
	String WELCOME_SHOWN = "traderWelcomeShown";
	String GEAR_KEY = "rememberGear";

	// Constants so the reading-level test can check the words players see in the settings.
	String MARKET_NAME = "Show the sidebar button";
	String MARKET_DESCRIPTION = "Adds the Gielinor Gains button to the sidebar, with Trade, Market and Progress."
		+ " Turn it off to remove the button. Trader still follows your offers if you're signed in.";
	String GUIDE_NAME = "Guide my GE offer";
	String GUIDE_DESCRIPTION = "At the GE, outlines the next thing to click and shows what to type. It guides your"
		+ " Trader plan, or a flip you picked in the Market. It never clicks or types for you.";
	String COLOR_NAME = "Highlight color";
	String COLOR_DESCRIPTION = "The color of the outline around the next thing to click.";
	String NOTIFY_NAME = "Notify me";
	String NOTIFY_DESCRIPTION = "Tells you when a buy fills, when it sells, and when Trader has a new step for you."
		+ " It uses your RuneLite notification settings.";
	String SOUND_NAME = "Sound when it fills or sells";
	String SOUND_DESCRIPTION = "Plays the game's own GE sound at your game's sound effect volume.";
	String BUYING_NAME = "Show when buying";
	String BUYING_DESCRIPTION = "Show the box when you set up a buy offer.";
	String SELLING_NAME = "Show when selling";
	String SELLING_DESCRIPTION = "Show the box when you set up a sell offer.";
	String MOTION_NAME = "Reduce motion";
	String MOTION_DESCRIPTION = "Turns off the sidebar's small moves, like cards sliding in and dots that pulse.";
	String INFOBOX_NAME = "Show offers to collect";
	String INFOBOX_DESCRIPTION = "When you use Trader here, a small box counts the GE offers ready to collect.";
	String GEAR_NAME = "Remember my gear for Gear Up";
	String GEAR_DESCRIPTION = "Reads your bank, bag and worn gear so Gear Up can build setups from what you have."
		+ " It stays in RuneLite until you press Open Gear Up. Turn it off to forget it.";

	@ConfigItem(
		keyName = "showMarketList",
		name = MARKET_NAME,
		description = MARKET_DESCRIPTION,
		position = 0
	)
	default boolean showMarketList()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideOffers",
		name = GUIDE_NAME,
		description = GUIDE_DESCRIPTION,
		position = 1
	)
	default boolean guideOffers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guideColor",
		name = COLOR_NAME,
		description = COLOR_DESCRIPTION,
		position = 2
	)
	default Color guideColor()
	{
		// The site's blue-500, its colour for interaction.
		return new Color(0x3b82f6);
	}

	@ConfigItem(
		keyName = "flipNotification",
		name = NOTIFY_NAME,
		description = NOTIFY_DESCRIPTION,
		position = 3
	)
	default Notification flipNotification()
	{
		return Notification.ON;
	}

	@ConfigItem(
		keyName = "flipSound",
		name = SOUND_NAME,
		description = SOUND_DESCRIPTION,
		position = 4
	)
	default boolean flipSound()
	{
		return false;
	}

	@ConfigItem(
		keyName = "reduceMotion",
		name = MOTION_NAME,
		description = MOTION_DESCRIPTION,
		position = 8
	)
	default boolean reduceMotion()
	{
		return false;
	}

	/** Set once the player has seen or hidden the Collect tip, so it shows only once. */
	@ConfigItem(keyName = COLLECT_TIP_SEEN, name = "", description = "", hidden = true)
	default boolean collectTipSeen()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showWhenBuying",
		name = BUYING_NAME,
		description = BUYING_DESCRIPTION,
		position = 5
	)
	default boolean showWhenBuying()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showWhenSelling",
		name = SELLING_NAME,
		description = SELLING_DESCRIPTION,
		position = 6
	)
	default boolean showWhenSelling()
	{
		return true;
	}

	@ConfigItem(
		keyName = "traderInfoBox",
		name = INFOBOX_NAME,
		description = INFOBOX_DESCRIPTION,
		position = 7
	)
	default boolean traderInfoBox()
	{
		return true;
	}

	@ConfigItem(
		keyName = GEAR_KEY,
		name = GEAR_NAME,
		description = GEAR_DESCRIPTION,
		position = 9
	)
	default boolean rememberGear()
	{
		return true;
	}

	/** Set once the one-time "Gielinor Gains is in your sidebar" game message has shown. */
	@ConfigItem(keyName = WELCOME_SHOWN, name = "", description = "", hidden = true)
	default boolean traderWelcomeShown()
	{
		return false;
	}

	/**
	 * This RuneLite's sign-in key. The website keeps only its hash, and Unlink ends it. RuneLite
	 * doesn't keep secret items off its profile sync, so a synced profile carries it too.
	 */
	@ConfigItem(keyName = TRADER_KEY, name = "", description = "", hidden = true, secret = true)
	default String traderKey()
	{
		return "";
	}
}
