package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * {@link RevisionRef} and the pure baseline selection of addendum L line L5 - the rule that replaced K's
 * "newest revision at or before now minus the window", which six analysts measured as a coin flip (L-C).
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

	// ---------------------------------------------------------------- pickThen (L5)

	@Test
	public void theBaselineForADateIsThatDatesBotRevision()
	{
		assertSame(BOT_0907, RevisionRef.pickThen(HISTORY, SEP_7));
		assertSame(BOT_0908, RevisionRef.pickThen(HISTORY, SEP_8));
		assertSame(BOT_0904, RevisionRef.pickThen(HISTORY, SEP_4));
	}

	/**
	 * The fixture addendum L was written around: 2026-09-03 holds a human edit at 06:46 and the bot's run at
	 * 21:45. The bot's table is 09-03's prices; Riblet15's is 09-02's.
	 */
	@Test
	public void aDateHoldingBothAHumanEditAndABotRunAnswersTheBotRun()
	{
		assertSame(BOT_0903, RevisionRef.pickThen(HISTORY, SEP_3));
	}

	/**
	 * The discriminating case. A human edit saved AFTER the day's bot run is the LAST revision of the date, so
	 * "take the newest revision of the date" would answer it - and its body is the previous day's table.
	 * Preferring the bot is what makes the rule right rather than merely last.
	 */
	@Test
	public void aHumanEditSavedAfterTheBotRunStillLosesToIt()
	{
		final List<RevisionRef> index = withExtra(HUMAN_AFTER_BOT_0904);

		assertTrue("the fixture only proves anything if the human edit really is the newer of the two",
			HUMAN_AFTER_BOT_0904.editSeconds() > BOT_0904.editSeconds());
		assertSame(BOT_0904, RevisionRef.pickThen(index, SEP_4));
	}

	@Test
	public void aDateWithTwoBotRunsAnswersTheLaterOne()
	{
		final List<RevisionRef> index = withExtra(BOT_0903_EARLY);

		assertSame(BOT_0903, RevisionRef.pickThen(index, SEP_3));
	}

	/**
	 * With no bot revision on the date at all, the newest revision of that date is still better than walking
	 * back a whole day: its table is at worst one Jagex day stale, which the {@code %LAST_UPDATE%} check
	 * downstream (L5) then notices.
	 */
	@Test
	public void aDateWithOnlyAHumanEditAnswersTheHumanEdit()
	{
		final List<RevisionRef> onlyHuman = Arrays.asList(BOT_0904, HUMAN_0903, BOT_0906);

		assertSame(HUMAN_0903, RevisionRef.pickThen(onlyHuman, SEP_3));
	}

	/**
	 * The "walk back a date at a time" step of L5. No revision was saved on 2026-08-20, so a 30d window landing
	 * there takes the newest date that does have one - and the caller labels the row with the day it really got.
	 */
	@Test
	public void aDateWithNoRevisionWalksBackToTheNewestOlderDate()
	{
		assertSame(BOT_0809, RevisionRef.pickThen(HISTORY, LocalDate.of(2026, 8, 20)));
		assertSame("walking back must never step FORWARD to a newer date",
			BOT_0312, RevisionRef.pickThen(HISTORY, LocalDate.of(2026, 6, 1)));
	}

	/**
	 * A window whose target predates the whole history is not a failure - it is "No 180d history" on the panel
	 * (L11). Answering the oldest revision instead would silently label months-old prices as 180 days old.
	 */
	@Test
	public void aTargetOlderThanTheWholeIndexAnswersNothing()
	{
		assertNull(RevisionRef.pickThen(HISTORY, LocalDate.of(2026, 3, 11)));
		assertNull(RevisionRef.pickThen(HISTORY, LocalDate.of(2020, 1, 1)));
	}

	@Test
	public void theRuleIsIndependentOfTheOrderTheIndexArrivesIn()
	{
		final List<RevisionRef> shuffled = new ArrayList<>(HISTORY);
		Collections.reverse(shuffled);
		Collections.swap(shuffled, 0, 4);

		assertSame(BOT_0907, RevisionRef.pickThen(shuffled, SEP_7));
		assertSame(BOT_0903, RevisionRef.pickThen(shuffled, SEP_3));
		assertSame(BOT_0809, RevisionRef.pickThen(shuffled, LocalDate.of(2026, 8, 20)));
	}

	@Test
	public void nothingToPickFromAnswersNullRatherThanThrowing()
	{
		assertNull(RevisionRef.pickThen(null, SEP_7));
		assertNull(RevisionRef.pickThen(Collections.emptyList(), SEP_7));
		assertNull("no anchor day yet means no target date and so no baseline",
			RevisionRef.pickThen(HISTORY, null));
		assertNull(RevisionRef.pickThen(Collections.singletonList(null), SEP_7));
	}

	@Test
	public void aNullEntryInTheIndexIsSteppedOverRatherThanFatal()
	{
		final List<RevisionRef> holed = Arrays.asList(null, BOT_0908, null, BOT_0907, null);

		assertSame(BOT_0907, RevisionRef.pickThen(holed, SEP_7));
	}

	// ---------------------------------------------------------------- the L5 retry candidates

	/**
	 * When the body that came back carries an older {@code %LAST_UPDATE%} than the date asked for, L5 retries
	 * ONCE with the revision underneath it on the same date.
	 */
	@Test
	public void thePreviousRevisionOnTheSameDateIsTheFirstRetryCandidate()
	{
		assertSame(HUMAN_0903, RevisionRef.previousOn(HISTORY, BOT_0903));
		assertNull("Riblet15's edit is the first of its date - there is nothing under it",
			RevisionRef.previousOn(HISTORY, HUMAN_0903));
		assertNull("a date with one revision has no previous one", RevisionRef.previousOn(HISTORY, BOT_0907));
	}

	@Test
	public void thePreviousRevisionNeverCrossesADateBoundary()
	{
		// 15330300 is the first revision of 2026-09-03 once Riblet15's edit is taken out of the index.
		final List<RevisionRef> withoutHuman = Arrays.asList(BOT_0904, BOT_0903, BOT_0809);

		assertNull(RevisionRef.previousOn(withoutHuman, BOT_0903));
	}

	@Test
	public void previousOnToleratesNothingToLookIn()
	{
		assertNull(RevisionRef.previousOn(null, BOT_0903));
		assertNull(RevisionRef.previousOn(HISTORY, null));
		assertNull(RevisionRef.previousOn(Arrays.asList((RevisionRef) null), BOT_0903));
	}

	/**
	 * The second retry candidate: when the target date's only revision was a human edit carrying the previous
	 * day's table, the day AFTER it is where that date's prices were finally published.
	 */
	@Test
	public void theNewestBotRevisionOfADateIsTheSecondRetryCandidate()
	{
		assertSame(BOT_0903, RevisionRef.newestBotOn(withExtra(BOT_0903_EARLY), SEP_3));
		assertSame(BOT_0904, RevisionRef.newestBotOn(withExtra(HUMAN_AFTER_BOT_0904), SEP_4));
		assertNull("2026-09-02 has no revision at all in this history",
			RevisionRef.newestBotOn(HISTORY, SEP_2));
		assertNull(RevisionRef.newestBotOn(Arrays.asList(HUMAN_0903), SEP_3));
		assertNull(RevisionRef.newestBotOn(null, SEP_3));
		assertNull(RevisionRef.newestBotOn(HISTORY, null));
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
