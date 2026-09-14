package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.Test;

/**
 * {@link MovementMath} (contract C8-C10): every number the sidebar shows.
 *
 * <p>Four things are worth the length. (1) The SOURCE ladder - the guide price now (addendum K, source GUIDE),
 * else the fallback price the service no longer offers, else no price - because a fallback price must never be
 * subtracted from a baseline to invent a move (D9, C8, K13). (2) NULLS LAST under both directions, because a
 * bank item whose name is in neither the mapping nor the guide table has no baseline, and flipping the arrow
 * must not fill the top of the list with dashes. (3) The gp BAND
 * edges, which are the user's headline feature. (4) {@code parseGp}, which is the one place user typing
 * becomes a number. (5) Since addendum L, the PRINTED percentage: truncation toward zero and a sign taken from
 * the gp change (L2), because the number on the row is compared against the Grand Exchange site's own by the
 * user, item by item.
 */
public class MovementMathTest
{
	/**
	 * A stack of one, priced by a point whose two sides are equal, so the mid is exactly the number written
	 * here and the sort tests read as their own expectations.
	 */
	private static MovementRow guideRow(final int id, final String name, final long unit, final Long then)
	{
		return MovementMath.row(new BankItem(id, 1, name, false), new PricePoint(unit, unit),
			then == null ? null : new PricePoint(then, then), 0);
	}

	private static List<String> names(final List<MovementRow> rows)
	{
		final List<String> names = new ArrayList<>();
		for (final MovementRow row : rows)
		{
			names.add(row.name());
		}

		return names;
	}

	// ---------------------------------------------------------------- row: where the price comes from (C8)

	@Test
	public void aGuidePriceIsTheMidOfTheNowPoint()
	{
		final MovementRow row = MovementMath.row(new BankItem(4151, 2, "Abyssal whip", false),
			new PricePoint(1_600_000L, 1_500_000L), new PricePoint(1_500_000L, 1_400_000L), 999);

		assertEquals(4151, row.id());
		assertEquals("Abyssal whip", row.name());
		assertEquals(2, row.quantity());
		assertFalse(row.stackable());
		assertEquals(Long.valueOf(1_550_000L), row.unitPrice());
		assertEquals(Long.valueOf(1_450_000L), row.thenPrice());
		assertSame("a guide price wins over the fallback", MovementRow.PriceSource.GUIDE, row.source());
		assertEquals(3_100_000L, row.holdingValue());
	}

	@Test
	public void oneSidedNowStillPricesTheRow()
	{
		assertEquals(Long.valueOf(180L),
			MovementMath.row(new BankItem(2, 1, "Cannonball", true), new PricePoint(null, 180L), null, 0)
				.unitPrice());
		assertEquals(Long.valueOf(190L),
			MovementMath.row(new BankItem(2, 1, "Cannonball", true), new PricePoint(190L, null), null, 0)
				.unitPrice());
	}

	@Test
	public void noGuidePriceFallsBackToRuneliteAndSaysSo()
	{
		final MovementRow row = MovementMath.row(new BankItem(4151, 3, "Abyssal whip", false), null,
			new PricePoint(1_000_000L, 1_000_000L), 1_234_567);

		assertEquals(Long.valueOf(1_234_567L), row.unitPrice());
		assertSame(MovementRow.PriceSource.RUNELITE, row.source());
		assertEquals(3_703_701L, row.holdingValue());
		assertNull("a fallback price is not the guide series - no move can be computed from it", row.deltaGp());
		assertNull(row.deltaPct());
		assertFalse(row.hasMovement());
		assertEquals("the baseline is still shown", Long.valueOf(1_000_000L), row.thenPrice());
	}

	/**
	 * A point that exists but carries NEITHER side is "no price", not zero - the defensive case of C3.
	 */
	@Test
	public void anEmptyNowPointIsTreatedAsNoPrice()
	{
		final MovementRow row = MovementMath.row(new BankItem(2, 1, "Cannonball", true), PricePoint.EMPTY,
			null, 500);

		assertEquals(Long.valueOf(500L), row.unitPrice());
		assertSame(MovementRow.PriceSource.RUNELITE, row.source());
	}

	@Test
	public void noPriceAnywhereIsSourceNone()
	{
		final MovementRow row = MovementMath.row(new BankItem(2, 7, "Cannonball", true), null, null, 0);

		assertNull(row.unitPrice());
		assertSame(MovementRow.PriceSource.NONE, row.source());
		assertEquals("no price, no holding value", 0L, row.holdingValue());
		assertFalse(row.hasMovement());
	}

	@Test
	public void aZeroFallbackIsNotAPrice()
	{
		assertSame(MovementRow.PriceSource.NONE,
			MovementMath.row(new BankItem(2, 1, "Cannonball", true), null, null, 0).source());
		assertSame("a negative fallback is not a price either", MovementRow.PriceSource.NONE,
			MovementMath.row(new BankItem(2, 1, "Cannonball", true), null, null, -5).source());
	}

	@Test
	public void aNullItemIsAProgrammingError()
	{
		try
		{
			MovementMath.row(null, PricePoint.EMPTY, PricePoint.EMPTY, 0);
			fail("a row without an item should not be constructible");
		}
		catch (NullPointerException expected)
		{
			// the point of the test
		}
	}

