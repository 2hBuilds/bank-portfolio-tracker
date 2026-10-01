package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The Net Worth History's content (addendum AU, contract section 6 with amendments 9.1, 9.2 and 9.8-9.14; plan 7.2
 * items 9-11 and 7.5 item 3): everything under the toggle's caption while the sidebar shows it - the chart block
 * and the list of days. Mock 5 is the look.
 *
 * <p><b>The chart block</b>, top to bottom (the user's choice of 2026-09-29, "plain line, design 2 but i want the
 * chips below like design 8"): the change line - the range's first and last reading's days on the left, the change in
 * gp and % on the right ({@link BankHistoryMath#overDays}); a thin rule; the readout line - a small ring, the weekday
 * and date of the hovered point or of the latest reading, the exact total; the chart ({@link BankHistoryChart}) of
 * {@code thin(days(...), 120)} over the range's calendar days; and under it the range chips "7d 30d 90d all" as ONE
 * segmented bar ({@link RangeBar}) in the Items | Net Worth History toggle's family (a range with no reading older
 * than its start is dimmed but still clickable). With no reading at all the block is replaced by
 * {@link #NO_READINGS}; with one reading it draws that one point, its readout and nothing else.
 *
 * <p><b>The list of days</b>, newest first: every calendar day from the first reading to today, whatever the chart's
 * range ({@link BankHistoryDayRow}), in pages of {@link BankPriceMovementPanel#ROWS_PER_PAGE} with a "Show n more" row
 * in the Items list's manner.
 *
 * <p><b>The range.</b> The view starts at {@code forWindow(MovementWindow.DEFAULT)} (D7). The card's chip sets it
 * through {@link #setRange}; the view's OWN chips move the chart the same way but are never saved nor sent anywhere,
 * and the next {@link #setRange} with a different range overrules them (amendment 9.10).
 *
 * <p><b>Cost</b> (plan 7.2 item 9). {@link #show} is a no-op when the series, the four total-changing switches and
 * today all equal what is drawn. Otherwise the chart is re-fed and the list re-used row by row: a row is rebuilt only
 * when the strings it prints differ, so a new total for today rebuilds today's row and nothing else. No label here
 * carries {@code <html>}, and no component here calls {@code setToolTipText} (amendment 9.13) - the chart paints its
 * own readout.
 *
 * <p><b>Width</b> (amendment 9.8): no outer side margin; every part lays itself out from its own width, so the view
 * is right at any width from 213 px to 230 px; its preferred height is its true content height, and it revalidates
 * itself after every rebuild. The constructor builds components only: no timer, no clock read (amendment 9.1).
 */
public final class BankHistoryView extends JPanel
{
	/** Shown in place of the chart block when the plugin has never seen a reading (plan 7.5 item 3). */
	static final String NO_READINGS = "No readings yet - open your bank to load your first reading.";

	/** At most this many points on the chart (plan 7.2 item 10). */
	static final int MAX_POINTS = 120;

	/** Between the chart block and the list. */
	static final int BLOCK_GAP = 6;
	/** Between two rows of the list. */
	static final int ROW_GAP = 2;

	/** The change line: the days in grey, the gp change quiet, the percentage bold (mock 5). */
	static final Font CHANGE_DAYS_FONT = Widgets.sans(11);
	static final Font CHANGE_GP_FONT = Widgets.sans(11);
	static final Font CHANGE_PCT_FONT = Widgets.sansBold(12);
	/** The readout line: the day beside its ring, the exact total bold on the right. */
	static final Font READOUT_DAY_FONT = Widgets.sans(11);
	static final Font READOUT_TOTAL_FONT = Widgets.sansBold(12);

	private final LongSupplier clockMillis;
	private final ZoneId zone;

	private BankHistoryRange range = BankHistoryRange.forWindow(MovementWindow.DEFAULT);

	// ---- what is drawn: null before the first show
	@Nullable
	private LocalDate drawnToday;
	/** The last show's series cut to its today; EMPTY before the first. */
	private BankHistorySeries shown = BankHistorySeries.EMPTY;
	private ViewOptions drawnOptions = ViewOptions.DEFAULT;

	// ---- the chart block
	private final ChartBlock block;
	private final RangeBar bar;
	private final JLabel changeDays;
	private final JLabel changeGp;
	private final JLabel changePct;
	private final Ring ring = new Ring();
	private final JLabel readoutDay;
	private final JLabel readoutTotal;
	private final BankHistoryChart chart;
	private final Sentence empty;

	// ---- the list
	private final JPanel list;
	private final JPanel listHeader;
	private final JPanel showMoreRow;
	private final JLabel showMoreLabel;
	/** Every day from the first reading to today, NEWEST first. */
	private List<BankHistoryMath.Day> listDays = Collections.emptyList();
	/** The rows in the list now, newest first. */
	private final List<BankHistoryDayRow> rows = new ArrayList<>();
	/** How many rows the pager allows: one page, plus a page per {@link #showMore}. */
	private int pageLimit = BankPriceMovementPanel.ROWS_PER_PAGE;

	/**
	 * @param clockMillis the panel's clock, read on every {@link #show} for "today" (never here)
	 * @param zone        the player's time zone, what turns that clock into a LOCAL day
	 */
	public BankHistoryView(final LongSupplier clockMillis, final ZoneId zone)
	{
		this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
		this.zone = Objects.requireNonNull(zone, "zone");
		setOpaque(false);
		setLayout(new Stack(BLOCK_GAP));

		bar = new RangeBar(this::pickRange);
		changeDays = Widgets.label("", CHANGE_DAYS_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		changeGp = Widgets.label("", CHANGE_GP_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		changePct = Widgets.label("", CHANGE_PCT_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		readoutDay = Widgets.label("", READOUT_DAY_FONT, ColorScheme.TEXT_COLOR);
		readoutDay.setIcon(ring);
		readoutDay.setIconTextGap(ChartBlock.RING_GAP);
		readoutTotal = Widgets.label("", READOUT_TOTAL_FONT, Color.WHITE);
		chart = new BankHistoryChart(i -> renderReadout());
		block = new ChartBlock();
		empty = new Sentence(NO_READINGS);

		list = new JPanel(new Stack(ROW_GAP));
		list.setOpaque(false);
		listHeader = BankHistoryDayRow.header();
		showMoreLabel = Widgets.linkLabel("", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR, Color.WHITE,
			this::showMore);
		showMoreLabel.setHorizontalAlignment(SwingConstants.CENTER);
		showMoreRow = new JPanel(new BorderLayout());
		showMoreRow.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		showMoreRow.add(showMoreLabel, BorderLayout.CENTER);
		showMoreRow.setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, BankPriceMovementPanel.SHOW_MORE_HEIGHT));
		renderChips();
	}

	/**
	 * Draws the series under these switches. EDT only. A null series reads as {@link BankHistorySeries#EMPTY} and
	 * null options as {@link ViewOptions#DEFAULT} (amendment 9.2); the series is cut to {@code upTo(today)} first,
	 * today being the clock's LOCAL date, read now. A no-op when the cut series, the five switches that change a
	 * total ({@code countCash}, {@code countUntradeables}, {@code countInventory}, {@code countGrandExchange},
	 * {@code livePrices} - not {@code showHoverText}) and today all equal what is drawn (amendment 9.10).
	 */
	public void show(@Nullable final BankHistorySeries series, @Nullable final ViewOptions options)
	{
		final BankHistorySeries all = series == null ? BankHistorySeries.EMPTY : series;
		final ViewOptions o = options == null ? ViewOptions.DEFAULT : options;
		final LocalDate today = BankHistoryMath.dayOf(clockMillis.getAsLong(), zone);
		final BankHistorySeries cut = all.upTo(today);
		if (today.equals(drawnToday) && cut.equals(shown) && sameTotals(o, drawnOptions))
		{
			return;
		}
		final BankHistoryPoint wasFirst = shown.first();
		final BankHistoryPoint nowFirst = cut.first();
		if (drawnToday == null || wasFirst == null || nowFirst == null || !wasFirst.day().equals(nowFirst.day()))
		{
			// Another bank's history, or the first one: back to one page.
			pageLimit = BankPriceMovementPanel.ROWS_PER_PAGE;
		}
		drawnToday = today;
		shown = cut;
		drawnOptions = o;
		arrange();
		renderChart();
		renderList();
		revalidate();
		repaint();
	}

	/** Whether two option sets give every total the same figure: the five switches {@code valueFor} reads. */
	private static boolean sameTotals(final ViewOptions a, final ViewOptions b)
	{
		return a.countCash() == b.countCash() && a.countUntradeables() == b.countUntradeables()
			&& a.countInventory() == b.countInventory() && a.countGrandExchange() == b.countGrandExchange()
			&& a.livePrices() == b.livePrices();
	}

	/**
	 * The chart's range, as the card's chip sets it ({@code BankHistoryRange.forWindow(window)}). Redraws the chart
	 * only; a no-op for the range already drawn, and safe before any {@link #show}. Null is ignored.
	 */
	public void setRange(@Nullable final BankHistoryRange next)
	{
		if (next == null || next == range)
		{
			return;
		}
		range = next;
		renderChips();
		if (drawnToday != null)
		{
			renderChart();
			block.revalidate();
			block.repaint();
		}
	}

	/** One of the view's own chips: moves the chart, and nothing else - unsaved, never sent anywhere. */
	private void pickRange(final BankHistoryRange r)
	{
		setRange(r);
	}

	/**
	 * The day list's pager: builds the next {@link BankPriceMovementPanel#ROWS_PER_PAGE} rows. False when there was
	 * nothing more to show.
	 */
	public boolean showMore()
	{
		if (drawnToday == null || rows.size() >= listDays.size())
		{
			return false;
		}
		pageLimit = rows.size() + BankPriceMovementPanel.ROWS_PER_PAGE;
		renderList();
		revalidate();
		repaint();
		return true;
	}

	/**
	 * The dev bridge's view of the History card (amendment 9.11), exactly these seven keys and only String,
	 * Integer and null values: {@code readings} (the last show's {@code upTo(today).size()}), {@code first} and
	 * {@code last} (ISO {@code yyyy-MM-dd}, or null), {@code range} ({@link BankHistoryRange#label()}),
	 * {@code rows} and {@code carriedRows} (day rows built so far, carried ones among them) and {@code hoverDay}
	 * (the hovered point's day, ISO, or null).
	 */
	public LinkedHashMap<String, Object> describe()
	{
		final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
		final BankHistoryPoint first = shown.first();
		final BankHistoryPoint last = shown.last();
		int carried = 0;
		for (BankHistoryDayRow row : rows)
		{
			if (row.model().carried())
			{
				carried++;
			}
		}
		final BankHistoryMath.Day hovered = chart.hoverDay();
		map.put("readings", shown.size());
		map.put("first", first == null ? null : first.day().toString());
		map.put("last", last == null ? null : last.day().toString());
		map.put("range", range.label());
		map.put("rows", rows.size());
		map.put("carriedRows", carried);
		map.put("hoverDay", hovered == null ? null : hovered.day().toString());
		return map;
	}

	// ------------------------------------------------------------------------------------------ drawing

	/** The chart block and the list - or, with no reading at all, the one sentence and nothing else. */
	private void arrange()
	{
		final List<Component> want = new ArrayList<>(2);
		if (shown.isEmpty())
		{
			want.add(empty);
		}
		else
		{
			want.add(block);
			want.add(list);
		}
		boolean same = getComponentCount() == want.size();
		for (int i = 0; same && i < want.size(); i++)
		{
			same = getComponent(i) == want.get(i);
		}
		if (!same)
		{
			removeAll();
			for (Component c : want)
			{
				add(c);
			}
		}
	}

	/** The range bar: the lit cell orange, a range with nothing older than its start dimmed. */
	private void renderChips()
	{
		final Set<BankHistoryRange> dim = EnumSet.noneOf(BankHistoryRange.class);
		for (BankHistoryRange r : BankHistoryRange.values())
		{
			if (r != range && reachesPastFirstReading(r))
			{
				dim.add(r);
			}
		}
		bar.light(range, dim);
	}

	/** Whether a range starts before the first reading: nothing on or before its start to compare with. */
	private boolean reachesPastFirstReading(final BankHistoryRange r)
	{
		return drawnToday != null && r.days() > 0 && shown.atOrBefore(drawnToday.minusDays(r.days())) == null;
	}

	/** Re-feeds the range bar, the change line, the chart and the readout from what is drawn. */
	private void renderChart()
	{
		renderChips();
		if (drawnToday == null || shown.isEmpty())
		{
			chart.setData(Collections.emptyList(), false, 0);
			renderReadout();
			return;
		}
		final LocalDate today = drawnToday;
		final LocalDate from = range.days() <= 0 ? null : today.minusDays(range.days());
		final List<BankHistoryMath.Day> days = BankHistoryMath.thin(
			BankHistoryMath.days(shown, from, today, drawnOptions), MAX_POINTS);

		// A range reaching back past the first reading has no reading at its start: the change is then the one the
		// chart can show, since the first reading - its left date says so. ONLY then: a range that has a reading at
		// its start but none inside it (its last reading is the one at its start) moved nothing that was measured,
		// and prints the dash like a series of one reading, over a chart that is flat and carried.
		BankHistoryMath.Change change = BankHistoryMath.overDays(shown, today, range.days(), drawnOptions);
		if (change == null && reachesPastFirstReading(range))
		{
			change = BankHistoryMath.overDays(shown, today, 0, drawnOptions);
		}
		final int sign = change == null ? 0 : Long.signum(change.deltaGp());
		if (change == null)
		{
			final BankHistoryPoint last = shown.last();
			changeDays.setText(last == null ? "" : MovementMath.formatDay(last.day()));
			changeGp.setText("");
			changePct.setText(MovementMath.DASH);
		}
		else
		{
			changeDays.setText(MovementMath.formatDay(change.fromDay()) + " - " + MovementMath.formatDay(change.toDay()));
			changeGp.setText(change.deltaGp() == 0L ? "" : MovementRowPanel.signedGp(change.deltaGp()));
			changePct.setText(MovementMath.formatPctCompact(change.pct(), change.deltaGp()));
		}
		changeGp.setForeground(Widgets.move(sign, Widgets.Kind.QUIET));
		changePct.setForeground(Widgets.move(sign, Widgets.Kind.FIGURE));

		chart.setData(days, shown.size() > 1, sign);
		renderReadout();
	}

	/** The readout line: the hovered point, or with no hover the latest reading - its ring, its day, its total. */
	private void renderReadout()
	{
		final List<BankHistoryMath.Day> days = chart.days();
		final int i = chart.readoutIndex();
		if (i < 0 || i >= days.size())
		{
			readoutDay.setText("");
			readoutTotal.setText("");
			ring.colour = null;
			readoutDay.repaint();
			return;
		}
		final BankHistoryMath.Day day = days.get(i);
		ring.colour = chart.lineColour();
		readoutDay.setText(BankHistoryDayRow.dayText(day.day(), drawnToday));
		readoutTotal.setText(MovementMath.formatExact(day.valueGp()) + " gp");
		readoutDay.repaint();
		block.doLayout();
	}

	/**
	 * The list of days, keeping every row whose strings did not change: a new total for today rebuilds today's row
	 * alone, and a flipped switch every row it changes.
	 */
	private void renderList()
	{
		if (drawnToday == null || shown.isEmpty())
		{
			listDays = Collections.emptyList();
		}
		else
		{
			final List<BankHistoryMath.Day> ascending = BankHistoryMath.days(shown, null, drawnToday, drawnOptions);
			final List<BankHistoryMath.Day> newest = new ArrayList<>(ascending);
			Collections.reverse(newest);
			listDays = newest;
		}
		final int want = Math.min(listDays.size(), Math.max(pageLimit, BankPriceMovementPanel.ROWS_PER_PAGE));
		final Map<LocalDate, BankHistoryDayRow> old = new HashMap<>();
		for (BankHistoryDayRow row : rows)
		{
			old.put(row.model().day(), row);
		}
		final List<BankHistoryDayRow> next = new ArrayList<>(want);
		for (int i = 0; i < want; i++)
		{
			final BankHistoryDayRow.Model model = BankHistoryDayRow.model(shown, listDays.get(i), drawnToday, drawnOptions);
			final BankHistoryDayRow kept = old.get(model.day());
			next.add(kept != null && kept.model().equals(model) ? kept : new BankHistoryDayRow(model));
		}

		// The list's children: the header, the rows, and the pager row when more are left.
		final boolean more = want < listDays.size();
		if (more)
		{
			showMoreLabel.setText(BankPriceMovementPanel.showMoreText(listDays.size() - want));
		}
		final int expected = 1 + next.size() + (more ? 1 : 0);
		boolean inPlace = list.getComponentCount() == expected && rows.size() == next.size()
			&& list.getComponent(0) == listHeader && (list.getComponent(expected - 1) == showMoreRow) == more;
		if (inPlace)
		{
			// A swap in place is right only while no KEPT row changes its place: a kept row moved (a new day at a
			// full page shifts every row by one, a clock set back shifts them the other way) is re-added in order.
			final Set<BankHistoryDayRow> before = Collections.newSetFromMap(new IdentityHashMap<>());
			before.addAll(rows);
			for (int i = 0; inPlace && i < next.size(); i++)
			{
				inPlace = rows.get(i) == next.get(i) || !before.contains(next.get(i));
			}
		}
		if (inPlace)
		{
			// Same shape, every kept row where it was: swap only the rows that changed.
			for (int i = 0; i < next.size(); i++)
			{
				if (rows.get(i) != next.get(i))
				{
					list.remove(1 + i);
					list.add(next.get(i), 1 + i);
				}
			}
		}
		else
		{
			list.removeAll();
			list.add(listHeader);
			for (BankHistoryDayRow row : next)
			{
				list.add(row);
			}
			if (more)
			{
				list.add(showMoreRow);
			}
		}
		rows.clear();
		rows.addAll(next);
		list.revalidate();
	}

	// ------------------------------------------------------------------------------------------ parts

	/**
	 * The chart block's card, top to bottom: change line, rule, readout line, chart, range bar - laid out from its own
	 * width, so it is right at 213 px and at 230. Block-local: the change line's baseline at y 17, the rule at 23, the
	 * readout's baseline at 39, the chart over 45..154 and the block's whole width, the bar over 159..176 with 6 px of
	 * card either side, and 6 px of card under it - 183 px in all.
	 */
	private final class ChartBlock extends JPanel
	{
		static final int PAD_X = 12;
		/** Baselines of the change line and the readout line, from the block's top. */
		static final int CHANGE_BASELINE = 17;
		static final int RULE_Y = 23;
		static final int READOUT_BASELINE = 39;
		/** The chart's top, 6 px under the readout's baseline. */
		static final int CHART_TOP = 45;
		/** The range bar's top, 4 px under the chart. */
		static final int BAR_TOP = CHART_TOP + BankHistoryChart.PLOT_HEIGHT + 4;
		static final int HEIGHT = BAR_TOP + RangeBar.HEIGHT + RangeBar.SIDE;
		/** Between the change line's gp figure and its percentage. */
		static final int FIGURE_GAP = 8;
		static final int GAP = 4;
		static final int RING_GAP = 5;

		ChartBlock()
		{
			super(null);
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			add(changeDays);
			add(changeGp);
			add(changePct);
			add(readoutDay);
			add(readoutTotal);
			add(chart);
			add(bar);
			setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, HEIGHT));
		}

		@Override
		public void doLayout()
		{
			final int left = PAD_X;
			final int right = getWidth() - PAD_X;

			final int pctW = pref(changePct);
			final int pctX = right - pctW;
			put(changePct, pctX, pctW, CHANGE_BASELINE);
			final int gpW = pref(changeGp);
			final int gpX = pctX - (gpW == 0 ? 0 : FIGURE_GAP) - gpW;
			put(changeGp, gpX, gpW, CHANGE_BASELINE);
			put(changeDays, left, Math.min(pref(changeDays), gpX - GAP - left), CHANGE_BASELINE);

			final int totalW = pref(readoutTotal);
			final int totalX = right - totalW;
			put(readoutTotal, totalX, totalW, READOUT_BASELINE);
			put(readoutDay, left, Math.min(pref(readoutDay), totalX - GAP - left), READOUT_BASELINE);

			chart.setBounds(0, CHART_TOP, getWidth(), BankHistoryChart.PLOT_HEIGHT);
			bar.setBounds(RangeBar.SIDE, BAR_TOP, getWidth() - 2 * RangeBar.SIDE, RangeBar.HEIGHT);
		}

		private int pref(final JLabel label)
		{
			return label.getPreferredSize().width;
		}

		/** A label at {@code x}, {@code width} wide, its text standing on {@code baseline}. */
		private void put(final JLabel label, final int x, final int width, final int baseline)
		{
			final FontMetrics fm = label.getFontMetrics(label.getFont());
			final int h = label.getPreferredSize().height;
			// With an icon the label centres its text in its height: the baseline is where the text's box puts it.
			final int top = baseline - fm.getAscent() - (h - fm.getHeight()) / 2;
			label.setBounds(x, top, Math.max(0, width), h);
		}

		@Override
		protected void paintComponent(final Graphics g)
		{
			super.paintComponent(g);
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(PAD_X - 4, RULE_Y, getWidth() - 2 * (PAD_X - 4), 1);
		}
	}

	/**
	 * The chart's range chips "7d 30d 90d all" as ONE segmented bar under the chart (the user, 2026-09-29: "i want the
	 * chips below like design 8"), in the Items | Net Worth History toggle's family - its look copied, not its code:
	 * four EQUAL cells (the last ones take any odd pixel) in a 1 px {@link ColorScheme#MEDIUM_GRAY_COLOR} frame with
	 * 1 px dividers of the same grey - the bar's own ground, round and between the cells' grounds, which the bar paints
	 * in each cell's background colour (the cells' labels are see-through, so that each word can stand on
	 * {@link #TEXT_BASELINE} whatever the face's ascent). The lit cell is filled {@link ColorScheme#BRAND_ORANGE} with
	 * its word in dark bold; the others are the card's grey with their word in light grey, or in
	 * {@link ColorScheme#MEDIUM_GRAY_COLOR} for a DIMMED range - one with no reading on or before its start - which is
	 * still a cell and still answers a press.
	 *
	 * <p>A LEFT press ({@link Widgets#isPress}) on an unlit cell hands its range to {@code onPick}; a press on the lit
	 * one does nothing, it is already the answer. The mouse over an unlit cell lights its word orange, as on the
	 * toggle. Nothing here decides which cell is lit: the view says so through {@link #light}. No hover text
	 * (amendment 9.13).
	 */
	static final class RangeBar extends JPanel
	{
		/** The bar's height, its frame included. */
		static final int HEIGHT = 18;
		/** The card left on either side of the bar, and under it. */
		static final int SIDE = 6;
		static final Font LIT_FONT = Widgets.sansBold(12);
		static final Font UNLIT_FONT = Widgets.sans(12);
		/** A word's baseline, 13 px under the bar's top: 3 px of cell over its 9 px of ink and 4 px under it. */
		static final int TEXT_BASELINE = 13;

		private final Map<BankHistoryRange, JLabel> cells = new EnumMap<>(BankHistoryRange.class);
		@Nullable
		private BankHistoryRange lit;
		private Set<BankHistoryRange> dim = EnumSet.noneOf(BankHistoryRange.class);
		/** Each cell's ground, inside the frame: its left edge and width, from the last layout. */
		private final int[] groundX = new int[BankHistoryRange.values().length];
		private final int[] groundWidth = new int[BankHistoryRange.values().length];

		RangeBar(final Consumer<BankHistoryRange> onPick)
		{
			super(null);
			Objects.requireNonNull(onPick, "onPick");
			setOpaque(true);
			// The frame and the dividers: the bar's own ground, round and between the cells' grounds.
			setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
			for (final BankHistoryRange r : BankHistoryRange.values())
			{
				final JLabel cell = new JLabel(r.label());
				cell.setHorizontalAlignment(SwingConstants.CENTER);
				cell.setVerticalAlignment(SwingConstants.TOP);
				cell.setOpaque(false);
				cell.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mousePressed(final MouseEvent e)
					{
						if (Widgets.isPress(e) && r != lit)
						{
							onPick.accept(r);
						}
					}

					@Override
					public void mouseEntered(final MouseEvent e)
					{
						if (r != lit)
						{
							cell.setForeground(ColorScheme.BRAND_ORANGE);
						}
					}

					@Override
					public void mouseExited(final MouseEvent e)
					{
						paintCell(r);
					}
				});
				cells.put(r, cell);
				add(cell);
				paintCell(r);
			}
			setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH - 2 * SIDE, HEIGHT));
		}

		/** Lights {@code range}'s cell and dims those in {@code dimmed} (the view never dims the lit one). */
		void light(final BankHistoryRange range, final Set<BankHistoryRange> dimmed)
		{
			lit = range;
			dim = dimmed.isEmpty() ? EnumSet.noneOf(BankHistoryRange.class) : EnumSet.copyOf(dimmed);
			for (BankHistoryRange r : BankHistoryRange.values())
			{
				paintCell(r);
			}
		}

		private void paintCell(final BankHistoryRange r)
		{
			final JLabel cell = cells.get(r);
			final boolean on = r == lit;
			cell.setBackground(on ? ColorScheme.BRAND_ORANGE : ColorScheme.DARKER_GRAY_COLOR);
			cell.setForeground(on ? ColorScheme.DARKER_GRAY_COLOR
				: dim.contains(r) ? ColorScheme.MEDIUM_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
			final Font font = on ? LIT_FONT : UNLIT_FONT;
			cell.setFont(font);
			// The word stands on TEXT_BASELINE: a top inset in a cell as tall as the bar, not the label's own centring,
			// which would set it a pixel low in a face whose ascent is the logical font's (its fallbacks' included).
			final int inset = Math.max(0, TEXT_BASELINE - cell.getFontMetrics(font).getAscent());
			if (!(cell.getBorder() instanceof EmptyBorder) || cell.getBorder().getBorderInsets(cell).top != inset)
			{
				cell.setBorder(new EmptyBorder(inset, 0, 0, 0));
			}
			// A hand only where a press does something.
			cell.setCursor(on ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		}

		/**
		 * Four equal cells inside the 1 px frame, 1 px apart; the last ones take any odd pixel. Each word's label spans
		 * its cell's width and the bar's whole height; its ground is painted by {@link #paintComponent}.
		 */
		@Override
		public void doLayout()
		{
			final BankHistoryRange[] ranges = BankHistoryRange.values();
			final int n = ranges.length;
			final int room = getWidth() - 2 - (n - 1);
			final int base = room / n;
			final int odd = room % n;
			int x = 1;
			for (int k = 0; k < n; k++)
			{
				final int w = Math.max(0, base + (k >= n - odd ? 1 : 0));
				groundX[k] = x;
				groundWidth[k] = w;
				cells.get(ranges[k]).setBounds(x, 0, w, getHeight());
				x += w + 1;
			}
		}

		/** The frame and dividers (the bar's ground), then each cell's ground inside them in its cell's colour. */
		@Override
		protected void paintComponent(final Graphics g)
		{
			super.paintComponent(g);
			final BankHistoryRange[] ranges = BankHistoryRange.values();
			for (int k = 0; k < ranges.length; k++)
			{
				g.setColor(cells.get(ranges[k]).getBackground());
				g.fillRect(groundX[k], 1, groundWidth[k], Math.max(0, getHeight() - 2));
			}
		}
	}

	/**
	 * A top-to-bottom stack: every child as wide as the parent and exactly as tall as it asks, {@code gap} px apart.
	 * Not {@code DynamicGridLayout}, which scales a child's preferred width by a ratio and truncates it - a 213 px
	 * row in a 215 px column came out 214 - and scales heights too.
	 */
	static final class Stack implements LayoutManager
	{
		private final int gap;

		Stack(final int gap)
		{
			this.gap = gap;
		}

		@Override
		public void addLayoutComponent(final String name, final Component comp)
		{
		}

		@Override
		public void removeLayoutComponent(final Component comp)
		{
		}

		@Override
		public Dimension preferredLayoutSize(final Container parent)
		{
			final Insets in = parent.getInsets();
			int width = 0;
			int height = 0;
			for (int i = 0; i < parent.getComponentCount(); i++)
			{
				final Dimension d = parent.getComponent(i).getPreferredSize();
				width = Math.max(width, d.width);
				height += (i == 0 ? 0 : gap) + d.height;
			}
			return new Dimension(in.left + width + in.right, in.top + height + in.bottom);
		}

		@Override
		public Dimension minimumLayoutSize(final Container parent)
		{
			return preferredLayoutSize(parent);
		}

		@Override
		public void layoutContainer(final Container parent)
		{
			final Insets in = parent.getInsets();
			final int width = parent.getWidth() - in.left - in.right;
			int y = in.top;
			for (int i = 0; i < parent.getComponentCount(); i++)
			{
				final Component c = parent.getComponent(i);
				final int h = c.getPreferredSize().height;
				c.setBounds(in.left, y, width, h);
				y += h + gap;
			}
		}
	}

	/** The readout's small ring, in the line's colour; nothing when there is no point to name. */
	private static final class Ring implements Icon
	{
		static final int SIZE = 9;
		@Nullable
		Color colour;

		@Override
		public void paintIcon(final Component c, final Graphics g, final int x, final int y)
		{
			if (colour == null)
			{
				return;
			}
			final Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(colour);
				g2.setStroke(new BasicStroke(1.2f));
				g2.draw(new Ellipse2D.Double(x + 0.6, y + 0.6, SIZE - 1.2, SIZE - 1.2));
				g2.fill(new Ellipse2D.Double(x + SIZE / 2.0 - 1.2, y + SIZE / 2.0 - 1.2, 2.4, 2.4));
			}
			finally
			{
				g2.dispose();
			}
		}

		@Override
		public int getIconWidth()
		{
			return SIZE;
		}

		@Override
		public int getIconHeight()
		{
			return SIZE;
		}
	}

	/**
	 * One grey sentence wrapped to the component's width - a plain painted component rather than an HTML label
	 * (plan 7.2 item 9). Before it has a width it wraps at {@link Widgets#CONTENT_WIDTH}.
	 */
	static final class Sentence extends JComponent
	{
		static final int PAD_X = 12;
		static final int PAD_Y = 8;
		static final Font FONT = Widgets.sans(11);

		private final String text;

		Sentence(final String text)
		{
			this.text = Objects.requireNonNull(text, "text");
			setOpaque(true);
			setBackground(ColorScheme.DARKER_GRAY_COLOR);
			setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			setFont(FONT);
		}

		/** The lines {@link #text} breaks into at {@code width} px, words kept whole. */
		List<String> lines(final int width)
		{
			final FontMetrics fm = getFontMetrics(FONT);
			final int room = Math.max(1, width - 2 * PAD_X);
			final List<String> out = new ArrayList<>();
			String current = "";
			for (String word : text.split(" "))
			{
				final String tried = current.isEmpty() ? word : current + " " + word;
				if (current.isEmpty() || fm.stringWidth(tried) <= room)
				{
					current = tried;
				}
				else
				{
					out.add(current);
					current = word;
				}
			}
			if (!current.isEmpty())
			{
				out.add(current);
			}
			return out;
		}

		private int layoutWidth()
		{
			return getWidth() > 0 ? getWidth() : Widgets.CONTENT_WIDTH;
		}

		@Override
		public Dimension getPreferredSize()
		{
			final FontMetrics fm = getFontMetrics(FONT);
			return new Dimension(Widgets.CONTENT_WIDTH, 2 * PAD_Y + lines(layoutWidth()).size() * fm.getHeight());
		}

		@Override
		public void setBounds(final int x, final int y, final int width, final int height)
		{
			final boolean resized = width != getWidth();
			super.setBounds(x, y, width, height);
			if (resized && getPreferredSize().height != height)
			{
				revalidate();
			}
		}

		@Override
		protected void paintComponent(final Graphics g)
		{
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
			final Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setFont(FONT);
				g2.setColor(getForeground());
				final FontMetrics fm = g2.getFontMetrics();
				int y = PAD_Y + fm.getAscent();
				for (String line : lines(getWidth()))
				{
					g2.drawString(line, PAD_X, y);
					y += fm.getHeight();
				}
			}
			finally
			{
				g2.dispose();
			}
		}
	}
}
