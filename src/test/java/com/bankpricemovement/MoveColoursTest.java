package com.bankpricemovement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import net.runelite.client.ui.ColorScheme;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 1.1.0 part B (contract {@code docs/handoff/contract-1.1.0-settings-and-colours-2026-10-03.md}): the palette behind
 * {@link Widgets#move} - frozen tests B1 and B2 - and where the colour picker stands, B7b.
 *
 * <p>The palette is one static for the whole process, so every test that sets it clears it in {@code @After}: no other test
 * may see it.
 */
public class MoveColoursTest
{
	/** What a reader picks for a rise and for a fall: neither equals a built-in colour. */
	private static final Color UP = new Color(10, 20, 200);
	private static final Color DOWN = new Color(200, 150, 0);

	@After
	public void clearThePalette()
	{
		Widgets.setMoveColours(null, null);
	}

	/** {@code from} mixed 55 % of the way into the card's grey, as {@link Widgets.Kind#QUIET} is documented to be. */
	private static Color quiet(Color from)
	{
		final Color to = ColorScheme.DARKER_GRAY_COLOR;
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * 0.55),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * 0.55),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * 0.55));
	}

	// ---------------------------------------------------------------- B1: no palette, today's colours

	/** B1: with no palette every {@code move(signum, kind)} is exactly what it returned before the palette existed. */
	@Test
	public void b1_withNoPaletteEveryMoveColourIsExactlyWhatItWasBefore() throws Exception
	{
		// A rise: the client's green for a figure and a mark, its darker() for an edge, the figure pushed 55 % into the card for the quiet one.
		assertEquals(new Color(55, 240, 70), Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(new Color(55, 240, 70), Widgets.move(1, Widgets.Kind.MARK));
		assertEquals(new Color(38, 168, 49), Widgets.move(1, Widgets.Kind.EDGE));
		assertEquals(new Color(41, 124, 48), Widgets.move(1, Widgets.Kind.QUIET));
		// A fall: the lifted red for the figure, the client's own red for the mark, the darker() of THAT for the edge.
		assertEquals(new Color(240, 92, 84), Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(new Color(230, 30, 30), Widgets.move(-1, Widgets.Kind.MARK));
		assertEquals(new Color(161, 21, 21), Widgets.move(-1, Widgets.Kind.EDGE));
		assertEquals(new Color(124, 58, 54), Widgets.move(-1, Widgets.Kind.QUIET));
		// Flat or none: the quiet grey, the card's own grey for an edge.
		for (final Widgets.Kind kind : Widgets.Kind.values())
		{
			assertEquals(kind == Widgets.Kind.EDGE ? new Color(30, 30, 30) : new Color(165, 165, 165),
				Widgets.move(0, kind));
		}
		// The constants those numbers stand for.
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(Widgets.MOVE_DOWN_TEXT, Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, Widgets.move(-1, Widgets.Kind.MARK));
	}

	// ---------------------------------------------------------------- B2: a palette

	/**
	 * B2: with up = c and down = d the four kinds follow the contract - a figure and a mark are the colour, the quiet figure
	 * is it 55 % toward the card, an edge is its darker() - a direction that has no colour set keeps its built-in one, and
	 * a flat move is untouched.
	 */
	@Test
	public void b2_aPaletteColoursTheFourKindsOfEachDirectionAndLeavesTheFlatOneAlone() throws Exception
	{
		Widgets.setMoveColours(UP, DOWN);

		assertEquals(UP, Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(UP, Widgets.move(1, Widgets.Kind.MARK));
		assertEquals(UP.darker(), Widgets.move(1, Widgets.Kind.EDGE));
		assertEquals(quiet(UP), Widgets.move(1, Widgets.Kind.QUIET));

		assertEquals(DOWN, Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals("a chosen fall is one colour for the figure AND the mark", DOWN, Widgets.move(-1, Widgets.Kind.MARK));
		assertEquals(DOWN.darker(), Widgets.move(-1, Widgets.Kind.EDGE));
		assertEquals(quiet(DOWN), Widgets.move(-1, Widgets.Kind.QUIET));

		for (final Widgets.Kind kind : Widgets.Kind.values())
		{
			assertEquals("a flat move is untouched: " + kind,
				kind == Widgets.Kind.EDGE ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR,
				Widgets.move(0, kind));
		}

		// One direction only: the other keeps today's colours.
		Widgets.setMoveColours(UP, null);
		assertEquals(UP, Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(Widgets.MOVE_DOWN_TEXT, Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, Widgets.move(-1, Widgets.Kind.MARK));
		Widgets.setMoveColours(null, DOWN);
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(DOWN, Widgets.move(-1, Widgets.Kind.MARK));
	}

	/**
	 * B2: setting the defaults is no palette - the config's own defaults ({@code ColorScheme.PROGRESS_COMPLETE_COLOR},
	 * {@link Widgets#MOVE_DOWN_TEXT}) are read as "nothing chosen", so the built-in red mark and edge stay the deeper red
	 * and a reader who never touched the setting sees the sidebar exactly as before - and an opaque copy of a default,
	 * or one with transparency, is the same default.
	 */
	@Test
	public void b2_settingTheDefaultsIsNoPaletteAndTransparencyIsDropped() throws Exception
	{
		Widgets.setMoveColours(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.MOVE_DOWN_TEXT);
		assertSame("a default rise is the very constant: no palette", ColorScheme.PROGRESS_COMPLETE_COLOR,
			Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals(new Color(38, 168, 49), Widgets.move(1, Widgets.Kind.EDGE));
		assertEquals(new Color(41, 124, 48), Widgets.move(1, Widgets.Kind.QUIET));
		assertEquals(new Color(230, 30, 30), Widgets.move(-1, Widgets.Kind.MARK));
		assertEquals(new Color(161, 21, 21), Widgets.move(-1, Widgets.Kind.EDGE));
		assertEquals(new Color(124, 58, 54), Widgets.move(-1, Widgets.Kind.QUIET));
		assertEquals(new Color(240, 92, 84), Widgets.move(-1, Widgets.Kind.FIGURE));

		Widgets.setMoveColours(new Color(55, 240, 70, 90), new Color(Widgets.MOVE_DOWN_TEXT.getRGB()));
		assertEquals("a see-through default is still the default", new Color(38, 168, 49),
			Widgets.move(1, Widgets.Kind.EDGE));
		assertEquals(new Color(230, 30, 30), Widgets.move(-1, Widgets.Kind.MARK));

		// A colour that is NOT a default is drawn opaque whatever alpha it came with.
		Widgets.setMoveColours(new Color(10, 20, 200, 40), null);
		assertEquals(255, Widgets.move(1, Widgets.Kind.FIGURE).getAlpha());
		assertEquals(UP, Widgets.move(1, Widgets.Kind.FIGURE));

		// The client's own red is NOT the down default (the lifted red is): chosen, it is one colour for every kind.
		Widgets.setMoveColours(null, ColorScheme.PROGRESS_ERROR_COLOR);
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), Widgets.move(-1, Widgets.Kind.EDGE));

		// And clearing puts today's colours back.
		Widgets.setMoveColours(null, null);
		assertEquals(new Color(240, 92, 84), Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(new Color(55, 240, 70), Widgets.move(1, Widgets.Kind.FIGURE));
	}

	/** The Refresh glow keeps its own green: it marks a held bank change, not a rise, whatever the reader picks. */
	@Test
	public void theRefreshGlowKeepsItsOwnGreenWhateverTheReaderPicks() throws Exception
	{
		Widgets.setMoveColours(UP, DOWN);
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, BankPriceMovementPanel.GLOW_COLOUR);
	}

	// ---------------------------------------------------------------- B7b: where the picker stands

	private static final Rectangle SCREEN = new Rectangle(0, 0, 1920, 1080);
	private static final Dimension PICKER = new Dimension(400, 300);

	private static void assertInside(Rectangle screen, Point spot, Dimension size)
	{
		assertTrue("inside the screen on the left and top: " + spot, spot.x >= screen.x && spot.y >= screen.y);
		assertTrue("inside the screen on the right and bottom: " + spot,
			spot.x + size.width <= screen.x + screen.width && spot.y + size.height <= screen.y + screen.height);
	}

	/** B7b: with room on the left the picker stands left of the panel, 8 px clear of it, its top level with the panel's. */
	@Test
	public void b7b_withRoomOnTheLeftThePickerStandsLeftOfThePanelWithTheTopsLevel()
	{
		final Rectangle panel = new Rectangle(1500, 120, 225, 800);
		final Point spot = RuneLiteColourPicker.pickerSpot(panel, PICKER, SCREEN);
		assertEquals(new Point(1500 - 400 - 8, 120), spot);
		assertEquals("its right edge is 8 px short of the panel's left", panel.x - 8, spot.x + PICKER.width);
		assertEquals("the tops are level", panel.y, spot.y);
		assertInside(SCREEN, spot, PICKER);
		assertTrue("and it does not cover the panel", spot.x + PICKER.width <= panel.x);
	}

	/** B7b: with exactly enough room on the left it is still on the left; one pixel less and it is on the right. */
	@Test
	public void b7b_theLeftSideIsTakenWhileItFitsAndTheRightOnlyWhenItDoesNot()
	{
		final Rectangle fits = new Rectangle(408, 50, 225, 800);
		assertEquals(new Point(0, 50), RuneLiteColourPicker.pickerSpot(fits, PICKER, SCREEN));
		final Rectangle short1 = new Rectangle(407, 50, 225, 800);
		final Point right = RuneLiteColourPicker.pickerSpot(short1, PICKER, SCREEN);
		assertEquals("one pixel short on the left: the right of the panel", new Point(407 + 225 + 8, 50), right);
		assertInside(SCREEN, right, PICKER);
	}

	/** B7b: no room on the left puts it to the right of the panel, 8 px clear, the tops still level. */
	@Test
	public void b7b_withNoRoomOnTheLeftItStandsRightOfThePanel()
	{
		final Rectangle panel = new Rectangle(300, 100, 225, 800);
		final Point spot = RuneLiteColourPicker.pickerSpot(panel, PICKER, SCREEN);
		assertEquals(new Point(300 + 225 + 8, 100), spot);
		assertEquals(panel.y, spot.y);
		assertTrue("and it does not cover the panel", spot.x >= panel.x + panel.width);
		assertInside(SCREEN, spot, PICKER);
	}

	/** B7b: with room on neither side it falls back to the screen's left edge, inside the screen. */
	@Test
	public void b7b_withRoomOnNeitherSideItFallsBackToTheScreensLeftEdge()
	{
		final Rectangle panel = new Rectangle(100, 100, 1700, 800);
		final Point spot = RuneLiteColourPicker.pickerSpot(panel, PICKER, SCREEN);
		assertEquals(new Point(0, 100), spot);
		assertInside(SCREEN, spot, PICKER);

		// The same on a second monitor, whose left edge is not 0.
		final Rectangle second = new Rectangle(1920, 0, 1920, 1080);
		final Rectangle onSecond = new Rectangle(2000, 40, 1700, 800);
		final Point there = RuneLiteColourPicker.pickerSpot(onSecond, PICKER, second);
		assertEquals(new Point(1920, 40), there);
		assertInside(second, there, PICKER);
	}

	/** B7b: always inside the screen - the top follows the panel's but is pulled up or down as far as the screen needs. */
	@Test
	public void b7b_theTopIsThePanelsButNeverLeavesTheScreen()
	{
		// A panel whose top is below where a 300 px picker would still fit: the picker is pulled up to the bottom edge.
		final Rectangle low = new Rectangle(1500, 900, 225, 800);
		final Point pulledUp = RuneLiteColourPicker.pickerSpot(low, PICKER, SCREEN);
		assertEquals(new Point(1092, 1080 - 300), pulledUp);
		assertInside(SCREEN, pulledUp, PICKER);

		// A panel scrolled partly off the top of the screen: the picker is pushed down to the screen's top.
		final Rectangle high = new Rectangle(1500, -60, 225, 800);
		final Point pushedDown = RuneLiteColourPicker.pickerSpot(high, PICKER, SCREEN);
		assertEquals(new Point(1092, 0), pushedDown);
		assertInside(SCREEN, pushedDown, PICKER);

		// A second monitor above the first, whose top is negative.
		final Rectangle above = new Rectangle(0, -1080, 1920, 1080);
		final Rectangle panelAbove = new Rectangle(1500, -1200, 225, 800);
		final Point up = RuneLiteColourPicker.pickerSpot(panelAbove, PICKER, above);
		assertEquals(new Point(1092, -1080), up);
		assertInside(above, up, PICKER);
	}
}
