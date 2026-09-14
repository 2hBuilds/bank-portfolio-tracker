package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;

/**
 * {@link PriceService} against contract C20-C27 as rewritten by addendum K and again by addendum L (L1, L3,
 * L5, L7, L10, L11, L13), headless and synchronous (C27): the injected executor is a {@link DirectScheduler}
 * that runs {@code execute} inline (or queues it when a test wants to interleave) and records every periodic
 * timer so a test fires it by hand; the clock is an {@link AtomicLong}; the EDT consumer is a
 * {@link RecordingEdt} that runs inline unless a test wants to prove the hop; the client thread is a
 * {@link FakeClientThread} subclass with the same inline-or-queue switch; the guide client is a Mockito mock
 * whose futures the test completes itself. No sleeps, no latches, no real thread anywhere (playbook 7.6).
 *
 * <p><b>The recorded history.</b> The revision index is the REAL one from
 * {@code docs/research/bank-price-movement-calibration-2026-09-08.md} and addendum L's evidence lines (ids and
 * timestamps of 2026-09-03..08, 2026-08-09, 2026-03-12, and Riblet15's stale edit 15329323 of 2026-09-03T06:46Z
 * carrying 2026-09-02's table - L-E), padded with SYNTHETIC bot revisions on the dates the doc did not quote
 * (2026-08-30..09-02 and 2026-06-09), each marked so below. Every body is generated from its date: "Item n"
 * costs {@code 1000 * n + <days since 2026-01-01>}, so any two days differ on every item and a wrong day shows
 * up as a wrong number; "Green hat" carries the calibration doc's real figures (1,124 on 07 Sep, 1,086 on 08 Sep:
 * -38 gp, -3.4 %, the GE site's "today"); "Shark" is flat so a one-day move of 0 exists; the tablet is named only
 * by the {@code /mapping} table (L8 a); "Karambwan vessel" is the L8 b trap (id 3159's composition name is
 * another id's wiki name, so its fallback must be refused). RuneLite's table is switched between days with
 * {@link #runeliteOn} - agreement with R0 is what derives the anchor day (L3), never the clock.
 */
public class PriceServiceTest
{
	// ---------------------------------------------------------------- the items

	private static final int GREEN_HAT = 658;
	private static final int WHIP = 4151;
	private static final int SHARK = 385;
	private static final int BONES = 526;
	/** "Varrock teleport" by composition; the wiki names it "Varrock teleport (tablet)" (calibration doc: the 29 misses). */
	private static final int TABLET = 8007;
	/** "Mystery box" - priced by RuneLite, named by no table and no mapping. */
	private static final int BOX = 6199;
	/** The unbaited vessel: the mapping's owner of the name "Karambwan vessel". */
	private static final int VESSEL = 3157;
	/**
	 * The baited vessel (L-F: id 3159, in the user's own bank): its composition name is "Karambwan vessel", the
	 * UNBAITED item's wiki name, and the mapping in this fixture does not list 3159 - so the L8 b fallback must
	 * refuse it rather than hand it the other item's price.
	 */
	private static final int VESSEL_BAITED = 3159;
	/** Synthetic "Item 1".."Item 24": enough comparable items to clear L3's n >= 20 gate. */
	private static final int ITEMS = 24;
	private static final int ITEM_BASE = 10_000;

	/**
	 * L-F's own example, id 11980: {@code ItemMapping} folds it onto the plain Ring of wealth (2572), so
	 * {@code ItemManager} answers 2572's price for it. It is in the user's captured bank.
	 */
	private static final int RING_OF_WEALTH_5 = 11_980;
	/** Both Crystal 2h axe ids are mapped, and the guide table has no key for either (B001). */
	private static final int CRYSTAL_2H_AXE = 28_220;
	private static final String RING_NAME = "Ring of wealth (5)";
	private static final long RING_GP = 13_260L;

	private static final long ACCOUNT = 0x1234_5678_9abcL;
	private static final long OTHER_ACCOUNT = 0x9999L;
	private static final String PROFILE = "STANDARD";
	/** The bank's cash for the P1 tests - 200,000,000 gp, the figure the addendum's {@code bank=} example stages. */
	private static final long CURRENCY = 200_000_000L;
	private static final long SECOND = 1_000L;
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24L * HOUR;

	// ---------------------------------------------------------------- the dates (UTC, L9)

