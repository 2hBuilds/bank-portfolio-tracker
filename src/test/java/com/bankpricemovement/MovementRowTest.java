package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * {@link MovementRow}'s addendum-Q additions (Q5, Q6): the {@link MovementRow.PriceSource#ALCH} source an
 * untradeable stack is priced by, and {@link MovementRow#holdingDeltaGp()} - the change over the WHOLE stack,
 * which is what a row prints and what the gp orderings sort on while "Show stack value on rows" is on.
 *
 * <p>And the addendum-R seam beside them (R2, R4): {@link MovementRow.PriceSource#PARTS} and
 * {@link MovementRow#parts()}, the row the panel paints as a GUIDE row and tells the reader what it was valued
 * from.
 */
public class MovementRowTest
{
	private static MovementRow row(final int quantity, final Long deltaGp)
	{
		return new MovementRow(4151, "Abyssal whip", quantity, false, 1_500_000L,
			deltaGp == null ? null : 1_500_000L - deltaGp, deltaGp, deltaGp == null ? null : 1.0d,
			1_500_000L * quantity, MovementRow.PriceSource.GUIDE);
	}

	@Test
	public void theStacksChangeIsTheUnitChangeTimesTheQuantity()
	{
		assertEquals(30_000L, row(3, 10_000L).holdingDeltaGp());
		assertEquals("a fall stays a fall", -30_000L, row(3, -10_000L).holdingDeltaGp());
		assertEquals(10_000L, row(1, 10_000L).holdingDeltaGp());
		assertEquals(0L, row(7, 0L).holdingDeltaGp());
	}

	/**
	 * A row with no baseline has no change to multiply. It answers a plain 0 rather than a null so a caller can
	 * print and compare it without unboxing; {@link MovementRow#hasMovement()} is what tells "no move" from
	 * "unmoved", exactly as it does for the unit change.
	 */
	@Test
	public void aRowWithNoMoveHasNoStackChangeEither()
	{
		final MovementRow dash = row(500, null);

		assertFalse(dash.hasMovement());
		assertEquals(0L, dash.holdingDeltaGp());
	}

	@Test
	public void anEmptyStackIsWorthNoChange()
	{
		assertEquals(0L, row(0, 10_000L).holdingDeltaGp());
		assertEquals(0L, row(-3, 10_000L).holdingDeltaGp());
	}

	/**
	 * The clamp picks an END by the sign rather than always {@link Long#MAX_VALUE}: a collapse clamped upwards
	 * would sort to the top of "Biggest gp gain", which is the one place this figure decides anything.
	 */
	@Test
	public void anImpossibleStackClampsToTheRightEnd()
	{
		assertEquals(Long.MAX_VALUE, row(Integer.MAX_VALUE, Long.MAX_VALUE / 2L).holdingDeltaGp());
		assertEquals(Long.MIN_VALUE, row(Integer.MAX_VALUE, Long.MIN_VALUE / 2L).holdingDeltaGp());
	}

	/** Q5: an alch row is a real row with a price, and it says where that price came from. */
	@Test
	public void anAlchRowCarriesItsOwnSource()
	{
		final MovementRow alch = MovementMath.alchRow(new BankItem(772, 2, "Dramen staff", false, true, 1500));

		assertSame(MovementRow.PriceSource.ALCH, alch.source());
		assertEquals(Long.valueOf(1500L), alch.unitPrice());
		assertEquals(3000L, alch.holdingValue());
		assertFalse("an alch value is a constant of the item, not a series", alch.hasMovement());
		assertEquals(0L, alch.holdingDeltaGp());
		assertTrue("and it is not one of the guide-priced rows",
			alch.source() != MovementRow.PriceSource.GUIDE);
	}

	// ---------------------------------------------------------------- R2/R4: the parts row

	private static final int CRYSTAL_BODY = 23_975;
	private static final int ARMOUR_SEED = 23_956;
	private static final String SEED_NAME = "Crystal armour seed";

	private static List<BankItem.Part> seeds(final long quantity)
	{
		return Collections.singletonList(new BankItem.Part(ARMOUR_SEED, quantity, SEED_NAME));
	}

	private static MovementRow crystalBodyRow()
	{
		final BankItem item = new BankItem(CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000, seeds(3L));
		// The service hands the two SUMS to MovementMath.row as an ordinary guide "now" and "then" (R2), then says
		// where the price came from: 3 x 5,564,922 against 3 x 5,500,000.
		return MovementMath.row(item, new PricePoint(16_694_766L, 16_694_766L),
			new PricePoint(16_500_000L, 16_500_000L), 0).asParts(item.parts);
	}

	/**
	 * R2/R4: a parts row is an ordinary movement row that says where its price came from - a real baseline, a real
	 * move, the same arithmetic as a GUIDE row - and carries the parts the tooltip's one extra line is built from.
	 */
	@Test
	public void aPartsRowKeepsTheGuideArithmeticAndNamesItsParts()
	{
		final MovementRow row = crystalBodyRow();

		assertSame(MovementRow.PriceSource.PARTS, row.source());
		assertEquals(Long.valueOf(16_694_766L), row.unitPrice());
		assertEquals(Long.valueOf(16_500_000L), row.thenPrice());
		assertEquals("the move a GUIDE row would have had", Long.valueOf(194_766L), row.deltaGp());
		assertTrue(row.hasMovement());
		assertEquals(seeds(3L), row.parts());
		assertTrue("never the alch tag", row.source() != MovementRow.PriceSource.ALCH);
	}

	/** Every other row says it has none, so a reader can tell a parts row by the list alone. */
	@Test
	public void everyOtherRowCarriesNoParts()
	{
		assertNull(row(3, 10_000L).parts());
		assertNull(MovementMath.alchRow(new BankItem(772, 2, "Dramen staff", false, true, 1500)).parts());
	}

	/**
	 * {@code asParts} is a re-sourcing, not a valuation: it must not be able to turn a row that names no price into
	 * one "valued as its parts", and it has nothing to say when there are no parts to name.
	 */
	@Test
	public void asPartsAnswersTheRowUnchangedWhenThereIsNothingToSay()
	{
		final MovementRow guide = row(3, 10_000L);
		assertSame(guide, guide.asParts(null));
		assertSame(guide, guide.asParts(Collections.emptyList()));

		final MovementRow unpriced = MovementMath.alchRow(new BankItem(1, 1, "Junk", false, true, 0));
		assertNull(unpriced.unitPrice());
		assertSame("no price is not a parts price", unpriced, unpriced.asParts(seeds(3L)));
	}

	/** The parts are part of a row's identity: the panel republishes on a changed row list, and this changes one. */
	@Test
	public void thePartsArePartOfTheRowsIdentity()
	{
		final MovementRow row = crystalBodyRow();

		assertEquals(row, crystalBodyRow());
		assertEquals(row.hashCode(), crystalBodyRow().hashCode());
		assertNotEquals("the same numbers, a different reason for them",
			row, new MovementRow(CRYSTAL_BODY, "Crystal body", 1, false, 16_694_766L, 16_500_000L, 194_766L,
				1.180400d, 16_694_766L, MovementRow.PriceSource.GUIDE));
	}

	/** The list is the row's own: a caller cannot reach in and change what a published row says it is worth. */
	@Test
	public void thePartsListIsACopyTheCallerCannotChange()
	{
		final List<BankItem.Part> mutable = new ArrayList<>(seeds(3L));
		final MovementRow row = crystalBodyRow().asParts(mutable);

		mutable.clear();

		assertEquals(1, row.parts().size());
		try
		{
			row.parts().add(new BankItem.Part(1, 1L, "x"));
			fail("a published row's parts must be unmodifiable");
		}
		catch (final UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	// ---------------------------------------------------------------- U3: the day each window used (seam S1)

	private static final LocalDate SEP_11 = LocalDate.of(2026, 9, 11);
	private static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);

	/** The days of a row the traded series touched: 1d off the traded bucket, 7d off the guide baseline it fell to. */
	private static Map<MovementWindow, LocalDate> mixedDays()
	{
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, SEP_11);
		days.put(MovementWindow.D7, SEP_5);
		return days;
	}

	/**
	 * U3: a live row carries the day each window really compared against - the traded bucket's own day where the
	 * window is live, the guide baseline's where it fell back - so a tooltip line's day and its figure always come
	 * from the same place.
	 */
	@Test
	public void aLiveRowCarriesTheDayEachWindowComparedAgainst()
	{
		final MovementRow live = LiveRows.asLive(row(1, 10_000L), null, mixedDays());

		assertEquals(SEP_11, live.windowDay(MovementWindow.D1));
		assertEquals("the window that fell back stamps the guide's day", SEP_5, live.windowDay(MovementWindow.D7));
		assertNull("a window nothing recorded stamps nothing", live.windowDay(MovementWindow.D180));
		assertNull(live.windowDay(null));
	}

	/** The day travels with the row through the parts re-sourcing, because the figures it belongs to do. */
	@Test
	public void theDaysSurviveAsParts()
	{
		final MovementRow live = LiveRows.asLive(crystalBodyRow(), null, mixedDays());

		assertEquals(SEP_11, live.asParts(seeds(3L)).windowDay(MovementWindow.D1));
	}

	/** A row built with the switch off records no day at all - the pre-addendum-U shape, byte for byte. */
	@Test
	public void aRowBuiltWithTheSwitchOffRecordsNoDay()
	{
		final MovementRow guide = row(3, 10_000L);

		for (final MovementWindow window : MovementWindow.values())
		{
			assertNull(guide.windowDay(window));
		}
		assertEquals("an empty map is stored as no map", LiveRows.asLive(guide, null, null),
			LiveRows.asLive(guide, null, Collections.<MovementWindow, LocalDate>emptyMap()));
	}

	/** Two rows that compared against different days are different rows, whatever their figures look like. */
	@Test
	public void theDaysArePartOfTheRowsIdentity()
	{
		final MovementRow guide = row(1, 10_000L);
		final MovementRow live = LiveRows.asLive(guide, null, mixedDays());

		assertEquals(live, LiveRows.asLive(guide, null, mixedDays()));
		assertEquals(live.hashCode(), LiveRows.asLive(guide, null, mixedDays()).hashCode());
		assertNotEquals("one day apart is one row apart", live,
			LiveRows.asLive(guide, null, Collections.singletonMap(MovementWindow.D1, SEP_5)));
		assertNotEquals(live, LiveRows.asLive(guide, null, null));
	}

	// ---------------------------------------------------------------- addendum Y: where the quantity is

	/** Y3: the three figures say where the stack is, and they add up to the quantity the row prints. */
	@Test
	public void theSplitSaysWhereTheQuantityIs()
	{
		final MovementRow all = row(5, 10_000L).withSplit(3, 1, 1, 0);

		assertEquals(3, all.bankQuantity());
		assertEquals(1, all.inventoryQuantity());
		assertEquals(1, all.wornQuantity());
		assertEquals("they add up to what the row prints", all.quantity(),
			all.bankQuantity() + all.inventoryQuantity() + all.wornQuantity());
		assertTrue(PanelState.split(all));
		assertFalse("something is carried, so the hover line is drawn", all.allInBank());
	}

	/** A stack the player only WEARS is a row with no bank quantity at all - "1 worn". */
	@Test
	public void aWornOnlyStackHasNoBankQuantity()
	{
		final MovementRow worn = row(1, 10_000L).withSplit(0, 0, 1, 0);

		assertEquals(0, worn.bankQuantity());
		assertEquals(0, worn.inventoryQuantity());
		assertEquals(1, worn.wornQuantity());
		assertTrue("it still knows where it is", PanelState.split(worn));
		assertFalse(worn.allInBank());
	}

	/** A stack held only in the bank knows it, and says so - which is what keeps the hover line off it. */
	@Test
	public void aBankOnlyStackIsAllInBank()
	{
		final MovementRow banked = row(3, 10_000L).withSplit(3, 0, 0, 0);

		assertEquals(3, banked.bankQuantity());
		assertTrue(PanelState.split(banked));
		assertTrue(banked.allInBank());
	}

	/** Y3: with the switch off a row carries three zeros - no split, and no hover line about one. */
	@Test
	public void aRowBuiltWithTheSwitchOffCarriesNoSplit()
	{
		final MovementRow guide = row(3, 10_000L);

		assertEquals(0, guide.bankQuantity());
		assertEquals(0, guide.inventoryQuantity());
		assertEquals(0, guide.wornQuantity());
		assertFalse(PanelState.split(guide));
		assertTrue(guide.allInBank());
		assertSame("three zeros answer the row itself", guide, guide.withSplit(0, 0, 0, 0));
		assertEquals("and a negative is no split either", guide, guide.withSplit(-2, 0, 0, 0));
	}

	/** The split says where the stack lives, never what it is worth: every figure is untouched. */
	@Test
	public void theSplitChangesNoFigure()
	{
		final MovementRow guide = row(5, 10_000L);
		final MovementRow split = guide.withSplit(3, 1, 1, 0);

		assertEquals(guide.unitPrice(), split.unitPrice());
		assertEquals(guide.thenPrice(), split.thenPrice());
		assertEquals(guide.deltaGp(), split.deltaGp());
		assertEquals(guide.deltaPct(), split.deltaPct());
		assertEquals(guide.holdingValue(), split.holdingValue());
		assertEquals(guide.holdingDeltaGp(), split.holdingDeltaGp());
		assertEquals(guide.source(), split.source());
	}

	/** The split travels with the row through the parts re-sourcing, exactly as the parts and the days do. */
	@Test
	public void theSplitSurvivesAsParts()
	{
		final MovementRow body = crystalBodyRow().withSplit(2, 0, 1, 0);

		assertEquals(2, body.asParts(seeds(3L)).bankQuantity());
		assertEquals(1, body.asParts(seeds(3L)).wornQuantity());
	}

	/** Two rows of the same five that live in different places are two different rows. */
	@Test
	public void theSplitIsPartOfTheRowsIdentity()
	{
		final MovementRow banked = row(5, 10_000L).withSplit(5, 0, 0, 0);

		assertEquals(banked, row(5, 10_000L).withSplit(5, 0, 0, 0));
		assertEquals(banked.hashCode(), row(5, 10_000L).withSplit(5, 0, 0, 0).hashCode());
		assertNotEquals("four banked and one worn is not five banked", banked, row(5, 10_000L).withSplit(4, 0, 1, 0));
		assertNotEquals("and neither is a row that does not know", banked, row(5, 10_000L));
	}

	/** A row with no split knows none; one merged with a split holds its four figures. */
	@Test
	public void aRowKnowsItsSplitOnlyWhenOneWasMerged()
	{
		assertFalse(PanelState.split(row(5, 10_000L)));
		final MovementRow carried = row(5, 10_000L).withSplit(3, 1, 1, 0);
		assertEquals(3, carried.bankQuantity());
		assertEquals(1, carried.inventoryQuantity());
		assertEquals(1, carried.wornQuantity());
		assertEquals("the exchange part is there only when there is one", 0, carried.exchangeQuantity());
		assertEquals(2, row(7, 10_000L).withSplit(3, 1, 1, 2).exchangeQuantity());
	}

	// ---------------------------------------------------------------- 1.0.9 part 3: the Grand Exchange offers

	/** The fourth figure says how many are in the player's offers, and the four add up to the quantity. */
	@Test
	public void theSplitSaysHowManyAreInTheOffersToo()
	{
		final MovementRow all = row(7, 10_000L).withSplit(3, 1, 1, 2);

		assertEquals(3, all.bankQuantity());
		assertEquals(1, all.inventoryQuantity());
		assertEquals(1, all.wornQuantity());
		assertEquals(2, all.exchangeQuantity());
		assertEquals("the four add up to what the row prints", all.quantity(),
			all.bankQuantity() + all.inventoryQuantity() + all.wornQuantity() + all.exchangeQuantity());
		assertTrue(PanelState.split(all));
		assertFalse("something is in an offer, so the hover line is drawn", all.allInBank());
	}

	/** A stack only in an offer is a row with no bank quantity at all - "2 in the Grand Exchange". */
	@Test
	public void anOfferOnlyStackHasNoBankQuantityAndStillKnowsWhereItIs()
	{
		final MovementRow offer = row(2, 10_000L).withSplit(0, 0, 0, 2);

		assertEquals(0, offer.bankQuantity());
		assertEquals(2, offer.exchangeQuantity());
		assertTrue("a split of nothing but offers is still a split", PanelState.split(offer));
		assertFalse(offer.allInBank());
		assertNotEquals("and it is not the row that knows nothing of the split", offer, row(2, 10_000L));
	}

	/** The row built with the three-int split and the one built with no split carry no offers. */
	@Test
	public void aRowWithoutOffersCarriesZeroThere()
	{
		assertEquals(0, row(5, 10_000L).exchangeQuantity());
		assertEquals(0, row(5, 10_000L).withSplit(3, 1, 1, 0).exchangeQuantity());
		assertTrue("a bank-only split with no offers is all in the bank", row(3, 10_000L).withSplit(3, 0, 0, 0)
			.allInBank());
		final MovementRow guide = row(3, 10_000L);
		assertSame(guide, guide.withSplit(0, 0, 0, 0));
		assertEquals("a negative offer figure is no offer", guide, guide.withSplit(0, 0, 0, -3));
	}

	/** The offers' figure travels with the row through the parts re-sourcing and is part of its identity. */
	@Test
	public void theOffersFigureSurvivesAsPartsAndIsPartOfTheIdentity()
	{
		final MovementRow body = crystalBodyRow().withSplit(1, 0, 0, 2);

		assertEquals(2, body.asParts(seeds(3L)).exchangeQuantity());

		final MovementRow banked = row(5, 10_000L).withSplit(3, 0, 0, 2);
		assertEquals(banked, row(5, 10_000L).withSplit(3, 0, 0, 2));
		assertEquals(banked.hashCode(), row(5, 10_000L).withSplit(3, 0, 0, 2).hashCode());
		assertNotEquals("three banked and two in offers is not three banked and two worn", banked,
			row(5, 10_000L).withSplit(3, 0, 2, 0));
		assertNotEquals(banked, row(5, 10_000L).withSplit(5, 0, 0, 0));
		final MovementRow viaConstructor = new MovementRow(4151, "Abyssal whip", 5, false, 1_500_000L, null, null, null,
			7_500_000L, MovementRow.PriceSource.GUIDE, null, null, 3, 0, 0, 2, null);
		assertEquals(2, viaConstructor.exchangeQuantity());
		assertEquals(0, new MovementRow(4151, "Abyssal whip", 5, false, 1_500_000L, null, null, null, 7_500_000L,
			MovementRow.PriceSource.GUIDE, null, null, 3, 0, 0, 0, null).exchangeQuantity());
	}
}
