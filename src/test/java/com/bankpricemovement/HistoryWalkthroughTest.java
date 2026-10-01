package com.bankpricemovement;

import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.invocation.Invocation;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_DAYS;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_GP;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_PCT;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_DAY;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_TOTAL;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Addendum AU, walked through the way players will meet it: day by day, session by session, with the REAL classes -
 * the real {@link PriceService} with its clock moved by hand, the real {@link PriceStore} on a temporary folder that
 * stands for {@code ~/.runelite/plugin-data/bank-portfolio-tracker/}, and the real {@link BankPriceMovementPanel} with
 * the real {@link BankHistoryView} in it, headless. A "launch" is a client start: a NEW store instance over the same
 * folder (so the store's session memory - a failed owner, a backed-up file - starts clean, as a restart makes it), a
 * new service and a new panel, the config carried over in {@link Config} the way RuneLite's config keeps it. Each test
 * pins what the player would SEE - the card's lines, the view's {@code describe()}, the texts of the day rows read off
 * their labels, the chart's readout - and what lies in the history file on disk.
 *
 * <p>Where the build did not do what the plan and the contract say, the test was written to the PLAN's expectation and
 * marked {@code @Ignore} with the defect's id; the final fix round fixed both (AU-W1, s10a2; AU-W2, s10b2), and no
 * test here is ignored. Where the plan does not say what a player should see, the test pins what the build does
 * and names the open question in a comment (s11, s12, s14, s15).
 *
 * <p>Days are the player's LOCAL dates. The JVM's default zone is moved for each test (the panel and the view read
 * {@link ZoneId#systemDefault()}, as the client does) and put back afterwards. 2026-10-01 is a Thursday.
 */
public class HistoryWalkthroughTest
{
	private static final long MAIN = PriceServiceTest.ACCOUNT;
	private static final long ALT = PriceServiceTest.OTHER_ACCOUNT;
	private static final String PROFILE = PriceServiceTest.PROFILE;
	private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
	private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");
	private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;
	private static final int WHIP = PriceServiceTest.WHIP;
	private static final int BOX = PriceServiceTest.BOX;

	private static final LocalDate FRI_25 = LocalDate.of(2026, 9, 25);
	private static final LocalDate MON_28 = LocalDate.of(2026, 9, 28);
	private static final LocalDate TUE_29 = LocalDate.of(2026, 9, 29);
	private static final LocalDate WED_30 = LocalDate.of(2026, 9, 30);
	private static final LocalDate THU = LocalDate.of(2026, 10, 1);
	private static final LocalDate FRI = LocalDate.of(2026, 10, 2);
	private static final LocalDate SAT = LocalDate.of(2026, 10, 3);
	private static final LocalDate MON = LocalDate.of(2026, 10, 5);
	/** The live fixture's own days: {@link PriceServiceTest#T0} is Tue 08 Sep 16:20 in Toronto. */
	private static final LocalDate SEP_7 = PriceServiceTest.SEP_7;
	private static final LocalDate SEP_8 = LocalDate.of(2026, 9, 8);
	private static final LocalDate SEP_9 = LocalDate.of(2026, 9, 9);
	private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
	/** A "today" far enough ahead that seeding a past reading never trips the clock-behind rule. */
	private static final LocalDate SEEDING = LocalDate.of(2030, 1, 1);

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();
	private final Config config = new Config();
	private TimeZone savedZone;
	private ZoneId zone;
	/** This client session's store: a new instance per launch, over the one data folder. */
	private PriceStore disk;
	private PriceService service;
	private BankPriceMovementPanel panel;
	private ItemManager sprites;
	/** The plugin's bank-read counter, as addendum AS hands it to the panel. */
	private int reads;

	/**
	 * RuneLite's config for the plugin, kept across launches: the filter, the switches and the view the panel writes,
	 * and the road the plugin's {@code saveOptions} takes on to the service.
	 */
	private final class Config implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		RowFilter filter;
		ViewOptions options = ViewOptions.DEFAULT;
		/**
		 * The view this scenario's hand presses after each launch: null = Items, the view every panel opens on. Nothing
		 * in Prefs remembers a view since 2026-09-29, so a scenario that pressed History (or says so here) has its hand
		 * press it again once a panel is built - the harness standing in for the reader, not for the plugin.
		 */
		@Nullable
		SidebarView view;
		int optionSaves;

		@Override
		public RowFilter load()
		{
			return filter;
		}

		@Override
		public void save(RowFilter next)
		{
			filter = next;
		}

		@Override
		public ViewOptions loadOptions()
		{
			return options;
		}

		@Override
		public void saveOptions(ViewOptions next)
		{
			options = next;
			optionSaves++;
			// BankPriceMovementPlugin.saveOptions: the keys written under the prefs-writer guard, then the service told.
			service.setOptions(next);
		}
	}

	/** The five lines of the Bank value card, as drawn. */
	private static final class Card
	{
		final String total;
		final String move;
		final String pct;
		final String footnote;
		final String second;

		Card(String total, String move, String pct, String footnote, String second)
		{
			this.total = total;
			this.move = move;
			this.pct = pct;
			this.footnote = footnote;
			this.second = second;
		}

		@Override
		public String toString()
		{
			return "[" + total + " | " + move + " " + pct + " | " + footnote + " | " + second + "]";
		}
	}

	@Before
	public void setUp()
	{
		savedZone = TimeZone.getDefault();
		f.setUp();
		disk = store();
		when(f.store.loadBankHistory(anyLong(), any())).thenAnswer(i ->
			disk.loadBankHistory(i.<Long>getArgument(0), i.<String>getArgument(1)));
		when(f.store.recordBankHistory(anyLong(), any(), any(), any())).thenAnswer(i ->
			disk.recordBankHistory(i.<Long>getArgument(0), i.<String>getArgument(1), i.<BankHistoryPoint>getArgument(2),
				i.<LocalDate>getArgument(3)));
		when(f.store.loadBank(anyLong(), anyString())).thenAnswer(i ->
			disk.loadBank(i.<Long>getArgument(0), i.<String>getArgument(1)));
		doAnswer(i ->
		{
			disk.saveBank(i.<BankSnapshot>getArgument(0));
			return null;
		}).when(f.store).saveBank(any());
		sprites = mock(ItemManager.class);
		when(sprites.getImage(anyInt(), anyInt(), anyBoolean()))
			.thenAnswer(i -> LookRenderer.sprite(i.<Integer>getArgument(0)));
		useZone(TORONTO);
	}

	@After
	public void tearDown() throws Exception
	{
		try
		{
			quit();
		}
		finally
		{
			TimeZone.setDefault(savedZone);
		}
	}

	// ================================================================================================ the scenarios

	/**
	 * 1. The update. A 1.0.5 player: a bank stored on disk (captured Monday evening, with a Crystal body - an
	 * untradeable made of tradeable parts - and two Dramen staffs, alch-only), the settings as 1.0.5 kept them
	 * (untradeables OFF, no {@code view} key), no history file. Thursday: launch, log in, open the sidebar on Items,
	 * press History BEFORE the bank is opened - then open the bank.
	 *
	 * <p>Plan 7.5 item 1: a day's reading is any day the player logs in, "whether or not the bank was opened", so the
	 * login alone records Thursday from the stored bank at Thursday's prices; 7.5 item 3: the "No readings yet"
	 * sentence is only for a bank the plugin has never seen, and one reading draws its one point, its readout and its
	 * row, with no sentence. AV: the Crystal body counts with untradeables off; the staffs do not.
	 */
	@Test
	public void s01_theUpdateRecordsTheStoredBankAtLoginAndTheBankReadReplacesIt() throws Exception
	{
		f.nameTheSeed();
		final BankSnapshot stored = withDramen(PriceServiceTest.bankWithCrystalBody(at(MON_28, 19, 0)));
		store().saveBank(stored);
		assertFalse("1.0.5 left no history file", historyFile(MAIN).exists());

		f.clock.set(at(THU, 10, 0));
		launch(false);
		login(MAIN);
		openSidebar();

		assertEquals("the sidebar opens on Items", SidebarView.ITEMS, panel.view());
		assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
		assertEquals("Item price changes", caption());
		assertNotNull("AV: the Crystal body is a row with untradeables off",
			PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));
		assertNull("and the alch-only staffs are not", PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.DRAMEN));

		final PriceService.Status atLogin = panel.status();
		final long loginTotal = atLogin.portfolio().valueNow();
		final BankHistoryPoint loginReading = atLogin.bankHistory().on(THU);
		assertNotNull("the login alone is Thursday's reading", loginReading);
		assertEquals("from the stored bank", stored.capturedAtMillis, loginReading.bankAtMillis());
		assertEquals("the parts cell holds the three seeds", 3L * PriceServiceTest.SEED_NOW,
			loginReading.card(BankHistoryPoint.BANK_PARTS));
		assertEquals("and counts under untradeables off: History IS the card", loginTotal,
			loginReading.valueFor(atLogin.options()));
		assertEquals("written at once", Collections.singletonList(loginReading), onDisk(MAIN).points());

		pressView(SidebarView.HISTORY);

		assertEquals("Bank net worth history", caption());
		assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());
		assertNull("one reading: no 'No readings yet' sentence", sentence());
		final Card card = card();
		assertEquals(card.toString(), MovementMath.formatGp(loginTotal), card.total);
		assertEquals(card.toString(), MovementMath.DASH, card.move);
		assertEquals(card.toString(), "1d from 02 Oct", card.footnote);
		assertEquals(card.toString(), "1 day recorded", card.second);
		assertChips(MovementWindow.D1, MovementWindow.D7, MovementWindow.D30, MovementWindow.D90, MovementWindow.D180);
		assertEquals(1, hist("readings"));
		assertEquals("2026-10-01", hist("first"));
		assertEquals("2026-10-01", hist("last"));
		assertEquals("7d", hist("range"));
		assertEquals(Collections.singletonList(firstRow("Thu 01 Oct", loginTotal)), rows());
		assertEquals("Thu 01 Oct", chart(READOUT_DAY));
		assertEquals(MovementMath.formatExact(loginTotal) + " gp", chart(READOUT_TOTAL));
		assertEquals("one point, no change to show", MovementMath.DASH, chart(CHANGE_PCT));
		assertEquals("", chart(CHANGE_GP));
		assertFalse("the lit range is never dimmed", viewChipDimmed(BankHistoryRange.D7));
		assertTrue(viewChipDimmed(BankHistoryRange.D30));
		assertTrue(viewChipDimmed(BankHistoryRange.D90));
		assertFalse("'all' has no start to reach past", viewChipDimmed(BankHistoryRange.ALL));

		// The bank, opened at 10:30: twenty more Mystery boxes than Monday's capture.
		f.clock.set(at(THU, 10, 30));
		final BankSnapshot opened = withQuantity(withDramen(PriceServiceTest.bankWithCrystalBody(f.clock.get())), BOX, 23);
		bankVisit(opened);

		final PriceService.Status afterRead = panel.status();
		final long readTotal = afterRead.portfolio().valueNow();
		assertEquals(opened.capturedAtMillis, afterRead.bankAtMillis());
		assertEquals("twenty boxes at 100 gp", loginTotal + 2_000L, readTotal);
		assertEquals("still ONE day, its row redrawn", Collections.singletonList(firstRow("Thu 01 Oct", readTotal)), rows());
		assertEquals(MovementMath.formatExact(readTotal) + " gp", chart(READOUT_TOTAL));
		assertEquals(MovementMath.formatGp(readTotal), card().total);
		final BankHistoryPoint saved = onDisk(MAIN).on(THU);
		assertEquals("one reading on disk", 1, onDisk(MAIN).size());
		assertEquals("the bank read replaced it", opened.capturedAtMillis, saved.bankAtMillis());
		assertEquals(readTotal, saved.valueFor(afterRead.options()));
		assertEquals("the stored switches were never written", 0, config.optionSaves);
	}

	/**
	 * 2. Day two, day three. Three evenings, a client launch each, the whip's guide price moving from day to day. The
	 * 1d chip fills on day two ("1d vs your 01 Oct total"); the 7d chip stays dimmed with "7d from 08 Oct" when picked
	 * (lit wins over dimmed); the list's lines read "vs 01 Oct", "vs 02 Oct" and "first reading".
	 */
	@Test
	public void s02_dayTwoAndDayThree() throws Exception
	{
		f.runelitePrice(WHIP, 800_000);
		f.clock.set(at(THU, 18, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		pressView(SidebarView.HISTORY);
		final long thu = panel.status().portfolio().valueNow();
		assertEquals("1d from 02 Oct", card().footnote);

		// Day two: the whip up 10,000.
		f.runelitePrice(WHIP, 810_000);
		f.clock.set(at(FRI, 18, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		final long fri = panel.status().portfolio().valueNow();
		assertEquals(10_000L, fri - thu);
		Card card = card();
		assertEquals(card.toString(), "1d vs your 01 Oct total", card.footnote);
		assertEquals(card.toString(), MovementMath.formatDelta(10_000L), card.move);
		assertEquals(card.toString(), MovementMath.formatPct(pct(10_000L, thu), 10_000L), card.pct);
		assertEquals(card.toString(), "2 days recorded since 01 Oct", card.second);
		assertChips(MovementWindow.D1, MovementWindow.D7, MovementWindow.D30, MovementWindow.D90, MovementWindow.D180);
		assertEquals(Arrays.asList(
			readingRow("Fri 02 Oct", "vs 01 Oct", fri, fri - thu, thu),
			firstRow("Thu 01 Oct", thu)), rows());

		pressCardChip(MovementWindow.D7);
		card = card();
		assertEquals(card.toString(), "7d from 08 Oct", card.footnote);
		assertEquals(card.toString(), MovementMath.DASH, card.move);
		assertChips(MovementWindow.D7, MovementWindow.D30, MovementWindow.D90, MovementWindow.D180);
		pressCardChip(MovementWindow.D1);

		// Day three: the whip down 20,000.
		f.runelitePrice(WHIP, 790_000);
		f.clock.set(at(SAT, 18, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		final long sat = panel.status().portfolio().valueNow();
		assertEquals(-20_000L, sat - fri);
		card = card();
		assertEquals(card.toString(), "1d vs your 02 Oct total", card.footnote);
		assertEquals(card.toString(), MovementMath.formatDelta(-20_000L), card.move);
		assertEquals(card.toString(), "3 days recorded since 01 Oct", card.second);
		assertEquals(Arrays.asList(
			readingRow("Sat 03 Oct", "vs 02 Oct", sat, sat - fri, fri),
			readingRow("Fri 02 Oct", "vs 01 Oct", fri, fri - thu, thu),
			firstRow("Thu 01 Oct", thu)), rows());
		pressCardChip(MovementWindow.D7);
		assertEquals("still grey until a week is behind it", "7d from 08 Oct", card().footnote);
		assertChips(MovementWindow.D7, MovementWindow.D30, MovementWindow.D90, MovementWindow.D180);
		assertEquals(Arrays.asList(THU, FRI, SAT), days(onDisk(MAIN)));
	}

	/**
	 * 3. The skipped weekend: readings Thursday, Friday and Monday. Monday's card on 1d names Friday and the span
	 * ("1d vs your 02 Oct total (3 days)"); the list has Saturday and Sunday as two thin grey rows reading their
	 * dates alone, and Monday's line reads "vs 02 Oct, 3 days"; the chart carries Friday's total across them as
	 * plain line, and resting on Saturday names it with Friday's total.
	 */
	@Test
	public void s03_theSkippedWeekend() throws Exception
	{
		final long[] totals = new long[3];
		final LocalDate[] played = {THU, FRI, MON};
		final int[] whip = {800_000, 812_000, 806_000};
		for (int i = 0; i < played.length; i++)
		{
			f.runelitePrice(WHIP, whip[i]);
			f.clock.set(at(played[i], 19, 0));
			launch(false);
			login(MAIN);
			openSidebar();
			if (i == 0)
			{
				bankVisit(PriceServiceTest.bank(f.clock.get()));
				pressView(SidebarView.HISTORY);
			}
			totals[i] = panel.status().portfolio().valueNow();
		}
		final long thu = totals[0];
		final long fri = totals[1];
		final long mon = totals[2];

		final Card card = card();
		assertEquals(card.toString(), "1d vs your 02 Oct total (3 days)", card.footnote);
		assertEquals(card.toString(), MovementMath.formatDelta(mon - fri), card.move);
		assertEquals(card.toString(), "3 days recorded since 01 Oct", card.second);
		assertEquals(Arrays.asList(
			readingRow("Mon 05 Oct", "vs 02 Oct, 3 days", mon, mon - fri, fri),
			carriedRow("Sun 04 Oct"),
			carriedRow("Sat 03 Oct"),
			readingRow("Fri 02 Oct", "vs 01 Oct", fri, fri - thu, thu),
			firstRow("Thu 01 Oct", thu)), rows());
		assertEquals(5, hist("rows"));
		assertEquals(2, hist("carriedRows"));
		assertEquals(3, hist("readings"));

		// The chart, on 7d (the card's 1d): Thursday to Monday, the weekend carried - and drawn as plain line, in the
		// one colour of the whole line (the user, 2026-09-29: "plain line").
		assertEquals("7d", hist("range"));
		final BankHistoryChart chart = find(BankHistoryChart.class);
		final List<BankHistoryMath.Day> drawn = chart.days();
		assertEquals(Arrays.asList(THU, FRI, SAT, LocalDate.of(2026, 10, 4), MON), daysOf(drawn));
		assertEquals(Arrays.asList(false, false, true, true, false), carriedOf(drawn));
		assertEquals("the carried days at Friday's total", fri, drawn.get(2).valueGp());
		assertEquals("the range's direction colours the whole line",
			Widgets.move(Long.signum(mon - thu), Widgets.Kind.FIGURE), chart.lineColour());
		assertEquals("7d reaches past the first reading: the change since it, both days named", "01 Oct - 05 Oct",
			chart(CHANGE_DAYS));
		assertEquals(MovementMath.formatPctCompact(pct(mon - thu, thu), mon - thu), chart(CHANGE_PCT));
		assertEquals("Mon 05 Oct", chart(READOUT_DAY));

		hover(2);
		assertEquals("Sat 03 Oct", chart(READOUT_DAY));
		assertEquals(MovementMath.formatExact(fri) + " gp", chart(READOUT_TOTAL));
		assertEquals("2026-10-03", hist("hoverDay"));
		leaveChart();
		assertEquals("Mon 05 Oct", chart(READOUT_DAY));
		assertNull(hist("hoverDay"));

		pressCardChip(MovementWindow.D7);
		assertEquals("7d from 08 Oct", card().footnote);
	}

	/**
	 * 4. A counting switch flipped at noon, and flipped back the next day. "Include coins and platinum tokens" off at
	 * Friday noon: no stored cell changes, nothing is written (the file's bytes are the same), the WHOLE line and the
	 * card redraw without coins - Thursday's row included - and no day shows the coins as a one-day loss; History
	 * equals the Items card at every moment. Saturday's launch keeps the switch off, records Saturday, and the switch
	 * back on at noon redraws every row to the very text it had on Friday morning, again with no write.
	 */
	@Test
	public void s04_aCountingSwitchAtNoonAndBackTheNextDay() throws Exception
	{
		f.runelitePrice(WHIP, 800_000);
		f.clock.set(at(THU, 19, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(cashBank(f.clock.get()));
		pressView(SidebarView.HISTORY);
		assertHistoryIsTheCard();

		f.runelitePrice(WHIP, 815_000);
		f.clock.set(at(FRI, 9, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertHistoryIsTheCard();
		final PriceService.Status morning = panel.status();
		final BankHistoryPoint thuPoint = morning.bankHistory().on(THU);
		final List<List<String>> morningRows = rows();
		final long friAll = morning.portfolio().valueNow();
		final int writes = writes();
		final byte[] bytes = fileBytes(MAIN);

		f.clock.set(at(FRI, 12, 0));
		clickMenu(panel.countCashItem());

		final PriceService.Status noon = panel.status();
		assertFalse(noon.options().countCash());
		assertEquals("no write", writes, writes());
		assertArrayEquals("the file untouched", bytes, fileBytes(MAIN));
		assertEquals("no stored cell changed", morning.bankHistory(), noon.bankHistory());
		final long friNoCash = noon.portfolio().valueNow();
		assertEquals("the coins in the bank and in hand left the total", friAll - 1_000_000L - 791_078L, friNoCash);
		final long thuNoCash = thuPoint.valueFor(noon.options());
		assertEquals(thuPoint.valueFor(morning.options()) - 1_791_078L, thuNoCash);
		assertEquals("the whole line redrew: Thursday too", Arrays.asList(
			readingRow("Fri 02 Oct", "vs 01 Oct", friNoCash, friNoCash - thuNoCash, thuNoCash),
			firstRow("Thu 01 Oct", thuNoCash)), rows());
		assertEquals("the day's move is the whip's alone, never a coins-sized fall", 15_000L, friNoCash - thuNoCash);
		assertHistoryIsTheCard();
		assertEquals(MovementMath.formatDelta(15_000L), card().move);
		pressView(SidebarView.ITEMS);
		assertEquals("the Items card: the same total", MovementMath.formatGp(friNoCash), card().total);
		pressView(SidebarView.HISTORY);

		// Saturday: the switch is still off in the config; the day is recorded whole (the cells ignore the switch).
		f.clock.set(at(SAT, 9, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals("Saturday recorded", writes + 1, writes());
		assertFalse(panel.status().options().countCash());
		assertHistoryIsTheCard();
		final byte[] saturday = fileBytes(MAIN);
		final long satAll = panel.status().bankHistory().on(SAT).valueFor(ViewOptions.DEFAULT);

		f.clock.set(at(SAT, 12, 0));
		clickMenu(panel.countCashItem());

		assertTrue(panel.status().options().countCash());
		assertEquals("no write", writes + 1, writes());
		assertArrayEquals(saturday, fileBytes(MAIN));
		assertHistoryIsTheCard();
		final List<List<String>> back = rows();
		assertEquals(3, back.size());
		assertEquals(readingRow("Sat 03 Oct", "vs 02 Oct", satAll, satAll - friAll, friAll), back.get(0));
		assertEquals("Friday's row is the very text of Friday morning", morningRows.get(0), back.get(1));
		assertEquals("and Thursday's", morningRows.get(1), back.get(2));
	}

	/**
	 * 5. "Use live prices" off for a day and on again. Tue 08 Sep with live prices on (the whip is a live row, so the
	 * reading's card and guide figures differ); Wed 09 Sep with the switch off in the settings all day (the reading has
	 * its guide figures only); Thu 10 Sep on again. Then, on Thursday, the switch off from the settings menu and back
	 * on: the whole line redraws on guide figures and back, today's reading is rewritten each time (its card cells
	 * change), and the two past days on disk never change. The step at the first live day is plan 7.7 item 8's known
	 * limit: Wednesday reads its guide figure under live prices on.
	 */
	@Test
	public void s05_livePricesOffForADayAndOnAgain() throws Exception
	{
		// Tuesday, live prices on.
		f.clock.set(PriceServiceTest.T0);
		liveDay(PriceServiceTest.bank(f.clock.get()), SEP_7);
		pressView(SidebarView.HISTORY);
		final BankHistoryPoint tue = panel.status().bankHistory().on(SEP_8);
		assertTrue("the whip is live", PriceServiceTest.rowFor(f.lastRows(), WHIP).isLive());
		assertTrue("so the reading keeps a guide figure beside the card's", tue.hasGuide());
		final ViewOptions liveOn = ViewOptions.DEFAULT;
		final ViewOptions liveOff = ViewOptions.DEFAULT.withLivePrices(false);
		assertNotEquals(tue.valueFor(liveOn), tue.valueFor(liveOff));
		assertHistoryIsTheCard();

		// Wednesday: switched off in RuneLite's settings before the launch, off all day.
		config.options = liveOff;
		f.clock.set(PriceServiceTest.T0 + DAY);
		launch(true);
		login(MAIN);
		openSidebar();
		final BankHistoryPoint wed = panel.status().bankHistory().on(SEP_9);
		assertNotNull(wed);
		assertFalse("a day with live prices off has its guide figures only", wed.hasGuide());
		assertEquals("History draws Tuesday on its guide figure", MovementMath.formatExact(tue.valueFor(liveOff)),
			row("Tue 08 Sep").get(3));
		assertHistoryIsTheCard();

		// Thursday: on again.
		config.options = liveOn;
		f.clock.set(PriceServiceTest.T0 + 2L * DAY);
		liveDay(PriceServiceTest.bank(f.clock.get()), SEP_9);
		final BankHistoryPoint thu = panel.status().bankHistory().on(SEP_10);
		assertTrue(PriceServiceTest.rowFor(f.lastRows(), WHIP).isLive());
		assertTrue(thu.hasGuide());
		assertHistoryIsTheCard();
		final long tueLive = tue.valueFor(liveOn);
		final long wedGuide = wed.valueFor(liveOn);
		final long thuLive = thu.valueFor(liveOn);
		assertEquals("Tuesday on its live figure again", MovementMath.formatExact(tueLive), row("Tue 08 Sep").get(3));
		assertEquals("the known step: Wednesday's guide figure against Tuesday's live one",
			readingRow("Wed 09 Sep", "vs 08 Sep", wedGuide, wedGuide - tueLive, tueLive), row("Wed 09 Sep"));
		assertEquals(readingRow("Thu 10 Sep", "vs 09 Sep", thuLive, thuLive - wedGuide, wedGuide), row("Thu 10 Sep"));

		// Off from the settings menu, and on again.
		final int writes = writes();
		clickMenu(panel.livePricesItem());
		assertFalse(panel.status().options().livePrices());
		assertEquals("every day on its guide figure", Arrays.asList(
			MovementMath.formatExact(thu.valueFor(liveOff)), MovementMath.formatExact(wedGuide),
			MovementMath.formatExact(tue.valueFor(liveOff))), exactColumn());
		assertHistoryIsTheCard();
		assertEquals("today's card cells changed, so today was rewritten", writes + 1, writes());
		assertEquals("Tuesday on disk untouched", tue, onDisk(MAIN).on(SEP_8));
		assertEquals("Wednesday on disk untouched", wed, onDisk(MAIN).on(SEP_9));

		clickMenu(panel.livePricesItem());
		assertTrue(panel.status().options().livePrices());
		assertTrue(PriceServiceTest.rowFor(f.lastRows(), WHIP).isLive());
		assertEquals("back to the live figures", Arrays.asList(MovementMath.formatExact(thuLive),
			MovementMath.formatExact(wedGuide), MovementMath.formatExact(tueLive)), exactColumn());
		assertHistoryIsTheCard();
		assertEquals(writes + 2, writes());
		assertEquals(tue, onDisk(MAIN).on(SEP_8));
		assertEquals(wed, onDisk(MAIN).on(SEP_9));
		assertEquals(thu, onDisk(MAIN).on(SEP_10));
	}

	/**
	 * 6. A volatile day with live prices on. The half-hourly re-check with the sidebar open brings a new {@code /latest}
	 * snapshot: the whip's live price moved, today's reading moves with it and is written; the day stays ONE row. The
	 * next re-check at the same live price changes no cell, so the file is not rewritten and the reading keeps its
	 * stamp. A guide price moving under a thin row is a changed cell again, and is written.
	 */
	@Test
	public void s06_aVolatileDayRewritesTheFileOnlyWhenACellChanged() throws Exception
	{
		f.clock.set(PriceServiceTest.T0);
		liveDay(PriceServiceTest.bank(f.clock.get()), SEP_7);
		pressView(SidebarView.HISTORY);
		final BankHistoryPoint first = panel.status().bankHistory().on(SEP_8);
		final int writes = writes();
		assertEquals(1, hist("rows"));

		// 16:50: the re-check, the whip's live price up to 880,000.
		f.clock.addAndGet(PriceService.TICK_MS);
		f.fireTick();
		f.answerLatest(whipQuote(890_000L, 870_000L));
		settle();
		final BankHistoryPoint moved = panel.status().bankHistory().on(SEP_8);
		assertTrue(PriceServiceTest.rowFor(f.lastRows(), WHIP).isLive());
		assertNotEquals("the live price moved today's reading", first.card(BankHistoryPoint.BANK_TRADEABLE),
			moved.card(BankHistoryPoint.BANK_TRADEABLE));
		assertEquals("the guide did not move", first.guide(BankHistoryPoint.BANK_TRADEABLE),
			moved.guide(BankHistoryPoint.BANK_TRADEABLE));
		assertEquals("written", writes + 1, writes());
		assertEquals(moved, onDisk(MAIN).on(SEP_8));
		assertEquals("still one day, one row", 1, hist("rows"));
		assertEquals(1, onDisk(MAIN).size());
		assertHistoryIsTheCard();
		final byte[] bytes = fileBytes(MAIN);

		// 17:20: the same live price again.
		f.clock.addAndGet(PriceService.TICK_MS);
		f.fireTick();
		f.answerLatest(whipQuote(890_000L, 870_000L));
		settle();
		assertEquals("no cell changed: the reading and its stamp stand", moved, panel.status().bankHistory().on(SEP_8));
		assertEquals("no write", writes + 1, writes());
		assertArrayEquals(bytes, fileBytes(MAIN));
		assertHistoryIsTheCard();

		// 17:50: RuneLite's table reloaded, a thin item's guide price up 7 gp.
		f.runelitePrice(PriceServiceTest.item(5), 5_257);
		f.clock.addAndGet(PriceService.TICK_MS);
		f.fireTick();
		f.answerLatest(whipQuote(890_000L, 870_000L));
		settle();
		assertEquals("a guide cell changed: written", writes + 2, writes());
		assertEquals(1, onDisk(MAIN).size());
		assertEquals(1, hist("rows"));
		assertHistoryIsTheCard();
	}

	/**
	 * 7. Two accounts in one client: main, the login screen, the alt, the login screen, main again. Each file holds
	 * only its own readings; the view shows the right series after each switch - and at the login screen, the last
	 * account's, unchanged and unwritten.
	 */
	@Test
	public void s07_twoAccountsInOneClient() throws Exception
	{
		store().saveBank(PriceServiceTest.bank(at(WED_30, 20, 0)));
		final BankSnapshot altBank = PriceServiceTest.bank(at(TUE_29, 20, 0), ALT);
		altBank.items.removeIf(item -> item.id == WHIP);
		store().saveBank(altBank);
		seed(MAIN, MON_28, 6_100_000L);
		seed(MAIN, TUE_29, 6_200_000L);
		seed(ALT, FRI_25, 5_000_000L);
		config.view = SidebarView.HISTORY;

		f.clock.set(at(THU, 18, 0));
		launch(false);
		openSidebar();
		login(MAIN);
		final long main = panel.status().portfolio().valueNow();
		assertSeries(3, "2026-09-28", 4, 1, main);
		assertEquals("3 days recorded since 28 Sep", card().second);

		final int writes = writes();
		logout(MAIN);
		assertEquals("the login screen keeps the last bank's History", BankPriceMovementPanel.CARD_HISTORY, panel.card());
		assertSeries(3, "2026-09-28", 4, 1, main);
		assertEquals("nothing recorded at the login screen", writes, writes());

		f.clock.set(at(THU, 18, 10));
		login(ALT);
		final long alt = panel.status().portfolio().valueNow();
		assertNotEquals(main, alt);
		assertSeries(2, "2026-09-25", 7, 5, alt);
		assertEquals("2 days recorded since 25 Sep", card().second);
		assertEquals(carriedRow("Wed 30 Sep"), rows().get(1));

		logout(ALT);
		assertSeries(2, "2026-09-25", 7, 5, alt);
		f.clock.set(at(THU, 18, 20));
		login(MAIN);
		assertSeries(3, "2026-09-28", 4, 1, main);
		assertEquals(MovementMath.formatExact(main) + " gp", chart(READOUT_TOTAL));

		final BankHistorySeries mainFile = onDisk(MAIN);
		final BankHistorySeries altFile = onDisk(ALT);
		assertEquals(Arrays.asList(MON_28, TUE_29, THU), days(mainFile));
		assertEquals(Arrays.asList(FRI_25, THU), days(altFile));
		assertEquals(main, mainFile.on(THU).valueFor(ViewOptions.DEFAULT));
		assertEquals(alt, altFile.on(THU).valueFor(ViewOptions.DEFAULT));
		assertEquals(6_100_000L, mainFile.on(MON_28).valueFor(ViewOptions.DEFAULT));
		assertEquals(5_000_000L, altFile.on(FRI_25).valueFor(ViewOptions.DEFAULT));
	}

	/**
	 * 8a. The night owl, west of UTC (Los Angeles, UTC-7): 23:30 Wednesday and 00:30 Thursday are two readings, though
	 * both are 01 Oct in UTC; 16:30 and 17:30 Friday straddle the UTC midnight and are one.
	 */
	@Test
	public void s08a_theNightOwlWestOfUtc() throws Exception
	{
		nightOwl(LOS_ANGELES);
	}

	/**
	 * 8b. The night owl, east of UTC (Tokyo, UTC+9): 23:30 Wednesday and 00:30 Thursday are two readings, though both
	 * are 30 Sep in UTC; 08:30 and 09:30 Friday straddle the UTC midnight and are one.
	 */
	@Test
	public void s08b_theNightOwlEastOfUtc() throws Exception
	{
		nightOwl(TOKYO);
	}

	/**
	 * 9. A big deposit: 49,500 more of Item 24 (about 1.2b) in the bank on Tue 08 Sep. History's card shows the jump
	 * against Monday's recorded total; the Items card's 1d move - the guide-price change on what is held now - does not,
	 * and says so in its own footnote. Every label reads true and nothing is cut at the sidebar's width.
	 */
	@Test
	public void s09_aBigDepositShowsInHistoryAndNotInTheItemsMove() throws Exception
	{
		// Monday evening: an ordinary bank.
		f.clock.set(PriceServiceTest.T0 - DAY);
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		pressView(SidebarView.HISTORY);
		final long mon = panel.status().portfolio().valueNow();
		quit();

		// Tuesday: the deposit, with the wiki answering so the Items card has its guide-price move.
		f.clock.set(PriceServiceTest.T0);
		disk = store();
		f.zonedService(zone, false);
		service = f.service;
		f.warmUpWith(withQuantity(PriceServiceTest.bank(f.clock.get()), PriceServiceTest.item(24), 49_524));
		buildPanel();
		final PriceService.Status status = panel.status();
		final long tue = status.portfolio().valueNow();
		final long jump = tue - mon;
		assertTrue("about 1.2b: " + jump, jump > 1_150_000_000L && jump < 1_250_000_000L);

		assertEquals(SidebarView.HISTORY, panel.view());
		Card card = card();
		assertEquals(card.toString(), MovementMath.formatGp(tue), card.total);
		assertEquals(card.toString(), MovementMath.formatDelta(jump), card.move);
		assertEquals(card.toString(), MovementMath.formatPct(pct(jump, mon), jump), card.pct);
		assertEquals(card.toString(), "1d vs your 07 Sep total", card.footnote);
		assertEquals(Arrays.asList(readingRow("Tue 08 Sep", "vs 07 Sep", tue, jump, mon), firstRow("Mon 07 Sep", mon)),
			rows());
		assertEquals(MovementMath.formatPctCompact(pct(jump, mon), jump), chart(CHANGE_PCT));
		assertNothingCut();

		pressView(SidebarView.ITEMS);
		final WindowMove itemsMove = status.portfolio().move(MovementWindow.D1);
		assertNotNull("the Items card has its guide-price move", itemsMove);
		card = card();
		assertEquals(card.toString(), MovementMath.formatGp(tue), card.total);
		assertEquals(card.toString(), MovementMath.formatDelta(itemsMove.deltaGp()), card.move);
		assertTrue("the Items move is the prices', not the deposit: " + itemsMove.deltaGp(),
			Math.abs(itemsMove.deltaGp()) < 1_000_000L);
		assertTrue(card.toString(), card.footnote.startsWith("1d vs 07 Sep - bank "));
		assertEquals("Item price changes", caption());
	}

	/**
	 * 10a. A clock set a day ahead for one session, then corrected. Readings Mon-Wed. On Thursday evening the clock says
	 * Friday: Friday is recorded and drawn as today. The clock corrected the same evening: the next launch draws
	 * Thursday as today, never the "Friday" (plan 7.1 item 7: not drawn), and its write removes it from the file.
	 */
	@Test
	public void s10a_aClockADayAheadIsForgottenOnceCorrected() throws Exception
	{
		seedMonToWed();
		config.view = SidebarView.HISTORY;

		f.clock.set(at(FRI, 20, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals("2026-10-02", hist("last"));
		assertEquals("Thursday carried under the ahead clock", 1, hist("carriedRows"));
		assertNotNull(onDisk(MAIN).on(FRI));

		f.clock.set(at(THU, 21, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals("2026-10-01", hist("last"));
		assertEquals(4, hist("readings"));
		assertEquals(4, hist("rows"));
		assertEquals(0, hist("carriedRows"));
		assertEquals("4 days recorded since 28 Sep", card().second);
		assertTrue(rows().get(0).get(0).startsWith("Thu 01 Oct"));
		assertNull("the future reading is gone from the file", onDisk(MAIN).on(FRI));
		assertEquals(Arrays.asList(MON_28, TUE_29, WED_30, THU), days(onDisk(MAIN)));
	}

	/**
	 * 10a, continued: the corrected session runs on past midnight into the real Friday. Plan 7.1 item 7 says the
	 * ahead-clock "Friday" is not drawn and is removed at the next write; plan 7.5 item 1 and review finding H3 say a
	 * re-login - and any new total - on a day with no reading records that day. At 00:20 Friday the player hops worlds
	 * and opens the sidebar, bank and prices unchanged: Friday should be recorded, with this session's stamps.
	 */
	@Test
	public void s10a2_theCorrectedSessionRecordsTheRealFridayAfterMidnight() throws Exception
	{
		seedMonToWed();
		config.view = SidebarView.HISTORY;
		f.clock.set(at(FRI, 20, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		final long aheadRead = panel.status().bankHistory().on(FRI).readAtMillis();

		f.clock.set(at(THU, 21, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertNull(onDisk(MAIN).on(FRI));

		// Past midnight: the sidebar hidden, a hop (a re-login of the same account), then the sidebar opened.
		hideSidebar();
		f.clock.set(at(FRI, 0, 20));
		logout(MAIN);
		login(MAIN);
		assertNotNull("the hop alone records Friday (H3), the sidebar still hidden", onDisk(MAIN).on(FRI));
		openSidebar();

		final BankHistoryPoint drawn = panel.status().bankHistory().on(FRI);
		assertNotNull(drawn);
		assertNotEquals("Friday's reading is this session's, not the ahead clock's", aheadRead, drawn.readAtMillis());
		assertNotNull("and it is on disk", onDisk(MAIN).on(FRI));
		assertEquals(drawn, onDisk(MAIN).on(FRI));
	}

	/**
	 * 10a, the other way round (the final fixer's, beside AU-W1): the ahead session's "Friday" is still in the file when
	 * the next launch comes on the REAL Friday, bank and prices unchanged. The file's Friday is stamped after the clock
	 * now reads, so it is not the day's last reading: the login's own reading replaces it, on screen and on disk.
	 */
	@Test
	public void s10a3_aReadingStampedAheadIsReplacedOnItsRealDay() throws Exception
	{
		seedMonToWed();
		config.view = SidebarView.HISTORY;
		f.clock.set(at(FRI, 20, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		final long aheadRead = onDisk(MAIN).on(FRI).readAtMillis();
		quit();

		f.clock.set(at(FRI, 0, 20));
		launch(false);
		login(MAIN);
		openSidebar();

		final BankHistoryPoint drawn = panel.status().bankHistory().on(FRI);
		assertNotNull(drawn);
		assertEquals("this session's stamp", at(FRI, 0, 20), drawn.readAtMillis());
		assertEquals(drawn, onDisk(MAIN).on(FRI));
		assertNotEquals(aheadRead, onDisk(MAIN).on(FRI).readAtMillis());
		assertEquals(Arrays.asList(MON_28, TUE_29, WED_30, FRI), days(onDisk(MAIN)));
		assertEquals(1, hist("carriedRows"));
	}

	/**
	 * 10b. A clock set a year back. Readings Mon-Wed 2026; the clock says 01 Oct 2025. The record on disk is safe - the
	 * clock-behind rule writes nothing (H1) - and the view draws what the session holds: the one reading of "today".
	 */
	@Test
	public void s10b_aClockAYearBackNeverTouchesTheRecord() throws Exception
	{
		seedMonToWed();
		config.view = SidebarView.HISTORY;
		final byte[] bytes = fileBytes(MAIN);

		final LocalDate yearBack = THU.minusYears(1);
		f.clock.set(at(yearBack, 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));

		assertArrayEquals("nothing written", bytes, fileBytes(MAIN));
		assertEquals(1, hist("readings"));
		assertEquals("2025-10-01", hist("first"));
		assertEquals(Collections.singletonList(firstRow("Wed 01 Oct", panel.status().portfolio().valueNow())), rows());
		assertEquals("1 day recorded", card().second);

		quit();
		f.clock.set(at(THU, 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals("the next launch with the clock right: the record whole, and today",
			Arrays.asList(MON_28, TUE_29, WED_30, THU), days(onDisk(MAIN)));
		assertEquals("2026-09-28", hist("first"));
	}

	/**
	 * 10b, continued: the clock is corrected WHILE the client runs (the half-hourly re-check after the system clock
	 * catches up). The record should be the three readings and today's; a reading taken under the year-back clock
	 * should not enter the file as a real day a year ago (H1: "a clock set in the past can never prune the record";
	 * plan 7.5 item 1: a reading is a day the player logged in).
	 */
	@Test
	public void s10b2_aYearBackClockCorrectedWhileRunningLeavesNoYearOldReading() throws Exception
	{
		seedMonToWed();
		config.view = SidebarView.HISTORY;
		f.clock.set(at(THU.minusYears(1), 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();

		f.clock.set(at(THU, 12, 5));
		tick();

		assertEquals(Arrays.asList(MON_28, TUE_29, WED_30, THU), days(onDisk(MAIN)));
		assertEquals("2026-09-28", hist("first"));
		assertEquals(4, hist("rows"));
		assertEquals("4 days recorded since 28 Sep", card().second);
	}

	/**
	 * 11. The long record: 730 daily readings (01 Oct 2024 to 30 Sep 2026) and today's. On "all" the chart thins to
	 * buckets of ceil(731 / 120) = 7 days aligned to today, 105 points, each its last reading; resting on a point names
	 * that reading's day and total. The list pages at 250 - "Show 481 more", then "Show 231 more" - to its end, where
	 * the first reading prints its year like every row more than 300 days back.
	 */
	@Test
	public void s11_theLongRecord() throws Exception
	{
		final LocalDate start = THU.minusDays(730);
		writeLongRecord(start, 730);
		store().saveBank(PriceServiceTest.bank(at(WED_30, 20, 0)));
		config.view = SidebarView.HISTORY;

		f.clock.set(at(THU, 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals(731, hist("readings"));
		assertEquals("2024-10-01", hist("first"));
		assertEquals(250, hist("rows"));

		pressCardChip(MovementWindow.D180);
		assertEquals("all", hist("range"));
		final BankHistoryChart chart = find(BankHistoryChart.class);
		final List<BankHistoryMath.Day> drawn = chart.days();
		assertEquals(105, drawn.size());
		assertTrue(drawn.size() <= BankHistoryView.MAX_POINTS);
		assertEquals(THU, drawn.get(drawn.size() - 1).day());
		assertEquals("the first bucket holds three days; its point is the third", start.plusDays(2), drawn.get(0).day());
		for (int i = 1; i < drawn.size(); i++)
		{
			assertEquals("aligned to today", 0, ChronoUnit.DAYS.between(drawn.get(i).day(), THU) % 7);
		}

		for (int i : new int[]{0, 52, drawn.size() - 1})
		{
			hover(i);
			final BankHistoryMath.Day d = drawn.get(i);
			assertEquals(d.day().toString(), hist("hoverDay"));
			// Walk-through AU-N2, answered 2026-09-29: the readout follows the list's year rule, through its helper -
			// "03 Oct 2024" two years back, no weekday.
			assertEquals(BankHistoryDayRow.dayText(d.day(), THU), chart(READOUT_DAY));
			assertEquals(MovementMath.formatExact(d.valueGp()) + " gp", chart(READOUT_TOTAL));
			assertEquals("the readout names the reading itself",
				panel.status().bankHistory().on(d.day()).valueFor(panel.status().options()), d.valueGp());
		}
		leaveChart();

		assertEquals("Show 481 more", showMoreText());
		onEdt(panel::showMore);
		assertEquals(500, hist("rows"));
		assertEquals("Show 231 more", showMoreText());
		onEdt(panel::showMore);
		assertEquals(731, hist("rows"));
		assertNull("no pager at the end", showMoreText());
		onEdt(panel::showMore);
		assertEquals(731, hist("rows"));

		final List<List<String>> all = rows();
		assertEquals(731, all.size());
		assertEquals("the oldest row, with its year", "01 Oct 2024", all.get(730).get(0));
		assertEquals("first reading", all.get(730).get(1));
		final LocalDate d300 = THU.minusDays(300);
		final LocalDate d301 = THU.minusDays(301);
		assertEquals(BankHistoryDayRow.weekday(d300), all.get(300).get(0));
		assertEquals(MovementMath.formatDay(d301) + " 2025", all.get(301).get(0));
		assertEquals(0, hist("carriedRows"));
	}

	/**
	 * 12. A history file that cannot be read at launch - a directory where the file should be - then repaired by hand
	 * while the client runs. The load is FAILED: today's reading lives in memory and is drawn ("1 day recorded"), the
	 * directory is left where it is and nothing is written for the rest of the session, the repair included (plan 7.1
	 * item 5). The next launch reads the repaired file and adds today.
	 */
	@Test
	public void s12_anUnreadableFileRepairedWhileTheClientRuns() throws Exception
	{
		store().saveBank(PriceServiceTest.bank(at(WED_30, 20, 0)));
		config.view = SidebarView.HISTORY;
		final File file = historyFile(MAIN);
		assertTrue(file.mkdir());

		f.clock.set(at(THU, 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		// OPEN QUESTION for the user (final review R3): after a failed load History shows only this session's readings
		// - "1 day recorded", "first reading" - with nothing on the sidebar to say the record could not be read, until
		// the client restarts. Pinned as built until they say whether a line should explain it.
		assertEquals(1, hist("readings"));
		assertEquals("1 day recorded", card().second);
		assertEquals(Collections.singletonList(firstRow("Thu 01 Oct", panel.status().portfolio().valueNow())), rows());
		assertTrue("left in place", file.isDirectory());
		assertEquals("nothing sent to the store", 0, writes());
		assertEquals("and nothing quarantined", 0, corruptCopies());

		// The player repairs it by hand: the directory out, their backup of ten days in.
		assertTrue(file.delete());
		for (int back = 10; back >= 1; back--)
		{
			seed(MAIN, THU.minusDays(back), 6_000_000L + back);
		}
		final byte[] repaired = fileBytes(MAIN);

		f.clock.set(at(THU, 12, 30));
		bankVisit(withQuantity(PriceServiceTest.bank(f.clock.get()), BOX, 30));
		final long total = panel.status().portfolio().valueNow();
		assertEquals("drawn from memory, the new total", Collections.singletonList(firstRow("Thu 01 Oct", total)), rows());
		assertArrayEquals("nothing written this session", repaired, fileBytes(MAIN));

		f.clock.set(at(THU, 13, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals(11, hist("readings"));
		assertEquals("2026-09-21", hist("first"));
		assertEquals("11 days recorded since 21 Sep", card().second);
		assertEquals(11, onDisk(MAIN).size());
		assertEquals(total, onDisk(MAIN).on(THU).valueFor(ViewOptions.DEFAULT));
	}

	/**
	 * 13. The bank hold (addendum AS) with History showing: a ten-minute visit with three changes. During it the screen
	 * stays as it was - the card, the readout, the rows - the ring breathes, and the toggle pressed there and back
	 * changes nothing. The close reads the bank once: today's reading moves, still one row for today, one write.
	 */
	@Test
	public void s13_theBankHoldWithHistoryShowing() throws Exception
	{
		seed(MAIN, WED_30, 6_000_000L);
		config.view = SidebarView.HISTORY;
		f.clock.set(at(THU, 14, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		final PriceService.Status drawn = panel.status();
		final Card card = card();
		final List<List<String>> rows = rows();
		final String readout = chart(READOUT_TOTAL);
		final int writes = writes();

		f.clock.set(at(THU, 14, 30));
		hold(true, false, 0);
		for (int change = 1; change <= 3; change++)
		{
			f.clock.addAndGet(3L * MINUTE);
			hold(true, true, change);
			assertTrue("the ring breathes round Refresh", panel.glowRunning());
			if (change == 1)
			{
				pressView(SidebarView.ITEMS);
				pressView(SidebarView.HISTORY);
				assertTrue("the toggle does not end the hold", panel.glowRunning());
			}
			assertSame("the screen is the one drawn before the visit", drawn, panel.status());
			assertEquals(card.toString(), card().toString());
			assertEquals(rows, rows());
			assertEquals(readout, chart(READOUT_TOTAL));
		}
		assertEquals("nothing read, nothing written", writes, writes());

		// 14:40, the bank closes: the plugin reads the held change once, then tells the panel.
		f.clock.set(at(THU, 14, 40));
		final BankSnapshot closed = withQuantity(PriceServiceTest.bank(f.clock.get()), BOX, 53);
		service.setBank(closed);
		reads++;
		hold(false, false, 3);

		assertFalse(panel.glowRunning());
		final PriceService.Status after = panel.status();
		assertEquals(closed.capturedAtMillis, after.bankAtMillis());
		final long total = after.portfolio().valueNow();
		assertEquals("fifty more boxes", drawn.portfolio().valueNow() + 5_000L, total);
		assertEquals(MovementMath.formatExact(total) + " gp", chart(READOUT_TOTAL));
		assertEquals(MovementMath.formatGp(total), card().total);
		assertEquals("one row for today still", rows.size(), rows().size());
		assertEquals(readingRow("Thu 01 Oct", "vs 30 Sep", total, total - 6_000_000L, 6_000_000L), rows().get(0));
		assertEquals("one write for the close", writes + 1, writes());
		assertEquals(closed.capturedAtMillis, onDisk(MAIN).on(THU).bankAtMillis());
	}

	/**
	 * 14. Logged out and sitting at the login screen across midnight with the sidebar open on History. Nothing is
	 * recorded or written at the login screen, whatever the prices do; at 00:15 the half-hourly re-check redraws the
	 * view for the new day - Thursday a thin carried row at Wednesday's total, the readout still on Wednesday's reading,
	 * the card's 1d against Wednesday. The login at 00:20 records Thursday (H3), and its row replaces the carried one.
	 */
	@Test
	public void s14_theLoginScreenAcrossMidnight() throws Exception
	{
		seed(MAIN, TUE_29, 6_000_000L);
		config.view = SidebarView.HISTORY;
		f.clock.set(at(WED_30, 22, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		final long wed = panel.status().portfolio().valueNow();
		final int writes = writes();
		final byte[] bytes = fileBytes(MAIN);

		f.clock.set(at(WED_30, 23, 0));
		logout(MAIN);
		f.clock.set(at(WED_30, 23, 30));
		tick();
		assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());

		f.runelitePrice(WHIP, 830_000);
		f.clock.set(at(THU, 0, 15));
		tick();
		final PriceService.Status status = panel.status();
		assertFalse(status.loggedIn());
		assertNull("no reading at the login screen", status.bankHistory().on(THU));
		assertEquals("nothing written", writes, writes());
		assertArrayEquals(bytes, fileBytes(MAIN));
		assertEquals("2026-09-30", hist("last"));
		assertEquals(3, hist("rows"));
		assertEquals(1, hist("carriedRows"));
		assertEquals(carriedRow("Thu 01 Oct"), rows().get(0));
		assertEquals("the readout stays on the latest reading", "Wed 30 Sep", chart(READOUT_DAY));
		assertEquals(MovementMath.formatExact(wed) + " gp", chart(READOUT_TOTAL));
		final Card card = card();
		// Walk-through AU-N1, answered 2026-09-29: logged out, History's footnote ends " - logged out" as Items' does.
		assertEquals(card.toString(), "1d vs your 30 Sep total - logged out", card.footnote);
		assertEquals(card.toString(), "2 days recorded since 29 Sep", card.second);
		final long repriced = status.portfolio().valueNow();
		assertNotEquals("the headline is re-priced", wed, repriced);
		assertEquals(card.toString(), MovementMath.formatGp(repriced), card.total);

		f.clock.set(at(THU, 0, 20));
		login(MAIN);
		final long thu = panel.status().portfolio().valueNow();
		assertEquals("the login records Thursday", writes + 1, writes());
		assertEquals(0, hist("carriedRows"));
		assertEquals(readingRow("Thu 01 Oct", "vs 30 Sep", thu, thu - wed, wed), rows().get(0));
		assertEquals(Arrays.asList(TUE_29, WED_30, THU), days(onDisk(MAIN)));
	}

	/**
	 * 15 (added by the walk-through). A fresh install whose first bank is read before RuneLite's price table has
	 * loaded - every stack unpriced, which plan 7.1 item 6 says is not a reading. The strip appears with the bank; in
	 * History the card's two lines are dashes (ruling 9.7) and the view shows the contract's zero-reading sentence
	 * (section 6), although the bank HAS been opened (plan 7.5 item 3 says the sentence is for a bank never seen - the
	 * contract, which wins where it is more exact, draws it for "no reading at all"). The next computation after the
	 * prices load - the half-hourly re-check - is the first reading, and the sentence gives way to the point.
	 */
	@Test
	public void s15_aFreshInstallWhoseFirstBankIsReadBeforeTheGuidePricesLoad() throws Exception
	{
		final BankSnapshot first = PriceServiceTest.bank(at(THU, 12, 0));
		final Map<Integer, Long> table = new HashMap<>();
		for (BankItem item : first.items)
		{
			table.put(item.id, f.itemManager.getItemPriceWithSource(item.id, false));
		}
		final boolean[] loaded = {false};
		when(f.itemManager.getItemPriceWithSource(anyInt(), eq(false))).thenAnswer(i ->
			loaded[0] ? table.getOrDefault(i.<Integer>getArgument(0), 0L) : 0L);

		f.clock.set(at(THU, 12, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		assertEquals("no bank yet", BankPriceMovementPanel.CARD_NO_BANK, panel.card());
		final Object[] strip = new Object[1];
		onEdt(() -> strip[0] = LookRenderer.find(panel, Widgets.Toggle.class));
		assertNull("no strip without a bank", strip[0]);

		bankVisit(first);
		assertTrue(panel.status().bankLoaded());
		assertTrue("nothing priced: not a reading", panel.status().bankHistory().isEmpty());
		pressView(SidebarView.HISTORY);
		// OPEN QUESTION for the user (walk-through AU-N3): "No readings yet - open your bank to load your first
		// reading." shows to a player who has just opened the bank, while RuneLite's prices are still loading; it
		// clears at the next re-check. Pinned as built until they say whether that moment needs its own words.
		assertEquals(BankHistoryView.NO_READINGS, sentence());
		Card card = card();
		assertEquals(card.toString(), MovementMath.DASH, card.footnote);
		assertEquals(card.toString(), MovementMath.DASH, card.second);
		assertEquals(0, hist("readings"));
		assertEquals(0, hist("rows"));
		assertEquals(0, writes());
		assertFalse(historyFile(MAIN).exists());

		// RuneLite's table has loaded; the half-hourly re-check prices the bank.
		loaded[0] = true;
		f.clock.set(at(THU, 12, 30));
		tick();
		final long total = panel.status().portfolio().valueNow();
		assertNull(sentence());
		assertEquals(Collections.singletonList(firstRow("Thu 01 Oct", total)), rows());
		card = card();
		assertEquals(card.toString(), "1 day recorded", card.second);
		assertEquals(card.toString(), "1d from 02 Oct", card.footnote);
		assertEquals(1, onDisk(MAIN).size());
	}

	/**
	 * 16 (added by the walk-through). Logged in across local midnight with the sidebar HIDDEN and the bank untouched:
	 * contract section 10 (a)'s accepted limit - no timer runs while hidden, so Thursday has no reading until the
	 * player next does something that computes; opening the sidebar in the morning records it.
	 */
	@Test
	public void s16_loggedInAcrossMidnightWithTheSidebarHiddenTheNewDayWaits() throws Exception
	{
		seed(MAIN, TUE_29, 6_000_000L);
		config.view = SidebarView.HISTORY;
		f.clock.set(at(WED_30, 22, 0));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		final long wed = panel.status().portfolio().valueNow();
		hideSidebar();

		f.runelitePrice(WHIP, 830_000);
		f.clock.set(at(THU, 0, 30));
		assertNull("the known limit: nothing ran, no reading for Thursday", service.currentStatus().bankHistory().on(THU));
		f.clock.set(at(THU, 7, 45));
		assertNull(onDisk(MAIN).on(THU));

		openSidebar();
		final long thu = panel.status().portfolio().valueNow();
		assertNotNull("the sidebar opened is a computation, and a reading", onDisk(MAIN).on(THU));
		assertEquals(readingRow("Thu 01 Oct", "vs 30 Sep", thu, thu - wed, wed), rows().get(0));
		assertEquals(0, hist("carriedRows"));
	}

	// ================================================================================================ scenario bodies

	private void nightOwl(ZoneId owlZone) throws Exception
	{
		useZone(owlZone);
		config.view = SidebarView.HISTORY;
		f.runelitePrice(WHIP, 800_000);
		f.clock.set(at(WED_30, 23, 30));
		launch(false);
		login(MAIN);
		openSidebar();
		bankVisit(PriceServiceTest.bank(f.clock.get()));
		final long wed = panel.status().portfolio().valueNow();
		final long wedAt = f.clock.get();
		assertEquals(Collections.singletonList(WED_30), days(onDisk(MAIN)));

		// 00:30 Thursday: RuneLite's table has moved the whip; the half-hourly re-check with the sidebar open.
		f.runelitePrice(WHIP, 805_000);
		f.clock.set(at(THU, 0, 30));
		tick();
		assertEquals("both in one UTC day", BankHistoryMath.dayOf(wedAt, ZoneOffset.UTC),
			BankHistoryMath.dayOf(f.clock.get(), ZoneOffset.UTC));
		final long thu = panel.status().portfolio().valueNow();
		assertEquals("two local days, two readings", Arrays.asList(WED_30, THU), days(onDisk(MAIN)));
		assertEquals("2026-10-01", hist("last"));
		final Card card = card();
		assertEquals(card.toString(), "1d vs your 30 Sep total", card.footnote);
		assertEquals(card.toString(), "2 days recorded since 30 Sep", card.second);
		assertEquals(Arrays.asList(readingRow("Thu 01 Oct", "vs 30 Sep", thu, thu - wed, wed),
			firstRow("Wed 30 Sep", wed)), rows());

		// Friday: an hour either side of the UTC midnight that falls inside the local day.
		final Instant utcMidnight = utcMidnightWithin(FRI);
		f.clock.set(utcMidnight.toEpochMilli() - 30L * MINUTE);
		bankVisit(withQuantity(PriceServiceTest.bank(f.clock.get()), BOX, 13));
		f.clock.set(utcMidnight.toEpochMilli() + 30L * MINUTE);
		final BankSnapshot later = withQuantity(PriceServiceTest.bank(f.clock.get()), BOX, 23);
		bankVisit(later);
		assertNotEquals(BankHistoryMath.dayOf(utcMidnight.toEpochMilli() - MINUTE, ZoneOffset.UTC),
			BankHistoryMath.dayOf(utcMidnight.toEpochMilli() + MINUTE, ZoneOffset.UTC));
		assertEquals("one local day across the UTC midnight", Arrays.asList(WED_30, THU, FRI), days(onDisk(MAIN)));
		assertEquals("the later read won", later.capturedAtMillis, onDisk(MAIN).on(FRI).bankAtMillis());
		assertEquals(3, hist("rows"));
	}

	/** The UTC midnight that falls inside the local {@code day} (there is exactly one in any zone but UTC). */
	private Instant utcMidnightWithin(LocalDate day)
	{
		final Instant noon = day.atTime(12, 0).atZone(zone).toInstant();
		final Instant before = noon.truncatedTo(ChronoUnit.DAYS);
		return day.equals(before.atZone(zone).toLocalDate()) ? before : before.plus(1, ChronoUnit.DAYS);
	}

	private void seedMonToWed() throws IOException
	{
		store().saveBank(PriceServiceTest.bank(at(WED_30, 20, 0)));
		seed(MAIN, MON_28, 6_100_000L);
		seed(MAIN, TUE_29, 6_150_000L);
		seed(MAIN, WED_30, 6_200_000L);
	}

	/** One live day on the fixture's live feeds: the service warmed up with {@code bank}, yesterday's bucket answered. */
	private void liveDay(BankSnapshot bank, LocalDate yesterday) throws Exception
	{
		quit();
		disk = store();
		f.warmUpLiveWith(bank, Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		f.answerDay(yesterday, PriceServiceTest.tradedSep7());
		service = f.service;
		if (!config.options.equals(ViewOptions.DEFAULT))
		{
			service.setOptions(config.options);
		}
		buildPanel();
	}

	/** A {@code /latest} snapshot with the whip alone, traded minutes ago. */
	private Map<Integer, TradedPriceClient.Quote> whipQuote(long buy, long sell)
	{
		final long now = f.clock.get() / 1000L;
		return Collections.singletonMap(WHIP, new TradedPriceClient.Quote(buy, now - 600L, sell, now - 900L));
	}

	// ================================================================================================ the client

	private void useZone(ZoneId z)
	{
		zone = z;
		TimeZone.setDefault(TimeZone.getTimeZone(z));
	}

	/** A client start: a new store instance on the same folder, a new service and a new panel, the config kept. */
	private void launch(boolean live) throws Exception
	{
		quit();
		disk = store();
		f.zonedService(zone, live);
		service = f.service;
		service.setOptions(config.options);
		if (config.filter != null)
		{
			service.setFilter(config.filter);
		}
		service.start();
		buildPanel();
	}

	private void buildPanel() throws Exception
	{
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(sprites, service, config);
			panel.setClock(f.clock::get);
			// The panel opens on Items; the scenario's hand presses History again if it had.
			if (config.view != null)
			{
				panel.setView(config.view);
			}
		});
		settle();
	}

	/** The client closed: the panel stopped, the service stopped. */
	private void quit() throws Exception
	{
		if (panel != null)
		{
			final BankPriceMovementPanel p = panel;
			panel = null;
			onEdt(p::stop);
		}
		if (service != null)
		{
			service.stop();
			service = null;
		}
		reads = 0;
		settle();
	}

	private void login(long account) throws Exception
	{
		service.setLoggedIn(true, account, PROFILE);
		settle();
	}

	private void logout(long account) throws Exception
	{
		service.setLoggedIn(false, account, PROFILE);
		settle();
	}

	/** The sidebar's tab selected: the panel shown and the service's half-hourly timer's first run. */
	private void openSidebar() throws Exception
	{
		onEdt(panel::onActivate);
		f.fireTick();
		settle();
	}

	private void hideSidebar() throws Exception
	{
		onEdt(panel::onDeactivate);
		settle();
	}

	/** The half-hourly re-check while the sidebar is open. */
	private void tick() throws Exception
	{
		f.fireTick();
		settle();
	}

	/**
	 * A bank visit whose items are read as the bank opens - the first visit of a session - and which ends with no
	 * change held: the plugin's order, the open told first, the read, then the close.
	 */
	private void bankVisit(BankSnapshot read) throws Exception
	{
		hold(true, false, 0);
		service.setBank(read);
		reads++;
		hold(true, false, 0);
		hold(false, false, 0);
	}

	/** What the plugin tells the panel about the bank (addendum AS). */
	private void hold(boolean open, boolean pending, int heldEvents) throws Exception
	{
		final int r = reads;
		settleThen(() -> panel.setBankHold(open, pending, heldEvents, r));
	}

	private void pressView(SidebarView v) throws Exception
	{
		onEdt(() -> press(((Container) LookRenderer.find(panel, Widgets.Toggle.class)).getComponent(v.ordinal()),
			MouseEvent.BUTTON1));
		settle();
		assertEquals(v, panel.view());
		config.view = v;
	}

	private void pressCardChip(MovementWindow w) throws Exception
	{
		onEdt(() -> press(panel.windowChip(w), MouseEvent.BUTTON1));
		settle();
	}

	/** A check item of the settings menu, clicked. */
	private void clickMenu(javax.swing.AbstractButton item) throws Exception
	{
		onEdt(() -> item.doClick(0));
		settle();
	}

	/** Lets every publish posted to the Swing thread land, and what they post in turn. */
	private static void settle() throws Exception
	{
		for (int i = 0; i < 3; i++)
		{
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	private static void settleThen(Runnable body) throws Exception
	{
		settle();
		onEdt(body);
		settle();
	}

	// ================================================================================================ what is seen

	private Card card() throws Exception
	{
		final Card[] out = new Card[1];
		onEdt(() -> out[0] = new Card(panel.totalLabel().getText(), panel.deltaLabel().getText(),
			panel.pctLabel().getText(), panel.footnoteLabel().getText(), panel.updateLabel().getText()));
		return out[0];
	}

	private String caption() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() ->
		{
			// The strip: the toggle and, under it, its one caption line.
			for (Component c : LookRenderer.find(panel, Widgets.Toggle.class).getParent().getComponents())
			{
				if (c instanceof JLabel)
				{
					out[0] = ((JLabel) c).getText();
				}
			}
		});
		return out[0];
	}

	/** The card's chips: exactly {@code dimmed} are drawn grey, and the lit one never is. */
	private void assertChips(MovementWindow... dimmed) throws Exception
	{
		final List<MovementWindow> want = Arrays.asList(dimmed);
		onEdt(() ->
		{
			for (MovementWindow w : MovementWindow.values())
			{
				final JLabel chip = panel.windowChip(w);
				final boolean lit = Widgets.isLit(chip);
				assertEquals(w + (lit ? " (lit)" : ""), want.contains(w) && !lit, Widgets.isDim(chip));
			}
		});
	}

	@Nullable
	private Object hist(String key) throws Exception
	{
		final Object[] out = new Object[1];
		onEdt(() -> out[0] = panel.bankHistoryState().get(key));
		return out[0];
	}

	private BankHistoryView view()
	{
		return LookRenderer.find(panel, BankHistoryView.class);
	}

	private <T> T find(Class<T> type) throws Exception
	{
		final List<T> out = new ArrayList<>();
		onEdt(() -> out.add(LookRenderer.find(view(), type)));
		return out.get(0);
	}

	private String chart(int index) throws Exception
	{
		final String[] out = new String[1];
		onEdt(() -> out[0] = BankHistoryViewTest.chartLabel(view(), index));
		return out[0];
	}

	/** The "No readings yet" sentence when it is what the view shows, else null. */
	@Nullable
	private String sentence() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() ->
		{
			final BankHistoryView v = view();
			if (v.getComponentCount() == 1 && v.getComponent(0) instanceof BankHistoryView.Sentence)
			{
				out[0] = String.join(" ", ((BankHistoryView.Sentence) v.getComponent(0)).lines(10_000));
			}
		});
		return out[0];
	}

	/** Every day row built, newest first: each its labels' texts in order (a carried row has one). */
	private List<List<String>> rows() throws Exception
	{
		final List<List<String>> out = new ArrayList<>();
		onEdt(() ->
		{
			for (Component c : SidebarViewPanelTest.walk(view()))
			{
				if (c instanceof BankHistoryDayRow)
				{
					final List<String> texts = new ArrayList<>();
					for (Component label : ((Container) c).getComponents())
					{
						texts.add(((JLabel) label).getText());
					}
					out.add(texts);
				}
			}
		});
		return out;
	}

	/** The row whose date begins with {@code date}. */
	private List<String> row(String date) throws Exception
	{
		for (List<String> r : rows())
		{
			if (r.get(0).startsWith(date))
			{
				return r;
			}
		}
		throw new AssertionError("no row for " + date + " in " + rows());
	}

	/** Each reading row's exact total, newest first. */
	private List<String> exactColumn() throws Exception
	{
		final List<String> out = new ArrayList<>();
		for (List<String> r : rows())
		{
			if (r.size() > 1)
			{
				out.add(r.get(3));
			}
		}
		return out;
	}

	/** The list's pager text, or null when there is none in the view. */
	@Nullable
	private String showMoreText() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() ->
		{
			for (Component c : SidebarViewPanelTest.walk(view()))
			{
				if (c instanceof JLabel && ((JLabel) c).getText() != null && ((JLabel) c).getText().startsWith("Show ")
					&& c.getParent() != null && c.getParent().getParent() != null)
				{
					out[0] = ((JLabel) c).getText();
				}
			}
		});
		return out[0];
	}

	/**
	 * Today's reading, the chart's readout, today's row and the card's headline are ONE figure - the Bank value card
	 * as Items draws it - under the switches the drawn status was computed with (plan 7.5 item 2).
	 */
	private void assertHistoryIsTheCard() throws Exception
	{
		final PriceService.Status status = panel.status();
		final long total = status.portfolio().valueNow();
		final LocalDate today = BankHistoryMath.dayOf(f.clock.get(), zone);
		final BankHistoryPoint reading = status.bankHistory().on(today);
		assertNotNull("today has a reading", reading);
		assertEquals("today's reading is the card's total", total, reading.valueFor(status.options()));
		assertEquals(MovementMath.formatExact(total) + " gp", chart(READOUT_TOTAL));
		assertEquals(MovementMath.formatExact(total), rows().get(0).get(3));
		assertEquals(MovementMath.formatGp(total), card().total);
	}

	/** The view's series: readings, first day, rows built, carried among them, and the readout at {@code total}. */
	private void assertSeries(int readings, String first, int rowCount, int carried, long total) throws Exception
	{
		assertEquals(readings, hist("readings"));
		assertEquals(first, hist("first"));
		assertEquals("2026-10-01", hist("last"));
		assertEquals(rowCount, hist("rows"));
		assertEquals(carried, hist("carriedRows"));
		assertEquals(MovementMath.formatExact(total) + " gp", chart(READOUT_TOTAL));
		assertEquals(MovementMath.formatGp(total), card().total);
	}

	/** The panel laid out at the sidebar's width: no card line fitted short, and no History label narrower than its text. */
	private void assertNothingCut() throws Exception
	{
		onEdt(() ->
		{
			panel.setSize(LookRenderer.WIDTH, 3_000);
			LookRenderer.layoutTree(panel);
			for (Component c : SidebarViewPanelTest.walk(view()))
			{
				if (c instanceof JLabel && c.isVisible() && c.getParent() instanceof BankHistoryDayRow)
				{
					final JLabel label = (JLabel) c;
					assertTrue("cut: '" + label.getText() + "' " + label.getWidth() + " < "
						+ label.getPreferredSize().width, label.getWidth() >= label.getPreferredSize().width);
				}
			}
			for (JLabel label : new JLabel[]{panel.footnoteLabel(), panel.updateLabel(), panel.deltaLabel(),
				panel.pctLabel(), panel.totalLabel()})
			{
				assertFalse("fitted short: " + label.getText(), label.getText().endsWith("..."));
			}
		});
	}

	private void hover(int index) throws Exception
	{
		onEdt(() ->
		{
			panel.setSize(LookRenderer.WIDTH, 3_000);
			LookRenderer.layoutTree(panel);
			final BankHistoryChart chart = LookRenderer.find(view(), BankHistoryChart.class);
			assertTrue("the chart is laid out", chart.getWidth() > 0);
			final int x = (int) Math.round(BankHistoryChart.xAt(index, chart.days().size(), chart.getWidth()));
			final MouseEvent e = new MouseEvent(chart, MouseEvent.MOUSE_MOVED, 0L, 0, x, 20, 0, false);
			for (MouseMotionListener l : chart.getMouseMotionListeners())
			{
				l.mouseMoved(e);
			}
		});
	}

	private void leaveChart() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryChart chart = LookRenderer.find(view(), BankHistoryChart.class);
			final MouseEvent e = new MouseEvent(chart, MouseEvent.MOUSE_EXITED, 0L, 0, -5, -5, 0, false);
			for (MouseListener l : chart.getMouseListeners())
			{
				l.mouseExited(e);
			}
		});
	}

	// ================================================================================================ the disk

	private PriceStore store()
	{
		return new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
	}

	private File historyFile(long account)
	{
		return new File(tmp.getRoot(), PriceStore.HISTORY_PREFIX + account + "-" + PROFILE + ".json");
	}

	private byte[] fileBytes(long account) throws IOException
	{
		final File file = historyFile(account);
		return file.isFile() ? Files.readAllBytes(file.toPath()) : new byte[0];
	}

	/** What is ON DISK, read by a store of its own. */
	private BankHistorySeries onDisk(long account)
	{
		final PriceStore.BankHistoryLoad load = store().loadBankHistory(account, PROFILE);
		assertNotEquals("the file reads", PriceStore.BankHistoryLoad.State.FAILED, load.state());
		return load.series();
	}

	private int corruptCopies()
	{
		final String[] names = tmp.getRoot().list();
		int n = 0;
		for (String name : names == null ? new String[0] : names)
		{
			if (name.contains(".corrupt-"))
			{
				n++;
			}
		}
		return n;
	}

	/** The history writes the service has handed the store, every session so far. */
	private int writes()
	{
		int n = 0;
		for (Invocation invocation : mockingDetails(f.store).getInvocations())
		{
			if ("recordBankHistory".equals(invocation.getMethod().getName()))
			{
				n++;
			}
		}
		return n;
	}

	/** One earlier session's reading of {@code day}: the bank's tradeable stacks alone, at local noon. */
	private void seed(long account, LocalDate day, long total)
	{
		final long[] cells = new long[BankHistoryPoint.CELLS];
		cells[BankHistoryPoint.BANK_TRADEABLE] = total;
		final long noon = at(day, 12, 0);
		assertTrue(store().recordBankHistory(account, PROFILE, new BankHistoryPoint(day, noon, noon, cells, null), SEEDING));
	}

	/**
	 * {@code count} daily readings from {@code start}, written as one file in the store's own format - schema 2, ten cells
	 * a reading: a record this build wrote, so nothing in it is a day before 1.0.9 (1.0.9 part 5).
	 */
	private void writeLongRecord(LocalDate start, int count) throws IOException
	{
		final StringBuilder json = new StringBuilder("{\"schema\":2,\"points\":[");
		for (int i = 0; i < count; i++)
		{
			final LocalDate day = start.plusDays(i);
			final long noon = at(day, 12, 0);
			json.append(i == 0 ? "" : ",").append("{\"day\":\"").append(day).append("\",\"readAtMillis\":").append(noon)
				.append(",\"bankAtMillis\":").append(noon).append(",\"card\":[").append(5_000_000L + 1_000L * i)
				.append(",0,0,0,0,0,0,0,0,0]}");
		}
		json.append("]}");
		Files.write(historyFile(MAIN).toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
		assertEquals(count, onDisk(MAIN).size());
	}

	// ================================================================================================ fixtures and words

	private long at(LocalDate day, int hour, int minute)
	{
		return day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli();
	}

	private static BankSnapshot withQuantity(BankSnapshot bank, int id, int quantity)
	{
		for (int i = 0; i < bank.items.size(); i++)
		{
			if (bank.items.get(i).id == id)
			{
				bank.items.set(i, bank.items.get(i).withQuantity(quantity));
				return bank;
			}
		}
		throw new AssertionError("no stack " + id);
	}

	private static BankSnapshot withDramen(BankSnapshot bank)
	{
		bank.items.add(new BankItem(PriceServiceTest.DRAMEN, 2, "Dramen staff", false, true, 1_500));
		return bank;
	}

	/** The fixture bank with 1,000,000 gp of coins in it and 791,078 gp in hand. */
	private static BankSnapshot cashBank(long capturedAt)
	{
		final BankSnapshot bank = PriceServiceTest.bankWithCarried(capturedAt, 791_078L);
		bank.currencyGp = 1_000_000L;
		return bank;
	}

	@Nullable
	private static Double pct(long delta, long from)
	{
		return from == 0L ? null : delta * 100.0d / from;
	}

	/** A day row with a reading, as it must print: date, sub-line, compact and exact total, % and gp vs the reading before. */
	private static List<String> readingRow(String date, String sub, long total, long delta, long from)
	{
		return Arrays.asList(date, sub, MovementMath.formatGp(total), MovementMath.formatExact(total),
			MovementMath.formatPctCompact(pct(delta, from), delta), delta == 0L ? "" : MovementRowPanel.signedGp(delta));
	}

	private static List<String> firstRow(String date, long total)
	{
		return Arrays.asList(date, BankHistoryDayRow.FIRST_READING, MovementMath.formatGp(total),
			MovementMath.formatExact(total), MovementMath.DASH, "");
	}

	private static List<String> carriedRow(String text)
	{
		return Collections.singletonList(text);
	}

	private static List<LocalDate> days(BankHistorySeries series)
	{
		final List<LocalDate> out = new ArrayList<>();
		for (BankHistoryPoint p : series.points())
		{
			out.add(p.day());
		}
		return out;
	}

	private static List<LocalDate> daysOf(List<BankHistoryMath.Day> drawn)
	{
		final List<LocalDate> out = new ArrayList<>();
		for (BankHistoryMath.Day d : drawn)
		{
			out.add(d.day());
		}
		return out;
	}

	private static List<Boolean> carriedOf(List<BankHistoryMath.Day> drawn)
	{
		final List<Boolean> out = new ArrayList<>();
		for (BankHistoryMath.Day d : drawn)
		{
			out.add(d.carried());
		}
		return out;
	}

	/** One of the chart's own range chips, 7d 30d 90d all: whether it is drawn in the dimmed grey. */
	private boolean viewChipDimmed(BankHistoryRange range) throws Exception
	{
		final boolean[] out = new boolean[1];
		onEdt(() -> out[0] = ColorScheme.MEDIUM_GRAY_COLOR.equals(
			BankHistoryViewTest.rangeCell(view(), range).getForeground()));
		return out[0];
	}
}
