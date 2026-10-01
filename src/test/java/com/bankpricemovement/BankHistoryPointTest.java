package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import org.junit.Test;

/**
 * {@link BankHistoryPoint} (addendum AU, contract sections 2 and 9.4; 1.0.9 part 3 added the two Grand Exchange
 * cells): the ten cells, the guide normalised away when it equals the card, the arrays copied in, wrong lengths
 * refused - an eight-cell array included, which only the store pads - value equality - and {@code valueFor}, the
 * ONE rule every History figure uses, checked cell by cell against the contract's table for every option set.
 *
 * <p>The cells are powers of ten, so every expected total below spells out which cells it holds: a digit 1 in
 * place {@code i} is cell {@code i} counted. The guide figures are twice the card's, so a total read from the wrong
 * array is twice as large.
 */
public class BankHistoryPointTest
{
	private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
	private static final long[] CARD = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L,
		100_000_000L, 1_000_000_000L};
	private static final long[] GUIDE = {2L, 20L, 200L, 2_000L, 20_000L, 200_000L, 2_000_000L, 20_000_000L,
		200_000_000L, 2_000_000_000L};

	private static BankHistoryPoint point(final long[] card, final long[] guide)
	{
		return new BankHistoryPoint(DAY, 1_000L, 900L, card, guide);
	}

	/** The four switches the table of addendum AU names, with the Grand Exchange one OFF. */
	private static ViewOptions options(final boolean cash, final boolean untradeables, final boolean inventory,
		final boolean live)
	{
		return options(cash, untradeables, inventory, false, live);
	}

	private static ViewOptions options(final boolean cash, final boolean untradeables, final boolean inventory,
		final boolean ge, final boolean live)
	{
		return new ViewOptions(cash, untradeables, live, inventory, ge, false);
	}

	@Test
	public void theCellsAreInTheFrozenOrder()
	{
		assertEquals(0, BankHistoryPoint.BANK_TRADEABLE);
		assertEquals(1, BankHistoryPoint.BANK_CASH);
		assertEquals(2, BankHistoryPoint.BANK_PARTS);
		assertEquals(3, BankHistoryPoint.BANK_ALCH);
		assertEquals(4, BankHistoryPoint.CARRIED_TRADEABLE);
		assertEquals(5, BankHistoryPoint.CARRIED_CASH);
		assertEquals(6, BankHistoryPoint.CARRIED_PARTS);
		assertEquals(7, BankHistoryPoint.CARRIED_ALCH);
		assertEquals(8, BankHistoryPoint.GE_TRADEABLE);
		assertEquals(9, BankHistoryPoint.GE_CASH);
		assertEquals(10, BankHistoryPoint.CELLS);
	}

	@Test
	public void theFieldsReadBack()
	{
		final BankHistoryPoint p = point(CARD, GUIDE);
		assertEquals(DAY, p.day());
		assertEquals(1_000L, p.readAtMillis());
		assertEquals(900L, p.bankAtMillis());
		assertTrue(p.hasGuide());
		for (int i = 0; i < BankHistoryPoint.CELLS; i++)
		{
			assertEquals(CARD[i], p.card(i));
			assertEquals(GUIDE[i], p.guide(i));
		}
	}

	@Test
	public void withNoGuideTheGuideReadsAsTheCard()
	{
		final BankHistoryPoint p = point(CARD, null);
		assertFalse(p.hasGuide());
		for (int i = 0; i < BankHistoryPoint.CELLS; i++)
		{
			assertEquals(CARD[i], p.guide(i));
		}
	}

	@Test
	public void aGuideEqualToTheCardIsNotStoredAndTheTwoSpellingsAreEqual()
	{
		final BankHistoryPoint spelledOut = point(CARD, CARD.clone());
		final BankHistoryPoint left = point(CARD, null);
		assertFalse("a guide equal to the card is normalised to none", spelledOut.hasGuide());
		assertEquals(left, spelledOut);
		assertEquals(left.hashCode(), spelledOut.hashCode());
	}

	@Test
	public void theArraysAreCopiedIn()
	{
		final long[] card = CARD.clone();
		final long[] guide = GUIDE.clone();
		final BankHistoryPoint p = point(card, guide);
		card[0] = 999L;
		guide[0] = 999L;
		assertEquals(1L, p.card(0));
		assertEquals(2L, p.guide(0));
	}

	@Test
	public void wrongLengthsAreRefused()
	{
		for (final int length : new int[]{0, 7, 8, 9, 11})
		{
			try
			{
				point(new long[length], null);
				fail("a card of " + length + " cells was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				// the point of the test
			}
			try
			{
				point(CARD, new long[length]);
				fail("a guide of " + length + " cells was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				// the point of the test
			}
		}
	}

	@Test(expected = NullPointerException.class)
	public void aNullCardIsRefused()
	{
		point(null, null);
	}

	@Test(expected = NullPointerException.class)
	public void aNullDayIsRefused()
	{
		new BankHistoryPoint(null, 1L, 1L, CARD, null);
	}

	@Test
	public void aNegativeCellReadsAsZero()
	{
		final long[] card = CARD.clone();
		card[BankHistoryPoint.BANK_ALCH] = -50L;
		final BankHistoryPoint p = point(card, null);
		assertEquals(0L, p.card(BankHistoryPoint.BANK_ALCH));
	}

	/**
	 * The contract's table, spelled out for the eight counting sets with live prices on (card figures) and off
	 * (guide figures, twice as large).
	 */
	@Test
	public void valueForCountsExactlyTheCellsTheContractsTableNames()
	{
		final BankHistoryPoint p = point(CARD, GUIDE);
		// cash, untradeables, inventory -> the cells counted, as digits (cell 0 is the units digit)
		final Object[][] table = {
			{false, false, false, 101L},
			{true, false, false, 111L},
			{false, true, false, 1_101L},
			{false, false, true, 1_010_101L},
			{true, true, false, 1_111L},
			{true, false, true, 1_110_111L},
			{false, true, true, 11_011_101L},
			{true, true, true, 11_111_111L},
		};
		for (final Object[] row : table)
		{
			final boolean cash = (Boolean) row[0];
			final boolean untradeables = (Boolean) row[1];
			final boolean inventory = (Boolean) row[2];
			final long expected = (Long) row[3];
			assertEquals("live on, cash=" + cash + " untradeables=" + untradeables + " inventory=" + inventory,
				expected, p.valueFor(options(cash, untradeables, inventory, true)));
			assertEquals("live off reads the guide figures", 2 * expected,
				p.valueFor(options(cash, untradeables, inventory, false)));
		}
	}

	/**
	 * 1.0.9 part 3: the Grand Exchange cells are counted with the Grand Exchange switch - the tradeable one alone,
	 * and the coins one only with the coins switch as well - and by nothing else.
	 */
	@Test
	public void valueForAddsTheGrandExchangeCellsUnderTheirSwitch()
	{
		final BankHistoryPoint p = point(CARD, GUIDE);
		final long geTradeable = 100_000_000L;
		final long geCash = 1_000_000_000L;
		final Object[][] table = {
			{false, false, false, 101L},
			{true, false, false, 111L},
			{false, true, false, 1_101L},
			{false, false, true, 1_010_101L},
			{true, true, false, 1_111L},
			{true, false, true, 1_110_111L},
			{false, true, true, 11_011_101L},
			{true, true, true, 11_111_111L},
		};
		for (final Object[] row : table)
		{
			final boolean cash = (Boolean) row[0];
			final boolean untradeables = (Boolean) row[1];
			final boolean inventory = (Boolean) row[2];
			final long without = (Long) row[3];
			final long expected = without + geTradeable + (cash ? geCash : 0L);
			assertEquals("live on, cash=" + cash + " untradeables=" + untradeables + " inventory=" + inventory,
				expected, p.valueFor(options(cash, untradeables, inventory, true, true)));
			assertEquals("live off reads the guide figures", 2 * expected,
				p.valueFor(options(cash, untradeables, inventory, true, false)));
			assertEquals("the switch off counts neither cell", without,
				p.valueFor(options(cash, untradeables, inventory, false, true)));
		}
	}

	@Test
	public void withLivePricesOffAndNoGuideTheCardFiguresAreUsed()
	{
		final BankHistoryPoint p = point(CARD, null);
		assertEquals(11_111_111L, p.valueFor(options(true, true, true, false)));
		assertEquals(1_111_111_111L, p.valueFor(options(true, true, true, true, false)));
	}

	@Test
	public void theHoverSwitchChangesNothing()
	{
		final BankHistoryPoint p = point(CARD, GUIDE);
		assertEquals(p.valueFor(ViewOptions.DEFAULT), p.valueFor(ViewOptions.DEFAULT.withShowHoverText(true)));
	}

	@Test
	public void aNullOptionsReadsAsTheDefault()
	{
		final BankHistoryPoint p = point(CARD, GUIDE);
		// DEFAULT = cash on, alch-only untradeables off, live on, inventory on, Grand Exchange on: the bank's
		// tradeables, cash and parts, the carried tradeables, cash and parts, and both Grand Exchange cells.
		assertEquals(1_101_110_111L, p.valueFor(null));
		assertEquals(p.valueFor(ViewOptions.DEFAULT), p.valueFor(null));
	}

	@Test
	public void theSumIsClampedLikeThePortfolios()
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = Long.MAX_VALUE;
		card[BankHistoryPoint.BANK_PARTS] = 5L;
		card[BankHistoryPoint.CARRIED_TRADEABLE] = Long.MAX_VALUE;
		assertEquals(Long.MAX_VALUE, point(card, null).valueFor(ViewOptions.DEFAULT));
		final long[] offers = new long[BankHistoryPoint.CELLS];
		offers[BankHistoryPoint.BANK_TRADEABLE] = Long.MAX_VALUE - 5L;
		offers[BankHistoryPoint.GE_TRADEABLE] = 10L;
		offers[BankHistoryPoint.GE_CASH] = 10L;
		assertEquals("the Grand Exchange cells clamp too", Long.MAX_VALUE,
			point(offers, null).valueFor(ViewOptions.DEFAULT));
	}

	@Test
	public void equalityCoversEveryField()
	{
		final BankHistoryPoint base = point(CARD, GUIDE);
		assertEquals(base, point(CARD.clone(), GUIDE.clone()));
		assertEquals(base.hashCode(), point(CARD.clone(), GUIDE.clone()).hashCode());
		assertNotEquals(base, new BankHistoryPoint(DAY.plusDays(1), 1_000L, 900L, CARD, GUIDE));
		assertNotEquals(base, new BankHistoryPoint(DAY, 1_001L, 900L, CARD, GUIDE));
		assertNotEquals(base, new BankHistoryPoint(DAY, 1_000L, 901L, CARD, GUIDE));
		final long[] otherCard = CARD.clone();
		otherCard[7] = 1L;
		assertNotEquals(base, point(otherCard, GUIDE));
		final long[] otherGe = CARD.clone();
		otherGe[BankHistoryPoint.GE_CASH] = 1L;
		assertNotEquals("a Grand Exchange cell is part of the reading", base, point(otherGe, GUIDE));
		final long[] otherGuide = GUIDE.clone();
		otherGuide[7] = 1L;
		assertNotEquals(base, point(CARD, otherGuide));
		assertNotEquals(base, point(CARD, null));
		assertTrue(base.toString().contains("2026-09-28"));
	}
}
