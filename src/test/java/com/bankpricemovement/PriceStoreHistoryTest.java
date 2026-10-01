package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
		final long[] card = {10L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L};
		final long[] guide = {9L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L};
		store.recordBankHistory(ACCOUNT, PROFILE, new BankHistoryPoint(sep(27), read, read - 5L, card, card), TODAY);
		assertEquals("{\"schema\":2,\"points\":[{\"day\":\"2026-09-27\",\"readAtMillis\":1790000000000,"
				+ "\"bankAtMillis\":1789999999995,\"card\":[10,1,2,3,4,5,6,7,8,9]}]}",
			TestFilepaths.read(file(store)));

		store.recordBankHistory(ACCOUNT, PROFILE, new BankHistoryPoint(TODAY, read + 1L, read, card, guide), TODAY);
		assertEquals("{\"schema\":2,\"points\":[{\"day\":\"2026-09-27\",\"readAtMillis\":1790000000000,"
				+ "\"bankAtMillis\":1789999999995,\"card\":[10,1,2,3,4,5,6,7,8,9]},"
				+ "{\"day\":\"2026-09-28\",\"readAtMillis\":1790000000001,\"bankAtMillis\":1790000000000,"
				+ "\"card\":[10,1,2,3,4,5,6,7,8,9],\"guide\":[9,1,2,3,4,5,6,7,8,9]}]}",
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
			assertTrue("rewritten at this build's schema", written.startsWith("{\"schema\":2,"));
			assertEquals(3, store.loadBankHistory(ACCOUNT, PROFILE).series().size());
			file(store).delete();
		}
	}

	/**
	 * 1.0.9 part 3, schema 1 -> 2: a file of eight-cell entries - every history written before the Grand Exchange
	 * offers were counted - loads with the two new cells at zero, is not lossy (nothing was lost by the padding, so no
	 * copy is made of it), and is not touched by the load: its bytes are exactly what they were.
	 */
	@Test
	public void aSchemaOneFileOfEightCellEntriesLoadsPaddedAndItsBytesAreUntouched() throws IOException
	{
		final PriceStore store = store();
		final String old = "{\"schema\":1,\"points\":["
			+ "{\"day\":\"2026-09-26\",\"readAtMillis\":5,\"bankAtMillis\":4,\"card\":[10,1,2,3,4,5,6,7]},"
			+ "{\"day\":\"2026-09-27\",\"readAtMillis\":7,\"bankAtMillis\":6,\"card\":[20,1,2,3,4,5,6,7],"
			+ "\"guide\":[19,1,2,3,4,5,6,7]}]}";
		TestFilepaths.write(file(store), old);

		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);

		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertFalse("padding loses nothing, so the file is not lossy", load.lossy());
		assertEquals(2, load.series().size());
		final BankHistoryPoint first = load.series().on(sep(26));
		final BankHistoryPoint second = load.series().on(sep(27));
		for (int i = 0; i < 8; i++)
		{
			assertEquals("cell " + i, i == 0 ? 10L : i, first.card(i));
		}
		assertEquals("the Grand Exchange tradeable cell reads 0", 0L, first.card(BankHistoryPoint.GE_TRADEABLE));
		assertEquals("and so does its coins cell", 0L, first.card(BankHistoryPoint.GE_CASH));
		assertTrue(second.hasGuide());
		assertEquals(19L, second.guide(0));
		assertEquals("the guide is padded the same way", 0L, second.guide(BankHistoryPoint.GE_CASH));
		assertEquals("a read never writes: not a byte changed", old, TestFilepaths.read(file(store)));
		assertTrue("and nothing was copied aside", namesContaining(".corrupt-").isEmpty());
		assertEquals(Arrays.asList("history-42-STANDARD.json"), names());
	}

	/**
	 * ...and the first recording after it writes schema 2 with ten cells in EVERY entry, the earlier ones padded, so
	 * the document is one shape again.
	 */
	@Test
	public void aRecordingAfterASchemaOneFileWritesSchemaTwoWithTenCellsInEveryEntry() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":1,\"points\":["
			+ "{\"day\":\"2026-09-26\",\"readAtMillis\":5,\"bankAtMillis\":4,\"card\":[10,1,2,3,4,5,6,7]},"
			+ "{\"day\":\"2026-09-27\",\"readAtMillis\":7,\"bankAtMillis\":6,\"card\":[20,1,2,3,4,5,6,7],"
			+ "\"guide\":[19,1,2,3,4,5,6,7]}]}");
		store.loadBankHistory(ACCOUNT, PROFILE);

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 9L, 30L), TODAY));

		final JsonObject root = new JsonParser().parse(TestFilepaths.read(file(store))).getAsJsonObject();
		assertEquals(2, root.get("schema").getAsInt());
		final JsonArray points = root.getAsJsonArray("points");
		assertEquals(3, points.size());
		for (final JsonElement entry : points)
		{
			assertEquals(10, entry.getAsJsonObject().getAsJsonArray("card").size());
			if (entry.getAsJsonObject().has("guide"))
			{
				assertEquals(10, entry.getAsJsonObject().getAsJsonArray("guide").size());
			}
		}
		final JsonArray firstCard = points.get(0).getAsJsonObject().getAsJsonArray("card");
		assertEquals(0L, firstCard.get(8).getAsLong());
		assertEquals(0L, firstCard.get(9).getAsLong());
		assertEquals(10L, firstCard.get(0).getAsLong());
		assertTrue("an earlier day padded in memory is not a reason to copy the file aside",
			namesContaining(".corrupt-").isEmpty());
		final BankHistorySeries back = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(3, back.size());
		assertEquals(38L, back.on(TODAY).card(8));
		assertEquals(39L, back.on(TODAY).card(9));
	}

	/** A schema 2 file with the offers' two cells filled round-trips whole, through a fresh store. */
	@Test
	public void aTenCellSchemaTwoFileRoundTrips() throws IOException
	{
		final PriceStore store = store();
		final long[] card = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 52_000_000L, 1_300_000L};
		final long[] guide = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 51_000_000L, 1_300_000L};
		final BankHistoryPoint p = new BankHistoryPoint(sep(27), 100L, 90L, card, guide);
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, p, TODAY));
		final String written = TestFilepaths.read(file(store));
		assertTrue(written, written.contains("\"card\":[1,2,3,4,5,6,7,8,52000000,1300000]"));
		assertTrue(written, written.contains("\"guide\":[1,2,3,4,5,6,7,8,51000000,1300000]"));

		final PriceStore.BankHistoryLoad load = store().loadBankHistory(ACCOUNT, PROFILE);

		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertFalse(load.lossy());
		assertEquals(p, load.series().on(sep(27)));
		assertEquals(52_000_000L, load.series().on(sep(27)).card(BankHistoryPoint.GE_TRADEABLE));
		assertEquals(51_000_000L, load.series().on(sep(27)).guide(BankHistoryPoint.GE_TRADEABLE));
	}

	/** A nine-cell entry - neither schema's shape - is skipped, and every other entry of the file is kept. */
	@Test
	public void aNineCellEntryIsSkippedAndTheRestKept() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":2,\"points\":["
			+ entry("2026-09-01", 1L, "1,1,1,1,1,1,1,1,1,1") + ","
			+ entry("2026-09-02", 1L, "1,1,1,1,1,1,1,1,1") + ","
			+ entry("2026-09-03", 1L, "1,1,1,1,1,1,1,1") + ","
			+ entry("2026-09-04", 1L, "1,1,1,1,1,1,1,1,1,1,1") + ","
			+ entry("2026-09-05", 1L, "2,2,2,2,2,2,2,2,2,2")
			+ "]}");

		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);

		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertTrue("a skipped entry makes the file lossy", load.lossy());
		assertEquals("ten cells and the padded eight stay; nine and eleven go",
			Arrays.asList(sep(1), sep(3), sep(5)), BankHistorySeriesTest.days(load.series()));
		assertEquals(0L, load.series().on(sep(3)).card(BankHistoryPoint.GE_CASH));
		assertEquals(2L, load.series().on(sep(5)).card(BankHistoryPoint.GE_CASH));
	}

	@Test
	public void aNewerSchemaIsFailedAndLeftAlone() throws IOException
	{
		final PriceStore store = store();
		final String newer = "{\"schema\":3,\"points\":[" + entry("2026-09-01", 1L, CELLS_1) + "]}";
		TestFilepaths.write(file(store), newer);

		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertEquals("not a byte changed", newer, TestFilepaths.read(file(store)));
		assertTrue("and not moved aside", namesContaining(".corrupt-").isEmpty());
	}

	// ---- 1.0.9 part 5: the fresh start

	/** A schema 1 file of two eight-cell days, 26 and 27 September. */
	private void writeSchemaOne(final PriceStore store) throws IOException
	{
		TestFilepaths.write(file(store), "{\"schema\":1,\"points\":["
			+ "{\"day\":\"2026-09-26\",\"readAtMillis\":5,\"bankAtMillis\":4,\"card\":[10,1,2,3,4,5,6,7]},"
			+ "{\"day\":\"2026-09-27\",\"readAtMillis\":7,\"bankAtMillis\":6,\"card\":[20,1,2,3,4,5,6,7]}]}");
	}

	private JsonObject writtenRoot(final PriceStore store) throws IOException
	{
		return new JsonParser().parse(TestFilepaths.read(file(store))).getAsJsonObject();
	}

	/** Every day of a migrated file is a legacy day, until something is recorded into it: every load answers MAX. */
	@Test
	public void aSchemaOneFileLoadsWithEveryDayLegacyAndNoneOfItsBytesChange() throws IOException
	{
		final PriceStore store = store();
		writeSchemaOne(store);
		final String before = TestFilepaths.read(file(store));

		for (int i = 0; i < 2; i++)
		{
			final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
			assertEquals(2, series.size());
			assertEquals(LocalDate.MAX, series.freshFrom());
			assertTrue(series.hasLegacyDays());
			assertTrue(series.fromFresh().isEmpty());
		}
		assertEquals("a read never writes", before, TestFilepaths.read(file(store)));
		assertTrue(namesContaining(".corrupt-").isEmpty());
	}

	/** A file with no schema key is as old as schema 1 and reads the same way. */
	@Test
	public void aSchemaZeroFileLoadsWithEveryDayLegacy() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"points\":[" + entry("2026-09-01", 1L, CELLS_1) + "]}");
		assertEquals(LocalDate.MAX, store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	/** An old file with no days has nothing to hide. */
	@Test
	public void anEmptySchemaOneFileHasNoFreshStart() throws IOException
	{
		for (final String body : new String[]{"{\"schema\":1}", "{\"schema\":1,\"points\":[]}"})
		{
			final PriceStore store = store();
			TestFilepaths.write(file(store), body);
			final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
			assertTrue(series.isEmpty());
			assertNull(body, series.freshFrom());
			assertFalse(series.hasLegacyDays());
			file(store).delete();
		}
	}

	/** The first recording after a migration writes schema 2 with the day of that reading as {@code freshFrom}. */
	@Test
	public void theFirstRecordingAfterAMigrationStampsItsDayAsTheFreshStart() throws IOException
	{
		final PriceStore store = store();
		writeSchemaOne(store);

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 9L, 30L), TODAY));

		final JsonObject root = writtenRoot(store);
		assertEquals(2, root.get("schema").getAsInt());
		assertEquals("the day of this first 1.0.9 reading", "2026-09-28", root.get("freshFrom").getAsString());
		assertTrue("schema, then the fresh start, then the days",
			TestFilepaths.read(file(store)).startsWith("{\"schema\":2,\"freshFrom\":\"2026-09-28\",\"points\":["));
		final JsonArray points = root.getAsJsonArray("points");
		assertEquals("the old days stay in the file", 3, points.size());
		assertEquals(10, points.get(0).getAsJsonObject().getAsJsonArray("card").size());
		assertEquals(10L, points.get(0).getAsJsonObject().getAsJsonArray("card").get(0).getAsLong());

		final BankHistorySeries back = store().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(28), back.freshFrom());
		assertEquals(3, back.size());
		assertTrue(back.hasLegacyDays());
		assertEquals(Arrays.asList(sep(28)), BankHistorySeriesTest.days(back.fromFresh()));
	}

	/**
	 * A reading on the SAME calendar day as the newest old day replaces that day (last wins, as ever) and the fresh
	 * start is that day: it was recorded by this build, so it counts the offers and shows.
	 */
	@Test
	public void aRecordingOnTheNewestOldDaysOwnDateReplacesItAndIsFresh() throws IOException
	{
		final PriceStore store = store();
		writeSchemaOne(store);

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(27), 99L, 70L), sep(27)));

		final BankHistorySeries back = store().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(27), back.freshFrom());
		assertEquals(2, back.size());
		assertEquals("the old reading of that day is gone", 70L, back.on(sep(27)).card(0));
		assertEquals(Arrays.asList(sep(26)), BankHistorySeriesTest.days(back.upTo(sep(26))));
		assertEquals(Arrays.asList(sep(27)), BankHistorySeriesTest.days(back.fromFresh()));
		assertEquals(10L, back.on(sep(26)).card(0));
	}

	/** Later recordings keep the stored date: the fresh start is where 1.0.9 began, not where the last reading is. */
	@Test
	public void aLaterRecordingKeepsTheStoredFreshStart() throws IOException
	{
		final PriceStore store = store();
		writeSchemaOne(store);
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 9L, 30L), TODAY));

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(29), 10L, 31L), sep(29)));
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(29), 11L, 32L), sep(29)));
		assertTrue(store().recordBankHistory(ACCOUNT, PROFILE, point(sep(30), 12L, 33L), sep(30)));

		assertEquals("2026-09-28", writtenRoot(store).get("freshFrom").getAsString());
		final BankHistorySeries back = store().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(28), back.freshFrom());
		assertEquals(Arrays.asList(sep(28), sep(29), sep(30)), BankHistorySeriesTest.days(back.fromFresh()));
		assertEquals(5, back.size());
	}

	/** An unchanged reading into a migrated file still writes it - the marker is a change - and does it once. */
	@Test
	public void aRecordingThatChangesNoDayStillStampsAMigratedFile() throws IOException
	{
		final PriceStore store = store();
		writeSchemaOne(store);
		final BankHistoryPoint same = new BankHistoryPoint(sep(27), 7L, 6L,
			new long[]{20L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 0L, 0L}, null);

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, same, sep(27)));

		assertEquals("2026-09-27", writtenRoot(store).get("freshFrom").getAsString());
	}

	/** A schema 2 file with a {@code freshFrom} reads it and writes it back, through a fresh store. */
	@Test
	public void aSchemaTwoFileWithAFreshStartRoundTripsIt() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":2,\"freshFrom\":\"2026-09-27\",\"points\":["
			+ entry("2026-09-25", 1L, "1,1,1,1,1,1,1,1,1,1") + "," + entry("2026-09-27", 2L, "2,2,2,2,2,2,2,2,2,2")
			+ "]}");

		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);

		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertFalse("freshFrom is a known root key", load.lossy());
		assertEquals(sep(27), load.series().freshFrom());
		assertTrue(load.series().hasLegacyDays());
		assertEquals(Arrays.asList(sep(27)), BankHistorySeriesTest.days(load.series().fromFresh()));

		assertTrue(store().recordBankHistory(ACCOUNT, PROFILE, point(sep(28), 3L, 3L), sep(28)));
		assertEquals("2026-09-27", writtenRoot(store).get("freshFrom").getAsString());
		assertTrue("a known key does not copy the file aside", namesContaining(".corrupt-").isEmpty());
		assertEquals(sep(27), store().loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	/** A schema 2 file without a {@code freshFrom} has no legacy days: every day counts. */
	@Test
	public void aSchemaTwoFileWithoutAFreshStartHasNoLegacyDays() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":2,\"points\":[" + entry("2026-09-25", 1L, "1,1,1,1,1,1,1,1,1,1")
			+ "]}");

		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertNull(series.freshFrom());
		assertFalse(series.hasLegacyDays());

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(28), 3L, 3L), sep(28)));
		assertFalse("none is written for a file that never had one", writtenRoot(store).has("freshFrom"));
		assertNull(store().loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	/** Nothing is recorded into a file that is not there yet with a fresh start: a new install has no legacy days. */
	@Test
	public void aNewFileHasNoFreshStart() throws IOException
	{
		final PriceStore store = store();
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 1L), TODAY));
		assertFalse(writtenRoot(store).has("freshFrom"));
		assertNull(store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	/** A fresh start that is not a date is not understood: nothing is hidden by it, and the file is copied aside first. */
	@Test
	public void aFreshStartThatIsNotADateIsDroppedAfterACopyIsKept() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(file(store), "{\"schema\":2,\"freshFrom\":\"soon\",\"points\":["
			+ entry("2026-09-25", 1L, "1,1,1,1,1,1,1,1,1,1") + "]}");

		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertTrue(load.lossy());
		assertNull(load.series().freshFrom());

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(28), 3L, 3L), sep(28)));
		assertEquals(1, namesContaining(".corrupt-").size());
		assertFalse(writtenRoot(store).has("freshFrom"));
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
