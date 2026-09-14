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
		assertFalse(ViewOptions.DEFAULT.holdingOnRows());
		assertTrue(ViewOptions.DEFAULT.livePrices());
		assertTrue("Y1: the inventory and worn gear count unless the user says otherwise",
			ViewOptions.DEFAULT.countInventory());
	}

	@Test
	public void eachWithFlipsOneSwitchAndLeavesTheOthers()
	{
		final ViewOptions cashOff = ViewOptions.DEFAULT.withCountCash(false);
		assertFalse(cashOff.countCash());
		assertFalse(cashOff.countUntradeables());
		assertFalse(cashOff.holdingOnRows());

		final ViewOptions untradeables = ViewOptions.DEFAULT.withCountUntradeables(true);
		assertTrue(untradeables.countCash());
		assertTrue(untradeables.countUntradeables());
		assertFalse(untradeables.holdingOnRows());

		final ViewOptions holding = ViewOptions.DEFAULT.withHoldingOnRows(true);
		assertTrue(holding.countCash());
		assertFalse(holding.countUntradeables());
		assertTrue(holding.holdingOnRows());

		final ViewOptions guideOnly = ViewOptions.DEFAULT.withLivePrices(false);
		assertFalse(guideOnly.livePrices());
		assertTrue(guideOnly.countCash());
		assertTrue("and the inventory is untouched by it", guideOnly.countInventory());
		assertEquals("the three-argument constructor keeps live at its default", ViewOptions.DEFAULT, new ViewOptions(true, false, false));
	}

	/** Y1: the fifth switch is one more {@code with} of the same shape, and it moves nothing else. */
	@Test
	public void withCountInventoryFlipsTheFifthSwitchAlone()
	{
		final ViewOptions bankOnly = ViewOptions.DEFAULT.withCountInventory(false);

		assertFalse(bankOnly.countInventory());
		assertTrue(bankOnly.countCash());
		assertFalse(bankOnly.countUntradeables());
		assertFalse(bankOnly.holdingOnRows());
		assertTrue(bankOnly.livePrices());
		assertTrue("and back again", bankOnly.withCountInventory(true).countInventory());
		assertEquals(ViewOptions.DEFAULT, bankOnly.withCountInventory(true));
	}

	/** Y1: the four-argument constructor is the pre-Y one and delegates with the new switch ON. */
	@Test
	public void theOlderConstructorsDelegateWithTheInventoryOn()
	{
		assertTrue(new ViewOptions(true, false, false, true).countInventory());
		assertTrue(new ViewOptions(false, true, true).countInventory());
		assertEquals(ViewOptions.DEFAULT, new ViewOptions(true, false, false, true));
		assertEquals(new ViewOptions(false, true, true, false, true), new ViewOptions(false, true, true, false));
	}

	@Test
	public void asMapPrintsTheFiveSwitchesInTheOrderTheBridgeUses()
	{
		final ViewOptions o = new ViewOptions(false, true, true, false, false);
		assertEquals("[cash, untradeables, holding, live, inventory]", new ArrayList<>(o.asMap().keySet()).toString());
		assertEquals("[false, true, true, false, false]", new ArrayList<>(o.asMap().values()).toString());
		assertEquals("Y1: inventory is the FIFTH key", "inventory",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(4));
	}

	@Test
	public void equalityIsByValue()
	{
		assertEquals(new ViewOptions(true, false, false, true, true), ViewOptions.DEFAULT);
		assertEquals(ViewOptions.DEFAULT.hashCode(), new ViewOptions(true, false, false, true, true).hashCode());
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withHoldingOnRows(true));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withLivePrices(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountInventory(false));
		assertEquals("ViewOptions{cash=true, untradeables=false, holding=false, live=true, inventory=true}",
			ViewOptions.DEFAULT.toString());
	}
}
