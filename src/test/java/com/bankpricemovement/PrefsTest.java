package com.bankpricemovement;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BankPriceMovementPanel.Prefs} - the seam between the sidebar and where its choices are kept (contract
 * C28; the hero pair is addendum O line O2, the options pair addendum Q line Q3, the presets pair addendum Z
 * line Z1 and the fold pair addendum AA line AA1,
 * {@code docs/bank-price-movement-addendum-AA-2026-09-13.md}).
 *
 * <p>Only the FILTER pair is abstract. Every pair added since has a default, so a caller with nothing to
 * remember - the headless renderer, a throwaway test seam - need not care, and the panel draws exactly what it
 * drew before that pair existed. This file pins that: what a bare implementation answers, and that the writes it
 * does not want are no-ops rather than errors.
 *
 * <p>The plugin's own implementation over {@code ConfigManager} is a different thing and is pinned in
 * {@code BankPriceMovementWiringTest}; what a null from any of the three READS as is the panel's business and is
 * pinned in {@code BankPriceMovementPanelTest}.
 */
public class PrefsTest
{
	/** The least a {@code Prefs} can be: the filter pair, and not one line more. */
	private static final class Bare implements BankPriceMovementPanel.Prefs
	{
		final List<RowFilter> saved = new ArrayList<>();

		@Override
		public RowFilter load()
		{
			return null;
		}

		@Override
		public void save(RowFilter filter)
		{
			saved.add(filter);
		}
	}

	@Test
	public void aPrefsThatRemembersOnlyTheFilterAnswersNullToEverythingElse()
	{
		final Bare prefs = new Bare();
		assertNull("a fresh install has no filter", prefs.load());
		assertNull("O2: no stored hero switches", prefs.loadHero());
		assertNull("Q3/T1/Y1: no stored view switches", prefs.loadOptions());
		assertNull("Z1: no stored price presets", prefs.loadPresets());
		assertNull("AA1: no stored fold state", prefs.loadFoldOpen());
	}

	/**
	 * AA1: the fold pair answers a {@code Boolean} and not a {@code boolean}, because "nothing stored" and
	 * "stored closed" are different answers and only the first may be read as the shipped default (open). A
	 * primitive here would make every bare implementation - the renderer, a throwaway seam - claim the reader had
	 * chosen to shut the fold.
	 */
	@Test
	public void theFoldPairAnswersNullRatherThanFalseWhenNothingIsStored()
	{
		final Bare prefs = new Bare();
		assertNull(prefs.loadFoldOpen());
		assertNotEquals("a stored FALSE would be a choice, and this is not one", Boolean.FALSE, prefs.loadFoldOpen());
	}

	@Test
	public void theWritesItDoesNotWantAreNoOps()
	{
		final Bare prefs = new Bare();
		prefs.saveHero(HeroVisibility.NONE);
		prefs.saveOptions(ViewOptions.DEFAULT);
		prefs.savePresets(BandPresets.DEFAULT);
		prefs.saveFoldOpen(false);
		prefs.saveFoldOpen(true);
		assertTrue("the defaults write nothing anywhere", prefs.saved.isEmpty());

		prefs.save(RowFilter.DEFAULT);
		assertEquals("...and the one pair that is abstract is the implementation's own", 1, prefs.saved.size());
	}
}
