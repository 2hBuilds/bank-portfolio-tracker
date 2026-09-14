package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * {@link BankItem}'s two addendum-Q fields (Q5), the addendum-R {@code parts} beside them (R1), and what all three
 * mean off disk.
 *
 * <p>The first test is the load-bearing one. A snapshot is a Gson bag of public fields, and every
 * {@code bank-*.json} on a user's machine was written before addendum Q existed: it names neither field. Gson
 * leaves an absent {@code boolean} at {@code false}, so the field had to be spelled {@code untradeable} rather
 * than {@code tradeable} - the other way round, every stack of every stored bank would have read as untradeable,
 * been left out of the rows while the switch was off, and emptied the sidebar of anyone who had not opened their
 * bank since updating.
 */
public class BankItemTest
{
	private static final Gson GSON = new Gson();

	/** A stack as a pre-Q file spells it: four fields, neither of the new ones. */
	private static final String PRE_Q = "{\"id\":4151,\"quantity\":3,\"name\":\"Abyssal whip\",\"stackable\":false}";

	@Test
	public void aStackFromAFileWrittenBeforeAddendumQReadsAsTradeable()
	{
		final BankItem item = GSON.fromJson(PRE_Q, BankItem.class);

		assertEquals(4151, item.id);
		assertEquals(3, item.quantity);
		assertFalse("an absent boolean is false, and false here means tradeable", item.untradeable);
		assertEquals(0, item.haPrice);
		assertNull("so it is priced by the guide table, not alched", item.alchPrice());
	}

	@Test
	public void anUntradeableStackSurvivesTheRoundTrip()
	{
		final BankItem written = new BankItem(772, 2, "Dramen staff", false, true, 1500);

		final BankItem read = GSON.fromJson(GSON.toJson(written), BankItem.class);

		assertEquals(written, read);
		assertTrue(read.untradeable);
		assertEquals(1500, read.haPrice);
		assertEquals(Long.valueOf(1500L), read.alchPrice());
	}

	/** The four-argument constructor is the tradeable one, so every existing caller keeps its meaning. */
	@Test
	public void theShortConstructorMakesATradeableStack()
	{
		final BankItem item = new BankItem(385, 500, "Shark", true);

		assertFalse(item.untradeable);
		assertEquals(0, item.haPrice);
		assertNull(item.alchPrice());
	}

	/**
	 * The one rule for "this stack is valued at its alch price", spelled once so the reader's keep rule, the row
	 * and the bank value cannot drift apart: untradeable AND worth something. Either half alone is nothing.
	 */
	@Test
	public void anAlchPriceNeedsBothTheFlagAndAPositiveValue()
	{
		assertNull("marked but worthless", new BankItem(1, 1, "Junk", false, true, 0).alchPrice());
		assertNull("a negative is not a price", new BankItem(1, 1, "Junk", false, true, -5).alchPrice());
		assertNull("a value on a tradeable stack is not what it is priced at",
			new BankItem(4151, 1, "Abyssal whip", false, false, 72_000).alchPrice());
		assertEquals(Long.valueOf(1L), new BankItem(1, 1, "Junk", false, true, 1).alchPrice());
	}

	@Test
	public void theTwoNewFieldsArePartOfTheStacksIdentity()
	{
		final BankItem tradeable = new BankItem(772, 2, "Dramen staff", false);
		final BankItem untradeable = new BankItem(772, 2, "Dramen staff", false, true, 1500);
		final BankItem cheaper = new BankItem(772, 2, "Dramen staff", false, true, 1400);

		assertNotEquals("a bank that changed which stacks it holds must be written", tradeable, untradeable);
		assertNotEquals(untradeable, cheaper);
		assertEquals(untradeable, new BankItem(772, 2, "Dramen staff", false, true, 1500));
		assertEquals(untradeable.hashCode(), new BankItem(772, 2, "Dramen staff", false, true, 1500).hashCode());
	}

	/** An ordinary stack prints what it always printed; only a marked one says more. */
	@Test
	public void toStringNamesTheAlchValueOnlyWhenThereIsOne()
	{
		assertEquals("BankItem{id=385, quantity=500, name='Shark', stackable=true}",
			new BankItem(385, 500, "Shark", true).toString());
		assertEquals("BankItem{id=772, quantity=2, name='Dramen staff', stackable=false, untradeable, haPrice=1500}",
			new BankItem(772, 2, "Dramen staff", false, true, 1500).toString());
	}

	// ---------------------------------------------------------------- R1: the tradeable parts

	/** The addendum's own example: a Crystal body reverts to three Crystal armour seeds. */
	private static final int CRYSTAL_BODY = 23_975;
	private static final int ARMOUR_SEED = 23_956;
	private static final String SEED_NAME = "Crystal armour seed";

