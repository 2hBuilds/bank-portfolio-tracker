package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static com.bankpricemovement.BankHistoryViewTest.label;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.layoutTree;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, builder C: the History view's columns are MEASURED at 213 px (plan 7.2 item 2, amendment 9.8), in
 * the style of {@code MovementRowPanelTest.aRiseOfAHundredPercentOrMoreFitsItsColumnWhole}: the widest honest row -
 * "12,345,678,901" exact, "+1.66m", "-100.0%", "vs 28 May, 188 days" - has no two labels overlapping and nothing
 * cut with an ellipsis, each label measured with its OWN {@link FontMetrics}; and the same for the change line and
 * the readout line. Checked again at 230 px, the other end of the widths the view is laid out at.
 */
public class BankHistoryViewFitTest
{
	private static final int[] WIDTHS = {213, 230};

	@Test
	public void theWidestHonestRowFitsAt213WithNothingCutOrOverlapping() throws Exception
	{
		onEdt(() ->
		{
			final List<BankHistoryDayRow.Model> worst = Arrays.asList(
				// The widest sub-line (a May date, a 3-digit span: 95 px) beside the widest gp figures the row face
				// prints ("+1.66m" and "+1,00m", 36 px each): line 2 is 199 of 200 px at 213 (review C-FIT-1).
				row("Wed 28 May", "vs 28 May, 188 days", "12.3b", "12,345,678,901", "-100.0%", "+1.66m", 1),
				row("Wed 28 May", "vs 01 May, 100 days", "12.3b", "12,345,678,901", "+9999%", "+1,00m", 1),
				row("01 May 2025", "vs 28 May, 188 days", "99.9m", "99,999,999,999", "-100.0%", "+1.21m", 1),
				row("Wed 30 Sep", "vs 30 Aug, 123 days", "12.3b", "12,345,678,901", "-100.0%", "-1.23b", -1),
				row("Wed 30 Sep", "vs 30 Aug, 123 days", "12.3b", "12,345,678,901", "+9999%", "+1.23b", 1),
				row("30 Sep 2025", "vs 30 Aug, 123 days", "99.9m", "12,345,678,901", "-100.0%", "+3.41m", 1),
				row("Wed 30 Sep", "vs 30 Aug, 123 days", "2.14b", "99,999,999,999", "-99.9%", "-12.3m", -1));
			for (int width : WIDTHS)
			{
				for (BankHistoryDayRow.Model model : worst)
				{
					final BankHistoryDayRow row = new BankHistoryDayRow(model);
					row.setSize(width, BankHistoryDayRow.HEIGHT);
					row.doLayout();
					assertFits(width + " " + model, row, labels(row));
				}
			}
		});
	}

	@Test
	public void thePremiseTheExactTotalAt10pxWouldNotFitBesideTheWidestSubLine() throws Exception
	{
		onEdt(() ->
		{
			// Why the exact total is 9 px: at the sub-lines' 10 px, line 2 of the widest honest row wants more than the
			// row has at 213 px.
			final BankHistoryDayRow row = new BankHistoryDayRow(
				row("Wed 28 May", "vs 28 May, 188 days", "12.3b", "12,345,678,901", "-100.0%", "+1.66m", 1));
			row.setSize(213, BankHistoryDayRow.HEIGHT);
			row.doLayout();
			final JLabel sub = (JLabel) row.getComponent(1);
			final JLabel exact = (JLabel) row.getComponent(3);
			final JLabel gp = (JLabel) row.getComponent(5);
			final int room = gp.getX() - BankHistoryDayRow.GAP - exact.getX();
			final FontMetrics tenPx = exact.getFontMetrics(BankHistoryDayRow.SUB_FONT);
			assertTrue("'12,345,678,901' at 10 px (" + tenPx.stringWidth("12,345,678,901") + ") overflows its "
				+ room + " px", tenPx.stringWidth("12,345,678,901") > room);
			assertTrue("...at 9 px it fits", exact.getFontMetrics(exact.getFont()).stringWidth("12,345,678,901") <= room);
			// And why the date column is 95 and the gap 2 (review C-FIT-1): a May sub-line with a 3-digit span is wider
			// than the 94 px the column was first given, and the widest gp figure is "+1.66m", not "-1.23b".
			final FontMetrics subFm = sub.getFontMetrics(sub.getFont());
			assertTrue("'vs 28 May, 188 days' is wider than 94 px: " + subFm.stringWidth(sub.getText()),
				subFm.stringWidth(sub.getText()) > 94);
			final FontMetrics gpFm = gp.getFontMetrics(gp.getFont());
			assertTrue("'+1.66m' is wider than '-1.23b'", gpFm.stringWidth("+1.66m") > gpFm.stringWidth("-1.23b"));
		});
	}

