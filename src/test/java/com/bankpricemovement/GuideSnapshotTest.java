package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

/**
 * {@link GuideSnapshot} after addendum L: the DAY a table's prices belong to (L7) and the one name-folding rule
 * every lookup goes through (L8).
 *
 * <p>The two tests that carry the addendum are {@link #aHumanEditCarriesThePreviousDaysPricesUnderALaterStamp()}
 * and {@link #aCaseRenamedKeyIsStillFound()}. The first is revision 15329323 (Riblet15, saved
 * 2026-09-03T06:46Z, {@code %LAST_UPDATE_F%} "02 September 2026 07:21:23") - the measured proof that the
 * revision timestamp is not the data day (L-E). The second is the wiki's August 2026 case rename of 81 keys
 * ("3rd age amulet" to "3rd Age amulet"), which cost a byte-exact lookup three of the user's bank items on every
 * window of 30 days or more (L-F).
 *
 * <p>Fixture figures are the live ones from revision 15333448 (Green hat 1124, Barrel 835, Abyssal whip 807253,
 * 3rd Age axe 2,147,483,646) - {@code docs/research/bank-price-movement-calibration-2026-09-08.md}.
 */
public class GuideSnapshotTest
{
	/** 2026-09-07T19:55:12Z - when revision 15333448 was SAVED. */
	private static final long EDIT_SECONDS = 1_788_810_912L;
	/** 2026-09-07T19:45:22Z - the {@code %LAST_UPDATE%} inside that revision's body. */
	private static final long DATA_SECONDS = 1_788_810_322L;
	private static final long REV_ID = 15_333_448L;
	private static final long FETCHED_MILLIS = 1_788_909_600_123L;

	private static GuideSnapshot snapshot(final Map<String, Long> prices)
	{
		return new GuideSnapshot(REV_ID, EDIT_SECONDS, DATA_SECONDS, FETCHED_MILLIS, prices);
	}

	private static Map<String, Long> table()
	{
		final Map<String, Long> prices = new LinkedHashMap<>();
		prices.put("Green hat", 1124L);
		prices.put("Barrel", 835L);
		prices.put("Abyssal whip", 807_253L);
		return prices;
	}

	// ---------------------------------------------------------------- L7: the day marker

	@Test
	public void aSnapshotCarriesItsRevisionItsSaveTimeItsDayMarkerAndItsFetchStamp()
	{
		final GuideSnapshot table = snapshot(table());

		assertEquals(REV_ID, table.revId());
		assertEquals("the save time is cache identity only since addendum L", EDIT_SECONDS, table.revisionSeconds());
		assertEquals(DATA_SECONDS, table.dataSeconds());
		assertEquals(FETCHED_MILLIS, table.fetchedAtMillis());
		assertEquals(LocalDate.of(2026, 9, 7), table.dataDay());
		assertEquals(3, table.size());
		assertFalse(table.isEmpty());
	}

	/**
	 * The measurement addendum L is built on. Revision 15329323 was saved on 2026-09-03 and holds the table
	 * Jagex stamped on 2026-09-02: a status line reading the SAVE time would label those prices a day too late,
	 * and the L5 day check would have nothing to catch it with.
	 */
	@Test
	public void aHumanEditCarriesThePreviousDaysPricesUnderALaterStamp()
	{
		// 2026-09-03T06:46:00Z saved; %LAST_UPDATE% 2026-09-02T07:21:23Z.
		final GuideSnapshot stale = new GuideSnapshot(15_329_323L, 1_788_417_960L, 1_788_333_683L,
			FETCHED_MILLIS, table());

		assertEquals(LocalDate.of(2026, 9, 3), RevisionRef.dayOf(stale.revisionSeconds()));
		assertEquals("the day the prices are from, which is the one the row must be labelled with",
			LocalDate.of(2026, 9, 2), stale.dataDay());
	}

	@Test
	public void aTableWithNoDayMarkerHasNoDay()
	{
		assertNull(new GuideSnapshot(REV_ID, EDIT_SECONDS, 0L, FETCHED_MILLIS, table()).dataDay());
		assertNull(GuideSnapshot.EMPTY.dataDay());
	}

	/** Midnight UTC is the day boundary wherever the client runs (L9) - this box is four hours behind it. */
	@Test
	public void theDayIsUtcAndNotTheMachineZone()
	{
		assertEquals(LocalDate.of(2026, 9, 9),
			new GuideSnapshot(1L, 1L, 1_788_912_000L, 1L, table()).dataDay());
		assertEquals(LocalDate.of(2026, 9, 8),
			new GuideSnapshot(1L, 1L, 1_788_911_999L, 1L, table()).dataDay());
	}

