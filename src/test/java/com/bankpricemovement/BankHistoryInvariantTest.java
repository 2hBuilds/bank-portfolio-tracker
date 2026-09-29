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
 * saved in eight parts so that it can be drawn under whatever counting switches are on NOW, and the parts the
 * switches pick add up to the Bank value card's figure to the gp.
 *
 * <p>One fixture holds every kind of stack the cells tell apart: the bank, the inventory and the worn gear; coins in
 * the bank and in hand; a parts stack (the Crystal body, banked AND worn); a parts stack with one part no table
 * prices (so it ends on the alch rule); an alch-only stack (the Dramen staff); a carried-only stack (the Karambwan
 * vessel, worn) and a carried-only alch stack (the Graceful cape); a stack in both the bank and the inventory
 * ("Item 3"); one live row (the Abyssal whip); and one id {@code ItemMapping} rewrites (the Ring of wealth (5)).
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
		return bank.withCarried(new BankReader.Carried(inventory, worn, CASH_IN_HAND, PriceServiceTest.T0));
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

	/** The 16 option sets: the three counting switches by live prices on and off (amendment 9.17). */
	static List<ViewOptions> allSixteen()
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
						sets.add(ViewOptions.DEFAULT.withLivePrices(live).withCountCash(cash)
							.withCountUntradeables(untradeables).withCountInventory(inventory));
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
	public void theFixtureFillsAllEightCellsAndHasALiveRow()
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
		assertEquals("the half-priced stack ends on its alch value, in the bank and in hand",
			HALF_PRICED_ALCH, point.card(BankHistoryPoint.CARRIED_ALCH) - 500L);
		assertEquals(3L * PriceServiceTest.SEED_NOW, point.guide(BankHistoryPoint.BANK_PARTS));
		assertEquals(3L * PriceServiceTest.SEED_NOW, point.guide(BankHistoryPoint.CARRIED_PARTS));
	}

	/**
	 * Amendment 9.17, the first form: for each of the 16 option sets X, the reading recorded by the computation run
	 * under X adds up, under X, to that same computation's Bank value, to the gp.
	 */
	@Test
	public void underEachOfTheSixteenOptionSetsTheReadingEqualsTheCardToTheGp()
	{
		for (final ViewOptions options : allSixteen())
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
			.withCountInventory(true).withLivePrices(true);
		f.service.setOptions(allOn);
		final BankHistoryPoint recorded = reading();
		assertEquals(f.lastStatus().portfolio().valueNow(), recorded.valueFor(allOn));

		for (final ViewOptions options : allSixteen())
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
}
