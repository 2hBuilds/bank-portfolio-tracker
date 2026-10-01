package com.bankpricemovement;

import java.util.Arrays;
import javax.annotation.Nullable;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;

/**
 * An immutable COPY of the player's Grand Exchange offers at one moment (1.0.9 part 3): the six figures of each
 * slot - the item, the state, the quantity asked for, the quantity filled, the price asked and the coins spent - and
 * what they mean for the player's worth. The user banked, logged out with about 50m sitting in offers, and the day's
 * reading was 50m short: what is in an offer belongs to the player and the plugin counted it nowhere.
 *
 * <p><b>Why a copy.</b> {@code Client.getGrandExchangeOffers()} answers the client's own live objects, which the
 * client changes in place on every fill. A plugin that kept one would see a later offer event through an old read,
 * and a snapshot built from it would change under the thread that was publishing it. {@link #of} therefore copies
 * the six figures of every slot into this class's own arrays and holds no {@link GrandExchangeOffer} at all: a copy
 * taken at one read never changes under a later event, and can be handed to the executor and the EDT as it is.
 * {@code of} must run on the client thread, where the offers are consistent; everything else here can run anywhere.
 *
 * <p><b>What an offer holds, by state</b> (counted at the item's price, like the inventory's stacks):
 * <ul>
 * <li>A SELL offer - {@code SELLING}, {@code SOLD}, {@code CANCELLED_SELL} - holds the items not yet sold (still the
 * player's, in the offer's slot) and the coins received so far and not yet collected.</li>
 * <li>A BUY offer - {@code BUYING}, {@code BOUGHT}, {@code CANCELLED_BUY} - holds the items bought and not yet
 * collected, and the coins still committed to the part not yet filled. Those coins are {@code price x total - spent}:
 * they include the change a fill below the asked price hands back, because the client's {@code spent} is what the
 * fills actually cost.</li>
 * <li>An {@code EMPTY} slot holds nothing.</li>
 * </ul>
 * A figure that reads below zero reads as zero, and a filled quantity above the total reads as a full fill.
 *
 * <p><b>An open question, written down rather than guessed.</b> The figures follow what the client reports for the
 * slot at the read. What {@code spent} reads after the player collects only PART of a sell offer that is still
 * {@code SELLING} is not known from source: if the client zeroes it on a collect, coins already in the player's
 * pocket are not counted twice; if it does not, they are. The local look settles it (collect half, press Refresh,
 * compare), and no correction is applied here until it has.
 */
public final class ExchangeOffers
{
	/** No offers at all - what a read with no client, no array or only empty slots answers. */
	public static final ExchangeOffers EMPTY = new ExchangeOffers(new int[0], new GrandExchangeOfferState[0],
		new int[0], new int[0], new long[0], new long[0]);

	private final int[] itemIds;
	private final GrandExchangeOfferState[] states;
	private final int[] totalQuantities;
	private final int[] quantitiesSold;
	private final long[] prices;
	private final long[] spent;

	private ExchangeOffers(final int[] itemIds, final GrandExchangeOfferState[] states, final int[] totalQuantities,
		final int[] quantitiesSold, final long[] prices, final long[] spent)
	{
		this.itemIds = itemIds;
		this.states = states;
		this.totalQuantities = totalQuantities;
		this.quantitiesSold = quantitiesSold;
		this.prices = prices;
		this.spent = spent;
	}

	/**
	 * Copies the client's offers. CLIENT THREAD.
	 *
	 * <p>Null, an empty array, or an array whose every slot is null or {@code EMPTY} answers {@link #EMPTY}. A null
	 * slot or a slot with no state reads as {@code EMPTY}, and so does a slot whose item id is 0 or below - it has
	 * nothing to name a stack by. Every figure is clamped at zero as it is copied, and a filled quantity above the
	 * total is cut to the total, so everything this class answers afterwards is arithmetic over plain numbers.
	 *
	 * @param offers the client's offers ({@code Client.getGrandExchangeOffers()}), any of whose slots may be null
	 */
	public static ExchangeOffers of(@Nullable final GrandExchangeOffer[] offers)
	{
		if (offers == null || offers.length == 0)
		{
			return EMPTY;
		}

		final int n = offers.length;
		final int[] itemIds = new int[n];
		final GrandExchangeOfferState[] states = new GrandExchangeOfferState[n];
		final int[] totals = new int[n];
		final int[] sold = new int[n];
		final long[] prices = new long[n];
		final long[] spent = new long[n];
		boolean any = false;
		for (int i = 0; i < n; i++)
		{
			states[i] = GrandExchangeOfferState.EMPTY;
			final GrandExchangeOffer offer = offers[i];
			if (offer == null)
			{
				continue;
			}

			final GrandExchangeOfferState state = offer.getState();
			final int itemId = offer.getItemId();
			if (state == null || state == GrandExchangeOfferState.EMPTY || itemId <= 0)
			{
				continue;
			}

			any = true;
			itemIds[i] = itemId;
			states[i] = state;
			totals[i] = Math.max(0, offer.getTotalQuantity());
			sold[i] = Math.min(totals[i], Math.max(0, offer.getQuantitySold()));
			prices[i] = Math.max(0L, offer.getPrice());
			spent[i] = Math.max(0L, offer.getSpent());
		}

		return any ? new ExchangeOffers(itemIds, states, totals, sold, prices, spent) : EMPTY;
	}

