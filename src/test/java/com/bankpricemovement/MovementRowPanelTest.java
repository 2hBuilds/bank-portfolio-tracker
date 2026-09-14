package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
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
 * {@link MovementRowPanel}: the 213 x 48 card, its rail, its name line, the gp figure and the percentage on
 * line 2, the quantity-1 rule, the fit order, the guide-price tooltip and the two-entry right-click - built
 * for real on the EDT and measured off-screen (playbook 7.6, REAL SWING OFF-SCREEN).
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
 * <p><b>Addendum Q's two row switches</b> ({@link ViewOptions}): an untradeable stack prints its High Alchemy
 * price with a grey "alch" tag where the move figures go and says so in one tooltip sentence (Q5), and with
 * {@code holdingOnRows} on the price figure is the whole stack and the gp figure is the whole stack's change
 * (Q6). Both are decided when the row is BUILT, so every assertion below builds the row it measures.
 *
 * <p><b>Addendum R splits the untradeables in two</b> (R4): a stack RuneLite can take apart is worth what its
 * tradeable PARTS are worth, so it has a baseline and a move and must paint as an ordinary guide row does, with
 * one extra tooltip line naming the parts; only a stack with no such mapping keeps Q5's alch tag and sentence.
 * The tests below hold both halves of that - what the parts row must look like, and what the alch row still
 * does.
 *
 * <p><b>No literal pixel widths.</b> {@code Font.DIALOG} maps to a different face on every platform, so every
 * width assertion here measures the components it is about and compares them with
 * {@link MovementRowPanel#TEXT_WIDTH} (addendum N line N6), never with a number copied out of the spec.
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

	// ---- geometry (addendum N section 2: 213 x 48, and section 3 §4: a 156 px text block)

	@Test
	public void everyRowIs213By48WithA36By32PictureCell() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("row", new Dimension(213, 48), p.getPreferredSize());
			assertEquals("picture", new Dimension(36, 32), p.iconLabel().getPreferredSize());
			assertEquals(MovementRowPanel.ROW_WIDTH, Widgets.CONTENT_WIDTH);
			assertEquals("the row's height overrides C31's 40", 48, MovementRowPanel.ROW_HEIGHT);
			// 213 - 3 (rail) - 4 - 6 (padding) - 36 (picture) - 8 (gap): the name's whole line.
			assertEquals("the text block", 156, MovementRowPanel.TEXT_WIDTH);
		});
	}

	/**
	 * N6: with the widest figures the formatters can produce and a name no sidebar could hold, neither line
	 * asks for more than the text block and the row is still exactly 213 x 48. The fixtures are addendum N
	 * section 3 §9's: a 2,147m unit price on a stack of 28,000 moving 100 %, and the same row with a -999.9m
	 * change. One walk, not one per ordering: since addendum O nothing on the face varies with the sort (O1).
	 */
	@Test
	public void neitherLineOverflowsWithTheWidestFigures() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow huge = stack("Ancient ceremonial legs of the utterly absurd", 28_000,
				2_147_000_000L, 1_073_500_000L, 100.0);
			final MovementRow crash = stack("Karambwan vessel (baited)", 28_000, 2_147_000_000L,
				-999_900_000L, -100.0);
			for (MovementRow r : new MovementRow[]{huge, crash, whip()})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D180, THEN_DAY);
				assertEquals(r.name(), new Dimension(213, 48), p.getPreferredSize());
				assertFits(r.name() + " name", nameLabel(p));
				assertFits(r.name() + " line 2", line2(p));
			}
		});
	}

	/**
	 * The fit order (the M4 count idiom): the figures on the right are sized first, the price takes what is
	 * left, and the stack text is added only if it still fits - so an absurd stack loses its "x28,000" rather
	 * than the price losing its digits.
	 */
	@Test
	public void theStackTextIsDroppedBeforeThePriceIsCut() throws Exception
	{
		onEdt(() ->
		{
			// The widest line the formatters can make: a five-figure price, a five-figure stack and both
			// movement figures at their widest. Something has to go, and it is the "x9,999".
			final MovementRow crowded = stack("Coins", 9_999, 9_999L, -9_999L, -99.9);
			assertEquals("x9,999", MovementRowPanel.quantityText(crowded));
			final MovementRowPanel p = new MovementRowPanel(crowded, null, MovementWindow.D1, THEN_DAY);
			assertEquals("the price is whole", MovementRowPanel.priceText(crowded), p.priceText());
			assertEquals("the stack text went, not the price", "", p.quantityText());
			assertFits("line 2", line2(p));
		});
	}

	/**
	 * A name that does not fit is cut with three ASCII dots (never U+2026 - the bitmap faces box a glyph they
	 * lack) and the whole name goes in the tooltip; a name that fits is left alone. The name now has the WHOLE
	 * text block, which is what buys "Confliction gauntlets" its last two letters back.
	 */
	@Test
	public void aLongNameIsCutToTheTextBlockAndKeptWholeInTheTooltip() throws Exception
	{
		onEdt(() ->
		{
			final String monster = "A very long item name that goes on and on and on for ever";
			final MovementRowPanel p = new MovementRowPanel(row(monster, 1_000L, 900L, 100L, 11.1), null,
				MovementWindow.D180, THEN_DAY);
			assertTrue(p.nameText(), p.nameText().endsWith(Widgets.ELLIPSIS));
			assertNotEquals(monster, p.nameText());
			assertFits("the cut name", nameLabel(p));
			assertTrue("the whole name is in the tooltip", p.tooltipHtml().contains(monster));

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
			assertEquals("+20k", MovementRowPanel.gpText(r));

			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY);
			assertEquals("+20k", p.gpText());
			assertEquals("+1.3%", p.changeText());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, p.changeColor());

			// ...and a row built with contract C31's constructor - no baseline day - prints it too.
			final MovementRowPanel untold = new MovementRowPanel(r, null, MovementWindow.D1);
			assertEquals("+20k", untold.gpText());
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
			assertEquals("pct", MovementMath.DASH, p.changeText());
			assertEquals("colour", ColorScheme.LIGHT_GRAY_COLOR, p.changeColor());
			assertEquals("", MovementRowPanel.gpText(none));
		});
	}

	// ---- the quantity-1 rule

	@Test
	public void theStackTextIsEmptyAtAQuantityOfOne() throws Exception
	{
		onEdt(() ->
		{
			assertEquals("", MovementRowPanel.quantityText(stack("Green hat", 1, 1_086L, 12L, 1.1)));
			assertEquals("x3", MovementRowPanel.quantityText(stack("Green hat", 3, 1_086L, 12L, 1.1)));
			assertEquals("x9,999", MovementRowPanel.quantityText(stack("Coins", 9_999, 1L, 0L, 0.0)));
			// The stack count is the game's own stack text, so a big one shortens itself the way the game does.
			assertEquals("x28k", MovementRowPanel.quantityText(stack("Coins", 28_000, 1L, 0L, 0.0)));
			// ...and the holding value has left the face for the tooltip's "Holding:" line.
			assertFalse(MovementRowPanel.quantityText(stack("Green hat", 3, 1_086L, 12L, 1.1)).contains("-"));

			final MovementRowPanel one = new MovementRowPanel(stack("Green hat", 1, 1_086L, 12L, 1.1), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("", one.quantityText());
			assertEquals("1,086", one.priceText());
			final MovementRowPanel three = new MovementRowPanel(stack("Green hat", 3, 1_086L, 12L, 1.1), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("x3", three.quantityText());
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

	@Test
	public void textsAreTheMathsShortForms() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			assertEquals("Abyssal whip", p.nameText());
			assertEquals("1.52m", p.priceText());
			assertEquals("x12", p.quantityText());
			// formatGp is the game's stack text: 20,000 is "20k", not "20.0k" (QuantityFormatter, A1 as landed).
			assertEquals("+20k", p.gpText());
			assertEquals("+1.3%", p.changeText());
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, p.changeColor());
		});
	}

	@Test
	public void aFallIsRedAZeroMoveAndNoMoveAreGrey()
	{
		// -21.47 truncates toward zero to -21.4, never rounds to -21.5 (L2): the row never prints a move
		// larger than the one that happened.
		final MovementRow fall = row("Rune scimitar", 15_000L, 19_100L, -4_100L, -21.47);
		assertEquals("-21.4%", MovementRowPanel.changeText(fall));
		assertEquals("-4,100", MovementRowPanel.gpText(fall));
		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, MovementRowPanel.changeColor(fall));

		final MovementRow flat = row("Coal", 150L, 150L, 0L, 0.0);
		assertEquals("0.0%", MovementRowPanel.changeText(flat));
		assertEquals("a price that did not move prints its percentage and nothing else (B055)", "",
			MovementRowPanel.gpText(flat));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.changeColor(flat));

		// A row that is priced but has no baseline - the guide history has not landed, or the wiki table
		// carries no name for it - shows ONE dash and the dim grey.
		final MovementRow noBaseline = row("Coal", 150L, null, null, null);
		assertEquals("one dash, never two", MovementMath.DASH, MovementRowPanel.changeText(noBaseline));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.changeColor(noBaseline));
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
			assertEquals(MovementMath.DASH, MovementRowPanel.priceText(none));
			assertEquals("x5", MovementRowPanel.quantityText(none));
			assertEquals(MovementMath.DASH, MovementRowPanel.changeText(none));

			final MovementRowPanel p = new MovementRowPanel(none, null, MovementWindow.D1, null);
			assertEquals(MovementMath.DASH, p.priceText());
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
	 * T6: a LIVE row paints EXACTLY as a guide row with the same figures does - same name, same price, same stack
	 * text, same gp figure, same percentage, same rail, same size. No tag, no colour of its own: a live price is a
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
			assertEquals(b.quantityText(), a.quantityText());
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

	@Test
	public void everyChildCarriesTheTooltipTheHoverListenerAndThePopup() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(whip(), null, MovementWindow.D1, THEN_DAY);
			final String tip = p.tooltipHtml();
			assertEquals(tip, p.getToolTipText());
			assertNotNull(p.getComponentPopupMenu());
			final List<JComponent> children = new ArrayList<>();
			collect(p, children);
			assertTrue("the picture, the text block, two lines, two groups, five labels", children.size() >= 9);
			for (JComponent c : children)
			{
				assertEquals(c.getClass().getSimpleName() + " tooltip", tip, c.getToolTipText());
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
			assertEquals(new Dimension(36, 32), p.iconLabel().getPreferredSize());
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
	 * measure 164 px in the row's bold 14 against a 156 px block, and the plain cut made one string of them -
	 * four consecutive rows headed "Super combat potio...", with the only thing that told them apart in the
	 * part that was thrown away.
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

	/** B055: a row whose price did not move prints its percentage alone - "0" beside "0.0%" says it twice. */
	@Test
	public void aRowThatDidNotMovePrintsNoGpFigure() throws Exception
	{
		onEdt(() ->
		{
			final MovementRowPanel p = new MovementRowPanel(row("Coal", 150L, 150L, 0L, 0.0), null,
				MovementWindow.D1, THEN_DAY);
			assertEquals("", p.gpText());
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

	// ---- addendum Q: the untradeable tag (Q5) and the holding figures (Q6)

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

	/**
	 * Q6: with {@code holdingOnRows} on, line 2 prints what the whole STACK is worth and what the whole stack
	 * moved - the two figures the tooltip has carried alone since addendum N - and nothing else about the row
	 * changes: the percentage is the same number per item and per stack, the "x12" stays, and the tooltip
	 * already carries both readings.
	 */
	@Test
	public void holdingOnRowsPrintsTheStacksWorthAndTheStacksChange() throws Exception
	{
		onEdt(() ->
		{
			final MovementRow r = whip();
			final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);
			assertEquals("1.52m", MovementRowPanel.priceText(r));
			assertEquals("12 x 1,520,000", "18.2m", MovementRowPanel.priceText(r, holding));
			assertEquals("+20k", MovementRowPanel.gpText(r));
			assertEquals("12 x +20,000", "+240k", MovementRowPanel.gpText(r, holding));

			final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D1, THEN_DAY, holding);
			assertEquals("18.2m", p.priceText());
			assertEquals("+240k", p.gpText());
			assertEquals("the percentage is unchanged", "+1.3%", p.changeText());
			assertEquals("and so is the stack text", "x12", p.quantityText());
			assertEquals("and so is the tooltip",
				MovementRowPanel.tooltip(r, MovementWindow.D1, THEN_DAY), p.tooltipHtml());
			assertEquals("and so is the rail", ColorScheme.PROGRESS_COMPLETE_COLOR.darker(), p.railColor());
			assertFits("line 2", line2(p));

			// A null ViewOptions is the default reading, and the default is the unit price.
			assertEquals("1.52m", MovementRowPanel.priceText(r, null));
			assertEquals("+20k", MovementRowPanel.gpText(r, null));
		});
	}

	/**
	 * Q6, the two edges of the "nothing to print" rule under the holding reading: a row with no movement has no
	 * gp figure either way, and a stack whose change came out at zero prints nothing beside its own "0.0%" -
	 * asked of the figure that will be DRAWN, not of the unit change behind it.
	 */
	@Test
	public void theHoldingGpFigureFollowsTheSameNothingToPrintRule() throws Exception
	{
		onEdt(() ->
		{
			final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);
			final MovementRow none = row("Coal", 150L, null, null, null);
			assertEquals("", MovementRowPanel.gpText(none, holding));
			assertEquals(MovementMath.DASH, new MovementRowPanel(none, null, MovementWindow.D1, THEN_DAY, holding).changeText());

			final MovementRow flat = row("Coal", 150L, 150L, 0L, 0.0);
			assertEquals("", MovementRowPanel.gpText(flat, holding));

			final MovementRow noPrice = row("Mystery box", null, null, null, null);
			assertEquals("a stack with no price is not worth 0", MovementMath.DASH,
				MovementRowPanel.priceText(noPrice, holding));
		});
	}

	/**
	 * Q6's width case: the widest holding figure the fixture can make ("1.63b", a Twisted bow) and the widest
	 * holding change ("-851k") have to fit the same 156 px box the unit figures fit - and so does every N6
	 * fixture read as a holding, where a stack multiplies both figures.
	 */
	@Test
	public void theWidestHoldingFiguresFitTheSameBoxTheUnitFiguresFit() throws Exception
	{
		onEdt(() ->
		{
			final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);
			final MovementRow bow = stack("Twisted bow", 1, 1_632_000_000L, -58_000_000L, -3.4);
			final MovementRow gauntlets = stack("Confliction gauntlets", 1, 64_300_000L, -851_000L, -1.3);
			assertEquals("1.63b", MovementRowPanel.priceText(bow, holding));
			assertEquals("-851k", MovementRowPanel.gpText(gauntlets, holding));

			final MovementRow huge = stack("Ancient ceremonial legs of the utterly absurd", 28_000,
				2_147_000_000L, 1_073_500_000L, 100.0);
			final MovementRow crash = stack("Karambwan vessel (baited)", 28_000, 2_147_000_000L,
				-999_900_000L, -100.0);
			for (MovementRow r : new MovementRow[]{bow, gauntlets, huge, crash, whip()})
			{
				final MovementRowPanel p = new MovementRowPanel(r, null, MovementWindow.D180, THEN_DAY, holding);
				assertEquals(r.name(), new Dimension(213, 48), p.getPreferredSize());
				assertFits(r.name() + " name", nameLabel(p));
				assertFits(r.name() + " line 2 as a holding", line2(p));
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

	// ---- helpers

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

	/** The text block: the row's only {@link JPanel} child, the picture being a label. */
	private static JPanel textBlock(MovementRowPanel p)
	{
		for (Component c : p.getComponents())
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
