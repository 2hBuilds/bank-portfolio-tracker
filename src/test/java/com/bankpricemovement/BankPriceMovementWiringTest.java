package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.inject.Guice;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The plugin's wiring (contract C35-C38, C41): the descriptor, the Hub descriptor line, the directory every
 * saved file is rooted at (addendum AD), the lifecycle order, the dev-bridge safety rule and every
 * {@code @Subscribe} handler. No client boots and no Guice injector builds the plugin: the private fields are
 * filled by reflection and the handlers are called directly, which is exactly how RuneLite calls them.
 *
 * <p>Swing work happens on the EDT ({@code startUp} and {@code shutDown} run there in the client, because
 * {@code PluginManager} asserts it), so the two lifecycle tests hop.
 */
public class BankPriceMovementWiringTest
{
	/**
	 * The exact text C46 forbids anywhere in {@code src/main/java/com/bankpricemovement}, spelled out: the
	 * seven fragments the contract lists, plus the three ways its prose rules ("no reflection", "no
	 * {@code Thread} construction", "no sleeps", "{@code ImageUtil} unused", "files only under
	 * {@code RuneLite.RUNELITE_DIR}") are actually written in Java. Deliberately NOT here: {@code TimeUnit} and
	 * {@code Executors}, which are legitimate ({@code f.get(timeout, TimeUnit.MILLISECONDS)},
	 * {@code scheduleWithFixedDelay(..., TimeUnit.MILLISECONDS)}) - a blanket "no concurrency" fragment would
	 * fail the suite on correct code.
	 */
	private static final List<String> HUB_BLOCKERS = Arrays.asList(
		"new Gson(",
		"new GsonBuilder(",
		"new OkHttpClient(",
		"OkHttpClient.Builder(",
		".execute()",
		"Class.getResource(",
		"getResourceAsStream(",
		"Class.forName(",
		"java.lang.reflect",
		".setAccessible(",
		"new Thread(",
		"Thread.sleep(",
		"ImageUtil",
		"System.getProperty(\"user.home\")",
		// The rest of the packager's disallowed-apis.txt (runelite/plugin-hub-tooling, read 2026-09-11) and the one
		// idiom its reviewers flag (re-asserting an interrupt in a catch block).
		".getVar(",
		"ChatMessageManager",
		"WidgetInfo",
		"WidgetID",
		"getItemStats(",
		"AccountClient",
		"AccountSession",
		"SessionManager",
		".interrupt(");

	/**
	 * The one line that decides where every file this plugin saves lands (addendum AD). Whitespace and line
	 * breaks are tolerated and nothing else is: a store rooted at any other expression is the defect this pins.
	 */
	private static final Pattern STORE_WIRING = Pattern.compile(
		"store\\s*=\\s*new\\s+PriceStore\\s*\\(\\s*gson\\s*,\\s*this\\s*::\\s*getPluginDirectory\\s*\\)");

	/** ANY construction of the store, so a second one rooted somewhere else cannot arrive unnoticed. */
	private static final Pattern ANY_STORE = Pattern.compile("new\\s+PriceStore\\s*\\(");

	/**
	 * The dev bridge's shot directory, pinned as ONE statement: {@code [^;]*} cannot cross a semicolon, so the
	 * supplier has to sit inside the {@code new BpmCommands(...)} argument list. Written any other way - a
	 * correct supplier built and then handed to nobody, or a different one handed over - this does not match.
	 */
	private static final Pattern SHOT_DIR_WIRING = Pattern.compile(
		"new\\s+BpmCommands\\s*\\([^;]*\\(\\s*\\)\\s*->\\s*getPluginDirectory\\s*\\(\\s*\\)\\s*\\.\\s*"
			+ "joinSegment\\s*\\(\\s*BpmCommands\\s*\\.\\s*SHOT_DIR\\s*\\)");

	/** Any name joined onto a {@code Filepath}, and any reach for the plugin's own directory. */
	private static final Pattern ANY_JOIN = Pattern.compile("\\.\\s*joinSegment\\s*\\(");
	private static final Pattern PLUGIN_DIRECTORY = Pattern.compile("getPluginDirectory\\b");

	/**
	 * The keys {@code runelite-plugin.properties} would name the Hub slug with, if it named it at all. It does
	 * not: the Hub descriptor carries only displayName/author/description/tags/plugins/version/build, and the
	 * slug is the FILE NAME of the two-line manifest in {@code runelite/plugin-hub}, which no file in this tree
	 * holds. So {@link #theDescriptorNamesTheDataDirectory()} pins the literal instead - and fails here if that
	 * premise ever stops being true, which is the moment to pin the key rather than the literal.
	 */
	private static final List<String> SLUG_KEYS = Arrays.asList("slug", "internalName", "id");

	/**
	 * A floor on the package walk. The package had 32 sources when this was written; a scan that finds a
	 * handful has found the wrong tree, and a test that silently scans nothing is worse than no test at all.
	 */
	private static final int LEAST_SOURCES = 25;

	/**
	 * Where the tests that touch a real {@link PriceStore} or a real {@link Filepath} work; never
	 * {@code ~/.runelite}, which is the developer's own client directory.
	 */
	@Rule
	public final TemporaryFolder dir = new TemporaryFolder();

	@After
	public void clearBridge()
	{
		BpmDevBridge.handler = null;
	}

	// ---------------------------------------------------------------- descriptor and registration

	@Test
	public void descriptorAndSuperclass()
	{
		final PluginDescriptor d = BankPriceMovementPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull(d);
		assertEquals("2h Bank Portfolio Tracker", d.name());
		// Addendum W made the sort four columns and addendum T added live prices; this is the wording that
		// says so, and it is the line the client prints under the plugin's name in its list.
		assertEquals("Your bank's value and price movement over 1 to 180 days: live prices, your inventory and"
			+ " worn gear, gp filters, and sorting by percent, gp, item or stack price", d.description());
		assertTrue("the Hub search wants the obvious words", Arrays.asList(d.tags()).contains("bank"));
		assertTrue(Arrays.asList(d.tags()).contains("grand exchange"));
		// The loader checks the DIRECT superclass; an intermediate base class makes it skip the plugin silently.
		assertEquals(Plugin.class, BankPriceMovementPlugin.class.getSuperclass());
	}

	/**
	 * Addendum AD, the fact every saved file now stands on. {@code Plugin.getPluginDirectory()} names the data
	 * directory after {@code internalName} and throws {@link IllegalArgumentException} outright when that name
	 * is empty - which is the default - so this one word is what makes the plugin able to store anything at
	 * all. Drop it and the plugin still loads, still draws and still fetches; only the remembered bank, the
	 * baselines and the traded buckets quietly stop existing. {@link PriceStore#DIR_NAME} is the single
	 * spelling of the Hub slug, and the descriptor, the store and the folder on disk all have to agree on it.
	 *
	 * <p>Read straight off the annotation: no plugin is constructed and {@code getPluginDirectory()} is never
	 * called, because calling it would create and migrate folders inside the developer's own
	 * {@code ~/.runelite}.
	 */
	@Test
	public void theDescriptorNamesTheDataDirectory() throws Exception
	{
		final PluginDescriptor d = BankPriceMovementPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull(d);
		assertEquals(PriceStore.DIR_NAME, d.internalName());
		assertFalse("an empty internalName makes getPluginDirectory() throw, and nothing is ever saved",
			d.internalName().isEmpty());

		// ...and the name is the SLUG the submission ships. A Hub slug is permanent - it is the file name of the
		// two-line manifest merged into runelite/plugin-hub (plugins/bank-portfolio-tracker, PR #16536) and
		// cannot be changed afterwards - while the folder a user's files sit in is named after internalName. Let
		// the two drift and an update looks for those files under a name they were never saved under.
		//
		// runelite-plugin.properties cannot be read for the slug: the Hub descriptor carries only
		// displayName/author/description/tags/plugins/version/build (the slug lives in the OTHER repository's
		// file name), and this combined workspace's copy still describes OSRS LOS - correctly, the shipped
		// export has its own. So the literal is pinned here, and the descriptor is asked whether that premise
		// still holds rather than trusted to.
		assertEquals("the submitted Hub slug is permanent: PR #16536 pinned plugins/bank-portfolio-tracker",
			"bank-portfolio-tracker", PriceStore.DIR_NAME);
		final Properties manifest = hubDescriptor();
		for (String key : SLUG_KEYS)
		{
			assertFalse("runelite-plugin.properties now carries '" + key + "' - pin internalName against that"
				+ " key rather than against the literal in this test",
				manifest.stringPropertyNames().contains(key));
		}
	}

	/**
	 * Addendum AD, the other half: what happens to the files an existing user already has. Every build up to
	 * the port wrote {@code ~/.runelite/bank-portfolio-tracker/} directly, while the port stores under
	 * {@code ~/.runelite/plugin-data/bank-portfolio-tracker/}. {@code legacyDataDirectory} is the one thing
	 * that makes RuneLite MOVE the old folder to the new place on the first call, so the update carries the
	 * remembered bank, the baselines and the traded buckets across instead of orphaning them beside a new empty
	 * directory. It names the same slug, so the two must match exactly - a near miss silently migrates nothing.
	 */
	@Test
	public void theDescriptorCarriesTheOldDataDirectoryOver() throws Exception
	{
		final PluginDescriptor d = BankPriceMovementPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull(d);
		assertEquals(PriceStore.DIR_NAME, d.legacyDataDirectory());
		assertFalse("an empty legacyDataDirectory moves nothing: the old folder is orphaned and the user starts"
			+ " over with no bank, no baselines and no buckets", d.legacyDataDirectory().isEmpty());

		// The name has to be a LEGAL directory name by RuneLite's own rules, and this is the very call the
		// client makes with it: getPluginDirectory() hands legacyDataDirectory to
		// Filepath.Unchecked.getLegacyPluginDirectory(RUNELITE_DIR, name) (clone Plugin, 1.12.39), which joins
		// it as ONE path component - joinSegment.checkName: no separators, no trailing dot or space, no Windows
		// device name - and then refuses the client's OWN folders: cache, logs, profiles, plugin-data,
		// screenshots, sideloaded-plugins and the rest of DOT_RUNELITE_DIRS. It throws on every call, not only
		// on the one that migrates, so a future rename onto one of those names would take the first save, the
		// stale sweep and the dev bridge down together at start-up on a user's machine. One constant serves as
		// both internalName and legacyDataDirectory, so the stricter of the two rules is applied to it once.
		//
		// Over a TemporaryFolder, because the call is pure path arithmetic: it reads nothing and creates
		// nothing, and the real ~/.runelite is never named.
		final Filepath legacy = Filepath.Unchecked.getLegacyPluginDirectory(dir.getRoot().toPath(),
			PriceStore.DIR_NAME);
		assertEquals("the name must survive as one path component", PriceStore.DIR_NAME, legacy.getFileName());
		assertTrue(legacy.toString(), legacy.toString().startsWith(dir.getRoot().toString()));
		assertEquals("path arithmetic only: this test creates nothing, here or anywhere else",
			0, dir.getRoot().list().length);

		// ...and the instrument really bites, so the line above is not a pass by inspection.
		try
		{
			Filepath.Unchecked.getLegacyPluginDirectory(dir.getRoot().toPath(), "logs");
			fail("a data directory named after one of the client's own folders must be refused");
		}
		catch (IllegalArgumentException expected)
		{
			// Exactly what a colliding rename would throw out of getPluginDirectory() at start-up.
		}
	}

	// ------------------------------------------- addendum AD: the production wiring, read off the source

	/**
	 * Addendum AD put every byte this plugin saves behind ONE expression, and this is what holds it there:
	 * {@code store = new PriceStore(gson, this::getPluginDirectory)} in {@code startUp}.
	 *
	 * <p><b>Why a source scan is the right instrument.</b> {@code Plugin.getPluginDirectory()} is
	 * {@code protected final} on RuneLite's {@code Plugin}, so nothing can override it or stand in for it, and
	 * CALLING it is not a read: it creates {@code ~/.runelite/plugin-data} on the developer's own machine and
	 * then moves any {@code ~/.runelite/bank-portfolio-tracker} into it, once, for real (clone Plugin, 1.12.39
	 * - the {@code Files.createDirectories} at the top and the {@code moveTo} at the bottom). A unit test must
	 * never do that. Nor can the seam be identified from the running object, because a method reference has no
	 * identity to compare and the {@link PriceStore.Directory} the store holds can only be told apart from a
	 * wrong one by asking it - which is the forbidden call again. So the only evidence of where production
	 * roots its files is the line that writes it down.
	 *
	 * <p>Before the port that line was {@code PriceStore.defaultDir()}, a constant a test could read. The
	 * lambda that replaced it was pinned by nothing: a reviewer rooted the store at another folder and the
	 * whole suite stayed green, while on a user's machine every file would have landed somewhere else.
	 *
	 * <p>Scanned with the COMMENTS BLANKED ({@link #withoutComments}), because the lines around this one
	 * explain what {@code getPluginDirectory()} does and a raw substring scan would pass on the prose alone.
	 */
	@Test
	public void theStoreIsRootedAtThePluginsOwnDirectory() throws Exception
	{
		final String code = shippedSource("BankPriceMovementPlugin.java");
		assertEquals("startUp must build the store from the plugin's OWN directory -"
			+ " store = new PriceStore(gson, this::getPluginDirectory) - or every bank, baseline and bucket"
			+ " this plugin saves lands somewhere RuneLite did not give it",
			1, occurrences(STORE_WIRING, code));
		assertEquals("exactly one PriceStore is built, and the assertion above says which: a second one rooted"
			+ " elsewhere would read and write files the first never sees",
			1, occurrences(ANY_STORE, code));
	}

