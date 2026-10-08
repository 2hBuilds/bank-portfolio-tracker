package com.bankpricemovement;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The poll memory of contract 1.2.0, line L1: every DISTINCT print of each side of each item that this session's
 * {@code /latest} polls have seen in the last 24 hours, as a price and the second it traded. A poll drops what is more
 * than {@value GradeMath#LATEST_PRINT_MAX_AGE_SECONDS} seconds older than itself and a print that old is never kept, so
 * whatever is here is from the last 24 hours - "today" is a rolling day and not the UTC calendar day (the user,
 * 2026-10-07), and nothing is cleared at 00:00 UTC. In memory only: nothing of it is ever written to disk.
 *
 * <p>It is what turns the thirty-minute {@code /latest} polls into a side's OBSERVATIONS of the last 24 hours, whose
 * median is that side's "now" ({@link GradeMath#facts}): a liquid item collects a print a poll, a thin one keeps the one
 * it had.
 *
 * <p>Immutable, so the service can hand the one it holds to a computation by reference: {@link #record} answers a new
 * memory, sharing every item's prints that the poll did not change. A side keeps at most
 * {@value GradeMath#POLL_MEMORY_MAX_PRINTS} prints, the oldest dropped first.
 */
final class PollMemory
{
	/** Nothing seen yet. */
	static final PollMemory EMPTY = new PollMemory(0L, Collections.<Integer, long[][]>emptyMap());

	private static final long[] NONE = new long[0];

	/** When the newest poll held was made, epoch millis; 0 for {@link #EMPTY}. */
	private final long polledAtMillis;
	/** Item id to {buy prints, sell prints}, each as {@code price, seconds} pairs, oldest first. */
	private final Map<Integer, long[][]> prints;

	private PollMemory(final long polledAtMillis, final Map<Integer, long[][]> prints)
	{
		this.polledAtMillis = polledAtMillis;
		this.prints = prints;
	}

	/**
	 * This memory with one more poll in it: every held print more than a day older than the poll is dropped, and every
	 * side's print of the poll that is at most a day old and not already held is added. A poll stamped before the newest
	 * one held (the clock corrected backwards) changes nothing - its age arithmetic would run the wrong way.
	 *
	 * @param fetchedAtMillis when the poll was made, epoch millis - the "now" its prints' ages are counted from
	 * @param quotes          the poll's quotes, item id to the newest buy and sell print
	 */
	PollMemory record(final long fetchedAtMillis, @Nullable final Map<Integer, TradedPriceClient.Quote> quotes)
	{
		if (fetchedAtMillis <= 0L || quotes == null || fetchedAtMillis < polledAtMillis)
		{
			return this;
		}
		final long oldest = fetchedAtMillis / 1000L - GradeMath.LATEST_PRINT_MAX_AGE_SECONDS;
		final Map<Integer, long[][]> next = new HashMap<>(Math.max(16, prints.size() + quotes.size() / 4));
		for (final Map.Entry<Integer, long[][]> held : prints.entrySet())
		{
			final long[] buys = since(held.getValue()[0], oldest);
			final long[] sells = since(held.getValue()[1], oldest);
			if (buys.length > 0 || sells.length > 0)
			{
				next.put(held.getKey(), buys == held.getValue()[0] && sells == held.getValue()[1] ? held.getValue()
					: new long[][]{buys, sells});
			}
		}
		for (final Map.Entry<Integer, TradedPriceClient.Quote> entry : quotes.entrySet())
		{
			final TradedPriceClient.Quote quote = entry.getValue();
			if (entry.getKey() == null || quote == null)
			{
				continue;
			}
			final long[][] was = next.get(entry.getKey());
			final long[] buys = with(was == null ? NONE : was[0], quote.buy(), quote.buySeconds(), oldest);
			final long[] sells = with(was == null ? NONE : was[1], quote.sell(), quote.sellSeconds(), oldest);
			if (was == null ? buys.length > 0 || sells.length > 0 : buys != was[0] || sells != was[1])
			{
				next.put(entry.getKey(), new long[][]{buys, sells});
			}
		}
		return new PollMemory(fetchedAtMillis, Collections.unmodifiableMap(next));
	}

	/**
	 * One side's prints of one item, as {@code price, seconds} pairs, oldest first; an empty array when there are none.
	 * The array is this memory's own and must not be changed.
	 *
	 * @param buySide true for the instant-buy prints, false for the instant-sell ones
	 */
	long[] prints(final int id, final boolean buySide)
	{
		final long[][] both = prints.get(id);
		return both == null ? NONE : both[buySide ? 0 : 1];
	}

	/** {@code side} without the prints traded before {@code oldest} (unix seconds); {@code side} itself when none is. */
	private static long[] since(final long[] side, final long oldest)
	{
		int kept = 0;
		for (int i = 0; i + 1 < side.length; i += 2)
		{
			if (side[i + 1] >= oldest)
			{
				kept++;
			}
		}
		if (kept * 2 == side.length)
		{
			return side;
		}
		final long[] trimmed = new long[2 * kept];
		int at = 0;
		for (int i = 0; i + 1 < side.length; i += 2)
		{
			if (side[i + 1] >= oldest)
			{
				trimmed[at++] = side[i];
				trimmed[at++] = side[i + 1];
			}
		}
		return trimmed;
	}

	/**
	 * {@code side} with one more print, or {@code side} itself when the print is absent, older than {@code oldest}, or
	 * already held.
	 */
	private static long[] with(final long[] side, @Nullable final Long price, final long seconds, final long oldest)
	{
		if (price == null || price <= 0L || seconds <= 0L || seconds < oldest)
		{
			return side;
		}
		for (int i = 0; i + 1 < side.length; i += 2)
		{
			if (side[i] == price && side[i + 1] == seconds)
			{
				return side;
			}
		}
		final int keep = Math.min(side.length / 2, GradeMath.POLL_MEMORY_MAX_PRINTS - 1);
		final long[] grown = new long[2 * keep + 2];
		System.arraycopy(side, side.length - 2 * keep, grown, 0, 2 * keep);
		grown[2 * keep] = price;
		grown[2 * keep + 1] = seconds;
		return grown;
	}

	@Override
	public String toString()
	{
		return "PollMemory{polledAt=" + polledAtMillis + ", items=" + prints.size() + '}';
	}

	/** For a debug line: how many prints the memory holds in all. */
	int size()
	{
		int n = 0;
		for (final long[][] both : prints.values())
		{
			n += both[0].length / 2 + both[1].length / 2;
		}
		return n;
	}
}
