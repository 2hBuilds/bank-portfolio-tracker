package com.bankpricemovement;

import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, amendment 9.3: how {@link PriceService.Status} carries the drawn owner's bank-history series - the
 * three public constructors unchanged and EMPTY, {@code withBankHistory} copying every other field, the series in
 * {@code equals} / {@code hashCode}, and {@code toString} byte-identical while it is empty.
 */
public class StatusBankHistoryTest
{
	private static final BankHistorySeries TWO = BankHistorySeries.of(java.util.Arrays.asList(
		BankHistorySeriesTest.p(LocalDate.of(2026, 9, 7), 1L, 100L),
		BankHistorySeriesTest.p(LocalDate.of(2026, 9, 8), 2L, 200L)));

	private static PriceService.Status status()
	{
		return StatusFixtures.listed(3L, 7L, MovementWindow.D7, "No 7d history", LocalDate.of(2026, 9, 1),
			new PortfolioSummary(5L, 1, 1, null), 4, 2);
	}

	@Test
	public void everyPublicConstructorCarriesTheEmptySeries()
	{
		assertSame(BankHistorySeries.EMPTY, status().bankHistory());
		final PriceService.Status bare = new PriceService.Status(0L, 0L, false, false, null, null, null, 0, 0, null,
			false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, null, null);
		assertSame(BankHistorySeries.EMPTY, bare.bankHistory());
	}

	@Test
	public void withBankHistoryCopiesEveryOtherField()
	{
		final PriceService.Status base = status();
		final PriceService.Status with = base.withBankHistory(TWO);

		assertNotSame(base, with);
		assertSame(TWO, with.bankHistory());
		assertEquals("every other field copied: the same status once the series is taken back off", base,
			with.withBankHistory(BankHistorySeries.EMPTY));
		assertEquals(base.text(), with.text());
		assertEquals(base.portfolio(), with.portfolio());
		assertEquals(base.options(), with.options());
		assertEquals(base.live(), with.live());
		assertEquals(base.totalRows(), with.totalRows());
		assertEquals(base.bankItems(), with.bankItems());
		assertSame("null reads as EMPTY", BankHistorySeries.EMPTY, with.withBankHistory(null).bankHistory());
	}

	@Test
	public void theSeriesIsPartOfAStatussIdentity()
	{
		final PriceService.Status base = status();
		assertNotEquals(base, base.withBankHistory(TWO));
		assertEquals(base.withBankHistory(TWO), base.withBankHistory(BankHistorySeries.of(TWO.points())));
		assertEquals(base.withBankHistory(TWO).hashCode(),
			base.withBankHistory(BankHistorySeries.of(TWO.points())).hashCode());
	}

	@Test
	public void toStringIsUnchangedWhileTheSeriesIsEmpty()
	{
		final PriceService.Status base = status();
		assertEquals(base.toString(), base.withBankHistory(BankHistorySeries.EMPTY).toString());
		assertTrue(!base.toString().contains("bankHistory"));
		assertTrue(base.withBankHistory(TWO).toString().endsWith(", bankHistory=2}"));
	}
}
