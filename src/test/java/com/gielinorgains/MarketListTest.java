package com.gielinorgains;

import static com.gielinorgains.Fixtures.MINUTE;
import static com.gielinorgains.Fixtures.SIX_MINUTES_IN;
import static com.gielinorgains.Fixtures.SOURCE_AT;
import static com.gielinorgains.Fixtures.STALE_AT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import org.junit.Test;

public class MarketListTest
{
	/** What the game would say about the fixture's items. */
	static final Map<Integer, MarketList.Item> GAME = new HashMap<>();

	static
	{
		GAME.put(2, new MarketList.Item("Cannonball", true));
		GAME.put(561, new MarketList.Item("Nature rune", false));
		GAME.put(995, new MarketList.Item("Coins", false));
		GAME.put(4151, new MarketList.Item("Abyssal whip", true));
		GAME.put(11840, new MarketList.Item("Dragon boots", true));
		GAME.put(20997, new MarketList.Item("Twisted bow", true));
	}

	static MarketList.View view(ItemCheck.Snapshot snapshot, boolean failed, long now, boolean members)
	{
		return MarketList.build(snapshot, failed, now, members, GAME::get);
	}

	private static List<String> status(MarketList.View view)
	{
		return view.getStatus().stream().map(BoxText.Line::getLeft).collect(Collectors.toList());
	}

	@Test
	public void listsEveryFlipHighestExpectedProfitFirst() throws Exception
	{
		MarketList.View view = view(Fixtures.snapshot(), false, SIX_MINUTES_IN, true);
		assertEquals(Arrays.asList("Prices from 6 minutes ago"), status(view));
		assertNull(view.getNote());
		assertNull(view.getEmpty());
		assertEquals(Arrays.asList(2, 561), view.getRows().stream().map(MarketList.Row::getId).collect(Collectors.toList()));

		MarketList.Row top = view.getRows().get(0);
		assertEquals("Cannonball", top.getName());
		assertEquals("259", top.getBuy());
		assertEquals("269", top.getSell());
		assertEquals("11,000", top.getQuantity());
		assertEquals("21K", top.getProfit());
		assertEquals("69%", top.getChance());
		assertEquals("Last 10 tests: 8 made money", top.getRecord());
		assertEquals("as the site writes totals", "+1.1% after tax over 6 tests", view.getRows().get(1).getRecord());

		String tip = String.join(" ", top.getTooltip());
		assertTrue(tip.startsWith(BoxText.CHANCE_TIP));
		assertTrue(tip.contains("Expected profit: about what you'd make after tax at this quantity."));
		assertTrue(tip.endsWith("Estimates, not promises. Click to pick this flip. Use Item page to see its page."));
	}

	@Test
	public void freeWorldsHideMembersItemsAndSaySo() throws Exception
	{
		MarketList.View view = view(Fixtures.snapshot(), false, SIX_MINUTES_IN, false);
		assertEquals(MarketList.MEMBERS_HIDDEN, view.getNote());
		assertEquals(1, view.getRows().size());
		assertEquals("Nature rune", view.getRows().get(0).getName());

		Map<Integer, MarketList.Item> allMembers = new HashMap<>(GAME);
		allMembers.put(561, new MarketList.Item("Nature rune", true));
		MarketList.View none = MarketList.build(Fixtures.snapshot(), false, SIX_MINUTES_IN, false, allMembers::get);
		assertTrue(none.getRows().isEmpty());
		assertEquals(MarketList.NO_FREE_FLIPS, none.getEmpty());
	}

