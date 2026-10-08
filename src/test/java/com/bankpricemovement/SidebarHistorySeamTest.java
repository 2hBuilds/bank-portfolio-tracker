package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_PCT;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_TOTAL;
import static com.bankpricemovement.BankHistoryViewTest.chartLabel;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Addendum AU, the two halves TOGETHER (the integrator's step): the real {@link BankPriceMovementPanel} with the real
 * {@link BankHistoryView} inside it, over a REAL {@link PriceService} that loads a 40-day record from a REAL
 * {@link PriceStore} on a temporary folder, records today's reading beside it and publishes {@code Status} objects of
 * its own making - no mocked status anywhere. Every act goes through the road the client uses: the toggle's press, a
 * card chip's press, the view's own chip, the settings menu's check item (whose saved choice reaches the service
 * exactly as the plugin's {@code saveOptions} hands it on), the bank hold of addendum AS, and the dev bridge's words.
 *
 * <p>The service publishes through {@link SwingUtilities#invokeLater}, as the plugin wires it, so a publish lands on
 * the Swing thread AFTER the act that caused it - {@link #settle()} waits for it. The service and the panel share one
 * clock and one zone ({@code ZoneId.systemDefault()}), so "today" is one local day for both.
 */
public class SidebarHistorySeamTest
{
	private static final long ACCOUNT = PriceServiceTest.ACCOUNT;
	private static final String PROFILE = PriceServiceTest.PROFILE;
	private static final ZoneId ZONE = ZoneId.systemDefault();
	/** Coins in the bank and in the inventory: the cells the settings menu's "coins" switch moves. */
	private static final long BANK_CASH = 1_000_000L;
	private static final long CARRIED_CASH = 791_078L;
	/** Days back from today with no reading in the seeded record: three gaps, one of them two days long. */
	private static final Set<Integer> GAPS = new HashSet<>(Arrays.asList(4, 5, 17, 31));
	private static final int SEEDED_DAYS = 40;

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();
	private PriceStore disk;
	private PriceService service;
	private BankPriceMovementPanel panel;
	private Prefs prefs;
	private LocalDate today;

	/** The config as the plugin's prefs keep it: a saved switch set reaches the service, as saveOptions hands it on. */
	private final class Prefs implements BankPriceMovementPanel.Prefs
	{
		final List<RowFilter> filterSaves = new ArrayList<>();
		final List<ViewOptions> optionSaves = new ArrayList<>();

		@Override
		public RowFilter load()
		{
			return null;
		}

		@Override
		public void save(RowFilter filter)
		{
			filterSaves.add(filter);
		}

		@Override
		public ViewOptions loadOptions()
		{
			return ViewOptions.DEFAULT.withLivePrices(false);
		}

		@Override
		public void saveOptions(ViewOptions options)
		{
			optionSaves.add(options);
			// BankPriceMovementPlugin's saveOptions: the keys are written under the prefs-writer guard, and then the
			// service is told outside it, because the ConfigChanged that would have carried them was this thread's own.
			service.setOptions(options);
		}
	}

	@Before
	public void setUp() throws Exception
	{
		f.setUp();
		disk = new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
		when(f.store.loadBankHistory(anyLong(), any())).thenAnswer(invocation ->
			disk.loadBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1)));
		when(f.store.recordBankHistory(anyLong(), any(), any(), any(), any())).thenAnswer(invocation ->
			disk.recordBankHistory(invocation.<Long>getArgument(0), invocation.<String>getArgument(1),
				invocation.<BankHistoryPoint>getArgument(2), invocation.<LocalDate>getArgument(3),
				invocation.<BankHistorySeries.PlaceholderCheck>getArgument(4)));
		today = BankHistoryMath.dayOf(f.clock.get(), ZONE);
		seed();

		// A wiki that never answers: nothing here waits on a price table - RuneLite's own prices price every row.
		final GuidePriceClient wiki = mock(GuidePriceClient.class);
		when(wiki.fetchRevisionIndex(anyLong(), anyLong())).thenAnswer(invocation -> new CompletableFuture<>());
		when(wiki.fetchTables(anyCollection(), anyLong())).thenAnswer(invocation -> new CompletableFuture<>());
		when(wiki.fetchMapping(anyLong())).thenAnswer(invocation -> new CompletableFuture<>());
		service = new PriceService(wiki, f.store, f.itemManager, f.clientThread, f.scheduler, f.clock::get,
			SwingUtilities::invokeLater, null, ZONE);
		service.start();

		final ItemManager sprites = mock(ItemManager.class);
		when(sprites.getImage(anyInt(), anyInt(), anyBoolean()))
			.thenAnswer(invocation -> LookRenderer.sprite(invocation.getArgument(0)));
		prefs = new Prefs();
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(sprites, service, prefs);
			panel.setClock(f.clock::get);
		});
		service.setOptions(ViewOptions.DEFAULT.withLivePrices(false));
	}

	@After
	public void tearDown() throws Exception
	{
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
		if (service != null)
		{
			service.stop();
		}
	}

	/** Forty days ending yesterday, three gaps, cash in the bank and in the inventory on every reading. */
	private void seed()
	{
		for (int back = SEEDED_DAYS; back >= 1; back--)
		{
			if (GAPS.contains(back))
			{
				continue;
			}
			final LocalDate day = today.minusDays(back);
			final long[] cells = new long[BankHistoryPoint.CELLS];
			cells[BankHistoryPoint.BANK_TRADEABLE] = 40_000_000L + back * 173_000L;
			cells[BankHistoryPoint.BANK_CASH] = BANK_CASH;
			cells[BankHistoryPoint.CARRIED_TRADEABLE] = 250_000L;
			cells[BankHistoryPoint.CARRIED_CASH] = CARRIED_CASH;
			final long noon = day.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
			assertTrue(disk.recordBankHistory(ACCOUNT, PROFILE, new BankHistoryPoint(day, noon, noon, cells, null), today,
				null));
		}
		assertEquals(SEEDED_DAYS - GAPS.size(), disk.loadBankHistory(ACCOUNT, PROFILE).series().size());
	}

	/** The bank this account opens: the fixture's, with coins in the bank and the inventory. */
	private static BankSnapshot bank(long capturedAt)
	{
		final BankSnapshot bank = PriceServiceTest.bankWithCarried(capturedAt, CARRIED_CASH);
		bank.currencyGp = BANK_CASH;
		return bank;
	}

	/** Logged in and the bank read: the commit that loads the record, folds today in and publishes it. */
	private void login() throws Exception
	{
		service.setLoggedIn(true, ACCOUNT, PROFILE);
		service.setBank(bank(f.clock.get()));
		settle();
	}

	/** Lets every publish already posted to the Swing thread land, and anything they post in turn. */
	private static void settle() throws Exception
	{
		for (int i = 0; i < 3; i++)
		{
			SwingUtilities.invokeAndWait(() ->
			{
			});
		}
	}

	private Object state(String key)
	{
		final Object[] out = new Object[1];
		try
		{
			onEdt(() -> out[0] = panel.bankHistoryState().get(key));
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
		return out[0];
	}

	private BankHistoryView view()
	{
		return LookRenderer.find(panel, BankHistoryView.class);
	}

	private void pressHistory() throws Exception
	{
		onEdt(() -> press(((Container) LookRenderer.find(panel, Widgets.Toggle.class)).getComponent(1),
			MouseEvent.BUTTON1));
		settle();
	}

	private void pressCardChip(MovementWindow w) throws Exception
	{
		onEdt(() -> press(panel.windowChip(w), MouseEvent.BUTTON1));
		settle();
	}

	/** The rows of the History list, newest first. */
	private List<BankHistoryDayRow> dayRows()
	{
		final List<BankHistoryDayRow> out = new ArrayList<>();
		for (Component c : SidebarViewPanelTest.walk(view()))
		{
			if (c instanceof BankHistoryDayRow)
			{
				out.add((BankHistoryDayRow) c);
			}
		}
		return out;
	}

	@Nullable
	private BankHistoryDayRow rowOf(LocalDate day)
	{
		for (BankHistoryDayRow row : dayRows())
		{
			if (row.model().day().equals(day))
			{
				return row;
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- the series crosses the seam

	/**
	 * A real Status carrying the record arrives while Items shows: the view is not fed (it is not showing). The toggle
	 * is pressed: the view draws that very series - every reading, every calendar day, the four carried - and its
	 * readout is the card's own total to the gp, because today's reading and the total come from one commit.
	 */
	@Test
	public void aSeriesThatArrivesWhileItemsShowsIsDrawnTheMomentTheToggleIsPressed() throws Exception
	{
		login();
		final PriceService.Status status = panel.status();
		assertNotNull(status);
		final BankHistorySeries series = status.bankHistory();
		assertEquals("the forty seeded days less the gaps, and today's", SEEDED_DAYS - GAPS.size() + 1, series.size());
		assertEquals(SidebarView.ITEMS, panel.view());
		assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
		assertEquals("not fed while Items shows", 0, state("readings"));

		pressHistory();

		assertTrue("the toggle writes nothing", prefs.filterSaves.isEmpty() && prefs.optionSaves.isEmpty());
		assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());
		assertSame("the toggle asked the service for nothing", status, panel.status());
		assertEquals(series.size(), state("readings"));
		assertEquals(today.minusDays(SEEDED_DAYS).toString(), state("first"));
		assertEquals(today.toString(), state("last"));
		assertEquals("every calendar day from the first reading to today", SEEDED_DAYS + 1, state("rows"));
		assertEquals(GAPS.size(), state("carriedRows"));

		final long total = status.portfolio().valueNow();
		assertEquals("today's reading IS the card's total", total, series.on(today).valueFor(status.options()));
		onEdt(() ->
		{
			assertEquals(MovementMath.formatExact(total) + " gp", chartLabel(view(), READOUT_TOTAL));
			assertEquals(MovementMath.formatGp(total), panel.totalLabel().getText());
			assertEquals("1d vs your " + MovementMath.formatDay(today.minusDays(1)) + " total",
				panel.footnoteLabel().getText());
			assertEquals((SEEDED_DAYS - GAPS.size() + 1) + " days recorded since "
				+ MovementMath.formatDay(today.minusDays(SEEDED_DAYS)), panel.updateLabel().getText());
		});
		// And the record on disk now holds today beside the forty.
		assertEquals(series, disk.loadBankHistory(ACCOUNT, PROFILE).series());
	}

	// ---------------------------------------------------------------- the ranges

	/**
	 * A card chip moves the chart's range - and the card's move and the chart's change are then ONE figure; the view's
	 * own chip moves the chart alone: not the card's window, not the service's filter, nothing saved.
	 */
	@Test
	public void aCardChipMovesTheChartAndTheViewsOwnChipMovesNothingElse() throws Exception
	{
		login();
		pressHistory();
		assertEquals("1d draws 7d", "7d", state("range"));

		pressCardChip(MovementWindow.D30);
		assertEquals("30d", state("range"));
		assertEquals("the chip reached the service", MovementWindow.D30, service.filter().window());
		final String[] card = new String[2];
		onEdt(() ->
		{
			card[0] = panel.pctLabel().getText();
			card[1] = panel.footnoteLabel().getText();
			assertEquals("the card's move IS the chart's change", card[0], chartLabel(view(), CHANGE_PCT));
			assertEquals("30d vs your " + MovementMath.formatDay(today.minusDays(30)) + " total", card[1]);
		});
		pressCardChip(MovementWindow.D180);
		assertEquals("180d draws all", "all", state("range"));
		final int saves = prefs.filterSaves.size();
		onEdt(() ->
		{
			card[0] = panel.pctLabel().getText();
			card[1] = panel.footnoteLabel().getText();
		});

		// The view's own chips: the range bar's four cells under the chart, 7d 30d 90d all.
		onEdt(() -> press(BankHistoryViewTest.rangeCell(view(), BankHistoryRange.D90), MouseEvent.BUTTON1));
		settle();
		assertEquals("the view's chip moved the chart", "90d", state("range"));
		assertEquals("...and not the card", MovementWindow.D180, panel.filter().window());
		assertEquals("...nor the service", MovementWindow.D180, service.filter().window());
		assertEquals("...and saved nothing", saves, prefs.filterSaves.size());
		onEdt(() ->
		{
			assertEquals("the card is as it was", card[1], panel.footnoteLabel().getText());
			assertEquals(card[0], panel.pctLabel().getText());
		});

		pressCardChip(MovementWindow.D7);
		assertEquals("the card's next chip overrules the view's", "7d", state("range"));
		onEdt(() -> assertEquals(panel.pctLabel().getText(), chartLabel(view(), CHANGE_PCT)));
	}

	// ---------------------------------------------------------------- the counting switches

	/**
	 * A counting switch flipped in the settings menu goes the plugin's road to the service, which recomputes; the
	 * publish that answers redraws the card's History lines AND every figure of the view to the SAME totals - today's,
	 * the chart's change and every past day's row - and no stored reading changes (the cells ignore the switches).
	 */
	@Test
	public void aCountingSwitchFromTheSettingsMenuRedrawsTheCardAndTheViewToOneTotal() throws Exception
	{
		login();
		pressHistory();
		pressCardChip(MovementWindow.D7);
		final PriceService.Status before = panel.status();
		final LocalDate past = today.minusDays(9);
		final BankHistoryPoint pastPoint = before.bankHistory().on(past);
		assertNotNull(pastPoint);
		onEdt(() -> assertTrue(rowOf(past).model().toString(),
			rowOf(past).model().toString().contains(MovementMath.formatExact(pastPoint.valueFor(before.options())))));

		for (String which : new String[]{"cash", "inventory"})
		{
			onEdt(() -> ("cash".equals(which) ? panel.countCashItem() : panel.countInventoryItem()).doClick(0));
			settle();
			final PriceService.Status after = panel.status();
			assertNotSame(which + ": the service answered with a new status", before, after);
			final ViewOptions options = after.options();
			assertFalse(which, "cash".equals(which) ? options.countCash() : options.countInventory());
			assertEquals(which + ": the switch was saved, which told the service", options,
				prefs.optionSaves.get(prefs.optionSaves.size() - 1));
			assertEquals(which + ": no stored reading moved", before.bankHistory().upTo(today.minusDays(1)),
				after.bankHistory().upTo(today.minusDays(1)));
			final long total = after.portfolio().valueNow();
			assertEquals(which + ": today's reading is still the card's total", total,
				after.bankHistory().on(today).valueFor(options));
			onEdt(() ->
			{
				assertEquals(which, MovementMath.formatGp(total), panel.totalLabel().getText());
				assertEquals(which, MovementMath.formatExact(total) + " gp", chartLabel(view(), READOUT_TOTAL));
				assertEquals(which + ": the card's move and the chart's change", panel.pctLabel().getText(),
					chartLabel(view(), CHANGE_PCT));
				final String row = rowOf(past).model().toString();
				assertTrue(which + ": a past day redrawn under the switch: " + row,
					row.contains(MovementMath.formatExact(pastPoint.valueFor(options))));
				final String todayRow = rowOf(today).model().toString();
				assertTrue(which + ": today's row: " + todayRow, todayRow.contains(MovementMath.formatExact(total)));
			});
		}
		assertNotEquals("the totals did move", before.portfolio().valueNow(), panel.status().portfolio().valueNow());
		assertEquals("coins off, then the inventory off: what is left is the bank's tradeable stacks",
			pastPoint.card(BankHistoryPoint.BANK_TRADEABLE) + pastPoint.card(BankHistoryPoint.BANK_PARTS),
			pastPoint.valueFor(panel.status().options()));
	}

	// ---------------------------------------------------------------- the bank hold (addendum AS)

	/**
	 * With the bank open, a publish that restates the same bank at new prices - and so carries a new reading for today -
	 * is STORED while History shows: the view keeps the reading it drew. The bank closing replays it into the view and
	 * the card together.
	 */
	@Test
	public void theBankHoldStoresAPublishWhileHistoryShowsAndTheCloseReplaysItIntoTheView() throws Exception
	{
		login();
		pressHistory();
		final PriceService.Status drawn = panel.status();
		final String readout = readout();
		onEdt(() -> panel.setBankHold(true, true, 1, 0));

		// The 30-minute re-check while the sidebar is open, after RuneLite's price for one stack moved.
		f.runelitePrice(PriceServiceTest.BOX, 5_000);
		service.setVisible(true);
		f.fireTick();
		settle();
		final PriceService.Status restated = service.currentStatus();
		assertNotEquals("the re-check moved today's reading", drawn.bankHistory().on(today),
			restated.bankHistory().on(today));
		assertEquals("the same capture: a restatement, not a read", drawn.bankAtMillis(), restated.bankAtMillis());
		assertSame("held while the bank is open", drawn, panel.status());
		assertEquals("the view keeps what it drew", readout, readout());

		onEdt(() -> panel.setBankHold(false, false, 1, 0));
		settle();
		assertEquals("the close replays the stored publish", restated, panel.status());
		final long total = restated.portfolio().valueNow();
		assertEquals(MovementMath.formatExact(total) + " gp", readout());
		assertEquals(total, restated.bankHistory().on(today).valueFor(restated.options()));
		onEdt(() -> assertEquals(MovementMath.formatGp(total), panel.totalLabel().getText()));
	}

	// ---------------------------------------------------------------- 1.0.9 part 5: days before 1.0.9

	/**
	 * A record an older build wrote - schema 1, eight cells, eight days ending the day before yesterday - on the REAL
	 * store under the REAL service and the REAL panel. Until a reading is recorded into it every day is a legacy day: the
	 * tab draws the empty state (and the card says so) with the check box on screen. The first reading folded in is the
	 * first that counts the offers: the service stamps it, the store writes it as {@code freshFrom} beside the old days
	 * (which are not touched), and the tab shows that one day. Turned on, the box shows all nine; the bridge reads the
	 * marker back.
	 */
	@Test
	public void aRecordFromBeforeThisBuildIsHiddenUntilTheBoxIsOnAndTheFirstReadingStartsTheFreshDays() throws Exception
	{
		final net.runelite.client.util.Filepath file = disk.historyFile(ACCOUNT, PROFILE);
		final StringBuilder old = new StringBuilder("{\"schema\":1,\"points\":[");
		for (int back = 9; back >= 2; back--)
		{
			final LocalDate day = today.minusDays(back);
			final long noon = day.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
			old.append(back == 9 ? "" : ",").append("{\"day\":\"").append(day).append("\",\"readAtMillis\":").append(noon)
				.append(",\"bankAtMillis\":").append(noon).append(",\"card\":[").append(30_000_000L + back)
				.append(",1000000,0,0,250000,791078,0,0]}");
		}
		TestFilepaths.write(file, old.append("]}").toString());
		final BankHistorySeries migrated = disk.loadBankHistory(ACCOUNT, PROFILE).series();
		assertEquals(8, migrated.size());
		assertEquals(LocalDate.MAX, migrated.freshFrom());

		final java.util.List<String> asked = new ArrayList<>();
		final ItemManager sprites = mock(ItemManager.class);
		when(sprites.getImage(anyInt(), anyInt(), anyBoolean()))
			.thenAnswer(invocation -> LookRenderer.sprite(invocation.getArgument(0)));
		onEdt(() ->
		{
			panel.stop();
			panel = new BankPriceMovementPanel(sprites, service, prefs, text -> { }, question ->
			{
				asked.add(question);
				return true;
			});
			panel.setClock(f.clock::get);
		});

		login();

		// The first reading since the update was folded in and stamped: nine readings, the fresh start on today.
		final BankHistorySeries series = service.currentStatus().bankHistory();
		assertEquals(9, series.size());
		assertEquals(today, series.freshFrom());
		assertTrue(series.hasLegacyDays());
		assertEquals(Collections.singletonList(today), BankHistorySeriesTest.days(series.fromFresh()));
		// ...and the file says the same, with the old days still in it, padded to ten cells.
		final JsonObject written = new Gson().fromJson(TestFilepaths.read(file), JsonObject.class);
		assertEquals(2, written.get("schema").getAsInt());
		assertEquals(today.toString(), written.get("freshFrom").getAsString());
		assertEquals(9, written.getAsJsonArray("points").size());
		assertEquals(series, disk.loadBankHistory(ACCOUNT, PROFILE).series());

		pressHistory();
		final BpmCommands dev = new BpmCommands(panel, service, new Gson());
		JsonObject state = new Gson().fromJson(dev.apply("state"), JsonObject.class);
		assertEquals("only the first reading of this build", 1, state.getAsJsonObject("bankHistory").get("readings").getAsInt());
		assertEquals(today.toString(), state.getAsJsonObject("bankHistory").get("first").getAsString());
		assertEquals(today.toString(), state.getAsJsonObject("bankHistory").get("freshFrom").getAsString());
		assertTrue(state.getAsJsonObject("panel").get("legacyDays").getAsBoolean());
		assertFalse(state.getAsJsonObject("panel").get("includeLegacy").getAsBoolean());
		onEdt(() ->
		{
			// 1.1.0 part A: the check item is in the settings menu now, and the History header holds no box.
			boolean inMenu = false;
			for (java.awt.Component c : panel.heroMenu().getComponents())
			{
				inMenu |= c instanceof javax.swing.JMenuItem
					&& BankPriceMovementPanel.LEGACY_TEXT.equals(((javax.swing.JMenuItem) c).getText());
			}
			assertTrue("the item is in the settings menu", inMenu);
			assertEquals("1 day recorded", panel.updateLabel().getText());
		});

		state = new Gson().fromJson(dev.apply("legacy=on"), JsonObject.class);
		settle();
		assertEquals("asked once, in the words", java.util.Arrays.asList(BankPriceMovementPanel.LEGACY_ASK), asked);
		assertTrue(state.getAsJsonObject("panel").get("includeLegacy").getAsBoolean());
		assertEquals("every reading", 9, state.getAsJsonObject("bankHistory").get("readings").getAsInt());
		assertEquals(today.minusDays(9).toString(), state.getAsJsonObject("bankHistory").get("first").getAsString());
		assertEquals("every calendar day from the first reading to today, yesterday carried", 10,
			state.getAsJsonObject("bankHistory").get("rows").getAsInt());
		onEdt(() -> assertEquals("9 days recorded since " + MovementMath.formatDay(today.minusDays(9)),
			panel.updateLabel().getText()));

		state = new Gson().fromJson(dev.apply("legacy=off"), JsonObject.class);
		assertEquals(1, state.getAsJsonObject("bankHistory").get("readings").getAsInt());
		assertEquals("turning it off asked nothing", 1, asked.size());
		assertEquals("the file was not touched by any of it", series, disk.loadBankHistory(ACCOUNT, PROFILE).series());
	}

	private String readout() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() -> out[0] = chartLabel(view(), READOUT_TOTAL));
		return out[0];
	}

	// ---------------------------------------------------------------- the dev bridge

	/** {@code bpm view=}, {@code range=} and {@code state.bankHistory} through BpmCommands, the real panel and view. */
	@Test
	public void theDevBridgeDrivesTheRealPanelAndReadsTheRealView() throws Exception
	{
		login();
		final Gson gson = new Gson();
		final BpmCommands dev = new BpmCommands(panel, service, gson);

		JsonObject state = gson.fromJson(dev.apply("state"), JsonObject.class);
		assertEquals("ITEMS", state.get("view").getAsString());
		assertEquals(0, state.getAsJsonObject("bankHistory").get("readings").getAsInt());

		state = gson.fromJson(dev.apply("view=history"), JsonObject.class);
		settle();
		assertTrue(state.get("ok").getAsBoolean());
		assertEquals("HISTORY", state.get("view").getAsString());
		assertTrue("the toggle writes nothing", prefs.filterSaves.isEmpty() && prefs.optionSaves.isEmpty());
		JsonObject history = state.getAsJsonObject("bankHistory");
		assertEquals(SEEDED_DAYS - GAPS.size() + 1, history.get("readings").getAsInt());
		assertEquals(today.minusDays(SEEDED_DAYS).toString(), history.get("first").getAsString());
		assertEquals(today.toString(), history.get("last").getAsString());
		assertEquals("7d", history.get("range").getAsString());
		assertEquals(SEEDED_DAYS + 1, history.get("rows").getAsInt());
		assertEquals(GAPS.size(), history.get("carriedRows").getAsInt());
		assertTrue("no hover", history.get("hoverDay") == null || history.get("hoverDay").isJsonNull());
		assertEquals(BankPriceMovementPanel.CARD_HISTORY, state.getAsJsonObject("panel").get("card").getAsString());

		state = gson.fromJson(dev.apply("range=all"), JsonObject.class);
		assertEquals("all", state.getAsJsonObject("bankHistory").get("range").getAsString());
		assertEquals("the card's window is untouched", "D1", state.getAsJsonObject("panel").get("window").getAsString());
		assertEquals(MovementWindow.D1, service.filter().window());

		dev.apply("window=90d");
		settle();
		state = gson.fromJson(dev.apply("state"), JsonObject.class);
		assertEquals("the card's window moves the chart", "90d",
			state.getAsJsonObject("bankHistory").get("range").getAsString());
		assertEquals(MovementWindow.D90, service.filter().window());

		final JsonObject refused = gson.fromJson(dev.apply("range=1d"), JsonObject.class);
		assertFalse(refused.get("ok").getAsBoolean());
		assertEquals("range= wants 7d, 30d, 90d or all, not '1d'", refused.get("error").getAsString());

		state = gson.fromJson(dev.apply("view=items"), JsonObject.class);
		settle();
		assertEquals("ITEMS", state.get("view").getAsString());
		assertEquals(BankPriceMovementPanel.CARD_LIST, state.getAsJsonObject("panel").get("card").getAsString());
		assertTrue("the toggle writes no option", prefs.optionSaves.isEmpty());
		// The view keeps what it drew while Items shows: the echo still reads the record.
		assertEquals(SEEDED_DAYS - GAPS.size() + 1,
			state.getAsJsonObject("bankHistory").get("readings").getAsInt());
	}
}
