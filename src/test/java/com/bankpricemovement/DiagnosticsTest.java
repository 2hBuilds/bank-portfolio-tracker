package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * The diagnostics memory (1.0.8): a ring of 150 notes, the last five distinct errors, warn-once keys, and the report
 * the troubleshooting window shows and copies - in sections, verdict first, with no account, no name and no path in
 * it. The clock and the zone are fixed so every stamp is a known one.
 */
public class DiagnosticsTest
{
	/** 2026-09-30T14:05:09Z. */
	private static final long T0 = 1_790_777_109_000L;

	/** The report's headings, in order - the contract's part 7. */
	private static final List<String> HEADINGS = Arrays.asList("Build", "Player", "Bank", "Prices", "Pricing", "History",
		"Sidebar", "Background work", "Settings", "Errors", "Checks", "Recent events, oldest first");

	private final AtomicLong clock = new AtomicLong(T0);
	private final Diagnostics diagnostics = new Diagnostics(clock::get, ZoneId.of("UTC"));

	@Test
	public void aNoteIsStampedWithTheLocalTimeOfDay()
	{
		diagnostics.note("bank interface opened");

		assertTrue(notes(report(diagnostics)).contains("14:05:09 bank interface opened"));
	}

	@Test
	public void theStampFollowsTheZone()
	{
		final Diagnostics tokyo = new Diagnostics(clock::get, ZoneId.of("Asia/Tokyo"));

		tokyo.note("x");

		assertTrue(notes(report(tokyo)).contains("23:05:09 x"));
		assertTrue(report(tokyo).contains("utc offset: UTC+09:00\n"));
		assertTrue(report(tokyo).contains("local time: 2026-09-30 23:05:09\n"));
	}

	/**
	 * The report says how far the player's clock is from UTC and never which place they are in: a zone's NAME can
	 * narrow a person down to a country or a city, its offset only to a band of the map.
	 */
	@Test
	public void theReportNamesTheOffsetFromUtcAndNeverTheZone()
	{
		final String tokyo = report(new Diagnostics(clock::get, ZoneId.of("Asia/Tokyo")));
		assertTrue(tokyo, tokyo.contains("\nutc offset: UTC+09:00\n"));
		assertFalse("the zone's name is not in the report: " + tokyo, tokyo.contains("Tokyo"));
		assertFalse("the old line is gone: " + tokyo, tokyo.contains("time zone"));

		final String kolkata = report(new Diagnostics(clock::get, ZoneId.of("Asia/Kolkata")));
		assertTrue(kolkata, kolkata.contains("\nutc offset: UTC+05:30\n"));
		assertFalse(kolkata, kolkata.contains("Kolkata"));

		final String utc = report(diagnostics);
		assertTrue(utc, utc.contains("\nutc offset: UTC+00:00\n"));

		// A zone west of Greenwich, at the report's own clock (30 Sep 2026, daylight time in New York).
		final String newYork = report(new Diagnostics(clock::get, ZoneId.of("America/New_York")));
		assertTrue(newYork, newYork.contains("\nutc offset: UTC-04:00\n"));
		assertFalse(newYork, newYork.contains("New_York"));
	}

	@Test
	public void theRingKeepsTheLastOneHundredAndFiftyNotesOldestFirst()
	{
		assertEquals(150, Diagnostics.CAPACITY);
		for (int i = 1; i <= 200; i++)
		{
			clock.set(T0 + i * 1_000L);
			diagnostics.note("note " + i);
		}

		final List<String> notes = notes(report(diagnostics));

		assertEquals(150, notes.size());
		assertTrue("the oldest 50 are gone: " + notes.get(0), notes.get(0).endsWith(" note 51"));
		assertTrue(notes.get(149).endsWith(" note 200"));
		for (int i = 0; i < notes.size(); i++)
		{
			assertTrue("in order: " + notes.get(i), notes.get(i).endsWith(" note " + (51 + i)));
		}
	}

	@Test
	public void aKeyIsNewOnceAndNotedOnceAndCountedEveryTime()
	{
		assertTrue(diagnostics.warnOnce("bank-read"));
		assertFalse(diagnostics.warnOnce("bank-read"));
		assertFalse(diagnostics.warnOnce("bank-read"));
		assertTrue("another key is its own", diagnostics.warnOnce("no-hash"));

		final String report = report(diagnostics);
		final List<String> notes = notes(report);

		assertEquals(2, notes.size());
		assertTrue(notes.get(0).endsWith(" warning: bank-read"));
		assertTrue(notes.get(1).endsWith(" warning: no-hash"));
		assertTrue(report, report.contains("warnings raised:\n   bank-read: 3 times\n   no-hash: 1 time\n"));
	}

