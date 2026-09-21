package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.inject.Provides;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 2h Bank Portfolio Tracker: every tradeable stack in your bank, with what it is worth now and what its guide price
 * has done over the last day, week, month, quarter or half year, filtered by a gp band and sorted three ways.
 *
 * <p>Wiring (see {@code docs/bank-price-movement-design-2026-09-08.md}, addendum K, and
 * {@code docs/bank-price-movement-addendum-L-2026-09-08.md}): the bank is read on the CLIENT thread from
 * {@code ItemContainerChanged} ({@link BankReader}) and persisted per account; "now" is the Jagex GUIDE price
 * out of RuneLite's own item table (K1) and "then" is the same table as the OSRS wiki published it on an
 * earlier calendar DAY, fetched through {@link GuidePriceClient} on OkHttp's own threads ({@code enqueue},
 * never {@code execute}); {@link PriceService} schedules the fetches on RuneLite's shared
 * {@link ScheduledExecutorService}, does the maths there and publishes finished rows to
 * {@link BankPriceMovementPanel} through {@code SwingUtilities.invokeLater}. Nothing in this plugin blocks
 * the client thread or the EDT.
 *
 * <p>Addendum L costs one steady-state request pair a day (L10): {@code fetchRevisionIndex} for the guide
 * page's history, cached as {@code revindex.json} and refetched at six hours old, and one batched
 * {@code fetchTables} for every revision body still missing. Both are the service's calls; this class only
 * hands it the client and the store they go through.
 *
 * <p>Addendum T adds a second feed behind a switch ({@code livePrices}, T1): {@link TradedPriceClient} over the
 * wiki's {@code /latest} and {@code /24h} traded endpoints, built here from the same two injected collaborators
 * and handed to {@link PriceService} at construction (seam S2). It is asked for nothing while the switch is off,
 * so that setting is the guide-only plugin of addenda K to S down to the last request.
 *
 * <p>Plugin Hub rule: no client is built here and no Gson is built here - both are injected, because the Hub
 * forbids a plugin making its own (it would escape RuneLite's interceptors and its shared connection pool).
 */
@PluginDescriptor(
	name = "2h Bank Portfolio Tracker",
	description = "Your bank's value and price movement over 1 to 180 days: live prices, your inventory and worn gear, gp filters, and sorting by percent, gp, item or stack price",
	tags = {"bank", "price", "prices", "ge", "grand exchange", "guide price", "portfolio", "wiki", "flipping", "money"},
	// The Plugin Hub slug, which is what Plugin.getPluginDirectory() names the data directory after and what it
	// refuses to run without (addendum AD). PriceStore.DIR_NAME is the one spelling of it.
	internalName = PriceStore.DIR_NAME,
	// ...and the folder that directory is MOVED from, once, the first time it is asked for: every build up to
	// addendum AC wrote ~/.runelite/bank-portfolio-tracker/, and a user who installs an updated plugin must keep
	// their remembered bank, their baselines and their traded buckets rather than start over.
	legacyDataDirectory = PriceStore.DIR_NAME
)
public class BankPriceMovementPlugin extends Plugin
{
	/** Where the nav button sits in the sidebar; the same band the bundled information panels use. */
	static final int NAV_PRIORITY = 6;
	/**
	 * The three stored keys of addendum O's show/hide switches, as {@link BankPriceMovementConfig} declares
	 * them (O2). Named here because three things use them: the {@code ConfigChanged} route that re-renders the
	 * card, the pref seam the card's right-click checkboxes write through, and
	 * {@link #isHeroKey(String)}.
	 */
	static final String SHOW_VALUE_KEY = "showBankValue";
	static final String SHOW_GP_KEY = "showBankMoveGp";
	static final String SHOW_PCT_KEY = "showBankMovePct";
	/**
	 * The two stored keys addendum Q's gear menu still has (Q3), which travel with the three below as one
	 * {@link ViewOptions}. The menu opened with a third, {@code holdingOnRows}, which addendum AO deleted once
	 * addendum AN's three-line row printed the stack AND the item and left it nothing to choose - see
	 * {@link #LEGACY_HOLDING_KEY}. They are named here for the same three users as the hero keys above - the
	 * {@code ConfigChanged} route, the pref seam the gear's check items write through and
	 * {@link #isOptionKey(String)} - but they take a DIFFERENT road from there: both change what the figures are,
	 * not merely whether they are painted, so the service is told as well as the panel.
	 */
	static final String COUNT_CASH_KEY = "countCash";
	static final String COUNT_UNTRADEABLES_KEY = "countUntradeables";
	/**
	 * Addendum T's live-price switch (T1), which landed as the fourth key of the same {@link ViewOptions} and is
	 * the third since addendum AO deleted {@code holdingOnRows}. It is listed apart from the gear's original keys
	 * only because it arrived a wave later: {@link #isOptionKey(String)} treats every passenger alike, and it must,
	 * because this one changes the most of any of them - with it on, {@link PriceService} fetches the wiki's traded
	 * feeds and an item that passes the five liquidity checks (T3's three and addendum V's two) is priced from them
	 * instead of from the daily guide table.
	 */
	static final String LIVE_PRICES_KEY = "livePrices";
	/**
	 * Addendum Y's carried switch (Y1), which landed as the fifth key of the same {@link ViewOptions} and is the
	 * fourth since addendum AO. Like the live switch it is listed apart from the gear's original keys only because
	 * it arrived later; {@link #isOptionKey(String)} treats them all alike. What it changes is what the bank VALUE is
	 * and which stacks are rows, never a band or an ordering - so the service is told and the panel re-rendered,
	 * and no fetch is made either way: the two containers behind it are read from the client, not from the wiki.
	 */
	static final String COUNT_INVENTORY_KEY = "countInventory";
	/**
	 * Addendum AH's switch, which landed as the sixth and is the fifth since addendum AO - and is the only one of
	 * them whose default is OFF: whether the sidebar's hover text is
	 * shown at all. {@link #isOptionKey(String)} treats them all alike. Addendum AI took the ROWS out of its
	 * reach - a row's description is the block the cell opens, and a row carries no tooltip at any setting - so
	 * since AJ it governs the bank value's hover and every control's. The service is told like the rest even
	 * though it does nothing with it: the option road is one road, and a switch that took a private path would
	 * be the one nobody remembered to write.
	 */
	static final String SHOW_HOVER_TEXT_KEY = "showHoverText";
	/**
	 * Addendum Z's three price presets (Z1), which landed as the fourteenth stored key and is the thirteenth since
	 * addendum AO - and the only one of this plugin's that
	 * holds free text: the three gp bands the fold's chips offer, written as one line of shorthand
	 * ({@code "100k, 1m, 10m"}). It takes a FOURTH {@code ConfigChanged} road ({@link #isPresetKey(String)})
	 * because it is neither of the other three things - it is not the filter (the band a chip applies is
	 * {@code gpMin} / {@code gpMax}, and those are untouched by an edit here), not a figure the service computes,
	 * and not a switch on the card - so nothing but the panel is told.
	 */
	static final String BAND_PRESETS_KEY = "bandPresets";
	/**
	 * Addendum AA's fold switch (AA1), which landed as the fifteenth stored key and is the fourteenth since
	 * addendum AO: whether the price fold - the chip strip the key
	 * above fills, over the Min / Max fields - stands open under the control row. It takes a FIFTH
	 * {@code ConfigChanged} road ({@link #isFoldKey(String)}) of the same shape as the fourth, and for the same
	 * reason: it is a piece of the sidebar's SHAPE and nothing else reads it. Not the filter - the fold is where a
	 * band is typed, never a bound, so closing it moves no row and {@code gpMin} / {@code gpMax} keep whatever was
	 * applied - and not a figure, so the service is never told.
	 */
	static final String FOLD_OPEN_KEY = "foldOpen";
	/**
	 * Addendum N's look switch, which addendum O deleted (O1). The key survives only as something to SWEEP:
	 * see {@link #unstickLook()}.
	 */
	static final String LEGACY_LOOK_KEY = "look";
	/**
	 * Addendum Q's stack-value switch (Q6), which addendum AO deleted (AO1). The key survives only as something
	 * to SWEEP: see {@link #unstickHolding()}.
	 */
	static final String LEGACY_HOLDING_KEY = "holdingOnRows";
	private static final Logger log = LoggerFactory.getLogger(BankPriceMovementPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	/**
	 * RuneLite's shared client, with its interceptors: it refuses a call made on the client thread or the EDT
	 * and blocks non-LIVE hosts, which is exactly the discipline this plugin wants. A plugin may never build
	 * its own (Hub rule); {@link GuidePriceClient} only ever calls {@code enqueue} on it.
	 */
	@Inject
	private OkHttpClient okHttpClient;

	/** RuneLite's own Gson (nulls dropped). A Gson the plugin built for itself would be a Hub blocker. */
	@Inject
	private Gson gson;

	@Inject
	private ConfigManager configManager;

	/**
	 * RuneLite's single shared scheduler, bound in {@code RuneLiteModule.configure} as
	 * {@code bind(ScheduledExecutorService.class).toInstance(new ExecutorServiceExceptionLogger(
	 * Executors.newSingleThreadScheduledExecutor()))} (1.12.37, RuneLiteModule.java:128). Every fetch, every
	 * parse and every disk touch this plugin makes runs on it, so none of them can be on the client thread.
	 */
	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private BankPriceMovementConfig config;

	/**
	 * RuneLite's own developer-mode flag, bound in {@code RuneLiteModule.configure} as
	 * {@code bindConstant().annotatedWith(Names.named("developerMode")).to(developerMode)} (1.12.37,
	 * RuneLiteModule.java:120, from {@code RuneLite.java:224} = {@code --developer-mode} given AND no launcher
	 * version); the bundled plugins read it back with exactly this pair of annotations (BankTagsPlugin:150,
	 * ClueScrollPlugin:221). All it gates here is {@link BpmDevBridge}, the Effect Lab's command hook.
	 */
	@Inject
	@Named("developerMode")
	private boolean developerMode;

	/**
	 * The collaborators {@link #startUp()} builds and {@link #shutDown()} drops. Every one of them is WRITTEN on
	 * the EDT (PluginManager asserts it, clone PluginManager.java:405) and READ on another thread - {@code service}
	 * and {@code bankReader} by the client thread in the two game handlers, {@code panel} by whichever thread
	 * posts a {@code ConfigChanged} - and RuneLite's {@code EventBus} makes no happens-before promise between the
	 * two (its subscriber map is a plain field, clone EventBus.java:82: {@code register} is synchronized but
	 * {@code post} is not). So they are {@code volatile}, exactly as {@link #accountHash} and
	 * {@link #profileType} already were, or a handler could see a half-built plugin as {@code null} and silently
	 * drop the first capture. {@code navButton} is deliberately NOT here: it is touched on the EDT and nowhere
	 * else.
	 */
	private volatile PriceStore store;
	private volatile GuidePriceClient guide;
	/**
	 * Addendum T's traded feeds (T2), built here for the same reason {@link #guide} is: the Hub forbids a plugin
	 * making its own OkHttp client or Gson, so both are injected above and handed to a collaborator that only
	 * ever calls {@code enqueue}. It is given to {@link PriceService} at construction and cancelled by
	 * {@code service.stop()}, so this field is a handle for the lifecycle and nothing else reads it.
	 */
	private volatile TradedPriceClient traded;
	private volatile BankReader bankReader;
	private volatile PriceService service;
	private volatile BankPriceMovementPanel panel;
	/**
	 * The last snapshot this plugin PUBLISHED (Y2): the bank half a container event read, with whatever the
	 * player was carrying folded into it. It is what the Refresh hook re-stamps - the bank part is kept
	 * exactly as it was captured and only the carried part is read again, so pressing Refresh can never
	 * invent a bank the player has not opened.
	 *
	 * <p>Null until the first bank event of the session, and null again after {@code shutDown}; a Refresh
	 * before that one event does nothing at all, which is the honest answer - the rows on screen then came
	 * off disk ({@code PriceService.setLoggedIn}) and this class has never seen that bank's container.
	 * {@code volatile} for the reason every field above it is: written on the client thread and on the EDT
	 * ({@code shutDown}), read on the client thread.
	 */
	private volatile BankSnapshot lastBank;
	private NavigationButton navButton;

	/**
	 * The logged-in account's identity, cached on the CLIENT thread whenever it is read there and used off it
	 * by the dev bridge's synthetic {@code bank=} (see {@link BpmCommands.Account}). {@code volatile} because
	 * the writer is the client thread and the reader is the EDT.
	 */
	private volatile long accountHash;
	private volatile String profileType = RuneScapeProfileType.STANDARD.name();

	/**
	 * The last {@code (loggedIn, accountHash, profileType)} triple handed to {@link PriceService#setLoggedIn},
	 * so the same one is never sent twice. {@code LOGGED_IN} fires on every region change as well as on login -
	 * core's own XpTracker carries the comment and the same guard ({@code XpTrackerPlugin.java:183-187}) - and
	 * {@code setLoggedIn} short-circuits only when the held bank already belongs to that account, which is false
	 * for the whole of a session in which no bank has been opened. Without this, every zone crossing queued a
	 * disk read of a bank file that is usually not there, plus a publish, on RuneLite's single shared executor.
	 *
	 * <p>Written on the client thread (the handlers and the {@code startUp} seed) and reset on the EDT by
	 * {@link #startUp()}, because RuneLite reuses the plugin INSTANCE across a disable/enable - a memo left
	 * standing would swallow the first login of the next run, whose service is brand new and has been told
	 * nothing.
	 */
	private volatile boolean sentLoggedIn;
	private volatile long sentHash;
	private volatile String sentProfile = "";

	/**
	 * Non-null only while {@link #configPrefs()} is inside a write of its own - {@code save}'s five
	 * {@code setConfiguration} calls, {@code saveHero}'s three, {@code saveOptions}' six or
	 * {@code savePresets}' one - and it holds the thread making them.
	 * {@code ConfigManager} posts {@code ConfigChanged} SYNCHRONOUSLY, on the caller's own thread and only when
	 * the value really changed (clone ConfigManager.java:874-901), so a two-key change - "Clear price range"
	 * writes {@code gpMin} and {@code gpMax} - re-entered {@link #onConfigChanged} after the FIRST write and
	 * handed {@link PriceService} a filter half of which was still the old value. The widget that made the
	 * change has already applied it itself ({@code BankPriceMovementPanel.changeFilter}: {@code prefs.save} then
	 * {@code service.setFilter}; {@code setHeroVisibility}: {@code applyHeroVisibility} then
	 * {@code prefs.saveHero}), so the round trip is not merely early, it is redundant - on the hero road it was
	 * three more renders of the card per tick, two of them from a half-written config. The thread identity
	 * rather than a bare flag: an event genuinely raised elsewhere, on another thread, must still get through
	 * while this write is in progress.
	 */
	private volatile Thread prefsWriter;

	@Provides
	BankPriceMovementConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankPriceMovementConfig.class);
	}

	// ---------------------------------------------------------------- lifecycle

	/**
	 * EDT (PluginManager asserts it). Everything is built with explicit constructor arguments - a Swing panel
	 * is never {@code @Inject}ed - and in dependency order, so nothing sees a half-built collaborator.
	 *
	 * <p>The last step hops to the client thread: RuneLite registers a plugin's {@code @Subscribe} handlers
	 * only AFTER {@code startUp} returns, so a plugin switched on mid-session would otherwise wait for the next
	 * login to learn who is playing. The BANK is not read there - RuneLite replays every cached container into
	 * the freshly started plugin by itself (see {@link #onItemContainerChanged}) - so the seed carries the login
	 * state and nothing else.
	 */
	@Override
	protected void startUp()
	{
		// RuneLite reuses the plugin instance across a disable/enable, so last run's memo would swallow this
		// run's first login (the service below is a new one and has been told nothing).
		sentLoggedIn = false;
		sentHash = 0L;
		sentProfile = "";
		// First, before anything reads the config: a profile written by the pre-addendum-K build still says
		// window=H24, which no longer names a constant (K9), one written by the addendum-N build still
		// holds a look= this build has no item for (O1), and one written by any build up to addendum AN holds a
		// holdingOnRows= whose item went the same way (AO1).
		unstickWindow();
		unstickLook();
		unstickHolding();
		// getPluginDirectory() is RuneLite's own: it answers a Filepath rooted at
		// ~/.runelite/plugin-data/bank-portfolio-tracker/, moves the legacy folder in on the first call, and
		// throws if the disk refuses. Handed over as a method reference rather than called here, so this method
		// itself does no disk work - the first call is made by sweepStaleFiles() below, on the executor, never
		// on the EDT that PluginManager runs startUp on (addendum AD).
		store = new PriceStore(gson, this::getPluginDirectory);
		// ...and the files that build left behind (K11). Disk work, so it goes to the executor rather than
		// holding the EDT that PluginManager runs startUp on.
		sweepStaleFiles();
		guide = new GuidePriceClient(okHttpClient, gson);
		// T2, and the same two injected collaborators as the line above: RuneLite's own OkHttp (with its
		// interceptors, its connection pool and its refusal to run on the client thread or the EDT) and RuneLite's
		// own Gson. Built unconditionally rather than behind the livePrices switch, because it makes no request of
		// its own - the service asks it for a feed only while the switch is on (T2), so a user who has turned live
		// prices off pays for this object and nothing else.
		traded = new TradedPriceClient(okHttpClient, gson);
		bankReader = new BankReader(itemManager);
		// The client thread is the service's one hop back into the game: ItemManager.getItemPriceWithSource
		// reads the item composition first (ItemManager.java:328-368, the composition at :339), so the guide price is a
		// client-thread read.
		service = new PriceService(guide, store, itemManager, clientThread, executor, System::currentTimeMillis,
			SwingUtilities::invokeLater, traded);
		panel = new BankPriceMovementPanel(itemManager, service, configPrefs());
		// O2: the card opens showing exactly the figures the config names. The panel already asks the prefs
		// seam while it builds; this says it a second time, out loud, because applying the visibility it is
		// already in is a no-op and a card that flashed a figure the user had hidden would be the first thing
		// they saw.
		panel.applyHeroVisibility(heroFromConfig());
		// Q3, T1 and Y1, and said out loud here for the same reason as the line above: the gear's five switches
		// decide what the bank value COUNTS, which stacks are rows at all, what a row prints and which price
		// series a liquid item is read from, so the service and the panel both open on the stored answer
		// rather than on the value each of
		// them was built with - and a stored "live prices off" must reach the service before it starts, or the
		// first tick would fetch the traded feeds a user has switched off.
		final ViewOptions options = optionsFromConfig();
		panel.applyOptions(options);
		// Z1, and the panel alone: the fold's three chips are whatever this profile last stored, so a user who
		// edited them in the gear menu - or by hand in RuneLite's settings - opens on their own bands rather than
		// on 100k / 1m / 10m for as long as it took the first ConfigChanged to arrive (there is none on a launch).
		// The service is deliberately not told: a preset decides what a chip OFFERS, never what the list contains.
		panel.applyPresets(presetsFromConfig());
		// AA1, and the panel alone again: the fold opens the way this profile last left it. Said out loud here for
		// the reason the four lines above are - the panel has already asked the prefs seam while it built, and this
		// is the statement that the STORED answer is the one it opens on. setFoldOpen and not pressFold: seeding is
		// not a press, and writing the value back over itself would be a config write on every launch.
		panel.setFoldOpen(foldOpenFromConfig());
		service.setFilter(filterFromConfig());
		service.setOptions(options);
		// Y2 (b): the Refresh link's second job. PriceService owns the cooldown and the re-check; what it
		// cannot do is read an item container, so it runs this hook first and the two containers are read
		// here, on the client thread, exactly as the bank event reads them.
		service.setCarriedReader(this::rereadCarried);
		// Developer mode only (see BpmDevBridge): the Effect Lab's /bpm route drives this panel through it. A
		// Hub client binds developerMode false, so the handler stays null and nothing can reach the sidebar.
		if (developerMode)
		{
			// The shots folder sits inside this plugin's own directory, asked for only when a shot is taken.
			BpmDevBridge.handler = new BpmCommands(panel, service, gson, account(),
				() -> getPluginDirectory().joinSegment(BpmCommands.SHOT_DIR), panel::isShowing);
		}
		navButton = NavigationButton.builder()
			.tooltip("2h Bank Portfolio Tracker")
			.icon(NavIcon.create())
			.priority(NAV_PRIORITY)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		service.start();

		clientThread.invoke(() ->
		{
			// The fields are read HERE, on the client thread, not captured at startUp: a plugin switched off
			// again before the seed runs has nulled them, and the seed must then do nothing at all.
			final Client c = client;
			final PriceService s = service;
			if (c == null || s == null || c.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}
			// Who is playing. The BANK deliberately is not read here: PluginManager calls
			// gameEventManager.simulateGameEvents(plugin) the moment startUp returns (clone
			// PluginManager.java:429-441), and that replays an ItemContainerChanged for EVERY cached container,
			// container 95 included, into this very plugin (clone GameEventManager.java:111-130, itself gated on
			// LOGGED_IN). Reading the container here as well captured, priced and SAVED the same bank twice on
			// every plugin start.
			sendLoggedIn(s, rememberAccount(), profileType);
		});
	}

	/**
	 * EDT, and it runs even when {@link #startUp()} threw part way - so every step is null-guarded and the
	 * order is the reverse of the build.
	 */
	@Override
	protected void shutDown()
	{
		// First, and unconditionally: the panel this points at is about to be thrown away, and the lab's HTTP
		// thread can call in at any moment. A handler left by an earlier developer-mode run must go either way.
		BpmDevBridge.handler = null;
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		if (panel != null)
		{
			panel.stop();
			panel = null;
		}
		if (service != null)
		{
			// The hook points back at this plugin, whose fields are about to be nulled (Y2). Dropped before
			// stop() rather than after, so a Refresh racing the shutdown cannot reach a half-dropped plugin.
			service.setCarriedReader(null);
			// Never blocks: it cancels the schedules and sets a stopped flag so a fetch already in flight is
			// dropped when it lands.
			service.stop();
			service = null;
		}
		guide = null;
		// After service.stop(), which is what cancels the calls this client has in flight (it holds the same
		// instance). Nulling it first would leave the service holding the only reference and this class unable to
		// say what it built, which is the opposite of the order every other field here is dropped in.
		traded = null;
		bankReader = null;
		lastBank = null;
		store = null;
	}

	// ---------------------------------------------------------------- startUp housekeeping (K9, K11)

	/**
	 * K9. Clears {@code bankpricemovement.window} when what is stored there does not name a
	 * {@link MovementWindow} constant any more - which is every profile written before addendum K, where the
	 * three windows were H1 / H24 / D7 and the value on disk is "H24".
	 *
	 * <p><b>RuneLite repairs this by itself, and that is the point of the belt and braces</b> (corrected by the
	 * checker 2026-09-09; the original note here claimed the stale value survived the whole session, which the
	 * 1.12.37 clone does not bear out). {@code RuneLite.java:322} calls
	 * {@code pluginManager.loadDefaultPluginConfiguration(null)} BEFORE {@code startPlugins()} at :348, and that
	 * runs {@code ConfigManager.setDefaultConfiguration(config, false)} over every config proxy. Its
	 * {@code !override} branch reads the key back through {@code getConfiguration(group, key, type)}, which
	 * catches the {@code IllegalArgumentException} out of {@code Enum.valueOf} and answers null
	 * (ConfigManager.java:855-870), so the stale string counts as "not set" - the branch's own comment says
	 * "we will overwrite invalid config values with the default" (:1115-1124) - and the interface default is
	 * WRITTEN over it (:1137-1155). The same repair runs on a config-profile switch and when the Hub installs a
	 * plugin mid-session, both through {@code PluginManager.loadDefaultPluginConfiguration}. By the time this
	 * method reads the key it will normally say "D1" already.
	 *
	 * <p>The guard stays because it costs one read and depends on no ordering: it makes this plugin's own
	 * start-up self-sufficient rather than reliant on a repair that happens elsewhere in the client, and it
	 * spells the hazard out for the next reader. Do NOT clone it for other enum items on the strength of the
	 * repair being absent - it is not absent. What IS a real trap, and a separate one, is an item declared on a
	 * SUPER-interface: {@code setDefaultConfiguration} walks {@code getDeclaredMethods()} only, so such an item
	 * never gets a stored default at all and {@code ConfigPanel.createComboBox} then throws an uncaught NPE out
	 * of {@code Enum.valueOf(type, null)} - hence the flat-interface rule {@code BankPriceMovementConfigTest}
	 * guards.
	 *
	 * <p>Unsetting the key is what removes it here: {@code ConfigManager.unsetConfiguration} drops the value,
	 * invalidates the proxy cache and posts a {@code ConfigChanged} (ConfigManager.java:972-993), after which
	 * the interface default applies cleanly and the first chip click writes a current constant.
	 *
	 * <p><b>The test is {@code name()}, not {@link MovementWindow#parse(String)}.</b> {@code parse} is generous
	 * on purpose - it folds "H24" and "1h" onto D1 so an old {@code /bpm} script keeps working - but generosity
	 * here would be wrong: what matters is whether {@code ConfigManager} can read the value back, and
	 * {@code ConfigManager} calls {@code Enum.valueOf}, which knows only the constant names. A string {@code
	 * parse} understands and {@code Enum.valueOf} does not is exactly the case this guard exists for.
	 *
	 * <p>Package-private so {@code BankPriceMovementWiringTest} can drive it with a mocked manager; the sweep
	 * itself is {@link #dropStoredKey}, which {@link #unstickLook()} and {@link #unstickHolding()} share.
	 */
	void unstickWindow()
	{
		dropStoredKey("window", BankPriceMovementPlugin::namesAConstant,
			"it is not one of the five windows this build knows, so the default ("
				+ MovementWindow.DEFAULT.name() + ") applies");
	}

	/**
	 * O1. Drops {@code bankpricemovement.look} if a profile still holds it. Addendum N put two sidebar designs
	 * behind that item so the user could pick a winner; the user picked Ticker, the item is gone and the panel
	 * has one design - so whichever of the two names a profile stored is a value with no item, no reader and no
	 * way for a user to clear it by hand (RuneLite's config panel only lists the items the interface declares).
	 *
	 * <p>Unlike {@link #unstickWindow()} this is housekeeping and not a repair: an orphaned key breaks nothing
	 * while it sits there, because nothing reads it any more. It goes anyway, with ONE line at INFO, so a
	 * profile that has been through the whole wave ends up holding exactly the keys this build declares -
	 * and so a future item that happened to reuse the name could never inherit a value from the deleted enum.
	 * {@code ConfigManager.unsetConfiguration} drops the value, invalidates the proxy cache and posts a
	 * {@code ConfigChanged} (ConfigManager.java:972-993); that event's key is not one of ours, so
	 * {@link #onConfigChanged} treats it as a filter change and re-applies the filter it already has, which is
	 * a no-op by the time it lands.
	 *
	 * <p>Package-private so {@code BankPriceMovementWiringTest} can drive it with a mocked manager; the sweep
	 * itself is {@link #dropStoredKey}, which {@link #unstickWindow()} and {@link #unstickHolding()} share.
	 * Nothing stored here is valid any more, so the test it passes refuses every value rather than naming one.
	 */
	void unstickLook()
	{
		dropStoredKey(LEGACY_LOOK_KEY, stored -> false,
			"addendum O made Ticker the only design, so the key has no item behind it any more");
	}

	/**
	 * AO1. Drops {@code bankpricemovement.holdingOnRows} if a profile still holds it. Addendum Q put the choice
	 * between a row's per-ITEM reading and its per-STACK one behind that item (Q6); addendum AN's three-line row
	 * prints BOTH - the stack on line two, one item on line three - so by the time the user saw it in a client
	 * the switch reached nothing drawn, and the last thing it still decided, which figure
	 * {@link SortMode#GP_MOVE} compares, is now settled the other way for good: the gp column follows the STACK
	 * whatever a profile says. So whichever of true or false a profile stored is a value with no item, no reader
	 * and no way for a user to clear it by hand (RuneLite's config panel only lists the items the interface
	 * declares).
	 *
	 * <p>Like {@link #unstickLook()} and unlike {@link #unstickWindow()} this is housekeeping and not a repair:
	 * an orphaned key breaks nothing while it sits there, because nothing reads it any more. It goes anyway, with
	 * ONE line at INFO, so a profile that has been through the whole wave ends up holding exactly the keys this
	 * build declares - and so a future item that happened to reuse the name could never inherit a value from the
	 * deleted switch. {@code ConfigManager.unsetConfiguration} drops the value, invalidates the proxy cache and
	 * posts a {@code ConfigChanged} (ConfigManager.java:972-993); that event's key is no longer one of ours, so
	 * {@link #onConfigChanged} treats it as a filter change and re-applies the filter it already has, which is a
	 * no-op by the time it lands.
	 *
	 * <p>Package-private so {@code BankPriceMovementWiringTest} can drive it with a mocked manager; the sweep
	 * itself is {@link #dropStoredKey}, which {@link #unstickWindow()} and {@link #unstickLook()} share. Nothing
	 * stored here is valid any more, so the test it passes refuses every value rather than naming one.
	 */
	void unstickHolding()
	{
		dropStoredKey(LEGACY_HOLDING_KEY, stored -> false,
			"addendum AN's row prints the stack and the item both, so the key has no item behind it any more");
	}

	/**
	 * The one sweep behind {@link #unstickWindow()}, {@link #unstickLook()} and {@link #unstickHolding()}: read
	 * one stored key of this plugin's group, and unset it unless it is empty or {@code stillValid} accepts it.
	 * All three callers wanted the same five steps - read, ignore nothing-stored, test, say so once at INFO,
	 * unset - and every step of each
	 * has to survive a {@link ConfigManager} that is missing (a field never injected, as the unit tests leave it)
	 * or that throws, because this runs first thing in {@link #startUp()} and a failed sweep may not take the
	 * plugin down with it.
	 *
	 * @param key        the key inside {@link BankPriceMovementConfig#GROUP}
	 * @param stillValid whether a stored value is one this build can still read back; a key whose item is gone
	 *                   passes a test that accepts nothing
	 * @param why        the second half of the INFO line - why the value cannot stay
	 */
	private void dropStoredKey(String key, Predicate<String> stillValid, String why)
	{
		final ConfigManager cm = configManager;
		if (cm == null)
		{
			return;
		}
		final String stored;
		try
		{
			stored = cm.getConfiguration(BankPriceMovementConfig.GROUP, key);
		}
		catch (RuntimeException e)
		{
			log.debug("could not read the stored {}", key, e);
			return;
		}
		if (stored == null || stored.isEmpty() || stillValid.test(stored))
		{
			return;
		}
		log.info("bank-portfolio-tracker: dropping the stored {} '{}' - {}", key, stored, why);
		try
		{
			cm.unsetConfiguration(BankPriceMovementConfig.GROUP, key);
		}
		catch (RuntimeException e)
		{
			log.debug("could not unset the stored {}", key, e);
		}
	}

	/** Whether {@code stored} is the {@code name()} of a live {@link MovementWindow} - what ConfigManager needs. */
	private static boolean namesAConstant(String stored)
	{
		for (MovementWindow w : MovementWindow.values())
		{
			if (w.name().equals(stored))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * K11. Deletes what the pre-addendum-K build left in the plugin's data directory -
	 * {@code prices-latest.json} and the baselines of windows that no longer exist or that hold trade-era
	 * figures ({@link PriceStore#deleteStaleFiles()}). Files, so it runs on the executor: {@code startUp} is on
	 * the EDT and a disk sweep there would stutter the client's UI. Queued with {@code submit} rather than the
	 * executor's fire-and-forget method for the reason {@code PriceService.execute} gives: it keeps the word
	 * {@code execute} out of this package, so an eyeball scan against the Hub's forbidden
	 * {@code okhttp3.Call#execute()} is trivially clean. The machine check is signature-based over bytecode and
	 * would not have confused the two either way - do not contort other code to avoid a method NAME.
	 *
	 * <p><b>What the sweep must NOT take.</b> Addendum L added {@code revindex.json}, the guide page's revision
	 * history (L4) - about 232 days of {@code {revId, editSeconds, user, comment}}, the one file that lets a
	 * baseline be PICKED without a request. The sweep's rules are the two file names of the trade era plus
	 * {@code baseline-*.json}, so it cannot reach the index; deleting it would cost a needless refetch on every
	 * launch and, worse, leave every window without a baseline until that fetch landed. Its staleness is a
	 * six-hour refetch inside the service, never a deletion here.
	 *
	 * <p>The store is captured in a local rather than read from the field inside the task: the plugin may be
	 * switched off before the executor gets to it, and a sweep of a store that is about to be dropped is still
	 * harmless, while a null field would not be.
	 *
	 * <p>Package-private so {@code BankPriceMovementWiringTest} can drive it with a mocked store, rather than
	 * running the task startUp queued and deleting files out of the developer's own RuneLite directory.
	 */
	void sweepStaleFiles()
	{
		final PriceStore s = store;
		if (s == null)
		{
			return;
		}
		executor.submit(() ->
		{
			try
			{
				final int deleted = s.deleteStaleFiles();
				if (deleted > 0)
				{
					// "an older build" covers both sweep rules: the trade-price era K11 was written for, and any
					// baseline whose stored schema is below this build's (B028) - both are files whose fields do
					// not mean what this build would read them as.
					log.info("bank-portfolio-tracker: deleted {} stale price file(s) written by an older build",
						deleted);
				}
			}
			catch (RuntimeException e)
			{
				log.debug("stale-file sweep failed", e);
			}
		});
	}

	// ---------------------------------------------------------------- game events (client thread)

	/**
	 * CLIENT THREAD. Who is logged in, and only when that ANSWER changes: {@code LOGGED_IN} fires on every
	 * region change too, not just on login (core's XpTracker says so and guards on the account hash for the
	 * same reason, clone XpTrackerPlugin.java:183-187), and {@link PriceService#setLoggedIn} does real work
	 * every time it is told something new - it bumps the login generation and queues a bank load on RuneLite's
	 * shared executor. It short-circuits only when the bank it already holds belongs to that account, which is
	 * false for the whole of a session in which no bank has been opened, so an unguarded forward cost one
	 * pointless file read and one publish per zone crossing. See {@link #sentLoggedIn}.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		final PriceService s = service;
		if (s == null)
		{
			return;
		}
		switch (event.getGameState())
		{
			case LOGGED_IN:
				sendLoggedIn(s, rememberAccount(), profileType);
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				// The rows stay on screen: the panel is useful at the Grand Exchange with the last bank in it.
				// LOADING is deliberately not here - it fires on every scene change, including one inside a
				// bank, and nothing this plugin holds is scene-bound.
				accountHash = 0;
				// Y2: the held snapshot belongs to the account that has just left. The ROWS stay on screen
				// (the panel keeps what the service published), but a Refresh from here must not re-stamp
				// somebody else's bank with this client's inventory.
				lastBank = null;
				sentLoggedIn = false;
				sentHash = 0L;
				sentProfile = "";
				s.setLoggedIn(false, 0, "");
				break;
			default:
				break;
		}
	}

	/**
	 * CLIENT THREAD. Tells the service this account is logged in unless it has been told exactly that already.
	 * The logged-OUT half is never deduplicated: it is one flag write inside the service, and a login screen
	 * that failed to reach it would leave the header saying the player is still online.
	 *
	 * @param s       the live service (never null; the caller has already copied the field)
	 * @param hash    {@code client.getAccountHash()} as {@link #rememberAccount()} just cached it
	 * @param profile the RuneScape profile type that went with it
	 */
	private void sendLoggedIn(final PriceService s, final long hash, final String profile)
	{
		final String p = profile == null ? "" : profile;
		if (sentLoggedIn && hash == sentHash && p.equals(sentProfile))
		{
			return;
		}
		sentLoggedIn = true;
		sentHash = hash;
		sentProfile = p;
		s.setLoggedIn(true, hash, p);
	}

	/**
	 * The bank, read on the CLIENT thread - {@link BankReader#read} calls {@code ItemManager.canonicalize} and
	 * {@code getItemComposition}, both of which go through the client's cache and must not be touched from
	 * anywhere else.
	 *
	 * <p>RuneLite replays every cached container into a newly started plugin:
	 * {@code GameEventManager.simulateGameEvents(subscriber)} posts an {@code ItemContainerChanged} for each
	 * of {@code client.getItemContainers()} (clone GameEventManager.java:111-130), and PluginManager calls it
	 * for every plugin it starts, right after {@code startUp} returns (clone PluginManager.java:429-441). So a
	 * plugin switched on mid-session sees the bank the player opened earlier through this handler, with no
	 * special case anywhere - which is why {@link #startUp()}'s seed reads the login state only.
	 *
	 * <p><b>Two guards on the capture, and both are about WHOSE bank this is.</b> A capture is stamped with the
	 * account read a line later and is then persisted under it, so it must never be taken while the client
	 * cannot say who is playing: the replay above is gated on {@code LOGGED_IN}, but nothing gates a container
	 * event that arrives while the player is logging out or between accounts, and {@code Client.getAccountHash()}
	 * answers -1 before a login (clone {@code com/jagex/oldscape/pub/OAuthApi.java:30-32}) while this plugin
	 * spells "nobody" as 0. So: no capture unless the game state is {@code LOGGED_IN}, and none unless the hash
	 * is a real account - otherwise the previous player's stacks could be written over
	 * {@code bank-<this account>-<profile>.json}, or a capture filed as {@code bank--1-STANDARD.json} that no
	 * login will ever load.
	 */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		final PriceService s = service;
		final BankReader reader = bankReader;
		final Client c = client;
		if (s == null || reader == null || c == null || !BankReader.isBank(event))
		{
			return;
		}
		final ItemContainer container = event.getItemContainer();
		if (container == null || c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final long hash = rememberAccount();
		if (hash <= 0)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		// Y2 (a). The bank, and in the SAME pass the two containers the player is carrying: the client has
		// them cached on the very thread this handler runs on, so one event produces ONE snapshot with all
		// three parts in it rather than three publishes and three recomputes. They are read whatever the
		// "Include inventory and worn gear" switch says, for the reason BankSnapshot.currencyGp is recorded
		// whatever the cash switch says (P1, Q4): a capture that held only what the switch of the day wanted
		// would need a bank visit every time it was flipped.
		final BankSnapshot snapshot = carriedInto(reader, c,
			reader.read(container.getItems(), hash, profileType, now), now);
		lastBank = snapshot;
		s.setBank(snapshot);
	}

	/**
	 * CLIENT THREAD. Folds what the player is CARRYING into a snapshot that already holds their bank (Y2):
	 * the inventory ({@code InventoryID.INV} = 93) and the worn gear ({@code InventoryID.WORN} = 94), read
	 * through {@code Client.getItemContainer(int)} - which answers null for a container the client has not
	 * cached, and a null container is an empty list rather than a failure ({@link BankReader#readContainers}).
	 *
	 * <p>The one place the two ids are named and the one caller of {@code readContainers}, so the bank event
	 * and the Refresh hook cannot drift apart. A reader that answers nothing leaves the snapshot exactly as
	 * it came in, which is what keeps the carried half additive: everything this wave adds can be absent.
	 *
	 * @param reader the live reader (never null - the caller has copied the field)
	 * @param c      the live client (likewise)
	 * @param bank   the bank half of the snapshot about to be published
	 * @param now    wall clock, stamped on the carried half
	 * @return {@code bank} with its carried half replaced, or {@code bank} itself when there is none to add
	 */
	@Nullable
	private static BankSnapshot carriedInto(final BankReader reader, final Client c,
		@Nullable final BankSnapshot bank, final long now)
	{
		if (bank == null)
		{
			return null;
		}
		final BankReader.Carried carried = reader.readContainers(itemsOf(c, InventoryID.INV),
			itemsOf(c, InventoryID.WORN), now);
		return carried == null ? bank : bank.withCarried(carried);
	}

	/** CLIENT THREAD. One container's items, or null when the client has never cached that container. */
	@Nullable
	private static Item[] itemsOf(final Client c, final int containerId)
	{
		final ItemContainer container = c.getItemContainer(containerId);
		return container == null ? null : container.getItems();
	}

	/**
	 * Y2 (b), the Refresh trigger: the hook {@link #startUp()} hands {@link PriceService#setCarriedReader}
	 * and {@code refreshNow()} runs before it re-checks the prices. Refresh is pressed on the EDT, and an
	 * item container may only be read on the client thread, so all this does is hop - the work is
	 * {@link #readCarriedOnClientThread()}.
	 *
	 * <p>Package-private so {@code BankPriceMovementWiringTest} can press it without a service.
	 */
	void rereadCarried()
	{
		final ClientThread ct = clientThread;
		if (ct == null)
		{
			return;
		}
		ct.invoke(this::readCarriedOnClientThread);
	}

	/**
	 * CLIENT THREAD. Re-reads the inventory and the worn gear and republishes the STORED bank part with the
	 * fresh carried part (Y2). The bank half is never re-read: it is whatever the last container event
	 * captured, so a Refresh can move a row's quantity but can never invent stacks the player has not
	 * banked.
	 *
	 * <p>Nothing happens unless the client can say who is playing - the two guards
	 * {@link #onItemContainerChanged} uses, for the same reason: the republished snapshot keeps its stamps
	 * and {@link PriceService#setBank(BankSnapshot)} may persist it.
	 *
	 * <p><b>Which bank is re-stamped.</b> The session's last capture ({@link #lastBank}) when there has been
	 * one, and otherwise the snapshot the service loaded from DISK ({@link PriceService#bank()}) - which is
	 * what a player who has logged in and not yet opened a bank is looking at, carried half and all, as it was
	 * at their last bank visit. Refresh is one of the two moments Y2 promises will re-read what they are
	 * holding, so it has to reach that one too. A snapshot stamped with another account is left alone: it is
	 * not this player's bank, and a republish would file it under this one.
	 *
	 * <p>The publish is a no-op inside the service when the content has not moved
	 * ({@code BankSnapshot.sameContentAs}), so a Refresh pressed twice with nothing eaten in between costs
	 * one recompute and no write.
	 */
	void readCarriedOnClientThread()
	{
		final PriceService s = service;
		final BankReader reader = bankReader;
		final Client c = client;
		if (s == null || reader == null || c == null)
		{
			return;
		}
		if (c.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final long hash = rememberAccount();
		if (hash <= 0L)
		{
			return;
		}
		final BankSnapshot cached = lastBank;
		final BankSnapshot held = cached != null ? cached : ownBank(s.bank(), hash);
		if (held == null)
		{
			return;
		}
		final BankSnapshot next = carriedInto(reader, c, held, System.currentTimeMillis());
		if (next == null || next == held)
		{
			return;
		}
		lastBank = next;
		s.setBank(next);
	}

	/**
	 * The service's own snapshot when it is THIS account's and profile's, and null when it is anyone else's
	 * (Y2). Only the DISK fallback of {@link #readCarriedOnClientThread()} is asked this: {@link #lastBank} is
	 * a capture this plugin took and is dropped at {@code LOGIN_SCREEN} and {@code HOPPING}, so it can only
	 * ever belong to the player at the keyboard.
	 *
	 * <p>The service's can, for one window: a hop or a re-login bumps its login generation and the new
	 * account's bank is read from disk on the executor, so between the login and that read landing the
	 * service still holds the PREVIOUS account's snapshot. Re-stamping that one with this player's inventory
	 * would write their gear into someone else's bank file, which is keyed from the snapshot's own stamps.
	 *
	 * @param loaded the service's current bank, or null when it has none
	 * @param hash   the account hash just read from the client
	 */
	@Nullable
	private BankSnapshot ownBank(@Nullable final BankSnapshot loaded, final long hash)
	{
		if (loaded == null || loaded.accountHash != hash)
		{
			return null;
		}
		return profileType.equals(loaded.profileType == null ? "" : loaded.profileType) ? loaded : null;
	}

	// ---------------------------------------------------------------- config and shutdown

	/**
	 * The config panel and the sidebar are the same switch: a change made in RuneLite's own settings has to
	 * reach the widgets and the row list. {@code ConfigChanged} arrives on the AWT thread already in most
	 * cases, but not by contract, so the widget half hops explicitly; {@link BankPriceMovementPanel#applyFilter}
	 * writes nothing back, which is what keeps this from looping.
	 *
	 * <p>Addendum O's three show/hide keys take their own road (O2), as addendum N's deleted {@code look} key
	 * did. They are presentation: they change no row, no figure and no fetch, so they must NOT go down the
	 * filter path - that would hand {@link PriceService} an identical filter and make the whole list recompute
	 * because the user hid a number on the card.
	 *
	 * <p>Addendum Q's gear switches take a THIRD road (Q3), because they are neither of those things: the
	 * list they produce is the same list under the same band and the same ordering (so not the filter), but what
	 * the bank value counts and which stacks are rows at all do change (so not presentation). Addendum AO left
	 * two of that road's original three - {@code holdingOnRows} is gone, and with it the one passenger that only
	 * changed a reading. {@link PriceService#setOptions}
	 * recomputes the figures and {@link BankPriceMovementPanel#applyOptions} re-renders what is already on
	 * screen; no fetch is made either way, because nothing here asks for a price this build has not got.
	 *
	 * <p><b>Addendum T's live-price switch rides that third road as the fourth passenger</b> (T1), and is the one
	 * exception to the sentence above: turning it ON does ask for prices this build has not got, so
	 * {@code setOptions} starts the traded feeds as well as recomputing. Turning it off stops them. Nothing about
	 * that changes the shape of this handler - the switch is one field of the same {@link ViewOptions}, and
	 * {@link #isOptionKey(String)} answers true for its key.
	 *
	 * <p><b>Addendum Z's price presets take a FOURTH road</b> (Z1), the shortest of them: the three chips under
	 * the band button are drawn from {@code bandPresets} and nothing else reads it, so the panel is told and
	 * nobody else is. Not the filter road, which would rebuild the list for a change that moves no row - the band
	 * a chip APPLIES is {@code gpMin} / {@code gpMax}, two keys this one never writes; not the option road, which
	 * exists because those switches change what the figures are.
	 *
	 * <p><b>Addendum AA's fold takes a FIFTH of the same shape</b> (AA1): {@code foldOpen} says whether those
	 * chips and the two gp fields are on screen at all, which is the sidebar's shape and not its contents - so the
	 * panel is told and nothing else is, for exactly the reasons the fourth road exists. The two are separate
	 * because they answer different questions (what a chip offers; whether the chips are drawn) and because a
	 * reader may change either without the other.
	 *
	 * <p><b>A write the sidebar itself just made is skipped</b> (see {@link #prefsWriter}). {@code ConfigManager}
	 * posts this event synchronously, key by key, so a two-key change - "Clear price range" is {@code gpMin}
	 * then {@code gpMax} - would otherwise re-enter here after the first write and hand the service a filter
	 * built half from the new value and half from the old one, and the panel's own
	 * {@code service.setFilter(filter)} would then correct it a moment later. The widget has already applied the
	 * whole filter; there is nothing for the round trip to bring back. The card's three switches are written
	 * together too and are skipped for the same reason: the check item that was ticked has already painted the
	 * card, so an unguarded round trip only re-rendered it three more times.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BankPriceMovementConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (Thread.currentThread() == prefsWriter)
		{
			return;
		}
		if (isHeroKey(event.getKey()))
		{
			final HeroVisibility hero = heroFromConfig();
			final BankPriceMovementPanel card = panel;
			if (card != null)
			{
				SwingUtilities.invokeLater(() -> card.applyHeroVisibility(hero));
			}
			return;
		}
		if (isOptionKey(event.getKey()))
		{
			// Q3's third road, and since addendum T, addendum Y and addendum AH all five view switches take it.
			// Not the filter's: not one of them touches the gp band or the sort, so handing PriceService an
			// identical RowFilter would be a lie about what changed. Not the hero's either: the cash, untradeable
			// and carried switches change what the SUMS are and which stacks are rows at all, so the service is
			// told first (it recomputes and publishes) and the panel is re-rendered for the half that is pure
			// painting - the hover text - which must not wait for a publish that may be a fetch away.
			final ViewOptions options = optionsFromConfig();
			final PriceService withOptions = service;
			if (withOptions != null)
			{
				withOptions.setOptions(options);
			}
			final BankPriceMovementPanel view = panel;
			if (view != null)
			{
				SwingUtilities.invokeLater(() -> view.applyOptions(options));
			}
			return;
		}
		if (isPresetKey(event.getKey()))
		{
			// Z1's FOURTH road, and the shortest of the four: the three chips under the band button are a panel
			// matter from end to end. Not the filter's road - the band those chips apply is gpMin / gpMax, which
			// this key does not touch, so handing PriceService a filter it already holds would rebuild the whole
			// list because a user renamed a chip. Not the option road either: nothing here changes a figure, so
			// there is nothing for the service to recompute. The boxes' own write comes back this way only when
			// it was not this thread's (see prefsWriter) - the gear menu has already applied the value itself.
			final BandPresets presets = presetsFromConfig();
			final BankPriceMovementPanel chips = panel;
			if (chips != null)
			{
				SwingUtilities.invokeLater(() -> chips.applyPresets(presets));
			}
			return;
		}
		if (isFoldKey(event.getKey()))
		{
			// AA1's FIFTH road, the fourth's twin: the price fold is a piece of the sidebar's shape, so the panel
			// is told and nobody else. Not the filter's road - the fold is where a band is TYPED, not a bound, so
			// a list rebuilt because the user folded the controls away would be work done for a change that moves
			// no row. Not the option road: no figure is recomputed. The band button's own press comes back this
			// way only when it was not this thread's (see prefsWriter), the press having already moved the header.
			final boolean open = foldOpenFromConfig();
			final BankPriceMovementPanel shape = panel;
			if (shape != null)
			{
				// setFoldOpen and not pressFold: this IS the config, so writing it back would be a round trip that
				// says nothing, and on the settings page's own write it would be a second write of the same value.
				SwingUtilities.invokeLater(() -> shape.setFoldOpen(open));
			}
			return;
		}
		final RowFilter filter = filterFromConfig();
		final BankPriceMovementPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.applyFilter(filter));
		}
		final PriceService s = service;
		if (s != null)
		{
			s.setFilter(filter);
		}
	}

	/**
	 * The client never calls a plugin's {@code shutDown} on exit, so the last bank and the last price maps
	 * would be lost with the writes still queued on the executor. {@code ClientShutdown.waitFor(Future)} adds
	 * a future the client drains before it goes (clone ClientShutdown.java:41-45); {@code service.flush()} is
	 * the pending store writes.
	 */
	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		final PriceService s = service;
		if (s != null)
		{
			event.waitFor(s.flush());
		}
	}

	// ---------------------------------------------------------------- config <-> filter

	/** The five stored keys as one immutable filter; the panel's {@code Prefs.load} and the startup value. */
	RowFilter filterFromConfig()
	{
		return new RowFilter(config.gpMin(), config.gpMax(), config.sortMode(), config.sortDescending(),
			config.window());
	}

	/**
	 * The last three stored keys (O2) as one immutable value, kept OUT of {@link RowFilter} for the reason
	 * {@link HeroVisibility} gives: a filter says what the list CONTAINS, and these three say only what the
	 * card paints. The panel's {@code Prefs.loadHero} and the value {@link #startUp()} opens the card with.
	 */
	HeroVisibility heroFromConfig()
	{
		return HeroVisibility.of(config.showBankValue(), config.showBankMoveGp(), config.showBankMovePct());
	}

	/** Whether a {@code ConfigChanged} key is one of addendum O's three switches (O2). */
	static boolean isHeroKey(@Nullable String key)
	{
		return SHOW_VALUE_KEY.equals(key) || SHOW_GP_KEY.equals(key) || SHOW_PCT_KEY.equals(key);
	}

	/**
	 * The gear menu's five stored keys (Q3, T1, Y1, AH) as one immutable value - the plugin's only reader of
	 * {@code countCash} / {@code countUntradeables} / {@code livePrices} / {@code countInventory} /
	 * {@code showHoverText}, and so the one place the config and {@link ViewOptions} are put in step. The panel's
	 * {@code Prefs.loadOptions}, the value {@link #startUp()} opens on, and what {@link #onConfigChanged} rebuilds
	 * when one of the five is written anywhere. Addendum AO took a sixth, {@code holdingOnRows}, off this list and
	 * out of the group altogether (AO1).
	 *
	 * <p>It must name every field of {@link ViewOptions}: the value keeps shorter constructors that default
	 * {@code livePrices} and {@code countInventory} to on, which is right for a caller that predates addendum T or
	 * Y and wrong here - it would make the stored switch unreadable, so that a user who turned live prices (or the
	 * carried items) off in RuneLite's settings got them back on the next launch.
	 * {@code BankPriceMovementWiringTest} pins the five-way round trip.
	 */
	ViewOptions optionsFromConfig()
	{
		return new ViewOptions(config.countCash(), config.countUntradeables(), config.livePrices(),
			config.countInventory(), config.showHoverText());
	}

	/**
	 * Whether a {@code ConfigChanged} key is one of the gear menu's view switches - addendum Q's two remaining
	 * ones (Q3; the third, {@code holdingOnRows}, went with addendum AO and is swept by {@link #unstickHolding()}
	 * rather than answered here), addendum T's live-price switch (T1), addendum Y's carried switch (Y1) or
	 * addendum AH's hover switch, which ride the same road for the
	 * same reason: they are neither a filter (no gp band changes, and no ordering) nor presentation (what a live
	 * row's unit, "then" and move figures ARE changes, and so does the bank value built out of them, and so does
	 * the quantity of a stack the player is half carrying), so the service is told and the panel is re-rendered,
	 * exactly as for {@code countCash}.
	 */
	static boolean isOptionKey(@Nullable String key)
	{
		return COUNT_CASH_KEY.equals(key) || COUNT_UNTRADEABLES_KEY.equals(key)
			|| LIVE_PRICES_KEY.equals(key) || COUNT_INVENTORY_KEY.equals(key)
			|| SHOW_HOVER_TEXT_KEY.equals(key);
	}

	/**
	 * The fourteenth stored key as the value the fold's chips are drawn from (Z1) - the plugin's only reader of
	 * {@code bandPresets}, and so the one place the config line and {@link BandPresets} are put in step. The
	 * panel's {@code Prefs.loadPresets}, the value {@link #startUp()} seeds the chips with, and what
	 * {@link #onConfigChanged} rebuilds when the line is written anywhere.
	 *
	 * <p><b>Anything it cannot read is {@link BandPresets#DEFAULT}</b> (Z1), never null and never a throw. The
	 * key holds free text - a user may type into the settings page directly, and a profile may have been
	 * hand-edited - so "1m" alone, "a, b, c", "1m, 1m, 10m" and an empty string all arrive here as easily as a
	 * line this plugin wrote. A fold with no chips would be a dead control the user could not repair from the
	 * sidebar, so the three defaults stand in instead and the boxes at the foot of the gear menu print them, ready
	 * to be corrected. Nothing is written back on that path: the stored text is left exactly as the user typed it
	 * until they save a line that parses.
	 */
	BandPresets presetsFromConfig()
	{
		final BandPresets stored = BandPresets.parse(config.bandPresets());
		return stored == null ? BandPresets.DEFAULT : stored;
	}

	/**
	 * Whether a {@code ConfigChanged} key is addendum Z's price presets (Z1) - the fourth road, and the one that
	 * reaches nothing but the panel: it is not a filter field (the chips apply a band, they are not one), not a
	 * figure the service computes, and not one of the card's show/hide switches.
	 */
	static boolean isPresetKey(@Nullable String key)
	{
		return BAND_PRESETS_KEY.equals(key);
	}

	/**
	 * The fifteenth stored key as the shape the header is drawn in (AA1) - the plugin's only reader of
	 * {@code foldOpen}. The panel's {@code Prefs.loadFoldOpen}, the value {@link #startUp()} opens the fold on, and
	 * what {@link #onConfigChanged} hands the panel when the switch is ticked on the settings page.
	 *
	 * <p>A plain {@code boolean}: the seam's {@code Boolean} exists so a {@code Prefs} with nothing stored can say
	 * "nothing stored" and be given the shipped default, and this plugin always has an answer -
	 * {@code ConfigManager} writes the interface's own default over a fresh profile before any of this runs, so
	 * {@code config.foldOpen()} is true there rather than absent.
	 */
	boolean foldOpenFromConfig()
	{
		return config.foldOpen();
	}

	/**
	 * Whether a {@code ConfigChanged} key is addendum AA's fold switch (AA1) - the fifth road, and the second that
	 * reaches nothing but the panel: it is not a filter field (the fold HOLDS the two bound fields, it is not a
	 * bound), not a figure the service computes, and not one of the card's show/hide switches.
	 */
	static boolean isFoldKey(@Nullable String key)
	{
		return FOLD_OPEN_KEY.equals(key);
	}

	/**
	 * The panel's and the bridge's way into the config. Writing a widget's value back through
	 * {@link ConfigManager} rather than holding it in the panel is what makes the sidebar and the config panel
	 * one switch: the write posts a {@code ConfigChanged} that {@link #onConfigChanged} turns back into a
	 * filter for whichever half did not make the change.
	 *
	 * <p>{@code gpMin}/{@code gpMax} are stored as {@code int} (a gp band above 2^31 is not a band), so a
	 * filter carrying more is clamped rather than wrapped.
	 *
	 * <p><b>The card's three switches ride the same seam</b> (O2): {@code Prefs.loadHero} / {@code saveHero}
	 * are the panel's way to {@code showBankValue} / {@code showBankMoveGp} / {@code showBankMovePct}, so
	 * ticking one of the hero card's right-click check items writes the config and RuneLite's own settings
	 * panel follows on the {@code ConfigChanged} that write posts - the same round trip the filter widgets have
	 * always made. Both are {@code default} on the interface so a test's throwaway {@code Prefs} need not care;
	 * the plugin MUST override them, or a check item would re-render the card and forget the choice on the next
	 * launch.
	 *
	 * <p><b>And the gear menu's three boxes</b> (Z1): {@code loadPresets} / {@code savePresets} are the way to
	 * {@code bandPresets}, so a preset typed into a box at the foot of the menu is what RuneLite's settings page
	 * then shows on one line, and what the next launch opens the fold with. The save half does LESS than
	 * {@code saveHero} rather than more - one key, no service - because nothing behind the chips is computed.
	 *
	 * <p><b>And the band button itself</b> (AA1): {@code loadFoldOpen} / {@code saveFoldOpen} are the way to
	 * {@code foldOpen}, the same one-key-no-service shape as the pair above, so a fold the reader shut stays shut
	 * across a relaunch and RuneLite's settings page shows the switch unticked. The load half answers a
	 * {@code Boolean} the panel treats as "nothing stored = open"; this implementation never answers null, the
	 * config having a default of its own.
	 *
	 * <p><b>And so do the gear menu's five switches</b> (Q3, T1, Y1, AH): {@code loadOptions} /
	 * {@code saveOptions} are the way
	 * to {@code countCash} / {@code countUntradeables} / {@code livePrices} / {@code countInventory} /
	 * {@code showHoverText} - a sixth, {@code holdingOnRows}, travelled here until addendum AO deleted it (AO1).
	 * The save half does one thing more
	 * than {@code saveHero} does, and the comment inside it says why: the switch it wrote is suppressed on the
	 * {@code ConfigChanged} round trip as this thread's own write, and three of the five change figures the
	 * SERVICE owns, so it is told directly.
	 */
	BankPriceMovementPanel.Prefs configPrefs()
	{
		return new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return filterFromConfig();
			}

			@Override
			public void save(RowFilter filter)
			{
				if (filter == null)
				{
					return;
				}
				final ConfigManager cm = configManager;
				if (cm == null)
				{
					return;
				}
				// The five writes are ONE change of mind, and each one posts a ConfigChanged on this very
				// thread as it lands. Marking the thread keeps onConfigChanged out of the middle of them: see
				// prefsWriter. Restored rather than nulled, so a nested save (there is none today) still ends
				// with the outer one's mark in place.
				final Thread outer = prefsWriter;
				prefsWriter = Thread.currentThread();
				try
				{
					cm.setConfiguration(BankPriceMovementConfig.GROUP, "gpMin", clampToInt(filter.gpMin()));
					cm.setConfiguration(BankPriceMovementConfig.GROUP, "gpMax", clampToInt(filter.gpMax()));
					cm.setConfiguration(BankPriceMovementConfig.GROUP, "sortMode", filter.sort());
					cm.setConfiguration(BankPriceMovementConfig.GROUP, "sortDescending", filter.descending());
					cm.setConfiguration(BankPriceMovementConfig.GROUP, "window", filter.window());
				}
				finally
				{
					prefsWriter = outer;
				}
			}

			@Override
			public HeroVisibility loadHero()
			{
				return heroFromConfig();
			}

			@Override
			public void saveHero(HeroVisibility hero)
			{
				final ConfigManager cm = configManager;
				if (hero == null || cm == null)
				{
					return;
				}
				// The three writes are ONE change of mind, exactly as save's five are, and they are marked for
				// the same reason: each posts a ConfigChanged on this very thread as it lands, and the check
				// item that was ticked has already painted the card itself (setHeroVisibility applies, then
				// calls this). Unmarked, one tick re-rendered the card three more times - twice from a config
				// in which only some of the three keys had been written. See prefsWriter.
				final Thread outer = prefsWriter;
				prefsWriter = Thread.currentThread();
				try
				{
					// All three every time: a check item that only wrote its own key would leave the other two
					// unstored the first time a fresh profile touches the menu.
					cm.setConfiguration(BankPriceMovementConfig.GROUP, SHOW_VALUE_KEY, hero.value());
					cm.setConfiguration(BankPriceMovementConfig.GROUP, SHOW_GP_KEY, hero.gp());
					cm.setConfiguration(BankPriceMovementConfig.GROUP, SHOW_PCT_KEY, hero.pct());
				}
				finally
				{
					prefsWriter = outer;
				}
			}

			@Override
			public ViewOptions loadOptions()
			{
				return optionsFromConfig();
			}

			@Override
			public void saveOptions(ViewOptions options)
			{
				if (options == null)
				{
					return;
				}
				final ConfigManager cm = configManager;
				if (cm != null)
				{
					// Five writes since addendum AO, one change of mind, guarded exactly as saveHero's three
					// are: ConfigManager posts a ConfigChanged on this very thread as each key lands, and the
					// check item that was ticked has already applied the switch itself. Unguarded, ticking one
					// item would recompute the whole bank value again for every other key, from a config in
					// which only some of the five had been written - and each of those figures would be wrong
					// on screen for as long as the recompute took.
					final Thread outer = prefsWriter;
					prefsWriter = Thread.currentThread();
					try
					{
						// All five every time, for saveHero's reason: an item that wrote only its own key would
						// leave the others unstored the first time a fresh profile touched the menu.
						cm.setConfiguration(BankPriceMovementConfig.GROUP, COUNT_CASH_KEY, options.countCash());
						cm.setConfiguration(BankPriceMovementConfig.GROUP, COUNT_UNTRADEABLES_KEY,
							options.countUntradeables());
						// T1, and the one of the five that is worth a fetch: PriceService stops asking for the
						// traded feeds altogether when this goes off, so it must be stored before the service is
						// told below or a relaunch would quietly turn them back on.
						cm.setConfiguration(BankPriceMovementConfig.GROUP, LIVE_PRICES_KEY, options.livePrices());
						// Y1. Nothing is fetched for this one - the inventory and the worn gear come from the
						// client, not from the wiki - but it is stored the same way and before the service is
						// told below, or the choice would be forgotten by the next launch.
						cm.setConfiguration(BankPriceMovementConfig.GROUP, COUNT_INVENTORY_KEY,
							options.countInventory());
						// AH. Nothing is fetched or recomputed for this one - it decides whether the sidebar's
						// tooltips are handed to Swing at all - but it is stored with the rest so a reader who
						// turned the hovers on does not meet a silent sidebar again next launch.
						cm.setConfiguration(BankPriceMovementConfig.GROUP, SHOW_HOVER_TEXT_KEY,
							options.showHoverText());
					}
					finally
					{
						prefsWriter = outer;
					}
				}
				// ...and then, OUTSIDE the guard and whether or not there was a manager to write to, the half the
				// guard just suppressed. The gear's check item paints the card and the rows itself, but the sums
				// behind the bank value are the service's (Q4/Q5), and the ConfigChanged that would have carried
				// them was skipped as this thread's own write. A service that already holds these options does
				// nothing with them, so saying it here as well as on the config round trip cannot double-count.
				final PriceService s = service;
				if (s != null)
				{
					s.setOptions(options);
				}
			}

			@Override
			public BandPresets loadPresets()
			{
				return presetsFromConfig();
			}

			@Override
			public void savePresets(BandPresets presets)
			{
				if (presets == null)
				{
					return;
				}
				final ConfigManager cm = configManager;
				if (cm == null)
				{
					return;
				}
				// ONE key, and still guarded (see prefsWriter): ConfigManager posts its ConfigChanged on this very
				// thread as the write lands, and the boxes that made the change have already applied it to the fold
				// themselves (BankPriceMovementPanel.setPresets applies, then calls this). The round trip would
				// therefore relabel four chips that are already right, from the EDT, while the user is still typing
				// in the next box.
				//
				// Nothing else happens here - no setOptions tail as saveOptions has, and no setFilter: the service
				// has never been told what the chips offer, because it decides nothing about them.
				final Thread outer = prefsWriter;
				prefsWriter = Thread.currentThread();
				try
				{
					// The stored form is the value's OWN text (Z1): ascending, trimmed, in the shorthand the boxes
					// print, so what the settings page shows and what the three boxes show are the same string.
					cm.setConfiguration(BankPriceMovementConfig.GROUP, BAND_PRESETS_KEY, presets.format());
				}
				finally
				{
					prefsWriter = outer;
				}
			}

			@Override
			public Boolean loadFoldOpen()
			{
				return foldOpenFromConfig();
			}

			@Override
			public void saveFoldOpen(boolean open)
			{
				final ConfigManager cm = configManager;
				if (cm == null)
				{
					return;
				}
				// ONE key and no service, exactly as savePresets above (AA1), and guarded for the same reason:
				// ConfigManager posts its ConfigChanged on this very thread as the write lands, and the band
				// button that made the change has already taken the fold out of the header itself
				// (BankPriceMovementPanel.pressFold applies, then calls this). The round trip would re-lay the
				// header a second time for a state it is already in.
				final Thread outer = prefsWriter;
				prefsWriter = Thread.currentThread();
				try
				{
					cm.setConfiguration(BankPriceMovementConfig.GROUP, FOLD_OPEN_KEY, open);
				}
				finally
				{
					prefsWriter = outer;
				}
			}
		};
	}

	private static Integer clampToInt(long gp)
	{
		if (gp <= 0)
		{
			return 0;
		}
		return (int) Math.min(gp, Integer.MAX_VALUE);
	}

	// ---------------------------------------------------------------- account identity

	/**
	 * CLIENT THREAD. Reads and caches the logged-in account's hash and RuneScape profile type, and answers the
	 * hash. {@code Client.getAccountHash()} comes from {@code com.jagex.oldscape.pub.OAuthApi} (clone
	 * OAuthApi.java:32) and {@code RuneScapeProfileType.getCurrent(client)} (clone RuneScapeProfileType.java:60)
	 * never answers null, so the pair {@link PriceStore} keys its files on is always well formed.
	 */
	private long rememberAccount()
	{
		final long hash = client.getAccountHash();
		accountHash = hash;
		profileType = RuneScapeProfileType.getCurrent(client).name();
		return hash;
	}

	/** The cached pair, for the dev bridge's synthetic {@code bank=} (developer mode only). */
	private BpmCommands.Account account()
	{
		return new BpmCommands.Account()
		{
			@Override
			public long hash()
			{
				return accountHash;
			}

			@Override
			public String profileType()
			{
				return profileType;
			}
		};
	}
}
