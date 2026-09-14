package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Locale;

/**
 * How far back a bank row's movement is measured, in the five spans the Grand Exchange item pages themselves
 * show - "today", 1 month, 3 months, 6 months - rewritten to 1d / 7d / 30d / 90d / 180d (contract K4, restated
 * in CALENDAR DAYS by addendum L, line L9; {@code docs/bank-price-movement-addendum-L-2026-09-08.md}).
 *
 * <p><b>Why these five and not the old 1h / 24h / 7d.</b> Movement is not the wiki's real-time TRADE series; it
 * is the Jagex GUIDE price, the number the in-game GE, the GE web site and RuneLite itself all show. That is a
 * step function that moves ONCE A DAY, at a variable UTC hour (L-A: the rollover was observed between 00:47 and
 * 02:06 UTC), so an hourly window would measure nothing at all: both ends would land on the same step and every
 * row would read 0 %. A day is the shortest window the series can express, and the longer four are the ones the
 * GE site itself offers.
 *
 * <p><b>Why a window is DAYS and not seconds now</b> (L9, evidence L-C). Addendum K asked for "the revision at
 * or before now minus 86,400 seconds". Six analysts measured that against live data: the wiki's price bot writes
 * day D's table at a random hour of day D (02:11-22:20 UTC, median about 12:00), so subtracting a fixed span
 * from the wall clock lands on the intended Jagex day only 50-56 % of clock hours, changing the printed percent
 * on 40-60 % of the user's rows and flipping its sign on 3-5 %. The GE site's own arithmetic is
 * {@code (daily[D] - daily[D-N]) / daily[D-N]} over CALENDAR days (L-B), so a window is now a count of days
 * subtracted from an anchor DATE that is derived from the data (L3), and {@code seconds()} and
 * {@code targetFor(long)} are gone with the clock arithmetic that produced the coin flip.
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
	 * How many calendar days back the baseline sits (L9). The GE site's 30d / 90d / 180d figures are exactly
	 * {@code daily[D] - daily[D-N]} for these N, so a "30d" here is 30 dates back, never 2,592,000 seconds back.
	 */
	public int days()
	{
		return days;
	}

	/**
	 * The UTC calendar date whose guide table is this window's "then" side: {@code anchorDay.minusDays(days)}
	 * (L5, L9).
	 *
	 * <p>The anchor is DERIVED from the data, never read off the wall clock (L3): RuneLite's price table can
	 * already hold the next Jagex day while the wiki's newest revision still holds the previous one, for roughly
	 * half of every day (L-D). {@code PriceService} settles which day D is by comparing RuneLite's prices against
	 * the newest table and hands the answer here.
	 *
	 * <p>{@link LocalDate#minusDays} is calendar arithmetic, so a window that spans a leap day or the end of a
	 * month lands on the date a reader would count to - which subtracting {@code days * 86400} seconds does not
	 * guarantee once a caller starts from an instant rather than a date.
	 *
	 * @param anchorDay the derived anchor day D; null (no anchor settled yet) answers null, so a caller with no
	 *                  anchor gets no target, picks no baseline and shows "-" rather than guessing a date
	 * @return the target date, or null when {@code anchorDay} is null
	 */
	public LocalDate targetDate(final LocalDate anchorDay)
	{
		return anchorDay == null ? null : anchorDay.minusDays(days);
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
