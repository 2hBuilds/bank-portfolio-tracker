package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.TODAY;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.find;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.series;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static com.bankpricemovement.SidebarViewPanelTest.walk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 1.1.0 part E (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): "Hide amounts", the eye
 * in the hero card's number row that hides every gp amount and every item quantity in the sidebar behind a fixed mask and
 * keeps item names, pictures, percentages, dates and the chart's shape.
 *
 * <p>E2b the eye's two images, E2 what it does (and, since part I, that it stands in the card's top row), E3 the card, E4 an Items row (face, picture and open
 * block), E5 the History tab, E6 the masks never depend on the number and toggling back restores every text, and the
 * panel half of E7 (a config change from outside is a redraw from what the panel holds, never a write and never a service
 * call). 1.1.0 part I moved the eye from the total's row to the card's top-right icons - eye, Discord, settings - and grey
 * 52 / grey 165 for its two states: I1 where it stands, I2 its colours, I3 that a press at the new place reaches it, and
 * I4 the pictures ({@link ViewStripPicturesTest}, {@link HistorySidebarPicturesTest}). E1 is {@link BankPriceMovementConfigTest}'s, E8 the picture tests' ({@link ViewStripPicturesTest},
 * {@link HistorySidebarPicturesTest}), and the plugin's half of E7 {@link BankPriceMovementWiringTest}'s.
 *
 * <p>The move palette is untouched here, so no test needs to clear it.
 */
public class HideAmountsTest
{
	/** A stack of seven Dragon claws: 4,618 each, 4,190 a day ago, +428 each, +10.2%, worth 32,326 - every figure distinct. */
	private static final MovementRow CLAWS = new MovementRow(11, "Dragon claws", 7, false, 4_618L, 4_190L, 428L, 10.2d,
		32_326L, MovementRow.PriceSource.GUIDE);
	/** One of a thing worth 9 gp: a one-digit amount, a quantity of one (no item figure under its stack's). */
	private static final MovementRow PENNY = new MovementRow(12, "Penny", 1, false, 9L, 8L, 1L, 12.5d, 9L,
		MovementRow.PriceSource.GUIDE);
	/** A twelve-digit stack: 123,456,789,012 gp, moving +987,654,321,098 over the day. */
	private static final MovementRow WHALE = new MovementRow(13, "Whale", 1_000_000, true, 123_456_789L, 100_000_000L,
		987_654L, 5.5d, 123_456_789_012L, MovementRow.PriceSource.GUIDE);
	/** Three in the bank, four in the inventory: a stack whose open block names where it is. */
	private static final MovementRow SPLIT = new MovementRow(14, "Split", 7, false, 100L, 90L, 10L, 11.1d, 700L,
		MovementRow.PriceSource.GUIDE, null, null, null, null, 3, 4, 0, 0);

	private PriceService service;
	private ItemManager items;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;
	/** What each {@code getImage(id, quantity, stackable)} call answered: the picture the row was given. */
	private final Map<String, AsyncBufferedImage> pictures = new HashMap<>();

	/** The config as a memory: whether the amounts are stored hidden, and every write of that and of anything else. */
	private static final class Memory implements BankPriceMovementPanel.Prefs
	{
		@Nullable
		Boolean hide;
		final List<Boolean> hideSaves = new ArrayList<>();
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
		public Boolean loadHideAmounts()
		{
			return hide;
		}

