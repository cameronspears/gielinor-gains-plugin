package com.gielinorgains;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import lombok.Value;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * The Trade tab, in the website Trader's words: sign in by code, your budget, the one next step,
 * a fresh Rebound, your slots and Today so far. It shows what the plugin hands it and passes
 * presses back; it never touches the game. Swing thread only.
 */
class TradePanel extends JPanel
{
	private static final String LINK_PAGE = TraderApi.SITE + "/link";
	private static final String REBOUND_RECORD = TraderApi.SITE + "/rebound-record";

	/** What the tab shows, built on the client thread. */
	@Value
	static class View
	{
		boolean signedIn;
		/** The code to type on the website while signing in, else null. */
		String code;
		/** A problem to show in plain words, else null. */
		String problem;
		/** The website can't be reached; the plan is safe. */
		boolean offline;
		Trader.Step step;
		List<Trader.SlotRow> slots;
		/** GP the plan has put into its items. */
		long invested;
		Trader.Rebound rebound;
		Trader.Today today;
		Long budget;
		/** Coins in the bank and inventory, or -1 before the bank's been opened. */
		long coins;
		/** On a free world only 3 GE slots can be used. */
		boolean freeWorld;
		/** A buy that just filled or a flip that just finished, else null. */
		Trader.Moment moment;
		/** What the next free slots would get. */
		List<UpNext> upNext;
		/** Just signed in, for a moment. */
		boolean justLinked;
		/** The best finished flip this session, else null. */
		Trader.Moment best;
		/** Whether the plan has a free slot for a Rebound. */
		boolean room;
		/** With every slot full, the unstarted pick a Rebound would take the place of, else null. */
		String swap;
		/** In a Leagues, Deadman or beta world, where Trader pauses. */
		boolean otherWorld;
		/** Make and Sell when Trader's picks are full, else null. */
		MakeSellCard makeSell;
	}

	/** Make and Sell's card, as the website's MakeSellOfferCard shows it. */
	@Value
	static class MakeSellCard
	{
		enum State
		{
			/** A swap to take. */
			OFFER,
			/** The swap taken this window. */
			TAKEN,
			/** Marked done this window. */
			DONE,
			/** No swap has room either. */
			FULL
		}

		State state;
		/** The swap offered, sized to the room and the free GP. */
		MakeSell.Offer offer;
		/** More open swaps after this one. */
		boolean more;
		/** The swap taken, or null once it's left the feed, and the claim's size. */
		PlanFeed.Swap swap;
		long units;
		/** The step under way, as an index into the swap's steps, or -1. */
		int current;
		/** When the room opens again: "6:00 PM". */
		String opens;
		boolean busy;
		String error;
	}

	@Value
	static class UpNext
	{
		int itemId;
		String name;
		long expected;
	}

	interface Actions
	{
		void signIn();

		void setBudget(long gp);

		void skip(int itemId);

		/** "Already done" or "Keep my current price" on a step. */
		void dismiss(String stepKey);

		void addRebound();

		void dismissRebound();

		void retry();

		void openSettings();

		/** Make and Sell: take the offered swap, show the next one, give the taken one back, mark it done. */
		void takeSwap();

		void nextSwap();

		void giveBackSwap();

		void finishSwap();
	}

	private final Actions actions;
	private final IntFunction<AsyncBufferedImage> icons;
	private View shown;
	/** What was on screen last time, so only what's new deals in. */
	private String shownStep;
	private String shownRebound;
	/** Each slot's status last time, so a dot beats once when the game changes it. */
	private final java.util.Map<Integer, String> shownStatus = new java.util.HashMap<>();
	private Trader.Moment shownMoment;
	private final List<Integer> shownSlots = new ArrayList<>();
	/** Kept across rebuilds so the number rolls from what it showed. */
	private Ui.Count today;
	private boolean seenProfit;
	/** What the player has typed in a budget field, kept when the tab redraws around it. */
	private String typedBudget;
	/** The slot whose plan is open, or -1; one at a time, kept when the tab redraws. */
	private int openSlot = -1;
	/** The slot row that had keyboard focus, so it keeps it when the tab redraws. */
	private int focusedSlot = -1;

	TradePanel(Actions actions, IntFunction<AsyncBufferedImage> icons)
	{
		this.actions = actions;
		this.icons = icons;
		setLayout(new Ui.ColumnLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(Ui.GAP, Ui.GAP, Ui.GAP, Ui.GAP));
	}

	/** Call on the Swing thread. */
	void show(View view)
	{
		if (view.equals(shown))
		{
			return;
		}
		shown = view;
		removeAll();
		if (view.isOffline())
		{
			add(offline());
		}
		else if (view.getProblem() != null)
		{
			add(notice(view.getProblem()));
		}
		if (!view.isSignedIn())
		{
			signIn(view);
		}
		else if (view.isOtherWorld())
		{
			Ui.Card card = new Ui.Card(Brand.AMBER, Ui.AMBER_EDGE);
			card.add(Ui.eyebrow("Trader is paused"));
			card.add(Ui.title("This world has its own GE"));
			card.add(Ui.text("Trader works on normal worlds. Hop to one to keep trading. Your plan is safe.", Brand.MUTED));
			add(card);
		}
		else if (view.getStep() == null)
		{
			if (!view.isOffline())
			{
				add(loading());
			}
		}
		else if (view.getBudget() == null)
		{
			budget(view);
		}
		else
		{
			trade(view);
		}
		revalidate();
		repaint();
	}

