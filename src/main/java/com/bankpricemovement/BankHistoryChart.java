package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import net.runelite.client.ui.ColorScheme;

/**
 * The Net Worth History's chart (addendum AU, plan 7.2 item 10), drawn as the user chose it on 2026-09-29 - "plain
 * line, design 2": the "Mountain". The bank's total over the chart's range, one point per drawn day, in a
 * {@link #PLOT_HEIGHT} px plot that is the whole component.
 *
 * <p><b>The mountain.</b> A 1.5 px line in the colour of the range's direction (a rise green, a fall the lifted red, no
 * change grey - the reader's own up and down colours since 1.1.0, or ONE colour whatever the range did while "Single
 * chart colour" is on, {@link #setFixedColour}) over a fill that fades from a tint of that colour at the line's
 * highest point down to nothing at the plot's bottom. The range's HIGH and LOW - chosen among the days WITH a
 * reading, the first of equals - each carry a small grey dot and their compact total ("759m") in small grey figures,
 * the high's above its point and the low's below; they are drawn only when the range has two readings or more and the
 * two differ, since a single reading or a flat range has no high or low to tell (the readout already gives the total).
 * The latest READING carries a filled dot inside a soft halo. No gridline, axis or baseline.
 *
 * <p><b>A plain line</b> (the user: "plain line"). A day with no reading is carried forward flat from the last reading
 * ({@link BankHistoryMath#days}) and takes its place on the time axis, and NOTHING marks it: the same stroke, colour
 * and fill as any other day, the step into the next reading an ordinary segment. A series of ONE reading draws that
 * one dot and halo and no line.
 *
 * <p><b>Hover</b>: one {@link MouseAdapter}, registered as the mouse and the mouse-motion listener, picks the drawn
 * point nearest the pointer by x - a carried day as readily as a reading - draws a thin dashed guide and a ring on it,
 * and tells the view (which prints the readout); leaving the chart hands the readout back to the latest reading. The
 * chart repaints only when the hovered index changes, and the points' coordinates and the figures' places are computed
 * when the data or the size changes - never per mouse move. It paints and hit-tests from its own {@link #getWidth()}
 * (amendment 9.8). No tooltip (amendment 9.13), with the one exception of the next paragraph.
 *
 * <p><b>Transfers</b> (1.2.2). The line, its fill, the high and the low and the hover are drawn on each day's
 * {@link BankHistoryMath.Day#drawnGp() drawn} total, so a day the player marked as a transfer leaves no cliff. When shifting the days
 * would draw one below zero ({@link BankHistoryMath#drawsBelowZero}) the view hands in the marked days instead and the chart draws
 * the REAL totals, the step into each marked day dotted in grey with a small orange mark at the point. A point drawn at a
 * total that is not the real one (a day before a marked transfer) carries one hover, {@link #tipAt}: "<exact total> gp at the
 * time", the amount masked while amounts are hidden; every other point has none.
 */
final class BankHistoryChart extends JComponent
{
	/** The plot's height, the whole component: 110 px (design 2; 84 before it, 42 as first built). */
	static final int PLOT_HEIGHT = 110;
	/** The first point's x: on the range bar's left edge. */
	static final int INSET_LEFT = 6;
	/**
	 * Room right of the last point: the latest point's {@link #DOT_RADIUS} px dot ends on the range bar's right edge and
	 * its {@link #HALO_RADIUS} px halo 3 px inside the chart block.
	 */
	static final int INSET_RIGHT = 9;
	/** Room above the highest point and below the lowest one, for the high's and the low's figures. */
	static final int INSET_Y = 14;
	/** The high's and the low's figures stay this far inside either side of the chart. */
	static final int FIGURE_MARGIN = 6;
	/** A figure's baseline (the high) or its ink's top (the low) stands at least this far from its point. */
	static final int FIGURE_CLEAR = 3;
	/** The air a figure keeps round the latest point's halo. */
	static final int HALO_AIR = 2;
	/**
	 * How far every pixel of a figure's ink keeps from the line's centre: the 1.5 px stroke's half and a little over a
	 * pixel of air.
	 */
	private static final double LINE_AIR = 0.75 + 1.2;
	/** No clear row inside the plot at a figure's left edge. */
	private static final int NOWHERE = Integer.MIN_VALUE;
	static final double HALO_RADIUS = 6;
	static final double DOT_RADIUS = 3;
	/** The fill's alpha at the line's highest point: 70 for a rise or no change, 80 for a fall (a red at 70 reads muddy). */
	static final int FILL_ALPHA = 70;
	static final int FILL_ALPHA_FALL = 80;
	static final int HALO_ALPHA = 50;
	/** The high's and the low's figures. */
	static final Font FIGURE_FONT = Widgets.sans(10);

