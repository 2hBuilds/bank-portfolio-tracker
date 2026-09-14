package com.bankpricemovement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.client.util.QuantityFormatter;

/**
 * Every number the sidebar shows, in one pure place (contract C8-C10): how a bank stack plus two price points
 * become a row, how a row list is filtered and ordered, and how a long becomes the short text a 213 px column
 * has room for.
 *
 * <p>Pure on purpose. The service computes rows on its executor and the panel formats them on the EDT, so
 * nothing here touches the client, Swing, the network or the clock beyond the millis it is handed - which is
 * also what makes the whole of the plugin's arithmetic testable without a client (playbook 7.6, style PURE).
 *
 * <p>The formatting delegates to RuneLite's own {@code QuantityFormatter} rather than inventing a second
 * house style for gp
 * ({@code runelite-client/src/main/java/net/runelite/client/util/QuantityFormatter.java}:
 * {@code quantityToStackSize} :80-115, {@code parseQuantity} :165-170, {@code formatNumber} :177-180),
 * so "12.3k" here means exactly what it means on an item stack in the game.
 */
public final class MovementMath
{
	/**
	 * What every "there is no number here" cell reads. One dash, everywhere, so a missing baseline is never
	 * mistaken for a zero move.
	 */
	public static final String DASH = "-";

	/**
	 * {@code HH:mm} in the viewer's own zone. Immutable and therefore safe to share
	 * ({@link DateTimeFormatter} is thread-safe); {@link Locale#ENGLISH} only pins the digits, as the pattern
	 * carries no locale-sensitive text.
	 */
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

