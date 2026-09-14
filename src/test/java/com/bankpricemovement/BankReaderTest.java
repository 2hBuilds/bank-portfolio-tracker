package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link BankReader} against the C11/C12 contract: the skip rules in their stated order, the canonical fold,
 * the stamps and the ordering.
 *
 * <p>Fixtures, not claims about the live cache. The item ids are chosen so the RELATIONSHIP under test is
 * obvious ({@link #WHIP} / {@link #WHIP_NOTED} / {@link #WHIP_PLACEHOLDER}); only the three real constants -
 * bank filler 20594, coins 995, platinum tokens 13204 - are the game's own ids, because the code tests
 * against them by value.
 *
 * <p>{@link ItemManager} (a non-final class) and {@link ItemComposition} (an interface) are mocked, which
 * Mockito 4.11 handles without {@code mockito-inline}; {@link Item} and {@link ItemContainerChanged} are
 * Lombok {@code @Value} classes and therefore final, so those are built for real.
 */
public class BankReaderTest
{
	private static final int WHIP = 4151;
	private static final int WHIP_NOTED = 4152;
	private static final int WHIP_PLACEHOLDER = 14032;
	private static final String WHIP_NAME = "Abyssal whip";

	private static final int BONES = 526;
	private static final String BONES_NAME = "Bones";

	private static final int SHARK = 385;
	private static final String SHARK_NAME = "Shark";

	private static final int RUNE_ARROW = 892;

	private static final int BANK_FILLER = 20594;
	private static final int COINS = 995;
	private static final int PLATINUM = 13204;

	private static final long ACCOUNT = 0x1234_5678_9abcL;
	private static final String PROFILE = "STANDARD";
	private static final long NOW = 1_757_000_000_000L;

	private final ItemManager itemManager = mock(ItemManager.class);

	/** raw id -&gt; the id {@code canonicalize} answers for it; absent means "canonical, answers itself". */
	private final Map<Integer, Integer> folds = new HashMap<>();

	/** id -&gt; the composition {@code getItemComposition} answers for it; absent means null. */
	private final Map<Integer, ItemComposition> compositions = new HashMap<>();

	private BankReader reader;

	@Before
	public void setUp()
	{
		when(itemManager.canonicalize(anyInt())).thenAnswer(invocation ->
		{
			final int id = invocation.getArgument(0);
			return folds.getOrDefault(id, id);
		});
		when(itemManager.getItemComposition(anyInt()))
			.thenAnswer(invocation -> compositions.get(invocation.getArgument(0)));

		reader = new BankReader(itemManager);

		define(WHIP, WHIP_NAME, true, false);
		define(BONES, BONES_NAME, true, false);
		define(SHARK, SHARK_NAME, true, false);
		folds.put(WHIP_NOTED, WHIP);
		folds.put(WHIP_PLACEHOLDER, WHIP);
	}

	// ---------------------------------------------------------------- C12: the container id and isBank

	@Test
	public void bankContainerIdIsTheGamevalNinetyFive()
	{
		assertEquals("gameval InventoryID.BANK (clone InventoryID.java:102)", 95, BankReader.BANK_CONTAINER_ID);
	}

	@Test
	public void isBankAcceptsContainerNinetyFive()
	{
		assertTrue(BankReader.isBank(new ItemContainerChanged(95, mock(ItemContainer.class))));
	}

	@Test
	public void isBankRejectsEveryOtherContainer()
	{
		final ItemContainer container = mock(ItemContainer.class);
		assertFalse("inventory", BankReader.isBank(new ItemContainerChanged(93, container)));
		assertFalse("equipment", BankReader.isBank(new ItemContainerChanged(94, container)));
		assertFalse("group storage", BankReader.isBank(new ItemContainerChanged(659, container)));
		assertFalse("zero", BankReader.isBank(new ItemContainerChanged(0, container)));
	}

	@Test
	public void isBankRejectsNull()
	{
		assertFalse(BankReader.isBank(null));
	}

	// ---------------------------------------------------------------- C11: the skip rules, in order

	@Test
	public void nullSlotsAreSkipped()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{null, item(WHIP, 1), null}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(WHIP, snapshot.items.get(0).id);
	}

	@Test
	public void nonPositiveIdsAreSkipped()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(-1, 5), item(0, 5), item(WHIP, 1)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(WHIP, snapshot.items.get(0).id);
	}

	@Test
	public void placeholdersAreSkippedOnTheirZeroQuantity()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(WHIP_PLACEHOLDER, 0), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	/**
	 * The order in C11 is load-bearing: the quantity test runs BEFORE canonicalisation, so a placeholder
	 * never reaches the fold map at all. Were it canonicalised first it would land on the real item's key
	 * with quantity 0 - harmless arithmetically, but it would then decide that row's name and stackable flag
	 * when the placeholder happens to sit above the real stack in the container.
	 */
	@Test
	public void aPlaceholderIsSkippedWithoutBeingCanonicalised()
	{
		reader.read(new Item[]{item(WHIP_PLACEHOLDER, 0)}, ACCOUNT, PROFILE, NOW);

		verify(itemManager, never()).canonicalize(WHIP_PLACEHOLDER);
	}

	@Test
	public void negativeQuantitiesAreSkipped()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(WHIP, -4), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	@Test
	public void bankFillerIsSkipped()
	{
		define(BANK_FILLER, "Bank filler", true, false);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(BANK_FILLER, 100), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	@Test
	public void coinsAreSkipped()
	{
		define(COINS, "Coins", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(COINS, 50_000_000), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	@Test
	public void platinumTokensAreSkipped()
	{
		define(PLATINUM, "Platinum token", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(PLATINUM, 1000), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	/**
	 * B097. Skipped as a ROW is not thrown away: coins are worth 1 gp each, which is what RuneLite's own item
	 * manager answers for the id rather than looking it up (clone {@code ItemManager.java:328-337}) and so what
	 * the client's bundled Bank plugin counts in the bank title bar. A "bank value" without them disagrees with
	 * that number by exactly the player's cash.
	 */
	@Test
	public void coinsLeaveAsCurrencyWorthAtOneGpEach()
	{
		define(COINS, "Coins", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(COINS, 50_000_000), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals("still not a row", 1, snapshot.items.size());
		assertEquals(50_000_000L, snapshot.currencyGp);
	}

	/** The other half of the same rule: a platinum token is 1,000 gp (ItemManager.java:333-336). */
	@Test
	public void platinumTokensLeaveAsCurrencyWorthAtOneThousandGpEach()
	{
		define(PLATINUM, "Platinum token", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(PLATINUM, 1000), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals("still not a row", 1, snapshot.items.size());
		assertEquals(1_000_000L, snapshot.currencyGp);
	}

	/** Every currency slot in the container counts, and the two kinds add up. */
	@Test
	public void everyCurrencySlotAddsToTheOneTotal()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(COINS, 250), item(BONES, 3), item(PLATINUM, 4), item(COINS, 750)},
			ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals("250 + 750 coins and 4 platinum", 5_000L, snapshot.currencyGp);
	}

	/** A bank with no currency in it says so with a zero, never with a stale or negative number. */
	@Test
	public void aBankWithoutCurrencyCarriesNoCurrencyWorth()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(WHIP, 1), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(0L, snapshot.currencyGp);
	}

	/**
	 * The extreme case B097 names: a bank of nothing but cash has no rows at all, and its worth must still leave
	 * with the snapshot - otherwise the player is told their bank is worth 0.
	 */
	@Test
	public void aBankOfNothingButCurrencyStillCarriesItsWorth()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{item(COINS, 200_000_000)}, ACCOUNT, PROFILE, NOW);

		assertTrue("no tradeable stack to make a row of", snapshot.items.isEmpty());
		assertEquals(200_000_000L, snapshot.currencyGp);
		assertEquals("and it is still a capture", NOW, snapshot.capturedAtMillis);
	}

	/**
	 * The biggest currency a bank can hold is {@link Integer#MAX_VALUE} platinum tokens - 2.1 x 10^12 gp - so the
	 * multiplication must be done in a long. In an int it wraps to -727,379,968 and the bank is worth less than
	 * nothing.
	 */
	@Test
	public void theLargestCurrencyStackIsCountedInALongNotAnInt()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(PLATINUM, Integer.MAX_VALUE), item(COINS, Integer.MAX_VALUE)}, ACCOUNT, PROFILE, NOW);

		assertEquals(2_147_483_647_000L + 2_147_483_647L, snapshot.currencyGp);
		assertTrue(snapshot.currencyGp > 0L);
	}

	/** A currency PLACEHOLDER is a quantity of 0 and is worth nothing, exactly as it makes no row. */
	@Test
	public void aCurrencySlotOfZeroIsWorthNothing()
	{
		assertEquals(0L, reader.read(new Item[]{item(COINS, 0), item(PLATINUM, 0)}, ACCOUNT, PROFILE, NOW).currencyGp);
		assertEquals(0L, BankReader.currencyWorth(COINS, 0));
		assertEquals(0L, BankReader.currencyWorth(COINS, -5));
	}

	/** Nothing else is currency - the rule is the two ids and no other, so no ordinary stack is counted twice. */
	@Test
	public void currencyWorthIsZeroForEveryOtherItem()
	{
		assertEquals(0L, BankReader.currencyWorth(WHIP, 3));
		assertEquals(0L, BankReader.currencyWorth(BANK_FILLER, 3));
		assertEquals(0L, BankReader.currencyWorth(0, 3));
	}

	/** Currency is rejected on the raw id, before any cache lookup - the cheap test comes first. */
	@Test
	public void currencyAndFillerAreSkippedWithoutBeingCanonicalised()
	{
		reader.read(new Item[]{item(COINS, 5), item(PLATINUM, 5), item(BANK_FILLER, 5)}, ACCOUNT, PROFILE, NOW);

		verify(itemManager, never()).canonicalize(COINS);
		verify(itemManager, never()).canonicalize(PLATINUM);
		verify(itemManager, never()).canonicalize(BANK_FILLER);
	}

	/**
	 * C11 as amended by the lead (2026-09-08 13:55): the flag is {@code isGeTradeable()} (clone
	 * {@code ItemComposition.java:145}, what {@code GrandExchangeSearchPanel.java:207} filters on), because the
	 * wiki price feed is the exchange. {@code isTradeable()} (:140) only means "can change hands between
	 * players" and is never consulted - a player-tradeable item the exchange does not list has no market price.
	 */
	@Test
	public void itemsTheGrandExchangeDoesNotListAreSkipped()
	{
		final int quest = 772;
		final ItemComposition dramen = define(quest, "Dramen staff", false, false);
		when(dramen.isTradeable()).thenReturn(true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(quest, 1), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
		verify(dramen, never()).isTradeable();
	}

	// ---------------------------------------------------------------- Q5: untradeable stacks worth alching

	/**
	 * Addendum Q line Q5 narrows the GE rule rather than dropping it: a stack the exchange does not list is kept
	 * when it has a High Alchemy value, marked as untradeable and carrying that value, so the "Include
	 * untradeable items" switch can list it without waiting for the next bank visit. The switch itself is the
	 * service's; the reader always records.
	 */
	@Test
	public void anUntradeableStackWithAnAlchValueIsKeptAndMarked()
	{
		final int dramen = 772;
		when(define(dramen, "Dramen staff", false, false).getHaPrice()).thenReturn(1500);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(dramen, 2), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(2, snapshot.items.size());
		final BankItem row = rowFor(snapshot, dramen);
		assertTrue(row.untradeable);
		assertEquals(1500, row.haPrice);
		assertEquals(Long.valueOf(1500L), row.alchPrice());
		assertEquals(2, row.quantity);
		assertEquals("Dramen staff", row.name);
	}

	/** No market and nothing to alch is nothing at all - the C11 skip stands for it. */
	@Test
	public void anUntradeableStackWithNoAlchValueIsStillSkipped()
	{
		final int quest = 773;
		when(define(quest, "Quest item", false, false).getHaPrice()).thenReturn(0);
		final int negative = 774;
		when(define(negative, "Odd item", false, false).getHaPrice()).thenReturn(-1);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(quest, 1), item(negative, 1), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	/** An ordinary stack is not marked and records no alch value: its price is the guide price, looked up live. */
	@Test
	public void aTradeableStackIsNeverMarkedAndKeepsNoAlchValue()
	{
		final ItemComposition whip = compositions.get(WHIP);
		when(whip.getHaPrice()).thenReturn(72_000);

		final BankItem row = reader.read(new Item[]{item(WHIP, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertFalse(row.untradeable);
		assertEquals(0, row.haPrice);
		assertNull(row.alchPrice());
		verify(whip, never()).getHaPrice();
	}

	// ---------------------------------------------------------------- R1: the tradeable parts of an untradeable stack

	/**
	 * REAL ids, because the rule under test is a lookup in RuneLite's own static table:
	 * {@code ITEM_CRYSTAL_BODY(PRIF_ARMOUR_SEED, true, 3L, CRYSTAL_CHESTPLATE)} (clone
	 * {@code runelite-client/.../game/ItemMapping.java:379}), so {@code ItemMapping.map(23975)} answers one mapping
	 * naming item 23956 three times over. Confirmed against the cached 1.12.38 client jar, 2026-09-11.
	 */
	private static final int CRYSTAL_BODY = 23_975;
	private static final int CRYSTAL_HELM = 23_971;
	private static final int ARMOUR_SEED = 23_956;
	private static final String SEED_NAME = "Crystal armour seed";
	/** Mapped onto TWO tradeables - a crystal tool seed and a crystal felling axe - which is what orders them. */
	private static final int CRYSTAL_2H_AXE = 28_220;
	private static final int TOOL_SEED = 23_953;
	private static final int FELLING_AXE = 28_217;

	/**
	 * R1: a kept untradeable stack records what RuneLite maps it onto, so it can be valued at what its parts fetch
	 * on the exchange rather than at its alch value. The alch value is recorded all the same - it is the fallback
	 * for a part the guide tables cannot price (R2).
	 */
	@Test
	public void anUntradeableStackRecordsTheTradeablePartsRuneLiteMapsItOnto()
	{
		when(define(CRYSTAL_BODY, "Crystal body", false, false).getHaPrice()).thenReturn(900_000);
		define(ARMOUR_SEED, SEED_NAME, true, false);

		final BankItem row = reader.read(new Item[]{item(CRYSTAL_BODY, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertTrue(row.untradeable);
		assertEquals("the alch value is still recorded, as the fallback", 900_000, row.haPrice);
		assertTrue(row.hasParts());
		assertEquals(1, row.parts.size());
		assertEquals(new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME), row.parts.get(0));
	}

	/** The part's name is {@code getMembersName()}, the same naming rule the rows use (L8 b). */
	@Test
	public void aPartIsNamedByItsMembersNameNeverByGetName()
	{
		when(define(CRYSTAL_HELM, "Crystal helm", false, false).getHaPrice()).thenReturn(300_000);
		final ItemComposition seed = define(ARMOUR_SEED, SEED_NAME, true, false);
		when(seed.getName()).thenReturn(SEED_NAME + " (Members)");

		final BankItem row = reader.read(new Item[]{item(CRYSTAL_HELM, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertEquals(new BankItem.Part(ARMOUR_SEED, 1L, SEED_NAME), row.parts.get(0));
		verify(seed, never()).getName();
	}

	/**
	 * {@code ItemMapping.map} answers a {@code HashMultimap}'s collection, whose order over enum constants follows
	 * identity hash codes and so differs from one JVM run to the next. The reader imposes id order, or a captured
	 * stack - and the tooltip sentence built from it - would shuffle between launches.
	 *
	 * <p>The fixture marks a GE-TRADEABLE id untradeable on purpose: item 28220 is the only two-part mapping this
	 * plugin has ever had reason to name (B001), and the ordering rule needs two parts to order.
	 */
	@Test
	public void partsLeaveInIdOrder()
	{
		when(define(CRYSTAL_2H_AXE, "Crystal 2h axe", false, false).getHaPrice()).thenReturn(10_000);
		define(TOOL_SEED, "Crystal tool seed", true, false);
		define(FELLING_AXE, "Crystal felling axe", true, false);

		final BankItem row = reader.read(new Item[]{item(CRYSTAL_2H_AXE, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertEquals(2, row.parts.size());
		assertEquals(TOOL_SEED, row.parts.get(0).id);
		assertEquals(FELLING_AXE, row.parts.get(1).id);
	}

	/** An untradeable stack RuneLite maps to nothing keeps Q5's alch rule and records no parts. */
	@Test
	public void anUntradeableStackWithNoMappingRecordsNoParts()
	{
		final int dramen = 772;
		when(define(dramen, "Dramen staff", false, false).getHaPrice()).thenReturn(1500);

		final BankItem row = reader.read(new Item[]{item(dramen, 2)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertTrue(row.untradeable);
		assertNull(row.parts);
		assertFalse(row.hasParts());
	}

	/** A stack the exchange DOES list is priced by its own guide series; nothing is looked up for it. */
	@Test
	public void aTradeableStackRecordsNoPartsEvenWhenRuneLiteMapsTheId()
	{
		define(CRYSTAL_2H_AXE, "Crystal 2h axe", true, false);

		final BankItem row = reader.read(new Item[]{item(CRYSTAL_2H_AXE, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertFalse(row.untradeable);
		assertNull("its price is its own guide price, not a sum over parts", row.parts);
	}

	/**
	 * The name is a convenience for the guide table's second-choice lookup, not a requirement: a part whose
	 * composition the client cannot serve is still recorded and still priced by its id.
	 */
	@Test
	public void aPartWithNoCompositionIsStillRecordedUnderAnEmptyName()
	{
		when(define(CRYSTAL_BODY, "Crystal body", false, false).getHaPrice()).thenReturn(900_000);

		final BankItem row = reader.read(new Item[]{item(CRYSTAL_BODY, 1)}, ACCOUNT, PROFILE, NOW).items.get(0);

		assertEquals(new BankItem.Part(ARMOUR_SEED, 3L, ""), row.parts.get(0));
	}

	/**
	 * Q5's keep rule is unchanged by R1: parts are recorded for a stack the reader KEEPS, and a stack with nothing
	 * to alch is not kept. A mapped item with no alch value would be dropped here - which is the same answer R2
	 * gives such a stack when its parts cannot be priced either ("an ALCH row, or dropped when haPrice is 0").
	 */
	@Test
	public void aMappedStackWithNoAlchValueIsStillSkipped()
	{
		when(define(CRYSTAL_BODY, "Crystal body", false, false).getHaPrice()).thenReturn(0);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(CRYSTAL_BODY, 1), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	/** Marked stacks fold and order exactly like any other: the mark changes what they are worth, not the read. */
	@Test
	public void untradeableStacksFoldAndOrderLikeEveryOtherStack()
	{
		final int dramen = 772;
		final int dramenNoted = 773;
		when(define(dramen, "Dramen staff", false, false).getHaPrice()).thenReturn(1500);
		folds.put(dramenNoted, dramen);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(SHARK, 1), item(dramenNoted, 4), item(BONES, 1), item(dramen, 2)},
			ACCOUNT, PROFILE, NOW);

		assertEquals(Arrays.asList("Bones", "Dramen staff", "Shark"), namesOf(snapshot));
		assertEquals("noted and un-noted summed on the canonical id", 6, rowFor(snapshot, dramen).quantity);
		assertEquals(1500, rowFor(snapshot, dramen).haPrice);
	}

	/**
	 * Unreachable against a live client ({@code Client.getItemDefinition} is {@code @Nonnull}), but the loop
	 * runs inside an {@code @Subscribe} handler on the client thread, where a thrown NPE would abandon the
	 * whole capture. One odd id must cost one row, not the bank.
	 */
	@Test
	public void anItemWithNoCompositionIsSkippedRatherThanThrowing()
	{
		final int unknown = 99_999;

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(unknown, 1), item(BONES, 3)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(BONES, snapshot.items.get(0).id);
	}

	// ---------------------------------------------------------------- C11: canonicalisation and folding

	@Test
	public void aNotedStackIsPricedAsTheCanonicalItem()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{item(WHIP_NOTED, 7)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		final BankItem row = snapshot.items.get(0);
		assertEquals("the row carries the canonical id", WHIP, row.id);
		assertEquals(7, row.quantity);
		assertEquals(WHIP_NAME, row.name);
	}

	@Test
	public void notedAndUnNotedStacksFoldIntoOneRow()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(WHIP, 3), item(WHIP_NOTED, 500)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		final BankItem row = snapshot.items.get(0);
		assertEquals(WHIP, row.id);
		assertEquals("quantities are summed", 503, row.quantity);
	}

	@Test
	public void repeatedSlotsOfTheSameItemFoldIntoOneRow()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(BONES, 10), item(SHARK, 1), item(BONES, 5), item(BONES, 1)},
			ACCOUNT, PROFILE, NOW);

		assertEquals(2, snapshot.items.size());
		assertEquals(16, rowFor(snapshot, BONES).quantity);
		assertEquals(1, rowFor(snapshot, SHARK).quantity);
	}

	/**
	 * A stackable item can sit at the game's 2,147,483,647 ceiling AND have a noted stack folding onto it, so
	 * the sum genuinely overflows an int. It is done in a long and clamped rather than wrapped negative.
	 */
	@Test
	public void aFoldedQuantityIsClampedAtIntegerMaxValueRatherThanWrapping()
	{
		final BankSnapshot snapshot = reader.read(
			new Item[]{item(WHIP, Integer.MAX_VALUE), item(WHIP_NOTED, Integer.MAX_VALUE)},
			ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertEquals(Integer.MAX_VALUE, snapshot.items.get(0).quantity);
	}

	/**
	 * The GE-tradeable test, the name and the stackable flag all come from the CANONICAL composition: a noted id
	 * has its own composition in the cache and it is not the one the price and the picture belong to. Here
	 * the noted id has no composition at all, which would be a skip if the raw id were consulted.
	 */
	@Test
	public void theCompositionConsultedIsTheCanonicalOne()
	{
		final int notedOnly = 5001;
		final int base = 5000;
		folds.put(notedOnly, base);
		define(base, "Fixture item", true, true);

		final BankSnapshot snapshot = reader.read(new Item[]{item(notedOnly, 2)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		final BankItem row = snapshot.items.get(0);
		assertEquals(base, row.id);
		assertEquals("Fixture item", row.name);
		assertTrue("stackable comes from the canonical composition", row.stackable);
	}

	@Test
	public void stackableIsCarriedThroughBothWays()
	{
		define(RUNE_ARROW, "Rune arrow", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(SHARK, 1), item(RUNE_ARROW, 900)}, ACCOUNT, PROFILE, NOW);

		assertFalse("a shark is one bank slot per fish", rowFor(snapshot, SHARK).stackable);
		assertTrue(rowFor(snapshot, RUNE_ARROW).stackable);
	}

	/**
	 * Addendum L line L8 (b). On a FREE world {@code getName()} appends " (Members)" to every members item
	 * (clone {@code runelite-api/src/main/java/net/runelite/api/ItemComposition.java:34-41} against
	 * {@code :43-49}); the wiki's guide table has no such key, so an F2P player reading their bank through
	 * {@code getName()} would lose the price of every members item in it. The row must carry the real name.
	 */
	@Test
	public void theNameIsTheRealOneEvenOnAFreeWorld()
	{
		final ItemComposition whip = compositions.get(WHIP);
		when(whip.getName()).thenReturn(WHIP_NAME + " (Members)");

		final BankSnapshot snapshot = reader.read(new Item[]{item(WHIP, 1)}, ACCOUNT, PROFILE, NOW);

		assertEquals(WHIP_NAME, snapshot.items.get(0).name);
		verify(whip, never()).getName();
	}

	@Test
	public void aNullNameBecomesTheEmptyStringNeverNull()
	{
		final int nameless = 7777;
		define(nameless, null, true, false);

		final BankSnapshot snapshot = reader.read(new Item[]{item(nameless, 1)}, ACCOUNT, PROFILE, NOW);

		assertEquals(1, snapshot.items.size());
		assertNotNull(snapshot.items.get(0).name);
		assertEquals("", snapshot.items.get(0).name);
	}

	// ---------------------------------------------------------------- C11: ordering

	@Test
	public void rowsLeaveInNameOrder()
	{
		final int zamorak = 189;
		final int adamant = 1247;
		define(zamorak, "Zamorak brew(4)", true, false);
		define(adamant, "Adamant spear", true, false);

		final BankSnapshot snapshot = reader.read(
			new Item[]{item(zamorak, 1), item(SHARK, 1), item(BONES, 1), item(adamant, 1)},
			ACCOUNT, PROFILE, NOW);

		assertEquals(
			Arrays.asList("Adamant spear", "Bones", "Shark", "Zamorak brew(4)"),
			namesOf(snapshot));
	}

	/** Two genuinely different items can share a display name; the id keeps the order reproducible. */
	@Test
	public void equalNamesAreOrderedByIdSoTwoCapturesAgree()
	{
		final int clueA = 19835;
		final int clueB = 12073;
		define(clueA, "Clue scroll (elite)", true, false);
		define(clueB, "Clue scroll (elite)", true, false);

		final BankSnapshot later = reader.read(new Item[]{item(clueA, 1), item(clueB, 1)}, ACCOUNT, PROFILE, NOW);
		final BankSnapshot earlier = reader.read(new Item[]{item(clueB, 1), item(clueA, 1)}, ACCOUNT, PROFILE, NOW);

		assertEquals(clueB, later.items.get(0).id);
		assertEquals(clueA, later.items.get(1).id);
		assertEquals("bank order must not change the answer", clueB, earlier.items.get(0).id);
		assertEquals(clueA, earlier.items.get(1).id);
	}

	// ---------------------------------------------------------------- C11: the stamps and the empty cases

	@Test
	public void theSnapshotCarriesTheAccountProfileAndCaptureTime()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{item(BONES, 1)}, ACCOUNT, "IRONMAN", NOW);

		assertEquals(ACCOUNT, snapshot.accountHash);
		assertEquals("IRONMAN", snapshot.profileType);
		assertEquals(NOW, snapshot.capturedAtMillis);
	}

	@Test
	public void aNullProfileTypeIsStoredAsTheEmptyStringNeverNull()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{item(BONES, 1)}, ACCOUNT, null, NOW);

		assertNotNull(snapshot.profileType);
		assertEquals("", snapshot.profileType);
	}

	/**
	 * An emptied bank is still a CAPTURED bank, so it keeps its stamps rather than degrading to
	 * {@code BankSnapshot.EMPTY}, whose zeroed times mean "nothing has ever been captured".
	 */
	@Test
	public void anEmptyContainerAnswersAnEmptyButStampedSnapshot()
	{
		final BankSnapshot snapshot = reader.read(new Item[0], ACCOUNT, PROFILE, NOW);

		assertNotNull(snapshot.items);
		assertTrue(snapshot.items.isEmpty());
		assertEquals(NOW, snapshot.capturedAtMillis);
		assertEquals(ACCOUNT, snapshot.accountHash);
	}

	@Test
	public void aNullContainerArrayIsHarmless()
	{
		final BankSnapshot snapshot = reader.read(null, ACCOUNT, PROFILE, NOW);

		assertNotNull(snapshot.items);
		assertTrue(snapshot.items.isEmpty());
		assertEquals(NOW, snapshot.capturedAtMillis);
	}

	@Test
	public void aBankOfNothingButSkippedSlotsAnswersNoRows()
	{
		define(BANK_FILLER, "Bank filler", true, false);
		define(COINS, "Coins", true, true);

		final BankSnapshot snapshot = reader.read(
			new Item[]{null, item(0, 0), item(BANK_FILLER, 8), item(COINS, 1), item(WHIP_PLACEHOLDER, 0)},
			ACCOUNT, PROFILE, NOW);

		assertTrue(snapshot.items.isEmpty());
	}

	// ---------------------------------------------------------------- Y2: the inventory and the worn gear

	@Test
	public void theCarriedContainerIdsAreTheGamevalNinetyThreeAndNinetyFour()
	{
		assertEquals("gameval InventoryID.INV (clone InventoryID.java:100)", 93, BankReader.INVENTORY_CONTAINER_ID);
		assertEquals("gameval InventoryID.WORN (clone InventoryID.java:101)", 94, BankReader.WORN_CONTAINER_ID);
	}

	/** Y2: the two containers are read in one pass and kept APART - the row's hover has to name each side. */
	@Test
	public void readContainersKeepsTheInventoryAndTheWornGearApart()
	{
		final BankReader.Carried carried = reader.readContainers(
			new Item[]{item(SHARK, 3), item(BONES, 2)}, new Item[]{item(WHIP, 1)}, NOW);

		assertEquals(2, carried.inventory.size());
		assertEquals("name order, like every list this plugin draws", BONES, carried.inventory.get(0).id);
		assertEquals(SHARK, carried.inventory.get(1).id);
		assertEquals(3, carried.inventory.get(1).quantity);
		assertEquals(1, carried.worn.size());
		assertEquals(WHIP, carried.worn.get(0).id);
		assertEquals(WHIP_NAME, carried.worn.get(0).name);
		assertEquals(NOW, carried.readAtMillis);
		assertEquals(0L, carried.carriedGp);
		assertFalse(carried.isEmpty());
	}

	/**
	 * The bank's rules, applied to a container that has no placeholders but plenty of repeats: 28 slots of the same
	 * unstackable item are ONE stack of 28, a noted stack folds onto what it notes, and the empty slots go.
	 */
	@Test
	public void readContainersFoldsAndCanonicalisesExactlyAsTheBankDoes()
	{
		final BankReader.Carried carried = reader.readContainers(
			new Item[]{item(WHIP, 1), null, item(WHIP_NOTED, 4), item(-1, 0), item(WHIP, 2), item(BANK_FILLER, 1)},
			null, NOW);

		assertEquals(1, carried.inventory.size());
		assertEquals(WHIP, carried.inventory.get(0).id);
		assertEquals("1 + 4 noted + 2, on one canonical id", 7, carried.inventory.get(0).quantity);
		assertTrue("and the filler slot never became one", carried.worn.isEmpty());
	}

	/** Y2: a container the client cannot serve yet is an empty list, never an error - and never a lost read. */
	@Test
	public void aNullContainerReadsAsEmpty()
	{
		final BankReader.Carried neither = reader.readContainers(null, null, NOW);

		assertNotNull(neither.inventory);
		assertNotNull(neither.worn);
		assertTrue(neither.inventory.isEmpty());
		assertTrue(neither.worn.isEmpty());
		assertTrue(neither.isEmpty());
		assertEquals("an empty read is still a read", NOW, neither.readAtMillis);

		final BankReader.Carried wornOnly = reader.readContainers(null, new Item[]{item(WHIP, 1)}, NOW);
		assertTrue(wornOnly.inventory.isEmpty());
		assertEquals(1, wornOnly.worn.size());
	}

	/** Y2: the coins in your pocket are a worth, never a row - the same rule the bank's cash follows (B097). */
	@Test
	public void coinsInTheInventoryLeaveAsCarriedGpAndNeverAsARow()
	{
		define(COINS, "Coins", true, true);
		define(PLATINUM, "Platinum token", true, true);

		final BankReader.Carried carried = reader.readContainers(
			new Item[]{item(COINS, 791_078), item(PLATINUM, 2), item(SHARK, 1)}, new Item[]{item(WHIP, 1)}, NOW);

		assertEquals("only the shark is a row", 1, carried.inventory.size());
		assertEquals(SHARK, carried.inventory.get(0).id);
		assertEquals("791,078 coins and 2 platinum", 791_078L + 2_000L, carried.carriedGp);
	}

	/** Q5 and R1 over the worn gear: an untradeable piece is kept, marked, valued and taken apart like a banked one. */
	@Test
	public void aWornUntradeableStackCarriesItsAlchValueAndItsParts()
	{
		when(define(CRYSTAL_BODY, "Crystal body", false, false).getHaPrice()).thenReturn(900_000);
		define(ARMOUR_SEED, SEED_NAME, true, false);

		final BankReader.Carried carried = reader.readContainers(null, new Item[]{item(CRYSTAL_BODY, 1)}, NOW);

		assertEquals(1, carried.worn.size());
		final BankItem body = carried.worn.get(0);
		assertTrue(body.untradeable);
		assertEquals(900_000, body.haPrice);
		assertNotNull(body.parts);
		assertEquals(1, body.parts.size());
		assertEquals(new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME), body.parts.get(0));
	}

	/** Q5's other half: an untradeable with nothing to alch for is no more a row when worn than when banked. */
	@Test
	public void aWornUntradeableWithNoAlchValueIsSkipped()
	{
		final int quest = 777;
		when(define(quest, "Quest item", false, false).getHaPrice()).thenReturn(0);

		final BankReader.Carried carried = reader.readContainers(new Item[]{item(quest, 1)},
			new Item[]{item(quest, 1)}, NOW);

		assertTrue(carried.inventory.isEmpty());
		assertTrue(carried.worn.isEmpty());
	}

	/** The value class is a value: two reads of the same containers at the same moment are the same thing. */
	@Test
	public void carriedComparesByValue()
	{
		final BankReader.Carried one = reader.readContainers(new Item[]{item(SHARK, 3)}, new Item[]{item(WHIP, 1)}, NOW);
		final BankReader.Carried two = reader.readContainers(new Item[]{item(SHARK, 3)}, new Item[]{item(WHIP, 1)}, NOW);

		assertEquals(one, two);
		assertEquals(one.hashCode(), two.hashCode());
		assertEquals("Carried{inventory=1, worn=1, carriedGp=0, readAtMillis=" + NOW + "}", one.toString());
		assertTrue(BankReader.Carried.EMPTY.isEmpty());
		assertEquals(0L, BankReader.Carried.EMPTY.readAtMillis);
	}

	/** The bank's own read is untouched by Y: it still answers a snapshot with no carried half at all. */
	@Test
	public void readStillAnswersABankWithNoCarriedHalf()
	{
		final BankSnapshot snapshot = reader.read(new Item[]{item(WHIP, 1)}, ACCOUNT, PROFILE, NOW);

		assertTrue(snapshot.inventory.isEmpty());
		assertTrue(snapshot.worn.isEmpty());
		assertEquals(0L, snapshot.carriedGp);
		assertEquals(0L, snapshot.carriedAtMillis);
	}

	// ---------------------------------------------------------------- fixtures

	/**
	 * Registers a composition for an id. Built first and stubbed afterwards on purpose: a mock created inside
	 * a {@code thenReturn(...)} argument trips Mockito's unfinished-stubbing check.
	 */
	private ItemComposition define(final int id, final String name, final boolean geTradeable, final boolean stackable)
	{
		final ItemComposition composition = mock(ItemComposition.class);
		// getMembersName, not getName: on a free world getName carries " (Members)" (L8 b).
		when(composition.getMembersName()).thenReturn(name);
		when(composition.isGeTradeable()).thenReturn(geTradeable);
		when(composition.isStackable()).thenReturn(stackable);
		compositions.put(id, composition);
		return composition;
	}

	private static Item item(final int id, final int quantity)
	{
		return new Item(id, quantity);
	}

	private static BankItem rowFor(final BankSnapshot snapshot, final int id)
	{
		for (final BankItem row : snapshot.items)
		{
			if (row.id == id)
			{
				return row;
			}
		}
		throw new AssertionError("no row for item " + id + " in " + namesOf(snapshot));
	}

	private static List<String> namesOf(final BankSnapshot snapshot)
	{
		final List<String> out = new ArrayList<>();
		for (final BankItem row : snapshot.items)
		{
			out.add(row.name);
		}
		return out;
	}
}
