package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Addendum AU's start-tab setting, "Tab to open on startup" (the user, 2026-09-29): the sixteenth config item, the
 * tab the sidebar opens on - Items unless it says Net Worth History. The player's choice, not the last tab used: the
 * toggle writes nothing, the settings menu's two dots write the key, and neither switches the tab that is showing.
 */
public class StartTabTest
{
	private final Gson gson = new Gson();
	private BankPriceMovementPanel panel;

	/** The config as a memory: what the start tab is, and every write of it. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		SidebarView stored;
		final List<SidebarView> saves = new ArrayList<>();
		final List<Boolean> foldSaves = new ArrayList<>();

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
		public void saveFoldOpen(boolean open)
		{
			foldSaves.add(open);
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

	private PriceService service()
	{
		final PriceService service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		return service;
	}

	private PriceService.Listener build(Memory prefs, PriceService service) throws Exception
	{
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			panel.setClock(() -> NOW);
		});
		final org.mockito.ArgumentCaptor<PriceService.Listener> captor =
			org.mockito.ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		return captor.getValue();
	}

	private void loadBank(PriceService.Listener listener) throws Exception
	{
		final PriceService.Status s = status(series(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows(3), s));
	}

	private String card()
	{
		return gson.fromJson(panel.describe(), JsonObject.class).get("card").getAsString();
	}

	private List<JRadioButtonMenuItem> dots()
	{
		final List<JRadioButtonMenuItem> out = new ArrayList<>();
		for (Component c : panel.heroMenu().getComponents())
		{
			if (c instanceof JRadioButtonMenuItem)
			{
				out.add((JRadioButtonMenuItem) c);
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

	private void assertDot(SidebarView on)
	{
		final List<JRadioButtonMenuItem> dots = dots();
		assertEquals(2, dots.size());
		assertEquals(on == SidebarView.ITEMS, dots.get(0).isSelected());
		assertEquals(on == SidebarView.HISTORY, dots.get(1).isSelected());
		assertEquals(on, panel.startTab());
	}

	// ---------------------------------------------------------------- the setting

	@Test
	public void theDefaultIsItemsAndTheItemIsTheSixteenthAndLast() throws Exception
	{
		final BankPriceMovementConfig config = new BankPriceMovementConfig()
		{
		};
		assertEquals(SidebarView.ITEMS, config.startTab());
		final Method m = BankPriceMovementConfig.class.getMethod("startTab");
		final ConfigItem item = m.getAnnotation(ConfigItem.class);
		assertEquals("startTab", item.keyName());
		assertEquals("Tab to open on startup", item.name());
		assertEquals("Which tab the sidebar shows when the plugin starts.", item.description());
		assertEquals(15, item.position());
		assertEquals(SidebarView.class, m.getReturnType());
		int items = 0;
		for (Method other : BankPriceMovementConfig.class.getMethods())
		{
			final ConfigItem o = other.getAnnotation(ConfigItem.class);
			if (o != null && other.getParameterCount() == 0)
			{
				items++;
				assertTrue(o.position() <= 15);
			}
		}
		assertEquals("sixteen items", 16, items);
		assertEquals("the same sentence in the menu's hover", item.description(), BankPriceMovementPanel.START_TAB_TIP);
		assertEquals(item.name(), BankPriceMovementPanel.START_TAB_TEXT);
	}

	@Test
	public void aBarePrefsHasNothingStoredAndWritesNothing()
	{
		final BankPriceMovementPanel.Prefs bare = new BankPriceMovementPanel.Prefs()
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
		};
		assertNull(bare.loadStartTab());
		bare.saveStartTab(SidebarView.HISTORY);
	}

	// ---------------------------------------------------------------- the panel opens on it

	@Test
	public void aFreshPanelOpensOnItemsWithItsDotThere() throws Exception
	{
		final Memory prefs = new Memory();
		final PriceService.Listener l = build(prefs, service());
		loadBank(l);
		onEdt(() ->
		{
			assertEquals(SidebarView.ITEMS, panel.view());
			assertEquals(BankPriceMovementPanel.CARD_LIST, card());
			assertTrue(toggle().isLit(0));
			assertDot(SidebarView.ITEMS);
		});
		assertTrue(prefs.saves.isEmpty());
	}

	@Test
	public void aStoredHistoryOpensOnNetWorthHistoryAndDrawsItOnceABankIsLoaded() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.stored = SidebarView.HISTORY;
		final PriceService.Listener l = build(prefs, service());
		onEdt(() ->
		{
			assertEquals(SidebarView.HISTORY, panel.view());
			assertDot(SidebarView.HISTORY);
		});
		loadBank(l);
		onEdt(() ->
		{
			assertTrue(toggle().isLit(1));
			assertEquals(SidebarView.HISTORY, panel.view());
			assertEquals(BankPriceMovementPanel.CARD_HISTORY, card());
			assertEquals(BankPriceMovementPanel.HISTORY_CAPTION, captionText());
		});
		assertTrue("reading the setting writes nothing", prefs.saves.isEmpty());
	}

	private String captionText()
	{
		return find(panel.header(), JLabel.class) == null ? null : findText(panel.header(),
			BankPriceMovementPanel.HISTORY_CAPTION);
	}

	private static String findText(Container root, String text)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				return text;
			}
			if (c instanceof Container)
			{
				final String found = findText((Container) c, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- the toggle and the dots

	@Test
	public void theTogglePressedEitherWayWritesNothingAndLeavesTheDotsWhereTheyWere() throws Exception
	{
		final Memory prefs = new Memory();
		prefs.stored = SidebarView.HISTORY;
		final PriceService.Listener l = build(prefs, service());
		loadBank(l);
		onEdt(() ->
		{
			final Component items = toggle().getComponent(0);
			final Component history = toggle().getComponent(1);
			press(items, MouseEvent.BUTTON1);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertDot(SidebarView.HISTORY);
			press(history, MouseEvent.BUTTON1);
			assertEquals(SidebarView.HISTORY, panel.view());
			panel.pressView(SidebarView.ITEMS);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertDot(SidebarView.HISTORY);
		});
		assertTrue("the toggle writes nothing", prefs.saves.isEmpty());
	}

	@Test
	public void aDotPressedWritesTheKeyOnceMovesTheDotAndDoesNotSwitchTheShowingTab() throws Exception
	{
		final Memory prefs = new Memory();
		final PriceService service = service();
		final PriceService.Listener l = build(prefs, service);
		loadBank(l);
		onEdt(() ->
		{
			dots().get(1).doClick();
			assertDot(SidebarView.HISTORY);
			assertEquals("the showing tab does not change", SidebarView.ITEMS, panel.view());
			assertEquals(BankPriceMovementPanel.CARD_LIST, card());
			assertEquals(Arrays.asList(SidebarView.HISTORY), prefs.saves);

			// The same dot again writes nothing.
			panel.pressStartTab(SidebarView.HISTORY);
			assertEquals(1, prefs.saves.size());

			dots().get(0).doClick();
			assertDot(SidebarView.ITEMS);
			assertEquals(SidebarView.ITEMS, panel.view());
			assertEquals(Arrays.asList(SidebarView.HISTORY, SidebarView.ITEMS), prefs.saves);

			// A dot pressed while History is showing leaves History showing too.
			panel.pressView(SidebarView.HISTORY);
			dots().get(0).doClick();
			assertEquals(SidebarView.HISTORY, panel.view());
			assertEquals("Items was already chosen: no write", 2, prefs.saves.size());
			panel.pressStartTab(null);
			assertEquals("null reads as Items", 2, prefs.saves.size());
		});
		assertTrue(prefs.foldSaves.isEmpty());
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
	}

	@Test
	public void theConfigRoadMovesTheDotWithoutSwitchingTheTabOrWritingBack() throws Exception
	{
		final Memory prefs = new Memory();
		final PriceService service = service();
		final PriceService.Listener l = build(prefs, service);
		loadBank(l);
		onEdt(() ->
		{
			panel.setStartTab(SidebarView.HISTORY);
			assertDot(SidebarView.HISTORY);
			assertEquals(SidebarView.ITEMS, panel.view());
			panel.setStartTab(null);
			assertDot(SidebarView.ITEMS);
			panel.stop();
			panel.setStartTab(SidebarView.HISTORY);
			panel.pressStartTab(SidebarView.HISTORY);
			assertEquals("a stopped panel changes nothing", SidebarView.ITEMS, panel.startTab());
		});
		assertTrue(prefs.saves.isEmpty());
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
	}

	// ---------------------------------------------------------------- the menu

	@Test
	public void theMenuHoldsTheCaptionAndTheTwoDotsInTheirOwnGroup() throws Exception
	{
		build(new Memory(), service());
		onEdt(() ->
		{
			final JPopupMenu menu = panel.heroMenu();
			final Component[] c = menu.getComponents();
			int inventory = -1;
			for (int i = 0; i < c.length; i++)
			{
				if (c[i] == panel.countInventoryItem())
				{
					inventory = i;
				}
			}
			assertTrue(inventory > 0);
			assertTrue("a separator after the four switches", c[inventory + 1] instanceof JSeparator);
			final JLabel caption = find((Container) c[inventory + 2], JLabel.class);
			assertNotNull(caption);
			assertEquals("Tab to open on startup", caption.getText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, caption.getForeground());
			assertEquals("Items", ((JRadioButtonMenuItem) c[inventory + 3]).getText());
			assertEquals("Net Worth History", ((JRadioButtonMenuItem) c[inventory + 4]).getText());
			assertTrue("a separator before the preset caption", c[inventory + 5] instanceof JSeparator);
			final JLabel presets = find((Container) c[inventory + 6], JLabel.class);
			assertEquals("Preset price ranges", presets.getText());
			assertSame(dots().get(0), c[inventory + 3]);
			assertEquals("the caption looks like the presets'", presets.getFont(), caption.getFont());
			assertEquals(presets.getForeground(), caption.getForeground());
		});
	}

	@Test
	public void theDotsCloseTheMenuAsTheCheckSwitchesDo() throws Exception
	{
		build(new Memory(), service());
		onEdt(() ->
		{
			// Neither kind is marked to stay open, so both close the menu on a click, which is what the four check
			// switches above do (nothing in this panel sets the property).
			assertNull(panel.countInventoryItem().getClientProperty("doNotCloseOnMouseClick"));
			for (JRadioButtonMenuItem dot : dots())
			{
				assertNull(dot.getClientProperty("doNotCloseOnMouseClick"));
			}
		});
	}

	@Test
	public void theHoverAppearsOnlyWithShowHoverTextOn() throws Exception
	{
		build(new Memory(), service());
		onEdt(() ->
		{
			for (JRadioButtonMenuItem dot : dots())
			{
				assertNull("off by default", dot.getToolTipText());
			}
			final JLabel caption = findLabel(panel.heroMenu(), "Tab to open on startup");
			assertNull(caption.getToolTipText());
			panel.applyOptions(panel.options().withShowHoverText(true));
			for (JRadioButtonMenuItem dot : dots())
			{
				assertEquals("Which tab the sidebar shows when the plugin starts.", dot.getToolTipText());
			}
			panel.applyOptions(panel.options().withShowHoverText(false));
			for (JRadioButtonMenuItem dot : dots())
			{
				assertNull(dot.getToolTipText());
			}
		});
	}

	private static JLabel findLabel(Container root, String text)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				return (JLabel) c;
			}
			if (c instanceof Container)
			{
				final JLabel found = findLabel((Container) c, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------- the plugin's roads

	private static void set(BankPriceMovementPlugin plugin, String field, Object value) throws Exception
	{
		final Field f = BankPriceMovementPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static ConfigChanged changed(String key)
	{
		final ConfigChanged e = new ConfigChanged();
		e.setGroup(BankPriceMovementConfig.GROUP);
		e.setKey(key);
		return e;
	}

	@Test
	public void theStartTabKeyTakesItsOwnRoadToThePanelAlone() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel mocked = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", mocked);
		set(plugin, "config", config);
		when(config.startTab()).thenReturn(SidebarView.HISTORY);

		assertEquals("startTab", BankPriceMovementPlugin.START_TAB_KEY);
		assertTrue(BankPriceMovementPlugin.isStartTabKey("startTab"));
		assertFalse(BankPriceMovementPlugin.isStartTabKey("view"));
		assertFalse(BankPriceMovementPlugin.isStartTabKey(null));
		assertFalse(BankPriceMovementPlugin.isFoldKey("startTab"));
		assertFalse(BankPriceMovementPlugin.isOptionKey("startTab"));
		assertFalse(BankPriceMovementPlugin.isHeroKey("startTab"));
		assertFalse(BankPriceMovementPlugin.isPresetKey("startTab"));

		plugin.onConfigChanged(changed("startTab"));
		onEdt(() ->
		{
		});
		verify(mocked).setStartTab(SidebarView.HISTORY);
		verify(mocked, never()).pressStartTab(any());
		verify(mocked, never()).pressView(any());
		verify(mocked, never()).setView(any());
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
		verify(mocked, never()).applyFilter(any());
		verify(mocked, never()).applyOptions(any());

		// Shut down: no panel, no throw.
		set(plugin, "panel", null);
		plugin.onConfigChanged(changed("startTab"));

		// A mock config that answers nothing reads as Items.
		when(config.startTab()).thenReturn(null);
		assertEquals(SidebarView.ITEMS, plugin.startTabFromConfig());
	}

	@Test
	public void theSeamReadsAndWritesTheOneKeyAndTheWriteDoesNotComeBack() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementPanel mocked = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		final PriceService service = mock(PriceService.class);
		set(plugin, "panel", mocked);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		set(plugin, "service", service);
		when(config.startTab()).thenReturn(SidebarView.ITEMS);
		org.mockito.Mockito.doAnswer(invocation ->
		{
			when(config.startTab()).thenReturn((SidebarView) invocation.getArgument(2));
			plugin.onConfigChanged(changed((String) invocation.getArgument(1)));
			return null;
		}).when(cm).setConfiguration(anyString(), anyString(), any(Object.class));

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		assertEquals(SidebarView.ITEMS, prefs.loadStartTab());
		prefs.saveStartTab(SidebarView.HISTORY);
		verify(cm).setConfiguration(BankPriceMovementConfig.GROUP, "startTab", SidebarView.HISTORY);
		onEdt(() ->
		{
		});
		verify(mocked, never()).setStartTab(any());
		assertEquals(SidebarView.HISTORY, prefs.loadStartTab());
		verify(service, never()).setOptions(any());

		// No manager: a no-op, not an NPE.
		set(plugin, "configManager", null);
		plugin.configPrefs().saveStartTab(SidebarView.ITEMS);
	}

	// ---------------------------------------------------------------- the bridge

	@Test
	public void theVerbPressesTheDotsRoadRefusesOtherWordsAndEchoesTheState() throws Exception
	{
		final BankPriceMovementPanel mocked = mock(BankPriceMovementPanel.class);
		final BpmCommands dev = new BpmCommands(mocked, service(), gson);
		assertTrue(send(dev, "starttab=history").get("ok").getAsBoolean());
		verify(mocked).pressStartTab(SidebarView.HISTORY);
		assertTrue(send(dev, "starttab= Items ").get("ok").getAsBoolean());
		verify(mocked).pressStartTab(SidebarView.ITEMS);
		final JsonObject bad = send(dev, "starttab=Chart");
		assertFalse(bad.get("ok").getAsBoolean());
		assertEquals("starttab= wants items or history, not 'Chart'", bad.get("error").getAsString());
		assertEquals("starttab= wants items or history, not ''", send(dev, "starttab").get("error").getAsString());
		verify(mocked, never()).pressView(any());

		// The real panel: the echo follows the dot and not the showing tab.
		final Memory prefs = new Memory();
		final PriceService service = service();
		build(prefs, service);
		final BpmCommands real = new BpmCommands(panel, service, gson);
		JsonObject state = send(real, "state");
		assertEquals("ITEMS", state.get("startTab").getAsString());
		state = send(real, "starttab=history");
		assertEquals("HISTORY", state.get("startTab").getAsString());
		assertEquals("ITEMS", state.get("view").getAsString());
		assertEquals(Arrays.asList(SidebarView.HISTORY), prefs.saves);
		final String unknown = send(real, "nope").get("error").getAsString();
		assertTrue(unknown, unknown.contains("starttab="));
	}

	private JsonObject send(BpmCommands bridge, String cmd)
	{
		final String s = bridge.apply(cmd);
		assertNotNull(s);
		return gson.fromJson(s, JsonObject.class);
	}
}
