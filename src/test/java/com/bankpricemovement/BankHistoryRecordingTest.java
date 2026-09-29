package com.bankpricemovement;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Addendum AU, the recording half (contract section 5 with amendments 9.3 and 9.18; plan 7.1 items 3-6 and 9,
 * 7.3's named tests, 7.5 item 1): WHEN a computation of {@link PriceService} is a bank-history reading, whose it is,
 * which LOCAL day it is filed under, what reaches the store, and that the publish carrying a new total carries its
 * point.
 *
 * <p>{@link PriceServiceTest}'s fixture by composition. Its store is a mock; here the two history methods answer
 * from a REAL {@link PriceStore} on a temporary folder, so "never writes one owner's point into the other's file"
 * is read off the files themselves.
 */
public class BankHistoryRecordingTest
{
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;
	private static final long ACCOUNT = PriceServiceTest.ACCOUNT;
	private static final long OTHER = PriceServiceTest.OTHER_ACCOUNT;
	private static final String PROFILE = PriceServiceTest.PROFILE;
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();
	private PriceStore disk;
	/** Every status published, in order - the listener {@link PriceServiceTest} registers keeps only the last. */
	private final List<PriceService.Status> published = new ArrayList<>();

	@Before
	public void setUp()
	{
		f.setUp();
		disk = new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
		when(f.store.loadBankHistory(anyLong(), any())).thenAnswer(invocation ->
			disk.loadBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1)));
		when(f.store.recordBankHistory(anyLong(), any(), any(), any())).thenAnswer(invocation ->
			disk.recordBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<BankHistoryPoint>getArgument(2), invocation.<LocalDate>getArgument(3)));
		// UTC unless a test says otherwise, so "today" is the fixture's own 08 Sep whatever machine runs this.
		useZone(ZoneOffset.UTC);
	}

	private void useZone(final ZoneId zone)
	{
		f.zonedService(zone, false);
		f.service.addListener((rows, status) -> published.add(status));
		published.clear();
	}

	/** Logged in, then the bank: the plain order, with no fetch - RuneLite's own prices price every row. */
	private void loginWith(final BankSnapshot bank)
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(bank);
	}

	private static LocalDate utcDay(final long millis)
	{
		return BankHistoryMath.dayOf(millis, ZoneOffset.UTC);
	}

	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);
	private static final LocalDate SEP_9 = LocalDate.of(2026, 9, 9);

	private BankHistorySeries series()
	{
		return f.lastStatus().bankHistory();
	}

	private int writes()
	{
		int count = 0;
		for (final org.mockito.invocation.Invocation invocation : mockingDetails(f.store).getInvocations())
		{
			if ("recordBankHistory".equals(invocation.getMethod().getName()))
			{
				count++;
			}
		}
		return count;
	}

	/** The bank with one more Mystery box - the smallest real change to the cells. */
	private static BankSnapshot withMoreBoxes(final long capturedAt, final int boxes)
	{
		final BankSnapshot bank = PriceServiceTest.bank(capturedAt);
		for (int i = 0; i < bank.items.size(); i++)
		{
			if (bank.items.get(i).id == PriceServiceTest.BOX)
			{
				bank.items.set(i, bank.items.get(i).withQuantity(boxes));
			}
		}
		return bank;
	}

	// ---------------------------------------------------------------- the reading and its publish

	/**
	 * Plan 7.1 item 3: the reading is folded in BEFORE the status is built, so the very publish that carries a new
	 * total carries its point - and every publish after it agrees with the card to the gp.
	 */
	@Test
	public void theReadingIsInTheSamePublishAsItsTotal()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + MINUTE, 50));
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		boolean sawTotal = false;
		for (final PriceService.Status status : published)
		{
			if (status.portfolio().valueNow() <= 0L)
			{
				continue;
			}
			sawTotal = true;
			final BankHistoryPoint point = status.bankHistory().on(SEP_8);
			assertNotNull("a publish with a total carries its reading: " + status, point);
			assertEquals(status.portfolio().valueNow(), point.valueFor(status.options()));
			assertEquals(status.bankAtMillis(), point.bankAtMillis());
		}
		assertTrue(sawTotal);
		assertEquals("one reading a day", 1, series().size());
		assertEquals(SEP_8, series().last().day());
		assertEquals(PriceServiceTest.T0 + MINUTE, series().last().bankAtMillis());
	}

	/** Amendment 9.3: a status-only publish (a logout) carries the series too - buildStatusLocked is the one place. */
	@Test
	public void aStatusOnlyPublishCarriesTheSeries()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final List<MovementRow> rows = f.lastRows();
		final BankHistorySeries before = series();
		assertEquals(1, before.size());

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);

		assertSame("a status-only publish", rows, f.lastRows());
		assertEquals(before, series());
	}

	/** A DEGRADED publish still records (plan 7.1 item 6): "now" is still a guide price. */
	@Test
	public void aDegradedPublishStillRecords()
	{
		final BankSnapshot small = PriceServiceTest.bank(PriceServiceTest.T0);
		small.items.subList(5, small.items.size()).clear();
		f.warmUpWith(small);

		assertTrue("five stacks cannot date the prices", f.lastStatus().anchorDegraded());
		final BankHistoryPoint point = series().on(SEP_8);
		assertNotNull(point);
		assertEquals(f.lastStatus().portfolio().valueNow(), point.valueFor(f.lastStatus().options()));
	}

	/** Exactly one client-thread trip per computation still: the cells ride the trip the rows already make. */
	@Test
	public void theCellsCostNoSecondTripToTheClientThread()
	{
		loginWith(PriceServiceTest.bankWithCarried(PriceServiceTest.T0, 0L));
		final int before = f.clientThread.invocations;

		f.service.setBank(PriceServiceTest.bankWithCarried(PriceServiceTest.T0 + MINUTE, 0L));

		assertEquals(before + 1, f.clientThread.invocations);
		assertEquals(1, series().size());
	}

	/** A cell above the int range stays whole: every figure on the way is a long (the 1.13.0 rule). */
	@Test
	public void aCellAboveTheIntRangeStaysWhole()
	{
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.items.clear();
		bank.items.add(new BankItem(PriceServiceTest.item(24), 2_000_000_000, "Item 24", true));
		loginWith(bank);

		final long unit = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.item(24)).unitPrice();
		final BankHistoryPoint point = series().last();
		assertEquals(2_000_000_000L * unit, point.card(BankHistoryPoint.BANK_TRADEABLE));
		assertTrue(point.card(BankHistoryPoint.BANK_TRADEABLE) > Integer.MAX_VALUE);
		assertEquals(f.lastStatus().portfolio().valueNow(), point.valueFor(ViewOptions.DEFAULT));
		assertEquals("and on disk", point,
			disk.loadBankHistory(ACCOUNT, PROFILE).series().last());
	}

	// ---------------------------------------------------------------- not a reading

	/** Plan 7.1 item 6: a made-up bank (the dev verb, persist false) is never a reading, nor any re-pricing of it. */
	@Test
	public void nothingIsRecordedFromASyntheticBank()
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0), false);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertTrue(f.lastStatus().portfolio().valueNow() > 0L);
		assertTrue(series().isEmpty());
		verify(f.store, never()).recordBankHistory(anyLong(), any(), any(), any());

		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + MINUTE));
		assertEquals("a real bank after it records", 1, series().size());
		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + 2L * MINUTE, 99), false);
		assertEquals("and a made-up one after that changes nothing", PriceServiceTest.T0 + MINUTE,
			series().last().bankAtMillis());
		assertEquals(1, writes());
	}

	/** An owner hash {@code <= 0} is never a reading and never asks the store for a history. */
	@Test
	public void nothingIsRecordedForABankWithNoOwner()
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(new BankSnapshot(PriceServiceTest.bank(PriceServiceTest.T0).items, PriceServiceTest.T0, 0L,
			PROFILE));

		assertTrue(f.lastStatus().portfolio().valueNow() > 0L);
		assertTrue(series().isEmpty());
		verify(f.store, never()).loadBankHistory(anyLong(), any());
		verify(f.store, never()).recordBankHistory(anyLong(), any(), any(), any());
	}

	/**
	 * Plan 7.1 item 6: GE-tradeable stacks of which not one is priced is not a reading (the bones are never priced);
	 * a bank with no tradeable stack at all - nothing but coins - still is.
	 */
	@Test
	public void nothingIsRecordedWhenNoTradeableStackIsPriced()
	{
		final BankSnapshot bones = PriceServiceTest.bank(PriceServiceTest.T0);
		bones.items.clear();
		bones.items.add(new BankItem(526, 100, "Bones", true));
		bones.items.add(new BankItem(PriceServiceTest.DRAMEN, 1, "Dramen staff", false, true, 1_500));
		bones.currencyGp = 5_000L;
		loginWith(bones);

		assertTrue(series().isEmpty());
		verify(f.store, never()).recordBankHistory(anyLong(), any(), any(), any());

		final BankSnapshot coins = PriceServiceTest.bank(PriceServiceTest.T0 + MINUTE);
		coins.items.clear();
		coins.currencyGp = 5_000L;
		f.service.setBank(coins);

		assertEquals("a bank of coins alone is a reading", 5_000L, series().last().valueFor(ViewOptions.DEFAULT));
	}

	/**
	 * Amendment 9.18: a client at the login screen records nothing - a re-pricing of the capture already recorded
	 * never records, however the prices move, and a new day brings no reading while nobody logs in.
	 */
	@Test
	public void nothingIsRecordedAtTheLoginScreen()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final BankHistoryPoint recorded = series().last();
		final int writes = writes();

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.clock.addAndGet(HOUR);
		f.runelitePrice(PriceServiceTest.BOX, 5_000);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertNotEquals("the prices did move", recorded.valueFor(ViewOptions.DEFAULT),
			f.lastStatus().portfolio().valueNow());
		assertEquals("the reading stays as it was", recorded, series().on(SEP_8));
		assertEquals(writes, writes());

		f.clock.set(PriceServiceTest.T0 + DAY);
		f.service.setOptions(ViewOptions.DEFAULT);

		assertNull("no login, no reading for the new day", series().on(SEP_9));
		assertEquals(writes, writes());

		// The positive control: the same price move while logged in IS a reading.
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));
		assertNotNull(series().on(SEP_9));
		assertEquals(writes + 1, writes());
	}

	/**
	 * Amendment 9.18, the logout read: a bank captured today and priced for the first time after the logout is
	 * recorded once; re-pricing that same capture at the login screen then never is.
	 */
	@Test
	public void theLogoutReadRecordsOnceAndItsRepricingNever()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final int writes = writes();

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.clock.addAndGet(10L * MINUTE);
		final long logoutRead = f.clock.get();
		f.service.setBank(withMoreBoxes(logoutRead, 40));

		assertEquals("the logout read is today's reading", logoutRead, series().on(SEP_8).bankAtMillis());
		assertEquals(f.lastStatus().portfolio().valueNow(), series().on(SEP_8).valueFor(ViewOptions.DEFAULT));
		assertEquals(writes + 1, writes());
		final BankHistoryPoint read = series().on(SEP_8);

		f.clock.addAndGet(HOUR);
		f.runelitePrice(PriceServiceTest.BOX, 5_000);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertEquals("its re-pricing at the login screen is not", read, series().on(SEP_8));
		assertEquals(writes + 1, writes());
	}

	/** Amendment 9.18: a logout read of a bank captured on an EARLIER local day is not today's reading. */
	@Test
	public void aLogoutReadOfABankCapturedYesterdayIsNotRecorded()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		// 08 Sep 23:50 UTC captured, priced at 09 Sep 00:30 UTC.
		f.clock.set(PriceServiceTest.T0 + 4L * HOUR + 10L * MINUTE);
		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + 3L * HOUR + 30L * MINUTE, 40));

		assertEquals(SEP_9, utcDay(f.clock.get()));
		assertNull(series().on(SEP_9));
		assertEquals("and yesterday's reading is untouched", PriceServiceTest.T0, series().on(SEP_8).bankAtMillis());
	}

	// ---------------------------------------------------------------- the local day

	/**
	 * Plan 7.1 item 2: a reading is filed under the player's LOCAL date. In Toronto (UTC-4 in September) 23:30 on
	 * 08 Sep and 00:30 on 09 Sep are two days, though both are 09 Sep in UTC; 19:30 and 20:30 on 08 Sep are one day,
	 * though they straddle the UTC midnight.
	 */
	@Test
	public void theDayTurnsAtLocalMidnight()
	{
		useZone(TORONTO);
		final long evening = local(SEP_8, 19, 30);
		final long lateEvening = local(SEP_8, 20, 30);
		final long beforeMidnight = local(SEP_8, 23, 30);
		final long afterMidnight = local(SEP_9, 0, 30);
		assertNotEquals(utcDay(evening), utcDay(lateEvening));
		assertEquals(utcDay(beforeMidnight), utcDay(afterMidnight));

		f.clock.set(evening);
		loginWith(withMoreBoxes(evening, 10));
		f.clock.set(lateEvening);
		f.service.setBank(withMoreBoxes(lateEvening, 20));
		assertEquals("either side of the UTC midnight, one local day", 1, series().size());

		f.clock.set(beforeMidnight);
		f.service.setBank(withMoreBoxes(beforeMidnight, 30));
		f.clock.set(afterMidnight);
		f.service.setBank(withMoreBoxes(afterMidnight, 40));

		assertEquals(2, series().size());
		assertEquals(beforeMidnight, series().on(SEP_8).bankAtMillis());
		assertEquals(afterMidnight, series().on(SEP_9).bankAtMillis());
		assertEquals("and the file agrees", series(), disk.loadBankHistory(ACCOUNT, PROFILE).series());
	}

	private static long local(final LocalDate day, final int hour, final int minute)
	{
		return LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(TORONTO).toInstant().toEpochMilli();
	}

	/** Plan 7.5 item 1: today's point is overwritten by every new total; the last of the day stays, in memory and on disk. */
	@Test
	public void theLastReadingOfTheDayWins()
	{
		loginWith(withMoreBoxes(PriceServiceTest.T0, 10));
		f.clock.addAndGet(HOUR);
		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + HOUR, 20));
		f.clock.addAndGet(HOUR);
		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + 2L * HOUR, 30));

		assertEquals(1, series().size());
		final BankHistoryPoint last = series().last();
		assertEquals(PriceServiceTest.T0 + 2L * HOUR, last.bankAtMillis());
		assertEquals(PriceServiceTest.T0 + 2L * HOUR, last.readAtMillis());
		assertEquals(f.lastStatus().portfolio().valueNow(), last.valueFor(ViewOptions.DEFAULT));
		assertEquals(Collections.singletonList(last), disk.loadBankHistory(ACCOUNT, PROFILE).series().points());

		final ArgumentCaptor<BankHistoryPoint> points = ArgumentCaptor.forClass(BankHistoryPoint.class);
		verify(f.store, times(3)).recordBankHistory(eq(ACCOUNT), eq(PROFILE), points.capture(), eq(SEP_8));
		assertEquals(last, points.getValue());
	}

	// ---------------------------------------------------------------- owners, switches, failures

	/**
	 * Contract section 5: the series is the PRICED snapshot's owner's. Another account's bank loads that account's
	 * series (and today's point goes into it), a hop of the same account reads nothing, and no owner's point is
	 * ever written into the other's file.
	 */
	@Test
	public void anOwnerSwitchLoadsTheOtherSeriesAndNeverCrossesTheFiles()
	{
		assertTrue(disk.recordBankHistory(OTHER, PROFILE, BankHistorySeriesTest.p(LocalDate.of(2026, 9, 1), 1L, 123L),
			SEP_8));
		when(f.store.loadBank(OTHER, PROFILE)).thenReturn(PriceServiceTest.bank(PriceServiceTest.T0 - 2L * HOUR, OTHER));
		when(f.store.loadBank(ACCOUNT, PROFILE)).thenReturn(PriceServiceTest.bank(PriceServiceTest.T0, ACCOUNT));

		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		assertEquals(1, series().size());

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));
		verify(f.store, times(1)).loadBankHistory(ACCOUNT, PROFILE);

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.service.setLoggedIn(true, OTHER, PROFILE);

		assertEquals(PriceServiceTest.T0 - 2L * HOUR, f.lastStatus().bankAtMillis());
		assertEquals("the other account's older reading and today's", 2, series().size());
		assertEquals(123L, series().first().valueFor(ViewOptions.DEFAULT));
		assertEquals(PriceServiceTest.T0 - 2L * HOUR, series().on(SEP_8).bankAtMillis());

		final BankHistorySeries mine = disk.loadBankHistory(ACCOUNT, PROFILE).series();
		final BankHistorySeries theirs = disk.loadBankHistory(OTHER, PROFILE).series();
		assertEquals(1, mine.size());
		assertEquals(PriceServiceTest.T0, mine.last().bankAtMillis());
		assertEquals(2, theirs.size());
		assertEquals(PriceServiceTest.T0 - 2L * HOUR, theirs.on(SEP_8).bankAtMillis());

		f.service.setLoggedIn(false, OTHER, PROFILE);
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);

		assertEquals("back to the first account's own series", mine, series());
		verify(f.store, times(2)).loadBankHistory(ACCOUNT, PROFILE);
	}

	/**
	 * Plan 7.7 items 5 and 7: a counting switch flipped changes NO stored cell - the cells are computed with every
	 * switch on - so it writes nothing and leaves the reading as it was, while the card moves.
	 */
	@Test
	public void aCountingSwitchFlippedWritesNothing()
	{
		final BankSnapshot bank = PriceServiceTest.bankWithCarried(PriceServiceTest.T0, 791_078L);
		bank.currencyGp = 1_000_000L;
		loginWith(bank);
		final BankHistoryPoint recorded = series().last();
		final int writes = writes();

		f.clock.addAndGet(MINUTE);
		for (final ViewOptions options : new ViewOptions[]{ViewOptions.DEFAULT.withCountCash(false),
			ViewOptions.DEFAULT.withCountUntradeables(true), ViewOptions.DEFAULT.withCountInventory(false),
			ViewOptions.DEFAULT})
		{
			f.service.setOptions(options);
			assertSame("the very reading", recorded, series().last());
			assertEquals(options.toString(), f.lastStatus().portfolio().valueNow(), recorded.valueFor(options));
		}
		assertEquals(writes, writes());
	}

	/**
	 * Plan 7.1 item 5: after a FAILED load the readings are kept in memory and drawn, and nothing is written for that
	 * owner - not the first reading, not a later one.
	 */
	@Test
	public void aFailedLoadKeepsDrawingAndWritesNothing()
	{
		when(f.store.loadBankHistory(ACCOUNT, PROFILE)).thenReturn(PriceStore.BankHistoryLoad.failed());
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));

		assertEquals("drawn", 1, series().size());
		assertEquals(f.lastStatus().portfolio().valueNow(), series().last().valueFor(ViewOptions.DEFAULT));

		f.service.setBank(withMoreBoxes(PriceServiceTest.T0 + MINUTE, 70));

		assertEquals(PriceServiceTest.T0 + MINUTE, series().last().bankAtMillis());
		verify(f.store, never()).recordBankHistory(anyLong(), any(), any(), any());
		verify(f.store, atLeastOnce()).loadBankHistory(ACCOUNT, PROFILE);
		assertEquals("no file", PriceStore.BankHistoryLoad.State.MISSING, disk.loadBankHistory(ACCOUNT, PROFILE).state());
	}

	/** A store that answers null (a bare mock) reads as MISSING: the series starts, the write goes out. */
	@Test
	public void aStoreThatAnswersNothingStartsASeries()
	{
		when(f.store.loadBankHistory(anyLong(), anyString())).thenReturn(null);
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));

		assertEquals(1, series().size());
		assertEquals(1, writes());
	}

	/** The write goes through the tracked write queue, so {@link PriceService#flush()} covers it at shutdown. */
	@Test
	public void theWriteIsOneTheFlushWaitsFor()
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.scheduler.deferred = true;
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0));
		// Run the queue one task at a time until the reading is published and its write is still waiting.
		while (!f.scheduler.queued.isEmpty() && (published.isEmpty() || series().isEmpty()))
		{
			f.scheduler.runQueued(0);
		}
		assertEquals("the reading is drawn", 1, series().size());
		assertEquals("and its write not yet run", 0, writes());
		assertFalse("so the flush waits for it", f.service.flush().isDone());

		f.scheduler.deferred = false;
		f.scheduler.runPending();

		assertTrue(f.service.flush().isDone());
		assertEquals(1, writes());
		assertEquals(1, disk.loadBankHistory(ACCOUNT, PROFILE).series().size());
	}
}
