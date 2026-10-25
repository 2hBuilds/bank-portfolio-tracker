package com.bankpricemovement;

import java.util.Locale;
import javax.annotation.Nullable;

/**
 * How big the gp change and the percentage change are drawn on an Items row or a Net Worth History day row (release 1.2.1): the
 * four steps of the two size bars in a tab's options menu, one value for the gp figures and one for the percentage, each stored
 * on its own key ({@code gpChangeSize}, {@code pctChangeSize}; the History tab's twins {@code historyGpChangeSize},
 * {@code historyPctChangeSize}). {@link #NORMAL} is what every release before 1.2.1 drew - a 10 px gp figure
 * and a 15 px bold percentage - so a player who never touches the bars sees the rows as they were.
 *
 * <p>{@link #toString()} answers the LABEL, which is what a bar cell prints.
 */
public enum ChangeSize
{
	SMALL("Small", 8, 12, 11),
	NORMAL("Normal", 10, 15, 14),
	LARGE("Large", 12, 17, 16),
	LARGEST("Largest", 13, 18, 17);

	private final String label;
	private final int gpPx;
	private final int pctPx;
	private final int historyPctPx;

	ChangeSize(final String label, final int gpPx, final int pctPx, final int historyPctPx)
	{
		this.label = label;
		this.gpPx = gpPx;
		this.pctPx = pctPx;
		this.historyPctPx = historyPctPx;
	}

	/** The size, in px, a row draws a gp figure in at this step. */
	public int gpPx()
	{
		return gpPx;
	}

	/** The size, in px, an Items row draws the (bold) percentage in at this step. */
	public int pctPx()
	{
		return pctPx;
	}

	/**
	 * The size, in px, a Net Worth History day row draws its (bold) percentage in at this step (release 1.2.1 part 2): 11, 14 -
	 * what the tab always drew - 16 and 17. Its gp figure takes {@link #gpPx()}, the same 8 / 10 / 12 / 13 as an Items row's.
	 */
	public int historyPctPx()
	{
		return historyPctPx;
	}

	@Override
	public String toString()
	{
		return label;
	}

	/** The step a word names (its label or constant, any case), or null for anything else - the dev bridge's reading. */
	@Nullable
	public static ChangeSize parse(@Nullable final String word)
	{
		if (word == null)
		{
			return null;
		}
		final String w = word.trim().toLowerCase(Locale.ROOT);
		for (final ChangeSize size : values())
		{
			if (size.label.toLowerCase(Locale.ROOT).equals(w))
			{
				return size;
			}
		}
		return null;
	}
}
