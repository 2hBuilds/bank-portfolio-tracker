package com.bankpricemovement;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.0.9 part 3: what a Grand Exchange offer holds for the player, by state, and the copy that keeps it. Pure - the
 * offers are mocks of the client's interface, nothing here touches a client.
 */
public class ExchangeOffersTest
{
	private static final int ITEM = 4151;

	private static GrandExchangeOffer offer(final GrandExchangeOfferState state, final int itemId, final int total,
		final int sold, final long price, final long spent)
	{
		final GrandExchangeOffer offer = mock(GrandExchangeOffer.class);
		when(offer.getState()).thenReturn(state);
		when(offer.getItemId()).thenReturn(itemId);
		when(offer.getTotalQuantity()).thenReturn(total);
		when(offer.getQuantitySold()).thenReturn(sold);
		when(offer.getPrice()).thenReturn(price);
		when(offer.getSpent()).thenReturn(spent);
		return offer;
	}

	/** The one slot of the contract's table: total 10, sold 4, price 100, spent 350. */
	private static ExchangeOffers one(final GrandExchangeOfferState state)
	{
		return ExchangeOffers.of(new GrandExchangeOffer[] {offer(state, ITEM, 10, 4, 100L, 350L)});
	}

	private static ExchangeOffers one(final GrandExchangeOfferState state, final int total, final int sold,
		final long price, final long spent)
	{
		return ExchangeOffers.of(new GrandExchangeOffer[] {offer(state, ITEM, total, sold, price, spent)});
	}

	private static void assertItems(final ExchangeOffers offers, final int... quantities)
	{
		final Item[] items = offers.items();
		assertEquals(quantities.length, items.length);
		for (int i = 0; i < quantities.length; i++)
		{
			assertEquals(ITEM, items[i].getId());
			assertEquals(quantities[i], items[i].getQuantity());
		}
	}

	@Test
	public void aSellOfferHoldsWhatIsUnsoldAndWhatItHasEarned()
	{
		for (final GrandExchangeOfferState state : new GrandExchangeOfferState[] {GrandExchangeOfferState.SELLING,
			GrandExchangeOfferState.SOLD, GrandExchangeOfferState.CANCELLED_SELL})
		{
			final ExchangeOffers offers = one(state);
			assertItems(offers, 6);
			assertEquals(state.name(), 350L, offers.cashGp());
		}
	}

	@Test
	public void aBuyOfferHoldsWhatIsBoughtAndTheCoinsStillCommitted()
	{
		for (final GrandExchangeOfferState state : new GrandExchangeOfferState[] {GrandExchangeOfferState.BUYING,
			GrandExchangeOfferState.BOUGHT, GrandExchangeOfferState.CANCELLED_BUY})
		{
			final ExchangeOffers offers = one(state);
			assertItems(offers, 4);
			assertEquals(state.name(), 650L, offers.cashGp());
		}
	}

