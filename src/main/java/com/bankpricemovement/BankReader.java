package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the raw bank container into the priceable {@link BankSnapshot} the rest of Bank Portfolio Tracker works
 * from (contract lines C11 and C12).
 *
 * <p>Since addendum Y line Y2 the same rules read two more containers: {@link #readContainers} turns the player's
 * INVENTORY and WORN gear into a {@link Carried}, which {@link BankSnapshot#withCarried(Carried)} hangs on the
 * snapshot so "Include inventory and worn gear" can count and list those stacks beside the bank's.
 *
 * <p><b>Client thread only.</b> {@link #read} calls {@link ItemManager#canonicalize(int)} and
 * {@link ItemManager#getItemComposition(int)}, and both bottom out in {@code client.getItemDefinition(int)}
 * (clone {@code runelite-client/src/main/java/net/runelite/client/game/ItemManager.java:459-462, :467-482};
 * {@code runelite-api/src/main/java/net/runelite/api/Client.java:393-394}), which reads the live item cache.
 * The one caller is the plugin's {@code onItemContainerChanged} handler, which the client dispatches on the
 * client thread. Everything the method touches afterwards is plain data, so the finished snapshot can be
 * handed straight to the executor and the EDT.
 *
 * <p>Why each skip rule exists, in the order C11 lists them:
 * <ul>
 * <li>{@code null} entries and {@code id <= 0}: core's own bank valuation walks the array with exactly this
 * guard (clone {@code .../plugins/bank/BankPlugin.java:608-634}, the {@code id <= 0 || qty == 0} test at
 * :624) because an item container is a fixed-size array padded with empty slots.</li>
 * <li>{@code quantity <= 0}: this is what removes BANK PLACEHOLDERS. A placeholder sits in container 95 as
 * its own item id with quantity 0 (research claim C9, 2026-09-08). Note the order matters: the quantity test
 * runs BEFORE {@link ItemManager#canonicalize(int)}, which would otherwise fold a placeholder onto the real
 * item and let a ghost row add nothing but noise to the fold map.</li>
 * <li>{@code BANK_FILLER} (20594, clone {@code runelite-api/.../gameval/ItemID.java:50013}): the grey filler
 * the bank layout uses for spacing. Core skips it in the same place (BankPlugin.java:589-606, the test at
 * :600).</li>
 * <li>{@code COINS} (995, ItemID.java:3280) and {@code PLATINUM} (13204, ItemID.java:44396): currency, not a
 * traded item. RuneLite hard-codes their prices at 1 and 1000 rather than looking them up
 * (ItemManager.java:328-337), and the wiki price feed has no market for them, so a movement row would always
 * read 0 %. They are tested on the RAW id only, which is enough: the sole way a currency id could appear
 * after canonicalisation is as its own bank placeholder, and that carries quantity 0 and has already gone.
 * Skipped as a ROW is not the same as thrown away, though: since B097 their WORTH leaves with the snapshot as
 * {@link BankSnapshot#currencyGp}, because a "bank value" that omits the player's cash disagrees with the total
 * the bank interface itself has just shown them. {@link #currencyWorth} is that sum, and the only place it is
 * defined.</li>
 * <li>{@code !isGeTradeable()} on the CANONICAL composition (clone
 * {@code runelite-api/src/main/java/net/runelite/api/ItemComposition.java:145}): an item the Grand Exchange
 * does not list has no market price to move. It is the GE flag and not {@code isTradeable()} (:140, which
 * only means "can change hands between players") because the wiki price feed IS the exchange - core's own
 * exchange search filters on the same flag ({@code GrandExchangeSearchPanel.java:207}). Amended by the lead
 * 2026-09-08 13:55 (contract C11) and switched by the gate. NARROWED by addendum Q line Q5: such a stack is
 * skipped only when it has no High Alchemy value either ({@code getHaPrice()}, :120). One WITH a value is kept
 * and marked ({@link BankItem#untradeable}, {@link BankItem#haPrice}) so the "Include untradeable items" switch
 * can list it at that value; the switch itself lives in the service, not here, because a snapshot that recorded
 * only what the switch of the day wanted would need a bank visit every time it was flipped - the same reasoning
 * that puts {@link BankSnapshot#currencyGp} in every capture (P1). WIDENED again by addendum R line R1: a kept
 * untradeable stack also records what RuneLite maps it onto ({@link BankItem#parts}, {@link #partsOf}), so it can
 * be valued at its tradeable parts instead - a Crystal body at three Crystal armour seeds rather than at its 900k
 * alch value.</li>
 * </ul>
 *
 * <p><b>The name is {@code getMembersName()}</b> (addendum L line L8 b, and K3 before it). On a members world
 * the two composition names are identical; on a FREE world {@code getName()} appends " (Members)" to every
 * members item (clone {@code runelite-api/src/main/java/net/runelite/api/ItemComposition.java:34-41} against
 * {@code :43-49}, "the real item name, even if the player is on a F2P server"). The wiki's guide table has no
 * such key, so an F2P player reading their bank with {@code getName()} would lose the price of every members
 * item in it. The name is only ever the SECOND-choice lookup anyway - {@code PriceService} resolves an id
 * through the {@code /mapping} table first, because the composition-name route silently answers another item's
 * price for 287 of 4,662 ids, one of them in the user's own bank (id 3159, "Karambwan vessel (baited)" - L-F).
 *
 * <p>Canonicalisation is what folds a NOTED stack onto the item it notes (and, for the handful of ids in
 * {@code ItemManager.WORN_ITEMS}, a worn variant onto its base). Two bank slots that canonicalise to the same
 * id therefore become ONE row with the quantities summed, so a bank holding 500 noted and 3 un-noted of the
 * same thing prices 503 of it once.
 *
 * <p>Deliberately NOT final, and {@link #read} deliberately not final: the plugin's wiring test mocks this
 * class, and Mockito 4.11 without {@code mockito-inline} cannot mock a final class or stub a final method.
 */
public class BankReader
{
	private static final Logger log = LoggerFactory.getLogger(BankReader.class);

	/**
	 * The bank's item-container id (clone {@code runelite-api/src/main/java/net/runelite/api/gameval/
	 * InventoryID.java:102}, {@code public static final int BANK = 95}). Container 95 is {@code @Nullable}
	 * and absent until the player opens the bank once in the session, which is why the plugin captures on
	 * the event rather than polling (research claim C7, 2026-09-08).
	 */
	public static final int BANK_CONTAINER_ID = InventoryID.BANK;

	/**
	 * The player's carried inventory (clone {@code runelite-api/src/main/java/net/runelite/api/gameval/
	 * InventoryID.java:100}, {@code public static final int INV = 93}) - read beside the bank since addendum Y,
	 * line Y2, by {@link #readContainers}.
	 */
	public static final int INVENTORY_CONTAINER_ID = InventoryID.INV;

	/**
	 * The player's worn equipment (clone {@code runelite-api/src/main/java/net/runelite/api/gameval/
	 * InventoryID.java:101}, {@code public static final int WORN = 94}) - the other half of what "Include inventory
	 * and worn gear" counts (Y2).
	 */
	public static final int WORN_CONTAINER_ID = InventoryID.WORN;

	/**
	 * Rows leave in name order so the panel's "no sort key" tail and the dev bridge's {@code state} echo are
	 * reproducible; the id breaks a tie so two genuinely different items sharing a display name (clue
	 * scrolls, for one) never swap places between two captures of the same bank.
	 *
	 * <p>Package-private since addendum Y: {@code PriceService} puts the MERGED stacks - the bank's and the carried
	 * ones folded together (Y3) - in this same order, and two lists the sidebar draws from must not be ordered by
	 * two different rules.
	 */
	static final Comparator<BankItem> BY_NAME_THEN_ID = (a, b) ->
	{
		final String left = a.name == null ? "" : a.name;
		final String right = b.name == null ? "" : b.name;
		final int byName = left.compareTo(right);
		return byName != 0 ? byName : Integer.compare(a.id, b.id);
	};

	/**
	 * Parts leave in id order, then quantity (addendum R, R1). {@code ItemMapping.map} answers a
	 * {@code HashMultimap}'s collection, whose iteration order over enum constants follows their IDENTITY hash
	 * codes and so differs from one JVM run to the next. An order of our own keeps a captured stack - and the
	 * tooltip sentence built from it - the same on every launch.
	 */
	private static final Comparator<BankItem.Part> BY_PART_ID = (a, b) ->
	{
		final int byId = Integer.compare(a.id, b.id);
		return byId != 0 ? byId : Long.compare(a.quantity, b.quantity);
	};

	/**
	 * What the player is CARRYING and WEARING at one moment (addendum Y, line Y2): the inventory's stacks and the
	 * worn gear's, each folded onto canonical ids by exactly the bank's rules, and the coins and platinum tokens in
	 * hand as a worth rather than as rows - the carried half of {@link BankSnapshot}, which {@link
	 * BankSnapshot#withCarried(Carried)} puts on a snapshot.
	 *
	 * <p>Public final fields and no accessors, like the snapshot it is folded into: it is one read's answer, handed
	 * straight on. Never mutated after {@link #readContainers} builds it; the two lists are unmodifiable.
	 */
	public static final class Carried
	{
		/** Nothing carried and nothing worn, read at no particular time - what a logged-out read answers. */
		public static final Carried EMPTY = new Carried(Collections.emptyList(), Collections.emptyList(), 0L, 0L);

		/** The inventory's stacks, canonical, quantity-summed and name-ordered; never null, possibly empty. */
		public final List<BankItem> inventory;

		/** The worn gear's stacks, by the same rules; never null, possibly empty. */
		public final List<BankItem> worn;

		/**
		 * What the coins and platinum tokens in hand are worth, in gp ({@link #currencyWorth}) - the carried half of
		 * {@link BankSnapshot#currencyGp}, counted only while "Include coins and platinum tokens" is on and never a
		 * row, for the same reason the bank's cash is not one (design D6).
		 */
		public final long carriedGp;

		/** When the two containers were read, epoch millis; 0 means "never". */
		public final long readAtMillis;

		/**
		 * @param inventory    the inventory's stacks; null becomes empty
		 * @param worn         the worn gear's stacks; null becomes empty
		 * @param carriedGp    the coins and platinum tokens in hand, in gp; a negative reads as 0
		 * @param readAtMillis wall clock at the read; a negative reads as 0
		 */
		public Carried(final List<BankItem> inventory, final List<BankItem> worn, final long carriedGp,
			final long readAtMillis)
		{
			this.inventory = inventory == null
				? Collections.<BankItem>emptyList() : Collections.unmodifiableList(inventory);
			this.worn = worn == null ? Collections.<BankItem>emptyList() : Collections.unmodifiableList(worn);
			this.carriedGp = Math.max(0L, carriedGp);
			this.readAtMillis = Math.max(0L, readAtMillis);
		}

		/** True when the player holds and wears nothing priceable and carries no cash. */
		public boolean isEmpty()
		{
			return inventory.isEmpty() && worn.isEmpty() && carriedGp <= 0L;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}

			if (!(o instanceof Carried))
			{
				return false;
			}

			final Carried other = (Carried) o;
			return carriedGp == other.carriedGp
				&& readAtMillis == other.readAtMillis
				&& inventory.equals(other.inventory)
				&& worn.equals(other.worn);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(inventory, worn, carriedGp, readAtMillis);
		}

		@Override
		public String toString()
		{
			return "Carried{inventory=" + inventory.size() + ", worn=" + worn.size() + ", carriedGp=" + carriedGp
				+ ", readAtMillis=" + readAtMillis + '}';
		}
	}

	private final ItemManager itemManager;

	/**
	 * @param itemManager RuneLite's item manager, used for {@code canonicalize} and {@code getItemComposition}
	 *                    only - never for prices, which come from the wiki client
	 */
	public BankReader(final ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/**
	 * Whether an item-container event is the BANK, and so worth a capture. The plugin ignores every other
	 * container: the inventory, the deposit box and group storage all raise this event too.
	 *
	 * <p>{@link ItemContainerChanged} is a Lombok {@code @Value} class (clone
	 * {@code runelite-api/src/main/java/net/runelite/api/events/ItemContainerChanged.java:42-54}), so it is
	 * final and tests build real instances rather than mocks.
	 *
	 * @param event the event, may be null
	 * @return true only when {@code event} is non-null and its container id is {@link #BANK_CONTAINER_ID}
	 */
	public static boolean isBank(final ItemContainerChanged event)
	{
		return event != null && event.getContainerId() == BANK_CONTAINER_ID;
	}

	/**
	 * Reads a bank container into a snapshot. CLIENT THREAD ONLY - see the class javadoc.
	 *
	 * <p>An empty or null array still answers a snapshot stamped with this account, profile and time (an
	 * emptied bank is a captured bank), never {@code BankSnapshot.EMPTY}, whose zeroed stamps mean "nothing
	 * has ever been captured".
	 *
	 * @param items       the container's items, from {@code ItemContainer.getItems()}; may be null or hold
	 *                    null entries
	 * @param accountHash {@code client.getAccountHash()} - half of the persistence key
	 * @param profileType {@code RuneScapeProfileType.getCurrent(client).name()} - the other half; a null is
	 *                    stored as "" so a file name can never read "bank-123-null.json"
	 * @param nowMillis   wall clock at capture, stored as {@code capturedAtMillis} and shown as
	 *                    "Bank as of HH:MM"
	 * @return a snapshot whose items are canonical, quantity-summed and name-ordered - every GE-tradeable stack,
	 *         plus (Q5) every untradeable one worth alching, marked as such and carrying the tradeable parts
	 *         RuneLite maps it onto (R1) - carrying the worth of the coins and platinum tokens it did NOT make rows
	 *         of as {@link BankSnapshot#currencyGp} (B097)
	 */
	public BankSnapshot read(final Item[] items, final long accountHash, final String profileType, final long nowMillis)
	{
		final Map<Integer, BankItem> folded = new LinkedHashMap<>();
		final long currencyGp = fold(items, folded);

		return new BankSnapshot(rowsOf(folded), nowMillis, accountHash, profileType == null ? "" : profileType,
			currencyGp);
	}

	/**
	 * Reads the player's INVENTORY and WORN containers into the carried half of a snapshot (addendum Y, line Y2).
	 * CLIENT THREAD ONLY, for the reason {@link #read} is - it is the same cache lookups over two more containers.
	 *
	 * <p>Exactly the bank's rules, because a carried stack and a banked one become ONE row and must therefore have
	 * been read the same way: canonicalised (so a noted stack in the inventory folds onto the item it notes, and a
	 * worn variant onto its base), quantity-summed - which is what turns 28 inventory slots of the same item into
	 * one stack of 28 - placeholders, fillers and empty slots dropped, GE-untradeable stacks kept only when they
	 * alch for something and then carrying their High Alchemy value and their tradeable parts (Q5, R1).
	 *
	 * <p>Currency leaves as {@link Carried#carriedGp} and never as a row, exactly as the bank's does. Y2 names the
	 * inventory for it, which is the only container of the two that can hold coins or platinum tokens; the rule is
	 * applied to both so that a currency slot can never become a row whichever container it arrives in.
	 *
	 * <p>The two containers are read INDEPENDENTLY - an item in both is two stacks here, one in each list - because
	 * the row's hover has to name the parts separately ("3 in bank, 1 in inventory, 1 worn"). Adding them up is the
	 * service's job, done under the switch.
	 *
	 * @param inventory the inventory container's items ({@code ItemContainer.getItems()} of
	 *                  {@link #INVENTORY_CONTAINER_ID}); NULL when the client has no such container yet, which
	 *                  reads as an empty list rather than as an error
	 * @param worn      the worn container's items ({@link #WORN_CONTAINER_ID}); null reads the same way
	 * @param nowMillis wall clock at the read, stored as {@link Carried#readAtMillis}
	 * @return what the player carries and wears; {@link Carried#EMPTY} is never answered - an empty read is still a
	 *         read, and is stamped with {@code nowMillis}
	 */
	public Carried readContainers(@Nullable final Item[] inventory, @Nullable final Item[] worn, final long nowMillis)
	{
		final Map<Integer, BankItem> heldFold = new LinkedHashMap<>();
		final Map<Integer, BankItem> wornFold = new LinkedHashMap<>();
		final long held = fold(inventory, heldFold);
		final long onBody = fold(worn, wornFold);

		return new Carried(rowsOf(heldFold), rowsOf(wornFold), PortfolioMath.clampedAdd(held, onBody), nowMillis);
	}

	/**
	 * One container's slots, folded onto canonical ids in {@code folded} and its currency answered as a worth: the
	 * ONE walk of a container this plugin has, so the bank (C11) and the two carried containers (Y2) can never come
	 * to disagree about what a slot means.
	 *
	 * @param items  the container's items; null is an empty container, worth nothing
	 * @param folded the fold map, mutated in place; may already hold another container's stacks
	 * @return the coins and platinum tokens among those slots, in gp
	 */
	private long fold(@Nullable final Item[] items, final Map<Integer, BankItem> folded)
	{
		if (items == null)
		{
			return 0L;
		}

		long currencyGp = 0L;
		for (final Item item : items)
		{
			if (item == null)
			{
				continue;
			}

			// Currency leaves as a NUMBER, never as a row: it has no market to move (D6), but it is half of
			// what the player calls their bank value (B097). Taken on the RAW id, before canonicalisation, and
			// the same shape the dev bridge's synthetic bank= verb uses so the two cannot drift apart.
			final long currency = currencyWorth(item.getId(), item.getQuantity());
			if (currency > 0L)
			{
				currencyGp = PortfolioMath.clampedAdd(currencyGp, currency);
				continue;
			}

			accept(folded, item);
		}

		return currencyGp;
	}

	/** A fold map's stacks in the one row order this plugin has ({@link #BY_NAME_THEN_ID}). */
	private static List<BankItem> rowsOf(final Map<Integer, BankItem> folded)
	{
		final List<BankItem> rows = new ArrayList<>(folded.values());
		rows.sort(BY_NAME_THEN_ID);
		return rows;
	}

	/**
	 * What one slot of CURRENCY is worth, and 0 for anything else: coins at 1 gp each and platinum tokens at
	 * 1,000, which is exactly what RuneLite's own item manager answers for the two ids rather than looking them
	 * up (clone {@code runelite-client/src/main/java/net/runelite/client/game/ItemManager.java:328-337}), and so
	 * what the client's bundled Bank plugin counts them as in the bank title bar.
	 *
	 * <p>Public and static because two callers need the identical rule and neither should own a second copy of
	 * it: {@link #accept} on the live container, and the dev bridge's synthetic {@code bank=} verb
	 * ({@code BpmCommands}), whose made-up banks would otherwise be able to hold a coin ROW that no real bank
	 * can produce.
	 *
	 * <p>No clamp is needed here: the biggest stack the game can hold is {@link Integer#MAX_VALUE} platinum
	 * tokens, which is 2.1 x 10^12 gp - four million times short of a long. Summing MANY slots is where a clamp
	 * belongs, and {@link PortfolioMath#clampedAdd} is where it is.
	 *
	 * @param id       a raw item id, before canonicalisation
	 * @param quantity the slot's quantity; 0 or less is worth nothing
	 */
	public static long currencyWorth(final int id, final int quantity)
	{
		if (quantity <= 0)
		{
			return 0L;
		}

		if (id == ItemID.COINS)
		{
			return quantity;
		}

		if (id == ItemID.PLATINUM)
		{
			return quantity * 1000L;
		}

		return 0L;
	}

	/**
	 * Applies the C11 skip rules to one container slot and folds what survives into {@code folded}, keyed by
	 * canonical id. The map is a {@link LinkedHashMap} so the pre-sort order is bank order and the result is
	 * a function of the input alone.
	 *
	 * <p>Currency is not one of the rules here: {@link #read} takes a coins or platinum slot as a WORTH before
	 * this is called, so the only currency slot that can reach this method is one with no quantity, which the
	 * quantity rule below drops like any other empty slot.
	 *
	 * @param folded the fold map, mutated in place
	 * @param item   one container slot, never null
	 */
	private void accept(final Map<Integer, BankItem> folded, final Item item)
	{
		final int id = item.getId();
		if (id <= 0)
		{
			return;
		}

		final int quantity = item.getQuantity();
		if (quantity <= 0)
		{
			return;
		}

		if (id == ItemID.BANK_FILLER)
		{
			return;
		}

		final int canonical = itemManager.canonicalize(id);
		final ItemComposition composition = itemManager.getItemComposition(canonical);
		if (composition == null)
		{
			// Unreachable against a live client - getItemDefinition is @Nonnull (Client.java:393-394) - but
			// this loop runs inside an @Subscribe handler on the client thread, where one thrown NPE would
			// abandon the whole capture. One odd id costs one row instead.
			log.debug("no composition for canonical item {} (raw {}), skipping", canonical, id);
			return;
		}

		// Q5: not listed by the Grand Exchange is no longer the end of it - a stack with a High Alchemy value is
		// kept and marked, and the service decides whether to show it. getHaPrice is read only on this branch, so
		// an ordinary bank costs exactly the cache lookups it did before.
		final boolean untradeable = !composition.isGeTradeable();
		final int haPrice = untradeable ? composition.getHaPrice() : 0;
		if (untradeable && haPrice <= 0)
		{
			return;
		}

		final BankItem existing = folded.get(canonical);
		if (existing != null)
		{
			existing.quantity = addClamped(existing.quantity, quantity);
			return;
		}

		// getMembersName, never getName: on a FREE world getName appends " (Members)" to every members item
		// (clone runelite-api/src/main/java/net/runelite/api/ItemComposition.java:34-41 vs :43-49, tag
		// runelite-parent-1.12.37), and that suffix is not in the guide table, so an F2P player would lose the
		// price of every members item in their bank. Addendum L line L8 (b) makes this the composition name the
		// plugin uses. There is deliberately no fallback to getName() when this is empty: falling back would put
		// the suffix straight back into the lookup for exactly the items it breaks.
		final String name = composition.getMembersName();
		folded.put(canonical, new BankItem(
			canonical,
			quantity,
			name == null ? "" : name,
			composition.isStackable(),
			untradeable,
			haPrice,
			untradeable ? partsOf(canonical) : null));
	}

	/**
	 * What RuneLite says ONE of an untradeable item reverts to (addendum R, line R1): every
	 * {@code ItemMapping.map(canonicalId)} entry as a {@link BankItem.Part} - the mapping's
	 * {@code getTradeableItem()}, its {@code getQuantity()}, and that part's {@code getMembersName()} (the naming
	 * rule of L8 b, so the guide table can be asked for it by name) - in id order. Null when RuneLite maps the id
	 * to nothing, which is when the stack keeps the alch rule of Q5.
	 *
	 * <p>Asked only for a stack already KEPT as untradeable, so an ordinary bank costs not one extra lookup.
	 * {@code ItemMapping.map} is static and pure - its table is built once from the enum and
	 * {@code ItemVariationMapping} - so the only client reads here are the compositions the names come from, on the
	 * client thread with the rest of {@link #read}.
	 *
	 * <p>An entry with a non-positive id or quantity names nothing that can be priced and is dropped; if that
	 * leaves none, the answer is null rather than an empty list, because "RuneLite maps this to nothing" and "the
	 * mapping named nothing usable" are the same answer to every reader.
	 *
	 * @param canonical the stack's canonical id
	 */
	@Nullable
	private List<BankItem.Part> partsOf(final int canonical)
	{
		final Collection<ItemMapping> mappings;
		try
		{
			mappings = ItemMapping.map(canonical);
		}
		catch (final RuntimeException | LinkageError e)
		{
			// Read as "not mapped", which is the pre-R behaviour: the stack keeps its alch value. Mirrors
			// PriceService.rewrittenByItemMapping, the other caller of this enum.
			log.debug("bank-portfolio-tracker: ItemMapping could not be consulted for item {}", canonical, e);
			return null;
		}

		if (mappings == null || mappings.isEmpty())
		{
			return null;
		}

		final List<BankItem.Part> parts = new ArrayList<>(mappings.size());
		for (final ItemMapping mapping : mappings)
		{
			if (mapping == null)
			{
				continue;
			}

			final int partId = mapping.getTradeableItem();
			final long partQuantity = mapping.getQuantity();
			if (partId <= 0 || partQuantity <= 0L)
			{
				continue;
			}

			parts.add(new BankItem.Part(partId, partQuantity, partName(partId)));
		}

		if (parts.isEmpty())
		{
			return null;
		}

		parts.sort(BY_PART_ID);
		return parts;
	}

	/**
	 * A part's name for the L8 b lookup: {@code getMembersName()} on its own composition, "" when the client
	 * cannot serve one. Never throws - the capture loop runs inside an {@code @Subscribe} handler, where one
	 * escaped exception would abandon the whole bank for the sake of one part's name, and a nameless part is still
	 * priced by its id.
	 */
	private String partName(final int partId)
	{
		try
		{
			final ItemComposition part = itemManager.getItemComposition(partId);
			final String name = part == null ? null : part.getMembersName();
			return name == null ? "" : name;
		}
		catch (final RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: no composition for mapped part {}", partId, e);
			return "";
		}
	}

	/**
	 * Sums two bank quantities without wrapping. Two int quantities can genuinely overflow an int - a bank
	 * can hold 2,147,483,647 of a stackable item AND that many again as a noted stack that folds onto it -
	 * so the sum is done in a long and clamped. Clamping rather than throwing keeps the capture alive; the
	 * row is already at the game's own display ceiling.
	 *
	 * <p>Package-private rather than private since addendum Y: {@code PriceService} adds a carried stack's quantity
	 * onto the banked one's when "Include inventory and worn gear" is on (Y3), and two callers of the same rule must
	 * not be two copies of it.
	 *
	 * @param a a non-negative quantity
	 * @param b a non-negative quantity
	 * @return {@code a + b}, or {@link Integer#MAX_VALUE} when that would overflow
	 */
	static int addClamped(final int a, final int b)
	{
		final long sum = (long) a + (long) b;
		return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
	}
}
