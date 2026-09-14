package com.bankpricemovement;

import java.util.Objects;

/**
 * One item's price at one moment, as two nullable sides (contract C3).
 *
 * <p><b>What the sides hold today.</b> Since addendum K the plugin has ONE price per item per day - the Jagex
 * GUIDE price - so both sides carry the SAME number ({@code PriceService.project} and its {@code guidePoint}
 * helper build {@code new PricePoint(gp, gp)}) and {@link #mid()} hands that price back unchanged. The two-sided
 * shape is the pre-addendum-K TRADE point - the instant-buy ("high") and instant-sell ("low") of the wiki's
 * {@code /latest}, or an hour's {@code avgHighPrice} / {@code avgLowPrice} from {@code /1h} - and it is kept
 * because it is the on-disk shape of every stored {@code baseline-*.json} ({@link PriceMapDto}), which a
 * relaunch still has to read.
 *
 * <p>Both sides are {@link Long} and either may be NULL. Pre-addendum-K evidence, kept for the DTO's
 * back-compatibility: in the live {@code /1h} bucket measured on 2026-09-08 about 20 % of entries had no
 * {@code avgHighPrice} and about 16 % no {@code avgLowPrice} - roughly 36 % single-sided - while NO entry had
 * both sides null (research C10). A both-null point is therefore a defensive case rather than an observed one,
 * and it reads as "no price" ({@link #isEmpty()}), never as zero.
 *
 * <p>Prices may exceed {@code int} (research: "prices may exceed int"), which is why every side is a long.
 */
public final class PricePoint
{
	/**
	 * The "no price on either side" point. Shared because the class is immutable.
	 */
	public static final PricePoint EMPTY = new PricePoint(null, null);

	private final Long high;
	private final Long low;

	public PricePoint(final Long high, final Long low)
	{
		this.high = high;
		this.low = low;
	}

	/**
	 * Since addendum K: the item's one guide price, the same number {@link #low()} carries. Before it: the
	 * instant-buy side ({@code high} / {@code avgHighPrice}), which a stored baseline may still hold. Null when
	 * no price was given.
	 */
	public Long high()
	{
		return high;
	}

	/**
	 * Since addendum K: the item's one guide price, the same number {@link #high()} carries. Before it: the
	 * instant-sell side ({@code low} / {@code avgLowPrice}), which a stored baseline may still hold. Null when
	 * no price was given.
	 */
	public Long low()
	{
		return low;
	}

	/**
	 * The one number a row shows: the rounded mean of the two sides when both are present, the side that IS
	 * present when only one is (the symmetric single-sided rule of decision D3 - either side can be the
	 * missing one), and null when neither is.
	 *
	 * <p>Rounding is half-up and the arithmetic is done in {@code double} halves ({@code high / 2.0 + low / 2.0})
	 * so that a pair near {@link Long#MAX_VALUE} cannot overflow the way {@code (high + low) / 2} would; every
	 * real price is far below 2^53, where a double is exact.
	 *
	 * @return the mid price, or null when the point {@link #isEmpty()}
	 */
	public Long mid()
	{
		if (high == null)
		{
			return low;
		}

		if (low == null)
		{
			return high;
		}

		return Math.round(high / 2.0d + low / 2.0d);
	}

	/**
	 * True when the wiki gave neither side, so this point names no price at all.
	 */
	public boolean isEmpty()
	{
		return high == null && low == null;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof PricePoint))
		{
			return false;
		}

		final PricePoint other = (PricePoint) o;
		return Objects.equals(high, other.high) && Objects.equals(low, other.low);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(high, low);
	}

	@Override
	public String toString()
	{
		return "PricePoint{high=" + high + ", low=" + low + '}';
	}
}
