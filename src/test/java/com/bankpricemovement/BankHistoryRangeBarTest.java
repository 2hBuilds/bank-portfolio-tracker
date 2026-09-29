package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryViewFitTest.assertFits;
import static com.bankpricemovement.BankHistoryViewTest.BAR;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_DAYS;
import static com.bankpricemovement.BankHistoryViewTest.CHANGE_PCT;
import static com.bankpricemovement.BankHistoryViewTest.CHART;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_DAY;
import static com.bankpricemovement.BankHistoryViewTest.READOUT_TOTAL;
import static com.bankpricemovement.BankHistoryViewTest.bar;
import static com.bankpricemovement.BankHistoryViewTest.chartBlock;
import static com.bankpricemovement.BankHistoryViewTest.hover;
import static com.bankpricemovement.BankHistoryViewTest.lit;
import static com.bankpricemovement.BankHistoryViewTest.press;
import static com.bankpricemovement.BankHistoryViewTest.rangeCell;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.fixture;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The chart block's order and the range bar under the chart (the user, 2026-09-29: "design 2 but i want the chips below
 * like design 8"): the change line, the rule, the readout line, the 110 px chart and then the chips "7d 30d 90d all" as
 * ONE segmented bar in the Items | Net Worth History toggle's family - four equal cells in a thin grey frame, the lit
 * one filled orange with dark bold text, a range with nothing older than its start dimmed and still clickable.
 */
public class BankHistoryRangeBarTest
{
	private static final int[] WIDTHS = {213, 230};

	/** Top to bottom, at both widths: change line, rule, readout line, chart, bar, and 6 px of card - 183 px in all. */
	@Test
	public void theBlockIsChangeLineRuleReadoutChartThenTheBar() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
				layOut(view, width);
				final Container block = chartBlock(view);
				assertEquals(width + ": the block's height", 183, block.getHeight());
				assertEquals(width + ": the block's rows, in order", 7, block.getComponentCount());
				assertEquals("27 Aug - 26 Sep", ((JLabel) block.getComponent(CHANGE_DAYS)).getText());
				assertEquals("Sat 26 Sep", ((JLabel) block.getComponent(READOUT_DAY)).getText());
				assertSame(chart(view), block.getComponent(CHART));
				assertSame(bar(view), block.getComponent(BAR));

				// Baselines: the change line's at 17, the readout's at 39.
				assertEquals(width + ": the change line's baseline", 17, baseline((JLabel) block.getComponent(CHANGE_DAYS)));
				assertEquals(17, baseline((JLabel) block.getComponent(CHANGE_PCT)));
				assertEquals(width + ": the readout's baseline", 39, baseline((JLabel) block.getComponent(READOUT_DAY)));
				assertEquals(39, baseline((JLabel) block.getComponent(READOUT_TOTAL)));

				// The chart: the block's whole width, 45..154.
				assertEquals(new Rectangle(0, 45, width, BankHistoryChart.PLOT_HEIGHT), chart(view).getBounds());
				// The bar: 4 px under the chart, 6 px of card either side and under it, 18 px tall.
				final Rectangle bar = bar(view).getBounds();
				assertEquals(new Rectangle(6, 159, width - 12, 18), bar);
				assertEquals("6 px of card under the bar", 6, block.getHeight() - (bar.y + bar.height));

