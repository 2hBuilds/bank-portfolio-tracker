package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The % and gp sorts are ONE list (the user, 2026-10-07, on the first live look at 1.2.0: "sort as 1 list and not a
 * separate second list for problem ones"): a SOFT figure sorts by its figure beside the SOLID ones in both directions,
 * only a NONE row - no figure - sits with the dashes at the end, and a row nothing graded sorts by its own figure too.
 * The contract's three tiers (L5) were built at 87347d9 and removed the same day. Synthetic rows only.
 */
public class GradedSortTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
	private static final long NOW = TODAY.atTime(14, 0).toEpochSecond(ZoneOffset.UTC);

	/** Solid, +1 %, +10 gp, unit 1,010. */
	private static final MovementRow SOLID_UP = row(1, "Solid up", 1_010L, 10L, 1.0d, solid());
	/** Solid, -2 %, -20 gp, unit 980. */
	private static final MovementRow SOLID_DOWN = row(2, "Solid down", 980L, -20L, -2.0d, solid());
	/** Soft, +50 %, +500 gp, unit 1,500 - the biggest figure on the list. */
	private static final MovementRow SOFT_UP = row(3, "Soft up", 1_500L, 500L, 50.0d, soft());
	/** Soft, -30 %, -300 gp, unit 700 - the smallest. */
	private static final MovementRow SOFT_DOWN = row(4, "Soft down", 700L, -300L, -30.0d, soft());
	/** No trades: no figure, the dash. */
	private static final MovementRow NONE = row(5, "No trades", 2_000L, null, null, none());
	/** Nothing graded it (live prices off): +5 %, +50 gp - sorted by that figure like every other row. */
	private static final MovementRow UNGRADED = row(6, "Guide only", 1_050L, 50L, 5.0d, null);

	/** One list: the soft +50 % heads the descending list and the soft -30 % the ascending one; NONE is last in both. */
	@Test
	public void thePercentSortIsOneListWithTheDashesLast()
	{
		assertEquals(Arrays.asList(SOFT_UP, UNGRADED, SOLID_UP, SOLID_DOWN, SOFT_DOWN, NONE),
			sorted(SortMode.PERCENT_MOVE, true));
		assertEquals(Arrays.asList(SOFT_DOWN, SOLID_DOWN, SOLID_UP, UNGRADED, SOFT_UP, NONE),
			sorted(SortMode.PERCENT_MOVE, false));
	}

	@Test
	public void theGpSortIsTheSameOneList()
	{
		assertEquals(Arrays.asList(SOFT_UP, UNGRADED, SOLID_UP, SOLID_DOWN, SOFT_DOWN, NONE),
			sorted(SortMode.GP_MOVE, true));
		assertEquals(Arrays.asList(SOFT_DOWN, SOLID_DOWN, SOLID_UP, UNGRADED, SOFT_UP, NONE),
			sorted(SortMode.GP_MOVE, false));
	}

	/** A price is a price however its move was graded: the two price columns never looked at the grade. */
	@Test
	public void thePriceColumnsIgnoreTheGrade()
	{
		assertEquals(Arrays.asList(NONE, SOFT_UP, UNGRADED, SOLID_UP, SOLID_DOWN, SOFT_DOWN),
			sorted(SortMode.UNIT_PRICE, true));
		assertEquals(Arrays.asList(SOFT_DOWN, SOLID_DOWN, SOLID_UP, UNGRADED, SOFT_UP, NONE),
			sorted(SortMode.STACK_VALUE, false));
	}

	private static List<MovementRow> sorted(final SortMode sort, final boolean descending)
	{
		final List<MovementRow> rows = new ArrayList<>(Arrays.asList(NONE, SOFT_DOWN, SOLID_UP, SOFT_UP, UNGRADED,
			SOLID_DOWN));
		rows.sort(MovementMath.comparator(sort, descending));
		return rows;
	}

	private static MovementRow row(final int id, final String name, final long unit, final Long deltaGp,
		final Double deltaPct, final GradedMove graded)
	{
		return new MovementRow(90_000 + id, name, 1, false, unit, deltaGp == null ? null : unit - deltaGp, deltaGp,
			deltaPct, unit, MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0, graded);
	}

	/** Two prints a side today against a busy yesterday: a SOLID figure. */
	private static GradedMove solid()
	{
		final TradedPriceClient.Bucket yesterday = new TradedPriceClient.Bucket(1_000L, 20_000L, 980L, 20_000L);
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, 1_000L,
			new TradedPriceClient.Quote(1_030L, at(13, 50), 1_010L, at(13, 40)), new long[]{1_020L, at(10, 0)},
			new long[]{1_000L, at(9, 0)}, null, 0L, yesterday, TODAY.minusDays(1), null, null);
		return graded(facts, yesterday, Grade.SOLID);
	}

	/** A thin compared day - the sell side traded 7 units yesterday: a SOFT figure (its word is "low vol 7"). */
	private static GradedMove soft()
	{
		final TradedPriceClient.Bucket yesterday = new TradedPriceClient.Bucket(1_000L, 20_000L, 980L, 7L);
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, 1_000L,
			new TradedPriceClient.Quote(1_030L, at(13, 50), 1_010L, at(13, 40)), null, null, null, 0L, yesterday,
			TODAY.minusDays(1), null, null);
		return graded(facts, yesterday, Grade.SOFT);
	}

	/** No trades at all: NONE. */
	private static GradedMove none()
	{
		final GradeMath.Facts facts = GradeMath.facts(NOW, TODAY, 1_000L, null, null, null, null, 0L, null, null, null,
			null);
		return graded(facts, null, Grade.NONE);
	}

	private static GradedMove graded(final GradeMath.Facts facts, final TradedPriceClient.Bucket day,
		final Grade expected)
	{
		final GradedMove move = GradeMath.figure(facts, MovementWindow.D1, day, TODAY.minusDays(1));
		assertEquals("the fixture's own grade", expected, move.grade());
		return move;
	}

	private static long at(final int hour, final int minute)
	{
		return TODAY.atTime(hour, minute).toEpochSecond(ZoneOffset.UTC);
	}
}
