package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/**
 * {@link SortMode} (contract C2, rewritten by addendum W line W1): the FOUR columns the user listed by number -
 * "1) Percent change 2) GP change 3) Item price 4) Stack price". The verbs that reach them from the dev bridge
 * ({@code bpm sort=stack}), the pre-W labels included, are read by the developer bridge's own parser, which is
 * local-only and pinned by its own tests.
 *
 * <p>Addendum AO line AO1 changed what ONE of those columns MEANS without touching a word of its surface:
 * {@link SortMode#GP_MOVE} compares the whole stack's move now that {@code holdingOnRows} is deleted. The label
 * and the constant name are the ones they always were - {@link #theGpColumnRanksTheStacksMoveAndNotOneItems()} is
 * the test that holds the new rule, by example.
 */
public class SortModeTest
{
	/** W1: four columns, and these exact words on them - lowercase gp, as the user asked. */
	@Test
	public void theFourColumnsCarryTheirButtonText()
	{
		assertEquals("Percent change", SortMode.PERCENT_MOVE.label());
		assertEquals("gp change", SortMode.GP_MOVE.label());
		assertEquals("Item price", SortMode.UNIT_PRICE.label());
		assertEquals("Stack price", SortMode.STACK_VALUE.label());
		assertEquals("four columns, one per question the user named", 4, SortMode.values().length);
	}

	/** W1: the menu is drawn in declaration order, so the order IS the user's numbered list. */
	@Test
	public void theColumnsAreDeclaredInTheUsersOrder()
	{
		assertEquals(
			Arrays.asList(SortMode.PERCENT_MOVE, SortMode.GP_MOVE, SortMode.UNIT_PRICE, SortMode.STACK_VALUE),
			Arrays.asList(SortMode.values()));
	}

	/**
	 * AO1, by example rather than by restating the code. {@link SortMode#GP_MOVE}'s javadoc claims the column
	 * compares the WHOLE STACK's move under every switch; these two rows are what that claim costs.
	 *
	 * <p>A thousand feathers that each gained 5 gp are 5,000 gp of bank; one whip that gained 1,000 gp is 1,000.
	 * Biggest first, the feathers lead - and the message on that assertion is the whole of AO1, because until the
	 * {@code holdingOnRows} key was deleted the column's DEFAULT reading was the per-item one, under which the
	 * whip's 1,000 beat the feather's 5 and this same list came out the other way round.
	 */
	@Test
	public void theGpColumnRanksTheStacksMoveAndNotOneItems()
	{
		final MovementRow feathers = movedRow(314, "Feather", 1_000, 10L, 5L);
		final MovementRow whip = movedRow(4151, "Abyssal whip", 1, 2_000_000L, 1_000L);

		final List<MovementRow> biggestFirst = new ArrayList<>(Arrays.asList(whip, feathers));
		biggestFirst.sort(MovementMath.comparator(SortMode.GP_MOVE, true));
		assertEquals("5,000 gp of feathers outrank the whip's 1,000, though ONE feather moved only 5",
			Arrays.asList("Feather", "Abyssal whip"), names(biggestFirst));

		final List<MovementRow> smallestFirst = new ArrayList<>(Arrays.asList(feathers, whip));
		smallestFirst.sort(MovementMath.comparator(SortMode.GP_MOVE, false));
		assertEquals("and the arrow the other way turns the same two rows round",
			Arrays.asList("Abyssal whip", "Feather"), names(smallestFirst));
	}

	/**
	 * The config combo box reads {@code toString()}; the stored value is still the constant name
	 * ({@code ConfigManager.java:1279-1281}), which W1 froze - adding {@code STACK_VALUE} was allowed, renaming
	 * any of the other three was not.
	 */
	@Test
	public void toStringIsTheLabelWhileTheNameIsUnchanged()
	{
		assertEquals("Percent change", SortMode.PERCENT_MOVE.toString());
		assertEquals("Stack price", SortMode.STACK_VALUE.toString());

		assertEquals("PERCENT_MOVE", SortMode.PERCENT_MOVE.name());
		assertEquals("GP_MOVE", SortMode.GP_MOVE.name());
		assertEquals("UNIT_PRICE", SortMode.UNIT_PRICE.name());
		assertEquals("STACK_VALUE", SortMode.STACK_VALUE.name());
		assertSame(SortMode.PERCENT_MOVE, SortMode.valueOf("PERCENT_MOVE"));
	}

	/**
	 * One priced row that moved, with the two numbers the gp column weighs against each other spelled out: what
	 * ONE of them gained ({@code deltaGp}) and how many are held. Built by hand rather than through
	 * {@link MovementMath#row} so the example reads as the arithmetic it is testing.
	 */
	private static MovementRow movedRow(final int id, final String name, final int quantity, final long unit,
		final long deltaGp)
	{
		final long then = unit - deltaGp;

		return new MovementRow(id, name, quantity, quantity > 1, unit, then, deltaGp,
			100.0 * deltaGp / then, unit * quantity, MovementRow.PriceSource.GUIDE);
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
}
