package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.p;
import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * {@link BankHistoryMath} (addendum AU, contract sections 3, 9.5 and 9.6): the window change and the card's move,
 * the change against the previous reading, the calendar days with their carried gaps, the thinning, and the day a
 * greyed chip fills - with 0 and 1 readings, a window with no reading old enough (null, never 0), the span when the
 * reading used is older than the window's day, and the day boundary at local midnight.
 */
public class BankHistoryMathTest
{
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	/** Readings on 1, 2, 5 and 10 Sep worth 100, 110, 150, 200 (bank-tradeable only). */
	private static BankHistorySeries fourReadings()
	{
		return BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 100), p(sep(2), 2, 110), p(sep(5), 5, 150),
			p(sep(10), 10, 200)));
	}

	private static long millis(final LocalDateTime local, final ZoneId zone)
	{
		return local.atZone(zone).toInstant().toEpochMilli();
	}

	// ---- dayOf

	@Test
	public void aReadingIsFiledUnderTheLocalDateAndTheDayTurnsAtLocalMidnight()
	{
		final long lastMilli = millis(LocalDateTime.of(2026, 9, 28, 23, 59, 59, 999_000_000), TORONTO);
		assertEquals(sep(28), BankHistoryMath.dayOf(lastMilli, TORONTO));
		assertEquals(sep(29), BankHistoryMath.dayOf(lastMilli + 1, TORONTO));

		// 9 pm in Toronto is 1 am UTC the next day: the player's day, not UTC's (plan 7.1 item 2).
		final long nineInTheEvening = millis(LocalDateTime.of(2026, 9, 28, 21, 0), TORONTO);
		assertEquals(sep(28), BankHistoryMath.dayOf(nineInTheEvening, TORONTO));
		assertEquals(sep(29), BankHistoryMath.dayOf(nineInTheEvening, ZoneOffset.UTC));
	}

	// ---- overDays

	@Test
	public void overDaysComparesTheLastReadingWithTheLastOneOnOrBeforeTheWindowsDay()
	{
		final BankHistoryMath.Change c = BankHistoryMath.overDays(fourReadings(), sep(10), 5, null);
		assertNotNull(c);
		assertEquals(sep(5), c.fromDay());
		assertEquals(sep(10), c.toDay());
		assertEquals("from 150 to 200", new BankHistoryMath.Change(sep(5), sep(10), 150L, 200L), c);
		assertEquals(50L, c.deltaGp());
		assertEquals(50 * 100.0d / 150, c.pct(), 0.0d);
		assertEquals(5, c.spanDays());
	}

	@Test
	public void theSpanSaysWhenTheReadingUsedIsOlderThanTheWindowsDay()
	{
		// 1d on the 10th wants the 9th; the 9th has none, so the 5th is used and the span is 5 days, not 1.
		final BankHistoryMath.Change c = BankHistoryMath.overDays(fourReadings(), sep(10), 1, null);
		assertEquals(sep(5), c.fromDay());
		assertEquals(5, c.spanDays());
	}

	@Test
	public void aWindowWithNoReadingOldEnoughIsNullNeverZero()
	{
		assertNull(BankHistoryMath.overDays(fourReadings(), sep(10), 10, null));
		assertNotNull("the 9th boundary: 9 days back is the 1st", BankHistoryMath.overDays(fourReadings(), sep(10),
			9, null));
		assertNull(BankHistoryMath.overDays(fourReadings(), sep(10), 30, null));
	}

	@Test
	public void overDaysWithNoOrOneReadingIsNull()
	{
		assertNull(BankHistoryMath.overDays(BankHistorySeries.EMPTY, sep(10), 7, null));
		assertNull(BankHistoryMath.overDays(null, sep(10), 7, null));
		final BankHistorySeries one = BankHistorySeries.EMPTY.with(p(sep(3), 3, 100));
		assertNull(BankHistoryMath.overDays(one, sep(10), 7, null));
		assertNull(BankHistoryMath.overDays(one, sep(10), 1, null));
		assertNull("all, over one reading: from is to", BankHistoryMath.overDays(one, sep(10), 0, null));
	}

	@Test
	public void overDaysIsNullWhenFromIsTheSameReadingAsTo()
	{
		// Readings on the 1st and 5th; on the 7th, 1d wants the 6th, whose last reading is the 5th - which is also
		// the latest. That is no measured change, so no figure.
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 100), p(sep(5), 5, 150)));
		assertNull(BankHistoryMath.overDays(s, sep(7), 1, null));
		assertNotNull(BankHistoryMath.overDays(s, sep(7), 6, null));
	}

	@Test
	public void overDaysWithZeroOrFewerDaysMeansFromTheFirstReading()
	{
		for (final int days : new int[]{0, -1})
		{
			final BankHistoryMath.Change c = BankHistoryMath.overDays(fourReadings(), sep(10), days, null);
			assertEquals(sep(1), c.fromDay());
			assertEquals("from the first reading's 100", new BankHistoryMath.Change(sep(1), sep(10), 100L, 200L), c);
			assertEquals(9, c.spanDays());
		}
	}

	@Test
	public void overDaysIgnoresReadingsAfterToday()
	{
		// On the 7th the 10th has not happened: to is the 5th.
		final BankHistoryMath.Change c = BankHistoryMath.overDays(fourReadings(), sep(7), 5, null);
		assertEquals(sep(2), c.fromDay());
		assertEquals(sep(5), c.toDay());
		assertEquals(40L, c.deltaGp());
	}

	@Test
	public void theChangeFollowsTheSwitches()
	{
		final long[] a = new long[BankHistoryPoint.CELLS];
		a[BankHistoryPoint.BANK_TRADEABLE] = 100L;
		a[BankHistoryPoint.BANK_CASH] = 1_000L;
		final long[] b = a.clone();
		b[BankHistoryPoint.BANK_TRADEABLE] = 200L;
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(new BankHistoryPoint(sep(1), 1, 1, a, null),
			new BankHistoryPoint(sep(2), 2, 2, b, null)));
		final BankHistoryMath.Change withCash = BankHistoryMath.overDays(s, sep(2), 1, ViewOptions.DEFAULT);
		final BankHistoryMath.Change without = BankHistoryMath.overDays(s, sep(2), 1,
			ViewOptions.DEFAULT.withCountCash(false));
		assertEquals(new BankHistoryMath.Change(sep(1), sep(2), 1_100L, 1_200L), withCash);
		assertEquals(new BankHistoryMath.Change(sep(1), sep(2), 100L, 200L), without);
		assertEquals("the same gp move", withCash.deltaGp(), without.deltaGp());
		assertEquals(100 * 100.0d / 1_100, withCash.pct(), 0.0d);
		assertEquals(100.0d, without.pct(), 0.0d);
		assertEquals("a null options is the default", withCash, BankHistoryMath.overDays(s, sep(2), 1, null));
	}

	@Test
	public void aChangeFromZeroHasNoPercentage()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 0), p(sep(2), 2, 50)));
		final BankHistoryMath.Change c = BankHistoryMath.overDays(s, sep(2), 1, null);
		assertEquals(50L, c.deltaGp());
		assertNull(c.pct());
	}

	// ---- sinceDay

	@Test
	public void sinceDayComparesNowWithTheLastReadingOnOrBeforeTheWindowsDay()
	{
		final BankHistoryMath.Change c = BankHistoryMath.sinceDay(fourReadings(), sep(12), 7, null, 260L);
		assertEquals(sep(5), c.fromDay());
		assertEquals(sep(12), c.toDay());
		assertEquals("from 150 to now's 260", new BankHistoryMath.Change(sep(5), sep(12), 150L, 260L), c);
		assertEquals(110L, c.deltaGp());
		assertEquals(7, c.spanDays());
	}

	@Test
	public void sinceDaySpansTheGapWhenTheReadingUsedIsOlder()
	{
		// 1d on the 8th wants the 7th; the 5th is the reading used - "(3 days)" on the card.
		final BankHistoryMath.Change c = BankHistoryMath.sinceDay(fourReadings(), sep(8), 1, null, 170L);
		assertEquals(sep(5), c.fromDay());
		assertEquals(3, c.spanDays());
		assertTrue("older than the window's day", c.fromDay().isBefore(sep(8).minusDays(1)));
	}

	@Test
	public void sinceDayIsNullExactlyWhenTheChipIsGreyed()
	{
		final BankHistorySeries s = fourReadings();
		for (final MovementWindow window : MovementWindow.values())
		{
			for (int d = 1; d <= 40; d++)
			{
				final LocalDate today = sep(1).plusDays(d - 1);
				final LocalDate fills = BankHistoryMath.fillsOn(s.upTo(today), window.days());
				final boolean greyed = fills == null || fills.isAfter(today);
				assertEquals(window + " on " + today, greyed,
					BankHistoryMath.sinceDay(s, today, window.days(), null, 1L) == null);
			}
		}
		assertNull(BankHistoryMath.sinceDay(BankHistorySeries.EMPTY, sep(10), 1, null, 5L));
		assertNull(BankHistoryMath.sinceDay(null, sep(10), 1, null, 5L));
	}

	@Test
	public void sinceDayIgnoresReadingsAfterToday()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 100), p(sep(20), 20, 999)));
		final BankHistoryMath.Change c = BankHistoryMath.sinceDay(s, sep(10), 0, null, 120L);
		assertEquals(sep(1), c.fromDay());
		assertNull("a future reading is never old enough", BankHistoryMath.sinceDay(
			BankHistorySeries.EMPTY.with(p(sep(20), 20, 1)), sep(10), 0, null, 1L));
	}

	@Test
	public void whenTodayHasAReadingSinceDayEqualsOverDaysToTheGp()
	{
		final BankHistorySeries s = fourReadings();
		for (final int days : new int[]{1, 5, 7, 9})
		{
			final BankHistoryMath.Change over = BankHistoryMath.overDays(s, sep(10), days, null);
			final BankHistoryMath.Change since = BankHistoryMath.sinceDay(s, sep(10), days, null,
				s.on(sep(10)).valueFor(null));
			assertEquals("days " + days, over, since);
			assertEquals(over.deltaGp(), since.deltaGp());
			assertEquals(over.spanDays(), since.spanDays());
		}
	}

	// ---- vsPrevious

	@Test
	public void vsPreviousComparesADaysReadingWithTheOneBeforeItAcrossAGap()
	{
		final BankHistorySeries s = fourReadings();
		final BankHistoryMath.Change c = BankHistoryMath.vsPrevious(s, sep(5), null);
		assertEquals(sep(2), c.fromDay());
		assertEquals(sep(5), c.toDay());
		assertEquals(40L, c.deltaGp());
		assertEquals("vs 02 Sep, 3 days", 3, c.spanDays());
		assertEquals(1, BankHistoryMath.vsPrevious(s, sep(2), null).spanDays());
		assertNull("the first reading ever", BankHistoryMath.vsPrevious(s, sep(1), null));
		assertNull("a day with no reading", BankHistoryMath.vsPrevious(s, sep(4), null));
		assertNull(BankHistoryMath.vsPrevious(null, sep(5), null));
		assertNull(BankHistoryMath.vsPrevious(s, null, null));
	}

	// ---- days

	@Test
	public void daysCarriesTheLastReadingForwardAcrossEveryGap()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 100), p(sep(3), 3, 300)));
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(5), null);
		assertEquals(5, days.size());
		final long[] values = {100, 100, 300, 300, 300};
		final boolean[] carried = {false, true, false, true, true};
		for (int i = 0; i < 5; i++)
		{
			final BankHistoryMath.Day day = days.get(i);
			assertEquals(sep(1 + i), day.day());
			assertEquals(values[i], day.valueGp());
			assertEquals(carried[i], day.carried());
			assertEquals("a day is its reading, or none", new BankHistoryMath.Day(sep(1 + i), values[i],
				carried[i] ? null : s.on(sep(1 + i))), day);
		}
	}

	@Test
	public void daysStartAtTheFirstReadingOrInsideAGap()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(5), 5, 100), p(sep(9), 9, 900)));
		final List<BankHistoryMath.Day> fromBefore = BankHistoryMath.days(s, sep(1), sep(9), null);
		assertEquals("never a day before the first reading", sep(5), fromBefore.get(0).day());
		assertEquals(5, fromBefore.size());

		final List<BankHistoryMath.Day> fromInside = BankHistoryMath.days(s, sep(7), sep(9), null);
		assertEquals(3, fromInside.size());
		assertTrue("a range starting in a gap starts carried", fromInside.get(0).carried());
		assertEquals(100L, fromInside.get(0).valueGp());
		assertEquals(900L, fromInside.get(2).valueGp());
	}

	@Test
	public void daysIgnoreReadingsAfterTheLastDayAndAnswerEmptyWhenThereIsNothing()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 100), p(sep(3), 3, 300)));
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(2), null);
		assertEquals(2, days.size());
		assertEquals(100L, days.get(1).valueGp());
		assertTrue(days.get(1).carried());

		assertTrue(BankHistoryMath.days(s, null, LocalDate.of(2026, 8, 31), null).isEmpty());
		assertTrue(BankHistoryMath.days(BankHistorySeries.EMPTY, null, sep(3), null).isEmpty());
		assertTrue(BankHistoryMath.days(null, null, sep(3), null).isEmpty());
		assertTrue(BankHistoryMath.days(s, sep(4), sep(3), null).isEmpty());
		try
		{
			days.add(days.get(0));
			fail("the list was modifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	@Test
	public void daysFollowTheSwitches()
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = 100L;
		card[BankHistoryPoint.CARRIED_TRADEABLE] = 5L;
		final BankHistorySeries s = BankHistorySeries.EMPTY.with(new BankHistoryPoint(sep(1), 1, 1, card, null));
		assertEquals(105L, BankHistoryMath.days(s, null, sep(2), null).get(1).valueGp());
		assertEquals(100L, BankHistoryMath.days(s, null, sep(2), ViewOptions.DEFAULT.withCountInventory(false))
			.get(1).valueGp());
	}

	@Test
	public void oneReadingIsOneDayAndZeroReadingsNone()
	{
		final BankHistorySeries one = BankHistorySeries.EMPTY.with(p(sep(28), 1, 735));
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(one, sep(28).minusDays(7), sep(28), null);
		assertEquals(1, days.size());
		assertFalse(days.get(0).carried());
		assertEquals(735L, days.get(0).valueGp());
		assertEquals(sep(28), BankHistoryMath.fillsOn(one, 0));
	}

	// ---- thin

	@Test
	public void thinLeavesAShortListAlone()
	{
		final List<BankHistoryMath.Day> days = daily(120, 1);
		assertSame(days, BankHistoryMath.thin(days, 120));
		assertSame(days, BankHistoryMath.thin(days, 500));
		assertTrue(BankHistoryMath.thin(null, 120).isEmpty());
		assertTrue(BankHistoryMath.thin(Collections.<BankHistoryMath.Day>emptyList(), 120).isEmpty());
	}

	@Test
	public void thinAbove120PointsBucketsFromTheLastDay()
	{
		final List<BankHistoryMath.Day> days = daily(121, 1);
		final List<BankHistoryMath.Day> thin = BankHistoryMath.thin(days, 120);
		// ceil(121 / 120) = 2 days a bucket, counted back from the last day: 60 whole pairs and day 0 alone.
		assertEquals(61, thin.size());
		assertSame("the newest bucket ends on the last day", days.get(120), thin.get(60));
		assertSame(days.get(118), thin.get(59));
		assertSame("the oldest bucket is the short one", days.get(0), thin.get(0));
	}

	@Test
	public void aBucketIsDrawnAsItsLastReadingOrItsLastDayWhenAllCarried()
	{
		// Readings every 3rd day from day 0; 121 days thin into pairs, so a pair holds one reading or none.
		final List<BankHistoryMath.Day> days = daily(121, 3);
		final List<BankHistoryMath.Day> thin = BankHistoryMath.thin(days, 120);
		for (int b = 0; b < thin.size(); b++)
		{
			final int end = 120 - 2 * (thin.size() - 1 - b);
			final int start = Math.max(0, end - 1);
			BankHistoryMath.Day expected = days.get(end);
			for (int i = end; i >= start; i--)
			{
				if (!days.get(i).carried())
				{
					expected = days.get(i);
					break;
				}
			}
			assertSame("bucket " + start + ".." + end, expected, thin.get(b));
		}
		// Day 120 is a reading (120 % 3 == 0) and closes the newest pair; day 119 is carried and loses to it.
		assertFalse(thin.get(thin.size() - 1).carried());
		// Pair 115..116: 115 is carried, 116 carried -> the last day, carried.
		assertTrue(thin.contains(days.get(116)));
		assertTrue(days.get(116).carried());
	}

	@Test
	public void past120WeeksTheBucketsGrowAndStayUnder120Points()
	{
		for (final int n : new int[]{841, 1000, 1826})
		{
			final List<BankHistoryMath.Day> days = daily(n, 1);
			final List<BankHistoryMath.Day> thin = BankHistoryMath.thin(days, 120);
			final int bucket = (n + 119) / 120;
			assertTrue(n + " days thin to " + thin.size(), thin.size() <= 120);
			assertEquals((n + bucket - 1) / bucket, thin.size());
			assertSame(days.get(n - 1), thin.get(thin.size() - 1));
			for (int i = 1; i < thin.size(); i++)
			{
				assertTrue(thin.get(i - 1).day().isBefore(thin.get(i).day()));
			}
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void thinRefusesFewerThanOnePoint()
	{
		BankHistoryMath.thin(daily(3, 1), 0);
	}

	@Test
	public void thinOverRealDaysOfASeriesWithGaps()
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		final LocalDate first = LocalDate.of(2024, 1, 1);
		for (int d = 0; d <= 1000; d += 2)
		{
			points.add(p(first.plusDays(d), d, d));
		}
		final BankHistorySeries s = BankHistorySeries.of(points);
		final LocalDate today = first.plusDays(1000);
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, today, null);
		assertEquals(1001, days.size());
		final List<BankHistoryMath.Day> thin = BankHistoryMath.thin(days, 120);
		assertTrue(thin.size() <= 120);
		assertEquals("the last day is today, a reading", today, thin.get(thin.size() - 1).day());
		for (final BankHistoryMath.Day day : thin)
		{
			assertFalse("with a reading every other day and 9-day buckets, no bucket is all carried", day.carried());
		}
	}

	// ---- fillsOn

	@Test
	public void fillsOnIsTheFirstReadingPlusTheWindow()
	{
		assertEquals(sep(1).plusDays(30), BankHistoryMath.fillsOn(fourReadings(), 30));
		assertEquals(sep(2), BankHistoryMath.fillsOn(fourReadings(), 1));
		assertNull(BankHistoryMath.fillsOn(BankHistorySeries.EMPTY, 30));
		assertNull(BankHistoryMath.fillsOn(null, 30));
	}

	// ---- the value types

	@Test
	public void aChangeAndADayHaveValueEquality()
	{
		final BankHistoryMath.Change a = new BankHistoryMath.Change(sep(1), sep(3), 10L, 20L);
		assertEquals(a, new BankHistoryMath.Change(sep(1), sep(3), 10L, 20L));
		assertEquals(a.hashCode(), new BankHistoryMath.Change(sep(1), sep(3), 10L, 20L).hashCode());
		assertFalse(a.equals(new BankHistoryMath.Change(sep(1), sep(3), 10L, 21L)));
		assertEquals(2, a.spanDays());
		assertEquals(10L, a.deltaGp());
		assertEquals(100.0d, a.pct(), 0.0d);
		assertEquals(-100.0d, new BankHistoryMath.Change(sep(1), sep(3), 10L, 0L).pct(), 0.0d);
		final BankHistoryMath.Day d = new BankHistoryMath.Day(sep(1), 5L, null);
		assertEquals(d, new BankHistoryMath.Day(sep(1), 5L, null));
		assertTrue(d.carried());
		assertFalse(d.toString().isEmpty());
		assertFalse(a.toString().isEmpty());
	}

	/** {@code n} consecutive days from 1 Jan 2025, a reading on every {@code every}-th (day 0 always). */
	private static List<BankHistoryMath.Day> daily(final int n, final int every)
	{
		final List<BankHistoryMath.Day> days = new ArrayList<>(n);
		final LocalDate first = LocalDate.of(2025, 1, 1);
		for (int i = 0; i < n; i++)
		{
			final LocalDate day = first.plusDays(i);
			days.add(new BankHistoryMath.Day(day, i, i % every == 0 ? p(day, i, i) : null));
		}
		return days;
	}
}
