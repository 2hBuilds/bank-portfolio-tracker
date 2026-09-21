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
		assertFalse("AH: and the hover text is OFF until the user asks for it",
			ViewOptions.DEFAULT.showHoverText());
	}

	/**
	 * Addendum AH, and the one thing about this switch that is easy to lose in a renumbering: <b>its default is the
	 * QUIETER sidebar</b>, where every other switch here defaults to the fuller one. {@code countCash},
	 * {@code livePrices} and {@code countInventory} default ON because leaving them off would withhold figures a
	 * reader expects; {@code countUntradeables} defaults off, but off is the plugin's own long-standing shape - the
	 * tradeable list - rather than a thing taken away.
	 *
	 * <p>{@code showHoverText} is the only one of the five that, at its default, shows LESS than the build before it
	 * did: the hero card's hover and every control's were drawn unconditionally through addendum AG, and on a fresh
	 * profile they are now drawn not at all. That is the user's own request ("i would like it to be default 'off'
	 * and only display hover text if turned on", 2026-09-20), so it is pinned in a test whose name says it - a
	 * later hand that "tidied" the default to true would otherwise pass every other test in this file.
	 *
	 * <p>The field itself was deleted by addendum AI and restored by AJ, when the user asked where the switch had
	 * gone. That round trip is the reason the default is pinned by name here as well as beside the other four:
	 * a value re-added by hand is exactly where a shipped default gets written down the wrong way round.
	 */
	@Test
	public void theHoverSwitchIsTheOneSwitchWhoseDefaultShowsLessThanTheBuildBeforeIt()
	{
		assertFalse("AH: a fresh profile draws no hover text at all", ViewOptions.DEFAULT.showHoverText());
		assertTrue("...while the three switches that ADD figures all default on",
			ViewOptions.DEFAULT.countCash() && ViewOptions.DEFAULT.livePrices()
				&& ViewOptions.DEFAULT.countInventory());
		// Turning it on is what restores the addendum-AG sidebar, and it is the only difference between the two.
		final ViewOptions hovers = ViewOptions.DEFAULT.withShowHoverText(true);
		assertTrue(hovers.showHoverText());
		assertNotEquals(ViewOptions.DEFAULT, hovers);
		assertEquals("and nothing else moved with it", ViewOptions.DEFAULT, hovers.withShowHoverText(false));
	}

	@Test
	public void eachWithFlipsOneSwitchAndLeavesTheOthers()
	{
		final ViewOptions cashOff = ViewOptions.DEFAULT.withCountCash(false);
		assertFalse(cashOff.countCash());
		assertFalse(cashOff.countUntradeables());
		assertTrue(cashOff.livePrices());
		assertTrue(cashOff.countInventory());
		assertFalse(cashOff.showHoverText());

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
			new ViewOptions(true, false, true, true, false));
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
		assertFalse(bankOnly.showHoverText());
		assertTrue("and back again", bankOnly.withCountInventory(true).countInventory());
		assertEquals(ViewOptions.DEFAULT, bankOnly.withCountInventory(true));
	}

	/** AH: the hover switch is one more {@code with} of the same shape, and it moves nothing else. */
	@Test
	public void withShowHoverTextFlipsTheFifthSwitchAlone()
	{
		final ViewOptions hovers = ViewOptions.DEFAULT.withShowHoverText(true);

		assertTrue(hovers.showHoverText());
		assertTrue(hovers.countCash());
		assertFalse(hovers.countUntradeables());
		assertTrue(hovers.livePrices());
		assertTrue(hovers.countInventory());
		assertFalse("and back again", hovers.withShowHoverText(false).showHoverText());
		assertEquals(ViewOptions.DEFAULT, hovers.withShowHoverText(false));
		// ...and it survives every other switch being flipped over it, which is what makes it a field and not a mode.
		assertTrue("the hover text stays on through the other four",
			hovers.withCountCash(false).withCountUntradeables(true)
				.withLivePrices(false).withCountInventory(false).showHoverText());
	}

	/**
	 * Addendum AO line AO1 deleted the ladder of shorter constructors along with {@code holdingOnRows}, so there is
	 * exactly ONE way to build this value and its five arguments are the whole contract. The test that stood here
	 * pinned what each rung of that ladder defaulted (the inventory ON, the hovers OFF); its subject is gone, and
	 * what replaces it is the reason the ladder went.
	 *
	 * <p>The removed field sat in the MIDDLE of the list, so a five-argument call meaning
	 * {@code (cash, untradeables, holding, live, inventory)} would still COMPILE against
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
		assertTrue("fifth: hover", new ViewOptions(false, false, false, false, true).showHoverText());

		assertFalse("and nothing else came on with it", new ViewOptions(true, false, false, false, false)
			.countUntradeables());
		assertFalse(new ViewOptions(false, false, false, false, true).countCash());

		assertEquals("so the default spelled out IS DEFAULT", ViewOptions.DEFAULT,
			new ViewOptions(true, false, true, true, false));
	}

	@Test
	public void asMapPrintsTheFiveSwitchesInTheOrderTheBridgeUses()
	{
		final ViewOptions o = new ViewOptions(false, true, false, false, true);
		assertEquals("[cash, untradeables, live, inventory, hover]",
			new ArrayList<>(o.asMap().keySet()).toString());
		assertEquals("[false, true, false, false, true]", new ArrayList<>(o.asMap().values()).toString());
		assertEquals("AO1: live moves up to THIRD, where holding used to sit", "live",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(2));
		assertEquals("Y1: inventory is the FOURTH key", "inventory",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(3));
		assertEquals("AH: hover is the FIFTH and last", "hover",
			new ArrayList<>(ViewOptions.DEFAULT.asMap().keySet()).get(4));
		assertEquals("and a fresh profile prints it off", Boolean.FALSE, ViewOptions.DEFAULT.asMap().get("hover"));
		assertFalse("AO1: and nothing prints the deleted switch any more",
			ViewOptions.DEFAULT.asMap().containsKey("holding"));
	}

	@Test
	public void equalityIsByValue()
	{
		assertEquals(new ViewOptions(true, false, true, true, false), ViewOptions.DEFAULT);
		assertEquals(ViewOptions.DEFAULT.hashCode(),
			new ViewOptions(true, false, true, true, false).hashCode());
		// Every remaining field on its own: the panel decides what to rebuild by comparing two of these values, so
		// a field equals() skipped would be a switch the user pressed and the sidebar never followed.
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountCash(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountUntradeables(true));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withLivePrices(false));
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withCountInventory(false));
		// AH: two options that differ in the hover switch ALONE are two different options - the panel decides
		// whether to build the card's tooltip and whether the controls carry theirs by comparing them, so an
		// equals() that ignored this field would leave the sidebar's hover text whatever it was when the menu
		// item was last pressed.
		assertNotEquals(ViewOptions.DEFAULT, ViewOptions.DEFAULT.withShowHoverText(true));
		assertNotEquals(ViewOptions.DEFAULT.hashCode(), ViewOptions.DEFAULT.withShowHoverText(true).hashCode());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, hover=false}",
			ViewOptions.DEFAULT.toString());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, hover=true}",
			ViewOptions.DEFAULT.withShowHoverText(true).toString());
	}

	/**
	 * The field count is pinned by a number as well as by the assertions above, because this value has changed shape
	 * three times in three days: addendum AI deleted the hover switch, AJ restored it, and addendum AO line AO1
	 * deleted {@code holdingOnRows} when the user answered "if it doesnt do anything anymore then remove it". Every
	 * field here is read on the {@code ConfigChanged} option road and compared by {@link ViewOptions#equals}, so a
	 * sixth arriving unnoticed would be a switch the sidebar never followed - and a fifth going missing again would
	 * be a stored answer nothing reads.
	 */
	@Test
	public void theValueCarriesFiveSwitchesAndNoMore()
	{
		assertEquals("AO1: holding is gone, so the bridge prints five", 5, ViewOptions.DEFAULT.asMap().size());
		assertEquals("ViewOptions{cash=true, untradeables=false, live=true, inventory=true, hover=false}",
			ViewOptions.DEFAULT.toString());
	}
}