	// ---------------------------------------------------------------- row: the move (C8)

	@Test
	public void aRiseAndAFallAreSignedGpAndPercent()
	{
		final MovementRow rise = guideRow(1, "Rise", 1_550_000L, 1_450_000L);
		assertEquals(Long.valueOf(100_000L), rise.deltaGp());
		assertNotNull(rise.deltaPct());
		assertEquals(6.8966d, rise.deltaPct(), 0.0001d);
		assertTrue(rise.hasMovement());

		final MovementRow fall = guideRow(2, "Fall", 900L, 1_000L);
		assertEquals(Long.valueOf(-100L), fall.deltaGp());
		assertEquals(-10.0d, fall.deltaPct(), 0.0001d);

		final MovementRow flat = guideRow(3, "Flat", 1_000L, 1_000L);
		assertEquals(Long.valueOf(0L), flat.deltaGp());
		assertEquals(0.0d, flat.deltaPct(), 0.0001d);
		assertTrue("a flat row still HAS a movement - it is zero, not unknown", flat.hasMovement());
	}

	@Test
	public void noBaselineIsNoMove()
	{
		final MovementRow row = guideRow(1, "Alpha", 1_000L, null);

		assertNull(row.thenPrice());
		assertNull(row.deltaGp());
		assertNull(row.deltaPct());
		assertFalse(row.hasMovement());
		assertSame("the price itself is still a guide price", MovementRow.PriceSource.GUIDE, row.source());
	}

	/**
	 * A baseline of zero would make the percentage infinite; the row shows the price and no move instead.
	 */
	@Test
	public void aZeroBaselineIsNoMove()
	{
		final MovementRow row = guideRow(1, "Alpha", 1_000L, 0L);

		assertEquals(Long.valueOf(0L), row.thenPrice());
		assertNull(row.deltaGp());
		assertNull(row.deltaPct());
	}

	/**
	 * L1 spells 0 as "there is no price for this item", and the two other places that decide what counts as a
	 * price - the service's own {@code price > 0} and {@code PortfolioMath}'s {@code priced} - already say so. A
	 * "now" of 0 reaches here through L3's degraded mode, where the side comes from a guide TABLE rather than
	 * from RuneLite; a row priced at 0 would pass the band, count as priced, read "-100.0%" at the top of the
	 * losers, and be left out of the bank-value card that the same list is published beside.
	 */
	@Test
	public void aZeroGuidePriceIsNoPriceAtAll()
	{
		final MovementRow zero = MovementMath.row(new BankItem(1, 5, "Alpha", false),
			new PricePoint(0L, 0L), new PricePoint(1_000L, 1_000L), 0);

		assertNull(zero.unitPrice());
		assertEquals(MovementRow.PriceSource.NONE, zero.source());
		assertNull("and no move against a baseline it cannot be compared with", zero.deltaGp());
		assertEquals(0L, zero.holdingValue());

		final MovementRow negative = MovementMath.row(new BankItem(2, 1, "Bravo", false),
			new PricePoint(-5L, -5L), null, 0);
		assertNull("a negative is not a price either", negative.unitPrice());

		final MovementRow fallback = MovementMath.row(new BankItem(3, 1, "Charlie", false),
			new PricePoint(0L, 0L), null, 700);
		assertEquals("the ladder falls through to the caller's fallback, as it does for a null point",
			Long.valueOf(700L), fallback.unitPrice());
		assertEquals(MovementRow.PriceSource.RUNELITE, fallback.source());
	}

	@Test
	public void anEmptyBaselinePointIsNoMove()
	{
		final MovementRow row = MovementMath.row(new BankItem(1, 1, "Alpha", false),
			new PricePoint(100L, 100L), PricePoint.EMPTY, 0);

		assertNull(row.thenPrice());
		assertNull(row.deltaGp());
	}

	// ---------------------------------------------------------------- holding value (C8)

	@Test
	public void aHoldingIsThePriceTimesTheStack()
	{
		assertEquals(3_100_000L, MovementMath.holdingValue(1_550_000L, 2));
		assertEquals(0L, MovementMath.holdingValue(null, 5));
		assertEquals(0L, MovementMath.holdingValue(100L, 0));
		assertEquals(0L, MovementMath.holdingValue(100L, -3));
	}

	/**
	 * A bank can hold billions of a billion-gp item on paper; the product is clamped, never wrapped into a
	 * negative fortune.
	 */
	@Test
	public void anImpossibleHoldingClampsRatherThanWraps()
	{
		assertEquals(Long.MAX_VALUE, MovementMath.holdingValue(Long.MAX_VALUE, 2));
		assertEquals(Long.MAX_VALUE, MovementMath.holdingValue(Long.MAX_VALUE / 2L, 5));
		assertTrue(MovementMath.holdingValue(Long.MAX_VALUE, Integer.MAX_VALUE) > 0L);

		final MovementRow row = MovementMath.row(new BankItem(2, 2, "Cannonball", true),
			new PricePoint(Long.MAX_VALUE, Long.MAX_VALUE), null, 0);
		assertEquals(Long.MAX_VALUE, row.holdingValue());
	}

	// ---------------------------------------------------------------- the gp band (C9, C10)