		@Override
		public void saveHideAmounts(boolean on)
		{
			hideSaves.add(on);
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

	private void build(Memory prefs) throws Exception
	{
		service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		items = mock(ItemManager.class);
		when(items.getImage(anyInt(), anyInt(), anyBoolean())).thenAnswer(call ->
		{
			final AsyncBufferedImage image = LookRenderer.sprite(call.getArgument(0));
			pictures.put(call.getArgument(0) + "," + call.getArgument(1) + "," + call.getArgument(2), image);
			return image;
		});
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(items, service, prefs);
			panel.setClock(() -> NOW);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	/** Publishes {@code rows} under a status worth {@code value}, with {@code record} as the bank's history. */
	private void publish(List<MovementRow> rows, long value, @Nullable BankHistorySeries record) throws Exception
	{
		final PriceService.Status s = status(record, value, NOW - 60_000L);
		onEdt(() -> listener.onRows(rows, s));
	}

	private static BankHistorySeries rising()
	{
		return series(TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
	}

	private static void layout(BankPriceMovementPanel p)
	{
		p.setSize(LookRenderer.WIDTH, 1500);
		LookRenderer.layoutTree(p);
	}

	/** The eye: the one label in the card's top row (since part I; the total's row held it before) that says what a press does. */
	private JLabel eye()
	{
		for (Component c : walk(panel.captionRow()))
		{
			if (c instanceof JLabel && (BankPriceMovementPanel.HIDE_AMOUNTS_TIP.equals(((JLabel) c).getToolTipText())
				|| BankPriceMovementPanel.SHOW_AMOUNTS_TIP.equals(((JLabel) c).getToolTipText())))
			{
				return (JLabel) c;
			}
		}
		throw new AssertionError("no eye in the card's top row");
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

	private static void press(Component c, int button)
	{
		SidebarViewPanelTest.press(c, button);
	}

	// ---------------------------------------------------------------- E2b: the eye's two images

	/** Design D of the contract, rows 0 to 11, x = 0 at the left: X is a grey pixel, "." is clear. Written out, not derived. */
	private static final String[] DESIGN_D_SHOWN = {
		"............",
		"............",
		"....XXXX....",
		"..XX....XX..",
		".X...XX...X.",
		"X...XXXX...X",
		"X...XXXX...X",
		".X...XX...X.",
		"..XX....XX..",
		"....XXXX....",
		"............",
		"............",
	};

	/**
	 * The grid of the eye while the amounts are HIDDEN, built from the contract's rule alone: the open eye, and for i = 0..11
	 * the pixel (i, 11 - i) grey and its left and right neighbours on that row cleared.
	 */
	private static boolean[][] hiddenGrid()
	{
		final boolean[][] grid = new boolean[12][12];
		for (int y = 0; y < 12; y++)
		{
			for (int x = 0; x < 12; x++)
			{
				grid[y][x] = DESIGN_D_SHOWN[y].charAt(x) == 'X';
			}
		}
		for (int i = 0; i < 12; i++)
		{
			final int y = 11 - i;
			if (i - 1 >= 0)
			{
				grid[y][i - 1] = false;
			}
			if (i + 1 < 12)
			{
				grid[y][i + 1] = false;
			}
			grid[y][i] = true;
		}
		return grid;
	}

	/** Design D's open eye as a grid: true where X. */
	private static boolean[][] shownGrid()
	{
		final boolean[][] shown = new boolean[12][12];
		for (int y = 0; y < 12; y++)
		{
			for (int x = 0; x < 12; x++)
			{
				shown[y][x] = DESIGN_D_SHOWN[y].charAt(x) == 'X';
			}
		}
		return shown;
	}

	private static void assertGrid(String what, boolean[][] want, BufferedImage got, Color ink)
	{
		assertEquals(what + ": 12 wide", 12, got.getWidth());
		assertEquals(what + ": 12 tall", 12, got.getHeight());
		for (int y = 0; y < 12; y++)
		{
			for (int x = 0; x < 12; x++)
			{
				final int argb = got.getRGB(x, y);
				if (want[y][x])
				{
					assertEquals(what + ": (" + x + ", " + y + ") is the ink, opaque", 0xFF000000 | ink.getRGB(), argb);
				}
				else
				{
					assertEquals(what + ": (" + x + ", " + y + ") is clear", 0, argb);
				}
			}
		}
	}

	/**
	 * E2b: the eye's two images match design D's grids pixel for pixel - grey where X, clear everywhere else, no blend - while
	 * the amounts show (the open eye) and while they are hidden (the same eye with the slash that stands apart from it); and
	 * the label wears them: the open eye at rest, white under the mouse, the slashed one after a press. (Part I changed the
	 * label's resting ink - grey 52 while the amounts show, grey 165 while hidden; this test is about the grids, I2 pins the
	 * rule, and the label half here uses the part I greys so that it still checks every pixel.)
	 */
	@Test
	public void e2b_theEyesTwoImagesMatchDesignDPixelForPixel() throws Exception
	{
		final boolean[][] shown = shownGrid();
		final Color grey = ColorScheme.LIGHT_GRAY_COLOR;
		final Color quiet = new Color(52, 52, 52);
		assertEquals("the settings icon's grey", new Color(165, 165, 165), grey);
		assertGrid("shown", shown, (BufferedImage) EyeIcon.icon(false, grey).getImage(), grey);
		assertGrid("hidden", hiddenGrid(), (BufferedImage) EyeIcon.icon(true, grey).getImage(), grey);
		assertGrid("shown, white", shown, (BufferedImage) EyeIcon.icon(false, Color.WHITE).getImage(), Color.WHITE);
		// The slash is its own pixels and the clear ones round them are really clear: pin three rows of it by hand.
		final boolean[][] slashed = hiddenGrid();
		assertTrue("the slash's bottom-left corner", slashed[11][0]);
		assertTrue("the slash's top-right corner", slashed[0][11]);
		assertTrue("row 5: the outline the slash does not touch stays", slashed[5][4] && slashed[5][11]);
		assertFalse("row 5: the outline is cleared left of the slash", slashed[5][5]);
		assertTrue("...the slash itself stands on that row", slashed[5][6]);
		assertFalse("...and the outline is cleared right of it", slashed[5][7]);

		build(new Memory());
		onEdt(() ->
		{
			final JLabel eye = eye();
			assertGrid("the label at rest", shown, image(eye), quiet);
			mouse(eye, MouseEvent.MOUSE_ENTERED);
			assertGrid("under the mouse", shown, image(eye), Color.WHITE);
			mouse(eye, MouseEvent.MOUSE_EXITED);
			assertGrid("back at rest", shown, image(eye), quiet);
			press(eye, MouseEvent.BUTTON1);
			assertGrid("hidden, at rest", hiddenGrid(), image(eye), grey);
			mouse(eye, MouseEvent.MOUSE_ENTERED);
			assertGrid("hidden, under the mouse", hiddenGrid(), image(eye), Color.WHITE);
			press(eye, MouseEvent.BUTTON1);
			assertGrid("open again, still under the mouse", shown, image(eye), Color.WHITE);
		});
	}

	// ---------------------------------------------------------------- E2: what the eye does (and, since part I, where it stands)

	/**
	 * E2: the eye toggles and stores. Its hover reads "Hide amounts" and then "Show amounts" with "Show hover text" OFF; a left
	 * press toggles and stores once, a right press does nothing; it stands where it stood whatever the total says; and with
	 * "Show bank value" off the eye is still there.
	 *
	 * <p>Part I's change to this test, and why: part E put the eye in the total's row left of the Refresh link, and this test's
	 * placement assertions pinned that - one holder with the link, the eye's box ending where the link's box begins, centred on
	 * the link and on the total's row. Part I moved the eye to the card's top-right icons (eye, Discord, settings), where those
	 * assertions no longer describe the card, so they are replaced by "it is in the top row and the total's row holds none"
	 * here, and by the full geometry in {@link #i1_theEyeIsFirstOfThreeInTheCardsTopRowOnBothTabs}. Everything about what it does
	 * - the hovers, the press, the one store, the stillness, the switch-off case - is unchanged.
	 */
	@Test
	public void e2_theEyeSitsInTheCardsTopRowAndTogglesAndStoresOnce() throws Exception
	{
		final Memory memory = new Memory();
		build(memory);
		assertFalse("the quieter sidebar is what ships", panel.options().showHoverText());
		publish(Arrays.asList(CLAWS, PENNY), 7L, rising());
		final Rectangle[] firstBox = new Rectangle[1];
		onEdt(() ->
		{
			layout(panel);
			final JLabel eye = eye();
			assertTrue("the eye is in the card's top row", SwingUtilities.isDescendingFrom(eye, panel.captionRow()));
			assertFalse("...and the total's row holds none", SwingUtilities.isDescendingFrom(eye, panel.totalRow()));
			assertEquals("the eye is 12 px of ink", 12, eye.getIcon().getIconWidth());
			assertEquals("the hover, with the amounts showing", BankPriceMovementPanel.HIDE_AMOUNTS_TIP, eye.getToolTipText());
			assertEquals("Hide amounts", eye.getToolTipText());
			final Rectangle shownBox = inCard(eye);
			firstBox[0] = shownBox;

			// A right press is a menu gesture everywhere in this sidebar, never a toggle.
			press(eye, MouseEvent.BUTTON3);
			assertEquals("nothing happened", BankPriceMovementPanel.HIDE_AMOUNTS_TIP, eye.getToolTipText());
			assertTrue(memory.hideSaves.isEmpty());

			press(eye, MouseEvent.BUTTON1);
			assertEquals("the hover, with them hidden", "Show amounts", eye.getToolTipText());
			assertEquals("stored once", Collections.singletonList(true), memory.hideSaves);
			assertFalse("and still no sentence hovers: the switch is off", panel.options().showHoverText());

			// The eye stands where it stood, whatever the total says: 7 gp then, and a billion now.
			layout(panel);
			assertEquals(shownBox, inCard(eye));

			press(eye, MouseEvent.BUTTON1);
			assertEquals("Hide amounts", eye.getToolTipText());
			assertEquals("stored again, once more", Arrays.asList(true, false), memory.hideSaves);
			assertEquals("nothing else was written", 0, memory.otherWrites);
		});

		publish(Arrays.asList(CLAWS, PENNY), 1_234_567_890_123L, rising());
		onEdt(() ->
		{
			layout(panel);
			assertEquals("a long total does not push the eye", firstBox[0], inCard(eye()));

			// "Show bank value" off takes the number out of the line and leaves the eye in the card's top row.
			panel.applyHeroVisibility(HeroVisibility.ALL.withValue(false));
			layout(panel);
			assertNotEquals("the total is out of its line", panel.totalRow(), panel.totalLabel().getParent());
			final JLabel eye = eye();
			assertTrue("the eye is still there, in the card's top row", SwingUtilities.isDescendingFrom(eye, panel.captionRow()));
			press(eye, MouseEvent.BUTTON1);
			assertEquals("and still works", BankPriceMovementPanel.SHOW_AMOUNTS_TIP, eye.getToolTipText());
		});
	}

	// ---------------------------------------------------------------- I: the eye beside the Discord mark (1.1.0 part I)

	/** {@code c}'s bounds in the hero card's own coordinates. */
	private Rectangle inCard(Component c)
	{
		return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), panel.hero());
	}

	/** The label of the card's top row whose hover is exactly {@code tip}. */
	private JLabel topRowLabel(String tip)
	{
		for (Component c : walk(panel.captionRow()))
		{
			if (c instanceof JLabel && tip.equals(((JLabel) c).getToolTipText()))
			{
				return (JLabel) c;
			}
		}
		throw new AssertionError("no label with the hover '" + tip + "' in the card's top row");
	}

	/** Where {@code label}'s ink starts, in the card: its box less its left inset. */
	private int inkLeft(JLabel label)
	{
		return inCard(label).x + label.getInsets().left;
	}

	/** Where {@code label}'s ink ends, in the card: its box less its right inset. */
	private int inkRight(JLabel label)
	{
		final Rectangle box = inCard(label);
		return box.x + box.width - label.getInsets().right;
	}

	/** The y of the middle of {@code label}'s icon, in the card: its box less its insets, halved. */
	private int inkMiddle(JLabel label)
	{
		final Rectangle box = inCard(label);
		final Insets in = label.getInsets();
		return box.y + in.top + (box.height - in.top - in.bottom) / 2;
	}

	/** The deepest component under the point (x, y) of the hero card, as a mouse there would find it. */
	private Component deepestAt(int x, int y)
	{
		return SwingUtilities.getDeepestComponentAt(panel.hero(), x, y);
	}

	/**
	 * I1: the eye is the FIRST of the card's three top-right icons - eye, Discord, settings - on both tabs (the card is the
	 * same): left of the Discord mark, with the gap between the eye's ink and the Discord mark's the same as the one between
	 * the Discord mark's and the settings icon's (8 px, {@link SupportLinks#PAIR_GAP}), the eye's 12 x 12 box centred on the
	 * line the other two are centred on, the settings icon still the right-most thing - and the total's row holds no eye: the
	 * Refresh link stands alone there, as it did before part E.
	 */
	@Test
	public void i1_theEyeIsFirstOfThreeInTheCardsTopRowOnBothTabs() throws Exception
	{
		build(new Memory());
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		for (SidebarView tab : SidebarView.values())
		{
			final String on = " (" + tab + ")";
			onEdt(() ->
			{
				panel.setView(tab);
				layout(panel);
				final JLabel eye = eye();
				final JLabel discord = topRowLabel(SupportLinks.DISCORD_TIP);
				final JLabel gear = topRowLabel(SupportLinks.SETTINGS_TIP);
				assertSame("the settings icon is the card's own", panel.gearLabel(), gear);
				assertTrue("the eye is in the top row" + on, SwingUtilities.isDescendingFrom(eye, panel.captionRow()));
				assertFalse("...and not in the total's row" + on, SwingUtilities.isDescendingFrom(eye, panel.totalRow()));

				// Eye, Discord, settings: left to right, nothing overlapping, the settings icon last.
				assertTrue("the eye is left of the Discord mark" + on, inkRight(eye) <= inkLeft(discord));
				assertTrue("the Discord mark is left of the settings icon" + on, inkRight(discord) <= inkLeft(gear));
				final int discordToSettings = inkLeft(gear) - inkRight(discord);
				final int eyeToDiscord = inkLeft(discord) - inkRight(eye);
				assertEquals("8 px between the Discord mark and the settings icon" + on, SupportLinks.PAIR_GAP,
					discordToSettings);
				assertEquals("the eye is the same gap from the Discord mark" + on, discordToSettings, eyeToDiscord);
				final int innerRight = panel.hero().getWidth() - panel.hero().getInsets().right;
				assertEquals("the settings icon is still right-most, at the card's right padding" + on, innerRight,
					inCard(gear).x + inCard(gear).width);

				// 12 x 12 boxes, centred on one line.
				assertEquals(12, eye.getIcon().getIconWidth());
				assertEquals(12, eye.getIcon().getIconHeight());
				assertEquals("the eye's box is centred on the Discord mark's line" + on, inkMiddle(discord), inkMiddle(eye));
				assertEquals("...and on the settings icon's" + on, inkMiddle(gear), inkMiddle(eye));

				// The total's row holds the Refresh link and no eye.
				for (Component c : walk(panel.totalRow()))
				{
					if (c instanceof JLabel)
					{
						final String tip = ((JLabel) c).getToolTipText();
						assertFalse("no eye in the total's row" + on, BankPriceMovementPanel.HIDE_AMOUNTS_TIP.equals(tip)
							|| BankPriceMovementPanel.SHOW_AMOUNTS_TIP.equals(tip));
					}
				}
				assertEquals("the Refresh link stands alone in its holder" + on, 1,
					panel.refreshLabel().getParent().getComponentCount());
			});
		}
	}

	/**
	 * I2: the eye's ink is grey 52 while the amounts SHOW (the open eye), grey 165 - the other icons' grey - while they are
	 * HIDDEN (the slashed eye), and white while the pointer is over it, in both states. Every pixel of the label's image is
	 * checked against design D's grid, so the ink and the shape are pinned together.
	 */
	@Test
	public void i2_theEyeIsGrey52WhileAmountsShowGrey165WhileHiddenAndWhiteWhenHovered() throws Exception
	{
		final Color shownInk = new Color(52, 52, 52);
		final Color hiddenInk = new Color(165, 165, 165);
		assertEquals("the contract's grey while the amounts show", shownInk, EyeIcon.ink(false, false));
		assertEquals("the contract's grey while they are hidden", hiddenInk, EyeIcon.ink(true, false));
		assertEquals("...which is the other icons' grey", ColorScheme.LIGHT_GRAY_COLOR, EyeIcon.ink(true, false));
		assertEquals("white under the mouse, shown", Color.WHITE, EyeIcon.ink(false, true));
		assertEquals("white under the mouse, hidden", Color.WHITE, EyeIcon.ink(true, true));

		build(new Memory());
		onEdt(() ->
		{
			final JLabel eye = eye();
			assertGrid("shown, at rest", shownGrid(), image(eye), shownInk);
			mouse(eye, MouseEvent.MOUSE_ENTERED);
			assertGrid("shown, hovered", shownGrid(), image(eye), Color.WHITE);
			mouse(eye, MouseEvent.MOUSE_EXITED);
			assertGrid("shown, at rest again", shownGrid(), image(eye), shownInk);

			press(eye, MouseEvent.BUTTON1);
			assertGrid("hidden, at rest", hiddenGrid(), image(eye), hiddenInk);
			mouse(eye, MouseEvent.MOUSE_ENTERED);
			assertGrid("hidden, hovered", hiddenGrid(), image(eye), Color.WHITE);
			mouse(eye, MouseEvent.MOUSE_EXITED);
			assertGrid("hidden, at rest again", hiddenGrid(), image(eye), hiddenInk);

			// The same ink after a press while the pointer stays over it, and back to the quiet grey on the way out.
			mouse(eye, MouseEvent.MOUSE_ENTERED);
			press(eye, MouseEvent.BUTTON1);
			assertGrid("shown again, still hovered", shownGrid(), image(eye), Color.WHITE);
			mouse(eye, MouseEvent.MOUSE_EXITED);
			assertGrid("shown again, left", shownGrid(), image(eye), shownInk);
		});
	}

	/**
	 * I3: E2's hover, press and store behaviour stands at the new place, and a press THERE reaches it: a mouse over the middle
	 * of the eye's icon, and over the far left of its 6 px of hit area, finds the eye (never the Discord mark or the card), the
	 * press delivered to what the hit test found toggles and stores once, and the old place - left of the Refresh link in the
	 * total's row - finds no eye.
	 */
	@Test
	public void i3_aPressAtTheNewPlaceReachesTheEyeAndTogglesAndStoresOnce() throws Exception
	{
		final Memory memory = new Memory();
		build(memory);
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		onEdt(() ->
		{
			layout(panel);
			final JLabel eye = eye();
			final JLabel discord = topRowLabel(SupportLinks.DISCORD_TIP);
			final Rectangle box = inCard(eye);
			final int midY = box.y + box.height / 2;
			assertSame("the middle of the eye's icon is the eye", eye, deepestAt(box.x + box.width - 6, midY));
			assertSame("the far left of its hit area is the eye", eye, deepestAt(box.x, midY));
			final Rectangle mark = inCard(discord);
			assertSame("the middle of the Discord mark is the mark, not the eye", discord,
				deepestAt(mark.x + mark.width - 6, midY));
			assertNotSame("the strip between them is neither", eye, deepestAt(box.x + box.width, midY));

			final Rectangle link = inCard(panel.refreshLabel());
			assertNotSame("the old place, left of the Refresh link, finds no eye", eye,
				deepestAt(link.x - 6, link.y + link.height / 2));

			assertEquals("Hide amounts", eye.getToolTipText());
			final Component hit = deepestAt(box.x + box.width - 6, midY);
			press(hit, MouseEvent.BUTTON3);
			assertTrue("a right press is no toggle", memory.hideSaves.isEmpty());
			press(hit, MouseEvent.BUTTON1);
			assertEquals("Show amounts", eye.getToolTipText());
			assertEquals("stored once", Collections.singletonList(true), memory.hideSaves);
			assertEquals("and the card reads the mask", AmountMask.AMOUNT, panel.totalLabel().getText());
			press(hit, MouseEvent.BUTTON1);
			assertEquals("Hide amounts", eye.getToolTipText());
			assertEquals(Arrays.asList(true, false), memory.hideSaves);
			assertEquals("nothing else was written", 0, memory.otherWrites);
		});
	}

	// ---------------------------------------------------------------- E3: the card

	private String totalText()
	{
		return panel.totalLabel().getText();
	}

	/**
	 * E3: hidden, the card's total and its gp move read the masks, the percentage is what it was, and the hover holds no digit
	 * of the total. Toggled back, the card reads what it read.
	 */
	@Test
	public void e3_theCardsTotalAndGpMoveReadTheMasksAndThePercentageStays() throws Exception
	{
		final Memory memory = new Memory();
		build(memory);
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true)));
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		final String[] shown = new String[5];
		onEdt(() ->
		{
			shown[0] = totalText();
			shown[1] = panel.deltaLabel().getText();
			shown[2] = panel.pctLabel().getText();
			shown[3] = panel.totalLabel().getToolTipText();
			shown[4] = panel.hero().getToolTipText();
			assertEquals("736m", shown[0]);
			assertEquals("-950k", shown[1]);
			assertTrue("the percentage is there to compare", shown[2].endsWith("%"));
			assertEquals("the exact total, as before", "736,412,683 gp", shown[3]);
			assertEquals(shown[3], shown[4]);

			panel.pressHideAmounts();
			assertEquals("the total reads its mask", AmountMask.AMOUNT, totalText());
			assertEquals("the gp move reads its mask", AmountMask.SHORT, panel.deltaLabel().getText());
			assertEquals("the percentage is what it was", shown[2], panel.pctLabel().getText());
			final String tip = panel.totalLabel().getToolTipText();
			assertEquals("the hover says the mask", AmountMask.AMOUNT + " gp", tip);
			assertFalse("...and holds no digit of the total: " + tip, Pattern.compile("[0-9]").matcher(tip).find());
			assertEquals("on every target of the card", tip, panel.hero().getToolTipText());
			assertEquals(tip, panel.deltaLabel().getToolTipText());
			assertEquals("the mask is five dots and three", "•••••", AmountMask.AMOUNT);
			assertEquals("•••", AmountMask.SHORT);

			panel.pressHideAmounts();
			assertEquals("toggled back: the total", shown[0], totalText());
			assertEquals("the gp move", shown[1], panel.deltaLabel().getText());
			assertEquals("the percentage", shown[2], panel.pctLabel().getText());
			assertEquals("the total's hover", shown[3], panel.totalLabel().getToolTipText());
			assertEquals("the card's hover", shown[4], panel.hero().getToolTipText());
		});
	}

