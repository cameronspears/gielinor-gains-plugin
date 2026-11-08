package com.gielinorgains;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import org.junit.Test;

public class ItemCheckTest
{
	@Test
	public void readsTheSitesFixture() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.snapshot();
		assertNotNull(snapshot);
		assertTrue(snapshot.fresh);
		assertTrue(snapshot.recordsComplete);
		assertEquals(1, snapshot.minPlugin);
		assertEquals(Long.valueOf(Fixtures.SOURCE_AT), snapshot.sourceAt);
		assertEquals(Long.valueOf(Fixtures.STALE_AT), snapshot.staleAt);
		// Five items with flips, reasons or records, and one with only a price jump.
		assertEquals(7, snapshot.items.size());
		assertEquals(1.27, snapshot.items.get(11832).pump.rise, 0);
		assertNull(snapshot.items.get(2).pump);

		ItemCheck.Flip flip = snapshot.items.get(2).flip;
		assertEquals(259, flip.buy, 0);
		assertEquals(269, flip.sell, 0);
		assertEquals(11000, flip.qty, 0);
		assertEquals(21000, flip.expectedProfit, 0);
		assertEquals(55000, flip.profitIfAllSells, 0);
		assertEquals(0.69, flip.chance, 0);
		ItemCheck.Record record = snapshot.items.get(2).record;
		assertEquals(0.03, record.avgReturn, 0);
		assertEquals(12, (int) record.flips);
		assertEquals("1101101111", record.checks);

		assertEquals("no stored history", "", snapshot.items.get(561).record.checks);
		assertEquals("no_profit_after_tax", snapshot.items.get(4151).skip);
		assertNull(snapshot.items.get(11840).flip);
		assertNull(snapshot.items.get(11840).skip);
	}

	@Test
	public void recordsCompleteIsTrueUnlessTheSiteSaysOtherwise() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.recordsComplete = null;
		assertTrue(ItemCheck.Snapshot.of(response, 0, 0).recordsComplete);
		response.recordsComplete = false;
		assertFalse(ItemCheck.Snapshot.of(response, 0, 0).recordsComplete);
	}

	@Test
	public void ignoresFieldsItDoesNotKnow() throws Exception
	{
		assertNotNull(Fixtures.edgeSnapshot().items.get(2).flip);
	}

	@Test
	public void dropsFlipsWithMissingOrImpossibleNumbers() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.edgeSnapshot();
		assertNull("no chance", snapshot.items.get(13).flip);
		assertNull("chance above 1", snapshot.items.get(14).flip);
		assertTrue(snapshot.items.containsKey(13));
	}

	@Test
	public void keepsTheLastTenChecksAndRefusesGarbage() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.edgeSnapshot();
		assertEquals("1110000011", snapshot.items.get(12).record.checks);
		assertEquals("", snapshot.items.get(15).record.checks);
		assertEquals("", snapshot.items.get(2).record.checks);
	}

	@Test
	public void skipsKeysThatAreNotItemIds() throws Exception
	{
		ItemCheck.Snapshot snapshot = Fixtures.edgeSnapshot();
		assertEquals(11, snapshot.items.size());
		assertFalse(snapshot.items.containsKey(-3));
	}

	@Test
	public void staleListsKeepRecordsButNotFlipsOrReasons() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.fresh = false;
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(response, 0, 0);
		assertFalse(snapshot.fresh);
		assertNull(snapshot.items.get(2).flip);
		assertNull(snapshot.items.get(4151).skip);
		assertEquals(12, (int) snapshot.items.get(2).record.flips);
	}

	@Test
	public void refusesOtherVersionsAndHugeLists() throws Exception
	{
		assertNull(ItemCheck.Snapshot.of(null, 0, 0));

		ItemCheck.Response response = Fixtures.itemCheck();
		response.v = 2;
		assertNull(ItemCheck.Snapshot.of(response, 0, 0));
		response.v = null;
		assertNull(ItemCheck.Snapshot.of(response, 0, 0));

		response.v = 1;
		response.items = new HashMap<>();
		for (int id = 1; id <= ItemCheck.MAX_ITEMS + 1; id++)
		{
			response.items.put(Integer.toString(id), new ItemCheck.Entry());
		}
		assertNull(ItemCheck.Snapshot.of(response, 0, 0));
	}

	@Test
	public void anEmptyListIsStillAList() throws Exception
	{
		ItemCheck.Response response = Fixtures.itemCheck();
		response.items = null;
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(response, 0, 0);
		assertNotNull(snapshot);
		assertTrue(snapshot.items.isEmpty());
	}

	@Test
	public void serverNowCountsOnlyTheTimeSinceTheAnswer()
	{
		ItemCheck.Response response = new ItemCheck.Response();
		response.v = 1;
		// Our clock is 15 minutes fast; the server answered at 1,000,000.
		ItemCheck.Snapshot snapshot = ItemCheck.Snapshot.of(response, 1_000_000, 1_900_000);
		assertEquals(1_000_000, snapshot.serverNow(1_900_000));
		assertEquals(1_120_000, snapshot.serverNow(2_020_000));
		// A clock moved backwards never makes the prices younger than when they arrived.
		assertEquals(1_000_000, snapshot.serverNow(1_000_000));
	}
}
