package com.bankpricemovement;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recording half's review and mutation fixes (addendum AU, review of 1bbd683): what {@link PriceService} got
 * wrong about WHEN a computation is a bank-history reading and whether its write reaches the disk.
 * <ul>
 * <li>H3 - a re-login of the account whose bank is held, on a day with no reading yet, records one.</li>
 * <li>H4 - "logged in" means the priced bank's OWNER is: another account's login is not its reading.</li>
 * <li>H5 - a write the store did not take is sent again with the next computation, never over a newer reading.</li>
 * <li>H10 - Refresh's carried re-stamp of the dev verb's made-up bank keeps it made-up.</li>
 * <li>N5 - an unchanged logout read still ends its capture's recording (amendment 9.18).</li>
 * <li>N9 - a guide price that moves under a LIVE row is a changed reading and is written.</li>
 * <li>N6 - the write's owner is the priced snapshot's, fixed under the lock (a source rule: the race cannot be
 * reached from a single-threaded test).</li>
 * </ul>
 * {@link PriceServiceTest}'s fixture by composition, with the mock store's two history methods answered by a REAL
 * {@link PriceStore} on a temporary folder, as {@link BankHistoryRecordingTest} does.
 */
public class BankHistoryRecordingReviewTest
{
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;
	private static final long ACCOUNT = PriceServiceTest.ACCOUNT;
	private static final long OTHER = PriceServiceTest.OTHER_ACCOUNT;
	private static final String PROFILE = PriceServiceTest.PROFILE;
	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);
	private static final LocalDate SEP_9 = LocalDate.of(2026, 9, 9);

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();
	private PriceStore disk;

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
		f.zonedService(ZoneOffset.UTC, false);
	}

	private void loginWith(final BankSnapshot bank)
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(bank);
	}

	private BankHistorySeries series()
	{
		return f.lastStatus().bankHistory();
	}

	private BankHistorySeries onDisk(final long account)
	{
		return disk.loadBankHistory(account, PROFILE).series();
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

	// ---------------------------------------------------------------- H3

	/**
	 * Plan 7.5 item 1, "a day's reading = any day the player logs in": the client left open overnight, the game's idle
	 * logout, and the next day the same account logs in with the sidebar on another plugin. Nothing reloads (the bank
	 * is that account's) and addendum AS reads nothing from an unchanged bank, so without its own computation the
	 * day would be drawn "carried".
	 */
	@Test
	public void aReLoginOnADayWithNoReadingYetRecordsOne()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.clock.set(PriceServiceTest.T0 + DAY);

		f.service.setLoggedIn(true, ACCOUNT, PROFILE);

		assertNotNull("the day of the login has its reading", series().on(SEP_9));
		assertNotNull("and on disk", onDisk(ACCOUNT).on(SEP_9));
		assertEquals(f.lastStatus().portfolio().valueNow(), series().on(SEP_9).valueFor(ViewOptions.DEFAULT));
		assertTrue(f.lastStatus().loggedIn());

		// A second re-login the same day finds the reading and costs no computation.
		final int trips = f.clientThread.invocations;
		final int writes = writes();
		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		assertEquals(trips, f.clientThread.invocations);
		assertEquals(writes, writes());
	}

	// ---------------------------------------------------------------- H4

	/**
	 * Between another account's login and its own bank landing from disk, the service still holds the previous
	 * account's snapshot. A computation queued in that window (the 30-minute tick, a price landing) prices it while
	 * "logged in" - but not logged in as ITS owner, who never logged in that day.
	 */
	@Test
	public void theHeldBankOfAnotherAccountIsNotItsOwnersReadingAtALogin()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		assertNotNull(onDisk(ACCOUNT).on(SEP_8));
		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.clock.set(PriceServiceTest.T0 + DAY);
		when(f.store.loadBank(OTHER, PROFILE)).thenReturn(PriceServiceTest.bank(PriceServiceTest.T0 + DAY - HOUR, OTHER));

		f.scheduler.deferred = true;
		f.service.setLoggedIn(true, OTHER, PROFILE); // queues OTHER's bank load
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false)); // queues a computation of the bank held
		// Everything but the bank load, which waits at the head of the queue.
		while (f.scheduler.queued.size() > 1)
		{
			f.scheduler.runQueued(1);
		}
		assertEquals("the computation ran over the previous account's bank", PriceServiceTest.T0,
			f.lastStatus().bankAtMillis());
		assertNull("no reading for the account that did not log in", series().on(SEP_9));
		assertNull(onDisk(ACCOUNT).on(SEP_9));

		f.scheduler.deferred = false;
		f.scheduler.runPending();

		assertNotNull("the account that did log in has its reading", onDisk(OTHER).on(SEP_9));
		assertEquals(1, onDisk(ACCOUNT).size());
	}

	// ---------------------------------------------------------------- H5

	/**
	 * A write the store did not take (a sharing violation on Windows, a clock behind the file) is sent again at the
	 * next computation, even when today's cells have not moved since - and only until one is taken.
	 */
	@Test
	public void aWriteThatFailedIsSentAgainWithTheNextComputation()
	{
		when(f.store.recordBankHistory(anyLong(), any(), any(), any())).thenReturn(false).thenAnswer(invocation ->
			disk.recordBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<BankHistoryPoint>getArgument(2), invocation.<LocalDate>getArgument(3)));
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		assertEquals(1, writes());
		assertTrue("the first write was refused", onDisk(ACCOUNT).isEmpty());
		final BankHistoryPoint reading = series().on(SEP_8);

		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertEquals("the same cells, sent again", 2, writes());
		assertEquals(reading, onDisk(ACCOUNT).on(SEP_8));

		f.service.setOptions(ViewOptions.DEFAULT);
		assertEquals("taken: nothing more to send", 2, writes());
	}

	/**
	 * The resend never sends an OLDER reading over a newer one: a write that fails after a later reading of the same
	 * day was taken leaves that later reading alone.
	 */
	@Test
	public void aFailedWriteOfAReadingSinceReplacedIsNotSentAgain()
	{
		final long first = PriceServiceTest.T0;
		final long second = first + MINUTE;
		final AtomicBoolean interleaved = new AtomicBoolean();
		when(f.store.recordBankHistory(anyLong(), any(), any(), any())).thenAnswer(invocation ->
		{
			final BankHistoryPoint point = invocation.getArgument(2);
			if (point.bankAtMillis() == first && interleaved.compareAndSet(false, true))
			{
				// While the first reading's write is out, a newer bank is read and its reading written; then the first
				// write fails. (The test scheduler runs every task at once, so this IS the executor's order.)
				f.service.setBank(withMoreBoxes(second, 40));
				return false;
			}
			return disk.recordBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1), point,
				invocation.<LocalDate>getArgument(3));
		});
		loginWith(PriceServiceTest.bank(first));
		assertTrue(interleaved.get());
		assertEquals(2, writes());
		assertEquals(second, onDisk(ACCOUNT).on(SEP_8).bankAtMillis());
		assertEquals(second, series().on(SEP_8).bankAtMillis());

		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertEquals("the failed older reading is not sent again", 2, writes());
		assertEquals(second, onDisk(ACCOUNT).on(SEP_8).bankAtMillis());
	}

	// ---------------------------------------------------------------- H10

	/**
	 * The plugin's Refresh re-stamps the bank it is drawing with a fresh carried half, persist true. When that bank is
	 * the dev verb's made-up one ({@code bpm bank=}), it must stay made-up: never saved, never a reading (plan 7.1
	 * item 6). A real capture afterwards is both.
	 */
	@Test
	public void aRefreshOfTheDevVerbsBankKeepsItMadeUp()
	{
		f.service.start();
		f.service.setLoggedIn(true, ACCOUNT, PROFILE);
		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0), false);
		final BankSnapshot held = f.service.bank();
		assertNotNull(held);

		f.service.setBank(held.withCarried(new BankReader.Carried(Collections.singletonList(
			new BankItem(PriceServiceTest.item(3), 1, "Item 3", true)), Collections.<BankItem>emptyList(), 5_000L,
			PriceServiceTest.T0 + MINUTE)));

		assertTrue(f.lastStatus().portfolio().valueNow() > 0L);
		verify(f.store, never()).saveBank(any(BankSnapshot.class));
		verify(f.store, never()).recordBankHistory(anyLong(), any(), any(), any());
		assertTrue(series().isEmpty());

		f.service.setBank(PriceServiceTest.bank(PriceServiceTest.T0 + 2L * MINUTE));
		verify(f.store).saveBank(any(BankSnapshot.class));
		assertEquals(PriceServiceTest.T0 + 2L * MINUTE, onDisk(ACCOUNT).on(SEP_8).bankAtMillis());
	}

	// ---------------------------------------------------------------- N5

	/**
	 * Amendment 9.18: the logout read ends its capture's recording even when its cells equal today's reading (the
	 * bank did not change) - so a later re-pricing of that capture at the login screen never records.
	 */
	@Test
	public void anUnchangedLogoutReadStillEndsTheCapturesRecording()
	{
		loginWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final BankHistoryPoint recorded = series().on(SEP_8);
		final int writes = writes();

		f.service.setLoggedIn(false, ACCOUNT, PROFILE);
		f.clock.addAndGet(10L * MINUTE);
		f.service.setBank(PriceServiceTest.bank(f.clock.get())); // the same contents, a NEW capture
		assertEquals("the same cells: nothing written", writes, writes());
		assertEquals(recorded, series().on(SEP_8));

		f.clock.addAndGet(HOUR);
		f.runelitePrice(PriceServiceTest.BOX, 5_000);
		f.service.setOptions(ViewOptions.DEFAULT.withCountCash(false));

		assertNotEquals("the prices did move", recorded.valueFor(ViewOptions.DEFAULT),
			f.lastStatus().portfolio().valueNow());
		assertEquals("its re-pricing at the login screen is not a reading", recorded, series().on(SEP_8));
		assertEquals(writes, writes());
	}

	// ---------------------------------------------------------------- N9

	/**
	 * Plan 7.7 item 7, "write when ANY cell changed": with live prices on, a live row's card figure is its live mid,
	 * while its guide figure is the guide price. Jagex's daily update moves the guide under an unchanged mid; the
	 * reading's guide cells then change and must be written, or "Use live prices" off would draw the day stale.
	 */
	@Test
	public void aGuideChangeUnderALiveRowIsWritten()
	{
		f.nameTheSeed();
		f.nameTheRing();
		f.warmUpLiveWith(BankHistoryInvariantTest.fixture(), Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		f.answerDay(PriceServiceTest.SEP_7, PriceServiceTest.tradedSep7());
		final ViewOptions allOn = ViewOptions.DEFAULT.withLivePrices(true).withCountCash(true)
			.withCountUntradeables(true).withCountInventory(true);
		f.service.setOptions(allOn);
		assertTrue("the whip is live", PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.WHIP).isLive());
		final BankHistoryPoint before = series().last();
		final int writes = writes();

		f.runelitePrice(PriceServiceTest.WHIP, 800_400);
		f.service.setOptions(allOn.withCountCash(false));

		assertTrue("still live", PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.WHIP).isLive());
		final BankHistoryPoint after = series().last();
		assertEquals("the live mid did not move", before.card(BankHistoryPoint.BANK_TRADEABLE),
			after.card(BankHistoryPoint.BANK_TRADEABLE));
		assertNotEquals("the guide did", before.guide(BankHistoryPoint.BANK_TRADEABLE),
			after.guide(BankHistoryPoint.BANK_TRADEABLE));
		assertEquals("so the reading was written", writes + 1, writes());
		assertEquals(after, onDisk(ACCOUNT).last());

		final ViewOptions liveOff = allOn.withLivePrices(false);
		f.service.setOptions(liveOff);
		assertEquals("and live prices off draws the card's figure", f.lastStatus().portfolio().valueNow(),
			after.valueFor(liveOff));
	}

	// ---------------------------------------------------------------- N6

	/**
	 * The write's owner is the PRICED snapshot's, fixed under commit's lock - never the service's {@code bank} field
	 * read later, which a client-thread {@code setBank} can replace between the lock's release and the write (the
	 * old point would then land in the new owner's file). No single-threaded test reaches that window, and contract
	 * section 0 forbids a seam for it, so this pins the rule in the source: the store is called with the owner the
	 * write carries, and the write is built from {@code foldBankHistoryLocked}'s {@code PricedBank} parameter.
	 */
	@Test
	public void theWritesOwnerIsThePricedSnapshotsFixedUnderTheLock() throws IOException
	{
		final String source = priceServiceSource();

		final Matcher calls = Pattern.compile("store\\.recordBankHistory\\(").matcher(source);
		int count = 0;
		while (calls.find())
		{
			count++;
		}
		assertEquals("one call of the store's record", 1, count);
		assertTrue("the store is handed the write's own owner", Pattern.compile(
			"store\\.recordBankHistory\\(\\s*write\\.accountHash\\s*,\\s*write\\.profileType\\s*,").matcher(source).find());

		final int fold = source.indexOf("foldBankHistoryLocked(final PricedBank bank,");
		assertTrue("the fold takes the priced snapshot as `bank`", fold >= 0);
		final int foldEnd = source.indexOf("\n\t}\n", fold);
		final String foldBody = source.substring(fold, foldEnd);
		assertTrue("the write is built from that parameter", Pattern.compile(
			"new BankHistoryWrite\\(\\s*bank\\.accountHash\\s*,\\s*bank\\.profileType\\s*,").matcher(foldBody).find());
		assertFalse("and never from the field", foldBody.contains("this.bank"));
		assertEquals("built nowhere else", source.indexOf("new BankHistoryWrite("), source.lastIndexOf(
			"new BankHistoryWrite("));
	}

	private static String priceServiceSource() throws IOException
	{
		final String relative = "src/main/java/com/bankpricemovement/PriceService.java";
		Path here = Paths.get("").toAbsolutePath();
		for (int up = 0; up < 4 && here != null; up++, here = here.getParent())
		{
			final Path candidate = here.resolve(relative);
			if (Files.isRegularFile(candidate))
			{
				return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8).replace("\r\n", "\n");
			}
		}
		throw new AssertionError("PriceService.java not found from " + Paths.get("").toAbsolutePath());
	}
}
