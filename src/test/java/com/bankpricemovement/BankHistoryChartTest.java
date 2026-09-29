package com.bankpricemovement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryViewTest.label;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.fixture;
import static com.bankpricemovement.HistoryViewRenderer.hover;
import static com.bankpricemovement.HistoryViewRenderer.indexOf;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.leave;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, builder C: the chart (plan 7.2 item 10) - a continuous line, the thinning above 120 points, and the
 * hover that moves the readout. Its look since 2026-09-29 - the mountain, the plain line, the high and the low - is
 * pinned by {@link BankHistoryMountainTest}.
 *
 * <p>The pixel tests paint the chart alone into an image on the card grey and read each column: how far its most-drawn
 * pixel is from the card grey.
 */
public class BankHistoryChartTest
{
	/** Greener than this: some line is drawn in the column. */
	private static final int INK = 25;

	@Test
	public void theLineIsContinuousEverySegmentIsDrawn() throws Exception
	{
		onEdt(() ->
		{
			for (BankHistoryRange range : BankHistoryRange.values())
			{
				final BankHistoryView view = view(fixture(), range);
				layOut(view, 213);
				final BankHistoryChart chart = chart(view);
				final BufferedImage image = paint(chart);
				final int n = chart.days().size();
				for (int i = 0; i + 1 < n; i++)
				{
					int best = 0;
					for (int x = x(chart, i); x <= x(chart, i + 1); x++)
					{
						best = Math.max(best, ink(image, x));
					}
					assertTrue(range + ": segment " + i + " -> " + (i + 1) + " is drawn (" + best + ")", best > INK);
				}
			}
		});
	}

