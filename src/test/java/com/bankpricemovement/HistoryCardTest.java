package com.bankpricemovement;

import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.TODAY;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.point;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static com.bankpricemovement.SidebarViewPanelTest.rows;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Bank value card in its History state (plan 7.2 item 5; the phase-0 contract's section 6 words and amendments
 * 9.6, 9.7 and 9.14): the headline is the portfolio's total in both views; the move line is the total now against
 * the reader's own RECORDED total of the window's day; the footnote names that day, with the span when the reading
 * used is older than the window's day, or says when a dimmed chip fills; the second line says how long the record is;
 * a dash stands for each with no reading at all. The card keeps its height, the three show / hide switches act on it
 * and a degraded status still reddens the footnote.
 *
 * <p>"Today" is 28 Sep 2026 on the panel's clock, and every reading is stamped at local noon (amendment 9.1).
 */
public class HistoryCardTest
{
	private ItemManager itemManager;
	private PriceService service;
	private SidebarViewPanelTest.Prefs prefs;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	@Before
	public void setUp() throws Exception
	{
		itemManager = mock(ItemManager.class);
		service = mock(PriceService.class);
		prefs = new SidebarViewPanelTest.Prefs();
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(itemManager, service, prefs);
			panel.setClock(() -> NOW);
			panel.setView(SidebarView.HISTORY);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	@After
	public void tearDown() throws Exception
	{
		onEdt(() -> panel.stop());
	}

	private void draw(BankHistorySeries series) throws Exception
	{
		final PriceService.Status s = status(series, VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	private static BankHistorySeries readings(LocalDate... days)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 0; i < days.length; i++)
		{
			points.add(point(days[i], VALUE_NOW - 2_000_000L + i * 500_000L));
		}
		return BankHistorySeries.of(points);
	}

	@Test
	public void withNoReadingEveryHistoryLineIsADash() throws Exception
	{
		draw(BankHistorySeries.EMPTY);
		onEdt(() ->
		{
			assertEquals(MovementMath.formatGp(VALUE_NOW), panel.totalLabel().getText());
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals("one dash on the move line, as with no guide move", "", panel.pctLabel().getText());
			assertEquals(MovementMath.DASH, panel.footnoteLabel().getText());
			assertEquals(MovementMath.DASH, panel.updateLabel().getText());
			assertEquals("no move, no coloured edge", Widgets.move(0, Widgets.Kind.EDGE), edge());
			for (MovementWindow w : MovementWindow.values())
			{
				final boolean lit = w == panel.filter().window();
				assertEquals(w + " lit", lit, Widgets.isLit(panel.windowChip(w)));
				assertEquals(w + ": no reading is old enough for any window, and the lit one stays orange", !lit,
					Widgets.isDim(panel.windowChip(w)));
			}
		});
	}

	@Test
	public void oneReadingTodaySaysOneDayAndTheLitChipFillsTomorrow() throws Exception
	{
		draw(readings(TODAY));
		onEdt(() ->
		{
			assertEquals("1 day recorded", panel.updateLabel().getText());
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals("1d from 29 Sep", panel.footnoteLabel().getText());
			panel.selectWindow(MovementWindow.D30);
			assertEquals("30d from 28 Oct", panel.footnoteLabel().getText());
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
		});
	}

	@Test
	public void aReadingOldEnoughIsComparedAgainstAndNamed() throws Exception
	{
		// 27 Sep is worth VALUE_NOW - 1.5m, 28 Sep VALUE_NOW - 1.0m: the move is the card's total NOW against 27 Sep.
		draw(readings(TODAY.minusDays(2), TODAY.minusDays(1), TODAY));
		onEdt(() ->
		{
			assertEquals("3 days recorded since 26 Sep", panel.updateLabel().getText());
			assertEquals("1d vs your 27 Sep total", panel.footnoteLabel().getText());
			final long delta = 1_500_000L;
			assertEquals(MovementMath.formatDelta(delta), panel.deltaLabel().getText());
			assertEquals(MovementMath.formatPct(delta * 100.0d / (VALUE_NOW - 1_500_000L), delta),
				panel.pctLabel().getText());
			assertEquals("a rise is green", Widgets.move(1, Widgets.Kind.FIGURE), panel.deltaLabel().getForeground());
			assertEquals("...and so is the card's edge", Widgets.move(1, Widgets.Kind.EDGE), edge());
			assertFalse(Widgets.isDim(panel.windowChip(MovementWindow.D1)));
		});
	}

	/** Amendment 9.6: the span is said exactly when the reading used is older than the window's own day. */
	@Test
	public void aReadingOlderThanTheWindowsDayCarriesItsSpan() throws Exception
	{
		draw(readings(TODAY.minusDays(3), TODAY));
		onEdt(() ->
		{
			assertEquals("1d vs your 25 Sep total (3 days)", panel.footnoteLabel().getText());
			assertEquals("2 days recorded since 25 Sep", panel.updateLabel().getText());
			assertFalse("an old reading still fills the chip", Widgets.isDim(panel.windowChip(MovementWindow.D1)));
			panel.selectWindow(MovementWindow.D7);
			assertEquals("7d from 02 Oct", panel.footnoteLabel().getText());
		});
		draw(readings(TODAY.minusDays(9), TODAY));
		onEdt(() ->
		{
			assertEquals("7d vs your 19 Sep total (9 days)", panel.footnoteLabel().getText());
			panel.selectWindow(MovementWindow.D1);
			assertEquals("1d vs your 19 Sep total (9 days)", panel.footnoteLabel().getText());
		});
		// The reading OF the window's day carries no span.
		draw(readings(TODAY.minusDays(7), TODAY));
		onEdt(() ->
		{
			panel.selectWindow(MovementWindow.D7);
			assertEquals("7d vs your 21 Sep total", panel.footnoteLabel().getText());
		});
	}

	/**
	 * A chip whose window has no reading old enough is DIMMED - a third state, a ColorScheme grey - and still takes a
	 * press; picked, the move line is a dash and the footnote says when it fills.
	 */
	@Test
	public void aDimmedChipIsGreyStillClickableAndItsFootnoteSaysWhenItFills() throws Exception
	{
		draw(readings(LocalDate.of(2026, 9, 10), TODAY.minusDays(1), TODAY));
		onEdt(() ->
		{
			assertFalse(Widgets.isDim(panel.windowChip(MovementWindow.D1)));
			assertFalse(Widgets.isDim(panel.windowChip(MovementWindow.D7)));
			assertTrue(Widgets.isDim(panel.windowChip(MovementWindow.D30)));
			assertTrue(Widgets.isDim(panel.windowChip(MovementWindow.D90)));
			assertTrue(Widgets.isDim(panel.windowChip(MovementWindow.D180)));
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, panel.windowChip(MovementWindow.D30).getForeground());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, panel.windowChip(MovementWindow.D7).getForeground());

			// The mouse lights it like any chip, and it goes back to ITS grey.
			Widgets.hoverChip(panel.windowChip(MovementWindow.D30), true);
			assertEquals(ColorScheme.BRAND_ORANGE, panel.windowChip(MovementWindow.D30).getForeground());
			Widgets.hoverChip(panel.windowChip(MovementWindow.D30), false);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, panel.windowChip(MovementWindow.D30).getForeground());

			press(panel.windowChip(MovementWindow.D30), MouseEvent.BUTTON1);
			assertEquals("still clickable", MovementWindow.D30, panel.filter().window());
			assertTrue(Widgets.isLit(panel.windowChip(MovementWindow.D30)));
			assertFalse("lit wins over dimmed", Widgets.isDim(panel.windowChip(MovementWindow.D30)));
			assertEquals(MovementMath.DASH, panel.deltaLabel().getText());
			assertEquals("30d from 10 Oct", panel.footnoteLabel().getText());
			assertEquals("3 days recorded since 10 Sep", panel.updateLabel().getText());

			panel.selectWindow(MovementWindow.D7);
			assertEquals("7d vs your 10 Sep total (18 days)", panel.footnoteLabel().getText());
		});
		// Items has no dimmed chip whatever the record says.
		onEdt(() ->
		{
			panel.pressView(SidebarView.ITEMS);
			for (MovementWindow w : MovementWindow.values())
			{
				assertFalse(w + " in Items", Widgets.isDim(panel.windowChip(w)));
			}
		});
	}

	/**
	 * Amendment 9.6: the recorded total is read under the switches the status was COMPUTED under, not the defaults - a
	 * reading holding cash and carried items is compared without them when the reader counts neither.
	 */
	@Test
	public void theRecordedTotalIsReadUnderTheStatussOwnSwitches() throws Exception
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (LocalDate day : new LocalDate[]{TODAY.minusDays(1), TODAY})
		{
			final long[] cells = new long[BankHistoryPoint.CELLS];
			cells[BankHistoryPoint.BANK_TRADEABLE] = VALUE_NOW - 4_000_000L;
			cells[BankHistoryPoint.BANK_CASH] = 3_000_000L;
			cells[BankHistoryPoint.CARRIED_TRADEABLE] = 2_000_000L;
			final long noon = SidebarViewPanelTest.noon(day);
			points.add(new BankHistoryPoint(day, noon, noon, cells, null));
		}
		final BankHistorySeries series = BankHistorySeries.of(points);
		final ViewOptions options = ViewOptions.DEFAULT.withCountCash(false).withCountInventory(false);
		final PriceService.Status s = status(series, VALUE_NOW, NOW - 60_000L);
		when(s.options()).thenReturn(options);
		onEdt(() -> listener.onRows(rows(3), s));
		onEdt(() ->
		{
			final long then = points.get(0).valueFor(options);
			assertEquals("the premise: the switches change the reading", VALUE_NOW - 4_000_000L, then);
			assertTrue("...and the defaults would count more", points.get(0).valueFor(ViewOptions.DEFAULT) > then);
			final long delta = VALUE_NOW - then;
			assertEquals("1d vs your 27 Sep total", panel.footnoteLabel().getText());
			assertEquals(MovementMath.formatDelta(delta), panel.deltaLabel().getText());
			assertEquals(MovementMath.formatPct(delta * 100.0d / then, delta), panel.pctLabel().getText());
		});
	}

	/**
	 * Ruling 9.7: in History the second line ("n days recorded since d") carries no hover - the guide-price sentence
	 * would describe a line that is not there - and in Items the guide-price hover comes back.
	 */
	@Test
	public void theRecordLineCarriesNoHoverAndItemsGetsItsOwnBack() throws Exception
	{
		draw(readings(TODAY.minusDays(1), TODAY));
		onEdt(() ->
		{
			assertEquals(SidebarView.HISTORY, panel.view());
			assertNull("no hover on the record line", panel.updateLabel().getToolTipText());
			panel.pressView(SidebarView.ITEMS);
			assertNotNull("Items' update line has its hover back", panel.updateLabel().getToolTipText());
			panel.pressView(SidebarView.HISTORY);
			assertNull("...and loses it again in History", panel.updateLabel().getToolTipText());
		});
	}

	/** The headline is the same figure in both views, and Items keeps its own footnote and update line. */
	@Test
	public void theHeadlineIsThePortfoliosTotalInBothViews() throws Exception
	{
		draw(readings(TODAY.minusDays(1), TODAY));
		final AtomicReference<String> history = new AtomicReference<>();
		onEdt(() ->
		{
			history.set(panel.totalLabel().getText());
			panel.pressView(SidebarView.ITEMS);
			assertEquals(history.get(), panel.totalLabel().getText());
			assertEquals(MovementMath.formatGp(VALUE_NOW), panel.totalLabel().getText());
			assertTrue("Items' footnote is the guide provenance again", panel.footnoteLabel().getText().startsWith("1d vs "));
			assertFalse(panel.footnoteLabel().getText().contains("your"));
			assertEquals(BankPriceMovementPanel.updateText(ViewOptions.DEFAULT), panel.updateLabel().getText());
			assertEquals(MovementMath.formatDelta(-950_972L), panel.deltaLabel().getText());
		});
	}

	/** The card keeps its height in both views (the glow ring and the pictures depend on it), in every state. */
	@Test
	public void theCardKeepsItsHeightInBothViews() throws Exception
	{
		final BankHistorySeries[] states = {BankHistorySeries.EMPTY, readings(TODAY), readings(TODAY.minusDays(3), TODAY),
			readings(TODAY.minusDays(1), TODAY)};
		for (BankHistorySeries s : states)
		{
			draw(s);
			for (HeroVisibility v : new HeroVisibility[]{HeroVisibility.ALL, HeroVisibility.NONE})
			{
				onEdt(() ->
				{
					panel.applyHeroVisibility(v);
					panel.pressView(SidebarView.HISTORY);
					final int inHistory = panel.hero().getPreferredSize().height;
					panel.pressView(SidebarView.ITEMS);
					final int inItems = panel.hero().getPreferredSize().height;
					assertEquals(s.size() + " readings, " + v, inItems, inHistory);
				});
			}
		}
	}

	/** The three show / hide switches act on the card in History too. */
	@Test
	public void theShowHideSwitchesActOnTheHistoryCard() throws Exception
	{
		draw(readings(TODAY.minusDays(1), TODAY));
		onEdt(() ->
		{
			panel.applyHeroVisibility(HeroVisibility.NONE);
			assertFalse("the total is removed", panel.shows(panel.totalLabel()));
			assertFalse("and the move line", panel.shows(panel.moveLine()));
			assertTrue("the footnote stays", panel.shows(panel.footnoteLabel()));
			assertTrue("and the record line", panel.shows(panel.updateLabel()));
			assertEquals("1d vs your 27 Sep total", panel.footnoteLabel().getText());
			panel.applyHeroVisibility(HeroVisibility.ALL);
			assertTrue(panel.shows(panel.totalLabel()));
			assertTrue(panel.shows(panel.deltaLabel()));
			assertTrue(panel.shows(panel.pctLabel()));
		});
	}

	/** A degraded status still reddens the footnote in History. */
	@Test
	public void aDegradedStatusStillReddensTheHistoryFootnote() throws Exception
	{
		final PriceService.Status s = status(readings(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		when(s.degraded()).thenReturn(true);
		when(s.degradedReason()).thenReturn("The last wiki history fetch failed");
		onEdt(() -> listener.onRows(rows(3), s));
		onEdt(() ->
		{
			assertEquals("1d vs your 27 Sep total", panel.footnoteLabel().getText());
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.footnoteLabel().getForeground());
		});
	}

	/** The words, as pure functions: 0, 1 and n readings; the span; the fill date. */
	@Test
	public void theTwoLinesAsWritten()
	{
		assertEquals(MovementMath.DASH, BankPriceMovementPanel.historyRecorded(BankHistorySeries.EMPTY));
		assertEquals(MovementMath.DASH, BankPriceMovementPanel.historyRecorded(null));
		assertEquals("1 day recorded", BankPriceMovementPanel.historyRecorded(readings(TODAY)));
		assertEquals("37 days recorded since 18 Aug", BankPriceMovementPanel.historyRecorded(everyDay(
			LocalDate.of(2026, 8, 18), 37)));

		assertEquals(MovementMath.DASH, BankPriceMovementPanel.historyFootnoteForms(BankHistorySeries.EMPTY,
			TODAY, MovementWindow.D30, null, false).get(0));
		final BankHistorySeries record = everyDay(LocalDate.of(2026, 8, 18), 37);
		final BankHistoryMath.Change change = BankHistoryMath.sinceDay(record, LocalDate.of(2026, 9, 26), 30,
			ViewOptions.DEFAULT, VALUE_NOW);
		assertEquals("30d vs your 27 Aug total", BankPriceMovementPanel.historyFootnoteForms(record,
			LocalDate.of(2026, 9, 26), MovementWindow.D30, change, false).get(0));
		assertEquals("30d from 17 Oct", BankPriceMovementPanel.historyFootnoteForms(readings(LocalDate.of(2026, 9,
			17)), TODAY, MovementWindow.D30, null, false).get(0));
	}

	/**
	 * While the client is at the login screen the History footnote ends " - logged out", as the Items footnote does,
	 * and reads "logged out" alone with no reading at all; logged in it says neither. The forms come longest first
	 * and the card draws the first that fits its 191 px line, so the line gives up the words "your" and "total" -
	 * and, logged out, the span - before it would lose its end to an ellipsis.
	 */
	@Test
	public void theFootnoteSaysLoggedOutAndGivesUpWordsNotItsEnd() throws Exception
	{
		final BankHistorySeries record = everyDay(LocalDate.of(2026, 8, 18), 37);
		final LocalDate day = LocalDate.of(2026, 9, 26);
		final BankHistoryMath.Change vs = BankHistoryMath.sinceDay(record, day, 30, ViewOptions.DEFAULT, VALUE_NOW);
		assertEquals(java.util.Arrays.asList("30d vs your 27 Aug total", "30d vs 27 Aug"),
			BankPriceMovementPanel.historyFootnoteForms(record, day, MovementWindow.D30, vs, false));
		assertEquals(java.util.Arrays.asList("30d vs your 27 Aug total - logged out", "30d vs 27 Aug - logged out"),
			BankPriceMovementPanel.historyFootnoteForms(record, day, MovementWindow.D30, vs, true));

		final BankHistorySeries young = readings(LocalDate.of(2026, 9, 17));
		assertEquals(java.util.Collections.singletonList("30d from 17 Oct"),
			BankPriceMovementPanel.historyFootnoteForms(young, TODAY, MovementWindow.D30, null, false));
		assertEquals(java.util.Collections.singletonList("30d from 17 Oct - logged out"),
			BankPriceMovementPanel.historyFootnoteForms(young, TODAY, MovementWindow.D30, null, true));

		assertEquals(java.util.Collections.singletonList(MovementMath.DASH),
			BankPriceMovementPanel.historyFootnoteForms(BankHistorySeries.EMPTY, TODAY, MovementWindow.D30, null, false));
		assertEquals(java.util.Collections.singletonList("logged out"),
			BankPriceMovementPanel.historyFootnoteForms(BankHistorySeries.EMPTY, TODAY, MovementWindow.D30, null, true));

		// A gap in the record: the span is said, and logged out it is the LAST thing to give way.
		final BankHistorySeries gapped = readings(LocalDate.of(2026, 3, 25), day);
		final BankHistoryMath.Change old = BankHistoryMath.sinceDay(gapped, day, 180, ViewOptions.DEFAULT, VALUE_NOW);
		assertEquals(java.util.Arrays.asList("180d vs your 25 Mar total (185 days)", "180d vs 25 Mar (185 days)"),
			BankPriceMovementPanel.historyFootnoteForms(gapped, day, MovementWindow.D180, old, false));
		assertEquals(java.util.Arrays.asList("180d vs your 25 Mar total (185 days) - logged out",
			"180d vs 25 Mar (185 days) - logged out", "180d vs 25 Mar - logged out"),
			BankPriceMovementPanel.historyFootnoteForms(gapped, day, MovementWindow.D180, old, true));

		// On the drawn label nothing is ever cut: every form of every case, in the label's own font.
		final PriceService.Status in = status(readings(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), in));
		onEdt(() -> assertEquals("1d vs your 27 Sep total", panel.footnoteLabel().getText()));
		final PriceService.Status out = status(readings(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		when(out.loggedIn()).thenReturn(false);
		onEdt(() -> listener.onRows(rows(3), out));
		onEdt(() ->
		{
			final javax.swing.JLabel label = panel.footnoteLabel();
			assertTrue(label.getText(), label.getText().endsWith(" - logged out"));
			assertTrue(label.getText(), label.getText().startsWith("1d vs "));
			final java.awt.FontMetrics fm = label.getFontMetrics(label.getFont());
			for (final boolean loggedOut : new boolean[]{false, true})
			{
				for (final java.util.List<String> forms : java.util.Arrays.asList(
					BankPriceMovementPanel.historyFootnoteForms(record, day, MovementWindow.D30, vs, loggedOut),
					BankPriceMovementPanel.historyFootnoteForms(gapped, day, MovementWindow.D180, old, loggedOut),
					BankPriceMovementPanel.historyFootnoteForms(young, TODAY, MovementWindow.D180, null, loggedOut)))
				{
					final String drawn = BankPriceMovementPanel.firstThatFits(label, forms, 191);
					assertTrue(drawn + " fits the card's line", fm.stringWidth(drawn) <= 191);
					assertEquals("so the fitter cuts nothing", drawn, Widgets.fit(label.getFont(), drawn, 191));
					assertEquals(drawn, loggedOut, drawn.endsWith("logged out"));
				}
			}
		});
	}

	/** The colour of the card's left edge ({@link Widgets#card}: a matte border outside the padding). */
	private java.awt.Color edge()
	{
		return ((javax.swing.border.MatteBorder) ((javax.swing.border.CompoundBorder) panel.hero().getBorder())
			.getOutsideBorder()).getMatteColor();
	}

	private static BankHistorySeries everyDay(LocalDate first, int n)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			points.add(point(first.plusDays(i), VALUE_NOW));
		}
		return BankHistorySeries.of(points);
	}
}
