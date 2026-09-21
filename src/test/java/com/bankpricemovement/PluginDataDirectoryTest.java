package com.bankpricemovement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.runelite.client.RuneLite;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The one-time move of this plugin's data directory (addendum AD), proved over temporary folders.
 *
 * <p><b>Why this test exists.</b> Addendum AD put every byte of file I/O through RuneLite's {@link Filepath},
 * which a plugin can only obtain from {@code Plugin.getPluginDirectory()} - and that method does not answer
 * {@code ~/.runelite/bank-portfolio-tracker/}, the folder every build up to addendum AC wrote. It answers
 * {@code ~/.runelite/plugin-data/bank-portfolio-tracker/}, and it MOVES the old folder there the first time it
 * is called. So the next release relocates, on a stranger's machine, a remembered bank, five guide baselines,
 * the wiki name table, the revision history and six traded feeds - about 1.3 MB of files the user cannot get
 * back if the move loses them. Nothing in the plugin performs that move, so nothing in the plugin can be tested
 * for it; what CAN be tested is the algorithm RuneLite will run, against files this plugin's own store wrote,
 * and that is what follows.
 *
 * <p><b>Why the algorithm is copied rather than called.</b> {@code Plugin.getPluginDirectory()} is
 * {@code protected} and reads {@code RuneLite.PLUGIN_DATA} and {@code RuneLite.RUNELITE_DIR}, which are the
 * DEVELOPER's own {@code ~/.runelite}: calling it from a unit test would move the developer's real folder, once,
 * irreversibly, as a side effect of running the suite. {@link #pluginDirectory} is therefore that method's body
 * verbatim with those two constants replaced by a {@link TemporaryFolder}. It is pinned to the real thing from
 * two ends: {@link #theDescriptorNamesTheDirectoriesThisTestMoves()} reads the plugin's own
 * {@code @PluginDescriptor} for the two names, and every {@link Filepath} call below is the client's own class
 * off the client jar, not a stand-in.
 *
 * <p><b>And why {@code Filepath.Unchecked} appears here.</b> For {@code TestFilepaths}'s reason: a test has no
 * plugin instance and must make its own root. The Hub never sees {@code src/test}, and no class under
 * {@code src/main} names it.
 *
 * <p><b>Nothing here names a real account.</b> The export's {@code publish.py} copies this package -
 * {@code src/test} included - into the PUBLIC repository the Plugin Hub builds from, and a push to a public
 * repository cannot be undone. So the fixtures carry a made-up hash ({@link #ACCOUNT}), the one test that reads
 * the developer's own backup parses the hash out of the file name and never prints it, and every file name that
 * can reach a message or a printed name set goes through {@link #safeName} first ({@link #BANK_FILE}).
 *
 * <p>Real disk, no mocks: every assertion is on what is on the file system when the call returns.
 */
public class PluginDataDirectoryTest
{
	/** RuneLite's own sub-directory of {@code ~/.runelite} - {@code RuneLite.PLUGIN_DATA}'s last segment. */
	private static final String PLUGIN_DATA_DIR = "plugin-data";
	/**
	 * The read-only copy of the developer's real data directory, taken before the port (2026-09-20).
	 *
	 * <p>Located through {@link RuneLite#RUNELITE_DIR} rather than through {@code user.home}, and
	 * {@code RealDataTest} - which reads the same folder and used to spell it the other way - now does the same.
	 * That constant is the client's OWN definition of where its data lives: it honours the {@code runelite.home}
	 * property, so a developer who moves their client's folder still finds the backup, and it is the constant
	 * {@code Plugin.getPluginDirectory()} itself derives this plugin's directory from. Two tests reading one
	 * folder two different ways could disagree on a machine where the two are not the same place.
	 */
	private static final Path REAL_BACKUP = RuneLite.RUNELITE_DIR.toPath()
		.resolve("_bpm-backup-2026-09-20")
		.resolve(PriceStore.DIR_NAME);

	/**
	 * The account the fixtures below belong to. A MADE-UP hash, and deliberately one that reads as made up: the
	 * fixtures are hand-written, nothing here needs a real account, and this package's {@code src/test} is copied
	 * into the public repository the Plugin Hub builds from - so a real account hash written down here would be
	 * published with it. Only 0 and -1 are special to the code under test (a bank stamped with either is refused,
	 * C17); every other long is as good as this one.
	 */
	private static final long ACCOUNT = 1_111_222_233_334_444_555L;
	private static final String PROFILE = "STANDARD";

	/**
	 * The shape of a bank file, {@code bank-<accountHash>-<profileType>.json} (C17), and the placeholder it is
	 * printed as. The BACKUP's bank file is named for a real account, and a name set JUnit prints when two of them
	 * differ - or an "identical bytes: ..." message - would carry that hash into a log and, through the export,
	 * into a public repository. Every name that reaches a message here goes through {@link #safeName} first.
	 */
	private static final Pattern BANK_FILE = Pattern.compile(Pattern.quote(PriceStore.BANK_PREFIX)
		+ "[0-9]+-[A-Za-z0-9_-]+" + Pattern.quote(PriceStore.JSON_SUFFIX));
	private static final String REDACTED_BANK = PriceStore.BANK_PREFIX + "<account>" + PriceStore.JSON_SUFFIX;
	private static final int WHIP = 4151;
	private static final int PARTYHAT = 1038;
	private static final int BLOOD_RUNE = 565;
	/** A real guide revision and its edit time, so no seeded baseline can be mistaken for a trade-era one. */
	private static final long REV_ID = 15_344_511L;
	private static final long REVISION_SECONDS = 1_789_524_799L;
	/** The UTC day the traded buckets below count back from (U1) - the day the real backup's feeds carry. */
	private static final LocalDate LIVE_DAY = LocalDate.of(2026, 9, 17);
	/** How many files a complete data directory holds: a bank, five baselines, five buckets and three tables. */
	private static final int FULL_SET = 14;

	/** A stock Gson stands in for RuneLite's injected one; the documents are public-field only. */
	private final Gson gson = new Gson();

	/** Stands in for {@code ~/.runelite}. Nothing in this test may write anywhere else. */
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private Path runeliteDir;
	private Path pluginData;

	@Before
	public void setUp()
	{
		runeliteDir = tmp.getRoot().toPath();
		pluginData = runeliteDir.resolve(PLUGIN_DATA_DIR);
	}

	// ---- 0: the reproduction below moves the directories the shipped annotation names

	/**
	 * The two names {@link #pluginDirectory} is handed are the plugin's own, read off the annotation the client
	 * will read. Without this the rest of the file would be proving a move of somebody else's folder.
	 */
	@Test
	public void theDescriptorNamesTheDirectoriesThisTestMoves()
	{
		final PluginDescriptor descriptor = BankPriceMovementPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull("the plugin must carry a @PluginDescriptor at runtime", descriptor);
		assertEquals("getPluginDirectory() refuses a plugin with no internalName (addendum AD)",
			PriceStore.DIR_NAME, descriptor.internalName());
		assertEquals("and it moves the legacy folder of that name, once", PriceStore.DIR_NAME,
			descriptor.legacyDataDirectory());
		assertEquals("which is the hub slug, and the one spelling of it (D10)", "bank-portfolio-tracker",
			PriceStore.DIR_NAME);
	}

	// ---- 1: the happy path - an existing user's whole directory arrives intact

	@Test
	public void aWholeLegacyDirectoryIsMovedAcrossByteForByte() throws IOException
	{
		final File legacy = new File(tmp.getRoot(), PriceStore.DIR_NAME);
		seed(legacy, 1_789_688_282_748L, 1_206_828L);

		final Filepath legacyPath = TestFilepaths.rooted(legacy);
		final Map<String, byte[]> before = contents(legacyPath);
		assertEquals("a complete data directory is fourteen files", FULL_SET, before.size());

		final Filepath moved = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);

		assertEquals("the plugin's directory is now under plugin-data", pluginData.resolve(PriceStore.DIR_NAME),
			Paths.get(moved.toString()));
		assertTrue(moved.isDirectory());
		assertFalse("nothing is left behind in the old location - the move is a rename", legacy.exists());

		final Map<String, byte[]> after = contents(moved);
		assertEquals("every file arrives", safeNames(before.keySet()), safeNames(after.keySet()));
		for (final Map.Entry<String, byte[]> entry : before.entrySet())
		{
			assertArrayEquals("identical bytes: " + safeName(entry.getKey()), entry.getValue(),
				after.get(entry.getKey()));
		}

		// ...and the point of all of it: the store reads them where they now are.
		assertSeededStoreReadsEverything(new PriceStore(gson, moved), 1_789_688_282_748L, 1_206_828L);
	}

	/**
	 * The same move over the DEVELOPER's real files rather than the fixtures - 1.3 MB of documents written by
	 * earlier builds on a live account, which is the corpus every existing user's directory is made of. Skipped
	 * (not failed) where the backup is not on the machine, so the suite stays portable; it is a read-only copy
	 * taken before the port, and this test never writes to it.
	 */
	@Test
	public void theDevelopersRealDirectoryIsMovedAndStillReads() throws IOException
	{
		assumeTrue("no read-only backup on this machine: " + REAL_BACKUP, Files.isDirectory(REAL_BACKUP));

		final File legacy = new File(tmp.getRoot(), PriceStore.DIR_NAME);
		final Filepath legacyPath = TestFilepaths.rooted(legacy);
		legacyPath.createDirectories();
		final Filepath backup = Filepath.Unchecked.getRooted(REAL_BACKUP);
		final List<String> copied = names(backup);
		for (final String name : copied)
		{
			backup.join(name).copyTo(legacyPath.join(name));
		}
		assertEquals("the backup is a complete data directory", FULL_SET, copied.size());

		final Map<String, byte[]> before = contents(legacyPath);
		final Filepath moved = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);
		final Map<String, byte[]> after = contents(moved);

		assertFalse(legacy.exists());
		assertEquals(safeNames(before.keySet()), safeNames(after.keySet()));
		for (final Map.Entry<String, byte[]> entry : before.entrySet())
		{
			assertArrayEquals("identical bytes: " + safeName(entry.getKey()), entry.getValue(),
				after.get(entry.getKey()));
		}

		final PriceStore store = new PriceStore(gson, moved);

		// The bank names its own account, so the file name is parsed rather than assumed: a store that could not
		// find the moved bank under the key it carries would show "open your bank once" to a user who has. The
		// hash that comes out of it is used and never printed - see BANK_FILE.
		final String bankFile = onlyNameStartingWith(after.keySet(), PriceStore.BANK_PREFIX);
		final String key = bankFile.substring(PriceStore.BANK_PREFIX.length(),
			bankFile.length() - PriceStore.JSON_SUFFIX.length());
		final int split = key.lastIndexOf('-');
		final long hash = Long.parseLong(key.substring(0, split));
		final BankSnapshot bank = store.loadBank(hash, key.substring(split + 1));
		assertFalse("the real bank still reads after the move", bank.isEmpty());
		assertTrue("and it is keyed to the account its file names", hash == bank.accountHash);
		assertTrue("and it is a real bank, not a stub", bank.items.size() > 50);

		for (final MovementWindow window : MovementWindow.values())
		{
			final PriceMap baseline = store.loadBucket(window);
			assertFalse("baseline-" + window.name() + " still reads", baseline.isEmpty());
			assertTrue("a real guide baseline is thousands of items", baseline.size() > 1_000);
			assertNotEquals("and it carries its revision, so the sweep keeps it (K11 rule 3)", 0L, baseline.revId());

			final PriceStore.TradedDay traded = store.loadTradedDay(window);
			assertFalse("traded-" + window.name() + " still reads", traded.isEmpty());
			assertNotNull("a bucket names its own day (U1)", traded.day());
		}

		assertTrue("the wiki name table still reads", store.loadMapping().value().size() > 1_000);
		assertTrue(store.loadMapping().fetchedAtMillis() > 0L);
		assertTrue("the revision history still reads", store.loadRevisionIndex().value().size() > 10);
		assertTrue(store.loadRevisionIndex().fetchedAtMillis() > 0L);
		assertTrue("the traded snapshot still reads", store.loadTradedLatest().value().size() > 1_000);
		assertTrue(store.loadTradedLatest().fetchedAtMillis() > 0L);

		assertEquals("and startUp's sweep finds nothing stale in a directory that only moved", 0,
			store.deleteStaleFiles());
		assertEquals("so all fourteen survive the first launch after the move", FULL_SET, names(moved).size());
	}

	// ---- 2: idempotence - every launch after the first one

	@Test
	public void aSecondCallMovesNothingAndLosesNothing() throws IOException
	{
		final File legacy = new File(tmp.getRoot(), PriceStore.DIR_NAME);
		seed(legacy, 5_000L, 1_000L);

		final Filepath first = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);
		final Map<String, byte[]> afterFirst = contents(first);

		final Filepath second = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);
		final Filepath third = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);

		assertEquals("the same directory every time", first, second);
		assertEquals(first, third);
		assertFalse("and the legacy folder is not recreated", legacy.exists());

		final Map<String, byte[]> afterThird = contents(third);
		assertEquals(safeNames(afterFirst.keySet()), safeNames(afterThird.keySet()));
		for (final Map.Entry<String, byte[]> entry : afterFirst.entrySet())
		{
			assertArrayEquals("untouched by the second and third calls: " + safeName(entry.getKey()), entry.getValue(),
				afterThird.get(entry.getKey()));
		}
		assertSeededStoreReadsEverything(new PriceStore(gson, third), 5_000L, 1_000L);
	}

	// ---- 3: a fresh install - nobody has a legacy folder

	@Test
	public void aFreshInstallGetsAnEmptyDirectoryAndAWorkingStore() throws IOException
	{
		final Filepath dir = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);

		assertFalse("the directory is NAMED, not created - an installed but unused plugin leaves nothing behind",
			dir.exists());
		assertFalse("and no legacy folder is invented to move", new File(tmp.getRoot(), PriceStore.DIR_NAME).exists());

		final PriceStore store = new PriceStore(gson, dir);
		assertSame("a fresh install simply starts empty", BankSnapshot.EMPTY, store.loadBank(ACCOUNT, PROFILE));
		assertSame(PriceMap.EMPTY, store.loadBucket(MovementWindow.D1));
		assertSame(PriceStore.TradedDay.EMPTY, store.loadTradedDay(MovementWindow.D1));
		assertTrue(store.loadMapping().value().isEmpty());
		assertTrue(store.loadRevisionIndex().value().isEmpty());
		assertTrue(store.loadTradedLatest().value().isEmpty());
		assertEquals("and the sweep has nothing to sweep", 0, store.deleteStaleFiles());
		assertFalse("none of which created anything", dir.exists());

		// The first fetch creates it, and from there the plugin is the plugin.
		store.saveBank(bank(7_000L, 791_078L));
		assertTrue("the first write creates the plugin's own directory", dir.isDirectory());
		assertEquals(7_000L, store.loadBank(ACCOUNT, PROFILE).capturedAtMillis);
		assertEquals(791_078L, store.loadBank(ACCOUNT, PROFILE).currencyGp);
	}

	// ---- 4: both present - the destination wins and the legacy folder is left alone

	/**
	 * RuneLite's guard is {@code !fp.exists() && legacy.exists()}, so a directory that is already in plugin-data
	 * is never overwritten and never merged into. It is reachable in the wild: an older build of the plugin
	 * running beside a newer one writes the legacy folder again after the move. The newer location must win,
	 * because it is the one this build has been reading and writing.
	 */
	@Test
	public void whenBothExistTheDestinationSurvivesAndTheLegacyFolderIsUntouched() throws IOException
	{
		final File legacy = new File(tmp.getRoot(), PriceStore.DIR_NAME);
		final File destination = pluginData.resolve(PriceStore.DIR_NAME).toFile();
		seed(destination, 2_000L, 2_222L);
		seed(legacy, 1_000L, 1_111L);

		final Filepath destinationPath = TestFilepaths.rooted(destination);
		final Filepath legacyPath = TestFilepaths.rooted(legacy);
		final Map<String, byte[]> destinationBefore = contents(destinationPath);
		final Map<String, byte[]> legacyBefore = contents(legacyPath);

		final Filepath dir = pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME);
		assertEquals(destinationPath, dir);

		final Map<String, byte[]> destinationAfter = contents(dir);
		assertEquals(safeNames(destinationBefore.keySet()), safeNames(destinationAfter.keySet()));
		for (final Map.Entry<String, byte[]> entry : destinationBefore.entrySet())
		{
			assertArrayEquals("the destination is never overwritten: " + safeName(entry.getKey()), entry.getValue(),
				destinationAfter.get(entry.getKey()));
		}

		assertTrue("and the legacy folder is left exactly where it is", legacy.isDirectory());
		final Map<String, byte[]> legacyAfter = contents(legacyPath);
		assertEquals(safeNames(legacyBefore.keySet()), safeNames(legacyAfter.keySet()));
		for (final Map.Entry<String, byte[]> entry : legacyBefore.entrySet())
		{
			assertArrayEquals("nothing is taken out of it either: " + safeName(entry.getKey()), entry.getValue(),
				legacyAfter.get(entry.getKey()));
		}

		// The contents that survive are the destination's, down to the figures the panel would print.
		assertSeededStoreReadsEverything(new PriceStore(gson, dir), 2_000L, 2_222L);
		assertEquals("the legacy copy is still its own, and still readable by hand", 1_000L,
			new PriceStore(gson, legacyPath).loadBank(ACCOUNT, PROFILE).capturedAtMillis);
	}

	// ---- 5: the name is legal

	/**
	 * {@code getLegacyPluginDirectory} refuses a name that collides with one of the client's own directories, and
	 * it refuses by THROWING - out of {@code getPluginDirectory()}, on the first save, for as long as the
	 * descriptor says so. The slug is permanent on the Plugin Hub, so this is worth a test of its own rather than
	 * a reading of the list.
	 */
	@Test
	public void theLegacyDirectoryNameIsOneRuneliteAccepts()
	{
		final Filepath legacy = Filepath.Unchecked.getLegacyPluginDirectory(runeliteDir, PriceStore.DIR_NAME);
		assertEquals(PriceStore.DIR_NAME, legacy.getFileName());
		assertEquals(runeliteDir.resolve(PriceStore.DIR_NAME), Paths.get(legacy.toString()));
		assertTrue("the legacy folder is its own root, so nothing built from it can reach ~/.runelite",
			legacy.isRoot());

		// The same call with a name from the client's own list, to show the refusal is real and that this plugin
		// is only outside it by its name.
		for (final String reserved : Arrays.asList(PLUGIN_DATA_DIR, "cache", "logs", "profiles", "screenshots",
			"settings.properties"))
		{
			final String refusal = refused("a legacy directory called " + reserved,
				() -> Filepath.Unchecked.getLegacyPluginDirectory(runeliteDir, reserved));
			assertTrue("the refusal names the directory: " + refusal, refusal.contains(reserved));
		}

		// ...and the internalName must be a single legal segment, which is the other half of the same method: it
		// is joined to plugin-data with joinSegment, which refuses a separator, a device name and a traversal.
		final Filepath data = Filepath.Unchecked.getRooted(pluginData).joinSegment(PriceStore.DIR_NAME).rooted();
		assertEquals(PriceStore.DIR_NAME, data.getFileName());
		assertTrue(data.isRoot());
	}

	// ---- 6: the sandbox actually holds

	/**
	 * The property the Hub asked for: "All file i/o must go through the Filepath utility". A {@link Filepath}
	 * cannot NAME anything outside the root it was made from, so the store is unable - not merely unwilling - to
	 * write outside its own directory. Every path this plugin builds is {@code dir().join(name)}, so these are
	 * the refusals that make the rule true rather than aspirational.
	 */
	@Test
	public void theStoresDirectoryCannotBeUsedToReachAnythingOutsideIt() throws IOException
	{
		final File legacy = new File(tmp.getRoot(), PriceStore.DIR_NAME);
		seed(legacy, 3_000L, 3_333L);
		final PriceStore store = new PriceStore(gson, pluginDirectory(PriceStore.DIR_NAME, PriceStore.DIR_NAME));

		final Filepath dir = store.dir();
		assertNotNull(dir);
		assertTrue("the directory RuneLite hands a plugin is its OWN root - that is what makes .. impossible",
			dir.isRoot());

		assertTrue(refused("a traversal", () -> dir.join("../evil.json")).contains("Path escaped its root"));
		assertTrue(refused("the parent itself", () -> dir.join("..")).contains("Path escaped its root"));
		assertTrue(refused("getParent() on the root", dir::getParent).contains("Path escaped its root"));
		assertTrue("a deeper traversal is caught by the same rule",
			refused("a traversal through a child", () -> dir.join("baselines/../../evil.json"))
				.contains("Path escaped its root"));
		assertTrue("nor can an absolute path be pasted in",
			refused("an absolute path", () -> dir.join(tmp.getRoot().getAbsolutePath()))
				.contains("Cannot append an absolute path"));

		// A Windows device name is refused by NAME rather than by location: "nul.json" resolves inside the root,
		// and opening it would write to the null device instead of to a file.
		assertTrue(refused("a device name", () -> dir.join("nul.json")).contains("Windows reserved name"));
		assertTrue(refused("a device name with no extension", () -> dir.join("CON")).contains("Windows reserved name"));
		assertTrue("and a trailing dot, which Windows would strip",
			refused("a trailing dot", () -> dir.join("bank.")).contains("cannot end with"));

		// A SEPARATOR is refused by joinSegment, which is what names a directory; plain join treats it as a
		// sub-directory and contains it, which is the honest behaviour and still inside the sandbox. Nothing in
		// this plugin joins a separator either way - every file name is a constant plus a sanitised profile type.
		assertTrue(refused("a separator in a segment", () -> dir.joinSegment("sub/evil.json"))
			.contains("disallowed characters"));
		assertTrue(refused("a backslash in a segment", () -> dir.joinSegment("sub\\evil.json"))
			.contains("disallowed characters"));
		assertTrue("a separator passed to join stays under the root", dir.join("sub/evil.json").startsWith(dir));

		// The store's own front door: a profile type arrives as a String from the plugin, and a traversal in it is
		// sanitised into the file name before Filepath ever sees it (C17). Both locks, one door.
		final Filepath traversal = store.bankFile(7L, "../../etc");
		assertEquals("a traversal cannot walk out of the store's directory", dir, traversal.getParent());
		assertFalse(traversal.getFileName().contains(".."));
		assertEquals("bank-7-a_b_c.json", store.bankFile(7L, "a/b\\c").getFileName());

		// Nothing the refusals attempted reached the disk.
		assertFalse(new File(tmp.getRoot(), "evil.json").exists());
		assertFalse(new File(pluginData.toFile(), "evil.json").exists());
		assertEquals("and the directory still holds exactly what was moved into it", FULL_SET, names(dir).size());
	}

	// ---- the algorithm under test

	/**
	 * {@code Plugin.getPluginDirectory()} verbatim, with {@code RuneLite.PLUGIN_DATA} and
	 * {@code RuneLite.RUNELITE_DIR} replaced by this test's {@link TemporaryFolder}. The client's own body is:
	 *
	 * <pre>
	 * if (!Files.exists(RuneLite.PLUGIN_DATA)) { Files.createDirectories(RuneLite.PLUGIN_DATA); }
	 * var fp = Filepath.Unchecked.getRooted(RuneLite.PLUGIN_DATA).joinSegment(internalName).rooted();
	 * var legacyName = desc.legacyDataDirectory();
	 * if (!Strings.isNullOrEmpty(legacyName))
	 * {
	 *     var legacy = Filepath.Unchecked.getLegacyPluginDirectory(RuneLite.RUNELITE_DIR.toPath(), legacyName);
	 *     if (!fp.exists() &amp;&amp; legacy.exists()) { legacy.moveTo(fp); }
	 * }
	 * return fp;
	 * </pre>
	 *
	 * <p>{@code Files} rather than {@link Filepath} for the first two lines because that is what the client does,
	 * and a reproduction that tidied it up would be testing something else: {@code plugin-data} is the root every
	 * {@code Filepath} here is made FROM, and nothing can create its own root.
	 */
	private Filepath pluginDirectory(final String internalName, @Nullable final String legacyName) throws IOException
	{
		if (!Files.exists(pluginData))
		{
			Files.createDirectories(pluginData);
		}

		final Filepath fp = Filepath.Unchecked.getRooted(pluginData)
			.joinSegment(internalName)
			.rooted();

		if (legacyName != null && !legacyName.isEmpty())
		{
			final Filepath legacy = Filepath.Unchecked.getLegacyPluginDirectory(runeliteDir, legacyName);

			if (!fp.exists() && legacy.exists())
			{
				legacy.moveTo(fp);
			}
		}

		return fp;
	}

	// ---- fixtures

	/**
	 * Writes a complete data directory - one of every file the plugin ships - THROUGH THE STORE, so the corpus
	 * that gets moved is in the shipped format rather than a hand-typed approximation of it, and a change to any
	 * document's shape travels into this test for free.
	 *
	 * @param stamp the moment every document claims it was fetched; also the bank's capture time, which is how
	 *              two seeded directories are told apart
	 * @param price the guide price of the whip in every baseline - the other half of that telling apart
	 */
	private void seed(final File dir, final long stamp, final long price)
	{
		final PriceStore store = new PriceStore(gson, TestFilepaths.rooted(dir));
		store.saveBank(bank(stamp, price));
		for (final MovementWindow window : MovementWindow.values())
		{
			store.saveBucket(window, guideMap(window, stamp, price));
			store.saveTradedDay(window, LIVE_DAY.minusDays(window.days()), buckets(), stamp);
		}
		store.saveMapping(mapping(), stamp);
		store.saveRevisionIndex(history(), stamp);
		store.saveTradedLatest(quotes(), stamp);
		assertEquals("a seeded directory is a complete one", FULL_SET, dir.list().length);
	}

	/** Every load a launch performs, against a directory {@link #seed} wrote with these two numbers. */
	private void assertSeededStoreReadsEverything(final PriceStore store, final long stamp, final long price)
		throws IOException
	{
		final BankSnapshot bank = store.loadBank(ACCOUNT, PROFILE);
		assertEquals("the remembered bank is found under the key it carries", stamp, bank.capturedAtMillis);
		assertEquals(ACCOUNT, bank.accountHash);
		assertEquals(3, bank.items.size());
		assertEquals("Abyssal whip", bank.items.get(0).name);
		assertEquals(25_000, bank.items.get(2).quantity);
		assertEquals("and the coins that count towards Bank value (P1)", price, bank.currencyGp);

		for (final MovementWindow window : MovementWindow.values())
		{
			final PriceMap baseline = store.loadBucket(window);
			assertEquals("baseline-" + window.name() + " survives whole", guideMap(window, stamp, price), baseline);
			assertEquals(Long.valueOf(price), baseline.get(WHIP).high());

			final PriceStore.TradedDay traded = store.loadTradedDay(window);
			assertEquals("traded-" + window.name() + " keeps the day it is of (U1)",
				LIVE_DAY.minusDays(window.days()), traded.day());
			assertEquals(buckets(), traded.buckets());
			assertEquals(stamp, traded.fetchedAtMillis());
		}

		assertEquals(mapping(), store.loadMapping().value());
		assertEquals(stamp, store.loadMapping().fetchedAtMillis());
		assertEquals(history(), store.loadRevisionIndex().value());
		assertEquals(stamp, store.loadRevisionIndex().fetchedAtMillis());
		assertEquals(quotes(), store.loadTradedLatest().value());
		assertEquals(stamp, store.loadTradedLatest().fetchedAtMillis());

		assertEquals("and startUp's sweep finds nothing stale in it", 0, store.deleteStaleFiles());
		assertEquals("so every file is still there after the first launch", FULL_SET, names(store.dir()).size());
	}

	private static BankSnapshot bank(final long stamp, final long currencyGp)
	{
		final BankSnapshot snapshot = new BankSnapshot();
		snapshot.items = new ArrayList<>(Arrays.asList(
			item(WHIP, 1, "Abyssal whip", false),
			item(PARTYHAT, 2, "Red partyhat", false),
			item(BLOOD_RUNE, 25_000, "Blood rune", true)));
		snapshot.capturedAtMillis = stamp;
		snapshot.accountHash = ACCOUNT;
		snapshot.profileType = PROFILE;
		snapshot.currencyGp = currencyGp;
		return snapshot;
	}

	private static BankItem item(final int id, final int quantity, final String name, final boolean stackable)
	{
		final BankItem bankItem = new BankItem();
		bankItem.id = id;
		bankItem.quantity = quantity;
		bankItem.name = name;
		bankItem.stackable = stackable;
		return bankItem;
	}

	/** A guide baseline stamped with a real revision, so the sweep's rule 3 keeps it (K11). */
	private static PriceMap guideMap(final MovementWindow window, final long stamp, final long price)
	{
		final Map<Integer, PricePoint> points = new LinkedHashMap<>();
		points.put(WHIP, new PricePoint(price, price));
		points.put(PARTYHAT, new PricePoint(price * 1_000L, price * 1_000L));
		return new PriceMap(points, stamp, REVISION_SECONDS - window.days() * 86_400L, REV_ID - window.days());
	}

	private static Map<Integer, String> mapping()
	{
		final Map<Integer, String> names = new LinkedHashMap<>();
		names.put(WHIP, "Abyssal whip");
		names.put(PARTYHAT, "Red partyhat");
		names.put(BLOOD_RUNE, "Blood rune");
		return names;
	}

	private static List<RevisionRef> history()
	{
		return Arrays.asList(
			new RevisionRef(REV_ID, REVISION_SECONDS, "Gaz GEBot", "GE update"),
			new RevisionRef(REV_ID - 1_000L, REVISION_SECONDS - 86_400L, "Gaz GEBot", "GE update"));
	}

	private static Map<Integer, TradedPriceClient.Quote> quotes()
	{
		final Map<Integer, TradedPriceClient.Quote> map = new LinkedHashMap<>();
		map.put(WHIP, new TradedPriceClient.Quote(1_700_000L, 1_789_128_000L, 1_690_000L, 1_789_127_000L));
		// The API really serves one-sided quotes; a move must not turn the missing side into a zero.
		map.put(BLOOD_RUNE, new TradedPriceClient.Quote(1_000L, 1_789_120_000L, null, 0L));
		return map;
	}

	private static Map<Integer, TradedPriceClient.Bucket> buckets()
	{
		final Map<Integer, TradedPriceClient.Bucket> map = new LinkedHashMap<>();
		map.put(WHIP, new TradedPriceClient.Bucket(1_699_000L, 213L, 1_688_000L, 304L));
		map.put(BLOOD_RUNE, new TradedPriceClient.Bucket(null, 0L, 990L, 41_000L));
		return map;
	}

	// ---- reading the file system, through Filepath like everything else here

	/** The names of a directory's children, sorted; the directory itself is not one of them. */
	private static List<String> names(final Filepath dir) throws IOException
	{
		final List<String> names = new ArrayList<>();
		try (Stream<Filepath> walk = dir.walk(1))
		{
			walk.forEach(entry ->
			{
				if (!dir.equals(entry))
				{
					names.add(entry.getFileName());
				}
			});
		}
		names.sort(String::compareTo);
		return names;
	}

	/** Every child of a directory as name to bytes - what "identical bytes" is asserted against. */
	private static Map<String, byte[]> contents(final Filepath dir) throws IOException
	{
		final Map<String, byte[]> out = new LinkedHashMap<>();
		for (final String name : names(dir))
		{
			try (InputStream in = dir.join(name).openInputStream())
			{
				out.put(name, in.readAllBytes());
			}
		}
		return out;
	}

	/** The one name in {@code names} that starts with {@code prefix}; a fixture with two would prove nothing. */
	private static String onlyNameStartingWith(final Iterable<String> names, final String prefix)
	{
		final List<String> hits = new ArrayList<>();
		for (final String name : names)
		{
			if (name.startsWith(prefix))
			{
				hits.add(name);
			}
		}
		// The COUNT goes in the message, never the names: the one being looked for here is the backup's, which is
		// named for a real account.
		assertEquals("exactly one " + prefix + "* file was expected, found " + hits.size(), 1, hits.size());
		return hits.get(0);
	}

	// ---- keeping the account out of every message

	/**
	 * A file's name as it may be PRINTED: a bank file reduced to {@value #REDACTED_BANK}, everything else
	 * verbatim. See {@link #BANK_FILE} for why - and note that it redacts the FIXTURES' bank file too, which
	 * costs nothing and keeps one rule for every name in the class rather than one for the backup and another
	 * for everything else.
	 */
	private static String safeName(final String name)
	{
		return BANK_FILE.matcher(name).matches() ? REDACTED_BANK : name;
	}

	/** A set of names as it may be printed - what two directories are compared as, so a mismatch prints safely. */
	private static Set<String> safeNames(final Iterable<String> names)
	{
		final Set<String> safe = new TreeSet<>();
		for (final String name : names)
		{
			safe.add(safeName(name));
		}
		return safe;
	}

	// ---- the refusals

	/** Something the sandbox must refuse; a lambda may throw, because several of these calls declare it. */
	private interface Attempt
	{
		void run() throws Exception;
	}

	/**
	 * Runs {@code attempt} and answers the message of the {@link IllegalArgumentException} it must throw. JUnit
	 * 4.12 is the pinned version here and has no {@code assertThrows}, so this is the house form of it.
	 */
	private static String refused(final String what, final Attempt attempt)
	{
		try
		{
			attempt.run();
		}
		catch (final IllegalArgumentException e)
		{
			return String.valueOf(e.getMessage());
		}
		catch (final Exception e)
		{
			fail(what + " must be refused with IllegalArgumentException, not " + e);
		}
		fail(what + " was allowed, and the sandbox the Plugin Hub asked for is not there");
		return "";
	}
}
