package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * {@link RevisionRef} and the pure baseline selection - addendum L line L5's calendar day until contract 1.1.2, the
 * newest revision at least the window's span before the "now" table since (T1) - and the rule that keeps the index
 * (T2).
 *
 * <p>Every fixture below is a REAL revision of {@code Module:GEPrices/data.json} taken from
 * {@code docs/research/bank-price-movement-calibration-2026-09-08.md} and from addendum L's own evidence lines,
 * with the unix seconds computed from the published ISO timestamps. The load-bearing one is 15329323: Riblet15,
 * saved 2026-09-03T06:46Z with the comment "new items with initial ge prices", carrying the table Jagex stamped
 * on 2026-09-02 (L-E). It is the shape that makes "the newest revision of the date" the wrong rule and "the
 * newest BOT revision of the date" the right one.
 *
 * <p>Two fixtures are deliberately SYNTHETIC and marked so: a human edit saved after the bot's daily run, and a
 * date carrying two bot runs. Neither was observed (exactly one bot run per calendar day on 247 of 247 days),
 * but the rule has to answer for them, and they are the only fixtures that tell "prefer the bot" apart from
 * "take the last of the date".
 */
public class RevisionRefTest
{
	// The real history, newest first (calibration doc; seconds computed from the published ISO timestamps).
	/** 15334656 @ 2026-09-08T09:25:12Z. */
	private static final RevisionRef BOT_0908 = bot(15_334_656L, 1_788_859_512L);
	/** 15333448 @ 2026-09-07T19:55:12Z - the revision addendum K's own example resolved to. */
	private static final RevisionRef BOT_0907 = bot(15_333_448L, 1_788_810_912L);
	/** 15332677 @ 2026-09-06T19:15:12Z. */
	private static final RevisionRef BOT_0906 = bot(15_332_677L, 1_788_722_112L);
	/** 15331360 @ 2026-09-05T08:45:11Z. */
	private static final RevisionRef BOT_0905 = bot(15_331_360L, 1_788_597_911L);
	/** 15330652 @ 2026-09-04T07:45:12Z. */
	private static final RevisionRef BOT_0904 = bot(15_330_652L, 1_788_507_912L);
	/** 15330300 @ 2026-09-03T21:45:11Z. */
	private static final RevisionRef BOT_0903 = bot(15_330_300L, 1_788_471_911L);
	/** 15290673 @ 2026-08-09T04:45:13Z - what a 30d window landed on during the calibration run. */
	private static final RevisionRef BOT_0809 = bot(15_290_673L, 1_786_250_713L);
	/** 15148129 @ 2026-03-12T18:26:53Z - what a 180d window landed on. */
	private static final RevisionRef BOT_0312 = bot(15_148_129L, 1_773_340_013L);

	/**
	 * 15329323 @ 2026-09-03T06:46:00Z, Riblet15, "new items with initial ge prices" - REAL, and the reason the
	 * bot preference exists: its body carries 2026-09-02's prices (L-E).
	 */
	private static final RevisionRef HUMAN_0903 = new RevisionRef(15_329_323L, 1_788_417_960L, "Riblet15",
		"new items with initial ge prices");

	/** SYNTHETIC: a human edit saved at 2026-09-04T18:00:00Z, i.e. AFTER that day's bot run at 07:45. */
	private static final RevisionRef HUMAN_AFTER_BOT_0904 = new RevisionRef(15_330_999L, 1_788_544_800L,
		"Spineweilder", "fix a name");

	/** SYNTHETIC: a second bot run on 2026-09-03, five minutes before the real one at 21:45:11Z. */
	private static final RevisionRef BOT_0903_EARLY = bot(15_330_299L, 1_788_471_605L);