	private static BankItem crystalBody()
	{
		return new BankItem(CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000,
			Collections.singletonList(new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME)));
	}

	/**
	 * The same rule the class comment makes of {@code untradeable}, for the field addendum R adds: every
	 * {@code bank-*.json} on a user's machine was written before R and names no {@code parts}, Gson leaves an
	 * absent object null, and null has to read as "no parts" - which is what those files held. The next bank open
	 * fills it in.
	 */
	@Test
	public void aStackFromAFileWrittenBeforeAddendumRHasNoParts()
	{
		final BankItem item = GSON.fromJson(PRE_Q, BankItem.class);

		assertNull(item.parts);
		assertFalse("so it is valued as it was: by the guide table or, when marked, by the alch rule", item.hasParts());
	}

	@Test
	public void aPartSurvivesTheRoundTripAsAPlainGsonValue()
	{
		final BankItem read = GSON.fromJson(GSON.toJson(crystalBody()), BankItem.class);

		assertEquals(crystalBody(), read);
		assertTrue(read.hasParts());
		assertEquals(1, read.parts.size());
		final BankItem.Part part = read.parts.get(0);
		assertEquals(ARMOUR_SEED, part.id);
		assertEquals("a long, as ItemMapping.getQuantity() is", 3L, part.quantity);
		assertEquals(SEED_NAME, part.name);
	}

	/**
	 * "No mapping" and "a mapping that named nothing" are the same answer to every reader, so only one of them is
	 * ever stored - otherwise {@code PriceService} would have to test for both before valuing a stack.
	 */
	@Test
	public void anEmptyPartsListIsStoredAsNoPartsAtAll()
	{
		assertNull(new BankItem(772, 1, "Dramen staff", false, true, 1500, Collections.emptyList()).parts);
		assertNull(new BankItem(772, 1, "Dramen staff", false, true, 1500, null).parts);
	}

	/** A file could hold {@code "parts":[null]}; that is no more a valuation than an absent field is. */
	@Test
	public void hasPartsIgnoresAListOfNothingButNulls()
	{
		final BankItem item = new BankItem(772, 1, "Dramen staff", false, true, 1500);
		item.parts = Arrays.asList((BankItem.Part) null, null);

		assertFalse(item.hasParts());
	}

	/** The six-argument constructor is the pre-R one, so every existing caller keeps its meaning. */
	@Test
	public void theSixArgumentConstructorRecordsNoParts()
	{
		assertNull(new BankItem(772, 2, "Dramen staff", false, true, 1500).parts);
		assertFalse(new BankItem(385, 500, "Shark", true).hasParts());
	}

	/**
	 * A bank whose stacks changed what they revert to has changed, and the change-only write rule (B008) compares
	 * stacks - so the parts have to be part of a stack's identity or a re-mapped item would never be re-written.
	 */
	@Test
	public void thePartsArePartOfTheStacksIdentity()
	{
		final BankItem withParts = crystalBody();
		final BankItem withNone = new BankItem(CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000);
		final List<BankItem.Part> two = Collections.singletonList(new BankItem.Part(ARMOUR_SEED, 2L, SEED_NAME));
		final BankItem withTwo = new BankItem(CRYSTAL_BODY, 1, "Crystal body", false, true, 900_000, two);

		assertNotEquals(withParts, withNone);
		assertNotEquals("three seeds is not two seeds", withParts, withTwo);
		assertEquals(withParts, crystalBody());
		assertEquals(withParts.hashCode(), crystalBody().hashCode());
	}

	@Test
	public void aPartIsAValueOfItsOwn()
	{
		final BankItem.Part part = new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME);

		assertEquals(part, new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME));
		assertEquals(part.hashCode(), new BankItem.Part(ARMOUR_SEED, 3L, SEED_NAME).hashCode());
		assertNotEquals(part, new BankItem.Part(ARMOUR_SEED, 1L, SEED_NAME));
		assertNotEquals(part, new BankItem.Part(ARMOUR_SEED + 1, 3L, SEED_NAME));
		assertNotEquals(part, new BankItem.Part(ARMOUR_SEED, 3L, "Crystal tool seed"));
		assertEquals("a null name is \"\", as a stack's is", "", new BankItem.Part(1, 1L, null).name);
		assertEquals("Part{id=23956, quantity=3, name='Crystal armour seed'}", part.toString());
	}

	/**
	 * Y3: the merge of a banked stack and a carried one is a COPY with the quantities added - the stacks it merges
	 * belong to the persisted snapshot, and a computation that wrote to one would put a number in
	 * {@code bank-*.json} that no bank ever held. Everything but the quantity rides along.
	 */
	@Test
	public void withQuantityCopiesTheStackAndChangesNothingElse()
	{
		final BankItem body = crystalBody();

		final BankItem merged = body.withQuantity(4);

		assertEquals(4, merged.quantity);
		assertEquals("the original is untouched", 1, body.quantity);
		assertEquals(body.id, merged.id);
		assertEquals(body.name, merged.name);
		assertEquals(body.stackable, merged.stackable);
		assertEquals(body.untradeable, merged.untradeable);
		assertEquals(body.haPrice, merged.haPrice);
		assertEquals(body.parts, merged.parts);
		assertEquals(body, merged.withQuantity(1));
	}

	/** An ordinary stack still prints what it always printed; only one with parts says more. */
	@Test
	public void toStringNamesThePartsOnlyWhenThereAreSome()
	{
		assertEquals("BankItem{id=385, quantity=500, name='Shark', stackable=true}",
			new BankItem(385, 500, "Shark", true).toString());
		assertEquals("BankItem{id=23975, quantity=1, name='Crystal body', stackable=false, untradeable, "
				+ "haPrice=900000, parts=[Part{id=23956, quantity=3, name='Crystal armour seed'}]}",
			crystalBody().toString());
	}
}
