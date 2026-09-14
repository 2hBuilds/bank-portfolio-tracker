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

	// ---------------------------------------------------------------- T4/T6: the live seam (S1)

	private static MovementRow.LiveFacts liveFacts()
	{
		return new MovementRow.LiveFacts(63_600_000L, 63_300_000L, 517L, null);
	}

	private static Map<MovementWindow, MovementRow.PriceSource> mixedSources()
	{
		final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
		sources.put(MovementWindow.D1, MovementRow.PriceSource.LIVE);
		sources.put(MovementWindow.D7, MovementRow.PriceSource.GUIDE);
		return sources;
	}

	/** T4: a live row is an ordinary row that says which series priced it and carries the numbers behind that. */
	@Test
	public void aLiveRowCarriesTheSourceTheFactsAndItsWindowSources()
	{
		final MovementRow live = row(1, 10_000L).asLive(null, mixedSources(), liveFacts());

		assertSame(MovementRow.PriceSource.LIVE, live.source());
		assertTrue(live.isLive());
		assertEquals(Long.valueOf(63_600_000L), live.liveFacts().buy());
		assertEquals(Long.valueOf(63_300_000L), live.liveFacts().sell());
		assertEquals(517L, live.liveFacts().volumeYesterday());
		assertNull("a live row names no refusal", live.liveFacts().reason());
		assertSame(MovementRow.PriceSource.LIVE, live.windowSource(MovementWindow.D1));
		assertSame("the window that fell back says so", MovementRow.PriceSource.GUIDE,
			live.windowSource(MovementWindow.D7));
	}

	/**
	 * T4's awkward case: a live row whose CURRENT window fell back is built from the guide pair - so its gp and
	 * percent are the guide's move - and still prints the live traded price, with the holding following the price
	 * it prints. The move must not be recomputed from the new unit: that would be live-now against guide-then,
	 * which is precisely the comparison addendum T's study refuted.
	 */
	@Test
	public void aLivePriceCanBePrintedOverAGuideMove()
	{
		final MovementRow guide = new MovementRow(4151, "Abyssal whip", 3, false, 1_500_000L, 1_490_000L,
			10_000L, 0.671140d, 4_500_000L, MovementRow.PriceSource.GUIDE);

		final MovementRow live = guide.asLive(1_600_000L, mixedSources(), liveFacts());

		assertEquals("the price it prints is the live mid", Long.valueOf(1_600_000L), live.unitPrice());
		assertEquals("the holding follows the price it prints", 4_800_000L, live.holdingValue());
		assertEquals("the move is untouched - still the guide's", Long.valueOf(10_000L), live.deltaGp());
		assertEquals(Long.valueOf(1_490_000L), live.thenPrice());
		assertEquals("and the stack change with it", 30_000L, live.holdingDeltaGp());
	}

	/** A parts stack keeps the source that tells a reader what it was valued from, and is still live (T4/R4). */
	@Test
	public void aLivePartsRowKeepsThePartsSourceAndStillAnswersLive()
	{
		final MovementRow live = crystalBodyRow().asLive(null, mixedSources(), liveFacts());

		assertSame("\"valued as its parts\" is what a reader has to be told first", MovementRow.PriceSource.PARTS,
			live.source());
		assertTrue("and isLive() is the one test that covers both", live.isLive());
		assertEquals(seeds(3L), live.parts());
	}

	/** T6: a row that stayed on the guide keeps everything it had and gains the check that refused it. */
	@Test
	public void aRefusedRowKeepsItsGuideSourceAndNamesTheFirstFailingCheck()
	{
		final MovementRow.LiveFacts refused = new MovementRow.LiveFacts(1_000L, 900L, 12L, "12 traded yesterday");

		final MovementRow guide = row(1, 10_000L).withLiveRefusal(refused);

		assertSame(MovementRow.PriceSource.GUIDE, guide.source());
		assertFalse(guide.isLive());
		assertEquals("12 traded yesterday", guide.liveFacts().reason());
		assertEquals("the numbers the refusal was made on are still there", Long.valueOf(1_000L),
			guide.liveFacts().buy());
		assertSame("every window of a guide row is the guide's", MovementRow.PriceSource.GUIDE,
			guide.windowSource(MovementWindow.D30));
	}

	/**
	 * The two builders are re-sourcings, not valuations - the rule {@code asParts} already obeys. A row with no
	 * price cannot become "the live traded price", facts that name a refusal cannot make a row live, and facts
	 * that name none cannot be a refusal.
	 */
	@Test
	public void theLiveBuildersAnswerTheRowUnchangedWhenThereIsNothingToSay()
	{
		final MovementRow guide = row(3, 10_000L);

		assertSame(guide, guide.asLive(null, mixedSources(), null));
		assertSame(guide, guide.asLive(null, mixedSources(),
			new MovementRow.LiveFacts(1L, 1L, 1L, "no live data")));
		assertSame(guide, guide.withLiveRefusal(null));
		assertSame(guide, guide.withLiveRefusal(liveFacts()));

		final MovementRow unpriced = MovementMath.alchRow(new BankItem(1, 1, "Junk", false, true, 0));
		assertNull(unpriced.unitPrice());
		assertSame(unpriced, unpriced.asLive(1_000L, mixedSources(), liveFacts()));
	}

	/**
	 * The pre-T shape, exactly: a row built by any caller that knows nothing of live prices carries no window
	 * sources and no facts, and every window answers the row's own source. This is what "the switch off is
	 * byte-identical to today" rests on at the row level.
	 */
	@Test
	public void aRowBuiltWithTheSwitchOffCarriesNoLiveStateAtAll()
	{
		final MovementRow guide = row(3, 10_000L);

		assertNull(guide.liveFacts());
		assertFalse(guide.isLive());
		for (final MovementWindow window : MovementWindow.values())
		{
			assertSame(MovementRow.PriceSource.GUIDE, guide.windowSource(window));
		}
		assertSame("a null window is not a reason to guess", MovementRow.PriceSource.GUIDE, guide.windowSource(null));

		final MovementRow alch = MovementMath.alchRow(new BankItem(772, 2, "Dramen staff", false, true, 1500));
		assertSame("an alch row's windows are the alch rule", MovementRow.PriceSource.ALCH,
			alch.windowSource(MovementWindow.D1));
	}

	/** A LIVE row asked about a window nothing recorded must not claim a comparison that was never made. */
	@Test
	public void aLiveRowNeverClaimsLiveForAnUnrecordedWindow()
	{
		final Map<MovementWindow, MovementRow.PriceSource> only1d = new EnumMap<>(MovementWindow.class);
		only1d.put(MovementWindow.D1, MovementRow.PriceSource.LIVE);

		final MovementRow live = row(1, 10_000L).asLive(null, only1d, liveFacts());

		assertSame(MovementRow.PriceSource.LIVE, live.windowSource(MovementWindow.D1));
		assertSame(MovementRow.PriceSource.GUIDE, live.windowSource(MovementWindow.D180));
		assertSame(MovementRow.PriceSource.GUIDE, live.windowSource(null));
	}

	/** The live state is part of a row's identity: two rows with the same figures and different series differ. */
	@Test
	public void theLiveStateIsPartOfTheRowsIdentity()
	{
		final MovementRow guide = row(1, 10_000L);
		final MovementRow live = guide.asLive(null, mixedSources(), liveFacts());

		assertNotEquals(guide, live);
		assertEquals(live, guide.asLive(null, mixedSources(), liveFacts()));
		assertEquals(live.hashCode(), guide.asLive(null, mixedSources(), liveFacts()).hashCode());
		assertNotEquals("a different window split is a different row",
			live, guide.asLive(null, Collections.singletonMap(MovementWindow.D1, MovementRow.PriceSource.LIVE),
				liveFacts()));
	}

	/** An empty window map is stored as the pre-T null, so an empty map and no map are one row. */
	@Test
	public void anEmptyWindowMapIsTheSameAsNone()
	{
		final MovementRow a = row(1, 10_000L).asLive(null, Collections.emptyMap(), liveFacts());
		final MovementRow b = row(1, 10_000L).asLive(null, null, liveFacts());

		assertEquals(a, b);
		assertSame(MovementRow.PriceSource.GUIDE, a.windowSource(MovementWindow.D1));
	}

	/** A negative volume cannot become a negative count on a tooltip. */
	@Test
	public void liveFactsRefuseANegativeVolume()
	{
		assertEquals(0L, new MovementRow.LiveFacts(1L, 1L, -5L, null).volumeYesterday());
	}

	// ---------------------------------------------------------------- U3: the day each window used (seam S1)

	private static final LocalDate SEP_11 = LocalDate.of(2026, 9, 11);
	private static final LocalDate SEP_5 = LocalDate.of(2026, 9, 5);

	/** The days beside {@link #mixedSources()}: 1d off the traded bucket, 7d off the guide baseline it fell to. */
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
		final MovementRow live = row(1, 10_000L).asLive(null, mixedSources(), mixedDays(), liveFacts());

		assertEquals(SEP_11, live.windowDay(MovementWindow.D1));
		assertEquals("the window that fell back stamps the guide's day", SEP_5, live.windowDay(MovementWindow.D7));
		assertNull("a window nothing recorded stamps nothing", live.windowDay(MovementWindow.D180));
		assertNull(live.windowDay(null));
	}

	/** The day travels with the row through both re-sourcings, because the figures it belongs to do. */
	@Test
	public void theDaysSurviveAsPartsAndARefusal()
	{
		final MovementRow live = crystalBodyRow().asLive(null, mixedSources(), mixedDays(), liveFacts());

		assertEquals(SEP_11, live.asParts(seeds(3L)).windowDay(MovementWindow.D1));

		final MovementRow.LiveFacts refused = new MovementRow.LiveFacts(1_000L, 900L, 12L, "12 traded yesterday");
		assertEquals(SEP_11, live.withLiveRefusal(refused).windowDay(MovementWindow.D1));
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
		assertEquals("an empty map is stored as no map", guide.asLive(null, mixedSources(),
			Collections.<MovementWindow, LocalDate>emptyMap(), liveFacts()),
			guide.asLive(null, mixedSources(), liveFacts()));
	}

	/** Two rows that compared against different days are different rows, whatever their figures look like. */
	@Test
	public void theDaysArePartOfTheRowsIdentity()
	{
		final MovementRow guide = row(1, 10_000L);
		final MovementRow live = guide.asLive(null, mixedSources(), mixedDays(), liveFacts());

		assertEquals(live, guide.asLive(null, mixedSources(), mixedDays(), liveFacts()));
		assertEquals(live.hashCode(), guide.asLive(null, mixedSources(), mixedDays(), liveFacts()).hashCode());
		assertNotEquals("one day apart is one row apart", live, guide.asLive(null, mixedSources(),
			Collections.singletonMap(MovementWindow.D1, SEP_5), liveFacts()));
		assertNotEquals(live, guide.asLive(null, mixedSources(), liveFacts()));
	}

	// ---------------------------------------------------------------- addendum Y: where the quantity is

	/** Y3: the three figures say where the stack is, and they add up to the quantity the row prints. */
	@Test
	public void theSplitSaysWhereTheQuantityIs()
	{
		final MovementRow all = row(5, 10_000L).withSplit(3, 1, 1);

		assertEquals(3, all.bankQuantity());
		assertEquals(1, all.inventoryQuantity());
		assertEquals(1, all.wornQuantity());
		assertEquals("they add up to what the row prints", all.quantity(),
			all.bankQuantity() + all.inventoryQuantity() + all.wornQuantity());
		assertTrue(all.split());
		assertFalse("something is carried, so the hover line is drawn", all.allInBank());
	}

	/** A stack the player only WEARS is a row with no bank quantity at all - "1 worn". */
	@Test
	public void aWornOnlyStackHasNoBankQuantity()
	{
		final MovementRow worn = row(1, 10_000L).withSplit(0, 0, 1);

		assertEquals(0, worn.bankQuantity());
		assertEquals(0, worn.inventoryQuantity());
		assertEquals(1, worn.wornQuantity());
		assertTrue("it still knows where it is", worn.split());
		assertFalse(worn.allInBank());
	}

	/** A stack held only in the bank knows it, and says so - which is what keeps the hover line off it. */
	@Test
	public void aBankOnlyStackIsAllInBank()
	{
		final MovementRow banked = row(3, 10_000L).withSplit(3, 0, 0);

		assertEquals(3, banked.bankQuantity());
		assertTrue(banked.split());
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
		assertFalse(guide.split());
		assertTrue(guide.allInBank());
		assertSame("three zeros answer the row itself", guide, guide.withSplit(0, 0, 0));
		assertEquals("and a negative is no split either", guide, guide.withSplit(-2, 0, 0));
	}

	/** The split says where the stack lives, never what it is worth: every figure is untouched. */
	@Test
	public void theSplitChangesNoFigure()
	{
		final MovementRow guide = row(5, 10_000L);
		final MovementRow split = guide.withSplit(3, 1, 1);

		assertEquals(guide.unitPrice(), split.unitPrice());
		assertEquals(guide.thenPrice(), split.thenPrice());
		assertEquals(guide.deltaGp(), split.deltaGp());
		assertEquals(guide.deltaPct(), split.deltaPct());
		assertEquals(guide.holdingValue(), split.holdingValue());
		assertEquals(guide.holdingDeltaGp(), split.holdingDeltaGp());
		assertEquals(guide.source(), split.source());
	}

	/** The split travels with the row through every re-sourcing, exactly as the parts and the days do. */
	@Test
	public void theSplitSurvivesAsPartsAsLiveAndARefusal()
	{
		final MovementRow body = crystalBodyRow().withSplit(2, 0, 1);

		assertEquals(2, body.asParts(seeds(3L)).bankQuantity());
		assertEquals(1, body.asParts(seeds(3L)).wornQuantity());

		final MovementRow live = body.asLive(2_000_000L, mixedSources(), mixedDays(), liveFacts());
		assertEquals(2, live.bankQuantity());
		assertEquals(1, live.wornQuantity());

		final MovementRow.LiveFacts refused = new MovementRow.LiveFacts(1_000L, 900L, 12L, "12 traded yesterday");
		assertEquals(2, body.withLiveRefusal(refused).bankQuantity());
	}

	/** Two rows of the same five that live in different places are two different rows. */
	@Test
	public void theSplitIsPartOfTheRowsIdentity()
	{
		final MovementRow banked = row(5, 10_000L).withSplit(5, 0, 0);

		assertEquals(banked, row(5, 10_000L).withSplit(5, 0, 0));
		assertEquals(banked.hashCode(), row(5, 10_000L).withSplit(5, 0, 0).hashCode());
		assertNotEquals("four banked and one worn is not five banked", banked, row(5, 10_000L).withSplit(4, 0, 1));
		assertNotEquals("and neither is a row that does not know", banked, row(5, 10_000L));
	}

	/** A row with no split prints exactly what it printed before addendum Y. */
	@Test
	public void toStringNamesTheSplitOnlyWhenThereIsOne()
	{
		assertFalse(row(5, 10_000L).toString().contains("bank="));
		assertTrue(row(5, 10_000L).withSplit(3, 1, 1).toString()
			.contains(", bank=3, inventory=1, worn=1"));
	}
}
