package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Test;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.TODAY;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.find;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static com.bankpricemovement.SidebarViewPanelTest.series;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release 1.2.1 (contract {@code docs/handoff/contract-1.2.1-change-size-and-brightness-2026-10-24.md}, S1, S3 and S4): the List
 * options menu's five new rows - two captions, two size bars and a brightness box - and what pressing them does. The five
 * stand directly under "Down colour", in order; a press on an unlit cell of a bar lights it, stores it and redraws the rows at
 * once, and never asks the service for anything; the box opens on no focus, saves on Enter, on focus lost and when the menu
 * closes, and goes red on anything that is not a whole number from 20 to 100; "Reset to default" restores all three; the
 * hovers are one line each. The dev bridge's {@code change=} verb presses the same road ({@code pressChangeText}); its
 * reading of the words is pinned in the test source set.
 *
 * <p>The move palette is one static for the whole process, so every test clears it in {@code @After}.
 */
public class ChangeSizeMenuTest
{
	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;
	private Memory prefs;

	/** The config as a memory: the three change settings as stored, and every write of them, in order. */
	private static final class Memory extends PrefsDefaults
	{
		@Nullable
		ChangeText stored;
		final List<ChangeText> saves = new ArrayList<>();
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
		public void saveOptions(ViewOptions options)
		{
			otherWrites++;
		}

		@Override
		public void savePresets(BandPresets presets)
		{
			otherWrites++;
		}

		@Override
		public ChangeText loadChangeText()
		{
			return stored;
		}

		@Override
		public void saveChangeText(ChangeText text)
		{
			saves.add(text);
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

	private void build(@Nullable ChangeText stored) throws Exception
	{
		build(stored, rows());
	}

	private void build(@Nullable ChangeText stored, List<MovementRow> published) throws Exception
	{
		prefs = new Memory();
		prefs.stored = stored;
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			panel.setClock(() -> NOW);
		});
		final org.mockito.ArgumentCaptor<PriceService.Listener> captor =
			org.mockito.ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
		final PriceService.Status s = status(series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(published, s));
	}

	/** Three rows that moved: two rose and one fell, each a stack of several so line 3 prints a gp figure. */
	private static List<MovementRow> rows()
	{
		return Arrays.asList(
			new MovementRow(1, "Abyssal whip", 12, false, 1_520_000L, 1_500_000L, 20_000L, 1.3d, 18_240_000L, null),
			new MovementRow(2, "Dragon claws", 7, false, 41_480_000L, 40_050_000L, 1_430_000L, 3.5d, 290_360_000L, null),
			new MovementRow(3, "Shark", 1_000, true, 890L, 895L, -5L, -0.5d, 890_000L, null));
	}

	private Component[] menu()
	{
		return panel.listMenu().getComponents();
	}

	/** The brightness row (the menu's seventh component) and the box in it. */
	private JPanel brightnessRow()
	{
		return (JPanel) menu()[6];
	}

	private Widgets.PlaceholderField box()
	{
		return find(brightnessRow(), Widgets.PlaceholderField.class);
	}

	private SizeBar gpBar()
	{
		return find(menu()[5], SizeBar.class);
	}

	private SizeBar pctBar()
	{
		return find(menu()[8], SizeBar.class);
	}

	/** The bar's cell {@code i}: Small, Normal, Large, Largest. */
	private static JLabel cell(SizeBar bar, int i)
	{
		return (JLabel) bar.getComponent(i);
	}

	private static boolean lit(JLabel cell)
	{
		return Widgets.sansBold(11).equals(cell.getFont()) && Color.WHITE.equals(cell.getForeground())
			&& SizeBar.LIT_GROUND.equals(cell.getBackground());
	}

	/** The index of the lit cell of {@code bar}: the one wearing the lit look, and only one may. */
	private static int litIndex(SizeBar bar)
	{
		int at = -1;
		for (int i = 0; i < 4; i++)
		{
			if (lit(cell(bar, i)))
			{
				assertEquals("only one cell is lit", -1, at);
				at = i;
			}
			else
			{
				assertEquals(Widgets.sans(11), cell(bar, i).getFont());
				assertEquals(ColorScheme.LIGHT_GRAY_COLOR, cell(bar, i).getForeground());
				assertEquals(ColorScheme.DARKER_GRAY_COLOR, cell(bar, i).getBackground());
			}
		}
		return at;
	}

	// ----------------------------------------------------------------------------------------------- the menu

