package com.bankpricemovement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * Addendum AU, amendment 9.12: the four semantics of {@link BankHistoryView#describe()} the panel relies on across the
 * seam - {@code range}, {@code readings}, {@code first}, {@code last} - which the view must keep whatever it draws; plus
 * the seven keys and their types (amendment 9.11). {@code SidebarHistorySeamTest} drives the same view through the real
 * panel.
 */
public class BankHistoryViewSeamTest
{
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	/** Local noon of a September day in Toronto, as amendment 9.1 tells the panel's tests to build stamps. */
	private static long noon(final int day)
	{
		return LocalDate.of(2026, 9, day).atTime(12, 0).atZone(TORONTO).toInstant().toEpochMilli();
	}

	private static BankHistorySeries series(final int... days)
	{
		final BankHistoryPoint[] points = new BankHistoryPoint[days.length];
		for (int i = 0; i < days.length; i++)
		{
			points[i] = BankHistorySeriesTest.p(BankHistorySeriesTest.sep(days[i]), noon(days[i]), 1_000L * days[i]);
		}
		return BankHistorySeries.of(Arrays.asList(points));
	}

	@Test
	public void theRangeStartsAtTheDefaultWindowsAndFollowsSetRange()
	{
		final BankHistoryView view = new BankHistoryView(() -> noon(20), TORONTO);
		assertEquals("7d", view.describe().get("range"));

		view.setRange(BankHistoryRange.D90);
		assertEquals("90d", view.describe().get("range"));
		view.setRange(BankHistoryRange.ALL);
		assertEquals("all", view.describe().get("range"));
		view.setRange(null);
		assertEquals("null is ignored", "all", view.describe().get("range"));
	}

	@Test
	public void readingsFirstAndLastComeFromTheLastShowCutToToday()
	{
		final AtomicLong clock = new AtomicLong(noon(20));
		final BankHistoryView view = new BankHistoryView(clock::get, TORONTO);
		assertEquals(0, view.describe().get("readings"));
		assertNull(view.describe().get("first"));
		assertNull(view.describe().get("last"));

		view.show(series(3, 9, 20, 22), ViewOptions.DEFAULT);

		assertEquals("the 22nd is after today and is not drawn", 3, view.describe().get("readings"));
		assertEquals("2026-09-03", view.describe().get("first"));
		assertEquals("2026-09-20", view.describe().get("last"));

		clock.set(noon(22));
		view.show(series(3, 9, 20, 22), null);
		assertEquals(4, view.describe().get("readings"));
		assertEquals("2026-09-22", view.describe().get("last"));

		view.show(null, null);
		assertEquals("null reads as EMPTY", 0, view.describe().get("readings"));
		assertNull(view.describe().get("first"));
	}

	@Test
	public void todayIsTheClocksLocalDate()
	{
		// 21 Sep 01:00 UTC is still 20 Sep in Toronto.
		final long lateEvening = LocalDate.of(2026, 9, 21).atTime(1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		final BankHistoryView view = new BankHistoryView(() -> lateEvening, TORONTO);
		view.show(series(20, 21), ViewOptions.DEFAULT);
		assertEquals(1, view.describe().get("readings"));
		assertEquals("2026-09-20", view.describe().get("last"));
	}

	@Test
	public void describeHasExactlyTheSevenKeysInOrderWithStringIntegerAndNullValues()
	{
		final BankHistoryView view = new BankHistoryView(() -> noon(20), TORONTO);
		view.show(series(19, 20), ViewOptions.DEFAULT);
		final LinkedHashMap<String, Object> map = view.describe();

		assertEquals(Arrays.asList("readings", "first", "last", "range", "rows", "carriedRows", "hoverDay"),
			Arrays.asList(map.keySet().toArray()));
		assertEquals(Integer.valueOf(2), map.get("readings"));
		assertEquals("2026-09-19", map.get("first"));
		// The rows built: the 19th and the 20th, neither carried.
		assertEquals(Integer.valueOf(2), map.get("rows"));
		assertEquals(Integer.valueOf(0), map.get("carriedRows"));
		assertNull(map.get("hoverDay"));
		assertFalse("nothing more to page", view.showMore());
	}
}
