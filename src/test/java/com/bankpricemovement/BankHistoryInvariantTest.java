package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, THE invariant (contract section 2 and amendment 9.17, plan 7.7 item 6): a bank-history reading is
 * saved in ten parts (eight until 1.0.9 part 3 added the Grand Exchange offers' two) so that it can be drawn under
 * whatever counting switches are on NOW, and the parts the switches pick add up to the Bank value card's figure to
 * the gp.
 *
 * <p>One fixture holds every kind of stack the cells tell apart: the bank, the inventory and the worn gear; coins in
 * the bank and in hand; a parts stack (the Crystal body, banked AND worn); a parts stack with one part no table
 * prices (so it ends on the alch rule); an alch-only stack (the Dramen staff); a carried-only stack (the Karambwan
 * vessel, worn) and a carried-only alch stack (the Graceful cape); a stack in the bank, the inventory AND a
 * Grand Exchange offer ("Item 3"); one live row (the Abyssal whip, in the bank and in an offer); one id
 * {@code ItemMapping} rewrites (the Ring of wealth (5)); and coins in the Grand Exchange offers.
 * It is {@link PriceServiceTest}'s own, used by composition.
 */
public class BankHistoryInvariantTest
{
	/** An untradeable with two parts, one of which nothing prices: it ends on the alch rule (R2's all-or-nothing). */
	private static final int HALF_PRICED = 90_001;
	private static final long HALF_PRICED_ALCH = 700_000L;
	private static final int UNPRICED_PART = 99_001;
	private static final long CASH_IN_BANK = 200_000_000L;
	private static final long CASH_IN_HAND = 791_078L;
	private static final long CASH_IN_OFFERS = 52_000_000L;

	private final PriceServiceTest f = new PriceServiceTest();

	@Before
	public void setUp()
	{
		f.setUp();
		f.nameTheSeed();
		f.nameTheRing();
		f.warmUpLiveWith(fixture(), Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		f.answerDay(PriceServiceTest.SEP_7, PriceServiceTest.tradedSep7());
	}

	/** The one fixture (class javadoc). */
	static BankSnapshot fixture()
	{
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.currencyGp = CASH_IN_BANK;
		bank.items.add(new BankItem(PriceServiceTest.RING_OF_WEALTH_5, 2, "Ring of wealth (5)", false));
		bank.items.add(crystalBody());
		bank.items.add(halfPriced());
		bank.items.add(new BankItem(PriceServiceTest.DRAMEN, 2, "Dramen staff", false, true, 1_500));
		final List<BankItem> inventory = Arrays.asList(
			new BankItem(PriceServiceTest.item(3), 1, "Item 3", true),
			new BankItem(PriceServiceTest.GRACEFUL, 1, "Graceful cape", false, true, 500),
			halfPriced());
		final List<BankItem> worn = Arrays.asList(
			new BankItem(PriceServiceTest.VESSEL, 1, "Karambwan vessel", false),
			crystalBody());
		// 1.0.9 part 3: two of Item 3 for sale and one more whip bought and not collected - both merge into rows the
		// bank already holds - and the coins the offers hold.
		final List<BankItem> exchange = Arrays.asList(
			new BankItem(PriceServiceTest.item(3), 2, "Item 3", true),
			new BankItem(PriceServiceTest.WHIP, 1, "Abyssal whip", false));
		return bank.withCarried(new BankReader.Carried(inventory, worn, exchange, CASH_IN_HAND, CASH_IN_OFFERS,
			PriceServiceTest.T0));
	}

	private static BankItem crystalBody()
	{
		return new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME)));
	}

	private static BankItem halfPriced()
	{
		return new BankItem(HALF_PRICED, 1, "Half-priced thing", false, true, (int) HALF_PRICED_ALCH,
			Arrays.asList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 1L, PriceServiceTest.SEED_NAME),
				new BankItem.Part(UNPRICED_PART, 1L, "Unpriced part")));
	}

	/**
	 * The 32 option sets: the four counting switches by live prices on and off (amendment 9.17 had sixteen, before
	 * 1.0.9 part 3 added the Grand Exchange switch).
	 */
	static List<ViewOptions> allThirtyTwo()
	{
		final List<ViewOptions> sets = new ArrayList<>();
		for (final boolean live : new boolean[]{true, false})
		{
			for (final boolean cash : new boolean[]{true, false})
			{
				for (final boolean untradeables : new boolean[]{true, false})
				{
					for (final boolean inventory : new boolean[]{true, false})
					{
						for (final boolean offers : new boolean[]{true, false})
						{
							sets.add(ViewOptions.DEFAULT.withLivePrices(live).withCountCash(cash)
								.withCountUntradeables(untradeables).withCountInventory(inventory)
								.withCountGrandExchange(offers));
						}
					}
				}
			}
		}
		return sets;
	}

	/** The reading the last publish carries: today's, which is the only one there is. */
	private BankHistoryPoint reading()
	{
		final PriceService.Status status = f.lastStatus();
		assertEquals("one reading, today's", 1, status.bankHistory().size());
		return status.bankHistory().last();
	}

	/** The fixture really is the one the contract names - every cell has something in it, and one row is live. */
	@Test
	public void theFixtureFillsAllTenCellsAndHasALiveRow()
	{
		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		final BankHistoryPoint point = reading();
		for (int cell = 0; cell < BankHistoryPoint.CELLS; cell++)
		{
			assertTrue("cell " + cell + " holds something", point.card(cell) > 0L);
		}
		assertTrue("a live row", f.lastStatus().live().liveRows() >= 1);
		final MovementRow whip = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.WHIP);
		assertNotNull(whip);
		assertTrue("the whip is the live row", whip.isLive());
		assertNotEquals("so the card figures and the guide figures differ", point.card(BankHistoryPoint.BANK_TRADEABLE),
			point.guide(BankHistoryPoint.BANK_TRADEABLE));
		assertTrue(point.hasGuide());
		assertEquals("the rewritten ring is priced", Long.valueOf(PriceServiceTest.RING_GP),
			PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.RING_OF_WEALTH_5).unitPrice());
		assertEquals(CASH_IN_BANK, point.card(BankHistoryPoint.BANK_CASH));
		assertEquals(CASH_IN_HAND, point.card(BankHistoryPoint.CARRIED_CASH));
		assertEquals(CASH_IN_OFFERS, point.card(BankHistoryPoint.GE_CASH));
		assertEquals(CASH_IN_OFFERS, point.guide(BankHistoryPoint.GE_CASH));
		assertEquals("the half-priced stack ends on its alch value, in the bank and in hand",
			HALF_PRICED_ALCH, point.card(BankHistoryPoint.CARRIED_ALCH) - 500L);
		assertEquals(3L * PriceServiceTest.SEED_NOW, point.guide(BankHistoryPoint.BANK_PARTS));
		assertEquals(3L * PriceServiceTest.SEED_NOW, point.guide(BankHistoryPoint.CARRIED_PARTS));
	}

	/**
	 * Amendment 9.17, the first form: for each of the 32 option sets X, the reading recorded by the computation run
	 * under X adds up, under X, to that same computation's Bank value, to the gp.
	 */
	@Test
	public void underEachOfTheThirtyTwoOptionSetsTheReadingEqualsTheCardToTheGp()
	{
		for (final ViewOptions options : allThirtyTwo())
		{
			f.service.setOptions(options);
			final PriceService.Status status = f.lastStatus();
			assertEquals(options, status.options());
			assertFalse(options + ": not degraded", status.anchorDegraded());
			assertEquals(options.toString(), status.portfolio().valueNow(), reading().valueFor(options));
		}
	}

	/**
	 * Amendment 9.17, the second form, with degraded mode off: the reading recorded under every switch on (live
	 * prices on) adds up, under ANY X, to the Bank value of a computation run under X - so a flipped switch redraws
	 * every past day at exactly the figure the card would have shown that day.
	 */
	@Test
	public void theReadingRecordedWithEverySwitchOnEqualsTheCardUnderAnyOptionSet()
	{
		final ViewOptions allOn = ViewOptions.DEFAULT.withCountCash(true).withCountUntradeables(true)
			.withCountInventory(true).withCountGrandExchange(true).withLivePrices(true);
		f.service.setOptions(allOn);
		final BankHistoryPoint recorded = reading();
		assertEquals(f.lastStatus().portfolio().valueNow(), recorded.valueFor(allOn));

		for (final ViewOptions options : allThirtyTwo())
		{
			f.service.setOptions(options);
			final PriceService.Status status = f.lastStatus();
			assertFalse(options + ": not degraded", status.anchorDegraded());
			assertEquals(options.toString(), status.portfolio().valueNow(), recorded.valueFor(options));
		}
	}

	/**
	 * The cells follow the contract's arithmetic stack by stack: "Item 3" is 3 in the bank and 1 carried, the
	 * Crystal body 1 and 1, and a stack's bank cell plus its carried cell is its whole holding.
	 */
	@Test
	public void aStackHeldInTwoPlacesIsSplitByQuantity()
	{
		f.service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		final BankHistoryPoint point = reading();
		final long seedsPerBody = 3L * PriceServiceTest.SEED_NOW;
		assertEquals("one body banked", seedsPerBody, point.card(BankHistoryPoint.BANK_PARTS));
		assertEquals("one body worn", seedsPerBody, point.card(BankHistoryPoint.CARRIED_PARTS));
		assertEquals("2 Dramen staffs and one half-priced thing in the bank", 2L * 1_500L + HALF_PRICED_ALCH,
			point.card(BankHistoryPoint.BANK_ALCH));
		final MovementRow three = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.item(3));
		final MovementRow vessel = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.VESSEL);
		assertEquals("one of Item 3 and the vessel are carried", three.unitPrice() + vessel.unitPrice(),
			point.card(BankHistoryPoint.CARRIED_TRADEABLE));
		assertFalse("live prices off: the guide is the card", point.hasGuide());
	}

	/**
	 * 1.0.9 part 3: the offers' items are the third part of a stack held in three places. "Item 3" is 3 in the bank, 1
	 * carried and 2 in offers, the whip 1 and 1: the bank cell and the carried cell are what they were without the
	 * offers, the Grand Exchange cell is exactly the offers' items at the row's unit - and the same snapshot without
	 * its offers gives the same bank and carried cells.
	 */
	@Test
	public void theOffersItemsFillTheirOwnCellAndLeaveTheBankAndCarriedCellsAlone()
	{
		f.service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		final BankHistoryPoint point = reading();
		final MovementRow three = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.item(3));
		final MovementRow whip = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.WHIP);
		assertEquals("2 of Item 3 and 1 whip in the offers, each at its unit",
			2L * three.unitPrice() + whip.unitPrice(), point.card(BankHistoryPoint.GE_TRADEABLE));
		assertEquals(CASH_IN_OFFERS, point.card(BankHistoryPoint.GE_CASH));
		assertEquals("the merged row is 3 + 1 + 2", 6, three.quantity());
		assertEquals(2, three.exchangeQuantity());
		assertEquals(3, three.bankQuantity());
		assertEquals(1, three.inventoryQuantity());

		// The same snapshot with no offers at all: every other cell is what it was.
		final BankSnapshot full = fixture();
		final BankSnapshot bare = full.withCarried(new BankReader.Carried(full.inventory, full.worn, CASH_IN_HAND,
			PriceServiceTest.T0));
		f.service.setBank(bare);
		final BankHistoryPoint without = reading();
		for (int cell = 0; cell < BankHistoryPoint.GE_TRADEABLE; cell++)
		{
			assertEquals("cell " + cell, without.card(cell), point.card(cell));
		}
		assertEquals(0L, without.card(BankHistoryPoint.GE_TRADEABLE));
		assertEquals(0L, without.card(BankHistoryPoint.GE_CASH));
	}
}
