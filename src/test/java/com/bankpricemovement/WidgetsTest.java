package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * {@link Widgets}' addendum-N half (N sections 2, 3.1-3.5 and 7) as addendum O left it - the Ticker look's
 * members only (O1) - off-screen on the EDT.
 *
 * <p>Four risks are what this file exists for. (1) The sidebar is 213 px wide and never scrolls sideways, so
 * a chip that wants more room than its cell is silently CLIPPED - the width assertions measure the widest
 * label each strip can be handed ("180d") against the 38 px cell the hero card gives it. (2) Every arrow, tick
 * and dot is code-drawn because the bitmap RuneScape faces draw a box for a glyph they lack (playbook 7.5),
 * so each glyph is painted here and inspected for ink; painting them in a test also proves they need no
 * display. (3) Lighting a chip must not change the strip's height, or the hero card would jump every time a
 * window is picked. (4) The pre-existing members are load-bearing for the shipped panel, so
 * {@link Widgets#triangle(boolean)} is compared PIXEL FOR PIXEL against the overload that now backs it.
 */
public class WidgetsTest
{
	/** The five window labels, the widest of which ("180d") sets the strip's cell width. */
	private static final String[] WINDOW_LABELS = {"1d", "7d", "30d", "90d", "180d"};

	/** The window cell: five inside the hero card's 191 px of inner width (N 4.2), rounded down. */
	private static final int CELL = 38;

	/** The fold's preset cell (N 3.5): four of them plus three 3 px gaps make the fold's 205 px. */
	private static final int PRESET = 49;

	// ---------------------------------------------------------------- the sans scale (N section 3 §2)

	@Test
	public void sansIsTheLogicalDialogFaceAtTheSizeAskedFor() throws Exception
	{
		onEdt(() ->
		{
			for (int size : new int[]{28, 18, 14, 13, 12, 11})
			{
				final Font plain = Widgets.sans(size);
				assertEquals("the logical Dialog face, per FontManager.java:93", Font.DIALOG, plain.getName());
				assertEquals(size, plain.getSize());
				assertTrue("sans is plain", plain.isPlain());

				final Font bold = Widgets.sansBold(size);
				assertEquals("the logical Dialog face, per FontManager.java:94", Font.DIALOG, bold.getName());
				assertEquals(size, bold.getSize());
				assertTrue("sansBold is bold", bold.isBold());
			}

			assertEquals("the float and int overloads agree", Widgets.sans(14), Widgets.sans(14f));
			assertEquals(Widgets.sansBold(14), Widgets.sansBold(14f));
		});
	}

	/**
	 * {@code deriveFont} answers a new font, so a caller asking for 28 px must not have resized the shared
	 * {@code FontManager} constant every other RuneLite panel is drawn with.
	 */
	@Test
	public void derivingASizeDoesNotMutateFontManagersFonts() throws Exception
	{
		onEdt(() ->
		{
			Widgets.sans(28);
			Widgets.sansBold(28);
			assertEquals(16, FontManager.getDefaultFont().getSize());
			assertEquals(16, FontManager.getDefaultBoldFont().getSize());
			assertTrue(FontManager.getDefaultBoldFont().isBold());
		});
	}

	@Test
	public void labelWithAFontAndColourSetsBoth() throws Exception
	{
		onEdt(() ->
		{
			final Font font = Widgets.sansBold(18);
			final JLabel l = Widgets.label("+1.8%", font, ColorScheme.PROGRESS_COMPLETE_COLOR);
			assertEquals("+1.8%", l.getText());
			assertEquals(font, l.getFont());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, l.getForeground());
			assertEquals("a null text is an empty label, not an NPE", "",
				Widgets.label(null, font, Color.WHITE).getText());
		});
	}

	// ---------------------------------------------------------------- chips (N section 2, N 4.4 control 1)

	/** A segment is made unlit: plain grey text, transparent, an empty border where the underline will go. */
	@Test
	public void aFreshSegmentIsUnlitPlainGreyAndTransparent() throws Exception
	{
		onEdt(() ->
		{
			final JLabel cell = Widgets.segment("30d", "Guide-price change over the last 30d", null);
			assertEquals("30d", cell.getText());
			assertEquals("Guide-price change over the last 30d", cell.getToolTipText());
			assertEquals(SwingConstants.CENTER, cell.getHorizontalAlignment());
			assertFalse(Widgets.isLit(cell));
			assertFalse("the hero card's ground shows through a chip", cell.isOpaque());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, cell.getForeground());
			assertFalse("an unlit chip is plain, not bold", cell.getFont().isBold());
			assertTrue(cell.getBorder() instanceof EmptyBorder);
			assertEquals(Widgets.CHIP_UNDERLINE, cell.getBorder().getBorderInsets(cell).bottom);
		});
	}

	@Test
	public void aChipIsTransparentAndCarriesAnOrangeRuleWhenLit() throws Exception
	{
		onEdt(() ->
		{
			final JLabel cell = Widgets.segment("180d", null, null);

			Widgets.chip(cell, true);
			assertFalse("the hero card's ground shows through a lit chip too", cell.isOpaque());
			assertEquals(ColorScheme.BRAND_ORANGE, cell.getForeground());
			assertTrue(cell.getFont().isBold());
			assertTrue(cell.getBorder() instanceof MatteBorder);
			assertEquals(ColorScheme.BRAND_ORANGE, ((MatteBorder) cell.getBorder()).getMatteColor());
			assertEquals(Widgets.CHIP_UNDERLINE, cell.getBorder().getBorderInsets(cell).bottom);
			assertTrue(Widgets.isLit(cell));

			Widgets.chip(cell, false);
			assertFalse(cell.isOpaque());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, cell.getForeground());
			assertFalse("an unlit chip is plain, not bold", cell.getFont().isBold());
			assertFalse(Widgets.isLit(cell));
		});
	}

	/**
	 * N 4.4 control 1: the unlit cell keeps an {@link EmptyBorder} of the underline's height "so the height
	 * never jumps on selection". A jumping strip inside the hero card would move the whole list.
	 */
	@Test
	public void lightingAChipDoesNotChangeItsHeight() throws Exception
	{
		onEdt(() ->
		{
			final JLabel cell = Widgets.segment("180d", null, null);

			Widgets.chip(cell, false);
			final Insets unlit = cell.getBorder().getBorderInsets(cell);
			final int unlitHeight = cell.getPreferredSize().height;
			assertTrue(cell.getBorder() instanceof EmptyBorder);

			Widgets.chip(cell, true);
			final Insets lit = cell.getBorder().getBorderInsets(cell);
			assertEquals(unlit.top, lit.top);
			assertEquals(unlit.bottom, lit.bottom);
			assertEquals("the strip must not move when a window is picked", unlitHeight,
				cell.getPreferredSize().height);
		});
	}

	/**
	 * {@code renderChips} repaints all nine cells of the strip and the fold on every publish, so a chip already
	 * painted the way it is being asked for takes one shared face and one shared border rather than a new pair -
	 * and a cell the MOUSE is over is still put back to its resting colour, because {@link Widgets#hoverChip}
	 * paints an unlit cell orange without lighting it.
	 */
	@Test
	public void repaintingAChipReusesOneFaceAndStillPutsAHoveredCellBack() throws Exception
	{
		onEdt(() ->
		{
			final JLabel lit = Widgets.segment("30d", null, null);
			final JLabel other = Widgets.segment("90d", null, null);
			Widgets.chip(lit, true);
			Widgets.chip(other, true);
			assertSame("one lit face for every chip", lit.getFont(), other.getFont());
			assertSame("one lit border", lit.getBorder(), other.getBorder());

			final Font face = lit.getFont();
			final Border border = lit.getBorder();
			Widgets.chip(lit, true);
			assertSame("painting it the way it already is changes nothing", face, lit.getFont());
			assertSame(border, lit.getBorder());

			Widgets.chip(lit, false);
			Widgets.hoverChip(lit, true);
			assertEquals(ColorScheme.BRAND_ORANGE, lit.getForeground());
			Widgets.chip(lit, false);
			assertEquals("a repaint under the mouse still paints the resting colour",
				ColorScheme.LIGHT_GRAY_COLOR, lit.getForeground());
			assertFalse(Widgets.isLit(lit));
		});
	}

	/** {@link Widgets#isLit} reads what {@link Widgets#chip} recorded; a label it never painted is not lit. */
	@Test
	public void aLabelNeverPaintedIsNotLit() throws Exception
	{
		onEdt(() ->
		{
			final JLabel plain = new JLabel("1d");
			assertFalse(Widgets.isLit(plain));
			plain.setForeground(ColorScheme.BRAND_ORANGE);
			assertFalse("orange text alone is not a lit chip - a hovered chip is orange too", Widgets.isLit(plain));
			Widgets.chip(plain, true);
			assertTrue(Widgets.isLit(plain));
		});
	}

	@Test
	public void hoverLightsAnUnlitChipAndLeavesTheLitOneAlone() throws Exception
	{
		onEdt(() ->
		{
			final JLabel cell = Widgets.segment("7d", null, null);
			Widgets.hoverChip(cell, true);
			assertEquals(ColorScheme.BRAND_ORANGE, cell.getForeground());
			assertFalse("hover is a colour, not a light", Widgets.isLit(cell));
			Widgets.hoverChip(cell, false);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, cell.getForeground());

			Widgets.chip(cell, true);
			Widgets.hoverChip(cell, true);
			assertEquals("a lit chip is not a control", ColorScheme.BRAND_ORANGE, cell.getForeground());
			assertTrue(cell.getFont().isBold());
			Widgets.hoverChip(cell, false);
			assertEquals("...and leaving it does not dim it", ColorScheme.BRAND_ORANGE, cell.getForeground());
		});
	}

	/** N 3.2: a press on an unlit segment selects it; a press on the lit one is a no-op. */
	@Test
	public void aSegmentFiresOnlyWhileItIsUnlit() throws Exception
	{
		onEdt(() ->
		{
			final AtomicInteger clicks = new AtomicInteger();
			final JLabel cell = Widgets.segment("90d", "the 90 day window", clicks::incrementAndGet);
			assertEquals("90d", cell.getText());
			assertEquals("the 90 day window", cell.getToolTipText());

			press(cell);
			assertEquals(1, clicks.get());

			Widgets.chip(cell, true);
			press(cell);
			assertEquals("clicking the lit chip is a no-op", 1, clicks.get());

			Widgets.chip(cell, false);
			press(cell);
			assertEquals(2, clicks.get());
		});
	}

	/**
	 * N 3.1 / O4: the hero card's right-click is the card's own menu, and the card keeps its window chips and
	 * its "Refresh" link INSIDE it - so a right-button press, or the platform's popup trigger, on a segment or
	 * a link must leave the menu as the only answer. Only the left button presses ({@link Widgets#isPress}).
	 */
	@Test
	public void aRightButtonPressIsNeverAPress() throws Exception
	{
		onEdt(() ->
		{
			final AtomicInteger clicks = new AtomicInteger();
			final JLabel cell = Widgets.segment("7d", null, clicks::incrementAndGet);
			final JLabel link = link("Refresh", clicks::incrementAndGet);
			rightPress(cell);
			rightPress(link);
			popupPress(cell);
			popupPress(link);
			assertEquals("a right button or a popup trigger is a menu gesture, not a press", 0, clicks.get());

			press(cell);
			press(link);
			assertEquals(2, clicks.get());
		});
	}

	// ---------------------------------------------------------------- the width rule at 213 px (N6)

	/**
	 * The widest label the window strip can be handed ("180d") fits inside one 38 px cell at the chip face,
	 * lit or unlit, and the fold's presets fit their 49 px cells.
	 */
	@Test
	public void everyWindowChipAndPresetFitsItsCell() throws Exception
	{
		onEdt(() ->
		{
			for (String text : WINDOW_LABELS)
			{
				final JLabel cell = Widgets.segment(text, null, null);
				Widgets.chip(cell, true);
				assertTrue(text + " overflows the " + CELL + " px cell: " + cell.getPreferredSize().width,
					cell.getPreferredSize().width <= CELL);

				// The unlit face is no wider than the lit one, so a strip cannot grow when it is re-lit.
				Widgets.chip(cell, false);
				assertTrue(cell.getPreferredSize().width <= CELL);
			}
			for (String text : new String[]{"All", "100k+", "1m+", "10m+"})
			{
				final JLabel cell = Widgets.segment(text, null, null);
				Widgets.chip(cell, true);
				assertTrue(text + " overflows the " + PRESET + " px preset cell: " + cell.getPreferredSize().width,
					cell.getPreferredSize().width <= PRESET);
			}
		});
	}

	/** N 3.5: four 49 px presets and three 3 px gaps are the fold's 205 px - the sidebar's 213 less 4 + 4 of padding. */
	@Test
	public void theFoldsPresetStripIsTheFoldsInnerWidth() throws Exception
	{
		onEdt(() ->
		{
			final String[] labels = {"All", "100k+", "1m+", "10m+"};
			final JLabel[] cells = new JLabel[labels.length];
			for (int i = 0; i < labels.length; i++)
			{
				cells[i] = Widgets.fixed(Widgets.segment(labels[i], null, null), PRESET, 24);
				Widgets.chip(cells[i], i == 0);
			}

			final JPanel strip = Widgets.grid(3, cells);
			assertEquals("four 49 px cells and three 3 px gaps are the fold's inner width",
				Widgets.CONTENT_WIDTH - 8, strip.getPreferredSize().width);
			assertTrue(strip.getPreferredSize().width <= Widgets.CONTENT_WIDTH);
		});
	}

	/**
	 * The worst figures the formatters can produce still fit the card's 191 px of inner width (N6, O6 for
	 * every combination of the switches - the widest move line is the pair), the card's two CONSTANT sentences
	 * fit it outright (the caption row's words, and the update line of addendum S line S3), and the sentences
	 * that are only ever FITTED - the footnote and the problem line - come back inside their room whatever this
	 * machine's Dialog metrics are.
	 */
	@Test
	public void theWidestHeroFiguresFitTheCard() throws Exception
	{
		onEdt(() ->
		{
			final int inner = Widgets.CONTENT_WIDTH - Widgets.EDGE_WIDTH - 9 - 10;
			assertEquals(191, inner);

			// Numbers are never truncated: a cut price is a wrong price, so these have to fit outright.
			assertFits("999.9b", Widgets.sansBold(28), inner);
			final int moveLine = 7 + 5 + width("-999.9m", Widgets.sansBold(18)) + 8 + width("-100.0%", Widgets.sansBold(18));
			assertTrue("the widest move line - triangle, gp, percent - asks for " + moveLine + " of " + inner, moveLine <= inner);
			assertFits("180d", Widgets.sansBold(13), CELL);

			// The caption row is not fitted either: "Bank value" WEST and the Refresh link EAST, with the row's
			// 6 px gap between them and the link's own 6 px of hit padding (N 4.2). Every word that link can say
			// has to fit beside the caption or the card grows the moment one is shown - "Refreshing..." (B043)
			// and now "Up to date" (P2), which stands for a whole minute after every refresh.
			final int caption = width(BankPriceMovementPanel.VALUE_TITLE, Widgets.sans(11));
			for (String word : new String[]{BankPriceMovementPanel.REFRESH_TEXT, BankPriceMovementPanel.REFRESHING_TEXT,
				BankPriceMovementPanel.UP_TO_DATE_TEXT})
			{
				final int row = caption + 2 * BankPriceMovementPanel.ROW_GAP + width(word, Widgets.sans(11));
				assertTrue("the caption row saying \"" + word + "\" asks for " + row + " px of " + inner, row <= inner);
			}

			// S3: the update line is a CONSTANT sentence in the footnote's 12 px face, so it is never fitted - it
			// has to fit outright, or the card's last line reads "Item prices update every 24h..." (a JLabel too
			// narrow for its text ellipsises it itself, so the failure is a shortened SENTENCE and never a broken
			// row - which is why this line may go unfitted where a figure may not). It is measured at 11 px as
			// well, the size addendum S line S1 names, so the rule holds at either of the card's small faces; the
			// 12 px face leaves about a tenth of the card spare for a wider physical Dialog than this machine's.
			assertFits(BankPriceMovementPanel.UPDATE_TEXT, Widgets.sans(12), inner);
			assertFits(BankPriceMovementPanel.UPDATE_TEXT, Widgets.sans(11), inner);
			// T5: the same line has a second sentence since addendum T, and it is the LONGER of the two. The rule is
			// the rule - the line is still never fitted, so "Live prices on - thin items dail..." is the failure this
			// measurement exists to catch, and it would be the card's own answer to "why did my figures just move?"
			// that came out cut.
			assertFits(BankPriceMovementPanel.UPDATE_LIVE_TEXT, Widgets.sans(12), inner);
			assertFits(BankPriceMovementPanel.UPDATE_LIVE_TEXT, Widgets.sans(11), inner);
			System.out.println("the update line, live: " + width(BankPriceMovementPanel.UPDATE_LIVE_TEXT, Widgets.sans(12))
				+ " px of " + inner + " (guide: " + width(BankPriceMovementPanel.UPDATE_TEXT, Widgets.sans(12)) + ")");

			// Sentences go through the fitter with the whole text on the tooltip (N 4.2, 3.6).
			assertFitted("180d vs 31 Dec - logged out", Widgets.sans(12), inner);
			assertFitted("Guide prices - bank 23:59", Widgets.sans(12), inner);
			assertFitted("Wiki history down - no movement", Widgets.sans(12), Widgets.CONTENT_WIDTH);

			// ...but the problem row is the one sentence that must NOT be cut: it is the only line whose whole
			// job is to say why every percentage on screen has gone to a dash, and it is drawn in the plugin's
			// only red. Its box is the row's 213 px less the 6 px insets renderProblem fits against, and the
			// constant is named rather than quoted so a reworded sentence is measured here rather than on
			// screen (checker, B041: "Wiki history unavailable - no movement" was 213 px in that 201 px box,
			// so it always printed as "Wiki history unavailable - no move...").
			assertFits(PriceService.PROBLEM_HISTORY_UNAVAILABLE, Widgets.sans(12), Widgets.CONTENT_WIDTH - 2 * 6);
			assertFits(PriceService.problemCooldown(12L), Widgets.sans(12), Widgets.CONTENT_WIDTH - 2 * 6);
			assertFits(PriceService.problemHistoryPending(MovementWindow.D180), Widgets.sans(12), Widgets.CONTENT_WIDTH - 2 * 6);
		});
	}

	/**
	 * Addendum W's width rule: each of the four column labels, with the direction arrow beside it, fits the sort
	 * button's half of the control row - the row's 213 px less its 6 px insets and the 6 px gap between the two
	 * word-buttons, less the widest band button that can stand beside it ("100k - 200k" and its own marker).
	 *
	 * <p>The sort button is the one word-button that is never FITTED: {@code renderControl} sets its text and the
	 * band button is then fitted to what is left, so a column label that overruns does not ellipsise itself - it
	 * eats the band button's room and the band reads "100k -..." instead. That is why this is measured on all
	 * four labels rather than on the longest one guessed by eye ("Percent change" is the longest today; a fifth
	 * column added later is measured the moment it exists).
	 */
	@Test
	public void everySortColumnAndItsArrowFitBesideTheWidestBand() throws Exception
	{
		onEdt(() ->
		{
			// Both word-buttons carry the same two non-text pieces: the 7 px triangle and the 5 px gap before it.
			final int marker = BankPriceMovementPanel.TRIANGLE_ICON + BankPriceMovementPanel.ICON_GAP;
			final int band = width("100k - 200k", Widgets.sans(12)) + marker;
			final int budget = BankPriceMovementPanel.W - 2 * BankPriceMovementPanel.ROW_GAP
				- BankPriceMovementPanel.ROW_GAP - band;
			assertEquals("the four columns of addendum W", 4, SortMode.values().length);
			for (SortMode mode : SortMode.values())
			{
				final int asked = width(mode.label(), Widgets.sansBold(12)) + marker;
				System.out.println("the sort button saying \"" + mode.label() + "\": " + asked + " px of " + budget);
				assertTrue("\"" + mode.label() + "\" with its arrow asks for " + asked + " px of the " + budget
					+ " px the widest band leaves it", asked <= budget);
			}
		});
	}

	/**
	 * B040: {@link Widgets#fitName} is {@link Widgets#fit} with one exception - a trailing dose bracket survives
	 * the cut, because the four doses of a potion differ only in those three characters and the plain cut throws
	 * exactly them away. The rule is narrow on purpose: a dose has NO space before its bracket, a qualifier does.
	 */
	@Test
	public void fitNameKeepsADoseBracketAndCutsEverythingElseLikeFit() throws Exception
	{
		onEdt(() ->
		{
			final Font face = Widgets.sansBold(14);
			final int block = 156;

			// The four doses: each cut, each still saying which dose it is, each different from the others.
			final Set<String> shown = new HashSet<>();
			for (int dose = 1; dose <= 4; dose++)
			{
				final String name = "Super combat potion(" + dose + ")";
				final String cut = Widgets.fitName(face, name, block);
				assertNotEquals("the fixture has to be too wide, or this proves nothing", name, cut);
				assertTrue(cut, cut.endsWith("(" + dose + ")"));
				assertFits(cut, face, block);
				shown.add(cut);
			}
			assertEquals("four doses, four headlines", 4, shown.size());

			// Everything else is exactly fit(): a name that fits, a qualifier, a null and an empty text.
			assertEquals("Abyssal whip", Widgets.fitName(face, "Abyssal whip", block));
			assertEquals(Widgets.fit(face, "Karambwan vessel (baited)", block),
				Widgets.fitName(face, "Karambwan vessel (baited)", block));
			assertEquals(Widgets.fit(face, "Ancient ceremonial legs", block),
				Widgets.fitName(face, "Ancient ceremonial legs", block));
			assertEquals("", Widgets.fitName(face, null, block));
			assertEquals("", Widgets.fitName(face, "", block));
			assertEquals("(4)", Widgets.fitName(face, "(4)", block));

			// setFittedName is setFitted through it: the label is cut, the tooltip is whole.
			final JLabel label = Widgets.label("", face, Color.WHITE);
			Widgets.setFittedName(label, "Super combat potion(4)", block);
			assertTrue(label.getText(), label.getText().endsWith("(4)"));
			assertEquals("Super combat potion(4)", label.getToolTipText());
			Widgets.setFittedName(label, "", block);
			assertNull("an empty name carries no tooltip", label.getToolTipText());
		});
	}

	/**
	 * B045: the one colour in this sidebar that is not a {@code ColorScheme} constant, and the reason it is
	 * allowed - addendum N section 3 §2 pre-authorised (240, 92, 84) by name for red TEXT, on the condition the
	 * 12 px red read dark in the first render. It clears 4.5:1 on the card grey where the constant does not.
	 */
	@Test
	public void theLiftedRedIsTheDeclaredOneAndClearsTheSmallTextThreshold()
	{
		assertEquals(new Color(240, 92, 84), Widgets.MOVE_DOWN_TEXT);
		final double lifted = contrast(Widgets.MOVE_DOWN_TEXT, ColorScheme.DARKER_GRAY_COLOR);
		final double constant = contrast(ColorScheme.PROGRESS_ERROR_COLOR, ColorScheme.DARKER_GRAY_COLOR);
		assertTrue("the constant is what the finding measured: " + constant, constant < 4.5d);
		assertTrue("the lift has to clear the small-text bar: " + lifted, lifted >= 4.5d);
		assertTrue("...and it must not be brighter than the green it sits beside",
			lifted <= contrast(ColorScheme.PROGRESS_COMPLETE_COLOR, ColorScheme.DARKER_GRAY_COLOR));
	}

	/**
	 * The one move rule the whole sidebar draws (N sections 2 and 3.1, B045): green up, red down, the quiet grey
	 * flat - as a FIGURE with the lifted red, as a MARK with the {@code ColorScheme} constants, as an EDGE
	 * darkened and with the card's own grey where there is no move to point at. Six methods across the panel and
	 * the row read it, so the rule itself is pinned here, once.
	 */
	@Test
	public void theMoveRuleIsGreenUpRedDownAndQuietFlatInEachOfItsThreeRoles()
	{
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.MARK));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, Widgets.move(-1, Widgets.Kind.MARK));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, Widgets.move(0, Widgets.Kind.MARK));

		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.move(1, Widgets.Kind.FIGURE));
		assertEquals("a falling FIGURE takes the lifted red", Widgets.MOVE_DOWN_TEXT,
			Widgets.move(-1, Widgets.Kind.FIGURE));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, Widgets.move(0, Widgets.Kind.FIGURE));

		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), Widgets.move(1, Widgets.Kind.EDGE));
		assertEquals("an edge is darkened, never lifted", ColorScheme.PROGRESS_ERROR_COLOR.darker(),
			Widgets.move(-1, Widgets.Kind.EDGE));
		assertEquals("an edge with nothing to say is the card's own grey", ColorScheme.DARKER_GRAY_COLOR,
			Widgets.move(0, Widgets.Kind.EDGE));

		// The callers hand it Long.signum, but any magnitude reads as its sign.
		assertEquals(Widgets.move(1, Widgets.Kind.MARK), Widgets.move(42, Widgets.Kind.MARK));
		assertEquals(Widgets.move(-1, Widgets.Kind.EDGE), Widgets.move(-42, Widgets.Kind.EDGE));

		// The text half on its own: the falling constant is lifted, and nothing else is - not the green, not the
		// grey, and not the darkened red a rail is painted in.
		assertEquals(Widgets.MOVE_DOWN_TEXT, Widgets.liftRed(ColorScheme.PROGRESS_ERROR_COLOR));
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, Widgets.liftRed(ColorScheme.PROGRESS_COMPLETE_COLOR));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, Widgets.liftRed(ColorScheme.LIGHT_GRAY_COLOR));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(),
			Widgets.liftRed(ColorScheme.PROGRESS_ERROR_COLOR.darker()));
	}

	/** WCAG relative-luminance contrast of two opaque colours, the ratio the addendum's figures are quoted in. */
	private static double contrast(Color a, Color b)
	{
		final double la = luminance(a);
		final double lb = luminance(b);
		return (Math.max(la, lb) + 0.05d) / (Math.min(la, lb) + 0.05d);
	}

	private static double luminance(Color c)
	{
		return 0.2126d * channel(c.getRed()) + 0.7152d * channel(c.getGreen()) + 0.0722d * channel(c.getBlue());
	}

	private static double channel(int value)
	{
		final double v = value / 255.0d;
		return v <= 0.03928d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d);
	}

	// ---------------------------------------------------------------- links (N 3.3, 3.5, section 7)

	@Test
	public void aLinkSwapsColoursOnHoverAndFiresOnAPress() throws Exception
	{
		onEdt(() ->
		{
			final AtomicInteger clicks = new AtomicInteger();
			final JLabel link = link("Show 279 more", clicks::incrementAndGet);
			assertEquals("Show 279 more", link.getText());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, link.getForeground());

			enter(link);
			assertEquals(ColorScheme.BRAND_ORANGE, link.getForeground());
			exit(link);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, link.getForeground());

			press(link);
			assertEquals(1, clicks.get());

			// A link with nothing to do must not throw on a press - some of them are only a light.
			press(link("312 of 529", null));
		});
	}

	/**
	 * N 3.3: the band target goes orange the moment a band is set, and the next mouse-exit must paint the
	 * NEW base colour rather than the grey it was built with.
	 */
	@Test
	public void reTintingALinkSurvivesTheNextHover() throws Exception
	{
		onEdt(() ->
		{
			final JLabel target = link("529 items", null);
			Widgets.setLinkColors(target, ColorScheme.BRAND_ORANGE, Color.WHITE);
			assertEquals(ColorScheme.BRAND_ORANGE, target.getForeground());

			enter(target);
			assertEquals(Color.WHITE, target.getForeground());
			exit(target);
			assertEquals(ColorScheme.BRAND_ORANGE, target.getForeground());
		});
	}

	@Test
	public void linkLabelTakesItsOwnFontAndColours() throws Exception
	{
		onEdt(() ->
		{
			final Font font = Widgets.sansBold(12);
			final JLabel sort = Widgets.linkLabel("Percent change", font, ColorScheme.TEXT_COLOR,
				ColorScheme.BRAND_ORANGE, null);
			assertEquals(font, sort.getFont());
			assertEquals(ColorScheme.TEXT_COLOR, sort.getForeground());
			enter(sort);
			assertEquals(ColorScheme.BRAND_ORANGE, sort.getForeground());
			exit(sort);
			assertEquals(ColorScheme.TEXT_COLOR, sort.getForeground());
		});
	}

	// ---------------------------------------------------------------- the card border (N 3.1)

	@Test
	public void theCardBorderIsAThreePixelEdgeOverThePadding() throws Exception
	{
		onEdt(() ->
		{
			final JPanel card = new JPanel();
			final Border border = Widgets.card(ColorScheme.PROGRESS_ERROR_COLOR.darker());
			card.setBorder(border);

			assertTrue(border instanceof CompoundBorder);
			final Border outer = ((CompoundBorder) border).getOutsideBorder();
			assertTrue(outer instanceof MatteBorder);
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), ((MatteBorder) outer).getMatteColor());

			final Insets in = border.getBorderInsets(card);
			assertEquals("only the left edge is coloured", Widgets.EDGE_WIDTH + 9, in.left);
			assertEquals(10, in.right);
			assertEquals(8, in.top);
			assertEquals(8, in.bottom);

			// 3 + 9 + 10 of border leaves the 191 px the hero's labels are fitted to.
			assertEquals(191, Widgets.CONTENT_WIDTH - in.left - in.right);

			final Insets tight = Widgets.card(null, new Insets(2, 3, 4, 5)).getBorderInsets(card);
			assertEquals(Widgets.EDGE_WIDTH + 3, tight.left);
			assertEquals(5, tight.right);
			assertEquals(2, tight.top);
			assertEquals(4, tight.bottom);
			assertEquals("a null edge is invisible but still 3 px wide", ColorScheme.DARKER_GRAY_COLOR,
				((MatteBorder) ((CompoundBorder) Widgets.card(null)).getOutsideBorder()).getMatteColor());
		});
	}

	// ---------------------------------------------------------------- the drawn glyphs (N section 7)

	/** Every glyph paints headless, has the size the addendum gives it, and actually puts ink down. */
	@Test
	public void everyGlyphPaintsWithoutADisplayAndHasInk()
	{
		assertGlyph(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR), 12, 12);
		assertGlyph(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.BRAND_ORANGE), 12, 12);
		assertGlyph(Widgets.triangle(true), 7, 7);
		assertGlyph(Widgets.triangle(false), 7, 7);
		assertGlyph(Widgets.triangleDown(ColorScheme.TEXT_COLOR), 7, 7);
		assertGlyph(Widgets.triangleUp(ColorScheme.TEXT_COLOR), 7, 7);
		assertGlyph(Widgets.triangle(true, 11, ColorScheme.BRAND_ORANGE), 11, 11);
		assertGlyph(Widgets.clearIcon(ColorScheme.LIGHT_GRAY_COLOR), 11, 11);
		assertGlyph(Widgets.clearIcon(ColorScheme.BRAND_ORANGE), 11, 11);
		assertGlyph(Widgets.dotIcon(true), 7, 7);
		assertGlyph(Widgets.dotIcon(false), 7, 7);
	}

	/**
	 * The shipped sort chip's triangle must not have changed when the size/colour overload was added under
	 * it: same size, same colour, same pixels.
	 */
	@Test
	public void theOneArgumentTriangleIsTheSevenPixelOrangeOneItAlwaysWas()
	{
		assertSamePixels(Widgets.triangle(true), Widgets.triangle(true, 7, ColorScheme.BRAND_ORANGE));
		assertSamePixels(Widgets.triangle(false), Widgets.triangle(false, 7, ColorScheme.BRAND_ORANGE));
		assertSamePixels(Widgets.triangle(true), Widgets.triangleDown(ColorScheme.BRAND_ORANGE));
		assertSamePixels(Widgets.triangle(false), Widgets.triangleUp(ColorScheme.BRAND_ORANGE));
	}

	/** A glyph is drawn in the colour it was handed - grey when quiet, brand orange when it is a control. */
	@Test
	public void aGlyphIsPaintedInTheColourItIsGiven()
	{
		assertHasColour(Widgets.clearIcon(ColorScheme.BRAND_ORANGE), ColorScheme.BRAND_ORANGE);
		assertHasColour(Widgets.clearIcon(ColorScheme.LIGHT_GRAY_COLOR), ColorScheme.LIGHT_GRAY_COLOR);
		assertHasColour(Widgets.triangleDown(ColorScheme.TEXT_COLOR), ColorScheme.TEXT_COLOR);
		assertHasColour(Widgets.triangleUp(ColorScheme.PROGRESS_COMPLETE_COLOR), ColorScheme.PROGRESS_COMPLETE_COLOR);
		assertHasColour(Widgets.dotIcon(true), ColorScheme.BRAND_ORANGE);
		assertHasColour(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR), ColorScheme.LIGHT_GRAY_COLOR);
		assertHasColour(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.BRAND_ORANGE), ColorScheme.BRAND_ORANGE);
	}

	// ---------------------------------------------------------------- the options gear (addendum Q, line Q1)

	/**
	 * Q1: the gear is a RING with eight teeth and a HOLE - the three things that make the glyph read as a gear
	 * and not as a disc or a star. The teeth are counted by walking a circle outside the ring and counting the
	 * runs of ink, at a size where the geometry is unambiguous; the hole is the centre pixel, which must stay
	 * transparent at every size the icon is drawn at.
	 */
	@Test
	public void theGearIsARingWithEightTeethAndAHoleThroughTheMiddle()
	{
		final BufferedImage big = image(Widgets.gearIcon(48, ColorScheme.LIGHT_GRAY_COLOR));
		assertEquals("the teeth, counted round the rim", Widgets.GEAR_TEETH, runsOfInk(big, 20.5));
		assertEquals(8, Widgets.GEAR_TEETH);

		for (int size : new int[]{Widgets.GEAR_MIN, Widgets.GEAR_SIZE, 16, 48})
		{
			final BufferedImage img = image(Widgets.gearIcon(size, ColorScheme.LIGHT_GRAY_COLOR));
			assertEquals(size + " px", size, img.getWidth());
			assertEquals(size + " px", size, img.getHeight());
			assertEquals("the hole at " + size + " px", 0, alpha(img, size / 2, size / 2));
			assertTrue("the ring at " + size + " px", ink(img) > 0);
		}
	}

	/** The gear is drawn at the size the panel asks for, and a size too small to hold a hole is raised to one. */
	@Test
	public void aGearTooSmallToHoldItsHoleIsClampedNotDrawnShut()
	{
		assertEquals(12, Widgets.GEAR_SIZE);
		assertEquals(Widgets.GEAR_MIN, Widgets.gearIcon(0, ColorScheme.LIGHT_GRAY_COLOR).getIconWidth());
		assertEquals(Widgets.GEAR_MIN, Widgets.gearIcon(-5, ColorScheme.LIGHT_GRAY_COLOR).getIconHeight());
		assertTrue("bigger gear, more ink", ink(image(Widgets.gearIcon(24, ColorScheme.LIGHT_GRAY_COLOR)))
			> ink(image(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR))));
		// A colour is not required of the caller: the sidebar's quiet grey is what a control rests in.
		assertHasColour(Widgets.gearIcon(Widgets.GEAR_SIZE, null), ColorScheme.LIGHT_GRAY_COLOR);
	}

	/** N 3.4: the current ordering gets a filled dot, the other five a lighter ring - visibly different. */
	@Test
	public void theFilledDotCarriesMoreInkThanTheRing()
	{
		assertTrue("a filled dot must read louder than a ring",
			ink(image(Widgets.dotIcon(true))) > ink(image(Widgets.dotIcon(false))));
	}

	/**
	 * Nothing under 3 px can hold the triangle's 1 px inset, so the size is raised rather than inverted - a
	 * negative here would otherwise ask {@code BufferedImage} for an image of no width and throw.
	 */
	@Test
	public void aRidiculousTriangleSizeIsClampedNotInverted()
	{
		assertEquals(3, Widgets.triangle(true, 0, ColorScheme.BRAND_ORANGE).getIconWidth());
		assertEquals(3, Widgets.triangle(false, -5, ColorScheme.BRAND_ORANGE).getIconHeight());
	}

	// ---------------------------------------------------------------- the older members are untouched

	@Test
	public void thePreExistingWidgetsStillBehave() throws Exception
	{
		onEdt(() ->
		{
			assertEquals(213, Widgets.CONTENT_WIDTH);
			assertEquals("...", Widgets.ELLIPSIS);

			final Font face = Widgets.sansBold(14);
			assertEquals("Abyssal whip", Widgets.fit(face, "Abyssal whip", 200));
			assertTrue(Widgets.fit(face, "Karambwan vessel (baited)", 60).endsWith(Widgets.ELLIPSIS));

			final JLabel name = Widgets.label("", face, Color.WHITE);
			Widgets.setFitted(name, "Karambwan vessel (baited)", 60);
			assertEquals("the label is cut to its room", Widgets.fit(face, "Karambwan vessel (baited)", 60),
				name.getText());
			assertEquals("the whole name stays on the tooltip", "Karambwan vessel (baited)",
				name.getToolTipText());

			assertEquals("&amp;&lt;&gt;", Widgets.escapeHtml("&<>"));
			assertEquals(90, Widgets.fixed(new JLabel(), 90, 24).getPreferredSize().width);
			assertNotNull(Widgets.gpField("min gp").placeholder());

			// The one JButton the Ticker sidebar still makes: the EMPTY card's way out of a band that hid
			// everything. Small face, tight margins, no focus ring, and it does what it was given.
			final AtomicInteger clicks = new AtomicInteger();
			final JButton clear = Widgets.smallButton("Clear price range", "a tooltip", e -> clicks.incrementAndGet());
			assertEquals("Clear price range", clear.getText());
			assertEquals("a tooltip", clear.getToolTipText());
			assertFalse(clear.isFocusPainted());
			assertEquals(new Insets(1, 5, 1, 5), clear.getMargin());
			clear.doClick(0);
			assertEquals(1, clicks.get());
		});
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * A text link at the control size in the sidebar's quiet grey, brand orange under the mouse - the shape
	 * every one of the panel's links is built in ({@code Widgets.linkLabel} at 12 px). It was a
	 * {@code Widgets.link} helper until nothing in main code called it; the RULES it pins - the hover swap, the
	 * press, the re-tint - are the shipped {@code linkLabel}'s and are still pinned here.
	 */
	private static JLabel link(String text, @Nullable Runnable onClick)
	{
		return Widgets.linkLabel(text, Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR, ColorScheme.BRAND_ORANGE,
			onClick);
	}

	private static void assertFits(String text, Font font, int width)
	{
		final int measured = width(text, font);
		assertTrue("\"" + text + "\" measures " + measured + " px, over " + width, measured <= width);
	}

	/** What {@code text} measures in {@code font}, with the same metrics a label paints with. */
	private static int width(String text, Font font)
	{
		return new JLabel().getFontMetrics(font).stringWidth(text);
	}

	/** The fitter brings a sentence inside its room, whether by fitting it whole or by cutting it. */
	private static void assertFitted(String text, Font font, int width)
	{
		final String shown = Widgets.fit(font, text, width);
		assertFits(shown, font, width);
		assertTrue("\"" + text + "\" came back longer than it went in", shown.length() <= text.length()
			+ Widgets.ELLIPSIS.length());
	}

	private static void assertGlyph(ImageIcon icon, int width, int height)
	{
		assertNotNull(icon);
		assertEquals(width, icon.getIconWidth());
		assertEquals(height, icon.getIconHeight());
		assertTrue("the glyph put no ink down", ink(image(icon)) > 0);
	}

	private static void assertHasColour(ImageIcon icon, Color colour)
	{
		final BufferedImage img = image(icon);
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				final int argb = img.getRGB(x, y);
				if (((argb >>> 24) & 0xff) == 0xff && (argb & 0xffffff) == (colour.getRGB() & 0xffffff))
				{
					return;
				}
			}
		}
		throw new AssertionError("no fully opaque pixel of " + colour + " in the glyph");
	}

	private static void assertSamePixels(ImageIcon left, ImageIcon right)
	{
		final BufferedImage a = image(left);
		final BufferedImage b = image(right);
		assertEquals(a.getWidth(), b.getWidth());
		assertEquals(a.getHeight(), b.getHeight());
		for (int x = 0; x < a.getWidth(); x++)
		{
			for (int y = 0; y < a.getHeight(); y++)
			{
				assertEquals("pixel " + x + "," + y, a.getRGB(x, y), b.getRGB(x, y));
			}
		}
	}

	private static BufferedImage image(ImageIcon icon)
	{
		assertTrue("the glyphs are drawn into a BufferedImage at build time",
			icon.getImage() instanceof BufferedImage);
		return (BufferedImage) icon.getImage();
	}

	/**
	 * How many separate runs of ink a circle of radius {@code r} about the glyph's centre crosses - the teeth of
	 * a gear, counted rather than assumed. Sampled every degree and read as a ring, so a run that straddles 0
	 * degrees counts once.
	 */
	private static int runsOfInk(BufferedImage img, double r)
	{
		final double centre = img.getWidth() / 2.0;
		final boolean[] inked = new boolean[360];
		for (int deg = 0; deg < 360; deg++)
		{
			final double a = Math.toRadians(deg);
			final int x = (int) Math.round(centre + Math.cos(a) * r);
			final int y = (int) Math.round(centre + Math.sin(a) * r);
			inked[deg] = x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight() && alpha(img, x, y) > 0;
		}
		int runs = 0;
		for (int deg = 0; deg < 360; deg++)
		{
			if (inked[deg] && !inked[(deg + 359) % 360])
			{
				runs++;
			}
		}
		return runs;
	}

	/** The alpha of one pixel, 0 for fully transparent. */
	private static int alpha(BufferedImage img, int x, int y)
	{
		return (img.getRGB(x, y) >>> 24) & 0xff;
	}

	/** How many pixels of the glyph are not fully transparent. */
	private static int ink(BufferedImage img)
	{
		int count = 0;
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				if (((img.getRGB(x, y) >>> 24) & 0xff) > 0)
				{
					count++;
				}
			}
		}
		return count;
	}

	/** The LEFT button going down on {@code target} - what a click starts with. */
	private static void press(Component target)
	{
		deliver(target, new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
	}

	/** The RIGHT button going down: the start of the hero card's menu gesture (N3 b), never a press. */
	private static void rightPress(Component target)
	{
		deliver(target, new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, false, MouseEvent.BUTTON3));
	}

	/** The platform's popup trigger on the left button (macOS ctrl-click): a menu gesture, never a press. */
	private static void popupPress(Component target)
	{
		deliver(target, new MouseEvent(target, MouseEvent.MOUSE_PRESSED, 0L, 0, 1, 1, 1, true, MouseEvent.BUTTON1));
	}

	private static void deliver(Component target, MouseEvent e)
	{
		for (MouseListener l : target.getMouseListeners())
		{
			l.mousePressed(e);
		}
	}

	private static void enter(Component target)
	{
		for (MouseListener l : target.getMouseListeners())
		{
			l.mouseEntered(new MouseEvent(target, MouseEvent.MOUSE_ENTERED, 0L, 0, 1, 1, 0, false));
		}
	}

	private static void exit(Component target)
	{
		for (MouseListener l : target.getMouseListeners())
		{
			l.mouseExited(new MouseEvent(target, MouseEvent.MOUSE_EXITED, 0L, 0, 1, 1, 0, false));
		}
	}

	/** Runs the body on the EDT (where every widget here lives) and re-throws whatever it failed with. */
	private static void onEdt(Runnable body) throws Exception
	{
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				body.run();
			}
			catch (Throwable t)
			{
				failure.set(t);
			}
		});

		final Throwable t = failure.get();
		if (t instanceof Error)
		{
			throw (Error) t;
		}
		if (t instanceof Exception)
		{
			throw (Exception) t;
		}
		if (t != null)
		{
			throw new RuntimeException(t);
		}
	}
}
