package com.bankpricemovement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * One line of the sidebar: a bank stack with its guide price now, its guide price at the baseline revision,
 * and the move between them (contract C6, sources per addendum K line K6). Immutable, because a row list is
 * computed on the service's executor and then handed to the EDT to draw - a value the panel can hold without
 * a lock.
 *
 * <p>Every price is a nullable {@link Long} rather than a primitive, and that is the whole point of the class:
 * "no price" and "price of zero" are different answers. A bank item whose name is in neither the wiki's
 * id-to-name table nor the guide table has no baseline, so a null {@link #thenPrice()} is ordinary, renders as
 * "-" and sorts LAST under every sort (design D6, contract C9).
 *
 * <p>Since addendum Y line Y3 a row is not only the bank's: with "Include inventory and worn items" on, one row
 * carries the whole of what the player owns of that item - {@link #quantity()} is the bank's, the inventory's and
 * the worn gear's added - and {@link #bankQuantity()}, {@link #inventoryQuantity()} and {@link #wornQuantity()} say
 * where it is, for the one hover line that names the parts ("3 in bank, 1 in inventory, 1 worn"). All three are 0
 * with the switch off, which is what makes such a row byte-identical to the pre-Y one. Since 1.0.9 part 3 the row
 * can hold a fourth part, the items in the player's Grand Exchange offers ({@link #exchangeQuantity()}, "2 in the
 * Grand Exchange"), added into {@link #quantity()} while "Include Grand Exchange offers" is on and 0 otherwise.
 *
 * <p>{@link #deltaGp()} and {@link #deltaPct()} are set together or not at all, and only for a GUIDE row with
 * both ends present (contract C8 as rewritten by K13) - or, since addendum R, for a {@link PriceSource#PARTS} row,
 * whose two ends are the same two sums over the same guide series: both ends are then the same Jagex series -
 * RuneLite's copy of the guide table now, the wiki's copy of it at the baseline revision - so the difference is
 * exactly the "today" figure the GE web site shows.
 */
public final class MovementRow
{
	/**
	 * Where {@link MovementRow#unitPrice()} came from, which the row shows so a reader always knows which
	 * series a number belongs to.
	 */
	public enum PriceSource
	{
		/**
		 * The Jagex Grand Exchange GUIDE price - every priced row since addendum K. Normally RuneLite's own table
		 * ({@code ItemManager.getItemPriceWithSource(id, false)}, K1); for the handful of ids RuneLite rewrites
		 * through {@code ItemMapping} - Black mask (10), Ring of wealth (5), the two Crystal 2h axes - and for
		 * L3's degraded mode, the newest guide TABLE from the wiki instead, which is the same Jagex series
		 * (see {@code PriceService}'s class javadoc). Either way it is the guide price of THIS item.
		 */
		GUIDE,
		/**
		 * A price from the OSRS Wiki's TRADED series - since contract 1.2.0 (L4) the graded figure's mark: yesterday's
		 * two-sided average moved by the last 24 hours' same-side move ({@link GradedMove#price()}) - or, for a figure
		 * reported on one side only, that side's own price - or yesterday's average when nothing traded in the last 24
		 * hours - for a stack with a 1d figure while the {@code livePrices} switch was on. Addendum T's
		 * five liquidity checks no longer decide this; how far the figure can be trusted is its {@link Grade}
		 * ({@link MovementRow#graded()}).
		 *
		 * <p>A different series from {@link #GUIDE}, and the only one in this plugin that moves between two Jagex
		 * days. A LIVE row paints exactly as a GUIDE row does (T6) and carries a real {@link MovementRow#thenPrice()},
		 * {@link MovementRow#deltaGp()} and {@link MovementRow#deltaPct()}; the day its figure compared against is
		 * {@link MovementRow#windowDay(MovementWindow)}, and the figure itself is {@link MovementRow#graded()}.
		 *
		 * <p>Never produced while the switch is off: that is exactly the guide-only behaviour of addenda K to S.
		 */
		LIVE,
		/**
		 * The item's HIGH ALCHEMY value ({@code ItemComposition.getHaPrice()}, carried on the stack as
		 * {@link BankItem#haPrice}) - the only price an item the Grand Exchange does not list has (addendum Q,
		 * line Q5). Produced whatever "Include alch-only untradeables" says since 1.1.0 part G - the switch now decides only
		 * whether the stack is in the bank value; the list shows the row by its own tick - and never with a
		 * baseline: an alch value is a constant of the item, not a series, so such a row has no
		 * {@link MovementRow#thenPrice()}, {@link MovementRow#deltaGp()} or {@link MovementRow#deltaPct()} and the
		 * panel prints a small grey "alch" tag where the move would go.
		 */
		ALCH,
		/**
		 * The sum of what the item's TRADEABLE PARTS are worth at the guide prices - three Crystal armour seeds for
		 * a Crystal body, marks of grace for a graceful piece - as RuneLite's {@code ItemMapping} names them
		 * (addendum R, lines R1 and R2; the parts themselves are on {@link MovementRow#parts()}).
		 *
		 * <p>A guide price like any other, only summed: every part's "now" comes from the same rule a
		 * {@link #GUIDE} row's does and every part's "then" from the same baseline, so such a row carries a real
		 * {@link MovementRow#thenPrice()}, {@link MovementRow#deltaGp()} and {@link MovementRow#deltaPct()} and is
		 * painted exactly as a GUIDE row is (R4). The "then" is null - the dash - when ANY part has no baseline
		 * value, because a sum of some of the parts is not the item's price. Produced whatever the switches say since
		 * addendum AV, only for a stack whose every part could be priced; one that could not falls to {@link #ALCH}.
		 */
		PARTS,
		/**
		 * The OSRS Wiki real-time {@code /latest} mid. Legacy (pre-addendum-K): the service no longer produces
		 * it; kept while the panel and the bridge of the first build still name it (K6 lets it go once nothing
		 * references it).
		 */
		WIKI,
		/**
		 * {@code ItemManager.getItemPrice} as a FALLBACK with no history behind it. Legacy (pre-addendum-K):
		 * only {@code MovementMath.row}'s fallback parameter can still produce it, and the service passes 0 there
		 * since K1 made RuneLite's price the primary series.
		 */
		RUNELITE,
		/**
		 * No price at all.
		 */
		NONE
	}

	private final int id;
	private final String name;
	private final int quantity;
	private final boolean stackable;
	private final Long unitPrice;
	private final Long thenPrice;
	private final Long deltaGp;
	private final Double deltaPct;
	private final long holdingValue;
	private final PriceSource source;
	@Nullable
	private final List<BankItem.Part> parts;
	/**
	 * Which calendar DAY each window's "then" was actually taken from (addendum U, line U3) - the traded bucket's
	 * own day for a live window, that window's guide baseline day for one that fell back - for a row the traded
	 * prices touched; null on every row computed with the switch off, and read through
	 * {@link #windowDay(MovementWindow)} rather than directly.
	 */
	@Nullable
	private final Map<MovementWindow, LocalDate> windowDays;
	/**
	 * Where the {@link #quantity} is (addendum Y, line Y3): how many of it the BANK holds, how many are in the
	 * INVENTORY, how many are WORN, and - since 1.0.9 part 3 - how many are in the Grand Exchange OFFERS. All four are
	 * 0 on every row computed with neither "Include inventory and worn items" nor "Include Grand Exchange offers" on -
	 * which is what keeps such a row byte-identical to the pre-Y one - and they add up to {@link #quantity} while
	 * either is.
	 */
	private final int bankQuantity;
	private final int inventoryQuantity;
	private final int wornQuantity;
	private final int exchangeQuantity;
	/**
	 * The graded live figure of the window this row was built for (contract 1.2.0, L2-L4) - its grade, its word and
	 * every number behind it - or null on a row the traded feeds did not grade: every row computed with live prices
	 * off or without a usable snapshot, and every alch row. Read through {@link #graded()}.
	 */
	@Nullable
	private final GradedMove graded;

	/**
	 * @param id           canonical item id
	 * @param name         item name; null becomes "" so no renderer has to null-check
	 * @param quantity     how many the bank holds
	 * @param stackable    whether the icon draws a stack number
	 * @param unitPrice    the price of ONE, or null when there is none
	 * @param thenPrice    the price of one at the baseline revision, or null when no table names the item
	 * @param deltaGp      unit price change, or null when no move can be computed
	 * @param deltaPct     the same change as a percentage of {@code thenPrice}, or null
	 * @param holdingValue unit price times quantity ({@link MovementMath#holdingValue}), 0 when there is no price
	 * @param source       where {@code unitPrice} came from; null becomes {@link PriceSource#NONE}
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source)
	{
		this(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue, source, null, null,
			0, 0, 0, 0, null);
	}

	/**
	 * The full form: the ten above, then
	 *
	 * @param parts             the tradeable parts the price was summed from (addendum R, line R2), or null for any
	 *                          other row; copied into an unmodifiable list, and an empty list is stored as null - a
	 *                          row cannot be "valued as its parts" and name none
	 * @param windowDays        the calendar DAY each window's "then" was actually taken from (addendum U, line U3);
	 *                          copied into an unmodifiable {@link EnumMap}, null keys or values dropped, and an empty
	 *                          map stored as null
	 * @param bankQuantity      how many of {@code quantity} the bank holds (addendum Y, line Y3); 0 while the
	 *                          inventory and Grand Exchange switches are off
	 * @param inventoryQuantity how many are in the inventory; 0 while the switch is off
	 * @param wornQuantity      how many are worn; 0 while the switch is off
	 * @param exchangeQuantity  how many are in the Grand Exchange offers (1.0.9 part 3); 0 while the switch is off
	 * @param graded            the graded live figure of the row's window (contract 1.2.0, L2-L4), or null for an
	 *                          ungraded row
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source, @Nullable final List<BankItem.Part> parts,
		@Nullable final Map<MovementWindow, LocalDate> windowDays, final int bankQuantity,
		final int inventoryQuantity, final int wornQuantity, final int exchangeQuantity,
		@Nullable final GradedMove graded)
	{
		this.id = id;
		this.name = name == null ? "" : name;
		this.quantity = quantity;
		this.stackable = stackable;
		this.unitPrice = unitPrice;
		this.thenPrice = thenPrice;
		this.deltaGp = deltaGp;
		this.deltaPct = deltaPct;
		this.holdingValue = holdingValue;
		this.source = source == null ? PriceSource.NONE : source;
		this.parts = parts == null || parts.isEmpty()
			? null
			: Collections.unmodifiableList(new ArrayList<>(parts));
		this.windowDays = copyByWindow(windowDays);
		this.bankQuantity = Math.max(0, bankQuantity);
		this.inventoryQuantity = Math.max(0, inventoryQuantity);
		this.wornQuantity = Math.max(0, wornQuantity);
		this.exchangeQuantity = Math.max(0, exchangeQuantity);
		this.graded = graded;
	}

	/** One defensive copy, null keys and values dropped; an empty map is stored as null - the pre-T shape. */
	@Nullable
	private static <V> Map<MovementWindow, V> copyByWindow(@Nullable final Map<MovementWindow, V> values)
	{
		if (values == null || values.isEmpty())
		{
			return null;
		}
		final EnumMap<MovementWindow, V> copy = new EnumMap<>(MovementWindow.class);
		for (final Map.Entry<MovementWindow, V> entry : values.entrySet())
		{
			if (entry.getKey() != null && entry.getValue() != null)
			{
				copy.put(entry.getKey(), entry.getValue());
			}
		}
		return copy.isEmpty() ? null : Collections.unmodifiableMap(copy);
	}

	public int id()
	{
		return id;
	}

	/**
	 * Never null.
	 */
	public String name()
	{
		return name;
	}

	public int quantity()
	{
		return quantity;
	}

	/**
	 * How many of {@link #quantity()} the BANK holds (addendum Y, line Y3), or 0 on a row computed with "Include
	 * inventory and worn gear" off - which is every row of addenda K to W.
	 *
	 * <p>The four split figures are what the row's hover names ("3 in bank, 1 in inventory, 1 worn, 2 in the Grand
	 * Exchange"), and they add up to {@link #quantity()} whenever any of them is non-zero. A stack the player only
	 * WEARS answers 0 here, and that is not the same answer as the switch being off - any of the four figures above 0
	 * says the row knows where its quantity is.
	 */
	public int bankQuantity()
	{
		return bankQuantity;
	}

	/** How many of {@link #quantity()} are in the INVENTORY (Y3); 0 while the switch is off. */
	public int inventoryQuantity()
	{
		return inventoryQuantity;
	}

	/** How many of {@link #quantity()} are WORN (Y3); 0 while the switch is off. */
	public int wornQuantity()
	{
		return wornQuantity;
	}

	/**
	 * How many of {@link #quantity()} are in the player's Grand Exchange OFFERS (1.0.9 part 3) - unsold in a sell
	 * offer, bought and not yet collected in a buy offer; 0 while "Include Grand Exchange offers" is off.
	 */
	public int exchangeQuantity()
	{
		return exchangeQuantity;
	}

	/**
	 * Whether the whole stack is in the bank - the test the row's hover line is drawn on: it is printed only while
	 * something is carried, worn or in an offer (Y3), because "3 in bank" alone tells a reader nothing they cannot
	 * see. True for a row with no split at all, which is what a switched-off row is.
	 */
	public boolean allInBank()
	{
		return inventoryQuantity == 0 && wornQuantity == 0 && exchangeQuantity == 0;
	}

	public boolean stackable()
	{
		return stackable;
	}

	/**
	 * The guide price of one, or null when RuneLite's table has none (0 there reads as none, K1). A row with
	 * no unit price is filtered out entirely ({@link MovementMath#apply}) because a gp band cannot say anything
	 * about it.
	 */
	public Long unitPrice()
	{
		return unitPrice;
	}

	/**
	 * The guide price of one at the baseline revision - the wiki's {@code Module:GEPrices/data.json} as it
	 * stood at or before now minus the window - or null when neither the mapping nor the composition name
	 * found the item in it.
	 */
	public Long thenPrice()
	{
		return thenPrice;
	}

	/**
	 * {@link #unitPrice()} minus {@link #thenPrice()}, or null when no move can be computed.
	 */
	public Long deltaGp()
	{
		return deltaGp;
	}

	/**
	 * {@link #deltaGp()} as a percentage of {@link #thenPrice()}, or null.
	 */
	public Double deltaPct()
	{
		return deltaPct;
	}

	/**
	 * What the stack is worth: unit price times quantity, clamped rather than overflowed
	 * ({@link MovementMath#holdingValue}). Shown small and in the tooltip; never filtered on (design D4).
	 */
	public long holdingValue()
	{
		return holdingValue;
	}

	/**
	 * What the WHOLE STACK's price changed by: {@link #deltaGp()} times {@link #quantity()}, the figure a row
	 * prints in place of the unit change while "Show stack value on rows" is on (addendum Q, line Q6), and the
	 * key the two gp orderings sort on then.
	 *
	 * <p>0 when there is no move to multiply - a row with no baseline prints its dash rather than a zero, and the
	 * caller tells the two apart with {@link #hasMovement()}, exactly as it does for {@link #deltaGp()}. Clamped
	 * by sign rather than wrapped ({@link MovementMath#holdingDelta}): a fall must never come out as a gain.
	 */
	public long holdingDeltaGp()
	{
		return MovementMath.holdingDelta(deltaGp, quantity);
	}

	/**
	 * Where {@link #unitPrice()} came from. Never null.
	 */
	public PriceSource source()
	{
		return source;
	}

	/**
	 * The tradeable items this row's price was summed from (addendum R, line R2) - for a Crystal body, one part of
	 * quantity 3 named "Crystal armour seed" - or NULL for every row that is not a {@link PriceSource#PARTS} one.
	 * Unmodifiable and never empty when present.
	 *
	 * <p>What the tooltip's one extra line is built from (R4): "Untradeable - valued as its parts: 3 x Crystal
	 * armour seed".
	 */
	@Nullable
	public List<BankItem.Part> parts()
	{
		return parts;
	}

	/**
	 * This same row, re-sourced as {@link PriceSource#PARTS} and carrying the parts it was valued from (R2).
	 *
	 * <p>It exists so the arithmetic stays in ONE place: the service builds a parts-priced row by handing the parts
	 * SUM to {@code MovementMath.row} as an ordinary guide "now" and "then" - which is precisely what R2 asks for,
	 * "gp / pct from unit and then exactly as a GUIDE row" - and then says here where that price came from.
	 *
	 * @param parts the parts; null or empty, or a row with no price at all, answers this row unchanged, because a
	 *              row that names no price is not "valued as its parts"
	 */
	public MovementRow asParts(@Nullable final List<BankItem.Part> parts)
	{
		if (parts == null || parts.isEmpty() || unitPrice == null)
		{
			return this;
		}

		return new MovementRow(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue,
			PriceSource.PARTS, parts, windowDays, bankQuantity, inventoryQuantity, wornQuantity, exchangeQuantity,
			graded);
	}

	/**
	 * This same row, saying where its quantity is (addendum Y, line Y3) - the last thing the service does to a row
	 * while "Include inventory and worn items" is on, after every other {@code as*} copy, so nothing can drop it.
	 *
	 * <p>Every figure is untouched: the split says where the stack lives, never what it is worth. Four zeros answer
	 * this row unchanged, which is what the switches being off hand it.
	 *
	 * @param bankQuantity      how many the bank holds
	 * @param inventoryQuantity how many are in the inventory
	 * @param wornQuantity      how many are worn
	 * @param exchangeQuantity  how many are in the Grand Exchange offers
	 */
	public MovementRow withSplit(final int bankQuantity, final int inventoryQuantity, final int wornQuantity,
		final int exchangeQuantity)
	{
		if (bankQuantity <= 0 && inventoryQuantity <= 0 && wornQuantity <= 0 && exchangeQuantity <= 0)
		{
			return this;
		}

		return new MovementRow(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue,
			source, parts, windowDays, bankQuantity, inventoryQuantity, wornQuantity, exchangeQuantity, graded);
	}

	/**
	 * The calendar DAY this row's "then" for ONE window was actually taken from (addendum U, line U3) - the day the
	 * window's figure is a comparison against, and the day its tooltip line prints: "1d ago (11 Sep): 103,214 gp".
	 *
	 * <p>Which day that is depends on the series the window used:
	 * <ul>
	 * <li>a LIVE window: the day of the traded bucket the comparison was made against - {@code liveDay - N} counted
	 * back from the live snapshot's own UTC date, or one day further back when that day had not closed yet (U2).
	 * The guide's anchor day plays no part in it, which is the whole of addendum U: tying the traded bucket to the
	 * guide's anchor made a "1d" row span two days whenever Jagex had not published the day's table yet;</li>
	 * <li>a window that fell back to the guide: that window's guide baseline day - the same day
	 * {@code Status.thenDay()} names for it.</li>
	 * </ul>
	 *
	 * <p>NULL when this row has no record for that window, which covers every row computed with the
	 * {@code livePrices} switch off (they carry no days at all, which is what keeps them byte-identical to the
	 * pre-addendum-T ones) and a window with no figure on either series. A caller drawing a row therefore falls
	 * back to the status's own baseline day, which is exactly what it printed before addendum U.
	 *
	 * @param window the window; null answers null
	 */
	@Nullable
	public LocalDate windowDay(@Nullable final MovementWindow window)
	{
		return windowDays == null || window == null ? null : windowDays.get(window);
	}

	/**
	 * The graded live figure of the window this row was built for (contract 1.2.0, L2-L4): its {@link Grade}, the word
	 * the sidebar prints under a soft figure ({@link GradedMove#word()}), the price and the mark the figure moved from,
	 * both sides' "now" and "then" and the newest print of each side with its time. NULL on a row nothing graded - live
	 * prices off, no usable snapshot, an alch row - which is drawn exactly as before 1.2.0.
	 *
	 * <p>On a graded row with a figure, {@link #thenPrice()} is {@link GradedMove#thenMark()}, {@link #deltaGp()} its
	 * {@link GradedMove#deltaGp()} and {@link #deltaPct()} its {@link GradedMove#move()} x 100; {@link #unitPrice()} is
	 * the row's price, which on the 1d window is {@link GradedMove#price()} itself (L4).
	 */
	@Nullable
	public GradedMove graded()
	{
		return graded;
	}

	/**
	 * True when this row has a move to show; false when it renders "-" and sorts last.
	 */
	public boolean hasMovement()
	{
		return deltaGp != null;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof MovementRow))
		{
			return false;
		}

		final MovementRow other = (MovementRow) o;
		return id == other.id
			&& quantity == other.quantity
			&& stackable == other.stackable
			&& holdingValue == other.holdingValue
			&& name.equals(other.name)
			&& Objects.equals(unitPrice, other.unitPrice)
			&& Objects.equals(thenPrice, other.thenPrice)
			&& Objects.equals(deltaGp, other.deltaGp)
			&& Objects.equals(deltaPct, other.deltaPct)
			&& source == other.source
			&& Objects.equals(parts, other.parts)
			// Part of the value, not decoration (U3): two rows that compared against different days are two
			// different rows, whatever their figures look like, and the "the switch off is byte-identical" test
			// compares whole rows.
			&& Objects.equals(windowDays, other.windowDays)
			// And for the SPLIT (Y3): a stack of five in the bank and a stack of four plus one worn are two
			// different rows, and the "the switch off is byte-identical" test compares whole rows.
			&& bankQuantity == other.bankQuantity
			&& inventoryQuantity == other.inventoryQuantity
			&& wornQuantity == other.wornQuantity
			&& exchangeQuantity == other.exchangeQuantity
			// And the GRADE (1.2.0): the same figure, solid or soft, is two different rows on the screen.
			&& Objects.equals(graded, other.graded);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct,
			holdingValue, source, parts, windowDays, bankQuantity, inventoryQuantity, wornQuantity,
			exchangeQuantity, graded);
	}
}
