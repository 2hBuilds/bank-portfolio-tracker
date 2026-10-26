package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.p;
import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * The transfer notes of release 1.2.2 (contract {@code contract-1.2.2-transfers-and-start-fresh-2026-10-25.md}, F1 and
 * F2): what a day marked as "a transfer" is in a {@link BankHistorySeries}, and what it does to every figure
 * {@link BankHistoryMath} draws or compares - the days before it drawn as if the gold had already moved, every CHANGE
 * (the chart's line and its change line, the History card, each row) corrected, every TOTAL kept, today never shifted - plus
 * the below-zero decision and the amount a note starts as.
 *
 * <p>The scenes: a main account that SENDS 200 on day 3, an alt that RECEIVES 200 on day 3, several notes, a coins note
 * with the coins switch off, a carried day, a base that goes to zero or below.
 */
public class BankHistoryTransfersTest
{
	private static final BankHistorySeries.Transfer SENT_200 = new BankHistorySeries.Transfer(-200L, false);
	private static final BankHistorySeries.Transfer GOT_200 = new BankHistorySeries.Transfer(200L, false);
	private static final BankHistorySeries.Transfer COINS_SENT_200 = new BankHistorySeries.Transfer(-200L, true);
	private static final ViewOptions NO_COINS = ViewOptions.DEFAULT.withCountCash(false);

	/** Five days: 1000, 1010, then 810 on day 3 (200 sent away and nothing else moved), 820, 830. */
	private static BankHistorySeries main()
	{
		return BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1000), p(sep(2), 2, 1010), p(sep(3), 3, 810),
			p(sep(4), 4, 820), p(sep(5), 5, 830)));
	}

	/** The alt: 5, 6, then 206 on day 3 (200 received), 207, 208. */
	private static BankHistorySeries alt()
	{
		return BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 5), p(sep(2), 2, 6), p(sep(3), 3, 206), p(sep(4), 4, 207),
			p(sep(5), 5, 208)));
	}

	/** A reading with a bank cash cell and a bank tradeable one. */
	private static BankHistoryPoint cash(final LocalDate day, final long readAt, final long tradeable, final long coins)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = tradeable;
		card[BankHistoryPoint.BANK_CASH] = coins;
		return new BankHistoryPoint(day, readAt, readAt, card, null);
	}

	private static List<Long> drawn(final List<BankHistoryMath.Day> days)
	{
		final Long[] out = new Long[days.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = days.get(i).drawnGp();
		}
		return Arrays.asList(out);
	}

	private static List<Long> real(final List<BankHistoryMath.Day> days)
	{
		final Long[] out = new Long[days.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = days.get(i).valueGp();
		}
		return Arrays.asList(out);
	}

	// ---------------------------------------------------------------- the notes on a series

	@Test
	public void aNoteIsPutReplacedAndTakenAway()
	{
		final BankHistorySeries marked = main().withTransfer(sep(3), SENT_200);
		assertEquals(Collections.singletonMap(sep(3), SENT_200), marked.transfers());
		assertEquals("one note a day: marking again replaces it", GOT_200,
			marked.withTransfer(sep(3), GOT_200).transfers().get(sep(3)));
		assertEquals(1, marked.withTransfer(sep(3), GOT_200).transfers().size());
		assertTrue("null takes it away", marked.withTransfer(sep(3), null).transfers().isEmpty());
		assertEquals("and the series is the one it was", main(), marked.withTransfer(sep(3), null));
		assertTrue("so does a note of 0 gp", marked.withTransfer(sep(3), new BankHistorySeries.Transfer(0L, true))
			.transfers().isEmpty());
		assertSame("the same note again changes nothing", marked, marked.withTransfer(sep(3), SENT_200));
		assertSame("nor does a null day", marked, marked.withTransfer(null, SENT_200));
	}

	@Test
	public void aNoteBelongsToAReading()
	{
		final BankHistorySeries s = main();
		assertSame("a day with no reading answers the series", s, s.withTransfer(sep(20), SENT_200));
		assertSame("an empty series has nothing to mark", BankHistorySeries.EMPTY,
			BankHistorySeries.EMPTY.withTransfer(sep(3), SENT_200));
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.withTransfers(Collections.singletonMap(sep(3), SENT_200)));
		assertTrue("of() carries none", BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1))).transfers().isEmpty());
		assertTrue(BankHistorySeries.EMPTY.transfers().isEmpty());
	}

	@Test
	public void withTransfersKeepsOnlyNotesOnReadingsThatMoveGold()
	{
		final Map<LocalDate, BankHistorySeries.Transfer> asked = new HashMap<>();
		asked.put(sep(3), SENT_200);
		asked.put(sep(17), GOT_200);
		asked.put(sep(4), new BankHistorySeries.Transfer(0L, false));
		assertEquals(Collections.singletonMap(sep(3), SENT_200), main().withTransfers(asked).transfers());
		final BankHistorySeries bare = main();
		assertTrue(bare.withTransfers(null).transfers().isEmpty());
		assertSame(bare, bare.withTransfers(Collections.<LocalDate, BankHistorySeries.Transfer>emptyMap()));
	}

	@Test
	public void theNotesAreUnmodifiable()
	{
		final BankHistorySeries marked = main().withTransfer(sep(3), SENT_200);
		try
		{
			marked.transfers().put(sep(4), GOT_200);
			org.junit.Assert.fail("an unmodifiable map");
		}
		catch (UnsupportedOperationException expected)
		{
			// as it should
		}
	}

	@Test
	public void theNotesArePartOfTheValue()
	{
		final BankHistorySeries marked = main().withTransfer(sep(3), SENT_200);
		assertEquals(marked, main().withTransfer(sep(3), SENT_200));
		assertEquals(marked.hashCode(), main().withTransfer(sep(3), SENT_200).hashCode());
		assertNotEquals("the same readings without the note", main(), marked);
		assertNotEquals("another amount", marked, main().withTransfer(sep(3), GOT_200));
		assertNotEquals("coins or not", marked, main().withTransfer(sep(3), COINS_SENT_200));
		assertNotEquals("another day", marked, main().withTransfer(sep(4), SENT_200));
	}

	@Test
	public void everyCopyCarriesTheNotes()
	{
		final BankHistorySeries marked = main().withTransfer(sep(3), SENT_200);
		final Map<LocalDate, BankHistorySeries.Transfer> notes = marked.transfers();
		assertEquals("with", notes, marked.with(p(sep(6), 6, 840)).transfers());
		assertEquals("a reading replaced", notes, marked.with(p(sep(3), 30, 811)).transfers());
		assertEquals("withFreshFrom", notes, marked.withFreshFrom(sep(2)).transfers());
		assertEquals("withPlaceholdersChecked", notes, marked.withPlaceholdersChecked(sep(2)).transfers());
		assertEquals("withFreshWhy", notes, marked.withFreshWhy("player").transfers());
		assertEquals("judged, a restart", notes, marked.judged(new BankHistorySeries.PlaceholderCheck(sep(4), true)).transfers());
		assertEquals("judged, no restart", notes, marked.judged(new BankHistorySeries.PlaceholderCheck(sep(4), false)).transfers());
		assertEquals("upTo with nothing after it", notes, marked.upTo(sep(9)).transfers());
		assertEquals("upTo that cuts a later reading", notes, marked.with(p(sep(7), 7, 1)).upTo(sep(5)).transfers());
		assertEquals("fromFresh with a cut", notes, marked.withFreshFrom(sep(2)).fromFresh().transfers());
		assertEquals("fromFresh with none", notes, marked.fromFresh().transfers());
	}

	@Test
	public void upToDropsTheNotesOfTheReadingsItDrops()
	{
		final BankHistorySeries marked = main().withTransfer(sep(3), SENT_200).withTransfer(sep(5), GOT_200);
		final BankHistorySeries cut = marked.upTo(sep(4));
		assertEquals(Collections.singletonMap(sep(3), SENT_200), cut.transfers());
		assertEquals(sep(4), cut.last().day());
		assertEquals("a note on the day itself stays", 2, marked.upTo(sep(5)).transfers().size());
	}

	// ---------------------------------------------------------------- the restore mark (part 5)

	@Test
	public void theRestoreMarkIsSetAndTakenOffAndAnEmptySeriesCarriesNone()
	{
		final BankHistorySeries cut = main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLAYER);
		assertFalse("a series starts unrestored", cut.restored());
		final BankHistorySeries restored = cut.withRestored(true);
		assertTrue(restored.restored());
		assertSame("the same mark again changes nothing", restored, restored.withRestored(true));
		assertSame(cut, cut.withRestored(false));
		assertFalse("a new reset takes it off", restored.withRestored(false).restored());
		assertEquals(cut, restored.withRestored(false));
		assertEquals("the mark never touches a reading, a note or the cut", cut.points(), restored.points());
		assertEquals(cut.freshFrom(), restored.freshFrom());
		assertEquals(cut.freshWhy(), restored.freshWhy());
		assertSame("an empty series has nothing to restore", BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.withRestored(true));
		assertFalse(BankHistorySeries.EMPTY.restored());
		assertFalse("of() carries none", BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1))).restored());
	}

	@Test
	public void theRestoreMarkIsPartOfTheValue()
	{
		final BankHistorySeries cut = main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLAYER);
		final BankHistorySeries restored = cut.withRestored(true);
		assertNotEquals("the same days, cut at the same day, restored or not", cut, restored);
		assertEquals(restored, main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withRestored(true));
		assertEquals(restored.hashCode(),
			main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withRestored(true).hashCode());
		assertNotEquals(cut.hashCode(), restored.hashCode());
	}

	@Test
	public void everyCopyCarriesTheRestoreMark()
	{
		final BankHistorySeries marked = main().withFreshFrom(sep(2)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withRestored(true);
		assertTrue("with", marked.with(p(sep(6), 6, 840)).restored());
		assertTrue("a reading replaced", marked.with(p(sep(3), 30, 811)).restored());
		assertTrue("withFreshFrom", marked.withFreshFrom(sep(4)).restored());
		assertTrue("withPlaceholdersChecked", marked.withPlaceholdersChecked(sep(2)).restored());
		assertTrue("withFreshWhy", marked.withFreshWhy(BankHistorySeries.WHY_PLACEHOLDERS).restored());
		assertTrue("withTransfer", marked.withTransfer(sep(3), SENT_200).restored());
		assertTrue("withTransfers", marked.withTransfers(Collections.singletonMap(sep(3), SENT_200)).restored());
		assertTrue("judged, a restart", marked.judged(new BankHistorySeries.PlaceholderCheck(sep(4), true)).restored());
		assertTrue("judged, no restart", marked.judged(new BankHistorySeries.PlaceholderCheck(sep(4), false)).restored());
		assertTrue("upTo with nothing after it", marked.upTo(sep(9)).restored());
		assertTrue("upTo that cuts a later reading", marked.with(p(sep(7), 7, 1)).upTo(sep(5)).restored());
		assertTrue("fromFresh with a cut", marked.fromFresh().restored());
		assertTrue("fromFresh with none", main().withRestored(true).fromFresh().restored());
	}

	@Test
	public void aNoteOnAHiddenDayStaysStoredAndInert()
	{
		final BankHistorySeries marked = main().withTransfer(sep(2), SENT_200).withFreshFrom(sep(4));
		final BankHistorySeries visible = marked.fromFresh();
		assertEquals("kept", marked.transfers(), visible.transfers());
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(visible, null, sep(5), null);
		assertEquals("no visible day is shifted by a note dated before them", real(days), drawn(days));
		assertFalse(BankHistoryMath.drawsBelowZero(visible, null));
	}

	@Test
	public void aNoteIsItsAmountAndWhetherItWasCoins()
	{
		assertEquals(-200L, SENT_200.gp());
		assertFalse(SENT_200.coins());
		assertTrue(COINS_SENT_200.coins());
		assertEquals(new BankHistorySeries.Transfer(-200L, false), SENT_200);
		assertEquals(new BankHistorySeries.Transfer(-200L, false).hashCode(), SENT_200.hashCode());
		assertNotEquals(SENT_200, COINS_SENT_200);
		assertNotEquals(SENT_200, GOT_200);
		assertNotEquals(SENT_200, null);
	}

	// ---------------------------------------------------------------- the drawn totals

	@Test
	public void aSentTransferDrawsTheOlderDaysLowerAndKeepsTheRealTotals()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(5), null);
		assertEquals("the totals are the real ones", Arrays.asList(1000L, 1010L, 810L, 820L, 830L), real(days));
		assertEquals("older days drawn as if it had happened; the day itself and after untouched",
			Arrays.asList(800L, 810L, 810L, 820L, 830L), drawn(days));
		assertEquals("today is never shifted: the last point is the card's", days.get(4).valueGp(), days.get(4).drawnGp());
	}

	@Test
	public void aReceivedTransferDrawsTheOlderDaysHigher()
	{
		final BankHistorySeries s = alt().withTransfer(sep(3), GOT_200);
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(5), null);
		assertEquals(Arrays.asList(5L, 6L, 206L, 207L, 208L), real(days));
		assertEquals(Arrays.asList(205L, 206L, 206L, 207L, 208L), drawn(days));
	}

	@Test
	public void theTwoMarkedAccountsDrawnLinesAddUpToTheRealPair()
	{
		final List<BankHistoryMath.Day> sender = BankHistoryMath.days(main().withTransfer(sep(3), SENT_200), null, sep(5),
			null);
		final List<BankHistoryMath.Day> receiver = BankHistoryMath.days(alt().withTransfer(sep(3), GOT_200), null, sep(5),
			null);
		for (int i = 0; i < 5; i++)
		{
			assertEquals("day " + (i + 1), sender.get(i).valueGp() + receiver.get(i).valueGp(),
				sender.get(i).drawnGp() + receiver.get(i).drawnGp());
		}
	}

	@Test
	public void severalNotesAddUpAndEachShiftsOnlyTheDaysBeforeIt()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200).withTransfer(sep(5), new BankHistorySeries
			.Transfer(50L, false));
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(5), null);
		assertEquals(Arrays.asList(1000L - 150L, 1010L - 150L, 810L + 50L, 820L + 50L, 830L), drawn(days));
	}

	@Test
	public void aCarriedDayIsDrawnAtItsReadingsDrawnValue()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1000), p(sep(4), 4, 800),
			p(sep(5), 5, 810))).withTransfer(sep(4), SENT_200);
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(5), null);
		assertEquals("two carried days between the readings", Arrays.asList(1000L, 1000L, 1000L, 800L, 810L), real(days));
		assertEquals(Arrays.asList(800L, 800L, 800L, 800L, 810L), drawn(days));
		assertTrue(days.get(1).carried());
	}

	@Test
	public void aNoteDatedAfterTheDayAskedForPlaysNoPart()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200).withTransfer(sep(5), GOT_200);
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(s, null, sep(4), null);
		assertEquals("only the day-3 note is behind day 4", Arrays.asList(800L, 810L, 810L, 820L), drawn(days));
	}

	@Test
	public void aCoinsNoteDrawsNoShiftWhileCoinsAreNotCounted()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), COINS_SENT_200);
		assertEquals(Arrays.asList(800L, 810L, 810L, 820L, 830L), drawn(BankHistoryMath.days(s, null, sep(5), null)));
		final List<BankHistoryMath.Day> off = BankHistoryMath.days(s, null, sep(5), NO_COINS);
		assertEquals("a dip the line never drew needs no shift", real(off), drawn(off));
		assertEquals(Arrays.asList(1000L, 1010L, 810L, 820L, 830L), drawn(off));
		final List<BankHistoryMath.Day> items = BankHistoryMath.days(main().withTransfer(sep(3), SENT_200), null, sep(5),
			NO_COINS);
		assertEquals("an items note shifts with coins off too", Arrays.asList(800L, 810L, 810L, 820L, 830L), drawn(items));
	}

	@Test
	public void aDayIsEqualOnlyWithTheSameDrawnTotalToo()
	{
		final BankHistoryMath.Day a = new BankHistoryMath.Day(sep(1), 5L, 5L, null);
		assertEquals(a, new BankHistoryMath.Day(sep(1), 5L, 5L, null));
		assertEquals(a.hashCode(), new BankHistoryMath.Day(sep(1), 5L, 5L, null).hashCode());
		assertNotEquals(a, new BankHistoryMath.Day(sep(1), 5L, 6L, null));
		assertEquals(6L, new BankHistoryMath.Day(sep(1), 5L, 6L, null).drawnGp());
		assertEquals(a.valueGp(), a.drawnGp());
	}

	@Test
	public void withNoNotesTheDrawnTotalIsTheRealOne()
	{
		final List<BankHistoryMath.Day> days = BankHistoryMath.days(main(), null, sep(5), null);
		assertEquals(real(days), drawn(days));
		assertEquals(real(days), drawn(BankHistoryMath.days(main(), null, sep(5), NO_COINS)));
	}

	// ---------------------------------------------------------------- every change is corrected

	@Test
	public void theChartsChangeLineStopsCountingTheTransfer()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		final BankHistoryMath.Change plain = BankHistoryMath.overDays(main(), sep(5), 4, null);
		assertEquals("before: a 170 loss", -170L, plain.deltaGp());
		final BankHistoryMath.Change c = BankHistoryMath.overDays(s, sep(5), 4, null);
		assertEquals(sep(1), c.fromDay());
		assertEquals(sep(5), c.toDay());
		assertEquals(30L, c.deltaGp());
		assertEquals(30 * 100.0d / 800, c.pct(), 1e-9);
		assertEquals("the same change as the card's move: the one rule", c, BankHistoryMath.sinceDay(s, sep(5), 4, null, 830L));
	}

	@Test
	public void theHistoryCardsHeadlineIsCorrectedToo()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		final BankHistoryMath.Change plain = BankHistoryMath.sinceDay(main(), sep(5), 4, null, 830L);
		assertEquals(-170L, plain.deltaGp());
		final BankHistoryMath.Change card = BankHistoryMath.sinceDay(s, sep(5), 4, null, 830L);
		assertEquals("the card agrees with the chart", 30L, card.deltaGp());
		assertEquals(sep(1), card.fromDay());
		assertEquals("the total now is the card's own, never shifted", sep(5), card.toDay());
		final BankHistoryMath.Change fromTheTransferOn = BankHistoryMath.sinceDay(s, sep(5), 2, null, 830L);
		assertEquals("a window starting on the transfer day is whole", 20L, fromTheTransferOn.deltaGp());
		assertEquals(sep(3), fromTheTransferOn.fromDay());
	}

	@Test
	public void aWindowStartingAfterTheTransferIsNotShiftedAtAll()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		assertEquals(BankHistoryMath.overDays(main(), sep(5), 1, null), BankHistoryMath.overDays(s, sep(5), 1, null));
		assertEquals(10L, BankHistoryMath.overDays(s, sep(5), 1, null).deltaGp());
	}

	@Test
	public void eachRowsChangeIsCorrectedAndTheMarkedRowReadsZero()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		final BankHistoryMath.Change marked = BankHistoryMath.vsPrevious(s, sep(3), null);
		assertEquals(sep(2), marked.fromDay());
		assertEquals("a whole-day transfer: 0", 0L, marked.deltaGp());
		assertEquals(0.0d, marked.pct(), 0.0d);
		assertEquals("the real change was -200", -200L, BankHistoryMath.vsPrevious(main(), sep(3), null).deltaGp());
		assertEquals("the row before the transfer", 10L, BankHistoryMath.vsPrevious(s, sep(2), null).deltaGp());
		assertEquals(10L, BankHistoryMath.vsPrevious(s, sep(4), null).deltaGp());
		assertEquals(10L, BankHistoryMath.vsPrevious(s, sep(5), null).deltaGp());
		assertNull("the first reading has none", BankHistoryMath.vsPrevious(s, sep(1), null));
	}

	/**
	 * A row prints its REAL total, so its percentage is a share of the real total before it - a row before a transfer reads
	 * exactly what it read with no note (10 of 1,000 is 1.0 %, not 10 of the drawn 800), and the alt's rows before the gold
	 * arrived keep theirs even where the drawn base would be far larger. The lead's find on the 1.2.2 pictures.
	 */
	@Test
	public void aRowsPercentageIsAShareOfTheRealTotalBeforeIt()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), SENT_200);
		assertEquals(BankHistoryMath.vsPrevious(main(), sep(2), null).pct(), BankHistoryMath.vsPrevious(s, sep(2), null).pct());
		assertEquals(1.0d, BankHistoryMath.vsPrevious(s, sep(2), null).pct(), 1e-9);
		final BankHistorySeries a = alt().withTransfer(sep(3), new BankHistorySeries.Transfer(200L, false));
		assertEquals(BankHistoryMath.vsPrevious(alt(), sep(2), null).pct(), BankHistoryMath.vsPrevious(a, sep(2), null).pct());
		assertEquals(20.0d, BankHistoryMath.vsPrevious(a, sep(2), null).pct(), 1e-9);
		assertEquals("the marked row still reads 0", 0.0d, BankHistoryMath.vsPrevious(a, sep(3), null).pct(), 0.0d);
	}

	@Test
	public void aReceivedTransferCorrectsTheAltsChangeAndItsPercentage()
	{
		final BankHistorySeries s = alt().withTransfer(sep(3), GOT_200);
		final BankHistoryMath.Change before = BankHistoryMath.overDays(alt(), sep(5), 4, null);
		assertEquals(203L, before.deltaGp());
		assertTrue("+4060 %", before.pct() > 4000.0d);
		final BankHistoryMath.Change after = BankHistoryMath.overDays(s, sep(5), 4, null);
		assertEquals(3L, after.deltaGp());
		assertEquals(3 * 100.0d / 205, after.pct(), 1e-9);
		assertEquals(0L, BankHistoryMath.vsPrevious(s, sep(3), null).deltaGp());
	}

	@Test
	public void aRowAfterASpanOfNoReadingsIsCorrectedOverTheSpan()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1000), p(sep(4), 4, 810)))
			.withTransfer(sep(4), SENT_200);
		final BankHistoryMath.Change c = BankHistoryMath.vsPrevious(s, sep(4), null);
		assertEquals(3, c.spanDays());
		assertEquals("810 against 1000 drawn at 800", 10L, c.deltaGp());
	}

	@Test
	public void aCoinsNoteLeavesTheChangesAloneWhileCoinsAreNotCounted()
	{
		final BankHistorySeries s = main().withTransfer(sep(3), COINS_SENT_200);
		assertEquals(30L, BankHistoryMath.overDays(s, sep(5), 4, null).deltaGp());
		assertEquals("coins off: the line never dipped, so nothing is corrected", -170L,
			BankHistoryMath.overDays(s, sep(5), 4, NO_COINS).deltaGp());
		assertEquals(-200L, BankHistoryMath.vsPrevious(s, sep(3), NO_COINS).deltaGp());
		assertEquals(-170L, BankHistoryMath.sinceDay(s, sep(5), 4, NO_COINS, 830L).deltaGp());
	}

	@Test
	public void aNoteDatedAfterTodayShiftsNothing()
	{
		final BankHistorySeries s = main().withTransfer(sep(5), SENT_200);
		assertEquals("today's own note shifts the days before it", 1000L - 200L,
			BankHistoryMath.days(s, null, sep(5), null).get(0).drawnGp());
		assertEquals("seen from day 4 the day-5 note is the future", 1000L,
			BankHistoryMath.days(s, null, sep(4), null).get(0).drawnGp());
		assertEquals(-180L, BankHistoryMath.overDays(s, sep(4), 3, null).deltaGp());
		assertEquals(-180L, BankHistoryMath.sinceDay(s, sep(4), 3, null, 820L).deltaGp());
	}

	// ---------------------------------------------------------------- no flipped sign over a base at or below zero

	@Test
	public void aPercentageOverABaseOfZeroOrLessIsNull()
	{
		assertNull(new BankHistoryMath.Change(sep(1), sep(3), 0L, 50L).pct());
		assertNull("no flipped sign", new BankHistoryMath.Change(sep(1), sep(3), -100L, 50L).pct());
		assertNull(new BankHistoryMath.Change(sep(1), sep(3), -1L, -50L).pct());
		assertNotNull(new BankHistoryMath.Change(sep(1), sep(3), 1L, 50L).pct());
		assertEquals(150L, new BankHistoryMath.Change(sep(1), sep(3), -100L, 50L).deltaGp());
	}

	@Test
	public void aRowOverADrawnBaseBelowZeroPrintsNoPercentage()
	{
		// An account that earned 250 then sent 150 away: day 1's drawn total is 50 - 150 = -100.
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 50), p(sep(2), 2, 250),
			p(sep(3), 3, 100))).withTransfer(sep(3), new BankHistorySeries.Transfer(-150L, false));
		final BankHistoryMath.Change c = BankHistoryMath.overDays(s, sep(3), 2, null);
		assertEquals(sep(1), c.fromDay());
		assertEquals(200L, c.deltaGp());
		assertNull(c.pct());
	}

	// ---------------------------------------------------------------- the below-zero decision

	@Test
	public void drawsBelowZeroOnlyWhenAShiftedDayWould()
	{
		assertFalse("no notes", BankHistoryMath.drawsBelowZero(main(), null));
		assertFalse("null", BankHistoryMath.drawsBelowZero(null, null));
		assertFalse(BankHistoryMath.drawsBelowZero(main().withTransfer(sep(3), SENT_200), null));
		assertFalse(BankHistoryMath.drawsBelowZero(alt().withTransfer(sep(3), GOT_200), null));
		final BankHistorySeries feeder = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 50), p(sep(2), 2, 250),
			p(sep(3), 3, 100))).withTransfer(sep(3), new BankHistorySeries.Transfer(-150L, false));
		assertTrue("earned then sent: day 1 would be drawn at -100", BankHistoryMath.drawsBelowZero(feeder, null));
		assertFalse("exactly zero is not below it", BankHistoryMath.drawsBelowZero(BankHistorySeries.of(Arrays.asList(
			p(sep(1), 1, 50), p(sep(2), 2, 250), p(sep(3), 3, 100))).withTransfer(sep(3),
			new BankHistorySeries.Transfer(-50L, false)), null));
		assertEquals("the figures are corrected either way: the days still say so", -100L,
			BankHistoryMath.days(feeder, null, sep(3), null).get(0).drawnGp());
	}

	@Test
	public void theBelowZeroDecisionFollowsTheCoinsSwitch()
	{
		final BankHistorySeries feeder = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 50), p(sep(2), 2, 250),
			p(sep(3), 3, 100))).withTransfer(sep(3), new BankHistorySeries.Transfer(-150L, true));
		assertTrue(BankHistoryMath.drawsBelowZero(feeder, null));
		assertFalse("a coins note the switch leaves out shifts nothing", BankHistoryMath.drawsBelowZero(feeder, NO_COINS));
	}

	@Test
	public void aHiddenDayNeverDecidesTheBelowZeroRule()
	{
		final BankHistorySeries feeder = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 50), p(sep(2), 2, 250),
			p(sep(3), 3, 100))).withTransfer(sep(3), new BankHistorySeries.Transfer(-150L, false));
		assertTrue(BankHistoryMath.drawsBelowZero(feeder, null));
		assertFalse("after Reset history on day 2 the only day that would go negative is hidden",
			BankHistoryMath.drawsBelowZero(feeder.withFreshFrom(sep(2)).fromFresh(), null));
	}

	// ---------------------------------------------------------------- 1.2.2 verification fixes

	/**
	 * A marked row's percentage is a share of the larger of the real total before it and that total moved by the note: an alt that held 1m
	 * and received a 200m item that rose 2m reads +0.99% on the row, as the change line over the same day does - not +200% on the 1m from
	 * before the gold arrived.
	 */
	@Test
	public void aMarkedRowOfAReceivedTransferTakesItsPercentOnTheLargerTotal()
	{
		final long m = 1_000_000L;
		final BankHistorySeries received = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1 * m), p(sep(2), 2, 203 * m)))
			.withTransfer(sep(2), new BankHistorySeries.Transfer(200 * m, false));
		final BankHistoryMath.Change row = BankHistoryMath.vsPrevious(received, sep(2), null);
		assertEquals("the leftover change is the 2m the item rose", 2 * m, row.deltaGp());
		assertEquals(2.0d * 100.0d / 201.0d, row.pct(), 1e-9);
		assertTrue("under 1 %, not +200 %", row.pct() < 1.0d);
		assertEquals("the change line over the same day reads the same", row.pct(),
			BankHistoryMath.overDays(received, sep(2), 1, null).pct(), 1e-9);
		// The whole day's change as the note: nothing left over, nothing to divide.
		assertEquals(0L, BankHistoryMath.vsPrevious(received.withTransfer(sep(2), new BankHistorySeries.Transfer(202 * m, false)),
			sep(2), null).deltaGp());
	}

	@Test
	public void aMarkedRowOfASentTransferKeepsTheLargerRealTotalAsItsBase()
	{
		final long m = 1_000_000L;
		// 201m sends 200m and drifts down 0.5m: the leftover -0.5m is a share of the 201m the row's own figures start from.
		final BankHistorySeries sent = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 201 * m), p(sep(2), 2, 500_000L)))
			.withTransfer(sep(2), new BankHistorySeries.Transfer(-200 * m, false));
		final BankHistoryMath.Change row = BankHistoryMath.vsPrevious(sent, sep(2), null);
		assertEquals(-500_000L, row.deltaGp());
		assertEquals(-500_000d * 100.0d / (201 * m), row.pct(), 1e-9);
	}

	@Test
	public void anUnmarkedRowAndACoinsNoteTheSwitchLeavesOutKeepTheRealTotalBeforeItAsTheBase()
	{
		final long m = 1_000_000L;
		final BankHistorySeries plain = BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1 * m), p(sep(2), 2, 203 * m)));
		assertEquals("no note: the row's own figures", 20200.0d, BankHistoryMath.vsPrevious(plain, sep(2), null).pct(), 1e-9);
		final BankHistorySeries coins = plain.withTransfer(sep(2), new BankHistorySeries.Transfer(200 * m, true));
		assertEquals("a coins note the switch leaves out is no mark", 20200.0d, BankHistoryMath.vsPrevious(coins, sep(2), NO_COINS).pct(), 1e-9);
		assertEquals("a note on another day is no mark of this one", 20200.0d,
			BankHistoryMath.vsPrevious(plain.withTransfer(sep(1), new BankHistorySeries.Transfer(5L, false)), sep(2), null).pct(), 1e-9);
	}

	/** One note moves at most 10,000b, so no sum of notes - one a day for a century - can wrap a long. */
	@Test
	public void aNoteBeyondTheCeilingIsDroppedAndTheCeilingItselfIsKept()
	{
		final long max = BankHistorySeries.Transfer.MAX_GP;
		assertEquals(10_000_000_000_000L, max);
		for (final long gp : new long[]{Long.MAX_VALUE, Long.MIN_VALUE, max + 1L, -max - 1L})
		{
			assertTrue("a note of " + gp + " is dropped", main().withTransfer(sep(3), new BankHistorySeries.Transfer(gp, false))
				.transfers().isEmpty());
		}
		assertEquals(Collections.singletonMap(sep(3), new BankHistorySeries.Transfer(max, false)),
			main().withTransfer(sep(3), new BankHistorySeries.Transfer(max, false)).transfers());
		assertEquals(Collections.singletonMap(sep(3), new BankHistorySeries.Transfer(-max, true)),
			main().withTransfer(sep(3), new BankHistorySeries.Transfer(-max, true)).transfers());
		final Map<LocalDate, BankHistorySeries.Transfer> loaded = new HashMap<>();
		loaded.put(sep(2), new BankHistorySeries.Transfer(Long.MAX_VALUE, false));
		loaded.put(sep(3), new BankHistorySeries.Transfer(Long.MAX_VALUE, false));
		final BankHistorySeries series = main().withTransfers(loaded);
		assertTrue("a file that holds such notes is read without them", series.transfers().isEmpty());
		assertEquals("so no change is wrapped: day 2 read 1010 against day 1's 1000", 10L,
			BankHistoryMath.vsPrevious(series, sep(2), null).deltaGp());
	}

	// ---------------------------------------------------------------- the cut a player's reset stands over

	@Test
	public void theAutomaticCutAResetStandsOverIsCarriedByEveryCopyAndPartOfTheValue()
	{
		final BankHistorySeries.AutoCut under = new BankHistorySeries.AutoCut(sep(3), BankHistorySeries.WHY_PLACEHOLDERS);
		final BankHistorySeries cut = main().withFreshFrom(sep(4)).withFreshWhy(BankHistorySeries.WHY_PLAYER);
		final BankHistorySeries marked = cut.withAutoCut(under);
		assertNull(cut.autoCut());
		assertEquals(under, marked.autoCut());
		assertNotEquals("the same days, the same cut: with and without what it stands over", cut, marked);
		assertNotEquals(cut.hashCode(), marked.hashCode());
		assertEquals(marked, cut.withAutoCut(new BankHistorySeries.AutoCut(sep(3), BankHistorySeries.WHY_PLACEHOLDERS)));
		assertSame(marked, marked.withAutoCut(under));
		assertEquals("with", under, marked.with(p(sep(6), 6, 840)).autoCut());
		assertEquals("withFreshFrom", under, marked.withFreshFrom(sep(5)).autoCut());
		assertEquals("withFreshWhy", under, marked.withFreshWhy(BankHistorySeries.WHY_PLACEHOLDERS).autoCut());
		assertEquals("withPlaceholdersChecked", under, marked.withPlaceholdersChecked(sep(2)).autoCut());
		assertEquals("withRestored", under, marked.withRestored(true).autoCut());
		assertEquals("withTransfer", under, marked.withTransfer(sep(3), SENT_200).autoCut());
		assertEquals("judged", under, marked.judged(new BankHistorySeries.PlaceholderCheck(sep(4), true)).autoCut());
		assertEquals("upTo", under, marked.with(p(sep(7), 7, 1)).upTo(sep(5)).autoCut());
		assertEquals("fromFresh", under, marked.fromFresh().autoCut());
		assertNull("of() carries none", BankHistorySeries.of(Arrays.asList(p(sep(1), 1, 1))).autoCut());
		assertNull(BankHistorySeries.EMPTY.autoCut());
		assertSame("an empty series has nothing to stand over", BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.withAutoCut(under));
	}

	/** The series as the panel reads its cut: a restored reset over an automatic cut reads as that cut; every other record is itself. */
	@Test
	public void aRestoredResetOverAnAutomaticCutStandsAsThatCut()
	{
		final BankHistorySeries.AutoCut under = new BankHistorySeries.AutoCut(sep(3), BankHistorySeries.WHY_PLACEHOLDERS);
		final BankHistorySeries reset = main().withFreshFrom(sep(5)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withAutoCut(under);
		assertSame("not restored: the player's own cut", reset, reset.standing());
		final BankHistorySeries restored = reset.withRestored(true);
		final BankHistorySeries standing = restored.standing();
		assertEquals(sep(3), standing.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, standing.freshWhy());
		assertEquals("the readings and the notes are the same", restored.points(), standing.points());
		assertTrue("and the days before the automatic cut are the legacy ones", standing.hasLegacyDays());
		final BankHistorySeries oneNineNine = main().withFreshFrom(sep(5)).withFreshWhy(BankHistorySeries.WHY_PLAYER)
			.withAutoCut(new BankHistorySeries.AutoCut(sep(2), null)).withRestored(true);
		assertNull("1.0.9's reason is no word", oneNineNine.standing().freshWhy());
		assertEquals(sep(2), oneNineNine.standing().freshFrom());
		final BankHistorySeries noUnder = main().withFreshFrom(sep(5)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withRestored(true);
		assertSame("nothing to stand over: itself", noUnder, noUnder.standing());
		final BankHistorySeries automatic = main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLACEHOLDERS)
			.withAutoCut(under).withRestored(true);
		assertSame("an automatic record is itself whatever else it carries", automatic, automatic.standing());
		assertSame(BankHistorySeries.EMPTY, BankHistorySeries.EMPTY.standing());
	}

	/** What a reset made now would stand over: this record's own automatic cut, or the one a player's cut already stands over. */
	@Test
	public void theCutAResetWouldStandOverIsTheAutomaticOneAndNeverAMigratedFilesMax()
	{
		assertNull("no cut at all", main().autoCutToKeep());
		assertEquals(new BankHistorySeries.AutoCut(sep(3), BankHistorySeries.WHY_PLACEHOLDERS),
			main().withFreshFrom(sep(3)).withFreshWhy(BankHistorySeries.WHY_PLACEHOLDERS).autoCutToKeep());
		assertEquals("1.0.9's reason", new BankHistorySeries.AutoCut(sep(3), null), main().withFreshFrom(sep(3)).autoCutToKeep());
		assertNull("a migrated file's every-day-is-legacy marker is not a cut that was shown",
			main().withFreshFrom(LocalDate.MAX).autoCutToKeep());
		final BankHistorySeries.AutoCut under = new BankHistorySeries.AutoCut(sep(2), null);
		assertEquals("a player's cut already stands over its own", under,
			main().withFreshFrom(sep(4)).withFreshWhy(BankHistorySeries.WHY_PLAYER).withAutoCut(under).autoCutToKeep());
		assertNull(BankHistorySeries.EMPTY.autoCutToKeep());
	}

	// ---------------------------------------------------------------- what a note starts as

	@Test
	public void theCoinChangeIsOfferedWhenItIsMostOfTheDay()
	{
		// 200 of coins left, the rest of the bank rose by 4: the whole change is -196, the coins are exact.
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 104, 300)));
		final BankHistorySeries.Transfer t = BankHistoryMath.prefill(s, sep(2), null);
		assertEquals(new BankHistorySeries.Transfer(-200L, true), t);
	}

	@Test
	public void theWholeRealChangeIsOfferedWhenCoinsAreNotMostOfIt()
	{
		// Coins -50 of a whole change of -100: 50 %, below the 80 %.
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 500, 500), cash(sep(2), 2, 450, 450)));
		assertEquals(new BankHistorySeries.Transfer(-100L, false), BankHistoryMath.prefill(s, sep(2), null));
	}

	@Test
	public void theEightyPercentEdgeIsInclusive()
	{
		// A whole change of -100 in both: coins -80 (exactly 80 %), then coins -79.
		final BankHistorySeries at = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 100), cash(sep(2), 2, 80, 20)));
		assertEquals("80 of 100", new BankHistorySeries.Transfer(-80L, true), BankHistoryMath.prefill(at, sep(2), null));
		final BankHistorySeries under = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 100),
			cash(sep(2), 2, 79, 21)));
		assertEquals("79 of 100 is the whole change", new BankHistorySeries.Transfer(-100L, false),
			BankHistoryMath.prefill(under, sep(2), null));
	}

	@Test
	public void coinsGoingTheOtherWayThanTheDayAreNotTheTransfer()
	{
		// 200 of coins spent on a purchase that raised the bank by 250: the whole change is +50 - not coins.
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 350, 300)));
		assertEquals(new BankHistorySeries.Transfer(50L, false), BankHistoryMath.prefill(s, sep(2), null));
	}

	@Test
	public void aReceivedSumIsOfferedWithItsPlus()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 10, 5), cash(sep(2), 2, 10, 205)));
		assertEquals(new BankHistorySeries.Transfer(200L, true), BankHistoryMath.prefill(s, sep(2), null));
	}

	@Test
	public void coinsThatTheSwitchesDoNotCountAreNotTheOffer()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 104, 300)));
		assertEquals("coins off: the whole change is the 4 the bank rose, in no coin", new BankHistorySeries.Transfer(4L, false),
			BankHistoryMath.prefill(s, sep(2), NO_COINS));
	}

	@Test
	public void carriedAndExchangeCoinsCountWhenTheirSwitchesDo()
	{
		final long[] a = new long[BankHistoryPoint.CELLS];
		final long[] b = new long[BankHistoryPoint.CELLS];
		a[BankHistoryPoint.BANK_TRADEABLE] = 100;
		a[BankHistoryPoint.CARRIED_CASH] = 100;
		a[BankHistoryPoint.GE_CASH] = 100;
		b[BankHistoryPoint.BANK_TRADEABLE] = 100;
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(new BankHistoryPoint(sep(1), 1, 1, a, null),
			new BankHistoryPoint(sep(2), 2, 2, b, null)));
		assertEquals("bank + carried + Grand Exchange cash", new BankHistorySeries.Transfer(-200L, true),
			BankHistoryMath.prefill(s, sep(2), null));
		final ViewOptions noInventory = new ViewOptions(true, false, true, false, true);
		assertEquals("the inventory's coins left out", new BankHistorySeries.Transfer(-100L, true),
			BankHistoryMath.prefill(s, sep(2), noInventory));
		final ViewOptions noOffers = new ViewOptions(true, false, true, true, false);
		assertEquals("the offers' coins left out", new BankHistorySeries.Transfer(-100L, true),
			BankHistoryMath.prefill(s, sep(2), noOffers));
		assertNull("no coins counted and nothing else moved", BankHistoryMath.prefill(s, sep(2), NO_COINS));
	}

	@Test
	public void aMarkedDayOffersItsOwnNoteNotTheCorrectedChange()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 104, 300)))
			.withTransfer(sep(2), new BankHistorySeries.Transfer(-150L, false));
		assertEquals("its own note", new BankHistorySeries.Transfer(-150L, false), BankHistoryMath.prefill(s, sep(2), null));
		assertEquals("the real change was -196; the corrected one is not what it offers", -46L,
			BankHistoryMath.vsPrevious(s, sep(2), null).deltaGp());
		assertEquals(new BankHistorySeries.Transfer(-200L, true),
			BankHistoryMath.prefill(s.withTransfer(sep(2), null), sep(2), null));
	}

	/** A coins note the switch leaves out draws no mark (Transfer.counts), so the day reads as unmarked and offers what an unmarked day does. */
	@Test
	public void aCoinsNoteTheSwitchLeavesOutIsNotTheDaysOwnOffer()
	{
		final BankHistorySeries coins = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 104, 300)))
			.withTransfer(sep(2), new BankHistorySeries.Transfer(-150L, true));
		assertEquals("coins counted: its own note", new BankHistorySeries.Transfer(-150L, true),
			BankHistoryMath.prefill(coins, sep(2), null));
		assertEquals("coins left out: the day's change, in no coin, as for a day with no note", new BankHistorySeries.Transfer(4L, false),
			BankHistoryMath.prefill(coins, sep(2), NO_COINS));
		final BankHistorySeries items = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 104, 300)))
			.withTransfer(sep(2), new BankHistorySeries.Transfer(-150L, false));
		assertEquals("an items note counts whatever the switch says", new BankHistorySeries.Transfer(-150L, false),
			BankHistoryMath.prefill(items, sep(2), NO_COINS));
	}

	@Test
	public void nothingIsOfferedWithNothingToOffer()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 500), cash(sep(2), 2, 100, 500),
			cash(sep(4), 4, 150, 500)));
		assertNull("the first reading has no reading before it", BankHistoryMath.prefill(s, sep(1), null));
		assertNull("a change of 0", BankHistoryMath.prefill(s, sep(2), null));
		assertNull("a day with no reading", BankHistoryMath.prefill(s, sep(3), null));
		assertNull(BankHistoryMath.prefill(null, sep(2), null));
		assertNull(BankHistoryMath.prefill(s, null, null));
		assertEquals("a change after a gap is the change since the reading before it", new BankHistorySeries.Transfer(50L,
			false), BankHistoryMath.prefill(s, sep(4), null));
	}

	@Test
	public void aDayOfTwoReadingsOnlyEverOffersTheLastOne()
	{
		final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(cash(sep(1), 1, 100, 100), cash(sep(2), 2, 100, 100),
			cash(sep(2), 3, 100, 0)));
		assertEquals(new BankHistorySeries.Transfer(-100L, true), BankHistoryMath.prefill(s, sep(2), null));
	}
}