	/**
	 * The same pin for the developer bridge's shots. {@code BpmCommands} writes a PNG wherever the supplier it
	 * was handed points, so that supplier is what keeps a shot inside the plugin's own directory:
	 * {@code () -> getPluginDirectory().joinSegment(BpmCommands.SHOT_DIR)} - the plugin's root, then the strict
	 * single-component join, and nothing else. Joining a different name, or rooting the supplier anywhere else,
	 * would leave the suite green and drop files outside the sandbox the Hub asked us to stay inside.
	 *
	 * <p>The bridge is developer-mode only and never reachable in a Hub client, so this is not a user-facing
	 * bug waiting to happen - it is the rule the port was made to keep, held to in the one place that could
	 * break it quietly.
	 */
	@Test
	public void theDevBridgeCanOnlyShootInsideThePluginsOwnDirectory() throws Exception
	{
		final String code = shippedSource("BankPriceMovementPlugin.java");
		assertEquals("the bridge's shot directory must be getPluginDirectory().joinSegment(BpmCommands.SHOT_DIR)"
			+ " and must be handed to BpmCommands in that same statement",
			1, occurrences(SHOT_DIR_WIRING, code));
		assertEquals("one name is joined onto a Filepath in this class, and the assertion above says which:"
			+ " a second join is a second folder with nothing watching it",
			1, occurrences(ANY_JOIN, code));
		assertEquals("the plugin's own directory is reached exactly twice - the store and the shots - so a"
			+ " third use is added to this test on purpose rather than arriving by accident",
			2, occurrences(PLUGIN_DIRECTORY, code));

		// SHOT_DIR has to be a single path component or joinSegment - the strict overload, which is what makes
		// the escape impossible - throws at the moment a shot is taken. Path arithmetic over the temporary
		// folder: nothing is created.
		assertEquals("the shots folder must be one path component", BpmCommands.SHOT_DIR,
			Filepath.Unchecked.getRooted(dir.getRoot().toPath()).joinSegment(BpmCommands.SHOT_DIR).getFileName());
	}

	/**
	 * ...and nowhere else in the shipped package builds a store or asks for the plugin's directory. The two
	 * tests above pin the expressions inside {@code BankPriceMovementPlugin}; this one pins that the file is
	 * the only place either can appear, so a second store - rooted at a folder of its own, with its own bank
	 * file and its own baselines - cannot be added in a class no scan is pointed at.
	 *
	 * <p>Two guards against a vacuous pass, because the only way a scan can lie is by reading nothing: the
	 * file count, and the two classes that own the plugin's files being among the names read.
	 */
	@Test
	public void nothingElseInTheShippedPackageBuildsAStoreOrReachesForTheDirectory() throws Exception
	{
		final Map<String, String> sources = shippedSources();
		assertTrue("only " + sources.size() + " sources were scanned - the walk found the wrong tree",
			sources.size() >= LEAST_SOURCES);
		assertTrue("PriceStore.java was not among the files scanned: the scan proves nothing",
			sources.containsKey("PriceStore.java"));
		assertTrue("BpmCommands.java was not among the files scanned: the scan proves nothing",
			sources.containsKey("BpmCommands.java"));

		final List<String> builders = new ArrayList<>();
		final List<String> askers = new ArrayList<>();
		for (Map.Entry<String, String> source : sources.entrySet())
		{
			if (occurrences(ANY_STORE, source.getValue()) > 0)
			{
				builders.add(source.getKey());
			}
			if (occurrences(PLUGIN_DIRECTORY, source.getValue()) > 0)
			{
				askers.add(source.getKey());
			}
		}

		assertEquals("only the plugin class may build the store",
			Collections.singletonList("BankPriceMovementPlugin.java"), builders);
		assertEquals("only the plugin class may ask RuneLite for the directory - getPluginDirectory() is"
			+ " protected on Plugin, and every other class is handed a Filepath or a seam instead",
			Collections.singletonList("BankPriceMovementPlugin.java"), askers);
	}

	/**
	 * C46 as a TEST rather than as a promise that somebody greps. The rule is the Hub's own submission blocker
	 * list, and the whole build is aimed at passing it, yet until now the only gate was a manual grep in a
	 * checklist - the one class of defect with nothing behind it. The fragments are matched literally, over
	 * source with its COMMENTS REMOVED: this package documents the rules it obeys ("never {@code execute}",
	 * "constructing one here is a Plugin Hub blocker"), and a raw substring scan would red-fail on a compliant
	 * file the moment somebody wrote the rule down. Two guards against a vacuous pass: the file count, and a
	 * marker that only real source can contain.
	 */
	@Test
	public void hubBlockersAreAbsentFromTheWholePackage() throws Exception
	{
		final Path root = Paths.get("src", "main", "java", "com", "bankpricemovement");
		assertTrue("wrong working directory - no package at " + root.toAbsolutePath(), Files.isDirectory(root));

		final List<String> hits = new ArrayList<>();
		int scanned = 0;
		boolean sawMarker = false;
		final List<Path> sources;
		try (Stream<Path> walk = Files.walk(root))
		{
			sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		for (Path source : sources)
		{
			scanned++;
			final String code = withoutComments(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
			sawMarker |= code.contains("implements Function<String, String>");
			final String[] lines = code.split("\n", -1);
			for (int i = 0; i < lines.length; i++)
			{
				for (String blocker : HUB_BLOCKERS)
				{
					if (lines[i].contains(blocker))
					{
						hits.add(source.getFileName() + ":" + (i + 1) + "  " + blocker);
					}
				}
			}
		}

		assertTrue("Plugin Hub blockers in com.bankpricemovement (C46):\n" + String.join("\n", hits), hits.isEmpty());
		assertTrue("only " + scanned + " source files were scanned - the walk found the wrong tree",
			scanned >= LEAST_SOURCES);
		assertTrue("nothing recognisable was read: the scan proves nothing", sawMarker);
	}

	@Test
	public void hubDescriptorListsThePlugin() throws Exception
	{
		final Properties p = hubDescriptor();
		final List<String> names = Arrays.stream(p.getProperty("plugins").split(","))
			.map(String::trim).collect(Collectors.toList());
		assertTrue(names.toString(), names.contains(BankPriceMovementPlugin.class.getName()));
		// This plugin declares no third-party dependency, so either build mode packages it: the plugin's own
		// hub repository says build=standard (the Hub substitutes its own build file); a combined workspace that
		// carries other plugins may say build=gradle for their sake.
		assertTrue("build must be the Hub's standard mode or a gradle build",
			Arrays.asList("gradle", "standard").contains(p.getProperty("build")));
	}

	/**
	 * The lambda form is load-bearing. Installing the plugin itself as a Module -
	 * {@code Guice.createInjector(plugin)}, the way RuneLite's PluginManager does it - makes Guice scan the
	 * class for {@code @Provides} methods, and this plugin's {@code @Provides} takes RuneLite's ConfigManager,
	 * which cannot be built in a unit test. Calling {@code configure(binder)} from inside a plain lambda module
	 * exercises the explicit bindings only - and this plugin declares none, which is the thing being pinned:
	 * every collaborator is built by hand in {@code startUp} with explicit arguments.
	 */
	@Test
	public void thePluginAddsNoGuiceBindingsOfItsOwn()
	{
		assertNotNull(Guice.createInjector(binder -> new BankPriceMovementPlugin().configure(binder)));
	}

	// ---------------------------------------------------------------- the dev bridge

	/**
	 * The dev bridge is a test hook and must be off in every client that did not ask for it. Nothing but
	 * {@code startUp} in a developer-mode client may fill it, so a plugin that has merely been constructed
	 * (which is all a Hub client does before the user enables it) leaves it null - and {@code shutDown} puts
	 * it back to null whether or not this run set it.
	 */
	@Test
	public void theDevBridgeIsEmptyUntilADeveloperModeStartUpFillsIt() throws Exception
	{
		BpmDevBridge.handler = null;
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		assertNull("constructing the plugin must not install a handler", BpmDevBridge.handler);

		final Field dev = BankPriceMovementPlugin.class.getDeclaredField("developerMode");
		dev.setAccessible(true);
		assertEquals(boolean.class, dev.getType());
		assertFalse("the flag defaults to off, so a client that never binds it gets no bridge",
			(boolean) dev.get(plugin));

		// RuneLite binds the constant as Names.named("developerMode") in RuneLiteModule (1.12.37, line 120);
		// javax.inject.Named is the annotation the bundled plugins read it back with (BankTagsPlugin:150).
		final Named named = dev.getAnnotation(Named.class);
		assertNotNull("the field must carry @Named or Guice cannot satisfy it", named);
		assertEquals("developerMode", named.value());
		assertNotNull("and it must be an injection point", dev.getAnnotation(Inject.class));

		// shutDown clears it even though this plugin never started - it is the first statement, before every
		// null guard - so a stale handler can never outlive the panel it points at.
		BpmDevBridge.handler = cmd -> "{}";
		onEdt(plugin::shutDown);
		assertNull(BpmDevBridge.handler);
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * C36/C37 with every injected collaborator mocked: the sidebar button goes on, and {@code shutDown} takes
	 * exactly it off again. Nothing here touches the disk - {@link PriceStore} creates its directory on the
	 * first WRITE - and nothing runs on the executor, which is a mock.
	 */
	@Test
	public void startUpAddsTheNavButtonAndShutDownRemovesIt() throws Exception
	{
		final Fixture f = new Fixture(false);
		onEdt(f.plugin::startUp);

		final ArgumentCaptor<NavigationButton> nav = ArgumentCaptor.forClass(NavigationButton.class);
		verify(f.clientToolbar).addNavigation(nav.capture());
		assertEquals("2h Bank Portfolio Tracker", nav.getValue().getTooltip());
		assertEquals(BankPriceMovementPlugin.NAV_PRIORITY, nav.getValue().getPriority());
		assertNotNull("the button needs a drawn icon or RuneLite renders a blank square",
			nav.getValue().getIcon());
		assertNull("a client that is not in developer mode installs no bridge", BpmDevBridge.handler);

		onEdt(f.plugin::shutDown);
		verify(f.clientToolbar).removeNavigation(nav.getValue());
		// The fields the handlers read are nulled, so an event that arrives after shutDown is a no-op.
		assertNull(field(f.plugin, "panel"));
		assertNull(field(f.plugin, "service"));
		assertNull(field(f.plugin, "navButton"));
	}

	/**
	 * T2, seam S2: {@code startUp} builds the traded client from the two INJECTED collaborators and hands the
	 * very same instance to the service, which is the only thing that ever asks it for a feed.
	 *
	 * <p>Three things are pinned, and each of them is a way the wave could have gone wrong quietly. That the
	 * client is built at all - a null one is "guide prices only for ever" (the service's own words), so a missed
	 * construction would leave the switch on, the card saying "Live prices on" and not one live row anywhere.
	 * That the SERVICE holds it - handing the field to nobody would do exactly the same thing. And that
	 * {@code shutDown} drops it, so a disable/enable does not leave the old client reachable from this instance,
	 * which RuneLite reuses.
	 *
	 * <p>It is built unconditionally, whatever {@code livePrices} says: the object makes no request of its own,
	 * the service asks it for nothing while the switch is off (T2), and building it lazily would mean a switch
	 * turned on mid-session had nothing to turn on.
	 */
	@Test
	public void startUpBuildsTheTradedClientAndHandsItToTheService() throws Exception
	{
		final Fixture f = new Fixture(false);
		// The switch off, to prove the client is built for the lifetime of the plugin rather than for the setting.
		when(f.config.livePrices()).thenReturn(false);
		// Y1: and the carried switch, whose stored OFF is the one a default-on item can lose silently.
		when(f.config.countInventory()).thenReturn(false);
		onEdt(f.plugin::startUp);

		final Object traded = field(f.plugin, "traded");
		assertNotNull("addendum T's traded feeds are built at startUp (T2)", traded);
		assertTrue(String.valueOf(traded), traded instanceof TradedPriceClient);

		final PriceService service = (PriceService) field(f.plugin, "service");
		assertNotNull(service);
		final Field held = PriceService.class.getDeclaredField("traded");
		held.setAccessible(true);
		assertSame("the service must hold the very client the plugin built, not another one",
			traded, held.get(service));

		onEdt(f.plugin::shutDown);
		assertNull("shutDown drops it, because RuneLite reuses the plugin instance", field(f.plugin, "traded"));
	}

	@Test
	public void aDeveloperModeStartUpInstallsTheBridgeAndShutDownClearsIt() throws Exception
	{
		final Fixture f = new Fixture(true);
		onEdt(f.plugin::startUp);
		assertNotNull("developer mode is the only thing that fills the slot", BpmDevBridge.handler);
		// It really is this plugin's bridge, and it answers without a client.
		assertTrue(BpmDevBridge.handler.apply("state"), BpmDevBridge.handler.apply("state").contains("\"ok\""));

		onEdt(f.plugin::shutDown);
		assertNull(BpmDevBridge.handler);
	}

	/**
	 * Handlers register only AFTER {@code startUp} returns, so a plugin switched on mid-session would learn
	 * nothing until the next login. The seed reads the live state on the client thread instead - and reads the
	 * ACCOUNT only.
	 *
	 * <p>The bank is deliberately not read here: {@code PluginManager} calls
	 * {@code gameEventManager.simulateGameEvents(plugin)} the moment {@code startUp} returns (clone
	 * PluginManager.java:429-441), which replays an {@code ItemContainerChanged} for every cached container
	 * into this very plugin (clone GameEventManager.java:111-130), so a seed that read container 95 as well
	 * captured, priced and SAVED the same 800-stack bank twice on every start.
	 */
	@Test
	public void theSeedReadsTheLiveAccountAndLeavesTheBankToRuneLitesOwnReplay() throws Exception
	{
		final Fixture f = new Fixture(false);
		onEdt(f.plugin::startUp);

		final ArgumentCaptor<Runnable> seed = ArgumentCaptor.forClass(Runnable.class);
		verify(f.clientThread).invoke(seed.capture());

		// A service of our own, so the calls can be counted; the real one was built by startUp.
		final PriceService service = mock(PriceService.class);
		set(f.plugin, "service", service);
		final BankReader reader = mock(BankReader.class);
		set(f.plugin, "bankReader", reader);

		when(f.client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		seed.getValue().run();
		verify(service, never()).setLoggedIn(anyBoolean(), anyLong(), anyString());

		when(f.client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(f.client.getAccountHash()).thenReturn(4242L);

		seed.getValue().run();
		verify(service).setLoggedIn(true, 4242L, RuneScapeProfileType.STANDARD.name());
		// The replay brings the bank; reading it here as well captures, prices and saves it a second time.
		verify(reader, never()).read(any(), anyLong(), anyString(), anyLong());
		verify(service, never()).setBank(any());
		verify(f.client, never()).getItemContainer(anyInt());

		onEdt(f.plugin::shutDown);
	}

	/**
	 * The same replay, arriving as the event it really is: a plugin enabled after a bank visit gets its rows
	 * from {@code onItemContainerChanged}, so dropping the seed's own container read costs nothing.
	 */
	@Test
	public void aPluginStartedAfterABankVisitStillGetsTheBankFromTheReplay() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(4242L);
		final ItemContainer container = mock(ItemContainer.class);
		final Item[] items = new Item[]{new Item(4151, 1)};
		when(container.getItems()).thenReturn(items);
		final BankSnapshot snapshot = new BankSnapshot();
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(snapshot);

		// What GameEventManager.simulateGameEvents posts for the cached bank (clone GameEventManager.java:124).
		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, container));

		verify(reader).read(eq(items), eq(4242L), eq(RuneScapeProfileType.STANDARD.name()), anyLong());
		verify(service).setBank(snapshot);
	}

	/**
	 * Every field a game thread reads is {@code volatile}. They are written on the EDT (PluginManager asserts
	 * it) and read on the client thread and on whichever thread posts a {@code ConfigChanged}, and RuneLite's
	 * EventBus makes no happens-before promise between the two (its subscriber map is a plain field, clone
	 * EventBus.java:82; {@code post} at :217 is not synchronized), so without this a replayed container event
	 * could see a half-built plugin as null and silently drop the first capture.
	 */
	@Test
	public void theFieldsTheGameThreadsReadAreVolatile() throws Exception
	{
		for (String name : new String[]{"store", "guide", "traded", "bankReader", "service", "panel", "accountHash",
			"profileType", "sentLoggedIn", "sentHash", "sentProfile", "prefsWriter", "lastBank"})
		{
			final Field f = BankPriceMovementPlugin.class.getDeclaredField(name);
			assertTrue(name + " is written on the EDT and read on another thread: it must be volatile",
				Modifier.isVolatile(f.getModifiers()));
		}
	}

	// ---------------------------------------------------------------- handlers

	/**
	 * C38: only container 95 is the bank. The id is {@code gameval InventoryID.BANK} (clone
	 * InventoryID.java:102) and the inventory (93) shares its shape, so a gate on the id is the whole guard.
	 */
	@Test
	public void onlyTheBankContainerReachesTheService() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(77L);

		final ItemContainer container = mock(ItemContainer.class);
		// A real Item: net.runelite.api.Item is a final @Value class, which Mockito 4.11 cannot mock here.
		final Item[] items = new Item[]{new Item(4151, 1)};
		when(container.getItems()).thenReturn(items);
		final BankSnapshot snapshot = new BankSnapshot();
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(snapshot);

		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, container));
		verify(reader).read(eq(items), eq(77L), eq(RuneScapeProfileType.STANDARD.name()), anyLong());
		verify(service).setBank(snapshot);

