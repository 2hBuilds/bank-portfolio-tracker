package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@link BankSnapshot} and {@link BankItem} (contract C5): the persisted bank, its repair pass, and the
 * round trip through the stock Gson that {@code PriceStore} writes.
 *
 * <p>{@link BankSnapshot#normalize()} is what lets every reader downstream skip its null checks, so each of
 * its clauses is pinned here separately - including the one that doubles as a feature: a bank PLACEHOLDER is
 * a quantity of 0 and disappears in the same sweep that drops corrupt rows (design D6).
 */
public class BankSnapshotTest
{
	private static BankSnapshot snapshot(final BankItem... items)
	{
		return new BankSnapshot(new ArrayList<>(Arrays.asList(items)), 1_700_000_000_000L, 123L, "STANDARD");
	}

	@Test
	public void aSnapshotCarriesItsStacksAndItsIdentity()
	{
		final BankSnapshot bank = snapshot(new BankItem(4151, 2, "Abyssal whip", false));

		assertFalse(bank.isEmpty());
		assertEquals(1, bank.items.size());
		assertEquals(4151, bank.items.get(0).id);
		assertEquals(2, bank.items.get(0).quantity);
		assertEquals("Abyssal whip", bank.items.get(0).name);
		assertFalse(bank.items.get(0).stackable);
		assertEquals(1_700_000_000_000L, bank.capturedAtMillis);
		assertEquals("the account the bank belongs to", 123L, bank.accountHash);
		assertEquals("STANDARD", bank.profileType);
	}

	@Test
	public void emptyIsTheNoBankYetSnapshot()
	{
		assertTrue(BankSnapshot.EMPTY.isEmpty());
		assertEquals(0, BankSnapshot.EMPTY.items.size());
		assertEquals(0L, BankSnapshot.EMPTY.capturedAtMillis);
		assertEquals(0L, BankSnapshot.EMPTY.accountHash);
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, BankSnapshot.EMPTY.profileType);
	}

	@Test
	public void aFreshSnapshotIsUsableBeforeAnythingIsSet()
	{
		final BankSnapshot bank = new BankSnapshot();
		assertNotNull("the no-arg ctor Gson uses leaves a list, not a null", bank.items);
		assertTrue(bank.isEmpty());
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, bank.profileType);
	}

	// ---------------------------------------------------------------- normalize

	@Test
	public void normalizeDropsNullEntries()
	{
		final BankSnapshot bank = snapshot(new BankItem(4151, 1, "Abyssal whip", false), null,
			new BankItem(2, 5, "Cannonball", true));

		bank.normalize();

		assertEquals(2, bank.items.size());
		assertEquals(4151, bank.items.get(0).id);
		assertEquals(2, bank.items.get(1).id);
	}

	@Test
	public void normalizeDropsNonPositiveIdsAndQuantities()
	{
		final BankSnapshot bank = snapshot(
			new BankItem(0, 5, "no id", false),
			new BankItem(-3, 5, "negative id", false),
			new BankItem(4151, 0, "a placeholder", false),
			new BankItem(4152, -2, "negative quantity", false),
			new BankItem(2, 1, "kept", true));

		bank.normalize();

		assertEquals(1, bank.items.size());
		assertEquals("only the sound row survives", 2, bank.items.get(0).id);
	}

	@Test
	public void normalizeNeverLeavesANullName()
	{
		final BankItem nameless = new BankItem();
		nameless.id = 4151;
		nameless.quantity = 1;
		nameless.name = null;

		final BankSnapshot bank = snapshot(nameless);
		bank.normalize();

		assertEquals(1, bank.items.size());
		assertEquals("", bank.items.get(0).name);
	}

	@Test
	public void normalizeFillsInAMissingProfileAndList()
	{
		final BankSnapshot bank = new BankSnapshot(null, -5L, 9L, null);
		bank.normalize();

		assertNotNull(bank.items);
		assertTrue(bank.isEmpty());
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, bank.profileType);
		assertEquals(0L, bank.capturedAtMillis);
		assertEquals("the account is left alone", 9L, bank.accountHash);

		final BankSnapshot blankProfile = new BankSnapshot(new ArrayList<>(), 1L, 9L, "");
		blankProfile.normalize();
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, blankProfile.profileType);
	}

	@Test
	public void normalizeKeepsAGoodSnapshotAsItIs()
	{
		final BankSnapshot bank = snapshot(new BankItem(4151, 2, "Abyssal whip", false),
			new BankItem(2, 5000, "Cannonball", true));

		bank.normalize();

		assertEquals(2, bank.items.size());
		assertEquals("Abyssal whip", bank.items.get(0).name);
		assertEquals(5000, bank.items.get(1).quantity);
		assertEquals(1_700_000_000_000L, bank.capturedAtMillis);
	}

	// ---------------------------------------------------------------- disk

	@Test
	public void theSnapshotRoundTripsThroughStockGson()
	{
		final Gson gson = new Gson();
		final BankSnapshot bank = snapshot(new BankItem(4151, 2, "Abyssal whip", false),
			new BankItem(2, 5000, "Cannonball", true));

		final BankSnapshot back = gson.fromJson(gson.toJson(bank), BankSnapshot.class);
		back.normalize();

		assertEquals(bank, back);
		assertEquals(2, back.items.size());
		assertEquals("Cannonball", back.items.get(1).name);
		assertTrue(back.items.get(1).stackable);
	}

	/**
	 * Back compat: a file written before {@code stackable} existed still parses, and the missing flag reads as
	 * false rather than stopping the load (the workspace rule for every persisted class).
	 */
	@Test
	public void jsonWithoutStackableParses()
	{
		final String json = "{\"items\":[{\"id\":4151,\"quantity\":2,\"name\":\"Abyssal whip\"}],"
			+ "\"capturedAtMillis\":17,\"accountHash\":123,\"profileType\":\"STANDARD\"}";

		final BankSnapshot bank = new Gson().fromJson(json, BankSnapshot.class);
		bank.normalize();

		assertEquals(1, bank.items.size());
		assertEquals(4151, bank.items.get(0).id);
		assertFalse("a field no old file has reads as false", bank.items.get(0).stackable);
		assertEquals(17L, bank.capturedAtMillis);
	}

	/**
	 * Back compat again, and this one has a consequence worth stating: {@code currencyGp} arrived with B097, so
	 * every {@code bank-*.json} written before it reads back as 0. A REMEMBERED bank - the login-screen and Grand
	 * Exchange case of D7 - therefore shows no coins until the player next opens a bank, which is the honest
	 * answer, because the stored file does not know how many there were.
	 */
	@Test
	public void aFileWrittenBeforeCurrencyExistedReadsZeroCurrency()
	{
		final String json = "{\"items\":[{\"id\":4151,\"quantity\":2,\"name\":\"Abyssal whip\",\"stackable\":false}],"
			+ "\"capturedAtMillis\":17,\"accountHash\":123,\"profileType\":\"STANDARD\"}";

		final BankSnapshot bank = new Gson().fromJson(json, BankSnapshot.class);
		bank.normalize();

		assertEquals("a field no old file has reads as 0", 0L, bank.currencyGp);
		assertEquals(1, bank.items.size());
	}

	/**
	 * B097: the worth of the coins and platinum tokens is part of the bank and survives the file, so a total drawn
	 * from a remembered bank counts the same cash the live one did.
	 */
	@Test
	public void currencyWorthRoundTripsThroughStockGson()
	{
		final Gson gson = new Gson();
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(
			new BankItem(4151, 2, "Abyssal whip", false))), 1_700_000_000_000L, 123L, "STANDARD", 200_000_000L);

		final BankSnapshot back = gson.fromJson(gson.toJson(bank), BankSnapshot.class);
		back.normalize();

		assertEquals(200_000_000L, back.currencyGp);
		assertEquals(bank, back);
	}

	/** A hand-edited or truncated file cannot make the bank worth less than nothing. */
	@Test
	public void normalizeClampsANegativeCurrencyToZero()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(), 1L, 9L, "STANDARD", -5L);

		bank.normalize();

		assertEquals(0L, bank.currencyGp);
	}

	/**
	 * The oldest shape imaginable: an item list and nothing else. normalize() has to make it usable.
	 */
	@Test
	public void jsonWithNothingButItemsParses()
	{
		final BankSnapshot bank = new Gson().fromJson("{\"items\":[{\"id\":2,\"quantity\":1}]}",
			BankSnapshot.class);
		bank.normalize();

		assertEquals(1, bank.items.size());
		assertEquals("", bank.items.get(0).name);
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, bank.profileType);
		assertEquals(0L, bank.capturedAtMillis);
	}

	@Test
	public void anEmptyJsonObjectParsesIntoAnEmptySnapshot()
	{
		final BankSnapshot bank = new Gson().fromJson("{}", BankSnapshot.class);
		bank.normalize();

		assertTrue(bank.isEmpty());
		assertEquals(BankSnapshot.DEFAULT_PROFILE_TYPE, bank.profileType);
	}

	// ---------------------------------------------------------------- values

	@Test
	public void theSnapshotAndItsItemsCompare()
	{
		final List<BankItem> items = new ArrayList<>();
		items.add(new BankItem(4151, 2, "Abyssal whip", false));

		assertEquals(new BankItem(4151, 2, "Abyssal whip", false), items.get(0));
		assertEquals(new BankItem(4151, 2, "Abyssal whip", false).hashCode(), items.get(0).hashCode());
		assertNotEquals(new BankItem(4151, 3, "Abyssal whip", false), items.get(0));
		assertNotEquals(new BankItem(4151, 2, "Abyssal whip", true), items.get(0));

		assertEquals(new BankSnapshot(items, 5L, 9L, "STANDARD"), new BankSnapshot(
			new ArrayList<>(items), 5L, 9L, "STANDARD"));
		assertNotEquals(new BankSnapshot(items, 5L, 9L, "STANDARD"),
			new BankSnapshot(items, 5L, 10L, "STANDARD"));
		assertNotEquals(new BankSnapshot(items, 5L, 9L, "STANDARD"),
			new BankSnapshot(items, 5L, 9L, "BETA"));
	}

	@Test
	public void anItemsNullNameNeverReachesTheAllArgsConstructor()
	{
		assertEquals("", new BankItem(4151, 1, null, false).name);
	}

	/**
	 * The content test the service skips a repeated disk write with (B008). {@code equals} cannot serve: it
	 * compares the capture clock, which {@code BankReader} stamps with {@code System.currentTimeMillis()} on every
	 * capture, so two readings of an unchanged bank are never equal - and the client posts one
	 * {@code ItemContainerChanged} per change to the container, many of them carrying identical stacks.
	 */
	@Test
	public void sameContentAsIgnoresTheCaptureClockAndNothingElse()
	{
		final List<BankItem> items = Arrays.asList(new BankItem(4151, 1, "Abyssal whip", false),
			new BankItem(385, 500, "Shark", true));
		final BankSnapshot first = new BankSnapshot(new ArrayList<>(items), 1_000L, 7L, "STANDARD");
		final BankSnapshot again = new BankSnapshot(new ArrayList<>(items), 9_999L, 7L, "STANDARD");

		assertTrue("the same stacks, captured later", first.sameContentAs(again));
		assertNotEquals("but not equal: a persisted snapshot must be told apart from a re-reading of it",
			first, again);

		final List<BankItem> more = new ArrayList<>(items);
		more.add(new BankItem(1042, 1, "Blue partyhat", false));
		assertFalse("a stack added", first.sameContentAs(new BankSnapshot(more, 1_000L, 7L, "STANDARD")));
		assertFalse("a quantity changed", first.sameContentAs(new BankSnapshot(
			Arrays.asList(new BankItem(4151, 1, "Abyssal whip", false), new BankItem(385, 499, "Shark", true)),
			1_000L, 7L, "STANDARD")));
		assertFalse("another account", first.sameContentAs(new BankSnapshot(new ArrayList<>(items), 1_000L, 8L, "STANDARD")));
		assertFalse("another profile", first.sameContentAs(new BankSnapshot(new ArrayList<>(items), 1_000L, 7L, "LEAGUE")));
		assertFalse("nothing at all", first.sameContentAs(null));
		assertTrue("and itself", first.sameContentAs(first));
	}

	// ---------------------------------------------------------------- addendum Y: the carried half

	private static final BankItem WHIP = new BankItem(4151, 1, "Abyssal whip", false);
	private static final BankItem SHARK = new BankItem(385, 3, "Shark", true);
	private static final BankItem HELM = new BankItem(1163, 1, "Rune full helm", false);

	private static BankReader.Carried carried()
	{
		return new BankReader.Carried(Arrays.asList(SHARK), Arrays.asList(HELM), 791_078L, 1_700_000_009_000L);
	}

	/** Y2: a fresh snapshot has an inventory and a worn list, not two nulls - the no-arg ctor is Gson's. */
	@Test
	public void aFreshSnapshotCarriesNothingRatherThanNull()
	{
		final BankSnapshot bank = new BankSnapshot();

		assertNotNull(bank.inventory);
		assertNotNull(bank.worn);
		assertTrue(bank.inventory.isEmpty());
		assertTrue(bank.worn.isEmpty());
		assertEquals(0L, bank.carriedGp);
		assertEquals(0L, bank.carriedAtMillis);
		assertTrue(BankSnapshot.EMPTY.inventory.isEmpty());
		assertTrue(BankSnapshot.EMPTY.worn.isEmpty());
	}

	/**
	 * Y2: {@code withCarried} replaces the carried part and touches nothing of the bank's - the stacks, the capture
	 * clock (the footnote's "bank HH:MM"), the cash and the account all stay exactly as they were, which is what
	 * lets Refresh republish the STORED bank beside a fresh inventory.
	 */
	@Test
	public void withCarriedReplacesTheCarriedPartAndLeavesTheBankAlone()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(WHIP)), 1_700_000_000_000L, 123L,
			"STANDARD", 500L);

		final BankSnapshot next = bank.withCarried(carried());

		assertEquals("the bank's own stacks are untouched", 1, next.items.size());
		assertEquals(1_700_000_000_000L, next.capturedAtMillis);
		assertEquals(123L, next.accountHash);
		assertEquals("STANDARD", next.profileType);
		assertEquals("the bank's own cash is untouched", 500L, next.currencyGp);
		assertEquals(Arrays.asList(SHARK), next.inventory);
		assertEquals(Arrays.asList(HELM), next.worn);
		assertEquals(791_078L, next.carriedGp);
		assertEquals("its own clock, not the bank's", 1_700_000_009_000L, next.carriedAtMillis);
		assertTrue("a copy, never a mutation: the snapshot the panel holds must not change under it",
			bank.inventory.isEmpty());
	}

	/** Y2: a read with no client logged in never happens, and a null still clears rather than throwing. */
	@Test
	public void withCarriedNullClearsTheCarriedPart()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(WHIP)), 1L, 123L, "STANDARD")
			.withCarried(carried());

		final BankSnapshot cleared = bank.withCarried(null);

		assertTrue(cleared.inventory.isEmpty());
		assertTrue(cleared.worn.isEmpty());
		assertEquals(0L, cleared.carriedGp);
		assertEquals(0L, cleared.carriedAtMillis);
		assertEquals("and the bank part is still there", 1, cleared.items.size());
	}

	/** Y2: the carried lists go through the same repair pass the bank's stacks do. */
	@Test
	public void normalizeCleansTheCarriedListsAndClampsTheirFigures()
	{
		final BankItem nameless = new BankItem();
		nameless.id = 4151;
		nameless.quantity = 1;
		nameless.name = null;
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(), 1L, 9L, "STANDARD", 0L,
			new ArrayList<>(Arrays.asList(nameless, null, new BankItem(2, 0, "a placeholder", true))),
			new ArrayList<>(Arrays.asList(new BankItem(-1, 5, "no id", false), HELM)), -5L, -9L);

		bank.normalize();

		assertEquals(1, bank.inventory.size());
		assertEquals("", bank.inventory.get(0).name);
		assertEquals(1, bank.worn.size());
		assertEquals(1163, bank.worn.get(0).id);
		assertEquals(0L, bank.carriedGp);
		assertEquals(0L, bank.carriedAtMillis);
	}

	/** Gson leaves an absent field null when it does not use the no-arg constructor; normalize answers for both. */
	@Test
	public void normalizeTurnsNullCarriedListsIntoEmptyOnes()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(), 1L, 9L, "STANDARD", 0L, null, null, 0L, 0L);

		bank.normalize();

		assertNotNull(bank.inventory);
		assertNotNull(bank.worn);
		assertTrue(bank.inventory.isEmpty());
		assertTrue(bank.worn.isEmpty());
	}

	/**
	 * Y2: the carried half is CONTENT. A Refresh that finds a different inventory has to be written, or the file
	 * would keep saying the old one and the next launch would draw a bank nobody has.
	 */
	@Test
	public void sameContentAsCoversTheCarriedHalf()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(WHIP)), 1_000L, 7L, "STANDARD");
		final BankSnapshot withCarried = bank.withCarried(carried());

		assertFalse("an inventory appeared", bank.sameContentAs(withCarried));
		assertFalse("and the other way round", withCarried.sameContentAs(bank));
		assertTrue("the same carried half, read again a moment later",
			withCarried.sameContentAs(bank.withCarried(new BankReader.Carried(Arrays.asList(SHARK),
				Arrays.asList(HELM), 791_078L, 1_700_000_099_000L))));
		assertFalse("one shark eaten", withCarried.sameContentAs(bank.withCarried(new BankReader.Carried(
			Arrays.asList(new BankItem(385, 2, "Shark", true)), Arrays.asList(HELM), 791_078L, 1L))));
		assertFalse("the helm taken off", withCarried.sameContentAs(bank.withCarried(new BankReader.Carried(
			Arrays.asList(SHARK), new ArrayList<>(), 791_078L, 1L))));
		assertFalse("coins spent", withCarried.sameContentAs(bank.withCarried(new BankReader.Carried(
			Arrays.asList(SHARK), Arrays.asList(HELM), 5L, 1L))));
	}

	/** Y2/Y5: the carried half survives the file, so the switch can be flipped without a bank visit. */
	@Test
	public void theCarriedHalfRoundTripsThroughStockGson()
	{
		final Gson gson = new Gson();
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(WHIP)), 1_700_000_000_000L, 123L,
			"STANDARD", 500L).withCarried(carried());

		final BankSnapshot back = gson.fromJson(gson.toJson(bank), BankSnapshot.class);
		back.normalize();

		assertEquals(Arrays.asList(SHARK), back.inventory);
		assertEquals(Arrays.asList(HELM), back.worn);
		assertEquals(791_078L, back.carriedGp);
		assertEquals(1_700_000_009_000L, back.carriedAtMillis);
		assertEquals(bank, back);
	}

	/**
	 * Back compat, the addendum-Y edition: a {@code bank-*.json} written before the four fields existed reads as
	 * "carrying nothing" and draws exactly the bank it always did.
	 */
	@Test
	public void aFileWrittenBeforeTheCarriedHalfExistedReadsAsCarryingNothing()
	{
		final String json = "{\"items\":[{\"id\":4151,\"quantity\":1,\"name\":\"Abyssal whip\",\"stackable\":false}],"
			+ "\"capturedAtMillis\":17,\"accountHash\":123,\"profileType\":\"STANDARD\",\"currencyGp\":500}";

		final BankSnapshot bank = new Gson().fromJson(json, BankSnapshot.class);
		bank.normalize();

		assertNotNull(bank.inventory);
		assertNotNull(bank.worn);
		assertTrue(bank.inventory.isEmpty());
		assertTrue(bank.worn.isEmpty());
		assertEquals(0L, bank.carriedGp);
		assertEquals(0L, bank.carriedAtMillis);
		assertEquals(500L, bank.currencyGp);
		assertEquals(1, bank.items.size());
	}

	/** A bank with no carried half prints exactly what it printed before addendum Y. */
	@Test
	public void toStringNamesTheCarriedHalfOnlyWhenThereIsOne()
	{
		final BankSnapshot bank = new BankSnapshot(new ArrayList<>(Arrays.asList(WHIP)), 17L, 123L, "STANDARD", 500L);

		assertEquals("BankSnapshot{items=1, capturedAtMillis=17, accountHash=123, profileType='STANDARD',"
			+ " currencyGp=500}", bank.toString());
		assertEquals("BankSnapshot{items=1, capturedAtMillis=17, accountHash=123, profileType='STANDARD',"
			+ " currencyGp=500, inventory=1, worn=1, carriedGp=791078}", bank.withCarried(carried()).toString());
	}
}
