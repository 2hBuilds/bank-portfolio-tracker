package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Test;
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
import static org.junit.Assert.assertNotEquals;
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
 * 1.1.0 part H (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the "Colour presets" caption
 * (called "Colour sets" until part J) and the Classic / 2h / Colour-blind rows under "Down colour" in BOTH menus - frozen
 * tests H1, H2, H3 and H4. (H5, every pinned picture unchanged, is the picture tests' own: no menu is in a picture.) Part J's
 * ticks, Slot 1 and the save row are {@link ColourPresetsTest}'s.
 *
 * <p>The palette is one static for the whole process, so every test clears it in {@code @After}.
 */
public class ColourSetsTest
{
	/** What a reader picked before clicking a set: neither equals a set's colour nor a built-in one. */
	private static final Color UP = new Color(10, 20, 200);
	private static final Color DOWN = new Color(200, 150, 0);
	/** The sets' colours, spelled out so a change of one is a change of the contract (the constants are not read back). */
	private static final Color TWO_H_UP = new Color(0x30, 0xE5, 0x33);
	private static final Color TWO_H_DOWN = new Color(0x73, 0xBD, 0xFF);
	private static final Color BLIND_UP = new Color(0xFF, 0x9F, 0x43);
	private static final Color BLIND_DOWN = new Color(0x4D, 0xA3, 0xFF);
	private static final Color FRAME = new Color(110, 110, 110);

	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config as a memory: what the colours are stored as and every other write, so a test sees all of them. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		Color up;
		@Nullable
		Color down;
		final List<Color> upSaves = new ArrayList<>();
		final List<Color> downSaves = new ArrayList<>();
		int otherWrites;

		@Override
		public RowFilter load()
		{
			return null;
		}

		@Override
		public void save(RowFilter filter)
		{
			otherWrites++;
		}

		@Override
		public void savePresets(BandPresets presets)
		{
			otherWrites++;
		}

		@Override
		public void saveStartTab(SidebarView tab)
		{
			otherWrites++;
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

		/** Stored OFF so the chart follows the range in the up and down colours, which is what these tests read. */
		@Override
		public Boolean loadSingleChartColour()
		{
			return Boolean.FALSE;
		}
	}

	/** A colour picker that shows nothing and keeps the request, so a test moves it by hand. */
	private static final class FakePicker implements ColourPicker
	{
		final List<String> titles = new ArrayList<>();
		Consumer<Color> live;
		Consumer<Color> done;

		@Override
		public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
		{
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
		final org.mockito.ArgumentCaptor<PriceService.Listener> captor =
			org.mockito.ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	/** Three rising rows, a card whose windows all fell, and a record that rose day by day to today. */
	private void load() throws Exception
	{
		final PriceService.Status s = status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	/** A record that fell day by day to today, under the same three rows. */
	private void loadFalling() throws Exception
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 2; i >= 0; i--)
		{
			points.add(SidebarViewPanelTest.point(TODAY.minusDays(i), VALUE_NOW + 1_000_000L * i));
		}
		final PriceService.Status s = status(BankHistorySeries.of(points), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	/** The menu's components after its "Down colour" row, as many as {@code count}. */
	private static List<Component> after(JPopupMenu menu, int downIndex, int count)
	{
		final Component[] all = menu.getComponents();
		assertEquals("the Down colour row where the menu keeps it", BankPriceMovementPanel.DOWN_COLOUR_TEXT,
			((JMenuItem) all[downIndex]).getText());
		return Arrays.asList(all).subList(downIndex + 1, downIndex + 1 + count);
	}

	private static SetRow setRow(JPopupMenu menu, ColourSet set)
	{
		for (final Component c : menu.getComponents())
		{
			if (c instanceof SetRow && ((SetRow) c).getText().equals(set.label()))
			{
				return (SetRow) c;
			}
		}
		throw new AssertionError("no row for " + set + " in " + Arrays.asList(menu.getComponents()));
	}

	/** The Items row's percentage as drawn: the first row rises. */
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

	/** The row as the look and feel paints it, laid out {@code width} px wide at its preferred height. */
	private static BufferedImage paint(JMenuItem row, int width)
	{
		row.setSize(width, row.getPreferredSize().height);
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

	// ---------------------------------------------------------------- H1: the caption and the three rows

	/**
	 * H1, the settings menu: the grey caption "Colour presets" (part J renamed it from "Colour sets") and the rows Classic, 2h,
	 * Colour-blind, Slot 1 and "Save current colours to Slot 1" stand directly under "Down colour", in that order, before the
	 * rule that opens the view group. The caption wears the look of the "Net worth chart" caption (the same font, grey and
	 * inset) and has no hover; each row is a check item in the menu's face - the shape that stands its words in the check
	 * items' column. (Indices only changed for part J: a rule above Up colour, and Slot 1 and its save row after
	 * Colour-blind, moved what follows; no assertion was weakened.)
	 */
	@Test
	public void h1_theSettingsMenuHasTheCaptionAndTheRowsDirectlyUnderDownColour() throws Exception
	{
		build(new Memory(), new FakePicker());
		load();
		onEdt(() ->
		{
			final JPopupMenu menu = panel.heroMenu();
			final List<Component> next = after(menu, 7, 7);
			assertSetsGroup(next.subList(0, 6));
			assertTrue("then the rule that opens the view group", next.get(6) instanceof JSeparator);
			assertSame(panel.livePricesItem(), menu.getComponent(15));
			assertEquals("Net worth chart", find(menu.getComponent(23), JLabel.class).getText());

			// The caption's look is the Net worth chart caption's, with no hover of its own.
			final JLabel caption = find(next.get(0), JLabel.class);
			final JLabel chart = find(menu.getComponent(23), JLabel.class);
			assertEquals(chart.getFont(), caption.getFont());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, caption.getForeground());
			assertEquals(chart.getForeground(), caption.getForeground());
			assertEquals(((javax.swing.JComponent) menu.getComponent(23)).getBorder().getBorderInsets(menu.getComponent(23)),
				((javax.swing.JComponent) next.get(0)).getBorder().getBorderInsets(next.get(0)));
			assertNull("no hover of its own", caption.getToolTipText());
		});
	}

	/**
	 * H1, the List options menu: the same caption and rows, directly under "Down colour" - which is the menu's last row
	 * until part H - after the alch tick, the rule and the two colour rows.
	 */
	@Test
	public void h1_theListOptionsMenuHasTheSameCaptionAndRowsDirectlyUnderDownColour() throws Exception
	{
		build(new Memory(), new FakePicker());
		load();
		onEdt(() ->
		{
			final JPopupMenu menu = panel.listMenu();
			assertEquals("the tick, a rule, two colour rows, the caption, four presets and the save row", 10,
				menu.getComponentCount());
			assertEquals(BankPriceMovementPanel.SHOW_ALCH_TEXT, ((JMenuItem) menu.getComponent(0)).getText());
			assertTrue(menu.getComponent(1) instanceof JSeparator);
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((JMenuItem) menu.getComponent(2)).getText());
			assertSetsGroup(after(menu, 3, 6));
		});
	}

	/**
	 * The caption and the rows, in order: Classic, 2h, Colour-blind, Slot 1 (preset rows, check items to Swing) and the plain
	 * row that saves into the slot. A click can select none of them: the tick is the panel's to set (part J).
	 */
	private static void assertSetsGroup(List<Component> group)
	{
		assertEquals(6, group.size());
		assertEquals("Colour presets", find(group.get(0), JLabel.class).getText());
		assertEquals(BankPriceMovementPanel.COLOUR_PRESETS_TEXT, find(group.get(0), JLabel.class).getText());
		assertFalse("the caption is no item", group.get(0) instanceof JMenuItem);
		final String[] words = {"Classic", "2h", "Colour-blind", "Slot 1", "Save current colours to Slot 1"};
		for (int i = 0; i < words.length; i++)
		{
			final Component c = group.get(i + 1);
			assertTrue(String.valueOf(c), c instanceof SetRow);
			assertTrue("a check item to Swing, whose words stand in the menu's check column", c instanceof JCheckBoxMenuItem);
			assertEquals(words[i], ((SetRow) c).getText());
			assertEquals("the menu's face", Widgets.sans(12), c.getFont());
			final JCheckBoxMenuItem item = (JCheckBoxMenuItem) c;
			final boolean before = item.isSelected();
			item.setSelected(!before);
			assertEquals(words[i] + ": and nothing a click's toggle does can change the tick", before, item.isSelected());
		}
	}

	// ---------------------------------------------------------------- H2: what a row paints

	/**
	 * H2: each row paints its set's two colours as two small squares at its right end - the rise's first, then the fall's -
	 * in the space one colour row's swatch takes (24 x 12, ending the same menu inset from the right): 11 px of colour, a 2 px
	 * gap, 11 px of colour, each in the swatch's 1 px frame, grey 110. Painted by the look and feel at the row's own height.
	 */
	@Test
	public void h2_eachRowPaintsItsTwoColoursUpThenDown() throws Exception
	{
		build(new Memory(), new FakePicker());
		load();
		final Color[][] pairs = {
			{ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.MOVE_DOWN_TEXT},
			{TWO_H_UP, TWO_H_DOWN},
			{BLIND_UP, BLIND_DOWN}};
		onEdt(() ->
		{
			for (final JPopupMenu menu : new JPopupMenu[]{panel.heroMenu(), panel.listMenu()})
			{
				int i = 0;
				for (final ColourSet set : ColourSet.values())
				{
					final SetRow row = setRow(menu, set);
					assertSquares(row, pairs[i][0], pairs[i][1]);
					assertEquals("the row's height is a colour row's", ((JMenuItem) menu.getComponent(menu == panel.heroMenu()
						? 6 : 2)).getPreferredSize().height, row.getPreferredSize().height);
					i++;
				}
				// The palette in force does not change a set's own colours: the squares are the SET's.
				panel.applyMoveColours(UP, DOWN);
				assertSquares(setRow(menu, ColourSet.TWO_H), TWO_H_UP, TWO_H_DOWN);
				panel.applyMoveColours(null, null);
			}
			// And the squares end where a colour row's swatch ends: the same right frame column in a row of the same width.
			final JMenuItem colourRow = (JMenuItem) panel.heroMenu().getComponent(6);
			assertEquals("the right end", rightFrameColumn(paint(colourRow, 200), colourRow),
				rightFrameColumn(paint(setRow(panel.heroMenu(), ColourSet.TWO_H), 200), colourRow));
		});
	}

	/** {@code row}'s squares: up in the left, down in the right, a gap between, and the frame round each. */
	private static void assertSquares(SetRow row, Color up, Color down)
	{
		final BufferedImage img = paint(row, 200);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		final int mid = y + BankPriceMovementPanel.SWATCH_HEIGHT / 2;
		final String name = row.getText();
		assertEquals(name + ": the rise's colour in the left square", up.getRGB(), img.getRGB(x + 5, mid));
		assertEquals(name + ": the fall's colour in the right square", down.getRGB(), img.getRGB(x + 18, mid));
		assertEquals(name + ": 1 px in, the colour already", up.getRGB(), img.getRGB(x + 1, y + 1));
		assertEquals(name + ": ...right square too", down.getRGB(), img.getRGB(x + 22, y + 10));
		// The frames: the outer left and right edges of the 24 px, and the two edges that face each other across the gap.
		assertEquals(name + ": left square, left edge", FRAME.getRGB(), img.getRGB(x, mid));
		assertEquals(name + ": left square, right edge", FRAME.getRGB(), img.getRGB(x + 10, mid));
		assertEquals(name + ": right square, left edge", FRAME.getRGB(), img.getRGB(x + 13, mid));
		assertEquals(name + ": right square, right edge", FRAME.getRGB(), img.getRGB(x + 23, mid));
		assertEquals(name + ": top", FRAME.getRGB(), img.getRGB(x + 5, y));
		assertEquals(name + ": bottom", FRAME.getRGB(), img.getRGB(x + 18, y + BankPriceMovementPanel.SWATCH_HEIGHT - 1));
		// The gap is the menu's own ground: neither colour and not the frame.
		for (final int gap : new int[]{x + 11, x + 12})
		{
			final int px = img.getRGB(gap, mid);
			assertNotEquals(name + ": the gap holds no up colour", up.getRGB(), px);
			assertNotEquals(name + ": the gap holds no down colour", down.getRGB(), px);
			assertNotEquals(name + ": the gap holds no frame", FRAME.getRGB(), px);
		}
		assertEquals("the squares fill the swatch's room and no more, so 24 px from the inset", 24,
			BankPriceMovementPanel.SWATCH_WIDTH);
	}

	/** The rightmost column holding the swatch frame's grey at the swatch's middle line - where a swatch ends. */
	private static int rightFrameColumn(BufferedImage img, JMenuItem row)
	{
		final Insets in = row.getInsets();
		final int y = in.top + (row.getPreferredSize().height - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		final int mid = y + BankPriceMovementPanel.SWATCH_HEIGHT / 2;
		for (int x = img.getWidth() - 1; x >= 0; x--)
		{
			if (img.getRGB(x, mid) == FRAME.getRGB())
			{
				return x;
			}
		}
		throw new AssertionError("no frame painted");
	}

	// ---------------------------------------------------------------- H3: clicking sets both colours

	/**
	 * H3: clicking "2h" stores #30E533 and #73BDFF - each key once, opaque, nothing else written, the service never asked -
	 * and draws both tabs in them at once: the Items row's rise and the card's fall, then the History tab's day rows, chart
	 * line and card (a record that rose), and a record that fell in the fall colour. Either menu's row does it, and each menu's
	 * Up / Down swatches show the new colours.
	 */
	@Test
	public void h3_clickingTwoHSetsBothColoursStoresThemAndDrawsBothTabs() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			assertEquals("a rise starts in the built-in green", ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
			assertEquals("a fall starts in the lifted red", Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());
			final int rebuilds = panel.rebuilds();

			setRow(panel.heroMenu(), ColourSet.TWO_H).doClick(0);

			assertEquals("the rise's key, once", Arrays.asList(TWO_H_UP), prefs.upSaves);
			assertEquals("the fall's key, once", Arrays.asList(TWO_H_DOWN), prefs.downSaves);
			assertEquals(255, prefs.upSaves.get(0).getAlpha());
			assertEquals("no other write", 0, prefs.otherWrites);
			assertEquals("Items: the rising row", TWO_H_UP, rowPercent());
			assertEquals("...its rail", TWO_H_UP.darker(), panel.rowPanels().get(0).railColor());
			assertEquals("Items: the card's fall", TWO_H_DOWN, panel.deltaLabel().getForeground());
			assertEquals(TWO_H_DOWN, panel.pctLabel().getForeground());
			assertTrue("the rows were drawn again from what the panel holds", panel.rebuilds() > rebuilds);
			assertEquals(TWO_H_UP, Widgets.move(1, Widgets.Kind.FIGURE));
			assertEquals(TWO_H_DOWN, Widgets.move(-1, Widgets.Kind.FIGURE));

			// Each menu's Up / Down swatches show it when next opened (they are read as they paint).
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(6), TWO_H_UP);
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(7), TWO_H_DOWN);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(2), TWO_H_UP);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(3), TWO_H_DOWN);

			// The History tab, which was not showing, is drawn in both on arrival.
			panel.setView(SidebarView.HISTORY);
			assertAll("every day row (the record rose)", TWO_H_UP, dayPercents());
			assertEquals("the chart's line follows the range in the up colour", TWO_H_UP, chartLine());
			assertEquals("the card's move: the record rose", TWO_H_UP, panel.deltaLabel().getForeground());
		});
		loadFalling();
		onEdt(() ->
		{
			assertAll("every day row (the record fell)", TWO_H_DOWN, dayPercents());
			assertEquals("the chart's line in the fall colour", TWO_H_DOWN, chartLine());
			assertEquals("the card", TWO_H_DOWN, panel.deltaLabel().getForeground());
		});
		verifyNoInteractions(service);
	}

	/** H3: "Colour-blind" likewise, clicked in the List options menu - the one palette, both keys, both tabs. */
	@Test
	public void h3_clickingColourBlindInTheListMenuDoesTheSame() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			setRow(panel.listMenu(), ColourSet.COLOUR_BLIND).doClick(0);

			assertEquals(Arrays.asList(BLIND_UP), prefs.upSaves);
			assertEquals(Arrays.asList(BLIND_DOWN), prefs.downSaves);
			assertEquals(0, prefs.otherWrites);
			assertEquals(BLIND_UP, rowPercent());
			assertEquals(BLIND_DOWN, panel.deltaLabel().getForeground());
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(6), BLIND_UP);
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(7), BLIND_DOWN);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(2), BLIND_UP);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(3), BLIND_DOWN);
			panel.setView(SidebarView.HISTORY);
			assertAll("every day row", BLIND_UP, dayPercents());
			assertEquals(BLIND_UP, chartLine());
		});
		verifyNoInteractions(service);
	}

	/**
	 * H3 / part H's third rule: a set is not a mode. Nothing remembers which one was clicked, and either colour can be
	 * changed after it: the picker's Up colour move takes the rise alone and leaves the fall in the set's colour.
	 */
	@Test
	public void h3_aSetIsNotAModeAndEitherColourChangesAfterIt() throws Exception
	{
		final Memory prefs = new Memory();
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		load();
		onEdt(() ->
		{
			setRow(panel.heroMenu(), ColourSet.TWO_H).doClick(0);
			((JMenuItem) panel.heroMenu().getComponent(6)).doClick(0);
			assertEquals("Up colour", picker.titles.get(0));
			picker.live.accept(UP);
			picker.done.accept(UP);
			assertEquals("the rise is the reader's own now", UP, rowPercent());
			assertEquals("the fall is still the set's", TWO_H_DOWN, panel.deltaLabel().getForeground());
			assertEquals(Arrays.asList(TWO_H_UP, UP), prefs.upSaves);
			assertEquals("the fall was written once, by the set", Arrays.asList(TWO_H_DOWN), prefs.downSaves);
			for (final Component c : panel.heroMenu().getComponents())
			{
				if (c instanceof SetRow)
				{
					assertFalse("no row shows a chosen state", ((SetRow) c).isSelected());
				}
			}
		});
	}

	/** H3: a stopped panel ignores a click, as it ignores a picker's close. */
	@Test
	public void h3_aStoppedPanelStoresNothing() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load();
		onEdt(() ->
		{
			final SetRow row = setRow(panel.heroMenu(), ColourSet.TWO_H);
			panel.stop();
			row.doClick(0);
			assertTrue("nothing stored", prefs.upSaves.isEmpty() && prefs.downSaves.isEmpty());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.FIGURE));
		});
	}

	// ---------------------------------------------------------------- H4: Classic puts the built-in colours back

	/**
	 * H4: clicking "Classic" after another set draws the built-in green and red again and leaves no palette in force -
	 * the same state "Reset to default" leaves: the two keys written as the built-in colours (which RuneLite reads back as
	 * the defaults), the built-in red MARK back and not the lifted red, and both menus' swatches showing them.
	 */
	@Test
	public void h4_classicAfterAnotherSetPutsTheBuiltInColoursBack() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		load();
		clearInvocations(service);
		onEdt(() ->
		{
			setRow(panel.heroMenu(), ColourSet.COLOUR_BLIND).doClick(0);
			assertEquals(BLIND_UP, rowPercent());
			assertEquals(BLIND_DOWN, panel.deltaLabel().getForeground());

			setRow(panel.listMenu(), ColourSet.CLASSIC).doClick(0);

			assertEquals("the rise is the built-in green again", ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
			assertEquals("the fall is the lifted red again", Widgets.MOVE_DOWN_TEXT, panel.deltaLabel().getForeground());
			assertEquals("the keys end on the built-in colours", Arrays.asList(BLIND_UP, ColorScheme.PROGRESS_COMPLETE_COLOR),
				prefs.upSaves);
			assertEquals(Arrays.asList(BLIND_DOWN, Widgets.MOVE_DOWN_TEXT), prefs.downSaves);
			assertEquals("no palette: the built-in red MARK, not the lifted red", ColorScheme.PROGRESS_ERROR_COLOR,
				Widgets.move(-1, Widgets.Kind.MARK));
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.MARK));
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(6), ColorScheme.PROGRESS_COMPLETE_COLOR);
			assertSwatchOf((JMenuItem) panel.heroMenu().getComponent(7), Widgets.MOVE_DOWN_TEXT);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(2), ColorScheme.PROGRESS_COMPLETE_COLOR);
			assertSwatchOf((JMenuItem) panel.listMenu().getComponent(3), Widgets.MOVE_DOWN_TEXT);
			panel.setView(SidebarView.HISTORY);
			assertAll("the day rows", ColorScheme.PROGRESS_COMPLETE_COLOR, dayPercents());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, chartLine());
			assertEquals("a reader on the built-in colours is where Classic leaves everyone: Classic's pair is the default pair",
				Widgets.MOVE_UP_DEFAULT, ColourSet.CLASSIC.up());
			assertEquals(Widgets.MOVE_DOWN_TEXT, ColourSet.CLASSIC.down());
		});
		verifyNoInteractions(service);
		verify(service, org.mockito.Mockito.never()).setFilter(any());
	}

	/** The swatch's middle is {@code colour} (a colour row paints it as it paints; the row is laid out at its own size). */
	private static void assertSwatchOf(JMenuItem row, Color colour)
	{
		final BufferedImage img = paint(row, row.getPreferredSize().width);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		assertEquals(row.getText() + ": the swatch's colour", colour.getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH / 2, y + BankPriceMovementPanel.SWATCH_HEIGHT / 2));
	}

	/** A guard on the test's own helpers: the sets' three names are the contract's. */
	@Test
	public void theThreeSetsAreNamedAndColouredAsTheContractSays() throws Exception
	{
		assertEquals(3, ColourSet.values().length);
		assertEquals(Arrays.asList("Classic", "2h", "Colour-blind"), Arrays.asList(ColourSet.CLASSIC.label(),
			ColourSet.TWO_H.label(), ColourSet.COLOUR_BLIND.label()));
		assertEquals(TWO_H_UP, ColourSet.TWO_H.up());
		assertEquals(TWO_H_DOWN, ColourSet.TWO_H.down());
		assertEquals(BLIND_UP, ColourSet.COLOUR_BLIND.up());
		assertEquals(BLIND_DOWN, ColourSet.COLOUR_BLIND.down());
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, ColourSet.CLASSIC.up());
		assertEquals(Widgets.MOVE_DOWN_TEXT, ColourSet.CLASSIC.down());
	}
}
