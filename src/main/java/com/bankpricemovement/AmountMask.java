package com.bankpricemovement;

import javax.annotation.Nullable;

/**
 * The one place that decides what a hidden amount reads (1.1.0 part E, "Hide amounts"): every gp amount and every item
 * quantity the sidebar draws goes through here when the eye is shut, and comes out as a FIXED mask that never depends on
 * the number - a one-digit amount and a twelve-digit one read the same, so a mask shows nothing about its figure, not
 * even its length.
 *
 * <p>Three masks, by what they stand for: {@link #AMOUNT} for a total, a stack value or any exact gp figure,
 * {@link #SHORT} for a gp change and for a quantity. A text that is not a number - the empty text that stands for "nothing
 * moved" and {@link MovementMath#DASH}, which stands for "no figure" - is left as it is: the mask goes where a number would
 * have printed and nowhere else, so shutting the eye changes what is written and never what the layout is made of.
 *
 * <p>What this class never touches: percentages, item names, dates, the price band, the counts of rows - they say nothing
 * about how much the player has. The drawers ask here and keep no rule of their own: with the amounts SHOWN every method
 * answers the text it was handed, so the sidebar is byte for byte what it was before the eye existed.
 */
final class AmountMask
{
	/** A bullet: a typed dot, because the drawn glyphs a bitmap face lacks would need an image for each size. */
	private static final String DOT = "\u2022";

	/** The mask for a total, a stack value or an exact gp amount: five dots. */
	static final String AMOUNT = DOT + DOT + DOT + DOT + DOT;
	/** The mask for a gp change and for an item quantity: three dots. */
	static final String SHORT = DOT + DOT + DOT;

	private AmountMask()
	{
	}

	/** Whether {@code text} is a figure that would say something: not empty and not the dash. */
	private static boolean isFigure(@Nullable final String text)
	{
		return text != null && !text.isEmpty() && !MovementMath.DASH.equals(text);
	}

	/**
	 * A total or a stack value, as printed: {@link #AMOUNT} while {@code hide} is on and there is a figure, else the text
	 * itself.
	 */
	static String amount(final boolean hide, final String text)
	{
		return hide && isFigure(text) ? AMOUNT : text;
	}

	/** A gp change, as printed: {@link #SHORT} while {@code hide} is on and there is a figure, else the text itself. */
	static String change(final boolean hide, final String text)
	{
		return hide && isFigure(text) ? SHORT : text;
	}

	/**
	 * An item quantity, as printed, or a whole text that carries one ("1 x 39.7m"): {@link #SHORT} while {@code hide} is
	 * on, else the text itself. Unlike the other two it masks the dash as well - the line it is used on says "-" for a row
	 * with no price, and its quantity is still the player's.
	 */
	static String quantity(final boolean hide, final String text)
	{
		return hide ? SHORT : text;
	}
}
