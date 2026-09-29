package com.bankpricemovement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * The readings of one account on one profile (addendum AU; phase 0 contract sections 1, 3 and 9.4): immutable,
 * sorted by day, at most ONE reading per day. Days with no reading are not here - they are derived when drawing,
 * by {@link BankHistoryMath#days} and nowhere else.
 *
 * <p><b>One reading per day.</b> Two readings that name one day are resolved by their {@code readAtMillis}: the
 * later one wins, because "the last one of the day stays" (plan 7.5 item 1). On a tie the one given later wins -
 * the element later in {@link #of}'s collection. {@link #with} replaces its day's reading unconditionally.
 *
 * <p>Value equality over {@link #points()}, so a series re-read from disk equals the one in memory when they hold
 * the same readings - which is how the store decides that a write would change nothing, and how the History view
 * decides that there is nothing to redraw.
 */
public final class BankHistorySeries
{
	/** No readings at all. */
	public static final BankHistorySeries EMPTY = new BankHistorySeries(Collections.<BankHistoryPoint>emptyList());

	/** Ascending by day, one per day, unmodifiable. */
	private final List<BankHistoryPoint> points;

	private BankHistorySeries(final List<BankHistoryPoint> sorted)
	{
		this.points = sorted;
	}

	/**
	 * The series of these readings: sorted by day, one per day - the higher {@code readAtMillis} wins, and on a tie
	 * the one later in the collection. Null elements are skipped; a null or empty collection is {@link #EMPTY}.
	 */
	public static BankHistorySeries of(@Nullable final Collection<BankHistoryPoint> points)
	{
		if (points == null || points.isEmpty())
		{
			return EMPTY;
		}
		final TreeMap<LocalDate, BankHistoryPoint> byDay = new TreeMap<>();
		for (final BankHistoryPoint point : points)
		{
			if (point != null)
			{
				byDay.merge(point.day(), point, BankHistorySeries::later);
			}
		}
		return fromMap(byDay);
	}

	/** Of two readings of one day, the one taken later; {@code b} on a tie. */
	private static BankHistoryPoint later(final BankHistoryPoint a, final BankHistoryPoint b)
	{
		return a.readAtMillis() > b.readAtMillis() ? a : b;
	}

	private static BankHistorySeries fromMap(final TreeMap<LocalDate, BankHistoryPoint> byDay)
	{
		if (byDay.isEmpty())
		{
			return EMPTY;
		}
		return new BankHistorySeries(Collections.unmodifiableList(new ArrayList<>(byDay.values())));
	}

	private TreeMap<LocalDate, BankHistoryPoint> toMap()
	{
		final TreeMap<LocalDate, BankHistoryPoint> byDay = new TreeMap<>();
		for (final BankHistoryPoint point : points)
		{
			byDay.put(point.day(), point);
		}
		return byDay;
	}

	/**
	 * This series with {@code point} as its day's reading, replacing whatever was there whatever its time - the
	 * reading just taken is the last one of its day (plan 7.5 item 1). A null point answers this series.
	 */
	public BankHistorySeries with(@Nullable final BankHistoryPoint point)
	{
		if (point == null)
		{
			return this;
		}
		final TreeMap<LocalDate, BankHistoryPoint> byDay = toMap();
		byDay.put(point.day(), point);
		return fromMap(byDay);
	}

	/**
	 * This series without the readings dated after {@code today} - a clock that was wrong when they were taken
	 * (plan 7.1 item 7). They are not drawn, and the store drops them at its next write.
	 */
	public BankHistorySeries upTo(final LocalDate today)
	{
		Objects.requireNonNull(today, "today");
		final BankHistoryPoint last = last();
		if (last == null || !last.day().isAfter(today))
		{
			return this;
		}
		final List<BankHistoryPoint> kept = new ArrayList<>(points.size());
		for (final BankHistoryPoint point : points)
		{
			if (!point.day().isAfter(today))
			{
				kept.add(point);
			}
		}
		return kept.isEmpty() ? EMPTY : new BankHistorySeries(Collections.unmodifiableList(kept));
	}

	/** Every reading, ascending by day; unmodifiable. */
	public List<BankHistoryPoint> points()
	{
		return points;
	}

	public boolean isEmpty()
	{
		return points.isEmpty();
	}

	/** How many readings - days WITH a reading; carried days are not counted. */
	public int size()
	{
		return points.size();
	}

	/** The earliest reading, or null when there is none. */
	@Nullable
	public BankHistoryPoint first()
	{
		return points.isEmpty() ? null : points.get(0);
	}

	/** The latest reading, or null when there is none. */
	@Nullable
	public BankHistoryPoint last()
	{
		return points.isEmpty() ? null : points.get(points.size() - 1);
	}

	/** The reading OF that day, or null when that day has none (or {@code day} is null). */
	@Nullable
	public BankHistoryPoint on(@Nullable final LocalDate day)
	{
		final int i = indexAtOrBefore(day);
		return i >= 0 && points.get(i).day().equals(day) ? points.get(i) : null;
	}

	/** The last reading on or before that day, or null when there is none (or {@code day} is null). */
	@Nullable
	public BankHistoryPoint atOrBefore(@Nullable final LocalDate day)
	{
		final int i = indexAtOrBefore(day);
		return i >= 0 ? points.get(i) : null;
	}

	/** The index of the last reading on or before {@code day}, or -1: a binary search over the sorted days. */
	private int indexAtOrBefore(@Nullable final LocalDate day)
	{
		if (day == null)
		{
			return -1;
		}
		int lo = 0;
		int hi = points.size() - 1;
		int found = -1;
		while (lo <= hi)
		{
			final int mid = (lo + hi) >>> 1;
			if (points.get(mid).day().isAfter(day))
			{
				hi = mid - 1;
			}
			else
			{
				found = mid;
				lo = mid + 1;
			}
		}
		return found;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		return o instanceof BankHistorySeries && points.equals(((BankHistorySeries) o).points);
	}

	@Override
	public int hashCode()
	{
		return points.hashCode();
	}

	@Override
	public String toString()
	{
		final BankHistoryPoint first = first();
		final BankHistoryPoint last = last();
		return "BankHistorySeries{readings=" + points.size()
			+ (first == null ? "" : ", first=" + first.day() + ", last=" + last.day()) + '}';
	}
}
