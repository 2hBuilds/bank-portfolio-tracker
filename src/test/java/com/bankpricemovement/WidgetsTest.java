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
import java.awt.Graphics2D;
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
import javax.swing.JComponent;
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

			// S3: the update line is a CONSTANT sentence in the footnote's face, so it is never fitted - it
			// has to fit outright, or the card's last line reads "Guide prices update every f..." (a JLabel too
			// narrow for its text ellipsises it itself, so the failure is a shortened SENTENCE and never a broken
			// row - which is why this line may go unfitted where a figure may not). Since 1.1.2 T4 the line is drawn at
			// 11 px (UPDATE_SIZE, the size addendum S line S1 names): the user's two wordings measure 199 and 203 px at
			// 12, over the card's 191, and 182 and 181 at 11, so the rule is held at the size the line is drawn at.
			// 1.1.2 T4: the user's two lines, word for word - a constant compared with itself pins nothing.
			assertEquals("Guide prices update every few hours", BankPriceMovementPanel.UPDATE_TEXT);
			assertEquals("Live prices on - others from the guide", BankPriceMovementPanel.UPDATE_LIVE_TEXT);
			assertEquals(11, BankPriceMovementPanel.UPDATE_SIZE);
			assertFits(BankPriceMovementPanel.UPDATE_TEXT, Widgets.sans(BankPriceMovementPanel.UPDATE_SIZE), inner);
			// T5: the same line has a second sentence since addendum T, and it is the LONGER of the two. The rule is
			// the rule - the line is still never fitted, so "Live prices on - others from the g..." is the failure this
			// measurement exists to catch, and it would be the card's own answer to "why did my figures just move?"
			// that came out cut.
			assertFits(BankPriceMovementPanel.UPDATE_LIVE_TEXT, Widgets.sans(BankPriceMovementPanel.UPDATE_SIZE), inner);
			System.out.println("the update line, live: "
				+ width(BankPriceMovementPanel.UPDATE_LIVE_TEXT, Widgets.sans(BankPriceMovementPanel.UPDATE_SIZE))
				+ " px of " + inner + " (guide: "
				+ width(BankPriceMovementPanel.UPDATE_TEXT, Widgets.sans(BankPriceMovementPanel.UPDATE_SIZE)) + ")");

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
	 * What {@link Widgets#setFitted} leaves on a label, pinned in one place because the sidebar depends on it: the panel
	 * lets whatever tooltip the fitter hung on its total, footnote, band target and problem line stand as that label's
	 * hover (addendum AH3 once registered each one under the "Show hover text" switch, which release 1.2.0 deleted - the
	 * hovers are always on now, and the fitter's tooltip is the hover).
	 *
	 * <p>The rule is UNCONDITIONAL, and that is the half nothing pinned before: the whole text goes on the
	 * tooltip whether the label had to be CUT or fitted whole, and only an empty (or null) text leaves a label
	 * with none - which also CLEARS one already there. So a label a reader can read in full still carries a
	 * hover, which is why the panel need not tell the labels it expects to be cut from the others,
	 * and why {@code MovementRowPanel} clears the tooltip on its price label even though the fit order keeps a
	 * price whole.
	 */
	@Test
	public void everyFittedLabelCarriesItsWholeTextAsATooltipCutOrNot() throws Exception
	{
		onEdt(() ->
		{
			final Font face = Widgets.sansBold(14);

			// Cut: the label shows an ellipsis and the tooltip is the only place the whole text survives.
			final JLabel cut = Widgets.label("", face, Color.WHITE);
			Widgets.setFitted(cut, "Karambwan vessel (baited)", 60);
			assertNotEquals("the fixture has to be too wide, or this proves nothing",
				"Karambwan vessel (baited)", cut.getText());
			assertEquals("Karambwan vessel (baited)", cut.getToolTipText());

			// Whole: room to spare, nothing lost - and the label is handed the tooltip anyway.
			final JLabel whole = Widgets.label("", face, Color.WHITE);
			Widgets.setFitted(whole, "1.52m", 200);
			assertEquals("the label had room and was not cut", "1.52m", whole.getText());
			assertEquals("...and it still arrives carrying a hover", "1.52m", whole.getToolTipText());

			// setFittedName is the same body through the dose-keeping fitter, so it leaves the same thing.
			final JLabel name = Widgets.label("", face, Color.WHITE);
			Widgets.setFittedName(name, "Abyssal whip", 200);
			assertEquals("Abyssal whip", name.getText());
			assertEquals("Abyssal whip", name.getToolTipText());

			// An empty text, and a null one, are the only ways out - and both clear what was there.
			Widgets.setFitted(whole, "", 200);
			assertEquals("", whole.getText());
			assertNull("an empty text carries no tooltip", whole.getToolTipText());
			Widgets.setFitted(cut, null, 60);
			assertEquals("", cut.getText());
			assertNull("and a null one clears the tooltip it replaces", cut.getToolTipText());
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
	 *
	 * <p>Addendum AL's fourth role, {@link Widgets.Kind#QUIET}, has its own two tests below: it is the only one
	 * whose colour is DERIVED from another role's rather than stated, so it is measured against the constants it
	 * is derived from instead of being listed here beside three flat answers.
	 */
	@Test
	public void theMoveRuleIsGreenUpRedDownAndQuietFlatAsAMarkAFigureAndAnEdge()
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

	// ---------------------------------------------------------------- the quiet gp figure (addendum AL)

	/**
	 * AL: the gp change and the percentage share a row's second line, and the user picked the variant that lets
	 * the percentage be read on its own by taking the gp figure DOWN rather than by moving either of them. So
	 * {@link Widgets.Kind#QUIET} is not a new colour - it is exactly what {@link Widgets.Kind#FIGURE} would have
	 * painted, mixed {@link Widgets#QUIET_MIX_PERCENT}% of the way into the row's own {@code DARKER_GRAY} ground.
	 *
	 * <p>The expectation is COMPUTED from those two colours and that percentage rather than copied out as a hex,
	 * so the day somebody tunes the mix by eye against the real list this test follows it instead of failing on a
	 * number nobody can trace back to a decision.
	 *
	 * <p>The faller is the half worth stating out loud. {@code FIGURE} does not hand back
	 * {@code PROGRESS_ERROR_COLOR}: it LIFTS it to {@link Widgets#MOVE_DOWN_TEXT} first, because the raw constant
	 * measures 3.63:1 on the card where a small figure needs 4.5 (B045). QUIET therefore has to dim the LIFTED
	 * red - dimming the raw one instead spends the lift twice over and leaves a row's fall darker than the very
	 * rule it is meant to be a quieter copy of, which is the one way this can be wrong and still look plausible
	 * in a screenshot.
	 */
	@Test
	public void theQuietFigureIsTheFigureColourMixedTowardTheRowsOwnGround()
	{
		// A mix of none or of all is not a mix, and "strictly between" below would be vacuous against either.
		// WHERE between the two it sits is deliberately not pinned - the constant's own javadoc measured that by
		// eye at 213 px, and this file has no eye.
		assertTrue("a QUIET that is not a mix says nothing: " + Widgets.QUIET_MIX_PERCENT,
			Widgets.QUIET_MIX_PERCENT > 0 && Widgets.QUIET_MIX_PERCENT < 100);

		final Color ground = ColorScheme.DARKER_GRAY_COLOR;
		for (int signum : new int[]{1, -1})
		{
			final Color figure = Widgets.move(signum, Widgets.Kind.FIGURE);
			final Color quiet = Widgets.move(signum, Widgets.Kind.QUIET);
			assertEquals("signum " + signum, mixed(figure, ground, Widgets.QUIET_MIX_PERCENT), quiet);

			// Strictly between, in both directions: quieter than the figure it copies, and still plainly there.
			assertNotEquals("signum " + signum + ": a QUIET that is the loud figure is not quiet", figure, quiet);
			assertNotEquals("signum " + signum + ": ...and one that is the ground is not a figure", ground, quiet);
			assertTrue("signum " + signum + ": the quiet figure must sit nearer the ground than the loud one",
				distance(quiet, ground) < distance(figure, ground));
			assertTrue("signum " + signum + ": ...which is the same thing said as contrast on that ground",
				contrast(quiet, ground) < contrast(figure, ground));
			System.out.println("the quiet figure at signum " + signum + ": " + quiet + " at "
				+ contrast(quiet, ground) + ":1, beside the loud " + figure + " at " + contrast(figure, ground));
		}

		// The faller is dimmed AFTER the lift and never instead of it (B045). The pair is self-checking: if the
		// implementation dimmed the raw constant, the first assertion fails; if the two mixes were somehow the
		// same colour - a fixture that would prove nothing - the second one does.
		assertEquals("QUIET dims the LIFTED red",
			mixed(Widgets.MOVE_DOWN_TEXT, ground, Widgets.QUIET_MIX_PERCENT),
			Widgets.move(-1, Widgets.Kind.QUIET));
		assertNotEquals("...and never the raw constant the lift exists to replace",
			mixed(ColorScheme.PROGRESS_ERROR_COLOR, ground, Widgets.QUIET_MIX_PERCENT),
			Widgets.move(-1, Widgets.Kind.QUIET));
	}

	/**
	 * A flat move has nothing to out-shout, so QUIET hands back the quiet grey UNDIMMED - the same colour
	 * {@link Widgets.Kind#FIGURE} gives it, which is why they are asserted equal rather than each against the
	 * constant.
	 *
	 * <p>This is the edge a mix applied before the sign is read gets wrong, and it gets it wrong invisibly:
	 * pushing {@code LIGHT_GRAY} into the ground leaves the row's dash at about 2:1 on a (30, 30, 30) row, a
	 * smudge where every other row carries a readable figure - and a dash is the one figure whose whole job is to
	 * say "nothing happened here" rather than leave the reader wondering what failed to draw.
	 */
	@Test
	public void aFlatMoveIsTheQuietGreyUndimmed()
	{
		assertEquals("a flat QUIET is the flat FIGURE, undimmed", Widgets.move(0, Widgets.Kind.FIGURE),
			Widgets.move(0, Widgets.Kind.QUIET));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, Widgets.move(0, Widgets.Kind.QUIET));
		assertNotEquals("a dash must not be mixed into the row it stands on",
			mixed(ColorScheme.LIGHT_GRAY_COLOR, ColorScheme.DARKER_GRAY_COLOR, Widgets.QUIET_MIX_PERCENT),
			Widgets.move(0, Widgets.Kind.QUIET));
		assertTrue("...so it still clears the small-text bar on the row",
			contrast(Widgets.move(0, Widgets.Kind.QUIET), ColorScheme.DARKER_GRAY_COLOR) >= 4.5d);
	}

	/**
	 * Every role answers for every signum, and answers to the SIGN rather than the magnitude - the callers hand
	 * {@code Long.signum} in, but a role that read a raw gp figure would paint a 1 gp fall and a 4.6m one alike
	 * only by accident.
	 *
	 * <p>The loop is over {@code values()} and the count is pinned beside it, so a fifth role cannot be added
	 * without this file noticing: a new constant that nobody writes a branch for falls into {@link Widgets#move}'s
	 * {@code default} and silently paints the undimmed {@code ColorScheme} pair, which is a plausible-looking
	 * answer and the reason the count is worth failing on.
	 */
	@Test
	public void everyRoleAnswersForEverySignumAndReadsItAsASign()
	{
		assertEquals("a new Kind needs its own rule pinned in this file", 4, Widgets.Kind.values().length);
		for (Widgets.Kind kind : Widgets.Kind.values())
		{
			assertNotNull(kind + " has no flat colour", Widgets.move(0, kind));
			assertNotNull(kind + " has no rising colour", Widgets.move(1, kind));
			assertNotNull(kind + " has no falling colour", Widgets.move(-1, kind));
			assertNotEquals(kind + " paints a rise and a fall the same way", Widgets.move(1, kind),
				Widgets.move(-1, kind));
			assertEquals(kind + " must read a magnitude as its sign", Widgets.move(1, kind),
				Widgets.move(4_618_000, kind));
			assertEquals(kind + " must read a magnitude as its sign", Widgets.move(-1, kind),
				Widgets.move(-4_618_000, kind));
		}
	}

	/**
	 * {@code from} mixed {@code percent}% of the way into {@code to} - the arithmetic {@link Widgets#move} does
	 * for {@link Widgets.Kind#QUIET}, written out here rather than reached for. The rounding is the same
	 * {@code Math.round} per channel, so this is an independent statement of the rule and not a second reference
	 * to the same code.
	 */
	private static Color mixed(Color from, Color to, int percent)
	{
		final double a = percent / 100.0;
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a));
	}

	/** How far apart two colours are in RGB: "nearer the background" measured rather than eyeballed. */
	private static double distance(Color a, Color b)
	{
		final double dr = a.getRed() - b.getRed();
		final double dg = a.getGreen() - b.getGreen();
		final double db = a.getBlue() - b.getBlue();
		return Math.sqrt(dr * dr + dg * dg + db * db);
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

	/** Every glyph paints headless, has the size the addendum gives it, and actually puts ink down. */
	@Test
	public void everyGlyphPaintsWithoutADisplayAndHasInk()
	{
		assertGlyph(Widgets.gearIcon(Widgets.GEAR_SIZE, ColorScheme.LIGHT_GRAY_COLOR), 12, 12);
		assertGlyph(Widgets.triangle(true), 7, 7);
		assertGlyph(Widgets.triangle(false), 7, 7);
	}

	// ---------------------------------------------------------------- the toggle: equal halves, smaller text (2026-09-29)

	/**
	 * The second live look (2026-09-29, the user choosing look b, "equal halves, smaller text": "I prefer whats in the
	 * image"): laid out at the sidebar's 213 px and at 230, in both states, the two halves are EQUAL (the second takes
	 * the odd pixel) and fill the box; both words are set in ONE size, pinned here per width; that size is the LARGEST
	 * whole size at which the bold "Net Worth History" keeps {@link Widgets#TOGGLE_AIR} px a side in its half - one size
	 * more and it would not; the lit half is bold and the unlit plain; each word has at least 2 px of air on each side
	 * of it; and Swing's own label layout prints each word whole.
	 */
	@Test
	public void theToggleHasEqualHalvesAndOneFittedSizeInEitherState() throws Exception
	{
		onEdt(() ->
		{
			assertEquals(2, Widgets.TOGGLE_AIR);
			assertEquals(12, Widgets.TOGGLE_TEXT_MAX);
			final int[][] widthAndSize = {{Widgets.CONTENT_WIDTH, 12}, {223, 12}, {230, 12}};
			for (int[] pair : widthAndSize)
			{
				final int width = pair[0];
				final Widgets.Toggle toggle = Widgets.toggle(SidebarView.ITEMS.toString(),
					SidebarView.HISTORY.toString(), null);
				assertEquals("Net Worth History", ((JLabel) toggle.getComponent(1)).getText());
				toggle.setSize(width, Widgets.TOGGLE_HEIGHT);
				for (int lit = 0; lit < 2; lit++)
				{
					toggle.setLit(lit);
					toggle.doLayout();
					final JLabel items = (JLabel) toggle.getComponent(0);
					final JLabel tracker = (JLabel) toggle.getComponent(1);
					final String at = "at " + width + ", lit " + lit;
					final int inner = width - 2;
					assertEquals(at + ": the box's border", 1, items.getX());
					assertEquals(at + ": side by side", items.getX() + items.getWidth(), tracker.getX());
					assertEquals(at + ": the two fill the box", width - 1, tracker.getX() + tracker.getWidth());
					assertEquals(at + ": equal halves", inner / 2, items.getWidth());
					assertEquals(at + ": the second takes the odd pixel", inner - inner / 2, tracker.getWidth());
					assertEquals(at + ": the height inside the frame", Widgets.TOGGLE_HEIGHT - 2, items.getHeight());

					final int size = items.getFont().getSize();
					assertEquals(at + ": the size", pair[1], size);
					assertEquals(at + ": one size for both words", size, tracker.getFont().getSize());
					assertEquals(at + ": the lit half is bold", lit == 0, items.getFont().isBold());
					assertEquals(at + ": the unlit half is plain", lit == 1, tracker.getFont().isBold());
					if (size < Widgets.TOGGLE_TEXT_MAX)
					{
						final int larger = tracker.getFontMetrics(Widgets.sansBold(size + 1))
							.stringWidth(tracker.getText());
						assertTrue(at + ": the size is the largest that fits - at " + (size + 1) + " the bold word is "
							+ larger + " px in " + tracker.getWidth(),
							larger + 2 * Widgets.TOGGLE_AIR > tracker.getWidth());
					}
					for (JLabel half : new JLabel[]{items, tracker})
					{
						final String word = half.getText();
						// The bold face whichever half is lit: the air holds in the wider of the word's two faces.
						final int bold = half.getFontMetrics(Widgets.sansBold(size)).stringWidth(word);
						final int text = half.getFontMetrics(half.getFont()).stringWidth(word);
						assertTrue(at + ": '" + word + "' is widest bold", bold >= text);
						for (int shown : new int[]{text, bold})
						{
							final int left = (half.getWidth() - shown) / 2;
							final int right = half.getWidth() - shown - left;
							final String what = at + ", '" + word + "' " + shown + " px in " + half.getWidth();
							assertTrue(what + ": air left " + left, left >= Widgets.TOGGLE_AIR);
							assertTrue(what + ": air right " + right, right >= Widgets.TOGGLE_AIR);
						}
						final java.awt.Rectangle icon = new java.awt.Rectangle();
						final java.awt.Rectangle textRect = new java.awt.Rectangle();
						final String laid = SwingUtilities.layoutCompoundLabel(half,
							half.getFontMetrics(half.getFont()), word, null, half.getVerticalAlignment(),
							half.getHorizontalAlignment(), half.getVerticalTextPosition(),
							half.getHorizontalTextPosition(),
							new java.awt.Rectangle(0, 0, half.getWidth(), half.getHeight()), icon, textRect,
							half.getIconTextGap());
						assertEquals(at + ", '" + word + "': never cut", word, laid);
						// The ink, painted: whole inside the half's rows, and (the long word, with its ascender and
						// its descender) centred as the 10 px word was (Swing centres the font box, so the ink sits 3 px high).
						final BufferedImage ink = new BufferedImage(half.getWidth(), half.getHeight(),
							BufferedImage.TYPE_INT_ARGB);
						final java.awt.Graphics2D g = ink.createGraphics();
						half.paint(g);
						g.dispose();
						int top = -1;
						int bottom = -1;
						for (int y = 0; y < ink.getHeight(); y++)
						{
							for (int x = 0; x < ink.getWidth(); x++)
							{
								if (ink.getRGB(x, y) != ink.getRGB(0, 0))
								{
									top = top < 0 ? y : top;
									bottom = y;
									break;
								}
							}
						}
						assertTrue(at + ", '" + word + "': some ink", top >= 0);
						assertTrue(at + ", '" + word + "': ink whole inside the strip, top " + top, top >= 1);
						assertTrue(at + ", '" + word + "': ink whole inside the strip, bottom " + bottom,
							bottom <= half.getHeight() - 2);
						if (half == tracker)
						{
							final int above = top;
							final int below = half.getHeight() - 1 - bottom;
							assertTrue(at + ": centred, " + above + " above and " + below + " below",
								Math.abs(above - below) <= 3);
						}
						assertTrue(at + ", '" + word + "': drawn inside its half, left",
							textRect.x >= Widgets.TOGGLE_AIR);
						assertTrue(at + ", '" + word + "': drawn inside its half, right",
							textRect.x + textRect.width <= half.getWidth() - Widgets.TOGGLE_AIR);
					}
				}
			}
		});
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

	// ---------------------------------------------------------------- 1.1.1 part X: the search box's clear x

	/**
	 * The x is two 1 px diagonals corner to corner of a 5 x 5 box - nine pixels, the centre shared - in the placeholder
	 * grey at rest and white under the mouse, with {@code AIR_LEFT} px of air to its left and {@code AIR_RIGHT} to its
	 * right, centred in the height it is given, on a transparent ground. Painted into an image, as the glyphs above are.
	 */
	@Test
	public void theClearXIsTwoOnePixelDiagonalsInTheGreyAndWhiteUnderTheMouse() throws Exception
	{
		onEdt(() ->
		{
			assertEquals("a 5 px x", 5, Widgets.ClearX.GLYPH);
			assertEquals("3 px of air, the x, 1 px of air", 9, Widgets.ClearX.WIDTH);
			final Widgets.PlaceholderField box = Widgets.searchField("Search items");
			final Widgets.ClearX x = box.clearX();
			box.setText("a");
			x.setSize(Widgets.ClearX.WIDTH, 18);
			final int top = (18 - Widgets.ClearX.GLYPH) / 2;

			assertClearX(paintOf(x), top, Widgets.PLACEHOLDER_COLOR);
			assertEquals("the ink is nine pixels", 9, ink(paintOf(x)));
			assertFalse("the cursor stays the default: no hand", x.isCursorSet());

			enter(x);
			assertClearX(paintOf(x), top, Color.WHITE);
			exit(x);
			assertClearX(paintOf(x), top, Widgets.PLACEHOLDER_COLOR);
		});
	}

	/** Every pixel of the x's 9 x {@code height} image is the ink where it should be and clear where it should not. */
	private static void assertClearX(BufferedImage img, int top, Color ink)
	{
		final int left = Widgets.ClearX.AIR_LEFT;
		final int side = Widgets.ClearX.GLYPH;
		for (int px = 0; px < img.getWidth(); px++)
		{
			for (int py = 0; py < img.getHeight(); py++)
			{
				final int i = px - left;
				final int j = py - top;
				final boolean on = i >= 0 && i < side && j >= 0 && j < side && (i == j || i == side - 1 - j);
				if (on)
				{
					assertEquals("ink at " + px + "," + py, ink.getRGB(), img.getRGB(px, py));
				}
				else
				{
					assertEquals("clear at " + px + "," + py, 0, alpha(img, px, py));
				}
			}
		}
	}

	/** {@code c} painted at its size into a transparent image, without being shown. */
	private static BufferedImage paintOf(JComponent c)
	{
		final BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			c.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	/**
	 * Only the search box has the x, an empty box has it hidden and a typed-in one shows it - through the text's own
	 * document, so a character typed, a text set and an emptying all keep it right; a press on it asks for the focus once.
	 */
	@Test
	public void onlyTheSearchFieldCarriesAClearXAndItFollowsTheText() throws Exception
	{
		onEdt(() ->
		{
			assertNull("a gp bound has no x", Widgets.gpField("min gp").clearX());
			final Widgets.PlaceholderField box = Widgets.searchField("Search items");
			final Widgets.ClearX x = box.clearX();
			assertNotNull(x);
			assertFalse("empty: hidden", x.isVisible());
			assertFalse("it never takes the focus by itself: Tab does not land on it", x.isFocusable());

			box.setText("rune");
			assertTrue(x.isVisible());
			box.setText("");
			assertFalse(x.isVisible());

			box.getTextField().setText("a");
			assertTrue("through the text field as well", x.isVisible());
			press(x);
			assertEquals("", box.getText());
			assertFalse(x.isVisible());
			assertEquals(1, x.focusRequests);
		});
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
