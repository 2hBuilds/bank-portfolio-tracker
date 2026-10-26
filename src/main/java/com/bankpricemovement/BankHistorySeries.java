package com.bankpricemovement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
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
 *
 * <p><b>The placeholder judgement</b> (1.1.1 part B). Until 1.1.1 a bank placeholder that arrived with a quantity was
 * counted as one of its item, so every reading such a player recorded reads HIGH. The first reading taken from a fresh
 * bank read after the update judges the series, once: {@link #placeholdersChecked()} is the day it was judged, and when
 * that read dropped placeholders with a quantity and the series held an earlier day, the fresh start moves up to that day
 * with {@link #freshWhy()} {@value #WHY_PLACEHOLDERS} ({@link #judged}). Both are part of the value and carried by every
 * copy, as {@link #freshFrom()} is. An EMPTY series carries none of the three: it has nothing to hide or judge.
 *
 * <p><b>The transfer notes</b> (1.2.2). A day the player marked as "a transfer" - gold sent to or received from another
 * account - is a {@link Transfer} in {@link #transfers()}, kept in its own file beside the readings and carried by every
 * copy, as part of the value. A note sits on a day this series holds a reading of, and an EMPTY series carries none;
 * {@link #of} has none. The notes never change a reading: {@link BankHistoryMath} reads them to draw the days before a
 * transfer as if the gold had already moved.
 *
 * <p><b>The restore mark</b> (1.2.2 part 5). A player's own reset ({@link #WHY_PLAYER}) and its way back belong to THIS account:
 * {@link #restored()} says the player pressed "Restore history" after it, so the panel shows every day again, and a new reset
 * takes the mark off. It is read with the notes, from the same file, and carried by every copy as part of the value; {@link #of}
 * has none and an EMPTY series carries none.
 *
 * <p><b>The cut a reset stands over</b> (1.2.2 verification fix). A player's reset replaces the record's {@link #freshFrom()} and
 * {@link #freshWhy()}, so the automatic cut of 1.0.9 or 1.1.1 it replaced would be forgotten - and with it the warning those days
 * earn. {@link #autoCut()} keeps that cut, in the account's notes file, and {@link #standing()} is the record as the panel reads its
 * cut: the player's own until they restore it, the automatic one after. Carried by every copy, as part of the value; {@link #of}
 * has none and an EMPTY series carries none.
 */
public final class BankHistorySeries
{
	private static final SortedMap<LocalDate, Transfer> NO_TRANSFERS = Collections.emptySortedMap();

	/** No readings at all. */
	public static final BankHistorySeries EMPTY = new BankHistorySeries(Collections.<BankHistoryPoint>emptyList(), null,
		null, null, NO_TRANSFERS, false, null);

	/**
	 * The {@link #freshWhy()} of a fresh start made because the days before it counted bank placeholders as items (1.1.1
	 * part B). A series with no reason stands for 1.0.9 part 5's: the days before it did not count the Grand Exchange
	 * offers.
	 */
	public static final String WHY_PLACEHOLDERS = "placeholders";

	/**
	 * The {@link #freshWhy()} of a fresh start the player made (1.2.2): "Reset history", or "Reset history from this day". An
	 * older build reads a reason it does not know as 1.0.9's, which only words its tick box differently.
	 */
	public static final String WHY_PLAYER = "player";

	/** Ascending by day, one per day, unmodifiable. */
	private final List<BankHistoryPoint> points;

	/** The first day recorded by 1.0.9 or later; null = no legacy days; {@link LocalDate#MAX} = every day is one. */
	@Nullable
	private final LocalDate freshFrom;

	/** 1.1.1 part B: the day this series was judged for counted placeholders; null = not judged yet. */
	@Nullable
	private final LocalDate placeholdersChecked;

	/** 1.1.1 part B: why the days before {@link #freshFrom} are hidden; null = 1.0.9 part 5's reason. */
	@Nullable
	private final String freshWhy;

	/** 1.2.2: the days the player marked as transfers, ascending, unmodifiable; never null. */
	private final SortedMap<LocalDate, Transfer> transfers;

	/** 1.2.2 part 5: the player brought back the days their own reset hid; false = not, or no player's reset. */
	private final boolean restored;

	/** 1.2.2 verification fix: the automatic cut a player's reset stands over; null = none. */
	@Nullable
	private final AutoCut autoCut;

	private BankHistorySeries(final List<BankHistoryPoint> sorted, @Nullable final LocalDate freshFrom,
		@Nullable final LocalDate placeholdersChecked, @Nullable final String freshWhy,
		final SortedMap<LocalDate, Transfer> transfers, final boolean restored, @Nullable final AutoCut autoCut)
	{
		this.points = sorted;
		this.freshFrom = freshFrom;
		this.placeholdersChecked = placeholdersChecked;
		this.freshWhy = freshWhy;
		this.transfers = transfers;
		this.restored = restored;
		this.autoCut = autoCut;
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
		return fromMap(byDay, null, null, null, NO_TRANSFERS, false, null);
	}

	/** Of two readings of one day, the one taken later; {@code b} on a tie. */
	private static BankHistoryPoint later(final BankHistoryPoint a, final BankHistoryPoint b)
	{
		return a.readAtMillis() > b.readAtMillis() ? a : b;
	}

	private static BankHistorySeries fromMap(final TreeMap<LocalDate, BankHistoryPoint> byDay,
		@Nullable final LocalDate freshFrom, @Nullable final LocalDate placeholdersChecked, @Nullable final String freshWhy,
		final SortedMap<LocalDate, Transfer> transfers, final boolean restored, @Nullable final AutoCut autoCut)
	{
		if (byDay.isEmpty())
		{
			return EMPTY;
		}
		return new BankHistorySeries(Collections.unmodifiableList(new ArrayList<>(byDay.values())), freshFrom,
			placeholdersChecked, freshWhy, transfers, restored, autoCut);
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
		return fromMap(byDay, freshFrom, placeholdersChecked, freshWhy, transfers, restored, autoCut);
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
		return new BankHistorySeries(points, freshFrom, placeholdersChecked, freshWhy, transfers, restored, autoCut);
	}

	/**
	 * This series with {@code day} as the day it was judged for counted placeholders (1.1.1 part B): the store's way to
	 * put back what a file says. Null clears it. An EMPTY series answers itself.
	 */
	public BankHistorySeries withPlaceholdersChecked(@Nullable final LocalDate day)
	{
		if (points.isEmpty() || Objects.equals(placeholdersChecked, day))
		{
			return this;
		}
		return new BankHistorySeries(points, freshFrom, day, freshWhy, transfers, restored, autoCut);
	}

	/**
	 * This series with {@code why} as the reason for its fresh start (1.1.1 part B), as a file says it. Null is 1.0.9 part
	 * 5's reason. An EMPTY series answers itself.
	 */
	public BankHistorySeries withFreshWhy(@Nullable final String why)
	{
		if (points.isEmpty() || Objects.equals(freshWhy, why))
		{
			return this;
		}
		return new BankHistorySeries(points, freshFrom, placeholdersChecked, why, transfers, restored, autoCut);
	}

	/**
	 * This series with the restore mark set to {@code restored} (1.2.2 part 5): the player's own reset brought back, or - false -
	 * hidden again by a new one. The store puts back what the notes file says with it, and the service sets it at the player's
	 * press. An EMPTY series answers itself.
	 */
	public BankHistorySeries withRestored(final boolean restored)
	{
		if (points.isEmpty() || this.restored == restored)
		{
			return this;
		}
		return new BankHistorySeries(points, freshFrom, placeholdersChecked, freshWhy, transfers, restored, autoCut);
	}

	/**
	 * This series with {@code cut} as the automatic cut a player's reset stands over (1.2.2 verification fix): the store puts back what
	 * the notes file says, and the service sets it at the player's reset. Null clears it. An EMPTY series answers itself.
	 */
	public BankHistorySeries withAutoCut(@Nullable final AutoCut cut)
	{
		if (points.isEmpty() || Objects.equals(autoCut, cut))
		{
			return this;
		}
		return new BankHistorySeries(points, freshFrom, placeholdersChecked, freshWhy, transfers, restored, cut);
	}

	/**
	 * This series judged for counted placeholders (1.1.1 part B) - the ONE rule, which the service applies to the series
	 * in memory and the store to the file it re-reads, so the two cannot come to disagree. A series already judged answers
	 * itself whatever {@code check} says: the judgement is made once per file. So do a null check (a reading that was not
	 * taken from a fresh bank read) and an EMPTY series.
	 *
	 * <p>Otherwise the series is marked judged as of {@link PlaceholderCheck#day()}; and when the check is a restart, the
	 * fresh start moves up to that day - never back: a later {@link #freshFrom()} already standing stays - with
	 * {@link #WHY_PLACEHOLDERS} as its reason.
	 */
	public BankHistorySeries judged(@Nullable final PlaceholderCheck check)
	{
		if (check == null || points.isEmpty() || placeholdersChecked != null)
		{
			return this;
		}
		if (!check.restart())
		{
			return new BankHistorySeries(points, freshFrom, check.day(), freshWhy, transfers, restored, autoCut);
		}
		final LocalDate from = freshFrom != null && freshFrom.isAfter(check.day()) ? freshFrom : check.day();
		return new BankHistorySeries(points, from, check.day(), WHY_PLACEHOLDERS, transfers, restored, autoCut);
	}

	/**
	 * The judgement this series carries (1.1.1 part B), or null while it has none: the day it was judged, and whether that
	 * judgement restarted it - which is what a {@link #freshWhy()} of {@link #WHY_PLACEHOLDERS} records. What the service
	 * hands the store with every write, so a file not yet judged gets the decision the series in memory already holds.
	 */
	@Nullable
	public PlaceholderCheck placeholderCheck()
	{
		return placeholdersChecked == null ? null
			: new PlaceholderCheck(placeholdersChecked, WHY_PLACEHOLDERS.equals(freshWhy));
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
		return kept.isEmpty() ? EMPTY
			: new BankHistorySeries(Collections.unmodifiableList(kept), freshFrom, placeholdersChecked, freshWhy,
				Collections.unmodifiableSortedMap(new TreeMap<>(transfers.headMap(today.plusDays(1)))), restored, autoCut);
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
	 * its own, so its {@link #freshFrom()} is null; it keeps the placeholder judgement and the reason (1.1.1 part B). The
	 * panel hands the History view and the card THIS unless the reader has asked to see the old days.
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
		// The notes ride along whole: one on a hidden day shifts only days before it, so it is inert here (1.2.2).
		return kept.isEmpty() ? EMPTY
			: new BankHistorySeries(Collections.unmodifiableList(kept), null, placeholdersChecked, freshWhy, transfers, restored,
					autoCut);
	}

	/**
	 * Whether any reading here was recorded before 1.0.9 (1.0.9 part 5): a {@link #freshFrom()} is named and at least
	 * one day lies before it - {@link LocalDate#MAX} counts as long as the series is not empty. The settings menu
	 * carries its "Include days before v1.0.9" item only while this is true.
	 */
	public boolean hasLegacyDays()
	{
		if (freshFrom == null || points.isEmpty())
		{
			return false;
		}
		return points.get(0).day().isBefore(freshFrom);
	}

	/**
	 * The day this series was judged for bank placeholders counted as items (1.1.1 part B), or null while it has not been:
	 * every series an older build wrote, and every series no fresh bank read has recorded into since the update.
	 */
	@Nullable
	public LocalDate placeholdersChecked()
	{
		return placeholdersChecked;
	}

	/**
	 * Why the days before {@link #freshFrom()} are hidden (1.1.1 part B): {@link #WHY_PLACEHOLDERS} when they counted bank
	 * placeholders as items, or null for 1.0.9 part 5's reason - they did not count the Grand Exchange offers. A reason
	 * this build does not know is kept, and read as null's.
	 */
	@Nullable
	public String freshWhy()
	{
		return freshWhy;
	}

	/**
	 * The days the player marked as transfers (1.2.2), ascending: each is a day this series holds a reading of. Unmodifiable;
	 * empty for a series with none.
	 */
	public SortedMap<LocalDate, Transfer> transfers()
	{
		return transfers;
	}

	/**
	 * Whether the player brought back the days their own reset hid (1.2.2 part 5): only meaningful while {@link #freshWhy()} is
	 * {@value #WHY_PLAYER}. This account's own, as its notes are - the shared "Include days before ..." box never says it.
	 */
	public boolean restored()
	{
		return restored;
	}

	/**
	 * The automatic cut a player's reset stands over (1.2.2 verification fix), or null for none: the 1.0.9 or 1.1.1 cut the reset
	 * replaced, kept so Restore history can still ask the warning those days earn. Only meaningful while {@link #freshWhy()} is
	 * {@value #WHY_PLAYER}.
	 */
	@Nullable
	public AutoCut autoCut()
	{
		return autoCut;
	}

	/**
	 * The automatic cut a player's reset made NOW would stand over: this record's own cut while it is one (1.0.9's or 1.1.1's, not a
	 * migrated file's {@link LocalDate#MAX}), the one it already stands over while the cut is the player's, or null. The service asks it of
	 * the series in memory and the store of the file it re-reads, so the two cannot come to disagree.
	 */
	@Nullable
	AutoCut autoCutToKeep()
	{
		if (points.isEmpty() || WHY_PLAYER.equals(freshWhy) || freshFrom == null || LocalDate.MAX.equals(freshFrom))
		{
			return autoCut;
		}
		return new AutoCut(freshFrom, freshWhy);
	}

	/**
	 * This record as the panel reads its cut (1.2.2 verification fix): a player's reset the player has restored, over an automatic
	 * cut, leaves that cut standing - the days it hid read low or high, so they stay hidden until the shared "Include days before ..."
	 * box and its warning bring them back. Every other record answers itself. Only {@link #freshFrom()} and {@link #freshWhy()}
	 * differ; the readings and the notes are the same.
	 */
	public BankHistorySeries standing()
	{
		if (!restored || autoCut == null || !WHY_PLAYER.equals(freshWhy) || points.isEmpty())
		{
			return this;
		}
		return new BankHistorySeries(points, autoCut.from(), placeholdersChecked, autoCut.why(), transfers, restored, autoCut);
	}

	/**
	 * This series with exactly these notes (1.2.2): the store's way to put back what its file says, and the service's after it
	 * rebuilds the readings. A note is kept only when it names a day this series holds a reading of and moves some gold - no more
	 * than {@link Transfer#MAX_GP}, so no sum of notes can overflow - so a
	 * file naming a day that was never recorded - or a reading the clock-behind rule dropped - leaves no stray shift. Null or
	 * empty clears them. An EMPTY series answers itself.
	 */
	public BankHistorySeries withTransfers(@Nullable final Map<LocalDate, Transfer> notes)
	{
		if (points.isEmpty())
		{
			return this;
		}
		final TreeMap<LocalDate, Transfer> next = new TreeMap<>();
		for (final Map.Entry<LocalDate, Transfer> note : notes == null ? NO_TRANSFERS.entrySet() : notes.entrySet())
		{
			final long gp = note.getValue().gp();
			if (gp != 0L && gp >= -Transfer.MAX_GP && gp <= Transfer.MAX_GP && on(note.getKey()) != null)
			{
				next.put(note.getKey(), note.getValue());
			}
		}
		return next.equals(transfers) ? this
			: new BankHistorySeries(points, freshFrom, placeholdersChecked, freshWhy,
				next.isEmpty() ? NO_TRANSFERS : Collections.unmodifiableSortedMap(next), restored, autoCut);
	}

	/**
	 * This series with {@code note} as the transfer of {@code day} (1.2.2), replacing the one that stood there; null - or a
	 * note of 0 gp - takes it away. A day with no reading answers this series: a note belongs to a reading.
	 */
	public BankHistorySeries withTransfer(@Nullable final LocalDate day, @Nullable final Transfer note)
	{
		if (day == null)
		{
			return this;
		}
		final TreeMap<LocalDate, Transfer> next = new TreeMap<>(transfers);
		if (note == null)
		{
			next.remove(day);
		}
		else
		{
			next.put(day, note);
		}
		return withTransfers(next);
	}

	/**
	 * The gold the counted notes dated AFTER {@code day} moved (1.2.2): what {@link BankHistoryMath} adds to that day's real
	 * total to draw it as if every transfer after it had already happened - a sum sent away (minus) takes the older days
	 * down to where the line landed, a sum received (plus) brings them up. A coins note counts only while {@code countCash}
	 * is on - a transfer the line never showed a dip for must draw no shift either. Today is never shifted: no note is
	 * dated after it.
	 */
	long shiftAfter(final LocalDate day, final boolean countCash)
	{
		long sum = 0L;
		if (transfers.isEmpty())
		{
			return sum;
		}
		for (final Transfer note : transfers.tailMap(day.plusDays(1)).values())
		{
			if (note.counts(countCash))
			{
				sum += note.gp();
			}
		}
		return sum;
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
		return points.equals(that.points) && Objects.equals(freshFrom, that.freshFrom)
			&& Objects.equals(placeholdersChecked, that.placeholdersChecked) && Objects.equals(freshWhy, that.freshWhy)
			&& transfers.equals(that.transfers) && restored == that.restored && Objects.equals(autoCut, that.autoCut);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(points, freshFrom, placeholdersChecked, freshWhy, transfers, restored, autoCut);
	}

	/**
	 * One transfer note (1.2.2): gold the player sent to, or received from, another account on the day of a reading.
	 * {@link #gp()} is signed - minus is sent away, plus is received - and never 0; {@link #coins()} says the amount was coins
	 * (the day's cash cells moved it), which the "Include coins and platinum tokens" switch decides is drawn or not. Immutable.
	 */
	public static final class Transfer
	{
		/** The most gold one note may move, 10,000b: far above any bank, and small enough that the notes of every day add up in a long. */
		public static final long MAX_GP = 10_000_000_000_000L;

		private final long gp;
		private final boolean coins;

		public Transfer(final long gp, final boolean coins)
		{
			this.gp = gp;
			this.coins = coins;
		}

		/** The gold moved: minus when it left this account, plus when it arrived. */
		public long gp()
		{
			return gp;
		}

		/** Whether the amount was coins and platinum tokens, rather than the day's whole change. */
		public boolean coins()
		{
			return coins;
		}

		/**
		 * Whether the line draws this note: an items note always, a coins note only while the coins switch is on (a transfer the
		 * line showed no dip for must draw no shift). The one rule behind the shift, the marked row and the chart's marks.
		 */
		boolean counts(final boolean countCash)
		{
			return !coins || countCash;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Transfer))
			{
				return false;
			}
			final Transfer that = (Transfer) o;
			return gp == that.gp && coins == that.coins;
		}

		@Override
		public int hashCode()
		{
			return 31 * Long.hashCode(gp) + (coins ? 1 : 0);
		}
	}

	/**
	 * The automatic cut (1.0.9 part 5 or 1.1.1 part B) a player's reset stands over (1.2.2 verification fix): the first day those cuts
	 * showed and why they hid the days before it - {@link #WHY_PLACEHOLDERS}, or null for 1.0.9's reason. Immutable.
	 */
	public static final class AutoCut
	{
		private final LocalDate from;
		@Nullable
		private final String why;

		public AutoCut(final LocalDate from, @Nullable final String why)
		{
			this.from = Objects.requireNonNull(from, "from");
			this.why = why;
		}

		/** The first day the automatic cut showed. */
		public LocalDate from()
		{
			return from;
		}

		/** Why the days before it were hidden: {@link #WHY_PLACEHOLDERS}, or null for 1.0.9's reason. */
		@Nullable
		public String why()
		{
			return why;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof AutoCut))
			{
				return false;
			}
			final AutoCut that = (AutoCut) o;
			return from.equals(that.from) && Objects.equals(why, that.why);
		}

		@Override
		public int hashCode()
		{
			return 31 * from.hashCode() + Objects.hashCode(why);
		}
	}

	/**
	 * One placeholder judgement (1.1.1 part B): the LOCAL day a reading from a fresh bank read judged a series, and
	 * whether that read found placeholders with a quantity while the series held an earlier day - a restart. Made by the
	 * service in its fold, carried to the store with the write, applied at both sites by {@link #judged}. Immutable.
	 */
	public static final class PlaceholderCheck
	{
		private final LocalDate day;
		private final boolean restart;

		public PlaceholderCheck(final LocalDate day, final boolean restart)
		{
			this.day = Objects.requireNonNull(day, "day");
			this.restart = restart;
		}

		/** The day of the judging reading. */
		public LocalDate day()
		{
			return day;
		}

		/** Whether the days before {@link #day()} are hidden because they counted placeholders. */
		public boolean restart()
		{
			return restart;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof PlaceholderCheck))
			{
				return false;
			}
			final PlaceholderCheck that = (PlaceholderCheck) o;
			return restart == that.restart && day.equals(that.day);
		}

		@Override
		public int hashCode()
		{
			return 31 * day.hashCode() + (restart ? 1 : 0);
		}
	}
}
