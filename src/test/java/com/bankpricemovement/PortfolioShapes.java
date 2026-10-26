package com.bankpricemovement;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The convenience shapes of {@link PortfolioMath} that only the tests called (release 1.2.2, the trim): the five-argument
 * {@code summariseResolved} (no per-window "now" and no live rows, which is every caller that predates addendum T), the
 * contract's literal shape over projected {@link PriceMap}s ({@code summarise}) and the resolver that reads a map's
 * {@link PricePoint#mid()}. Each one delegates to the shipped seven-argument method with exactly what the old
 * overload passed, so a figure here is the figure the shipped code answers.
 */
final class PortfolioShapes
{
	private PortfolioShapes()
	{
	}

	/** {@link PortfolioMath#summariseResolved} with no per-window "now" resolvers and 0 live rows. */
	static PortfolioSummary summariseResolved(List<BankItem> items, Function<Integer, Long> nowPrice,
		Map<MovementWindow, Function<Integer, Long>> thenPrices, Map<MovementWindow, LocalDate> thenDays, long currencyGp)
	{
		return PortfolioMath.summariseResolved(items, nowPrice, thenPrices, null, thenDays, currencyGp, 0);
	}

	/**
	 * The baselines as the projected {@link PriceMap}s, each window's "then" for an id being that map's point (its mid);
	 * a window is included when it has a map. No name fallback is applied.
	 */
	static PortfolioSummary summarise(List<BankItem> items, Function<Integer, Long> nowPrice,
		Map<MovementWindow, PriceMap> baselines, Map<MovementWindow, LocalDate> thenDays, long currencyGp)
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

	/** A projected map as an id-keyed resolver: the point's mid, or null when the map lacks the id. */
	static Function<Integer, Long> resolver(PriceMap map)
	{
		final PriceMap used = map == null ? PriceMap.EMPTY : map;
		return id ->
		{
			final PricePoint point = id == null ? null : used.get(id);
			return point == null ? null : point.mid();
		};
	}
}