	/**
	 * E3: the hover's shape when hidden - the mask and its unit, the degraded sentence under it when there is one, and nothing
	 * while the card's total is not drawn - and the shown hover unchanged beside it.
	 */
	@Test
	public void e3_theHiddenHoverIsTheMaskWithItsUnitAndTheWarningUnderIt() throws Exception
	{
		final PortfolioSummary summary = SidebarViewPanelTest.summary(736_412_683L);
		assertEquals("736,412,683 gp", BankPriceMovementPanel.valueTooltip(summary, HeroVisibility.ALL, null));
		assertEquals(AmountMask.AMOUNT + " gp", BankPriceMovementPanel.hiddenValueTooltip(HeroVisibility.ALL, null));
		assertEquals(AmountMask.AMOUNT + " gp", BankPriceMovementPanel.hiddenValueTooltip(null, ""));
		assertEquals("<html>" + AmountMask.AMOUNT + " gp<br>the wiki is down</html>",
			BankPriceMovementPanel.hiddenValueTooltip(HeroVisibility.ALL, "the wiki is down"));
		assertEquals("nothing to explain while the total is not drawn", "",
			BankPriceMovementPanel.hiddenValueTooltip(HeroVisibility.ALL.withValue(false), null));
		assertEquals("<html>736,412,683 gp<br>the wiki is down</html>",
			BankPriceMovementPanel.valueTooltip(summary, HeroVisibility.ALL, "the wiki is down"));
	}

