package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import net.runelite.client.game.ItemManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part G (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): "List options" - the two
 * gears at the end of the search box, the menu they open, and the alch-only rows the Items list shows or keeps out. Frozen
 * tests G2 to G7 and G9. (G1 is {@link BankPriceMovementConfigTest}'s, G8 the picture tests' - {@link ViewStripPicturesTest}
 * and {@link HistorySidebarPicturesTest} - and the plugin's half of G3 {@link BankPriceMovementWiringTest}'s.)
 *
 * <p>The service half (G4, G9) runs the real {@link PriceService} through {@link PriceServiceTest}'s fixture, used by
 * composition as {@link UntradeablePartsAlwaysCountTest} does, and hands the rows and status it published to a real panel:
 * what the reader sees is decided by the two together.
 *
 * <p>The move palette is one static for the whole process, so every test clears it in {@code @After}.
 */
public class ListOptionsTest
{
	/** What a reader picks: neither equals a built-in colour. */
	private static final Color UP = new Color(10, 20, 200);

	/** A stack of seven Dragon claws: a rise, a row with a move, a price. */
	private static final MovementRow CLAWS = new MovementRow(11, "Dragon claws", 7, false, 4_618L, 4_190L, 428L, 10.2d,
		32_326L, MovementRow.PriceSource.GUIDE);
	/** A stack of one that fell: the other direction. */
	private static final MovementRow PENNY = new MovementRow(12, "Penny", 1, false, 900L, 1_000L, -100L, -10.0d, 900L,
		MovementRow.PriceSource.GUIDE);
	/** Two alch rows: no baseline, no move, an alch value. */
	private static final MovementRow DRAMEN = alch(772, "Dramen staff", 2, 1_500L);
	private static final MovementRow CAPE = alch(11_850, "Graceful cape", 1, 500L);

	private final PriceServiceTest f = new PriceServiceTest();
	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;
	private JFrame frame;

	/** The config as a memory: whether the tick is stored on, every write of it, the colours' writes and anything else. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		Boolean showAlch;
		final List<Boolean> alchSaves = new ArrayList<>();
		final List<Color> upSaves = new ArrayList<>();
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
		public void saveStartTab(SidebarView tab)
		{
			otherWrites++;
		}

		@Override
		public void saveHero(HeroVisibility hero)
		{
			otherWrites++;
		}

		@Override
		public void savePresets(BandPresets presets)
		{
			otherWrites++;
		}

		@Override
		public Boolean loadShowAlchRows()
		{
			return showAlch;
		}

		@Override
		public void saveShowAlchRows(boolean show)
		{
			alchSaves.add(show);
		}

		@Override
		public void saveUpColour(Color colour)
		{
			upSaves.add(colour);
		}
	}

	/** A colour picker that shows nothing and keeps every request it was given, so a test moves it by hand. */
	private static final class FakePicker implements ColourPicker
	{
		final List<String> titles = new ArrayList<>();
		final List<Color> starts = new ArrayList<>();
		Consumer<Color> live;
		Consumer<Color> done;

		@Override
		public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
		{
			starts.add(start);
			titles.add(title);
			this.live = live;
			this.done = done;
		}
	}

	@Before
	public void setUp()
	{
		f.setUp();
	}

	@After
	public void tearDown() throws Exception
	{
		Widgets.setMoveColours(null, null);
		if (frame != null)
		{
			onEdt(() -> frame.dispose());
		}
		if (panel != null)
		{
			onEdt(() -> panel.stop());
		}
	}

	private static MovementRow alch(int id, String name, int quantity, long gp)
	{
		return MovementMath.alchRow(new BankItem(id, quantity, name, false, true, (int) gp));
	}

