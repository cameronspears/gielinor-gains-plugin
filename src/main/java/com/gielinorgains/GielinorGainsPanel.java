package com.gielinorgains;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The sidebar: the name and how fresh the prices are, then Trade, Market, Gear, Progress and Settings as a
 * segmented control over one view at a time. Swing thread only.
 */
class GielinorGainsPanel extends PluginPanel
{
	private static final String[] TABS = {"Trade", "Market", "Gear", "Progress", "Settings"};
	private static final int TRADE = 0;
	private static final int MARKET = 1;
	private static final int GEAR = 2;
	private static final int PROGRESS = 3;
	private static final int SETTINGS = 4;

	private final MarketPanel market;
	private final TradePanel trade;
	private final JPanel settings = new Column();
	private final CardLayout cards = new CardLayout();
	private final JPanel views = new JPanel(cards);
	private final Segments segments = new Segments();
	private final Ui.Dot statusDot = new Ui.Dot(Brand.MUTED, false);
	private final JLabel statusText = Ui.label(" ", FontManager.getRunescapeSmallFont(), Brand.MUTED);
	private final Runnable unlink;
	private final Runnable onTradeShown;
	private final Runnable onGearShown;
	private int selected = TRADE;
	private boolean active;
	private Boolean settingsSignedIn;
	private Long settingsBudget;

	GielinorGainsPanel(MarketPanel market, TradePanel trade, GearPanel gear, ProgressPanel progress, BufferedImage logo, Runnable unlink,
		Runnable onTradeShown, Runnable onGearShown)
	{
		super(false);
		this.market = market;
		this.trade = trade;
		this.unlink = unlink;
		this.onTradeShown = onTradeShown;
		this.onGearShown = onGearShown;
		progress.setSignIn(this::showTrade);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel top = new JPanel(new BorderLayout(0, Ui.GAP));
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(Ui.PAD, Ui.GAP, 2, Ui.GAP));
		JPanel brand = new JPanel(new BorderLayout(Ui.GAP, 0));
		brand.setOpaque(false);
		JLabel mark = new JLabel(new ImageIcon(logo.getScaledInstance(24, 24, java.awt.Image.SCALE_SMOOTH)));
		brand.add(mark, BorderLayout.WEST);
		JPanel words = new JPanel(new GridLayout(2, 1));
		words.setOpaque(false);
		words.add(Ui.label("Gielinor Gains", FontManager.getRunescapeBoldFont(), Brand.TEXT));
		JPanel status = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		status.setOpaque(false);
		status.add(statusDot);
		status.add(statusText);
		words.add(status);
		brand.add(words, BorderLayout.CENTER);
		top.add(brand, BorderLayout.NORTH);
		top.add(segments, BorderLayout.SOUTH);
		add(top, BorderLayout.NORTH);

