package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The review of part 5 (contract {@code contract-1.2.2-part5-reset-per-account-2026-10-26.md}), finding 1: a player's reset
 * writes two files - the cut into the account's history file and the taking-off of its restore mark in the account's notes
 * file - and the two must agree whenever one of them fails. The notes were cleared FIRST and the result ignored, so a cut that
 * then could not be saved left the old cut with the mark gone: after a restart the days before the old cut were hidden again
 * although the player had restored them and the new cut was never saved. The cut now goes first and the mark comes off only
 * once it is in the file.
 *
 * <p>Real disk through {@link TestFilepaths}; the history file's writes can be refused with a switch, as a full disk or a
 * locked folder would, while the notes file keeps writing - and, for the 1.2.2 verification fixes, the other way round: the notes
 * file's writes refused while the history file keeps writing.
 */
public class ResetPerAccountReviewTest
{
	private static final long ACCOUNT = 42L;
	private static final String PROFILE = "STANDARD";
	private static final LocalDate TODAY = sep(28);
	private static final String RESTORED_DOC = "{\"schema\":1,\"transfers\":[],\"restoredFrom\":\"2026-09-24\"}";

	private final Gson gson = new Gson();

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private static BankHistoryPoint point(final LocalDate day, final long gp)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = gp;
		return new BankHistoryPoint(day, day.toEpochDay(), day.toEpochDay(), card, null);
	}

	/** A store over the same folder with nothing refused - what a restart of the client reads, and what the files are read through. */
	private PriceStore plain()
	{
		return new PriceStore(gson, TestFilepaths.rooted(tmp.getRoot()));
	}

	@Test
	public void aCutThatCannotBeSavedLeavesTheRestoreMarkAndTheOldCutTogether() throws IOException
	{
		final AtomicBoolean refuse = new AtomicBoolean();
		final PriceStore store = new PriceStore(gson, refusingHistoryWrites(refuse));
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(day), day), TODAY, null));
		}
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		final PriceStore disk = plain();
		final String history = TestFilepaths.read(disk.historyFile(ACCOUNT, PROFILE));
		assertEquals(RESTORED_DOC, TestFilepaths.read(disk.notesFile(ACCOUNT, PROFILE)));

		refuse.set(true);
		assertFalse("the new cut cannot be written", store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));

		assertEquals("the history file is as it was", history, TestFilepaths.read(disk.historyFile(ACCOUNT, PROFILE)));
		assertEquals("and so is the restore mark: it did not come off for a cut that was never saved", RESTORED_DOC,
			TestFilepaths.read(disk.notesFile(ACCOUNT, PROFILE)));
		final BankHistorySeries restart = plain().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals("after a restart: the old cut", sep(24), restart.freshFrom());
		assertTrue("still restored, as the player left it", restart.restored());

		refuse.set(false);
		assertTrue("the disk is back: the same press now saves", store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));
		final BankHistorySeries after = plain().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(27), after.freshFrom());
		assertFalse("and only now does the mark come off", after.restored());
	}

	// ---- the 1.2.2 verification fixes: the notes file that cannot be written

	/** A history whose automatic 1.1.1 restart cut it at 22 Sep: 18 to 21 read wrong, 22 to 28 stand. */
	private void placeholderCut(final PriceStore store)
	{
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(day), day), TODAY, day == 22
				? new BankHistorySeries.PlaceholderCheck(sep(22), true) : null));
		}
		final BankHistorySeries record = store.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(22), record.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, record.freshWhy());
	}

	/**
	 * A reset over an automatic cut writes that cut into the notes FIRST, so Restore history can still warn about the days it hid. When
	 * the notes cannot be written the cut is left exactly as it was and the reset answers false - never a player's cut that forgot what it
	 * stood over, which Restore would bring back with no word.
	 */
	@Test
	public void aResetOverAnAutomaticCutIsRefusedWhenItsNotesCannotBeWritten() throws IOException
	{
		final AtomicBoolean refuse = new AtomicBoolean();
		final PriceStore store = new PriceStore(gson, refusingWrites(refuse, PriceStore.NOTES_PREFIX));
		placeholderCut(store);
		final String history = TestFilepaths.read(plain().historyFile(ACCOUNT, PROFILE));

		refuse.set(true);
		assertFalse("the notes cannot be written", store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));
		assertEquals("the history file is as it was", history, TestFilepaths.read(plain().historyFile(ACCOUNT, PROFILE)));
		assertFalse("and no notes file was begun", plain().notesFile(ACCOUNT, PROFILE).exists());
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, plain().loadBankHistory(ACCOUNT, PROFILE).series().freshWhy());

		refuse.set(false);
		assertTrue("the disk is back: the same press now saves", store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));
		final BankHistorySeries after = plain().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(sep(27), after.freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, after.freshWhy());
		assertEquals("and what it stands over is kept", new BankHistorySeries.AutoCut(sep(22), BankHistorySeries.WHY_PLACEHOLDERS),
			after.autoCut());
	}

	/**
	 * The restore mark names the cut it restored. A reset made when the notes file cannot be written leaves the old mark behind, and the
	 * old mark - made for another cut - restores nothing of the new one: the reset the player made is not undone at the next launch.
	 */
	@Test
	public void aResetWhoseMarkCouldNotBeTakenOffIsNotUndoneByTheOldMark() throws IOException
	{
		final AtomicBoolean refuse = new AtomicBoolean();
		final PriceStore store = new PriceStore(gson, refusingWrites(refuse, PriceStore.NOTES_PREFIX));
		for (int day = 18; day <= 28; day++)
		{
			assertTrue(store.recordBankHistory(ACCOUNT, PROFILE, point(sep(day), day), TODAY, null));
		}
		assertTrue(store.recordFreshStart(ACCOUNT, PROFILE, sep(24)));
		assertTrue(store.recordRestored(ACCOUNT, PROFILE, sep(24)));
		assertTrue("restored, as the player left it", plain().loadBankHistory(ACCOUNT, PROFILE).series().restored());

		refuse.set(true);
		assertTrue("the cut is saved: the history file takes writes", store.recordFreshStart(ACCOUNT, PROFILE, sep(27)));
		assertEquals("the mark could not come off", RESTORED_DOC, TestFilepaths.read(plain().notesFile(ACCOUNT, PROFILE)));

		final BankHistorySeries next = plain().loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals("the next launch finds the new cut", sep(27), next.freshFrom());
		assertFalse("and the old mark is not its restore", next.restored());
	}

	/**
	 * The temporary folder as a store root on which a history file's temp write throws while {@code refuse} is set, and on which
	 * everything else - the notes file, every read - works as it does on disk.
	 */
	private Filepath refusingHistoryWrites(final AtomicBoolean refuse)
	{
		return refusingWrites(refuse, PriceStore.HISTORY_PREFIX);
	}

	/** The same root, refusing the temp write of any file whose name starts with {@code prefix} while {@code refuse} is set. */
	private Filepath refusingWrites(final AtomicBoolean refuse, final String prefix)
	{
		final Filepath root = mock(Filepath.class,
			withSettings().spiedInstance(TestFilepaths.rooted(tmp.getRoot())).defaultAnswer(CALLS_REAL_METHODS));
		doAnswer(join ->
		{
			final Filepath child = (Filepath) join.callRealMethod();
			final Filepath wrapped = mock(Filepath.class, withSettings().spiedInstance(child).defaultAnswer(CALLS_REAL_METHODS));
			doReturn(root).when(wrapped).getParent();
			if (child.getFileName().startsWith(prefix))
			{
				doAnswer(write ->
				{
					if (refuse.get())
					{
						throw new IOException("the disk is full");
					}
					return write.callRealMethod();
				}).when(wrapped).write(anyString(), any());
			}
			return wrapped;
		}).when(root).joinSegment(anyString());
		return root;
	}
}
