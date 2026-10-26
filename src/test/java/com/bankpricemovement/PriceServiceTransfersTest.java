package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Release 1.2.2's service half (contract {@code contract-1.2.2-transfers-and-start-fresh-2026-10-25.md}, F1 and F4): the
 * player marks a day as a transfer ({@link PriceService#markTransfer}, {@link PriceService#removeTransfer}) or starts the
 * history from a day ({@link PriceService#startHistoryFrom}) - the series in memory updated under the lock, the status
 * rebuilt and published, then the file written, the road a reading takes.
 *
 * <p>{@link PriceServiceTest}'s fixture by composition, its store a mock whose history and notes methods answer from a
 * REAL {@link PriceStore} on a temporary folder, so what reached the disk is read off the files. The player has a week
 * of readings, 1 to 7 Sep, and logs in on the 8th (the fixture's own day, UTC).
 */
public class PriceServiceTransfersTest
{
	private static final long DAY = 24L * 60L * 60_000L;
	private static final long ACCOUNT = PriceServiceTest.ACCOUNT;
	private static final long OTHER = PriceServiceTest.OTHER_ACCOUNT;
	private static final String PROFILE = PriceServiceTest.PROFILE;
	private static final LocalDate SEEDING = LocalDate.of(2030, 1, 1);
	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);
	private static final BankHistorySeries.Transfer SENT = new BankHistorySeries.Transfer(-200L, false);

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();
	private PriceStore disk;
	private final List<PriceService.Status> published = new ArrayList<>();

	@Before
	public void setUp()
	{
		f.setUp();
		disk = new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
		when(f.store.loadBankHistory(anyLong(), any())).thenAnswer(invocation ->
			disk.loadBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1)));
		when(f.store.recordBankHistory(anyLong(), any(), any(), any(), any())).thenAnswer(invocation ->
			disk.recordBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<BankHistoryPoint>getArgument(2), invocation.<LocalDate>getArgument(3),
				invocation.<BankHistorySeries.PlaceholderCheck>getArgument(4)));
		when(f.store.recordTransfer(anyLong(), any(), any(), any())).thenAnswer(invocation ->
			disk.recordTransfer(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<LocalDate>getArgument(2), invocation.<BankHistorySeries.Transfer>getArgument(3)));
		when(f.store.recordFreshStart(anyLong(), any(), any())).thenAnswer(invocation ->
			disk.recordFreshStart(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<LocalDate>getArgument(2)));
		when(f.store.recordRestored(anyLong(), any(), any())).thenAnswer(invocation ->
			disk.recordRestored(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<LocalDate>getArgument(2)));
		f.zonedService(ZoneOffset.UTC, false);
		f.service.addListener((rows, status) -> published.add(status));
		published.clear();
	}

	private static LocalDate sep(final int day)
	{
		return LocalDate.of(2026, 9, day);
	}

	/** A reading of {@code day}: the bank's tradeable cell {@code gp}, taken at noon. */
	private static BankHistoryPoint reading(final LocalDate day, final long gp)
	{
		final long[] cells = new long[BankHistoryPoint.CELLS];
		cells[BankHistoryPoint.BANK_TRADEABLE] = gp;
		final long noon = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() + DAY / 2L;
		return new BankHistoryPoint(day, noon, noon, cells, null);
	}

	/** One earlier session's week of readings for {@code account}, 1 to 7 Sep, each worth its day number x 1000. */
	private void seedWeek(final long account)
	{
		for (int day = 1; day <= 7; day++)
		{
			assertTrue(disk.recordBankHistory(account, PROFILE, reading(sep(day), day * 1000L), SEEDING, null));
		}
	}

	/** Logged in and the bank read: the 8th's reading is folded into the week on disk. */
	private void login(final long account)
	{
		f.service.start();
		f.service.setLoggedIn(true, account, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0));
	}

	private BankHistorySeries series()
	{
		return f.lastStatus().bankHistory();
	}

	private BankHistorySeries onDisk(final long account)
	{
		return disk.loadBankHistory(account, PROFILE).series();
	}

	// ---------------------------------------------------------------- the notes

	@Test
	public void markingADayPutsTheNoteOnTheStatusAndOnDisk() throws Exception
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		assertTrue("the week and the 8th", series().size() == 8 && series().transfers().isEmpty());
		published.clear();

		assertTrue(f.service.markTransfer(sep(5), -200L, true));

		assertEquals(Collections.singletonMap(sep(5), new BankHistorySeries.Transfer(-200L, true)),
			series().transfers());
		assertEquals("published at once", 1, published.size());
		assertEquals(series(), published.get(0).bankHistory());
		assertEquals("and written", "{\"schema\":1,\"transfers\":[{\"day\":\"2026-09-05\",\"gp\":-200,\"coins\":true}]}",
			TestFilepaths.read(disk.notesFile(ACCOUNT, PROFILE)));
		assertEquals("the same series a restart reads", series(), onDisk(ACCOUNT));
		assertEquals("no reading was touched", 8, series().size());
	}

	@Test
	public void theNoteIsChangedAndTakenAwayOnTheSameRoad()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		assertTrue(f.service.markTransfer(sep(5), -200L, false));
		assertTrue(f.service.markTransfer(sep(5), 75L, false));
		assertEquals(new BankHistorySeries.Transfer(75L, false), series().transfers().get(sep(5)));
		assertEquals(1, series().transfers().size());
		assertEquals(series(), onDisk(ACCOUNT));

		assertTrue(f.service.removeTransfer(sep(5)));
		assertTrue(series().transfers().isEmpty());
		assertTrue(onDisk(ACCOUNT).transfers().isEmpty());

		assertTrue(f.service.markTransfer(sep(5), -200L, false));
		assertTrue("0 gp is the note taken away", f.service.markTransfer(sep(5), 0L, true));
		assertTrue(series().transfers().isEmpty());
		assertTrue(onDisk(ACCOUNT).transfers().isEmpty());
	}

	@Test
	public void theNotesChangeEveryChangeTheStatusSeriesAnswers()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		final BankHistoryMath.Change before = BankHistoryMath.vsPrevious(series(), sep(5), null);
		f.service.markTransfer(sep(5), -500L, false);
		final BankHistoryMath.Change after = BankHistoryMath.vsPrevious(series(), sep(5), null);
		assertEquals(1000L, before.deltaGp());
		assertEquals("4000 to 5000 is a 1000 rise, and 500 of the day was sent away: a 1500 rise", 1500L, after.deltaGp());
		assertEquals("a real total is still the real one", 5000L, series().on(sep(5)).valueFor(null));
	}

	@Test
	public void aDayWithNoReadingIsRefused()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		published.clear();
		assertFalse(f.service.markTransfer(sep(20), -200L, false));
		assertFalse(f.service.markTransfer(LocalDate.of(2026, 8, 1), -200L, false));
		assertFalse(f.service.markTransfer(null, -200L, false));
		assertFalse(f.service.removeTransfer(sep(20)));
		assertFalse(f.service.removeTransfer(null));
		assertTrue(series().transfers().isEmpty());
		assertTrue("no publish and no write", published.isEmpty());
		assertFalse(disk.notesFile(ACCOUNT, PROFILE).exists());
		verify(f.store, never()).recordTransfer(anyLong(), any(), any(), any());
	}

	@Test
	public void nothingIsMarkedBeforeABankHasAnOwner()
	{
		f.service.start();
		assertFalse(f.service.markTransfer(sep(5), -200L, false));
		assertFalse(f.service.startHistoryFrom(sep(5)));
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		assertFalse("logged in, no bank priced yet", f.service.markTransfer(sep(5), -200L, false));
		verify(f.store, never()).recordTransfer(anyLong(), any(), any(), any());
	}

	@Test
	public void aStoppedServiceTakesNoMark()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		f.service.stop();
		assertFalse(f.service.markTransfer(sep(5), -200L, false));
		assertFalse(f.service.startHistoryFrom(sep(5)));
		assertFalse(disk.notesFile(ACCOUNT, PROFILE).exists());
	}

	@Test
	public void aNoteBelongsToTheAccountInMemoryAndNoOther()
	{
		seedWeek(ACCOUNT);
		seedWeek(OTHER);
		login(ACCOUNT);
		assertTrue(f.service.markTransfer(sep(5), -200L, false));

		f.service.setLoggedIn(true, OTHER, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 1_000L, OTHER));
		assertTrue("the other account's series has no note", series().transfers().isEmpty());
		assertTrue(f.service.markTransfer(sep(3), 90L, false));
		assertEquals(Collections.singletonMap(sep(3), new BankHistorySeries.Transfer(90L, false)), onDisk(OTHER).transfers());
		assertEquals("and the first account's file is as it was", Collections.singletonMap(sep(5), SENT),
			onDisk(ACCOUNT).transfers());

		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 2_000L));
		assertEquals("back again: its own note is read from the disk", Collections.singletonMap(sep(5), SENT),
			series().transfers());
	}

	@Test
	public void theNotesSurviveAClockThatWasBehindAndIsRightAgain()
	{
		seedWeek(ACCOUNT);
		// A clock a year back: the reading of that day is drawn but never written, and the record is kept whole.
		f.clock.set(PriceServiceTest.T0 - 365L * DAY);
		login(ACCOUNT);
		assertEquals("the week and the reading of the wrong day", 8, series().size());
		final LocalDate wrongDay = LocalDate.of(2025, 9, 8);
		assertEquals(wrongDay, series().first().day());

		assertTrue(f.service.markTransfer(sep(5), -200L, false));
		assertEquals(1, series().transfers().size());

		// The clock is put right while the client runs: the next reading drops the wrong day's, and rebuilds the series.
		f.clock.set(PriceServiceTest.T0);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 60_000L));

		assertNull("the year-back reading is gone", series().on(wrongDay));
		assertEquals(sep(1), series().first().day());
		assertNotNull(series().on(SEP_8));
		assertEquals("and the note is still on the series", Collections.singletonMap(sep(5), SENT), series().transfers());
		assertEquals(series(), onDisk(ACCOUNT));
	}

	@Test
	public void aNoteOnTodaysReadingSurvivesALaterReadingOfTheDay()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		assertTrue("the 8th's own reading can be marked", f.service.markTransfer(SEP_8, -300L, false));
		assertEquals(Collections.singletonMap(SEP_8, new BankHistorySeries.Transfer(-300L, false)), onDisk(ACCOUNT).transfers());
		// A later reading of the same day (the bank changes) keeps the note: the note is on the day, not on the reading.
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 120_000L));
		assertEquals(1, series().transfers().size());
		assertEquals(series(), onDisk(ACCOUNT));
	}

	@Test
	public void markingDoesNotMoveTheRestartCount()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		final int restarts = f.lastStatus().placeholderRestarts();
		f.service.markTransfer(sep(5), -200L, false);
		f.service.removeTransfer(sep(5));
		assertEquals(restarts, f.lastStatus().placeholderRestarts());
	}

	// ---------------------------------------------------------------- Start fresh

	@Test
	public void startingTheHistoryFromADayHidesTheDaysBeforeAndDeletesNothing()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		published.clear();
		final int before = f.lastStatus().placeholderRestarts();

		assertTrue(f.service.startHistoryFrom(sep(5)));

		assertEquals(sep(5), series().freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series().freshWhy());
		assertEquals("every reading is still there", 8, series().size());
		assertEquals("the sidebar's view of it: 5, 6, 7 and 8 Sep", Arrays.asList(sep(5), sep(6), sep(7), SEP_8),
			days(series().fromFresh()));
		assertEquals("no automatic restart: the shared 'Include days before' box is not this account's to turn off", before,
			f.lastStatus().placeholderRestarts());
		assertEquals(1, published.size());
		assertEquals("and written", series(), onDisk(ACCOUNT));
		assertEquals(sep(5), onDisk(ACCOUNT).freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, onDisk(ACCOUNT).freshWhy());
	}

	@Test
	public void theCutMovesEarlierAndLaterAndNoPressIsARestart()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		final int base = f.lastStatus().placeholderRestarts();
		assertTrue(f.service.startHistoryFrom(sep(6)));
		assertTrue(f.service.startHistoryFrom(sep(3)));
		assertEquals("earlier: a player's cut is not held to the never-back rule", sep(3), series().freshFrom());
		assertEquals(sep(3), onDisk(ACCOUNT).freshFrom());
		assertTrue(f.service.startHistoryFrom(sep(7)));
		assertEquals("later", sep(7), series().freshFrom());
		assertTrue("the same cut again", f.service.startHistoryFrom(sep(7)));
		assertEquals("no press is a restart, not even one that moved the cut", base, f.lastStatus().placeholderRestarts());
		assertEquals(8, series().size());
	}

	@Test
	public void theNotesAreKeptThroughACutAndInertBeforeIt()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		f.service.markTransfer(sep(3), -200L, false);
		f.service.markTransfer(sep(6), 40L, false);
		assertTrue(f.service.startHistoryFrom(sep(5)));
		assertEquals("both stay stored", 2, series().transfers().size());
		assertEquals(2, onDisk(ACCOUNT).transfers().size());
		final BankHistorySeries visible = series().fromFresh();
		assertEquals("the one before the cut shifts nothing that is drawn", Arrays.asList(5000L + 40L, 6000L, 7000L),
			drawn(visible, sep(5), sep(7)));
	}

	@Test
	public void aHistoryThatFailedToReadIsCutInMemoryAndNeverWritten() throws Exception
	{
		disk.historyFile(ACCOUNT, PROFILE).createDirectories();
		login(ACCOUNT);
		assertEquals("only today's reading is drawn", 1, series().size());
		published.clear();

		assertTrue(f.service.startHistoryFrom(SEP_8));

		assertEquals(SEP_8, series().freshFrom());
		assertEquals(1, published.size());
		verify(f.store, never()).recordFreshStart(anyLong(), any(), any());
		assertTrue("the obstacle is untouched", disk.historyFile(ACCOUNT, PROFILE).isDirectory());
	}

	/**
	 * The review of part 5, finding 2: the reset above is never written while the history is unreadable, so the restore that undoes
	 * it must not be either - a mark written next to a cut that was never saved would restore whatever older cut the file holds.
	 */
	@Test
	public void aRestoreOnAHistoryThatFailedToReadIsKeptInMemoryAndNeverWritten() throws Exception
	{
		disk.historyFile(ACCOUNT, PROFILE).createDirectories();
		login(ACCOUNT);
		assertTrue(f.service.startHistoryFrom(SEP_8));
		published.clear();

		assertTrue(f.service.restoreHistory());

		assertTrue("the session shows what the player asked for", series().restored());
		assertEquals(1, published.size());
		verify(f.store, never()).recordFreshStart(anyLong(), any(), any());
		verify(f.store, never()).recordRestored(anyLong(), any(), any());
		assertFalse("no notes file was written", disk.notesFile(ACCOUNT, PROFILE).exists());
		assertTrue("the obstacle is untouched", disk.historyFile(ACCOUNT, PROFILE).isDirectory());
	}

	@Test
	public void aCutWithNoReadingsIsRefused()
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		assertFalse(f.service.startHistoryFrom(sep(5)));
		assertFalse(f.service.startHistoryFrom(null));
		verify(f.store, never()).recordFreshStart(anyLong(), any(), eq(sep(5)));
	}

	// ---------------------------------------------------------------- Restore history (part 5): one account's

	private static final String RESTORED_DOC = "{\"schema\":1,\"transfers\":[],\"restoredFrom\":\"2026-09-05\"}";

	@Test
	public void restoringAPlayersResetMarksTheAccountAndWritesItsNotesAndNothingElse() throws Exception
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		final int restarts = f.lastStatus().placeholderRestarts();
		assertTrue(f.service.startHistoryFrom(sep(5)));
		final String history = TestFilepaths.read(disk.historyFile(ACCOUNT, PROFILE));
		published.clear();

		assertTrue(f.service.restoreHistory());

		assertTrue(series().restored());
		assertEquals("the cut and its reason stand: the days are hidden-but-restored, not un-cut", sep(5), series().freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series().freshWhy());
		assertEquals("published at once", 1, published.size());
		assertEquals(series(), published.get(0).bankHistory());
		assertEquals("and written, in the account's notes", RESTORED_DOC, TestFilepaths.read(disk.notesFile(ACCOUNT, PROFILE)));
		assertEquals("the history file is not touched", history, TestFilepaths.read(disk.historyFile(ACCOUNT, PROFILE)));
		assertEquals("the series a restart reads", series(), onDisk(ACCOUNT));
		assertEquals("no automatic restart", restarts, f.lastStatus().placeholderRestarts());
		assertEquals("every reading is there", 8, series().size());
	}

	@Test
	public void aNewResetTakesTheRestoreOffAndWritesThatToo() throws Exception
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		final int restarts = f.lastStatus().placeholderRestarts();
		f.service.startHistoryFrom(sep(5));
		f.service.restoreHistory();
		assertTrue(onDisk(ACCOUNT).restored());

		assertTrue("the same cut again", f.service.startHistoryFrom(sep(5)));
		assertFalse(series().restored());
		assertFalse(onDisk(ACCOUNT).restored());
		assertEquals("no mark, no key", "{\"schema\":1,\"transfers\":[]}", TestFilepaths.read(disk.notesFile(ACCOUNT, PROFILE)));

		f.service.restoreHistory();
		assertTrue(f.service.startHistoryFrom(sep(7)));
		assertFalse("another day", series().restored());
		assertFalse(onDisk(ACCOUNT).restored());
		assertEquals(sep(7), onDisk(ACCOUNT).freshFrom());
		assertEquals("a reset is never an automatic restart", restarts, f.lastStatus().placeholderRestarts());
	}

	@Test
	public void restoreIsRefusedUnlessThePlayerResetThisAccountsHistory()
	{
		f.service.start();
		assertFalse("nothing to restore before a bank has an owner", f.service.restoreHistory());
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		published.clear();
		assertFalse("no cut at all", f.service.restoreHistory());
		assertTrue(f.service.startHistoryFrom(sep(5)));
		assertTrue(f.service.restoreHistory());
		published.clear();
		assertFalse("already restored", f.service.restoreHistory());
		assertTrue("no second publish and no second write", published.isEmpty());
		verify(f.store, times(1)).recordRestored(anyLong(), any(), eq(sep(5)));
		f.service.stop();
		assertFalse(f.service.restoreHistory());
	}

	@Test
	public void restoreIsRefusedForTheAutomaticRestartsReasons()
	{
		seedWeek(ACCOUNT);
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		// A fresh bank read that dropped placeholders with a quantity restarts the record for the placeholder bug: the days before it are
		// the shared box's to show (1.1.1), and the player's own Restore history is not that road.
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.placeholderSlots = 5;
		f.service.setBank(bank);
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, series().freshWhy());
		published.clear();
		assertFalse(f.service.restoreHistory());
		assertTrue(published.isEmpty());
		verify(f.store, never()).recordRestored(anyLong(), any(), any());
	}

	/** Two accounts in one service: each one's reset and restore is its own, in memory and on disk. */
	@Test
	public void aRestoreAndAResetBelongToTheAccountInMemoryAndNoOther()
	{
		seedWeek(ACCOUNT);
		seedWeek(OTHER);
		login(ACCOUNT);
		final int restarts = f.lastStatus().placeholderRestarts();
		assertTrue(f.service.startHistoryFrom(sep(5)));
		assertTrue(f.service.restoreHistory());

		f.service.setLoggedIn(true, OTHER, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 1_000L, OTHER));
		assertFalse("the other account is not restored", series().restored());
		assertNull("and not cut", series().freshFrom());
		assertTrue(f.service.startHistoryFrom(sep(6)));
		assertFalse(series().restored());
		assertEquals(sep(6), series().freshFrom());
		assertTrue("the first account's file is as it was", onDisk(ACCOUNT).restored());
		assertEquals(sep(5), onDisk(ACCOUNT).freshFrom());
		assertFalse("the other's own", onDisk(OTHER).restored());
		assertFalse("and the other pressed no restore: it has no notes file", disk.notesFile(OTHER, PROFILE).exists());

		assertTrue(f.service.restoreHistory());
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 2_000L));
		assertTrue("back again: its own restore is read from the disk, the other's reset did not touch it", series().restored());
		assertEquals(sep(5), series().freshFrom());
		assertEquals("no reset or restore was ever counted as a restart", restarts, f.lastStatus().placeholderRestarts());
	}

	/** One file, two writers, through the service: a transfer keeps the restore and a restore keeps the transfer. */
	@Test
	public void aTransferAndARestoreKeepEachOtherOnTheSeriesAndOnDisk()
	{
		seedWeek(ACCOUNT);
		login(ACCOUNT);
		assertTrue(f.service.startHistoryFrom(sep(3)));
		assertTrue(f.service.markTransfer(sep(5), -200L, false));
		assertTrue(f.service.restoreHistory());
		assertTrue(series().restored());
		assertEquals(Collections.singletonMap(sep(5), SENT), series().transfers());
		assertEquals(series(), onDisk(ACCOUNT));

		assertTrue(f.service.removeTransfer(sep(5)));
		assertTrue("taking the note away kept the restore", series().restored());
		assertTrue(onDisk(ACCOUNT).restored());
		assertTrue(f.service.markTransfer(sep(6), 90L, false));
		assertEquals(series(), onDisk(ACCOUNT));
		assertTrue(onDisk(ACCOUNT).restored());
	}

	/** {@code withoutBehind} rebuilds the series from its readings: the restore mark must be put back like the notes. */
	@Test
	public void theRestoreMarkSurvivesAClockThatWasBehindAndIsRightAgain()
	{
		seedWeek(ACCOUNT);
		f.clock.set(PriceServiceTest.T0 - 365L * DAY);
		login(ACCOUNT);
		final LocalDate wrongDay = LocalDate.of(2025, 9, 8);
		assertEquals(wrongDay, series().first().day());
		assertTrue(f.service.startHistoryFrom(sep(3)));
		assertTrue(f.service.markTransfer(sep(5), -200L, false));
		assertTrue(f.service.restoreHistory());
		assertTrue(series().restored());

		// The clock is put right while the client runs: the next reading drops the wrong day's, and rebuilds the series.
		f.clock.set(PriceServiceTest.T0);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 60_000L));

		assertNull("the year-back reading is gone", series().on(wrongDay));
		assertTrue("and the restore mark is still on the series", series().restored());
		assertEquals(sep(3), series().freshFrom());
		assertEquals(BankHistorySeries.WHY_PLAYER, series().freshWhy());
		assertEquals(Collections.singletonMap(sep(5), SENT), series().transfers());
		assertEquals(series(), onDisk(ACCOUNT));
	}

	// ---------------------------------------------------------------- the cut a reset stands over (verification fix)

	/**
	 * A reset over the 1.1.1 placeholder restart keeps that cut with the account - in memory and in its notes - and the restore mark never
	 * brings it back by itself: the record then stands as the automatic cut, whose days wait for the shared box and its warning. A second
	 * reset keeps the FIRST automatic cut, not the player's own.
	 */
	@Test
	public void aResetOverAnAutomaticCutKeepsItWithTheAccountInMemoryAndOnDisk()
	{
		seedWeek(ACCOUNT);
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.placeholderSlots = 5;
		f.service.setBank(bank);
		assertEquals(BankHistorySeries.WHY_PLACEHOLDERS, series().freshWhy());
		final LocalDate restartDay = series().freshFrom();
		final int restarts = f.lastStatus().placeholderRestarts();
		final BankHistorySeries.AutoCut under = new BankHistorySeries.AutoCut(restartDay, BankHistorySeries.WHY_PLACEHOLDERS);

		assertTrue(f.service.startHistoryFrom(restartDay));

		assertEquals(BankHistorySeries.WHY_PLAYER, series().freshWhy());
		assertEquals("the cut it replaced is kept on the series", under, series().autoCut());
		assertEquals("and in the account's notes", under, onDisk(ACCOUNT).autoCut());
		assertEquals("no automatic restart", restarts, f.lastStatus().placeholderRestarts());
		assertTrue(f.service.restoreHistory());
		assertTrue(series().restored());
		assertEquals("restored, the record stands as the placeholder cut", BankHistorySeries.WHY_PLACEHOLDERS,
			series().standing().freshWhy());
		assertEquals(restartDay, series().standing().freshFrom());
		assertEquals(series(), onDisk(ACCOUNT));

		assertTrue("a second reset", f.service.startHistoryFrom(sep(5)));
		assertEquals("stands over the first automatic cut, not the player's own", under, series().autoCut());
		assertFalse(series().restored());
		assertEquals(series(), onDisk(ACCOUNT));
	}

	/** {@code withoutBehind} rebuilds the series from its readings: what the reset stands over must be put back like the restore mark. */
	@Test
	public void theCutAResetStandsOverSurvivesAClockThatWasBehindAndIsRightAgain() throws Exception
	{
		seedWeek(ACCOUNT);
		// A 1.0.9 cut at 2 Sep, and the player's reset over it from 4 Sep: the notes keep the cut that was replaced.
		TestFilepaths.write(disk.historyFile(ACCOUNT, PROFILE), TestFilepaths.read(disk.historyFile(ACCOUNT, PROFILE))
			.replace("\"schema\":2,", "\"schema\":2,\"freshFrom\":\"2026-09-02\","));
		assertTrue(disk.recordFreshStart(ACCOUNT, PROFILE, sep(4)));
		final BankHistorySeries.AutoCut under = new BankHistorySeries.AutoCut(sep(2), null);
		assertEquals(under, onDisk(ACCOUNT).autoCut());
		f.clock.set(PriceServiceTest.T0 - 365L * DAY);
		login(ACCOUNT);
		final LocalDate wrongDay = LocalDate.of(2025, 9, 8);
		assertEquals(wrongDay, series().first().day());
		assertEquals("loaded with the notes", under, series().autoCut());

		f.clock.set(PriceServiceTest.T0);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 60_000L));

		assertNull("the year-back reading is gone", series().on(wrongDay));
		assertEquals("and what the reset stands over is still on the series", under, series().autoCut());
		assertEquals(sep(4), series().freshFrom());
		assertEquals(series(), onDisk(ACCOUNT));
	}

	private static List<LocalDate> days(final BankHistorySeries series)
	{
		final List<LocalDate> out = new ArrayList<>();
		for (final BankHistoryPoint point : series.points())
		{
			out.add(point.day());
		}
		return out;
	}

	/** The drawn totals of {@code from} to {@code to}. */
	private static List<Long> drawn(final BankHistorySeries series, final LocalDate from, final LocalDate to)
	{
		final List<Long> out = new ArrayList<>();
		for (final BankHistoryMath.Day day : BankHistoryMath.days(series, from, to, null))
		{
			out.add(day.drawnGp());
		}
		return out;
	}
}
