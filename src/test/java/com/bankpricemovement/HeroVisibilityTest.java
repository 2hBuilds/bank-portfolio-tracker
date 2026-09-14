package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link HeroVisibility} (addendum O line O2): the immutable carrier of the bank value card's three show/hide
 * switches, and the pure half of the {@code hero=} dev verb (O5).
 */
public class HeroVisibilityTest
{
	/** The default a fresh profile gets, and what the card drew before addendum O existed. */
	@Test
	public void allShowsEveryFigureAndNoneShowsNothing()
	{
		assertTrue(HeroVisibility.ALL.value());
		assertTrue(HeroVisibility.ALL.gp());
		assertTrue(HeroVisibility.ALL.pct());
		assertTrue(HeroVisibility.ALL.moveLine());
		assertTrue(HeroVisibility.ALL.any());

		assertFalse(HeroVisibility.NONE.value());
		assertFalse(HeroVisibility.NONE.gp());
		assertFalse(HeroVisibility.NONE.pct());
		assertFalse("with both halves off there is no move line to draw (O3)", HeroVisibility.NONE.moveLine());
		assertFalse(HeroVisibility.NONE.any());
	}

	/** All eight combinations exist, are distinct, and answer exactly the three booleans they were built from. */
	@Test
	public void allEightCombinationsAreDistinctAndReadBackWhatTheyWereGiven()
	{
		final Set<HeroVisibility> seen = new HashSet<>();
		for (int bits = 0; bits < 8; bits++)
		{
			final boolean value = (bits & 4) != 0;
			final boolean gp = (bits & 2) != 0;
			final boolean pct = (bits & 1) != 0;
			final HeroVisibility v = HeroVisibility.of(value, gp, pct);
			assertEquals("value " + bits, value, v.value());
			assertEquals("gp " + bits, gp, v.gp());
			assertEquals("pct " + bits, pct, v.pct());
			// O3: the move line survives while EITHER of its two figures does.
			assertEquals("moveLine " + bits, gp || pct, v.moveLine());
			assertEquals("any " + bits, value || gp || pct, v.any());
			assertTrue("combination " + bits + " is not distinct", seen.add(v));
			// Value semantics, so the panel can compare an incoming visibility with the one it is drawing.
			assertEquals(v, HeroVisibility.of(value, gp, pct));
			assertEquals(v.hashCode(), HeroVisibility.of(value, gp, pct).hashCode());
		}
		assertEquals(8, seen.size());
		assertEquals(HeroVisibility.ALL, HeroVisibility.of(true, true, true));
		assertEquals(HeroVisibility.NONE, HeroVisibility.of(false, false, false));
		assertNotEquals(HeroVisibility.ALL, HeroVisibility.NONE);
		assertNotEquals("a visibility is not a boolean", HeroVisibility.ALL, Boolean.TRUE);
	}

	/** The three switches are independent: changing one leaves the other two exactly where they were. */
	@Test
	public void theWithersChangeOneSwitchAndNothingElse()
	{
		final HeroVisibility hidden = HeroVisibility.ALL.withValue(false);
		assertEquals(HeroVisibility.of(false, true, true), hidden);
		assertEquals(HeroVisibility.of(false, false, true), hidden.withGp(false));
		assertEquals(HeroVisibility.of(false, false, false), hidden.withGp(false).withPct(false));
		assertEquals(HeroVisibility.NONE, hidden.withGp(false).withPct(false));

		// The original is untouched - the value is immutable, and the panel may hold on to one.
		assertEquals(HeroVisibility.ALL, HeroVisibility.of(true, true, true));
		// Setting a switch to what it already says answers the SAME instance: a no-op re-render is cheap to spot.
		assertSame(HeroVisibility.ALL, HeroVisibility.ALL.withValue(true));
		assertSame(HeroVisibility.ALL, HeroVisibility.ALL.withGp(true));
		assertSame(HeroVisibility.ALL, HeroVisibility.ALL.withPct(true));
	}

	/**
	 * The three switches as one ordered map: the shape BOTH JSON answers about them are built from - the dev
	 * bridge's {@code state.hero} and the panel's own {@code describe()} echo of it - so the two cannot spell
	 * one object two ways. The order is the order those answers print.
	 */
	@Test
	public void asMapIsTheThreeSwitchesInTheOrderTheJsonPrintsThem()
	{
		final Map<String, Boolean> all = HeroVisibility.ALL.asMap();
		assertEquals(Arrays.asList("value", "gp", "pct"), new ArrayList<>(all.keySet()));
		assertEquals(Arrays.asList(true, true, true), new ArrayList<>(all.values()));
		assertEquals(Arrays.asList(false, false, false), new ArrayList<>(HeroVisibility.NONE.asMap().values()));

		final HeroVisibility mixed = HeroVisibility.of(true, false, true);
		assertEquals(Boolean.TRUE, mixed.asMap().get("value"));
		assertEquals(Boolean.FALSE, mixed.asMap().get("gp"));
		assertEquals(Boolean.TRUE, mixed.asMap().get("pct"));

		// A fresh map every time: a caller may keep it, add to it or hand it to Gson.
		final Map<String, Boolean> mine = mixed.asMap();
		mine.put("value", false);
		assertEquals("the value is immutable whatever a caller does with its map", Boolean.TRUE,
			mixed.asMap().get("value"));
	}