				// The rule between the change line and the readout: one row of the sidebar's grey at y 23.
				final BufferedImage image = HistoryViewRenderer.paint(view, width);
				final int rule = ColorScheme.DARK_GRAY_COLOR.getRGB() & 0xFFFFFF;
				for (int x = 8; x <= width - 9; x++)
				{
					assertEquals(width + ": the rule at x " + x, rule, image.getRGB(x, 23) & 0xFFFFFF);
				}
				assertEquals("the card under it", ColorScheme.DARKER_GRAY_COLOR.getRGB() & 0xFFFFFF,
					image.getRGB(100, 24) & 0xFFFFFF);
			}
		});
	}

	/**
	 * Four equal cells in a 1 px frame with 1 px dividers, all of the toggle's frame grey: at 213 the bar is 201 px and
	 * each cell 49 (block x 7..55, 57..105, 107..155, 157..205); at 230 the four share 213 px, the last one the odd pixel.
	 */
	@Test
	public void theBarIsFourEqualCellsInAThinGreyFrame() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
				layOut(view, width);
				final BankHistoryView.RangeBar bar = bar(view);
				assertEquals(4, bar.getComponentCount());
				final List<Integer> widths = new ArrayList<>();
				int x = 1;
				for (BankHistoryRange r : BankHistoryRange.values())
				{
					final JLabel cell = rangeCell(view, r);
					assertEquals(r.label(), cell.getText());
					assertEquals(width + " " + r + ": 1 px after the frame or the divider before it", x, cell.getX());
					widths.add(cell.getWidth());
					x += cell.getWidth() + 1;
				}
				assertEquals(width + ": the last cell ends 1 px inside the frame", bar.getWidth(), x);
				final int least = widths.stream().mapToInt(Integer::intValue).min().getAsInt();
				final int most = widths.stream().mapToInt(Integer::intValue).max().getAsInt();
				assertTrue(width + ": equal cells " + widths, most - least <= 1);
				if (width == 213)
				{
					assertEquals(Arrays.asList(49, 49, 49, 49), widths);
				}

				// Painted: the frame's four sides and the three dividers are the toggle's grey, the cells' grounds not.
				final BufferedImage image = HistoryViewRenderer.paint(view, width);
				final int frame = ColorScheme.MEDIUM_GRAY_COLOR.getRGB() & 0xFFFFFF;
				final Rectangle b = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), view);
				for (int px = b.x; px < b.x + b.width; px++)
				{
					assertEquals("top edge at " + px, frame, image.getRGB(px, b.y) & 0xFFFFFF);
					assertEquals("bottom edge at " + px, frame, image.getRGB(px, b.y + b.height - 1) & 0xFFFFFF);
				}
				for (int py = b.y; py < b.y + b.height; py++)
				{
					assertEquals("left edge at " + py, frame, image.getRGB(b.x, py) & 0xFFFFFF);
					assertEquals("right edge at " + py, frame, image.getRGB(b.x + b.width - 1, py) & 0xFFFFFF);
				}
				int divider = b.x + 1;
				for (int k = 0; k < 3; k++)
				{
					divider += widths.get(k);
					for (int py = b.y + 1; py < b.y + b.height - 1; py++)
					{
						assertEquals("divider " + k + " at " + py, frame, image.getRGB(divider, py) & 0xFFFFFF);
					}
					divider++;
				}
				assertEquals("an unlit cell's ground is the card", ColorScheme.DARKER_GRAY_COLOR.getRGB() & 0xFFFFFF,
					image.getRGB(b.x + 3, b.y + 3) & 0xFFFFFF);
				assertEquals("the lit cell's ground is orange", ColorScheme.BRAND_ORANGE.getRGB() & 0xFFFFFF,
					image.getRGB(b.x + 1 + widths.get(0) + 1 + 2, b.y + 3) & 0xFFFFFF);
			}
		});
	}

	/**
	 * The lit cell orange with its word dark and bold; the unlit ones the card with light grey plain words; a range with
	 * nothing older than its start in the dimmed grey - "all" never, the lit one never.
	 */
	@Test
	public void theLitCellIsOrangeWithDarkBoldTextAndTheDimmedOnesGrey() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final JLabel d7 = rangeCell(view, BankHistoryRange.D7);
			final JLabel d30 = rangeCell(view, BankHistoryRange.D30);
			final JLabel d90 = rangeCell(view, BankHistoryRange.D90);
			final JLabel all = rangeCell(view, BankHistoryRange.ALL);
			assertTrue(lit(d30));
			assertTrue("bold", d30.getFont().isBold());
			assertEquals(12, d30.getFont().getSize());
			for (JLabel unlit : Arrays.asList(d7, d90, all))
			{
				assertFalse(lit(unlit));
				assertEquals(ColorScheme.DARKER_GRAY_COLOR, unlit.getBackground());
				assertFalse("plain", unlit.getFont().isBold());
				assertEquals(12, unlit.getFont().getSize());
			}
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, d7.getForeground());
			assertEquals("40 days of readings: nothing 90 days old", ColorScheme.MEDIUM_GRAY_COLOR, d90.getForeground());
			assertEquals("'all' is never dimmed", ColorScheme.LIGHT_GRAY_COLOR, all.getForeground());

			// Picked, the dimmed range is lit - lit wins over dimmed - and 30d goes back to plain.
			press(d90);
			assertTrue(lit(d90));
			assertTrue(d90.getFont().isBold());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, d30.getForeground());
			assertFalse(d30.getFont().isBold());

			// One reading today: every range but "all" reaches past it - 7d is dimmed like 90d until it is lit.
			final BankHistoryView one = view(HistoryViewRenderer.oneReading(), BankHistoryRange.D30);
			layOut(one, 213);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, rangeCell(one, BankHistoryRange.D7).getForeground());
			assertTrue(lit(rangeCell(one, BankHistoryRange.D30)));
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, rangeCell(one, BankHistoryRange.D90).getForeground());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, rangeCell(one, BankHistoryRange.ALL).getForeground());
		});
	}

	/**
	 * A left press on each cell picks its range - the chart moves, the list does not, nothing else is told - a press on
	 * the lit cell and a right press change nothing, a dimmed cell answers like any other, and the card's setRange still
	 * overrules the bar.
	 */
	@Test
	public void aPressOnEachCellPicksItsRange() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			layOut(view, 213);
			final int rows = (Integer) view.describe().get("rows");
			final int[] drawn = {31, 40, 40, 8};
			final BankHistoryRange[] order = {BankHistoryRange.D30, BankHistoryRange.D90, BankHistoryRange.ALL,
				BankHistoryRange.D7};
			for (int k = 0; k < order.length; k++)
			{
				final BankHistoryRange r = order[k];
				press(rangeCell(view, r));
				assertEquals(r.label(), view.describe().get("range"));
				assertTrue(r + " is lit", lit(rangeCell(view, r)));
				assertEquals(r + ": the chart's days", drawn[k], chart(view).days().size());
				assertEquals("the list does not move with the chart", rows, view.describe().get("rows"));
			}

			// The lit cell again: nothing - the chart is not even re-fed.
			final List<BankHistoryMath.Day> before = chart(view).days();
			press(rangeCell(view, BankHistoryRange.D7));
			assertSame(before, chart(view).days());

			// A right press picks nothing.
			final JLabel d30 = rangeCell(view, BankHistoryRange.D30);
			d30.dispatchEvent(new MouseEvent(d30, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
				InputEvent.BUTTON3_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON3));
			assertEquals("7d", view.describe().get("range"));

			// The card's range overrules the bar's.
			press(d30);
			view.setRange(BankHistoryRange.ALL);
			assertEquals("all", view.describe().get("range"));
			assertTrue(lit(rangeCell(view, BankHistoryRange.ALL)));
			assertFalse(lit(d30));
		});
	}

	/** The mouse lights an unlit cell's word orange - a dimmed one too - and gives it back its own grey; the lit one stays. */
	@Test
	public void theMouseLightsAnUnlitCellsWordAndGivesItBack() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final JLabel d7 = rangeCell(view, BankHistoryRange.D7);
			final JLabel d90 = rangeCell(view, BankHistoryRange.D90);
			final JLabel d30 = rangeCell(view, BankHistoryRange.D30);
			hover(d7, MouseEvent.MOUSE_ENTERED);
			assertEquals(ColorScheme.BRAND_ORANGE, d7.getForeground());
			hover(d7, MouseEvent.MOUSE_EXITED);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, d7.getForeground());
			hover(d90, MouseEvent.MOUSE_ENTERED);
			assertEquals(ColorScheme.BRAND_ORANGE, d90.getForeground());
			hover(d90, MouseEvent.MOUSE_EXITED);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, d90.getForeground());
			hover(d30, MouseEvent.MOUSE_ENTERED);
			assertTrue("the lit cell does not react", lit(d30));
			assertTrue("no hover text", d7.getToolTipText() == null && bar(view).getToolTipText() == null);
		});
	}

	/**
	 * Each word fits its cell whole at 213 and at 230, measured with its own FontMetrics lit (bold) and unlit, and
	 * stands on the bar's baseline, 13 px under its top: its 9 px of ink 3 px under the frame and 4 px over it.
	 */
	@Test
	public void theBarsWordsFitTheirCellsAndStandOnItsBaseline() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				for (BankHistoryRange litRange : BankHistoryRange.values())
				{
					final BankHistoryView view = view(fixture(), litRange);
					layOut(view, width);
					final BankHistoryView.RangeBar bar = bar(view);
					final List<JLabel> cells = new ArrayList<>();
					for (Component c : bar.getComponents())
					{
						cells.add((JLabel) c);
					}
					assertFits(width + " lit " + litRange, bar, cells);
					for (JLabel cell : cells)
					{
						final FontMetrics fm = cell.getFontMetrics(cell.getFont());
						assertTrue(width + " '" + cell.getText() + "' is whole", fm.stringWidth(cell.getText())
							<= cell.getWidth() - cell.getInsets().left - cell.getInsets().right);
						assertEquals(width + " '" + cell.getText() + "' stands on the bar's baseline",
							BankHistoryView.RangeBar.TEXT_BASELINE, cell.getY() + cell.getInsets().top + fm.getAscent());
					}
				}
			}
		});
	}

	/**
	 * A range whose start has no reading is dimmed and clickable in the bar, and picking it draws what there is: the
	 * change since the first reading, as before the bar moved.
	 */
	@Test
	public void aDimmedCellIsClickableAndDrawsWhatThereIs() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(10), 100_000_000L),
				point(TODAY, 110_000_000L)));
			final BankHistoryView view = view(s, BankHistoryRange.D7);
			layOut(view, 213);
			final JLabel d30 = rangeCell(view, BankHistoryRange.D30);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, d30.getForeground());
			press(d30);
			assertEquals("30d", view.describe().get("range"));
			assertEquals(MovementMath.formatDay(TODAY.minusDays(10)) + " - " + MovementMath.formatDay(TODAY),
				BankHistoryViewTest.chartLabel(view, CHANGE_DAYS));
			assertEquals(LocalDate.of(2026, 9, 16), chart(view).days().get(0).day());
		});
	}

	// ---------------------------------------------------------------------------------------- helpers

	/** Where a label's text stands, from its parent's top: the label's own top-centred layout of its font. */
	private static int baseline(final JLabel label)
	{
		final Font font = label.getFont();
		final FontMetrics fm = label.getFontMetrics(font);
		return label.getY() + (label.getHeight() - fm.getHeight()) / 2 + fm.getAscent();
	}
}
