package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * {@link BankHistorySeries} (addendum AU, contract sections 3 and 9.4): sorted, one reading per day (the later
 * reading wins, the one given later on a tie, {@code with} unconditionally), {@code upTo} dropping the future, the
 * look-ups either side of a gap, and value equality.
 */
public class BankHistorySeriesTest
{
	static LocalDate sep(final int day)
	{
		return LocalDate.of(2026, 9, day);
	}

	/** A reading of {@code day} whose bank-tradeable cell is {@code gp}, taken at {@code readAt}. */
	static BankHistoryPoint p(final LocalDate day, final long readAt, final long gp)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = gp;
		return new BankHistoryPoint(day, readAt, readAt, card, null);
	}

	@Test
	public void emptyHasNothing()
	{
		final BankHistorySeries s = BankHistorySeries.EMPTY;
		assertTrue(s.isEmpty());
		assertEquals(0, s.size());
		assertNull(s.first());
		assertNull(s.last());
		assertNull(s.on(sep(1)));
		assertNull(s.atOrBefore(sep(1)));
		assertTrue(s.points().isEmpty());
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.of(null));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.of(Collections.<BankHistoryPoint>emptyList()));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.of(Collections.<BankHistoryPoint>singletonList(null)));
	}

	@Test
	public void ofSortsByDayWhateverTheOrderGiven()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(5), 5, 50), p(sep(1), 1, 10),
			null, p(sep(3), 3, 30)));
		assertEquals(3, s.size());
		assertEquals(Arrays.asList(sep(1), sep(3), sep(5)), days(s));
		assertEquals(sep(1), s.first().day());
		assertEquals(sep(5), s.last().day());
	}

	@Test
	public void ofKeepsOneReadingPerDayTheLaterOne()
	{
		final BankHistoryPoint late = p(sep(2), 200, 2);
		final BankHistoryPoint early = p(sep(2), 100, 1);
		assertSame(late, BankHistorySeries.of(Arrays.asList(late, early)).on(sep(2)));
		assertSame(late, BankHistorySeries.of(Arrays.asList(early, late)).on(sep(2)));
	}

	@Test
	public void onATieTheReadingGivenLaterWins()
	{
		final BankHistoryPoint a = p(sep(2), 100, 1);
		final BankHistoryPoint b = p(sep(2), 100, 2);
		assertSame(b, BankHistorySeries.of(Arrays.asList(a, b)).on(sep(2)));
		assertSame(a, BankHistorySeries.of(Arrays.asList(b, a)).on(sep(2)));
	}

	@Test
	public void withReplacesThatDaysReadingEvenWithAnEarlierTime()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 500, 1), p(sep(2), 500, 2)));
		final BankHistoryPoint replacement = p(sep(2), 1, 99);
		final BankHistorySeries next = s.with(replacement);
		assertSame("last wins for that day", replacement, next.on(sep(2)));
		assertEquals(2, next.size());
		assertEquals("the original is untouched", 2L, s.on(sep(2)).card(BankHistoryPoint.BANK_TRADEABLE));
		assertSame(s, s.with(null));

		final BankHistorySeries grown = s.with(p(sep(9), 9, 9));
		assertEquals(Arrays.asList(sep(1), sep(2), sep(9)), days(grown));
		assertEquals(sep(7), BankHistorySeries.EMPTY.with(p(sep(7), 1, 1)).first().day());
	}

	@Test
	public void upToDropsOnlyTheReadingsAfterToday()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1), p(sep(5), 5, 5),
			p(sep(6), 6, 6)));
		assertEquals(Arrays.asList(sep(1), sep(5)), days(s.upTo(sep(5))));
		assertSame("nothing after today: the same series", s, s.upTo(sep(6)));
		assertSame(s, s.upTo(sep(30)));
		assertSame(BankHistorySeries.EMPTY, s.upTo(LocalDate.of(2026, 8, 31)));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.upTo(sep(1)));
	}

	@Test
	public void pointsCannotBeChanged()
	{
		final BankHistorySeries s = BankHistorySeries.of(Collections.singletonList(p(sep(1), 1, 1)));
		try
		{
			s.points().add(p(sep(2), 2, 2));
			fail("the list was modifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}
		final List<BankHistoryPoint> input = new ArrayList<>(Collections.singletonList(p(sep(1), 1, 1)));
		final BankHistorySeries fromInput = BankHistorySeries.of(input);
		input.add(p(sep(2), 2, 2));
		assertEquals("the series does not see a later change to its input", 1, fromInput.size());
	}

	@Test
	public void onAndAtOrBeforeEitherSideOfAGap()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(2), 2, 2), p(sep(5), 5, 5),
			p(sep(9), 9, 9), p(sep(10), 10, 10)));
		assertNull("before the first reading", s.on(sep(1)));
		assertNull(s.atOrBefore(sep(1)));
		assertEquals(sep(2), s.on(sep(2)).day());
		assertEquals(sep(2), s.atOrBefore(sep(2)).day());
		assertNull("in a gap there is no reading OF the day", s.on(sep(4)));
		assertEquals("but the last one before it", sep(2), s.atOrBefore(sep(4)).day());
		assertEquals(sep(5), s.atOrBefore(sep(8)).day());
		assertEquals(sep(9), s.atOrBefore(sep(9)).day());
		assertEquals(sep(10), s.atOrBefore(sep(10)).day());
		assertEquals("after the last reading", sep(10), s.atOrBefore(sep(30)).day());
		assertNull(s.on(sep(30)));
		assertNull(s.on(null));
		assertNull(s.atOrBefore(null));
	}

	@Test
	public void valueEqualityOverThePoints()
	{
		final BankHistorySeries a = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1), p(sep(2), 2, 2)));
		final BankHistorySeries b = BankHistorySeries.EMPTY.with(p(sep(2), 2, 2)).with(p(sep(1), 1, 1));
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, a.with(p(sep(2), 2, 3)));
		assertNotEquals(a, a.with(p(sep(3), 3, 3)));
		assertEquals(BankHistorySeries.EMPTY, BankHistorySeries.of(new ArrayList<BankHistoryPoint>()));

		// A point spelled with its guide equal to its card is the same reading.
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[0] = 7L;
		final BankHistorySeries spelled = BankHistorySeries.EMPTY.with(new BankHistoryPoint(sep(1), 1, 1, card,
			card.clone()));
		final BankHistorySeries bare = BankHistorySeries.EMPTY.with(new BankHistoryPoint(sep(1), 1, 1, card, null));
		assertEquals(bare, spelled);
		assertFalse(a.toString().isEmpty());
	}

	// ---- 1.0.9 part 5: the fresh start

	private static BankHistorySeries fiveDays()
	{
		return BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1), p(sep(2), 2, 2), p(sep(3), 3, 3), p(sep(4), 4, 4),
			p(sep(5), 5, 5)));
	}

	@Test
	public void aSeriesFromOfHasNoFreshStartAndNoLegacyDays()
	{
		final BankHistorySeries s = fiveDays();
		assertNull(s.freshFrom());
		assertFalse(s.hasLegacyDays());
		assertSame(s, s.fromFresh());
		assertNull(BankHistorySeries.EMPTY.freshFrom());
		assertFalse(BankHistorySeries.EMPTY.hasLegacyDays());
	}

	@Test
	public void theFreshStartIsCarriedByEveryCopy()
	{
		final BankHistorySeries s = fiveDays().withFreshFrom(sep(3));
		assertEquals(sep(3), s.freshFrom());
		assertEquals(sep(3), s.with(p(sep(6), 6, 6)).freshFrom());
		assertEquals(sep(3), s.with(p(sep(2), 22, 22)).freshFrom());
		assertEquals(sep(3), s.upTo(sep(4)).freshFrom());
		assertEquals(sep(3), s.upTo(sep(9)).freshFrom());
		assertEquals(sep(3), s.with(null).freshFrom());
		assertEquals(BankHistorySeries.of(s.points()).withFreshFrom(sep(3)), s);

		final BankHistorySeries all = fiveDays().withFreshFrom(LocalDate.MAX);
		assertEquals(LocalDate.MAX, all.freshFrom());
		assertEquals(LocalDate.MAX, all.with(p(sep(6), 6, 6)).freshFrom());
		assertEquals(LocalDate.MAX, all.upTo(sep(2)).freshFrom());
	}

	@Test
	public void withFreshFromSetsAndClearsAndEmptyHasNothingToHide()
	{
		final BankHistorySeries s = fiveDays();
		assertSame(s, s.withFreshFrom(null));
		final BankHistorySeries cut = s.withFreshFrom(sep(2));
		assertEquals(sep(2), cut.freshFrom());
		assertNull(cut.withFreshFrom(null).freshFrom());
		assertSame(cut, cut.withFreshFrom(sep(2)));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.withFreshFrom(LocalDate.MAX));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.withFreshFrom(sep(1)));
		assertNull(BankHistorySeries.EMPTY.with(p(sep(1), 1, 1)).freshFrom());
	}

	@Test
	public void fromFreshKeepsTheDaysAtOrAfterTheFreshStart()
	{
		final BankHistorySeries cut = fiveDays().withFreshFrom(sep(3)).fromFresh();
		assertEquals(Arrays.asList(sep(3), sep(4), sep(5)), days(cut));
		assertNull("the cut series has no legacy days of its own", cut.freshFrom());
		assertFalse(cut.hasLegacyDays());

		// The day a fresh start names is itself fresh, and so is every later one.
		assertEquals(Arrays.asList(sep(5)), days(fiveDays().withFreshFrom(sep(5)).fromFresh()));
		// A fresh start at or before the first day cuts nothing.
		assertEquals(days(fiveDays()), days(fiveDays().withFreshFrom(sep(1)).fromFresh()));
		assertEquals(days(fiveDays()), days(fiveDays().withFreshFrom(LocalDate.of(2020, 1, 1)).fromFresh()));
		// A fresh start after the last day leaves nothing.
		assertSame(BankHistorySeries.EMPTY, fiveDays().withFreshFrom(sep(6)).fromFresh());
	}

	@Test
	public void everyDayLegacyIsCutToNothing()
	{
		final BankHistorySeries all = fiveDays().withFreshFrom(LocalDate.MAX);
		assertSame(BankHistorySeries.EMPTY, all.fromFresh());
		assertEquals(5, all.size());
		assertEquals("the whole series is still there to be shown on request", fiveDays().points(), all.points());
	}

	@Test
	public void hasLegacyDaysNeedsAFreshStartAndADayBeforeIt()
	{
		assertFalse(fiveDays().hasLegacyDays());
		assertTrue(fiveDays().withFreshFrom(sep(3)).hasLegacyDays());
		assertTrue(fiveDays().withFreshFrom(sep(2)).hasLegacyDays());
		assertTrue(fiveDays().withFreshFrom(LocalDate.MAX).hasLegacyDays());
		assertFalse("nothing lies before the first day", fiveDays().withFreshFrom(sep(1)).hasLegacyDays());
		assertFalse(fiveDays().withFreshFrom(LocalDate.of(2020, 1, 1)).hasLegacyDays());
		assertFalse(BankHistorySeries.EMPTY.withFreshFrom(LocalDate.MAX).hasLegacyDays());
	}

	@Test
	public void equalityIncludesTheFreshStart()
	{
		final BankHistorySeries plain = fiveDays();
		final BankHistorySeries cutAt3 = fiveDays().withFreshFrom(sep(3));
		assertNotEquals(plain, cutAt3);
		assertNotEquals(cutAt3, fiveDays().withFreshFrom(sep(4)));
		assertNotEquals(cutAt3, fiveDays().withFreshFrom(LocalDate.MAX));
		assertEquals(cutAt3, fiveDays().withFreshFrom(sep(3)));
		assertEquals(cutAt3.hashCode(), fiveDays().withFreshFrom(sep(3)).hashCode());
		assertNotEquals(plain.hashCode(), cutAt3.hashCode());
		assertTrue(cutAt3.toString().contains("freshFrom=2026-09-03"));
		assertTrue(fiveDays().withFreshFrom(LocalDate.MAX).toString().contains("freshFrom=all"));
		assertFalse(plain.toString().contains("freshFrom"));
	}

	static List<LocalDate> days(final BankHistorySeries s)
	{
		final List<LocalDate> days = new ArrayList<>();
		for (final BankHistoryPoint point : s.points())
		{
			days.add(point.day());
		}
		return days;
	}
}