	private void add(Ui.Card card)
	{
		if (getComponentCount() > 0)
		{
			super.add(Ui.gap(Ui.GAP));
		}
		super.add(card);
	}

	// --- States before trading ---

	private Ui.Card offline()
	{
		Ui.Card card = new Ui.Card(Brand.AMBER, Ui.AMBER_EDGE);
		card.add(Ui.eyebrow("Prices paused"));
		card.add(Ui.title("We can't reach Gielinor Gains"));
		card.add(Ui.text("Your plan is safe. We'll keep trying, and nothing changes in your game.", Brand.MUTED));
		card.add(Ui.gap(Ui.GAP));
		card.add(new Ui.Button("Try again", false, actions::retry));
		return card;
	}

	private Ui.Card notice(String text)
	{
		Ui.Card card = new Ui.Card(Brand.AMBER, Ui.AMBER_EDGE);
		card.add(Ui.text(text, Brand.TEXT));
		return card;
	}

	private Ui.Card loading()
	{
		Ui.Card card = new Ui.Card();
		card.add(Ui.status("Loading your plan", Brand.BLUE, true));
		return card;
	}

	private void signIn(View view)
	{
		Ui.Card card = new Ui.Card(Brand.BLUE, Ui.BLUE_EDGE);
		card.add(Ui.title("Trader is free with a Gielinor Gains account"));
		card.add(Ui.text("Trader picks your trades and follows your offers here, on the same plan as the website.",
			Brand.MUTED));
		card.add(Ui.gap(Ui.GAP));
		if (view.getCode() == null && (view.getProblem() != null || view.isOffline()))
		{
			// Nothing is on its way, so the next step is the player's.
			card.add(new Ui.Button("Sign in", true, actions::signIn));
		}
		else if (view.getCode() == null)
		{
			card.add(Ui.status("Getting a sign-in code", Brand.BLUE, true));
		}
		else
		{
			card.add(Ui.eyebrow("Your code"));
			card.add(Ui.gap(4));
			card.add(new Code(view.getCode()));
			card.add(Ui.gap(Ui.GAP));
			card.add(new Ui.Button("Sign in on the website", true, () -> LinkBrowser.browse(LINK_PAGE)));
			card.add(Ui.gap(6));
			card.add(Ui.status("Waiting for you to type the code", Brand.BLUE, true));
			card.add(Ui.gap(6));
			card.add(Ui.text("Type it on the website after you sign in. This tab updates by itself.", Brand.MUTED));
		}
		card.add(Ui.gap(6));
		card.add(Ui.text("The Market tab works without signing in.", Brand.MUTED));
		add(card);
	}

	private void budget(View view)
	{
		Ui.Card card = new Ui.Card(Brand.BLUE, Ui.BLUE_EDGE);
		card.add(Ui.eyebrow("Start here"));
		card.add(Ui.title("How much do you want to trade with?"));
		// Shown only before Trader sees any GP: once it does, it plans with that (up to 20M) on its own.
		card.add(Ui.text(view.getCoins() == 0
			? "We don't see any coins yet. Type an amount like 5m or 500k."
			: "Log in and Trader plans with your coins. Or type an amount like 5m.", Brand.MUTED));
		card.add(Ui.gap(Ui.GAP));
		card.add(budgetField(null, "Plan my trades"));
		card.add(Ui.gap(6));
		card.add(Ui.text("You can change this any time.", Brand.MUTED));
		add(card);

		Ui.Card how = new Ui.Card();
		how.add(Ui.eyebrow("How it works"));
		how.add(Ui.gap(4));
		how.add(Ui.text("1. Trader picks trades that fit your budget.", Brand.TEXT));
		how.add(Ui.text("2. You place each offer. We outline every step at the GE.", Brand.TEXT));
		how.add(Ui.text("3. We follow your offers and tell you when to collect and sell.", Brand.TEXT));
		add(how);
	}

