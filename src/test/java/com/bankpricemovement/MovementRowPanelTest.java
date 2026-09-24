package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Font;
import java.awt.Insets;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.api.Constants;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link MovementRowPanel}: the 213 x 62 card, its rail, its name line, the STACK line and the ITEM line under
 * it, the two gp columns, the guide-price tooltip and the two-entry right-click - built for real on the EDT and
 * measured off-screen (playbook 7.6, REAL SWING OFF-SCREEN).
 *
 * <p><b>What addendum N changed here, and what it deliberately did not.</b> The row grew from 40 px to 48,
 * lost its zebra ({@code setStripe} / {@code stripe()} are gone with it), gained a 3 px rail in the move's
 * colour for movers only, gave the name the whole 156 px text block, dropped the holding value and the "x1"
 * off the face, and split the old "+12.3k +0.8%" into a 12 px gp figure and a 14 px bold percentage. The
 * tooltip and the right-click menu are UNCHANGED - and the tooltip is now the only place the holding value
 * and the exact gp change exist, which is why its tests below are kept whole rather than trimmed.
 *
 * <p><b>Addendum O deleted the second design</b> (O1): the user picked Ticker, so there is no design
 * parameter and no "does this design print the gp figure" question - every row prints the pair. The row's
 * figures are the product and are NOT hideable; addendum O's three switches belong to the hero card
 * ({@link HeroVisibility}) and never reach a row.
 *
 * <p><b>Addendum Q's row switches, of which ONE is left</b> ({@link ViewOptions}): an untradeable stack prints
 * its High Alchemy price with a grey "alch" tag where the move figures go and says so in one tooltip sentence
 * (Q5). The other was {@code holdingOnRows}, which chose whether a row printed the STACK's figures or one
 * ITEM's (Q6) - and the Q4 format prints both, so it reached nothing here and addendum AO deleted the key
 * outright (AO1), taking the {@code priceText} / {@code gpText} / {@code quantityText} helpers that expressed
 * its two readings with it. Q5 is decided when the row is BUILT, so every assertion below builds the row it
 * measures.
 *
 * <p><b>Addendum R splits the untradeables in two</b> (R4): a stack RuneLite can take apart is worth what its
 * tradeable PARTS are worth, so it has a baseline and a move and must paint as an ordinary guide row does, with
 * one extra tooltip line naming the parts; only a stack with no such mapping keeps Q5's alch tag and sentence.
 * The tests below hold both halves of that - what the parts row must look like, and what the alch row still
 * does.
 *
 * <p><b>Addendum AI moved the description off the hover and into the CELL.</b> A row is now a FACE over a
 * DETAIL block inside the one card border: a left click shows the detail and the row grows by the height that
 * detail MEASURES, a second click shuts it and the row is exactly {@link MovementRowPanel#ROW_HEIGHT} again.
 * A row therefore carries no tooltip at all, on itself or on any child, and the tests that pinned the short
 * hover, its named AG6 form and the text a toggle handed to Swing went with {@code briefTooltip} and
 * {@code MORE_INFO}. What AI did NOT change is the COLLAPSED geometry, which the four pinned renders depend on.
 *
 * <p><b>Addendum AK rewrote what that cell SAYS</b>, and the tooltip tests below stopped describing it. The cell
 * is now the label column {@link MovementRowPanel#detail(MovementRow, MovementWindow, java.time.LocalDate,
 * ViewOptions, boolean)} writes - "Worth now / Was / You have / Change 1d", and under it only the notes that say
 * something - which is pinned under "addendum AK" near the end of this file. The long builder
 * ({@link MovementRowPanel#tooltip(MovementRow, MovementWindow, java.time.LocalDate, ViewOptions)}) is UNCHANGED
 * and still exists, so the tooltip tests below still describe it exactly; nothing in the sidebar draws it any
 * more, and if it is ever deleted they go with it.
 *
 * <p><b>Addendum AL quietened the gp figure on the FACE</b>: it takes
 * {@link MovementRowPanel#quietChangeColor} - the percentage's colour mixed toward the card - while the
 * percentage keeps {@link MovementRowPanel#textChangeColor}, so the two stop competing. Nothing else about a row
 * moves, and the geometry above is untouched: a colour does not change a box.
 *
 * <p><b>Addendum AJ put the "Show hover text" switch back, and a row is outside its reach.</b> AI had deleted
 * addendum AH's switch along with the hover it was written for, which left the hero card's exact-gp hover and
 * every control tooltip permanently on; AJ restores it for those, and for those only. A ROW carries no tooltip
 * at EITHER setting - its description is the block the cell opens - so the switch has nothing to silence here
 * and nothing to give back, and the click that opens the cell answers whatever the switch says. Both halves of
 * that are pinned under "addendum AJ" at the end of this file: they are the facts a reader who found a sixth
 * switch in {@link ViewOptions} would otherwise get wrong.
 *
 * <p><b>The Q4 row format (2026-09-20) is what the card now draws</b>, and it is the one change since addendum N
 * that moved the GEOMETRY the four pinned renders are pictures of. A row is three lines in a 62 px cell rather
 * than two in 48:
 *
 * <pre>
 * Divine ranging poti...(3)
 * 226k            +3.0k   +10.2%   &lt;- the STACK: its value, its gp move, the percentage
 * 7 x 32.3k       +428             &lt;- ONE item: the working, and one item's gp move
 * </pre>
 *
 * <p>The two gp figures stand in one right-aligned {@code GP_COLUMN} and the percentage in its own
 * {@code PCT_COLUMN} beside it, which is what lets the format read DOWN a page rather than along a line - pinned
 * by {@link #theTwoGpFiguresStandInOneColumnAndThePercentageInItsOwn}, the one assertion here that lays the row
 * out for real. One rule the user corrected during the design survives as its own pin: a stack of ONE prints its
 * working but no item gp (Q4.7 - {@link #aStackOfOnePrintsItsWorkingAndNoItemGp}).
 *
 * <p><b>The other one is gone, and so is the word it was about</b> (addendum AO line AO2). Q4.6 made the word
 * "Total" part of line 2, and AN6 then made it UNCONDITIONAL at the user's own instruction, because a build that
 * printed it only where it fitted dropped it on exactly the richest rows. The user has since seen it in a client
 * over a real bank - "1,851 Total", "68.6k Total", "5,750 Total" down the page - and asked for it to go, so line
 * 2 is now the stack's value and the two columns and nothing else. The guard that pinned the word is replaced
 * rather than deleted ({@link #noLabelOnARowPrintsTheWordTotal}): a word removed by request that creeps back in
 * is the same class of bug as a word that vanished, and this file is where either would show.
 *
 * <p>Two consequences worth stating, because a reader of the tests below will otherwise take them for bugs.
 * First, the face's gp figure is now COMPACT in the thousands - "+3.4k" where every build from N to AL printed
 * "+3,432" ({@link #theFaceGpFigureIsCompactInTheThousands}); the exact gp is still in the block the cell opens.
 * Second, nothing in {@link ViewOptions} changes a single thing a row DRAWS any more: the Q4 format printed both
 * of {@code holdingOnRows}'s readings at once, which is what let addendum AO delete that key, and the switches
 * that are left reach the description and never the face.
 *
 * <p><b>No literal pixel widths.</b> {@code Font.DIALOG} maps to a different face on every platform, so every
 * width assertion here measures the components it is about and compares them with
 * {@link MovementRowPanel#TEXT_WIDTH} (addendum N line N6), never with a number copied out of the spec. The
 * open HEIGHTS follow the same rule: a test asks the row's own detail block what it measures rather than
 * naming a number, which is what lets it tell a measured height from a guessed constant.
 *
 * <p><b>Addendum AS moved WHEN the open block is written</b>, and nothing about what it says or how tall it
 * opens. A row used to write its block - an HTML table the label turns into a view tree on the spot - in its
 * constructor, for every row of every page, whether anyone opened it or not; it now writes it on the row's
 * first opening, before measuring it. So every test here that READS the block opens the row first, through the
 * click a reader makes, and not through a test-only door that builds on demand: a second road to the text
 * could drift from the real one, and the click is the road the reader takes. The proof of the change is
 * {@link #aPageOfRowsBuiltAndThrownAwayWritesNoBlockAndNoDescription}, which counts every call to the builder
 * while fifty rows are built and thrown away and asserts the count did not move; the tests beside it under
 * "addendum AS" pin that nothing else moved - the height, the seam, the click on the block, the menu, the
 * silence.
 */
public class MovementRowPanelTest
{
	/**
	 * The DAY the 1 d baseline's guide table belongs to. The calibration probe read that table from revid
	 * 15333448 of {@code Module:GEPrices/data.json}, published 2026-09-07T19:55:12Z and carrying
	 * {@code %LAST_UPDATE_F%} "07 September 2026 19:45:22"
	 * ({@code docs/research/bank-price-movement-calibration-2026-09-08.md}).
	 *
	 * <p>A {@link LocalDate}, not that instant, since addendum L7: the publication clock is not the data day
	 * (a maintenance edit republishes the previous day's prices under the next day's timestamp, L-E), so what a
	 * row stamps is the day the table itself claims.
	 */
	private static final LocalDate THEN_DAY = LocalDate.of(2026, 9, 7);

	/** Abyssal whip: 12 at 1,520,000 (was 1,500,000) - a rise of 20,000 = +1.3 %. */
	private static MovementRow whip()
	{
		return new MovementRow(4151, "Abyssal whip", 12, false, 1_520_000L, 1_500_000L, 20_000L,
			20_000d * 100.0 / 1_500_000d, 18_240_000L, null);
	}

	private static MovementRow row(String name, Long unit, Long then, Long deltaGp, Double deltaPct)
	{
		final long holding = unit == null ? 0L : unit * 5L;
		return new MovementRow(1, name, 5, false, unit, then, deltaGp, deltaPct, holding, null);
	}

	/** A row of {@code quantity} items with a whole set of figures, for the quantity and fit-order rules. */
	private static MovementRow stack(String name, int quantity, Long unit, Long deltaGp, Double deltaPct)
	{
		final long holding = unit == null ? 0L : unit * quantity;
		final Long then = unit == null || deltaGp == null ? null : unit - deltaGp;
		return new MovementRow(2, name, quantity, true, unit, then, deltaGp, deltaPct, holding, null);
	}

	// ---- geometry (the Q4 format: 213 x 62, a 36 x 32 picture and a 162 px text block)

	/**
	 * The card the four renders are pictures of. Q4 took the row from two lines in 48 px to three in 62, and paid
	 * for the third line's width out of the margins beside the picture - the gap after it from 8 to 4 and the
	 * left padding from 4 to 2 - so the text block came out WIDER (162, was 156) with a whole extra line under it.
	 *
	 * <p>AN had narrowed the picture cell to 32 as well, for a 166 px block. Addendum AP put it back: see
	 * {@link #thePictureCellIsExactlyTheClientsItemSprite}.
	 */
	@Test
	public void everyRowIs213By62WithA36By32PictureCell() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("row", new Dimension(213, 62), p.getPreferredSize());
			assertEquals("the picture cell: the whole 36 x 32 sprite and the 2 px nudge beside it",
				new Dimension(36 + 2, 32), p.iconLabel().getPreferredSize());
			assertEquals(MovementRowPanel.ROW_WIDTH, Widgets.CONTENT_WIDTH);
			assertEquals("the row's height overrides addendum N's 48 and C31's 40", 62,
				MovementRowPanel.ROW_HEIGHT);
			// 213 - 3 (rail) - 3 - 6 (padding) - 38 (picture and nudge) - 1 (gap): the name's whole line, and the
			// line the stack and the item lines under it are laid out in.
			assertEquals("the text block", 162, MovementRowPanel.TEXT_WIDTH);

			// Three lines, in the order the format reads: the name, the stack, then the one item.
			assertNotNull("line 1, the name", nameLabel(p));
			assertNotNull("line 2, the stack", line2(p));
			assertNotNull("line 3, the item", line3(p));
		});
	}

	/**
	 * AI's invariant, and the reason it is pinned here: a COLLAPSED row must be the same arrangement of the same
	 * pixels every build before AI drew, because the four pinned renders the sidebar is checked against are
	 * pictures of collapsed rows. (The renders are re-pinned under a new dated name at addendum AM, which moves
	 * two controls in the panel's control row; a row's GEOMETRY is untouched by AK, AL and AM alike - only the gp
	 * figure's colour moves, and a colour does not change a box.)
	 *
	 * <p>So the FACE - which is what AI wrapped the old row's contents in - has to be exactly the box those
	 * contents used to have: the card's 213 px less the 3 px rail and the 2 / 6 px padding, and the 62 px height
	 * less the 3 / 2 px padding. The arithmetic is asserted from {@link MovementRowPanel#ROW_WIDTH} and the
	 * border's OWN insets rather than from four copied numbers, so a change to either end of it fails here.
	 *
	 * <p>Q4 moved both ends at once - the card is 62 px and the left padding is 2 - which is exactly what this
	 * assertion is shaped to catch, and the renders are re-pinned under Q4's own addendum letter.
	 */
	@Test
	public void aCollapsedRowIsTheCardAndItsFaceIsTheBoxInsideTheRailAndThePadding() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("the collapsed card", new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				p.getPreferredSize());

			final Insets in = p.getBorder().getBorderInsets(p);
			assertEquals("the rail is the card's left edge", Widgets.EDGE_WIDTH + 3, in.left);
			assertEquals(6, in.right);
			assertEquals(3, in.top);
			assertEquals(2, in.bottom);
			assertEquals("the face is the row less the rail and the padding",
				MovementRowPanel.ROW_WIDTH - in.left - in.right, MovementRowPanel.INNER_WIDTH);
			assertEquals(MovementRowPanel.ROW_HEIGHT - in.top - in.bottom, MovementRowPanel.FACE_HEIGHT);
			assertEquals("...and that is the box it is pinned to",
				new Dimension(MovementRowPanel.INNER_WIDTH, MovementRowPanel.FACE_HEIGHT),
				face(p).getPreferredSize());

			// The face is where the picture and the text block live, so the arrangement inside it is untouched.
			final BorderLayout inside = (BorderLayout) face(p).getLayout();
			assertSame("the picture", p.iconLabel(), inside.getLayoutComponent(BorderLayout.WEST));
			assertSame("the text block", textBlock(p), inside.getLayoutComponent(BorderLayout.CENTER));
		});
	}

	/**
	 * N6, read against the THREE lines Q4 draws: with the widest figures the formatters can produce and a name no
	 * sidebar could hold, no line asks for more than the text block and the row is still exactly 213 x 62. The
	 * absurd fixtures are addendum N section 3 §9's - a 2,147m unit price on a stack of 28,000 moving 100 %, and
	 * the same row with a -999.9m change - walked beside the two rows whose left half is a tag or a sum of parts
	 * rather than a price, and the row that has no price at all.
	 *
	 * <p>Line 3 is in the walk because Q4 added it and it is NOT fitted: the working ("9,999 x 18.2m") is set as
	 * it comes. It stays inside the block only because every figure on it is in stack style, which
	 * {@code QuantityFormatter.quantityToStackSize} caps at five characters - so this is the assertion that would
	 * fail first if a future addendum printed an exact number there.
	 *
	 * <p>One walk, not one per ordering: since addendum O nothing on the face varies with the sort (O1).
	 */
	@Test
	public void noLineOverflowsTheTextBlockWithTheWidestFigures() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow huge = stack("Ancient ceremonial legs of the utterly absurd", 28_000,
				2_147_000_000L, 1_073_500_000L, 100.0);
			final MovementRow crash = stack("Karambwan vessel (baited)", 28_000, 2_147_000_000L,
				-999_900_000L, -100.0);
			final MovementRow crowded = stack("Coins", 9_999, 9_999L, -9_999L, -99.9);
			for (MovementRow r : new MovementRow[]{huge, crash, crowded, whip(),
				alch("Graceful hood", 4, 20_000L), body("Crystal body", seeds()),
				row("Mystery box", null, null, null, null)})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D180, THEN_DAY);
				assertEquals(r.name(), new Dimension(213, 62), p.getPreferredSize());
				assertFits(r.name() + " name", nameLabel(p));
				assertFits(r.name() + " line 2", line2(p));
				assertFits(r.name() + " line 3", line3(p));

				// The fit order, as Q4 leaves it (the M4 count idiom): the two right-hand columns are sized FIRST
				// - they are the point of the row - and the stack value is fitted into what they left.
				assertTrue(r.name() + ": the stack value is fitted into what the columns left",
					priceLabel(p).getPreferredSize().width
						<= MovementRowPanel.TEXT_WIDTH - figures(p).getPreferredSize().width);
			}
		});
	}

	/**
	 * AO2, and the guard that replaced Q4.6's: <b>no label on a row prints the word "Total".</b> The word opened
	 * line 2's figure from the Q4 format until addendum AO, and AN6 had made it unconditional on the user's own
	 * instruction; the user then saw it in a client over a real bank - "1,851 Total", "68.6k Total", "5,750
	 * Total" down the page - and asked for it to be removed. That is a request, not a fit problem, so the answer
	 * is that the label does not exist rather than that it is drawn when there is room.
	 *
	 * <p>The walk is the one Q4.6's guard used, led by the rows the pre-AN fit check dropped the word on - the
	 * widest stack value there is, beside the widest gp figure and percentage - because those are the rows where
	 * a later hand looking for pixels would be tempted to put a conditional label back. The whole card is swept
	 * for the text and not just line 2: a word that came back somewhere else would be the same bug wearing a
	 * different hat.
	 *
	 * <p>Line 2's SHAPE is pinned with it, which is the structural half of the same fact: two children, the
	 * fitted stack value and the two-column figures group, and no third component between them. The 31 px the
	 * word used to take are back in the price's fit budget - it is now fitted to the whole of what the columns
	 * leave ({@link #noLineOverflowsTheTextBlockWithTheWidestFigures} measures that) - and the air before the
	 * figures is the panel's ordinary gap again rather than the narrowest one that fitted, which is asserted
	 * against the air after the gp figure instead of against a number (the file's no-literal-pixels rule).
	 */
	@Test
	public void noLabelOnARowPrintsTheWordTotal() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow widest = stack("Twisted bow", 28_000, 2_147_000_000L, -999_900_000L, -100.0);
			final MovementRow[] rows = {
				widest,
				stack("Coins", 9_999, 9_999L, -9_999L, -99.9),
				whip(),
				stack("Green hat", 1, 1_086L, 12L, 1.1),
				alch("Graceful hood", 4, 20_000L),
				body("Crystal body", seeds()),
				row("Mystery box", null, null, null, null),
			};
			for (MovementRow r : rows)
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D180, THEN_DAY);
				assertNull(r.name() + ": the word is gone, on the narrowest row and the widest alike",
					label(p, "Total"));
				final List<JComponent> children = new ArrayList<>();
				collect(p, children);
				for (JComponent c : children)
				{
					if (c instanceof JLabel)
					{
						assertFalse(r.name() + ": \"" + ((JLabel) c).getText() + "\" still says it",
							((JLabel) c).getText().contains("Total"));
					}
				}
				assertFits(r.name() + " line 2 without the word", line2(p));
				// The sweep above reached the open block's text too until addendum AS moved its writing to the
				// first opening, so the row is opened and the block read here rather than the guard reading less.
				leftClick(p);
				assertTrue(r.name() + ": the block was written", detail(p).getText().startsWith("<html>"));
				assertFalse(r.name() + ": the open block says it", detail(p).getText().contains("Total"));
			}

			// Line 2's shape: the fitted stack value, then the columns, and nothing in between where the word
			// used to stand.
			final MovementRowPanel p = new MovementRowPanel(widest, null, MovementWindow.D180, THEN_DAY);
			final BorderLayout layout = (BorderLayout) line2(p).getLayout();
			assertEquals("two children, and the word is not a third", 2, line2(p).getComponentCount());
			assertSame("the stack value, straight into the line and not through a wrapper", priceLabel(p),
				layout.getLayoutComponent(BorderLayout.WEST));
			assertSame(figures(p), layout.getLayoutComponent(BorderLayout.EAST));

			// AO restored the air before the figures to the gap the rest of the panel uses; it had been cut to
			// buy the gp column the px line 2 needed while it carried a value, a word, a gp figure and a
			// percentage at once. Read against the air after the gp figure, so no number is copied in here.
			assertEquals("the air before the columns is the panel's ordinary gap again",
				gpLabel(p).getInsets().right, figures(p).getInsets().left);
			assertTrue("...and it is real air", figures(p).getInsets().left > 0);
		});
	}

	/**
	 * A name that does not fit is cut with three ASCII dots (never U+2026 - the bitmap faces box a glyph they
	 * lack) and the whole name goes in the tooltip; a name that fits is left alone. The name now has the WHOLE
	 * text block, which is what buys "Confliction gauntlets" its last two letters back.
	 */
	@Test
	public void aLongNameIsCutToTheTextBlockAndKeptWholeInTheDescription() throws Exception
	{
		onEdt(() ->
		{
			final String monster = "A very long item name that goes on and on and on for ever";
			final MovementRowPanel p = new MovementRowPanel(row(monster, 1_000L, 900L, 100L, 11.1), null,
				MovementWindow.D180, THEN_DAY);
			assertTrue(p.nameText(), p.nameText().endsWith(Widgets.ELLIPSIS));
			assertNotEquals(monster, p.nameText());
			assertFits("the cut name", nameLabel(p));
			// AI: the whole name is one click away, in the block the row opens - the long description has bolded
			// it at the top since K8, which is why AG6's named short hover could go.
			assertTrue("the whole name is in the description", p.tooltipHtml().contains(monster));

			final MovementRowPanel short0 = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("a name that fits is not touched", "Abyssal whip", short0.nameText());
		});
	}

	// ---- line 2: the gp figure and the percentage

	/**
	 * O1: the row prints the PAIR - the gp change then the percentage, both in the move's colour. Addendum N's
	 * LOOK A withheld the gp figure except under a gp sort, so the sorted figure could be missing from the face;
	 * with One Bar deleted that question is gone, the row takes no ordering at all, and both of its constructors
	 * print the same face.
	 */
	@Test
	public void everyRowPrintsTheGpAndThePercent() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow r = whip();
			assertEquals("+1.3%", MovementRowPanel.changeText(r));

			// Q4: line 2 is the STACK, so the gp figure beside the percentage is the whole stack's move
			// (12 x +20,000 = +240k) and ONE item's is on the line under it. The percentage is the same number
			// per item and per stack, so it is untouched by that.
			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);
			assertEquals("+240k", p.gpText());
			assertEquals("+20k", itemGpLabel(p).getText());
			assertEquals("+1.3%", p.changeText());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, p.changeColor());

			// ...and a row built with contract C31's constructor - no baseline day - prints it too.
			final MovementRowPanel untold = new MovementRowPanel(r, null, MovementWindow.D1);
			assertEquals("+240k", untold.gpText());
			assertEquals("+20k", itemGpLabel(untold).getText());
			assertEquals("+1.3%", untold.changeText());
		});
	}

	/** A row with nothing to show has no gp figure at all - the dash beside it already says so. */
	@Test
	public void aRowWithoutMovementHasNoGpFigure() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow none = row("Coal", 150L, null, null, null);
			final MovementRowPanel p = new MovementRowPanel(none, null, MovementWindow.D7, null);
			assertEquals("gp", "", p.gpText());
			assertEquals("...and the item's line says nothing either", "", itemGpLabel(p).getText());
			assertEquals("pct", MovementMath.DASH, p.changeText());
			assertEquals("colour", ColorScheme.LIGHT_GRAY_COLOR, p.changeColor());
		});
	}

	/**
	 * Addendum AT: a rise of a hundred percent or more fits its column whole. The user's Elemental shield
	 * (2026-09-24) read "+157..." because "+157.8%" measures 58 px at the row's bold 15 px face and the column
	 * is 54 - "-100.0%" is 54 and held, a plus being 4 px wider than a minus, so only RISES were cut, and every
	 * one of them. The face now prints "+157%" (46), and the block the row opens into keeps the exact "+157.4%".
	 * The premise is asserted too, so the test cannot pass by accident of a wider column or a narrower face.
	 */
	@Test
	public void aRiseOfAHundredPercentOrMoreFitsItsColumnWhole() throws Exception
	{
		onEdt(() ->
		{
			// Elemental shield: 1 at 2,551, up 1,560 from 991 = +157.4 %.
			final MovementRow shield = stack("Elemental shield", 1, 2_551L, 1_560L, 1_560d * 100.0 / 991d);
			final RecordingExpansion seam = new RecordingExpansion();
			seam.setExpanded(shield.id(), true);
			final MovementRowPanel p = new MovementRowPanel(shield, null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, seam);
			layOut(p);

			assertEquals("+157%", p.changeText());
			final JLabel pct = label(p, "+157%");
			assertNotNull("the percentage is on the row", pct);
			final FontMetrics fm = pct.getFontMetrics(pct.getFont());
			final int room = pct.getWidth() - pct.getInsets().left - pct.getInsets().right;
			assertTrue("the box is real", room > 0);
			assertTrue("'+157%' (" + fm.stringWidth("+157%") + " px) fits its " + room + " px box whole",
				fm.stringWidth("+157%") <= room);
			assertTrue("the premise: the old '+157.4%' (" + fm.stringWidth("+157.4%") + " px) overflowed it",
				fm.stringWidth("+157.4%") > room);
			assertTrue("...while '-100.0%' always held", fm.stringWidth("-100.0%") <= room);

			// The exact figure is still in the block the row opens into, and the short form is not.
			final String block = detail(p).getText();
			assertTrue("the block keeps the decimal: " + block, block.contains("+157.4%"));
			assertFalse("...and not the face's short form", block.contains("+157%"));
		});
	}

	// ---- the Q4 format: a stack line over an item line, in two shared columns

	/**
	 * What the two lines under the name ARE, piece by piece, in the faces the format gives them: line 2 is the
	 * STACK - what the whole holding is worth (13 px white), the stack's gp move and the percentage - and line 3
	 * is ONE item, the working behind the figure above it ("12 x 1.52m", 11 px grey) and that one item's gp move.
	 * The word "Total" stood between the first two until addendum AO removed it (AO2,
	 * {@link #noLabelOnARowPrintsTheWordTotal}); the 13 px face it was sized against did NOT move with it, which
	 * is why that size is still asserted here.
	 *
	 * <p>The faces are compared against {@link Widgets} calls rather than against sizes copied out of the design
	 * doc, so a change of scale has to be made in one place; the SIZES are the format, because it is the contrast
	 * between the 15 px percentage and the 10 px gp figure that the user picked variant F for.
	 */
	@Test
	public void lineTwoIsTheStackAndLineThreeIsOneItem() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow r = whip();
			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);

			// Line 2, left to right.
			assertEquals("the whole holding, 12 x 1,520,000", "18.2m", priceLabel(p).getText());
			assertEquals(Widgets.sans(13), priceLabel(p).getFont());
			assertEquals(Color.WHITE, priceLabel(p).getForeground());
			assertEquals("the stack's move", "+240k", gpLabel(p).getText());
			assertEquals(Widgets.sans(10), gpLabel(p).getFont());
			assertEquals("AL's quiet colour, so the percentage is the one the eye lands on",
				MovementRowPanel.quietChangeColor(r), gpLabel(p).getForeground());
			assertEquals("+1.3%", label(p, "+1.3%").getText());
			assertEquals("the biggest figure on the row", Widgets.sansBold(15), label(p, "+1.3%").getFont());
			assertEquals(MovementRowPanel.textChangeColor(r), label(p, "+1.3%").getForeground());

			// Line 3, left to right. A lowercase "x" and not the multiplication sign: the bitmap faces box a
			// glyph they lack, and this label is drawn in one of them on some machines.
			assertEquals("12 x 1.52m", workingLabel(p).getText());
			assertTrue(workingLabel(p).getText().contains(" x "));
			assertFalse("never the multiplication sign", workingLabel(p).getText().contains("\u00d7"));
			assertEquals(Widgets.sans(11), workingLabel(p).getFont());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, workingLabel(p).getForeground());
			assertEquals("one item's move", "+20k", itemGpLabel(p).getText());
			assertEquals("the same face as the figure above it", Widgets.sans(10), itemGpLabel(p).getFont());
			assertEquals(MovementRowPanel.quietChangeColor(r), itemGpLabel(p).getForeground());

			// The two lines are different numbers, which is the whole reason there are two of them.
			assertNotEquals(priceLabel(p).getText(), workingLabel(p).getText());
			assertNotEquals(gpLabel(p).getText(), itemGpLabel(p).getText());
		});
	}

	/**
	 * The shape the format stands on, and the only assertion in this file that lays a row out for real: the two
	 * gp figures share ONE right-aligned column, the stack's directly over the item's, and the percentage has a
	 * column of its own to the right of it that the gp figures never reach into. That is what lets a reader run
	 * an eye straight down a page of rows instead of hunting along each one - the reason every single-line
	 * arrangement was rejected during the design.
	 *
	 * <p>Laid out by hand rather than through {@code validate()}, which wants a peer this machine has no display
	 * to give it; {@link #layOut(MovementRowPanel)} makes the same {@code layoutContainer} calls down the tree.
	 * Positions are then read in the ROW's coordinates, so what is compared is where the two figures really stand
	 * on the card and not where they stand inside two different parents.
	 */
	@Test
	public void theTwoGpFiguresStandInOneColumnAndThePercentageInItsOwn() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			layOut(p);

			final JLabel stackGp = gpLabel(p);
			final JLabel itemGp = itemGpLabel(p);
			final JLabel pct = label(p, p.changeText());
			assertNotNull("the percentage is on the row", pct);

			final int gpLeft = leftEdge(stackGp, p);
			assertTrue("a column with no width is not a column", stackGp.getWidth() > 0);
			assertEquals("the two gp figures share one left edge", gpLeft, leftEdge(itemGp, p));
			assertEquals("...and one width", stackGp.getWidth(), itemGp.getWidth());
			// Right-aligned inside that width, with the same air after them, so the DIGITS line up and not just
			// the boxes - a left-aligned "+428" under a right-aligned "+3.0k" would read as two columns.
			assertEquals(SwingConstants.RIGHT, stackGp.getHorizontalAlignment());
			assertEquals(SwingConstants.RIGHT, itemGp.getHorizontalAlignment());
			assertEquals("the same air on the right of both", stackGp.getInsets().right,
				itemGp.getInsets().right);
			assertTrue("...and it is real air", stackGp.getInsets().right > 0);

			// The percentage: its own column, wholly to the right of the gp column and never over it.
			final int pctLeft = leftEdge(pct, p);
			assertTrue(pct.getWidth() > 0);
			assertTrue("the percentage starts at " + pctLeft + " where the gp column ends at "
				+ (gpLeft + stackGp.getWidth()), pctLeft >= gpLeft + stackGp.getWidth());

			// Line 3 keeps that width EMPTY rather than letting its gp figure slide right into it, which is what
			// holds the column above it true - the spacer is the format, not padding.
			final JComponent spacer = pctSpacer(p);
			assertEquals("the spacer stands under the percentage", pctLeft, leftEdge(spacer, p));
			assertEquals("...and is exactly as wide", pct.getWidth(), spacer.getWidth());

			// Both lines end on the same right edge, and that edge is inside the text block.
			assertEquals(pctLeft + pct.getWidth(), leftEdge(spacer, p) + spacer.getWidth());
			assertTrue("the columns end inside the card", pctLeft + pct.getWidth() <= MovementRowPanel.ROW_WIDTH);
		});
	}

	/**
	 * Q4.7, the second of the two rules the user corrected: <b>a stack of ONE prints its working but no item
	 * gp.</b> The user, on the variant that printed both: "if there's only 1 item then only show the Total rows
	 * gp move" - otherwise "+744" sits directly under "+744" and the row says one thing twice.
	 *
	 * <p>The working still prints at a quantity of one ("1 x 1.63b"), so every row in the list reads the same way
	 * down the page, and the gp column is still THERE - empty, not absent - so the percentage above it keeps its
	 * place on a list that is mostly single items.
	 */
	@Test
	public void aStackOfOnePrintsItsWorkingAndNoItemGp() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel one = new MovementRowPanel(stack("Twisted bow", 1, 1_632_000_000L,
				-58_000_000L, -3.4), null, MovementWindow.D1, THEN_DAY);
			assertEquals("the working still prints", "1 x 1.63b", workingLabel(one).getText());
			assertEquals("the stack's own move is the only one", "-58m", one.gpText());
			assertEquals("...and the item's would be the same figure directly under it", "",
				itemGpLabel(one).getText());

			// A stack of many: both figures, and they are different numbers.
			final MovementRowPanel many = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("12 x 1.52m", workingLabel(many).getText());
			assertEquals("+240k", many.gpText());
			assertEquals("+20k", itemGpLabel(many).getText());
			assertNotEquals("the two lines say different things", many.gpText(), itemGpLabel(many).getText());

			// The empty column is still a column, or a page of single items would lose its right edge.
			assertEquals(itemGpLabel(many).getPreferredSize().width,
				itemGpLabel(one).getPreferredSize().width);
		});
	}

	/**
	 * Q4 §6, the one behaviour change under the format rather than in it: the face's gp figure is COMPACT in the
	 * thousands - "+3.4k" where every build from addendum N to AL printed "+3,432" - because the column does not
	 * fit beside a 15 px percentage otherwise. Below 1,000 and from 10,000 up it is the game's own stack text,
	 * which it always was; the tenths only exist in the gap between.
	 *
	 * <p>Both edges of that gap are asked, and so is the rounding INSIDE it, which is where this would be wrong
	 * without being obviously wrong: 9,949 is "9.9k" and 9,950 is "10.0k", so the compact form can print a "10.0k"
	 * that the plain form would print as "10k" one gp later. Driven through a built row's own label rather than
	 * through a helper, because the compacting is private to the panel and the FACE is where it matters.
	 *
	 * <p>The precision that is lost here is not lost from the plugin: the last leg checks that the exact figure is
	 * still in the block the cell opens, which is the trade the user accepted.
	 */
	@Test
	public void theFaceGpFigureIsCompactInTheThousands() throws Exception
	{
		onEdt(() ->
		{
			// Under a thousand: the plain figure, as it always was.
			assertEquals("+999", faceGp(999L));
			assertEquals("-999", faceGp(-999L));

			// The tenths, and the rounding inside them.
			assertEquals("+1.0k", faceGp(1_000L));
			assertEquals("the user's own example", "+3.4k", faceGp(3_432L));
			assertEquals("+9.9k", faceGp(9_949L));
			assertEquals("rounded to the nearest tenth, not truncated", "+10.0k", faceGp(9_950L));
			assertEquals("+10.0k", faceGp(9_999L));
			assertEquals("a negative keeps its sign", "-3.4k", faceGp(-3_432L));
			assertEquals("-10.0k", faceGp(-9_999L));

			// Ten thousand and up: the existing compact form, unchanged.
			assertEquals("+10k", faceGp(10_000L));
			assertEquals("-10k", faceGp(-10_000L));
			assertEquals("+20k", faceGp(20_000L));
			assertEquals("+1.52m", faceGp(1_520_000L));

			// ...and the exact gp the face gave up is still one click away, in the block the cell opens - and
			// since addendum AS it is that click which writes the block, so the leg makes it before reading.
			final MovementRow r = stack("Coins", 1, 2_000_000_000L, 3_432L, 0.1);
			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);
			assertEquals("+3.4k", p.gpText());
			leftClick(p);
			assertTrue(detail(p).getText(), detail(p).getText().contains("+3,432"));
		});
	}

	/**
	 * Addendum AK's open cell is untouched by Q4: the block is the same label column, wrapped to the same cell,
	 * and the row's OPEN height is still the face plus what that block MEASURES - which is the assertion that had
	 * to be re-read, because the face it is added to grew by 14 px.
	 */
	@Test
	public void theOpenCellIsUnchangedAndStillMeasuresItsOwnHeightFromTheTallerFace() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			// AS: the click writes the block, so it comes first and the block is read after it.
			leftClick(p);
			assertTrue(p.expanded());
			assertEquals("the block is the one AK writes, not re-written by Q4",
				MovementRowPanel.detail(whip(), MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, false),
				detail(p).getText());
			assertTrue("wrapped to the cell, which Q4 widened by the two px it took off the left padding",
				detail(p).getText().startsWith("<html><div width=\"" + MovementRowPanel.INNER_WIDTH + "\">"));
			assertEquals("the open row is the 62 px face plus the block's own measurement",
				MovementRowPanel.ROW_HEIGHT + detailHeight(p), p.getPreferredSize().height);
			assertEquals("...which is 62 and not 48", 62 + detailHeight(p), p.getPreferredSize().height);

			leftClick(p);
			assertEquals("and shut again it is exactly the card the renders are of",
				new Dimension(213, 62), p.getPreferredSize());
		});
	}

	// ---- the quantity-1 rule

	/**
	 * Where the quantity went. Every build up to the Q4 format put an "x3" tag on line 2 beside the price, and
	 * the format moved the count into line 3's working ("3 x 1,086") - so the tag is nowhere on the card at any
	 * quantity, and what line 2 opens with is the whole stack's worth instead.
	 *
	 * <p>The helper that answered the old tag ({@code quantityText}) went with the config key addendum AO
	 * deleted, along with the two pure price and gp helpers beside it, so what is pinned here is what the CARD
	 * draws. That is the right place for it in any case: the tag left the face at Q4 and a helper nobody drew
	 * was only pinning a string.
	 */
	@Test
	public void theCountIsOnLineThreeAndNoStackTagIsOnTheCard() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel one = new MovementRowPanel(stack("Green hat", 1, 1_086L, 12L, 1.1), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("the stack of one IS worth one item", "1,086", one.priceText());
			assertEquals("1 x 1,086", workingLabel(one).getText());
			assertNull("no \"x1\" beside the price", label(one, "x1"));

			final MovementRowPanel three = new MovementRowPanel(stack("Green hat", 3, 1_086L, 12L, 1.1), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("3 x 1,086 = 3,258", "3,258", three.priceText());
			assertEquals("3 x 1,086", workingLabel(three).getText());
			assertNull("no \"x3\" anywhere on the card any more", label(three, "x3"));

			// A big stack: the count on line 3 is the game's own stack text, so it shortens itself the way the
			// game does rather than running a five-digit number into the price beside it.
			final MovementRowPanel big = new MovementRowPanel(stack("Coins", 28_000, 1L, 0L, 0.0), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("28k x 1", workingLabel(big).getText());
			assertNull("and still no tag", label(big, "x28k"));
		});
	}

	// ---- the rail (N section 2, N section 3 §4)

	@Test
	public void theRailIsTheDarkenedMoveColourForMoversAndInvisibleForEveryoneElse() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow rise = row("Abyssal whip", 1_520_000L, 1_500_000L, 20_000L, 1.33);
			final MovementRow fall = row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47);
			final MovementRow flat = row("Coal", 150L, 150L, 0L, 0.0);
			final MovementRow noBaseline = row("Coal", 150L, null, null, null);
			final MovementRow noPrice = row("Mystery box", null, null, null, null);

			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), MovementRowPanel.railColor(rise));
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), MovementRowPanel.railColor(fall));
			assertEquals("a quiet day is a quiet list", ColorScheme.DARKER_GRAY_COLOR,
				MovementRowPanel.railColor(flat));
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, MovementRowPanel.railColor(noBaseline));
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, MovementRowPanel.railColor(noPrice));

			assertEquals("rise", ColorScheme.PROGRESS_COMPLETE_COLOR.darker(),
				new MovementRowPanel(rise, null, MovementWindow.D1, THEN_DAY).railColor());
			assertEquals("fall", ColorScheme.PROGRESS_ERROR_COLOR.darker(),
				new MovementRowPanel(fall, null, MovementWindow.D1, THEN_DAY).railColor());
			assertEquals("flat", ColorScheme.DARKER_GRAY_COLOR,
				new MovementRowPanel(flat, null, MovementWindow.D1, THEN_DAY).railColor());
		});
	}

	/** The rail is a border, so it is still there after a hover has repainted the card. */
	@Test
	public void theRailSurvivesAHover() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertNotNull(p.getBorder());
			enter(p, p);
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), p.railColor());
			assertNotNull(p.getBorder());
			exit(p, p);
		});
	}

	// ---- texts and colours

	/**
	 * The whole of the Q4 face on one row, in the order it is read: the name, then the stack (its value, its
	 * move, the percentage), then the one item (the working, and one item's move). Every figure is a
	 * short form - {@code formatGp} is the game's own stack text, so 20,000 is "20k" and never "20.0k"
	 * (QuantityFormatter, A1 as landed) - and the two gp figures are compact even in the thousands (Q4 §6).
	 */
	@Test
	public void textsAreTheMathsShortForms() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("Abyssal whip", p.nameText());

			// Line 2, the stack: 12 x 1,520,000 = 18,240,000, moving 12 x +20,000 = +240,000.
			assertEquals("18.2m", p.priceText());
			assertEquals("+240k", p.gpText());
			assertEquals("+1.3%", p.changeText());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, p.changeColor());

			// Line 3, one item: the working behind the figure above it, and that one item's own move.
			assertEquals("12 x 1.52m", workingLabel(p).getText());
			assertEquals("+20k", itemGpLabel(p).getText());
		});
	}

	@Test
	public void aFallIsRedAZeroMoveAndNoMoveAreGrey() throws Exception
	{
		onEdt(() ->
		{
			// -21.47 truncates toward zero to -21.4, never rounds to -21.5 (L2): the row never prints a move
			// larger than the one that happened.
			final MovementRow fall = row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47);
			assertEquals("-21.4%", MovementRowPanel.changeText(fall));
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, MovementRowPanel.changeColor(fall));
			// The gp figure is read off a BUILT row since addendum AO deleted the pure helper that answered it
			// per switch: line 2 prints what the STACK of five moved, compacted (Q4 section 6), and line 3 what
			// one of them did.
			final MovementRowPanel fell = new MovementRowPanel(fall, null, MovementWindow.D1, THEN_DAY);
			assertEquals("the stack, 5 x -4,100", "-20.5k", fell.gpText());
			assertEquals("one item, compacted", "-4.1k", itemGpLabel(fell).getText());

			final MovementRow flat = row("Coal", 150L, 150L, 0L, 0.0);
			assertEquals("0.0%", MovementRowPanel.changeText(flat));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.changeColor(flat));
			final MovementRowPanel level = new MovementRowPanel(flat, null, MovementWindow.D1, THEN_DAY);
			assertEquals("a price that did not move prints its percentage and nothing else (B055)", "",
				level.gpText());

			// A row that is priced but has no baseline - the guide history has not landed, or the wiki table
			// carries no name for it - shows ONE dash and the dim grey.
			final MovementRow noBaseline = row("Coal", 150L, null, null, null);
			assertEquals("one dash, never two", MovementMath.DASH, MovementRowPanel.changeText(noBaseline));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.changeColor(noBaseline));
		});
	}

	/**
	 * L2: a fall too small to survive truncation still shows its minus and still paints red, because both the
	 * sign and the colour come from the gp change - the site's own behaviour ("Amulet of fury", 30 d, -0.10 %
	 * printed "-0.0%" under a red arrow, L-B). The mirror case is a rise of the same size, and the third state
	 * is a row that really did not move.
	 */
	@Test
	public void aMoveTooSmallToPrintKeepsItsSignAndItsColour() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow fall = row("Amulet of fury", 1_999_200L, 2_000_000L, -800L,
				-800d * 100.0 / 2_000_000d);
			assertEquals("-0.0%", MovementRowPanel.changeText(fall));
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, MovementRowPanel.changeColor(fall));
			final MovementRowPanel p = new MovementRowPanel(fall, null, MovementWindow.D30, THEN_DAY);
			assertEquals("-0.0%", p.changeText());
			assertEquals("the printed figure takes the lifted red (B045)", Widgets.MOVE_DOWN_TEXT, p.changeColor());
			assertEquals("and the rail agrees with it", ColorScheme.PROGRESS_ERROR_COLOR.darker(),
				p.railColor());

			final MovementRow rise = row("Amulet of fury", 2_000_800L, 2_000_000L, 800L,
				800d * 100.0 / 2_000_000d);
			assertEquals("+0.0%", MovementRowPanel.changeText(rise));
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, MovementRowPanel.changeColor(rise));
		});
	}

	@Test
	public void aRowWithoutAPriceShowsDashes() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow none = row("Mystery box", null, null, null, null);
			assertEquals(MovementMath.DASH, MovementRowPanel.changeText(none));

			final MovementRowPanel p = new MovementRowPanel(none, null, MovementWindow.D1, null);
			// A stack with no price is not worth 0: the dash is asked of the UNIT price, on both lines.
			assertEquals(MovementMath.DASH, p.priceText());
			assertEquals("5 x " + MovementMath.DASH, workingLabel(p).getText());
			assertEquals(MovementMath.DASH, p.changeText());
			assertEquals("", p.gpText());
			assertEquals("no rail without a price", ColorScheme.DARKER_GRAY_COLOR, p.railColor());
		});
	}

	// ---- tooltip (K8, day stamp L7) - UNCHANGED by addendum N, and now load-bearing

	/**
	 * K8's five lines with L7's stamp. The baseline line carries the DAY the "then" table belongs to, because
	 * the guide series steps once per UTC day and a reader checking an odd move needs to know whether the
	 * baseline is yesterday's or last Tuesday's. A day and not a clock: the wiki's bot republishes at a random
	 * hour (02:11-22:20 UTC, L-C), so the publication time addendum K printed here named nothing usable.
	 *
	 * <p>Addendum N makes "Holding:" and "Change:" the ONLY place the holding value and the exact gp change
	 * exist - both left the face of the row - so this assertion is now the whole of their coverage.
	 */
	@Test
	public void tooltipIsTheGuidePriceAndTheStampedBaselineDay()
	{
		final String tip = MovementRowPanel.tooltip(whip(), MovementWindow.D1, THEN_DAY);
		assertTrue(tip, tip.startsWith("<html><b>Abyssal whip</b>"));
		assertTrue(tip, tip.contains("Guide price: 1,520,000 gp"));
		assertTrue(tip, tip.contains("1d ago (07 Sep): 1,500,000 gp"));
		assertTrue(tip, tip.contains("Holding: 12 = 18,240,000 gp"));
		// "per item" is load-bearing (B048): this figure is the change in ONE item's guide price, printed two
		// lines under a "Holding:" line that is the whole stack.
		assertTrue(tip, tip.contains("Change per item: +20,000 gp (+1.3%)"));
		assertFalse("a bare \"Change:\" reads as the holding's change", tip.contains("<br>Change: "));
		assertTrue(tip, tip.endsWith("</html>"));
		// The old wording is gone: there is no second source to distinguish any more (K6).
		assertFalse(tip, tip.contains("Unit:"));
		assertFalse(tip, tip.contains("wiki "));
		assertFalse(tip, tip.contains("RuneLite"));
		// ...and so is the K-era clock beside the day (L7).
		assertFalse("no publication clock any more", tip.contains("07 Sep 19:"));
	}

	/** The two figures that left the face are on the tooltip of the built row. */
	@Test
	public void theHoldingAndTheExactChangeAreOnEveryBuiltRow() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertTrue(p.tooltipHtml(), p.tooltipHtml().contains("Holding: 12 = 18,240,000 gp"));
			assertTrue(p.tooltipHtml(), p.tooltipHtml().contains("Change per item: +20,000 gp (+1.3%)"));
		});
	}

	/** Every window labels its own line, and each of the five is one a chip can select. */
	@Test
	public void everyWindowLabelsItsOwnBaselineLine()
	{
		for (MovementWindow w : MovementWindow.values())
		{
			final String tip = MovementRowPanel.tooltip(whip(), w, THEN_DAY);
			assertTrue(tip, tip.contains(w.label() + " ago (07 Sep):"));
		}
	}

	/**
	 * The day is printed as it stands, in no zone: it was derived in UTC from the table's own
	 * {@code %LAST_UPDATE%} (L9), so this literal holds wherever the suite runs - which is the whole point of
	 * carrying a {@link LocalDate} down here instead of an instant.
	 */
	@Test
	public void theBaselineDayIsTheUtcDateInEveryTimeZone()
	{
		assertTrue(MovementRowPanel.tooltip(whip(), MovementWindow.D180, LocalDate.of(2026, 3, 12))
			.contains("180d ago (12 Mar):"));
		assertTrue(MovementRowPanel.tooltip(whip(), MovementWindow.D1, LocalDate.of(2026, 1, 1))
			.contains("1d ago (01 Jan):"));
	}

	/**
	 * L5's "accept and label" case: the window asked for the 3rd, the only revision of that date was a
	 * maintenance edit carrying the 2nd's prices, so the row stamps the day the table really claims rather than
	 * the day that was wanted. Nothing here computes that - it is what the service hands down - but the row must
	 * print what it is given.
	 */
	@Test
	public void tooltipStampsTheDayItIsGivenNotTheDayTheWindowAskedFor()
	{
		assertTrue(MovementRowPanel.tooltip(whip(), MovementWindow.D1, LocalDate.of(2026, 9, 2))
			.contains("1d ago (02 Sep):"));
	}

	/** Nothing known yet: no baseline stamp, no baseline price, no holding, no change - dashes, not zeroes. */
	@Test
	public void tooltipDashesEveryFigureItDoesNotHave()
	{
		final MovementRow noBaseline = row("Coal", 150L, null, null, null);
		final String tip = MovementRowPanel.tooltip(noBaseline, MovementWindow.D7, null);
		assertTrue(tip, tip.contains("Guide price: 150 gp"));
		assertTrue("no baseline day yet stamps a dash, never 1970", tip.contains("7d ago (-): -"));
		assertTrue(tip, tip.contains("Holding: 5 = 750 gp"));
		assertTrue(tip, tip.contains("Change per item: -"));

		final MovementRow none = row("Mystery box", null, null, null, null);
		final String noneTip = MovementRowPanel.tooltip(none, null, null);
		assertTrue(noneTip, noneTip.contains("Guide price: - (no price)"));
		assertTrue("a null window reads as the default", noneTip.contains("1d ago (-): -"));
		assertTrue(noneTip, noneTip.contains("Holding: 5 = -"));
	}

	@Test
	public void tooltipEscapesTheName()
	{
		final MovementRow odd = row("Zulrah's <scales> & co", 100L, null, null, null);
		final String tip = MovementRowPanel.tooltip(odd, MovementWindow.D1, null);
		assertTrue(tip, tip.contains("<b>Zulrah&#39;s &lt;scales&gt; &amp; co</b>"));
	}

	// ---- addendum T: the live traded price, on the tooltip and nowhere else (T6)

	/** The 1 d baseline's day in the live fixtures - the day the traded bucket belongs to. */
	private static final LocalDate TRADED_DAY = LocalDate.of(2026, 9, 10);
	/** The view switches with the live switch OFF: the guide-only row of addenda K to S (T8). */
	private static final ViewOptions LIVE_OFF = ViewOptions.DEFAULT.withLivePrices(false);

	/**
	 * A Twisted bow priced off the traded series (T4): the mid of a 63.6m buy and a 63.3m sell, 517 units traded
	 * yesterday, against that day's traded average of 62,100,000.
	 *
	 * @param windowSource what the 1 d window used - LIVE for a traded comparison, GUIDE for T4's fallback
	 */
	private static MovementRow liveBow(MovementRow.PriceSource windowSource)
	{
		final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
		sources.put(MovementWindow.D1, windowSource);
		return guideBow().asLive(null, sources, new MovementRow.LiveFacts(63_600_000L, 63_300_000L, 517L, null));
	}

	/**
	 * {@link #liveBow(MovementRow.PriceSource)} carrying the DAY its 1 d window actually compared against (addendum
	 * U, line U3) - the traded bucket's own day on a live window, the guide baseline day on one that fell back.
	 */
	private static MovementRow liveBow(MovementRow.PriceSource windowSource, LocalDate windowDay)
	{
		final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
		sources.put(MovementWindow.D1, windowSource);
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, windowDay);
		return guideBow().asLive(null, sources, days,
			new MovementRow.LiveFacts(63_600_000L, 63_300_000L, 517L, null));
	}

	/** The same figures with no traded series behind them: what the live row has to paint exactly like. */
	private static MovementRow guideBow()
	{
		return new MovementRow(20997, "Twisted bow", 1, false, 63_437_264L, 62_100_000L, 1_337_264L,
			1_337_264d * 100.0 / 62_100_000d, 63_437_264L, MovementRow.PriceSource.GUIDE);
	}

	/**
	 * T6: a live row's tooltip names the series and shows the three figures behind the price - the two sides it is
	 * the middle of, and the volume that let it qualify - and its window line says the baseline is that day's
	 * traded average and not a guide table's entry.
	 */
	@Test
	public void aLiveRowsTooltipNamesTheTradedSeriesAndShowsBothSidesAndTheVolume()
	{
		final String tip = MovementRowPanel.tooltip(liveBow(MovementRow.PriceSource.LIVE), MovementWindow.D1, TRADED_DAY);
		System.out.println("the live tooltip: " + tip);
		assertTrue(tip, tip.startsWith("<html><b>Twisted bow</b>"));
		assertTrue(tip, tip.contains("<br>Live traded price: 63,437,264 gp (buy 63.6m, sell 63.3m; 517 traded yesterday)"));
		assertFalse("a live price is not a guide price, and the line says which it is", tip.contains("Guide price"));
		assertTrue(tip, tip.contains("<br>1d ago (10 Sep): 62,100,000 gp (traded average)"));
		assertTrue("the holding and the change are the ordinary ones", tip.contains("<br>Holding: 1 = 63,437,264 gp"));
		assertTrue(tip, tip.contains("<br>Change per item: +1,337,264 gp (+2.1%)"));
		assertTrue(tip, tip.endsWith("</html>"));
		assertEquals("Live traded price: ", MovementRowPanel.LIVE_PRICE_PREFIX);
	}

	/**
	 * T4 / T6: a row whose price is live but whose traded bucket for THAT day could not answer compares the
	 * guide's own two ends for that window, and the window line says so. Never a live "now" over a guide "then" -
	 * that is the hybrid the live study refuted - so the note is the only way a reader can see which comparison
	 * was made.
	 *
	 * <p>V5 rewrote the note: a bucket is now refused for its own buy/sell gap as well as for its volume (V3), so
	 * one note covers both - "too few or too scattered trades that day" - rather than telling a reader "too few"
	 * about a day that had thousands of them.
	 */
	@Test
	public void aLiveRowWhoseWindowFellBackToTheGuideSaysSoOnThatLine()
	{
		assertEquals("the wording addendum V asks for, pinned", " (guide - too few or too scattered trades that day)",
			MovementRowPanel.WINDOW_FELL_BACK_NOTE);
		final String tip = MovementRowPanel.tooltip(liveBow(MovementRow.PriceSource.GUIDE), MovementWindow.D1, TRADED_DAY);
		assertTrue(tip, tip.contains("<br>Live traded price: 63,437,264 gp (buy 63.6m"));
		assertTrue(tip, tip.contains("<br>1d ago (10 Sep): 62,100,000 gp (guide - too few or too scattered trades that day)"));
		assertFalse(tip, tip.contains("(traded average)"));
		// A window nothing was recorded for reads the same way rather than claiming a live comparison (T4).
		final MovementRow noRecord = guideBow().asLive(null, null, new MovementRow.LiveFacts(63_600_000L, 63_300_000L, 517L, null));
		assertTrue(MovementRowPanel.tooltip(noRecord, MovementWindow.D30, TRADED_DAY).contains(
			"<br>30d ago (10 Sep): 62,100,000 gp" + MovementRowPanel.WINDOW_FELL_BACK_NOTE));
	}

	/**
	 * T6: a row the checks left on the guide price, while the switch is on, gains exactly one line - the FIRST
	 * check that refused it, as the service recorded it - directly under the price it explains. Everything else on
	 * the tooltip is untouched, and with the switch off the same row's tooltip is the pre-T one to the character
	 * (T8).
	 */
	@Test
	public void aGuideRowSaysWhichCheckKeptItOffTheLiveSeries()
	{
		final MovementRow thin = whip().withLiveRefusal(
			new MovementRow.LiveFacts(1_540_000L, 1_200_000L, 12L, "12 traded yesterday"));
		final String tip = MovementRowPanel.tooltip(thin, MovementWindow.D1, THEN_DAY);
		assertTrue(tip, tip.contains("<br>Guide price: 1,520,000 gp"
			+ "<br>Guide price - live not used: 12 traded yesterday"
			+ "<br>1d ago (07 Sep): 1,500,000 gp"));
		assertFalse("both ends are the guide's, so the window line says nothing new", tip.contains("traded average"));
		assertFalse(tip, tip.contains(MovementRowPanel.WINDOW_FELL_BACK_NOTE));
		assertEquals("Guide price - live not used: ", MovementRowPanel.LIVE_NOT_USED_PREFIX);
		assertEquals("the reason is the service's phrase, printed and not composed here",
			"Guide price - live not used: 12 traded yesterday", MovementRowPanel.liveRefusalLine(thin));
		assertEquals("", MovementRowPanel.liveRefusalLine(whip()));
		assertEquals("", MovementRowPanel.liveLine(thin));

		// T8: the switch off is the tooltip addenda K to S wrote, character for character, whatever the row carries.
		assertEquals(MovementRowPanel.tooltip(whip(), MovementWindow.D1, THEN_DAY),
			MovementRowPanel.tooltip(thin, MovementWindow.D1, THEN_DAY, LIVE_OFF));
		assertEquals(MovementRowPanel.tooltip(guideBow(), MovementWindow.D1, TRADED_DAY),
			MovementRowPanel.tooltip(liveBow(MovementRow.PriceSource.LIVE), MovementWindow.D1, TRADED_DAY, LIVE_OFF));
	}

	/**
	 * U3: the window line stamps the day the ROW compared against, not the guide baseline day the status handed
	 * down. On a live row those are different days for every hour before Jagex publishes the day's table - the live
	 * series counts back from its own snapshot's UTC date (U1) - and printing the guide's day over a traded figure
	 * is exactly how a Partyhat set came to read "+30.0 %" for "1d" on the live look of 2026-09-12: the figure
	 * spanned two days under a label that said one.
	 *
	 * <p>A window that fell back to the guide stamps the guide's day, because that is what it compared against; a
	 * row that recorded no day at all, a guide row and the switch off all keep the status's day, which is what every
	 * row printed before addendum U.
	 */
	@Test
	public void aLiveRowsWindowLineStampsTheDayTheRowComparedAgainst() throws Exception
	{
		final LocalDate liveDay = LocalDate.of(2026, 9, 11);
		final MovementRow row = liveBow(MovementRow.PriceSource.LIVE, liveDay);
		// TRADED_DAY here is the GUIDE's baseline day, a day behind the traded bucket this row really used.
		final String tip = MovementRowPanel.tooltip(row, MovementWindow.D1, TRADED_DAY, ViewOptions.DEFAULT);
		assertTrue(tip, tip.contains("<br>1d ago (11 Sep): 62,100,000 gp (traded average)"));
		assertFalse("the guide's day is not where this figure came from", tip.contains("10 Sep"));

		final String back = MovementRowPanel.tooltip(liveBow(MovementRow.PriceSource.GUIDE, TRADED_DAY),
			MovementWindow.D1, TRADED_DAY, ViewOptions.DEFAULT);
		assertTrue("a window that fell back compared against the guide's day, and says so",
			back.contains("<br>1d ago (10 Sep): 62,100,000 gp" + MovementRowPanel.WINDOW_FELL_BACK_NOTE));

		// The rule itself: the row's day when it has one, the status's day in every other state.
		assertEquals(liveDay, MovementRowPanel.stampedDay(row, MovementWindow.D1, TRADED_DAY, true));
		assertEquals("a window this row recorded nothing for", TRADED_DAY,
			MovementRowPanel.stampedDay(row, MovementWindow.D30, TRADED_DAY, true));
		assertEquals("the switch off, whatever the row carries (T8)", TRADED_DAY,
			MovementRowPanel.stampedDay(row, MovementWindow.D1, TRADED_DAY, false));
		assertEquals("a guide row carries no days at all", TRADED_DAY,
			MovementRowPanel.stampedDay(guideBow(), MovementWindow.D1, TRADED_DAY, true));
		assertNull("no day anywhere stamps a dash, never 1970",
			MovementRowPanel.stampedDay(row, null, null, true));
		assertTrue(MovementRowPanel.tooltip(row, null, null, ViewOptions.DEFAULT).contains("1d ago (-): "));

		// T8: with the switch off this row's tooltip is the guide-only one, character for character - the day
		// included, so a page recorded while the switch was on cannot bleed a traded day into a guide sidebar.
		assertEquals(MovementRowPanel.tooltip(guideBow(), MovementWindow.D1, TRADED_DAY),
			MovementRowPanel.tooltip(row, MovementWindow.D1, TRADED_DAY, LIVE_OFF));

		// ...and the built row prints what the pure helper says, so nothing on screen can disagree with it.
		onEdt(() -> assertEquals(tip,
			new MovementRowPanel(row, null, MovementWindow.D1, TRADED_DAY, ViewOptions.DEFAULT).tooltipHtml()));
	}

	/**
	 * T6: a LIVE row paints EXACTLY as a guide row with the same figures does - same name, same stack value, same
	 * working, same gp figure, same percentage, same rail, same size. No tag, no colour of its own: a live price is a
	 * price, and a badge on every liquid row would be a badge on most of the list. The tooltip is the one thing
	 * that differs.
	 */
	@Test
	public void aLiveRowPaintsExactlyLikeAGuideRow() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow live = liveBow(MovementRow.PriceSource.LIVE);
			final MovementRowPanel a = new MovementRowPanel(live, null, MovementWindow.D1, TRADED_DAY);
			final MovementRowPanel b = new MovementRowPanel(guideBow(), null, MovementWindow.D1, TRADED_DAY);
			assertEquals(b.nameText(), a.nameText());
			assertEquals(b.priceText(), a.priceText());
			assertEquals("line 3's working", workingLabel(b).getText(), workingLabel(a).getText());
			assertEquals(b.gpText(), a.gpText());
			assertEquals(b.changeText(), a.changeText());
			assertEquals(b.getPreferredSize(), a.getPreferredSize());
			assertEquals(MovementRowPanel.railColor(guideBow()), MovementRowPanel.railColor(live));
			assertEquals(MovementRowPanel.textChangeColor(guideBow()), MovementRowPanel.textChangeColor(live));
			assertFalse("no tag where the move goes", a.changeText().contains(MovementRowPanel.ALCH_TAG));
			assertFalse(MovementRowPanel.isAlch(live));
			assertNotEquals("...and the tooltip is the one place the series is named", b.tooltipHtml(), a.tooltipHtml());
			assertTrue(a.tooltipHtml(), a.tooltipHtml().contains("Live traded price: "));

			// The row the panel builds under the switch OFF carries the pre-T tooltip (T8).
			final MovementRowPanel off = new MovementRowPanel(live, null, MovementWindow.D1, TRADED_DAY, LIVE_OFF);
			assertEquals(b.tooltipHtml(), off.tooltipHtml());
			assertEquals(b.priceText(), off.priceText());
		});
	}

	// ---- the two links (K8) - unchanged by addendum N

	@Test
	public void theTwoUrlsAreTheGePageAndTheWikiHistoryPage()
	{
		assertEquals("https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=4151",
			MovementRowPanel.geUrl(4151));
		assertEquals("https://prices.runescape.wiki/osrs/item/4151", MovementRowPanel.wikiUrl(4151));
	}

	/**
	 * K8: two entries, the Grand Exchange first (where this row's guide price is actually published) and the
	 * wiki's history second. Both go through the row's browser, which is {@code LinkBrowser::browse} in the
	 * client and a recorder here. Addenda N and O both leave the menu alone.
	 */
	@Test
	public void rightClickOffersTheGrandExchangeAndTheWikiHistory() throws Exception
	{
		onEdt(() ->
		{
			final AtomicReference<String> opened = new AtomicReference<>();
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, opened::set);
			final JPopupMenu menu = p.getComponentPopupMenu();
			assertEquals("entries", 2, menu.getComponentCount());

			final JMenuItem ge = (JMenuItem) menu.getComponent(0);
			assertEquals(MovementRowPanel.OPEN_GE, ge.getText());
			ge.doClick(0);
			assertEquals("https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=4151", opened.get());

			final JMenuItem wiki = (JMenuItem) menu.getComponent(1);
			assertEquals(MovementRowPanel.OPEN_WIKI, wiki.getText());
			wiki.doClick(0);
			assertEquals("https://prices.runescape.wiki/osrs/item/4151", opened.get());
		});
	}

	// ---- ground, hover, mouse targets

	/**
	 * Addendum N section 2: every card is {@code DARKER_GRAY} on the {@code DARK_GRAY} ground - no zebra - and
	 * the hover is {@code DARKER_GRAY_HOVER}, RuneLite's paired hover for that card, quieter than the
	 * {@code MEDIUM_GRAY} the pre-N row used.
	 */
	@Test
	public void everyRowIsTheCardGreyAndHoversToTheCardHover() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, p.getBackground());
			enter(p, p);
			assertTrue(p.hovered());
			assertEquals(ColorScheme.DARKER_GRAY_HOVER_COLOR, p.getBackground());
			exit(p, p);
			assertFalse(p.hovered());
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, p.getBackground());

			// The pointer crossing onto the name (its own mouse target) hovers the row just the same.
			final JLabel name = label(p, "Abyssal whip");
			assertNotNull(name);
			enter(name, p);
			assertEquals(ColorScheme.DARKER_GRAY_HOVER_COLOR, p.getBackground());
			exit(name, p);
			assertEquals(ColorScheme.DARKER_GRAY_COLOR, p.getBackground());
		});
	}

	/**
	 * Playbook 7.5, which is a rule about REACH: a component that carries a tooltip becomes its own mouse target,
	 * so the hover text, the hover listener and the popup must reach every child of the card. A listener on the
	 * row alone would lose the hover the moment the pointer crossed onto the name, and a child with no tooltip of
	 * its own would answer with none over most of the 213 x 62 card.
	 *
	 * <p>Addendum AI took the TEXT away - a row says nothing on hover any more, and the silence is pinned by
	 * {@link #everyRowAndEveryChildIsSilentOpenOrShut} - but not the reach: the LISTENER and the POPUP still have
	 * to be on every child, because the click that opens the cell has to work wherever it lands and the two
	 * right-click entries have to open over the whole card. A listener on the row alone would leave the name -
	 * the biggest thing on it, and the obvious place to aim - dead to both.
	 */
	@Test
	public void everyChildCarriesTheHoverListenerAndThePopup() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertNotNull(p.getComponentPopupMenu());
			final List<JComponent> children = new ArrayList<>();
			collect(p, children);
			assertTrue("the face, the picture, the text block, three lines, two figure groups, six labels, the detail",
				children.size() >= 9);
			for (JComponent c : children)
			{
				assertTrue(c.getClass().getSimpleName() + " hover listener", c.getMouseListeners().length >= 1);
				assertTrue(c.getClass().getSimpleName() + " inherits the popup", c.getInheritsPopupMenu());
			}
		});
	}

	// ---- the picture

	@Test
	public void thePictureIsAddedToTheCellAndKept() throws Exception
	{
		onEdt(() ->
		{
			final AsyncBufferedImage image = new AsyncBufferedImage(null, 36, 32, BufferedImage.TYPE_INT_ARGB);
			final MovementRowPanel p = new MovementRowPanel(whip(), image, MovementWindow.D1, THEN_DAY);
			assertNotNull("addTo set the icon", ((JLabel) p.iconLabel()).getIcon());
			assertSame("the reference is kept on the row", image, p.icon());
		});
	}

	@Test
	public void noPictureIsNotAnError() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1);
			assertNull(((JLabel) p.iconLabel()).getIcon());
			assertNull(p.icon());
			// The cell keeps its size with nothing in it, so a row whose picture has not loaded yet is laid out
			// exactly like one whose picture has, and the text does not jump sideways when it arrives.
			assertEquals(new Dimension(MovementRowPanel.PICTURE_WIDTH, Constants.ITEM_SPRITE_HEIGHT),
				p.iconLabel().getPreferredSize());
		});
	}

	/**
	 * Addendum AR: where the ARTWORK stands between the rail and the name's first drawn pixel - measured on a
	 * painted row the way the user measured their screenshots, from "the edge of the green line" to "the start of
	 * the words", and not asserted from the constants that produce it.
	 *
	 * <p>The sprite here is drawn the way the client draws real item art: centred on x = 15 of its 36 px frame,
	 * which is where the art sat on three of the four rows of the user's screenshot. By that measurement a 3 px
	 * nudge puts it dead centre, and AR landed 3. <b>The user then looked in the client and said "shift it 2
	 * pixels to the left"</b> (AR3) and then <b>"shift it 1 pixel to the right"</b> (AR4), so the position pinned
	 * here is theirs: 1 px left of the measured midpoint.
	 * It is pinned rather than left loose because a picture that drifts a pixel is exactly what the user has
	 * now looked at three times. Half a pixel is allowed because the empty span can be an odd number of pixels
	 * wide, and a picture cannot stand on a half.
	 */
	@Test
	public void theArtworkStandsWhereTheUserPlacedIt() throws Exception
	{
		onEdt(() ->
		{
			final int art = 0xFF00FFFF;
			final AsyncBufferedImage sprite = new AsyncBufferedImage(null, Constants.ITEM_SPRITE_WIDTH,
				Constants.ITEM_SPRITE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
			// Columns 5..25: centred on 15, where the client centres item art in its frame.
			for (int x = 5; x <= 25; x++)
			{
				for (int y = 6; y <= 26; y++)
				{
					sprite.setRGB(x, y, art);
				}
			}
			final MovementRowPanel p = new MovementRowPanel(
				stack("Blighted super restore(4)", 164, 3_253L, 183L, 5.9d), sprite, MovementWindow.D1, THEN_DAY);
			layOut(p);
			final BufferedImage shot = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_ARGB);
			final java.awt.Graphics2D g = shot.createGraphics();
			try
			{
				p.paint(g);
			}
			finally
			{
				g.dispose();
			}

			// The artwork, in the row's own coordinates.
			final int artRow = SwingUtilities.convertPoint(p.iconLabel(), 0, p.iconLabel().getHeight() / 2, p).y;
			int artLeft = -1;
			int artRight = -1;
			for (int x = 0; x < shot.getWidth(); x++)
			{
				if (shot.getRGB(x, artRow) == art)
				{
					artLeft = artLeft < 0 ? x : artLeft;
					artRight = x;
				}
			}
			assertTrue("the artwork was painted", artLeft >= 0);

			// The name's first drawn pixel: the leftmost bright pixel anywhere in the name label's band.
			final JLabel name = nameLabel(p);
			final java.awt.Point at = SwingUtilities.convertPoint(name, 0, 0, p);
			int nameStart = Integer.MAX_VALUE;
			for (int y = at.y; y < at.y + name.getHeight(); y++)
			{
				for (int x = at.x; x < at.x + name.getWidth() && x < nameStart; x++)
				{
					final int c = shot.getRGB(x, y);
					if (((c >> 16) & 0xFF) > 150 && ((c >> 8) & 0xFF) > 150 && (c & 0xFF) > 150)
					{
						nameStart = x;
					}
				}
			}
			assertTrue("the name was painted", nameStart < Integer.MAX_VALUE);

			// The empty span runs from the first pixel after the rail to the last before the name.
			final double midpoint = (Widgets.EDGE_WIDTH + (nameStart - 1)) / 2.0;
			final double artCentre = (artLeft + artRight) / 2.0;
			assertEquals("the artwork's centre is 1 px left of the measured midpoint, where the user put it (AR4)"
					+ " (rail ends " + (Widgets.EDGE_WIDTH - 1) + ", art " + artLeft + ".." + artRight
					+ ", name starts " + nameStart + ")",
				midpoint - 1.0, artCentre, 0.5);
		});
	}

	/**
	 * Addendum AP: the picture cell is the client's own item sprite size, taken from {@link Constants} and not
	 * typed, and it is asserted against a sprite drawn to the edge so a narrower cell fails here rather than in
	 * the user's bank.
	 *
	 * <p>This was a real bug, and the belief behind it is worth writing down so it is not believed again. AN
	 * narrowed the cell to 32 on the idea that an item sprite is 32 px square and "the two px either side" were
	 * spare. A sprite is 36 x 32. The label CENTRES its icon, so 2 px went off each side - and the client paints a
	 * stackable item's quantity hard against the sprite's LEFT edge, so on the user's first live look every stack
	 * number in the list ("3290" on earth runes, "5000" on lizardman fangs, "32590" on water runes) had lost part
	 * of its first digit. None of the renders showed it, because the harness's stand-in sprite has no number.
	 */
	@Test
	public void thePictureCellIsExactlyTheClientsItemSprite() throws Exception
	{
		assertEquals("the client's own constant, not a typed number",
			Constants.ITEM_SPRITE_WIDTH, MovementRowPanel.ICON_WIDTH);
		assertEquals(Constants.ITEM_SPRITE_HEIGHT, MovementRowPanel.ICON_HEIGHT);
		assertEquals("which is 36 x 32, and NOT square", 36, MovementRowPanel.ICON_WIDTH);

		onEdt(() ->
		{
			// A sprite whose leftmost and rightmost columns are both painted, where the quantity sits.
			final AsyncBufferedImage sprite = new AsyncBufferedImage(null, Constants.ITEM_SPRITE_WIDTH,
				Constants.ITEM_SPRITE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
			for (int y = 0; y < Constants.ITEM_SPRITE_HEIGHT; y++)
			{
				sprite.setRGB(0, y, 0xFFFFFF00);
				sprite.setRGB(Constants.ITEM_SPRITE_WIDTH - 1, y, 0xFFFFFF00);
			}
			final MovementRowPanel p = new MovementRowPanel(whip(), sprite, MovementWindow.D1, THEN_DAY);
			final JLabel cell = (JLabel) p.iconLabel();
			cell.setSize(cell.getPreferredSize());
			final BufferedImage painted = new BufferedImage(cell.getWidth(), cell.getHeight(),
				BufferedImage.TYPE_INT_ARGB);
			final java.awt.Graphics2D g = painted.createGraphics();
			try
			{
				cell.paint(g);
			}
			finally
			{
				g.dispose();
			}
			final int middle = Constants.ITEM_SPRITE_HEIGHT / 2;
			assertEquals("the cell is the whole sprite plus the nudge (AR)",
				Constants.ITEM_SPRITE_WIDTH + MovementRowPanel.ART_NUDGE, cell.getWidth());
			assertEquals("the sprite's first column is inside the cell - a stack number's first digit is drawn",
				0xFFFFFF00, painted.getRGB(MovementRowPanel.ART_NUDGE, middle));
			assertEquals("...and so is its last - a whip's tip, a godsword's blade",
				0xFFFFFF00, painted.getRGB(cell.getWidth() - 1, middle));
			for (int x = 0; x < MovementRowPanel.ART_NUDGE; x++)
			{
				assertEquals("the nudge is air on the sprite's LEFT, not a cut off its right (column " + x + ")",
					0, painted.getRGB(x, middle) >>> 24);
			}
		});
	}

	/**
	 * The row keeps the {@link MovementRow} behind it, which is what the panel and the bridge ask it for - and
	 * that is now the whole of what it remembers: the ordering the page was built under is the PANEL's business
	 * (nothing on a row's face has varied with it since addendum O), and the baseline day it is given is not
	 * kept as a field but spent on the tooltip.
	 */
	@Test
	public void theRowKeepsTheRowItWasBuiltFrom() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow r = whip();
			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1);
			assertSame(r, p.row());
			assertSame(r, new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY).row());
			assertTrue("the day it was given is on the tooltip",
				new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY).tooltipHtml().contains("(07 Sep)"));
			assertTrue("...and without one, a dash",
				new MovementRowPanel(r, null, MovementWindow.D1).tooltipHtml().contains("(-)"));
		});
	}

	// ---- the fixes of the 2026-09-09 hardening pass

	/**
	 * B040: a potion keeps its DOSE when its name has to be cut. All four of "Super combat potion(1..4)"
	 * measure 164 px in the row's bold 14 against the 156 px block addendum N gave the name, and the plain cut
	 * made one string of them - four consecutive rows headed "Super combat potio...", with the only thing that
	 * told them apart in the part that was thrown away.
	 *
	 * <p>Q4 widened that block to 166, which is wide enough to hold those four whole - so a second, longer potion
	 * is walked beside them and its cut is asserted out loud. Without that leg the rule would be pinned only by
	 * fixtures the row no longer has to cut, and B040 could be undone without a single assertion noticing.
	 * "Divine ranging potion(3)" is the name on the picture the user approved the format from
	 * ({@code docs/handoff/lab/row-format-Q4-2026-09-20.png}), where it reads "Divine ranging poti...(3)".
	 */
	@Test
	public void theFourDosesOfAPotionKeepTheirDoseWhenTheNameIsCut() throws Exception
	{
		onEdt(() ->
		{
			final List<String> shown = new ArrayList<>();
			for (int dose = 1; dose <= 4; dose++)
			{
				final MovementRowPanel p = new MovementRowPanel(row("Super combat potion(" + dose + ")", 12_000L,
					11_800L, 200L, 1.7), null, MovementWindow.D1, THEN_DAY);
				assertTrue(p.nameText(), p.nameText().endsWith("(" + dose + ")"));
				assertFits("dose " + dose, nameLabel(p));
				assertTrue("the whole name is still on the tooltip", p.tooltipHtml().contains("Super combat potion(" + dose + ")"));
				shown.add(p.nameText());
			}
			assertEquals("four rows, four different headlines", 4, new java.util.HashSet<>(shown).size());

			// The same four doses of a name the 166 px block really has to cut, which is where the rule earns its
			// keep: each one is cut AND still says which dose it is.
			final List<String> cut = new ArrayList<>();
			for (int dose = 1; dose <= 4; dose++)
			{
				final MovementRowPanel p = new MovementRowPanel(row("Divine ranging potion(" + dose + ")",
					32_300L, 31_900L, 400L, 1.2), null, MovementWindow.D1, THEN_DAY);
				assertTrue("the fixture has to be cut, or this leg proves nothing: " + p.nameText(),
					p.nameText().contains(Widgets.ELLIPSIS));
				assertTrue(p.nameText(), p.nameText().endsWith("(" + dose + ")"));
				assertFits("divine dose " + dose, nameLabel(p));
				cut.add(p.nameText());
			}
			assertEquals("four cut rows, four different headlines", 4, new java.util.HashSet<>(cut).size());

			// A qualifier is NOT a dose (it carries a space before its bracket) and takes the plain cut.
			final MovementRowPanel baited = new MovementRowPanel(row("Karambwan vessel (baited)", 3_235L, 3_223L,
				12L, 0.4), null, MovementWindow.D1, THEN_DAY);
			assertTrue(baited.nameText(), baited.nameText().endsWith(Widgets.ELLIPSIS));
			assertFits("qualifier", nameLabel(baited));

			// A dosed name that fits is untouched, and so is one whose stem could not survive the cut.
			final MovementRowPanel fits = new MovementRowPanel(row("Stamina potion(4)", 12_000L, 11_800L, 200L, 1.7),
				null, MovementWindow.D1, THEN_DAY);
			assertEquals("Stamina potion(4)", fits.nameText());
			final String squeezed = Widgets.fitName(Widgets.sansBold(14), "Super combat potion(4)", 30);
			assertTrue(squeezed, squeezed.endsWith(Widgets.ELLIPSIS));
			assertFalse("a box too small for a stem falls back to the plain cut, never \"...(4)\"",
				squeezed.endsWith("(4)"));
		});
	}

	/**
	 * B045: the row's two figures - the 12 px gp change and the 14 px percentage - take the lifted red on a
	 * fall (5.05:1 against the card grey, where the ColorScheme constant measures 3.63:1 at those sizes). The
	 * 3 px RAIL keeps the darkened constant: it is a large mark that already clears its threshold, and the rail
	 * and the number must not disagree about the direction.
	 */
	@Test
	public void aFallsFiguresTakeTheLiftedRedAndTheRailKeepsTheConstant() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow fall = row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47);
			final MovementRowPanel p = new MovementRowPanel(fall, null, MovementWindow.D1, THEN_DAY);
			assertEquals(Widgets.MOVE_DOWN_TEXT, p.changeColor());
			assertEquals(Widgets.MOVE_DOWN_TEXT, MovementRowPanel.textChangeColor(fall));
			assertEquals("the rail is not text", ColorScheme.PROGRESS_ERROR_COLOR.darker(), p.railColor());
			assertEquals("the semantic colour is untouched", ColorScheme.PROGRESS_ERROR_COLOR,
				MovementRowPanel.changeColor(fall));

			final MovementRow rise = row("Abyssal whip", 1_520_000L, 1_500_000L, 20_000L, 1.33);
			assertEquals("a rise is green either way", ColorScheme.PROGRESS_COMPLETE_COLOR,
				MovementRowPanel.textChangeColor(rise));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR,
				MovementRowPanel.textChangeColor(row("Coal", 150L, 150L, 0L, 0.0)));
		});
	}

	/**
	 * B055: a row whose price did not move prints its percentage alone - "0" beside "0.0%" says it twice, and
	 * under Q4 it would say it FOUR times, once in each gp column and once in each of the two figures' place.
	 * The rule is the same on both of Q4's lines: the stack's move and the item's move are both nothing to print.
	 */
	@Test
	public void aRowThatDidNotMovePrintsNoGpFigure() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(row("Coal", 150L, 150L, 0L, 0.0), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("", p.gpText());
			assertEquals("...and the item's move is nothing to print either", "", itemGpLabel(p).getText());
			assertEquals("0.0%", p.changeText());
			assertEquals("no rail either: a quiet day is a quiet list", ColorScheme.DARKER_GRAY_COLOR, p.railColor());
			assertFits("line 2", line2(p));
		});
	}

	/**
	 * B047: both right-click entries set their own face. A menu item that is given none is drawn by the look
	 * and feel in the 16 px bitmap RuneScape default, and these two sit one gesture away from the sidebar's
	 * order menu, which sets {@code Widgets.sans(12)}.
	 */
	@Test
	public void bothRightClickEntriesSetTheirOwnFace() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			final JPopupMenu menu = p.getComponentPopupMenu();
			assertEquals(2, menu.getComponentCount());
			for (int i = 0; i < menu.getComponentCount(); i++)
			{
				final JMenuItem entry = (JMenuItem) menu.getComponent(i);
				assertNotNull(entry.getText(), entry.getFont());
				assertEquals(entry.getText(), Widgets.sans(12).getFamily(), entry.getFont().getFamily());
				assertEquals(entry.getText(), 12f, entry.getFont().getSize2D(), 0f);
			}
		});
	}

	// ---- addendum Q: the untradeable tag (Q5); the holding switch (Q6) went with addendum AO

	/**
	 * Q5: an untradeable stack is listed at its High Alchemy value, so line 2 prints that price exactly as it
	 * prints a guide price and puts a small grey "alch" tag where the two move figures would be - a WORD, not a
	 * pair of dashes, because a dash means "not known yet" everywhere else on this panel and this figure is
	 * never coming.
	 */
	@Test
	public void anUntradeableRowWearsTheGreyAlchTagInsteadOfTheMoveFigures() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow hood = alch("Graceful hood", 1, 20_000L);
			assertTrue(MovementRowPanel.isAlch(hood));
			assertFalse("a tradeable row is not tagged", MovementRowPanel.isAlch(whip()));

			final MovementRowPanel p = new MovementRowPanel(hood, null, MovementWindow.D1, THEN_DAY);
			assertEquals("the alch price, formatted as any other price", "20k", p.priceText());
			assertEquals(MovementRowPanel.ALCH_TAG, p.changeText());
			assertEquals("no gp figure beside the tag", "", p.gpText());
			assertEquals("the tag is quiet, not a move colour", ColorScheme.LIGHT_GRAY_COLOR, p.changeColor());
			assertEquals("11 px: a label on the price, not a figure", 11f, tag(p).getFont().getSize2D(), 0f);
			assertEquals("no rail: an untradeable stack never moved", ColorScheme.DARKER_GRAY_COLOR, p.railColor());
			assertFits("line 2", line2(p));
		});
	}

	/**
	 * Q5: its tooltip says the whole of it in one sentence over the holding, instead of stamping a guide price,
	 * a baseline day and a change that cannot exist - three lines that would all read "-".
	 */
	@Test
	public void anUntradeableRowsTooltipNamesTheAlchValueAndSaysItHasNoMovement() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow defender = alch("Avernic defender", 3, 42_000L);
			final String line = "Untradeable - High Alchemy value 42,000 gp; not in the movement figures";
			assertEquals(line, MovementRowPanel.untradeableLine(defender));

			final String tip = new MovementRowPanel(defender, null, MovementWindow.D30, THEN_DAY).tooltipHtml();
			assertEquals("<html><b>Avernic defender</b><br>" + line + "<br>Holding: 3 = 126,000 gp</html>", tip);
			assertFalse("no guide price is claimed", tip.contains("Guide price"));
			assertFalse("no baseline day is stamped", tip.contains("30d ago"));
			assertFalse("and no change", tip.contains("Change per item"));
		});
	}

	/*
	 * DELETED by addendum AO, with the config key they were written about:
	 *
	 *   theHoldingHelpersStillPrintTheStacksWorthAndTheStacksChange - the contract of MovementRowPanel's two
	 *   pure priceText / gpText pairs, which answered the unit figures by default and the stack's while
	 *   holdingOnRows was on;
	 *   theFaceShowsTheStackWhateverTheHoldingSwitchSays             - that a row built with the switch on and
	 *   one built with it off drew the same face, figure for figure;
	 *   theHoldingGpFigureFollowsTheSameNothingToPrintRule           - the three "nothing to print" edges read
	 *   through those helpers under the switch.
	 *
	 * The first two have no subject left in any form: the key is gone (AO1) and the helpers went with it, so
	 * there is neither a switch to set nor a method to ask. The equality the second one pinned - that a row's
	 * face does not vary with a ViewOptions - is still pinned, by aRowIsSilentAtEitherSettingOfTheHoverSwitch,
	 * which walks the same figures across the one switch that is left.
	 *
	 * The third's RULE is alive and is pinned on built rows, which is where it belongs now that no helper
	 * stands between the fixture and the label: no baseline by aRowWithoutMovementHasNoGpFigure, a flat price
	 * by aRowThatDidNotMovePrintsNoGpFigure, and no price at all by aRowWithoutAPriceShowsDashes. Re-pointing
	 * it here would have made a fourth copy of those three rather than covering anything new.
	 */

	/**
	 * The width case the holding reading used to own, read as what it now is: the widest stack figures the
	 * formatters can make are what line 2 prints on EVERY row, so the box they have to fit is the same 166 px
	 * box every other row's is.
	 *
	 * <p>It is worth keeping after addendum AO for a reason the switch never gave it: line 2's arithmetic MOVED.
	 * The word "Total" is gone from the fit budget and the air before the columns went back from 3 px to 6, so
	 * roughly 31 px changed hands on this line - and a walk that was comfortable under the old sums is the
	 * cheapest way to find out that it is still comfortable under the new ones. The fixtures are the two real
	 * items that made the widest figures in the calibration bank (a Twisted bow at 1.63b, Confliction gauntlets
	 * at -851k) beside the two absurd N6 rows, where a stack of 28,000 multiplies both figures at once.
	 */
	@Test
	public void theWidestStackFiguresFitTheSameBoxEveryOtherRowFits() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow bow = stack("Twisted bow", 1, 1_632_000_000L, -58_000_000L, -3.4);
			final MovementRow gauntlets = stack("Confliction gauntlets", 1, 64_300_000L, -851_000L, -1.3);
			assertEquals("1.63b", new MovementRowPanel(bow, null, MovementWindow.D180, THEN_DAY).priceText());
			assertEquals("-851k",
				new MovementRowPanel(gauntlets, null, MovementWindow.D180, THEN_DAY).gpText());

			final MovementRow huge = stack("Ancient ceremonial legs of the utterly absurd", 28_000,
				2_147_000_000L, 1_073_500_000L, 100.0);
			final MovementRow crash = stack("Karambwan vessel (baited)", 28_000, 2_147_000_000L,
				-999_900_000L, -100.0);
			for (MovementRow r : new MovementRow[]{bow, gauntlets, huge, crash, whip()})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D180, THEN_DAY);
				assertEquals(r.name(), new Dimension(213, 62), p.getPreferredSize());
				assertFits(r.name() + " name", nameLabel(p));
				assertFits(r.name() + " line 2", line2(p));
				assertFits(r.name() + " line 3", line3(p));
				// The 31 px AO handed back are the price's: it is fitted into the whole of what the two columns
				// leave, with nothing reserved beside it.
				assertTrue(r.name() + ": the stack value has all of the room the columns left",
					priceLabel(p).getPreferredSize().width
						<= MovementRowPanel.TEXT_WIDTH - figures(p).getPreferredSize().width);
			}
		});
	}

	// ---- addendum R: an untradeable stack valued at its tradeable parts (R4)

	/**
	 * R4: a parts row is a priced row with a real baseline and a real move, so it paints EXACTLY as a guide row
	 * with the same figures does - price, gp figure, percentage, colours, rail - and it wears no "alch" tag. The
	 * tag means "this figure is never coming", and on this row it came.
	 */
	@Test
	public void aPartsRowPaintsExactlyLikeAGuideRow() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow body = body("Crystal body", seeds());
			final MovementRow twin = body("Crystal body", null);
			assertTrue(MovementRowPanel.isParts(body));
			assertFalse("a parts row is not an alch row", MovementRowPanel.isAlch(body));
			assertFalse("and a guide row is neither", MovementRowPanel.isParts(twin));

			final MovementRowPanel p = new MovementRowPanel(body, null, MovementWindow.D1, THEN_DAY);
			final MovementRowPanel guide = new MovementRowPanel(twin, null, MovementWindow.D1, THEN_DAY);
			assertEquals("the parts sum, formatted as any other price (truncated, never rounded up)",
				"16.6m", p.priceText());
			assertEquals(guide.priceText(), p.priceText());
			assertEquals(guide.gpText(), p.gpText());
			assertEquals(guide.changeText(), p.changeText());
			assertEquals(guide.changeColor(), p.changeColor());
			assertEquals("the rail is the move's, like any other faller", guide.railColor(), p.railColor());
			assertEquals(ColorScheme.PROGRESS_ERROR_COLOR.darker(), p.railColor());
			assertNull("no alch tag anywhere on it", label(p, MovementRowPanel.ALCH_TAG));
			assertFits("line 2", line2(p));
		});
	}

	/**
	 * R4: the tooltip gains ONE line, under the price and above the baseline, naming the parts that price is a
	 * sum of - and keeps every other line, because a parts row has a baseline, a holding and a change like any
	 * other row. Remove that one line and it is a guide row's tooltip, word for word.
	 */
	@Test
	public void aPartsRowsTooltipNamesThePartsUnderThePrice() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow body = body("Crystal body", seeds());
			final String line = "Untradeable - valued as its parts: 3 x Crystal armour seed";
			assertEquals(line, MovementRowPanel.partsLine(body));

			final String tip = new MovementRowPanel(body, null, MovementWindow.D1, THEN_DAY).tooltipHtml();
			assertEquals("<html><b>Crystal body</b>"
				+ "<br>Guide price: 16,694,766 gp"
				+ "<br>" + line
				+ "<br>1d ago (07 Sep): 18,300,000 gp"
				+ "<br>Holding: 1 = 16,694,766 gp"
				+ "<br>Change per item: -1,605,234 gp (-8.7%)</html>", tip);
			assertEquals("one line added, and it is the parts line",
				MovementRowPanel.tooltip(body("Crystal body", null), MovementWindow.D1, THEN_DAY),
				tip.replace("<br>" + line, ""));
		});
	}

	/**
	 * R4's two rules for that line: a quantity of ONE is left off - "1 x Black mask" is a quantity nobody asked
	 * about - and several parts are joined with ", ". Every row that is not a parts row has no line at all, so a
	 * caller can ask blind and a guide row's tooltip is untouched by addendum R.
	 */
	@Test
	public void thePartsLineOmitsAQuantityOfOneAndJoinsTheRestWithCommas() throws Exception
	{
		onEdt(() ->
		{
			assertEquals("Untradeable - valued as its parts: Black mask",
				MovementRowPanel.partsLine(body("Slayer helmet (i)",
					Collections.singletonList(new BankItem.Part(8921, 1L, "Black mask")))));
			assertEquals("Untradeable - valued as its parts: 3 x Crystal armour seed, 250 x Mark of grace",
				MovementRowPanel.partsLine(body("Nonsense", Arrays.asList(
					new BankItem.Part(23956, 3L, "Crystal armour seed"),
					new BankItem.Part(11849, 250L, "Mark of grace")))));

			assertEquals("a guide row has no parts line", "", MovementRowPanel.partsLine(whip()));
			assertEquals("and neither has an alch row", "",
				MovementRowPanel.partsLine(alch("Graceful hood", 1, 20_000L)));
			assertEquals("so a guide row's tooltip is the one addendum N left",
				MovementRowPanel.tooltip(whip(), MovementWindow.D1, THEN_DAY),
				new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY).tooltipHtml());
		});
	}

	// ---- addendum Y: where a merged stack is (Y3)

	/**
	 * Y3: with "Include inventory and worn gear" on, a stack held in more than one place is ONE row with the
	 * quantities summed - so its tooltip gains one line under the Holding line naming the non-zero parts, in the
	 * order a player looks for them, and the three shapes that line can take.
	 */
	@Test
	public void aCarriedRowsTooltipNamesWhereTheStackIs() throws Exception
	{
		onEdt(() ->
		{
			// The whole split: the wording of Y3, to the character.
			assertEquals("3 in bank, 1 in inventory, 1 worn", MovementRowPanel.splitLine(carried(3, 1, 1)));
			// Two of the three: the zero part is DROPPED, not printed as "0 worn".
			assertEquals("2 in bank, 1 in inventory", MovementRowPanel.splitLine(carried(2, 1, 0)));
			assertEquals("3 in bank, 1 worn", MovementRowPanel.splitLine(carried(3, 0, 1)));
			// And a stack that is only worn, which is every piece of gear a player is standing in.
			assertEquals("1 worn", MovementRowPanel.splitLine(carried(0, 0, 1)));
			assertEquals("1 in inventory", MovementRowPanel.splitLine(carried(0, 1, 0)));
			// The figures are the exact ones the Holding line above them uses, so a trip's worth of scales reads
			// in the same shape as the line it qualifies.
			assertEquals("27,000 in bank, 1,000 in inventory", MovementRowPanel.splitLine(carried(27_000, 1_000, 0)));

			// In the tooltip: directly under the Holding line and above the change.
			final String tip = new MovementRowPanel(carried(3, 1, 1), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT).tooltipHtml();
			assertTrue(tip, tip.contains("<br>Holding: 5 = 7,600,000 gp<br>3 in bank, 1 in inventory, 1 worn"
				+ "<br>Change per item: +20,000 gp (+1.3%)"));
		});
	}

	/**
	 * Y3: the line is there exactly when it has something to add - never with the switch off, never on a row the
	 * bank holds whole, and never on a row built before addendum Y, which carries no split at all. So every
	 * tooltip but a merged stack's is the tooltip addenda K to X wrote, line for line.
	 */
	@Test
	public void theSplitLineIsAbsentWithTheSwitchOffAndOnABankOnlyRow() throws Exception
	{
		onEdt(() ->
		{
			// The switch off: the same row, the pre-Y tooltip.
			final ViewOptions off = ViewOptions.DEFAULT.withCountInventory(false);
			final String offTip = new MovementRowPanel(carried(3, 1, 1), null, MovementWindow.D1, THEN_DAY, off)
				.tooltipHtml();
			assertFalse(offTip, offTip.contains("in bank"));
			assertFalse(offTip, offTip.contains("worn"));
			assertTrue(offTip, offTip.contains("<br>Holding: 5 = 7,600,000 gp<br>Change per item: "));

			// The switch on, the whole stack in the bank: "3 in bank" under "Holding: 3" is the same fact twice.
			assertEquals("", MovementRowPanel.splitLine(carried(5, 0, 0)));
			final String bankOnly = new MovementRowPanel(carried(5, 0, 0), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT).tooltipHtml();
			assertFalse(bankOnly, bankOnly.contains("in bank"));

			// A row that knows nothing of the split - every row of addenda K to X - asks blind and gets "".
			assertEquals("", MovementRowPanel.splitLine(whip()));
			assertEquals(MovementRowPanel.tooltip(whip(), MovementWindow.D1, THEN_DAY),
				new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT).tooltipHtml());
		});
	}

	/**
	 * Y3: an untradeable stack is worn as often as any other - a slayer helmet, a graceful cape - and its tooltip
	 * has no price, no baseline and no change to hang the line under, only the holding. So the line goes there,
	 * last, and the alch sentence above it is untouched.
	 */
	@Test
	public void aWornAlchRowSaysWhereItIsToo() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow hood = new MovementRow(7, "Graceful hood", 1, false, 20_000L, null, null, null,
				20_000L, MovementRow.PriceSource.ALCH, null, null, null, null, 0, 0, 1);
			final String tip = new MovementRowPanel(hood, null, MovementWindow.D30, THEN_DAY, ViewOptions.DEFAULT)
				.tooltipHtml();
			assertEquals("<html><b>Graceful hood</b>"
				+ "<br>Untradeable - High Alchemy value 20,000 gp; not in the movement figures"
				+ "<br>Holding: 1 = 20,000 gp"
				+ "<br>1 worn</html>", tip);

			// The same stack in the bank, and with the switch off: the Q5 tooltip, word for word.
			assertEquals("<html><b>Graceful hood</b>"
				+ "<br>Untradeable - High Alchemy value 20,000 gp; not in the movement figures"
				+ "<br>Holding: 1 = 20,000 gp</html>",
				new MovementRowPanel(hood, null, MovementWindow.D30, THEN_DAY,
					ViewOptions.DEFAULT.withCountInventory(false)).tooltipHtml());
		});
	}

	// ---- addendum AI: the cell opens and shuts, and the description is inside it

	/**
	 * AI's resting state, as addendum AS leaves it: a fresh row is the card the renders are pictures of, and the
	 * block under its face is THERE - its label, a child of the row like any other - but EMPTY, and hidden.
	 *
	 * <p>This test used to pin the opposite: that the block was written with the row, "because its measured
	 * height is what the open row's height is computed from, and a height that only existed after the click
	 * would make the cell snap twice". The height is only ever measured AT an opening, and the opening now writes
	 * the text before it measures
	 * ({@link #theFirstOpeningWritesTheBlockAndOpensToTheHeightAWrittenBlockMeasures}), so nothing snaps - and the
	 * early text was the lag of every bank change. Its old last line, "the hidden block is already measurable",
	 * would still pass today on nothing but the air above an empty block, which is exactly why it is replaced
	 * rather than kept: the empty text is what catches a constructor that writes the block again.
	 */
	@Test
	public void aFreshRowIsRowHeightAndItsDetailIsHidden() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertFalse("a fresh row is shut", p.expanded());
			assertFalse("...and says nothing under its face", detail(p).isVisible());
			assertEquals(new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				p.getPreferredSize());
			assertEquals("AS: the block is there but EMPTY - nothing is written until the row is opened", "",
				detail(p).getText());
		});
	}

	/**
	 * The click of addendum AI, driven through the listener the row installed (see {@link #leftClick}): the
	 * detail appears, the CELL grows by what that detail measures - one cell, not a second widget under a row
	 * that stayed 62 px - and a second click puts it back to exactly {@link MovementRowPanel#ROW_HEIGHT}.
	 *
	 * <p>The row's width never moves: it is the sidebar's content width, and a cell that widened as it opened
	 * would push the column's scroll bar about.
	 */
	@Test
	public void aLeftClickShowsTheDetailAndGrowsTheCellAndASecondClickShutsIt() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			final int shut = p.getPreferredSize().height;

			leftClick(p);
			assertTrue("the row is open", p.expanded());
			assertTrue("the detail is showing", detail(p).isVisible());
			// Measured AFTER the click since addendum AS: the click is what writes the block, and before it the
			// empty label asks only for the air above it.
			final int block = detailHeight(p);
			assertEquals("the cell grew by the block's own height",
				MovementRowPanel.ROW_HEIGHT + block, p.getPreferredSize().height);
			assertTrue("...which is taller than it was", p.getPreferredSize().height > shut);
			assertEquals("the width is the sidebar's, open or shut", MovementRowPanel.ROW_WIDTH,
				p.getPreferredSize().width);

			leftClick(p);
			assertFalse(p.expanded());
			assertFalse(detail(p).isVisible());
			assertEquals("exactly the 62 px card again, which is what the renders are of",
				new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT), p.getPreferredSize());
		});
	}

	/**
	 * What the block says since addendum AK: the LABEL COLUMN
	 * {@link MovementRowPanel#detail(MovementRow, MovementWindow, java.time.LocalDate, ViewOptions, boolean)}
	 * writes, wrapped for the cell - and no longer the long hover's prose, which is what AI had put in here and
	 * what the user could not read ("This looks really hard to read").
	 *
	 * <p>Asserted as a RELATIONSHIP to the pure builder and never as a copied literal: the two must not be able
	 * to drift, and a literal here would have to be edited by every future addendum that touches a line of the
	 * block, which is exactly how a copy stops describing the original. WHAT that builder writes is pinned line
	 * by line under "addendum AK" below.
	 *
	 * <p>The {@code showName} argument is derived here the same way the row derives it - the name repeats only
	 * when the FACE had to cut it - so this assertion also pins that the row asks for the block AFTER
	 * {@code Widgets.setFittedName} has run, which is the one ordering AK depends on.
	 *
	 * <p>The wrapper is load-bearing and is AI's: a tooltip is laid out to whatever width it likes, and a block
	 * inside a 225 px sidebar has to be told one or Swing draws the whole description on one unreadable line.
	 */
	@Test
	public void theDetailIsTheLabelColumnWrappedForTheCell() throws Exception
	{
		onEdt(() ->
		{
			for (MovementRow r : new MovementRow[]{whip(), alch("Graceful hood", 4, 20_000L),
				body("Crystal body", seeds()), row("Mystery box", null, null, null, null), longDescription()})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);
				// AS: opened first, because the opening is what writes the block.
				leftClick(p);
				assertEquals(r.name() + ": the block, as the pure builder writes it and not re-written",
					MovementRowPanel.detail(r, MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT,
						!r.name().equals(p.nameText())),
					detail(p).getText());
				assertTrue(r.name() + ": wrapped to the cell", detail(p).getText()
					.startsWith("<html><div width=\"" + MovementRowPanel.INNER_WIDTH + "\">"));
				assertTrue(r.name(), detail(p).getText().endsWith("</div></html>"));
			}

			// The long hover's prose is what AK replaced, so none of its lines are in the cell any more - and the
			// figures they carried are all still there, in the column that replaced them.
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			leftClick(p);
			final String block = detail(p).getText();
			assertNotEquals("the cell is no longer the long description", p.tooltipHtml(), block);
			assertFalse(block, block.contains("Holding: 12 = 18,240,000 gp"));
			assertFalse(block, block.contains("Change per item:"));
			assertFalse(block, block.contains("Guide price:"));
			assertTrue(block, block.contains("18,240,000 gp"));
			assertTrue(block, block.contains("+20,000 each"));
		});
	}

	/**
	 * The open height is MEASURED, not a constant - the assertion a guessed number could not survive, and the
	 * reason it is re-read after addendum AK changed what the block says. An alch row's block is two labelled
	 * lines and one note; a live parts row carrying a carried split and a traded note is four lines and three
	 * notes, each of them long enough to wrap again inside a 200 px cell. A constant tall enough for the second
	 * leaves a hole under the first, and one sized for the first clips the second, so the two are built here and
	 * their open heights compared.
	 *
	 * <p>Neither height is named. Each row is asked what its OWN block measures, which is the only way to say
	 * "measured" on a machine whose Dialog face wraps these lines somewhere this test cannot know.
	 */
	@Test
	public void theOpenHeightIsTheRowsOwnMeasurementAndNotOneConstant() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel small = new MovementRowPanel(alch("Graceful hood", 4, 20_000L), null,
				MovementWindow.D1, THEN_DAY);
			final MovementRowPanel big = new MovementRowPanel(longDescription(), null, MovementWindow.D1,
				THEN_DAY);
			assertEquals("both start as the same card", small.getPreferredSize(), big.getPreferredSize());

			leftClick(small);
			leftClick(big);
			// Compared AFTER the clicks since addendum AS: the clicks write the two blocks, and two unwritten
			// blocks are the same empty string, which would let this guard pass on nothing.
			assertNotEquals("the two blocks have to say different things, or this proves nothing",
				detail(small).getText(), detail(big).getText());
			assertEquals(MovementRowPanel.ROW_HEIGHT + detailHeight(small), small.getPreferredSize().height);
			assertEquals(MovementRowPanel.ROW_HEIGHT + detailHeight(big), big.getPreferredSize().height);
			assertTrue("the long description is the taller block: " + detailHeight(small) + " vs "
				+ detailHeight(big), detailHeight(big) > detailHeight(small));
			assertTrue("...so the two open rows are not the same height",
				big.getPreferredSize().height > small.getPreferredSize().height);
		});
	}

	/**
	 * The click works wherever it LANDS. Every child is its own mouse target (playbook 7.5), so a reader who
	 * clicks the item's name - the biggest thing on the card, and the obvious place to aim - must flip the same
	 * row as a reader who clicks its padding.
	 */
	@Test
	public void aClickOnTheNameFlipsTheRowJustTheSame() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			final JLabel name = label(p, "Abyssal whip");
			assertNotNull(name);
			leftClick(name);
			assertTrue(p.expanded());
			assertTrue("the whole cell opened, not the label the click landed on", detail(p).isVisible());
			assertEquals(MovementRowPanel.ROW_HEIGHT + detailHeight(p), p.getPreferredSize().height);

			leftClick(name);
			assertFalse(p.expanded());
			assertEquals(new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				p.getPreferredSize());
		});
	}

	/**
	 * The right button is the popup's, and only the popup's (K8). A right-click that also toggled would change
	 * the hover under a menu the reader opened for something else - and on the platforms where the trigger is the
	 * PRESS, the menu and the flipped hover would fight for the same corner of the screen.
	 *
	 * <p>Both guards in the handler are asked: the button, and the popup-trigger flag - the second because a
	 * Control-click on macOS is a left button carrying it.
	 *
	 * <p>Since AI the cost of getting it wrong is larger than a swapped hover: a right-click that also toggled
	 * would move every row under the menu it just opened.
	 */
	@Test
	public void aRightClickTogglesNothing() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			final Dimension shut = p.getPreferredSize();

			click(p, MouseEvent.BUTTON3, false);
			assertFalse("the right button is the popup's", p.expanded());

			click(p, MouseEvent.BUTTON3, true);
			assertFalse(p.expanded());

			click(p, MouseEvent.BUTTON1, true);
			assertFalse("a left button carrying the popup trigger is still a popup", p.expanded());

			assertFalse("nothing opened", detail(p).isVisible());
			assertEquals("and the cell never moved", shut, p.getPreferredSize());
		});
	}

	/** K8's two entries are untouched by the toggle: the menu opens, and both still browse, on an expanded row. */
	@Test
	public void thePopupStillWorksAfterAToggle() throws Exception
	{
		onEdt(() ->
		{
			final AtomicReference<String> opened = new AtomicReference<>();
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, opened::set);
			leftClick(p);
			assertTrue(p.expanded());

			final JPopupMenu menu = p.getComponentPopupMenu();
			assertEquals("entries", 2, menu.getComponentCount());
			((JMenuItem) menu.getComponent(0)).doClick(0);
			assertEquals("https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=4151", opened.get());
			((JMenuItem) menu.getComponent(1)).doClick(0);
			assertEquals("https://prices.runescape.wiki/osrs/item/4151", opened.get());
		});
	}

	/**
	 * The seam of addendum AG, which AI keeps exactly as it was: the state lives OUTSIDE the row, is read as the
	 * row is built and written on every toggle. That is what makes it survive the rebuild - {@code addPage}
	 * throws every row away and builds the page again on every publish (a refresh, a bank opening, the
	 * half-hourly recheck), so a row that held the flag itself would fold up under a reader who had just opened
	 * it, with nothing on screen to explain why. Under AI that reader is watching a cell CLOSE, which is a good
	 * deal more startling than a hover changing length.
	 */
	@Test
	public void theExpansionSeamIsReadAtBuildTimeAndWrittenOnEveryToggle() throws Exception
	{
		onEdt(() ->
		{
			final RecordingExpansion seam = new RecordingExpansion();
			seam.setExpanded(4151, true);
			seam.writes = 0;

			final MovementRowPanel opened = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, seam);
			assertTrue("a row whose id is already expanded is open", opened.expanded());
			assertEquals("reading the seam writes nothing to it", 0, seam.writes);

			leftClick(opened);
			assertFalse("the click was recorded outside the row", seam.isExpanded(4151));
			assertEquals(1, seam.writes);
			assertFalse("...and the cell shut", detail(opened).isVisible());
			assertEquals(new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				opened.getPreferredSize());

			leftClick(opened);
			assertTrue(seam.isExpanded(4151));
			assertEquals(2, seam.writes);
			assertTrue(detail(opened).isVisible());

			// The whole point: the next publish builds a NEW row from the same seam, and it stands where the
			// reader left it - keyed by item id, so a re-sort or a change of price band does not lose it either.
			final MovementRowPanel rebuilt = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, seam);
			assertTrue(rebuilt.expanded());

			// ...and one row's state is one ITEM's: another id in the same seam is untouched by all of that.
			final MovementRowPanel other = new MovementRowPanel(alch("Graceful hood", 4, 20_000L), null,
				MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, seam);
			assertFalse(other.expanded());
			assertFalse(detail(other).isVisible());
		});
	}

	/**
	 * The other half of that seam, and the half AI made VISIBLE: a row the reader had opened before the publish
	 * has to come back DRAWN open, not merely reporting that it is.
	 *
	 * <p>Before AI the two could not come apart - the state chose which of two texts the row was handed, and it
	 * was handed one as it was built. AI made the state decide a child's visibility and the row's own height,
	 * which are seeded once and then only changed by a click, so a row built from an already-open seam can
	 * report {@code expanded()} while standing 62 px tall with its description hidden. A reader would then find
	 * their open rows shut by an invisible refresh, and their next click would shut them again - two clicks to
	 * reopen one row.
	 */
	@Test
	public void aRowBuiltFromAnAlreadyOpenSeamIsDrawnOpen() throws Exception
	{
		onEdt(() ->
		{
			final RecordingExpansion seam = new RecordingExpansion();
			seam.setExpanded(4151, true);

			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, seam);
			assertTrue("the state is read at build time", p.expanded());
			assertTrue("...and the cell it decides is open", detail(p).isVisible());
			assertEquals("a rebuilt open row stands at its open height",
				MovementRowPanel.ROW_HEIGHT + detailHeight(p), p.getPreferredSize().height);

			// ...so ONE click shuts it, which is what the reader expects of the row in front of them.
			leftClick(p);
			assertFalse(p.expanded());
			assertFalse(detail(p).isVisible());
			assertEquals(new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				p.getPreferredSize());
		});
	}

	/**
	 * No seam, no shared state: a row built with null keeps one boolean of its own, which is what every caller
	 * that never rebuilds a page wants (the bridge, the renderer and every older constructor). It starts shut
	 * and toggles for as long as it exists, and two rows of the SAME item are strangers.
	 */
	@Test
	public void aRowWithNoExpansionKeepsItsOwnState() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel a = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, (MovementRowPanel.Expansion) null);
			final MovementRowPanel b = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, (MovementRowPanel.Expansion) null);
			assertFalse("both start shut", a.expanded());
			assertFalse(b.expanded());

			leftClick(a);
			assertTrue(a.expanded());
			assertTrue(detail(a).isVisible());
			assertFalse("the other row of the same item is a stranger", b.expanded());
			assertFalse(detail(b).isVisible());
			assertEquals("...and is still the 62 px card",
				new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT), b.getPreferredSize());

			leftClick(a);
			assertFalse(a.expanded());
			assertFalse(detail(a).isVisible());
		});
	}

	// ---- addendum AI: the row is silent, open or shut

	/**
	 * AI: a row says NOTHING on hover - null, not "", on the row and on every one of its children, whether it is
	 * open or shut. The description is the block the click opens, so a tooltip would be a second copy of it, and a
	 * hover over every row the pointer crossed is what the reader asked to be rid of. There is no switch to read
	 * any more: silence is what a row IS, which is why nothing here builds a fixture to turn it on.
	 *
	 * <p>Null and not "" because Swing tells the two apart: {@code ToolTipManager} registers a component the moment
	 * it is given any non-null text and opens an empty box for "", which is a hover the reader can neither read nor
	 * dismiss. Only null takes the component back off the manager.
	 *
	 * <p>Every child for the reason every hover on this card ever reached every child (playbook 7.5): each one is
	 * its own mouse target, so a row that cleared only its own text would go on answering over the icon and the
	 * five labels - most of the 213 x 62 card - and, once it is open, over the description as well.
	 */
	@Test
	public void everyRowAndEveryChildIsSilentOpenOrShut() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertNull("the row", p.getToolTipText());

			final List<JComponent> children = new ArrayList<>();
			collect(p, children);
			assertTrue("the face, the picture, the text block, three lines, two figure groups, six labels, the detail",
				children.size() >= 9);
			for (JComponent c : children)
			{
				assertNull(c.getClass().getSimpleName() + " must be silent too", c.getToolTipText());
			}

			// ...and opening the cell hands nobody a hover on the way past.
			leftClick(p);
			assertTrue(p.expanded());
			assertNull("the open row", p.getToolTipText());
			final List<JComponent> afterwards = new ArrayList<>();
			collect(p, afterwards);
			for (JComponent c : afterwards)
			{
				assertNull(c.getClass().getSimpleName() + " must be silent while open", c.getToolTipText());
			}
		});
	}

	/**
	 * The children that would otherwise still answer, and the reason the row has to CLEAR rather than merely
	 * decline to set: {@code Widgets.setFittedName} and {@code Widgets.setFitted} leave the WHOLE text on every
	 * label they fit, as that label's own tooltip - which is how a cut name was readable before addendum AG - so
	 * the name label and the price label both arrive at the end of the constructor already carrying one.
	 *
	 * <p>A row that stopped walking its children would therefore leave those two behind, and the biggest thing on
	 * the card would go on opening a hover in a sidebar that is meant to have none. So the fixtures are built to
	 * be cut: a name too long for any metrics, and then the widest row the formatters can make, where the price
	 * prints a stack of 28,000 at 2.1b and is squeezed by the widest gp figure and percentage there are. That
	 * second fixture used to be read under {@code holdingOnRows}; since addendum AO deleted the key, line 2
	 * prints the stack on every row and the fixture alone is what makes it wide.
	 *
	 * <p>Whether this machine's Dialog face cuts one, both or neither is not asserted (the file's rule: no literal
	 * pixel widths), and it does not need to be - the leftover is UNCONDITIONAL
	 * ({@code WidgetsTest.everyFittedLabelCarriesItsWholeTextAsATooltipCutOrNot}), so both labels arrive carrying
	 * one either way. That there WAS something to clear is proved against twin labels put through the same call
	 * rather than read off the row, which has already cleared them by the time a test can look.
	 */
	@Test
	public void theFittedLabelsLoseTheirOwnLeftoverTooltipsToo() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow long0 = row("Twisted ancestral robe bottom", 1_000L, 900L, 100L, 11.1);
			final MovementRowPanel p = new MovementRowPanel(long0, null, MovementWindow.D1, THEN_DAY);
			assertNotEquals("the face had to cut it, so setFittedName left the whole name on the label",
				long0.name(), p.nameText());
			assertNull("...and the silent row cleared it", nameLabel(p).getToolTipText());
			assertNull("the price label is fitted the same way", label(p, p.priceText()).getToolTipText());
			assertLeftoverWasThere("the name", Widgets.sansBold(14), long0.name(), MovementRowPanel.TEXT_WIDTH);
			// What line 2 opens with, said in the test's own terms now that no helper answers it: the stack's
			// worth in the game's stack style, which is exactly what the row hands the fitter.
			assertLeftoverWasThere("the price", Widgets.sans(13),
				MovementMath.formatGp(long0.holdingValue()), MovementRowPanel.TEXT_WIDTH);

			final MovementRow crowded = stack("Twisted ancestral robe bottom of the utterly absurd", 28_000,
				2_147_000_000L, -999_900_000L, -100.0);
			final MovementRowPanel squeezed = new MovementRowPanel(crowded, null, MovementWindow.D180, THEN_DAY);
			assertNotEquals("the name is cut at any metrics", crowded.name(), squeezed.nameText());
			assertNull("the row", squeezed.getToolTipText());
			assertNull("the name label", nameLabel(squeezed).getToolTipText());
			assertNull("the price label", label(squeezed, squeezed.priceText()).getToolTipText());
			assertLeftoverWasThere("the squeezed name", Widgets.sansBold(14), crowded.name(),
				MovementRowPanel.TEXT_WIDTH);
			assertLeftoverWasThere("the stack price", Widgets.sans(13),
				MovementMath.formatGp(crowded.holdingValue()), MovementRowPanel.TEXT_WIDTH);

			// And the clearing survives the click: opening the cell must not put a leftover back on either label.
			leftClick(squeezed);
			assertTrue(squeezed.expanded());
			assertNull("the name label, open", nameLabel(squeezed).getToolTipText());
			assertNull("the price label, open", label(squeezed, squeezed.priceText()).getToolTipText());
			assertNull("the description itself", detail(squeezed).getToolTipText());
		});
	}

	/**
	 * That a label of this text, in this face, in this much room, comes back from {@code Widgets.setFitted}
	 * carrying a tooltip - the leftover the silent row above has to clear. Measured on a twin rather than read
	 * off the row, because the row has already cleared it by the time a test can look.
	 *
	 * <p>{@code setFitted} stands in for the name's {@code setFittedName} as well: the two are one private method
	 * with a different cutter handed to it, and the {@code setToolTipText(full)} is in that shared method.
	 */
	private static void assertLeftoverWasThere(String what, Font face, String text, int width)
	{
		final JLabel twin = Widgets.label("", face, Color.WHITE);
		Widgets.setFitted(twin, text, width);
		assertEquals(what + ": the fitter leaves the whole text on the label", text, twin.getToolTipText());
	}

	// ---- addendum AJ: the hover switch is back, and a row is outside its reach

	/**
	 * The view switches with AJ's restored "Show hover text" ON. Nothing a row builds reads it - which is the
	 * whole point of the two tests below - so the fixture exists to make the ON leg a real setting rather than an
	 * absent one, and both tests say so out loud before they use it.
	 */
	private static final ViewOptions HOVERS_ON = ViewOptions.DEFAULT.withShowHoverText(true);

	/**
	 * AJ restored the switch addendum AI had deleted, and narrowed it: it governs the hero card's exact-gp hover
	 * (AF) and every CONTROL tooltip - the sort button and its menu, the chips, the band button, Refresh, the
	 * update line, the preset row and the gear's own items - and it does NOT reach an item row.
	 *
	 * <p>That is the fact a reader of this file would otherwise get wrong, because {@link ViewOptions} carries a
	 * switch named after hover text and a row is built from a {@link ViewOptions}. Since AI a row carries
	 * no tooltip whatever the options say: its description is the block the cell opens on a click
	 * ({@link #theDetailIsTheLabelColumnWrappedForTheCell}), so the switch has nothing here to silence and
	 * nothing to hand back. Wiring it into a row would be a regression at BOTH settings - ON it would put back the
	 * hover over every row the pointer crossed that the user asked to be rid of, and OFF it would remove a text
	 * that is not there.
	 *
	 * <p>The fixture is a row built to be CUT, because a leftover is where a switch that had crept back in would
	 * show first: {@code Widgets.setFittedName} and {@code Widgets.setFitted} hang the whole text on the name and
	 * the price labels as their own tooltip, and a constructor that skipped its clearing under one setting would
	 * leave the biggest thing on the card speaking. Both labels are read by name as well as by the sweep over the
	 * children, and the sweep is repeated with the cell OPEN, where the description is a child too.
	 */
	@Test
	public void aRowIsSilentAtEitherSettingOfTheHoverSwitch() throws Exception
	{
		onEdt(() ->
		{
			assertFalse("the switch's own default is OFF (AH, restored by AJ)", ViewOptions.DEFAULT.showHoverText());
			assertTrue("...and the ON fixture really is on, or this test has one leg", HOVERS_ON.showHoverText());

			final MovementRow long0 = row("Twisted ancestral robe bottom", 1_000L, 900L, 100L, 11.1);
			for (ViewOptions view : new ViewOptions[]{HOVERS_ON, ViewOptions.DEFAULT})
			{
				final String state = view.showHoverText() ? "hovers on" : "hovers off";
				final MovementRowPanel p = new MovementRowPanel(long0, null, MovementWindow.D1, THEN_DAY, view);
				assertNotEquals(state + ": the face had to cut the name, so the fitter left the whole of it behind",
					long0.name(), p.nameText());
				assertNull(state + ": the row", p.getToolTipText());
				assertNull(state + ": the name label", nameLabel(p).getToolTipText());
				assertNull(state + ": the price label", label(p, p.priceText()).getToolTipText());

				final List<JComponent> children = new ArrayList<>();
				collect(p, children);
				assertTrue(state + ": the face, the picture, the text block, three lines, two figure groups, six labels,"
					+ " the detail", children.size() >= 9);
				for (JComponent c : children)
				{
					assertNull(state + ": " + c.getClass().getSimpleName(), c.getToolTipText());
				}

				// ...and an OPEN row is silent too: its description is on the screen, not under the pointer.
				leftClick(p);
				assertTrue(state + ": the cell opened", p.expanded());
				assertNull(state + ": the open row", p.getToolTipText());
				final List<JComponent> afterwards = new ArrayList<>();
				collect(p, afterwards);
				for (JComponent c : afterwards)
				{
					assertNull(state + ": " + c.getClass().getSimpleName() + ", open", c.getToolTipText());
				}
			}

			// There WAS something to clear at both settings - measured on twins put through the same call, because
			// the row has cleared its own by the time a test can look at it.
			assertLeftoverWasThere("the name", Widgets.sansBold(14), long0.name(), MovementRowPanel.TEXT_WIDTH);
			assertLeftoverWasThere("the price", Widgets.sans(13),
				MovementMath.formatGp(long0.holdingValue()), MovementRowPanel.TEXT_WIDTH);

			// And the switch changes nothing ELSE about a row either: same face, same card, same description.
			// Since addendum AO this is the WHOLE of "a row's face does not vary with a ViewOptions" - the
			// deleted holding switch had its own equality test, and this walk inherited the job.
			final MovementRowPanel on = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY, HOVERS_ON);
			final MovementRowPanel off = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT);
			assertEquals(off.nameText(), on.nameText());
			assertEquals(off.priceText(), on.priceText());
			assertEquals(off.gpText(), on.gpText());
			assertEquals(off.changeText(), on.changeText());
			assertEquals("line 3's working", workingLabel(off).getText(), workingLabel(on).getText());
			assertEquals("line 3's gp", itemGpLabel(off).getText(), itemGpLabel(on).getText());
			assertEquals(off.railColor(), on.railColor());
			assertEquals(off.getPreferredSize(), on.getPreferredSize());
			assertEquals("the description is built either way", off.tooltipHtml(), on.tooltipHtml());
			// Both opened first since addendum AS: the opening writes the block, and two unwritten blocks are the
			// same empty string whatever the switch says - so the text compared is asserted to be a written one.
			leftClick(off);
			leftClick(on);
			assertTrue("the block was written", detail(off).getText().startsWith("<html>"));
			assertEquals("...and reaches the cell either way", detail(off).getText(), detail(on).getText());
			assertEquals("and opens both cells to the same height", off.getPreferredSize(), on.getPreferredSize());
		});
	}

	/**
	 * The other half of AJ's reach, and the regression it is here to stop: a row OPENS at either setting of the
	 * switch. AH - the switch as it first shipped - made a click do nothing while the hovers were off, because
	 * back then the click's only job was to lengthen a hover, and that is exactly what sent the user to AI
	 * ("when we click an item it does not expand because our hover text button is off"). AI moved the description
	 * into the cell, so the click is no longer about hover text at all, and AJ must not carry the old coupling
	 * back in with the switch.
	 *
	 * <p>The seam is read as well as the cell, because a row that opened on screen while writing nothing would
	 * fold itself up again on the next publish - which under the switch's OFF setting would be a reader's whole
	 * experience of it.
	 */
	@Test
	public void theCellOpensAndShutsAtEitherSettingOfTheHoverSwitch() throws Exception
	{
		onEdt(() ->
		{
			for (ViewOptions view : new ViewOptions[]{HOVERS_ON, ViewOptions.DEFAULT})
			{
				final String state = view.showHoverText() ? "hovers on" : "hovers off";
				final RecordingExpansion seam = new RecordingExpansion();
				final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY, view,
					seam);
				assertFalse(state + ": a fresh row is shut", p.expanded());
				assertFalse(state + ": and says nothing under its face", detail(p).isVisible());
				assertEquals(state + ": building only reads the seam", 0, seam.writes);

				leftClick(p);
				assertTrue(state + ": the click opened it", p.expanded());
				assertTrue(state + ": the description is showing", detail(p).isVisible());
				assertEquals(state + ": the cell grew by the block's own height",
					MovementRowPanel.ROW_HEIGHT + detailHeight(p), p.getPreferredSize().height);
				assertTrue(state + ": ...and the seam knows, so the next publish brings it back open",
					seam.isExpanded(4151));

				leftClick(p);
				assertFalse(state + ": and a second click shuts it", p.expanded());
				assertFalse(state + ": the description is hidden again", detail(p).isVisible());
				assertEquals(state + ": exactly the 62 px card, which is what the four renders are of",
					new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT), p.getPreferredSize());
				assertFalse(state, seam.isExpanded(4151));
				assertEquals(state + ": one write per click, whatever the switch says", 2, seam.writes);
			}
		});
	}

	// ---- addendum AK: the open cell is a label column

	/**
	 * AK's shape, and the order it prints in: four labelled lines, the value of each beside its own label, ended
	 * by {@code </table>}. The user could not read the prose AI put in this cell - "Live traded price: 4,618 gp
	 * (buy 4,796, sell 4,440; 6,565 traded yesterday)" is seventy-five characters in a cell about thirty-two
	 * wide - so the eye now runs down one edge instead of hunting along a sentence.
	 *
	 * <p>Asserted in ORDER rather than as four {@code contains} calls: a column whose lines arrived in any order
	 * would satisfy the weaker assertion, and the order is the whole point of a column.
	 */
	@Test
	public void theOpenCellIsFourLabelledLinesInOrder()
	{
		final String html = block(whip(), MovementWindow.D1);
		assertInOrder(html, "<table",
			MovementRowPanel.L_NOW, "1,520,000 gp each",
			MovementRowPanel.L_WAS, "1,500,000 gp", "(07 Sep)",
			MovementRowPanel.L_HAVE, "12&nbsp; =&nbsp; 18,240,000 gp",
			MovementRowPanel.L_CHANGE + "1d", "+20,000 each", "+1.3%",
			"</table>");
		// The labels are the block's, pinned so a rename has to be deliberate.
		assertEquals("Worth now", MovementRowPanel.L_NOW);
		assertEquals("Was", MovementRowPanel.L_WAS);
		assertEquals("You have", MovementRowPanel.L_HAVE);
		assertEquals("Change ", MovementRowPanel.L_CHANGE);
		// A table, not padded spaces: these faces are proportional, so only a column lines the figures up.
		assertTrue(html, html.contains("<table cellpadding=0 cellspacing=0>"));
		// The one coloured figure is the change, and it takes the FULL colour - it has nothing beside it here.
		assertTrue(html, html.contains("<font color='#"
			+ String.format("%06X", MovementRowPanel.textChangeColor(whip()).getRGB() & 0xFFFFFF)
			+ "'>+20,000 each&nbsp; +1.3%</font>"));
	}

	/**
	 * The user's own correction to AK: "i find the change confusing, make it show how many days (1d, 7d, 30d,
	 * 90d, 180d) so itll be Change 1d or Change 7d for example". A line reading "Change" beside a figure says
	 * nothing about whether it is a day's move or half a year's, and the lit chip is at the top of the sidebar
	 * rather than beside the number.
	 *
	 * <p>Every window is walked, and each is checked to claim ONLY its own label - a builder that hard-coded
	 * "1d" would pass a test that looked at one window, and that is exactly the bug the correction was about.
	 */
	@Test
	public void theChangeLineNamesTheWindowItIsMeasuring()
	{
		for (MovementWindow w : MovementWindow.values())
		{
			final String html = block(whip(), w);
			assertTrue(html, html.contains(MovementRowPanel.L_CHANGE + w.label()));
			for (MovementWindow other : MovementWindow.values())
			{
				if (other != w)
				{
					assertFalse(w.label() + " also claims " + other.label() + ": " + html,
						html.contains(MovementRowPanel.L_CHANGE + other.label()));
				}
			}
		}
		assertTrue("a null window reads as the default, like every other reader of one",
			MovementRowPanel.detail(whip(), null, THEN_DAY, ViewOptions.DEFAULT, false)
				.contains(MovementRowPanel.L_CHANGE + MovementWindow.DEFAULT.label()));
	}

	/**
	 * A row with no price dashes every value that needs one and invents nothing: a "0" here would claim the
	 * stack is worthless rather than unpriced, which is the rule the face has followed since addendum N and the
	 * block has to follow too. The quantity is still printed, because that figure IS known.
	 */
	@Test
	public void aRowWithNoPriceDashesEveryFigureAndGuessesNoZero()
	{
		final MovementRow none = row("Mystery box", null, null, null, null);
		final String html = MovementRowPanel.detail(none, MovementWindow.D7, null, ViewOptions.DEFAULT, false);
		assertInOrder(html,
			MovementRowPanel.L_NOW, MovementMath.DASH,
			MovementRowPanel.L_WAS, MovementMath.DASH, "(-)",
			MovementRowPanel.L_HAVE, "5&nbsp; =&nbsp; " + MovementMath.DASH,
			MovementRowPanel.L_CHANGE + "7d", MovementMath.DASH,
			"</table>");
		assertFalse("an absent price is never a zero", html.contains("0 gp"));
		assertFalse("and an absent move is never a +0", html.contains("+0"));
		assertFalse("a dash carries no unit", html.contains("- gp"));
		assertFalse("nothing is coloured: there is no direction to say", html.contains("<font"));
	}

	/**
	 * Q5 in the new block: an untradeable stack listed at its High Alchemy value prints what it is worth and
	 * what you have, and NEITHER of the two lines it cannot know - a constant is not a series, so there is no
	 * baseline day to stamp and no change to print. {@link MovementRowPanel#ALCH_NOTE} says the whole of it
	 * under the table.
	 */
	@Test
	public void anAlchRowPrintsWorthNowAndYouHaveAndNeitherWasNorChange()
	{
		final MovementRow hood = alch("Graceful hood", 4, 20_000L);
		final String html = block(hood, MovementWindow.D30);
		assertInOrder(html, MovementRowPanel.L_NOW, "20,000 gp each",
			MovementRowPanel.L_HAVE, "4&nbsp; =&nbsp; 80,000 gp", "</table>", MovementRowPanel.ALCH_NOTE);
		assertFalse("an alch row has no baseline to show", html.contains(MovementRowPanel.L_WAS));
		assertFalse("...and no change either", html.contains(MovementRowPanel.L_CHANGE));
		assertFalse("so it stamps no day", html.contains("(07 Sep)"));
		assertEquals("Untradeable - High Alchemy value, not in the movement figures", MovementRowPanel.ALCH_NOTE);
		assertFalse("the note does not repeat the value that is two lines above it",
			MovementRowPanel.ALCH_NOTE.contains("20,000"));
	}

	/**
	 * R4 in the new block: a stack valued at its tradeable PARTS has a real baseline and a real move, so it
	 * prints all four lines exactly as a guide row does, and gains the one note naming what the price is a sum
	 * of. Without that note a Crystal body simply reads 16.6m and nothing says why it is not its 900k alch value.
	 */
	@Test
	public void aPartsRowPrintsAllFourLinesAndNamesItsParts()
	{
		final MovementRow crystal = body("Crystal body", seeds());
		final String html = block(crystal, MovementWindow.D1);
		assertInOrder(html, MovementRowPanel.L_NOW, "16,694,766 gp each",
			MovementRowPanel.L_WAS, "18,300,000 gp",
			MovementRowPanel.L_HAVE, "1&nbsp; =&nbsp; 16,694,766 gp",
			MovementRowPanel.L_CHANGE + "1d", "-1,605,234 each", "-8.7%",
			"</table>", "Untradeable - valued as its parts: 3 x Crystal armour seed");
		assertFalse("a parts row is not an alch row", html.contains(MovementRowPanel.ALCH_NOTE));
		// A guide row with the same figures has the four lines and no note at all.
		final String guide = block(body("Crystal body", null), MovementWindow.D1);
		assertFalse(guide, guide.contains("Untradeable"));
		assertTrue(guide, guide.endsWith("</table></div></html>"));
	}

	/**
	 * Y3 in the new block: the carried split is there exactly when it has something to add - never with the
	 * switch off, and never on a stack the bank holds whole, where "5 in bank" under "You have 5" is one fact
	 * twice.
	 */
	@Test
	public void theCarriedSplitIsInTheCellOnlyWhileTheInventorySwitchIsOn()
	{
		final MovementRow split = carried(3, 1, 1);
		final String on = MovementRowPanel.detail(split, MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, false);
		assertTrue(on, on.contains("</table>3 in bank, 1 in inventory, 1 worn"));

		final String off = MovementRowPanel.detail(split, MovementWindow.D1, THEN_DAY,
			ViewOptions.DEFAULT.withCountInventory(false), false);
		assertFalse(off, off.contains("in bank"));
		assertFalse(off, off.contains("worn"));
		assertTrue("...and the four lines are untouched by the switch", off.endsWith("</table></div></html>"));

		assertFalse("a stack the bank holds whole says nothing",
			MovementRowPanel.detail(carried(5, 0, 0), MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, false)
				.contains("in bank"));
	}

	/**
	 * What survives of AK's dropped live line, and what does not.
	 *
	 * <p>The VOLUME survives as a note, because that is what says whether a live price can be trusted. The
	 * buy/sell SPREAD is gone: it was the densest thing the old block carried and it serves a flipper, who has
	 * the Grand Exchange open anyway. Its absence is asserted out loud - "buy " and "sell " appear NOWHERE -
	 * because a deliberate removal is exactly the kind of thing a future hand puts back while restoring
	 * something else, and nothing else in this file would notice.
	 *
	 * <p>The refusal line is the other live note and is pinned here with it: a reader who switched live prices
	 * on wants to know which of their rows are actually live, and the two notes are the only answer.
	 */
	@Test
	public void theTradedNoteSurvivesAndTheBuySellSpreadAppearsNowhere()
	{
		final MovementRow live = liveBow(MovementRow.PriceSource.LIVE);
		final String html = block(live, MovementWindow.D1);
		assertTrue(html, html.contains("Live price, 517 traded yesterday"));
		assertFalse("the spread is gone (AK), on purpose: " + html, html.contains("buy "));
		assertFalse("the spread is gone (AK), on purpose: " + html, html.contains("sell "));
		assertFalse(html, html.contains("63.6m"));
		assertFalse(html, html.contains("63.3m"));
		assertFalse("and the old prose line went with it", html.contains(MovementRowPanel.LIVE_PRICE_PREFIX));

		// A guide row has no traded note, and neither has the same live row with the switch off.
		assertFalse(block(guideBow(), MovementWindow.D1).contains("traded yesterday"));
		assertFalse(MovementRowPanel.detail(live, MovementWindow.D1, TRADED_DAY, LIVE_OFF, false)
			.contains("traded yesterday"));

		// ...nor a live row the feed recorded no volume for: the note exists to carry a figure.
		final MovementRow unmeasured = guideBow().asLive(null, null,
			new MovementRow.LiveFacts(63_600_000L, 63_300_000L, 0L, null));
		assertFalse(block(unmeasured, MovementWindow.D1).contains("Live price,"));

		// The other live note: which check kept a row on the guide series, and only while the switch is on.
		final MovementRow thin = whip().withLiveRefusal(
			new MovementRow.LiveFacts(1_540_000L, 1_200_000L, 12L, "12 traded yesterday"));
		assertTrue(block(thin, MovementWindow.D1)
			.contains("</table>Guide price - live not used: 12 traded yesterday"));
		assertFalse(MovementRowPanel.detail(thin, MovementWindow.D1, THEN_DAY, LIVE_OFF, false)
			.contains("live not used"));
	}

	/**
	 * The name is repeated above the table ONLY when the row's FACE had to cut it, which is the one case a
	 * reader cannot read it from the cell they are looking at. Repeating it on every row would waste the top
	 * line of every open cell on a word already an inch above it.
	 *
	 * <p>Both legs are driven twice: through the pure builder's {@code showName} argument, and through a built
	 * row, which derives the argument from its own fitted label.
	 */
	@Test
	public void theNameIsBoldedAboveTheTableOnlyWhenTheFaceHadToCutIt() throws Exception
	{
		assertInOrder(MovementRowPanel.detail(whip(), MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, true),
			"<b>Abyssal whip</b>", "<table");
		assertFalse("a name the face shows whole is not repeated",
			block(whip(), MovementWindow.D1).contains("<b>"));

		onEdt(() ->
		{
			final String monster = "A very long item name that goes on and on and on for ever";
			final MovementRowPanel cut = new MovementRowPanel(row(monster, 1_000L, 900L, 100L, 11.1), null,
				MovementWindow.D1, THEN_DAY);
			assertNotEquals("the fixture has to be cut, or this test has one leg", monster, cut.nameText());
			// AS: each row is opened before its block is read - the opening writes it, and an unwritten block is
			// "", which carries no bold name and would pass the second leg on nothing.
			leftClick(cut);
			assertTrue(detail(cut).getText(), detail(cut).getText().contains("<b>" + monster + "</b>"));

			final MovementRowPanel whole = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("...and this one must not be", "Abyssal whip", whole.nameText());
			leftClick(whole);
			assertTrue("the block was written", detail(whole).getText().contains("<table"));
			assertFalse(detail(whole).getText(), detail(whole).getText().contains("<b>"));
		});
	}

	/**
	 * Everything that comes from the GAME is escaped - the item name and the part names - and everything built
	 * here is not: the block is HTML, so a name carrying an angle bracket would otherwise open a tag inside it,
	 * and a figure run through the escaper twice would print "&amp;nbsp;" where a space belongs.
	 */
	@Test
	public void theNameIsEscapedAndTheFiguresAreNotDoubleEscaped()
	{
		final MovementRow odd = row("Zulrah's <scales> & co", 100L, 90L, 10L, 11.1);
		final String html = MovementRowPanel.detail(odd, MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, true);
		assertTrue(html, html.contains("<b>Zulrah&#39;s &lt;scales&gt; &amp; co</b>"));
		assertFalse("the raw name would open a tag inside the block", html.contains("<scales>"));

		assertTrue("the markup this class writes is markup", html.contains("&nbsp;"));
		assertFalse("...and is not escaped a second time", html.contains("&amp;nbsp;"));
		assertTrue(html, html.contains("<font color='#"));
		assertFalse(html, html.contains("&lt;font"));
		assertFalse(html, html.contains("&lt;table"));
		assertFalse("the labels carry no entities of their own", html.contains("&amp;lt;"));

		// A part name comes from the game too, and is escaped where the note appends it.
		final String parts = MovementRowPanel.detail(
			body("Crystal body", Collections.singletonList(new BankItem.Part(23956, 3L, "Zulrah's <seed>"))),
			MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, false);
		assertTrue(parts, parts.contains("3 x Zulrah&#39;s &lt;seed&gt;"));
		assertFalse(parts, parts.contains("<seed>"));
	}

	/**
	 * The spacing rule under the table, which has no other guard and was a real visual bug: {@code </table>} has
	 * already ended the line, so a {@code <br>} on top of it opened a blank line the width of the cell between
	 * the figures and their note. The FIRST note therefore takes no break and every note after it does - and a
	 * block with no notes at all ends on the table.
	 */
	@Test
	public void theFirstNoteFollowsTheTableAndASecondTakesABreak()
	{
		final String one = block(alch("Graceful hood", 1, 20_000L), MovementWindow.D1);
		assertTrue(one, one.contains("</table>" + MovementRowPanel.ALCH_NOTE));
		assertFalse("the first note takes no break of its own", one.contains("</table><br>"));
		assertTrue(one, one.endsWith(MovementRowPanel.ALCH_NOTE + "</div></html>"));

		// Two notes: the second takes one, or it runs on to the end of the first.
		final MovementRow worn = new MovementRow(7, "Graceful hood", 1, false, 20_000L, null, null, null,
			20_000L, MovementRow.PriceSource.ALCH, null, null, null, null, 0, 0, 1);
		final String two = block(worn, MovementWindow.D1);
		assertTrue(two, two.contains("</table>" + MovementRowPanel.ALCH_NOTE + "<br>1 worn"));
		assertTrue(two, two.endsWith("1 worn</div></html>"));

		// Three, on the tallest block this plugin writes: one break fewer than there are notes, always.
		final String three = block(longDescription(), MovementWindow.D1);
		assertFalse(three, three.contains("</table><br>"));
		assertEquals("one <br> per note after the first", 2, countOf(three, "<br>"));

		// And none at all ends the block on the table.
		assertTrue(block(whip(), MovementWindow.D1), block(whip(), MovementWindow.D1)
			.endsWith("</table></div></html>"));
	}

	// ---- addendum AL: the gp figure on the face goes quiet

	/**
	 * AL, at row level: the gp figure takes {@link MovementRowPanel#quietChangeColor} and the percentage beside
	 * it keeps {@link MovementRowPanel#textChangeColor}, so the two stop competing for the same glance. The
	 * user, looking at a full list: "can you brainstorm some ideas for making the change in gp (+3,432) and the
	 * percent change (+23.5%) ... be more isolated from each other?" - four variants were rendered at the real
	 * width and they picked the dimmed one.
	 *
	 * <p>On a FLAT row and a row with no baseline the two are the same colour: there is nothing there to
	 * out-shout, and pushing a dash toward the background only makes it hard to read. Those legs are asked of
	 * the pure helpers, because such a row prints no gp label at all to read a colour off.
	 */
	@Test
	public void theGpFigureTakesTheQuietColourAndThePercentageTheFullOne() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow rise = whip();
			final MovementRowPanel up = new MovementRowPanel(rise, null, MovementWindow.D1, THEN_DAY);
			assertEquals("the gp figure", MovementRowPanel.quietChangeColor(rise), gpLabel(up).getForeground());
			assertEquals("the percentage", MovementRowPanel.textChangeColor(rise), up.changeColor());
			assertNotEquals("...and on a riser the two differ, which is the whole of AL",
				MovementRowPanel.textChangeColor(rise), MovementRowPanel.quietChangeColor(rise));

			final MovementRow fall = row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47);
			final MovementRowPanel down = new MovementRowPanel(fall, null, MovementWindow.D1, THEN_DAY);
			assertEquals(MovementRowPanel.quietChangeColor(fall), gpLabel(down).getForeground());
			assertEquals("the lifted red is still the percentage's (B045)", Widgets.MOVE_DOWN_TEXT,
				down.changeColor());
			assertNotEquals(MovementRowPanel.textChangeColor(fall), MovementRowPanel.quietChangeColor(fall));
			assertEquals("the rail is untouched by AL", ColorScheme.PROGRESS_ERROR_COLOR.darker(),
				down.railColor());

			final MovementRow flat = row("Coal", 150L, 150L, 0L, 0.0);
			assertEquals("a flat move is not dimmed: there is nothing to out-shout",
				MovementRowPanel.textChangeColor(flat), MovementRowPanel.quietChangeColor(flat));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.quietChangeColor(flat));
			final MovementRow noBaseline = row("Coal", 150L, null, null, null);
			assertEquals("...and neither is a row with nothing to compare",
				MovementRowPanel.textChangeColor(noBaseline), MovementRowPanel.quietChangeColor(noBaseline));
		});
	}

	/**
	 * What "quiet" means, computed and never copied: the gp figure's colour is strictly CLOSER to the card the
	 * row is painted on than the percentage's is, in both directions. A hex literal here would pass whatever the
	 * mix produced, which is the one thing this assertion must not do.
	 *
	 * <p>Two guards on the other side of it: the quiet colour is not the background itself (a figure that
	 * vanished would stop saying which way the price went at all), and it is not the full colour either.
	 */
	@Test
	public void theQuietColourIsStrictlyCloserToTheCardThanTheFullOne()
	{
		for (MovementRow r : new MovementRow[]{whip(), row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47)})
		{
			final Color full = MovementRowPanel.textChangeColor(r);
			final Color quiet = MovementRowPanel.quietChangeColor(r);
			final double toQuiet = distance(quiet, ColorScheme.DARKER_GRAY_COLOR);
			final double toFull = distance(full, ColorScheme.DARKER_GRAY_COLOR);
			assertTrue(r.name() + ": quiet " + quiet + " is " + toQuiet + " from the card while full " + full
				+ " is " + toFull, toQuiet < toFull);
			assertNotEquals(r.name() + ": a quiet figure is still a figure", ColorScheme.DARKER_GRAY_COLOR, quiet);
			assertTrue(r.name() + ": ...and has not reached the card", toQuiet > 0.0);
			assertNotEquals(r.name() + ": nor is it simply the full colour", full, quiet);
		}
	}

	// ---- addendum AS: the open block is written when the row is first opened, not when it is built

	/**
	 * THE proof of addendum AS: fifty rows built the way a page builds them - through the constructor
	 * {@code addPage} calls, with a seam of the panel's kind - and thrown away, and not one open block or long
	 * description written. Before AS every one of them wrote both in its constructor, and the block is the dear
	 * one: a label turns its HTML into a view tree the moment it is handed it, about 1 MB and 1 ms a row by the
	 * lag investigation's measure, so a 250-row page paid for 250 blocks nobody had opened on every bank change.
	 *
	 * <p>Counted at the BUILDERS ({@code MovementRowPanel.detailBuilds()} and {@code tooltipBuilds()}), not read
	 * off the labels: a constructor that wrote a block and threw it away would leave every label empty and still
	 * pay in full, and only a count sees that. The rows are of every kind the service publishes
	 * ({@link #pageRow}), so no kind is the one that still writes early.
	 *
	 * <p>Two controls keep the zero honest. The page is built a second time with ONE of its items open in the
	 * seam, as the panel's rebuild after a publish would be under a reader with a row open, and that writes
	 * exactly one block: a page costs one block per row the reader has open, and none for the rest. And a
	 * counter that could not count would pass all of this on nothing, so the last legs open a row and ask it for
	 * its description and see each count move by exactly one.
	 */
	@Test
	public void aPageOfRowsBuiltAndThrownAwayWritesNoBlockAndNoDescription() throws Exception
	{
		onEdt(() ->
		{
			final long details = MovementRowPanel.detailBuilds();
			final long tips = MovementRowPanel.tooltipBuilds();
			final RecordingExpansion shut = new RecordingExpansion();
			for (int i = 0; i < 50; i++)
			{
				final MovementRowPanel p = new MovementRowPanel(pageRow(i), null, MovementWindow.D7, THEN_DAY,
					ViewOptions.DEFAULT, shut);
				assertFalse(p.row().name() + ": built shut", p.expanded());
				assertEquals(p.row().name() + ": and its block is not written", "", detail(p).getText());
			}
			assertEquals("fifty rows built and thrown away, and not one block written", details,
				MovementRowPanel.detailBuilds());
			assertEquals("...nor one long description", tips, MovementRowPanel.tooltipBuilds());
			assertEquals("building only reads the seam", 0, shut.writes);

			// The same page rebuilt with ONE item open in the seam, as a publish rebuilds the page under a reader
			// who has a row open: that row, and only that row, writes its block.
			final RecordingExpansion oneOpen = new RecordingExpansion();
			oneOpen.setExpanded(pageRow(17).id(), true);
			for (int i = 0; i < 50; i++)
			{
				final MovementRowPanel p = new MovementRowPanel(pageRow(i), null, MovementWindow.D7, THEN_DAY,
					ViewOptions.DEFAULT, oneOpen);
				assertEquals(p.row().name() + ": open exactly when the seam says so", i == 17, p.expanded());
			}
			assertEquals("one row open, one block written", details + 1, MovementRowPanel.detailBuilds());
			assertEquals("and still no description", tips, MovementRowPanel.tooltipBuilds());

			// The counters can count, or the zeros above prove nothing.
			final MovementRowPanel opened = new MovementRowPanel(pageRow(3), null, MovementWindow.D7, THEN_DAY,
				ViewOptions.DEFAULT, new RecordingExpansion());
			leftClick(opened);
			assertEquals("an opening writes one block", details + 2, MovementRowPanel.detailBuilds());
			opened.tooltipHtml();
			assertEquals("an ask writes one description", tips + 1, MovementRowPanel.tooltipBuilds());
		});
	}

	/**
	 * The first opening writes the block and opens the cell to EXACTLY the height a block written in the
	 * constructor opened it to - the promise that let addendum AS move the write at all. The reader sees the same
	 * cell, in one beat, at the same size; only the moment the text is written has moved.
	 *
	 * <p>"The height a written block measures" is asked of a TWIN: a label given the properties the row's own
	 * block carries - read off it, so no font size or gap is copied in here - and then the text, which is what
	 * every build from AI to AR did in the constructor. The row is then opened, and must stand at the card's
	 * height plus what the twin measures, with the twin's text on its block.
	 *
	 * <p>This is the assertion that catches the one ordering AS depends on. Measure the block before writing it
	 * and the empty label asks only for the air above it, so the row opens a few px tall with the description
	 * clipped away - short of the twin by the whole block.
	 *
	 * <p>Walked across the blocks that differ most in height, from an alch row's two lines and a note to the
	 * tallest this plugin writes, and a name the face had to cut, whose block opens with that name in bold.
	 */
	@Test
	public void theFirstOpeningWritesTheBlockAndOpensToTheHeightAWrittenBlockMeasures() throws Exception
	{
		onEdt(() ->
		{
			for (MovementRow r : new MovementRow[]{whip(), alch("Graceful hood", 4, 20_000L),
				body("Crystal body", seeds()), row("Mystery box", null, null, null, null), longDescription(),
				row("A very long item name that goes on and on and on for ever", 1_000L, 900L, 100L, 11.1)})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);
				final JLabel block = detail(p);
				assertEquals(r.name() + ": nothing written yet", "", block.getText());

				final JLabel twin = new JLabel();
				twin.setFont(block.getFont());
				twin.setForeground(block.getForeground());
				twin.setBorder(block.getBorder());
				twin.setHorizontalAlignment(block.getHorizontalAlignment());
				twin.setVerticalAlignment(block.getVerticalAlignment());
				twin.setText(MovementRowPanel.detail(r, MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT,
					!r.name().equals(p.nameText())));
				final int written = twin.getPreferredSize().height;
				assertTrue(r.name() + ": a written block is taller than the air above it (" + written + " vs "
					+ block.getInsets().top + ")", written > block.getInsets().top);

				final long atClick = MovementRowPanel.detailBuilds();
				leftClick(p);
				assertTrue(p.expanded());
				assertEquals(r.name() + ": the opening wrote exactly one block", atClick + 1,
					MovementRowPanel.detailBuilds());
				assertEquals(r.name() + ": and it is the twin's", twin.getText(), block.getText());
				assertEquals(r.name() + ": and the cell opened to the card plus what a written block measures",
					MovementRowPanel.ROW_HEIGHT + written, p.getPreferredSize().height);
			}
		});
	}

	/**
	 * A row rebuilt from a seam that says it is OPEN writes its block as it is built - the one case where the
	 * constructor still writes one, because the reader is looking at that block and every publish rebuilds the
	 * page from new rows. It has to come back open WITH its text: a block never written would stand open at the
	 * air's height with nothing in it, while {@code expanded()} said the row was open.
	 *
	 * <p>{@link #aRowBuiltFromAnAlreadyOpenSeamIsDrawnOpen} pins the open height against the row's OWN block,
	 * which an unwritten block would satisfy with its own few px - so the text and the count are the half of
	 * the promise this test adds.
	 */
	@Test
	public void aRowRebuiltFromAnOpenSeamWritesItsBlockAsItIsBuilt() throws Exception
	{
		onEdt(() ->
		{
			final RecordingExpansion seam = new RecordingExpansion();
			seam.setExpanded(4151, true);
			seam.writes = 0;

			final long before = MovementRowPanel.detailBuilds();
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, seam);
			assertEquals("the constructor wrote the one block its seam asked for", before + 1,
				MovementRowPanel.detailBuilds());
			assertEquals("...and it is this row's block",
				MovementRowPanel.detail(whip(), MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT, false),
				detail(p).getText());
			assertTrue(detail(p).isVisible());
			assertEquals(MovementRowPanel.ROW_HEIGHT + detailHeight(p), p.getPreferredSize().height);
			assertTrue("...which is a written block's height, not the air's",
				detailHeight(p) > detail(p).getInsets().top);
			assertEquals("reading the seam wrote nothing to it", 0, seam.writes);

			// ...and ONE click shuts it, writing nothing more.
			final long written = MovementRowPanel.detailBuilds();
			leftClick(p);
			assertFalse(p.expanded());
			assertEquals(written, MovementRowPanel.detailBuilds());
		});
	}

	/**
	 * Shutting a block keeps its text, and opening it again writes nothing: a block is written ONCE in the life
	 * of a row. Asserted two ways, because each catches what the other cannot - the label holds the very same
	 * String through a shut and two more openings (a second write would hand it a new one, even with the same
	 * words), and the builder's count moves by exactly one across all of it.
	 *
	 * <p>A row that rewrote its block on every opening would still open to the right height with the right
	 * words; what it would cost is the HTML parse on every click, which is the cost addendum AS exists to
	 * remove. A row that emptied its block on shutting would look the same - a shut block is hidden - and pay
	 * again on the next opening.
	 */
	@Test
	public void shuttingKeepsTheBlockAndASecondOpeningWritesNothing() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(longDescription(), null, MovementWindow.D1, THEN_DAY);
			final long before = MovementRowPanel.detailBuilds();

			leftClick(p);
			final String written = detail(p).getText();
			final int open = p.getPreferredSize().height;
			assertTrue("the opening wrote it", written.startsWith("<html>"));
			assertEquals(before + 1, MovementRowPanel.detailBuilds());

			leftClick(p);
			assertFalse(p.expanded());
			assertFalse("shut: hidden", detail(p).isVisible());
			assertSame("...but still written - the very same text, not a copy", written, detail(p).getText());
			assertEquals("and the card is the 62 px card again",
				new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT), p.getPreferredSize());

			leftClick(p);
			assertTrue(p.expanded());
			assertSame("the second opening shows the text the first one wrote", written, detail(p).getText());
			assertEquals("...and opens to the same height", open, p.getPreferredSize().height);

			leftClick(p);
			leftClick(p);
			assertSame("and so does the third", written, detail(p).getText());
			assertEquals("one write in the whole life of the row", before + 1, MovementRowPanel.detailBuilds());
		});
	}

	/**
	 * The block's LABEL is still built with the row, empty, and it is a CHILD like any other from the start -
	 * which is why addendum AS writes only its text late. The row installs its click and its right-click menu
	 * on every child as it is built, so a label added at the first opening would carry neither: a click on an
	 * open block - most of an open cell, and the obvious place to click to shut it - would do nothing, and a
	 * right-click there would open no menu.
	 *
	 * <p>So on a FRESH row the block is the card's CENTER child, in the walk every sweep here uses, carrying the
	 * row's listener and resolving the row's own menu; once open, a click ON the block shuts the row, a
	 * right-click on it toggles nothing, both menu entries still browse from it, and it has no hover, open or
	 * shut.
	 */
	@Test
	public void theEmptyBlockIsAChildFromTheStartSoTheClickAndTheMenuReachIt() throws Exception
	{
		onEdt(() ->
		{
			final AtomicReference<String> browsed = new AtomicReference<>();
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY,
				ViewOptions.DEFAULT, browsed::set);
			final JLabel block = detail(p);
			assertEquals("fresh, and empty", "", block.getText());
			final List<JComponent> children = new ArrayList<>();
			collect(p, children);
			assertTrue("the empty block is one of the row's children", children.contains(block));
			assertTrue("it carries the row's click", block.getMouseListeners().length >= 1);
			assertTrue("it takes the row's menu", block.getInheritsPopupMenu());
			assertSame("...and the menu it finds is the row's own", p.getComponentPopupMenu(),
				block.getComponentPopupMenu());
			assertNull("no hover while shut", block.getToolTipText());

			// Opened from the face; then the clicks land ON the block, where a reader shutting it would click.
			leftClick(nameLabel(p));
			assertTrue(p.expanded());
			assertSame("the opening wrote into the label that was built", block, detail(p));
			assertTrue(block.getText().startsWith("<html>"));
			assertNull("no hover while open either", block.getToolTipText());

			click(block, MouseEvent.BUTTON3, false);
			assertTrue("a right-click on the block toggles nothing", p.expanded());
			final JPopupMenu menu = block.getComponentPopupMenu();
			((JMenuItem) menu.getComponent(0)).doClick(0);
			assertEquals("https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=4151", browsed.get());
			((JMenuItem) menu.getComponent(1)).doClick(0);
			assertEquals("https://prices.runescape.wiki/osrs/item/4151", browsed.get());

			leftClick(block);
			assertFalse("a click on the open block shuts the row", p.expanded());
			assertFalse(block.isVisible());
			assertEquals(new Dimension(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT),
				p.getPreferredSize());
		});
	}

	/**
	 * The two texts written late are written from what the row was BUILT with - its window, its baseline day and
	 * its switches - and not from defaults. A late text has to say exactly what an early one would have said, or
	 * moving the write would have moved the words.
	 *
	 * <p>The fixture is chosen so a default would show: a live row built for the 30 d window, on a day that is
	 * not the file's usual one, with the live switch OFF - so a block written with any default (the 1 d window,
	 * the usual day, the switch on) reads differently. Both texts are compared with the pure builders under the
	 * row's own arguments, and the defaults' texts are asserted to differ, or the comparison proves nothing.
	 */
	@Test
	public void theLateTextsAreWrittenFromWhatTheRowWasBuiltWith() throws Exception
	{
		onEdt(() ->
		{
			final LocalDate day = LocalDate.of(2026, 8, 12);
			final MovementRow live = liveBow(MovementRow.PriceSource.LIVE);
			final MovementRowPanel p = new MovementRowPanel(live, null, MovementWindow.D30, day, LIVE_OFF);
			leftClick(p);
			final String block = MovementRowPanel.detail(live, MovementWindow.D30, day, LIVE_OFF, false);
			assertEquals("the block, as the row's own window, day and switches write it", block,
				detail(p).getText());
			assertTrue(block, block.contains(MovementRowPanel.L_CHANGE + MovementWindow.D30.label()));
			assertTrue(block, block.contains("(12 Aug)"));
			assertNotEquals("the defaults write another block, or this proves nothing", block,
				MovementRowPanel.detail(live, MovementWindow.DEFAULT, THEN_DAY, ViewOptions.DEFAULT, false));

			final String tip = MovementRowPanel.tooltip(live, MovementWindow.D30, day, LIVE_OFF);
			assertEquals("the long description, likewise", tip, p.tooltipHtml());
			assertNotEquals("...and likewise not the defaults'", tip,
				MovementRowPanel.tooltip(live, MovementWindow.DEFAULT, THEN_DAY, ViewOptions.DEFAULT));
		});
	}

	/**
	 * The long description is written on the first ask and kept - and never by building a row. No sidebar has
	 * drawn it since addendum AK, yet every build from AI to AR wrote it in the constructor for every row of
	 * every page; only the tests read it, through {@code tooltipHtml()}.
	 *
	 * <p>Counted and compared by identity: building writes none, the first ask writes one, the second ask writes
	 * nothing and answers the very same String - and that String is what the pure builder writes for the row's
	 * own arguments, so every test in this file that reads it describes exactly what it always did.
	 */
	@Test
	public void theLongDescriptionIsWrittenOnTheFirstAskAndKept() throws Exception
	{
		onEdt(() ->
		{
			final long before = MovementRowPanel.tooltipBuilds();
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("building a row writes no description", before, MovementRowPanel.tooltipBuilds());

			final String first = p.tooltipHtml();
			assertEquals("the first ask writes one", before + 1, MovementRowPanel.tooltipBuilds());
			assertSame("the second answers the same String, kept rather than written again", first,
				p.tooltipHtml());
			assertEquals(before + 1, MovementRowPanel.tooltipBuilds());
			assertEquals("and it is what the pure builder writes for this row",
				MovementRowPanel.tooltip(whip(), MovementWindow.D1, THEN_DAY, ViewOptions.DEFAULT), first);

			// Opening and shutting the cell has nothing to do with it.
			leftClick(p);
			leftClick(p);
			assertSame(first, p.tooltipHtml());
		});
	}

	// ---- helpers

	/** The open cell's block for one row, under the default switches and the fixed baseline day. */
	private static String block(MovementRow row, MovementWindow window)
	{
		return MovementRowPanel.detail(row, window, THEN_DAY, ViewOptions.DEFAULT, false);
	}

	/**
	 * That {@code parts} appear in {@code html} in this order, each after the last. A column is an ORDER, and
	 * four {@code contains} calls would pass on a block that printed its lines backwards.
	 */
	private static void assertInOrder(String html, String... parts)
	{
		int at = -1;
		for (String part : parts)
		{
			final int found = html.indexOf(part, at + 1);
			assertTrue("\"" + part + "\" is missing or out of order in " + html, found > at);
			at = found;
		}
	}

	/** How many times {@code needle} occurs in {@code text}, without overlaps. */
	private static int countOf(String text, String needle)
	{
		int count = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
		{
			count++;
		}
		return count;
	}

	/**
	 * The gp figure's label, found by the text it prints - line 2 carries no other label with that text. It is
	 * only in the row at all when there is a figure to print, so the empty case is refused rather than matching
	 * the first label whose text happens to be "".
	 */
	private static JLabel gpLabel(MovementRowPanel p)
	{
		assertFalse("this row prints no gp figure", p.gpText().isEmpty());
		final JLabel found = label(p, p.gpText());
		assertNotNull("the gp figure is not on the row", found);
		return found;
	}

	/** Straight-line distance between two colours in RGB, for AL's "closer to the card" rule. */
	private static double distance(Color a, Color b)
	{
		final int dr = a.getRed() - b.getRed();
		final int dg = a.getGreen() - b.getGreen();
		final int db = a.getBlue() - b.getBlue();
		return Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db);
	}

	/**
	 * An Abyssal whip the player holds in {@code bank} + {@code inventory} + {@code worn} places at once, as the
	 * service merges one under addendum Y (line Y3): one row, the quantities summed, and the split recorded so the
	 * hover can name it.
	 */
	private static MovementRow carried(int bank, int inventory, int worn)
	{
		final int quantity = bank + inventory + worn;
		return new MovementRow(4151, "Abyssal whip", quantity, false, 1_520_000L, 1_500_000L, 20_000L,
			20_000d * 100.0 / 1_500_000d, 1_520_000L * quantity, null, null, null, null, null,
			bank, inventory, worn);
	}

	/**
	 * A Crystal body as the service lists one under addendum R: 16,694,766 gp now - three Crystal armour seeds at
	 * 5,564,922 - against 18,300,000 on the baseline day, a fall of 1,605,234 gp (-8.77 %, truncated to -8.7 %).
	 *
	 * @param parts what one of it reverts to; null makes the same figures as a plain GUIDE row, which is the twin
	 *              the "paints exactly like" comparison needs
	 */
	private static MovementRow body(String name, List<BankItem.Part> parts)
	{
		return new MovementRow(23975, name, 1, false, 16_694_766L, 18_300_000L, -1_605_234L,
			-1_605_234d * 100.0 / 18_300_000d, 16_694_766L,
			parts == null ? null : MovementRow.PriceSource.PARTS, parts);
	}

	/** What a Crystal body reverts to: three Crystal armour seeds ({@code ItemMapping.ITEM_CRYSTAL_BODY}). */
	private static List<BankItem.Part> seeds()
	{
		return Collections.singletonList(new BankItem.Part(23956, 3L, "Crystal armour seed"));
	}

	/**
	 * The tallest description this plugin can write: every optional line at once. The name, a live traded price
	 * with both sides and the volume (T6), a parts line (R4), a window line stamped with the traded day and its
	 * "(traded average)" note (U3), the holding, a three-way carried split (Y3) and the change - seven lines,
	 * against the three an alch row has, and every one of them long enough to wrap again inside the cell.
	 *
	 * <p>Synthetic, and deliberately so: no real item is both untradeable-with-parts and liquid enough to be
	 * priced live. It is a HEIGHT fixture, and what it has to be is the row whose description wraps to the most
	 * lines - which is the only thing the test that uses it asserts about it.
	 */
	private static MovementRow longDescription()
	{
		final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
		sources.put(MovementWindow.D1, MovementRow.PriceSource.LIVE);
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, TRADED_DAY);
		return new MovementRow(23975, "Crystal body", 5, false, 16_694_766L, 18_300_000L, -1_605_234L,
			-1_605_234d * 100.0 / 18_300_000d, 83_473_830L, MovementRow.PriceSource.LIVE, seeds(), sources, days,
			new MovementRow.LiveFacts(16_800_000L, 16_600_000L, 517L, null), 3, 1, 1);
	}

	/**
	 * Row {@code i} of a synthetic page, for addendum AS's counts: a distinct item id per row, so a seam can open
	 * exactly one of them, cycling through every kind of row the service publishes - a riser, a faller, a flat
	 * row, a row with no price, an alch row, a parts row, a live row carrying a carried split, and a riser whose
	 * name the face has to cut, the one kind whose block reads something off the face.
	 */
	private static MovementRow pageRow(int i)
	{
		final int id = 30_000 + i;
		switch (i % 8)
		{
			case 0:
				return new MovementRow(id, "Riser " + i, 12, false, 1_520_000L, 1_500_000L, 20_000L,
					20_000d * 100.0 / 1_500_000d, 18_240_000L, null);
			case 1:
				return new MovementRow(id, "Faller " + i, 5, false, 15_000L, 19_100L, -4_100L,
					-4_100d * 100.0 / 19_100d, 75_000L, null);
			case 2:
				return new MovementRow(id, "Flat " + i, 5, false, 150L, 150L, 0L, 0.0, 750L, null);
			case 3:
				return new MovementRow(id, "Unpriced " + i, 5, false, null, null, null, null, 0L, null);
			case 4:
				return new MovementRow(id, "Alch " + i, 4, false, 20_000L, null, null, null, 80_000L,
					MovementRow.PriceSource.ALCH);
			case 5:
				return new MovementRow(id, "Parts " + i, 1, false, 16_694_766L, 18_300_000L, -1_605_234L,
					-1_605_234d * 100.0 / 18_300_000d, 16_694_766L, MovementRow.PriceSource.PARTS, seeds());
			case 6:
			{
				final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
				sources.put(MovementWindow.D7, MovementRow.PriceSource.LIVE);
				final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
				days.put(MovementWindow.D7, TRADED_DAY);
				return new MovementRow(id, "Live " + i, 5, false, 16_694_766L, 18_300_000L, -1_605_234L,
					-1_605_234d * 100.0 / 18_300_000d, 83_473_830L, MovementRow.PriceSource.LIVE, null, sources,
					days, new MovementRow.LiveFacts(16_800_000L, 16_600_000L, 517L, null), 3, 1, 1);
			}
			default:
				return new MovementRow(id, "A very long item name that goes on and on and on for ever " + i, 1,
					false, 1_000L, 900L, 100L, 11.1, 1_000L, null);
		}
	}

	/** An untradeable stack as the service lists one (Q5): the alch price, no baseline, no move. */
	private static MovementRow alch(String name, int quantity, long haPrice)
	{
		return new MovementRow(7, name, quantity, false, haPrice, null, null, null, haPrice * quantity,
			MovementRow.PriceSource.ALCH);
	}

	/** The label carrying the {@value MovementRowPanel#ALCH_TAG} tag, for its face and its colour. */
	private static JLabel tag(MovementRowPanel p)
	{
		final JLabel found = label(p, MovementRowPanel.ALCH_TAG);
		assertNotNull("the row has no alch tag", found);
		return found;
	}

	/** No line of a row may ask for more than the text block it lives in (addendum N line N6). */
	private static void assertFits(String what, JComponent line)
	{
		final int width = line.getPreferredSize().width;
		assertTrue(what + " asks for " + width + " px of " + MovementRowPanel.TEXT_WIDTH,
			width <= MovementRowPanel.TEXT_WIDTH);
	}

	/**
	 * The face: the cell every build before AI called the row - 48 px then, 62 since Q4 - now the NORTH child of
	 * the card. Found by
	 * its place in the layout rather than by "the first panel", because the row has a second child since AI.
	 */
	private static JPanel face(MovementRowPanel p)
	{
		final Component north = ((BorderLayout) p.getLayout()).getLayoutComponent(BorderLayout.NORTH);
		if (!(north instanceof JPanel))
		{
			fail("the row has no face");
		}
		return (JPanel) north;
	}

	/**
	 * The detail block: the CENTER child of the card, hidden until the row is clicked (AI). Asked of the layout
	 * and not of the visible children, because a test has to be able to read it while it is invisible - EMPTY
	 * until the row's first opening since addendum AS, and from then on the block whose measurement the open
	 * height is.
	 */
	private static JLabel detail(MovementRowPanel p)
	{
		final Component centre = ((BorderLayout) p.getLayout()).getLayoutComponent(BorderLayout.CENTER);
		if (!(centre instanceof JLabel))
		{
			fail("the row has no detail block");
		}
		return (JLabel) centre;
	}

	/**
	 * What the row is {@link MovementRowPanel#ROW_HEIGHT} taller by while it is open: the detail's own ask. Read
	 * it after an opening (addendum AS): before one, the unwritten block asks only for the air above it.
	 */
	private static int detailHeight(MovementRowPanel p)
	{
		return detail(p).getPreferredSize().height;
	}

	/** The text block: the face's only {@link JPanel} child, the picture beside it being a label. */
	private static JPanel textBlock(MovementRowPanel p)
	{
		for (Component c : face(p).getComponents())
		{
			if (c instanceof JPanel)
			{
				return (JPanel) c;
			}
		}
		fail("the row has no text block");
		return null;
	}

	private static JLabel nameLabel(MovementRowPanel p)
	{
		final JPanel text = textBlock(p);
		return (JLabel) ((BorderLayout) text.getLayout()).getLayoutComponent(BorderLayout.NORTH);
	}

	private static JComponent line2(MovementRowPanel p)
	{
		final JPanel text = textBlock(p);
		return (JComponent) ((BorderLayout) text.getLayout()).getLayoutComponent(BorderLayout.CENTER);
	}

	/** Line 3, the ITEM line Q4 added: the text block's SOUTH child, under line 2. */
	private static JComponent line3(MovementRowPanel p)
	{
		final JPanel text = textBlock(p);
		return (JComponent) ((BorderLayout) text.getLayout()).getLayoutComponent(BorderLayout.SOUTH);
	}

	/** Line 2's right-hand group: the gp column and the percentage column, sized first by the fit order. */
	private static JComponent figures(MovementRowPanel p)
	{
		return (JComponent) ((BorderLayout) line2(p).getLayout()).getLayoutComponent(BorderLayout.EAST);
	}

	/**
	 * The stack value on the left of line 2. Found by its PLACE in the layout and not by its text, because that
	 * text is a figure the fixture chose and two labels on a row can print the same one.
	 *
	 * <p>It is line 2's WEST child DIRECTLY since addendum AO: the label and the word "Total" used to share a
	 * wrapper panel there, and with the word deleted a one-child wrapper was deleted with it.
	 */
	private static JLabel priceLabel(MovementRowPanel p)
	{
		return (JLabel) ((BorderLayout) line2(p).getLayout()).getLayoutComponent(BorderLayout.WEST);
	}

	/** The working on the left of line 3 ("12 x 1.52m"). */
	private static JLabel workingLabel(MovementRowPanel p)
	{
		return (JLabel) ((BorderLayout) line3(p).getLayout()).getLayoutComponent(BorderLayout.WEST);
	}

	/**
	 * ONE item's gp figure, in line 3's half of the shared gp column. By place and never by text: it is empty on
	 * a stack of one (Q4.7) and on a row with no movement, and it prints the same string as the figure above it
	 * whenever the stack is one item.
	 */
	private static JLabel itemGpLabel(MovementRowPanel p)
	{
		return (JLabel) ((BorderLayout) line3Figures(p).getLayout()).getLayoutComponent(BorderLayout.WEST);
	}

	/** Line 3's empty stand-in for the percentage column, which is what keeps the gp column above it true. */
	private static JComponent pctSpacer(MovementRowPanel p)
	{
		return (JComponent) ((BorderLayout) line3Figures(p).getLayout()).getLayoutComponent(BorderLayout.EAST);
	}

	private static JComponent line3Figures(MovementRowPanel p)
	{
		return (JComponent) ((BorderLayout) line3(p).getLayout()).getLayoutComponent(BorderLayout.EAST);
	}

	/**
	 * Lays a row out for real, off-screen and top down. {@link Container#validate()} does nothing without a peer
	 * and there is no display here, so each container is handed its preferred size and told to lay itself out -
	 * the same {@code layoutContainer} calls {@code validate} would make, without the peer.
	 */
	private static void layOut(MovementRowPanel p)
	{
		p.setSize(p.getPreferredSize());
		layOut((Container) p);
	}

	private static void layOut(Container c)
	{
		c.doLayout();
		for (Component child : c.getComponents())
		{
			if (child instanceof Container)
			{
				layOut((Container) child);
			}
		}
	}

	/** Where {@code c}'s left edge stands in the ROW's own coordinates, after {@link #layOut(MovementRowPanel)}. */
	private static int leftEdge(Component c, MovementRowPanel row)
	{
		return SwingUtilities.convertPoint(c, 0, 0, row).x;
	}

	/**
	 * What line 2's gp column prints for a move of {@code deltaGp}, read off a built row. The stack is ONE item,
	 * so the stack's move and the item's are the same figure and this is the compacting on its own.
	 */
	private static String faceGp(long deltaGp)
	{
		return new MovementRowPanel(stack("Coins", 1, 2_000_000_000L, deltaGp, 0.1), null, MovementWindow.D1,
			THEN_DAY).gpText();
	}

	private static JLabel label(Container c, String text)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText()))
			{
				return (JLabel) child;
			}
			if (child instanceof Container)
			{
				final JLabel found = label((Container) child, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void collect(Container c, List<JComponent> into)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JComponent)
			{
				into.add((JComponent) child);
			}
			if (child instanceof Container)
			{
				collect((Container) child, into);
			}
		}
	}

	private static void enter(Component target, MovementRowPanel row)
	{
		for (MouseListener l : target.getMouseListeners())
		{
			l.mouseEntered(new MouseEvent(target, MouseEvent.MOUSE_ENTERED, 0L, 0, 1, 1, 0, false));
		}
		assertTrue(row.hovered());
	}

	/**
	 * A left click on one of the row's own mouse targets, driven the way {@link #enter} drives a hover: the
	 * listeners the row installed on that component are handed a real {@link MouseEvent}. Synthesising the event
	 * rather than calling {@code toggleExpanded} directly is what keeps the WIRING under test - that the listener
	 * is on the component at all, and that both of the handler's guards (the left button, and never a popup
	 * trigger) are the ones deciding.
	 */
	private static void leftClick(Component target)
	{
		click(target, MouseEvent.BUTTON1, false);
	}

	/** {@link #leftClick} with the button and the popup-trigger flag spelled out, for the right-click rule. */
	private static void click(Component target, int button, boolean popupTrigger)
	{
		final MouseEvent e = new MouseEvent(target, MouseEvent.MOUSE_CLICKED, 0L, 0, 3, 3, 1, popupTrigger,
			button);
		for (MouseListener l : target.getMouseListeners())
		{
			l.mouseClicked(e);
		}
	}

	/**
	 * A {@link MovementRowPanel.Expansion} of the test's own, standing in for the one the panel holds: it
	 * records what a click wrote, and counts the writes so a test can prove that BUILDING a row only reads.
	 */
	private static final class RecordingExpansion implements MovementRowPanel.Expansion
	{
		private final Map<Integer, Boolean> state = new HashMap<>();
		private int writes;

		@Override
		public boolean isExpanded(int itemId)
		{
			return Boolean.TRUE.equals(state.get(itemId));
		}

		@Override
		public void setExpanded(int itemId, boolean expanded)
		{
			state.put(itemId, expanded);
			writes++;
		}
	}

	private static void exit(Component target, MovementRowPanel row)
	{
		for (MouseListener l : target.getMouseListeners())
		{
			l.mouseExited(new MouseEvent(target, MouseEvent.MOUSE_EXITED, 0L, 0, 1, 1, 0, false));
		}
		assertFalse(row.hovered());
	}

	/** Runs the body on the EDT (where the row lives) and re-throws whatever it failed with. */
	static void onEdt(Runnable body) throws Exception
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