	private void build(Memory prefs, @Nullable ColourPicker picker) throws Exception
	{
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		final ItemManager items = mock(ItemManager.class);
		when(items.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(call -> LookRenderer.sprite(call.getArgument(0)));
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(items, service, prefs);
			panel.setClock(() -> NOW);
			panel.setColourPicker(picker);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	/** Publishes {@code rows} under a mocked LIST status whose m is {@code bankItems} and whose alch counts are as given. */
	private void publish(List<MovementRow> rows, int bankItems, int alchStacks, int alchCounted) throws Exception
	{
		final PriceService.Status s = SidebarViewPanelTest.status(BankHistorySeries.EMPTY, SidebarViewPanelTest.VALUE_NOW,
			NOW - 60_000L);
		when(s.bankItems()).thenReturn(bankItems);
		when(s.alchStacks()).thenReturn(alchStacks);
		when(s.alchCounted()).thenReturn(alchCounted);
		onEdt(() -> listener.onRows(rows, s));
	}

	private static void layout(BankPriceMovementPanel p)
	{
		p.setSize(LookRenderer.WIDTH, 1500);
		LookRenderer.layoutTree(p);
	}

	private List<String> shownNames()
	{
		final List<String> names = new ArrayList<>();
		for (MovementRowPanel row : panel.rowPanels())
		{
			names.add(row.nameText());
		}
		return names;
	}

	private JCheckBoxMenuItem tick()
	{
		final Component first = panel.listMenu().getComponent(0);
		assertTrue(String.valueOf(first), first instanceof JCheckBoxMenuItem);
		return (JCheckBoxMenuItem) first;
	}

	private static BufferedImage image(JLabel label)
	{
		return (BufferedImage) ((ImageIcon) label.getIcon()).getImage();
	}

	/** A mouse event of {@code id} delivered to {@code c}'s listeners, as the toolkit would deliver it. */
	private static void mouse(Component c, int id)
	{
		final MouseEvent e = new MouseEvent(c, id, 0L, 0, 1, 1, 0, false, MouseEvent.NOBUTTON);
		for (MouseListener l : c.getMouseListeners())
		{
			if (id == MouseEvent.MOUSE_ENTERED)
			{
				l.mouseEntered(e);
			}
			else
			{
				l.mouseExited(e);
			}
		}
	}

	/** The menu's own announcement that it went away, as Swing makes it for a close however it was made. */
	private void listMenuClosing()
	{
		final PopupMenuEvent e = new PopupMenuEvent(panel.listMenu());
		for (PopupMenuListener l : panel.listMenu().getPopupMenuListeners())
		{
			l.popupMenuWillBecomeInvisible(e);
		}
	}

	// ---------------------------------------------------------------- G2: the icon

	/**
	 * G2: the icon is at the search row's right end - its right edge where the box's right edge was, the box shortened by the
	 * icon's 12 px and a 6 px gap, the icon centred on the box - grey at rest (the colour the box paints its placeholder in),
	 * white while the pointer is over it, and its one-phrase hover "List options" is there.
	 */
	@Test
	public void g2_theGearsStandAtTheSearchRowsRightEndGreyAtRestAndWhiteOnHoverAndNameThemselves() throws Exception
	{
		build(new Memory(), null);
		publish(Arrays.asList(CLAWS, PENNY), 2, 0, 0);
		onEdt(() ->
		{
			layout(panel);
			final JLabel gears = LookRenderer.listOptionsLabel(panel);
			final Container row = panel.searchField().getParent();
			assertSame("the gears are in the search row", row, gears.getParent());
			final Rectangle rowBox = SwingUtilities.convertRectangle(row.getParent(), row.getBounds(), panel);
			final Rectangle boxInPanel = SwingUtilities.convertRectangle(panel.searchField().getParent(),
				panel.searchField().getBounds(), panel);
			final Rectangle icon = LookRenderer.listOptionsIcon(panel);
			final MovementRowPanel first = panel.rowPanels().get(0);
			final Rectangle cell = SwingUtilities.convertRectangle(first.getParent(), first.getBounds(), panel);

			assertEquals("the icon is 12 x 12", 12, icon.width);
			assertEquals(12, icon.height);
			assertEquals("the icon is the picture's size", GearsIcon.SIZE, icon.width);
			assertEquals("its right edge is where the box's right edge was: the row cells'", cell.x + cell.width,
				icon.x + icon.width);
			assertEquals("...which is the search row's", rowBox.x + rowBox.width, icon.x + icon.width);
			assertEquals("the box keeps the cells' left edge", cell.x, boxInPanel.x);
			assertEquals("and stops 6 px short of the icon", icon.x - 6, boxInPanel.x + boxInPanel.width);
			assertEquals("...so it is shorter by the icon and the gap",
				cell.width - 12 - 6, boxInPanel.width);
			assertEquals("the icon is centred on the box", boxInPanel.y + boxInPanel.height / 2.0, icon.y + icon.height / 2.0,
				1.0);
			assertEquals("the hover", "List options", gears.getToolTipText());
			assertEquals(BankPriceMovementPanel.LIST_OPTIONS_TIP, gears.getToolTipText());
			assertEquals("the box has a hover of its own", BankPriceMovementPanel.SEARCH_TIP,
				panel.searchField().getToolTipText());

			// Grey at rest: every opaque pixel is the placeholder's grey; white under the pointer; grey again after.
			assertEquals("the placeholder's grey", Color.GRAY, Widgets.PLACEHOLDER_COLOR);
			assertInk(image(gears), Widgets.PLACEHOLDER_COLOR);
			mouse(gears, MouseEvent.MOUSE_ENTERED);
			assertInk(image(gears), Color.WHITE);
			assertEquals("the hover is the same word on the way", "List options", gears.getToolTipText());
			mouse(gears, MouseEvent.MOUSE_EXITED);
			assertInk(image(gears), Widgets.PLACEHOLDER_COLOR);
		});
	}

	/**
	 * Every inked pixel of the icon is {@code ink} at some coverage - exactly, where the coverage is full; within the rounding of
	 * its blend where it is more than half; a faint edge pixel (a few per cent coverage) is left alone, because an un-premultiplied
	 * pixel that faint cannot hold its colour in 8 bits and contributes next to nothing to what is seen - and at least one pixel is
	 * {@code ink} outright.
	 */
	private static void assertInk(BufferedImage image, Color ink)
	{
		boolean solid = false;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				final int argb = image.getRGB(x, y);
				final int alpha = argb >>> 24;
				if (alpha == 0)
				{
					continue;
				}
				final String where = "pixel (" + x + ", " + y + ") is the ink's colour";
				if (alpha == 255)
				{
					assertEquals(where, ink.getRGB() & 0xFFFFFF, argb & 0xFFFFFF);
					solid = true;
				}
				else if (alpha >= 128)
				{
					assertEquals(where, ink.getRed(), argb >> 16 & 0xFF, 6);
					assertEquals(where, ink.getGreen(), argb >> 8 & 0xFF, 6);
					assertEquals(where, ink.getBlue(), argb & 0xFF, 6);
				}
			}
		}
		assertTrue("some of the icon is solid ink", solid);
	}

