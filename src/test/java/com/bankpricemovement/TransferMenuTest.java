package com.bankpricemovement;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import net.runelite.client.game.ItemManager;
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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release 1.2.2 parts 2 and 4: the right-click menu of a History day row, the History options menu's bottom row of "Reset history"
 * and "Restore history" (layout D), and the words of a cut the player made - through the real panel and a mocked service, so what is
 * pinned is the call each press makes. The calls themselves are proven against the real service in {@link PriceServiceTransfersTest}
 * and end to end in {@link HistoryWalkthroughTest}.
 */
public class TransferMenuTest
{
	private static final LocalDate SEP_25 = LocalDate.of(2026, 9, 25);
	private static final LocalDate SEP_26 = LocalDate.of(2026, 9, 26);
	private static final LocalDate SEP_27 = LocalDate.of(2026, 9, 27);
	private static final long M = 1_000_000L;

	private ItemManager itemManager;
	private PriceService service;
	private SidebarViewPanelTest.Prefs prefs;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;
	private Asked asked;

	/** The questions the panel puts, recorded; answered {@link #answer}. */
	private static final class Asked implements BankPriceMovementPanel.LegacyPrompt
	{
		boolean answer;
		final List<String> questions = new ArrayList<>();
		final List<String> titles = new ArrayList<>();
		final List<String> yes = new ArrayList<>();

		@Override
		public boolean ask(final String question)
		{
			questions.add(question);
			titles.add(null);
			yes.add(null);
			return answer;
		}

		@Override
		public boolean ask(final String title, final String question, final String yesWord)
		{
			questions.add(question);
			titles.add(title);
			yes.add(yesWord);
			return answer;
		}
	}

	@Before
	public void setUp()
	{
		itemManager = mock(ItemManager.class);
		service = mock(PriceService.class);
		prefs = new SidebarViewPanelTest.Prefs();
		asked = new Asked();
	}

	@After
	public void tearDown() throws Exception
	{
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
	}

	private void open(final BankHistorySeries series) throws Exception
	{
		open(series, ViewOptions.DEFAULT);
	}

	/** {@code options} are the switches the status says its figures were computed under: what the rows and the menu read. */
	private void open(final BankHistorySeries series, final ViewOptions options) throws Exception
	{
		if (panel == null)
		{
			onEdt(() ->
			{
				panel = new BankPriceMovementPanel(itemManager, service, prefs, text -> { }, asked);
				panel.setClock(() -> NOW);
			});
			final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
			verify(service).addListener(captor.capture());
			listener = captor.getValue();
		}
		final PriceService.Status published = status(series, VALUE_NOW, NOW - 60_000L);
		when(published.options()).thenReturn(options);
		onEdt(() -> listener.onRows(rows(3), published));
		onEdt(() -> panel.pressView(SidebarView.HISTORY));
	}

	/** 931m on the 25th, 731m on the 26th, nothing on the 27th, 736m today: 200m left on the 26th. */
	private static BankHistorySeries sent()
	{
		return BankHistorySeries.of(Arrays.asList(point(SEP_25, 931 * M), point(SEP_26, 731 * M), point(TODAY, 736 * M)));
	}

	/** 531m on the 25th, 731m on the 26th, 736m today: 200m arrived on the 26th. */
	private static BankHistorySeries got()
	{
		return BankHistorySeries.of(Arrays.asList(point(SEP_25, 531 * M), point(SEP_26, 731 * M), point(TODAY, 736 * M)));
	}

	/** 731m on the 25th and the 26th: the total did not move, so there is nothing to offer. */
	private static BankHistorySeries flat()
	{
		return BankHistorySeries.of(Arrays.asList(point(SEP_25, 731 * M), point(SEP_26, 731 * M), point(TODAY, 736 * M)));
	}

	private static List<String> words(final JPopupMenu menu)
	{
		final List<String> out = new ArrayList<>();
		for (final Component c : menu.getComponents())
		{
			out.add(c instanceof JMenuItem ? ((JMenuItem) c).getText() : c instanceof JSeparator ? "-" : "?");
		}
		return out;
	}

	private static JMenuItem item(final JPopupMenu menu, final int i)
	{
		return (JMenuItem) menu.getComponent(i);
	}

	// ---------------------------------------------------------------------------------------- the right-click menu

