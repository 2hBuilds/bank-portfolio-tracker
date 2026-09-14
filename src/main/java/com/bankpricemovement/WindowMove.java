package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The whole bank's guide-price move over ONE window, on a consistent basis (addendum M, line M1/M2;
 * {@code docs/bank-price-movement-addendum-M-2026-09-09.md}): only the stacks priced on BOTH days count, so
 * {@link #valueThen()} and {@link #valueNowCovered()} are sums over the same stacks and their difference is a real
 * move rather than the arrival or departure of a price. {@link #itemsCovered()} says how many stacks that was, and
 * the panel shows it beside {@code itemsPriced} so a reader can see when a window's coverage is thin.
 *
 * <p>Since addendum P line P1 the bank's cash ({@link BankSnapshot#currencyGp}) sits inside {@link #valueThen()} and
 * {@link #valueNowCovered()} alike, so {@link #deltaGp()} is exactly what it always was and {@link #deltaPct()} is
 * measured against the whole bank. A window that covers no stack carries no cash and keeps its zeros - see
 * {@link PortfolioMath}, which computes all of this; this class stays a value holder.
 *
 * <p>Immutable, like {@link MovementRow}: built on the service's executor and read on the EDT and by the dev bridge.
 * Sums are {@code long} clamped at {@link Long#MAX_VALUE} by {@link PortfolioMath}, never wrapped.
 */
public final class WindowMove
{
	private final MovementWindow window;
	@Nullable
	private final LocalDate thenDay;
	private final long valueThen;
	private final long valueNowCovered;
	private final long deltaGp;
	@Nullable
	private final Double deltaPct;
	private final int itemsCovered;

	/**
	 * @param window          the window this move is measured over; null reads as {@link MovementWindow#DEFAULT}
	 * @param thenDay         the UTC day the window's baseline table claims (its own {@code %LAST_UPDATE%}, L7), or
	 *                        null when the baseline carries no day marker
	 * @param valueThen       the sum of {@code then x quantity} over the covered stacks plus the bank's cash (P1),
	 *                        clamped; the cash is left off when nothing is covered
	 * @param valueNowCovered the sum of {@code now x quantity} over the SAME stacks plus the same cash, clamped
	 * @param deltaGp         {@code valueNowCovered - valueThen}
	 * @param deltaPct        {@code deltaGp x 100 / valueThen}, or null when {@code valueThen} is 0 (M1)
	 * @param itemsCovered    how many stacks were priced on both days
	 */
	public WindowMove(@Nullable final MovementWindow window, @Nullable final LocalDate thenDay, final long valueThen,
		final long valueNowCovered, final long deltaGp, @Nullable final Double deltaPct, final int itemsCovered)
	{
		this.window = window == null ? MovementWindow.DEFAULT : window;
		this.thenDay = thenDay;
		this.valueThen = valueThen;
		this.valueNowCovered = valueNowCovered;
		this.deltaGp = deltaGp;
		this.deltaPct = deltaPct;
		this.itemsCovered = itemsCovered;
	}

	/** Never null. */
	public MovementWindow window()
	{
		return window;
	}

	/** The baseline table's own UTC day (L7), the day the tooltip prints ("1d vs 07 Sep"); null when unknown. */
	@Nullable
	public LocalDate thenDay()
	{
		return thenDay;
	}

	/** The covered stacks' worth at the baseline day's guide prices, plus the bank's cash (P1). */
	public long valueThen()
	{
		return valueThen;
	}

	/**
	 * The SAME stacks' worth at today's guide prices, plus the same cash - still not the whole bank's
	 * {@link PortfolioSummary#valueNow()}, which counts every priced stack rather than only the covered ones.
	 */
	public long valueNowCovered()
	{
		return valueNowCovered;
	}

	/**
	 * {@link #valueNowCovered()} minus {@link #valueThen()}; the sign that colours the line (L2). The cash cancels,
	 * so this is the covered stacks' move and nothing else (P1).
	 */
	public long deltaGp()
	{
		return deltaGp;
	}

	/**
	 * The move as a percentage of {@link #valueThen()} - cash included in that base since P1 - untruncated; null
	 * when nothing was worth anything then, which is also the case of a window that covers no stack at all.
	 */
	@Nullable
	public Double deltaPct()
	{
		return deltaPct;
	}

	/** How many stacks were priced on both days - the basis of the three sums. */
	public int itemsCovered()
	{
		return itemsCovered;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof WindowMove))
		{
			return false;
		}
		final WindowMove other = (WindowMove) o;
		return window == other.window
			&& Objects.equals(thenDay, other.thenDay)
			&& valueThen == other.valueThen
			&& valueNowCovered == other.valueNowCovered
			&& deltaGp == other.deltaGp
			&& Objects.equals(deltaPct, other.deltaPct)
			&& itemsCovered == other.itemsCovered;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(window, thenDay, valueThen, valueNowCovered, deltaGp, deltaPct, itemsCovered);
	}

	@Override
	public String toString()
	{
		return "WindowMove{window=" + window.name()
			+ ", thenDay=" + thenDay
			+ ", valueThen=" + valueThen
			+ ", valueNowCovered=" + valueNowCovered
			+ ", deltaGp=" + deltaGp
			+ ", deltaPct=" + deltaPct
			+ ", itemsCovered=" + itemsCovered + '}';
	}
}
