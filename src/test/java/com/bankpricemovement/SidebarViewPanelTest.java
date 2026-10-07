package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JToolTip;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Addendum AU's panel half (plan 7.2 items 1, 6-8 and 12; the phase-0 contract's section 6 and amendments 9.8-9.11):
 * the Items | Net Worth History toggle and its two captions, the card the sidebar chooses in each view, the header's
 * rows in each view, the bank hold the toggle must never lift, the History chart's range following the card's window by
 * every road, and the pager going to the list that is showing.
 *
 * <p>Every timestamp is built at LOCAL noon (amendment 9.1), so "today" is one calendar day wherever the suite runs.
 */
public class SidebarViewPanelTest
{
	static final LocalDate TODAY = LocalDate.of(2026, 9, 28);
	static final long NOW = noon(TODAY);
	static final long VALUE_NOW = 736_412_683L;

	private ItemManager itemManager;
	private PriceService service;
	private Prefs prefs;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config, as a memory: what was loaded and every write, in order. */
	static final class Prefs implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		RowFilter stored;
		@Nullable
		Boolean storedFold = Boolean.TRUE;
		final List<Boolean> foldSaves = new ArrayList<>();
		final List<RowFilter> saves = new ArrayList<>();
		/** 1.0.9 part 5: what "Include days before v1.0.9" was stored as (null = nothing stored), and every write of it. */
		@Nullable
		Boolean storedLegacy;
		final List<Boolean> legacySaves = new ArrayList<>();

		@Override
		public RowFilter load()
		{
			return stored;
		}

		@Override
		public void save(RowFilter filter)
		{
			saves.add(filter);
		}

		@Override
		public Boolean loadFoldOpen()
		{
			return storedFold;
		}

		@Override
		public void saveFoldOpen(boolean open)
		{
			foldSaves.add(open);
		}

		@Override
		public Boolean loadIncludeLegacy()
		{
			return storedLegacy;
		}

		@Override
		public void saveIncludeLegacy(boolean include)
		{
			legacySaves.add(include);
		}

		/** 1.1.1 part G2: every write of "Single chart colour", in order. */
		final List<Boolean> singleSaves = new ArrayList<>();

