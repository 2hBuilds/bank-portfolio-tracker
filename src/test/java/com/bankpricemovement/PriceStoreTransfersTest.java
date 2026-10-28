package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The two things release 1.2.2 adds to the history's store (contract {@code contract-1.2.2-transfers-and-start-fresh-
 * 2026-10-25.md}, F1 and F4): the player's transfer notes in a file of their own beside the history, and the player's
 * own fresh start in the history file's existing {@code freshFrom} root key.
 *
 * <p>The notes: the exact document, read with the history and written only on a press, a missing file reading as no
 * notes, a file that does not parse moved aside and read as none (never deleted), an unreadable one or a newer build's
 * left exactly where it is and never written over, a note another client wrote kept, and the sweep never matching it.
 * The fresh start: {@code freshFrom} and the reason {@code "player"} written with every reading left alone, the cut
 * moving earlier as well as later.
 *
 * <p>Real disk through {@link TestFilepaths}, as {@link PriceStoreHistoryTest}; a directory standing where a file
 * should be is the portable way to make a read fail.
 */
public class PriceStoreTransfersTest
{
	private static final long ACCOUNT = 42L;
	private static final String PROFILE = "STANDARD";
	private static final LocalDate TODAY = sep(28);
	private static final BankHistorySeries.Transfer SENT = new BankHistorySeries.Transfer(-200_000_000L, true);
	private static final String SENT_DOC = "{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-20\",\"gp\":-200000000,"
		+ "\"coins\":true}]}";

	private final Gson gson = new Gson();

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private PriceStore store()
	{
		return new PriceStore(gson, TestFilepaths.rooted(tmp.getRoot()));
	}

	private Filepath notes(final PriceStore store)
	{
		return store.notesFile(ACCOUNT, PROFILE);
	}

	private Filepath history(final PriceStore store)
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

