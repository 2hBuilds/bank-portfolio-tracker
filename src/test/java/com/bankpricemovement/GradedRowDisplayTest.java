package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract 1.2.0 part P2, line L5 - what a graded figure looks like on a row and in its open block (the user's picks of
 * 2026-10-07: option a, "a soft row is a solid row with one word under the %"):
 *
 * <ul>
 * <li>the grade WORD stands in line 3's free 54 px slot under the percentage - 11 px, the slot's grey, right-aligned - for a
 * soft row and for a row with no figure, and nowhere else: a solid row, a row nothing graded and an alch row leave the slot
 * empty, exactly as they were drawn before 1.2.0;</li>
 * <li>OPTION A is pinned as pixels: a soft row painted next to the same row as solid differs only inside the word's own box;</li>
 * <li>a row with no figure is the grey dash with no gp figure on either line and "no trades" in the slot;</li>
 * <li>the % is signed and coloured by the MOVE, not by the rounded gp - a cheap item's small rise that rounds to 0 gp is still a
 * rise;</li>
 * <li>the open block's one sentence about both sides, in all its variants, and the last print of each side;</li>
 * <li>the eye: the words stay, every price and amount is a mask.</li>
 * </ul>
 *
 * Synthetic items throughout (no real item ids), and no real prices: the player's bank is private.
 */
