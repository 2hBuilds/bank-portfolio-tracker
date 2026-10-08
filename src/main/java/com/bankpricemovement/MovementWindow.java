package com.bankpricemovement;

import java.util.Locale;

/**
 * How far back a bank row's movement is measured, in the five spans the Grand Exchange item pages themselves
 * show - "today", 1 month, 3 months, 6 months - rewritten to 1d / 7d / 30d / 90d / 180d (contract K4, restated
 * in CALENDAR DAYS by addendum L, line L9, and as a span of TIME again by contract 1.1.2, T1;
 * {@code docs/handoff/contract-1.1.2-guide-then-by-time-2026-10-07.md}).
 *
 * <p><b>Why these five and not the old 1h / 24h / 7d.</b> Movement is not the wiki's real-time TRADE series; it
 * is the Jagex GUIDE price, the number the in-game GE, the GE web site and RuneLite itself all show. That is a
 * step function - stepped once a day until 29 Sep 2026 and about eight times a day since - so an hourly window
 * would mostly land both ends on the same step and read 0 %. A day is the shortest window worth showing, and the
 * longer four are the ones the GE site itself offers.
 *
 * <p><b>Why a window is a span of TIME again</b> (contract 1.1.2, T1, superseding addendum L's L9). Addendum L made
 * a window a count of calendar DAYS subtracted from an anchor date, because the wiki's bot saved one table a day at a
 * random hour and "the revision at or before now minus 86,400 seconds" landed on the intended day only half the time
 * (L-C). Since the bot saves eight tables a day the calendar day no longer names one table, and "the newest table of
 * the day before the anchor" turned into a table a couple of hours old (1.1.2's finding: 368 of 499 rows at 0.0 %).
 * The "now" end is now a TIME - the time of the guide table RuneLite's prices match, or the clock while RuneLite holds
 * a table the index has not seen ({@code PriceService}) - and the "then" end is the newest table at least
 * {@code days() x 24 h} older ({@link #targetSeconds}, {@code RevisionRef.pickThen}). Nothing here reads a clock.
 *
 * <p>{@link #toString()} is the LABEL, not the enum name, because RuneLite's config panel renders an enum combo
 * box with {@code toString()} while {@code ConfigManager} stores and reads the constant by {@code name()}
 * ({@code ConfigManager.java:1279-1281} writes {@code ((Enum) object).name()}, {@code :1205-1207} reads it back
 * with {@code Enum.valueOf}). Overriding the display text therefore cannot corrupt a saved setting.
 */
public enum MovementWindow
{
	D1("1d", 1),
	D7("7d", 7),
	D30("30d", 30),
	D90("90d", 90),
	D180("180d", 180);

	/**
	 * The window a fresh profile gets, and the one every legacy stored value collapses onto: it is the GE site's
	 * headline "today" figure and the only span the daily guide table can resolve sharply (K4).
	 */
	public static final MovementWindow DEFAULT = D1;

	/** Seconds in a day: a window of N days reaches back N x this. */
	private static final long DAY_SECONDS = 24L * 60L * 60L;

	private final String label;
	private final int days;

	MovementWindow(final String label, final int days)
	{
		this.label = label;
		this.days = days;
	}

	/**
	 * The chip text the sidebar draws ("1d", "7d", "30d", "90d", "180d").
	 */
	public String label()
	{
		return label;
	}

	/**
	 * How many days back the baseline sits: the "then" table is at least {@code days() x 24 h} older than the "now"
	 * table (contract 1.1.2, T1).
	 */
	public int days()
	{
		return days;
	}

	/**
	 * The latest time, unix seconds, this window's "then" table may carry: {@code nowSeconds - days() x 24 h}
	 * (contract 1.1.2, T1). {@code RevisionRef.pickThen} takes the newest revision at or before it.
	 *
	 * <p>Plain seconds and not calendar arithmetic, because the series no longer steps once a calendar day: a 1d move
	 * is RuneLite's table against the one in force a day earlier, at whatever hour that was. All in UTC instants, so a
	 * reader's zone plays no part (L9).
	 *
	 * @param nowSeconds the "now" table's time, unix seconds; 0 or less (no "now" settled yet) answers 0, so a caller
	 *                   with no "now" gets no target, picks no baseline and shows "-" rather than guessing one
	 * @return the target time, or 0 when {@code nowSeconds} is not positive
	 */
	public long targetSeconds(final long nowSeconds)
	{
		return nowSeconds <= 0L ? 0L : nowSeconds - days * DAY_SECONDS;
	}

	/**
	 * Reads a window out of user text, a dev-bridge verb or a stored config value: the label ("1d", "30d"), the
	 * constant name ("D1", "D30"), or one of the pre-addendum-K spellings, trimmed and case-insensitive.
	 *
	 * <p>The legacy spellings matter because a user who ran the first build has {@code window=H24} written into
	 * their RuneLite profile ({@code ConfigManager} stores an enum by {@code name()}), and {@code H1} / {@code H24}
	 * / {@code 1h} / {@code 24h} no longer name anything. They all collapse onto {@link #D1}: an hour is below
	 * the resolution of the guide series, and 24 h IS the new 1d. Without this, the stored value would fail
	 * {@code Enum.valueOf} and the config panel would NPE on the way in (the flat-config trap this workspace
	 * already hit - CLAUDE.md "Config interfaces must be FLAT"); K9 also unsets an unparseable value at startUp,
	 * so the two guards overlap on purpose.
	 *
	 * @param text anything, including null
	 * @return the window, or null when the text names none (the caller keeps its current window)
	 */
	public static MovementWindow parse(final String text)
	{
		if (text == null)
		{
			return null;
		}

		final String key = text.trim().toLowerCase(Locale.ENGLISH);
		if (key.isEmpty())
		{
			return null;
		}

		for (final MovementWindow window : values())
		{
			if (key.equals(window.label) || key.equals(window.name().toLowerCase(Locale.ENGLISH)))
			{
				return window;
			}
		}

		// Pre-addendum-K windows, in both the spelling the chips used and the spelling ConfigManager stored.
		if (key.equals("1h") || key.equals("h1") || key.equals("24h") || key.equals("h24"))
		{
			return D1;
		}

		return null;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