	@Test
	public void emptyIsTheNoTableYetSnapshot()
	{
		assertTrue(GuideSnapshot.EMPTY.isEmpty());
		assertEquals(0, GuideSnapshot.EMPTY.size());
		assertEquals(0L, GuideSnapshot.EMPTY.revId());
		assertEquals(0L, GuideSnapshot.EMPTY.revisionSeconds());
		assertEquals(0L, GuideSnapshot.EMPTY.dataSeconds());
		assertEquals(0L, GuideSnapshot.EMPTY.fetchedAtMillis());
		assertNull(GuideSnapshot.EMPTY.get("Green hat"));
	}

	// ---------------------------------------------------------------- L8: one name-folding rule

	@Test
	public void aPriceIsFoundByTheNameTheWikiSpellsItWith()
	{
		final GuideSnapshot table = snapshot(table());

		assertEquals(Long.valueOf(1124L), table.get("Green hat"));
		assertEquals(Long.valueOf(835L), table.get("Barrel"));
		assertEquals(Long.valueOf(807_253L), table.get("Abyssal whip"));
		assertNull("a name the table does not list is null, not zero", table.get("Twisted bow"));
		assertNull(table.get(null));
	}

	/**
	 * The wiki case-renamed 81 keys on 2026-08-12..20 (L-F). A 30d or 180d baseline therefore spells three of
	 * the user's bank items the OLD way while the mapping spells them the new way; folding both sides is what
	 * recovers them.
	 */
	@Test
	public void aCaseRenamedKeyIsStillFound()
	{
		final Map<String, Long> old = new HashMap<>();
		old.put("3rd age amulet", 2_100_000L);

		final GuideSnapshot august = snapshot(old);

		assertEquals("the mapping's spelling today", Long.valueOf(2_100_000L), august.get("3rd Age amulet"));
		assertEquals("and the revision's own spelling", Long.valueOf(2_100_000L), august.get("3rd age amulet"));
		assertEquals(Long.valueOf(2_100_000L), august.get("3RD AGE AMULET"));
	}

	@Test
	public void theFoldNormalisesSpacingAndCaseAndNothingElse()
	{
		assertEquals("green hat", GuideSnapshot.key("Green hat"));
		assertEquals("leading, trailing and doubled spaces are invisible in a wiki edit box",
			"green hat", GuideSnapshot.key("  Green   hat "));
		assertEquals("a tab is whitespace as well", "green hat", GuideSnapshot.key("Green\that"));
		assertEquals("and so is the non-breaking space in this fixture, which \\s does NOT match",
			"green hat", GuideSnapshot.key("Green\u00A0hat"));
		assertNull(GuideSnapshot.key(null));
		assertEquals("", GuideSnapshot.key("   "));
	}

	/**
	 * Qualifiers and punctuation are NEVER stripped (L8 d). "Karambwan vessel (baited)" and "Karambwan vessel"
	 * are different items with prices a median 86 % apart, and collapsing them is precisely the defect the
	 * composition-name fallback had (L-F).
	 */
	@Test
	public void aQualifierIsPartOfTheNameAndIsNeverStripped()
	{
		final Map<String, Long> prices = new HashMap<>();
		prices.put("Karambwan vessel", 12L);
		prices.put("Karambwan vessel (baited)", 3388L);

		final GuideSnapshot table = snapshot(prices);

		assertEquals(Long.valueOf(3388L), table.get("Karambwan vessel (baited)"));
		assertEquals(Long.valueOf(12L), table.get("Karambwan vessel"));
		assertEquals("two names, two entries", 2, table.size());
	}

	/**
	 * The Turkish locale is the classic trap: {@code "I".toLowerCase()} there is a DOTLESS i, so a fold using
	 * the default locale would key "Ice gloves" onto a string no table holds and the item would silently lose
	 * its price - on that user's machine only. {@link Locale#ROOT} is what makes the fold the same everywhere.
	 */
	@Test
	public void theFoldIsLocaleIndependent()
	{
		final Locale original = Locale.getDefault();
		try
		{
			Locale.setDefault(new Locale("tr", "TR"));

			assertEquals("ice gloves", GuideSnapshot.key("Ice gloves"));
			assertEquals(Long.valueOf(180L),
				snapshot(one("Ice gloves", 180L)).get("ICE GLOVES"));
		}
		finally
		{
			Locale.setDefault(original);
		}
	}