	/**
	 * G2: the two gears are drawn to the contract's geometry in a 12-unit box: a big gear at (4.3, 7.7) with a hole, a small one
	 * at (9.2, 3.0), their teeth reaching 4.2 and 2.8 from their centres, and nothing in the box's far corners.
	 */
	@Test
	public void g2_theGearsAreDrawnToTheContractsGeometry() throws Exception
	{
		final BufferedImage image = (BufferedImage) GearsIcon.icon(Color.WHITE).getImage();
		assertEquals(12, image.getWidth());
		assertEquals(12, image.getHeight());
		// The big gear's hole (radius 1.2 round (4.3, 7.7)) is clear and its body (radius 3.0) is solid.
		assertEquals("the big gear's hole", 0, image.getRGB(4, 7) >>> 24);
		assertEquals("its body, 2.2 to the right of the centre", 255, image.getRGB(6, 7) >>> 24);
		assertEquals("its body, 2.2 above the centre", 255, image.getRGB(4, 5) >>> 24);
		// The first tooth of each gear points along +x: the big one's tip stands at x 7.8, y 7.7; the small one's at x 11.5, y 3.0.
		assertEquals("a tooth of the big gear, beyond its body", 255, image.getRGB(7, 7) >>> 24);
		// (The small gear's tooth is 1.4 wide, so a pixel row of it is covered about 70 %: well inked, not solid.)
		assertTrue("a tooth of the small gear, beyond its body", image.getRGB(11, 2) >>> 24 > 150);
		assertTrue("...which reaches the box's right edge", image.getRGB(11, 3) >>> 24 > 150);
		// Nothing in the corners the two gears do not reach: top-left and bottom-right.
		assertEquals(0, image.getRGB(0, 0) >>> 24);
		assertEquals(0, image.getRGB(11, 11) >>> 24);
		assertEquals(0, image.getRGB(0, 11) >>> 24);
		// And with a colour given, the colour's own RGB is what is drawn.
		assertInk((BufferedImage) GearsIcon.icon(new Color(1, 2, 3)).getImage(), new Color(1, 2, 3));
	}

	// ---------------------------------------------------------------- G3: the menu