	@Test
	public void theLastErrorIsKeptWithItsClassMessageAndAtMostTwelveFrames()
	{
		diagnostics.error("first", new IllegalStateException("old"));
		final Throwable deep = deepException(30);

		diagnostics.error(Diagnostics.BANK_READ_FAILED, deep);

		final String report = report(diagnostics);
		assertEquals("bank read failed", diagnostics.lastErrorWhat());
		assertTrue(report, report.contains("1. 14:05:09 bank read failed\n"));
		assertTrue(report, report.contains("   class: java.lang.IllegalArgumentException\n"));
		assertTrue(report, report.contains("   message: deep 30\n"));
		assertTrue("the older one is still there, second", report.contains("2. 14:05:09 first\n"));
		assertEquals("twelve frames of the deep one and the older one's, no more", 12 + frames(new IllegalStateException("old")),
			count(report, "\n     at "));
		assertTrue("and the error is noted as an event too: " + report,
			report.contains(" bank read failed: java.lang.IllegalArgumentException: deep 30"));
	}

	@Test
	public void theLastFiveDistinctErrorsAreKeptNewestFirstAndARepeatCountsUp()
	{
		for (int i = 1; i <= 7; i++)
		{
			diagnostics.error("what " + i, new IllegalStateException("message " + i));
		}
		diagnostics.error("what 5 again", new IllegalStateException("message 5"));

		final String report = report(diagnostics);

		assertEquals("only five are kept", 5, count(report, "   class: "));
		assertTrue(report, report.contains("1. 14:05:09 what 5 again (seen 2 times)\n"));
		assertTrue(report, report.contains("2. 14:05:09 what 7\n"));
		assertTrue(report, report.contains("3. 14:05:09 what 6\n"));
		assertFalse("the same error does not take a second place", report.contains("\n2. 14:05:09 what 5\n"));
		assertFalse("the oldest two are gone",
			report.contains("   message: message 2\n") || report.contains("   message: message 1\n"));
		assertEquals("what 5 again", diagnostics.lastErrorWhat());
	}

	@Test
	public void aCauseIsPrintedOnItsOwnLine()
	{
		diagnostics.error("wrapped", new IllegalStateException("outer", new java.io.IOException("inner")));

		assertTrue(report(diagnostics).contains("   cause: java.io.IOException: inner\n"));
	}

	@Test
	public void withNoErrorTheReportSaysNone()
	{
		assertTrue(report(diagnostics).contains("\nErrors\nnone\n"));
		assertNull(diagnostics.lastErrorWhat());
	}

	@Test
	public void aLineBreakInANoteOrAFactNeverBreaksTheReport()
	{
		diagnostics.note("a\nb\r\nc");
		final Diagnostics.Facts facts = Diagnostics.Facts.builder().line(Diagnostics.SIDEBAR, "x", "y\nz").build();

		final String report = diagnostics.text(facts, "v\nw", Collections.emptyList());

		assertTrue(report, report.contains("\n14:05:09 a b c\n"));
		assertTrue(report, report.contains("x: y z\n"));
		assertTrue(report, report.contains("Verdict: v w\n"));
	}

	@Test
	public void warnLogsAtWarnOnceThenAtDebugAndNotesTheKeyOnce()
	{
		final List<String> levels = new ArrayList<>();
		final org.slf4j.Logger log = (org.slf4j.Logger) java.lang.reflect.Proxy.newProxyInstance(
			getClass().getClassLoader(), new Class<?>[]{org.slf4j.Logger.class}, (proxy, method, args) ->
			{
				if (method.getName().equals("warn") || method.getName().equals("debug"))
				{
					levels.add(method.getName());
				}
				return null;
			});

		diagnostics.warn(log, "k", "a {}", "b");
		diagnostics.warn(log, "k", "a {}", "b");
		diagnostics.warn(log, "k", "a {}", "b");

		assertEquals(Arrays.asList("warn", "debug", "debug"), levels);
		assertEquals(1, count(report(diagnostics), "warning: k"));
	}

	// ---- the report

	/** Every heading is there, once, in the contract's order; the title and the verdict come first. */
	@Test
	public void theReportHasEverySectionInOrderWithTheVerdictFirst()
	{
		final String report = diagnostics.text(fullFacts(), "Everything looks fine.", Collections.singletonList(
			new CheckResult("wiki mapping", true, 200, 131L, "reachable")));

		final String[] lines = report.split("\n");
		assertEquals("2h Bank Portfolio Tracker " + Version.CURRENT + " - diagnostics", lines[0]);
		assertEquals("Verdict: Everything looks fine.", lines[1]);
		int from = 0;
		for (final String heading : HEADINGS)
		{
			final int at = report.indexOf("\n" + heading + "\n", from);
			assertTrue("the heading " + heading + " comes next, in order, in: " + report, at >= from && at >= 0);
			assertEquals("once: " + heading, at, report.lastIndexOf("\n" + heading + "\n"));
			from = at + 1;
		}
		assertTrue(report, report.contains("Checks\nwiki mapping: ok, HTTP 200, 131 ms - reachable\n"));
	}