		views.setOpaque(false);
		views.add(scroll(trade), TABS[TRADE]);
		views.add(market, TABS[MARKET]);
		views.add(scroll(gear), TABS[GEAR]);
		views.add(scroll(progress), TABS[PROGRESS]);
		views.add(scroll(settings), TABS[SETTINGS]);
		add(views, BorderLayout.CENTER);
		showSettings(false, null);
	}

	/** A column that's always as wide as the sidebar, so text wraps instead of running off. */
	private static class Column extends JPanel implements Scrollable
	{
		Column()
		{
			setLayout(new Ui.ColumnLayout());
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(Ui.GAP, Ui.GAP, Ui.GAP, Ui.GAP));
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction)
		{
			return visible.height - 32;
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}
	}

	private static JScrollPane scroll(JComponent content)
	{
		Column column = new Column();
		column.setBorder(null);
		column.add(content);
		JScrollPane scroll = new JScrollPane(column, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		return scroll;
	}

	/** Trade, Market, Gear, Progress and Settings as one segmented control; the arrow keys move between them. */
	private final class Segments extends JPanel
	{
		private int hovered = -1;

		Segments()
		{
			setOpaque(false);
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			getAccessibleContext().setAccessibleName("Trade, Market, Gear, Progress or Settings");
			java.awt.event.MouseAdapter mouse = new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					select(at(e.getX()));
				}

				@Override
				public void mouseMoved(java.awt.event.MouseEvent e)
				{
					hovered = at(e.getX());
					repaint();
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					hovered = -1;
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
			getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "left");
			getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "right");
			getActionMap().put("left", run(() -> select(Math.max(0, selected - 1))));
			getActionMap().put("right", run(() -> select(Math.min(TABS.length - 1, selected + 1))));
			addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusGained(java.awt.event.FocusEvent e)
				{
					repaint();
				}

				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					repaint();
				}
			});
		}

		private int at(int x)
		{
			int[] edges = edges(getWidth(), getFontMetrics(FontManager.getRunescapeSmallFont()));
			for (int i = 0; i < TABS.length; i++)
			{
				if (x < edges[i + 1])
				{
					return i;
				}
			}
			return TABS.length - 1;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, 28);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			Ui.smooth(g2);
			int w = getWidth();
			int h = getHeight();
			g2.setColor(Ui.GROUND);
			g2.fillRoundRect(0, 0, w, h, Brand.RADIUS, Brand.RADIUS);
			g2.setColor(Brand.BORDER);
			g2.drawRoundRect(0, 0, w - 1, h - 1, Brand.RADIUS, Brand.RADIUS);
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics fm = g2.getFontMetrics();
			int[] edges = edges(w, fm);
			for (int i = 0; i < TABS.length; i++)
			{
				int x0 = edges[i];
				int x1 = edges[i + 1];
				if (i == selected)
				{
					g2.setColor(Ui.FILL_HOVER);
					g2.fillRoundRect(x0 + 3, 3, x1 - x0 - 6, h - 6, 6, 6);
					if (isFocusOwner())
					{
						g2.setColor(Brand.BLUE);
						g2.drawRoundRect(x0 + 3, 3, x1 - x0 - 7, h - 7, 6, 6);
					}
				}
				g2.setColor(i == selected ? Brand.TEXT : i == hovered ? new Color(0xd4d4d4) : Brand.MUTED);
				g2.drawString(TABS[i], x0 + (x1 - x0 - fm.stringWidth(TABS[i])) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
			}
			g2.dispose();
		}
	}

	/**
	 * Where each tab starts, and the end: each as wide as its name plus an equal share of the room
	 * left, so five names fit the sidebar with space between them.
	 */
	static int[] edges(int width, FontMetrics fm)
	{
		int[] text = new int[TABS.length];
		int used = 0;
		for (int i = 0; i < TABS.length; i++)
		{
			text[i] = fm.stringWidth(TABS[i]);
			used += text[i];
		}
		int spare = Math.max(0, width - used);
		int[] edges = new int[TABS.length + 1];
		int x = 0;
		for (int i = 0; i < TABS.length; i++)
		{
			edges[i] = x;
			x += text[i] + spare * (i + 1) / TABS.length - spare * i / TABS.length;
		}
		edges[TABS.length] = width;
		return edges;
	}

	private static AbstractAction run(Runnable run)
	{
		return new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				run.run();
			}
		};
	}

	private void select(int tab)
	{
		selected = tab;
		cards.show(views, TABS[tab]);
		segments.repaint();
		// The Market list fetches only while it's on screen.
		if (tab == MARKET && active)
		{
			market.onActivate();
		}
		else
		{
			market.onDeactivate();
		}
		if (tab == TRADE)
		{
			onTradeShown.run();
		}
		if (tab == GEAR && active)
		{
			onGearShown.run();
		}
	}

	/** Whether the Trade tab is on screen. */
	boolean tradeShown()
	{
		return active && selected == TRADE;
	}

	void showTrade()
	{
		select(TRADE);
	}

	void showGearTab()
	{
		select(GEAR);
	}

	void showProgressTab()
	{
		select(PROGRESS);
	}

	void showSettingsTab()
	{
		select(SETTINGS);
	}

	/** The line under the name: how fresh the prices are, or that the website can't be reached. */
	void setStatus(String text, Color color, boolean live)
	{
		if (!text.equals(statusText.getText().trim()))
		{
			statusText.setText(" " + text);
		}
		statusDot.set(color, live);
	}

	@Override
	public void onActivate()
	{
		active = true;
		if (selected == MARKET)
		{
			market.onActivate();
		}
		if (selected == TRADE)
		{
			onTradeShown.run();
		}
		if (selected == GEAR)
		{
			onGearShown.run();
		}
	}

	@Override
	public void onDeactivate()
	{
		active = false;
		market.onDeactivate();
	}

	/** The Settings tab: the account, Unlink and the budget. Rebuilt only when either changes. */
	void showSettings(boolean signedIn, Long budget)
	{
		if (settingsSignedIn != null && settingsSignedIn == signedIn && java.util.Objects.equals(budget, settingsBudget))
		{
			return;
		}
		settingsSignedIn = signedIn;
		settingsBudget = budget;
		settings.removeAll();
		Ui.Card account = new Ui.Card();
		account.add(Ui.eyebrow("Your account"));
		if (signedIn)
		{
			account.add(Ui.title("Signed in"));
			account.add(Ui.text("Trader here uses the same plan as Trader on the website.", Brand.MUTED));
			account.add(Ui.gap(Ui.GAP));
			account.add(new Ui.Button("Unlink", false, unlink));
			account.add(Ui.gap(6));
			account.add(Ui.text("Unlink signs this RuneLite out. Your plan and trades stay on your account.", Brand.MUTED));
		}
		else
		{
			account.add(Ui.title("Not signed in"));
			account.add(Ui.text("Trader is free with a Gielinor Gains account.", Brand.MUTED));
			account.add(Ui.gap(6));
			account.add(new Ui.Link("Sign in", Brand.BLUE, this::showTrade));
		}
		settings.add(account);
		if (signedIn)
		{
			settings.add(Ui.gap(Ui.GAP));
			Ui.Card card = new Ui.Card();
			card.add(Ui.eyebrow("Budget"));
			card.add(Ui.text("How much GP Trader plans with. Type an amount like 5m or 500k.", Brand.MUTED));
			card.add(Ui.gap(6));
			card.add(trade.budgetField(budget, "Save budget"));
			settings.add(card);
		}
		settings.add(Ui.gap(Ui.GAP));
		Ui.Card more = new Ui.Card();
		more.add(Ui.text("Outlines, notifications and sounds are in RuneLite's settings for Gielinor Gains.",
			Brand.MUTED));
		settings.add(more);
		settings.revalidate();
		settings.repaint();
	}
}
