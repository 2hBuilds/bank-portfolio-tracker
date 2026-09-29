package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The net-worth history file in {@link PriceStore} (addendum AU; contract section 4; plan 7.1 items 1, 4, 5 and 7,
 * 7.3's named store tests). The fault this class exists for is the one the plan's review called its blocker: a
 * history file that fails to read ONCE must never be replaced by a one-point file. So: the three outcomes of a
 * load, an unreadable file never written over and its owner refused for the rest of the session, an unparseable
 * one moved aside and nothing written in its place, an older schema migrated, a newer one left alone, a bad entry
 * skipped, two stores keeping each other's days, last-wins within a day, the future dropped, the exact shape on
 * disk, and the sweep leaving every {@code history-*} file alone.
 *
 * <p>Real disk through {@link TestFilepaths}, as {@code PriceStoreTest}; a directory standing where the file should
 * be is the portable way to make a read fail.
 */
public class PriceStoreHistoryTest
{
	private static final long ACCOUNT = 42L;
	private static final String PROFILE = "STANDARD";
	private static final LocalDate TODAY = sep(28);

	private final Gson gson = new Gson();

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private PriceStore store()
	{
		return new PriceStore(gson, TestFilepaths.rooted(tmp.getRoot()));
	}

	/** A reading of {@code day} taken at {@code readAt}: cell i is {@code base + i}, the guide {@code guideBase + i}. */
	private static BankHistoryPoint point(final LocalDate day, final long readAt, final long base,
		final Long guideBase)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		final long[] guide = new long[BankHistoryPoint.CELLS];
		for (int i = 0; i < card.length; i++)
		{
			card[i] = base + i;
			guide[i] = guideBase == null ? base + i : guideBase + i;
		}
		return new BankHistoryPoint(day, readAt, readAt - 1_000L, card, guideBase == null ? null : guide);
	}

	private static BankHistoryPoint point(final LocalDate day, final long readAt, final long base)
	{
		return point(day, readAt, base, null);
	}

	private Filepath file(final PriceStore store)
	{
		return store.historyFile(ACCOUNT, PROFILE);
	}

	private List<String> names()
	{
		final String[] names = tmp.getRoot().list();
		final List<String> sorted = new ArrayList<>(names == null ? new ArrayList<String>() : Arrays.asList(names));
		sorted.sort(null);
		return sorted;
	}

	private List<String> namesContaining(final String part)
	{
		final List<String> found = new ArrayList<>();
		for (final String name : names())
		{
			if (name.contains(part))
			{
				found.add(name);
			}
		}
		return found;
	}

	/** One entry as the store writes it, for hand-built files. */
	private static String entry(final String day, final long readAt, final String card)
	{
		return "{\"day\":\"" + day + "\",\"readAtMillis\":" + readAt + ",\"bankAtMillis\":" + readAt + ",\"card\":["
			+ card + "]}";
	}

	private static final String CELLS_1 = "1,1,1,1,1,1,1,1";

	// ---- the file's name

	@Test
	public void theHistoryFilePairsWithTheBankFile()
	{
		final PriceStore store = store();
		assertEquals("history-", PriceStore.HISTORY_PREFIX);
		assertEquals("history-42-STANDARD.json", store.historyFile(42L, "STANDARD").getFileName());
		assertEquals("the same safeProfile as the bank file", "history-7-______etc.json",
			store.historyFile(7L, "../../etc").getFileName());
		for (final String profile : new String[]{"STANDARD", "../../etc", " ", null, "LEAGUE"})
		{
			final String bank = store.bankFile(9L, profile).getFileName();
			final String history = store.historyFile(9L, profile).getFileName();
			assertEquals(bank.substring(PriceStore.BANK_PREFIX.length()),
				history.substring(PriceStore.HISTORY_PREFIX.length()));
			assertEquals(store.dir(), store.historyFile(9L, profile).getParent());
		}
		assertEquals("history-9-UNKNOWN.json", store.historyFile(9L, null).getFileName());
	}

	// ---- MISSING and LOADED

	@Test
	public void noFileIsMissingAndEmpty()
	{
		final PriceStore.BankHistoryLoad load = store().loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, load.state());
		assertSame(BankHistorySeries.EMPTY, load.series());
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, store().loadBankHistory(0L, PROFILE).state());
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, store().loadBankHistory(-1L, PROFILE).state());
		assertTrue("a load creates nothing", names().isEmpty());
	}

	@Test
	public void aRecordedPointLoadsBack()
	{
		final PriceStore store = store();
		final BankHistoryPoint p = point(TODAY, 5_000L, 100L, 200L);
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, p, TODAY));
		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertEquals(BankHistorySeries.EMPTY.with(p), load.series());
		assertTrue(load.series().on(TODAY).hasGuide());
	}

	@Test
	public void theDirectoryIsCreatedOnTheFirstRecord()
	{
		final Filepath missing = TestFilepaths.at(tmp.getRoot(), "never-used");
		final PriceStore store = new PriceStore(gson, missing);
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertFalse(missing.exists());
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertTrue(store.historyFile(ACCOUNT, PROFILE).exists());
	}

	// ---- the shape on disk

	@Test
	public void theFileIsExactlyTheDocumentedShape() throws IOException
	{
		final PriceStore store = store();
		final long read = 1_790_000_000_000L;
		final long[] card = {10L, 1L, 2L, 3L, 4L, 5L, 6L, 7L};
		final long[] guide = {9L, 1L, 2L, 3L, 4L, 5L, 6L, 7L};
		store.recordBankHistory(ACCOUNT, PROFILE, new BankHistoryPoint(sep(27), read, read - 5L, card, card), TODAY);
		assertEquals("{\"schema\":1,\"points\":[{\"day\":\"2026-09-27\",\"readAtMillis\":1790000000000,"
				+ "\"bankAtMillis\":1789999999995,\"card\":[10,1,2,3,4,5,6,7]}]}",
			TestFilepaths.read(file(store)));

		store.recordBankHistory(ACCOUNT, PROFILE, new BankHistoryPoint(TODAY, read + 1L, read, card, guide), TODAY);
		assertEquals("{\"schema\":1,\"points\":[{\"day\":\"2026-09-27\",\"readAtMillis\":1790000000000,"
				+ "\"bankAtMillis\":1789999999995,\"card\":[10,1,2,3,4,5,6,7]},"
				+ "{\"day\":\"2026-09-28\",\"readAtMillis\":1790000000001,\"bankAtMillis\":1790000000000,"
				+ "\"card\":[10,1,2,3,4,5,6,7],\"guide\":[9,1,2,3,4,5,6,7]}]}",
			TestFilepaths.read(file(store)));
	}

	// ---- the write rules

	@Test
	public void lastWinsWithinADayAndPastDaysAreKept()
	{
		final PriceStore store = store();
		store.recordBankHistory(ACCOUNT, PROFILE, point(sep(27), 100L, 1L), TODAY);
		store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 200L, 2L), TODAY);
		// A later reading of the same day with an EARLIER stamp (a clock set back) still replaces it: last wins.
		store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 150L, 3L), TODAY);
		final BankHistorySeries s = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(2, s.size());
		assertEquals(1L, s.on(sep(27)).card(0));
		assertEquals(3L, s.on(TODAY).card(0));
	}

	@Test
	public void twoStoresWritingDifferentDaysToOneFileKeepBoth()
	{
		final PriceStore first = store();
		final PriceStore second = store();
		assertTrue(first.recordBankHistory(ACCOUNT, PROFILE, point(sep(27), 1L, 27L), TODAY));
		assertTrue(second.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 2L, 28L), TODAY));
		assertTrue(first.recordBankHistory(ACCOUNT, PROFILE, point(sep(26), 3L, 26L), TODAY));
		final BankHistorySeries s = second.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(Arrays.asList(sep(26), sep(27), TODAY), BankHistorySeriesTest.days(s));
	}

	@Test
	public void aFutureDatedPointIsDroppedAtTheNextWrite() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":1,\"points\":[" + entry("2026-09-20", 1L, CELLS_1) + ","
			+ entry("2026-10-05", 2L, CELLS_1) + "]}");
		assertEquals("the load keeps what is on disk", 2, store.loadBankHistory(ACCOUNT, PROFILE).series().size());

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 3L, 5L), TODAY));
		assertEquals(Arrays.asList(sep(20), TODAY),
			BankHistorySeriesTest.days(store.loadBankHistory(ACCOUNT, PROFILE).series()));
	}

	@Test
	public void aPointDatedAfterTodayIsNotRecorded()
	{
		final PriceStore store = store();
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY.plusDays(1), 1L, 1L), TODAY));
		assertFalse(file(store).exists());
		assertTrue("today itself is fine", store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
	}

	@Test
	public void nothingIsWrittenWhenTheMergeEqualsWhatWasRead() throws IOException
	{
		final PriceStore store = store();
		final BankHistoryPoint p = point(TODAY, 7L, 70L);
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, p, TODAY));
		// The same document spelled with spaces: were it rewritten, the spaces would be gone.
		final String spaced = TestFilepaths.read(file(store)).replace(",", ", ");
		TestFilepaths.write(file(store), spaced);

		assertTrue("the point is already there", store.recordBankHistory(ACCOUNT, PROFILE, p, TODAY));
		assertEquals("and nothing was written", spaced, TestFilepaths.read(file(store)));

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 7L, 71L), TODAY));
		assertFalse("any changed cell is a write", spaced.equals(TestFilepaths.read(file(store))));
	}

	@Test
	public void anAccountWithNoOwnerIsRefused()
	{
		final PriceStore store = store();
		assertFalse(store.recordBankHistory(0L, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertFalse(store.recordBankHistory(-1L, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, null, TODAY));
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), null));
		assertTrue(names().isEmpty());
	}

	@Test
	public void accountsAndProfilesKeepTheirOwnHistories()
	{
		final PriceStore store = store();
		store.recordBankHistory(42L, "STANDARD", point(TODAY, 1L, 1L), TODAY);
		store.recordBankHistory(42L, "DEADMAN", point(TODAY, 1L, 2L), TODAY);
		store.recordBankHistory(43L, "STANDARD", point(TODAY, 1L, 3L), TODAY);
		assertEquals(Arrays.asList("history-42-DEADMAN.json", "history-42-STANDARD.json",
			"history-43-STANDARD.json"), names());
		assertEquals(1L, store.loadBankHistory(42L, "STANDARD").series().on(TODAY).card(0));
		assertEquals(2L, store.loadBankHistory(42L, "DEADMAN").series().on(TODAY).card(0));
		assertEquals(3L, store.loadBankHistory(43L, "STANDARD").series().on(TODAY).card(0));
	}

	// ---- FAILED: never written over

	/**
	 * THE blocker: a history that fails to read once is never replaced by a one-point file. A directory where the
	 * file should be cannot be read; the record writes nothing and answers false, the owner is refused for the rest
	 * of the session even once the obstacle is gone, another owner is unaffected, and a new store - a restart -
	 * reads and records again.
	 */
	@Test
	public void anUnreadableHistoryIsNeverWrittenOverAndItsOwnerIsRefusedForTheSession() throws IOException
	{
		final PriceStore store = store();
		final Filepath history = file(store);
		history.createDirectories();

		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertTrue("the obstacle is untouched", history.isDirectory());
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertSame(BankHistorySeries.EMPTY, store.loadBankHistory(ACCOUNT, PROFILE).series());
		assertTrue("nothing was quarantined - an unreadable file says nothing about its content",
			namesContaining(".corrupt-").isEmpty());

		// The obstacle goes, and a good file appears (another client wrote it): still refused this session.
		history.delete();
		TestFilepaths.write(history, "{\"schema\":1,\"points\":[" + entry("2026-09-01", 1L, CELLS_1) + "]}");
		final String before = TestFilepaths.read(history);
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 2L, 2L), TODAY));
		assertEquals(before, TestFilepaths.read(history));

		assertTrue("another owner is unaffected",
			store.recordBankHistory(ACCOUNT, "DEADMAN", point(TODAY, 1L, 1L), TODAY));

		final PriceStore restarted = store();
		assertTrue(restarted.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 3L, 3L), TODAY));
		assertEquals(Arrays.asList(sep(1), TODAY),
			BankHistorySeriesTest.days(restarted.loadBankHistory(ACCOUNT, PROFILE).series()));
	}

	@Test
	public void aFailedLoadAloneRefusesTheOwnerForTheSession() throws IOException
	{
		final PriceStore store = store();
		final Filepath history = file(store);
		history.createDirectories();
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		history.delete();
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertFalse(history.exists());
	}

	@Test
	public void anUnparseableHistoryIsMovedAsideAndNothingIsWrittenInItsPlace() throws IOException
	{
		for (final String bad : new String[]{"{ this is not JSON", "[1,2,3]", "\"text\"", "",
			"{\"schema\":1,\"points\":{}}", "{\"schema\":\"one\",\"points\":[]}", "{\"schema\":1} trailing"})
		{
			final PriceStore store = store();
			final Filepath history = file(store);
			TestFilepaths.write(history, bad);

			assertFalse("[" + bad + "]", store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
			assertFalse("[" + bad + "] is moved aside", history.exists());
			final List<String> quarantined = namesContaining(".corrupt-");
			assertEquals("[" + bad + "]", 1, quarantined.size());
			assertTrue(quarantined.get(0).startsWith("history-42-STANDARD.json.corrupt-"));
			assertEquals("[" + bad + "] keeps its bytes", bad,
				TestFilepaths.read(TestFilepaths.at(tmp.getRoot(), quarantined.get(0))));

			assertFalse("and the owner stays refused", store.recordBankHistory(ACCOUNT, PROFILE,
				point(TODAY, 2L, 2L), TODAY));
			assertFalse(history.exists());
			TestFilepaths.at(tmp.getRoot(), quarantined.get(0)).delete();
		}
	}

	@Test
	public void anUnparseableHistoryLoadsAsFailed() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{ nope");
		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, load.state());
		assertSame(BankHistorySeries.EMPTY, load.series());
		assertEquals(1, namesContaining(".corrupt-").size());
	}

	@Test
	public void anEntryThatDoesNotParseIsSkippedAndTheRestKept() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":1,\"points\":["
			+ entry("2026-09-01", 1L, CELLS_1) + ","
			+ entry("2026-13-45", 1L, CELLS_1) + ","
			+ entry("2026-09-02", 1L, "1,1,1,1,1,1,1") + ","
			+ entry("2026-09-03", 1L, "1,1,1,1,1,1,1,1,1") + ","
			+ entry("2026-09-04", 1L, "1,1,1,one,1,1,1,1") + ","
			+ "{\"day\":\"2026-09-05\",\"bankAtMillis\":1,\"card\":[" + CELLS_1 + "]},"
			+ "{\"day\":\"2026-09-06\",\"readAtMillis\":1,\"bankAtMillis\":1,\"card\":[" + CELLS_1
			+ "],\"guide\":[1,1]},"
			+ "{\"readAtMillis\":1,\"bankAtMillis\":1,\"card\":[" + CELLS_1 + "]},"
			+ "7,null,[],"
			+ "{\"day\":\"2026-09-08\",\"readAtMillis\":1,\"bankAtMillis\":1,\"card\":[" + CELLS_1
			+ "],\"guide\":[2,2,2,2,2,2,2,2]},"
			+ entry("2026-09-09", 1L, CELLS_1)
			+ "]}");

		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertEquals(Arrays.asList(sep(1), sep(8), sep(9)), BankHistorySeriesTest.days(load.series()));
		assertTrue(load.series().on(sep(8)).hasGuide());
		assertTrue("nothing was quarantined", namesContaining(".corrupt-").isEmpty());

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertEquals(Arrays.asList(sep(1), sep(8), sep(9), TODAY),
			BankHistorySeriesTest.days(store.loadBankHistory(ACCOUNT, PROFILE).series()));
	}

	@Test
	public void duplicateDaysInAFileKeepTheLaterReading() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":1,\"points\":["
			+ "{\"day\":\"2026-09-01\",\"readAtMillis\":9,\"bankAtMillis\":1,\"card\":[9,0,0,0,0,0,0,0]},"
			+ "{\"day\":\"2026-09-01\",\"readAtMillis\":3,\"bankAtMillis\":1,\"card\":[3,0,0,0,0,0,0,0]}]}");
		assertEquals(9L, store.loadBankHistory(ACCOUNT, PROFILE).series().on(sep(1)).card(0));
	}

	@Test
	public void aDocumentWithNoPointsLoadsEmpty() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":1}");
		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertTrue(load.series().isEmpty());
	}

	// ---- schemas

	@Test
	public void anOlderSchemaIsMigratedAndKeepsItsPoints() throws IOException
	{
		for (final String header : new String[]{"", "\"schema\":0,"})
		{
			final PriceStore store = store();
			TestFilepaths.write(file(store), "{" + header + "\"points\":[" + entry("2026-09-01", 1L, CELLS_1) + ","
				+ entry("2026-09-02", 2L, CELLS_1) + "]}");

			final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
			assertEquals("[" + header + "]", PriceStore.BankHistoryLoad.State.LOADED, load.state());
			assertEquals(2, load.series().size());

			assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 3L, 3L), TODAY));
			final String written = TestFilepaths.read(file(store));
			assertTrue("rewritten at this build's schema", written.startsWith("{\"schema\":1,"));
			assertEquals(3, store.loadBankHistory(ACCOUNT, PROFILE).series().size());
			file(store).delete();
		}
	}

	@Test
	public void aNewerSchemaIsFailedAndLeftAlone() throws IOException
	{
		final PriceStore store = store();
		final String newer = "{\"schema\":2,\"points\":[" + entry("2026-09-01", 1L, CELLS_1) + "]}";
		TestFilepaths.write(file(store), newer);

		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertEquals("not a byte changed", newer, TestFilepaths.read(file(store)));
		assertTrue("and not moved aside", namesContaining(".corrupt-").isEmpty());
	}

	// ---- no directory

	@Test
	public void withNoDirectoryNothingIsReadOrWrittenAndALaterDirectoryStillWorks()
	{
		final AtomicInteger asks = new AtomicInteger();
		final Filepath root = TestFilepaths.rooted(tmp.getRoot());
		final PriceStore store = new PriceStore(gson, () ->
		{
			if (asks.getAndIncrement() < 2)
			{
				throw new IOException("the disk said no");
			}
			return root;
		});
		assertNull(store.historyFile(ACCOUNT, PROFILE));
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		// The directory answers now. Nothing was ever read, so nothing could be written over: the record reads the
		// file fresh and writes.
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertNotNull(store.historyFile(ACCOUNT, PROFILE));
		assertTrue(store.historyFile(ACCOUNT, PROFILE).exists());
	}

	// ---- the sweep

	@Test
	public void theSweepLeavesHistoryFilesAndTheirBackupsAlone() throws IOException
	{
		final PriceStore store = store();
		store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY);
		store.recordBankHistory(ACCOUNT, "DEADMAN", point(TODAY, 1L, 1L), TODAY);
		TestFilepaths.write(TestFilepaths.at(tmp.getRoot(), "history-42-STANDARD.json.corrupt-123"), "kept");
		TestFilepaths.write(TestFilepaths.at(tmp.getRoot(), "history-1-UNKNOWN.json"), "{ not even JSON");
		TestFilepaths.write(TestFilepaths.at(tmp.getRoot(), PriceStore.LEGACY_LATEST_FILE), "{}");

		assertEquals("only the legacy file goes", 1, store.deleteStaleFiles());
		assertEquals(Arrays.asList("history-1-UNKNOWN.json", "history-42-DEADMAN.json", "history-42-STANDARD.json",
			"history-42-STANDARD.json.corrupt-123"), names());
		assertEquals(0, store.deleteStaleFiles());
	}

	@Test
	public void theLoadIsAValue()
	{
		final PriceStore.BankHistoryLoad a = PriceStore.BankHistoryLoad.loaded(BankHistorySeries.EMPTY);
		assertEquals(a, PriceStore.BankHistoryLoad.loaded(null));
		assertEquals(a.hashCode(), PriceStore.BankHistoryLoad.loaded(null).hashCode());
		assertFalse(a.equals(PriceStore.BankHistoryLoad.missing()));
		assertSame(PriceStore.BankHistoryLoad.failed(), PriceStore.BankHistoryLoad.failed());
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, PriceStore.BankHistoryLoad.failed().state());
		assertFalse(a.toString().isEmpty());
	}
}