	/**
	 * {@code dd MMM} for a CALENDAR DATE - "07 Sep" - the day marker addendum L put in the status line and every
	 * row tooltip (L7). No clock and no zone: what it prints is a {@link LocalDate} that was already derived in
	 * UTC from the guide table's own {@code %LAST_UPDATE%} (L9), and turning a date into an instant to show it in
	 * the viewer's zone would shift it a day for half the world.
	 */
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);

	/**
	 * How many decimals a percentage keeps (L2). The Grand Exchange site prints whole percents; ours keeps one,
	 * because a bank full of guide prices moves by fractions of a percent on most days (calibration: median
	 * |move| 0.36 % over 504 items) and whole percents would print a wall of zeroes.
	 */
	private static final int PCT_DECIMALS = 1;

	private MovementMath()
	{
	}

	// ---------------------------------------------------------------- rows (C8)

	/**
	 * Builds one row from a bank stack and the two price points that bracket the window (contract C8, sources
	 * per addendum K line K6/K13).
	 *
	 * <p>The source ladder is: the "now" point's mid if there is a POSITIVE one - since addendum K the service
	 * builds it as {@code new PricePoint(guide, guide)} from RuneLite's guide price (K1), so the mid IS that price
	 * and the source is GUIDE; otherwise the fallback price if the caller offered a positive one (source RUNELITE -
	 * the pre-K path, which the service no longer takes: it always passes 0); otherwise no price. A point that
	 * exists but has NEITHER side counts as no price, not as zero.
	 *
	 * <p><b>Why a "now" of 0 or less is no price at all.</b> L1 spells 0 as "RuneLite has no price for this item",
	 * and both the other two places that decide what counts as a price already say so - {@code PriceService.finish}
	 * ({@code price > 0 ? Long.valueOf(price) : null}) and {@code PortfolioMath}'s own {@code priced} test. The
	 * degraded mode of L3 takes "now" from the newest guide TABLE instead, where a 0 or a negative would survive
	 * the fold, and a row priced at 0 would pass the gp band, count as priced, and read "-100.0%" at the top of
	 * the losers - while the bank-value card, which applies the rule, would leave the same stack out. One rule,
	 * three places.
	 *
	 * <p>A move is computed ONLY for a GUIDE row with both ends present and a positive "then": the baseline is
	 * the same Jagex series at an earlier revision, so the difference is a real move, while a fallback price is
	 * a different measurement and subtracting a baseline from it would invent one; a "then" of 0 would make the
	 * percentage infinite.
	 *
	 * @param item                  the bank stack; never null
	 * @param now                   the guide price now as a point, or null when RuneLite has none
	 * @param then                  the baseline revision's point for this item, or null when no table names it
	 * @param runeliteFallbackPrice a fallback price with no history behind it, or 0 for "do not fall back"
	 * @return the row, never null
	 */
	public static MovementRow row(final BankItem item, final PricePoint now, final PricePoint then,
		final int runeliteFallbackPrice)
	{
		Objects.requireNonNull(item, "item");

		final Long guideNow = now == null ? null : now.mid();

		final Long unitPrice;
		final MovementRow.PriceSource source;
		if (guideNow != null && guideNow > 0L)
		{
			unitPrice = guideNow;
			source = MovementRow.PriceSource.GUIDE;
		}
		else if (runeliteFallbackPrice > 0)
		{
			unitPrice = (long) runeliteFallbackPrice;
			source = MovementRow.PriceSource.RUNELITE;
		}
		else
		{
			unitPrice = null;
			source = MovementRow.PriceSource.NONE;
		}

		final Long thenPrice = then == null ? null : then.mid();

		Long deltaGp = null;
		Double deltaPct = null;
		if (source == MovementRow.PriceSource.GUIDE && thenPrice != null && thenPrice > 0L)
		{
			deltaGp = unitPrice - thenPrice;
			deltaPct = deltaGp * 100.0d / thenPrice;
		}

		return new MovementRow(item.id, item.name, item.quantity, item.stackable, unitPrice, thenPrice,
			deltaGp, deltaPct, holdingValue(unitPrice, item.quantity), source);
	}

	/**
	 * The row an UNTRADEABLE stack becomes while "Include untradeable items" is on (addendum Q, line Q5): its unit
	 * price is the item's High Alchemy value ({@link BankItem#alchPrice()}), its source is
	 * {@link MovementRow.PriceSource#ALCH}, and it has no baseline and therefore no move - an alch value is a
	 * constant of the item rather than a series, so subtracting one from another would invent a movement that
	 * never happened.
	 *
	 * <p>The holding is the alch value times the quantity, as for any other row, which is what puts the stack in
	 * the bank value and what the row prints in holding mode (Q6).
	 *
	 * @param item the bank stack; never null. A stack that is not untradeable, or whose alch value is 0, has no
	 *             price here at all and renders as the dash - the caller decides whether such a row is worth
	 *             drawing ({@code MovementMath.apply} drops it)
	 */
	public static MovementRow alchRow(final BankItem item)
	{
		Objects.requireNonNull(item, "item");

		final Long alch = item.alchPrice();
		return new MovementRow(item.id, item.name, item.quantity, item.stackable, alch, null, null, null,
			holdingValue(alch, item.quantity),
			alch == null ? MovementRow.PriceSource.NONE : MovementRow.PriceSource.ALCH);
	}

	/**
	 * What a stack is worth: unit price times quantity (contract C8). A bank can hold 2.1 billion of a
	 * 2 billion gp item on paper, so the multiplication is checked and CLAMPED to {@link Long#MAX_VALUE}
	 * rather than allowed to wrap into a negative fortune.
	 *
	 * @return 0 when there is no price or nothing to hold
	 */
	public static long holdingValue(final Long unitPrice, final int quantity)
	{
		if (unitPrice == null || quantity <= 0)
		{
			return 0L;
		}

		try
		{
			return Math.multiplyExact(unitPrice, (long) quantity);
		}
		catch (ArithmeticException overflow)
		{
			return Long.MAX_VALUE;
		}
	}

	/**
	 * What a stack's price CHANGED by: the unit change times the quantity (addendum Q, line Q6) - the figure a
	 * row prints in holding mode and the key the {@link SortMode#GP_MOVE} column sorts on then.
	 *
	 * <p>Not {@link #holdingValue}: a change is signed, so the overflow clamp has to pick an END rather than
	 * always {@link Long#MAX_VALUE}. A fall clamped upwards would sort a collapse to the top of the gainers.
	 *
	 * @param deltaGp  the change in the price of one, or null when there is no move
	 * @param quantity how many the bank holds
	 * @return 0 when there is no move or nothing to hold; otherwise the product, clamped to
	 *         {@link Long#MAX_VALUE} or {@link Long#MIN_VALUE} by the change's own sign
	 */
	public static long holdingDelta(@Nullable final Long deltaGp, final int quantity)
	{
		if (deltaGp == null || quantity <= 0)
		{
			return 0L;
		}

		try
		{
			return Math.multiplyExact(deltaGp, (long) quantity);
		}
		catch (ArithmeticException overflow)
		{
			return deltaGp > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
		}
	}

	// ---------------------------------------------------------------- filter and sort (C9)

	/**
	 * The user's view of the rows: keep what the gp band admits, order it by the chosen sort (contract C9).
	 *
	 * <p>A row with no unit price is dropped rather than shown at the bottom - a band on the price of one
	 * cannot say anything about an item that has no price, and the count line would otherwise claim items the
	 * filter never considered.
	 *
	 * @param rows   every bank row; null reads as empty
	 * @param filter the band, sort, direction and window; null reads as {@link RowFilter#DEFAULT}
	 * @return a new unmodifiable list
	 */
	public static List<MovementRow> apply(final List<MovementRow> rows, final RowFilter filter)
	{
		return apply(rows, filter, ViewOptions.DEFAULT);
	}

	/**
	 * The same, with the view switches the list is being drawn under (addendum Q). Only ONE column reads them:
	 * with {@code holdingOnRows} on, {@link SortMode#GP_MOVE} sorts on the STACK's change rather than one item's
	 * (Q6), because that is the figure those rows are then printing. Nothing else moves - since addendum W the
	 * stack's WORTH has a column of its own ({@link SortMode#STACK_VALUE}), so {@link SortMode#UNIT_PRICE} means
	 * the price of one item under every switch (W1 reverted addendum V's line V1). The band still judges the unit
	 * price whatever the rows print - it is "gp min / max" on the price of ONE item (design D4), and a band that
	 * silently meant something else when a display switch moved would be a different feature.
	 *
	 * @param options the view switches; null reads as {@link ViewOptions#DEFAULT}, which is today's behaviour
	 */
	public static List<MovementRow> apply(final List<MovementRow> rows, final RowFilter filter,
		final ViewOptions options)
	{
		final RowFilter used = filter == null ? RowFilter.DEFAULT : filter;
		final List<MovementRow> kept = new ArrayList<>();

		if (rows != null)
		{
			for (final MovementRow row : rows)
			{
				if (row == null || row.unitPrice() == null)
				{
					continue;
				}

				if (inBand(row.unitPrice(), used.gpMin(), used.gpMax()))
				{
					kept.add(row);
				}
			}
		}

		kept.sort(comparator(used.sort(), used.descending(), options));
		return Collections.unmodifiableList(kept);
	}

	/**
	 * The order one sort puts rows in (contract C9).
	 *
	 * <p>Two rules beyond "compare the key": a row WITHOUT the key - no move to speak of, or no price - is
	 * always last whichever way the arrow points (flipping the direction must not fill the top of the list
	 * with dashes), and equal keys fall back to name then id so the list never shuffles between two
	 * recomputes of the same data.
	 *
	 * @param sort       which key to compare; null reads as {@link SortMode#PERCENT_MOVE}
	 * @param descending true for biggest first
	 */
	public static Comparator<MovementRow> comparator(final SortMode sort, final boolean descending)
	{
		return comparator(sort, descending, ViewOptions.DEFAULT);
	}

	/**
	 * The same, under the view switches (addendum Q line Q6): with {@code holdingOnRows} on,
	 * {@link SortMode#GP_MOVE} compares {@link MovementRow#holdingDeltaGp()} - the change over the whole stack -
	 * because that is the figure those rows are then printing. That is the ONLY thing the switch moves.
	 *
	 * <p>Addendum V had {@link SortMode#UNIT_PRICE} follow the switch too (line V1), so that the dearest-first
	 * list meant the stack while it was on; addendum W reverted it, because the question V was answering - a hundred
	 * Robin hood hats ARE a large holding though one hat is not a dear item (the user, 2026-09-12) - now has a
	 * column of its own in {@link SortMode#STACK_VALUE} and does not need to borrow another one's meaning. So
	 * the price column is the price of ONE item whatever the switch says, and the percentage column is unmoved
	 * either way: a percentage is the same number whether one item or a thousand is held.
	 *
	 * @param options the view switches; null reads as {@link ViewOptions#DEFAULT}
	 */
	public static Comparator<MovementRow> comparator(final SortMode sort, final boolean descending,
		final ViewOptions options)
	{
		final SortMode used = sort == null ? SortMode.PERCENT_MOVE : sort;
		final boolean holding = (options == null ? ViewOptions.DEFAULT : options).holdingOnRows();

		return (a, b) ->
		{
			final Double keyA = key(a, used, holding);
			final Double keyB = key(b, used, holding);

			if (keyA == null || keyB == null)
			{
				if (keyA != null)
				{
					return -1;
				}

				if (keyB != null)
				{
					return 1;
				}

				return byNameThenId(a, b);
			}

			final int byKey = Double.compare(keyA, keyB);
			if (byKey != 0)
			{
				return descending ? -byKey : byKey;
			}

			return byNameThenId(a, b);
		};
	}

	/**
	 * Whether a unit price sits inside the band, where a maximum of 0 means "no upper bound" (contract C10) -
	 * the reading an empty Max field writes.
	 */
	public static boolean inBand(final long unit, final long min, final long max)
	{
		return unit >= min && (max == 0L || unit <= max);
	}

	/**
	 * The sort key as a double. Every gp figure a bank can hold is far below 2^53, where a double still counts
	 * exactly, so this loses nothing and keeps one comparator for the four columns (addendum W, line W1).
	 *
	 * <p>{@link SortMode#STACK_VALUE} is gated on {@link MovementRow#unitPrice()} rather than on the holding
	 * value itself, because {@link MovementRow#holdingValue()} answers a plain 0 for a row with no price, and a
	 * 0 sorts as the cheapest thing in the bank rather than as a dash. The same gate the price column has always
	 * used therefore serves both, and a priceless row is last under either whichever way the arrow points.
	 *
	 * @param holding only the gp key reads it: the whole stack's change rather than one item's (Q6). Its NULL
	 *                test does not move - {@link MovementRow#deltaGp()} still decides it, because a row with no
	 *                move has no gp key under either reading and {@link MovementRow#holdingDeltaGp()} would
	 *                answer a plain 0 for it
	 * @return null when this row has no such key - the "always last" case
	 */
	private static Double key(final MovementRow row, final SortMode sort, final boolean holding)
	{
		switch (sort)
		{
			case PERCENT_MOVE:
				return row.deltaPct();
			case GP_MOVE:
				return row.deltaGp() == null ? null
					: (double) (holding ? row.holdingDeltaGp() : row.deltaGp());
			case UNIT_PRICE:
				return row.unitPrice() == null ? null : (double) row.unitPrice();
			case STACK_VALUE:
				return row.unitPrice() == null ? null : (double) row.holdingValue();
			default:
				return null;
		}
	}

	/**
	 * The tiebreak, always ascending whichever way the sort points: alphabetical (case-insensitive, so
	 * "Abyssal whip" and "abyssal whip" land together), then by id so two items sharing a name still have a
	 * fixed order.
	 */
	private static int byNameThenId(final MovementRow a, final MovementRow b)
	{
		final int byName = String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name());
		return byName != 0 ? byName : Integer.compare(a.id(), b.id());
	}

	// ---------------------------------------------------------------- text (C10)

	/**
	 * Reads a gp amount the user typed: "100k", "1.5m", "2b", "1,000", "850" (contract C10). Delegates to
	 * {@code QuantityFormatter.parseQuantity} ({@code QuantityFormatter.java:165-170}) so the field accepts
	 * exactly what the rest of the client accepts, then adds the two rules a PRICE band needs and the
	 * formatter does not have: blank is not a number, and a negative is not a price (the formatter's suffix
	 * pattern :49 admits a leading "-" and would return it happily).
	 *
	 * @param text what the field holds; null and blank both throw
	 * @return the amount in gp, never negative
	 * @throws ParseException when the text names no non-negative amount - the panel turns the field's border
	 *                        red and leaves the filter alone (design C29 (5))
	 */
	public static long parseGp(final String text) throws ParseException
	{
		final String trimmed = text == null ? "" : text.trim();
		if (trimmed.isEmpty())
		{
			throw new ParseException("Enter a gp amount", 0);
		}

		final long value = QuantityFormatter.parseQuantity(trimmed);
		if (value < 0L)
		{
			throw new ParseException("A price cannot be negative: " + trimmed, 0);
		}

		return value;
	}

	/**
	 * A gp amount as the game writes a stack size: "850", "12.3k", "1.52m"
	 * ({@code QuantityFormatter.quantityToStackSize}, {@code QuantityFormatter.java:80-115}), lower-cased so
	 * the suffix sits quietly beside the number in a small row.
	 *
	 * <p>Note the client's own threshold rides along: anything under 10,000 is written out in full with
	 * commas ("4,100", not "4.1k").
	 */
	public static String formatGp(final long gp)
	{
		return lowerSuffix(QuantityFormatter.quantityToStackSize(gp));
	}

	/**
	 * The same amount in full, comma grouped ({@code QuantityFormatter.formatNumber},
	 * {@code QuantityFormatter.java:177-180}) - what the tooltip shows, where there is room for every digit.
	 */
	public static String formatExact(final long gp)
	{
		return QuantityFormatter.formatNumber(gp);
	}

	/**
	 * A price change with its sign: "+12.3k", "-4,100", "0", or {@link #DASH} when there is no move to show
	 * (contract C10).
	 *
	 * <p>A negative is formatted through the formatter's own minus rather than by negating the value, so
	 * {@link Long#MIN_VALUE} cannot overflow on its way to the screen.
	 */
	public static String formatDelta(final Long gp)
	{
		if (gp == null)
		{
			return DASH;
		}

		if (gp > 0L)
		{
			return "+" + formatGp(gp);
		}

		return formatGp(gp);
	}

	/**
	 * A percentage move as the Grand Exchange site writes one, to one decimal: "+0.8%", "-12.4%", "0.0%", or
	 * {@link #DASH} when there is none (contract C10, redefined by addendum L line L2).
	 *
	 * <p>The sign comes from the percentage itself here; the {@link #formatPct(Double, Long)} overload takes it
	 * from the gp change instead, which is what a row shows (L2).
	 */
	public static String formatPct(@Nullable final Double pct)
	{
		return formatPct(pct, null);
	}

	/**
	 * The same percentage, signed by the gp change beside it (L2).
	 *
	 * <p><b>Truncated toward zero, never rounded.</b> The GE site computes
	 * {@code trunc_toward_zero((daily[D] - daily[D-N]) / daily[D-N] * 100)}: measured against 26 live figures,
	 * truncation matched 26/26 and rounding only 15/26 (L-B). Ours truncates at {@value #PCT_DECIMALS} decimal
	 * instead of at a whole percent, so "-12.44" reads "-12.4%" - one digit shorter than the value, never one
	 * digit larger than it. {@link BigDecimal#valueOf(double)} goes through the double's SHORTEST decimal
	 * representation, so 0.7 truncates to "0.7" rather than to the "0.6" that {@code (long) (0.7 * 10)} yields
	 * from 6.999999999999999.
	 *
	 * <p><b>Why the sign is the gp change's and not the percentage's.</b> A fall of a hundredth of a percent
	 * truncates to a bare zero, and the site still prints it with a minus and a red arrow ("Amulet of fury",
	 * 30 d, -0.10 % shown as "-0.0%", L-B). So a row whose gp change is negative reads "-0.0%" and stays red,
	 * a row whose gp change is positive reads "+0.0%", and only a genuinely unmoved row reads "0.0%" in grey -
	 * the three states {@code MovementRowPanel.changeColor} already paints from the same gp figure.
	 *
	 * @param pct     the move in percent, or null / non-finite for {@link #DASH}
	 * @param deltaGp the gp change the percentage was computed from; null takes the sign from {@code pct}
	 */
	public static String formatPct(@Nullable final Double pct, @Nullable final Long deltaGp)
	{
		if (pct == null || !Double.isFinite(pct))
		{
			return DASH;
		}

		final BigDecimal truncated = BigDecimal.valueOf(pct).setScale(PCT_DECIMALS, RoundingMode.DOWN);
		// toPlainString, not toString: a percentage this large is nonsense data rather than "1.0E+2" on a row.
		final String digits = truncated.abs().toPlainString();
		final int sign = deltaGp != null ? Long.signum(deltaGp) : signum(pct);
		if (sign > 0)
		{
			return "+" + digits + "%";
		}

		if (sign < 0)
		{
			return "-" + digits + "%";
		}

		return digits + "%";
	}

	/**
	 * The sign of a double as -1, 0 or 1, with negative zero counted as zero - {@code Math.signum} answers
	 * {@code -0.0} for it, which would print a minus in front of an unmoved row.
	 */
	private static int signum(final double value)
	{
		if (value > 0.0d)
		{
			return 1;
		}

		return value < 0.0d ? -1 : 0;
	}

	/**
	 * A timestamp as the header's "Prices HH:mm" clock, in the viewer's own zone (contract C10).
	 *
	 * @param millis epoch millis; 0 or less - the "never fetched" reading of every timestamp in this plugin -
	 *               gives {@link #DASH} rather than 1970
	 */
	public static String formatTime(final long millis)
	{
		if (millis <= 0L)
		{
			return DASH;
		}

		return CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
	}

	/**
	 * A guide-price DAY as the status line and every row tooltip stamp it - "07 Sep" (addendum L, L7).
	 *
	 * <p>This is what a baseline is labelled with since addendum L; addendum K's {@code formatDayTime} - a local
	 * "07 Sep 19:55" built from the wiki revision's publication clock - went with the label it printed, so no
	 * caller can reach for a zone-shifted day by mistake (removed by the checker, 2026-09-09). The
	 * series steps ONCE A DAY at a variable UTC hour (L-A: the Jagex rollover was observed between 00:47 and
	 * 02:06 UTC, not at midnight) and the wiki's bot republishes it at a random hour of that day (02:11-22:20 UTC,
	 * L-C), so the publication CLOCK says nothing about which day's prices are in the table - the table's own
	 * {@code %LAST_UPDATE%} does (L-E), and that is a date. Printing a clock beside it invited exactly the
	 * misreading addendum L exists to end.
	 *
	 * <p>The date is printed as it stands, in no zone at all: it was derived in UTC (L9), and re-interpreting it
	 * in the viewer's zone would name the wrong day for anyone west of Greenwich.
	 *
	 * @param day the UTC calendar date; null - no baseline day known yet - gives {@link #DASH}
	 */
	public static String formatDay(@Nullable final LocalDate day)
	{
		if (day == null)
		{
			return DASH;
		}

		return DAY.format(day);
	}

	/**
	 * The hero card's baseline phrase - "since 08 Sep" (addendum N sections 3.1 and 4.4, the sub-line under
	 * the bank total and the Ticker card's provenance footnote).
	 *
	 * <p>Its whole job is the null. {@code " since " + formatDay(null)} would read "since -", a sentence
	 * that claims a baseline and then fails to name it; a card with no baseline day shows the one
	 * {@link #DASH} the rest of the panel shows instead, and the problem row says why (N 3.6).
	 *
	 * @param day the baseline's UTC calendar date; null - no baseline settled - gives {@link #DASH}
	 */
	public static String formatSince(@Nullable final LocalDate day)
	{
		return day == null ? DASH : "since " + formatDay(day);
	}

	/**
	 * Lower-cases a trailing K/M/B so a stack size reads as a price.
	 */
	private static String lowerSuffix(final String text)
	{
		final int last = text.length() - 1;
		if (last >= 0 && Character.isLetter(text.charAt(last)))
		{
			return text.substring(0, last) + Character.toLowerCase(text.charAt(last));
		}

		return text;
	}
}
