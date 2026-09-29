package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import org.junit.Test;

/** {@link BankHistoryRange} (addendum AU, contract section 3 and plan 7.2 item 6). */
public class BankHistoryRangeTest
{
	@Test
	public void theFourRangesInOrderWithTheirDaysAndLabels()
	{
		assertEquals(Arrays.asList(BankHistoryRange.D7, BankHistoryRange.D30, BankHistoryRange.D90,
			BankHistoryRange.ALL), Arrays.asList(BankHistoryRange.values()));
		assertEquals(7, BankHistoryRange.D7.days());
		assertEquals(30, BankHistoryRange.D30.days());
		assertEquals(90, BankHistoryRange.D90.days());
		assertEquals(0, BankHistoryRange.ALL.days());
		assertEquals("7d", BankHistoryRange.D7.label());
		assertEquals("30d", BankHistoryRange.D30.label());
		assertEquals("90d", BankHistoryRange.D90.label());
		assertEquals("all", BankHistoryRange.ALL.label());
		for (final BankHistoryRange range : BankHistoryRange.values())
		{
			assertEquals(range.label(), range.toString());
		}
	}

	@Test
	public void theCardsWindowPicksTheRange()
	{
		assertEquals(BankHistoryRange.D7, BankHistoryRange.forWindow(MovementWindow.D1));
		assertEquals(BankHistoryRange.D7, BankHistoryRange.forWindow(MovementWindow.D7));
		assertEquals(BankHistoryRange.D30, BankHistoryRange.forWindow(MovementWindow.D30));
		assertEquals(BankHistoryRange.D90, BankHistoryRange.forWindow(MovementWindow.D90));
		assertEquals(BankHistoryRange.ALL, BankHistoryRange.forWindow(MovementWindow.D180));
		assertEquals("a null window is the default window's range", BankHistoryRange.D7,
			BankHistoryRange.forWindow(null));
		assertEquals(BankHistoryRange.forWindow(MovementWindow.DEFAULT), BankHistoryRange.forWindow(null));
	}
}