	private static BankHistoryPoint point(final LocalDate day, final long readAt, final long gp)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = gp;
		return new BankHistoryPoint(day, readAt, readAt, card, null);
	}

	/** A history of the days 18 to 28 Sep, each worth its day number, written through the store. */
	private PriceStore withHistory()
	{
		final PriceStore store = store();
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(day), day, day), TODAY, null));
		}
		return store;
	}

	// ---- the file's name

	@Test
	public void theNotesFilePairsWithTheHistoryFile()
	{
		final PriceStore store = store();
		assertEquals("notes-", PriceStore.NOTES_PREFIX);
		assertEquals("notes-42-STANDARD.json", notes(store).getFileName());
		assertEquals("the same safeProfile as the history", "notes-7-______etc.json",
			store.notesFile(7L, "../../etc").getFileName());
		assertEquals("notes-9-UNKNOWN.json", store.notesFile(9L, null).getFileName());
		for (final String profile : new String[]{"STANDARD", "../../etc", " ", null, "LEAGUE"})
		{
			assertEquals(store.historyFile(9L, profile).getFileName().substring(PriceStore.HISTORY_PREFIX.length()),
				store.notesFile(9L, profile).getFileName().substring(PriceStore.NOTES_PREFIX.length()));
			assertEquals(store.dir(), store.notesFile(9L, profile).getParent());
		}
	}

	// ---- reading: nothing is written, nothing is invented

	@Test
	public void noNotesFileIsNoNotesAndAReadCreatesNothing()
	{
		final PriceStore store = withHistory();
		final List<String> before = names();
		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, load.state());
		assertTrue(load.series().transfers().isEmpty());
		assertEquals("a load writes nothing", before, names());
		assertFalse(notes(store).exists());
	}

	@Test
	public void aNoteIsWrittenInTheDocumentedShapeAndLoadsBackOnTheSeries() throws IOException
	{
		final PriceStore store = withHistory();
		final String historyBefore = TestFilepaths.read(history(store));

		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));

		assertEquals(SENT_DOC, TestFilepaths.read(notes(store)));
		assertEquals("the history file is not touched", historyBefore, TestFilepaths.read(history(store)));
		final PriceStore.BankHistoryLoad load = store.loadBankHistory(ACCOUNT, PROFILE);
		assertEquals(Collections.singletonMap(sep(20), SENT), load.series().transfers());
		assertEquals("11 readings, as before", 11, load.series().size());
		assertEquals("a new session reads the same", Collections.singletonMap(sep(20), SENT),
			store().loadBankHistory(ACCOUNT, PROFILE).series().transfers());
	}

	@Test
	public void theNotesRideOnTheSeriesAndTheSeriesIsStillTheFilesPlusThem()
	{
		final PriceStore store = withHistory();
		final BankHistorySeries bare = store.loadBankHistory(ACCOUNT, PROFILE).series();
		store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT);
		final BankHistorySeries noted = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(bare.withTransfer(sep(20), SENT), noted);
		assertEquals(bare.points(), noted.points());
	}

	@Test
	public void aHistoryWithNoReadingsCarriesNoNotesAndANoteOnAMissingHistoryStaysInert()
	{
		final PriceStore store = store();
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertEquals(PriceStore.BankHistoryLoad.State.MISSING, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertSame(BankHistorySeries.EMPTY, store.loadBankHistory(ACCOUNT, PROFILE).series());
		assertTrue("kept for the day a history is there", notes(store).exists());
	}

	@Test
	public void aNoteOnADayWithNoReadingIsInert()
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(3), SENT));
		assertTrue("stored", notes(store).exists());
		assertTrue("but it shifts nothing: the series holds no reading of that day",
			store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
	}

	@Test
	public void theNotesDoNotChangeWhetherTheHistoryIsLossy() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT);
		final String doc = TestFilepaths.read(history(store));
		TestFilepaths.write(history(store), doc.replace("\"schema\":3,", "\"schema\":3,\"extra\":1,"));
		assertTrue("a key this build does not know is lossy, with the notes attached too",
			store.loadBankHistory(ACCOUNT, PROFILE).lossy());
		TestFilepaths.write(history(store), doc);
		assertFalse(store.loadBankHistory(ACCOUNT, PROFILE).lossy());
	}

	// ---- writing: only on a press

	@Test
	public void aNoteIsChangedAndTakenAwayAndTheFileIsKept() throws IOException
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertTrue("changed", store.recordTransfer(ACCOUNT, PROFILE, sep(20), new BankHistorySeries.Transfer(150L, false)));
		assertEquals("{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-20\",\"gp\":150,\"coins\":false}]}",
			TestFilepaths.read(notes(store)));
		assertTrue("a second day", store.recordTransfer(ACCOUNT, PROFILE, sep(22), SENT));
		assertEquals("ascending by day", Arrays.asList(sep(20), sep(22)),
			new ArrayList<>(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().keySet()));
		assertTrue("taken away", store.recordTransfer(ACCOUNT, PROFILE, sep(20), null));
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(22), null));
		assertEquals("none left: an empty list, the file stays", "{\"schema\":1,\"transfers\":[]}",
			TestFilepaths.read(notes(store)));
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
	}

	@Test
	public void aNoteOf0GpTakesTheNoteAway()
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT);
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), new BankHistorySeries.Transfer(0L, true)));
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
	}

	@Test
	public void takingAwayANoteThatIsNotThereWritesNothing()
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), null));
		assertFalse(notes(store).exists());
	}

	@Test
	public void theSameNoteAgainIsNotWritten() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT);
		// The same document spelled with spaces: were it rewritten, the spaces would be gone.
		final String spaced = SENT_DOC.replace(",", ", ");
		TestFilepaths.write(notes(store), spaced);
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertEquals(spaced, TestFilepaths.read(notes(store)));
	}

	@Test
	public void aNoteAnotherClientWroteIsKept() throws IOException
	{
		final PriceStore first = withHistory();
		final PriceStore second = store();
		assertTrue(first.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertTrue("the second store re-reads before it writes", second.recordTransfer(ACCOUNT, PROFILE, sep(22),
			new BankHistorySeries.Transfer(75L, false)));
		assertEquals(Arrays.asList(sep(20), sep(22)),
			new ArrayList<>(first.loadBankHistory(ACCOUNT, PROFILE).series().transfers().keySet()));
	}

	@Test
	public void accountsAndProfilesKeepTheirOwnNotes()
	{
		final PriceStore store = store();
		store.recordTransfer(42L, "STANDARD", sep(20), SENT);
		store.recordTransfer(42L, "DEADMAN", sep(20), SENT);
		store.recordTransfer(43L, "STANDARD", sep(20), SENT);
		assertEquals(Arrays.asList("notes-42-DEADMAN.json", "notes-42-STANDARD.json", "notes-43-STANDARD.json"), names());
	}

	@Test
	public void anAccountWithNoOwnerIsRefused()
	{
		final PriceStore store = store();
		assertFalse(store.recordTransfer(0L, PROFILE, sep(20), SENT));
		assertFalse(store.recordTransfer(-1L, PROFILE, sep(20), SENT));
		assertFalse(store.recordTransfer(ACCOUNT, PROFILE, null, SENT));
		assertTrue(names().isEmpty());
		assertTrue("a negative hash is an account (1.0.10)", store.recordTransfer(-7_123L, PROFILE, sep(20), SENT));
		assertEquals(Collections.singletonList("notes--7123-STANDARD.json"), names());
	}

	@Test
	public void theDirectoryIsCreatedOnTheFirstNote()
	{
		final Filepath missing = TestFilepaths.at(tmp.getRoot(), "never-used");
		final PriceStore store = new PriceStore(gson, missing);
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
		assertFalse(missing.exists());
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertTrue(store.notesFile(ACCOUNT, PROFILE).exists());
	}

	@Test
	public void anUnreadableNotesFileIsNeverWrittenOverAndItsOwnerIsRefusedForTheSession() throws IOException
	{
		final PriceStore store = withHistory();
		final Filepath notes = notes(store);
		notes.createDirectories();

		assertTrue("a history with notes that cannot be read still loads", store.loadBankHistory(ACCOUNT, PROFILE)
			.series().transfers().isEmpty());
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertFalse(store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertTrue("the obstacle is untouched", notes.isDirectory());
		assertTrue("nothing was moved aside - an unreadable file says nothing about its content",
			namesContaining(".corrupt-").isEmpty());
		notes.delete();
		assertFalse("refused for the rest of the session, even once the obstacle is gone",
			store.recordTransfer(ACCOUNT, PROFILE, sep(21), SENT));
		assertFalse(notes.exists());
		assertTrue("another owner is unaffected", store.recordTransfer(43L, PROFILE, sep(20), SENT));
		assertTrue("a restart reads and writes again", store().recordTransfer(ACCOUNT, PROFILE, sep(21), SENT));
	}

	// ---- a file that does not parse, a newer build's, entries that do not read

	@Test
	public void aNotesFileThatDoesNotParseIsKeptAsideAndReadAsNoNotes() throws IOException
	{
		final PriceStore store = withHistory();
		TestFilepaths.write(notes(store), "this is not json {");

		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
		assertEquals("moved aside, not deleted", 1, namesContaining("notes-42-STANDARD.json.corrupt-").size());
		assertFalse(notes(store).exists());

		assertTrue("the next press writes a new one", store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertEquals(SENT_DOC, TestFilepaths.read(notes(store)));
		assertEquals("and the copy is still there", 1, namesContaining(".corrupt-").size());
	}

	/**
	 * Kept aside means KEPT: when the move cannot be made (here the name the copy would take is a folder with something in it), the
	 * file stays where it is, is treated as an unreadable one - nothing is written over it, for the rest of the session - and a
	 * restart, whose move works, puts it aside and starts a new one.
	 */
	@Test
	public void aNotesFileThatCannotBeMovedAsideIsNeverWrittenOver() throws IOException
	{
		final PriceStore store = withHistory();
		final String bad = "this is not json {";
		TestFilepaths.write(notes(store), bad);
		store.asideMillis = () -> 7L;
		final Filepath taken = store.dir().joinSegment("notes-42-STANDARD.json.corrupt-7");
		TestFilepaths.write(taken.joinSegment("held"), "x");

		assertTrue("the history still loads", store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
		assertEquals(PriceStore.BankHistoryLoad.State.LOADED, store.loadBankHistory(ACCOUNT, PROFILE).state());
		assertEquals("nothing was moved: the file is where it was", bad, TestFilepaths.read(notes(store)));
		assertFalse("a press writes nothing over it", store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT));
		assertEquals(bad, TestFilepaths.read(notes(store)));
		taken.joinSegment("held").delete();
		taken.delete();
		assertFalse("refused for the rest of the session, even once the name is free",
			store.recordTransfer(ACCOUNT, PROFILE, sep(21), SENT));
		assertEquals(bad, TestFilepaths.read(notes(store)));
		assertTrue("another owner is unaffected", store.recordTransfer(43L, PROFILE, sep(20), SENT));

		assertTrue("a restart puts it aside and the next press writes a new one",
			store().recordTransfer(ACCOUNT, PROFILE, sep(21), SENT));
		assertEquals(1, namesContaining("notes-42-STANDARD.json.corrupt-").size());
		assertEquals(Collections.singletonMap(sep(21), SENT), store().loadBankHistory(ACCOUNT, PROFILE).series().transfers());
	}

	@Test
	public void aDocumentThatIsNotAnObjectAndAnEmptyOneAreKeptAsideToo() throws IOException
	{
		// One owner each: a copy is named by the millisecond, and two in one would be one.
		final PriceStore store = store();
		final String[] documents = {"[1,2]", "", "{\"schema\":1,\"transfers\":7}"};
		for (int i = 0; i < documents.length; i++)
		{
			final long account = ACCOUNT + i;
			assertTrue(store.recordBankHistory(account, PROFILE, point(TODAY, 1L, 1L), TODAY, null));
			TestFilepaths.write(store.notesFile(account, PROFILE), documents[i]);
			assertTrue(store.loadBankHistory(account, PROFILE).series().transfers().isEmpty());
			assertEquals("document " + i, 1, namesContaining("notes-" + account + "-STANDARD.json.corrupt-").size());
			assertFalse(store.notesFile(account, PROFILE).exists());
		}
	}

	@Test
	public void aNewerBuildsNotesAreLeftAlone() throws IOException
	{
		final PriceStore store = withHistory();
		final String newer = "{\"schema\":2,\"transfers\":[{\"day\":\"2026-09-20\",\"gp\":-5,\"coins\":false,\"who\":\"a\"}]}";
		TestFilepaths.write(notes(store), newer);
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().transfers().isEmpty());
		assertFalse(store.recordTransfer(ACCOUNT, PROFILE, sep(21), SENT));
		assertEquals(newer, TestFilepaths.read(notes(store)));
		assertTrue(namesContaining(".corrupt-").isEmpty());
	}

	@Test
	public void anEntryThatDoesNotReadPutsTheWholeFileAsideAndTheNextPressStartsANewOne() throws IOException
	{
		final String[] entries = {"{\"day\":\"nope\",\"gp\":3}", "{\"gp\":3}", "7",
			"{\"day\":\"2026-09-22\",\"gp\":\"much\"}"};
		for (int i = 0; i < entries.length; i++)
		{
			// One owner each: a copy is named by the millisecond, and two in one would be one.
			final long account = ACCOUNT + i;
			final PriceStore store = store();
			assertTrue(store.recordBankHistory(account, PROFILE, point(sep(20), 1L, 1L), TODAY, null));
			TestFilepaths.write(store.notesFile(account, PROFILE), "{\"schema\":1,\"transfers\":["
				+ "{\"day\":\"2026-09-20\",\"gp\":-5}," + entries[i] + "]}");

			assertTrue("entry " + i, store.loadBankHistory(account, PROFILE).series().transfers().isEmpty());
			assertEquals("what was there is kept", 1,
				namesContaining("notes-" + account + "-STANDARD.json.corrupt-").size());
			assertTrue(store.recordTransfer(account, PROFILE, sep(20), SENT));
			assertEquals(Collections.singletonMap(sep(20), SENT),
				store.loadBankHistory(account, PROFILE).series().transfers());
		}
	}

	@Test
	public void aMissingCoinsFlagReadsAsFalseAndTheNotesAreReadInTheOrderOfTheirDays() throws IOException
	{
		final PriceStore store = withHistory();
		TestFilepaths.write(notes(store), "{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-24\",\"gp\":9,"
			+ "\"coins\":true},{\"day\":\"2026-09-20\",\"gp\":-5}]}");
		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(Arrays.asList(sep(20), sep(24)), new ArrayList<>(series.transfers().keySet()));
		assertEquals(new BankHistorySeries.Transfer(-5L, false), series.transfers().get(sep(20)));
		assertEquals(new BankHistorySeries.Transfer(9L, true), series.transfers().get(sep(24)));
		assertTrue("a read writes nothing", namesContaining(".corrupt-").isEmpty());
	}

	// ---- the sweep

	@Test
	public void theSweepNeverMatchesANotesFileOrItsCopies() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(20), SENT);
		TestFilepaths.write(store.notesFile(7L, "STANDARD"), "{\"schema\":0}");
		TestFilepaths.write(store.dir().joinSegment("notes-7-STANDARD.json.corrupt-123"), "x");
		TestFilepaths.write(store.dir().joinSegment("notes-8-STANDARD.json"), "garbage");
		final List<String> before = names();

		store.deleteStaleFiles();
		store().deleteStaleFiles();

		assertEquals(before, names());
		assertTrue("and the history's own", history(store).exists());
	}

	// ---- the player's own fresh start

	@Test
	public void theCutIsWrittenIntoTheHistoryFileWithThePlayersReason() throws IOException
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));

		final String doc = TestFilepaths.read(history(store));
		assertTrue(doc, doc.startsWith("{\"schema\":3,\"freshFrom\":\"2026-09-24\",\"freshWhy\":\"player\",\"points\":["));
		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(24), series.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series.freshWhy());
		assertEquals("player", BankHistorySeries.WHY_PLAYER);
		assertEquals("nothing deleted: every reading is still there", 11, series.size());
		assertEquals(sep(18), series.first().day());
		assertTrue(series.hasLegacyDays());
		assertEquals("hidden from the days before it", 5, series.fromFresh().size());
	}

	@Test
	public void theCutMovesEarlierAsWellAsLater()
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(20)));
		assertEquals("earlier: a player's cut is not held to the never-back rule", sep(20),
			store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));
		assertEquals("later", sep(27), store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
		assertEquals(11, store.loadBankHistory(ACCOUNT, PROFILE).series().size());
	}

	@Test
	public void thePlayersCutMovesAnAutomaticOneBackToo()
	{
		final PriceStore store = withHistory();
		// An automatic restart on the 25th (placeholders), judged that day.
		assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(28), 99, 28), TODAY,
			new BankHistorySeries.PlaceholderCheck(sep(25), true)));
		assertEquals(sep(25), store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, store.loadBankHistory(ACCOUNT, PROFILE).series().freshWhy());

		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(21)));
		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(21), series.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series.freshWhy());
		assertEquals("the judgement is kept", sep(25), series.placeholdersChecked());
	}

	@Test
	public void theSameCutAgainIsNotWritten() throws IOException
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		final String spaced = TestFilepaths.read(history(store)).replace(",", ", ");
		TestFilepaths.write(history(store), spaced);
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertEquals(spaced, TestFilepaths.read(history(store)));
	}

	@Test
	public void aCutNeedsReadingsAndAnOwner()
	{
		final PriceStore store = store();
		assertFalse("no history at all", store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertTrue(names().isEmpty());
		final PriceStore with = withHistory();
		assertFalse(with.recordFreshStart(0L, PROFILE, sep(24)));
		assertFalse(with.recordFreshStart(-1L, PROFILE, sep(24)));
		assertFalse(with.recordFreshStart(ACCOUNT, PROFILE, null));
		assertNull(with.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	@Test
	public void aCutOnAHistoryThatFailedToReadWritesNothing() throws IOException
	{
		final PriceStore store = store();
		history(store).createDirectories();
		assertFalse(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertTrue(history(store).isDirectory());
		assertTrue(namesContaining(".corrupt-").isEmpty());
		assertFalse("and the owner stays refused", store.recordFreshStart(ACCOUNT, PROFILE, sep(25)));
	}

	@Test
	public void aCutOnAMigratedFileIsWrittenAsTheCurrentSchema() throws IOException
	{
		final PriceStore store = store();
		TestFilepaths.write(history(store), "{\"schema\":1,\"points\":[{\"day\":\"2026-09-20\",\"readAtMillis\":1,"
			+ "\"bankAtMillis\":1,\"card\":[5,0,0,0,0,0,0,0]},{\"day\":\"2026-09-22\",\"readAtMillis\":2,"
			+ "\"bankAtMillis\":2,\"card\":[6,0,0,0,0,0,0,0]}]}");
		assertEquals("every day of it is a legacy day until something is recorded", LocalDate.MAX,
			store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());

		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(22)));

		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(22), series.freshFrom());
		assertEquals(2, series.size());
		assertTrue(TestFilepaths.read(history(store)).startsWith("{\"schema\":3,\"freshFrom\":\"2026-09-22\""));
	}

	@Test
	public void aCutCopiesAFileItCannotKeepAsideFirst() throws IOException
	{
		final PriceStore store = withHistory();
		final String doc = TestFilepaths.read(history(store));
		TestFilepaths.write(history(store), doc.replace("\"schema\":3,", "\"schema\":3,\"extra\":1,"));
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertEquals(1, namesContaining("history-42-STANDARD.json.corrupt-").size());
		assertEquals(sep(24), store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}

	// ---- the restore mark (part 5): the player's way back from their own reset, in the account's notes file

	private static final String RESTORED_DOC = "{\"schema\":1,\"transfers\":[],\"restoredFrom\":\"2026-09-24\"}";

	@Test
	public void theRestoreMarkIsWrittenIntoTheNotesFileAndLoadsBackOnTheSeries() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		final String historyBefore = TestFilepaths.read(history(store));

		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));

		assertEquals(RESTORED_DOC, TestFilepaths.read(notes(store)));
		assertEquals("the history file is not touched", historyBefore, TestFilepaths.read(history(store)));
		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertTrue(series.restored());
		assertEquals("a restart reads the same", series, store().loadBankHistory(ACCOUNT, PROFILE).series());
		assertEquals("nothing else moved: the cut and its reason", sep(24), series.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series.freshWhy());

		assertTrue("taken off", store.recordRestored(ACCOUNT, PROFILE, null));
		assertEquals("no mark is no key: the file is as it was before", "{\"schema\":1,\"transfers\":[]}",
			TestFilepaths.read(notes(store)));
		assertFalse(store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
	}

	@Test
	public void aFileWithNoRestoreMarkAndAFalseOneReadAsNotRestored() throws IOException
	{
		final PriceStore store = withHistory();
		assertFalse("no notes file at all", store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		TestFilepaths.write(notes(store), SENT_DOC);
		assertFalse("a notes file from before part 5", store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		TestFilepaths.write(notes(store), "{\"schema\":1,\"transfers\":[],\"restoredFrom\":null}");
		assertFalse(store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		assertTrue("and none of it was written or put aside", namesContaining(".corrupt-").isEmpty());
	}

	@Test
	public void aRestoreMarkThatIsNotADatePutsTheFileAsideLikeAnyEntryThatDoesNotRead() throws IOException
	{
		final PriceStore store = withHistory();
		TestFilepaths.write(notes(store), "{\"schema\":1,\"transfers\":[],\"restoredFrom\":7}");
				assertFalse(store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		assertEquals("kept, not deleted", 1, namesContaining("notes-42-STANDARD.json.corrupt-").size());
		assertTrue("and the next press writes a new one", store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertEquals(RESTORED_DOC, TestFilepaths.read(notes(store)));
	}

	@Test
	public void theSameRestoreMarkAgainIsNotWrittenAndNoMarkWritesNothing() throws IOException
	{
		final PriceStore store = withHistory();
		assertTrue("no mark to take off: nothing to write", store.recordRestored(ACCOUNT, PROFILE, null));
		assertFalse(notes(store).exists());
		store.recordRestored(ACCOUNT, PROFILE, sep(24));
		final String spaced = RESTORED_DOC.replace(",", ", ");
		TestFilepaths.write(notes(store), spaced);
		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertEquals(spaced, TestFilepaths.read(notes(store)));
	}

	/** One file, two writers: a transfer write keeps the mark, and a restore write keeps the transfers. */
	@Test
	public void aTransferWriteKeepsTheRestoreMarkAndARestoreWriteKeepsTheTransfers() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(26), SENT));
		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertEquals("the restore kept the note", "{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-26\",\"gp\":-200000000,"
			+ "\"coins\":true}],\"restoredFrom\":\"2026-09-24\"}", TestFilepaths.read(notes(store)));

		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(25), new BankHistorySeries.Transfer(40L, false)));
		assertTrue(store.recordTransfer(ACCOUNT, PROFILE, sep(26), null));
		BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertTrue("transfer writes kept the mark", series.restored());
		assertEquals(Collections.singletonMap(sep(25), new BankHistorySeries.Transfer(40L, false)), series.transfers());

		assertTrue(store.recordRestored(ACCOUNT, PROFILE, null));
		series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertFalse(series.restored());
		assertEquals("and taking the mark off kept the transfer", 1, series.transfers().size());
	}

	@Test
	public void aNewCutTakesTheRestoreMarkOffAndKeepsTheTransfers() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		store.recordTransfer(ACCOUNT, PROFILE, sep(26), SENT);
		store.recordRestored(ACCOUNT, PROFILE, sep(24));
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().restored());

		assertTrue("the same cut again: the history file needs no write, the mark still comes off",
			store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertFalse(series.restored());
		assertEquals(Collections.singletonMap(sep(26), SENT), series.transfers());
		assertEquals(sep(24), series.freshFrom());

		store.recordRestored(ACCOUNT, PROFILE, sep(24));
		assertTrue("another day", store.recordFreshStart(ACCOUNT, PROFILE, sep(22)));
		series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertFalse(series.restored());
		assertEquals(sep(22), series.freshFrom());
	}

	@Test
	public void aCutWritesNoNotesFileWhenThereIsNoMarkToTakeOff()
	{
		final PriceStore store = withHistory();
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertFalse("the notes file is written only when something in it changed", notes(store).exists());
	}

	@Test
	public void theRestoreMarkIsPerOwner()
	{
		final PriceStore store = withHistory();
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(43L, PROFILE, point(sep(day), day, day), TODAY, null));
		}
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		store.recordFreshStart(43L, PROFILE, sep(24));
		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertTrue(store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		assertFalse("the other account is as it was", store.loadBankHistory(43L, PROFILE).series().restored());
		assertFalse(store.notesFile(43L, PROFILE).exists());

		assertTrue("a new cut on the other account", store.recordFreshStart(43L, PROFILE, sep(26)));
		assertTrue("leaves this account restored", store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		assertEquals(Collections.singletonList("notes-42-STANDARD.json"), namesContaining("notes-"));
		assertTrue("another profile of the same account too", store.recordRestored(ACCOUNT, "DEADMAN", sep(24)));
		assertEquals(Arrays.asList("notes-42-DEADMAN.json", "notes-42-STANDARD.json"), namesContaining("notes-"));
	}

	@Test
	public void aRestoreNeedsAnOwnerAndAReadableFile() throws IOException
	{
		final PriceStore store = withHistory();
		assertFalse(store.recordRestored(0L, PROFILE, sep(24)));
		assertFalse(store.recordRestored(-1L, PROFILE, sep(24)));
		assertTrue(namesContaining("notes-").isEmpty());
		assertTrue("a negative hash is an account (1.0.10)", store.recordRestored(-7_123L, PROFILE, sep(24)));

		final String newer = "{\"schema\":2,\"transfers\":[],\"restored\":false}";
		TestFilepaths.write(notes(store), newer);
		assertFalse("a newer build's notes are left alone", store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertEquals(newer, TestFilepaths.read(notes(store)));

		final PriceStore other = store();
		other.notesFile(ACCOUNT, "UNREAD").createDirectories();
		assertFalse("an unreadable one is never written over", other.recordRestored(ACCOUNT, "UNREAD", sep(24)));
		assertTrue(other.notesFile(ACCOUNT, "UNREAD").isDirectory());
	}

	/** An unreadable notes file reads as not restored, so its failure must not stop a new cut from hiding the days. */
	@Test
	public void aCutStillHidesTheDaysWhenTheNotesFileCannotBeRead() throws IOException
	{
		final PriceStore store = withHistory();
		notes(store).createDirectories();
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertEquals(sep(24), store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
		assertTrue("the obstacle is untouched", notes(store).isDirectory());
	}

	// ---- the 1.2.2 verification fixes: the cut a reset stands over, and the mark that names its cut

	/** Days 18 to 28 Sep with the automatic 1.1.1 restart at 22 Sep ({@code why} "placeholders"), or 1.0.9's cut when {@code placeholders} is false. */
	private PriceStore withAutomaticCut(final boolean placeholders) throws IOException
	{
		final PriceStore store = withHistory();
		final String doc = TestFilepaths.read(history(store));
		TestFilepaths.write(history(store), doc.replace("\"schema\":3,", "\"schema\":3,\"freshFrom\":\"2026-09-22\","
			+ (placeholders ? "\"freshWhy\":\"placeholders\"," : "")));
		final BankHistorySeries record = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(22), record.freshFrom());
		assertEquals(placeholders ? BankHistorySeries.WHY_PLACEHOLDERS : null, record.freshWhy());
		return store;
	}

	@Test
	public void aResetOverAnAutomaticCutWritesWhatItStoodOverIntoTheNotesAndKeepsItThroughLaterResets() throws IOException
	{
		final PriceStore store = withAutomaticCut(true);
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(28)));
		assertEquals("the notes carry the cut that was replaced, and no mark",
			"{\"schema\":1,\"transfers\":[],\"underFrom\":\"2026-09-22\",\"underWhy\":\"placeholders\"}",
			TestFilepaths.read(notes(store)));
		BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(28), series.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series.freshWhy());
		assertEquals(new BankHistorySeries.AutoCut(sep(22), BankHistorySeries.WHY_PLACEHOLDERS), series.autoCut());
		assertFalse(series.restored());
		assertEquals("a restart reads the same", series, store().loadBankHistory(ACCOUNT, PROFILE).series());

		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(28)));
		series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertTrue(series.restored());
		assertEquals("restored, the record stands as the automatic cut again", sep(22), series.standing().freshFrom());
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, series.standing().freshWhy());

		assertTrue("a second reset: from the player's own cut", store.recordFreshStart(ACCOUNT, PROFILE, sep(25)));
		series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertFalse("the mark comes off", series.restored());
		assertEquals("and the first automatic cut is still what it stands over - not the player's own",
			new BankHistorySeries.AutoCut(sep(22), BankHistorySeries.WHY_PLACEHOLDERS), series.autoCut());
		assertEquals(sep(25), series.freshFrom());
	}

	@Test
	public void aResetOverOnePointZeroNinesCutKeepsItWithNoReasonWord() throws IOException
	{
		final PriceStore store = withAutomaticCut(false);
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(28)));
		assertEquals("{\"schema\":1,\"transfers\":[],\"underFrom\":\"2026-09-22\"}", TestFilepaths.read(notes(store)));
		assertEquals(new BankHistorySeries.AutoCut(sep(22), null), store.loadBankHistory(ACCOUNT, PROFILE).series().autoCut());
	}

	@Test
	public void aResetThatStandsOverNothingWritesNoNotesAndALoadOfTheNotesKeepsTheTransfers() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(26), SENT);
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertNull(store.loadBankHistory(ACCOUNT, PROFILE).series().autoCut());
		assertEquals("only the transfer, as before", SENT_DOC.replace("2026-09-20", "2026-09-26"), TestFilepaths.read(notes(store)));
	}

	/** The mark counts only while the history file still holds the cut it restored, and that cut is the player's. */
	@Test
	public void aRestoreMarkThatNamesAnotherCutOrAnAutomaticOneRestoresNothing() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		TestFilepaths.write(notes(store), "{\"schema\":1,\"transfers\":[],\"restoredFrom\":\"2026-09-20\"}");
		assertFalse("a mark for the 20th is no restore of the 24th", store.loadBankHistory(ACCOUNT, PROFILE).series().restored());
		TestFilepaths.write(notes(store), RESTORED_DOC);
		assertTrue("the 24th's own", store.loadBankHistory(ACCOUNT, PROFILE).series().restored());

		final PriceStore automatic = withAutomaticCutOn(43L);
		TestFilepaths.write(automatic.notesFile(43L, PROFILE),
			"{\"schema\":1,\"transfers\":[],\"restoredFrom\":\"2026-09-22\"}");
		assertFalse("the same day, but the cut is no player's", automatic.loadBankHistory(43L, PROFILE).series().restored());
	}

	private PriceStore withAutomaticCutOn(final long account) throws IOException
	{
		final PriceStore store = store();
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(account, PROFILE, point(sep(day), day, day), TODAY, null));
		}
		final String doc = TestFilepaths.read(store.historyFile(account, PROFILE));
		TestFilepaths.write(store.historyFile(account, PROFILE), doc.replace("\"schema\":3,",
			"\"schema\":3,\"freshFrom\":\"2026-09-22\",\"freshWhy\":\"placeholders\","));
		return store;
	}

	@Test
	public void aNotesDateThatDoesNotReadPutsTheFileAsideLikeAnyEntryThatDoesNot() throws IOException
	{
		final PriceStore store = withHistory();
		TestFilepaths.write(notes(store), "{\"schema\":1,\"transfers\":[],\"underFrom\":\"yesterday\"}");
		assertNull(store.loadBankHistory(ACCOUNT, PROFILE).series().autoCut());
		assertEquals("kept, not deleted", 1, namesContaining("notes-42-STANDARD.json.corrupt-").size());
	}

	@Test
	public void theCutAndTheNotesAreSeparateFiles() throws IOException
	{
		final PriceStore store = withHistory();
		store.recordTransfer(ACCOUNT, PROFILE, sep(26), SENT);
		store.recordFreshStart(ACCOUNT, PROFILE, sep(24));
		assertEquals("a cut leaves the notes file as it was", "{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-26\","
			+ "\"gp\":-200000000,\"coins\":true}]}", TestFilepaths.read(notes(store)));
		final BankHistorySeries series = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(24), series.freshFrom());
		assertEquals("and the notes are on the series", Collections.singletonMap(sep(26), SENT), series.transfers());
		store.recordBankHistory(ACCOUNT, PROFILE, point(TODAY, 500L, 77L), TODAY, null);
		assertEquals("a reading keeps both", Collections.singletonMap(sep(26), SENT),
			store.loadBankHistory(ACCOUNT, PROFILE).series().transfers());
		assertEquals(sep(24), store.loadBankHistory(ACCOUNT, PROFILE).series().freshFrom());
	}
}
