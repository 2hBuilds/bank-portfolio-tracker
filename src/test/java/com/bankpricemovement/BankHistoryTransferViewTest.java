package com.bankpricemovement;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.Arrays;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_GP;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_PCT;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_TOTAL;
import static com.bankpricemovement.BankHistoryViewTest.chartLabel;
import static com.bankpricemovement.BankHistoryViewTest.edge;
import static com.bankpricemovement.BankHistoryViewTest.rowOf;
import static com.bankpricemovement.BankHistoryViewTest.text;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.hover;
import static com.bankpricemovement.HistoryViewRenderer.indexOf;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.leave;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Release 1.2.2 part 2: what the History view DRAWS and PRINTS for a day the player marked as a transfer - the chart on the shifted
 * totals, the real totals everywhere a total is printed, the corrected change line and rows, the marked row as the user's mock draws
 * it, and the fallback drawing (the real totals, the step into the marked day dotted and marked in orange) when shifting would go
 * below zero. The maths itself is in {@link BankHistoryTransfersTest}; the pictures are pinned by {@code HistoryTransferPicturesTest}.
 */
public class BankHistoryTransferViewTest
{
	private static final LocalDate SEP_19 = LocalDate.of(2026, 9, 19);
	private static final LocalDate SEP_20 = LocalDate.of(2026, 9, 20);
	private static final long M = 1_000_000L;

	/** 931m on the 19th, 731m on the 20th and 736m today: the account sent 200m on the 20th. */
	private static BankHistorySeries sent()
	{
		return BankHistorySeries.of(Arrays.asList(point(SEP_19, 931 * M), point(SEP_20, 731 * M), point(TODAY, 736 * M)))
			.withTransfer(SEP_20, new BankHistorySeries.Transfer(-200 * M, false));
	}

	/** The same account with no note: the cliff. */
	private static BankHistorySeries unmarked()
	{
		return sent().withTransfer(SEP_20, null);
	}

