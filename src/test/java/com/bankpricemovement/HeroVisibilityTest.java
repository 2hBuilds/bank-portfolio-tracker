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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link HeroVisibility} (addendum O line O2): the immutable carrier of the bank value card's three show/hide
 * switches. The pure half of the {@code hero=} dev verb (O5) is the bridge's own parser, pinned by the
 * developer-bridge tests, which are local-only and not part of the shipped source.
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
	 * bridge's {@code state.hero} and the panel's own {@code PanelState.describe} echo of it - so the two cannot spell
	 * one object two ways. The order is the order those answers print.
	 */
	@Test
	public void asMapIsTheThreeSwitchesInTheOrderTheJsonPrintsThem()
	{
		final Map<String, Boolean> all = PanelState.asMap(HeroVisibility.ALL);
		assertEquals(Arrays.asList("value", "gp", "pct"), new ArrayList<>(all.keySet()));
		assertEquals(Arrays.asList(true, true, true), new ArrayList<>(all.values()));
		assertEquals(Arrays.asList(false, false, false), new ArrayList<>(PanelState.asMap(HeroVisibility.NONE).values()));

		final HeroVisibility mixed = HeroVisibility.of(true, false, true);
		assertEquals(Boolean.TRUE, PanelState.asMap(mixed).get("value"));
		assertEquals(Boolean.FALSE, PanelState.asMap(mixed).get("gp"));
		assertEquals(Boolean.TRUE, PanelState.asMap(mixed).get("pct"));

		// A fresh map every time: a caller may keep it, add to it or hand it to Gson.
		final Map<String, Boolean> mine = PanelState.asMap(mixed);
		mine.put("value", false);
		assertEquals("the value is immutable whatever a caller does with its map", Boolean.TRUE,
			PanelState.asMap(mixed).get("value"));
	}

	/** The three switches are told apart: which is on and which is off. */
	@Test
	public void theThreeSwitchesAreToldApart()
	{
		final HeroVisibility hero = HeroVisibility.of(true, false, true);
		assertTrue(hero.value());
		assertFalse(hero.gp());
		assertTrue(hero.pct());
	}
}
