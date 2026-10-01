package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
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
import javax.swing.JPanel;
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

	private boolean legacyRowShows()
	{
		return SwingUtilities.isDescendingFrom(panel.legacyRow(), panel.header());
	}

	private static boolean sameIcon(javax.swing.Icon a, javax.swing.Icon b)
	{
		if (a.getIconWidth() != b.getIconWidth() || a.getIconHeight() != b.getIconHeight())
		{
			return false;
		}
		final java.awt.image.BufferedImage x = new java.awt.image.BufferedImage(a.getIconWidth(), a.getIconHeight(),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		final java.awt.image.BufferedImage y = new java.awt.image.BufferedImage(a.getIconWidth(), a.getIconHeight(),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		a.paintIcon(null, x.getGraphics(), 0, 0);
		b.paintIcon(null, y.getGraphics(), 0, 0);
		for (int i = 0; i < x.getWidth(); i++)
		{
			for (int j = 0; j < x.getHeight(); j++)
			{
				if (x.getRGB(i, j) != y.getRGB(i, j))
				{
					return false;
				}
			}
		}
		return true;
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
	 * With no legacy days the row is not in the tree at all - in either view, with an empty record and with a full
	 * one - so every History picture stays what it was; and in Items it is not there even WITH legacy days, because the
	 * caption it sits under is History's.
	 */
	@Test
	public void theLegacyRowIsAbsentWithoutLegacyDaysAndInItems() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals(Arrays.asList(panel.hero(), strip()), legacyHeader());
			assertFalse(legacyRowShows());
			assertTrue(panel.describe(), panel.describe().contains("\"legacyDays\":false"));
		});
		publish(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertEquals(Arrays.asList(panel.hero(), strip()), legacyHeader());
			assertFalse(legacyRowShows());
		});
		// A mocked status that answers no series at all reads as empty.
		publish(rows(3), status(null, VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse(legacyRowShows()));

		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			assertTrue(panel.describe(), panel.describe().contains("\"legacyDays\":true"));
			assertTrue("in History it is under the caption", legacyRowShows());
			panel.pressView(SidebarView.ITEMS);
			assertFalse("in Items it is not there", legacyRowShows());
			assertEquals(Arrays.asList(panel.hero(), strip(), panel.controlRow(), panel.fold(), searchRow()), header());
		});
	}

	/** Present: directly under the strip, the label verbatim, 11 px grey, the box unticked, a hand cursor. */
	@Test
	public void theLegacyRowStandsUnderTheCaptionWithItsWordsItsBoxAndItsHover() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertEquals("hero, the strip, then the row - nothing between the caption and the chart",
				3, legacyHeader().size());
			assertSame(strip(), legacyHeader().get(1));
			assertSame(panel.legacyRow().getParent(), legacyHeader().get(2));
			final JLabel row = panel.legacyRow();
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, row.getText());
			assertEquals(11, row.getFont().getSize());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, row.getForeground());
			assertTrue("an unticked box", sameIcon(Widgets.checkBox(false), row.getIcon()));
			assertFalse("not a second line: one label", row.getText().contains("\n") || row.getText().contains("<"));
			assertEquals(java.awt.Cursor.HAND_CURSOR, row.getCursor().getType());
			assertEquals("the caption's own indent", 4,
				((javax.swing.border.EmptyBorder) ((JPanel) row.getParent()).getBorder()).getBorderInsets().left);

			// Its hover is behind "Show hover text" like every sentence hover here.
			assertNull("no hover with the switch off", row.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals(BankPriceMovementPanel.LEGACY_TIP, row.getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT);
			assertNull(row.getToolTipText());
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
			assertTrue("the box is ticked", sameIcon(Widgets.checkBox(true), panel.legacyRow().getIcon()));
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":true"));
			assertEquals("written once", Arrays.asList(true), prefs.legacySaves);
			assertTrue("the row stays while the days are there", legacyRowShows());

			// Turned off again: the cut comes back, nothing is asked, and the write follows.
			panel.pressLegacy();
			assertEquals("asked nothing", 1, yes.questions.size());
			assertEquals(2, readings());
			assertEquals(hidden, panel.updateLabel().getText());
			assertEquals(Arrays.asList(true, false), prefs.legacySaves);
			assertTrue(sameIcon(Widgets.checkBox(false), panel.legacyRow().getIcon()));
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
			assertTrue("the way out is on screen", legacyRowShows());
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
			assertTrue(sameIcon(Widgets.checkBox(false), panel.legacyRow().getIcon()));
			assertTrue(panel.describe(), panel.describe().contains("\"includeLegacy\":false"));
		});
		assertTrue("nothing written", prefs.legacySaves.isEmpty());
	}

	/** Through the box's own mouse listener: a left press asks; a right press is not a press. */
	@Test
	public void aLeftPressOnTheRowAsksAndARightPressDoesNot() throws Exception
	{
		final Asked yes = new Asked(true);
		build(yes);
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			press(panel.legacyRow(), MouseEvent.BUTTON3);
			assertTrue(yes.questions.isEmpty());
			press(panel.legacyRow(), MouseEvent.BUTTON1);
			assertEquals(1, yes.questions.size());
			assertEquals(6, readings());
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
			assertTrue(sameIcon(Widgets.checkBox(true), panel.legacyRow().getIcon()));
			panel.setIncludeLegacy(true);
			assertEquals("the same state again redraws what it drew", 6, readings());
			panel.setIncludeLegacy(false);
			assertEquals(2, readings());
			assertTrue(sameIcon(Widgets.checkBox(false), panel.legacyRow().getIcon()));

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
			assertTrue(sameIcon(Widgets.checkBox(true), panel.legacyRow().getIcon()));
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

	/** The row never touches the settings menu: still 23 components. */
	@Test
	public void theSettingsMenuIsStillTwentyThreeComponents() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue(legacyRowShows());
			assertEquals(23, panel.heroMenu().getComponentCount());
		});
	}

	/** A status arriving while the row is up that carries no legacy days takes the row away again. */
	@Test
	public void theRowGoesWhenALaterStatusHasNoLegacyDays() throws Exception
	{
		build(new Asked(true));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			assertTrue(legacyRowShows());
		});
		publish(rows(3), status(series(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertFalse(legacyRowShows()));
		publish(rows(3), status(withLegacyDays(), VALUE_NOW, NOW - 60_000L));
		onEdt(() -> assertTrue(legacyRowShows()));
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

	/** The caption: the strip's label that is not inside the toggle. */
	private JLabel caption()
	{
		for (Component c : strip().getComponents())
		{
			if (c instanceof JLabel)
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
