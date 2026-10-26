package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
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
import static com.bankpricemovement.SidebarViewPanelTest.walk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release 1.2.1 part 2 (contract {@code docs/handoff/contract-1.2.1-part2-history-settings-2026-10-25.md}, P2, P3 and P5): the Net
 * Worth History tab's own change settings and the range its chart remembers. The History options menu holds the five rows of the
 * Items menu under "Single chart colour" (one builder, {@link ChangeSettings}, over the tab's own {@link ChangeText}); the day rows
 * take the tab's percentage size (11 / 14 / 16 / 17 px bold) and gp size (8 / 10 / 12 / 13 px) and are exactly 36 px at Normal /
 * Normal, growing only by what bigger faces need; the opacity colours the day rows' gp figure and the change line's over the chart;
 * nothing of the Items tab moves with any of it; and the chart's range is stored by every road that moves it.
 *
 * <p>The move palette is one static for the whole process, so every test clears it in {@code @After}.
 */
public class HistoryChangeTest
{
	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;
	private Memory prefs;

	/** The config as a memory: both tabs' change settings and the range as stored, and every write of them, in order. */
	private static final class Memory extends PrefsDefaults
	{
		@Nullable
		ChangeText storedHistory;
		@Nullable
		BankHistoryRange storedRange;
		final List<ChangeText> itemSaves = new ArrayList<>();
		final List<ChangeText> historySaves = new ArrayList<>();
		final List<BankHistoryRange> rangeSaves = new ArrayList<>();

		@Override
		public RowFilter load()
		{
			return null;
		}

		@Override
		public void save(RowFilter filter)
		{
		}

		@Override
		public void saveChangeText(ChangeText text)
		{
			itemSaves.add(text);
		}

		@Override
		public ChangeText loadHistoryText()
		{
			return storedHistory;
		}

		@Override
		public void saveHistoryText(ChangeText text)
		{
			historySaves.add(text);
		}

		@Override
		public BankHistoryRange loadHistoryRange()
		{
			return storedRange;
		}

		@Override
		public void saveHistoryRange(BankHistoryRange range)
		{
			rangeSaves.add(range);
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

	private void build(@Nullable ChangeText storedHistory, @Nullable BankHistoryRange storedRange) throws Exception
	{
		prefs = new Memory();
		prefs.storedHistory = storedHistory;
		prefs.storedRange = storedRange;
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
		final LocalDate[] days = new LocalDate[30];
		for (int i = 0; i < days.length; i++)
		{
			days[i] = TODAY.minusDays(days.length - 1 - i);
		}
		final PriceService.Status s = status(series(days), VALUE_NOW, NOW - 60_000L);
		onEdt(() ->
		{
			listener.onRows(Collections.emptyList(), s);
			panel.setView(SidebarView.HISTORY);
		});
	}

	private void build() throws Exception
	{
		build(null, null);
	}

	// ------------------------------------------------------------------------------------------------ helpers

	private static final BankHistoryDayRow.Model WIDEST = new BankHistoryDayRow.Model(TODAY, false, "Wed 30 Sep",
		"vs 30 Aug, 123 days", "12.3b", "12,345,678,901", "+9999%", "-888.8m", -1, false);
	private static final BankHistoryDayRow.Model TYPICAL = new BankHistoryDayRow.Model(TODAY, false, "Sat 26 Sep", "vs 25 Sep",
		"736m", "736,412,683", "+0.2%", "+1.61m", 1, false);

	private static List<BankHistoryDayRow> dayRows(Component root)
	{
		final List<BankHistoryDayRow> rows = new ArrayList<>();
		for (Component c : walk(root))
		{
			if (c instanceof BankHistoryDayRow && !((BankHistoryDayRow) c).model().carried)
			{
				rows.add((BankHistoryDayRow) c);
			}
		}
		return rows;
	}

	private static JLabel pctLabel(BankHistoryDayRow row)
	{
		return (JLabel) row.getComponent(4);
	}

	private static JLabel gpLabel(BankHistoryDayRow row)
	{
		return (JLabel) row.getComponent(5);
	}

	/** The change line's gp figure over the chart: the second child of the view's chart block (the first child of the view). */
	private JLabel changeLineGp()
	{
		final BankHistoryView view = find(panel, BankHistoryView.class);
		return (JLabel) ((Container) view.getComponent(0)).getComponent(1);
	}

	private Component[] menu()
	{
		return panel.historyMenu().getComponents();
	}

	private SizeBar gpBar()
	{
		return find(menu()[3], SizeBar.class);
	}

	private SizeBar pctBar()
	{
		return find(menu()[6], SizeBar.class);
	}

	private Widgets.PlaceholderField box()
	{
		return find(menu()[4], Widgets.PlaceholderField.class);
	}

	private static JLabel cell(SizeBar bar, int i)
	{
		return (JLabel) bar.getComponent(i);
	}

	private static void fire(JPopupMenu menu, boolean visible)
	{
		for (PopupMenuListener l : menu.getPopupMenuListeners())
		{
			if (visible)
			{
				l.popupMenuWillBecomeVisible(new PopupMenuEvent(menu));
			}
			else
			{
				l.popupMenuWillBecomeInvisible(new PopupMenuEvent(menu));
			}
		}
	}

	// ------------------------------------------------------------------------------------------ the day rows

	/**
	 * P2: a day row is exactly 36 px at Normal / Normal and never less, grows only with the faces, and at every one of the sixteen
	 * pairs holds every label inside its own height with line 1 over line 2 - the widest honest row at 213 px. The percentage and the
	 * gp figure stand at the row's right edge whole (they are never cut); the labels measured are the row's own.
	 */
	@Test
	public void p2_aDayRowIsThirtySixAtNormalAndGrowsOnlyByWhatTheFacesNeed() throws Exception
	{
		onEdt(() ->
		{
			assertEquals(36, BankHistoryDayRow.HEIGHT);
			assertEquals(36, BankHistoryDayRow.rowHeight(ChangeText.DEFAULT));
			int previousGp = 0;
			for (ChangeSize gp : ChangeSize.values())
			{
				int previousPct = 0;
				for (ChangeSize pct : ChangeSize.values())
				{
					final ChangeText text = ChangeText.of(gp, pct, 55);
					final int height = BankHistoryDayRow.rowHeight(text);
					assertTrue(gp + " / " + pct + ": never less than the 36 px of Normal", height >= 36);
					assertTrue("a bigger percentage never makes the row shorter", height >= previousPct);
					previousPct = height;
					for (BankHistoryDayRow.Model model : new BankHistoryDayRow.Model[]{WIDEST, TYPICAL})
					{
						final BankHistoryDayRow row = new BankHistoryDayRow(model, text);
						assertEquals(gp + " / " + pct + ": the row's own height", height, row.getPreferredSize().height);
						row.setSize(213, height);
						row.doLayout();
						assertEquals(Widgets.sansBold(pct.historyPctPx()), pctLabel(row).getFont());
						assertEquals(Widgets.sans(gp.gpPx()), gpLabel(row).getFont());
						final int top1 = Math.min(row.getComponent(0).getY(), Math.min(row.getComponent(2).getY(), pctLabel(row).getY()));
						int bottom1 = 0;
						for (int i : new int[]{0, 2, 4})
						{
							final Component c = row.getComponent(i);
							assertTrue(gp + " / " + pct + ": line 1 starts inside the row", c.getY() >= 0);
							bottom1 = Math.max(bottom1, c.getY() + c.getHeight());
						}
						int top2 = height;
						int bottom2 = 0;
						for (int i : new int[]{1, 3, 5})
						{
							final Component c = row.getComponent(i);
							top2 = Math.min(top2, c.getY());
							bottom2 = Math.max(bottom2, c.getY() + c.getHeight());
						}
						assertTrue(gp + " / " + pct + ": line 1 (" + top1 + ".." + bottom1 + ") stands over line 2 (" + top2 + ".."
							+ bottom2 + ")", bottom1 <= top2);
						assertTrue(gp + " / " + pct + ": line 2 ends inside the row (" + bottom2 + " of " + height + ")",
							bottom2 <= height);
						final int right = 213 - row.getInsets().right;
						assertEquals("the percentage stands at the right edge, whole", right, pctLabel(row).getX() + pctLabel(row).getWidth());
						assertEquals(pctLabel(row).getPreferredSize().width, pctLabel(row).getWidth());
						assertEquals("and so does the gp figure", right, gpLabel(row).getX() + gpLabel(row).getWidth());
						assertEquals(gpLabel(row).getPreferredSize().width, gpLabel(row).getWidth());
					}
				}
				assertTrue("a bigger gp figure never makes the row shorter",
					BankHistoryDayRow.rowHeight(ChangeText.of(gp, ChangeSize.NORMAL, 55)) >= previousGp);
				previousGp = BankHistoryDayRow.rowHeight(ChangeText.of(gp, ChangeSize.NORMAL, 55));
			}
		});
	}

	/** P2: the History percentage's four sizes are 11 / 14 / 16 / 17 bold and the gp figure's 8 / 10 / 12 / 13, whatever an Items row says. */
	@Test
	public void p2_theFourStepsAreTheContractsPixelSizes() throws Exception
	{
		final int[] pct = {11, 14, 16, 17};
		final int[] gp = {8, 10, 12, 13};
		final ChangeSize[] steps = ChangeSize.values();
		for (int i = 0; i < steps.length; i++)
		{
			assertEquals(pct[i], steps[i].historyPctPx());
			assertEquals(gp[i], steps[i].gpPx());
		}
		assertNotEquals("the Items percentage is its own", ChangeSize.NORMAL.pctPx(), ChangeSize.NORMAL.historyPctPx());
	}

	/** P2: the carried day (20 px) and the header (16 px) are what they were whatever the sizes say. */
	@Test
	public void p2_aCarriedDayAndTheHeaderAreUnchanged() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryDayRow.Model carried = new BankHistoryDayRow.Model(TODAY, true, "Tue 22 Sep", "", "", "", "", "", 0, false);
			for (ChangeSize size : ChangeSize.values())
			{
				assertEquals(BankHistoryDayRow.CARRIED_HEIGHT,
					new BankHistoryDayRow(carried, ChangeText.of(size, size, 20)).getPreferredSize().height);
			}
			assertEquals(BankHistoryDayRow.HEADER_HEIGHT, BankHistoryDayRow.header().getPreferredSize().height);
		});
	}

	/** P2: the opacity is the gp figure's colour - 100 is the percentage's colour exactly, 69 the default (since 1.2.2), 20 the least. */
	@Test
	public void p2_theGpFigureWearsTheOpacityAtTwentySixtyNineAndOneHundred() throws Exception
	{
		onEdt(() ->
		{
			for (int sign : new int[]{1, -1})
			{
				final BankHistoryDayRow.Model model = new BankHistoryDayRow.Model(TODAY, false, "Sat 26 Sep", "vs 25 Sep", "736m",
					"736,412,683", sign > 0 ? "+0.2%" : "-0.2%", sign > 0 ? "+1.61m" : "-1.61m", sign, false);
				for (int opacity : new int[]{20, 69, 100})
				{
					final BankHistoryDayRow row = new BankHistoryDayRow(model, ChangeText.DEFAULT.withBrightness(opacity));
					assertEquals(Widgets.quietAt(sign, opacity), gpLabel(row).getForeground());
				}
				assertEquals("100 is the percentage's colour exactly", pctLabel(new BankHistoryDayRow(model,
					ChangeText.DEFAULT.withBrightness(100))).getForeground(),
					gpLabel(new BankHistoryDayRow(model, ChangeText.DEFAULT.withBrightness(100))).getForeground());
				assertEquals("the default is 69", Widgets.quietAt(sign, 69),
					gpLabel(new BankHistoryDayRow(model, ChangeText.DEFAULT)).getForeground());
				assertNotEquals(Widgets.quietAt(sign, 20), Widgets.quietAt(sign, 100));
			}
			// An outside value reads as 69 (ChangeText.of), whatever the config holds.
			assertEquals(69, ChangeText.of(ChangeSize.NORMAL, ChangeSize.NORMAL, 5).brightness());
			assertEquals(69, ChangeText.of(ChangeSize.NORMAL, ChangeSize.NORMAL, 150).brightness());
		});
	}

	/**
	 * P2: a change of the tab's settings rebuilds the day rows it kept (a row whose model did not change used to be kept), draws the
	 * new faces and colours in the rows AND the change line over the chart - which keeps its own sizes - and moves nothing of the
	 * Items tab or its stored settings.
	 */
	@Test
	public void p2_aChangeRebuildsTheKeptRowsAndTouchesTheItemsTabNotAtAll() throws Exception
	{
		build();
		onEdt(() ->
		{
			final List<BankHistoryDayRow> before = dayRows(panel);
			assertTrue(before.size() >= 20);
			final Color lineWas = changeLineGp().getForeground();
			final java.awt.Font lineFont = changeLineGp().getFont();
			assertEquals(Widgets.quietAt(1, 69), lineWas);
			panel.pressHistoryText(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 80));
			final List<BankHistoryDayRow> after = dayRows(panel);
			assertEquals(before.size(), after.size());
			for (int i = 0; i < before.size(); i++)
			{
				assertNotSame("the kept row is built again", before.get(i), after.get(i));
				assertEquals(Widgets.sans(12), gpLabel(after.get(i)).getFont());
				assertEquals(Widgets.sansBold(17), pctLabel(after.get(i)).getFont());
				if (!gpLabel(after.get(i)).getText().isEmpty())
				{
					assertEquals(Widgets.quietAt(1, 80), gpLabel(after.get(i)).getForeground());
				}
				assertEquals(BankHistoryDayRow.rowHeight(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 80)),
					after.get(i).getPreferredSize().height);
			}
			assertEquals("the change line takes the opacity", Widgets.quietAt(1, 80), changeLineGp().getForeground());
			assertEquals("and keeps its size", lineFont, changeLineGp().getFont());
			assertEquals("the Items tab keeps its own settings", ChangeText.DEFAULT, panel.changeText());
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 80), panel.historyText());
			assertEquals(Collections.singletonList(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 80)), prefs.historySaves);
			assertTrue("never the Items key", prefs.itemSaves.isEmpty());
			// The same settings again redraw and write nothing.
			panel.pressHistoryText(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 80));
			assertSame(after.get(0), dayRows(panel).get(0));
			assertEquals(1, prefs.historySaves.size());
		});
	}

	/** P2: the Items tab's settings never move the History tab's - the two are independent. */
	@Test
	public void p2_theItemsSettingsNeverMoveTheHistoryOnes() throws Exception
	{
		build();
		onEdt(() ->
		{
			final List<BankHistoryDayRow> before = dayRows(panel);
			panel.pressChangeText(ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGEST, 100));
			assertEquals(ChangeText.DEFAULT, panel.historyText());
			assertSame(before.get(0), dayRows(panel).get(0));
			assertTrue(prefs.historySaves.isEmpty());
			assertEquals(1, prefs.itemSaves.size());
		});
	}

	/**
	 * P2: a size change keeps the History list's scroll place on about the same day - the place is scaled about the list's top by the
	 * rows' new pitch over the old, and put back once the layout has run.
	 */
	@Test
	public void p2_aSizeChangeKeepsTheScrollPlaceOnAboutTheSameDay() throws Exception
	{
		final BankPriceMovementPanel[] drawn = new BankPriceMovementPanel[1];
		try
		{
			onEdt(() ->
			{
				drawn[0] = LookRenderer.buildHistory(HeroVisibility.ALL, HistoryViewRenderer.fixture());
				drawn[0].setSize(LookRenderer.WIDTH, 600);
				LookRenderer.layoutTree(drawn[0]);
			});
			final javax.swing.JScrollBar bar = LookRenderer.historyPane(drawn[0]).getVerticalScrollBar();
			final int[] top = new int[1];
			final int[] was = new int[1];
			final ChangeText large = ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGEST, ChangeText.DEFAULT_BRIGHTNESS);
			onEdt(() ->
			{
				final BankHistoryView view = find(drawn[0], BankHistoryView.class);
				top[0] = view.getY() + view.listTop();
				bar.setValue(top[0] + 600);
				was[0] = bar.getValue();
				drawn[0].pressHistoryText(large);
				LookRenderer.layoutTree(drawn[0]);
			});
			// The restore is posted behind the layout: let it run.
			onEdt(() ->
			{
			});
			final double scale = (double) (BankHistoryDayRow.rowHeight(large) + BankHistoryView.ROW_GAP)
				/ (BankHistoryDayRow.rowHeight(ChangeText.DEFAULT) + BankHistoryView.ROW_GAP);
			assertTrue("the rows grew", scale > 1.05);
			onEdt(() ->
			{
				final int want = top[0] + (int) Math.round((was[0] - top[0]) * scale);
				assertEquals("the place is scaled about the list's top", want, bar.getValue());
				assertTrue("and is further down than before", bar.getValue() > was[0]);
			});
		}
		finally
		{
			Widgets.setMoveColours(null, null);
			onEdt(() -> drawn[0].stop());
		}
	}

	/**
	 * P2, the review's find: a size change made with the list at its top owes no scale to a later redraw - scrolled down into the
	 * days afterwards, pressing the eye leaves the place where it is (it used to jump by the old scale, 189 + 300 x 1.21).
	 */
	@Test
	public void p2_aSizeChangeAtTheTopLeavesNoScaleForALaterRedraw() throws Exception
	{
		final BankPriceMovementPanel[] drawn = new BankPriceMovementPanel[1];
		try
		{
			onEdt(() ->
			{
				drawn[0] = LookRenderer.buildHistory(HeroVisibility.ALL, HistoryViewRenderer.fixture());
				drawn[0].setSize(LookRenderer.WIDTH, 600);
				LookRenderer.layoutTree(drawn[0]);
			});
			final javax.swing.JScrollBar bar = LookRenderer.historyPane(drawn[0]).getVerticalScrollBar();
			final int[] place = new int[1];
			onEdt(() ->
			{
				bar.setValue(0);
				drawn[0].pressHistoryText(ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGEST, ChangeText.DEFAULT_BRIGHTNESS));
				LookRenderer.layoutTree(drawn[0]);
			});
			onEdt(() ->
			{
			});
			onEdt(() ->
			{
				final BankHistoryView view = find(drawn[0], BankHistoryView.class);
				bar.setValue(view.getY() + view.listTop() + 300);
				place[0] = bar.getValue();
				drawn[0].pressHideAmounts();
				LookRenderer.layoutTree(drawn[0]);
			});
			onEdt(() ->
			{
			});
			onEdt(() -> assertEquals("the eye moves no place", place[0], bar.getValue()));
		}
		finally
		{
			Widgets.setMoveColours(null, null);
			onEdt(() -> drawn[0].stop());
		}
	}

	// ----------------------------------------------------------------------------------------------- the menu

	/**
	 * P2: the History options menu holds the tab's five rows directly after "Single chart colour" and a rule - caption, bar, the
	 * opacity row, caption, bar - and they are the ones the Items menu has, word for word, in one builder; then, since 1.2.2, a rule
	 * and the one row of Reset history and Restore history.
	 */
	@Test
	public void p2_theMenuHoldsTheFiveRowsAfterSingleChartColourAndARule() throws Exception
	{
		build();
		onEdt(() ->
		{
			final Component[] items = menu();
			assertEquals("the chart row, a rule, the five rows, a rule and the buttons' row", 9, items.length);
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, ((JMenuItem) items[0]).getText());
			assertTrue(items[1] instanceof JSeparator);
			assertEquals("GP change size", find(items[2], JLabel.class).getText());
			assertTrue(items[3] instanceof JPanel && find(items[3], SizeBar.class) != null);
			final List<String> words = new ArrayList<>();
			for (Component c : ((Container) items[4]).getComponents())
			{
				if (c instanceof JLabel)
				{
					words.add(((JLabel) c).getText());
				}
			}
			assertEquals(java.util.Arrays.asList("Opacity", "%"), words);
			assertEquals("Percent change size", find(items[5], JLabel.class).getText());
			assertTrue(items[6] instanceof JPanel && find(items[6], SizeBar.class) != null);
			assertTrue(items[7] instanceof JSeparator);
			assertTrue("Reset history and Restore history", items[8] instanceof JPanel
				&& find(items[8], javax.swing.JButton.class) != null);
			assertEquals(ChangeSettings.FIELD_WIDTH, box().getPreferredSize().width);
			assertEquals("69", box().getText());
			assertEquals("both bars lit on Normal", 1, lit(gpBar()));
			assertEquals(1, lit(pctBar()));
			assertSame("the keyboard is parked on the row, never the box", items[4], panel.historyMenuFocusTarget());
			assertTrue(items[4].isFocusable());
			assertFalse(box().getTextField().isFocusOwner());
			// The three hovers, one line each.
			assertEquals(BankPriceMovementPanel.GP_SIZE_TIP, gpBar().getToolTipText());
			assertEquals(BankPriceMovementPanel.PCT_SIZE_TIP, pctBar().getToolTipText());
			assertEquals(BankPriceMovementPanel.OPACITY_TIP, box().getToolTipText());
			assertEquals(BankPriceMovementPanel.OPACITY_TIP, box().getTextField().getToolTipText());
			assertEquals("How solid the gp change is - 100 is the same colour as the %", BankPriceMovementPanel.OPACITY_TIP);
		});
	}

	private static int lit(SizeBar bar)
	{
		int at = -1;
		for (int i = 0; i < 4; i++)
		{
			if (Widgets.sansBold(SizeBar.TEXT_PX).equals(cell(bar, i).getFont()))
			{
				assertEquals("only one cell is lit", -1, at);
				at = i;
			}
		}
		return at;
	}

	/** P2: a press on an unlit cell lights it, draws it in the day rows and stores it on the History key - never the Items one. */
	@Test
	public void p2_aBarPressLightsDrawsAndStoresOnTheHistoryKey() throws Exception
	{
		build();
		onEdt(() ->
		{
			press(cell(gpBar(), 2), MouseEvent.BUTTON1);
			assertEquals(2, lit(gpBar()));
			assertEquals(ChangeText.DEFAULT.withGp(ChangeSize.LARGE), panel.historyText());
			assertEquals(Widgets.sans(12), gpLabel(dayRows(panel).get(0)).getFont());
			press(cell(pctBar(), 3), MouseEvent.BUTTON1);
			assertEquals(3, lit(pctBar()));
			assertEquals(Widgets.sansBold(17), pctLabel(dayRows(panel).get(0)).getFont());
			assertEquals(2, prefs.historySaves.size());
			assertTrue(prefs.itemSaves.isEmpty());
			// The lit cell again chooses nothing.
			press(cell(pctBar(), 3), MouseEvent.BUTTON1);
			assertEquals(2, prefs.historySaves.size());
		});
	}

	/**
	 * P2 (every S3 behaviour of part 1): the box takes whole numbers from 20 to 100 and goes red and saves nothing on anything else;
	 * it saves on Enter, on a permanent focus lost and when the menu closes; a typed value survives a press on an unlit cell; and
	 * opening the menu shows the value in force with the red cleared.
	 */
	@Test
	public void p2_theBoxBehavesAsTheItemsBoxDoes() throws Exception
	{
		build();
		onEdt(() ->
		{
			for (String bad : new String[]{"abc", "19", "101", "", "5", "150", "0", "-30", "55.5", "1000"})
			{
				box().setText(bad);
				box().getTextField().postActionEvent();
				assertTrue("'" + bad + "' is red", PanelState.isMarkedInvalid(box()));
				assertTrue("'" + bad + "' saves nothing", prefs.historySaves.isEmpty());
				assertEquals(ChangeText.DEFAULT, panel.historyText());
			}
			fire(panel.historyMenu(), true);
			assertEquals("the value in force again", "69", box().getText());
			assertFalse("and not red", PanelState.isMarkedInvalid(box()));

			box().setText("70");
			box().getTextField().postActionEvent();
			assertEquals(ChangeText.DEFAULT.withBrightness(70), panel.historyText());
			assertEquals(Widgets.quietAt(1, 70), gpLabel(dayRows(panel).get(0)).getForeground());
			assertEquals(1, prefs.historySaves.size());
			assertTrue(prefs.itemSaves.isEmpty());

			// A temporary focus loss commits nothing; a permanent one does.
			box().setText("40");
			focusLost(true);
			assertEquals(1, prefs.historySaves.size());
			focusLost(false);
			assertEquals(40, panel.historyText().brightness());
			assertEquals(2, prefs.historySaves.size());

			// Closing the menu commits what was typed, and a refused text is not saved by the close either.
			box().setText("60");
			fire(panel.historyMenu(), false);
			assertEquals(60, panel.historyText().brightness());
			box().setText("nope");
			fire(panel.historyMenu(), false);
			assertEquals(60, panel.historyText().brightness());
			assertEquals(3, prefs.historySaves.size());
			assertTrue(PanelState.isMarkedInvalid(box()));
		});
	}

	private void focusLost(boolean temporary)
	{
		final FocusEvent e = new FocusEvent(box().getTextField(), FocusEvent.FOCUS_LOST, temporary);
		for (java.awt.event.FocusListener l : box().getTextField().getFocusListeners())
		{
			l.focusLost(e);
		}
	}

	/** P2: a value typed and not yet entered survives a press on an unlit size cell: both are stored, the box shows what was typed. */
	@Test
	public void p2_aTypedOpacitySurvivesAPressOnAnUnlitSizeCell() throws Exception
	{
		build();
		onEdt(() ->
		{
			box().setText("70");
			press(cell(gpBar(), 2), MouseEvent.BUTTON1);
			assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70), panel.historyText());
			assertEquals("70", box().getText());
			assertEquals(java.util.Arrays.asList(ChangeText.DEFAULT.withBrightness(70),
				ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70)), prefs.historySaves);
			focusLost(false);
			assertEquals("the queued focus lost saves nothing more", 2, prefs.historySaves.size());
			// Every non-item part of the popup hears a press and hands the keyboard over.
			for (Component c : java.util.Arrays.asList(menu()[1], menu()[2], menu()[3], menu()[4], menu()[5], menu()[6], menu()[7],
				menu()[8]))
			{
				assertTrue(c + " hears a press", c.getMouseListeners().length > 0);
			}
		});
	}

	/**
	 * P2: both menus are wide enough that every cell of every bar holds its word in BOLD - "Largest" is the widest - with at least 2 px
	 * to spare, measured with the real FontMetrics on the laid-out menu.
	 */
	@Test
	public void p2_everyBarCellHoldsItsWordInBoldWithTwoPixelsToSpareInBothMenus() throws Exception
	{
		build();
		onEdt(() ->
		{
			for (JPopupMenu menu : java.util.Arrays.asList(panel.listMenu(), panel.historyMenu()))
			{
				menu.setSize(menu.getPreferredSize());
				LookRenderer.layoutTree(menu);
				int bars = 0;
				for (Component c : walk(menu))
				{
					if (c instanceof SizeBar)
					{
						bars++;
						final FontMetrics bold = c.getFontMetrics(Widgets.sansBold(SizeBar.TEXT_PX));
						for (int i = 0; i < 4; i++)
						{
							final JLabel cell = cell((SizeBar) c, i);
							final int spare = cell.getWidth() - bold.stringWidth(cell.getText());
							assertTrue("\"" + cell.getText() + "\" has " + spare + " px to spare in a menu " + menu.getWidth() + " wide",
								spare >= SizeBar.MIN_SPARE);
						}
					}
				}
				assertEquals(2, bars);
			}
		});
	}

	/** P2: "Reset to default" puts both tabs' settings back and stores both, and the History menu's controls show it. */
	@Test
	public void p2_resetToDefaultResetsHistoryToo() throws Exception
	{
		build(ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 90), null);
		onEdt(() ->
		{
			assertEquals("stored settings are read at start", ChangeText.of(ChangeSize.LARGEST, ChangeSize.SMALL, 90),
				panel.historyText());
			assertEquals(Widgets.sans(13), gpLabel(dayRows(panel).get(0)).getFont());
			panel.pressChangeText(ChangeText.DEFAULT.withGp(ChangeSize.LARGE));
			panel.resetPresetsButton().doClick();
			assertEquals(ChangeText.DEFAULT, panel.historyText());
			assertEquals(ChangeText.DEFAULT, panel.changeText());
			assertEquals(ChangeText.DEFAULT, prefs.historySaves.get(prefs.historySaves.size() - 1));
			assertEquals(ChangeText.DEFAULT, prefs.itemSaves.get(prefs.itemSaves.size() - 1));
			assertEquals(1, lit(gpBar()));
			assertEquals(1, lit(pctBar()));
			assertEquals("69", box().getText());
			final BankHistoryDayRow row = dayRows(panel).get(0);
			assertEquals(Widgets.sans(10), gpLabel(row).getFont());
			assertEquals(Widgets.sansBold(14), pctLabel(row).getFont());
			assertEquals(36, row.getPreferredSize().height);
			assertEquals(Widgets.quietAt(1, 69), changeLineGp().getForeground());
		});
	}

	// ------------------------------------------------------------------------------------- the road a terminal presses

	/** The panel's own JSON line as a tree: what {@code state.panel} holds for a script. */
	private JsonObject describe() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() -> out[0] = PanelState.describe(panel));
		return new Gson().fromJson(out[0], JsonObject.class);
	}

	/**
	 * P2: {@code state.panel.historyChangeText} echoes the three values (with {@code changeText} the Items tab's, untouched), and
	 * {@code pressHistoryText} - the road the History menu's cells and the dev bridge's {@code hchange=} both press - stores the
	 * value for the History tab and writes nothing when it does not change; one part on its own leaves the other two as they were.
	 */
	@Test
	public void p2_theEchoShowsTheThreeAndPressingTheHistoryRoadStoresIt() throws Exception
	{
		build();
		JsonObject state = describe();
		JsonObject echo = state.getAsJsonObject("historyChangeText");
		assertEquals("NORMAL", echo.get("gpSize").getAsString());
		assertEquals("NORMAL", echo.get("pctSize").getAsString());
		assertEquals(69, echo.get("gpOpacity").getAsInt());

		onEdt(() -> panel.pressHistoryText(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70)));
		state = describe();
		echo = state.getAsJsonObject("historyChangeText");
		assertEquals("LARGE", echo.get("gpSize").getAsString());
		assertEquals(70, echo.get("gpOpacity").getAsInt());
		final JsonObject items = state.getAsJsonObject("changeText");
		assertEquals("the Items echo is untouched", "NORMAL", items.get("gpSize").getAsString());
		assertEquals(69, items.get("gpOpacity").getAsInt());
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.NORMAL, 70), prefs.historySaves.get(prefs.historySaves.size() - 1));
		assertTrue(prefs.itemSaves.isEmpty());

		onEdt(() -> panel.pressHistoryText(panel.historyText().withPct(ChangeSize.LARGEST)));
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 70), onEdtText());
		final int writes = prefs.historySaves.size();
		onEdt(() -> panel.pressHistoryText(panel.historyText().withPct(ChangeSize.LARGEST)));
		assertEquals("the same again writes nothing", writes, prefs.historySaves.size());
		// The Items road takes the opacity too, and the History value stays where it was.
		onEdt(() -> panel.pressChangeText(panel.changeText().withBrightness(80)));
		assertEquals(80, prefs.itemSaves.get(0).brightness());
		assertEquals(ChangeText.of(ChangeSize.LARGE, ChangeSize.LARGEST, 70), onEdtText());
	}

	private ChangeText onEdtText() throws Exception
	{
		final ChangeText[] out = new ChangeText[1];
		onEdt(() -> out[0] = panel.historyText());
		return out[0];
	}

	// ------------------------------------------------------------------------------------- the chart's range

	private String range() throws Exception
	{
		final String[] out = new String[1];
		onEdt(() -> out[0] = (String) PanelState.bankHistoryState(panel).get("range"));
		return out[0];
	}

	/** The chart's own range bar cell for {@code r}: the bar's cells are in {@link BankHistoryRange}'s order. */
	private JLabel rangeCell(BankHistoryRange r)
	{
		final BankHistoryView.RangeBar bar = find(panel, BankHistoryView.RangeBar.class);
		return (JLabel) bar.getComponent(r.ordinal());
	}

	/**
	 * P3: the chart's range is stored by EVERY road that changes it - its own bar, the card's window chip (which still moves the
	 * chart, as it did), the {@code setHistoryRange} the dev bridge's {@code range=} presses - and writes nothing at start or when
	 * the range does not change.
	 */
	@Test
	public void p3_everyRoadThatMovesTheRangeStoresIt() throws Exception
	{
		build();
		final String atStart = range();
		assertTrue("nothing is written at start", prefs.rangeSaves.isEmpty());
		assertEquals("nothing stored: the card's window picks the range", BankHistoryRange.forWindow(RowFilter.DEFAULT.window()).label(),
			atStart);
		final BankHistoryRange other = BankHistoryRange.D30.label().equals(atStart) ? BankHistoryRange.D90 : BankHistoryRange.D30;
		onEdt(() -> press(rangeCell(other), MouseEvent.BUTTON1));
		assertEquals(other.label(), range());
		assertEquals(Collections.singletonList(other), prefs.rangeSaves);

		onEdt(() -> press(rangeCell(BankHistoryRange.ALL), MouseEvent.BUTTON1));
		assertEquals(java.util.Arrays.asList(other, BankHistoryRange.ALL), prefs.rangeSaves);
		onEdt(() -> press(rangeCell(BankHistoryRange.ALL), MouseEvent.BUTTON1));
		assertEquals("the lit cell again changes nothing", 2, prefs.rangeSaves.size());

		// The card's window chip still moves the chart - 90d shows 90d - and that is stored too.
		onEdt(() -> press(panel.windowChip(MovementWindow.D90), MouseEvent.BUTTON1));
		assertEquals("90d", range());
		assertEquals(BankHistoryRange.D90, prefs.rangeSaves.get(prefs.rangeSaves.size() - 1));
		assertEquals(3, prefs.rangeSaves.size());

		// The setter the bridge presses, in any case.
		onEdt(() -> panel.setHistoryRange(BankHistoryRange.D7));
		assertEquals("7d", range());
		assertEquals(BankHistoryRange.D7, prefs.rangeSaves.get(prefs.rangeSaves.size() - 1));
		assertEquals(4, prefs.rangeSaves.size());
	}

	/** P3: at start the stored range wins over the window's - the user's bug was 30d picked, restart, and 7d back. */
	@Test
	public void p3_theStoredRangeWinsAtStart() throws Exception
	{
		for (BankHistoryRange stored : BankHistoryRange.values())
		{
			if (panel != null)
			{
				onEdt(() -> panel.stop());
			}
			build(null, stored);
			assertEquals(stored.label(), range());
			assertTrue("seeding is not a change", prefs.rangeSaves.isEmpty());
			// The lit cell of the bar is the stored one.
			onEdt(() -> assertEquals(BankHistoryView.RangeBar.LIT_FONT, rangeCell(stored).getFont()));
		}
	}

	/** P3: a range word is read without regard to case and only the four are ranges. */
	@Test
	public void p3_theRangeWordsAreParsed() throws Exception
	{
		for (BankHistoryRange r : BankHistoryRange.values())
		{
			assertSame(r, BankHistoryRange.parse(r.label()));
			assertSame(r, BankHistoryRange.parse(" " + r.label().toUpperCase(java.util.Locale.ROOT) + " "));
		}
		assertEquals(null, BankHistoryRange.parse(""));
		assertEquals(null, BankHistoryRange.parse("180d"));
		assertEquals(null, BankHistoryRange.parse(null));
	}
}
