package com.gielinorgains;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import javax.swing.AbstractAction;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * The free Market in the sidebar: every flip, highest expected profit first, laid out like the
 * Market's cards on a phone. Click a row to make it "your flip" for the GE guide; its "Item page"
 * link opens the item's page. Only the rows in view are painted,
 * and a refresh changes the rows in place, so the list keeps its scroll position and never flickers.
 */
class MarketPanel extends PluginPanel
{
	private static final Color LABEL = Brand.MUTED;
	private static final Color VALUE = Brand.TEXT;
	/** The dark lit tile behind each icon, after the item page's icon style. */
	private static final Color TILE_TOP = new Color(0x2a2a2a);
	private static final Color TILE_BOTTOM = new Color(0x161616);
	static final String LINK_TEXT = "Item page";
	static final String ADD_TEXT = "Add to Trader";
	private static final String DOT_TEXT = " · ";
	static final String GUIDE_HINT = "Open the GE. The box shows each step. You click and type.";

	private static final int PAD = 6;
	private static final int ICON = 32;
	private static final int ROW_GAP = 4;
	/** The panel's full width less the header's side padding. */
	private static final int TEXT_WIDTH = PANEL_WIDTH + SCROLLBAR_WIDTH - (PAD + 2) - PAD;

	private final JTextArea status = wrapping();
	private final JTextArea statusNote = wrapping();
	private final JTextArea note = wrapping();
	private final JTextArea empty = wrapping();
	private final CardLayout cards = new CardLayout();
	private final JPanel body = new JPanel(cards);
	private final RowModel model = new RowModel();
	final JList<MarketList.Row> list = new JList<MarketList.Row>(model)
	{
		@Override
		public String getToolTipText(MouseEvent e)
		{
			MarketList.Row row = rowAt(e);
			return row == null ? null : "<html>" + String.join("<br>", row.getTooltip()) + "</html>";
		}
	};
	private volatile boolean active;
	/** Item icons from the game, asked for only when a row is first painted, then kept. */
	private final IntFunction<AsyncBufferedImage> iconLoader;
	private final Map<Integer, AsyncBufferedImage> icons = new HashMap<>();
	private int hovered = -1;

	/** "Your flip": the one the guide follows. Swing thread only. */
	private final IntConsumer onPin;
	private int pinnedId = -1;
	private String pinnedName;
	private final JPanel pinnedCard = new JPanel();
	private final JTextArea pinnedTitle = wrapping();
	private final JTextArea pinnedNumbers = wrapping();
	private final JTextArea pinnedHint = wrapping();
	/** After a sale: the next flip offered, pinned only if the player clicks Pin it. */
	private boolean sold;
	private int nextId = -1;
	private String nextName;
	private final JButton pinNext = new JButton("Pin it");
	/** Signed in: each row can go into Trader's plan. */
	private boolean trader;
	private IntConsumer onAdd = id -> { };

	MarketPanel(IntFunction<AsyncBufferedImage> iconLoader, IntConsumer onPin)
	{
		super(false);
		this.iconLoader = iconLoader;
		this.onPin = onPin;
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(PAD, PAD + 2, PAD, PAD));
		// The sidebar's own header carries the name; this one keeps only what the Market list needs to say.
		header.add(status);
		header.add(statusNote);
		header.add(note);

