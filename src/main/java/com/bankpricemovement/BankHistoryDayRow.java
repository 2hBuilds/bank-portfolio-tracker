package com.bankpricemovement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * One row of the History view's list of days (addendum AU, plan 7.2 items 3, 4 and 11; contract section 6).
 *
 * <p>A day WITH a reading is a {@value #HEIGHT} px card with a coloured left edge - green for a rise, the rows' red
 * for a fall, grey for no change or the first reading - and three columns of two lines:
 * <pre>
 *   Sat 26 Sep            736m            +0.4%
 *   vs 25 Sep             736,412,683     +3.41m
 * </pre>
 * A day with NO reading is a thin {@value #CARRIED_HEIGHT} px grey line reading its date alone, "Tue 22 Sep" (the
 * user on the first live look, 2026-09-29: "remove the text '-carried, 754m' on days we dont login"; the chart still
 * draws the day carried forward). There is no clock on a row (plan 7.2 item 3).
 *
 * <p><b>The columns are measured at 213 px, not taken from the mock</b> (plan 7.2 item 2, amendment 9.8). Mock 5 was
 * drawn 223 px wide; at 213 its second line - "vs 01 May, 100 days" (95 px at 10 px: a May date is the widest, and
 * every 3-digit span measures the same), "12,345,678,901" (75) and "+1.66m" (36, the widest the row face's gp figure
 * ever prints) - wants 206 px of text plus two gaps in 200 px of room, and collides. So the left column is
 * {@value #LEFT_COLUMN} px (the widest honest sub-line), the exact total is printed at {@value #EXACT_SIZE} px
 * (64 px for twelve billion), the gap between columns is {@value #GAP} px, the right column is right-aligned to the
 * row's inner edge, and the middle column takes what is left - line 2 of the worst row is then
 * 95 + 2 + 64 + 2 + 36 = 199 of 200 px at 213, and at 230 all the slack goes to the middle. Every label is laid out at
 * its own preferred width and cut to its room only as a last resort; {@code BankHistoryViewFitTest} proves the widest
 * honest row cuts nothing and overlaps nothing.
 *
 * <p>KNOWN LIMIT: a gap of 1,000 days or more between two readings ("vs 01 May, 1000 days", 101 px) does not fit
 * the column and is cut with an ellipsis; the words are final, and nearly three years without a reading is the
 * only way to reach it.
 *
 * <p>Built from an immutable {@link Model} of the strings it prints, so the view can keep a row whose model has not
 * changed (plan 7.2 item 9) and a test can build the widest honest row directly. Plain labels only: no
 * {@code <html>}, no tooltip (amendment 9.13).
 */
final class BankHistoryDayRow extends JPanel
{
	/** A day with a reading. */
	static final int HEIGHT = 36;
	/** A carried day: no reading of its own. */
	static final int CARRIED_HEIGHT = 20;
	/** The "Day / Bank Total / Change" header over the list. */
	static final int HEADER_HEIGHT = 16;

	/** Inside the {@link Widgets#EDGE_WIDTH} px edge, and before the right edge. */
	static final int PAD_LEFT = 5;
	static final int PAD_RIGHT = 5;
	/**
	 * The date column: "vs 01 May, 100 days" at {@value #SUB_SIZE} px, the widest honest sub-line (any May date with
	 * a 3-digit span), is 95 px; the widest date on line 1 ("01 May 2025", bold 12) is 71.
	 */
	static final int LEFT_COLUMN = 95;
	/** Between two columns: 2, not 3, so the 95 px sub-line and the 64 px exact total both fit line 2 at 213 px. */
	static final int GAP = 2;
	/** Where a carried row's words start, from the row's left edge (mock 5 indents them past the rows' text). */
	static final int CARRIED_INDENT = Widgets.EDGE_WIDTH + PAD_LEFT + 4;

	/** A date more than this many days before today prints its year (plan 7.2 item 11, amendment 9.14). */
	static final int YEAR_AFTER_DAYS = 300;

	static final String FIRST_READING = "first reading";
	static final String DAY_HEADER = "Day";
	static final String TOTAL_HEADER = "Bank Total";
	static final String CHANGE_HEADER = "Change";

	private static final int DATE_SIZE = 12;
	private static final int SUB_SIZE = 10;
	private static final int TOTAL_SIZE = 13;
	/** A size below the other sub-lines: the one thing that makes the widest honest row fit at 213 px. */
	private static final int EXACT_SIZE = 9;
	private static final int PCT_SIZE = 14;
	private static final int GP_SIZE = 10;
	private static final int CARRIED_SIZE = 11;
	private static final int HEADER_SIZE = 11;

	static final Font DATE_FONT = Widgets.sansBold(DATE_SIZE);
	static final Font SUB_FONT = Widgets.sans(SUB_SIZE);
	static final Font TOTAL_FONT = Widgets.sans(TOTAL_SIZE);
	static final Font EXACT_FONT = Widgets.sans(EXACT_SIZE);
	static final Font PCT_FONT = Widgets.sansBold(PCT_SIZE);
	static final Font GP_FONT = Widgets.sans(GP_SIZE);
	static final Font CARRIED_FONT = Widgets.sans(CARRIED_SIZE);
	static final Font HEADER_FONT = Widgets.sans(HEADER_SIZE);

	/**
	 * Line 1's baseline and line 2's, from the row's top: far enough apart that no line-1 label's box (the 14 px
	 * percentage's descent included) reaches into a line-2 label's.
	 */
	private static final int LINE1_BASELINE = 16;
	private static final int LINE2_BASELINE = 31;

	/** The grey of a carried row's ground: between the card grey and the sidebar's, as mock 5 draws it. */
	static final Color CARRIED_GROUND = ColorScheme.DARK_GRAY_HOVER_COLOR;
	/** The edge of a reading that did not move, or of the first reading: a grey that shows on the card grey. */
	static final Color FLAT_EDGE = ColorScheme.MEDIUM_GRAY_COLOR;

	private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);

	/** The strings one row prints, and the sign its colours follow. Value equality: the view reuses equal rows. */
	static final class Model
	{
		private final LocalDate day;
		private final boolean carried;
		private final String date;
		private final String sub;
		private final String total;
		private final String exact;
		private final String pct;
		private final String gp;
		/** +1 rise, -1 fall, 0 no change; {@link #first} rows are 0 too. */
		private final int signum;
		private final boolean first;

		Model(final LocalDate day, final boolean carried, final String date, final String sub, final String total,
			final String exact, final String pct, final String gp, final int signum, final boolean first)
		{
			this.day = Objects.requireNonNull(day, "day");
			this.carried = carried;
			this.date = text(date);
			this.sub = text(sub);
			this.total = text(total);
			this.exact = text(exact);
			this.pct = text(pct);
			this.gp = text(gp);
			this.signum = Integer.signum(signum);
			this.first = first;
		}

		private static String text(@Nullable final String s)
		{
			return s == null ? "" : s;
		}

		LocalDate day()
		{
			return day;
		}

		boolean carried()
		{
			return carried;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Model))
			{
				return false;
			}
			final Model m = (Model) o;
			return carried == m.carried && signum == m.signum && first == m.first && day.equals(m.day)
				&& date.equals(m.date) && sub.equals(m.sub) && total.equals(m.total) && exact.equals(m.exact)
				&& pct.equals(m.pct) && gp.equals(m.gp);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(day, carried, date, sub, total, exact, pct, gp, signum, first);
		}

		@Override
		public String toString()
		{
			return carried ? "Model{" + date + '}'
				: "Model{" + date + " | " + sub + " | " + total + " | " + exact + " | " + pct + " | " + gp + '}';
		}
	}

	/**
	 * What the row of one calendar day prints, every figure through {@link BankHistoryPoint#valueFor} by way of
	 * {@link BankHistoryMath}: the compact total as the card prints it ({@link MovementMath#formatGp}, so "736m"),
	 * the exact one, the change against the PREVIOUS reading (% bold, gp quiet), the sub-line naming the day
	 * compared - "vs 25 Sep", "vs 21 Sep, 2 days", or "first reading" for the first reading ever. A day with no
	 * reading prints its date and nothing else.
	 *
	 * <p>With {@code hideAmounts} on (1.1.0 part E) the total, the exact total and the gp change read their masks
	 * ({@link AmountMask}) and the date, the sub-line and the percentage are what they always are.
	 *
	 * @param series the series already cut to {@code today}
	 */
	static Model model(final BankHistorySeries series, final BankHistoryMath.Day day, final LocalDate today,
		@Nullable final ViewOptions options, final boolean hideAmounts)
	{
		final String date = dayText(day.day(), today);
		if (day.carried())
		{
			return new Model(day.day(), true, date, "", "", "", "", "", 0, false);
		}
		final String total = AmountMask.amount(hideAmounts, MovementMath.formatGp(day.valueGp()));
		final String exact = AmountMask.amount(hideAmounts, MovementMath.formatExact(day.valueGp()));
		final BankHistoryMath.Change change = BankHistoryMath.vsPrevious(series, day.day(), options);
		if (change == null)
		{
			return new Model(day.day(), false, date, FIRST_READING, total, exact, MovementMath.DASH, "", 0, true);
		}
		final long delta = change.deltaGp();
		final int span = change.spanDays();
		final String sub = "vs " + MovementMath.formatDay(change.fromDay()) + (span > 1 ? ", " + span + " days" : "");
		return new Model(day.day(), false, date, sub, total, exact,
			MovementMath.formatPctCompact(change.pct(), delta),
			delta == 0L ? "" : AmountMask.change(hideAmounts, MovementRowPanel.signedGp(delta)), Long.signum(delta), false);
	}

	/** "Tue 22 Sep" (amendment 9.14's weekday form). */
	static String weekday(final LocalDate day)
	{
		return WEEKDAY.format(day) + " " + MovementMath.formatDay(day);
	}

	/**
	 * A list row's date: the weekday form, or - for a day more than {@value #YEAR_AFTER_DAYS} days before today -
	 * amendment 9.14's {@code formatDay + " " + year} ("30 Sep 2025"), which leaves the weekday out so the year fits
	 * the date column.
	 */
	static String dayText(final LocalDate day, final LocalDate today)
	{
		if (ChronoUnit.DAYS.between(day, today) > YEAR_AFTER_DAYS)
		{
			return MovementMath.formatDay(day) + " " + day.getYear();
		}
		return weekday(day);
	}

	private final Model model;
	private final JLabel dateLabel;
	@Nullable
	private final JLabel subLabel;
	@Nullable
	private final JLabel totalLabel;
	@Nullable
	private final JLabel exactLabel;
	@Nullable
	private final JLabel pctLabel;
	@Nullable
	private final JLabel gpLabel;

	BankHistoryDayRow(final Model model)
	{
		super(null);
		this.model = Objects.requireNonNull(model, "model");
		setOpaque(true);
		if (model.carried)
		{
			setBackground(CARRIED_GROUND);
			setBorder(new EmptyBorder(0, CARRIED_INDENT, 0, PAD_RIGHT));
			dateLabel = add(Widgets.label(model.date, CARRIED_FONT, Widgets.PLACEHOLDER_COLOR));
			subLabel = null;
			totalLabel = null;
			exactLabel = null;
			pctLabel = null;
			gpLabel = null;
			setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, CARRIED_HEIGHT));
			return;
		}
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		final Color edge = model.signum == 0 ? FLAT_EDGE : Widgets.move(model.signum, Widgets.Kind.EDGE);
		setBorder(Widgets.card(edge, new Insets(0, PAD_LEFT, 0, PAD_RIGHT)));
		dateLabel = add(Widgets.label(model.date, DATE_FONT, Color.WHITE));
		subLabel = add(Widgets.label(model.sub, SUB_FONT, ColorScheme.LIGHT_GRAY_COLOR));
		totalLabel = add(Widgets.label(model.total, TOTAL_FONT, Color.WHITE));
		exactLabel = add(Widgets.label(model.exact, EXACT_FONT, ColorScheme.LIGHT_GRAY_COLOR));
		pctLabel = add(Widgets.label(model.pct, PCT_FONT, Widgets.move(model.signum, Widgets.Kind.FIGURE)));
		gpLabel = add(Widgets.label(model.gp, GP_FONT, Widgets.move(model.signum, Widgets.Kind.QUIET)));
		setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, HEIGHT));
	}

	private JLabel add(final JLabel label)
	{
		super.add(label);
		return label;
	}

	Model model()
	{
		return model;
	}

	/** Columns from the row's own width, so the same row is right at 213 px and at 230. */
	@Override
	public void doLayout()
	{
		final Insets in = getInsets();
		final int left = in.left;
		final int right = getWidth() - in.right;
		if (model.carried)
		{
			place(dateLabel, left, right - left, (getHeight() - height(dateLabel)) / 2);
			return;
		}
		final int middle = left + LEFT_COLUMN + GAP;
		final int pctX = right - width(pctLabel);
		final int gpX = right - width(gpLabel);
		place(pctLabel, pctX, width(pctLabel), top(pctLabel, LINE1_BASELINE));
		place(gpLabel, gpX, width(gpLabel), top(gpLabel, LINE2_BASELINE));
		place(totalLabel, middle, Math.min(width(totalLabel), pctX - GAP - middle), top(totalLabel, LINE1_BASELINE));
		place(exactLabel, middle, Math.min(width(exactLabel), gpX - GAP - middle), top(exactLabel, LINE2_BASELINE));
		place(dateLabel, left, Math.min(width(dateLabel), middle - GAP - left), top(dateLabel, LINE1_BASELINE));
		place(subLabel, left, Math.min(width(subLabel), middle - GAP - left), top(subLabel, LINE2_BASELINE));
	}

	private static int width(final JLabel label)
	{
		return label.getPreferredSize().width;
	}

	private static int height(final JLabel label)
	{
		return label.getPreferredSize().height;
	}

	/** Where a label's top goes for its text to stand on {@code baseline}. */
	private static int top(final JLabel label, final int baseline)
	{
		final FontMetrics fm = label.getFontMetrics(label.getFont());
		return baseline - fm.getAscent();
	}

	private static void place(final JLabel label, final int x, final int width, final int y)
	{
		label.setBounds(x, y, Math.max(0, width), height(label));
	}

	/**
	 * The "Day / Bank Total / Change" header over the list, on the sidebar's own ground, its words standing over the
	 * columns of {@link BankHistoryDayRow}.
	 */
	static JPanel header()
	{
		final JLabel day = Widgets.label(DAY_HEADER, HEADER_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		final JLabel total = Widgets.label(TOTAL_HEADER, HEADER_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		final JLabel change = Widgets.label(CHANGE_HEADER, HEADER_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		final JPanel header = new JPanel(null)
		{
			@Override
			public void doLayout()
			{
				final int left = Widgets.EDGE_WIDTH + PAD_LEFT;
				final int right = getWidth() - PAD_RIGHT;
				final int y = (getHeight() - height(day)) / 2;
				place(day, left, width(day), y);
				place(total, left + LEFT_COLUMN + GAP, width(total), y);
				place(change, right - width(change), width(change), y);
			}
		};
		header.setOpaque(false);
		header.add(day);
		header.add(total);
		header.add(change);
		header.setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, HEADER_HEIGHT));
		return header;
	}
}