	@Test
	public void theBuildSectionNamesTheVersionsTheSystemAndTheClockSkew()
	{
		diagnostics.fetchLog().serverDate("Wed, 30 Sep 2026 14:05:04 GMT");

		final String report = report(diagnostics);

		assertTrue(report, report.contains("plugin version: " + Version.CURRENT + "\n"));
		assertTrue(report, report.contains("\nRuneLite: "));
		assertTrue(report, report.contains("\nlauncher: "));
		assertTrue(report, report.contains("\nhub version: "));
		assertTrue(report, report.contains("system: " + System.getProperty("os.name") + " " + System.getProperty("os.version")));
		assertTrue(report, report.contains("java: " + System.getProperty("java.version") + "\n"));
		assertTrue(report, report.contains("clock skew: +5 s (this computer minus the wiki)\n"));
	}

	@Test
	public void theClockSkewIsUnknownUntilAResponseCarriesADate()
	{
		assertTrue(report(diagnostics).contains("clock skew: unknown\n"));

		diagnostics.fetchLog().serverDate("not a date");
		assertTrue("an unreadable header changes nothing", report(diagnostics).contains("clock skew: unknown\n"));

		diagnostics.fetchLog().serverDate("Wed, 30 Sep 2026 14:05:12 GMT");
		assertTrue(report(diagnostics).contains("clock skew: -3 s (this computer minus the wiki)\n"));
	}

	@Test
	public void theFactsSectionsPrintTheirLinesAndASectionWithNoneSaysSo()
	{
		final String report = diagnostics.text(fullFacts(), "v", Collections.emptyList());

		assertTrue(report, report.contains("\nPlayer\nlogged in: yes\naccount known: yes\nprofile: STANDARD\n"
			+ "world types: MEMBERS, PVP\nbank window open now: no\n"));
		assertTrue(report, report.contains("bank events seen: 12\nbank events held: 3\nbank reads made: 4\nbank events dropped: 0\n"));
		assertTrue(report, report.contains("\nSettings\ngpMin: 0\n"));
		assertTrue(report(diagnostics), report(diagnostics).contains("\nHistory\nnothing reported\n"));
	}

	@Test
	public void thePricesSectionHasOneLinePerSourceFromTheFetchLog()
	{
		final FetchLog log = diagnostics.fetchLog();
		log.ok(FetchLog.MAPPING, 200, 131L, 84_213L, 4_566);
		log.failed(FetchLog.PRICE_INDEX, 503, 40L, 12L, "HTTP 503");
		log.failed(FetchLog.PRICE_INDEX, 0, 8_000L, 0L, "wiki request failed: timeout");

		final String report = report(diagnostics);

		assertTrue(report, report.contains("mapping: last attempt 14:05:09, ok, HTTP 200, 131 ms, 84213 bytes, 4566 items, "
			+ "0 failures in a row\n"));
		assertTrue(report, report.contains("price index: last attempt 14:05:09, failed, HTTP none, 8000 ms, 0 bytes, 0 items, "
			+ "2 failures in a row, last error: wiki request failed: timeout\n"));
		assertTrue(report, report.contains("guide tables: not fetched this session\n"));
		assertTrue(report, report.contains("live latest: not fetched this session\n"));
		assertTrue(report, report.contains("live day buckets: not fetched this session\n"));
	}

	@Test
	public void theBackgroundWorkSectionNamesTheLastTaskAndFlagsAStuckOne()
	{
		final Watchdog dog = diagnostics.watchdog();
		dog.submitted();
		dog.submitted();
		final long first = dog.started("pricing the bank", true);
		clock.set(T0 + 5_000L);
		dog.finished(first);
		final long second = dog.started("fetching live prices", true);
		clock.set(T0 + 20_000L);

		String report = report(diagnostics);
		assertTrue(report, report.contains("last task: fetching live prices\n"));
		assertTrue(report, report.contains("started at: 14:05:14\n"));
		assertTrue(report, report.contains("finished at: still running\n"));
		assertTrue(report, report.contains("queued now: 0\n"));
		assertTrue(report, report.contains("running for: 15 s\n"));

		clock.set(T0 + 50_000L);
		report = report(diagnostics);
		assertTrue(report, report.contains("running for: 45 s - STUCK\n"));

		dog.finished(second);
		report = report(diagnostics);
		assertTrue(report, report.contains("finished at: 14:05:59\n"));
		assertTrue(report, report.contains("running for: -\n"));
	}

