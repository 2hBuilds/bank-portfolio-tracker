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
 *
 * <p><b>Transfers</b> (1.2.2). A day the player marked as a transfer ({@link BankHistorySeries#transfers()}) changes what
 * is DRAWN and what is COMPARED, never what is recorded. {@link #drawnGp} is the one rule: a day's real total plus the gold
 * the counted notes dated after it moved, so the days before a transfer are drawn as if it had already happened. Every
 * CHANGE - {@link #overDays}, {@link #sinceDay}, {@link #vsPrevious} - is taken between those drawn totals, so it stops
 * counting the transfer; every TOTAL ({@link Day#valueGp()}) stays the real one. Today is never shifted.
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
		 * deltaGp = toGp - fromGp; pct = deltaGp x 100 / fromGp, or null when fromGp is 0 or less (the formula
		 * {@link PortfolioMath} uses for a window, and no flipped sign over a base a transfer took below zero, 1.2.2);
		 * spanDays = the calendar days from fromDay to toDay.
		 */
		Change(final LocalDate fromDay, final LocalDate toDay, final long fromGp, final long toGp)
		{
			this(fromDay, toDay, fromGp, toGp, fromGp);
		}

		/**
		 * @param baseGp what the percentage is a share of: {@code fromGp}, except on the day list, where a row prints its REAL
		 *               totals and so takes its percentage on the real total before it (1.2.2) - the gp change is still the
		 *               drawn one, so a transfer is still not counted
		 */
		Change(final LocalDate fromDay, final LocalDate toDay, final long fromGp, final long toGp, final long baseGp)
		{
			this.fromDay = Objects.requireNonNull(fromDay, "fromDay");
			this.toDay = Objects.requireNonNull(toDay, "toDay");
			this.fromGp = fromGp;
			this.toGp = toGp;
			this.deltaGp = toGp - fromGp;
			this.pct = baseGp <= 0L ? null : deltaGp * 100.0d / baseGp;
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
		 * as every percentage in the plugin is); null when {@code fromGp} is 0 or less.
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
	}

	/**
	 * One calendar day as drawn. A day with no reading is CARRIED: its value is the last reading before it, carried
	 * forward. Two days are equal when they are the same day, value, drawn value and reading.
	 */
	public static final class Day
	{
		private final LocalDate day;
		private final long valueGp;
		private final long drawnGp;
		private final boolean carried;
		@Nullable
		private final BankHistoryPoint reading;

		Day(final LocalDate day, final long valueGp, final long drawnGp, @Nullable final BankHistoryPoint reading)
		{
			this.day = Objects.requireNonNull(day, "day");
			this.valueGp = valueGp;
			this.drawnGp = drawnGp;
			this.carried = reading == null;
			this.reading = reading;
		}

		public LocalDate day()
		{
			return day;
		}

		/** The day's REAL total under the switches it was computed with - carried forward on a carried day. */
		public long valueGp()
		{
			return valueGp;
		}

		/**
		 * The total the chart draws and every change is measured from (1.2.2): {@link #valueGp()} plus the gold the counted
		 * transfers dated after this day moved - the same figure when there are none.
		 */
		public long drawnGp()
		{
			return drawnGp;
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
			return day.equals(other.day) && valueGp == other.valueGp && drawnGp == other.drawnGp
				&& Objects.equals(reading, other.reading);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(day, valueGp, drawnGp, reading);
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
		return new Change(from.day(), to.day(), drawnGp(s, from, options), drawnGp(s, to, options));
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
		return new Change(from.day(), today, drawnGp(s, from, options), nowGp);
	}

	/**
	 * The reading of {@code day} against the reading before it (the day list's change column). Null when that day
	 * has no reading, or when it is the first reading ("first reading" on the row). The gp change is the drawn one (a
	 * marked transfer is not counted); the percentage is a share of the REAL total before it, the figure the row above
	 * prints - so a row before a transfer reads the same percentage it always did (the lead's find on the 1.2.2 pictures:
	 * 941m and -6.81m read -0.9% on the drawn base where the row's own figures make -0.7%). The one exception is the MARKED row - a
	 * day carrying a note that counts under {@code options} - whose base is the larger of the real total before it and that total
	 * moved by the note: a received transfer would otherwise divide the leftover change by the small total from before the gold
	 * arrived (+200% beside a +0.9% change line), and a sent one by the large total from before it left.
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
		final ViewOptions o = options == null ? ViewOptions.DEFAULT : options;
		final BankHistorySeries.Transfer note = series.transfers().get(day);
		final long before = from.valueFor(o);
		final long base = note != null && note.counts(o.countCash()) ? Math.max(before, before + note.gp()) : before;
		return new Change(from.day(), to.day(), drawnGp(series, from, options), drawnGp(series, to, options), base);
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
		final BankHistorySeries s = cut(series, to);
		final BankHistoryPoint first = s.first();
		if (first == null)
		{
			return Collections.emptyList();
		}
		final LocalDate start = from == null || from.isBefore(first.day()) ? first.day() : from;
		if (start.isAfter(to))
		{
			return Collections.emptyList();
		}

		final List<BankHistoryPoint> points = s.points();
		final boolean countCash = (options == null ? ViewOptions.DEFAULT : options).countCash();
		final List<Day> out = new ArrayList<>((int) Math.min(Integer.MAX_VALUE - 8,
			ChronoUnit.DAYS.between(start, to) + 1));
		// The last reading on or before `start` seeds the carry; `next` walks the readings after it in step with the
		// days, so the whole range is one pass however long it is.
		BankHistoryPoint carry = s.atOrBefore(start);
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
			out.add(new Day(d, carriedValue, carriedValue + s.shiftAfter(d, countCash), reading));
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

	/**
	 * A reading's total as it is drawn and compared (1.2.2): its real {@link BankHistoryPoint#valueFor} plus the gold the
	 * counted transfers dated after its day moved ({@link BankHistorySeries#shiftAfter}) - the ONE rule behind every change
	 * here and every point of the chart. With no notes it IS the real total.
	 */
	static long drawnGp(final BankHistorySeries series, final BankHistoryPoint reading, @Nullable final ViewOptions options)
	{
		final ViewOptions o = options == null ? ViewOptions.DEFAULT : options;
		return reading.valueFor(o) + series.shiftAfter(reading.day(), o.countCash());
	}

	/**
	 * Whether drawing the shifted totals would put any reading of this series below zero (1.2.2, the user's pick 1a): an
	 * account that earned a sum and then sent it away has no days before the transfer to shift it onto. The chart then draws
	 * the real totals with the step into each marked day dotted; every figure stays corrected either way. Ask it with the
	 * series the reader can SEE (after "Reset history" or "Restore history"), so a hidden day never decides.
	 */
	public static boolean drawsBelowZero(@Nullable final BankHistorySeries series, @Nullable final ViewOptions options)
	{
		if (series == null || series.transfers().isEmpty())
		{
			return false;
		}
		for (final BankHistoryPoint reading : series.points())
		{
			if (drawnGp(series, reading, options) < 0L)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * What a transfer note on {@code day} starts as (1.2.2): the day's own note when it has one that counts (a coins note the
	 * switches leave out draws no mark, so the day reads as unmarked); otherwise the day's REAL
	 * change against the reading before it - never the corrected one, which a note makes 0. When the coins that moved (the
	 * cash cells the switches count) went the same way as the whole change and are at least 80 % of it, the amount is exactly
	 * those coins and {@link BankHistorySeries.Transfer#coins()} is true, so a day the prices also moved still offers the
	 * exact sum; otherwise it is the whole change, not coins. Null when there is nothing to offer: no reading that day, none
	 * before it, or a change of 0.
	 */
	@Nullable
	public static BankHistorySeries.Transfer prefill(@Nullable final BankHistorySeries series, @Nullable final LocalDate day,
		@Nullable final ViewOptions options)
	{
		if (series == null || day == null)
		{
			return null;
		}
		final ViewOptions o = options == null ? ViewOptions.DEFAULT : options;
		final BankHistorySeries.Transfer own = series.transfers().get(day);
		if (own != null && own.counts(o.countCash()))
		{
			return own;
		}
		final BankHistoryPoint to = series.on(day);
		final BankHistoryPoint from = to == null ? null : series.atOrBefore(day.minusDays(1));
		if (from == null)
		{
			return null;
		}
		final ViewOptions bare = o.withCountCash(false);
		final long whole = to.valueFor(o) - from.valueFor(o);
		final long coins = whole - (to.valueFor(bare) - from.valueFor(bare));
		if (coins != 0L && Long.signum(coins) == Long.signum(whole) && Math.abs(coins) * 5L >= Math.abs(whole) * 4L)
		{
			return new BankHistorySeries.Transfer(coins, true);
		}
		return whole == 0L ? null : new BankHistorySeries.Transfer(whole, false);
	}

	/**
	 * Which way a transfer went (1.2.3), from its sign: minus left this account (sent), plus arrived (got). The one rule behind the
	 * one-click item, the undo item and the row's tag.
	 */
	public static boolean sent(final long gp)
	{
		return gp < 0L;
	}

	/** The series cut to today; a null series reads as {@link BankHistorySeries#EMPTY}. */
	private static BankHistorySeries cut(@Nullable final BankHistorySeries series, final LocalDate today)
	{
		return series == null ? BankHistorySeries.EMPTY : series.upTo(today);
	}
}
