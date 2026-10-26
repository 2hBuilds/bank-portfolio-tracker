package com.bankpricemovement;

import java.time.LocalDate;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, amendment 9.3: how {@link PriceService.Status} carries the drawn owner's bank-history series - a null
 * series reads as EMPTY, the fixtures' copy keeps every other field, and the series is part of {@code equals} /
 * {@code hashCode}.
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
	public void aNullSeriesReadsAsTheEmptyOne()
	{
		assertSame(BankHistorySeries.EMPTY, status().bankHistory());
		final PriceService.Status bare = StatusFixtures.status(0L, 0L, false, false, null, null, null, 0, 0, null,
			false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, null, null, ViewOptions.DEFAULT, null);
		assertSame(BankHistorySeries.EMPTY, bare.bankHistory());
	}

	@Test
	public void theCopyKeepsEveryOtherField()
	{
		final PriceService.Status base = status();
		final PriceService.Status with = StatusFixtures.withBankHistory(base, TWO);

		assertNotSame(base, with);
		assertSame(TWO, with.bankHistory());
		assertEquals("every other field copied: the same status once the series is taken back off", base,
			StatusFixtures.withBankHistory(with, BankHistorySeries.EMPTY));
		assertEquals(PanelState.text(base), PanelState.text(with));
		assertEquals(base.portfolio(), with.portfolio());
		assertEquals(base.options(), with.options());
		assertEquals(base.live(), with.live());
		assertEquals(base.totalRows, with.totalRows);
		assertEquals(base.bankItems(), with.bankItems());
		assertSame("null reads as EMPTY", BankHistorySeries.EMPTY,
			StatusFixtures.withBankHistory(with, null).bankHistory());
	}

	@Test
	public void theSeriesIsPartOfAStatussIdentity()
	{
		final PriceService.Status base = status();
		assertNotEquals(base, StatusFixtures.withBankHistory(base, TWO));
		assertEquals(StatusFixtures.withBankHistory(base, TWO),
			StatusFixtures.withBankHistory(base, BankHistorySeries.of(TWO.points())));
		assertEquals(StatusFixtures.withBankHistory(base, TWO).hashCode(),
			StatusFixtures.withBankHistory(base, BankHistorySeries.of(TWO.points())).hashCode());
	}

	@Test
	public void aStatusHoldsAnEmptySeriesUntilOneIsPutOn()
	{
		final PriceService.Status base = status();
		assertTrue(base.bankHistory().isEmpty());
		assertEquals(base, StatusFixtures.withBankHistory(base, BankHistorySeries.EMPTY));
		assertEquals(2, StatusFixtures.withBankHistory(base, TWO).bankHistory().size());
	}
}
