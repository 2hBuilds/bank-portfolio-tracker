package com.bankpricemovement;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release 1.2.4 ({@code docs/handoff/contract-1.2.4-bonds-2026-10-27.md}): "Include bonds". Every rule of the contract has
 * its test here - the switch in every place, the untradeable bond at 90% of a tradeable one on guide and on live figures and
 * on every window, the pouch read by item and position, the pouch saved with the bank and overwritten by the next read, and
 * the history's bonds cells.
 *
 * <p>The fixture is {@link PriceServiceTest}'s, used by composition: the 31-stack bank worth 6,290,824 gp, to which the
 * tests add bonds. The tradeable bond's guide price is {@link PriceServiceTest#BOND_NOW} now and
 * {@link PriceServiceTest#BOND_THEN} on every earlier day.
 */
public class BondsTest
{
	/** The fixture bank's own value with no bond in it. */
	private static final long BANK_VALUE = 6_290_824L;
	/** One tradeable bond, and one untradeable one at nine tenths of it, in gp. */
	private static final long T = PriceServiceTest.BOND_NOW;
	private static final long U = T * 9L / 10L;
	private static final long T_THEN = PriceServiceTest.BOND_THEN;
	private static final long U_THEN = T_THEN * 9L / 10L;

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final PriceServiceTest f = new PriceServiceTest();

	@Before
	public void setUp()
	{
		f.setUp();
		f.nameTheBond();
	}

	private static BankItem tradeable(final int quantity)
	{
		return new BankItem(PriceServiceTest.BOND, quantity, PriceServiceTest.BOND_NAME, false);
	}

	private static BankItem untradeable(final int quantity)
	{
		return new BankItem(PriceServiceTest.BOND_UNTRADEABLE, quantity, "Old school bond (untradeable)", false, true, 0);
	}

	private static BankSnapshot bankWith(final BankItem... bonds)
	{
		final BankSnapshot bank = PriceServiceTest.bank(PriceServiceTest.T0);
		bank.items.addAll(Arrays.asList(bonds));
		return bank;
	}

	/** Bonds in all four places: the bank (2 + 1), the inventory (1 + 1), the offers (3 tradeable) and the pouch (4 + 2). */
	private static BankSnapshot everywhere()
	{
		final BankSnapshot bank = bankWith(tradeable(2), untradeable(1)).withCarried(new BankReader.Carried(
			Arrays.asList(tradeable(1), untradeable(1)), Collections.<BankItem>emptyList(),
			Collections.singletonList(tradeable(3)), 0L, 0L, PriceServiceTest.T0));
		return bank.withPouch(4, 2, PriceServiceTest.T0);
	}

	private MovementRow row(final int id)
	{
		return PriceServiceTest.rowFor(f.lastRows(), id);
	}

	// ---------------------------------------------------------------- the switch, in every place

	@Test
	public void aTradeableBondIsARowAtTheGuidePriceAndCountsInTheBankValue()
	{
		f.warmUpWith(bankWith(tradeable(2)));

		final MovementRow bond = row(PriceServiceTest.BOND);
		assertNotNull(bond);
		assertEquals(2, bond.quantity());
		assertEquals(Long.valueOf(T), bond.unitPrice());
		assertEquals(Long.valueOf(T_THEN), bond.thenPrice());
		assertEquals(MovementRow.PriceSource.GUIDE, bond.source());
		assertEquals(BANK_VALUE + 2L * T, f.lastStatus().portfolio().valueNow());
	}

	@Test
	public void theSwitchOffTakesEveryBondOutOfTheRowsTheValueAndTheCounts()
	{
		f.warmUpWith(bankWith(tradeable(2), untradeable(1)));
		assertEquals(BANK_VALUE + 2L * T + U, f.lastStatus().portfolio().valueNow());
		final int stacksOn = f.lastStatus().bankItems();

		f.service.setOptions(ViewOptions.DEFAULT.withCountBonds(false));

		assertNull(row(PriceServiceTest.BOND));
		assertNull(row(PriceServiceTest.BOND_UNTRADEABLE));
		assertEquals(BANK_VALUE, f.lastStatus().portfolio().valueNow());
		assertEquals("neither stack is counted", stacksOn - 2, f.lastStatus().bankItems());

		f.service.setOptions(ViewOptions.DEFAULT);

		assertNotNull("and back on, they are back", row(PriceServiceTest.BOND));
		assertEquals(BANK_VALUE + 2L * T + U, f.lastStatus().portfolio().valueNow());
	}

	/**
	 * The switch sits ON TOP of the place switches (contract rule 1): a bond in the inventory needs the inventory switch,
	 * one in an offer needs the Grand Exchange switch, the bank and the pouch need only this one.
	 */
	@Test
	public void theBondsSwitchIsOnTopOfThePlaceSwitches()
	{
		f.warmUpWith(everywhere());
		final long all = BANK_VALUE + 10L * T + 4L * U;
		assertEquals("all four places", all, f.lastStatus().portfolio().valueNow());
		final MovementRow bond = row(PriceServiceTest.BOND);
		assertEquals(2, bond.bankQuantity());
		assertEquals(1, bond.inventoryQuantity());
		assertEquals(3, bond.exchangeQuantity());
		assertEquals(4, bond.pouchQuantity());
		assertEquals(10, bond.quantity());

		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false));
		assertEquals("inventory off: its one of each goes", all - T - U, f.lastStatus().portfolio().valueNow());
		assertEquals(0, row(PriceServiceTest.BOND).inventoryQuantity());

		f.service.setOptions(ViewOptions.DEFAULT.withCountGrandExchange(false));
		assertEquals("offers off: the three in offers go", all - 3L * T, f.lastStatus().portfolio().valueNow());

		f.service.setOptions(ViewOptions.DEFAULT.withCountInventory(false).withCountGrandExchange(false));
		assertEquals("the bank's and the pouch's stay: they need only the bonds switch",
			BANK_VALUE + 6L * T + 3L * U, f.lastStatus().portfolio().valueNow());

		f.service.setOptions(ViewOptions.DEFAULT.withCountBonds(false));
		assertEquals("bonds off: none anywhere, though the other places are on", BANK_VALUE,
			f.lastStatus().portfolio().valueNow());
		assertNull(row(PriceServiceTest.BOND));
	}

	// ---------------------------------------------------------------- the untradeable bond's 90%

	/**
	 * Contract rule 2: "now" and every window's "then" are nine tenths of the tradeable bond's - with NO tradeable bond held,
	 * the switch for alch-only untradeables off, and a row the Items list shows.
	 */
	@Test
	public void anUntradeableBondIsNineTenthsOfATradeableOneEvenWithNoneHeld()
	{
		f.warmUpWith(bankWith(untradeable(3)));

		assertFalse("the alch-only switch is off, and does not matter", f.lastStatus().options().countUntradeables());
		assertNull("no tradeable bond is held", row(PriceServiceTest.BOND));
		final MovementRow bond = row(PriceServiceTest.BOND_UNTRADEABLE);
		assertNotNull(bond);
		assertEquals(MovementRow.PriceSource.PARTS, bond.source());
		assertEquals("9 / 10 of 14,000,000", Long.valueOf(12_600_000L), bond.unitPrice());
		assertEquals(Long.valueOf(U), bond.unitPrice());
		assertEquals("9 / 10 of 13,500,000", Long.valueOf(U_THEN), bond.thenPrice());
		assertEquals(Long.valueOf(U - U_THEN), bond.deltaGp());
		assertNotNull("it is in the list the reader sees",
			PriceServiceTest.rowFor(PriceServiceTest.defaultList(f.lastRows()), PriceServiceTest.BOND_UNTRADEABLE));
		assertEquals(BANK_VALUE + 3L * U, f.lastStatus().portfolio().valueNow());
		assertEquals("the open block names the rule", MovementRowPanel.BOND_NOTE, MovementRowPanel.partsLine(bond));
		assertEquals("Untradeable - valued at 90% of a tradeable bond", MovementRowPanel.BOND_NOTE);

		f.service.setOptions(ViewOptions.DEFAULT.withCountUntradeables(true));
		assertEquals("\"Include alch-only untradeables\" does not touch it", BANK_VALUE + 3L * U,
			f.lastStatus().portfolio().valueNow());
	}

	/** Contract rule 2: the untradeable bond's move on every window is the tradeable bond's. */
	@Test
	public void theUntradeableBondsMoveOnEveryWindowIsTheTradeableBonds()
	{
		f.warmUpWith(bankWith(tradeable(1), untradeable(1)));
		for (final MovementWindow window : new MovementWindow[]{MovementWindow.D1, MovementWindow.D7})
		{
			f.service.setFilter(RowFilter.DEFAULT.withWindow(window));
			final MovementRow t = row(PriceServiceTest.BOND);
			final MovementRow u = row(PriceServiceTest.BOND_UNTRADEABLE);
			assertNotNull(window.toString(), t.deltaPct());
			assertEquals(window + ": the same percentage", t.deltaPct(), u.deltaPct(), 0.0001d);
			assertEquals(window + ": the gp move is nine tenths of it", t.deltaGp() * 9L / 10L, u.deltaGp(), 1L);
			assertEquals(window + ": then = nine tenths", Long.valueOf(U_THEN), u.thenPrice());
		}
	}

	/**
	 * Contract rule 2 on LIVE figures: the tradeable bond is graded (yesterday's buy side moved by today's buy, as the
	 * Crystal armour seed's test pins it) and the untradeable bond, with none held, is nine tenths of THAT.
	 */
	@Test
	public void theUntradeableBondFollowsTheTradeableBondsLiveFigure()
	{
		final long buy = 14_100_000L;
		final long sell = 13_900_000L;
		final long mid = (buy + sell) / 2L;
		final long now = f.clock.get() / 1000L;
		f.warmUpLiveWith(bankWith(untradeable(2)), Collections.singletonMap(PriceServiceTest.BOND,
			new TradedPriceClient.Quote(buy, now - 3_600L, sell, now - 7_200L)));
		final Map<Integer, TradedPriceClient.Bucket> yesterday = new LinkedHashMap<>(PriceServiceTest.tradedSep7());
		yesterday.put(PriceServiceTest.BOND, new TradedPriceClient.Bucket(mid, 500L, null, 0L));
		f.answerDay(PriceServiceTest.SEP_7, yesterday);

		final MovementRow bond = row(PriceServiceTest.BOND_UNTRADEABLE);
		assertNotNull(bond);
		assertEquals(MovementRow.PriceSource.PARTS, bond.source());
		assertEquals("nine tenths of the live figure, not of the guide's 14,000,000", Long.valueOf(buy * 9L / 10L),
			bond.unitPrice());
		assertEquals("and its then is nine tenths of the live mark", Long.valueOf(mid * 9L / 10L), bond.thenPrice());
		assertEquals(BANK_VALUE + 2L * (buy * 9L / 10L), f.lastStatus().portfolio().valueNow() - liveWhipAndHat());
	}

	/** What the fixture's live rows add over the guide-only bank value, so the bond's share can be isolated. */
	private long liveWhipAndHat()
	{
		long printed = 0L;
		for (final MovementRow r : f.lastRows())
		{
			if (r.id() != PriceServiceTest.BOND_UNTRADEABLE)
			{
				printed += r.holdingValue();
			}
		}
		return printed - BANK_VALUE;
	}

	// ---------------------------------------------------------------- the pouch

	@Test
	public void thePouchJoinsTheBondRowsAndTheSplitLineNamesIt()
	{
		f.warmUpWith(bankWith(tradeable(2)));
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 1, 2, PriceServiceTest.T0 + 1_000L);

		final MovementRow bond = row(PriceServiceTest.BOND);
		assertEquals(3, bond.quantity());
		assertEquals(2, bond.bankQuantity());
		assertEquals(1, bond.pouchQuantity());
		assertEquals("2 in bank, 1 in bond pouch", MovementRowPanel.splitLine(bond, false));
		final MovementRow other = row(PriceServiceTest.BOND_UNTRADEABLE);
		assertEquals("a pouch-only row", 2, other.quantity());
		assertEquals(2, other.pouchQuantity());
		assertEquals("2 in bond pouch", MovementRowPanel.splitLine(other, false));
		assertEquals(BANK_VALUE + 3L * T + 2L * U, f.lastStatus().portfolio().valueNow());

		f.service.setOptions(ViewOptions.DEFAULT.withCountBonds(false));
		assertEquals("the switch takes the pouch out too", BANK_VALUE, f.lastStatus().portfolio().valueNow());
	}

	/** A pouch read with no bank stored for the account yet is kept and counted. */
	@Test
	public void aPouchReadBeforeAnyBankIsKeptAndCounted()
	{
		f.warmUpWithPouchOnly(2, 1);

		assertEquals(2, row(PriceServiceTest.BOND).quantity());
		assertEquals(2, row(PriceServiceTest.BOND).pouchQuantity());
		assertEquals(1, row(PriceServiceTest.BOND_UNTRADEABLE).quantity());
		assertEquals(2L * T + U, f.lastStatus().portfolio().valueNow());
		assertNotNull("and it is a snapshot the service holds for the account", f.service.bank());
		final ArgumentCaptor<BankSnapshot> saved = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(saved.capture());
		assertEquals(2, saved.getValue().pouchTradeable);
		assertEquals(1, saved.getValue().pouchUntradeable);
		assertEquals(PriceServiceTest.T0, saved.getValue().pouchAtMillis);
		assertEquals(PriceServiceTest.ACCOUNT, saved.getValue().accountHash);
	}

	/** The pouch is saved with the bank and survives a bank read that knows nothing of it. */
	@Test
	public void aBankReadKeepsThePouchAndTheNextPouchReadOverwritesIt()
	{
		f.warmUpWith(bankWith(tradeable(1)));
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 5, 0, PriceServiceTest.T0 + 1_000L);
		assertEquals(6, row(PriceServiceTest.BOND).quantity());

		f.service.setBank(bankWith(tradeable(1), untradeable(1)));
		assertEquals("a fresh bank read (no pouch in it) keeps the pouch", 6, row(PriceServiceTest.BOND).quantity());
		assertEquals(5, row(PriceServiceTest.BOND).pouchQuantity());
		final ArgumentCaptor<BankSnapshot> saved = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(saved.capture());
		final BankSnapshot last = saved.getValue();
		assertEquals("the saved bank carries the pouch", 5, last.pouchTradeable);
		assertEquals(PriceServiceTest.T0 + 1_000L, last.pouchAtMillis);

		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 2, 0, PriceServiceTest.T0 + 2_000L);
		assertEquals("overwritten, not added to", 3, row(PriceServiceTest.BOND).quantity());
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 0, 0, PriceServiceTest.T0 + 3_000L);
		assertEquals("an emptied pouch is read as empty", 1, row(PriceServiceTest.BOND).quantity());
		assertEquals(0, row(PriceServiceTest.BOND).pouchQuantity());
	}

	/** A pouch saved by an earlier run is on screen after a restart: the login loads it with the bank. */
	@Test
	public void aSavedPouchSurvivesARestart()
	{
		final BankSnapshot saved = bankWith(tradeable(1)).withPouch(3, 1, PriceServiceTest.T0 - 5_000L);
		when(f.store.loadBank(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE)).thenReturn(saved);

		f.warmUpFromDisk();

		assertEquals(4, row(PriceServiceTest.BOND).quantity());
		assertEquals(3, row(PriceServiceTest.BOND).pouchQuantity());
		assertEquals(1, row(PriceServiceTest.BOND_UNTRADEABLE).quantity());
		assertEquals(BANK_VALUE + 4L * T + U, f.lastStatus().portfolio().valueNow());
	}

	/**
	 * Refresh re-stamps the plugin's cached bank, which can hold an OLDER pouch read than the service now does: the
	 * newer read is kept, and is what is saved.
	 */
	@Test
	public void aBankCarryingAnOlderPouchReadNeverReplacesANewerOne()
	{
		f.warmUpWith(bankWith(tradeable(1)));
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 2, 0, PriceServiceTest.T0 + 1_000L);
		final BankSnapshot older = f.service.bank();
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 3, 0, PriceServiceTest.T0 + 2_000L);
		assertEquals(4, row(PriceServiceTest.BOND).quantity());

		f.service.setBank(older.withCarried(new BankReader.Carried(Collections.<BankItem>emptyList(),
			Collections.<BankItem>emptyList(), Collections.<BankItem>emptyList(), 0L, 0L, PriceServiceTest.T0 + 3_000L)));

		assertEquals("the newer pouch read stands", 4, row(PriceServiceTest.BOND).quantity());
		assertEquals(3, row(PriceServiceTest.BOND).pouchQuantity());
		final ArgumentCaptor<BankSnapshot> saved = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(saved.capture());
		assertEquals(3, saved.getValue().pouchTradeable);
		assertEquals(PriceServiceTest.T0 + 2_000L, saved.getValue().pouchAtMillis);
	}

	/**
	 * A plugin switched off and on while logged in: the replayed bank container knows nothing of the pouch, and
	 * lands before the disk load finishes. The pouch the file carries is kept on it and saved.
	 */
	@Test
	public void aReplayedBankKeepsThePouchTheFileCarries()
	{
		f.scheduler.deferred = true;
		final BankSnapshot onDisk = bankWith(tradeable(1)).withPouch(3, 1, PriceServiceTest.T0 - 5_000L);
		when(f.store.loadBank(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE)).thenReturn(onDisk);
		f.service.start();

		f.service.setLoggedIn(true, PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE); // queued: the disk load
		f.service.setBank(bankWith(tradeable(1)));                                      // the container replay, no pouch
		f.scheduler.runPending();                                                       // the disk load finishes older

		final BankSnapshot held = f.service.bank();
		assertEquals(PriceServiceTest.T0, held.capturedAtMillis);
		assertEquals("the replayed bank keeps the file's pouch", 3, held.pouchTradeable);
		assertEquals(1, held.pouchUntradeable);
		assertEquals(PriceServiceTest.T0 - 5_000L, held.pouchAtMillis);
		final ArgumentCaptor<BankSnapshot> saved = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(saved.capture());
		assertEquals("and it is saved with it", 3, saved.getValue().pouchTradeable);
	}

	/** The split line names the pouch with both place switches off: the pouch merges with the bonds switch alone. */
	@Test
	public void theSplitLineNamesThePouchWithBothPlaceSwitchesOff()
	{
		f.warmUpWith(bankWith(tradeable(2)));
		final ViewOptions off = ViewOptions.DEFAULT.withCountInventory(false).withCountGrandExchange(false);
		f.service.setOptions(off);
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 1, 0, PriceServiceTest.T0 + 1_000L);

		final String open = MovementRowPanel.detail(row(PriceServiceTest.BOND), MovementWindow.D1, null, 0L, off, false);
		assertTrue(open, open.contains("2 in bank, 1 in bond pouch"));
	}

	/** A bank made of the pouch alone is saved, but it is no bank read: it is never a Net Worth History reading. */
	@Test
	public void aPouchReadBeforeAnyBankIsNeverAHistoryReading()
	{
		f.warmUpWithPouchOnly(2, 1);

		assertEquals("the bonds count", 2, row(PriceServiceTest.BOND).quantity());
		assertEquals("but no day is recorded from them", 0, f.lastStatus().bankHistory().size());

		f.service.setBank(bankWith(tradeable(1)));
		assertEquals("the first real bank read records the day", 1, f.lastStatus().bankHistory().size());
	}

	/** The disk load finishing after a pouch-only bank was made: the real file wins, with the newer pouch lent to it. */
	@Test
	public void aPouchOnlyBankNeverBeatsTheSavedBank()
	{
		f.scheduler.deferred = true;
		final BankSnapshot onDisk = bankWith(tradeable(1)).withPouch(0, 0, PriceServiceTest.T0 - 5_000L);
		when(f.store.loadBank(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE)).thenReturn(onDisk);
		f.service.start();

		f.service.setLoggedIn(true, PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE); // queued: the disk load
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 3, 0, PriceServiceTest.T0 + 5_000L);
		f.scheduler.runPending();

		final BankSnapshot held = f.service.bank();
		assertEquals("the file's stacks are kept", onDisk.items.size(), held.items.size());
		assertEquals("with the newer pouch", 3, held.pouchTradeable);
		final ArgumentCaptor<BankSnapshot> saved = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(saved.capture());
		final BankSnapshot last = saved.getValue();
		assertEquals("and the file is not overwritten by an empty bank", onDisk.items.size(), last.items.size());
		assertEquals(3, last.pouchTradeable);
	}

	/** A held pouch stamped in the future (a clock that was ahead) does not undo the reads made after it. */
	@Test
	public void aPouchReadStampedInTheFutureDoesNotUndoLaterReads()
	{
		f.warmUpWith(bankWith(tradeable(1)));
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 3, 0, PriceServiceTest.T0 + 10_000_000L);
		assertEquals(4, row(PriceServiceTest.BOND).quantity());

		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 1, 0, PriceServiceTest.T0 + 1_000L);

		assertEquals("the new read stands", 2, row(PriceServiceTest.BOND).quantity());
		assertEquals(1, row(PriceServiceTest.BOND).pouchQuantity());
	}

	/** An equal pouch read changes nothing and writes nothing more. */
	@Test
	public void anEqualPouchReadWritesNothing()
	{
		f.warmUpWith(bankWith(tradeable(1)));
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 2, 0, PriceServiceTest.T0 + 1_000L);
		final ArgumentCaptor<BankSnapshot> first = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(first.capture());
		final int writes = first.getAllValues().size();

		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 2, 0, PriceServiceTest.T0 + 9_000L);
		f.service.setPouch(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE, 0, 0, PriceServiceTest.T0 + 9_000L);

		final ArgumentCaptor<BankSnapshot> second = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(f.store, atLeastOnce()).saveBank(second.capture());
		assertEquals("an equal read is no write; an emptying one is", writes + 1, second.getAllValues().size());
	}

	// ---------------------------------------------------------------- the pouch off the Your Bonds screen

	private static Widget picture(final int itemId, final int xMode, final int x, final int quantity)
	{
		final Widget w = mock(Widget.class);
		when(w.getItemId()).thenReturn(itemId);
		when(w.getXPositionMode()).thenReturn(xMode);
		when(w.getOriginalX()).thenReturn(x);
		when(w.getItemQuantity()).thenReturn(quantity);
		return w;
	}

	private static Widget column(final Widget... children)
	{
		final Widget column = mock(Widget.class);
		when(column.getDynamicChildren()).thenReturn(children);
		return column;
	}

	/**
	 * The pouch is the one left-anchored picture of the bond - whatever order the children come in. Live check 2026-10-28:
	 * the left column sits under the screen's gold sack, and container 536 (the pouch) held the bond it counted; the middle
	 * column, which 1.2.4's first build read, is the inventory.
	 */
	@Test
	public void thePouchIsFoundByItemAndPositionWhateverTheChildOrder()
	{
		final int bond = PriceServiceTest.BOND;
		final Widget pouch = picture(bond, WidgetPositionMode.ABSOLUTE_LEFT, 80, 3);
		final Widget inventory = picture(bond, WidgetPositionMode.ABSOLUTE_RIGHT, 46, 5);
		final Widget bank = picture(bond, WidgetPositionMode.ABSOLUTE_RIGHT, 5, 7);
		final Widget stranger = picture(bond + 1, WidgetPositionMode.ABSOLUTE_LEFT, 80, 99);
		final Widget[][] orders = {
			{pouch, inventory, bank}, {bank, inventory, pouch}, {inventory, bank, pouch}, {stranger, bank, pouch, inventory},
			{inventory, stranger, pouch},
		};
		for (final Widget[] order : orders)
		{
			assertEquals(3, BankReader.pouchCount(column(order), bond));
		}
		assertEquals("another bond's column is read for that bond", 99,
			BankReader.pouchCount(column(pouch, inventory, bank, stranger), bond + 1));
		assertEquals("an empty pouch reads 0 - never the inventory's count", 0, BankReader.pouchCount(column(inventory,
			picture(bond, WidgetPositionMode.ABSOLUTE_LEFT, 80, 0), bank), bond));
	}

	@Test
	public void aColumnThatCannotBeReadIsNoCount()
	{
		final int bond = PriceServiceTest.BOND;
		assertEquals(-1, BankReader.pouchCount(null, bond));
		assertEquals(-1, BankReader.pouchCount(column(), bond));
		final Widget noChildren = mock(Widget.class);
		assertEquals(-1, BankReader.pouchCount(noChildren, bond));
		assertEquals("no left-anchored picture: no pouch", -1, BankReader.pouchCount(
			column(picture(bond, WidgetPositionMode.ABSOLUTE_RIGHT, 46, 5), picture(bond, WidgetPositionMode.ABSOLUTE_RIGHT, 5, 3)),
			bond));
		assertEquals("two left-anchored pictures: it is not known which it is", -1, BankReader.pouchCount(
			column(picture(bond, WidgetPositionMode.ABSOLUTE_LEFT, 80, 5), picture(bond, WidgetPositionMode.ABSOLUTE_LEFT, 40, 3)),
			bond));
	}

	/** The "Old School Bonds" totals: the picture whose item is the bond, found by item whatever the child order. */
	@Test
	public void aBondTotalIsFoundByItemWhateverTheChildOrder()
	{
		final int bond = PriceServiceTest.BOND;
		final Widget tradeable = picture(bond, WidgetPositionMode.ABSOLUTE_LEFT, 0, 4);
		final Widget untradeable = picture(PriceServiceTest.BOND_UNTRADEABLE, WidgetPositionMode.ABSOLUTE_LEFT, 0, 0);
		final Widget stranger = picture(bond + 5, WidgetPositionMode.ABSOLUTE_LEFT, 0, 99);
		for (final Widget[] order : new Widget[][]{{tradeable, untradeable, stranger}, {stranger, untradeable, tradeable},
			{untradeable, tradeable}})
		{
			assertEquals(4, BankReader.bondTotal(column(order), bond));
			assertEquals("a count of 0 is a count", 0, BankReader.bondTotal(column(order), PriceServiceTest.BOND_UNTRADEABLE));
		}
	}

	@Test
	public void aBondTotalThatCannotBeFoundIsNoCount()
	{
		final int bond = PriceServiceTest.BOND;
		assertEquals(-1, BankReader.bondTotal(null, bond));
		assertEquals(-1, BankReader.bondTotal(column(), bond));
		assertEquals(-1, BankReader.bondTotal(mock(Widget.class), bond));
		assertEquals("the other bond's picture is not this one's", -1, BankReader.bondTotal(
			column(picture(PriceServiceTest.BOND_UNTRADEABLE, WidgetPositionMode.ABSOLUTE_LEFT, 0, 3)), bond));
	}

	// ---------------------------------------------------------------- the files

	/** An old bank file - no pouch fields - loads, as an empty pouch never read. */
	@Test
	public void anOldBankFileLoadsWithAnEmptyPouch()
	{
		final String old = "{\"items\":[{\"id\":13190,\"quantity\":2,\"name\":\"Old school bond\",\"stackable\":false,"
			+ "\"untradeable\":false,\"haPrice\":0}],\"capturedAtMillis\":5,\"accountHash\":7,\"profileType\":\"STANDARD\","
			+ "\"currencyGp\":10,\"carriedGp\":0,\"exchangeGp\":0,\"carriedAtMillis\":0}";
		final BankSnapshot loaded = new Gson().fromJson(old, BankSnapshot.class);
		loaded.normalize();

		assertEquals(1, loaded.items.size());
		assertEquals(0, loaded.pouchTradeable);
		assertEquals(0, loaded.pouchUntradeable);
		assertEquals(0L, loaded.pouchAtMillis);
	}

	@Test
	public void thePouchRoundTripsThroughTheBankFile() throws IOException
	{
		final PriceStore store = new PriceStore(new Gson(), TestFilepaths.rooted(tmp.getRoot()));
		final BankSnapshot saved = bankWith(tradeable(1)).withPouch(4, 2, 12_345L);

		store.saveBank(saved);
		final BankSnapshot back = store.loadBank(PriceServiceTest.ACCOUNT, PriceServiceTest.PROFILE);

		assertNotNull(back);
		assertEquals(4, back.pouchTradeable);
		assertEquals(2, back.pouchUntradeable);
		assertEquals(12_345L, back.pouchAtMillis);
		assertTrue("and the pouch is content: a pouch-only change is a different bank",
			!saved.sameContentAs(bankWith(tradeable(1)).withPouch(5, 2, 12_345L)));
		assertTrue(saved.sameContentAs(bankWith(tradeable(1)).withPouch(4, 2, 99L)));
	}

	// ---------------------------------------------------------------- the history

	/** The four bonds cells hold each place's bonds at the card's prices, and the older cells are what they were. */
	@Test
	public void theHistoryFilesBondsInTheirOwnCellsOnePerPlace()
	{
		f.warmUpWith(PriceServiceTest.bank(PriceServiceTest.T0));
		final BankHistoryPoint before = f.lastStatus().bankHistory().last();

		f.service.setBank(everywhere());

		final BankHistoryPoint p = f.lastStatus().bankHistory().last();
		assertEquals("bank: 2 tradeable + 1 untradeable", 2L * T + U, p.card(BankHistoryPoint.BANK_BONDS));
		assertEquals("carried: one of each", T + U, p.card(BankHistoryPoint.CARRIED_BONDS));
		assertEquals("offers: three tradeable", 3L * T, p.card(BankHistoryPoint.GE_BONDS));
		assertEquals("pouch: 4 + 2", 4L * T + 2L * U, p.card(BankHistoryPoint.POUCH_BONDS));
		for (final int cell : new int[]{BankHistoryPoint.BANK_TRADEABLE, BankHistoryPoint.BANK_PARTS,
			BankHistoryPoint.CARRIED_TRADEABLE, BankHistoryPoint.CARRIED_PARTS, BankHistoryPoint.GE_TRADEABLE})
		{
			assertEquals("bonds are kept OUT of cell " + cell, before.card(cell), p.card(cell));
		}
		assertEquals(f.lastStatus().portfolio().valueNow(), p.valueFor(f.lastStatus().options()));
	}

	/**
	 * THE invariant, with bonds: for every combination of the six switches (live prices off here - the live variant is
	 * the next test), the reading recorded under all-on adds up, under that combination, to the Bank value the same
	 * computation shows.
	 */
	@Test
	public void underEverySwitchSetTheReadingEqualsTheCardToTheGp()
	{
		f.warmUpWith(everywhere());
		final List<ViewOptions> sets = new ArrayList<>();
		for (final boolean cash : new boolean[]{true, false})
		{
			for (final boolean alch : new boolean[]{true, false})
			{
				for (final boolean inventory : new boolean[]{true, false})
				{
					for (final boolean offers : new boolean[]{true, false})
					{
						for (final boolean bonds : new boolean[]{true, false})
						{
							sets.add(ViewOptions.DEFAULT.withCountCash(cash).withCountUntradeables(alch)
								.withCountInventory(inventory).withCountGrandExchange(offers).withCountBonds(bonds));
						}
					}
				}
			}
		}
		for (final ViewOptions options : sets)
		{
			f.service.setOptions(options);
			final PriceService.Status status = f.lastStatus();
			assertEquals(options, status.options());
			assertEquals(options.toString(), status.portfolio().valueNow(), status.bankHistory().last().valueFor(options));
		}
	}

	/** The same invariant with a LIVE tradeable bond, where the card's figures and the guide's differ. */
	@Test
	public void theInvariantHoldsWithALiveBondToo()
	{
		final long buy = 14_100_000L;
		final long sell = 13_900_000L;
		final long mid = (buy + sell) / 2L;
		final long now = f.clock.get() / 1000L;
		f.warmUpLiveWith(everywhere(), Collections.singletonMap(PriceServiceTest.BOND,
			new TradedPriceClient.Quote(buy, now - 3_600L, sell, now - 7_200L)));
		final Map<Integer, TradedPriceClient.Bucket> yesterday = new LinkedHashMap<>(PriceServiceTest.tradedSep7());
		yesterday.put(PriceServiceTest.BOND, new TradedPriceClient.Bucket(mid, 500L, null, 0L));
		f.answerDay(PriceServiceTest.SEP_7, yesterday);

		assertEquals("the bond is graded", MovementRow.PriceSource.LIVE, row(PriceServiceTest.BOND).source());
		for (final boolean live : new boolean[]{true, false})
		{
			for (final boolean inventory : new boolean[]{true, false})
			{
				for (final boolean bonds : new boolean[]{true, false})
				{
					final ViewOptions options = ViewOptions.DEFAULT.withLivePrices(live).withCountInventory(inventory)
						.withCountBonds(bonds);
					f.service.setOptions(options);
					final PriceService.Status status = f.lastStatus();
					assertEquals(options.toString(), status.portfolio().valueNow(),
						status.bankHistory().last().valueFor(options));
				}
			}
		}
	}
}