	private static final LocalDate SEP_2 = LocalDate.of(2026, 9, 2);
	private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
	private static final LocalDate SEP_4 = LocalDate.of(2026, 9, 4);
	private static final LocalDate SEP_7 = LocalDate.of(2026, 9, 7);
	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);

	private static final List<RevisionRef> HISTORY = Collections.unmodifiableList(Arrays.asList(
		BOT_0908, BOT_0907, BOT_0906, BOT_0905, BOT_0904, BOT_0903, HUMAN_0903, BOT_0809, BOT_0312));

	// ---------------------------------------------------------------- the fields

	@Test
	public void aReferenceCarriesTheFourThingsTheIndexAsksFor()
	{
		assertEquals(15_333_448L, BOT_0907.revId());
		assertEquals(1_788_810_912L, BOT_0907.editSeconds());
		assertEquals("Gaz GEBot", BOT_0907.user());
		assertEquals("GE update", BOT_0907.comment());
	}

	/** A suppressed edit carries no user and an edit can be saved with no comment; neither may be null here. */
	@Test
	public void aMissingUserOrCommentReadsAsEmptyRatherThanNull()
	{
		final RevisionRef bare = new RevisionRef(1L, 2L, null, null);

		assertEquals("", bare.user());
		assertEquals("", bare.comment());
		assertFalse("nothing about a nameless edit says price bot", bare.isBot());
	}

	// ---------------------------------------------------------------- isBot (L5, evidence L-E)

	@Test
	public void eitherBotMarkerOnItsOwnIsEnough()
	{
		assertTrue("the bot account", new RevisionRef(1L, 2L, "Gaz GEBot", "").isBot());
		assertTrue("the comment, measured on 200 of 200 bot revisions",
			new RevisionRef(1L, 2L, "SomeoneElse", "GE update").isBot());
		assertTrue(BOT_0907.isBot());
		assertEquals("Gaz GEBot", RevisionRef.BOT_USER);
		assertEquals("GE update", RevisionRef.BOT_COMMENT);
	}

	@Test
	public void aHumanMaintenanceEditIsNotTheBot()
	{
		assertFalse("Riblet15's edit is the one that carries the PREVIOUS day's prices (L-E)", HUMAN_0903.isBot());
		assertFalse(new RevisionRef(1L, 2L, "Coopermor", "adding new items").isBot());
		assertFalse("no human typed this comment on 24 of 24 sampled edits",
			new RevisionRef(1L, 2L, "Spineweilder", "ge update prices").isBot());
	}

	@Test
	public void theMarkersAreTrimmedButNotCaseFolded()
	{
		assertTrue(new RevisionRef(1L, 2L, "  Gaz GEBot  ", null).isBot());
		assertTrue(new RevisionRef(1L, 2L, null, "\tGE update\n").isBot());
		assertFalse("a different account is a different account, whatever its case",
			new RevisionRef(1L, 2L, "gaz gebot", "").isBot());
	}

	// ---------------------------------------------------------------- editDay (L9: UTC, never the machine zone)

	@Test
	public void theEditDayIsTheUtcDateOfTheTimestamp()
	{
		assertEquals(SEP_8, BOT_0908.editDay());
		assertEquals(SEP_7, BOT_0907.editDay());
		assertEquals(SEP_3, HUMAN_0903.editDay());
		assertEquals(LocalDate.of(2026, 3, 12), BOT_0312.editDay());
	}

	/**
	 * The two instants a local-zone implementation gets wrong. 2026-09-09T00:00:00Z is still 2026-09-08 in every
	 * zone west of Greenwich - including this workspace's - so an implementation reading
	 * {@code ZoneId.systemDefault()} fails here and the guide day would be a whole day out for the user.
	 */
	@Test
	public void midnightUtcBelongsToTheNewDayEvenWestOfGreenwich()
	{
		// 2026-09-08T23:59:59Z and 2026-09-09T00:00:00Z.
		assertEquals(SEP_8, new RevisionRef(1L, 1_788_911_999L, null, null).editDay());
		assertEquals(LocalDate.of(2026, 9, 9), new RevisionRef(1L, 1_788_912_000L, null, null).editDay());
	}

	// ---------------------------------------------------------------- pickThen (contract 1.1.2, T1)

	/** The last second of a UTC date, unix seconds. */
	private static long endOf(final LocalDate day)
	{
		return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1L;
	}

	@Test
	public void theBaselineIsTheNewestRevisionAtOrBeforeTheTarget()
	{
		assertSame("at or before: a revision saved at the target second counts", BOT_0907,
			RevisionRef.pickThen(HISTORY, BOT_0907.editSeconds()));
		assertSame("one second earlier it is too new", BOT_0906,
			RevisionRef.pickThen(HISTORY, BOT_0907.editSeconds() - 1L));
		assertSame(BOT_0908, RevisionRef.pickThen(HISTORY, BOT_0908.editSeconds() + 86_400L));
		assertSame(BOT_0904, RevisionRef.pickThen(HISTORY, endOf(SEP_4)));
	}

	/**
	 * The discriminating case. A human edit saved AFTER the day's bot run is the newest revision old enough, and its
	 * body is the table it was saved over (L-E). The bot run of the same day carries those prices without the doubt.
	 */
	@Test
	public void aHumanEditSavedAfterTheBotRunLosesToThatDaysBot()
	{
		final List<RevisionRef> index = withExtra(HUMAN_AFTER_BOT_0904);

		assertTrue("the fixture only proves anything if the human edit really is the newer of the two",
			HUMAN_AFTER_BOT_0904.editSeconds() > BOT_0904.editSeconds());
		assertSame(BOT_0904, RevisionRef.pickThen(index, endOf(SEP_4)));
	}

	/**
	 * The preference stays inside the day: Riblet15's 06:46 edit is the newest revision before noon on 2026-09-03 and
	 * that day's bot ran at 21:45, so the edit is taken - its table is at worst one step stale - rather than the bot run
	 * of 9 Aug, 25 days older.
	 */
	@Test
	public void aHumanEditWhoseDayHasNoBotRunOldEnoughIsTaken()
	{
		final long noonSep3 = endOf(SEP_2) + 1L + 12L * 3_600L;

		assertSame(HUMAN_0903, RevisionRef.pickThen(HISTORY, noonSep3));
		assertSame("the evening of the 3rd: the bot's run", BOT_0903, RevisionRef.pickThen(HISTORY, endOf(SEP_3)));
	}

	/** Eight runs a day: the newest run old enough, not the last of its date. */
	@Test
	public void aDayWithTwoBotRunsAnswersTheNewestOneOldEnough()
	{
		final List<RevisionRef> index = withExtra(BOT_0903_EARLY);

		assertSame(BOT_0903, RevisionRef.pickThen(index, endOf(SEP_3)));
		assertSame("before the later run, the earlier one - the calendar rule would have said the later",
			BOT_0903_EARLY, RevisionRef.pickThen(index, BOT_0903.editSeconds() - 1L));
	}

	/**
	 * A body's own {@code %LAST_UPDATE%} replaces the save time once it is known: a table stamped ten minutes before
	 * its save qualifies ten minutes sooner, and one stamped after the target - the five minutes of lead the client
	 * allows - is too new however early it was saved.
	 */
	@Test
	public void aKnownTableTimeStandsInForTheSaveTime()
	{
		final long target = BOT_0907.editSeconds() - 300L;
		final Map<Long, Long> earlier = Collections.singletonMap(BOT_0907.revId(), BOT_0907.editSeconds() - 600L);
		final Map<Long, Long> later = Collections.singletonMap(BOT_0907.revId(), BOT_0907.editSeconds() + 200L);

		assertSame("by its save time 19:55 is after 19:50", BOT_0906, RevisionRef.pickThen(HISTORY, target));
		assertSame("its table was stamped 19:45", BOT_0907, RevisionRef.pickThen(HISTORY, target, earlier, 0L));
		assertSame(BOT_0907, RevisionRef.pickThen(HISTORY, BOT_0907.editSeconds()));
		assertSame("stamped after the target: too new", BOT_0906,
			RevisionRef.pickThen(HISTORY, BOT_0907.editSeconds(), later, 0L));

		assertEquals(BOT_0907.editSeconds() - 600L, BOT_0907.timeIn(earlier));
		assertEquals("no map", BOT_0907.editSeconds(), BOT_0907.timeIn(null));
		assertEquals("another revision's time", BOT_0906.editSeconds(), BOT_0906.timeIn(earlier));
		assertEquals("a refused body (null) teaches nothing", BOT_0907.editSeconds(),
			BOT_0907.timeIn(Collections.singletonMap(BOT_0907.revId(), (Long) null)));
		assertEquals(BOT_0907.editSeconds(), BOT_0907.timeIn(Collections.singletonMap(BOT_0907.revId(), 0L)));
	}

	/** The service's one retry past a body the client refused: the same rule with that revision left out. */
	@Test
	public void theSkippedRevisionIsLeftOut()
	{
		assertSame(BOT_0906, RevisionRef.pickThen(HISTORY, endOf(SEP_7), null, BOT_0907.revId()));
		assertSame(BOT_0907, RevisionRef.pickThen(HISTORY, endOf(SEP_7), null, 0L));
	}

	/**
	 * A window whose target predates the whole history is not a failure - it is "No 180d history" on the panel
	 * (L11). Answering the oldest revision instead would silently label months-old prices as 180 days old.
	 */
	@Test
	public void aTargetOlderThanTheWholeIndexAnswersNothing()
	{
		assertNull(RevisionRef.pickThen(HISTORY, BOT_0312.editSeconds() - 1L));
		assertNull(RevisionRef.pickThen(HISTORY, 0L));
		assertSame(BOT_0312, RevisionRef.pickThen(HISTORY, BOT_0312.editSeconds()));
	}

	@Test
	public void theRuleIsIndependentOfTheOrderTheIndexArrivesIn()
	{
		final List<RevisionRef> shuffled = new ArrayList<>(HISTORY);
		Collections.reverse(shuffled);
		Collections.swap(shuffled, 0, 4);

		assertSame(BOT_0907, RevisionRef.pickThen(shuffled, endOf(SEP_7)));
		assertSame(BOT_0903, RevisionRef.pickThen(shuffled, endOf(SEP_3)));
		assertSame(BOT_0809, RevisionRef.pickThen(shuffled, endOf(LocalDate.of(2026, 8, 20))));
	}

	@Test
	public void nothingToPickFromAnswersNullRatherThanThrowing()
	{
		assertNull(RevisionRef.pickThen(null, endOf(SEP_7)));
		assertNull(RevisionRef.pickThen(Collections.emptyList(), endOf(SEP_7)));
		assertNull(RevisionRef.pickThen(Collections.singletonList(null), endOf(SEP_7)));
	}

	@Test
	public void aNullEntryInTheIndexIsSteppedOverRatherThanFatal()
	{
		final List<RevisionRef> holed = Arrays.asList(null, BOT_0908, null, BOT_0907, null);

		assertSame(BOT_0907, RevisionRef.pickThen(holed, endOf(SEP_7)));
	}

	// ---------------------------------------------------------------- pruned (contract 1.1.2, T2)

	/**
	 * Eight bot runs a day for thirty days, plus a human edit saved after the last run of an old day: the last
	 * {@link RevisionRef#FULL_DAYS} days before the newest revision are kept whole, and every older day keeps ONE - its
	 * last bot run, never the human edit after it.
	 */
	@Test
	public void theIndexKeepsEveryRevisionOfTheLastEightDaysAndOneADayBefore()
	{
		final long start = endOf(LocalDate.of(2026, 9, 7)) + 1L;
		final List<RevisionRef> index = eightADay(start, 30);
		final RevisionRef lateHuman = new RevisionRef(99L, start + 5L * 86_400L + 23L * 3_600L, "Riblet15", "names");
		index.add(lateHuman);

		final List<RevisionRef> kept = RevisionRef.pruned(index);

		final long newest = kept.get(0).editSeconds();
		final long fullFrom = newest - RevisionRef.FULL_DAYS * 86_400L;
		final Map<LocalDate, RevisionRef> lastBotOfDay = new HashMap<>();
		for (final RevisionRef ref : index)
		{
			final RevisionRef held = lastBotOfDay.get(ref.editDay());
			// The day the eight days begin in counts only its runs before them: the rest are kept whole.
			if (ref.isBot() && ref.editSeconds() < fullFrom && (held == null || ref.editSeconds() > held.editSeconds()))
			{
				lastBotOfDay.put(ref.editDay(), ref);
			}
		}
		int full = 0;
		for (final RevisionRef ref : index)
		{
			if (ref.editSeconds() >= fullFrom)
			{
				full++;
				assertTrue("every revision of the last eight days stays: " + ref, kept.contains(ref));
			}
		}
		final Set<LocalDate> oldDays = new HashSet<>();
		for (final RevisionRef ref : kept)
		{
			if (ref.editSeconds() < fullFrom)
			{
				assertTrue("one per older day: " + ref, oldDays.add(ref.editDay()));
				assertSame("the day's LAST bot run", lastBotOfDay.get(ref.editDay()), ref);
			}
		}
		assertFalse("the human edit after the day's last run is not the day's keeper", kept.contains(lateHuman));
		assertEquals(full + oldDays.size(), kept.size());
		assertTrue("thirty days at eight a day keep about eight days whole and a line a day before: " + kept.size(),
			kept.size() < 100);
		for (int i = 1; i < kept.size(); i++)
		{
			assertTrue("newest first", RevisionRef.NEWEST_FIRST.compare(kept.get(i - 1), kept.get(i)) < 0);
		}
	}

	/**
	 * The index the 1.1.1 build stored - its cap was 400 LINES, which at eight a day reaches fifty days - prunes to the
	 * rule, and a day with no bot run at all keeps its last revision of any kind. Nothing past
	 * {@link RevisionRef#KEPT_DAYS} days is kept, and pruning twice changes nothing.
	 */
	@Test
	public void aFourHundredLineIndexPrunesToTheRuleAndNothingOlderThanFourHundredDaysStays()
	{
		final long start = endOf(LocalDate.of(2026, 8, 18)) + 1L;
		final List<RevisionRef> index = eightADay(start, 50);
		assertEquals("the old cap's worth", 400, index.size());
		final RevisionRef humanOnlyDay = new RevisionRef(7L, start - 3L * 86_400L, "Coopermor", "items");
		final RevisionRef ancient = bot(6L, start - 500L * 86_400L);
		index.add(humanOnlyDay);
		index.add(ancient);

		final List<RevisionRef> kept = RevisionRef.pruned(index);

		assertTrue("a day with no bot run keeps its human edit", kept.contains(humanOnlyDay));
		assertFalse("500 days back is past the 400 kept", kept.contains(ancient));
		assertEquals("eight days back whole - 64 runs, and 21:10 of the day the eight days start in - one for each of the"
			+ " 42 days before (that day's 18:10 among them), and the human-only day", 8 * 8 + 1 + 42 + 1, kept.size());
		assertEquals(kept, RevisionRef.pruned(kept));
		assertTrue(RevisionRef.pruned(null).isEmpty());
		assertTrue(RevisionRef.pruned(Arrays.asList(null, null)).isEmpty());
		assertEquals("a duplicate id is kept once", 1,
			RevisionRef.pruned(Arrays.asList(BOT_0907, BOT_0907, null)).size());
	}

	/** Eight bot runs a day from {@code start} for {@code days} days, three hours apart, revision ids rising with time. */
	private static List<RevisionRef> eightADay(final long start, final int days)
	{
		final List<RevisionRef> index = new ArrayList<>();
		long revId = 15_000_000L;
		for (int d = 0; d < days; d++)
		{
			for (int k = 0; k < 8; k++)
			{
				index.add(bot(revId++, start + d * 86_400L + k * 3L * 3_600L + 600L));
			}
		}
		return index;
	}

	// ---------------------------------------------------------------- ordering and value semantics

	@Test
	public void theComparatorPutsTheNewestFirstAndBreaksTiesOnTheRevisionId()
	{
		final List<RevisionRef> jumbled = new ArrayList<>(Arrays.asList(BOT_0312, BOT_0908, BOT_0903, BOT_0907));
		jumbled.sort(RevisionRef.NEWEST_FIRST);

		assertEquals(Arrays.asList(BOT_0908, BOT_0907, BOT_0903, BOT_0312), jumbled);

		final RevisionRef sameSecondLowerId = bot(15_333_447L, 1_788_810_912L);
		final List<RevisionRef> tied = new ArrayList<>(Arrays.asList(sameSecondLowerId, BOT_0907));
		tied.sort(RevisionRef.NEWEST_FIRST);
		assertSame("two edits in the same second must not be able to swap places between two reads",
			BOT_0907, tied.get(0));
	}

	@Test
	public void referencesCompareByValue()
	{
		assertEquals(bot(15_333_448L, 1_788_810_912L), BOT_0907);
		assertEquals(bot(15_333_448L, 1_788_810_912L).hashCode(), BOT_0907.hashCode());
		assertNotEquals(BOT_0907, BOT_0908);
		assertNotEquals(BOT_0907, new RevisionRef(15_333_448L, 1_788_810_912L, "Riblet15", "GE update"));
		assertNotEquals(BOT_0907, null);
		assertNotEquals(BOT_0907, "15333448");
		assertEquals("a null user and an empty one are the same absence",
			new RevisionRef(1L, 2L, null, null), new RevisionRef(1L, 2L, "", ""));
	}

	/** The dev bridge echoes this; it has to name the day and whether the bot wrote it, not just an id. */
	@Test
	public void toStringNamesTheDayAndTheBotFlag()
	{
		final String text = BOT_0907.toString();

		assertTrue(text, text.contains("15333448"));
		assertTrue(text, text.contains("2026-09-07"));
		assertTrue(text, text.contains("bot=true"));
		assertTrue(HUMAN_0903.toString(), HUMAN_0903.toString().contains("bot=false"));
	}

	// ---------------------------------------------------------------- fixtures

	private static RevisionRef bot(final long revId, final long editSeconds)
	{
		return new RevisionRef(revId, editSeconds, RevisionRef.BOT_USER, RevisionRef.BOT_COMMENT);
	}

	/** The real history plus one synthetic revision, in no particular order (the rule does not need one). */
	private static List<RevisionRef> withExtra(final RevisionRef extra)
	{
		final List<RevisionRef> index = new ArrayList<>(HISTORY);
		index.add(extra);
		return index;
	}
}
