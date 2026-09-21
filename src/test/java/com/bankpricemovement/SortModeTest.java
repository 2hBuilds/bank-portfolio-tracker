package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import org.junit.Test;

/**
 * {@link SortMode} (contract C2, rewritten by addendum W line W1): the FOUR columns the user listed by number -
 * "1) Percent change 2) GP change 3) Item price 4) Stack price" - and every verb that reaches them from the dev
 * bridge ({@code bpm sort=stack}), the pre-W labels included.
 *
 * <p>Addendum AO line AO1 changed what ONE of those columns MEANS without touching a word of its surface:
 * {@link SortMode#GP_MOVE} compares the whole stack's move now that {@code holdingOnRows} is deleted. The label,
 * the constant name and every alias are the ones they always were, so the vocabulary tests below say nothing new
 * - {@link #theGpColumnRanksTheStacksMoveAndNotOneItems()} is the one that holds the new rule, by example.
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

	/** Every short verb that worked before addendum W still works: old scripts are not rewritten by a rename. */
	@Test
	public void parseTakesTheOldShortVerbs()
	{
		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("pct"));
		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("percent"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("gp"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("amount"));
		assertSame(SortMode.UNIT_PRICE, SortMode.parse("price"));
		assertSame(SortMode.UNIT_PRICE, SortMode.parse("unit"));
	}

	/** The pre-W labels are verbs now ({@code bpm sort="% move"} still names the percentage column). */
	@Test
	public void parseStillTakesThePreAddendumWLabels()
	{
		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("% move"));
		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("% Move"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("gp move"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("  GP Move "));
	}

	/** W1's new column answers to the three words the addendum names. */
	@Test
	public void parseTakesTheNewStackVerbs()
	{
		assertSame(SortMode.STACK_VALUE, SortMode.parse("stack"));
		assertSame(SortMode.STACK_VALUE, SortMode.parse("stacks"));
		assertSame(SortMode.STACK_VALUE, SortMode.parse("holding"));
		assertSame(SortMode.STACK_VALUE, SortMode.parse(" HOLDING "));
	}

	@Test
	public void parseTakesNamesLabelsAndCasing()
	{
		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("PERCENT_MOVE"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("gp_move"));
		assertSame(SortMode.UNIT_PRICE, SortMode.parse("Unit_Price"));
		assertSame(SortMode.STACK_VALUE, SortMode.parse("stack_value"));

		assertSame(SortMode.PERCENT_MOVE, SortMode.parse("Percent change"));
		assertSame(SortMode.GP_MOVE, SortMode.parse("  GP CHANGE "));
		assertSame(SortMode.UNIT_PRICE, SortMode.parse("item price"));
		assertSame(SortMode.STACK_VALUE, SortMode.parse("Stack Price"));
	}

	@Test
	public void parseAnswersNullRatherThanGuessing()
	{
		assertNull(SortMode.parse(null));
		assertNull(SortMode.parse(""));
		assertNull(SortMode.parse("  "));
		assertNull(SortMode.parse("percentage"));
		assertNull(SortMode.parse("name"));
		assertNull(SortMode.parse("%"));
		assertNull(SortMode.parse("value"));
		assertNull(SortMode.parse("stack price change"));
	}

	@Test
	public void parseRoundTripsEveryLabelAndName()
	{
		for (final SortMode mode : SortMode.values())
		{
			assertSame(mode, SortMode.parse(mode.label()));
			assertSame(mode, SortMode.parse(mode.name()));
			assertSame(mode, SortMode.parse(mode.toString()));
		}
	}

	/**
	 * The whole vocabulary in one table, with no word in it twice. {@link SortMode#parse} answers the FIRST
	 * constant that claims a word, so a word shared by two columns would silently make one of them unreachable
	 * from the bridge rather than fail anywhere - "price" and "stack price" are one letter of sloppiness apart.
	 */
	@Test
	public void everyVerbNamesExactlyOneColumn()
	{
		final Map<String, SortMode> vocabulary = new LinkedHashMap<>();
		vocabulary.put("percent change", SortMode.PERCENT_MOVE);
		vocabulary.put("percent_move", SortMode.PERCENT_MOVE);
		vocabulary.put("pct", SortMode.PERCENT_MOVE);
		vocabulary.put("percent", SortMode.PERCENT_MOVE);
		vocabulary.put("% move", SortMode.PERCENT_MOVE);
		vocabulary.put("gp change", SortMode.GP_MOVE);
		vocabulary.put("gp_move", SortMode.GP_MOVE);
		vocabulary.put("gp", SortMode.GP_MOVE);
		vocabulary.put("amount", SortMode.GP_MOVE);
		vocabulary.put("gp move", SortMode.GP_MOVE);
		vocabulary.put("item price", SortMode.UNIT_PRICE);
		vocabulary.put("unit_price", SortMode.UNIT_PRICE);
		vocabulary.put("price", SortMode.UNIT_PRICE);
		vocabulary.put("unit", SortMode.UNIT_PRICE);
		vocabulary.put("stack price", SortMode.STACK_VALUE);
		vocabulary.put("stack_value", SortMode.STACK_VALUE);
		vocabulary.put("stack", SortMode.STACK_VALUE);
		vocabulary.put("stacks", SortMode.STACK_VALUE);
		vocabulary.put("holding", SortMode.STACK_VALUE);

		assertEquals("a word listed twice would have been swallowed by the map", 19, vocabulary.size());

		for (final Map.Entry<String, SortMode> word : vocabulary.entrySet())
		{
			assertSame(word.getKey(), word.getValue(), SortMode.parse(word.getKey()));
			assertSame(word.getKey() + " shouted", word.getValue(),
				SortMode.parse(word.getKey().toUpperCase(Locale.ENGLISH)));
		}
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
