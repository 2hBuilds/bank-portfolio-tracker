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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part J (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the "Colour presets"
 * caption, the tick on the preset in use, Slot 1 with its save row, and the rule above the colour block - frozen tests J1,
 * J2 and J3. (J4, the two hidden keys, is {@link BankPriceMovementConfigTest}'s; J5, every pinned picture unchanged, is the
 * picture tests' own: no menu is in a picture.)
 *
 * <p>The palette is one static for the whole process, so every test clears it in {@code @After}.
 */
public class ColourPresetsTest
{
	/** What a reader picked: neither equals a preset's colour nor a built-in one. */
	private static final Color UP = new Color(10, 20, 200);
	private static final Color DOWN = new Color(200, 150, 0);
	private static final Color TWO_H_UP = new Color(0x30, 0xE5, 0x33);
	private static final Color TWO_H_DOWN = new Color(0x73, 0xBD, 0xFF);
	private static final Color BLIND_UP = new Color(0xFF, 0x9F, 0x43);
	private static final Color BLIND_DOWN = new Color(0x4D, 0xA3, 0xFF);
	private static final String[] TEXTS = {"Classic", "2h", "Colour-blind", "Slot 1", "Save current colours to Slot 1"};

	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config as a memory: the colours and the slot as stored, and every write, so a test sees all of them. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		Color up;
		@Nullable
		Color down;
		@Nullable
		Color slotUp;
		@Nullable
		Color slotDown;
		final List<Color> upSaves = new ArrayList<>();
		final List<Color> downSaves = new ArrayList<>();
		final List<Color> slotUpSaves = new ArrayList<>();
		final List<Color> slotDownSaves = new ArrayList<>();
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

		@Override
		public Color loadSlotUpColour()
		{
			return slotUp;
		}

		@Override
		public void saveSlotUpColour(Color colour)
		{
			slotUpSaves.add(colour);
		}

		@Override
		public Color loadSlotDownColour()
		{
			return slotDown;
		}

		@Override
		public void saveSlotDownColour(Color colour)
		{
			slotDownSaves.add(colour);
		}

		/** Stored OFF so the chart follows the range in the up and down colours; the stored chart colour is not under test. */
		@Override
		public Boolean loadSingleChartColour()
		{
			return Boolean.FALSE;
		}

		@Override
		public void saveSingleChartColour(boolean single)
		{
			otherWrites++;
		}

		@Override
		public void saveChartColour(Color colour)
		{
			otherWrites++;
		}
	}

	/** A colour picker that shows nothing and keeps the request, so a test moves it by hand. */
	private static final class FakePicker implements ColourPicker
	{
		Consumer<Color> live;
		Consumer<Color> done;

		@Override
		public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
		{
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
		final PriceService.Status s = status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	private static SetRow row(JPopupMenu menu, String text)
	{
		for (final Component c : menu.getComponents())
		{
			if (c instanceof SetRow && ((SetRow) c).getText().equals(text))
			{
				return (SetRow) c;
			}
		}
		throw new AssertionError("no row \"" + text + "\" in " + Arrays.asList(menu.getComponents()));
	}

	/** The words of the preset rows that carry a tick in {@code menu}, in menu order. */
	private static List<String> ticked(JPopupMenu menu)
	{
		final List<String> out = new ArrayList<>();
		for (final Component c : menu.getComponents())
		{
			if (c instanceof SetRow && ((SetRow) c).isSelected())
			{
				out.add(((SetRow) c).getText());
			}
		}
		return out;
	}

	/** Both menus tick exactly {@code expected} (nothing for null) - they always agree. */
	private void assertTicked(String why, @Nullable String expected)
	{
		final List<String> want = expected == null ? Collections.<String>emptyList() : Arrays.asList(expected);
		assertEquals(why + " (settings menu)", want, ticked(panel.heroMenu()));
		assertEquals(why + " (list options menu)", want, ticked(panel.listMenu()));
	}

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

	/** The colours of {@code row}'s two squares as painted: up's middle and down's middle. */
	private static List<Color> squares(JMenuItem row)
	{
		final BufferedImage img = paint(row, 200);
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		final int mid = y + BankPriceMovementPanel.SWATCH_HEIGHT / 2;
		return Arrays.asList(new Color(img.getRGB(x + 5, mid)), new Color(img.getRGB(x + 18, mid)));
	}

	private Color rowPercent()
	{
		return panel.rowPanels().get(0).changeColor();
	}

	// ---------------------------------------------------------------- J1: the caption, the rows, the rule

	/**
	 * J1: both menus read "Colour presets" and hold, in order under Down colour, the caption, Classic, 2h, Colour-blind, Slot 1
	 * and "Save current colours to Slot 1"; the settings menu has a rule directly above Up colour, and no other change above it.
	 */
	@Test
	public void j1_bothMenusReadColourPresetsAndHoldTheFourPresetsAndTheSaveRowUnderDownColour() throws Exception
	{
		build(new Memory(), new FakePicker());
		onEdt(() ->
		{
			assertEquals("Colour presets", BankPriceMovementPanel.COLOUR_PRESETS_TEXT);
			assertEquals("Slot 1", BankPriceMovementPanel.SLOT_TEXT);
			assertEquals("Save current colours to Slot 1", BankPriceMovementPanel.SAVE_SLOT_TEXT);
			final Component[] settings = panel.heroMenu().getComponents();
			assertTrue("a rule directly above Up colour", settings[5] instanceof JSeparator);
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((JMenuItem) settings[6]).getText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TEXT, ((JMenuItem) settings[7]).getText());
			assertGroup(Arrays.asList(settings).subList(8, 14));
			assertTrue("then the rule that opens the view group", settings[14] instanceof JSeparator);

			final Component[] list = panel.listMenu().getComponents();
			assertTrue("the list options menu had its rule already", list[1] instanceof JSeparator);
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((JMenuItem) list[2]).getText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TEXT, ((JMenuItem) list[3]).getText());
			assertGroup(Arrays.asList(list).subList(4, 10));
			assertEquals("and nothing after the save row", 10, list.length);
		});
	}

	private static void assertGroup(List<Component> group)
	{
		assertEquals(6, group.size());
		assertEquals("Colour presets", find(group.get(0), JLabel.class).getText());
		assertFalse("the caption is no item", group.get(0) instanceof JMenuItem);
		for (int i = 0; i < TEXTS.length; i++)
		{
			final Component c = group.get(i + 1);
			assertTrue(String.valueOf(c), c instanceof SetRow);
			assertTrue("a check item to Swing, so its words stand in the check column", c instanceof JCheckBoxMenuItem);
			assertEquals(TEXTS[i], ((SetRow) c).getText());
			assertEquals(Widgets.sans(12), c.getFont());
		}
	}

	/** J1: the save row carries no swatch - the room where the other rows' squares are is the menu's own ground. */
	@Test
	public void j1_theSaveRowHasNoSwatchAndSlotOneHasTwoSquares() throws Exception
	{
		build(new Memory(), new FakePicker());
		onEdt(() ->
		{
			for (final JPopupMenu menu : new JPopupMenu[]{panel.heroMenu(), panel.listMenu()})
			{
				final BufferedImage img = paint(row(menu, "Save current colours to Slot 1"), 300);
				final int from = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
				final int mid = img.getHeight() / 2;
				final int ground = img.getRGB(from, mid);
				for (int x = from; x < from + BankPriceMovementPanel.SWATCH_WIDTH; x++)
				{
					assertEquals("no square at x=" + x, ground, img.getRGB(x, mid));
				}
				assertEquals("Slot 1 has the squares of the pair it holds", Arrays.asList(
					Widgets.MOVE_UP_DEFAULT, Widgets.MOVE_DOWN_TEXT), squares(row(menu, "Slot 1")));
			}
		});
	}

	// ---------------------------------------------------------------- J2: the tick follows the colours

	/**
	 * J2: by default Classic is ticked and no other row; after 2h, 2h alone; after a picked colour matching no row, none -
	 * live and done; after Reset, Classic; a ConfigChanged from outside moves it too. Both menus agree each time.
	 */
	@Test
	public void j2_theTickIsOnThePresetInUseInBothMenusAfterEveryRoadThatChangesTheColours() throws Exception
	{
		final Memory prefs = new Memory();
		final FakePicker picker = new FakePicker();
		build(prefs, picker);
		onEdt(() ->
		{
			assertTicked("by default", "Classic");

			row(panel.heroMenu(), "2h").doClick(0);
			assertTicked("after 2h", "2h");

			row(panel.listMenu(), "Colour-blind").doClick(0);
			assertTicked("after Colour-blind, from the other menu", "Colour-blind");

			// The picker: live and done, both ways.
			((JMenuItem) panel.heroMenu().getComponent(6)).doClick(0);
			picker.live.accept(UP);
			assertTicked("while the picker moves the rise to a colour no preset has", null);
			picker.live.accept(BLIND_UP);
			assertTicked("and back to the preset's own colour", "Colour-blind");
			picker.live.accept(UP);
			picker.done.accept(UP);
			assertTicked("after the picker closed on a colour no preset has", null);

			// A press on a preset lands on it again; a press on Classic is Classic.
			row(panel.heroMenu(), "Classic").doClick(0);
			assertTicked("after Classic", "Classic");
			panel.applyMoveColours(TWO_H_UP, TWO_H_DOWN);
			assertTicked("after a ConfigChanged from outside that spells 2h", "2h");
			panel.applyMoveColours(UP, DOWN);
			assertTicked("after a ConfigChanged that matches none", null);

			// Reset to default.
			panel.resetPresetsButton().doClick(0);
			assertTicked("after Reset to default", "Classic");
			assertEquals("the colours are the built-in ones", ColorScheme.PROGRESS_COMPLETE_COLOR,
				Widgets.move(1, Widgets.Kind.FIGURE));
		});
	}

	/**
	 * J2/J-3: the tick is the colours', never the click's - a click's toggle cannot tick or untick a row, and the panel sets
	 * the tick after the press: the pressed preset is ticked, the others are not, and no row is ticked twice.
	 */
	@Test
	public void j2_aClickNeverTicksByItselfAndOnlyOneRowIsEverTicked() throws Exception
	{
		build(new Memory(), new FakePicker());
		onEdt(() ->
		{
			for (final String text : TEXTS)
			{
				final SetRow row = row(panel.heroMenu(), text);
				final boolean before = row.isSelected();
				row.setSelected(!before);
				assertEquals(text + ": setSelected from a click's toggle changes nothing", before, row.isSelected());
			}
			final SetRow save = row(panel.heroMenu(), "Save current colours to Slot 1");
			save.doClick(0);
			assertFalse("the save row is never ticked", save.isSelected());
			for (final String text : new String[]{"2h", "Colour-blind", "Classic"})
			{
				row(panel.heroMenu(), text).doClick(0);
				assertEquals(Arrays.asList(text), ticked(panel.heroMenu()));
			}
		});
	}

	/**
	 * J-3: a pair that is in the menu twice is ticked once, on the FIRST row in the order Classic, 2h, Colour-blind, Slot 1 -
	 * Slot 1 saved while Colour-blind is in use is the same pair, and Colour-blind carries the tick. Slot 1 carries it when
	 * its pair matches no row before it.
	 */
	@Test
	public void j2_aPairInTheMenuTwiceIsTickedOnTheFirstRowAndSlotOneCarriesItWhenItIsAlone() throws Exception
	{
		build(new Memory(), new FakePicker());
		onEdt(() ->
		{
			row(panel.heroMenu(), "Colour-blind").doClick(0);
			row(panel.heroMenu(), "Save current colours to Slot 1").doClick(0);
			assertTicked("Slot 1 now spells Colour-blind: the first row has it", "Colour-blind");
			row(panel.listMenu(), "Slot 1").doClick(0);
			assertTicked("pressing Slot 1 applies the same pair", "Colour-blind");

			panel.applyMoveColours(UP, DOWN);
			row(panel.listMenu(), "Save current colours to Slot 1").doClick(0);
			assertTicked("Slot 1 now spells the reader's own pair, and the pair is in use", "Slot 1");
			row(panel.heroMenu(), "Classic").doClick(0);
			assertTicked("Classic again", "Classic");
			row(panel.heroMenu(), "Slot 1").doClick(0);
			assertTicked("Slot 1 alone", "Slot 1");
		});
	}

	/** J2: a panel built on stored colours opens with the tick already on the preset they spell - and on none for others. */
	@Test
	public void j2_aPanelBuiltOnStoredColoursOpensWithTheRightTick() throws Exception
	{
		final Memory twoH = new Memory();
		twoH.up = TWO_H_UP;
		twoH.down = TWO_H_DOWN;
		build(twoH, new FakePicker());
		onEdt(() -> assertTicked("2h stored", "2h"));
		onEdt(() -> panel.stop());

		final Memory own = new Memory();
		own.up = UP;
		own.down = DOWN;
		build(own, new FakePicker());
		onEdt(() -> assertTicked("a pair no preset has", null));
		onEdt(() -> panel.stop());

		final Memory slot = new Memory();
		slot.up = UP;
		slot.down = DOWN;
		slot.slotUp = UP;
		slot.slotDown = DOWN;
		build(slot, new FakePicker());
		onEdt(() -> assertTicked("the stored slot's pair", "Slot 1"));
	}

	// ---------------------------------------------------------------- J3: Slot 1

	/**
	 * J3: Slot 1 starts as Classic's pair; "Save current colours to Slot 1" stores the colours in use into both keys once -
	 * nothing else written, the service never asked, the colours in use unchanged - and Slot 1's squares show them; pressing
	 * Slot 1 later applies them (both move-colour keys stored as a preset does); Reset to default leaves the slot's keys alone.
	 */
	@Test
	public void j3_saveStoresTheColoursInUseIntoTheSlotAndPressingSlotOneAppliesThemAndResetLeavesThemAlone() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		clearInvocations(service);
		onEdt(() ->
		{
			assertEquals("Slot 1 starts as Classic's pair", Arrays.asList(Widgets.MOVE_UP_DEFAULT, Widgets.MOVE_DOWN_TEXT),
				squares(row(panel.heroMenu(), "Slot 1")));
			assertTrue("nothing is written by building", prefs.slotUpSaves.isEmpty() && prefs.slotDownSaves.isEmpty());

			panel.applyMoveColours(UP, DOWN);
			row(panel.heroMenu(), "Save current colours to Slot 1").doClick(0);
			assertEquals("the rise's slot key, once", Arrays.asList(UP), prefs.slotUpSaves);
			assertEquals("the fall's slot key, once", Arrays.asList(DOWN), prefs.slotDownSaves);
			assertEquals(255, prefs.slotUpSaves.get(0).getAlpha());
			assertTrue("the colours in use were not written", prefs.upSaves.isEmpty() && prefs.downSaves.isEmpty());
			assertEquals("no other write", 0, prefs.otherWrites);
			assertEquals("Slot 1 shows the saved pair in both menus", Arrays.asList(UP, DOWN),
				squares(row(panel.heroMenu(), "Slot 1")));
			assertEquals(Arrays.asList(UP, DOWN), squares(row(panel.listMenu(), "Slot 1")));
			assertEquals("the colours in use did not change", UP, rowPercent());

			// Away from the slot's pair, then back by pressing Slot 1.
			row(panel.listMenu(), "2h").doClick(0);
			assertEquals(TWO_H_UP, rowPercent());
			prefs.upSaves.clear();
			prefs.downSaves.clear();
			row(panel.heroMenu(), "Slot 1").doClick(0);
			assertEquals("the slot's rise is drawn", UP, rowPercent());
			assertEquals("and its fall, on the card", DOWN, panel.deltaLabel().getForeground());
			assertEquals("both move-colour keys stored, as a preset does", Arrays.asList(UP), prefs.upSaves);
			assertEquals(Arrays.asList(DOWN), prefs.downSaves);
			assertEquals("pressing a slot writes no slot key", 1, prefs.slotUpSaves.size());

			// Reset to default leaves the slot alone.
			panel.resetPresetsButton().doClick(0);
			assertEquals("Reset wrote no slot key", 1, prefs.slotUpSaves.size());
			assertEquals(1, prefs.slotDownSaves.size());
			assertEquals("and Slot 1 still shows the saved pair", Arrays.asList(UP, DOWN),
				squares(row(panel.heroMenu(), "Slot 1")));
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, rowPercent());
		});
		verifyNoInteractions(service);
	}

	/** J3: a panel built on a stored slot shows it; the config's own change (a hand edit) is shown and written back nowhere. */
	@Test
	public void j3_aStoredSlotIsShownAndAChangeFromOutsideUpdatesItWithoutWritingAnything() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.slotUp = BLIND_UP;
		prefs.slotDown = DOWN;
		build(prefs, new FakePicker());
		clearInvocations(service);
		onEdt(() ->
		{
			assertEquals(Arrays.asList(BLIND_UP, DOWN), squares(row(panel.heroMenu(), "Slot 1")));
			assertEquals(Arrays.asList(BLIND_UP, DOWN), squares(row(panel.listMenu(), "Slot 1")));

			panel.applySlotColours(TWO_H_UP, null);
			assertEquals("a null colour is Classic's", Arrays.asList(TWO_H_UP, Widgets.MOVE_DOWN_TEXT),
				squares(row(panel.heroMenu(), "Slot 1")));
			// The slot now holds (2h up, Classic down): the colours in use are Classic's, which is not it.
			assertTicked("the colours in use still spell Classic", "Classic");
			panel.applySlotColours(Widgets.MOVE_UP_DEFAULT, Widgets.MOVE_DOWN_TEXT);
			assertTicked("Classic is first, the slot's equal pair is not ticked as well", "Classic");

			assertTrue("nothing written", prefs.slotUpSaves.isEmpty() && prefs.slotDownSaves.isEmpty()
				&& prefs.upSaves.isEmpty() && prefs.downSaves.isEmpty() && prefs.otherWrites == 0);
		});
		verifyNoInteractions(service);
	}

	/** J3: a stopped panel stores nothing, as it ignores a picker's close. */
	@Test
	public void j3_aStoppedPanelStoresNothing() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new FakePicker());
		onEdt(() ->
		{
			final SetRow save = row(panel.heroMenu(), "Save current colours to Slot 1");
			final SetRow slot = row(panel.heroMenu(), "Slot 1");
			panel.stop();
			save.doClick(0);
			slot.doClick(0);
			panel.applySlotColours(UP, DOWN);
			assertTrue(prefs.slotUpSaves.isEmpty() && prefs.slotDownSaves.isEmpty() && prefs.upSaves.isEmpty());
		});
	}

	/** J3: the percentage of a rising row and the fall shown on the card are drawn in the colours Slot 1 applied. */
	@Test
	public void j3_pressingSlotOneDrawsBothTabsInItsPair() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.slotUp = UP;
		prefs.slotDown = DOWN;
		build(prefs, new FakePicker());
		onEdt(() ->
		{
			row(panel.listMenu(), "Slot 1").doClick(0);
			assertEquals(UP, rowPercent());
			assertEquals(Arrays.asList(UP), prefs.upSaves);
			assertEquals(Arrays.asList(DOWN), prefs.downSaves);
			assertEquals(UP, Widgets.move(1, Widgets.Kind.FIGURE));
			assertEquals(DOWN, Widgets.move(-1, Widgets.Kind.FIGURE));
			assertNull("no hover text on the preset rows", row(panel.heroMenu(), "Slot 1").getToolTipText());
		});
	}
}
