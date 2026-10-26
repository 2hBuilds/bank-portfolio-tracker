package com.bankpricemovement;

/**
 * Which of the hero card's three figures the sidebar prints: the whole-bank TOTAL, its gp move for the lit
 * window, and that move as a percentage (addendum O line O2;
 * {@code docs/bank-price-movement-addendum-O-2026-09-09.md}). The user asked for it in one sentence -
 * "i prefer the ticker look, also have an option to show/hide the bank value and pnl and percentage"
 * (2026-09-09) - so the card's headline block is three independent switches and everything else on it (the
 * caption, the Refresh link, the window chips, the footnote) is always drawn.
 *
 * <p><b>Immutable, and the three switches are independent.</b> Every {@code with*} answers a new value, so a
 * visibility can be handed to the panel, stored in a field and compared with {@code equals} without anyone
 * having to copy it. The eight combinations are all legal: {@link #ALL} is the default a fresh profile gets
 * and {@link #NONE} - the card reduced to its caption, its chips and its footnote - is one of them, not an
 * error.
 *
 * <p><b>Presentation only.</b> Hiding a figure hides the FIGURE, never the arithmetic: {@code PortfolioMath}
 * still computes the whole-bank sums, {@code state.portfolio} still echoes them in full (developer mode only)
 * and every row keeps its own numbers. This is deliberately not part of {@link RowFilter} for the reason
 * addendum N gave the look it replaces - a filter says what the list CONTAINS, and a switch that only decides
 * what is painted must not make the service recompute anything.
 *
 * <p><b>The move line is the pair.</b> {@link #gp()} and {@link #pct()} are the two halves of one line, so
 * {@link #moveLine()} - "is anything left of it" - is what the card asks before it decides whether to draw
 * the line and its direction triangle at all (O3).
 *
 * <p>The three switches are stored as three flat boolean config items ({@code showBankValue},
 * {@code showBankMoveGp}, {@code showBankMovePct}), which is what makes them reachable from RuneLite's own
 * settings panel as well as from the card's right-click menu; {@link BankPriceMovementPlugin#heroFromConfig()}
 * is the one place the three are read back into this value.
 */
public final class HeroVisibility
{
	/** Everything shown - the default a fresh profile gets, and what addendum M's card always drew (O2). */
	public static final HeroVisibility ALL = new HeroVisibility(true, true, true);
	/** All three figures hidden: the card keeps its caption, its chips and its footnote and nothing else (O3). */
	public static final HeroVisibility NONE = new HeroVisibility(false, false, false);

	private final boolean value;
	private final boolean gp;
	private final boolean pct;

	private HeroVisibility(boolean value, boolean gp, boolean pct)
	{
		this.value = value;
		this.gp = gp;
		this.pct = pct;
	}

	/**
	 * @param value whether the 28 px whole-bank total is drawn
	 * @param gp    whether the move line carries the gp change
	 * @param pct   whether the move line carries the percentage
	 */
	public static HeroVisibility of(boolean value, boolean gp, boolean pct)
	{
		if (value && gp && pct)
		{
			return ALL;
		}
		if (!value && !gp && !pct)
		{
			return NONE;
		}
		return new HeroVisibility(value, gp, pct);
	}

	/** Whether the whole-bank total is drawn; with it off the 28 px line is REMOVED and the card shrinks (O3). */
	public boolean value()
	{
		return value;
	}

	/** Whether the move line carries the gp change ("+12.4m"). */
	public boolean gp()
	{
		return gp;
	}

	/** Whether the move line carries the percentage ("+1.0%"). */
	public boolean pct()
	{
		return pct;
	}

	/**
	 * Whether the move line exists at all: with BOTH figures off the line is removed and the direction triangle
	 * goes with it (O3). The card's coloured left edge is not a figure and stays either way - it is a direction
	 * hint, and hiding a number is not the same as hiding which way the bank went.
	 */
	public boolean moveLine()
	{
		return gp || pct;
	}

	/** Whether any figure is drawn at all; false is {@link #NONE}, the caption-chips-footnote card. */
	public boolean any()
	{
		return value || gp || pct;
	}

	public HeroVisibility withValue(boolean shown)
	{
		return shown == value ? this : of(shown, gp, pct);
	}

	public HeroVisibility withGp(boolean shown)
	{
		return shown == gp ? this : of(value, shown, pct);
	}

	public HeroVisibility withPct(boolean shown)
	{
		return shown == pct ? this : of(value, gp, shown);
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof HeroVisibility))
		{
			return false;
		}
		final HeroVisibility other = (HeroVisibility) o;
		return value == other.value && gp == other.gp && pct == other.pct;
	}

	@Override
	public int hashCode()
	{
		return (value ? 4 : 0) | (gp ? 2 : 0) | (pct ? 1 : 0);
	}
}