public class GradedRowDisplayTest
{
	/** The slot under the percentage: {@code MovementRowPanel.PCT_COLUMN}, which is private - the pictures pin the same 54. */
	private static final int SLOT_WIDTH = 54;
	/** The live day, and the day the window's average is of. */
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);
	private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 6);
	/** 2026-10-07 13:40:00 UTC and 14:02:00 UTC, and 2026-10-06 21:35:00 UTC, in unix seconds. */
	private static final long TODAY_1340 = 1_791_380_400L;
	private static final long TODAY_1402 = 1_791_381_720L;
	private static final long YDAY_2135 = 1_791_322_500L;
	/** One hour, in seconds - the unit of {@link GradeWords#lastTraded}. */
	private static final long HOUR = 3_600L;

	// ---------------------------------------------------------------- fixtures

	private static GradedMove.Side side(Long now, Long then, int observations, Long last, long lastSeconds)
	{
		final Double move = now == null || then == null ? null : now / (double) then - 1.0d;
		return new GradedMove.Side(now, then, move, observations, last, lastSeconds);
	}

	/** The contract's own example: buyers 1,338 vs 2,280 (-41.3 %), sellers 1,250 vs 2,176 (-42.5 %). */
	private static GradedMove.Side buySide()
	{
		return side(1_338L, 2_280L, 6, 1_338L, TODAY_1340);
	}

	private static GradedMove.Side sellSide()
	{
		return side(1_250L, 2_176L, 5, 1_250L, TODAY_1402);
	}

	/** A measured figure of the two example sides: mean move -41.9 %, mark 2,228, price 1,294. */
	private static GradedMove measured(Grade grade, String word)
	{
		return measured(grade, word, buySide(), sellSide(), TODAY, YESTERDAY);
	}

	private static GradedMove measured(Grade grade, String word, GradedMove.Side buy, GradedMove.Side sell,
		LocalDate nowDay, LocalDate thenDay)
	{
		return measured(grade, word, buy, sell, nowDay, thenDay, null);
	}

	/** {@link #measured} for a figure whose current pair is a wide spread of {@code spreadPct} % (null: it is not one). */
	private static GradedMove measured(Grade grade, String word, GradedMove.Side buy, GradedMove.Side sell,
		LocalDate nowDay, LocalDate thenDay, Long spreadPct)
	{
		final double move = ((buy.move() == null ? 0d : buy.move()) + (sell.move() == null ? 0d : sell.move()))
			/ (buy.move() != null && sell.move() != null ? 2d : 1d);
		final long mark = 2_228L;
		return new GradedMove(grade, word, move, mark, Math.round(mark * move), false, buy, sell, nowDay, thenDay, mark,
			spreadPct);
	}

	/** The row a graded move makes, as the service builds one: price = mark + gp, then = mark, % = move x 100. */
	private static MovementRow rowOf(GradedMove graded, int quantity)
	{
		if (!graded.hasFigure())
		{
			return new MovementRow(5001, "Test item", quantity, false, 38_900L, null, null, null, 38_900L * quantity,
				MovementRow.PriceSource.GUIDE, null, null, null, null, 0, 0, 0, 0, graded);
		}
		final long price = graded.price();
		return new MovementRow(5001, "Test item", quantity, false, price, graded.thenMark(), graded.deltaGp(),
			graded.move() * 100.0d, price * quantity, MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0,
			graded);
	}

	/** A row whose figure is a tiny rise on a cheap item: 50 gp then, +0.4 %, which rounds to 0 gp. */
	private static MovementRow cheapRow(double move)
	{
		final GradedMove graded = new GradedMove(Grade.SOFT, GradeWords.volume(1L), move, 50L, Math.round(50L * move),
			false, side(50L, 50L, 1, 50L, TODAY_1340), side(50L, 50L, 1, 50L, TODAY_1340), TODAY, YESTERDAY, 50L);
		return new MovementRow(5002, "Cheap item", 10, false, 50L + Math.round(50L * move), 50L, Math.round(50L * move),
			move * 100.0d, 500L, MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0, graded);
	}

	/** Builds a row on the EDT, the way every Swing component here must be. */
	private static MovementRowPanel build(MovementRow row, boolean hide) throws Exception
	{
		final AtomicReference<MovementRowPanel> out = new AtomicReference<>();
		LookRenderer.onEdt(() -> out.set(new MovementRowPanel(row, null, MovementWindow.D1, null, 0L, ViewOptions.DEFAULT,
			url ->
			{
			}, null, hide)));
		return out.get();
	}

	private static MovementRowPanel build(MovementRow row) throws Exception
	{
		return build(row, false);
	}

	/** {@code body} on the EDT, with its answer. */
	private static <T> T onEdt(Supplier<T> body) throws Exception
	{
		final AtomicReference<T> out = new AtomicReference<>();
		LookRenderer.onEdt(() -> out.set(body.get()));
		return out.get();
	}

	/** Every JLabel under {@code root}, in tree order. */
	private static List<JLabel> labels(Component root)
	{
		final List<JLabel> out = new ArrayList<>();
		collect(root, out);
		return out;
	}

	private static void collect(Component c, List<JLabel> out)
	{
		if (c instanceof JLabel)
		{
			out.add((JLabel) c);
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				collect(child, out);
			}
		}
	}

	private static JLabel labelWithText(Component root, String text)
	{
		for (JLabel l : labels(root))
		{
			if (text.equals(l.getText()))
			{
				return l;
			}
		}
		return null;
	}

	private static JLabel labelStartingWith(Component root, String prefix)
	{
		for (JLabel l : labels(root))
		{
			if (l.getText() != null && l.getText().startsWith(prefix))
			{
				return l;
			}
		}
		return null;
	}

	/** Lays the row out at its own size, EDT. */
	private static void layout(MovementRowPanel row)
	{
		row.setSize(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT);
		LookRenderer.layoutTree(row);
	}

	private static BufferedImage paint(MovementRowPanel row)
	{
		layout(row);
		final BufferedImage image = new BufferedImage(MovementRowPanel.ROW_WIDTH, MovementRowPanel.ROW_HEIGHT,
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			row.printAll(g);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/** The box {@code label} is painted in, in the row's own coordinates. */
	private static Rectangle boxIn(MovementRowPanel row, JLabel label)
	{
		return SwingUtilities.convertRectangle(label.getParent(), label.getBounds(), row);
	}

	private static String blockOf(MovementRow row, MovementWindow window, boolean hide)
	{
		return hide ? MovementRowPanel.maskedDetail(row, window, null, 0L, ViewOptions.DEFAULT, false)
			: MovementRowPanel.detail(row, window, null, 0L, ViewOptions.DEFAULT, false);
	}

	// ---------------------------------------------------------------- the slot

	/**
	 * Every word a figure can carry fits the 54 px slot WHOLE - measured with the same font and metrics the label paints with -
	 * including the widest the parametrised words can get (the percentage of a spread, the units on a thin day, the hours or days
	 * since the last trade). The 11 px face holds some of them ("low vol 3", the plain "spread", "no trades", "last 5h ago",
	 * "last 2d ago"); "spread 55%" (59 there, 55 at 10, 49 at 9), "spread 100%" (65, 61 and exactly 54 at 9 px - the widest, any
	 * three-digit percentage being as wide) and "last 10h ago" to "last 23h ago" (59 there, 58 at 10) are too wide for it and for
	 * 10 px, so they step down to 9 - which is the rule for any word that is too wide (the row draws the largest face that
	 * fits, 10 px between). So the test asserts what is true on every platform - each word fits at 9 px, the ones the user named
	 * fit at 11 or are drawn at the largest face that holds them, and a built row draws each one whole - and not a table of
	 * widths measured on one machine.
	 */
	@Test
	public void everyWordFitsTheSlotWhole() throws Exception
	{
		final List<String> words = Arrays.asList(GradeWords.SPREAD, GradeWords.spread(10L), GradeWords.spread(55L),
			GradeWords.spread(99L), GradeWords.spread(100L), GradeWords.spread(160L), GradeWords.YDAY, GradeWords.NO_TRADES,
			GradeWords.volume(0L), GradeWords.volume(3L), GradeWords.volume(9L), GradeWords.lastTraded(0L),
			GradeWords.lastTraded(5L * HOUR), GradeWords.lastTraded(9L * HOUR), GradeWords.lastTraded(10L * HOUR),
			GradeWords.lastTraded(23L * HOUR), GradeWords.lastTraded(24L * HOUR), GradeWords.lastTraded(2L * 24L * HOUR),
			GradeWords.lastTraded(9L * 24L * HOUR), GradeWords.lastTraded(12L * 24L * HOUR),
			GradeWords.lastTraded(Long.MAX_VALUE));
		final FontMetrics at11 = onEdt(() -> new JLabel().getFontMetrics(Widgets.sans(11)));
		final FontMetrics at10 = onEdt(() -> new JLabel().getFontMetrics(Widgets.sans(10)));
		final FontMetrics at9 = onEdt(() -> new JLabel().getFontMetrics(Widgets.sans(9)));
		for (String word : words)
		{
			assertTrue(word + " measures " + at9.stringWidth(word) + " at 9 px", at9.stringWidth(word) <= SLOT_WIDTH);
			final GradedMove soft = new GradedMove(Grade.SOFT, word, 0.05d, 1_000L, 50L, false, buySide(), sellSide(),
				TODAY, YESTERDAY, 1_000L);
			final MovementRowPanel row = build(rowOf(soft, 1));
			final JLabel label = labelWithText(row, word);
			assertNotNull("\"" + word + "\" is printed whole, not cut", label);
			assertNull("and with no hover of its own: a row is silent", label.getToolTipText());
			assertEquals(word + " is drawn in the largest face that holds it",
				at11.stringWidth(word) <= SLOT_WIDTH ? Widgets.sans(11)
					: at10.stringWidth(word) <= SLOT_WIDTH ? Widgets.sans(10) : Widgets.sans(9), label.getFont());
		}
		// The words the contract names for the 11 px slot.
		for (String named : new String[]{GradeWords.volume(3L), GradeWords.volume(9L), GradeWords.SPREAD, GradeWords.NO_TRADES,
			GradeWords.YDAY, GradeWords.lastTraded(5L * HOUR), GradeWords.lastTraded(9L * HOUR),
			GradeWords.lastTraded(2L * 24L * HOUR)})
		{
			assertTrue(named + " measures " + at11.stringWidth(named) + " at 11 px", at11.stringWidth(named) <= SLOT_WIDTH);
		}
		// The spread word is wider than the 11 px slot, which is the point of the step-down: "spread 55%" is drawn smaller.
		assertTrue("spread 55% measures " + at11.stringWidth(GradeWords.spread(55L)) + " at 11 px",
			at11.stringWidth(GradeWords.spread(55L)) > SLOT_WIDTH);
		assertEquals("the widest, at 9 px", SLOT_WIDTH, at9.stringWidth(GradeWords.spread(100L)));
		// The words' own texts.
		assertEquals("low vol 3", GradeWords.volume(3L));
		assertEquals("low vol 0", GradeWords.volume(-5L));
		assertEquals("spread 55%", GradeWords.spread(55L));
		assertEquals("spread 0%", GradeWords.spread(-3L));
		assertEquals("spread", GradeWords.SPREAD);
		assertTrue(GradeWords.isSpread("spread 55%") && GradeWords.isSpread(GradeWords.SPREAD));
		assertFalse(GradeWords.isSpread("low vol 3") || GradeWords.isSpread(GradeWords.SOLID) || GradeWords.isSpread(null));
	}

	/** The word's place and look: 11 px, the slot's grey (line 3's own), right-aligned, 54 px wide, under the percentage. */
	@Test
	public void aSoftRowPrintsItsWordInTheSlotUnderThePercentage() throws Exception
	{
		final MovementRowPanel row = build(rowOf(measured(Grade.SOFT, GradeWords.volume(3L)), 5));
		onEdt(() ->
		{
			layout(row);
			return null;
		});
		final JLabel word = labelWithText(row, "low vol 3");
		assertNotNull(word);
		assertEquals("11 px", Widgets.sans(11), word.getFont());
		assertEquals("the working's own grey", ColorScheme.LIGHT_GRAY_COLOR, word.getForeground());
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, labelWithText(row, "5 x 1,294").getForeground());
		assertEquals(SwingConstants.RIGHT, word.getHorizontalAlignment());
		final JLabel pct = labelWithText(row, row.changeText());
		final Rectangle slot = boxIn(row, word);
		final Rectangle pctBox = boxIn(row, pct);
		assertEquals("the slot is as wide as the percentage's column", SLOT_WIDTH, slot.width);
		assertEquals("...and under it: the same right edge", pctBox.x + pctBox.width, slot.x + slot.width);
		assertTrue("...on the line below (" + pctBox + " over " + slot + ")", slot.y >= pctBox.y + pctBox.height - 2);
		assertEquals("the row is the 62 px card it always was", MovementRowPanel.ROW_HEIGHT,
			onEdt(() -> row.getPreferredSize().height).intValue());
	}

	/** A solid row, a row nothing graded and an alch row leave the slot empty - no label at all stands in it. */
	@Test
	public void aSolidRowAnUngradedRowAndAnAlchRowPrintNoWord() throws Exception
	{
		for (String word : Arrays.asList(GradeWords.spread(55L), GradeWords.YDAY, GradeWords.NO_TRADES, "low vol 3", "last 5h ago",
			"last 2d ago"))
		{
			final MovementRowPanel solid = build(rowOf(measured(Grade.SOLID, GradeWords.SOLID), 5));
			assertNull("a solid row has no word", labelWithText(solid, word));
			final MovementRow plain = new MovementRow(5003, "Plain", 5, false, 1_520L, 1_500L, 20L, 1.3d, 7_600L, null);
			assertNull("an ungraded row has none", labelWithText(build(plain), word));
		}
		assertEquals("", MovementRowPanel.slotWord(rowOf(measured(Grade.SOLID, GradeWords.SOLID), 1)));
		assertEquals("", MovementRowPanel.slotWord(new MovementRow(5003, "Plain", 5, false, 1_520L, 1_500L, 20L, 1.3d,
			7_600L, null)));
		assertEquals("", MovementRowPanel.slotWord(new MovementRow(5004, "Hood", 1, false, 20_000L, null, null, null,
			20_000L, MovementRow.PriceSource.ALCH, null, null, null, null, 0, 0, 0, 0,
			new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null, null, null, null))));
		assertEquals("a SOLID figure prints no word even if its move carries one", "",
			MovementRowPanel.slotWord(rowOf(measured(Grade.SOLID, "low vol 3"), 1)));
		assertEquals("low vol 3", MovementRowPanel.slotWord(rowOf(measured(Grade.SOFT, "low vol 3"), 1)));
		assertEquals(GradeWords.NO_TRADES, MovementRowPanel.slotWord(rowOf(
			new GradedMove(Grade.NONE, null, null, null, null, false, null, null, null, null, null), 1)));
	}

	/**
	 * OPTION A, pinned as pixels (the user: "users will know its not a solid stat but still see it"). A soft row painted beside
	 * the same row as solid differs ONLY inside the word's own box - the bold %, its colour, the digits, the gp figures, the
	 * rail, the picture cell, the name, the card, every pixel is the solid row's - and inside that box it does differ. And a soft
	 * row whose word is blank is the solid row to the last pixel, so there is no other way the row can tell.
	 */
	@Test
	public void aSoftRowIsASolidRowWithOnlyTheWordAdded() throws Exception
	{
		for (Grade grade : new Grade[]{Grade.SOFT})
		{
			for (String word : Arrays.asList("low vol 3", GradeWords.spread(55L), GradeWords.spread(100L), GradeWords.SPREAD,
				GradeWords.YDAY, "last 5h ago", "last 23h ago", "last 2d ago"))
			{
				final MovementRowPanel solid = build(rowOf(measured(Grade.SOLID, GradeWords.SOLID), 5));
				final MovementRowPanel soft = build(rowOf(measured(grade, word), 5));
				final BufferedImage a = onEdt(() -> paint(solid));
				final BufferedImage b = onEdt(() -> paint(soft));
				final Rectangle box = boxIn(soft, labelWithText(soft, word));
				assertEquals("the same card size", a.getHeight(), b.getHeight());
				boolean differsInside = false;
				for (int y = 0; y < a.getHeight(); y++)
				{
					for (int x = 0; x < a.getWidth(); x++)
					{
						if (a.getRGB(x, y) == b.getRGB(x, y))
						{
							continue;
						}
						assertTrue(word + ": pixel (" + x + ", " + y + ") changed outside the word's box " + box,
							box.contains(x, y));
						differsInside = true;
					}
				}
				assertTrue(word + ": the word is drawn", differsInside);
				assertEquals("the text of every figure is the solid row's", onEdt(() -> solid.changeText()),
					onEdt(() -> soft.changeText()));
				assertEquals(onEdt(() -> solid.gpText()), onEdt(() -> soft.gpText()));
				assertEquals(onEdt(() -> solid.priceText()), onEdt(() -> soft.priceText()));
				assertEquals(onEdt(() -> solid.changeColor()), onEdt(() -> soft.changeColor()));
				assertEquals(onEdt(() -> solid.railColor()), onEdt(() -> soft.railColor()));
			}
		}
		// A soft row with its word blanked IS the solid row.
		final MovementRowPanel solid = build(rowOf(measured(Grade.SOLID, GradeWords.SOLID), 5));
		final MovementRowPanel blank = build(rowOf(measured(Grade.SOFT, ""), 5));
		final BufferedImage a = onEdt(() -> paint(solid));
		final BufferedImage b = onEdt(() -> paint(blank));
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				assertEquals("pixel (" + x + ", " + y + ")", a.getRGB(x, y), b.getRGB(x, y));
			}
		}
	}

	/**
	 * The word sits on line 3's baseline at every size it is drawn at (the display review): a word stepped down to 10 or 9 px
	 * used to be centred in the line's height and ended a pixel or two ABOVE the working's baseline. Painted: the bottom row of
	 * the word's ink is the bottom row of the working's ("5 x 232", no comma, no descender) for a word at 11 px ("low vol 3",
	 * "last 2d ago") and at 9 px ("spread 100%", the widest word, which measures 65 at 11 px and 61 at 10 in the face this
	 * plugin draws in, and "last 23h ago", 59 and 58). The ink bottom of the letters above the baseline IS the baseline, so a
	 * word with a letter that hangs below it - the "p" of "spread", the "g" of "ago" - is measured over its longest run of
	 * letters that do not ({@link #aboveTheBaseline}). The premise that they really are the sizes named is asserted from the
	 * labels' fonts. (No word the grade says lands on 10 px in this face; the step stays in the row for a platform whose face
	 * is a pixel wider.)
	 */
	@Test
	public void aWordSteppedDownSitsOnTheWorkingsBaseline() throws Exception
	{
		final int[] sizes = new int[4];
		int at = 0;
		for (String word : new String[]{"low vol 3", GradeWords.spread(100L), GradeWords.lastTraded(23L * HOUR),
			GradeWords.lastTraded(2L * 24L * HOUR)})
		{
			final MovementRowPanel panel = build(rowOf(measuredAtMark(Grade.SOFT, word, 228L), 5));
			final JLabel working = labelStartingWith(panel, "5 x ");
			final JLabel wordLabel = labelWithText(panel, word);
			assertNotNull("the working", working);
			assertNotNull("the word", wordLabel);
			sizes[at++] = wordLabel.getFont().getSize();
			final BufferedImage image = onEdt(() -> paint(panel));
			final int workingBottom = inkBottom(image, boxIn(panel, working));
			final int wordBottom = inkBottom(image, aboveTheBaseline(wordLabel, word, boxIn(panel, wordLabel)));
			assertTrue(word + ": the working is drawn", workingBottom >= 0);
			assertEquals(word + ": ink bottom of the word is the working's", workingBottom, wordBottom);
		}
		assertEquals("the words are drawn at 11, 9, 9 and 11 px", Arrays.asList(11, 9, 9, 11),
			Arrays.asList(sizes[0], sizes[1], sizes[2], sizes[3]));
	}

	/**
	 * The part of a right-aligned word's box that holds only letters standing on the baseline: its longest run of characters
	 * with none that hangs below it (g, j, p, q, y), or the whole box when no letter hangs. "last 23h ago" hangs only at the "g"
	 * of "ago" (its run is "last 23h a"), "spread 100%" only at the "p" (its run is "read 100%"), and the ink bottom of the
	 * whole word would be that tail's, not the baseline's.
	 */
	private static Rectangle aboveTheBaseline(JLabel label, String word, Rectangle box) throws Exception
	{
		int bestStart = 0;
		int bestLength = 0;
		int start = 0;
		boolean anyHangs = false;
		for (int i = 0; i <= word.length(); i++)
		{
			final boolean hangs = i < word.length() && "gjpqy".indexOf(word.charAt(i)) >= 0;
			anyHangs |= hangs;
			if (i == word.length() || hangs)
			{
				if (i - start > bestLength)
				{
					bestStart = start;
					bestLength = i - start;
				}
				start = i + 1;
			}
		}
		if (!anyHangs)
		{
			return box;
		}
		final FontMetrics metrics = onEdt(() -> label.getFontMetrics(label.getFont()));
		final int textStart = box.x + box.width - metrics.stringWidth(word);
		final int from = textStart + metrics.stringWidth(word.substring(0, bestStart));
		final int width = Math.max(1, metrics.stringWidth(word.substring(bestStart, bestStart + bestLength)) - 1);
		return new Rectangle(from, box.y, width, box.height);
	}

	/** {@link #measured} at a mark of the test's choosing, so the working's price needs no thousands comma. */
	private static GradedMove measuredAtMark(Grade grade, String word, long mark)
	{
		final GradedMove.Side buy = buySide();
		final GradedMove.Side sell = sellSide();
		final double move = (buy.move() + sell.move()) / 2d;
		return new GradedMove(grade, word, move, mark, Math.round(mark * move), false, buy, sell, TODAY, YESTERDAY, mark);
	}

	/**
	 * The lowest row, in the image's own coordinates, with a strong ink pixel inside {@code box}: a pixel at least half way
	 * from the box's background (its commonest colour) to the grey the labels are drawn in. -1 when there is none.
	 */
	private static int inkBottom(BufferedImage image, Rectangle box)
	{
		final java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
		for (int y = box.y; y < box.y + box.height; y++)
		{
			for (int x = box.x; x < box.x + box.width; x++)
			{
				counts.merge(image.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
			}
		}
		int background = 0;
		int most = -1;
		for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet())
		{
			if (entry.getValue() > most)
			{
				most = entry.getValue();
				background = entry.getKey();
			}
		}
		final int backgroundGrey = (background >> 8) & 0xFF;
		final int textGrey = ColorScheme.LIGHT_GRAY_COLOR.getGreen();
		final int threshold = Math.abs(textGrey - backgroundGrey) / 2;
		for (int y = box.y + box.height - 1; y >= box.y; y--)
		{
			for (int x = box.x; x < box.x + box.width; x++)
			{
				if (Math.abs(((image.getRGB(x, y) >> 8) & 0xFF) - backgroundGrey) >= threshold)
				{
					return y;
				}
			}
		}
		return -1;
	}

	/**
	 * A row with no figure (L5): the existing grey dash, NO gp figure on either line - the stack's move and one item's are
	 * both blank - no rail, the guide-priced stack value and working as ever, and "no trades" in the slot.
	 */
	@Test
	public void aRowWithNoFigureIsTheGreyDashWithNoGpFiguresAndNoTrades() throws Exception
	{
		final GradedMove none = new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null,
			null, null, 38_900L);
		final MovementRow row = rowOf(none, 3);
		final MovementRowPanel panel = build(row);
		assertEquals("the dash", MovementMath.DASH, onEdt(() -> panel.changeText()));
		assertEquals("grey", ColorScheme.LIGHT_GRAY_COLOR, onEdt(() -> panel.changeColor()));
		assertEquals("no rail", ColorScheme.DARKER_GRAY_COLOR, onEdt(() -> panel.railColor()));
		assertEquals("no stack move", "", onEdt(() -> panel.gpText()));
		assertEquals("the stack is worth its guide price", "116k", onEdt(() -> panel.priceText()));
		assertNotNull("the working is as ever", labelWithText(panel, "3 x 38.9k"));
		assertNotNull("and the word says why", labelWithText(panel, "no trades"));
		for (JLabel l : labels(panel))
		{
			final String text = l.getText();
			assertFalse("no signed figure anywhere on the row: " + text, text != null && text.matches("[+-][0-9].*"));
		}
		// ...and the open block: the dash for the change, no day it was compared against, and why in a sentence.
		final String block = blockOf(row, MovementWindow.D1, false);
		assertTrue(block, block.contains("Change 1d"));
		assertTrue(block, block.contains(MovementRowPanel.PROVENANCE_NONE));
		assertFalse("nothing was compared against a day", block.contains("(" + MovementMath.formatDay(YESTERDAY) + ")"));
	}

	/**
	 * The sign, the colour and the rail follow the MOVE (the P1 report): a cheap item's small rise rounds to 0 gp while the
	 * move is +0.4 %, and the row must read "+0.4%" in the rise's green, not an unsigned grey "0.4%"; a small fall reads "-0.4%"
	 * in red; only a move of exactly zero is the grey "0.0%". The gp figures are blank at 0, as the blank-zero rule has it.
	 * A row nothing graded keeps the old rule - its gp change signs it.
	 */
	@Test
	public void theSignAndColourComeFromTheMoveNotTheRoundedGp() throws Exception
	{
		final MovementRowPanel up = build(cheapRow(0.004d));
		assertEquals("+0.4%", onEdt(() -> up.changeText()));
		assertEquals(MovementRowPanel.textChangeColor(cheapRow(0.004d)), onEdt(() -> up.changeColor()));
		assertEquals(Widgets.move(1, Widgets.Kind.FIGURE), onEdt(() -> up.changeColor()));
		assertEquals("the rail is the rise's", Widgets.move(1, Widgets.Kind.EDGE), onEdt(() -> up.railColor()));
		assertEquals("0 gp is blank, not a zero", "", onEdt(() -> up.gpText()));
		assertEquals(Long.valueOf(0L), cheapRow(0.004d).deltaGp());

		final MovementRowPanel down = build(cheapRow(-0.004d));
		assertEquals("-0.4%", onEdt(() -> down.changeText()));
		assertEquals(Widgets.move(-1, Widgets.Kind.FIGURE), onEdt(() -> down.changeColor()));
		assertEquals(Widgets.move(-1, Widgets.Kind.EDGE), onEdt(() -> down.railColor()));

		final MovementRowPanel flat = build(cheapRow(0.0d));
		assertEquals("0.0%", onEdt(() -> flat.changeText()));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, onEdt(() -> flat.changeColor()));
		assertEquals(ColorScheme.DARKER_GRAY_COLOR, onEdt(() -> flat.railColor()));

		// The open block says the same: signed by the move.
		assertTrue(blockOf(cheapRow(0.004d), MovementWindow.D1, false), blockOf(cheapRow(0.004d), MovementWindow.D1, false)
			.contains("0 each&nbsp; +0.4%"));

		// The hover text's "Change per item" line is signed by the move as well.
		final String tip = MovementRowPanel.tooltip(cheapRow(0.004d), MovementWindow.D1, null);
		assertTrue(tip, tip.contains("Change per item: 0 gp (+0.4%)"));
		final String tipDown = MovementRowPanel.tooltip(cheapRow(-0.004d), MovementWindow.D1, null);
		assertTrue(tipDown, tipDown.contains("Change per item: 0 gp (-0.4%)"));

		// Nothing graded: the gp change signs the percentage, as since L2 - a 0 gp row with a 0.4 % is the grey "0.4%".
		final MovementRow legacy = new MovementRow(5005, "Legacy", 10, false, 50L, 50L, 0L, 0.4d, 500L, null);
		assertEquals("0.4%", MovementRowPanel.changeText(legacy));
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, MovementRowPanel.textChangeColor(legacy));
	}

	// ---------------------------------------------------------------- the open block

	/** The five variants of L5, and the one-side form, exactly as built. */
	@Test
	public void theOpenBlocksSentenceHasItsVariants()
	{
		// 1. both sides, agreeing.
		assertEquals("Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41.3%); sellers got 1,250 vs 2,176 (-42.5%)."
			+ " Both sides agree.", MovementRowPanel.provenance(measured(Grade.SOLID, ""), MovementWindow.D1, false));

		// 2. both sides, disagreeing: sellers up 10.2 % against buyers down 41.3 %.
		final GradedMove apart = measured(Grade.SOFT, GradeWords.volume(3L), buySide(),
			side(2_400L, 2_176L, 5, 2_400L, TODAY_1402), TODAY, YESTERDAY);
		assertEquals("Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41.3%); sellers got 2,400 vs 2,176 (+10.2%)."
			+ " The sides disagree, so the move is uncertain.", MovementRowPanel.provenance(apart, MovementWindow.D1, false));

		// 3. one side observed in the last 24 hours, each way round: priced at that side alone, solid or soft by the usual facts.
		final GradedMove buyOnly = measured(Grade.SOLID, GradeWords.SOLID, buySide(), side(null, 2_176L, 0, null, 0L),
			TODAY, YESTERDAY);
		assertEquals("Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41.3%); no sells in the last 24 hours.",
			MovementRowPanel.provenance(buyOnly, MovementWindow.D1, false));
		final GradedMove sellOnly = measured(Grade.SOLID, GradeWords.SOLID, side(null, 2_280L, 0, null, 0L), sellSide(),
			TODAY, YESTERDAY);
		assertEquals("Sellers got 1,250 in the last 24 hours vs 2,176 on average yesterday (-42.5%); no buys in the last 24 hours.",
			MovementRowPanel.provenance(sellOnly, MovementWindow.D1, false));

		// 4. nothing in the last 24 hours: yesterday's move, with the two days.
		final GradedMove yday = new GradedMove(Grade.SOFT, GradeWords.lastTraded(18L * HOUR), -0.05d, 2_228L, -111L, true,
			side(2_300L, 2_400L, 0, null, 0L), side(2_200L, 2_300L, 0, null, 0L), YESTERDAY, YESTERDAY.minusDays(1L), 2_228L);
		assertEquals("No trades in the last 24 hours; this is yesterday's move (06 Oct vs 05 Oct).",
			MovementRowPanel.provenance(yday, MovementWindow.D1, false));

		// 5. nothing at all.
		final GradedMove none = new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false, null, null, null,
			null, null);
		assertEquals("No trades in the last 24 hours or yesterday; valued at the guide price.",
			MovementRowPanel.provenance(none, MovementWindow.D1, false));
		assertEquals("no trades for the longer windows says what is missing",
			"No trades to compare with over 30d, so there is no figure for it.",
			MovementRowPanel.provenance(none, MovementWindow.D30, false));
	}

	/**
	 * The spread clause (the row says "spread 30%" - the user's last wording, 2026-10-08 - and the open block explains it): a row
	 * whose word is the spread word closes its sentence with "The buy and sell prices are 30% apart." - the number the figure
	 * carries, {@link GradedMove#spreadPercent}, the current pair's gap as a whole percentage of its middle - and no other row
	 * says it, whatever its prints or its number; a figure that carries none (the plain "spread" of a price outside the
	 * anchor's range, which has no current pair) leaves the clause out; and the eye leaves the percentage, which is no amount.
	 */
	@Test
	public void theOpenBlockSaysTheSpreadOnlyForASpreadRow()
	{
		final GradedMove.Side wideBuy = side(1_338L, 2_280L, 6, 1_150L, TODAY_1340);
		final GradedMove.Side wideSell = side(1_250L, 2_176L, 5, 850L, TODAY_1402);
		final String sides = "Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41.3%); sellers got 1,250 vs 2,176 (-42.5%).";

		// 1,150 against 850: 300 apart over a middle of 1,000.
		final GradedMove spread = measured(Grade.SOFT, GradeWords.spread(30L), wideBuy, wideSell, TODAY, YESTERDAY, 30L);
		assertEquals(sides + " Both sides agree. The buy and sell prices are 30% apart.",
			MovementRowPanel.provenance(spread, MovementWindow.D1, false));

		// The sides disagreeing keeps its own ending, and the clause follows it.
		final GradedMove apart = measured(Grade.SOFT, GradeWords.spread(30L), wideBuy, side(2_400L, 2_176L, 5, 850L, TODAY_1402),
			TODAY, YESTERDAY, 30L);
		assertEquals("Buyers paid 1,338 in the last 24 hours vs 2,280 on average yesterday (-41.3%); sellers got 2,400 vs 2,176 (+10.2%)."
			+ " The sides disagree, so the move is uncertain. The buy and sell prices are 30% apart.",
			MovementRowPanel.provenance(apart, MovementWindow.D1, false));

		// The same prints and number under any other word, and a solid figure, say nothing of a spread.
		for (String word : new String[]{GradeWords.volume(3L), GradeWords.YDAY, GradeWords.SOLID})
		{
			final GradedMove other = measured(word.isEmpty() ? Grade.SOLID : Grade.SOFT, word, wideBuy, wideSell, TODAY,
				YESTERDAY, 30L);
			assertEquals(word + ": no spread clause", sides + " Both sides agree.",
				MovementRowPanel.provenance(other, MovementWindow.D1, false));
		}

		// The number is the figure's own: 18 here, whatever the last prints on its sides say.
		final GradedMove eighteen = measured(Grade.SOFT, GradeWords.spread(18L), wideBuy, wideSell, TODAY, YESTERDAY, 18L);
		assertTrue(MovementRowPanel.provenance(eighteen, MovementWindow.D1, false),
			MovementRowPanel.provenance(eighteen, MovementWindow.D1, false).endsWith(" The buy and sell prices are 18% apart."));

		// A figure with no number (the plain "spread") leaves the clause out rather than inventing one from last prints that
		// may be a day old.
		final GradedMove noNumber = measured(Grade.SOFT, GradeWords.SPREAD, wideBuy, wideSell, TODAY, YESTERDAY);
		assertNull(noNumber.spreadPercent());
		assertEquals(sides + " Both sides agree.", MovementRowPanel.provenance(noNumber, MovementWindow.D1, false));

		// The eye masks the prices and leaves the percentage; the open block carries the same sentence.
		final String masked = MovementRowPanel.provenance(spread, MovementWindow.D1, true);
		assertTrue(masked, masked.endsWith(" Both sides agree. The buy and sell prices are 30% apart."));
		assertFalse(masked, masked.contains("1,338") || masked.contains("1,150") || masked.contains("850"));
		final MovementRow row = rowOf(spread, 3);
		final String shownBlock = blockOf(row, MovementWindow.D1, false);
		assertTrue(shownBlock, shownBlock.contains(Widgets.escapeHtml(MovementRowPanel.provenance(spread, MovementWindow.D1, false))));
		assertTrue(shownBlock, shownBlock.contains("30% apart."));
		assertTrue(blockOf(row, MovementWindow.D1, true), blockOf(row, MovementWindow.D1, true).contains("30% apart."));
	}

	/** A longer window names its day instead of "yesterday", and a 1d day that is not yesterday is named too. */
	@Test
	public void aLongerWindowNamesItsDayInsteadOfYesterday()
	{
		final GradedMove thirty = measured(Grade.SOLID, "", buySide(), sellSide(), TODAY, LocalDate.of(2026, 9, 30));
		assertEquals("Buyers paid 1,338 in the last 24 hours vs 2,280 on average on 30 Sep (-41.3%); sellers got 1,250 vs 2,176 (-42.5%)."
			+ " Both sides agree.", MovementRowPanel.provenance(thirty, MovementWindow.D30, false));
		final GradedMove older = measured(Grade.SOLID, "", buySide(), sellSide(), TODAY, TODAY.minusDays(2L));
		assertTrue(MovementRowPanel.provenance(older, MovementWindow.D1, false), MovementRowPanel.provenance(older,
			MovementWindow.D1, false).contains("on average on 05 Oct"));
		final GradedMove yday = new GradedMove(Grade.SOFT, GradeWords.YDAY, -0.05d, 2_228L, -111L, true,
			side(2_300L, 2_400L, 0, null, 0L), side(2_200L, 2_300L, 0, null, 0L), YESTERDAY, LocalDate.of(2026, 9, 30), 2_228L);
		assertEquals("No trades in the last 24 hours; this is the move up to yesterday (06 Oct vs 30 Sep).",
			MovementRowPanel.provenance(yday, MovementWindow.D30, false));
		assertEquals("a null window reads as 1d", MovementRowPanel.provenance(measured(Grade.SOLID, ""), MovementWindow.D1,
			false), MovementRowPanel.provenance(measured(Grade.SOLID, ""), null, false));
	}

	/** The last print of each side with its time, UTC, the date as well when it is not the figure's own day. */
	@Test
	public void theLastPrintOfEachSideIsNamedWithItsTime()
	{
		assertEquals("Last bought 1,338 at 13:40 UTC; last sold 1,250 at 14:02 UTC.",
			MovementRowPanel.lastPrints(measured(Grade.SOLID, ""), false));

		// A print from an earlier day carries its date; a side with no print is left out; none at all adds no line.
		final GradedMove older = measured(Grade.SOFT, GradeWords.volume(1L), side(1_338L, 2_280L, 1, 1_338L, YDAY_2135),
			side(1_250L, 2_176L, 1, null, 0L), TODAY, YESTERDAY);
		assertEquals("Last bought 1,338 at 06 Oct 21:35 UTC.", MovementRowPanel.lastPrints(older, false));
		final GradedMove silent = measured(Grade.SOFT, GradeWords.volume(1L), side(1_338L, 2_280L, 1, null, 0L),
			side(1_250L, 2_176L, 1, null, 0L), TODAY, YESTERDAY);
		assertEquals("", MovementRowPanel.lastPrints(silent, false));

		// A row with no figure still names the last prints, with their dates: there is no day of its own to compare them with.
		final GradedMove none = new GradedMove(Grade.NONE, GradeWords.NO_TRADES, null, null, null, false,
			side(null, null, 0, 1_338L, YDAY_2135), side(null, null, 0, null, 0L), null, null, null);
		assertEquals("Last bought 1,338 at 06 Oct 21:35 UTC.", MovementRowPanel.lastPrints(none, false));
	}

	/** The block carries both, after the table and in that order, escaped like every note. */
	@Test
	public void theBlockCarriesTheSentenceAndTheLastPrintsUnderTheTable()
	{
		final MovementRow row = rowOf(measured(Grade.SOLID, ""), 3);
		final String html = blockOf(row, MovementWindow.D1, false);
		final String sentence = Widgets.escapeHtml(MovementRowPanel.provenance(row.graded(), MovementWindow.D1, false));
		final String last = Widgets.escapeHtml(MovementRowPanel.lastPrints(row.graded(), false));
		assertTrue(html, html.contains("</table>" + sentence + "<br>" + last + "</div></html>"));
		assertTrue("the apostrophe is escaped like any other note's", blockOf(rowOf(new GradedMove(Grade.NONE, null, null,
			null, null, false, null, null, null, null, null), 1), MovementWindow.D1, false)
			.contains("valued at the guide price."));
		// Switched off, the block is the four lines.
		assertTrue(MovementRowPanel.detail(row, MovementWindow.D1, null, 0L, ViewOptions.DEFAULT.withLivePrices(false), false)
			.endsWith("</table></div></html>"));
		// A row nothing graded has no sentence.
		assertTrue(blockOf(new MovementRow(5003, "Plain", 5, false, 1_520L, 1_500L, 20L, 1.3d, 7_600L, null),
			MovementWindow.D1, false).endsWith("</table></div></html>"));
	}

	// ---------------------------------------------------------------- the eye

	/**
	 * Hide amounts: the words are not amounts and STAY, while the stack value, both gp figures, line 3's working and every price
	 * in the open block's sentence read their masks - the block's own "Worth now" is masked, so a sentence printing the same
	 * price would undo it. The percentages, the days and the sentence's words stay.
	 */
	@Test
	public void theEyeKeepsTheWordsAndMasksEveryAmount() throws Exception
	{
		final MovementRow row = rowOf(measured(Grade.SOFT, GradeWords.volume(3L)), 5);
		final MovementRowPanel panel = build(row, true);
		assertNotNull("the word stays", labelWithText(panel, "low vol 3"));
		assertEquals("the stack value is a mask", AmountMask.AMOUNT, onEdt(() -> panel.priceText()));
		assertEquals("the stack move is a mask", AmountMask.SHORT, onEdt(() -> panel.gpText()));
		assertEquals("the percentage stays", MovementRowPanel.changeText(row), onEdt(() -> panel.changeText()));
		assertNotNull("line 3's working is the quantity's mask", labelWithText(panel, AmountMask.SHORT));

		final String shown = MovementRowPanel.provenance(row.graded(), MovementWindow.D1, false);
		final String masked = MovementRowPanel.provenance(row.graded(), MovementWindow.D1, true);
		assertEquals("Buyers paid " + AmountMask.AMOUNT + " in the last 24 hours vs " + AmountMask.AMOUNT + " on average yesterday (-41.3%);"
			+ " sellers got " + AmountMask.AMOUNT + " vs " + AmountMask.AMOUNT + " (-42.5%). Both sides agree.", masked);
		assertTrue(shown, shown.contains("1,338"));
		assertFalse(masked, masked.contains("1,338") || masked.contains("2,280") || masked.contains("1,250"));
		assertEquals("Last bought " + AmountMask.AMOUNT + " at 13:40 UTC; last sold " + AmountMask.AMOUNT + " at 14:02 UTC.",
			MovementRowPanel.lastPrints(row.graded(), true));
		final String html = blockOf(row, MovementWindow.D1, true);
		assertFalse(html, html.contains("1,338") || html.contains("2,280") || html.contains("1,250") || html.contains("2,228"));
		assertTrue(html, html.contains(Widgets.escapeHtml(masked)));
	}

	/** The pixel check at the eye's door: hiding the amounts moves nothing about the word - the same box, the same ink. */
	@Test
	public void theWordIsTheSamePixelsWithTheAmountsHidden() throws Exception
	{
		final MovementRow row = rowOf(measured(Grade.SOFT, GradeWords.volume(3L)), 5);
		final MovementRowPanel shown = build(row, false);
		final MovementRowPanel hidden = build(row, true);
		final BufferedImage a = onEdt(() -> paint(shown));
		final BufferedImage b = onEdt(() -> paint(hidden));
		final Rectangle box = boxIn(shown, labelWithText(shown, "low vol 3"));
		assertEquals(box, boxIn(hidden, labelWithText(hidden, "low vol 3")));
		for (int y = box.y; y < box.y + box.height; y++)
		{
			for (int x = box.x; x < box.x + box.width; x++)
			{
				assertEquals("the word at (" + x + ", " + y + ")", a.getRGB(x, y), b.getRGB(x, y));
			}
		}
	}

	/** The colour of a word never depends on the move: a fall's word is the same grey as a rise's. */
	@Test
	public void theWordIsTheSameGreyOnARiseAndAFall() throws Exception
	{
		final MovementRowPanel up = build(cheapRow(0.2d));
		final MovementRowPanel down = build(cheapRow(-0.2d));
		final Color a = labelWithText(up, GradeWords.volume(1L)).getForeground();
		final Color b = labelWithText(down, GradeWords.volume(1L)).getForeground();
		assertEquals(a, b);
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, a);
	}
}