	@Test
	public void theWidestCarriedRowFits() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				for (String words : Arrays.asList("Wed 30 Sep", "30 Sep 2025"))
				{
					final BankHistoryDayRow row = new BankHistoryDayRow(new BankHistoryDayRow.Model(TODAY, true, words,
						"", "", "", "", "", 0, false));
					row.setSize(width, BankHistoryDayRow.CARRIED_HEIGHT);
					row.doLayout();
					assertFits(width + " " + words, row, labels(row));
				}
			}
		});
	}

	@Test
	public void theHeaderStandsOverTheColumns() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				final javax.swing.JPanel header = BankHistoryDayRow.header();
				header.setSize(width, BankHistoryDayRow.HEADER_HEIGHT);
				header.doLayout();
				final BankHistoryDayRow row = new BankHistoryDayRow(
					row("Wed 30 Sep", "vs 30 Aug, 123 days", "12.3b", "12,345,678,901", "-100.0%", "-1.23b", -1));
				row.setSize(width, BankHistoryDayRow.HEIGHT);
				row.doLayout();
				final List<JLabel> h = labels(header);
				assertEquals("Day starts where the date does", row.getComponent(0).getX(), h.get(0).getX());
				assertEquals("Bank Total where the totals do", row.getComponent(2).getX(), h.get(1).getX());
				assertEquals("Change ends where the change does", row.getComponent(4).getX() + row.getComponent(4).getWidth(),
					h.get(2).getX() + h.get(2).getWidth());
				assertFits(width + " header", header, h);
				assertEquals("the header's three words", 3, h.size());
				assertEquals(BankHistoryDayRow.TOTAL_HEADER, "Bank Total");
				assertEquals("Bank Total", h.get(1).getText());
				final java.awt.FontMetrics fm = h.get(1).getFontMetrics(h.get(1).getFont());
				final int textWidth = fm.stringWidth(h.get(1).getText());
				assertTrue(width + " Bank Total is not cut: box " + h.get(1).getWidth() + " for " + textWidth,
					h.get(1).getWidth() >= textWidth);
				final JLabel first = h.get(0);
				final JLabel last = h.get(2);
				assertTrue(width + " Day ends before Bank Total starts",
					first.getX() + first.getWidth() <= h.get(1).getX());
				assertTrue(width + " Bank Total keeps 6 px of air before Change: "
						+ (last.getX() - (h.get(1).getX() + textWidth)),
					last.getX() - (h.get(1).getX() + textWidth) >= 6);
			}
		});
	}

	@Test
	public void theChangeLineAndTheReadoutFitWithTheirWidestText() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				// Twelve billion falling to 12,345,678,901 over the range: the widest figures a real bank prints.
				final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(
					point(TODAY.minusDays(30), 13_575_678_901L), point(TODAY, 12_345_678_901L))), BankHistoryRange.D30);
				layOut(view, width);
				final JLabel days = label(view, "27 Aug - 26 Sep");
				final JLabel gp = label(view, "-1.23b");
				final JLabel pct = label(view, "-9.0%");
				final JLabel readoutDay = label(view, "Sat 26 Sep");
				final JLabel readoutTotal = label(view, "12,345,678,901 gp");
				assertNotNull(days);
				assertNotNull(gp);
				assertNotNull(pct);
				assertNotNull(readoutDay);
				assertNotNull(readoutTotal);
				// The widest text each can be given: the longest date pair, a fall of all of it, the widest weekday.
				days.setText("30 Aug - 30 Sep");
				pct.setText("-100.0%");
				gp.setText("+12.3m");
				readoutDay.setText("Wed 30 Sep");
				final Container block = days.getParent();
				layoutTree(block);
				assertFits(width + " change line", block, Arrays.asList(days, gp, pct));
				assertFits(width + " readout line", block, Arrays.asList(readoutDay, readoutTotal));
				final Rectangle ring = new Rectangle(readoutDay.getX(), readoutDay.getY(),
					readoutDay.getIcon().getIconWidth(), readoutDay.getHeight());
				assertTrue("the ring stands inside the day's label", readoutDay.getBounds().contains(ring));
				assertTrue("the chart is inside the block", block.getBounds().width >= chart(view).getX()
					+ chart(view).getWidth());
			}
		});
	}

	/**
	 * The readout follows the list's year rule through the list's own helper ({@link BankHistoryDayRow#dayText}): a
	 * hovered point MORE than 300 days before today prints "03 Dec 2025" with no weekday, one exactly 300 days back
	 * keeps "Thu 03 Dec"-style weekday form; and with the year and the widest total the line still fits 213 and 230 px
	 * with nothing cut, each label measured with its own FontMetrics.
	 */
	@Test
	public void theReadoutPrintsTheYearOnlyPastThreeHundredDaysAndStillFits() throws Exception
	{
		onEdt(() ->
		{
			for (int width : WIDTHS)
			{
				for (int back : new int[] {301, 300})
				{
					final LocalDate day = TODAY.minusDays(back);
					final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(
						point(day, 12_345_678_901L), point(TODAY, 12_345_678_901L))), BankHistoryRange.ALL);
					layOut(view, width);
					HistoryViewRenderer.hover(chart(view), 0);
					final String expected = BankHistoryDayRow.dayText(day, TODAY);
					final JLabel readoutDay = readoutLabel(view);
					assertEquals(width + " " + back + " days back", expected, readoutDay.getText());
					if (back == 301)
					{
						assertEquals("a year, and no weekday", MovementMath.formatDay(day) + " " + day.getYear(), expected);
					}
					else
					{
						assertEquals("no year at 300", BankHistoryDayRow.weekday(day), expected);
					}
					final JLabel readoutTotal = label(view, "12,345,678,901 gp");
					final Container block = readoutDay.getParent();
					layoutTree(block);
					assertFits(width + " readout line " + back, block, Arrays.asList(readoutDay, readoutTotal));
				}
			}
		});
	}

	/** The readout's day label: the one carrying the ring icon. */
	private static JLabel readoutLabel(final BankHistoryView view)
	{
		for (Component c : BankHistoryViewTest.tree(view))
		{
			if (c instanceof JLabel && ((JLabel) c).getIcon() != null && !((JLabel) c).getText().isEmpty())
			{
				return (JLabel) c;
			}
		}
		throw new AssertionError("no readout day label");
	}

	@Test
	public void theWholeViewLaysOutAtEveryWidthFrom213To230() throws Exception
	{
		onEdt(() ->
		{
			for (int width = 213; width <= 230; width++)
			{
				final BankHistoryView view = view(HistoryViewRenderer.fixture(), BankHistoryRange.D30);
				layOut(view, width);
				assertEquals("its preferred height is its content", view.getPreferredSize().height, view.getHeight());
				for (Component c : BankHistoryViewTest.tree(view))
				{
					final Rectangle r = SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), view);
					assertTrue(width + ": " + c.getClass().getSimpleName() + " " + r + " stays inside the view",
						r.x >= 0 && r.x + r.width <= width);
				}
				for (BankHistoryDayRow row : BankHistoryViewTest.rows(view))
				{
					assertEquals("a row is as wide as the view", width, row.getWidth());
					assertFits(width + " " + row.model(), row, labels(row));
				}
			}
		});
	}

	// ---------------------------------------------------------------------------------------- helpers

	static BankHistoryDayRow.Model row(final String date, final String sub, final String total, final String exact,
		final String pct, final String gp, final int sign)
	{
		return new BankHistoryDayRow.Model(LocalDate.of(2026, 9, 30), false, date, sub, total, exact, pct, gp, sign,
			false);
	}

	static List<JLabel> labels(final Container c)
	{
		final List<JLabel> out = new ArrayList<>();
		for (Component child : c.getComponents())
		{
			if (child instanceof JLabel)
			{
				out.add((JLabel) child);
			}
		}
		return out;
	}

	/**
	 * Every label is at least as wide as its own text in its own font plus its icon (nothing cut), inside its
	 * parent's insets, and no two of them overlap.
	 */
	static void assertFits(final String what, final Container parent, final List<JLabel> labels)
	{
		final Insets in = parent.getInsets();
		for (JLabel l : labels)
		{
			if (l.getText().isEmpty())
			{
				continue;
			}
			final FontMetrics fm = l.getFontMetrics(l.getFont());
			final int need = fm.stringWidth(l.getText())
				+ (l.getIcon() == null ? 0 : l.getIcon().getIconWidth() + l.getIconTextGap());
			assertTrue(what + ": '" + l.getText() + "' needs " + need + " px, has " + l.getWidth(), l.getWidth() >= need);
			assertTrue(what + ": '" + l.getText() + "' starts inside at " + l.getX(), l.getX() >= in.left);
			assertTrue(what + ": '" + l.getText() + "' ends inside at " + (l.getX() + l.getWidth()),
				l.getX() + l.getWidth() <= parent.getWidth() - in.right);
			assertTrue(what + ": '" + l.getText() + "' is tall enough", l.getHeight() >= fm.getHeight());
		}
		for (int i = 0; i < labels.size(); i++)
		{
			for (int j = i + 1; j < labels.size(); j++)
			{
				final JLabel a = labels.get(i);
				final JLabel b = labels.get(j);
				if (a.getText().isEmpty() || b.getText().isEmpty())
				{
					continue;
				}
				assertTrue(what + ": '" + a.getText() + "' " + a.getBounds() + " and '" + b.getText() + "' "
					+ b.getBounds() + " overlap", !a.getBounds().intersects(b.getBounds()));
			}
		}
	}
}
