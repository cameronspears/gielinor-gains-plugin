package com.gielinorgains;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import lombok.Value;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * The Gear tab: Gear Up on the website, opened with the player's gear. It shows which of Gear Up's
 * items the plugin found and how fresh the bank is, so the player knows what the website will
 * get before they press the button. Swing thread only.
 */
class GearPanel extends JPanel
{
	// The website's words for Gear Up, from its page header.
	static final String TITLE = "Gear Up";
	static final String WHAT = "The gear that gives you the most damage for your GP, for any fight.";
	static final String OPEN = "Open Gear Up";
	static final String LOG_IN = "Log in to the game to use your own gear.";
	static final String OFF = "Reading your gear is off in RuneLite's settings for Gielinor Gains.";
	static final String LOADING = "Checking your gear…";
	static final String FAILED = "We couldn't reach Gielinor Gains. Gear Up still opens, without your gear.";
	static final String NONE = "None of your gear is on Gear Up's list yet.";
	static final String NO_BANK = "Only what you wear and carry: your bank hasn't been open yet.";
	static final String PRIVACY = "Your name, levels, quests, coins and these items go in the link to Gear Up. We don't store them.";
	private static final int COLUMNS = 6;
	private static final int MAX_ICONS = 24;

	enum Status
	{
		LOADING, FAILED, READY
	}

	@Value
	static class Owned
	{
		int id;
		String name;
	}

	/** What the tab shows. {@code now} is to the minute, so the bank's age only redraws once a minute. */
	@Value
	static class View
	{
		boolean on;
		boolean loggedIn;
		Status status;
		List<Owned> owned;
		long bankAt;
		long now;

		View(boolean on, boolean loggedIn, Status status, List<Owned> owned, long bankAt, long now)
		{
			this.on = on;
			this.loggedIn = loggedIn;
			this.status = status;
			this.owned = owned;
			this.bankAt = bankAt;
			this.now = now / 60_000 * 60_000;
		}
	}

	private final IntFunction<AsyncBufferedImage> icons;
	private final Runnable open;
	private final Runnable retry;
	private View shown;

	GearPanel(IntFunction<AsyncBufferedImage> icons, Runnable open, Runnable retry)
	{
		this.icons = icons;
		this.open = open;
		this.retry = retry;
		setLayout(new Ui.ColumnLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(Ui.GAP, Ui.GAP, Ui.GAP, Ui.GAP));
		show(new View(true, false, Status.READY, java.util.Collections.emptyList(), 0, 0));
	}

	void show(View view)
	{
		if (Objects.equals(view, shown))
		{
			return;
		}
		shown = view;
		removeAll();
		Ui.Card card = new Ui.Card();
		card.add(Ui.eyebrow(TITLE));
		card.add(Ui.gap(2));
		card.add(Ui.text(WHAT, Brand.TEXT));
		card.add(Ui.gap(Ui.GAP));
		String status = status(view);
		if (status != null)
		{
			card.add(Ui.text(status, Brand.MUTED));
			card.add(Ui.gap(Ui.GAP));
		}
		if (view.on && view.loggedIn && view.status == Status.READY && !view.owned.isEmpty())
		{
			card.add(grid(view.owned));
			card.add(Ui.gap(Ui.GAP));
		}
		card.add(new Ui.Button(OPEN, true, open));
		if (view.on && view.loggedIn && view.status == Status.FAILED)
		{
			card.add(Ui.gap(6));
			card.add(new Ui.Link("Try again", Brand.BLUE, retry));
		}
		if (view.on && view.loggedIn && view.status == Status.READY)
		{
			card.add(Ui.gap(6));
			card.add(Ui.text(PRIVACY, Brand.MUTED));
		}
		add(card);
		revalidate();
		repaint();
	}

	/** The line over the items: how many, and how fresh the bank is. Null when there's nothing to say. */
	static String status(View view)
	{
		if (!view.on)
		{
			return OFF;
		}
		if (!view.loggedIn)
		{
			return LOG_IN;
		}
		if (view.status == Status.LOADING)
		{
			return LOADING;
		}
		if (view.status == Status.FAILED)
		{
			return FAILED;
		}
		String bank = view.bankAt > 0 ? "Bank seen " + ago(view.bankAt, view.now) + "." : NO_BANK;
		if (view.owned.isEmpty())
		{
			return NONE + " " + bank;
		}
		int n = view.owned.size();
		return (n == 1 ? "1 of your items is" : n + " of your items are") + " on Gear Up's list. " + bank;
	}

	/** "just now", "4 minutes ago", "2 hours ago", "3 days ago": the website's words. */
	static String ago(long at, long now)
	{
		long minutes = Math.max(0, (now - at) / 60_000);
		if (minutes < 1)
		{
			return "just now";
		}
		if (minutes < 60)
		{
			return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + (hours == 1 ? " hour ago" : " hours ago");
		}
		long days = hours / 24;
		return days + (days == 1 ? " day ago" : " days ago");
	}

	private JComponent grid(List<Owned> owned)
	{
		JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 2, 2));
		grid.setOpaque(false);
		grid.setAlignmentX(LEFT_ALIGNMENT);
		int shownCount = Math.min(owned.size(), MAX_ICONS);
		for (int i = 0; i < shownCount; i++)
		{
			grid.add(new Icon(owned.get(i)));
		}
		JPanel column = new JPanel(new Ui.ColumnLayout());
		column.setOpaque(false);
		column.setAlignmentX(LEFT_ALIGNMENT);
		column.add(grid);
		if (owned.size() > MAX_ICONS)
		{
			column.add(Ui.gap(4));
			column.add(Ui.text("and " + (owned.size() - MAX_ICONS) + " more", Brand.MUTED));
		}
		return column;
	}

	/** One owned item's art, named in its tooltip and for screen readers. */
	private final class Icon extends JPanel
	{
		private final AsyncBufferedImage image;

		Icon(Owned item)
		{
			setOpaque(false);
			image = icons.apply(item.getId());
			if (image != null)
			{
				image.onLoaded(this::repaint);
			}
			setToolTipText(item.getName());
			getAccessibleContext().setAccessibleName(item.getName());
			setPreferredSize(new Dimension(32, 30));
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (image == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			// Item art is 36 by 32; it keeps its shape and sits in the middle.
			int w = Math.min(getWidth(), 36);
			int h = w * 32 / 36;
			g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
			g2.dispose();
		}
	}
}