	@Test
	public void oneReadingIsADotAndNoLine() throws Exception
	{
		onEdt(() ->
		{
			// One reading three days ago: four days drawn, three of them carried - and still no line and no fill, just
			// the dot in its halo, every drawn pixel inside the halo's box round the reading's point.
			final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(3), 5L))),
				BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			assertEquals(4, chart.days().size());
			final BufferedImage image = paint(chart);
			final double px = BankHistoryChart.xAt(0, 4, chart.getWidth());
			final double py = chart.getHeight() / 2.0;
			final double reach = BankHistoryChart.HALO_RADIUS + 1;
			int drawn = 0;
			for (int y = 0; y < image.getHeight(); y++)
			{
				for (int x = 0; x < image.getWidth(); x++)
				{
					if ((image.getRGB(x, y) & 0xFFFFFF) != (ColorScheme.DARKER_GRAY_COLOR.getRGB() & 0xFFFFFF))
					{
						drawn++;
						assertTrue("(" + x + ", " + y + ") is inside the halo round the reading",
							Math.abs(x + 0.5 - px) <= reach && Math.abs(y + 0.5 - py) <= reach);
					}
				}
			}
			assertTrue("the dot and its halo are drawn: " + drawn + " px", drawn > 20);
			assertTrue("no high or low with one reading", chart.figures().isEmpty());
			assertNotNull("its readout names it", label(view, "Wed 23 Sep"));
		});
	}

	@Test
	public void aRangeOfMoreThan120DaysIsThinned() throws Exception
	{
		onEdt(() ->
		{
			for (int n : new int[]{120, 121, 400})
			{
				final List<BankHistoryPoint> points = new ArrayList<>();
				for (int i = 0; i < n; i++)
				{
					points.add(point(TODAY.minusDays(i), 1_000_000L + i));
				}
				final BankHistorySeries s = BankHistorySeries.of(points);
				final BankHistoryView view = view(s, BankHistoryRange.ALL);
				final List<BankHistoryMath.Day> drawn = chart(view).days();
				final List<BankHistoryMath.Day> all = BankHistoryMath.days(s, null, TODAY, ViewOptions.DEFAULT);
				assertEquals(n, all.size());
				assertEquals(n + " days", BankHistoryMath.thin(all, BankHistoryView.MAX_POINTS), drawn);
				assertTrue(n + " days: at most 120 points, " + drawn.size(), drawn.size() <= 120);
				assertEquals("the last point is today", TODAY, drawn.get(drawn.size() - 1).day());
				if (n > 120)
				{
					assertTrue("thinned", drawn.size() < n);
				}
				else
				{
					assertEquals(n, drawn.size());
				}
				assertEquals("the list is not thinned: one page of it", Math.min(n, 250), view.describe().get("rows"));
			}
		});
	}

	@Test
	public void theReadoutAndDotNameTheLatestReadingNotACarriedToday() throws Exception
	{
		onEdt(() ->
		{
			// One reading three days ago, 7d: four days drawn, the last three carried. The readout and the dot stand on
			// the reading (index 0), never on today's carried point (index 3).
			final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(3), 5L))),
				BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			assertEquals(4, chart.days().size());
			assertEquals(0, chart.readoutIndex());
			assertEquals("the readout itself, by its place", "Wed 23 Sep",
				BankHistoryViewTest.chartLabel(view, BankHistoryViewTest.READOUT_DAY));
			final BufferedImage image = paint(chart);
			assertTrue("the dot is at the reading", ink(image, x(chart, 0)) > INK);
			assertTrue("nothing at today's carried point", ink(image, x(chart, 3)) <= INK);
		});
	}

	@Test
	public void aThinnedBucketIsDrawnAsItsLastReadingAndTheReadoutNamesIt() throws Exception
	{
		onEdt(() ->
		{
			// 400 days with a reading every third day and none today (review C-TEST-1): buckets of four days, the last
			// of them 23-26 Sep with its one reading on the 25th - the point drawn is that reading, not carried today.
			final List<BankHistoryPoint> points = new ArrayList<>();
			for (int i = 1; i < 400; i += 3)
			{
				points.add(point(TODAY.minusDays(i), 1_000_000L + i * 1_000L));
			}
			final BankHistorySeries s = BankHistorySeries.of(points);
			final BankHistoryView view = view(s, BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final List<BankHistoryMath.Day> all = BankHistoryMath.days(s, null, TODAY, ViewOptions.DEFAULT);
			assertTrue("today is carried", all.get(all.size() - 1).carried());
			assertEquals(BankHistoryMath.thin(all, BankHistoryView.MAX_POINTS), chart.days());
			final BankHistoryMath.Day last = chart.days().get(chart.days().size() - 1);
			assertTrue("the last bucket's point is its reading", !last.carried());
			assertEquals(TODAY.minusDays(1), last.day());
			for (BankHistoryMath.Day d : chart.days())
			{
				assertTrue("every bucket here holds a reading: " + d, !d.carried());
			}

			hover(chart, x(chart, 3));
			leave(chart);
			assertEquals(BankHistoryDayRow.weekday(TODAY.minusDays(1)),
				BankHistoryViewTest.chartLabel(view, BankHistoryViewTest.READOUT_DAY));
		});
	}

	@Test
	public void theReadoutFollowsTheHoverAndLeavingRestoresTheLatestReading() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			assertNull(view.describe().get("hoverDay"));
			assertNotNull("with no hover, the latest reading", label(view, "Sat 26 Sep"));
			assertNotNull(label(view, "736,412,683 gp"));

			final LocalDate sep9 = LocalDate.of(2026, 9, 9);
			hover(chart, x(chart, indexOf(chart, sep9)));
			assertEquals("2026-09-09", view.describe().get("hoverDay"));
			assertNotNull(label(view, "Wed 09 Sep"));
			final long v = fixture().on(sep9).valueFor(ViewOptions.DEFAULT);
			assertNotNull(label(view, MovementMath.formatExact(v) + " gp"));

			// The nearest point by x: a pointer 2 px right of the 9th is still the 9th.
			hover(chart, x(chart, indexOf(chart, sep9)) + 2);
			assertEquals("2026-09-09", view.describe().get("hoverDay"));

			// A carried day can be hovered: its readout names it, at the carried total.
			final LocalDate sep8 = LocalDate.of(2026, 9, 8);
			hover(chart, x(chart, indexOf(chart, sep8)));
			assertEquals("2026-09-08", view.describe().get("hoverDay"));
			// Found by its place: the list's own row for the 8th reads "Tue 08 Sep" too, its date alone.
			assertEquals("Tue 08 Sep", BankHistoryViewTest.chartLabel(view, BankHistoryViewTest.READOUT_DAY));
			assertNotNull(label(view, MovementMath.formatExact(fixture().on(sep8.minusDays(1)).valueFor(null)) + " gp"));

			leave(chart);
			assertNull(view.describe().get("hoverDay"));
			assertNotNull(label(view, "Sat 26 Sep"));
			assertNotNull(label(view, "736,412,683 gp"));
			assertEquals("Sat 26 Sep", BankHistoryViewTest.chartLabel(view, BankHistoryViewTest.READOUT_DAY));
		});
	}

	@Test
	public void theChartTellsItsListenerOnlyWhenTheHoveredPointChanges() throws Exception
	{
		onEdt(() ->
		{
			final AtomicInteger told = new AtomicInteger();
			final BankHistoryChart chart = new BankHistoryChart(i -> told.incrementAndGet());
			final List<BankHistoryMath.Day> days = BankHistoryMath.days(fixture(), TODAY.minusDays(30), TODAY, null);
			chart.setData(days, true, 1);
			chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);
			final int x5 = (int) Math.round(BankHistoryChart.xAt(5, days.size(), chart.getWidth()));
			hover(chart, x5);
			hover(chart, x5 + 1);
			hover(chart, x5 - 1);
			assertEquals("three moves over one point: one change", 1, told.get());
			hover(chart, (int) Math.round(BankHistoryChart.xAt(6, days.size(), chart.getWidth())));
			assertEquals(2, told.get());
			leave(chart);
			leave(chart);
			assertEquals(3, told.get());
			assertTrue("one listener serves both roles", chart.getMouseListeners().length == 1
				&& chart.getMouseMotionListeners().length == 1
				&& (Object) chart.getMouseListeners()[0] == chart.getMouseMotionListeners()[0]);
		});
	}

	@Test
	public void theChartHitTestsFromItsOwnWidth() throws Exception
	{
		onEdt(() ->
		{
			for (int width : new int[]{213, 230})
			{
				final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
				layOut(view, width);
				final BankHistoryChart chart = chart(view);
				assertEquals("the chart spans the block's whole width", width, chart.getWidth());
				final int last = chart.days().size() - 1;
				hover(chart, chart.getWidth() - 1);
				assertEquals(width + ": the right edge is today", TODAY.toString(), view.describe().get("hoverDay"));
				hover(chart, 0);
				assertEquals(width + ": the left edge is the range's first day", "2026-08-27",
					view.describe().get("hoverDay"));
				assertEquals(last, chart.indexAt(chart.getWidth()));
			}
		});
	}

	// ---------------------------------------------------------------------------------------- helpers

	/** The chart alone, on the card grey it sits on. */
	static BufferedImage paint(final BankHistoryChart chart)
	{
		final BufferedImage image = new BufferedImage(chart.getWidth(), chart.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setColor(ColorScheme.DARKER_GRAY_COLOR);
			g.fillRect(0, 0, image.getWidth(), image.getHeight());
			chart.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	static int x(final BankHistoryChart chart, final int i)
	{
		return (int) Math.round(BankHistoryChart.xAt(i, chart.days().size(), chart.getWidth()));
	}

	/** How far the most-drawn pixel in column {@code x} is from the card grey, in any channel: any line, any colour. */
	static int ink(final BufferedImage image, final int x)
	{
		final Color ground = ColorScheme.DARKER_GRAY_COLOR;
		int best = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			final Color c = new Color(image.getRGB(x, y));
			best = Math.max(best, Math.max(Math.abs(c.getRed() - ground.getRed()),
				Math.max(Math.abs(c.getGreen() - ground.getGreen()), Math.abs(c.getBlue() - ground.getBlue()))));
		}
		return best;
	}
}