	@Test
	public void anEmptySlotHoldsNothing()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.EMPTY);
		assertItems(offers);
		assertEquals(0L, offers.cashGp());
		assertTrue(offers.isEmpty());
		assertEquals(0, offers.slotsInUse());
		assertSame("an all-empty read is the one EMPTY", ExchangeOffers.EMPTY, offers);
	}

	@Test
	public void aFullySoldSellOfferHoldsNoItemAndItsEarnings()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.SOLD, 10, 10, 100L, 1_000L);
		assertItems(offers);
		assertEquals(1_000L, offers.cashGp());
		assertFalse(offers.isEmpty());
	}

	@Test
	public void anUnfilledBuyOfferHoldsNoItemAndEveryCoinItAsked()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.BUYING, 10, 0, 100L, 0L);
		assertItems(offers);
		assertEquals(1_000L, offers.cashGp());
	}

	@Test
	public void aBuyOfferFilledAtTheAskedPriceHoldsAllItsItemsAndNoCoins()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.BOUGHT, 10, 10, 100L, 1_000L);
		assertItems(offers, 10);
		assertEquals(0L, offers.cashGp());
	}

	@Test
	public void aBuyOfferFilledBelowTheAskedPriceHoldsTheChangeAsCoins()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.BOUGHT, 10, 10, 100L, 900L);
		assertItems(offers, 10);
		assertEquals("the 100 gp the lower fill hands back", 100L, offers.cashGp());
	}

	@Test
	public void aFilledQuantityAboveTheTotalReadsAsAFullFill()
	{
		assertItems(one(GrandExchangeOfferState.BUYING, 10, 25, 100L, 1_000L), 10);
		assertItems(one(GrandExchangeOfferState.SELLING, 10, 25, 100L, 1_000L));
		assertEquals(1_000L, one(GrandExchangeOfferState.SELLING, 10, 25, 100L, 1_000L).cashGp());
	}

	@Test
	public void aNegativeFigureReadsAsZero()
	{
		assertItems(one(GrandExchangeOfferState.SELLING, 10, -3, 100L, 350L), 10);
		assertEquals(0L, one(GrandExchangeOfferState.SELLING, 10, 4, 100L, -50L).cashGp());
		assertItems(one(GrandExchangeOfferState.BUYING, 10, -4, 100L, 0L));
		assertEquals("a negative price asks for nothing", 0L, one(GrandExchangeOfferState.BUYING, 10, 0, -100L, 0L)
			.cashGp());
		assertItems(one(GrandExchangeOfferState.SELLING, -10, 0, 100L, 0L));
		assertEquals("more spent than asked cannot be a negative commitment", 0L,
			one(GrandExchangeOfferState.BOUGHT, 10, 10, 100L, 5_000L).cashGp());
	}

	@Test
	public void aSlotWithNoItemContributesNothing()
	{
		final ExchangeOffers offers = ExchangeOffers.of(new GrandExchangeOffer[] {
			offer(GrandExchangeOfferState.SELLING, 0, 10, 4, 100L, 350L),
			offer(GrandExchangeOfferState.BUYING, -1, 10, 4, 100L, 350L)});
		assertSame(ExchangeOffers.EMPTY, offers);
		assertEquals(0, offers.slotsInUse());
	}

	@Test
	public void twoSlotsOfOneItemAreTwoItems()
	{
		final ExchangeOffers offers = ExchangeOffers.of(new GrandExchangeOffer[] {
			offer(GrandExchangeOfferState.SELLING, ITEM, 10, 4, 100L, 350L),
			offer(GrandExchangeOfferState.BOUGHT, ITEM, 3, 3, 90L, 270L)});
		assertItems(offers, 6, 3);
		assertEquals(350L, offers.cashGp());
		assertEquals(2, offers.slotsInUse());
	}

	@Test
	public void eightSlotsSum()
	{
		final GrandExchangeOffer[] eight = new GrandExchangeOffer[8];
		for (int i = 0; i < 8; i++)
		{
			eight[i] = offer(GrandExchangeOfferState.SELLING, ITEM + i, 10, 4, 100L, 350L);
		}

		final ExchangeOffers offers = ExchangeOffers.of(eight);
		assertEquals(8, offers.items().length);
		assertEquals(8 * 350L, offers.cashGp());
		assertEquals(8, offers.slotsInUse());
		assertEquals(ITEM + 7, offers.items()[7].getId());
	}

	@Test
	public void theSumsClampInsteadOfWrapping()
	{
		final ExchangeOffers offers = ExchangeOffers.of(new GrandExchangeOffer[] {
			offer(GrandExchangeOfferState.SOLD, ITEM, 1, 1, 1L, Long.MAX_VALUE),
			offer(GrandExchangeOfferState.SOLD, ITEM + 1, 1, 1, 1L, 1_000L)});
		assertEquals(Long.MAX_VALUE, offers.cashGp());
		assertEquals("a price times a quantity that would wrap clamps too", Long.MAX_VALUE,
			one(GrandExchangeOfferState.BUYING, Integer.MAX_VALUE, 0, Long.MAX_VALUE / 2, 0L).cashGp());
	}

	@Test
	public void nothingAtAllReadsAsEmpty()
	{
		assertSame(ExchangeOffers.EMPTY, ExchangeOffers.of(null));
		assertSame(ExchangeOffers.EMPTY, ExchangeOffers.of(new GrandExchangeOffer[0]));
		assertSame(ExchangeOffers.EMPTY, ExchangeOffers.of(new GrandExchangeOffer[8]));
		assertSame("a null state reads as EMPTY",
			ExchangeOffers.EMPTY, ExchangeOffers.of(new GrandExchangeOffer[] {
				offer(null, ITEM, 10, 4, 100L, 350L)}));
		assertTrue(ExchangeOffers.EMPTY.isEmpty());
		assertEquals(0, ExchangeOffers.EMPTY.items().length);
		assertEquals(0L, ExchangeOffers.EMPTY.cashGp());
		assertEquals(0, ExchangeOffers.EMPTY.slotsInUse());
	}

	@Test
	public void aNullSlotBesideAUsedOneReadsAsEmpty()
	{
		final ExchangeOffers offers = ExchangeOffers.of(new GrandExchangeOffer[] {null,
			offer(GrandExchangeOfferState.SELLING, ITEM, 10, 4, 100L, 350L), null});
		assertItems(offers, 6);
		assertEquals(1, offers.slotsInUse());
	}

	@Test
	public void theCopyDoesNotChangeWhenTheClientsOfferDoes()
	{
		final GrandExchangeOffer live = offer(GrandExchangeOfferState.SELLING, ITEM, 10, 4, 100L, 350L);
		final ExchangeOffers copy = ExchangeOffers.of(new GrandExchangeOffer[] {live});
		final ExchangeOffers same = ExchangeOffers.of(new GrandExchangeOffer[] {live});
		when(live.getQuantitySold()).thenReturn(10);
		when(live.getSpent()).thenReturn(1_000L);
		when(live.getState()).thenReturn(GrandExchangeOfferState.SOLD);
		assertItems(copy, 6);
		assertEquals(350L, copy.cashGp());
		assertEquals(same, copy);
		assertNotEquals("a read taken after the change is a different value", copy,
			ExchangeOffers.of(new GrandExchangeOffer[] {live}));
	}

	@Test
	public void itemsAnswersAFreshArrayEachCall()
	{
		final ExchangeOffers offers = one(GrandExchangeOfferState.SELLING);
		final Item[] first = offers.items();
		assertNotSame(first, offers.items());
		first[0] = null;
		assertItems(offers, 6);
		assertArrayEquals(offers.items(), offers.items());
	}

	@Test
	public void equalityFollowsTheFigures()
	{
		assertEquals(one(GrandExchangeOfferState.SELLING), one(GrandExchangeOfferState.SELLING));
		assertEquals(one(GrandExchangeOfferState.SELLING).hashCode(), one(GrandExchangeOfferState.SELLING).hashCode());
		assertNotEquals(one(GrandExchangeOfferState.SELLING), one(GrandExchangeOfferState.BUYING));
		assertNotEquals(one(GrandExchangeOfferState.SELLING), one(GrandExchangeOfferState.SELLING, 10, 5, 100L, 350L));
		assertNotEquals(one(GrandExchangeOfferState.SELLING), one(GrandExchangeOfferState.SELLING, 10, 4, 100L, 351L));
		assertNotEquals(one(GrandExchangeOfferState.SELLING), ExchangeOffers.EMPTY);
		assertNotEquals(one(GrandExchangeOfferState.SELLING), null);
		assertEquals(ExchangeOffers.EMPTY, ExchangeOffers.EMPTY);
	}

	@Test
	public void isEmptyIsTrueOnlyWithNoItemAndNoCoins()
	{
		assertFalse("coins alone are something", one(GrandExchangeOfferState.SOLD, 10, 10, 100L, 1_000L).isEmpty());
		assertFalse("an item alone is something", one(GrandExchangeOfferState.BOUGHT, 10, 10, 100L, 1_000L).isEmpty());
		assertTrue("a sold-out offer that earned nothing holds nothing",
			one(GrandExchangeOfferState.SOLD, 10, 10, 100L, 0L).isEmpty());
	}

	@Test
	public void toStringPrintsCountsAndNeverAnItemId()
	{
		final String text = one(GrandExchangeOfferState.SELLING).toString();
		assertEquals("ExchangeOffers{slots=1, items=1, gp=350}", text);
		assertFalse(text.contains(String.valueOf(ITEM)));
	}
}
