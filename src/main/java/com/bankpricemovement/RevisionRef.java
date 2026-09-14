package com.bankpricemovement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One line of the guide table's revision history - who published it, when, and under what edit comment
 * (addendum L, contract line L4) - plus the pure selection rule that turns a history into the ONE revision a
 * window's baseline must be read from (L5, {@link #pickThen}).
 *
 * <p><b>Why the history is indexed at all.</b> Addendum K asked the wiki for "the newest revision at or before
 * now minus the window" and used it directly. Six analysts measured that selector against live data and it is a
 * coin flip: the bot writes day D's table at a RANDOM hour of day D (02:11-22:20 UTC over 247 days, median about
 * 12:00), so "24 hours ago" lands on day D-1's table only 50-56 % of clock hours (L-C). On the user's 524-item
 * bank a wrong day changes the printed percent on 40-60 % of rows and flips its sign on 3-5 %; a 1d window spans
 * ZERO guide steps 14 % of the time and TWO steps 14-43 % of the time. The fix is to stop asking about instants
 * altogether: one cheap call brings back about 232 days of history ({@code rvlimit=250}, ~3.6 KB gzipped), and
 * the baseline is chosen by CALENDAR DAY from that list, locally, whenever the anchor day or the window changes
 * (L4, L10).
 *
 * <p><b>Why a bot revision is preferred over a human one on the same date.</b> The revision timestamp is not the
 * data day (L-E): human maintenance edits - Riblet15, Spineweilder, Coopermor, 24 of 194 in-window revisions -
 * save the page with the PREVIOUS day's prices still in it, under the next day's timestamp. Lead check:
 * revision 15329323 (Riblet15, 2026-09-03T06:46Z, "new items with initial ge prices") carries a
 * {@code %LAST_UPDATE_F%} of "02 September 2026 07:21:23". Bot revisions all carry the comment
 * {@value #BOT_COMMENT} (200/200) and human ones never do (0/24), so the comment - or the bot's user name -
 * separates them cleanly. The preference is only the FIRST guess: {@code PriceService} still checks the
 * {@code %LAST_UPDATE%} day of the body it gets back (L5), so a misjudged edit costs one retry, never a wrong
 * number on a row.
 *
 * <p>Immutable and safe to share: built on an OkHttp dispatcher thread by
 * {@code GuidePriceClient.parseRevisionIndex}, kept by the service, read while rows are computed, and written to
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
	 * When the revision was saved, unix seconds. Cache identity and nothing else (L7): the day the prices in it
	 * belong to is {@code GuideSnapshot.dataDay()}, read from the body's {@code %LAST_UPDATE%}.
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
	 * mistaken for a human one only loses the preference (the newest revision of the date is then taken), and
	 * the {@code %LAST_UPDATE%} check downstream (L5) catches a stale body either way.
	 */
	public boolean isBot()
	{
		return BOT_USER.equals(user.trim()) || BOT_COMMENT.equals(comment.trim());
	}

	/**
	 * The UTC calendar date this revision was saved on. UTC and never {@code ZoneId.systemDefault()} (L9): the
	 * Jagex guide price steps once a day near the top of the UTC day (rollover observed 00:47-02:06 UTC, L-A) and
	 * the wiki's bot publishes that step later the same UTC day (02:11-22:20, L-C), so every date in this plugin's
	 * selection maths is a UTC date; using the machine's zone would move a user in Sydney a whole day off.
	 */
	public LocalDate editDay()
	{
		return dayOf(editSeconds);
	}

	// ---------------------------------------------------------------- L5: the pure selection rule

	/**
	 * The revision whose body holds the guide prices of the calendar day {@code target} - the "then" side of a
	 * window (L5).
	 *
	 * <p>The rule, in order:
	 * <ol>
	 * <li>consider only revisions saved on {@code target} or earlier;</li>
	 * <li>take the NEWEST such date that has any revision at all - normally {@code target} itself (465 of 465
	 * dates sampled had one), otherwise this is the "walk back a date at a time" step of L5, and the caller
	 * reports the date it actually landed on rather than the one it asked for;</li>
	 * <li>within that date, prefer the newest BOT revision; with none, take the newest revision of the date
	 * whoever made it.</li>
	 * </ol>
	 *
	 * <p>Answering null is a real outcome, not a failure: it means the whole index is NEWER than the target,
	 * which is what a 180d window looks like on a page whose history does not reach back that far. The panel
	 * shows "No 180d history" for it and the service treats it as data, not as an error (L11).
	 *
	 * <p>Pure and order-independent - the list is scanned, not assumed sorted - so it can be tested on its own
	 * and called from the service's executor, the dev bridge, or a test, with no clock and no network.
	 *
	 * @param index  the revision history, newest first by convention; null, empty and null entries are tolerated
	 * @param target the UTC calendar day whose prices are wanted, {@code MovementWindow.targetDate(anchorDay)};
	 *               null (no anchor day yet) answers null
	 * @return the revision to fetch, or null when nothing in the index is that old
	 */
	public static RevisionRef pickThen(final List<RevisionRef> index, final LocalDate target)
	{
		if (index == null || index.isEmpty() || target == null)
		{
			return null;
		}

		LocalDate day = null;
		RevisionRef bot = null;
		RevisionRef any = null;

		for (final RevisionRef ref : index)
		{
			if (ref == null)
			{
				continue;
			}

			final LocalDate refDay = ref.editDay();
			if (refDay.isAfter(target))
			{
				continue;
			}

			final int against = day == null ? 1 : refDay.compareTo(day);
			if (against > 0)
			{
				// A newer date than anything seen so far: it wins outright, so both candidates restart on it.
				day = refDay;
				any = ref;
				bot = ref.isBot() ? ref : null;
			}
			else if (against == 0)
			{
				if (isNewer(ref, any))
				{
					any = ref;
				}
				if (ref.isBot() && isNewer(ref, bot))
				{
					bot = ref;
				}
			}
		}

		return bot != null ? bot : any;
	}

	/**
	 * The first retry candidate of L5: the revision saved just BEFORE {@code chosen} on the same date, or null
	 * when {@code chosen} was the first of its date.
	 *
	 * <p>Wanted when the body that came back carries an OLDER {@code %LAST_UPDATE%} than the date asked for -
	 * a name-only edit saved after the bot's run, which republishes the previous table under a later timestamp
	 * (L-E). The revision underneath it on the same date is then the one holding that date's prices.
	 *
	 * @param index  the revision history; null, empty and null entries are tolerated
	 * @param chosen the revision that came back stale; null answers null
	 * @return the next revision down on that date, or null when there is none
	 */
	public static RevisionRef previousOn(final List<RevisionRef> index, final RevisionRef chosen)
	{
		if (index == null || chosen == null)
		{
			return null;
		}

		final LocalDate day = chosen.editDay();
		RevisionRef best = null;
		for (final RevisionRef ref : index)
		{
			if (ref == null || ref.revId == chosen.revId || !day.equals(ref.editDay()) || !isNewer(chosen, ref))
			{
				continue;
			}
			if (isNewer(ref, best))
			{
				best = ref;
			}
		}
		return best;
	}

	/**
	 * The second retry candidate of L5: the newest BOT revision of one date, used with the day AFTER the target
	 * when the target's only revision turned out to be a human edit carrying the wrong day's prices.
	 *
	 * @param index the revision history; null, empty and null entries are tolerated
	 * @param day   the UTC date to look on; null answers null
	 * @return that date's newest bot revision, or null when it has none
	 */
	public static RevisionRef newestBotOn(final List<RevisionRef> index, final LocalDate day)
	{
		if (index == null || day == null)
		{
			return null;
		}

		RevisionRef best = null;
		for (final RevisionRef ref : index)
		{
			if (ref == null || !ref.isBot() || !day.equals(ref.editDay()))
			{
				continue;
			}
			if (isNewer(ref, best))
			{
				best = ref;
			}
		}
		return best;
	}

	/**
	 * The UTC calendar date of a unix-seconds instant (L9). Spelled once here so every date in the selection
	 * path - this class, {@code GuideSnapshot.dataDay()}, {@code PriceMap.dataDay()} - is derived the same way.
	 */
	static LocalDate dayOf(final long seconds)
	{
		return Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.UTC).toLocalDate();
	}

	/** True when {@code candidate} was saved after {@code incumbent} (a null incumbent loses). */
	private static boolean isNewer(final RevisionRef candidate, final RevisionRef incumbent)
	{
		if (incumbent == null)
		{
			return true;
		}
		return NEWEST_FIRST.compare(candidate, incumbent) < 0;
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
