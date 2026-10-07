package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The history file's review fixes (addendum AU, review of 65c7e4d / 1bbd683): the ways a store that obeyed every
 * rule of contract section 4 could still lose readings.
 * <ul>
 * <li>H1 - a clock set BACK must not prune every stored reading as "the future".</li>
 * <li>H2 - a file whose existence cannot be told is read, never taken for missing and written over.</li>
 * <li>H6 - the history's bytes reach the disk before the atomic move.</li>
 * <li>H7 - a file holding entries this build cannot keep is copied aside before its first rewrite.</li>
 * <li>H8 - an owner whose file failed is not read (and warned about) again this session.</li>
 * </ul>
 * Real disk through {@link TestFilepaths}; where a test must see what {@link Filepath} is ASKED (H2, H6), the store's
 * directory is a Mockito spy of the real one whose every joined path is a spy too.
 */
public class PriceStoreHistoryReviewTest
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

	private static BankHistoryPoint point(final LocalDate day, final long readAt, final long base)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		Arrays.fill(card, base);
		return new BankHistoryPoint(day, readAt, readAt, card, null);
	}

	private static String entry(final LocalDate day, final long readAt)
	{
		return "{\"day\":\"" + day + "\",\"readAtMillis\":" + readAt + ",\"bankAtMillis\":" + readAt
			+ ",\"card\":[1,1,1,1,1,1,1,1]}";
	}

	/** A document holding one reading for every day from {@code from} to {@code to}, both included. */
	private static String days(final LocalDate from, final LocalDate to)
	{
		final StringBuilder json = new StringBuilder("{\"schema\":1,\"points\":[");
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
		{
			if (!day.equals(from))
			{
				json.append(',');
			}
			json.append(entry(day, day.toEpochDay()));
		}
		return json.append("]}").toString();
	}

	private List<String> namesContaining(final String part)
	{
		final List<String> found = new ArrayList<>();
		final String[] names = tmp.getRoot().list();
		for (final String name : names == null ? new String[0] : names)
		{
			if (name.contains(part))
			{
				found.add(name);
			}
		}
		found.sort(null);
		return found;
	}

	// ---- H1: a clock behind the file

	/**
	 * A PC whose clock falls back to 01 Jan 2000 (a dead CMOS battery) files its first reading under 2000; the 28
	 * readings of September 2026 are then all "after today". Pruning them would replace the file with one point.
	 */
	@Test
	public void aClockSetBackBehindTheStoredReadingsWritesNothing() throws IOException
	{
		final PriceStore store = store();
		final Filepath file = store.historyFile(ACCOUNT, PROFILE);
		final String september = days(sep(1), sep(28));
		TestFilepaths.write(file, september);

		final LocalDate y2k = LocalDate.of(2000, 1, 1);
		assertFalse(store.recordBankHistory(ACCOUNT, PROFILE, point(y2k, 1L, 5L), y2k, null));
		assertEquals("not a byte changed", september, TestFilepaths.read(file));

		assertTrue("the clock put right, it records again",
			store.recordBankHistory(ACCOUNT, PROFILE, point(sep(29), 2L, 5L), sep(29), null));
		assertEquals(29, store.loadBankHistory(ACCOUNT, PROFILE).series().size());
	}

	/**
	 * The one reading a traveller's clock put a day ahead is not a reason to stop: as long as the readings ahead are
	 * fewer than the ones on or before today (the new one counted), they are dropped as plan 7.1 item 7 says.
	 */
	@Test
	public void aFewReadingsAheadOfTheClockAreStillDropped() throws IOException
	{
		final PriceStore store = store();
		final Filepath file = store.historyFile(ACCOUNT, PROFILE);
		TestFilepaths.write(file, days(sep(20), sep(30)));
		// 20..27 and today: 9 on or before today; 29 and 30: 2 ahead.
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));
		final BankHistorySeries s = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(9, s.size());
		assertEquals(TODAY, s.last().day());

		// As many ahead as on or before today (the new one counted: one and one) is a clock behind the file.
		final Filepath other = store.historyFile(ACCOUNT, "DEADMAN");
		final String even = days(sep(26), sep(26));
		TestFilepaths.write(other, even);
		assertFalse(store.recordBankHistory(ACCOUNT, "DEADMAN", point(sep(25), 1L, 5L), sep(25), null));
		assertEquals(even, TestFilepaths.read(other));
	}

	// ---- H2: existence that cannot be told

	/**
	 * {@code Files.exists} answers false when it cannot tell - a transient error, a refused attribute read. A file
	 * read as missing on that word is a file the record writes a one-point document over. The store must open it.
	 */
	@Test
	public void aFileWhoseExistenceCannotBeToldIsReadNotReplaced() throws IOException
	{
		final Watched watched = new Watched();
		final PriceStore store = new PriceStore(gson, watchedRoot(watched));
		final Filepath file = store.historyFile(ACCOUNT, PROFILE);
		TestFilepaths.write(TestFilepaths.at(tmp.getRoot(), file.getFileName()), days(sep(1), sep(27)));
		watched.hideHistory = true;
		assertFalse("the premise: the store's own path says the file is not there", file.exists());

		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));

		assertEquals("every September reading kept", 28, store().loadBankHistory(ACCOUNT, PROFILE).series().size());
	}

	/** And a file that really is not there is still MISSING - the open's own NoSuchFileException says so. */
	@Test
	public void aFileThatIsNotThereIsStillMissing()
	{
		final PriceStore inMissingFolder = new PriceStore(gson, TestFilepaths.at(tmp.getRoot(), "not-made-yet"));
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING,
			inMissingFolder.loadBankHistory(ACCOUNT, PROFILE).state());
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, store().loadBankHistory(ACCOUNT, PROFILE).state());
		assertTrue(store().recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));
	}

	// ---- H6: durable

	/** The history's temp file is written through DSYNC; the caches' are not (they are fetched again). */
	@Test
	public void theHistoryIsWrittenThroughToTheDiskBeforeItsMove()
	{
		final Watched watched = new Watched();
		final PriceStore store = new PriceStore(gson, watchedRoot(watched));
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));
		store.saveBank(new BankSnapshot(new ArrayList<BankItem>(), 5L, ACCOUNT, PROFILE));

		final List<String> historyTemps = new ArrayList<>();
		final List<String> bankTemps = new ArrayList<>();
		for (final Written written : watched.writes)
		{
			(written.name.startsWith(PriceStore.HISTORY_PREFIX) ? historyTemps : bankTemps).add(written.name);
			if (written.name.startsWith(PriceStore.HISTORY_PREFIX))
			{
				assertArrayEquals(written.name, new OpenOption[]{StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
					StandardOpenOption.DSYNC}, written.options);
			}
			else
			{
				assertFalse(written.name, Arrays.asList(written.options).contains(StandardOpenOption.DSYNC));
			}
		}
		assertEquals("one history temp file: " + historyTemps, 1, historyTemps.size());
		assertTrue(historyTemps.get(0).endsWith(".tmp"));
		assertEquals("one bank temp file: " + bankTemps, 1, bankTemps.size());
		assertEquals(1, store.loadBankHistory(ACCOUNT, PROFILE).series().size());
	}

	// ---- H7: what a rewrite would drop is copied aside first

	/**
	 * An entry that does not parse is skipped on the read (contract section 4) and would be gone after the rewrite;
	 * so would a key this build does not know. The first rewrite of the session copies the file as it was.
	 */
	@Test
	public void aFileWithEntriesThisBuildCannotKeepIsCopiedAsideBeforeItsFirstRewrite() throws IOException
	{
		final PriceStore store = store();
		final Filepath file = store.historyFile(ACCOUNT, PROFILE);
		final String damaged = "{\"schema\":1,\"points\":[" + entry(sep(1), 1L) + ",{\"day\":\"2026-09-02\"}]}";
		TestFilepaths.write(file, damaged);

		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));
		final List<String> copies = namesContaining(".corrupt-");
		assertEquals(1, copies.size());
		assertTrue(copies.get(0).startsWith("history-42-STANDARD.json.corrupt-"));
		assertEquals("the copy is the file as it was", damaged,
			TestFilepaths.read(TestFilepaths.at(tmp.getRoot(), copies.get(0))));
		assertEquals(Arrays.asList(sep(1), TODAY), BankHistorySeriesTest.days(store.loadBankHistory(ACCOUNT, PROFILE)
			.series()));

		// Once a session is enough: the copy already holds what this build could not keep.
		TestFilepaths.write(file, damaged);
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 2L, 6L), TODAY, null));
		assertEquals(1, namesContaining(".corrupt-").size());
	}

	@Test
	public void unknownKeysAndDuplicateDaysAreKeptInTheCopyToo() throws IOException
	{
		final String[] kinds = {
			"{\"schema\":1,\"note\":\"a newer build's\",\"points\":[" + entry(sep(1), 1L) + "]}",
			"{\"schema\":1,\"points\":[{\"day\":\"2026-09-01\",\"readAtMillis\":1,\"bankAtMillis\":1,"
				+ "\"card\":[1,1,1,1,1,1,1,1],\"items\":12}]}",
			"{\"schema\":1,\"points\":[" + entry(sep(1), 1L) + "," + entry(sep(1), 2L) + "]}"};
		for (final String kind : kinds)
		{
			final PriceStore store = store();
			final Filepath file = store.historyFile(ACCOUNT, PROFILE);
			TestFilepaths.write(file, kind);
			assertTrue(kind, store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 1L, 5L), TODAY, null));
			final List<String> copies = namesContaining(".corrupt-");
			assertEquals(kind, 1, copies.size());
			final Filepath copy = TestFilepaths.at(tmp.getRoot(), copies.get(0));
			assertEquals(kind, kind, TestFilepaths.read(copy));
			copy.delete();
			file.delete();
		}
	}

	/** A file this build writes itself holds nothing it cannot keep: no copy is ever made of it. */
	@Test
	public void aCleanFileIsRewrittenWithNoCopy()
	{
		final PriceStore store = store();
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(27), 1L, 5L), TODAY, null));
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 2L, 5L), TODAY, null));
		assertTrue(store().recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 3L, 6L), TODAY, null));
		assertTrue(namesContaining(".corrupt-").isEmpty());
	}

	// ---- H8: one WARN

	/** A failed owner answers FAILED for the rest of the session without its file being read (or warned about) again. */
	@Test
	public void anOwnerWhoseFileFailedIsNotReadAgainThisSession() throws IOException
	{
		final PriceStore store = store();
		final Filepath file = store.historyFile(ACCOUNT, PROFILE);
		file.createDirectories();
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());

		file.delete();
		TestFilepaths.write(file, days(sep(1), sep(2)));
		assertEquals(PriceStore.BankHistoryLoad.State.FAILED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertEquals("a restart reads it", PriceStore.BankHistoryLoad.State.LOADED,
			store().loadBankHistory(ACCOUNT, PROFILE).state());
	}

	// ---- the spy

	/** What {@link #watchedRoot} saw, and the one lie it can tell. */
	private static final class Watched
	{
		final List<Written> writes = new ArrayList<>();
		/** When set, every {@code history-*} path answers {@code exists()} false - "cannot tell". */
		boolean hideHistory;
	}

	private static final class Written
	{
		final String name;
		final OpenOption[] options;

		Written(final String name, final OpenOption[] options)
		{
			this.name = name;
			this.options = options;
		}
	}

	/**
	 * The temporary folder as a store root that watches its children: every path joined onto it is a spy of the real
	 * one, reporting its {@code write} options and answering {@code getParent()} with this root, so a temp file the
	 * store names beside its target is watched too.
	 */
	private Filepath watchedRoot(final Watched watched)
	{
		final Filepath real = TestFilepaths.rooted(tmp.getRoot());
		final Filepath[] root = new Filepath[1];
		root[0] = mock(Filepath.class, withSettings().spiedInstance(real).defaultAnswer(invocation ->
			"joinSegment".equals(invocation.getMethod().getName())
				? watch((Filepath) invocation.callRealMethod(), root[0], watched)
				: invocation.callRealMethod()));
		return root[0];
	}

	private static Filepath watch(final Filepath real, final Filepath parent, final Watched watched)
	{
		final String name = real.getFileName();
		return mock(Filepath.class, withSettings().spiedInstance(real).defaultAnswer(invocation ->
		{
			switch (invocation.getMethod().getName())
			{
				case "getParent":
					return parent;
				case "exists":
					if (watched.hideHistory && name.startsWith(PriceStore.HISTORY_PREFIX))
					{
						return false;
					}
					return invocation.callRealMethod();
				case "write":
					watched.writes.add(new Written(name, (OpenOption[]) invocation.getRawArguments()[1]));
					return invocation.callRealMethod();
				default:
					return invocation.callRealMethod();
			}
		}));
	}
}
