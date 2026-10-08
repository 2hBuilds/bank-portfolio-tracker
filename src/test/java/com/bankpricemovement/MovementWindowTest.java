package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * {@link MovementWindow} after contract 1.1.2 (T1): the five Grand Exchange windows, the TIME arithmetic that names
 * the latest moment a baseline table may carry, and the legacy spellings a first-build profile still holds.
 *
 * <p><b>Why seconds again.</b> Addendum L (L9) made a window a count of calendar DAYS off an anchor date, because the
 * wiki's price bot saved one table a day at a random hour (L-C). Since 30 Sep 2026 it saves about eight a day, and
 * "the table of the day before the anchor" became a table a couple of hours old. The "now" end is now the TIME of the
 * table RuneLite's prices match (or the clock while RuneLite is ahead of the index), and the "then" end the newest
 * table at least {@code days() x 24 h} older - {@code PriceService} picks it, this class only does the subtraction.
 *
 * <p>The legacy parse tests are the other load-bearing ones. A user who ran the first build has
 * {@code window=H24} written into their RuneLite config; {@code ConfigManager} reads an enum back with
 * {@code Enum.valueOf}, and an unknown constant there is how this workspace has already NPE'd a config panel
 * once (CLAUDE.md, "Config interfaces must be FLAT"). Every one of the four old spellings has to land on a
 * window that exists.
 */
public class MovementWindowTest
{
	@Test
	public void theFiveWindowsCarryTheirLabelAndSpan()
	{
		assertEquals("1d", MovementWindow.D1.label());
		assertEquals("7d", MovementWindow.D7.label());
		assertEquals("30d", MovementWindow.D30.label());
		assertEquals("90d", MovementWindow.D90.label());
		assertEquals("180d", MovementWindow.D180.label());

		assertEquals(1, MovementWindow.D1.days());
		assertEquals(7, MovementWindow.D7.days());
		assertEquals(30, MovementWindow.D30.days());
		assertEquals(90, MovementWindow.D90.days());
		assertEquals(180, MovementWindow.D180.days());

		assertEquals("the GE site's own spans: today, a week, 1 month, 3 months, 6 months",
			5, MovementWindow.values().length);
	}

	/**
	 * The label a chip draws and the number the arithmetic uses have to agree: "30d" that looked back 90 days
	 * would be a silently wrong figure on every row, not a failure anybody would see.
	 */
	@Test
	public void everyLabelIsItsOwnDayCountAndTheyIncrease()
	{
		int previous = 0;
		for (final MovementWindow window : MovementWindow.values())
		{
			assertEquals(window + " must look back exactly as far as its label says",
				window.label(), window.days() + "d");
			assertTrue(window + " must be longer than the window before it", window.days() > previous);
			previous = window.days();
		}
	}

	/**
	 * The hourly windows are gone with the trade API: the guide table is a step function stamped once a DAY, so
	 * an hour-wide window would put both ends on the same step and read 0.0 % for every item in the bank.
	 */
	@Test
	public void noWindowIsShorterThanADay()
	{
		for (final MovementWindow window : MovementWindow.values())
		{
			assertTrue(window + " is shorter than one guide-price step", window.days() >= 1);
		}
	}

	/**
	 * The config combo box and the sidebar chip both read {@code toString()}; the STORED value is the constant
	 * name, which {@code ConfigManager.java:1279-1281} writes and {@code :1205-1207} reads.
	 */
	@Test
	public void toStringIsTheLabelWhileTheNameIsUnchanged()
	{
		assertEquals("1d", MovementWindow.D1.toString());
		assertEquals("D1", MovementWindow.D1.name());
		assertSame(MovementWindow.D1, MovementWindow.valueOf("D1"));
		assertEquals("180d", MovementWindow.D180.toString());
		assertEquals("D180", MovementWindow.D180.name());
	}

	@Test
	public void theDefaultIsTheGeSitesTodayFigure()
	{
		assertSame(MovementWindow.D1, MovementWindow.DEFAULT);
	}

	// ---- targetSeconds (contract 1.1.2, T1)

	/** The now time of the 1.1.2 finding: 2026-10-07T16:25:00Z, the table RuneLite's prices matched. */
	private static final long NOW = 1_791_390_300L;
	private static final long DAY = 86_400L;

	@Test
	public void theTargetIsThatManyTimesTwentyFourHoursBeforeTheNowTime()
	{
		assertEquals("06 Oct 16:25", NOW - DAY, MovementWindow.D1.targetSeconds(NOW));
		assertEquals(NOW - 7 * DAY, MovementWindow.D7.targetSeconds(NOW));
		assertEquals(NOW - 30 * DAY, MovementWindow.D30.targetSeconds(NOW));
		assertEquals(NOW - 90 * DAY, MovementWindow.D90.targetSeconds(NOW));
		assertEquals(NOW - 180 * DAY, MovementWindow.D180.targetSeconds(NOW));
		assertEquals("the same hour of the day, a day back: the table in force then, not the last of its date",
			RevisionRef.dayOf(NOW).minusDays(1), RevisionRef.dayOf(MovementWindow.D1.targetSeconds(NOW)));
	}

