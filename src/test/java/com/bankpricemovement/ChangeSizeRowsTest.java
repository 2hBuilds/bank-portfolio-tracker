package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Release 1.2.1 (contract {@code docs/handoff/contract-1.2.1-change-size-and-brightness-2026-10-24.md}, S2 and S4): what the
 * three change settings do to an Items ROW and to nothing else - the gp figures' size, the percentage's size and the gp figures'
 * brightness. Every size step is measured: its two columns are the widest text a row can print at that size, in the label's own
 * {@link FontMetrics}; a row is the same height whatever it holds and grows only as much as the bigger faces need; the gp
 * figures stand one over the other and the percentage and the word under it at the right edge, at every one of the sixteen
 * pairs of sizes; and the brightness is the colour the percentage beside the figure is drawn in, mixed into the card.
 * ({@link ChangeSizeMenuTest} is the menu's half and {@link ChangeSizePicturesTest} the pictures'.)
 *
 * <p>Like every width assertion in this plugin's tests none copies a pixel figure out of the spec, but for the one the contract
 * names: at Normal and Normal the columns are 42 and 54 and the row is 62.
 */
public class ChangeSizeRowsTest
{
	private static final ChangeSize[] SIZES = ChangeSize.values();

	/** A stack of twelve whips that rose: line 2 reads "+240k", line 3 "12 x 1.52m" and "+20k", the percentage "+1.3%". */
	private static MovementRow whips()
	{
		return new MovementRow(4151, "Abyssal whip", 12, false, 1_520_000L, 1_500_000L, 20_000L, 20_000d * 100.0 / 1_500_000d,
			18_240_000L, null);
	}

	/** A stack of one thousand sharks that fell: "-5.0k" over "-5". */
	private static MovementRow sharks()
	{
		return new MovementRow(385, "Shark", 1_000, true, 890L, 895L, -5L, -0.5d, 890_000L, null);
	}

	/** A heavy rise: "+10.0m" over "+1.43m" and a percentage of "+3.5%". */
	private static MovementRow claws()
	{
		return new MovementRow(13652, "Dragon claws", 7, false, 41_480_000L, 40_050_000L, 1_430_000L, 3.5d, 290_360_000L, null);
	}

	/** A row that did not move: it prints no gp figure on either line. */
	private static MovementRow flat()
	{
		return new MovementRow(1, "Green hat", 4, false, 1_086L, 1_086L, 0L, 0.0d, 4_344L, null);
	}

	/** A stack of one: line 3 prints its working and no gp figure (Q4.7). */
	private static MovementRow single()
	{
		return new MovementRow(3, "Twisted bow", 1, false, 1_620_000_000L, 1_683_500_000L, -63_500_000L, -3.7d, 1_620_000_000L,
			null);
	}

	/** A row whose grade is soft, so the slot under the percentage carries a word. */
	private static MovementRow soft(String word)
	{
		final GradedMove graded = new GradedMove(Grade.SOFT, word, 0.013d, 1_500_000L, 20_000L, false, null, null, null, null,
			1_500_000L);
		return new MovementRow(4151, "Abyssal whip", 12, false, 1_520_000L, 1_500_000L, 20_000L, 1.3d, 18_240_000L,
			MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0, graded);
	}

	@After
	public void tearDown()
	{
		Widgets.setMoveColours(null, null);
	}

	private static MovementRowPanel build(MovementRow row, ChangeText style, boolean hide) throws Exception
	{
		final AtomicReference<MovementRowPanel> out = new AtomicReference<>();
		LookRenderer.onEdt(() -> out.set(new MovementRowPanel(row, null, MovementWindow.D1, null, 0L, ViewOptions.DEFAULT,
			url ->
			{
			}, null, hide, style)));
		return out.get();
	}

	private static MovementRowPanel build(MovementRow row, ChangeText style) throws Exception
	{
		return build(row, style, false);
	}