	/**
	 * G3: the menu holds its four items in order - "Show alch-only items" (a check item), the settings menu's rule, "Up colour"
	 * and "Down colour" (the same swatch rows) - and ticking the first stores {@code showAlchRows} ONCE and nothing else.
	 */
	@Test
	public void g3_theMenuHoldsItsFourItemsInOrderAndTickingStoresOnce() throws Exception
	{
		final Memory memory = new Memory();
		build(memory, null);
		publish(Arrays.asList(CLAWS, PENNY, DRAMEN, CAPE), 4, 0, 0);
		onEdt(() ->
		{
			final Component[] items = panel.listMenu().getComponents();
			// 1.1.0 part H: the "Colour sets" caption and its three rows stand under the colour rows - four more; part J
			// renamed the caption "Colour presets" and added Slot 1 and its save row - two more (ColourPresetsTest pins them).
			assertEquals("ten components", 10, items.length);
			assertTrue(items[0] instanceof JCheckBoxMenuItem);
			assertEquals("Show alch-only items", ((JMenuItem) items[0]).getText());
			assertEquals(BankPriceMovementPanel.SHOW_ALCH_TEXT, ((JMenuItem) items[0]).getText());
			assertTrue("the settings menu's rule", items[1] instanceof JSeparator);
			assertTrue(items[2] instanceof SwatchRow);
			assertEquals("Up colour", ((JMenuItem) items[2]).getText());
			assertTrue(items[3] instanceof SwatchRow);
			assertEquals("Down colour", ((JMenuItem) items[3]).getText());
			assertEquals("Colour presets", ((JLabel) ((java.awt.Container) items[4]).getComponent(0)).getText());
			assertTrue(items[5] instanceof SetRow && items[6] instanceof SetRow && items[7] instanceof SetRow
				&& items[8] instanceof SetRow && items[9] instanceof SetRow);
			assertEquals("the settings menu's face", Widgets.sans(12), items[0].getFont());
			assertFalse("off for a fresh profile", tick().isSelected());
			assertEquals("so the alch rows are out of the list", Arrays.asList("Dragon claws", "Penny"), shownNames());

			tick().doClick();
			assertTrue("the tick is on", tick().isSelected());
			assertEquals("stored once", Collections.singletonList(true), memory.alchSaves);
			assertEquals("the list has them, after the rest", Arrays.asList("Dragon claws", "Penny", "Dramen staff",
				"Graceful cape"), shownNames());
			assertEquals("nothing else was written", 0, memory.otherWrites);
			assertTrue(memory.upSaves.isEmpty());

			tick().doClick();
			assertFalse(tick().isSelected());
			assertEquals("stored again, once more", Arrays.asList(true, false), memory.alchSaves);
			assertEquals(Arrays.asList("Dragon claws", "Penny"), shownNames());
		});
	}

	/**
	 * G3: the config's own change - the settings page, the plugin's seed at startUp - lands through {@code applyShowAlchRows}:
	 * the tick and the list follow at once and nothing is written back. A panel built on a stored tick opens with it.
	 */
	@Test
	public void g3_aChangeFromOutsideUpdatesTheTickAndTheListAndWritesNothing() throws Exception
	{
		final Memory memory = new Memory();
		build(memory, null);
		publish(Arrays.asList(CLAWS, DRAMEN), 2, 0, 0);
		onEdt(() ->
		{
			assertEquals(Arrays.asList("Dragon claws"), shownNames());
			panel.applyShowAlchRows(true);
			assertTrue("the tick follows", tick().isSelected());
			assertEquals("and so does the list", Arrays.asList("Dragon claws", "Dramen staff"), shownNames());
			panel.applyShowAlchRows(true);
			assertEquals("the same state again is a redraw", Arrays.asList("Dragon claws", "Dramen staff"), shownNames());
			panel.applyShowAlchRows(false);
			assertFalse(tick().isSelected());
			assertEquals(Arrays.asList("Dragon claws"), shownNames());
		});
		assertTrue("nothing was written back", memory.alchSaves.isEmpty());
		assertEquals(0, memory.otherWrites);

		// A profile that stored the tick on opens with it on: the list and the menu.
		onEdt(() -> panel.stop());
		panel = null;
		final Memory stored = new Memory();
		stored.showAlch = Boolean.TRUE;
		build(stored, null);
		publish(Arrays.asList(CLAWS, DRAMEN), 2, 0, 0);
		onEdt(() ->
		{
			assertTrue("the menu opens ticked", tick().isSelected());
			assertEquals(Arrays.asList("Dragon claws", "Dramen staff"), shownNames());
		});
		assertTrue("a seed is not a press", stored.alchSaves.isEmpty());
	}

