package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * The bank value line's arithmetic, in one pure place (addendum M, line M1;
 * {@code docs/bank-price-movement-addendum-M-2026-09-09.md}). Nothing here touches the client, Swing, the
 * network or the clock - like {@link MovementMath}, which is what makes it testable without a client.
 *
 * <p><b>The rules (M1).</b>
 * <ul>
 * <li>{@code valueNow} = the sum over EVERY bank stack that has a guide price of {@code unitPrice x quantity} (since
 * P1, plus the bank's cash - see below): the whole bank, never the filtered rows.</li>
 * <li>For each window with a baseline, the move counts ONLY the stacks priced on BOTH days - a consistent basis:
 * {@code valueThen = sum(then x qty)}, {@code valueNowCovered = sum(now x qty)} over those same stacks,
 * {@code deltaGp = valueNowCovered - valueThen}, {@code deltaPct = deltaGp x 100 / valueThen} (null when
 * {@code valueThen} is 0). Without the shared basis a stack whose baseline is missing would show up as a fall of its
 * whole worth, and one whose price arrived today as a rise.</li>
 * <li>A price is "priced" when it is present and positive: RuneLite's 0 means "no price" (L1), and
 * {@link MovementMath#row} refuses a move against a "then" of 0 for the same reason.</li>
 * <li>Every sum is a {@code long} clamped at {@link Long#MAX_VALUE}: a stack is
 * {@link MovementMath#holdingValue} (a checked multiply that clamps) and the running totals go through a checked
 * add that clamps. Nothing wraps into a negative fortune. Quantities are {@code int}, as the bank holds them.</li>
 * </ul>
 *
 * <p><b>The cash</b> (addendum P, line P1; {@code docs/bank-price-movement-addendum-P-2026-09-10.md}). The bank's
 * coins and platinum tokens ({@link BankSnapshot#currencyGp}) are handed in as one gp figure and counted as follows.
 * <ul>
 * <li>{@code valueNow} = the priced stacks plus the cash: the whole bank, which is what the card's headline claims
 * and what RuneLite's own bank total shows.</li>
 * <li>A window's {@code valueThen} and {@code valueNowCovered} BOTH carry the cash, so {@code deltaGp} is untouched
 * (cash does not move) while {@code deltaPct} is measured against a base that includes it - a cash-heavy bank moves
 * less in percent, and that is the truth of the whole bank rather than of the traded slice of it.</li>
 * <li>A window that covers no stack keeps today's rule, cash or not: {@code valueThen} and {@code valueNowCovered}
 * stay 0 and {@code deltaPct} stays null, so the card prints a dash. Adding the cash there would turn "nothing to
 * compare" into a measured move of 0.0 %.</li>
 * <li>The cash is in no other figure: {@code itemsPriced}, {@code itemsTotal} and {@code itemsCovered} count stacks,
 * and coins are not a stack (design D6 keeps them out of the rows - a coin row would read 0 % for ever).</li>
 * </ul>
 * A {@code currencyGp} of 0 is the pre-P1 behaviour exactly, to the gp, so a caller with no cash to declare passes 0.
 *
 * <p><b>Untradeable stacks</b> (addendum Q, line Q5). A stack marked {@link BankItem#untradeable} has no market
 * price: it is worth its High Alchemy value ({@link BankItem#alchPrice()}) and counts in {@code valueNow} and
 * {@code itemsTotal}, is NOT counted in {@code itemsPriced}, and is in no window's covered set on either side - an
 * alch value is a constant of the item, so including it would add the same number to both sides of every move and
 * dilute the percentage with something that cannot move. Whether such stacks are in the list at all is the
 * "Include untradeable items" switch, applied by the caller; a list without them is the pre-Q behaviour exactly.
 *
 * <p><b>...unless they have parts</b> (addendum R, line R3). A stack RuneLite maps onto tradeable parts
 * ({@link BankItem#hasParts()}) is worth what those parts fetch on the exchange, which IS a market price and does
 * move. Such a stack is therefore asked of the resolvers like any other - the caller answers the parts SUM under the
 * stack's own id, on both sides - and counts in {@code valueNow}, {@code itemsTotal} AND {@code itemsPriced}, and in
 * a window's covered set whenever that window's resolver answers for it. The alch rule above is what a stack with no
 * parts falls back to, and what a stack whose parts the caller could not price falls back to as well: the caller
 * answers null for it, and null is the alch rule here. So a bank whose stacks carry no parts - every bank before
 * addendum R, and every bank at all while the switch is off - behaves exactly as it did.
 *
 * <p><b>The signature</b> (M2 lets the builder pick "the equivalent over the service's existing per-item projection"
 * and asks for it to be documented). {@code PriceService.finish} has, per item, the guide "now" as one number per
 * canonical id (RuneLite's table, or the newest wiki table standing in for it in the degraded mode of L3) and, per
 * window, a "then" resolved through the SAME ladder the rows use - the projected {@link PriceMap} first, then the
 * L8 b name fallback against the table in memory. Both are therefore handed in as id-keyed resolvers,
 * {@code Function<Integer, Long>}, one for "now" and one per window for "then", so the portfolio for the current
 * window is exactly the sum of the rows it is published beside (unfiltered), and the other windows get the identical
 * treatment without a second projection. A resolver keyed by item ID loses nothing: a normalised snapshot has one
 * stack per canonical id, and every input the service resolves from is itself per id. The overload taking
 * {@code Map<MovementWindow, PriceMap>} is the contract's literal shape for a caller that holds only projections
 * (the checker's independent computation); it reads each map's {@link PricePoint#mid()} and delegates.
 */
public final class PortfolioMath
{
	private PortfolioMath()
	{
	}

	/**
	 * The contract's literal shape (M2): the baselines as the projected {@link PriceMap}s, each window's "then" for an
	 * id being that map's point (its {@link PricePoint#mid()}); a window is included when it has a map. No name
	 * fallback is applied here - the service's overload carries it - so this is exactly "the persisted projection
	 * against the bank", which is what an independent check computes.
	 *
	 * @param items      the bank; null reads as empty, null entries are skipped
	 * @param nowPrice   the guide price now for a canonical id, null or non-positive = no price
	 * @param baselines  one projected map per window with a baseline; null reads as none
	 * @param thenDays   the day each of those maps belongs to (its own {@code %LAST_UPDATE%} day, L7); null reads as none
	 * @param currencyGp the bank's coins and platinum tokens in gp ({@link BankSnapshot#currencyGp}, P1); 0 is the
	 *                   pre-P1 behaviour exactly
	 */
	public static PortfolioSummary summarise(@Nullable final List<BankItem> items, final Function<Integer, Long> nowPrice,
		@Nullable final Map<MovementWindow, PriceMap> baselines, @Nullable final Map<MovementWindow, LocalDate> thenDays,
		final long currencyGp)
	{
		final Map<MovementWindow, Function<Integer, Long>> resolvers = new EnumMap<>(MovementWindow.class);
		if (baselines != null)
		{
			for (final Map.Entry<MovementWindow, PriceMap> entry : baselines.entrySet())
			{
				if (entry.getKey() != null && entry.getValue() != null)
				{
					resolvers.put(entry.getKey(), resolver(entry.getValue()));
				}
			}
		}
		return summariseResolved(items, nowPrice, resolvers, thenDays, currencyGp);
	}

	/**
	 * The service's shape (see the class comment): one id-keyed resolver for "now" and one per window for "then".
	 * A window is included iff {@code thenPrices} holds a resolver for it; its day comes from {@code thenDays} and
	 * may be null.
	 *
	 * <p>The bank's cash (P1) lands in {@link PortfolioSummary#valueNow()} and on both sides of every window that
	 * covers a stack - see the class comment for the four rules.
	 *
	 * @param items      the bank; null reads as empty, null entries are skipped
	 * @param nowPrice   the guide price now for a canonical id, null or non-positive = no price
	 * @param thenPrices the baseline price for a canonical id, per window with a baseline; null reads as none
	 * @param thenDays   the day each window's baseline belongs to; null reads as none
	 * @param currencyGp the bank's coins and platinum tokens in gp ({@link BankSnapshot#currencyGp}, P1); 0 is the
	 *                   pre-P1 behaviour exactly
	 */
	public static PortfolioSummary summariseResolved(@Nullable final List<BankItem> items,
		final Function<Integer, Long> nowPrice, @Nullable final Map<MovementWindow, Function<Integer, Long>> thenPrices,
		@Nullable final Map<MovementWindow, LocalDate> thenDays, final long currencyGp)
	{
		return summariseResolved(items, nowPrice, thenPrices, null, thenDays, currencyGp, 0);
	}

	/**
	 * The same, with a "now" resolver PER WINDOW and the live-row count (addendum T, line T5). The arity above is
	 * the pre-T one, kept so every caller that knows nothing of live prices keeps compiling AND keeps its figures:
	 * it delegates with no per-window resolvers and 0 live rows, and then every window reads the same
	 * {@code nowPrice} it always did, to the gp.
	 *
	 * <p><b>Why a window needs its own "now".</b> With live prices on, a stack's two ends must come from ONE
	 * series (T4: "never live-now against guide-then"). A stack priced live today whose traded bucket for 30d ago
	 * is missing or thin therefore compares guide against guide for that window, while the card's headline still
	 * counts it at the live mid it is printing. One shared resolver cannot say both, so {@code windowNowPrices}
	 * carries the "now" each window is to compare with; a window with no resolver of its own, and an id its
	 * resolver cannot answer, both fall back to {@code nowPrice}.
	 *
	 * <p>The stacks a window COVERS are unchanged by any of this: a stack is still covered when the shared "now"
	 * prices it and that window's "then" names it, so an untradeable stack on the alch rule stays out of every
	 * window exactly as before, and a per-window "now" can only change the number, never the basis.
	 *
	 * @param windowNowPrices the "now" price for a canonical id per window, where that window's series differs
	 *                        from the headline's; null reads as none
	 * @param liveRows        how many stacks the caller priced from the live traded series ({@link
	 *                        PortfolioSummary#liveRows()}); 0 when the switch is off
	 */
	public static PortfolioSummary summariseResolved(@Nullable final List<BankItem> items,
		final Function<Integer, Long> nowPrice, @Nullable final Map<MovementWindow, Function<Integer, Long>> thenPrices,
		@Nullable final Map<MovementWindow, Function<Integer, Long>> windowNowPrices,
		@Nullable final Map<MovementWindow, LocalDate> thenDays, final long currencyGp, final int liveRows)
	{
		final long cash = Math.max(0L, currencyGp);
		final List<BankItem> stacks = items == null ? Collections.<BankItem>emptyList() : items;
		final Function<Integer, Long> now = nowPrice == null ? id -> null : nowPrice;
		final Map<MovementWindow, Function<Integer, Long>> thens =
			thenPrices == null ? Collections.<MovementWindow, Function<Integer, Long>>emptyMap() : thenPrices;
		final Map<MovementWindow, LocalDate> days = thenDays == null ? Collections.<MovementWindow, LocalDate>emptyMap() : thenDays;
		final Map<MovementWindow, Function<Integer, Long>> windowNows = windowNowPrices == null
			? Collections.<MovementWindow, Function<Integer, Long>>emptyMap()
			: windowNowPrices;

		// The "now" side once per stack, shared by the total and by every window. The cash is not one of them:
		// PortfolioSummary adds it to valueNow(), and the loop below to each window that covers something.
		// The id is boxed here too, once per stack rather than once per (window, stack): both resolvers are keyed
		// by Integer, and a five-window bank of 500 stacks would otherwise box 2,500 of them per summary.
		final int count = stacks.size();
		final Long[] nowOf = new Long[count];
		final Integer[] idOf = new Integer[count];
		long valueStacks = 0L;
		int itemsPriced = 0;
		int itemsTotal = 0;
		for (int i = 0; i < count; i++)
		{
			final BankItem item = stacks.get(i);
			if (item == null)
			{
				continue;
			}
			itemsTotal++;
			idOf[i] = item.id;
			// R2/R3: an untradeable stack RuneLite maps onto tradeable parts is priced like any other stack - the
			// caller resolves the parts SUM and answers it under this stack's own id - so it is asked for here with
			// the rest. A stack with no parts at all is not asked: nothing could answer for it.
			final Long gp = item.untradeable && !item.hasParts() ? null : priced(now.apply(idOf[i]));
			// Q5: an untradeable stack that could not be parts-priced is worth its High Alchemy value and nothing
			// else. It counts in the total and in itemsTotal, it is NOT "priced" - that word means "has a guide
			// price", and the card's "over N of M stacks" is the count of the market prices behind the figure - and
			// it enters no window, so nowOf stays null and every loop below skips it. Whether such a stack is in
			// this list at all is the "Include untradeable items" switch, decided by the caller (PriceService).
			if (gp == null && item.untradeable)
			{
				valueStacks = clampedAdd(valueStacks, MovementMath.holdingValue(item.alchPrice(), item.quantity));
				continue;
			}
			nowOf[i] = gp;
			if (gp != null)
			{
				itemsPriced++;
				valueStacks = clampedAdd(valueStacks, MovementMath.holdingValue(gp, item.quantity));
			}
		}

		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		for (final MovementWindow window : MovementWindow.values())
		{
			final Function<Integer, Long> then = thens.get(window);
			if (then == null)
			{
				continue;
			}
			final Function<Integer, Long> windowNow = windowNows.get(window);
			long coveredThen = 0L;
			long coveredNow = 0L;
			int covered = 0;
			for (int i = 0; i < count; i++)
			{
				final BankItem item = stacks.get(i);
				final Long gpNow = nowOf[i];
				if (item == null || gpNow == null)
				{
					continue;
				}
				final Long gpThen = priced(then.apply(idOf[i]));
				if (gpThen == null)
				{
					continue;
				}
				// T5: this window's own "now" where the caller has one - the guide price for a stack whose traded
				// bucket for this window was missing or thin, so the two ends are one series. The shared "now" is
				// the fallback, which is what every window read before addendum T and what every window reads with
				// the switch off.
				final Long windowNowGp = windowNow == null ? gpNow : orElse(priced(windowNow.apply(idOf[i])), gpNow);
				covered++;
				coveredThen = clampedAdd(coveredThen, MovementMath.holdingValue(gpThen, item.quantity));
				coveredNow = clampedAdd(coveredNow, MovementMath.holdingValue(windowNowGp, item.quantity));
			}
			// Both sums sit in [0, Long.MAX_VALUE], so the difference cannot overflow. It is taken BEFORE the cash
			// goes on, which is the whole of "deltaGp is unchanged by P1": the same figure sits on both sides.
			final long deltaGp = coveredNow - coveredThen;
			// The cash rides on both sides of a window that covers something, so it moves no gp and only widens the
			// base the percentage is measured against (P1). A window covering nothing keeps today's dash: with the
			// cash added, valueThen would be positive and a move of 0.0 % would be reported where none was measured.
			final long valueThen = covered == 0 ? coveredThen : clampedAdd(coveredThen, cash);
			final long valueNowCovered = covered == 0 ? coveredNow : clampedAdd(coveredNow, cash);
			final Double deltaPct = valueThen == 0L ? null : deltaGp * 100.0d / valueThen;
			moves.put(window, new WindowMove(window, days.get(window), valueThen, valueNowCovered, deltaGp, deltaPct, covered));
		}
		return new PortfolioSummary(valueStacks, itemsPriced, itemsTotal, moves, cash, liveRows);
	}

	/** {@code value}, or {@code fallback} when it is null - the per-window "now" rule, spelled once. */
	@Nullable
	private static Long orElse(@Nullable final Long value, @Nullable final Long fallback)
	{
		return value == null ? fallback : value;
	}

	/** A projected map as an id-keyed resolver: the point's {@link PricePoint#mid()}, or null when the map lacks the id. */
	public static Function<Integer, Long> resolver(final PriceMap map)
	{
		final PriceMap used = map == null ? PriceMap.EMPTY : map;
		return id ->
		{
			final PricePoint point = id == null ? null : used.get(id);
			return point == null ? null : point.mid();
		};
	}

	/**
	 * {@code a + b} clamped at {@link Long#MAX_VALUE} rather than wrapped (M1). Both operands are sums or holdings
	 * and never negative, so only the upper bound can be crossed.
	 */
	public static long clampedAdd(final long a, final long b)
	{
		try
		{
			return Math.addExact(a, b);
		}
		catch (final ArithmeticException overflow)
		{
			return Long.MAX_VALUE;
		}
	}

	/** A guide price that counts: present and positive (L1: 0 is "no price"); anything else is null. */
	@Nullable
	private static Long priced(@Nullable final Long gp)
	{
		return gp == null || gp <= 0L ? null : gp;
	}
}