	/**
	 * O5: a FIELD word toggles its figure, so {@code hero=value} twice is where it started, while {@code all}
	 * and {@code none} SET all three whatever they were.
	 */
	@Test
	public void aFieldVerbTogglesAndAllOrNoneSets()
	{
		assertEquals(HeroVisibility.of(false, true, true), HeroVisibility.ALL.applyVerb("value"));
		assertEquals(HeroVisibility.ALL, HeroVisibility.ALL.applyVerb("value").applyVerb("value"));
		assertEquals(HeroVisibility.of(true, false, true), HeroVisibility.ALL.applyVerb("gp"));
		assertEquals(HeroVisibility.of(true, true, false), HeroVisibility.ALL.applyVerb("pct"));

		assertEquals(HeroVisibility.ALL, HeroVisibility.NONE.applyVerb("all"));
		assertEquals(HeroVisibility.ALL, HeroVisibility.ALL.applyVerb("all"));
		assertEquals(HeroVisibility.NONE, HeroVisibility.ALL.applyVerb("none"));
		assertEquals(HeroVisibility.NONE, HeroVisibility.NONE.applyVerb("none"));

		// A toggle off NONE turns the one figure on, which is the way back from an empty card.
		assertEquals(HeroVisibility.of(true, false, false), HeroVisibility.NONE.applyVerb("value"));
		assertEquals(HeroVisibility.of(false, true, false), HeroVisibility.NONE.applyVerb("gp"));
		assertEquals(HeroVisibility.of(false, false, true), HeroVisibility.NONE.applyVerb("pct"));
	}

	/** The verb is typed by hand into a URL query, so it is trimmed, case-folded and generous about synonyms. */
	@Test
	public void theVerbTakesTheObviousSynonymsAndRefusesEverythingElse()
	{
		for (String word : new String[]{"value", "VALUE", " Value ", "total", "bankvalue", "bank value", "v"})
		{
			assertEquals(word, HeroVisibility.of(false, true, true), HeroVisibility.ALL.applyVerb(word));
		}
		for (String word : new String[]{"gp", "GP", "move", "movegp", "amount", "pnl"})
		{
			assertEquals(word, HeroVisibility.of(true, false, true), HeroVisibility.ALL.applyVerb(word));
		}
		for (String word : new String[]{"pct", "percent", "Percentage", "movepct", "%"})
		{
			assertEquals(word, HeroVisibility.of(true, true, false), HeroVisibility.ALL.applyVerb(word));
		}
		for (String word : new String[]{"all", "on", "SHOW"})
		{
			assertEquals(word, HeroVisibility.ALL, HeroVisibility.NONE.applyVerb(word));
		}
		for (String word : new String[]{"none", "off", "Hide"})
		{
			assertEquals(word, HeroVisibility.NONE, HeroVisibility.ALL.applyVerb(word));
		}

		// Anything else answers null: the caller refuses and leaves the card exactly as it was, rather than
		// guessing which figure was meant.
		for (String word : new String[]{"", "   ", "sideways", "bank", "money", "true", "1"})
		{
			assertNull("'" + word + "'", HeroVisibility.ALL.applyVerb(word));
		}
		assertNull("a null verb is an answer too", HeroVisibility.ALL.applyVerb(null));
	}

	/** The refusal names what to type instead, so an operator never has to read the source to recover. */
	@Test
	public void theVerbListNamesTheFiveWords()
	{
		final String verbs = HeroVisibility.verbs();
		assertTrue(verbs, verbs.contains("value"));
		assertTrue(verbs, verbs.contains("gp"));
		assertTrue(verbs, verbs.contains("pct"));
		assertTrue(verbs, verbs.contains("all"));
		assertTrue(verbs, verbs.contains("none"));
	}

	/** {@code toString} is a debugging line, and it says which of the three is which. */
	@Test
	public void toStringNamesTheThreeSwitches()
	{
		final String text = HeroVisibility.of(true, false, true).toString();
		assertTrue(text, text.contains("value=true"));
		assertTrue(text, text.contains("gp=false"));
		assertTrue(text, text.contains("pct=true"));
	}
}
