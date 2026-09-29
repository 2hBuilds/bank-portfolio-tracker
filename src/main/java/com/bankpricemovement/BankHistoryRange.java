package com.bankpricemovement;

import javax.annotation.Nullable;

/**
 * The History chart's four ranges (addendum AU, plan 7.2 items 6 and 10; phase 0 contract section 3): the last 7,
 * 30 or 90 calendar days, or every day since the first reading.
 *
 * <p>A range covers the calendar days {@code [today - days(), today]} inclusive, and
 * {@code [first reading, today]} for {@link #ALL} (contract amendment 9.5). The chart's own range chips move only
 * the chart - unsaved, never sent to the service - and the card's window chip overrules them through
 * {@link #forWindow(MovementWindow)}.
 */
public enum BankHistoryRange
{
	D7("7d", 7),
	D30("30d", 30),
	D90("90d", 90),
	/** Every day since the first reading; {@link #days()} is 0. */
	ALL("all", 0);

	private final String label;
	private final int days;

	BankHistoryRange(final String label, final int days)
	{
		this.label = label;
		this.days = days;
	}

	/** How many calendar days back the range reaches: 7, 30, 90, or 0 for {@link #ALL}. */
	public int days()
	{
		return days;
	}

	/** The chip text: "7d", "30d", "90d", "all". */
	public String label()
	{
		return label;
	}

	/**
	 * The range the card's window chip selects (plan 7.2 item 6): 1d and 7d both show a week - one day is not a
	 * line - 30d and 90d show themselves, and 180d shows everything, because a history that reaches half a year is
	 * the whole of it for a long while. A null window reads as {@link MovementWindow#DEFAULT}.
	 */
	public static BankHistoryRange forWindow(@Nullable final MovementWindow window)
	{
		final MovementWindow w = window == null ? MovementWindow.DEFAULT : window;
		switch (w)
		{
			case D30:
				return D30;
			case D90:
				return D90;
			case D180:
				return ALL;
			case D1:
			case D7:
			default:
				return D7;
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
