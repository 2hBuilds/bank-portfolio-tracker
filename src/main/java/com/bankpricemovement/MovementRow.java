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
 * <p>Since addendum Y line Y3 a row is not only the bank's: with "Include inventory and worn gear" on, one row
 * carries the whole of what the player owns of that item - {@link #quantity()} is the bank's, the inventory's and
 * the worn gear's added - and {@link #bankQuantity()}, {@link #inventoryQuantity()} and {@link #wornQuantity()} say
 * where it is, for the one hover line that names the parts ("3 in bank, 1 in inventory, 1 worn"). All three are 0
 * with the switch off, which is what makes such a row byte-identical to the pre-Y one.
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
		 * The OSRS Wiki's REAL-TIME TRADED mid - the mean of the newest instant-buy and instant-sell from
		 * {@code prices.runescape.wiki/api/v1/osrs/latest} - for a stack that passed all five liquidity checks
		 * (addendum T line T3, extended by addendum V lines V3 and V4) while the {@code livePrices} switch was on:
		 * at least 100 units traded yesterday, a buy/sell gap inside 10 % of the mid, a mid inside 50 % of the
		 * guide price, a buy/sell gap inside 10 % of the middle of YESTERDAY's daily bucket, and a mid inside 50 %
		 * of that bucket's volume-weighted average.
		 *
		 * <p>A different series from {@link #GUIDE}, and the only one in this plugin that moves between two Jagex
		 * days. A LIVE row paints exactly as a GUIDE row does (T6) and carries a real {@link MovementRow#thenPrice()},
		 * {@link MovementRow#deltaGp()} and {@link MovementRow#deltaPct()}; which SERIES the two ends of a given
		 * window came from is {@link MovementRow#windowSource(MovementWindow)}, because a window whose traded bucket
		 * is missing or thin falls back to the guide's own two ends (T4) while the row still prints the live unit
		 * price. Everything a reader needs to see why - the two sides, yesterday's volume, or the check that
		 * refused - is on {@link MovementRow#liveFacts()}.
		 *
		 * <p>Never produced while the switch is off: that is exactly the guide-only behaviour of addenda K to S.
		 */
		LIVE,
		/**
		 * The item's HIGH ALCHEMY value ({@code ItemComposition.getHaPrice()}, carried on the stack as
		 * {@link BankItem#haPrice}) - the only price an item the Grand Exchange does not list has (addendum Q,
		 * line Q5). Produced only while "Include untradeable items" is on, and never with a baseline: an alch value
		 * is a constant of the item, not a series, so such a row has no {@link MovementRow#thenPrice()},
		 * {@link MovementRow#deltaGp()} or {@link MovementRow#deltaPct()} and the panel prints a small grey "alch"
		 * tag where the move would go.
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
		 * value, because a sum of some of the parts is not the item's price. Produced only while "Include
		 * untradeable items" is on, and only for a stack whose every part could be priced; one that could not falls to
		 * {@link #ALCH}.
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

	/**
	 * What a row can say about the TRADED series (addendum T, lines T3 and T6, with addendum V's two extra
	 * checks), carried only while the
	 * {@code livePrices} switch is on and null on every row computed without it - which is what keeps a row built
	 * with the switch off byte-identical to the pre-T one.
	 *
	 * <p>Two shapes, told apart by {@link #live()}:
	 * <ul>
	 * <li>a LIVE row: {@link #reason()} null, and the two sides and yesterday's volume behind the price the row is
	 * printing - the tooltip's "Live traded price: 63,437,264 gp (buy 63.6m, sell 63.3m; 517 traded yesterday)";</li>
	 * <li>a row that stayed on the guide: {@link #reason()} is the FIRST of the five checks that refused it, in
	 * their own order - "12 traded yesterday", "buy/sell gap 23 %", "live price 61 % from guide", "buy/sell gap
	 * 100 % yesterday" (V3), "live price 181 % from yesterday's average" (V4), or "no live data" when a feed was
	 * not in hand at all. The sides and the volume are whatever WAS known, so a reader can see the
	 * numbers the refusal was made on.</li>
	 * </ul>
	 *
	 * <p>Immutable, like the row that holds it.
	 */
	public static final class LiveFacts
	{
		@Nullable
		private final Long buy;
		@Nullable
		private final Long sell;
		private final long volumeYesterday;
		@Nullable
		private final String reason;

		/**
		 * @param buy             the newest instant-buy price from {@code /latest}, or null when there was none
		 * @param sell            the newest instant-sell price, or null
		 * @param volumeYesterday units traded in yesterday's bulk bucket, both sides; 0 when none or unknown
		 * @param reason          why the live price was NOT used, or null for a live row
		 */
		public LiveFacts(@Nullable final Long buy, @Nullable final Long sell, final long volumeYesterday,
			@Nullable final String reason)
		{
			this.buy = buy;
			this.sell = sell;
			this.volumeYesterday = Math.max(0L, volumeYesterday);
			this.reason = reason;
		}

		/** The newest instant-buy price ({@code high}), or null when the feed had none for this item. */
		@Nullable
		public Long buy()
		{
			return buy;
		}

		/** The newest instant-sell price ({@code low}), or null. */
		@Nullable
		public Long sell()
		{
			return sell;
		}

		/** Units traded in yesterday's bulk bucket, both sides added; 0 when the bucket named none. */
		public long volumeYesterday()
		{
			return volumeYesterday;
		}

		/** Why the live price was not used, one short phrase; null exactly when the row IS live. */
		@Nullable
		public String reason()
		{
			return reason;
		}

		/** True when this row's unit price is the live traded mid - equivalently, when {@link #reason()} is null. */
		public boolean live()
		{
			return reason == null;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof LiveFacts))
			{
				return false;
			}
			final LiveFacts other = (LiveFacts) o;
			return Objects.equals(buy, other.buy)
				&& Objects.equals(sell, other.sell)
				&& volumeYesterday == other.volumeYesterday
				&& Objects.equals(reason, other.reason);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(buy, sell, volumeYesterday, reason);
		}

		@Override
		public String toString()
		{
			return "LiveFacts{buy=" + buy + ", sell=" + sell + ", volumeYesterday=" + volumeYesterday
				+ (reason == null ? ", live" : ", reason='" + reason + '\'') + '}';
		}
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
	 * Which SERIES each window's two ends came from (T4), for a row the traded prices touched; null on every row
	 * computed with the switch off, and read through {@link #windowSource(MovementWindow)} rather than directly.
	 */
	@Nullable
	private final Map<MovementWindow, PriceSource> windowSources;
	/**
	 * Which calendar DAY each window's "then" was actually taken from (addendum U, line U3) - the traded bucket's
	 * own day for a live window, that window's guide baseline day for one that fell back - for a row the traded
	 * prices touched; null on every row computed with the switch off, and read through
	 * {@link #windowDay(MovementWindow)} rather than directly.
	 */
	@Nullable
	private final Map<MovementWindow, LocalDate> windowDays;
	@Nullable
	private final LiveFacts liveFacts;
	/**
	 * Where the {@link #quantity} is (addendum Y, line Y3): how many of it the BANK holds, how many are in the
	 * INVENTORY and how many are WORN. All three are 0 on every row computed with "Include inventory and worn gear"
	 * off - which is what keeps such a row byte-identical to the pre-Y one - and they add up to {@link #quantity}
	 * while it is on.
	 */
	private final int bankQuantity;
	private final int inventoryQuantity;
	private final int wornQuantity;

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
		this(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue, source, null);
	}

	/**
	 * The same, carrying the tradeable parts the price was summed from (addendum R, line R2). The arity above is
	 * the pre-R one, kept so every caller that knows nothing of parts keeps compiling AND keeps its meaning: it
	 * delegates with no parts, which is what every row but a {@link PriceSource#PARTS} one has.
	 *
	 * @param parts the parts, or null for any other row; copied into an unmodifiable list, and an empty list is
	 *              stored as null - a row cannot be "valued as its parts" and name none
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source, @Nullable final List<BankItem.Part> parts)
	{
		this(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue, source, parts,
			null, null);
	}

	/**
	 * The same, carrying what the TRADED series had to say about this row (addendum T, lines T4 and T6). The
	 * arity above is the pre-T one, kept so every caller that knows nothing of live prices keeps compiling AND
	 * keeps its meaning: it delegates with no window sources and no facts, which is exactly what a row computed
	 * with the {@code livePrices} switch off carries.
	 *
	 * @param windowSources which series each window's two ends came from; copied into an unmodifiable
	 *                      {@link EnumMap}, null keys or values dropped, and an empty map stored as null
	 * @param liveFacts     the live sides and volume, or the reason the live price was refused; null when the
	 *                      switch was off
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source, @Nullable final List<BankItem.Part> parts,
		@Nullable final Map<MovementWindow, PriceSource> windowSources, @Nullable final LiveFacts liveFacts)
	{
		this(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue, source, parts,
			windowSources, null, liveFacts);
	}

	/**
	 * The same, carrying the calendar DAY each window's "then" came from (addendum U, line U3). The arity above is
	 * the pre-U one, kept so every caller that knows nothing of the live calendar keeps compiling AND keeps its
	 * meaning: it delegates with no days, which is exactly what a row built before addendum U carried.
	 *
	 * @param windowDays the day each window was actually compared against; copied into an unmodifiable
	 *                   {@link EnumMap}, null keys or values dropped, and an empty map stored as null
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source, @Nullable final List<BankItem.Part> parts,
		@Nullable final Map<MovementWindow, PriceSource> windowSources,
		@Nullable final Map<MovementWindow, LocalDate> windowDays, @Nullable final LiveFacts liveFacts)
	{
		this(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue, source, parts,
			windowSources, windowDays, liveFacts, 0, 0, 0);
	}

	/**
	 * The same, saying where the quantity IS (addendum Y, line Y3). The arity above is the pre-Y one, kept so every
	 * caller that knows nothing of the inventory keeps compiling AND keeps its meaning: it delegates with three
	 * zeros, which is exactly what a row computed with "Include inventory and worn gear" off carries.
	 *
	 * @param bankQuantity      how many of {@code quantity} the bank holds; 0 while the switch is off
	 * @param inventoryQuantity how many are in the inventory; 0 while the switch is off
	 * @param wornQuantity      how many are worn; 0 while the switch is off
	 */
	public MovementRow(final int id, final String name, final int quantity, final boolean stackable,
		final Long unitPrice, final Long thenPrice, final Long deltaGp, final Double deltaPct,
		final long holdingValue, final PriceSource source, @Nullable final List<BankItem.Part> parts,
		@Nullable final Map<MovementWindow, PriceSource> windowSources,
		@Nullable final Map<MovementWindow, LocalDate> windowDays, @Nullable final LiveFacts liveFacts,
		final int bankQuantity, final int inventoryQuantity, final int wornQuantity)
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
		this.windowSources = copyByWindow(windowSources);
		this.windowDays = copyByWindow(windowDays);
		this.liveFacts = liveFacts;
		this.bankQuantity = Math.max(0, bankQuantity);
		this.inventoryQuantity = Math.max(0, inventoryQuantity);
		this.wornQuantity = Math.max(0, wornQuantity);
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
	 * <p>The three split figures are what the row's hover names ("3 in bank, 1 in inventory, 1 worn"), and they add
	 * up to {@link #quantity()} whenever any of them is non-zero. A stack the player only WEARS answers 0 here, and
	 * that is not the same answer as the switch being off - {@link #split()} is the test for that.
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
	 * Whether this row knows where its quantity is - true exactly when it was computed with "Include inventory and
	 * worn gear" on. A row without the split prints no hover line about it (Y3), which is what a bank-only row did
	 * before addendum Y and what one still does with the switch off.
	 */
	public boolean split()
	{
		return bankQuantity > 0 || inventoryQuantity > 0 || wornQuantity > 0;
	}

	/**
	 * Whether the whole stack is in the bank - the test the row's hover line is drawn on: it is printed only while
	 * the switch is on AND something is carried or worn (Y3), because "3 in bank" alone tells a reader nothing they
	 * cannot see. True for a row with no split at all, which is what a switched-off row is.
	 */
	public boolean allInBank()
	{
		return inventoryQuantity == 0 && wornQuantity == 0;
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
			PriceSource.PARTS, parts, windowSources, windowDays, liveFacts, bankQuantity, inventoryQuantity,
			wornQuantity);
	}

	/**
	 * This same row, saying where its quantity is (addendum Y, line Y3) - the last thing the service does to a row
	 * while "Include inventory and worn gear" is on, after every other {@code as*} copy, so nothing can drop it.
	 *
	 * <p>Every figure is untouched: the split says where the stack lives, never what it is worth. Three zeros answer
	 * this row unchanged, which is what the switch being off hands it.
	 *
	 * @param bankQuantity      how many the bank holds
	 * @param inventoryQuantity how many are in the inventory
	 * @param wornQuantity      how many are worn
	 */
	public MovementRow withSplit(final int bankQuantity, final int inventoryQuantity, final int wornQuantity)
	{
		if (bankQuantity <= 0 && inventoryQuantity <= 0 && wornQuantity <= 0)
		{
			return this;
		}

		return new MovementRow(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue,
			source, parts, windowSources, windowDays, liveFacts, bankQuantity, inventoryQuantity, wornQuantity);
	}

	/**
	 * Which price series the two ends of ONE window came from (addendum T, line T4): {@link PriceSource#LIVE} when
	 * that window compared the live traded mid against that day's traded average, and {@link PriceSource#GUIDE}
	 * when it fell back to the guide's own two ends - which is what a window does when its traded bucket is
	 * missing, thin, or simply not this plugin's business (every window of a row the switch left on the guide).
	 *
	 * <p>The answer for a row the traded feeds never touched is the row's own {@link #source()}, so a GUIDE row
	 * answers GUIDE, an ALCH row ALCH and a PARTS row PARTS for every window - each of which is the truth about
	 * where that window's numbers came from. The one case that is NOT the row's own source is a LIVE row with no
	 * record for the window asked about: that answers GUIDE rather than claiming a live comparison nothing made.
	 *
	 * @param window the window; null answers the row's own source
	 */
	public PriceSource windowSource(@Nullable final MovementWindow window)
	{
		if (windowSources != null && window != null)
		{
			final PriceSource recorded = windowSources.get(window);
			if (recorded != null)
			{
				return recorded;
			}
		}
		return source == PriceSource.LIVE ? PriceSource.GUIDE : source;
	}

	/**
	 * The calendar DAY this row's "then" for ONE window was actually taken from (addendum U, line U3) - the day the
	 * window's figure is a comparison against, and the day its tooltip line prints: "1d ago (11 Sep): 103,214 gp".
	 *
	 * <p>Which day that is depends on the series the window used ({@link #windowSource(MovementWindow)}):
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
	 * What the traded series had to say about this row (addendum T, lines T4 and T6) - the two live sides and
	 * yesterday's volume for a live row, the first failing check for one that stayed on the guide - or NULL on
	 * every row computed while the {@code livePrices} switch was off, which is every row of addenda K to S.
	 */
	@Nullable
	public LiveFacts liveFacts()
	{
		return liveFacts;
	}

	/**
	 * Whether this row's unit price is the live traded mid (T4). True for a {@link PriceSource#LIVE} row and for a
	 * {@link PriceSource#PARTS} row whose every part passed the checks - a parts stack keeps the PARTS source so
	 * the tooltip can still name what it was valued from, and this is the one test that covers both.
	 */
	public boolean isLive()
	{
		return liveFacts != null && liveFacts.live();
	}

	/**
	 * This same row, re-sourced as {@link PriceSource#LIVE} and carrying its window sources and live facts (T4).
	 *
	 * <p>It exists for the same reason {@link #asParts(List)} does: the service builds a live row by handing the
	 * live mid to {@code MovementMath.row} as an ordinary "now" - so every rule about gp, percent, holdings and
	 * the dash stays in one place - and then says here which series the numbers came from. A row that already
	 * says {@link PriceSource#PARTS} keeps that source, because "valued as its parts" is what a reader has to be
	 * told first and {@link #isLive()} still answers true for it.
	 *
	 * <p><b>The unit price is a parameter, and that is T4's one awkward case.</b> A live row whose traded bucket
	 * for the CURRENT window was missing or thin compares the guide's two ends for that window - "never live-now
	 * against guide-then" - and is therefore built from the guide pair, so that its gp and percent are the guide's
	 * move. It still PRINTS the live traded price, because that is what the item costs right now and what the rest
	 * of the card counts it at. {@code liveUnit} is how those two facts live on one row: the move stays as it was
	 * built, the unit price (and the holding that follows from it) become the live mid, and
	 * {@link #windowSource(MovementWindow)} tells a reader which window did which.
	 *
	 * @param liveUnit      the live traded mid to print, or null to keep the unit price this row was built with -
	 *                      which is the ordinary case, where the row was built from the live pair already
	 * @param windowSources which series each window used; see {@link #windowSource(MovementWindow)}
	 * @param facts         the live sides and yesterday's volume, with a null reason
	 * @return this row unchanged when it has no price at all, or when {@code facts} is null or says the row is not
	 *         live - a row cannot be "the live traded price" and name the check that refused it
	 */
	public MovementRow asLive(@Nullable final Long liveUnit,
		@Nullable final Map<MovementWindow, PriceSource> windowSources, @Nullable final LiveFacts facts)
	{
		return asLive(liveUnit, windowSources, null, facts);
	}

	/**
	 * The same, carrying the DAY each window was compared against (addendum U, line U3;
	 * {@link #windowDay(MovementWindow)}). The arity above is the pre-U one and delegates with no days.
	 *
	 * @param windowDays the day each window's "then" came from - the traded bucket's own day for a live window,
	 *                   the guide baseline day for one that fell back
	 */
	public MovementRow asLive(@Nullable final Long liveUnit,
		@Nullable final Map<MovementWindow, PriceSource> windowSources,
		@Nullable final Map<MovementWindow, LocalDate> windowDays, @Nullable final LiveFacts facts)
	{
		if (unitPrice == null || facts == null || !facts.live())
		{
			return this;
		}

		final Long unit = liveUnit == null || liveUnit <= 0L ? unitPrice : liveUnit;
		return new MovementRow(id, name, quantity, stackable, unit, thenPrice, deltaGp, deltaPct,
			MovementMath.holdingValue(unit, quantity),
			source == PriceSource.PARTS ? PriceSource.PARTS : PriceSource.LIVE, parts, windowSources, windowDays,
			facts, bankQuantity, inventoryQuantity, wornQuantity);
	}

	/**
	 * This same row with the reason the live price was NOT used (T6: a guide row gains one tooltip line while the
	 * switch is on). The source and every figure are untouched - only the explanation rides along.
	 *
	 * @param facts the sides and volume that were known, with the first failing check as the reason; null, or a
	 *              {@code facts} that says the row IS live, answers this row unchanged
	 */
	public MovementRow withLiveRefusal(@Nullable final LiveFacts facts)
	{
		if (facts == null || facts.live())
		{
			return this;
		}

		return new MovementRow(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct, holdingValue,
			source, parts, windowSources, windowDays, facts, bankQuantity, inventoryQuantity, wornQuantity);
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
			// Part of the value, not decoration: two rows with the same figures but different series behind them
			// are two different rows, and the "the switch off is byte-identical" test compares whole rows.
			&& Objects.equals(windowSources, other.windowSources)
			// The same reasoning for the DAYS (U3): two rows that compared against different days are two
			// different rows, whatever their figures look like.
			&& Objects.equals(windowDays, other.windowDays)
			&& Objects.equals(liveFacts, other.liveFacts)
			// And for the SPLIT (Y3): a stack of five in the bank and a stack of four plus one worn are two
			// different rows, and the "the switch off is byte-identical" test compares whole rows.
			&& bankQuantity == other.bankQuantity
			&& inventoryQuantity == other.inventoryQuantity
			&& wornQuantity == other.wornQuantity;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, name, quantity, stackable, unitPrice, thenPrice, deltaGp, deltaPct,
			holdingValue, source, parts, windowSources, windowDays, liveFacts, bankQuantity, inventoryQuantity,
			wornQuantity);
	}

	@Override
	public String toString()
	{
		return "MovementRow{id=" + id
			+ ", name='" + name + '\''
			+ ", quantity=" + quantity
			+ ", unitPrice=" + unitPrice
			+ ", thenPrice=" + thenPrice
			+ ", deltaGp=" + deltaGp
			+ ", deltaPct=" + deltaPct
			+ ", holdingValue=" + holdingValue
			+ ", source=" + source
			+ (parts == null ? "" : ", parts=" + parts)
			+ (windowSources == null ? "" : ", windowSources=" + windowSources)
			+ (windowDays == null ? "" : ", windowDays=" + windowDays)
			+ (liveFacts == null ? "" : ", live=" + liveFacts)
			// Printed only when there IS a split, so a row computed with the switch off reads exactly as it did.
			+ (split() ? ", bank=" + bankQuantity + ", inventory=" + inventoryQuantity + ", worn=" + wornQuantity : "")
			+ '}';
	}
}
