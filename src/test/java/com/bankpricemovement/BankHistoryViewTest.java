package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JComponent;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static com.bankpricemovement.HistoryViewRenderer.TODAY;
import static com.bankpricemovement.HistoryViewRenderer.ZONE;
import static com.bankpricemovement.HistoryViewRenderer.chart;
import static com.bankpricemovement.HistoryViewRenderer.fixture;
import static com.bankpricemovement.HistoryViewRenderer.layOut;
import static com.bankpricemovement.HistoryViewRenderer.noon;
import static com.bankpricemovement.HistoryViewRenderer.point;
import static com.bankpricemovement.HistoryViewRenderer.view;
import static com.bankpricemovement.MovementRowPanelTest.onEdt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, builder C: what the History view draws and when it rebuilds (plan 7.2 items 9-11, 7.5 item 3;
 * contract section 6 with amendments 9.10-9.14). The chart's own pixels and hover are in
 * {@link BankHistoryChartTest}; the column fit at 213 px in {@link BankHistoryViewFitTest}.
 */
public class BankHistoryViewTest
{
	// ---------------------------------------------------------------------------------------- the no-op rule

	@Test
	public void anEqualShowIsANoOpThatKeepsEveryComponent() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final List<Component> before = tree(view);
			final List<BankHistoryMath.Day> days = chart(view).days();

			// An equal series (a new instance), and options that differ ONLY in the hover switch, the same today.
			view.show(fixture(), ViewOptions.DEFAULT.withShowHoverText(true));

