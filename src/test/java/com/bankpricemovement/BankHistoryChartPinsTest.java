package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryChartTest.paint;
import static com.bankpricemovement.BankHistoryMountainTest.assertSameImage;
import static com.bankpricemovement.BankHistoryMountainTest.dated;
import static com.bankpricemovement.BankHistoryMountainTest.lineY;
import static com.bankpricemovement.BankHistoryMountainTest.near;
import static com.bankpricemovement.BankHistoryMountainTest.ys;
import static com.bankpricemovement.BankHistoryViewTest.press;
import static com.bankpricemovement.BankHistoryViewTest.rangeCell;
import static com.bankpricemovement.HistoryViewRenderer.HOVER_DAY;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.fixture;
import static com.bankpricemovement.HistoryViewRenderer.hover;
import static com.bankpricemovement.HistoryViewRenderer.indexOf;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The mountain chart's pins added after the review and mutation pass of 2026-09-29: the first of two equal readings
 * takes the high (and the low), new data at the same size lays the chart out again, the hover ring takes the line's
 * colour, the fill's alpha is 70 on a rise and 80 on a fall, and the last point stands where the target puts it.
 */
public class BankHistoryChartPinsTest
{
	/** Two readings share the range's highest total: the FIRST of them carries the high's mark. */
	@Test
	public void theFirstOfTwoEqualHighsTakesTheMark() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(dated(6, 700, 4, 500, 2, 700, 0, 600), BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final List<BankHistoryChart.Figure> figures = chart.figures();
			assertEquals(2, figures.size());
			assertEquals("700m", figures.get(0).text);
			assertEquals("the high is the first 700m", TODAY.minusDays(6), chart.days().get(figures.get(0).index).day());
		});
	}

	/** Two readings share the range's lowest total: the FIRST of them carries the low's mark. */
	@Test
	public void theFirstOfTwoEqualLowsTakesTheMark() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(dated(6, 500, 4, 800, 2, 500, 0, 700), BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final List<BankHistoryChart.Figure> figures = chart.figures();
			assertEquals(2, figures.size());
			assertEquals("500m", figures.get(1).text);
			assertEquals("the low is the first 500m", TODAY.minusDays(6), chart.days().get(figures.get(1).index).day());
		});
	}

	/**
	 * A press on the range bar hands the chart new days at the SAME size: it lays them out again rather than painting
	 * them on the old range's coordinates - 7d to 30d (more days than the old layout had) and back (fewer).
	 */
	@Test
	public void newDataAtTheSameSizeIsLaidOutAgain() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryRange[][] moves = {{BankHistoryRange.D7, BankHistoryRange.D30},
				{BankHistoryRange.D30, BankHistoryRange.D7}};
			for (BankHistoryRange[] move : moves)
			{
				final BankHistoryView view = view(fixture(), move[0]);
				layOut(view, 213);
				final BankHistoryChart chart = chart(view);
				paint(chart);
				final int size = chart.getWidth() * 1000 + chart.getHeight();
				press(rangeCell(view, move[1]));
				assertEquals("the premise: the chart keeps its size", size, chart.getWidth() * 1000 + chart.getHeight());
				final BankHistoryView fresh = view(fixture(), move[1]);
				layOut(fresh, 213);
				assertEquals(chart(fresh).days().size(), chart.days().size());
				assertSameImage(move[0] + " then " + move[1], paint(chart(fresh)), paint(chart));
				assertEquals(chart(fresh).figures().get(0).left, chart.figures().get(0).left);
				assertEquals(chart(fresh).figures().get(1).index, chart.figures().get(1).index);
			}
		});
	}

	/** The hover ring is stroked in the line's colour round a disc of the card grey - green on a rise, red on a fall. */
	@Test
	public void theHoverRingTakesTheLinesColour() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView rise = view(fixture(), BankHistoryRange.D30);
			layOut(rise, 213);
			final BankHistoryChart up = chart(rise);
			assertRing(up, indexOf(up, HOVER_DAY), ColorScheme.PROGRESS_COMPLETE_COLOR);

			final BankHistoryView fall = view(dated(6, 900, 4, 880, 2, 700, 0, 690), BankHistoryRange.D7);
			layOut(fall, 213);
			final BankHistoryChart down = chart(fall);
			assertEquals("the premise: a fall", Widgets.MOVE_DOWN_TEXT, down.lineColour());
			assertRing(down, 2, Widgets.MOVE_DOWN_TEXT);
		});
	}

	/** The fill's tint under the line's highest point: alpha 70 on a rise, 80 on a fall, fading to 0 at the bottom. */
	@Test
	public void theFillIsAlpha70OnARiseAnd80OnAFall() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView rise = view(fixture(), BankHistoryRange.D30);
			layOut(rise, 213);
			assertFillAlpha(chart(rise), ColorScheme.PROGRESS_COMPLETE_COLOR, BankHistoryChart.FILL_ALPHA);
			assertEquals(70, BankHistoryChart.FILL_ALPHA);

			final BankHistoryView fall = view(dated(6, 900, 4, 880, 2, 700, 0, 690), BankHistoryRange.D7);
			layOut(fall, 213);
			assertFillAlpha(chart(fall), Widgets.MOVE_DOWN_TEXT, BankHistoryChart.FILL_ALPHA_FALL);
			assertEquals(80, BankHistoryChart.FILL_ALPHA_FALL);
		});
	}

	/**
	 * The last point stands 9 px in from the chart's right edge: at 213 at x 204, so its 3 px dot ends on the range
	 * bar's right edge (207) and its 6 px halo 3 px inside the block; at 230 at x 221.
	 */
	@Test
	public void theLastPointStandsWhereTheTargetPutsIt()
	{
		assertEquals(204.0, BankHistoryChart.xAt(30, 31, 213), 1e-9);
		assertEquals(221.0, BankHistoryChart.xAt(7, 8, 230), 1e-9);
		assertEquals(6.0, BankHistoryChart.xAt(0, 31, 213), 1e-9);
		assertEquals("the dot ends on the bar's right edge", 207.0,
			BankHistoryChart.xAt(30, 31, 213) + BankHistoryChart.DOT_RADIUS, 1e-9);
		assertEquals("the halo ends 3 px inside the block", 213 - 3.0,
			BankHistoryChart.xAt(30, 31, 213) + BankHistoryChart.HALO_RADIUS, 1e-9);
	}

	// ---------------------------------------------------------------------------------------- helpers

	private static void assertRing(final BankHistoryChart chart, final int index, final Color expected)
	{
		assertTrue("the premise: a point to hover", index >= 0 && index != chart.readoutIndex());
		hover(chart, BankHistoryChartTest.x(chart, index));
		assertEquals("the premise: the hover is on it", index, chart.readoutIndex());
		final BufferedImage image = paint(chart);
		final double cx = BankHistoryChart.xAt(index, chart.days().size(), chart.getWidth());
		final double cy = ys(chart)[index];
		int ring = 0;
		int coloured = 0;
		for (int y = (int) cy - 7; y <= (int) cy + 7; y++)
		{
			for (int x = (int) cx - 7; x <= (int) cx + 7; x++)
			{
				final double r = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				if (r >= 4.1 && r <= 4.9)
				{
					ring++;
					coloured += near(new Color(image.getRGB(x, y)), expected, 40) ? 1 : 0;
				}
			}
		}
		assertTrue("the ring is the line's colour: " + coloured + " of " + ring + " px", ring >= 20
			&& coloured * 4 >= ring * 3);
		assertEquals("inside the ring, the card grey", ColorScheme.DARKER_GRAY_COLOR.getRGB() & 0xFFFFFF,
			image.getRGB((int) Math.floor(cx), (int) Math.floor(cy)) & 0xFFFFFF);
	}

	private static void assertFillAlpha(final BankHistoryChart chart, final Color line, final int alpha)
	{
		final BufferedImage image = paint(chart);
		final double[] ys = ys(chart);
		int hi = 0;
		for (int i = 1; i < ys.length; i++)
		{
			hi = ys[i] < ys[hi] ? i : hi;
		}
		final double top = ys[hi];
		final int h = chart.getHeight();
		final int x = (int) Math.max(8, Math.min(chart.getWidth() - 12,
			Math.round(BankHistoryChart.xAt(hi, ys.length, chart.getWidth()))));
		final int y = (int) Math.ceil(lineY(chart, ys, x + 0.5) + 5);
		final double a = alpha * (1 - (y + 0.5 - top) / (h - top)) / 255.0;
		final Color card = ColorScheme.DARKER_GRAY_COLOR;
		final Color want = new Color((int) Math.round(card.getRed() + (line.getRed() - card.getRed()) * a),
			(int) Math.round(card.getGreen() + (line.getGreen() - card.getGreen()) * a),
			(int) Math.round(card.getBlue() + (line.getBlue() - card.getBlue()) * a));
		final Color got = new Color(image.getRGB(x, y));
		assertTrue("the fill at (" + x + ", " + y + ") is the line's colour at alpha " + alpha + " there: " + got
			+ " want " + want, near(got, want, 3));
	}
}