	/** The row laid out at its own size, top down, with no peer (EDT). */
	private static MovementRowPanel layOut(MovementRowPanel p)
	{
		p.setSize(p.getPreferredSize());
		layOut((Container) p);
		return p;
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

	private static JComponent face(MovementRowPanel p)
	{
		return (JComponent) ((BorderLayout) p.getLayout()).getLayoutComponent(BorderLayout.NORTH);
	}

	private static JComponent text(MovementRowPanel p)
	{
		return (JComponent) ((BorderLayout) face(p).getLayout()).getLayoutComponent(BorderLayout.CENTER);
	}

	private static JComponent part(JComponent in, String where)
	{
		return (JComponent) ((BorderLayout) in.getLayout()).getLayoutComponent(where);
	}

	private static JLabel name(MovementRowPanel p)
	{
		return (JLabel) part(text(p), BorderLayout.NORTH);
	}

	private static JComponent line2(MovementRowPanel p)
	{
		return part(text(p), BorderLayout.CENTER);
	}

	private static JComponent line3(MovementRowPanel p)
	{
		return part(text(p), BorderLayout.SOUTH);
	}

	/** Line 2's stack gp figure. */
	static JLabel gp2(MovementRowPanel p)
	{
		return (JLabel) part(part(line2(p), BorderLayout.EAST), BorderLayout.WEST);
	}

	/** Line 2's percentage. */
	static JLabel pct(MovementRowPanel p)
	{
		return (JLabel) part(part(line2(p), BorderLayout.EAST), BorderLayout.EAST);
	}

	/** Line 2's stack value. */
	static JLabel price(MovementRowPanel p)
	{
		return (JLabel) part(line2(p), BorderLayout.WEST);
	}

	/** Line 3's working. */
	static JLabel working(MovementRowPanel p)
	{
		return (JLabel) part(line3(p), BorderLayout.WEST);
	}

	/** Line 3's one-item gp figure. */
	static JLabel gp3(MovementRowPanel p)
	{
		// Null on a line that prints no gp figure: since release 1.2.1 it holds no column for one.
		final JComponent figures = part(line3(p), BorderLayout.EAST);
		return figures == null ? null : (JLabel) part(figures, BorderLayout.WEST);
	}

	/** Line 3's slot under the percentage: the word's label, or the empty stand-in. */
	static JComponent slot(MovementRowPanel p)
	{
		return part(part(line3(p), BorderLayout.EAST), BorderLayout.EAST);
	}

	private static Rectangle box(Component c, Component root)
	{
		return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), root);
	}

	private static FontMetrics metrics(Font face) throws Exception
	{
		final AtomicReference<FontMetrics> fm = new AtomicReference<>();
		LookRenderer.onEdt(() -> fm.set(new JLabel().getFontMetrics(face)));
		return fm.get();
	}

	private static List<ChangeText> everyPairOfSizes()
	{
		final List<ChangeText> all = new ArrayList<>();
		for (ChangeSize gp : SIZES)
		{
			for (ChangeSize pct : SIZES)
			{
				all.add(ChangeText.of(gp, pct, ChangeText.DEFAULT_BRIGHTNESS));
			}
		}
		return all;
	}

	// ------------------------------------------------------------------------------------------------------ Normal

	/**
	 * S2: at Normal and Normal every number is today's - the gp column 42, the percentage's 54, the word slot 54, the face 57 and
	 * the row 62 by 213 - and the faces are the 10 px plain and the 15 px bold. (The columns are MEASURED here, so this is also
	 * the proof that measuring the widest forms gives the figures this panel was drawn with before there were sizes.)
	 */
	@Test
	public void normalAndNormalAreExactlyTheRowsOfEveryEarlierRelease() throws Exception
	{
		final MovementRowPanel.Fit fit = MovementRowPanel.fit(ChangeSize.NORMAL, ChangeSize.NORMAL);
		assertEquals(42, fit.gpColumn);
		assertEquals(54, fit.pctColumn);
		assertEquals(54, fit.rightBlock);
		assertEquals(MovementRowPanel.FACE_HEIGHT, fit.faceHeight);
		assertEquals(62, MovementRowPanel.ROW_HEIGHT);
		assertEquals(62, MovementRowPanel.rowHeight(ChangeText.DEFAULT));
		assertEquals(62, MovementRowPanel.rowHeight(null));
		assertEquals(Widgets.sans(10), fit.gpFont);
		assertEquals(Widgets.sansBold(15), fit.pctFont);

		final MovementRowPanel p = layOut(build(whips(), ChangeText.DEFAULT));
		assertEquals(213, p.getPreferredSize().width);
		assertEquals(62, p.getPreferredSize().height);
		assertEquals(42, gp2(p).getWidth());
		assertEquals(42, gp3(p).getWidth());
		assertEquals(54, pct(p).getWidth());
		assertEquals(54, slot(p).getWidth());
		assertEquals(Widgets.sans(10), gp2(p).getFont());
		assertEquals(Widgets.sansBold(15), pct(p).getFont());
	}

	/**
	 * The user, 2026-10-25, on a Large percentage: "why does the 1 x 4.9... or 1 x 14.... get cut off so early?" A line 3 that
	 * prints no gp figure gives its working the room nothing is drawn in - the whole width with no word, all but the slot with
	 * one - at every pair of sizes, so "1 x 14.9m" is never cut; a line that prints a gp figure keeps its columns.
	 */
	@Test
	public void aWorkingTakesTheRoomNothingIsDrawnIn() throws Exception
	{
		final MovementRow crystal = new MovementRow(23975, "Crystal body", 1, false, 14_902_000L, 14_440_000L, 462_000L, 3.2d,
			14_902_000L, null);
		for (ChangeText style : everyPairOfSizes())
		{
			final MovementRowPanel one = layOut(build(crystal, style));
			assertEquals(style + ": nothing on the right", null, part(line3(one), BorderLayout.EAST));
			assertEquals(style + ": the working whole", "1 x 14.9m", working(one).getText());

			final MovementRowPanel worded = layOut(build(new MovementRow(4151, "Abyssal whip", 1, false, 1_520_000L, 1_500_000L,
				20_000L, 1.3d, 1_520_000L, MovementRow.PriceSource.LIVE, null, null, null, null, 0, 0, 0, 0,
				soft(GradeWords.volume(3L)).graded()), style));
			assertTrue(style + ": the word keeps its slot", slot(worded).getWidth() >= MovementRowPanel.WORD_SLOT);
			assertEquals(style + ": the working whole beside a word", "1 x 1.52m", working(worded).getText());

			final MovementRowPanel twelve = layOut(build(whips(), style));
			assertEquals(style + ": a gp figure keeps its column", MovementRowPanel.fit(style.gp(), style.pct()).gpColumn,
				gp3(twelve).getWidth());
		}
	}

	// ------------------------------------------------------------------------------------- the columns, per step

	/**
	 * S2 and S4: at every step the two columns are exactly wide enough for the widest text a row can print at that step. The
	 * gp column holds every output of {@link MovementRowPanel#signedGp} - a sweep of every amount to 30,000 and of 1,000
	 * mantissas at every power of ten up to a quintillion, both signs - and the percentage column every
	 * {@link MovementMath#formatPctCompact} form up to 999,999 percent (+999k%): the widest of each, plus the gp figure's 6 px
	 * of air, IS the column (the gp column to the pixel; the percentage's to one px, because the contract's "-100.0%" - which
	 * that formatter never prints, a fall of a hundred percent reads "-100%" - is a pixel wider than any form it does at 17 px).
	 */
	@Test
	public void theColumnsAreTheWidestTextAtEveryStep() throws Exception
	{
		final List<Long> amounts = new ArrayList<>();
		for (long v = 1; v <= 30_000; v++)
		{
			amounts.add(v);
		}
		for (int k = 4; k <= 18; k++)
		{
			final double base = Math.pow(10, k);
			for (int m = 1000; m <= 9999; m++)
			{
				final long v = (long) (base * m / 1000.0);
				if (v > 0 && v < Long.MAX_VALUE / 2)
				{
					amounts.add(v);
				}
			}
		}
		for (ChangeSize size : SIZES)
		{
			final MovementRowPanel.Fit fit = MovementRowPanel.fit(size, size);
			final FontMetrics gpm = metrics(fit.gpFont);
			int widestGp = 0;
			for (long v : amounts)
			{
				widestGp = Math.max(widestGp, Math.max(gpm.stringWidth(MovementRowPanel.signedGp(v)),
					gpm.stringWidth(MovementRowPanel.signedGp(-v))));
			}
			assertEquals(size + ": the gp column is the widest signed gp figure and the air to its right", widestGp + 6,
				fit.gpColumn);

			final FontMetrics pctm = metrics(fit.pctFont);
			int widestPct = 0;
			for (int tenth = 0; tenth <= 9999; tenth++)
			{
				for (long sign : new long[]{1L, -1L})
				{
					widestPct = Math.max(widestPct, pctm.stringWidth(MovementMath.formatPctCompact(tenth / 10.0d, sign)));
				}
			}
			for (int k = 2; k <= 5; k++)
			{
				for (int m = 1; m <= 9999; m++)
				{
					final double pct = m * Math.pow(10, k - 3);
					if (pct >= 1_000_000d)
					{
						continue;
					}
					for (long sign : new long[]{1L, -1L})
					{
						widestPct = Math.max(widestPct, pctm.stringWidth(MovementMath.formatPctCompact(pct, sign)));
					}
				}
			}
			assertTrue(size + ": the percentage column holds every form: " + fit.pctColumn + " vs " + widestPct,
				fit.pctColumn >= widestPct);
			assertTrue(size + ": ...and is no wider than the contract's own widest, \"-100.0%\", asks",
				fit.pctColumn - widestPct <= 1);
			assertTrue(size + ": the contract's named forms fit, both directions", Arrays.asList("+9999%", "-9999%", "+999k%",
				"-999k%", "-100.0%", "+99.9%", "-99.9%").stream().allMatch(s -> pctm.stringWidth(s) <= fit.pctColumn));
			assertTrue(size + ": and so do its gp ones", Arrays.asList("+999", "-999", "+9.9k", "-9.9k", "+999k", "-999k",
				"+99.9m", "-99.9m", "+999m", "-999m", "+1.00m").stream().allMatch(s -> gpm.stringWidth(s) + 6 <= fit.gpColumn));
			assertEquals(size + ": the right-hand block is the percentage's column, or the word's slot if more",
				Math.max(fit.pctColumn, 54), fit.rightBlock);
		}
		// The columns never shrink as the step grows, and each depends on its own step alone.
		for (int i = 1; i < SIZES.length; i++)
		{
			assertTrue(MovementRowPanel.fit(SIZES[i], SIZES[0]).gpColumn >= MovementRowPanel.fit(SIZES[i - 1], SIZES[0]).gpColumn);
			assertTrue(MovementRowPanel.fit(SIZES[0], SIZES[i]).pctColumn >= MovementRowPanel.fit(SIZES[0], SIZES[i - 1]).pctColumn);
		}
		for (ChangeSize a : SIZES)
		{
			for (ChangeSize b : SIZES)
			{
				assertEquals("the gp column follows the gp step alone", MovementRowPanel.fit(a, SIZES[0]).gpColumn, MovementRowPanel.fit(a, b).gpColumn);
				assertEquals("the percentage column follows its step alone", MovementRowPanel.fit(SIZES[0], b).pctColumn, MovementRowPanel.fit(a, b).pctColumn);
			}
		}
	}

	/**
	 * S4: the widest forms, printed, fit the labels they are printed in at every step, both directions - measured with the
	 * label's own metrics: the figure a row paints is never cut to an ellipsis, and its text is wholly inside its box.
	 */
	@Test
	public void theWidestFormsPrintedFitTheirLabelsAtEveryStep() throws Exception
	{
		final MovementRow widest = new MovementRow(1, "Widest", 2, false, 999_000_000L, 1_000_000_000L, -888_000_000L, -99.9d,
			1_998_000_000L, null);
		final MovementRow hundreds = new MovementRow(2, "Hundreds", 2, false, 2_000_000L, 1_000_000L, 1_000_000L, 9_999.0d,
			4_000_000L, null);
		final MovementRow thousands = new MovementRow(4, "Thousands", 2, false, 999_000_000L, 1_000L, 1_000_000L, 999_000.0d,
			1_998_000_000L, null);
		final MovementRow fall = new MovementRow(5, "Fall", 2, false, 1L, 1_000_000L, -999_000L, -100.0d, 2L, null);
		for (ChangeText style : everyPairOfSizes())
		{
			final MovementRowPanel.Fit fit = MovementRowPanel.fit(style.gp(), style.pct());
			final FontMetrics gpm = metrics(fit.gpFont);
			final FontMetrics pctm = metrics(fit.pctFont);
			for (MovementRow r : Arrays.asList(widest, hundreds, thousands, fall))
			{
				final MovementRowPanel p = layOut(build(r, style));
				for (JLabel gp : Arrays.asList(gp2(p), gp3(p)))
				{
					assertFalse(style + " " + r.name() + ": the gp figure is not cut", gp.getText().endsWith("..."));
					assertTrue(style + " " + r.name() + ": '" + gp.getText() + "' fits its column",
						gpm.stringWidth(gp.getText()) + 6 <= gp.getWidth());
				}
				assertFalse(style + " " + r.name() + ": the percentage is not cut", pct(p).getText().endsWith("..."));
				assertTrue(style + " " + r.name() + ": '" + pct(p).getText() + "' fits its column",
					pctm.stringWidth(pct(p).getText()) <= pct(p).getWidth());
			}
		}
	}

	// ----------------------------------------------------------------------------------------------- the heights

	/**
	 * S2 and S4: a row is as tall as {@link MovementRowPanel#rowHeight} says at every one of the sixteen pairs, whatever it
	 * holds - a rich row, a flat one with no gp figure at all, a stack of one, a soft row with a word - so a list is never of two
	 * heights. It is 62 at Normal and Normal and never less than that; a bigger step never makes it shorter; and the three
	 * lines are stacked inside the face without touching each other or its edge (the name above line 2 above line 3).
	 */
	@Test
	public void everyRowIsTheSameHeightAtEveryPairAndItsLinesDoNotOverlap() throws Exception
	{
		int tallest = 0;
		final StringBuilder table = new StringBuilder();
		for (ChangeText style : everyPairOfSizes())
		{
			final int height = MovementRowPanel.rowHeight(style);
			assertTrue(style + ": never shorter than every earlier release's row", height >= MovementRowPanel.ROW_HEIGHT);
			table.append(style.gp()).append('/').append(style.pct()).append('=').append(height).append(' ');
			for (MovementRow r : Arrays.asList(whips(), sharks(), claws(), flat(), single(), soft("low vol 3")))
			{
				final MovementRowPanel p = layOut(build(r, style));
				assertEquals(style + " " + r.name() + ": the row's height", height, p.getPreferredSize().height);
				assertEquals(style + " " + r.name() + ": and its width", 213, p.getPreferredSize().width);
				final Rectangle n = box(name(p), p);
				final Rectangle two = box(line2(p), p);
				final Rectangle three = box(line3(p), p);
				assertTrue(style + " " + r.name() + ": the name is above line 2: " + n + " " + two, n.y + n.height <= two.y);
				assertTrue(style + " " + r.name() + ": line 2 is above line 3: " + two + " " + three,
					two.y + two.height <= three.y);
				assertTrue(style + " " + r.name() + ": line 3 is inside the card: " + three + " of " + height,
					three.y + three.height <= height - 2);
				// Every label on line 2 and line 3 is whole inside its line: no figure is taller than the line it stands on.
				for (JLabel gp : Arrays.asList(gp2(p), pct(p)))
				{
					final int need = metricsHeight(gp);
					assertTrue(style + " " + r.name() + ": '" + gp.getText() + "' is " + need + " px tall in a line "
						+ two.height, need <= two.height);
				}
				if (gp3(p) != null)
				{
					assertTrue(style + " " + r.name() + ": the one-item gp figure fits line 3",
						metricsHeight(gp3(p)) <= three.height);
				}
			}
			tallest = Math.max(tallest, height);
		}
		assertEquals("Normal and Normal is 62", 62, MovementRowPanel.rowHeight(ChangeText.DEFAULT));
		assertTrue("the biggest pair is the tallest: " + table, tallest == MovementRowPanel.rowHeight(
			ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGEST, 55)));
		for (ChangeSize a : SIZES)
		{
			for (int i = 1; i < SIZES.length; i++)
			{
				assertTrue("a bigger gp step is never a shorter row", MovementRowPanel.rowHeight(ChangeText.of(SIZES[i], a, 55))
					>= MovementRowPanel.rowHeight(ChangeText.of(SIZES[i - 1], a, 55)));
				assertTrue("a bigger percentage step is never a shorter row", MovementRowPanel.rowHeight(
					ChangeText.of(a, SIZES[i], 55)) >= MovementRowPanel.rowHeight(ChangeText.of(a, SIZES[i - 1], 55)));
			}
		}
	}

	private static int metricsHeight(JLabel label) throws Exception
	{
		return metrics(label.getFont()).getHeight();
	}

	// ---------------------------------------------------------------------------------------- the columns line up

	/**
	 * S2 and S4: the figures stand in columns at every pair of sizes - the stack's gp figure on line 2 and one item's on line 3
	 * end at ONE x, on every row whatever it says; the percentage ends at the row's right edge and so does the slot under it;
	 * and the stack value and the working begin at the same left edge. Rows of very different figures: "+10.0m", "-5.0k",
	 * "+240k", a heavy rise and a fall.
	 */
	@Test
	public void theFiguresLineUpRowToRowAtEveryPair() throws Exception
	{
		for (ChangeText style : everyPairOfSizes())
		{
			final List<Integer> gpRight = new ArrayList<>();
			final List<Integer> pctRight = new ArrayList<>();
			final List<Integer> slotRight = new ArrayList<>();
			final List<Integer> left = new ArrayList<>();
			for (MovementRow r : Arrays.asList(whips(), sharks(), claws()))
			{
				final MovementRowPanel p = layOut(build(r, style));
				final Rectangle one = box(gp2(p), p);
				final Rectangle two = box(gp3(p), p);
				assertEquals(style + " " + r.name() + ": line 2's gp figure is over line 3's", one.x + one.width, two.x + two.width);
				gpRight.add(one.x + one.width);
				final Rectangle percentage = box(pct(p), p);
				pctRight.add(percentage.x + percentage.width);
				final Rectangle slotBox = box(slot(p), p);
				slotRight.add(slotBox.x + slotBox.width);
				assertEquals(style + " " + r.name() + ": the percentage ends where the slot under it ends",
					percentage.x + percentage.width, slotBox.x + slotBox.width);
				assertEquals(style + " " + r.name() + ": the slot is at least 54 px wide",
					Math.max(54, MovementRowPanel.fit(style.gp(), style.pct()).pctColumn), slotBox.width);
				left.add(box(price(p), p).x);
				assertEquals(style + " " + r.name() + ": the stack value and the working begin together", box(price(p), p).x,
					box(working(p), p).x);
				// The percentage's text is right-aligned in its own column: its last glyph is at the row's right edge.
				assertEquals(style + " " + r.name() + ": the percentage is right-aligned", javax.swing.SwingConstants.RIGHT,
					pct(p).getHorizontalAlignment());
				assertEquals(style + " " + r.name() + ": the right edge is the text block's, 6 px in from the card's",
					213 - 6, percentage.x + percentage.width);
			}
			assertEquals(style + ": every row's gp figures end at one x", 1, new java.util.HashSet<>(gpRight).size());
			assertEquals(style + ": every row's percentage ends at one x", 1, new java.util.HashSet<>(pctRight).size());
			assertEquals(style + ": every row's slot ends at one x", 1, new java.util.HashSet<>(slotRight).size());
			assertEquals(style + ": every row's text begins at one x", 1, new java.util.HashSet<>(left).size());
		}
	}

	/**
	 * S2 and S4: the word under the percentage keeps a slot at least 54 px wide, right-aligned under the percentage, at every
	 * percentage size - a Small percentage's own column (narrower than 54) does not narrow it - and the word is drawn in its own
	 * face (11 px, or the steps down to 10 and 9 that make a long word fit), never one that follows the percentage's size.
	 */
	@Test
	public void theWordKeepsItsSlotAndItsFaceAtEveryPercentageSize() throws Exception
	{
		for (ChangeSize pctSize : SIZES)
		{
			for (ChangeSize gpSize : SIZES)
			{
				final ChangeText style = ChangeText.of(gpSize, pctSize, 55);
				for (String word : Arrays.asList("low vol 3", "spread 55%", "last 1d ago", "last 23h ago", "no trades"))
				{
					final MovementRowPanel p = layOut(build(soft(word), style));
					final JComponent slot = slot(p);
					assertTrue(style + ": the word '" + word + "' has its label", slot instanceof JLabel);
					final JLabel label = (JLabel) slot;
					assertEquals(word, label.getText());
					assertTrue(style + ": the slot is at least 54 px: " + label.getWidth(), label.getWidth() >= 54);
					final Rectangle word2 = box(label, p);
					final Rectangle percentage = box(pct(p), p);
					assertEquals(style + ": right-aligned under the percentage", percentage.x + percentage.width,
						word2.x + word2.width);
					assertEquals(javax.swing.SwingConstants.RIGHT, label.getHorizontalAlignment());
					final int px = label.getFont().getSize();
					assertTrue(style + ": the word is 11, 10 or 9 px, whatever the sizes: " + px, px == 11 || px == 10 || px == 9);
					assertTrue(style + ": the word '" + word + "' fits its slot whole",
						metrics(label.getFont()).stringWidth(word) <= label.getWidth());
					assertFalse(style + ": and is never cut", label.getText().endsWith("..."));
					assertTrue(style + ": the word is whole inside the card", word2.x >= 0);
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------ sizes and faces

	/**
	 * S2: the gp figures take the gp size and the percentage the percentage size - on both lines - and everything else keeps
	 * what it had: the name (14 bold), the stack value (13), the working (11), the picture cell and the text block's width.
	 */
	@Test
	public void theTwoSizesReachTheirFiguresAndNothingElse() throws Exception
	{
		for (ChangeText style : everyPairOfSizes())
		{
			final MovementRowPanel p = layOut(build(whips(), style));
			assertEquals(style + ": line 2's gp figure", Widgets.sans(style.gp().gpPx()), gp2(p).getFont());
			assertEquals(style + ": line 3's gp figure", Widgets.sans(style.gp().gpPx()), gp3(p).getFont());
			assertEquals(style + ": the percentage", Widgets.sansBold(style.pct().pctPx()), pct(p).getFont());
			assertEquals(style + ": the name", Widgets.sansBold(14), name(p).getFont());
			assertEquals(style + ": the stack value", Widgets.sans(13), price(p).getFont());
			assertEquals(style + ": the working", Widgets.sans(11), working(p).getFont());
			assertEquals(style + ": the text block", MovementRowPanel.TEXT_WIDTH, text(p).getWidth());
		}
		// The steps are the contract's: gp 8 / 10 / 12 / 13 and percentage 12 / 15 / 17 / 18.
		assertEquals(Arrays.asList(8, 10, 12, 13), Arrays.asList(ChangeSize.SMALL.gpPx(), ChangeSize.NORMAL.gpPx(),
			ChangeSize.LARGE.gpPx(), ChangeSize.LARGEST.gpPx()));
		assertEquals(Arrays.asList(12, 15, 17, 18), Arrays.asList(ChangeSize.SMALL.pctPx(), ChangeSize.NORMAL.pctPx(),
			ChangeSize.LARGE.pctPx(), ChangeSize.LARGEST.pctPx()));
		assertEquals(Arrays.asList("Small", "Normal", "Large", "Largest"), Arrays.asList(ChangeSize.SMALL.toString(),
			ChangeSize.NORMAL.toString(), ChangeSize.LARGE.toString(), ChangeSize.LARGEST.toString()));
	}

	/**
	 * Hide amounts (the eye) is unchanged: with the amounts masked the figures sit in the same boxes at the same sizes - a mask is
	 * a text of the figure's label and nothing else about the row moves.
	 */
	@Test
	public void maskedFiguresUseTheSameSizesAndColumns() throws Exception
	{
		final ChangeText style = ChangeText.of(ChangeSize.LARGEST, ChangeSize.LARGE, 70);
		final MovementRowPanel shown = layOut(build(whips(), style, false));
		final MovementRowPanel hidden = layOut(build(whips(), style, true));
		assertEquals(shown.getPreferredSize(), hidden.getPreferredSize());
		assertEquals(box(gp2(shown), shown), box(gp2(hidden), hidden));
		assertEquals(box(gp3(shown), shown), box(gp3(hidden), hidden));
		assertEquals(gp2(shown).getFont(), gp2(hidden).getFont());
		assertEquals(gp2(shown).getForeground(), gp2(hidden).getForeground());
		assertNotEquals("the amount is masked", gp2(shown).getText(), gp2(hidden).getText());
		assertEquals("the percentage is not", pct(shown).getText(), pct(hidden).getText());
	}

	// ------------------------------------------------------------------------------------------------ brightness

	/**
	 * S2 and S4: the brightness is how far the gp figure's colour is from the card's grey to the percentage's colour: at 100 it
	 * IS the percentage's colour, exactly, in the built-in colours and in the reader's own; at 20 and 55 it is that colour mixed
	 * 80 and 45 percent into the card; a stored 5 or 150 reads 55; a flat row's grey is the quiet grey whatever the setting. The
	 * Net Worth History tab's quiet colour ({@link Widgets.Kind#QUIET}) is the 45 percent strength it always was - it is not
	 * this setting's.
	 */
	@Test
	public void theBrightnessIsHowFarTheGpFigureIsFromTheCardToThePercentagesColour() throws Exception
	{
		final Color card = net.runelite.client.ui.ColorScheme.DARKER_GRAY_COLOR;
		for (boolean custom : new boolean[]{false, true})
		{
			if (custom)
			{
				Widgets.setMoveColours(new Color(10, 20, 200), new Color(200, 150, 0));
			}
			for (MovementRow r : Arrays.asList(whips(), sharks()))
			{
				final MovementRowPanel at100 = layOut(build(r, ChangeText.DEFAULT.withBrightness(100)));
				assertEquals(r.name() + " custom=" + custom + ": 100 is the percentage's colour exactly", pct(at100).getForeground(),
					gp2(at100).getForeground());
				assertEquals(pct(at100).getForeground(), gp3(at100).getForeground());
				for (int b : new int[]{20, 55, 100})
				{
					final MovementRowPanel p = layOut(build(r, ChangeText.DEFAULT.withBrightness(b)));
					final Color figure = pct(p).getForeground();
					final Color want = new Color(
						(int) Math.round(figure.getRed() + (card.getRed() - figure.getRed()) * (100 - b) / 100.0),
						(int) Math.round(figure.getGreen() + (card.getGreen() - figure.getGreen()) * (100 - b) / 100.0),
						(int) Math.round(figure.getBlue() + (card.getBlue() - figure.getBlue()) * (100 - b) / 100.0));
					assertEquals(r.name() + " custom=" + custom + " at " + b, want, gp2(p).getForeground());
					assertEquals(want, gp3(p).getForeground());
				}
				// 55 is brighter than the 45 percent every release before 1.2.1 drew a gp figure at.
				final Color old = Widgets.move(Long.signum(r.deltaGp()), Widgets.Kind.QUIET);
				final Color now = gp2(layOut(build(r, ChangeText.DEFAULT))).getForeground();
				assertNotEquals(old, now);
				assertTrue("the default is nearer the percentage's colour than the old quiet was",
					distance(now, pct(layOut(build(r, ChangeText.DEFAULT))).getForeground())
						< distance(old, pct(layOut(build(r, ChangeText.DEFAULT))).getForeground()));
			}
			Widgets.setMoveColours(null, null);
		}
		// Stored values outside 20..100 read as 55; the limits themselves are readings.
		assertEquals(55, ChangeText.of(null, null, 5).brightness());
		assertEquals(55, ChangeText.of(null, null, 150).brightness());
		assertEquals(55, ChangeText.of(null, null, 19).brightness());
		assertEquals(55, ChangeText.of(null, null, 101).brightness());
		assertEquals(55, ChangeText.of(null, null, 0).brightness());
		assertEquals(55, ChangeText.of(null, null, -1).brightness());
		assertEquals(20, ChangeText.of(null, null, 20).brightness());
		assertEquals(100, ChangeText.of(null, null, 100).brightness());
		assertTrue(ChangeText.isBrightness(20) && ChangeText.isBrightness(100));
		assertFalse(ChangeText.isBrightness(19) || ChangeText.isBrightness(101));
		// A flat move is the quiet grey whatever the setting; the History tab's own quiet colour is the old 45 percent strength.
		for (int b : new int[]{20, 55, 100})
		{
			final MovementRowPanel p = layOut(build(single(), ChangeText.DEFAULT.withBrightness(b)));
			assertNotNull(gp2(p));
			assertEquals(net.runelite.client.ui.ColorScheme.LIGHT_GRAY_COLOR, Widgets.quietAt(0, b));
		}
		assertEquals("History keeps its 45 percent", new Color(41, 124, 48), Widgets.move(1, Widgets.Kind.QUIET));
		assertEquals(new Color(124, 58, 54), Widgets.move(-1, Widgets.Kind.QUIET));
		assertEquals("quietAt at 45 is the old quiet mix", Widgets.move(1, Widgets.Kind.QUIET), Widgets.quietAt(1, 45));
		assertEquals(Widgets.move(-1, Widgets.Kind.QUIET), Widgets.quietAt(-1, 45));
	}

	/** Straight-line distance between two colours in RGB. */
	private static double distance(Color a, Color b)
	{
		final int dr = a.getRed() - b.getRed();
		final int dg = a.getGreen() - b.getGreen();
		final int db = a.getBlue() - b.getBlue();
		return Math.sqrt((double) dr * dr + (double) dg * dg + (double) db * db);
	}

	/** The value type: null sizes read Normal, the equals is by value, and the withers change one part. */
	@Test
	public void theValueReadsAndChangesOnePartAtATime()
	{
		assertEquals(ChangeText.DEFAULT, ChangeText.of(null, null, 55));
		assertEquals(ChangeSize.NORMAL, ChangeText.DEFAULT.gp());
		assertEquals(ChangeSize.NORMAL, ChangeText.DEFAULT.pct());
		assertEquals(55, ChangeText.DEFAULT.brightness());
		final ChangeText t = ChangeText.DEFAULT.withGp(ChangeSize.LARGE).withPct(ChangeSize.SMALL).withBrightness(70);
		assertEquals(ChangeSize.LARGE, t.gp());
		assertEquals(ChangeSize.SMALL, t.pct());
		assertEquals(70, t.brightness());
		assertEquals(t, ChangeText.of(ChangeSize.LARGE, ChangeSize.SMALL, 70));
		assertEquals(t.hashCode(), ChangeText.of(ChangeSize.LARGE, ChangeSize.SMALL, 70).hashCode());
		assertNotEquals(t, ChangeText.DEFAULT);
		assertEquals(ChangeText.DEFAULT.withGp(null), ChangeText.DEFAULT);
		assertEquals(ChangeSize.LARGEST, ChangeSize.parse(" Largest "));
		assertEquals(ChangeSize.SMALL, ChangeSize.parse("small"));
		assertEquals(null, ChangeSize.parse("huge"));
		assertEquals(null, ChangeSize.parse(null));
	}
}
