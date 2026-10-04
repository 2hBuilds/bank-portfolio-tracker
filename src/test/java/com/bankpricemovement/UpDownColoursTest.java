package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
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
import static com.bankpricemovement.SidebarViewPanelTest.walk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part B (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the settings menu's two
 * colour rows and what the colour picker does to the sidebar - frozen tests B3, B4, B5 and B6. (B1, B2 and B7b are
 * {@link MoveColoursTest}'s, B7 is the wiring test's, and B8 the picture tests'.)
 *
 * <p>The palette is one static for the whole process, so every test clears it in {@code @After}: no other test may see it.
 */
public class UpDownColoursTest
{
	/** What a reader picks: neither equals a built-in colour. */
	private static final Color UP = new Color(10, 20, 200);
	private static final Color UP2 = new Color(90, 40, 160);
	private static final Color UP3 = new Color(200, 100, 20);
	private static final Color DOWN = new Color(200, 150, 0);

	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config as a memory: what the colours and the presets are stored as, and every write of them. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		Color up;
		@Nullable
		Color down;
		final List<Color> upSaves = new ArrayList<>();
		final List<Color> downSaves = new ArrayList<>();
		final List<BandPresets> presetSaves = new ArrayList<>();
		final List<RowFilter> filterSaves = new ArrayList<>();
		final List<SidebarView> tabSaves = new ArrayList<>();

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
		public Color loadUpColour()
		{
			return up;
		}

		@Override
		public void saveUpColour(Color colour)
		{
			upSaves.add(colour);
		}

		@Override
		public Color loadDownColour()
		{
			return down;
		}

		@Override
		public void saveDownColour(Color colour)
		{
			downSaves.add(colour);
		}

		/**
		 * Single chart colour is stored OFF here: these tests read the chart following the range in the up and down
		 * colours, which part D's default (the one gold colour, ON) would hide.
		 */
		@Override
		public Boolean loadSingleChartColour()
		{
			return Boolean.FALSE;
		}

		boolean wroteNothing()
		{
			return upSaves.isEmpty() && downSaves.isEmpty() && presetSaves.isEmpty() && filterSaves.isEmpty()
				&& tabSaves.isEmpty();
		}
	}

	/** A colour picker that shows nothing and keeps every request it was given, so a test moves it by hand. */
	private static final class FakePicker implements ColourPicker
	{
		final List<Component> anchors = new ArrayList<>();
		final List<Color> starts = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		Consumer<Color> live;
		Consumer<Color> done;

		@Override
		public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
		{
			anchors.add(anchor);
			starts.add(start);
			titles.add(title);
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

	private void build(Memory prefs, @Nullable ColourPicker picker) throws Exception
	{
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			panel.setClock(() -> NOW);
			panel.setColourPicker(picker);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	/** Three rising rows, a card whose windows all fell, and a record that rose day by day to today. */
	private void load() throws Exception
	{
		final PriceService.Status s = status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	private JMenuItem row(boolean up)
	{
		final Component c = panel.heroMenu().getComponent(up ? 6 : 7);
		assertTrue(String.valueOf(c), c instanceof JMenuItem);
		return (JMenuItem) c;
	}

	/** The Items row's percentage as drawn. */
	private Color rowPercent()
	{
		return panel.rowPanels().get(0).changeColor();
	}

	/** The History tab's day rows' percentages as drawn: every label ending in a percent sign. */
	private List<Color> dayPercents()
	{
		final List<Color> out = new ArrayList<>();
		for (final Component c : walk(find(panel, BankHistoryView.class)))
		{
			if (c instanceof BankHistoryDayRow)
			{
				for (final Component part : ((BankHistoryDayRow) c).getComponents())
				{
					if (part instanceof JLabel && ((JLabel) part).getText().endsWith("%"))
					{
						out.add(part.getForeground());
					}
				}
			}
		}
		assertFalse("the History tab drew day rows with a percentage", out.isEmpty());
		return out;
	}

	private Color chartLine()
	{
		return find(panel, BankHistoryChart.class).lineColour();
	}

	private static void assertAll(String what, Color expected, List<Color> actual)
	{
		for (final Color c : actual)
		{
			assertEquals(what, expected, c);
		}
	}

	/** The row as the look and feel paints it at its preferred size. */
	private static BufferedImage paint(JMenuItem row)
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

	// ---------------------------------------------------------------- B3: the rows

	/**
	 * B3: the two rows sit directly after "Show change in %" and before the rule; each shows a swatch of the colour in force
	 * at its right end (a 24 x 12 box, a 1 px frame in grey 110) - the built-in green and red for a reader who chose
	 * nothing, what is stored otherwise - and the words are in the face of every item.
	 */
	@Test
	public void b3_theTwoRowsSitRightAfterShowChangeInPercentAndTheirSwatchesShowTheCurrentColours() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.up = UP;
		build(prefs, new FakePicker());
		load();
		onEdt(() ->
		{
			final Component[] menu = panel.heroMenu().getComponents();
			assertSame(panel.showPctItem(), menu[4]);
			// 1.1.0 part J: a rule of its own stands above the colour block, so the rows are two places lower than in part B.
			assertTrue("part J's rule above the colour rows", menu[5] instanceof JSeparator);
			assertEquals("Up colour", ((JMenuItem) menu[6]).getText());
			assertEquals("Down colour", ((JMenuItem) menu[7]).getText());
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((JMenuItem) menu[6]).getText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TEXT, ((JMenuItem) menu[7]).getText());
			// 1.1.0 parts H and J: the "Colour presets" caption and its five rows (the three sets, Slot 1 and the save row)
			// come next (ColourSetsTest and ColourPresetsTest pin them), then the rule.
			assertTrue("the colour presets' five rows", menu[9] instanceof SetRow && menu[10] instanceof SetRow
				&& menu[11] instanceof SetRow && menu[12] instanceof SetRow && menu[13] instanceof SetRow);
			assertTrue("then the rule that opens the view group", menu[14] instanceof JSeparator);
			assertSame(panel.livePricesItem(), menu[15]);
			assertEquals(Widgets.sans(12), menu[6].getFont());
			assertEquals(Widgets.sans(12), menu[7].getFont());

			// The words stand in the check items' own column: since 1.1.0 part C each row is a check item to the look and
			// feel (ChartColourTest.c0_... paints it), not a plain item with a blank icon of a check box's size.
			assertTrue(row(true) instanceof javax.swing.JCheckBoxMenuItem);
			assertTrue(row(false) instanceof javax.swing.JCheckBoxMenuItem);

			assertSwatch(row(true), UP);
			assertSwatch(row(false), Widgets.MOVE_DOWN_TEXT);

			// The swatch follows the colours as they change.
			panel.applyMoveColours(UP2, DOWN);
			assertSwatch(row(true), UP2);
			assertSwatch(row(false), DOWN);
			panel.applyMoveColours(null, null);
			assertSwatch(row(true), ColorScheme.PROGRESS_COMPLETE_COLOR);
			assertSwatch(row(false), Widgets.MOVE_DOWN_TEXT);
		});
		assertTrue("nothing was written to show them", prefs.wroteNothing());
	}

	/** The swatch's middle is the colour, its left edge is the frame, and it is 24 x 12 with the row's inset from the right. */
	private static void assertSwatch(JMenuItem row, Color colour)
	{
		final BufferedImage img = paint(row);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final java.awt.Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		assertEquals(row.getText() + ": the swatch's colour", colour.getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH / 2, y + BankPriceMovementPanel.SWATCH_HEIGHT / 2));
		assertEquals("its frame, grey 110, left", new Color(110, 110, 110).getRGB(), img.getRGB(x, y + 6));
		assertEquals("...right", new Color(110, 110, 110).getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH - 1, y + 6));
		assertEquals("...top", new Color(110, 110, 110).getRGB(), img.getRGB(x + 12, y));
		assertEquals("...bottom", new Color(110, 110, 110).getRGB(),
			img.getRGB(x + 12, y + BankPriceMovementPanel.SWATCH_HEIGHT - 1));
		assertEquals("inside the frame, 1 px in, the colour already", colour.getRGB(), img.getRGB(x + 1, y + 1));
		assertEquals("and the row is wide enough for words and swatch", img.getWidth(), row.getWidth());
	}

	/**
	 * B3: a press on a row calls the seam ONCE with the colour in force and its title - "Up colour" / "Down colour" - and
	 * the sidebar's own panel as the anchor the picker is placed beside; the hovers are the config items' descriptions,
	 * behind "Show hover text".
	 */
	@Test
	public void b3_aPressCallsTheSeamOnceWithTheColourAndTheTitle() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.up = UP;
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load();
		onEdt(() ->
		{
			assertNull("no hover while the switch is off", row(true).getToolTipText());
			panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true));
			assertEquals("The colour of a rise, on both tabs.", row(true).getToolTipText());
			assertEquals("The colour of a fall, on both tabs.", row(false).getToolTipText());
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TIP, row(true).getToolTipText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TIP, row(false).getToolTipText());

			row(true).doClick(0);
			assertEquals(1, picker.titles.size());
			assertEquals("Up colour", picker.titles.get(0));
			assertEquals(UP, picker.starts.get(0));
			assertSame("beside the sidebar's own panel", panel, picker.anchors.get(0));

			row(false).doClick(0);
			assertEquals(2, picker.titles.size());
			assertEquals("Down colour", picker.titles.get(1));
			assertEquals("a reader who chose nothing starts on the built-in red", Widgets.MOVE_DOWN_TEXT,
				picker.starts.get(1));
			assertSame(panel, picker.anchors.get(1));
		});
		assertTrue("a press writes nothing; the picker's close does", prefs.wroteNothing());
		verify(service, org.mockito.Mockito.never()).setFilter(any());
		verify(service, org.mockito.Mockito.never()).setOptions(any());
	}

	/** B3: with no seam (null: the headless renderer's) the rows do nothing at all. */
	@Test
	public void b3_withNoSeamTheRowsDoNothing() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, null);
		load();
		onEdt(() ->
		{
			final Color before = rowPercent();
			row(true).doClick(0);
			row(false).doClick(0);
			assertEquals(before, rowPercent());
			panel.setColourPicker(null);
			row(true).doClick(0);
		});
		assertTrue(prefs.wroteNothing());
	}

	// ---------------------------------------------------------------- B4: the picker moves, the sidebar follows

	/**
	 * B4: the seam's {@code live} turns the card's move figure, an Items row's percentage, a History day row's percentage and
	 * the chart's line to the new colour - the tab on screen at once and the other on arrival - with zero service calls
	 * and zero Prefs writes.
	 */
	@Test
	public void b4_liveRecoloursTheCardAnItemsRowADayRowAndTheChartWithNoServiceCallAndNoWrite() throws Exception
	{
		final Memory prefs = new Memory();
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			// ---- Items is showing: a rising row, and a card whose windows all fell.
			assertEquals(SidebarView.ITEMS, panel.view());
			assertEquals("a rise starts in the built-in green", ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
			assertEquals("a fall starts in the lifted red", Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());
			final int rebuilds = panel.rebuilds();

			row(true).doClick(0);
			picker.live.accept(UP);
			assertEquals("the Items row follows at once", UP, rowPercent());
			assertEquals("...and its rail is the colour's darker()", UP.darker(), panel.rowPanels().get(0).railColor());
			assertEquals("the card's fall is the other direction: unmoved", Widgets.MOVE_DOWN_TEXT,
				panel.deltaLabel().getForeground());
			picker.live.accept(UP2);
			assertEquals("every move of the picker, not the first", UP2, rowPercent());
			assertTrue("the rows were drawn again from what the panel holds", panel.rebuilds() > rebuilds);

			row(false).doClick(0);
			picker.live.accept(DOWN);
			assertEquals("the card's move figure", DOWN, panel.deltaLabel().getForeground());
			assertEquals("...and its percentage", DOWN, panel.pctLabel().getForeground());
			assertEquals("the rising row keeps the up colour", UP2, rowPercent());

			// ---- the History tab, which was not showing, is drawn in both on arrival.
			panel.setView(SidebarView.HISTORY);
			assertEquals("the day rows' percentages (the record rose)", UP2.getRGB(), dayPercents().get(0).getRGB());
			assertAll("every day row", UP2, dayPercents());
			assertEquals("the chart's line follows the range in the up colour", UP2, chartLine());
			assertEquals("the History card's move: the record rose", UP2, panel.deltaLabel().getForeground());

			// ---- and with History showing, the picker moves it live.
			final int itemRebuilds = panel.rebuilds();
			row(true).doClick(0);
			picker.live.accept(UP3);
			assertAll("every day row follows at once", UP3, dayPercents());
			assertEquals("the chart's line", UP3, chartLine());
			assertEquals("the card", UP3, panel.deltaLabel().getForeground());
			assertEquals("the Items rows are not rebuilt at every move while they are away", itemRebuilds,
				panel.rebuilds());

			// ---- back on Items the rows are drawn in what the picker left.
			panel.setView(SidebarView.ITEMS);
			assertEquals(UP3, rowPercent());
			assertEquals("one rebuild, on arrival", itemRebuilds + 1, panel.rebuilds());
			panel.setView(SidebarView.HISTORY);
			panel.setView(SidebarView.ITEMS);
			assertEquals("and no more once they are current", itemRebuilds + 1, panel.rebuilds());
		});
		verifyNoInteractions(service);
		assertTrue("live writes nothing", prefs.wroteNothing());
	}

	/** B4: a falling record draws the History tab in the down colour: the day rows, the chart's line and the card. */
	@Test
	public void b4_aFallingRecordTakesTheDownColourOnTheHistoryTab() throws Exception
	{
		final Memory prefs = new Memory();
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 2; i >= 0; i--)
		{
			points.add(SidebarViewPanelTest.point(TODAY.minusDays(i), VALUE_NOW + 1_000_000L * i));
		}
		final PriceService.Status s = status(BankHistorySeries.of(points), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
		clearInvocations(service);
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			assertAll("the built-in lifted red", Widgets.MOVE_DOWN_TEXT, dayPercents());
			assertEquals(Widgets.MOVE_DOWN_TEXT, chartLine());
			row(false).doClick(0);
			picker.live.accept(DOWN);
			assertAll("every day row", DOWN, dayPercents());
			assertEquals(DOWN, chartLine());
			assertEquals(DOWN, panel.deltaLabel().getForeground());
		});
		verifyNoInteractions(service);
		assertTrue(prefs.wroteNothing());
	}

	/**
	 * B4: the seam's {@code done} stores the colour - once, as an opaque colour - and draws it; closing the picker without a
	 * move stores what it opened on, and a done on a stopped panel stores nothing.
	 */
	@Test
	public void b4_doneWritesTheKeyOnce() throws Exception
	{
		final Memory prefs = new Memory();
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			row(true).doClick(0);
			picker.live.accept(UP);
			picker.live.accept(UP2);
			assertTrue("nothing stored while it moves", prefs.upSaves.isEmpty());
			picker.done.accept(new Color(UP2.getRed(), UP2.getGreen(), UP2.getBlue(), 40));
			assertEquals("the key, once, opaque", Arrays.asList(UP2), prefs.upSaves);
			assertEquals(255, prefs.upSaves.get(0).getAlpha());
			assertTrue(prefs.downSaves.isEmpty());
			assertEquals(UP2, rowPercent());

			row(false).doClick(0);
			picker.live.accept(DOWN);
			picker.done.accept(DOWN);
			assertEquals(Arrays.asList(DOWN), prefs.downSaves);
			assertEquals("the other key is not written again", 1, prefs.upSaves.size());

			// Closed on what it opened on: the colour is stored all the same (the picker has no cancel).
			row(false).doClick(0);
			picker.done.accept(DOWN);
			assertEquals(Arrays.asList(DOWN, DOWN), prefs.downSaves);
		});
		assertTrue("the presets, the filter and the tab are untouched", prefs.presetSaves.isEmpty()
			&& prefs.filterSaves.isEmpty() && prefs.tabSaves.isEmpty());
		verifyNoInteractions(service);
		onEdt(() ->
		{
			panel.stop();
			picker.done.accept(UP);
			assertEquals("a stopped panel stores nothing", 1, prefs.upSaves.size());
		});
	}

	// ---------------------------------------------------------------- B5: from outside

	/**
	 * B5, the panel's half: the colours arriving from outside ({@code applyMoveColours}, which the plugin's
	 * {@code ConfigChanged} road calls) recolour the whole panel and write nothing - the plugin half, that the panel's
	 * own write is not echoed, is the wiring test's. A null colour is the built-in one, and a stopped panel changes nothing.
	 */
	@Test
	public void b5_colourArrivingFromOutsideRecoloursThePanelAndWritesNothing() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			panel.applyMoveColours(UP, DOWN);
			assertEquals(UP, rowPercent());
			assertEquals(DOWN, panel.deltaLabel().getForeground());
			assertEquals(UP, Widgets.move(1, Widgets.Kind.FIGURE));
			assertSwatch(row(true), UP);
			assertSwatch(row(false), DOWN);

			panel.applyMoveColours(UP2, null);
			assertEquals(UP2, rowPercent());
			assertEquals("null is the built-in colour", Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());

			panel.applyMoveColours(null, null);
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
		});
		verifyNoInteractions(service);
		assertTrue(prefs.wroteNothing());
		onEdt(() ->
		{
			panel.stop();
			panel.applyMoveColours(UP, DOWN);
			assertEquals("a stopped panel changes nothing", ColorScheme.PROGRESS_COMPLETE_COLOR,
				Widgets.move(1, Widgets.Kind.FIGURE));
		});
	}

	// ---------------------------------------------------------------- B6: Reset to default

	/**
	 * B6: "Reset to default" writes both colours back to their defaults - stored, and drawn at once - and the presets as it
	 * always did (the three bands back to 100k / 1m / 10m, written when they were not already).
	 */
	@Test
	public void b6_resetToDefaultPutsBothColoursBackAndTheBandsAsToday() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.up = UP;
		prefs.down = DOWN;
		build(prefs, new FakePicker());
		load();
		onEdt(() ->
		{
			panel.applyMoveColours(UP, DOWN);
			panel.setPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));
			assertEquals(1, prefs.presetSaves.size());
			assertEquals("a fall on the card is in the reader's colour", DOWN, panel.deltaLabel().getForeground());

			panel.resetPresetsButton().doClick(0);

			assertEquals("the up colour stored as the default", Arrays.asList(ColorScheme.PROGRESS_COMPLETE_COLOR),
				prefs.upSaves);
			assertEquals("the down colour stored as the default", Arrays.asList(Widgets.MOVE_DOWN_TEXT), prefs.downSaves);
			assertEquals("and the bands as today", Arrays.asList(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L),
				BandPresets.DEFAULT), prefs.presetSaves);
			assertEquals(BandPresets.DEFAULT, panel.presets());
			assertEquals("drawn at once: the rising row", ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
			assertEquals("...the card", Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());
			assertSwatch(row(true), ColorScheme.PROGRESS_COMPLETE_COLOR);
			assertSwatch(row(false), Widgets.MOVE_DOWN_TEXT);
			assertEquals("the built-in red mark is back too, not the lifted red", ColorScheme.PROGRESS_ERROR_COLOR,
				Widgets.move(-1, Widgets.Kind.MARK));
		});
		assertTrue(prefs.filterSaves.isEmpty());
		assertNotNull(prefs.up);
	}
}
