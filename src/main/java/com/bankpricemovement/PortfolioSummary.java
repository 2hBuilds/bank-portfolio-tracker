package com.bankpricemovement;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * The bank as a portfolio (addendum M, lines M1/M2; {@code docs/bank-price-movement-addendum-M-2026-09-09.md}):
 * what every priced stack is worth at today's guide prices, how many stacks that covers, and one {@link WindowMove}
 * per window that has a baseline in memory. The WHOLE bank - the gp band the rows are filtered on is not applied,
 * because a portfolio is everything you own, not the slice you are looking at.
 *
 * <p>Immutable and shared: computed by {@link PortfolioMath#summariseResolved} (the service's shape) or
 * {@link PortfolioMath#summarise} (the contract's) on the service's executor, published inside
 * {@link PriceService.Status} to the EDT, echoed by the dev bridge. {@link #EMPTY} is what a status carries until
 * the first computation (M3).
 *
 * <p><b>Live prices</b> (addendum T, line T5). With the {@code livePrices} switch on, a stack that passed the
 * liquidity checks - T3's three, and addendum V's two on yesterday's own daily bucket - is counted at its live
 * traded mid rather than at the guide price, and every window
 * compares it against that day's traded average - each stack on its own series, which is why
 * {@link PortfolioMath} takes a "now" resolver per window as well as the one behind {@link #valueNow()}.
 * {@link #liveRows()} is how many stacks that was. With the switch off it is 0 and every figure here is the
 * guide-only one of addenda K to S, to the gp.
 *
 * <p><b>Cash</b> (addendum P, line P1; {@code docs/bank-price-movement-addendum-P-2026-09-10.md}). The bank's coins
 * and platinum tokens are not stacks - they have no market and never appear as a row - but they are half of what a
 * player means by "bank value", so {@link #currencyGp()} rides here beside the stacks and {@link #valueNow()} is the
 * two added. {@link #itemsPriced()} and {@link #itemsTotal()} still count stacks only.
 */
public final class PortfolioSummary
{
	/** No bank computed yet: nothing is worth anything, no stacks, no cash, no windows (M3: "EMPTY until the first compute"). */
	public static final PortfolioSummary EMPTY = new PortfolioSummary(0L, 0, 0, Collections.emptyMap(), 0L);

	private final long valueStacks;
	private final int itemsPriced;
	private final int itemsTotal;
	private final long currencyGp;
	private final int liveRows;
	private final Map<MovementWindow, WindowMove> moves;

	/**
	 * The pre-P1 arity, kept so every caller that knows nothing of cash keeps compiling AND keeps its figures: it
	 * delegates with {@code currencyGp} 0, and 0 cash makes {@link #valueNow()} the stacks' value exactly as before.
	 *
	 * @param valueStacks the sum of {@code now x quantity} over every stack with a guide price, clamped at
	 *                    {@link Long#MAX_VALUE} (M1)
	 * @param itemsPriced how many stacks have a guide price
	 * @param itemsTotal  how many stacks the bank holds
	 * @param moves       one move per window WITH a baseline; copied into an unmodifiable {@link EnumMap}, null keys or
	 *                    values dropped, null reads as empty
	 */
	public PortfolioSummary(final long valueStacks, final int itemsPriced, final int itemsTotal,
		@Nullable final Map<MovementWindow, WindowMove> moves)
	{
		this(valueStacks, itemsPriced, itemsTotal, moves, 0L);
	}

	/**
	 * @param valueStacks the sum of {@code now x quantity} over every stack with a guide price, WITHOUT the cash,
	 *                    clamped at {@link Long#MAX_VALUE} (M1); {@link #valueNow()} is this plus {@code currencyGp}
	 * @param itemsPriced how many stacks have a guide price - cash is not a stack and is not counted here
	 * @param itemsTotal  how many stacks the bank holds - cash is not one of them either
	 * @param moves       one move per window WITH a baseline; copied into an unmodifiable {@link EnumMap}, null keys or
	 *                    values dropped, null reads as empty
	 * @param currencyGp  the bank's coins and platinum tokens in gp ({@link BankSnapshot#currencyGp}, P1); a negative
	 *                    reads as 0, because there is no such thing as a negative fortune
	 */
	public PortfolioSummary(final long valueStacks, final int itemsPriced, final int itemsTotal,
		@Nullable final Map<MovementWindow, WindowMove> moves, final long currencyGp)
	{
		this(valueStacks, itemsPriced, itemsTotal, moves, currencyGp, 0);
	}

	/**
	 * The same, carrying how many of those stacks were priced from the LIVE traded series (addendum T, line T5).
	 * The arity above is the pre-T one, kept so every caller that knows nothing of live prices keeps compiling AND
	 * keeps its meaning: it delegates with 0, which is what a summary computed with the switch off holds.
	 *
	 * @param liveRows how many stacks {@link #valueStacks()} counted at a live traded mid rather than at a guide
	 *                 price; a negative reads as 0, and 0 is every summary of addenda K to S
	 */
	public PortfolioSummary(final long valueStacks, final int itemsPriced, final int itemsTotal,
		@Nullable final Map<MovementWindow, WindowMove> moves, final long currencyGp, final int liveRows)
	{
		this.valueStacks = valueStacks;
		this.itemsPriced = itemsPriced;
		this.itemsTotal = itemsTotal;
		this.currencyGp = Math.max(0L, currencyGp);
		this.liveRows = Math.max(0, liveRows);
		final EnumMap<MovementWindow, WindowMove> copy = new EnumMap<>(MovementWindow.class);
		if (moves != null)
		{
			for (final Map.Entry<MovementWindow, WindowMove> entry : moves.entrySet())
			{
				if (entry.getKey() != null && entry.getValue() != null)
				{
					copy.put(entry.getKey(), entry.getValue());
				}
			}
		}
		this.moves = Collections.unmodifiableMap(copy);
	}

	/**
	 * The whole bank's worth at today's guide prices: the {@link #itemsPriced()} stacks that have one, PLUS the
	 * {@link #currencyGp()} in the bank (P1). The headline figure on the card, and clamped at
	 * {@link Long#MAX_VALUE} like every other sum here.
	 */
	public long valueNow()
	{
		return PortfolioMath.clampedAdd(valueStacks, currencyGp);
	}

	/**
	 * {@link #valueNow()} without the cash: what the STACKS alone are worth - the {@link #itemsPriced()} with a
	 * guide price, which since addendum R includes an untradeable stack valued at the sum of its tradeable parts
	 * (R3), plus any remaining untradeable ones at their High Alchemy value (Q5) - which is the
	 * figure the rows add up to. The card and its tooltip both print {@link #valueNow()} - the whole bank in one
	 * number - so this is for a reader who wants the two halves apart (a test, or a live session subtracting the
	 * cash) rather than for anything drawn.
	 */
	public long valueStacks()
	{
		return valueStacks;
	}

	/**
	 * The bank's coins and platinum tokens in gp ({@code coins + 1,000 x platinum}, P1); 0 when the bank holds no
	 * currency, and 0 for a remembered bank captured before the plugin measured it ({@link BankSnapshot#currencyGp}).
	 */
	public long currencyGp()
	{
		return currencyGp;
	}

	/**
	 * Stacks with a GUIDE price - the market prices behind {@link #valueStacks()}. Cash is not a stack, and
	 * neither is an untradeable stack valued at its High Alchemy value counted here (Q5): an alch value is not a
	 * market price, so such a stack lands in {@link #valueStacks()} and {@link #itemsTotal()} but never here. An
	 * untradeable stack valued at the sum of its TRADEABLE PARTS does count (R3): every gp of that sum is a guide
	 * price, and it moves with them.
	 */
	public int itemsPriced()
	{
		return itemsPriced;
	}

	/**
	 * Every stack the summary was given, priced or not - including the untradeable ones while they are being
	 * counted (Q5). Cash is not one of them.
	 */
	public int itemsTotal()
	{
		return itemsTotal;
	}

	/**
	 * How many of the {@link #itemsPriced()} stacks were valued at a LIVE traded mid rather than at the Jagex
	 * guide price (addendum T, line T5). It was the N of the card tooltip's "N of M stacks live" until addendum
	 * AF took that hover down to the bank value alone; it now reaches a reader through the dev bridge. 0 whenever the
	 * {@code livePrices} switch is off, which is every summary of addenda K to S.
	 */
	public int liveRows()
	{
		return liveRows;
	}

	/** One move per window with a baseline in memory, in window order; unmodifiable, never null. */
	public Map<MovementWindow, WindowMove> moves()
	{
		return moves;
	}

	/** The move for one window, or null when that window has no baseline (the panel prints "1d   -   -"). */
	@Nullable
	public WindowMove move(@Nullable final MovementWindow window)
	{
		return window == null ? null : moves.get(window);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof PortfolioSummary))
		{
			return false;
		}
		final PortfolioSummary other = (PortfolioSummary) o;
		// The stacks and the cash separately, not their sum: two banks that add up to the same clamped total are
		// still two different banks.
		return valueStacks == other.valueStacks
			&& itemsPriced == other.itemsPriced
			&& itemsTotal == other.itemsTotal
			&& currencyGp == other.currencyGp
			&& liveRows == other.liveRows
			&& moves.equals(other.moves);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(valueStacks, itemsPriced, itemsTotal, currencyGp, liveRows, moves);
	}

	@Override
	public String toString()
	{
		return "PortfolioSummary{valueNow=" + valueNow()
			+ ", currencyGp=" + currencyGp
			+ ", itemsPriced=" + itemsPriced
			+ ", itemsTotal=" + itemsTotal
			+ (liveRows > 0 ? ", liveRows=" + liveRows : "")
			+ ", moves=" + moves.values() + '}';
	}
}