	@Test
	public void theReportCarriesNoRunOfFifteenDigitsAndNoPath()
	{
		diagnostics.note("bank event: read (31 stacks, 4 carried)");
		diagnostics.note("saving failed in C:\\Users\\John Smith\\.runelite\\plugin-data\\bank-portfolio-tracker\\x.json");
		diagnostics.error("saving", new java.nio.file.AccessDeniedException("C:\\Users\\John Smith\\.runelite\\x.tmp"));
		diagnostics.error("reading", new IllegalStateException("/home/jsmith/.runelite/plugin-data/bank/x.json: denied"));
		diagnostics.fetchLog().failed(FetchLog.MAPPING, 0, 10L, 0L, "NoSuchFileException: D:\\Stuff\\mapping.json");
		final Diagnostics.Facts facts = Diagnostics.Facts.builder()
			.line(Diagnostics.SETTINGS, "data folder", "C:\\Users\\John Smith\\.runelite\\plugin-data\\bank-portfolio-tracker")
			.build();

		final String report = diagnostics.text(facts, "v", Collections.singletonList(new CheckResult("data folder", false, 0, 3L,
			"AccessDeniedException: " + Diagnostics.scrub("C:\\Users\\John Smith\\.runelite\\x.tmp"))));

		assertFalse("no run of fifteen digits, which is what an account hash looks like",
			Pattern.compile("\\d{15,}").matcher(report).find());
		for (final String leak : new String[]{"John", "Smith", "jsmith", "C:\\", "D:\\", "/home/", "x.tmp", "x.json",
			System.getProperty("user.home")})
		{
			assertFalse("the path fragment '" + leak + "' must not reach the report: " + report, report.contains(leak));
		}
		assertTrue(report, report.contains("data folder: <path>\n"));
		assertTrue(report, report.contains("   message: <path>\n"));
	}

	@Test
	public void scrubTakesOutPathsAndLeavesUrlsAndPlainTextAlone()
	{
		assertEquals("failed: <path>: Access is denied",
			Diagnostics.scrub("failed: C:\\Users\\John Smith\\.runelite\\x.tmp: Access is denied"));
		assertEquals("<path>", Diagnostics.scrub("C:/Users/jsmith/.runelite/x.json"));
		assertEquals("<path> not found", Diagnostics.scrub("\\\\server\\share\\x.json not found"));
		assertEquals("could not open <path>", Diagnostics.scrub("could not open /home/jsmith/.runelite/x.json"));
		assertEquals("bad (<path>)", Diagnostics.scrub("bad (/Users/jsmith/x.json)"));
		final String url = "wiki request to https://prices.runescape.wiki/api/v1/osrs/latest failed (HTTP 503)";
		assertEquals(url, Diagnostics.scrub(url));
		assertEquals("read and/or write 3/4 of it", Diagnostics.scrub("read and/or write 3/4 of it"));
		assertEquals("", Diagnostics.scrub(null));
	}

	// ---- helpers

	/** A full set of facts, as the plugin, the service and the panel would hand them over. */
	private static Diagnostics.Facts fullFacts()
	{
		final Diagnostics.Facts.Builder b = Diagnostics.Facts.builder();
		b.loggedIn(true).accountKnown(true);
		b.line(Diagnostics.PLAYER, "profile", "STANDARD");
		b.line(Diagnostics.PLAYER, "world types", "MEMBERS, PVP");
		b.line(Diagnostics.PLAYER, "bank window open now", "no");
		b.bankEvents(12, 3, 4, 0);
		b.line(Diagnostics.BANK, "last read at", "14:01:02");
		b.line(Diagnostics.PRICING, "rows by source", "live 10, guide 20, parts 1, alch 2, unpriced 0 (of 33 listed)");
		b.line(Diagnostics.SIDEBAR, "tab", "ITEMS");
		b.card("LIST");
		b.line(Diagnostics.SETTINGS, "gpMin", "0");
		return b.build();
	}

	private static String report(final Diagnostics diagnostics)
	{
		return DiagnosticsReports.report(diagnostics);
	}

	private static Throwable deepException(final int depth)
	{
		try
		{
			recurse(depth);
			return null;
		}
		catch (final IllegalArgumentException e)
		{
			return e;
		}
	}

	private static void recurse(final int left)
	{
		if (left == 0)
		{
			throw new IllegalArgumentException("deep 30");
		}
		recurse(left - 1);
	}

	/** How many frames the report keeps of an exception made right here: its whole trace, up to twelve. */
	private static int frames(final Throwable e)
	{
		return Math.min(Diagnostics.MAX_FRAMES, e.getStackTrace().length);
	}

	/** The event lines of a report: everything after the last heading. */
	private static List<String> notes(final String report)
	{
		final List<String> all = Arrays.asList(report.split("\n"));
		return all.subList(all.indexOf("Recent events, oldest first") + 1, all.size());
	}

	private static int count(final String text, final String needle)
	{
		int count = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
		{
			count++;
		}
		return count;
	}
}