	@Test
	public void noFlipsSaysSo() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.items.values().forEach(entry -> entry.flip = null);
		MarketList.View view = view(ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN), false,
			SIX_MINUTES_IN, true);
		assertTrue(view.getRows().isEmpty());
		assertEquals(MarketList.NO_FLIPS, view.getEmpty());
		assertNull(view.getNote());
	}

	@Test
	public void itemsTheGameDoesNotKnowAreLeftOut() throws Exception
	{
		MarketList.View view = MarketList.build(Fixtures.snapshot(), false, SIX_MINUTES_IN, true,
			id -> id == 2 ? null : GAME.get(id));
		assertEquals(1, view.getRows().size());
		assertEquals(561, view.getRows().get(0).getId());
	}

	@Test
	public void unreadableRecordsMeanNoRecordLine() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.recordsComplete = false;
		response.items.get("561").record = null;
		MarketList.View view = view(ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN), false,
			SIX_MINUTES_IN, true);
		assertEquals("Last 10 tests: 8 made money", view.getRows().get(0).getRecord());
		assertNull(view.getRows().get(1).getRecord());
	}

	@Test
	public void saysWhatTheBoxSaysWhenThereAreNoPrices() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		assertEquals(Arrays.asList("Getting prices..."), status(view(null, false, 0, true)));
		assertEquals(Arrays.asList("Can't reach Gielinor Gains.", "We'll try again soon."),
			status(view(null, true, 0, true)));
		assertEquals(Arrays.asList("Our prices are 22 minutes old.", "We'll show them once they update."),
			status(view(snapshot, false, SOURCE_AT + 22 * MINUTE, true)));
		assertEquals(Arrays.asList("Can't reach Gielinor Gains.", "We'll try again soon."),
			status(view(snapshot, true, STALE_AT, true)));
		ItemCheck.Response outdated = Fixtures.itemCheck();
		outdated.minPlugin = ItemCheck.PLUGIN_VERSION + 1;
		assertEquals(Arrays.asList("This version is out of date.", "Restart RuneLite to update it."),
			status(view(ItemCheck.Snapshot.of(outdated, 0, 0), false, 0, true)));
		for (MarketList.View view : Arrays.asList(view(null, false, 0, true), view(snapshot, true, STALE_AT, true)))
		{
			assertTrue("no rows without fresh prices", view.getRows().isEmpty());
			assertNull(view.getEmpty());
		}
	}

	@Test
	public void theSelectedItemStaysSelectedWhenTheOrderChanges() throws Exception
	{
		MarketPanel panel = new MarketPanel(id -> null, id -> { });
		panel.setView(view(Fixtures.snapshot(), false, SIX_MINUTES_IN, true));
		panel.list.setSelectedIndex(1);
		assertEquals(561, panel.list.getSelectedValue().getId());

		// A new download puts the selected item first: Enter must still open it, not what took its place.
		ItemCheck.Response response = Fixtures.itemCheck();
		response.items.get("561").flip.expectedProfit = 99_000.0;
		panel.setView(view(ItemCheck.Snapshot.of(response, SIX_MINUTES_IN, SIX_MINUTES_IN), false, SIX_MINUTES_IN, true));
		assertEquals(0, panel.list.getSelectedIndex());
		assertEquals(561, panel.list.getSelectedValue().getId());

		// Gone from the list: nothing is selected.
		panel.setView(MarketList.build(Fixtures.snapshot(), false, SIX_MINUTES_IN, true, id -> id == 561 ? null : GAME.get(id)));
		assertEquals(-1, panel.list.getSelectedIndex());
	}

	@Test
	public void tooltipsWrapLikeTheBox() throws Exception
	{
		for (String line : view(Fixtures.snapshot(), false, SIX_MINUTES_IN, true).getRows().get(0).getTooltip())
		{
			assertTrue(line, line.length() <= 60);
		}
	}

	@Test
	public void theNextFlipIsTheBestOneNotAlreadyInASlot() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		Set<Integer> none = new HashSet<>();
		assertEquals("highest expected profit", 2, MarketList.next(snapshot, true, none, GAME::get));
		assertEquals("not the one just sold", 561, MarketList.next(snapshot, true, new HashSet<>(Arrays.asList(2)), GAME::get));
		assertEquals("no members' items on a free world", 561, MarketList.next(snapshot, false, none, GAME::get));
		assertEquals(-1, MarketList.next(snapshot, true, new HashSet<>(Arrays.asList(2, 561)), GAME::get));
	}

	@Test
	public void refreshingChangesRowsInPlace() throws Exception
	{
		MarketPanel.RowModel model = new MarketPanel.RowModel();
		List<String> events = new ArrayList<>();
		model.addListDataListener(new ListDataListener()
		{
			@Override
			public void intervalAdded(ListDataEvent e)
			{
				events.add("added " + e.getIndex0() + "-" + e.getIndex1());
			}

			@Override
			public void intervalRemoved(ListDataEvent e)
			{
				events.add("removed " + e.getIndex0() + "-" + e.getIndex1());
			}

			@Override
			public void contentsChanged(ListDataEvent e)
			{
				events.add("changed " + e.getIndex0() + "-" + e.getIndex1());
			}
		});
		List<MarketList.Row> rows = view(Fixtures.snapshot(), false, SIX_MINUTES_IN, true).getRows();
		model.setRows(rows);
		model.setRows(rows);
		model.setRows(rows.subList(0, 1));
		assertEquals(Arrays.asList("added 0-1", "changed 0-1", "removed 1-1", "changed 0-0"), events);
	}
}
