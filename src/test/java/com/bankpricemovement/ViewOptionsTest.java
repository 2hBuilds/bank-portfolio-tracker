package com.bankpricemovement;

import java.util.ArrayList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class ViewOptionsTest
{
	@Test
	public void theDefaultCountsCashAndNothingElse()
	{
		assertTrue(ViewOptions.DEFAULT.countCash());
		assertFalse(ViewOptions.DEFAULT.countUntradeables());
		assertTrue(ViewOptions.DEFAULT.livePrices());
		assertTrue("Y1: the inventory and worn gear count unless the user says otherwise",
			ViewOptions.DEFAULT.countInventory());
		assertTrue("1.0.9 part 3: and so do the Grand Exchange offers", ViewOptions.DEFAULT.countGrandExchange());
	}

	/**
	 * Release 1.2.0 deleted addendum AH's hover switch (the user, 2026-10-08: every hover left is one short line, so the
	 * hovers are always on). The field is gone from the value, and a compile error names every caller that still asked
	 * for it; what a test CAN still say is that nothing the value prints, echoes or compares mentions it - the dev
	 * bridge's {@code state.options} is built from {@code asMap()}, and a script that still looked for
	 * {@code options.hover} should find the key absent rather than stale.
	 */
	@Test
	public void theHoverSwitchIsGoneFromWhatTheValuePrintsAndEchoes()
	{
		assertFalse("the bridge no longer echoes a hover key", ViewOptions.DEFAULT.asMap().containsKey("hover"));
		assertFalse("and neither does the value print one", ViewOptions.DEFAULT.toString().contains("hover"));
		// The value now differs from another only by the five switches that remain: spelled out, the default IS DEFAULT.
		assertEquals(ViewOptions.DEFAULT, new ViewOptions(true, false, true, true, true));
	}

	@Test
	public void eachWithFlipsOneSwitchAndLeavesTheOthers()
	{
		final ViewOptions cashOff = ViewOptions.DEFAULT.withCountCash(false);
		assertFalse(cashOff.countCash());
		assertFalse(cashOff.countUntradeables());
		assertTrue(cashOff.livePrices());
		assertTrue(cashOff.countInventory());
		assertTrue(cashOff.countGrandExchange());

		final ViewOptions untradeables = ViewOptions.DEFAULT.withCountUntradeables(true);
		assertTrue(untradeables.countCash());
		assertTrue(untradeables.countUntradeables());
		assertTrue(untradeables.livePrices());

		final ViewOptions guideOnly = ViewOptions.DEFAULT.withLivePrices(false);
		assertFalse(guideOnly.livePrices());
		assertTrue(guideOnly.countCash());
		assertTrue("and the inventory is untouched by it", guideOnly.countInventory());

		// The value is shared - DEFAULT is a static every road in the plugin reads - so a with() that wrote through
		// to the receiver would hand the whole client one user's answer. Pin the receiver, not just the copy.
		assertEquals("and none of them touched the value they were called on", ViewOptions.DEFAULT,
			new ViewOptions(true, false, true, true, true));
	}

	/** Y1: the inventory switch is one more {@code with} of the same shape, and it moves nothing else. */
	@Test
	public void withCountInventoryFlipsTheFourthSwitchAlone()
	{
		final ViewOptions bankOnly = ViewOptions.DEFAULT.withCountInventory(false);

		assertFalse(bankOnly.countInventory());
		assertTrue(bankOnly.countCash());
		assertFalse(bankOnly.countUntradeables());
		assertTrue(bankOnly.livePrices());
		assertTrue(bankOnly.countGrandExchange());
		assertTrue("and back again", bankOnly.withCountInventory(true).countInventory());
		assertEquals(ViewOptions.DEFAULT, bankOnly.withCountInventory(true));
	}

	/** 1.0.9 part 3: the Grand Exchange switch is one more {@code with} of the same shape, and it moves nothing else. */
	@Test
	public void withCountGrandExchangeFlipsTheFifthSwitchAlone()
	{
		final ViewOptions noOffers = ViewOptions.DEFAULT.withCountGrandExchange(false);

		assertFalse(noOffers.countGrandExchange());
		assertTrue(noOffers.countCash());
		assertFalse(noOffers.countUntradeables());
		assertTrue(noOffers.livePrices());
		assertTrue("and the inventory is untouched by it", noOffers.countInventory());
		assertTrue("and back again", noOffers.withCountGrandExchange(true).countGrandExchange());
		assertEquals(ViewOptions.DEFAULT, noOffers.withCountGrandExchange(true));
		assertEquals("and every other with() passes it through", noOffers,
			noOffers.withCountCash(true).withCountUntradeables(false).withLivePrices(true).withCountInventory(true));
		assertFalse(ViewOptions.DEFAULT.withCountCash(false).withCountUntradeables(true).withLivePrices(false)
			.withCountInventory(false).withCountGrandExchange(false).countGrandExchange());
	}

	/**
	 * Addendum AO line AO1 deleted the ladder of shorter constructors along with {@code holdingOnRows}, so there is
	 * exactly ONE way to build this value and its five arguments (six until release 1.2.0 removed the hover switch,
	 * the last of them) are the whole contract. The test that stood here
	 * pinned what each rung of that ladder defaulted (the inventory ON, the hovers OFF); its subject is gone, and
	 * what replaces it is the reason the ladder went.
	 *
	 * <p>The field AO removed sat in the MIDDLE of the list, so a five-argument call meaning
	 * {@code (cash, untradeables, holding, live, inventory)} would still COMPILE against that day's
	 * {@code (cash, untradeables, live, inventory, hover)} and quietly mean three different things. Nothing but a
	 * test can catch that, because the compiler never will: each position is pinned here on its own, by a call that
	 * sets that one argument true and every other false.
	 */
	@Test
	public void theOneConstructorTakesTheFiveSwitchesInThisOrder()
	{
		assertTrue("first: cash", new ViewOptions(true, false, false, false, false).countCash());
		assertTrue("second: untradeables", new ViewOptions(false, true, false, false, false).countUntradeables());
		assertTrue("third: live", new ViewOptions(false, false, true, false, false).livePrices());
		assertTrue("fourth: inventory", new ViewOptions(false, false, false, true, false).countInventory());
		assertTrue("fifth: Grand Exchange", new ViewOptions(false, false, false, false, true).countGrandExchange());
		assertFalse("and the fourth is not the fifth", new ViewOptions(false, false, false, true, false)
			.countGrandExchange());
		assertFalse(new ViewOptions(false, false, false, false, true).countInventory());

		assertFalse("and nothing else came on with it", new ViewOptions(true, false, false, false, false)
			.countUntradeables());
		assertFalse(new ViewOptions(false, false, false, false, true).countCash());

		assertEquals("so the default spelled out IS DEFAULT", ViewOptions.DEFAULT,
			new ViewOptions(true, false, true, true, true));
	}

	@Test
	public void asMapPrintsTheFiveSwitchesInTheOrderTheBridgeUses()
	{
		final ViewOptions o = new ViewOptions(false, true, false, false, true);
		assertEquals("[cash, untradeables, live, inventory, ge]", new ArrayList<>(o.asMap().keySet()).toString());
		assertEquals("[false, true, false, false, true]", new ArrayList<>(o.asMap().values()).toString());
		assertEquals("AO1: live moves up to THIRD, where holding used to sit", "live",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(2));
		assertEquals("Y1: inventory is the FOURTH key", "inventory",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(3));
		assertEquals("1.0.9 part 3: ge is the FIFTH and last, directly after inventory", "ge",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(4));
		assertFalse("AO1: and nothing prints the deleted holding switch any more",
			ViewOptions.DEFAULT.asMap().containsKey("holding"));
		assertFalse("release 1.2.0: nor the deleted hover switch", ViewOptions.DEFAULT.asMap().containsKey("hover"));
	}

	@Test
	public void equalityIsByValue()
	{
		assertEquals(new ViewOptions(true, false, true, true, true), ViewOptions.DEFAULT);
		assertEquals(ViewOptions.DEFAULT.hashCode(), new ViewOptions(true, false, true, true, true).hashCode());
		// Every remaining field on its own: the panel decides what to rebuild by comparing two of these values, so
		// a field equals() skipped would be a switch the user pressed and the sidebar never followed.
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountCash(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountUntradeables(true));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withLivePrices(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountInventory(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountGrandExchange(false));
		assertNotEquals(ViewOptions.DEFAULT.hashCode(), ViewOptions.DEFAULT.withCountGrandExchange(false).hashCode());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, ge=true}",
			ViewOptions.DEFAULT.toString());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, ge=false}",
			ViewOptions.DEFAULT.withCountGrandExchange(false).toString());
	}

	/**
	 * The field count is pinned by a number as well as by the assertions above, because this value has changed shape
	 * several times in a few weeks: addendum AI deleted the hover switch, AJ restored it, addendum AO line AO1 deleted
	 * {@code holdingOnRows} when the user answered "if it doesnt do anything anymore then remove it", and release 1.2.0
	 * deleted the hover switch for good. Every field here is read on the {@code ConfigChanged} option road and
	 * compared by {@link ViewOptions#equals}, so a sixth arriving unnoticed would be a switch the sidebar never
	 * followed - and a fifth going missing again would be a stored answer nothing reads.
	 */
	@Test
	public void theValueCarriesFiveSwitchesAndNoMore()
	{
		assertEquals("AO1 and release 1.2.0 took holding and hover out, so the bridge prints five", 5,
			ViewOptions.DEFAULT.asMap().size());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, ge=true}",
			ViewOptions.DEFAULT.toString());
	}
}
