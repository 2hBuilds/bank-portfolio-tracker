package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The graded live figure of contract 1.2.0 ({@code docs/handoff/contract-1.2.0-graded-live-moves-2026-10-07.md}), in
 * ONE pure place: the plausibility anchor and the last 24 hours' observations (L2), the same-side figure for a window,
 * its fallback and its grade with the word that names why (L3), and every threshold those rules use - each a named
 * constant here, so a threshold is changed by changing one line (the user, 2026-10-07: "use your recommendations for
 * now and we can always change them quickly during testing").
 *
 * <p>Pure on purpose, like {@link MovementMath}: {@code PriceService} gathers the inputs on its executor and asks this
 * class, and the tests ask it the same questions with synthetic items, no client and no clock.
 *
 * <p><b>Why same-side.</b> The two sides of the exchange sit a spread apart, and the newest print flips between them
 * as buyers and sellers take turns: a "now" that is the last print, compared against a day's average of both, moves
 * by the spread with every trade (the comparator's R2 plan put 82 rows over 20 % a day). The last 24 hours' buys are
 * compared with yesterday's buys and the last 24 hours' sells with yesterday's sells, and each side's "now" is the
 * MEDIAN of its observations in those 24 hours, so one silly print cannot carry it - and the 3x anchor throws out the
 * 1 gp prints that would. "Today" is a rolling 24 hours and not the UTC calendar day (the user, 2026-10-07: measured on
 * a bank 2.7 h into the UTC day, 33 rows had a silent side by the calendar rule and 3 by the 24-hour one).
 *
 * <p><b>One side.</b> When exactly one side has observations the row is priced at that side's own median and compared
 * with that side's own average on the window's day (the user: "if all the trades were buyers paying that, that is the
 * current price"), and it is no weaker for it: it is solid when the same facts hold. With both sides the price is the
 * two-sided mark moved by the mean of the two moves.
 *
 * <p><b>Solid or soft</b> (the user's final decision, 2026-10-07). A figure is SOFT only when one of three facts holds,
 * and carries the word of the first: nothing traded in the last 24 hours (the fallback, {@link GradeWords#lastTraded}),
 * the compared day's thinner side under {@value #WORD_MIN_DAY_COUNT} units ({@link GradeWords#volume}), or the current
 * pair - both sides reported in the last 24 hours - more than {@value #WORD_MAX_QUOTE_GAP_PCT} % of its middle apart
 * ({@link GradeWords#spread}, which says how far). Everything else is SOLID and carries no word.
 */
final class GradeMath
{
	// ---------------------------------------------------------------- L2: the anchor and the observations

	/** A price is plausible within this factor of the anchor A, either way: {@code A / 3 <= p <= 3 x A} (L2). */
	static final long PLAUSIBLE_FACTOR = 3L;

	/**
	 * How far back "today" reaches (L2, the user's 2026-10-07 decision): a print of a side - the current {@code /latest}
	 * one, or one the poll memory holds - counts as an observation only up to this old, 24 hours, whatever UTC day it
	 * is of.
	 */
	static final long LATEST_PRINT_MAX_AGE_SECONDS = 24L * 60L * 60L;

	/** The {@code /1h} bucket's span. */
	static final long HOUR_SECONDS = 60L * 60L;

	/** The {@code /1h} side average counts as an observation while the bucket ended at most this long ago: 3 h (L2). */
	static final long HOUR_MAX_AGE_SECONDS = 3L * HOUR_SECONDS;

	/** A side average counts only when at least this many units traded on that side (L2): one. */
	static final long MIN_SIDE_VOLUME = 1L;

	/**
	 * The largest price reasoned about, above which a figure is not measured: {@link Long#MAX_VALUE} / 200, so no
	 * comparison here can wrap (the gap test multiplies by 100). Four orders of magnitude past the dearest item.
	 */
	static final long PRICE_CEILING = Long.MAX_VALUE / 200L;

	// ---------------------------------------------------------------- L3: the solid test and its words

	/**
	 * The compared day's thinner side must have traded at least this many units for a figure to be solid, and under it the
	 * word {@link GradeWords#volume} is said: ten (L3). The user's final decision of 2026-10-07 made this, the pair's gap
	 * and the fallback the only three facts that make a figure soft; the money floors, the hour's floor, the observation
	 * count, the 100 gp price floor and the sides-agree clause left the test.
	 */
	static final long WORD_MIN_DAY_COUNT = 10L;

	/**
	 * The word {@link GradeWords#spread} fires - and the figure is soft - when the current pair's gap is over this
	 * percentage of its middle: ten.
	 */
	static final int WORD_MAX_QUOTE_GAP_PCT = 10;

	/**
	 * Two side moves "agree" when they are at most this many percentage points apart: ten. It is no part of the solid test
	 * (the user's first live look retired the sides-agree clause); it only picks which of two sentences the open block
	 * closes on ({@link #sidesAgree}).
	 */
	static final int SOLID_MAX_SIDE_GAP_POINTS = 10;

	/**
	 * How far two side moves may differ and still be "within ten points", in points, to absorb the binary rounding of a
	 * difference that is exactly ten points in decimal (1.2 - 1.1 is not 0.1 in a double).
	 */
	private static final double SIDE_GAP_TOLERANCE_POINTS = 1e-9d;

	// ---------------------------------------------------------------- L3: the card

	/** The card is SOFT when its solid rows hold less than this percentage of the counted value (L3). */
	static final int CARD_SOLID_VALUE_PCT = 90;

	/** ... or when its soft rows supply more than this percentage of its gp move (L3). */
	static final int CARD_SOFT_MOVE_PCT = 10;

	// ---------------------------------------------------------------- L1: the inputs' cadence

	/**
	 * At most this many {@code /1h} requests in one UTC day (L1): one per closed hour, and a retry of an hour the wiki
	 * had not cut yet counts as a request, so a late wiki can use the day's twenty-four up before the day ends - the
	 * hour then goes without, and the figures carry on with today's prints (the no-hour rule of L3).
	 */
	static final int HOUR_MAX_FETCHES_PER_DAY = 24;

	/**
	 * How long after an EMPTY {@code /1h} answer (the wiki has not cut the hour yet) the same hour is asked for again,
	 * milliseconds: five minutes. The wiki's cut lands within a minute or two of the top of the hour; the empty answer
	 * carries {@code Cache-Control: no-cache}, so a retry reaches the origin and is not served the empty one.
	 */
	static final long HOUR_RETRY_MILLIS = 5L * 60L * 1000L;

	/**
	 * How many times one closed hour is asked for in all (the first request and its retries): three. An hour still empty
	 * after that is left (its figures carry on with today's prints) until the next hour closes.
	 */
	static final int HOUR_MAX_TRIES_PER_HOUR = 3;

	/**
	 * How many distinct prints of one side of one item the poll memory keeps (L1), the oldest dropped first: 48, a
	 * whole day of the thirty-minute tick. A bound on memory, never reached by the tick alone.
	 */
	static final int POLL_MEMORY_MAX_PRINTS = 48;

	private GradeMath()
	{
	}

	// ---------------------------------------------------------------- one item's facts, the same for every window

	/**
	 * One side's observations of the last 24 hours: how many there were and their median (L2). Immutable.
	 */
	static final class Observed
	{
		static final Observed NONE = new Observed(0, null);

		final int count;
		@Nullable
		final Long median;

		Observed(final int count, @Nullable final Long median)
		{
			this.count = count;
			this.median = median;
		}
	}

	/**
	 * What one item's figures are made from, the same for every window: the anchor, the last 24 hours' observations per
	 * side (the {@code /1h} bucket's averages among them), the current {@code /latest} pair and yesterday's and the day
	 * before's buckets. Built by {@link #facts}; immutable.
	 */
	static final class Facts
	{
		/** The clock the facts were taken at, unix seconds: how old a print and an hour are, and how long ago the item last traded. */
		final long nowSeconds;
		@Nullable
		final LocalDate today;
		@Nullable
		final Long anchor;
		final Observed buy;
		final Observed sell;
		@Nullable
		final TradedPriceClient.Quote quote;
		@Nullable
		final TradedPriceClient.Bucket d1;
		@Nullable
		final LocalDate d1Day;
		@Nullable
		final TradedPriceClient.Bucket d2;
		@Nullable
		final LocalDate d2Day;

		private Facts(final long nowSeconds, @Nullable final LocalDate today, @Nullable final Long anchor,
			final Observed buy, final Observed sell, @Nullable final TradedPriceClient.Quote quote,
			@Nullable final TradedPriceClient.Bucket d1, @Nullable final LocalDate d1Day,
			@Nullable final TradedPriceClient.Bucket d2, @Nullable final LocalDate d2Day)
		{
			this.nowSeconds = nowSeconds;
			this.today = today;
			this.anchor = anchor;
			this.buy = buy;
			this.sell = sell;
			this.quote = quote;
			this.d1 = d1;
			this.d1Day = d1Day;
			this.d2 = d2;
			this.d2Day = d2Day;
		}
	}

	/**
	 * One item's facts (L1, L2): its anchor, and the plausible observations of each side in the last 24 hours - every
	 * distinct print the poll memory holds, the current {@code /latest} print, each when it is at most
	 * {@value #LATEST_PRINT_MAX_AGE_SECONDS} seconds old, and the {@code /1h} side average when that side traded and the
	 * hour ended at most {@value #HOUR_MAX_AGE_SECONDS} seconds ago.
	 *
	 * @param nowSeconds the clock, unix seconds
	 * @param today      the live day - the UTC date of the {@code /latest} snapshot; it labels the figure (the day "now"
	 *                   is of) and no longer decides which prints count. Null: there is no live snapshot and nothing is
	 *                   observed
	 * @param guideNow   the item's guide price now (RuneLite's, or the newest guide table's), the anchor's first value
	 * @param quote      the item's current {@code /latest} pair, or null
	 * @param buyPrints  the poll memory's buy prints of the item as {@code price, seconds} pairs; null or empty for none
	 * @param sellPrints the same for the sell side
	 * @param hour       the item's {@code /1h} bucket, or null when the feed does not name it
	 * @param hourStart  the {@code /1h} bucket's start, unix seconds; 0 when there is no {@code /1h} feed in hand
	 * @param d1         yesterday's bucket for the item, or null
	 * @param d1Day      the day {@code d1} records
	 * @param d2         the day before yesterday's bucket for the item ({@code traded-D2.json}), or null
	 * @param d2Day      the day {@code d2} records
	 */
	static Facts facts(final long nowSeconds, @Nullable final LocalDate today, @Nullable final Long guideNow,
		@Nullable final TradedPriceClient.Quote quote, @Nullable final long[] buyPrints, @Nullable final long[] sellPrints,
		@Nullable final TradedPriceClient.Bucket hour, final long hourStart, @Nullable final TradedPriceClient.Bucket d1,
		@Nullable final LocalDate d1Day, @Nullable final TradedPriceClient.Bucket d2, @Nullable final LocalDate d2Day)
	{
		final Long anchor = anchor(guideNow, d1, d2);
		final boolean hourFresh = hourStart > 0L && nowSeconds - (hourStart + HOUR_SECONDS) <= HOUR_MAX_AGE_SECONDS;
		final Observed buy = observe(true, nowSeconds, today, buyPrints, quote, hour, hourFresh, anchor);
		final Observed sell = observe(false, nowSeconds, today, sellPrints, quote, hour, hourFresh, anchor);
		return new Facts(nowSeconds, today, anchor, buy, sell, quote, d1, d1Day, d2, d2Day);
	}

	/**
	 * The plausibility anchor A (L2): the median of the values present among the guide price now and yesterday's two
	 * side averages - the day before's two when yesterday's bucket names neither side. Null when none is present, and
	 * then no price is plausible.
	 */
	@Nullable
	static Long anchor(@Nullable final Long guideNow, @Nullable final TradedPriceClient.Bucket d1,
		@Nullable final TradedPriceClient.Bucket d2)
	{
		final long[] values = new long[3];
		int n = 0;
		if (sane(guideNow))
		{
			values[n++] = guideNow;
		}
		final TradedPriceClient.Bucket day = d1 != null && (d1.avgHigh() != null || d1.avgLow() != null) ? d1 : d2;
		if (day != null)
		{
			if (sane(day.avgHigh()))
			{
				values[n++] = day.avgHigh();
			}
			if (sane(day.avgLow()))
			{
				values[n++] = day.avgLow();
			}
		}
		return median(values, n);
	}

	/**
	 * Whether a price is plausible against the anchor (L2): {@code A / 3 <= p <= 3 x A}, in integers ({@code 3p >= A}
	 * and {@code p <= 3A}). No anchor, no price, or a price past {@link #PRICE_CEILING}: not plausible.
	 */
	static boolean plausible(@Nullable final Long price, @Nullable final Long anchor)
	{
		if (!sane(price) || !sane(anchor))
		{
			return false;
		}
		return price * PLAUSIBLE_FACTOR >= anchor && price <= anchor * PLAUSIBLE_FACTOR;
	}

	/**
	 * The median of the first {@code n} values (L2): the middle one, or the mean of the middle two - rounded half up,
	 * in halves so no pair can overflow - when {@code n} is even. Null for none.
	 */
	@Nullable
	static Long median(final long[] values, final int n)
	{
		if (values == null || n <= 0)
		{
			return null;
		}
		final long[] sorted = Arrays.copyOf(values, Math.min(n, values.length));
		Arrays.sort(sorted);
		final int middle = sorted.length / 2;
		if (sorted.length % 2 == 1)
		{
			return sorted[middle];
		}
		return halfUpMean(sorted[middle - 1], sorted[middle]);
	}

	/** One side's observations of the last 24 hours (L2), plausible ones only. */
	private static Observed observe(final boolean buySide, final long nowSeconds, @Nullable final LocalDate today,
		@Nullable final long[] prints, @Nullable final TradedPriceClient.Quote quote,
		@Nullable final TradedPriceClient.Bucket hour, final boolean hourFresh, @Nullable final Long anchor)
	{
		if (today == null)
		{
			return Observed.NONE;
		}
		final int printed = prints == null ? 0 : prints.length / 2;
		final long[] values = new long[printed + 2];
		final long[] times = new long[printed + 1];
		int n = 0;
		for (int i = 0; i < printed; i++)
		{
			final long price = prints[2 * i];
			final long seconds = prints[2 * i + 1];
			if (price > 0L && seconds > 0L && nowSeconds - seconds <= LATEST_PRINT_MAX_AGE_SECONDS
				&& !seen(values, times, n, price, seconds))
			{
				times[n] = seconds;
				values[n++] = price;
			}
		}
		if (quote != null)
		{
			final Long price = buySide ? quote.buy() : quote.sell();
			final long seconds = buySide ? quote.buySeconds() : quote.sellSeconds();
			if (price != null && price > 0L && seconds > 0L && nowSeconds - seconds <= LATEST_PRINT_MAX_AGE_SECONDS
				&& !seen(values, times, n, price, seconds))
			{
				times[n] = seconds;
				values[n++] = price;
			}
		}
		if (hourFresh && hour != null)
		{
			final Long average = buySide ? hour.avgHigh() : hour.avgLow();
			final long volume = buySide ? hour.highVolume() : hour.lowVolume();
			if (average != null && average > 0L && volume >= MIN_SIDE_VOLUME)
			{
				values[n++] = average;
			}
		}
		final long[] kept = new long[n];
		int k = 0;
		for (int i = 0; i < n; i++)
		{
			if (plausible(values[i], anchor))
			{
				kept[k++] = values[i];
			}
		}
		return k == 0 ? Observed.NONE : new Observed(k, median(kept, k));
	}

	/** Whether {@code price} at {@code seconds} is among the first {@code n} prints already taken. */
	private static boolean seen(final long[] values, final long[] times, final int n, final long price,
		final long seconds)
	{
		for (int i = 0; i < n && i < times.length; i++)
		{
			if (values[i] == price && times[i] == seconds)
			{
				return true;
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- L2 and L3: one window's figure

	/**
	 * One window's graded figure (L2, L3) for an item whose {@link Facts} are in hand:
	 * <ul>
	 * <li>MEASURED when a side has both today's "now" and a "then" on the window's day (volume at least
	 * {@value #MIN_SIDE_VOLUME}, inside the anchor): the side moves, their mean, the mark and the grade;</li>
	 * <li>otherwise, when NO side was observed today, the FALLBACK - yesterday's move: yesterday's side averages against
	 * the day before's for 1d ({@code traded-D2.json}), and against the window's own day for a longer window (a day
	 * short of the window: the day before the window's day is not fetched). Always SOFT;</li>
	 * <li>otherwise NONE - "no trades".</li>
	 * </ul>
	 *
	 * @param facts  the item's facts
	 * @param window the window; null reads as 1d
	 * @param dN     the window's day's bucket for the item, or null
	 * @param dNDay  the day {@code dN} records
	 */
	static GradedMove figure(final Facts facts, @Nullable final MovementWindow window,
		@Nullable final TradedPriceClient.Bucket dN, @Nullable final LocalDate dNDay)
	{
		final Long anchor = facts.anchor;
		final Long lastBuy = facts.quote == null ? null : facts.quote.buy();
		final long lastBuyAt = facts.quote == null ? 0L : facts.quote.buySeconds();
		final Long lastSell = facts.quote == null ? null : facts.quote.sell();
		final long lastSellAt = facts.quote == null ? 0L : facts.quote.sellSeconds();

		final Long thenBuy = sideAverage(dN, true, anchor);
		final Long thenSell = sideAverage(dN, false, anchor);
		final Double buyMove = ratio(facts.buy.median, thenBuy);
		final Double sellMove = ratio(facts.sell.median, thenSell);
		if (buyMove != null || sellMove != null)
		{
			final double move = mean(buyMove, sellMove);
			// With exactly ONE side observed the row is priced at that side's own price against that side's own average
			// (the user: "if all the trades were buyers paying that, that is the current price"), so its worth, its was
			// and its change all stand on the one side. With both sides observed it is the two-sided mark moved by the
			// mean of the moves, as before.
			final boolean buyOnly = facts.buy.count > 0 && facts.sell.count == 0 && buyMove != null;
			final boolean sellOnly = facts.sell.count > 0 && facts.buy.count == 0 && sellMove != null;
			final long thenMark = buyOnly ? thenBuy : sellOnly ? thenSell : mark(thenBuy, thenSell, anchor);
			final long deltaGp = buyOnly ? facts.buy.median - thenBuy : sellOnly ? facts.sell.median - thenSell
				: deltaOf(thenMark, move);
			final long price = thenMark + deltaGp;
			// SOLID (the user's final decision, 2026-10-07): a figure that exists, inside the anchor's range, with none of the
			// three facts that name a word - not the fallback, the compared day's thinner side at least ten units, the
			// pair's gap at most a tenth of its middle. A figure reported on one side only is as solid as one reported on both.
			final Long spreadPct = wideSpreadPercent(facts);
			final String word = measuredWord(facts, dN, price, spreadPct);
			final boolean solid = GradeWords.SOLID.equals(word);
			return new GradedMove(solid ? Grade.SOLID : Grade.SOFT, word, move, thenMark, deltaGp, false,
				new GradedMove.Side(facts.buy.median, thenBuy, buyMove, facts.buy.count, lastBuy, lastBuyAt),
				new GradedMove.Side(facts.sell.median, thenSell, sellMove, facts.sell.count, lastSell, lastSellAt),
				facts.today, dNDay, anchor, spreadPct);
		}
		if (facts.buy.count == 0 && facts.sell.count == 0)
		{
			final boolean oneDay = window == null || window == MovementWindow.D1;
			final TradedPriceClient.Bucket thenBucket = oneDay ? facts.d2 : dN;
			final LocalDate thenDay = oneDay ? facts.d2Day : dNDay;
			final Long nowBuy = sideAverage(facts.d1, true, anchor);
			final Long nowSell = sideAverage(facts.d1, false, anchor);
			final Long dayBuy = sideAverage(thenBucket, true, anchor);
			final Long daySell = sideAverage(thenBucket, false, anchor);
			final Double dayBuyMove = ratio(nowBuy, dayBuy);
			final Double daySellMove = ratio(nowSell, daySell);
			if (dayBuyMove != null || daySellMove != null)
			{
				final double move = mean(dayBuyMove, daySellMove);
				final long thenMark = mark(dayBuy, daySell, anchor);
				final long deltaGp = deltaOf(thenMark, move);
				return new GradedMove(Grade.SOFT, fallbackWord(facts), move, thenMark,
					deltaGp, true, new GradedMove.Side(nowBuy, dayBuy, dayBuyMove, 0, lastBuy, lastBuyAt),
					new GradedMove.Side(nowSell, daySell, daySellMove, 0, lastSell, lastSellAt), facts.d1Day, thenDay,
					anchor);
			}
		}
		return new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false,
			new GradedMove.Side(facts.buy.median, null, null, facts.buy.count, lastBuy, lastBuyAt),
			new GradedMove.Side(facts.sell.median, null, null, facts.sell.count, lastSell, lastSellAt), null, null,
			anchor);
	}

	/**
	 * The word of a MEASURED figure (L3, the user's final decision of 2026-10-07), or {@link GradeWords#SOLID} (the empty
	 * string) when it has none: the FIRST of the facts that make a figure soft. The compared day's thinner side traded
	 * under {@value #WORD_MIN_DAY_COUNT} units ({@link GradeWords#volume}); the current pair, both sides reported in the
	 * last 24 hours, more than {@value #WORD_MAX_QUOTE_GAP_PCT} % of its middle apart ({@link GradeWords#spread}, with its
	 * number). A figure with neither is solid
	 * if its price is inside the anchor's range - and a price outside it, the one other way a measured figure can fail
	 * (the two sides moving violently opposite ways, which no bank has met), has no word of its own, so it says
	 * the plain {@link GradeWords#SPREAD}, the sides being far apart: a soft row always carries a word and a row with no word is
	 * solid. A figure reported on one side only is judged by the same facts - the old word "1 side" is gone and
	 * one-sidedness is no weakness.
	 */
	private static String measuredWord(final Facts facts, @Nullable final TradedPriceClient.Bucket dN, final long price,
		@Nullable final Long spreadPct)
	{
		final long count = thinnerCount(dN);
		if (count < WORD_MIN_DAY_COUNT)
		{
			return GradeWords.volume(count);
		}
		if (spreadPct != null)
		{
			return GradeWords.spread(spreadPct);
		}
		return plausible(price, facts.anchor) ? GradeWords.SOLID : GradeWords.SPREAD;
	}

	/**
	 * The word of the FALLBACK figure (yesterday's move, nothing traded in the last 24 hours): how long it has been since the
	 * item last traded ({@link GradeWords#lastTraded}), from the newer of the two current prints' times that the figure would
	 * count, against the clock; {@link GradeWords#YDAY} when no print time is known. A plausible print is older than the 24
	 * hours that were silent - and an implausible one (outside the anchor's range) is no trade the figure counts, so it is no
	 * trade the word counts.
	 */
	private static String fallbackWord(final Facts facts)
	{
		final TradedPriceClient.Quote last = facts.quote;
		long lastTrade = 0L;
		if (last != null)
		{
			lastTrade = Math.max(plausible(last.buy(), facts.anchor) ? last.buySeconds() : 0L,
				plausible(last.sell(), facts.anchor) ? last.sellSeconds() : 0L);
		}
		return lastTrade <= 0L ? GradeWords.YDAY : GradeWords.lastTraded(facts.nowSeconds - lastTrade);
	}

	/**
	 * The current pair's spread as a whole percentage of its middle when it is a CURRENT and WIDE one, else null - the fact
	 * behind {@link GradeWords#spread}. Current: BOTH sides reported in the last 24 hours, each print at most
	 * {@value #LATEST_PRINT_MAX_AGE_SECONDS} seconds old and inside the anchor's range (the test a print passes to be an
	 * observation, so a side that is silent for a day or only prints junk makes the pair no spread, and a figure reported on
	 * one side only never has one). Wide: the gap over {@value #WORD_MAX_QUOTE_GAP_PCT} % of the middle, in integers.
	 */
	@Nullable
	private static Long wideSpreadPercent(final Facts facts)
	{
		final TradedPriceClient.Quote quote = facts.quote;
		if (quote == null || !reportedInTheLast24Hours(quote.buy(), quote.buySeconds(), facts)
			|| !reportedInTheLast24Hours(quote.sell(), quote.sellSeconds(), facts))
		{
			return null;
		}
		final Long gap = quote.spread();
		final Long mid = quote.mid();
		return gap != null && mid != null && mid > 0L && gap * 100L > mid * WORD_MAX_QUOTE_GAP_PCT
			? spreadPercent(quote.buy(), quote.sell()) : null;
	}

	/** Whether a print counts as its side having reported in the last 24 hours: the age and the range of an observation. */
	private static boolean reportedInTheLast24Hours(@Nullable final Long price, final long seconds, final Facts facts)
	{
		return price != null && seconds > 0L && facts.nowSeconds - seconds <= LATEST_PRINT_MAX_AGE_SECONDS
			&& plausible(price, facts.anchor);
	}

	/**
	 * Whether two side moves agree to within {@value #SOLID_MAX_SIDE_GAP_POINTS} percentage points. False when either is
	 * missing. Not part of the solid test since 2026-10-07; the open block closes on "Both sides agree." or "The sides
	 * disagree, so the move is uncertain." by it.
	 */
	static boolean sidesAgree(@Nullable final Double buyMove, @Nullable final Double sellMove)
	{
		return buyMove != null && sellMove != null
			&& Math.abs(buyMove - sellMove) * 100.0d <= SOLID_MAX_SIDE_GAP_POINTS + SIDE_GAP_TOLERANCE_POINTS;
	}

	/**
	 * An untradeable stack's figure as the sum of its tradeable parts' (addendum R's rule, carried to 1.2.0): "then" is
	 * the parts' marks summed, "now" their window prices summed, and the grade the worst of theirs - SOLID only when
	 * every part is, the first soft part's word otherwise. NONE ("no trades") when any part has no figure: half a sum is
	 * not the item's price.
	 *
	 * @param parts  the stack's parts
	 * @param moveOf each part id's figure for this window, or null when it has none
	 */
	static GradedMove combineParts(@Nullable final List<BankItem.Part> parts,
		final Function<Integer, GradedMove> moveOf)
	{
		if (parts == null || parts.isEmpty())
		{
			return new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null, null, null, null);
		}
		long then = 0L;
		long now = 0L;
		Grade grade = Grade.SOLID;
		String word = GradeWords.SOLID;
		boolean fallback = false;
		LocalDate nowDay = null;
		LocalDate thenDay = null;
		for (final BankItem.Part part : parts)
		{
			final GradedMove move = part == null || part.quantity <= 0L ? null : moveOf.apply(part.id);
			if (move == null || !move.hasFigure())
			{
				return new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null, null, null,
					null);
			}
			then = PortfolioMath.clampedAdd(then, clampedMultiply(move.thenMark(), part.quantity));
			now = PortfolioMath.clampedAdd(now, clampedMultiply(move.price(), part.quantity));
			if (move.grade() == Grade.SOFT && grade == Grade.SOLID)
			{
				grade = Grade.SOFT;
				word = move.word();
			}
			fallback |= move.fallback();
			if (nowDay == null)
			{
				nowDay = move.nowDay();
				thenDay = move.thenDay();
			}
		}
		if (then <= 0L)
		{
			return new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null, null, null, null);
		}
		return new GradedMove(grade, word, now / (double) then - 1.0d, then, now - then, fallback, null, null, nowDay,
			thenDay, null);
	}

	// ---------------------------------------------------------------- small pure helpers

	/** {@code part} as a whole percentage of {@code whole}, rounded half up - the figure the open block's spread clause names. */
	static long percentOf(final long part, final long whole)
	{
		if (whole <= 0L)
		{
			return 0L;
		}
		return (part * 100L + whole / 2L) / whole;
	}

	/**
	 * The spread between a last buy print and a last sell print as a whole percentage of their middle - {@code |buy -
	 * sell| / mid}, rounded half up - which is the figure behind {@link GradeWords#spread}, shown in the word and in the open block
	 * ("The buy and sell prices are 30% apart."). Null when either print is missing or past the ceiling.
	 */
	@Nullable
	static Long spreadPercent(@Nullable final Long buy, @Nullable final Long sell)
	{
		if (!sane(buy) || !sane(sell))
		{
			return null;
		}
		return percentOf(Math.abs(buy - sell), halfUpMean(buy, sell));
	}

	/**
	 * Whether {@code part} is under {@code pct} percent of {@code whole} - {@code part x 100 < whole x pct} - in longs,
	 * and in doubles only when a product would overflow. The card's two shares (L3).
	 */
	static boolean underShare(final long part, final long whole, final int pct)
	{
		try
		{
			return Math.multiplyExact(part, 100L) < Math.multiplyExact(whole, (long) pct);
		}
		catch (final ArithmeticException overflow)
		{
			return part * 100.0d < whole * (double) pct;
		}
	}

	/**
	 * Whether {@code part} is over {@code pct} percent of {@code whole} - {@code part x 100 > whole x pct} - in longs,
	 * and in doubles only when a product would overflow. The card's soft-move share (L3).
	 */
	static boolean overShare(final long part, final long whole, final int pct)
	{
		try
		{
			return Math.multiplyExact(part, 100L) > Math.multiplyExact(whole, (long) pct);
		}
		catch (final ArithmeticException overflow)
		{
			return part * 100.0d > whole * (double) pct;
		}
	}

	/**
	 * One side's average of a bucket when it counts (L2): that side traded at least {@value #MIN_SIDE_VOLUME} unit and
	 * its average is plausible against the anchor. Null otherwise.
	 */
	@Nullable
	private static Long sideAverage(@Nullable final TradedPriceClient.Bucket bucket, final boolean buySide,
		@Nullable final Long anchor)
	{
		if (bucket == null)
		{
			return null;
		}
		final Long average = buySide ? bucket.avgHigh() : bucket.avgLow();
		final long volume = buySide ? bucket.highVolume() : bucket.lowVolume();
		return volume >= MIN_SIDE_VOLUME && plausible(average, anchor) ? average : null;
	}

	/** {@code now / then - 1}, or null when either is missing. */
	@Nullable
	private static Double ratio(@Nullable final Long now, @Nullable final Long then)
	{
		return now == null || then == null || then <= 0L ? null : now / (double) then - 1.0d;
	}

	/** The mean of the present moves; one of them is always present where this is called. */
	private static double mean(@Nullable final Double a, @Nullable final Double b)
	{
		if (a != null && b != null)
		{
			return (a + b) / 2.0d;
		}
		return a != null ? a : (b != null ? b : 0.0d);
	}

	/**
	 * The mark a figure moves from (L2): the two sides' "then" halved when both are present, else the one present,
	 * else the anchor - and 1 if even that is missing, which no caller reaches (a move implies a "then").
	 */
	private static long mark(@Nullable final Long buy, @Nullable final Long sell, @Nullable final Long anchor)
	{
		if (buy != null && sell != null)
		{
			return halfUpMean(buy, sell);
		}
		if (buy != null)
		{
			return buy;
		}
		if (sell != null)
		{
			return sell;
		}
		return anchor != null && anchor > 0L ? anchor : 1L;
	}

	/**
	 * One item's gp move, {@code round(thenMark x move)} - held so the price it makes stays at least 1 gp, because a
	 * price of 0 is "no price" everywhere in this plugin.
	 */
	private static long deltaOf(final long thenMark, final double move)
	{
		final long delta = Math.round(thenMark * move);
		return Math.max(delta, 1L - thenMark);
	}

	/** The thinner side's unit count on one bucket; 0 for none. */
	private static long thinnerCount(@Nullable final TradedPriceClient.Bucket bucket)
	{
		return bucket == null ? 0L : Math.min(bucket.highVolume(), bucket.lowVolume());
	}

	/** The mean of two non-negative longs rounded half up, added in halves so it cannot overflow. */
	private static long halfUpMean(final long a, final long b)
	{
		final long half = a / 2L + b / 2L;
		final long remainder = (a % 2L) + (b % 2L);
		return half + (remainder + 1L) / 2L;
	}

	/** {@code a x b} clamped at {@link Long#MAX_VALUE}; both are non-negative where this is called. */
	private static long clampedMultiply(final long a, final long b)
	{
		try
		{
			return Math.multiplyExact(Math.max(0L, a), Math.max(0L, b));
		}
		catch (final ArithmeticException overflow)
		{
			return Long.MAX_VALUE;
		}
	}

	/** A price this class reasons about: present, positive and at most {@link #PRICE_CEILING}. */
	private static boolean sane(@Nullable final Long gp)
	{
		return gp != null && gp > 0L && gp <= PRICE_CEILING;
	}
}
