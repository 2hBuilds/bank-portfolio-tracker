package com.bankpricemovement;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The five view switches, carried as one immutable value the way {@link HeroVisibility} carries the card's three
 * figures: the three of addendum Q - whether coins and platinum tokens count in the bank value ({@code countCash},
 * config item {@code countCash}, default on), whether untradeable stacks are listed and counted at their High
 * Alchemy value ({@code countUntradeables}, default off), and whether a row prints the worth of the whole stack
 * instead of the unit price ({@code holdingOnRows}, default off) - addendum T's fourth, whether an actively
 * traded item is priced from the wiki's live traded series ({@code livePrices}, default ON) - and addendum Y's
 * fifth, whether the stacks the player is CARRYING and WEARING are counted and listed beside the bank's
 * ({@code countInventory}, default ON). The gear menu on the hero card, RuneLite's settings page and the dev verb
 * {@code opt=} all read and write the same five switches through the plugin's config.
 */
public final class ViewOptions
{
	/**
	 * Every switch at its default: cash counted, untradeables left out, unit prices on the rows, live prices on,
	 * the inventory and worn gear counted.
	 */
	public static final ViewOptions DEFAULT = new ViewOptions(true, false, false, true, true);

	private final boolean countCash;
	private final boolean countUntradeables;
	private final boolean holdingOnRows;
	private final boolean livePrices;
	private final boolean countInventory;

	/** The three addendum-Q switches with {@code livePrices} and {@code countInventory} at their defaults (on). */
	public ViewOptions(final boolean countCash, final boolean countUntradeables, final boolean holdingOnRows)
	{
		this(countCash, countUntradeables, holdingOnRows, true);
	}

	/** The four pre-Y switches with {@code countInventory} at its default (on). */
	public ViewOptions(final boolean countCash, final boolean countUntradeables, final boolean holdingOnRows,
		final boolean livePrices)
	{
		this(countCash, countUntradeables, holdingOnRows, livePrices, true);
	}

	public ViewOptions(final boolean countCash, final boolean countUntradeables, final boolean holdingOnRows,
		final boolean livePrices, final boolean countInventory)
	{
		this.countCash = countCash;
		this.countUntradeables = countUntradeables;
		this.holdingOnRows = holdingOnRows;
		this.livePrices = livePrices;
		this.countInventory = countInventory;
	}

	/** Coins and platinum tokens (1,000 gp each) count in the bank value and in the percentage's basis. */
	public boolean countCash()
	{
		return countCash;
	}

	/** Untradeable stacks are listed and counted at their High Alchemy value, with no movement figures. */
	public boolean countUntradeables()
	{
		return countUntradeables;
	}

	/** A row prints {@code unit x quantity} and the stack's gp change instead of the unit price and change. */
	public boolean holdingOnRows()
	{
		return holdingOnRows;
	}

	/**
	 * Actively traded items use the wiki's live traded prices for every figure (addendum T); thin items keep the daily
	 * guide price. Off means exactly the guide-only behaviour of addenda K to S.
	 */
	public boolean livePrices()
	{
		return livePrices;
	}

	/**
	 * The stacks in the player's INVENTORY and on their WORN gear count in the bank value and are listed beside the
	 * bank's, one row per item with the quantities added (addendum Y, lines Y1 to Y3). Off means exactly the
	 * bank-only behaviour of addenda K to W, figure for figure.
	 *
	 * <p>Both containers are read at the two moments the bank itself is read - a bank container event and Refresh -
	 * so what the sidebar shows is what the player was carrying then (Y2).
	 */
	public boolean countInventory()
	{
		return countInventory;
	}

	public ViewOptions withCountCash(final boolean value)
	{
		return new ViewOptions(value, countUntradeables, holdingOnRows, livePrices, countInventory);
	}

	public ViewOptions withCountUntradeables(final boolean value)
	{
		return new ViewOptions(countCash, value, holdingOnRows, livePrices, countInventory);
	}

	public ViewOptions withHoldingOnRows(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, value, livePrices, countInventory);
	}

	public ViewOptions withLivePrices(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, holdingOnRows, value, countInventory);
	}

	public ViewOptions withCountInventory(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, holdingOnRows, livePrices, value);
	}

	/**
	 * The switches in the order the dev bridge prints them: {@code cash}, {@code untradeables}, {@code holding},
	 * {@code live}, {@code inventory}.
	 */
	public LinkedHashMap<String, Boolean> asMap()
	{
		final LinkedHashMap<String, Boolean> map = new LinkedHashMap<>();
		map.put("cash", countCash);
		map.put("untradeables", countUntradeables);
		map.put("holding", holdingOnRows);
		map.put("live", livePrices);
		map.put("inventory", countInventory);
		return map;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ViewOptions))
		{
			return false;
		}
		final ViewOptions other = (ViewOptions) o;
		return countCash == other.countCash
			&& countUntradeables == other.countUntradeables
			&& holdingOnRows == other.holdingOnRows
			&& livePrices == other.livePrices
			&& countInventory == other.countInventory;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(countCash, countUntradeables, holdingOnRows, livePrices, countInventory);
	}

	@Override
	public String toString()
	{
		return "ViewOptions{cash=" + countCash + ", untradeables=" + countUntradeables + ", holding=" + holdingOnRows
			+ ", live=" + livePrices + ", inventory=" + countInventory + '}';
	}
}
