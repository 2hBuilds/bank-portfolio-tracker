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
 *
 * <p><b>The fresh start</b> (1.0.9 part 5). Readings recorded before 1.0.9 did not count the Grand Exchange offers,
 * so for a player who keeps much of their bank on offer those days read low. {@link #freshFrom()} names the first day
 * recorded by 1.0.9 or later: the days before it are the LEGACY days, and {@link #fromFresh()} is the series without
 * them. It is part of the value - two series holding the same readings but cut at different days are not equal - and
 * every copy carries it, so the store and the panel never have to remember it on the side. A series built by
 * {@link #of} has none: every day counts.
 */
public final class BankHistorySeries
{
	/** No readings at all. */
	public static final BankHistorySeries EMPTY = new BankHistorySeries(Collections.<BankHistoryPoint>emptyList(), null);

	/** Ascending by day, one per day, unmodifiable. */
	private final List<BankHistoryPoint> points;

	/** The first day recorded by 1.0.9 or later; null = no legacy days; {@link LocalDate#MAX} = every day is one. */
	@Nullable
	private final LocalDate freshFrom;

	private BankHistorySeries(final List<BankHistoryPoint> sorted, @Nullable final LocalDate freshFrom)
	{
		this.points = sorted;
		this.freshFrom = freshFrom;
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
		return fromMap(byDay, null);
	}

	/** Of two readings of one day, the one taken later; {@code b} on a tie. */
	private static BankHistoryPoint later(final BankHistoryPoint a, final BankHistoryPoint b)
	{
		return a.readAtMillis() > b.readAtMillis() ? a : b;
	}

	private static BankHistorySeries fromMap(final TreeMap<LocalDate, BankHistoryPoint> byDay,
		@Nullable final LocalDate freshFrom)
	{
		if (byDay.isEmpty())
		{
			return EMPTY;
		}
		return new BankHistorySeries(Collections.unmodifiableList(new ArrayList<>(byDay.values())), freshFrom);
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
		return fromMap(byDay, freshFrom);
	}

	/**
	 * This series with {@code freshFrom} as its fresh start (1.0.9 part 5): the store's way to say "the days before
	 * this one were recorded before 1.0.9". Null clears it; {@link LocalDate#MAX} says every day is a legacy day. An
	 * EMPTY series has nothing to hide and answers itself.
	 */
	public BankHistorySeries withFreshFrom(@Nullable final LocalDate freshFrom)
	{
		if (points.isEmpty() || Objects.equals(this.freshFrom, freshFrom))
		{
			return this;
		}
		return new BankHistorySeries(points, freshFrom);
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
		return kept.isEmpty() ? EMPTY : new BankHistorySeries(Collections.unmodifiableList(kept), freshFrom);
	}

	/**
	 * The first day recorded by 1.0.9 or later (1.0.9 part 5), or null when this series has no legacy days: every new
	 * install, and every file that was never written by an older build. {@link LocalDate#MAX} means every day here
	 * is a legacy day - a file migrated from an older schema that 1.0.9 has not recorded into yet. Otherwise the days
	 * BEFORE it are the legacy ones.
	 */
	@Nullable
	public LocalDate freshFrom()
	{
		return freshFrom;
	}

	/**
	 * This series cut to the days recorded by 1.0.9 or later - the days at or after {@link #freshFrom()}: the whole
	 * series when there is none, {@link #EMPTY} when every day is a legacy day. The cut series has no legacy days of
	 * its own, so its {@link #freshFrom()} is null. The panel hands the History view and the card THIS unless the
	 * reader has asked to see the old days.
	 */
	public BankHistorySeries fromFresh()
	{
		if (freshFrom == null)
		{
			return this;
		}
		if (LocalDate.MAX.equals(freshFrom))
		{
			return EMPTY;
		}
		final List<BankHistoryPoint> kept = new ArrayList<>(points.size());
		for (final BankHistoryPoint point : points)
		{
			if (!point.day().isBefore(freshFrom))
			{
				kept.add(point);
			}
		}
		return kept.isEmpty() ? EMPTY : new BankHistorySeries(Collections.unmodifiableList(kept), null);
	}

	/**
	 * Whether any reading here was recorded before 1.0.9 (1.0.9 part 5): a {@link #freshFrom()} is named and at least
	 * one day lies before it - {@link LocalDate#MAX} counts as long as the series is not empty. The History tab
	 * shows its "Include days before v1.0.9" check box only while this is true.
	 */
	public boolean hasLegacyDays()
	{
		if (freshFrom == null || points.isEmpty())
		{
			return false;
		}
		return points.get(0).day().isBefore(freshFrom);
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
		if (!(o instanceof BankHistorySeries))
		{
			return false;
		}
		final BankHistorySeries that = (BankHistorySeries) o;
		return points.equals(that.points) && Objects.equals(freshFrom, that.freshFrom);
	}

	@Override
	public int hashCode()
	{
		return 31 * points.hashCode() + Objects.hashCode(freshFrom);
	}

	@Override
	public String toString()
	{
		final BankHistoryPoint first = first();
		final BankHistoryPoint last = last();
		return "BankHistorySeries{readings=" + points.size()
			+ (first == null ? "" : ", first=" + first.day() + ", last=" + last.day())
			+ (freshFrom == null ? "" : ", freshFrom=" + (LocalDate.MAX.equals(freshFrom) ? "all" : freshFrom)) + '}';
	}
}
