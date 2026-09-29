package com.bankpricemovement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The arithmetic over a {@link BankHistorySeries} (addendum AU; phase 0 contract sections 3, 9.5 and 9.6): the
 * change over a window, the change a day's reading made against the reading before it, the calendar days a
 * chart draws - carried days included - and the thinning that keeps a long range to about 120 points.
 *
 * <p>Pure and static. Nothing here reads a clock or a time zone: every caller hands in "today" (from its own
 * clock), and {@link #dayOf} takes the zone. Every figure is a reading's {@link BankHistoryPoint#valueFor}, so
 * everything drawn follows the switches that are on now; a null {@link ViewOptions} reads as
 * {@link ViewOptions#DEFAULT} everywhere (amendment 9.2).
 */
public final class BankHistoryMath
{
	private BankHistoryMath()
	{
	}

	/**
	 * The LOCAL calendar date of an epoch-millisecond stamp (contract section 3): what a reading is filed under.
	 * Production passes {@link ZoneId#systemDefault()}; a test passes the zone it needs.
	 */
	public static LocalDate dayOf(final long epochMillis, final ZoneId zone)
	{
		return Instant.ofEpochMilli(epochMillis).atZone(Objects.requireNonNull(zone, "zone")).toLocalDate();
	}

	/**
	 * The total moved from one reading to another (or to "now"). {@link #fromDay()} is the day of the reading
	 * actually USED, which is older than the window's day whenever that day had no reading - {@link #spanDays()}
	 * is what says so.
	 */
	public static final class Change
	{
		private final LocalDate fromDay;
		private final LocalDate toDay;
		private final long fromGp;
		private final long toGp;
		private final long deltaGp;
		@Nullable
		private final Double pct;
		private final int spanDays;

		/**
		 * deltaGp = toGp - fromGp; pct = deltaGp x 100 / fromGp, or null when fromGp is 0 (the formula
		 * {@link PortfolioMath} uses for a window); spanDays = the calendar days from fromDay to toDay.
		 */
		Change(final LocalDate fromDay, final LocalDate toDay, final long fromGp, final long toGp)
		{
			this.fromDay = Objects.requireNonNull(fromDay, "fromDay");
			this.toDay = Objects.requireNonNull(toDay, "toDay");
			this.fromGp = fromGp;
			this.toGp = toGp;
			this.deltaGp = toGp - fromGp;
			this.pct = fromGp == 0L ? null : deltaGp * 100.0d / fromGp;
			this.spanDays = (int) ChronoUnit.DAYS.between(fromDay, toDay);
		}

		/** The day of the reading compared FROM - the one actually used. */
		public LocalDate fromDay()
		{
			return fromDay;
		}

		/** The day compared TO. */
		public LocalDate toDay()
		{
			return toDay;
		}

		/** The total at the day compared TO less the total at the day compared FROM. */
		public long deltaGp()
		{
			return deltaGp;
		}

		/**
		 * The move as a percentage of the total compared FROM, unrounded (a reader truncates it toward zero when printing,
		 * as every percentage in the plugin is); null when {@code fromGp} is 0.
		 */
		@Nullable
		public Double pct()
		{
			return pct;
		}

		/** Calendar days from {@link #fromDay()} to {@link #toDay()}. */
		public int spanDays()
		{
			return spanDays;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Change))
			{
				return false;
			}
			final Change other = (Change) o;
			return fromDay.equals(other.fromDay) && toDay.equals(other.toDay) && fromGp == other.fromGp
				&& toGp == other.toGp;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(fromDay, toDay, fromGp, toGp);
		}

		@Override
		public String toString()
		{
			return "Change{" + fromDay + " " + fromGp + " -> " + toDay + " " + toGp + ", deltaGp=" + deltaGp
				+ ", pct=" + pct + ", spanDays=" + spanDays + '}';
		}
	}

	/**
	 * One calendar day as drawn. A day with no reading is CARRIED: its value is the last reading before it, carried
	 * forward. Two days are equal when they are the same day, value and reading.
	 */
	public static final class Day
	{
		private final LocalDate day;
		private final long valueGp;
		private final boolean carried;
		@Nullable
		private final BankHistoryPoint reading;

		Day(final LocalDate day, final long valueGp, @Nullable final BankHistoryPoint reading)
		{
			this.day = Objects.requireNonNull(day, "day");
			this.valueGp = valueGp;
			this.carried = reading == null;
			this.reading = reading;
		}

		public LocalDate day()
		{
			return day;
		}

		/** The day's total under the switches it was computed with - carried forward on a carried day. */
		public long valueGp()
		{
			return valueGp;
		}

		/** True when the day has no reading of its own. */
		public boolean carried()
		{
			return carried;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Day))
			{
				return false;
			}
			final Day other = (Day) o;
			return day.equals(other.day) && valueGp == other.valueGp && Objects.equals(reading, other.reading);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(day, valueGp, reading);
		}

		@Override
		public String toString()
		{
			return "Day{" + day + " " + valueGp + (carried ? " carried" : "") + '}';
		}
	}

	/**
	 * The change over a range of {@code days} ending today (amendment 9.5): the series is first cut to
	 * {@code upTo(today)}; to = the last reading on or before today; from = the first reading when
	 * {@code days <= 0} (the "all" range), else the last reading on or before {@code today - days}. Null when either
	 * is missing, or when from is the same reading as to - never a change of 0 that was not measured.
	 */
	@Nullable
	public static Change overDays(@Nullable final BankHistorySeries series, final LocalDate today, final int days,
		@Nullable final ViewOptions options)
	{
		Objects.requireNonNull(today, "today");
		final BankHistorySeries s = cut(series, today);
		final BankHistoryPoint to = s.atOrBefore(today);
		final BankHistoryPoint from = days <= 0 ? s.first() : s.atOrBefore(today.minusDays(days));
		if (to == null || from == null || from.day().equals(to.day()))
		{
			return null;
		}
		return new Change(from.day(), to.day(), from.valueFor(options), to.valueFor(options));
	}

	/**
	 * The card's move in History (amendment 9.6): the total NOW against the last reading on or before
	 * {@code today - days}. toDay is today and toGp is {@code nowGp}, the card's own headline, so the move is the
	 * figure the card prints against the day the footnote names. Null when no reading is old enough - the chip is
	 * then greyed. {@code days <= 0} compares against the first reading, as {@link #overDays} does.
	 */
	@Nullable
	public static Change sinceDay(@Nullable final BankHistorySeries series, final LocalDate today, final int days,
		@Nullable final ViewOptions options, final long nowGp)
	{
		Objects.requireNonNull(today, "today");
		final BankHistorySeries s = cut(series, today);
		final BankHistoryPoint from = days <= 0 ? s.first() : s.atOrBefore(today.minusDays(days));
		if (from == null)
		{
			return null;
		}
		return new Change(from.day(), today, from.valueFor(options), nowGp);
	}

	/**
	 * The reading of {@code day} against the reading before it (the day list's change column). Null when that day
	 * has no reading, or when it is the first reading ("first reading" on the row).
	 */
	@Nullable
	public static Change vsPrevious(@Nullable final BankHistorySeries series, @Nullable final LocalDate day,
		@Nullable final ViewOptions options)
	{
		if (series == null || day == null)
		{
			return null;
		}
		final BankHistoryPoint to = series.on(day);
		final BankHistoryPoint from = to == null ? null : series.atOrBefore(day.minusDays(1));
		if (from == null)
		{
			return null;
		}
		return new Change(from.day(), to.day(), from.valueFor(options), to.valueFor(options));
	}

	/**
	 * Every calendar day from {@code max(from, first reading)} to {@code to}, ascending - the ONE place carried days
	 * are derived (contract section 3). A day with a reading is that reading's total; a day without one carries the
	 * last reading before it forward. Readings after {@code to} play no part. Empty when the series is empty or
	 * {@code to} is before the first reading.
	 *
	 * @param from the first day wanted, or null for the first reading's day (the "all" range)
	 * @param to   the last day wanted - today, for the chart and the list
	 */
	public static List<Day> days(@Nullable final BankHistorySeries series, @Nullable final LocalDate from,
		final LocalDate to, @Nullable final ViewOptions options)
	{
		Objects.requireNonNull(to, "to");
		final BankHistoryPoint first = series == null ? null : series.first();
		if (first == null)
		{
			return Collections.emptyList();
		}
		final LocalDate start = from == null || from.isBefore(first.day()) ? first.day() : from;
		if (start.isAfter(to))
		{
			return Collections.emptyList();
		}

		final List<BankHistoryPoint> points = series.points();
		final List<Day> out = new ArrayList<>((int) Math.min(Integer.MAX_VALUE - 8,
			ChronoUnit.DAYS.between(start, to) + 1));
		// The last reading on or before `start` seeds the carry; `next` walks the readings after it in step with the
		// days, so the whole range is one pass however long it is.
		BankHistoryPoint carry = series.atOrBefore(start);
		int next = carry == null ? 0 : points.indexOf(carry) + 1;
		long carriedValue = carry == null ? 0L : carry.valueFor(options);
		for (LocalDate d = start; !d.isAfter(to); d = d.plusDays(1))
		{
			while (next < points.size() && !points.get(next).day().isAfter(d))
			{
				carry = points.get(next++);
				carriedValue = carry.valueFor(options);
			}
			final BankHistoryPoint reading = carry != null && carry.day().equals(d) ? carry : null;
			out.add(new Day(d, carriedValue, reading));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * At most about {@code maxPoints} of these days (plan 7.2 item 10): unchanged when there are no more than that;
	 * otherwise buckets of {@code ceil(n / maxPoints)} days, aligned to the LAST day (so the newest bucket is always
	 * whole and ends today, and only the oldest may be short), each drawn as its last READING - or, when every day in
	 * it is carried, as its last day.
	 *
	 * @throws IllegalArgumentException if {@code maxPoints} is below 1
	 */
	public static List<Day> thin(@Nullable final List<Day> days, final int maxPoints)
	{
		if (maxPoints < 1)
		{
			throw new IllegalArgumentException("maxPoints must be at least 1, not " + maxPoints);
		}
		if (days == null || days.isEmpty())
		{
			return Collections.emptyList();
		}
		final int n = days.size();
		if (n <= maxPoints)
		{
			return days;
		}
		final int bucket = (n + maxPoints - 1) / maxPoints;
		final List<Day> out = new ArrayList<>(maxPoints);
		// Walk back from the last day, one bucket at a time, then reverse: the alignment is to the end.
		for (int end = n - 1; end >= 0; end -= bucket)
		{
			final int start = Math.max(0, end - bucket + 1);
			Day pick = days.get(end);
			for (int i = end; i >= start; i--)
			{
				if (!days.get(i).carried())
				{
					pick = days.get(i);
					break;
				}
			}
			out.add(pick);
		}
		Collections.reverse(out);
		return Collections.unmodifiableList(out);
	}

	/**
	 * The day a window of {@code days} first has a reading old enough - {@code first().day() + days} - which is
	 * when a greyed chip stops being grey (amendment 9.6). Null for an empty series.
	 */
	@Nullable
	public static LocalDate fillsOn(@Nullable final BankHistorySeries series, final int days)
	{
		final BankHistoryPoint first = series == null ? null : series.first();
		return first == null ? null : first.day().plusDays(Math.max(0, days));
	}

	/** The series cut to today; a null series reads as {@link BankHistorySeries#EMPTY}. */
	private static BankHistorySeries cut(@Nullable final BankHistorySeries series, final LocalDate today)
	{
		return series == null ? BankHistorySeries.EMPTY : series.upTo(today);
	}
}
