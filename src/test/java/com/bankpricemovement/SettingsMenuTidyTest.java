package com.bankpricemovement;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.AbstractButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JRadioButtonMenuItem;
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
import static com.bankpricemovement.SidebarViewPanelTest.press;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part A (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the settings menu
 * tidied - "Refresh prices now" and the start-tab group out, a "Net worth chart" caption with "Include days before
 * v1.0.9" under it in place of the History tab's check box - and the sidebar remembering the tab used last. Frozen
 * tests A1-A6 (A4, the hidden config item, is {@code StartTabTest.startTabIsHiddenAndItsKeyIsStillStartTab}, which
 * already reads the config interface).
 */
public class SettingsMenuTidyTest
{
	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	/** The config as a memory: what the start tab and the legacy switch are stored as, and every write of them. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		SidebarView stored;
		final List<SidebarView> saves = new ArrayList<>();
		final List<Boolean> legacySaves = new ArrayList<>();

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
		public SidebarView loadStartTab()
		{
			return stored;
		}

		@Override
		public void saveStartTab(SidebarView tab)
		{
			saves.add(tab);
		}

		@Override
		public void saveIncludeLegacy(boolean include)
		{
			legacySaves.add(include);
		}
	}

	/** A prompt that answers {@code answer} (changeable) and keeps every question it was asked. */
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

	@After
	public void tearDown() throws Exception
	{
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
	}

	private void build(Memory prefs, Asked prompt) throws Exception
	{
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs, text -> { }, prompt);
			panel.setClock(() -> NOW);
		});
		final org.mockito.ArgumentCaptor<PriceService.Listener> captor =
			org.mockito.ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	private void load(@Nullable BankHistorySeries record) throws Exception
	{
		final PriceService.Status s = status(record, VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	/** Four readings before 1.0.9 and two since it, the fresh start standing on yesterday's. */
	private static BankHistorySeries withLegacyDays()
	{
		return series(TODAY.minusDays(7), TODAY.minusDays(6), TODAY.minusDays(4), TODAY.minusDays(3),
			TODAY.minusDays(1), TODAY).withFreshFrom(TODAY.minusDays(1));
	}

	/** A record with nothing before 1.0.9 in it. */
	private static BankHistorySeries withoutLegacyDays()
	{
		return series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
	}

	private static List<String> textsIn(Component root)
	{
		final List<String> out = new ArrayList<>();
		for (Component c : walk(root))
		{
			if (c instanceof JLabel)
			{
				out.add(((JLabel) c).getText());
			}
			else if (c instanceof AbstractButton)
			{
				out.add(((AbstractButton) c).getText());
			}
		}
		return out;
	}

	private Widgets.Toggle toggle()
	{
		for (Component row : panel.header().getComponents())
		{
			final Widgets.Toggle t = find(row, Widgets.Toggle.class);
			if (t != null)
			{
				return t;
			}
		}
		throw new AssertionError("no toggle in the header");
	}

	private JCheckBoxMenuItem legacyItem()
	{
		for (Component c : panel.heroMenu().getComponents())
		{
			if (c instanceof JCheckBoxMenuItem && BankPriceMovementPanel.LEGACY_TEXT.equals(((JCheckBoxMenuItem) c).getText()))
			{
				return (JCheckBoxMenuItem) c;
			}
		}
		return null;
	}

	private int readings()
	{
		return (Integer) panel.bankHistoryState().get("readings");
	}

	// ---------------------------------------------------------------- A1: the menu, component by component

	/**
	 * A1: without days before 1.0.9 the menu is header, rule, the three Show items, (since 1.1.0 part J) a rule, (since part B)
	 * the Up colour and Down colour rows, (since parts H and J) the "Colour presets" caption and its rows (Classic, 2h,
	 * Colour-blind, Slot 1, the save row), rule, Use live prices, the four Include items, rule, the preset row, Show hover
	 * text, rule, the "Net worth chart" caption, (since part C) Single chart colour, the OK row; with such days the
	 * Include-days item stands after Single chart colour (after the caption itself until part C).
	 * (Updated for part J by index and count only: the rule above Up colour and Slot 1 with its save row moved what
	 * follows them; no assertion was weakened.)
	 */
	@Test
	public void a1_theMenuReadsInOrderWithoutDaysBeforeV109AndTheItemFollowsTheCaptionWithThem() throws Exception
	{
		build(new Memory(), new Asked(true));
		load(withoutLegacyDays());
		onEdt(() ->
		{
			final Component[] c = panel.heroMenu().getComponents();
			// 1.1.0 part B: the two colour rows stand between the Show items and the second rule - two more components;
			// part C: Single chart colour stands under the caption - one more; part H: the presets' caption and its three
			// rows stand under the colour rows - four more; part J: a rule above the colour rows, and Slot 1 and the save
			// row under the presets - three more.
			assertEquals(27, c.length);
			assertFalse("the header is neither a rule nor an item", c[0] instanceof JSeparator
				|| c[0] instanceof AbstractButton);
			assertTrue(c[1] instanceof JSeparator);
			assertSame(panel.showValueItem(), c[2]);
			assertSame(panel.showGpItem(), c[3]);
			assertSame(panel.showPctItem(), c[4]);
			assertTrue("part J: the colour block has a rule of its own", c[5] instanceof JSeparator);
			assertEquals(BankPriceMovementPanel.UP_COLOUR_TEXT, ((javax.swing.JMenuItem) c[6]).getText());
			assertEquals(BankPriceMovementPanel.DOWN_COLOUR_TEXT, ((javax.swing.JMenuItem) c[7]).getText());
			assertEquals("Colour presets", find(c[8], JLabel.class).getText());
			assertTrue(c[9] instanceof SetRow && c[10] instanceof SetRow && c[11] instanceof SetRow && c[12] instanceof SetRow
				&& c[13] instanceof SetRow);
			assertTrue(c[14] instanceof JSeparator);
			assertSame(panel.livePricesItem(), c[15]);
			assertSame(panel.countCashItem(), c[16]);
			assertSame(panel.countUntradeablesItem(), c[17]);
			assertSame(panel.countInventoryItem(), c[18]);
			assertSame(panel.countGrandExchangeItem(), c[19]);
			assertTrue(c[20] instanceof JSeparator);
			assertSame(panel.presetRow(), c[21]);
			assertSame(panel.showHoverTextItem(), c[22]);
			assertTrue(c[23] instanceof JSeparator);
			final JLabel caption = find(c[24], JLabel.class);
			assertNotNull(caption);
			assertEquals("Net worth chart", caption.getText());
			assertEquals(BankPriceMovementPanel.NET_WORTH_CHART_TEXT, caption.getText());
			final JLabel presets = find(panel.presetRow(), JLabel.class);
			assertEquals("the caption looks like the presets'", presets.getFont(), caption.getFont());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, caption.getForeground());
			assertEquals(presets.getForeground(), caption.getForeground());
			assertNull("no hover of its own", caption.getToolTipText());
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, ((JCheckBoxMenuItem) c[25]).getText());
			assertSame(panel.okRow(), c[26]);
			assertNull("no item yet", legacyItem());
		});

		load(withLegacyDays());
		onEdt(() ->
		{
			final Component[] c = panel.heroMenu().getComponents();
			assertEquals(28, c.length);
			assertEquals("Net worth chart", find(c[24], JLabel.class).getText());
			assertEquals(BankPriceMovementPanel.SINGLE_CHART_COLOUR_TEXT, ((JCheckBoxMenuItem) c[25]).getText());
			assertSame(legacyItem(), c[26]);
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, ((JCheckBoxMenuItem) c[26]).getText());
			assertEquals(Widgets.sans(12), c[26].getFont());
			assertSame("the OK row is still last", panel.okRow(), c[27]);
		});
	}

	// ---------------------------------------------------------------- A2: what is gone

	/** A2: nothing in the menu says "Refresh prices now" or "Tab to open on startup", and no radio item exists. */
	@Test
	public void a2_noComponentOfTheMenuCarriesRefreshPricesNowOrTabToOpenOnStartupAndNoRadioItemExists() throws Exception
	{
		build(new Memory(), new Asked(true));
		for (final BankHistorySeries record : Arrays.asList(withoutLegacyDays(), withLegacyDays()))
		{
			load(record);
			onEdt(() ->
			{
				for (final String text : textsIn(panel.heroMenu()))
				{
					assertFalse("no Refresh item: " + text, text != null && text.contains("Refresh prices now"));
					assertFalse("no start-tab caption: " + text, text != null && text.contains("Tab to open on startup"));
				}
				for (final Component c : walk(panel.heroMenu()))
				{
					assertFalse("no radio item: " + c,
						c instanceof JRadioButtonMenuItem || c instanceof JRadioButton);
				}
			});
		}
	}

	// ---------------------------------------------------------------- A3: the last tab

	/**
	 * A3: pressing the toggle onto Net Worth History stores HISTORY once; pressing Items stores ITEMS once; pressing the
	 * tab already lit stores nothing; a panel whose prefs answer HISTORY opens on History.
	 */
	@Test
	public void a3_theTogglePressStoresTheTabItLandsOnAndAPanelOpensOnWhatIsStored() throws Exception
	{
		final Memory prefs = new Memory();
		build(prefs, new Asked(true));
		load(withoutLegacyDays());
		onEdt(() ->
		{
			final Component items = toggle().getComponent(0);
			final Component history = toggle().getComponent(1);
			assertEquals(SidebarView.ITEMS, panel.view());
			press(items, MouseEvent.BUTTON1);
			assertTrue("Items is already lit: nothing stored", prefs.saves.isEmpty());

			press(history, MouseEvent.BUTTON1);
			assertEquals(SidebarView.HISTORY, panel.view());
			assertEquals("HISTORY once", Arrays.asList(SidebarView.HISTORY), prefs.saves);
			press(history, MouseEvent.BUTTON1);
			panel.pressView(SidebarView.HISTORY);
			assertEquals("the tab already lit stores nothing", 1, prefs.saves.size());

			press(items, MouseEvent.BUTTON1);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertEquals("ITEMS once", Arrays.asList(SidebarView.HISTORY, SidebarView.ITEMS), prefs.saves);
			panel.pressView(SidebarView.ITEMS);
			assertEquals(2, prefs.saves.size());
			assertEquals("the panel's own memory of it", SidebarView.ITEMS, panel.startTab());
		});
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());

		// A panel whose prefs answer HISTORY opens on History - and writes nothing to say so.
		onEdt(() -> panel.stop());
		final Memory stored = new Memory();
		stored.stored = SidebarView.HISTORY;
		build(stored, new Asked(true));
		load(withoutLegacyDays());
		onEdt(() ->
		{
			assertEquals(SidebarView.HISTORY, panel.view());
			assertTrue(toggle().isLit(1));
			assertEquals(BankPriceMovementPanel.CARD_HISTORY, panel.card());
		});
		assertTrue(stored.saves.isEmpty());
	}

	// ---------------------------------------------------------------- A5: the History header

	/** A5: the History tab's header never holds the check-box row, with or without days before 1.0.9. */
	@Test
	public void a5_theHistoryTabsHeaderNeverHoldsTheCheckBoxRow() throws Exception
	{
		build(new Memory(), new Asked(true));
		for (final BankHistorySeries record : Arrays.asList(withoutLegacyDays(), withLegacyDays()))
		{
			load(record);
			onEdt(() ->
			{
				panel.pressView(SidebarView.HISTORY);
				assertEquals("only the card and the toggle strip", 2, panel.header().getComponentCount());
				assertSame(panel.hero(), panel.header().getComponent(0));
				for (final Component c : walk(panel.header()))
				{
					final String text = c instanceof JLabel ? ((JLabel) c).getText()
						: c instanceof AbstractButton ? ((AbstractButton) c).getText() : null;
					assertFalse("no check box row in the header: " + text,
						BankPriceMovementPanel.LEGACY_TEXT.equals(text));
				}
				panel.pressView(SidebarView.ITEMS);
				for (final Component c : walk(panel.header()))
				{
					assertFalse(c instanceof JLabel && BankPriceMovementPanel.LEGACY_TEXT.equals(((JLabel) c).getText()));
				}
			});
		}
	}

	// ---------------------------------------------------------------- A6: the item's press

	/** A6: the item turns the days on only after the prompt agrees, and off without asking. */
	@Test
	public void a6_pressingTheItemTurnsItOnOnlyAfterThePromptAgreesAndOffWithoutAsking() throws Exception
	{
		final Memory prefs = new Memory();
		final Asked prompt = new Asked(false);
		build(prefs, prompt);
		load(withLegacyDays());
		onEdt(() ->
		{
			// The History view draws the readings, so it is the one showing (setView, which stores nothing).
			panel.setView(SidebarView.HISTORY);
			final JCheckBoxMenuItem item = legacyItem();
			assertNotNull(item);
			assertFalse(item.isSelected());
			assertEquals("the fresh days only", 2, readings());

			// Declined: asked, and nothing changes - the tick goes back, nothing is written.
			item.doClick(0);
			assertEquals(Arrays.asList(BankPriceMovementPanel.LEGACY_ASK), prompt.questions);
			assertFalse("the tick stays off", item.isSelected());
			assertEquals(2, readings());
			assertTrue(prefs.legacySaves.isEmpty());

			// Agreed: asked again, then on and written once.
			prompt.answer = true;
			item.doClick(0);
			assertEquals(2, prompt.questions.size());
			assertTrue(item.isSelected());
			assertEquals("every reading", 6, readings());
			assertEquals(Arrays.asList(true), prefs.legacySaves);

			// Off: asks nothing, writes false.
			item.doClick(0);
			assertEquals("turning it off asked nothing", 2, prompt.questions.size());
			assertFalse(item.isSelected());
			assertEquals(2, readings());
			assertEquals(Arrays.asList(true, false), prefs.legacySaves);
		});
		verify(service, never()).setOptions(any());
	}

	/**
	 * The menu is synced as it opens as well as with every status: a record that changed under the drawn status takes
	 * the item out when the menu next opens, and puts it back the same way.
	 */
	@Test
	public void theItemFollowsTheRecordAsTheMenuOpens() throws Exception
	{
		build(new Memory(), new Asked(true));
		final PriceService.Status s = status(withLegacyDays(), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
		onEdt(() -> assertNotNull(legacyItem()));

		when(s.bankHistory()).thenReturn(BankHistorySeries.EMPTY);
		onEdt(() ->
		{
			assertNotNull("nothing has told the menu yet", legacyItem());
			opens();
			assertNull("the open synced it", legacyItem());
			assertEquals(27, panel.heroMenu().getComponentCount());
		});

		when(s.bankHistory()).thenReturn(withLegacyDays());
		onEdt(() ->
		{
			opens();
			assertNotNull(legacyItem());
			assertEquals(28, panel.heroMenu().getComponentCount());
		});
	}

	/** What Swing does just before it lays a popup out: the menu's will-become-visible event. */
	private void opens()
	{
		for (javax.swing.event.PopupMenuListener l : panel.heroMenu().getPopupMenuListeners())
		{
			l.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(panel.heroMenu()));
		}
	}
}
