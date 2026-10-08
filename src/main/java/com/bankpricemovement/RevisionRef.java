package com.bankpricemovement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One line of the guide table's revision history - who published it, when, and under what edit comment
 * (addendum L, contract line L4) - plus the pure rules that turn a history into the ONE revision a window's
 * baseline must be read from ({@link #pickThen}, contract 1.1.2 T1) and keep that history small enough to hold
 * ({@link #pruned}, T2).
 *
 * <p><b>Why the history is indexed at all.</b> Addendum K asked the wiki for "the newest revision at or before
 * now minus the window" and used it directly, one request per window. One cheap call instead brings back 250
 * revisions ({@code rvlimit=250}, a few KB gzipped), and the baseline is chosen from that list LOCALLY whenever the
 * "now" or the window changes (L4, L10).
 *
 * <p><b>Why the choice is by TIME again</b> (contract 1.1.2, T1). Addendum L chose by CALENDAR DAY because the
 * wiki's price bot saved Jagex's table exactly once a day (247 of 247 days, L-C), so "the table of day D - N" was one
 * well-defined revision. Since 30 Sep 2026 the bot saves it about EIGHT times a day, each save moving 5-20 % of all
 * items, and "the newest revision of a date" became the last of eight: with the anchor rule's "+1 day" on top, the 1d
 * window compared RuneLite's price with a table about two and a half hours old and 368 of 499 of the user's rows
 * read 0.0 %. So "then" for window N is now the newest revision whose time is at least N x 24 h before the "now"
 * table's time - which in the once-a-day regime of September is still the previous day's table whenever that day's
 * run came earlier in the day.
 *
 * <p><b>A revision's time.</b> The table's own {@code %LAST_UPDATE%} when its body has been read (a baseline file
 * carries it as {@code bucketSeconds}, a fetched body as {@code dataSeconds}), and the save time {@link #editSeconds()}
 * until then: the bot saves about ten minutes after Jagex publishes (measured on the stored baselines, 5-10 min, in
 * both regimes), so the save time errs on the late side and a window chosen by it is never SHORTER than N x 24 h.
 * The service hands the known table times in ({@link #pickThen(List, long, Map, long)}); this class holds none.
 *
 * <p><b>Why a bot revision is preferred over a human one.</b> The revision timestamp is not the data time (L-E):
 * human maintenance edits - Riblet15, Spineweilder, Coopermor, 24 of 194 in-window revisions in 2026 - save the page
 * with an OLDER table still in it under a later timestamp. Lead check: revision 15329323 (Riblet15,
 * 2026-09-03T06:46Z, "new items with initial ge prices") carries a {@code %LAST_UPDATE_F%} of "02 September 2026
 * 07:21:23". Bot revisions all carry the comment {@value #BOT_COMMENT} (200/200) and human ones never do (0/24), so
 * the comment - or the bot's user name - separates them cleanly, and a human edit is chosen only where its day has no
 * bot revision old enough.
 *
 * <p>Immutable and safe to share: built on an OkHttp dispatcher thread by
 * {@code GuidePriceClient.parseRevisionPage}, kept by the service, read while rows are computed, and written to
 * {@code revindex.json} by {@code PriceStore}.
 */
public final class RevisionRef
{
	/** The wiki's price bot. Every daily table since the page was created is one of its edits (L-C, L-E). */
	public static final String BOT_USER = "Gaz GEBot";

	/**
	 * The edit comment the bot uses, measured on 200 of 200 bot revisions and 0 of 24 human ones (L-E). Either
	 * marker on its own is enough: the wiki could rename the account, and a human could in principle be given
	 * the bot flag, but nobody hand-types this comment.
	 */
	public static final String BOT_COMMENT = "GE update";

	/**
	 * Newest first - the order {@code rvdir=older} answers in, the order {@code revindex.json} keeps, and the
	 * order the dev bridge echoes. The revision id breaks a tie so two edits saved in the same second can never
	 * swap places between two reads of the same history.
	 *
	 * <p>{@link #pickThen} does NOT depend on it (it scans the whole list), so a shape change at the wiki can
	 * only make the echo untidy, never the baseline wrong.
	 */
	public static final Comparator<RevisionRef> NEWEST_FIRST = (a, b) ->
	{
		final int byTime = Long.compare(b.editSeconds, a.editSeconds);
		return byTime != 0 ? byTime : Long.compare(b.revId, a.revId);
	};

	private final long revId;
	private final long editSeconds;
	private final String user;
	private final String comment;

	/**
	 * @param revId       the wiki revision id ({@code revid}); the identity a content fetch asks for (L6)
	 * @param editSeconds when the revision was SAVED, unix seconds (the API's {@code timestamp}) - not the day
	 *                    its prices belong to, which only the body's {@code %LAST_UPDATE%} knows (L7)
	 * @param user        the editor's name, may be null (the API omits it for a suppressed edit) - stored as ""
	 * @param comment     the edit comment, may be null (an edit can be saved without one) - stored as ""
	 */
	public RevisionRef(final long revId, final long editSeconds, final String user, final String comment)
	{
		this.revId = revId;
		this.editSeconds = editSeconds;
		this.user = user == null ? "" : user;
		this.comment = comment == null ? "" : comment;
	}

	public long revId()
	{
		return revId;
	}

	/**
	 * When the revision was saved, unix seconds. The revision's time for {@link #pickThen} until its body has been
	 * read; from then on the body's own {@code %LAST_UPDATE%} ({@code GuideSnapshot.dataSeconds()}) is, because a
	 * human edit can carry an older table under a later save time (L-E).
	 */
	public long editSeconds()
	{
		return editSeconds;
	}

	/** The editor's name; "" when the API carried none. Never null. */
	public String user()
	{
		return user;
	}

	/** The edit comment; "" when the edit was saved without one. Never null. */
	public String comment()
	{
		return comment;
	}

	/**
	 * True when this looks like the price bot's daily run rather than a human's maintenance edit (L5): the
	 * {@value #BOT_USER} account, or the {@value #BOT_COMMENT} comment.
	 *
	 * <p>Both are matched on the trimmed text and case-sensitively, which is deliberately strict: a bot revision
	 * mistaken for a human one only loses the preference (the newest revision old enough is then taken whoever
	 * made it), and the body's own {@code %LAST_UPDATE%} is what the rows are labelled with either way.
	 */
	public boolean isBot()
	{
		return BOT_USER.equals(user.trim()) || BOT_COMMENT.equals(comment.trim());
	}

	/**
	 * The UTC calendar date this revision was saved on - the day {@link #pruned} files it under. UTC and never
	 * {@code ZoneId.systemDefault()} (L9): every date this plugin prints beside a guide table is a UTC date, and
	 * the machine's zone would move a user in Sydney a whole day off.
	 */
	public LocalDate editDay()
	{
		return dayOf(editSeconds);
	}

	// ---------------------------------------------------------------- T1: the pure selection rule

	/**
	 * This revision's time as {@link #pickThen} reads it (contract 1.1.2, T1): the table's own {@code %LAST_UPDATE%}
	 * when {@code tableSeconds} knows it (a positive value under this revision's id), and the save time otherwise.
	 *
	 * @param tableSeconds revision id to the table time read off its body; null, a missing id, a null value (a body
	 *                     the client refused) and a value of 0 or less all read as "not known"
	 */
	public long timeIn(final Map<Long, Long> tableSeconds)
	{
		final Long known = tableSeconds == null ? null : tableSeconds.get(revId);
		return known != null && known > 0L ? known : editSeconds;
	}

	/**
	 * {@link #pickThen(List, long, Map, long)} with no table time known and nothing skipped: every revision is at its
	 * save time.
	 */
	public static RevisionRef pickThen(final List<RevisionRef> index, final long targetSeconds)
	{
		return pickThen(index, targetSeconds, null, 0L);
	}

	/**
	 * The revision whose body holds the guide table a window's "then" side must be read from (contract 1.1.2, T1):
	 * the NEWEST revision whose time ({@link #timeIn}) is at or before {@code targetSeconds} - and when that one is a
	 * human edit, the newest BOT revision of the same UTC day at or before the target instead, if the day has one.
	 * Newest by that same time, the revision id breaking a tie.
	 *
	 * <p>{@code targetSeconds} is the "now" table's time minus N x 24 h ({@code MovementWindow.targetSeconds}), so the
	 * revision answered is never the one RuneLite's prices match, never one newer than it, and its table is at least
	 * N x 24 h older than the "now" table - which is what makes a 1d move a day's move rather than one Jagex step's.
	 *
	 * <p>Why the bot is preferred within its day and not across days: a human edit's body is the table it was saved
	 * over, never a newer one, so the bot run just before it on the same day carries the same prices without the
	 * edit's doubts (L-E); across a day there is no such bot, and walking back to an older day's run would trade a
	 * table at worst a step stale for one a day or more older. The bot saves eight times a day, so in practice the
	 * answer is the newest bot revision old enough.
	 *
	 * <p>Answering null is a real outcome, not a failure: it means the whole index is NEWER than the target, which is
	 * what a 180d window looks like on a history that does not reach back that far. The panel shows "No 180d history"
	 * for it and the service treats it as data, not as an error (L11).
	 *
	 * <p>Pure and order-independent - the list is scanned, not assumed sorted - so it can be tested on its own and
	 * called from the service's executor, the dev bridge, or a test, with no clock and no network.
	 *
	 * @param index         the revision history, newest first by convention; null, empty and null entries are tolerated
	 * @param targetSeconds the latest time, unix seconds, the "then" table may carry
	 * @param tableSeconds  the table times known so far, by revision id ({@link #timeIn}); may be null
	 * @param skipRevId     a revision to leave out - the service's one retry past a body the client refused; 0 skips
	 *                      nothing
	 * @return the revision to read, or null when nothing in the index is that old
	 */
	public static RevisionRef pickThen(final List<RevisionRef> index, final long targetSeconds,
		final Map<Long, Long> tableSeconds, final long skipRevId)
	{
		if (index == null || index.isEmpty())
		{
			return null;
		}

		RevisionRef any = null;
		long anyTime = 0L;
		for (final RevisionRef ref : index)
		{
			if (ref == null || (skipRevId != 0L && ref.revId == skipRevId))
			{
				continue;
			}
			final long time = ref.timeIn(tableSeconds);
			if (time <= targetSeconds && later(time, ref, anyTime, any))
			{
				any = ref;
				anyTime = time;
			}
		}
		if (any == null || any.isBot())
		{
			return any;
		}

		final LocalDate day = dayOf(anyTime);
		RevisionRef bot = null;
		long botTime = 0L;
		for (final RevisionRef ref : index)
		{
			if (ref == null || !ref.isBot() || (skipRevId != 0L && ref.revId == skipRevId))
			{
				continue;
			}
			final long time = ref.timeIn(tableSeconds);
			if (time <= targetSeconds && day.equals(dayOf(time)) && later(time, ref, botTime, bot))
			{
				bot = ref;
				botTime = time;
			}
		}
		return bot != null ? bot : any;
	}

	/** True when {@code candidate} at {@code time} is newer than {@code incumbent} at {@code incumbentTime}; a null incumbent loses. */
	private static boolean later(final long time, final RevisionRef candidate, final long incumbentTime,
		final RevisionRef incumbent)
	{
		if (incumbent == null)
		{
			return true;
		}
		return time != incumbentTime ? time > incumbentTime : candidate.revId > incumbent.revId;
	}

	// ---------------------------------------------------------------- T2: what the index keeps

	/** Seconds in a day, the unit both keeping rules are counted in. */
	static final long DAY_SECONDS = 24L * 60L * 60L;

	/**
	 * How many days back from the newest revision EVERY revision is kept: {@value} (contract 1.1.2, T2). The 1d and 7d
	 * windows choose among the day's eight tables, so every one of them must be there; one day of margin past the 7d
	 * window covers a "now" a few hours older than the newest revision (RuneLite behind the index).
	 */
	public static final int FULL_DAYS = 8;

	/**
	 * How many days back from the newest revision the index reaches at all: {@value}. Past {@link #FULL_DAYS} it keeps
	 * ONE revision per UTC day, which is all the 30d, 90d and 180d windows can tell apart, and 400 days is the 180d
	 * window with more than a year's margin - a few hundred lines, about 30 KB on disk.
	 */
	public static final int KEPT_DAYS = 400;

	/**
	 * The index as it is kept (contract 1.1.2, T2): every revision from the last {@link #FULL_DAYS} days before the
	 * newest one, and before that ONE per UTC day - the day's LAST bot revision, or its last revision of any kind when
	 * the day has no bot run - back to {@link #KEPT_DAYS} days; nothing older. Newest first, one entry per revision id
	 * (the first met wins), unmodifiable.
	 *
	 * <p>Why a rule and not a line count: the old cap of 400 lines was sized for the 1.1 revisions a day of the
	 * once-a-day era, and at eight a day it covered about ten days - the 180d window would have read "No 180d history"
	 * from late October. Counted from the NEWEST revision rather than the clock, so a stale clock cannot empty the
	 * index, and the rule is the same on every machine.
	 *
	 * @param refs any history, in any order; null and null entries are tolerated
	 */
	public static List<RevisionRef> pruned(final Collection<RevisionRef> refs)
	{
		final List<RevisionRef> sorted = new ArrayList<>();
		final Set<Long> seen = new HashSet<>();
		if (refs != null)
		{
			for (final RevisionRef ref : refs)
			{
				if (ref != null && seen.add(ref.revId))
				{
					sorted.add(ref);
				}
			}
		}
		if (sorted.isEmpty())
		{
			return Collections.emptyList();
		}
		sorted.sort(NEWEST_FIRST);

		final long newest = sorted.get(0).editSeconds;
		final long fullFrom = newest - FULL_DAYS * DAY_SECONDS;
		final long keptFrom = newest - KEPT_DAYS * DAY_SECONDS;
		final List<RevisionRef> kept = new ArrayList<>();
		final Map<LocalDate, RevisionRef> daily = new LinkedHashMap<>();
		for (final RevisionRef ref : sorted)
		{
			if (ref.editSeconds >= fullFrom)
			{
				kept.add(ref);
				continue;
			}
			if (ref.editSeconds < keptFrom)
			{
				continue;
			}
			// Newest first, so the first revision met on a day is that day's last; a bot run met later on the
			// same day still beats a human edit kept before it.
			final LocalDate day = ref.editDay();
			final RevisionRef held = daily.get(day);
			if (held == null || (!held.isBot() && ref.isBot()))
			{
				daily.put(day, ref);
			}
		}
		kept.addAll(daily.values());
		kept.sort(NEWEST_FIRST);
		return Collections.unmodifiableList(kept);
	}

	/**
	 * The UTC calendar date of a unix-seconds instant (L9). Spelled once here so every date in the selection
	 * path - this class, {@code GuideSnapshot.dataDay()}, {@code PriceMap.dataDay()} - is derived the same way.
	 */
	static LocalDate dayOf(final long seconds)
	{
		return Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof RevisionRef))
		{
			return false;
		}

		final RevisionRef other = (RevisionRef) o;
		return revId == other.revId
			&& editSeconds == other.editSeconds
			&& user.equals(other.user)
			&& comment.equals(other.comment);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(revId, editSeconds, user, comment);
	}

	@Override
	public String toString()
	{
		return "RevisionRef{revId=" + revId
			+ ", editDay=" + editDay()
			+ ", user=" + user
			+ ", bot=" + isBot() + '}';
	}
}
