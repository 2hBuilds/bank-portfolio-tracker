package com.bankpricemovement;

import javax.annotation.Nullable;

/**
 * The words a graded figure can carry (contract 1.2.0, line L3), in ONE place: the sidebar prints one of them in the
 * free slot under a soft row's percentage (L5), and the dev bridge echoes it as {@code state.rows[].word}. A SOLID
 * figure carries none ({@link #SOLID}, the empty string): a row with no word is solid.
 *
 * <p>The user's final decision (2026-10-07): a figure is SOFT only when one of THREE facts holds, and it carries the word
 * of the FIRST of them, in this order (applied by {@link GradeMath#figure}):
 * <ol>
 * <li>{@link #lastTraded(long)} - nothing traded in the last 24 hours, so the figure is yesterday's move, and the word
 * says how long it has been since the item last traded ("last 1d ago"); {@link #YDAY} stands in only when no
 * last-trade time is known (the fallback; always first);</li>
 * <li>{@link #volume(long)} - the compared day's thinner side traded under ten units ("low vol 3");</li>
 * <li>{@link #spread(long)} - the current buy and sell prints, both from the last 24 hours, sit more than ten percent of
 * their middle apart, and the word says how far ("spread 55%").</li>
 * </ol>
 * A figure reported on one side only is no weaker for it: it is priced at that side and is solid unless one of the facts
 * above holds. A NONE row says {@link #NO_TRADES}, a label and not a warning. The words are the user's picks (2026-10-07,
 * and the two last wordings of 2026-10-08: "spread 55%" and "low vol 7"), and this class is where they change.
 */
public final class GradeWords
{
	/** A SOLID figure's word: none. */
	public static final String SOLID = "";

	/**
	 * The plain word for a spread with no number to say - the rare figure whose price fell outside the anchor's range, which
	 * has no current pair to measure. A spread with a number says {@link #spread(long)}.
	 */
	public static final String SPREAD = "spread";

	/**
	 * Nothing traded in the last 24 hours and no last-trade time is known: the figure is yesterday's move, always SOFT. With
	 * a time known the fallback says {@link #lastTraded(long)} instead.
	 */
	public static final String YDAY = "yday";

	/** A NONE row: no trade in the last 24 hours and no fallback pair - the dash. */
	public static final String NO_TRADES = "no trades";

	/** The longest age {@link #lastTraded} prints, in days: two digits, so "last 99d ago" is the widest it gets. */
	private static final long LAST_TRADED_MAX_DAYS = 99L;

	private static final long SECONDS_PER_HOUR = 60L * 60L;

	private static final long SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR;

	private GradeWords()
	{
	}

	/**
	 * "last Nh ago" or "last Nd ago" - how long since the item last traded, for the fallback row whose figure is
	 * yesterday's move (the user, 2026-10-07: "last 5h ago" in place of "yday"). Whole hours below 24 h ("last 5h ago"),
	 * whole days from 24 h ("last 1d ago", "last 2d ago"), each rounded DOWN as an age is. Under one hour it still says
	 * "last 1h ago" - the word never says 0 - and a negative age (a clock a little behind the print) reads as 0. Days are
	 * capped at {@value #LAST_TRADED_MAX_DAYS} so the word stays inside the slot; a fallback row's last trade is a day or
	 * two old, so the cap is a safety net and never reached.
	 *
	 * <p>The word is wider than the slot under the percentage at 11 px from "last 10h ago" up (59 px against 54 in the face
	 * this plugin draws in, so "last 23h ago" is drawn at 9 px); "last 9h ago" and "last 2d ago" fit at 11.
	 *
	 * @param secondsAgo the age of the item's newest trade, in seconds
	 */
	public static String lastTraded(final long secondsAgo)
	{
		final long age = Math.max(0L, secondsAgo);
		if (age < SECONDS_PER_DAY)
		{
			return "last " + Math.max(1L, age / SECONDS_PER_HOUR) + "h ago";
		}
		return "last " + Math.min(LAST_TRADED_MAX_DAYS, age / SECONDS_PER_DAY) + "d ago";
	}

	/**
	 * "low vol N" - the thinner side's unit count on the compared day ("low vol 3"). Negative reads as 0. {@link GradeMath}
	 * says the word only for a count under ten units (a thin day), so the count is one digit and the word fits the slot under
	 * the percentage at 11 px (44 px against 54).
	 *
	 * @param count units the thinner side traded on the compared day
	 */
	public static String volume(final long count)
	{
		return "low vol " + Math.max(0L, count);
	}

	/**
	 * "spread N%" - the current buy and sell prints are this whole percentage of their middle apart ("spread 55%"). Negative
	 * reads as 0. Both prints are inside the anchor's range, so the percentage is at most 160 and the word is at most
	 * "spread 160%". Neither fits the slot under the percentage at 11 px; the row draws them at 9 px, where "spread 55%"
	 * measures 49 and "spread 100%" - the widest, any three-digit percentage being as wide - exactly the slot's 54.
	 *
	 * @param pct the pair's spread as a whole percentage of its middle ({@code GradedMove#spreadPercent})
	 */
	public static String spread(final long pct)
	{
		return SPREAD + " " + Math.max(0L, pct) + "%";
	}

	/** Whether a word is the spread word, with its number ("spread 55%") or without ({@link #SPREAD}). */
	public static boolean isSpread(@Nullable final String word)
	{
		return word != null && word.startsWith(SPREAD);
	}
}
