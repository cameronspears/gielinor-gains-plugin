package com.gielinorgains;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.LinkBrowser;

/**
 * The Progress tab: Progress on the website in short, in its words. Recorded net profit, the last
 * 14 days, and achievements, with the latest earned and how close the next one is. Swing thread only.
 */
class ProgressPanel extends JPanel
{
	private static final String PROGRESS_PAGE = TraderApi.SITE + "/trader/progress";
	private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.UK);

	private Boolean shownSignedIn;
	private Trader.Summary shown;
	private String shownNew;
	private Ui.Count total;
	private Runnable signIn = () -> { };

	ProgressPanel()
	{
		setLayout(new Ui.ColumnLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(Ui.GAP, Ui.GAP, Ui.GAP, Ui.GAP));
		show(false, null, null);
	}

	/** "Sign in" while signed out opens the Trade tab, where signing in starts. */
	void setSignIn(Runnable signIn)
	{
		this.signIn = signIn;
	}

	/** {@code newId} is an achievement earned this session, to mark as new; else null. */
	void show(boolean signedIn, Trader.Summary summary, String newId)
	{
		if (shownSignedIn != null && shownSignedIn == signedIn && java.util.Objects.equals(summary, shown)
			&& java.util.Objects.equals(newId, shownNew))
		{
			return;
		}
		shownSignedIn = signedIn;
		shown = summary;
		shownNew = newId;
		removeAll();
		if (!signedIn)
		{
			Ui.Card card = new Ui.Card();
			card.add(Ui.eyebrow("Your progress"));
			card.add(Ui.gap(4));
			card.add(Ui.text("Sign in to see your profit and achievements here.", Brand.MUTED));
			card.add(Ui.gap(6));
			card.add(new Ui.Link("Sign in", Brand.BLUE, () -> signIn.run()));
			add(card);
		}
		else
		{
			add(profit(summary));
			if (summary != null && (summary.trades > 0 || summary.selling != 0) && summary.days != null)
			{
				add(days(summary.days));
			}
			if (summary != null && summary.achievements != null)
			{
				add(achievements(summary.achievements, newId));
			}
			Ui.Card more = new Ui.Card();
			more.add(Ui.text("Your calendar, charts and every achievement are on the website.", Brand.MUTED));
			more.add(Ui.gap(6));
			more.add(new Ui.Link("Open your progress", Brand.BLUE, () -> LinkBrowser.browse(PROGRESS_PAGE)));
			add(more);
		}
		revalidate();
		repaint();
	}

	@Override
	public java.awt.Component add(java.awt.Component card)
	{
		if (getComponentCount() > 0)
		{
			super.add(Ui.gap(Ui.GAP));
		}
		return super.add(card);
	}

	private Ui.Card profit(Trader.Summary summary)
	{
		Ui.Card card = new Ui.Card();
		card.add(Ui.eyebrow("Recorded net profit · all time"));
		long profit = summary == null ? 0 : summary.profit;
		if (total == null)
		{
			total = new Ui.Count(profit);
		}
		else
		{
			total.set(profit, false);
		}
		card.add(total);
		int trades = summary == null ? 0 : summary.trades;
		card.add(Ui.text(trades > 0
			? BoxText.whole(trades) + " completed " + (trades == 1 ? "trade" : "trades") + " · after estimated tax and fees"
			: "Your first completed trade will appear here.", Brand.MUTED));
		if (summary != null && summary.selling != 0)
		{
			card.add(Ui.gap(6));
			card.add(Ui.status("Includes " + signed(summary.selling) + " from items still selling. It's saved once they sell.",
				summary.selling > 0 ? Brand.MADE_MONEY : Brand.AMBER, false));
		}
		if (summary != null && !summary.enabled)
		{
			card.add(Ui.gap(6));
			card.add(Ui.status("Saving is paused. Your history is still here. Resume saving on the website.", Brand.AMBER, false));
		}
		return card;
	}

	private Ui.Card days(List<Trader.Day> days)
	{
		Ui.Card card = new Ui.Card();
		card.add(Ui.eyebrow("Last 14 days · UTC"));
		card.add(Ui.gap(6));
		card.add(new Bars(days));
		return card;
	}

	private Ui.Card achievements(Trader.Achievements a, String newId)
	{
		Ui.Card card = new Ui.Card();
		card.add(Ui.eyebrow("Achievements · " + a.earned + " of " + a.total));
		if (a.latest != null)
		{
			boolean fresh = a.latest.id.equals(newId);
			card.add(Ui.gap(6));
			card.add(Ui.status(fresh ? "New" : "Latest", fresh ? Brand.MADE_MONEY : Brand.MUTED, fresh));
			card.add(Ui.title(a.latest.title));
			card.add(Ui.text(a.latest.text, Brand.MUTED));
		}
		if (a.next != null)
		{
			card.add(Ui.gap(Ui.GAP));
			card.add(Ui.label("Next", FontManager.getRunescapeSmallFont(), Brand.MUTED));
			card.add(Ui.title(a.next.title));
			card.add(Ui.text(a.next.text, Brand.MUTED));
			card.add(Ui.gap(6));
			card.add(new Meter(a.next));
		}
		return card;
	}

	/** "+40K GP" or "-3K GP". */
	private static String signed(long gp)
	{
		return (gp > 0 ? "+" : "") + Ui.gp(gp) + " GP";
	}

	/** Each day's recorded profit as a bar: green above the line, amber below, nothing on a day off. */
	private static final class Bars extends JPanel
	{
		private static final int HEIGHT = 64;
		private final List<Trader.Day> days;

		Bars(List<Trader.Day> days)
		{
			this.days = days;
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			setToolTipText("");
			long best = 0;
			int traded = 0;
			for (Trader.Day d : days)
			{
				best = Math.max(best, d.profit);
				traded += d.trades > 0 ? 1 : 0;
			}
			getAccessibleContext().setAccessibleName("Last 14 days: you traded on " + traded
				+ (traded == 1 ? " day" : " days") + (best > 0 ? ". Best day " + Ui.gp(best) + " GP." : "."));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, HEIGHT + 16);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT + 16);
		}

		private int at(int x)
		{
			return days.isEmpty() ? -1 : Math.max(0, Math.min(days.size() - 1, x * days.size() / Math.max(1, getWidth())));
		}

		@Override
		public String getToolTipText(MouseEvent e)
		{
			int i = at(e.getX());
			if (i < 0)
			{
				return null;
			}
			Trader.Day d = days.get(i);
			if (d.trades == 0 && d.selling == 0)
			{
				return label(d.day) + ": no trades";
			}
			String made = label(d.day) + ": " + (d.profit > 0 ? "+" : "") + BoxText.whole(d.profit) + " GP"
				+ (d.trades > 0 ? ", " + d.trades + (d.trades == 1 ? " trade" : " trades") : "");
			return d.selling == 0 ? made : made + " · " + signed(d.selling) + " from items still selling";
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			Ui.smooth(g2);
			int w = getWidth();
			long up = 0;
			long down = 0;
			for (Trader.Day d : days)
			{
				up = Math.max(up, d.profit);
				down = Math.max(down, -d.profit);
			}
			long range = Math.max(1, up + down);
			int base = (int) Math.round(HEIGHT * (double) up / range);
			if (up == 0 && down == 0)
			{
				base = HEIGHT;
			}
			g2.setColor(Brand.BORDER);
			g2.drawLine(0, base, w, base);
			int n = days.size();
			for (int i = 0; i < n; i++)
			{
				Trader.Day d = days.get(i);
				int x0 = i * w / n + 2;
				int x1 = (i + 1) * w / n - 2;
				if (d.profit == 0)
				{
					continue;
				}
				int h = Math.max(2, (int) Math.round(HEIGHT * (double) Math.abs(d.profit) / range));
				g2.setColor(d.profit > 0 ? Brand.MADE_MONEY : Brand.AMBER);
				g2.fillRoundRect(x0, d.profit > 0 ? base - h : base, x1 - x0, h, 3, 3);
			}
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g2.getFontMetrics();
			g2.setColor(Brand.MUTED);
			if (n > 0)
			{
				g2.drawString(label(days.get(0).day), 0, HEIGHT + 4 + fm.getAscent());
				String last = label(days.get(n - 1).day);
				g2.drawString(last, w - fm.stringWidth(last), HEIGHT + 4 + fm.getAscent());
			}
			g2.dispose();
		}

		/** Days are UTC days, as on the website, so they're named by date, never "Today". */
		private static String label(String day)
		{
			try
			{
				return LocalDate.parse(day).format(SHORT);
			}
			catch (RuntimeException e)
			{
				return day;
			}
		}
	}

	/** How far along the next achievement is: a thin bar, and the numbers under it. */
	private static final class Meter extends JPanel
	{
		private final Trader.Achievement next;

		Meter(Trader.Achievement next)
		{
			this.next = next;
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			getAccessibleContext().setAccessibleName(words());
		}

		private String words()
		{
			boolean gp = next.id.startsWith("net_");
			return (gp ? Ui.gp(next.current) : BoxText.whole(next.current)) + " of "
				+ (gp ? Ui.gp(next.target) + " GP" : BoxText.whole(next.target));
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, 24);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, 24);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			Ui.smooth(g2);
			int w = getWidth();
			g2.setColor(Ui.GROUND);
			g2.fillRoundRect(0, 0, w, 6, 6, 6);
			double share = next.target <= 0 ? 0 : Math.min(1, (double) next.current / next.target);
			if (share > 0)
			{
				g2.setColor(Brand.BLUE);
				g2.fillRoundRect(0, 0, Math.max(6, (int) Math.round(w * share)), 6, 6, 6);
			}
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(Brand.MUTED);
			g2.drawString(words(), 0, 10 + g2.getFontMetrics().getAscent());
			g2.dispose();
		}
	}
}