	@Test
	public void anUnmarkedDayOffersTheOneClickAmountTheBoxAndTheCut() throws Exception
	{
		open(sent());
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("Sent 200m to my other account", "Another amount...", "-",
				"Reset history from this day"), words(menu));
			assertEquals("the sidebar's menu face", Widgets.sans(12), item(menu, 0).getFont());
			assertNull("a menu item's words are all it has", item(menu, 0).getToolTipText());
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, -200 * M, false);
	}

	/** The eye shut hides the amount here as everywhere: the one-click item still marks the exact amount, but does not say it. */
	@Test
	public void withTheEyeShutTheOneClickItemDoesNotSayTheAmount() throws Exception
	{
		open(sent());
		onEdt(() ->
		{
			panel.applyHideAmounts(true);
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals("the amount is left out, not masked", "Sent to my other account", item(menu, 0).getText());
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, -200 * M, false);
	}

	/** 1.2.3: a day that rose offers "Got 200m from my other account", the size unsigned, and marks the plus amount. */
	@Test
	public void aDayThatRoseOffersGotFromMyOtherAccountAndMarksThePlusAmount() throws Exception
	{
		open(got());
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("Got 200m from my other account", "Another amount...", "-", "Reset history from this day"),
				words(menu));
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, 200 * M, false);
	}

	/** 1.2.3: with the eye shut the amount is left out of both one-click items, not masked, and the click still marks the exact amount. */
	@Test
	public void withTheEyeShutTheGotItemLeavesTheAmountOutToo() throws Exception
	{
		open(got());
		onEdt(() ->
		{
			panel.applyHideAmounts(true);
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals("Got from my other account", item(menu, 0).getText());
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, 200 * M, false);
		assertEquals("Sent to my other account", BankPriceMovementPanel.transferText(-200 * M, true));
		assertEquals("Got from my other account", BankPriceMovementPanel.transferText(200 * M, true));
		assertEquals("Sent 1.5m to my other account", BankPriceMovementPanel.transferText(-1_500_000L, false));
		assertEquals("Got 1.5m from my other account", BankPriceMovementPanel.transferText(1_500_000L, false));
	}

	/** 1.2.3: a day whose total did not move has no offer, so no one-click item; the box item says it may be either way. */
	@Test
	public void aDayThatDidNotMoveHasNoOneClickItemAndTheBoxItemSaysToOrFrom() throws Exception
	{
		open(flat());
		final List<Long> offered = new ArrayList<>();
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("To or from my other account...", "-", "Reset history from this day"), words(menu));
			panel.transferPrompt = (day, initial, whole) ->
			{
				offered.add(initial);
				offered.add(whole);
				return 30 * M;
			};
			item(menu, 0).doClick();
		});
		assertEquals("nothing on offer and nothing moved", Arrays.asList(0L, 0L), offered);
		verify(service).markTransfer(SEP_26, 30 * M, false);
	}

	/** 1.2.3: the undo item follows the stored note's sign, both ways. */
	@Test
	public void theUndoItemFollowsTheNotesSign() throws Exception
	{
		open(sent().withTransfer(SEP_26, new BankHistorySeries.Transfer(-200 * M, false)));
		onEdt(() -> assertEquals("Not sent to my other account", item(panel.historyRowMenu(SEP_26), 0).getText()));
		open(got().withTransfer(SEP_26, new BankHistorySeries.Transfer(200 * M, false)));
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("Not from my other account", "Change the amount...", "-", "Reset history from this day"),
				words(menu));
			item(menu, 0).doClick();
		});
		verify(service).removeTransfer(SEP_26);
		open(sent().withTransfer(SEP_26, new BankHistorySeries.Transfer(-200 * M, true)));
		onEdt(() -> assertEquals("a coins note reads the same way", "Not sent to my other account",
			item(panel.historyRowMenu(SEP_26), 0).getText()));
	}

	/** The amount box answers: what was typed is marked, with the offer's flag; Cancel marks nothing. */
	@Test
	public void theOtherAmountItemMarksWhatTheBoxAnswers() throws Exception
	{
		open(sent());
		final List<Long> offered = new ArrayList<>();
		final Long[] typed = {-150 * M};
		onEdt(() ->
		{
			panel.transferPrompt = (day, initial, whole) ->
			{
				assertEquals(SEP_26, day);
				offered.add(initial);
				offered.add(whole);
				return typed[0];
			};
			item(panel.historyRowMenu(SEP_26), 1).doClick();
		});
		assertEquals("the amount on offer and what the day really changed by", Arrays.asList(-200 * M, -200 * M), offered);
		verify(service).markTransfer(SEP_26, -150 * M, false);

		typed[0] = null;
		onEdt(() -> item(panel.historyRowMenu(SEP_26), 1).doClick());
		verify(service).markTransfer(any(), anyLong(), anyBoolean());
	}

	/** A day the coins moved by nearly all of it offers the exact coins, flagged as coins. */
	@Test
	public void theOneClickAmountIsTheExactCoinsWhenTheyMovedMostOfTheChange() throws Exception
	{
		final long[] before = new long[BankHistoryPoint.CELLS];
		before[BankHistoryPoint.BANK_TRADEABLE] = 600 * M;
		before[BankHistoryPoint.BANK_CASH] = 331 * M;
		final long[] after = new long[BankHistoryPoint.CELLS];
		after[BankHistoryPoint.BANK_TRADEABLE] = 595 * M;
		after[BankHistoryPoint.BANK_CASH] = 131 * M;
		open(BankHistorySeries.of(Arrays.asList(
			new BankHistoryPoint(SEP_25, SidebarViewPanelTest.noon(SEP_25), SidebarViewPanelTest.noon(SEP_25), before, null),
			new BankHistoryPoint(SEP_26, SidebarViewPanelTest.noon(SEP_26), SidebarViewPanelTest.noon(SEP_26), after, null),
			point(TODAY, 736 * M))));
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals("Sent 200m to my other account", item(menu, 0).getText());
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, -200 * M, true);
	}

	@Test
	public void aMarkedDayOffersToTakeItAwayOrChangeIt() throws Exception
	{
		open(sent().withTransfer(SEP_26, new BankHistorySeries.Transfer(-200 * M, true)));
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("Not sent to my other account", "Change the amount...", "-", "Reset history from this day"), words(menu));
			final List<Long> offered = new ArrayList<>();
			panel.transferPrompt = (day, initial, whole) ->
			{
				offered.add(initial);
				return -120 * M;
			};
			item(menu, 1).doClick();
			assertEquals("the box starts as the note, not the corrected change (which a note makes 0)",
				Arrays.asList(-200 * M), offered);
			item(menu, 0).doClick();
		});
		verify(service).markTransfer(SEP_26, -120 * M, true);
		verify(service).removeTransfer(SEP_26);
	}

	/**
	 * 1.2.2 verification fix: a coins note the coins switch leaves out draws no mark, but it is still the day's note - the menu is the
	 * marked one, so the unmarked items cannot replace it with no word (which, with the switch back on, brought the 200m cliff back under
	 * a "sent" tag with the wrong amount), and "Change the amount..." starts from the note and keeps its coins flag. Switched back on,
	 * the row is marked again and the menu is the same.
	 */
	@Test
	public void aCoinsNoteTheSwitchLeavesOutDrawsNoMarkButItsMenuIsTheMarkedOneAndItsBoxStartsOnTheNote() throws Exception
	{
		final BankHistorySeries noted = sent().withTransfer(SEP_26, new BankHistorySeries.Transfer(-150 * M, true));
		open(noted, ViewOptions.DEFAULT.withCountCash(false));
		final List<Long> offered = new ArrayList<>();
		onEdt(() ->
		{
			assertFalse("the row draws no mark", BankHistoryViewTest.rowOf(panel.historyView, SEP_26).model().marked);
			final JPopupMenu menu = panel.historyRowMenu(SEP_26);
			assertEquals(Arrays.asList("Not sent to my other account", "Change the amount...", "-", "Reset history from this day"), words(menu));
			assertEquals("and so does the row's own", words(menu), words(BankHistoryViewTest.rowOf(panel.historyView, SEP_26).menu()));
			panel.transferPrompt = (day, initial, whole) ->
			{
				offered.add(initial);
				return -120 * M;
			};
			item(menu, 1).doClick();
		});
		assertEquals("the note's own amount, the one nobody can see while coins are off", Arrays.asList(-150 * M), offered);
		verify(service).markTransfer(SEP_26, -120 * M, true);
		verify(service, never()).markTransfer(SEP_26, -200 * M, false);

		onEdt(() -> item(panel.historyRowMenu(SEP_26), 0).doClick());
		verify(service).removeTransfer(SEP_26);

		open(noted, ViewOptions.DEFAULT);
		onEdt(() ->
		{
			assertTrue("coins counted again: the row is marked", BankHistoryViewTest.rowOf(panel.historyView, SEP_26).model().marked);
			assertEquals(Arrays.asList("Not sent to my other account", "Change the amount...", "-", "Reset history from this day"),
				words(panel.historyRowMenu(SEP_26)));
		});
	}

	@Test
	public void aDayWithNoReadingAndTheFirstReadingOfferOnlyTheCut() throws Exception
	{
		open(sent());
		onEdt(() ->
		{
			assertEquals("no reading that day", Arrays.asList("Reset history from this day"), words(panel.historyRowMenu(SEP_27)));
			assertEquals("nothing before it to compare with", Arrays.asList("Reset history from this day"),
				words(panel.historyRowMenu(SEP_25)));
		});
	}

	/** The rows hand the panel their day and show what it answers: the same menu as the panel builds for that day. */
	@Test
	public void everyRowAsksThePanelForItsDaysMenu() throws Exception
	{
		open(sent().withTransfer(SEP_26, new BankHistorySeries.Transfer(-200 * M, false)));
		onEdt(() ->
		{
			final BankHistoryDayRow marked = BankHistoryViewTest.rowOf(panel.historyView, SEP_26);
			assertEquals(words(panel.historyRowMenu(SEP_26)), words(marked.menu()));
			assertEquals("Not sent to my other account", item(marked.menu(), 0).getText());
			final BankHistoryDayRow carried = BankHistoryViewTest.rowOf(panel.historyView, SEP_27);
			assertTrue(carried.model().carried);
			assertEquals(Arrays.asList("Reset history from this day"), words(carried.menu()));
			assertEquals("a row that was built without menus has none", null,
				new BankHistoryDayRow(marked.model(), ChangeText.DEFAULT).menu());
		});
	}

	// ---------------------------------------------------------------------------------------- the two cuts

	@Test
	public void resetHistoryFromThisDayAsksThenCuts() throws Exception
	{
		open(sent());
		onEdt(() -> item(panel.historyRowMenu(SEP_26), 3).doClick());
		assertEquals(Arrays.asList("Reset your history before Sat 26 Sep? You can bring it back with Restore history."),
			asked.questions);
		assertEquals(Arrays.asList("Reset history from this day"), asked.titles);
		assertEquals(Arrays.asList("Reset"), asked.yes);
		verify(service, never()).startHistoryFrom(any());

		asked.answer = true;
		onEdt(() -> item(panel.historyRowMenu(SEP_26), 3).doClick());
		verify(service).startHistoryFrom(SEP_26);
	}

	private JButton resetButton()
	{
		return SidebarViewPanelTest.historyButton(panel, "Reset history");
	}

	private JButton restoreButton()
	{
		return SidebarViewPanelTest.historyButton(panel, "Restore history");
	}

	/**
	 * Layout D of the user's picture: the menu's LAST row, after a rule, is "Reset history" hard left and "Restore history" hard
	 * right, small buttons in the OK row's style (the menu's 12 px face, pinned to their words, glue between), with the user's hovers.
	 * "Start fresh" and the rule that followed it are gone from the top.
	 */
	@Test
	public void theHistoryMenuEndsInTheRowOfResetAndRestore() throws Exception
	{
		open(sent());
		onEdt(() ->
		{
			final JPopupMenu menu = panel.historyMenu();
			final Component[] parts = menu.getComponents();
			assertTrue("the chart colour row is first now", parts[0] instanceof SwatchRow);
			assertTrue("a rule before the row", parts[parts.length - 2] instanceof JSeparator);
			final JPanel row = (JPanel) parts[parts.length - 1];
			assertEquals("Reset, the glue, Restore", 3, row.getComponentCount());
			assertSame(resetButton(), row.getComponent(0));
			assertSame(restoreButton(), row.getComponent(2));
			assertEquals("Reset history", resetButton().getText());
			assertEquals("Restore history", restoreButton().getText());
			for (JButton b : Arrays.asList(resetButton(), restoreButton()))
			{
				assertEquals("the menu's face", Widgets.sans(12), b.getFont());
				assertEquals("pinned to the size of its word", b.getPreferredSize(), b.getMaximumSize());
			}
			assertEquals("Start the chart again from today. Nothing is lost.", resetButton().getToolTipText());
			assertEquals("Bring back the days a reset hid.", restoreButton().getToolTipText());

			// Laid out 60 px wider than it asks for: Reset against the row's left edge, Restore against its right, the glue between.
			menu.setSize(menu.getPreferredSize().width + 60, menu.getPreferredSize().height);
			LookRenderer.layoutTree(menu);
			final int left = resetButton().getX();
			final int rightEdge = restoreButton().getX() + restoreButton().getWidth();
			assertTrue("Reset hard left: " + left, left <= BankPriceMovementPanel.ROW_GAP);
			assertTrue("Restore hard right", row.getWidth() - rightEdge <= BankPriceMovementPanel.ROW_GAP);
			assertTrue("the glue between them", restoreButton().getX() > resetButton().getX() + resetButton().getWidth());
			for (Component c : parts)
			{
				assertFalse("no Start fresh anywhere in the menu", c instanceof JMenuItem
					&& ((JMenuItem) c).getText() != null && ((JMenuItem) c).getText().startsWith("Start"));
			}
		});
	}

	@Test
	public void resetHistoryAsksThenCutsAtToday() throws Exception
	{
		open(sent());
		onEdt(() -> resetButton().doClick());
		assertEquals(Arrays.asList("Reset your history? You can bring it back with Restore history."), asked.questions);
		assertEquals(Arrays.asList("Reset history"), asked.titles);
		assertEquals(Arrays.asList("Reset"), asked.yes);
		verify(service, never()).startHistoryFrom(any());

		asked.answer = true;
		onEdt(() -> resetButton().doClick());
		verify(service).startHistoryFrom(TODAY);
	}

	/** A cut made on a stopped panel is not made: the question can outlive the window it was asked over. */
	@Test
	public void aStoppedPanelCutsNothing() throws Exception
	{
		open(sent());
		asked.answer = true;
		onEdt(() ->
		{
			final JButton reset = resetButton();
			panel.stop();
			reset.doClick();
		});
		verify(service, never()).startHistoryFrom(any());
	}

	// ---------------------------------------------------------------------------------------- Restore history for the player's cut

	private static BankHistorySeries cutAt(final LocalDate day)
	{
		return sent().withFreshFrom(day).withFreshWhy(BankHistorySeries.WHY_PLAYER);
	}

	/**
	 * Part 5: the settings menu's "Include days before ..." item is the 1.0.9 / 1.1.1 reasons' alone - never for a player's reset,
	 * whose way back is Restore history (one account's, not the shared box's).
	 */
	@Test
	public void theSettingsMenuHasNoIncludeItemForAPlayersResetOnlyForTheOtherReasons() throws Exception
	{
		open(cutAt(SEP_26));
		onEdt(() ->
		{
			assertNull("no item for a reset the player made", legacyItem());
			assertTrue("and Restore history is on", restoreButton().isEnabled());
		});
		open(cutAt(SEP_27));
		onEdt(() -> assertNull("however the cut moves", legacyItem()));

		open(sent().withFreshFrom(SEP_26));
		onEdt(() ->
		{
			assertNotNull("the 1.0.9 reason keeps its item", legacyItem());
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, legacyItem().getText());
		});
		open(sent().judged(new BankHistorySeries.PlaceholderCheck(SEP_26, true)));
		onEdt(() -> assertEquals("and the placeholder reason its own words", BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT,
			legacyItem().getText()));
		open(cutAt(SEP_26));
		onEdt(() -> assertNull("a record that goes back to a player's reset loses the item again", legacyItem()));
	}

	@Test
	public void theIIconSaysWhyForThePlayersResetToo() throws Exception
	{
		open(cutAt(SEP_26));
		onEdt(() ->
		{
			final JLabel info = infoIcon();
			assertNotNull("the player's reset shows the \"i\" like a restart does", info);
			assertEquals("<html>Days before 26 Sep are hidden because<br>you reset your history. Restore history<br>"
				+ "in the History options brings them back.</html>", info.getToolTipText());
		});
		open(cutAt(SEP_27));
		onEdt(() -> assertTrue("and follows the day", infoIcon().getToolTipText().contains("27 Sep")));
		open(sent());
		onEdt(() -> assertNull("no cut, no icon", infoIcon()));
	}

	/** Restore history is on for every reason a day is hidden, and for none else: no day hidden, or every hidden day shown. */
	@Test
	public void restoreIsOnForAPlayersResetAndGreyWithNothingHidden() throws Exception
	{
		open(sent());
		onEdt(() -> assertFalse("nothing hidden", restoreButton().isEnabled()));
		open(cutAt(SEP_26));
		onEdt(() -> assertTrue("a reset hides SEP_25", restoreButton().isEnabled()));
		open(cutAt(SEP_25));
		onEdt(() -> assertFalse("a cut at the first reading hides nothing", restoreButton().isEnabled()));
		open(cutAt(SEP_26).withRestored(true));
		onEdt(() -> assertFalse("the player restored it: nothing to restore", restoreButton().isEnabled()));
		open(sent().withFreshFrom(SEP_26));
		onEdt(() -> assertTrue("the 1.0.9 days, hidden", restoreButton().isEnabled()));
		onEdt(() -> panel.setIncludeLegacy(true));
		onEdt(() -> assertFalse("shown by the shared box: nothing to restore", restoreButton().isEnabled()));
	}

	/**
	 * The player hid those days themselves: Restore history asks nothing and tells the service, which restores THIS account's days; the
	 * shared config key is not written. Until the service answers with the restored record nothing on screen moves.
	 */
	@Test
	public void restoreHistoryAsksNothingForAPlayersResetAndTellsTheServiceNotTheSharedBox() throws Exception
	{
		open(cutAt(SEP_26));
		onEdt(() -> restoreButton().doClick());
		assertTrue("no question", asked.questions.isEmpty());
		verify(service).restoreHistory();
		assertTrue("the shared key is not written", prefs.legacySaves.isEmpty());
		onEdt(() -> assertTrue("the service has not answered yet", restoreButton().isEnabled()));

		open(cutAt(SEP_26).withRestored(true));
		onEdt(() ->
		{
			assertFalse("nothing left to restore", restoreButton().isEnabled());
			assertNull("every day is back: the \"i\" goes", infoIcon());
			assertEquals("25 Sep is shown again", SEP_25, BankHistoryViewTest.rowOf(panel.historyView, SEP_25).model().day());
			assertNull("and the settings menu has no item to tick", legacyItem());
		});
		assertTrue(prefs.legacySaves.isEmpty());
		verify(service, never()).setOptions(any());
	}

	/** The bridge's {@code legacy=on} and the "i" icon take the same road as the button - and OFF is nothing for a reset the player made. */
	@Test
	public void theIIconAndTheStateAimedPressRestoreAPlayersResetWhateverTheSharedBoxSays() throws Exception
	{
		open(cutAt(SEP_26));
		onEdt(() -> panel.setIncludeLegacy(true));
		onEdt(() -> press(infoIcon(), MouseEvent.BUTTON1));
		verify(service, times(1)).restoreHistory();
		onEdt(() -> panel.pressLegacy(false));
		verify(service, times(1)).restoreHistory();
		onEdt(() -> panel.pressLegacy(true));
		verify(service, times(2)).restoreHistory();
		assertTrue("no question and no write of the shared key", asked.questions.isEmpty() && prefs.legacySaves.isEmpty());
	}

	/** A new reset after a restore hides from the new day, with no help from the shared box: the record's mark simply comes off. */
	@Test
	public void resetAgainAfterARestoreHidesFromTheNewDayAndTheSharedBoxIsNotInvolved() throws Exception
	{
		open(cutAt(SEP_26).withRestored(true));
		assertEquals("every day is shown", 3, readings());
		onEdt(() -> assertFalse(restoreButton().isEnabled()));

		open(cutAt(SEP_27));
		assertEquals("the new cut is drawn: today alone", 1, readings());
		onEdt(() ->
		{
			assertTrue("and Restore is on again", restoreButton().isEnabled());
			assertNotNull("with its reason", infoIcon());
		});
		assertTrue("nothing was written to the shared key", prefs.legacySaves.isEmpty());
	}

	/** Two accounts on one panel: each status carries ITS account's record, and what is hidden is read off that record alone. */
	@Test
	public void aPlayersResetIsReadOffTheRecordOfTheAccountInTheStatusAndNoOther() throws Exception
	{
		final BankHistorySeries main = cutAt(SEP_26).withRestored(true);
		final BankHistorySeries alt = cutAt(SEP_26);
		open(main);
		assertEquals(3, readings());
		onEdt(() -> assertFalse(restoreButton().isEnabled()));
		open(alt);
		assertEquals("the alt's own days stay hidden", 2, readings());
		onEdt(() ->
		{
			assertTrue("Restore is still on for the alt", restoreButton().isEnabled());
			assertNotNull(infoIcon());
		});
		open(main);
		assertEquals("and back on the main: whole again", 3, readings());
		onEdt(() ->
		{
			assertFalse(restoreButton().isEnabled());
			assertNull(infoIcon());
		});
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** The shared box is the 1.0.9 / 1.1.1 reasons' switch: it neither shows a player's reset nor is moved by it. */
	@Test
	public void theSharedBoxNeitherShowsAPlayersResetNorIsMovedByIt() throws Exception
	{
		open(cutAt(SEP_26));
		onEdt(() -> panel.setIncludeLegacy(true));
		assertEquals("ticked, and the player's reset is still hidden", 2, readings());
		onEdt(() -> assertTrue("Restore is on", restoreButton().isEnabled()));

		open(sent().withFreshFrom(SEP_26));
		assertEquals("another account's 1.0.9 days are shown by that same box", 3, readings());
		open(cutAt(SEP_26));
		assertEquals("the reset account stays hidden, the box on", 2, readings());
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** For the 1.0.9 and 1.1.1 reasons the existing warning is asked first, in its own words; no answer changes nothing. */
	@Test
	public void restoreHistoryStillAsksTheWarningForTheAutomaticRestarts() throws Exception
	{
		open(sent().withFreshFrom(SEP_26));
		onEdt(() -> restoreButton().doClick());
		assertEquals("the 1.0.9 warning, unchanged", Arrays.asList(BankPriceMovementPanel.LEGACY_ASK), asked.questions);
		assertTrue("answered no: nothing written", prefs.legacySaves.isEmpty());
		onEdt(() -> assertTrue("and still restorable", restoreButton().isEnabled()));

		asked.answer = true;
		onEdt(() -> restoreButton().doClick());
		assertEquals(Arrays.asList(true), prefs.legacySaves);
		onEdt(() -> assertFalse(restoreButton().isEnabled()));

		final BankHistorySeries placeholders = sent().judged(new BankHistorySeries.PlaceholderCheck(SEP_26, true));
		asked.questions.clear();
		asked.answer = false;
		open(placeholders);
		onEdt(() -> panel.setIncludeLegacy(false));
		onEdt(() -> restoreButton().doClick());
		assertEquals("the 1.1.1 warning, unchanged", Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), asked.questions);
		verify(service, never()).restoreHistory();
	}

	// ---------------------------------------------------------------------------------------- a reset over an automatic cut (verification fix)

	/**
	 * The record of a player who reset from 27 Sep over the automatic cut {@code under} (25 Sep when 1.0.9's, 26 Sep for the placeholders):
	 * the reset hid 25 and 26 Sep, and the days before the automatic cut read wrong.
	 */
	private static BankHistorySeries over(final BankHistorySeries.AutoCut under)
	{
		return cutAt(SEP_27).withAutoCut(under);
	}

	private static BankHistorySeries overPlaceholders()
	{
		return over(new BankHistorySeries.AutoCut(SEP_26, BankHistorySeries.WHY_PLACEHOLDERS));
	}

	/**
	 * Restore history for a reset that stands over a 1.1.1 cut: the days those placeholders inflated come back too, so the warning of
	 * that reason is asked FIRST, in its own words. No answer changes nothing - the service is not told, the shared box is not written,
	 * Restore stays on. A yes restores the player's days through the service AND puts the shared box on, for the days before the cut. The
	 * record the service then answers stands as the automatic cut: its tick is back in the settings menu, and unticking hides those days
	 * again with Restore on for them, asking the same question.
	 */
	@Test
	public void restoreOverAPlaceholderCutAsksItsWarningFirstAndAYesPutsTheSharedBoxOnToo() throws Exception
	{
		open(overPlaceholders());
		assertEquals("today alone: the reset hid 25 and 26 Sep", 1, readings());
		onEdt(() ->
		{
			assertTrue(restoreButton().isEnabled());
			assertNull("no tick while the player's own reset is what hides", legacyItem());
			restoreButton().doClick();
		});
		assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), asked.questions);
		verify(service, never()).restoreHistory();
		assertTrue("answered no: the shared box is not written", prefs.legacySaves.isEmpty());
		onEdt(() -> assertTrue("and still restorable", restoreButton().isEnabled()));
		assertEquals(1, readings());

		asked.answer = true;
		asked.questions.clear();
		onEdt(() -> restoreButton().doClick());
		assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK), asked.questions);
		verify(service, times(1)).restoreHistory();
		assertEquals("the box on, for the days before the cut", Arrays.asList(true), prefs.legacySaves);

		// What the service answers: the record restored, now standing as the placeholder cut.
		open(overPlaceholders().withRestored(true));
		assertEquals("every day: the box is on", 3, readings());
		onEdt(() ->
		{
			assertFalse(restoreButton().isEnabled());
			assertNotNull("the 1.1.1 tick is back, ticked", legacyItem());
			assertEquals(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_TEXT, legacyItem().getText());
			assertTrue(legacyItem().isSelected());
			panel.pressLegacy(false);
		});
		assertEquals("unticked: 25 Sep is hidden again by the placeholder cut", 2, readings());
		assertEquals(Arrays.asList(true, false), prefs.legacySaves);
		asked.questions.clear();
		onEdt(() ->
		{
			assertTrue("Restore is on for the days the tick hid", restoreButton().isEnabled());
			restoreButton().doClick();
		});
		assertEquals("the same warning, by the shared box's own road", Arrays.asList(BankPriceMovementPanel.LEGACY_PLACEHOLDERS_ASK),
			asked.questions);
		assertEquals(Arrays.asList(true, false, true), prefs.legacySaves);
		verify(service, times(1)).restoreHistory();
		assertEquals(3, readings());
	}

	/** The 1.0.9 reason's own words, in its own question - and nothing asked when the reset hides no day the box owns. */
	@Test
	public void restoreOverAnOnePointZeroNineCutAsksItsWarningAndAResetThatHidesNoneOfItsDaysAsksNothing() throws Exception
	{
		asked.answer = true;
		open(over(new BankHistorySeries.AutoCut(SEP_26, null)));
		onEdt(() -> restoreButton().doClick());
		assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_ASK), asked.questions);
		verify(service, times(1)).restoreHistory();
		assertEquals(Arrays.asList(true), prefs.legacySaves);

		// A cut that began at the first reading has no day before it: only the player's own days wait, and they ask nothing.
		asked.questions.clear();
		prefs.legacySaves.clear();
		onEdt(() -> panel.setIncludeLegacy(false));
		open(over(new BankHistorySeries.AutoCut(SEP_25, null)));
		onEdt(() -> restoreButton().doClick());
		assertTrue(asked.questions.isEmpty());
		verify(service, times(2)).restoreHistory();
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** The shared box already shows the old days: a restore asks nothing, and leaves it as it is. */
	@Test
	public void restoreOverAnAutomaticCutAsksNothingWhileTheSharedBoxAlreadyShowsItsDays() throws Exception
	{
		open(overPlaceholders());
		onEdt(() -> panel.setIncludeLegacy(true));
		assertEquals("ticked, and the player's reset is still what hides", 1, readings());
		onEdt(() -> restoreButton().doClick());
		assertTrue(asked.questions.isEmpty());
		verify(service).restoreHistory();
		assertTrue(prefs.legacySaves.isEmpty());
	}

	/** The "i" icon says what hides the days NOW: the player's own words until they restore, the automatic reason's after. */
	@Test
	public void theIIconNamesTheReasonThatHidesTheDaysNow() throws Exception
	{
		open(overPlaceholders());
		onEdt(() -> assertTrue(infoTexts().contains("<html>Days before 27 Sep are hidden because<br>you reset your history. Restore history<br>"
			+ "in the History options brings them back.</html>")));
		open(overPlaceholders().withRestored(true));
		onEdt(() ->
		{
			assertTrue("restored: the placeholder reason", infoTexts().contains(BankPriceMovementPanel.LEGACY_INFO_TIP));
			for (final String text : infoTexts())
			{
				assertFalse("and no longer the player's words: " + text, text.contains("you reset your history"));
			}
		});
		onEdt(() -> panel.setIncludeLegacy(true));
		onEdt(() ->
		{
			assertFalse("shown by the box: no icon", infoTexts().contains(BankPriceMovementPanel.LEGACY_INFO_TIP));
			assertFalse(infoTexts().contains("<html>Days before 27 Sep are hidden because<br>you reset your history. Restore history<br>"
				+ "in the History options brings them back.</html>"));
		});
	}

	private List<String> infoTexts()
	{
		final List<String> out = new ArrayList<>();
		for (final Component c : panel.viewCaptionRow().getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getToolTipText() != null)
			{
				out.add(((JLabel) c).getToolTipText());
			}
		}
		return out;
	}

	/** How many readings the History tab draws: the days that are not hidden. */
	private int readings() throws Exception
	{
		final int[] out = new int[1];
		onEdt(() -> out[0] = ((Number) PanelState.bankHistoryState(panel).get("readings")).intValue());
		return out[0];
	}

	private JMenuItem legacyItem()
	{
		for (final Component c : panel.heroMenu().getComponents())
		{
			if (c instanceof JMenuItem && ((JMenuItem) c).getText().startsWith("Include days before"))
			{
				return (JMenuItem) c;
			}
		}
		return null;
	}

	private JLabel infoIcon()
	{
		for (final Component c : panel.viewCaptionRow().getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getToolTipText() != null && ((JLabel) c).getToolTipText().contains("reset your history"))
			{
				return (JLabel) c;
			}
		}
		return null;
	}
}