		// Your flip, pinned above the list, with real buttons so a keyboard or screen reader can use them.
		pinnedCard.setLayout(new BoxLayout(pinnedCard, BoxLayout.Y_AXIS));
		pinnedCard.setBackground(Brand.SURFACE);
		pinnedCard.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, Brand.BLUE),
			BorderFactory.createEmptyBorder(PAD, PAD + 2, PAD, PAD)));
		pinnedCard.setAlignmentX(LEFT_ALIGNMENT);
		pinnedTitle.setFont(FontManager.getRunescapeBoldFont());
		pinnedCard.add(pinnedTitle);
		pinnedCard.add(pinnedNumbers);
		pinnedCard.add(pinnedHint);
		JPanel buttons = new JPanel(new GridLayout(1, 2, PAD, 0));
		buttons.setBackground(Brand.SURFACE);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		buttons.setBorder(BorderFactory.createEmptyBorder(PAD, 0, 0, 0));
		JButton page = new JButton("Open page");
		page.addActionListener(e -> LinkBrowser.browse(ItemCheckOverlay.ITEM_PAGE + pinnedId));
		JButton clear = new JButton("Clear");
		clear.addActionListener(e -> pin(-1, null));
		buttons.add(page);
		buttons.add(clear);
		pinnedCard.add(buttons);
		pinNext.addActionListener(e -> pin(nextId, nextName));
		pinNext.setAlignmentX(LEFT_ALIGNMENT);
		pinNext.setVisible(false);
		JPanel nextRow = new JPanel(new GridLayout(1, 1));
		nextRow.setBackground(Brand.SURFACE);
		nextRow.setAlignmentX(LEFT_ALIGNMENT);
		nextRow.setBorder(BorderFactory.createEmptyBorder(PAD, 0, 0, 0));
		nextRow.add(pinNext);
		pinnedCard.add(nextRow);
		pinnedCard.setVisible(false);
		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setAlignmentX(LEFT_ALIGNMENT);
		top.add(header);
		JPanel pinnedWrap = new JPanel(new BorderLayout());
		pinnedWrap.setBackground(ColorScheme.DARK_GRAY_COLOR);
		pinnedWrap.setBorder(BorderFactory.createEmptyBorder(0, PAD, PAD, PAD));
		pinnedWrap.setAlignmentX(LEFT_ALIGNMENT);
		pinnedWrap.add(pinnedCard, BorderLayout.CENTER);
		top.add(pinnedWrap);
		add(top, BorderLayout.NORTH);

		list.setCellRenderer(new RowRenderer());
		list.setBackground(ColorScheme.DARK_GRAY_COLOR);
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		// Fixed sizes mean Swing never measures every row; the list then stretches to the panel's width.
		list.setFixedCellHeight(RowRenderer.HEIGHT + ROW_GAP);
		list.setFixedCellWidth(PANEL_WIDTH / 2);
		list.getAccessibleContext().setAccessibleName("Today's flips, highest expected profit first");
		MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				setHovered(list.getModel().getSize() == 0 ? -1 : indexAt(e));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setHovered(-1);
			}

			@Override
			public void mouseClicked(MouseEvent e)
			{
				MarketList.Row row = rowAt(e);
				if (row == null || e.getButton() != MouseEvent.BUTTON1)
				{
					return;
				}
				Rectangle cell = list.getCellBounds(list.locationToIndex(e.getPoint()), list.locationToIndex(e.getPoint()));
				int link = linkAt(e.getX() - cell.x, e.getY() - cell.y, trader, list);
				if (link == LINK_PAGE)
				{
					LinkBrowser.browse(ItemCheckOverlay.ITEM_PAGE + row.getId());
				}
				else if (link == LINK_ADD)
				{
					onAdd.accept(row.getId());
				}
				else
				{
					pin(row.getId(), row.getName());
				}
			}
		};
		list.addMouseListener(mouse);
		list.addMouseMotionListener(mouse);
		list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "pin");
		list.getActionMap().put("pin", new AbstractAction()
		{
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e)
			{
				MarketList.Row row = list.getSelectedValue();
				if (row != null)
				{
					pin(row.getId(), row.getName());
				}
			}
		});

		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createEmptyBorder(0, PAD, PAD, 0));
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		scroll.getVerticalScrollBar().setUnitIncrement(RowRenderer.HEIGHT / 3);

		JPanel emptyCard = new JPanel(new BorderLayout());
		emptyCard.setBackground(ColorScheme.DARK_GRAY_COLOR);
		emptyCard.setBorder(BorderFactory.createEmptyBorder(PAD, PAD + 2, PAD, PAD));
		emptyCard.add(empty, BorderLayout.NORTH);
		body.add(scroll, "list");
		body.add(emptyCard, "empty");
		add(body, BorderLayout.CENTER);

		setView(MarketList.message(MarketList.LOG_IN));
	}

	/** Whether the panel is open in the sidebar. Read on the client thread to decide on fetching. */
	boolean isActive()
	{
		return active;
	}

	@Override
	public void onActivate()
	{
		active = true;
	}

	@Override
	public void onDeactivate()
	{
		active = false;
	}

	/** Shows a new list. Call on the Swing thread. The selected item stays selected, wherever it moves. */
	void setView(MarketList.View view)
	{
		setStatus(view.getStatus());
		setText(note, view.getNote() == null ? null : BoxText.note(view.getNote()));
		MarketList.Row selected = list.getSelectedValue();
		model.setRows(view.getRows());
		list.clearSelection();
		for (int i = 0; selected != null && i < model.getSize(); i++)
		{
			if (model.getElementAt(i).getId() == selected.getId())
			{
				list.setSelectedIndex(i);
			}
		}
		setText(empty, view.getEmpty() == null ? null : BoxText.note(view.getEmpty()));
		cards.show(body, !view.getRows().isEmpty() || view.getEmpty() == null ? "list" : "empty");
		if (hovered >= model.getSize())
		{
			hovered = -1;
		}
		updatePinned();
	}

	/** Makes a flip "your flip", or clears it with -1, and tells the plugin so the guide follows it. */
	void pin(int id, String name)
	{
		sold = false;
		nextId = -1;
		pinnedId = id;
		pinnedName = name;
		onPin.accept(id);
		updatePinned();
		list.repaint();
	}

	/** After the pinned flip sells: offers the next one, which the player can pin with one click. */
	/** Shows "Add to Trader" on each row while signed in. Call on the Swing thread. */
	void setTrader(boolean signedIn, IntConsumer onAdd)
	{
		this.onAdd = onAdd;
		if (trader != signedIn)
		{
			trader = signedIn;
			list.repaint();
		}
	}

	void setSold(boolean sold, int nextId, String nextName)
	{
		this.sold = sold;
		this.nextId = nextId;
		this.nextName = nextName;
		updatePinned();
	}

	/** Shows the pinned flip's latest numbers, or says plainly that it has left today's flips. */
	private void updatePinned()
	{
		pinnedCard.setVisible(pinnedId > 0);
		if (pinnedId <= 0)
		{
			return;
		}
		MarketList.Row row = null;
		for (int i = 0; i < model.getSize() && row == null; i++)
		{
			row = model.getElementAt(i).getId() == pinnedId ? model.getElementAt(i) : null;
		}
		setText(pinnedTitle, BoxText.plain("Your flip: " + pinnedName));
		setText(pinnedNumbers, BoxText.note(row == null ? "It isn't in today's flips right now."
			: "Buy " + row.getBuy() + ", sell " + row.getSell() + ", quantity " + row.getQuantity() + "."));
		setText(pinnedHint, BoxText.note(!sold ? GUIDE_HINT
			: nextId > 0 ? "Sold! Next flip: " + nextName + "." : "Sold! No other flips right now."));
		pinNext.setVisible(sold && nextId > 0);
		revalidate();
	}

	/** Changes only the lines at the top, such as the price age each minute. Call on the Swing thread. */
	void setStatus(List<BoxText.Line> lines)
	{
		setText(status, lines.isEmpty() ? null : lines.get(0));
		setText(statusNote, lines.size() < 2 ? null : lines.get(1));
	}

	private AsyncBufferedImage icon(int id)
	{
		return icons.computeIfAbsent(id, i ->
		{
			AsyncBufferedImage icon = iconLoader.apply(i);
			if (icon != null)
			{
				icon.onLoaded(list::repaint);
			}
			return icon;
		});
	}

	private static void setText(JTextArea text, BoxText.Line line)
	{
		text.setVisible(line != null);
		if (line != null)
		{
			text.setForeground(line.getKind() == BoxText.Kind.NOTE ? LABEL : VALUE);
			// Its width first, so its height counts every wrapped line from the first layout.
			text.setSize(TEXT_WIDTH, 1);
			text.setText(line.getLeft());
		}
	}

	/** Read-only text that wraps at the panel's width, for sentences too long for one line. */
	private static JTextArea wrapping()
	{
		JTextArea text = new JTextArea();
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setEditable(false);
		text.setFocusable(false);
		text.setOpaque(false);
		text.setBorder(null);
		text.setAlignmentX(LEFT_ALIGNMENT);
		text.setVisible(false);
		return text;
	}

	private int indexAt(MouseEvent e)
	{
		int index = list.locationToIndex(e.getPoint());
		return index >= 0 && list.getCellBounds(index, index).contains(e.getPoint()) ? index : -1;
	}

	private MarketList.Row rowAt(MouseEvent e)
	{
		int index = indexAt(e);
		return index < 0 ? null : model.getElementAt(index);
	}

	private void setHovered(int index)
	{
		if (index != hovered)
		{
			hovered = index;
			list.setCursor(Cursor.getPredefinedCursor(index < 0 ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
			list.repaint();
		}
	}

	static final int LINK_NONE = 0;
	static final int LINK_PAGE = 1;
	static final int LINK_ADD = 2;

	/**
	 * Which link a point in a row is on, relative to the row's top left: the row's last line reads
	 * "Add to Trader · Item page" when signed in, else "Item page".
	 */
	static int linkAt(int x, int y, boolean trader, JComponent on)
	{
		int top = RowRenderer.LINKS_Y - 12;
		if (y < top - 2 || y > top + 16)
		{
			return LINK_NONE;
		}
		FontMetrics fm = on.getFontMetrics(FontManager.getRunescapeSmallFont());
		int add = trader ? fm.stringWidth(ADD_TEXT) : 0;
		int start = trader ? add + fm.stringWidth(DOT_TEXT) : 0;
		if (trader && x >= PAD - 4 && x <= PAD + add + 4)
		{
			return LINK_ADD;
		}
		int page = PAD + start;
		return x >= page - 4 && x <= page + fm.stringWidth(LINK_TEXT) + 4 ? LINK_PAGE : LINK_NONE;
	}

	/** The rows, changed in place so Swing keeps the scroll position and repaints only what changed. */
	static class RowModel extends AbstractListModel<MarketList.Row>
	{
		private List<MarketList.Row> rows = new ArrayList<>();

		void setRows(List<MarketList.Row> next)
		{
			int before = rows.size();
			rows = next;
			int after = next.size();
			if (after < before)
			{
				fireIntervalRemoved(this, after, before - 1);
			}
			else if (after > before)
			{
				fireIntervalAdded(this, before, after - 1);
			}
			if (Math.min(before, after) > 0)
			{
				fireContentsChanged(this, 0, Math.min(before, after) - 1);
			}
		}

		@Override
		public int getSize()
		{
			return rows.size();
		}

		@Override
		public MarketList.Row getElementAt(int index)
		{
			return rows.get(index);
		}
	}

	/**
	 * One flip, painted directly, as the Market's phone cards lay it out: icon, name and record;
	 * Expected profit and Chance; then Buy, Sell and Quantity.
	 */
	class RowRenderer extends JPanel implements ListCellRenderer<MarketList.Row>
	{
		static final int LINKS_Y = PAD + ICON + 6 + 26 + 4 + 26 + 16;
		static final int HEIGHT = LINKS_Y + 4 + PAD;

		private MarketList.Row row;
		private boolean highlighted;
		private boolean focused;

		@Override
		public Component getListCellRendererComponent(JList<? extends MarketList.Row> list, MarketList.Row row,
			int index, boolean selected, boolean focus)
		{
			this.row = row;
			if (row == null)
			{
				return this;
			}
			this.highlighted = index == hovered || (selected && list.hasFocus());
			this.focused = focus && list.hasFocus();
			getAccessibleContext().setAccessibleName((row.getId() == pinnedId ? "Your flip. " : "") + row.getName() + ". Expected profit " + row.getProfit()
				+ ". Chance " + row.getChance() + ". Buy " + row.getBuy() + ". Sell " + row.getSell()
				+ ". Quantity " + row.getQuantity() + "." + (row.getRecord() == null ? "" : " " + row.getRecord() + "."));
			return this;
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (row == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g;
			int width = getWidth() - PAD;
			// The site's card; blue marks what you can act on: hover, keyboard focus and your flip.
			Brand.card(g2, 0, 0, width, HEIGHT, highlighted ? Brand.BLUE : Brand.BORDER);
			if (focused)
			{
				g2.setColor(Brand.BLUE);
				g2.drawRoundRect(1, 1, width - 3, HEIGHT - 3, Brand.RADIUS, Brand.RADIUS);
			}
			if (row.getId() == pinnedId)
			{
				g2.setColor(Brand.BLUE);
				g2.fillRoundRect(0, 0, 4, HEIGHT, 4, 4);
			}

			g2.setPaint(new GradientPaint(0, PAD, TILE_TOP, 0, PAD + ICON, TILE_BOTTOM));
			g2.fillRoundRect(PAD, PAD, ICON, ICON, 6, 6);
			Image icon = icon(row.getId());
			if (icon != null)
			{
				g2.drawImage(icon, PAD, PAD, null);
			}
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics small = g2.getFontMetrics();
			int linkX = PAD;
			if (trader)
			{
				link(g2, ADD_TEXT, linkX, LINKS_Y);
				linkX += small.stringWidth(ADD_TEXT);
				g2.setColor(LABEL);
				g2.drawString(DOT_TEXT, linkX, LINKS_Y);
				linkX += small.stringWidth(DOT_TEXT);
			}
			link(g2, LINK_TEXT, linkX, LINKS_Y);

			int x = PAD + ICON + PAD;
			g2.setFont(FontManager.getRunescapeBoldFont());
			text(g2, row.getName(), VALUE, x, PAD + 14, width - x - PAD);
			if (row.getRecord() != null)
			{
				g2.setFont(FontManager.getRunescapeSmallFont());
				text(g2, row.getRecord(), LABEL, x, PAD + 29, width - x - PAD);
			}

			// Lead figures in bold, as the site's cards lead with them; offers quieter beneath.
			int third = (width - 2 * PAD) / 3;
			int y = PAD + ICON + 6;
			cell(g2, "Expected profit", row.getProfit(), FontManager.getRunescapeBoldFont(), PAD, y, 2 * third);
			cell(g2, "Chance", row.getChance(), FontManager.getRunescapeBoldFont(), PAD + 2 * third, y, third);
			y += 26 + 4;
			cell(g2, "Buy", row.getBuy(), FontManager.getRunescapeFont(), PAD, y, third);
			cell(g2, "Sell", row.getSell(), FontManager.getRunescapeFont(), PAD + third, y, third);
			cell(g2, "Quantity", row.getQuantity(), FontManager.getRunescapeFont(), PAD + 2 * third, y, third);
		}

		private void link(Graphics2D g, String text, int x, int y)
		{
			g.setColor(Brand.BLUE);
			g.drawString(text, x, y);
			g.drawLine(x, y + 2, x + g.getFontMetrics().stringWidth(text), y + 2);
		}

		private void cell(Graphics2D g, String label, String value, java.awt.Font font, int x, int y, int width)
		{
			g.setFont(FontManager.getRunescapeSmallFont());
			text(g, label, LABEL, x, y + 10, width - 4);
			g.setFont(font);
			text(g, value, VALUE, x, y + 24, width - 4);
		}

		/** Draws text, cut with "..." if it can't fit. No shadow: the card is dark, as on the site. */
		private void text(Graphics2D g, String text, Color color, int x, int y, int width)
		{
			FontMetrics fm = g.getFontMetrics();
			String shown = text;
			if (fm.stringWidth(shown) > width)
			{
				while (shown.length() > 1 && fm.stringWidth(shown + "...") > width)
				{
					shown = shown.substring(0, shown.length() - 1);
				}
				shown += "...";
			}
			g.setColor(color);
			g.drawString(shown, x, y);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(PANEL_WIDTH - PAD, HEIGHT + ROW_GAP);
		}
	}
}
