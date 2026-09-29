package com.bankpricemovement;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryChartTest.paint;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_DAY;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_TOTAL;
import static com.bankpricemovement.BankHistoryViewTest.chartBlock;
import static com.bankpricemovement.BankHistoryViewTest.chartLabel;
import static com.bankpricemovement.HistoryViewRenderer.GAPS;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.fixture;
import static com.bankpricemovement.HistoryViewRenderer.hover;
import static com.bankpricemovement.HistoryViewRenderer.indexOf;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The chart as the user chose it on 2026-09-29 - "plain line, design 2 but i want the chips below like design 8, keep
 * the caption": design 2, the "Mountain" - a direction-coloured line over a fading fill in a 110 px plot, the range's
 * high and low as grey dots with their compact totals, the latest point's dot in a halo - with a day on which the
 * player did not log in drawn as plain line, marked by nothing.
 *
 * <p>Each pixel test paints the chart alone into an image on the card grey ({@link BankHistoryChartTest#paint}).
 */
public class BankHistoryMountainTest
{
	private static final int[] WIDTHS = {213, 230};

	/** The plot is 110 px - the whole chart - at both widths, and the line spans it between its 14 px insets. */
	@Test
	public void thePlotIs110PxHighAndTheLineReachesFromItsTopInsetToItsBottomOne() throws Exception
	{
		onEdt(() ->
		{
			assertEquals(110, BankHistoryChart.PLOT_HEIGHT);
			for (int width : WIDTHS)
			{
				final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
				layOut(view, width);
				final BankHistoryChart chart = chart(view);
				assertEquals(width + ": the chart is the plot", BankHistoryChart.PLOT_HEIGHT, chart.getHeight());
				final BufferedImage image = paint(chart);
				int top = -1;
				int bottom = -1;
				for (int y = 0; y < image.getHeight(); y++)
				{
					for (int x = 0; x < image.getWidth(); x++)
					{
						if (lineInk(new Color(image.getRGB(x, y))))
						{
							top = top < 0 ? y : top;
							bottom = y;
						}
					}
				}
				// The line's green (the fill never passes g 90), stroked 1.5 px round the highest (y 14) and the lowest
				// (y 96) point - within 3 px, the high's and the low's grey dots sitting on the line's own extremes.
				assertTrue(width + ": the line's top " + top, Math.abs(top - BankHistoryChart.INSET_Y) <= 3);
				assertTrue(width + ": the line's bottom " + bottom,
					Math.abs(bottom - (BankHistoryChart.PLOT_HEIGHT - BankHistoryChart.INSET_Y)) <= 3);
			}
		});
	}

	/**
	 * The fill: tinted in every column under the line, fading as it goes down, and nothing at all above the line - so the
	 * card grey shows between the line and the plot's top.
	 */
	@Test
	public void theFillIsUnderTheLineAndNeverAboveIt() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final BufferedImage image = paint(chart);
			final double[] ys = ys(chart);
			final int card = ColorScheme.DARKER_GRAY_COLOR.getRGB() & 0xFFFFFF;
			int above = 0;
			int under = 0;
			for (int x = 12; x < 190; x++)
			{
				int lastTint = 255;
				for (int y = 0; y < chart.getHeight(); y++)
				{
					// The stroke and its antialiased edge, the high's and the low's figures and dots: not the fill's.
					if (distanceToLine(chart, ys, x + 0.5, y + 0.5) <= 2 || inFigure(chart, x, y))
					{
						continue;
					}
					final Color c = new Color(image.getRGB(x, y));
					if (y + 0.5 < lineY(chart, ys, x + 0.5))
					{
						assertEquals("(" + x + ", " + y + ") above the line is bare card", card, c.getRGB() & 0xFFFFFF);
						above++;
						continue;
					}
					final int tint = c.getGreen() - c.getRed();
					if (y <= chart.getHeight() - 20)
					{
						assertTrue("(" + x + ", " + y + ") under the line is tinted green: " + c, tint >= 3);
					}
					assertTrue("(" + x + ", " + y + ") the tint fades downward: " + tint + " under " + lastTint,
						tint <= lastTint + 1);
					lastTint = tint;
					under++;
				}
			}
			assertTrue("pixels checked above the line " + above + " and under it " + under, above > 2_000 && under > 5_000);
			// And it closes on the plot's bottom: the last row is (almost) bare - the tint has faded to nothing.
			final Color last = new Color(image.getRGB(100, chart.getHeight() - 1));
			assertTrue("the fill has faded out at the bottom: " + last, last.getGreen() - last.getRed() <= 2);
		});
	}

	/**
	 * "Plain line": a stretch of days with no login is painted EXACTLY as the same stretch would be had the player logged
	 * in each day and found the same total - the same stroke, colour and fill, no marker - so the two charts are the same
	 * image to the pixel. At 30d and at all, at both widths.
	 */
	@Test
	public void aNoLoginStretchIsPaintedExactlyAsReadingsAtTheSameValues() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries withGaps = fixture();
			final BankHistorySeries filled = filledAtCarriedValues(withGaps);
			assertEquals("the premise: four more readings", withGaps.size() + GAPS.size(), filled.size());
			for (BankHistoryRange range : Arrays.asList(BankHistoryRange.D30, BankHistoryRange.ALL))
			{
				for (int width : WIDTHS)
				{
					final BankHistoryView a = view(withGaps, range);
					final BankHistoryView b = view(filled, range);
					layOut(a, width);
					layOut(b, width);
					int carried = 0;
					for (BankHistoryMath.Day d : chart(a).days())
					{
						carried += d.carried() ? 1 : 0;
					}
					assertTrue(range + ": the premise, the gapped chart draws carried days: " + carried, carried >= 3);
					assertSameImage(range + " at " + width, paint(chart(a)), paint(chart(b)));
				}
			}
		});
	}

	/** The fixture at 30d: the high is 759m and the low 712m, as the chosen picture shows them, each on a reading. */
	@Test
	public void theHighAndTheLowOfTheFixtureAreTheChosenPicturesOwn() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final List<BankHistoryChart.Figure> figures = chart.figures();
			assertEquals("the high, then the low", 2, figures.size());
			assertEquals("759m", figures.get(0).text);
			assertEquals("712m", figures.get(1).text);
			assertEquals("the low is the range's first day", 0, figures.get(1).index);
			for (BankHistoryChart.Figure f : figures)
			{
				assertFalse(f.text + " is on a reading", chart.days().get(f.index).carried());
			}
		});
	}

	/**
	 * The high and the low are chosen among READINGS: a range whose first days are carried from an older and higher
	 * reading draws those days highest - and still names its high among its own readings - and a no-login day that
	 * repeats the low (it carries it forward) never takes the mark from the reading it repeats.
	 */
	@Test
	public void theHighAndTheLowStandOnReadingsNeverOnANoLoginDay() throws Exception
	{
		onEdt(() ->
		{
			// 10 days ago 1,000m (outside 7d), then 500m 3 days ago (carried over 2 days ago), 700m yesterday, 600m today.
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(10), 1_000_000_000L),
				point(TODAY.minusDays(3), 500_000_000L), point(TODAY.minusDays(1), 700_000_000L),
				point(TODAY, 600_000_000L)));
			final BankHistoryView view = view(s, BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final List<BankHistoryMath.Day> days = chart.days();
			assertEquals(8, days.size());
			assertTrue("the premise: the range starts on carried days at 1,000m", days.get(0).carried()
				&& days.get(0).valueGp() == 1_000_000_000L);
			assertTrue("...and 2 days ago is a no-login day at the low", days.get(5).carried()
				&& days.get(5).valueGp() == 500_000_000L);
			final List<BankHistoryChart.Figure> figures = chart.figures();
			assertEquals(2, figures.size());
			assertEquals("the high is yesterday's reading", TODAY.minusDays(1), days.get(figures.get(0).index).day());
			assertEquals("700m", figures.get(0).text);
			assertEquals("the low is the reading 3 days ago", TODAY.minusDays(3), days.get(figures.get(1).index).day());
			assertEquals("500m", figures.get(1).text);
		});
	}

	/** One reading, or a flat range: no high and no low - the readout already gives the total. */
	@Test
	public void oneReadingOrAFlatRangeHasNoHighOrLow() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView one = view(HistoryViewRenderer.oneReading(), BankHistoryRange.D30);
			layOut(one, 213);
			assertTrue(chart(one).figures().isEmpty());

			final BankHistoryView flat = view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(5), 42_000_000L),
				point(TODAY.minusDays(2), 42_000_000L), point(TODAY, 42_000_000L))), BankHistoryRange.D7);
			layOut(flat, 213);
			final BankHistoryChart chart = chart(flat);
			assertTrue(chart.figures().isEmpty());
			assertEquals("a flat range is grey", ColorScheme.LIGHT_GRAY_COLOR, chart.lineColour());
			// Its line stands in the middle of the plot.
			final BufferedImage image = paint(chart);
			int brightest = 0;
			for (int y = BankHistoryChart.PLOT_HEIGHT / 2 - 2; y <= BankHistoryChart.PLOT_HEIGHT / 2 + 2; y++)
			{
				final Color c = new Color(image.getRGB(100, y));
				assertTrue("grey, not a colour: " + c, Math.abs(c.getRed() - c.getGreen()) <= 2
					&& Math.abs(c.getGreen() - c.getBlue()) <= 2);
				brightest = Math.max(brightest, c.getRed());
			}
			assertTrue("the grey line at mid-height: " + brightest, brightest >= 110);
		});
	}

	/** A falling range: the line and the readout's ring the lifted red, and the fill a red tint (alpha 80) under it. */
	@Test
	public void aFallingRangeIsARedLineOverARedFill() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries falling = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(6), 900_000_000L),
				point(TODAY.minusDays(4), 880_000_000L), point(TODAY.minusDays(2), 700_000_000L),
				point(TODAY, 690_000_000L)));
			final BankHistoryView view = view(falling, BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			assertEquals(Widgets.MOVE_DOWN_TEXT, chart.lineColour());
			final BufferedImage image = paint(chart);
			final double[] ys = ys(chart);
			int redLine = 0;
			for (int x = 0; x < image.getWidth(); x++)
			{
				for (int y = 0; y < image.getHeight(); y++)
				{
					final Color c = new Color(image.getRGB(x, y));
					redLine += c.getRed() >= 150 && c.getRed() - c.getGreen() >= 100 ? 1 : 0;
				}
			}
			assertTrue("the red line is drawn: " + redLine + " px", redLine > 100);
			// 4 px under the line's highest point the fill is red: at alpha 80 near the top, about a quarter of the way
			// from the card to the lifted red.
			final int x = (int) Math.round(BankHistoryChart.xAt(1, chart.days().size(), chart.getWidth()));
			final Color fill = new Color(image.getRGB(x, (int) Math.ceil(lineY(chart, ys, x + 0.5) + 4)));
			assertTrue("the fill is red: " + fill, fill.getRed() - fill.getGreen() >= 30 && fill.getRed() >= 75);
			// The readout's ring takes the line's red.
			assertEquals(Widgets.MOVE_DOWN_TEXT, ringColour(view));
		});
	}

	/**
	 * Every label fits at 213 (and 230), each measured with its own FontMetrics - the high's and the low's figures
	 * included: inside the chart's 6 px margins and its height, as wide as their text, clear of the line, of each other
	 * and - by the 2 px of air - of the latest point's halo. Checked on the fixture, on a record high two days before
	 * the latest point (the long record's "781m" case), on a high that IS the latest point at the plot's top, on a
	 * low that is the latest point at the plot's bottom - and (the review's F1) on a range that OPENS on days carried
	 * from an older reading above every reading inside it, so the line comes down off that plateau straight into the
	 * high's figure, and on its mirror, a range opening on a plateau below every reading, the line coming up into the
	 * low's figure, each at 7d and at 30d.
	 */
	@Test
	public void theHighAndLowFiguresFitAndTouchNothing() throws Exception
	{
		onEdt(() ->
		{
			final List<BankHistorySeries> cases = new ArrayList<>();
			final List<BankHistoryRange> ranges = new ArrayList<>();
			cases.add(fixture());
			// The record high two days before the latest point; the latest point the high; the latest point the low.
			cases.add(series(742, 761, 704, 726, 744, 770, 781.3, 779, 776.2));
			cases.add(series(742, 761, 704, 726, 744, 770, 781.3, 779, 1_210.5));
			cases.add(series(742, 761, 704, 726, 744, 770, 781.3, 779, 12.345_678));
			for (int i = 0; i < cases.size(); i++)
			{
				ranges.add(BankHistoryRange.D30);
			}
			// F1: a carried plateau above every reading (a falling bank: the high is the first reading in the range) ...
			cases.add(dated(10, 1_000, 3, 900, 2, 800, 1, 750, 0, 700));
			ranges.add(BankHistoryRange.D7);
			cases.add(dated(40, 1_000, 20, 900, 15, 850, 10, 820, 5, 760, 0, 700));
			ranges.add(BankHistoryRange.D30);
			// ... and one below every reading (a rising bank: the low is the first reading in the range).
			cases.add(dated(10, 400, 3, 500, 2, 600, 1, 650, 0, 700));
			ranges.add(BankHistoryRange.D7);
			cases.add(dated(40, 400, 20, 500, 15, 560, 10, 600, 5, 650, 0, 700));
			ranges.add(BankHistoryRange.D30);
			for (int c = 0; c < cases.size(); c++)
			{
				for (int width : WIDTHS)
				{
					final BankHistoryView view = view(cases.get(c), ranges.get(c));
					layOut(view, width);
					final BankHistoryChart chart = chart(view);
					final List<BankHistoryChart.Figure> figures = chart.figures();
					assertEquals(2, figures.size());
					final FontMetrics fm = chart.getFontMetrics(BankHistoryChart.FIGURE_FONT);
					final double[] ys = ys(chart);
					final int latest = chart.readoutIndex();
					final double lx = BankHistoryChart.xAt(latest, chart.days().size(), width);
					final double ly = ys[latest];
					final double reach = BankHistoryChart.HALO_RADIUS + BankHistoryChart.HALO_AIR;
					for (BankHistoryChart.Figure f : figures)
					{
						final String what = "case " + c + " at " + ranges.get(c) + ", " + width + " '" + f.text + "'";
						final int fw = fm.stringWidth(f.text);
						final int top = top(chart, f);
						assertTrue(what + ": inside the left margin at " + f.left, f.left >= BankHistoryChart.FIGURE_MARGIN);
						assertTrue(what + ": inside the right margin, ends " + (f.left + fw),
							f.left + fw <= width - BankHistoryChart.FIGURE_MARGIN);
						assertTrue(what + ": inside the plot, " + top + ".." + f.baseline,
							top >= 0 && f.baseline <= chart.getHeight());
						assertTrue(what + ": ink " + top + ".." + f.baseline + " is 7 px of digits",
							f.baseline - top >= 6 && f.baseline - top <= 9);
						assertTrue(what + ": clear of the halo round (" + lx + ", " + ly + ")",
							f.left + fw <= lx - reach || f.left >= lx + reach || f.baseline <= ly - reach
								|| top >= ly + reach);
						for (int x = f.left; x < f.left + fw; x++)
						{
							for (int y = top; y < f.baseline; y++)
							{
								// The stroke is 1.5 px: its edge 0.75 px from the centre, and at least a pixel of air.
								assertTrue(what + ": clear of the line at (" + x + ", " + y + ")",
									distanceToLine(chart, ys, x + 0.5, y + 0.5) >= 0.75 + 1.2);
							}
						}
					}
					final BankHistoryChart.Figure hi = figures.get(0);
					final BankHistoryChart.Figure lo = figures.get(1);
					final int hiW = fm.stringWidth(hi.text);
					final int loW = fm.stringWidth(lo.text);
					assertTrue(width + ": the two figures do not overlap", hi.baseline <= top(chart, lo)
						|| hi.left + hiW <= lo.left || lo.left + loW <= hi.left);
				}
			}
		});
	}

	/**
	 * Hovering a no-login day moves the readout to that day and its CARRIED total, with the ordinary ring - on the chart
	 * the same ring, guide and line as the same hover over a chart where that day was a reading at that total.
	 */
	@Test
	public void hoveringANoLoginDayReadsItsCarriedTotalWithTheOrdinaryRing() throws Exception
	{
		onEdt(() ->
		{
			final LocalDate sep8 = LocalDate.of(2026, 9, 8);
			final BankHistoryView a = view(fixture(), BankHistoryRange.D30);
			final BankHistoryView b = view(filledAtCarriedValues(fixture()), BankHistoryRange.D30);
			layOut(a, 213);
			layOut(b, 213);
			final BankHistoryChart ca = chart(a);
			assertTrue("the premise: 08 Sep is a no-login day", ca.days().get(indexOf(ca, sep8)).carried());
			final int x = BankHistoryChartTest.x(ca, indexOf(ca, sep8));
			hover(ca, x);
			hover(chart(b), x);
			assertEquals("2026-09-08", a.describe().get("hoverDay"));
			assertEquals("Tue 08 Sep", chartLabel(a, READOUT_DAY));
			final long carried = fixture().on(sep8.minusDays(1)).valueFor(null);
			assertEquals(MovementMath.formatExact(carried) + " gp", chartLabel(a, READOUT_TOTAL));
			assertEquals("the readout's ring is the line's colour", ColorScheme.PROGRESS_COMPLETE_COLOR, ringColour(a));
			assertSameImage("the hover over a no-login day", paint(ca), paint(chart(b)));
		});
	}

	// ---------------------------------------------------------------------------------------- helpers

	/** The same series with a reading on every day it lacked, at the total that day was carried at. */
	static BankHistorySeries filledAtCarriedValues(final BankHistorySeries s)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (BankHistoryMath.Day d : BankHistoryMath.days(s, null, TODAY, ViewOptions.DEFAULT))
		{
			points.add(point(d.day(), d.valueGp()));
		}
		return BankHistorySeries.of(points);
	}

	/**
	 * Readings in millions, the last three on the last three days (2 days ago, yesterday, today) and the others spread
	 * over the 30 days before them.
	 */
	private static BankHistorySeries series(final double... millions)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		final int n = millions.length;
		for (int i = 0; i < n; i++)
		{
			final int daysAgo = i >= n - 3 ? n - 1 - i : 30 - i * 27 / (n - 3);
			points.add(point(TODAY.minusDays(daysAgo), Math.round(millions[i] * 1_000_000d)));
		}
		return BankHistorySeries.of(points);
	}

	/** Readings in millions on given days: pairs of (days before today, millions). */
	static BankHistorySeries dated(final double... pairs)
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			points.add(point(TODAY.minusDays((long) pairs[i]), Math.round(pairs[i + 1] * 1_000_000d)));
		}
		return BankHistorySeries.of(points);
	}

	/** The top row of a figure's ink, as its own face draws its text (the figures have no descenders). */
	static int top(final BankHistoryChart chart, final BankHistoryChart.Figure f)
	{
		final FontMetrics fm = chart.getFontMetrics(BankHistoryChart.FIGURE_FONT);
		return f.baseline + BankHistoryChart.FIGURE_FONT.createGlyphVector(fm.getFontRenderContext(), f.text)
			.getPixelBounds(null, 0, 0).y;
	}

	/** The points' y, by the chart's own rule: the highest at {@code INSET_Y}, the lowest {@code INSET_Y} off the bottom. */
	static double[] ys(final BankHistoryChart chart)
	{
		final List<BankHistoryMath.Day> days = chart.days();
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (BankHistoryMath.Day d : days)
		{
			min = Math.min(min, d.valueGp());
			max = Math.max(max, d.valueGp());
		}
		final double plot = chart.getHeight() - 2.0 * BankHistoryChart.INSET_Y;
		final double[] ys = new double[days.size()];
		for (int i = 0; i < ys.length; i++)
		{
			ys[i] = max == min ? BankHistoryChart.INSET_Y + plot / 2
				: BankHistoryChart.INSET_Y + (max - (double) days.get(i).valueGp()) * plot / ((double) max - min);
		}
		return ys;
	}

	/** The line's y at {@code x}, between the two points either side of it. */
	static double lineY(final BankHistoryChart chart, final double[] ys, final double x)
	{
		final int n = ys.length;
		final int w = chart.getWidth();
		for (int i = 0; i + 1 < n; i++)
		{
			final double x0 = BankHistoryChart.xAt(i, n, w);
			final double x1 = BankHistoryChart.xAt(i + 1, n, w);
			if (x >= x0 && x <= x1)
			{
				return ys[i] + (ys[i + 1] - ys[i]) * (x - x0) / (x1 - x0);
			}
		}
		return x < BankHistoryChart.xAt(0, n, w) ? ys[0] : ys[n - 1];
	}

	/** How far (x, y) is from the line's centre: from the nearest of its segments. */
	static double distanceToLine(final BankHistoryChart chart, final double[] ys, final double x, final double y)
	{
		final int n = ys.length;
		final int w = chart.getWidth();
		double best = Double.MAX_VALUE;
		for (int i = 0; i + 1 < n; i++)
		{
			best = Math.min(best, java.awt.geom.Line2D.ptSegDist(BankHistoryChart.xAt(i, n, w), ys[i],
				BankHistoryChart.xAt(i + 1, n, w), ys[i + 1], x, y));
		}
		return best;
	}

	/** Whether (x, y) is in a figure's box, or within 3 px of a high or low dot. */
	private static boolean inFigure(final BankHistoryChart chart, final int x, final int y)
	{
		final double[] ys = ys(chart);
		final FontMetrics fm = chart.getFontMetrics(BankHistoryChart.FIGURE_FONT);
		for (BankHistoryChart.Figure f : chart.figures())
		{
			if (x >= f.left - 1 && x <= f.left + fm.stringWidth(f.text) && y >= top(chart, f) - 1 && y <= f.baseline + 1)
			{
				return true;
			}
			final double px = BankHistoryChart.xAt(f.index, chart.days().size(), chart.getWidth());
			if (Math.abs(x + 0.5 - px) <= 3 && Math.abs(y + 0.5 - ys[f.index]) <= 3)
			{
				return true;
			}
		}
		return false;
	}

	/** The colour the readout's ring icon paints at its centre. */
	static Color ringColour(final BankHistoryView view)
	{
		final JLabel day = (JLabel) chartBlock(view).getComponent(READOUT_DAY);
		final Icon ring = day.getIcon();
		final BufferedImage image = new BufferedImage(ring.getIconWidth(), ring.getIconHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			ring.paintIcon(day, g, 0, 0);
		}
		finally
		{
			g.dispose();
		}
		return new Color(image.getRGB(ring.getIconWidth() / 2, ring.getIconHeight() / 2));
	}

	/** A pixel of the rising line: its green (the fill's tint never passes g 90). */
	private static boolean lineInk(final Color c)
	{
		return c.getGreen() >= 150 && c.getGreen() - c.getRed() >= 100;
	}

	static boolean near(final Color a, final Color b, final int tolerance)
	{
		return Math.abs(a.getRed() - b.getRed()) <= tolerance && Math.abs(a.getGreen() - b.getGreen()) <= tolerance
			&& Math.abs(a.getBlue() - b.getBlue()) <= tolerance;
	}

	static void assertSameImage(final String what, final BufferedImage a, final BufferedImage b)
	{
		assertEquals(what + ": width", a.getWidth(), b.getWidth());
		assertEquals(what + ": height", a.getHeight(), b.getHeight());
		for (int y = 0; y < a.getHeight(); y++)
		{
			for (int x = 0; x < a.getWidth(); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					throw new AssertionError(what + ": pixel (" + x + ", " + y + ") differs: "
						+ new Color(a.getRGB(x, y)) + " vs " + new Color(b.getRGB(x, y)));
				}
			}
		}
	}
}