	/**
	 * S1: directly under "Down colour" and before "Colour presets", in this order - the caption "GP change size", a bar, the
	 * row "Brightness [box] %", the caption "Percent change size", a bar - the captions in the colour presets' look (the 12 px
	 * face in light grey, the same inset), the bars of four cells Small, Normal, Large, Largest with a 1 px frame, the lit one
	 * Normal on a fresh profile. The menu's first four components and the presets after them are where they were.
	 */
	@Test
	public void s1_theFiveRowsStandInOrderUnderDownColour() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			final Component[] items = menu();
			assertEquals("the tick, a rule, two colour rows, five change rows, the caption, four presets and the save row", 15,
				items.length);
			assertEquals(BankPriceMovementPanel.SHOW_ALCH_TEXT, ((JMenuItem) items[0]).getText());
			assertTrue(items[1] instanceof JSeparator);
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((JMenuItem) items[2]).getText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TEXT, ((JMenuItem) items[3]).getText());

			assertEquals("GP change size", BankPriceMovementPanel.GP_SIZE_TEXT);
			assertEquals("Percent change size", BankPriceMovementPanel.PCT_SIZE_TEXT);
			assertEquals("Opacity", BankPriceMovementPanel.OPACITY_TEXT);
			final JLabel gpCaption = find(items[4], JLabel.class);
			assertEquals("GP change size", gpCaption.getText());
			assertEquals(Widgets.sans(12), gpCaption.getFont());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, gpCaption.getForeground());
			assertFalse("the caption is no item", items[4] instanceof JMenuItem);
			assertTrue(items[5] instanceof JPanel && find(items[5], SizeBar.class) != null);
			assertTrue("the brightness row", items[6] instanceof JPanel);
			final JLabel pctCaption = find(items[7], JLabel.class);
			assertEquals("Percent change size", pctCaption.getText());
			assertEquals(gpCaption.getFont(), pctCaption.getFont());
			assertEquals(gpCaption.getForeground(), pctCaption.getForeground());
			assertEquals("the same inset as the colour presets' caption",
				((javax.swing.JComponent) items[9]).getBorder().getBorderInsets(items[9]),
				((javax.swing.JComponent) items[4]).getBorder().getBorderInsets(items[4]));
			assertTrue(items[8] instanceof JPanel && find(items[8], SizeBar.class) != null);
			assertEquals("Colour presets", find(items[9], JLabel.class).getText());
			assertTrue(items[10] instanceof SetRow && items[14] instanceof SetRow);

			// The row: the word, the box, and the percent sign - in that order.
			final JPanel row = (JPanel) items[6];
			final List<String> words = new ArrayList<>();
			for (Component c : row.getComponents())
			{
				if (c instanceof JLabel)
				{
					words.add(((JLabel) c).getText());
				}
			}
			assertEquals(Arrays.asList("Opacity", "%"), words);
			assertEquals(ChangeSettings.FIELD_WIDTH, box().getPreferredSize().width);
			assertEquals("the default opacity since release 1.2.2", "69", box().getText());
			assertEquals("the same box as the preset price boxes", box().getTextField().getFont(), panel.presetField(0).getTextField().getFont());
			assertFalse("a fresh box is not red", PanelState.isMarkedInvalid(box()));

			// The bars: four cells in order, a 1 px frame, the lit look on Normal and the unlit look on the rest.
			for (SizeBar bar : Arrays.asList(gpBar(), pctBar()))
			{
				assertEquals(4, bar.getComponentCount());
				assertEquals(Arrays.asList("Small", "Normal", "Large", "Largest"), Arrays.asList(
					cell(bar, 0).getText(), cell(bar, 1).getText(), cell(bar, 2).getText(), cell(bar, 3).getText()));
				assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, bar.getBackground());
				assertEquals(1, bar.getBorder().getBorderInsets(bar).left);
				assertEquals(1, litIndex(bar));
			}
			// Four cells share the width equally (the frame and the dividers apart): 1 px at most between the widest and narrowest.
			gpBar().setSize(200, 21);
			LookRenderer.layoutTree(gpBar());
			int least = Integer.MAX_VALUE;
			int most = 0;
			for (int i = 0; i < 4; i++)
			{
				least = Math.min(least, cell(gpBar(), i).getWidth());
				most = Math.max(most, cell(gpBar(), i).getWidth());
			}
			assertTrue("equal cells: " + least + ".." + most, most - least <= 1);
		});
	}

	/** A profile that stored sizes and a brightness opens the menu on them: the lit cells and the box say so. */
	@Test
	public void theMenuIsBuiltFromWhatIsStored() throws Exception
	{
		build(ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 80));
		onEdt(() ->
		{
			assertEquals(3, litIndex(gpBar()));
			assertEquals(0, litIndex(pctBar()));
			assertEquals("80", box().getText());
			assertEquals(ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 80), panel.changeText());
			// ...and the rows were drawn that way from the first publish.
			assertEquals(Widgets.sans(13), gpFont(0));
			assertEquals(Widgets.sansBold(12), pctFont(0));
		});
	}

	private Font gpFont(int row)
	{
		return gpLabel(panel.rowPanels().get(row)).getFont();
	}

	private Font pctFont(int row)
	{
		return ChangeSizeRowsTest.pct(panel.rowPanels().get(row)).getFont();
	}

	// ----------------------------------------------------------------------------------------- a bar's press

	/**
	 * S3: a press on an unlit cell lights it, writes the setting once and redraws the rows at once - the gp bar changes the gp
	 * figures' face and the percentage bar the percentage's - without asking the service for anything and without touching the
	 * other bar; a press on the lit cell does nothing at all, and a right-button press is no press.
	 */
	@Test
	public void s3_pressingACellLightsItStoresItAndRedrawsTheRows() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			assertEquals(Widgets.sans(10), gpFont(0));
			assertEquals(Widgets.sansBold(15), pctFont(0));
			press(cell(gpBar(), 2), java.awt.event.MouseEvent.BUTTON1);
			assertEquals("Large is lit", 2, litIndex(gpBar()));
			assertEquals("the other bar is where it was", 1, litIndex(pctBar()));
			assertEquals(Collections.singletonList(ChangeText.DEFAULT.withGp(ChangeSize.LARGE)), prefs.saves);
			assertEquals(ChangeText.DEFAULT.withGp(ChangeSize.LARGE), panel.changeText());
			assertEquals("the rows are redrawn at once", Widgets.sans(12), gpFont(0));
			assertEquals(Widgets.sans(12), gpFont(2));
			assertEquals("the percentage keeps its size", Widgets.sansBold(15), pctFont(0));

			// The lit cell again: nothing, not even a write.
			press(cell(gpBar(), 2), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(1, prefs.saves.size());
			// A right press is no press.
			press(cell(gpBar(), 3), java.awt.event.MouseEvent.BUTTON3);
			assertEquals(2, litIndex(gpBar()));
			assertEquals(1, prefs.saves.size());

			press(cell(pctBar(), 3), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(3, litIndex(pctBar()));
			assertEquals(2, litIndex(gpBar()));
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 69), prefs.saves.get(1));
			assertEquals(Widgets.sansBold(18), pctFont(1));
			press(cell(pctBar(), 0), java.awt.event.MouseEvent.BUTTON1);
			press(cell(gpBar(), 0), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(Widgets.sans(8), gpFont(0));
			assertEquals(Widgets.sansBold(12), pctFont(0));
			assertEquals(4, prefs.saves.size());
			assertEquals("nothing else was written", 0, prefs.otherWrites);
		});
		verify(service, never()).setOptions(any());
		verify(service, never()).setFilter(any());
		verify(service, never()).refreshNow(false);
	}

	/**
	 * A press comes back through the config only when it was not the menu's own write: the plugin's {@code ConfigChanged} for a
	 * change setting reaches {@code applyChangeText}, which redraws the bars, the box and the rows and writes NOTHING back.
	 */
	@Test
	public void aChangeFromOutsideRedrawsEverythingAndWritesNothingBack() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			final ChangeText from = ChangeText.of(ChangeSize.LARGE, ChangeSize.SMALL, 90);
			panel.applyChangeText(from);
			assertEquals(2, litIndex(gpBar()));
			assertEquals(0, litIndex(pctBar()));
			assertEquals("90", box().getText());
			assertEquals(Widgets.sans(12), gpFont(0));
			assertEquals(Widgets.sansBold(12), pctFont(0));
			assertEquals("the same again is no redraw: the rows are the same objects", panel.rowPanels().get(0),
				redrawlessRow(from));
			panel.applyChangeText(null);
			assertEquals(ChangeText.DEFAULT, panel.changeText());
			assertEquals(1, litIndex(gpBar()));
			assertEquals("69", box().getText());
			assertEquals("written back: nothing", 0, prefs.saves.size());
		});
	}

	/** The first row after {@code applyChangeText(same)} - which must not have rebuilt the page. */
	private MovementRowPanel redrawlessRow(ChangeText same)
	{
		final MovementRowPanel before = panel.rowPanels().get(0);
		panel.applyChangeText(same);
		final MovementRowPanel after = panel.rowPanels().get(0);
		assertSame(before, after);
		return after;
	}

	/** The rows are redrawn when the Net Worth History tab shows too: they wait flagged and are drawn on arrival. */
	@Test
	public void theOtherTabsRowsAreDrawnOnArrival() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			panel.pressView(SidebarView.HISTORY);
			final int rebuilds = panel.rebuilds();
			panel.pressChangeText(ChangeText.DEFAULT.withPct(ChangeSize.LARGE));
			assertEquals("no tab nobody is looking at is rebuilt", rebuilds, panel.rebuilds());
			panel.pressView(SidebarView.ITEMS);
			assertEquals(Widgets.sansBold(17), pctFont(0));
		});
	}

	// ---------------------------------------------------------------------------------------- the brightness box

	/**
	 * S3: opening the menu targets NO box - the keyboard is asked for the brightness ROW, which is focusable, and never for the
	 * box in it, and the box owns no focus - and the menu is opened on the value in force with the red cleared (a refused edit
	 * is a question that was answered).
	 */
	@Test
	public void s3_theMenuOpensOnNoFocusAndOnTheValueInForce() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			final Component target = panel.listMenuFocusTarget();
			assertSame("the row, which the keyboard is parked on", menu()[6], target);
			assertTrue("focusable, or the popup would give the box the keyboard", target.isFocusable());
			assertFalse("not the box", target == box() || target == box().getTextField());
			assertFalse("the box has no focus", box().getTextField().isFocusOwner());
			box().setText("abc");
			box().getTextField().postActionEvent();
			assertTrue("red", PanelState.isMarkedInvalid(box()));
			for (PopupMenuListener l : panel.listMenu().getPopupMenuListeners())
			{
				l.popupMenuWillBecomeVisible(new PopupMenuEvent(panel.listMenu()));
			}
			assertEquals("the value in force again", "69", box().getText());
			assertFalse("and not red", PanelState.isMarkedInvalid(box()));
		});
	}

	/**
	 * S3: anything that is not a whole number from 20 to 100 turns the box red and saves nothing - words, 19, 101, an empty box,
	 * a sign, a decimal, an exponent, four digits, 0 and a negative - and the rows stay as they were; a number in range saves,
	 * clears the red, and redraws the rows' figures in the new brightness.
	 */
	@Test
	public void s3_theBoxTakesWholeNumbersFromTwentyToOneHundredAndTurnsRedOnAnythingElse() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			final Color before = gpLabel(panel.rowPanels().get(0)).getForeground();
			for (String bad : new String[]{"abc", "19", "101", "", "  ", "5", "150", "0", "-30", "+30", "55.5", "1e2", "1000", "0x40", "20 %"})
			{
				box().setText(bad);
				box().getTextField().postActionEvent();
				assertTrue("'" + bad + "' is refused in red", PanelState.isMarkedInvalid(box()));
				assertEquals("'" + bad + "' saves nothing", 0, prefs.saves.size());
				assertEquals(ChangeText.DEFAULT, panel.changeText());
				assertEquals(before, gpLabel(panel.rowPanels().get(0)).getForeground());
			}
			for (String good : new String[]{"20", "100", " 70 ", "055", "99"})
			{
				prefs.saves.clear();
				box().setText(good);
				box().getTextField().postActionEvent();
				assertFalse("'" + good + "' is not red", PanelState.isMarkedInvalid(box()));
				final int want = Integer.parseInt(good.trim());
				assertEquals(want, panel.changeText().brightness());
				assertEquals(1, prefs.saves.size());
				assertEquals(ChangeText.DEFAULT.withBrightness(want), prefs.saves.get(0));
				assertEquals(Widgets.quietAt(1, want), gpLabel(panel.rowPanels().get(0)).getForeground());
				assertEquals("the box prints the value in force", Integer.toString(want), box().getText());
			}
			// The same value again writes nothing more.
			prefs.saves.clear();
			box().setText("99");
			box().getTextField().postActionEvent();
			assertEquals(0, prefs.saves.size());
			// 100 is the percentage's colour exactly.
			box().setText("100");
			box().getTextField().postActionEvent();
			assertEquals(panel.rowPanels().get(0).changeColor(), gpLabel(panel.rowPanels().get(0)).getForeground());
		});
	}

	/**
	 * S3: the value saves on Enter (above), on focus lost - a PERMANENT loss; a temporary one is the window going away and
	 * commits nothing - and when the menu closes, which is also where a press outside the menu lands, the popup's own grabber
	 * cancelling it before the press goes anywhere else.
	 */
	@Test
	public void s3_theBoxSavesOnFocusLostAndWhenTheMenuCloses() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			box().setText("40");
			box().getTextField().getFocusListeners();
			fireFocusLost(true);
			assertEquals("a temporary loss commits nothing", 0, prefs.saves.size());
			fireFocusLost(false);
			assertEquals(ChangeText.DEFAULT.withBrightness(40), prefs.saves.get(0));
			assertEquals(40, panel.changeText().brightness());

			box().setText("60");
			for (PopupMenuListener l : panel.listMenu().getPopupMenuListeners())
			{
				l.popupMenuWillBecomeInvisible(new PopupMenuEvent(panel.listMenu()));
			}
			assertEquals("closing the menu commits", 2, prefs.saves.size());
			assertEquals(60, panel.changeText().brightness());

			box().setText("nope");
			for (PopupMenuListener l : panel.listMenu().getPopupMenuListeners())
			{
				l.popupMenuWillBecomeInvisible(new PopupMenuEvent(panel.listMenu()));
			}
			assertEquals("a refused text is not saved by the close either", 2, prefs.saves.size());
			assertTrue(PanelState.isMarkedInvalid(box()));
			assertEquals(60, panel.changeText().brightness());
		});
	}

	private void fireFocusLost(boolean temporary)
	{
		final java.awt.event.FocusEvent e = new java.awt.event.FocusEvent(box().getTextField(), FocusEvent.FOCUS_LOST, temporary);
		for (java.awt.event.FocusListener l : box().getTextField().getFocusListeners())
		{
			l.focusLost(e);
		}
	}

	/**
	 * S3: a press on a bar - the lit cell, an unlit one - while the box is typed in takes the keyboard off the box (the panel's
	 * click-off reaches this popup), which commits what was typed through the box's own focus-lost rule. Headless there is no
	 * keyboard owner to move, so what is proved is the press's decision: every press on a cell, a caption, the row's air or the
	 * row's labels is wired to the one hand-over, and an unlit cell's press still chooses its size.
	 */
	@Test
	public void s3_aPressAnywhereElseInTheMenuIsWiredToTakeTheKeyboardOffTheBox() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			for (Component c : Arrays.asList(menu()[4], menu()[5], menu()[6], menu()[7], menu()[8]))
			{
				assertTrue("a press on " + c + " is heard", c.getMouseListeners().length > 0);
			}
			for (SizeBar bar : Arrays.asList(gpBar(), pctBar()))
			{
				for (int i = 0; i < 4; i++)
				{
					assertTrue("every cell hears a press", cell(bar, i).getMouseListeners().length > 0);
				}
			}
		});
	}

	/**
	 * Fix 1: a brightness typed and not yet entered survives a press on an UNLIT size cell. The cell's press hands the keyboard
	 * over (which only queues the box's focus-lost) and then chooses the size, which redraws the box from the stored value - so
	 * the typed one used to be thrown away before its commit ran. Now the hand-over commits it first: the size and the
	 * brightness are both stored, the box shows what was typed, and the focus-lost that arrives later saves nothing more.
	 */
	@Test
	public void aTypedBrightnessSurvivesAPressOnAnUnlitSizeCell() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			box().setText("70");
			press(cell(gpBar(), 2), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70), panel.changeText());
			assertEquals("70", box().getText());
			assertFalse(PanelState.isMarkedInvalid(box()));
			assertEquals(2, litIndex(gpBar()));
			assertEquals(Widgets.sans(12), gpFont(0));
			assertEquals("both are stored, brightness first", Arrays.asList(ChangeText.DEFAULT.withBrightness(70),
				ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70)), prefs.saves);

			// The queued focus lost, arriving after the press: the box already shows the stored value, so nothing is saved.
			fireFocusLost(false);
			assertEquals(2, prefs.saves.size());
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70), panel.changeText());
			assertEquals("70", box().getText());

			// The percentage bar too, and a value typed in the box again.
			box().setText("90");
			press(cell(pctBar(), 3), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 90), panel.changeText());
			assertEquals("90", box().getText());
			fireFocusLost(false);
			assertEquals(4, prefs.saves.size());
		});
	}

	/** Fix 1: a press on the LIT cell chooses nothing, but it still ends the typing - what was typed is stored. */
	@Test
	public void aPressOnTheLitCellStillStoresATypedBrightness() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			box().setText("33");
			press(cell(gpBar(), 1), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(ChangeText.DEFAULT.withBrightness(33), panel.changeText());
			assertEquals(Collections.singletonList(ChangeText.DEFAULT.withBrightness(33)), prefs.saves);
			assertEquals("33", box().getText());
		});
	}

	/**
	 * Fix 1: a typed brightness that is not a whole number from 20 to 100 is refused by the same press as by Enter - nothing
	 * about it is saved - while the size still applies, and the box then shows the brightness in force.
	 */
	@Test
	public void aRefusedBrightnessIsNotSavedByTheSizePress() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			box().setText("101");
			press(cell(pctBar(), 2), java.awt.event.MouseEvent.BUTTON1);
			assertEquals(ChangeText.DEFAULT.withPct(ChangeSize.LARGE), panel.changeText());
			assertEquals(Collections.singletonList(ChangeText.DEFAULT.withPct(ChangeSize.LARGE)), prefs.saves);
			assertEquals("the value in force", "69", box().getText());
			fireFocusLost(false);
			assertEquals(1, prefs.saves.size());

			// A press that chooses nothing leaves the refused text red, as Enter would.
			box().setText("abc");
			press(cell(pctBar(), 2), java.awt.event.MouseEvent.BUTTON1);
			assertTrue(PanelState.isMarkedInvalid(box()));
			assertEquals(1, prefs.saves.size());
		});
	}

	/**
	 * Fix 2: the box is wide enough to show 100 whole. The box's borders and padding leave its text a few px less than its
	 * width, and "100" in its face is wider than the 14 px that 28 px left, so it was drawn like "101". Measured from the box's
	 * own face and layout: the text area holds "100" with at least 2 px to spare, and one px narrower would not.
	 */
	@Test
	public void theBoxShowsOneHundredWhole() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			final int width = ChangeSettings.FIELD_WIDTH;
			assertEquals(width, box().getPreferredSize().width);
			assertTrue("room to spare for 100: " + textRoom(width, "100"), textRoom(width, "100") >= 2);
			assertTrue("the smallest width that does: " + textRoom(width - 1, "100"), textRoom(width - 1, "100") < 2);
			assertTrue("and 20 and 55 have it too", textRoom(width, "55") >= 2 && textRoom(width, "20") >= 2);
		});
	}

	/** The px a field {@code width} wide leaves beside {@code text} in its text area, by the field's own face and layout. */
	private static int textRoom(int width, String text)
	{
		final Widgets.PlaceholderField field = Widgets.gpField("");
		Widgets.fixed(field, width, field.getPreferredSize().height);
		field.setText(text);
		field.setSize(width, field.getPreferredSize().height);
		LookRenderer.layoutTree(field);
		final javax.swing.JTextField tf = field.getTextField();
		final java.awt.Insets in = tf.getInsets();
		return tf.getWidth() - in.left - in.right - tf.getFontMetrics(tf.getFont()).stringWidth(text);
	}

	/** {@code n} rows that moved, each a stack of several so line 3 prints a gp figure. */
	private static List<MovementRow> many(int n)
	{
		final List<MovementRow> out = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			out.add(new MovementRow(100 + i, "Item " + i, 12, false, 1_520_000L - i, 1_500_000L, 20_000L - i, 1.3d, 18_240_000L - i,
				null));
		}
		return out;
	}

	private void layout()
	{
		panel.setSize(net.runelite.client.ui.PluginPanel.PANEL_WIDTH + net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH, 400);
		LookRenderer.layoutTree(panel);
		LookRenderer.layoutTree(panel);
	}

	/** The distance from one row's top to the next, as the list is laid out now. */
	private int pitch()
	{
		return panel.rowPanels().get(1).getY() - panel.rowPanels().get(0).getY();
	}

	/**
	 * Fix 3: a size change makes every row taller, so the reader's scroll place is scaled by the new pitch over the old and they
	 * stay on about the same row - the pixel offset they had would otherwise land several rows higher. Row 20's top stays the top
	 * of the view, going up in size and coming back.
	 */
	@Test
	public void aSizeChangeKeepsTheReaderOnTheSameRow() throws Exception
	{
		build(null, many(60));
		final int[] normalPitch = new int[1];
		onEdt(() ->
		{
			layout();
			normalPitch[0] = pitch();
			final javax.swing.JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertTrue("the fixture scrolls: " + bar.getMaximum(), bar.getMaximum() > 30 * normalPitch[0]);
			assertEquals(normalPitch[0] * 20, panel.rowPanels().get(20).getY());
			bar.setValue(normalPitch[0] * 20);
			assertEquals(normalPitch[0] * 20, bar.getValue());
			panel.pressChangeText(ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGEST, ChangeText.DEFAULT_BRIGHTNESS));
			layout();
		});
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			assertTrue("the premise: bigger faces make taller rows", pitch() > normalPitch[0]);
			final javax.swing.JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertEquals("row 20 is still at the top of the view", panel.rowPanels().get(20).getY(), bar.getValue(), 1);
			assertEquals(pitch() * 20, bar.getValue(), 1);
			panel.pressChangeText(ChangeText.DEFAULT);
			layout();
		});
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			final javax.swing.JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertEquals("and back again", panel.rowPanels().get(20).getY(), bar.getValue(), 1);
			assertEquals(normalPitch[0] * 20, bar.getValue(), 1);
		});
	}

	/** Fix 3: the scale is owed only for a real change - the same settings again redraw nothing and move nothing. */
	@Test
	public void theSameSettingsAgainMoveNoPlace() throws Exception
	{
		build(null, many(60));
		final int[] normalPitch = new int[1];
		onEdt(() ->
		{
			layout();
			normalPitch[0] = pitch();
			panel.scrollPane().getVerticalScrollBar().setValue(normalPitch[0] * 10);
			panel.pressChangeText(ChangeText.DEFAULT);
			panel.applyChangeText(ChangeText.DEFAULT);
			layout();
		});
		onEdt(() ->
		{
		});
		onEdt(() ->
		{
			assertEquals(normalPitch[0] * 10, panel.scrollPane().getVerticalScrollBar().getValue(), 1);
		});
	}

	/**
	 * Fix 4: a press on any part of the List options menu that is not a menu item takes the caret off the box - the popup itself
	 * (its border), the rule, the "Colour presets" caption the colour presets bring, the bars' rows and the captions. The
	 * decision is the box's commit, so a typed value is stored by a press on each of them.
	 */
	@Test
	public void everyPartOfThePopupThatIsNoItemDropsTheCaret() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			assertTrue("the popup itself hears a press", panel.listMenu().getMouseListeners().length > 0);
			int heard = 0;
			for (Component part : menu())
			{
				if (!(part instanceof JMenuItem))
				{
					assertTrue(part + " hears a press", part.getMouseListeners().length > 0);
					heard++;
				}
			}
			assertEquals("the rule, two captions, two bar rows, the brightness row and the Colour presets caption", 7, heard);
			assertEquals("Colour presets", find(menu()[9], JLabel.class).getText());
			int value = 30;
			for (Component part : Arrays.asList(menu()[1], menu()[9], menu()[4], menu()[7], menu()[5], menu()[8], menu()[6]))
			{
				box().setText(Integer.toString(value));
				press(part, java.awt.event.MouseEvent.BUTTON1);
				assertEquals(part + " committed the typed value", value, panel.changeText().brightness());
				value += 7;
			}
			box().setText("90");
			press(panel.listMenu(), java.awt.event.MouseEvent.BUTTON1);
			assertEquals("a press on the popup itself", 90, panel.changeText().brightness());
			box().setText("40");
			press(menu()[9], java.awt.event.MouseEvent.BUTTON3);
			assertEquals("a right press is no press", 90, panel.changeText().brightness());
		});
	}

	/** Fix 5: "Reset to default" draws the rows once, not once for the colours and again for the sizes. */
	@Test
	public void resetToDefaultRebuildsTheRowsOnce() throws Exception
	{
		build(ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 90));
		onEdt(() ->
		{
			final int before = panel.rebuilds();
			panel.resetPresetsButton().doClick();
			assertEquals(1, panel.rebuilds() - before);
			assertEquals(ChangeText.DEFAULT, panel.changeText());
			assertEquals("drawn in the default sizes", Widgets.sans(10), gpFont(0));
			assertEquals(Widgets.sansBold(15), pctFont(0));
		});
	}

	// ------------------------------------------------------------------------------------------------- hovers

	/** S3: one-line hovers, always on - the bars and the box, on the control and on every part of it that is a mouse target. */
	@Test
	public void s3_theHoversAreOneLineEachAndAlwaysOn() throws Exception
	{
		build(null);
		onEdt(() ->
		{
			assertEquals("Size of the gp change on each row", gpBar().getToolTipText());
			assertEquals("Size of the % change on each row", pctBar().getToolTipText());
			assertEquals("How solid the gp change is - 100 is the same colour as the %", box().getToolTipText());
			assertEquals(BankPriceMovementPanel.OPACITY_TIP, box().getTextField().getToolTipText());
			assertEquals(BankPriceMovementPanel.GP_SIZE_TIP, gpBar().getToolTipText());
			assertEquals(BankPriceMovementPanel.PCT_SIZE_TIP, pctBar().getToolTipText());
			for (int i = 0; i < 4; i++)
			{
				assertEquals("a cell is a mouse target, so it speaks too", gpBar().getToolTipText(), cell(gpBar(), i).getToolTipText());
				assertEquals(pctBar().getToolTipText(), cell(pctBar(), i).getToolTipText());
			}
			for (String tip : Arrays.asList(gpBar().getToolTipText(), pctBar().getToolTipText(), box().getToolTipText()))
			{
				assertFalse("one line: " + tip, tip.contains("\n") || tip.contains("<br"));
				assertFalse("plain text, no markup", tip.startsWith("<html"));
			}
		});
	}

	// ----------------------------------------------------------------------------------------- Reset to default

	/** S1: "Reset to default" in the settings menu puts the three back, draws it and stores it. */
	@Test
	public void s1_resetToDefaultRestoresTheThree() throws Exception
	{
		build(ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 90));
		onEdt(() ->
		{
			assertEquals(3, litIndex(gpBar()));
			panel.resetPresetsButton().doClick();
			assertEquals(ChangeText.DEFAULT, panel.changeText());
			assertEquals(1, litIndex(gpBar()));
			assertEquals(1, litIndex(pctBar()));
			assertEquals("69", box().getText());
			assertEquals(Widgets.sans(10), gpFont(0));
			assertEquals(Widgets.sansBold(15), pctFont(0));
			assertEquals("stored too", ChangeText.DEFAULT, prefs.saves.get(prefs.saves.size() - 1));
		});
	}

	// ---------------------------------------------------------------------------------------------------- the road a terminal presses

	/** The panel's own JSON line as a tree: what {@code state.panel} holds for a script. */
	private JsonObject describe() throws Exception
	{
		return new Gson().fromJson(onEdtValue(() -> PanelState.describe(panel)), JsonObject.class);
	}

	/**
	 * S3: {@code state.panel.changeText} echoes the three values, and {@code pressChangeText} - the road the menu's cells and the
	 * dev bridge's {@code change=} both press - draws at once, stores, and writes nothing when the value does not change. A part
	 * changed on its own leaves the other two as they were.
	 */
	@Test
	public void s3_theEchoShowsTheThreeAndPressingTheMenusRoadDrawsAndStores() throws Exception
	{
		build(null);
		JsonObject echo = describe().getAsJsonObject("changeText");
		assertEquals("NORMAL", echo.get("gpSize").getAsString());
		assertEquals("NORMAL", echo.get("pctSize").getAsString());
		assertEquals(69, echo.get("gpOpacity").getAsInt());

		onEdt(() -> panel.pressChangeText(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70)));
		echo = describe().getAsJsonObject("changeText");
		assertEquals("LARGE", echo.get("gpSize").getAsString());
		assertEquals("NORMAL", echo.get("pctSize").getAsString());
		assertEquals(70, echo.get("gpOpacity").getAsInt());
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70), prefs.saves.get(prefs.saves.size() - 1));
		onEdt(() ->
		{
			assertEquals("drawn at once", Widgets.sans(12), gpFont(0));
			assertEquals(2, litIndex(gpBar()));
			assertEquals("70", box().getText());
		});

		// One part on its own: the others stay as they were.
		onEdt(() -> panel.pressChangeText(panel.changeText().withPct(ChangeSize.LARGEST)));
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 70), onEdtValue(() -> panel.changeText()));
		onEdt(() -> panel.pressChangeText(panel.changeText().withBrightness(100)));
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 100), onEdtValue(() -> panel.changeText()));
		// The same again: idempotent, no extra write.
		final int writes = prefs.saves.size();
		onEdt(() -> panel.pressChangeText(panel.changeText().withBrightness(100)));
		assertEquals(writes, prefs.saves.size());
	}

	private <T> T onEdtValue(java.util.function.Supplier<T> body) throws Exception
	{
		final java.util.concurrent.atomic.AtomicReference<T> out = new java.util.concurrent.atomic.AtomicReference<>();
		onEdt(() -> out.set(body.get()));
		return out.get();
	}

	/** The gp figure's label on line 2 of a row. */
	private static JLabel gpLabel(MovementRowPanel row)
	{
		return ChangeSizeRowsTest.gp2(row);
	}
}