	@Test
	public void theBandIsInclusiveAtBothEnds()
	{
		assertTrue(MovementMath.inBand(100L, 100L, 200L));
		assertTrue(MovementMath.inBand(200L, 100L, 200L));
		assertTrue(MovementMath.inBand(150L, 100L, 200L));
		assertFalse(MovementMath.inBand(99L, 100L, 200L));
		assertFalse(MovementMath.inBand(201L, 100L, 200L));
	}

	@Test
	public void aMaximumOfZeroIsNoUpperBound()
	{
		assertTrue(MovementMath.inBand(Long.MAX_VALUE, 0L, 0L));
		assertTrue(MovementMath.inBand(0L, 0L, 0L));
		assertTrue(MovementMath.inBand(2_000_000_000L, 1_000_000L, 0L));
		assertFalse("the minimum still bites", MovementMath.inBand(999_999L, 1_000_000L, 0L));
	}

	@Test
	public void applyKeepsOnlyTheBand()
	{
		final List<MovementRow> rows = Arrays.asList(
			guideRow(1, "Under", 99L, 50L),
			guideRow(2, "AtMin", 100L, 50L),
			guideRow(3, "Between", 150L, 50L),
			guideRow(4, "AtMax", 200L, 50L),
			guideRow(5, "Over", 201L, 50L));

		final List<MovementRow> kept = MovementMath.apply(rows,
			new RowFilter(100L, 200L, SortMode.UNIT_PRICE, false, MovementWindow.D1));

		assertEquals(Arrays.asList("AtMin", "Between", "AtMax"), names(kept));
	}

