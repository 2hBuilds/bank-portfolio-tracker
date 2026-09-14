package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * {@link PortfolioMath} against addendum M line M1 ({@code docs/bank-price-movement-addendum-M-2026-09-09.md}):
 * the whole bank's worth, the both-days basis of every window's move, the overflow clamps, and the exact figures
 * of a three-item fixture - pure functions, no client (playbook 7.6, style PURE).
 *
 * <p>The fixture: an Abyssal whip (1 held, 1,520,000 now, 1,507,600 a day ago, 1,500,000 a week ago), 500 sharks
 * (1,000 now, flat over a day, 1,100 a week ago) and 100 bones (100 now, no 1d baseline, 90 a week ago). So
 * {@code valueNow} is 2,030,000 over 3 of 3 stacks; the 1d move covers the whip and the sharks only
 * (2,007,600 -> 2,020,000 = +12,400, +0.617 %); the 7d move covers all three (2,059,000 -> 2,030,000 = -29,000,
 * -1.408 %); and 30d has no baseline at all.
 *
 * <p>The last section adds addendum P line P1 ({@code docs/bank-price-movement-addendum-P-2026-09-10.md}): the same
 * fixture with {@value #CASH} gp of coins in the bank, where the total gains the cash, every window gains it on both
 * sides, the gp moves are unchanged and only the percentages fall.
 */
public class PortfolioMathTest
{
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final int BONES = 526;
	private static final int BOX = 6199;
	/** Two items the Grand Exchange does not list, kept for their alch value since Q5. */
	private static final int DRAMEN = 772;
	private static final int GRACEFUL = 11_850;

	private static final LocalDate SEP_7 = LocalDate.of(2026, 9, 7);
	private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);

	/** The cash of the P1 examples: 1,000,000 gp of coins and platinum tokens, half the fixture's stacks again. */
	private static final long CASH = 1_000_000L;

	// ---------------------------------------------------------------- the fixture

	private static List<BankItem> bank()
	{
		return Arrays.asList(
			new BankItem(WHIP, 1, "Abyssal whip", false),
			new BankItem(SHARK, 500, "Shark", true),
			new BankItem(BONES, 100, "Bones", true));
	}

	private static Map<Integer, Long> now()
	{
		final Map<Integer, Long> m = new HashMap<>();
		m.put(WHIP, 1_520_000L);
		m.put(SHARK, 1_000L);
		m.put(BONES, 100L);
		return m;
	}

	private static Map<Integer, Long> oneDayAgo()
	{
		final Map<Integer, Long> m = new HashMap<>();
		m.put(WHIP, 1_507_600L);
		m.put(SHARK, 1_000L);
		return m;
	}

	private static Map<Integer, Long> sevenDaysAgo()
	{
		final Map<Integer, Long> m = new HashMap<>();
		m.put(WHIP, 1_500_000L);
		m.put(SHARK, 1_100L);
		m.put(BONES, 90L);
		return m;
	}

	private static Map<MovementWindow, Function<Integer, Long>> thens()
	{
		final Map<MovementWindow, Function<Integer, Long>> m = new EnumMap<>(MovementWindow.class);
		m.put(MovementWindow.D1, oneDayAgo()::get);
		m.put(MovementWindow.D7, sevenDaysAgo()::get);
		return m;
	}

	private static Map<MovementWindow, LocalDate> days()
	{
		final Map<MovementWindow, LocalDate> m = new EnumMap<>(MovementWindow.class);
		m.put(MovementWindow.D1, SEP_7);
		m.put(MovementWindow.D7, SEP_1);
		return m;
	}

	private static PortfolioSummary fixture()
	{
		return PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), 0L);
	}

	private static PriceMap map(final Map<Integer, Long> prices, final long revId)
	{
		final Map<Integer, PricePoint> points = new HashMap<>();
		for (final Map.Entry<Integer, Long> entry : prices.entrySet())
		{
			points.put(entry.getKey(), new PricePoint(entry.getValue(), entry.getValue()));
		}
		return new PriceMap(points, 1L, 2L, revId);
	}

	// ---------------------------------------------------------------- M1: the exact figures

	@Test
	public void theWholeBankIsValuedAtTodaysPricesOverEveryPricedStack()
	{
		final PortfolioSummary s = fixture();

		assertEquals("1,520,000 + 500 x 1,000 + 100 x 100", 2_030_000L, s.valueNow());
		assertEquals(3, s.itemsPriced());
		assertEquals(3, s.itemsTotal());
		assertEquals("1d and 7d have baselines; 30d, 90d and 180d do not", 2, s.moves().size());
		assertEquals(Arrays.asList(MovementWindow.D1, MovementWindow.D7), new ArrayList<>(s.moves().keySet()));
	}

	@Test
	public void theOneDayMoveCountsOnlyTheStacksPricedOnBothDays()
	{
		final WindowMove d1 = fixture().move(MovementWindow.D1);

		assertEquals(MovementWindow.D1, d1.window());
		assertEquals(SEP_7, d1.thenDay());
		assertEquals("the bones have no 1d baseline: the whip and the sharks", 2, d1.itemsCovered());
		assertEquals("1,507,600 + 500,000", 2_007_600L, d1.valueThen());
		assertEquals("the SAME two stacks now: 1,520,000 + 500,000 - not the whole bank's 2,030,000", 2_020_000L, d1.valueNowCovered());
		assertEquals(12_400L, d1.deltaGp());
		assertEquals(12_400L * 100.0d / 2_007_600L, d1.deltaPct(), 1e-12);
		assertEquals("+0.6%", MovementMath.formatPct(d1.deltaPct(), d1.deltaGp()));
	}

	@Test
	public void theSevenDayMoveCoversAllThreeAndFalls()
	{
		final WindowMove d7 = fixture().move(MovementWindow.D7);

		assertEquals(SEP_1, d7.thenDay());
		assertEquals(3, d7.itemsCovered());
		assertEquals("1,500,000 + 500 x 1,100 + 100 x 90", 2_059_000L, d7.valueThen());
		assertEquals(2_030_000L, d7.valueNowCovered());
		assertEquals(-29_000L, d7.deltaGp());
		assertEquals(-29_000L * 100.0d / 2_059_000L, d7.deltaPct(), 1e-12);
		assertEquals("-1.4%", MovementMath.formatPct(d7.deltaPct(), d7.deltaGp()));
	}

	@Test
	public void aWindowWithoutABaselineHasNoMove()
	{
		final PortfolioSummary s = fixture();

		assertNull(s.move(MovementWindow.D30));
		assertNull(s.move(MovementWindow.D90));
		assertNull(s.move(MovementWindow.D180));
		assertNull(s.move(null));
		assertFalse(s.moves().containsKey(MovementWindow.D30));
	}

	// ---------------------------------------------------------------- M1: the basis rules

	@Test
	public void unpricedStacksAreLeftOutOfEverySum()
	{
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(new BankItem(BOX, 3, "Mystery box", true)); // RuneLite has no price: null
		final Map<Integer, Long> now = now();
		now.put(SHARK, 0L); // 0 is "no price" (L1), not a price of zero
		final Map<Integer, Long> then = sevenDaysAgo();
		then.put(BOX, 1_000_000L); // a "then" with no "now" is not a covered stack either
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D7, then::get);

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now::get, thens, days(), 0L);

		assertEquals("the whip and the bones only", 1_520_000L + 10_000L, s.valueNow());
		assertEquals(2, s.itemsPriced());
		assertEquals("every stack counts in the total, priced or not", 4, s.itemsTotal());
		final WindowMove d7 = s.move(MovementWindow.D7);
		assertEquals("the sharks have no now, the box has no now: two covered", 2, d7.itemsCovered());
		assertEquals(1_500_000L + 9_000L, d7.valueThen());
		assertEquals(1_520_000L + 10_000L, d7.valueNowCovered());
		assertEquals(21_000L, d7.deltaGp());
	}

	@Test
	public void aStackWhoseBaselineIsMissingOrZeroIsNotCoveredButStillCountsNow()
	{
		final Map<Integer, Long> then = oneDayAgo();
		then.put(SHARK, 0L); // a then of 0 would make the percentage infinite: not priced (MovementMath.row's rule)
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, then::get);

		final PortfolioSummary s = PortfolioMath.summariseResolved(bank(), now()::get, thens, days(), 0L);

		assertEquals(2_030_000L, s.valueNow());
		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("only the whip has a positive then", 1, d1.itemsCovered());
		assertEquals(1_507_600L, d1.valueThen());
		assertEquals(1_520_000L, d1.valueNowCovered());
		assertEquals(12_400L, d1.deltaGp());
	}

	@Test
	public void aWindowWhoseCoveredStacksWereWorthNothingHasAGpMoveAndNoPercent()
	{
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, id -> null); // a baseline that names none of the bank

		final WindowMove d1 = PortfolioMath.summariseResolved(bank(), now()::get, thens, days(), 0L).move(MovementWindow.D1);

		assertEquals(0, d1.itemsCovered());
		assertEquals(0L, d1.valueThen());
		assertEquals(0L, d1.valueNowCovered());
		assertEquals(0L, d1.deltaGp());
		assertNull("deltaPct is null when valueThen is 0 (M1) - never a division by zero", d1.deltaPct());
		assertEquals(MovementMath.DASH, MovementMath.formatPct(d1.deltaPct(), d1.deltaGp()));
	}

	@Test
	public void aFlatWindowIsAZeroMoveWithAZeroPercent()
	{
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, now()::get);

		final WindowMove d1 = PortfolioMath.summariseResolved(bank(), now()::get, thens, days(), 0L).move(MovementWindow.D1);

		assertEquals(3, d1.itemsCovered());
		assertEquals(2_030_000L, d1.valueThen());
		assertEquals(0L, d1.deltaGp());
		assertEquals(0.0d, d1.deltaPct(), 0.0d);
		assertEquals("0.0%", MovementMath.formatPct(d1.deltaPct(), d1.deltaGp()));
	}

	// ---------------------------------------------------------------- M1: the clamps

	@Test
	public void sumsClampAtLongMaxInsteadOfWrapping()
	{
		final List<BankItem> items = Arrays.asList(
			new BankItem(1, 3, "A fortune", true),
			new BankItem(2, Integer.MAX_VALUE, "Another", true),
			new BankItem(3, 1, "Small change", false));
		final Map<Integer, Long> now = new HashMap<>();
		now.put(1, Long.MAX_VALUE / 2L + 1L); // x3 overflows the multiply: clamped
		now.put(2, 5_000_000_000L);           // x (2^31 - 1) is 1.07e19, past Long.MAX_VALUE: clamped too
		now.put(3, 1L);
		final Map<Integer, Long> then = new HashMap<>();
		then.put(1, 1L);
		then.put(2, 1L);
		then.put(3, 1L);
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, then::get);

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now::get, thens, days(), 0L);

		assertEquals("a clamped holding plus anything is still the clamp, never negative", Long.MAX_VALUE, s.valueNow());
		assertEquals(3, s.itemsPriced());
		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals(3, d1.itemsCovered());
		assertEquals(3L + Integer.MAX_VALUE + 1L, d1.valueThen());
		assertEquals(Long.MAX_VALUE, d1.valueNowCovered());
		assertEquals("the difference of two clamped sums cannot overflow", Long.MAX_VALUE - d1.valueThen(), d1.deltaGp());
		assertTrue(d1.deltaPct() > 0.0d);
	}

	@Test
	public void clampedAddSaturatesAtTheTop()
	{
		assertEquals(3L, PortfolioMath.clampedAdd(1L, 2L));
		assertEquals(Long.MAX_VALUE, PortfolioMath.clampedAdd(Long.MAX_VALUE, 1L));
		assertEquals(Long.MAX_VALUE, PortfolioMath.clampedAdd(Long.MAX_VALUE - 1L, 2L));
		assertEquals(Long.MAX_VALUE, PortfolioMath.clampedAdd(Long.MAX_VALUE, Long.MAX_VALUE));
		assertEquals(Long.MAX_VALUE, PortfolioMath.clampedAdd(Long.MAX_VALUE, 0L));
	}

	// ---------------------------------------------------------------- M2: the two signatures agree

	@Test
	public void theContractSignatureOverProjectedMapsGivesTheSameSummary()
	{
		final Map<MovementWindow, PriceMap> baselines = new EnumMap<>(MovementWindow.class);
		baselines.put(MovementWindow.D1, map(oneDayAgo(), 15_333_448L));
		baselines.put(MovementWindow.D7, map(sevenDaysAgo(), 15_328_200L));

		final PortfolioSummary viaMaps = PortfolioMath.summarise(bank(), now()::get, baselines, days(), 0L);

		assertEquals(fixture(), viaMaps);
		assertEquals(fixture().hashCode(), viaMaps.hashCode());
		assertEquals(2_020_000L, viaMaps.move(MovementWindow.D1).valueNowCovered());
	}

	@Test
	public void aResolverOverAMapReadsThePointsMid()
	{
		final Function<Integer, Long> resolver = PortfolioMath.resolver(map(oneDayAgo(), 1L));

		assertEquals(Long.valueOf(1_507_600L), resolver.apply(WHIP));
		assertNull(resolver.apply(BONES));
		assertNull(resolver.apply(null));
		assertNull(PortfolioMath.resolver(null).apply(WHIP));
		assertNull(PortfolioMath.resolver(PriceMap.EMPTY).apply(WHIP));
	}

	// ---------------------------------------------------------------- M2: the model

	@Test
	public void emptyAndNullInputsAreTheEmptySummary()
	{
		assertEquals(PortfolioSummary.EMPTY, PortfolioMath.summariseResolved(null, null, null, null, 0L));
		assertEquals(PortfolioSummary.EMPTY, PortfolioMath.summarise(null, null, null, null, 0L));
		assertEquals("null entries are skipped, not counted", PortfolioSummary.EMPTY,
			PortfolioMath.summariseResolved(Collections.singletonList(null), now()::get, Collections.emptyMap(), null, 0L));
		// An empty bank against real baselines: nothing is worth anything, and a window with a baseline is still
		// a window (zero covered, no percent) - it is the SERVICE that publishes EMPTY for an empty snapshot (M3).
		final PortfolioSummary noStacks = PortfolioMath.summariseResolved(Collections.emptyList(), now()::get, thens(), days(), 0L);
		assertEquals(0L, noStacks.valueNow());
		assertEquals(0, noStacks.itemsTotal());
		assertEquals(2, noStacks.moves().size());
		assertEquals(0, noStacks.move(MovementWindow.D1).itemsCovered());
		assertNull(noStacks.move(MovementWindow.D1).deltaPct());
		assertEquals(0L, PortfolioSummary.EMPTY.valueNow());
		assertEquals(0, PortfolioSummary.EMPTY.itemsPriced());
		assertEquals(0, PortfolioSummary.EMPTY.itemsTotal());
		assertTrue(PortfolioSummary.EMPTY.moves().isEmpty());
		assertNull(PortfolioSummary.EMPTY.move(MovementWindow.D1));
	}

	@Test
	public void aBankWithBaselinesButNoWindowDayStillMoves()
	{
		final PortfolioSummary s = PortfolioMath.summariseResolved(bank(), now()::get, thens(), null, 0L);

		assertEquals(2, s.moves().size());
		assertNull("no day known: the label prints '-' rather than a guess", s.move(MovementWindow.D1).thenDay());
		assertEquals(12_400L, s.move(MovementWindow.D1).deltaGp());
	}

	@Test
	public void theSummaryIsImmutableAndValueBased()
	{
		final PortfolioSummary s = fixture();
		try
		{
			s.moves().put(MovementWindow.D30, s.move(MovementWindow.D1));
			fail("moves() must be unmodifiable");
		}
		catch (final UnsupportedOperationException expected)
		{
			// the M2 contract: unmodifiable
		}
		assertEquals(fixture(), s);
		assertSame(s, s);
		assertNotEquals(s, PortfolioSummary.EMPTY);
		assertNotEquals(s.move(MovementWindow.D1), s.move(MovementWindow.D7));
		assertEquals(s.move(MovementWindow.D1), fixture().move(MovementWindow.D1));
		assertEquals(s.move(MovementWindow.D1).hashCode(), fixture().move(MovementWindow.D1).hashCode());
		assertTrue(s.toString(), s.toString().contains("valueNow=2030000"));
		assertTrue(s.move(MovementWindow.D7).toString(), s.move(MovementWindow.D7).toString().contains("deltaGp=-29000"));

		final Map<MovementWindow, WindowMove> withNulls = new EnumMap<>(MovementWindow.class);
		withNulls.put(MovementWindow.D1, null);
		assertTrue("null moves are dropped at construction", new PortfolioSummary(1L, 1, 1, withNulls).moves().isEmpty());
		assertEquals("a null window reads as the default", MovementWindow.DEFAULT,
			new WindowMove(null, null, 0L, 0L, 0L, null, 0).window());
	}

	// ---------------------------------------------------------------- P1: the bank's coins and platinum tokens

	/**
	 * The worked example (P1). The same fixture plus 1,000,000 gp of cash: the total gains it once, and so does each
	 * side of each window - so the gp moves are the SAME +12,400 and -29,000 while the percentages fall, because the
	 * money that cannot move is now part of what they are measured against (+0.617 % -> +0.412 %, -1.408 % -> -0.948 %).
	 */
	@Test
	public void theCashIsAddedToTheBankValueAndToBothSidesOfEveryWindow()
	{
		final PortfolioSummary s = PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), CASH);

		assertEquals(CASH, s.currencyGp());
		assertEquals("2,030,000 of stacks + 1,000,000 of cash", 3_030_000L, s.valueNow());
		assertEquals("the stacks alone are what the tooltip counts stacks over", 2_030_000L, s.valueStacks());
		assertEquals("cash is not a stack", 3, s.itemsPriced());
		assertEquals(3, s.itemsTotal());

		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("the covered stacks' 2,007,600 + the cash", 3_007_600L, d1.valueThen());
		assertEquals("the same two stacks' 2,020,000 + the same cash", 3_020_000L, d1.valueNowCovered());
		assertEquals("the cash cancels: the gp move is untouched by P1", 12_400L, d1.deltaGp());
		assertEquals("2 covered stacks, cash or not", 2, d1.itemsCovered());
		assertEquals(12_400L * 100.0d / 3_007_600L, d1.deltaPct(), 1e-12);
		assertEquals("+0.4%", MovementMath.formatPct(d1.deltaPct(), d1.deltaGp()));
		assertEquals("without the cash it was +0.6%", "+0.6%",
			MovementMath.formatPct(fixture().move(MovementWindow.D1).deltaPct(), 12_400L));

		final WindowMove d7 = s.move(MovementWindow.D7);
		assertEquals(3_059_000L, d7.valueThen());
		assertEquals(3_030_000L, d7.valueNowCovered());
		assertEquals(-29_000L, d7.deltaGp());
		assertEquals(-29_000L * 100.0d / 3_059_000L, d7.deltaPct(), 1e-12);
		assertEquals("-0.9%", MovementMath.formatPct(d7.deltaPct(), d7.deltaGp()));
	}

	/** A cash of 0 must answer to the gp what the pre-P1 arities always did. */
	@Test
	public void aBankWithNoCashAnswersExactlyWhatItAnsweredBeforeP1()
	{
		final PortfolioSummary withZero = PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), 0L);

		assertEquals(fixture(), withZero);
		assertEquals(fixture().hashCode(), withZero.hashCode());
		assertEquals(fixture().toString(), withZero.toString());
		assertEquals(0L, withZero.currencyGp());
		assertEquals("valueNow() is the stacks alone when there is no cash", withZero.valueStacks(), withZero.valueNow());
		assertEquals(fixture().move(MovementWindow.D1), withZero.move(MovementWindow.D1));
		assertEquals(fixture().move(MovementWindow.D7), withZero.move(MovementWindow.D7));
		assertEquals(0L, PortfolioSummary.EMPTY.currencyGp());
	}

	/** P1: a bank of nothing but coins is worth its coins - it is not {@link PortfolioSummary#EMPTY} - and nothing moved. */
	@Test
	public void aBankOfNothingButCoinsIsWorthItsCoinsAndHasNoMove()
	{
		final PortfolioSummary s = PortfolioMath.summariseResolved(
			Collections.<BankItem>emptyList(), now()::get, thens(), days(), CASH);

		assertNotEquals(PortfolioSummary.EMPTY, s);
		assertEquals(CASH, s.valueNow());
		assertEquals(CASH, s.currencyGp());
		assertEquals(0L, s.valueStacks());
		assertEquals(0, s.itemsPriced());
		assertEquals(0, s.itemsTotal());
		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals(0, d1.itemsCovered());
		assertEquals(0L, d1.deltaGp());
		assertNull("no stack to compare, so no move - the cash is not a move", d1.deltaPct());
		assertEquals(MovementMath.DASH, MovementMath.formatPct(d1.deltaPct(), d1.deltaGp()));
	}

	/**
	 * The rule that keeps the dash: cash rides on a window only when that window covers a stack. Otherwise
	 * {@code valueThen} would be the cash, the percentage would be 0.0 % of it, and the card would report a move that
	 * was never measured.
	 */
	@Test
	public void aWindowThatCoversNoStackKeepsItsDashWhenTheBankHoldsCash()
	{
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, id -> null); // a baseline that names none of the bank

		final WindowMove d1 = PortfolioMath.summariseResolved(bank(), now()::get, thens, days(), CASH)
			.move(MovementWindow.D1);

		assertEquals(0, d1.itemsCovered());
		assertEquals("not the cash", 0L, d1.valueThen());
		assertEquals("not the cash here either", 0L, d1.valueNowCovered());
		assertEquals(0L, d1.deltaGp());
		assertNull(d1.deltaPct());
	}

	/** A fortune plus cash still clamps rather than wrapping - and the gp move, taken before the cash, survives it. */
	@Test
	public void theCashClampsWithEverythingElseAtLongMax()
	{
		final List<BankItem> items = Collections.singletonList(new BankItem(1, 1, "A fortune", true));
		final Map<Integer, Long> now = new HashMap<>();
		now.put(1, Long.MAX_VALUE);
		final Map<Integer, Long> then = new HashMap<>();
		then.put(1, Long.MAX_VALUE - 10L);
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, then::get);

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now::get, thens, days(), CASH);

		assertEquals("Long.MAX_VALUE + 1,000,000 is Long.MAX_VALUE, never a negative fortune", Long.MAX_VALUE, s.valueNow());
		assertEquals(CASH, s.currencyGp());
		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals(Long.MAX_VALUE, d1.valueThen());
		assertEquals(Long.MAX_VALUE, d1.valueNowCovered());
		assertEquals("both sides clamped to the same number, and the move is still the real one", 10L, d1.deltaGp());
		assertTrue(d1.deltaPct() > 0.0d && Double.isFinite(d1.deltaPct()));
	}

	/** The contract's over-maps shape carries the cash too, and agrees with the resolver shape to the gp. */
	@Test
	public void theContractSignatureOverProjectedMapsCarriesTheCash()
	{
		final Map<MovementWindow, PriceMap> baselines = new EnumMap<>(MovementWindow.class);
		baselines.put(MovementWindow.D1, map(oneDayAgo(), 15_333_448L));
		baselines.put(MovementWindow.D7, map(sevenDaysAgo(), 15_328_200L));

		final PortfolioSummary viaMaps = PortfolioMath.summarise(bank(), now()::get, baselines, days(), CASH);

		assertEquals(PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), CASH), viaMaps);
		assertEquals(3_030_000L, viaMaps.valueNow());
		assertEquals(CASH, viaMaps.currencyGp());
		assertEquals("and the same shape at a cash of 0 is still cashless", 2_030_000L,
			PortfolioMath.summarise(bank(), now()::get, baselines, days(), 0L).valueNow());
	}

	/** The summary holds the cash apart from the stacks, and no bank owes money. */
	@Test
	public void theSummaryKeepsTheCashApartAndRefusesANegativeOne()
	{
		final PortfolioSummary s = new PortfolioSummary(10L, 1, 1, null, CASH);

		assertEquals(10L, s.valueStacks());
		assertEquals(CASH, s.currencyGp());
		assertEquals(CASH + 10L, s.valueNow());
		assertTrue(s.toString(), s.toString().contains("currencyGp=" + CASH));
		assertEquals("a negative fortune is not a thing", 0L, new PortfolioSummary(10L, 1, 1, null, -5L).currencyGp());
		assertEquals(10L, new PortfolioSummary(10L, 1, 1, null, -5L).valueNow());
		assertEquals("the old arity means no cash", 0L, new PortfolioSummary(10L, 1, 1, null).currencyGp());
		assertNotEquals("the same total, different banks", new PortfolioSummary(10L, 1, 1, null, 0L),
			new PortfolioSummary(0L, 1, 1, null, 10L));
	}

	// ---------------------------------------------------------------- Q5: untradeable stacks at their alch value

	/** The fixture plus two Dramen staves at 1,500 gp to alch - what the "Include untradeable items" switch admits. */
	private static List<BankItem> bankWithUntradeables()
	{
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(new BankItem(DRAMEN, 2, "Dramen staff", false, true, 1_500));
		items.add(new BankItem(GRACEFUL, 1, "Graceful cape", false, true, 500));
		return items;
	}

	/**
	 * Q5, in one test: an untradeable stack is worth its alch value in the TOTAL and counts in {@code itemsTotal},
	 * it is not one of the {@code itemsPriced} - that word means "has a guide price", which is what the card's
	 * "over n of m stacks" is about - and it is in no window on either side, because an alch value is a constant
	 * of the item and adding the same number to both sides would only dilute the percentage.
	 */
	@Test
	public void anUntradeableStackCountsInTheTotalButInNoWindowAndIsNotPriced()
	{
		final PortfolioSummary s = PortfolioMath.summariseResolved(
			bankWithUntradeables(), now()::get, thens(), days(), 0L);

		assertEquals("2,030,000 of stacks + 2 x 1,500 + 500", 2_033_500L, s.valueNow());
		assertEquals(2_033_500L, s.valueStacks());
		assertEquals("the three guide-priced stacks, and no more", 3, s.itemsPriced());
		assertEquals("but all five are stacks", 5, s.itemsTotal());

		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("the same two covered stacks as without them", 2, d1.itemsCovered());
		assertEquals(2_007_600L, d1.valueThen());
		assertEquals(2_020_000L, d1.valueNowCovered());
		assertEquals(12_400L, d1.deltaGp());
		assertEquals("and the percentage is measured against the same basis as before",
			fixture().move(MovementWindow.D1).deltaPct(), d1.deltaPct(), 1e-12);
	}

	/**
	 * The pin for the switch being OFF: the service hands this function the tradeable stacks alone then, and what
	 * comes back has to be the pre-Q answer to the gp - the same summary object, field for field.
	 */
	@Test
	public void aBankWithoutUntradeableStacksAnswersExactlyWhatItAnsweredBeforeQ()
	{
		assertEquals(fixture(), PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), 0L));
		assertEquals("2,030,000 over 3 of 3, as it always was", 2_030_000L, fixture().valueNow());
		assertEquals(3, fixture().itemsTotal());
	}

	/** A marked stack with nothing to alch is worth nothing, and is still a stack the bank holds. */
	@Test
	public void anUntradeableStackWithNoAlchValueIsWorthNothingAndStillCounted()
	{
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(new BankItem(DRAMEN, 5, "Worthless", false, true, 0));

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now()::get, thens(), days(), 0L);

		assertEquals(2_030_000L, s.valueNow());
		assertEquals(3, s.itemsPriced());
		assertEquals(4, s.itemsTotal());
	}

	/**
	 * A guide price offered for a marked stack is ignored: the mark decides how a stack is valued, so a name
	 * collision in a table can never quietly price an item the exchange does not list.
	 */
	@Test
	public void anUntradeableStackIsValuedByItsAlchPriceEvenIfAPriceIsOffered()
	{
		final List<BankItem> items = Collections.singletonList(new BankItem(DRAMEN, 2, "Dramen staff", false, true, 1_500));
		final Map<Integer, Long> now = new HashMap<>();
		now.put(DRAMEN, 999_999L);
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, now::get);

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now::get, thens, days(), 0L);

		assertEquals(3_000L, s.valueNow());
		assertEquals(0, s.itemsPriced());
		assertEquals(1, s.itemsTotal());
		assertEquals(0, s.move(MovementWindow.D1).itemsCovered());
	}

	// ---------------------------------------------------------------- R3: untradeable stacks valued at their parts

	/** The addendum's own example: a Crystal body, worth three Crystal armour seeds (16,694,766 gp) and not 900k. */
	private static final int CRYSTAL_BODY = 23_975;
	private static final int ARMOUR_SEED = 23_956;
	private static final long BODY_NOW = 16_694_766L;
	private static final long BODY_THEN = 16_500_000L;

	private static BankItem crystalBody(final int quantity)
	{
		return new BankItem(CRYSTAL_BODY, quantity, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(ARMOUR_SEED, 3L, "Crystal armour seed")));
	}

	/**
	 * The caller has already summed the parts and answers that sum under the STACK's id, on both sides (R2) - which
	 * is what these resolvers do. PortfolioMath's own rule is only "a stack with parts is asked, and counts".
	 */
	private static PortfolioSummary withCrystalBody(final int quantity, @Nullable final Long now,
		@Nullable final Long then)
	{
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(crystalBody(quantity));
		final Map<Integer, Long> nowById = new HashMap<>(now());
		if (now != null)
		{
			nowById.put(CRYSTAL_BODY, now);
		}
		final Map<Integer, Long> thenById = new HashMap<>(oneDayAgo());
		if (then != null)
		{
			thenById.put(CRYSTAL_BODY, then);
		}
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, thenById::get);
		thens.put(MovementWindow.D7, sevenDaysAgo()::get);
		return PortfolioMath.summariseResolved(items, nowById::get, thens, days(), 0L);
	}

	/**
	 * R3, in one test: a stack valued at its tradeable parts counts in the total, in {@code itemsTotal} AND in
	 * {@code itemsPriced} - every gp of that sum is a guide price - and it is in the window's covered set on both
	 * sides, so the crystal set moves the bank value with the seed price.
	 */
	@Test
	public void aStackValuedAtItsPartsIsCountedAndCoveredLikeAnyGuideRow()
	{
		final PortfolioSummary s = withCrystalBody(2, BODY_NOW, BODY_THEN);

		assertEquals("2,030,000 of stacks + 2 x 16,694,766, never 2 x 900,000",
			2_030_000L + 2L * BODY_NOW, s.valueNow());
		assertEquals("its price is a market price", 4, s.itemsPriced());
		assertEquals(4, s.itemsTotal());

		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("the whip, the sharks and the crystal body", 3, d1.itemsCovered());
		assertEquals(2_007_600L + 2L * BODY_THEN, d1.valueThen());
		assertEquals(2_020_000L + 2L * BODY_NOW, d1.valueNowCovered());
		assertEquals(12_400L + 2L * (BODY_NOW - BODY_THEN), d1.deltaGp());
	}

	/**
	 * The other half of R2's fallback, seen from here: when the caller could not price every part it answers null
	 * for the stack, and null IS the alch rule - the stack drops back to its High Alchemy value, out of
	 * {@code itemsPriced} and out of every window, exactly as a stack with no mapping does.
	 */
	@Test
	public void aStackWhosePartsCouldNotBePricedFallsBackToItsAlchValue()
	{
		final PortfolioSummary s = withCrystalBody(2, null, null);

		assertEquals("2,030,000 + 2 x 900,000", 2_030_000L + 1_800_000L, s.valueNow());
		assertEquals("an alch value is not a market price", 3, s.itemsPriced());
		assertEquals(4, s.itemsTotal());
		assertEquals("and it is in no window", 2, s.move(MovementWindow.D1).itemsCovered());
	}

	/** Covered means priced on BOTH days here too: a parts stack with no baseline counts in the total alone. */
	@Test
	public void aStackValuedAtItsPartsWithNoBaselineIsInTheTotalButInNoWindow()
	{
		final PortfolioSummary s = withCrystalBody(1, BODY_NOW, null);

		assertEquals(2_030_000L + BODY_NOW, s.valueNow());
		assertEquals(4, s.itemsPriced());
		assertEquals("the whip and the sharks, as without it", 2, s.move(MovementWindow.D1).itemsCovered());
		assertEquals(12_400L, s.move(MovementWindow.D1).deltaGp());
	}

	/**
	 * The pre-R pin, kept by construction: a stack with NO parts is never asked for a price, so a name collision in
	 * some table can still never quietly price an item the exchange does not list (the Q5 rule above), and a bank
	 * of pre-R stacks answers exactly what it answered before.
	 */
	@Test
	public void aStackWithoutPartsIsNeverAskedForAPrice()
	{
		final List<Integer> asked = new ArrayList<>();
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(new BankItem(DRAMEN, 2, "Dramen staff", false, true, 1_500));
		final Function<Integer, Long> recording = id ->
		{
			asked.add(id);
			return now().get(id);
		};

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, recording, thens(), days(), 0L);

		assertFalse("nothing can answer for a stack with no parts", asked.contains(DRAMEN));
		assertEquals(2_030_000L + 3_000L, s.valueNow());
		assertEquals(3, s.itemsPriced());
	}

	/** Alch values clamp with everything else: a bank cannot be worth a negative fortune. */
	@Test
	public void anAbsurdAlchHoldingClampsRatherThanWrapping()
	{
		final List<BankItem> items = Arrays.asList(
			new BankItem(DRAMEN, Integer.MAX_VALUE, "Dramen staff", false, true, Integer.MAX_VALUE),
			new BankItem(GRACEFUL, Integer.MAX_VALUE, "Graceful cape", false, true, Integer.MAX_VALUE),
			new BankItem(GRACEFUL + 2, Integer.MAX_VALUE, "Graceful top", false, true, Integer.MAX_VALUE));

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now()::get, thens(), days(), 0L);

		assertEquals("three stacks of 4.6 x 10^18 each: clamped, never a negative fortune", Long.MAX_VALUE, s.valueNow());
		assertTrue(s.valueStacks() > 0L);
	}

	// ---------------------------------------------------------------- T5: a "now" per window, and the live count

	/** The per-window "now" of a live whip: the guide price for the window whose traded bucket was too thin. */
	private static Map<MovementWindow, Function<Integer, Long>> guideNowFor1d()
	{
		final Map<MovementWindow, Function<Integer, Long>> m = new EnumMap<>(MovementWindow.class);
		m.put(MovementWindow.D1, Collections.singletonMap(WHIP, 1_500_000L)::get);
		return m;
	}

	/**
	 * T5: the headline counts each stack at the price its own row prints, while a window whose traded bucket was
	 * missing or thin compares the GUIDE's two ends - "never live-now against guide-then". So the whip can be worth
	 * 1,520,000 in the total and be compared at 1,500,000 over 1d, and the two are not a contradiction.
	 */
	@Test
	public void aWindowCanCompareADifferentNowFromTheOneTheTotalCounts()
	{
		final PortfolioSummary s = PortfolioMath.summariseResolved(bank(), now()::get, thens(), guideNowFor1d(),
			days(), 0L, 1);

		assertEquals("the total is unchanged: it counts what the rows print", 2_030_000L, s.valueNow());
		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("the whip enters 1d at its GUIDE price", 1_500_000L + 500_000L, d1.valueNowCovered());
		assertEquals("its baseline is untouched", 2_007_600L, d1.valueThen());
		assertEquals(-7_600L, d1.deltaGp());
		assertEquals("and 7d, which has no per-window now, still reads the headline's", 2_030_000L,
			s.move(MovementWindow.D7).valueNowCovered());
	}

	/** A per-window resolver that cannot answer for an id falls back to the headline's "now", never to nothing. */
	@Test
	public void aWindowNowThatCannotAnswerFallsBackToTheSharedOne()
	{
		final Map<MovementWindow, Function<Integer, Long>> empty = new EnumMap<>(MovementWindow.class);
		empty.put(MovementWindow.D1, id -> null);

		final PortfolioSummary s = PortfolioMath.summariseResolved(bank(), now()::get, thens(), empty, days(), 0L, 0);

		assertEquals("identical to the fixture, id for id", fixture().move(MovementWindow.D1), s.move(MovementWindow.D1));
		assertEquals(2, s.move(MovementWindow.D1).itemsCovered());
	}

	/**
	 * The BASIS is untouched by any of this: a window still covers the stacks the shared "now" prices and its own
	 * "then" names, so an untradeable stack on the alch rule stays out of every window even when a per-window
	 * resolver would happily answer for it.
	 */
	@Test
	public void aPerWindowNowCannotDragAnUncoveredStackIntoAWindow()
	{
		final List<BankItem> items = new ArrayList<>(bank());
		items.add(new BankItem(DRAMEN, 2, "Dramen staff", false, true, 1_500));
		final Map<MovementWindow, Function<Integer, Long>> pushy = new EnumMap<>(MovementWindow.class);
		pushy.put(MovementWindow.D1, Collections.singletonMap(DRAMEN, 9_000_000L)::get);
		final Map<MovementWindow, Function<Integer, Long>> thens = new EnumMap<>(MovementWindow.class);
		thens.put(MovementWindow.D1, id -> id == DRAMEN ? Long.valueOf(8_000_000L) : oneDayAgo().get(id));

		final PortfolioSummary s = PortfolioMath.summariseResolved(items, now()::get, thens, pushy, days(), 0L, 0);

		final WindowMove d1 = s.move(MovementWindow.D1);
		assertEquals("the alch stack is in the total at its alch value", 2_030_000L + 3_000L, s.valueNow());
		assertEquals("but in no window: the shared now never priced it", 2, d1.itemsCovered());
		assertEquals(2_020_000L, d1.valueNowCovered());
	}

	/** T5: how many stacks the caller priced live, carried through to the card's "N of M stacks live". */
	@Test
	public void theLiveRowCountIsCarriedOnTheSummary()
	{
		assertEquals(7, PortfolioMath.summariseResolved(bank(), now()::get, thens(), null, days(), 0L, 7).liveRows());
		assertEquals("a negative count is not a count", 0,
			new PortfolioSummary(1L, 1, 1, null, 0L, -3).liveRows());
		assertEquals("and it is part of the value: two summaries that priced differently are different",
			false, new PortfolioSummary(1L, 1, 1, null, 0L, 1).equals(new PortfolioSummary(1L, 1, 1, null, 0L, 2)));
	}

	/**
	 * The switch OFF, pinned where it matters most: the pre-T arity and the new one with no per-window resolvers
	 * and no live rows are the SAME summary, to the gp and to the field - which is what lets the whole engine
	 * carry one code path for both.
	 */
	@Test
	public void theNewArityWithNothingLiveIsTheOldOneExactly()
	{
		final PortfolioSummary before = PortfolioMath.summariseResolved(bank(), now()::get, thens(), days(), CASH);
		final PortfolioSummary after = PortfolioMath.summariseResolved(bank(), now()::get, thens(), null, days(),
			CASH, 0);

		assertEquals(before, after);
		assertEquals(before.hashCode(), after.hashCode());
		assertEquals(0, before.liveRows());
		assertEquals("an empty map is the same as none",
			before, PortfolioMath.summariseResolved(bank(), now()::get, thens(),
				Collections.<MovementWindow, Function<Integer, Long>>emptyMap(), days(), CASH, 0));
	}
}