	@Test
	public void theChartIsDrawnOnTheShiftedTotalsAndTheListPrintsTheRealOnes() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(sent(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final BankHistoryMath.Day nineteenth = chart.days().get(indexOf(chart, SEP_19));
			assertEquals("the real total is kept", 931 * M, nineteenth.valueGp());
			assertEquals("the chart draws it 200m lower", 731 * M, chart.value(nineteenth));
			assertEquals("so there is no cliff: the line is flat to the 20th", chart.value(nineteenth),
				chart.value(chart.days().get(indexOf(chart, SEP_20))));
			assertEquals("the day list prints the real total", "931m", text(rowOf(view, SEP_19), 2));
			assertEquals("and the exact one", "931,000,000", text(rowOf(view, SEP_19), 3));
			assertEquals("today is never shifted", 736 * M, chart.value(chart.days().get(chart.days().size() - 1)));
			assertEquals("a high and a low of the drawn line: 736m and 731m", Arrays.asList("736m", "731m"), figures(chart));
		});
	}

	@Test
	public void theChangeLineStopsCountingTheTransfer() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView marked = view(sent(), BankHistoryRange.ALL);
			layOut(marked, 213);
			assertEquals("736m against 731m", "+5m", chartLabel(marked, CHANGE_GP));
			assertEquals("+0.6%", chartLabel(marked, CHANGE_PCT));
			final BankHistoryView plain = view(unmarked(), BankHistoryRange.ALL);
			layOut(plain, 213);
			assertEquals("without the note it reads the loss", "-195m", chartLabel(plain, CHANGE_GP));
			assertEquals("-20.9%", chartLabel(plain, CHANGE_PCT));
		});
	}

	@Test
	public void theReadoutNamesTheFigureTheHoveredPointIsDrawnAt() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(sent(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			hover(chart, (int) Math.round(BankHistoryChart.xAt(indexOf(chart, SEP_19), chart.days().size(), chart.getWidth())));
			assertEquals("a dot must match its number", "731,000,000 gp", chartLabel(view, READOUT_TOTAL));
			leave(chart);
			assertEquals("736,000,000 gp", chartLabel(view, READOUT_TOTAL));
		});
	}

	/**
	 * Release 1.2.2 part 4 (R3): hovering a point drawn at a shifted total shows exactly "<real total> gp at the time"; a point drawn at
	 * its own total shows nothing. The readout line keeps the drawn figure ({@link #theReadoutNamesTheFigureTheHoveredPointIsDrawnAt}).
	 */
	@Test
	public void aShiftedPointSaysItsRealTotalOnHoverAndAnUnshiftedOneSaysNothing() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(sent(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final int n = chart.days().size();
			final int nineteenth = indexOf(chart, SEP_19);
			assertEquals("931,000,000 gp at the time", chart.tipAt(nineteenth));
			assertEquals("the 20th is drawn at its own total", null, chart.tipAt(indexOf(chart, SEP_20)));
			assertEquals("so is today, which is never shifted", null, chart.tipAt(n - 1));
			assertEquals(null, chart.tipAt(-1));
			assertEquals(null, chart.tipAt(n));

			// Swing asks the chart per pointer place - and the chart is registered, or it would never be asked.
			assertTrue("registered with the tooltip manager", Arrays.stream(chart.getMouseListeners())
				.anyMatch(l -> l instanceof javax.swing.ToolTipManager));
			assertEquals("931,000,000 gp at the time", chart.getToolTipText(at(chart, (int) Math.round(
				BankHistoryChart.xAt(nineteenth, n, chart.getWidth())))));
			assertEquals("no fixed text on the chart itself", null, chart.getToolTipText());
			assertEquals("the pointer over today: no tip", null, chart.getToolTipText(at(chart, chart.getWidth() - 10)));
		});
	}

	@Test
	public void theHoverTipIsMaskedWithTheEyeShutAndAbsentWithoutAShift() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = new BankHistoryView(() -> HistoryViewRenderer.noon(TODAY), HistoryViewRenderer.ZONE);
			view.setAmountsHidden(true);
			view.setRange(BankHistoryRange.ALL);
			view.show(sent(), ViewOptions.DEFAULT);
			layOut(view, 213);
			final BankHistoryChart hidden = chart(view);
			assertEquals("the amount's mask in its place", AmountMask.amount(true, "931,000,000") + " gp at the time",
				hidden.tipAt(indexOf(hidden, SEP_19)));
			assertNotEquals("931,000,000 gp at the time", hidden.tipAt(indexOf(hidden, SEP_19)));
			view.setAmountsHidden(false);
			layOut(view, 213);
			assertEquals("931,000,000 gp at the time", chart(view).tipAt(indexOf(chart(view), SEP_19)));

			final BankHistoryView plain = view(unmarked(), BankHistoryRange.ALL);
			layOut(plain, 213);
			for (int i = 0; i < chart(plain).days().size(); i++)
			{
				assertEquals("no note, no shift, no tip", null, chart(plain).tipAt(i));
			}
		});
	}

	/** The real-totals drawing (a shift below zero) draws every point at its own total, so no point has a tip. */
	@Test
	public void theBelowZeroFallbackDrawsRealTotalsSoItHasNoTips() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(feeder(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			assertTrue("the premise: the 19th's shifted total is below zero",
				chart.days().get(indexOf(chart, SEP_19)).drawnGp() < 0);
			for (int i = 0; i < chart.days().size(); i++)
			{
				assertEquals(null, chart.tipAt(i));
			}
		});
	}

	private static java.awt.event.MouseEvent at(final BankHistoryChart chart, final int x)
	{
		return new java.awt.event.MouseEvent(chart, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x,
			chart.getHeight() / 2, 0, false);
	}

	/** The mock's row: the real total, a corrected 0.0% on a grey rail, the note's amount in grey, "vs 19 Sep," and the orange word. */
	@Test
	public void aMarkedRowReadsAsTheMockDrawsIt() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(sent(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryDayRow row = rowOf(view, SEP_20);
			assertEquals(7, row.getComponentCount());
			assertEquals("Sun 20 Sep", text(row, 0));
			assertEquals("the sub-line ends at the comma; the word is the next label", "vs 19 Sep,", text(row, 1));
			assertEquals("the real total", "731m", text(row, 2));
			assertEquals("731,000,000", text(row, 3));
			assertEquals("the corrected change: nothing moved but the transfer", "0.0%", text(row, 4));
			assertEquals("the note's amount stands where the gp change does", "-200m", text(row, 5));
			assertEquals("transfer", text(row, 6));
			final JLabel word = (JLabel) row.getComponent(6);
			final JLabel sub = (JLabel) row.getComponent(1);
			assertEquals(ColorScheme.BRAND_ORANGE, word.getForeground());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, sub.getForeground());
			assertEquals("the figure is grey, not red", ColorScheme.LIGHT_GRAY_COLOR, row.getComponent(5).getForeground());
			assertEquals("a flat rail", BankHistoryDayRow.FLAT_EDGE, edge(row));
			assertTrue("the word starts where the sub-line ends", word.getX() >= sub.getX() + sub.getPreferredSize().width);
			assertTrue("and stays inside the date column", word.getX() + word.getWidth() <= BankHistoryDayRow.LEFT_COLUMN
				+ Widgets.EDGE_WIDTH + BankHistoryDayRow.PAD_LEFT);
			assertEquals("the word is not cut", word.getPreferredSize().width, word.getWidth());
			assertEquals("an unmarked row keeps its six labels", 6, rowOf(view, SEP_19).getComponentCount());
			assertEquals("and its sub-line", "first reading", text(rowOf(view, SEP_19), 1));
		});
	}

	@Test
	public void withTheEyeShutTheNotesAmountIsMaskedLikeEveryGpFigure() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = new BankHistoryView(() -> HistoryViewRenderer.noon(TODAY), HistoryViewRenderer.ZONE);
			view.setAmountsHidden(true);
			view.setRange(BankHistoryRange.ALL);
			view.show(sent(), ViewOptions.DEFAULT);
			layOut(view, 213);
			assertEquals(AmountMask.change(true, "-200m"), text(rowOf(view, SEP_20), 5));
			assertEquals("the word and the percentage are not amounts", "transfer", text(rowOf(view, SEP_20), 6));
			assertEquals("0.0%", text(rowOf(view, SEP_20), 4));
		});
	}

	/** The widest honest sub-line a marked row can print - a May date - fits the 95 px column whole. */
	@Test
	public void theWidestMarkedSubLineFitsItsColumn() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryDayRow row = new BankHistoryDayRow(new BankHistoryDayRow.Model(TODAY, false, "Wed 30 Sep", "vs 01 May,",
				"731m", "12,345,678,901", "0.0%", "-200m", 0, false, true), ChangeText.DEFAULT);
			row.setSize(Widgets.CONTENT_WIDTH, BankHistoryDayRow.HEIGHT);
			HistoryViewRenderer.layoutTree(row);
			final JLabel sub = (JLabel) row.getComponent(1);
			final JLabel word = (JLabel) row.getComponent(6);
			final JLabel exact = (JLabel) row.getComponent(3);
			assertEquals("the sub-line is whole", sub.getPreferredSize().width, sub.getWidth());
			assertEquals("so is the word", word.getPreferredSize().width, word.getWidth());
			assertTrue("and the word stops before the exact total starts", word.getX() + word.getWidth() <= exact.getX());
		});
	}

	@Test
	public void aCoinsNoteWithTheCoinsSwitchOffDrawsNothingAndMarksNothing() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries coins = sent().withTransfer(SEP_20, new BankHistorySeries.Transfer(-200 * M, true));
			final BankHistoryView view = new BankHistoryView(() -> HistoryViewRenderer.noon(TODAY), HistoryViewRenderer.ZONE);
			view.setRange(BankHistoryRange.ALL);
			view.show(coins, ViewOptions.DEFAULT.withCountCash(false));
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final BankHistoryMath.Day nineteenth = chart.days().get(indexOf(chart, SEP_19));
			assertEquals("no shift while the coins are not counted", nineteenth.valueGp(), chart.value(nineteenth));
			assertEquals("the row is an ordinary one", 6, rowOf(view, SEP_20).getComponentCount());
			assertNotEquals("vs 19 Sep,", text(rowOf(view, SEP_20), 1));
			view.show(coins, ViewOptions.DEFAULT);
			layOut(view, 213);
			assertEquals("counted again: the shift and the mark are back", 7, rowOf(view, SEP_20).getComponentCount());
		});
	}

	// ---------------------------------------------------------------------------------------- the fallback drawing

	/** Earned its gold and sent 200m: shifted, the 19th would be drawn at -100m. */
	private static BankHistorySeries feeder()
	{
		return BankHistorySeries.of(Arrays.asList(point(SEP_19, 100 * M), point(SEP_20, 600 * M), point(TODAY, 610 * M)))
			.withTransfer(SEP_20, new BankHistorySeries.Transfer(-200 * M, false));
	}

	@Test
	public void whenShiftingWouldGoBelowZeroTheChartDrawsTheRealTotalsAndMarksTheNote() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(feeder(), BankHistoryRange.ALL);
			layOut(view, 213);
			final BankHistoryChart chart = chart(view);
			final BankHistoryMath.Day nineteenth = chart.days().get(indexOf(chart, SEP_19));
			assertTrue("shifting it would go below zero", nineteenth.drawnGp() < 0);
			assertEquals("so the chart draws the real total", 100 * M, chart.value(nineteenth));
			assertEquals("100m", figures(chart).get(1));
			assertTrue("the orange mark is on the 20th's point", hasOrange(paint(chart)));
			// The figures are corrected either way: 610m against the 19th's drawn -100m is +710m, but the percentage has no base.
			assertEquals("+710m", chartLabel(view, CHANGE_GP));
			assertEquals("a base of 0 or less has no percentage", MovementMath.DASH, chartLabel(view, CHANGE_PCT));
			hover(chart, (int) Math.round(BankHistoryChart.xAt(indexOf(chart, SEP_19), chart.days().size(), chart.getWidth())));
			assertEquals("the readout is the real total", "100,000,000 gp", chartLabel(view, READOUT_TOTAL));
		});
	}

	@Test
	public void theShiftedDrawingHasNoOrangeAndTheFallbackIsOnlyForWhatCanBeSeen() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView shifted = view(sent(), BankHistoryRange.ALL);
			layOut(shifted, 213);
			assertFalse("no orange in the smooth line", hasOrange(paint(chart(shifted))));

			// Hide the 19th the way "Reset history" does: the days that remain never go below zero, so the line is the shifted one.
			final BankHistorySeries hiding = feeder().withFreshFrom(SEP_20).withFreshWhy(BankHistorySeries.WHY_PLAYER);
			final BankHistoryView seen = view(hiding.fromFresh(), BankHistoryRange.ALL);
			layOut(seen, 213);
			final BankHistoryChart chart = chart(seen);
			assertEquals(SEP_20, chart.days().get(0).day());
			assertEquals("a hidden day decides nothing", chart.days().get(0).drawnGp(), chart.value(chart.days().get(0)));
			assertFalse(hasOrange(paint(chart)));

			final BankHistoryView all = view(hiding, BankHistoryRange.ALL);
			layOut(all, 213);
			assertTrue("bring the days back and the fallback is back", hasOrange(paint(chart(all))));
		});
	}

	@Test
	public void aNoteInAThinnedBucketMarksTheDrawnDayThatStandsForIt() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryChart chart = new BankHistoryChart(i -> { });
			final BankHistorySeries series = feeder();
			final java.util.List<BankHistoryMath.Day> days = BankHistoryMath.days(series, null, TODAY, ViewOptions.DEFAULT);
			// The 20th is not among these days (a thinned chart drew the 25th, a carried day, instead): the first drawn day on or after it
			// stands for it.
			final java.util.List<BankHistoryMath.Day> drawn = Arrays.asList(days.get(0), days.get(days.size() - 2), days.get(days.size() - 1));
			chart.setSize(213, BankHistoryChart.PLOT_HEIGHT);
			chart.setData(drawn, true, 1, java.util.Collections.singleton(SEP_20));
			assertTrue(hasOrange(paint(chart)));
			chart.setData(drawn, true, 1);
			assertFalse("with no marks it is the shifted drawing", hasOrange(paint(chart)));
		});
	}

	private static java.util.List<String> figures(final BankHistoryChart chart)
	{
		final java.util.List<String> out = new java.util.ArrayList<>();
		for (BankHistoryChart.Figure f : chart.figures())
		{
			out.add(f.text);
		}
		return out;
	}

	private static BufferedImage paint(final BankHistoryChart chart)
	{
		final BufferedImage image = new BufferedImage(chart.getWidth(), chart.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			chart.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/** Whether any pixel is exactly the brand orange: the mark's centre, which the 4 px dot paints solid. */
	private static boolean hasOrange(final BufferedImage image)
	{
		final int orange = ColorScheme.BRAND_ORANGE.getRGB();
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (image.getRGB(x, y) == orange)
				{
					return true;
				}
			}
		}
		return false;
	}

}
