package com.bankpricemovement;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One stack in the user's bank, folded onto its CANONICAL item id (contract C5). Public fields, a no-arg
 * constructor and nothing but plain types, because a snapshot is persisted with the injected Gson and read
 * back on the next launch (playbook step 13: "public fields, no-arg ctor, stock Gson").
 *
 * <p>The name and the stackable flag are carried rather than looked up again at render time on purpose: the
 * panel must draw the saved bank BEFORE the user logs in (design D7 - "Bank as of HH:MM" with no client
 * session), and {@code ItemComposition} is a client-thread read that is simply not available then.
 * {@code stackable} is what {@code ItemManager.getImage(id, quantity, stackable)} needs to draw the stack
 * number on the icon.
 *
 * <p>Noted items do not appear here as themselves: {@code BankReader} canonicalises first, so a note and its
 * unnoted twin are ONE entry with the quantities summed (design D6).
 *
 * <p><b>Untradeable stacks</b> (addendum Q, line Q5). Since Q the reader also keeps a stack the Grand Exchange
 * does not list when it has a High Alchemy value, so the "Include untradeable items" switch can list it without a
 * bank visit ({@link #untradeable} and {@link #haPrice} are the two fields that carry it). The negative spelling
 * is deliberate and load-bearing: a {@code bank-*.json} written before Q has neither field, and Gson leaves an
 * absent boolean at {@code false} - so {@code untradeable} reads as "tradeable" for every stack of an old file,
 * which is exactly what those files held. A {@code tradeable} field would have read every old stack as
 * untradeable and emptied the sidebar.
 *
 * <p><b>Tradeable parts</b> (addendum R, line R1). RuneLite knows what many an untradeable item reverts to:
 * {@code net.runelite.client.game.ItemMapping} maps a Crystal body onto three Crystal armour seeds, a degraded
 * barrows piece onto the base piece, a graceful piece onto marks of grace, and so on. {@link #parts} records that
 * answer at capture time, so such a stack can be valued at what its parts are worth on the exchange - with a
 * movement - instead of at the High Alchemy value Q5 had to settle for. It is null for a tradeable stack, null for
 * an untradeable one RuneLite has no mapping for, and null in every {@code bank-*.json} written before addendum R
 * (Gson leaves an absent field null); the next bank open fills it in. {@link #haPrice} is recorded either way, so a
 * stack whose parts turn out to have no price still has the alch rule to fall back on.
 */
public class BankItem
{
	/**
	 * One TRADEABLE item an untradeable stack reverts to, as RuneLite's {@code ItemMapping} names it (addendum R,
	 * line R1): {@code ITEM_CRYSTAL_BODY(PRIF_ARMOUR_SEED, true, 3L, CRYSTAL_CHESTPLATE)} becomes one of these,
	 * with {@code id} the seed and {@code quantity} 3.
	 *
	 * <p>Public fields, a no-arg constructor and plain types, for the same reason {@link BankItem} has them: a
	 * snapshot is persisted with the injected Gson and read back on the next launch.
	 */
	public static class Part
	{
		/** The tradeable item's id ({@code ItemMapping.getTradeableItem()}); positive in a recorded part. */
		public int id;

		/**
		 * How many of {@link #id} ONE of the untradeable item is worth ({@code ItemMapping.getQuantity()}, a long
		 * in RuneLite's own enum); positive in a recorded part.
		 */
		public long quantity;

		/**
		 * That part's name at capture time - {@code ItemComposition.getMembersName()}, the same naming rule the
		 * rows use (addendum L line L8 b) - so a guide table can be asked for it by name when the wiki's
		 * id-to-name mapping does not cover the id. "" when the composition had none.
		 */
		public String name;

		public Part()
		{
			name = "";
		}

		/**
		 * @param id       the tradeable item's id
		 * @param quantity how many of it ONE untradeable item is worth
		 * @param name     the part's members name; null becomes ""
		 */
		public Part(final int id, final long quantity, final String name)
		{
			this.id = id;
			this.quantity = quantity;
			this.name = name == null ? "" : name;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}

			if (!(o instanceof Part))
			{
				return false;
			}

			final Part other = (Part) o;
			return id == other.id && quantity == other.quantity && Objects.equals(name, other.name);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(id, quantity, name);
		}

		@Override
		public String toString()
		{
			return "Part{id=" + id + ", quantity=" + quantity + ", name='" + name + "'}";
		}
	}

	/**
	 * The canonical item id ({@code ItemManager.canonicalize}), always positive in a normalised snapshot.
	 */
	public int id;

	/**
	 * How many the bank holds; always positive in a normalised snapshot - a placeholder (quantity 0) is
	 * dropped rather than shown (design D6).
	 */
	public int quantity;

	/**
	 * The item's name at capture time. Never null after {@link BankSnapshot#normalize()}; "" when the
	 * composition had none.
	 */
	public String name;

	/**
	 * Whether the item stacks - what the icon needs to decide whether to draw a stack number.
	 */
	public boolean stackable;

	/**
	 * True when the Grand Exchange does not list this item ({@code ItemComposition.isGeTradeable()} is false), so
	 * it has no market price and no movement - it is valued at {@link #haPrice} instead, and only while the
	 * "Include untradeable items" switch is on (Q5). False for every ordinary stack, and false for every stack of a
	 * file written before addendum Q (see the class comment).
	 */
	public boolean untradeable;

	/**
	 * The High Alchemy value of ONE ({@code ItemComposition.getHaPrice()}, clone
	 * {@code runelite-api/src/main/java/net/runelite/api/ItemComposition.java:120}), recorded only for an
	 * {@link #untradeable} stack, which is valued at it. 0 for a tradeable stack - its price is the guide price,
	 * looked up live - and 0 for every stack of a pre-Q file.
	 */
	public int haPrice;

	/**
	 * The TRADEABLE items ONE of this stack reverts to, as {@code ItemMapping} named them at capture time
	 * (addendum R, line R1) - three Crystal armour seeds for a Crystal body, a black mask for a slayer helmet, the
	 * base piece for a degraded barrows item. Null for a tradeable stack, for an untradeable one RuneLite has no
	 * mapping for, and for every stack of a file written before addendum R; never empty when it is present.
	 *
	 * <p>Recorded whatever the "Include untradeable items" switch says, exactly as {@link #haPrice} is: a snapshot
	 * that held only what the switch of the day wanted would need a bank visit every time it was flipped.
	 */
	public List<Part> parts;

	public BankItem()
	{
		name = "";
	}

	public BankItem(final int id, final int quantity, final String name, final boolean stackable)
	{
		this(id, quantity, name, stackable, false, 0);
	}

	/**
	 * The pre-R arity, kept so every caller that knows nothing of parts keeps compiling AND keeps its meaning: it
	 * records no parts, which is what an untradeable stack with no mapping holds.
	 *
	 * @param untradeable the Grand Exchange does not list this item (Q5)
	 * @param haPrice     what one alchs for, the value an untradeable stack is counted at when it has no parts
	 */
	public BankItem(final int id, final int quantity, final String name, final boolean stackable,
		final boolean untradeable, final int haPrice)
	{
		this(id, quantity, name, stackable, untradeable, haPrice, null);
	}

	/**
	 * @param parts the tradeable items one of this stack reverts to (R1), or null when RuneLite maps it to none; an
	 *              empty list is stored as null, because "no mapping" and "a mapping naming nothing" are the same
	 *              answer to every reader
	 */
	public BankItem(final int id, final int quantity, final String name, final boolean stackable,
		final boolean untradeable, final int haPrice, final List<Part> parts)
	{
		this.id = id;
		this.quantity = quantity;
		this.name = name == null ? "" : name;
		this.stackable = stackable;
		this.untradeable = untradeable;
		this.haPrice = haPrice;
		this.parts = parts == null || parts.isEmpty() ? null : Collections.unmodifiableList(parts);
	}

	/**
	 * This same stack with another quantity (addendum Y, line Y3): what {@code PriceService} folds a banked stack
	 * and a carried one into while "Include inventory and worn gear" is on - three in the bank and one in the
	 * inventory become one stack of four.
	 *
	 * <p>A COPY, and that is the whole point: the stacks it merges belong to the persisted snapshot, which the
	 * panel draws and the store writes, and a computation that changed a quantity under them would put a number in
	 * {@code bank-*.json} that no bank ever held. Everything else - the name, the stackable flag, the untradeable
	 * mark, the alch value and the parts - rides along unchanged.
	 *
	 * @param quantity the merged quantity
	 */
	public BankItem withQuantity(final int quantity)
	{
		return new BankItem(id, quantity, name, stackable, untradeable, haPrice, parts);
	}

	/**
	 * Whether this stack can be valued at its tradeable parts (R1) - the ONE place that rule is spelled, so the
	 * reader, the row, the bank value and the tooltip cannot drift apart. A list read off disk could be empty or
	 * hold nothing but nulls; both read as "no parts" here.
	 */
	public boolean hasParts()
	{
		if (parts == null)
		{
			return false;
		}

		for (final Part part : parts)
		{
			if (part != null)
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * What this stack is worth per item when it is listed at its High Alchemy value, or null when it has no such
	 * value: an untradeable stack with a positive {@link #haPrice}, and nothing else. The ONE place that rule is
	 * spelled, so the row, the bank value and the reader's keep rule cannot drift apart.
	 *
	 * <p>Since addendum R this is the SECOND choice for an untradeable stack: one whose {@link #parts} can all be
	 * priced is worth their sum instead (R2), and the alch value is what a stack with no parts - or one whose parts
	 * the guide tables cannot price - falls back to. The rule itself is unchanged, and so is every stack it answers
	 * for; the decision between the two belongs to {@code PriceService}, which is the only thing that knows what a
	 * part costs today.
	 */
	public Long alchPrice()
	{
		return untradeable && haPrice > 0 ? Long.valueOf(haPrice) : null;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof BankItem))
		{
			return false;
		}

		final BankItem other = (BankItem) o;
		return id == other.id
			&& quantity == other.quantity
			&& stackable == other.stackable
			&& untradeable == other.untradeable
			&& haPrice == other.haPrice
			&& (name == null ? other.name == null : name.equals(other.name))
			&& Objects.equals(parts, other.parts);
	}

	@Override
	public int hashCode()
	{
		int result = id;
		result = 31 * result + quantity;
		result = 31 * result + (stackable ? 1 : 0);
		result = 31 * result + (untradeable ? 1 : 0);
		result = 31 * result + haPrice;
		result = 31 * result + (name == null ? 0 : name.hashCode());
		result = 31 * result + (parts == null ? 0 : parts.hashCode());
		return result;
	}

	@Override
	public String toString()
	{
		return "BankItem{id=" + id + ", quantity=" + quantity + ", name='" + name + "', stackable=" + stackable
			+ (untradeable ? ", untradeable, haPrice=" + haPrice : "")
			+ (hasParts() ? ", parts=" + parts : "") + '}';
	}
}
