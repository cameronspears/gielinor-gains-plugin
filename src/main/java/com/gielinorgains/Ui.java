package com.gielinorgains;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.accessibility.AccessibleRole;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import net.runelite.client.ui.FontManager;

/**
 * The sidebar's pieces, painted to match the site's dark cards: rounded cards with an accent
 * edge, filled and quiet buttons, text links, breathing status dots, and the few small motions
 * (cards dealing in, a number rolling up). Motion never holds up a click: everything is usable
 * the moment it's shown. Swing thread only.
 */
final class Ui
{
	static final int PAD = 10;
	static final int GAP = 8;
	static final Color GROUND = new Color(0x0a0a0a);
	static final Color FILL = new Color(0x1d1d1d);
	static final Color FILL_HOVER = new Color(0x262626);
	static final Color ACCENT = new Color(0x2563eb);
	static final Color ACCENT_HOVER = new Color(0x3b74f0);
	static final Color ACCENT_SHADOW = new Color(0x0b2a6b);
	static final Color BLUE_EDGE = new Color(0x1e3a8a);
	static final Color GREEN_EDGE = new Color(0x14532d);
	static final Color AMBER_EDGE = new Color(0x78350f);

	/** RuneLite has no reduced-motion setting of its own, so the plugin has one. */
	static volatile boolean reduceMotion;

	private Ui()
	{
	}

	static void smooth(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
	}

	static double easeOut(double t)
	{
		t = Math.max(0, Math.min(1, t));
		return 1 - Math.pow(1 - t, 3);
	}

	/** A small overshoot, for something new arriving. */
	static double easeBack(double t)
	{
		t = Math.max(0, Math.min(1, t)) - 1;
		return 1 + 2.2 * t * t * t + 1.2 * t * t;
	}

	/**
	 * One timer for every moving thing, running only while something moves: a breathing dot on
	 * screen or a card dealing in. Nothing moves while the sidebar is closed.
	 */
	static final class Clock
	{
		private static final Set<JComponent> MOVING = new LinkedHashSet<>();
		private static final Timer TIMER = new Timer(33, e -> tick());

		private Clock()
		{
		}

		static void start(JComponent c)
		{
			if (reduceMotion)
			{
				return;
			}
			MOVING.add(c);
			if (!TIMER.isRunning())
			{
				TIMER.start();
			}
		}

		static void stop(JComponent c)
		{
			MOVING.remove(c);
			if (MOVING.isEmpty())
			{
				TIMER.stop();
			}
		}

		static boolean running()
		{
			return TIMER.isRunning();
		}

		static void tick()
		{
			for (JComponent c : MOVING.toArray(new JComponent[0]))
			{
				if (!c.isDisplayable())
				{
					// Taken off screen, or never put there (a card dealt while the sidebar was closed).
					// It can't paint to finish, so it would tick forever; it starts again when it's added.
					MOVING.remove(c);
				}
				else if (c.isShowing())
				{
					c.repaint();
				}
			}
			if (MOVING.isEmpty())
			{
				TIMER.stop();
			}
		}
	}

	// --- Layout ---

	/** Something whose height depends on how wide it is, like wrapping text beside an icon. */
	interface WidthAware
	{
		int heightFor(int width);
	}

	/**
	 * A column where every child takes the full width and is as tall as it needs at that width,
	 * so wrapping text is never cut off. Swing's BoxLayout asks for heights before it knows the
	 * width, which is why it can't do this.
	 */
	static final class ColumnLayout implements java.awt.LayoutManager
	{
		/** The sidebar's width, for the first layout before the column has been given one. */
		private static final int FALLBACK_WIDTH = 225;

		static int heightFor(Component c, int width)
		{
			if (!c.isVisible())
			{
				return 0;
			}
			if (c instanceof WidthAware)
			{
				return ((WidthAware) c).heightFor(width);
			}
			if (c instanceof java.awt.Container && ((java.awt.Container) c).getLayout() instanceof ColumnLayout)
			{
				return ((ColumnLayout) ((java.awt.Container) c).getLayout()).height((java.awt.Container) c, width);
			}
			if (c instanceof JTextArea)
			{
				// A wrapping text area works out its lines from its current width.
				c.setSize(width, Short.MAX_VALUE);
			}
			return c.getPreferredSize().height;
		}

		int height(java.awt.Container parent, int width)
		{
			Insets in = parent.getInsets();
			int inner = Math.max(1, width - in.left - in.right);
			int h = in.top + in.bottom;
			for (Component c : parent.getComponents())
			{
				h += heightFor(c, inner);
			}
			return h;
		}