	/**
	 * An item with no price at all cannot be judged against a gp band, so it leaves the list entirely rather
	 * than sitting at the bottom and inflating the count line.
	 */
	@Test
	public void applyDropsRowsWithNoUnitPrice()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(1, 1, "Priceless", false), null, null, 0),
			guideRow(2, "Priced", 100L, 50L));

		final List<MovementRow> kept = MovementMath.apply(rows, RowFilter.DEFAULT);

		assertEquals(Collections.singletonList("Priced"), names(kept));
	}

	@Test
	public void applyToleratesNullsAndReturnsANewUnmodifiableList()
	{
		final List<MovementRow> rows = new ArrayList<>();
		rows.add(null);
		rows.add(guideRow(1, "Alpha", 100L, 50L));

		final List<MovementRow> kept = MovementMath.apply(rows, null);
		assertEquals("a null filter reads as the default, and the null row is skipped", 1, kept.size());
		assertEquals("the caller's list is left as it was", 2, rows.size());

		try
		{
			kept.add(guideRow(2, "Bravo", 100L, 50L));
			fail("the published row list must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}

		assertTrue(MovementMath.apply(null, RowFilter.DEFAULT).isEmpty());
		assertTrue(MovementMath.apply(Collections.emptyList(), RowFilter.DEFAULT).isEmpty());
	}

	// ---------------------------------------------------------------- the sorts (C9)

	/**
	 * The five-row fixture every sort test reads: two moves up and down around a flat one, and two rows the
	 * bucket never carried.
	 */
	private static List<MovementRow> fixture()
	{
		return Arrays.asList(
			guideRow(1, "Alpha", 100L, 50L),      // +50 gp, +100 %
			guideRow(2, "Bravo", 100L, 200L),     // -100 gp, -50 %
			guideRow(3, "Charlie", 100L, 100L),   // 0 gp, 0 %
			guideRow(4, "Delta", 100L, null),     // no baseline
			guideRow(5, "Echo", 100L, null));     // no baseline
	}

	@Test
	public void percentMoveSortsBothWaysWithTheDashesLast()
	{
		assertEquals(Arrays.asList("Alpha", "Charlie", "Bravo", "Delta", "Echo"),
			names(MovementMath.apply(fixture(),
				new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, true, MovementWindow.D1))));

		assertEquals(Arrays.asList("Bravo", "Charlie", "Alpha", "Delta", "Echo"),
			names(MovementMath.apply(fixture(),
				new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, false, MovementWindow.D1))));
	}

	@Test
	public void gpMoveSortsBothWaysWithTheDashesLast()
	{
		assertEquals(Arrays.asList("Alpha", "Charlie", "Bravo", "Delta", "Echo"),
			names(MovementMath.apply(fixture(),
				new RowFilter(0L, 0L, SortMode.GP_MOVE, true, MovementWindow.D1))));

		assertEquals(Arrays.asList("Bravo", "Charlie", "Alpha", "Delta", "Echo"),
			names(MovementMath.apply(fixture(),
				new RowFilter(0L, 0L, SortMode.GP_MOVE, false, MovementWindow.D1))));
	}

	@Test
	public void unitPriceSortsBothWays()
	{
		final List<MovementRow> rows = Arrays.asList(
			guideRow(1, "Cheap", 10L, 10L),
			guideRow(2, "Dear", 1_000_000L, 10L),
			guideRow(3, "Middling", 5_000L, 10L));

		assertEquals(Arrays.asList("Dear", "Middling", "Cheap"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.UNIT_PRICE, true, MovementWindow.D1))));

		assertEquals(Arrays.asList("Cheap", "Middling", "Dear"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.UNIT_PRICE, false, MovementWindow.D1))));
	}

	/**
	 * The tiebreak does NOT flip with the arrow: equal keys stay alphabetical, so a list of identical prices
	 * does not reverse itself when the user only meant to flip the direction of the sort.
	 */
	@Test
	public void tiesBreakByNameThenIdInBothDirections()
	{
		final List<MovementRow> rows = Arrays.asList(
			guideRow(9, "Bravo", 100L, 50L),
			guideRow(2, "alpha", 100L, 50L),
			guideRow(1, "Alpha", 100L, 50L));

		assertEquals(Arrays.asList("Alpha", "alpha", "Bravo"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.UNIT_PRICE, true, MovementWindow.D1))));

		assertEquals(Arrays.asList("Alpha", "alpha", "Bravo"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.UNIT_PRICE, false, MovementWindow.D1))));
	}

	/**
	 * Rows with no key at all are ordered among themselves by name, whichever way the arrow points.
	 */
	@Test
	public void theDashesAreAlphabeticalAmongThemselves()
	{
		final List<MovementRow> rows = Arrays.asList(
			guideRow(1, "Zulu", 100L, null),
			guideRow(2, "Yankee", 100L, null),
			guideRow(3, "Alpha", 100L, 50L));

		assertEquals(Arrays.asList("Alpha", "Yankee", "Zulu"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, true, MovementWindow.D1))));

		assertEquals(Arrays.asList("Alpha", "Yankee", "Zulu"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, false, MovementWindow.D1))));
	}

	@Test
	public void theComparatorIsAvailableOnItsOwnAndDefaultsToPercent()
	{
		final List<MovementRow> rows = new ArrayList<>(fixture());
		rows.sort(MovementMath.comparator(null, true));

		assertEquals(Arrays.asList("Alpha", "Charlie", "Bravo", "Delta", "Echo"), names(rows));
	}

	// ---------------------------------------------------------------- addendum Q: alch rows and the holding sort

	/**
	 * Q5. An untradeable stack becomes a row priced at its High Alchemy value and marked as such, with no
	 * baseline and therefore no move: an alch value is a constant of the item, so a "change" computed from two of
	 * them would be a movement that never happened.
	 */
	@Test
	public void anAlchRowIsPricedByTheAlchValueAndHasNoMove()
	{
		final MovementRow row = MovementMath.alchRow(new BankItem(772, 4, "Dramen staff", false, true, 1500));

		assertEquals(772, row.id());
		assertEquals("Dramen staff", row.name());
		assertEquals(4, row.quantity());
		assertEquals(Long.valueOf(1500L), row.unitPrice());
		assertSame(MovementRow.PriceSource.ALCH, row.source());
		assertNull(row.thenPrice());
		assertNull(row.deltaGp());
		assertNull(row.deltaPct());
		assertFalse(row.hasMovement());
		assertEquals("the stack is worth the alch value times the quantity", 6_000L, row.holdingValue());
	}

	/**
	 * A stack the reader would never keep - not untradeable, or untradeable and worth nothing to alch - has no
	 * price here at all, so {@link MovementMath#apply} drops it exactly as it drops any other unpriced row rather
	 * than listing a 0 gp item.
	 */
	@Test
	public void anAlchRowWithNoAlchValueIsNoPriceAtAll()
	{
		final MovementRow worthless = MovementMath.alchRow(new BankItem(1, 1, "Junk", false, true, 0));
		final MovementRow tradeable = MovementMath.alchRow(new BankItem(4151, 1, "Abyssal whip", false));

		assertNull(worthless.unitPrice());
		assertSame(MovementRow.PriceSource.NONE, worthless.source());
		assertNull(tradeable.unitPrice());
		assertSame(MovementRow.PriceSource.NONE, tradeable.source());
		assertTrue(MovementMath.apply(Arrays.asList(worthless, tradeable), RowFilter.DEFAULT).isEmpty());
	}

	/** The band judges an alch row on its alch price, which is the only price it has (Q5). */
	@Test
	public void theBandJudgesAnAlchRowOnItsAlchPrice()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.alchRow(new BankItem(1, 1, "Cheap staff", false, true, 900)),
			MovementMath.alchRow(new BankItem(2, 1, "Dear staff", false, true, 1500)));

		assertEquals(Collections.singletonList("Dear staff"), names(MovementMath.apply(rows,
			new RowFilter(1_000L, 0L, SortMode.UNIT_PRICE, true, MovementWindow.D1))));
	}

	/**
	 * Q6. With the holding switch on, the gp column compares the change over the WHOLE stack, so the list
	 * agrees with the figures those rows are printing. Here the bulk stack's tiny unit move is the bigger money:
	 * 1,000 sharks up 20 gp each is +20,000, against one whip up 5,000.
	 */
	@Test
	public void theGpSortComparesTheStacksChangeWhileHoldingModeIsOn()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(4151, 1, "Whip", false), new PricePoint(1_005_000L, 1_005_000L),
				new PricePoint(1_000_000L, 1_000_000L), 0),
			MovementMath.row(new BankItem(385, 1_000, "Shark", true), new PricePoint(1_020L, 1_020L),
				new PricePoint(1_000L, 1_000L), 0));
		final RowFilter gain = new RowFilter(0L, 0L, SortMode.GP_MOVE, true, MovementWindow.D1);

		assertEquals("per item, the whip moved more", Arrays.asList("Whip", "Shark"),
			names(MovementMath.apply(rows, gain, ViewOptions.DEFAULT)));
		assertEquals("per stack, the sharks did", Arrays.asList("Shark", "Whip"),
			names(MovementMath.apply(rows, gain, ViewOptions.DEFAULT.withHoldingOnRows(true))));
		assertEquals("and the other way round for the losers", Arrays.asList("Whip", "Shark"),
			names(MovementMath.apply(rows, new RowFilter(0L, 0L, SortMode.GP_MOVE, false, MovementWindow.D1),
				ViewOptions.DEFAULT.withHoldingOnRows(true))));
	}

	/**
	 * The OTHER THREE columns are the same list either way (Q6, widened back by addendum W line W1): a percentage
	 * is the same number whether one item or a thousand is held, and since W the price of one item and the worth
	 * of the stack are two columns rather than one column read two ways. Addendum V's line V1 had
	 * {@link SortMode#UNIT_PRICE} follow this switch for a day; this is the test that says it does not.
	 */
	@Test
	public void theOtherColumnsAreUnmovedByTheHoldingSwitch()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(4151, 1, "Whip", false), new PricePoint(1_005_000L, 1_005_000L),
				new PricePoint(1_000_000L, 1_000_000L), 0),
			MovementMath.row(new BankItem(385, 1_000, "Shark", true), new PricePoint(1_020L, 1_020L),
				new PricePoint(1_000L, 1_000L), 0));
		final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);

		for (final SortMode sort : new SortMode[]{SortMode.PERCENT_MOVE, SortMode.UNIT_PRICE, SortMode.STACK_VALUE})
		{
			for (final boolean descending : new boolean[]{true, false})
			{
				final RowFilter filter = new RowFilter(0L, 0L, sort, descending, MovementWindow.D1);
				assertEquals(sort + " descending=" + descending,
					names(MovementMath.apply(rows, filter, ViewOptions.DEFAULT)),
					names(MovementMath.apply(rows, filter, holding)));
			}
		}
	}

	// ---------------------------------------------------------------- addendum W: the Stack price column

	/**
	 * W1. {@link SortMode#STACK_VALUE} orders by what the whole STACK is worth - unit price times quantity - so
	 * the user's own case has a column of its own: "perhaps someone has 100 robin hood hats and that's worth a
	 * lot so the sort would show that near the top" (2026-09-12). A hundred hats at 3m is 300m of bank, and the
	 * Item price column - which is the price of ONE hat, whatever any switch says - cannot answer that question.
	 */
	@Test
	public void theStackPriceSortComparesWhatTheWholeStackIsWorth()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(4151, 1, "Whip", false),
				new PricePoint(20_000_000L, 20_000_000L), null, 0),
			MovementMath.row(new BankItem(2581, 100, "Hat", true),
				new PricePoint(3_000_000L, 3_000_000L), null, 0));
		final RowFilter biggest = new RowFilter(0L, 0L, SortMode.STACK_VALUE, true, MovementWindow.D1);
		final RowFilter smallest = new RowFilter(0L, 0L, SortMode.STACK_VALUE, false, MovementWindow.D1);

		assertEquals("100 x 3,000,000 leads 1 x 20,000,000", Arrays.asList("Hat", "Whip"),
			names(MovementMath.apply(rows, biggest)));
		assertEquals("and the arrow turned over is that list turned round", Arrays.asList("Whip", "Hat"),
			names(MovementMath.apply(rows, smallest)));
	}

	/**
	 * A row with no MOVE still has a price, so it still has a stack key. The dashes go to the bottom under the
	 * two movement columns and take their proper place among the money under the two price ones - a bank full of
	 * items the wiki has no baseline for is still a bank whose biggest stacks the user can ask for.
	 */
	@Test
	public void aRowWithNoMoveStillHasAStackKey()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(1, 10, "Nomove", true), new PricePoint(1_000L, 1_000L), null, 0),
			MovementMath.row(new BankItem(2, 1, "Mover", false), new PricePoint(100L, 100L),
				new PricePoint(50L, 50L), 0));

		assertEquals("10 x 1,000 leads though only the other one moved", Arrays.asList("Nomove", "Mover"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.STACK_VALUE, true, MovementWindow.D1))));
		assertEquals("while a movement column puts the dash last", Arrays.asList("Mover", "Nomove"),
			names(MovementMath.apply(rows,
				new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, true, MovementWindow.D1))));
	}

	/**
	 * W1, the other half of the same pair: Item price is the price of ONE item under EVERY switch. Addendum V's
	 * line V1 moved it onto the stack while <i>Show stack value on rows</i> was on and addendum W reverted
	 * that, because the stack now has its own column and a column that changed its meaning with a display switch
	 * was a column you could not name.
	 */
	@Test
	public void theItemPriceSortIsTheUnitPriceUnderEverySwitch()
	{
		final List<MovementRow> rows = Arrays.asList(
			MovementMath.row(new BankItem(4151, 1, "Whip", false),
				new PricePoint(20_000_000L, 20_000_000L), null, 0),
			MovementMath.row(new BankItem(2581, 100, "Hat", true),
				new PricePoint(3_000_000L, 3_000_000L), null, 0));
		final RowFilter dearest = new RowFilter(0L, 0L, SortMode.UNIT_PRICE, true, MovementWindow.D1);
		final RowFilter cheapest = new RowFilter(0L, 0L, SortMode.UNIT_PRICE, false, MovementWindow.D1);
		final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);

		assertEquals("one whip is the dearer item", Arrays.asList("Whip", "Hat"),
			names(MovementMath.apply(rows, dearest, ViewOptions.DEFAULT)));
		assertEquals("and still is with holding on - 300m of hats do not make one hat dear",
			Arrays.asList("Whip", "Hat"), names(MovementMath.apply(rows, dearest, holding)));
		assertEquals(Arrays.asList("Hat", "Whip"), names(MovementMath.apply(rows, cheapest, ViewOptions.DEFAULT)));
		assertEquals(Arrays.asList("Hat", "Whip"), names(MovementMath.apply(rows, cheapest, holding)));
	}

	/**
	 * W1's null gate: the stack key is gated on {@link MovementRow#unitPrice()}, not on the holding value, so a
	 * row with no price is LAST whichever way the arrow points. {@link MovementRow#holdingValue()} answers a
	 * plain 0 for such a row, which would otherwise sort as the smallest stack in the bank rather than as a dash
	 * - and would take the TOP of the list the moment the user pressed the column again.
	 */
	@Test
	public void aPricelessRowHasNoStackKeyAndStaysLast()
	{
		final MovementRow priced = MovementMath.row(new BankItem(2581, 100, "Hat", true),
			new PricePoint(3_000_000L, 3_000_000L), null, 0);
		final MovementRow priceless = MovementMath.row(new BankItem(1, 500, "Junk", true), null, null, 0);
		assertNull("the fixture is the case: no price, and a holding of 0", priceless.unitPrice());
		assertEquals(0L, priceless.holdingValue());

		for (final boolean descending : new boolean[]{true, false})
		{
			for (final ViewOptions options : new ViewOptions[]{
				ViewOptions.DEFAULT, ViewOptions.DEFAULT.withHoldingOnRows(true)})
			{
				final List<MovementRow> stacks = new ArrayList<>(Arrays.asList(priceless, priced));
				stacks.sort(MovementMath.comparator(SortMode.STACK_VALUE, descending, options));
				assertEquals("stack, descending=" + descending, Arrays.asList("Hat", "Junk"), names(stacks));

				final List<MovementRow> units = new ArrayList<>(Arrays.asList(priceless, priced));
				units.sort(MovementMath.comparator(SortMode.UNIT_PRICE, descending, options));
				assertEquals("unit, descending=" + descending, Arrays.asList("Hat", "Junk"), names(units));
			}
		}
	}

	/** A row with no baseline has no gp key under either reading, so it still sorts last rather than as a 0. */
	@Test
	public void theDashesAreStillLastInHoldingMode()
	{
		assertEquals(Arrays.asList("Alpha", "Charlie", "Bravo", "Delta", "Echo"),
			names(MovementMath.apply(fixture(), new RowFilter(0L, 0L, SortMode.GP_MOVE, true, MovementWindow.D1),
				ViewOptions.DEFAULT.withHoldingOnRows(true))));
	}

	/** The pre-Q arities mean what they always did: the default switches, which are today's behaviour. */
	@Test
	public void theOldApplyAndComparatorReadAsTheDefaultOptions()
	{
		final List<MovementRow> rows = new ArrayList<>(fixture());
		final RowFilter filter = new RowFilter(0L, 0L, SortMode.GP_MOVE, true, MovementWindow.D1);

		assertEquals(names(MovementMath.apply(rows, filter, ViewOptions.DEFAULT)),
			names(MovementMath.apply(rows, filter)));

		final List<MovementRow> viaOld = new ArrayList<>(rows);
		final List<MovementRow> viaNew = new ArrayList<>(rows);
		viaOld.sort(MovementMath.comparator(SortMode.GP_MOVE, true));
		viaNew.sort(MovementMath.comparator(SortMode.GP_MOVE, true, null));
		assertEquals("a null options reads as the default too", names(viaOld), names(viaNew));
	}

	/**
	 * The stack change is signed, so its overflow clamp has to pick an END: a collapse clamped upwards would sort
	 * to the top of the gainers.
	 */
	@Test
	public void theStackChangeClampsBySignRatherThanWrapping()
	{
		assertEquals(30_000L, MovementMath.holdingDelta(10_000L, 3));
		assertEquals(-30_000L, MovementMath.holdingDelta(-10_000L, 3));
		assertEquals(0L, MovementMath.holdingDelta(null, 3));
		assertEquals(0L, MovementMath.holdingDelta(10_000L, 0));
		assertEquals(0L, MovementMath.holdingDelta(10_000L, -3));
		assertEquals(Long.MAX_VALUE, MovementMath.holdingDelta(Long.MAX_VALUE, 2));
		assertEquals(Long.MIN_VALUE, MovementMath.holdingDelta(Long.MIN_VALUE, 2));
	}

	// ---------------------------------------------------------------- reading what the user typed (C10)

	@Test
	public void parseGpTakesTheShorthandTheGameUses() throws ParseException
	{
		assertEquals(850L, MovementMath.parseGp("850"));
		assertEquals(100_000L, MovementMath.parseGp("100k"));
		assertEquals(100_000L, MovementMath.parseGp("100K"));
		assertEquals(1_500_000L, MovementMath.parseGp("1.5m"));
		assertEquals(2_000_000_000L, MovementMath.parseGp("2b"));
		assertEquals(1_000L, MovementMath.parseGp("1,000"));
		assertEquals(250L, MovementMath.parseGp("  250  "));
		assertEquals(0L, MovementMath.parseGp("0"));
	}

	@Test
	public void parseGpRefusesWhatIsNotAPrice()
	{
		final String[] rubbish = {null, "", "   ", "abc", "1.5x", "10 000", "k", "+5", "1e6"};

		for (final String text : rubbish)
		{
			try
			{
				final long value = MovementMath.parseGp(text);
				fail("\"" + text + "\" should not have parsed, gave " + value);
			}
			catch (ParseException expected)
			{
				// the point of the test
			}
		}
	}

	/**
	 * A price cannot be negative. {@code QuantityFormatter}'s own pattern admits a leading minus
	 * ({@code QuantityFormatter.java:49}), so the band's rule is added here.
	 */
	@Test
	public void parseGpRefusesANegative()
	{
		final String[] negatives = {"-1", "-100k", "-1,000"};

		for (final String text : negatives)
		{
			try
			{
				fail("\"" + text + "\" should not have parsed, gave " + MovementMath.parseGp(text));
			}
			catch (ParseException expected)
			{
				// the point of the test
			}
		}
	}

	// ---------------------------------------------------------------- writing what the row shows (C10)

	@Test
	public void gpIsWrittenTheWayTheGameWritesAStack()
	{
		assertEquals("0", MovementMath.formatGp(0L));
		assertEquals("850", MovementMath.formatGp(850L));
		assertEquals("9,999", MovementMath.formatGp(9_999L));
		assertEquals("10k", MovementMath.formatGp(10_000L));
		assertEquals("12.3k", MovementMath.formatGp(12_300L));
		assertEquals("1.52m", MovementMath.formatGp(1_520_000L));
		assertEquals("-12.3k", MovementMath.formatGp(-12_300L));

		final String huge = MovementMath.formatGp(2_147_483_647L);
		assertTrue("a billion-gp price ends in b: " + huge, huge.endsWith("b"));
	}

	@Test
	public void theTooltipGetsEveryDigit()
	{
		assertEquals("1,520,000", MovementMath.formatExact(1_520_000L));
		assertEquals("850", MovementMath.formatExact(850L));
		assertEquals("-4,100", MovementMath.formatExact(-4_100L));
	}

	@Test
	public void aDeltaCarriesItsSignAndADashWhenThereIsNone()
	{
		assertEquals("-", MovementMath.formatDelta(null));
		assertEquals("+12.3k", MovementMath.formatDelta(12_300L));
		assertEquals("+850", MovementMath.formatDelta(850L));
		assertEquals("-4,100", MovementMath.formatDelta(-4_100L));
		assertEquals("-12.3k", MovementMath.formatDelta(-12_300L));
		assertEquals("0", MovementMath.formatDelta(0L));
		assertTrue("the extreme must not overflow on its way to the screen",
			MovementMath.formatDelta(Long.MIN_VALUE).startsWith("-"));
	}

	@Test
	public void aPercentIsOneDecimalWithItsSign()
	{
		assertEquals("-", MovementMath.formatPct(null));
		assertEquals("+0.8%", MovementMath.formatPct(0.83d));
		assertEquals("-12.4%", MovementMath.formatPct(-12.44d));
		assertEquals("+100.0%", MovementMath.formatPct(100.0d));
		assertEquals("0.0%", MovementMath.formatPct(0.0d));
	}

	/**
	 * L2/L-B: the Grand Exchange site TRUNCATES toward zero (26/26 of its printed figures reproduce with
	 * truncation, 15/26 with rounding), so a move never prints larger than it is. The last two cases are the
	 * binary trap: {@code (long) (0.7 * 10)} is 6, and a percentage of 0.7 that printed "0.6%" would be off by
	 * a tenth in the direction the site never goes.
	 */
	@Test
	public void aPercentIsTruncatedTowardZeroNeverRounded()
	{
		assertEquals("+0.7%", MovementMath.formatPct(0.79d));
		assertEquals("-12.4%", MovementMath.formatPct(-12.46d));
		assertEquals("+3.9%", MovementMath.formatPct(3.999d));
		assertEquals("-3.9%", MovementMath.formatPct(-3.999d));
		assertEquals("+0.7%", MovementMath.formatPct(0.7d));
		assertEquals("-0.7%", MovementMath.formatPct(-0.7d));
	}

	/**
	 * L2: a fall too small to survive truncation still shows its minus and stays red, exactly as the site does
	 * ("Amulet of fury", 30 d, -0.10 % printed "-0.0%" with a red arrow, L-B). Only a genuinely unmoved row -
	 * a gp change of zero - reads a bare "0.0%".
	 */
	@Test
	public void aVanishinglySmallMoveKeepsItsSign()
	{
		assertEquals("-0.0%", MovementMath.formatPct(-0.01d));
		assertEquals("-0.0%", MovementMath.formatPct(-0.04d));
		assertEquals("+0.0%", MovementMath.formatPct(0.04d));
		assertEquals("0.0%", MovementMath.formatPct(0.0d));
		assertEquals("0.0%", MovementMath.formatPct(-0.0d));
		assertEquals("-", MovementMath.formatPct(Double.NaN));
		assertEquals("-", MovementMath.formatPct(Double.POSITIVE_INFINITY));
	}

	/**
	 * L2: the sign a ROW shows comes from the gp change, not from the percentage - the two must never disagree,
	 * and the colour is painted from the same gp figure ({@code MovementRowPanel.changeColor}).
	 */
	@Test
	public void aPercentTakesItsSignFromTheGpChangeWhenOneIsGiven()
	{
		assertEquals("-0.0%", MovementMath.formatPct(-0.004d, -1L));
		assertEquals("+0.0%", MovementMath.formatPct(0.004d, 1L));
		assertEquals("0.0%", MovementMath.formatPct(0.0d, 0L));
		assertEquals("-3.4%", MovementMath.formatPct(-3.41d, -38L));
		assertEquals("+12.8%", MovementMath.formatPct(12.845d, 550_000L));
		assertEquals("the percentage's own sign stands in when no gp figure is offered",
			"-0.0%", MovementMath.formatPct(-0.004d, null));
		assertEquals("-", MovementMath.formatPct(null, -38L));
	}

	/**
	 * The one figure a live session compares against the Grand Exchange site: Green hat, 1,124 -&gt; 1,086 on the
	 * 1 d window (calibration doc, 2026-09-08). -38 / 1124 x 100 = -3.3807...; the site prints "-3%", we print
	 * one decimal of the same truncation.
	 */
	@Test
	public void theGreenHatFigureReadsAsTheGrandExchangeSitePrintsIt()
	{
		final MovementRow row = guideRow(658, "Green hat", 1086L, 1124L);

		assertEquals(Long.valueOf(-38L), row.deltaGp());
		assertEquals("-3.3%", MovementMath.formatPct(row.deltaPct(), row.deltaGp()));
	}

	/**
	 * The plugin's ONE zone-sensitive output: every date in the movement pipeline is a UTC calendar day (L9) and
	 * the header's "Bank as of HH:mm" is the deliberate exception. Two zones, two literals, one instant - which
	 * is the assertion an expectation built from {@code ZoneId.systemDefault()} cannot make: on a UTC runner it
	 * would agree with a regression to {@code ZoneOffset.UTC} and the whole suite would stay green while every
	 * user east or west of Greenwich read the wrong capture time.
	 */
	@Test
	public void aTimestampIsTheViewersOwnClockAndNotUtc()
	{
		final long millis = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
		final TimeZone original = TimeZone.getDefault();
		try
		{
			TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
			assertEquals("17:13", MovementMath.formatTime(millis));

			TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
			assertEquals("07:13", MovementMath.formatTime(millis));

			TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
			assertEquals("22:13", MovementMath.formatTime(millis));
		}
		finally
		{
			TimeZone.setDefault(original);
		}
	}

	@Test
	public void aTimestampIsTheLocalClock()
	{
		final long millis = 1_700_000_000_000L;
		final ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());

		assertEquals(String.format(Locale.ENGLISH, "%02d:%02d", local.getHour(), local.getMinute()),
			MovementMath.formatTime(millis));
		assertTrue(MovementMath.formatTime(millis).matches("\\d{2}:\\d{2}"));
	}

	@Test
	public void aTimestampOfNeverIsADash()
	{
		assertEquals("-", MovementMath.formatTime(0L));
		assertEquals("-", MovementMath.formatTime(-1L));
		assertEquals("-", MovementMath.DASH);
	}

	/**
	 * L7's day stamp: the guide table's own UTC date, printed as it stands. No zone conversion anywhere in it -
	 * the date came from {@code %LAST_UPDATE%} in UTC (L9), and re-reading it in a viewer's zone would name the
	 * wrong day for everyone west of Greenwich. This test therefore holds in every time zone the suite runs in.
	 */
	@Test
	public void aGuideDayIsTheUtcDateAsItStands()
	{
		assertEquals("07 Sep", MovementMath.formatDay(LocalDate.of(2026, 9, 7)));
		assertEquals("01 Jan", MovementMath.formatDay(LocalDate.of(2026, 1, 1)));
		assertEquals("31 Dec", MovementMath.formatDay(LocalDate.of(2025, 12, 31)));
		assertEquals("12 Mar", MovementMath.formatDay(LocalDate.of(2026, 3, 12)));
	}

	@Test
	public void noGuideDayIsADash()
	{
		assertEquals("-", MovementMath.formatDay(null));
	}

	/**
	 * Addendum N sections 3.1 and 4.4: the phrase under the bank total. Its whole reason for existing is the
	 * null - "since -" would claim a baseline and then fail to name it, so a card with no baseline shows the
	 * same single dash as every other empty figure and the problem row says why (N 3.6).
	 */
	@Test
	public void theBaselinePhraseNamesTheDayOrIsASingleDash()
	{
		assertEquals("since 08 Sep", MovementMath.formatSince(LocalDate.of(2026, 9, 8)));
		assertEquals("since 31 Dec", MovementMath.formatSince(LocalDate.of(2025, 12, 31)));
		assertEquals("-", MovementMath.formatSince(null));
		assertEquals(MovementMath.DASH, MovementMath.formatSince(null));

		// It is exactly "since " plus the day stamp L7 already froze - one date format in the plugin, not two.
		final LocalDate day = LocalDate.of(2026, 3, 12);
		assertEquals("since " + MovementMath.formatDay(day), MovementMath.formatSince(day));
	}
}