	// ---------------------------------------------------------------- E4: an Items row

	/** Every label text under {@code root}, in tree order. */
	private static List<String> texts(Component root)
	{
		final List<String> out = new ArrayList<>();
		for (Component c : walk(root))
		{
			if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
			{
				out.add(((JLabel) c).getText());
			}
		}
		return out;
	}

	private MovementRowPanel row(int index)
	{
		return panel.rowPanels().get(index);
	}

	/** The open block's text: the one HTML label of an opened row. */
	private static String detailText(MovementRowPanel row)
	{
		for (String t : texts(row))
		{
			if (t.startsWith("<html>"))
			{
				return t;
			}
		}
		throw new AssertionError("the row has no open block");
	}

	/**
	 * E4: hidden, an Items row keeps its name and its percentage, reads the masks for its stack value, its gp change and its
	 * whole third line ("7 x 4.6k"), draws the plain picture - one item, not stackable, so the game paints no stack number -
	 * and its open block holds no gp amount or quantity and keeps its percentage and its dates. A stack of one keeps its
	 * empty item figure: the mask goes where a number would have printed.
	 */
	@Test
	public void e4_anItemsRowKeepsNameAndPercentageAndHidesAmountsQuantitiesAndTheStackNumber() throws Exception
	{
		build(new Memory());
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		final List<String> before = new ArrayList<>();
		final String[] open = new String[2];
		onEdt(() ->
		{
			assertEquals(2, panel.rowPanels().size());
			final MovementRowPanel claws = row(0);
			assertEquals("Dragon claws", claws.nameText());
			assertEquals("32.3k", claws.priceText());
			assertEquals("+3.0k", claws.gpText());
			assertEquals("+10.2%", claws.changeText());
			assertTrue("the working: 7 x 4,618: " + texts(claws), texts(claws).contains("7 x 4,618"));
			assertTrue("one item moved +428", texts(claws).contains("+428"));
			assertSame("a stack's own picture, numbered", pictures.get("11,7,false"), claws.icon());
			before.addAll(texts(claws));
			claws.toggleExpanded(claws, null);
			open[0] = detailText(claws);
			assertTrue(open[0], open[0].contains("4,618 gp each") && open[0].contains("4,190 gp")
				&& open[0].contains("32,326 gp") && open[0].contains("+428 each"));
			claws.toggleExpanded(claws, null);

			panel.pressHideAmounts();

			final MovementRowPanel masked = row(0);
			assertEquals("the name stays", "Dragon claws", masked.nameText());
			assertEquals("the percentage stays", "+10.2%", masked.changeText());
			assertEquals("the stack value", AmountMask.AMOUNT, masked.priceText());
			assertEquals("the gp change", AmountMask.SHORT, masked.gpText());
			final List<String> face = texts(masked);
			assertEquals("the stack's gp change, the whole working and the item's gp change: " + face, 3,
				Collections.frequency(face, AmountMask.SHORT));
			assertFalse("no 7 x 4,618", face.contains("7 x 4,618"));
			assertFalse("no +428", face.contains("+428"));

			// The picture: the plain one, and the numbered one was not asked for again.
			verify(items).getImage(11, 1, false);
			assertSame("the plain picture", pictures.get("11,1,false"), masked.icon());
			assertNotNull(pictures.get("11,7,false"));
			assertNotEquals("...which is not the stack's own", pictures.get("11,7,false"), masked.icon());

			// A stack of one: its item figure was empty and stays empty, its stack figure is masked.
			final MovementRowPanel penny = row(1);
			assertEquals("Penny", penny.nameText());
			assertEquals(AmountMask.AMOUNT, penny.priceText());
			assertEquals("+12.5%", penny.changeText());
			assertEquals("the stack's gp change and the working's mask: the item figure was empty and stays empty", 2,
				Collections.frequency(texts(penny), AmountMask.SHORT));

			masked.toggleExpanded(masked, null);
			open[1] = detailText(masked);
			assertTrue(open[1], open[1].contains(AmountMask.AMOUNT + " gp each"));
			assertTrue("the old price, masked, with its date: " + open[1],
				Pattern.compile("Was.*" + AmountMask.AMOUNT + " gp.*\\(\\d\\d [A-Z][a-z]{2}\\)").matcher(open[1]).find());
			assertTrue("the holding, masked: quantity and value", open[1].contains(AmountMask.SHORT + "&nbsp; =&nbsp; "
				+ AmountMask.AMOUNT + " gp"));
			assertTrue("the change: its gp masked, its percentage kept", open[1].contains(AmountMask.SHORT
				+ " each&nbsp; +10.2%"));
			for (String figure : new String[]{"4,618", "4,190", "32,326", "428", "7&nbsp;"})
			{
				assertFalse(figure + " is hidden in the open block: " + open[1], open[1].contains(figure));
			}
			masked.toggleExpanded(masked, null);

			panel.pressHideAmounts();
			assertEquals("toggled back: the face is what it was", before, texts(row(0)));
			assertSame("...and so is the picture", pictures.get("11,7,false"), row(0).icon());
			row(0).toggleExpanded(row(0), null);
			assertEquals("...and the open block, as it was", open[0], detailText(row(0)));
		});
	}

