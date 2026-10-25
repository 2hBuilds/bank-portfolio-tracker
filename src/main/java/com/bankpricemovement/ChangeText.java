package com.bankpricemovement;

import java.util.Objects;
import javax.annotation.Nullable;

/**
 * How an Items row draws its two change figures (release 1.2.1, the player's asks): the SIZE of the gp figures, the SIZE of the
 * percentage, and how BRIGHT the gp figures are - the three settings of the List options menu, carried as one immutable value
 * the way {@link ViewOptions} carries the view switches. A row is built from it (a row is built once and never re-read, so a
 * change rebuilds the page from the rows the panel already holds); nothing here reaches the service, because what is drawn
 * changes and no figure does.
 *
 * <p>The brightness is a percentage of the way from the card's own grey to the colour of the percentage beside the figure:
 * 100 is that colour exactly, and the default, {@value #DEFAULT_BRIGHTNESS}, is the colour a gp figure is drawn in. It
 * reads 20 to 100; anything outside reads as the default ({@link #readBrightness}), which is what a stored value nobody
 * can have typed in the menu - a hand-edited profile - is made of.
 */
public final class ChangeText
{
	/** The least brightness the menu accepts: below it the figure is no longer plainly green or red. */
	public static final int MIN_BRIGHTNESS = 20;
	/** The most: the percentage's own colour. */
	public static final int MAX_BRIGHTNESS = 100;
	/** What the gp figures are drawn at unless the player chose otherwise (it was 45 until release 1.2.1). */
	public static final int DEFAULT_BRIGHTNESS = 55;

	/** Both sizes Normal and the brightness at its default: the rows as release 1.2.1 draws them with nothing chosen. */
	public static final ChangeText DEFAULT = new ChangeText(ChangeSize.NORMAL, ChangeSize.NORMAL, DEFAULT_BRIGHTNESS);

	private final ChangeSize gp;
	private final ChangeSize pct;
	private final int brightness;

	private ChangeText(final ChangeSize gp, final ChangeSize pct, final int brightness)
	{
		this.gp = gp;
		this.pct = pct;
		this.brightness = brightness;
	}

	/** The value for these three; a null size reads as Normal and a brightness outside 20 to 100 as the default. */
	public static ChangeText of(@Nullable final ChangeSize gp, @Nullable final ChangeSize pct, final int brightness)
	{
		return new ChangeText(gp == null ? ChangeSize.NORMAL : gp, pct == null ? ChangeSize.NORMAL : pct,
			readBrightness(brightness));
	}

	/** Whether {@code percent} is a brightness the menu takes: a whole number from 20 to 100. */
	public static boolean isBrightness(final int percent)
	{
		return percent >= MIN_BRIGHTNESS && percent <= MAX_BRIGHTNESS;
	}

	/** {@code stored} when it is a brightness, the default when it is not. */
	public static int readBrightness(final int stored)
	{
		return isBrightness(stored) ? stored : DEFAULT_BRIGHTNESS;
	}

	public ChangeSize gp()
	{
		return gp;
	}

	public ChangeSize pct()
	{
		return pct;
	}

	public int brightness()
	{
		return brightness;
	}

	public ChangeText withGp(@Nullable final ChangeSize size)
	{
		return of(size, pct, brightness);
	}

	public ChangeText withPct(@Nullable final ChangeSize size)
	{
		return of(gp, size, brightness);
	}

	public ChangeText withBrightness(final int percent)
	{
		return of(gp, pct, percent);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ChangeText))
		{
			return false;
		}
		final ChangeText that = (ChangeText) o;
		return gp == that.gp && pct == that.pct && brightness == that.brightness;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(gp, pct, brightness);
	}

	@Override
	public String toString()
	{
		return "ChangeText{gp=" + gp.name() + ", pct=" + pct.name() + ", brightness=" + brightness + "}";
	}
}