		// The inventory changes on every pickup: it must never reach the reader.
		plugin.onItemContainerChanged(new ItemContainerChanged(93, container));
		verify(service).setBank(any());

		// And a container with nothing behind it is not a crash.
		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, null));
		verify(service).setBank(any());

		// After shutDown the fields are null and the same event is a no-op rather than an NPE in the handler.
		set(plugin, "service", null);
		set(plugin, "bankReader", null);
		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, container));
	}

	/**
	 * A capture is stamped with the account read a line later and is then PERSISTED under it, so it may only be
	 * taken while the client can say who is playing. Two ways it cannot: the game state is not
	 * {@code LOGGED_IN} (the container the client still holds may be the previous player's), and the account
	 * hash is not a real account - {@code Client.getAccountHash()} answers -1 before a login (clone
	 * {@code com/jagex/oldscape/pub/OAuthApi.java:30-32}) while this plugin spells "nobody" as 0. Either would
	 * write somebody else's stacks over {@code bank-<hash>-<profile>.json}, or file a bank under an account
	 * that does not exist.
	 */
	@Test
	public void aContainerIsNeverCapturedWhileTheClientCannotSayWhoIsPlaying() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		final ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(4151, 1)});
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(new BankSnapshot());
		final ItemContainerChanged bank = new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, container);

		// Logging out, hopping, or on the login screen: the account below is real, and it is still not ours.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		when(client.getAccountHash()).thenReturn(77L);
		plugin.onItemContainerChanged(bank);
		verify(service, never()).setBank(any());
		verify(reader, never()).read(any(), anyLong(), anyString(), anyLong());

		// Logged in, but the client has not said who yet: -1 before a login, 0 after this plugin cleared it.
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(-1L);
		plugin.onItemContainerChanged(bank);
		when(client.getAccountHash()).thenReturn(0L);
		plugin.onItemContainerChanged(bank);
		verify(service, never()).setBank(any());

		// Both known: the capture goes through, stamped with that account.
		when(client.getAccountHash()).thenReturn(77L);
		plugin.onItemContainerChanged(bank);
		verify(reader).read(any(), eq(77L), eq(RuneScapeProfileType.STANDARD.name()), anyLong());
		verify(service).setBank(any());
	}

	/**
	 * {@code LOGGED_IN} fires on every region change as well as on login (core's XpTracker says so and guards
	 * on the account hash for the same reason, clone XpTrackerPlugin.java:183-187), and
	 * {@code PriceService.setLoggedIn} does real work every time it is told something: it bumps the login
	 * generation and queues a bank load on RuneLite's single shared executor, and it short-circuits only when
	 * the bank it holds already belongs to that account - which is false for the whole of a session in which no
	 * bank has been opened. So the same answer is sent once, and a NEW answer always gets through.
	 */
	@Test
	public void theSameLoginIsNotSentToTheServiceOnEveryRegionChange() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getAccountHash()).thenReturn(9L);

		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		verify(service, times(1)).setLoggedIn(true, 9L, RuneScapeProfileType.STANDARD.name());

		// Another account on the same client IS news, and so is the login that follows a logout.
		when(client.getAccountHash()).thenReturn(10L);
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		verify(service).setLoggedIn(true, 10L, RuneScapeProfileType.STANDARD.name());

		plugin.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		verify(service).setLoggedIn(false, 0L, "");
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		verify(service, times(2)).setLoggedIn(true, 10L, RuneScapeProfileType.STANDARD.name());
	}

	/**
	 * RuneLite reuses the plugin INSTANCE across a disable/enable, so the memo above must not survive
	 * {@code startUp}: the service it was memoising is gone, and the new one has been told nothing.
	 */
	@Test
	public void aRestartedPluginSendsItsFirstLoginAgain() throws Exception
	{
		final Fixture f = new Fixture(false);
		when(f.client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(f.client.getAccountHash()).thenReturn(9L);
		onEdt(f.plugin::startUp);
		final ArgumentCaptor<Runnable> seed = ArgumentCaptor.forClass(Runnable.class);
		verify(f.clientThread).invoke(seed.capture());
		seed.getValue().run();
		onEdt(f.plugin::shutDown);

		final PriceService second = mock(PriceService.class);
		onEdt(f.plugin::startUp);
		set(f.plugin, "service", second);
		f.plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		verify(second).setLoggedIn(true, 9L, RuneScapeProfileType.STANDARD.name());
		onEdt(f.plugin::shutDown);
	}

	/**
	 * C38: LOGGED_IN carries the account into the service; the login screen and a world hop say "logged out"
	 * and keep the rows. LOADING is deliberately ignored - it fires on every scene change, a bank visit
	 * included, and nothing this plugin holds is scene-bound.
	 */
	@Test
	public void gameStateMovesTheLoggedInFlagAndNothingElse() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getAccountHash()).thenReturn(9L);

		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
		verify(service).setLoggedIn(true, 9L, RuneScapeProfileType.STANDARD.name());

		plugin.onGameStateChanged(gameState(GameState.LOGIN_SCREEN));
		verify(service).setLoggedIn(false, 0L, "");

		plugin.onGameStateChanged(gameState(GameState.HOPPING));
		verify(service, times(2)).setLoggedIn(false, 0L, "");

		plugin.onGameStateChanged(gameState(GameState.LOADING));
		verify(service, times(3)).setLoggedIn(anyBoolean(), anyLong(), anyString());

		// No service (before startUp, after shutDown): still not a crash.
		set(plugin, "service", null);
		plugin.onGameStateChanged(gameState(GameState.LOGGED_IN));
	}

	/**
	 * C38/C39: the config panel and the sidebar are the same switch. Our group reaches both halves; another
	 * plugin's group reaches neither.
	 */
	@Test
	public void configChangesReachTheWidgetsAndTheService() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		when(config.gpMin()).thenReturn(100);
		when(config.gpMax()).thenReturn(5000);
		when(config.sortMode()).thenReturn(SortMode.GP_MOVE);
		when(config.sortDescending()).thenReturn(false);
		when(config.window()).thenReturn(MovementWindow.D7);

		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, "sortMode"));
		final RowFilter expected = new RowFilter(100, 5000, SortMode.GP_MOVE, false, MovementWindow.D7);
		verify(service).setFilter(expected);
		// The widget half is queued onto the EDT; drain it before looking.
		onEdt(() ->
		{
		});
		verify(panel).applyFilter(expected);

		plugin.onConfigChanged(configChanged("someoneelse", "sortMode"));
		verify(service).setFilter(any());
		// A filter key never re-renders the card (addendum O: the two roads do not cross).
		verify(panel, never()).applyHeroVisibility(any());
	}

	// ------------------------------------------------- addendum O: the card's three show/hide switches (O2)

	/**
	 * O2: the card opens showing exactly the figures the config names. A card that flashed a total the user had
	 * hidden and then dropped it would be the first thing they saw.
	 */
	@Test
	public void theCardOpensShowingTheFiguresTheConfigNames() throws Exception
	{
		final Fixture f = new Fixture(false);
		when(f.config.showBankValue()).thenReturn(false);
		when(f.config.showBankMoveGp()).thenReturn(true);
		when(f.config.showBankMovePct()).thenReturn(false);
		onEdt(f.plugin::startUp);

		final BankPriceMovementPanel panel = (BankPriceMovementPanel) field(f.plugin, "panel");
		assertNotNull(panel);
		assertEquals(HeroVisibility.of(false, true, false), panel.heroVisibility());
		onEdt(f.plugin::shutDown);
	}

	/**
	 * O2's other half: RuneLite's own settings panel reaches the card. The three switches are PRESENTATION -
	 * they change no row, no figure and no fetch - so each takes its own road: {@code applyHeroVisibility} and
	 * nothing else. Down the filter road one of them would hand {@link PriceService} an identical filter and
	 * recompute the whole list because the user hid a number.
	 */
	@Test
	public void aCardSwitchReRendersTheCardAndRecomputesNothing() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		when(config.showBankMoveGp()).thenReturn(true);

		for (String key : new String[]{BankPriceMovementPlugin.SHOW_VALUE_KEY,
			BankPriceMovementPlugin.SHOW_GP_KEY, BankPriceMovementPlugin.SHOW_PCT_KEY})
		{
			assertTrue(key + " is one of the card's own keys", BankPriceMovementPlugin.isHeroKey(key));
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
		}
		// The widget half is queued onto the EDT; drain it before looking.
		onEdt(() ->
		{
		});
		verify(panel, times(3)).applyHeroVisibility(HeroVisibility.of(false, true, false));
		verify(service, never()).setFilter(any());
		verify(panel, never()).applyFilter(any());

		assertFalse(BankPriceMovementPlugin.isHeroKey("sortMode"));
		assertFalse(BankPriceMovementPlugin.isHeroKey(null));

		// After shutDown there is no card to re-render, and the event must not throw.
		set(plugin, "panel", null);
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.SHOW_VALUE_KEY));
	}

	/** O2: the three stored keys as one value, in the order the card draws them. */
	@Test
	public void theVisibilityIsBuiltFromTheThreeStoredKeys() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "config", config);
		// A mocked proxy answers false for everything until it is told otherwise, which is the all-hidden card.
		assertEquals(HeroVisibility.NONE, plugin.heroFromConfig());

		when(config.showBankValue()).thenReturn(true);
		when(config.showBankMoveGp()).thenReturn(true);
		when(config.showBankMovePct()).thenReturn(true);
		assertEquals(HeroVisibility.ALL, plugin.heroFromConfig());

		when(config.showBankMoveGp()).thenReturn(false);
		assertEquals(HeroVisibility.of(true, false, true), plugin.heroFromConfig());
	}

	/**
	 * O4: the hero card's right-click check items write through the same {@code Prefs}-style seam the filter
	 * widgets use, so RuneLite's settings panel follows on the {@code ConfigChanged} those writes post. All
	 * three keys go every time - a check item that wrote only its own would leave the other two unstored on a
	 * fresh profile.
	 */
	@Test
	public void theHeroPrefSeamReadsAndWritesTheThreeKeys() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		when(config.showBankValue()).thenReturn(true);
		when(config.showBankMoveGp()).thenReturn(true);
		when(config.showBankMovePct()).thenReturn(true);

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		assertEquals(HeroVisibility.ALL, prefs.loadHero());

		prefs.saveHero(HeroVisibility.of(false, true, false));
		final String g = BankPriceMovementConfig.GROUP;
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.SHOW_VALUE_KEY, (Object) Boolean.FALSE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.SHOW_GP_KEY, (Object) Boolean.TRUE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.SHOW_PCT_KEY, (Object) Boolean.FALSE);

		// Nothing to save is not a crash, and writes nothing more.
		prefs.saveHero(null);
		verify(cm, times(3)).setConfiguration(anyString(), anyString(), any(Object.class));

		// The filter half of the seam is untouched by any of that.
		assertEquals(plugin.filterFromConfig(), prefs.load());
	}

	/**
	 * The three writes of one {@code saveHero} are ONE change of mind, exactly as the filter's five are (see
	 * {@link #theSidebarsOwnWritesDoNotFeedAHalfUpdatedFilterBack}), and they are guarded the same way.
	 * {@code ConfigManager} posts a {@code ConfigChanged} synchronously as each key lands, and the check item
	 * that was ticked has already painted the card ({@code BankPriceMovementPanel.setHeroVisibility}:
	 * {@code applyHeroVisibility}, then {@code prefs.saveHero}) - so an unguarded write re-rendered the card
	 * three more times per tick, two of them from a config in which only some of the three keys had been
	 * written.
	 */
	@Test
	public void theCardsOwnWritesDoNotReRenderItOncePerKey() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		// The card as it stands before the tick - everything shown - and the rest of the stored filter, which
		// the guard must keep out of this road as well.
		when(config.showBankValue()).thenReturn(true);
		when(config.showBankMoveGp()).thenReturn(true);
		when(config.showBankMovePct()).thenReturn(true);
		when(config.sortMode()).thenReturn(SortMode.PERCENT_MOVE);
		when(config.window()).thenReturn(MovementWindow.DEFAULT);
		// ConfigManager, as it really behaves: the stored value changes and the event is posted on this very
		// thread before the next key is written.
		doAnswer(invocation ->
		{
			final String key = invocation.getArgument(1);
			final Boolean value = (Boolean) invocation.getArgument(2);
			if (BankPriceMovementPlugin.SHOW_VALUE_KEY.equals(key))
			{
				when(config.showBankValue()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.SHOW_GP_KEY.equals(key))
			{
				when(config.showBankMoveGp()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.SHOW_PCT_KEY.equals(key))
			{
				when(config.showBankMovePct()).thenReturn(value);
			}
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
			return null;
		}).when(cm).setConfiguration(anyString(), anyString(), any(Object.class));

		plugin.configPrefs().saveHero(HeroVisibility.of(false, true, false));

		// Not "the right visibility three times": nothing at all. The card painted itself before it wrote.
		onEdt(() ->
		{
		});
		verify(panel, never()).applyHeroVisibility(any());
		// ...and a presentation switch still recomputes nothing (O2).
		verify(service, never()).setFilter(any());

		// The guard is over as soon as the save is: a tick made in RuneLite's own settings panel still lands.
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.SHOW_VALUE_KEY));
		onEdt(() ->
		{
		});
		verify(panel).applyHeroVisibility(HeroVisibility.of(false, true, false));
	}

	// --------------- addendum Q's two surviving view switches (Q3), addendum T's (T1), Y's, AH's; AO1 took one

	/**
	 * Q3, T1, Y1 and AH: the five stored keys as one value, in the order the gear menu lists them. The last was
	 * deleted by addendum AI and restored by addendum AJ - the user asked where it had gone ("where is the show
	 * hover text box and wording and default 'off' setting?", 2026-09-20) - so it is a live stored key again, read
	 * here rather than swept.
	 *
	 * <p>There were SIX until addendum AO. {@code holdingOnRows} rode here from addendum Q until the user saw the
	 * three-line row in a client and found the switch reached nothing drawn (AO1); the key is deleted and swept at
	 * start-up ({@link #aStoredHoldingOnRowsIsUnsetAtStartUp()}), so a build that still read it would be reading a
	 * value no item writes.
	 *
	 * <p>Every expectation names all five fields, because {@link ViewOptions} now has exactly ONE constructor and
	 * nothing may stand in for a read that never happened: a stored key left out of the build would mean a user
	 * who turned live prices - or the carried items, or the hover text - away from the shipped default in
	 * RuneLite's settings got it back on the next launch, and every {@code ConfigChanged} handed the service the
	 * wrong answer.
	 *
	 * <p>Addendum AH's switch is the one that can hide here most easily, because the value the shipped default
	 * supplies for it - false - is the very answer a mocked config gives when nothing has been stubbed. So it is
	 * turned ON and read back on its own line, where neither a default nor an unstubbed proxy can stand in for a
	 * read that never happened.
	 */
	@Test
	public void theViewOptionsAreBuiltFromTheFiveStoredKeys() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "config", config);
		// A mocked proxy answers false for everything until it is told otherwise - here, every switch off, which
		// is a combination the interface defaults do not give (three of them default on).
		assertEquals(new ViewOptions(false, false, false, false, false), plugin.optionsFromConfig());

		when(config.countCash()).thenReturn(true);
		when(config.livePrices()).thenReturn(true);
		when(config.countInventory()).thenReturn(true);
		assertEquals(ViewOptions.DEFAULT, plugin.optionsFromConfig());

		when(config.countUntradeables()).thenReturn(true);
		assertEquals(new ViewOptions(true, true, true, true, false), plugin.optionsFromConfig());

		// ...and each of the three late arrivals is READ rather than assumed: moving only one moves only it.
		when(config.livePrices()).thenReturn(false);
		assertEquals(new ViewOptions(true, true, false, true, false), plugin.optionsFromConfig());
		when(config.countInventory()).thenReturn(false);
		assertEquals(new ViewOptions(true, true, false, false, false), plugin.optionsFromConfig());
		// AH: a stored ON is the one answer for this switch that no default and no unstubbed proxy can give.
		when(config.showHoverText()).thenReturn(true);
		assertEquals(new ViewOptions(true, true, false, false, true), plugin.optionsFromConfig());
	}

	/**
	 * Q3, T1, Y1 and AH: RuneLite's own settings page reaches the gear's five switches, and they take a THIRD
	 * road - neither the filter's nor the card's. Four of them change what the figures ARE (Q4, Q5, T1, Y3), so
	 * the service recomputes; all five change what is drawn, so the panel re-renders. What must NOT happen is a
	 * {@code setFilter}: the band, the ordering and the window are untouched, and a filter the service already
	 * holds would make it rebuild the whole list for nothing.
	 *
	 * <p>The road carried SIX until addendum AO. {@code holdingOnRows} was the one passenger that changed only a
	 * READING of figures already computed, and it is deleted (AO1) - so the last assertions below pin that its
	 * key is no longer one of the gear's own: a build that still answered true for it would send a swept,
	 * item-less key down the road on the very {@code ConfigChanged} the start-up unset posts.
	 */
	@Test
	public void aGearSwitchRecomputesTheFiguresAndReRendersTheSidebar() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		when(config.countUntradeables()).thenReturn(true);
		// AH: switched ON, so the value that travels this road is one no unstubbed proxy could have produced.
		when(config.showHoverText()).thenReturn(true);

		for (String key : new String[]{BankPriceMovementPlugin.COUNT_CASH_KEY,
			BankPriceMovementPlugin.COUNT_UNTRADEABLES_KEY,
			// T1: the live-price switch is one of the gear's keys too, and takes the same road. It is the one
			// whose ConfigChanged can start or stop a FETCH, which is exactly why it must not be mistaken for a
			// hero key (a re-render that never reaches the service) or a filter key.
			BankPriceMovementPlugin.LIVE_PRICES_KEY,
			// Y1: the carried switch is the fourth passenger since addendum AO, and the one whose ConfigChanged
			// can change a row's QUANTITY - so it must reach the service, not merely the card.
			BankPriceMovementPlugin.COUNT_INVENTORY_KEY,
			// AH, as addendum AJ leaves it: the hover switch is the last, and the only one that changes
			// NOTHING the service computes - it decides whether the hero card's hover and every CONTROL's
			// tooltip are set at all. It rides here anyway, because applyOptions is the only road that hands
			// the panel a ViewOptions: down the card's road (applyHeroVisibility) the controls would never
			// hear, and down the filter's the whole list would be recomputed because a reader asked for a
			// quieter sidebar.
			BankPriceMovementPlugin.SHOW_HOVER_TEXT_KEY})
		{
			assertTrue(key + " is one of the gear's own keys", BankPriceMovementPlugin.isOptionKey(key));
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
		}
		// The widget half is queued onto the EDT; drain it before looking.
		onEdt(() ->
		{
		});
		final ViewOptions expected = new ViewOptions(false, true, false, false, true);
		verify(service, times(5)).setOptions(expected);
		verify(panel, times(5)).applyOptions(expected);
		verify(service, never()).setFilter(any());
		verify(panel, never()).applyFilter(any());
		// ...and the card's switches are a different road again (O2): a gear switch never re-renders the hero.
		verify(panel, never()).applyHeroVisibility(any());

		// AO1: the deleted switch's key is not one of the gear's own any more. It is swept at start-up, and that
		// unset posts a ConfigChanged of its own - which must read as a stranger's key and not as a passenger.
		assertFalse(BankPriceMovementPlugin.isOptionKey(BankPriceMovementPlugin.LEGACY_HOLDING_KEY));
		assertFalse(BankPriceMovementPlugin.isOptionKey("sortMode"));
		assertFalse(BankPriceMovementPlugin.isOptionKey(BankPriceMovementPlugin.SHOW_VALUE_KEY));
		assertFalse(BankPriceMovementPlugin.isOptionKey(null));

		// After shutDown there is neither a sidebar to re-render nor a service to recompute, and the event must
		// not throw.
		set(plugin, "panel", null);
		set(plugin, "service", null);
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.COUNT_CASH_KEY));
	}

	/**
	 * Q3, T1, Y1 and AH: the gear menu's check items write through the same {@code Prefs} seam the filter widgets
	 * and the hero switches use, so RuneLite's settings page follows. All five keys go every time, for
	 * {@code saveHero}'s reason - an item that wrote only its own would leave the others unstored on a fresh
	 * profile - and the service is told as well, which is the one thing {@code saveHero} does not do: hiding a
	 * figure changes no sum, and counting the cash - or reading a liquid item off the traded series - does.
	 *
	 * <p>It was six writes until addendum AO deleted {@code holdingOnRows} (AO1), and the count is pinned below
	 * as well as the keys: a sixth write would be a write to a key with no item behind it, which the start-up
	 * sweep would then unset on the next launch and the reader would never see stored at all.
	 *
	 * <p>Addendum AH's {@code showHoverText} is written here with the rest even though the service does nothing
	 * with it. It is a choice about the sidebar, and the whole point of storing it is that a reader who turned
	 * the hover text on does not meet a silent sidebar again next launch - so the key that is easiest to forget
	 * in the save is the one whose absence the user would feel.
	 */
	@Test
	public void theOptionsPrefSeamReadsAndWritesTheFiveKeysAndTellsTheService() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		final PriceService service = mock(PriceService.class);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		set(plugin, "service", service);
		when(config.countCash()).thenReturn(true);
		when(config.livePrices()).thenReturn(true);
		when(config.countInventory()).thenReturn(true);

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		// The shipped defaults, hover text included: AH's switch defaults OFF, which is what the unstubbed proxy
		// above answers, and DEFAULT says so too.
		assertEquals(ViewOptions.DEFAULT, prefs.loadOptions());

		// Every switch away from its default, the hover text ON - so each of the five writes below carries a
		// value the config did not already hold, and a key written from the wrong field would show as the
		// wrong one.
		final ViewOptions all = new ViewOptions(false, true, false, false, true);
		prefs.saveOptions(all);
		final String g = BankPriceMovementConfig.GROUP;
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.COUNT_CASH_KEY, (Object) Boolean.FALSE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.COUNT_UNTRADEABLES_KEY, (Object) Boolean.TRUE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.LIVE_PRICES_KEY, (Object) Boolean.FALSE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.COUNT_INVENTORY_KEY, (Object) Boolean.FALSE);
		verify(cm).setConfiguration(g, BankPriceMovementPlugin.SHOW_HOVER_TEXT_KEY, (Object) Boolean.TRUE);
		// AO1: and the deleted key is not written at all. The sweep unsets it at start-up, so a save that still
		// wrote it would put back on every tick the very value the launch had just taken away.
		verify(cm, never()).setConfiguration(eq(g), eq(BankPriceMovementPlugin.LEGACY_HOLDING_KEY),
			any(Object.class));
		verify(service).setOptions(all);

		// Nothing to save is not a crash, and writes nothing more.
		prefs.saveOptions(null);
		verify(cm, times(5)).setConfiguration(anyString(), anyString(), any(Object.class));
		verify(service, times(1)).setOptions(any());

		// No manager at all (a field never injected) still reaches the service: the figures on screen must
		// follow the tick even where there is nothing to remember it in.
		set(plugin, "configManager", null);
		plugin.configPrefs().saveOptions(ViewOptions.DEFAULT);
		verify(service).setOptions(ViewOptions.DEFAULT);

		// And after shutDown there is no service either, which is a no-op rather than an NPE.
		set(plugin, "service", null);
		plugin.configPrefs().saveOptions(ViewOptions.DEFAULT);

		// The filter half of the seam is untouched by any of that.
		assertEquals(plugin.filterFromConfig(), prefs.load());
	}

	/**
	 * The five writes of one {@code saveOptions} are ONE change of mind, exactly as the filter's five and the
	 * card's three are, and they are guarded the same way ({@code prefsWriter}). Unguarded, ticking one gear
	 * item would recompute the bank value four times more from a config in which only some of the five keys had
	 * been written - and the figure on screen would be wrong for as long as each of those recomputes took. The
	 * service is still told exactly ONCE, from the tail of the save itself, because that is the half the guard
	 * suppressed.
	 *
	 * <p>Five since addendum AO, six before it: the deleted {@code holdingOnRows} had a branch of its own in the
	 * fake {@link ConfigManager} below, and it is gone with the key (AO1).
	 */
	@Test
	public void theGearsOwnWritesDoNotRecomputeOncePerKey() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		// The sidebar as it stands before the tick: the defaults, plus the rest of the stored filter, which the
		// guard must keep out of this road as well.
		when(config.countCash()).thenReturn(true);
		when(config.livePrices()).thenReturn(true);
		when(config.countInventory()).thenReturn(true);
		when(config.sortMode()).thenReturn(SortMode.PERCENT_MOVE);
		when(config.window()).thenReturn(MovementWindow.DEFAULT);
		// ConfigManager, as it really behaves: the stored value changes and the event is posted on this very
		// thread before the next key is written.
		doAnswer(invocation ->
		{
			final String key = invocation.getArgument(1);
			final Boolean value = (Boolean) invocation.getArgument(2);
			if (BankPriceMovementPlugin.COUNT_CASH_KEY.equals(key))
			{
				when(config.countCash()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.COUNT_UNTRADEABLES_KEY.equals(key))
			{
				when(config.countUntradeables()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.LIVE_PRICES_KEY.equals(key))
			{
				when(config.livePrices()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.COUNT_INVENTORY_KEY.equals(key))
			{
				when(config.countInventory()).thenReturn(value);
			}
			else if (BankPriceMovementPlugin.SHOW_HOVER_TEXT_KEY.equals(key))
			{
				when(config.showHoverText()).thenReturn(value);
			}
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
			return null;
		}).when(cm).setConfiguration(anyString(), anyString(), any(Object.class));

		// The tick turns the untradeables ON, the live switch OFF and the hover text ON, so three of the five
		// writes really move a stored value and the guard has five events to swallow.
		final ViewOptions ticked = new ViewOptions(true, true, false, true, true);
		plugin.configPrefs().saveOptions(ticked);

		// Not "the right options five times": nothing at all from the round trip. The menu applied the switch
		// before it wrote.
		onEdt(() ->
		{
		});
		verify(panel, never()).applyOptions(any());
		verify(service, times(1)).setOptions(ticked);
		// ...and a gear switch still moves no row into or out of the list (Q3).
		verify(service, never()).setFilter(any());

		// The guard is over as soon as the save is: a tick made in RuneLite's own settings panel still lands.
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.COUNT_UNTRADEABLES_KEY));
		onEdt(() ->
		{
		});
		verify(panel).applyOptions(ticked);
		verify(service, times(2)).setOptions(ticked);
	}

	/**
	 * Q3, T1 and AH: the sidebar opens on the stored switches. The panel is built with its own defaults and the
	 * service with none at all, so a bank value that counted cash the user had switched off - or a list that
	 * flashed the untradeables they had not asked for - would be the first thing they saw.
	 */
	@Test
	public void theSidebarOpensOnTheStoredViewOptions() throws Exception
	{
		final Fixture f = new Fixture(false);
		when(f.config.countCash()).thenReturn(false);
		when(f.config.countUntradeables()).thenReturn(true);
		// T1: switched off, the setting that matters most at start-up - a service opened on the default would
		// fetch the traded feeds this profile has said no to before the first ConfigChanged could stop it.
		when(f.config.livePrices()).thenReturn(false);
		// Y1: switched off as well, the other stored answer a build that skipped the read would turn back on.
		when(f.config.countInventory()).thenReturn(false);
		// AH: switched ON, which is the direction this one can be lost in. It ships OFF, so a panel opened on
		// ViewOptions.DEFAULT looks exactly right to a build that never read the key, and a reader who asked for
		// the hover text would silently get none until they went back to the gear menu and ticked it again.
		when(f.config.showHoverText()).thenReturn(true);
		onEdt(f.plugin::startUp);

		final BankPriceMovementPanel panel = (BankPriceMovementPanel) field(f.plugin, "panel");
		assertNotNull(panel);
		final ViewOptions stored = new ViewOptions(false, true, false, false, true);
		assertEquals(stored, panel.options());
		// The service was told too, or the figures behind the card would be the defaults until the first tick.
		// Its own switches, not the Status's: that one answers what the figures ALREADY on screen were computed
		// with, which is one publish later and, in a fixture whose executor drops every task, never.
		final PriceService service = (PriceService) field(f.plugin, "service");
		assertNotNull(service);
		assertEquals(stored, service.options());
		onEdt(f.plugin::shutDown);
	}

	// --------------------------------------- addendum Z's price presets (Z1): the fourth ConfigChanged road

	/**
	 * Z1: the key that landed fourteenth, and is the thirteenth since addendum AO, as the value the chips are
	 * drawn from. It is the only stored key of this plugin that
	 * holds FREE TEXT, so the reader has to survive everything a hand-edited profile or a settings page can put
	 * there - and every one of those answers is {@link BandPresets#DEFAULT} rather than null, an exception or a
	 * fold with no chips in it.
	 */
	@Test
	public void thePresetsAreBuiltFromTheStoredLineAndAnythingUnreadableIsTheDefault() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "config", config);

		// A mocked proxy answers null for a String until it is told otherwise - the state a profile is in before
		// ConfigManager has written the interface default over it.
		assertEquals(BandPresets.DEFAULT, plugin.presetsFromConfig());

		when(config.bandPresets()).thenReturn("1m, 10m, 100m");
		assertEquals(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L), plugin.presetsFromConfig());

		// Typed into RuneLite's settings page by hand, in the order that came to mind: the value sorts, it does
		// not obey.
		when(config.bandPresets()).thenReturn("10m 100k 1m");
		assertEquals(BandPresets.DEFAULT, plugin.presetsFromConfig());

		// The four ways to get it wrong, and one answer to all of them: the defaults, so the fold always has
		// chips and the user can repair the line from the boxes.
		for (String bad : new String[]{"", "   ", "abc", "1m", "1m, 10m", "1m, 1m, 10m", "0, 1m, 10m",
			"1m, 10m, 100m, 1b"})
		{
			when(config.bandPresets()).thenReturn(bad);
			assertEquals("'" + bad + "' must read as the defaults", BandPresets.DEFAULT,
				plugin.presetsFromConfig());
		}
	}

	/**
	 * Z1: the presets take a FOURTH road, and the shortest one - the panel and nobody else. Not the filter's: the
	 * band a chip applies is {@code gpMin} / {@code gpMax}, which this key never writes, so a {@code setFilter}
	 * here would rebuild the whole list because a user re-cut a chip. Not the option road: no figure moves. Not
	 * the card's: the hero switches are a different menu.
	 */
	@Test
	public void aPresetChangeRelabelsTheChipsAndRecomputesNothing() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		when(config.bandPresets()).thenReturn("1m, 10m, 100m");

		final String key = BankPriceMovementPlugin.BAND_PRESETS_KEY;
		assertEquals("bandPresets", key);
		assertTrue(BankPriceMovementPlugin.isPresetKey(key));
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
		// The widget half is queued onto the EDT; drain it before looking.
		onEdt(() ->
		{
		});
		verify(panel).applyPresets(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L));
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
		verify(panel, never()).applyFilter(any());
		verify(panel, never()).applyOptions(any());
		verify(panel, never()).applyHeroVisibility(any());

		// The road is this key alone: nothing else in the group is mistaken for it, and nothing of it for them.
		assertFalse(BankPriceMovementPlugin.isPresetKey("gpMin"));
		assertFalse(BankPriceMovementPlugin.isPresetKey("gpMax"));
		assertFalse(BankPriceMovementPlugin.isPresetKey(BankPriceMovementPlugin.COUNT_CASH_KEY));
		assertFalse(BankPriceMovementPlugin.isPresetKey(null));
		assertFalse(BankPriceMovementPlugin.isOptionKey(key));
		assertFalse(BankPriceMovementPlugin.isHeroKey(key));

		// After shutDown there is no sidebar to relabel, and the event must not throw.
		set(plugin, "panel", null);
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
	}

	/**
	 * Z1: the three boxes at the foot of the gear menu write through the same {@code Prefs} seam every other
	 * widget uses, so RuneLite's settings page shows the line they stored. ONE key and no service: the sums
	 * behind the card have never depended on what a chip offers, which is what makes this the shortest of the
	 * four roads.
	 */
	@Test
	public void thePresetsPrefSeamReadsAndWritesTheOneKeyAndTellsNobodyElse() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		final PriceService service = mock(PriceService.class);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		set(plugin, "service", service);
		when(config.bandPresets()).thenReturn("100k, 1m, 10m");

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		assertEquals(BandPresets.DEFAULT, prefs.loadPresets());

		// What the boxes hand over is already sorted; what is STORED is the value's own text, so the settings
		// page and the boxes print the same line.
		prefs.savePresets(BandPresets.of(100_000_000L, 1_000_000L, 10_000_000L));
		// The STRING overload of setConfiguration, which is what a String value binds to (ConfigManager declares
		// both it and the generic one); the boolean and enum keys beside it go through the other.
		verify(cm).setConfiguration(BankPriceMovementConfig.GROUP, BankPriceMovementPlugin.BAND_PRESETS_KEY,
			"1m, 10m, 100m");
		// The one thing saveOptions does that this must not: the service is never told, because it decides
		// nothing about the chips.
		verify(service, never()).setOptions(any());
		verify(service, never()).setFilter(any());

		// Nothing to save is not a crash, and writes nothing more.
		prefs.savePresets(null);
		verify(cm, times(1)).setConfiguration(anyString(), anyString(), anyString());
		verify(cm, never()).setConfiguration(anyString(), anyString(), any(Object.class));

		// No manager at all (a field never injected) is a no-op rather than an NPE.
		set(plugin, "configManager", null);
		plugin.configPrefs().savePresets(BandPresets.DEFAULT);

		// The other three halves of the seam are untouched by any of that.
		assertEquals(plugin.filterFromConfig(), prefs.load());
		assertEquals(plugin.heroFromConfig(), prefs.loadHero());
		assertEquals(plugin.optionsFromConfig(), prefs.loadOptions());
	}

	/**
	 * The boxes' own write does not come back and relabel the chips a second time ({@code prefsWriter}), for the
	 * reason the card's three and the gear's five do not: {@code ConfigManager} posts its {@code ConfigChanged}
	 * on this very thread as the write lands, and the box that was typed into has already applied the value.
	 * One key rather than five, so there is no half-written trio to guard against - what is guarded is a repaint
	 * of four chips that are already right, from the EDT, while the user is still typing in the next box.
	 */
	@Test
	public void theBoxesOwnWriteDoesNotRelabelTheChipsAgain() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		when(config.bandPresets()).thenReturn("100k, 1m, 10m");
		// ConfigManager, as it really behaves: the stored value changes and the event is posted on this very
		// thread before the call returns.
		doAnswer(invocation ->
		{
			when(config.bandPresets()).thenReturn((String) invocation.getArgument(2));
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
				(String) invocation.getArgument(1)));
			return null;
			// The STRING overload: a String value binds to it rather than to the generic one the boolean keys
			// take, and a stub on the wrong overload would never run.
		}).when(cm).setConfiguration(anyString(), anyString(), anyString());

		final BandPresets typed = BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L);
		plugin.configPrefs().savePresets(typed);
		onEdt(() ->
		{
		});
		verify(panel, never()).applyPresets(any());

		// The guard is over as soon as the save is: a line typed into RuneLite's own settings panel still lands.
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.BAND_PRESETS_KEY));
		onEdt(() ->
		{
		});
		verify(panel).applyPresets(typed);
	}

	/**
	 * Z1: the sidebar opens on the stored presets. The panel is built with the defaults in it, so a user who cut
	 * their chips to 1m / 10m / 100m and relaunched would otherwise read 100k+ / 1m+ / 10m+ until the first
	 * {@code ConfigChanged} - and on a launch there is none.
	 */
	@Test
	public void theSidebarOpensOnTheStoredPresets() throws Exception
	{
		final Fixture f = new Fixture(false);
		when(f.config.bandPresets()).thenReturn("100m, 1m, 10m");
		onEdt(f.plugin::startUp);

		final BankPriceMovementPanel panel = (BankPriceMovementPanel) field(f.plugin, "panel");
		assertNotNull(panel);
		assertEquals(BandPresets.of(1_000_000L, 10_000_000L, 100_000_000L), panel.presets());
		// The service was NOT told - it has no opinion about the chips - and the band is still the stored one.
		final PriceService service = (PriceService) field(f.plugin, "service");
		assertNotNull(service);
		assertEquals(f.plugin.filterFromConfig(), service.filter());
		onEdt(f.plugin::shutDown);
	}

	// --------------------------------------- addendum AA's price fold (AA1): the fifth ConfigChanged road

	/**
	 * AA1: the key that landed fifteenth, and is the fourteenth since addendum AO, as the shape the header opens
	 * in. A plain boolean read straight off the config - the
	 * seam's {@code Boolean} exists so a {@code Prefs} with NOTHING stored can say so and be given the shipped
	 * default, and this plugin always has an answer, {@code ConfigManager} having written the interface default
	 * over a fresh profile before any of this runs.
	 */
	@Test
	public void theFoldIsReadStraightOffTheStoredKey() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "config", config);

		when(config.foldOpen()).thenReturn(true);
		assertTrue(plugin.foldOpenFromConfig());
		when(config.foldOpen()).thenReturn(false);
		assertFalse(plugin.foldOpenFromConfig());

		// ...and the pref seam hands the panel the same answer, boxed, never null.
		set(plugin, "configManager", mock(ConfigManager.class));
		assertEquals(Boolean.FALSE, plugin.configPrefs().loadFoldOpen());
		when(config.foldOpen()).thenReturn(true);
		assertEquals(Boolean.TRUE, plugin.configPrefs().loadFoldOpen());
	}

	/**
	 * AA1: the fold takes a FIFTH road, the fourth's twin - the panel and nobody else. Not the filter's: the fold
	 * is where a band is TYPED, never a bound, so a {@code setFilter} here would rebuild the whole list because a
	 * user folded the controls away. Not the option road: no figure moves. Not the card's.
	 */
	@Test
	public void aFoldChangeReshapesTheHeaderAndRecomputesNothing() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		when(config.foldOpen()).thenReturn(false);

		final String key = BankPriceMovementPlugin.FOLD_OPEN_KEY;
		assertEquals("foldOpen", key);
		assertTrue(BankPriceMovementPlugin.isFoldKey(key));
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
		// The widget half is queued onto the EDT; drain it before looking.
		onEdt(() ->
		{
		});
		// setFoldOpen, the road that writes nothing back - never pressFold, which is the band button's own press
		// and would store the value the settings page just stored.
		verify(panel).setFoldOpen(false);
		verify(panel, never()).pressFold(anyBoolean());
		verify(panel, never()).toggleFold();
		verify(service, never()).setFilter(any());
		verify(service, never()).setOptions(any());
		verify(panel, never()).applyFilter(any());
		verify(panel, never()).applyOptions(any());
		verify(panel, never()).applyPresets(any());
		verify(panel, never()).applyHeroVisibility(any());

		// Ticked back on, and the panel follows the other way.
		when(config.foldOpen()).thenReturn(true);
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
		onEdt(() ->
		{
		});
		verify(panel).setFoldOpen(true);

		// The road is this key alone: nothing else in the group is mistaken for it, and nothing of it for them.
		assertFalse(BankPriceMovementPlugin.isFoldKey("gpMin"));
		assertFalse(BankPriceMovementPlugin.isFoldKey(BankPriceMovementPlugin.BAND_PRESETS_KEY));
		assertFalse(BankPriceMovementPlugin.isFoldKey(BankPriceMovementPlugin.COUNT_CASH_KEY));
		assertFalse(BankPriceMovementPlugin.isFoldKey(null));
		assertFalse(BankPriceMovementPlugin.isPresetKey(key));
		assertFalse(BankPriceMovementPlugin.isOptionKey(key));
		assertFalse(BankPriceMovementPlugin.isHeroKey(key));

		// After shutDown there is no sidebar to reshape, and the event must not throw.
		set(plugin, "panel", null);
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
	}

	/**
	 * AA1: the band button writes through the same {@code Prefs} seam every other widget uses, so RuneLite's
	 * settings page shows the switch it left. ONE key and no service, the shape {@code savePresets} has - the
	 * list does not depend on whether the controls that set the band are on screen.
	 */
	@Test
	public void theFoldPrefSeamReadsAndWritesTheOneKeyAndTellsNobodyElse() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		final PriceService service = mock(PriceService.class);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		set(plugin, "service", service);
		when(config.foldOpen()).thenReturn(true);

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		assertEquals(Boolean.TRUE, prefs.loadFoldOpen());

		prefs.saveFoldOpen(false);
		verify(cm).setConfiguration(BankPriceMovementConfig.GROUP, BankPriceMovementPlugin.FOLD_OPEN_KEY, false);
		prefs.saveFoldOpen(true);
		verify(cm).setConfiguration(BankPriceMovementConfig.GROUP, BankPriceMovementPlugin.FOLD_OPEN_KEY, true);
		// The one thing saveOptions does that this must not: the service is never told, because whether the
		// controls are drawn decides nothing about the list.
		verify(service, never()).setOptions(any());
		verify(service, never()).setFilter(any());
		// Two writes, and both of them the fold's own key.
		verify(cm, times(2)).setConfiguration(anyString(), anyString(), any(Object.class));
		verify(cm, never()).setConfiguration(anyString(), anyString(), anyString());

		// No manager at all (a field never injected) is a no-op rather than an NPE.
		set(plugin, "configManager", null);
		plugin.configPrefs().saveFoldOpen(false);

		// The other four halves of the seam are untouched by any of that.
		assertEquals(plugin.filterFromConfig(), prefs.load());
		assertEquals(plugin.heroFromConfig(), prefs.loadHero());
		assertEquals(plugin.optionsFromConfig(), prefs.loadOptions());
		assertEquals(plugin.presetsFromConfig(), prefs.loadPresets());
	}

	/**
	 * The band button's own write does not come back and re-lay the header a second time ({@code prefsWriter}),
	 * for the reason the boxes' write does not: {@code ConfigManager} posts its {@code ConfigChanged} on this very
	 * thread as the write lands, and the press that made the change has already taken the fold out of the header.
	 */
	@Test
	public void theBandButtonsOwnWriteDoesNotReshapeTheHeaderAgain() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		when(config.foldOpen()).thenReturn(true);
		// ConfigManager, as it really behaves: the stored value changes and the event is posted on this very
		// thread before the call returns.
		doAnswer(invocation ->
		{
			when(config.foldOpen()).thenReturn((Boolean) invocation.getArgument(2));
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
				(String) invocation.getArgument(1)));
			return null;
		}).when(cm).setConfiguration(anyString(), anyString(), any(Object.class));

		plugin.configPrefs().saveFoldOpen(false);
		onEdt(() ->
		{
		});
		verify(panel, never()).setFoldOpen(anyBoolean());

		// The guard is over as soon as the save is: a tick on RuneLite's own settings panel still lands.
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP,
			BankPriceMovementPlugin.FOLD_OPEN_KEY));
		onEdt(() ->
		{
		});
		verify(panel).setFoldOpen(false);
	}

	/**
	 * AA1: the sidebar opens on the stored fold. The panel asks the prefs seam as it builds, and startUp says the
	 * stored answer out loud - so a reader who shut the fold and relaunched finds it shut rather than open until
	 * the first {@code ConfigChanged}, of which a launch has none.
	 */
	@Test
	public void theSidebarOpensOnTheStoredFold() throws Exception
	{
		final Fixture shut = new Fixture(false);
		when(shut.config.foldOpen()).thenReturn(false);
		onEdt(shut.plugin::startUp);
		final BankPriceMovementPanel closed = (BankPriceMovementPanel) field(shut.plugin, "panel");
		assertNotNull(closed);
		assertFalse("a fold the reader shut stays shut across a relaunch (AA1)", closed.foldOpen());
		// The service was NOT told - it has no opinion about the header's shape - and the band is the stored one.
		final PriceService service = (PriceService) field(shut.plugin, "service");
		assertNotNull(service);
		assertEquals(shut.plugin.filterFromConfig(), service.filter());
		onEdt(shut.plugin::shutDown);

		// ...and the shipped default is the other way: a fresh profile opens on the preset price ranges, which is
		// the whole of what the user asked for.
		final Fixture fresh = new Fixture(false);
		onEdt(fresh.plugin::startUp);
		final BankPriceMovementPanel open = (BankPriceMovementPanel) field(fresh.plugin, "panel");
		assertNotNull(open);
		assertTrue(open.foldOpen());
		onEdt(fresh.plugin::shutDown);
	}

	/**
	 * O1: the {@code look} key is swept at startUp. Addendum N stored one of two designs there; the user picked
	 * Ticker, the item is gone, and a value nothing reads and no config panel lists is not something a user
	 * could clear by hand.
	 */
	@Test
	public void aStoredLookIsUnsetAtStartUp() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "configManager", cm);
		final String g = BankPriceMovementConfig.GROUP;
		final String key = BankPriceMovementPlugin.LEGACY_LOOK_KEY;
		assertEquals("look", key);

		when(cm.getConfiguration(g, key)).thenReturn("TICKER");
		plugin.unstickLook();
		verify(cm).unsetConfiguration(g, key);

		// A profile that never saw addendum N has nothing there, and nothing is written.
		when(cm.getConfiguration(g, key)).thenReturn(null);
		plugin.unstickLook();
		when(cm.getConfiguration(g, key)).thenReturn("");
		plugin.unstickLook();
		verify(cm, times(1)).unsetConfiguration(anyString(), anyString());

		// A manager that throws on either call is logged and swallowed; startUp goes on.
		when(cm.getConfiguration(g, key)).thenThrow(new IllegalStateException());
		plugin.unstickLook();

		// And no manager at all (a field never injected) is a no-op rather than an NPE.
		set(plugin, "configManager", null);
		plugin.unstickLook();
	}

	/**
	 * AO1: the {@code holdingOnRows} key is swept at startUp, on {@link #aStoredLookIsUnsetAtStartUp()}'s model
	 * and for its reason. Addendum Q stored there whether a row printed its per-ITEM reading or its per-STACK one
	 * (Q6); addendum AN's three-line row prints BOTH, so by the time the user saw it in a client the switch
	 * reached nothing drawn and he asked for it to go ("if it doesnt do anything anymore then remove it",
	 * 2026-09-20). The item is deleted, so a profile that ever touched the gear menu holds a true or a false that
	 * nothing reads and that RuneLite's config panel will never list for the user to clear by hand.
	 *
	 * <p>This sweep is the whole reason deleting a STORED key is safe, so it is pinned in both directions: the
	 * stored value goes, and a profile that has none is left completely alone. The second half is the one that
	 * would bite - an unset of a key holding nothing posts a {@code ConfigChanged} on every launch for ever, and
	 * writes a line to the log with it.
	 *
	 * <p>Neither of the two values it can hold survives, because neither means anything any more: the reading it
	 * used to pick is drawn on every row now, and the one thing it still decided - which figure
	 * {@link SortMode#GP_MOVE} compares - is settled the other way for good (the gp column follows the STACK
	 * whatever a profile says). So the test the sweep applies must refuse a stored "true" exactly as it refuses
	 * a stored "false", and both are driven below.
	 */
	@Test
	public void aStoredHoldingOnRowsIsUnsetAtStartUp() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "configManager", cm);
		final String g = BankPriceMovementConfig.GROUP;
		final String key = BankPriceMovementPlugin.LEGACY_HOLDING_KEY;
		assertEquals("holdingOnRows", key);

		when(cm.getConfiguration(g, key)).thenReturn("true");
		plugin.unstickHolding();
		verify(cm).unsetConfiguration(g, key);

		// The other value the switch could hold is no more readable than the first: it named a reading of the
		// row, and the row has no reading to choose any more.
		when(cm.getConfiguration(g, key)).thenReturn("false");
		plugin.unstickHolding();
		verify(cm, times(2)).unsetConfiguration(g, key);

		// A profile that never touched addendum Q's gear menu has nothing there, and nothing is written.
		when(cm.getConfiguration(g, key)).thenReturn(null);
		plugin.unstickHolding();
		when(cm.getConfiguration(g, key)).thenReturn("");
		plugin.unstickHolding();
		verify(cm, times(2)).unsetConfiguration(anyString(), anyString());

		// A manager that throws on either call is logged and swallowed; startUp goes on.
		when(cm.getConfiguration(g, key)).thenThrow(new IllegalStateException());
		plugin.unstickHolding();

		// And no manager at all (a field never injected) is a no-op rather than an NPE.
		set(plugin, "configManager", null);
		plugin.unstickHolding();
	}

	/**
	 * AJ, AO1: {@code startUp} really does run the sweeps it has, and runs them on the keys the deleted items
	 * used. {@link #aStoredLookIsUnsetAtStartUp()} and {@link #aStoredHoldingOnRowsIsUnsetAtStartUp()} drive two
	 * of them directly and so cannot see whether anything calls them; this is the assertion that catches a sweep
	 * WRITTEN and never CALLED, which is the way a start-up repair usually fails.
	 *
	 * <p>There are THREE of them since addendum AO - the window's repair (K9), the {@code look} key (O1) and now
	 * {@code holdingOnRows} (AO1) - and not four. Addendum AI added {@code unstickHoverText} when it deleted the
	 * hover switch, and addendum AJ put the switch back at the user's word ("where is the show hover text box and
	 * wording and default 'off' setting? it should be the row above OK", 2026-09-20) - so {@code showHoverText}
	 * has a config item behind it again and must NOT be swept: a sweep of a live key would clear the reader's
	 * own answer on every launch. {@code BankPriceMovementConfigTest} pins the item; this pins that start-up
	 * leaves its stored value alone.
	 */
	@Test
	public void startUpSweepsTheDeletedKeysAndOnlyThose() throws Exception
	{
		final Fixture f = new Fixture(false);
		final String g = BankPriceMovementConfig.GROUP;
		when(f.configManager.getConfiguration(g, BankPriceMovementPlugin.LEGACY_LOOK_KEY)).thenReturn("TICKER");
		when(f.configManager.getConfiguration(g, "window")).thenReturn("H24");
		// AO1: a profile that ticked addendum Q's row switch on. The item is gone, so the value goes with it.
		when(f.configManager.getConfiguration(g, BankPriceMovementPlugin.LEGACY_HOLDING_KEY)).thenReturn("true");
		// A profile that turned the hover text on. The value is the user's, and start-up must not touch it.
		when(f.configManager.getConfiguration(g, BankPriceMovementPlugin.SHOW_HOVER_TEXT_KEY)).thenReturn("true");

		onEdt(f.plugin::startUp);

		verify(f.configManager).unsetConfiguration(g, BankPriceMovementPlugin.LEGACY_LOOK_KEY);
		verify(f.configManager).unsetConfiguration(g, "window");
		verify(f.configManager).unsetConfiguration(g, BankPriceMovementPlugin.LEGACY_HOLDING_KEY);
		verify(f.configManager, never()).unsetConfiguration(g, BankPriceMovementPlugin.SHOW_HOVER_TEXT_KEY);
		onEdt(f.plugin::shutDown);
	}

	/**
	 * ...and the other direction, which matters more: a fresh profile has nothing stored under any of the swept
	 * keys, and start-up must not write to the config at all. An unset of a key that holds nothing would post a
	 * {@code ConfigChanged} on every launch for ever.
	 */
	@Test
	public void aFreshProfileIsSweptWithoutAnyWrite() throws Exception
	{
		final Fixture f = new Fixture(false);
		onEdt(f.plugin::startUp);
		verify(f.configManager, never()).unsetConfiguration(anyString(), anyString());
		onEdt(f.plugin::shutDown);
	}

	@Test
	public void clientShutdownWaitsForThePendingWrites() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final Future<?> pending = mock(Future.class);
		when(service.flush()).thenAnswer(invocation -> pending);
		set(plugin, "service", service);

		final ClientShutdown event = new ClientShutdown();
		plugin.onClientShutdown(event);
		assertEquals("the plugin must hand its writes to the exit or the last bank is lost",
			1, event.getTasks().size());
		assertTrue(event.getTasks().contains(pending));

		// After shutDown there is nothing to wait for, and asking must not throw.
		set(plugin, "service", null);
		plugin.onClientShutdown(new ClientShutdown());
	}

	// ------------------------------------------- addendum Y: what the player is carrying (Y1, Y2)

	/**
	 * Y2 (a): one bank event, THREE containers, one publish. The bank is read as it always was, and in the
	 * same pass the client is asked for the inventory ({@code InventoryID.INV} = 93) and the worn gear
	 * ({@code WORN} = 94) - which it can only be asked for here, on the client thread this handler runs on.
	 *
	 * <p>ONE {@code setBank} is the half that matters as much as the read: three publishes would be three
	 * recomputes and three chances for the panel to draw a bank with half a player in it.
	 */
	@Test
	public void theBankEventReadsTheInventoryAndTheWornGearInTheSamePass() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(77L);

		final ItemContainer bank = mock(ItemContainer.class);
		final Item[] banked = new Item[]{new Item(4151, 3)};
		when(bank.getItems()).thenReturn(banked);
		final ItemContainer inventory = mock(ItemContainer.class);
		final Item[] held = new Item[]{new Item(4151, 1)};
		when(inventory.getItems()).thenReturn(held);
		final ItemContainer gear = mock(ItemContainer.class);
		final Item[] equipped = new Item[]{new Item(1215, 1)};
		when(gear.getItems()).thenReturn(equipped);
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		when(client.getItemContainer(InventoryID.WORN)).thenReturn(gear);
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(new BankSnapshot());

		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, bank));

		verify(reader).read(eq(banked), eq(77L), eq(RuneScapeProfileType.STANDARD.name()), anyLong());
		// The ids are RuneLite's own gameval constants and nothing this plugin made up.
		assertEquals(93, InventoryID.INV);
		assertEquals(94, InventoryID.WORN);
		verify(reader).readContainers(eq(held), eq(equipped), anyLong());
		assertEquals("one event, one snapshot", 1,
			mockingDetails(service).getInvocations().stream()
			.filter(i -> "setBank".equals(i.getMethod().getName())).count());
	}

	/**
	 * Y2: a container the client has never cached answers null ({@code Client.getItemContainer} is
	 * {@code @Nullable}), and an empty hand is not a failure - the reader is still asked, and still answers a
	 * snapshot. A player who has never worn anything on this account is exactly that case.
	 */
	@Test
	public void aCarriedContainerTheClientCannotServeIsReadAsNothing() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(77L);
		when(client.getItemContainer(anyInt())).thenReturn(null);
		final ItemContainer bank = mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(new Item[]{new Item(4151, 1)});
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(new BankSnapshot());

		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, bank));

		verify(reader).readContainers(isNull(), isNull(), anyLong());
		verify(service).setBank(any(BankSnapshot.class));
	}

	/**
	 * Y2 (b): Refresh re-reads what the player is carrying. The hook hops to the client thread (an item
	 * container may be read nowhere else) and republishes the STORED bank part with the fresh carried part,
	 * so a Refresh can move a row's quantity and can never invent a stack the player has not banked.
	 *
	 * <p>Three refusals are pinned beside it, because each of them would be a wrong bank rather than a missing
	 * one: with no capture of this session AND no bank in the service there is nothing to re-stamp; at the
	 * login screen the client cannot say whose inventory it is holding; and after {@code shutDown} there is no
	 * plugin left to read one. (The service's own snapshot is the disk fallback, and it has its own test.)
	 */
	@Test
	public void refreshRereadsWhatThePlayerIsCarryingAndRepublishesTheStoredBank() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		final ClientThread clientThread = mock(ClientThread.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		set(plugin, "clientThread", clientThread);
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(77L);
		when(reader.readContainers(any(), any(), anyLong())).thenReturn(
			new BankReader.Carried(Collections.emptyList(), Collections.emptyList(), 0L, 5L));

		// Nothing has been captured yet and the service holds no bank either (the mock answers null): a
		// Refresh here must read nothing and publish nothing.
		plugin.readCarriedOnClientThread();
		verify(reader, never()).readContainers(any(), any(), anyLong());
		verify(service, never()).setBank(any(BankSnapshot.class));

		// The bank event of Y2 (a) leaves the snapshot this hook re-stamps.
		final ItemContainer bank = mock(ItemContainer.class);
		when(bank.getItems()).thenReturn(new Item[]{new Item(4151, 3)});
		when(reader.read(any(), anyLong(), anyString(), anyLong())).thenReturn(new BankSnapshot());
		plugin.onItemContainerChanged(new ItemContainerChanged(BankReader.BANK_CONTAINER_ID, bank));
		verify(service, times(1)).setBank(any(BankSnapshot.class));

		// The hook itself only hops: Refresh is pressed on the EDT, and a container is client-thread only.
		plugin.rereadCarried();
		final ArgumentCaptor<Runnable> hop = ArgumentCaptor.forClass(Runnable.class);
		verify(clientThread).invoke(hop.capture());
		assertEquals("nothing is read until the client thread runs it", 1,
			mockingDetails(service).getInvocations().stream()
			.filter(i -> "setBank".equals(i.getMethod().getName())).count());

		hop.getValue().run();
		verify(reader, times(2)).readContainers(any(), any(), anyLong());
		verify(service, times(2)).setBank(any(BankSnapshot.class));
		// ...and the bank half was NOT read again: one bank event, one read of container 95.
		verify(reader, times(1)).read(any(), anyLong(), anyString(), anyLong());

		// At the login screen the client cannot say whose inventory this is.
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		plugin.readCarriedOnClientThread();
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(-1L);
		plugin.readCarriedOnClientThread();
		verify(service, times(2)).setBank(any(BankSnapshot.class));

		// And after shutDown there is nothing to read with, which is a no-op rather than an NPE.
		set(plugin, "service", null);
		set(plugin, "bankReader", null);
		plugin.readCarriedOnClientThread();
		set(plugin, "clientThread", null);
		plugin.rereadCarried();
	}

	/**
	 * Y2 (b), the case the plugin's own cache cannot serve: a player who has logged in and NOT yet opened a
	 * bank is looking at the snapshot the service loaded from disk - carried half and all, as it stood at
	 * their last bank visit - and Refresh is one of the two moments Y2 promises will re-read what they hold.
	 * So the hook falls back to {@link PriceService#bank()}, and re-stamps that.
	 *
	 * <p>Its guard is pinned with it: a snapshot stamped with another account (the window between a hop and
	 * the new account's disk read landing) or another profile is left alone, because the republish is keyed
	 * from the snapshot's own stamps and would write this player's gear into someone else's bank file.
	 */
	@Test
	public void refreshRestampsTheBankTheServiceLoadedWhenThisSessionHasCapturedNone() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankReader reader = mock(BankReader.class);
		final Client client = mock(Client.class);
		set(plugin, "service", service);
		set(plugin, "bankReader", reader);
		set(plugin, "client", client);
		set(plugin, "profileType", "STANDARD");
		when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getAccountHash()).thenReturn(77L);
		when(client.getItemContainer(anyInt())).thenReturn(null);
		when(reader.readContainers(any(), any(), anyLong())).thenReturn(
			new BankReader.Carried(Collections.emptyList(), Collections.emptyList(), 12L, 5L));

		// Another account's snapshot is still in the service (a hop whose disk read has not landed): refused.
		final BankSnapshot theirs = new BankSnapshot(Collections.emptyList(), 1_000L, 88L, "STANDARD", 0L);
		when(service.bank()).thenReturn(theirs);
		plugin.readCarriedOnClientThread();
		verify(service, never()).setBank(any(BankSnapshot.class));

		// This account, but the wrong profile (a leagues bank against a standard login): refused too.
		when(service.bank()).thenReturn(new BankSnapshot(Collections.emptyList(), 1_000L, 77L, "LEAGUE", 0L));
		plugin.readCarriedOnClientThread();
		verify(service, never()).setBank(any(BankSnapshot.class));

		// This account's own disk bank: re-stamped with the fresh carried half, its bank stamps untouched.
		final BankSnapshot mine = new BankSnapshot(Collections.singletonList(item(4151, 3)), 1_000L, 77L,
			"STANDARD", 500L);
		when(service.bank()).thenReturn(mine);
		plugin.readCarriedOnClientThread();
		final ArgumentCaptor<BankSnapshot> published = ArgumentCaptor.forClass(BankSnapshot.class);
		verify(service).setBank(published.capture());
		assertEquals("the bank half is the loaded one", 1, published.getValue().items.size());
		assertEquals("and its capture stamp with it", 1_000L, published.getValue().capturedAtMillis);
		assertEquals("the carried half is the fresh read", 12L, published.getValue().carriedGp);
		// And it is now the cache, so the next Refresh never asks the service again.
		when(service.bank()).thenReturn(theirs);
		plugin.readCarriedOnClientThread();
		verify(service, times(2)).setBank(any(BankSnapshot.class));
	}

	/** One bank stack, for the fallback test above. */
	private static BankItem item(int id, int quantity)
	{
		return new BankItem(id, quantity, "Item " + id, false);
	}

	/**
	 * Y2 (b), the registration: {@code startUp} hands the hook to the service and {@code shutDown} takes it
	 * back, because RuneLite reuses the plugin INSTANCE and a hook left standing would point at a plugin
	 * whose every field has been nulled. The service's own field is read by type rather than by name - what
	 * is being pinned here is that something was registered and then dropped, not how the service stores it.
	 */
	@Test
	public void startUpRegistersTheCarriedReaderAndShutDownDropsIt() throws Exception
	{
		final Fixture f = new Fixture(false);
		onEdt(f.plugin::startUp);
		final PriceService service = (PriceService) field(f.plugin, "service");
		assertNotNull(service);
		final Runnable hook = carriedReaderOf(service);
		assertNotNull("startUp must register the carried reader (Y2)", hook);

		// It is this plugin's hook, and all it does is hop to the client thread.
		hook.run();
		verify(f.clientThread, atLeastOnce()).invoke(any(Runnable.class));

		onEdt(f.plugin::shutDown);
		assertNull("shutDown drops it, because RuneLite reuses the plugin instance", carriedReaderOf(service));
	}

	/** The one {@link Runnable} field {@link PriceService} holds: addendum Y's carried reader (Y2). */
	private static Runnable carriedReaderOf(PriceService service) throws Exception
	{
		Runnable found = null;
		int fields = 0;
		for (Field f : PriceService.class.getDeclaredFields())
		{
			if (!Runnable.class.equals(f.getType()))
			{
				continue;
			}
			fields++;
			f.setAccessible(true);
			found = (Runnable) f.get(service);
		}
		assertEquals("PriceService must hold exactly one Runnable field, the carried reader", 1, fields);
		return found;
	}

	// ---------------------------------------------------------------- config <-> filter

	@Test
	public void theFilterIsBuiltFromTheFiveStoredKeys() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		set(plugin, "config", config);
		when(config.gpMin()).thenReturn(1000);
		when(config.gpMax()).thenReturn(0);
		when(config.sortMode()).thenReturn(SortMode.UNIT_PRICE);
		when(config.sortDescending()).thenReturn(true);
		when(config.window()).thenReturn(MovementWindow.D30);

		assertEquals(new RowFilter(1000, 0, SortMode.UNIT_PRICE, true, MovementWindow.D30),
			plugin.filterFromConfig());
	}

	/**
	 * {@code Prefs.save} is the widgets' write path: five {@code setConfiguration} calls into our group, which
	 * post the {@code ConfigChanged} that brings the other half of the switch along. gp bounds are stored as
	 * {@code int}, so an over-large filter value is clamped rather than wrapped.
	 */
	@Test
	public void prefsWriteEveryKeyThroughConfigManager() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		when(config.sortMode()).thenReturn(SortMode.PERCENT_MOVE);
		when(config.window()).thenReturn(MovementWindow.DEFAULT);

		final BankPriceMovementPanel.Prefs prefs = plugin.configPrefs();
		prefs.save(new RowFilter(250, Long.MAX_VALUE, SortMode.GP_MOVE, false, MovementWindow.D7));

		final String g = BankPriceMovementConfig.GROUP;
		verify(cm).setConfiguration(g, "gpMin", (Object) 250);
		verify(cm).setConfiguration(g, "gpMax", (Object) Integer.MAX_VALUE);
		verify(cm).setConfiguration(g, "sortMode", (Object) SortMode.GP_MOVE);
		verify(cm).setConfiguration(g, "sortDescending", (Object) Boolean.FALSE);
		verify(cm).setConfiguration(g, "window", (Object) MovementWindow.D7);

		// load() is the same read the plugin starts from.
		assertEquals(plugin.filterFromConfig(), prefs.load());
		// A null filter is not a crash and writes nothing more.
		prefs.save(null);
		verify(cm, times(5)).setConfiguration(anyString(), anyString(), any(Object.class));
	}

	/**
	 * The five writes of one {@code Prefs.save} are ONE change of mind. {@code ConfigManager} posts a
	 * {@code ConfigChanged} synchronously as each key lands (clone ConfigManager.java:874-901), so a two-key
	 * change - "Clear price range" is {@code gpMin} then {@code gpMax} - used to re-enter
	 * {@code onConfigChanged} after the FIRST write and hand {@link PriceService} a filter built half from the
	 * new value and half from the old one, which recomputed and published a list capped at the band the user
	 * had just cleared. The widget has already applied the whole filter itself
	 * ({@code BankPriceMovementPanel.changeFilter}: {@code prefs.save} then {@code service.setFilter}), so the
	 * round trip has nothing to bring back and is skipped - for THIS thread's writes only.
	 */
	@Test
	public void theSidebarsOwnWritesDoNotFeedAHalfUpdatedFilterBack() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceService service = mock(PriceService.class);
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "service", service);
		set(plugin, "panel", panel);
		set(plugin, "config", config);
		set(plugin, "configManager", cm);
		// The band the user is clearing, and the rest of the stored filter.
		when(config.gpMin()).thenReturn(100_000);
		when(config.gpMax()).thenReturn(5_000_000);
		when(config.sortMode()).thenReturn(SortMode.PERCENT_MOVE);
		when(config.sortDescending()).thenReturn(true);
		when(config.window()).thenReturn(MovementWindow.D1);
		// ConfigManager, as it really behaves: the stored value changes and the event is posted on this very
		// thread before the next key is written.
		doAnswer(invocation ->
		{
			final String key = invocation.getArgument(1);
			final Object value = invocation.getArgument(2);
			if ("gpMin".equals(key))
			{
				when(config.gpMin()).thenReturn((Integer) value);
			}
			else if ("gpMax".equals(key))
			{
				when(config.gpMax()).thenReturn((Integer) value);
			}
			plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, key));
			return null;
		}).when(cm).setConfiguration(anyString(), anyString(), any(Object.class));

		plugin.configPrefs().save(new RowFilter(0, 0, SortMode.PERCENT_MOVE, true, MovementWindow.D1));

		// Not "the right filter once": nothing at all. The panel makes that call itself, with the whole filter.
		verify(service, never()).setFilter(any());
		onEdt(() ->
		{
		});
		verify(panel, never()).applyFilter(any());

		// And the guard is over as soon as the save is: a change made in RuneLite's settings panel still lands.
		plugin.onConfigChanged(configChanged(BankPriceMovementConfig.GROUP, "gpMin"));
		verify(service).setFilter(new RowFilter(0, 0, SortMode.PERCENT_MOVE, true, MovementWindow.D1));
	}

	// ---------------------------------------------------------------- addendum K (K9, K11)

	/**
	 * K9. A profile written by the trade-price build says {@code window=H24}, which no longer names a
	 * {@link MovementWindow} constant, so the guard clears the key and lets the interface default apply.
	 *
	 * <p>Without it RuneLite limps rather than fails: {@code ConfigInvocationHandler.invoke} catches the
	 * {@code IllegalArgumentException} that {@code Enum.valueOf} throws, logs at WARN and calls the default
	 * method - but does NOT cache the result (ConfigInvocationHandler.java:117-131), so every read of the
	 * window throws and logs again for the life of the client, while
	 * {@code ConfigPanel.createComboBox}'s own catch (ConfigPanel.java:635-644) leaves the combo box showing
	 * its FIRST constant with the stale string still on disk.
	 */
	@Test
	public void aWindowFromTheTradeEraIsUnsetAtStartUp() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "configManager", cm);
		when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenReturn("H24");

		plugin.unstickWindow();
		verify(cm).unsetConfiguration(BankPriceMovementConfig.GROUP, "window");
	}

	/** K9: a value that IS a live constant is left exactly where it is - the guard must not reset a choice. */
	@Test
	public void aWindowThisBuildKnowsIsLeftAlone() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "configManager", cm);

		for (MovementWindow w : MovementWindow.values())
		{
			when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenReturn(w.name());
			plugin.unstickWindow();
		}
		verify(cm, never()).unsetConfiguration(anyString(), anyString());
	}

	/**
	 * K9: the guard matches the STORED spelling, which is {@code name()} - so the label "1d" is not a stored
	 * value and is swept, and a fresh profile (nothing stored) is not touched. Neither a missing manager nor a
	 * manager that throws may take startUp down with it.
	 */
	@Test
	public void theGuardMatchesTheStoredNameAndSurvivesEveryOtherAnswer() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ConfigManager cm = mock(ConfigManager.class);
		set(plugin, "configManager", cm);

		// A fresh profile: nothing stored, nothing to do.
		when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenReturn(null);
		plugin.unstickWindow();
		when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenReturn("");
		plugin.unstickWindow();
		verify(cm, never()).unsetConfiguration(anyString(), anyString());

		// The label is not the stored spelling: ConfigManager would fail to unmarshal "1d" just as it fails
		// on "H24", so it goes the same way.
		when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenReturn("1d");
		plugin.unstickWindow();
		verify(cm).unsetConfiguration(BankPriceMovementConfig.GROUP, "window");

		// A manager that throws on either call is logged and swallowed; startUp goes on.
		when(cm.getConfiguration(BankPriceMovementConfig.GROUP, "window")).thenThrow(new IllegalStateException());
		plugin.unstickWindow();

		// And no manager at all (a field never injected) is a no-op rather than an NPE.
		set(plugin, "configManager", null);
		plugin.unstickWindow();
	}

	/**
	 * K11. The stale-file sweep is disk work, so startUp hands it to the executor instead of running it on the
	 * EDT the client calls startUp from. Driven here with a MOCKED store: since addendum AD the real store gets
	 * its directory from {@code Plugin.getPluginDirectory()}, which roots at
	 * {@code ~/.runelite/plugin-data/<internalName>/}, so running the task startUp itself queued would delete
	 * files out of the developer's own data folder. No test here ever calls that method - the one sweep test
	 * that needs real files ({@link #theSweepTakesTheTradeEraFilesAndLeavesTheRevisionIndex()}) roots its own
	 * {@code Filepath} at a temporary folder instead.
	 */
	@Test
	public void theStaleFileSweepRunsOnTheExecutorAndNotOnTheEdt() throws Exception
	{
		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final PriceStore store = mock(PriceStore.class);
		final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		set(plugin, "store", store);
		set(plugin, "executor", executor);
		when(store.deleteStaleFiles()).thenReturn(3);

		plugin.sweepStaleFiles();
		// Queued, not run: the EDT has not touched the disk.
		verify(store, never()).deleteStaleFiles();

		final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).submit(task.capture());
		task.getValue().run();
		verify(store).deleteStaleFiles();

		// A store that blows up on the sweep must not kill the executor's thread.
		when(store.deleteStaleFiles()).thenThrow(new IllegalStateException());
		task.getValue().run();

		// And a plugin with no store (shut down before the sweep was even queued) queues nothing.
		set(plugin, "store", null);
		plugin.sweepStaleFiles();
		verify(executor, times(1)).submit(any(Runnable.class));
	}

	/**
	 * L4: the sweep the plugin queues must not take {@code revindex.json} with it. The revision index is the one
	 * file that lets a baseline be PICKED without a request, so deleting it would cost a needless fetch on every
	 * launch and leave every window without a baseline until that fetch landed - while the trade-era files
	 * beside it must still go (K11). Driven over a REAL {@link PriceStore} in a temporary directory, because the
	 * rule being checked is which FILES survive, and a mocked store cannot show that. Since addendum AD the
	 * store takes a {@code Filepath}, so the temporary folder is rooted as one ({@link TestFilepaths#rooted})
	 * and the plain {@link File} probes below read the very same directory back.
	 */
	@Test
	public void theSweepTakesTheTradeEraFilesAndLeavesTheRevisionIndex() throws Exception
	{
		final File root = dir.getRoot();
		write(new File(root, PriceStore.REVINDEX_FILE), "{\"fetchedAtMillis\":1,\"revisions\":[]}");
		write(new File(root, PriceStore.LEGACY_LATEST_FILE), "{\"points\":{}}");
		write(new File(root, "baseline-H24.json"), "{\"points\":{}}");
		write(new File(root, "bank-4242-STANDARD.json"), "{\"items\":[]}");
		final PriceStore store = new PriceStore(new Gson(), TestFilepaths.rooted(root));

		final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
		set(plugin, "store", store);
		set(plugin, "executor", executor);
		plugin.sweepStaleFiles();
		final ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
		verify(executor).submit(task.capture());
		task.getValue().run();

		assertTrue("the revision index survives the sweep (L4)", new File(root, PriceStore.REVINDEX_FILE).isFile());
		assertTrue("and so does the remembered bank", new File(root, "bank-4242-STANDARD.json").isFile());
		assertFalse("the trade-era spot map goes (K11)", new File(root, PriceStore.LEGACY_LATEST_FILE).isFile());
		assertFalse("and so does a baseline of a window that no longer exists",
			new File(root, "baseline-H24.json").isFile());
	}

	private static void write(File file, String json) throws Exception
	{
		Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Java source with every {@code //} and {@code /* *}{@code /} comment blanked and every newline kept, so
	 * {@link #hubBlockersAreAbsentFromTheWholePackage()} scans CODE and still reports a real line number.
	 * String and character literals are left intact - a URL's {@code //} must not start a comment, and
	 * {@code System.getProperty("user.home")} is a blocker precisely because of what is inside the quotes.
	 */
	/**
	 * The Hub descriptor, off the test classpath. It speaks for what is SHIPPED, so it is read rather than
	 * described: a test that assumed its contents would still pass after somebody changed them.
	 */
	private static Properties hubDescriptor() throws Exception
	{
		final Properties p = new Properties();
		try (InputStream in = BankPriceMovementWiringTest.class.getResourceAsStream("/runelite-plugin.properties"))
		{
			assertNotNull("runelite-plugin.properties is not on the test classpath", in);
			p.load(in);
		}
		return p;
	}

	/**
	 * {@code src/main/java/com/bankpricemovement}, relative to the working directory Gradle runs tests from.
	 * Fails loudly rather than answering nothing: a scan that reads no files passes every rule it has.
	 */
	private static Path shippedPackage()
	{
		final Path root = Paths.get("src", "main", "java", "com", "bankpricemovement");
		assertTrue("wrong working directory - no package at " + root.toAbsolutePath(), Files.isDirectory(root));
		return root;
	}

	/** One shipped source with its comments blanked, so a scan reads the code and not the prose beside it. */
	private static String shippedSource(String fileName) throws Exception
	{
		final Path source = shippedPackage().resolve(fileName);
		assertTrue(fileName + " is not where this test looks for it: " + source.toAbsolutePath(),
			Files.isRegularFile(source));
		return withoutComments(new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
	}

	/** Every shipped source of the package, comments blanked, keyed by file name and in that order. */
	private static Map<String, String> shippedSources() throws Exception
	{
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(shippedPackage()))
		{
			files = walk.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList());
		}
		final Map<String, String> out = new LinkedHashMap<>();
		for (Path source : files)
		{
			// Keyed by the bare name, which is only sound while the package is flat: two sources sharing one
			// name would leave the second scanned and the first silently gone from the evidence.
			final String previous = out.put(source.getFileName().toString(),
				withoutComments(new String(Files.readAllBytes(source), StandardCharsets.UTF_8)));
			assertNull("two sources are named " + source.getFileName() + ": one of them would go unscanned",
				previous);
		}
		return out;
	}

	/** How many times a pattern matches - every occurrence, because one fixed line does not fix its twin. */
	private static int occurrences(Pattern pattern, String code)
	{
		final Matcher m = pattern.matcher(code);
		int found = 0;
		while (m.find())
		{
			found++;
		}
		return found;
	}

	private static String withoutComments(String code)
	{
		final StringBuilder out = new StringBuilder(code.length());
		boolean inLine = false;
		boolean inBlock = false;
		boolean inString = false;
		boolean inChar = false;
		for (int i = 0; i < code.length(); i++)
		{
			final char c = code.charAt(i);
			final char next = i + 1 < code.length() ? code.charAt(i + 1) : '\0';
			if (inLine)
			{
				if (c == '\n')
				{
					inLine = false;
					out.append(c);
				}
				continue;
			}
			if (inBlock)
			{
				if (c == '\n')
				{
					out.append(c);
				}
				else if (c == '*' && next == '/')
				{
					inBlock = false;
					i++;
				}
				continue;
			}
			if (inString || inChar)
			{
				out.append(c);
				if (c == '\\')
				{
					if (i + 1 < code.length())
					{
						out.append(next);
						i++;
					}
					continue;
				}
				inString = inString && c != '"';
				inChar = inChar && c != '\'';
				continue;
			}
			if (c == '/' && next == '/')
			{
				inLine = true;
				i++;
				continue;
			}
			if (c == '/' && next == '*')
			{
				inBlock = true;
				i++;
				continue;
			}
			inString = c == '"';
			inChar = c == '\'';
			out.append(c);
		}
		return out.toString();
	}

	/**
	 * K11 through the front door: startUp really does queue the sweep on the shared executor.
	 * {@code atLeastOnce} rather than an exact count - the service may queue work of its own on the same
	 * executor, and this test is about the sweep being queued at all.
	 */
	@Test
	public void startUpQueuesTheSweep() throws Exception
	{
		final Fixture f = new Fixture(false);
		onEdt(f.plugin::startUp);
		verify(f.executor, atLeastOnce()).submit(any(Runnable.class));
		onEdt(f.plugin::shutDown);
	}

	// ---------------------------------------------------------------- fixtures

	/** Everything RuneLite injects, mocked, with the plugin's private fields already filled. */
	private static final class Fixture
	{
		private final BankPriceMovementPlugin plugin = new BankPriceMovementPlugin();
		private final Client client = mock(Client.class);
		private final ClientThread clientThread = mock(ClientThread.class);
		private final ClientToolbar clientToolbar = mock(ClientToolbar.class);
		private final ItemManager itemManager = mock(ItemManager.class);
		private final ConfigManager configManager = mock(ConfigManager.class);
		private final BankPriceMovementConfig config = mock(BankPriceMovementConfig.class);
		private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

		private Fixture(boolean developerMode) throws Exception
		{
			when(client.getWorldType()).thenReturn(EnumSet.noneOf(WorldType.class));
			when(config.sortMode()).thenReturn(SortMode.PERCENT_MOVE);
			when(config.window()).thenReturn(MovementWindow.DEFAULT);
			when(config.sortDescending()).thenReturn(true);
			when(config.showBankValue()).thenReturn(true);
			when(config.showBankMoveGp()).thenReturn(true);
			when(config.showBankMovePct()).thenReturn(true);
			// AA1: the shipped default, so a fixture that says nothing about the fold gets the sidebar a fresh
			// profile gets. A mocked proxy would answer false, which is the one state no new user ever sees.
			when(config.foldOpen()).thenReturn(true);
			// Nothing may run: the executor is a mock, and every task it is handed is dropped. The futures it
			// answers are real so a caller that keeps one (flush) has something to keep.
			when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> mock(Future.class));
			when(executor.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
				.thenAnswer(invocation -> mock(ScheduledFuture.class));
			when(executor.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
				.thenAnswer(invocation -> mock(ScheduledFuture.class));
			when(executor.scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class)))
				.thenAnswer(invocation -> mock(ScheduledFuture.class));

			set(plugin, "client", client);
			set(plugin, "clientThread", clientThread);
			set(plugin, "clientToolbar", clientToolbar);
			set(plugin, "itemManager", itemManager);
			set(plugin, "okHttpClient", mock(OkHttpClient.class));
			set(plugin, "gson", new Gson());
			set(plugin, "configManager", configManager);
			set(plugin, "executor", executor);
			set(plugin, "config", config);
			set(plugin, "developerMode", developerMode);
		}
	}

	private static GameStateChanged gameState(GameState state)
	{
		final GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		return e;
	}

	private static ConfigChanged configChanged(String group, String key)
	{
		final ConfigChanged e = new ConfigChanged();
		e.setGroup(group);
		e.setKey(key);
		return e;
	}

	private static void set(BankPriceMovementPlugin plugin, String field, Object value) throws Exception
	{
		final Field f = BankPriceMovementPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private static Object field(BankPriceMovementPlugin plugin, String field) throws Exception
	{
		final Field f = BankPriceMovementPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(plugin);
	}

	/** Runs on the Swing thread and rethrows whatever happened there, the way the client would see it. */
	private static void onEdt(Runnable r) throws Exception
	{
		final AtomicReference<Throwable> thrown = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				r.run();
			}
			catch (Throwable t)
			{
				thrown.set(t);
			}
		});
		if (thrown.get() != null)
		{
			throw new AssertionError(thrown.get());
		}
	}
}