	// ---------------------------------------------------------------- L8 (c): the unpriced sentinel

	/**
	 * Three table values were exactly 2,147,483,646 on the sampled revisions - "3rd Age axe", "3rd Age druidic
	 * robe top", "3rd Age pickaxe" - and they mean "no price", not "two billion gp". Left in, the 3rd Age axe in
	 * the user's bank would read as the single largest holding they own and dominate every sort.
	 */
	@Test
	public void theUnpricedSentinelIsNotAPrice()
	{
		final Map<String, Long> prices = table();
		prices.put("3rd Age axe", GuideSnapshot.NO_PRICE);
		prices.put("3rd Age pickaxe", GuideSnapshot.NO_PRICE + 1L);
		prices.put("Twisted bow", GuideSnapshot.NO_PRICE - 1L);

		final GuideSnapshot table = snapshot(prices);

		assertEquals(2_147_483_646L, GuideSnapshot.NO_PRICE);
		assertNull(table.get("3rd Age axe"));
		assertNull("anything at or above the sentinel is unpriced", table.get("3rd Age pickaxe"));
		assertEquals("one gp below it is a real, if absurd, price",
			Long.valueOf(2_147_483_645L), table.get("Twisted bow"));
		assertEquals("three real prices plus the Twisted bow", 4, table.size());
	}

	// ---------------------------------------------------------------- immutability and value semantics

	@Test
	public void theTableIsCopiedAtConstructionAndHandedOutUnmodifiable()
	{
		final Map<String, Long> source = table();
		final GuideSnapshot table = snapshot(source);

		source.put("Twisted bow", 1L);
		source.remove("Barrel");

		assertEquals("the snapshot does not follow the caller's map", 3, table.size());
		assertNull(table.get("Twisted bow"));
		assertEquals(Long.valueOf(835L), table.get("Barrel"));

		try
		{
			table.pricesByName().put("Twisted bow", 1L);
			fail("pricesByName() must be unmodifiable - it is read while rows are computed on another thread");
		}
		catch (final UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	@Test
	public void theStoredKeysAreTheFoldedOnes()
	{
		assertEquals("green hat", snapshot(one("Green Hat", 1124L)).pricesByName().keySet().iterator().next());
	}

	@Test
	public void damagedEntriesAreDroppedRatherThanStored()
	{
		final Map<String, Long> source = new HashMap<>();
		source.put("Green hat", 1124L);
		source.put(null, 5L);
		source.put("Barrel", null);
		source.put("   ", 7L);

		final GuideSnapshot table = snapshot(source);

		assertEquals(1, table.size());
		assertEquals(Long.valueOf(1124L), table.get("Green hat"));
	}

	@Test
	public void aNullSourceMapIsAnEmptyTable()
	{
		final GuideSnapshot table = new GuideSnapshot(REV_ID, EDIT_SECONDS, DATA_SECONDS, FETCHED_MILLIS, null);

		assertTrue(table.isEmpty());
		assertEquals(DATA_SECONDS, table.dataSeconds());
	}

	@Test
	public void snapshotsCompareByValue()
	{
		assertEquals(snapshot(table()), snapshot(table()));
		assertEquals(snapshot(table()).hashCode(), snapshot(table()).hashCode());
		assertNotEquals("two revisions of the same numbers are different snapshots",
			snapshot(table()), new GuideSnapshot(REV_ID + 1L, EDIT_SECONDS, DATA_SECONDS, FETCHED_MILLIS, table()));
		assertNotEquals("and so are two DAYS of the same numbers",
			snapshot(table()), new GuideSnapshot(REV_ID, EDIT_SECONDS, DATA_SECONDS + 86_400L, FETCHED_MILLIS, table()));
		assertNotEquals(snapshot(table()), GuideSnapshot.EMPTY);
		assertNotEquals(snapshot(table()), null);
	}

	@Test
	public void toStringNamesTheRevisionAndTheDay()
	{
		final String text = snapshot(table()).toString();

		assertTrue(text, text.contains("15333448"));
		assertTrue(text, text.contains("2026-09-07"));
		assertTrue(text, text.contains("names=3"));
	}

	// ---------------------------------------------------------------- fixtures

	/** A one-entry table; a mutable map rather than {@code singletonMap} so the constructor really copies it. */
	private static Map<String, Long> one(final String name, final long price)
	{
		final Map<String, Long> prices = new HashMap<>();
		prices.put(name, price);
		return prices;
	}
}