	/**
	 * G3: the icon toggles its menu exactly as the settings icon does: a left press opens it under the icon, a second press
	 * while it stands closes it and opens nothing, and the close is the one the reopen guard remembers. (Skipped where there
	 * is no display: a popup has nowhere to be shown.)
	 */
	@Test
	public void g3_aPressOpensTheMenuUnderTheIconAndASecondPressClosesIt() throws Exception
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		build(new Memory(), null);
		publish(Arrays.asList(CLAWS, PENNY), 2, 0, 0);
		final AtomicReference<JFrame> made = new AtomicReference<>();
		onEdt(() ->
		{
			final JFrame host = new JFrame();
			host.setFocusableWindowState(false);
			host.add(panel);
			host.setSize(400, 900);
			host.setLocation(-2000, -2000);
			host.setVisible(true);
			made.set(host);
		});
		frame = made.get();
		onEdt(() ->
		{
			final JLabel gears = LookRenderer.listOptionsLabel(panel);
			// A right press is a menu gesture everywhere in this sidebar, never a press.
			press(gears, MouseEvent.BUTTON3);
			assertFalse(panel.listMenu().isVisible());

			press(gears, MouseEvent.BUTTON1);
			assertTrue("the first press opens it", panel.listMenu().isVisible());
			assertSame("under the icon", gears, panel.listMenu().getInvoker());

			press(gears, MouseEvent.BUTTON1);
			assertFalse("the second press closes it", panel.listMenu().isVisible());
			assertFalse("and is the close the reopen guard remembers", panel.listPressOpens());
		});
	}

	/**
	 * G3: the reopen guard, driven through the panel's own clock as the settings icon's is: a press within 300 ms of a close is
	 * that close and opens nothing, one at 300 ms or later opens, and the rule is about the LAST close.
	 */
	@Test
	public void g3_aPressWithinTheGuardOfACloseOpensNothingAndOneAfterItOpens() throws Exception
	{
		build(new Memory(), null);
		final AtomicLong now = new AtomicLong(NOW);
		onEdt(() ->
		{
			panel.setClock(now::get);
			assertTrue("nothing has closed yet, so the first press opens", panel.listPressOpens());
			listMenuClosing();
			for (long after : new long[]{0L, 100L, BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS - 1})
			{
				now.set(NOW + after);
				assertFalse("a press " + after + " ms after the close is that close", panel.listPressOpens());
			}
			now.set(NOW + BankPriceMovementPanel.GEAR_REOPEN_GUARD_MILLIS);
			assertTrue("the guard is over at exactly 300 ms", panel.listPressOpens());
			listMenuClosing();
			now.set(NOW + 400L);
			assertFalse("100 ms after the second close", panel.listPressOpens());
			now.set(NOW + 700L);
			assertTrue(panel.listPressOpens());
			assertTrue("the settings icon's own guard is not this one's", panel.gearPressOpens());
		});
	}

	// ---------------------------------------------------------------- G4: the service and the list

	/** The alch-only stacks a bank holds for these tests: two untradeables the Grand Exchange does not list, 1,500 and 500 to alch. */
	private BankSnapshot bankWithAlchStacks()
	{
		return PriceServiceTest.bankWithUntradeables(PriceServiceTest.T0);
	}

	/**
	 * G4: "Include alch-only untradeables" ON with the tick OFF - no alch row is listed and the bank value counts them; the
	 * switch OFF with the tick ON - the alch rows are listed and the bank value leaves them out; both OFF - the bank value
	 * is what it was before part G, and no alch row is listed. The service publishes the alch rows in every case: only the
	 * list decides what is shown.
	 */
	@Test
	public void g4_theSwitchDecidesTheBankValueAndTheTickDecidesTheList() throws Exception
	{
		final long plain = 6_290_824L;
		final long alchGp = 3_500L;
		final Memory memory = new Memory();
		f.warmUpWith(bankWithAlchStacks());
		build(memory, null);

		// Both off: the value as before part G, the alch rows published and not listed.
		final List<MovementRow> bothOff = f.lastRows();
		assertNotNull("published", PriceServiceTest.rowFor(bothOff, PriceServiceTest.DRAMEN));
		assertEquals(plain, f.lastStatus().portfolio().valueNow());
		final PriceService.Status offStatus = f.lastStatus();
		onEdt(() -> listener.onRows(bothOff, offStatus));
		onEdt(() ->
		{
			assertFalse(shownNames().contains("Dramen staff"));
			assertFalse(shownNames().contains("Graceful cape"));
			assertEquals("every other row is listed", PriceServiceTest.defaultList(bothOff).size(), panel.rowPanels().size());
		});

		// Switch ON, tick OFF: the value counts them, the list does not show them.
		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		final List<MovementRow> switchOn = f.lastRows();
		final PriceService.Status onStatus = f.lastStatus();
		assertEquals("the bank value counts them", plain + alchGp, onStatus.portfolio().valueNow());
		assertNotNull(PriceServiceTest.rowFor(switchOn, PriceServiceTest.DRAMEN));
		onEdt(() -> listener.onRows(switchOn, onStatus));
		onEdt(() ->
		{
			assertFalse("tick off: no alch row listed", shownNames().contains("Dramen staff"));
			assertFalse(shownNames().contains("Graceful cape"));
		});

		// Switch OFF, tick ON: the list shows them, the value leaves them out.
		f.service.setOptions(ViewOptions.DEFAULT);
		final List<MovementRow> switchOff = f.lastRows();
		final PriceService.Status offAgain = f.lastStatus();
		assertEquals("the bank value leaves them out", plain, offAgain.portfolio().valueNow());
		onEdt(() ->
		{
			listener.onRows(switchOff, offAgain);
			tick().doClick();
			assertTrue("tick on: the alch rows are listed", shownNames().contains("Dramen staff"));
			assertTrue(shownNames().contains("Graceful cape"));
			final List<String> names = shownNames();
			assertEquals("...after every other row", Arrays.asList("Dramen staff", "Graceful cape"),
				names.subList(names.size() - 2, names.size()));
		});
		assertEquals("stored the once", Collections.singletonList(true), memory.alchSaves);
	}

	// ---------------------------------------------------------------- G5: the sort

	/**
	 * G5: an alch row sorts after every other row - a row with no key and a row with no price included - in all four columns and
	 * both directions; among themselves the alch rows keep the rules the other rows have.
	 */
	@Test
	public void g5_anAlchRowSortsAfterEveryOtherRowInEveryColumnAndDirection()
	{
		// A cheap alch row (500) and a dear one (900,000): by price the dear one would lead every ascending or descending list.
		final MovementRow dear = alch(23_975, "Crystal body", 1, 900_000L);
		final MovementRow flat = new MovementRow(13, "Flat", 5, false, 2_000L, 2_000L, 0L, 0.0d, 10_000L,
			MovementRow.PriceSource.GUIDE);
		final MovementRow noMove = new MovementRow(14, "No baseline", 1, false, 50L, null, null, null, 50L,
			MovementRow.PriceSource.GUIDE);
		final MovementRow noPrice = new MovementRow(15, "No price", 1, false, null, null, null, null, 0L,
			MovementRow.PriceSource.NONE);
		final List<MovementRow> all = Arrays.asList(CAPE, dear, CLAWS, noPrice, PENNY, DRAMEN, noMove, flat);
		for (SortMode sort : SortMode.values())
		{
			for (boolean descending : new boolean[]{true, false})
			{
				final List<MovementRow> sorted = new ArrayList<>(all);
				sorted.sort(MovementMath.comparator(sort, descending));
				final String what = sort + (descending ? " descending" : " ascending");
				final int firstAlch = indexOfFirstAlch(sorted);
				assertEquals(what + ": the three alch rows are the last three", sorted.size() - 3, firstAlch);
				for (int i = firstAlch; i < sorted.size(); i++)
				{
					assertTrue(what + ": " + sorted.get(i).name(), MovementMath.isAlch(sorted.get(i)));
				}
				assertSame(what + ": the row with no price is the last of the others", noPrice, sorted.get(firstAlch - 1));
			}
		}
		// Through the filter the service applies, too: the same rows, the same place.
		for (SortMode sort : SortMode.values())
		{
			final List<MovementRow> applied = MovementMath.apply(all, RowFilter.DEFAULT.withSort(sort).withDescending(false));
			assertEquals(sort + ": the four rows with a price and the three alch rows (the row with no price is dropped)", 7,
				applied.size());
			assertTrue(sort + ": the last three are alch", MovementMath.isAlch(applied.get(4)) && MovementMath.isAlch(applied.get(5))
				&& MovementMath.isAlch(applied.get(6)));
		}
	}

	private static int indexOfFirstAlch(List<MovementRow> rows)
	{
		for (int i = 0; i < rows.size(); i++)
		{
			if (MovementMath.isAlch(rows.get(i)))
			{
				return i;
			}
		}
		return -1;
	}

	// ---------------------------------------------------------------- G6: the search

	/**
	 * G6: with the tick OFF a search that matches an alch item lists that row - whatever the tick says - and clearing the search
	 * hides it again; a search that matches nothing of it leaves it out, and an item that is not alch is found as it always was.
	 */
	@Test
	public void g6_aSearchFindsAnAlchRowWhateverTheTickSaysAndClearingItHidesTheRowAgain() throws Exception
	{
		build(new Memory(), null);
		publish(Arrays.asList(CLAWS, PENNY, DRAMEN, CAPE), 4, 0, 0);
		onEdt(() ->
		{
			assertEquals(Arrays.asList("Dragon claws", "Penny"), shownNames());

			panel.applySearch("dramen");
			assertEquals("the search lists the alch row the tick keeps out", Arrays.asList("Dramen staff"), shownNames());
			panel.applySearch("gra");
			assertEquals(Arrays.asList("Graceful cape"), shownNames());
			panel.applySearch("pen");
			assertEquals("a search for something that is not alch is as it was", Arrays.asList("Penny"), shownNames());

			panel.applySearch("");
			assertEquals("clearing the search hides the alch rows again", Arrays.asList("Dragon claws", "Penny"), shownNames());
			panel.applySearch("nothing like this");
			assertTrue(shownNames().isEmpty());
			assertEquals(BankPriceMovementPanel.CARD_EMPTY, panel.card());
			panel.applySearch("");
			assertEquals(BankPriceMovementPanel.CARD_LIST, panel.card());
		});
	}

	// ---------------------------------------------------------------- G7: the colours

	/**
	 * G7: a colour picked from the List options menu is the palette and the keys the settings menu shows: the same title, the
	 * same colour in force on opening, the same live preview and the same store - the settings menu's swatch and the rows
	 * follow, and each menu's swatches show the current colours.
	 */
	@Test
	public void g7_aColourPickedFromTheListOptionsMenuIsThePaletteAndTheKeysTheSettingsMenuShows() throws Exception
	{
		final Memory memory = new Memory();
		final FakePicker picker = new FakePicker();
		build(memory, picker);
		publish(Arrays.asList(CLAWS, PENNY), 2, 0, 0);
		onEdt(() ->
		{
			final JMenuItem listUp = (JMenuItem) panel.listMenu().getComponent(2);
			final JMenuItem listDown = (JMenuItem) panel.listMenu().getComponent(3);
			final JMenuItem settingsUp = (JMenuItem) panel.heroMenu().getComponent(6);
			final JMenuItem settingsDown = (JMenuItem) panel.heroMenu().getComponent(7);
			assertEquals("Up colour", settingsUp.getText());
			assertSwatch(listUp, Widgets.MOVE_UP_DEFAULT);
			assertSwatch(listDown, Widgets.MOVE_DOWN_TEXT);

			listUp.doClick();
			assertEquals("one request", 1, picker.titles.size());
			assertEquals("the settings menu's title", BankPriceMovementPanel.UP_COLOUR_TEXT, picker.titles.get(0));
			assertEquals("opened on the colour in force", Widgets.MOVE_UP_DEFAULT, picker.starts.get(0));

			picker.live.accept(UP);
			assertTrue("nothing is stored while the picker moves", memory.upSaves.isEmpty());
			assertEquals("the rows are drawn in it", UP, panel.rowPanels().get(0).changeColor());
			assertSwatch(settingsUp, UP);
			assertSwatch(listUp, UP);

			picker.done.accept(UP);
			assertEquals("stored under the settings menu's key, once", Collections.singletonList(UP), memory.upSaves);
			assertEquals(UP, panel.rowPanels().get(0).changeColor());
			assertSwatch(settingsUp, UP);

			// And the other way round: the settings menu's pick shows in this menu's swatch when it is opened.
			final Color down = new Color(200, 150, 0);
			settingsDown.doClick();
			picker.live.accept(down);
			picker.done.accept(down);
			assertSwatch(listDown, down);
			assertSwatch(settingsDown, down);
			assertEquals("the picker was opened on the colour in force", Widgets.MOVE_DOWN_TEXT, picker.starts.get(1));
		});
	}

	/** The swatch's middle is the colour: the row painted at its preferred size, the box 24 x 12 inset from the right. */
	private static void assertSwatch(JMenuItem row, Color colour)
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
		final int x = img.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final java.awt.Insets in = row.getInsets();
		final int y = in.top + (img.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		assertEquals(row.getText() + ": the swatch's colour", colour.getRGB(),
			img.getRGB(x + BankPriceMovementPanel.SWATCH_WIDTH / 2, y + BankPriceMovementPanel.SWATCH_HEIGHT / 2));
	}

	// ---------------------------------------------------------------- G9: the counts

	/**
	 * G9: an alch row the list keeps out is in neither n nor m of "n of m items"; one it lists is in both. The service's real
	 * status carries the stack counts (its m is unchanged by part G: the bank's stacks as the switch counts them) and the panel
	 * says the sentence over the alch stacks it answers for - with the tick off, over none; with it on, over all; with a search
	 * that found one, over that one.
	 */
	@Test
	public void g9_aHiddenAlchRowCountsInNeitherNNorM() throws Exception
	{
		f.warmUpWith(bankWithAlchStacks());
		build(new Memory(), null);

		// The switch off (the default), the tick off: the bank's 31 stacks, 30 of them with a row.
		final int nonAlch = PriceServiceTest.defaultList(f.lastRows()).size();
		final int stacks = f.lastStatus().bankItems();
		assertEquals("the status's m is the pre-G one", 31, stacks);
		assertEquals("two alch stacks, uncounted", 2, f.lastStatus().alchStacks());
		assertEquals(0, f.lastStatus().alchCounted());
		final PriceService.Status off = f.lastStatus();
		final List<MovementRow> offRows = f.lastRows();
		onEdt(() -> listener.onRows(offRows, off));
		onEdt(() ->
		{
			assertEquals(says(nonAlch, 31), countSentenceEnd());
			// The tick on: both alch stacks are in n and in m.
			tick().doClick();
			assertEquals(says(nonAlch + 2, 33), countSentenceEnd());
			tick().doClick();
			// A search that finds one of them, tick off: that row is in n and in m, the other is in neither.
			panel.applySearch("dramen");
			assertEquals(says(1, 32), countSentenceEnd());
			panel.applySearch("");
		});

		// The switch ON counts the alch stacks in the bank's m; the list still keeps its alch rows out of both numbers.
		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		final PriceService.Status on = f.lastStatus();
		final List<MovementRow> onRows = f.lastRows();
		assertEquals("the service's m counts the alch stacks the switch counts", 33, on.bankItems());
		assertEquals(2, on.alchStacks());
		assertEquals(2, on.alchCounted());
		onEdt(() ->
		{
			listener.onRows(onRows, on);
			assertEquals("the tick off: neither n nor m has them", says(nonAlch, 31), countSentenceEnd());
			tick().doClick();
			assertEquals("the tick on: both have them", says(nonAlch + 2, 33), countSentenceEnd());
		});
	}

	/**
	 * G9, the rest of the sentence: an alch stack the list keeps out is not "with no guide price" either, and an untradeable
	 * with no alch value - a row with no price, which no list shows - is not an alch stack in "n of m" (the reviewer's two
	 * findings on part G).
	 */
	@Test
	public void g9b_theNoGuidePriceClauseAndAZeroAlchUntradeable() throws Exception
	{
		final BankSnapshot bank = bankWithAlchStacks();
		bank.items.add(new BankItem(99_001, 1, "Pet rock", false, true, 0));
		f.warmUpWith(bank);
		build(new Memory(), null);
		assertEquals("the zero-alch stack is no alch stack", 2, f.lastStatus().alchStacks());

		// The switch off, the tick on: the two alch rows are listed, and the zero-alch stack is in neither number.
		final PriceService.Status off = f.lastStatus();
		final List<MovementRow> offRows = f.lastRows();
		final int nonAlch = PriceServiceTest.defaultList(offRows).size();
		onEdt(() ->
		{
			listener.onRows(offRows, off);
			tick().doClick();
			assertEquals(says(nonAlch + 2, off.bankItems() + 2), countSentenceEnd());
			tick().doClick();
		});

		// The switch on, the tick off: the hidden alch stacks are not named as "with no guide price"; the fixture's one unpriced
		// stack and the zero-alch one are.
		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		final PriceService.Status on = f.lastStatus();
		final List<MovementRow> onRows = f.lastRows();
		onEdt(() ->
		{
			listener.onRows(onRows, on);
			final String tip = panel.bandTarget().getToolTipText();
			assertTrue(tip, tip.endsWith(", 2 with no guide price"));
			tick().doClick();
			assertTrue(panel.bandTarget().getToolTipText(), panel.bandTarget().getToolTipText().endsWith(", 4 with no guide price"));
		});
	}

	/** The count sentence in the band button's hover - "n of m items", or "n items" when n is all of m - without what may follow it. */
	private String countSentenceEnd()
	{
		final String tip = panel.bandTarget().getToolTipText();
		assertNotNull(tip);
		final java.util.regex.Matcher found = java.util.regex.Pattern.compile("\\d+ (of \\d+ )?items").matcher(tip);
		assertTrue(tip, found.find());
		return found.group();
	}

	/** What the sentence says for {@code n} rows listed of {@code m} stacks. */
	private static String says(int n, int m)
	{
		return n + (m > n ? " of " + m : "") + " items";
	}
}
