package com.bankpricemovement;

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * What the price fetches have done this session (1.0.8): one {@link Record} per source, filled PASSIVELY by
 * {@link GuidePriceClient} and {@link TradedPriceClient} on every real fetch, so the Troubleshoot report can say
 * what happened without sending a new request. It also keeps the wiki's own clock - the {@code Date} header of the
 * newest response - so the report can say how far this computer's clock is from it.
 *
 * <p>Sources are named by the constants below; a source never fetched has no record. Only counts, times and
 * error text are kept: never a URL, an item id or a quantity. Thread-safe: OkHttp's dispatcher threads write, the
 * EDT reads, and each method is one short critical section.
 */
public final class FetchLog
{
	/** The wiki's id-to-name table. */
	public static final String MAPPING = "mapping";
	/** The guide-table revision index. */
	public static final String PRICE_INDEX = "price index";
	/** The batched guide tables - one request for every window, so one source. */
	public static final String GUIDE_TABLES = "guide tables";
	/** The traded {@code /latest} snapshot. */
	public static final String LIVE_LATEST = "live latest";
	/** A traded day bucket, whichever day was asked for last. */
	public static final String LIVE_BUCKETS = "live day buckets";

	/** Every source, in the order the report prints them. */
	public static final String[] SOURCES = {MAPPING, PRICE_INDEX, GUIDE_TABLES, LIVE_LATEST, LIVE_BUCKETS};

	/** The last error text kept is cut to this many characters. */
	private static final int MAX_ERROR_CHARS = 160;

	/** One source's last attempt and its run of failures, immutable. */
	public static final class Record
	{
		public final long attemptMillis;
		public final boolean ok;
		/** 0 when no response came (a refused connection, a timeout, a cancelled call). */
		public final int httpStatus;
		public final long millis;
		/** The decoded body's size; 0 when none was read. */
		public final long bytes;
		/** What the parser made of it (entries in the table); 0 on a failure. */
		public final int items;
		/** Failures since the last success, this one included; 0 after a success. */
		public final int consecutiveFailures;
		/** The failure's text, or null after a success. */
		@Nullable
		public final String lastError;

		Record(final long attemptMillis, final boolean ok, final int httpStatus, final long millis, final long bytes,
			final int items, final int consecutiveFailures, @Nullable final String lastError)
		{
			this.attemptMillis = attemptMillis;
			this.ok = ok;
			this.httpStatus = httpStatus;
			this.millis = millis;
			this.bytes = bytes;
			this.items = items;
			this.consecutiveFailures = consecutiveFailures;
			this.lastError = lastError;
		}
	}

	private final LongSupplier clock;
	private final Object lock = new Object();
	private final Map<String, Record> records = new HashMap<>();
	@Nullable
	private Long skewSeconds;

	/** @param clock wall clock in epoch milliseconds: the stamp of an attempt, and "this computer's" side of the skew */
	public FetchLog(final LongSupplier clock)
	{
		this.clock = clock;
	}

	/** A fetch that parsed. */
	public void ok(final String source, final int httpStatus, final long millis, final long bytes, final int items)
	{
		final long now = clock.getAsLong();
		synchronized (lock)
		{
			records.put(source, new Record(now, true, httpStatus, millis, bytes, items, 0, null));
		}
	}

	/** A fetch that did not: no response, a bad status, or a body that would not parse. */
	public void failed(final String source, final int httpStatus, final long millis, final long bytes,
		@Nullable final String error)
	{
		final long now = clock.getAsLong();
		final String text = error == null ? "" : cut(error);
		synchronized (lock)
		{
			final Record before = records.get(source);
			final int run = before == null ? 1 : before.consecutiveFailures + 1;
			records.put(source, new Record(now, false, httpStatus, millis, bytes, 0, run, text));
		}
	}

	/**
	 * Notes the {@code Date} header of a response as the wiki's clock: this computer's clock minus it, in whole
	 * seconds, replaces the last skew. A missing or unreadable header changes nothing.
	 */
	public void serverDate(@Nullable final String header)
	{
		if (header == null || header.isEmpty())
		{
			return;
		}
		final long theirs;
		try
		{
			theirs = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
		}
		catch (final DateTimeException e)
		{
			return;
		}
		final long skew = Math.round((clock.getAsLong() - theirs) / 1000.0d);
		synchronized (lock)
		{
			skewSeconds = skew;
		}
	}

	/** The last attempt at {@code source}, or null when it was never fetched this session. */
	@Nullable
	public Record last(final String source)
	{
		synchronized (lock)
		{
			return records.get(source);
		}
	}

	/** This computer's clock minus the wiki's, in seconds, or null while no response has carried a date. */
	@Nullable
	public Long clockSkewSeconds()
	{
		synchronized (lock)
		{
			return skewSeconds;
		}
	}

	private static String cut(final String text)
	{
		final String oneLine = text.replace('\r', ' ').replace('\n', ' ');
		return oneLine.length() <= MAX_ERROR_CHARS ? oneLine : oneLine.substring(0, MAX_ERROR_CHARS) + "...";
	}
}