			final List<Component> after = tree(view);
			assertEquals(before.size(), after.size());
			for (int i = 0; i < before.size(); i++)
			{
				assertSame("component " + i + " is the same object", before.get(i), after.get(i));
			}
			assertSame("the chart was not even re-fed", days, chart(view).days());
		});
	}

	@Test
	public void aNewDayIsNotANoOpEvenForAnEqualSeries() throws Exception
	{
		onEdt(() ->
		{
			final AtomicLong clock = new AtomicLong(noon(TODAY));
			final BankHistoryView view = new BankHistoryView(clock::get, ZONE);
			view.show(fixture(), ViewOptions.DEFAULT);
			assertEquals(40, view.describe().get("rows"));

			clock.set(noon(TODAY.plusDays(1)));
			view.show(fixture(), ViewOptions.DEFAULT);
			assertEquals("tomorrow is a carried row of its own", 41, view.describe().get("rows"));
			assertEquals(5, view.describe().get("carriedRows"));
		});
	}

	// ---------------------------------------------------------------------------------------- the switches

	@Test
	public void aFlippedSwitchRedrawsEveryFigureThroughValueFor() throws Exception
	{
		onEdt(() ->
		{
			// Two days, each with a bank and coins: the coins count only while "Include coins" is on.
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(
				cells(TODAY.minusDays(1), 700_000_000L, 20_000_000L), cells(TODAY, 710_000_000L, 30_000_000L)));
			final BankHistoryView view = view(s, BankHistoryRange.D7);
			layOut(view, 213);
			final Component todayRowBefore = rows(view).get(0);
			assertNotNull(label(view, "740,000,000"));
			assertNotNull("the readout names the latest reading's total", label(view, "740,000,000 gp"));
			assertNotNull("740m against 720m", label(view, "+20m"));
			assertNotNull(label(view, "+2.7%"));

			final ViewOptions noCash = ViewOptions.DEFAULT.withCountCash(false);
			assertEquals(710_000_000L, s.last().valueFor(noCash));
			view.show(s, noCash);
			layOut(view, 213);

			assertNull("the old figure is gone", label(view, "740,000,000"));
			assertNotNull(label(view, "710,000,000"));
			assertNotNull(label(view, "710,000,000 gp"));
			assertNull(label(view, "+20m"));
			assertNotNull("the change follows the switch too: 710m against 700m", label(view, "+10m"));
			assertNotNull(label(view, "+1.4%"));
			assertNotSame("today's row was rebuilt", todayRowBefore, rows(view).get(0));
			assertEquals("the count is unchanged", 2, view.describe().get("rows"));
		});
	}

	@Test
	public void eachOfTheFourTotalSwitchesRedrawsAndOnlyThey() throws Exception
	{
		onEdt(() ->
		{
			// Eight days of readings, TODAY-7 to TODAY, every cell moving at its own pace and a guide figure apart from
			// the card's: each of the four switches changes today's total, every row's, the readout and the 7d change
			// (whose start has a reading, so overDays answers for the range itself - no fallback).
			final List<BankHistoryPoint> points = new ArrayList<>();
			for (int i = 0; i <= 7; i++)
			{
				points.add(allCells(TODAY.minusDays(7 - i), i));
			}
			final BankHistorySeries s = BankHistorySeries.of(points);
			final List<ViewOptions> steps = Arrays.asList(ViewOptions.DEFAULT,
				ViewOptions.DEFAULT.withCountCash(false),
				ViewOptions.DEFAULT.withCountCash(false).withCountUntradeables(true),
				ViewOptions.DEFAULT.withCountCash(false).withCountUntradeables(true).withCountInventory(false),
				ViewOptions.DEFAULT.withCountCash(false).withCountUntradeables(true).withCountInventory(false)
					.withLivePrices(false));
			final BankHistoryView view = new BankHistoryView(() -> noon(TODAY), ZONE);
			BankHistoryDayRow todayRow = null;
			long lastToday = -1L;
			for (ViewOptions o : steps)
			{
				view.show(s, o);
				layOut(view, 213);
				final long now = s.last().valueFor(o);
				assertTrue(o + ": the switch changes today's total", now != lastToday);
				lastToday = now;
				assertNotSame(o + ": today's row was rebuilt", todayRow, rows(view).get(0));
				todayRow = rows(view).get(0);
				for (BankHistoryDayRow row : rows(view))
				{
					assertEquals(o + ": " + row.model().day() + "'s exact total is valueFor",
						MovementMath.formatExact(s.on(row.model().day()).valueFor(o)), text(row, 3));
				}
				assertEquals(o + ": the readout", MovementMath.formatExact(now) + " gp", chartLabel(view, READOUT_TOTAL));
				final BankHistoryMath.Change c = BankHistoryMath.overDays(s, TODAY, 7, o);
				assertEquals(TODAY.minusDays(7), c.fromDay());
				assertEquals(o + ": the change line's days", "19 Sep - 26 Sep", chartLabel(view, CHANGE_DAYS));
				assertEquals(o + ": the change line's gp", MovementRowPanel.signedGp(c.deltaGp()),
					chartLabel(view, CHANGE_GP));
				assertEquals(o + ": the change line's %", MovementMath.formatPctCompact(c.pct(), c.deltaGp()),
					chartLabel(view, CHANGE_PCT));
			}
			// Guide and card differ, so the last step really printed the guide sums.
			assertTrue(s.last().valueFor(steps.get(4)) != s.last().valueFor(steps.get(4).withLivePrices(true)));

			// The fifth switch, the hover text, changes no total: a no-op.
			view.show(s, steps.get(4).withShowHoverText(true));
			assertSame(todayRow, rows(view).get(0));
		});
	}

	/** A reading with all eight cells set, moving with {@code i}, and a guide figure apart from the card's. */
	static BankHistoryPoint allCells(final LocalDate day, final int i)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = 700_000_000L + i * 1_000_000L;
		card[BankHistoryPoint.BANK_CASH] = 20_000_000L + i * 3_000_000L;
		card[BankHistoryPoint.BANK_PARTS] = 4_000_000L;
		card[BankHistoryPoint.BANK_ALCH] = 5_000_000L + i * 2_000_000L;
		card[BankHistoryPoint.CARRIED_TRADEABLE] = 9_000_000L + i * 4_000_000L;
		card[BankHistoryPoint.CARRIED_CASH] = 1_000_000L + i * 500_000L;
		card[BankHistoryPoint.CARRIED_PARTS] = 2_000_000L;
		card[BankHistoryPoint.CARRIED_ALCH] = 3_000_000L + i * 700_000L;
		final long[] guide = card.clone();
		guide[BankHistoryPoint.BANK_TRADEABLE] = 650_000_000L + i * 5_000_000L;
		guide[BankHistoryPoint.CARRIED_TRADEABLE] = 8_000_000L + i * 1_000_000L;
		return new BankHistoryPoint(day, noon(day), noon(day), card, guide);
	}

	// ---------------------------------------------------------------------------------------- the cost rule

	@Test
	public void aNewTotalForTodayRebuildsTodaysRowAlone() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			final List<BankHistoryDayRow> before = rows(view);

			view.show(fixture().with(point(TODAY, 737_000_000L)), ViewOptions.DEFAULT);

			final List<BankHistoryDayRow> after = rows(view);
			assertEquals(before.size(), after.size());
			assertNotSame("today's row is new", before.get(0), after.get(0));
			for (int i = 1; i < before.size(); i++)
			{
				assertSame("row " + i + " is kept", before.get(i), after.get(i));
			}
			assertEquals("737m", ((JLabel) after.get(0).getComponent(2)).getText());
		});
	}

	@Test
	public void aNewDaysReadingAddsItsRowAndKeepsTheOthers() throws Exception
	{
		onEdt(() ->
		{
			final AtomicLong clock = new AtomicLong(noon(TODAY));
			final BankHistoryView view = new BankHistoryView(clock::get, ZONE);
			view.show(fixture(), ViewOptions.DEFAULT);
			final List<BankHistoryDayRow> before = rows(view);

			clock.set(noon(TODAY.plusDays(1)));
			view.show(fixture().with(point(TODAY.plusDays(1), 740_000_000L)), ViewOptions.DEFAULT);

			final List<BankHistoryDayRow> after = rows(view);
			assertEquals(before.size() + 1, after.size());
			for (int i = 0; i < before.size(); i++)
			{
				assertSame("yesterday's rows are all kept, one place down", before.get(i), after.get(i + 1));
			}
		});
	}

	@Test
	public void aFullPageShiftedByADayEitherWayKeepsItsRowsInOrder() throws Exception
	{
		onEdt(() ->
		{
			// A reading every day for 400 days: a full page of 250 rows, then the clock moves a day on (every kept row
			// one place down, a new one on top) and a day back (every kept row one place up, an older one at the foot).
			final List<BankHistoryPoint> points = new ArrayList<>();
			for (int i = 0; i < 400; i++)
			{
				points.add(point(TODAY.minusDays(i), 1_000_000L + i * 1_000L));
			}
			final BankHistorySeries s = BankHistorySeries.of(points);
			final AtomicLong clock = new AtomicLong(noon(TODAY));
			final BankHistoryView view = new BankHistoryView(clock::get, ZONE);
			view.show(s, ViewOptions.DEFAULT);
			assertListInOrder(view, TODAY, 250);

			clock.set(noon(TODAY.plusDays(1)));
			final BankHistorySeries tomorrow = s.with(point(TODAY.plusDays(1), 2_000_000L));
			view.show(tomorrow, ViewOptions.DEFAULT);
			assertListInOrder(view, TODAY.plusDays(1), 250);

			clock.set(noon(TODAY));
			view.show(s, ViewOptions.DEFAULT);
			assertListInOrder(view, TODAY, 250);

			// And the same after a second page.
			assertTrue(view.showMore());
			clock.set(noon(TODAY.plusDays(1)));
			view.show(tomorrow, ViewOptions.DEFAULT);
			assertListInOrder(view, TODAY.plusDays(1), 401);
			clock.set(noon(TODAY));
			view.show(s, ViewOptions.DEFAULT);
			assertListInOrder(view, TODAY, 400);
		});
	}

	@Test
	public void theViewRevalidatesItselfAfterARebuildAndAPageAndItsHeightFollows() throws Exception
	{
		onEdt(() ->
		{
			// Headless there is no peer to validate against, so the test listens where revalidate() reports: the
			// RepaintManager's invalid components.
			final List<Component> asked = new ArrayList<>();
			final javax.swing.RepaintManager was = javax.swing.RepaintManager.currentManager((JComponent) null);
			javax.swing.RepaintManager.setCurrentManager(new javax.swing.RepaintManager()
			{
				@Override
				public void addInvalidComponent(final JComponent c)
				{
					asked.add(c);
				}
			});
			try
			{
				final BankHistorySeries two = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(400), 1L),
					point(TODAY, 2L)));
				final BankHistoryView view = new BankHistoryView(() -> noon(TODAY), ZONE);
				new javax.swing.JPanel().add(view);
				view.show(two, ViewOptions.DEFAULT);
				assertTrue("a show that draws asks for a layout", asked.contains(view));
				layOut(view, 213);
				final int onePage = view.getHeight();
				assertEquals("laid out at its content height", view.getPreferredSize().height, onePage);

				asked.clear();
				view.show(two, ViewOptions.DEFAULT);
				assertFalse("a no-op asks for nothing", asked.contains(view));

				assertTrue(view.showMore());
				assertTrue("a page asks for a layout", asked.contains(view));
				layOut(view, 213);
				assertTrue("151 more rows, and the pager row gone", view.getHeight() > onePage);

				asked.clear();
				view.show(BankHistorySeries.of(Arrays.asList(point(TODAY, 2L))), ViewOptions.DEFAULT);
				assertTrue("a rebuild asks for one too", asked.contains(view));
				layOut(view, 213);
				assertTrue(view.getHeight() < onePage);
			}
			finally
			{
				javax.swing.RepaintManager.setCurrentManager(was);
			}
		});
	}

	/** The list's children are the header, {@code n} rows from {@code newest} back one day at a time, and the pager. */
	private static void assertListInOrder(final BankHistoryView view, final LocalDate newest, final int n)
	{
		final List<BankHistoryDayRow> rows = rows(view);
		assertEquals(n, rows.size());
		assertEquals(n, view.describe().get("rows"));
		for (int i = 0; i < n; i++)
		{
			assertEquals("row " + i, newest.minusDays(i), rows.get(i).model().day());
		}
		// 401 days from the first reading to tomorrow, 400 to today: the pager stands for the rest.
		final int left = (newest.isAfter(TODAY) ? 401 : 400) - n;
		final Container list = rows.get(0).getParent();
		assertEquals("header, rows, and the pager only while more are left", 1 + n + (left > 0 ? 1 : 0),
			list.getComponentCount());
		if (left > 0)
		{
			assertSame(list, label(view, "Show " + left + " more").getParent().getParent());
		}
	}

	// ---------------------------------------------------------------------------------------- the list

	@Test
	public void everyDayFromTheFirstReadingIsARowNewestFirstCarriedDaysThin() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			final LinkedHashMap<String, Object> d = view.describe();
			assertEquals("the list ignores the chart's range: all 40 days", 40, d.get("rows"));
			assertEquals("30 Aug, 8 Sep, 21 and 22 Sep", 4, d.get("carriedRows"));
			assertEquals(36, d.get("readings"));

			final List<BankHistoryDayRow> rows = rows(view);
			assertEquals("newest first", TODAY, rows.get(0).model().day());
			assertEquals(TODAY.minusDays(39), rows.get(39).model().day());

			// 22 Sep and 21 Sep had no reading: grey and thin, the date alone.
			final BankHistoryDayRow sep22 = rows.get(4);
			assertTrue(sep22.model().carried());
			assertEquals(BankHistoryDayRow.CARRIED_HEIGHT, sep22.getPreferredSize().height);
			assertEquals(ColorScheme.DARK_GRAY_HOVER_COLOR, sep22.getBackground());
			final JLabel words = (JLabel) sep22.getComponent(0);
			assertEquals("Tue 22 Sep", words.getText());
			assertEquals(Widgets.PLACEHOLDER_COLOR, words.getForeground());
			assertEquals("a reading's row is 36 px", BankHistoryDayRow.HEIGHT, rows.get(0).getPreferredSize().height);
			assertEquals(36, BankHistoryDayRow.HEIGHT);
		});
	}

	/**
	 * The user on the first live look (2026-09-29): "leave format as is except remove the text '-carried, 754m' on
	 * days we dont login". A day with no reading reads its date and nothing else - no words, no total, compact or
	 * exact - while the chart still carries the total forward.
	 */
	@Test
	public void aDayWithNoLoginReadsItsDateAloneAndNoTotal() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			final long sep20 = fixture().on(LocalDate.of(2026, 9, 20)).valueFor(ViewOptions.DEFAULT);
			final BankHistoryDayRow sep22 = rows(view).get(4);
			assertEquals(LocalDate.of(2026, 9, 22), sep22.model().day());
			assertTrue(sep22.model().carried());
			assertEquals("one label: the date", 1, sep22.getComponentCount());
			final String text = ((JLabel) sep22.getComponent(0)).getText();
			assertEquals("Tue 22 Sep", text);
			assertFalse(text, text.contains("carried"));
			assertFalse(text, text.contains(MovementMath.formatGp(sep20)));
			assertFalse(text, text.contains(MovementMath.formatExact(sep20)));
			assertFalse(text, text.contains(","));
		});
	}

	@Test
	public void aRowComparesWithThePreviousReadingAndNamesItsDay() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			layOut(view, 213);
			final BankHistoryDayRow sep23 = rowOf(view, LocalDate.of(2026, 9, 23));
			assertEquals("Wed 23 Sep", text(sep23, 0));
			assertEquals("the reading before is 20 Sep, three days back", "vs 20 Sep, 3 days", text(sep23, 1));
			final BankHistoryDayRow sep24 = rowOf(view, LocalDate.of(2026, 9, 24));
			assertEquals("vs 23 Sep", text(sep24, 1));

			// The figures: compact as the card prints it, exact, % compact and signed by the gp, gp as the row face.
			final BankHistorySeries s = fixture();
			final long v = s.on(LocalDate.of(2026, 9, 23)).valueFor(null);
			final long prev = s.on(LocalDate.of(2026, 9, 20)).valueFor(null);
			assertEquals(MovementMath.formatGp(v), text(sep23, 2));
			assertEquals(MovementMath.formatExact(v), text(sep23, 3));
			assertEquals(MovementMath.formatPctCompact((v - prev) * 100.0d / prev, v - prev), text(sep23, 4));
			assertEquals(MovementRowPanel.signedGp(v - prev), text(sep23, 5));
			final int sign = Long.signum(v - prev);
			assertEquals(Widgets.move(sign, Widgets.Kind.FIGURE), ((JLabel) sep23.getComponent(4)).getForeground());
			assertEquals(Widgets.move(sign, Widgets.Kind.QUIET), ((JLabel) sep23.getComponent(5)).getForeground());
			assertEquals("no clock on a row: six labels and nothing else", 6, sep23.getComponentCount());
			for (Component c : sep23.getComponents())
			{
				assertFalse(((JLabel) c).getText().contains(":"));
			}
		});
	}

	@Test
	public void theFirstReadingEverSaysSoWithAGreyEdge() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			final List<BankHistoryDayRow> rows = rows(view);
			final BankHistoryDayRow first = rows.get(rows.size() - 1);
			assertEquals("Tue 18 Aug", text(first, 0));
			assertEquals("first reading", text(first, 1));
			assertEquals(MovementMath.DASH, text(first, 4));
			assertEquals("", text(first, 5));
			assertEquals(BankHistoryDayRow.FLAT_EDGE, edge(first));
			// A rise is green, a fall red, on the edge.
			assertEquals(Widgets.move(1, Widgets.Kind.EDGE), edge(rowOf(view, TODAY)));
			assertEquals(Widgets.move(-1, Widgets.Kind.EDGE), edge(rowOf(view, LocalDate.of(2026, 9, 23))));
		});
	}

	@Test
	public void aReadingThatDidNotMoveHasAGreyEdgeAndNoGpFigure() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(1), 5_000_000L),
				point(TODAY, 5_000_000L)));
			final BankHistoryDayRow today = rows(view(s, BankHistoryRange.D7)).get(0);
			assertEquals("0.0%", text(today, 4));
			assertEquals("", text(today, 5));
			assertEquals(BankHistoryDayRow.FLAT_EDGE, edge(today));
		});
	}

	@Test
	public void aDateMoreThan300DaysOldPrintsItsYearAndPagesHold250Rows() throws Exception
	{
		onEdt(() ->
		{
			// Two readings 400 days apart: 401 rows, all but two carried.
			final LocalDate old = TODAY.minusDays(400);
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(point(old, 100_000_000L),
				point(TODAY, 120_000_000L)));
			final BankHistoryView view = view(s, BankHistoryRange.ALL);
			assertEquals(250, view.describe().get("rows"));
			assertEquals(249, view.describe().get("carriedRows"));
			assertNotNull("the pager row, in the Items list's words", label(view, "Show 151 more"));

			assertTrue(view.showMore());
			assertEquals(401, view.describe().get("rows"));
			assertNull(label(view, "Show 151 more"));
			assertFalse("nothing more to show", view.showMore());
			assertFalse(view.showMore());

			final List<BankHistoryDayRow> rows = rows(view);
			// 300 days back: the weekday form. 301 days back: the year, and the weekday gives way to it.
			final LocalDate d300 = TODAY.minusDays(300);
			final LocalDate d301 = TODAY.minusDays(301);
			assertEquals(BankHistoryDayRow.weekday(d300), text(rows.get(300), 0));
			assertEquals("30 Nov 2025", MovementMath.formatDay(d300) + " " + d300.getYear());
			assertEquals(MovementMath.formatDay(d301) + " 2025", text(rows.get(301), 0));
			final BankHistoryDayRow first = rows.get(400);
			assertEquals("22 Aug 2025", text(first, 0));
			assertEquals("first reading", text(first, 1));
			// Today, 400 days after it: the sub-line names the day with no year (the year rule is the row's date).
			assertEquals("vs 22 Aug, 400 days", text(rows.get(0), 1));
		});
	}

	@Test
	public void aNewBanksHistoryGoesBackToOnePage() throws Exception
	{
		onEdt(() ->
		{
			final BankHistorySeries a = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(400), 1L),
				point(TODAY, 2L)));
			final BankHistoryView view = view(a, BankHistoryRange.ALL);
			assertTrue(view.showMore());
			assertEquals(401, view.describe().get("rows"));

			// Today's total moves: still that bank - the pages stay open.
			view.show(a.with(point(TODAY, 3L)), ViewOptions.DEFAULT);
			assertEquals(401, view.describe().get("rows"));

			// Another bank's history, first read on another day: one page again.
			view.show(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(399), 1L), point(TODAY, 2L))),
				ViewOptions.DEFAULT);
			assertEquals(250, view.describe().get("rows"));
		});
	}

	// ---------------------------------------------------------------------------------------- 0 and 1 readings

	@Test
	public void noReadingAtAllIsTheOneSentenceAndNothingElse() throws Exception
	{
		onEdt(() ->
		{
			for (BankHistorySeries s : Arrays.asList(null, BankHistorySeries.EMPTY,
				BankHistorySeries.of(Arrays.asList(point(TODAY.plusDays(2), 1L)))))
			{
				final BankHistoryView view = view(s, BankHistoryRange.D7);
				layOut(view, 213);
				assertEquals("one child: the sentence", 1, view.getComponentCount());
				assertTrue(view.getComponent(0) instanceof BankHistoryView.Sentence);
				final BankHistoryView.Sentence sentence = (BankHistoryView.Sentence) view.getComponent(0);
				final List<String> lines = sentence.lines(213);
				assertEquals("No readings yet - open your bank to load your first reading.", String.join(" ", lines));
				assertEquals(BankHistoryView.NO_READINGS, String.join(" ", lines));
				assertTrue("wrapped to the width", lines.size() >= 2);
				assertNull("no chart", chart(view));
				assertEquals(0, view.describe().get("rows"));
				assertEquals(0, view.describe().get("readings"));
				assertEquals("its height is its lines", sentence.getPreferredSize().height, view.getHeight());
			}
		});
	}

	@Test
	public void oneReadingIsTheOnePointItsReadoutItsRowAndNoSentence() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(point(TODAY, 736_412_683L))),
				BankHistoryRange.D7);
			layOut(view, 213);
			assertEquals(2, view.getComponentCount());
			assertFalse(view.getComponent(0) instanceof BankHistoryView.Sentence);
			assertNotNull(label(view, "Sat 26 Sep"));
			assertNotNull(label(view, "736,412,683 gp"));
			assertEquals(1, view.describe().get("rows"));
			assertEquals("first reading", text(rows(view).get(0), 1));
			assertEquals(1, chart(view).days().size());
			for (Component c : tree(view))
			{
				if (c instanceof BankHistoryView.Sentence)
				{
					throw new AssertionError("no sentence with one reading");
				}
			}
		});
	}

	// ---------------------------------------------------------------------------------------- the range

	@Test
	public void theViewsOwnChipsMoveTheChartAndSetRangeOverrulesThem() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D7);
			layOut(view, 213);
			assertEquals("7d", view.describe().get("range"));
			assertEquals("today and the seven days before it", 8, chart(view).days().size());
			final int rows = (Integer) view.describe().get("rows");

			press(label(view, "30d"));
			assertEquals("30d", view.describe().get("range"));
			assertEquals(31, chart(view).days().size());
			assertEquals("the list does not move with the chart", rows, view.describe().get("rows"));
			assertTrue(lit(label(view, "30d")));
			assertFalse(lit(label(view, "7d")));

			// The same range again from the card: a no-op, the chart is not even re-fed.
			final List<BankHistoryMath.Day> drawn = chart(view).days();
			view.setRange(BankHistoryRange.D30);
			assertSame(drawn, chart(view).days());

			// The card's chip overrules the view's own.
			view.setRange(BankHistoryRange.D7);
			assertEquals("7d", view.describe().get("range"));
			assertEquals(8, chart(view).days().size());
		});
	}

	@Test
	public void aRangeReachingPastTheFirstReadingIsDimmedButClickable() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final JLabel ninety = label(view, "90d");
			assertEquals("40 days of readings: nothing 90 days old", ColorScheme.MEDIUM_GRAY_COLOR, ninety.getForeground());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, label(view, "7d").getForeground());
			assertEquals("'all' is never dimmed", ColorScheme.LIGHT_GRAY_COLOR, label(view, "all").getForeground());

			// The pointer passes over it: it lights orange, and goes back to its dim, not to the plain grey.
			hover(ninety, MouseEvent.MOUSE_ENTERED);
			assertEquals(ColorScheme.BRAND_ORANGE, ninety.getForeground());
			hover(ninety, MouseEvent.MOUSE_EXITED);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, ninety.getForeground());

			press(ninety);
			assertEquals("90d", view.describe().get("range"));
			assertEquals("the chart draws what there is: all 40 days", 40, chart(view).days().size());
			assertNotNull("the change line names the days it can show", label(view, "18 Aug - 26 Sep"));
			assertTrue("lit, it is filled orange", lit(ninety));
		});
	}

	@Test
	public void theChangeLineIsTheRangesChangeGreenForARise() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(fixture(), BankHistoryRange.D30);
			layOut(view, 213);
			final BankHistoryMath.Change c = BankHistoryMath.overDays(fixture(), TODAY, 30, ViewOptions.DEFAULT);
			assertEquals(LocalDate.of(2026, 8, 27), c.fromDay());
			assertTrue(c.deltaGp() > 0);
			assertNotNull(label(view, "27 Aug - 26 Sep"));
			final JLabel pct = label(view, MovementMath.formatPctCompact(c.pct(), c.deltaGp()));
			assertNotNull(pct);
			assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, pct.getForeground());
			final JLabel gp = label(view, MovementRowPanel.signedGp(c.deltaGp()));
			assertNotNull(gp);
			assertEquals(Widgets.move(1, Widgets.Kind.QUIET), gp.getForeground());

			// "all": from the first reading (amendment 9.5); a fall is the rows' red.
			final BankHistorySeries falling = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(3), 900L),
				point(TODAY, 800L)));
			final BankHistoryView down = view(falling, BankHistoryRange.ALL);
			layOut(down, 213);
			assertNotNull(label(down, "23 Sep - 26 Sep"));
			assertEquals(Widgets.MOVE_DOWN_TEXT, label(down, "-11.1%").getForeground());
		});
	}

	@Test
	public void aRangeWithAReadingAtItsStartButNoneInsideItShowsTheDash() throws Exception
	{
		onEdt(() ->
		{
			// Readings on 01 Sep (100m) and 10 Sep (120m), today 26 Sep (review C-FIG-1). At 7d the range's start has a
			// reading (10 Sep carried to 19 Sep), so the chip is NOT dimmed - but no reading lies inside it: the chart
			// is flat and carried, and the change line must not print the since-first-reading +20m over it.
			final BankHistorySeries s = BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(25), 100_000_000L),
				point(TODAY.minusDays(16), 120_000_000L)));
			assertNull(BankHistoryMath.overDays(s, TODAY, 7, null));
			assertNotNull("7d has a reading at its start: not a dimmed range", s.atOrBefore(TODAY.minusDays(7)));
			final BankHistoryView view = view(s, BankHistoryRange.D7);
			layOut(view, 213);
			assertEquals("30d is dimmed: nothing on or before 27 Aug", ColorScheme.MEDIUM_GRAY_COLOR,
				label(view, "30d").getForeground());
			assertEquals("the last reading's day", "10 Sep", chartLabel(view, CHANGE_DAYS));
			assertEquals("", chartLabel(view, CHANGE_GP));
			assertEquals(MovementMath.DASH, chartLabel(view, CHANGE_PCT));
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, chartBlock(view).getComponent(CHANGE_PCT).getForeground());
			// (The list's 10 Sep row does print "+20m": that is its change against 01 Sep, which is right.)

			// A DIMMED range (30d: nothing on or before 27 Aug) still shows what the chart can: since the first reading.
			view.setRange(BankHistoryRange.D30);
			assertEquals("01 Sep - 10 Sep", chartLabel(view, CHANGE_DAYS));
			assertEquals("+20m", chartLabel(view, CHANGE_GP));
		});
	}

	@Test
	public void aChipIsDimmedOnlyWhenNoReadingIsAtOrBeforeItsStart() throws Exception
	{
		onEdt(() ->
		{
			// First read exactly 30 days ago: 30d has its start, 90d has not.
			final BankHistoryView exact = view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(30), 1L),
				point(TODAY, 2L))), BankHistoryRange.ALL);
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, label(exact, "30d").getForeground());
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, label(exact, "7d").getForeground());
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, label(exact, "90d").getForeground());

			// First read 29 days ago: 30d reaches past it.
			final BankHistoryView short1 = view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(29), 1L),
				point(TODAY, 2L))), BankHistoryRange.ALL);
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, label(short1, "30d").getForeground());

			// The same boundary for 7d: first read 7 days ago, then 6.
			assertEquals(ColorScheme.LIGHT_GRAY_COLOR, label(view(BankHistorySeries.of(Arrays.asList(
				point(TODAY.minusDays(7), 1L), point(TODAY, 2L))), BankHistoryRange.ALL), "7d").getForeground());
			assertEquals(ColorScheme.MEDIUM_GRAY_COLOR, label(view(BankHistorySeries.of(Arrays.asList(
				point(TODAY.minusDays(6), 1L), point(TODAY, 2L))), BankHistoryRange.ALL), "7d").getForeground());
		});
	}

	@Test
	public void oneReadingsChangeLineIsItsDayAndADash() throws Exception
	{
		onEdt(() ->
		{
			final BankHistoryView view = view(BankHistorySeries.of(Arrays.asList(point(TODAY, 736_412_683L))),
				BankHistoryRange.D7);
			layOut(view, 213);
			assertEquals("26 Sep", chartLabel(view, CHANGE_DAYS));
			assertEquals("", chartLabel(view, CHANGE_GP));
			assertEquals(MovementMath.DASH, chartLabel(view, CHANGE_PCT));
			assertEquals("Sat 26 Sep", chartLabel(view, READOUT_DAY));
			assertEquals("736,412,683 gp", chartLabel(view, READOUT_TOTAL));
		});
	}

	// ---------------------------------------------------------------------------------------- hygiene

	@Test
	public void noComponentInTheViewHasATooltipOrHtml() throws Exception
	{
		onEdt(() ->
		{
			final List<BankHistoryView> views = new ArrayList<>();
			views.add(view(fixture(), BankHistoryRange.D30));
			views.add(view(null, BankHistoryRange.D7));
			views.add(view(BankHistorySeries.of(Arrays.asList(point(TODAY.minusDays(400), 1L), point(TODAY, 2L))),
				BankHistoryRange.ALL));
			for (BankHistoryView view : views)
			{
				layOut(view, 213);
				HistoryViewRenderer.hover(chartOrNull(view), 50);
				for (Component c : tree(view))
				{
					if (c instanceof JComponent)
					{
						assertNull(c + " has no tooltip", ((JComponent) c).getToolTipText());
					}
					if (c instanceof JLabel)
					{
						assertFalse(((JLabel) c).getText().toLowerCase().contains("<html"));
					}
				}
			}
		});
	}

	@Test
	public void theFourSemanticsThePanelReliesOnStillHold() throws Exception
	{
		onEdt(() ->
		{
			final AtomicLong clock = new AtomicLong(noon(LocalDate.of(2026, 9, 20)));
			final BankHistoryView view = new BankHistoryView(clock::get, ZONE);
			assertEquals("7d", view.describe().get("range"));
			assertEquals(0, view.describe().get("readings"));
			assertNull(view.describe().get("first"));
			assertNull(view.describe().get("last"));
			view.setRange(BankHistoryRange.D90);
			assertEquals("before any show", "90d", view.describe().get("range"));

			view.show(fixture(), null);
			assertEquals("readings up to the 20th", fixture().upTo(LocalDate.of(2026, 9, 20)).size(),
				view.describe().get("readings"));
			assertEquals("2026-08-18", view.describe().get("first"));
			assertEquals("2026-09-20", view.describe().get("last"));
			assertEquals(Arrays.asList("readings", "first", "last", "range", "rows", "carriedRows", "hoverDay"),
				new ArrayList<>(view.describe().keySet()));
		});
	}

	// ---------------------------------------------------------------------------------------- helpers

	static BankHistoryPoint cells(final LocalDate day, final long bank, final long cash)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = bank;
		card[BankHistoryPoint.BANK_CASH] = cash;
		return new BankHistoryPoint(day, noon(day), noon(day), card, null);
	}

	/**
	 * The chart block's children, in the order it adds them, which is its rows' order top to bottom: these five labels
	 * (the change line, then the readout line), the chart, then the range bar.
	 */
	static final int CHANGE_DAYS = 0;
	static final int CHANGE_GP = 1;
	static final int CHANGE_PCT = 2;
	static final int READOUT_DAY = 3;
	static final int READOUT_TOTAL = 4;
	static final int CHART = 5;
	static final int BAR = 6;

	/** The chart block: the chart's parent. */
	static Container chartBlock(final Container view)
	{
		return chart(view).getParent();
	}

	/** The range bar under the chart. */
	static BankHistoryView.RangeBar bar(final Container view)
	{
		return (BankHistoryView.RangeBar) chartBlock(view).getComponent(BAR);
	}

	/** The range bar's cell for {@code range}: its four cells in the ranges' order, 7d 30d 90d all. */
	static JLabel rangeCell(final Container view, final BankHistoryRange range)
	{
		return (JLabel) bar(view).getComponent(range.ordinal());
	}

	/** Whether the range bar's cell is the lit one: filled orange, its word dark. */
	static boolean lit(final JLabel cell)
	{
		return ColorScheme.BRAND_ORANGE.equals(cell.getBackground())
			&& ColorScheme.DARKER_GRAY_COLOR.equals(cell.getForeground());
	}

	/** The text of the chart block's label at {@code index} - found by its place, not by searching for a text. */
	static String chartLabel(final Container view, final int index)
	{
		return ((JLabel) chartBlock(view).getComponent(index)).getText();
	}

	static List<Component> tree(final Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component child : c.getComponents())
		{
			out.add(child);
			if (child instanceof Container)
			{
				out.addAll(tree((Container) child));
			}
		}
		return out;
	}

	static List<BankHistoryDayRow> rows(final Container view)
	{
		final List<BankHistoryDayRow> out = new ArrayList<>();
		for (Component c : tree(view))
		{
			if (c instanceof BankHistoryDayRow)
			{
				out.add((BankHistoryDayRow) c);
			}
		}
		return out;
	}

	static BankHistoryDayRow rowOf(final Container view, final LocalDate day)
	{
		for (BankHistoryDayRow row : rows(view))
		{
			if (row.model().day().equals(day))
			{
				return row;
			}
		}
		throw new AssertionError("no row for " + day);
	}

	/** A row's label {@code i}: 0 date, 1 sub-line, 2 total, 3 exact, 4 %, 5 gp - or a carried row's words. */
	static String text(final BankHistoryDayRow row, final int i)
	{
		return ((JLabel) row.getComponent(i)).getText();
	}

	static java.awt.Color edge(final BankHistoryDayRow row)
	{
		final javax.swing.border.CompoundBorder b = (javax.swing.border.CompoundBorder) row.getBorder();
		return ((javax.swing.border.MatteBorder) b.getOutsideBorder()).getMatteColor();
	}

	static JLabel label(final Container c, final String text)
	{
		for (Component child : tree(c))
		{
			if (child instanceof JLabel && text.equals(((JLabel) child).getText()))
			{
				return (JLabel) child;
			}
		}
		return null;
	}

	static BankHistoryChart chartOrNull(final Container c)
	{
		final BankHistoryChart chart = chart(c);
		return chart == null ? new BankHistoryChart(i -> { }) : chart;
	}

	/** A left press, as a range bar cell listens for it. */
	static void press(final JLabel chip)
	{
		chip.dispatchEvent(new MouseEvent(chip, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
			InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false, MouseEvent.BUTTON1));
	}

	static void hover(final JLabel chip, final int id)
	{
		chip.dispatchEvent(new MouseEvent(chip, id, System.currentTimeMillis(), 0, 5, 5, 0, false));
	}
}
