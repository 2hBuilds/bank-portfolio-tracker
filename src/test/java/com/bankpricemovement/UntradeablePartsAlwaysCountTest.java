package com.bankpricemovement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Addendum AV ({@code docs/bank-price-movement-addendum-AV-2026-09-28.md}): an untradeable stack RuneLite maps onto
 * tradeable parts is ALWAYS listed and counted at those parts' prices, "Include alch-only untradeables" decides the
 * alch ones alone, and every stack of the snapshot is priced on the computation's one client-thread trip whatever
 * the three counting switches say.
 *
 * <p>The fixture is {@link PriceServiceTest}'s own - the 31-stack bank worth 6,290,824 gp, the Crystal body over
 * three Crystal armour seeds, the carried half of addendum Y - used by composition, so these tests read the very
 * figures that class pins and cannot drift from them. Its {@code @Before} is run by hand here.
 *
 * <p><b>1.1.0 part G changed what "left out" means for the rows.</b> The service now publishes an alch row for every
 * stack that ends on the alch rule whatever "Include alch-only untradeables" says - the Items list keeps it out by its own
 * tick, off by default - and the switch decides the bank value and the counts alone. So where these tests used to say "not a
 * row" they now say "not in the list the reader sees" ({@link PriceServiceTest#defaultList}) and add that the row is
 * published; the value, the stack totals and the stack count they pin are untouched.
 */
public class UntradeablePartsAlwaysCountTest
{
	/** The fixture bank's own value, stack count and priced count with every switch at its default. */
	private static final long BANK_VALUE = 6_290_824L;
	private static final int BANK_STACKS = 31;
	private static final int BANK_PRICED = 30;
	/** A Crystal body's alch value in the fixture. */
	private static final long BODY_ALCH = 900_000L;
	/** A part no table names and RuneLite has no price for. */
	private static final int UNPRICED_PART = 99_001;

	private final PriceServiceTest f = new PriceServiceTest();

	@Before
	public void setUp()
	{
		f.setUp();
	}