	@Test
	public void everyWindowLooksBackwardsAndTheLongerOneLooksFurther()
	{
		long previous = NOW;
		for (final MovementWindow window : MovementWindow.values())
		{
			final long target = window.targetSeconds(NOW);
			assertTrue(window + " must look BACKWARDS", target < NOW);
			assertTrue(window + " must look further back than the window before it", target < previous);
			assertEquals(window + " looks back exactly its span", window.days() * DAY, NOW - target);
			previous = target;
		}
	}

	/**
	 * No now time yet means no target, which means no baseline and a "-" in the change column. Guessing one from the
	 * wall clock here is exactly what the service decides against unless RuneLite is ahead of the index (T1).
	 */
	@Test
	public void noNowTimeMeansNoTarget()
	{
		for (final MovementWindow window : MovementWindow.values())
		{
			assertEquals(window + " must not invent a now", 0L, window.targetSeconds(0L));
			assertEquals(0L, window.targetSeconds(-5L));
		}
	}

	// ---- parse (K4)

	@Test
	public void parseTakesLabelsNamesAndCasing()
	{
		assertSame(MovementWindow.D1, MovementWindow.parse("1d"));
		assertSame(MovementWindow.D7, MovementWindow.parse("7d"));
		assertSame(MovementWindow.D30, MovementWindow.parse("30d"));
		assertSame(MovementWindow.D90, MovementWindow.parse("90d"));
		assertSame(MovementWindow.D180, MovementWindow.parse("180d"));

		assertSame(MovementWindow.D1, MovementWindow.parse("D1"));
		assertSame(MovementWindow.D30, MovementWindow.parse("d30"));
		assertSame(MovementWindow.D180, MovementWindow.parse("  D180  "));
	}

	/**
	 * The reason this method exists at all: {@code window=H24} is sitting in the user's RuneLite profile from
	 * the first build, and H1 / H24 name nothing now. All four spellings collapse onto 1d - an hour is below the
	 * resolution of the guide series, and 24 h IS the new 1d.
	 */
	@Test
	public void theFourPreAddendumKSpellingsAllLandOnOneDay()
	{
		assertSame("the stored config value from the first build", MovementWindow.D1, MovementWindow.parse("H24"));
		assertSame(MovementWindow.D1, MovementWindow.parse("h24"));
		assertSame("the old chip text", MovementWindow.D1, MovementWindow.parse("24h"));
		assertSame(MovementWindow.D1, MovementWindow.parse("H1"));
		assertSame(MovementWindow.D1, MovementWindow.parse("h1"));
		assertSame(MovementWindow.D1, MovementWindow.parse("1h"));
		assertSame(MovementWindow.D1, MovementWindow.parse("  24H  "));
	}

	/** "7d" and "D7" meant the same window before addendum K and still do - the one value that carries over. */
	@Test
	public void theOldSevenDayWindowSurvivesUnchanged()
	{
		assertSame(MovementWindow.D7, MovementWindow.parse("7d"));
		assertSame(MovementWindow.D7, MovementWindow.parse("D7"));
		assertEquals(7, MovementWindow.D7.days());
	}

	@Test
	public void parseAnswersNullRatherThanGuessing()
	{
		assertNull(MovementWindow.parse(null));
		assertNull(MovementWindow.parse(""));
		assertNull(MovementWindow.parse("   "));
		assertNull(MovementWindow.parse("1"));
		assertNull(MovementWindow.parse("d"));
		assertNull(MovementWindow.parse("12h"));
		assertNull(MovementWindow.parse("2d"));
		assertNull(MovementWindow.parse("365d"));
		assertNull(MovementWindow.parse("week"));
	}

	@Test
	public void parseRoundTripsEveryLabelAndName()
	{
		for (final MovementWindow window : MovementWindow.values())
		{
			assertSame(window, MovementWindow.parse(window.label()));
			assertSame(window, MovementWindow.parse(window.name()));
			assertSame(window, MovementWindow.parse(window.toString()));
		}
	}

	/** Two windows sharing a label or a name would make the chips and the stored value ambiguous. */
	@Test
	public void everyLabelAndNameIsUnique()
	{
		final Set<String> seen = new HashSet<>();
		for (final MovementWindow window : MovementWindow.values())
		{
			assertTrue("duplicate label " + window.label(), seen.add(window.label()));
			assertTrue("duplicate name " + window.name(), seen.add(window.name()));
		}
	}
}
