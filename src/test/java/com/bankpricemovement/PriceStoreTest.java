package com.bankpricemovement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.RuneLite;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link PriceStore} on a real temp directory (contract C17-C19, rewritten by K11 and extended by addendum L
 * line L4): round trips of the four kinds of file - bank, mapping, revision index, baseline - the EMPTY answers
 * for a missing one, the quarantine of a corrupt one, the refusal to quarantine a merely unreadable one, the
 * atomic replace and its temp-file hygiene, the file naming that keeps two accounts, two profiles and the five
 * windows apart - and the addendum-K sweep.
 *
 * <p>The sweep is the test that matters most here. The plugin shipped once with movement measured off the
 * wiki's real-time TRADE buckets, and the first live look read "Green hat +1,413 gp (+12845.5 %)" against a GE
 * site figure of -38 gp. Those buckets are on the user's disk as {@code baseline-*.json}. If one survived the
 * switch, the panel would show that number again for the minutes between start-up and the first refetch - which
 * is the whole reason {@link PriceStore#deleteStaleFiles()} exists.
 *
 * <p>Real disk, no mocks and no sleeps: the store is synchronous by contract (the caller owns the executor), so
 * every assertion here is on what is on the file system when the call returns.
 */
public class PriceStoreTest
{
	/** 2026-09-07T19:55:12Z and its revision - the baseline the calibration run actually fetched. */
	private static final long REVISION_SECONDS = 1_788_810_912L;
	private static final long REV_ID = 15_333_448L;

	/** A stock Gson stands in for RuneLite's injected one; the documents are public-field only. */
	private final Gson gson = new Gson();

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private PriceStore store()
	{
		return new PriceStore(gson, tmp.getRoot());
	}

	// ---- file names and the directory (C17, K11)

	@Test
	public void defaultDirIsBankPriceMovementUnderTheRuneliteDir()
	{
		final File dir = PriceStore.defaultDir();
		assertEquals("the directory name is the hub slug (C17/D10)", "bank-portfolio-tracker", dir.getName());
		assertEquals("every file the plugin writes lives under ~/.runelite (hub rule C46)",
			RuneLite.RUNELITE_DIR, dir.getParentFile());
	}

	@Test
	public void theDirectoryIsCreatedLazilyOnTheFirstWriteAndNotBeforeIt()
	{
		final File missing = new File(tmp.getRoot(), "never-used");
		final PriceStore store = new PriceStore(gson, missing);

		assertSame("a load must not create anything", PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));
		assertSame(BankSnapshot.EMPTY, store.loadBank(1, "STANDARD"));
		assertTrue(store.loadMapping().value().isEmpty());
		assertEquals(0L, store.loadMapping().fetchedAtMillis());
		assertEquals("and neither must the sweep", 0, store.deleteStaleFiles());
		assertFalse("an installed but unused plugin leaves no directory behind", missing.exists());

		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));
		assertTrue("the first write creates the directory", missing.isDirectory());
		assertTrue(new File(missing, "baseline-D1.json").isFile());
	}

	@Test
	public void theFourKindsOfFileAreNamedByTheContract()
	{
		final PriceStore store = store();
		assertEquals("bank-1234-STANDARD.json", store.bankFile(1234L, "STANDARD").getName());
		assertEquals("mapping.json", store.mappingFile().getName());
		assertEquals("revindex.json", store.revisionIndexFile().getName());
		assertEquals("baseline-D1.json", store.bucketFile(MovementWindow.D1).getName());
		assertEquals("baseline-D7.json", store.bucketFile(MovementWindow.D7).getName());
		assertEquals("baseline-D30.json", store.bucketFile(MovementWindow.D30).getName());
		assertEquals("baseline-D90.json", store.bucketFile(MovementWindow.D90).getName());
		assertEquals("baseline-D180.json", store.bucketFile(MovementWindow.D180).getName());
		assertEquals("the store reads and writes the directory it was given", tmp.getRoot(), store.dir());
	}

	/** A separator in the profile type would write outside the store's directory. */
	@Test
	public void aProfileTypeIsSanitisedIntoTheFileNameAndBlankBecomesUnknown()
	{
		final PriceStore store = store();
		assertEquals("bank-7-a_b_c.json", store.bankFile(7L, "a/b\\c").getName());

		final File traversal = store.bankFile(7L, "../../etc");
		assertEquals("a traversal cannot walk out of the store's directory", tmp.getRoot(), traversal.getParentFile());
		assertFalse(traversal.getName().contains(".."));
		assertFalse(traversal.getName().contains("/"));
		assertFalse(traversal.getName().contains("\\"));

		assertEquals("bank-7-UNKNOWN.json", store.bankFile(7L, "").getName());
		assertEquals("bank-7-UNKNOWN.json", store.bankFile(7L, "   ").getName());
		assertEquals("bank-7-UNKNOWN.json", store.bankFile(7L, null).getName());
		assertEquals("a logged-out account hash is -1", "bank--1-STANDARD.json", store.bankFile(-1L, "STANDARD").getName());
	}

	// ---- banks (C18)

	@Test
	public void aBankRoundTripsThroughItsOwnFile()
	{
		final PriceStore store = store();
		final BankSnapshot saved = snapshot(42L, "STANDARD", 1_700_000_000_000L,
			item(4151, 1, "Abyssal whip", false),
			item(565, 25_000, "Blood rune", true));
		store.saveBank(saved);

		assertTrue(store.bankFile(42L, "STANDARD").isFile());
		final BankSnapshot loaded = store.loadBank(42L, "STANDARD");
		assertEquals(42L, loaded.accountHash);
		assertEquals("STANDARD", loaded.profileType);
		assertEquals(1_700_000_000_000L, loaded.capturedAtMillis);
		assertEquals(2, loaded.items.size());
		assertEquals(4151, loaded.items.get(0).id);
		assertEquals(1, loaded.items.get(0).quantity);
		assertEquals("Abyssal whip", loaded.items.get(0).name);
		assertEquals(25_000, loaded.items.get(1).quantity);
		assertTrue("stackable survives the round trip", loaded.items.get(1).stackable);
	}

	@Test
	public void aMissingBankReadsAsEmpty()
	{
		assertSame("a fresh install has no bank yet (D7 shows the 'open your bank once' card)",
			BankSnapshot.EMPTY, store().loadBank(42L, "STANDARD"));
	}

	@Test
	public void twoAccountsAndTwoProfilesDoNotCollide()
	{
		final PriceStore store = store();
		store.saveBank(snapshot(1L, "STANDARD", 1_000L, item(4151, 1, "Whip", false)));
		store.saveBank(snapshot(2L, "STANDARD", 2_000L, item(11802, 1, "Armadyl godsword", false)));
		store.saveBank(snapshot(1L, "DEADMAN", 3_000L, item(1038, 1, "Red partyhat", false)));

		assertEquals(1_000L, store.loadBank(1L, "STANDARD").capturedAtMillis);
		assertEquals(2_000L, store.loadBank(2L, "STANDARD").capturedAtMillis);
		assertEquals("a League or Deadman bank is a different bank (D7)", 3_000L, store.loadBank(1L, "DEADMAN").capturedAtMillis);
		assertEquals("Whip", store.loadBank(1L, "STANDARD").items.get(0).name);
		assertEquals("Red partyhat", store.loadBank(1L, "DEADMAN").items.get(0).name);
	}

	/** The snapshot names its own file, so a save and the load that follows cannot disagree about the key. */
	@Test
	public void aSavedBankIsFoundUnderTheKeyTheSnapshotCarries()
	{
		final PriceStore store = store();
		store.saveBank(snapshot(99L, "BETA", 4_000L, item(4151, 1, "Whip", false)));
		assertTrue(new File(tmp.getRoot(), "bank-99-BETA.json").isFile());
		assertEquals(4_000L, store.loadBank(99L, "BETA").capturedAtMillis);
	}

	@Test
	public void savingANullBankDoesNothing()
	{
		final PriceStore store = store();
		store.saveBank(null);
		assertEquals("no file, no exception", 0, names().size());
	}

	/**
	 * A bank belongs to an ACCOUNT. 0 is this plugin's "nobody is logged in" and -1 is the client's own "has
	 * not logged in yet" ({@code com/jagex/oldscape/pub/OAuthApi.java:30-32}), so a snapshot carrying either
	 * would be filed as {@code bank-0-STANDARD.json} or {@code bank--1-STANDARD.json} - a file no login ever
	 * loads ({@link PriceStore#loadBank} is always called with a live hash) and no sweep ever removes.
	 */
	@Test
	public void aBankWithNoAccountBehindItIsNeverWritten()
	{
		final PriceStore store = store();
		store.saveBank(snapshot(0L, "STANDARD", 1_000L, item(4151, 1, "Whip", false)));
		store.saveBank(snapshot(-1L, "STANDARD", 2_000L, item(4151, 1, "Whip", false)));
		assertEquals("no owner, no file", 0, names().size());

		// One with an owner still writes, so the guard is the hash and nothing else.
		store.saveBank(snapshot(7L, "STANDARD", 3_000L, item(4151, 1, "Whip", false)));
		assertEquals(Arrays.asList("bank-7-STANDARD.json"), names());
	}

	/**
	 * The store normalises whatever it read: the file may come from an older build or a hand edit, and every
	 * caller downstream assumes a clean item list (C5).
	 */
	@Test
	public void aLoadedBankIsNormalised() throws IOException
	{
		final PriceStore store = store();
		write(store.bankFile(5L, "STANDARD"),
			"{\"items\":[null,{\"id\":4151,\"quantity\":1,\"name\":\"Abyssal whip\",\"stackable\":false}],"
				+ "\"capturedAtMillis\":10,\"accountHash\":5,\"profileType\":\"STANDARD\"}");

		final BankSnapshot loaded = store.loadBank(5L, "STANDARD");
		assertEquals("normalize() drops the null entry (C5), so no caller has to null-check", 1, loaded.items.size());
		assertEquals(4151, loaded.items.get(0).id);
	}

	/** A file written before a field existed must still parse - Gson leaves the missing field at its default. */
	@Test
	public void aBankFileFromAnOlderBuildStillParses() throws IOException
	{
		final PriceStore store = store();
		write(store.bankFile(5L, "STANDARD"),
			"{\"items\":[{\"id\":4151,\"quantity\":1,\"name\":\"Abyssal whip\"}],"
				+ "\"capturedAtMillis\":10,\"accountHash\":5,\"profileType\":\"STANDARD\",\"somethingNew\":7}");

		final BankSnapshot loaded = store.loadBank(5L, "STANDARD");
		assertEquals(1, loaded.items.size());
		assertEquals("Abyssal whip", loaded.items.get(0).name);
		assertFalse("a missing boolean defaults to false, not a parse failure", loaded.items.get(0).stackable);
	}

	/**
	 * Y2: the carried half rides in the same file, so "Include inventory and worn gear" can be flipped on the login
	 * screen - or after a relaunch - without a bank visit, exactly as the cash of B097 and the untradeable marks of
	 * Q5 can. The store needed no change for it: the snapshot is one Gson document.
	 */
	@Test
	public void aBanksCarriedHalfRoundTripsWithIt()
	{
		final PriceStore store = store();
		final BankSnapshot saved = snapshot(42L, "STANDARD", 1_700_000_000_000L,
			item(4151, 1, "Abyssal whip", false))
			.withCarried(new BankReader.Carried(
				Arrays.asList(item(385, 3, "Shark", true)),
				Arrays.asList(item(1163, 1, "Rune full helm", false)), 791_078L, 1_700_000_009_000L));
		store.saveBank(saved);

		final BankSnapshot loaded = store.loadBank(42L, "STANDARD");
		assertEquals(1, loaded.items.size());
		assertEquals(1, loaded.inventory.size());
		assertEquals(385, loaded.inventory.get(0).id);
		assertEquals(3, loaded.inventory.get(0).quantity);
		assertEquals(1, loaded.worn.size());
		assertEquals("Rune full helm", loaded.worn.get(0).name);
		assertEquals(791_078L, loaded.carriedGp);
		assertEquals("its own clock, beside the bank's", 1_700_000_009_000L, loaded.carriedAtMillis);
		assertEquals("the bank's own capture time is untouched", 1_700_000_000_000L, loaded.capturedAtMillis);
	}

	/** And a file written before addendum Y loads as a bank with nothing carried, not as a failure. */
	@Test
	public void aBankFileWrittenBeforeTheCarriedHalfLoadsAsCarryingNothing() throws IOException
	{
		final PriceStore store = store();
		write(store.bankFile(5L, "STANDARD"),
			"{\"items\":[{\"id\":4151,\"quantity\":1,\"name\":\"Abyssal whip\",\"stackable\":false}],"
				+ "\"capturedAtMillis\":10,\"accountHash\":5,\"profileType\":\"STANDARD\",\"currencyGp\":500}");

		final BankSnapshot loaded = store.loadBank(5L, "STANDARD");
		assertNotNull("normalize() answers for the two lists no old file has", loaded.inventory);
		assertNotNull(loaded.worn);
		assertTrue(loaded.inventory.isEmpty());
		assertTrue(loaded.worn.isEmpty());
		assertEquals(0L, loaded.carriedGp);
		assertEquals(0L, loaded.carriedAtMillis);
		assertEquals(500L, loaded.currencyGp);
		assertEquals(1, loaded.items.size());
	}

	// ---- baselines (C18, K11)

	@Test
	public void aBaselineRoundTripsWithItsRevisionAndItsPublicationTime()
	{
		final PriceStore store = store();
		final Map<Integer, PricePoint> points = new LinkedHashMap<>();
		points.put(658, new PricePoint(1124L, 1124L));
		points.put(4151, new PricePoint(807_253L, 807_253L));
		points.put(2, new PricePoint(null, null));
		store.saveBucket(MovementWindow.D1, new PriceMap(points, 1_788_909_600_123L, REVISION_SECONDS, REV_ID));

		final PriceMap loaded = store.loadBucket(MovementWindow.D1);
		assertEquals(3, loaded.size());
		assertEquals("the revision id must survive disk - it is what marks the file as guide-era (K11)",
			REV_ID, loaded.revId());
		assertEquals("and its publication time, which the status line prints (K7)",
			REVISION_SECONDS, loaded.bucketSeconds());
		assertEquals(1_788_909_600_123L, loaded.fetchedAtMillis());
		assertEquals(Long.valueOf(1124L), loaded.get(658).mid());
		assertEquals(Long.valueOf(807_253L), loaded.get(4151).mid());
		assertNull("an item the guide table does not list has no price", loaded.get(2).mid());
		assertNull("an item that was never in the map is still absent", loaded.get(9_999_999));
	}

	@Test
	public void aMissingBaselineReadsAsEmpty()
	{
		assertSame(PriceMap.EMPTY, store().loadBucket(MovementWindow.D30));
	}

	@Test
	public void eachOfTheFiveWindowsKeepsItsOwnBaselineFile()
	{
		final PriceStore store = store();
		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));
		store.saveBucket(MovementWindow.D7, guideMap(658, 1100L));
		store.saveBucket(MovementWindow.D30, guideMap(658, 1000L));
		store.saveBucket(MovementWindow.D90, guideMap(658, 900L));
		store.saveBucket(MovementWindow.D180, guideMap(658, 800L));

		assertEquals(Long.valueOf(1124L), store.loadBucket(MovementWindow.D1).get(658).mid());
		assertEquals(Long.valueOf(1100L), store.loadBucket(MovementWindow.D7).get(658).mid());
		assertEquals(Long.valueOf(1000L), store.loadBucket(MovementWindow.D30).get(658).mid());
		assertEquals(Long.valueOf(900L), store.loadBucket(MovementWindow.D90).get(658).mid());
		assertEquals(Long.valueOf(800L), store.loadBucket(MovementWindow.D180).get(658).mid());
		assertEquals("switching windows must never have to re-fetch a baseline it already holds",
			5, tmp.getRoot().list().length);
	}

	@Test
	public void savingANullBaselineDoesNothing()
	{
		final PriceStore store = store();
		store.saveBucket(MovementWindow.D1, null);
		assertSame(PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));
		assertEquals(0, names().size());
	}

	@Test
	public void anEmptyBaselineRoundTripsAsAnEmptyMap()
	{
		final PriceStore store = store();
		store.saveBucket(MovementWindow.D7, PriceMap.EMPTY);
		final PriceMap loaded = store.loadBucket(MovementWindow.D7);
		assertTrue(loaded.isEmpty());
		assertEquals(0, loaded.size());
	}

	// ---- the id to wiki-name mapping (K11)

	@Test
	public void theMappingRoundTripsWithItsFetchStamp()
	{
		final PriceStore store = store();
		final Map<Integer, String> names = new LinkedHashMap<>();
		names.put(4151, "Abyssal whip");
		names.put(658, "Green hat");
		names.put(8007, "Varrock teleport (tablet)");
		store.saveMapping(names, 1_788_909_600_123L);

		assertTrue(store.mappingFile().isFile());
		final PriceStore.Stamped<Map<Integer, String>> read = store.loadMapping();
		final Map<Integer, String> loaded = read.value();
		assertEquals(3, loaded.size());
		assertEquals("Abyssal whip", loaded.get(4151));
		assertEquals("the wiki spelling, not the bank's: 8007's composition name \"Varrock teleport\" is not a "
				+ "guide-table key while this is (checked live 2026-09-08)",
			"Varrock teleport (tablet)", loaded.get(8007));
		assertEquals("the stamp rides back with the names, from the one parse that read them (K5)",
			1_788_909_600_123L, read.fetchedAtMillis());
	}

	@Test
	public void aMissingMappingReadsAsAnEmptyMapAndAZeroStamp()
	{
		final PriceStore store = store();
		assertTrue(store.loadMapping().value().isEmpty());
		assertEquals("a zero stamp is what makes the service fetch one", 0L, store.loadMapping().fetchedAtMillis());
	}

	/**
	 * Writing an empty table would stamp it as freshly fetched and the weekly cadence would then leave every
	 * bank item unpriced for a week. Both this and {@code GuidePriceClient.parseMapping} refuse it.
	 */
	@Test
	public void aNullOrEmptyMappingIsNeverWritten()
	{
		final PriceStore store = store();
		store.saveMapping(null, 5_000L);
		store.saveMapping(new LinkedHashMap<>(), 5_000L);

		assertFalse(store.mappingFile().exists());
		assertEquals(0L, store.loadMapping().fetchedAtMillis());
		assertEquals(0, names().size());
	}

	@Test
	public void theMappingIsHandedOutUnmodifiableAndSkipsDamagedEntries() throws IOException
	{
		final PriceStore store = store();
		write(store.mappingFile(), "{\"fetchedAtMillis\":7,\"names\":{\"4151\":\"Abyssal whip\","
			+ "\"not-an-id\":\"Nope\",\"658\":null,\"8007\":\"\"}}");

		final PriceStore.Stamped<Map<Integer, String>> read = store.loadMapping();
		final Map<Integer, String> loaded = read.value();
		assertEquals("a damaged cache file must never stop the panel opening (D9)", 1, loaded.size());
		assertEquals("Abyssal whip", loaded.get(4151));
		assertEquals(7L, read.fetchedAtMillis());

		try
		{
			loaded.put(1, "no");
			fail("loadMapping must hand back an unmodifiable map");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	@Test
	public void aCorruptMappingIsQuarantinedAndReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();
		write(store.mappingFile(), "{ not json");

		assertTrue(store.loadMapping().value().isEmpty());
		assertFalse(store.mappingFile().exists());
		assertEquals(1, namesContaining("mapping.json.corrupt-").size());
	}

	@Test
	public void aMappingWithNoNamesObjectAtAllStillReads() throws IOException
	{
		final PriceStore store = store();
		write(store.mappingFile(), "{\"fetchedAtMillis\":9}");

		final PriceStore.Stamped<Map<Integer, String>> read = store.loadMapping();
		assertTrue(read.value().isEmpty());
		assertEquals("the stamp still reads, so the service knows how old the nothing is",
			9L, read.fetchedAtMillis());
	}

	// ---- the guide page's revision history (L4)

	@Test
	public void theRevisionIndexRoundTripsWithItsFetchStamp()
	{
		final PriceStore store = store();
		store.saveRevisionIndex(history(), 1_788_909_600_123L);

		assertTrue(store.revisionIndexFile().isFile());

		final PriceStore.Stamped<List<RevisionRef>> read = store.loadRevisionIndex();
		final List<RevisionRef> loaded = read.value();
		assertEquals(4, loaded.size());
		assertEquals("newest first, the order rvdir=older answers in", 15_334_656L, loaded.get(0).revId());
		assertEquals(REV_ID, loaded.get(1).revId());
		assertEquals(REVISION_SECONDS, loaded.get(1).editSeconds());
		assertEquals("Gaz GEBot", loaded.get(1).user());
		assertTrue(loaded.get(1).isBot());
		assertFalse("who saved a revision is what tells the bot's daily run from a human edit (L-E)",
			loaded.get(3).isBot());
		assertEquals("Riblet15", loaded.get(3).user());
		assertEquals("new items with initial ge prices", loaded.get(3).comment());
		assertEquals("the stamp rides back with the revisions, from the one parse that read them (L4)",
			1_788_909_600_123L, read.fetchedAtMillis());
	}

	@Test
	public void aMissingRevisionIndexReadsAsAnEmptyListAndAZeroStamp()
	{
		final PriceStore store = store();

		assertTrue(store.loadRevisionIndex().value().isEmpty());
		assertEquals("a zero stamp is what makes the service fetch one", 0L,
			store.loadRevisionIndex().fetchedAtMillis());
	}

	/**
	 * The point of persisting the index at all: a relaunch has to be able to choose a baseline before any
	 * request comes back, and choose the SAME one the live index would have.
	 */
	@Test
	public void aReloadedIndexPicksTheSameBaselineAsTheLiveOne()
	{
		final PriceStore store = store();
		store.saveRevisionIndex(history(), 1L);

		final RevisionRef live = RevisionRef.pickThen(history(), LocalDate.of(2026, 9, 3));
		final RevisionRef reloaded = RevisionRef.pickThen(store.loadRevisionIndex().value(), LocalDate.of(2026, 9, 3));

		assertNotNull(reloaded);
		assertEquals("the bot's run of 2026-09-03, not Riblet15's edit of the same date", 15_330_300L,
			reloaded.revId());
		assertEquals(live, reloaded);
	}

	/**
	 * Writing an empty index would stamp it as freshly fetched, and the six-hourly cadence would then leave
	 * every window without a baseline for six hours. Both this and
	 * {@code GuidePriceClient.parseRevisionIndex} refuse it.
	 */
	@Test
	public void aNullOrEmptyRevisionIndexIsNeverWritten()
	{
		final PriceStore store = store();
		store.saveRevisionIndex(null, 5_000L);
		store.saveRevisionIndex(new ArrayList<>(), 5_000L);
		store.saveRevisionIndex(Arrays.asList((RevisionRef) null), 5_000L);

		assertFalse(store.revisionIndexFile().exists());
		assertEquals(0L, store.loadRevisionIndex().fetchedAtMillis());
		assertEquals(0, names().size());
	}

	@Test
	public void aDamagedIndexEntryIsSkippedAndTheRestStillSorts() throws IOException
	{
		final PriceStore store = store();
		write(store.revisionIndexFile(), "{\"fetchedAtMillis\":7,\"revisions\":["
			+ "{\"revId\":15332677,\"editSeconds\":1788722112,\"user\":\"Gaz GEBot\",\"comment\":\"GE update\"},"
			+ "{\"revId\":0,\"editSeconds\":1788722112},"
			+ "{\"revId\":15333448,\"editSeconds\":0},"
			+ "null,"
			+ "{\"revId\":15334656,\"editSeconds\":1788859512}]}");

		final PriceStore.Stamped<List<RevisionRef>> read = store.loadRevisionIndex();
		final List<RevisionRef> loaded = read.value();
		assertEquals("an entry with no id or no edit time carries no calendar day", 2, loaded.size());
		assertEquals("and what is left still comes back newest first", 15_334_656L, loaded.get(0).revId());
		assertEquals("a missing user reads as \"\" rather than breaking isBot()", "", loaded.get(0).user());
		assertEquals(7L, read.fetchedAtMillis());

		try
		{
			loaded.add(null);
			fail("loadRevisionIndex must hand back an unmodifiable list");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	@Test
	public void aCorruptRevisionIndexIsQuarantinedAndReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();
		write(store.revisionIndexFile(), "{ not json");

		assertTrue(store.loadRevisionIndex().value().isEmpty());
		assertFalse(store.revisionIndexFile().exists());
		assertEquals(1, namesContaining("revindex.json.corrupt-").size());
	}

	/**
	 * B028, the other two persisted shapes. Nothing REFUSES a mapping or an index over its schema - neither has
	 * ever changed meaning, and refusing the mapping would cost a 127 KB refetch against a weekly cadence - so
	 * what is pinned here is only that the marker is written, and written now: a build that adds the field at the
	 * moment a meaning changes cannot tell a file written before that change from one written after it, which is
	 * the whole reason the number goes in before v1. An old file's missing key still reads as 0 and still loads.
	 */
	@Test
	public void theMappingAndTheRevisionIndexCarryTheShapeThatWroteThem() throws IOException
	{
		final PriceStore store = store();
		store.saveMapping(Collections.singletonMap(658, "Green hat"), 5L);
		store.saveRevisionIndex(history(), 7L);

		assertTrue("the mapping names its shape: " + read(store.mappingFile()),
			read(store.mappingFile()).contains("\"schema\":" + PriceStore.MappingDto.SCHEMA));
		assertTrue("so does the index: " + read(store.revisionIndexFile()),
			read(store.revisionIndexFile()).contains("\"schema\":" + PriceStore.RevIndexDto.SCHEMA));

		// A file written before the field existed: absent reads as 0, and the document still loads whole.
		write(store.mappingFile(), "{\"fetchedAtMillis\":9,\"names\":{\"658\":\"Green hat\"}}");
		assertEquals(0, gson.fromJson(read(store.mappingFile()), PriceStore.MappingDto.class).schema);
		assertEquals("Green hat", store.loadMapping().value().get(658));
		assertEquals(9L, store.loadMapping().fetchedAtMillis());
	}

	/** The sweep is for trade-era files; the index and the mapping are current and must survive it. */
	@Test
	public void theSweepLeavesTheRevisionIndexAndTheMappingAlone()
	{
		final PriceStore store = store();
		store.saveRevisionIndex(history(), 1L);
		store.saveMapping(Collections.singletonMap(658, "Green hat"), 1L);

		assertEquals(0, store.deleteStaleFiles());
		assertTrue(store.revisionIndexFile().isFile());
		assertTrue(store.mappingFile().isFile());
	}

	// ---- the addendum-K sweep (K11)

	@Test
	public void theSweepRemovesTheTradeEraFilesAndNothingElse() throws IOException
	{
		final PriceStore store = store();
		write(new File(tmp.getRoot(), PriceStore.LEGACY_LATEST_FILE), "{\"points\":{\"658\":[1848,999]}}");
		write(new File(tmp.getRoot(), "baseline-H1.json"), "{\"bucketSeconds\":1788822000,\"points\":{}}");
		write(new File(tmp.getRoot(), "baseline-H24.json"), "{\"bucketSeconds\":1788822000,\"points\":{}}");
		store.saveBank(snapshot(42L, "STANDARD", 1L, item(4151, 1, "Whip", false)));
		store.saveMapping(Collections.singletonMap(4151, "Abyssal whip"), 5L);
		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));

		assertEquals("prices-latest, baseline-H1 and baseline-H24", 3, store.deleteStaleFiles());

		assertFalse(new File(tmp.getRoot(), PriceStore.LEGACY_LATEST_FILE).exists());
		assertFalse("H1 is not the name of any window this build has", new File(tmp.getRoot(), "baseline-H1.json").exists());
		assertFalse(new File(tmp.getRoot(), "baseline-H24.json").exists());
		assertTrue("the remembered bank is untouched", store.bankFile(42L, "STANDARD").isFile());
		assertTrue("so is the mapping", store.mappingFile().isFile());
		assertEquals("and so is a guide baseline", Long.valueOf(1124L),
			store.loadBucket(MovementWindow.D1).get(658).mid());
	}

	/**
	 * {@code baseline-H24.json} must NOT be matched with {@code MovementWindow.parse}, which maps the legacy
	 * spelling "H24" onto D1 and would keep a trade file alive under a window it was never measured for.
	 */
	@Test
	public void theSweepMatchesWindowNamesExactlyRatherThanThroughTheLegacyParse() throws IOException
	{
		final PriceStore store = store();
		write(new File(tmp.getRoot(), "baseline-H24.json"), "{\"points\":{\"658\":[11,-1]}}");

		assertSame("parse() would answer D1 here - the sweep must not use it",
			MovementWindow.D1, MovementWindow.parse("H24"));
		assertEquals(1, store.deleteStaleFiles());
		assertFalse(new File(tmp.getRoot(), "baseline-H24.json").exists());
	}

	/**
	 * The one file that keeps its name across the switch: D7 is a window in both eras, so its CONTENT is the
	 * only thing that tells the two apart. A stored revId of 0 means the map holds trade averages - exactly what
	 * showed a 1,086 gp hat as +12845.5 % - and it goes.
	 */
	@Test
	public void aSevenDayBaselineFromTheTradeEraGoesWhileTheGuideOneStays() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D7),
			"{\"fetchedAtMillis\":1788909600123,\"bucketSeconds\":1788822000,\"points\":{\"658\":[11,-1]}}");

		assertEquals("no revId key means the trade era", 0L, store.loadBucket(MovementWindow.D7).revId());
		assertEquals(1, store.deleteStaleFiles());
		assertFalse(store.bucketFile(MovementWindow.D7).exists());

		store.saveBucket(MovementWindow.D7,
			new PriceMap(points(658, 1124L, 1124L), 1_788_909_600_123L, REVISION_SECONDS, REV_ID));
		assertEquals("a guide baseline names a revision, so it survives", 0, store.deleteStaleFiles());
		assertEquals(REV_ID, store.loadBucket(MovementWindow.D7).revId());
	}

	/**
	 * B028, rule 4. A baseline written by an older BUILD carries a revision id exactly like a current one, so
	 * rule 3 cannot see it - and yet its fields need not mean what this build reads them as (addendum K stored
	 * the revision's save time in {@code bucketSeconds} where L stores the table's own {@code %LAST_UPDATE%}).
	 * {@code PriceService.guideOrEmpty} refuses such a map on the way in; the sweep is what stops it being read
	 * and refused again on every launch until a fetch replaces it. The file the store itself just wrote must of
	 * course survive - if it did not, every launch would throw away the baselines the launch before had fetched.
	 */
	@Test
	public void aBaselineFromAnOlderSchemaGoesWhileTheOneThisBuildWroteStays() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D30),
			"{\"schema\":" + (PriceMapDto.SCHEMA - 1) + ",\"fetchedAtMillis\":1788909600123,"
				+ "\"bucketSeconds\":1788822000,\"revId\":15333448,\"points\":{\"658\":[1124,1124]}}");
		store.saveBucket(MovementWindow.D90, guideMap(658, 1124L));

		assertEquals("a revision id of its own is not enough - the shape has to be this build's",
			15_333_448L, store.loadBucket(MovementWindow.D30).revId());
		assertEquals(1, store.deleteStaleFiles());
		assertFalse(store.bucketFile(MovementWindow.D30).exists());
		assertEquals("and the file this build wrote is not swept with it",
			Long.valueOf(1124L), store.loadBucket(MovementWindow.D90).get(658).mid());
		assertEquals("the sweep is still idempotent", 0, store.deleteStaleFiles());
	}

	/**
	 * The sweep reads only the two header fields, so a baseline whose BODY it could never build must still be
	 * judged by them (B025: the whole {@link PriceMapDto} used to be materialised - 4,500 entries, five files -
	 * to read two numbers). A points map full of the wrong type is the cheapest way to prove the body is skipped
	 * rather than parsed.
	 */
	@Test
	public void theSweepJudgesABaselineByItsHeaderWithoutBuildingItsPriceMap() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D1),
			"{\"schema\":" + PriceMapDto.SCHEMA + ",\"revId\":15333448,\"points\":{\"658\":\"not a price at all\"}}");
		write(store.bucketFile(MovementWindow.D7),
			"{\"schema\":" + PriceMapDto.SCHEMA + ",\"revId\":0,\"points\":{\"658\":\"not a price at all\"}}");

		assertEquals("only the trade-era one goes, and its body was never the question", 1, store.deleteStaleFiles());
		assertTrue(store.bucketFile(MovementWindow.D1).exists());
		assertFalse(store.bucketFile(MovementWindow.D7).exists());
	}

	@Test
	public void aCorruptBaselineIsSweptRatherThanLeftToBeQuarantinedForever() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D30), "]]not json[[");

		assertEquals(1, store.deleteStaleFiles());
		assertFalse(store.bucketFile(MovementWindow.D30).exists());
		assertTrue("swept, not quarantined - it is about to be refetched anyway",
			namesContaining(".corrupt-").isEmpty());
	}

	/**
	 * The trade-era rule reads the file's {@code revId}, and "0" must mean "this file says 0", never "this file
	 * could not be read". A sharing violation while a second client replaces the same baseline, an antivirus
	 * lock or a permissions blip would otherwise delete a perfectly good 90 KB baseline and leave every window
	 * saying "No 30d history yet" until the next fetch landed - the opposite of what every other read in this
	 * class does with an unreadable file ({@code readJson}: logged, left exactly where it is). A directory in
	 * the file's place is the portable way to make a read fail.
	 */
	@Test
	public void anUnreadableBaselineIsKeptRatherThanMistakenForATradeEraOne() throws IOException
	{
		final PriceStore store = store();
		final File baseline = store.bucketFile(MovementWindow.D30);
		assertTrue(baseline.mkdirs());

		assertEquals("nothing was deleted", 0, store.deleteStaleFiles());
		assertTrue("an unreadable file says nothing about its content", baseline.isDirectory());

		// ...while a file that really does read as revId 0 still goes, so the rule itself is intact.
		write(store.bucketFile(MovementWindow.D7), "{\"points\":{\"658\":[11,-1]}}");
		assertEquals(1, store.deleteStaleFiles());
		assertFalse(store.bucketFile(MovementWindow.D7).exists());
		assertTrue(baseline.isDirectory());
	}

	@Test
	public void theSweepLeavesQuarantinedBytesAndTemporariesAlone() throws IOException
	{
		final PriceStore store = store();
		write(new File(tmp.getRoot(), "baseline-D7.json.corrupt-123"), "kept for a bug report");
		write(new File(tmp.getRoot(), "baseline-D7.json.999.tmp"), "an interrupted write");

		assertEquals(0, store.deleteStaleFiles());
		assertEquals(2, names().size());
	}

	@Test
	public void theSweepIsSafeToRunTwiceAndOnAnEmptyDirectory()
	{
		final PriceStore store = store();
		assertEquals(0, store.deleteStaleFiles());
		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));
		assertEquals(0, store.deleteStaleFiles());
		assertEquals(0, store.deleteStaleFiles());
		assertEquals(1, names().size());
	}

	// ---- corrupt and unreadable files (C18)

	@Test
	public void aCorruptBankIsQuarantinedAndReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();
		final File file = store.bankFile(42L, "STANDARD");
		write(file, "{ this is not JSON");

		assertSame(BankSnapshot.EMPTY, store.loadBank(42L, "STANDARD"));
		assertFalse("the bad file is moved out of the way", file.exists());
		final List<String> quarantined = namesContaining(".corrupt-");
		assertEquals("exactly one backup, named after the file it came from", 1, quarantined.size());
		assertTrue(quarantined.get(0).startsWith("bank-42-STANDARD.json.corrupt-"));
		assertEquals("the bytes are kept for a bug report", "{ this is not JSON",
			new String(Files.readAllBytes(new File(tmp.getRoot(), quarantined.get(0)).toPath()), StandardCharsets.UTF_8));
	}

	@Test
	public void aBaselineOfTheWrongJsonShapeIsQuarantinedAndReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D1), "[1, 2, 3]");

		assertSame("an array where an object belongs is just as unusable", PriceMap.EMPTY,
			store.loadBucket(MovementWindow.D1));
		assertEquals(1, namesContaining("baseline-D1.json.corrupt-").size());
	}

	@Test
	public void anEmptyFileReadsAsEmptyAndIsNotQuarantined() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D1), "");

		assertSame(PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));
		assertTrue("an empty document is not corruption; the next save replaces it",
			store.bucketFile(MovementWindow.D1).isFile());
		assertTrue(namesContaining(".corrupt-").isEmpty());
	}

	/**
	 * A file that cannot be READ is left exactly where it is: quarantining it would throw the user's data away
	 * over a permissions blip. A directory in the file's place is the portable way to make a read fail.
	 */
	@Test
	public void anUnreadableBankReadsAsEmptyAndIsLeftAlone()
	{
		final PriceStore store = store();
		final File file = store.bankFile(42L, "STANDARD");
		assertTrue(file.mkdirs());

		assertSame(BankSnapshot.EMPTY, store.loadBank(42L, "STANDARD"));
		assertTrue("nothing was moved aside", file.isDirectory());
		assertTrue(namesContaining(".corrupt-").isEmpty());
	}

	@Test
	public void aCorruptFileIsReplacedWholeByTheNextSave() throws IOException
	{
		final PriceStore store = store();
		write(store.bucketFile(MovementWindow.D1), "not json at all");
		assertSame(PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));

		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));
		assertEquals(Long.valueOf(1124L), store.loadBucket(MovementWindow.D1).get(658).mid());
	}

	// ---- writeAtomic (C19)

	@Test
	public void writeAtomicReplacesTheTargetWholeAndInUtf8() throws IOException
	{
		// Unicode escapes, not literal accents: the assertion is about the STORE's encoding, and a literal
		// non-ASCII byte in this source file would drag the compiler's own encoding into the question.
		final String text = "{\"n\":\"caf\u00e9 \u00fcber\"}";
		final File target = new File(tmp.getRoot(), "doc.json");
		PriceStore.writeAtomic(target, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
		PriceStore.writeAtomic(target, text);

		assertEquals("the shorter write replaces the longer one whole", text,
			new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
		assertArrayEquals("UTF-8, not the platform encoding (C19)",
			text.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target.toPath()));
	}

	@Test
	public void writeAtomicLeavesNoTempFileBehind() throws IOException
	{
		final File target = new File(tmp.getRoot(), "doc.json");
		PriceStore.writeAtomic(target, "{}");

		assertEquals(Arrays.asList("doc.json"), names());
	}

	/**
	 * The temp file is opened {@code CREATE_NEW}, so a name another writer already holds is never filled: the
	 * loser takes a fresh name instead of truncating and interleaving into somebody else's half-written
	 * document. Two RuneLite clients on one machine are the case that matters - {@code System.nanoTime()} is
	 * {@code QueryPerformanceCounter} on Windows, whose origin is the MACHINE, so two processes can pick the
	 * same name in the same tick, and the file most at risk is the one with real user data
	 * ({@code bank-<hash>-<profile>.json} for the account both clients are logged into).
	 *
	 * <p>Driven through the package-private overload because a production temp name is a nanosecond count and
	 * cannot be predicted from outside; this is the only way to stage the collision at all.
	 */
	@Test
	public void writeAtomicNeverFillsATempFileAnotherWriterIsHolding() throws IOException
	{
		final File target = new File(tmp.getRoot(), "doc.json");
		final File taken = new File(tmp.getRoot(), "doc.json.taken.tmp");
		final String theirs = "the other client's half-written document";
		write(taken, theirs);
		final Path free = new File(tmp.getRoot(), "doc.json.free.tmp").toPath();

		// The first two attempts collide with the file that is already there; the third name is free.
		final List<Path> names = new ArrayList<>(Arrays.asList(taken.toPath(), taken.toPath(), free));
		PriceStore.writeAtomic(target, "{\"ours\":1}", () -> names.remove(0));

		assertEquals("{\"ours\":1}", new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
		assertEquals("their bytes are untouched - truncating them is the bug this guards",
			theirs, new String(Files.readAllBytes(taken.toPath()), StandardCharsets.UTF_8));
		assertFalse("our own temp moved onto the target", free.toFile().exists());

		// And a name that is taken every single time is reported rather than looped on forever.
		try
		{
			PriceStore.writeAtomic(target, "{\"ours\":2}", taken::toPath);
			fail("a permanently taken temp name must be an IOException, not a silent success");
		}
		catch (FileAlreadyExistsException expected)
		{
			assertEquals("the target keeps the document it had", "{\"ours\":1}",
				new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8));
		}
	}

	@Test
	public void everySaveLeavesNoTempFileBehind()
	{
		final PriceStore store = store();
		store.saveBank(snapshot(1L, "STANDARD", 1L, item(4151, 1, "Whip", false)));
		store.saveMapping(Collections.singletonMap(4151, "Abyssal whip"), 1L);
		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));

		assertTrue("a temp file is never left in place of, or beside, the document", namesContaining(".tmp").isEmpty());
		assertEquals(3, tmp.getRoot().list().length);
	}

	/** A failed write takes its own temp file away again, so a doomed target cannot silt up the directory. */
	@Test
	public void aFailedWriteAtomicThrowsAndCleansUpItsTempFile() throws IOException
	{
		final File blocked = new File(tmp.getRoot(), "blocked");
		assertTrue(blocked.mkdirs());
		write(new File(blocked, "child.txt"), "a non-empty directory cannot be replaced by a file");

		try
		{
			PriceStore.writeAtomic(blocked, "{}");
			fail("moving a file over a non-empty directory must fail");
		}
		catch (IOException expected)
		{
			// The point of the test.
		}
		assertTrue("the temp file is gone", namesContaining(".tmp").isEmpty());
		assertTrue("the target is untouched", blocked.isDirectory());
	}

	@Test
	public void aFailedSaveIsSwallowedSoTheCallerNeverSeesAnException()
	{
		final File blocked = new File(tmp.getRoot(), "blocked-dir");
		assertTrue(blocked.mkdirs());
		final PriceStore store = new PriceStore(gson, blocked);
		// A directory where the baseline file belongs: the write cannot land, and must not throw either - the
		// caller is an executor task, and prices are a convenience.
		assertTrue(new File(blocked, "baseline-D1.json").mkdirs());

		store.saveBucket(MovementWindow.D1, guideMap(658, 1124L));
		assertSame("the old (absent) map stands", PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));
	}

	@Test
	public void twoStoresOnTheSameDirectorySeeEachOthersWrites()
	{
		final PriceStore writer = store();
		final PriceStore reader = store();
		writer.saveBucket(MovementWindow.D1, guideMap(658, 1124L));

		assertEquals("nothing is cached in memory: every load reads the file (C18)",
			Long.valueOf(1124L), reader.loadBucket(MovementWindow.D1).get(658).mid());
		writer.saveBucket(MovementWindow.D1, guideMap(658, 1086L));
		assertEquals(Long.valueOf(1086L), reader.loadBucket(MovementWindow.D1).get(658).mid());
		assertNotEquals("the second write is seen, not a cached copy of the first",
			Long.valueOf(1124L), reader.loadBucket(MovementWindow.D1).get(658).mid());
	}

	// ---- fixtures

	/**
	 * Four real revisions of the guide page (calibration doc, and addendum L's L-E line): three bot runs and
	 * Riblet15's edit, which shares 2026-09-03 with one of them.
	 */
	private static List<RevisionRef> history()
	{
		return Arrays.asList(
			new RevisionRef(15_334_656L, 1_788_859_512L, "Gaz GEBot", "GE update"),
			new RevisionRef(REV_ID, REVISION_SECONDS, "Gaz GEBot", "GE update"),
			new RevisionRef(15_330_300L, 1_788_471_911L, "Gaz GEBot", "GE update"),
			new RevisionRef(15_329_323L, 1_788_417_960L, "Riblet15", "new items with initial ge prices"));
	}

	private static Map<Integer, PricePoint> points(final int id, final Long high, final Long low)
	{
		final Map<Integer, PricePoint> points = new LinkedHashMap<>();
		points.put(id, new PricePoint(high, low));
		return points;
	}

	/** A guide baseline: one number on both sides of the point, stamped with a real revision (K1, K11). */
	private static PriceMap guideMap(final int id, final long guidePrice)
	{
		return new PriceMap(points(id, guidePrice, guidePrice), 1_788_909_600_123L, REVISION_SECONDS, REV_ID);
	}

	private static BankItem item(final int id, final int quantity, final String name, final boolean stackable)
	{
		final BankItem item = new BankItem();
		item.id = id;
		item.quantity = quantity;
		item.name = name;
		item.stackable = stackable;
		return item;
	}

	private static BankSnapshot snapshot(final long accountHash, final String profileType, final long capturedAtMillis, final BankItem... items)
	{
		final BankSnapshot snapshot = new BankSnapshot();
		snapshot.items = new ArrayList<>(Arrays.asList(items));
		snapshot.capturedAtMillis = capturedAtMillis;
		snapshot.accountHash = accountHash;
		snapshot.profileType = profileType;
		return snapshot;
	}

	/** The bytes of one document, as text - for the assertions that are about what is ON DISK, not what loads. */
	private static String read(final File file) throws IOException
	{
		return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
	}

	private static void write(final File file, final String text) throws IOException
	{
		final File parent = file.getParentFile();
		assertTrue(parent.isDirectory() || parent.mkdirs());
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private List<String> names()
	{
		final String[] names = tmp.getRoot().list();
		assertNotNull(names);
		final List<String> list = new ArrayList<>(Arrays.asList(names));
		list.sort(String::compareTo);
		return list;
	}

	private List<String> namesContaining(final String fragment)
	{
		final List<String> hits = new ArrayList<>();
		for (String name : names())
		{
			if (name.contains(fragment))
			{
				hits.add(name);
			}
		}
		return hits;
	}

	// ---------------------------------------------------------------- T2: the two traded files

	/** 2026-09-11, the day the bucket files in this section are of. */
	private static final LocalDate SEP_11 = LocalDate.of(2026, 9, 11);
	/** The day before it - U2's one-day fallback, the day a 1d file can legitimately hold. */
	private static final LocalDate SEP_10 = LocalDate.of(2026, 9, 10);
	private static final int WHIP = 4151;
	private static final int SHARK = 385;

	private static Map<Integer, TradedPriceClient.Quote> quotes()
	{
		final Map<Integer, TradedPriceClient.Quote> map = new LinkedHashMap<>();
		map.put(WHIP, new TradedPriceClient.Quote(1_700_000L, 1_789_128_000L, 1_690_000L, 1_789_127_000L));
		// The API really serves one-sided quotes; the file must not turn the missing side into a zero.
		map.put(SHARK, new TradedPriceClient.Quote(1_000L, 1_789_120_000L, null, 0L));
		return map;
	}

	private static Map<Integer, TradedPriceClient.Bucket> buckets()
	{
		final Map<Integer, TradedPriceClient.Bucket> map = new LinkedHashMap<>();
		map.put(WHIP, new TradedPriceClient.Bucket(1_699_000L, 213L, 1_688_000L, 304L));
		map.put(SHARK, new TradedPriceClient.Bucket(null, 0L, 990L, 41_000L));
		return map;
	}

	@Test
	public void theTradedFilesAreNamedAsAddendumTSays()
	{
		final PriceStore store = store();

		assertEquals("traded-latest.json", PriceStore.TRADED_LATEST_FILE);
		assertEquals("traded-latest.json", store.tradedLatestFile().getName());
		assertEquals("traded-D1.json", store.tradedFile(MovementWindow.D1).getName());
		assertEquals("traded-D180.json", store.tradedFile(MovementWindow.D180).getName());
		assertEquals("one bucket file per window, beside its baseline", MovementWindow.values().length,
			new java.util.HashSet<>(Arrays.asList(
				store.tradedFile(MovementWindow.D1).getName(), store.tradedFile(MovementWindow.D7).getName(),
				store.tradedFile(MovementWindow.D30).getName(), store.tradedFile(MovementWindow.D90).getName(),
				store.tradedFile(MovementWindow.D180).getName())).size());
	}

	@Test
	public void aTradedLatestSnapshotRoundTripsWithBothSidesAndTheirTimes()
	{
		final PriceStore store = store();

		store.saveTradedLatest(quotes(), 1_789_128_500_000L);
		final PriceStore.Stamped<Map<Integer, TradedPriceClient.Quote>> read = store.loadTradedLatest();

		assertEquals(1_789_128_500_000L, read.fetchedAtMillis());
		assertEquals(2, read.value().size());
		final TradedPriceClient.Quote whip = read.value().get(WHIP);
		assertEquals(Long.valueOf(1_700_000L), whip.buy());
		assertEquals(Long.valueOf(1_690_000L), whip.sell());
		assertEquals(1_789_128_000L, whip.buySeconds());
		assertEquals(1_789_127_000L, whip.sellSeconds());
		final TradedPriceClient.Quote shark = read.value().get(SHARK);
		assertNull("a missing side survives the round trip as a missing side, never as 0", shark.sell());
		assertEquals(0L, shark.sellSeconds());
	}

	@Test
	public void aTradedDayRoundTripsWithItsDayAndItsVolumes()
	{
		final PriceStore store = store();

		store.saveTradedDay(MovementWindow.D1, SEP_11, buckets(), 1_789_128_500_000L);
		final PriceStore.TradedDay read = store.loadTradedDay(MovementWindow.D1);

		assertEquals(SEP_11, read.day());
		assertEquals(1_789_128_500_000L, read.fetchedAtMillis());
		assertFalse(read.isEmpty());
		assertEquals(517L, read.get(WHIP).volume());
		assertEquals(Long.valueOf(1_699_000L), read.get(WHIP).avgHigh());
		assertNull("a bucket with a volume and no price keeps both facts", read.get(SHARK).avgHigh());
		assertEquals(41_000L, read.get(SHARK).volume());
	}

	/**
	 * U2: the file records the day the bucket ACTUALLY holds, which is not always {@code liveDay - N} for that
	 * window - the wiki may not have closed the wanted day, and the fetch then falls one day further back. The day
	 * is therefore read from the document and never derived from the file's name, so a 1d file holding the day
	 * before yesterday says so and {@code PriceService} can refuse or date it accordingly.
	 */
	@Test
	public void aTradedDayRecordsTheDayItHoldsAndNotTheWindowsOwn()
	{
		final PriceStore store = store();

		store.saveTradedDay(MovementWindow.D1, SEP_10, buckets(), 1_789_128_500_000L);

		final PriceStore.TradedDay read = store.loadTradedDay(MovementWindow.D1);
		assertEquals("the day in the document, whatever window's file it is", SEP_10, read.day());
		assertEquals(517L, read.get(WHIP).volume());
	}

	/** The day is part of the document: a bucket whose day cannot be read can never be paired with a baseline. */
	@Test
	public void aTradedDayWithNoUsableDayReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();

		write(store.tradedFile(MovementWindow.D7), "{\"schema\":1,\"fetchedAtMillis\":5,\"buckets\":{}}");
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D7));

		write(store.tradedFile(MovementWindow.D7),
			"{\"schema\":1,\"fetchedAtMillis\":5,\"day\":\"not-a-date\",\"buckets\":{\"4151\":[1,2,3,4]}}");
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D7));
	}

	@Test
	public void missingTradedFilesReadAsEmptyRatherThanNull()
	{
		final PriceStore store = store();

		assertTrue(store.loadTradedLatest().value().isEmpty());
		assertEquals("and unstamped, which is what makes a fetch due", 0L, store.loadTradedLatest().fetchedAtMillis());
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D30));
		assertTrue(PriceStore.TradedDay.EMPTY.isEmpty());
		assertNull(PriceStore.TradedDay.EMPTY.day());
	}

	/**
	 * The same rule {@code saveMapping} and {@code saveRevisionIndex} obey, and for the same reason: an empty
	 * document written here would be stamped as freshly fetched, and the six-hour rule would then leave every row
	 * on the guide price for six hours with nothing due to be refetched.
	 */
	@Test
	public void anEmptyTradedDocumentIsNotWrittenAtAll()
	{
		final PriceStore store = store();

		store.saveTradedLatest(null, 1L);
		store.saveTradedLatest(Collections.<Integer, TradedPriceClient.Quote>emptyMap(), 1L);
		store.saveTradedDay(MovementWindow.D1, SEP_11, null, 1L);
		store.saveTradedDay(MovementWindow.D1, SEP_11, Collections.<Integer, TradedPriceClient.Bucket>emptyMap(), 1L);
		store.saveTradedDay(MovementWindow.D1, null, buckets(), 1L);

		assertTrue("nothing at all was written", names().isEmpty());
	}

	/**
	 * A traded document from an older build is refused on the way in, exactly as a below-schema baseline is: a
	 * live traded price is the one figure in this plugin a reader cannot check against anything else, so a
	 * document whose fields might mean something else is refetched rather than believed.
	 */
	@Test
	public void aTradedDocumentBelowThisBuildsSchemaIsRefused() throws IOException
	{
		final PriceStore store = store();

		write(store.tradedLatestFile(), "{\"fetchedAtMillis\":9,\"quotes\":{\"4151\":[10,1,9,1]}}");
		write(store.tradedFile(MovementWindow.D1),
			"{\"fetchedAtMillis\":9,\"day\":\"2026-09-11\",\"buckets\":{\"4151\":[10,5,9,5]}}");

		assertTrue("no schema marker means a build older than addendum T", store.loadTradedLatest().value().isEmpty());
		assertEquals("and its stamp is not believed either", 0L, store.loadTradedLatest().fetchedAtMillis());
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D1));
	}

	/** Rule 5 of the sweep: a traded file for a window this build does not have. */
	@Test
	public void theSweepRemovesATradedBucketForAWindowThatIsGone() throws IOException
	{
		final PriceStore store = store();
		write(new File(tmp.getRoot(), "traded-H24.json"), "{\"schema\":1,\"day\":\"2026-09-11\"}");
		store.saveTradedDay(MovementWindow.D1, SEP_11, buckets(), 1L);

		assertEquals(1, store.deleteStaleFiles());

		assertFalse(new File(tmp.getRoot(), "traded-H24.json").exists());
		assertTrue("a current window's bucket survives", store.tradedFile(MovementWindow.D1).isFile());
	}

	/** Rule 6: a traded file an older build wrote goes, so it is not read and refused on every launch. */
	@Test
	public void theSweepRemovesATradedFileFromAnOlderBuild() throws IOException
	{
		final PriceStore store = store();
		write(store.tradedLatestFile(), "{\"fetchedAtMillis\":9,\"quotes\":{}}");
		write(store.tradedFile(MovementWindow.D7), "{\"fetchedAtMillis\":9,\"day\":\"2026-09-11\"}");

		assertEquals(2, store.deleteStaleFiles());

		assertFalse(store.tradedLatestFile().exists());
		assertFalse(store.tradedFile(MovementWindow.D7).exists());
		assertEquals("and the sweep stays idempotent", 0, store.deleteStaleFiles());
	}

	/**
	 * {@value PriceStore#TRADED_LATEST_FILE} shares the {@code traded-} prefix and is NOT a window. Rule 5 must
	 * never see it, or every launch would sweep away the snapshot and pay for a fetch to get it back.
	 */
	@Test
	public void theSweepKeepsTheTradedLatestSnapshotItself()
	{
		final PriceStore store = store();
		store.saveTradedLatest(quotes(), 1L);

		assertEquals(0, store.deleteStaleFiles());

		assertTrue(store.tradedLatestFile().isFile());
		assertEquals(2, store.loadTradedLatest().value().size());
	}

	/** An unreadable traded file is KEPT, exactly as an unreadable baseline is: it says nothing about its content. */
	@Test
	public void anUnreadableTradedFileIsKeptRatherThanSwept()
	{
		final PriceStore store = store();
		final File bucket = store.tradedFile(MovementWindow.D90);
		assertTrue(bucket.mkdirs());

		assertEquals(0, store.deleteStaleFiles());
		assertTrue(bucket.isDirectory());
	}

	/** The traded files are written the same way every other document is: one whole document, atomically replaced. */
	@Test
	public void aTradedFileIsReplacedWholeAndLeavesNoTemporaryBehind()
	{
		final PriceStore store = store();

		store.saveTradedLatest(quotes(), 1L);
		store.saveTradedLatest(Collections.singletonMap(WHIP,
			new TradedPriceClient.Quote(2L, 3L, 1L, 3L)), 2L);

		assertEquals("one snapshot on disk, not two merged", 1, store.loadTradedLatest().value().size());
		assertEquals(2L, store.loadTradedLatest().fetchedAtMillis());
		assertTrue("no temp file survives a write", namesContaining(".tmp").isEmpty());
		assertEquals(Collections.singletonList("traded-latest.json"), names());
	}

	/** A corrupt traded file is quarantined and reads as empty - the C18 rule, applied to the new documents. */
	@Test
	public void aCorruptTradedFileIsQuarantinedAndReadsAsEmpty() throws IOException
	{
		final PriceStore store = store();
		write(store.tradedLatestFile(), "{\"schema\":1,");
		write(store.tradedFile(MovementWindow.D1), "{\"schema\":1,");

		assertTrue(store.loadTradedLatest().value().isEmpty());
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D1));

		assertEquals(1, namesContaining("traded-latest.json.corrupt-").size());
		assertEquals(1, namesContaining("traded-D1.json.corrupt-").size());
	}

	/** A damaged entry inside a file that parses costs that entry and nothing else (D9, fail soft). */
	@Test
	public void aTruncatedTradedEntryIsSkippedRatherThanFatal() throws IOException
	{
		final PriceStore store = store();
		write(store.tradedLatestFile(), "{\"schema\":1,\"fetchedAtMillis\":7,\"quotes\":{"
			+ "\"4151\":[10,1,9,1],\"385\":[5,1],\"nope\":[1,1,1,1]}}");
		write(store.tradedFile(MovementWindow.D1), "{\"schema\":1,\"fetchedAtMillis\":7,\"day\":\"2026-09-11\","
			+ "\"buckets\":{\"4151\":[10,5,9,5],\"385\":null}}");

		assertEquals(Collections.singleton(WHIP), store.loadTradedLatest().value().keySet());
		assertEquals(Collections.singleton(WHIP), store.loadTradedDay(MovementWindow.D1).buckets().keySet());
	}
}