	private static final BasicStroke LINE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke GUIDE = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
		new float[]{2f, 2f}, 0f);
	private static final BasicStroke RING = new BasicStroke(1.6f);
	private static final double MARK_RADIUS = 1.5;
	/** The orange mark on a day the player marked as a transfer, when the chart draws the real totals (1.2.2). */
	private static final double TRANSFER_RADIUS = 2;
	private static final double RING_RADIUS = 4.5;
	/** What follows the real total in the hover on a shifted point (1.2.2). */
	static final String AT_THE_TIME = " gp at the time";

	/** Told whenever the hovered point changes: its index, or -1 when the pointer left. */
	interface HoverListener
	{
		void hoverChanged(int index);
	}

	/** The high's or the low's figure as placed: the day it names, its text, its left edge and its baseline. */
	static final class Figure
	{
		/** The day's index among the drawn days. */
		final int index;
		final String text;
		final int left;
		final int baseline;

		Figure(final int index, final String text, final int left, final int baseline)
		{
			this.index = index;
			this.text = text;
			this.left = left;
			this.baseline = baseline;
		}
	}

	private final HoverListener listener;

	List<BankHistoryMath.Day> days = Collections.emptyList();
	private boolean drawLine;
	private int sign;
	private Color line = ColorScheme.LIGHT_GRAY_COLOR;
	/**
	 * The one colour the line, its fill and the latest point are drawn in whatever the range did (1.1.0 part C's "Single
	 * chart colour"), or null for the range's own - a rise or a fall in the reader's up and down colours.
	 */
	@Nullable
	private Color fixed;
	/**
	 * 1.1.0 part E: whether the high's and the low's figures read their mask ({@link AmountMask#AMOUNT}) in place of the
	 * compact totals. The line, its shape, the dots and the hover are the same either way: only the two words change.
	 */
	private boolean hideAmounts;
	/**
	 * 1.2.2: true while the chart draws the REAL totals - the fallback for a shift that would go below zero - and the days whose step
	 * is dotted and marked, by index; empty while it draws the shifted ones.
	 */
	private boolean real;
	private boolean[] stepInto = new boolean[0];
	/** The latest reading among {@link #days}, or -1. */
	private int latest = -1;
	int hover = -1;

	/** The points' coordinates and the figures' places, for the size they were computed at. */
	private double[] xs = new double[0];
	private double[] ys = new double[0];
	private List<Figure> figures = Collections.emptyList();
	private int laidWidth = -1;
	private int laidHeight = -1;

	BankHistoryChart(final HoverListener listener)
	{
		this.listener = Objects.requireNonNull(listener, "listener");
		setOpaque(false);
		setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, PLOT_HEIGHT));
		final MouseAdapter mouse = new MouseAdapter()
		{
			@Override
			public void mouseMoved(final MouseEvent e)
			{
				setHover(indexAt(e.getX()));
			}

			@Override
			public void mouseDragged(final MouseEvent e)
			{
				setHover(indexAt(e.getX()));
			}

			@Override
			public void mouseEntered(final MouseEvent e)
			{
				setHover(indexAt(e.getX()));
			}

			@Override
			public void mouseExited(final MouseEvent e)
			{
				setHover(-1);
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		// The hover is asked for point by point (getToolTipText(MouseEvent)), so the chart is registered and has no fixed text.
		ToolTipManager.sharedInstance().registerComponent(this);
	}

	@Override
	public String getToolTipText(final MouseEvent e)
	{
		return tipAt(indexAt(e.getX()));
	}

	/**
	 * The hover of drawn point {@code index} (1.2.2): its REAL total, exact, and " gp at the time" - or its mask while amounts are
	 * hidden - when the chart draws that point at another total (a day before a marked transfer); null for a point drawn at its
	 * own total, for the real-totals fallback, and for an index that names no point.
	 */
	@Nullable
	String tipAt(final int index)
	{
		if (index < 0 || index >= days.size() || value(days.get(index)) == days.get(index).valueGp())
		{
			return null;
		}
		return AmountMask.amount(hideAmounts, MovementMath.formatExact(days.get(index).valueGp())) + AT_THE_TIME;
	}

	/**
	 * New points to draw. Clears the hover without telling the listener - the view that calls this prints its
	 * readout from the new data straight after.
	 *
	 * @param days     the drawn days, ascending, already thinned
	 * @param drawLine false for a series of one reading: the dot alone
	 * @param sign     the range's direction, which colours the line, its fill and the latest point: +1 a rise, -1 a
	 *                 fall, 0 no change
	 */
	void setData(final List<BankHistoryMath.Day> days, final boolean drawLine, final int sign)
	{
		setData(days, drawLine, sign, null);
	}

	/**
	 * @param marked null to draw the shifted totals; else the days the player marked as transfers, and the REAL totals are drawn with
	 *               the step into each dotted and marked - a day thinned away marks the drawn day that stands for it
	 */
	void setData(final List<BankHistoryMath.Day> days, final boolean drawLine, final int sign,
		@Nullable final Set<LocalDate> marked)
	{
		this.days = days == null ? Collections.emptyList() : days;
		this.drawLine = drawLine;
		this.sign = Integer.signum(sign);
		real = marked != null;
		stepInto = new boolean[this.days.size()];
		for (final LocalDate note : marked == null ? Collections.<LocalDate>emptySet() : marked)
		{
			for (int i = 0; i < stepInto.length; i++)
			{
				if (!this.days.get(i).day().isBefore(note))
				{
					stepInto[i] = true;
					break;
				}
			}
		}
		line = lineFor(this.sign);
		latest = -1;
		for (int i = this.days.size() - 1; i >= 0; i--)
		{
			if (!this.days.get(i).carried())
			{
				latest = i;
				break;
			}
		}
		hover = -1;
		laidWidth = -1;
		repaint();
	}

	/**
	 * Draws the chart in one colour whatever the range did (1.1.0 part C), or in the range's own again for null: the line,
	 * its fill, its latest point and its halo, and the hover ring. The days and the hover are untouched; only the colour
	 * changes, so this costs a repaint and nothing else. Any transparency is dropped: the fill's tint is {@link #FILL_ALPHA}
	 * of it, as for a rise.
	 */
	void setFixedColour(@Nullable final Color colour)
	{
		fixed = colour == null ? null : new Color(colour.getRGB());
		line = lineFor(sign);
		repaint();
	}

	/**
	 * Hides or shows the high's and the low's figures (1.1.0 part E): each reads {@link AmountMask#AMOUNT} while hidden, so the
	 * chart keeps its line and shape and says nothing about the totals. The figures are placed again at the next paint
	 * (the mask is a different width from the total it replaces), so this costs a repaint and nothing else.
	 */
	void setAmountsHidden(final boolean hide)
	{
		hideAmounts = hide;
		laidWidth = -1;
		repaint();
	}

	/** The colour the line is drawn in for a range that went {@code direction}: the fixed colour, else the move rule's. */
	private Color lineFor(final int direction)
	{
		return fixed != null ? fixed : Widgets.move(direction, Widgets.Kind.FIGURE);
	}

	List<BankHistoryMath.Day> days()
	{
		return days;
	}

	/** The total this chart draws for a day: the shifted one, or the real one in the below-zero fallback (1.2.2). */
	long value(final BankHistoryMath.Day day)
	{
		return real ? day.valueGp() : day.drawnGp();
	}

	/** The point the readout names: the hovered one, else the latest reading, else the last day drawn; -1 if none. */
	int readoutIndex()
	{
		if (hover >= 0)
		{
			return hover;
		}
		return latest >= 0 ? latest : days.size() - 1;
	}

	/**
	 * The line's colour, which is every point's: a day with no reading is drawn like any other. The fixed colour while
	 * "Single chart colour" is on, whatever the range did; otherwise the range's direction in the up and down colours.
	 */
	Color lineColour()
	{
		return line;
	}

	/** The x of point {@code i} of {@code n} across a chart {@code width} px wide: evenly spaced, one point centred. */
	static double xAt(final int i, final int n, final int width)
	{
		if (n <= 1)
		{
			return (INSET_LEFT + width - INSET_RIGHT) / 2.0;
		}
		return INSET_LEFT + i * (width - (double) INSET_LEFT - INSET_RIGHT) / (n - 1);
	}

	/** The drawn point nearest {@code x}, or -1 when nothing is drawn. */
	int indexAt(final int x)
	{
		final int n = days.size();
		if (n == 0)
		{
			return -1;
		}
		if (n == 1)
		{
			return 0;
		}
		final double step = (getWidth() - (double) INSET_LEFT - INSET_RIGHT) / (n - 1);
		if (step <= 0)
		{
			return n - 1;
		}
		final long i = Math.round((x - INSET_LEFT) / step);
		return (int) Math.max(0, Math.min(n - 1, i));
	}

	private void setHover(final int index)
	{
		if (index == hover)
		{
			return;
		}
		hover = index;
		repaint();
		listener.hoverChanged(index);
	}

	/**
	 * The high's and the low's figures as they are painted at the chart's present size: none with fewer than two
	 * readings among the drawn days, or when the high and the low are equal.
	 */
	List<Figure> figures()
	{
		layOut();
		return figures;
	}

	/** The coordinates and the figures for the current size, computed once per data or size change. */
	private void layOut()
	{
		final int w = getWidth();
		final int h = getHeight();
		if (w == laidWidth && h == laidHeight)
		{
			return;
		}
		laidWidth = w;
		laidHeight = h;
		final int n = days.size();
		xs = new double[n];
		ys = new double[n];
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (BankHistoryMath.Day d : days)
		{
			min = Math.min(min, value(d));
			max = Math.max(max, value(d));
		}
		final double top = INSET_Y;
		final double plot = Math.max(1, h - 2 * INSET_Y);
		for (int i = 0; i < n; i++)
		{
			xs[i] = xAt(i, n, w);
			ys[i] = max == min ? top + plot / 2.0
				: top + (max - (double) value(days.get(i))) * plot / ((double) max - (double) min);
		}
		figures = placeFigures(w, h);
	}

	/**
	 * The high and the low, chosen among the days with a reading (a carried day repeats a total, it never makes one),
	 * each placed by one rule: centred on its point's x and kept {@link #FIGURE_MARGIN} px inside either side; the
	 * high's baseline {@link #FIGURE_CLEAR} px above its point, the low's ink top as far below it; and a figure that
	 * would come within {@link #HALO_AIR} px of the latest point's halo, or within {@link #LINE_AIR} px of the line's
	 * centre, steps one pixel further from its point (the high up, the low down) until it is clear of both. When that
	 * would take it out of the plot - a high or a low that IS the latest point, at the plot's edge, or one the line
	 * comes down (or up) into from days carried from an older reading beyond every reading in the range - it slides
	 * sideways instead, to the nearest place (the left side first at equal distance) where the same steps find it a
	 * clear row inside the plot.
	 */
	private List<Figure> placeFigures(final int w, final int h)
	{
		int hi = -1;
		int lo = -1;
		int readings = 0;
		for (int i = 0; i < days.size(); i++)
		{
			final BankHistoryMath.Day d = days.get(i);
			if (d.carried())
			{
				continue;
			}
			readings++;
			if (hi < 0 || value(d) > value(days.get(hi)))
			{
				hi = i;
			}
			if (lo < 0 || value(d) < value(days.get(lo)))
			{
				lo = i;
			}
		}
		if (readings < 2 || value(days.get(hi)) == value(days.get(lo)))
		{
			return Collections.emptyList();
		}
		final FontMetrics fm = getFontMetrics(FIGURE_FONT);
		final List<Figure> out = new ArrayList<>(2);
		out.add(place(hi, true, fm, w, h));
		out.add(place(lo, false, fm, w, h));
		return out;
	}

	private Figure place(final int i, final boolean high, final FontMetrics fm, final int w, final int h)
	{
		final String text = AmountMask.amount(hideAmounts, MovementMath.formatGp(value(days.get(i))));
		final int width = fm.stringWidth(text);
		// The ink's height above the baseline, as the face draws this text (the figures have no descenders).
		final int cap = -FIGURE_FONT.createGlyphVector(fm.getFontRenderContext(), text).getPixelBounds(null, 0, 0).y;
		final int maxLeft = Math.max(FIGURE_MARGIN, w - FIGURE_MARGIN - width);
		final int centred = (int) Math.max(FIGURE_MARGIN, Math.min(maxLeft, Math.round(xs[i] - width / 2.0)));
		int baseline = settle(i, high, centred, width, cap, h);
		if (baseline != NOWHERE)
		{
			return new Figure(i, text, centred, baseline);
		}
		for (int d = 1; centred - d >= FIGURE_MARGIN || centred + d <= maxLeft; d++)
		{
			for (int side = -1; side <= 1; side += 2)
			{
				final int left = centred + side * d;
				if (left < FIGURE_MARGIN || left > maxLeft)
				{
					continue;
				}
				baseline = settle(i, high, left, width, cap, h);
				if (baseline != NOWHERE)
				{
					return new Figure(i, text, left, baseline);
				}
			}
		}
		// Nowhere clear in the whole plot (it cannot happen at the sidebar's sizes): the first row, centred.
		return new Figure(i, text, centred, high ? (int) Math.round(ys[i] - FIGURE_CLEAR)
			: (int) Math.round(ys[i] + FIGURE_CLEAR) + cap);
	}

	/**
	 * The baseline of the first row, stepping away from point {@code i} (the high up, the low down) from
	 * {@link #FIGURE_CLEAR} px, at which the figure's ink {@code [left, left + width) x [baseline - cap, baseline)} stays
	 * inside the plot and clear of the latest point's halo and of the line; {@link #NOWHERE} when it leaves the plot first.
	 */
	private int settle(final int i, final boolean high, final int left, final int width, final int cap, final int h)
	{
		for (int clear = FIGURE_CLEAR; ; clear++)
		{
			final int b = high ? (int) Math.round(ys[i] - clear) : (int) Math.round(ys[i] + clear) + cap;
			if (b - cap < 0 || b > h)
			{
				return NOWHERE;
			}
			if ((latest < 0 || clearOfHalo(left, width, b - cap, b)) && clearOfLine(left, width, b - cap, b))
			{
				return b;
			}
		}
	}

	/** Whether the box {@code [left, left + width) x [top, bottom)} keeps {@link #HALO_AIR} px from the halo's box. */
	private boolean clearOfHalo(final int left, final int width, final int top, final int bottom)
	{
		final double reach = HALO_RADIUS + HALO_AIR;
		return left + width <= xs[latest] - reach || left >= xs[latest] + reach
			|| bottom <= ys[latest] - reach || top >= ys[latest] + reach;
	}

	/**
	 * Whether every pixel of the box {@code [left, left + width) x [top, bottom)} - the rectangle through its pixels'
	 * centres - keeps {@link #LINE_AIR} px from every segment of the line.
	 */
	private boolean clearOfLine(final int left, final int width, final int top, final int bottom)
	{
		final double x0 = left + 0.5;
		final double x1 = left + width - 0.5;
		final double y0 = top + 0.5;
		final double y1 = bottom - 0.5;
		for (int s = 0; s + 1 < xs.length; s++)
		{
			if (xs[s + 1] < x0 - LINE_AIR || xs[s] > x1 + LINE_AIR)
			{
				continue;
			}
			if (segmentToBox(xs[s], ys[s], xs[s + 1], ys[s + 1], x0, y0, x1, y1) < LINE_AIR)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The distance from segment {@code (ax, ay) - (bx, by)} to the rectangle {@code [x0, x1] x [y0, y1]}: 0 when they
	 * meet, else the nearest of the segment's ends to the rectangle and of the rectangle's corners to the segment (two
	 * convex shapes apart are nearest at a corner of one of them).
	 */
	private static double segmentToBox(final double ax, final double ay, final double bx, final double by,
		final double x0, final double y0, final double x1, final double y1)
	{
		if (inside(ax, ay, x0, y0, x1, y1) || inside(bx, by, x0, y0, x1, y1)
			|| Line2D.linesIntersect(ax, ay, bx, by, x0, y0, x1, y0) || Line2D.linesIntersect(ax, ay, bx, by, x1, y0, x1, y1)
			|| Line2D.linesIntersect(ax, ay, bx, by, x1, y1, x0, y1) || Line2D.linesIntersect(ax, ay, bx, by, x0, y1, x0, y0))
		{
			return 0;
		}
		double d = Math.min(pointToBox(ax, ay, x0, y0, x1, y1), pointToBox(bx, by, x0, y0, x1, y1));
		d = Math.min(d, Line2D.ptSegDist(ax, ay, bx, by, x0, y0));
		d = Math.min(d, Line2D.ptSegDist(ax, ay, bx, by, x1, y0));
		d = Math.min(d, Line2D.ptSegDist(ax, ay, bx, by, x0, y1));
		return Math.min(d, Line2D.ptSegDist(ax, ay, bx, by, x1, y1));
	}

	private static boolean inside(final double x, final double y, final double x0, final double y0, final double x1,
		final double y1)
	{
		return x >= x0 && x <= x1 && y >= y0 && y <= y1;
	}

	private static double pointToBox(final double x, final double y, final double x0, final double y0, final double x1,
		final double y1)
	{
		final double dx = Math.max(0, Math.max(x0 - x, x - x1));
		final double dy = Math.max(0, Math.max(y0 - y, y - y1));
		return Math.hypot(dx, dy);
	}

	@Override
	protected void paintComponent(final Graphics g)
	{
		final int n = days.size();
		if (n == 0)
		{
			return;
		}
		final List<Figure> marks = figures();
		final Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			final int h = getHeight();
			final boolean line = drawLine && n > 1;
			if (line)
			{
				paintFill(g2, h);
			}
			if (hover >= 0 && hover < n)
			{
				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.setStroke(GUIDE);
				g2.draw(new Line2D.Double(xs[hover], 0, xs[hover], h));
			}
			if (line)
			{
				final Path2D.Double path = new Path2D.Double();
				final Path2D.Double steps = new Path2D.Double();
				path.moveTo(xs[0], ys[0]);
				for (int i = 1; i < n; i++)
				{
					if (stepInto[i])
					{
						steps.moveTo(xs[i - 1], ys[i - 1]);
						steps.lineTo(xs[i], ys[i]);
						path.moveTo(xs[i], ys[i]);
					}
					else
					{
						path.lineTo(xs[i], ys[i]);
					}
				}
				g2.setColor(this.line);
				g2.setStroke(LINE);
				g2.draw(path);
				g2.setColor(ColorScheme.MEDIUM_GRAY_COLOR);
				g2.setStroke(GUIDE);
				g2.draw(steps);
			}
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			for (Figure f : marks)
			{
				if (f.index != latest)
				{
					g2.fill(circle(xs[f.index], ys[f.index], MARK_RADIUS));
				}
			}
			g2.setColor(ColorScheme.BRAND_ORANGE);
			for (int i = 1; i < n; i++)
			{
				if (stepInto[i])
				{
					g2.fill(circle(xs[i], ys[i], TRANSFER_RADIUS));
				}
			}
			if (latest >= 0)
			{
				g2.setColor(withAlpha(this.line, HALO_ALPHA));
				g2.fill(circle(xs[latest], ys[latest], HALO_RADIUS));
				g2.setColor(this.line);
				g2.fill(circle(xs[latest], ys[latest], DOT_RADIUS));
			}
			if (!marks.isEmpty())
			{
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				g2.setFont(FIGURE_FONT);
				g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
				for (Figure f : marks)
				{
					g2.drawString(f.text, f.left, f.baseline);
				}
			}
			if (hover >= 0 && hover < n)
			{
				final Ellipse2D ring = circle(xs[hover], ys[hover], RING_RADIUS);
				g2.setColor(ColorScheme.DARKER_GRAY_COLOR);
				g2.fill(ring);
				g2.setColor(this.line);
				g2.setStroke(RING);
				g2.draw(ring);
			}
		}
		finally
		{
			g2.dispose();
		}
	}

	/** The area under the line down to the plot's bottom, its tint fading from the line's highest point to nothing. */
	private void paintFill(final Graphics2D g2, final int h)
	{
		final int n = days.size();
		final Path2D.Double area = new Path2D.Double();
		area.moveTo(xs[0], ys[0]);
		double top = ys[0];
		for (int i = 1; i < n; i++)
		{
			area.lineTo(xs[i], ys[i]);
			top = Math.min(top, ys[i]);
		}
		area.lineTo(xs[n - 1], h);
		area.lineTo(xs[0], h);
		area.closePath();
		// A fall's red reads muddy at the rise's alpha; a colour the reader chose for the whole chart has no direction.
		final int alpha = fixed == null && sign < 0 ? FILL_ALPHA_FALL : FILL_ALPHA;
		g2.setPaint(new GradientPaint(0f, (float) top, withAlpha(line, alpha), 0f, (float) h, withAlpha(line, 0)));
		g2.fill(area);
	}

	private static Color withAlpha(final Color c, final int alpha)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}

	private static Ellipse2D circle(final double x, final double y, final double r)
	{
		return new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
	}
}