	/** A field for the budget with its button; what's typed is checked in words. */
	JComponent budgetField(Long current, String label)
	{
		JPanel column = Ui.stack();
		JTextField field = Ui.field(typedBudget != null ? typedBudget : current == null ? "" : BoxText.whole(current),
			"Budget in GP");
		field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				typedBudget = field.getText();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				typedBudget = field.getText();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
			}
		});
		JLabel hint = Ui.label(" ", FontManager.getRunescapeSmallFont(), Brand.AMBER);
		Runnable apply = () ->
		{
			long gp = Ui.parseGp(field.getText());
			if (gp <= 0 && current != null && field.getText().trim().isEmpty())
			{
				// Clearing a typed budget hands planning back to the GP Trader sees.
				hint.setForeground(Brand.MADE_MONEY);
				hint.setText("Saved. Trader plans with your coins, up to " + Ui.gp(Trader.BEST_PLAN_GP) + ".");
				typedBudget = null;
				actions.setBudget(0);
			}
			else if (gp > 0)
			{
				hint.setForeground(Brand.MADE_MONEY);
				hint.setText("Saved. Trader plans with " + Ui.gp(gp) + ".");
				field.setText(BoxText.whole(gp));
				typedBudget = null;
				actions.setBudget(gp);
			}
			else
			{
				hint.setForeground(Brand.AMBER);
				hint.setText("Type an amount, like 5m or 500k.");
			}
		};
		field.addActionListener(e -> apply.run());
		column.add(field);
		column.add(hint);
		column.add(new Ui.Button(label, true, apply));
		return column;
	}

	// --- Trading ---

	private void trade(View view)
	{
		if (view.isJustLinked())
		{
			Ui.Card linked = new Ui.Card(Brand.MADE_MONEY, Ui.GREEN_EDGE).appear(0, true);
			linked.add(Ui.status("Signed in", Brand.MADE_MONEY, false));
			linked.add(Ui.text("Trader here uses the same plan as Trader on the website.", Brand.MUTED));
			add(linked);
		}
		Trader.Moment moment = view.getMoment();
		if (moment != null && moment.isSale())
		{
			boolean fresh = moment != shownMoment;
			add(finished(moment), fresh);
		}
		shownMoment = moment;
		Trader.Step step = view.getStep();
		boolean newStep = !Objects.equals(step.getKey(), shownStep);
		shownStep = step.getKey();
		add(next(step, moment).appear(0, false), newStep);
		if (view.getMakeSell() != null)
		{
			add(makeSell(view.getMakeSell()));
		}

		Trader.Rebound rebound = view.getRebound();
		if (rebound != null)
		{
			boolean fresh = !Objects.equals(rebound.key, shownRebound);
			shownRebound = rebound.key;
			add(rebound(rebound, view.isRoom(), view.getSwap()), fresh);
		}

		if (!view.getSlots().isEmpty())
		{
			add(slots(view));
		}
		if (!view.getUpNext().isEmpty())
		{
			add(upNext(view.getUpNext()));
		}
		add(today(view.getToday(), view.getBest()));

		Ui.Card budget = new Ui.Card();
		JPanel line = new JPanel(new java.awt.BorderLayout());
		line.setOpaque(false);
		line.setAlignmentX(LEFT_ALIGNMENT);
		line.add(Ui.label("Budget " + Ui.gp(view.getBudget()), FontManager.getRunescapeSmallFont(), Brand.MUTED),
			java.awt.BorderLayout.WEST);
		line.add(new Ui.Link("Change", Brand.BLUE, actions::openSettings), java.awt.BorderLayout.EAST);
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
		budget.add(line);
		add(budget);
	}

	/** Adds a card, dealing it in only when it's new. */
	private void add(Ui.Card card, boolean animate)
	{
		if (!animate)
		{
			card.appear(-10_000, false);
		}
		add(card);
	}

	/** A flip just finished: the profit, big, with the item. */
	private Ui.Card finished(Trader.Moment moment)
	{
		Ui.Card card = new Ui.Card(Brand.MADE_MONEY, Ui.GREEN_EDGE).appear(0, true);
		card.add(Ui.eyebrow(moment.isRebound() ? Rebounds.FINISHED : "Flip finished"));
		card.add(Ui.gap(4));
		ItemTitle title = new ItemTitle(moment.getItemId(), moment.profitText());
		title.title.setForeground(moment.getProfit() >= 0 ? Brand.MADE_MONEY : Brand.AMBER);
		card.add(title);
		card.add(Ui.text(moment.isKnown()
			? "Sold " + BoxText.whole(moment.getQty()) + " " + moment.getName() + " at " + BoxText.whole(moment.getPrice())
			+ " each, after tax. Collect your coins."
			: moment.getName() + " sold at " + BoxText.whole(moment.getPrice()) + " each. We didn't see what you paid,"
			+ " so there's no profit to show. Collect your coins.", Brand.MUTED));
		return card;
	}

	private Ui.Card next(Trader.Step step, Trader.Moment moment)
	{
		Trader.Kind kind = step.getKind();
		boolean priceStop = Trader.isPriceStop(step);
		boolean amber = kind == Trader.Kind.STALE || kind == Trader.Kind.LOWER || priceStop;
		Ui.Card card = amber ? new Ui.Card(Brand.AMBER, Ui.AMBER_EDGE) : new Ui.Card(Brand.BLUE, Ui.BLUE_EDGE);
		boolean justBought = moment != null && !moment.isSale() && moment.getItemId() == step.getItemId();
		if (justBought)
		{
			long seconds = moment.getTookMs() / 1000;
			card.add(Ui.status(moment.getTookMs() >= 0 && seconds < 120 ? "Bought in " + Math.max(1, seconds)
				+ (seconds == 1 ? " second" : " seconds") : "Your buy filled", Brand.MADE_MONEY, false));
		}
		else
		{
			card.add(Ui.eyebrow(kind == Trader.Kind.LOWER || priceStop ? "Price check" : "Next step"));
		}
		card.add(Ui.gap(4));
		card.add(step.getItemId() > 0 ? new ItemTitle(step.getItemId(), step.getTitle()) : Ui.title(step.getTitle()));
		if (step.getDetail() != null)
		{
			card.add(Ui.text(step.getDetail(), kind == Trader.Kind.LOWER || priceStop ? Brand.TEXT : Brand.MUTED));
		}
		if (step.getStatus() != null)
		{
			card.add(Ui.gap(6));
			card.add(Ui.status(step.getStatus(), TraderOverlay.color(step.getTone()), step.getTone() == Trader.Tone.BLUE));
		}
		String key = step.getKey();
		Ui.Link done = new Ui.Link("Already done", Brand.MUTED, () -> actions.dismiss(key));
		if (Trader.isSwap(step))
		{
			// A Make and Sell step isn't a pick, so it can't be skipped; the swap card gives it back.
			if (kind == Trader.Kind.WAIT)
			{
				return card;
			}
			card.add(Ui.gap(Ui.GAP));
			card.add(Ui.label("Game shows something else?", FontManager.getRunescapeSmallFont(), Brand.MUTED));
			card.add(Ui.links(done));
			return card;
		}
		switch (kind)
		{
			case BUY:
				card.add(Ui.gap(Ui.GAP));
				card.add(Ui.label("Game shows something else?", FontManager.getRunescapeSmallFont(), Brand.MUTED));
				card.add(Ui.links(done, new Ui.Link("Skip for now", Brand.MUTED, () -> actions.skip(step.getItemId()))));
				break;
			case COLLECT:
			case SELL:
				card.add(Ui.gap(Ui.GAP));
				card.add(Ui.label("Game shows something else?", FontManager.getRunescapeSmallFont(), Brand.MUTED));
				card.add(Ui.links(done));
				break;
			case LOWER:
				card.add(Ui.gap(Ui.GAP));
				card.add(Ui.links(done, new Ui.Link("Keep my current price", Brand.MUTED, () -> actions.dismiss(key))));
				break;
			case STOP:
				// A Rebound's day to buy is over either way; a price check is the player's call.
				if (priceStop)
				{
					card.add(Ui.gap(Ui.GAP));
					card.add(Ui.links(new Ui.Link("Keep buying", Brand.MUTED, () -> actions.dismiss(key))));
				}
				break;
			case EMPTY:
				// Nothing fits: the one thing that changes that is the budget.
				if (Trader.NO_PICK.equals(step.getDetail()))
				{
					card.add(Ui.gap(Ui.GAP));
					card.add(Ui.links(new Ui.Link("Change budget", Brand.BLUE, actions::openSettings)));
				}
				break;
			default:
				break;
		}
		return card;
	}

	private Ui.Card makeSell(MakeSellCard view)
	{
		Ui.Card card = new Ui.Card(Brand.MADE_MONEY, Ui.GREEN_EDGE);
		switch (view.getState())
		{
			case DONE:
				card.add(Ui.title(MakeSell.DONE_TITLE));
				card.add(Ui.text(MakeSell.opens(view.getOpens()), Brand.MUTED));
				card.add(Ui.gap(4));
				card.add(Ui.text(MakeSell.DONE_MORE, Brand.MUTED));
				card.add(Ui.gap(6));
				card.add(new Ui.Link("See all swaps", Brand.BLUE, () -> LinkBrowser.browse(MakeSell.HREF)));
				return card;
			case FULL:
				card.add(Ui.title(MakeSell.FULL_TITLE));
				card.add(Ui.text(MakeSell.SUMMARY, Brand.MUTED));
				card.add(Ui.gap(4));
				card.add(Ui.text(MakeSell.empty(view.getOpens()), Brand.MUTED));
				return card;
			case TAKEN:
				return taken(card, view);
			default:
				return offer(card, view);
		}
	}

	private Ui.Card offer(Ui.Card card, MakeSellCard view)
	{
		MakeSell.Offer offer = view.getOffer();
		PlanFeed.Swap swap = offer.getSwap();
		card.add(Ui.eyebrow(MakeSell.HEADING + (swap.where == null ? "" : " · " + swap.where)));
		card.add(Ui.gap(4));
		card.add(swapTitle(swap));
		card.add(Ui.text(MakeSell.SUMMARY, Brand.MUTED));
		card.add(Ui.gap(4));
		card.add(Ui.text(MakeSell.EXPLAINER, Brand.MUTED));
		card.add(Ui.gap(6));
		swapSteps(card, swap, offer.getUnits(), -1);
		card.add(Ui.gap(6));
		card.add(Ui.text(BoxText.whole(offer.getProfit()) + " GP " + MakeSell.PLANNED.toLowerCase(java.util.Locale.ROOT)
			+ " · costs " + BoxText.whole(offer.getCost()) + " GP · " + MakeSell.minutes(offer.getPlaySeconds())
			+ " of play", Brand.MADE_MONEY));
		if (swap.needs != null && !swap.needs.isEmpty())
		{
			card.add(Ui.gap(4));
			card.add(Ui.text("You need: " + String.join(", ", swap.needs) + (swap.members ? " (members)" : "") + ".",
				Brand.MUTED));
			if (swap.wikiUrl != null)
			{
				card.add(new Ui.Link("How on the Wiki", Brand.BLUE, () -> LinkBrowser.browse(swap.wikiUrl)));
			}
		}
		error(card, view.getError());
		card.add(Ui.gap(Ui.GAP));
		Ui.Button start = new Ui.Button("Start this swap", true, actions::takeSwap);
		start.setEnabled(!view.isBusy());
		card.add(start);
		card.add(Ui.gap(6));
		Ui.Link all = new Ui.Link("See all swaps", Brand.BLUE, () -> LinkBrowser.browse(MakeSell.HREF));
		card.add(view.isMore() ? Ui.links(new Ui.Link("Show another", Brand.BLUE, actions::nextSwap), all) : Ui.links(all));
		return card;
	}

	private Ui.Card taken(Ui.Card card, MakeSellCard view)
	{
		PlanFeed.Swap swap = view.getSwap();
		card.add(Ui.eyebrow("Your swap"));
		card.add(Ui.gap(4));
		if (swap != null)
		{
			card.add(swapTitle(swap));
			card.add(Ui.text("Room kept for you until " + view.getOpens() + ".", Brand.MUTED));
			card.add(Ui.gap(6));
			swapSteps(card, swap, view.getUnits(), view.getCurrent());
			card.add(Ui.gap(6));
			card.add(Ui.text(BoxText.whole(Math.round(swap.each * view.getUnits())) + " GP "
				+ MakeSell.PLANNED.toLowerCase(java.util.Locale.ROOT), Brand.MADE_MONEY));
		}
		else
		{
			card.add(Ui.title("Your Make and Sell swap"));
			card.add(Ui.text("Room kept for you until " + view.getOpens() + ".", Brand.MUTED));
			card.add(Ui.gap(4));
			card.add(Ui.text(MakeSell.GONE, Brand.MUTED));
		}
		error(card, view.getError());
		card.add(Ui.gap(Ui.GAP));
		Ui.Button finish = new Ui.Button("I'm done", true, actions::finishSwap);
		finish.setEnabled(!view.isBusy());
		card.add(finish);
		card.add(Ui.gap(6));
		// While a request is out, the give-back waits for it rather than sending a second.
		card.add(Ui.links(new Ui.Link("I'm not doing it", Brand.MUTED, () ->
		{
			if (!view.isBusy())
			{
				actions.giveBackSwap();
			}
		})));
		return card;
	}

	/** The swap's name with the item it changes. */
	private ItemTitle swapTitle(PlanFeed.Swap swap)
	{
		PlanFeed.Leg art = MakeSell.art(swap);
		return new ItemTitle(art == null ? -1 : art.id, swap.title);
	}

	/** The swap's steps, numbered; the one under way stands out. */
	private static void swapSteps(Ui.Card card, PlanFeed.Swap swap, long units, int current)
	{
		List<String> steps = MakeSell.steps(swap, units);
		for (int i = 0; i < steps.size(); i++)
		{
			boolean now = i == current;
			boolean done = current >= 0 && i < current;
			card.add(Ui.text((i + 1) + ". " + steps.get(i) + (done ? " (done)" : ""), now || current < 0 ? Brand.TEXT
				: Brand.MUTED));
		}
	}

	private static void error(Ui.Card card, String error)
	{
		if (error != null)
		{
			card.add(Ui.gap(6));
			card.add(Ui.text(error, Brand.AMBER));
		}
	}

	private Ui.Card rebound(Trader.Rebound rebound, boolean free, String swap)
	{
		Ui.Card card = new Ui.Card(Brand.MADE_MONEY, Ui.GREEN_EDGE).appear(0, true);
		String age = rebound.detectedAt == null ? "" : " · " + ago(System.currentTimeMillis() - rebound.detectedAt);
		card.add(Ui.eyebrow("New Rebound" + age));
		card.add(Ui.gap(4));
		card.add(new ItemTitle(rebound.id, rebound.name));
		card.add(Ui.text("Dropped " + Math.round(rebound.drop * 100) + "%. Buy " + BoxText.whole(rebound.qty) + " at " + BoxText.whole(rebound.buy)
			+ ". Leave the offer up for a day, then sell at " + BoxText.whole(rebound.sell) + " within 2 days.", Brand.MUTED));
		card.add(Ui.gap(Ui.GAP));
		boolean room = free || swap != null;
		if (swap != null)
		{
			// Every slot is taken: it replaces an unstarted pick, and says which before the click.
			card.add(Ui.text(Rebounds.swap(swap), Brand.MUTED));
			card.add(Ui.gap(6));
		}
		else if (!room)
		{
			// A button that can't work isn't shown; the card says what will make room instead.
			card.add(Ui.status(Rebounds.NO_ROOM, Brand.AMBER, false));
			card.add(Ui.gap(6));
		}
		JPanel buttons = new JPanel(new java.awt.GridLayout(1, room ? 2 : 1, 6, 0));
		buttons.setOpaque(false);
		buttons.setAlignmentX(LEFT_ALIGNMENT);
		if (room)
		{
			buttons.add(new Ui.Button("Add to plan", true, actions::addRebound));
		}
		buttons.add(new Ui.Button("Not now", false, actions::dismissRebound));
		buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		card.add(buttons);
		card.add(Ui.gap(6));
		card.add(new Ui.Link("See how every Rebound turned out", Brand.BLUE, () -> LinkBrowser.browse(REBOUND_RECORD)));
		return card;
	}

	private static String ago(long ms)
	{
		long minutes = Math.max(1, ms / 60_000);
		return minutes < 60 ? minutes + " min ago" : (minutes / 60) + "h ago";
	}

	private Ui.Card slots(View view)
	{
		Ui.Card card = new Ui.Card();
		JPanel head = new JPanel(new java.awt.BorderLayout());
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.add(Ui.label("Your slots · " + Ui.gp(view.getInvested()), FontManager.getRunescapeBoldFont(),
			Brand.TEXT), java.awt.BorderLayout.WEST);
		head.add(Ui.label("Expected", FontManager.getRunescapeSmallFont(), Brand.MUTED), java.awt.BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
		card.add(head);
		if (view.isFreeWorld())
		{
			card.add(Ui.label(Math.min(3, view.getSlots().size()) + " of 3 · free world", FontManager.getRunescapeSmallFont(),
				Brand.MUTED));
		}
		card.add(Ui.gap(4));
		List<Integer> ids = new ArrayList<>();
		int delay = 0;
		for (Trader.SlotRow row : view.getSlots())
		{
			ids.add(row.getItemId());
			boolean fresh = !shownSlots.contains(row.getItemId());
			String before = shownStatus.put(row.getItemId(), row.getStatus());
			SlotRowView rowView = new SlotRowView(row, fresh ? delay : -1);
			if (!fresh && before != null && !before.equals(row.getStatus()))
			{
				rowView.beat();
			}
			card.add(rowView);
			if (row.getItemId() == openSlot)
			{
				card.add(planOf(row, opening));
				card.add(Ui.gap(6));
			}
			if (row.getItemId() == focusedSlot)
			{
				javax.swing.SwingUtilities.invokeLater(rowView::requestFocusInWindow);
			}
			if (fresh)
			{
				delay += 50;
			}
		}
		shownSlots.clear();
		shownSlots.addAll(ids);
		if (!ids.contains(openSlot))
		{
			openSlot = -1;
		}
		opening = false;
		return card;
	}

	/** Whether the open plan was just opened, so it fades in once rather than on every redraw. */
	private boolean opening;

	/** Opens a slot's plan, or closes it if it's open; opening one closes the other. */
	void toggle(int itemId)
	{
		openSlot = openSlot == itemId ? -1 : itemId;
		opening = openSlot >= 0;
		focusedSlot = itemId;
		View view = shown;
		shown = null;
		show(view);
	}

	/** A slot's plan under its row: label and value lines, then what happens next. */
	private Ui.Card planOf(Trader.SlotRow row, boolean fresh)
	{
		Ui.Card well = new Ui.Card(null, Brand.BORDER)
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				Ui.smooth(g2);
				g2.setColor(Ui.FILL);
				g2.fillRoundRect(0, 0, getWidth(), getHeight(), Brand.RADIUS, Brand.RADIUS);
				g2.dispose();
			}
		};
		well.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
		well.getAccessibleContext().setAccessibleName(row.getName() + " plan");
		for (Trader.Fact fact : row.getPlan())
		{
			JPanel line = new JPanel(new java.awt.BorderLayout(6, 0));
			line.setOpaque(false);
			line.setAlignmentX(LEFT_ALIGNMENT);
			line.add(Ui.label(fact.getLabel(), FontManager.getRunescapeSmallFont(), Brand.MUTED), java.awt.BorderLayout.WEST);
			line.add(Ui.label(fact.getValue(), FontManager.getRunescapeSmallFont(), Brand.TEXT), java.awt.BorderLayout.EAST);
			line.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
			line.setMaximumSize(new Dimension(Integer.MAX_VALUE, line.getPreferredSize().height));
			line.getAccessibleContext().setAccessibleName(fact.getLabel() + ": " + fact.getValue());
			well.add(line);
		}
		if (row.getNote() != null)
		{
			well.add(Ui.gap(4));
			well.add(Ui.text(row.getNote(), Brand.MUTED));
		}
		return fresh ? well.appear(0, false) : well;
	}

	private Ui.Card upNext(List<UpNext> picks)
	{
		Ui.Card card = new Ui.Card();
		JPanel head = new JPanel(new java.awt.BorderLayout());
		head.setOpaque(false);
		head.add(Ui.label("Up next", FontManager.getRunescapeBoldFont(), Brand.TEXT), java.awt.BorderLayout.WEST);
		head.add(Ui.label("When a slot frees up", FontManager.getRunescapeSmallFont(), Brand.MUTED), java.awt.BorderLayout.EAST);
		card.add(head);
		card.add(Ui.gap(4));
		for (UpNext pick : picks)
		{
			JPanel row = new JPanel(new java.awt.BorderLayout(6, 0));
			row.setOpaque(false);
			row.add(new Icon(pick.getItemId(), 22), java.awt.BorderLayout.WEST);
			JPanel words = new JPanel(new java.awt.GridLayout(2, 1));
			words.setOpaque(false);
			words.add(Ui.label(pick.getName(), FontManager.getRunescapeBoldFont(), Brand.TEXT));
			words.add(Ui.label("Expected +" + Ui.gp(pick.getExpected()), FontManager.getRunescapeSmallFont(), Brand.MADE_MONEY));
			row.add(words, java.awt.BorderLayout.CENTER);
			row.add(new Ui.Link("Skip", Brand.MUTED, () -> actions.skip(pick.getItemId())), java.awt.BorderLayout.EAST);
			row.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 0));
			card.add(row);
		}
		return card;
	}

	private Ui.Card today(Trader.Today value, Trader.Moment best)
	{
		Ui.Card card = new Ui.Card();
		card.add(Ui.eyebrow("Today so far"));
		long profit = value == null ? 0 : value.profit;
		int flips = value == null ? 0 : value.flips;
		if (today == null)
		{
			today = new Ui.Count(profit);
			seenProfit = flips > 0;
		}
		else
		{
			// The first finished flip of the session gets a glint.
			today.set(profit, flips > 0 && !seenProfit);
			seenProfit |= flips > 0;
		}
		card.add(today);
		long selling = value == null ? 0 : value.selling;
		card.add(Ui.text(flips == 0 && selling == 0 ? "No finished flips yet today."
			: flips == 0 ? "From items still selling, after tax" : BoxText.soFarFrom(flips, selling) + ", after tax", Brand.MUTED));
		if (best != null && best.getProfit() > 0)
		{
			card.add(Ui.gap(4));
			card.add(Ui.status("Best this session: +" + Ui.gp(best.getProfit()) + " on " + best.getName(), Brand.MADE_MONEY,
				false));
		}
		return card;
	}

	// --- Pieces ---

	/** The item's icon beside a bold title, like "Buy Cannonball". */
	private final class ItemTitle extends JPanel implements Ui.WidthAware
	{
		private static final int ICON = 28;
		final javax.swing.JTextArea title;

		ItemTitle(int itemId, String text)
		{
			super(null);
			setOpaque(false);
			title = Ui.title(text);
			add(new Icon(itemId, ICON));
			add(title);
		}

		@Override
		public int heightFor(int width)
		{
			return Math.max(ICON, Ui.ColumnLayout.heightFor(title, width - ICON - 8));
		}

		@Override
		public void doLayout()
		{
			int h = getHeight();
			getComponent(0).setBounds(0, (h - ICON) / 2, ICON, ICON);
			int th = Ui.ColumnLayout.heightFor(title, getWidth() - ICON - 8);
			title.setBounds(ICON + 8, (h - th) / 2, getWidth() - ICON - 8, th);
		}
	}

	/** An item's icon, drawn once it loads. */
	private final class Icon extends JComponent
	{
		private final AsyncBufferedImage image;
		private final int size;

		Icon(int itemId, int size)
		{
			this.size = size;
			this.image = icons.apply(itemId);
			if (image != null)
			{
				image.onLoaded(this::repaint);
			}
			Dimension d = new Dimension(size, size);
			setPreferredSize(d);
			setMinimumSize(d);
			setMaximumSize(d);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			if (image == null)
			{
				return;
			}
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			// Item art is 36 by 32; it keeps its shape and sits in the middle.
			int w = size;
			int h = size * 32 / 36;
			g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
			g2.dispose();
		}
	}

	/** One row of Your slots: icon, name, a coloured dot with its status, and the expected profit. */
	private final class SlotRowView extends JPanel
	{
		private static final int HEIGHT = 36;
		private static final int ICON = 22;
		private final Trader.SlotRow row;
		private final AsyncBufferedImage image;
		private long appearAt;
		private long beatAt;
		private boolean hover;

		SlotRowView(Trader.SlotRow row, int delayMs)
		{
			this.row = row;
			setOpaque(false);
			this.image = icons.apply(row.getItemId());
			if (image != null)
			{
				image.onLoaded(this::repaint);
			}
			setAlignmentX(LEFT_ALIGNMENT);
			boolean open = row.getItemId() == openSlot;
			getAccessibleContext().setAccessibleName(row.getName() + ". " + row.getStatus() + ". Expected "
				+ Ui.gp(row.getExpected()) + " GP. " + (open ? "Plan shown. Press Enter to hide it." : "Press Enter to see the plan."));
			setToolTipText(open ? "Hide the plan" : "See the plan");
			setFocusable(true);
			setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
			addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mousePressed(java.awt.event.MouseEvent e)
				{
					toggle(row.getItemId());
				}

				@Override
				public void mouseEntered(java.awt.event.MouseEvent e)
				{
					hover = true;
					repaint();
				}

				@Override
				public void mouseExited(java.awt.event.MouseEvent e)
				{
					hover = false;
					repaint();
				}
			});
			addKeyListener(new java.awt.event.KeyAdapter()
			{
				@Override
				public void keyPressed(java.awt.event.KeyEvent e)
				{
					if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ENTER || e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE)
					{
						e.consume();
						toggle(row.getItemId());
					}
				}
			});
			addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusGained(java.awt.event.FocusEvent e)
				{
					focusedSlot = row.getItemId();
					repaint();
				}

				@Override
				public void focusLost(java.awt.event.FocusEvent e)
				{
					if (focusedSlot == row.getItemId() && !e.isTemporary())
					{
						focusedSlot = -1;
					}
					repaint();
				}
			});
			appearAt = delayMs < 0 || Ui.reduceMotion ? -1 : System.currentTimeMillis() + delayMs;
			if (appearAt >= 0)
			{
				Ui.Clock.start(this);
			}
		}

		/** The status dot pulses once: the game changed this slot. */
		void beat()
		{
			if (!Ui.reduceMotion)
			{
				beatAt = System.currentTimeMillis();
				Ui.Clock.start(this);
			}
		}

		@Override
		public void addNotify()
		{
			super.addNotify();
			if (appearAt >= 0 || beatAt > 0)
			{
				Ui.Clock.start(this);
			}
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, HEIGHT);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, HEIGHT);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			Ui.smooth(g2);
			if (appearAt >= 0)
			{
				double t = (System.currentTimeMillis() - appearAt) / 220.0;
				if (t >= 1)
				{
					appearAt = -1;
					if (beatAt == 0)
					{
						Ui.Clock.stop(this);
					}
				}
				else
				{
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Ui.easeOut(t)));
					g2.translate(0, (int) Math.round(5 * (1 - Ui.easeOut(t))));
				}
			}
			if (hover || isFocusOwner())
			{
				g2.setColor(Ui.FILL);
				g2.fillRoundRect(-4, 0, getWidth() + 4, HEIGHT, Brand.RADIUS, Brand.RADIUS);
			}
			if (isFocusOwner())
			{
				g2.setColor(Brand.BLUE);
				g2.drawRoundRect(-4, 0, getWidth() + 3, HEIGHT - 1, Brand.RADIUS, Brand.RADIUS);
			}
			int y = (HEIGHT - ICON) / 2;
			if (image != null)
			{
				g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g2.drawImage(image, 0, y + 1, ICON, ICON * 32 / 36, null);
			}
			int x = ICON + 6;
			g2.setFont(FontManager.getRunescapeSmallFont());
			FontMetrics small = g2.getFontMetrics();
			String expected = (row.getExpected() >= 0 ? "+" : "") + Ui.gp(row.getExpected());
			int expectedWidth = small.stringWidth(expected);
			g2.setColor(row.getExpected() > 0 ? Brand.MADE_MONEY : Brand.MUTED);
			g2.drawString(expected, getWidth() - expectedWidth, 15);
			// A chevron under the profit: down when the plan is shown, right when it isn't.
			boolean open = row.getItemId() == openSlot;
			int cx = getWidth() - 5;
			int cy = 26;
			g2.setColor(hover || isFocusOwner() ? Brand.TEXT : Brand.MUTED);
			g2.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
			if (open)
			{
				g2.drawPolyline(new int[]{cx - 4, cx, cx + 4}, new int[]{cy - 2, cy + 2, cy - 2}, 3);
			}
			else
			{
				g2.drawPolyline(new int[]{cx - 2, cx + 2, cx - 2}, new int[]{cy - 4, cy, cy + 4}, 3);
			}
			g2.setFont(FontManager.getRunescapeBoldFont());
			g2.setColor(Brand.TEXT);
			g2.drawString(fit(g2.getFontMetrics(), row.getName(), getWidth() - x - expectedWidth - 6), x, 15);
			Color dot = TraderOverlay.color(row.getTone());
			if (beatAt > 0)
			{
				double t = (System.currentTimeMillis() - beatAt) / 700.0;
				if (t < 1)
				{
					int r = (int) Math.round(3 + 6 * t);
					g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), (int) (140 * (1 - t))));
					g2.fillOval(x + 3 - r, 25 - r, r * 2, r * 2);
				}
				else
				{
					beatAt = 0;
					if (appearAt < 0)
					{
						Ui.Clock.stop(this);
					}
				}
			}
			g2.setColor(dot);
			g2.fillOval(x, 22, 6, 6);
			g2.setFont(FontManager.getRunescapeSmallFont());
			g2.setColor(Brand.MUTED);
			g2.drawString(fit(small, row.getStatus(), getWidth() - x - 26), x + 10, 29);
			g2.dispose();
		}
	}

	private static String fit(FontMetrics fm, String text, int width)
	{
		if (fm.stringWidth(text) <= width)
		{
			return text;
		}
		String cut = text;
		while (cut.length() > 1 && fm.stringWidth(cut + "...") > width)
		{
			cut = cut.substring(0, cut.length() - 1);
		}
		return cut + "...";
	}

	/** The sign-in code in a box, letters spaced so it's easy to check against the website. */
	private static final class Code extends JPanel
	{
		private final String code;

		Code(String code)
		{
			this.code = code;
			setOpaque(false);
			setAlignmentX(Component.LEFT_ALIGNMENT);
			getAccessibleContext().setAccessibleName("Your code: " + code);
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(100, 40);
		}

		@Override
		public Dimension getMaximumSize()
		{
			return new Dimension(Integer.MAX_VALUE, 40);
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			Ui.smooth(g2);
			g2.setColor(Ui.GROUND);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), Brand.RADIUS, Brand.RADIUS);
			g2.setColor(Brand.BORDER);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Brand.RADIUS, Brand.RADIUS);
			g2.setFont(FontManager.getRunescapeBoldFont().deriveFont(22f));
			FontMetrics fm = g2.getFontMetrics();
			int spacing = 4;
			int width = 0;
			for (char c : code.toCharArray())
			{
				width += fm.charWidth(c) + spacing;
			}
			int x = (getWidth() - width + spacing) / 2;
			int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
			g2.setColor(Brand.TEXT);
			for (char c : code.toCharArray())
			{
				g2.drawString(String.valueOf(c), x, y);
				x += fm.charWidth(c) + spacing;
			}
			g2.dispose();
		}
	}
}
