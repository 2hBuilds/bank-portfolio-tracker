package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** The passive fetch log (1.0.8): one record per source, the run of failures, and the clock skew. */
public class FetchLogTest
{
	/** 2026-09-30T14:05:09Z. */
	private static final long T0 = 1_790_777_109_000L;

	private final AtomicLong clock = new AtomicLong(T0);
	private final FetchLog log = new FetchLog(clock::get);

	@Test
	public void aSourceNeverFetchedHasNoRecord()
	{
		assertNull(log.last(FetchLog.MAPPING));
		assertNull(log.clockSkewSeconds());
		assertEquals(5, FetchLog.SOURCES.length);
	}

	@Test
	public void aSuccessIsRecordedWithEveryFigure()
	{
		log.ok(FetchLog.MAPPING, 200, 131L, 84_213L, 4_566);

		final FetchLog.Record r = log.last(FetchLog.MAPPING);
		assertTrue(r.ok);
		assertEquals(T0, r.attemptMillis);
		assertEquals(200, r.httpStatus);
		assertEquals(131L, r.millis);
		assertEquals(84_213L, r.bytes);
		assertEquals(4_566, r.items);
		assertEquals(0, r.consecutiveFailures);
		assertNull(r.lastError);
	}

	@Test
	public void failuresCountUpInARowAndASuccessResetsThem()
	{
		log.failed(FetchLog.PRICE_INDEX, 503, 40L, 12L, "HTTP 503");
		log.failed(FetchLog.PRICE_INDEX, 0, 8_000L, 0L, "timeout");
		FetchLog.Record r = log.last(FetchLog.PRICE_INDEX);
		assertFalse(r.ok);
		assertEquals(2, r.consecutiveFailures);
		assertEquals("the last error is the latest", "timeout", r.lastError);
		assertEquals(0, r.httpStatus);

		log.ok(FetchLog.PRICE_INDEX, 200, 50L, 3_000L, 250);
		r = log.last(FetchLog.PRICE_INDEX);
		assertTrue(r.ok);
		assertEquals(0, r.consecutiveFailures);
		assertNull(r.lastError);

		log.failed(FetchLog.PRICE_INDEX, 500, 1L, 1L, "again");
		assertEquals("a fresh run starts at one", 1, log.last(FetchLog.PRICE_INDEX).consecutiveFailures);
	}

	@Test
	public void eachSourceKeepsItsOwnRecord()
	{
		log.ok(FetchLog.LIVE_LATEST, 200, 1L, 2L, 3);
		log.failed(FetchLog.LIVE_BUCKETS, 404, 1L, 0L, "gone");

		assertTrue(log.last(FetchLog.LIVE_LATEST).ok);
		assertFalse(log.last(FetchLog.LIVE_BUCKETS).ok);
		assertNull(log.last(FetchLog.GUIDE_TABLES));
	}

	@Test
	public void aLongErrorIsCutAndKeptOnOneLine()
	{
		final StringBuilder longText = new StringBuilder("line one\nline two ");
		for (int i = 0; i < 100; i++)
		{
			longText.append("word ");
		}

		log.failed(FetchLog.MAPPING, 0, 1L, 0L, longText.toString());

		final String kept = log.last(FetchLog.MAPPING).lastError;
		assertFalse(kept.contains("\n"));
		assertTrue(kept.length() <= 163);
		assertTrue(kept.endsWith("..."));
		assertEquals("a null error is an empty one", "", recordAfterNull());
	}

	private String recordAfterNull()
	{
		log.failed(FetchLog.LIVE_LATEST, 0, 1L, 0L, null);
		return log.last(FetchLog.LIVE_LATEST).lastError;
	}

	@Test
	public void theSkewIsThisClockMinusTheServersInWholeSeconds()
	{
		log.serverDate("Wed, 30 Sep 2026 14:05:04 GMT");
		assertEquals(Long.valueOf(5L), log.clockSkewSeconds());

		log.serverDate("Wed, 30 Sep 2026 14:05:12 GMT");
		assertEquals("the newest response wins, and this clock may be behind", Long.valueOf(-3L), log.clockSkewSeconds());
	}

	@Test
	public void aMissingOrUnreadableDateChangesNothing()
	{
		log.serverDate("Wed, 30 Sep 2026 14:05:04 GMT");

		log.serverDate(null);
		log.serverDate("");
		log.serverDate("yesterday at noon");

		assertEquals(Long.valueOf(5L), log.clockSkewSeconds());
	}
}
