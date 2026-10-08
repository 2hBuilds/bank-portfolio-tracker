package com.bankpricemovement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import okhttp3.OkHttpClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The new inputs of contract 1.2.0, line L1, on their own: the poll memory ({@link PollMemory}), the {@code /1h} body
 * ({@link TradedPriceClient#parseHour}), and the two new files - {@code traded-H1.json} and {@code traded-D2.json} -
 * through the store's own Filepath roads. Synthetic item ids only.
 */
public class GradedFeedsTest
{
	private static final int ITEM = 90_001;
	private static final int OTHER = 90_002;
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	// ---------------------------------------------------------------- the poll memory

	/** Two polls that saw the same last trade hold it once; a new trade on either side is one more print. */
	@Test
	public void thePollMemoryKeepsEachDistinctPrintOnce()
	{
		PollMemory memory = PollMemory.EMPTY.record(millis(10, 0), quotes(1_030L, at(9, 50), 1_010L, at(9, 40)));
		memory = memory.record(millis(10, 30), quotes(1_030L, at(9, 50), 1_010L, at(9, 40)));
		assertArrayEquals("the same print, seen twice", new long[]{1_030L, at(9, 50)}, memory.prints(ITEM, true));

		memory = memory.record(millis(11, 0), quotes(1_040L, at(10, 55), 1_010L, at(9, 40)));
		assertArrayEquals(new long[]{1_030L, at(9, 50), 1_040L, at(10, 55)}, memory.prints(ITEM, true));
		assertArrayEquals("the sell side did not trade again", new long[]{1_010L, at(9, 40)}, memory.prints(ITEM, false));
		assertEquals(3, memory.size());
		assertEquals("an item never polled has no prints", 0, memory.prints(OTHER, true).length);
	}

	/**
	 * "Today" is the last 24 hours (the user, 2026-10-07), so nothing is cleared at 00:00 UTC: the prints of the evening
	 * before are still in the memory at 00:20 - the buy print of 23:45 and the sell print of 23:40 - and the sell side
	 * has the new print of 00:05 beside its old one.
	 */
	@Test
	public void thePollMemoryKeepsTheEveningBeforeAcrossTheUtcMidnight()
	{
		final LocalDate yesterday = TODAY.minusDays(1);
		final PollMemory evening = PollMemory.EMPTY.record(millis(yesterday, 23, 50),
			quotes(1_030L, seconds(yesterday, 23, 45), 1_010L, seconds(yesterday, 23, 40)));
		assertEquals(2, evening.size());

		final PollMemory afterMidnight = evening.record(millis(0, 20),
			quotes(1_030L, seconds(yesterday, 23, 45), 1_015L, at(0, 5)));
		assertArrayEquals("the buy print of last night, once", new long[]{1_030L, seconds(yesterday, 23, 45)},
			afterMidnight.prints(ITEM, true));
		assertArrayEquals("the sell side holds last night's print and the new one",
			new long[]{1_010L, seconds(yesterday, 23, 40), 1_015L, at(0, 5)}, afterMidnight.prints(ITEM, false));
		assertSame("a poll stamped before the newest one held changes nothing", afterMidnight,
			afterMidnight.record(millis(yesterday, 23, 59), quotes(999L, at(0, 1), 999L, at(0, 1))));
	}

	/**
	 * The edge of the day is the print's age at the poll: 23 h 59 min old is kept and 24 h 1 s is not - whether it is
	 * brought by the poll or already held and aged out by a later one - across a UTC midnight either way, and exactly 24 h
	 * still counts (the same "at most 24 h" GradeMath reads).
	 */
	@Test
	public void thePollMemoryKeepsAPrintFor24HoursAndNotLonger()
	{
		final long noon = at(12, 0);
		final long day = 24L * 60L * 60L;

		// Brought by the poll: the poll is at today 12:00 and the prints are of the day before.
		final PollMemory arriving = PollMemory.EMPTY.record(noon * 1_000L, quotes(1_030L, noon - day + 60L, 1_010L,
			noon - day - 1L));
		assertArrayEquals("23 h 59 min old counts", new long[]{1_030L, noon - day + 60L}, arriving.prints(ITEM, true));
		assertEquals("24 h and 1 s old does not", 0, arriving.prints(ITEM, false).length);
		final PollMemory exactly = PollMemory.EMPTY.record(noon * 1_000L, quotes(1_030L, noon - day, null, 0L));
		assertArrayEquals("exactly 24 h still counts", new long[]{1_030L, noon - day}, exactly.prints(ITEM, true));

		// Already held, then aged by a later poll: both prints were an hour or so old at the first poll (yesterday 12:30 and
		// 13:30 against a poll at 14:00); at the second poll, today 13:00, the first is 24 h 30 min old and the second
		// 23 h 30 min - the first is dropped, the second kept, across the UTC midnight.
		final LocalDate yesterday = TODAY.minusDays(1);
		final PollMemory held = PollMemory.EMPTY.record(millis(yesterday, 14, 0),
			quotes(1_030L, seconds(yesterday, 12, 30), 1_010L, seconds(yesterday, 13, 30)));
		assertEquals(2, held.size());
		final PollMemory aged = held.record(millis(13, 0), Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		assertEquals("the buy print of 12:30 is 24 h 30 min old now and gone", 0, aged.prints(ITEM, true).length);
		assertArrayEquals("the sell print of 13:30 is 23 h 30 min old and stays", new long[]{1_010L, seconds(yesterday, 13, 30)},
			aged.prints(ITEM, false));
		assertEquals(1, aged.size());

		// The same edge for a print already held: exactly 24 h old at a later poll still counts, a second more does not.
		final long printed = at(10, 0);
		final PollMemory early = PollMemory.EMPTY.record((printed + 600L) * 1_000L, quotes(1_030L, printed, null, 0L));
		final Map<Integer, TradedPriceClient.Quote> nothingNew = Collections.<Integer, TradedPriceClient.Quote>emptyMap();
		assertArrayEquals("24 h to the second", new long[]{1_030L, printed},
			early.record((printed + day) * 1_000L, nothingNew).prints(ITEM, true));
		assertEquals("a second more", 0, early.record((printed + day + 1L) * 1_000L, nothingNew).prints(ITEM, true).length);

		// Once nothing of an item is left the item is gone: a poll that is far enough on empties the memory.
		final PollMemory gone = aged.record(millis(TODAY.plusDays(1), 12, 0),
			Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		assertEquals(0, gone.size());
		assertEquals(0, gone.prints(ITEM, false).length);
	}

	/** A side keeps at most a day of the tick's prints, the oldest dropped first. */
	@Test
	public void thePollMemoryKeepsAtMostADayOfPrintsASide()
	{
		PollMemory memory = PollMemory.EMPTY;
		for (int i = 0; i < GradeMath.POLL_MEMORY_MAX_PRINTS + 5; i++)
		{
			memory = memory.record(millis(12, 0), quotes(1_000L + i, at(0, 0) + i, null, 0L));
		}
		final long[] buys = memory.prints(ITEM, true);
		assertEquals(2 * GradeMath.POLL_MEMORY_MAX_PRINTS, buys.length);
		assertEquals("the oldest went first", 1_005L, buys[0]);
		assertEquals(1_000L + GradeMath.POLL_MEMORY_MAX_PRINTS + 4, buys[buys.length - 2]);
	}

	// ---------------------------------------------------------------- the /1h body

	@Test
	public void anHourBodyIsReadWithItsStart() throws Exception
	{
		final TradedPriceClient client = new TradedPriceClient(mock(OkHttpClient.class), new Gson());
		final PriceStore.TradedHour hour = client.parseHour("{\"data\":{\"" + ITEM + "\":{\"avgHighPrice\":1026,"
			+ "\"highPriceVolume\":100,\"avgLowPrice\":1004,\"lowPriceVolume\":96}},\"timestamp\":" + at(12, 0) + "}", 7L);

		assertEquals(at(12, 0), hour.startSeconds());
		assertEquals(7L, hour.fetchedAtMillis());
		assertEquals(Long.valueOf(1_026L), hour.get(ITEM).avgHigh());
		assertEquals(96L, hour.get(ITEM).lowVolume());
		assertEquals("https://prices.runescape.wiki/api/v1/osrs/1h?timestamp=" + at(12, 0), TradedPriceClient.hourUrl(at(12, 0)));
		try
		{
			client.parseHour("{\"data\":{\"" + ITEM + "\":{\"avgHighPrice\":1026,\"highPriceVolume\":100}}}", 7L);
			fail("an hour that names no start is not an hour");
		}
		catch (final WikiPriceException expected)
		{
			// the point of the test
		}
	}

	// ---------------------------------------------------------------- traded-H1.json and traded-D2.json

	@Test
	public void theHourRoundTripsThroughTradedH1() throws IOException
	{
		final PriceStore store = store();
		final PriceStore.TradedHour hour = new PriceStore.TradedHour(at(12, 0), buckets(), 123L);

		store.saveTradedHour(hour);
		final PriceStore.TradedHour read = store.loadTradedHour();

		assertEquals("traded-H1.json", store.tradedHourFile().getFileName());
		assertEquals(at(12, 0), read.startSeconds());
		assertEquals(123L, read.fetchedAtMillis());
		assertEquals(hour.buckets(), read.buckets());
		assertSame("nothing stored reads as EMPTY", PriceStore.TradedHour.EMPTY, new PriceStore(new Gson(),
			TestFilepaths.rooted(tmp.newFolder("elsewhere"))).loadTradedHour());
	}

	@Test
	public void theDayBeforeYesterdayRoundTripsThroughTradedD2()
	{
		final PriceStore store = store();
		store.saveTradedD2(new PriceStore.TradedDay(TODAY.minusDays(2), buckets(), 456L));

		final PriceStore.TradedDay read = store.loadTradedD2();

		assertEquals("traded-D2.json", store.tradedD2File().getFileName());
		assertEquals(TODAY.minusDays(2), read.day());
		assertEquals(buckets(), read.buckets());
		assertEquals(456L, read.fetchedAtMillis());
	}

	/** Neither new file is a window, and the sweep's rule 5 must not take either for a window this build dropped. */
	@Test
	public void theSweepKeepsTheHourAndTheDayBeforeYesterday()
	{
		final PriceStore store = store();
		store.saveTradedHour(new PriceStore.TradedHour(at(12, 0), buckets(), 1L));
		store.saveTradedD2(new PriceStore.TradedDay(TODAY.minusDays(2), buckets(), 1L));

		assertEquals(0, store.deleteStaleFiles());

		assertTrue(store.tradedHourFile().isFile());
		assertTrue(store.tradedD2File().isFile());
	}

	@Test
	public void anEmptyHourIsNotWritten()
	{
		final PriceStore store = store();
		store.saveTradedHour(PriceStore.TradedHour.EMPTY);
		store.saveTradedHour(null);

		assertSame(PriceStore.TradedHour.EMPTY, store.loadTradedHour());
		assertNull(store.loadTradedD2().day());
	}

	// ---------------------------------------------------------------- fixtures

	private PriceStore store()
	{
		return new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
	}

	private static Map<Integer, TradedPriceClient.Bucket> buckets()
	{
		final Map<Integer, TradedPriceClient.Bucket> buckets = new LinkedHashMap<>();
		buckets.put(ITEM, new TradedPriceClient.Bucket(1_026L, 100L, 1_004L, 96L));
		buckets.put(OTHER, new TradedPriceClient.Bucket(null, 0L, 50L, 3L));
		return buckets;
	}

	/** One item's quote: a buy print and a sell print with their times; a null side has none. */
	private static Map<Integer, TradedPriceClient.Quote> quotes(final Long buy, final long buyAt, final Long sell,
		final long sellAt)
	{
		return Collections.singletonMap(ITEM, new TradedPriceClient.Quote(buy, buy == null ? 0L : buyAt, sell,
			sell == null ? 0L : sellAt));
	}

	private static long at(final int hour, final int minute)
	{
		return seconds(TODAY, hour, minute);
	}

	private static long seconds(final LocalDate day, final int hour, final int minute)
	{
		return day.atTime(hour, minute).toEpochSecond(ZoneOffset.UTC);
	}

	private static long millis(final int hour, final int minute)
	{
		return millis(TODAY, hour, minute);
	}

	private static long millis(final LocalDate day, final int hour, final int minute)
	{
		return seconds(day, hour, minute) * 1_000L;
	}
}