	/**
	 * E4: the open block's split note names where a stack is - "3 in bank, 4 in inventory" - and hidden it keeps the places
	 * and masks the counts; the static builders answer the same text for the same row.
	 */
	@Test
	public void e4_theSplitNoteKeepsItsPlacesAndMasksItsCounts() throws Exception
	{
		assertEquals("3 in bank, 4 in inventory", MovementRowPanel.splitLine(SPLIT));
		assertEquals("the one-argument form is the shown one", MovementRowPanel.splitLine(SPLIT),
			MovementRowPanel.splitLine(SPLIT, false));
		assertEquals(AmountMask.SHORT + " in bank, " + AmountMask.SHORT + " in inventory",
			MovementRowPanel.splitLine(SPLIT, true));
		final String shown = MovementRowPanel.detail(SPLIT, MovementWindow.D1, TODAY, ViewOptions.DEFAULT, false);
		final String hidden = MovementRowPanel.maskedDetail(SPLIT, MovementWindow.D1, TODAY, ViewOptions.DEFAULT, false);
		assertTrue(shown, shown.contains("3 in bank, 4 in inventory") && shown.contains("700 gp"));
		assertTrue(hidden, hidden.contains(AmountMask.SHORT + " in bank, " + AmountMask.SHORT + " in inventory"));
		assertFalse(hidden, hidden.contains("700") || hidden.contains("100 gp") || hidden.contains("90 gp"));
		assertTrue("its percentage and the window's label stay", hidden.contains("+11.1%") && hidden.contains("Change 1d"));
	}

