package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * {@link RowFilter} (contract C7): the five choices the user makes, as one immutable value.
 *
 * <p>The {@code with*} copies matter to the panel: a sort click builds a new filter from the old one and hands
 * it to the service, so a copy that quietly dropped a field would lose the user's gp band on every click.
 */
public class RowFilterTest
{
	@Test
	public void defaultIsTheFreshInstall()
	{
		assertEquals(0L, RowFilter.DEFAULT.gpMin());
		assertEquals("no upper bound", 0L, RowFilter.DEFAULT.gpMax());
		assertSame(SortMode.PERCENT_MOVE, RowFilter.DEFAULT.sort());
		assertTrue("biggest move first", RowFilter.DEFAULT.descending());
		assertSame("K4: the default window is 1d", MovementWindow.D1, RowFilter.DEFAULT.window());
	}

	@Test
	public void everyWithKeepsTheOtherFourAndLeavesTheOriginalAlone()
	{
		final RowFilter base = new RowFilter(100L, 5_000_000L, SortMode.GP_MOVE, false, MovementWindow.D30);

		final RowFilter min = base.withGpMin(250L);
		assertEquals(250L, min.gpMin());
		assertEquals(5_000_000L, min.gpMax());
		assertSame(SortMode.GP_MOVE, min.sort());
		assertFalse(min.descending());
		assertSame(MovementWindow.D30, min.window());

		assertEquals(1_000_000L, base.withGpMax(1_000_000L).gpMax());
		assertEquals(100L, base.withGpMax(1_000_000L).gpMin());

		assertSame(SortMode.UNIT_PRICE, base.withSort(SortMode.UNIT_PRICE).sort());
		assertEquals(100L, base.withSort(SortMode.UNIT_PRICE).gpMin());

		assertTrue(base.withDescending(true).descending());
		assertSame(SortMode.GP_MOVE, base.withDescending(true).sort());

		assertSame(MovementWindow.D7, base.withWindow(MovementWindow.D7).window());
		assertEquals(5_000_000L, base.withWindow(MovementWindow.D7).gpMax());

		assertEquals("the original is untouched",
			new RowFilter(100L, 5_000_000L, SortMode.GP_MOVE, false, MovementWindow.D30), base);
	}

	/**
	 * A price cannot be negative, so a band bound cannot either - whatever a hand-edited config file says.
	 */
	@Test
	public void negativeBoundsClampToZero()
	{
		final RowFilter filter = new RowFilter(-5L, -1L, SortMode.PERCENT_MOVE, true, MovementWindow.D1);
		assertEquals(0L, filter.gpMin());
		assertEquals(0L, filter.gpMax());
		assertEquals(0L, RowFilter.DEFAULT.withGpMin(-100L).gpMin());
	}

	/**
	 * The sort and the window are read on every recompute; a null from a corrupt config must not reach the
	 * comparator.
	 */
	@Test
	public void nullSortOrWindowFallsBackToTheDefault()
	{
		final RowFilter filter = new RowFilter(0L, 0L, null, true, null);
		assertSame(SortMode.PERCENT_MOVE, filter.sort());
		assertSame(MovementWindow.D1, filter.window());
		assertSame(SortMode.PERCENT_MOVE, RowFilter.DEFAULT.withSort(null).sort());
		assertSame(MovementWindow.D1, RowFilter.DEFAULT.withWindow(null).window());
	}

	/**
	 * The service compares the incoming filter with the current one to decide whether to recompute or fetch
	 * at all, so this has to be a value.
	 */
	@Test
	public void theFilterCompares()
	{
		final RowFilter a = new RowFilter(100L, 200L, SortMode.GP_MOVE, false, MovementWindow.D7);
		final RowFilter b = new RowFilter(100L, 200L, SortMode.GP_MOVE, false, MovementWindow.D7);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a, a);

		assertNotEquals(a, b.withGpMin(101L));
		assertNotEquals(a, b.withGpMax(201L));
		assertNotEquals(a, b.withSort(SortMode.UNIT_PRICE));
		assertNotEquals(a, b.withDescending(true));
		assertNotEquals(a, b.withWindow(MovementWindow.D30));
		assertNotEquals(a, null);
		assertNotEquals(a, "RowFilter");
	}
}