	private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
	private static final LocalDate MAR_12 = LocalDate.of(2026, 3, 12);
	private static final LocalDate JUN_9 = LocalDate.of(2026, 6, 9);
	/** {@code SEP_8 - 90 days}: the day the LIVE series wants for 90d, one day after the guide baseline's (U1). */
	private static final LocalDate JUN_10 = LocalDate.of(2026, 6, 10);
	private static final LocalDate AUG_9 = LocalDate.of(2026, 8, 9);
	private static final LocalDate AUG_30 = LocalDate.of(2026, 8, 30);
	private static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);
	private static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1);
	private static final LocalDate SEP_2 = LocalDate.of(2026, 9, 2);
	private static final LocalDate SEP_3 = LocalDate.of(2026, 9, 3);
	private static final LocalDate SEP_4 = LocalDate.of(2026, 9, 4);
	private static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);
	private static final LocalDate SEP_6 = LocalDate.of(2026, 9, 6);
	private static final LocalDate SEP_7 = LocalDate.of(2026, 9, 7);
	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);
	private static final LocalDate SEP_9 = LocalDate.of(2026, 9, 9);

	/** 2026-09-08T20:20:00Z - the evening of the first live session, RuneLite and the wiki both on 08 Sep. */
	private static final long T0 = utcMillis(SEP_8, 20, 20, 0);

	// ---------------------------------------------------------------- the recorded index (newest first)

	/** 15334656 @ 2026-09-08T09:25:12Z (real). */
	private static final RevisionRef BOT_0908 = bot(15_334_656L, SEP_8, 9, 25, 12);
	/** 15333448 @ 2026-09-07T19:55:12Z (real) - the revision addendum K's own example resolved to. */
	private static final RevisionRef BOT_0907 = bot(15_333_448L, SEP_7, 19, 55, 12);
	/** 15332677 @ 2026-09-06T19:15:12Z (real). */
	private static final RevisionRef BOT_0906 = bot(15_332_677L, SEP_6, 19, 15, 12);
	/** 15331360 @ 2026-09-05T08:45:11Z (real). */
	private static final RevisionRef BOT_0905 = bot(15_331_360L, SEP_5, 8, 45, 11);
	/** 15330652 @ 2026-09-04T07:45:12Z (real). */
	private static final RevisionRef BOT_0904 = bot(15_330_652L, SEP_4, 7, 45, 12);
	/** 15330300 @ 2026-09-03T21:45:11Z (real). */
	private static final RevisionRef BOT_0903 = bot(15_330_300L, SEP_3, 21, 45, 11);
	/** 15329323 @ 2026-09-03T06:46:00Z, Riblet15, "new items with initial ge prices" (real): its body holds 02 Sep's table (L-E). */
	private static final RevisionRef HUMAN_0903 = new RevisionRef(15_329_323L, utcSeconds(SEP_3, 6, 46, 0), "Riblet15",
		"new items with initial ge prices");
	/** SYNTHETIC: the bot's runs on 02 Sep .. 30 Aug, at noon. */
	private static final RevisionRef BOT_0902 = bot(15_328_800L, SEP_2, 12, 0, 0);
	private static final RevisionRef BOT_0901 = bot(15_328_200L, SEP_1, 12, 0, 0);
	private static final RevisionRef BOT_0831 = bot(15_327_600L, AUG_31, 12, 0, 0);
	private static final RevisionRef BOT_0830 = bot(15_327_000L, AUG_30, 12, 0, 0);
	/** 15290673 @ 2026-08-09T04:45:13Z (real) - what a 30d window landed on during the calibration run. */
	private static final RevisionRef BOT_0809 = bot(15_290_673L, AUG_9, 4, 45, 13);
	/** SYNTHETIC: 09 Jun - and no 10 Jun, so a 90d window from 08 Sep has to WALK BACK a day (L5). */
	private static final RevisionRef BOT_0609 = bot(15_220_000L, JUN_9, 12, 0, 0);
	/** 15148129 @ 2026-03-12T18:26:53Z (real) - what a 180d window landed on. */
	private static final RevisionRef BOT_0312 = bot(15_148_129L, MAR_12, 18, 26, 53);

	private static final List<RevisionRef> HISTORY = Collections.unmodifiableList(Arrays.asList(
		BOT_0908, BOT_0907, BOT_0906, BOT_0905, BOT_0904, BOT_0903, HUMAN_0903, BOT_0902, BOT_0901, BOT_0831,
		BOT_0830, BOT_0809, BOT_0609, BOT_0312));

	// ---------------------------------------------------------------- the seams (C27)

	private final AtomicLong clock = new AtomicLong(T0);
	private final DirectScheduler scheduler = new DirectScheduler();
	private final FakeClientThread clientThread = new FakeClientThread();
	private final RecordingEdt edt = new RecordingEdt();
	private final GuidePriceClient wiki = mock(GuidePriceClient.class);
	private final PriceStore store = mock(PriceStore.class);
	private final ItemManager itemManager = mock(ItemManager.class);

	/** Every future the mocked client handed out, in call order; the test completes them. */
	private final List<CompletableFuture<List<RevisionRef>>> indexFutures = new ArrayList<>();
	private final List<CompletableFuture<Map<Long, GuideSnapshot>>> tableFutures = new ArrayList<>();
	private final List<CompletableFuture<Map<Integer, String>>> mappingFutures = new ArrayList<>();
	/** The revision ids of every {@code fetchTables} call, in call order and in the order asked. */
	private final List<List<Long>> tableRequests = new ArrayList<>();

	/** The index the wiki answers with and the bodies are generated from; a test may swap it. */
	private List<RevisionRef> history = HISTORY;
	/** Bodies answered instead of the generated one for a revision id (the stale-body fixtures of L5). */
	private final Map<Long, GuideSnapshot> bodyOverrides = new HashMap<>();
	/** Revision ids the wiki answers WITHOUT a body (an unusable revision, L7). */
	private final Set<Long> omitBodies = new HashSet<>();
	/** RuneLite's guide table: item id to price; 0 or absent = no price (L1). */
	private final Map<Integer, Integer> runelite = new HashMap<>();

	/**
	 * The traded feeds of addendum T. Deliberately NOT handed to the service {@link #setUp} builds: with no
	 * traded client there is no traded request and no live row, so every test written before addendum T runs
	 * against exactly the service it was written for. The T section builds its own with {@link #liveService()}.
	 */
	private final TradedPriceClient traded = mock(TradedPriceClient.class);
	private final List<CompletableFuture<Map<Integer, TradedPriceClient.Quote>>> latestFutures = new ArrayList<>();
	/** The day of every {@code fetchDay} call, in call order, with its future beside it. */
	private final List<LocalDate> dayRequests = new ArrayList<>();
	private final List<CompletableFuture<Map<Integer, TradedPriceClient.Bucket>>> dayFutures = new ArrayList<>();

	private final List<List<MovementRow>> publishedRows = new ArrayList<>();
	private final List<PriceService.Status> publishedStatus = new ArrayList<>();

	private PriceService service;

	@Before
	public void setUp()
	{
		when(store.loadMapping()).thenReturn(storedMapping(mappingTable(), T0 - DAY));
		when(store.loadRevisionIndex()).thenReturn(storedIndex(Collections.<RevisionRef>emptyList(), 0L));
		when(store.loadBucket(any(MovementWindow.class))).thenReturn(PriceMap.EMPTY);
		when(store.loadBank(anyLong(), anyString())).thenReturn(BankSnapshot.EMPTY);
		when(wiki.fetchRevisionIndex(anyLong())).thenAnswer(invocation ->
		{
			final CompletableFuture<List<RevisionRef>> future = new CompletableFuture<>();
			indexFutures.add(future);
			return future;
		});
		when(wiki.fetchTables(anyCollection(), anyLong())).thenAnswer(invocation ->
		{
			tableRequests.add(new ArrayList<>(invocation.<List<Long>>getArgument(0)));
			final CompletableFuture<Map<Long, GuideSnapshot>> future = new CompletableFuture<>();
			tableFutures.add(future);
			return future;
		});
		when(wiki.fetchMapping(anyLong())).thenAnswer(invocation ->
		{
			final CompletableFuture<Map<Integer, String>> future = new CompletableFuture<>();
			mappingFutures.add(future);
			return future;
		});
		// T2: the traded feeds and their files. Stubbed here so a test that never asks for them still runs against
		// the store's real "there is nothing on disk" answers rather than a mock's nulls.
		when(store.loadTradedLatest())
			.thenReturn(new PriceStore.Stamped<>(Collections.<Integer, TradedPriceClient.Quote>emptyMap(), 0L));
		when(store.loadTradedDay(any(MovementWindow.class))).thenReturn(PriceStore.TradedDay.EMPTY);
		when(traded.fetchLatest(anyLong())).thenAnswer(invocation ->
		{
			final CompletableFuture<Map<Integer, TradedPriceClient.Quote>> future = new CompletableFuture<>();
			latestFutures.add(future);
			return future;
		});
		when(traded.fetchDay(any(LocalDate.class), anyLong())).thenAnswer(invocation ->
		{
			dayRequests.add(invocation.getArgument(0));
			final CompletableFuture<Map<Integer, TradedPriceClient.Bucket>> future = new CompletableFuture<>();
			dayFutures.add(future);
			return future;
		});

		// L1: RuneLite's guide table, the price field, switched by day; bones are never priced (0 = no price).
		runeliteOn(SEP_8);
		when(itemManager.getItemPriceWithSource(anyInt(), eq(false)))
			.thenAnswer(invocation -> runelite.getOrDefault(invocation.<Integer>getArgument(0), 0));
		// L8 b: the composition's members name (built first, stubbed after - a mock made inside a stub trips Mockito).
		final Map<Integer, ItemComposition> compositions = new HashMap<>();
		for (final BankItem item : bank(T0).items)
		{
			compositions.put(item.id, composition(item.name));
		}
		when(itemManager.getItemComposition(anyInt())).thenAnswer(invocation -> compositions.get(invocation.<Integer>getArgument(0)));

		service = new PriceService(wiki, store, itemManager, clientThread, scheduler, clock::get, edt);
		service.addListener((rows, status) ->
		{
			publishedRows.add(rows);
			publishedStatus.add(status);
		});
		// addListener delivers the initial state once; the tests count publishes from here.
		publishedRows.clear();
		publishedStatus.clear();
	}

	// ---------------------------------------------------------------- L3: the derived anchor day, at the thresholds

	@Test
	public void theAnchorDayIsR0sDayWhenEnoughItemsAgree()
	{
		assertEquals(SEP_8, PriceService.deriveAnchorDay(20, 20, SEP_8));
		assertEquals("18 of 20 is exactly 0.90 - on the threshold counts as agreement",
			SEP_8, PriceService.deriveAnchorDay(18, 20, SEP_8));
		assertEquals(SEP_8, PriceService.deriveAnchorDay(524, 524, SEP_8));
	}

	@Test
	public void theAnchorDayIsTheNextDayWhenEnoughItemsDisagree()
	{
		assertEquals("17 of 20 is 0.85: RuneLite already holds the next Jagex day (L-D)",
			SEP_9, PriceService.deriveAnchorDay(17, 20, SEP_8));
		assertEquals(SEP_9, PriceService.deriveAnchorDay(0, 20, SEP_8));
		assertEquals("41 % agreement is the measured one-day-apart figure", SEP_9,
			PriceService.deriveAnchorDay(215, 524, SEP_8));
	}

	@Test
	public void tooFewComparableItemsFallBackToR0sDay()
	{
		assertEquals("19 samples is below the n >= 20 gate whatever they say", SEP_8,
			PriceService.deriveAnchorDay(0, 19, SEP_8));
		assertEquals(SEP_8, PriceService.deriveAnchorDay(19, 19, SEP_8));
		assertEquals(SEP_8, PriceService.deriveAnchorDay(0, 0, SEP_8));
		assertEquals(SEP_8, PriceService.deriveAnchorDay(0, -1, SEP_8));
	}

	@Test
	public void noR0MeansNoAnchorDay()
	{
		assertNull(PriceService.deriveAnchorDay(20, 20, null));
		assertNull(PriceService.deriveAnchorDay(0, 0, null));
	}

	@Test
	public void theThresholdConstantsAreTheOnesAddendumLNames()
	{
		assertEquals(20, PriceService.AGREE_MIN_SAMPLES);
		assertEquals(0.90d, PriceService.AGREE_THRESHOLD, 0.0d);
		assertEquals("rows every 30 minutes while visible (L1)", 30L * MINUTE, PriceService.TICK_MS);
		assertEquals("the index every 6 hours (L4, L10)", 6L * HOUR, PriceService.HISTORY_MAX_AGE_MS);
		assertEquals(7L * DAY, PriceService.MAPPING_MAX_AGE_MS);
		assertEquals(30L * SECOND, PriceService.MANUAL_COOLDOWN_MS);
		assertEquals("Guide prices", PriceService.HEADER_PREFIX);
	}

	// ---------------------------------------------------------------- L5: pickBaseline, the pure half

	@Test
	public void pickBaselineIsPickThenOnTheWindowsTargetDate()
	{
		assertEquals(BOT_0907, PriceService.pickBaseline(HISTORY, SEP_8, MovementWindow.D1));
		assertEquals(BOT_0901, PriceService.pickBaseline(HISTORY, SEP_8, MovementWindow.D7));
		assertEquals(BOT_0809, PriceService.pickBaseline(HISTORY, SEP_8, MovementWindow.D30));
		assertEquals("10 Jun has no revision: the walk-back lands on 09 Jun", BOT_0609,
			PriceService.pickBaseline(HISTORY, SEP_8, MovementWindow.D90));
		assertEquals(BOT_0312, PriceService.pickBaseline(HISTORY, SEP_8, MovementWindow.D180));
		assertEquals("with RuneLite a day ahead the 1d baseline is the newest table itself", BOT_0908,
			PriceService.pickBaseline(HISTORY, SEP_9, MovementWindow.D1));
	}

	@Test
	public void pickBaselinePrefersTheBotOverAHumanEditOnTheSameDate()
	{
		assertEquals("03 Sep has Riblet15's 06:46 edit and the bot's 21:45 run: the bot's", BOT_0903,
			PriceService.pickBaseline(HISTORY, SEP_4, MovementWindow.D1));
	}

	@Test
	public void pickBaselineAnswersNullWithoutAnAnchorOrBeyondTheIndex()
	{
		assertNull("no anchor day: no target, no baseline, no guess", PriceService.pickBaseline(HISTORY, null, MovementWindow.D1));
		assertNull(PriceService.pickBaseline(HISTORY, SEP_8, null));
		assertNull(PriceService.pickBaseline(Collections.emptyList(), SEP_8, MovementWindow.D1));
		assertNull("the index starts on 12 Mar: a 180d window from 01 Sep predates it (L11)",
			PriceService.pickBaseline(HISTORY, SEP_1, MovementWindow.D180));
	}

	// ---------------------------------------------------------------- L8: project, owners, the refused fallback

	@Test
	public void projectJoinsTheMappingOntoIdsAndStampsTheTablesOwnDay()
	{
		final GuideSnapshot table = snapshot(BOT_0907, T0);

		final PriceMap map = PriceService.project(table, PriceService.foldedNamesOf(mappingTable()),
			PriceService.ownersOf(mappingTable()), bank(T0).items);

		assertEquals(Long.valueOf(1_124L), map.get(GREEN_HAT).mid());
		assertEquals("the tablet only resolves through the mapping (L8 a)", Long.valueOf(290L + dayIndex(SEP_7)), map.get(TABLET).mid());
		assertEquals(Long.valueOf(3_235L), map.get(VESSEL).mid());
		assertNull("the baited vessel's composition name is another id's wiki name: refused (L8 b)", map.get(VESSEL_BAITED));
		assertNull(map.get(BOX));
		assertNull(map.get(BONES));
		assertEquals("bucketSeconds is the table's own day marker, not the save time (L7)", dataSecondsFor(BOT_0907), map.bucketSeconds());
		assertEquals(SEP_7, map.dataDay());
		assertEquals(BOT_0907.revId(), map.revId());
		assertEquals(T0, map.fetchedAtMillis());
	}

	@Test
	public void projectFallsBackToTheBankNameOnlyWhenNoOtherIdOwnsIt()
	{
		final GuideSnapshot table = snapshot(BOT_0907, T0);
		final List<BankItem> items = Collections.singletonList(new BankItem(VESSEL_BAITED, 1, "Karambwan vessel", false));

		final PriceMap withOwners = PriceService.project(table, PriceService.foldedNamesOf(mappingTable()),
			PriceService.ownersOf(mappingTable()), items);
		final PriceMap noMapping = PriceService.project(table, Collections.emptyMap(), Collections.emptyMap(), items);

		assertNull("refused: 3157 owns the name in the mapping", withOwners.get(VESSEL_BAITED));
		assertEquals("with no mapping at all there is nothing to refuse against - the K3 ladder stands",
			Long.valueOf(3_235L), noMapping.get(VESSEL_BAITED).mid());
	}

	@Test
	public void ownersFoldThroughTheOneKeyRule()
	{
		final Map<Integer, String> mapping = new LinkedHashMap<>();
		mapping.put(1, "3rd Age amulet");
		mapping.put(2, "Green  hat ");

		final Map<String, Integer> owners = PriceService.ownersOf(mapping);

		assertEquals(Integer.valueOf(1), owners.get("3rd age amulet"));
		assertEquals("whitespace collapsed, trimmed, lower-cased with Locale.ROOT (L8)", Integer.valueOf(2), owners.get("green hat"));
		assertTrue(PriceService.ownersOf(null).isEmpty());
		assertTrue(PriceService.ownersOf(Collections.emptyMap()).isEmpty());
	}

	@Test
	public void fallbackGpRefusesAnotherIdsNameAndTakesItsOwn()
	{
		final GuideSnapshot table = snapshot(BOT_0907, T0);
		final Map<String, Integer> owners = PriceService.ownersOf(mappingTable());

		assertNull(PriceService.fallbackGp(table, "Karambwan vessel", VESSEL_BAITED, owners));
		assertEquals("the owner itself may use it", Long.valueOf(3_235L), PriceService.fallbackGp(table, "Karambwan vessel", VESSEL, owners));
		assertNull("another id's name is refused whatever the case (L8 b)", PriceService.fallbackGp(table, "green HAT", 999, owners));
		final GuideSnapshot unowned = new GuideSnapshot(1L, 2L, 3L, 4L, Collections.singletonMap("Unowned thing", 42L));
		assertEquals("a name no id owns is looked up through the one folding rule", Long.valueOf(42L),
			PriceService.fallbackGp(unowned, "unowned  THING", 999, owners));
		assertNull(PriceService.fallbackGp(table, null, GREEN_HAT, owners));
		assertNull(PriceService.fallbackGp(table, "", GREEN_HAT, owners));
		assertNull(PriceService.fallbackGp(GuideSnapshot.EMPTY, "Green hat", GREEN_HAT, owners));
		assertNull(PriceService.fallbackGp(null, "Green hat", GREEN_HAT, owners));
	}

	// ---------------------------------------------------------------- L7: the status text and its day labels

	@Test
	public void theDayLabelIsTheUtcDayInEnglish()
	{
		assertEquals("07 Sep", PriceService.Status.dayLabel(SEP_7));
		assertEquals("09 Jun", PriceService.Status.dayLabel(JUN_9));
		assertEquals(MovementMath.DASH, PriceService.Status.dayLabel(null));
	}

	@Test
	public void theStatusLineNamesTheGuideDayNeverAClock()
	{
		final PriceService.Status status = new PriceService.Status(T0, T0 - HOUR, true, true,
			MovementRow.PriceSource.GUIDE, null, null, 3, 4, MovementWindow.D1, true, dataSecondsFor(BOT_0907),
			BOT_0907.revId(), T0 - DAY, T0 - HOUR, SEP_8, 1.0d, 28, SEP_8, BOT_0908.revId(), false, false, null, null);

		assertEquals("Guide prices - 1d vs 07 Sep - Bank as of " + MovementMath.formatTime(T0 - HOUR), status.text());
		assertEquals("1d vs 07 Sep", status.baselineText());
		assertEquals(SEP_7, status.thenDay());
		assertEquals(BOT_0907.revId(), status.thenRevId());
		assertEquals(BOT_0907.revId(), status.baselineRevId());
		assertEquals(SEP_8, status.anchorDay());
		assertEquals(SEP_8, status.r0Day());
		assertEquals(1.0d, status.agree(), 0.0d);
		assertEquals(28, status.agreeSamples());
		assertFalse(status.degraded());
		assertNull(status.degradedReason());
	}

	@Test
	public void theStatusLineVariants()
	{
		final PriceService.Status noBank = new PriceService.Status(0L, 0L, false, false, null, null, null, 0, 0, null,
			false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, null, null);
		assertEquals("Guide prices - No bank yet", noBank.text());
		assertSame("a null summary reads as EMPTY (M3)", PortfolioSummary.EMPTY, noBank.portfolio());
		assertNull(noBank.baselineText());
		assertNull(noBank.thenDay());
		assertEquals(MovementWindow.D1, noBank.window());
		assertEquals(MovementRow.PriceSource.NONE, noBank.source());

		final PriceService.Status loggedOut = new PriceService.Status(T0, T0, true, false, MovementRow.PriceSource.GUIDE,
			null, null, 1, 1, MovementWindow.D30, false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, null, null);
		assertEquals("Guide prices - Bank as of " + MovementMath.formatTime(T0) + " (logged out)", loggedOut.text());

		final PortfolioSummary summary = new PortfolioSummary(5L, 1, 1, null);
		final PriceService.Status problem = new PriceService.Status(T0, T0, true, true, MovementRow.PriceSource.GUIDE,
			"No 30d history", PriceService.ProblemKind.NO_HISTORY, 1, 1, MovementWindow.D30, false, 0L, 0L, 0L, 0L,
			SEP_8, 1.0d, 28, SEP_8, 1L, true, false, "why", summary);
		assertEquals("the problem replaces the line", "No 30d history", problem.text());
		assertEquals("the header is still there for a tooltip", "Guide prices - Bank as of " + MovementMath.formatTime(T0), problem.headerText());
		assertTrue(problem.degraded());
		assertEquals("why", problem.degradedReason());
		assertSame("the summary rides on the status (M3)", summary, problem.portfolio());
		assertTrue(problem.toString(), problem.toString().contains("bankValue=5"));

		final PriceService.Status notDegraded = new PriceService.Status(T0, T0, true, true, null, null, null, 0, 0, null,
			false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, "ignored", null);
		assertNull("a reason without the flag is dropped", notDegraded.degradedReason());
		assertNotEquals("the summary is part of a status's identity", problem,
			new PriceService.Status(T0, T0, true, true, MovementRow.PriceSource.GUIDE, "No 30d history",
				PriceService.ProblemKind.NO_HISTORY, 1, 1, MovementWindow.D30, false, 0L, 0L, 0L, 0L, SEP_8, 1.0d, 28,
				SEP_8, 1L, true, false, "why", null));
	}

	@Test
	public void theProblemSentences()
	{
		assertEquals("Wiki history down - no movement", PriceService.problemHistoryUnavailable());
		assertEquals("No 30d history yet", PriceService.problemHistoryPending(MovementWindow.D30));
		assertEquals("No 180d history", PriceService.problemNoHistory(MovementWindow.D180));
		assertEquals("No 1d history", PriceService.problemNoHistory(null));
		assertEquals("Refreshed 12 s ago - wait", PriceService.problemCooldown(12L));
		assertEquals("Refreshed 0 s ago - wait", PriceService.problemCooldown(-5L));
	}

	/**
	 * The panel paints a problem row red for a FAULT and grey for a fact of life, and it must be able to tell
	 * them apart without rebuilding the sentences and comparing strings - a wording change would otherwise
	 * silently recolour a line. Exactly the two severe kinds are the ones that were red.
	 */
	@Test
	public void everyProblemKindKnowsWhetherItIsSevere()
	{
		assertFalse(PriceService.ProblemKind.NONE.severe());
		assertFalse("a request is out: normal", PriceService.ProblemKind.PENDING.severe());
		assertFalse("the index simply does not reach that far back (L11): normal", PriceService.ProblemKind.NO_HISTORY.severe());
		assertTrue("a failed fetch is a fault", PriceService.ProblemKind.HISTORY_DOWN.severe());
		assertTrue("a refused refresh is a fault", PriceService.ProblemKind.COOLDOWN.severe());
	}

	/** A status with no sentence has no kind either, whatever a caller offers - the two are decided together. */
	@Test
	public void aStatusWithoutASentenceIsAlwaysKindNone()
	{
		final PriceService.Status quiet = new PriceService.Status(T0, T0, true, true, null, null,
			PriceService.ProblemKind.HISTORY_DOWN, 0, 0, null, false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L,
			false, false, null, null);

		assertNull(quiet.problem());
		assertEquals(PriceService.ProblemKind.NONE, quiet.problemKind());
		assertFalse(quiet.problemKind().severe());
		assertEquals("and a kind offered with a sentence is kept", PriceService.ProblemKind.NO_HISTORY,
			new PriceService.Status(T0, T0, true, true, null, "No 180d history", PriceService.ProblemKind.NO_HISTORY,
				0, 0, null, false, 0L, 0L, 0L, 0L, null, -1.0d, 0, null, 0L, false, false, null, null).problemKind());
	}

	// ---------------------------------------------------------------- C22: start, stop, flush

	@Test
	public void startLoadsTheMappingTheIndexAndTheBaselinesAndFetchesNothing()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - HOUR));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0 - HOUR));

		service.start();

		// Once each, and the stamps below come out of those same two reads: the content and the moment it was
		// fetched arrive together, so start-up never parses a 150 KB file twice for one long.
		verify(store).loadMapping();
		verify(store).loadRevisionIndex();
		for (final MovementWindow window : MovementWindow.values())
		{
			verify(store).loadBucket(window);
		}
		final PriceService.Status status = lastStatus();
		assertTrue("the 1d baseline came from disk", status.baselineLoaded());
		assertEquals(SEP_7, status.thenDay());
		assertEquals(BOT_0907.revId(), status.baselineRevId());
		assertEquals(T0 - DAY, status.mappingAtMillis());
		assertEquals(T0 - HOUR, status.indexAtMillis());
		assertEquals(HISTORY, service.revisionIndex());
		assertNull("no R0 yet, so no anchor", status.anchorDay());
		assertNull(status.r0Day());
		assertFalse(status.bankLoaded());
		assertEquals(MovementRow.PriceSource.NONE, status.source());
		assertTrue(lastRows().isEmpty());
		assertEquals("Guide prices - 1d vs 07 Sep - No bank yet", status.text());
		verify(wiki, never()).fetchRevisionIndex(anyLong());
		verify(wiki, never()).fetchTables(anyCollection(), anyLong());
		verify(wiki, never()).fetchMapping(anyLong());
	}

	@Test
	public void startIsIdempotentAndTheInitialStatusIsGuidePricesNoBankYet()
	{
		assertEquals("Guide prices - No bank yet", service.currentStatus().text());
		assertTrue(service.currentRows().isEmpty());
		assertEquals(RowFilter.DEFAULT, service.filter());
		assertEquals(MovementWindow.D1, service.currentStatus().window());
		assertEquals(PriceMap.EMPTY, service.baseline(MovementWindow.D1));
		assertEquals(PriceMap.EMPTY, service.baseline(null));

		service.start();
		service.start();

		verify(store, times(1)).loadMapping();
		verify(store, times(1)).loadRevisionIndex();
	}

	/**
	 * A baseline file without a revision id is a trade-era file (K11): the start-up sweep is the first lock on
	 * that door and this is the second.
	 */
	@Test
	public void aDiskBaselineWithoutARevisionIdIsRefused()
	{
		final Map<Integer, PricePoint> tradeAverages = new HashMap<>();
		tradeAverages.put(WHIP, new PricePoint(11L, null));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(new PriceMap(tradeAverages, T0 - HOUR, 1_788_822_000L));

		service.start();

		assertFalse(lastStatus().baselineLoaded());
		assertEquals(0L, lastStatus().baselineRevId());
		assertNull(lastStatus().thenDay());
		assertEquals("Guide prices - No bank yet", lastStatus().text());
	}

	/**
	 * B028. A baseline written by an OLDER BUILD carries a revision id exactly like a current one, so the test
	 * above cannot see it - and yet its fields need not mean what this build reads them as: addendum K stored
	 * the revision's SAVE time in {@code bucketSeconds} where addendum L stores the table's own
	 * {@code %LAST_UPDATE%}, a day apart often enough to flip the sign on a row. Adopting one teaches
	 * {@code rememberDayLocked} a wrong calendar day for a real revision, and {@code resolveLocked} then accepts
	 * that day without ever refetching the body. The same map at the current schema IS adopted -
	 * {@link #startLoadsTheMappingTheIndexAndTheBaselinesAndFetchesNothing} is that half of the rule.
	 */
	@Test
	public void aDiskBaselineFromAnOlderSchemaIsRefused()
	{
		final PriceMap current = diskBaseline(BOT_0907, T0 - HOUR);
		when(store.loadBucket(MovementWindow.D1)).thenReturn(new PriceMap(current.points(),
			current.fetchedAtMillis(), current.bucketSeconds(), current.revId(), PriceMapDto.SCHEMA - 1));

		service.start();

		assertFalse("a file from a build whose fields meant something else is refetched, not believed",
			lastStatus().baselineLoaded());
		assertEquals(0L, lastStatus().baselineRevId());
		assertNull(lastStatus().thenDay());
		assertEquals("Guide prices - No bank yet", lastStatus().text());
	}

	@Test
	public void stopCancelsTheTimerAndDropsLateCallbacks()
	{
		service.start();
		service.setVisible(true);
		assertEquals(1, scheduler.timers.size());
		fireTick(); // the activation tick sends the index request
		assertEquals(1, indexFutures.size());
		final int publishesBefore = publishedStatus.size();

		service.stop();

		for (final Timer timer : scheduler.timers)
		{
			assertTrue("the scheduled future is cancelled by stop()", timer.future.isCancelled());
		}
		answerIndex();
		assertEquals("a fetch completing after stop() publishes nothing", publishesBefore, publishedStatus.size());
		verify(store, never()).saveRevisionIndex(anyList(), anyLong());
		assertTrue("the late index was not adopted either", service.revisionIndex().isEmpty());
		assertTrue("and nothing was fetched on the back of it", tableRequests.isEmpty());

		service.setBank(bank(T0));
		service.setFilter(RowFilter.DEFAULT.withGpMin(5L));
		service.refreshNow();
		service.setVisible(true);
		assertEquals("nothing after stop() reaches a listener", publishesBefore, publishedStatus.size());
		verify(store, never()).saveBank(any(BankSnapshot.class));
		assertEquals("no request after stop()", 1, indexFutures.size());
		assertFalse(service.isVisible());
	}

	@Test
	public void stopKeepsAQueuedWriteAndFlushCoversIt()
	{
		scheduler.deferred = true;
		assertTrue("nothing pending: flush is already complete", service.flush().isDone());

		service.setBank(bank(T0)); // queued: the save, then the computation
		final Future<?> flush = service.flush();
		assertFalse("the save has not run yet", flush.isDone());

		service.stop(); // returns at once with the queue untouched
		scheduler.runPending();

		verify(store).saveBank(any(BankSnapshot.class));
		assertTrue("flush completes once the queued write ran", flush.isDone());
		assertTrue("the computation queued before stop() was dropped", publishedRows.isEmpty());
	}

	@Test
	public void flushCoversAPersistedIndexAndBaselineAndIsCompleteAfterwards()
	{
		scheduler.deferred = true;
		service.start();
		scheduler.runPending();
		service.setVisible(true);
		scheduler.runPending();
		fireTick();
		scheduler.runPending();                          // the index request goes out
		indexFutures.get(0).complete(history);           // queues the completion
		scheduler.runQueued(0);                          // the completion queues the revindex save and the R0 fetch
		final Future<?> flush = service.flush();
		assertFalse(flush.isDone());

		scheduler.runPending();

		verify(store).saveRevisionIndex(eq(HISTORY), eq(T0));
		assertTrue(flush.isDone());
		assertTrue(service.flush().isDone());
	}

	@Test
	public void theConstructorRejectsANullCollaborator()
	{
		try
		{
			new PriceService(wiki, store, itemManager, null, scheduler, clock::get, edt);
			fail("a null ClientThread would make every row computation NPE on the executor later");
		}
		catch (final NullPointerException expected)
		{
			assertTrue(expected.getMessage().contains("clientThread"));
		}
	}

	// ---------------------------------------------------------------- C20: listeners and the bridge getters

	@Test
	public void listenersAreInvokedOnlyThroughTheEdtConsumer()
	{
		edt.deferred = true;
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));

		service.start();
		service.setBank(bank(T0));

		assertTrue("nothing reaches a listener before the EDT consumer runs", publishedRows.isEmpty());
		assertEquals("the bridge getters are current before the EDT runs", 30, service.currentRows().size());
		assertEquals(30, service.currentStatus().totalRows());

		edt.drain();

		assertFalse(publishedRows.isEmpty());
		assertEquals(30, lastRows().size());
		assertSame(service.currentRows(), lastRows());
	}

	@Test
	public void aListenerAddedLateReceivesTheCurrentStateOnce()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();
		service.setBank(bank(T0));
		final List<List<MovementRow>> seen = new ArrayList<>();

		service.addListener((rows, status) -> seen.add(rows));

		assertEquals(1, seen.size());
		assertSame(service.currentRows(), seen.get(0));
	}

	@Test
	public void aRemovedListenerHearsNothingMore()
	{
		final List<PriceService.Status> seen = new ArrayList<>();
		final PriceService.Listener listener = (rows, status) -> seen.add(status);
		service.addListener(listener);
		assertEquals(1, seen.size());

		service.removeListener(listener);
		service.setBank(bank(T0));

		assertEquals(1, seen.size());
		assertEquals("the remaining listener still hears it", 1, publishedStatus.size());
	}

	@Test
	public void theBridgeGettersReflectAFilterChangeAtOnce()
	{
		final RowFilter filter = RowFilter.DEFAULT.withGpMin(1_000L).withSort(SortMode.UNIT_PRICE);

		service.setFilter(filter);

		assertEquals(filter, service.filter());
		assertEquals(MovementWindow.D1, service.currentStatus().window());

		service.setFilter(filter.withWindow(MovementWindow.D7));

		assertEquals(MovementWindow.D7, service.filter().window());
		assertEquals(MovementWindow.D7, service.currentStatus().window());
		assertEquals(MovementWindow.D7, lastStatus().window());
	}

	@Test
	public void theWikiSwitchPassesThroughToTheClient()
	{
		service.setWikiEnabled(false);
		verify(wiki).setEnabled(false);
		when(wiki.isEnabled()).thenReturn(false);
		assertFalse(service.isWikiEnabled());
	}

	// ---------------------------------------------------------------- C23: login, bank

	@Test
	public void loggingInLoadsThePersistedBankForThatAccount()
	{
		final long captured = T0 - HOUR;
		when(store.loadBank(ACCOUNT, PROFILE)).thenReturn(bank(captured));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();

		service.setLoggedIn(true, ACCOUNT, PROFILE);

		verify(store).loadBank(ACCOUNT, PROFILE);
		final PriceService.Status status = lastStatus();
		assertTrue(status.loggedIn());
		assertTrue(status.bankLoaded());
		assertEquals(captured, status.bankAtMillis());
		assertEquals(31, status.bankItems());
		assertEquals("bones have no guide price and are dropped by the band", 30, status.totalRows());
		assertEquals(MovementRow.PriceSource.GUIDE, status.source());
		assertEquals("Guide prices - 1d vs 07 Sep - Bank as of " + MovementMath.formatTime(captured), status.text());
	}

	@Test
	public void loggingInForTheAccountAlreadyShownDoesNotReloadButAnotherAccountDoes()
	{
		service.setBank(bank(T0));

		service.setLoggedIn(true, ACCOUNT, PROFILE);

		verify(store, never()).loadBank(anyLong(), anyString());
		assertTrue(lastStatus().loggedIn());
		assertEquals(T0, lastStatus().bankAtMillis());

		service.setLoggedIn(true, OTHER_ACCOUNT, PROFILE);

		verify(store).loadBank(OTHER_ACCOUNT, PROFILE);
		assertFalse("the other account has no persisted bank: the first account's rows must not linger",
			lastStatus().bankLoaded());
		assertTrue(lastRows().isEmpty());
	}

	@Test
	public void loggingOutKeepsTheRowsAndMarksTheStatus()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		final List<MovementRow> rows = lastRows();
		assertEquals(30, rows.size());

		service.setLoggedIn(false, 0L, "");

		assertSame("a logout is a status-only publish: the same rows instance", rows, lastRows());
		assertFalse(lastStatus().loggedIn());
		assertEquals(30, lastStatus().totalRows());
		assertEquals("Guide prices - 1d vs 07 Sep - Bank as of " + MovementMath.formatTime(T0) + " (logged out)", lastStatus().text());
		verify(store, never()).loadBank(anyLong(), anyString());
	}

	@Test
	public void aPersistedBankNeverOverwritesAFresherSnapshotFromTheClient()
	{
		scheduler.deferred = true;
		final BankSnapshot stale = new BankSnapshot(
			Collections.singletonList(new BankItem(BONES, 1, "Bones", true)), T0 - HOUR, ACCOUNT, PROFILE);
		when(store.loadBank(ACCOUNT, PROFILE)).thenReturn(stale);

		service.setLoggedIn(true, ACCOUNT, PROFILE); // queued: the disk load
		service.setBank(bank(T0));                   // the container replay lands first (queued: save, compute)
		scheduler.runPending();                      // the disk load now finishes with the older snapshot

		assertEquals("the fresher snapshot from the client stays", T0, lastStatus().bankAtMillis());
		assertEquals(31, lastStatus().bankItems());
	}

	@Test
	public void aSupersededLoginLoadIsDropped()
	{
		scheduler.deferred = true;
		when(store.loadBank(ACCOUNT, PROFILE)).thenReturn(bank(T0 - HOUR, ACCOUNT));
		when(store.loadBank(OTHER_ACCOUNT, PROFILE)).thenReturn(bank(T0 - 2 * HOUR, OTHER_ACCOUNT));

		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setLoggedIn(true, OTHER_ACCOUNT, PROFILE);
		scheduler.runPending();

		assertEquals("only the newest login's bank is shown", T0 - 2 * HOUR, lastStatus().bankAtMillis());
	}

	@Test
	public void settingTheBankPersistsItAndComputesAgainstTheStoredBaseline()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();
		final BankSnapshot snapshot = bank(T0);

		service.setBank(snapshot);

		verify(store).saveBank(snapshot);
		final List<MovementRow> rows = lastRows();
		assertEquals(30, rows.size());
		final MovementRow hat = rowFor(rows, GREEN_HAT);
		assertNotNull(hat);
		assertEquals("RuneLite's 08 Sep guide price", Long.valueOf(1_086L), hat.unitPrice());
		assertEquals("the stored 07 Sep table", Long.valueOf(1_124L), hat.thenPrice());
		assertEquals(Long.valueOf(-38L), hat.deltaGp());
		assertEquals("-38 / 1124 - the GE site's -3 % 'today' before truncation", -3.3807d, hat.deltaPct(), 0.001d);
		assertEquals(MovementRow.PriceSource.GUIDE, hat.source());
		assertEquals("a flat item moves by zero, not by null", Long.valueOf(0L), rowFor(rows, SHARK).deltaGp());
		assertEquals("the tablet resolves through the mapping name", Long.valueOf(290L + dayIndex(SEP_7)), rowFor(rows, TABLET).thenPrice());
		assertNull("bones have no guide price: no unit price, dropped by apply", rowFor(rows, BONES));
		assertNull("the box is priced by RuneLite and named by nothing", rowFor(rows, BOX).thenPrice());
		assertNull("the baited vessel's fallback is refused (L8 b)", rowFor(rows, VESSEL_BAITED).thenPrice());
		assertEquals(Long.valueOf(3_388L), rowFor(rows, VESSEL_BAITED).unitPrice());
		assertNull("no R0 in memory: no anchor, no request, the stored baseline serves as it is (L11)", lastStatus().anchorDay());
		assertTrue(tableRequests.isEmpty());
	}

	@Test
	public void anEmptySnapshotClearsTheRowsWithoutBeingSaved()
	{
		service.setBank(bank(T0));
		assertEquals(30, lastRows().size());

		service.setBank(BankSnapshot.EMPTY);

		assertTrue(lastRows().isEmpty());
		assertFalse(lastStatus().bankLoaded());
		verify(store, times(1)).saveBank(any(BankSnapshot.class));
		service.setBank(null);
		verify(store, times(1)).saveBank(any(BankSnapshot.class));
	}

	/**
	 * B021 (checker): {@code setBank(snapshot, false)} fills the panel and never touches the disk. The dev verb
	 * {@code bpm bank=<id>:<qty>} must stamp its made-up rows with the LIVE account hash and profile - otherwise
	 * {@code setLoggedIn}'s {@code belongsTo} check throws them away on the next {@code LOGGED_IN} - and
	 * {@code PriceStore} keys the file from those very fields, so the ordinary path replaced the account's real
	 * capture in {@code bank-<hash>-<profile>.json} with two synthetic rows. The rows still have to arrive: the
	 * whole point of the verb is to drive the sidebar.
	 */
	@Test
	public void aBankPushedWithoutPersistFillsThePanelAndIsNeverWritten()
	{
		final BankSnapshot synthetic = bank(T0);

		service.setBank(synthetic, false);

		assertEquals("the rows arrive as they do on the ordinary path", 30, lastRows().size());
		assertTrue(lastStatus().bankLoaded());
		verify(store, never()).saveBank(any(BankSnapshot.class));

		// ...and the refusal is per call, not sticky: a real capture of the same bank afterwards IS written.
		service.setBank(synthetic);
		verify(store, times(1)).saveBank(synthetic);
	}

	// ---------------------------------------------------------------- L10 + L3 + L6: the activation chain

	/**
	 * The whole chain of a cold activation, request by request: the index (L4), R0 alone (L3 - nothing can be
	 * picked before the anchor day exists), then EVERY window's body in ONE batched call (L6), each validated
	 * and adopted (L5, L7) and persisted with the table's own day marker.
	 */
	@Test
	public void activationFetchesTheIndexThenR0ThenEveryWindowInOneBatchedCall()
	{
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		assertTrue("nothing is fetched until the tick runs", indexFutures.isEmpty());

		fireTick();

		assertEquals("one index request", 1, indexFutures.size());
		assertTrue(tableRequests.isEmpty());
		assertEquals("No 1d history yet", lastStatus().text());
		assertEquals("No 1d history yet", lastStatus().problem());
		assertEquals("a request is out, not a fault: the panel greys this one",
			PriceService.ProblemKind.PENDING, lastStatus().problemKind());

		answerIndex();

		verify(store).saveRevisionIndex(eq(HISTORY), eq(T0));
		assertEquals(HISTORY, service.revisionIndex());
		assertEquals(T0, lastStatus().indexAtMillis());
		assertEquals("R0 on its own: no anchor day yet, so no window can be picked", 1, tableRequests.size());
		assertEquals(Collections.singletonList(BOT_0908.revId()), tableRequests.get(0));
		assertEquals("No 1d history yet", lastStatus().text());

		answerTables();

		assertEquals("every window's body in ONE call, in window order", 2, tableRequests.size());
		assertEquals(Arrays.asList(BOT_0907.revId(), BOT_0901.revId(), BOT_0809.revId(), BOT_0609.revId(), BOT_0312.revId()),
			tableRequests.get(1));
		PriceService.Status status = lastStatus();
		assertEquals(SEP_8, status.r0Day());
		assertEquals(BOT_0908.revId(), status.r0RevId());
		assertEquals("RuneLite on 08 Sep agrees with R0 on 08 Sep", SEP_8, status.anchorDay());
		assertEquals(1.0d, status.agree(), 0.0d);
		assertEquals("24 items + hat + whip + shark + tablet compare; the box and the vessel have no R0 value", 28, status.agreeSamples());
		assertFalse(status.degraded());
		assertEquals("No 1d history yet", status.text());

		answerTables();

		status = lastStatus();
		assertNull(status.problem());
		assertEquals("Guide prices - 1d vs 07 Sep - Bank as of " + MovementMath.formatTime(T0), status.text());
		assertEquals(SEP_7, status.thenDay());
		assertEquals(BOT_0907.revId(), status.baselineRevId());
		assertEquals(dataSecondsFor(BOT_0907), status.baselineRevisionSeconds());
		assertTrue(status.baselineLoaded());
		assertEquals(SEP_1, service.baseline(MovementWindow.D7).dataDay());
		assertEquals(AUG_9, service.baseline(MovementWindow.D30).dataDay());
		assertEquals("the walk-back of L5: 10 Jun has no revision, 09 Jun is remembered as the real date", JUN_9,
			service.baseline(MovementWindow.D90).dataDay());
		assertEquals(MAR_12, service.baseline(MovementWindow.D180).dataDay());
		final List<MovementRow> rows = lastRows();
		assertEquals(30, rows.size());
		assertEquals(Long.valueOf(-38L), rowFor(rows, GREEN_HAT).deltaGp());
		assertEquals(Long.valueOf(1_124L), rowFor(rows, GREEN_HAT).thenPrice());
		assertEquals("Item 1: 1250 now against 1249 on 07 Sep", Long.valueOf(1L), rowFor(rows, item(1)).deltaGp());
		assertEquals(Long.valueOf(1L), rowFor(rows, WHIP).deltaGp());
		assertNull(rowFor(rows, VESSEL_BAITED).thenPrice());
		for (final MovementWindow window : MovementWindow.values())
		{
			verify(store).saveBucket(eq(window), any(PriceMap.class));
		}
		final ArgumentCaptor<PriceMap> saved = ArgumentCaptor.forClass(PriceMap.class);
		verify(store).saveBucket(eq(MovementWindow.D1), saved.capture());
		assertEquals("the persisted map carries the table's own day marker (L7)", dataSecondsFor(BOT_0907), saved.getValue().bucketSeconds());
		assertEquals(BOT_0907.revId(), saved.getValue().revId());
		assertEquals(T0, saved.getValue().fetchedAtMillis());
		assertEquals("the persisted projection serves any bank: every mapped name in the table", 24 + 5, saved.getValue().size());
		verify(wiki, never()).fetchMapping(anyLong());
	}

	@Test
	public void withRuneLiteADayAheadTheAnchorMovesAndThe1dBaselineIsR0Itself()
	{
		runeliteOn(SEP_9);
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0

		PriceService.Status status = lastStatus();
		assertEquals(SEP_8, status.r0Day());
		assertEquals("only the flat shark still agrees: 1 of 28", 1.0d / 28.0d, status.agree(), 1e-9);
		assertEquals("RuneLite holds the next Jagex day: D = day(R0) + 1 (L3)", SEP_9, status.anchorDay());
		assertEquals("the 1d target IS R0's day: no fetch for it, four windows in the batch", 2, tableRequests.size());
		assertEquals(Arrays.asList(BOT_0902.revId(), BOT_0809.revId(), BOT_0609.revId(), BOT_0312.revId()), tableRequests.get(1));
		assertEquals("1d is already served from memory while the others are out", SEP_8, status.thenDay());
		assertEquals(BOT_0908.revId(), status.baselineRevId());
		assertEquals("Guide prices - 1d vs 08 Sep - Bank as of " + MovementMath.formatTime(T0), status.text());

		answerTables();

		final List<MovementRow> rows = lastRows();
		assertEquals("RuneLite's 09 Sep price against the 08 Sep table", Long.valueOf(1_100L), rowFor(rows, GREEN_HAT).unitPrice());
		assertEquals(Long.valueOf(1_086L), rowFor(rows, GREEN_HAT).thenPrice());
		assertEquals(Long.valueOf(14L), rowFor(rows, GREEN_HAT).deltaGp());
		assertEquals(SEP_2, service.baseline(MovementWindow.D7).dataDay());
		assertEquals("30 days back from 09 Sep is 10 Aug, which walks back to 09 Aug", AUG_9, service.baseline(MovementWindow.D30).dataDay());
	}

	/** The rollover of L3: RuneLite's table steps to the next day; the next tick re-derives D with no request for 1d. */
	@Test
	public void theAnchorFollowsTheJagexRolloverWithinOneTickWithoutRefetchingWhatIsInMemory()
	{
		warmUp();
		assertEquals(SEP_8, lastStatus().anchorDay());
		final int requestsBefore = tableRequests.size();

		runeliteOn(SEP_9);
		fireTick();

		final PriceService.Status status = lastStatus();
		assertEquals(SEP_9, status.anchorDay());
		assertEquals("1d now reads R0, adopted from memory", BOT_0908.revId(), status.baselineRevId());
		assertEquals(SEP_8, status.thenDay());
		assertEquals("only the 7d body is new (02 Sep); 30d/90d/180d walk back onto the revisions already held",
			requestsBefore + 1, tableRequests.size());
		assertEquals(Collections.singletonList(BOT_0902.revId()), tableRequests.get(requestsBefore));
		assertEquals("the index is fresh: not refetched by the tick", 1, indexFutures.size());
		assertEquals(Long.valueOf(14L), rowFor(lastRows(), GREEN_HAT).deltaGp());
		verify(store, times(2)).saveBucket(eq(MovementWindow.D1), any(PriceMap.class));

		answerTables();

		assertEquals(SEP_2, service.baseline(MovementWindow.D7).dataDay());
		assertEquals(BOT_0809.revId(), service.baseline(MovementWindow.D30).revId());
	}

	@Test
	public void agreementIsJudgedInIntegersAtTheThreshold()
	{
		// 28 comparable items; nudge three: 25 of 28 = 0.893 < 0.90 - the next day.
		runelite.put(item(1), runelite.get(item(1)) + 1);
		runelite.put(item(2), runelite.get(item(2)) + 1);
		runelite.put(item(3), runelite.get(item(3)) + 1);
		warmUp();
		assertEquals(25, Math.round(lastStatus().agree() * 28));
		assertEquals(SEP_9, lastStatus().anchorDay());

		// Back to two nudged: 26 of 28 = 0.929 - R0's own day.
		runelite.put(item(3), runelite.get(item(3)) - 1);
		fireTick();
		assertEquals(SEP_8, lastStatus().anchorDay());
	}

	/** L3's n < 20 branch: R0 stands in as "now", the anchor is R0's day, and the status says so. */
	@Test
	public void withoutARuneLiteTableTheNewestGuideTableStandsInAsNow()
	{
		runelite.clear();
		runelite.put(BOX, 100); // priced by RuneLite, named by no table: keeps its own price
		warmUp();

		final PriceService.Status status = lastStatus();
		assertEquals(0, status.agreeSamples());
		assertEquals(-1.0d, status.agree(), 0.0d);
		assertEquals("no comparison possible: D = day(R0)", SEP_8, status.anchorDay());
		assertTrue(status.degraded());
		assertTrue(status.degradedReason(), status.degradedReason().contains("20"));
		assertEquals(SEP_7, status.thenDay());
		final List<MovementRow> rows = lastRows();
		assertEquals("R0's price is the unit price", Long.valueOf(1_086L), rowFor(rows, GREEN_HAT).unitPrice());
		assertEquals(Long.valueOf(-38L), rowFor(rows, GREEN_HAT).deltaGp());
		assertEquals(MovementRow.PriceSource.GUIDE, rowFor(rows, GREEN_HAT).source());
		assertEquals("an item R0 cannot name keeps RuneLite's price", Long.valueOf(100L), rowFor(rows, BOX).unitPrice());
		assertNull("the vessel has neither: unpriced, dropped by the band", rowFor(rows, VESSEL_BAITED));
		assertEquals(MovementRow.PriceSource.GUIDE, status.source());
	}

	@Test
	public void aStoredBaselineOfTheRightRevisionIsNotRefetched()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - HOUR));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0 - HOUR));
		when(store.loadBucket(MovementWindow.D30)).thenReturn(diskBaseline(BOT_0809, T0 - HOUR));
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);

		fireTick();

		assertTrue("the index on disk is an hour old: not refetched (L4)", indexFutures.isEmpty());
		assertEquals("R0 is never persisted: fetched on its own first", Collections.singletonList(BOT_0908.revId()), tableRequests.get(0));
		answerTables();
		assertEquals("only the windows whose picked revision differs from the stored one (L10)",
			Arrays.asList(BOT_0901.revId(), BOT_0609.revId(), BOT_0312.revId()), tableRequests.get(1));
		answerTables();
		verify(store, never()).saveBucket(eq(MovementWindow.D1), any(PriceMap.class));
		verify(store, never()).saveBucket(eq(MovementWindow.D30), any(PriceMap.class));
		assertEquals(BOT_0907.revId(), lastStatus().baselineRevId());
	}

	@Test
	public void aStoredBaselineOfTheWrongRevisionServesUntilTheRightOneValidates()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - HOUR));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0906, T0 - DAY));
		service.start();
		service.setBank(bank(T0));
		assertEquals("labelled with its REAL day, whatever the window says (L11)", SEP_6, lastStatus().thenDay());
		assertEquals(Long.valueOf(0L), rowFor(lastRows(), GREEN_HAT).deltaGp());
		service.setVisible(true);

		fireTick();
		answerTables(); // R0

		assertTrue(tableRequests.get(1).contains(BOT_0907.revId()));
		assertEquals("the stored one stays while the right one is out", SEP_6, lastStatus().thenDay());

		answerTables();

		assertEquals(SEP_7, lastStatus().thenDay());
		assertEquals(Long.valueOf(-38L), rowFor(lastRows(), GREEN_HAT).deltaGp());
	}

	@Test
	public void aStaleIndexOnDiskIsRefetchedOnActivationAndAFreshOneIsNot()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - PriceService.HISTORY_MAX_AGE_MS));
		service.start();
		service.setVisible(true);

		fireTick();

		assertEquals("six hours old is stale (L4)", 1, indexFutures.size());
		answerIndex();
		assertEquals(T0, lastStatus().indexAtMillis());

		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS - MINUTE);
		fireTick();
		assertEquals("under six hours: the tick picks locally and asks for nothing", 1, indexFutures.size());

		clock.addAndGet(MINUTE);
		fireTick();
		assertEquals(2, indexFutures.size());
	}

	@Test
	public void r0IsRefetchedOnlyWhenTheIndexNamesANewerRevision()
	{
		warmUp();
		final int before = tableRequests.size();

		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);
		fireTick();
		answerIndex(); // the same newest revision

		assertEquals("a revision is immutable: the same R0 is not fetched again", before, tableRequests.size());

		final RevisionRef bot0909 = bot(15_335_900L, SEP_9, 10, 0, 0);
		final List<RevisionRef> grown = new ArrayList<>(HISTORY);
		grown.add(0, bot0909);
		history = grown;
		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);
		fireTick();
		answerIndex();

		assertEquals(before + 1, tableRequests.size());
		assertEquals(Collections.singletonList(bot0909.revId()), tableRequests.get(before));
		runeliteOn(SEP_9); // RuneLite's table has stepped too by the time the body lands
		answerTables();

		final PriceService.Status status = lastStatus();
		assertEquals(SEP_9, status.r0Day());
		assertEquals(bot0909.revId(), status.r0RevId());
		assertEquals(SEP_9, status.anchorDay());
		assertEquals("the superseded R0 is the new 1d baseline, adopted from memory - not fetched again",
			BOT_0908.revId(), status.baselineRevId());
		assertEquals(SEP_8, status.thenDay());
		assertEquals("only the 7d body (02 Sep) is new", before + 2, tableRequests.size());
		assertEquals(Collections.singletonList(BOT_0902.revId()), tableRequests.get(before + 1));
	}

	@Test
	public void nothingIsFetchedWhileHidden()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - HOUR));
		service.start();
		service.setBank(bank(T0));

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D7));

		assertTrue(indexFutures.isEmpty());
		assertTrue("a window change while hidden picks locally and defers the body (L10)", tableRequests.isEmpty());
		assertTrue(scheduler.timers.isEmpty());

		service.setVisible(true);
		service.setVisible(false);

		assertTrue("the timer is cancelled before it could fire", scheduler.timers.get(0).future.isCancelled());
		fireAllTimers();
		assertTrue(tableRequests.isEmpty());
	}

	// ---------------------------------------------------------------- L5: the day check and the single retry

	/**
	 * The "once the bot itself" case of L-E, SYNTHETIC: 07 Sep's newest bot run republishes 06 Sep's table; an
	 * earlier run the same date holds the right one. The stale body is adopted meanwhile (labelled 06 Sep), the
	 * retry candidate is the previous revision of the date, and it replaces the stale one - once, never again.
	 */
	@Test
	public void aStaleBodyIsRetriedOnceWithThePreviousRevisionOfTheDate()
	{
		final RevisionRef early0907 = bot(15_333_400L, SEP_7, 5, 0, 0);
		final List<RevisionRef> withTwoRuns = new ArrayList<>(HISTORY);
		withTwoRuns.add(2, early0907);
		history = withTwoRuns;
		bodyOverrides.put(BOT_0907.revId(), snapshotWithDay(BOT_0907, SEP_6, T0));
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		assertEquals("the newest bot run of 07 Sep is picked first", Long.valueOf(BOT_0907.revId()), tableRequests.get(1).get(0));

		answerTables(); // 15333448 comes back with 06 Sep's table

		PriceService.Status status = lastStatus();
		assertEquals("adopted meanwhile, labelled with its real day", SEP_6, status.thenDay());
		assertEquals(BOT_0907.revId(), status.baselineRevId());
		assertEquals("Guide prices - 1d vs 06 Sep - Bank as of " + MovementMath.formatTime(T0), status.text());
		assertEquals("the retry: the previous revision of the date, on its own", 3, tableRequests.size());
		assertEquals(Collections.singletonList(early0907.revId()), tableRequests.get(2));

		answerTables();

		status = lastStatus();
		assertEquals("the retry's body is 07 Sep's: adopted", SEP_7, status.thenDay());
		assertEquals(early0907.revId(), status.baselineRevId());
		assertEquals(Long.valueOf(-38L), rowFor(lastRows(), GREEN_HAT).deltaGp());

		fireTick();
		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D7));
		service.setFilter(RowFilter.DEFAULT);
		assertEquals("never a third fetch: both candidates are known", 3, tableRequests.size());
		assertEquals(early0907.revId(), lastStatus().baselineRevId());
	}

	/**
	 * L5's other retry: the target date has only a human edit (stale). The candidate is the newest bot revision of
	 * the NEXT date - here R0 itself, already in memory - and "if still not T, accept the table": both are one day
	 * off, so the older one is kept and labelled with its real day.
	 */
	@Test
	public void aDateWithOnlyAHumanEditRetriesWithTheNextDatesBotAndKeepsTheCloserDay()
	{
		final RevisionRef human0907 = new RevisionRef(15_333_300L, utcSeconds(SEP_7, 6, 46, 0), "Riblet15", "new items");
		final List<RevisionRef> humanOnly = new ArrayList<>(HISTORY);
		humanOnly.set(1, human0907); // 07 Sep: the human edit instead of the bot run
		history = humanOnly;
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		assertEquals(Long.valueOf(human0907.revId()), tableRequests.get(1).get(0));

		answerTables(); // the human edit's body: 06 Sep's table

		final PriceService.Status status = lastStatus();
		assertEquals("no previous revision on 07 Sep and 08 Sep's bot is R0, already known: nothing to fetch", 2, tableRequests.size());
		assertEquals("06 Sep and 08 Sep are both a day off 07 Sep: the older wins the tie", SEP_6, status.thenDay());
		assertEquals(human0907.revId(), status.baselineRevId());
		assertEquals("1d vs 06 Sep", status.baselineText());
	}

	@Test
	public void anUnusableRevisionIsNeverAskedForAgainAndAnExhaustedWindowSaysUnavailable()
	{
		omitBodies.add(BOT_0809.revId());
		warmUp();
		assertNull(lastStatus().problem());
		final int before = tableRequests.size();

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D30));

		PriceService.Status status = lastStatus();
		assertFalse(status.baselineLoaded());
		assertEquals("09 Aug's only revision is unusable and 10 Aug has none: nothing left to try",
			"Wiki history down - no movement", status.text());
		assertEquals("and it is the severe kind, which is what the panel paints red",
			PriceService.ProblemKind.HISTORY_DOWN, status.problemKind());
		fireTick();
		assertEquals("the wiki answered without that body: it is not requested again this session", before, tableRequests.size());
		assertEquals(MovementRow.PriceSource.GUIDE, lastStatus().source());
		assertNull(rowFor(lastRows(), GREEN_HAT).thenPrice());

		service.setFilter(RowFilter.DEFAULT);
		status = lastStatus();
		assertNull("the 1d window is unaffected", status.problem());
		assertEquals(SEP_7, status.thenDay());
	}

	@Test
	public void aWindowBeyondTheIndexSaysNoHistoryAndAsksForNothing()
	{
		history = Arrays.asList(BOT_0908, BOT_0907, BOT_0906, BOT_0905, BOT_0904, BOT_0903, BOT_0902, BOT_0901, BOT_0831, BOT_0830);
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0

		assertEquals("only 1d and 7d can be served", Arrays.asList(BOT_0907.revId(), BOT_0901.revId()), tableRequests.get(1));
		answerTables();
		assertNull(lastStatus().problem());

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D180));

		final PriceService.Status status = lastStatus();
		assertEquals("No 180d history", status.text());
		assertEquals("not an error: the panel greys it", PriceService.ProblemKind.NO_HISTORY, status.problemKind());
		assertFalse(status.baselineLoaded());
		assertFalse("not an error: not degraded", status.degraded());
		fireTick();
		assertEquals(2, tableRequests.size());
	}

	// ---------------------------------------------------------------- L10: window changes and the manual refresh

	@Test
	public void aWindowChangeIsServedFromMemoryWithoutARequest()
	{
		warmUp();
		final int before = tableRequests.size();

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D7));

		final PriceService.Status status = lastStatus();
		assertEquals(MovementWindow.D7, status.window());
		assertEquals(SEP_1, status.thenDay());
		assertEquals(BOT_0901.revId(), status.baselineRevId());
		assertEquals("Guide prices - 7d vs 01 Sep - Bank as of " + MovementMath.formatTime(T0), status.text());
		assertEquals("Item 1 on 08 Sep against 01 Sep: seven days of one gp each", Long.valueOf(7L), rowFor(lastRows(), item(1)).deltaGp());
		assertEquals(before, tableRequests.size());

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D90));
		assertEquals("90d vs 09 Jun", lastStatus().baselineText());
		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D90));
		assertEquals("an equal filter is a no-op", before, tableRequests.size());
	}

	@Test
	public void aWindowChangeFetchesTheOneBodyItIsMissing()
	{
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		tableFutures.get(1).completeExceptionally(new WikiPriceException("wiki request failed: timeout")); // the windows batch fails
		assertEquals("Wiki history down - no movement", lastStatus().text());
		assertTrue(lastStatus().degraded());
		final int before = tableRequests.size();

		service.setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D7));

		assertEquals("No 7d history yet", lastStatus().text());
		assertEquals(before + 1, tableRequests.size());
		assertTrue(tableRequests.get(before).contains(BOT_0901.revId()));
		answerTables();
		assertEquals(SEP_1, lastStatus().thenDay());
		assertFalse(lastStatus().degraded());
	}

	@Test
	public void aManualRefreshForcesTheIndexAndHonoursTheCooldown()
	{
		warmUp();
		assertEquals(1, indexFutures.size());
		final int before = tableRequests.size();

		service.refreshNow();

		assertEquals("the index is fresh but a manual refresh asks anyway (L10)", 2, indexFutures.size());
		answerIndex();
		assertEquals("nothing changed: no body fetched", before, tableRequests.size());
		assertNull(lastStatus().problem());

		clock.addAndGet(12 * SECOND);
		service.refreshNow();

		assertEquals("Refreshed 12 s ago - wait", lastStatus().text());
		assertEquals("a refused refresh is the severe kind", PriceService.ProblemKind.COOLDOWN,
			lastStatus().problemKind());
		assertEquals(2, indexFutures.size());

		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS);
		service.refreshNow();

		assertEquals(3, indexFutures.size());
		assertNull("the cooldown text is gone with the next publish, and a window with a baseline never complains",
			lastStatus().problem());
	}

	@Test
	public void aManualRefreshFetchesEvenWhileHidden()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - HOUR));
		service.start();
		service.setBank(bank(T0));
		assertFalse(service.isVisible());

		service.refreshNow();

		assertEquals(1, indexFutures.size());
		answerIndex();
		assertEquals("the bridge's refresh verb works with the sidebar closed", 1, tableRequests.size());
		assertEquals(Collections.singletonList(BOT_0908.revId()), tableRequests.get(0));
	}

	@Test
	public void aManualRefreshRefetchesAStaleMappingButNotAFreshOne()
	{
		warmUp();
		service.refreshNow();
		assertTrue("a day-old mapping is fresh (weekly)", mappingFutures.isEmpty());

		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS + PriceService.MAPPING_MAX_AGE_MS);
		service.refreshNow();

		assertEquals(1, mappingFutures.size());
	}

	@Test
	public void theTickRecomputesTheRowsEvenWhenNothingIsFetched()
	{
		warmUp();
		final int publishes = publishedRows.size();
		runelite.put(GREEN_HAT, 1_200);

		fireTick();

		assertTrue(publishedRows.size() > publishes);
		assertEquals("RuneLite's table refreshed under us: the next tick shows it (L1)", Long.valueOf(1_200L),
			rowFor(lastRows(), GREEN_HAT).unitPrice());
	}

	// ---------------------------------------------------------------- L11: failure keeps stored data

	@Test
	public void anIndexFailureWithNothingStoredSaysUnavailableAndTheNextTickRetries()
	{
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();

		indexFutures.get(0).completeExceptionally(new WikiPriceException("wiki request failed with HTTP 403", 403));

		final PriceService.Status status = lastStatus();
		assertEquals("Wiki history down - no movement", status.text());
		assertTrue(status.degraded());
		assertTrue(status.degradedReason(), status.degradedReason().contains("failed"));
		assertEquals("the rows keep their guide prices", 30, status.totalRows());
		assertEquals(MovementRow.PriceSource.GUIDE, status.source());
		assertTrue(tableRequests.isEmpty());
		verify(store, never()).saveRevisionIndex(anyList(), anyLong());

		fireTick();

		assertEquals("the next tick tries again", 2, indexFutures.size());
		assertEquals("No 1d history yet", lastStatus().text());
	}

	@Test
	public void anIndexFailureWithAStoredBaselineKeepsTheHeaderAndTheMoves()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - DAY));
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0 - DAY));
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		assertEquals(1, indexFutures.size());

		indexFutures.get(0).completeExceptionally(new WikiPriceException("wiki request failed: offline"));

		final PriceService.Status status = lastStatus();
		assertNull("a window with a baseline never complains (L11)", status.problem());
		assertEquals("Guide prices - 1d vs 07 Sep - Bank as of " + MovementMath.formatTime(T0), status.text());
		assertTrue("but the bridge can see it", status.degraded());
		assertEquals(Long.valueOf(-38L), rowFor(lastRows(), GREEN_HAT).deltaGp());
		assertEquals("the stored index is still history: R0 is asked for on the back of it", 1, tableRequests.size());
	}

	@Test
	public void aBodyFailureKeepsEveryStoredBaselineAndDoesNotLoop()
	{
		warmUp();
		final int before = tableRequests.size();
		final RevisionRef bot0909 = bot(15_335_900L, SEP_9, 10, 0, 0);
		final List<RevisionRef> grown = new ArrayList<>(HISTORY);
		grown.add(0, bot0909);
		history = grown;
		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);
		fireTick();
		answerIndex();
		assertEquals(before + 1, tableRequests.size());

		tableFutures.get(tableFutures.size() - 1).completeExceptionally(new WikiPriceException("wiki request failed: reset"));

		final PriceService.Status status = lastStatus();
		assertEquals("the old R0 and every baseline stay", SEP_8, status.r0Day());
		assertEquals(SEP_7, status.thenDay());
		assertNull(status.problem());
		assertTrue(status.degraded());
		assertEquals("no immediate re-request: the next tick retries", before + 1, tableRequests.size());

		fireTick();

		assertEquals(before + 2, tableRequests.size());
		assertEquals(Collections.singletonList(bot0909.revId()), tableRequests.get(before + 1));
		runeliteOn(SEP_9);
		answerTables();
		assertFalse(lastStatus().degraded());
		assertEquals(SEP_9, lastStatus().r0Day());
		assertEquals(SEP_9, lastStatus().anchorDay());
	}

	@Test
	public void aDisabledClientFailsFastAndTheStatusSaysSo()
	{
		when(wiki.fetchRevisionIndex(anyLong())).thenAnswer(invocation ->
		{
			final CompletableFuture<List<RevisionRef>> future = new CompletableFuture<>();
			future.completeExceptionally(new WikiPriceException("wiki fetches are switched off (developer mode)"));
			indexFutures.add(future);
			return future;
		});
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);

		fireTick();

		assertEquals("Wiki history down - no movement", lastStatus().text());
		assertEquals("the rows keep their unit prices: they are RuneLite's", Long.valueOf(1_086L), rowFor(lastRows(), GREEN_HAT).unitPrice());
		assertNull(rowFor(lastRows(), GREEN_HAT).deltaGp());
	}

	@Test
	public void aThrowingClientIsAFailedFetchNotAStuckFlag()
	{
		doThrow(new IllegalStateException("boom")).when(wiki).fetchTables(anyCollection(), anyLong());
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();
		answerIndex();

		assertEquals("Wiki history down - no movement", lastStatus().text());

		doAnswer(invocation ->
		{
			tableRequests.add(new ArrayList<>(invocation.<List<Long>>getArgument(0)));
			final CompletableFuture<Map<Long, GuideSnapshot>> future = new CompletableFuture<>();
			tableFutures.add(future);
			return future;
		}).when(wiki).fetchTables(anyCollection(), anyLong());
		fireTick();

		assertEquals("the flag was released: the next tick asks again", 1, tableRequests.size());
	}

	// ---------------------------------------------------------------- K3/L8: the mapping

	@Test
	public void theMappingArrivingReprojectsR0AndEveryBaselineInMemory()
	{
		when(store.loadMapping()).thenReturn(storedMapping(Collections.<Integer, String>emptyMap(), 0L));
		warmUp();
		assertEquals("no mapping: stale, so the activation tick asked for it", 1, mappingFutures.size());
		assertNull("the tablet's composition name is not a table key", rowFor(lastRows(), TABLET).thenPrice());
		assertEquals("with no mapping the vessel's name cannot be refused - the K3 ladder stands",
			Long.valueOf(3_235L), rowFor(lastRows(), VESSEL_BAITED).thenPrice());
		final int samplesBefore = lastStatus().agreeSamples();

		mappingFutures.get(0).complete(mappingTable());

		verify(store).saveMapping(eq(mappingTable()), eq(T0));
		for (final MovementWindow window : MovementWindow.values())
		{
			verify(store, times(2)).saveBucket(eq(window), any(PriceMap.class));
		}
		final List<MovementRow> rows = lastRows();
		assertEquals("the tablet now resolves through the mapping", Long.valueOf(290L + dayIndex(SEP_7)), rowFor(rows, TABLET).thenPrice());
		assertNull("and the vessel's fallback is refused (L8 b)", rowFor(rows, VESSEL_BAITED).thenPrice());
		assertEquals(T0, lastStatus().mappingAtMillis());
		assertEquals("the vessel dropped out of the agreement and the tablet came in", samplesBefore, lastStatus().agreeSamples());
		assertEquals(SEP_8, lastStatus().anchorDay());
	}

	@Test
	public void aMappingFailureIsSilent()
	{
		when(store.loadMapping()).thenReturn(storedMapping(Collections.<Integer, String>emptyMap(), 0L));
		warmUp();

		mappingFutures.get(0).completeExceptionally(new WikiPriceException("wiki request failed with HTTP 403", 403));

		assertNull(lastStatus().problem());
		assertEquals(0L, lastStatus().mappingAtMillis());
		verify(store, never()).saveMapping(anyMap(), anyLong());
		fireTick();
		assertEquals("the next tick tries again", 2, mappingFutures.size());
	}

	// ---------------------------------------------------------------- M3: the bank value line

	@Test
	public void thePortfolioIsEmptyUntilTheFirstComputation()
	{
		assertSame(PortfolioSummary.EMPTY, service.currentStatus().portfolio());

		service.start();

		assertSame("a computation over no bank publishes EMPTY too", PortfolioSummary.EMPTY, lastStatus().portfolio());
		assertSame(PortfolioSummary.EMPTY, service.currentStatus().portfolio());
	}

	/**
	 * The fixture's figures, by hand: "Item n" costs {@code 1000n + 250} on 08 Sep and one gp less per day back, held
	 * n at a time, so the 24 of them are worth 4,975,000 now, 4,974,700 a day ago and 4,972,900 a week ago; the hat
	 * 1,086 / 1,124 / 1,100; the whip 800,250 / 800,249 / 800,243; 500 flat sharks 500,000; 20 tablets 10,800 /
	 * 10,780 / 10,660; the box (300) and the baited vessel (3,388) are priced now and named by no table; the bones
	 * have no price at all. So the whole bank is 6,290,824 over 30 of 31 stacks, the 1d move covers 28 stacks from
	 * 6,286,853 to 6,287,136 (+283), and the 7d move the same 28 from 6,284,903 (+2,233).
	 */
	@Test
	public void thePortfolioIsPublishedWithTheRowsFromTheWholeBankAndEveryBaseline()
	{
		warmUp();

		final PriceService.Status status = lastStatus();
		final PortfolioSummary p = status.portfolio();
		final List<MovementRow> rows = lastRows();
		assertEquals(31, p.itemsTotal());
		assertEquals("the bones have no guide price", 30, p.itemsPriced());
		assertEquals(6_290_824L, p.valueNow());
		assertEquals("the total is the sum of the rows' holdings (no band in force)", holdingSum(rows), p.valueNow());
		assertEquals("every window has a baseline after the warm-up", 5, p.moves().size());

		final WindowMove d1 = p.move(MovementWindow.D1);
		assertEquals(SEP_7, d1.thenDay());
		assertEquals("24 items + hat + whip + shark + tablet: the box and the vessel have no then", 28, d1.itemsCovered());
		assertEquals(6_286_853L, d1.valueThen());
		assertEquals("the SAME 28 stacks now - not the whole bank's 6,290,824", 6_287_136L, d1.valueNowCovered());
		assertEquals("300 - 38 + 1 + 0 + 20", 283L, d1.deltaGp());
		assertEquals(283L * 100.0d / 6_286_853L, d1.deltaPct(), 1e-12);
		long thenSum = 0L;
		long nowCovered = 0L;
		for (final MovementRow row : rows)
		{
			if (row.thenPrice() != null)
			{
				thenSum += row.thenPrice() * row.quantity();
				nowCovered += row.holdingValue();
			}
		}
		assertEquals("and exactly what the rows say", thenSum, d1.valueThen());
		assertEquals(nowCovered, d1.valueNowCovered());

		final WindowMove d7 = p.move(MovementWindow.D7);
		assertEquals(SEP_1, d7.thenDay());
		assertEquals(28, d7.itemsCovered());
		assertEquals(6_284_903L, d7.valueThen());
		assertEquals(2_233L, d7.deltaGp());
		assertEquals(AUG_9, p.move(MovementWindow.D30).thenDay());
		assertEquals("the walk-back's real day (L5)", JUN_9, p.move(MovementWindow.D90).thenDay());
		assertEquals(MAR_12, p.move(MovementWindow.D180).thenDay());
		assertSame("the bridge reads the same object", p, service.currentStatus().portfolio());

		service.setLoggedIn(false, 0L, "");

		assertSame("a status-only publish carries the summary on", p, lastStatus().portfolio());
	}

	@Test
	public void thePortfolioIsTheWholeBankNotTheFilteredRows()
	{
		warmUp();
		final PortfolioSummary whole = lastStatus().portfolio();

		service.setFilter(RowFilter.DEFAULT.withGpMin(100_000L));

		assertEquals("the band cut the rows down to the whip", 1, lastRows().size());
		assertEquals(WHIP, lastRows().get(0).id());
		assertEquals("...and left the portfolio alone (M1: whole bank)", whole, lastStatus().portfolio());
		assertEquals(6_290_824L, lastStatus().portfolio().valueNow());
		assertEquals(30, lastStatus().portfolio().itemsPriced());
		assertEquals(28, lastStatus().portfolio().move(MovementWindow.D1).itemsCovered());

		service.setFilter(RowFilter.DEFAULT.withGpMin(100_000L).withWindow(MovementWindow.D30));

		assertEquals("a window change moves the rows, not the summary", whole, lastStatus().portfolio());
	}

	/** A stored baseline with no table in memory still names a window (M3: every baseline in memory), the others do not. */
	@Test
	public void aStoredBaselineAloneGivesTheOneWindowItServes()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();

		service.setBank(bank(T0));

		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals(6_290_824L, p.valueNow());
		assertEquals(Collections.singleton(MovementWindow.D1), p.moves().keySet());
		final WindowMove d1 = p.move(MovementWindow.D1);
		assertEquals(SEP_7, d1.thenDay());
		assertEquals("the disk projection carries every mapped name: 28 covered", 28, d1.itemsCovered());
		assertEquals(283L, d1.deltaGp());
	}

	/** L3's degraded mode: R0 stands in as "now" for the rows AND for the total - the two can never disagree. */
	@Test
	public void inTheDegradedModeThePortfolioUsesTheSameNowAsTheRows()
	{
		runelite.clear();
		runelite.put(BOX, 100);
		warmUp();

		final PortfolioSummary p = lastStatus().portfolio();
		assertTrue(lastStatus().degraded());
		assertEquals("the vessel has neither price now: 29 priced", 29, p.itemsPriced());
		assertEquals(holdingSum(lastRows()), p.valueNow());
		assertEquals("R0's 08 Sep table is now: the 1d move is the same +283", 283L, p.move(MovementWindow.D1).deltaGp());
	}

	/**
	 * P1: the snapshot's coins and platinum tokens reach the published summary - once in the total, and on both sides
	 * of every window, so the gp move is still the stacks' own +283 and only the percentage is measured against more.
	 */
	@Test
	public void theBanksCoinsAndPlatinumTokensReachTheBankValue()
	{
		warmUpWith(bankWithCurrency(T0, CURRENCY));

		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals(CURRENCY, p.currencyGp());
		assertEquals(6_290_824L + CURRENCY, p.valueNow());
		assertEquals("the stacks alone are still what the rows add up to", 6_290_824L, p.valueStacks());
		assertEquals(holdingSum(lastRows()), p.valueStacks());
		assertEquals("coins are not a stack", 30, p.itemsPriced());
		assertEquals(31, p.itemsTotal());

		final WindowMove d1 = p.move(MovementWindow.D1);
		assertEquals(28, d1.itemsCovered());
		assertEquals(6_286_853L + CURRENCY, d1.valueThen());
		assertEquals(6_287_136L + CURRENCY, d1.valueNowCovered());
		assertEquals("the cash sits on both sides and cancels", 283L, d1.deltaGp());
		assertEquals(283L * 100.0d / (6_286_853L + CURRENCY), d1.deltaPct(), 1e-12);
	}

	/** P1: a bank of nothing but coins draws no row and still has a value - it is not {@link PortfolioSummary#EMPTY}. */
	@Test
	public void aBankOfNothingButCoinsStillHasABankValue()
	{
		warmUp();

		service.setBank(new BankSnapshot(new ArrayList<>(), T0 + 1L, ACCOUNT, PROFILE, CURRENCY));

		final PortfolioSummary p = lastStatus().portfolio();
		assertNotEquals("cash is not nothing", PortfolioSummary.EMPTY, p);
		assertEquals(CURRENCY, p.valueNow());
		assertEquals(CURRENCY, p.currencyGp());
		assertEquals(0, p.itemsTotal());
		assertTrue("no stack to draw a row for", lastRows().isEmpty());
		assertNull("...and nothing to compare, so no move", p.move(MovementWindow.D1).deltaPct());
	}

	private static long holdingSum(final List<MovementRow> rows)
	{
		long sum = 0L;
		for (final MovementRow row : rows)
		{
			sum += row.holdingValue();
		}
		return sum;
	}

	// ---------------------------------------------------------------- addendum Q: the three view switches

	/** Two stacks the Grand Exchange does not list, worth 1,500 and 500 gp to alch (Q5). */
	private static final int DRAMEN = 772;
	private static final int GRACEFUL = 11_850;

	private static BankSnapshot bankWithUntradeables(final long capturedAt)
	{
		final BankSnapshot snapshot = bank(capturedAt);
		snapshot.items.add(new BankItem(DRAMEN, 2, "Dramen staff", false, true, 1_500));
		snapshot.items.add(new BankItem(GRACEFUL, 1, "Graceful cape", false, true, 500));
		return snapshot;
	}

	/**
	 * The pin for Q5's "off is exactly today". The SAME captured bank - one that now carries two untradeable
	 * stacks, because the reader records them whatever the switch says - must publish the identical rows, counts
	 * and bank value as a bank that never held them, down to the last field of the summary. Anything else and an
	 * existing user's sidebar would move the moment they updated.
	 */
	@Test
	public void withUntradeablesOffTheServicePublishesExactlyWhatItPublishedBeforeQ()
	{
		warmUpWith(bankWithUntradeables(T0));
		final List<MovementRow> withThem = lastRows();
		final PriceService.Status statusWithThem = lastStatus();

		service.setBank(bank(T0 + 1L));

		assertEquals("the same rows, in the same order", withThem, lastRows());
		assertEquals(statusWithThem.portfolio(), lastStatus().portfolio());
		assertEquals(statusWithThem.totalRows(), lastStatus().totalRows());
		assertEquals("and the same stack count", statusWithThem.bankItems(), lastStatus().bankItems());
		assertEquals(31, statusWithThem.bankItems());
		assertEquals(6_290_824L, statusWithThem.portfolio().valueNow());
		assertNull("no alch row was drawn", rowFor(withThem, DRAMEN));
	}

	/**
	 * Q5 with the switch on: each untradeable stack becomes a row priced at its alch value with no move, counted
	 * in the total and in {@code itemsTotal} but never in {@code itemsPriced} or in a window.
	 */
	@Test
	public void countingUntradeablesAddsAlchRowsToTheListAndTheTotal()
	{
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(bankWithUntradeables(T0));

		final MovementRow dramen = rowFor(lastRows(), DRAMEN);
		assertNotNull("the untradeable stack is listed", dramen);
		assertEquals(MovementRow.PriceSource.ALCH, dramen.source());
		assertEquals(Long.valueOf(1_500L), dramen.unitPrice());
		assertNull(dramen.thenPrice());
		assertNull(dramen.deltaGp());
		assertEquals(3_000L, dramen.holdingValue());

		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals("6,290,824 + 2 x 1,500 + 500", 6_290_824L + 3_500L, p.valueNow());
		assertEquals("an alch value is not a market price", 30, p.itemsPriced());
		assertEquals("but they are stacks the bank holds", 33, p.itemsTotal());
		assertEquals(33, lastStatus().bankItems());
		final WindowMove d1 = p.move(MovementWindow.D1);
		assertEquals("the same 28 covered stacks as without them", 28, d1.itemsCovered());
		assertEquals(283L, d1.deltaGp());
		assertEquals("the day the prices are dated by is untouched", SEP_8, lastStatus().anchorDay());
		assertFalse(lastStatus().degraded());
	}

	/** Flipping the switch recomputes on the spot: nothing is fetched and no bank visit is needed. */
	@Test
	public void flippingTheUntradeablesSwitchRepublishesWithoutFetchingAnything()
	{
		warmUpWith(bankWithUntradeables(T0));
		final int requests = tableRequests.size();
		assertNull(rowFor(lastRows(), DRAMEN));

		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));

		assertNotNull(rowFor(lastRows(), DRAMEN));
		assertEquals("no table was asked for", requests, tableRequests.size());
		assertEquals(1, indexFutures.size());

		service.setOptions(ViewOptions.DEFAULT);

		assertNull("and back again", rowFor(lastRows(), DRAMEN));
		assertEquals(6_290_824L, lastStatus().portfolio().valueNow());
	}

	/**
	 * Q4: with the cash switch off the bank value behaves as if the bank held no coins - including the figure the
	 * dev bridge echoes, which is the one the card is printing - while the snapshot on disk keeps the real sum, so
	 * turning it back on needs no bank visit.
	 */
	@Test
	public void withTheCashSwitchOffTheCoinsAreNotInTheBankValue()
	{
		service.setOptions(ViewOptions.DEFAULT.withCountCash(false));
		warmUpWith(bankWithCurrency(T0, CURRENCY));

		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals("the value used, which is the value shown", 0L, p.currencyGp());
		assertEquals(6_290_824L, p.valueNow());
		assertEquals(6_286_853L, p.move(MovementWindow.D1).valueThen());
		assertEquals("the gp move never depended on the cash", 283L, p.move(MovementWindow.D1).deltaGp());

		service.setOptions(ViewOptions.DEFAULT);

		assertEquals("the snapshot still knew: no bank visit needed", CURRENCY, lastStatus().portfolio().currencyGp());
		assertEquals(6_290_824L + CURRENCY, lastStatus().portfolio().valueNow());
	}

	/**
	 * Q6: the two gp orderings follow the switch, so the list agrees with the figures the rows print. In the
	 * fixture every mover gains exactly 1 gp per item over a day, so the per-ITEM order is a wall of ties broken
	 * alphabetically ("Abyssal whip") while the per-STACK order is the depth of the stack - "Item 24", 24 of them
	 * at +1, is the most money made.
	 */
	@Test
	public void theHoldingSwitchReordersTheGpSorts()
	{
		warmUp();
		service.setFilter(RowFilter.DEFAULT.withSort(SortMode.GP_MOVE).withDescending(true));
		final MovementRow perItem = lastRows().get(0);

		service.setOptions(ViewOptions.DEFAULT.withHoldingOnRows(true));

		final MovementRow perStack = lastRows().get(0);
		assertEquals("Abyssal whip", perItem.name());
		assertEquals(Long.valueOf(1L), perItem.deltaGp());
		assertEquals("Item 24", perStack.name());
		assertEquals("24 held, 1 gp each", 24L, perStack.holdingDeltaGp());
		final List<MovementRow> rows = lastRows();
		for (int i = 1; i < rows.size(); i++)
		{
			if (rows.get(i).hasMovement() && rows.get(i - 1).hasMovement())
			{
				assertTrue("the list is ordered by the stack's change: " + rows.get(i - 1).name() + " then "
						+ rows.get(i).name(),
					rows.get(i - 1).holdingDeltaGp() >= rows.get(i).holdingDeltaGp());
			}
		}
	}

	/** The switches ride on the published status, which is how the panel knows what the figures beside them mean. */
	@Test
	public void theStatusCarriesTheOptionsTheFiguresWereComputedWith()
	{
		assertEquals("nothing set yet: the defaults, which are the pre-Q behaviour",
			ViewOptions.DEFAULT, service.currentStatus().options());
		assertEquals(ViewOptions.DEFAULT, service.options());

		warmUp();
		final ViewOptions all = new ViewOptions(false, true, true);
		service.setOptions(all);

		assertEquals(all, lastStatus().options());
		assertEquals(all, service.options());
		assertEquals(all, service.currentStatus().options());
	}

	/** An equal set is a no-op: the plugin forwards every ConfigChanged, including the ones the gear menu caused. */
	@Test
	public void settingTheSameOptionsAgainPublishesNothing()
	{
		warmUp();
		final int publishes = publishedStatus.size();

		service.setOptions(ViewOptions.DEFAULT);
		service.setOptions(null);

		assertEquals(publishes, publishedStatus.size());

		service.setOptions(ViewOptions.DEFAULT.withHoldingOnRows(true));

		assertTrue("a real change does publish", publishedStatus.size() > publishes);
	}

	// ---------------------------------------------------------------- addendum R: untradeables at their parts

	/**
	 * REAL ids, because {@code ItemMapping} is a static table this code reads for real:
	 * {@code ITEM_CRYSTAL_BODY(PRIF_ARMOUR_SEED, true, 3L, CRYSTAL_CHESTPLATE)} - a Crystal body reverts to three
	 * Crystal armour seeds. The bank stacks carry the parts the READER records, so nothing here depends on the
	 * mapping's own iteration order.
	 */
	private static final int CRYSTAL_BODY = 23_975;
	private static final int ARMOUR_SEED = 23_956;
	private static final String SEED_NAME = "Crystal armour seed";
	/** The seed's guide price on the anchor day 08 Sep, and on every earlier day in the fixture. */
	private static final long SEED_NOW = 5_564_922L;
	private static final long SEED_THEN = 5_500_000L;
	/** A graceful hood reverts to 28 marks of grace - and a mark of grace is ITSELF an id ItemMapping rewrites. */
	private static final int MARK_OF_GRACE = 11_849;
	private static final String MARK_NAME = "Mark of grace";

	/** The captured bank plus one Crystal body, marked untradeable and carrying its three seeds (R1). */
	private static BankSnapshot bankWithCrystalBody(final long capturedAt)
	{
		final BankSnapshot snapshot = bank(capturedAt);
		snapshot.items.add(new BankItem(CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME))));
		return snapshot;
	}

	/**
	 * R2, the headline: an untradeable stack RuneLite maps onto tradeable parts is priced at the SUM of those
	 * parts, now and then, and moves with them - 3 x 5,564,922 against 3 x 5,500,000, not the 900,000 gp alch value
	 * Q5 had to settle for. The user's own question, in one test.
	 */
	@Test
	public void anUntradeableStackWithPartsIsPricedAtTheSumOfItsParts()
	{
		nameTheSeed();
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(bankWithCrystalBody(T0));

		final MovementRow body = rowFor(lastRows(), CRYSTAL_BODY);
		assertNotNull("the untradeable stack is listed", body);
		assertEquals(MovementRow.PriceSource.PARTS, body.source());
		assertEquals("3 x 5,564,922", Long.valueOf(3L * SEED_NOW), body.unitPrice());
		assertEquals("3 x 5,500,000", Long.valueOf(3L * SEED_THEN), body.thenPrice());
		assertEquals(Long.valueOf(3L * (SEED_NOW - SEED_THEN)), body.deltaGp());
		assertEquals("and the tooltip's line has what it needs",
			Collections.singletonList(new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME)), body.parts());
	}

	/** R3: such a row counts in the bank value, in {@code itemsPriced} and in the window it has both days of. */
	@Test
	public void aPartsPricedStackCountsInTheBankValueAndInTheWindow()
	{
		nameTheSeed();
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(bankWithCrystalBody(T0));

		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals("6,290,824 + 3 x 5,564,922", 6_290_824L + 3L * SEED_NOW, p.valueNow());
		assertEquals("every gp of that sum is a guide price", 31, p.itemsPriced());
		assertEquals(32, p.itemsTotal());
		assertEquals(32, lastStatus().bankItems());

		final WindowMove d1 = p.move(MovementWindow.D1);
		assertEquals("the 28 of the tradeable bank, and the crystal body beside them", 29, d1.itemsCovered());
		assertEquals(283L + 3L * (SEED_NOW - SEED_THEN), d1.deltaGp());
		assertEquals("the day the prices are dated by is untouched", SEP_8, lastStatus().anchorDay());
		assertFalse(lastStatus().degraded());
	}

	/**
	 * R2: "null (the dash) when ANY part has no then-value". The seed is in the newest table but not in the one the
	 * 1d window landed on, so the row keeps its price and shows no move - never a move measured against a sum of
	 * the parts that happen to be known.
	 */
	@Test
	public void aPartWithNoBaselineValueLeavesThePartsRowWithoutAMove()
	{
		nameTheSeed();
		// The 1d baseline body, back without the seed in it.
		bodyOverrides.put(BOT_0907.revId(), snapshot(BOT_0907, clock.get()));
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(bankWithCrystalBody(T0));

		final MovementRow body = rowFor(lastRows(), CRYSTAL_BODY);
		assertEquals(MovementRow.PriceSource.PARTS, body.source());
		assertEquals(Long.valueOf(3L * SEED_NOW), body.unitPrice());
		assertNull("half a sum is not a baseline", body.thenPrice());
		assertFalse(body.hasMovement());
		assertEquals("priced, so it is in the total and the count", 31, lastStatus().portfolio().itemsPriced());
		assertEquals("but covered by no window", 28, lastStatus().portfolio().move(MovementWindow.D1).itemsCovered());
	}

	/**
	 * The B001 carve-out reaches the parts too, and a mark of grace is exactly that case: it is itself an id
	 * {@code ItemMapping} rewrites, so {@code getItemPriceWithSource} would answer another item's price for it. The
	 * part's own guide series is in the table, keyed by its own name, and that is what the sum uses.
	 */
	@Test
	public void aPartRuneLiteRewritesTakesItsNowFromTheGuideTable()
	{
		assertTrue("the fixture rests on it", PriceService.rewrittenByItemMapping(MARK_OF_GRACE));
		teachEveryTable(MARK_OF_GRACE, MARK_NAME, 100L, 90L);
		runelite.put(MARK_OF_GRACE, 999_999); // what ItemManager answers: ten of another item
		final BankSnapshot withHood = bank(T0);
		// Item 11850 is the graceful hood, which RuneLite maps onto 28 marks of grace.
		withHood.items.add(new BankItem(GRACEFUL, 1, "Graceful hood", false, true, 500,
			Collections.singletonList(new BankItem.Part(MARK_OF_GRACE, 28L, MARK_NAME))));
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));

		warmUpWith(withHood);

		final MovementRow hood = rowFor(lastRows(), GRACEFUL);
		assertEquals(MovementRow.PriceSource.PARTS, hood.source());
		assertEquals("28 x 100, never 28 x 999,999", Long.valueOf(2_800L), hood.unitPrice());
		assertEquals(Long.valueOf(2_520L), hood.thenPrice());
		verify(itemManager, never()).getItemPriceWithSource(MARK_OF_GRACE, false);
	}

	/**
	 * R2's fallback: a part no table names at all leaves the whole stack on Q5's alch rule - an ALCH row with its
	 * dash, out of {@code itemsPriced} and out of every window - rather than a partial sum. Nothing here teaches
	 * the seed, so nothing can price it.
	 */
	@Test
	public void anUntradeableStackWhosePartsHaveNoPriceKeepsTheAlchRule()
	{
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(bankWithCrystalBody(T0));

		final MovementRow body = rowFor(lastRows(), CRYSTAL_BODY);
		assertEquals(MovementRow.PriceSource.ALCH, body.source());
		assertEquals(Long.valueOf(900_000L), body.unitPrice());
		assertNull(body.parts());
		assertFalse(body.hasMovement());
		assertEquals("6,290,824 + 900,000", 6_290_824L + 900_000L, lastStatus().portfolio().valueNow());
		assertEquals("an alch value is not a market price", 30, lastStatus().portfolio().itemsPriced());
	}

	/**
	 * The pin for "with the switch off nothing of this runs". The SAME captured bank - one that now carries a stack
	 * with parts, because the reader records them whatever the switch says - must publish the identical rows, counts
	 * and bank value as a bank that never held it, down to the last field of the summary.
	 */
	@Test
	public void withUntradeablesOffAStackWithPartsChangesNothing()
	{
		nameTheSeed();
		warmUpWith(bankWithCrystalBody(T0));
		final List<MovementRow> withIt = lastRows();
		final PriceService.Status statusWithIt = lastStatus();

		service.setBank(bank(T0 + 1L));

		assertEquals("the same rows, in the same order", withIt, lastRows());
		assertEquals(statusWithIt.portfolio(), lastStatus().portfolio());
		assertEquals(statusWithIt.totalRows(), lastStatus().totalRows());
		assertEquals("and the same stack count", statusWithIt.bankItems(), lastStatus().bankItems());
		assertEquals(31, statusWithIt.bankItems());
		assertEquals(6_290_824L, statusWithIt.portfolio().valueNow());
		assertNull("no row of any kind was drawn for it", rowFor(withIt, CRYSTAL_BODY));
		verify(itemManager, never()).getItemPriceWithSource(ARMOUR_SEED, false);
	}

	// ---------------------------------------------------------------- the client-thread hop and superseded work

	@Test
	public void theGuidePricesAreReadOnTheClientThreadAndAStaleHopIsDropped()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();
		clientThread.deferred = true;

		service.setBank(bank(T0));
		assertEquals("the computation waits for the client thread", 1, clientThread.queued.size());
		assertTrue(lastRows().isEmpty());

		service.setFilter(RowFilter.DEFAULT.withGpMin(2_000L));
		assertEquals(2, clientThread.queued.size());
		clientThread.runAll();

		assertEquals("only the newest computation published: the band applied", 30 - countUnder(2_000L), lastRows().size());
		verify(itemManager, times(31)).getItemPriceWithSource(anyInt(), eq(false));
		verify(itemManager, never()).getItemPriceWithSource(anyInt(), eq(true));
	}

	@Test
	public void theMembersNameIsReadOnlyForItemsTheMappingDoesNotCover()
	{
		when(store.loadBucket(MovementWindow.D1)).thenReturn(diskBaseline(BOT_0907, T0));
		service.start();

		service.setBank(bank(T0));

		assertEquals("a stored baseline has no table in memory: no name is needed at all", 0, compositionReads());

		warmUp();

		assertTrue("with tables in memory: the box, the bones and the baited vessel", compositionReads() > 0);
		verify(itemManager, never()).getItemComposition(GREEN_HAT);
		verify(itemManager, never()).getItemComposition(item(1));
		verify(itemManager, never()).getItemComposition(TABLET);
		verify(itemManager, atLeastOnce()).getItemComposition(VESSEL_BAITED);
		verify(itemManager, atLeastOnce()).getItemComposition(BOX);
		assertEquals("exactly the three unmapped items per computation", 0, compositionReads() % 3);
	}

	// ---------------------------------------------------------------- B001: ids RuneLite rewrites through ItemMapping

	/**
	 * L1 says "now" is RuneLite's guide price for THIS item, and for a handful of ids it is not:
	 * {@code getItemPriceWithSource} asks {@code ItemMapping.map(id)} first and, where a mapping exists, returns the
	 * sum of the mapped TRADEABLE components instead of its own table's entry ({@code ItemManager.java:348-363}).
	 * Ring of wealth (5) folds onto the plain Ring of wealth, which is 2,428 gp cheaper - so the row used to read a
	 * permanent -18 % that no day ever changed, and the holding and the bank total were understated with it. The
	 * item's own guide series is right there in the newest table, keyed by its own name.
	 */
	@Test
	public void anIdRuneLiteRewritesTakesItsNowFromTheGuideTable()
	{
		nameTheRing();
		runelite.put(RING_OF_WEALTH_5, 10_832); // what ItemManager answers: the PLAIN ring's price
		final BankSnapshot withRing = bank(T0);
		withRing.items.add(new BankItem(RING_OF_WEALTH_5, 5, "Ring of wealth (5)", false));

		warmUpWith(withRing);

		final MovementRow ring = rowFor(lastRows(), RING_OF_WEALTH_5);
		assertNotNull("a GE-tradeable bank stack belongs in the list", ring);
		assertEquals("its OWN guide price, never the plain ring's", Long.valueOf(RING_GP), ring.unitPrice());
		assertEquals("and so no fabricated move", Long.valueOf(0L), ring.deltaGp());
		assertEquals("nor a fabricated holding", 5L * RING_GP, ring.holdingValue());
		verify(itemManager, never()).getItemPriceWithSource(RING_OF_WEALTH_5, false);
	}

	/** The same carve-out, for an id no guide table names at all: unpriced beats another item's 25m (B001). */
	@Test
	public void anIdRuneLiteRewritesWithNoGuidePriceIsLeftUnpriced()
	{
		// A crystal tool seed plus a dragon felling axe - what ItemManager sums for an id the guide table has no key for.
		runelite.put(CRYSTAL_2H_AXE, 25_191_857);
		final BankSnapshot withAxe = bank(T0);
		withAxe.items.add(new BankItem(CRYSTAL_2H_AXE, 1, "Crystal 2h axe", false));

		warmUpWith(withAxe);

		assertNull("no table names it, so it has no price at all", rowFor(lastRows(), CRYSTAL_2H_AXE));
		assertTrue("and no 25m of another item's price in the bank value",
			lastStatus().portfolio().valueNow() < 10_000_000L);
	}

	/**
	 * The list this plugin knows about, so a RuneLite version that widens {@code ItemMapping} is noticed here rather
	 * than in someone's sidebar - and so the fixture stays free of rewritten ids, which every price-read count in
	 * this suite depends on.
	 */
	@Test
	public void theIdsRuneLiteRewritesAreKnownAndNoneOfThemIsInTheFixture()
	{
		assertTrue("Ring of wealth (5), in the user's own bank", PriceService.rewrittenByItemMapping(RING_OF_WEALTH_5));
		assertTrue("Black mask (10)", PriceService.rewrittenByItemMapping(8_901));
		assertTrue("Crystal 2h axe", PriceService.rewrittenByItemMapping(CRYSTAL_2H_AXE));
		assertTrue("Crystal 2h axe (inactive)", PriceService.rewrittenByItemMapping(28_223));
		assertFalse("the plain ring is the TRADEABLE side of the mapping, not a rewritten id",
			PriceService.rewrittenByItemMapping(2_572));
		for (final BankItem item : bank(T0).items)
		{
			assertFalse(item.name + " is rewritten: its price read would be skipped and every count here would shift",
				PriceService.rewrittenByItemMapping(item.id));
		}
	}

	// ---------------------------------------------------------------- B002: the other direction of the anchor day

	/**
	 * L3 measured one direction only - the wiki's bot publishes day D hours after Jagex steps to it, so RuneLite
	 * LEADS for part of every day - and {@code deriveAnchorDay} therefore reads every disagreement as a lead. A
	 * client left open across an outage of RuneLite's price API holds an OLDER day than the wiki, and pushing the
	 * anchor forward there puts every window two days from the prices in hand and inverts the sign of a 1d move.
	 * When RuneLite matches the table for the day BEFORE R0 - the one it was serving until the index moved on, and
	 * which {@code evictLocked} keeps - the anchor steps back instead, and the status says the table is behind.
	 */
	@Test
	public void withRuneLiteBehindTheWikiTheAnchorStepsBackInsteadOfForward()
	{
		warmUp();
		assertEquals(SEP_8, lastStatus().anchorDay());
		final RevisionRef bot0909 = bot(15_335_900L, SEP_9, 10, 0, 0);
		final List<RevisionRef> grown = new ArrayList<>(HISTORY);
		grown.add(0, bot0909);
		history = grown;
		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);

		fireTick();
		answerIndex();
		answerTables(); // the wiki moves on to 09 Sep; RuneLite's own table stays on 08 Sep

		final PriceService.Status status = lastStatus();
		assertEquals(SEP_9, status.r0Day());
		assertEquals("RuneLite is a day BEHIND, so D = day(R0) - 1", SEP_8, status.anchorDay());
		assertTrue("and a reader is told to trust it less", status.degraded());
		assertTrue(status.degradedReason(), status.degradedReason().contains("behind"));
		assertFalse("this is not the too-few-samples flavour", status.anchorDegraded());
		assertEquals("so the 1d baseline is still 07 Sep, not 08 Sep", SEP_7, status.thenDay());
		assertEquals("and the real one-day move survives", Long.valueOf(-38L), rowFor(lastRows(), GREEN_HAT).deltaGp());
	}

	// ---------------------------------------------------------------- B008: the bank is written when it CHANGES

	/**
	 * The client posts one {@code ItemContainerChanged} per change to the bank container and the plugin turns every
	 * one into a {@code setBank}, so an unconditional save meant a full serialisation plus a temp file plus an
	 * atomic replace of the same bytes, over and over, on the client's one shared executor.
	 */
	@Test
	public void aBankWhoseContentsHaveNotMovedIsNotWrittenAgain()
	{
		service.start();

		service.setBank(bank(T0));
		service.setBank(bank(T0 + MINUTE));
		service.setBank(bank(T0 + 2L * MINUTE));

		verify(store, times(1)).saveBank(any(BankSnapshot.class));

		final BankSnapshot moved = bank(T0 + 3L * MINUTE);
		moved.items.add(new BankItem(1_042, 1, "Blue partyhat", false));
		service.setBank(moved);

		verify(store).saveBank(moved);
		verify(store, times(2)).saveBank(any(BankSnapshot.class));
	}

	// ---------------------------------------------------------------- B011: a manual refresh that sends nothing

	/**
	 * The cooldown is the price of a request, and {@code startIndex} drops a manual refresh outright while one is
	 * already in flight - so the user paid thirty seconds for nothing, and the {@code manual} flag that authorises
	 * a body fetch while the sidebar is hidden went with it. The intent now rides on the request already out, and
	 * the stamp is not spent.
	 */
	@Test
	public void aManualRefreshWhileAnIndexIsInFlightRidesOnItAndKeepsItsCooldown()
	{
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		fireTick();
		assertEquals(1, indexFutures.size());
		service.setVisible(false);

		service.refreshNow();

		assertEquals("nothing new could be sent", 1, indexFutures.size());
		assertEquals("so nothing was refused either - the line is the pending one, not a cooldown",
			PriceService.problemHistoryPending(MovementWindow.D1), lastStatus().problem());

		answerIndex();

		assertEquals("the manual intent rode along: a body is fetched even though the sidebar is hidden",
			1, tableRequests.size());

		service.refreshNow();

		assertEquals("and the cooldown was never spent", 2, indexFutures.size());
	}

	// ---------------------------------------------------------------- B013: a wall clock that jumps backwards

	/**
	 * The index and mapping stamps are read back off disk from an earlier session and compared against a wall
	 * clock. A machine whose clock was fast when the file was written, then corrected, leaves a stamp in the
	 * FUTURE - and {@code now - stampedAt >= maxAge} reads that as "fetched moments ago", so nothing would be
	 * refetched until real time caught up. A stamp from the future cannot be trusted, so it counts as expired.
	 */
	@Test
	public void aStampFromTheFutureCountsAsExpiredRatherThanFresh()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 + HOUR));
		when(store.loadMapping()).thenReturn(storedMapping(mappingTable(), T0 + HOUR));
		service.start();
		service.setBank(bank(T0));

		service.setVisible(true);
		fireTick();

		assertEquals("the index is refetched rather than trusted", 1, indexFutures.size());
		assertEquals("and so is the mapping", 1, mappingFutures.size());
	}

	/** The same jump must not wedge the manual refresh behind a cooldown that can never expire (B013). */
	@Test
	public void aBackwardsClockJumpDoesNotWedgeTheRefreshCooldown()
	{
		warmUp();
		final int before = indexFutures.size();
		service.refreshNow();
		answerIndex();
		assertEquals(before + 1, indexFutures.size());

		clock.addAndGet(-HOUR);
		service.refreshNow();

		assertNull("a stamp in the future is not \"refreshed a moment ago\"", lastStatus().problem());
		assertEquals(before + 2, indexFutures.size());
	}

	// ---------------------------------------------------------------- B042: the cooldown line deletes itself

	/**
	 * A refused refresh wrote one status and scheduled nothing, so its sentence - frozen at the second it was
	 * written - stood until the next publish of any kind, which with an idle sidebar is the thirty-minute tick.
	 * The cooldown itself is thirty SECONDS, so the panel sat red, and lying, for up to a hundred times as long as
	 * the state it described.
	 */
	@Test
	public void aRefusedRefreshRemovesItsOwnLineWhenTheCooldownEnds()
	{
		warmUp();
		service.refreshNow();
		clock.addAndGet(12 * SECOND);
		service.refreshNow();
		assertEquals("Refreshed 12 s ago - wait", lastStatus().text());
		final int requests = indexFutures.size();

		final Timer clear = oneShotWithDelay(PriceService.MANUAL_COOLDOWN_MS - 12 * SECOND);
		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS - 12 * SECOND);
		clear.fire();

		assertNull("the line is gone the moment the cooldown is", lastStatus().problem());
		assertEquals(PriceService.ProblemKind.NONE, lastStatus().problemKind());
		assertEquals("and clearing a line fetches nothing", requests, indexFutures.size());
	}

	// ---------------------------------------------------------------- B027: the index keeps what it has seen

	/**
	 * One call returns at most {@code INDEX_LIMIT} revisions and no continuation is followed, so replacing the
	 * stored index wholesale threw away reach the plugin had already paid for: a stretch of twice-daily edits
	 * shortens one fetch to under 180 days and the longest window then reads "No 180d history" over history that
	 * was in hand an hour earlier. A revision is immutable, so an older line can never go stale.
	 */
	@Test
	@SuppressWarnings("unchecked") // ArgumentCaptor.forClass cannot carry the list's element type
	public void theRevisionIndexKeepsRevisionsAFreshFetchNoLongerReaches()
	{
		when(store.loadRevisionIndex()).thenReturn(storedIndex(HISTORY, T0 - DAY));
		service.start();
		service.setBank(bank(T0));
		service.setVisible(true);
		history = Arrays.asList(BOT_0908, BOT_0907, BOT_0906);

		fireTick();
		answerIndex();

		final List<RevisionRef> index = service.revisionIndex();
		assertEquals("the three fetched lines merged into the fourteen already held", HISTORY.size(), index.size());
		assertEquals("newest first, still", BOT_0908, index.get(0));
		assertTrue("and the 180d revision survived a short answer", index.contains(BOT_0312));
		final ArgumentCaptor<List<RevisionRef>> saved = ArgumentCaptor.forClass(List.class);
		verify(store).saveRevisionIndex(saved.capture(), eq(T0));
		assertEquals("what is persisted is the merged list", HISTORY, saved.getValue());
	}

	// ---------------------------------------------------------------- B109: a failed body batch is retried

	/**
	 * A failed batch used to wait out the whole {@code TICK_MS}: the wiki could recover ten seconds later and the
	 * panel stayed red for half an hour, with the user's only lever a Refresh the failure sentence never mentions.
	 * One backed-off retry closes that, and the delay doubles so a real outage settles onto the ordinary cadence.
	 */
	@Test
	public void aFailedBodyBatchIsRetriedLongBeforeTheNextTick()
	{
		warmUp();
		final RevisionRef bot0909 = bot(15_335_900L, SEP_9, 10, 0, 0);
		final List<RevisionRef> grown = new ArrayList<>(HISTORY);
		grown.add(0, bot0909);
		history = grown;
		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);
		fireTick();
		answerIndex();
		final int before = tableRequests.size();

		tableFutures.get(tableFutures.size() - 1).completeExceptionally(new WikiPriceException("wiki request failed: reset"));

		assertEquals("no tight loop", before, tableRequests.size());

		oneShotWithDelay(PriceService.RETRY_MS).fire();

		assertEquals("the retry asks again", before + 1, tableRequests.size());
		assertEquals(Collections.singletonList(bot0909.revId()), tableRequests.get(before));

		tableFutures.get(tableFutures.size() - 1).completeExceptionally(new WikiPriceException("wiki request failed: reset"));

		assertNotNull("and backs off rather than knocking every minute", oneShotWithDelay(2L * PriceService.RETRY_MS));
	}

	/**
	 * The two one-shots are independent - arming either leaves the other pending - and {@code stop()} cancels
	 * both. A timer that outlived the plugin would publish to a cleared listener list or fetch bodies nobody
	 * asked for.
	 */
	@Test
	public void bothOneShotsCanBePendingAtOnceAndStopCancelsBoth()
	{
		warmUp();
		final RevisionRef bot0909 = bot(15_335_900L, SEP_9, 10, 0, 0);
		final List<RevisionRef> grown = new ArrayList<>(HISTORY);
		grown.add(0, bot0909);
		history = grown;
		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS);
		fireTick();
		answerIndex();
		tableFutures.get(tableFutures.size() - 1).completeExceptionally(new WikiPriceException("wiki request failed: reset"));
		final Timer retry = oneShotWithDelay(PriceService.RETRY_MS);

		service.refreshNow();
		clock.addAndGet(12 * SECOND);
		service.refreshNow();
		final Timer cooldown = oneShotWithDelay(PriceService.MANUAL_COOLDOWN_MS - 12 * SECOND);
		assertFalse("arming the cooldown clear leaves the body retry alone", retry.future.isCancelled());

		service.stop();

		assertTrue("the body retry is cancelled", retry.future.isCancelled());
		assertTrue("and so is the cooldown clear", cooldown.future.isCancelled());
	}

	// ---------------------------------------------------------------- fixtures

	// ================================================================ addendum T: live prices (T2-T5, T7)

	/**
	 * The liquid item of this section. Its guide price on the anchor day is 800,250 and on 07 Sep 800,249 (the
	 * fixture's {@code 800,000 + day index}), so the GUIDE 1d move is +1 gp - and the live figures below are
	 * nowhere near it, which is what makes every "which series is this?" assertion unambiguous.
	 */
	private static final long WHIP_GUIDE_NOW = 800_000L + 250L;
	/** buy 860,000 / sell 840,000: mid 850,000, a 20,000 gp gap (2.4 % of the mid) and 6 % from the guide. */
	private static final long WHIP_LIVE_MID = 850_000L;
	/** 07 Sep's bucket: (820,000 x 300 + 800,000 x 200) / 500. */
	private static final long WHIP_TRADED_THEN = 812_000L;

	private Map<Integer, TradedPriceClient.Quote> latestQuotes()
	{
		final long now = clock.get() / 1000L;
		final Map<Integer, TradedPriceClient.Quote> quotes = new LinkedHashMap<>();
		quotes.put(WHIP, new TradedPriceClient.Quote(860_000L, now - 3_600L, 840_000L, now - 7_200L));
		// The thin one: a perfectly tight quote that yesterday's volume refuses anyway.
		quotes.put(GREEN_HAT, new TradedPriceClient.Quote(1_100L, now - 600L, 1_080L, now - 900L));
		return quotes;
	}

	/** 07 Sep's traded bucket: the whip is liquid, the hat traded eight units all day. */
	private static Map<Integer, TradedPriceClient.Bucket> tradedSep7()
	{
		final Map<Integer, TradedPriceClient.Bucket> buckets = new LinkedHashMap<>();
		buckets.put(WHIP, new TradedPriceClient.Bucket(820_000L, 300L, 800_000L, 200L));
		buckets.put(GREEN_HAT, new TradedPriceClient.Bucket(1_100L, 5L, 1_080L, 3L));
		return buckets;
	}

	// ---------------------------------------------------------------- T3: the three checks, at every edge

	@Test
	public void theThreeLiquidityConstantsAreTheOnesAddendumTMeasured()
	{
		assertEquals("100 units a day is the line between a market and one person clearing their bank",
			100L, PriceService.LIVE_MIN_VOLUME);
		assertEquals("a gap wider than a tenth of the mid is not a price anybody paid",
			10, PriceService.LIVE_MAX_SPREAD_PCT);
		assertEquals("the user's own \"ignore live data changes that is >50 % of the 24 hour value\"",
			50, PriceService.LIVE_MAX_GUIDE_DRIFT_PCT);
		assertEquals(24L * 60L * 60L, PriceService.LIVE_QUOTE_MAX_AGE_SECONDS);
		assertEquals(6L * 60L * 60L * 1000L, PriceService.LIVE_LATEST_MAX_AGE_MS);
	}

	/** Check 1, both sides of {@value PriceService#LIVE_MIN_VOLUME}. */
	@Test
	public void theVolumeCheckRefusesBelowTheMinimumAndPassesAtIt()
	{
		final TradedPriceClient.Quote quote = fresh(1_050L, 950L);

		assertNull("exactly 100 is enough",
			PriceService.liveRefusal(quote, yesterdayFor(quote, 100L), 1_000L, NOW_SECONDS));
		assertEquals("99 traded yesterday",
			PriceService.liveRefusal(quote, yesterdayFor(quote, 99L), 1_000L, NOW_SECONDS));
		assertEquals("a bucket that names the item and no trades really is zero", "0 traded yesterday",
			PriceService.liveRefusal(quote, yesterdayFor(quote, 0L), 1_000L, NOW_SECONDS));
		assertEquals("no bucket at all is not a measurement", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(quote, null, 1_000L, NOW_SECONDS));
	}

	/** Check 2, both sides of {@value PriceService#LIVE_MAX_SPREAD_PCT} % of the mid. */
	@Test
	public void theSpreadCheckRefusesAWiderGapThanATenthOfTheMid()
	{
		assertNull("a gap of exactly 10 % of the mid passes",
			PriceService.liveRefusal(fresh(1_050L, 950L), yesterdayFor(fresh(1_050L, 950L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("and one gp past it does not", "buy/sell gap 10 %",
			PriceService.liveRefusal(fresh(1_051L, 949L), yesterdayFor(fresh(1_051L, 949L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("the phrase names the gap as a percentage of the mid", "buy/sell gap 18 %",
			PriceService.liveRefusal(fresh(1_200L, 1_000L), yesterdayFor(fresh(1_200L, 1_000L), 500L), 1_100L, NOW_SECONDS));
		assertEquals("a one-sided quote has no spread to measure", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(fresh(1_000L, null), yesterdayFor(fresh(1_000L, null), 500L), 1_000L, NOW_SECONDS));
		assertEquals("and neither has no quote at all", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(null, yesterdayFor(null, 500L), 1_000L, NOW_SECONDS));
	}

	/** Check 2's freshness half, both sides of {@value PriceService#LIVE_QUOTE_MAX_AGE_SECONDS} seconds. */
	@Test
	public void theSpreadCheckRefusesASideThatHasNotTradedForADay()
	{
		final long day = PriceService.LIVE_QUOTE_MAX_AGE_SECONDS;
		final TradedPriceClient.Quote onTime = new TradedPriceClient.Quote(1_050L, NOW_SECONDS - day, 950L, NOW_SECONDS);
		final TradedPriceClient.Quote staleBuy =
			new TradedPriceClient.Quote(1_050L, NOW_SECONDS - day - 1L, 950L, NOW_SECONDS);
		final TradedPriceClient.Quote staleSell =
			new TradedPriceClient.Quote(1_050L, NOW_SECONDS, 950L, NOW_SECONDS - day - 1L);

		assertNull("exactly a day old still counts",
			PriceService.liveRefusal(onTime, yesterdayFor(onTime, 500L), 1_000L, NOW_SECONDS));
		assertEquals("one second older does not", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(staleBuy, yesterdayFor(staleBuy, 500L), 1_000L, NOW_SECONDS));
		assertEquals("the SELL side counts the same", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(staleSell, yesterdayFor(staleSell, 500L), 1_000L, NOW_SECONDS));
	}

	/** Check 3, both sides of {@value PriceService#LIVE_MAX_GUIDE_DRIFT_PCT} % of the guide price, either way. */
	@Test
	public void theSanityCheckRefusesAMidMoreThanHalfAwayFromTheGuide()
	{
		assertNull("exactly half again passes",
			PriceService.liveRefusal(fresh(1_500L, 1_500L), yesterdayFor(fresh(1_500L, 1_500L), 500L), 1_000L, NOW_SECONDS));
		assertNull("and exactly half passes",
			PriceService.liveRefusal(fresh(500L, 500L), yesterdayFor(fresh(500L, 500L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("a gp past it does not", "live price 50 % from guide",
			PriceService.liveRefusal(fresh(1_501L, 1_501L), yesterdayFor(fresh(1_501L, 1_501L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("and neither does a gp under", "live price 50 % from guide",
			PriceService.liveRefusal(fresh(499L, 499L), yesterdayFor(fresh(499L, 499L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("the phrase names how far off it is - T6's own example", "live price 61 % from guide",
			PriceService.liveRefusal(fresh(1_610L, 1_610L), yesterdayFor(fresh(1_610L, 1_610L), 500L), 1_000L, NOW_SECONDS));
		assertEquals("an item RuneLite cannot price has no anchor to check against", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(fresh(1_000L, 1_000L), yesterdayFor(fresh(1_000L, 1_000L), 500L), null, NOW_SECONDS));
	}

	/**
	 * The checks multiply a price by up to 200, so a figure no exchange could produce is refused rather than
	 * measured: a product that wrapped would come out NEGATIVE and pass every comparison silently, putting a
	 * nonsense price on a row and into the bank total.
	 */
	@Test
	public void anAbsurdPriceIsRefusedRatherThanWrappedThroughTheChecks()
	{
		final long ceiling = PriceService.LIVE_SANE_PRICE_CEILING;
		final TradedPriceClient.Quote atCeiling = fresh(ceiling, ceiling);

		assertNull("a price at the ceiling is still measured",
			PriceService.liveRefusal(atCeiling, yesterdayFor(atCeiling, 500L), ceiling, NOW_SECONDS));
		assertEquals("a mid past it is not", PriceService.LIVE_NO_DATA, PriceService.liveRefusal(
			fresh(ceiling + 2L, ceiling + 2L), yesterdayFor(atCeiling, 500L), ceiling, NOW_SECONDS));
		assertEquals("and neither is a guide price past it", PriceService.LIVE_NO_DATA,
			PriceService.liveRefusal(atCeiling, yesterdayFor(atCeiling, 500L), ceiling + 1L, NOW_SECONDS));
		assertEquals("and neither is a day's average past it - check 5 multiplies that one by 50",
			PriceService.LIVE_NO_DATA, PriceService.liveRefusal(atCeiling,
				new TradedPriceClient.Bucket(ceiling + 1L, 500L, null, 0L), ceiling, NOW_SECONDS));
		assertEquals("and a day whose MIDDLE is past it says nothing rather than a wrapped gap (checker)",
			PriceService.LIVE_NO_DATA, PriceService.liveRefusal(fresh(1_000L, 1_000L),
				new TradedPriceClient.Bucket(Long.MAX_VALUE / 2L, 300L, 1L, 300L), 1_000L, NOW_SECONDS));
		assertEquals("Long.MAX_VALUE / 200 is four orders of magnitude past the game's dearest item",
			Long.MAX_VALUE / 200L, ceiling);
	}

	/** T3's order is part of the contract: a reader gets ONE line, and it must name the first failing check. */
	@Test
	public void theFirstFailingCheckIsTheOneReported()
	{
		// Fails all five at once: thin, wide, miles from the guide, and over a scattered day miles from the mid.
		assertEquals("volume is asked first", "3 traded yesterday", PriceService.liveRefusal(fresh(3_000L, 1_000L),
			new TradedPriceClient.Bucket(300L, 2L, 100L, 1L), 1_000L, NOW_SECONDS));
		// Volume fine, the rest still fail: today's gap is asked before the guide sanity check.
		assertEquals("buy/sell gap 100 %", PriceService.liveRefusal(fresh(3_000L, 1_000L),
			new TradedPriceClient.Bucket(300L, 300L, 100L, 300L), 1_000L, NOW_SECONDS));
		// Volume and today's gap fine; the GUIDE check is asked before yesterday's gap (V3) and the jump (V4).
		assertEquals("live price 200 % from guide", PriceService.liveRefusal(fresh(3_000L, 3_000L),
			new TradedPriceClient.Bucket(300L, 300L, 100L, 300L), 1_000L, NOW_SECONDS));
		// The first three fine; yesterday's gap is asked before the jump, and both fail here.
		assertEquals("buy/sell gap 100 % yesterday", PriceService.liveRefusal(fresh(1_000L, 1_000L),
			new TradedPriceClient.Bucket(300L, 300L, 100L, 300L), 1_000L, NOW_SECONDS));
		// Four fine, one left: a tight day 200 gp away from a live price of 1,000.
		assertEquals("live price 400 % from yesterday's average", PriceService.liveRefusal(fresh(1_000L, 1_000L),
			new TradedPriceClient.Bucket(200L, 600L, null, 0L), 1_000L, NOW_SECONDS));
	}

	// -------------------------------------------- addendum V: the daily bucket's own gap (V3) and the jump (V4)

	/** V4's own constant, beside T3's three: the user's rule read against the 24-hour figure they named. */
	@Test
	public void theDayJumpConstantIsTheUsersFiftyPercentRule()
	{
		assertEquals("\"ignore live data changes that is >50 % of the 24 hour value\" - the user, 2026-09-12",
			50, PriceService.LIVE_MAX_DAY_JUMP_PCT);
	}

	/**
	 * V3: the ONE predicate both readers ask - check 4 on yesterday's bucket, {@code Inputs.tradedThen} on every
	 * window's. Three ways to fail and two ways to pass, so neither reader can be given a bucket the other would
	 * have refused.
	 */
	@Test
	public void theBucketPredicateIsTheOneRuleBothReadersAsk()
	{
		assertFalse("no bucket at all", PriceService.bucketUsable(null));
		assertFalse("volumes but no price is not a price",
			PriceService.bucketUsable(new TradedPriceClient.Bucket(null, 700L, null, 300L)));
		assertFalse("99 units is under the same line check 1 draws",
			PriceService.bucketUsable(new TradedPriceClient.Bucket(1_000L, 50L, 1_000L, 49L)));
		assertFalse("and a day whose two sides are 100 % apart is not one price",
			PriceService.bucketUsable(new TradedPriceClient.Bucket(37L, 5_063L, 12L, 484L)));
		assertTrue("a tight, busy day is",
			PriceService.bucketUsable(new TradedPriceClient.Bucket(1_050L, 300L, 1_000L, 200L)));
		assertTrue("and so is a one-sided one: there is no gap to measure",
			PriceService.bucketUsable(new TradedPriceClient.Bucket(1_000L, 300L, null, 0L)));
	}

	/**
	 * V3 check 4, both sides of {@value PriceService#LIVE_MAX_SPREAD_PCT} % of the bucket's own middle. The
	 * arithmetic, stated: a day averaging 1,163 bought and 1,053 sold has a middle of (1,163 + 1,053 + 1) / 2 =
	 * 1,108 and a gap of 110, and 110 x 100 = 11,000 is inside 10 x 1,108 = 11,080. One gp more of a gap -
	 * 1,163 against 1,052, the same 1,108 middle - makes it 11,100 against 11,080, and the day is refused.
	 */
	@Test
	public void theYesterdayGapCheckRefusesAScatteredDayAndPassesATightOne()
	{
		final TradedPriceClient.Quote quote = fresh(1_108L, 1_108L);

		assertNull("a gap of exactly a tenth of the bucket's middle passes", PriceService.liveRefusal(quote,
			new TradedPriceClient.Bucket(1_163L, 300L, 1_053L, 300L), 1_108L, NOW_SECONDS));
		assertEquals("and one gp past it does not", "buy/sell gap 10 % yesterday", PriceService.liveRefusal(quote,
			new TradedPriceClient.Bucket(1_163L, 300L, 1_052L, 300L), 1_108L, NOW_SECONDS));
		assertEquals("the phrase names the gap as a percentage of that middle, and says WHICH day",
			"buy/sell gap 40 % yesterday", PriceService.liveRefusal(fresh(723L, 723L),
				new TradedPriceClient.Bucket(630L, 806L, 422L, 522L), 723L, NOW_SECONDS));
	}

	/** V3: a day with one side only has no gap to measure, and its one side IS the day's price. */
	@Test
	public void aOneSidedDayHasNoGapToMeasureAndPassesCheckFour()
	{
		assertNull("nobody sold that day, so the buy side is the whole of it", PriceService.liveRefusal(
			fresh(1_000L, 1_000L), new TradedPriceClient.Bucket(1_000L, 300L, null, 0L), 1_000L, NOW_SECONDS));
		assertNull("and the other way round", PriceService.liveRefusal(
			fresh(1_000L, 1_000L), new TradedPriceClient.Bucket(null, 0L, 1_000L, 300L), 1_000L, NOW_SECONDS));
	}

	/**
	 * V4 check 5, both sides of {@value PriceService#LIVE_MAX_DAY_JUMP_PCT} % of yesterday's average. A live mid
	 * of 150 over a day that averaged 100 is 50 x 100 = 5,000 against 50 x 100 = 5,000 - equal, so it stands; 151
	 * makes it 5,100 against 5,000, and the stack goes back to the guide.
	 */
	@Test
	public void theJumpCheckRefusesALivePriceMoreThanHalfFromYesterdaysAverage()
	{
		final TradedPriceClient.Bucket day = new TradedPriceClient.Bucket(100L, 300L, 100L, 300L);

		assertNull("exactly half again passes",
			PriceService.liveRefusal(fresh(150L, 150L), day, 150L, NOW_SECONDS));
		assertEquals("and one gp past it does not", "live price 51 % from yesterday's average",
			PriceService.liveRefusal(fresh(151L, 151L), day, 151L, NOW_SECONDS));
		assertNull("exactly half under passes too",
			PriceService.liveRefusal(fresh(50L, 50L), day, 50L, NOW_SECONDS));
		assertEquals("and a gp under that does not", "live price 51 % from yesterday's average",
			PriceService.liveRefusal(fresh(49L, 49L), day, 49L, NOW_SECONDS));
	}

	/**
	 * The live look of 2026-09-12, 21:24 EDT: Tinderbox led "Biggest gainers" at +181 % on a quote of 100 / 96
	 * over a day that bought 5,063 at 37 and sold 484 at 12. It passes T3's three checks - 5,547 units is a busy
	 * day, a gap of 4 on a mid of 98 is tight, and the guide is right there - and V3's check 4 is what refuses it:
	 * the middle of that day is (37 + 12 + 1) / 2 = 25 and its gap is 25, exactly 100 % of it.
	 */
	@Test
	public void theTinderboxCaseIsRefusedByItsOwnDaysGap()
	{
		assertEquals("buy/sell gap 100 % yesterday", PriceService.liveRefusal(fresh(100L, 96L),
			new TradedPriceClient.Bucket(37L, 5_063L, 12L, 484L), 98L, NOW_SECONDS));
	}

	/**
	 * The same quote over a day that was TIGHT and averaged 35: check 4 has nothing to say about it, and check 5
	 * is what refuses it - 98 against 35 is a jump of 63, which is 180 % of the day to the whole percent.
	 */
	@Test
	public void theTinderboxQuoteOverATightDayIsRefusedByTheJumpInstead()
	{
		assertEquals("live price 180 % from yesterday's average", PriceService.liveRefusal(fresh(100L, 96L),
			new TradedPriceClient.Bucket(35L, 100L, 35L, 100L), 98L, NOW_SECONDS));
	}

	/**
	 * The other half of the same live look: Confliction gauntlets, the case the two new guards must leave alone.
	 * Quote 63,732,159 / 62,217,259 (mid 62,974,709, a gap of 1,514,900 = 2 %); yesterday 63,774,688 over 271
	 * bought and 62,893,382 over 336 sold - 607 units, a middle of 63,334,035 with a gap of 881,306 = 1 %, and a
	 * weighted average of 63,286,848, which the live mid sits within half a percent of.
	 */
	@Test
	public void theConflictionGauntletsCasePassesAllFiveChecks()
	{
		assertNull(PriceService.liveRefusal(fresh(63_732_159L, 62_217_259L),
			new TradedPriceClient.Bucket(63_774_688L, 271L, 62_893_382L, 336L), 63_437_264L, NOW_SECONDS));
	}

	// ---------------------------------------------------------------- T2: the cadence

	/** T2: "/latest is fetched ... at start-up - ONLY while livePrices is on". */
	@Test
	public void theLatestSnapshotIsFetchedAtStartUp()
	{
		liveService();

		service.start();

		assertEquals(1, latestFutures.size());
		verify(traded, times(1)).fetchLatest(anyLong());
	}

	/** T2: and on the thirty-minute tick, which is the cadence the feed is read at. */
	@Test
	public void theLatestSnapshotIsFetchedAgainOnEveryTick()
	{
		warmUpLive();
		// The warm-up's own tick has one out already; one is in flight at a time, so answer it first.
		answerLatest(latestQuotes());
		final int before = latestFutures.size();

		fireTick();

		assertEquals("one more request, and no more than one", before + 1, latestFutures.size());
		fireTick();
		assertEquals("and nothing while that one is still out", before + 1, latestFutures.size());
	}

	/** T2: and on every accepted Refresh - the user's lever when a figure looks stale. */
	@Test
	public void anAcceptedRefreshFetchesTheLatestSnapshot()
	{
		warmUpLive();
		answerLatest(latestQuotes());
		final int before = latestFutures.size();
		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS + SECOND);

		service.refreshNow();

		assertEquals(before + 1, latestFutures.size());
	}

	/**
	 * T2's whole point: with the switch off, not one traded request is made - at start-up, on a tick, or on a
	 * Refresh - even though the client is right there. "Off = exactly today."
	 */
	@Test
	public void theSwitchOffSendsNotOneTradedRequest()
	{
		liveService();
		service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));

		warmUpBody();
		fireTick();
		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS + SECOND);
		service.refreshNow();

		verify(traded, never()).fetchLatest(anyLong());
		verify(traded, never()).fetchDay(any(LocalDate.class), anyLong());
		assertTrue(dayRequests.isEmpty());
	}

	/**
	 * T1: turning the switch on mid-session asks for the snapshot at once rather than waiting for the tick - and
	 * U1: the day buckets follow that snapshot, because its own UTC date is the day they are counted back from.
	 */
	@Test
	public void turningTheSwitchOnFetchesTheSnapshotAtOnce()
	{
		liveService();
		service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		warmUpBody();
		assertTrue(latestFutures.isEmpty());

		service.setOptions(ViewOptions.DEFAULT.withLivePrices(true));

		assertEquals(1, latestFutures.size());
		assertTrue("no live day yet, so nothing to count back from", dayRequests.isEmpty());

		answerLatest(latestQuotes());

		assertFalse("and the day buckets follow the snapshot", dayRequests.isEmpty());
		assertEquals("counted back from the snapshot's own UTC date", SEP_7, dayRequests.get(0));
	}

	/**
	 * U1: "window N compares the live price against the traded daily average of calendar day {@code liveDay - N},
	 * where liveDay is the UTC date of the live snapshot's fetchedAtMillis. The guide's anchor day plays no part."
	 *
	 * <p>The 90d window is the one that proves it: its guide BASELINE landed on 09 Jun (the newest revision at or
	 * before the target), while the live series wants 10 Jun - exactly {@code liveDay - 90}. Before addendum U the
	 * traded bucket followed the baseline, which is how a live "1d" row came to span two days (+30 % on a Partyhat
	 * set, live look 5).
	 */
	@Test
	public void oneBucketPerWindowIsFetchedForTheDayTheLiveSnapshotCountsBackTo()
	{
		warmUpLive();

		assertEquals("one per window, in window order", Arrays.asList(SEP_7, SEP_1, AUG_9, JUN_10, MAR_12), dayRequests);
		assertFalse("the live day itself is the running day and is never asked for", dayRequests.contains(SEP_8));
		for (final MovementWindow window : MovementWindow.values())
		{
			assertEquals("liveDay - N, for every window", SEP_8.minusDays(window.days()),
				dayRequests.get(window.ordinal()));
		}
		assertEquals("and the guide's own baseline for 90d is a DIFFERENT day, which no longer matters", JUN_9,
			service.baseline(MovementWindow.D90).dataDay());
	}

	@Test
	public void aBucketIsNotAskedForTwiceInOneLiveDay()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());
		final int before = dayRequests.size();

		fireTick();
		fireTick();

		assertEquals("nothing is asked for again while the live day stands", before, dayRequests.size());
	}

	/** T7: a failed bucket leaves that window on the guide, and Refresh is the user's lever to try again. */
	@Test
	public void aFailedBucketIsRetriedOnlyOnARefresh()
	{
		warmUpLive();
		failDay(SEP_7);
		final int afterFailure = dayRequests.size();

		fireTick();
		assertEquals("not on the tick", afterFailure, dayRequests.size());

		clock.addAndGet(PriceService.MANUAL_COOLDOWN_MS + SECOND);
		service.refreshNow();
		assertTrue("but Refresh asks again", dayRequests.size() > afterFailure);
		assertEquals(SEP_7, dayRequests.get(dayRequests.size() - 1));
	}

	// ---------------------------------------------------------------- T4: the figures, per row and per window

	/** T4: a liquid stack is priced at the live mid and compared against that day's traded average. */
	@Test
	public void aLiquidStackIsPricedLiveAndComparedAgainstTheTradedAverage()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertSame(MovementRow.PriceSource.LIVE, whip.source());
		assertTrue(whip.isLive());
		assertEquals(Long.valueOf(WHIP_LIVE_MID), whip.unitPrice());
		assertEquals(Long.valueOf(WHIP_TRADED_THEN), whip.thenPrice());
		assertEquals(Long.valueOf(WHIP_LIVE_MID - WHIP_TRADED_THEN), whip.deltaGp());
		assertSame(MovementRow.PriceSource.LIVE, whip.windowSource(MovementWindow.D1));
		assertEquals("the two sides and the volume travel with it", Long.valueOf(860_000L), whip.liveFacts().buy());
		assertEquals(Long.valueOf(840_000L), whip.liveFacts().sell());
		assertEquals(500L, whip.liveFacts().volumeYesterday());
		assertNull(whip.liveFacts().reason());
	}

	/** T6: a thin stack stays on the guide and says which check refused it. */
	@Test
	public void aThinStackStaysOnTheGuideAndNamesTheCheckThatRefusedIt()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final MovementRow hat = rowFor(service.currentRows(), GREEN_HAT);
		assertSame(MovementRow.PriceSource.GUIDE, hat.source());
		assertFalse(hat.isLive());
		assertEquals("the guide price, exactly as before addendum T", Long.valueOf(1_086L), hat.unitPrice());
		assertEquals(Long.valueOf(1_124L), hat.thenPrice());
		assertEquals("8 traded yesterday", hat.liveFacts().reason());
		assertSame(MovementRow.PriceSource.GUIDE, hat.windowSource(MovementWindow.D1));
	}

	/** An item the traded feed does not name at all is a refusal, never a guess. */
	@Test
	public void anItemTheFeedDoesNotNameStaysOnTheGuideWithNoLiveData()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final MovementRow shark = rowFor(service.currentRows(), SHARK);
		assertSame(MovementRow.PriceSource.GUIDE, shark.source());
		assertEquals(PriceService.LIVE_NO_DATA, shark.liveFacts().reason());
	}

	/**
	 * T4's fallback: a live row whose bucket for a window is missing compares the GUIDE's two ends for it -
	 * "never live-now against guide-then" - while still printing the live price. Here only 07 Sep's bucket
	 * arrived, so 1d is live and 7d is not.
	 */
	@Test
	public void aWindowWithNoBucketFallsBackToTheGuidesOwnTwoEnds()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());
		assertSame(MovementRow.PriceSource.LIVE, rowFor(service.currentRows(), WHIP).windowSource(MovementWindow.D1));

		service.setFilter(service.filter().withWindow(MovementWindow.D7));

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertTrue("it is still a live row and still prints the live price", whip.isLive());
		assertEquals(Long.valueOf(WHIP_LIVE_MID), whip.unitPrice());
		assertSame("but 7d compares the guide's two ends", MovementRow.PriceSource.GUIDE,
			whip.windowSource(MovementWindow.D7));
		assertEquals("the guide's own baseline", Long.valueOf(800_000L + dayIndex(SEP_1)), whip.thenPrice());
		assertEquals("and the guide's own move, not the live mid against a guide baseline",
			Long.valueOf(WHIP_GUIDE_NOW - (800_000L + dayIndex(SEP_1))), whip.deltaGp());
		assertEquals("the holding follows the price it prints", WHIP_LIVE_MID, whip.holdingValue());
	}

	/** T4: a bucket that names the item but too few trades of it is the same fallback, for the same reason. */
	@Test
	public void aWindowWhoseBucketIsTooThinFallsBackToo()
	{
		warmUpLive();
		answerDay(SEP_7, Collections.singletonMap(WHIP,
			new TradedPriceClient.Bucket(820_000L, 60L, 800_000L, 500L)));

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertTrue("560 units yesterday clears the eligibility check", whip.isLive());

		// ...but a window whose own bucket is under the minimum cannot be the "then" of a live comparison.
		answerNextDayRequestFor(SEP_1, Collections.singletonMap(WHIP,
			new TradedPriceClient.Bucket(790_000L, 40L, 780_000L, 50L)));
		service.setFilter(service.filter().withWindow(MovementWindow.D7));

		final MovementRow over7d = rowFor(service.currentRows(), WHIP);
		assertSame("90 units that day is not a traded average worth comparing", MovementRow.PriceSource.GUIDE,
			over7d.windowSource(MovementWindow.D7));
	}

	/**
	 * V3, the predicate's second reader: a stack can be perfectly liquid today and still have had a SCATTERED day
	 * a week ago, and the average of a day whose two sides are 25 % apart is not a price to compare against. That
	 * window falls back to the guide's own two ends while the stack stays live on a tight yesterday - the same
	 * fallback a thin bucket gets, for the same reason.
	 */
	@Test
	public void aWindowWhoseBucketIsScatteredFallsBackWhileTheStackStaysLive()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());
		// 01 Sep: busy (600 units) but two prices, not one - 900k bought against 700k sold, 25 % of the middle.
		answerDay(SEP_1, Collections.singletonMap(WHIP,
			new TradedPriceClient.Bucket(900_000L, 300L, 700_000L, 300L)));

		final MovementRow onD1 = rowFor(service.currentRows(), WHIP);
		assertTrue("yesterday was tight, so the stack is live", onD1.isLive());
		assertSame(MovementRow.PriceSource.LIVE, onD1.windowSource(MovementWindow.D1));

		service.setFilter(service.filter().withWindow(MovementWindow.D7));

		final MovementRow over7d = rowFor(service.currentRows(), WHIP);
		assertTrue("it is still a live row and still prints the live price", over7d.isLive());
		assertEquals(Long.valueOf(WHIP_LIVE_MID), over7d.unitPrice());
		assertSame("but 7d compares the guide's two ends", MovementRow.PriceSource.GUIDE,
			over7d.windowSource(MovementWindow.D7));
		assertEquals("the guide's own baseline, never that day's scattered average",
			Long.valueOf(800_000L + dayIndex(SEP_1)), over7d.thenPrice());
	}

	// ---------------------------------------------------------------- T5: the card

	/** T5: the total counts each stack at the price its own row prints, and says how many were live. */
	@Test
	public void theBankValueCountsEachStackOnItsOwnSeries()
	{
		warmUpLive();
		final long guideOnly = service.currentStatus().portfolio().valueNow();

		answerDay(SEP_7, tradedSep7());

		final PortfolioSummary summary = service.currentStatus().portfolio();
		assertEquals("only the whip went live", 1, summary.liveRows());
		assertEquals("and the total gains exactly what it moved by", guideOnly + WHIP_LIVE_MID - WHIP_GUIDE_NOW,
			summary.valueNow());
	}

	/** T5: every window's both-days basis uses each row's own series for that window. */
	@Test
	public void everyWindowSumsTheSeriesItsOwnRowsCompared()
	{
		warmUpLive();
		final WindowMove guideOnly = service.currentStatus().portfolio().move(MovementWindow.D1);
		final WindowMove guideOnly7d = service.currentStatus().portfolio().move(MovementWindow.D7);

		answerDay(SEP_7, tradedSep7());

		final PortfolioSummary summary = service.currentStatus().portfolio();
		final WindowMove d1 = summary.move(MovementWindow.D1);
		assertEquals("1d swaps the whip's guide pair for its traded one",
			guideOnly.valueNowCovered() - WHIP_GUIDE_NOW + WHIP_LIVE_MID, d1.valueNowCovered());
		assertEquals(guideOnly.valueThen() - (WHIP_GUIDE_NOW - 1L) + WHIP_TRADED_THEN, d1.valueThen());
		assertEquals("the same stacks as before: a series is not a coverage change",
			guideOnly.itemsCovered(), d1.itemsCovered());

		assertEquals("7d has no bucket, so both its ends are the guide's - to the gp, as before the feed landed",
			guideOnly7d, summary.move(MovementWindow.D7));
	}

	/** T8/U4: the bridge's {@code state.live}, and its seven keys in T8's and U4's own order. */
	@Test
	public void theStatusCarriesWhatTheTradedFeedsDelivered()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final PriceService.Status.LiveStatus live = service.currentStatus().live();
		assertEquals(clock.get(), live.fetchedAtMillis());
		assertEquals(2, live.latestItems());
		assertEquals(1, live.liveRows());
		assertEquals("every other priced stack", service.currentStatus().portfolio().itemsPriced() - 1,
			live.guideRows());
		assertEquals(0, live.alchRows());
		assertEquals(Arrays.asList("fetchedAt", "latestItems", "liveRows", "guideRows", "alchRows", "liveDay",
			"windowDays"), new ArrayList<>(live.asMap().keySet()));
	}

	/**
	 * U4: {@code state.live} carries the live CALENDAR - the snapshot's own UTC day, and the day each window's
	 * bucket really holds, null for a window that has none. Every window is a key, so a script never has to ask
	 * whether one exists.
	 */
	@Test
	public void theStatusCarriesTheLiveDayAndTheDayEachWindowUsed()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final PriceService.Status.LiveStatus live = service.currentStatus().live();
		assertEquals("the UTC date of the /latest snapshot, not of the guide's anchor", SEP_8, live.liveDay());
		assertEquals(SEP_7, live.windowDays().get(MovementWindow.D1));
		assertNull("7d has no bucket yet", live.windowDays().get(MovementWindow.D7));
		assertEquals("every window is a key either way", MovementWindow.values().length, live.windowDays().size());

		final Map<String, Object> map = live.asMap();
		assertEquals("2026-09-08", map.get("liveDay"));
		final Map<?, ?> days = (Map<?, ?>) map.get("windowDays");
		assertEquals("keyed by the chip's own label", "2026-09-07", days.get("1d"));
		assertNull(days.get("7d"));
		assertEquals(Arrays.asList("1d", "7d", "30d", "90d", "180d"), new ArrayList<>(days.keySet()));
	}

	/** U4: with the switch off there is no calendar to report, and OFF says so rather than guessing a day. */
	@Test
	public void theSwitchOffReportsNoLiveCalendarAtAll()
	{
		assertNull(PriceService.Status.LiveStatus.OFF.liveDay());
		assertNull(PriceService.Status.LiveStatus.OFF.windowDays().get(MovementWindow.D1));
		assertNull(PriceService.Status.LiveStatus.OFF.asMap().get("liveDay"));
	}

	// ---------------------------------------------------------------- T7: failure and staleness

	/** T7: a stored snapshot is used while it is less than six hours old, even after a fetch fails. */
	@Test
	public void aStoredSnapshotIsUsedWhileItIsUnderSixHoursOld()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		clock.addAndGet(PriceService.LIVE_LATEST_MAX_AGE_MS - MINUTE);
		fireTick();
		failLatest();

		assertTrue("still live", rowFor(service.currentRows(), WHIP).isLive());
		assertFalse("and nothing to tell the user about", service.currentStatus().degraded());
	}

	/** T7: older than six hours, every row is GUIDE and the card says why. */
	@Test
	public void aSnapshotOlderThanSixHoursTakesEveryRowBackToTheGuide()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		clock.addAndGet(PriceService.LIVE_LATEST_MAX_AGE_MS + MINUTE);
		fireTick();
		failLatest();

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertSame(MovementRow.PriceSource.GUIDE, whip.source());
		assertEquals("the guide price, to the gp", Long.valueOf(WHIP_GUIDE_NOW), whip.unitPrice());
		assertNull("and no live state at all", whip.liveFacts());
		assertTrue(service.currentStatus().degraded());
		assertEquals(PriceService.LIVE_UNAVAILABLE, service.currentStatus().degradedReason());
		assertEquals("live prices unavailable - showing guide prices", PriceService.LIVE_UNAVAILABLE);
	}

	/** A guide problem is the graver one and keeps the line: without the guide there is no movement at all. */
	@Test
	public void aGuideFailureOutranksTheLiveOneOnTheOneLineThereIs()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		clock.addAndGet(PriceService.HISTORY_MAX_AGE_MS + MINUTE);
		fireTick();
		indexFutures.get(indexFutures.size() - 1).completeExceptionally(new WikiPriceException("down"));
		failLatest();

		assertTrue(service.currentStatus().degraded());
		assertTrue(service.currentStatus().degradedReason(),
			service.currentStatus().degradedReason().contains("wiki history fetch failed"));
	}

	/** Nothing is said before the first attempt: silence is not a failure. */
	@Test
	public void aFeedThatHasNotAnsweredYetIsNotADegradedState()
	{
		liveService();
		warmUpBody();

		assertFalse(service.currentStatus().degraded());
		assertSame(MovementRow.PriceSource.GUIDE, rowFor(service.currentRows(), WHIP).source());
		assertNull("and no row claims a refusal it could not have made",
			rowFor(service.currentRows(), WHIP).liveFacts());
	}

	// ================================================================ addendum U: the live calendar (U1, U2)

	/**
	 * U1: the live day is the UTC date of the snapshot's own stamp, and the JVM's zone has no say in it. A stamp
	 * at 23:59 UTC and one two minutes later are two different days; read in Kiritimati (+14) or Midway (-11) they
	 * would be different days again, and both would be wrong for a wiki that cuts its daily buckets on UTC days.
	 */
	@Test
	public void theLiveDayIsTheSnapshotsUtcDateWhateverTheJvmZoneIs()
	{
		final long lateOnTheEighth = utcMillis(SEP_8, 23, 59, 0);
		final long justAfterMidnight = utcMillis(SEP_9, 0, 1, 0);
		final TimeZone was = TimeZone.getDefault();
		try
		{
			for (final String zone : new String[]{"UTC", "Pacific/Kiritimati", "Pacific/Midway"})
			{
				TimeZone.setDefault(TimeZone.getTimeZone(zone));
				assertEquals(zone, SEP_8, PriceService.utcDay(lateOnTheEighth));
				assertEquals(zone, SEP_9, PriceService.utcDay(justAfterMidnight));
			}
		}
		finally
		{
			TimeZone.setDefault(was);
		}
		assertNull("no snapshot is not 1970", PriceService.utcDay(0L));
	}

	/** U1: the wanted day is {@code liveDay - N} for every window, by calendar arithmetic. */
	@Test
	public void theWantedTradedDayIsCountedBackFromTheLiveDay()
	{
		assertEquals(SEP_7, PriceService.wantedTradedDay(SEP_8, MovementWindow.D1));
		assertEquals(SEP_1, PriceService.wantedTradedDay(SEP_8, MovementWindow.D7));
		assertEquals(AUG_9, PriceService.wantedTradedDay(SEP_8, MovementWindow.D30));
		assertEquals("dates, not 90 x 86,400 seconds", JUN_10, PriceService.wantedTradedDay(SEP_8, MovementWindow.D90));
		assertEquals(MAR_12, PriceService.wantedTradedDay(SEP_8, MovementWindow.D180));
		assertNull("no snapshot, nothing to count back from", PriceService.wantedTradedDay(null, MovementWindow.D1));
		assertNull(PriceService.wantedTradedDay(SEP_8, null));
	}

	/** U2: a bucket serves the day it records and the one day before it, and no other - stored or fetched. */
	@Test
	public void aBucketServesOnlyTheDayItRecordsOrTheOneBeforeIt()
	{
		assertTrue("the wanted day itself", PriceService.tradedDayUsable(SEP_7, SEP_7));
		assertTrue("and U2's one day further back", PriceService.tradedDayUsable(SEP_7, SEP_6));
		assertFalse("two days back is the +30 % finding of live look 5", PriceService.tradedDayUsable(SEP_7, SEP_5));
		assertFalse("a day the wiki has not closed is not a baseline either",
			PriceService.tradedDayUsable(SEP_7, SEP_8));
		assertFalse(PriceService.tradedDayUsable(null, SEP_7));
		assertFalse(PriceService.tradedDayUsable(SEP_7, null));
	}

	/**
	 * U2: a wanted day the wiki has not closed answers EMPTY - not an error - so the window asks once for the day
	 * before it, uses that, and records the day it really holds. Everything downstream then names 06 Sep: the row,
	 * the status and the file on disk.
	 */
	@Test
	public void aDayTheWikiHasNotClosedFallsBackOneDayAndRecordsTheDayItHolds()
	{
		warmUpLive();
		final int before = dayRequests.size();

		answerDay(SEP_7, Collections.<Integer, TradedPriceClient.Bucket>emptyMap());

		assertEquals("one more request, and one only", before + 1, dayRequests.size());
		assertEquals(SEP_6, dayRequests.get(dayRequests.size() - 1));

		answerDay(SEP_6, tradedSep7());

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertTrue("the row is live off the day that did close", whip.isLive());
		assertEquals(Long.valueOf(WHIP_TRADED_THEN), whip.thenPrice());
		assertEquals("and says which day that was", SEP_6, whip.windowDay(MovementWindow.D1));
		assertEquals(SEP_6, service.currentStatus().live().windowDays().get(MovementWindow.D1));
		verify(store).saveTradedDay(eq(MovementWindow.D1), eq(SEP_6), anyMap(), anyLong());
		verify(store, never()).saveTradedDay(eq(MovementWindow.D1), eq(SEP_7), anyMap(), anyLong());
	}

	/** U2: an empty answer to the fallback stops there - that window compares the guide's two ends (T4). */
	@Test
	public void aFallbackThatIsAlsoEmptyLeavesTheWindowOnTheGuide()
	{
		warmUpLive();
		answerDay(SEP_7, Collections.<Integer, TradedPriceClient.Bucket>emptyMap());
		final int afterTheFallback = dayRequests.size();

		answerDay(SEP_6, Collections.<Integer, TradedPriceClient.Bucket>emptyMap());

		assertEquals("the walk back is one step, never two", afterTheFallback, dayRequests.size());
		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertSame(MovementRow.PriceSource.GUIDE, whip.source());
		assertEquals("the guide price, to the gp", Long.valueOf(WHIP_GUIDE_NOW), whip.unitPrice());
		assertEquals(PriceService.LIVE_NO_DATA, whip.liveFacts().reason());
		assertNull(service.currentStatus().live().windowDays().get(MovementWindow.D1));
	}

	/** U1: a stored bucket for the day this window wants is used as it stands - no request at all. */
	@Test
	public void aStoredBucketForTheWantedDayIsNotRefetched()
	{
		when(store.loadTradedDay(MovementWindow.D1))
			.thenReturn(new PriceStore.TradedDay(SEP_7, tradedSep7(), T0 - HOUR));

		warmUpLive();

		assertFalse("07 Sep is already in hand", dayRequests.contains(SEP_7));
		assertEquals("and the other four are still asked for", 4, dayRequests.size());
		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertTrue(whip.isLive());
		assertEquals(SEP_7, whip.windowDay(MovementWindow.D1));
	}

	/**
	 * U1/U2: a stored bucket for any OTHER day is replaced rather than used - which is addendum U's finding in the
	 * form it reaches a new session in: yesterday's file, one more day stale than it looks, would otherwise be
	 * "yesterday's traded average" for today's 1d row.
	 */
	@Test
	public void aStoredBucketForAnotherDayIsRefetchedAndNeverUsed()
	{
		when(store.loadTradedDay(MovementWindow.D1))
			.thenReturn(new PriceStore.TradedDay(SEP_5, tradedSep7(), T0 - DAY));

		warmUpLive();

		assertTrue("the wanted day is asked for", dayRequests.contains(SEP_7));
		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertFalse("and a bucket is never used for a day it does not name", whip.isLive());
		assertEquals(PriceService.LIVE_NO_DATA, whip.liveFacts().reason());
		assertNull(service.currentStatus().live().windowDays().get(MovementWindow.D1));

		answerDay(SEP_7, tradedSep7());

		assertTrue("and the day that arrives replaces it", rowFor(service.currentRows(), WHIP).isLive());
		assertEquals(SEP_7, service.currentStatus().live().windowDays().get(MovementWindow.D1));
	}

	/** U1: "once per UTC day, and at start-up after a day change" - when the live day moves, so does every window. */
	@Test
	public void theBucketsAreRefetchedWhenTheLiveDayMovesOn()
	{
		warmUpLive();
		// Settle every window and the warm-up tick's own /latest request, so nothing below is merely "in flight".
		for (final LocalDate day : new ArrayList<>(dayRequests))
		{
			answerDay(day, tradedSep7());
		}
		answerLatest(latestQuotes());
		final int before = dayRequests.size();

		// 2026-09-09T00:20Z: past UTC midnight, so the whole calendar steps on.
		clock.addAndGet(4L * HOUR);
		fireTick();
		answerLatest(latestQuotes());

		assertEquals("one per window again", before + MovementWindow.values().length, dayRequests.size());
		assertEquals("1d now wants 08 Sep", SEP_8, dayRequests.get(before));
		assertEquals(SEP_9, service.currentStatus().live().liveDay());
		assertEquals("and until that lands the row compares against the day it really holds", SEP_7,
			service.currentStatus().live().windowDays().get(MovementWindow.D1));
	}

	/**
	 * S1/U3, per row: a live window stamps the traded bucket's own day, and a window that fell back to the guide
	 * stamps the guide baseline's - so the day on a tooltip line always belongs to the figure beside it.
	 */
	@Test
	public void aRowStampsTheTradedDayItUsedAndTheGuideDayWhereItFellBack()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final MovementRow whip = rowFor(service.currentRows(), WHIP);
		assertEquals("1d is live off 07 Sep's bucket", SEP_7, whip.windowDay(MovementWindow.D1));
		assertSame(MovementRow.PriceSource.GUIDE, whip.windowSource(MovementWindow.D7));
		assertEquals("7d fell back, so it stamps the guide baseline's own day",
			service.baseline(MovementWindow.D7).dataDay(), whip.windowDay(MovementWindow.D7));
	}

	/** U3: a guide row records no day of its own and keeps the header's, exactly as before addendum T. */
	@Test
	public void aGuideRowRecordsNoDayOfItsOwn()
	{
		warmUpLive();
		answerDay(SEP_7, tradedSep7());

		final MovementRow hat = rowFor(service.currentRows(), GREEN_HAT);
		assertSame(MovementRow.PriceSource.GUIDE, hat.source());
		assertNull("nothing to stamp - the panel keeps printing the status's baseline day",
			hat.windowDay(MovementWindow.D1));
		assertEquals("and its figures are the guide's, untouched by any of this", Long.valueOf(1_124L),
			hat.thenPrice());
	}

	// ---------------------------------------------------------------- the switch OFF is byte-identical

	/**
	 * The rule the whole wave rests on (addendum T's contract): with {@code livePrices} off, the engine produces
	 * exactly what it produced before addendum T - the same rows, field for field, and the same bank value line -
	 * and touches nothing traded. Run through a service that HAS the traded client and through one that has none,
	 * so the comparison covers both the switch and the wiring.
	 */
	@Test
	public void theSwitchOffIsByteIdenticalToTheGuideOnlyEngine()
	{
		liveService();
		service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		warmUpBody();
		final List<MovementRow> withClient = new ArrayList<>(service.currentRows());
		final PriceService.Status statusWithClient = service.currentStatus();
		verifyNoInteractions(traded);

		resetFixture();
		service = new PriceService(wiki, store, itemManager, clientThread, scheduler, clock::get, edt);
		listen();
		// The same switch on both, so the only thing the comparison can be measuring is the wiring.
		service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		warmUpBody();

		assertEquals("the same rows, field for field", service.currentRows(), withClient);
		assertEquals("and the same bank value line", service.currentStatus().portfolio(),
			statusWithClient.portfolio());
		assertEquals("and the same status", service.currentStatus(), statusWithClient);
		assertSame("with no live state to echo", PriceService.Status.LiveStatus.OFF, statusWithClient.live());
		for (final MovementRow row : withClient)
		{
			assertNull(row.name() + " must carry no live state at all", row.liveFacts());
			assertSame(row.source(), row.windowSource(MovementWindow.D1));
		}
	}

	// ---------------------------------------------------------------- addendum Y: the inventory and worn gear

	/**
	 * What the player is carrying in the Y tests. "Item 3" stands in for a stack held in all THREE places - the
	 * bank's fixture holds three of it, so one more in the inventory and one worn make the addendum's own example,
	 * "3 in bank, 1 in inventory, 1 worn" - and the unbaited Karambwan vessel (3157, named by the mapping and
	 * priced at a flat 3,235 on every day) is the WORN-ONLY stack: a row the bank has never seen.
	 *
	 * @param carriedGp the coins and platinum tokens in hand
	 */
	private static BankReader.Carried carried(final long carriedGp)
	{
		return new BankReader.Carried(
			Arrays.asList(new BankItem(item(3), 1, "Item 3", true)),
			Arrays.asList(new BankItem(item(3), 1, "Item 3", true), new BankItem(VESSEL, 1, "Karambwan vessel", false)),
			carriedGp, T0);
	}

	/** The captured bank with that carried half hung on it, exactly as the plugin's bank-event pass publishes it. */
	private static BankSnapshot bankWithCarried(final long capturedAt, final long carriedGp)
	{
		return bank(capturedAt).withCarried(carried(carriedGp));
	}

	/**
	 * Y3, the headline: one row per item with the quantities ADDED and the split beside them, a worn-only stack
	 * getting a row of its own, and both counted in the bank value and in the stack count.
	 */
	@Test
	public void theInventoryAndWornStacksMergeIntoTheBanksRows()
	{
		warmUpWith(bankWithCarried(T0, 0L));

		final MovementRow three = rowFor(lastRows(), item(3));
		assertNotNull(three);
		assertEquals("3 in the bank, 1 in the inventory, 1 worn", 5, three.quantity());
		assertEquals(3, three.bankQuantity());
		assertEquals(1, three.inventoryQuantity());
		assertEquals(1, three.wornQuantity());
		assertEquals("one row, not three", 1, countRowsFor(lastRows(), item(3)));

		final MovementRow vessel = rowFor(lastRows(), VESSEL);
		assertNotNull("a worn item the bank has never held is a row of its own", vessel);
		assertEquals(1, vessel.quantity());
		assertEquals(0, vessel.bankQuantity());
		assertEquals(1, vessel.wornQuantity());
		assertEquals("and it is priced like any other guide row", Long.valueOf(3_235L), vessel.unitPrice());
		assertEquals("with a baseline, because the projection names what you wear too",
			Long.valueOf(3_235L), vessel.thenPrice());

		final long unit = three.unitPrice();
		final PortfolioSummary p = lastStatus().portfolio();
		assertEquals("the bank's own 6,290,824, two more of Item 3 and the vessel",
			6_290_824L + 2L * unit + 3_235L, p.valueNow());
		assertEquals("the merged stacks are what is counted", 32, p.itemsTotal());
		assertEquals(32, lastStatus().bankItems());
		assertEquals("the two extra Item 3 gain a gp a day each", 285L, p.move(MovementWindow.D1).deltaGp());
	}

	/** Y3: a stack wholly inside the bank still knows it, so the panel can leave its hover line off. */
	@Test
	public void aStackHeldOnlyInTheBankSaysSo()
	{
		warmUpWith(bankWithCarried(T0, 0L));

		final MovementRow whip = rowFor(lastRows(), WHIP);
		assertEquals(1, whip.bankQuantity());
		assertEquals(0, whip.inventoryQuantity());
		assertEquals(0, whip.wornQuantity());
		assertTrue(whip.allInBank());
		assertTrue("it was computed with the switch on, so it does know", whip.split());
	}

	/** Y3: the coins in your pocket follow the COINS switch, exactly as the bank's do (Q4). */
	@Test
	public void theCarriedCoinsFollowTheCashSwitch()
	{
		warmUpWith(bankWithCarried(T0, 791_078L));

		assertEquals(791_078L, lastStatus().portfolio().currencyGp());

		service.setOptions(ViewOptions.DEFAULT.withCountCash(false));
		assertEquals("cash off takes the carried coins out too", 0L, lastStatus().portfolio().currencyGp());

		service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		assertEquals("and so does the inventory switch, with the cash switch back on",
			0L, lastStatus().portfolio().currencyGp());
	}

	/** And the bank's own cash and the carried cash add up when both switches are on. */
	@Test
	public void theCarriedCoinsAddToTheBanksOwn()
	{
		final BankSnapshot snapshot = bankWithCurrency(T0, CURRENCY).withCarried(carried(791_078L));
		warmUpWith(snapshot);

		assertEquals(CURRENCY + 791_078L, lastStatus().portfolio().currencyGp());
		assertEquals(6_290_824L + 2L * rowFor(lastRows(), item(3)).unitPrice() + 3_235L + CURRENCY + 791_078L,
			lastStatus().portfolio().valueNow());
	}

	/** Y3: a worn untradeable follows the untradeables switch exactly as a banked one does (Q5). */
	@Test
	public void aWornUntradeableFollowsTheUntradeablesSwitch()
	{
		final BankSnapshot snapshot = bank(T0).withCarried(new BankReader.Carried(Collections.<BankItem>emptyList(),
			Arrays.asList(new BankItem(DRAMEN, 1, "Dramen staff", false, true, 1_500)), 0L, T0));
		service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		warmUpWith(snapshot);

		final MovementRow dramen = rowFor(lastRows(), DRAMEN);
		assertNotNull("worn, untradeable and counted", dramen);
		assertEquals(MovementRow.PriceSource.ALCH, dramen.source());
		assertEquals(Long.valueOf(1_500L), dramen.unitPrice());
		assertEquals(1, dramen.wornQuantity());
		assertEquals(0, dramen.bankQuantity());

		service.setOptions(ViewOptions.DEFAULT);

		assertNull("the switch is the switch, wherever the stack is", rowFor(lastRows(), DRAMEN));
	}

	/**
	 * The pin for Y3's "off is exactly today". The SAME captured bank - one that now carries an inventory, worn
	 * gear and pocket money, because the plugin reads them whatever the switch says - must publish the identical
	 * rows, counts and bank value as a bank that never held any of it, down to the last field of the summary.
	 */
	@Test
	public void withTheInventorySwitchOffTheServicePublishesExactlyWhatItPublishedBeforeY()
	{
		service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		warmUpWith(bankWithCarried(T0, 791_078L));
		final List<MovementRow> withCarried = lastRows();
		final PriceService.Status statusWithCarried = lastStatus();

		service.setBank(bank(T0 + 1L));

		assertEquals("the same rows, in the same order", withCarried, lastRows());
		assertEquals(statusWithCarried.portfolio(), lastStatus().portfolio());
		assertEquals(statusWithCarried.totalRows(), lastStatus().totalRows());
		assertEquals("and the same stack count", statusWithCarried.bankItems(), lastStatus().bankItems());
		assertEquals(31, statusWithCarried.bankItems());
		assertEquals(6_290_824L, statusWithCarried.portfolio().valueNow());
		assertEquals("the carried coins are not in it either", 0L, statusWithCarried.portfolio().currencyGp());
		assertNull("no worn row was drawn", rowFor(withCarried, VESSEL));
		assertEquals("and the merged quantity is not there", 3, rowFor(withCarried, item(3)).quantity());
		for (final MovementRow row : withCarried)
		{
			assertFalse(row.name() + " must carry no split at all", row.split());
		}
	}

	/** Flipping the switch recomputes on the spot: nothing is fetched, and no bank visit is needed. */
	@Test
	public void flippingTheInventorySwitchRepublishesWithoutFetchingAnything()
	{
		service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		warmUpWith(bankWithCarried(T0, 0L));
		final int requests = tableRequests.size();
		assertNull(rowFor(lastRows(), VESSEL));

		service.setOptions(ViewOptions.DEFAULT);

		assertNotNull(rowFor(lastRows(), VESSEL));
		assertEquals(5, rowFor(lastRows(), item(3)).quantity());
		assertEquals("no table was asked for", requests, tableRequests.size());
		assertEquals(1, indexFutures.size());

		service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));

		assertNull("and back again", rowFor(lastRows(), VESSEL));
		assertEquals(6_290_824L, lastStatus().portfolio().valueNow());
	}

	/**
	 * Y2: Refresh re-reads what the player carries, BEFORE it re-checks the prices - and nothing else in the
	 * service ever runs the hook. The reader records how many index requests had gone out when it ran, which is
	 * how the ORDER is pinned: still one, the warm-up's, with the refresh's own about to leave.
	 */
	@Test
	public void refreshRunsTheCarriedReaderBeforeTheIndexAndNothingElseDoes()
	{
		final List<Integer> indexRequestsWhenRead = new ArrayList<>();
		service.setCarriedReader(() -> indexRequestsWhenRead.add(indexFutures.size()));
		warmUp();
		fireTick();

		assertEquals("not a tick, not a bank change, not a recompute", 0, indexRequestsWhenRead.size());
		assertEquals(1, indexFutures.size());

		service.refreshNow();

		assertEquals(1, indexRequestsWhenRead.size());
		assertEquals("it ran before the refresh's own index request", Integer.valueOf(1), indexRequestsWhenRead.get(0));
		assertEquals(2, indexFutures.size());

		clock.addAndGet(SECOND);
		service.refreshNow();
		assertEquals("the cooldown refuses the fetch; what you carry has no cooldown", 2, indexRequestsWhenRead.size());

		service.setCarriedReader(null);
		clock.addAndGet(2L * MINUTE);
		service.refreshNow();
		assertEquals("unregistered, never called again", 2, indexRequestsWhenRead.size());
	}

	/** A stopped service holds no hook: a Refresh that arrives late must not reach the client. */
	@Test
	public void stoppingTheServiceDropsTheCarriedReader()
	{
		final List<Integer> reads = new ArrayList<>();
		service.setCarriedReader(() -> reads.add(1));
		warmUp();

		service.stop();
		service.refreshNow();

		assertTrue(reads.isEmpty());
	}

	// ---------------------------------------------------------------- the T fixture's own helpers

	/** {@link PriceService#liveRefusal}'s clock: T0 in unix seconds. */
	private static final long NOW_SECONDS = T0 / 1000L;

	/** A quote whose two sides both traded a moment ago - so only the side under test can refuse it. */
	private static TradedPriceClient.Quote fresh(final Long buy, final Long sell)
	{
		return new TradedPriceClient.Quote(buy, NOW_SECONDS - 60L, sell, sell == null ? 0L : NOW_SECONDS - 60L);
	}

	/**
	 * Yesterday's bucket for a quote that the two checks addendum V added must not interfere with: {@code volume}
	 * units traded at the quote's own mid, on ONE side of the book - so there is no gap for check 4 to measure and
	 * the day's average IS the live price, which check 5 measures as a jump of nothing. Every test of checks 1 to 3
	 * hands one of these over, so each still measures only the check it names.
	 *
	 * <p>One-sided rather than two averages on the same price because {@code weightedAverage} multiplies a price by
	 * a volume, and the ceiling test's price is {@code Long.MAX_VALUE / 200}: the one-sided rule answers that side
	 * exactly, with no arithmetic to fall out of.
	 */
	private static TradedPriceClient.Bucket yesterdayFor(final TradedPriceClient.Quote quote, final long volume)
	{
		final Long mid = quote == null ? null : quote.mid();
		return new TradedPriceClient.Bucket(mid == null || mid <= 0L ? Long.valueOf(1L) : mid, volume, null, 0L);
	}

	/** Rebuilds {@link #service} WITH the traded client and re-registers the listener. */
	private void liveService()
	{
		service = new PriceService(wiki, store, itemManager, clientThread, scheduler, clock::get, edt, traded);
		listen();
	}

	private void listen()
	{
		service.addListener((rows, status) ->
		{
			publishedRows.add(rows);
			publishedStatus.add(status);
		});
		publishedRows.clear();
		publishedStatus.clear();
	}

	/** Everything {@link #warmUp()} does, without asserting on a line the live tests may legitimately change. */
	private void warmUpBody()
	{
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		answerTables(); // the windows
	}

	/** A cold activation with the traded feeds on and the {@code /latest} snapshot delivered. */
	private void warmUpLive()
	{
		liveService();
		service.start();
		answerLatest(latestQuotes());
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		answerTables(); // the windows
	}

	/** Everything the two services share, so the second run starts where the first one did. */
	private void resetFixture()
	{
		indexFutures.clear();
		tableFutures.clear();
		tableRequests.clear();
		mappingFutures.clear();
		latestFutures.clear();
		dayFutures.clear();
		dayRequests.clear();
		publishedRows.clear();
		publishedStatus.clear();
		scheduler.timers.clear();
	}

	private void answerLatest(final Map<Integer, TradedPriceClient.Quote> quotes)
	{
		assertFalse("no /latest request is out", latestFutures.isEmpty());
		latestFutures.get(latestFutures.size() - 1).complete(quotes);
	}

	private void failLatest()
	{
		assertFalse("no /latest request is out", latestFutures.isEmpty());
		latestFutures.get(latestFutures.size() - 1).completeExceptionally(new WikiPriceException("traded feed down"));
	}

	/** Completes the request for one DAY, wherever it sits in the call order. */
	private void answerDay(final LocalDate day, final Map<Integer, TradedPriceClient.Bucket> buckets)
	{
		final int at = dayRequests.indexOf(day);
		assertTrue("no bucket request is out for " + day + " (asked: " + dayRequests + ")", at >= 0);
		dayFutures.get(at).complete(buckets);
	}

	/** {@link #answerDay} for the LAST request of that day - the retry case. */
	private void answerNextDayRequestFor(final LocalDate day, final Map<Integer, TradedPriceClient.Bucket> buckets)
	{
		final int at = dayRequests.lastIndexOf(day);
		assertTrue("no bucket request is out for " + day + " (asked: " + dayRequests + ")", at >= 0);
		dayFutures.get(at).complete(buckets);
	}

	private void failDay(final LocalDate day)
	{
		final int at = dayRequests.indexOf(day);
		assertTrue("no bucket request is out for " + day, at >= 0);
		dayFutures.get(at).completeExceptionally(new WikiPriceException("bucket down"));
	}

	/** A cold activation through to every baseline adopted, RuneLite and the wiki on 08 Sep. */
	private void warmUp()
	{
		service.start();
		service.setBank(bank(T0));
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		answerTables(); // the windows
		assertNull("warm-up ends with no problem on the line", lastStatus().problem());
	}

	/** {@link #warmUp()} against a bank of the test's own making. */
	private void warmUpWith(final BankSnapshot snapshot)
	{
		service.start();
		service.setBank(snapshot);
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setVisible(true);
		fireTick();
		answerIndex();
		answerTables(); // R0
		answerTables(); // the windows
	}

	/**
	 * Teaches the mapping and every revision body one extra name at a fixed price, for an id RuneLite would
	 * answer another item's price for (B001).
	 */
	private void nameTheRing()
	{
		final Map<Integer, String> names = new LinkedHashMap<>(mappingTable());
		names.put(RING_OF_WEALTH_5, RING_NAME);
		when(store.loadMapping()).thenReturn(storedMapping(names, T0 - DAY));
		for (final RevisionRef ref : HISTORY)
		{
			final LocalDate day = RevisionRef.dayOf(dataSecondsFor(ref));
			final Map<String, Long> table = new LinkedHashMap<>(prices(day));
			table.put(RING_NAME, RING_GP);
			bodyOverrides.put(ref.revId(), new GuideSnapshot(ref.revId(), ref.editSeconds(), dataSecondsFor(ref),
				clock.get(), table));
		}
	}

	/** {@link #teachEveryTable} for the crystal armour seed, the part of the addendum R examples. */
	private void nameTheSeed()
	{
		teachEveryTable(ARMOUR_SEED, SEED_NAME, SEED_NOW, SEED_THEN);
	}

	/**
	 * Teaches the mapping and every revision body one extra id at a price: {@code now} on the anchor day 08 Sep,
	 * {@code earlier} on every other day, and {@code now} in RuneLite's table too. {@link #nameTheRing()} does this
	 * for a bank STACK; a part of R2 is priced through exactly the same two sources, so a fixture for one has to
	 * teach both the same way.
	 */
	private void teachEveryTable(final int id, final String name, final long now, final long earlier)
	{
		final Map<Integer, String> names = new LinkedHashMap<>(mappingTable());
		names.put(id, name);
		when(store.loadMapping()).thenReturn(storedMapping(names, T0 - DAY));
		for (final RevisionRef ref : HISTORY)
		{
			final LocalDate day = RevisionRef.dayOf(dataSecondsFor(ref));
			final Map<String, Long> table = new LinkedHashMap<>(prices(day));
			table.put(name, day.equals(SEP_8) ? now : earlier);
			bodyOverrides.put(ref.revId(), new GuideSnapshot(ref.revId(), ref.editSeconds(), dataSecondsFor(ref),
				clock.get(), table));
		}
		runelite.put(id, (int) now);
	}

	/** The one-shot task the service scheduled for exactly this delay; fails the test when there is none. */
	private Timer oneShotWithDelay(final long delayMs)
	{
		for (final Timer timer : scheduler.timers)
		{
			if (timer.periodMs == 0L && timer.initialDelayMs == delayMs && !timer.future.isCancelled())
			{
				return timer;
			}
		}
		throw new AssertionError("no live one-shot task scheduled " + delayMs + " ms out");
	}

	private void fireTick()
	{
		scheduler.timerWithPeriod(PriceService.TICK_MS).fire();
	}

	private void fireAllTimers()
	{
		for (final Timer timer : scheduler.timers)
		{
			timer.fire();
		}
	}

	/** Completes the newest index request with {@link #history}. */
	private void answerIndex()
	{
		assertFalse("no index request is out", indexFutures.isEmpty());
		indexFutures.get(indexFutures.size() - 1).complete(new ArrayList<>(history));
	}

	/**
	 * Completes the newest body request the way the wiki would: one generated table per requested revision, an
	 * override where a test planted one, nothing for an omitted id, and a failure when nothing survives (the
	 * client's own rule).
	 */
	private void answerTables()
	{
		assertFalse("no body request is out", tableFutures.isEmpty());
		final CompletableFuture<Map<Long, GuideSnapshot>> future = tableFutures.get(tableFutures.size() - 1);
		final List<Long> requested = tableRequests.get(tableRequests.size() - 1);
		final Map<Long, GuideSnapshot> tables = new LinkedHashMap<>();
		for (final Long revId : requested)
		{
			if (omitBodies.contains(revId))
			{
				continue;
			}
			final GuideSnapshot override = bodyOverrides.get(revId);
			if (override != null)
			{
				tables.put(revId, override);
				continue;
			}
			final RevisionRef ref = refById(revId);
			assertNotNull("the test index has no revision " + revId, ref);
			tables.put(revId, snapshot(ref, clock.get()));
		}
		if (tables.isEmpty())
		{
			future.completeExceptionally(new WikiPriceException("the wiki returned no usable guide tables"));
		}
		else
		{
			future.complete(tables);
		}
	}

	private RevisionRef refById(final long revId)
	{
		for (final RevisionRef ref : history)
		{
			if (ref.revId() == revId)
			{
				return ref;
			}
		}
		return null;
	}

	/** RuneLite's guide table as of one Jagex day, for every mapped bank item; the box and the vessel are extra. */
	private void runeliteOn(final LocalDate day)
	{
		runelite.clear();
		final Map<String, Long> prices = prices(day);
		for (final Map.Entry<Integer, String> entry : mappingTable().entrySet())
		{
			final Long gp = prices.get(entry.getValue());
			if (gp != null)
			{
				runelite.put(entry.getKey(), gp.intValue());
			}
		}
		runelite.put(BOX, 100);
		runelite.put(VESSEL_BAITED, 3_388);
	}

	private int countUnder(final long gp)
	{
		int count = 0;
		for (final BankItem item : bank(T0).items)
		{
			final int price = runelite.getOrDefault(item.id, 0);
			if (price > 0 && price < gp)
			{
				count++;
			}
		}
		return count;
	}

	private int compositionReads()
	{
		int reads = 0;
		for (final Invocation invocation : mockingDetails(itemManager).getInvocations())
		{
			if ("getItemComposition".equals(invocation.getMethod().getName()))
			{
				reads++;
			}
		}
		return reads;
	}

	/** The guide table Jagex stamped on one day: every item differs from every other day's figure. */
	private static Map<String, Long> prices(final LocalDate day)
	{
		final long di = dayIndex(day);
		final Map<String, Long> table = new LinkedHashMap<>();
		for (int n = 1; n <= ITEMS; n++)
		{
			table.put("Item " + n, 1_000L * n + di);
		}
		table.put("Green hat", hatPrice(day));
		table.put("Abyssal whip", 800_000L + di);
		table.put("Shark", 1_000L);
		table.put("Varrock teleport (tablet)", 290L + di);
		table.put("Karambwan vessel", 3_235L);
		table.put("3rd Age axe", GuideSnapshot.NO_PRICE);
		return table;
	}

	/** The calibration doc's real Green hat figures: 1,124 on 07 Sep, 1,086 on 08 Sep (-38 gp, the GE site's -3 %). */
	private static long hatPrice(final LocalDate day)
	{
		if (day.equals(SEP_8) || day.equals(SEP_6))
		{
			return 1_086L;
		}
		if (day.equals(SEP_7))
		{
			return 1_124L;
		}
		return 1_100L;
	}

	private static int dayIndex(final LocalDate day)
	{
		return (int) ChronoUnit.DAYS.between(JAN_1, day);
	}

	/** The table's own day marker: ten minutes before a bot run; the previous day's marker for a human edit (L-E). */
	private static long dataSecondsFor(final RevisionRef ref)
	{
		if (ref.isBot())
		{
			return ref.editSeconds() - 600L;
		}
		return utcSeconds(ref.editDay().minusDays(1), 7, 21, 23);
	}

	private static GuideSnapshot snapshot(final RevisionRef ref, final long fetchedAt)
	{
		return new GuideSnapshot(ref.revId(), ref.editSeconds(), dataSecondsFor(ref), fetchedAt,
			prices(RevisionRef.dayOf(dataSecondsFor(ref))));
	}

	/** A body under {@code ref}'s id that carries another day's table - the stale shapes of L5. */
	private static GuideSnapshot snapshotWithDay(final RevisionRef ref, final LocalDate day, final long fetchedAt)
	{
		return new GuideSnapshot(ref.revId(), ref.editSeconds(), utcSeconds(day, 19, 45, 22), fetchedAt, prices(day));
	}

	/** A {@code baseline-<window>.json} as the store reads it back: the mapping projected onto one revision. */
	private static PriceMap diskBaseline(final RevisionRef ref, final long fetchedAt)
	{
		return PriceService.project(snapshot(ref, fetchedAt), PriceService.foldedNamesOf(mappingTable()), PriceService.ownersOf(mappingTable()),
			Collections.emptyList());
	}

	/** The {@code /mapping} table: 3159 is deliberately absent so the L8 b refusal has something to refuse. */
	private static Map<Integer, String> mappingTable()
	{
		final Map<Integer, String> names = new LinkedHashMap<>();
		for (int n = 1; n <= ITEMS; n++)
		{
			names.put(item(n), "Item " + n);
		}
		names.put(GREEN_HAT, "Green hat");
		names.put(WHIP, "Abyssal whip");
		names.put(SHARK, "Shark");
		names.put(TABLET, "Varrock teleport (tablet)");
		names.put(VESSEL, "Karambwan vessel");
		return names;
	}

	/**
	 * What {@code PriceStore.loadMapping()} answers: the names AND the moment the file says they were fetched,
	 * from the one parse that read them. The stamp is what the weekly staleness rule compares (K5).
	 */
	private static PriceStore.Stamped<Map<Integer, String>> storedMapping(final Map<Integer, String> names,
		final long fetchedAtMillis)
	{
		return new PriceStore.Stamped<>(names, fetchedAtMillis);
	}

	/** What {@code PriceStore.loadRevisionIndex()} answers: the revisions and their fetch stamp (L4). */
	private static PriceStore.Stamped<List<RevisionRef>> storedIndex(final List<RevisionRef> index,
		final long fetchedAtMillis)
	{
		return new PriceStore.Stamped<>(index, fetchedAtMillis);
	}

	private static int item(final int n)
	{
		return ITEM_BASE + n;
	}

	private static BankSnapshot bank(final long capturedAt)
	{
		return bank(capturedAt, ACCOUNT);
	}

	private static BankSnapshot bank(final long capturedAt, final long account)
	{
		final List<BankItem> items = new ArrayList<>();
		for (int n = 1; n <= ITEMS; n++)
		{
			items.add(new BankItem(item(n), n, "Item " + n, true));
		}
		items.add(new BankItem(GREEN_HAT, 1, "Green hat", false));
		items.add(new BankItem(WHIP, 1, "Abyssal whip", false));
		items.add(new BankItem(SHARK, 500, "Shark", true));
		items.add(new BankItem(BONES, 100, "Bones", true));
		items.add(new BankItem(TABLET, 20, "Varrock teleport", true));
		items.add(new BankItem(BOX, 3, "Mystery box", true));
		items.add(new BankItem(VESSEL_BAITED, 1, "Karambwan vessel", false));
		return new BankSnapshot(items, capturedAt, account, PROFILE);
	}

	/** The same bank with coins and platinum tokens in it (P1): {@code currencyGp} is a plain field on the snapshot. */
	private static BankSnapshot bankWithCurrency(final long capturedAt, final long currencyGp)
	{
		final BankSnapshot snapshot = bank(capturedAt);
		snapshot.currencyGp = currencyGp;
		return snapshot;
	}

	private static RevisionRef bot(final long revId, final LocalDate day, final int hour, final int minute, final int second)
	{
		return new RevisionRef(revId, utcSeconds(day, hour, minute, second), RevisionRef.BOT_USER, RevisionRef.BOT_COMMENT);
	}

	private static long utcSeconds(final LocalDate day, final int hour, final int minute, final int second)
	{
		return day.atTime(hour, minute, second).toEpochSecond(ZoneOffset.UTC);
	}

	private static long utcMillis(final LocalDate day, final int hour, final int minute, final int second)
	{
		return utcSeconds(day, hour, minute, second) * 1_000L;
	}

	private static ItemComposition composition(final String membersName)
	{
		final ItemComposition composition = mock(ItemComposition.class);
		when(composition.getMembersName()).thenReturn(membersName);
		return composition;
	}

	private PriceService.Status lastStatus()
	{
		assertFalse("nothing was published", publishedStatus.isEmpty());
		return publishedStatus.get(publishedStatus.size() - 1);
	}

	private List<MovementRow> lastRows()
	{
		assertFalse("nothing was published", publishedRows.isEmpty());
		return publishedRows.get(publishedRows.size() - 1);
	}

	private static MovementRow rowFor(final List<MovementRow> rows, final int id)
	{
		for (final MovementRow row : rows)
		{
			if (row.id() == id)
			{
				return row;
			}
		}
		return null;
	}

	/** How many rows an item has - one, always, and that is the point of the Y3 merge. */
	private static int countRowsFor(final List<MovementRow> rows, final int id)
	{
		int count = 0;
		for (final MovementRow row : rows)
		{
			if (row.id() == id)
			{
				count++;
			}
		}
		return count;
	}

	// ---------------------------------------------------------------- C27 seams

	/**
	 * The direct-run {@link ScheduledExecutorService} stub of C27: {@code execute} runs inline (or queues while
	 * {@link #deferred} is set, so a test can interleave a fetch completion with a disk load), and every
	 * periodic schedule is recorded as a {@link Timer} the test fires by hand. Only what {@link PriceService}
	 * uses is implemented; {@code schedule(Callable)} is not.
	 */
	static final class DirectScheduler extends AbstractExecutorService implements ScheduledExecutorService
	{
		final List<Runnable> queued = new ArrayList<>();
		final List<Timer> timers = new ArrayList<>();
		boolean deferred;
		private boolean shutdown;

		@Override
		public void execute(final Runnable command)
		{
			if (deferred)
			{
				queued.add(command);
			}
			else
			{
				command.run();
			}
		}

		/** Runs every queued task in order, including the ones a task queues while running. */
		void runPending()
		{
			while (!queued.isEmpty())
			{
				queued.remove(0).run();
			}
		}

		/** Runs one queued task out of order. */
		void runQueued(final int index)
		{
			queued.remove(index).run();
		}

		Timer timerWithPeriod(final long periodMs)
		{
			for (final Timer timer : timers)
			{
				if (timer.periodMs == periodMs && !timer.future.isCancelled())
				{
					return timer;
				}
			}
			throw new AssertionError("no live timer with period " + periodMs + " ms");
		}

		@Override
		public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit)
		{
			return add(command, unit.toMillis(delay), 0L);
		}

		@Override
		public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit)
		{
			throw new UnsupportedOperationException("PriceService never schedules a Callable");
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period, final TimeUnit unit)
		{
			return add(command, unit.toMillis(initialDelay), unit.toMillis(period));
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay, final TimeUnit unit)
		{
			return add(command, unit.toMillis(initialDelay), unit.toMillis(delay));
		}

		private ScheduledFuture<?> add(final Runnable command, final long initialDelayMs, final long periodMs)
		{
			final Timer timer = new Timer(command, initialDelayMs, periodMs);
			timers.add(timer);
			return timer.future;
		}

		@Override
		public void shutdown()
		{
			shutdown = true;
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			shutdown = true;
			final List<Runnable> rest = new ArrayList<>(queued);
			queued.clear();
			return rest;
		}

		@Override
		public boolean isShutdown()
		{
			return shutdown;
		}

		@Override
		public boolean isTerminated()
		{
			return shutdown;
		}

		@Override
		public boolean awaitTermination(final long timeout, final TimeUnit unit)
		{
			return true;
		}
	}

	/** One recorded periodic schedule. {@link #fire()} honours cancellation like the real executor would. */
	static final class Timer
	{
		final Runnable task;
		final long initialDelayMs;
		final long periodMs;
		final StubFuture future = new StubFuture();

		Timer(final Runnable task, final long initialDelayMs, final long periodMs)
		{
			this.task = task;
			this.initialDelayMs = initialDelayMs;
			this.periodMs = periodMs;
		}

		void fire()
		{
			if (!future.isCancelled())
			{
				task.run();
			}
		}
	}

	/** Just enough {@link ScheduledFuture} to be cancelled and inspected. */
	static final class StubFuture implements ScheduledFuture<Object>
	{
		private boolean cancelled;

		@Override
		public boolean cancel(final boolean mayInterruptIfRunning)
		{
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled()
		{
			return cancelled;
		}

		@Override
		public boolean isDone()
		{
			return cancelled;
		}

		@Override
		public Object get()
		{
			return null;
		}

		@Override
		public Object get(final long timeout, final TimeUnit unit)
		{
			return null;
		}

		@Override
		public long getDelay(final TimeUnit unit)
		{
			return 0L;
		}

		@Override
		public int compareTo(final Delayed other)
		{
			return 0;
		}
	}

	/**
	 * A {@link ClientThread} whose {@code invoke(Runnable)} runs inline, or queues while {@link #deferred} so a
	 * test can prove the guide-price read waits for the client thread and that a stale hop is dropped. The real
	 * class's {@code invoke} needs an injected {@code Client} to ask {@code isClientThread()}; overriding it
	 * sidesteps that without a mock.
	 */
	static final class FakeClientThread extends ClientThread
	{
		final Deque<Runnable> queued = new ArrayDeque<>();
		boolean deferred;
		int invocations;

		@Override
		public void invoke(final Runnable r)
		{
			invocations++;
			if (deferred)
			{
				queued.add(r);
			}
			else
			{
				r.run();
			}
		}

		void runAll()
		{
			Runnable next;
			while ((next = queued.poll()) != null)
			{
				next.run();
			}
		}
	}

	/** The {@code edt} consumer: inline by default, queued while {@link #deferred} to prove the hop. */
	static final class RecordingEdt implements Consumer<Runnable>
	{
		final Deque<Runnable> queue = new ArrayDeque<>();
		boolean deferred;

		@Override
		public void accept(final Runnable runnable)
		{
			if (deferred)
			{
				queue.add(runnable);
			}
			else
			{
				runnable.run();
			}
		}

		void drain()
		{
			Runnable next;
			while ((next = queue.poll()) != null)
			{
				next.run();
			}
		}
	}
}
