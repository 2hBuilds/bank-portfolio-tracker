package com.bankpricemovement;

import java.util.Objects;

/**
 * Everything the user has chosen about what the sidebar shows: the gp band, the sort and its direction, and
 * the window (contract C7). One immutable value, so the panel, the config and the dev bridge cannot drift
 * apart - a change is a NEW filter handed to the service, never a field edited in place.
 *
 * <p>The band filters the UNIT price, not the holding value (design D4 - the user's "gp min and max" against
 * the price of one item), and {@code gpMax == 0} means "no upper bound" rather than "nothing over 0 gp", which
 * is what an empty Max field writes.
 *
 * <p>The five fields are the five FILTER keys of C39, so {@code Prefs.load()} / {@code Prefs.save()} is a
 * straight copy in each direction. The config also carries addendum O2's three hero show/hide switches, which
 * are deliberately NOT here: a filter says what the list CONTAINS, and a switch that only decides what is
 * painted must not make the service recompute anything - those ride on {@code HeroVisibility} through
 * {@code Prefs.loadHero()} / {@code Prefs.saveHero()}.
 */
public final class RowFilter
{
	/**
	 * A fresh install: no band, sorted by the biggest percentage move first, over the last day
	 * ({@link MovementWindow#DEFAULT}; design D5 as rewritten by addendum K4 - the guide table is republished
	 * about once a day, so a day is the shortest window the series can express).
	 */
	public static final RowFilter DEFAULT = new RowFilter(0L, 0L, SortMode.PERCENT_MOVE, true, MovementWindow.DEFAULT);

	private final long gpMin;
	private final long gpMax;
	private final SortMode sort;
	private final boolean descending;
	private final MovementWindow window;

	/**
	 * @param gpMin      lowest unit price to show; negatives clamp to 0
	 * @param gpMax      highest unit price to show, or 0 for no upper bound; negatives clamp to 0
	 * @param sort       which column orders the list; null becomes {@link SortMode#PERCENT_MOVE}
	 * @param descending true for biggest first
	 * @param window     the baseline the move is measured against; null becomes {@link MovementWindow#DEFAULT}
	 */
	public RowFilter(final long gpMin, final long gpMax, final SortMode sort, final boolean descending,
		final MovementWindow window)
	{
		this.gpMin = Math.max(0L, gpMin);
		this.gpMax = Math.max(0L, gpMax);
		this.sort = sort == null ? SortMode.PERCENT_MOVE : sort;
		this.descending = descending;
		this.window = window == null ? MovementWindow.DEFAULT : window;
	}

	public long gpMin()
	{
		return gpMin;
	}

	/**
	 * The upper bound of the band, or 0 for none.
	 */
	public long gpMax()
	{
		return gpMax;
	}

	/**
	 * Never null.
	 */
	public SortMode sort()
	{
		return sort;
	}

	public boolean descending()
	{
		return descending;
	}

	/**
	 * Never null.
	 */
	public MovementWindow window()
	{
		return window;
	}

	public RowFilter withGpMin(final long newGpMin)
	{
		return new RowFilter(newGpMin, gpMax, sort, descending, window);
	}

	public RowFilter withGpMax(final long newGpMax)
	{
		return new RowFilter(gpMin, newGpMax, sort, descending, window);
	}

	public RowFilter withSort(final SortMode newSort)
	{
		return new RowFilter(gpMin, gpMax, newSort, descending, window);
	}

	public RowFilter withDescending(final boolean newDescending)
	{
		return new RowFilter(gpMin, gpMax, sort, newDescending, window);
	}

	public RowFilter withWindow(final MovementWindow newWindow)
	{
		return new RowFilter(gpMin, gpMax, sort, descending, newWindow);
	}

	/**
	 * Value equality: the service compares the incoming filter with the current one to decide whether a
	 * recompute or a fetch is needed at all, so this has to be a value, not an identity.
	 */
	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof RowFilter))
		{
			return false;
		}

		final RowFilter other = (RowFilter) o;
		return gpMin == other.gpMin
			&& gpMax == other.gpMax
			&& descending == other.descending
			&& sort == other.sort
			&& window == other.window;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(gpMin, gpMax, sort, descending, window);
	}

	@Override
	public String toString()
	{
		return "RowFilter{gpMin=" + gpMin
			+ ", gpMax=" + gpMax
			+ ", sort=" + sort.name()
			+ ", descending=" + descending
			+ ", window=" + window.name() + '}';
	}
}