		private static int width(java.awt.Container parent)
		{
			return parent.getWidth() > 0 ? parent.getWidth() : FALLBACK_WIDTH;
		}

		@Override
		public void layoutContainer(java.awt.Container parent)
		{
			Insets in = parent.getInsets();
			int inner = Math.max(1, parent.getWidth() - in.left - in.right);
			int y = in.top;
			for (Component c : parent.getComponents())
			{
				int h = heightFor(c, inner);
				c.setBounds(in.left, y, inner, h);
				y += h;
			}
		}

		@Override
		public Dimension preferredLayoutSize(java.awt.Container parent)
		{
			int w = width(parent);
			return new Dimension(w, height(parent, w));
		}

		@Override
		public Dimension minimumLayoutSize(java.awt.Container parent)
		{
			return preferredLayoutSize(parent);
		}

		@Override
		public void addLayoutComponent(String name, Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(Component comp)
		{
		}
	}

	// --- Cards ---

	/**
	 * The site's card: #101010, a hairline border and 8px corners, with an optional coloured edge
	 * down its left side for the card that matters now. It can deal in: fading up a few pixels, or
	 * dropping in with a small bounce for something new.
	 */
	static class Card extends JPanel
	{
		private final Color edge;
		private final Color border;
		private long appearAt = -1;
		private int appearMs;
		private boolean bounce;

		Card(Color edge, Color border)
		{
			this.edge = edge;
			this.border = border;
			setOpaque(false);
			setLayout(new ColumnLayout());
			setBorder(BorderFactory.createEmptyBorder(PAD, PAD + (edge == null ? 0 : 2), PAD, PAD));
			setAlignmentX(LEFT_ALIGNMENT);
		}

		Card()
		{
			this(null, Brand.BORDER);
		}

		/** Deals the card in after {@code delayMs}: a short rise, or a drop with a bounce. */
		Card appear(int delayMs, boolean bounce)
		{
			if (reduceMotion)
			{
				return this;
			}
			this.appearAt = System.currentTimeMillis() + delayMs;
			this.appearMs = bounce ? 420 : 240;
			this.bounce = bounce;
			Clock.start(this);
			return this;
		}

		@Override
		public void addNotify()
		{
			super.addNotify();
			if (appearAt >= 0)
			{
				Clock.start(this);
			}
		}