	/**
	 * The items the offers hold, one {@link Item} per slot whose quantity is positive, in slot order: for a sell
	 * offer the quantity not yet sold, for a buy offer the quantity bought and not yet collected. Two slots of one
	 * item are two items here; the reader folds them into one stack, by the same rules it folds the inventory's.
	 *
	 * <p>A fresh array on every call, so a caller can keep it and nothing else sees it change.
	 */
	public Item[] items()
	{
		final Item[] out = new Item[itemIds.length];
		int count = 0;
		for (int i = 0; i < itemIds.length; i++)
		{
			final int quantity = itemQuantity(i);
			if (quantity > 0)
			{
				out[count++] = new Item(itemIds[i], quantity);
			}
		}

		return count == out.length ? out : Arrays.copyOf(out, count);
	}

	/**
	 * The coins the offers hold, in gp: the coins received by sell offers and not yet collected, plus the coins still
	 * committed to buy offers. Each slot's share is clamped at zero and the slots are summed with
	 * {@link PortfolioMath#clampedAdd}, so a figure this plugin cannot make sense of can only ever read low, never
	 * wrap.
	 */
	public long cashGp()
	{
		long sum = 0L;
		for (int i = 0; i < itemIds.length; i++)
		{
			sum = PortfolioMath.clampedAdd(sum, slotCash(i));
		}

		return sum;
	}

	/** True when the offers hold no item and no coin at all. */
	public boolean isEmpty()
	{
		return cashGp() <= 0L && items().length == 0;
	}

	/** How many of the slots are not empty, for the diagnostics line and the Refresh note. */
	public int slotsInUse()
	{
		int count = 0;
		for (final GrandExchangeOfferState state : states)
		{
			if (state != GrandExchangeOfferState.EMPTY)
			{
				count++;
			}
		}

		return count;
	}

	/** The items slot {@code i} holds; 0 for an empty slot. */
	private int itemQuantity(final int i)
	{
		switch (states[i])
		{
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return totalQuantities[i] - quantitiesSold[i];
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return quantitiesSold[i];
			default:
				return 0;
		}
	}

	/** The coins slot {@code i} holds, never below zero; 0 for an empty slot. */
	private long slotCash(final int i)
	{
		switch (states[i])
		{
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return spent[i];
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return Math.max(0L, asked(prices[i], totalQuantities[i]) - spent[i]);
			default:
				return 0L;
		}
	}

	/** {@code price x quantity}, clamped at {@link Long#MAX_VALUE} instead of wrapping. */
	private static long asked(final long price, final int quantity)
	{
		if (price <= 0L || quantity <= 0)
		{
			return 0L;
		}

		return quantity > Long.MAX_VALUE / price ? Long.MAX_VALUE : price * quantity;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof ExchangeOffers))
		{
			return false;
		}

		final ExchangeOffers other = (ExchangeOffers) o;
		return Arrays.equals(itemIds, other.itemIds)
			&& Arrays.equals(states, other.states)
			&& Arrays.equals(totalQuantities, other.totalQuantities)
			&& Arrays.equals(quantitiesSold, other.quantitiesSold)
			&& Arrays.equals(prices, other.prices)
			&& Arrays.equals(spent, other.spent);
	}

	@Override
	public int hashCode()
	{
		int h = Arrays.hashCode(itemIds);
		h = 31 * h + Arrays.hashCode(states);
		h = 31 * h + Arrays.hashCode(totalQuantities);
		h = 31 * h + Arrays.hashCode(quantitiesSold);
		h = 31 * h + Arrays.hashCode(prices);
		return 31 * h + Arrays.hashCode(spent);
	}

	/** Counts only - slots in use, items, gp - and never an item id. */
	@Override
	public String toString()
	{
		return "ExchangeOffers{slots=" + slotsInUse() + ", items=" + items().length + ", gp=" + cashGp() + '}';
	}
}
