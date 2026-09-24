package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The developer-mode command bridge (contract C40), driven exactly as the Effect Lab's {@code /bpm} route
 * drives it: one command string in, one JSON string out, from a thread that is NOT the EDT.
 *
 * <p>Two fixtures. Most tests run the bridge over a MOCKED panel and service: their subject is dispatch,
 * parsing, the JSON and which panel method each verb presses. One test
 * ({@link #theFilterVerbsPressTheRealPanelsWidgetsSoTheConfigWriteHappens}) runs it over a REAL panel with a
 * recording {@link BankPriceMovementPanel.Prefs}, because C40's promise is that a verb goes "through the same
 * code path the widgets use, so prefs.save runs" - and only the real widgets can show that. The widgets
 * themselves are {@code BankPriceMovementPanelTest}'s business.
 *
 * <p>{@code shot} writes into a {@link TemporaryFolder} through the package-private constructor; nothing here
 * writes into {@code ~/.runelite}. Since addendum AD the bridge is handed a {@link PriceStore.Directory} rather
 * than a {@code File}, and what it answers is a {@link Filepath} SANDBOXED to that folder - so a shot cannot land
 * outside it even if a name tried to walk out - which is why {@link #shotDir()} is the one place this file names
 * the temporary folder and {@link #shotFile(JsonObject)} is the one place an answer's path is read back.
 */
public class BpmCommandsTest
{
	/**
	 * The three days of one 1 d publish (L3/L5): the newest revision's own day, the derived anchor D - here one
	 * day past it, RuneLite having already rolled over (L-D) - and the day the 1 d baseline table claims.
	 */
	private static final LocalDate R0_DAY = LocalDate.of(2026, 9, 7);
	private static final LocalDate ANCHOR_DAY = LocalDate.of(2026, 9, 8);
	private static final LocalDate THEN_DAY = LocalDate.of(2026, 9, 7);
	/**
	 * The live series' own day (addendum U, U1): the UTC date of the {@code /latest} snapshot, which is TODAY and
	 * is deliberately four days past {@link #ANCHOR_DAY} here - the guide's anchor lags the clock whenever Jagex
	 * has not published yet, and keeping the two apart in this fixture is what makes a test that confuses them
	 * fail.
	 */
	private static final LocalDate LIVE_DAY = LocalDate.of(2026, 9, 12);
	/** The revision that baseline was read from (calibration doc: revid 15333448 @ 2026-09-07T19:55:12Z). */
	private static final long REV_ID = 15_333_448L;
	/** The L3 agreement fraction that derived the anchor day: 0.94 of the bank matched the newest table. */
	private static final double AGREE = 0.94d;
	/** The n behind {@link #AGREE} - how many bank items could be compared with the newest table (L3). */
	private static final int AGREE_SAMPLES = 28;
	private static final long MAPPING_AT = 1_788_800_000_000L;
	/** When the revision index was last fetched (L4's six-hour rule), distinct from {@link #MAPPING_AT}. */
	private static final long INDEX_AT = 1_788_811_111_000L;
	/** The L7 header line with a baseline: a DAY, not a publication clock. */
	private static final String STATUS_TEXT = "Guide prices - 1d vs 07 Sep - Bank as of 11:00";
	/**
	 * The five keys of {@code state.panel.bank} (plan AS, 7.3), sorted so a failure prints both sides in the same
	 * order. They are binding names: the live run's {@code jq} lines in {@code docs/effect-lab.md} 3.3 read them.
	 */
	private static final Set<String> BANK_KEYS = Collections.unmodifiableSet(
		new TreeSet<>(Arrays.asList("open", "pending", "glow", "heldEvents", "reads")));

	@Rule
	public final TemporaryFolder shots = new TemporaryFolder();

	private final Gson gson = new Gson();
	/** What the bridge asks instead of {@code panel.isShowing()}; no window ever realises the panel. */
	private final AtomicBoolean showing = new AtomicBoolean(true);
	private BankPriceMovementPanel panel;
	private PriceService service;
	private BpmCommands dev;

	@Before
	public void setUp()
	{
		panel = mock(BankPriceMovementPanel.class);
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		when(panel.shownRows()).thenReturn(0);
		when(panel.totalRows()).thenReturn(0);
		// The real fields answer true for anything parseGp takes; a test that wants a refusal stubs the text.
		when(panel.applyMin(anyString())).thenReturn(true);
		when(panel.applyMax(anyString())).thenReturn(true);
		dev = new BpmCommands(panel, service, gson, account(), shotDir(), showing::get);
	}

	// ---------------------------------------------------------------- parsing and dispatch

	@Test
	public void everyAnswerIsJsonAndAnUnknownCommandIsRefused()
	{
		final JsonObject bad = send("nope");
		assertFalse("an unknown command is refused, not thrown", bad.get("ok").getAsBoolean());
		// The refusal names the commands, so an operator never has to read the source to recover.
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("unknown command 'nope'"));
		assertTrue(bad.get("error").getAsString().contains("window="));

		assertFalse(send("").get("ok").getAsBoolean());
		assertFalse(send("   ").get("ok").getAsBoolean());
		assertFalse("a null command is an answer too", send(null).get("ok").getAsBoolean());
	}

	/** The FIRST '=' splits, so a value may contain one of its own. */
	@Test
	public void theKeyIsWhateverComesBeforeTheFirstEquals()
	{
		final JsonObject bad = send("nope=a=b");
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("'nope'"));
		// And the key is case- and space-insensitive, because a URL query is typed by hand.
		assertTrue(ok(" WINDOW = 7d ").get("ok").getAsBoolean());
		verify(panel).selectWindow(MovementWindow.D7);
	}

	@Test
	public void aCommandSentFromAnotherThreadIsRunOnTheEdtAndAnswered()
	{
		assertFalse("the test drives the bridge the way the lab's HTTP thread does",
			SwingUtilities.isEventDispatchThread());
		final AtomicReference<Boolean> onEdt = new AtomicReference<>();
		when(panel.shownRows()).thenAnswer(invocation ->
		{
			onEdt.set(SwingUtilities.isEventDispatchThread());
			return 7;
		});
		assertEquals(7, ok("state").get("shownRows").getAsInt());
		assertEquals(Boolean.TRUE, onEdt.get());
	}

	/**
	 * A wedged Swing thread - a modal dialog somewhere in the client - is an ANSWER, not a wedged HTTP thread.
	 * The timeout is overridden here so the proof does not take {@link BpmCommands#EDT_TIMEOUT_MS}.
	 */
	@Test
	public void aSwingThreadThatNeverAnswersStillProducesAnAnswer() throws Exception
	{
		final BpmCommands quick = new BpmCommands(panel, service, gson, account(), shotDir(), showing::get)
		{
			@Override
			long edtTimeoutMs()
			{
				return 50;
			}
		};
		final CountDownLatch blocked = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		SwingUtilities.invokeLater(() ->
		{
			blocked.countDown();
			try
			{
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(blocked.await(10, TimeUnit.SECONDS));
		try
		{
			final JsonObject r = gson.fromJson(quick.apply("state"), JsonObject.class);
			assertFalse(r.get("ok").getAsBoolean());
			assertTrue(r.get("error").getAsString(), r.get("error").getAsString().startsWith("timed out"));
		}
		finally
		{
			release.countDown();
		}
	}

	// ---------------------------------------------------------------- state

	@Test
	public void stateEchoesTheFilterTheStatusTheCountsAndThePanelsOwnLine()
	{
		when(service.filter()).thenReturn(new RowFilter(100, 5000, SortMode.GP_MOVE, false, MovementWindow.D1));
		when(panel.shownRows()).thenReturn(250);
		when(panel.totalRows()).thenReturn(613);
		when(panel.describe()).thenReturn("{\"card\":\"LIST\",\"shown\":250,\"minInvalid\":false}");
		// Built before the stubbing: a mock made inside thenReturn(...) trips Mockito's unfinished-stubbing check.
		final PriceService.Status st0 = status();
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject s = ok("state");
		assertTrue(s.get("showing").getAsBoolean());
		final JsonObject filter = s.getAsJsonObject("filter");
		assertEquals("1d", filter.get("window").getAsString());
		assertEquals("gp change", filter.get("sort").getAsString());
		assertFalse(filter.get("descending").getAsBoolean());
		assertEquals(100, filter.get("gpMin").getAsLong());
		assertEquals(5000, filter.get("gpMax").getAsLong());
		assertEquals(250, s.get("shownRows").getAsInt());
		assertEquals(613, s.get("totalRows").getAsInt());
		assertNull("nothing synthetic has been pushed in", s.get("syntheticBank"));

		final JsonObject st = s.getAsJsonObject("status");
		assertEquals(STATUS_TEXT, st.get("text").getAsString());
		assertEquals(613, st.get("totalRows").getAsInt());
		assertEquals(700, st.get("bankItems").getAsInt());
		assertEquals("1d", st.get("window").getAsString());
		assertTrue(st.get("bankLoaded").getAsBoolean());
		assertTrue(st.get("baselineLoaded").getAsBoolean());

		// The panel's describe() nests as a tree, not a quoted string: jq .result.panel.card has to work.
		final JsonObject p = s.getAsJsonObject("panel");
		assertEquals("LIST", p.get("card").getAsString());
		assertEquals(250, p.get("shown").getAsInt());
	}

	/**
	 * L13: {@code state} echoes the whole of how the baseline was CHOSEN - the derived anchor day and the
	 * agreement fraction behind it, the newest revision's day, and for this window the baseline's day, its
	 * revision id and whether it is the day that was asked for - plus the mapping's age. Addendum K's selector
	 * landed on the intended Jagex day about half the time (L-C); these are the fields that make the choice
	 * auditable from a terminal instead of merely plausible.
	 *
	 * <p>The days are ISO-8601, so a script can compare them without reparsing the printed "07 Sep".
	 */
	@Test
	public void stateEchoesTheAnchorDayTheAgreementTheBaselineDayAndTheMappingAge()
	{
		final PriceService.Status st0 = status();
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject st = ok("state").getAsJsonObject("status");
		assertEquals("2026-09-08", st.get("anchorDay").getAsString());
		assertEquals(AGREE, st.get("agree").getAsDouble(), 1e-9);
		assertEquals("2026-09-07", st.get("r0Day").getAsString());
		assertEquals("2026-09-07", st.get("thenDay").getAsString());
		assertEquals(REV_ID, st.get("thenRevId").getAsLong());
		assertFalse("not degraded: enough items compared and the last fetch succeeded", st.get("degraded").getAsBoolean());
		assertNull("no reason without the flag (the injected Gson drops the null)", st.get("degradedReason"));
		assertEquals(MAPPING_AT, st.get("mappingAtMillis").getAsLong());
		// The K-era fields are gone: a publication clock is not the data day (L-E), and echoing one would send a
		// live session hunting the wrong number.
		assertNull(st.get("baselineRevisionSeconds"));
		assertNull(st.get("baselineRevisionAt"));
		assertNull(st.get("baselineRevId"));
	}

	/**
	 * Two different things a script must be able to tell apart (checker, 2026-09-09). L5's "accept and label"
	 * case is NOT degraded: the baseline table claims a day older than the window asked for and {@code thenDay}
	 * simply is the day the table really carries - what the status line and every row tooltip print. Degraded is
	 * the fallback mode of L3/L11 - too few comparable items, or a failed history fetch - and {@code degradedReason}
	 * says which.
	 */
	@Test
	public void stateSaysWhyWhenTheRowsAreDegradedAndLabelsTheRealDayWhenTheyAreNot()
	{
		final PriceService.Status st0 = status();
		when(st0.thenDay()).thenReturn(LocalDate.of(2026, 9, 6));
		when(service.currentStatus()).thenReturn(st0);

		JsonObject st = ok("state").getAsJsonObject("status");
		assertEquals("the real day of the table, not the day asked for", "2026-09-06", st.get("thenDay").getAsString());
		assertFalse("a stale-day baseline is not a degraded mode", st.get("degraded").getAsBoolean());

		when(st0.degraded()).thenReturn(true);
		when(st0.degradedReason()).thenReturn("the last wiki history fetch failed - showing stored baselines");
		st = ok("state").getAsJsonObject("status");
		assertTrue(st.get("degraded").getAsBoolean());
		assertEquals("the last wiki history fetch failed - showing stored baselines", st.get("degradedReason").getAsString());
	}

	/**
	 * L13's "per window thenDay/revId": {@code state.windows} echoes EVERY window's baseline day and revision as
	 * the service holds them, so a live session can see all five choices in one answer; a window with no baseline
	 * is absent from the object rather than a plausible-looking zero (added by the checker, 2026-09-09).
	 */
	@Test
	public void stateEchoesEveryWindowsBaselineDayAndRevision()
	{
		final long thenSeconds = THEN_DAY.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + 71_122L; // 19:45:22Z, L7 marker
		when(service.baseline(MovementWindow.D1)).thenReturn(
			new PriceMap(Collections.emptyMap(), MAPPING_AT, thenSeconds, REV_ID));
		when(service.baseline(MovementWindow.D7)).thenReturn(PriceMap.EMPTY);
		// D30 / D90 / D180 answer the mock's null: no baseline known.

		final JsonObject windows = ok("state").getAsJsonObject("windows");
		assertEquals(1, windows.size());
		final JsonObject oneDay = windows.getAsJsonObject("1d");
		assertEquals("2026-09-07", oneDay.get("thenDay").getAsString());
		assertEquals(REV_ID, oneDay.get("thenRevId").getAsLong());
		assertEquals("the shape the map was read from, so a refused baseline can be told from a missing one (B028)",
			PriceMapDto.SCHEMA, oneDay.get("schema").getAsInt());
		assertNull("EMPTY has no revision and is left out", windows.get("7d"));
		assertNull(windows.get("180d"));
	}

	/**
	 * Nothing fetched yet: the day fields are ABSENT rather than a plausible-looking date (the injected Gson
	 * drops nulls, so {@code jq .result.status.thenDay} answers {@code null}), and the numbers are zeroes.
	 */
	@Test
	public void aStatusWithNoBaselineOmitsTheDaysRatherThanInventingThem()
	{
		final PriceService.Status empty = mock(PriceService.Status.class);
		when(empty.text()).thenReturn("Guide prices - No bank yet");
		when(empty.window()).thenReturn(MovementWindow.D1);
		// The real Status normalises a null source to NONE in its constructor, so the mock says so too rather
		// than leaving the bridge to print the string "null" for a value that cannot occur.
		when(empty.source()).thenReturn(MovementRow.PriceSource.NONE);
		when(service.currentStatus()).thenReturn(empty);

		final JsonObject st = ok("state").getAsJsonObject("status");
		assertNull("no anchor day yet, and no 1970 either", st.get("anchorDay"));
		assertNull(st.get("r0Day"));
		assertNull(st.get("thenDay"));
		assertEquals(0.0d, st.get("agree").getAsDouble(), 1e-9);
		assertEquals(0L, st.get("thenRevId").getAsLong());
		assertFalse(st.get("degraded").getAsBoolean());
		assertEquals(0L, st.get("mappingAtMillis").getAsLong());
		assertEquals("NONE", st.get("source").getAsString());
		assertEquals(0, st.get("agreeSamples").getAsInt());
		assertEquals(0L, st.get("indexAtMillis").getAsLong());
	}

	/**
	 * The four fields of {@code state.status} that nothing used to look at: {@code source}, {@code problem},
	 * {@code agreeSamples} and {@code indexAtMillis}. Every one of them read as a never-wired field would
	 * ({@code "null"}, absent, 0, 0) in all 31 bridge tests, so a wave that dropped one - or routed
	 * {@code problem} to the header text, which is its neighbour in {@code buildStatusLocked} - would have left
	 * the suite green while the addendum-K live acceptance steps that are READ OUT OF THIS OBJECT ("No 30d
	 * history yet" at step 4, "Wiki history down - no movement" at step 11) validated nothing.
	 *
	 * <p>{@code text} and {@code problem} are asserted together in both directions, which is what pins them as
	 * TWO fields: the healthy status has a header sentence and no problem at all, and a status with a problem
	 * still has the same header sentence beside it.
	 */
	@Test
	public void stateEchoesTheSourceTheIndexAgeAndTheProblemSeparatelyFromTheHeader()
	{
		final PriceService.Status st0 = status();
		when(service.currentStatus()).thenReturn(st0);

		JsonObject st = ok("state").getAsJsonObject("status");
		assertEquals("GUIDE", st.get("source").getAsString());
		assertEquals(AGREE_SAMPLES, st.get("agreeSamples").getAsInt());
		assertEquals(INDEX_AT, st.get("indexAtMillis").getAsLong());
		assertEquals(STATUS_TEXT, st.get("text").getAsString());
		assertNull("a loaded baseline complains about nothing (the injected Gson drops the null)",
			st.get("problem"));

		// The three sentences a live acceptance step greps for, verbatim and in their own field.
		for (String problem : new String[]{
			PriceService.problemHistoryPending(MovementWindow.D30),
			PriceService.problemNoHistory(MovementWindow.D30),
			PriceService.problemHistoryUnavailable()})
		{
			when(st0.problem()).thenReturn(problem);
			st = ok("state").getAsJsonObject("status");
			assertEquals(problem, st.get("problem").getAsString());
			assertEquals("the header sentence is a different field and does not follow the problem",
				STATUS_TEXT, st.get("text").getAsString());
		}
	}

	/**
	 * M5: {@code state.portfolio} echoes the bank value line as the service computed it - the WHOLE bank's worth over
	 * the priced stacks, and per window with a baseline the both-days sums, the move and its coverage - so a live
	 * session can sum the bank file against RuneLite's price table and compare to the gp (M6 step 2). Keyed by
	 * label like {@code windows}; a window with no baseline is absent; a null percent is absent like every null.
	 */
	@Test
	public void stateEchoesThePortfolio()
	{
		final PriceService.Status st0 = status();
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		moves.put(MovementWindow.D1, new WindowMove(MovementWindow.D1, THEN_DAY, 1_222_167_890L, 1_234_567_890L,
			12_400_000L, 1.0146d, 512));
		moves.put(MovementWindow.D30, new WindowMove(MovementWindow.D30, LocalDate.of(2026, 8, 9), 0L, 0L, 0L, null, 0));
		when(st0.portfolio()).thenReturn(new PortfolioSummary(1_234_567_890L, 519, 538, moves));
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject p = ok("state").getAsJsonObject("portfolio");
		assertEquals(1_234_567_890L, p.get("valueNow").getAsLong());
		assertEquals(519, p.get("itemsPriced").getAsInt());
		assertEquals("the old arity carries no cash, and it echoes as 0 rather than going missing (P1)",
			0L, p.get("currencyGp").getAsLong());
		assertEquals(538, p.get("itemsTotal").getAsInt());
		final JsonObject windows = p.getAsJsonObject("windows");
		assertEquals("only the windows with a baseline, keyed by label", 2, windows.size());
		final JsonObject d1 = windows.getAsJsonObject("1d");
		assertEquals("2026-09-07", d1.get("thenDay").getAsString());
		assertEquals(1_222_167_890L, d1.get("valueThen").getAsLong());
		assertEquals(1_234_567_890L, d1.get("valueNowCovered").getAsLong());
		assertEquals(12_400_000L, d1.get("deltaGp").getAsLong());
		assertEquals(1.0146d, d1.get("deltaPct").getAsDouble(), 1e-9);
		assertEquals(512, d1.get("itemsCovered").getAsInt());
		final JsonObject d30 = windows.getAsJsonObject("30d");
		assertEquals("2026-08-09", d30.get("thenDay").getAsString());
		assertEquals(0, d30.get("itemsCovered").getAsInt());
		assertNull("nothing was worth anything then: no percent, and Gson drops the null", d30.get("deltaPct"));
		assertNull(windows.get("7d"));
		assertTrue("every verb carries it, like the rest of the state (C40)", ok("window=7d").has("portfolio"));
	}

	/** Before the first computation - or on a status that carries none - the echo is zeroes and no windows, never absent. */
	@Test
	public void aStatusWithoutASummaryEchoesAnEmptyPortfolio()
	{
		final PriceService.Status empty = mock(PriceService.Status.class);
		when(empty.window()).thenReturn(MovementWindow.D1);
		when(service.currentStatus()).thenReturn(empty);

		final JsonObject p = ok("state").getAsJsonObject("portfolio");
		assertEquals(0L, p.get("valueNow").getAsLong());
		assertEquals(0, p.get("itemsPriced").getAsInt());
		assertEquals(0, p.get("itemsTotal").getAsInt());
		assertEquals(0, p.getAsJsonObject("windows").size());

		when(service.currentStatus()).thenReturn(null);
		assertEquals(0L, ok("state").getAsJsonObject("portfolio").get("valueNow").getAsLong());
		assertEquals("a portfolio nobody has computed still names its cash, as 0 (P1)",
			0L, ok("state").getAsJsonObject("portfolio").get("currencyGp").getAsLong());
	}

	/**
	 * P1: the cash {@link BankReader} measured - coins + 1,000 x platinum tokens - is echoed as
	 * {@code currencyGp} beside {@code valueNow}, and it is a SUMMAND of that figure rather than something to add
	 * to it: the headline on the card is the whole bank, stacks and cash in one number. A live session reads
	 * {@code bpm state | jq .result.portfolio.currencyGp} to see how much of the total is not a stack, and
	 * compares it with the bank file's own field. {@code itemsPriced} / {@code itemsTotal} count STACKS, so
	 * neither of them moves when cash arrives.
	 */
	@Test
	public void thePortfolioEchoNamesTheCashInsideTheBankValue()
	{
		final List<BankItem> items = new ArrayList<>();
		items.add(new BankItem(4151, 2, "Abyssal whip", false));
		final PortfolioSummary summary =
			PortfolioMath.summarise(items, id -> id == 4151 ? 1_500_000L : null, null, null, 791_078L);
		final PriceService.Status st0 = status();
		when(st0.portfolio()).thenReturn(summary);
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject p = ok("state").getAsJsonObject("portfolio");
		assertEquals(791_078L, p.get("currencyGp").getAsLong());
		assertEquals("the whole bank in one figure: the stack and the coins together (P1)",
			3_000_000L + 791_078L, p.get("valueNow").getAsLong());
		assertEquals("cash is not a stack", 1, p.get("itemsPriced").getAsInt());
		assertEquals(1, p.get("itemsTotal").getAsInt());
		assertTrue("every verb carries it, like the rest of the state (C40)",
			ok("window=7d").getAsJsonObject("portfolio").has("currencyGp"));
	}

	/**
	 * T8: {@code state.live} is what the traded feeds DELIVERED for the rows in the same answer - when the
	 * {@code /latest} snapshot behind them was fetched, how many items it names, and how the bank's stacks split
	 * between the live series, the guide table and the alch rule. The keys and their order are
	 * {@code LiveStatus.asMap()}'s, so this echo and the panel's own {@code describe()} can never drift apart.
	 *
	 * <p>{@code liveRows} against the {@code LIVE} rows beside it is the whole point: it is the one line a live
	 * session runs to see that the liquidity rules - T3's three, and addendum V's checks 4 and 5 - and the
	 * painted list agree.
	 */
	@Test
	public void stateEchoesWhatTheTradedFeedsDelivered()
	{
		final PriceService.Status st0 = status();
		when(st0.live()).thenReturn(new PriceService.Status.LiveStatus(1_789_000_000_000L, 4231, 37, 470, 5,
			LIVE_DAY, liveWindowDays()));
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject live = ok("state").getAsJsonObject("live");
		assertEquals("T8's five figures, then addendum U's two days (U4), in asMap()'s own order",
			"[fetchedAt, latestItems, liveRows, guideRows, alchRows, liveDay, windowDays]",
			live.keySet().toString());
		assertEquals(1_789_000_000_000L, live.get("fetchedAt").getAsLong());
		assertEquals(4231, live.get("latestItems").getAsInt());
		assertEquals(37, live.get("liveRows").getAsInt());
		assertEquals(470, live.get("guideRows").getAsInt());
		assertEquals(5, live.get("alchRows").getAsInt());
		assertTrue("every verb carries it, like the rest of the state (C40)", ok("window=7d").has("live"));
	}

	/**
	 * U4, and the finding that produced addendum U: the live series keeps its OWN calendar, and the bridge prints
	 * it. {@code liveDay} is the UTC date of the {@code /latest} snapshot - today - and {@code windowDays} names
	 * the traded day each window is ACTUALLY measured against, which is {@code liveDay - N}.
	 *
	 * <p>That is the whole of the fix. On live look 5 the addendum-T build tied a live window to the GUIDE's
	 * anchor day, which was still 11 Sep at 17:07 EDT on the 12th because Jagex had not published the 12th's
	 * table, so live "1d" reached back to the 10th and Partyhat set read +30 % over a two-day span. So this test
	 * puts the two calendars a long way apart on purpose - the fixture's guide anchor is 08 Sep and the live
	 * snapshot is the 12th - and pins BOTH: {@code live.liveDay} is the live day, {@code status.anchorDay} is
	 * still the guide's, and "1d" under {@code windowDays} is the day after the day addendum T would have used.
	 */
	@Test
	public void stateEchoesTheLiveCalendarTheLiveWindowsCountBackFrom()
	{
		final PriceService.Status st0 = status();
		when(st0.live()).thenReturn(new PriceService.Status.LiveStatus(1_789_247_229_142L, 4535, 398, 190, 218,
			LIVE_DAY, liveWindowDays()));
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject state = ok("state");
		final JsonObject live = state.getAsJsonObject("live");
		assertEquals("liveDay is the /latest snapshot's own UTC date (U1)", "2026-09-12",
			live.get("liveDay").getAsString());

		final JsonObject days = live.getAsJsonObject("windowDays");
		assertEquals("1d is YESTERDAY - the day addendum T reached past (live look 5)", "2026-09-11",
			days.get("1d").getAsString());
		assertEquals("2026-09-05", days.get("7d").getAsString());
		assertEquals("2026-08-13", days.get("30d").getAsString());
		// U2: this window fell back a day, because the day it wanted had not closed when the bucket was taken.
		// The echo prints the day the service actually HOLDS and never re-derives one from liveDay - which is
		// the only reason a reader can trust it at all.
		assertEquals("the day the bucket actually holds, fallback included (U2)", "2026-06-13",
			days.get("90d").getAsString());
		assertNull("a window with no traded bucket reads null (U4)", isoOrNull(days, "180d"));

		// ...and the GUIDE's days are untouched beside it. The two disagreeing on a day Jagex has not published
		// yet is exactly what addendum U separated; a script reads both and compares them.
		assertEquals("the guide's anchor is its own answer still", "2026-09-08",
			state.getAsJsonObject("status").get("anchorDay").getAsString());
		assertEquals("and so is the guide baseline's day", "2026-09-07",
			state.getAsJsonObject("status").get("thenDay").getAsString());
	}

	/**
	 * U4, the other half of T8's promise about the off position: with the switch off there is no live calendar
	 * either. {@code liveDay} has nothing behind it and no window names a traded day, so {@code state.live} with
	 * live prices off is the pre-addendum-T answer down to the last key a script can read a value from.
	 *
	 * <p>A null day is ABSENT rather than {@code null} (the injected Gson does not write nulls), which is why the
	 * assertion goes through {@link #isoOrNull}: {@code jq} answers {@code null} for both spellings, and the live
	 * list is written in {@code jq}.
	 */
	@Test
	public void theOffPositionCarriesNoLiveCalendarEither()
	{
		final PriceService.Status st0 = status();
		when(st0.live()).thenReturn(PriceService.Status.LiveStatus.OFF);
		when(service.currentStatus()).thenReturn(st0);

		final JsonObject live = ok("state").getAsJsonObject("live");
		assertNull("no snapshot, no day (T8's off position)", isoOrNull(live, "liveDay"));
		final JsonElement days = live.get("windowDays");
		if (days != null && !days.isJsonNull())
		{
			for (MovementWindow window : MovementWindow.values())
			{
				assertNull(window.label() + " names a traded day with the switch off",
					isoOrNull(days.getAsJsonObject(), window.label()));
			}
		}
	}

	/**
	 * T8: with the switch off - and before anything has been computed, and on a status that carries nothing -
	 * every figure is 0 and {@code fetchedAt} with it. That is the shape a script reads to prove the OFF position
	 * costs nothing: no snapshot, no live row, no request behind either. It must never be absent and never throw,
	 * because a state call on a freshly started plugin is the first thing a live session makes.
	 */
	@Test
	public void aStatusWithNoLiveFeedEchoesZeroes()
	{
		final PriceService.Status empty = mock(PriceService.Status.class);
		when(empty.window()).thenReturn(MovementWindow.D1);
		when(service.currentStatus()).thenReturn(empty);

		final JsonObject off = ok("state").getAsJsonObject("live");
		assertEquals(0L, off.get("fetchedAt").getAsLong());
		assertEquals(0, off.get("latestItems").getAsInt());
		assertEquals(0, off.get("liveRows").getAsInt());
		assertEquals(0, off.get("guideRows").getAsInt());
		assertEquals(0, off.get("alchRows").getAsInt());

		// ...and with no status at all, which is what a bridge call before the first publish sees.
		when(service.currentStatus()).thenReturn(null);
		assertEquals(0, ok("state").getAsJsonObject("live").get("liveRows").getAsInt());
	}

	/** A bank of 800 must not come back down the HTTP pipe: {@code state} carries the first ten rows. */
	@Test
	public void stateCarriesTheFirstTenRowsAndTheirNumbers()
	{
		final List<MovementRow> rows = new ArrayList<>();
		for (int i = 0; i < 25; i++)
		{
			rows.add(new MovementRow(4151 + i, "Item " + i, 2, false, 100L + i, 90L, 10L, 11.1, 200L,
				MovementRow.PriceSource.GUIDE));
		}
		when(service.currentRows()).thenReturn(rows);

		final JsonArray out = ok("state").getAsJsonArray("rows");
		assertEquals(BpmCommands.STATE_ROWS, out.size());
		final JsonObject first = out.get(0).getAsJsonObject();
		assertEquals(4151, first.get("id").getAsInt());
		assertEquals("Item 0", first.get("name").getAsString());
		assertEquals(2, first.get("qty").getAsInt());
		assertEquals(100, first.get("unit").getAsLong());
		assertEquals(90, first.get("then").getAsLong());
		assertEquals(10, first.get("gp").getAsLong());
		assertEquals(11.1, first.get("pct").getAsDouble(), 1e-9);
		// Every priced row is a GUIDE row since addendum K6 - one price, one source.
		assertEquals("GUIDE", first.get("source").getAsString());
	}

	/**
	 * Y1/Y3: while the carried switch is on every row carries the SPLIT behind its quantity -
	 * {@code bankQty} / {@code invQty} / {@code wornQty}, the three numbers the row's own tooltip prints as
	 * "3 in bank, 1 in inventory, 1 worn" - and while it is off the three keys are ABSENT rather than zeroed,
	 * because with nothing merged there is nothing split to say and 0 / 0 / 0 on every row would read as a
	 * bank that had lost its stacks.
	 */
	@Test
	public void aRowCarriesTheSplitQuantitiesOnlyWhileTheCarriedSwitchIsOn()
	{
		when(service.currentRows()).thenReturn(Collections.singletonList(
			new MovementRow(4151, "Abyssal whip", 5, false, 100L, 90L, 10L, 11.1, 500L,
				MovementRow.PriceSource.GUIDE)));

		// The carried switch is ON in the default, so the fuller of the two readings is DEFAULT itself.
		when(panel.options()).thenReturn(ViewOptions.DEFAULT);
		final JsonObject on = ok("state").getAsJsonArray("rows").get(0).getAsJsonObject();
		assertEquals(5, on.get("qty").getAsInt());
		assertNotNull("the split is echoed while the switch is on", on.get("bankQty"));
		assertNotNull(on.get("invQty"));
		assertNotNull(on.get("wornQty"));

		when(panel.options()).thenReturn(ViewOptions.DEFAULT.withCountInventory(false));
		final JsonObject off = ok("state").getAsJsonArray("rows").get(0).getAsJsonObject();
		assertEquals("the quantity itself never moves", 5, off.get("qty").getAsInt());
		assertNull("nothing is split while the switch is off", off.get("bankQty"));
		assertNull(off.get("invQty"));
		assertNull(off.get("wornQty"));
	}

	/** A row with no baseline has nothing under {@code then}/{@code gp}/{@code pct}: Gson drops the nulls. */
	@Test
	public void aRowWithNoMovementSimplyHasNoMovementFields()
	{
		when(service.currentRows()).thenReturn(Collections.singletonList(
			new MovementRow(995, "Coins", 1, true, 1L, null, null, null, 1L, MovementRow.PriceSource.GUIDE)));
		final JsonObject row = ok("state").getAsJsonArray("rows").get(0).getAsJsonObject();
		assertEquals("GUIDE", row.get("source").getAsString());
		assertNull(row.get("then"));
		assertNull(row.get("gp"));
		assertNull(row.get("pct"));
	}

	// ---------------------------------------------------------------- the filter verbs (which widget they press)

	/**
	 * K10: the five labels, the enum names, and the legacy trade-era spellings folded onto {@link
	 * MovementWindow#D1} so a script written against the first build keeps working. Anything else is refused
	 * with the five labels in the message.
	 */
	@Test
	public void windowTakesTheFiveLabelsTheEnumNamesAndTheLegacySpellings()
	{
		ok("window=1d");
		verify(panel).selectWindow(MovementWindow.D1);
		ok("window=7d");
		verify(panel).selectWindow(MovementWindow.D7);
		ok("window=30d");
		verify(panel).selectWindow(MovementWindow.D30);
		ok("window=90d");
		verify(panel).selectWindow(MovementWindow.D90);
		ok("window=180d");
		verify(panel).selectWindow(MovementWindow.D180);
		// The enum name is accepted too (MovementWindow.parse).
		ok("window=D7");
		verify(panel, times(2)).selectWindow(MovementWindow.D7);
		// ...and so are the windows this plugin used to have, so an old script does not answer ok:false.
		ok("window=24h");
		ok("window=1h");
		verify(panel, times(3)).selectWindow(MovementWindow.D1);

		final JsonObject bad = send("window=3h");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(),
			bad.get("error").getAsString().contains("1d, 7d, 30d, 90d or 180d"));
		verify(panel, times(8)).selectWindow(any());
	}

	/**
	 * {@code sort=} presses a column and {@code dir=} moves the direction alone. Since addendum W the columns are
	 * four (W1), and the word may be the short verb, the label or an alias an older script was written against -
	 * {@link SortMode#parse(String)} keeps every one of them - while anything else is refused with the four
	 * columns named, so an operator never has to read the source to recover.
	 */
	@Test
	public void sortAndDirPressTheChipsAndTheDirectionAlone()
	{
		ok("sort=gp");
		verify(panel).clickSort(SortMode.GP_MOVE);
		ok("sort=price");
		verify(panel).clickSort(SortMode.UNIT_PRICE);
		ok("sort=pct");
		verify(panel).clickSort(SortMode.PERCENT_MOVE);
		ok("sort=% move");
		verify(panel, times(2)).clickSort(SortMode.PERCENT_MOVE);
		// W1's fourth column, in the word a script types and in the label the button prints.
		ok("sort=stack");
		verify(panel).clickSort(SortMode.STACK_VALUE);
		ok("sort=Stack price");
		verify(panel, times(2)).clickSort(SortMode.STACK_VALUE);

		ok("dir=asc");
		verify(panel).setDescending(false);
		ok("dir=desc");
		verify(panel).setDescending(true);

		final JsonObject bad = send("sort=sideways");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(),
			bad.get("error").getAsString().contains("percent, gp, price, stack"));
		assertFalse(send("dir=widdershins").get("ok").getAsBoolean());
		verify(panel, times(6)).clickSort(any());
		verify(panel, times(2)).setDescending(anyBoolean());
	}

	// ---------------------------------------------------------------- addendum W: one vocabulary, four columns

	/**
	 * W4: {@code sort=} is the only sort verb again, and it names a COLUMN and nothing else. Every spelling
	 * addendum W kept is here - the short verb, the constant, the label and the aliases the pre-W scripts were
	 * written against - and so is the refusal that addendum N's six named orderings now get: they were a column
	 * AND a direction in one word, and there is no word for a direction any more but {@code dir=}.
	 *
	 * <p>The panel here is a mock, so {@code filter()} answers null and nothing can be inferred about what is
	 * lit; the bridge presses the button and lets the widget decide, which is the whole of its job since the
	 * {@code applyOrder} two-step went with {@code SortOrder}.
	 */
	@Test
	public void sortTakesTheFourColumnsAndNoNamedOrdering()
	{
		ok("sort=percent");
		verify(panel).clickSort(SortMode.PERCENT_MOVE);
		ok("sort=PERCENT_MOVE");
		verify(panel, times(2)).clickSort(SortMode.PERCENT_MOVE);
		ok("sort=amount");
		verify(panel).clickSort(SortMode.GP_MOVE);
		ok("sort=unit");
		verify(panel).clickSort(SortMode.UNIT_PRICE);
		ok("sort=holding");
		verify(panel).clickSort(SortMode.STACK_VALUE);

		// A named ordering is not a column: the verb refuses it and names the four that are left, rather than
		// pressing something the operator did not ask for.
		final JsonObject bad = send("sort=gainers");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(),
			bad.get("error").getAsString().contains("percent, gp, price, stack"));
		assertFalse(send("sort=Most valuable").get("ok").getAsBoolean());
		// ...and the direction is untouched by any of it: only dir= and the lit column move that (W2).
		verify(panel, times(5)).clickSort(any());
		verify(panel, never()).setDescending(anyBoolean());
	}

	/**
	 * W4: {@code order=} is REMOVED, and answers one sentence saying so and naming the two verbs that replace it.
	 * It is not "unknown command" and not a no-op: every live acceptance list from addendum N to addendum V types
	 * this verb, so the one thing it has to do is point its author at {@code sort=} and {@code dir=}.
	 */
	@Test
	public void orderIsGoneAndTheVerbSaysWhereTheWordsWent()
	{
		final JsonObject bad = send("order=gainers");
		assertFalse(bad.get("ok").getAsBoolean());
		assertEquals("order= is gone since addendum W - use sort=<column> (percent, gp, price, stack)"
			+ " and dir=asc|desc", bad.get("error").getAsString());

		// Every spelling of it gets the same sentence, the bare verb included, and none of them presses anything.
		assertEquals(bad.get("error").getAsString(), send("order=Most valuable").get("error").getAsString());
		assertEquals(bad.get("error").getAsString(), send("order").get("error").getAsString());
		verify(panel, never()).clickSort(any());
		verify(panel, never()).setDescending(anyBoolean());
	}

	/**
	 * W4: the filter echo is the pair the sidebar actually holds - the lit column's LABEL and the arrow beside
	 * it - and no more. Addendum N's third field, {@code order}, said the two as one sentence ("Biggest gp
	 * loss"); the sentences are deleted, so a field that re-derived one here would name something the sidebar
	 * prints nowhere.
	 */
	@Test
	public void stateEchoesTheLitColumnsLabelAndTheDirectionAndNoOrdering()
	{
		when(service.filter()).thenReturn(new RowFilter(0, 0, SortMode.GP_MOVE, false, MovementWindow.D1));
		final JsonObject f = ok("state").getAsJsonObject("filter");
		assertEquals("gp change", f.get("sort").getAsString());
		assertFalse(f.get("descending").getAsBoolean());
		assertNull("filter.order went with SortOrder (W4)", f.get("order"));

		when(service.filter()).thenReturn(new RowFilter(0, 0, SortMode.STACK_VALUE, true, MovementWindow.D1));
		final JsonObject g = ok("state").getAsJsonObject("filter");
		assertEquals("Stack price", g.get("sort").getAsString());
		assertTrue(g.get("descending").getAsBoolean());
		assertNull(g.get("order"));
	}

	// ---------------------------------------------------------------- addendum O: the card's three switches

	/**
	 * O5: {@code hero=} drives the bank value card's three show/hide switches. A field word TOGGLES its figure
	 * against what the card is currently drawing, {@code all} and {@code none} set all three, and the panel is
	 * pressed through {@code setHeroVisibility} - the card's own right-click check items, which apply AND
	 * write the config - so a {@code /bpm} session and a hand session leave the same stored settings behind.
	 */
	@Test
	public void heroTogglesTheCardsThreeFiguresThroughThePanel()
	{
		// A panel that has not been asked reads as ALL, which is also what a fresh profile stores.
		ok("hero=value");
		verify(panel).setHeroVisibility(HeroVisibility.of(false, true, true));
		ok("hero=gp");
		verify(panel).setHeroVisibility(HeroVisibility.of(true, false, true));
		ok("hero=pct");
		verify(panel).setHeroVisibility(HeroVisibility.of(true, true, false));

		ok("hero=none");
		verify(panel).setHeroVisibility(HeroVisibility.NONE);
		ok("hero=all");
		verify(panel).setHeroVisibility(HeroVisibility.ALL);

		// The toggle is against what the card is SHOWING, not against the default: from NONE, a field word
		// turns that one figure back on.
		when(panel.heroVisibility()).thenReturn(HeroVisibility.NONE);
		ok("hero=total");
		verify(panel).setHeroVisibility(HeroVisibility.of(true, false, false));
		ok("hero=%");
		verify(panel).setHeroVisibility(HeroVisibility.of(false, false, true));

		final JsonObject bad = send("hero=sideways");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("value"));
		assertFalse("a bare hero names no figure", send("hero").get("ok").getAsBoolean());
		verify(panel, times(7)).setHeroVisibility(any());
	}

	/** Every answer says which of the card's three figures are on screen, so a shot needs no second call. */
	@Test
	public void everyAnswerEchoesWhatTheCardIsShowing()
	{
		final JsonObject fresh = ok("state").getAsJsonObject("hero");
		assertTrue("a panel that has not been asked reads as ALL", fresh.get("value").getAsBoolean());
		assertTrue(fresh.get("gp").getAsBoolean());
		assertTrue(fresh.get("pct").getAsBoolean());

		when(panel.heroVisibility()).thenReturn(HeroVisibility.of(false, true, false));
		final JsonObject some = ok("state").getAsJsonObject("hero");
		assertFalse(some.get("value").getAsBoolean());
		assertTrue(some.get("gp").getAsBoolean());
		assertFalse(some.get("pct").getAsBoolean());

		// ...and the whole portfolio is still echoed, hidden figures included: the switch says what is PAINTED,
		// never what is known (O3).
		assertTrue(ok("state").has("portfolio"));
	}

	// ---------------------------------------------------------------- addendum Q: the gear menu's switches

	/**
	 * Q7: {@code opt=} drives the gear menu's view switches, shaped on {@code hero=}. A field word TOGGLES
	 * its switch against what the sidebar is using now, {@code all} and {@code none} set them all, and the
	 * panel is pressed through {@code setOptions} - the gear's own check items, which apply the switch AND write
	 * it through the {@code Prefs} seam the plugin implements over its config, where the figure-changing
	 * switches also reach {@code PriceService}. So a {@code /bpm} session and a hand session leave the same
	 * stored settings behind.
	 *
	 * <p>The expectations are written as {@link ViewOptions#DEFAULT} plus ONE wither wherever the press is one
	 * switch moving, because since addendum AO there is a single positional constructor and a line of five bare
	 * booleans is exactly what made removing a middle field dangerous: five booleans written for the pre-AO order
	 * still compile against the new one and mean something else, with no error anywhere to say so.
	 */
	@Test
	public void optTogglesTheViewSwitchesThroughTheGearsOwnItems()
	{
		// A panel that has not been asked reads as DEFAULT - cash counted, untradeables off, live prices and the
		// carried items on, the data hovers off - which is also what a fresh profile stores.
		ok("opt=cash");
		verify(panel).setOptions(ViewOptions.DEFAULT.withCountCash(false));
		ok("opt=live");
		verify(panel).setOptions(ViewOptions.DEFAULT.withLivePrices(false));
		ok("opt=inventory");
		verify(panel).setOptions(ViewOptions.DEFAULT.withCountInventory(false));

		ok("opt=all");
		verify(panel).setOptions(new ViewOptions(true, true, true, true, false));
		ok("opt=none");
		verify(panel).setOptions(new ViewOptions(false, false, false, false, false));

		// The toggle is against what the sidebar is USING, not against the default: from cash off, a field word
		// turns that one switch back on. The spellings are the generous ones a URL query gets typed with.
		//
		// The base below carries the data hovers ON - no opt= word moves that switch - so every value expected
		// here is one neither "all" nor "none" can produce. That matters since addendum AO: with the holding
		// switch gone, turning untradeables on from the DEFAULT lands on exactly the five values "all" does, and
		// Mockito would see one press where this test means two.
		final ViewOptions base = new ViewOptions(false, true, false, false, true);
		when(panel.options()).thenReturn(base);
		ok("opt=coins");
		verify(panel).setOptions(base.withCountCash(true));
		ok("opt=untradeables");
		verify(panel).setOptions(base.withCountUntradeables(false));
		ok("opt=traded");
		verify(panel).setOptions(base.withLivePrices(true));
		ok("opt=worn");
		verify(panel).setOptions(base.withCountInventory(true));

		// The untradeables alias, toggling the same switch the other way from a base of its own so the two
		// presses are told apart by their values.
		when(panel.options()).thenReturn(base.withCountUntradeables(false));
		ok("opt=alch");
		verify(panel).setOptions(base);

		// Ten presses, and every one of them through the check item rather than the bare applyOptions: the
		// difference between the two is whether the choice is remembered.
		verify(panel, times(10)).setOptions(any());
		verify(panel, never()).applyOptions(any());
	}

	/**
	 * T8 and Y1: {@code none} turns off every switch the verb reaches and {@code all} turns on every one but the
	 * data hovers. The word is the only one whose whole promise is that nothing is left standing, so its
	 * expectations are spelled in full rather than built with withers - and the one asymmetry is pinned here:
	 * {@code all} leaves {@code showHoverText} OFF, which is the arity addendum AH gave it and has never been a
	 * way to turn the hovers on.
	 *
	 * <p>Since addendum AO each word names FIVE fields rather than six - the holding switch is deleted - and a
	 * press that quietly left one of the remaining five standing is exactly what a positional expectation
	 * written for the old order would have hidden.
	 */
	@Test
	public void allAndNoneReachTheLiveAndCarriedSwitchesToo()
	{
		when(panel.options()).thenReturn(new ViewOptions(true, true, true, true, true));
		ok("opt=none");
		verify(panel).setOptions(new ViewOptions(false, false, false, false, false));

		when(panel.options()).thenReturn(new ViewOptions(false, false, false, false, false));
		ok("opt=all");
		verify(panel).setOptions(new ViewOptions(true, true, true, true, false));
	}

	/**
	 * Y1: the fifth switch under every name an operator reaches for. The words are the addendum's own
	 * ({@code inventory}), its short form ({@code inv}), and the two halves of the label "Include inventory and
	 * worn gear" ({@code gear}, {@code worn}); the stored key is accepted as well, as the other four accept
	 * theirs. Each one TOGGLES against what the sidebar is using, so the verb is symmetrical.
	 */
	@Test
	public void optInventoryTogglesTheCarriedSwitchUnderEveryName()
	{
		final ViewOptions on = ViewOptions.DEFAULT;
		final ViewOptions off = ViewOptions.DEFAULT.withCountInventory(false);
		for (String verb : new String[]{"inventory", "inv", "gear", "worn", "countinventory"})
		{
			when(panel.options()).thenReturn(on);
			ok("opt=" + verb);
			verify(panel).setOptions(off);
			when(panel.options()).thenReturn(off);
			ok("opt=" + verb);
			verify(panel).setOptions(on);
			reset(panel);
		}
	}

	/** A verb that names no switch leaves the sidebar exactly as it was, and says what to type instead. */
	@Test
	public void anUnknownOptVerbIsRefusedAndChangesNothing()
	{
		final JsonObject bad = send("opt=sideways");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("cash"));
		assertTrue(bad.get("error").getAsString().contains("untradeables"));
		// T8: the fourth switch is in the refusal too, or an operator would have to read the source to find it.
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("live"));
		// Y1: and the fifth.
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("inventory"));
		// AO1: and the word that is gone is NOT in the list - it has a sentence of its own, below.
		assertFalse(bad.get("error").getAsString(), bad.get("error").getAsString().contains("holding"));
		assertFalse("a bare opt names no switch", send("opt").get("ok").getAsBoolean());
		verify(panel, never()).setOptions(any());
		verify(panel, never()).applyOptions(any());
	}

	/**
	 * AO1: {@code opt=holding} is REMOVED, and answers one sentence that names the addendum and says what to
	 * expect instead - a row shows the stack AND one item since addendum AN, and the gp column always compares
	 * the stack. It is not "opt= wants cash, untradeables, ..." and not a no-op, for the reason {@code order=}
	 * is not: the word is in the Q, T, U, V, Y and Z live acceptance lists and in whatever the operator has in
	 * their shell history, so the one thing it has to do is tell its author where it went.
	 */
	@Test
	public void optHoldingIsGoneAndTheVerbSaysWhatTookItsPlace()
	{
		final JsonObject bad = send("opt=holding");
		assertFalse(bad.get("ok").getAsBoolean());
		assertEquals("opt=holding is gone since addendum AO - a row now shows the stack and one item both,"
			+ " and the gp column always compares the stack", bad.get("error").getAsString());

		// Every spelling the verb used to accept for that switch gets the same sentence, the stored key
		// included, and the text is case-folded the way a hand-typed URL query needs.
		for (String verb : new String[]{"holdings", "stack", "stacks", "holdingonrows", "Holding", "HOLDINGONROWS"})
		{
			assertEquals(verb, bad.get("error").getAsString(), send("opt=" + verb).get("error").getAsString());
		}
		// ...and none of them presses anything: the sidebar is left exactly as it was.
		verify(panel, never()).setOptions(any());
		verify(panel, never()).applyOptions(any());

		// The same word is still a COLUMN. opt= and sort= are different verbs, and addendum W's fourth column
		// is untouched by any of this - which is why the switch's spellings are caught by opt= alone.
		ok("sort=stack");
		verify(panel).clickSort(SortMode.STACK_VALUE);
	}

	/**
	 * Every answer says which of the gear's switches the sidebar is using, so a shot needs no second call - and
	 * unlike {@code hero}, these are also the reason the figures below say what they say.
	 *
	 * <p>There are FIVE keys since addendum AO, not six: {@code holding} went with the switch behind it (AO1),
	 * and the count is asserted so a key cannot be added or lost here without a test saying so.
	 */
	@Test
	public void everyAnswerEchoesTheViewSwitches()
	{
		final JsonObject fresh = ok("state").getAsJsonObject("options");
		assertEquals("cash, untradeables, live, inventory, hover (AO1)", 5, fresh.entrySet().size());
		assertTrue("a panel that has not been asked reads as the default", fresh.get("cash").getAsBoolean());
		assertFalse(fresh.get("untradeables").getAsBoolean());
		assertTrue("live prices are on by default (T1)", fresh.get("live").getAsBoolean());
		assertTrue("and so are the carried items (Y1)", fresh.get("inventory").getAsBoolean());
		assertFalse("the data hovers are the one switch that defaults off (AH)", fresh.get("hover").getAsBoolean());
		assertNull("holding went with the switch addendum AO deleted", fresh.get("holding"));

		when(panel.options()).thenReturn(ViewOptions.DEFAULT
			.withCountCash(false).withCountUntradeables(true).withLivePrices(false));
		final JsonObject some = ok("state").getAsJsonObject("options");
		assertFalse(some.get("cash").getAsBoolean());
		assertTrue(some.get("untradeables").getAsBoolean());
		assertFalse(some.get("live").getAsBoolean());
		assertTrue(some.get("inventory").getAsBoolean());
		assertFalse(some.get("hover").getAsBoolean());
		assertNull(some.get("holding"));
	}

	/** Q7: there is no {@code gear} verb - the menu is a Swing popup, and {@code shot=} is how it is looked at. */
	@Test
	public void thereIsNoGearVerb()
	{
		final JsonObject bad = send("gear");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(),
			bad.get("error").getAsString().contains("unknown command 'gear'"));
		assertTrue("the refusal names the verb that does drive the switches",
			bad.get("error").getAsString().contains("opt="));
	}

	/** O1: the look verb went with the One Bar design, and an old script gets a refusal that names the rest. */
	@Test
	public void theLookVerbIsGone()
	{
		final JsonObject bad = send("look=ticker");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(),
			bad.get("error").getAsString().contains("unknown command 'look'"));
		assertTrue("the refusal names the verbs that are left",
			bad.get("error").getAsString().contains("hero="));
	}

	/**
	 * The band is typed into the Min / Max field and applied as Enter would: an empty value is "no bound", and
	 * text the field refuses is an {@code ok:false} answer that still carries the state.
	 */
	@Test
	public void minAndMaxTypeIntoTheFieldsAndAnEmptyValueMeansNoBound()
	{
		ok("min=100k");
		verify(panel).applyMin("100k");
		ok("max=1.5m");
		verify(panel).applyMax("1.5m");
		ok("min=1,000");
		verify(panel).applyMin("1,000");

		ok("min=");
		verify(panel).applyMin("");
		ok("max");
		verify(panel).applyMax("");

		when(panel.applyMin("abc")).thenReturn(false);
		final JsonObject bad = send("min=abc");
		assertFalse(bad.get("ok").getAsBoolean());
		assertTrue(bad.get("error").getAsString(), bad.get("error").getAsString().contains("the field is red"));
		assertTrue("the refusal carries the state too", bad.has("filter") && bad.has("status"));

		when(panel.applyMax("-5")).thenReturn(false);
		assertFalse(send("max=-5").get("ok").getAsBoolean());
	}

	/**
	 * P3 (live look 3, section 2): a band of NOTHING is typed as nothing, so the field comes back blank on its
	 * placeholder instead of holding a literal "0".
	 *
	 * <p>The verb types its text into the field and the panel only rewrites a field whose text means a DIFFERENT
	 * number than the filter holds - and "0" already means the filter's 0 - so {@code bpm max=0} used to leave a
	 * "0" sitting where the placeholder "max gp" belongs, which is what the operator photographed on 2026-09-10.
	 * The bridge now hands the panel "" for any amount worth nothing, which is the very string the panel writes
	 * for a bound of nothing. Proven on a REAL panel, because the claim is about what the field SHOWS.
	 */
	@Test
	public void aBandOfNothingIsTypedAsNothingSoTheFieldKeepsItsPlaceholder() throws Exception
	{
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service, prefs())));
		final BankPriceMovementPanel p = real.get();
		final BpmCommands bridge = new BpmCommands(p, service, gson, account(), shotDir(), showing::get);
		try
		{
			assertTrue(reply(bridge, "max=1.5m").get("ok").getAsBoolean());
			assertEquals("an amount worth something keeps the operator's own spelling", "1.5m", p.maxField().getText());

			assertTrue(reply(bridge, "max=0").get("ok").getAsBoolean());
			assertEquals("a zero is nothing, and the panel writes nothing as an empty field (P3)",
				"", p.maxField().getText());
			assertTrue("...so \"max gp\" is what the operator sees", p.maxField().placeholderShowing());
			assertEquals("and the filter is on no upper bound, exactly as an empty value leaves it",
				0L, p.filter().gpMax());

			// Every spelling of nothing goes the same way: the test is the parsed VALUE, not the characters.
			reply(bridge, "min=100k");
			assertEquals("100k", p.minField().getText());
			reply(bridge, "min=0k");
			assertEquals("", p.minField().getText());
			assertTrue(p.minField().placeholderShowing());
			assertEquals(0L, p.filter().gpMin());

			// A refusal is untouched: this rule decides how a zero is spelled, never whether a value is legal.
			final JsonObject bad = reply(bridge, "max=abc");
			assertFalse(bad.get("ok").getAsBoolean());
			assertEquals("the operator's own text still reaches the field", "abc", p.maxField().getText());
			assertTrue("...and still turns it red", bad.getAsJsonObject("panel").get("maxInvalid").getAsBoolean());
			assertTrue("the refusal quotes what was typed", bad.get("error").getAsString().contains("'abc'"));
		}
		finally
		{
			MovementRowPanelTest.onEdt(p::stop);
		}
	}

	// ---------------------------------------------------------------- addendum Z: the three price presets

	/**
	 * Z4: {@code presets=} cuts the fold's three quick bands, pressed through {@code setPresets} - the path the
	 * three boxes at the foot of the gear menu take, which applies the trio AND writes it through the
	 * {@code Prefs} seam - so a {@code /bpm} session leaves the same stored line behind as a hand session.
	 *
	 * <p>The value is read by {@link BandPresets#parse(String)}, so any order and any shorthand the Min / Max
	 * fields take work, and the trio arrives sorted; the word {@code default} puts 100k / 1m / 10m back.
	 */
	@Test
	public void presetsCutsTheThreeBandsThroughTheGearsOwnBoxes()
	{
		ok("presets=1m,10m,100m");
		verify(panel).setPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));

		// Any order - the value sorts rather than obeying - and the separators a hand-typed URL query carries.
		ok("presets=100m,1m,10m");
		verify(panel, times(2)).setPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));
		ok("presets=500k 2m 1b");
		verify(panel).setPresets(BandPresets.of(500_000L, 2_000_000L, 1_000_000_000L));
		ok("presets=1,000/2,000/3,000");
		verify(panel).setPresets(BandPresets.of(1_000L, 2_000L, 3_000L));

		// Z2's reset item, as a word.
		ok("presets=default");
		verify(panel).setPresets(BandPresets.DEFAULT);
		ok("presets=reset");
		verify(panel, times(2)).setPresets(BandPresets.DEFAULT);

		// Six presses, every one through the box's own path rather than the bare applyPresets: the difference
		// between the two is whether the line is remembered.
		verify(panel, times(6)).setPresets(any());
		verify(panel, never()).applyPresets(any());
		// ...and not one of them touched the band itself, which is min= / max= and nothing else (Z4).
		verify(panel, never()).applyMin(anyString());
		verify(panel, never()).applyMax(anyString());
	}

	/**
	 * Z4: the three ways to get the line wrong - too few or too many values, a duplicate, a word that is not a
	 * number - all leave the chips exactly as they were and answer one sentence that says what a good line looks
	 * like. It is the boxes' own rule (Z2): a trio the sidebar would paint red is a trio this verb refuses.
	 */
	@Test
	public void anUnreadablePresetsValueIsRefusedAndChangesNothing()
	{
		for (String bad : new String[]{"abc", "1m", "1m,10m", "1m,1m,10m", "0,1m,10m", "-1,1m,10m",
			"1m,10m,100m,1b", ""})
		{
			final JsonObject r = send("presets=" + bad);
			assertFalse("'" + bad + "' must be refused", r.get("ok").getAsBoolean());
			assertEquals("presets= wants three distinct gp values (100k, 1m, 10m), not '" + bad + "'",
				r.get("error").getAsString());
		}
		// A bare verb names no trio either, and quotes the nothing it was given.
		final JsonObject bare = send("presets");
		assertFalse(bare.get("ok").getAsBoolean());
		assertEquals("presets= wants three distinct gp values (100k, 1m, 10m), not ''",
			bare.get("error").getAsString());

		verify(panel, never()).setPresets(any());
		verify(panel, never()).applyPresets(any());
	}

	/**
	 * Z4: every answer says which three bands the fold is offering, as plain gp amounts and smallest first, so a
	 * script can assert what a chip would apply without reading a label. The labels a user actually sees are the
	 * panel's own {@code presetLabels}, beside them in {@code state.panel}.
	 */
	@Test
	public void everyAnswerEchoesTheThreePresets()
	{
		final JsonArray fresh = ok("state").getAsJsonArray("presets");
		assertEquals("a panel that has not been asked reads as the default trio", 3, fresh.size());
		assertEquals(100_000L, fresh.get(0).getAsLong());
		assertEquals(1_000_000L, fresh.get(1).getAsLong());
		assertEquals(10_000_000L, fresh.get(2).getAsLong());

		when(panel.presets()).thenReturn(BandPresets.of(100_000_000L, 1_000_000L, 10_000_000L));
		final JsonArray cut = ok("state").getAsJsonArray("presets");
		assertEquals(1_000_000L, cut.get(0).getAsLong());
		assertEquals(10_000_000L, cut.get(1).getAsLong());
		assertEquals("the echo is ascending, whatever order it was set in", 100_000_000L, cut.get(2).getAsLong());
		// The band is a different thing from what the chips offer, and stays where it was.
		assertEquals(0L, ok("state").getAsJsonObject("filter").get("gpMin").getAsLong());
	}

	// ---------------------------------------------------------------- addendum AA: the price fold

	/**
	 * AA2: {@code fold=} drives the price fold through the band button's own road - {@code pressFold} for the two
	 * states, {@code toggleFold} for the gesture - which is the only road that REMEMBERS the choice (AA1). The two
	 * states are idempotent because the panel writes nothing when nothing changed, so a script may send the same
	 * word as often as it likes without flapping the fifteenth key.
	 */
	@Test
	public void foldOpensAndClosesThroughTheBandButtonsOwnRoad()
	{
		ok("fold=on");
		verify(panel, times(1)).pressFold(true);
		ok("fold=off");
		verify(panel, times(1)).pressFold(false);
		// The same word twice is the same press: the panel is what decides there is nothing to write.
		ok("fold=off");
		verify(panel, times(2)).pressFold(false);

		// The gesture is the click itself and goes through the method the band button calls.
		ok("fold=toggle");
		verify(panel, times(1)).toggleFold();

		// The road is the band button's and nothing else: setFoldOpen is the ConfigChanged road (AA1) and
		// remembers nothing, so a verb that took it would move the sidebar and forget by the relaunch.
		verify(panel, never()).setFoldOpen(anyBoolean());
		// ...and the fold is not the band: opening or closing it writes no bound and re-cuts no chip.
		verify(panel, never()).applyMin(anyString());
		verify(panel, never()).applyMax(anyString());
		verify(panel, never()).setPresets(any());
	}

	/**
	 * AA2: anything but the three words is refused with the fold left exactly as it was, and the refusal NAMES
	 * them - a verb whose vocabulary is three words has no business making an operator read the source.
	 */
	@Test
	public void anUnknownFoldWordIsRefusedAndPressesNothing()
	{
		for (String bad : new String[]{"maybe", "yes", "1d", "open?", "-"})
		{
			final JsonObject r = send("fold=" + bad);
			assertFalse("'" + bad + "' must be refused", r.get("ok").getAsBoolean());
			assertEquals("fold= wants on, off or toggle, not '" + bad + "'", r.get("error").getAsString());
		}
		// A bare verb names no state either, and quotes the nothing it was given.
		final JsonObject bare = send("fold");
		assertFalse(bare.get("ok").getAsBoolean());
		assertEquals("fold= wants on, off or toggle, not ''", bare.get("error").getAsString());

		// Not one of them moved the fold, by any of its three roads.
		verify(panel, never()).pressFold(anyBoolean());
		verify(panel, never()).toggleFold();
		verify(panel, never()).setFoldOpen(anyBoolean());

		// ...while the words themselves are taken whatever case and spacing they arrive in, a URL query being
		// hand-typed.
		ok("fold= OFF ");
		verify(panel, times(1)).pressFold(false);
		ok("FOLD=Toggle");
		verify(panel, times(1)).toggleFold();
	}

	/**
	 * AA2 on a REAL panel: {@code state.panel.foldOpen} is where the answer reads back, and it has been in
	 * {@code describe()} since the fold existed - so the verb adds no echo of its own and one call still proves
	 * both what was asked and what became of it. The live list reads exactly this key.
	 */
	@Test
	public void theFoldVerbMovesTheRealPanelAndTheEchoFollows() throws Exception
	{
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service,
			prefs())));
		final BpmCommands bridge = new BpmCommands(real.get(), service, gson, account(), shotDir(), showing::get);
		try
		{
			// The seam above remembers nothing, so this is the FRESH-INSTALL reading (loadFoldOpen answers null).
			assertTrue("a fresh sidebar opens on the fold (AA1)",
				ok(bridge, "state").getAsJsonObject("panel").get("foldOpen").getAsBoolean());

			assertFalse(ok(bridge, "fold=off").getAsJsonObject("panel").get("foldOpen").getAsBoolean());
			assertFalse("...and asking again leaves it there",
				ok(bridge, "fold=off").getAsJsonObject("panel").get("foldOpen").getAsBoolean());
			assertTrue(ok(bridge, "fold=on").getAsJsonObject("panel").get("foldOpen").getAsBoolean());
			assertFalse(ok(bridge, "fold=toggle").getAsJsonObject("panel").get("foldOpen").getAsBoolean());
			assertTrue(ok(bridge, "fold=toggle").getAsJsonObject("panel").get("foldOpen").getAsBoolean());

			// A refusal does not move it, and the state comes back all the same.
			assertFalse(reply(bridge, "fold=maybe").get("ok").getAsBoolean());
			assertTrue(ok(bridge, "state").getAsJsonObject("panel").get("foldOpen").getAsBoolean());
		}
		finally
		{
			MovementRowPanelTest.onEdt(() -> real.get().stop());
		}
	}

	/**
	 * C40's "through the same code path the widgets use, so prefs.save runs", proven on a REAL panel: every
	 * filter verb lands in the recording {@link BankPriceMovementPanel.Prefs} and in {@code service.setFilter}
	 * exactly as a click does, and a second {@code sort=} on the lit chip flips the direction like a second click.
	 */
	@Test
	public void theFilterVerbsPressTheRealPanelsWidgetsSoTheConfigWriteHappens() throws Exception
	{
		final List<RowFilter> saved = new ArrayList<>();
		final BankPriceMovementPanel.Prefs prefs = new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return RowFilter.DEFAULT;
			}

			@Override
			public void save(RowFilter filter)
			{
				saved.add(filter);
			}
		};
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service, prefs)));
		final BpmCommands bridge = new BpmCommands(real.get(), service, gson, account(), shotDir(), showing::get);
		try
		{
			// Not 1d: that is already the default, and an unchanged filter is deliberately not saved again.
			assertTrue(reply(bridge, "window=30d").get("ok").getAsBoolean());
			assertEquals(MovementWindow.D30, last(saved).window());
			verify(service).setFilter(RowFilter.DEFAULT.withWindow(MovementWindow.D30));

			reply(bridge, "sort=gp");
			assertEquals(SortMode.GP_MOVE, last(saved).sort());
			assertTrue("a column that was not lit opens biggest first (W3)", last(saved).descending());
			reply(bridge, "sort=gp");
			assertFalse("the lit column flips, as a second click does", last(saved).descending());
			reply(bridge, "dir=desc");
			assertTrue(last(saved).descending());

			reply(bridge, "min=100k");
			assertEquals(100_000L, last(saved).gpMin());
			reply(bridge, "max=1.5m");
			assertEquals(1_500_000L, last(saved).gpMax());
			assertEquals("the band accumulates on the panel's own filter", 100_000L, last(saved).gpMin());

			final int writes = saved.size();
			final JsonObject bad = reply(bridge, "min=abc");
			assertFalse(bad.get("ok").getAsBoolean());
			assertEquals("a refused amount writes nothing", writes, saved.size());
			assertTrue("the refusal carries the state, and the panel says the field is red",
				bad.getAsJsonObject("panel").get("minInvalid").getAsBoolean());

			reply(bridge, "min=");
			assertEquals("an empty value is no bound", 0L, last(saved).gpMin());
			assertFalse(reply(bridge, "state").getAsJsonObject("panel").get("minInvalid").getAsBoolean());

			// AS8: no bank is open here, so the link is the price re-check alone, pressed OUT LOUD - refreshNow(false),
			// which on this mock is a different call from the no-argument form the service keeps as its delegate.
			reply(bridge, "refresh");
			verify(service).refreshNow(false);
			verify(service, never()).refreshNow(true);
			assertEquals("every save was also a setFilter", saved.size(), realSetFilterCalls());
		}
		finally
		{
			MovementRowPanelTest.onEdt(() -> real.get().stop());
		}
	}

	/**
	 * The same promise for the four COLUMNS, on a REAL panel (W4): {@code sort=} goes through the sort button's
	 * own {@code clickSort}, so one verb is one press - a {@code prefs.save} and a {@code service.setFilter} -
	 * and naming the column that is already lit turns the arrow over instead of writing the same filter twice
	 * (W2, C29). Which direction a FRESH column opens on is the button's own rule (W3) and is pinned where the
	 * button is; this test only requires that pressing it again reverses whatever it opened on.
	 */
	@Test
	public void aColumnVerbPressesTheRealPanelsSortButtonAndTheLitOneFlipsIt() throws Exception
	{
		final List<RowFilter> saved = new ArrayList<>();
		final BankPriceMovementPanel.Prefs prefs = new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return RowFilter.DEFAULT;
			}

			@Override
			public void save(RowFilter filter)
			{
				saved.add(filter);
			}
		};
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service, prefs)));
		final BpmCommands bridge = new BpmCommands(real.get(), service, gson, account(), shotDir(), showing::get);
		try
		{
			// RowFilter.DEFAULT is (PERCENT_MOVE, descending), so this names the column that is already lit.
			assertTrue(reply(bridge, "sort=percent").get("ok").getAsBoolean());
			assertEquals("one verb is one press", 1, saved.size());
			assertEquals(SortMode.PERCENT_MOVE, last(saved).sort());
			assertFalse("the lit column again flips the direction (W2)", last(saved).descending());

			// A column that is not lit lands on that column, in whatever direction the button opens it on.
			reply(bridge, "sort=stack");
			assertEquals(2, saved.size());
			assertEquals(SortMode.STACK_VALUE, last(saved).sort());
			final boolean opened = last(saved).descending();

			// ...and pressing IT again turns that over in its turn - the label spells the same column.
			reply(bridge, "sort=Stack price");
			assertEquals(3, saved.size());
			assertEquals(SortMode.STACK_VALUE, last(saved).sort());
			assertEquals(!opened, last(saved).descending());

			assertEquals("every save was also a setFilter", saved.size(), realSetFilterCalls());
		}
		finally
		{
			MovementRowPanelTest.onEdt(() -> real.get().stop());
		}
	}

	private int realSetFilterCalls()
	{
		final ArgumentCaptor<RowFilter> captor = ArgumentCaptor.forClass(RowFilter.class);
		verify(service, org.mockito.Mockito.atLeast(0)).setFilter(captor.capture());
		return captor.getAllValues().size();
	}

	// ---------------------------------------------------------------- the other verbs

	@Test
	public void refreshAndMorePressTheTwoButtons()
	{
		ok("refresh");
		verify(panel).refreshNow();
		// AS, AS8: the verb presses the LINK and nothing else, because the panel decides what a click is - with the
		// bank open the plugin's read of the items and THEN the price re-check, quietly; with it closed the price
		// re-check alone, out loud. A bridge that called the service itself would skip the items, and could put the
		// wait line up in the middle of a bank visit. Both forms are checked: since AS8 the panel presses
		// refreshNow(boolean), so a never() on the no-argument form alone would pass whatever the bridge pressed.
		verify(service, never()).refreshNow();
		verify(service, never()).refreshNow(anyBoolean());
		ok("more");
		verify(panel).showMore();
	}

	@Test
	public void aSyntheticBankFillsThePanelWithoutABankAndSaysSoInState()
	{
		final JsonObject s = ok("bank=4151:2;385:1000");
		assertTrue("the rows are made up and state must admit it", s.get("syntheticBank").getAsBoolean());

		final ArgumentCaptor<BankSnapshot> pushed = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(pushed.capture(), eq(false));
		final BankSnapshot snapshot = pushed.getValue();
		assertEquals(2, snapshot.items.size());
		assertEquals(4151, snapshot.items.get(0).id);
		assertEquals(2, snapshot.items.get(0).quantity);
		assertEquals("item 4151", snapshot.items.get(0).name);
		assertEquals(385, snapshot.items.get(1).id);
		assertEquals(1000, snapshot.items.get(1).quantity);
		assertTrue("a stack of 1000 has to draw as a stack", snapshot.items.get(1).stackable);
		// The LIVE account: PriceService.setLoggedIn keeps the snapshot it holds only while belongsTo(bank,
		// hash, profile) is true, so rows stamped with zeros would be thrown away by the next login or hop -
		// and, since this push is persisted like any other, the developer's own bank file is what it lands in
		// (dev mode only; a live session opens a bank afterwards to put the real one back).
		assertEquals(4242L, snapshot.accountHash);
		assertEquals("STANDARD", snapshot.profileType);
		assertTrue(snapshot.capturedAtMillis > 0);
	}

	/**
	 * B097. The synthetic bank has to obey {@link BankReader}'s own rule for coins and platinum tokens - a WORTH,
	 * never a row - or the bridge can stage a panel the live reader cannot produce, and the one figure the verb
	 * exists to exercise (the card's currency half) is the figure it cannot reach.
	 */
	@Test
	public void aSyntheticBanksCurrencyBecomesItsWorthAndNotARow()
	{
		ok("bank=4151:2;995:1000;13204:5");

		final ArgumentCaptor<BankSnapshot> pushed = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(pushed.capture(), eq(false));
		final BankSnapshot snapshot = pushed.getValue();
		assertEquals("only the whip is a row", 1, snapshot.items.size());
		assertEquals(4151, snapshot.items.get(0).id);
		assertEquals("1000 coins and 5 platinum", 6_000L, snapshot.currencyGp);
	}

	/** ...and a bank of nothing but cash is a legal bank, which is the extreme case the card has to survive. */
	@Test
	public void aSyntheticBankOfNothingButCurrencyIsAccepted()
	{
		assertTrue(ok("bank=995:200000000").get("syntheticBank").getAsBoolean());

		final ArgumentCaptor<BankSnapshot> pushed = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(pushed.capture(), eq(false));
		assertTrue("no rows at all", pushed.getValue().items.isEmpty());
		assertEquals(200_000_000L, pushed.getValue().currencyGp);
	}

	/**
	 * P1's live step, through the verb an operator actually types: {@code bank=4151:2;995:200000000} stages two
	 * whips and 200m in coins, and the bank that comes out is worth BOTH - 2 x the guide price + 200,000,000 -
	 * with the coins nowhere among the rows. The valuation is {@link PortfolioMath#summarise}, the very call
	 * {@code PriceService.finish} makes over the snapshot this verb pushed, so the figure asserted here is the
	 * figure the card prints and the figure {@code jq .result.portfolio.valueNow} reads back.
	 */
	@Test
	public void aSyntheticBankOfAStackAndCoinsIsWorthBothTogether()
	{
		assertTrue(ok("bank=4151:2;995:200000000").get("syntheticBank").getAsBoolean());

		final ArgumentCaptor<BankSnapshot> pushed = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(pushed.capture(), eq(false));
		final BankSnapshot staged = pushed.getValue();
		assertEquals("the coins are a worth, never a row", 1, staged.items.size());
		assertEquals(4151, staged.items.get(0).id);
		assertEquals(200_000_000L, staged.currencyGp);

		final long price = 1_500_000L;
		final PortfolioSummary summary =
			PortfolioMath.summarise(staged.items, id -> id == 4151 ? price : null, null, null, staged.currencyGp);
		assertEquals("2 x the price + the coins (P1)", 2 * price + 200_000_000L, summary.valueNow());
		assertEquals(200_000_000L, summary.currencyGp());
		assertEquals("one stack, and the coins are not one of them", 1, summary.itemsTotal());
	}

	@Test
	public void aMalformedBankIsRefusedAndNothingIsPushed()
	{
		assertFalse(send("bank=").get("ok").getAsBoolean());
		assertFalse(send("bank=abc:1").get("ok").getAsBoolean());
		assertFalse(send("bank=4151:0").get("ok").getAsBoolean());
		assertFalse(send("bank=-1:5").get("ok").getAsBoolean());
		verify(service, never()).setBank(any(), anyBoolean());

		// A bare id is one of them.
		ok("bank=4151");
		final ArgumentCaptor<BankSnapshot> pushed = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(pushed.capture(), eq(false));
		assertEquals(1, pushed.getValue().items.get(0).quantity);
	}

	/**
	 * The switch goes through the service's pass-through (gate gotcha 20), never a client of the bridge's own.
	 * Under addendum K it turns off the guide HISTORY only: the unit prices come from RuneLite's own table
	 * (K1) and keep arriving, so the rows stay priced and lose their movement. The verb keeps its name.
	 */
	@Test
	public void theWikiSwitchTurnsTheFetchesOffAndOn()
	{
		ok("wiki=off");
		verify(service).setWikiEnabled(false);
		ok("wiki=on");
		verify(service).setWikiEnabled(true);
		assertFalse(send("wiki=maybe").get("ok").getAsBoolean());
		verify(service, times(2)).setWikiEnabled(anyBoolean());
	}

	/**
	 * The {@code bank=} guard is a VALUE test, not only a null test. The plugin zeroes its cached hash on
	 * {@code LOGIN_SCREEN} and {@code HOPPING} and does not restore it until the next login, so a developer who
	 * drives the panel from the login screen has an {@link BpmCommands.Account} that is present and answers 0 -
	 * and the push would then be filed as {@code bank-0-<profile>.json}, the very file the seam exists to
	 * prevent, which no login ever loads and no sweep ever removes. {@code -1} is the same story one step
	 * earlier: {@code Client.getAccountHash()} answers it before the client has logged in at all.
	 */
	@Test
	public void aSyntheticBankIsRefusedWhileNobodyIsLoggedIn()
	{
		for (long hash : new long[]{0L, -1L})
		{
			final BpmCommands loggedOut = new BpmCommands(panel, service, gson, account(hash), shotDir(),
				showing::get);
			final JsonObject r = gson.fromJson(loggedOut.apply("bank=4151:2"), JsonObject.class);
			assertFalse("account " + hash + " is nobody", r.get("ok").getAsBoolean());
			assertEquals(BpmCommands.NOT_LOGGED_IN, r.get("error").getAsString());
		}
		verify(service, never()).setBank(any(), anyBoolean());

		// ...and the same verb with somebody logged in goes through, so the guard is the hash and nothing else.
		assertTrue(ok("bank=4151:2").get("syntheticBank").getAsBoolean());
		verify(service).setBank(any(), eq(false));
	}

	/**
	 * The contract's three-argument constructor serves every verb that needs no seam of its own: {@code bank=} is
	 * refused because there is no live account to stamp the snapshot with, and since addendum AD {@code shot} is
	 * refused too because there is no directory to write into - that one is pinned on its own, just below, since
	 * it is a rule about a file rather than about the account.
	 */
	@Test
	public void theContractConstructorRefusesTheSyntheticBankAndServesTheRestOfTheVerbs()
	{
		final BpmCommands bare = new BpmCommands(panel, service, gson);
		final JsonObject noBank = gson.fromJson(bare.apply("bank=4151:1"), JsonObject.class);
		assertFalse(noBank.get("ok").getAsBoolean());
		assertTrue(noBank.get("error").getAsString(), noBank.get("error").getAsString().contains("account"));
		verify(service, never()).setBank(any(), anyBoolean());

		assertTrue(gson.fromJson(bare.apply("wiki=off"), JsonObject.class).get("ok").getAsBoolean());
		verify(service).setWikiEnabled(false);
		assertTrue(gson.fromJson(bare.apply("window=1h"), JsonObject.class).get("ok").getAsBoolean());
		verify(panel).selectWindow(MovementWindow.D1);
	}

	// ---------------------------------------------------------------- addendum AS: the bank hold

	/**
	 * AS (plan 7.3, "BpmCommands"): {@code state.panel.bank} is the bank hold exactly as the panel's
	 * {@code describe()} wrote it - {@code {open, pending, glow, heldEvents, reads}} - and it reaches the answer with
	 * no key of this class's own, because {@code describe()} is nested whole. The live run reads nothing else to
	 * prove the hold ({@code bpm state | jq -c .result.panel.bank}), so this pins what that line needs of the bridge:
	 * the object is there, under that name, with those five keys, on every answer.
	 *
	 * <p>The raw answer is compared as TEXT as well as read as a tree, because the tree reading alone cannot see the
	 * one way a pass-through can go wrong without losing a key: a {@code Map} in place of the {@code JsonElement} in
	 * {@code parse} turns every number into a double, and {@code heldEvents} would print as {@code 3.0} - measured on
	 * the client's own Gson 2.8.5 - while {@code getAsInt()} went on answering 3.
	 */
	@Test
	public void stateEchoesThePanelsBankHoldWholeUnderPanelBank()
	{
		when(panel.describe()).thenReturn("{\"card\":\"LIST\",\"shown\":250,\"foldOpen\":true,"
			+ "\"bank\":{\"open\":true,\"pending\":true,\"glow\":true,\"heldEvents\":3,\"reads\":1},"
			+ "\"problemText\":\"\"}");

		final String raw = dev.apply("state");
		assertTrue("the hold passes through verbatim - same keys, same order, integers as integers: " + raw,
			raw.contains("\"bank\":{\"open\":true,\"pending\":true,\"glow\":true,\"heldEvents\":3,\"reads\":1}"));
		final JsonObject s = gson.fromJson(raw, JsonObject.class);
		assertTrue(s.get("ok").getAsBoolean());
		assertBankHold("state", s, true, true, true, 3, 1);
		// ...and the keys beside it are untouched: the hold is one more key of describe(), not a new shape.
		assertEquals("LIST", s.getAsJsonObject("panel").get("card").getAsString());
		assertTrue(s.getAsJsonObject("panel").get("foldOpen").getAsBoolean());

		// Every verb carries it, like the rest of the state (C40) - and so does a refusal that carries the state.
		assertBankHold("window=7d", ok("window=7d"), true, true, true, 3, 1);
		when(panel.applyMin("abc")).thenReturn(false);
		final JsonObject refused = send("min=abc");
		assertFalse(refused.get("ok").getAsBoolean());
		assertBankHold("a refused min=", refused, true, true, true, 3, 1);
	}

	/**
	 * AS on a REAL panel: the object the live run reads is the one the panel actually writes, not the stub above.
	 * The plugin speaks to the panel through {@code setBankHold(open, pending, heldEvents, reads)} and through
	 * nothing else (plan AS, 7.3), so this drives that seam and reads the answer back through the bridge: the four
	 * mirrored values arrive unchanged, {@code glow} follows the gate the plan names
	 * ({@code active && open && pending}), and describe()'s hand-written line is still valid JSON - a comma or a
	 * quote lost there would leave {@code state.panel} a bare string, and {@code jq .result.panel.bank} answering
	 * nothing on the live run.
	 *
	 * <p>The walk is one bank visit as the recipe in {@code docs/effect-lab.md} 3.3 reads it: a change held, the
	 * sidebar moved away and back (the light stops while nobody is looking and comes back with the panel, because
	 * the plan runs it "only while" all three hold), the Refresh link's read with the bank still open (the change is
	 * no longer pending, so the light is out while {@code open} stays true), and the close.
	 */
	@Test
	public void theRealPanelsBankHoldReachesStatePanelBank() throws Exception
	{
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service,
			prefs())));
		final BankPriceMovementPanel p = real.get();
		final BpmCommands bridge = new BpmCommands(p, service, gson, account(), shotDir(), showing::get);
		try
		{
			// Before the plugin has said anything: no bank known to be open, nothing held, nothing read.
			assertBankHold("a fresh panel", ok(bridge, "state"), false, false, false, 0, 0);

			// The first change of a visit, as the plugin reports it: open, pending, three events held, one read.
			MovementRowPanelTest.onEdt(p::onActivate);
			MovementRowPanelTest.onEdt(() -> p.setBankHold(true, true, 3, 1));
			assertBankHold("open with a change held", ok(bridge, "state"), true, true, true, 3, 1);

			// The sidebar moves to another panel: the light stops, and the change is still held...
			MovementRowPanelTest.onEdt(p::onDeactivate);
			assertBankHold("hidden", ok(bridge, "state"), true, true, false, 3, 1);
			// ...and it comes back with the panel, because nothing about the hold has changed.
			MovementRowPanelTest.onEdt(p::onActivate);
			assertBankHold("shown again", ok(bridge, "state"), true, true, true, 3, 1);

			// The Refresh link's read with the bank still open: one more read, nothing pending, the light out.
			MovementRowPanelTest.onEdt(() -> p.setBankHold(true, false, 3, 2));
			assertBankHold("read with the bank open", ok(bridge, "state"), true, false, false, 3, 2);

			// The close, with nothing left to read.
			MovementRowPanelTest.onEdt(() -> p.setBankHold(false, false, 3, 2));
			assertBankHold("closed", ok(bridge, "state"), false, false, false, 3, 2);
		}
		finally
		{
			MovementRowPanelTest.onEdt(p::stop);
		}
	}

	/**
	 * AS8: {@code refresh} is the Refresh LINK, and one click on it refreshes everything wherever the player stands -
	 * the user, on the AS7 build: "manually clicking the refresh button should refresh everything for the user". With
	 * the bank open that is the plugin's local read of the bank FIRST and THEN the price re-check, pressed QUIETLY
	 * ({@code service.refreshNow(true)}): the click has just redrawn the items, so a cooldown refusal of the download
	 * must say nothing. That flag is what keeps the live run's "a second tap with the bank open draws no cooldown line"
	 * true now that the tap does reach the price re-check; until AS8 it held because the tap never did, which is what
	 * this test pinned as {@code refreshWithTheBankOpenIsTheLinksLocalReadAndNeverThePriceRecheck}. With the bank
	 * closed the same verb is the price re-check alone, OUT LOUD ({@code refreshNow(false)}), and the hook is left
	 * alone: nothing else refreshed, so there a refusal is the whole answer and says so.
	 *
	 * <p>The panel hands the first half to whatever the plugin registered through {@code setBankRefresh} (in the
	 * client, a hop to the client thread that reads the bank once) and the second to the service, which is the mock
	 * here - so the flag it is handed IS what the problem row would say. Both halves write into one log, so the ORDER
	 * is pinned with the counts. The second tap is sent with the change no longer pending, which is what the plugin
	 * reports after the first tap's read, because the routing is "the bank is open" and not "a change is held" (plan
	 * AS, 7.3).
	 *
	 * <p>Planted bugs this catches: the price half left out with the bank open, AS4's split (the log stops at
	 * "items"); a panel that ignores the hook (no "items"); the prices before the items (the log's order); the
	 * open-bank price half pressed out loud, which puts the wait line under a list the tap has just redrawn ("prices,
	 * out loud" in the log, and {@code never().refreshNow(false)}); a tap that reads the items only while a change is
	 * pending (the second tap); the closed-bank tap pressed quietly, or running the hook as well (the last entry and
	 * the counts); a bridge that presses the service beside the link (an entry too many).
	 */
	@Test
	public void refreshWithTheBankOpenIsTheLocalReadAndThenTheQuietPriceRecheck() throws Exception
	{
		final AtomicReference<BankPriceMovementPanel> real = new AtomicReference<>();
		MovementRowPanelTest.onEdt(() -> real.set(new BankPriceMovementPanel(mock(ItemManager.class), service,
			prefs())));
		final BankPriceMovementPanel p = real.get();
		final BpmCommands bridge = new BpmCommands(p, service, gson, account(), shotDir(), showing::get);
		// One log for both halves, in the order they ran: the hook writes "items", the mocked service writes how the
		// price re-check was pressed.
		final List<String> halves = Collections.synchronizedList(new ArrayList<>());
		doAnswer(invocation ->
		{
			final boolean quiet = invocation.getArgument(0);
			halves.add(quiet ? "prices, quietly" : "prices, out loud");
			return null;
		}).when(service).refreshNow(anyBoolean());
		try
		{
			MovementRowPanelTest.onEdt(() ->
			{
				p.setBankRefresh(() -> halves.add("items"));
				p.setBankHold(true, true, 1, 0);
			});

			ok(bridge, "refresh");
			assertEquals("the glowing link's click is the plugin's local read and THEN the quiet price re-check",
				Arrays.asList("items", "prices, quietly"), new ArrayList<>(halves));
			verify(service, times(1)).refreshNow(true);
			verify(service, never()).refreshNow(false);
			assertFalse("the click puts the light out at once, before the plugin's notice arrives",
				bankOf(ok(bridge, "state")).get("glow").getAsBoolean());

			// The plugin's notice after that read - still open, nothing pending - and a second tap in the same visit,
			// inside the 30 s the first one started: both halves again, and the price half still quiet, which is
			// the live run's "no wait line on a second tap" now that the tap reaches the price re-check.
			MovementRowPanelTest.onEdt(() -> p.setBankHold(true, false, 1, 1));
			ok(bridge, "refresh");
			assertEquals("a tap with the bank open is both halves whether or not a change is held",
				Arrays.asList("items", "prices, quietly", "items", "prices, quietly"), new ArrayList<>(halves));
			verify(service, times(2)).refreshNow(true);
			verify(service, never()).refreshNow(false);

			// The bank closes: the same verb is the price re-check alone, out loud, and the hook is left alone.
			MovementRowPanelTest.onEdt(() -> p.setBankHold(false, false, 1, 1));
			ok(bridge, "refresh");
			assertEquals("with the bank shut a tap is the price re-check alone, out loud",
				Arrays.asList("items", "prices, quietly", "items", "prices, quietly", "prices, out loud"),
				new ArrayList<>(halves));
			verify(service, times(1)).refreshNow(false);
			verify(service, times(2)).refreshNow(true);
		}
		finally
		{
			MovementRowPanelTest.onEdt(p::stop);
		}
	}

	// ---------------------------------------------------------------- the picture

	@Test
	public void shotIsRefusedWhileTheSidebarIsShut()
	{
		showing.set(false);
		final JsonObject r = send("shot");
		assertFalse(r.get("ok").getAsBoolean());
		assertEquals(BpmCommands.NOT_SHOWING, r.get("error").getAsString());
		assertEquals("nothing may be written when the shot is refused", 0, shots.getRoot().list().length);
		assertFalse("and state says which side of the line we are on", ok("state").get("showing").getAsBoolean());
	}

	@Test
	public void shotStacksThePartsIntoOnePngAndAnswersItsSizeAndTheState() throws Exception
	{
		when(panel.shotComponents()).thenReturn(parts(213, 120, 213, 400));

		final JsonObject r = ok("shot=list");
		// Addendum AD: the answer's "path" is still the absolute path, now spelled by Filepath.toString() - and
		// shotFile is what proves it landed INSIDE the directory the bridge was handed rather than anywhere else.
		final Filepath png = shotFile(r);
		assertTrue(png.toString(), png.isFile());
		assertTrue("the name the caller asked for is in the file name", png.getFileName().startsWith("list-"));
		assertTrue("a PNG with no bytes in it is no picture", png.size() > 0L);
		assertEquals(213, r.get("width").getAsInt());
		assertEquals("the whole sidebar, not the viewport", 520, r.get("height").getAsInt());
		final BufferedImage image = readPng(png);
		assertEquals(213, image.getWidth());
		assertEquals(520, image.getHeight());
		assertTrue("every verb answers the state as well (C40)", r.has("filter") && r.has("status") && r.has("rows"));

		// Two shots in the same second do not overwrite each other.
		final Filepath second = shotFile(ok("shot=list"));
		assertTrue(second.toString(), second.isFile());
		assertFalse(second.getFileName().equals(png.getFileName()));
	}

	/**
	 * Addendum AD: a bridge built without a directory cannot take a shot, and SAYS so. Only the plugin can ask
	 * RuneLite for a data directory ({@code Plugin.getPluginDirectory()} is protected), so the contract's
	 * three-argument constructor hands the bridge a seam that throws - and the sentence an operator reads names
	 * the reason rather than a bare {@code IOException} they would have to decode. The sidebar is deliberately
	 * SHOWING and the parts deliberately sized here, so the refusal can only be about the missing directory.
	 */
	@Test
	public void aBridgeWithNoDirectoryRefusesTheShotRatherThanWritingAnywhere()
	{
		when(panel.shotComponents()).thenReturn(parts(213, 120));
		// The bare constructor asks the panel itself, and a panel no window ever realised answers false.
		when(panel.isShowing()).thenReturn(true);
		final BpmCommands bare = new BpmCommands(panel, service, gson);

		final JsonObject r = gson.fromJson(bare.apply("shot=nowhere"), JsonObject.class);
		assertFalse("a shot with nowhere to go is an answer, never a throw", r.get("ok").getAsBoolean());
		assertTrue(r.get("error").getAsString(), r.get("error").getAsString().contains(BpmCommands.NO_SHOT_DIR));
		assertFalse("...and it names no file, because there is none", r.has("path"));
		assertEquals("nothing may be written when there is nowhere to write it", 0, shots.getRoot().list().length);
	}

	@Test
	public void aPartWithNoSizeIsRefusedRatherThanPrintedBlank()
	{
		when(panel.shotComponents()).thenReturn(parts(213, 0));
		final JsonObject r = send("shot");
		assertFalse(r.get("ok").getAsBoolean());
		assertTrue(r.get("error").getAsString(), r.get("error").getAsString().contains("no size"));

		when(panel.shotComponents()).thenReturn(Collections.emptyList());
		assertFalse(send("shot").get("ok").getAsBoolean());
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * Where {@code shot} writes: the {@link TemporaryFolder}, as the sandboxed {@link Filepath} the bridge takes
	 * since addendum AD. It is a SUPPLIER rather than a value because that is the shape the bridge holds - in the
	 * client it is {@code getPluginDirectory().join(SHOT_DIR)}, asked for when a shot is taken rather than at
	 * construction, so a disk that says no becomes {@code shot}'s own error answer instead of a plugin that will
	 * not start. Handing each bridge its own supplier over the same folder keeps every test's shots together
	 * where {@link #shotFile(JsonObject)} can find them.
	 */
	private PriceStore.Directory shotDir()
	{
		return () -> TestFilepaths.rooted(shots.getRoot());
	}

	/**
	 * The file a {@code shot} answer names, as the {@link Filepath} it now is. The answer still carries the
	 * absolute path ({@code shot.file.toString()}), and this reads it back the one way that also PROVES the port:
	 * the path must start inside the directory the bridge was handed - {@code SHOT_DIR} is now just "shots" and it
	 * is the plugin that joins it onto its own data directory, so nothing here may expect a folder name of its own -
	 * and the file is then reached by joining onto that root rather than by building a bare {@code File}.
	 */
	private Filepath shotFile(JsonObject answer)
	{
		final Filepath dir = TestFilepaths.rooted(shots.getRoot());
		final String path = answer.get("path").getAsString();
		final String prefix = dir.toString() + File.separator;
		assertTrue("the shot must land in the directory the bridge was given (" + prefix + "): " + path,
			path.startsWith(prefix));
		return dir.join(path.substring(prefix.length()));
	}

	/**
	 * The picture inside a written shot, read through the {@link Filepath} instead of around it - the stream
	 * overload rather than {@code ImageIO.read(File)}, which is the same choice {@code writeShot} makes on the
	 * writing side. A stream ImageIO cannot decode answers null, so that is said out loud rather than arriving as
	 * an NPE two lines later.
	 */
	private static BufferedImage readPng(Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			final BufferedImage image = ImageIO.read(in);
			assertNotNull("the shot must be a readable PNG: " + file, image);
			return image;
		}
	}

	/** Sized (but never realised) panels standing in for the header and the scroll pane's view. */
	private static List<Component> parts(int... widthHeightPairs)
	{
		final List<Component> out = new ArrayList<>();
		for (int i = 0; i + 1 < widthHeightPairs.length; i += 2)
		{
			final JPanel p = new JPanel();
			p.setSize(widthHeightPairs[i], widthHeightPairs[i + 1]);
			out.add(p);
		}
		return out;
	}

	/**
	 * The days a live publish holds per window (U1, U2): {@link #LIVE_DAY} minus the window's own span, except
	 * 90d, which fell back one further day because the wiki had not closed 14 Jun when the bucket was wanted, and
	 * 180d, which has no traded bucket at all and is therefore absent - a missing key and a null value are the
	 * same answer to {@code LiveStatus}, and both must reach {@code jq} as {@code null}.
	 */
	private static Map<MovementWindow, LocalDate> liveWindowDays()
	{
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, LIVE_DAY.minusDays(1));
		days.put(MovementWindow.D7, LIVE_DAY.minusDays(7));
		days.put(MovementWindow.D30, LIVE_DAY.minusDays(30));
		days.put(MovementWindow.D90, LIVE_DAY.minusDays(91));
		return days;
	}

	/**
	 * One ISO day out of a JSON object, reading an ABSENT key and a JSON {@code null} as the same nothing - which
	 * they are to the operator, because the injected Gson drops a null field and {@code jq} answers {@code null}
	 * for both. Every live-acceptance step that reads a day is written in {@code jq}, so the tests read it the
	 * same way rather than pinning which of the two spellings this build happens to produce.
	 */
	@Nullable
	private static String isoOrNull(JsonObject o, String key)
	{
		final JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	/**
	 * {@code state.panel.bank} out of one answer - the path {@code jq .result.panel.bank} reads on the live run,
	 * less the lab's {@code result} wrapper - failing with what WAS there when it is missing or is not an object,
	 * because "panel is a quoted string" and "panel has no bank" are different faults in different files.
	 */
	private static JsonObject bankOf(JsonObject answer)
	{
		final JsonElement panelJson = answer.get("panel");
		assertNotNull("no panel in " + answer, panelJson);
		assertTrue("state.panel must be an object, not a quoted string: " + panelJson, panelJson.isJsonObject());
		final JsonElement bank = panelJson.getAsJsonObject().get("bank");
		assertNotNull("no bank in state.panel: " + panelJson, bank);
		assertTrue("state.panel.bank must be an object: " + bank, bank.isJsonObject());
		return bank.getAsJsonObject();
	}

	/**
	 * One {@code state.panel.bank} against what the plugin said and the panel should draw. The NAMES first, so a
	 * sixth key, a lost one or a misspelling fails on the names; then the TYPES, so a flag written as 1 / 0 or a
	 * counter written as a string cannot pass as its value; then the values, each with the moment it was taken.
	 */
	private static void assertBankHold(String when, JsonObject answer, boolean open, boolean pending, boolean glow,
		int heldEvents, int reads)
	{
		final JsonObject bank = bankOf(answer);
		assertEquals(when + ": the five keys of plan AS 7.3 and no others", BANK_KEYS, new TreeSet<>(bank.keySet()));
		assertFlag(when, bank, "open", open);
		assertFlag(when, bank, "pending", pending);
		assertFlag(when, bank, "glow", glow);
		assertCount(when, bank, "heldEvents", heldEvents);
		assertCount(when, bank, "reads", reads);
	}

	private static void assertFlag(String when, JsonObject bank, String key, boolean expected)
	{
		final JsonElement e = bank.get(key);
		assertTrue(when + ": " + key + " must be a JSON boolean: " + bank,
			e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean());
		assertEquals(when + ": " + key + " in " + bank, expected, e.getAsBoolean());
	}

	private static void assertCount(String when, JsonObject bank, String key, int expected)
	{
		final JsonElement e = bank.get(key);
		assertTrue(when + ": " + key + " must be a JSON number: " + bank,
			e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber());
		assertEquals(when + ": " + key + " in " + bank, expected, e.getAsInt());
	}

	/**
	 * A mocked {@code Status}: every addendum so far has added fields to its constructor and another agent owns
	 * that class, so stubbing the getters keeps this file off an argument list it has no business pinning.
	 * ({@code Status} is deliberately not final for exactly this.)
	 */
	private PriceService.Status status()
	{
		final PriceService.Status s = mock(PriceService.Status.class);
		when(s.text()).thenReturn(STATUS_TEXT);
		when(s.pricesAtMillis()).thenReturn(1_600_000_000_000L);
		when(s.bankAtMillis()).thenReturn(1_599_996_400_000L);
		when(s.bankLoaded()).thenReturn(true);
		when(s.loggedIn()).thenReturn(true);
		when(s.totalRows()).thenReturn(613);
		when(s.bankItems()).thenReturn(700);
		when(s.window()).thenReturn(MovementWindow.D1);
		when(s.baselineLoaded()).thenReturn(true);
		when(s.anchorDay()).thenReturn(ANCHOR_DAY);
		when(s.agree()).thenReturn(AGREE);
		when(s.r0Day()).thenReturn(R0_DAY);
		when(s.thenDay()).thenReturn(THEN_DAY);
		when(s.thenRevId()).thenReturn(REV_ID);
		when(s.mappingAtMillis()).thenReturn(MAPPING_AT);
		// The four the bridge echoes and nothing used to stub, so all four read as a never-wired field would
		// (see stateEchoesTheSourceTheIndexAgeAndTheProblemSeparatelyFromTheHeader). problem() is deliberately
		// NOT stubbed here: this fixture is the HEALTHY status - a baseline is loaded - and a healthy status
		// has no problem sentence (PriceService.problemLocked answers null once the window has a revision).
		when(s.source()).thenReturn(MovementRow.PriceSource.GUIDE);
		when(s.agreeSamples()).thenReturn(AGREE_SAMPLES);
		when(s.indexAtMillis()).thenReturn(INDEX_AT);
		return s;
	}

	/**
	 * A {@link BankPriceMovementPanel.Prefs} that starts on the default filter and forgets every save - for the
	 * real-panel tests whose subject is what a WIDGET shows rather than what was written to the config.
	 */
	private static BankPriceMovementPanel.Prefs prefs()
	{
		return new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return RowFilter.DEFAULT;
			}

			@Override
			public void save(RowFilter filter)
			{
			}
		};
	}

	private static BpmCommands.Account account()
	{
		return account(4242L);
	}

	/** The seam with a chosen hash: 0 and -1 are the two ways the client says "nobody is logged in". */
	private static BpmCommands.Account account(long hash)
	{
		return new BpmCommands.Account()
		{
			@Override
			public long hash()
			{
				return hash;
			}

			@Override
			public String profileType()
			{
				return "STANDARD";
			}
		};
	}

	private static RowFilter last(List<RowFilter> saved)
	{
		assertFalse("nothing was written to the config", saved.isEmpty());
		return saved.get(saved.size() - 1);
	}

	private JsonObject reply(BpmCommands bridge, String cmd)
	{
		final String s = bridge.apply(cmd);
		assertNotNull("the bridge always answers", s);
		return gson.fromJson(s, JsonObject.class);
	}

	private JsonObject send(String cmd)
	{
		return reply(dev, cmd);
	}

	private JsonObject ok(String cmd)
	{
		return ok(dev, cmd);
	}

	/** The same, for a test that built its own bridge (a recording seam, a shorter timeout). */
	private JsonObject ok(BpmCommands bridge, String cmd)
	{
		final JsonObject r = reply(bridge, cmd);
		assertTrue(cmd + " -> " + r, r.get("ok").getAsBoolean());
		return r;
	}
}
