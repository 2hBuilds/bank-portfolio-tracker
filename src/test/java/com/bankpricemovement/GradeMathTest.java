package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * {@link GradeMath} against contract 1.2.0, lines L2 and L3 ({@code docs/handoff/contract-1.2.0-graded-live-moves-2026-10-07.md}),
 * with SYNTHETIC items only - one made-up item priced around 1,000 gp, its prints, its days - so nothing here says
 * anything about a real bank. Each word test changes ONE fact of a solid figure, so the word it pins is the one that
 * fact triggers; the order test changes two.
 */
public class GradeMathTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
	private static final LocalDate YESTERDAY = TODAY.minusDays(1);
	private static final LocalDate DAY_BEFORE = TODAY.minusDays(2);
	/** 2026-10-07T14:00:00Z. */
	private static final long NOW = seconds(TODAY, 14, 0);

	/** The guide price of the made-up item. */
	private static final long GUIDE = 1_000L;

	/** Yesterday, busy: 20,000 bought at 1,000 and 20,000 sold at 980 - 20m and 19.6m gp a side. */
	private static final TradedPriceClient.Bucket BUSY_YESTERDAY = day(1_000L, 20_000L, 980L, 20_000L);

	// ---------------------------------------------------------------- L2: the median and the anchor

	@Test
	public void theMedianOfOneTwoAndThreeObservations()
	{
		assertEquals(Long.valueOf(5L), GradeMath.median(new long[]{5L}, 1));
		assertEquals("two: the mean of the middle two, half up", Long.valueOf(3L), GradeMath.median(new long[]{1L, 4L}, 2));
		assertEquals("three: the middle one, whatever the order", Long.valueOf(4L),
			GradeMath.median(new long[]{7L, 1L, 4L}, 3));
		assertNull("none: no median", GradeMath.median(new long[0], 0));

		final GradeMath.Facts one = facts(quote(1_030L, 13, 50, null, 0, 0), prints(), prints(), null, 0L,
			BUSY_YESTERDAY, null);
		assertEquals(1, one.buy.count);
		assertEquals(Long.valueOf(1_030L), one.buy.median);
		final GradeMath.Facts two = facts(quote(1_030L, 13, 50, null, 0, 0), prints(1_020L, 10, 0), prints(), null, 0L,
			BUSY_YESTERDAY, null);
		assertEquals(2, two.buy.count);
		assertEquals(Long.valueOf(1_025L), two.buy.median);
		final GradeMath.Facts three = facts(quote(1_030L, 13, 50, null, 0, 0),
			prints(1_020L, 10, 0, 1_090L, 11, 0), prints(), null, 0L, BUSY_YESTERDAY, null);
		assertEquals(3, three.buy.count);
		assertEquals(Long.valueOf(1_030L), three.buy.median);
	}

	/** The anchor is the median of the guide and yesterday's two sides - and the day before's when yesterday names neither. */
	@Test
	public void theAnchorIsTheMedianOfTheGuideAndYesterdaysSides()
	{
		assertEquals(Long.valueOf(1_000L), GradeMath.anchor(GUIDE, BUSY_YESTERDAY, null));
		assertEquals("the guide alone", Long.valueOf(1_000L), GradeMath.anchor(GUIDE, null, null));
		assertEquals("yesterday names neither side: the day before's", Long.valueOf(1_200L),
			GradeMath.anchor(1_400L, day(null, 5L, null, 5L), day(1_200L, 5L, 1_000L, 5L)));
		assertEquals("no guide: the two sides' middle", Long.valueOf(990L), GradeMath.anchor(null, BUSY_YESTERDAY, null));
		assertNull("nothing at all: no anchor", GradeMath.anchor(null, null, null));
	}

	@Test
	public void aPriceIsPlausibleFromAThirdToThreeTimesTheAnchor()
	{
		assertTrue(GradeMath.plausible(334L, 1_000L));
		assertFalse("a third, by integers: 3 x 333 is under 1,000", GradeMath.plausible(333L, 1_000L));
		assertTrue(GradeMath.plausible(3_000L, 1_000L));
		assertFalse(GradeMath.plausible(3_001L, 1_000L));
		assertFalse("no anchor: nothing is plausible", GradeMath.plausible(1_000L, null));
		assertFalse(GradeMath.plausible(null, 1_000L));
	}

	/**
	 * The anchor's whole job: a 1 gp print - a mistyped offer - is one of today's observations until the anchor throws
	 * it out. With it, the buy side's "now" is the real print; without it, the median of 1 and 1,030 is 516 and the
	 * row reads a 48 % fall that never happened.
	 */
	@Test
	public void theAnchorDropsAOneGpPrint()
	{
		final GradeMath.Facts facts = facts(quote(1_030L, 13, 50, 1_010L, 13, 40), prints(1L, 9, 0), prints(1_000L, 9, 0),
			null, 0L, BUSY_YESTERDAY, null);

		assertEquals("the 1 gp print is not an observation", 1, facts.buy.count);
		assertEquals(Long.valueOf(1_030L), facts.buy.median);
		final GradedMove move = oneDay(facts);
		assertEquals(Long.valueOf(1_030L), move.buy().now());
		assertTrue("a rise, not a fall", move.move() > 0.0d);
	}

	/**
	 * The ceiling ({@link GradeMath#PRICE_CEILING}, {@code Long.MAX_VALUE / 200}) is what keeps the plausibility test and the
	 * gap test from wrapping: {@code price * 3} and {@code gap * 100} overflow a long long before the price is believable.
	 * The pair below is one where the wrapped arithmetic says "plausible" (3p wraps to a positive number above the anchor,
	 * and the anchor times three is above p) though p is almost three times the anchor and past any real price -
	 * so with the ceiling removed the made-up item gets a +190 % figure on an 8.3-quintillion-gp quote; with it the item
	 * has no anchor and no observation, and no figure.
	 */
	@Test
	public void aQuoteNearTheCeilingIsRefusedRatherThanWrappedThroughTheChecks()
	{
		assertEquals(Long.MAX_VALUE / 200L, GradeMath.PRICE_CEILING);
		final long absurd = 8_300_000_000_000_000_000L;
		final long anchor = 2_860_000_000_000_000_000L;
		try
		{
			Math.multiplyExact(absurd, 3L);
			org.junit.Assert.fail("the premise: three times the price overflows a long");
		}
		catch (final ArithmeticException expected)
		{
			// the premise holds
		}
		assertTrue("the premise: in wrapped arithmetic it passes both of the plausibility comparisons",
			absurd * 3L >= anchor && absurd <= anchor * 3L);
		assertFalse("refused, not wrapped", GradeMath.plausible(absurd, anchor));
		assertFalse(GradeMath.plausible(GradeMath.PRICE_CEILING + 1L, GradeMath.PRICE_CEILING));
		assertTrue("the ceiling itself is still a price, and three times it does not wrap",
			GradeMath.plausible(GradeMath.PRICE_CEILING, GradeMath.PRICE_CEILING));

		assertEquals("a guide price past the ceiling is not an anchor value: the sides' middle is", Long.valueOf(990L),
			GradeMath.anchor(GradeMath.PRICE_CEILING + 1L, BUSY_YESTERDAY, null));
		assertNull("and with only absurd values there is no anchor at all",
			GradeMath.anchor(anchor, day(anchor, 100L, anchor, 100L), null));

		final TradedPriceClient.Quote quote = new TradedPriceClient.Quote(absurd, seconds(TODAY, 13, 50), absurd,
			seconds(TODAY, 13, 40));
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, anchor, quote, null, null, null, 0L,
			day(anchor, 100L, anchor, 100L), YESTERDAY, null, null);
		assertEquals("no observation", 0, facts.buy.count);
		assertEquals(0, facts.sell.count);
		final GradedMove move = oneDay(facts);
		assertSame(Grade.NONE, move.grade());
		assertFalse("no figure on an absurd quote", move.hasFigure());
	}

	/**
	 * The gap word's own guard: a current pair whose middle is past the ceiling is not measured for its gap, because
	 * {@code gap * 100} would wrap there (here to a positive number above the middle times ten, which would read "wide
	 * spread" by accident). A made-up pair of 210 and 10 quadrillion gp beside real prints on a busy day: the pair's gap is
	 * not read, so the figure is solid and never says "spread".
	 */
	@Test
	public void aPairPastTheCeilingIsNotMeasuredForItsGap()
	{
		final long buy = 210_000_000_000_000_000L;
		final long sell = 10_000_000_000_000_000L;
		final long gap = buy - sell;
		final long middle = buy / 2L + sell / 2L;
		assertTrue("the premise: the middle is past the ceiling", middle > GradeMath.PRICE_CEILING);
		assertTrue("the premise: gap x 100 wraps, to a positive number above the middle x 10",
			gap > Long.MAX_VALUE / 100L && gap * 100L > 0L && gap * 100L > middle * 10L);
		final TradedPriceClient.Quote quote = new TradedPriceClient.Quote(buy, seconds(TODAY, 13, 50), sell,
			seconds(TODAY, 13, 40));
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, GUIDE, quote, prints(1_020L, 10, 0, 1_030L, 11, 0),
			prints(1_000L, 9, 0, 1_010L, 10, 0), null, 0L, day(1_000L, 500L, 980L, 500L), YESTERDAY, null, null);

		final GradedMove move = oneDay(facts);

		assertSame("the pair's gap is not read, so it is not a wide spread: solid", Grade.SOLID, move.grade());
		assertEquals(GradeWords.SOLID, move.word());
	}

	/**
	 * A current print is an observation while it is at most 24 hours old, whatever UTC day it is of (the user, 2026-10-07:
	 * "today" is the last 24 hours and not the calendar day): 23 h 59 min counts - across the UTC midnight, the print being
	 * ten minutes before it - 24 hours to the second counts, and a second more does not.
	 */
	@Test
	public void aCurrentPrintIsAnObservationForTwentyFourHoursAcrossTheUtcMidnight()
	{
		final long printAt = seconds(YESTERDAY, 23, 50);
		final TradedPriceClient.Quote quote = new TradedPriceClient.Quote(1_030L, printAt, null, 0L);
		final long exactlyADay = printAt + GradeMath.LATEST_PRINT_MAX_AGE_SECONDS;

		assertEquals("23 h 59 min counts, across the UTC midnight", 1, GradeMath.facts(exactlyADay - 60L, TODAY, GUIDE, quote,
			null, null, null, 0L, BUSY_YESTERDAY, YESTERDAY, null, null).buy.count);
		assertEquals("24 hours to the second still counts", 1, GradeMath.facts(exactlyADay, TODAY, GUIDE, quote, null, null,
			null, 0L, BUSY_YESTERDAY, YESTERDAY, null, null).buy.count);
		assertEquals("a second more does not", 0, GradeMath.facts(exactlyADay + 1L, TODAY, GUIDE, quote, null, null, null,
			0L, BUSY_YESTERDAY, YESTERDAY, null, null).buy.count);
	}

	/** The last 24 hours' prints are observations, the memory's too, and nothing older: the UTC day plays no part. */
	@Test
	public void printsOfTheLast24HoursAreObservationsWhateverUtcDayTheyAreOf()
	{
		final TradedPriceClient.Quote lastNight = new TradedPriceClient.Quote(1_030L, seconds(YESTERDAY, 23, 59),
			1_010L, seconds(YESTERDAY, 22, 0));
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, GUIDE, lastNight, null, null, null, 0L,
			BUSY_YESTERDAY, YESTERDAY, null, null);

		assertEquals("14 h old, of yesterday's UTC day", 1, facts.buy.count);
		assertEquals("16 h old", 1, facts.sell.count);
		assertEquals("a memory print 23 h old counts", 1, facts(quote(null, 0, 0, null, 0, 0),
			new long[]{1_020L, seconds(YESTERDAY, 15, 0)}, prints(), null, 0L, BUSY_YESTERDAY, null).buy.count);
		assertEquals("a memory print 26 h old does not", 0, facts(quote(null, 0, 0, null, 0, 0),
			new long[]{1_020L, seconds(YESTERDAY, 12, 0)}, prints(), null, 0L, BUSY_YESTERDAY, null).buy.count);
		assertEquals("the same print counted once, from the memory and the quote", 1, facts(quote(1_030L, 13, 50, null, 0, 0),
			prints(1_030L, 13, 50), prints(), null, 0L, BUSY_YESTERDAY, null).buy.count);
		assertEquals("no live day, nothing observed", 0, GradeMath.facts(NOW, null, GUIDE, lastNight, null, null, null, 0L,
			BUSY_YESTERDAY, YESTERDAY, null, null).buy.count);
	}

	// ---------------------------------------------------------------- L2: both sides, one side, the fallback, none

	/**
	 * Both sides, measured: buy "now" 1,025 (the median of 1,020 and 1,030) against yesterday's 1,000 is +2.5 %; sell
	 * 1,005 against 980 is +2.55 %; the figure is their mean, and the price is yesterday's two-sided mark, 990, moved by
	 * it: 990 + round(990 x 0.02526) = 1,015 (L4). Two prints a side, 19.6m gp on the thinner side, a pair 2 % apart and a
	 * price inside the anchor's range: SOLID, no word.
	 */
	@Test
	public void bothSidesMeasuredMakeASolidFigure()
	{
		final GradedMove move = oneDay(solidFacts());

		assertSame(Grade.SOLID, move.grade());
		assertEquals(GradeWords.SOLID, move.word());
		assertEquals(Long.valueOf(1_025L), move.buy().now());
		assertEquals(Long.valueOf(1_000L), move.buy().then());
		assertEquals(0.025d, move.buy().move(), 1e-12);
		assertEquals(Long.valueOf(1_005L), move.sell().now());
		assertEquals(Long.valueOf(980L), move.sell().then());
		assertEquals((0.025d + (1_005d / 980d - 1d)) / 2d, move.move(), 1e-12);
		assertEquals(Long.valueOf(990L), move.thenMark());
		assertEquals(Long.valueOf(25L), move.deltaGp());
		assertEquals("price = thenMark x (1 + move)", Long.valueOf(1_015L), move.price());
		assertTrue(move.sidesAgree());
		assertFalse(move.fallback());
		assertEquals(TODAY, move.nowDay());
		assertEquals(YESTERDAY, move.thenDay());
		assertEquals("the last print of each side, with its time", Long.valueOf(1_030L), move.buy().last());
		assertEquals(seconds(TODAY, 13, 50), move.buy().lastSeconds());
		assertEquals(Long.valueOf(1_010L), move.sell().last());
		assertEquals(2, move.buy().observations());
	}

	/**
	 * One side observed (the user, 2026-10-07: "if all the trades were buyers paying that, that is the current price"): the
	 * row is priced at that side's own median against that side's own average on the day, so its worth, its was and its
	 * change all stand on the one side; the figure is that side's move; and it is no weaker for being one-sided - on a day
	 * of real volume and a tight pair it is SOLID, with no word, whether it has two observations or one.
	 */
	@Test
	public void oneSideObservedIsPricedAtItsOwnSideAndIsNoWeakerForIt()
	{
		final GradedMove buyOnly = oneDay(facts(quote(1_030L, 13, 50, null, 0, 0), prints(1_020L, 10, 0), prints(), null,
			0L, BUSY_YESTERDAY, null));
		assertSame(Grade.SOLID, buyOnly.grade());
		assertEquals(GradeWords.SOLID, buyOnly.word());
		assertEquals("the price is the buy median of 1,020 and 1,030", Long.valueOf(1_025L), buyOnly.price());
		assertEquals("then is yesterday's buy average, not the two-sided mark of 990", Long.valueOf(1_000L), buyOnly.thenMark());
		assertEquals(Long.valueOf(25L), buyOnly.deltaGp());
		assertEquals(0.025d, buyOnly.move(), 1e-12);
		assertNull(buyOnly.sell().move());
		assertEquals(0, buyOnly.sell().observations());

		final GradedMove sellOnly = oneDay(facts(quote(null, 0, 0, 1_010L, 13, 40), prints(), prints(1_000L, 9, 0), null,
			0L, BUSY_YESTERDAY, null));
		assertSame(Grade.SOLID, sellOnly.grade());
		assertEquals(GradeWords.SOLID, sellOnly.word());
		assertEquals("the price is the sell median of 1,000 and 1,010", Long.valueOf(1_005L), sellOnly.price());
		assertEquals("then is yesterday's sell average", Long.valueOf(980L), sellOnly.thenMark());
		assertEquals(Long.valueOf(25L), sellOnly.deltaGp());
		assertEquals(1_005d / 980d - 1d, sellOnly.move(), 1e-12);
		assertNull(sellOnly.buy().move());
		assertEquals("the last prints stay as they are", Long.valueOf(1_010L), sellOnly.sell().last());

		final GradedMove onePrint = oneDay(facts(quote(1_030L, 13, 50, null, 0, 0), prints(), prints(), null, 0L,
			BUSY_YESTERDAY, null));
		assertSame("one print is enough: the observations are no part of the grade", Grade.SOLID, onePrint.grade());
		assertEquals("priced at the one print", Long.valueOf(1_030L), onePrint.price());
		assertEquals(Long.valueOf(1_000L), onePrint.thenMark());
	}

	/**
	 * A one-sided figure is judged by the facts that apply to it: a thin compared day says "low vol N", and it is priced at the
	 * one side's own price all the same. It never says "spread": its other side's last print is a day old, which is no
	 * current pair (the dedicated test is {@link #aSpreadNeedsBothSidesToHaveReportedInTheLast24Hours}).
	 */
	@Test
	public void aOneSidedFigureIsJudgedByTheSameFacts()
	{
		final GradedMove thin = oneDay(facts(quote(1_030L, 13, 50, null, 0, 0), prints(1_020L, 10, 0), prints(), null, 0L,
			day(1_000L, 7L, 980L, 20_000L), null));
		assertSame(Grade.SOFT, thin.grade());
		assertEquals("low vol 7", thin.word());
		assertEquals(Long.valueOf(1_025L), thin.price());
		assertEquals(Long.valueOf(1_000L), thin.thenMark());

		final TradedPriceClient.Quote staleSell = new TradedPriceClient.Quote(1_210L, seconds(TODAY, 13, 50), 1_000L,
			seconds(YESTERDAY, 12, 0));
		final GradedMove stale = oneDay(facts(staleSell, prints(), prints(), null, 0L, BUSY_YESTERDAY, null));
		assertEquals("only the buy was observed", 0, stale.sell().observations());
		assertSame("a pair with a side silent for 26 hours is no spread", Grade.SOLID, stale.grade());
		assertEquals(GradeWords.SOLID, stale.word());
		assertNull(stale.spreadPercent());
		assertEquals(Long.valueOf(1_210L), stale.price());
		assertEquals(Long.valueOf(1_000L), stale.thenMark());
	}

	/**
	 * The spread is a fact about the CURRENT pair: both sides must have reported in the last 24 hours (the user, 2026-10-07 -
	 * on a one-sided row the other side's last print was days old, and the "spread" was staleness). A buy print 2 hours old
	 * and a sell print 30 hours old, 40 % apart: no word, SOLID on a busy day, priced at the buy alone. The same with the sell
	 * print 2 hours old: "spread 40%". Exactly 24 hours old still counts and a second more does not, and a
	 * fresh print outside the anchor's range is no side that reported.
	 */
	@Test
	public void aSpreadNeedsBothSidesToHaveReportedInTheLast24Hours()
	{
		final long twoHoursAgo = NOW - 2L * 3_600L;
		final long thirtyHoursAgo = NOW - 30L * 3_600L;

		final GradedMove oneSided = oneDay(GradeMath.facts(NOW, TODAY, GUIDE,
			new TradedPriceClient.Quote(1_200L, twoHoursAgo, 800L, thirtyHoursAgo), null, null, null, 0L, BUSY_YESTERDAY,
			YESTERDAY, null, null));
		assertEquals("only the buy reported", 1, oneSided.buy().observations());
		assertEquals(0, oneSided.sell().observations());
		assertSame(Grade.SOLID, oneSided.grade());
		assertEquals(GradeWords.SOLID, oneSided.word());
		assertNull("the pair is 40 % apart and no current spread", oneSided.spreadPercent());
		assertEquals("priced at the buy alone", Long.valueOf(1_200L), oneSided.price());

		final GradedMove both = oneDay(GradeMath.facts(NOW, TODAY, GUIDE,
			new TradedPriceClient.Quote(1_200L, twoHoursAgo, 800L, twoHoursAgo), null, null, null, 0L, BUSY_YESTERDAY,
			YESTERDAY, null, null));
		assertEquals(1, both.buy().observations());
		assertEquals(1, both.sell().observations());
		assertSame(Grade.SOFT, both.grade());
		assertEquals("1,200 and 800 are 400 apart over a middle of 1,000: the word says how far", "spread 40%", both.word());
		assertEquals(Long.valueOf(40L), both.spreadPercent());

		// The edge: 24 hours to the second still counts, a second more does not.
		final long aDayAgo = NOW - GradeMath.LATEST_PRINT_MAX_AGE_SECONDS;
		assertEquals("spread 40%", oneDay(GradeMath.facts(NOW, TODAY, GUIDE, new TradedPriceClient.Quote(1_200L, twoHoursAgo,
			800L, aDayAgo), null, null, null, 0L, BUSY_YESTERDAY, YESTERDAY, null, null)).word());
		assertEquals(GradeWords.SOLID, oneDay(GradeMath.facts(NOW, TODAY, GUIDE, new TradedPriceClient.Quote(1_200L,
			twoHoursAgo, 800L, aDayAgo - 1L), null, null, null, 0L, BUSY_YESTERDAY, YESTERDAY, null, null)).word());

		// A fresh sell print of 5 gp is outside the anchor's range: the sell side has not reported, and the row is one-sided.
		final GradedMove junk = oneDay(GradeMath.facts(NOW, TODAY, GUIDE,
			new TradedPriceClient.Quote(1_200L, twoHoursAgo, 5L, twoHoursAgo), null, null, null, 0L, BUSY_YESTERDAY,
			YESTERDAY, null, null));
		assertEquals(0, junk.sell().observations());
		assertSame(Grade.SOLID, junk.grade());
		assertNull(junk.spreadPercent());
	}

	/**
	 * The price follows the sides observed: when the second side's first trade is reported the row moves from the first
	 * side's own price to the two-sided mark moved by the mean of the two moves - one jump - and the two-sided figure is
	 * the one it always was.
	 */
	@Test
	public void theSecondSidesFirstTradeMovesThePriceToTheMark()
	{
		final GradedMove buyOnly = oneDay(facts(quote(1_030L, 13, 50, null, 0, 0), prints(1_020L, 10, 0), prints(), null,
			0L, BUSY_YESTERDAY, null));
		final GradedMove both = oneDay(solidFacts());

		assertEquals(Long.valueOf(1_025L), buyOnly.price());
		assertEquals("990 + round(990 x 0.02526)", Long.valueOf(1_015L), both.price());
		assertEquals(Long.valueOf(1_000L), buyOnly.thenMark());
		assertEquals("the two-sided mark", Long.valueOf(990L), both.thenMark());
		assertSame(Grade.SOLID, buyOnly.grade());
		assertSame(Grade.SOLID, both.grade());
	}

	/**
	 * The fallback: nothing traded in the last 24 hours, so the figure is yesterday's move - yesterday's sides (1,100 and
	 * 1,080) against the day before's (1,000 and 1,000): +10 % and +8 %, mean +9 %. The mark is the day before's, so the
	 * price is 1,000 x 1.09 = 1,090 - yesterday's average (L4). Always SOFT, and the word says how long since the item last
	 * traded: its newest print was the buy at 12:00 yesterday and the clock reads 14:00 today, 26 hours, so "last 1d ago".
	 */
	@Test
	public void noTradeTodayFallsBackToYesterdaysMoveAndIsAlwaysSoft()
	{
		final GradedMove move = oneDay(ydayFacts());

		assertSame(Grade.SOFT, move.grade());
		assertEquals("last 1d ago", move.word());
		assertTrue(move.fallback());
		assertEquals(0.09d, move.move(), 1e-12);
		assertEquals(Long.valueOf(1_000L), move.thenMark());
		assertEquals(Long.valueOf(1_090L), move.price());
		assertEquals(Long.valueOf(1_100L), move.buy().now());
		assertEquals(Long.valueOf(1_000L), move.buy().then());
		assertEquals("now is yesterday", YESTERDAY, move.nowDay());
		assertEquals("then is the day before", DAY_BEFORE, move.thenDay());
	}

	/** A longer window's fallback compares yesterday with the window's own day (its day before is not fetched). */
	@Test
	public void aLongerWindowsFallbackComparesYesterdayWithTheWindowsDay()
	{
		final LocalDate weekAgo = TODAY.minusDays(7);
		final GradedMove move = GradeMath.figure(ydayFacts(), MovementWindow.D7, day(1_000L, 50L, 1_000L, 50L), weekAgo);

		assertTrue(move.fallback());
		assertEquals(0.09d, move.move(), 1e-12);
		assertEquals(weekAgo, move.thenDay());
		assertEquals("a longer window says the same: it is the item's last trade, not the window's", "last 1d ago",
			move.word());
	}

	@Test
	public void noTradeTodayAndNoFallbackPairIsNone()
	{
		final GradedMove move = oneDay(facts(quote(null, 0, 0, null, 0, 0), prints(), prints(), null, 0L,
			day(1_100L, 50L, 1_080L, 50L), null));

		assertSame(Grade.NONE, move.grade());
		assertEquals("no trades", move.word());
		assertFalse(move.hasFigure());
		assertNull(move.move());
		assertNull(move.price());
		assertNull(move.thenMark());
	}

	/** Trades in the last 24 hours but no "then" on either side: nothing to compare, and no fallback while there are trades. */
	@Test
	public void tradesTodayWithNoBaselineIsNone()
	{
		final GradedMove move = GradeMath.figure(solidFacts(), MovementWindow.D1, null, null);

		assertSame(Grade.NONE, move.grade());
		assertEquals(Long.valueOf(1_025L), move.buy().now());
	}

	// ---------------------------------------------------------------- L3: the three facts and their words

	/**
	 * "low vol N": the compared day's thinner side traded under ten units, and the number is that count - nine is thin and ten
	 * is not. The fact is read off the day's bucket alone (whichever side is the thinner), so the other side, the hour and
	 * the money play no part: ten units a side at 1,000 gp is 10k gp a side, and solid.
	 */
	@Test
	public void theWordVolumeIsAThinDay()
	{
		final TradedPriceClient.Quote pair = quote(1_030L, 13, 50, 1_010L, 13, 40);
		final GradedMove thin = oneDay(facts(pair, prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L,
			day(1_000L, 20_000L, 980L, 7L), null));
		assertSame(Grade.SOFT, thin.grade());
		assertEquals("low vol 7", thin.word());
		assertEquals("either side may be the thin one", "low vol 9", oneDay(facts(pair, prints(1_020L, 10, 0),
			prints(1_000L, 9, 0), null, 0L, day(1_000L, 9L, 980L, 20_000L), null)).word());
		final GradedMove ten = oneDay(facts(pair, prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L,
			day(1_000L, 10L, 980L, 10L), null));
		assertSame("ten units a side is not thin", Grade.SOLID, ten.grade());
		assertEquals(GradeWords.SOLID, ten.word());
		assertEquals("a side that did not trade that day is zero", "low vol 0", oneDay(facts(pair, prints(1_020L, 10, 0),
			prints(1_000L, 9, 0), null, 0L, day(1_000L, 20_000L, null, 0L), null)).word());
	}

	/**
	 * "spread 18%": the current pair is more than ten percent of its middle apart - 200 on 1,100 is 18 %, and the word says so.
	 * It is one of the three facts that make a figure soft, so a wide pair on a busy day is SOFT with this word; exactly a tenth
	 * of the middle is not wide and a hair over is. The number is {@link GradeMath#spreadPercent}.
	 */
	@Test
	public void theWordSpreadIsAWideCurrentPair()
	{
		final TradedPriceClient.Quote wide = quote(1_200L, 13, 50, 1_000L, 13, 40);
		final GradedMove move = oneDay(facts(wide, prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L, BUSY_YESTERDAY,
			null));

		assertSame(Grade.SOFT, move.grade());
		assertEquals("spread 18%", move.word());
		assertEquals("the number in the word", Long.valueOf(18L),
			GradeMath.spreadPercent(move.buy().last(), move.sell().last()));
		assertEquals("...which the figure carries for the open block", Long.valueOf(18L), move.spreadPercent());

		assertSame("1,050 and 950 are exactly a tenth of their middle apart: not wide", Grade.SOLID,
			oneDay(facts(quote(1_050L, 13, 50, 950L, 13, 40), prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L,
				BUSY_YESTERDAY, null)).grade());
		assertEquals("1,060 and 950 are over it: 110 on 1,005 is 11 %", "spread 11%", oneDay(facts(quote(1_060L, 13, 50, 950L, 13, 40),
			prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L, BUSY_YESTERDAY, null)).word());
	}

	/** The spread percentage: the two last prints apart, as a whole percentage of their middle, rounded half up. */
	@Test
	public void theSpreadPercentIsThePairApartOverItsMiddle()
	{
		assertEquals(Long.valueOf(18L), GradeMath.spreadPercent(1_200L, 1_000L));
		assertEquals("the order of the two does not matter", Long.valueOf(18L), GradeMath.spreadPercent(1_000L, 1_200L));
		assertEquals(Long.valueOf(0L), GradeMath.spreadPercent(500L, 500L));
		assertEquals("2 on 3 is 66.7 %", Long.valueOf(67L), GradeMath.spreadPercent(2L, 4L));
		assertNull("a side with no print", GradeMath.spreadPercent(null, 1_000L));
		assertNull(GradeMath.spreadPercent(1_000L, null));
		assertNull("no price at all", GradeMath.spreadPercent(0L, 1_000L));
		assertNull("a price past the ceiling is not measured", GradeMath.spreadPercent(Long.MAX_VALUE, 1_000L));
	}

	/**
	 * The sides-agree clause is NOT part of the solid test (the user, 2026-10-07): buyers paid 20 % more than yesterday
	 * and sellers got 3 % more - 17 points apart - and the figure is SOLID, no word. The method that says whether the sides
	 * agree is still true to the facts, for the open block's closing sentence.
	 */
	@Test
	public void sidesMovedApartStillMakeASolidFigure()
	{
		final GradedMove move = oneDay(facts(quote(1_210L, 13, 50, 1_150L, 13, 40), prints(1_190L, 10, 0),
			prints(1_000L, 9, 0, 1_010L, 10, 0), null, 0L, BUSY_YESTERDAY, null));

		assertSame(Grade.SOLID, move.grade());
		assertEquals(GradeWords.SOLID, move.word());
		assertFalse("the facts are still told as they are", move.sidesAgree());
	}

	/**
	 * The money, the hour and the price floor left the solid test (the user's final decision of 2026-10-07): 500 units a side
	 * yesterday is 490k gp and nowhere near the old 10m; a quiet hour (96k gp), an hour with one trade, one print a side and
	 * no hour at all, and an item worth 50 gp are all SOLID. The hour still counts as an observation: its average joins the
	 * print and moves the median.
	 */
	@Test
	public void moneyAndTheHourAndTheFloorNoLongerDecideTheGrade()
	{
		final TradedPriceClient.Quote pair = quote(1_030L, 13, 50, 1_010L, 13, 40);
		final long hourStart = seconds(TODAY, 12, 0);

		assertSame("490k gp a side", Grade.SOLID, oneDay(facts(pair, prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L,
			day(1_000L, 500L, 980L, 500L), null)).grade());
		assertSame("one print a side, no hour", Grade.SOLID, oneDay(facts(pair, prints(), prints(), null, 0L,
			BUSY_YESTERDAY, null)).grade());

		final GradedMove quietHour = oneDay(facts(pair, prints(), prints(), day(1_026L, 96L, 1_004L, 96L), hourStart,
			BUSY_YESTERDAY, null));
		assertSame("96k gp in the last hour", Grade.SOLID, quietHour.grade());
		assertEquals("the print and the hour", 2, quietHour.buy().observations());
		assertEquals("the hour's average moved the median: 1,030 and 1,026", Long.valueOf(1_028L), quietHour.buy().now());

		assertSame("one trade in the last hour", Grade.SOLID, oneDay(facts(pair, prints(), prints(),
			day(1_026L, 40L, 1_004L, 1L), hourStart, BUSY_YESTERDAY, null)).grade());

		final GradeMath.Facts cheap = GradeMath.facts(NOW, TODAY, 50L, quote(52L, 13, 50, 50L, 13, 40), prints(51L, 10, 0),
			prints(50L, 9, 0), null, 0L, day(50L, 1_000_000L, 49L, 1_000_000L), YESTERDAY, null, null);
		assertSame("a price under 100 gp", Grade.SOLID, oneDay(cheap).grade());
	}

	/** An hour that ended more than three hours ago is no hour at all: not an observation. */
	@Test
	public void anHourThatEndedFourHoursAgoIsNoObservation()
	{
		final TradedPriceClient.Quote pair = quote(1_030L, 13, 50, 1_010L, 13, 40);
		final GradedMove stale = oneDay(facts(pair, prints(), prints(), day(1_026L, 10_000L, 1_004L, 10_000L),
			seconds(TODAY, 9, 0), BUSY_YESTERDAY, null));
		assertEquals("the 09:00 hour ended four hours ago", 1, stale.buy().observations());
		assertEquals(Long.valueOf(1_030L), stale.buy().now());
	}

	/**
	 * A price outside the anchor's range is the one other way a measured figure can fail - the two sides moving violently opposite
	 * ways: yesterday's buys at 400 and sells at 2,900 against the guide's 1,000, today's buys at 2,900 and sells at 400 (the
	 * moves are +625 % and -86 %, their mean +269 % on a mark of 1,650: a price of 6,095 against a ceiling of 3,000). It is soft
	 * and, because a soft row always carries a word, says the plain "spread" - the sides are far apart, and there is no current
	 * pair to put a number on.
	 */
	@Test
	public void anImplausiblePriceIsSoftAndStillCarriesAWord()
	{
		final GradedMove move = oneDay(facts(quote(1_030L, 13, 50, 1_010L, 13, 40),
			prints(2_900L, 9, 0, 2_900L, 10, 0, 2_900L, 11, 0), prints(400L, 9, 0, 400L, 10, 0, 400L, 11, 0), null, 0L,
			day(400L, 20_000L, 2_900L, 20_000L), null));

		assertEquals(Long.valueOf(2_900L), move.buy().now());
		assertEquals(Long.valueOf(400L), move.sell().now());
		assertTrue("the premise: the price is outside three times the guide", move.price() > 3L * GUIDE);
		assertSame(Grade.SOFT, move.grade());
		assertEquals("spread", move.word());
		assertNull("no current spread, so no number", move.spreadPercent());
	}

	/**
	 * The user's rule in one place: a row with no word is solid and a soft row always has one of the three words - checked
	 * across the figures the tests above build, the fallback's and the no-figure row's included.
	 */
	@Test
	public void aSoftRowAlwaysCarriesAWordAndASolidRowNone()
	{
		final TradedPriceClient.Quote pair = quote(1_030L, 13, 50, 1_010L, 13, 40);
		final GradedMove[] figures = {
			oneDay(solidFacts()),
			oneDay(ydayFacts()),
			oneDay(facts(pair, prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L, day(1_000L, 20_000L, 980L, 7L), null)),
			oneDay(facts(quote(1_200L, 13, 50, 1_000L, 13, 40), prints(), prints(), null, 0L, BUSY_YESTERDAY, null)),
			oneDay(facts(quote(1_030L, 13, 50, null, 0, 0), prints(), prints(), null, 0L, BUSY_YESTERDAY, null)),
			oneDay(facts(quote(null, 0, 0, null, 0, 0), prints(), prints(), null, 0L, day(1_100L, 50L, 1_080L, 50L), null)),
			oneDay(facts(pair, prints(2_900L, 9, 0, 2_900L, 10, 0, 2_900L, 11, 0), prints(400L, 9, 0, 400L, 10, 0, 400L, 11, 0),
				null, 0L, day(400L, 20_000L, 2_900L, 20_000L), null))};
		for (final GradedMove figure : figures)
		{
			switch (figure.grade())
			{
				case SOLID:
					assertEquals("a solid row has no word: " + figure, GradeWords.SOLID, figure.word());
					break;
				case SOFT:
					assertFalse("a soft row has a word: " + figure, figure.word().isEmpty());
					break;
				default:
					assertEquals("no figure: " + figure, GradeWords.NO_TRADES, figure.word());
			}
		}
	}

	/**
	 * The ORDER (the user, 2026-10-07): the fallback outranks the other two - nothing observed, a thin yesterday and a wide pair
	 * still say how long since the last trade - and a thin day outranks a wide pair; the wide pair on a busy day says
	 * "spread 19%" (210 on 1,105).
	 */
	@Test
	public void theFirstFailingFactDecidesTheWord()
	{
		final TradedPriceClient.Quote wide = quote(1_210L, 13, 50, 1_000L, 13, 40);
		assertEquals("a thin day outranks a wide pair", "low vol 7", oneDay(facts(wide, prints(1_190L, 10, 0),
			prints(990L, 9, 0), null, 0L, day(1_000L, 20_000L, 980L, 7L), null)).word());
		assertEquals("spread 19%", oneDay(facts(wide, prints(1_190L, 10, 0), prints(990L, 9, 0), null, 0L, BUSY_YESTERDAY, null))
			.word());

		final GradedMove fallback = oneDay(GradeMath.facts(NOW, TODAY, GUIDE, new TradedPriceClient.Quote(1_210L,
			seconds(YESTERDAY, 12, 0), 1_000L, seconds(YESTERDAY, 11, 0)), null, null, null, 0L,
			day(1_100L, 5L, 1_080L, 5L), YESTERDAY, day(1_000L, 50L, 1_000L, 50L), DAY_BEFORE));
		assertTrue(fallback.fallback());
		assertEquals("the fallback outranks both", "last 1d ago", fallback.word());
	}

	/**
	 * The fallback speaks FIRST, whatever else is wrong with the figure (a thin yesterday here), and it says how long since
	 * the item last traded - the newer of the two current prints the figure would count, against the clock. The fallback
	 * only fires with both sides silent for 24 hours, so a plausible print is more than a day old and the word counts days
	 * ("last 1d ago" from 24 h, "last 2d ago" from 48 h). "yday" is only the word for a fallback with no usable print time.
	 */
	@Test
	public void theFallbackSaysHowLongSinceTheItemLastTradedOrYdayWithoutATime()
	{
		final TradedPriceClient.Bucket thinYesterday = day(1_100L, 5L, 1_080L, 50L);
		final TradedPriceClient.Bucket dayBefore = day(1_000L, 50L, 1_000L, 50L);

		// No print at all, and a pair whose prices carry no time: nothing to count from.
		final GradedMove noPrint = oneDay(facts(quote(null, 0, 0, null, 0, 0), prints(), prints(), null, 0L, thinYesterday,
			dayBefore));
		assertTrue(noPrint.fallback());
		assertEquals("no print time is known: the old word", "yday", noPrint.word());
		assertEquals("a price with no time is no time", "yday", oneDay(facts(new TradedPriceClient.Quote(1_100L, 0L, 1_080L,
			0L), prints(), prints(), null, 0L, thinYesterday, dayBefore)).word());
		assertEquals("no pair at all", "yday", oneDay(facts(null, prints(), prints(), null, 0L, thinYesterday, dayBefore))
			.word());

		// The clock reads 14:00. 26 hours is a day; the newer print of the two counts; 36 hours is still a day; 50 is two.
		assertEquals("last 1d ago", fallbackWord(NOW, 1_100L, seconds(YESTERDAY, 12, 0), 1_080L, seconds(YESTERDAY, 11, 0)));
		assertEquals("the sell side's print is the newer one", "last 1d ago",
			fallbackWord(NOW, 1_100L, seconds(DAY_BEFORE, 12, 0), 1_080L, seconds(YESTERDAY, 2, 0)));
		assertEquals("the buy side's print is the newer one", "last 1d ago",
			fallbackWord(NOW, 1_100L, seconds(YESTERDAY, 2, 0), 1_080L, seconds(DAY_BEFORE, 12, 0)));
		assertEquals("two days", "last 2d ago", fallbackWord(NOW, 1_100L, seconds(DAY_BEFORE, 12, 0), 1_080L,
			seconds(DAY_BEFORE, 11, 0)));
		assertEquals("one side only", "last 1d ago", fallbackWord(NOW, 1_100L, seconds(YESTERDAY, 12, 0), null, 0L));
		assertEquals("the other side only", "last 1d ago", fallbackWord(NOW, null, 0L, 1_080L, seconds(YESTERDAY, 12, 0)));

		// The edge: 24 hours to the second is still an observation, so the figure is measured and no fallback at all; a second
		// more is the fallback, and a day old.
		final long aDayAgo = NOW - GradeMath.LATEST_PRINT_MAX_AGE_SECONDS;
		assertFalse(fallbackFigure(NOW, 1_100L, aDayAgo, 1_080L, aDayAgo).fallback());
		assertEquals("last 1d ago", fallbackWord(NOW, 1_100L, aDayAgo - 1L, 1_080L, aDayAgo - 1L));

		// A print outside the anchor's range is no trade the figure counts, so it is none the word counts: a 5 gp buy three
		// hours ago leaves the sell print of yesterday to speak, and without one the word has no time.
		assertEquals("last 1d ago", fallbackWord(NOW, 5L, seconds(TODAY, 11, 0), 1_080L, seconds(YESTERDAY, 12, 0)));
		assertEquals("yday", fallbackWord(NOW, 5L, seconds(TODAY, 11, 0), null, 0L));

		// It is still the fallback's figure and still soft, whatever the word.
		assertSame(Grade.SOFT, noPrint.grade());
	}

	/** The fallback figure's word for a pair last printed at the given seconds, taken with the clock at {@code now}. */
	private static String fallbackWord(final long now, final Long buy, final long buySeconds, final Long sell,
		final long sellSeconds)
	{
		final GradedMove move = fallbackFigure(now, buy, buySeconds, sell, sellSeconds);
		assertTrue("a fallback figure", move.fallback());
		return move.word();
	}

	/** The 1d figure of an item whose current pair was last printed at the given seconds, the clock at {@code now}. */
	private static GradedMove fallbackFigure(final long now, final Long buy, final long buySeconds, final Long sell,
		final long sellSeconds)
	{
		return oneDay(GradeMath.facts(now, TODAY, GUIDE, new TradedPriceClient.Quote(buy, buySeconds, sell, sellSeconds),
			null, null, null, 0L, day(1_100L, 50L, 1_080L, 50L), YESTERDAY, day(1_000L, 50L, 1_000L, 50L), DAY_BEFORE));
	}

	/**
	 * {@link GradeWords#lastTraded}: whole hours below 24 h, whole days from it, rounded down, "last 1h ago" under an hour
	 * (it never says 0), a negative age read as 0, and two digits of days at most.
	 */
	@Test
	public void theLastTradedWordCountsWholeHoursThenWholeDays()
	{
		assertEquals("last 1h ago", GradeWords.lastTraded(0L));
		assertEquals("a clock behind the print reads as 0", "last 1h ago", GradeWords.lastTraded(-90L));
		assertEquals("59 min", "last 1h ago", GradeWords.lastTraded(59L * 60L));
		assertEquals("1 h", "last 1h ago", GradeWords.lastTraded(3_600L));
		assertEquals("1 h 59 min 59 s", "last 1h ago", GradeWords.lastTraded(2L * 3_600L - 1L));
		assertEquals("2 h", "last 2h ago", GradeWords.lastTraded(2L * 3_600L));
		assertEquals("5 h", "last 5h ago", GradeWords.lastTraded(5L * 3_600L));
		assertEquals("9 h 59 min", "last 9h ago", GradeWords.lastTraded(10L * 3_600L - 60L));
		assertEquals("10 h", "last 10h ago", GradeWords.lastTraded(10L * 3_600L));
		assertEquals("23 h 59 min", "last 23h ago", GradeWords.lastTraded(24L * 3_600L - 60L));
		assertEquals("23 h 59 min 59 s", "last 23h ago", GradeWords.lastTraded(24L * 3_600L - 1L));
		assertEquals("24 h", "last 1d ago", GradeWords.lastTraded(24L * 3_600L));
		assertEquals("47 h 59 min", "last 1d ago", GradeWords.lastTraded(48L * 3_600L - 60L));
		assertEquals("48 h", "last 2d ago", GradeWords.lastTraded(48L * 3_600L));
		assertEquals("9 days", "last 9d ago", GradeWords.lastTraded(9L * 24L * 3_600L));
		assertEquals("12 days", "last 12d ago", GradeWords.lastTraded(12L * 24L * 3_600L));
		assertEquals("two digits of days is the most the slot holds", "last 99d ago",
			GradeWords.lastTraded(500L * 24L * 3_600L));
		assertEquals("last 99d ago", GradeWords.lastTraded(Long.MAX_VALUE));
	}

	// ---------------------------------------------------------------- the open block's sentence

	/** The open block's closing sentence: the two side moves at most ten points apart agree - ten exactly still agrees. */
	@Test
	public void sidesTenPointsApartStillAgree()
	{
		assertTrue(GradeMath.sidesAgree(0.15d, 0.05d));
		assertTrue(GradeMath.sidesAgree(0.20d, 0.10d));
		assertFalse(GradeMath.sidesAgree(0.2001d, 0.10d));
		assertFalse("a missing side does not agree", GradeMath.sidesAgree(0.1d, null));
	}

	// ---------------------------------------------------------------- parts

	/** A parts stack: the parts' marks and prices summed; SOFT when any part is, with that part's word; NONE when any is. */
	@Test
	public void aPartsFigureIsTheSumOfItsPartsAndItsWorstGrade()
	{
		final GradedMove solid = oneDay(solidFacts());
		final GradedMove soft = oneDay(ydayFacts());
		final Map<Integer, GradedMove> moves = new HashMap<>();
		moves.put(1, solid);
		moves.put(2, soft);

		final GradedMove both = GradeMath.combineParts(Arrays.asList(new BankItem.Part(1, 3L, "Part one"),
			new BankItem.Part(2, 1L, "Part two")), moves::get);
		assertSame(Grade.SOFT, both.grade());
		assertEquals("the soft part's word travels with the sum", "last 1d ago", both.word());
		assertEquals(Long.valueOf(3L * 990L + 1_000L), both.thenMark());
		assertEquals(Long.valueOf(3L * 1_015L + 1_090L), both.price());

		moves.put(2, oneDay(facts(quote(null, 0, 0, null, 0, 0), prints(), prints(), null, 0L, null, null)));
		assertSame(Grade.NONE, GradeMath.combineParts(Arrays.asList(new BankItem.Part(1, 3L, "Part one"),
			new BankItem.Part(2, 1L, "Part two")), moves::get).grade());
	}

	// ---------------------------------------------------------------- L3: the card

	/**
	 * The card is SOFT when its solid rows hold under 90 % of the counted value or its soft rows supply over 10 % of its
	 * gp move - 90 % and 10 % exactly are not soft.
	 */
	@Test
	public void theCardIsSoftWhenSolidRowsHoldUnderNinetyPercentOrSoftRowsMoveOverTen()
	{
		assertFalse("solid 90 of 100, soft move 10 of 100", card(90L, 10L, 0L, 90L, 10L).soft());
		assertTrue("solid 89 of 100", card(89L, 11L, 0L, 90L, 10L).soft());
		assertTrue("soft move 11 of 100", card(95L, 5L, 0L, 89L, 11L).soft());
		assertFalse("solid 95, soft move 5", card(95L, 5L, 0L, 95L, 5L).soft());
		assertTrue("a NONE row's value is counted and not solid", card(85L, 0L, 15L, 100L, 0L).soft());
		assertFalse("nothing graded is never soft", GradeSummary.NONE.soft());
	}

	@Test
	public void theCardCountsTheRowsOfEachGrade()
	{
		final GradeSummary summary = card(90L, 10L, 5L, 90L, 10L);
		assertEquals(1, summary.solidRows());
		assertEquals(1, summary.softRows());
		assertEquals(1, summary.noneRows());
		assertEquals(3, summary.gradedRows());
		assertEquals(105L, summary.countedValue());
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * A card of three rows: a solid one worth {@code solidValue} moving {@code solidMove}, a soft one worth
	 * {@code softValue} moving {@code softMove}, and (when {@code noneValue} is above 0) a NONE one worth that.
	 */
	private static GradeSummary card(final long solidValue, final long softValue, final long noneValue,
		final long solidMove, final long softMove)
	{
		final GradeSummary.Tally tally = new GradeSummary.Tally(true);
		tally.add(row(1, solidValue, solidMove, oneDay(solidFacts())));
		tally.add(row(2, softValue, softMove, oneDay(ydayFacts())));
		if (noneValue > 0L)
		{
			tally.add(row(3, noneValue, 0L, oneDay(facts(quote(null, 0, 0, null, 0, 0), prints(), prints(), null, 0L,
				null, null))));
		}
		return tally.done();
	}

	/** One row of quantity 1 worth {@code value} whose move is {@code move} gp, carrying {@code graded}. */
	private static MovementRow row(final int id, final long value, final long move, final GradedMove graded)
	{
		final boolean moved = graded.hasFigure();
		return new MovementRow(id, "Item " + id, 1, false, value, moved ? value - move : null, moved ? move : null,
			moved ? 1.0d : null, value, MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0, graded);
	}

	/** Two prints a side today and a busy yesterday: the solid figure of {@link #bothSidesMeasuredMakeASolidFigure}. */
	private static GradeMath.Facts solidFacts()
	{
		return facts(quote(1_030L, 13, 50, 1_010L, 13, 40), prints(1_020L, 10, 0), prints(1_000L, 9, 0), null, 0L,
			BUSY_YESTERDAY, null);
	}

	/**
	 * No trade in the last 24 hours (the last prints were at 12:00 and 11:00 yesterday, 26 and 27 hours before the clock);
	 * yesterday 1,100 / 1,080 (50 a side), the day before 1,000 / 1,000 (50 a side).
	 */
	private static GradeMath.Facts ydayFacts()
	{
		final TradedPriceClient.Quote lastNight = new TradedPriceClient.Quote(1_100L, seconds(YESTERDAY, 12, 0), 1_080L,
			seconds(YESTERDAY, 11, 0));
		return GradeMath.facts(NOW, TODAY, GUIDE, lastNight, null, null, null, 0L, day(1_100L, 50L, 1_080L, 50L),
			YESTERDAY, day(1_000L, 50L, 1_000L, 50L), DAY_BEFORE);
	}

	private static GradeMath.Facts facts(final TradedPriceClient.Quote quote, final long[] buyPrints,
		final long[] sellPrints, final TradedPriceClient.Bucket hour, final long hourStart,
		final TradedPriceClient.Bucket d1, final TradedPriceClient.Bucket d2)
	{
		return GradeMath.facts(NOW, TODAY, GUIDE, quote, buyPrints, sellPrints, hour, hourStart, d1,
			d1 == null ? null : YESTERDAY, d2, d2 == null ? null : DAY_BEFORE);
	}

	private static GradedMove oneDay(final GradeMath.Facts facts)
	{
		return GradeMath.figure(facts, MovementWindow.D1, facts.d1, facts.d1Day);
	}

	/** A current pair whose sides traded today at the given times; a null side has no print. */
	private static TradedPriceClient.Quote quote(final Long buy, final int buyHour, final int buyMinute,
		final Long sell, final int sellHour, final int sellMinute)
	{
		return new TradedPriceClient.Quote(buy, buy == null ? 0L : seconds(TODAY, buyHour, buyMinute), sell,
			sell == null ? 0L : seconds(TODAY, sellHour, sellMinute));
	}

	/** Poll-memory prints of today: {@code price, hour, minute} triples. */
	private static long[] prints(final long... triples)
	{
		final long[] pairs = new long[triples.length / 3 * 2];
		for (int i = 0; i < triples.length / 3; i++)
		{
			pairs[2 * i] = triples[3 * i];
			pairs[2 * i + 1] = seconds(TODAY, (int) triples[3 * i + 1], (int) triples[3 * i + 2]);
		}
		return pairs;
	}

	private static TradedPriceClient.Bucket day(final Long avgHigh, final long highVolume, final Long avgLow,
		final long lowVolume)
	{
		return new TradedPriceClient.Bucket(avgHigh, highVolume, avgLow, lowVolume);
	}

	private static long seconds(final LocalDate day, final int hour, final int minute)
	{
		return day.atTime(hour, minute).toEpochSecond(ZoneOffset.UTC);
	}
}