		@Override
		public void paint(Graphics g)
		{
			if (appearAt < 0)
			{
				super.paint(g);
				return;
			}
			double t = (System.currentTimeMillis() - appearAt) / (double) appearMs;
			if (t >= 1)
			{
				appearAt = -1;
				Clock.stop(this);
				super.paint(g);
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			double eased = bounce ? easeBack(t) : easeOut(t);
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) easeOut(t * 1.4)));
			g2.translate(0, (int) Math.round((bounce ? -14 : 6) * (1 - eased)));
			super.paint(g2);
			g2.dispose();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			smooth(g2);
			g2.setColor(Brand.SURFACE);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), Brand.RADIUS, Brand.RADIUS);
			if (edge != null)
			{
				// The edge sits inside the card's corners, as the site's accent cards do. clip() narrows
				// the scroll pane's clip rather than replacing it, so a card scrolled under the tabs
				// never paints over them.
				Graphics2D inside = (Graphics2D) g2.create();
				inside.clip(new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), Brand.RADIUS, Brand.RADIUS));
				inside.setColor(edge);
				inside.fillRect(0, 0, 3, getHeight());
				inside.dispose();
			}
			g2.setColor(border);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Brand.RADIUS, Brand.RADIUS);
			g2.dispose();
		}
	}

	/** A column of cards with a gap between each. */
	static JPanel stack()
	{
		JPanel stack = new JPanel(new ColumnLayout());
		stack.setOpaque(false);
		stack.setAlignmentX(Component.LEFT_ALIGNMENT);
		return stack;
	}

	static Component gap(int height)
	{
		JPanel gap = new JPanel();
		gap.setOpaque(false);
		gap.setAlignmentX(Component.LEFT_ALIGNMENT);
		Dimension size = new Dimension(1, height);
		gap.setPreferredSize(size);
		gap.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		return gap;
	}

	// --- Text ---

	static JLabel label(String text, Font font, Color color)
	{
		JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(color);
		label.setAlignmentX(Component.LEFT_ALIGNMENT);
		return label;
	}

	/** A small grey label over a card's title, like "Next step". */
	static JLabel eyebrow(String text)
	{
		return label(text, FontManager.getRunescapeSmallFont(), Brand.MUTED);
	}

	/** A card's bold title, wrapping like the text under it. */
	static JTextArea title(String text)
	{
		JTextArea title = text(text, Brand.TEXT);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return title;
	}

	/** Wrapping text that sizes to the card's width. */
	static JTextArea text(String text, Color color)
	{
		JTextArea area = new JTextArea(text);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setFont(FontManager.getRunescapeSmallFont());
		area.setForeground(color);
		area.setAlignmentX(Component.LEFT_ALIGNMENT);
		area.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		return area;
	}

	/**
	 * GP as people say it: 412, 3.45M, 22.9M, 230K. Exact figures stay where they matter, like
	 * offer prices and Today so far.
	 */
	static String gp(long value)
	{
		long abs = Math.abs(value);
		String sign = value < 0 ? "-" : "";
		if (abs >= 1_000_000_000L)
		{
			return sign + trim(abs / 1e9, abs < 10_000_000_000L ? 2 : 1) + "B";
		}
		if (abs >= 1_000_000L)
		{
			return sign + trim(abs / 1e6, abs < 10_000_000L ? 2 : 1) + "M";
		}
		if (abs >= 10_000L)
		{
			return sign + Math.round(abs / 1e3) + "K";
		}
		return sign + BoxText.whole(abs);
	}

	private static String trim(double value, int decimals)
	{
		String text = String.format(java.util.Locale.ROOT, "%." + decimals + "f", value);
		return text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
	}

	/** A typed amount of GP: 5m, 2.5m, 500k, 24,600,000. -1 when it isn't one. */
	static long parseGp(String typed)
	{
		String text = typed == null ? "" : typed.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace(" ", "");
		double multiplier = 1;
		if (text.endsWith("k") || text.endsWith("m") || text.endsWith("b"))
		{
			char unit = text.charAt(text.length() - 1);
			multiplier = unit == 'k' ? 1e3 : unit == 'm' ? 1e6 : 1e9;
			text = text.substring(0, text.length() - 1);
		}
		if (!text.matches("\\d{1,13}(\\.\\d{1,3})?"))
		{
			return -1;
		}
		double gp = Double.parseDouble(text) * multiplier;
		return gp >= 1 && gp <= 1e12 ? (long) Math.floor(gp) : -1;
	}

	// --- Buttons and links ---

	/**
	 * A filled blue button for the one main action, or a quiet grey one. It takes the card's full
	 * width, lifts a shade on hover, sinks a pixel when pressed, and shows the site's focus ring.
	 */
	static class Button extends JButton
	{
		private final boolean primary;
		private boolean hover;

		Button(String text, boolean primary, Runnable onClick)
		{
			super(text);
			this.primary = primary;
			setFont(FontManager.getRunescapeBoldFont());
			setForeground(Brand.TEXT);
			setContentAreaFilled(false);
			setBorderPainted(false);
			setFocusPainted(false);
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			addActionListener(e -> onClick.run());
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseEntered(MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
		}

		@Override
		public Dimension getPreferredSize()
		{
			FontMetrics fm = getFontMetrics(getFont());
			return new Dimension(fm.stringWidth(getText()) + 24, 30);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			smooth(g2);
			int w = getWidth();
			int h = getHeight() - 2;
			boolean pressed = getModel().isPressed();
			int sink = pressed ? 1 : 0;
			if (primary)
			{
				g2.setColor(ACCENT_SHADOW);
				g2.fillRoundRect(0, 2, w, h, Brand.RADIUS, Brand.RADIUS);
			}
			g2.setColor(primary ? (hover ? ACCENT_HOVER : ACCENT) : (hover ? FILL_HOVER : FILL));
			g2.fillRoundRect(0, sink, w, h, Brand.RADIUS, Brand.RADIUS);
			if (!primary)
			{
				g2.setColor(Brand.BORDER);
				g2.drawRoundRect(0, sink, w - 1, h - 1, Brand.RADIUS, Brand.RADIUS);
			}
			if (isFocusOwner())
			{
				g2.setColor(Brand.BLUE);
				g2.setStroke(new BasicStroke(2f));
				g2.drawRoundRect(1, sink + 1, w - 3, h - 3, Brand.RADIUS, Brand.RADIUS);
			}
			g2.setFont(getFont());
			FontMetrics fm = g2.getFontMetrics();
			g2.setColor(isEnabled() ? Brand.TEXT : Brand.MUTED);
			g2.drawString(getText(), (w - fm.stringWidth(getText())) / 2, sink + (h + fm.getAscent() - fm.getDescent()) / 2);
			g2.dispose();
		}
	}

	/** A text link: blue for a place to go, grey for a quiet choice like "Skip for now". */
	static class Link extends JLabel
	{
		private final Color color;

		Link(String text, Color color, Runnable onClick)
		{
			super(text);
			this.color = color;
			setFont(FontManager.getRunescapeSmallFont());
			setForeground(color);
			setFocusable(true);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			setAlignmentX(Component.LEFT_ALIGNMENT);
			getAccessibleContext().setAccessibleName(text);
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					onClick.run();
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					setForeground(Brand.TEXT);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					setForeground(Link.this.color);
				}
			});
			addKeyListener(new KeyAdapter()
			{
				@Override
				public void keyPressed(KeyEvent e)
				{
					if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE)
					{
						onClick.run();
					}
				}
			});
			addFocusListener(new FocusAdapter()
			{
				@Override
				public void focusGained(FocusEvent e)
				{
					repaint();
				}

				@Override
				public void focusLost(FocusEvent e)
				{
					repaint();
				}
			});
		}

		@Override
		public javax.accessibility.AccessibleContext getAccessibleContext()
		{
			if (accessibleContext == null)
			{
				accessibleContext = new AccessibleJLabel()
				{
					@Override
					public AccessibleRole getAccessibleRole()
					{
						return AccessibleRole.HYPERLINK;
					}
				};
			}
			return accessibleContext;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			FontMetrics fm = g.getFontMetrics(getFont());
			int y = (getHeight() + fm.getAscent()) / 2 + 1;
			g.setColor(getForeground());
			g.drawLine(0, y, fm.stringWidth(getText()), y);
			if (isFocusOwner())
			{
				g.setColor(Brand.BLUE);
				g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
			}
		}
	}

	/** Links on one line, separated by dots: "Already done · Skip for now". */
	static JPanel links(Component... parts)
	{
		JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < parts.length; i++)
		{
			if (i > 0)
			{
				row.add(label(" · ", FontManager.getRunescapeSmallFont(), Brand.MUTED));
			}
			row.add(parts[i]);
		}
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	// --- Status ---

	/** A coloured dot. A live one breathes, once every 2 seconds, so you can tell it's watching. */
	static class Dot extends JComponent
	{
		private Color color;
		private boolean live;
		private long beatAt;

		Dot(Color color, boolean live)
		{
			this.color = color;
			this.live = live;
			setPreferredSize(new Dimension(10, 10));
			setMaximumSize(new Dimension(10, 10));
		}

		void set(Color color, boolean live)
		{
			this.color = color;
			this.live = live;
			if (isDisplayable())
			{
				if (live)
				{
					Clock.start(this);
				}
				else
				{
					Clock.stop(this);
				}
			}
			repaint();
		}

		/** Pulses once. */
		Dot beat()
		{
			beatAt = System.currentTimeMillis();
			Clock.start(this);
			return this;
		}

		@Override
		public void addNotify()
		{
			super.addNotify();
			if (live || beatAt > 0)
			{
				Clock.start(this);
			}
		}

		@Override
		public void removeNotify()
		{
			Clock.stop(this);
			super.removeNotify();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			smooth(g2);
			int cx = getWidth() / 2;
			int cy = getHeight() / 2;
			if (beatAt > 0 && !reduceMotion)
			{
				// One beat when the game shows a change, so you can tell it was seen.
				double t = (System.currentTimeMillis() - beatAt) / 700.0;
				if (t < 1)
				{
					int r = (int) Math.round(3 + 6 * t);
					g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (140 * (1 - t))));
					g2.fillOval(cx - r, cy - r, r * 2, r * 2);
				}
				else
				{
					beatAt = 0;
					if (!live)
					{
						Clock.stop(this);
					}
				}
			}
			if (live && !reduceMotion)
			{
				double t = System.currentTimeMillis() % 2000 / 2000.0;
				int r = (int) Math.round(3 + 4 * t);
				g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), (int) (90 * (1 - t))));
				g2.fillOval(cx - r, cy - r, r * 2, r * 2);
			}
			g2.setColor(color);
			g2.fillOval(cx - 3, cy - 3, 6, 6);
			g2.dispose();
		}
	}

	/** A dot and a few words, like "Watching for your buy in slot 1". Long words wrap beside the dot. */
	static JPanel status(String text, Color color, boolean live)
	{
		return new Status(text, color, live);
	}

	private static final class Status extends JPanel implements WidthAware
	{
		private static final int INDENT = 14;
		private final Dot dot;
		private final JTextArea words;

		Status(String text, Color color, boolean live)
		{
			super(null);
			setOpaque(false);
			dot = new Dot(color, live);
			words = text(text, Brand.MUTED);
			add(dot);
			add(words);
		}

		@Override
		public int heightFor(int width)
		{
			return ColumnLayout.heightFor(words, width - INDENT);
		}

		@Override
		public void doLayout()
		{
			int h = ColumnLayout.heightFor(words, getWidth() - INDENT);
			words.setBounds(INDENT, 0, getWidth() - INDENT, h);
			// The dot sits on the first line.
			int line = getFontMetrics(words.getFont()).getHeight();
			dot.setBounds(0, 1 + (line - 10) / 2, 10, 10);
		}
	}

	// --- Input ---

	/** The site's text field: dark fill, hairline border, blue when it has focus. */
	static JTextField field(String text, String accessibleName)
	{
		JTextField field = new JTextField(text);
		field.setFont(FontManager.getRunescapeBoldFont());
		field.setForeground(Brand.TEXT);
		field.setCaretColor(Brand.TEXT);
		field.setBackground(GROUND);
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.getAccessibleContext().setAccessibleName(accessibleName);
		Runnable edge = () -> field.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(field.isFocusOwner() ? Brand.BLUE : Brand.BORDER, 1),
			BorderFactory.createEmptyBorder(6, 8, 6, 8)));
		edge.run();
		field.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent e)
			{
				edge.run();
				field.selectAll();
			}

			@Override
			public void focusLost(FocusEvent e)
			{
				edge.run();
			}
		});
		return field;
	}

	// --- Numbers ---

	/**
	 * A big number that rolls up to its new value, with a glint across it the first time it goes
	 * above zero. The words beside it say the same thing.
	 */
	static class Count extends JComponent
	{
		private long from;
		private long to;
		private long startedAt = -1;
		private long glintAt = -1;
		private final Font font = FontManager.getRunescapeBoldFont().deriveFont(26f);

		Count(long value)
		{
			from = value;
			to = value;
			setAlignmentX(Component.LEFT_ALIGNMENT);
		}

		void set(long value, boolean glint)
		{
			if (value == to)
			{
				return;
			}
			if (reduceMotion)
			{
				from = value;
				to = value;
				repaint();
				return;
			}
			from = shown();
			to = value;
			startedAt = System.currentTimeMillis();
			if (glint)
			{
				glintAt = startedAt + 500;
			}
			Clock.start(this);
		}

		private long shown()
		{
			if (startedAt < 0)
			{
				return to;
			}
			double t = (System.currentTimeMillis() - startedAt) / 700.0;
			return t >= 1 ? to : from + Math.round((to - from) * easeOut(t));
		}

		@Override
		public Dimension getPreferredSize()
		{
			FontMetrics fm = getFontMetrics(font);
			return new Dimension(fm.stringWidth(text(to)) + 4, fm.getHeight());
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
		}

		private static String text(long value)
		{
			return (value > 0 ? "+" : "") + BoxText.whole(value) + " GP";
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			smooth(g2);
			g2.setFont(font);
			FontMetrics fm = g2.getFontMetrics();
			long value = shown();
			String text = text(value);
			int y = fm.getAscent();
			g2.setColor(value > 0 ? Brand.MADE_MONEY : value < 0 ? Brand.AMBER : Brand.TEXT);
			g2.drawString(text, 0, y);
			long now = System.currentTimeMillis();
			double glint = glintAt < 0 ? -1 : (now - glintAt) / 600.0;
			if (glint >= 0 && glint < 1)
			{
				int width = fm.stringWidth(text);
				float x = (float) (-40 + (width + 80) * easeOut(glint));
				g2.clipRect(0, 0, width, getHeight());
				g2.setPaint(new LinearGradientPaint(x - 20, 0, x + 20, 0, new float[]{0f, 0.5f, 1f},
					new Color[]{new Color(255, 255, 255, 0), new Color(255, 255, 255, 150), new Color(255, 255, 255, 0)}));
				g2.drawString(text, 0, y);
			}
			if ((startedAt < 0 || now - startedAt >= 700) && (glintAt < 0 || glint >= 1))
			{
				startedAt = -1;
				glintAt = -1;
				Clock.stop(this);
			}
			g2.dispose();
		}
	}
}
