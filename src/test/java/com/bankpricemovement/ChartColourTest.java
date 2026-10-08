package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.TODAY;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.find;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.rows;
import static com.bankpricemovement.SidebarViewPanelTest.series;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part C (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the swatch rows that
 * stand in the check items' column (C0), the net worth chart's "Single chart colour" and its colour (C2 to C6) and the
 * version (C7). C1 is {@link BankPriceMovementConfigTest}'s, the plugin's half of C6 the wiring test's, and the pictures'
 * half of C7 {@link HistorySidebarPicturesTest}'s.
 *
 * <p>Part D (Single chart colour ON by default) is {@code d2_} and {@code d3_} below, with D1 in
 * {@link BankPriceMovementConfigTest} and D4 in {@link HistorySidebarPicturesTest}. The part C tests that read the switch
 * OFF by default - the chart following the range, an unticked row - store it OFF explicitly in {@link Memory}, so their
 * meaning holds; the ones that put the switch back on or off do so from a stored state they name.
 *
 * <p>The palette is one static for the whole process, so every test clears it in {@code @After}: no other test may see it.
 */
public class ChartColourTest
{
	/** What a reader picks: none equals a built-in colour or the default chart gold. */
	private static final Color UP = new Color(10, 20, 200);
	private static final Color DOWN = new Color(200, 150, 0);
	private static final Color CHART = new Color(120, 30, 160);
	private static final Color CHART2 = new Color(30, 160, 120);
	/** The logo gold, the chart colour's default. */
	private static final Color GOLD = new Color(196, 156, 58);

	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config as a memory: what the chart switch and colour are stored as, and every write of anything. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		/**
		 * Stored OFF unless a test says otherwise: part C's tests were written for an unticked row and a chart that follows
		 * the range, and part D made ON the default for a panel that finds nothing stored. A test that wants nothing
		 * stored sets it to null.
		 */
		@Nullable
		Boolean single = Boolean.FALSE;
		@Nullable
		Color chart;
		final List<Boolean> singleSaves = new ArrayList<>();
		final List<Color> chartSaves = new ArrayList<>();
		final List<Color> upSaves = new ArrayList<>();
		final List<Color> downSaves = new ArrayList<>();
		final List<BandPresets> presetSaves = new ArrayList<>();
		final List<RowFilter> filterSaves = new ArrayList<>();
		final List<SidebarView> tabSaves = new ArrayList<>();
		final List<Boolean> legacySaves = new ArrayList<>();

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
		public void savePresets(BandPresets presets)
		{
			presetSaves.add(presets);
		}

		@Override
		public void saveStartTab(SidebarView tab)
		{
			tabSaves.add(tab);
		}

		@Override
		public void saveIncludeLegacy(boolean include)
		{
			legacySaves.add(include);
		}

		@Override
		public void saveUpColour(Color colour)
		{
			upSaves.add(colour);
		}

		@Override
		public void saveDownColour(Color colour)
		{
			downSaves.add(colour);
		}

		@Override
		public Boolean loadSingleChartColour()
		{
			return single;
		}

		@Override
		public void saveSingleChartColour(boolean on)
		{
			singleSaves.add(on);
		}

		@Override
		public Color loadChartColour()
		{
			return chart;
		}

		@Override
		public void saveChartColour(Color colour)
		{
			chartSaves.add(colour);
		}

		boolean wroteNothing()
		{
			return singleSaves.isEmpty() && chartSaves.isEmpty() && upSaves.isEmpty() && downSaves.isEmpty()
				&& presetSaves.isEmpty() && filterSaves.isEmpty() && tabSaves.isEmpty() && legacySaves.isEmpty();
		}
	}

	/** A colour picker that shows nothing and keeps every request it was given, so a test moves it by hand. */
	private static final class FakePicker implements ColourPicker
	{
		final List<Component> anchors = new ArrayList<>();
		final List<Color> starts = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		/** The tab on screen at the moment each picker opened: the chart's tab must already be showing. */
		final List<SidebarView> viewsAtOpen = new ArrayList<>();
		Supplier<SidebarView> view = () -> null;
		Consumer<Color> live;
		Consumer<Color> done;

		@Override
		public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
		{
			anchors.add(anchor);
			starts.add(start);
			titles.add(title);
			viewsAtOpen.add(view.get());
			this.live = live;
			this.done = done;
		}
	}

	@After
	public void tearDown() throws Exception
	{
		Widgets.setMoveColours(null, null);
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
	}

	private PriceService mockService()
	{
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		return service;
	}

	private void build(Memory prefs, @Nullable FakePicker picker) throws Exception
	{
		mockService();
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			panel.setClock(() -> NOW);
			panel.setColourPicker(picker);
			if (picker != null)
			{
				picker.view = panel::view;
			}
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	private void load(@Nullable BankHistorySeries record) throws Exception
	{
		final PriceService.Status s = status(record, VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	/** Readings that rose day by day to today. */
	private static BankHistorySeries rising()
	{
		return series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
	}

	/** Readings that fell day by day to today. */
	private static BankHistorySeries falling()
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 2; i >= 0; i--)
		{
			points.add(SidebarViewPanelTest.point(TODAY.minusDays(i), VALUE_NOW + 1_000_000L * i));
		}
		return BankHistorySeries.of(points);
	}

	/** Four readings before 1.0.9 and two since it. */
	private static BankHistorySeries withLegacyDays()
	{
		return series(TODAY.minusDays(7), TODAY.minusDays(6), TODAY.minusDays(4), TODAY.minusDays(3),
			TODAY.minusDays(1), TODAY).withFreshFrom(TODAY.minusDays(1));
	}

	/**
	 * The menu's "Single chart colour" row: after the caption, which is component 23 with or without the days item (24 before 1.2.0 took the hover item out; 21 before
	 * 1.1.0 part J put a rule above the colour rows and Slot 1 and its save row under the presets; 17 before part H).
	 */
	private SwatchRow chartRow()
	{
		final Component c = panel.heroMenu().getComponent(24);
		assertTrue(String.valueOf(c), c instanceof SwatchRow);
		return (SwatchRow) c;
	}

	private BankHistoryChart chart()
	{
		return find(panel, BankHistoryChart.class);
	}

	/** The chart as painted, at the sidebar's width. */
	private BufferedImage paintedChart()
	{
		final BankHistoryChart chart = chart();
		chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);
		return BankHistoryChartTest.paint(chart);
	}

	/** Whether some pixel of the image is exactly {@code colour}: the latest point's dot, drawn opaque, is. */
	private static boolean has(BufferedImage image, Color colour)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == (colour.getRGB() & 0xFFFFFF))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * C0: a colour row's model never selects - a press fires the action and leaves no tick, and nothing can tick it - while
	 * the "Single chart colour" row is an ordinary check item.
	 */
	@Test
	public void c0_aColourRowNeverSelectsAndTheChartRowDoes() throws Exception
	{
		build(new Memory(), new FakePicker());
		load(rising());
		onEdt(() ->
		{
			for (final int index : new int[]{6, 7})
			{
				final JMenuItem row = (JMenuItem) panel.heroMenu().getComponent(index);
				assertTrue("a check item to Swing", row instanceof JCheckBoxMenuItem);
				assertTrue(row instanceof SwatchRow);
				final AtomicInteger fired = new AtomicInteger();
				row.addActionListener(e -> fired.incrementAndGet());
				row.doClick(0);
				assertEquals("the press fired the action", 1, fired.get());
				assertFalse("and left no tick", row.isSelected());
				row.setSelected(true);
				assertFalse("nothing can tick it", row.isSelected());
				row.doClick(0);
				assertEquals(2, fired.get());
				assertFalse(row.isSelected());
			}
			final SwatchRow chart = chartRow();
			assertFalse(chart.isSelected());
			chart.doClick(0);
			assertTrue("the chart row ticks like any check item", chart.isSelected());
		});
	}

	// ---------------------------------------------------------------- C2: the chart's colour

	/**
	 * C2: with Single chart colour off the chart's line - and its latest point, painted - follows the range in the up and
	 * down colours; on, it is the chart colour on a rising AND a falling range, the end dot with it. The up and down
	 * colours are set so that neither is the chart colour nor a built-in one.
	 */
	@Test
	public void c2_theLineFollowsTheRangeWhenOffAndIsTheChartColourOnARiseAndOnAFallWhenOn() throws Exception
	{
		Widgets.setMoveColours(UP, DOWN);
		for (final boolean rise : new boolean[]{true, false})
		{
			final Color own = rise ? UP : DOWN;
			final Color other = rise ? DOWN : UP;
			final Memory prefs = new Memory();
			build(prefs, new FakePicker());
			load(rise ? rising() : falling());
			clearInvocations(service);
			onEdt(() ->
			{
				panel.setView(SidebarView.HISTORY);
				assertEquals((rise ? "rising" : "falling") + ", off: the range's colour", own, chart().lineColour());
				assertTrue("the end dot is drawn in it", has(paintedChart(), own));
				assertFalse(has(paintedChart(), CHART));

				panel.applyChartColour(true, CHART);
				assertEquals("on: the chart colour", CHART, chart().lineColour());
				final BufferedImage image = paintedChart();
				assertTrue("the line, fill and end dot are the chart colour (the dot, drawn opaque, is exactly it)",
					has(image, CHART));
				assertFalse("and none of it is the range's colour", has(image, own));
				assertFalse(has(image, other));

				panel.applyChartColour(false, CHART);
				assertEquals("off again: the range's colour", own, chart().lineColour());
				assertTrue(has(paintedChart(), own));
				assertFalse(has(paintedChart(), CHART));
			});
			verifyNoInteractions(service);
			assertTrue(prefs.wroteNothing());
			onEdt(() -> panel.stop());
			panel = null;
		}
	}

	/**
	 * C2: a chart that was drawn in the chart colour keeps it when its data changes - a new publish, a range chip - and a
	 * stored switch is on from the first paint, for a panel built with it.
	 */
	@Test
	public void c2_theColourSurvivesNewDataAndAStoredSwitchIsOnFromTheFirstPaint() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = true;
		prefs.chart = CHART;
		Widgets.setMoveColours(UP, DOWN);
		build(prefs, new FakePicker());
		load(rising());
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			assertEquals("a panel built with the switch on draws the chart in the colour at once", CHART,
				chart().lineColour());
			assertTrue(chartRow().isSelected());
		});
		load(falling());
		onEdt(() ->
		{
			assertEquals("a new record, a fall: still the chart colour", CHART, chart().lineColour());
			panel.setHistoryRange(BankHistoryRange.D7);
			assertEquals(CHART, chart().lineColour());
		});
		assertTrue("nothing was written to seed it", prefs.wroteNothing());
	}

	// ---------------------------------------------------------------- C3: the menu, component by component

	/**
	 * C3: the menu at the end of part C, in order - header, rule, the three Show items, (since part J) a rule, the Up and
	 * Down colour rows, (since parts H and J) the "Colour presets" caption and its five rows, rule, Use live prices, the
	 * four Include items, rule, the preset row, rule, the "Net worth chart" caption, Single chart colour,
	 * the OK row - and with days before 1.0.9 the Include-days item stands after Single chart colour. (Part J moved the
	 * indices and the counts below; no assertion was weakened. Release 1.2.0 took "Show hover text" out of the menu:
	 * one component fewer, and every index after the preset row one lower.)
	 */
	@Test
	public void c3_theMenuReadsInOrderWithAndWithoutDaysBeforeV109() throws Exception
	{
		build(new Memory(), new FakePicker());
		load(rising());
		onEdt(() ->
		{
			final Component[] c = panel.heroMenu().getComponents();
			assertEquals(26, c.length);
			assertFalse(c[0] instanceof JSeparator || c[0] instanceof javax.swing.AbstractButton);
			assertTrue(c[1] instanceof JSeparator);
			assertSame(panel.showValueItem(), c[2]);
			assertSame(panel.showGpItem(), c[3]);
			assertSame(panel.showPctItem(), c[4]);
			assertTrue("part J's rule above the colour block", c[5] instanceof JSeparator);
			assertEquals("Up colour", ((JMenuItem) c[6]).getText());
			assertEquals("Down colour", ((JMenuItem) c[7]).getText());
			assertTrue(c[6] instanceof SwatchRow && c[7] instanceof SwatchRow);
			assertEquals("Colour presets", find(c[8], JLabel.class).getText());
			assertTrue(c[9] instanceof SetRow && c[10] instanceof SetRow && c[11] instanceof SetRow
				&& c[12] instanceof SetRow && c[13] instanceof SetRow);
			assertTrue(c[14] instanceof JSeparator);
			assertSame(panel.livePricesItem(), c[15]);
			assertSame(panel.countCashItem(), c[16]);
			assertSame(panel.countUntradeablesItem(), c[17]);
			assertSame(panel.countInventoryItem(), c[18]);
			assertSame(panel.countGrandExchangeItem(), c[19]);
			assertTrue(c[20] instanceof JSeparator);
			assertSame(panel.presetRow(), c[21]);
			assertTrue(c[22] instanceof JSeparator);
			assertEquals("Net worth chart", find(c[23], JLabel.class).getText());
			assertTrue(c[24] instanceof SwatchRow);
			assertEquals("Single chart colour", ((SwatchRow) c[24]).getText());
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, ((SwatchRow) c[24]).getText());
			assertEquals(Widgets.sans(12), c[24].getFont());
			assertSame(panel.okRow(), c[25]);
		});

		load(withLegacyDays());
		onEdt(() ->
		{
			final Component[] c = panel.heroMenu().getComponents();
			assertEquals(27, c.length);
			assertEquals("Net worth chart", find(c[23], JLabel.class).getText());
			assertEquals("Single chart colour", ((SwatchRow) c[24]).getText());
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, ((JCheckBoxMenuItem) c[25]).getText());
			assertFalse("an ordinary check item, not a swatch row", c[25] instanceof SwatchRow);
			assertSame("the OK row is still last", panel.okRow(), c[26]);
		});
	}

	// ---------------------------------------------------------------- C4: the row, its swatch and its picker

	/**
	 * C4: ticking Single chart colour shows the swatch and stores true; unticking hides it and stores false. The swatch is
	 * the chart colour (the gold for a reader who chose none), 24 x 12 at the row's right end.
	 */
	@Test
	public void c4_tickingShowsTheSwatchAndStoresTrueAndUntickingHidesItAndStoresFalse() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load(rising());
		clearInvocations(service);
		onEdt(() ->
		{
			final SwatchRow row = chartRow();
			assertFalse(row.isSelected());
			assertNoSwatch(row);

			row.doClick(0);
			assertTrue(row.isSelected());
			assertEquals("true, once", Arrays.asList(true), prefs.singleSaves);
			assertSwatch(row, GOLD);
			assertEquals("the chart is in the colour at once", GOLD, chart().lineColour());

			row.doClick(0);
			assertFalse(row.isSelected());
			assertEquals(Arrays.asList(true, false), prefs.singleSaves);
			assertNoSwatch(row);
			assertEquals("and in the range's own again", Widgets.move(1, Widgets.Kind.FIGURE), chart().lineColour());
		});
		assertTrue("the chart colour itself was never written", prefs.chartSaves.isEmpty());
		verifyNoInteractions(service);
	}

	/**
	 * C4: the swatch's press - a release on it, the road Swing's own menu item takes - calls the seam with the chart colour
	 * and "Chart colour" and ticks nothing; a release anywhere else on the row ticks it. {@code live} recolours the chart
	 * with no service call and no write, and nothing but the chart; {@code done} stores the colour, once.
	 */
	@Test
	public void c4_theSwatchOpensThePickerLiveRecoloursTheChartAndDoneStoresIt() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = true;
		prefs.chart = CHART;
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load(rising());
		clearInvocations(service);
		onEdt(() ->
		{
			final SwatchRow row = chartRow();
			assertTrue("ticked from the stored switch", row.isSelected());
			row.setSize(row.getPreferredSize());
			assertSwatch(row, CHART);

			release(row, row.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH / 2,
				row.getHeight() / 2);
			assertEquals(1, picker.titles.size());
			assertEquals("Chart colour", picker.titles.get(0));
			assertEquals(BankPriceMovementPanel.CHART_COLOUR_TEXT, picker.titles.get(0));
			assertEquals("opening on the chart colour in force", CHART, picker.starts.get(0));
			assertSame("beside the sidebar's own panel", panel, picker.anchors.get(0));
			assertTrue("the swatch's press ticks nothing", row.isSelected());
			assertTrue("and stores nothing", prefs.singleSaves.isEmpty());

			// Live: the chart alone follows, and nothing is written or asked.
			final Color upBefore = Widgets.move(1, Widgets.Kind.FIGURE);
			final Color deltaBefore = panel.deltaLabel().getForeground();
			picker.live.accept(CHART2);
			assertEquals(CHART2, chart().lineColour());
			assertSwatch(row, CHART2);
			picker.live.accept(CHART);
			picker.live.accept(CHART2);
			assertEquals("every move of the picker", CHART2, chart().lineColour());
			assertTrue("nothing stored while it moves", prefs.chartSaves.isEmpty());
			assertEquals("the up and down colours are untouched", upBefore, Widgets.move(1, Widgets.Kind.FIGURE));
			assertEquals("so is the card's figure", deltaBefore, panel.deltaLabel().getForeground());

			// Done stores it, once, opaque.
			picker.done.accept(new Color(CHART2.getRed(), CHART2.getGreen(), CHART2.getBlue(), 40));
			assertEquals(Arrays.asList(CHART2), prefs.chartSaves);
			assertEquals(255, prefs.chartSaves.get(0).getAlpha());
			assertEquals(CHART2, chart().lineColour());

			// A release on the words ticks the row off, as on any check item, and opens nothing.
			release(row, 8, row.getHeight() / 2);
			assertFalse(row.isSelected());
			assertEquals("no second picker", 1, picker.titles.size());
			assertEquals(Arrays.asList(false), prefs.singleSaves);
			assertNoSwatch(row);
		});
		assertTrue("the presets, the filter, the colours and the days are untouched", prefs.presetSaves.isEmpty()
			&& prefs.filterSaves.isEmpty() && prefs.upSaves.isEmpty() && prefs.downSaves.isEmpty()
			&& prefs.legacySaves.isEmpty());
		verifyNoInteractions(service);
		onEdt(() ->
		{
			panel.stop();
			picker.done.accept(CHART);
			assertEquals("a stopped panel stores nothing", 1, prefs.chartSaves.size());
		});
	}

	/** With no seam the swatch does nothing at all - no picker, no tab change. */
	@Test
	public void c4_withNoSeamTheSwatchDoesNothing() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = true;
		build(prefs, null);
		load(rising());
		onEdt(() ->
		{
			final SwatchRow row = chartRow();
			row.setSize(row.getPreferredSize());
			release(row, row.getWidth() - BankPriceMovementPanel.ROW_GAP - 5, row.getHeight() / 2);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertTrue(row.isSelected());
		});
		assertTrue(prefs.wroteNothing());
	}

	/** A mouse release at (x, y) on the row, delivered as the toolkit would: Swing's menu item turns it into a click. */
	private static void release(JMenuItem row, int x, int y)
	{
		row.dispatchEvent(new MouseEvent(row, MouseEvent.MOUSE_RELEASED, 0L, 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	private static final Color SWATCH_FRAME = new Color(110, 110, 110);

	/** The swatch's middle is the colour, and its frame is grey 110: where {@code SwatchRow} paints it. */
	private static void assertSwatch(SwatchRow row, Color colour)
	{
		final BufferedImage img = paintRow(row);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final java.awt.Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		assertEquals("the swatch's colour", colour.getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH / 2, y + BankPriceMovementPanel.SWATCH_HEIGHT / 2));
		assertEquals("its frame, left", SWATCH_FRAME.getRGB(), img.getRGB(x, y + 6));
		assertEquals("...right", SWATCH_FRAME.getRGB(), img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH - 1, y + 6));
	}

	private static void assertNoSwatch(SwatchRow row)
	{
		final BufferedImage img = paintRow(row);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final java.awt.Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		assertNotEquals("no frame where the swatch would be", SWATCH_FRAME.getRGB(), img.getRGB(x, y + 6));
		assertNotEquals("no swatch colour either", GOLD.getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH / 2, y + BankPriceMovementPanel.SWATCH_HEIGHT / 2));
	}

	/** The row as the look and feel paints it at its preferred size. */
	private static BufferedImage paintRow(JMenuItem row)
	{
		row.setSize(row.getPreferredSize());
		final BufferedImage img = new BufferedImage(row.getWidth(), row.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			row.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	// ---------------------------------------------------------------- C4b: from the Items tab

	/**
	 * C4b: from the Items tab, ticking Single chart colour - or unticking it - switches the sidebar to Net Worth History
	 * FIRST, and it stays there; the tab is the remembered one, written once.
	 */
	@Test
	public void c4b_tickingFromTheItemsTabBringsNetWorthHistoryIntoView() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load(rising());
		clearInvocations(service);
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			chartRow().doClick(0);
			assertEquals("ticking brought the chart's tab into view", SidebarView.HISTORY, panel.view());
			assertEquals(Arrays.asList(SidebarView.HISTORY), prefs.tabSaves);
			assertEquals(Arrays.asList(true), prefs.singleSaves);
			assertEquals("seen live: the chart is already in the colour", GOLD, chart().lineColour());
			assertEquals("and it stays there", SidebarView.HISTORY, panel.view());

			panel.pressView(SidebarView.ITEMS);
			assertEquals(Arrays.asList(SidebarView.HISTORY, SidebarView.ITEMS), prefs.tabSaves);
			chartRow().doClick(0);
			assertEquals("unticking brings it into view too", SidebarView.HISTORY, panel.view());
			assertEquals(Arrays.asList(true, false), prefs.singleSaves);
			assertEquals(Arrays.asList(SidebarView.HISTORY, SidebarView.ITEMS, SidebarView.HISTORY), prefs.tabSaves);
		});
		verifyNoInteractions(service);
	}

	/** C4b: from the Items tab the swatch's press switches the sidebar to Net Worth History before the picker opens. */
	@Test
	public void c4b_theSwatchFromTheItemsTabBringsNetWorthHistoryIntoViewBeforeThePickerOpens() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = true;
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load(rising());
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			final SwatchRow row = chartRow();
			row.setSize(row.getPreferredSize());
			release(row, row.getWidth() - BankPriceMovementPanel.ROW_GAP - 5, row.getHeight() / 2);
			assertEquals(1, picker.titles.size());
			assertEquals("the tab was already in view when the picker opened", Arrays.asList(SidebarView.HISTORY),
				picker.viewsAtOpen);
			assertEquals(SidebarView.HISTORY, panel.view());
			assertEquals("and it is the remembered tab", Arrays.asList(SidebarView.HISTORY), prefs.tabSaves);
		});
		assertTrue(prefs.singleSaves.isEmpty() && prefs.chartSaves.isEmpty());
	}

	// ---------------------------------------------------------------- C5: Reset to default

	/**
	 * C5, as part D (D-2) changed it: "Reset to default" puts Single chart colour back ON - from a switch the reader had
	 * turned off, with a chart colour of their own - and the chart colour back to the gold, both stored and drawn at once,
	 * leaves the tab showing as it was, and its hover says so. (Before part D the reset turned the switch off.)
	 */
	@Test
	public void c5_resetToDefaultPutsTheSwitchBackOnAndTheGoldBack() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = false;
		prefs.chart = CHART;
		build(prefs, new FakePicker());
		load(rising());
		clearInvocations(service);
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			assertEquals("off: the range's colour", Widgets.move(1, Widgets.Kind.FIGURE), chart().lineColour());
			assertFalse(chartRow().isSelected());
			// A band that is not the default, so the reset has one to put back (it writes only what changed).
			final BandPresets custom = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
			panel.setPresets(custom);

			panel.resetPresetsButton().doClick(0);

			assertEquals("the switch stored on", Arrays.asList(true), prefs.singleSaves);
			assertEquals("the colour stored as the gold", Arrays.asList(GOLD), prefs.chartSaves);
			assertTrue("the row is ticked", chartRow().isSelected());
			assertSwatch(chartRow(), GOLD);
			assertEquals("the chart is in the gold", GOLD, chart().lineColour());
			assertEquals("and the bands and the colours as before", Arrays.asList(custom, BandPresets.DEFAULT),
				prefs.presetSaves);
			assertEquals(Arrays.asList(ColorScheme.PROGRESS_COMPLETE_COLOR), prefs.upSaves);
			assertEquals(Arrays.asList(Widgets.MOVE_DOWN_TEXT), prefs.downSaves);
			assertEquals("no tab was brought into view or stored", SidebarView.HISTORY, panel.view());
			assertTrue(prefs.tabSaves.isEmpty());

			// Unticked and ticked again, the row draws the gold: the colour the reset stored.
			chartRow().doClick(0);
			assertEquals(Widgets.move(1, Widgets.Kind.FIGURE), chart().lineColour());
			chartRow().doClick(0);
			assertEquals(GOLD, chart().lineColour());
		});
		assertTrue("the hover says what it puts back",
			BankPriceMovementPanel.RESET_PRESETS_TIP.contains("single chart colour"));
		verifyNoInteractions(service);
	}

	/** C5, from the Items tab: the reset does not move the sidebar off Items. */
	@Test
	public void c5_resetFromTheItemsTabLeavesTheTabAlone() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.single = false;
		build(prefs, new FakePicker());
		load(rising());
		onEdt(() ->
		{
			panel.resetPresetsButton().doClick(0);
			assertEquals(SidebarView.ITEMS, panel.view());
		});
		assertEquals(Arrays.asList(true), prefs.singleSaves);
		assertTrue(prefs.tabSaves.isEmpty());
	}

	// ---------------------------------------------------------------- D: ON by default (1.1.0 part D)

	/**
	 * D2: a panel whose prefs answer null - nothing stored, which is every player who never touched the switch, the
	 * headless renderer and a throwaway seam - draws the chart in the gold on a rising AND on a falling record, and its
	 * menu row is ticked with the gold swatch shown. The up and down colours are set so the gold is neither of them, and
	 * nothing is written to seed it. The seam's own default answer is null.
	 */
	@Test
	public void d2_aPanelWhosePrefsAnswerNullDrawsTheChartInTheGoldOnARiseAndOnAFall() throws Exception
	{
		assertNull("the default method answers nothing stored", new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return null;
			}

			@Override
			public void save(RowFilter filter)
			{
			}
		}.loadSingleChartColour());
		Widgets.setMoveColours(UP, DOWN);
		for (final boolean rise : new boolean[]{true, false})
		{
			final Memory prefs = new Memory();
			prefs.single = null;
			prefs.chart = null;
			build(prefs, new FakePicker());
			load(rise ? rising() : falling());
			onEdt(() ->
			{
				final SwatchRow row = chartRow();
				assertTrue((rise ? "rising" : "falling") + ": the row is ticked", row.isSelected());
				assertSwatch(row, GOLD);
				panel.setView(SidebarView.HISTORY);
				assertEquals("the line is the gold", GOLD, chart().lineColour());
				final BufferedImage image = paintedChart();
				assertTrue("the end dot, drawn opaque, is exactly the gold", has(image, GOLD));
				assertFalse("and none of it is the rise's colour", has(image, UP));
				assertFalse("or the fall's", has(image, DOWN));
			});
			assertTrue("nothing was written to seed it", prefs.wroteNothing());
			onEdt(() -> panel.stop());
			panel = null;
		}
	}

	/**
	 * D3: "Reset to default" leaves the switch ON and the colour gold, both stored - from a switch that was on with a
	 * colour of the reader's, and from one that was off - and the up and down colours go back as before. The hover names
	 * the single chart colour.
	 */
	@Test
	public void d3_resetToDefaultLeavesTheSwitchOnAndTheColourGoldBothStored() throws Exception
	{
		for (final boolean startedOn : new boolean[]{true, false})
		{
			final Memory prefs = new Memory();
			prefs.single = startedOn;
			prefs.chart = CHART;
			build(prefs, new FakePicker());
			load(falling());
			clearInvocations(service);
			onEdt(() ->
			{
				panel.setView(SidebarView.HISTORY);
				assertEquals(startedOn ? CHART : Widgets.move(-1, Widgets.Kind.FIGURE), chart().lineColour());

				panel.resetPresetsButton().doClick(0);

				assertEquals("the switch stored on, once", Arrays.asList(true), prefs.singleSaves);
				assertEquals("the colour stored as the gold, once", Arrays.asList(GOLD), prefs.chartSaves);
				assertTrue("the row is ticked", chartRow().isSelected());
				assertSwatch(chartRow(), GOLD);
				assertEquals("the chart is the gold on a falling record", GOLD, chart().lineColour());
				assertEquals(Arrays.asList(ColorScheme.PROGRESS_COMPLETE_COLOR), prefs.upSaves);
				assertEquals(Arrays.asList(Widgets.MOVE_DOWN_TEXT), prefs.downSaves);
			});
			verifyNoInteractions(service);
			onEdt(() -> panel.stop());
			panel = null;
		}
		assertTrue(BankPriceMovementPanel.RESET_PRESETS_TIP.contains("single chart colour"));
	}

	// ---------------------------------------------------------------- C6: from outside

	/**
	 * C6, the panel's half: the switch and the colour arriving from outside ({@code applyChartColour}, which the plugin's
	 * {@code ConfigChanged} road calls) redraw the chart and the row and write nothing, bring no tab into view and ask the
	 * service for nothing. A null colour is the gold, and a stopped panel changes nothing. The plugin's half - the panel's
	 * own writes are not echoed - is the wiring test's.
	 */
	@Test
	public void c6_aChangeFromOutsideRedrawsTheChartAndWritesNothing() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load(rising());
		clearInvocations(service);
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			panel.applyChartColour(true, CHART);
			assertEquals("still on Items: the settings page does not move the reader", SidebarView.ITEMS, panel.view());
			assertTrue("the row is ticked to match", chartRow().isSelected());
			assertSwatch(chartRow(), CHART);
			// The chart is mounted when its tab is first shown; it has the colour then, and keeps it when the reader
			// goes back to Items.
			panel.setView(SidebarView.HISTORY);
			assertEquals("the hidden tab's chart was drawn in it on arrival", CHART, chart().lineColour());
			panel.setView(SidebarView.ITEMS);

			panel.applyChartColour(true, CHART2);
			assertEquals("the colour alone changed", CHART2, chart().lineColour());
			assertSwatch(chartRow(), CHART2);

			panel.applyChartColour(true, null);
			assertEquals("null is the gold", GOLD, chart().lineColour());

			panel.applyChartColour(false, CHART);
			assertEquals(Widgets.move(1, Widgets.Kind.FIGURE), chart().lineColour());
			assertFalse(chartRow().isSelected());
			assertEquals("a change from the settings page never switches tabs", SidebarView.ITEMS, panel.view());
		});
		verifyNoInteractions(service);
		assertTrue(prefs.wroteNothing());
		final BankHistoryChart held = find(panel, BankHistoryChart.class);
		onEdt(() ->
		{
			panel.stop();
			panel.applyChartColour(true, CHART);
			assertEquals("a stopped panel changes nothing", Widgets.move(1, Widgets.Kind.FIGURE), held.lineColour());
		});
	}

	// ---------------------------------------------------------------- C7: the version

	/** C7: this build is 1.1.0. */
	@Test
	public void c7_theVersionIs110()
	{
		assertEquals("1.2.0", Version.CURRENT);
	}
}
