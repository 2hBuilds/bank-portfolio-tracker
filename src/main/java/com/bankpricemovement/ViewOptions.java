package com.bankpricemovement;

import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * The six view switches, carried as one immutable value the way {@link HeroVisibility} carries the card's three
 * figures: the two addendum Q still has - whether coins and platinum tokens count in the bank value
 * ({@code countCash}, config item {@code countCash}, default on) and whether untradeable stacks are listed and
 * counted at their High Alchemy value when they have no tradeable parts ({@code countUntradeables}, default off;
 * narrowed by addendum AV) - addendum T's, whether an actively
 * traded item is priced from the wiki's live traded series ({@code livePrices}, default ON) - addendum Y's,
 * whether the stacks the player is CARRYING and WEARING are counted and listed beside the bank's
 * ({@code countInventory}, default ON) - 1.0.9 part 3's, whether what the player has in the Grand Exchange's
 * offers is counted and listed beside them ({@code countGrandExchange}, default ON) - and addendum AH's, whether
 * the sidebar's DATA hovers are shown at all ({@code showHoverText}, default OFF). The settings menu on the hero
 * card, RuneLite's settings page and the dev verb {@code opt=} all read and write the same six switches through
 * the plugin's config.
 *
 * <p><b>Addendum AO line AO1 took the sixth away</b>: {@code holdingOnRows}, addendum Q's third, which chose
 * whether a row printed the per-ITEM reading or the per-STACK one. Addendum AN's three-line row prints BOTH - line
 * 2 is the stack, line 3 is one item - so the switch no longer reached anything drawn, and the user, asked whether
 * to keep it, answered "if it doesnt do anything anymore then remove it". The one thing it still did was pick
 * {@link SortMode#GP_MOVE}'s comparison key, which is the STACK's gp move permanently now.
 */
public final class ViewOptions
{
	/**
	 * Every switch at its default: cash counted, untradeables left out, live prices on, the inventory and worn
	 * gear counted, the Grand Exchange offers counted, and the data hovers OFF (addendum AH - the one switch here
	 * whose default is the quieter sidebar rather than the fuller one).
	 */
	public static final ViewOptions DEFAULT = new ViewOptions(true, false, true, true, true, false);

	private final boolean countCash;
	private final boolean countUntradeables;
	private final boolean livePrices;
	private final boolean countInventory;
	private final boolean countGrandExchange;
	private final boolean showHoverText;

	/**
	 * The one constructor, and since addendum AO the ONLY one: this class carried a ladder of shorter overloads
	 * that defaulted the switches added after them, and every rung of it became a trap the moment a field was
	 * removed from the MIDDLE of the list. Five booleans that used to mean
	 * {@code (cash, untradeables, holding, live, inventory)} would still compile against a five-argument
	 * {@code (cash, untradeables, live, inventory, hover)} and mean three different things, with no error anywhere
	 * to say so. Deleting the ladder makes the compiler name every call site instead. 1.0.9 part 3 added the
	 * Grand Exchange switch between the inventory's and the hover's and kept to the rule: the one constructor grew,
	 * no overload was added, and every call site was rewritten.
	 */
	public ViewOptions(final boolean countCash, final boolean countUntradeables, final boolean livePrices,
		final boolean countInventory, final boolean countGrandExchange, final boolean showHoverText)
	{
		this.countCash = countCash;
		this.countUntradeables = countUntradeables;
		this.livePrices = livePrices;
		this.countInventory = countInventory;
		this.countGrandExchange = countGrandExchange;
		this.showHoverText = showHoverText;
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

	/**
	 * The items and coins in the player's eight Grand Exchange offers count in the bank value and are listed beside
	 * the bank's stacks, one row per item with the quantities added (1.0.9 part 3). Off means exactly the behaviour
	 * without them, figure for figure.
	 *
	 * <p>The offers are read at the moments the inventory and the worn gear are read - a bank event, the bank's
	 * close, Refresh - and never when an offer changes, so an offer that fills with the bank closed shows at the
	 * next of those, not at once.
	 */
	public boolean countGrandExchange()
	{
		return countGrandExchange;
	}

	/**
	 * Whether the sidebar shows hover text at all (addendum AH, reach narrowed by AI and AJ). Default OFF, and
	 * the only switch here whose default is the quieter sidebar: every figure is already drawn, so a hover that
	 * opens whenever the pointer crosses one is something a reader asks for rather than something they dismiss.
	 *
	 * <p><b>What it reaches.</b> The bank value's hover on the hero card, and every CONTROL's - the sort button
	 * and its menu, the chips, the band button, Refresh, the update line, the gear's own items, the buttons,
	 * and the four labels {@code Widgets.setFitted} gives a full-text hover to when it has to cut them. AH1
	 * spared the controls and AH3 overruled that on the user's word: "make sure there is no hover text at all
	 * unless it is on".
	 *
	 * <p><b>What it does not reach: the item ROWS.</b> Since addendum AI a row carries no tooltip at any
	 * setting - its description is the block the cell opens when it is clicked - so there is nothing there for
	 * this switch to silence, and wiring it into a row would be a regression rather than a feature.
	 */
	public boolean showHoverText()
	{
		return showHoverText;
	}

	public ViewOptions withCountCash(final boolean value)
	{
		return new ViewOptions(value, countUntradeables, livePrices, countInventory, countGrandExchange,
			showHoverText);
	}

	public ViewOptions withCountUntradeables(final boolean value)
	{
		return new ViewOptions(countCash, value, livePrices, countInventory, countGrandExchange,
			showHoverText);
	}

	public ViewOptions withLivePrices(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, value, countInventory, countGrandExchange,
			showHoverText);
	}

	public ViewOptions withCountInventory(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, livePrices, value, countGrandExchange, showHoverText);
	}

	public ViewOptions withCountGrandExchange(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, livePrices, countInventory, value, showHoverText);
	}

	public ViewOptions withShowHoverText(final boolean value)
	{
		return new ViewOptions(countCash, countUntradeables, livePrices, countInventory, countGrandExchange, value);
	}

	/**
	 * The switches in the order the dev bridge prints them: {@code cash}, {@code untradeables}, {@code live},
	 * {@code inventory}, {@code ge}, {@code hover}. Addendum AO line AO1 removed {@code holding} from between the
	 * second and the third with the switch it echoed; 1.0.9 part 3 put {@code ge} directly after {@code inventory}.
	 */
	public LinkedHashMap<String, Boolean> asMap()
	{
		final LinkedHashMap<String, Boolean> map = new LinkedHashMap<>();
		map.put("cash", countCash);
		map.put("untradeables", countUntradeables);
		map.put("live", livePrices);
		map.put("inventory", countInventory);
		map.put("ge", countGrandExchange);
		map.put("hover", showHoverText);
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
			&& livePrices == other.livePrices
			&& countInventory == other.countInventory
			&& countGrandExchange == other.countGrandExchange
			&& showHoverText == other.showHoverText;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(countCash, countUntradeables, livePrices, countInventory, countGrandExchange,
			showHoverText);
	}

	@Override
	public String toString()
	{
		return "ViewOptions{cash=" + countCash + ", untradeables=" + countUntradeables + ", live=" + livePrices
			+ ", inventory=" + countInventory + ", ge=" + countGrandExchange + ", hover=" + showHoverText + '}';
	}
}
