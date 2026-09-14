package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * {@link PricePoint} (contract C3): the mid rule, branch by branch.
 *
 * <p>Every branch is a real case, not a defensive one: the live {@code /1h} bucket measured on 2026-09-08 had
 * about 20 % of entries with no {@code avgHighPrice} and about 16 % with no {@code avgLowPrice} (research C10),
 * so "one side only" is roughly a third of the market and the mid rule has to be symmetric. The both-null case
 * was never observed and is the defensive one.
 */
public class PricePointTest
{
	@Test
	public void bothSidesGiveTheMean()
	{
		assertEquals(Long.valueOf(150L), new PricePoint(200L, 100L).mid());
		assertEquals(Long.valueOf(1_550_000L), new PricePoint(1_600_000L, 1_500_000L).mid());
		assertEquals(Long.valueOf(99L), new PricePoint(100L, 98L).mid());
	}

	/**
	 * Half-up, so an odd sum does not silently round the price down every time.
	 */
	@Test
	public void anOddSumRoundsUp()
	{
		assertEquals(Long.valueOf(3L), new PricePoint(3L, 2L).mid());
		assertEquals(Long.valueOf(2L), new PricePoint(2L, 1L).mid());
		assertEquals(Long.valueOf(101L), new PricePoint(101L, 100L).mid());
	}

	@Test
	public void oneSideStandsAloneWhicheverSideItIs()
	{
		assertEquals("no low: the high is the price", Long.valueOf(100L), new PricePoint(100L, null).mid());
		assertEquals("no high: the low is the price", Long.valueOf(80L), new PricePoint(null, 80L).mid());
	}

	@Test
	public void neitherSideIsNoPriceRatherThanZero()
	{
		assertNull(new PricePoint(null, null).mid());
		assertTrue(new PricePoint(null, null).isEmpty());
		assertTrue(PricePoint.EMPTY.isEmpty());
		assertNull(PricePoint.EMPTY.mid());
	}

	@Test
	public void aPointWithEitherSideIsNotEmpty()
	{
		assertFalse(new PricePoint(1L, null).isEmpty());
		assertFalse(new PricePoint(null, 1L).isEmpty());
		assertFalse(new PricePoint(1L, 1L).isEmpty());
	}

	/**
	 * A price of zero is a price. Only null means "no price".
	 */
	@Test
	public void zeroIsAPrice()
	{
		final PricePoint zero = new PricePoint(0L, 0L);
		assertFalse(zero.isEmpty());
		assertEquals(Long.valueOf(0L), zero.mid());
	}

	/**
	 * Prices may exceed int (research), so the sides are longs - and the mid of two huge sides must not
	 * overflow the way {@code (high + low) / 2} would.
	 */
	@Test
	public void aHugePairDoesNotOverflow()
	{
		final Long mid = new PricePoint(Long.MAX_VALUE, Long.MAX_VALUE).mid();
		assertTrue("the mid of two maximums is still positive, not a wrapped negative", mid > 0L);
		assertEquals(Long.valueOf(Long.MAX_VALUE), mid);

		assertEquals(Long.valueOf(3_000_000_000L), new PricePoint(4_000_000_000L, 2_000_000_000L).mid());
	}

	@Test
	public void sidesAreReadableAndTheValueCompares()
	{
		final PricePoint point = new PricePoint(200L, 100L);
		assertEquals(Long.valueOf(200L), point.high());
		assertEquals(Long.valueOf(100L), point.low());

		assertEquals(new PricePoint(200L, 100L), point);
		assertEquals(new PricePoint(200L, 100L).hashCode(), point.hashCode());
		assertNotEquals(new PricePoint(100L, 200L), point);
		assertNotEquals(new PricePoint(200L, null), point);
		assertNotEquals(point, null);
		assertNotEquals(point, "PricePoint");
	}
}
