package com.bankpricemovement;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link BandPresets} (addendum Z line Z1): the immutable carrier of the fold's three quick bands, the pure half
 * of the gear menu's three boxes, of the {@code bandPresets} config line and of the {@code presets=} dev verb.
 *
 * <p>Four groups: what the DEFAULT is (the trio the fold has drawn since addendum N, so a profile that never
 * touches the new line sees today's chips byte for byte), what {@link BandPresets#parse(String)} reads, the three
 * refusals - not three values, not positive, not distinct - and what the value writes back out.
 */
public class BandPresetsTest
{
	/** The trio {@code PRESET_MINS} held before addendum Z made it a config line. */
	@Test
	public void theDefaultIsTheTrioTheFoldHasAlwaysDrawn()
	{
		assertArrayEquals(new long[]{100_000L, 1_000_000L, 10_000_000L}, BandPresets.DEFAULT.mins());
		assertEquals("100k, 1m, 10m", BandPresets.DEFAULT.format());
		assertEquals("the config line's default parses back to the default", BandPresets.DEFAULT,
			BandPresets.parse("100k, 1m, 10m"));
	}

	/**
	 * Z1 pins these three strings: they are what the fold's chips have read since addendum N, and a default
	 * profile must draw the W pictures byte for byte.
	 */
	@Test
	public void theDefaultLabelsAreExactlyTheChipsTheFoldDraws()
	{
		assertArrayEquals(new String[]{"100k+", "1m+", "10m+"}, BandPresets.DEFAULT.labels());
	}

	/** Every shorthand {@code MovementMath.parseGp} takes - the Min / Max fields' own reader - works here too. */
	@Test
	public void parseReadsEveryGpShorthandTheBandFieldsRead()
	{
		assertArrayEquals(new long[]{100_000L, 1_000_000L, 10_000_000L},
			BandPresets.parse("100k, 1m, 10m").mins());
		assertArrayEquals("upper case suffixes are the same suffixes",
			new long[]{100_000L, 1_000_000L, 10_000_000L}, BandPresets.parse("100K, 1M, 10M").mins());
		assertArrayEquals("a fraction and a billion", new long[]{1_500_000L, 100_000_000L, 2_000_000_000L},
			BandPresets.parse("1.5m, 100m, 2b").mins());
		assertArrayEquals("bare gp, under the shorthand threshold", new long[]{850L, 1_000L, 9_999L},
			BandPresets.parse("850, 1000, 9999").mins());
	}

	/** Z1's three separators, and any run of them - so ", " is one gap and not a gap with an empty band in it. */
	@Test
	public void parseSplitsOnCommasSlashesAndWhitespace()
	{
		final long[] expected = {100_000L, 1_000_000L, 10_000_000L};
		final String[] lines =
		{
			"100k, 1m, 10m",
			"100k,1m,10m",
			"100k/1m/10m",
			"100k / 1m / 10m",
			"100k 1m 10m",
			"100k\t1m\n10m",
			"  100k ,  1m ,  10m  ",
			", 100k, 1m, 10m",
		};

		for (final String line : lines)
		{
			final BandPresets parsed = BandPresets.parse(line);
			assertEquals("'" + line + "' should have parsed", BandPresets.DEFAULT, parsed);
			assertArrayEquals("'" + line + "'", expected, parsed.mins());
		}
	}

	/**
	 * The one comma that is NOT a separator: the thousands grouping comma of "1,000", which
	 * {@code MovementMath.parseGp} reads as part of the number. Without this rule a band under 10,000 could be
	 * typed but never read back, since {@link BandPresets#format()} writes those with grouping commas itself.
	 */
	@Test
	public void parseKeepsAThousandsGroupingCommaInsideOneBand()
	{
		final long[] small = {1_000L, 2_000L, 3_000L};
		assertArrayEquals(small, BandPresets.parse("1,000 2,000 3,000").mins());
		assertArrayEquals(small, BandPresets.parse("1,000, 2,000, 3,000").mins());
		assertArrayEquals(small, BandPresets.parse("1,000/2,000/3,000").mins());

		// A comma between digits that does NOT introduce a group of exactly three is still a separator.
		assertArrayEquals(new long[]{1_000L, 2_000L, 30_000L}, BandPresets.parse("1000,2000,30000").mins());
		assertArrayEquals(new long[]{100_000L, 1_000_000L, 1_234_567L},
			BandPresets.parse("1,234,567 1m 100k").mins());
	}

	/** Z1: the trio is held ascending however it was typed, so the chips read smallest first. */
	@Test
	public void parseSortsWhateverOrderItIsGiven()
	{
		final long[] expected = {100_000L, 1_000_000L, 10_000_000L};
		assertArrayEquals(expected, BandPresets.parse("10m, 1m, 100k").mins());
		assertArrayEquals(expected, BandPresets.parse("1m, 10m, 100k").mins());
		assertArrayEquals(expected, BandPresets.parse("10m 100k 1m").mins());
		assertEquals("100k, 1m, 10m", BandPresets.parse("10m, 1m, 100k").format());
	}

	/** Refusal one: a line that does not name exactly three bands - including no line at all. */
	@Test
	public void parseRefusesAnythingButThreeValues()
	{
		final String[] wrong = {null, "", "   ", "100k", "100k, 1m", "100k, 1m, 10m, 100m", "1 2 3 4 5"};

		for (final String line : wrong)
		{
			assertNull("'" + line + "' should have been refused", BandPresets.parse(line));
		}
	}

	/** Refusal two: a band that is not a price. A zero would admit every row and is not a band at all. */
	@Test
	public void parseRefusesAValueThatIsNotPositive()
	{
		assertNull(BandPresets.parse("0, 1m, 10m"));
		assertNull(BandPresets.parse("100k, 0, 10m"));
		assertNull(BandPresets.parse("100k, 1m, 0"));
		// parseGp itself refuses a negative (a price cannot be negative), so this arrives as unreadable text.
		assertNull(BandPresets.parse("-1, 1m, 10m"));
		assertNull(BandPresets.parse("100k, -1m, 10m"));
	}

	/** Refusal three: two equal bands would draw two identical chips, only one of which could ever light. */
	@Test
	public void parseRefusesTwoBandsTheSame()
	{
		assertNull(BandPresets.parse("1m, 1m, 10m"));
		assertNull(BandPresets.parse("100k, 1m, 100k"));
		assertNull(BandPresets.parse("1m, 10m, 10m"));
		assertNull("the same amount written two ways is still the same amount",
			BandPresets.parse("1m, 1000000, 10m"));
	}

	/** A part that names no number refuses the whole line: half a trio is never half applied. */
	@Test
	public void parseRefusesTextThatNamesNoNumber()
	{
		final String[] rubbish = {"abc", "100k, abc, 10m", "100k, 1.5x, 10m", "k, m, b", "100k, 1e6, 10m"};

		for (final String line : rubbish)
		{
			assertNull("'" + line + "' should have been refused", BandPresets.parse(line));
		}
	}

	/**
	 * {@link BandPresets#of(long, long, long)} sorts, and enforces the same invariant {@link
	 * BandPresets#parse(String)} does - by throwing, because a caller holding three longs has already done the
	 * reading. A panel reading three boxes catches this beside the boxes' own {@code ParseException}.
	 */
	@Test
	public void ofSortsAndRefusesWhatParseRefuses()
	{
		assertEquals(BandPresets.DEFAULT, BandPresets.of(10_000_000L, 100_000L, 1_000_000L));
		assertArrayEquals(new long[]{100_000L, 1_000_000L, 10_000_000L},
			BandPresets.of(1_000_000L, 10_000_000L, 100_000L).mins());

		final long[][] refused =
		{
			{0L, 1_000_000L, 10_000_000L},
			{100_000L, -1L, 10_000_000L},
			{1_000_000L, 1_000_000L, 10_000_000L},
			{100_000L, 10_000_000L, 100_000L},
		};

		for (final long[] trio : refused)
		{
			try
			{
				fail(Arrays.toString(trio) + " should not have built a trio, gave "
					+ BandPresets.of(trio[0], trio[1], trio[2]));
			}
			catch (IllegalArgumentException expected)
			{
				assertTrue("the refusal names the three values: " + expected.getMessage(),
					expected.getMessage().contains(String.valueOf(trio[0])));
			}
		}
	}

	/** Z1: a label is the band in gp shorthand plus the "+" that says "and everything dearer". */
	@Test
	public void aLabelIsTheBandInShorthandWithAPlus()
	{
		assertArrayEquals(new String[]{"1.5m+", "100m+", "2b+"},
			BandPresets.of(1_500_000L, 100_000_000L, 2_000_000_000L).labels());
		assertArrayEquals("under 10,000 the game writes every digit, so the label does too",
			new String[]{"1,000+", "2,000+", "30k+"}, BandPresets.of(1_000L, 2_000L, 30_000L).labels());
	}

	/** What the config line holds is what the value reads back: the round trip is the whole point of format(). */
	@Test
	public void formatRoundTripsThroughParse()
	{
		final BandPresets[] trios =
		{
			BandPresets.DEFAULT,
			BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L),
			BandPresets.of(1_500_000L, 100_000_000L, 2_000_000_000L),
			BandPresets.of(500_000L, 5_000_000L, 50_000_000L),
			BandPresets.of(10_000L, 100_000L, 1_000_000L),
		};

		for (final BandPresets trio : trios)
		{
			assertEquals(trio.format(), trio, BandPresets.parse(trio.format()));
		}

		assertEquals("1.5m, 100m, 2b", BandPresets.of(2_000_000_000L, 1_500_000L, 100_000_000L).format());
	}

	/**
	 * The round trip has to survive the commas {@link BandPresets#format()} writes itself: a band under 10,000 is
	 * spelled "1,000" rather than "1k", so a blind split on commas would refuse the line the value had just
	 * written.
	 */
	@Test
	public void formatRoundTripsEvenWhenABandIsWrittenWithGroupingCommas()
	{
		final BandPresets small = BandPresets.of(1_000L, 2_000L, 3_000L);
		assertEquals("1,000, 2,000, 3,000", small.format());
		assertEquals(small, BandPresets.parse(small.format()));
	}

	/**
	 * Shorthand is lossy where the game's own stack text is lossy, and the boxes print shorthand: a band of
	 * 1,234,567 gp is written "1.23m" and comes back as 1,230,000. Pinned because it is the one case where the
	 * round trip does NOT return the same trio, and because the boxes re-print what was saved.
	 */
	@Test
	public void formatIsShorthandAndRoundsABandItCannotWriteExactly()
	{
		final BandPresets odd = BandPresets.of(1_234_567L, 1_000_000L, 10_000_000L);
		assertEquals("1m, 1.23m, 10m", odd.format());

		final BandPresets returned = BandPresets.parse(odd.format());
		assertNotEquals("the odd band does not survive the shorthand", odd, returned);
		assertArrayEquals(new long[]{1_000_000L, 1_230_000L, 10_000_000L}, returned.mins());
		// What a user typed IS read exactly; only what format() wrote is rounded.
		assertArrayEquals(new long[]{1_000_000L, 1_234_567L, 10_000_000L},
			BandPresets.parse("1,234,567, 1m, 10m").mins());
	}

	/** The value is immutable, so the two arrays it hands out are copies and not its insides. */
	@Test
	public void minsAndLabelsAreFreshArraysTheCallerCannotMutate()
	{
		final long[] mins = BandPresets.DEFAULT.mins();
		mins[0] = 42L;
		assertArrayEquals(new long[]{100_000L, 1_000_000L, 10_000_000L}, BandPresets.DEFAULT.mins());

		final String[] labels = BandPresets.DEFAULT.labels();
		labels[2] = "wrong";
		assertArrayEquals(new String[]{"100k+", "1m+", "10m+"}, BandPresets.DEFAULT.labels());
	}

	/** Value semantics, so the panel can tell an incoming trio from the one it is already drawing. */
	@Test
	public void equalTriosAreEqualWhateverOrderTheyWereBuiltIn()
	{
		final BandPresets a = BandPresets.of(100_000L, 1_000_000L, 10_000_000L);
		final BandPresets b = BandPresets.of(10_000_000L, 100_000L, 1_000_000L);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a, BandPresets.DEFAULT);
		assertNotEquals(a, BandPresets.of(100_000L, 1_000_000L, 100_000_000L));
		assertNotEquals(a, null);
		assertNotEquals(a, "100k, 1m, 10m");
		assertEquals(a, a);

		final Set<BandPresets> seen = new HashSet<>();
		assertTrue(seen.add(a));
		assertFalse("an equal trio is the same key", seen.add(b));
		assertTrue(seen.add(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L)));
	}

	/** {@code toString} is a debugging line and prints gp, not the shorthand a log would have to un-round. */
	@Test
	public void toStringNamesTheThreeBandsInGp()
	{
		final String text = BandPresets.DEFAULT.toString();
		assertTrue(text, text.contains("BandPresets"));
		assertTrue(text, text.contains("100000"));
		assertTrue(text, text.contains("1000000"));
		assertTrue(text, text.contains("10000000"));
	}
}