	/**
	 * AV2, the all-or-nothing rule kept: nothing teaches the seed, so the body's parts cannot all be priced and it
	 * ends on the alch rule. With the switch OFF that is neither a row nor a gp of the bank value nor a stack of
	 * {@code itemsTotal}; with it ON it is the alch row Q5 has always drawn, counted at 900,000 gp.
	 */
	@Test
	public void aPartsStackWhosePartHasNoPriceIsLeftOutWithTheSwitchOffAndAnAlchRowWithItOn()
	{
		f.warmUpWith(PriceServiceTest.bankWithCrystalBody(PriceServiceTest.T0));

		// Part G: with the switch off the stack is an alch row the service publishes and the list keeps out - not a gp of the
		// bank value, not a stack of the total.
		assertNotNull("off: published as an alch row", PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));
		assertNull("off: and no row in the list the reader sees",
			PriceServiceTest.rowFor(PriceServiceTest.defaultList(f.lastRows()), PriceServiceTest.CRYSTAL_BODY));
		final PortfolioSummary off = f.lastStatus().portfolio();
		assertEquals("off: not a gp of it in the bank value", BANK_VALUE, off.valueNow());
		assertEquals("off: nor a stack of the total", BANK_STACKS, off.itemsTotal());
		assertEquals(BANK_PRICED, off.itemsPriced());
		assertEquals("off: and not one of the m in \"n of m items\" either (B105)", BANK_STACKS,
			f.lastStatus().bankItems());

		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));

		final MovementRow body = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY);
		assertNotNull("on: the alch row", body);
		assertEquals(MovementRow.PriceSource.ALCH, body.source());
		assertEquals(Long.valueOf(BODY_ALCH), body.unitPrice());
		final PortfolioSummary on = f.lastStatus().portfolio();
		assertEquals(BANK_VALUE + BODY_ALCH, on.valueNow());
		assertEquals(BANK_STACKS + 1, on.itemsTotal());
		assertEquals("an alch value is not a market price", BANK_PRICED, on.itemsPriced());
		assertEquals("on: a row, and counted", BANK_STACKS + 1, f.lastStatus().bankItems());
	}

	/**
	 * AV1 on the stack count: with the switch off, {@code bankItems} counts the bank's tradeable stacks AND its
	 * parts stacks, and still leaves the alch-only ones (the Dramen staff, the Graceful cape) to the switch.
	 */
	@Test
	public void theStackCountHoldsThePartsStacksAndLeavesTheAlchOnlyOnesToTheSwitch()
	{
		f.nameTheSeed();
		final BankSnapshot bank = PriceServiceTest.bankWithCrystalBody(PriceServiceTest.T0);
		bank.items.addAll(PriceServiceTest.bankWithUntradeables(PriceServiceTest.T0).items.subList(BANK_STACKS,
			BANK_STACKS + 2));
		f.warmUpWith(bank);

		assertEquals("31 + the Crystal body; the two alch-only stacks wait", BANK_STACKS + 1, f.lastStatus().bankItems());
		assertNotNull(PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));
		assertNotNull("since part G an alch-only stack is a published row...",
			PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.DRAMEN));
		assertNull("...and still not one the list shows",
			PriceServiceTest.rowFor(PriceServiceTest.defaultList(f.lastRows()), PriceServiceTest.DRAMEN));
		assertEquals(BANK_VALUE + 3L * PriceServiceTest.SEED_NOW, f.lastStatus().portfolio().valueNow());

		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));

		assertEquals("and all 34 with the switch on", BANK_STACKS + 3, f.lastStatus().bankItems());
		assertNotNull(PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.DRAMEN));
		assertEquals("2 x 1,500 + 500 more", BANK_VALUE + 3L * PriceServiceTest.SEED_NOW + 3_500L,
			f.lastStatus().portfolio().valueNow());
	}

	/**
	 * AV1 for what the player is WEARING: a Crystal body worn and never banked is a parts row with the switch off,
	 * split as one worn, counted in the bank value and in the merged stack count - a worn untradeable follows the
	 * switch exactly as a banked one does, and a parts one no longer waits for it.
	 */
	@Test
	public void aWornPartsStackIsARowAndCountsWithTheSwitchOff()
	{
		f.nameTheSeed();
		final BankItem body = new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME)));
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0).withCarried(new BankReader.Carried(
			Collections.<BankItem>emptyList(), Collections.singletonList(body), 0L, PriceServiceTest.T0));
		f.warmUpWith(bank);

		final MovementRow row = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY);
		assertNotNull("the worn parts stack is a row", row);
		assertEquals(MovementRow.PriceSource.PARTS, row.source());
		assertEquals(0, row.bankQuantity());
		assertEquals(1, row.wornQuantity());
		assertEquals(BANK_VALUE + 3L * PriceServiceTest.SEED_NOW, f.lastStatus().portfolio().valueNow());
		assertEquals(BANK_STACKS + 1, f.lastStatus().bankItems());
	}

	/**
	 * AV2 for the carried half: a worn parts stack whose part has no price ends on the alch rule, and with the
	 * switch off it is out of the rows and the value like a banked one.
	 */
	@Test
	public void aWornPartsStackWhosePartHasNoPriceIsLeftOutWithTheSwitchOff()
	{
		final BankItem body = new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME)));
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0).withCarried(new BankReader.Carried(
			Collections.<BankItem>emptyList(), Collections.singletonList(body), 0L, PriceServiceTest.T0));
		f.warmUpWith(bank);

		assertNotNull("published as an alch row (part G)...",
			PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));
		assertNull("...which the list keeps out",
			PriceServiceTest.rowFor(PriceServiceTest.defaultList(f.lastRows()), PriceServiceTest.CRYSTAL_BODY));
		assertEquals(BANK_VALUE, f.lastStatus().portfolio().valueNow());
		assertEquals(BANK_STACKS, f.lastStatus().portfolio().itemsTotal());
	}

	/**
	 * The shared refactor (plan 7.7 items 1-2): every stack is priced whatever the counting switches say. With the
	 * inventory switch OFF the worn-only Karambwan vessel is still asked for its guide price - on the SAME one trip
	 * to the client thread per computation - and still is not a row; with the untradeables switch off the Crystal
	 * body's seed is asked for too. An untradeable stack itself is never asked (RuneLite has no price for it).
	 */
	@Test
	public void everyStackIsPricedOnTheOneTripWhateverTheSwitchesSay()
	{
		f.nameTheSeed();
		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		f.warmUpWith(PriceServiceTest.bankWithCrystalBody(PriceServiceTest.T0).withCarried(PriceServiceTest.carried(0L)));

		verify(f.itemManager, atLeastOnce()).getItemPriceWithSource(PriceServiceTest.VESSEL, false);
		verify(f.itemManager, atLeastOnce()).getItemPriceWithSource(PriceServiceTest.ARMOUR_SEED, false);
		verify(f.itemManager, never()).getItemPriceWithSource(PriceServiceTest.CRYSTAL_BODY, false);
		assertNull("priced, and still not a row with the inventory switch off",
			PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.VESSEL));
		assertNotNull("the banked parts stack is", PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));

		// One computation, one trip: a fresh bank under the same switches hops once and only once.
		final int before = f.clientThread.invocations;
		f.service.setBank(PriceServiceTest.bankWithCarried(PriceServiceTest.T0 + 1L, 0L));
		assertEquals("one client-thread trip per computation", before + 1, f.clientThread.invocations);
	}

	/**
	 * The counting switches choose what is ADDED UP, never what is priced: flipping the inventory switch off and
	 * back publishes the very rows and bank value it started from, and the carried stacks' prices were read while
	 * it was off, so turning it on needs no new kind of lookup.
	 */
	@Test
	public void aCountingSwitchFlippedOffAndOnPublishesWhatItStartedFrom()
	{
		f.nameTheSeed();
		f.warmUpWith(PriceServiceTest.bankWithCarried(PriceServiceTest.T0, 0L));
		final List<MovementRow> rows = f.lastRows();
		final PortfolioSummary summary = f.lastStatus().portfolio();
		assertNotNull("the worn vessel is a row with the default switches",
			PriceServiceTest.rowFor(rows, PriceServiceTest.VESSEL));

		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		assertNull(PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.VESSEL));
		assertTrue("the value falls by what was carried", f.lastStatus().portfolio().valueNow() < summary.valueNow());

		f.service.setOptions(ViewOptions.DEFAULT);
		assertEquals(rows, f.lastRows());
		assertEquals(summary, f.lastStatus().portfolio());
	}

	/**
	 * A bank whose only untradeable is a parts stack that cannot be priced and nothing else: with the switch off it
	 * adds nothing at all, so the rows and the value are the plain bank's to the field - the list the summary is
	 * summed over is the switched one less the stack the gate left out, and nothing of it leaks.
	 */
	@Test
	public void aStackTheGateLeavesOutLeavesTheBankExactlyAsItWas()
	{
		f.warmUpWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final List<MovementRow> plain = f.lastRows();
		final PortfolioSummary plainSummary = f.lastStatus().portfolio();

		f.service.setBank(PriceServiceTest.bankWithCrystalBody(PriceServiceTest.T0 + 1L));

		// Part G: the stack the gate left out is a published alch row all the same; the list the reader sees is the plain bank's.
		assertEquals(plain, PriceServiceTest.defaultList(f.lastRows()));
		assertEquals(plain.size() + 1, f.lastRows().size());
		assertEquals(plainSummary, f.lastStatus().portfolio());
		assertEquals("and the stack count: the stack the gate left out is not one of the m of \"n of m items\"",
			BANK_STACKS, f.lastStatus().bankItems());
		assertFalse("with the switch at its default", f.lastStatus().options().countUntradeables());
		verify(f.itemManager, never()).getItemPriceWithSource(PriceServiceTest.CRYSTAL_BODY, false);
		verify(f.itemManager, atLeastOnce()).getItemPriceWithSource(PriceServiceTest.ARMOUR_SEED, false);
	}

	// ---------------------------------------------------------------- review and mutation fixes

	/**
	 * A parts stack on the LIVE series with the untradeables switch off (T3 through AV): its one part passes all
	 * five checks, so the stack is live at three times the seed's traded mid - the row prints that, it stays a
	 * PARTS row, it is counted live, and the bank value holds exactly what the rows print.
	 */
	@Test
	public void aPartsStackWhosePartsAreAllLiveIsALivePartsRowWithTheSwitchOff()
	{
		final long buy = 5_600_000L;
		final long sell = 5_560_000L;
		final long mid = (buy + sell) / 2L;
		f.nameTheSeed();
		final long now = f.clock.get() / 1000L;
		f.warmUpLiveWith(PriceServiceTest.bankWithCrystalBody(PriceServiceTest.T0),
			Collections.singletonMap(PriceServiceTest.ARMOUR_SEED,
				new TradedPriceClient.Quote(buy, now - 3_600L, sell, now - 7_200L)));
		final Map<Integer, TradedPriceClient.Bucket> yesterday = new LinkedHashMap<>(PriceServiceTest.tradedSep7());
		// 500 units yesterday, all at today's mid on one side of the book: no gap and no jump (checks 4 and 5).
		yesterday.put(PriceServiceTest.ARMOUR_SEED, new TradedPriceClient.Bucket(mid, 500L, null, 0L));
		f.answerDay(PriceServiceTest.SEP_7, yesterday);

		assertFalse(f.lastStatus().options().countUntradeables());
		final MovementRow body = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY);
		assertNotNull(body);
		assertEquals("valued as its parts first", MovementRow.PriceSource.PARTS, body.source());
		assertTrue("and live", body.isLive());
		assertEquals("three seeds at the traded mid", Long.valueOf(3L * mid), body.unitPrice());
		final PortfolioSummary summary = f.lastStatus().portfolio();
		long printed = 0L;
		int live = 0;
		for (final MovementRow row : f.lastRows())
		{
			printed += row.holdingValue();
			live += row.isLive() ? 1 : 0;
		}
		assertEquals("the card counts every stack at what its row prints", printed, summary.valueStacks());
		assertEquals("the body is one of the live stacks", live, f.lastStatus().live().liveRows());
		assertEquals(live, summary.liveRows());
	}

	/**
	 * R2's all-or-nothing rule with TWO parts: the seed is priced, the other part is not, so the stack is not worth
	 * three seeds - it ends on the alch rule, which with the switch off leaves it out altogether and with it on
	 * draws the alch row, never a partial sum.
	 */
	@Test
	public void aStackWithOnePricedAndOneUnpricedPartIsNeverAPartialSum()
	{
		f.nameTheSeed();
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.items.add(new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Arrays.asList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME),
				new BankItem.Part(UNPRICED_PART, 1L, "Unpriced part"))));
		f.warmUpWith(bank);

		// Part G: an alch row at its alch value, never a partial sum - published, kept out of the list, left out of the value.
		final MovementRow published = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY);
		assertNotNull(published);
		assertEquals(MovementRow.PriceSource.ALCH, published.source());
		assertNull(PriceServiceTest.rowFor(PriceServiceTest.defaultList(f.lastRows()), PriceServiceTest.CRYSTAL_BODY));
		assertEquals(BANK_VALUE, f.lastStatus().portfolio().valueNow());
		assertEquals(BANK_STACKS, f.lastStatus().portfolio().itemsTotal());
		assertEquals(BANK_STACKS, f.lastStatus().bankItems());

		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));

		final MovementRow body = PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY);
		assertNotNull(body);
		assertEquals(MovementRow.PriceSource.ALCH, body.source());
		assertEquals("its alch value, not three seeds", Long.valueOf(BODY_ALCH), body.unitPrice());
		assertEquals(BANK_VALUE + BODY_ALCH, f.lastStatus().portfolio().valueNow());
	}

	/**
	 * A bank of nothing but one parts stack whose part has no price, and no cash: with the switch off every stack
	 * is left out, so the summary is the EMPTY one an empty bank publishes, and the stack count is 0.
	 */
	@Test
	public void aBankWhoseEveryStackIsLeftOutPublishesTheEmptySummary()
	{
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.items.clear();
		bank.items.add(new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME))));
		f.warmUpWith(bank);

		// Part G: the one stack is published as an alch row; the list shows nothing and the summary is the empty one.
		assertEquals(1, f.lastRows().size());
		assertTrue(PriceServiceTest.defaultList(f.lastRows()).isEmpty());
		assertEquals(PortfolioSummary.EMPTY, f.lastStatus().portfolio());
		assertEquals(0, f.lastStatus().bankItems());
	}

	/**
	 * The L3 day and its degraded flag are decided over the SWITCHED list alone: a bank of nothing but alch-only
	 * untradeables, with the switch off, has no stack to compare - exactly an empty bank - so it is not degraded.
	 */
	@Test
	public void aBankOfAlchOnlyStacksWithTheSwitchOffIsNotDegraded()
	{
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		final List<BankItem> alchOnly = new ArrayList<>(
			PriceServiceTest.bankWithUntradeables(PriceServiceTest.T0).items.subList(BANK_STACKS, BANK_STACKS + 2));
		bank.items.clear();
		bank.items.addAll(alchOnly);
		f.warmUpWith(bank);

		// Part G: the two stacks are published as alch rows and the list shows none of them; nothing about the day changed.
		assertEquals(2, f.lastRows().size());
		assertTrue(PriceServiceTest.defaultList(f.lastRows()).isEmpty());
		assertFalse("nothing to compare is not too little to compare", f.lastStatus().anchorDegraded());
		assertEquals(PortfolioSummary.EMPTY, f.lastStatus().portfolio());
		assertEquals(0, f.lastStatus().bankItems());
	}

	/**
	 * The agreement sample is the switched list's alone, though the carried stacks are priced too: nineteen
	 * comparable stacks in the bank are one short of {@link PriceService#AGREE_MIN_SAMPLES}, so with "Include
	 * inventory and worn gear" off the status is degraded; five more carried in the inventory clear it only while
	 * that switch counts them.
	 */
	@Test
	public void theAgreementSampleFollowsTheInventorySwitchAndNotTheWiderPricing()
	{
		final int inBank = PriceService.AGREE_MIN_SAMPLES - 1;
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.items.clear();
		for (int n = 1; n <= inBank; n++)
		{
			bank.items.add(new BankItem(PriceServiceTest.item(n), n, "Item " + n, true));
		}
		final List<BankItem> inventory = new ArrayList<>();
		for (int n = inBank + 1; n <= inBank + 5; n++)
		{
			inventory.add(new BankItem(PriceServiceTest.item(n), n, "Item " + n, true));
		}
		f.warmUpWith(bank.withCarried(new BankReader.Carried(inventory, Collections.<BankItem>emptyList(), 0L,
			PriceServiceTest.T0)));
		final PriceService.Status counted = f.lastStatus();
		assertFalse("24 samples with the inventory counted", counted.anchorDegraded());
		assertEquals(inBank + 5, counted.agreeSamples());

		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));

		assertTrue("19 with it off, though the carried five were priced on the same trip",
			f.lastStatus().anchorDegraded());
		assertEquals(inBank, f.lastStatus().agreeSamples());
		assertEquals(counted.anchorDay(), f.lastStatus().anchorDay());

		f.service.setOptions(ViewOptions.DEFAULT);

		assertFalse(f.lastStatus().anchorDegraded());
	}

	/**
	 * Every stack is priced whatever the switches say - the PARTS of a stack only the player wears included: with
	 * "Include inventory and worn gear" off and the Crystal body worn and never banked, its seed is still asked
	 * for, and the body is still not a row.
	 */
	@Test
	public void theWornOnlyPartsStacksPartsArePricedWithTheInventorySwitchOff()
	{
		f.nameTheSeed();
		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		final BankItem body = new BankItem(PriceServiceTest.CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(PriceServiceTest.ARMOUR_SEED, 3L, PriceServiceTest.SEED_NAME)));
		f.warmUpWith(PriceServiceTest.bank(PriceServiceTest.T0).withCarried(new BankReader.Carried(
			Collections.<BankItem>emptyList(), Collections.singletonList(body), 0L, PriceServiceTest.T0)));

		verify(f.itemManager, atLeastOnce()).getItemPriceWithSource(PriceServiceTest.ARMOUR_SEED, false);
		assertNull(PriceServiceTest.rowFor(f.lastRows(), PriceServiceTest.CRYSTAL_BODY));
		assertEquals(BANK_VALUE, f.lastStatus().portfolio().valueNow());
	}

	/** The switch's words are the user's, pinned as written (plan 7.7 item 12) - the menu item's and its hover's. */
	@Test
	public void theSwitchReadsInTheUsersWords()
	{
		assertEquals("Include alch-only untradeables", BankPriceMovementPanel.COUNT_UNTRADEABLES_TEXT);
		assertEquals("Counts untradeables with no tradeable parts, at alch value.",
			BankPriceMovementPanel.COUNT_UNTRADEABLES_TIP);
	}

	/**
	 * The render fixture is honest about the stack AV added to it: the Crystal body's own move (-1,605,234 gp on
	 * 1d) is in the card's move under every switch, both ends of it, exactly as the service sums a covered stack.
	 */
	@Test
	public void theRenderFixturesCardMovesWithTheCrystalBody()
	{
		final MovementRow body = LookRenderer.crystalBody();
		for (final ViewOptions options : Arrays.asList(LookRenderer.GUIDE_ONLY, LookRenderer.OPTIONS, LookRenderer.LIVE))
		{
			for (final MovementWindow window : MovementWindow.values())
			{
				final WindowMove before = LookRenderer.summary().move(window);
				final WindowMove after = LookRenderer.summary(options).move(window);
				assertEquals(options + " " + window, before.deltaGp() + body.deltaGp(), after.deltaGp());
				assertEquals(before.valueThen() + body.thenPrice(), after.valueThen());
				assertEquals(before.valueNowCovered() + body.unitPrice(), after.valueNowCovered());
				assertEquals(after.deltaGp() * 100.0 / after.valueThen(), after.deltaPct().doubleValue(), 1e-9);
			}
		}
	}
}