	// ---------------------------------------------------------------- E5: the History tab

	private BankHistoryView historyView()
	{
		return find(panel, BankHistoryView.class);
	}

	/** The History view's chart block: its first child while there is a reading. */
	private Container block()
	{
		return (Container) historyView().getComponent(0);
	}

	private List<BankHistoryDayRow> dayRows()
	{
		final List<BankHistoryDayRow> out = new ArrayList<>();
		for (Component c : walk(historyView()))
		{
			if (c instanceof BankHistoryDayRow)
			{
				out.add((BankHistoryDayRow) c);
			}
		}
		return out;
	}

	private static String labelText(Container parent, int index)
	{
		return ((JLabel) parent.getComponent(index)).getText();
	}

	/** The six labels of a day row with a reading, in the order it adds them: date, sub-line, total, exact, percentage, gp. */
	private static String dayText(BankHistoryDayRow row, int index)
	{
		return labelText(row, index);
	}

	private BankHistoryChart chart()
	{
		return find(panel, BankHistoryChart.class);
	}

	/**
	 * E5: hidden, the History readout, a day row's totals and gp change and the chart's high and low read the masks, and every
	 * percentage, date and sub-line is what it was; and the readout keeps its unit.
	 */
	@Test
	public void e5_theHistoryTabHidesItsReadoutItsDayRowsAndTheChartsHighAndLow() throws Exception
	{
		build(new Memory());
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		onEdt(() ->
		{
			panel.setView(SidebarView.HISTORY);
			layout(panel);
			final BankHistoryChart chart = chart();
			chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);
			final Container block = block();
			final String changeGpShown = labelText(block, 1);
			final String changePct = labelText(block, 2);
			final String readoutDay = labelText(block, 3);
			assertEquals("the readout names the latest reading's exact total", "736,412,683 gp", labelText(block, 4));
			assertEquals(2, chart.figures().size());
			final List<String> figuresShown = new ArrayList<>();
			for (BankHistoryChart.Figure f : chart.figures())
			{
				figuresShown.add(f.text);
			}
			assertFalse("the compact highs and lows are numbers", figuresShown.contains(AmountMask.AMOUNT));
			final List<BankHistoryDayRow> rows = dayRows();
			BankHistoryDayRow reading = null;
			for (BankHistoryDayRow r : rows)
			{
				if (!r.model().carried())
				{
					reading = r;
					break;
				}
			}
			assertNotNull(reading);
			final String[] rowShown = new String[6];
			for (int i = 0; i < 6; i++)
			{
				rowShown[i] = dayText(reading, i);
			}
			assertFalse("a day row's totals are numbers", rowShown[2].isEmpty() || rowShown[3].isEmpty());

			panel.pressHideAmounts();
			layout(panel);
			chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);

			final Container hiddenBlock = block();
			assertEquals("the change line's gp", AmountMask.SHORT, labelText(hiddenBlock, 1));
			assertEquals("its percentage stays", changePct, labelText(hiddenBlock, 2));
			assertEquals("the readout's day stays", readoutDay, labelText(hiddenBlock, 3));
			assertEquals("the readout's total, with its unit", AmountMask.AMOUNT + " gp", labelText(hiddenBlock, 4));
			assertEquals("the high and the low are still two", 2, chart.figures().size());
			for (BankHistoryChart.Figure f : chart.figures())
			{
				assertEquals("a figure of the chart", AmountMask.AMOUNT, f.text);
			}
			BankHistoryDayRow masked = null;
			for (BankHistoryDayRow r : dayRows())
			{
				if (r.model().day().equals(reading.model().day()))
				{
					masked = r;
					break;
				}
			}
			assertNotNull(masked);
			assertEquals("the date stays", rowShown[0], dayText(masked, 0));
			assertEquals("the sub-line stays", rowShown[1], dayText(masked, 1));
			assertEquals("the day's total", AmountMask.AMOUNT, dayText(masked, 2));
			assertEquals("its exact total", AmountMask.AMOUNT, dayText(masked, 3));
			assertEquals("the percentage stays", rowShown[4], dayText(masked, 4));
			assertEquals("the gp change", rowShown[5].isEmpty() ? "" : AmountMask.SHORT, dayText(masked, 5));
			assertNotEquals("and a change of gp there was", "", rowShown[5]);

			panel.pressHideAmounts();
			layout(panel);
			chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);
			assertEquals("toggled back: the change line's gp", changeGpShown, labelText(block(), 1));
			assertEquals("the readout", "736,412,683 gp", labelText(block(), 4));
			final List<String> figuresBack = new ArrayList<>();
			for (BankHistoryChart.Figure f : chart.figures())
			{
				figuresBack.add(f.text);
			}
			assertEquals("the chart's high and low", figuresShown, figuresBack);
		});
	}

	// ---------------------------------------------------------------- E6: the masks, and toggling back

	/**
	 * E6: a one-digit amount and a twelve-digit one mask to the same text - on the card's total, on a row's stack value and gp
	 * change - and toggling back restores every text of the sidebar exactly, on both tabs.
	 */
	@Test
	public void e6_aOneDigitAndATwelveDigitAmountMaskAlikeAndTogglingBackRestoresEveryText() throws Exception
	{
		build(new Memory());
		publish(Arrays.asList(PENNY, WHALE, CLAWS), 7L, rising());
		final List<String> itemsBefore = new ArrayList<>();
		final List<String> historyBefore = new ArrayList<>();
		onEdt(() ->
		{
			assertEquals("a total of seven gp", "7", totalText());
			assertEquals("a stack of 9 gp", "9", row(0).priceText());
			assertTrue("a stack of 123,456,789,012 gp is another, longer figure", row(1).priceText().length() > 1);
			// The History view builds its labels the first time it is shown, so both tabs are visited once before anything is
			// compared: what is walked is then the same tree before and after.
			panel.setView(SidebarView.HISTORY);
			historyBefore.addAll(allTexts(false));
			panel.setView(SidebarView.ITEMS);
			itemsBefore.addAll(allTexts(true));

			panel.pressHideAmounts();
			assertEquals("a total of seven gp", AmountMask.AMOUNT, totalText());
			assertEquals("a one-digit stack value", AmountMask.AMOUNT, row(0).priceText());
			assertEquals("a twelve-digit stack value", AmountMask.AMOUNT, row(1).priceText());
			assertEquals("one gp of change", AmountMask.SHORT, row(0).gpText());
			assertEquals("987,654 gp of change on a stack of a million", AmountMask.SHORT, row(1).gpText());
		});
		publish(Arrays.asList(PENNY, WHALE, CLAWS), 1_234_567_890_123L, rising());
		onEdt(() ->
		{
			assertEquals("a total of a trillion gp reads the same", AmountMask.AMOUNT, totalText());
			panel.setView(SidebarView.HISTORY);
			final Container block = block();
			assertEquals("the readout reads the same whatever the total", AmountMask.AMOUNT + " gp", labelText(block, 4));
			panel.setView(SidebarView.ITEMS);
		});
		publish(Arrays.asList(PENNY, WHALE, CLAWS), 7L, rising());
		onEdt(() ->
		{
			panel.pressHideAmounts();
			assertSameTexts("toggled back: every text on the Items tab, exactly", itemsBefore, allTexts(true));
			panel.setView(SidebarView.HISTORY);
			assertSameTexts("and on the History tab, drawn as it arrives", historyBefore, allTexts(false));
		});
	}

	/** The two lists are equal; when they are not, the message names the first text that differs. */
	private static void assertSameTexts(String what, List<String> want, List<String> got)
	{
		for (int i = 0; i < Math.min(want.size(), got.size()); i++)
		{
			assertEquals(what + " (text " + i + ")", want.get(i), got.get(i));
		}
		assertEquals(what + " (how many texts)", want.size(), got.size());
	}

	/**
	 * Every label's text and every hover in the panel's tree, in tree order, the card's included - of the tab ON SCREEN only:
	 * the other tab is redrawn when it arrives (part B's pattern), so what it still holds meanwhile is not on screen.
	 */
	private List<String> allTexts(boolean itemsTab)
	{
		final List<String> out = new ArrayList<>();
		final Component other = itemsTab ? historyView() : panel.scrollPane();
		for (Component c : walk(panel))
		{
			if (SwingUtilities.isDescendingFrom(c, other))
			{
				continue;
			}
			if (c instanceof JLabel && ((JLabel) c).getText() != null && !((JLabel) c).getText().isEmpty())
			{
				out.add(((JLabel) c).getText());
			}
			if (c instanceof JComponent && ((JComponent) c).getToolTipText() != null)
			{
				out.add("tip:" + ((JComponent) c).getToolTipText());
			}
		}
		return out;
	}

	/**
	 * E6: while hidden NOTHING the fixture's numbers could be read from is on screen - every compact figure, exact figure and
	 * signed change the shown sidebar printed is absent from every label and every hover of the panel, on both tabs.
	 */
	@Test
	public void e6_whileHiddenNoFigureOfTheFixtureIsOnScreenOnEitherTab() throws Exception
	{
		build(new Memory());
		onEdt(() -> panel.applyOptions(ViewOptions.DEFAULT.withShowHoverText(true)));
		publish(Arrays.asList(CLAWS, SPLIT), VALUE_NOW, rising());
		final List<String> figures = Arrays.asList("736m", "736,412,683", "32.3k", "4,618", "+3.0k", "+428", "-950k", "951",
			"700", "+10", "4,618", "32,326");
		onEdt(() ->
		{
			final List<String> shown = allTexts(true);
			for (String figure : figures.subList(0, 6))
			{
				assertTrue(figure + " is on the shown sidebar", containsAny(shown, figure));
			}
			row(0).toggleExpanded(row(0), null);
			panel.pressHideAmounts();
			final List<String> items = allTexts(true);
			assertFalse("an Items label that is not a mask still holds a figure", containsAny(items, figures.get(0),
				figures.get(1), figures.get(2), figures.get(3), figures.get(4), figures.get(5), figures.get(6),
				figures.get(10), figures.get(11)));
			panel.setView(SidebarView.HISTORY);
			final List<String> history = allTexts(false);
			assertFalse("a History label that is not a mask still holds a figure", containsAny(history, figures.get(0),
				figures.get(1)));
			for (BankHistoryDayRow row : dayRows())
			{
				if (!row.model().carried())
				{
					assertEquals(AmountMask.AMOUNT, dayText(row, 2));
					assertEquals(AmountMask.AMOUNT, dayText(row, 3));
				}
			}
		});
	}

	private static boolean containsAny(List<String> texts, String... figures)
	{
		for (String text : texts)
		{
			for (String figure : figures)
			{
				if (text.contains(figure))
				{
					return true;
				}
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- E7: the panel's half

	/**
	 * E7, the panel's half: the road a config change takes, {@code applyHideAmounts}, hides or shows at once - the card and the
	 * tab on screen from what the panel already holds, the other tab when next shown - and neither writes anything nor asks
	 * the service for anything; the eye's own press writes exactly once. A panel built over a stored "hidden" opens hidden.
	 */
	@Test
	public void e7_aChangeFromOutsideRedrawsAtOnceAndWritesNothingAndAsksNoOne() throws Exception
	{
		final Memory memory = new Memory();
		build(memory);
		publish(Arrays.asList(CLAWS, PENNY), VALUE_NOW, rising());
		clearInvocations(service);
		onEdt(() ->
		{
			// The History tab has been drawn once, shown, so what it holds while the Items tab is on screen is real figures
			// that must be redrawn when it arrives - not a first draw, which reads the switch as it is.
			panel.setView(SidebarView.HISTORY);
			assertEquals("736,412,683 gp", labelText(block(), 4));
			panel.setView(SidebarView.ITEMS);
			assertEquals("736m", totalText());
			panel.applyHideAmounts(true);
			assertEquals("at once: the card", AmountMask.AMOUNT, totalText());
			assertEquals("...and the rows", AmountMask.AMOUNT, row(0).priceText());
			assertEquals("...and the eye", BankPriceMovementPanel.SHOW_AMOUNTS_TIP, eye().getToolTipText());
			panel.applyHideAmounts(true);
			assertEquals("the same state again is a redraw", AmountMask.AMOUNT, totalText());
			// The tab that was not on screen is drawn when it arrives.
			panel.setView(SidebarView.HISTORY);
			assertEquals("the History tab arrives hidden", AmountMask.AMOUNT + " gp", labelText(block(), 4));
			panel.applyHideAmounts(false);
			assertEquals("shown again at once on the tab on screen", "736,412,683 gp", labelText(block(), 4));
			assertEquals("...and the card", "736m", totalText());
			panel.setView(SidebarView.ITEMS);
			assertEquals("the Items tab arrives shown", "32.3k", row(0).priceText());
		});
		verifyNoInteractions(service);
		assertTrue("a change from outside writes nothing", memory.hideSaves.isEmpty());
		assertEquals("...nor anything else", 0, memory.otherWrites);

		onEdt(() ->
		{
			panel.pressHideAmounts();
			panel.pressHideAmounts();
		});
		assertEquals("the eye's presses write once each", Arrays.asList(true, false), memory.hideSaves);
		verifyNoInteractions(service);

		onEdt(() -> panel.stop());
		final Memory stored = new Memory();
		stored.hide = true;
		build(stored);
		publish(Arrays.asList(CLAWS), VALUE_NOW, rising());
		onEdt(() ->
		{
			assertEquals("opens hidden over a stored hide", AmountMask.AMOUNT, totalText());
			assertEquals(AmountMask.AMOUNT, row(0).priceText());
			assertEquals("the eye is shut", BankPriceMovementPanel.SHOW_AMOUNTS_TIP, eye().getToolTipText());
			verify(items, never()).getImage(11, 7, false);
			verify(items).getImage(11, 1, false);
		});
		assertTrue("seeding writes nothing", stored.hideSaves.isEmpty());
	}
}
