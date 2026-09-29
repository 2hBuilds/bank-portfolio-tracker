package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;

/**
 * The History view's own render harness (addendum AU, contract section 10 "Pictures"): the view ALONE, chart block and
 * list, on the sidebar's dark ground, at a chosen width. The whole sidebar in History is {@link LookRenderer}'s
 * ({@link LookRenderer#renderHistory}), drawn over THIS class's fixture - there is one History fixture, and it is here.
 *
 * <p>The fixture is 40 days, 18 Aug to 26 Sep 2026, of a total around 736m that rises into mid-September and falls
 * after it, with three gaps: 30 Aug, 8 Sep, and the two days 21-22 Sep. Every figure is deterministic, so the
 * pictures are the same whenever they are drawn. "Today" is 26 Sep at local noon in {@link #ZONE}.
 *
 * <p>{@code main(dir)} writes the five pictures of the handoff: {@link #FILE_213}, {@link #FILE_230},
 * {@link #FILE_EMPTY}, {@link #FILE_ONE} and {@link #FILE_HOVER}.
 */
public final class HistoryViewRenderer
{
	static final ZoneId ZONE = ZoneId.of("America/Toronto");
	static final LocalDate TODAY = LocalDate.of(2026, 9, 26);
	static final int DAYS = 40;
	/** The days with no reading: 30 Aug, 8 Sep, and 21-22 Sep (the two-day gap). */
	static final Set<LocalDate> GAPS = new HashSet<>(Arrays.asList(LocalDate.of(2026, 8, 30),
		LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 22)));

	static final String FILE_213 = "history-view-213-2026-09-28.png";
	static final String FILE_230 = "history-view-230-2026-09-28.png";
	static final String FILE_EMPTY = "history-view-empty-2026-09-28.png";
	static final String FILE_ONE = "history-view-one-2026-09-28.png";
	static final String FILE_HOVER = "history-view-hover-2026-09-28.png";
	/** The fixture's last reading, today's: mock 5's own total, which the whole-sidebar pictures' card reads too. */
	static final long LAST_GP = 736_412_683L;
	/** The day the hover picture points at: mid-range, as in mock 5 (09 Sep). */
	static final LocalDate HOVER_DAY = LocalDate.of(2026, 9, 9);

	/** The shape of the total over the 40 days, in millions, at key days - mock 5's own path. */
	private static final double[][] KEYS = {{0, 701}, {4, 692}, {9, 713}, {14, 724}, {19, 741}, {24, 752},
		{28, 758.4}, {32, 748}, {35, 733}, {37, 729}, {39, 736.4}};

	private HistoryViewRenderer()
	{
	}

	/** Local noon of {@code day} in {@link #ZONE}: how every stamp here is built (amendment 9.1). */
	static long noon(final LocalDate day)
	{
		return day.atTime(12, 0).atZone(ZONE).toInstant().toEpochMilli();
	}

	/** A reading of {@code day} whose bank-tradeable cell is {@code gp}. */
	static BankHistoryPoint point(final LocalDate day, final long gp)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		card[BankHistoryPoint.BANK_TRADEABLE] = gp;
		return new BankHistoryPoint(day, noon(day), noon(day), card, null);
	}

	/** The 40-day fixture described above. */
	static BankHistorySeries fixture()
	{
		final List<BankHistoryPoint> points = new ArrayList<>();
		for (int i = 0; i < DAYS; i++)
		{
			final LocalDate day = TODAY.minusDays(DAYS - 1 - i);
			if (GAPS.contains(day))
			{
				continue;
			}
			double base = KEYS[KEYS.length - 1][1];
			for (int k = 0; k + 1 < KEYS.length; k++)
			{
				if (i >= KEYS[k][0] && i <= KEYS[k + 1][0])
				{
					final double t = (i - KEYS[k][0]) / (KEYS[k + 1][0] - KEYS[k][0]);
					final double eased = (1 - Math.cos(t * Math.PI)) / 2;
					base = KEYS[k][1] + (KEYS[k + 1][1] - KEYS[k][1]) * eased;
					break;
				}
			}
			// A fixed wobble, so neighbouring days differ both ways, and odd digits all the way down.
			final double wobble = ((i * 37) % 11 - 5) * 0.45;
			final long gp = i == DAYS - 1 ? LAST_GP : Math.round((base + wobble) * 1_000_000d) + (i * 7_919L) % 999_983L;
			points.add(point(day, gp));
		}
		return BankHistorySeries.of(points);
	}

	/** Day one: the one reading of {@link #TODAY}, worth {@link #LAST_GP}. */
	static BankHistorySeries oneReading()
	{
		return BankHistorySeries.of(Arrays.asList(point(TODAY, LAST_GP)));
	}

	/** A view on the fixture's clock, shown {@code series} at {@code range}. EDT. */
	static BankHistoryView view(final BankHistorySeries series, final BankHistoryRange range)
	{
		final BankHistoryView view = new BankHistoryView(() -> noon(TODAY), ZONE);
		view.setRange(range);
		view.show(series, ViewOptions.DEFAULT);
		return view;
	}

	/** EDT. Sizes {@code view} to {@code width} and its own preferred height, and lays the tree out. */
	static void layOut(final JComponent view, final int width)
	{
		view.setSize(width, 10);
		layoutTree(view);
		view.setSize(width, view.getPreferredSize().height);
		layoutTree(view);
	}

	/** EDT. The view laid out at {@code width}, painted on the sidebar's ground. */
	static BufferedImage paint(final JComponent view, final int width)
	{
		layOut(view, width);
		final int height = view.getHeight();
		final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, width, height);
			view.printAll(g);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/** {@link LookRenderer#layoutTree}'s recipe: doLayout top-down, with no peer. */
	static void layoutTree(final Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	/** The view's chart, found in its tree. */
	static BankHistoryChart chart(final Container c)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof BankHistoryChart)
			{
				return (BankHistoryChart) child;
			}
			if (child instanceof Container)
			{
				final BankHistoryChart found = chart((Container) child);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	/** Moves a (synthetic) pointer onto the chart at {@code x}, in the chart's own coordinates. */
	static void hover(final BankHistoryChart chart, final int x)
	{
		chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, x,
			chart.getHeight() / 2, 0, false));
	}

	/** The pointer leaves the chart. */
	static void leave(final BankHistoryChart chart)
	{
		chart.dispatchEvent(new MouseEvent(chart, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0,
			false));
	}

	/** Index of {@code day} among the chart's drawn days, or -1. */
	static int indexOf(final BankHistoryChart chart, final LocalDate day)
	{
		final List<BankHistoryMath.Day> days = chart.days();
		for (int i = 0; i < days.size(); i++)
		{
			if (days.get(i).day().equals(day))
			{
				return i;
			}
		}
		return -1;
	}

	/** The five pictures, drawn on the EDT. */
	static List<BufferedImage> renderAll() throws Exception
	{
		final AtomicReference<List<BufferedImage>> out = new AtomicReference<>();
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				final List<BufferedImage> images = new ArrayList<>();
				images.add(paint(view(fixture(), BankHistoryRange.D30), 213));
				images.add(paint(view(fixture(), BankHistoryRange.D30), 230));
				images.add(paint(view(BankHistorySeries.EMPTY, BankHistoryRange.D7), 213));
				images.add(paint(view(oneReading(),
					BankHistoryRange.D7), 213));
				final BankHistoryView hovered = view(fixture(), BankHistoryRange.D30);
				layOut(hovered, 213);
				final BankHistoryChart chart = chart(hovered);
				hover(chart, (int) Math.round(BankHistoryChart.xAt(indexOf(chart, HOVER_DAY), chart.days().size(),
					chart.getWidth())));
				images.add(paint(hovered, 213));
				out.set(images);
			}
			catch (Throwable t)
			{
				failure.set(t);
			}
		});
		if (failure.get() != null)
		{
			throw new IllegalStateException(failure.get());
		}
		return out.get();
	}

	/** Writes the five pictures into {@code dir}; answers the files in the order of {@link #names()}. */
	static List<File> write(final File dir) throws Exception
	{
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("could not create " + dir);
		}
		final List<BufferedImage> images = renderAll();
		final List<File> files = new ArrayList<>();
		final List<String> names = names();
		for (int i = 0; i < names.size(); i++)
		{
			final File f = new File(dir, names.get(i));
			if (!ImageIO.write(images.get(i), "png", f))
			{
				throw new IOException("no PNG writer for " + f);
			}
			files.add(f);
		}
		return files;
	}

	static List<String> names()
	{
		return Arrays.asList(FILE_213, FILE_230, FILE_EMPTY, FILE_ONE, FILE_HOVER);
	}

	/** Writes the five pictures into {@code args[0]}, or {@code build/history-view} when no directory is given. */
	public static void main(final String[] args) throws Exception
	{
		for (File f : write(new File(args.length > 0 ? args[0] : "build/history-view")))
		{
			System.out.println(f.getAbsolutePath());
		}
	}
}