		@Override
		public void saveSingleChartColour(boolean single)
		{
			singleSaves.add(single);
		}
	}

	@Before
	public void setUp()
	{
		itemManager = mock(ItemManager.class);
		service = mock(PriceService.class);
		prefs = new Prefs();
	}

	@After
	public void tearDown() throws Exception
	{
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
	}

	private void build() throws Exception
	{
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(itemManager, service, prefs);
			panel.setClock(() -> NOW);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	/** {@link #build()} with the History tab's question answered by {@code prompt} instead of a window. */
	private void build(BankPriceMovementPanel.LegacyPrompt prompt) throws Exception
	{
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(itemManager, service, prefs, text -> { }, prompt);
			panel.setClock(() -> NOW);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	private void publish(List<MovementRow> rows, PriceService.Status status) throws Exception
	{
		onEdt(() -> listener.onRows(rows, status));
	}

	// ---------------------------------------------------------------- the toggle and its captions

	@Test
	public void theCaptionsAreTheUsersFinalWords()
	{
		assertEquals("Item price changes", BankPriceMovementPanel.ITEMS_CAPTION);
		assertEquals("Bank net worth history", BankPriceMovementPanel.HISTORY_CAPTION);
		assertEquals("HISTORY", BankPriceMovementPanel.CARD_HISTORY);
	}

	/**
	 * The toggle both ways: a left press on the unlit half switches the view, relights the toggle, rewrites the
	 * caption and writes NOTHING; the lit half, a right press and the same view again write nothing; {@code setView} switches the same way; a stopped panel does neither.
	 */
	@Test
	public void theToggleSwitchesTheViewAndWritesNothing() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			assertTrue("Items is lit on a fresh install", toggle().isLit(0));
			// The first live look (2026-09-29): "instead of being called 'history' call it 'net worth tracker'".
			assertEquals("Items", half(0).getText());
			assertEquals("Net Worth History", half(1).getText());
			assertEquals(BankPriceMovementPanel.ITEMS_CAPTION, caption().getText());
			assertLitLook(half(0), true);
			assertLitLook(half(1), false);

			press(half(1), MouseEvent.BUTTON1);
			assertEquals(SidebarView.HISTORY, panel.view());
			assertTrue(toggle().isLit(1));
			assertLitLook(half(1), true);
			assertLitLook(half(0), false);
			assertEquals(BankPriceMovementPanel.HISTORY_CAPTION, caption().getText());
			assertNothingWritten();

			press(half(1), MouseEvent.BUTTON1);
			assertNothingWritten();
			press(half(0), MouseEvent.BUTTON3);
			assertEquals("a right press is not a press", SidebarView.HISTORY, panel.view());
			panel.pressView(SidebarView.HISTORY);
			assertNothingWritten();

			press(half(0), MouseEvent.BUTTON1);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertEquals(BankPriceMovementPanel.ITEMS_CAPTION, caption().getText());
			assertNothingWritten();

			panel.setView(SidebarView.HISTORY);
			assertEquals("the settings road switches...", SidebarView.HISTORY, panel.view());
			assertTrue(toggle().isLit(1));
			assertNothingWritten();
			panel.setView(null);
			assertEquals("null reads as Items", SidebarView.ITEMS, panel.view());

			panel.stop();
			panel.pressView(SidebarView.HISTORY);
			panel.setView(SidebarView.HISTORY);
			assertEquals("a stopped panel switches nothing", SidebarView.ITEMS, panel.view());
			assertNothingWritten();
		});
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
	}

	/** No hover on the strip at any setting (ruling 9.7): the two words say what they do. */
	@Test
	public void theStripCarriesNoHoverEvenWithHoverTextOn() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			for (Component c : walk(strip()))
			{
				if (c instanceof javax.swing.JComponent)
				{
					assertNull(c + " carries a hover", ((javax.swing.JComponent) c).getToolTipText());
				}
			}
			assertEquals("the strip is the content width", Widgets.CONTENT_WIDTH, strip().getPreferredSize().width);
			assertEquals(Widgets.TOGGLE_HEIGHT, toggle().getPreferredSize().height);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, caption().getForeground());
		});
	}

	/** Nothing the toggle or setView does reaches Prefs: no filter, no fold, no options - the view is per session. */
	private void assertNothingWritten()
	{
		assertTrue("the view writes nothing through Prefs", prefs.saves.isEmpty() && prefs.foldSaves.isEmpty());
	}

	/**
	 * The sidebar ALWAYS opens on Items (the user, 2026-09-29: "Yes, always open on Items, no setting"): a panel built
	 * after History was pressed in an earlier panel opens on Items, with the Items half lit and its caption, and
	 * pressing the toggle wrote nothing through Prefs.
	 */
	@Test
	public void aPanelBuiltAfterHistoryWasPressedOpensOnItems() throws Exception
	{
		build();
		publish(rows(3), status(series(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			press(half(1), MouseEvent.BUTTON1);
			assertEquals(SidebarView.HISTORY, panel.view());
			assertNothingWritten();
			final BankPriceMovementPanel later = new BankPriceMovementPanel(itemManager, service, prefs);
			try
			{
				assertEquals(SidebarView.ITEMS, later.view());
				assertEquals(BankPriceMovementPanel.CARD_LOGIN, later.card());
			}
			finally
			{
				later.stop();
			}
		});
		assertNothingWritten();
	}

	// ---------------------------------------------------------------- the card chosen

	/** Amendment 9.9's table: LOGIN and NO_BANK first, then History whatever the rows are, then Items' own two. */
	@Test
	public void theCardIsChosenByTheTableOfAmendmentNinePointNine() throws Exception
	{
		build();
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			assertEquals("no status yet", BankPriceMovementPanel.CARD_LOGIN, panel.card());
		});
		publish(Collections.emptyList(), bare(false, false));
		onEdt(() -> assertEquals("logged out, no bank", BankPriceMovementPanel.CARD_LOGIN, panel.card()));
		publish(Collections.emptyList(), bare(true, false));
		onEdt(() -> assertEquals("logged in, no bank", BankPriceMovementPanel.CARD_NO_BANK, panel.card()));
		publish(Collections.emptyList(), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertEquals("History whatever the rows are", BankPriceMovementPanel.CARD_HISTORY, panel.card()));
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());
			// The shot prints the History column's holder, which holds the view.
			final Component body = panel.shotComponents().get(1);
			assertSame(panel.header(), panel.shotComponents().get(0));
			assertNotNull("the History view is in the shot's body", find(body, BankHistoryView.class));
			assertNull("...and the item list is not", find(body, MovementRowPanel.class));

			panel.setView(SidebarView.ITEMS);
			assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
			assertSame(panel.listView(), panel.shotComponents().get(1));
		});
		publish(Collections.emptyList(), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card()));
		publish(Collections.emptyList(), bare(true, false));
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			assertEquals("no bank beats the view", BankPriceMovementPanel.CARD_NO_BANK, panel.card());
		});
	}

	/**
	 * The header in each view (amendment 9.9): hero, strip, control row and fold (while open) in Items - then, last, the
	 * search box (1.0.9 part 4), which History has no list for; hero and strip in History - with the problem row last in
	 * History and just above the search box in Items - and nothing without a bank. The fold's state is KEPT, not
	 * written, while History hides it.
	 */
	@Test
	public void theHeaderCarriesTheControlsInItemsAndOnlyTheStripInHistory() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertTrue(panel.foldOpen());
			assertEquals(Arrays.asList(panel.hero(), strip(), panel.controlRow(), panel.fold(), searchRow()), header());
			panel.pressView(SidebarView.HISTORY);
			assertEquals(Arrays.asList(panel.hero(), strip()), header());
			assertTrue("the fold keeps its state", panel.foldOpen());
			panel.pressView(SidebarView.ITEMS);
			assertEquals("...and comes back as the reader left it",
				Arrays.asList(panel.hero(), strip(), panel.controlRow(), panel.fold(), searchRow()), header());
		});
		assertTrue("switching views never writes the fold", prefs.foldSaves.isEmpty());

		final PriceService.Status troubled = status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L);
		when(troubled.problem()).thenReturn("Wiki history down - no movement");
		when(troubled.problemKind()).thenReturn(PriceService.ProblemKind.HISTORY_DOWN);
		publish(rows(3), troubled);
		onEdt(() ->
		{
			assertEquals(Arrays.asList(panel.hero(), strip(), panel.controlRow(), panel.fold(), panel.problemLabel(),
				searchRow()), header());
			panel.pressView(SidebarView.HISTORY);
			assertEquals("the problem row stays in History", Arrays.asList(panel.hero(), strip(), panel.problemLabel()),
				header());
		});
		publish(Collections.emptyList(), bare(true, false));
		onEdt(() -> assertEquals("no bank, no header - strip included", 0, header().size()));
	}

	// ---------------------------------------------------------------- the bank hold (addendum AS)

	/**
	 * Plan 7.2 item 8: the toggle NEVER lifts the hold. With the bank open, a publish is stored; switching to History
	 * draws the last-DRAWN status (its series in the view, its total on the card); a publish after the switch is
	 * still stored; the bank closing replays it into History.
	 */
	@Test
	public void theToggleNeverLiftsTheBankHoldAndHistoryDrawsTheLastDrawnStatus() throws Exception
	{
		build();
		final long bankAt = NOW - 60_000L;
		final PriceService.Status drawn = status(series(TODAY.minusDays(2), TODAY.minusDays(1)), VALUE_NOW, bankAt);
		publish(rows(3), drawn);
		onEdt(() -> panel.setBankHold(true, true, 1, 0));

		// The same capture - a re-statement - so it is held rather than read.
		final PriceService.Status held = status(series(TODAY.minusDays(3), TODAY.minusDays(2), TODAY.minusDays(1),
			TODAY), VALUE_NOW + 5_000_000L, bankAt);
		publish(rows(3), held);
		final int[] before = new int[1];
		onEdt(() ->
		{
			assertSame("held while the bank is open", drawn, panel.status());
			before[0] = panel.rebuilds();
			panel.pressView(SidebarView.HISTORY);
			assertEquals("the toggle rebuilds no row", before[0], panel.rebuilds());
			assertSame("History is drawn from the status on screen", drawn, panel.status());
			assertEquals(2, panel.bankHistoryState().get("readings"));
			assertEquals(TODAY.minusDays(1).toString(), panel.bankHistoryState().get("last"));
			assertEquals(MovementMath.formatGp(VALUE_NOW), panel.totalLabel().getText());
		});

		// A publish AFTER the switch is still held: the switch lifted nothing.
		publish(rows(3), held);
		onEdt(() ->
		{
			assertSame("still held after the toggle", drawn, panel.status());
			assertEquals(2, panel.bankHistoryState().get("readings"));
			panel.pressView(SidebarView.ITEMS);
			assertEquals("the toggle back rebuilds no row either", before[0], panel.rebuilds());
			assertSame("...and lifts nothing", drawn, panel.status());
			panel.pressView(SidebarView.HISTORY);
			assertEquals(before[0], panel.rebuilds());
		});

		onEdt(() -> panel.setBankHold(false, false, 1, 1));
		onEdt(() ->
		{
			assertSame("the close replays the stored publish", held, panel.status());
			assertEquals("...into the History view", 4, panel.bankHistoryState().get("readings"));
			assertEquals(TODAY.toString(), panel.bankHistoryState().get("last"));
			assertEquals(TODAY.minusDays(3).toString(), panel.bankHistoryState().get("first"));
		});
	}

	/**
	 * Plan 7.2 item 8: the item rows keep building while History shows - no new pending path - so switching back to
	 * Items shows the list of the last publish at once, with no publish in between.
	 */
	@Test
	public void theItemRowsKeepBuildingWhileHistoryShows() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> panel.pressView(SidebarView.HISTORY));
		publish(rows(7), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 30_000L));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());
			assertEquals("the list was built behind the History card", 7, panel.shownRows());
			panel.pressView(SidebarView.ITEMS);
			assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
			assertEquals(7, panel.shownRows());
		});
	}

	/** Every DRAWN status reaches the view while it shows, cut to today (a reading dated tomorrow is not counted). */
	@Test
	public void everyDrawnStatusReachesTheViewWhileHistoryShows() throws Exception
	{
		build();
		publish(rows(3), status(series(TODAY.minusDays(1)), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals("not shown while Items shows", 0, panel.bankHistoryState().get("readings"));
			panel.pressView(SidebarView.HISTORY);
			assertEquals("the switch shows it", 1, panel.bankHistoryState().get("readings"));
		});
		publish(rows(3), status(series(TODAY.minusDays(1), TODAY, TODAY.plusDays(1)), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals("a publish shows it, cut to today", 2, panel.bankHistoryState().get("readings"));
			assertEquals(TODAY.toString(), panel.bankHistoryState().get("last"));
		});
		// A mocked status answers null for both: the view reads them as empty and the defaults.
		final PriceService.Status mocked = status(null, VALUE_NOW, NOW - 60_000L);
		when(mocked.options()).thenReturn(null);
		publish(rows(3), mocked);
		onEdt(() ->
		{
			assertEquals(0, panel.bankHistoryState().get("readings"));
			assertNull(panel.bankHistoryState().get("first"));
		});
	}

	// ---------------------------------------------------------------- the chart's range (amendment 9.10)

	@Test
	public void theChartFollowsTheCardsWindowByEveryRoad() throws Exception
	{
		prefs.stored = RowFilter.DEFAULT.withWindow(MovementWindow.D90);
		build();
		onEdt(() ->
		{
			assertEquals("the constructor, after the prefs", "90d", range());
			panel.selectWindow(MovementWindow.D1);
			assertEquals("1d draws 7d", "7d", range());
			press(panel.windowChip(MovementWindow.D30), MouseEvent.BUTTON1);
			assertEquals("a chip press", "30d", range());
			panel.applyFilter(panel.filter().withWindow(MovementWindow.D180));
			assertEquals("the settings page's road: 180d draws all", "all", range());
			panel.selectWindow(MovementWindow.D7);
			assertEquals("7d", range());

			panel.setHistoryRange(BankHistoryRange.D90);
			assertEquals("the bridge's range= moves the chart alone", "90d", range());
			assertEquals(MovementWindow.D7, panel.filter().window());
			panel.applyBand(1_000_000L, 0L);
			panel.clickSort(SortMode.GP_MOVE);
			assertEquals("a band or a column never moves the chart", "90d", range());
			panel.applyFilter(panel.filter().withGpMin(5L));
			assertEquals("...nor a settings-page change that leaves the window", "90d", range());
			panel.setHistoryRange(null);
			assertEquals("null is ignored", "90d", range());
			panel.selectWindow(MovementWindow.D30);
			assertEquals("the card's next window overrules it", "30d", range());
		});
	}

	// ---------------------------------------------------------------- the pager (amendment 9.11)

	@Test
	public void showMorePagesTheListThatIsShowing() throws Exception
	{
		build();
		publish(rows(300), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(BankPriceMovementPanel.ROWS_PER_PAGE, panel.shownRows());
			panel.pressView(SidebarView.HISTORY);
			panel.showMore();
			assertEquals("History's pager, not the item list's", BankPriceMovementPanel.ROWS_PER_PAGE,
				panel.shownRows());
			panel.pressView(SidebarView.ITEMS);
			panel.showMore();
			assertEquals(300, panel.shownRows());
		});
	}

	// ---------------------------------------------------------------- 1.0.9 part 5: days before 1.0.9

	/** A prompt that answers {@code answer} and keeps every question it was asked. */
	private static final class Asked implements BankPriceMovementPanel.LegacyPrompt
	{
		boolean answer;
		final List<String> questions = new ArrayList<>();

		Asked(boolean answer)
		{
			this.answer = answer;
		}

		@Override
		public boolean ask(String question)
		{
			questions.add(question);
			return answer;
		}
	}

	/**
	 * Six readings: the four before 1.0.9 (a week back to three days back) and the two since it, the fresh start
	 * standing on yesterday's.
	 */
	private static BankHistorySeries withLegacyDays()
	{
		return series(TODAY.minusDays(7), TODAY.minusDays(6), TODAY.minusDays(4), TODAY.minusDays(3),
			TODAY.minusDays(1), TODAY).withFreshFrom(TODAY.minusDays(1));
	}

	private int readings()
	{
		return (Integer) panel.bankHistoryState().get("readings");
	}

	private List<Component> legacyHeader()
	{
		return Arrays.asList(panel.header().getComponents());
	}

	/**
	 * The settings menu's "Include days before v1.0.9" item, or null while it is not in the menu (1.1.0 part A moved
	 * it there from the History tab's header) - in either of its wordings ("... v1.1.1" for a placeholder restart, 1.1.1
	 * part B).
	 */
	private javax.swing.JCheckBoxMenuItem legacyItem()
	{
		for (Component c : panel.heroMenu().getComponents())
		{
			if (c instanceof javax.swing.JCheckBoxMenuItem
				&& (BankPriceMovementPanel.LEGACY_TEXT.equals(((javax.swing.JCheckBoxMenuItem) c).getText())
				|| BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT.equals(((javax.swing.JCheckBoxMenuItem) c).getText())))
			{
				return (javax.swing.JCheckBoxMenuItem) c;
			}
		}
		return null;
	}

	private boolean legacyItemShows()
	{
		return legacyItem() != null;
	}

	/** The words, the hover and the question are the user's; one place pins them. */
	@Test
	public void theLegacyWordsAreVerbatim()
	{
		assertEquals("Include days before v1.0.9", BankPriceMovementPanel.LEGACY_TEXT);
		assertEquals("Days before v1.0.9 did not count open G.E. orders.", BankPriceMovementPanel.LEGACY_TIP);
		assertEquals("Days before v1.0.9 did not count open G.E. orders, so their net worth totals may read low."
			+ " Include them anyway?", BankPriceMovementPanel.LEGACY_ASK);
	}

	/**
	 * With no legacy days the item is not in the settings menu at all - with an empty record and with a full one - and
	 * the History tab's header never holds a check box (1.1.0 part A moved it out for good); with such days the menu
	 * carries it in either view, and the header stays what it was without it.
	 */
	@Test
	public void theLegacyItemIsAbsentWithoutLegacyDaysAndTheHeaderNeverHoldsABox() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(Arrays.asList(panel.hero(), strip()), legacyHeader());
			assertFalse(legacyItemShows());
			assertTrue(panel.describe(), panel.describe().contains("\"legacyDays\":false"));
		});
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(Arrays.asList(panel.hero(), strip()), legacyHeader());
			assertFalse(legacyItemShows());
		});
		// A mocked status that answers no series at all reads as empty.
		publish(rows(3), status(null, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse(legacyItemShows()));

		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertTrue(panel.describe(), panel.describe().contains("\"legacyDays\":true"));
			assertTrue("the menu carries it while the record holds such days", legacyItemShows());
			assertEquals("and the History header holds no box", Arrays.asList(panel.hero(), strip()), legacyHeader());
			panel.pressView(SidebarView.ITEMS);
			assertTrue("in Items too: it is the menu's", legacyItemShows());
			assertEquals(Arrays.asList(panel.hero(), strip(), panel.controlRow(), panel.fold(), searchRow()), header());
		});
	}

	/**
	 * Present: in the menu directly under the "Net worth chart" caption and above the OK row, the label verbatim, 12 px
	 * like every item, unticked, its hover behind "Show hover text".
	 */
	@Test
	public void theLegacyItemStandsUnderTheNetWorthChartCaptionWithItsWordsItsTickAndItsHover() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals("hero and the strip - nothing between the caption and the chart", 2, legacyHeader().size());
			final Component[] c = panel.heroMenu().getComponents();
			final javax.swing.JCheckBoxMenuItem item = legacyItem();
			assertNotNull(item);
			assertSame("the OK row is still last", panel.okRow(), c[c.length - 1]);
			assertSame("the item is directly above it", item, c[c.length - 2]);
			// 1.1.0 part C: "Single chart colour" stands between the caption and the days item.
			assertEquals("under the chart's own switch", BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT,
				((javax.swing.JCheckBoxMenuItem) c[c.length - 3]).getText());
			assertEquals("under the caption", BankPriceMovementPanel.NET_WORTH_CHART_TEXT,
				find(c[c.length - 4], JLabel.class).getText());
			assertTrue("under a rule of its own", c[c.length - 5] instanceof javax.swing.JSeparator);
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, item.getText());
			assertEquals(12, item.getFont().getSize());
			assertFalse("unticked", item.isSelected());
			assertFalse("not a second line: one label", item.getText().contains("\n") || item.getText().contains("<"));

			// Its hover is behind "Show hover text" like every sentence hover here.
			assertNull("no hover with the switch off", item.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals(BankPriceMovementPanel.LEGACY_TIP, item.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
			assertNull(item.getToolTipText());
		});
	}

	/**
	 * With the box off the chart, the list, the card's comparison and "n days recorded since" see only the days
	 * recorded since 1.0.9; with it on, every day - and the card's two lines follow the same cut.
	 */
	@Test
	public void theTabAndTheCardSeeOnlyTheFreshDaysUntilTheBoxIsOn() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		final BankHistorySeries record = withLegacyDays();
		publish(rows(3), status(record, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			panel.selectWindow(MovementWindow.D7);
			assertEquals("the fresh days only: yesterday and today", 2, readings());
			assertEquals(TODAY.minusDays(1).toString(), panel.bankHistoryState().get("first"));
			assertEquals(TODAY.toString(), panel.bankHistoryState().get("last"));
			final String hidden = panel.updateLabel().getText();
			assertEquals(BankPriceMovementPanel.historyRecorded(record.fromFresh()), hidden);
			assertTrue(hidden, hidden.startsWith("2 days recorded since"));
			final String hiddenFootnote = panel.heroSubText();

			panel.pressLegacy();
			assertEquals("asked once, in the words", Arrays.asList(BankPriceMovementPanel.LEGACY_ASK), yes.questions);
			assertEquals("every reading", 6, readings());
			assertEquals(TODAY.minusDays(7).toString(), panel.bankHistoryState().get("first"));
			final String shown = panel.updateLabel().getText();
			assertEquals(BankPriceMovementPanel.historyRecorded(record), shown);
			assertTrue(shown, shown.startsWith("6 days recorded since"));
			assertNotEquals("the comparison line follows the cut", hiddenFootnote, panel.heroSubText());
			assertTrue("the item is ticked", legacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":true"));
			assertEquals("written once", Arrays.asList(true), prefs.legacySaves);
			assertTrue("the item stays while the days are there", legacyItemShows());

			// Turned off again: the cut comes back, nothing is asked, and the write follows.
			panel.pressLegacy();
			assertEquals("asked nothing", 1, yes.questions.size());
			assertEquals(2, readings());
			assertEquals(hidden, panel.updateLabel().getText());
			assertEquals(Arrays.asList(true, false), prefs.legacySaves);
			assertFalse(legacyItem().isSelected());
		});
		verify(service, never()).setOptions(any());
	}

	private static void assertNotEquals(String message, Object a, Object b)
	{
		assertFalse(message + ": " + a, a.equals(b));
	}

	/** A series whose every day is a legacy day draws the empty state until the box is on. */
	@Test
	public void aSeriesOfLegacyDaysAloneDrawsTheEmptyStateUntilTheBoxIsOn() throws Exception
	{
		build(new Asked(true));
		final BankHistorySeries all = series(TODAY.minusDays(5), TODAY.minusDays(4), TODAY.minusDays(2))
			.withFreshFrom(LocalDate.MAX);
		publish(rows(3), status(all, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(0, readings());
			assertNull(panel.bankHistoryState().get("first"));
			assertEquals("all", panel.bankHistoryState().get("freshFrom"));
			assertEquals(MovementMath.DASH, panel.updateLabel().getText());
			assertTrue("the way out is in the menu", legacyItemShows());
			assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());

			panel.pressLegacy(true);
			assertEquals(3, readings());
			assertEquals(BankPriceMovementPanel.historyRecorded(all), panel.updateLabel().getText());
		});
	}

	/** Answered no: the box stays unticked, the series stays cut, and nothing is written. */
	@Test
	public void declinedTheBoxStaysOffAndNothingIsWritten() throws Exception
	{
		final Asked no = new Asked(false);
		build(no);
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			panel.pressLegacy();
			panel.pressLegacy(true);
			assertEquals("asked each time, answered no each time", 2, no.questions.size());
			assertEquals(2, readings());
			assertFalse(legacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":false"));
		});
		assertTrue("nothing written", prefs.legacySaves.isEmpty());
	}

	/** Through the menu item's own action: a click asks, and the tick follows the answer. */
	@Test
	public void aClickOnTheMenuItemAsks() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			legacyItem().doClick(0);
			assertEquals(1, yes.questions.size());
			assertEquals(6, readings());
			assertTrue(legacyItem().isSelected());
		});
		assertEquals(Arrays.asList(true), prefs.legacySaves);
	}

	/** {@code setIncludeLegacy} is the config's road: it redraws, asks nothing and writes nothing back. */
	@Test
	public void setIncludeLegacyRedrawsAndAsksAndWritesNothing() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(2, readings());
			panel.setIncludeLegacy(true);
			assertEquals(6, readings());
			assertTrue(legacyItem().isSelected());
			panel.setIncludeLegacy(true);
			assertEquals("the same state again redraws what it drew", 6, readings());
			panel.setIncludeLegacy(false);
			assertEquals(2, readings());
			assertFalse(legacyItem().isSelected());

			panel.stop();
			panel.setIncludeLegacy(true);
			assertTrue("a stopped panel changes nothing", panel.describe().contains("\"includeLegacy\":false"));
			panel.pressLegacy(true);
			panel.pressLegacy();
		});
		assertTrue("the config's road writes nothing back", prefs.legacySaves.isEmpty());
		assertTrue("and asks nothing", yes.questions.isEmpty());
	}

	/** The state-aimed press is idempotent: the same state again asks nothing and writes nothing. */
	@Test
	public void pressingTheStateItIsInDoesNothing() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			panel.pressLegacy(false);
			assertTrue(yes.questions.isEmpty());
			panel.pressLegacy(true);
			panel.pressLegacy(true);
			panel.pressLegacy(false);
			panel.pressLegacy(false);
			assertEquals("one question, for the one turning ON", 1, yes.questions.size());
		});
		assertEquals(Arrays.asList(true, false), prefs.legacySaves);
	}

	/** A second press while the question is open (a script's) does not open a second question on top of it. */
	@Test
	public void aPressWhileTheQuestionIsOpenIsIgnored() throws Exception
	{
		final List<String> asked = new ArrayList<>();
		build(question ->
		{
			asked.add(question);
			panel.pressLegacy(true);
			panel.pressLegacy();
			return true;
		});
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			panel.pressLegacy(true);
			assertEquals("only the first press asked", 1, asked.size());
			assertEquals(6, readings());
		});
		assertEquals(Arrays.asList(true), prefs.legacySaves);
	}

	/** The panel opens on the stored answer: ticked, every reading drawn, nothing written to say so. */
	@Test
	public void aPanelBuiltOnAStoredYesOpensWithTheDaysIncluded() throws Exception
	{
		prefs.storedLegacy = Boolean.TRUE;
		build(new Asked(false));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(6, readings());
			assertTrue(legacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":true"));
		});
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** Nothing stored reads as the days hidden. */
	@Test
	public void nothingStoredReadsAsTheDaysHidden() throws Exception
	{
		prefs.storedLegacy = null;
		build(new Asked(false));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(2, readings());
		});
	}

	/**
	 * The menu is 27 components while the record holds no days before 1.0.9 (24 before 1.1.0 part J added a rule above the
	 * colour rows and the Slot 1 and save rows, 20 before part H added the colour presets'
	 * caption and its three rows, 21 before part A took Refresh and
	 * the start-tab group out, 17 after it until part B added the two colour rows, and 19 until part C added the Single
	 * chart colour row), and exactly one more - the item - while it does.
	 */
	@Test
	public void theSettingsMenuIsTwentyComponentsAndOneMoreWhileTheItemIsThere() throws Exception
	{
		build(new Asked(true));
		onEdt(() -> assertEquals(27, panel.heroMenu().getComponentCount()));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue(legacyItemShows());
			assertEquals(28, panel.heroMenu().getComponentCount());
		});
	}

	/** A status arriving while the item is up that carries no legacy days takes the item away again. */
	@Test
	public void theItemGoesWhenALaterStatusHasNoLegacyDays() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue(legacyItemShows());
		});
		publish(rows(3), status(series(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse(legacyItemShows()));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertTrue(legacyItemShows()));
	}

	// ---------------------------------------------------------------- 1.1.1 part B: days before v1.1.1

	/**
	 * Six readings like {@link #withLegacyDays()}, the fresh start standing on yesterday's because a fresh bank read found
	 * placeholders with a quantity there: the days before it counted them as items.
	 */
	private static BankHistorySeries withPlaceholderDays()
	{
		return series(TODAY.minusDays(7), TODAY.minusDays(6), TODAY.minusDays(4), TODAY.minusDays(3),
			TODAY.minusDays(1), TODAY).judged(new BankHistorySeries.PlaceholderCheck(TODAY.minusDays(1), true));
	}

	/** {@link #status} counting {@code restarts} placeholder restarts, as the service's status does. */
	private static PriceService.Status restarted(BankHistorySeries series, int restarts)
	{
		final PriceService.Status s = status(series, VALUE_NOW, NOW - 60_000L);
		when(s.placeholderRestarts()).thenReturn(restarts);
		return s;
	}

	/** The words, the hover and the question for the placeholder reason are the user's; one place pins them. */
	@Test
	public void thePlaceholderWordsAreVerbatim()
	{
		assertEquals("Include days before v1.1.1", BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT);
		assertEquals("Days before v1.1.1 counted bank placeholders as items.",
			BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TIP);
		assertEquals("Days before v1.1.1 counted bank placeholders as items, so their net worth totals may read high."
			+ " Include them anyway?", BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK);
		assertEquals("placeholders", BankHistorySeries.WHY_PLACEHOLDERS);
	}

	/**
	 * The item, its hover and its question follow the record's {@code freshWhy}: a placeholder restart reads "... v1.1.1"
	 * in all three, a record whose days are hidden for the 1.0.9 reason reads exactly as before, and a later status
	 * changes the words both ways. The cut is the same cut whatever the reason.
	 */
	@Test
	public void theItemItsHoverAndItsQuestionFollowTheRecordsReason() throws Exception
	{
		final Asked no = new Asked(false);
		build(no);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final javax.swing.JCheckBoxMenuItem item = legacyItem();
			assertNotNull("the menu carries it", item);
			assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT, item.getText());
			assertNull("no hover with the switch off", item.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TIP, item.getToolTipText());
			assertEquals("the same cut: yesterday and today", 2, readings());
			assertEquals("placeholders", panel.bankHistoryState().get("freshWhy"));
			assertEquals(TODAY.minusDays(1).toString(), panel.bankHistoryState().get("placeholdersChecked"));
			assertEquals(TODAY.minusDays(1).toString(), panel.bankHistoryState().get("freshFrom"));
			panel.pressLegacy();
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), no.questions);
			assertFalse(legacyItem().isSelected());
		});
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			final javax.swing.JCheckBoxMenuItem item = legacyItem();
			assertEquals("the 1.0.9 words for a G.E.-only record", BankPriceMovementPanel.LEGACY_TEXT, item.getText());
			assertEquals(BankPriceMovementPanel.LEGACY_TIP, item.getToolTipText());
			assertNull(panel.bankHistoryState().get("freshWhy"));
			assertNull(panel.bankHistoryState().get("placeholdersChecked"));
			panel.pressLegacy();
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK, BankPriceMovementPanel.LEGACY_ASK),
				no.questions);
			panel.applyOptions(ViewOptions.DEFAULT);
			assertNull(item.getToolTipText());
		});
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT, legacyItem().getText()));
		assertTrue("nothing written: every question was answered no", prefs.legacySaves.isEmpty());
	}

	/**
	 * A status counting a placeholder restart the panel has not answered turns a ticked box OFF once and writes that; the
	 * same count again leaves the answer alone - ticked again, it stays ticked - and only a further restart turns it off
	 * again. A restart with the box already off writes nothing. Answered while the sidebar is hidden too.
	 */
	@Test
	public void aPlaceholderRestartTurnsATickedBoxOffOnce() throws Exception
	{
		prefs.storedLegacy = Boolean.TRUE;
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), restarted(withLegacyDays(), 0));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue("ticked for the 1.0.9 reason", legacyItem().isSelected());
			assertEquals(6, readings());
		});
		assertTrue(prefs.legacySaves.isEmpty());

		publish(rows(3), restarted(withPlaceholderDays(), 1));
		onEdt(() ->
		{
			assertFalse("turned off by the restart", legacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":false"));
			assertEquals("the inflated days are hidden", 2, readings());
		});
		assertEquals("and the stored setting follows", Arrays.asList(false), prefs.legacySaves);

		publish(rows(3), restarted(withPlaceholderDays(), 1));
		onEdt(() ->
		{
			assertFalse(legacyItem().isSelected());
			panel.pressLegacy();
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), yes.questions);
			assertTrue("the reader ticks it again", legacyItem().isSelected());
		});
		publish(rows(3), restarted(withPlaceholderDays(), 1));
		onEdt(() -> assertTrue("the same restart again leaves the answer alone", legacyItem().isSelected()));
		assertEquals(Arrays.asList(false, true), prefs.legacySaves);

		onEdt(panel::onDeactivate);
		publish(rows(3), restarted(withPlaceholderDays(), 2));
		onEdt(() -> assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":false")));
		assertEquals("a further restart, answered while hidden", Arrays.asList(false, true, false), prefs.legacySaves);

		publish(rows(3), restarted(withPlaceholderDays(), 3));
		assertEquals("with the box off there is nothing to turn off", Arrays.asList(false, true, false), prefs.legacySaves);
	}

	// ---------------------------------------------------------------- 1.1.1 part G2: two gears and a notice on the History tab

	/** The History options menu's twin of the settings menu's include item, or null while it is not in the menu. */
	private javax.swing.JCheckBoxMenuItem historyLegacyItem()
	{
		for (Component c : panel.historyMenu().getComponents())
		{
			if (c instanceof javax.swing.JCheckBoxMenuItem && !(c instanceof SwatchRow)
				&& (BankPriceMovementPanel.LEGACY_TEXT.equals(((javax.swing.JCheckBoxMenuItem) c).getText())
				|| BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT.equals(((javax.swing.JCheckBoxMenuItem) c).getText())))
			{
				return (javax.swing.JCheckBoxMenuItem) c;
			}
		}
		return null;
	}

	/** The History options menu's "Single chart colour" row. */
	private SwatchRow historyChartRow()
	{
		for (Component c : panel.historyMenu().getComponents())
		{
			if (c instanceof SwatchRow)
			{
				return (SwatchRow) c;
			}
		}
		fail("no Single chart colour row in the History options menu");
		return null;
	}

	/** The settings menu's "Single chart colour" row. */
	private SwatchRow settingsChartRow()
	{
		for (Component c : panel.heroMenu().getComponents())
		{
			if (c instanceof SwatchRow && BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT.equals(((SwatchRow) c).getText()))
			{
				return (SwatchRow) c;
			}
		}
		fail("no Single chart colour row in the settings menu");
		return null;
	}

	/** The "i" icon in the caption's row (1.1.1 part I2), or null while the row holds none: found by its hover, as a user would. */
	@Nullable
	private JLabel infoIcon()
	{
		for (Component c : panel.viewCaptionRow().getComponents())
		{
			if (c instanceof JLabel && BankPriceMovementPanel.LEGACY_INFO_TIP.equals(((JLabel) c).getToolTipText()))
			{
				return (JLabel) c;
			}
		}
		return null;
	}

	/** Whether the caption's row holds the "i" icon just now. */
	private boolean infoShowing()
	{
		return infoIcon() != null;
	}

	/**
	 * Where {@code icon}'s 12 x 12 picture is painted, in the panel's coordinates, on a panel already laid out: at the label's left
	 * inset and centred in what its top inset leaves of the row ({@link LookRenderer#historyOptionsIcon}'s rule, for any label).
	 */
	private Rectangle pictureBox(JLabel icon)
	{
		final java.awt.Insets in = icon.getInsets();
		final int h = icon.getIcon().getIconHeight();
		return SwingUtilities.convertRectangle(icon.getParent(), new Rectangle(icon.getX() + in.left,
			icon.getY() + in.top + (icon.getHeight() - in.top - in.bottom - h) / 2, icon.getIcon().getIconWidth(), h), panel);
	}

	/** The pointer entering or leaving {@code c}, delivered to its listeners. */
	private static void mouse(Component c, int id)
	{
		final MouseEvent e = new MouseEvent(c, id, 0L, 0, 1, 1, 0, false);
		for (MouseListener l : c.getMouseListeners())
		{
			if (id == MouseEvent.MOUSE_ENTERED)
			{
				l.mouseEntered(e);
			}
			else
			{
				l.mouseExited(e);
			}
		}
	}

	/** The picture an icon label holds (the icons here are all images drawn pixel by pixel). */
	private static java.awt.image.BufferedImage picture(JLabel icon)
	{
		return (java.awt.image.BufferedImage) ((javax.swing.ImageIcon) icon.getIcon()).getImage();
	}

	/** The words of the hover and of the menu are the user's, and none of them says "gear". */
	@Test
	public void g2_theWordsAreVerbatimAndNeverSayGear()
	{
		assertEquals("Days before v1.1.1 counted bank placeholders as items, so they may read high. Restore them in settings.",
			BankPriceMovementPanel.LEGACY_INFO_TIP_TEXT);
		assertEquals("History options", BankPriceMovementPanel.HISTORY_OPTIONS_TIP);
		for (String said : new String[]{BankPriceMovementPanel.LEGACY_INFO_TIP, BankPriceMovementPanel.LEGACY_INFO_TIP_TEXT,
			BankPriceMovementPanel.HISTORY_OPTIONS_TIP, BankPriceMovementPanel.LEGACY_TEXT,
			BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT})
		{
			assertFalse(said, said.toLowerCase().contains("gear"));
		}
	}

	/**
	 * G1: the gears are on the History caption's row - at its east end, the caption's text in the middle - and in no row at all
	 * in Items, which keeps the strip exactly as it was (the toggle and the caption's row, the row exactly as tall as the caption
	 * label was). Their one-phrase hover is on with "Show hover text" off and on.
	 */
	@Test
	public void g2_theGearsStandOnTheHistoryCaptionAndNotInItems() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertNull("Items: the icon is in no row", panel.historyOptionsLabel().getParent());
			assertEquals("Items: the row holds the caption alone", Arrays.asList(caption()),
				Arrays.asList(panel.viewCaptionRow().getComponents()));
			assertEquals("the strip is the toggle and the caption's row", Arrays.asList(toggle(), panel.viewCaptionRow()),
				Arrays.asList(strip().getComponents()));
			assertEquals("the row is as tall as the caption was", caption().getPreferredSize().height,
				panel.viewCaptionRow().getPreferredSize().height);

			panel.pressView(SidebarView.HISTORY);
			final JLabel gears = panel.historyOptionsLabel();
			assertSame("History: the icon is in the caption's row", panel.viewCaptionRow(), gears.getParent());
			assertSame("at its east end: the row's last child", gears,
				panel.viewCaptionRow().getComponent(panel.viewCaptionRow().getComponentCount() - 1));
			assertEquals("the caption is unchanged", BankPriceMovementPanel.HISTORY_CAPTION, caption().getText());
			assertEquals(Arrays.asList(toggle(), panel.viewCaptionRow()), Arrays.asList(strip().getComponents()));
			assertEquals("the row is no taller with the icon in it", caption().getPreferredSize().height,
				panel.viewCaptionRow().getPreferredSize().height);
			assertEquals("History options", gears.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals("the same word with the switch on", BankPriceMovementPanel.HISTORY_OPTIONS_TIP,
				gears.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
			assertEquals("and off: always on", BankPriceMovementPanel.HISTORY_OPTIONS_TIP, gears.getToolTipText());
			assertEquals("the List options gears, drawn again", GearsIcon.SIZE, gears.getIcon().getIconWidth());

			panel.pressView(SidebarView.ITEMS);
			assertNull("back in Items the icon is gone again", gears.getParent());
			assertEquals(Arrays.asList(caption()), Arrays.asList(panel.viewCaptionRow().getComponents()));
		});
	}

	/**
	 * G1: a LEFT press on the gears opens the History options menu under them, a right press does nothing, a second press takes
	 * the menu down and is remembered as the close (the reopen guard, per its own stamp), and leaving History takes an open
	 * menu down with the icon.
	 */
	@Test
	public void g2_pressingTheGearsOpensTheMenuAndASecondPressTakesItDown() throws Exception
	{
		build();
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		final javax.swing.JFrame[] host = new javax.swing.JFrame[1];
		try
		{
			onEdt(() ->
			{
				panel.pressView(SidebarView.HISTORY);
				host[0] = new javax.swing.JFrame();
				host[0].setFocusableWindowState(false);
				host[0].add(panel);
				host[0].setSize(400, 900);
				host[0].setLocation(-2000, -2000);
				host[0].setVisible(true);
			});
			onEdt(() ->
			{
				final JLabel gears = panel.historyOptionsLabel();
				press(gears, MouseEvent.BUTTON3);
				assertFalse("a right press is a menu gesture, never a press", panel.historyMenu().isVisible());

				press(gears, MouseEvent.BUTTON1);
				assertTrue("the first press opens it", panel.historyMenu().isVisible());
				assertSame("under the icon", gears, panel.historyMenu().getInvoker());
				assertFalse("not the List options menu", panel.listMenu().isVisible());

				press(gears, MouseEvent.BUTTON1);
				assertFalse("the second press closes it", panel.historyMenu().isVisible());
				assertFalse("and is the close the reopen guard remembers", panel.historyPressOpens());

				// Leaving History takes the icon away, and an open menu with it.
				panel.setClock(() -> NOW + 10_000L);
				press(gears, MouseEvent.BUTTON1);
				assertTrue(panel.historyMenu().isVisible());
				panel.pressView(SidebarView.ITEMS);
				assertFalse("the menu goes with the icon", panel.historyMenu().isVisible());
			});
		}
		finally
		{
			onEdt(() ->
			{
				if (host[0] != null)
				{
					host[0].dispose();
				}
			});
		}
	}

	/** The reopen guard, driven through the panel's own clock: 300 ms, about the LAST close, and its own stamp. */
	@Test
	public void g2_aPressWithinTheGuardOfACloseOpensNothingAndOneAfterItOpens() throws Exception
	{
		build();
		final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(NOW);
		onEdt(() ->
		{
			panel.setClock(now::get);
			assertTrue("nothing has closed yet", panel.historyPressOpens());
			final javax.swing.event.PopupMenuEvent e = new javax.swing.event.PopupMenuEvent(panel.historyMenu());
			for (javax.swing.event.PopupMenuListener l : panel.historyMenu().getPopupMenuListeners())
			{
				l.popupMenuWillBecomeInvisible(e);
			}
			now.set(NOW + BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS - 1);
			assertFalse(panel.historyPressOpens());
			now.set(NOW + BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS);
			assertTrue("over at exactly 300 ms", panel.historyPressOpens());
			assertTrue("the settings icon's guard is not this one", panel.gearPressOpens());
			assertTrue("nor the List options icon's", panel.listPressOpens());
		});
	}

	/**
	 * G1: the menu holds the include item and a rule ONLY while hidden days exist, in either wording (v1.1.1 for the placeholder
	 * restart, v1.0.9 otherwise), then "Single chart colour"; with none, "Single chart colour" alone. The item's hover is behind
	 * "Show hover text" like the settings menu's, in the words for the record's reason.
	 */
	@Test
	public void g2_theMenuHoldsTheIncludeItemOnlyWhileHiddenDaysExist() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final Component[] none = panel.historyMenu().getComponents();
			assertEquals("no hidden days: one row", 1, none.length);
			assertTrue(none[0] instanceof SwatchRow);
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, ((SwatchRow) none[0]).getText());
			assertNull(historyLegacyItem());
		});
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			final Component[] c = panel.historyMenu().getComponents();
			assertEquals("the item, a rule, the chart row", 3, c.length);
			assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT, ((javax.swing.JCheckBoxMenuItem) c[0]).getText());
			assertTrue("a rule under it", c[1] instanceof javax.swing.JSeparator);
			assertTrue(c[2] instanceof SwatchRow);
			final javax.swing.JCheckBoxMenuItem item = historyLegacyItem();
			assertSame(c[0], item);
			assertEquals("the menu's face", Widgets.sans(12), item.getFont());
			assertFalse("unticked", item.isSelected());
			assertNull("no hover with the switch off", item.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TIP, item.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
		});
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals("the 1.0.9 words for a G.E.-only record", BankPriceMovementPanel.LEGACY_TEXT,
				historyLegacyItem().getText());
			assertEquals(3, panel.historyMenu().getComponentCount());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals(BankPriceMovementPanel.LEGACY_TIP, historyLegacyItem().getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
		});
		publish(rows(3), status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertNull("the days are gone: so is the item", historyLegacyItem());
			assertEquals("and its rule", 1, panel.historyMenu().getComponentCount());
		});
	}

	/**
	 * G3: ticking the include item from the History options menu is the settings menu's road - the dialog's question in the
	 * record's words, the config key written once, the cut changed, BOTH menus' items ticked - and unticking asks nothing; an
	 * answer of no changes and writes nothing; a placeholder restart turns the box off through it still.
	 */
	@Test
	public void g2_tickingTheIncludeItemFromTheMenuRunsTheSameRoadAsTheSettingsMenu() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals("the hidden days stay hidden", 2, readings());
			historyLegacyItem().doClick(0);
			assertEquals("the settings item's question, in the record's words",
				Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), yes.questions);
			assertEquals("every reading", 6, readings());
			assertTrue(historyLegacyItem().isSelected());
			assertTrue("the settings menu's item follows", legacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":true"));

			historyLegacyItem().doClick(0);
			assertEquals("unticking asks nothing", 1, yes.questions.size());
			assertEquals(2, readings());
			assertFalse(historyLegacyItem().isSelected());
			assertFalse(legacyItem().isSelected());
		});
		assertEquals("written once each way", Arrays.asList(true, false), prefs.legacySaves);
		verify(service, never()).setOptions(any());

		// ...and the settings menu's tick moves the History menu's the same way.
		onEdt(() ->
		{
			legacyItem().doClick(0);
			assertTrue(historyLegacyItem().isSelected());
			legacyItem().doClick(0);
			assertFalse(historyLegacyItem().isSelected());
		});
		assertEquals(Arrays.asList(true, false, true, false), prefs.legacySaves);
	}

	/** Cancel leaves the box off and unticked in both menus and writes nothing. */
	@Test
	public void g2_anAnswerOfNoLeavesTheItemUntickedAndWritesNothing() throws Exception
	{
		final Asked no = new Asked(false);
		build(no);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			historyLegacyItem().doClick(0);
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), no.questions);
			assertFalse("put back to what the panel believes", historyLegacyItem().isSelected());
			assertFalse(legacyItem().isSelected());
			assertEquals(2, readings());
		});
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** A placeholder restart with the box ticked turns it off through the History menu's item as well. */
	@Test
	public void g2_aPlaceholderRestartStillTurnsTheBoxOffAndTheMenusFollow() throws Exception
	{
		prefs.storedLegacy = Boolean.TRUE;
		build(new Asked(true));
		publish(rows(3), restarted(withLegacyDays(), 0));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue("ticked for the 1.0.9 reason", historyLegacyItem().isSelected());
			assertFalse("a ticked box shows no icon", infoShowing());
		});
		publish(rows(3), restarted(withPlaceholderDays(), 1));
		onEdt(() ->
		{
			assertFalse("turned off by the restart", historyLegacyItem().isSelected());
			assertFalse(legacyItem().isSelected());
			assertTrue("and the icon says so", infoShowing());
		});
		assertEquals(Arrays.asList(false), prefs.legacySaves);
	}

	/**
	 * G1: "Single chart colour" in the History options menu is the settings menu's row again: its tick stores the switch ONCE
	 * and ticks both rows, a press on its swatch opens the picker titled "Chart colour" on the chart colour in force and takes
	 * the menu down.
	 */
	@Test
	public void g2_theChartRowIsTheSettingsMenusRowAgain() throws Exception
	{
		build();
		final List<String> titles = new ArrayList<>();
		final List<java.awt.Color> starts = new ArrayList<>();
		onEdt(() -> panel.setColourPicker((anchor, start, title, live, done) ->
		{
			starts.add(start);
			titles.add(title);
		}));
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final SwatchRow row = historyChartRow();
			assertTrue("ticked: on by default", row.isSelected());
			assertTrue(settingsChartRow().isSelected());
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, row.getText());
			assertEquals(Widgets.sans(12), row.getFont());

			row.doClick(0);
			assertFalse(row.isSelected());
			assertFalse("the settings menu's row follows", settingsChartRow().isSelected());
			settingsChartRow().doClick(0);
			assertTrue("and the other way", row.isSelected());
			row.doClick(0);
			assertFalse(row.isSelected());
			row.doClick(0);
			assertTrue(row.isSelected());

			row.setSize(row.getPreferredSize());
			row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED, 0L, 0,
				row.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH / 2, row.getHeight() / 2, 1,
				false, MouseEvent.BUTTON1));
			assertEquals("the swatch opens the picker", Arrays.asList(BankPriceMovementPanel.CHART_COLOUR_TEXT), titles);
			assertEquals("on the chart colour in force", Widgets.CHART_COLOUR_DEFAULT, starts.get(0));
			assertTrue("and ticks nothing", row.isSelected());
		});
		assertEquals("the tick stored each way, the swatch nothing", Arrays.asList(false, true, false, true),
			prefs.singleSaves);
	}

	// ---------------------------------------------------------------- 1.1.1 part I2: the "i" icon

	/**
	 * I1: the "i" icon stands in the caption's row directly LEFT of the gears with the gap the gears keep (6 px), 12 x 12 like them,
	 * centred on the caption's text line as they are and right of the caption's text; and it takes nothing from what is under it
	 * (I2: the notice's block shift is gone) - the row, the strip, the chart block and the gears stand exactly where they stand
	 * with the box ticked and the icon away.
	 */
	@Test
	public void i2_theIconStandsLeftOfTheGearsWithTheirGapCentredOnTheCaptionLineAndMovesNothing() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue(infoShowing());
			final JLabel info = infoIcon();
			final JLabel gears = panel.historyOptionsLabel();
			assertEquals("the row's children stand as they are drawn: the caption, the icon, the gears",
				Arrays.asList(caption(), info, gears), Arrays.asList(panel.viewCaptionRow().getComponents()));
			assertEquals("the row's own background is the cut-out's colour", InfoIcon.CUT, panel.viewCaptionRow().getBackground());
			panel.setSize(LookRenderer.WIDTH, 1500);
			LookRenderer.layoutTree(panel);

			final Rectangle infoBox = pictureBox(info);
			final Rectangle gearsBox = pictureBox(gears);
			final Rectangle row = SwingUtilities.convertRectangle(panel.viewCaptionRow().getParent(),
				panel.viewCaptionRow().getBounds(), panel);
			final Rectangle captionAt = SwingUtilities.convertRectangle(caption().getParent(), caption().getBounds(), panel);
			assertEquals("12 x 12, the gears' size", GearsIcon.SIZE, infoBox.width);
			assertEquals(GearsIcon.SIZE, infoBox.height);
			assertEquals(InfoIcon.SIZE, info.getIcon().getIconWidth());
			assertEquals("directly left of the gears, with the gap they keep", gearsBox.x,
				infoBox.x + infoBox.width + BankPriceMovementPanel.LIST_OPTIONS_GAP);
			assertEquals("on the gears' own line", gearsBox.y, infoBox.y);
			assertEquals("the gears still end the row", row.x + row.width, gearsBox.x + gearsBox.width);
			// The caption's text line is the row less the 3 px of air above it; the icon's centre is on it, as the gears' is.
			assertEquals("centred on the text line", row.y + 3 + (row.height - 3) / 2.0, infoBox.y + infoBox.height / 2.0, 1.0);
			final int textEnd = captionAt.x + caption().getInsets().left
				+ caption().getFontMetrics(caption().getFont()).stringWidth(caption().getText());
			assertTrue("right of the caption's text", infoBox.x > textEnd);
			assertTrue("inside the caption row", row.contains(infoBox));
			assertEquals("the row is no taller with the icon in it", caption().getPreferredSize().height,
				panel.viewCaptionRow().getPreferredSize().height);

			final Component view = find(panel, BankHistoryView.class);
			final Rectangle stripOn = SwingUtilities.convertRectangle(strip().getParent(), strip().getBounds(), panel);
			final Rectangle viewOn = SwingUtilities.convertRectangle(view.getParent(), view.getBounds(), panel);
			final Rectangle cardOn = SwingUtilities.convertRectangle(panel.hero().getParent(), panel.hero().getBounds(), panel);

			panel.pressLegacy(true);
			assertFalse(infoShowing());
			panel.setSize(LookRenderer.WIDTH, 1500);
			LookRenderer.layoutTree(panel);
			assertEquals("the row is where it was", row, SwingUtilities.convertRectangle(panel.viewCaptionRow().getParent(),
				panel.viewCaptionRow().getBounds(), panel));
			assertEquals("the strip is as tall", stripOn,
				SwingUtilities.convertRectangle(strip().getParent(), strip().getBounds(), panel));
			// (Its height is not compared: the included days add day rows under the chart.)
			final Rectangle viewOff = SwingUtilities.convertRectangle(view.getParent(), view.getBounds(), panel);
			assertEquals("the chart block does not move across", viewOn.x, viewOff.x);
			assertEquals("nor down", viewOn.y, viewOff.y);
			assertEquals("nor the card", cardOn,
				SwingUtilities.convertRectangle(panel.hero().getParent(), panel.hero().getBounds(), panel));
			assertEquals("nor the gears", gearsBox, pictureBox(gears));
		});
	}

	/**
	 * I1: the icon shows ONLY while all of these hold - the tab is History, the reason is placeholders, hidden days exist and the
	 * box is off. Ticking the box (the include question, the config road, either menu's item) takes it away and unticking brings
	 * it back; the 1.0.9 reason, no hidden days, an empty record and no record never show it, and Items never does.
	 */
	@Test
	public void i2_theIconShowsOnlyForPlaceholderDaysWithTheBoxOffInHistory() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			assertFalse("Items never shows it", infoShowing());
			panel.pressView(SidebarView.HISTORY);
			assertTrue("History, placeholders, hidden days, box off", infoShowing());

			panel.pressLegacy(true);
			assertFalse("ticked: gone", infoShowing());
			panel.pressLegacy(false);
			assertTrue("unticked: back", infoShowing());
			panel.setIncludeLegacy(true);
			assertFalse("the config's road takes it away too", infoShowing());
			panel.setIncludeLegacy(false);
			assertTrue(infoShowing());
			historyLegacyItem().doClick(0);
			assertFalse("from the History menu", infoShowing());
			legacyItem().doClick(0);
			assertTrue("from the settings menu", infoShowing());

			panel.pressView(SidebarView.ITEMS);
			assertFalse(infoShowing());
			panel.pressView(SidebarView.HISTORY);
			assertTrue(infoShowing());
		});
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse("never for 1.0.9's reason", infoShowing()));
		publish(rows(3), status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse("no hidden days", infoShowing()));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertTrue(infoShowing()));
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse("an empty record", infoShowing()));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		publish(rows(3), status(null, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse("no record at all", infoShowing()));
	}

	/**
	 * I1: the hover is the user's sentence (set as three short rows, see {@link #i3_theHoverIsThreeShortRowsOfTheSameWords}),
	 * ALWAYS on - set on the label with "Show hover text" off, on and off again - and the icon wears the hand cursor like the gears.
	 */
	@Test
	public void i2_theHoverIsTheUsersSentenceAndIsOnWithShowHoverTextOff() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertFalse("the quieter sidebar is what ships", panel.options().showHoverText());
			assertEquals("the caption, the icon, the gears", 3, panel.viewCaptionRow().getComponentCount());
			final JLabel info = (JLabel) panel.viewCaptionRow().getComponent(1);
			final String sentence = "Days before v1.1.1 counted bank placeholders as items, so they may read high."
				+ " Restore them in settings.";
			final String tip = "<html>Days before v1.1.1 counted bank placeholders<br>as items, so they may read high.<br>"
				+ "Restore them in settings.</html>";
			assertEquals("the words, on one line of plain text", sentence, BankPriceMovementPanel.LEGACY_INFO_TIP_TEXT);
			assertFalse(sentence.contains("<") || sentence.contains("\n"));
			assertEquals("with the switch off", tip, info.getToolTipText());
			assertEquals(BankPriceMovementPanel.LEGACY_INFO_TIP, info.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals("the same with it on", tip, info.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
			assertEquals("and off again: always on", tip, info.getToolTipText());
			assertEquals("the hand, like the gears", java.awt.Cursor.HAND_CURSOR, info.getCursor().getType());
			assertEquals(java.awt.Cursor.HAND_CURSOR, panel.historyOptionsLabel().getCursor().getType());
		});
	}

	/**
	 * I3: the hover is THREE short rows - Swing HTML with exactly two line breaks, after "placeholders" and after "high." - and
	 * says the very words of the one-line sentence; it is taller and narrower than the same words on one line, and the dialog's
	 * own sentence is untouched.
	 */
	@Test
	public void i3_theHoverIsThreeShortRowsOfTheSameWords() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final JLabel info = infoIcon();
			assertEquals("<html>Days before v1.1.1 counted bank placeholders<br>as items, so they may read high.<br>"
				+ "Restore them in settings.</html>", info.getToolTipText());
			final String inner = info.getToolTipText().substring("<html>".length(),
				info.getToolTipText().length() - "</html>".length());
			final String[] lines = inner.split("<br>");
			assertEquals("three rows", 3, lines.length);
			assertEquals("Days before v1.1.1 counted bank placeholders", lines[0]);
			assertEquals("as items, so they may read high.", lines[1]);
			assertEquals("Restore them in settings.", lines[2]);
			assertEquals("the same words as the plain sentence", BankPriceMovementPanel.LEGACY_INFO_TIP_TEXT,
				String.join(" ", lines));
			final JToolTip threeRows = info.createToolTip();
			threeRows.setTipText(info.getToolTipText());
			final JToolTip oneRow = info.createToolTip();
			oneRow.setTipText(BankPriceMovementPanel.LEGACY_INFO_TIP_TEXT);
			assertTrue("three rows are taller and narrower than one",
				threeRows.getPreferredSize().height > oneRow.getPreferredSize().height
					&& threeRows.getPreferredSize().width < oneRow.getPreferredSize().width);
			assertEquals("the dialog's own sentence is unchanged",
				"Days before v1.1.1 counted bank placeholders as items, so their net worth totals may read high."
					+ " Include them anyway?", BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK);
		});
	}

	/**
	 * I3: the hover opens LEFTWARD under the icon - the tip's right edge on the icon's right edge, its top 2 px under the icon -
	 * where there is room for it; at the real sidebar's width it never starts left of the panel's left edge; and a tip wider than
	 * the panel is aligned to that edge.
	 */
	@Test
	public void i3_theHoverOpensLeftwardUnderTheIconAndNeverLeftOfThePanel() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final JLabel info = infoIcon();

			// Room to spare: the label stands at the row's right end of a wide panel.
			panel.setSize(900, 1500);
			LookRenderer.layoutTree(panel);
			final JToolTip tip = info.createToolTip();
			tip.setTipText(info.getToolTipText());
			final int tipWidth = tip.getPreferredSize().width;
			assertTrue("the icon has a size", info.getWidth() > 0 && info.getHeight() > 0);
			assertTrue("the premise: it fits left of the icon",
				SwingUtilities.convertPoint(info, info.getWidth(), 0, panel).x > tipWidth);
			final Point at = info.getToolTipLocation(null);
			assertEquals("the tip's right edge on the icon's right edge", info.getWidth(), at.x + tipWidth, 1.0);
			assertEquals("2 px under the icon", info.getHeight() + 2, at.y);

			// The real sidebar: the tip may be too wide for what is left of the icon, and then it starts at the panel's edge.
			panel.setSize(LookRenderer.WIDTH, 1500);
			LookRenderer.layoutTree(panel);
			final Point narrow = info.getToolTipLocation(null);
			assertTrue("never left of the panel's edge: " + narrow,
				SwingUtilities.convertPoint(info, narrow, panel).x >= 0);
			assertEquals(info.getHeight() + 2, narrow.y);
			assertTrue("never right of the right-aligned place", narrow.x >= info.getWidth() - tipWidth);

			// A tip wider than the panel is aligned to the panel's left edge.
			info.setToolTipText("<html>Days before v1.1.1 counted bank placeholders as items, so they may read high. "
				+ "Restore them in settings, Restore them in settings.</html>");
			final JToolTip wide = info.createToolTip();
			wide.setTipText(info.getToolTipText());
			assertTrue("the premise: wider than the panel", wide.getPreferredSize().width > LookRenderer.WIDTH);
			final Point wideAt = info.getToolTipLocation(null);
			assertEquals("starts at the panel's left edge", 0, SwingUtilities.convertPoint(info, wideAt, panel).x);
			assertEquals(info.getHeight() + 2, wideAt.y);
		});
	}

	/**
	 * I3: the left press asks a modal question, so the hover is taken away while it is up - the label holds no tip text inside
	 * the prompt, which is what keeps Swing's manager from showing it over the question - and is back, the same HTML, once the
	 * press returns, whichever way the question was answered.
	 */
	@Test
	public void i3_theHoverIsGoneWhileTheQuestionIsUpAndBackAfterIncluding() throws Exception
	{
		hoverAroundTheQuestion(true);
	}

	@Test
	public void i3_theHoverIsGoneWhileTheQuestionIsUpAndBackAfterCancelling() throws Exception
	{
		hoverAroundTheQuestion(false);
	}

	private void hoverAroundTheQuestion(final boolean answer) throws Exception
	{
		final List<String> inside = new ArrayList<>();
		final AtomicReference<JLabel> icon = new AtomicReference<>();
		build(question ->
		{
			inside.add(String.valueOf(icon.get().getToolTipText()));
			return answer;
		});
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			icon.set(infoIcon());
			assertEquals("before the press", BankPriceMovementPanel.LEGACY_INFO_TIP, icon.get().getToolTipText());
			press(icon.get(), MouseEvent.BUTTON1);
			assertEquals("the question was asked once, with no hover on the label", Arrays.asList("null"), inside);
			assertEquals("after the press the hover is back", BankPriceMovementPanel.LEGACY_INFO_TIP,
				icon.get().getToolTipText());
		});
	}

	/**
	 * I1: a LEFT press asks the include question through the panel's own prompt seam - the box's road, in the record's words - and
	 * "Include" ticks the box in both menus, writes {@code includeLegacyHistory} true once, brings the days back and takes the icon
	 * away; a right press does nothing; unticking from a menu brings the icon back.
	 */
	@Test
	public void i2_aLeftPressAsksTheIncludeQuestionAndIncludeTicksTheBoxAndTheIconGoes() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final JLabel info = infoIcon();
			assertEquals("the hidden days stay hidden", 2, readings());

			press(info, MouseEvent.BUTTON3);
			assertTrue("a right press asks nothing", yes.questions.isEmpty());
			assertTrue("and the icon stays", infoShowing());
			assertEquals(2, readings());

			press(info, MouseEvent.BUTTON1);
			assertEquals("the box's question, in the record's words",
				Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), yes.questions);
			assertFalse("the icon goes", infoShowing());
			assertEquals("every reading is back", 6, readings());
			assertTrue("the settings menu's item is ticked", legacyItem().isSelected());
			assertTrue("and the History menu's", historyLegacyItem().isSelected());
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":true"));
		});
		assertEquals("written once", Arrays.asList(true), prefs.legacySaves);
		verify(service, never()).setOptions(any());
		onEdt(() ->
		{
			legacyItem().doClick(0);
			assertTrue("unticking brings the icon back", infoShowing());
			assertEquals(2, readings());
		});
		assertEquals(Arrays.asList(true, false), prefs.legacySaves);
		assertEquals("unticking asked nothing", 1, yes.questions.size());
	}

	/**
	 * I1: "Cancel" changes nothing - the box stays off and unticked in both menus, nothing is written, the icon stays - and the
	 * question may be asked again.
	 */
	@Test
	public void i2_cancelChangesNothingAndTheIconStays() throws Exception
	{
		final Asked no = new Asked(false);
		build(no);
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final JLabel info = infoIcon();
			press(info, MouseEvent.BUTTON1);
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), no.questions);
			assertTrue("the icon stays", infoShowing());
			assertSame("the same icon", info, infoIcon());
			assertEquals("the days stay hidden", 2, readings());
			assertFalse(legacyItem().isSelected());
			assertFalse(historyLegacyItem().isSelected());
			press(info, MouseEvent.BUTTON1);
			assertEquals("it asks again", 2, no.questions.size());
			no.answer = true;
			press(info, MouseEvent.BUTTON1);
			assertFalse(infoShowing());
		});
		assertEquals("only the answer that included was written", Arrays.asList(true), prefs.legacySaves);
	}

	/**
	 * I1: the disc is the gears' grey at rest and white under the mouse (its "i" always the row's colour), and an icon that leaves
	 * under the pointer - the press that ticked the box - is not white when it comes back.
	 */
	@Test
	public void i2_theIconIsGreyAtRestWhiteUnderTheMouseAndNotWhiteWhenItReturns() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withPlaceholderDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final JLabel info = infoIcon();
			assertEquals("a disc pixel at rest: the gears' grey", Widgets.PLACEHOLDER_COLOR.getRGB(), picture(info).getRGB(2, 5));
			assertEquals("the cut is the row's colour", InfoIcon.CUT.getRGB(), picture(info).getRGB(6, 5));
			mouse(info, MouseEvent.MOUSE_ENTERED);
			assertEquals("white under the mouse", Color.WHITE.getRGB(), picture(info).getRGB(2, 5));
			assertEquals("the cut is still the row's", InfoIcon.CUT.getRGB(), picture(info).getRGB(6, 5));
			mouse(info, MouseEvent.MOUSE_EXITED);
			assertEquals("grey again after", Widgets.PLACEHOLDER_COLOR.getRGB(), picture(info).getRGB(2, 5));

			mouse(info, MouseEvent.MOUSE_ENTERED);
			panel.pressLegacy(true);
			assertFalse(infoShowing());
			panel.pressLegacy(false);
			assertSame(info, infoIcon());
			assertEquals("back at rest: grey, not white", Widgets.PLACEHOLDER_COLOR.getRGB(), picture(info).getRGB(2, 5));
		});
	}

	// ---------------------------------------------------------------- fixtures and helpers

	static long noon(LocalDate day)
	{
		return day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	static BankHistoryPoint point(LocalDate day, long value)
	{
		final long[] cells = new long[BankHistoryPoint.CELLS];
		cells[BankHistoryPoint.BANK_TRADEABLE] = value;
		return new BankHistoryPoint(day, noon(day), noon(day), cells, null);
	}

	/** Readings on {@code days}, each worth a little less than the one after it. */
	static BankHistorySeries series(LocalDate... days)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (LocalDate day : days)
		{
			points.add(point(day, VALUE_NOW - 1_000_000L * TODAY.toEpochDay() + 1_000_000L * day.toEpochDay()));
		}
		return BankHistorySeries.of(points);
	}

	/** A LIST status with a bank, a D1 move, the series and the options its figures were computed under. */
	static PriceService.Status status(@Nullable BankHistorySeries series, long valueNow, long bankAt)
	{
		final PriceService.Status s = bare(true, true);
		when(s.bankAtMillis()).thenReturn(bankAt);
		when(s.portfolio()).thenReturn(summary(valueNow));
		when(s.options()).thenReturn(ViewOptions.DEFAULT);
		when(s.bankHistory()).thenReturn(series);
		return s;
	}

	/** A status with no bank figures: the LOGIN / NO_BANK states. */
	static PriceService.Status bare(boolean loggedIn, boolean bankLoaded)
	{
		final PriceService.Status s = mock(PriceService.Status.class);
		when(s.loggedIn()).thenReturn(loggedIn);
		when(s.bankLoaded()).thenReturn(bankLoaded);
		when(s.bankItems()).thenReturn(bankLoaded ? 3 : 0);
		when(s.totalRows()).thenReturn(bankLoaded ? 3 : 0);
		when(s.window()).thenReturn(MovementWindow.D1);
		when(s.problemKind()).thenReturn(PriceService.ProblemKind.NONE);
		when(s.text()).thenReturn("Guide prices");
		when(s.headerText()).thenReturn("Guide prices");
		when(s.pricesAtMillis()).thenReturn(bankLoaded ? NOW : 0L);
		when(s.bankAtMillis()).thenReturn(bankLoaded ? NOW - 60_000L : 0L);
		when(s.baselineLoaded()).thenReturn(true);
		when(s.thenDay()).thenReturn(TODAY.minusDays(1));
		return s;
	}

	/** The whole bank worth {@code valueNow}, with a guide move on every window (so Items has a move to draw). */
	static PortfolioSummary summary(long valueNow)
	{
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		for (MovementWindow w : MovementWindow.values())
		{
			final long delta = -950_972L;
			final long then = valueNow - delta;
			moves.put(w, new WindowMove(w, TODAY.minusDays(w.days()), then, valueNow, delta, delta * 100.0 / then, 3));
		}
		return new PortfolioSummary(valueNow, 3, 3, moves);
	}

	static List<MovementRow> rows(int n)
	{
		final List<MovementRow> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
		{
			final long unit = 1_000L + i;
			out.add(new MovementRow(1_000 + i, "Item " + i, 1, false, unit, unit - 10L, 10L, 1.0d, unit, null));
		}
		return Collections.unmodifiableList(out);
	}

	private String range()
	{
		return (String) panel.bankHistoryState().get("range");
	}

	private List<Component> header()
	{
		return Arrays.asList(panel.header().getComponents());
	}

	/** The search box's row (1.0.9 part 4): the header's last row in Items. */
	private Component searchRow()
	{
		return panel.searchField().getParent();
	}

	/** The Items | Net Worth History strip: the header row holding the toggle (the panel has no accessor for it). */
	private Container strip()
	{
		for (Component row : panel.header().getComponents())
		{
			if (row instanceof Container && find(row, Widgets.Toggle.class) != null)
			{
				return (Container) row;
			}
		}
		fail("no strip in the header");
		return null;
	}

	private Widgets.Toggle toggle()
	{
		return find(strip(), Widgets.Toggle.class);
	}

	private JLabel half(int i)
	{
		return (JLabel) toggle().getComponent(i);
	}

	/**
	 * The caption: the label in the strip's caption row - the row after the toggle (1.1.1 part G2 put the caption in a row of
	 * its own, which holds the History options icon beside it in History) - that carries words and no icon.
	 */
	private JLabel caption()
	{
		for (Component c : panel.viewCaptionRow().getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getIcon() == null)
			{
				return (JLabel) c;
			}
		}
		fail("no caption in the strip");
		return null;
	}

	private static void assertLitLook(JLabel half, boolean lit)
	{
		assertTrue(half.isOpaque());
		assertEquals(lit ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR, half.getBackground());
		assertEquals(lit ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR, half.getForeground());
		assertEquals(lit, half.getFont().isBold());
	}

	@Nullable
	static <T> T find(Component root, Class<T> type)
	{
		if (type.isInstance(root))
		{
			return type.cast(root);
		}
		if (root instanceof Container)
		{
			for (Component child : ((Container) root).getComponents())
			{
				final T found = find(child, type);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	static List<Component> walk(Component root)
	{
		final List<Component> out = new ArrayList<>();
		out.add(root);
		if (root instanceof Container)
		{
			for (Component child : ((Container) root).getComponents())
			{
				out.addAll(walk(child));
			}
		}
		return out;
	}

	/** A mouse press delivered to {@code c}'s listeners, as the toolkit would deliver it. */
	static void press(Component c, int button)
	{
		final int mask = button == MouseEvent.BUTTON1 ? InputEvent.BUTTON1_DOWN_MASK : InputEvent.BUTTON3_DOWN_MASK;
		final MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0L, mask, 1, 1, 1, false, button);
		for (MouseListener l : c.getMouseListeners())
		{
			l.mousePressed(e);
		}
	}

	static void onEdt(Runnable body) throws Exception
	{
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				body.run();
			}
			catch (Throwable t)
			{
				failure.set(t);
			}
		});
		final Throwable t = failure.get();
		if (t instanceof Exception)
		{
			throw (Exception) t;
		}
		if (t instanceof Error)
		{
			throw (Error) t;
		}
	}
}
