package com.bankpricemovement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.api.ItemComposition;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The orchestrator between the guide-price history client, the store, the bank capture and the panel
 * (contract C20-C27 as rewritten by addendum K and again by addendum L - lines L1, L3, L5, L7, L10, L11, L13;
 * {@code docs/bank-price-movement-addendum-L-2026-09-08.md}). It owns the current bank snapshot, the id-to-wiki-name
 * mapping, the guide page's revision INDEX, the newest guide table R0, one baseline per {@link MovementWindow},
 * the derived anchor day, and the rows computed for the current filter; it decides WHEN the wiki is asked (L10)
 * and it is the only thing that publishes to the panel.
 *
 * <p><b>The two sides of a movement.</b> "Now" is the Jagex GUIDE price RuneLite already holds:
 * {@code ItemManager.getItemPriceWithSource(id, false)} - the {@code price} field of RuneLite's price table,
 * never the "use wiki prices" toggle (L1, K1; clone {@code ItemManager.java:328-368}, tag
 * runelite-parent-1.12.37: {@code useWikiPrice ? getWikiPrice(ip) : ip.getPrice()} at :356; the table is
 * refreshed every 30 minutes by {@code scheduleWithFixedDelay(this::refreshPrices, 0, 30, MINUTES)} at :218).
 * No request is made for it, and 0 means "no price". "Then" is the same Jagex table as the wiki republishes it
 * ({@code Module:GEPrices/data.json}) on the CALENDAR DAY the window asks for, keyed by item NAME; the service
 * joins it onto ids itself (L8, {@link #project}).
 *
 * <p><b>The one carve-out on "now"</b> ({@link #rewrittenByItemMapping}). {@code getItemPriceWithSource} does not
 * always answer the item's OWN guide price: at {@code ItemManager.java:348} it asks {@code ItemMapping.map(id)}
 * first, and where a mapping exists it ignores its price table entirely and returns the SUM of the mapped
 * TRADEABLE components (:358-363). Four GE-tradeable ids in the mapping's expansion are affected - Black mask
 * (10) folds onto Black mask, Ring of wealth (5) onto Ring of wealth (a permanent fake -18 % on a stack the user
 * really holds), and both Crystal 2h axe ids onto a crystal tool seed plus a dragon felling axe. For such an id
 * RuneLite's answer is another item's price, so it is not asked for at all: "now" is taken from the newest guide
 * table R0 instead (the item's own series, one Jagex day behind while RuneLite leads), and the row is left
 * unpriced when R0 does not name it either. Those ids also drop out of the agreement sample below, which is
 * exactly right - they could only ever disagree.
 *
 * <p><b>Untradeable stacks, and their parts</b> (addendum Q line Q5, addendum R line R2). A stack the exchange does
 * not list has no series of its own. While "Include untradeable items" is on, such a stack is priced at the SUM of the
 * tradeable parts RuneLite maps it onto ({@link BankItem#parts}, recorded by {@code BankReader}) - a Crystal body at
 * three Crystal armour seeds - with every part's "now" and "then" taken through the very rules above, the carve-out
 * included ({@link #partsNow}, {@link #partsSum}). The row is then an ordinary movement row that happens to say
 * {@code PARTS}. A stack RuneLite maps to nothing, or one whose parts the guide tables cannot ALL price, keeps Q5's
 * High Alchemy value and its dash instead - half a sum is not the item's price. With the switch off none of this
 * runs: the stacks are gone from {@link Inputs#items} before a price is asked for.
 *
 * <p><b>What the player is carrying</b> (addendum Y lines Y2 and Y3). While "Include inventory and worn gear" is on,
 * a computation is over the MERGED stacks: the bank's, the inventory's and the worn gear's folded onto canonical ids
 * with the quantities added ({@link #stacksOf}), so an item held in two places is ONE row that says where its
 * quantity is ({@link MovementRow#bankQuantity()} and its two twins), the carried coins join the bank's under the
 * cash switch ({@link #cashOf}), and {@link Status#bankItems()} and the bank value count the merged list. The two
 * containers are read by the PLUGIN, on the client thread, at the two moments the bank itself is read - the bank
 * container event, and {@link #refreshNow()} through the hook of {@link #setCarriedReader} - and ride on the
 * snapshot ({@link BankSnapshot#inventory}), so flipping the switch costs no bank visit and no request. With the
 * switch off every figure is the bank's alone, byte for byte.
 *
 * <p><b>Why a calendar day and not "now minus the window"</b> (L-C, L-D). Addendum K fetched "the newest
 * revision at or before now - W seconds". Six analysts measured that against live data and it picked the right
 * Jagex day on only 50-56 % of clock hours, because the wiki's bot writes day D's table at a random hour of day
 * D and RuneLite's table leads the wiki's by one Jagex day for roughly half of every day. The GE site's own
 * arithmetic is {@code daily[D] - daily[D-N]} over calendar days (L-B), so this service now:
 * <ol>
 * <li>fetches the page's revision INDEX once (L4, {@link GuidePriceClient#fetchRevisionIndex}) and the newest
 * table R0 (L3);</li>
 * <li>DERIVES the anchor day D from the data on every recompute ({@link #deriveAnchorDay}, L3): over bank items
 * that have both a RuneLite price and an R0 value, if at least {@value #AGREE_MIN_SAMPLES} items compare and at
 * least {@value #AGREE_THRESHOLD} of them agree, D is R0's own {@code %LAST_UPDATE%} day; if enough compare but
 * they disagree, RuneLite already holds the NEXT Jagex day and D is that day plus one; with fewer than
 * {@value #AGREE_MIN_SAMPLES} comparable items nothing can be told, D is R0's day and R0 also stands in as
 * "now" for the rows. Never a wall clock, never a hard-coded rollover hour (L9);</li>
 * <li>picks every window's baseline revision LOCALLY from the index ({@link #pickBaseline}, L5:
 * {@code RevisionRef.pickThen(index, D.minusDays(N))}), fetches the bodies it does not already hold in ONE
 * batched call ({@link GuidePriceClient#fetchTables}, L6), and validates each body's own day marker
 * ({@code GuideSnapshot.dataDay()}, L7) against the date asked for, with the single retry of L5 when a body is
 * stale ({@link #resolveLocked}).</li>
 * </ol>
 *
 * <p><b>Threads.</b> Five threads touch this object and none of them may wait on another:
 * <ul>
 * <li>the client thread calls {@link #setLoggedIn} and {@link #setBank} from the plugin's event handlers;</li>
 * <li>the EDT calls {@link #setVisible}, {@link #refreshNow}, {@link #setFilter} (also for {@code ConfigChanged},
 * which arrives on the AWT thread - playbook 7.2), the listener methods and the bridge getters;</li>
 * <li>the injected {@link ScheduledExecutorService} runs every load, save, projection, row computation, baseline
 * selection and fetch start. RuneLite binds a SINGLE-THREAD scheduled executor shared with the whole client
 * ({@code runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java:128}:
 * {@code new ExecutorServiceExceptionLogger(Executors.newSingleThreadScheduledExecutor())}), so every task here
 * is short and never blocks on anything;</li>
 * <li>OkHttp's dispatcher completes the fetch futures ({@link GuidePriceClient}); a completion hops straight
 * back onto the executor before it reads or writes any state here;</li>
 * <li>the client thread once more, for the one read that has to be there: the guide price (below).</li>
 * </ul>
 * All mutable state sits behind one private lock that is held only for field reads and writes - never across
 * a store call, a wiki call, a client-thread hop, an executor submission or a listener. Listeners are invoked
 * ONLY through the {@code edt} consumer ({@code SwingUtilities::invokeLater} in the plugin, C20), so the panel
 * sees rows on the EDT and on nothing else.
 *
 * <p><b>Why the executor is the only caller of the store.</b> Every {@link PriceStore} method is a synchronous
 * disk read or write. A synchronous write on the client thread froze the client for seconds once already in
 * this workspace (playbook 7.2), and the EDT must not wait on a disk either; so the public methods only capture
 * their arguments under the lock and hand the IO to the executor (C22, C23).
 *
 * <p><b>Why every row computation hops to the client thread.</b> {@code getItemPriceWithSource} is NOT safe off
 * the client thread: before it touches the price table (an {@code ImmutableMap} replaced wholesale, :103 and
 * :276-281, which on its own would be fine) it calls {@code getItemComposition(itemID)} at :339 to fold a noted
 * id onto its unnoted one - {@code client.getItemDefinition(itemId)} at :459-462, a read of the game's
 * definition cache. The assertion for that read lives in the closed injector rather than in the source clone, so
 * the evidence relied on is RuneLite's own usage: {@code LootTrackerPlugin.java:558-570} hops with
 * {@code clientThread.invokeLater} and states "convertToLootTrackerRecord must be called on client thread"
 * before it reads compositions and prices, and {@code ItemManager.loadImage} (:490-508) hops the same way and
 * refuses below {@code LOGIN_SCREEN}. This workspace's {@code BankReader.read} is client-thread-only for the same
 * reason. So {@link #compute} snapshots its inputs on the executor, hops once to the client thread
 * ({@code ClientThread.java:45-70}: a Runnable handed in from another thread is queued and run on the game
 * thread) to read every guide price into one array - and, only for an item the mapping cannot name,
 * {@code ItemComposition.getMembersName()} (L8 b; {@code runelite-api/.../ItemComposition.java:43-49}: the real
 * name "even if the player is on a F2P server", where {@code getName()} carries " (Members)") - then hops back
 * to the executor to finish. The gate LootTracker adds ("can't be run while the client is starting", game state
 * below LOGIN_SCREEN) holds here by construction: a bank snapshot only exists after {@link #setLoggedIn} or
 * {@link #setBank}, both of which the plugin calls from a LOGGED_IN client, and a client never drops below
 * LOGIN_SCREEN again once it has been there.
 *
 * <p><b>Cadence (L10).</b> One timer every {@value #TICK_MS} ms while the sidebar is visible (first run at once
 * on activation): it recomputes the rows (L1 - the series is a daily step and RuneLite refreshes its own table
 * every 30 minutes), refetches the index when it is older than {@value #HISTORY_MAX_AGE_MS} ms (L4), fetches R0
 * when the index names a newer revision than the one in memory, and picks every window's baseline locally,
 * fetching only the bodies whose revision id differs from the stored one - all of them in one batched call.
 * The mapping is refetched weekly on the same tick (K3). The traded {@code /latest} snapshot of addendum T rides
 * the same tick too, but only once the one in hand is at least {@value #LATEST_MIN_AGE_MS} ms old (addendum AS,
 * decision 3): the first run on every activation would otherwise download it again however recently it landed. A
 * manual refresh forces the index (the rest follows from it) - and, with live prices on, the {@code /latest}
 * snapshot whatever its age - behind {@value #MANUAL_COOLDOWN_MS} ms; nothing at all is fetched while the sidebar
 * is hidden. A revision is immutable, so a body is never fetched twice for the same revision id in one session,
 * and the newest table R0 is refetched only when the index's newest revision changes.
 *
 * <p><b>Failure (L11).</b> A window whose target predates the whole index is not an error - it shows
 * "No 180d history" and nothing is requested for it. A stored baseline is kept until a newer body parses and
 * validates; a failed fetch marks the service degraded and is retried on the next tick. A body the wiki returned
 * but the client refused (no usable day marker, L7) is remembered as unusable for the session so it is never
 * asked for again.
 *
 * <p><b>Ordering without waiting.</b> A row computation can be overtaken - a fetch lands while the client-thread
 * hop is pending, or two filter changes arrive back to back. Every computation takes a generation number and a
 * result is dropped when a newer one has been requested, so the last publish always reflects the newest state.
 * The persisted-bank load of {@link #setLoggedIn} is dropped the same way when a later login superseded it, or
 * when {@link #setBank} already delivered a fresher snapshot for that account (the plugin's start-up replay of
 * the cached bank container, {@code GameEventManager.java:122}, can land before the disk read finishes). A map
 * read from disk never replaces a fresher fetched one.
 *
 * <p><b>Stop.</b> {@link #stop()} flips a flag, cancels the timer and clears the listeners; it never waits.
 * Every executor task, fetch completion, client-thread hop and EDT publish re-checks the flag and drops itself.
 * The one exception is a store WRITE already queued: it still runs, because it carries data the user produced,
 * and {@link #flush()} hands the plugin a future over exactly those writes for {@code ClientShutdown.waitFor}
 * ({@code ClientShutdown.java:41-44}; the client waits with a total timeout, :46-71, and never calls a plugin's
 * shutDown on exit).
 *
 * <p>Not final and no method final: the panel's tests mock this class with Mockito 4.11 (no mockito-inline).
 */
public class PriceService
{
	private static final Logger log = LoggerFactory.getLogger(PriceService.class);

	/**
	 * The one timer while the sidebar is visible (L1, L10): rows recomputed, the index's age checked, the baselines
	 * re-picked, and - with live prices on (addendum T) - the traded {@code /latest} snapshot asked for again once it
	 * is {@link #LATEST_MIN_AGE_MS} old, which a snapshot the previous run fetched always is by the next one (addendum
	 * AS). Thirty minutes because the guide series is a daily step and RuneLite itself refreshes its price table every
	 * thirty minutes ({@code ItemManager.java:218}); the anchor day is re-derived on every run, so the panel follows
	 * the Jagex rollover within one tick (L3).
	 */
	public static final long TICK_MS = 30L * 60L * 1000L;

	/**
	 * The revision index is refetched when older than this (L4, L10): the bot publishes exactly one revision a
	 * day (247 of 247 days, L-C), so six hours catches the day's new revision the same day without asking for
	 * the same 250 lines four times an hour.
	 */
	public static final long HISTORY_MAX_AGE_MS = 6L * 60L * 60L * 1000L;

	/**
	 * The mapping is refetched when older than this (K3, K5): it only changes when Jagex adds items, and it is
	 * the largest of the responses.
	 */
	public static final long MAPPING_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;

	/**
	 * Two manual refreshes closer than this: the second sends nothing (L10) and says so in a status line - unless the
	 * same click had just re-read the items, a refresh the user can see, and then it says nothing (addendum AS, line
	 * AS8, {@link #refreshNow(boolean)}).
	 */
	public static final long MANUAL_COOLDOWN_MS = 30L * 1000L;

	/**
	 * How many bank items must have BOTH a RuneLite price and an R0 value before their agreement is trusted to
	 * derive the anchor day (L3, "n >= 20"). Below it the two tables cannot be told apart and R0's own day is
	 * taken, with R0 standing in as "now".
	 */
	public static final int AGREE_MIN_SAMPLES = 20;

	/**
	 * The share of comparable items that must agree for RuneLite's table to be called the same Jagex day as R0
	 * (L3, "agree >= 0.90"). Measured separation: 100 % on the same day against about 41 % one day apart, so
	 * 0.90 sits far from both; compared in integers ({@code matches * 10 >= n * 9}) so 18 of 20 is exactly 0.90.
	 */
	public static final double AGREE_THRESHOLD = 0.90d;

	/**
	 * How many guide tables beyond R0 and the adopted baselines stay in memory after a reconcile (see
	 * {@link #evictLocked()}). Four is one previous R0 plus the losers of up to three L5 retries; at about 4,600
	 * entries a table, that is a couple of megabytes at most.
	 */
	static final int EXTRA_TABLES_KEPT = 4;

	/**
	 * How many revision lines the merged index keeps (B027, {@link #finishIndex}). One call can only ever return
	 * {@value GuidePriceClient#INDEX_LIMIT}; at the measured 1.1 revisions a day 400 lines reach back about a
	 * year, comfortably past the longest window, and the whole file is about 21 KB at 250 lines.
	 */
	static final int INDEX_KEPT = 400;

	/**
	 * How long after a FAILED body fetch the next attempt is made, while the sidebar is visible (B109). The
	 * ordinary cadence is {@link #TICK_MS}, and waiting half an hour on a wiki hiccup leaves the user looking at
	 * "Wiki history down - no movement" long after the wiki came back, with no reason to press Refresh.
	 * The delay doubles on each consecutive failure and is capped at the tick, so a real outage settles back onto
	 * the normal thirty minutes rather than knocking at the wiki all session.
	 */
	static final long RETRY_MS = 60L * 1000L;

	/** K7/L7: the header line always opens with this - the prices are the guide prices, whatever else is known. */
	public static final String HEADER_PREFIX = "Guide prices";

	/** K7 wording when the history fetch failed and the window has no stored baseline to show instead. */
	public static final String PROBLEM_HISTORY_UNAVAILABLE = "Wiki history down - no movement";

	// ------------------------------------------- addendum T: the liquidity checks (T3, checks 4 and 5 from V3/V4)

	/**
	 * <b>T3 check 1.</b> How many units of an item must have changed hands in YESTERDAY's bulk bucket (both sides
	 * added) before its live traded price is trusted: {@value}.
	 *
	 * <p>Measured on the user's own bank, 2026-09-12 ({@code docs/research/live-1d-study-2026-09-12.md}): the rows
	 * where live 1d and guide 1d disagree wildly are all thin junk - Compost potion(4) read +1006 %, Tatty larupia
	 * fur +569 %, Harpoon +561 % - because a single trade at a silly price IS the latest mid on an item nobody
	 * trades, while the 13 rows holding a million or more sat a median 2.4 points apart. A hundred units a day is
	 * the line between "somebody is making a market in this" and "one person cleared their bank".
	 */
	public static final long LIVE_MIN_VOLUME = 100L;

	/**
	 * <b>T3 check 2.</b> How far apart the instant-buy and instant-sell sides of {@code /latest} may be, as a
	 * percentage of their mid, before the mid is called meaningless: {@value} %.
	 *
	 * <p>The mid of a wide pair is not a price anybody paid - it is the middle of a gap nobody crossed - and the
	 * same study found that shape behind the worst of the long tail. Ten percent leaves every liquid item alone
	 * (a market maker's own margin is well inside it) and refuses the items whose two sides are a guess.
	 */
	public static final int LIVE_MAX_SPREAD_PCT = 10;

	/**
	 * <b>T3 check 3.</b> How far the live mid may sit from the Jagex guide price, either way, before it is refused:
	 * {@value} % - the user's own "ignore live data changes that is >50 % of the 24 hour value" (addendum T).
	 *
	 * <p>Jagex moves a guide price by at most about 5 % a day, so a live mid half again the guide is not a market
	 * that has moved: it is a manipulated or barely-traded item, or a feed reading the wrong thing. The guide is
	 * the anchor precisely because it cannot run away.
	 */
	public static final int LIVE_MAX_GUIDE_DRIFT_PCT = 50;

	/**
	 * <b>V4 check 5.</b> How far the live mid may sit from YESTERDAY's traded average, either way, before it is
	 * refused: {@value} % - the user's own rule ("ignore live data changes that is >50 % of the 24 hour value",
	 * 2026-09-12) read against the 24-hour figure itself, which is what they named, rather than only against the
	 * guide as check 3 reads it.
	 *
	 * <p>Check 3 cannot catch this on its own, because the guide price of a junk item IS the junk price: Tinderbox
	 * on the live look of 2026-09-12 quoted 100 / 96 (mid 98) against a guide in the same nineties, so it sailed
	 * through the sanity check - while the day it had actually traded on averaged about 35 (37 x 5,063 bought,
	 * 12 x 484 sold), and the row read <b>+181 %</b> at the top of "Biggest gainers". A live price nearly triple
	 * what the whole of yesterday paid is not a day's move; it is the top of a thin book.
	 *
	 * <p>Yesterday ONLY. A 90-day move over half is ordinary - that is what a 90-day window is for - so the older
	 * windows are untouched by this and keep T4's own rule.
	 */
	public static final int LIVE_MAX_DAY_JUMP_PCT = 50;

	/**
	 * <b>T3 check 2, the freshness half.</b> How old either side of a {@code /latest} quote may be: {@value}
	 * seconds, one day. A price last traded a week ago is not a live price whatever its spread looks like.
	 */
	public static final long LIVE_QUOTE_MAX_AGE_SECONDS = 24L * 60L * 60L;

	/**
	 * <b>T7.</b> How old the stored {@code /latest} snapshot may be before it stops being used at all: six hours.
	 * Past it every row falls back to the guide price and the card says so ({@link #LIVE_UNAVAILABLE}). Six hours
	 * rather than the thirty-minute tick because a stored snapshot is there for exactly the case where the tick
	 * cannot fetch - an outage, a laptop shut since this morning - and a few hours of drift on a liquid item is
	 * still closer to the market than yesterday's guide step.
	 */
	public static final long LIVE_LATEST_MAX_AGE_MS = 6L * 60L * 60L * 1000L;

	/**
	 * <b>Addendum AS, decision 3.</b> How old the {@code /latest} snapshot in hand must be before {@link #onTick} asks
	 * for another: {@value} ms, five minutes. Younger than that, the tick leaves it alone - and offers each window's
	 * traded bucket against it, the one other thing that fetch's landing did. A manual {@link #refreshNow()} asks
	 * whatever the age, and start-up and the switch turning on keep T7's own six-hour test
	 * ({@link #requestLatestIfLive}).
	 *
	 * <p>Why the tick needs a floor at all: {@link #setVisible} runs it at once on EVERY activation, so each return to
	 * this sidebar tab downloaded the whole feed again - 4,535 items, about 335 KiB raw and 71 KiB gzipped as measured
	 * on 2026-09-08 ({@code docs/research/bank-price-movement-research-2026-09-08.md}, claim C2) - then wrote it to
	 * disk and recomputed and republished the rows ({@link #finishLatest}), to replace a snapshot fetched moments
	 * earlier. Five minutes because the wiki's own answer carries {@code max-age=60} and the same research reads that
	 * as "poll in minutes, not 60 s", while {@link #TICK_MS} is six times longer: a snapshot the previous run fetched
	 * is always due at the next one, so the periodic cadence is untouched and only a quick return to the tab is
	 * spared. The cost is a bound, not a cadence: a tab reopened inside the five minutes restarts the timer without a
	 * fetch, so while the sidebar is showing the snapshot can grow to just under {@code TICK_MS + LATEST_MIN_AGE_MS}
	 * old rather than {@code TICK_MS}.
	 */
	public static final long LATEST_MIN_AGE_MS = 5L * 60L * 1000L;

	/** T7: what {@link Status#degradedReason()} says once the traded feed is unusable and every row is on the guide. */
	public static final String LIVE_UNAVAILABLE = "live prices unavailable - showing guide prices";

	/** T3/T6: the refusal when a feed this row needed was simply not in hand - no quote, or no bucket at all. */
	public static final String LIVE_NO_DATA = "no live data";

	/**
	 * What an item the day's feed does not NAME reads as: a bucket of no prices and no trades. The difference
	 * between this and a null bucket is the difference between a measurement and an absence - the feed answered
	 * for that day and this item was not in it, so it really did trade nothing, and check 1 says "0 traded
	 * yesterday" rather than {@link #LIVE_NO_DATA}. Immutable, so one instance serves every caller.
	 */
	private static final TradedPriceClient.Bucket NOT_TRADED = new TradedPriceClient.Bucket(null, 0L, null, 0L);

	/**
	 * The largest price {@link #liveRefusal} will reason about, above which a figure is refused as
	 * {@link #LIVE_NO_DATA} rather than measured: {@link Long#MAX_VALUE} / 200.
	 *
	 * <p>Not a market rule - the most valuable item in the game is four orders of magnitude below it - but an
	 * arithmetic one. The five checks are integer comparisons that multiply a price by up to 200
	 * ({@code spread x 100} against {@code mid x 10}, {@code |mid - guide| x 100} against {@code guide x 50}, and
	 * since addendum V {@code gap x 100} against {@code bucketMid x 10} and {@code |mid - yesterday| x 100}
	 * against {@code yesterday x 50}), and a product that wrapped would come out NEGATIVE and pass every check
	 * silently, putting a nonsense price on a row and into the bank total. A ceiling is the one line that makes
	 * "a wrapped comparison cannot happen" a property rather than a hope, and nothing the wiki has ever served
	 * comes near it.
	 */
	static final long LIVE_SANE_PRICE_CEILING = Long.MAX_VALUE / 200L;

	private final GuidePriceClient wiki;
	/**
	 * The traded feeds of addendum T, or null when the plugin handed none over - in which case not one traded
	 * request is ever made and every row is the guide-only row of addenda K to S, whatever the switch says. Set
	 * once, at construction or through {@link #setTradedClient}, before {@link #start()}; read under the lock.
	 */
	@Nullable
	private TradedPriceClient traded;
	private final PriceStore store;
	private final ItemManager itemManager;
	private final ClientThread clientThread;
	private final ScheduledExecutorService executor;
	private final LongSupplier clockMillis;
	private final Consumer<Runnable> edt;

	/** Guards every field below except the thread-safe {@link #listeners} and the volatile {@link #stopped}. */
	private final Object lock = new Object();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();

	/**
	 * One id-keyed baseline per window, {@link PriceMap#EMPTY} until loaded or fetched; never null. This is what
	 * the rows read and what the store persists ({@code baseline-<window>.json}, K11), stamped with the table's
	 * own day marker as {@code bucketSeconds} (L7).
	 */
	private final Map<MovementWindow, PriceMap> baselines = new EnumMap<>(MovementWindow.class);
	/**
	 * Every guide table in memory, keyed by revision id: R0 and the table behind each adopted baseline, plus the
	 * last few fetched bodies that are not (yet, or any more) in use. Insertion-ordered so {@link #evictLocked()}
	 * can drop the oldest spare ones first; a table is about 4,600 entries.
	 */
	private final Map<Long, GuideSnapshot> tablesByRev = new LinkedHashMap<>();
	/**
	 * What is known about a revision's body: present with a day = fetched (or read off a baseline file) and its
	 * {@code %LAST_UPDATE%} day; present with null = the wiki answered but the body was unusable (L7), never to be
	 * asked for again; absent = never seen. This is what bounds L5's retry to ONE: a candidate that is already
	 * known is judged, never refetched.
	 */
	private final Map<Long, LocalDate> knownDays = new HashMap<>();
	/** Windows whose target predates the whole index (L11: "No 180d history", not an error). */
	private final EnumSet<MovementWindow> noHistory = EnumSet.noneOf(MovementWindow.class);
	/** Windows whose every candidate revision came back unusable - nothing left to try. */
	private final EnumSet<MovementWindow> exhausted = EnumSet.noneOf(MovementWindow.class);
	/** Windows a failed body fetch was serving and that have not been served since - drives the K7 line. */
	private final EnumSet<MovementWindow> baselineFailed = EnumSet.noneOf(MovementWindow.class);
	/** Store writes queued on the executor and not yet finished; what {@link #flush()} waits for. */
	private final List<CompletableFuture<Void>> pendingWrites = new ArrayList<>();

	// ---- addendum T: the traded feeds (T2, T7)

	/** The newest {@code /latest} snapshot, item id to quote; empty until loaded or fetched, never null. */
	private Map<Integer, TradedPriceClient.Quote> latest = Collections.emptyMap();
	/**
	 * When {@link #latest} was fetched, epoch millis; 0 = none. What T7's six-hour rule compares, and the tick's
	 * five-minute floor of addendum AS ({@link #latestDueLocked}). Stamped only by a fetch that SUCCEEDED (or read off
	 * disk with the file), never by an attempt, so a failure can never make the tick think it holds a fresh snapshot.
	 */
	private long latestAtMillis;
	/** One traded daily bucket per window, {@link PriceStore.TradedDay#EMPTY} until loaded or fetched. */
	private final Map<MovementWindow, PriceStore.TradedDay> tradedDays = new EnumMap<>(MovementWindow.class);
	/**
	 * The WANTED calendar day each window's traded bucket has already been asked for this session, whether the
	 * answer arrived, failed or fell one day back (U2) - addendum U's "fetched once per UTC day per window, and at
	 * start-up after a day change". Always {@code liveDay - N} and never the day a fallback landed on, so one
	 * empty answer costs one extra request and not a request per recompute.
	 *
	 * <p>Cleared for every window by a manual refresh, which is the user's lever after a failure; otherwise a
	 * window that failed waits for the live day to move, exactly as T7 words it ("leaves that window GUIDE for
	 * every row").
	 */
	private final Map<MovementWindow, LocalDate> tradedAsked = new EnumMap<>(MovementWindow.class);
	/** Windows whose bucket request is out; one per window at a time. */
	private final EnumSet<MovementWindow> tradedInFlight = EnumSet.noneOf(MovementWindow.class);
	private boolean latestInFlight;
	/** True after a {@code /latest} fetch failed and until one succeeds - half of T7's degraded test. */
	private boolean liveFailed;
	/**
	 * What the last computation found: the switch was on, a fetch had failed, and no usable snapshot was left -
	 * so every row was GUIDE and the card says {@link #LIVE_UNAVAILABLE} (T7). Set in {@link #commit} from the
	 * inputs that computation actually used, never from the state as it stands.
	 */
	private boolean liveUnavailable;
	/**
	 * Whether the last computation had the traded series available to it at all - the switch on AND a client to
	 * honour it with. What {@link Status#live()} is gated on, rather than the switch alone: a build with no
	 * traded client has nothing to report however the switch stands, and reporting zeroes there would read as a
	 * feed that answered nothing.
	 */
	private boolean liveOnUsed;
	/** The last computation's live / guide / alch stack counts, before the filter - {@link Status#live()}. */
	private int liveRows;
	private int guideRows;
	private int alchRows;
	/**
	 * The live day the last computation counted back from (U1), and the day each window's bucket really held
	 * (null where a window had none). Set in {@link #commit} from the inputs that computation used, like the three
	 * counts above and for the same reason: a status must never name a day its own figures were not built with.
	 */
	@Nullable
	private LocalDate liveDayUsed;
	private final Map<MovementWindow, LocalDate> windowDaysUsed = new EnumMap<>(MovementWindow.class);

	/** Item id to wiki name (L8 a); empty until loaded or fetched, never null, always unmodifiable. */
	private Map<Integer, String> mapping = Collections.emptyMap();
	/** Folded wiki name to the id that owns it in {@link #mapping} - the L8 b refusal test. Never null. */
	private Map<String, Integer> owners = Collections.emptyMap();
	/**
	 * {@link #mapping} with its names already folded to guide-table keys ({@link #foldedNamesOf}) - what a
	 * projection and the agreement test look prices up by. Never null; rebuilt with {@link #owners}.
	 */
	private Map<Integer, String> foldedNames = Collections.emptyMap();
	/** When {@link #mapping} was fetched, epoch millis; 0 = none. What the weekly rule compares. */
	private long mappingAtMillis;
	/** The guide page's revision history, newest first, unmodifiable; empty until loaded or fetched (L4). */
	private List<RevisionRef> index = Collections.emptyList();
	/** When {@link #index} was fetched, epoch millis; 0 = none. What the six-hour rule compares (L4). */
	private long indexAtMillis;
	/** The newest guide table (L3); {@link GuideSnapshot#EMPTY} until fetched. Never persisted: refetched on activation. */
	private GuideSnapshot r0 = GuideSnapshot.EMPTY;
	/** {@link #r0} projected onto ids (the agreement test and the degraded "now"); EMPTY with it. */
	private PriceMap r0Map = PriceMap.EMPTY;
	/** The derived anchor day D (L3); null until a computation with R0 and a bank has run. */
	@Nullable
	private LocalDate anchorDay;
	/** The last agreement, matches / n, or -1 when nothing compared (L3). */
	private double agree = -1.0d;
	/** How many bank items had both a RuneLite price and an R0 value in the last computation (L3's n). */
	private int agreeSamples;
	/** True when the last computation used R0 as "now" because fewer than {@link #AGREE_MIN_SAMPLES} compared (L3). */
	private boolean anchorDegraded;
	/**
	 * True when the last computation found RuneLite's price table a day BEHIND the wiki's newest rather than a day
	 * ahead of it, and stepped the anchor day back to match ({@link #agrees}, B002).
	 */
	private boolean anchorBehind;
	/** True after an index or body fetch failed and until one succeeds; the rows run on stored data meanwhile (L11). */
	private boolean historyFailed;
	/** Whether the "the index is full and still does not reach every window" line has been logged this session (B009). */
	private boolean indexReachLogged;
	private BankSnapshot bank = BankSnapshot.EMPTY;
	/**
	 * The last snapshot {@link #setBank} handed to the store, for its content test; null until one is written.
	 * Never the disk copy {@link #setLoggedIn} loads - that one has not been re-written, and comparing against it
	 * would suppress the first save of a bank captured after an account switch.
	 */
	@Nullable
	private BankSnapshot savedBank;
	/**
	 * What {@link #refreshNow()} runs to re-read the player's INVENTORY and WORN gear (addendum Y, line Y2) -
	 * registered by the plugin at start-up ({@link #setCarriedReader}) and nulled at shutdown, because only the
	 * plugin can hop to the client thread and ask the client for a container. Null in every headless test that does
	 * not care, and null while nothing is logged in.
	 */
	@Nullable
	private Runnable carriedReader;
	private RowFilter filter = RowFilter.DEFAULT;
	/**
	 * The five view switches (Q3, T1, Y1) as they stand NOW - what the next computation will use
	 * ({@link #setOptions}). {@link ViewOptions#DEFAULT} until the plugin hands the config's over at start-up,
	 * and the default is today's behaviour exactly.
	 */
	private ViewOptions options = ViewOptions.DEFAULT;
	/**
	 * The switches the PUBLISHED rows, counts and bank value were computed under - {@link Status#options()}. It
	 * catches up with {@link #options} at the next {@link #commit}, so a status never claims a setting its own
	 * figures were not built with.
	 */
	private ViewOptions optionsUsed = ViewOptions.DEFAULT;
	private List<MovementRow> rows = Collections.emptyList();
	/** The bank value line of the last computation (M3); {@link PortfolioSummary#EMPTY} until one has run. */
	private PortfolioSummary portfolio = PortfolioSummary.EMPTY;
	private Status status;
	/** How many bank items the last computation priced (before the filter): the source is GUIDE iff > 0. */
	private int pricedRows;
	/** When the last computation with a priced row read RuneLite's table; 0 = never. {@link Status#pricesAtMillis()}. */
	private long guideReadAtMillis;
	private boolean loggedIn;
	private boolean visible;
	private boolean started;
	private boolean indexInFlight;
	private boolean tablesInFlight;
	private boolean mappingInFlight;
	private boolean manualRefreshed;
	private long lastManualRefreshMillis;
	/**
	 * A manual refresh that arrived while an index request was already in flight (B011): nothing new could be
	 * sent, so the intent is remembered here and ORed into the {@code manual} flag that request completes with -
	 * which is what authorises a body fetch while the sidebar is hidden.
	 */
	private boolean manualPending;
	private long loginGeneration;
	private long computeGeneration;
	@Nullable
	private ScheduledFuture<?> tickTimer;
	/**
	 * The one-shot timers, one pending per {@link OneShot} at most: arming one replaces the one it holds and
	 * {@link #stop()} cancels the lot. {@link #armOneShot} is the only writer.
	 */
	private final Map<OneShot, ScheduledFuture<?>> oneShots = new EnumMap<>(OneShot.class);
	/** The delay that retry used, doubling per consecutive failure and reset by the first success (B109). */
	private long retryDelayMs;

	/** Volatile: written under the lock by {@link #stop()}, read without it by every late callback. */
	private volatile boolean stopped;

	/**
	 * @param wiki         the revision index, the batched bodies and the mapping (L4, L6, K3); mocked in tests
	 *                     with completed or pending futures
	 * @param store        the JSON files (C17-C19, K11, L4); only ever called on {@code executor}
	 * @param itemManager  RuneLite's guide-price table and item compositions (L1, L8 b); only ever called on the
	 *                     client thread
	 * @param clientThread the hop for those reads (class javadoc)
	 * @param executor     RuneLite's injected scheduled executor (single-threaded, shared); a direct-run stub in tests
	 * @param clockMillis  {@code System::currentTimeMillis} in the plugin; settable in tests so every staleness
	 *                     decision is deterministic (C27). Only the "Bank as of HH:MM" clock is ever shown in local
	 *                     time; every DATE decision is UTC (L9) and never touches this clock
	 * @param edt          {@code SwingUtilities::invokeLater} in the plugin; {@code Runnable::run} in tests - the only
	 *                     way a {@link Listener} is ever invoked
	 */
	public PriceService(final GuidePriceClient wiki, final PriceStore store, final ItemManager itemManager,
		final ClientThread clientThread, final ScheduledExecutorService executor, final LongSupplier clockMillis,
		final Consumer<Runnable> edt)
	{
		this(wiki, store, itemManager, clientThread, executor, clockMillis, edt, null);
	}

	/**
	 * The same, with the traded feeds of addendum T (seam S2). The arity above is the pre-T one, kept so every
	 * caller that knows nothing of live prices keeps compiling AND keeps its behaviour: with no traded client
	 * there is no traded request and no live row, whatever {@link ViewOptions#livePrices()} says.
	 *
	 * @param traded the {@code /latest} and {@code /24h} feeds (T2); null is "guide prices only, for ever"
	 */
	public PriceService(final GuidePriceClient wiki, final PriceStore store, final ItemManager itemManager,
		final ClientThread clientThread, final ScheduledExecutorService executor, final LongSupplier clockMillis,
		final Consumer<Runnable> edt, @Nullable final TradedPriceClient traded)
	{
		this.traded = traded;
		this.wiki = Objects.requireNonNull(wiki, "wiki");
		this.store = Objects.requireNonNull(store, "store");
		this.itemManager = Objects.requireNonNull(itemManager, "itemManager");
		this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
		this.edt = Objects.requireNonNull(edt, "edt");
		for (final MovementWindow window : MovementWindow.values())
		{
			baselines.put(window, PriceMap.EMPTY);
			tradedDays.put(window, PriceStore.TradedDay.EMPTY);
		}
		status = buildStatusLocked(null);
	}

	/**
	 * The traded feeds of addendum T, handed over after construction (seam S2) - the plugin builds the client
	 * with the injected OkHttp and Gson and gives it to the service it already made. Must be called before
	 * {@link #start()}; a null client is "guide prices only".
	 */
	public void setTradedClient(@Nullable final TradedPriceClient client)
	{
		synchronized (lock)
		{
			traded = client;
		}
	}

	// ---------------------------------------------------------------- nested types (C20, C21, K13, L13)

	/**
	 * The service's one-shot timers, one slot each. Both obey the same rule - at most one pending, arming
	 * replaces, {@link #stop()} cancels - which is why they share {@link #armOneShot} and this key.
	 */
	private enum OneShot
	{
		/** The publish that removes the refresh-cooldown sentence when the cooldown ends (B042). */
		COOLDOWN_CLEAR,
		/** The backed-off body retry armed by a failed batch (B109). */
		BODY_RETRY
	}

	/**
	 * What KIND of problem a {@link Status}'s sentence is (K7, L11) - chosen where the sentence is written, never
	 * parsed back out of it.
	 *
	 * <p>Why it exists: the panel paints the problem row red for a real fault and grey for a fact of life, and it
	 * used to tell them apart by rebuilding two of the sentences and comparing strings. Any wording change
	 * silently turned a grey line red. The kind travels with the sentence instead, so the two cannot drift.
	 */
	public enum ProblemKind
	{
		/** No problem: {@link Status#problem()} is null. */
		NONE,
		/** A history request is out and this window has nothing older to show meanwhile (K7). */
		PENDING,
		/** The window's target date predates the whole revision index - nothing to fetch, not an error (L11). */
		NO_HISTORY,
		/** The history fetch failed, or every candidate revision proved unusable: the movement is missing (K7). */
		HISTORY_DOWN,
		/** A manual refresh inside {@link #MANUAL_COOLDOWN_MS} (K5/K7/L10). */
		COOLDOWN;

		/**
		 * Whether this is something WRONG rather than something merely absent - the two the panel paints red.
		 * "No 1d history yet" and "No 180d history" are the normal course of events; a failed fetch and a refused
		 * refresh are not.
		 */
		public boolean severe()
		{
			return this == HISTORY_DOWN || this == COOLDOWN;
		}
	}

	/**
	 * One problem sentence and its {@link ProblemKind}, decided together. Package-private and immutable; every
	 * sentence the service publishes is built here or in {@link #problemLocked()}.
	 */
	private static final class Problem
	{
		/** Nothing is wrong: no sentence, {@link ProblemKind#NONE}. */
		static final Problem NONE = new Problem(null, ProblemKind.NONE);

		@Nullable
		private final String text;
		private final ProblemKind kind;

		private Problem(@Nullable final String text, final ProblemKind kind)
		{
			this.text = text;
			this.kind = kind;
		}

		/** A sentence of the given kind; a null sentence is {@link #NONE} whatever kind is offered. */
		static Problem of(@Nullable final String text, final ProblemKind kind)
		{
			return text == null || kind == null ? NONE : new Problem(text, kind);
		}
	}

	/** Receives every publish; invoked only through the {@code edt} consumer given to the constructor. */
	@FunctionalInterface
	public interface Listener
	{
		/**
		 * @param rows   the rows AFTER the filter, an unmodifiable list ({@code MovementMath.apply}); the same
		 *               instance is handed over again by a status-only publish, so a panel can tell "rows changed"
		 *               from "only the header changed" by identity
		 * @param status the header state that goes with those rows
		 */
		void onRows(List<MovementRow> rows, Status status);
	}

	/**
	 * The header state of one publish (C21, extended by K13 and by L13). Immutable - every field final, no
	 * setter - but the CLASS is not final: the panel's and the bridge's tests stub it with Mockito 4.11, which has
	 * no mockito-inline in this workspace and so cannot mock a final class (playbook 7.6). {@link #text()} is the
	 * one line the panel shows.
	 *
	 * <p>Every date here is a UTC calendar day (L9): the guide price steps once a day near the top of the UTC day
	 * (rollover observed 00:47-02:06 UTC, L-A) and {@link #dayLabel} prints a day, never a local clock. The one
	 * local-time value in the header is the "Bank as of HH:MM" capture clock.
	 */
	public static class Status
	{
		/**
		 * What the TRADED feeds delivered for the publish this status belongs to (addendum T, line T8): when the
		 * {@code /latest} snapshot in use was fetched, how many items it names, how the bank's stacks split
		 * between the live series, the guide series and the High Alchemy rule, and - since addendum U, line U4 -
		 * the live CALENDAR behind all of it: the snapshot's own UTC day and the day each window's traded bucket
		 * really holds.
		 *
		 * <p>{@link #OFF} - every figure 0, no days - whenever the {@code livePrices} switch is off, so a reader of
		 * {@code state.live} never has to ask a second question to know the plugin is on the guide prices alone,
		 * and so the bridge's output with the switch off is exactly what it was before addendum T.
		 *
		 * <p>The counts are over the WHOLE bank as the computation saw it, before the gp band, for the same reason
		 * {@link Status#bankItems()} is: "N of M stacks live" is a fact about the bank, not about the page.
		 */
		public static final class LiveStatus
		{
			/** The switch is off (or nothing has been computed yet): no feed, no counts, no days. */
			public static final LiveStatus OFF = new LiveStatus(0L, 0, 0, 0, 0);

			private final long fetchedAtMillis;
			private final int latestItems;
			private final int liveRows;
			private final int guideRows;
			private final int alchRows;
			@Nullable
			private final LocalDate liveDay;
			private final Map<MovementWindow, LocalDate> windowDays;

			/**
			 * The pre-addendum-U arity: the five figures, with no live calendar behind them. Kept so every caller
			 * that knows nothing of the days keeps compiling AND keeps its meaning - no live day, and no day for
			 * any window.
			 *
			 * @param fetchedAtMillis when the {@code /latest} snapshot in use was fetched; 0 when there is none
			 * @param latestItems     how many items that snapshot names; 0 when there is none
			 * @param liveRows        stacks priced from the live traded series
			 * @param guideRows       stacks priced from the Jagex guide table
			 * @param alchRows        untradeable stacks on the High Alchemy rule (Q5)
			 */
			public LiveStatus(final long fetchedAtMillis, final int latestItems, final int liveRows,
				final int guideRows, final int alchRows)
			{
				this(fetchedAtMillis, latestItems, liveRows, guideRows, alchRows, null, null);
			}

			/**
			 * The same, carrying the live CALENDAR these rows were computed on (addendum U, lines U1 and U4).
			 *
			 * @param liveDay    the UTC date of the {@code /latest} snapshot in use - the day every live window
			 *                   counts back from - or null when there is no snapshot
			 * @param windowDays the day each window's traded bucket actually holds ({@code liveDay - N}, or one day
			 *                   further back when that day had not closed, U2); a window with no traded bucket is a
			 *                   null value, and a missing key reads as one, so the map this object exposes always
			 *                   carries every window
			 */
			public LiveStatus(final long fetchedAtMillis, final int latestItems, final int liveRows,
				final int guideRows, final int alchRows, @Nullable final LocalDate liveDay,
				@Nullable final Map<MovementWindow, LocalDate> windowDays)
			{
				this.fetchedAtMillis = fetchedAtMillis;
				this.latestItems = latestItems;
				this.liveRows = liveRows;
				this.guideRows = guideRows;
				this.alchRows = alchRows;
				this.liveDay = liveDay;
				final EnumMap<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
				for (final MovementWindow window : MovementWindow.values())
				{
					days.put(window, windowDays == null ? null : windowDays.get(window));
				}
				this.windowDays = Collections.unmodifiableMap(days);
			}

			/** When the {@code /latest} snapshot behind these rows was fetched, epoch millis; 0 when there is none. */
			public long fetchedAtMillis()
			{
				return fetchedAtMillis;
			}

			/** How many items that snapshot names; 0 when there is none. */
			public int latestItems()
			{
				return latestItems;
			}

			/** Stacks priced at a live traded mid. */
			public int liveRows()
			{
				return liveRows;
			}

			/** Stacks priced at a Jagex guide price - including a parts sum, which is guide prices summed (R3). */
			public int guideRows()
			{
				return guideRows;
			}

			/** Untradeable stacks counted at their High Alchemy value (Q5). */
			public int alchRows()
			{
				return alchRows;
			}

			/**
			 * The UTC calendar date of the {@code /latest} snapshot these rows were priced from (addendum U, line
			 * U1) - "today" for the live series, and the day every live window counts back from. Null when no
			 * snapshot has been fetched.
			 *
			 * <p>It is the date of {@link #fetchedAtMillis()} in UTC and of nothing else: not the wall clock (the
			 * two differ for the hours either side of UTC midnight, and a row must never compare against a day its
			 * own price did not come from) and not the guide's anchor day, whose lateness is exactly what addendum U
			 * was written to undo.
			 */
			@Nullable
			public LocalDate liveDay()
			{
				return liveDay;
			}

			/**
			 * The calendar day each window's traded bucket actually holds (U1, U2) - normally
			 * {@code liveDay - N}, and one day further back for a window whose own day had not closed when it was
			 * asked for. A window with no usable traded bucket maps to NULL, and every window is present, so a
			 * reader can print the map without asking which keys exist.
			 *
			 * <p>These are the days the LIVE rows compared against; the guide rows' days are
			 * {@link Status#thenDay()} and the baselines behind it, untouched by any of this.
			 */
			public Map<MovementWindow, LocalDate> windowDays()
			{
				return windowDays;
			}

			/**
			 * The figures under T8's own key names, in T8's own order, with addendum U's two calendar keys after
			 * them: {@code fetchedAt}, {@code latestItems}, {@code liveRows}, {@code guideRows}, {@code alchRows},
			 * then {@code liveDay} (an ISO date string, or null) and {@code windowDays} (an object keyed by window
			 * label - {@code 1d}, {@code 7d}, ... - of ISO date strings and nulls).
			 *
			 * <p>Values are {@link Long}, {@link String}, a nested {@code LinkedHashMap<String, String>} and null,
			 * which is what lets the panel's {@code describe()} write the object without a Gson it does not have -
			 * the same trick {@link ViewOptions#asMap()} plays with booleans. A writer that drops null values
			 * (Gson, unless {@code serializeNulls} is on) answers the same question a reader asks of it: a missing
			 * key and a null value both mean "that window has no traded bucket".
			 */
			public LinkedHashMap<String, Object> asMap()
			{
				final LinkedHashMap<String, Object> map = new LinkedHashMap<>();
				map.put("fetchedAt", fetchedAtMillis);
				map.put("latestItems", (long) latestItems);
				map.put("liveRows", (long) liveRows);
				map.put("guideRows", (long) guideRows);
				map.put("alchRows", (long) alchRows);
				map.put("liveDay", liveDay == null ? null : liveDay.toString());
				final LinkedHashMap<String, String> days = new LinkedHashMap<>();
				for (final MovementWindow window : MovementWindow.values())
				{
					final LocalDate day = windowDays.get(window);
					days.put(window.label(), day == null ? null : day.toString());
				}
				map.put("windowDays", days);
				return map;
			}

			@Override
			public boolean equals(final Object o)
			{
				if (this == o)
				{
					return true;
				}
				if (!(o instanceof LiveStatus))
				{
					return false;
				}
				final LiveStatus other = (LiveStatus) o;
				return fetchedAtMillis == other.fetchedAtMillis
					&& latestItems == other.latestItems
					&& liveRows == other.liveRows
					&& guideRows == other.guideRows
					&& alchRows == other.alchRows
					&& Objects.equals(liveDay, other.liveDay)
					&& windowDays.equals(other.windowDays);
			}

			@Override
			public int hashCode()
			{
				return Objects.hash(fetchedAtMillis, latestItems, liveRows, guideRows, alchRows, liveDay, windowDays);
			}

			@Override
			public String toString()
			{
				return "LiveStatus" + asMap();
			}
		}

		private final long pricesAtMillis;
		private final long bankAtMillis;
		private final boolean bankLoaded;
		private final boolean loggedIn;
		private final MovementRow.PriceSource source;
		@Nullable
		private final String problem;
		private final ProblemKind problemKind;
		private final int totalRows;
		private final int bankItems;
		private final MovementWindow window;
		private final boolean baselineLoaded;
		private final long baselineRevisionSeconds;
		private final long baselineRevId;
		private final long mappingAtMillis;
		private final long indexAtMillis;
		@Nullable
		private final LocalDate anchorDay;
		private final double agree;
		private final int agreeSamples;
		@Nullable
		private final LocalDate r0Day;
		private final long r0RevId;
		private final boolean degraded;
		private final boolean anchorDegraded;
		@Nullable
		private final String degradedReason;
		private final PortfolioSummary portfolio;
		private final ViewOptions options;
		private final LiveStatus live;

		/**
		 * @param pricesAtMillis          when the guide prices in the rows were read (the last computation that
		 *                                priced at least one item); 0 = no priced row yet
		 * @param bankAtMillis            when the bank snapshot was captured; 0 = none
		 * @param bankLoaded              a bank snapshot exists (an EMPTY bank still counts - "no bank yet" is
		 *                                bankLoaded false)
		 * @param loggedIn                the client is logged in (rows are still shown when it is not, D7)
		 * @param source                  where the unit prices come from - GUIDE when any row is priced, else
		 *                                NONE; null reads as NONE
		 * @param problem                 null, or one short sentence that replaces the header line (K7, L11)
		 * @param problemKind             what kind of problem that sentence is, so a reader never has to compare
		 *                                strings to tell a fault from a fact of life; null reads as
		 *                                {@link ProblemKind#NONE}, and so does any kind offered without a sentence
		 * @param totalRows               rows after the filter
		 * @param bankItems               stacks before the filter
		 * @param window                  the movement window in use; null reads as {@link MovementWindow#DEFAULT}
		 * @param baselineLoaded          the current window's baseline revision is known (its projection may still
		 *                                name none of the bank's items, e.g. before the mapping arrives)
		 * @param baselineRevisionSeconds the current window's baseline DAY MARKER - the table's own
		 *                                {@code %LAST_UPDATE%} in unix seconds (L7), NOT the revision's save time;
		 *                                0 = no baseline. {@link #thenDay()} is its UTC date
		 * @param baselineRevId           the wiki revision id of that baseline; 0 = no baseline
		 * @param mappingAtMillis         when the id-to-wiki-name table was fetched; 0 = none loaded
		 * @param indexAtMillis           when the revision index was fetched; 0 = none loaded (L4)
		 * @param anchorDay               the derived anchor day D (L3), or null when none is derived yet
		 * @param agree                   the last agreement matches / n in [0, 1], or -1 when nothing compared (L3)
		 * @param agreeSamples            the n of that agreement - bank items with both a RuneLite price and an R0 value
		 * @param r0Day                   the newest guide table's own day, or null when R0 is not in memory (L3)
		 * @param r0RevId                 the newest guide table's revision id; 0 when R0 is not in memory
		 * @param degraded                the rows are computed in a fallback mode (see {@link #degraded()})
		 * @param anchorDegraded          the flavour of it the panel words differently: too few comparable items to
		 *                                date the prices, so the newest guide TABLE stands in as "now" (L3)
		 * @param degradedReason          one short sentence saying why, or null when not degraded
		 * @param portfolio               the whole bank's value and per-window moves computed beside the rows (M3);
		 *                                null reads as {@link PortfolioSummary#EMPTY}
		 */
		public Status(final long pricesAtMillis, final long bankAtMillis, final boolean bankLoaded,
			final boolean loggedIn, @Nullable final MovementRow.PriceSource source, @Nullable final String problem,
			@Nullable final ProblemKind problemKind,
			final int totalRows, final int bankItems, @Nullable final MovementWindow window,
			final boolean baselineLoaded, final long baselineRevisionSeconds, final long baselineRevId,
			final long mappingAtMillis, final long indexAtMillis, @Nullable final LocalDate anchorDay,
			final double agree, final int agreeSamples, @Nullable final LocalDate r0Day, final long r0RevId,
			final boolean degraded, final boolean anchorDegraded, @Nullable final String degradedReason,
			@Nullable final PortfolioSummary portfolio)
		{
			this(pricesAtMillis, bankAtMillis, bankLoaded, loggedIn, source, problem, problemKind, totalRows,
				bankItems, window, baselineLoaded, baselineRevisionSeconds, baselineRevId, mappingAtMillis,
				indexAtMillis, anchorDay, agree, agreeSamples, r0Day, r0RevId, degraded, anchorDegraded,
				degradedReason, portfolio, ViewOptions.DEFAULT);
		}

		/**
		 * The same, carrying the view switches the figures were computed under (addendum Q, seam S1). The arity
		 * above is the pre-Q one, kept so every caller that knows nothing of the switches keeps compiling AND keeps
		 * its meaning: it delegates with {@link ViewOptions#DEFAULT}, which is what the pre-Q service computed.
		 *
		 * @param options the view switches; null reads as {@link ViewOptions#DEFAULT}
		 */
		public Status(final long pricesAtMillis, final long bankAtMillis, final boolean bankLoaded,
			final boolean loggedIn, @Nullable final MovementRow.PriceSource source, @Nullable final String problem,
			@Nullable final ProblemKind problemKind,
			final int totalRows, final int bankItems, @Nullable final MovementWindow window,
			final boolean baselineLoaded, final long baselineRevisionSeconds, final long baselineRevId,
			final long mappingAtMillis, final long indexAtMillis, @Nullable final LocalDate anchorDay,
			final double agree, final int agreeSamples, @Nullable final LocalDate r0Day, final long r0RevId,
			final boolean degraded, final boolean anchorDegraded, @Nullable final String degradedReason,
			@Nullable final PortfolioSummary portfolio, @Nullable final ViewOptions options)
		{
			this(pricesAtMillis, bankAtMillis, bankLoaded, loggedIn, source, problem, problemKind, totalRows,
				bankItems, window, baselineLoaded, baselineRevisionSeconds, baselineRevId, mappingAtMillis,
				indexAtMillis, anchorDay, agree, agreeSamples, r0Day, r0RevId, degraded, anchorDegraded,
				degradedReason, portfolio, options, null);
		}

		/**
		 * The same, carrying what the traded feeds delivered (addendum T, line T8; seam S1). The arity above is
		 * the pre-T one, kept so every caller that knows nothing of live prices keeps compiling AND keeps its
		 * meaning: it delegates with {@link LiveStatus#OFF}, which is what a guide-only publish says.
		 *
		 * @param live the traded feeds' state; null reads as {@link LiveStatus#OFF}
		 */
		public Status(final long pricesAtMillis, final long bankAtMillis, final boolean bankLoaded,
			final boolean loggedIn, @Nullable final MovementRow.PriceSource source, @Nullable final String problem,
			@Nullable final ProblemKind problemKind,
			final int totalRows, final int bankItems, @Nullable final MovementWindow window,
			final boolean baselineLoaded, final long baselineRevisionSeconds, final long baselineRevId,
			final long mappingAtMillis, final long indexAtMillis, @Nullable final LocalDate anchorDay,
			final double agree, final int agreeSamples, @Nullable final LocalDate r0Day, final long r0RevId,
			final boolean degraded, final boolean anchorDegraded, @Nullable final String degradedReason,
			@Nullable final PortfolioSummary portfolio, @Nullable final ViewOptions options,
			@Nullable final LiveStatus live)
		{
			this.live = live == null ? LiveStatus.OFF : live;
			this.options = options == null ? ViewOptions.DEFAULT : options;
			this.pricesAtMillis = pricesAtMillis;
			this.bankAtMillis = bankAtMillis;
			this.bankLoaded = bankLoaded;
			this.loggedIn = loggedIn;
			this.source = source == null ? MovementRow.PriceSource.NONE : source;
			this.problem = problem;
			// No sentence, no kind: the two are decided together everywhere they are built, and this is what makes
			// "problem() == null implies problemKind() == NONE" a property of the class rather than a habit.
			this.problemKind = problem == null || problemKind == null ? ProblemKind.NONE : problemKind;
			this.totalRows = totalRows;
			this.bankItems = bankItems;
			this.window = window == null ? MovementWindow.DEFAULT : window;
			this.baselineLoaded = baselineLoaded;
			this.baselineRevisionSeconds = baselineRevisionSeconds;
			this.baselineRevId = baselineRevId;
			this.mappingAtMillis = mappingAtMillis;
			this.indexAtMillis = indexAtMillis;
			this.anchorDay = anchorDay;
			this.agree = agree;
			this.agreeSamples = agreeSamples;
			this.r0Day = r0Day;
			this.r0RevId = r0RevId;
			this.degraded = degraded;
			this.anchorDegraded = degraded && anchorDegraded;
			this.degradedReason = degraded ? degradedReason : null;
			this.portfolio = portfolio == null ? PortfolioSummary.EMPTY : portfolio;
		}

		public long pricesAtMillis()
		{
			return pricesAtMillis;
		}

		public long bankAtMillis()
		{
			return bankAtMillis;
		}

		public boolean bankLoaded()
		{
			return bankLoaded;
		}

		public boolean loggedIn()
		{
			return loggedIn;
		}

		/** Never null. */
		public MovementRow.PriceSource source()
		{
			return source;
		}

		/** Null when nothing is wrong; otherwise the sentence {@link #text()} shows instead of the header. */
		@Nullable
		public String problem()
		{
			return problem;
		}

		/**
		 * What kind of problem {@link #problem()} is; {@link ProblemKind#NONE} exactly when there is no sentence.
		 * Never null. Read {@link ProblemKind#severe()} rather than comparing the sentence against a factory:
		 * that is what used to decide the problem row's colour, and it made every wording change a colour change.
		 */
		public ProblemKind problemKind()
		{
			return problemKind;
		}

		public int totalRows()
		{
			return totalRows;
		}

		public int bankItems()
		{
			return bankItems;
		}

		/** Never null. */
		public MovementWindow window()
		{
			return window;
		}

		public boolean baselineLoaded()
		{
			return baselineLoaded;
		}

		/**
		 * The current window's baseline day marker in unix seconds - the guide table's own {@code %LAST_UPDATE%},
		 * the instant Jagex stamped those prices (L7); 0 when there is none. Since addendum L this is NOT the
		 * revision's save time: a human maintenance edit republishes the previous day's table under the next
		 * day's timestamp (L-E), and the label on a row must name the day the numbers are from. Callers that want
		 * a date should read {@link #thenDay()}; the seconds are kept for the bridge's echo and for the panel's
		 * "did the baseline change" identity test.
		 */
		public long baselineRevisionSeconds()
		{
			return baselineRevisionSeconds;
		}

		/** The wiki revision id of the current window's baseline; 0 when there is none (L13 echoes it). */
		public long baselineRevId()
		{
			return baselineRevId;
		}

		/** When the id-to-wiki-name table in use was fetched, epoch millis; 0 when none is loaded. */
		public long mappingAtMillis()
		{
			return mappingAtMillis;
		}

		/** When the revision index in use was fetched, epoch millis; 0 when none is loaded (L4). */
		public long indexAtMillis()
		{
			return indexAtMillis;
		}

		/**
		 * The derived anchor day D (L3) - the Jagex day the "now" prices belong to - or null when it has not been
		 * derived yet (no R0 in memory, or no bank). UTC.
		 */
		@Nullable
		public LocalDate anchorDay()
		{
			return anchorDay;
		}

		/**
		 * The last agreement between RuneLite's prices and the newest guide table, matches / n in [0, 1], or -1
		 * when no item could be compared (L3). At or above {@value PriceService#AGREE_THRESHOLD} with enough samples
		 * means RuneLite is on R0's day; below it means RuneLite is a day ahead.
		 */
		public double agree()
		{
			return agree;
		}

		/** The n behind {@link #agree()}: bank items with both a RuneLite price and an R0 value. */
		public int agreeSamples()
		{
			return agreeSamples;
		}

		/** The newest guide table's own day (its {@code %LAST_UPDATE%} date, L7), or null when R0 is not in memory. */
		@Nullable
		public LocalDate r0Day()
		{
			return r0Day;
		}

		/** The newest guide table's revision id, or 0 when R0 is not in memory. */
		public long r0RevId()
		{
			return r0RevId;
		}

		/**
		 * The UTC day the current window's baseline prices belong to - what the header prints ("1d vs 07 Sep")
		 * and the tooltip repeats ("1d ago (07 Sep)", L7) - or null when there is no baseline. It is the REAL day
		 * of the table in use, which after L5's walk-back or a stale-body retry that could not reach the target may
		 * differ from {@code window.targetDate(anchorDay)}.
		 */
		@Nullable
		public LocalDate thenDay()
		{
			return baselineRevisionSeconds <= 0L ? null : RevisionRef.dayOf(baselineRevisionSeconds);
		}

		/** The same value as {@link #baselineRevId()} under the L13 name. */
		public long thenRevId()
		{
			return baselineRevId;
		}

		/**
		 * True when the rows are computed in a fallback mode and a reader should trust them less: the anchor day
		 * could not be derived from RuneLite's prices (fewer than {@value PriceService#AGREE_MIN_SAMPLES}
		 * comparable items, L3 - the newest guide table stands in as "now", see {@link #anchorDegraded()});
		 * RuneLite's price table was found a day BEHIND the wiki's and the anchor was stepped back to match
		 * (B002); or the last history fetch failed and the rows run on stored baselines (L11).
		 * {@link #degradedReason()} says which.
		 */
		public boolean degraded()
		{
			return degraded;
		}

		/**
		 * The one flavour of {@link #degraded()} a reader has to be told about differently: fewer than
		 * {@value PriceService#AGREE_MIN_SAMPLES} bank stacks could be compared, so the anchor day could not be
		 * derived and the newest guide TABLE stands in as "now" for every row (L3) - a different price series from
		 * the one RuneLite's own tooltip shows, on a small or fresh account. Always false when
		 * {@link #degraded()} is false.
		 *
		 * <p>Here rather than sniffed out of {@link #degradedReason()}'s wording: that sentence is one line of
		 * dev-facing prose the bridge echoes, and a panel deciding what to paint must not depend on its text.
		 */
		public boolean anchorDegraded()
		{
			return anchorDegraded;
		}

		/** Why {@link #degraded()} is true, one short sentence; null when it is false. */
		@Nullable
		public String degradedReason()
		{
			return degradedReason;
		}

		/**
		 * The bank value line (M3): the whole bank's worth at the guide prices the rows were computed from - the
		 * priced stacks plus the bank's coins and platinum tokens ({@link PortfolioSummary#currencyGp()}, P1) - and
		 * one move per window with a baseline in memory, computed by the SAME computation as the rows, from the whole
		 * snapshot rather than the filtered rows. {@link PortfolioSummary#EMPTY} until the first computation; never
		 * null.
		 */
		/**
		 * The five view switches these figures were computed under (Q3, T1, Y1): the cash in or out of the bank
		 * value, the untradeable stacks listed or not, the rows printing holdings or unit prices, the traded
		 * series or the guide table, and what the player carries counted or left out. Never null - a status
		 * built by the pre-Q arity carries {@link ViewOptions#DEFAULT}, which is what it meant.
		 *
		 * <p>Not "the switches as they stand": {@link PriceService#options()} is that question. This one says what
		 * the rows beside it were built with, so a panel can never print a figure under the wrong caption while a
		 * recompute is in flight.
		 */
		public ViewOptions options()
		{
			return options;
		}

		public PortfolioSummary portfolio()
		{
			return portfolio;
		}

		/**
		 * What the TRADED feeds delivered for this publish (addendum T, line T8; addendum U, line U4) - the
		 * {@code /latest} stamp and item count, the live / guide / alch split of the bank's stacks, the live day
		 * every live window counted back from and the day each window's bucket holds. Never null;
		 * {@link LiveStatus#OFF} whenever the {@code livePrices} switch is off.
		 */
		public LiveStatus live()
		{
			return live;
		}

		/**
		 * The status line (C21, K7): the {@link #problem()} sentence when there is one - the live acceptance list
		 * reads "Wiki history down - no movement" and the cooldown text off this line, and at 213 px there
		 * is no room for both - otherwise {@link #headerText()}.
		 */
		public String text()
		{
			return problem != null ? problem : headerText();
		}

		/**
		 * The header line regardless of any problem (L7): {@code "Guide prices - Bank as of HH:MM"}, and with a
		 * baseline {@code "Guide prices - 1d vs 07 Sep - Bank as of HH:MM"} (the guide DAY, UTC);
		 * {@code "No bank yet"} while no snapshot exists and {@code "(logged out)"} appended while the client is
		 * out. Meant for the tooltip when {@link #text()} shows a problem.
		 */
		public String headerText()
		{
			final StringBuilder sb = new StringBuilder(HEADER_PREFIX);
			final String baseline = baselineText();
			if (baseline != null)
			{
				sb.append(" - ").append(baseline);
			}
			sb.append(" - ");
			if (!bankLoaded)
			{
				sb.append("No bank yet");
			}
			else
			{
				sb.append("Bank as of ").append(MovementMath.formatTime(bankAtMillis));
				if (!loggedIn)
				{
					sb.append(" (logged out)");
				}
			}
			return sb.toString();
		}

		/**
		 * The baseline part of the header on its own - {@code "1d vs 07 Sep"} (L7) - or null when the window has
		 * no baseline. The tooltip repeats it.
		 */
		@Nullable
		public String baselineText()
		{
			final LocalDate day = thenDay();
			if (day == null)
			{
				return null;
			}
			return window.label() + " vs " + dayLabel(day);
		}

		/**
		 * A UTC calendar day as the header and tooltip print it - {@code "07 Sep"} (L7) - or
		 * {@link MovementMath#DASH} for null. Delegates to {@link MovementMath#formatDay}, the ONE spelling of the
		 * day format: the row tooltip prints through that method, and a second formatter here is how a header and a
		 * tooltip drift apart (folded by the checker, 2026-09-09).
		 */
		public static String dayLabel(@Nullable final LocalDate day)
		{
			return MovementMath.formatDay(day);
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Status))
			{
				return false;
			}
			final Status other = (Status) o;
			return pricesAtMillis == other.pricesAtMillis
				&& bankAtMillis == other.bankAtMillis
				&& bankLoaded == other.bankLoaded
				&& loggedIn == other.loggedIn
				&& source == other.source
				&& Objects.equals(problem, other.problem)
				&& totalRows == other.totalRows
				&& bankItems == other.bankItems
				&& window == other.window
				&& baselineLoaded == other.baselineLoaded
				&& baselineRevisionSeconds == other.baselineRevisionSeconds
				&& baselineRevId == other.baselineRevId
				&& mappingAtMillis == other.mappingAtMillis
				&& indexAtMillis == other.indexAtMillis
				&& Objects.equals(anchorDay, other.anchorDay)
				&& Double.compare(agree, other.agree) == 0
				&& agreeSamples == other.agreeSamples
				&& Objects.equals(r0Day, other.r0Day)
				&& r0RevId == other.r0RevId
				&& degraded == other.degraded
				&& anchorDegraded == other.anchorDegraded
				&& Objects.equals(degradedReason, other.degradedReason)
				&& portfolio.equals(other.portfolio)
				&& options.equals(other.options)
				&& live.equals(other.live);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(pricesAtMillis, bankAtMillis, bankLoaded, loggedIn, source, problem, totalRows,
				bankItems, window, baselineLoaded, baselineRevisionSeconds, baselineRevId, mappingAtMillis,
				indexAtMillis, anchorDay, agree, agreeSamples, r0Day, r0RevId, degraded, anchorDegraded,
				degradedReason, portfolio, options, live);
		}

		@Override
		public String toString()
		{
			return "Status{pricesAt=" + pricesAtMillis + ", bankAt=" + bankAtMillis + ", bankLoaded=" + bankLoaded
				+ ", loggedIn=" + loggedIn + ", source=" + source + ", problem=" + problem + ", totalRows=" + totalRows
				+ ", bankItems=" + bankItems + ", window=" + window.name() + ", baselineLoaded=" + baselineLoaded
				+ ", thenDay=" + thenDay() + ", baselineRevId=" + baselineRevId + ", mappingAt=" + mappingAtMillis
				+ ", indexAt=" + indexAtMillis + ", anchorDay=" + anchorDay + ", agree=" + agree
				+ ", agreeSamples=" + agreeSamples + ", r0Day=" + r0Day + ", r0RevId=" + r0RevId
				+ ", degraded=" + degraded + ", bankValue=" + portfolio.valueNow()
				+ ", options=" + options
				+ (LiveStatus.OFF.equals(live) ? "" : ", live=" + live) + '}';
		}
	}

	/**
	 * What {@link #resolveLocked} decides for one window (L5): the revision whose table to adopt now (possibly
	 * a provisional, stale one), the revision whose body must still be fetched, or one of the two terminal
	 * answers - no history that far back (L11) or every candidate exhausted.
	 */
	private static final class Resolution
	{
		static final Resolution NONE = new Resolution(null, null, false, false);
		static final Resolution NO_HISTORY = new Resolution(null, null, true, false);
		static final Resolution EXHAUSTED = new Resolution(null, null, false, true);

		@Nullable
		final RevisionRef accept;
		@Nullable
		final RevisionRef fetch;
		final boolean noHistory;
		final boolean exhausted;

		private Resolution(@Nullable final RevisionRef accept, @Nullable final RevisionRef fetch,
			final boolean noHistory, final boolean exhausted)
		{
			this.accept = accept;
			this.fetch = fetch;
			this.noHistory = noHistory;
			this.exhausted = exhausted;
		}

		static Resolution accept(final RevisionRef ref)
		{
			return new Resolution(ref, null, false, false);
		}

		static Resolution fetch(final RevisionRef ref)
		{
			return new Resolution(null, ref, false, false);
		}

		/** Adopt {@code accept} for now (a stale body is still a real past price) and fetch the retry candidate. */
		static Resolution acceptAndFetch(final RevisionRef accept, final RevisionRef fetch)
		{
			return new Resolution(accept, fetch, false, false);
		}
	}

	/**
	 * The stacks one computation is over, and where each one's quantity is (addendum Y, line Y3) - what
	 * {@link #stacksOf(BankSnapshot, ViewOptions)} answers, so the merge is done once per computation and the rows,
	 * the counts and the bank value cannot be built over three different lists.
	 */
	private static final class Stacks
	{
		/** The stacks, in {@link BankReader#BY_NAME_THEN_ID} order; never null, possibly empty. */
		final List<BankItem> items;

		/**
		 * Canonical id to {bank, inventory, worn} - the three figures a row's hover names. EMPTY while "Include
		 * inventory and worn gear" is off (and while nothing is carried), which leaves every row's split at three
		 * zeros and so byte-identical to the pre-Y one.
		 */
		final Map<Integer, int[]> splits;

		Stacks(final List<BankItem> items, final Map<Integer, int[]> splits)
		{
			this.items = items;
			this.splits = splits;
		}
	}

	/**
	 * One computation's inputs, snapshotted under the lock so the client-thread hop and the finish read one state.
	 * Since addendum M the snapshot carries EVERY window's baseline and the table behind it (M3: the portfolio's
	 * moves are computed for every baseline in memory, through the same name ladder as the rows), not only the
	 * current window's; {@link #baseline()} and {@link #table()} are the current window's pair.
	 */
	private static final class Inputs
	{
		/**
		 * The stacks this computation is over: every one of them, or the GE-tradeable ones alone when "Count
		 * untradeable items" is off (Q5) - the filter is applied once, here, so everything downstream simply works
		 * on "the bank" and the switch cannot be half-applied.
		 */
		final List<BankItem> items;
		/**
		 * Where each stack's quantity is (Y3), by canonical id: {bank, inventory, worn}. Empty while "Include
		 * inventory and worn gear" is off, which is what leaves every row's split at three zeros.
		 */
		final Map<Integer, int[]> splits;
		/**
		 * The coins and platinum tokens in gp at the moment the items were snapshotted
		 * ({@link BankSnapshot#currencyGp}, P1, plus {@link BankSnapshot#carriedGp} under Y3's switch) - taken here
		 * rather than read from {@code bank} later so the total and the stacks it is added to can never come from
		 * two different banks. 0 while "Include coins and platinum tokens" is off (Q4): the snapshot on disk still
		 * records the real figure, so the switch costs no bank visit.
		 */
		final long currencyGp;
		final RowFilter filter;
		/** The three view switches this computation was taken under (Q); never null. */
		final ViewOptions options;
		/** One entry per window, {@link PriceMap#EMPTY} where there is no baseline; never null. */
		final Map<MovementWindow, PriceMap> baselines;
		/** The table behind each baseline, {@link GuideSnapshot#EMPTY} when its bytes are not in memory; never null. */
		final Map<MovementWindow, GuideSnapshot> tables;
		/**
		 * Every guide table in memory, R0 and the spares {@link #evictLocked()} keeps alike - the pool
		 * {@link #tableForDay} searches when {@link #agrees} asks whether RuneLite's table matches the day BEFORE
		 * R0's (B002). A list rather than a day-keyed map because the question is asked on one branch of one
		 * recompute in a while, and building the map cost a {@code dataDay()} per table on every recompute that
		 * never asked. Never null; a handful of entries, all immutable, held by reference.
		 */
		final List<GuideSnapshot> inMemory;
		final GuideSnapshot r0;
		final PriceMap r0Map;
		final Map<Integer, String> mapping;
		/** {@link PriceService#foldedNames} - id to the guide-table key its wiki name folds to. Never null. */
		final Map<Integer, String> foldedNames;
		final Map<String, Integer> owners;
		/**
		 * The traded {@code /latest} quotes this computation may use (T2), or EMPTY - which is what the switch
		 * being off, a feed never fetched and T7's six-hour staleness all look like from here. One test, taken
		 * once under the lock, so nothing downstream has to know which of the three it was.
		 */
		final Map<Integer, TradedPriceClient.Quote> quotes;
		/**
		 * The traded daily bucket per window, ALREADY matched against the LIVE calendar (addendum U, line U1): an
		 * entry exists only when the bucket's own day is {@code liveDay - N} for that window, or the one day
		 * further back U2 allows when that day had not closed. Never null; a window with no entry is a window that
		 * falls back to the guide on both sides.
		 *
		 * <p>Before addendum U the test here was against the window's GUIDE BASELINE day, which is the defect U
		 * was written to fix: the guide's anchor lags Jagex's publication by up to a day, so "1d" could reach two
		 * days back and print a two-day move as one day's (+30 % on a Partyhat set, live look 5).
		 */
		final Map<MovementWindow, PriceStore.TradedDay> tradedDays;
		/**
		 * The UTC date of the {@code /latest} snapshot in {@link #quotes} (U1) - the live series' "today", and the
		 * day every live window counts back from. Null when there is no snapshot, which is also when
		 * {@link #tradedDays} is empty: no live now, no live then.
		 */
		@Nullable
		final LocalDate liveDay;
		/** The {@code livePrices} switch, and a traded client to honour it with. */
		final boolean liveOn;
		/** A {@code /latest} fetch has failed and not recovered - half of T7's "live prices unavailable" test. */
		final boolean liveFailed;
		/** The clock this computation was taken at, unix seconds - T3 check 2's freshness half. */
		final long nowSeconds;

		Inputs(final Stacks stacks, final long currencyGp, final RowFilter filter, final ViewOptions options,
			final Map<MovementWindow, PriceMap> baselines, final Map<MovementWindow, GuideSnapshot> tables,
			final List<GuideSnapshot> inMemory, final GuideSnapshot r0, final PriceMap r0Map,
			final Map<Integer, String> mapping, final Map<Integer, String> foldedNames,
			final Map<String, Integer> owners, final Map<Integer, TradedPriceClient.Quote> quotes,
			final Map<MovementWindow, PriceStore.TradedDay> tradedDays, @Nullable final LocalDate liveDay,
			final boolean liveOn, final boolean liveFailed, final long nowSeconds)
		{
			this.items = stacks.items;
			this.splits = stacks.splits;
			this.currencyGp = currencyGp;
			this.filter = filter;
			this.options = options;
			this.baselines = baselines;
			this.tables = tables;
			this.inMemory = inMemory;
			this.r0 = r0;
			this.r0Map = r0Map;
			this.mapping = mapping;
			this.foldedNames = foldedNames;
			this.owners = owners;
			this.quotes = quotes;
			this.tradedDays = tradedDays;
			this.liveDay = liveDay;
			this.liveOn = liveOn;
			this.liveFailed = liveFailed;
			this.nowSeconds = nowSeconds;
		}

		/** Whether the traded series may be consulted at all: the switch on and a usable {@code /latest} in hand. */
		boolean liveUsable()
		{
			return liveOn && !quotes.isEmpty();
		}

		/**
		 * One finished row with its split put on it (Y3) - the LAST thing done to a row, after every {@code as*}
		 * copy, so nothing can drop it. The row itself when there is no split, which is every row while "Include
		 * inventory and worn gear" is off.
		 */
		MovementRow withSplit(final MovementRow row)
		{
			final int[] split = splits.get(row.id());
			return split == null ? row : row.withSplit(split[0], split[1], split[2]);
		}

		/**
		 * Yesterday's bucket for one item - what checks 1, 4 and 5 are all made against - or NULL when the D1
		 * bucket is not in hand at all, which is a refusal of {@link #LIVE_NO_DATA} rather than a measurement. An
		 * item the feed simply does not name really did trade 0 units that day, so it answers {@link #NOT_TRADED}
		 * and is refused by check 1 with "0 traded yesterday".
		 *
		 * <p>Always the 1d window's bucket, whichever window the sidebar is showing: "traded yesterday" is a fact
		 * about the item, not about the span being looked at. Since addendum U that bucket is yesterday by the LIVE
		 * calendar - {@code liveDay - 1}, or the day before it when the wiki had not closed yesterday yet (U2) - and
		 * owes nothing to the guide history, which may be a day behind it.
		 */
		@Nullable
		TradedPriceClient.Bucket bucketYesterday(final int id)
		{
			final PriceStore.TradedDay yesterday = tradedDays.get(MovementWindow.D1);
			if (yesterday == null || yesterday.isEmpty())
			{
				return null;
			}
			final TradedPriceClient.Bucket bucket = yesterday.get(id);
			return bucket == null ? NOT_TRADED : bucket;
		}

		/**
		 * How many units of one item changed hands yesterday - the figure a live row's tooltip prints - read off
		 * {@link #bucketYesterday}, so the tooltip and check 1 can never be looking at two different days.
		 */
		@Nullable
		Long volumeYesterday(final int id)
		{
			final TradedPriceClient.Bucket bucket = bucketYesterday(id);
			return bucket == null ? null : Long.valueOf(bucket.volume());
		}

		/**
		 * One window's traded "then" for an item: the bucket's volume-weighted average, but ONLY when that bucket
		 * is one worth comparing against - {@link #bucketUsable}, which is T4's "names a price and clears
		 * {@value #LIVE_MIN_VOLUME}" plus addendum V line V3's gap rule. Null sends that window back to the guide's
		 * own two ends.
		 *
		 * <p>V3 reads the predicate here as well as at check 4 deliberately: a stack can be perfectly liquid today
		 * and yet have had a scattered day three months ago, and pricing its 90d move off an "average" of two
		 * prices a hundred percent apart would be the same defect one window further out.
		 */
		@Nullable
		Long tradedThen(final MovementWindow window, final int id)
		{
			final PriceStore.TradedDay day = tradedDays.get(window);
			final TradedPriceClient.Bucket bucket = day == null ? null : day.get(id);
			return bucketUsable(bucket) ? bucket.weightedAverage() : null;
		}

		/**
		 * The calendar day one window's traded bucket RECORDS (U1, U2) - what a live row for that window was
		 * compared against and what its tooltip prints - or null when that window has no usable bucket. The day
		 * the bucket names, never the day it was wanted for: after U2's one-day fallback those differ, and the one
		 * a reader must be shown is the day the numbers are really from.
		 */
		@Nullable
		LocalDate tradedDayOf(final MovementWindow window)
		{
			final PriceStore.TradedDay day = tradedDays.get(window);
			return day == null ? null : day.day();
		}

		/** The guide baseline day of one window (L7) - what a window that fell back to the guide compared against. */
		@Nullable
		LocalDate guideDayOf(final MovementWindow window)
		{
			final PriceMap baseline = baselines.get(window);
			return baseline == null ? null : baseline.dataDay();
		}

		/**
		 * The table in memory whose own day marker is {@code day}, or null when none is - the LAST such table, as
		 * the day-keyed map this replaced kept the last writer of a key. Null day, no table.
		 */
		@Nullable
		GuideSnapshot tableForDay(@Nullable final LocalDate day)
		{
			if (day == null)
			{
				return null;
			}
			for (int i = inMemory.size() - 1; i >= 0; i--)
			{
				final GuideSnapshot table = inMemory.get(i);
				if (table != null && !table.isEmpty() && day.equals(table.dataDay()))
				{
					return table;
				}
			}
			return null;
		}

		/** The current window's baseline - what the rows read. */
		PriceMap baseline()
		{
			final PriceMap map = baselines.get(filter.window());
			return map == null ? PriceMap.EMPTY : map;
		}

		/** The table behind the current window's baseline, EMPTY when not in memory. */
		GuideSnapshot table()
		{
			final GuideSnapshot table = tables.get(filter.window());
			return table == null ? GuideSnapshot.EMPTY : table;
		}

		/** Whether any guide table at all is in memory - R0 or one behind any window's baseline (the L8 b name gate). */
		boolean anyTable()
		{
			if (!r0.isEmpty())
			{
				return true;
			}
			for (final GuideSnapshot table : tables.values())
			{
				if (table != null && !table.isEmpty())
				{
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The tradeable PARTS of the untradeable stacks one computation covers (addendum R, line R2), gathered once:
	 * the distinct part ids, the name the bank recorded for each, whether RuneLite's price for one is another
	 * item's (the B001 carve-out, which applies to a part exactly as it does to a bank stack), and the guide price
	 * read for each on the client thread.
	 *
	 * <p>Gathered as a SET of ids rather than per stack because two stacks can revert to the same part - a crystal
	 * helm, body and legs are all crystal armour seeds - and the guide table should be asked once.
	 *
	 * <p>{@link #rewritten} and {@link #guide} are filled after construction, on the computing thread and then on
	 * the client thread; the executor hand-off between the two is what publishes the writes, exactly as it does for
	 * the bank stacks' own {@code guide} array.
	 */
	private static final class Parts
	{
		static final Parts EMPTY = new Parts(new int[0], Collections.emptyMap());

		/** The distinct part ids, ascending. */
		final int[] ids;
		/** Part id to the name {@code BankReader} recorded for it (L8 b); "" when the composition had none. */
		final Map<Integer, String> names;
		/** Parallel to {@link #ids}: RuneLite would answer another item's price for this one (B001). */
		final boolean[] rewritten;
		/** Parallel to {@link #ids}: RuneLite's guide price; 0 means none, or never asked for a rewritten id. */
		final int[] guide;

		private Parts(final int[] ids, final Map<Integer, String> names)
		{
			this.ids = ids;
			this.names = names;
			this.rewritten = new boolean[ids.length];
			this.guide = new int[ids.length];
		}

		/**
		 * Every part named by an untradeable stack in this computation's bank, or {@link #EMPTY} when there is
		 * none - which is every bank before addendum R, and every bank at all while "Include untradeable items" is
		 * off (the switch has already removed those stacks from {@code items}).
		 */
		static Parts of(final List<BankItem> items)
		{
			Map<Integer, String> names = null;
			for (final BankItem item : items)
			{
				if (item == null || !item.untradeable || !item.hasParts())
				{
					continue;
				}
				for (final BankItem.Part part : item.parts)
				{
					if (part == null || part.id <= 0)
					{
						continue;
					}
					if (names == null)
					{
						// Sorted, so the ids leave in a fixed order however the bank was walked.
						names = new TreeMap<>();
					}
					names.putIfAbsent(part.id, part.name == null ? "" : part.name);
				}
			}
			if (names == null)
			{
				return EMPTY;
			}
			final int[] ids = new int[names.size()];
			int at = 0;
			for (final Integer id : names.keySet())
			{
				ids[at++] = id;
			}
			return new Parts(ids, Collections.unmodifiableMap(names));
		}

		boolean isEmpty()
		{
			return ids.length == 0;
		}
	}

	/**
	 * How far RuneLite's prices agree with ONE guide table (L3), and that table's value for each bank item on the
	 * way. Built by {@link #tally}; {@link #agreement} asks it of R0 and {@link #agrees} of a spare table.
	 */
	private static final class Agreement
	{
		/** The table's value per bank item, in the bank's own order; null where the table cannot name the item. */
		final Long[] values;
		/** Items with BOTH a RuneLite price and a value here - L3's n. */
		final int samples;
		/** How many of those two numbers were equal. */
		final int matches;

		Agreement(final Long[] values, final int samples, final int matches)
		{
			this.values = values;
			this.samples = samples;
			this.matches = matches;
		}

		/**
		 * L3's threshold: at least {@value #AGREE_MIN_SAMPLES} comparable items and {@value #AGREE_THRESHOLD} of
		 * them equal. Integer arithmetic so 18 of 20 is exactly the threshold, as in {@link #deriveAnchorDay} -
		 * which needs the rule in a different shape (too few samples reads as "the same day" there, not as
		 * disagreement) and so spells it itself.
		 */
		boolean clears()
		{
			return samples >= AGREE_MIN_SAMPLES && (long) matches * 10L >= (long) samples * 9L;
		}
	}

	/**
	 * One computation's output before it is published: the rows AFTER the filter, the bank value line beside them,
	 * and how many stacks were priced (the source is GUIDE iff that is above 0).
	 */
	private static final class Computed
	{
		final List<MovementRow> rows;
		final PortfolioSummary summary;
		final int priced;
		/** Stacks priced at a live traded mid (T5/T8); 0 with the switch off. */
		final int live;
		/** Untradeable stacks counted at their High Alchemy value (Q5). */
		final int alch;

		Computed(final List<MovementRow> rows, final PortfolioSummary summary, final int priced, final int live,
			final int alch)
		{
			this.rows = rows;
			this.summary = summary;
			this.priced = priced;
			this.live = live;
			this.alch = alch;
		}

		/** Stacks priced from the Jagex guide table - a parts sum is guide prices summed (R3), so it counts here. */
		int guide()
		{
			return priced - live;
		}
	}

	// ---------------------------------------------------------------- problem wording (K7, L11), spelled once

	/** K7: the history fetch failed and the window has no stored baseline; the rows show prices and no move. */
	public static String problemHistoryUnavailable()
	{
		return PROBLEM_HISTORY_UNAVAILABLE;
	}

	/** K7: a history request is in flight and the window has nothing older to show meanwhile. */
	public static String problemHistoryPending(@Nullable final MovementWindow window)
	{
		return "No " + (window == null ? MovementWindow.DEFAULT : window).label() + " history yet";
	}

	/** L11: the window's target date predates the whole revision index - not an error, nothing to fetch. */
	public static String problemNoHistory(@Nullable final MovementWindow window)
	{
		return "No " + (window == null ? MovementWindow.DEFAULT : window).label() + " history";
	}

	/** K5/K7/L10: a manual refresh inside {@link #MANUAL_COOLDOWN_MS}. */
	public static String problemCooldown(final long secondsAgo)
	{
		return "Refreshed " + Math.max(0L, secondsAgo) + " s ago - wait";
	}

	// ---------------------------------------------------------------- U1/U2: the live calendar, in ONE place

	/**
	 * The UTC calendar date of an epoch-millis stamp (addendum U, line U1), or null when there is no stamp.
	 *
	 * <p><b>UTC, explicitly, and never the default zone.</b> The live day is the date the wiki's own daily buckets
	 * are cut on, and those are UTC days; a client in Sydney reading its own zone would count back from tomorrow
	 * for ten hours of every day, and one in Los Angeles from yesterday for seven. The rule is the same one
	 * {@link RevisionRef#dayOf} applies to the guide series - one calendar for both, so the two can be compared.
	 *
	 * @param millis epoch millis; 0 or negative answers null - "no snapshot", not 1970
	 */
	@Nullable
	static LocalDate utcDay(final long millis)
	{
		return millis <= 0L ? null : Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC).toLocalDate();
	}

	/**
	 * The calendar day one window's traded bucket should hold (U1): {@code liveDay - N}, counted back from the
	 * LIVE snapshot's own UTC date and from nothing else.
	 *
	 * <p>The guide's anchor day plays no part. Tying the two together is precisely the defect addendum U undoes:
	 * the anchor is the day Jagex has PUBLISHED a guide table for, which lags the calendar by up to a day, so
	 * "1d" reached two days back for as long as the day's table was unpublished and printed a two-day move as one
	 * day's (+30 % on a Partyhat set, live look 5, 2026-09-12).
	 *
	 * @param liveDay the live snapshot's UTC date; null (no snapshot) answers null - nothing to count back from
	 * @param window  the window; null answers null
	 */
	@Nullable
	static LocalDate wantedTradedDay(@Nullable final LocalDate liveDay, @Nullable final MovementWindow window)
	{
		return liveDay == null || window == null ? null : liveDay.minusDays(window.days());
	}

	/**
	 * Whether a bucket that RECORDS {@code held} may serve a window that WANTS {@code wanted} (U1, U2): only the
	 * wanted day itself, or the one day before it - the bucket U2's fallback fetches when the wiki has not closed
	 * the wanted day yet (possible in the first hours after UTC midnight).
	 *
	 * <p>The one day of slack covers the two states that produce it: U2's fallback, and the minutes after UTC
	 * midnight when every window's wanted day has just stepped on and the new buckets are still in flight. In both
	 * the row, the tooltip, the footnote and {@code state.live.windowDays} print the day the bucket RECORDS, so a
	 * reader is never told a day the figures did not come from - which is the half of addendum U that matters.
	 *
	 * <p>Anything further back is refused, and the window falls back to the guide's own two ends until the right
	 * bucket lands. That is what makes "a stored bucket is never used for a day other than the one it records" (U2)
	 * a property rather than a hope: a file left over from an earlier session cannot quietly become this window's
	 * "then", which is exactly how a 1d row came to span two days before addendum U.
	 */
	static boolean tradedDayUsable(@Nullable final LocalDate wanted, @Nullable final LocalDate held)
	{
		return wanted != null && held != null && (held.equals(wanted) || held.equals(wanted.minusDays(1)));
	}

	// ---------------------------------------------------------------- T3: the five checks, in ONE place

	/**
	 * Whether a traded daily bucket is a price worth comparing against (addendum V, line V3). The ONE predicate,
	 * read in two places so a bucket cannot be good enough for one of them and not the other: check 4 reads it on
	 * YESTERDAY's bucket to decide whether a stack goes live at all, and {@link Inputs#tradedThen} reads it on
	 * EVERY window's bucket to decide whether that window compares traded figures or falls back to the guide pair.
	 *
	 * <p>Usable means three things: the bucket names a price at all, its two volumes add to at least
	 * {@value #LIVE_MIN_VOLUME} (T3 check 1's own line, applied per day), and - when it names BOTH averages - they
	 * sit no further apart than {@value #LIVE_MAX_SPREAD_PCT} % of their middle, by exactly the integer arithmetic
	 * and the half-up rounding check 2 measures the live quote's gap with.
	 *
	 * <p>A ONE-SIDED bucket is usable: it has no gap to measure, and T4 already reads its single side as the day's
	 * price. The gap rule exists because the live look of 2026-09-12 found the top of "Biggest gainers" held by
	 * items whose own day was two prices, not one - Tinderbox averaged 37 over 5,063 bought against 12 over 484
	 * sold, a hundred percent apart, and the "average" of that is not a number anybody paid. Sixty-seven stacks of
	 * that bank carried such a bucket.
	 *
	 * @param bucket the day's bucket, or null when the feed does not name the item that day
	 */
	static boolean bucketUsable(@Nullable final TradedPriceClient.Bucket bucket)
	{
		if (bucket == null)
		{
			return false;
		}
		final Long average = bucket.weightedAverage();
		if (average == null || average <= 0L)
		{
			return false;
		}
		if (bucket.volume() < LIVE_MIN_VOLUME)
		{
			return false;
		}
		final Long gap = bucket.gap();
		if (gap == null)
		{
			// One side only: no gap to measure, and the day's price is that side.
			return true;
		}
		final Long mid = bucket.bucketMid();
		if (mid == null || mid <= 0L || mid > LIVE_SANE_PRICE_CEILING)
		{
			// Unmeasurable rather than wide - refused for LIVE_SANE_PRICE_CEILING's arithmetic reason.
			return false;
		}
		return gap * 100L <= mid * LIVE_MAX_SPREAD_PCT;
	}

	/**
	 * Whether one stack may be priced from the LIVE traded series, and why not when it may not (addendum T, line
	 * T3, extended by addendum V lines V3 and V4). The ONE place the checks live: the rows, the bank value and the
	 * tooltip's refusal line all read this answer, so no two of them can disagree about what "actively traded"
	 * means.
	 *
	 * <p>Null means LIVE. Anything else is the FIRST failing check's short phrase, in this order - the order
	 * matters, because a reader gets one line and it should name the reason that would be hardest to fix:
	 * <ol>
	 * <li>volume: yesterday's bulk bucket traded at least {@value #LIVE_MIN_VOLUME} units, both sides added;</li>
	 * <li>gap now: {@code /latest} carries BOTH sides, each traded inside the last
	 * {@value #LIVE_QUOTE_MAX_AGE_SECONDS} seconds, and their gap is at most {@value #LIVE_MAX_SPREAD_PCT} % of
	 * the mid;</li>
	 * <li>guide sanity: the mid sits within {@value #LIVE_MAX_GUIDE_DRIFT_PCT} % of the guide price, either
	 * way;</li>
	 * <li>gap yesterday (V3): yesterday's bucket is {@link #bucketUsable} - its own two averages no further apart
	 * than {@value #LIVE_MAX_SPREAD_PCT} % of their middle;</li>
	 * <li>jump (V4): the mid sits within {@value #LIVE_MAX_DAY_JUMP_PCT} % of yesterday's traded average.</li>
	 * </ol>
	 *
	 * <p>A feed that is not in hand at all answers {@link #LIVE_NO_DATA} rather than a number that would read as
	 * a measurement: no quote for the item, no bucket FEED for yesterday (as opposed to a bucket that names no
	 * trades in it, which really is "0 traded yesterday"), and no guide price to sanity-check against - the last
	 * of which is why an item RuneLite cannot price never goes live, however busily it trades.
	 *
	 * <p>Pure, static and taking every input explicitly, so every edge of every constant is pinned by a test on
	 * both sides of it without a client, a clock or a network.
	 *
	 * @param quote      the item's {@code /latest} pair, or null when the feed had none for it
	 * @param yesterday  yesterday's bucket - checks 1, 4 and 5 all read it - or NULL when that day's feed is not
	 *                   in hand at all. A bucket the feed does not NAME is a bucket of 0 traded, which the caller
	 *                   passes as an empty bucket rather than as this null
	 * @param guideNow   the row's guide price now - the anchor check 3 measures against; null is a refusal
	 * @param nowSeconds the caller's clock in unix seconds, for check 2's freshness half
	 */
	@Nullable
	static String liveRefusal(@Nullable final TradedPriceClient.Quote quote,
		@Nullable final TradedPriceClient.Bucket yesterday, @Nullable final Long guideNow, final long nowSeconds)
	{
		if (yesterday == null || quote == null)
		{
			return LIVE_NO_DATA;
		}
		final long volumeYesterday = yesterday.volume();
		if (volumeYesterday < LIVE_MIN_VOLUME)
		{
			return volumeYesterday + " traded yesterday";
		}
		if (!quote.hasBothSides() || !quote.tradedWithin(nowSeconds, LIVE_QUOTE_MAX_AGE_SECONDS))
		{
			return LIVE_NO_DATA;
		}
		final Long mid = quote.mid();
		final Long spread = quote.spread();
		if (mid == null || spread == null || mid <= 0L || mid > LIVE_SANE_PRICE_CEILING)
		{
			return LIVE_NO_DATA;
		}
		if (spread * 100L > mid * LIVE_MAX_SPREAD_PCT)
		{
			return "buy/sell gap " + percentOf(spread, mid) + " %";
		}
		if (guideNow == null || guideNow <= 0L || guideNow > LIVE_SANE_PRICE_CEILING)
		{
			return LIVE_NO_DATA;
		}
		if (Math.abs(mid - guideNow) * 100L > guideNow * LIVE_MAX_GUIDE_DRIFT_PCT)
		{
			return "live price " + percentOf(Math.abs(mid - guideNow), guideNow) + " % from guide";
		}
		// Check 4 (V3): the volume half is already past, so the only measurable way to fail here is the GAP.
		if (!bucketUsable(yesterday))
		{
			final Long bucketGap = yesterday.gap();
			final Long bucketMid = yesterday.bucketMid();
			if (bucketGap == null || bucketMid == null || bucketMid <= 0L
				|| bucketMid > LIVE_SANE_PRICE_CEILING)
			{
				// A bucket that names no price, or one the arithmetic refuses: nothing measured, nothing to say.
				// The ceiling clause is the same one the other four checks carry (checker): {@link #percentOf}
				// multiplies the gap by 100, and a day past the ceiling would print a WRAPPED percentage.
				return LIVE_NO_DATA;
			}
			return "buy/sell gap " + percentOf(bucketGap, bucketMid) + " % yesterday";
		}
		// Check 5 (V4): against yesterday's own average, which bucketUsable has just vouched for.
		final Long thenYesterday = yesterday.weightedAverage();
		if (thenYesterday == null || thenYesterday <= 0L || thenYesterday > LIVE_SANE_PRICE_CEILING)
		{
			return LIVE_NO_DATA;
		}
		if (Math.abs(mid - thenYesterday) * 100L > thenYesterday * LIVE_MAX_DAY_JUMP_PCT)
		{
			return "live price " + percentOf(Math.abs(mid - thenYesterday), thenYesterday)
				+ " % from yesterday's average";
		}
		return null;
	}

	/**
	 * {@code part} as a whole percentage of {@code whole}, rounded half up - the figure a refusal phrase names
	 * ("buy/sell gap 23 %"). Whole percents because this is prose rather than a measurement: the checks
	 * themselves compare exactly, in integers, and never read this.
	 */
	static long percentOf(final long part, final long whole)
	{
		if (whole <= 0L)
		{
			return 0L;
		}
		return (part * 100L + whole / 2L) / whole;
	}

	// ---------------------------------------------------------------- listeners and bridge getters (C20, L13)

	/**
	 * Registers a listener and hands it the current rows and status once, through {@code edt}, so a panel built
	 * after {@link #start()} is not blank until the next change. Duplicates are not detected.
	 */
	public void addListener(final Listener listener)
	{
		if (listener == null)
		{
			return;
		}
		listeners.add(listener);
		final List<MovementRow> currentRows;
		final Status currentStatus;
		synchronized (lock)
		{
			currentRows = rows;
			currentStatus = status;
		}
		edt.accept(() ->
		{
			if (!stopped && listeners.contains(listener))
			{
				deliver(listener, currentRows, currentStatus);
			}
		});
	}

	public void removeListener(final Listener listener)
	{
		if (listener != null)
		{
			listeners.remove(listener);
		}
	}

	/** The rows of the last computation (after the filter), unmodifiable; empty before the first one. */
	public List<MovementRow> currentRows()
	{
		synchronized (lock)
		{
			return rows;
		}
	}

	/** The status of the last publish; never null. */
	public Status currentStatus()
	{
		synchronized (lock)
		{
			return status;
		}
	}

	/** The filter in force; updated synchronously by {@link #setFilter} so the bridge can echo it at once. */
	public RowFilter filter()
	{
		synchronized (lock)
		{
			return filter;
		}
	}

	/** Whether the sidebar is currently counted as visible (the timer runs only while it is). */
	public boolean isVisible()
	{
		synchronized (lock)
		{
			return visible;
		}
	}

	/** The revision index in use, newest first, unmodifiable; empty before one is loaded or fetched (L4, L13). */
	public List<RevisionRef> revisionIndex()
	{
		synchronized (lock)
		{
			return index;
		}
	}

	/**
	 * One window's baseline as the rows read it - {@link PriceMap#EMPTY} when none - so the dev bridge can echo
	 * every window's {@code thenDay} and {@code revId} (L13), not just the current one's.
	 */
	public PriceMap baseline(@Nullable final MovementWindow window)
	{
		synchronized (lock)
		{
			final PriceMap map = window == null ? null : baselines.get(window);
			return map == null ? PriceMap.EMPTY : map;
		}
	}

	/**
	 * Pass-through to {@link GuidePriceClient#setEnabled(boolean)} for the dev bridge's {@code wiki=on|off}
	 * verb (L13): {@code BpmCommands(panel, service, gson)} holds no client reference. Developer mode only;
	 * no user-facing behaviour depends on it.
	 */
	public void setWikiEnabled(final boolean wikiEnabled)
	{
		wiki.setEnabled(wikiEnabled);
	}

	/** @see #setWikiEnabled(boolean) */
	public boolean isWikiEnabled()
	{
		return wiki.isEnabled();
	}

	// ---------------------------------------------------------------- lifecycle (C22)

	/**
	 * Reads {@code mapping.json}, {@code revindex.json} and every baseline file on the executor, then publishes
	 * (C22, K11, L4). Idempotent. A map read here never replaces a fresher one that a fetch already delivered, and
	 * a baseline file is refused outright ({@link #guideOrEmpty}) when it carries no revision id or when
	 * {@link PriceMapDto#schema} says an older build wrote it: only a guide revision has an id, so a map without
	 * one is a trade-era file the start-up sweep ({@code PriceStore.deleteStaleFiles}) did not get to - the
	 * second lock on the door that let "+12845.5 %" through - and a file from an older shape may not mean what
	 * this build reads it as. Each stored baseline also teaches the service which
	 * DAY its revision holds, so a relaunch can tell "the stored 1d baseline is still the right revision" without
	 * fetching it again (L10). Nothing is fetched here: the sidebar is hidden at start-up.
	 */
	public void start()
	{
		synchronized (lock)
		{
			if (started || stopped)
			{
				return;
			}
			started = true;
		}
		execute(() ->
		{
			// One read of each file answers the content AND its stamp: the staleness rule needs both, and a
			// second whole parse for one long is 150 KB of JSON at every start-up.
			final PriceStore.Stamped<Map<Integer, String>> mappingRead = store.loadMapping();
			final Map<Integer, String> loadedMapping = orEmpty(mappingRead == null ? null : mappingRead.value());
			final long loadedMappingAt = loadedMapping.isEmpty() ? 0L : mappingRead.fetchedAtMillis();
			final PriceStore.Stamped<List<RevisionRef>> indexRead = store.loadRevisionIndex();
			final List<RevisionRef> loadedIndex = orEmptyList(indexRead == null ? null : indexRead.value());
			final long loadedIndexAt = loadedIndex.isEmpty() ? 0L : indexRead.fetchedAtMillis();
			final Map<MovementWindow, PriceMap> loadedBaselines = new EnumMap<>(MovementWindow.class);
			for (final MovementWindow window : MovementWindow.values())
			{
				loadedBaselines.put(window, guideOrEmpty(store.loadBucket(window)));
			}
			// T2: the traded feeds off disk beside them, so a relaunch draws live rows before any request lands -
			// and, when the switch is off, so the six files simply sit there unread by anything below.
			final PriceStore.Stamped<Map<Integer, TradedPriceClient.Quote>> latestRead = store.loadTradedLatest();
			final Map<Integer, TradedPriceClient.Quote> loadedLatest =
				latestRead == null || latestRead.value() == null ? Collections.emptyMap() : latestRead.value();
			final long loadedLatestAt = loadedLatest.isEmpty() ? 0L : latestRead.fetchedAtMillis();
			final Map<MovementWindow, PriceStore.TradedDay> loadedTraded = new EnumMap<>(MovementWindow.class);
			for (final MovementWindow window : MovementWindow.values())
			{
				final PriceStore.TradedDay day = store.loadTradedDay(window);
				loadedTraded.put(window, day == null ? PriceStore.TradedDay.EMPTY : day);
			}
			synchronized (lock)
			{
				if (stopped)
				{
					return;
				}
				if (!loadedMapping.isEmpty() && (mapping.isEmpty() || loadedMappingAt > mappingAtMillis))
				{
					adoptMappingLocked(loadedMapping, loadedMappingAt);
				}
				if (!loadedIndex.isEmpty() && (index.isEmpty() || loadedIndexAt > indexAtMillis))
				{
					index = sortedIndex(loadedIndex);
					indexAtMillis = loadedIndexAt;
				}
				for (final Map.Entry<MovementWindow, PriceMap> entry : loadedBaselines.entrySet())
				{
					final PriceMap loaded = entry.getValue();
					if (loaded.fetchedAtMillis() > baselines.get(entry.getKey()).fetchedAtMillis())
					{
						baselines.put(entry.getKey(), loaded);
						rememberDayLocked(loaded.revId(), loaded.dataDay());
					}
				}
				if (!loadedLatest.isEmpty() && loadedLatestAt > latestAtMillis)
				{
					latest = loadedLatest;
					latestAtMillis = loadedLatestAt;
				}
				for (final Map.Entry<MovementWindow, PriceStore.TradedDay> entry : loadedTraded.entrySet())
				{
					final PriceStore.TradedDay loaded = entry.getValue();
					if (!loaded.isEmpty() && loaded.fetchedAtMillis() > tradedDays.get(entry.getKey()).fetchedAtMillis())
					{
						tradedDays.put(entry.getKey(), loaded);
					}
				}
			}
			log.debug("bank-portfolio-tracker: loaded {} mapped names, {} index lines, the baselines and {} traded"
				+ " quotes from disk", loadedMapping.size(), loadedIndex.size(), loadedLatest.size());
			// T2: "/latest is fetched ... at start-up - ONLY while livePrices is on". The switch is already in
			// hand: the plugin applies the config's options before it calls start().
			requestLatestIfLive(clockMillis.getAsLong(), "start-up");
			scheduleRecompute();
		});
	}

	/**
	 * Cancels the timers, cancels any wiki call still on the wire ({@link GuidePriceClient#cancelInFlight()} - a
	 * batched body download is most of a megabyte, and finishing it to throw the result away costs the user's
	 * bandwidth and OkHttp's dispatcher thread for nothing), marks the service stopped so every late callback
	 * drops itself, and clears the listeners. Never blocks (C22). Queued store writes still run; see
	 * {@link #flush()}.
	 */
	public void stop()
	{
		final ScheduledFuture<?> old;
		final List<ScheduledFuture<?>> pending;
		final TradedPriceClient tradedClient;
		synchronized (lock)
		{
			stopped = true;
			visible = false;
			old = tickTimer;
			tickTimer = null;
			pending = new ArrayList<>(oneShots.values());
			oneShots.clear();
			tradedClient = traded;
			// Y2: the hook holds the plugin, the client and the reader; a stopped service must not keep any of
			// them alive, and a Refresh that arrived late must not reach the client.
			carriedReader = null;
		}
		cancel(old);
		for (final ScheduledFuture<?> timer : pending)
		{
			cancel(timer);
		}
		wiki.cancelInFlight();
		if (tradedClient != null)
		{
			tradedClient.cancelInFlight();
		}
		listeners.clear();
	}

	/**
	 * A future over every store write queued and not yet finished, for {@code ClientShutdown.waitFor} (C22).
	 * Already complete when nothing is pending. The store has no queue of its own, so this is the record of the
	 * executor tasks {@link #setBank} and the fetch completions submitted.
	 */
	public Future<?> flush()
	{
		synchronized (lock)
		{
			pendingWrites.removeIf(CompletableFuture::isDone);
			if (pendingWrites.isEmpty())
			{
				return CompletableFuture.completedFuture(null);
			}
			return CompletableFuture.allOf(pendingWrites.toArray(new CompletableFuture<?>[0]));
		}
	}

	// ---------------------------------------------------------------- inputs from the plugin (C23)

	/**
	 * Login state (C23). On {@code true} the persisted bank for that account and profile is loaded on the
	 * executor unless the current snapshot already belongs to it (a hop or a re-login keeps the rows); on
	 * {@code false} the rows stay and the status says "(logged out)". Client thread in the plugin; any thread here.
	 *
	 * @param profileType {@code RuneScapeProfileType.getCurrent(client).name()}; null reads as ""
	 */
	public void setLoggedIn(final boolean loggedIn, final long accountHash, @Nullable final String profileType)
	{
		final String profile = profileType == null ? "" : profileType;
		final long generation;
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			this.loggedIn = loggedIn;
			if (!loggedIn || belongsTo(bank, accountHash, profile))
			{
				generation = 0L;
			}
			else
			{
				generation = ++loginGeneration;
			}
		}
		if (generation == 0L)
		{
			publishStatusOnly(null);
			return;
		}
		execute(() ->
		{
			final BankSnapshot loadedRaw = store.loadBank(accountHash, profile);
			final BankSnapshot loaded = loadedRaw == null ? BankSnapshot.EMPTY : loadedRaw;
			synchronized (lock)
			{
				if (generation != loginGeneration)
				{
					// A later login superseded this load; its own load is on the way.
					return;
				}
				if (belongsTo(bank, accountHash, profile) && bank.capturedAtMillis >= loaded.capturedAtMillis)
				{
					// setBank delivered this account's bank (from the container replay) while the disk was read.
					return;
				}
				bank = loaded;
			}
			log.debug("bank-portfolio-tracker: loaded the persisted bank for {}/{} ({} stacks)", accountHash, profile, itemCount(loaded));
			scheduleRecompute();
		});
	}

	/**
	 * A freshly captured bank (C23): replaces the snapshot, persists it on the executor and recomputes - which
	 * re-derives the anchor day over the new items (L3). Nothing is fetched here: a bank change moves neither
	 * the guide table nor the wiki's history; if the re-derived anchor day differs, the computation's own
	 * reconcile picks the baselines afresh. Null is ignored; {@link BankSnapshot#EMPTY} clears the bank without
	 * being saved (saving it would write {@code bank-0-STANDARD.json}). The snapshot is kept by reference and
	 * must not be mutated by the caller afterwards.
	 *
	 * <p><b>The write is skipped when the CONTENT has not moved</b> ({@link BankSnapshot#sameContentAs}). The
	 * plugin turns every bank {@code ItemContainerChanged} into a call here, and the client posts one per change
	 * to the container, so a burst of banking used to become a burst of full serialisations plus temp files plus
	 * atomic replaces on the client's one shared executor - while the recompute beside it has always been
	 * coalesced by {@link #scheduleRecompute}. A genuinely new bank is still written at once, so nothing is at
	 * risk on a crash; only a repeat of what is already on disk is dropped, and it keeps that file's earlier
	 * capture stamp.
	 */
	public void setBank(@Nullable final BankSnapshot snapshot)
	{
		setBank(snapshot, true);
	}

	/**
	 * The bank the sidebar is drawing, or null when nothing has ever been captured or loaded (addendum Y, line Y2):
	 * what the Refresh hook re-stamps its fresh carried half onto ({@code setBank(bank().withCarried(...))}).
	 *
	 * <p>It exists because "the STORED bank part" is not always the one the plugin itself captured. A player who
	 * logs in and opens the sidebar is looking at the snapshot this service loaded from DISK - carried half and
	 * all, as it was when they last visited a bank - and Refresh is one of the two moments Y2 promises will re-read
	 * what they are holding. Without a way to ask for that snapshot, Refresh could only move a bank captured in
	 * this session, and the stale carried stacks of the last one would stand until the player opened a bank.
	 *
	 * <p>{@link BankSnapshot#EMPTY} - and anything else with no capture stamp - answers null: it means "nothing has
	 * been captured", and re-publishing it with a carried half would file a bank under no account at all. The
	 * snapshot is handed out by reference and must not be mutated; {@link BankSnapshot#withCarried} makes the copy.
	 */
	@Nullable
	public BankSnapshot bank()
	{
		synchronized (lock)
		{
			return bank == null || bank.capturedAtMillis <= 0L ? null : bank;
		}
	}

	/**
	 * The hook {@link #refreshNow()} runs before it re-checks the prices (addendum Y, line Y2): a re-read of the
	 * player's INVENTORY and WORN containers, which the plugin registers at start-up and nulls at shutdown.
	 *
	 * <p>It lives here as a {@code Runnable} rather than as a client call of the service's own because the two
	 * container reads must happen on the CLIENT thread and the service has no business knowing that - the plugin
	 * owns the hop, reads the containers through {@code BankReader.readContainers} and republishes the stored bank
	 * beside the fresh carried part ({@code setBank(current.withCarried(...))}). When nothing is logged in the hook
	 * does nothing at all.
	 *
	 * <p>Refresh is the ONLY thing that runs it. The other trigger is the bank container event itself, which the
	 * plugin already handles and which reads all three containers in one pass; nothing else re-reads them, so an
	 * inventory change between those two moments is not tracked (Y2, the user's choice).
	 *
	 * @param reader the hook; null unregisters it, which is what {@code shutDown} does
	 */
	public void setCarriedReader(@Nullable final Runnable reader)
	{
		synchronized (lock)
		{
			carriedReader = reader;
		}
	}

	/**
	 * {@link #setBank(BankSnapshot)} with the disk write under the caller's control (checker, B021).
	 *
	 * <p>Everything else is identical - the snapshot becomes the bank, the panel fills, the anchor day is
	 * re-derived - so a synthetic bank drives the whole sidebar without touching a file. It exists for the dev
	 * verb {@code bpm bank=<id>:<qty>}, which stamps its made-up stacks with the LIVE account hash and profile
	 * (it must: {@code setLoggedIn}'s {@code belongsTo} check throws away a snapshot that belongs to nobody, and
	 * would reload the disk bank over it on the next {@code LOGGED_IN}). Persisting that was the harm: the file
	 * is keyed from the snapshot's own fields, so two made-up rows replaced the real capture in
	 * {@code bank-<liveHash>-<profile>.json} - and the operator drives exactly that verb while working the live
	 * acceptance list.
	 *
	 * @param persist whether the snapshot is written to disk; false is the synthetic, session-only path
	 */
	public void setBank(@Nullable final BankSnapshot snapshot, final boolean persist)
	{
		if (snapshot == null)
		{
			return;
		}
		final boolean save;
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			bank = snapshot;
			save = persist && snapshot != BankSnapshot.EMPTY && snapshot.capturedAtMillis > 0L
				&& !snapshot.sameContentAs(savedBank);
			if (save)
			{
				savedBank = snapshot;
			}
		}
		if (save)
		{
			submitWrite("saving the bank", () -> store.saveBank(snapshot));
		}
		scheduleRecompute();
	}

	/**
	 * The band, sort and window (C23, L10): recompute and publish; a window change also re-picks that window's
	 * baseline from the index (local, instant) and fetches its body only when the picked revision is not the
	 * stored one - and only while the sidebar is visible (nothing while hidden; the activation tick catches up)
	 * and only after {@link #start()} (the plugin applies the config filter BEFORE start, when the disk has not
	 * been read yet). An equal filter is a no-op (the plugin forwards every ConfigChanged, including the ones
	 * the panel itself caused).
	 */
	public void setFilter(@Nullable final RowFilter newFilter)
	{
		final RowFilter next = newFilter == null ? RowFilter.DEFAULT : newFilter;
		final long now = clockMillis.getAsLong();
		final boolean reconcile;
		synchronized (lock)
		{
			if (stopped || next.equals(filter))
			{
				return;
			}
			final boolean windowChanged = next.window() != filter.window();
			filter = next;
			reconcile = windowChanged && started;
		}
		scheduleRecompute();
		if (reconcile)
		{
			execute(() -> reconcile(now, "window changed", false));
		}
	}

	/**
	 * The five view switches (Q3, T1, Y1): whether the bank's cash counts, whether untradeable stacks are listed
	 * at their High Alchemy value, whether a row prints the whole stack (seam S1), whether an actively traded
	 * item is priced from the wiki's traded series, and whether what the player carries and wears is counted and
	 * listed. Stored under the lock and recomputed at once, because all but the holding switch change the figures
	 * - the rows, the counts and the bank value - and that one changes the order the gp sort puts the rows in.
	 *
	 * <p>Nothing is fetched for the three of addendum Q: every input those switches touch is already in memory, so
	 * flipping one is instant and costs no request. An equal set is a no-op, because the plugin forwards every
	 * {@code ConfigChanged}, including the ones the gear menu itself caused.
	 *
	 * <p><b>The fourth is different</b> (addendum T, line T1): {@code livePrices} turns a whole pair of feeds on
	 * and off. Turning it ON asks for the {@code /latest} snapshot when the stored one is missing or past its six
	 * hours - otherwise the sidebar would show guide prices until the next thirty-minute tick - and the reconcile
	 * that follows picks up each window's traded bucket. Turning it OFF sends nothing, ever: that is the whole of
	 * "off = not one traded request, exactly today".
	 *
	 * @param options the switches; null reads as {@link ViewOptions#DEFAULT}
	 */
	public void setOptions(@Nullable final ViewOptions options)
	{
		final ViewOptions next = options == null ? ViewOptions.DEFAULT : options;
		final long now = clockMillis.getAsLong();
		final boolean liveTurnedOn;
		synchronized (lock)
		{
			if (stopped || next.equals(this.options))
			{
				return;
			}
			liveTurnedOn = next.livePrices() && !this.options.livePrices();
			this.options = next;
		}
		if (liveTurnedOn)
		{
			execute(() ->
			{
				requestLatestIfLive(now, "live prices switched on");
				reconcileTraded(now, "live prices switched on", false);
			});
		}
		scheduleRecompute();
	}

	/**
	 * The switches as they stand now - what the NEXT computation will use. {@link Status#options()} is the other
	 * question: what the figures a reader is looking at were computed with, which is this one publish later.
	 */
	public ViewOptions options()
	{
		synchronized (lock)
		{
			return options;
		}
	}

	// ---------------------------------------------------------------- visibility, the timer and manual refresh (L10)

	/**
	 * Sidebar shown or hidden (L10). Shown: the rows are recomputed at once (RuneLite's table may have loaded or
	 * refreshed since the last computation - one hop, nothing sent) and ONE timer every {@link #TICK_MS} runs
	 * {@link #onTick} - first at once, on the executor, so it lands AFTER the disk read of {@link #start()}.
	 * Hidden: the timer is cancelled; a request already in flight completes normally. Idempotent.
	 *
	 * <p>That first run is paid on EVERY activation - each return to this sidebar tab - which is why it no longer
	 * downloads the traded {@code /latest} snapshot when the one in hand is under {@link #LATEST_MIN_AGE_MS} old
	 * (addendum AS, decision 3): opening the tab twice inside five minutes costs one download, not two. Everything
	 * else the tick does is the same on the first run as on every later one.
	 *
	 * <p>{@code scheduleWithFixedDelay} rather than {@code scheduleAtFixedRate}: the executor is the client's
	 * single shared thread, and a fixed-rate timer would fire a burst to catch up after any long task on it.
	 * The tick body swallows its own exceptions because an exception out of a periodic task cancels it for
	 * good ({@code ScheduledExecutorService} contract) and RuneLite's wrapper only logs.
	 */
	public void setVisible(final boolean visible)
	{
		final ScheduledFuture<?> old;
		synchronized (lock)
		{
			if (stopped || this.visible == visible)
			{
				return;
			}
			this.visible = visible;
			old = tickTimer;
			tickTimer = null;
		}
		cancel(old);
		if (!visible)
		{
			log.debug("bank-portfolio-tracker: panel hidden, timer cancelled");
			return;
		}
		final ScheduledFuture<?> timer = schedule("the tick", this::onTick, 0L, TICK_MS);
		final boolean keep;
		synchronized (lock)
		{
			keep = this.visible && !stopped;
			if (keep)
			{
				tickTimer = timer;
			}
		}
		if (!keep)
		{
			cancel(timer);
			return;
		}
		scheduleRecompute();
	}

	/**
	 * The price re-check alone, as every caller before addendum AS8 pressed it: {@link #refreshNow(boolean)} with
	 * {@code false}, so a press inside the cooldown still says "Refreshed n s ago - wait". It is the press where
	 * nothing but the prices refreshes - the gear menu's "Refresh prices now", and the Refresh link while the bank
	 * is closed.
	 */
	public void refreshNow()
	{
		refreshNow(false);
	}

	/**
	 * The Refresh button and the bridge's {@code refresh} verb (L10: "index + R0 + current window, 30 s
	 * cooldown"): refetches the revision index regardless of its age - its completion fetches R0 when the newest
	 * revision changed and every window's missing body in one call - refetches the mapping when it is older than
	 * {@link #MAPPING_MAX_AGE_MS}, and recomputes the rows (the guide side is a local read, so a refresh re-reads
	 * it too). Unless the previous manual refresh was under {@link #MANUAL_COOLDOWN_MS} ago, in which case
	 * nothing is sent and a status-only publish says "Refreshed n s ago - wait" - a line that now DELETES ITSELF
	 * the moment the cooldown ends, instead of standing until the next publish of any kind (which, with the
	 * sidebar idle, is the thirty-minute tick). A manual refresh is allowed to fetch even while the sidebar is
	 * hidden (the bridge verb): it is an explicit act, and the point of the hidden rule is to stop the timers,
	 * not the user.
	 *
	 * <p>Two things the cooldown deliberately does NOT do. It is not spent when an index request is already in
	 * flight: {@link #startIndex} would drop the second request, so nothing new goes out, the stamp stays where
	 * it was, and the manual INTENT is remembered ({@link #manualPending}) and handed to the reconcile the
	 * in-flight request completes into - which is what lets a manual refresh fetch bodies while the sidebar is
	 * hidden. And it treats a backwards jump of the wall clock (an NTP correction) as an expired cooldown rather
	 * than as "refreshed a moment ago", which would otherwise refuse every refresh until real time caught up.
	 *
	 * <p><b>{@code quietCooldown}</b> (addendum AS, line AS8). The user, on the live AS7 build: "manually clicking the
	 * refresh button should refresh everything for the user". Until then a click with the bank open only re-read the
	 * items and a click with it closed only re-checked the prices, so the first changed nothing the user could see
	 * while the second moved prices. Now one click does both: with the bank open the plugin re-reads the bank, the
	 * inventory and the worn gear and the sidebar redraws, and then the link presses this with {@code true} for the
	 * prices. Inside the cooldown that price half is refused as it always was - nothing is sent, the stamp stays
	 * where it was - but SILENTLY: no "Refreshed n s ago - wait", and so no timer to take it down. The click did
	 * refresh what the user can see, and a red line under the fresh items telling them to wait would call a served
	 * click a refused one; the line is kept for the press where nothing at all refreshed - the bank closed and the
	 * cooldown running - which is {@code false}. A line an earlier refusal left standing is not this press's to
	 * touch: its own clear still takes it down.
	 *
	 * <p>Nothing else depends on the flag, because the price half of the click IS the whole price check: the carried
	 * hook runs on every press; a served press fetches, recomputes, stamps {@code lastManualRefreshMillis} and so
	 * spends the cooldown; and a press that rides on an index already in flight hands its intent on and spends
	 * nothing - the same under both.
	 *
	 * @param quietCooldown true when the caller has just refreshed the items itself, so a cooldown refusal of the
	 *                      prices says nothing; false for a press that refreshes the prices alone, which says it
	 */
	public void refreshNow(final boolean quietCooldown)
	{
		final long now = clockMillis.getAsLong();
		final long secondsAgo;
		final long remainingMs;
		final boolean refresh;
		final boolean riding;
		final Runnable carried;
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			carried = carriedReader;
			final long since = now - lastManualRefreshMillis;
			if (manualRefreshed && since >= 0L && since < MANUAL_COOLDOWN_MS)
			{
				secondsAgo = since / 1000L;
				remainingMs = Math.max(1L, MANUAL_COOLDOWN_MS - since);
				refresh = false;
				riding = false;
			}
			else if (indexInFlight)
			{
				manualPending = true;
				secondsAgo = 0L;
				remainingMs = 0L;
				refresh = false;
				riding = true;
			}
			else
			{
				manualRefreshed = true;
				lastManualRefreshMillis = now;
				secondsAgo = 0L;
				remainingMs = 0L;
				refresh = true;
				riding = false;
			}
		}
		// Y2: the carried half is re-read on every Refresh, BEFORE the prices are - "press Refresh: that row's
		// quantity has followed" is half of what the button promises, and two container reads cost nothing. It runs
		// even when the cooldown refuses the fetch below: the user pressed the button, and what they are carrying
		// has no cooldown. The hook is the plugin's, so one that threw would abandon the whole refresh; it is
		// logged and the refresh goes on without it.
		if (carried != null)
		{
			try
			{
				carried.run();
			}
			catch (final RuntimeException e)
			{
				log.debug("bank-portfolio-tracker: the carried-container read failed on a manual refresh", e);
			}
		}
		if (riding)
		{
			// Everything a manual refresh does EXCEPT the index, which is already on its way with the intent
			// attached: the mapping is fetched on its own schedule and the rows are re-read either way.
			log.debug("bank-portfolio-tracker: manual refresh rides on the index request already in flight");
			requestMappingIfStale(now, "manual refresh");
			scheduleRecompute();
			return;
		}
		if (!refresh)
		{
			if (quietCooldown)
			{
				// AS8: this same click has just re-read the items, so something DID refresh - no "wait" line, and
				// with no line there is nothing for a cooldown clear to take down.
				log.debug("bank-portfolio-tracker: manual refresh refused quietly (the items were re-read), the last "
					+ "one was {} s ago", secondsAgo);
				return;
			}
			log.debug("bank-portfolio-tracker: manual refresh refused, the last one was {} s ago", secondsAgo);
			scheduleCooldownClear(remainingMs);
			publishStatusOnly(Problem.of(problemCooldown(secondsAgo), ProblemKind.COOLDOWN));
			return;
		}
		execute(() -> startIndex(now, "manual refresh", true));
		requestMappingIfStale(now, "manual refresh");
		// T2: "on every accepted Refresh". The window buckets are offered again too - since addendum U a bucket is
		// asked for once per LIVE day per window (never the guide's anchor day, which is U1's whole point), and
		// Refresh is the user's one lever after a day's fetch failed or U2's fallback stood in for it.
		execute(() ->
		{
			synchronized (lock)
			{
				tradedAsked.clear();
			}
			startLatest(now, "manual refresh");
			reconcileTraded(now, "manual refresh", true);
		});
		scheduleRecompute();
	}

	/**
	 * Arms the one-shot publish that removes the cooldown sentence when the cooldown ends. Only one is ever
	 * pending - hammering the button replaces it - and {@link #stop()} cancels it, so the invariant that every
	 * timer this service takes out is cancelled on stop holds.
	 *
	 * <p>{@code publishStatusOnly(null)} rather than a blank: it rebuilds the line from state, so a genuine
	 * problem underneath ("No 1d history yet", "Wiki history down - no movement") comes back rather than
	 * being wiped.
	 */
	private void scheduleCooldownClear(final long delayMs)
	{
		armOneShot(OneShot.COOLDOWN_CLEAR, delayMs, "the cooldown clear", () ->
		{
			if (!stopped)
			{
				publishStatusOnly(null);
			}
		});
	}

	/**
	 * Arms one of the {@link OneShot} timers: schedule it, swap it into its slot under the lock, and cancel
	 * whatever that slot held. Both callers want exactly this, and getting any step wrong leaks a timer past
	 * {@link #stop()}.
	 *
	 * <p>The order is the careful part. The task is scheduled BEFORE the lock so a rejecting executor costs a
	 * debug line rather than the lock, and the swap re-reads {@code stopped} INSIDE the lock: a stop that landed
	 * while we were scheduling means the timer is not stored at all and is cancelled on the way out, which is
	 * what keeps "every timer this service takes out is cancelled on stop" true.
	 *
	 * @param slot    which timer this is; the slot's previous timer is cancelled
	 * @param delayMs how long to wait
	 * @param what    the timer's name, for the one debug line a rejecting executor produces
	 * @param body    what to run; it must check {@code stopped} for itself, since a timer already running cannot
	 *                be cancelled
	 */
	private void armOneShot(final OneShot slot, final long delayMs, final String what, final Runnable body)
	{
		final ScheduledFuture<?> timer;
		try
		{
			timer = executor.schedule(body, delayMs, TimeUnit.MILLISECONDS);
		}
		catch (final RuntimeException e)
		{
			// RejectedExecutionException: the client is shutting its executor down.
			log.debug("bank-portfolio-tracker: could not schedule {}", what, e);
			return;
		}
		final ScheduledFuture<?> old;
		final boolean keep;
		synchronized (lock)
		{
			keep = !stopped;
			old = keep ? oneShots.put(slot, timer) : oneShots.remove(slot);
		}
		cancel(old);
		if (!keep)
		{
			cancel(timer);
		}
	}

	/**
	 * Executor: the one periodic task while visible (L10). The index is refetched when older than
	 * {@link #HISTORY_MAX_AGE_MS} (its completion picks the baselines); otherwise the baselines are picked
	 * locally against the index in hand and only missing bodies are fetched; the mapping is refetched weekly;
	 * the traded {@code /latest} snapshot is asked for only when the one in hand is missing or at least
	 * {@link #LATEST_MIN_AGE_MS} old (addendum AS, decision 3 - this also runs at once on every activation, see
	 * {@link #setVisible}); the rows are recomputed unconditionally - that IS the 30-minute cadence of L1, and it
	 * re-derives the anchor day.
	 */
	private void onTick()
	{
		final long now = clockMillis.getAsLong();
		final boolean fetchIndex;
		final boolean fetchMapping;
		final boolean fetchLatest;
		final long latestAt;
		synchronized (lock)
		{
			if (stopped || !visible)
			{
				return;
			}
			fetchIndex = !indexInFlight && indexStaleLocked(now);
			fetchMapping = !mappingInFlight && mappingStaleLocked(now);
			fetchLatest = latestDueLocked(now);
			latestAt = latestAtMillis;
		}
		if (fetchIndex)
		{
			startIndex(now, "tick", false);
		}
		else
		{
			reconcile(now, "tick", false);
		}
		if (fetchMapping)
		{
			startMapping(now, "tick");
		}
		// T2 fetched /latest "on the 30-minute tick" unconditionally while the switch is on, because it is the only
		// figure in the plugin that moves inside a day and the whole point of the switch is to follow it. The periodic
		// run still does - a snapshot the previous run fetched is TICK_MS old by now - but this also runs at once on
		// every activation, and there a snapshot younger than LATEST_MIN_AGE_MS is left alone (addendum AS, decision
		// 3). startLatest keeps its own rules either way: the switch, the client, one request in flight.
		if (fetchLatest)
		{
			startLatest(now, "tick");
		}
		else
		{
			log.debug("bank-portfolio-tracker: traded /latest left alone (tick): the snapshot in hand is {} s old,"
				+ " under the {} s floor", (now - latestAt) / 1000L, LATEST_MIN_AGE_MS / 1000L);
			// The one other thing that fetch's landing did (finishLatest): offer every window's traded bucket, here
			// against the snapshot already in hand. reconcile offers them as well - on this tick, or when the index
			// this tick asked for lands - but not when it returns early (no index at all, or a body batch in flight),
			// and a snapshot that landed while the sidebar was hidden (start-up's own) offered none, because no
			// bucket is asked for while hidden. Idempotent: a day asked for, in flight or in hand is skipped.
			reconcileTraded(now, "tick", false);
		}
		scheduleRecompute();
	}

	// ---------------------------------------------------------------- L4: the revision index

	/**
	 * Executor. One index request in flight at a time; the completion hops back to the executor. A status-only
	 * publish follows so the K7 "No 1d history yet" line shows while the request is out.
	 *
	 * @param manual a manual refresh: the reconcile that follows may fetch bodies even while hidden
	 */
	private void startIndex(final long now, final String reason, final boolean manual)
	{
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			if (indexInFlight)
			{
				if (manual)
				{
					// Nothing new goes out, so the manual intent rides on the request already in flight (B011).
					manualPending = true;
				}
				log.debug("bank-portfolio-tracker: revision index skipped ({}): a request is in flight", reason);
				return;
			}
			indexInFlight = true;
		}
		log.debug("bank-portfolio-tracker: GET the revision index ({})", reason);
		start(() -> wiki.fetchRevisionIndex(now), (list, error) -> finishIndex(now, manual, list, error),
			"fetchRevisionIndex");
		publishStatusOnly(null);
	}

	/**
	 * Issues one wiki call and routes its outcome back onto the executor. The three fetches (index, bodies,
	 * mapping) share this because they share the invariant that matters: the caller has ALREADY claimed an
	 * in-flight flag under the lock, so the completion must happen whatever the client does - a throw, an
	 * {@link Error}, or a null future all become a failed completion rather than a flag claimed for the rest of
	 * the session, which is a history subsystem that stops with nothing on screen and nothing in the log (B006).
	 *
	 * <p>{@link Throwable} and not {@code RuntimeException} for that same reason, and the completion hops through
	 * {@link #execute} because a real future completes on OkHttp's dispatcher thread and no state here may be
	 * touched from there.
	 *
	 * @param call   makes the request; may throw, and may (wrongly) answer null
	 * @param finish the outcome handler, run on the executor - exactly one of its two arguments is meaningful
	 * @param what   the client method's name, for the "answered null" message
	 */
	private <T> void start(final Supplier<CompletableFuture<T>> call, final BiConsumer<T, Throwable> finish,
		final String what)
	{
		CompletableFuture<T> future;
		try
		{
			future = call.get();
		}
		catch (final Throwable e)
		{
			future = failed(e);
		}
		if (future == null)
		{
			future = failed(new IllegalStateException(what + " answered null"));
		}
		future.whenComplete((value, error) -> execute(() -> finish.accept(value, error)));
	}

	/**
	 * Executor: the index outcome (L4). Success MERGES the list with the one already held, stamps it, persists it
	 * and reconciles - R0 and every window's baseline are re-picked against it; a failure keeps the stored index
	 * (still valid history: revisions are immutable) and marks the service degraded, and still reconciles
	 * locally so a window can be served from memory.
	 *
	 * <p><b>Why a merge and not a replacement</b> (B027). One call returns the newest {@value GuidePriceClient#INDEX_LIMIT}
	 * revisions and no more - that is the anonymous ceiling - which reaches back about 232 days at the one bot
	 * run a day L-C measured, but only about 178 at 1.4 revisions a day. A wave of maintenance edits therefore
	 * eats the margin over the 180d window, and replacing the file wholesale threw away revisions the plugin had
	 * already seen: the 180d chip would read "No 180d history" over history that WAS in hand an hour earlier, and
	 * the sentence for that is the same one an honestly short page gets. Keeping up to {@value #INDEX_KEPT} of
	 * them costs a few tens of kilobytes and lets a long-running install reach further back than any single call
	 * can. A revision is immutable, so an older entry can never go stale; one deleted from the wiki simply fails
	 * its body fetch and is remembered as unusable (L7).
	 */
	private void finishIndex(final long fetchedAt, final boolean manual, @Nullable final List<RevisionRef> list,
		@Nullable final Throwable error)
	{
		final boolean ok = error == null && list != null && !list.isEmpty();
		final List<RevisionRef> adopted;
		final boolean manualNow;
		synchronized (lock)
		{
			indexInFlight = false;
			if (stopped)
			{
				return;
			}
			manualNow = manual || manualPending;
			manualPending = false;
			if (ok)
			{
				adopted = mergedIndex(index, list);
				index = adopted;
				indexAtMillis = fetchedAt;
				historyFailed = false;
			}
			else
			{
				adopted = null;
				historyFailed = true;
			}
		}
		if (ok)
		{
			log.debug("bank-portfolio-tracker: the revision index holds {} revisions, newest {}", adopted.size(), adopted.get(0));
			submitWrite("saving " + PriceStore.REVINDEX_FILE, () -> store.saveRevisionIndex(adopted, fetchedAt));
		}
		else
		{
			log.debug("bank-portfolio-tracker: the revision index failed, keeping the stored one: {}", describe(error));
		}
		reconcile(fetchedAt, ok ? "revision index" : "revision index failed", manualNow);
		scheduleRecompute();
	}

	// ---------------------------------------------------------------- L3, L5, L6, L10: picking and fetching bodies

	/**
	 * Executor. The local half of L10, run whenever the index, the anchor day or the window changes and on every
	 * tick: settle which revision is R0 (the newest usable one in the index) and which revision each window's
	 * baseline should be read from ({@link #resolveLocked}), adopt from memory whatever is already in hand, and
	 * fetch the rest - R0 and every window's missing body - in ONE batched call (L6). A window is fetched only
	 * when its picked revision differs from the stored one; a revision is never fetched twice in a session.
	 *
	 * <p>The batched call goes out only while the sidebar is visible or on a manual refresh; while hidden the
	 * local part still runs (a window switch can be served from memory) and the activation tick fetches the rest.
	 * While a body fetch is in flight nothing is decided: its completion runs this method again.
	 *
	 * @param manual true on the manual-refresh chain (a fetch is allowed even while hidden)
	 */
	private void reconcile(final long now, final String reason, final boolean manual)
	{
		final Set<Long> want = new LinkedHashSet<>();
		final EnumSet<MovementWindow> served = EnumSet.noneOf(MovementWindow.class);
		final Map<MovementWindow, PriceMap> adopted = new EnumMap<>(MovementWindow.class);
		final boolean fetch;
		boolean r0Wanted = false;
		boolean r0Adopted = false;
		LocalDate reachShort = null;
		synchronized (lock)
		{
			if (stopped || index.isEmpty())
			{
				return;
			}
			if (tablesInFlight)
			{
				log.debug("bank-portfolio-tracker: reconcile ({}) deferred: a body fetch is in flight", reason);
				return;
			}

			final RevisionRef newest = newestUsableLocked();
			if (newest != null && r0.revId() != newest.revId())
			{
				final GuideSnapshot table = tablesByRev.get(newest.revId());
				if (table != null)
				{
					adoptR0Locked(table);
					r0Adopted = true;
				}
				else
				{
					want.add(newest.revId());
					r0Wanted = true;
				}
			}

			noHistory.clear();
			exhausted.clear();
			for (final MovementWindow window : MovementWindow.values())
			{
				final Resolution resolution = resolveLocked(window);
				if (resolution.noHistory)
				{
					noHistory.add(window);
					continue;
				}
				if (resolution.exhausted)
				{
					exhausted.add(window);
					continue;
				}
				if (resolution.accept != null && baselines.get(window).revId() != resolution.accept.revId())
				{
					final GuideSnapshot table = tablesByRev.get(resolution.accept.revId());
					if (table != null)
					{
						adopted.put(window, adoptLocked(window, table));
					}
					else
					{
						// Its day is known (from a baseline file) but its bytes are not in memory.
						want.add(resolution.accept.revId());
						served.add(window);
					}
				}
				if (resolution.fetch != null)
				{
					want.add(resolution.fetch.revId());
					served.add(window);
				}
			}
			evictLocked();
			// B009: "No 180d history" is the honest answer for a page that does not reach that far back, and it
			// is ALSO what a truncated index produces - one call returns at most INDEX_LIMIT revisions, about 232
			// days at one bot run a day but only about 178 at 1.4. Say which, once per session, at a level the
			// user's own client.log carries, so the two cannot be confused in a bug report.
			if (!noHistory.isEmpty() && !indexReachLogged && index.size() >= GuidePriceClient.INDEX_LIMIT)
			{
				indexReachLogged = true;
				reachShort = index.get(index.size() - 1).editDay();
			}
			fetch = !want.isEmpty() && (visible || manual);
			if (fetch)
			{
				tablesInFlight = true;
			}
		}
		if (reachShort != null)
		{
			log.info("bank-portfolio-tracker: the revision index is full ({} revisions back to {}) and does not reach"
				+ " every window - the guide page is edited more than once a day, so the longest windows may read"
				+ " \"no history\" over history the wiki still has", GuidePriceClient.INDEX_LIMIT, reachShort);
		}
		for (final Map.Entry<MovementWindow, PriceMap> entry : adopted.entrySet())
		{
			log.debug("bank-portfolio-tracker: {} baseline is now revision {} of {} ({} ids) ({})",
				entry.getKey().name(), entry.getValue().revId(), entry.getValue().dataDay(), entry.getValue().size(), reason);
			submitWrite("saving the " + entry.getKey().name() + " baseline",
				() -> store.saveBucket(entry.getKey(), entry.getValue()));
		}
		if (r0Adopted || !adopted.isEmpty())
		{
			scheduleRecompute();
		}
		if (fetch)
		{
			startTables(want, served, r0Wanted, now, reason, manual);
		}
		else if (!want.isEmpty())
		{
			log.debug("bank-portfolio-tracker: {} body fetch(es) deferred while hidden ({})", want.size(), reason);
		}
		// The traded buckets are offered here too. Since addendum U they owe nothing to the baselines above - their
		// days are counted back from the live snapshot (U1), and the snapshot's own completion offers them first -
		// but this is still the one place every periodic and manual path passes through, so a session whose
		// /latest landed while the sidebar was hidden picks them up on the next tick.
		reconcileTraded(now, reason, manual);
	}

	/**
	 * Under the lock. The L5 rule for one window, against the anchor day in force and what is known about the
	 * candidate bodies:
	 * <ol>
	 * <li>no anchor day yet: nothing to pick;</li>
	 * <li>{@code first = pickThen(index, D.minusDays(N))}: null means the whole index is newer than the target -
	 * "No 180d history" (L11), never a request;</li>
	 * <li>{@code first}'s body unknown: fetch it;</li>
	 * <li>its day marker is the date it was saved on (walk-back included - the date pickThen actually landed on
	 * is the one remembered): accept;</li>
	 * <li>its day marker is OLDER than that date (a name-only edit republishing the previous table, L-E): the ONE
	 * retry - the revision saved just before it on the same date, or the newest bot revision of the next date
	 * when the date had only that human edit. Unknown: adopt the stale body meanwhile (a real past price beats
	 * none) and fetch the candidate; known and on the date: accept the candidate; known and also off: accept
	 * whichever day is closer to the date, the older one on a tie - "if still not T, accept the table and label
	 * rows/status with its real day". A candidate that is already known is never fetched again, which is what
	 * makes this loop at most once.</li>
	 * </ol>
	 * A body the wiki returned but the client refused (L7) counts as known-and-unusable and is skipped the way a
	 * stale one is; a window with nothing usable left is {@link Resolution#EXHAUSTED}.
	 */
	private Resolution resolveLocked(final MovementWindow window)
	{
		final LocalDate target = window.targetDate(anchorDay);
		if (target == null)
		{
			return Resolution.NONE;
		}
		final RevisionRef first = RevisionRef.pickThen(index, target);
		if (first == null)
		{
			return Resolution.NO_HISTORY;
		}
		final LocalDate landed = first.editDay();
		if (!knownDays.containsKey(first.revId()))
		{
			return Resolution.fetch(first);
		}
		final LocalDate firstDay = knownDays.get(first.revId());
		if (firstDay != null && !firstDay.isBefore(landed))
		{
			return Resolution.accept(first);
		}

		RevisionRef second = RevisionRef.previousOn(index, first);
		if (second == null)
		{
			second = RevisionRef.newestBotOn(index, landed.plusDays(1));
		}
		if (second == null || second.revId() == first.revId())
		{
			return firstDay != null ? Resolution.accept(first) : Resolution.EXHAUSTED;
		}
		if (!knownDays.containsKey(second.revId()))
		{
			return firstDay != null ? Resolution.acceptAndFetch(first, second) : Resolution.fetch(second);
		}
		final LocalDate secondDay = knownDays.get(second.revId());
		if (secondDay == null)
		{
			return firstDay != null ? Resolution.accept(first) : Resolution.EXHAUSTED;
		}
		if (firstDay == null || secondDay.equals(landed))
		{
			return Resolution.accept(second);
		}
		final long firstOff = Math.abs(ChronoUnit.DAYS.between(landed, firstDay));
		final long secondOff = Math.abs(ChronoUnit.DAYS.between(landed, secondDay));
		return secondOff < firstOff ? Resolution.accept(second) : Resolution.accept(first);
	}

	/**
	 * Executor. The batched body call of L6, one in flight at a time (the caller claimed the flag under the
	 * lock). The future completes on OkHttp's dispatcher thread, so the completion hops back to the executor
	 * before touching any state; a null or throwing client is treated as a failed fetch rather than a stuck
	 * flag. A status-only publish follows so "No 30d history yet" shows while the request is out.
	 */
	private void startTables(final Set<Long> revIds, final EnumSet<MovementWindow> served, final boolean r0Wanted,
		final long now, final String reason, final boolean manual)
	{
		final List<Long> ids = new ArrayList<>(revIds);
		log.debug("bank-portfolio-tracker: GET {} guide table(s) in one call {} ({})", ids.size(), ids, reason);
		start(() -> wiki.fetchTables(ids, now),
			(tables, error) -> finishTables(ids, served, r0Wanted, now, manual, tables, error), "fetchTables");
		publishStatusOnly(null);
	}

	/**
	 * Executor: the batch outcome (L6, L7, L11). Success remembers every body that arrived - and, for a requested
	 * revision that did NOT arrive, that the wiki has nothing usable under that id, so it is never asked for
	 * again - then reconciles, which adopts what is now in memory and may issue L5's one retry. A failure keeps
	 * every stored baseline (a real past price beats none, D9), marks the windows it was serving and the service
	 * degraded, and does NOT reconcile with fetching: never a tight loop.
	 *
	 * <p>A failure arms ONE backed-off retry instead of waiting out the whole {@link #TICK_MS} (B109). A wiki
	 * hiccup used to leave the panel red for up to half an hour after the wiki came back, with the user's only
	 * lever a Refresh the failure sentence does not mention. The delay starts at {@link #RETRY_MS}, doubles per
	 * consecutive failure and stops at the tick, and the retry runs only while the sidebar is visible - the same
	 * rule every other fetch obeys.
	 */
	private void finishTables(final List<Long> requested, final EnumSet<MovementWindow> served, final boolean r0Wanted,
		final long now, final boolean manual, @Nullable final Map<Long, GuideSnapshot> tables, @Nullable final Throwable error)
	{
		final boolean ok = error == null && tables != null && !tables.isEmpty();
		int arrived = 0;
		final long retryIn;
		synchronized (lock)
		{
			tablesInFlight = false;
			if (stopped)
			{
				return;
			}
			retryIn = ok ? 0L : nextRetryDelayLocked();
			if (ok)
			{
				for (final Long revId : requested)
				{
					final GuideSnapshot table = tables.get(revId);
					if (table != null && !table.isEmpty() && table.revId() == revId && table.dataDay() != null)
					{
						tablesByRev.put(revId, table);
						knownDays.put(revId, table.dataDay());
						arrived++;
					}
					else
					{
						log.debug("bank-portfolio-tracker: revision {} came back unusable; it will not be asked for again", revId);
						knownDays.put(revId, null);
					}
				}
				historyFailed = false;
				baselineFailed.removeAll(served);
				retryDelayMs = 0L;
			}
			else
			{
				historyFailed = true;
				baselineFailed.addAll(served);
			}
		}
		if (ok)
		{
			log.debug("bank-portfolio-tracker: {} of {} guide table(s) arrived{}", arrived, requested.size(), r0Wanted ? " (R0 among them)" : "");
			reconcile(now, "guide tables", manual);
		}
		else
		{
			log.debug("bank-portfolio-tracker: the guide tables failed, keeping the stored baselines: {}; retrying in {} ms",
				describe(error), retryIn);
			scheduleRetry(retryIn);
		}
		scheduleRecompute();
	}

	/**
	 * Under the lock. The delay before the next body attempt: {@link #RETRY_MS} doubled once per consecutive
	 * failure and never past {@link #TICK_MS}, which is the cadence a persistent outage settles onto.
	 */
	private long nextRetryDelayLocked()
	{
		final long previous = retryDelayMs;
		retryDelayMs = previous <= 0L ? RETRY_MS : Math.min(TICK_MS, previous * 2L);
		return retryDelayMs;
	}

	/**
	 * Arms the single backed-off body retry of {@link #finishTables} (B109). Only one is ever pending; it runs
	 * only while the sidebar is still visible and the service is still running, and {@link #stop()} cancels it.
	 */
	private void scheduleRetry(final long delayMs)
	{
		armOneShot(OneShot.BODY_RETRY, delayMs, "the body retry", () ->
		{
			final long now = clockMillis.getAsLong();
			synchronized (lock)
			{
				if (stopped || !visible)
				{
					return;
				}
			}
			reconcile(now, "retry after a failed body fetch", false);
			scheduleRecompute();
		});
	}

	/**
	 * Under the lock. The newest revision in the index whose body is not known to be unusable - R0's identity
	 * (L3). Normally {@code index.get(0)}; the loop only matters when the wiki's newest revision is one the
	 * client refused (L7), in which case the one before it is the newest table there is.
	 */
	@Nullable
	private RevisionRef newestUsableLocked()
	{
		for (final RevisionRef ref : index)
		{
			if (ref != null && !(knownDays.containsKey(ref.revId()) && knownDays.get(ref.revId()) == null))
			{
				return ref;
			}
		}
		return null;
	}

	/** Under the lock. R0 and its id projection; the anchor day follows on the next computation (L3). */
	private void adoptR0Locked(final GuideSnapshot table)
	{
		r0 = table;
		r0Map = project(table, foldedNames, owners, tradeableItemsOf(bank, options));
		tablesByRev.put(table.revId(), table);
		rememberDayLocked(table.revId(), table.dataDay());
	}

	/** Under the lock. One window's baseline from a table in memory (L7 stamps: the table's own day marker). */
	private PriceMap adoptLocked(final MovementWindow window, final GuideSnapshot table)
	{
		final PriceMap map = project(table, foldedNames, owners, tradeableItemsOf(bank, options));
		baselines.put(window, map);
		tablesByRev.put(table.revId(), table);
		rememberDayLocked(table.revId(), table.dataDay());
		baselineFailed.remove(window);
		return map;
	}

	/** Under the lock. A revision's day marker, learned from a fetched body or a baseline file; a null day teaches nothing. */
	private void rememberDayLocked(final long revId, @Nullable final LocalDate day)
	{
		if (revId > 0L && day != null)
		{
			knownDays.put(revId, day);
		}
	}

	/**
	 * Under the lock. Drops every table that is neither R0 nor behind an adopted baseline, except the newest
	 * {@link #EXTRA_TABLES_KEPT} of them. A body that arrived and was not adopted keeps its DAY in
	 * {@link #knownDays}, which is all a later resolution reads; its bytes are kept a while because they tend to
	 * be wanted again shortly: the R0 the index just superseded becomes the 1d baseline the moment the anchor
	 * day advances (L3), and the loser of L5's retry is still the closer day when the winner turns out worse.
	 */
	private void evictLocked()
	{
		final Set<Long> keep = new HashSet<>();
		keep.add(r0.revId());
		for (final PriceMap map : baselines.values())
		{
			keep.add(map.revId());
		}
		final List<Long> spare = new ArrayList<>();
		for (final Long revId : tablesByRev.keySet())
		{
			if (!keep.contains(revId))
			{
				spare.add(revId);
			}
		}
		// Insertion order: the oldest spare tables go first.
		final int evict = Math.max(0, spare.size() - EXTRA_TABLES_KEPT);
		for (int i = 0; i < evict; i++)
		{
			tablesByRev.remove(spare.get(i));
		}
	}

	// ---------------------------------------------------------------- K3/L8: the mapping

	/** Any thread: the age test runs on the executor, where the mapping is settled. */
	private void requestMappingIfStale(final long now, final String reason)
	{
		execute(() ->
		{
			synchronized (lock)
			{
				if (mappingInFlight || !mappingStaleLocked(now))
				{
					return;
				}
			}
			startMapping(now, reason);
		});
	}

	/** Executor. One mapping request in flight at a time; a second call meanwhile is a no-op. */
	private void startMapping(final long now, final String reason)
	{
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			if (mappingInFlight)
			{
				log.debug("bank-portfolio-tracker: mapping skipped ({}): a request is already in flight", reason);
				return;
			}
			mappingInFlight = true;
		}
		log.debug("bank-portfolio-tracker: GET the id/name mapping ({})", reason);
		start(() -> wiki.fetchMapping(now), (names, error) -> finishMapping(now, names, error), "fetchMapping");
	}

	/**
	 * Executor: the mapping outcome. Success adopts the table, re-projects R0 and every baseline whose table is
	 * in memory onto it (a name the composition could not match now can be, and an L8 b refusal can now be made)
	 * and persists both; a failure is silent - the composition names carry on, the next tick retries - because
	 * it is not a K7 problem sentence.
	 */
	private void finishMapping(final long fetchedAt, @Nullable final Map<Integer, String> names, @Nullable final Throwable error)
	{
		final boolean ok = error == null && names != null && !names.isEmpty();
		final Map<Integer, String> adopted;
		final Map<MovementWindow, PriceMap> reprojected = new EnumMap<>(MovementWindow.class);
		synchronized (lock)
		{
			mappingInFlight = false;
			if (stopped)
			{
				return;
			}
			if (ok)
			{
				adopted = Collections.unmodifiableMap(new LinkedHashMap<>(names));
				for (final MovementWindow window : adoptMappingLocked(adopted, fetchedAt))
				{
					reprojected.put(window, baselines.get(window));
				}
			}
			else
			{
				adopted = null;
			}
		}
		if (ok)
		{
			log.debug("bank-portfolio-tracker: the mapping answered {} names; {} baselines re-projected", adopted.size(), reprojected.size());
			submitWrite("saving " + PriceStore.MAPPING_FILE, () -> store.saveMapping(adopted, fetchedAt));
			for (final Map.Entry<MovementWindow, PriceMap> entry : reprojected.entrySet())
			{
				submitWrite("saving the " + entry.getKey().name() + " baseline", () -> store.saveBucket(entry.getKey(), entry.getValue()));
			}
		}
		else
		{
			log.debug("bank-portfolio-tracker: the mapping failed, keeping the previous table: {}", describe(error));
		}
		scheduleRecompute();
	}

	/**
	 * Under the lock. Adopts a mapping, rebuilds the L8 b owner index and re-projects R0 and every baseline
	 * whose table is in memory (a baseline read off the disk has no table to re-project and keeps its points).
	 * Used by {@link #start()} for the disk copy and by {@link #finishMapping} for a fetched one.
	 *
	 * @return the windows whose baseline was re-projected, for the caller to persist
	 */
	private EnumSet<MovementWindow> adoptMappingLocked(final Map<Integer, String> names, final long fetchedAt)
	{
		mapping = names;
		owners = ownersOf(names);
		// Folded here, once per adopted mapping: every projection below and every agreement test afterwards reads
		// a table by these keys, so nothing downstream ever folds a wiki name again.
		foldedNames = foldedNamesOf(names);
		mappingAtMillis = fetchedAt;
		final List<BankItem> items = tradeableItemsOf(bank, options);
		if (!r0.isEmpty())
		{
			r0Map = project(r0, foldedNames, owners, items);
		}
		final EnumSet<MovementWindow> reprojected = EnumSet.noneOf(MovementWindow.class);
		for (final MovementWindow window : MovementWindow.values())
		{
			final GuideSnapshot table = tablesByRev.get(baselines.get(window).revId());
			if (table != null)
			{
				baselines.put(window, project(table, foldedNames, owners, items));
				reprojected.add(window);
			}
		}
		return reprojected;
	}

	// ---------------------------------------------------------------- T2: the traded feeds

	/**
	 * Executor. The {@code /latest} snapshot, one request in flight at a time and only while the switch is on and
	 * a traded client exists (T2). Silent when either is false: "off = not one traded request, exactly today".
	 */
	private void startLatest(final long now, final String reason)
	{
		final TradedPriceClient client;
		synchronized (lock)
		{
			if (stopped || traded == null || !options.livePrices())
			{
				return;
			}
			if (latestInFlight)
			{
				log.debug("bank-portfolio-tracker: traded /latest skipped ({}): a request is in flight", reason);
				return;
			}
			latestInFlight = true;
			client = traded;
		}
		log.debug("bank-portfolio-tracker: GET the traded /latest snapshot ({})", reason);
		start(() -> client.fetchLatest(now), (quotes, error) -> finishLatest(now, quotes, error), "fetchLatest");
	}

	/**
	 * Any thread: {@link #startLatest} only when there is nothing usable in memory - the start-up case and the
	 * switch-turned-on case, where a stored snapshot inside its six hours is already the right answer and a
	 * request would be waste.
	 */
	private void requestLatestIfLive(final long now, final String reason)
	{
		execute(() ->
		{
			synchronized (lock)
			{
				if (stopped || traded == null || !options.livePrices() || latestInFlight || !latestStaleLocked(now))
				{
					return;
				}
			}
			startLatest(now, reason);
		});
	}

	/**
	 * Executor: the {@code /latest} outcome. Success adopts and persists the snapshot and clears the failure
	 * flag; a failure sets it and keeps whatever is stored, which stays in use until it is six hours old (T7) and
	 * then takes every row back to the guide price with {@link #LIVE_UNAVAILABLE} on the card.
	 *
	 * <p>No retry is armed and no problem sentence is written. The traded feed is an enhancement over a series the
	 * plugin has in hand either way, so a failure costs freshness rather than function - and the next tick asks
	 * again half an hour later, which is the cadence the feed is read at anyway.
	 */
	private void finishLatest(final long fetchedAt, @Nullable final Map<Integer, TradedPriceClient.Quote> quotes,
		@Nullable final Throwable error)
	{
		final boolean ok = error == null && quotes != null && !quotes.isEmpty();
		final Map<Integer, TradedPriceClient.Quote> adopted;
		synchronized (lock)
		{
			latestInFlight = false;
			if (stopped)
			{
				return;
			}
			if (ok)
			{
				adopted = Collections.unmodifiableMap(new LinkedHashMap<>(quotes));
				latest = adopted;
				latestAtMillis = fetchedAt;
				liveFailed = false;
			}
			else
			{
				adopted = null;
				liveFailed = true;
			}
		}
		if (ok)
		{
			log.debug("bank-portfolio-tracker: the traded /latest snapshot holds {} items", adopted.size());
			submitWrite("saving " + PriceStore.TRADED_LATEST_FILE, () -> store.saveTradedLatest(adopted, fetchedAt));
			// U1: this snapshot's own UTC date IS the live day, so the buckets every window counts back to are
			// decided here - not after the guide baselines, which is where addendum T asked for them and where the
			// guide's late anchor day got into the live series. A day that is already in hand asks for nothing.
			reconcileTraded(fetchedAt, "the live snapshot", false);
		}
		else
		{
			log.debug("bank-portfolio-tracker: the traded /latest fetch failed, keeping the stored snapshot: {}",
				describe(error));
		}
		scheduleRecompute();
	}

	/**
	 * Executor. Each window's traded daily bucket, for the day the LIVE calendar says it is of (addendum U, line
	 * U1): {@code liveDay - N}, where {@code liveDay} is the UTC date of the {@code /latest} snapshot in hand.
	 *
	 * <p><b>What changed at addendum U.</b> Addendum T asked for the day that window's GUIDE BASELINE holds, so
	 * that both series of a window named one day. That was wrong for the live series and by up to a whole day: the
	 * guide's anchor is the newest day JAGEX has published a table for, so while the day's table was unpublished
	 * the 1d bucket was fetched for the day before yesterday and a one-day row printed a two-day move (+30 % on a
	 * Partyhat set, live look 5). The live series has its own "now" - the snapshot's clock - and counts back from
	 * that; the guide's days are untouched, and the two are simply allowed to differ (U3 prints both).
	 *
	 * <p>Four things stop a request, and each is one sentence of the addendum: the switch is off or no client was
	 * given (T2); no {@code /latest} snapshot has landed, so there is no live day to count back from and no live
	 * row to serve either; the wanted day is already the day that window's bucket records, or is already in memory
	 * under another window; or it has already been asked for this live day (Refresh clears that). The running day
	 * is never asked for by construction - every window is at least one day back. The hidden rule is the guide
	 * path's, for the same reason: while the sidebar is hidden the timers are the thing being stopped, not the user.
	 */
	private void reconcileTraded(final long now, final String reason, final boolean manual)
	{
		final Map<MovementWindow, LocalDate> want = new EnumMap<>(MovementWindow.class);
		synchronized (lock)
		{
			if (stopped || traded == null || !options.livePrices() || !(visible || manual))
			{
				return;
			}
			final LocalDate liveDay = utcDay(latestAtMillis);
			if (liveDay == null)
			{
				return;
			}
			for (final MovementWindow window : MovementWindow.values())
			{
				final LocalDate day = wantedTradedDay(liveDay, window);
				if (day.equals(tradedDays.get(window).day()) || day.equals(tradedAsked.get(window))
					|| tradedInFlight.contains(window))
				{
					continue;
				}
				// One fetch can answer two windows whenever their wanted days coincide (they cannot today - 1, 7,
				// 30, 90 and 180 days back are five distinct dates - but a bucket already in memory is still the
				// right answer, and asking the wiki again for a day it just served is not).
				final PriceStore.TradedDay held = tradedDayInMemoryLocked(day);
				if (held != null)
				{
					tradedDays.put(window, held);
					continue;
				}
				tradedAsked.put(window, day);
				tradedInFlight.add(window);
				want.put(window, day);
			}
		}
		for (final Map.Entry<MovementWindow, LocalDate> entry : want.entrySet())
		{
			startTradedDay(entry.getKey(), entry.getValue(), now, reason, true);
		}
	}

	/** Under the lock. A bucket already in memory for this day, whichever window fetched it; null when none is. */
	@Nullable
	private PriceStore.TradedDay tradedDayInMemoryLocked(final LocalDate day)
	{
		for (final PriceStore.TradedDay held : tradedDays.values())
		{
			if (!held.isEmpty() && day.equals(held.day()))
			{
				return held;
			}
		}
		return null;
	}

	/**
	 * Executor. One window's bucket request; the caller claimed {@link #tradedInFlight} under the lock and leaves
	 * it claimed until the answer is in.
	 *
	 * @param retryOneDayBack whether an EMPTY answer may fall one day further back (U2). True for the wanted day,
	 *                        false for the fallback itself, which is what bounds the walk to one step
	 */
	private void startTradedDay(final MovementWindow window, final LocalDate day, final long now, final String reason,
		final boolean retryOneDayBack)
	{
		final TradedPriceClient client;
		synchronized (lock)
		{
			client = traded;
			if (stopped || client == null)
			{
				tradedInFlight.remove(window);
				return;
			}
		}
		log.debug("bank-portfolio-tracker: GET the traded bucket of {} for {} ({})", day, window.name(), reason);
		start(() -> client.fetchDay(day, now),
			(buckets, error) -> finishTradedDay(window, day, now, buckets, error, reason, retryOneDayBack),
			"fetchDay");
	}

	/**
	 * Executor: one window's bucket outcome. Success adopts and persists it under the day it really holds; a
	 * failure keeps whatever that window held and leaves it on the guide series for every row, which is T7 exactly
	 * ("no red - the tooltip says so"). The wanted day stays in {@link #tradedAsked}, so it is not asked for again
	 * until the live day moves or the user presses Refresh.
	 *
	 * <p><b>U2, the day not yet closed.</b> An answer that is EMPTY rather than failed is the wiki saying it has
	 * not cut that day's bucket yet - possible in the first hours after UTC midnight, when {@code liveDay - 1} is
	 * yesterday by the clock but still today's unfinished bucket to the wiki. That case, and only that case, asks
	 * ONCE more for the day before it, and whatever comes back is recorded under ITS own day so every reader is
	 * told which day the row really compared against. An empty answer to the fallback leaves the window on the
	 * guide, which is T4's own rule.
	 */
	private void finishTradedDay(final MovementWindow window, final LocalDate day, final long fetchedAt,
		@Nullable final Map<Integer, TradedPriceClient.Bucket> buckets, @Nullable final Throwable error,
		final String reason, final boolean retryOneDayBack)
	{
		final boolean empty = error == null && (buckets == null || buckets.isEmpty());
		final boolean ok = error == null && !empty;
		final boolean fallback = empty && retryOneDayBack;
		final PriceStore.TradedDay adopted;
		synchronized (lock)
		{
			if (!fallback)
			{
				tradedInFlight.remove(window);
			}
			if (stopped)
			{
				tradedInFlight.remove(window);
				return;
			}
			adopted = ok ? new PriceStore.TradedDay(day, buckets, fetchedAt) : null;
			if (adopted != null)
			{
				tradedDays.put(window, adopted);
			}
		}
		if (adopted != null)
		{
			log.debug("bank-portfolio-tracker: the {} traded bucket of {} holds {} items", window.name(), day,
				adopted.buckets().size());
			submitWrite("saving the " + window.name() + " traded bucket",
				() -> store.saveTradedDay(window, day, adopted.buckets(), fetchedAt));
		}
		else if (fallback)
		{
			log.debug("bank-portfolio-tracker: the wiki has not closed {} yet, asking {} for the day before it",
				day, window.name());
			startTradedDay(window, day.minusDays(1), fetchedAt, reason, false);
			return;
		}
		else if (empty)
		{
			log.debug("bank-portfolio-tracker: the wiki has no traded bucket for {} yet, {} stays on the guide",
				day, window.name());
		}
		else
		{
			log.debug("bank-portfolio-tracker: the {} traded bucket of {} failed, that window stays on the guide: {}",
				window.name(), day, describe(error));
		}
		scheduleRecompute();
	}

	// ---------------------------------------------------------------- pure helpers (L3, L5, L8) - tested on their own

	/**
	 * The anchor day D of L3, from the agreement between RuneLite's prices and the newest guide table R0:
	 * <ul>
	 * <li>{@code r0Day == null} (no R0 in memory): null - nothing can be derived, no baseline is picked;</li>
	 * <li>{@code n < AGREE_MIN_SAMPLES}: {@code r0Day} - too few items compare to tell the two tables apart; the
	 * caller also uses R0 as "now" for the rows;</li>
	 * <li>{@code matches / n >= AGREE_THRESHOLD}: {@code r0Day} - RuneLite holds the same Jagex day as R0;</li>
	 * <li>otherwise {@code r0Day.plusDays(1)} - RuneLite already holds the NEXT Jagex day (the state between the
	 * Jagex rollover and the bot's run, roughly half of every day, L-D).</li>
	 * </ul>
	 * Measured separation: 100 % agreement on the same day against about 41 % one day apart, so the threshold
	 * is nowhere near either (L3). Integer arithmetic ({@code matches * 10 >= n * 9}) so 18 of 20 is exactly the
	 * threshold and no floating-point rounding can move it. Never the wall clock, never a rollover hour (L9).
	 *
	 * <p><b>This rule only knows one direction.</b> A disagreement means RuneLite is not on R0's day; L3 measured
	 * only the case the wiki's bot creates, where RuneLite is a day AHEAD, so that is what the last line answers.
	 * RuneLite can also be BEHIND (its price table is loaded once and refreshed every 30 minutes, so an outage
	 * strands a long session on an older day), and then {@code r0Day + 1} is two days wrong. {@link #finish}
	 * therefore checks the other direction before it takes this answer, against a table already in memory
	 * ({@link #agrees}); this method stays the pure rule the tests pin.
	 *
	 * @param matches items whose RuneLite price equals their R0 value
	 * @param n       items with both a RuneLite price (> 0) and an R0 value
	 * @param r0Day   R0's own {@code %LAST_UPDATE%} day (UTC), or null
	 */
	static LocalDate deriveAnchorDay(final int matches, final int n, @Nullable final LocalDate r0Day)
	{
		if (r0Day == null)
		{
			return null;
		}
		if (n < AGREE_MIN_SAMPLES)
		{
			return r0Day;
		}
		return (long) matches * 10L >= (long) n * 9L ? r0Day : r0Day.plusDays(1);
	}

	/**
	 * The revision a window's baseline should be read from (L5): {@code RevisionRef.pickThen(index,
	 * window.targetDate(anchorDay))}. Null when there is no anchor day, no window, or nothing in the index is as
	 * old as the target (L11: "No 180d history"). Pure; the L5 day check and retry are {@link #resolveLocked}'s.
	 */
	@Nullable
	static RevisionRef pickBaseline(@Nullable final List<RevisionRef> index, @Nullable final LocalDate anchorDay,
		@Nullable final MovementWindow window)
	{
		if (window == null)
		{
			return null;
		}
		return RevisionRef.pickThen(index, window.targetDate(anchorDay));
	}

	/**
	 * The join of L8: a name-keyed guide revision onto item ids. Every mapping entry whose wiki name the table
	 * lists becomes a point (about 4,500 ids, so the persisted file serves ANY bank after a relaunch - L8 a),
	 * and every bank item the mapping does not cover is tried by its own name - the composition's members name
	 * as {@code BankReader} captured it - and REFUSED when that name belongs to a different id in the mapping
	 * (L8 b: the composition-name fallback returns another item's price for 287 of 4,662 ids, "X (q)" against
	 * "X", one of them in the user's own bank - L-F). A guide price is one number, so the point carries it on
	 * both sides and {@code PricePoint.mid()} returns it unchanged. The map is stamped with the table's own day
	 * marker as {@code bucketSeconds} (L7), its revision id, and the fetch time.
	 *
	 * @param table       the revision; must not be {@link GuideSnapshot#EMPTY}
	 * @param foldedNames {@link #foldedNamesOf(Map)} of the mapping - item id to the table key its wiki name folds
	 *                    to, possibly empty; folded once when the mapping was adopted rather than here, so a
	 *                    projection folds nothing at all
	 * @param owners      {@link #ownersOf(Map)} of that same mapping
	 * @param items       the bank at projection time, possibly empty
	 */
	static PriceMap project(final GuideSnapshot table, final Map<Integer, String> foldedNames,
		final Map<String, Integer> owners, final List<BankItem> items)
	{
		final Map<String, Long> prices = table.pricesByName();
		final Map<Integer, PricePoint> points = new HashMap<>();
		for (final Map.Entry<Integer, String> entry : foldedNames.entrySet())
		{
			final Long gp = prices.get(entry.getValue());
			if (entry.getKey() != null && gp != null)
			{
				points.put(entry.getKey(), guidePoint(gp));
			}
		}
		for (final BankItem item : items)
		{
			if (item == null || points.containsKey(item.id))
			{
				continue;
			}
			final Long gp = fallbackGp(table, item.name, item.id, owners);
			if (gp != null)
			{
				points.put(item.id, guidePoint(gp));
			}
		}
		return new PriceMap(points, table.fetchedAtMillis(), table.dataSeconds(), table.revId());
	}

	/**
	 * The reverse of the mapping for the L8 b refusal: folded wiki name to the id that owns it. The mapping has
	 * zero id-to-name collisions (L-F), so a second id folding onto a taken key is unexpected - the first keeps
	 * the key and the clash is logged, never thrown. Folded through {@code GuideSnapshot.key}, the one rule.
	 */
	static Map<String, Integer> ownersOf(@Nullable final Map<Integer, String> mapping)
	{
		if (mapping == null || mapping.isEmpty())
		{
			return Collections.emptyMap();
		}
		final Map<String, Integer> owners = new HashMap<>(mapping.size() * 2);
		for (final Map.Entry<Integer, String> entry : mapping.entrySet())
		{
			final String key = GuideSnapshot.key(entry.getValue());
			if (entry.getKey() == null || key == null || key.isEmpty())
			{
				continue;
			}
			final Integer clash = owners.putIfAbsent(key, entry.getKey());
			if (clash != null && !clash.equals(entry.getKey()))
			{
				log.debug("bank-portfolio-tracker: the mapping names \"{}\" for both {} and {} (kept {})", key, clash, entry.getKey(), clash);
			}
		}
		return Collections.unmodifiableMap(owners);
	}

	/**
	 * The mapping with its names already folded through {@link GuideSnapshot#key}: id to the key a guide table is
	 * actually looked up by. Built ONCE when a mapping is adopted, because a table's map is keyed by that same
	 * folded form - so a projection over 4,500 names, and every agreement test over a bank, can read the price
	 * straight out of {@link GuideSnapshot#pricesByName()} instead of folding the same 4,500 strings again.
	 *
	 * <p>An entry whose name folds to nothing is dropped, exactly as {@link #ownersOf} drops it: the table cannot
	 * hold an empty key either ({@code GuideSnapshot}'s constructor drops those), so such an entry could never
	 * have matched a price.
	 */
	static Map<Integer, String> foldedNamesOf(@Nullable final Map<Integer, String> mapping)
	{
		if (mapping == null || mapping.isEmpty())
		{
			return Collections.emptyMap();
		}
		final Map<Integer, String> folded = new HashMap<>(mapping.size() * 2);
		for (final Map.Entry<Integer, String> entry : mapping.entrySet())
		{
			final String key = GuideSnapshot.key(entry.getValue());
			if (entry.getKey() == null || key == null || key.isEmpty())
			{
				continue;
			}
			folded.put(entry.getKey(), key);
		}
		return Collections.unmodifiableMap(folded);
	}

	/**
	 * The L8 b fallback for one item: its composition name against a table, unless that name is another id's
	 * wiki name - in which case the price the table holds is that other item's, and null is the right answer.
	 *
	 * <p>The name is folded ONCE and the folded key used for both lookups: {@code owners} is keyed by
	 * {@link GuideSnapshot#key} output and so is a table's own map, so asking {@code table.get(name)} afterwards
	 * would only fold the same string again (an NFC normalise, a regex and a lower-case, per item per window).
	 */
	@Nullable
	static Long fallbackGp(@Nullable final GuideSnapshot table, @Nullable final String name, final int id,
		final Map<String, Integer> owners)
	{
		if (table == null || table.isEmpty() || name == null || name.isEmpty())
		{
			return null;
		}
		final String folded = GuideSnapshot.key(name);
		final Integer owner = owners.get(folded);
		if (owner != null && owner != id)
		{
			return null;
		}
		return table.pricesByName().get(folded);
	}

	// ---------------------------------------------------------------- row computation (L1, L3, L8)

	/** Any thread: take a generation number and compute on the executor. */
	private void scheduleRecompute()
	{
		final long generation;
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			generation = ++computeGeneration;
		}
		execute(() -> compute(generation));
	}

	/**
	 * Executor. Snapshots the inputs, then either finishes at once (no items) or hops to the client thread for
	 * the guide prices (class javadoc) and comes back to the executor to finish. The members name is read only
	 * for an item the mapping does not cover while a table is in memory that might name it: that is the one
	 * case the L8 b fallback can still change, and it keeps the hop to one definition lookup per such item.
	 */
	private void compute(final long generation)
	{
		final Inputs in;
		// Read before the lock, and used for T3's freshness half and T7's staleness alike, so one computation
		// cannot judge a quote fresh and the snapshot it came from stale.
		final long nowMillis = clockMillis.getAsLong();
		synchronized (lock)
		{
			if (stopped || generation != computeGeneration)
			{
				return;
			}
			// Every window's baseline and the table behind it (M3), by reference: both are immutable.
			final Map<MovementWindow, PriceMap> snapshotBaselines = new EnumMap<>(MovementWindow.class);
			final Map<MovementWindow, GuideSnapshot> snapshotTables = new EnumMap<>(MovementWindow.class);
			for (final MovementWindow window : MovementWindow.values())
			{
				final PriceMap baseline = baselines.get(window);
				final GuideSnapshot table = tablesByRev.get(baseline.revId());
				snapshotBaselines.put(window, baseline);
				snapshotTables.put(window, table == null ? GuideSnapshot.EMPTY : table);
			}
			// Every table in memory (B002), by reference and in insertion order: a handful of immutable entries.
			// Which one belongs to which DAY is worked out only on the branch that asks (Inputs.tableForDay).
			final List<GuideSnapshot> inMemory = new ArrayList<>(tablesByRev.values());
			// T2/T4/T7/U1: the traded half, decided here once. The quotes are EMPTY unless the switch is on, a
			// client exists and the snapshot is inside its six hours; a window's bucket rides along only when the
			// day it RECORDS is the day the live calendar wants for it (or U2's one day further back), so nothing
			// downstream has to check either rule again.
			final boolean liveOn = options.livePrices() && traded != null;
			final Map<Integer, TradedPriceClient.Quote> snapshotQuotes =
				liveOn && !latestStaleLocked(nowMillis) ? latest : Collections.<Integer, TradedPriceClient.Quote>emptyMap();
			// The live day of the snapshot IN USE, not of the clock: the rows' "now" came from that fetch, so its
			// own UTC date is the only day their "then" may be counted back from (U1).
			final LocalDate liveDay = snapshotQuotes.isEmpty() ? null : utcDay(latestAtMillis);
			final Map<MovementWindow, PriceStore.TradedDay> snapshotTraded = new EnumMap<>(MovementWindow.class);
			if (liveDay != null)
			{
				for (final MovementWindow window : MovementWindow.values())
				{
					final PriceStore.TradedDay day = tradedDays.get(window);
					if (!day.isEmpty() && tradedDayUsable(wantedTradedDay(liveDay, window), day.day()))
					{
						snapshotTraded.put(window, day);
					}
				}
			}
			// Q4/Q5/Y3: the three switches that change the FIGURES are applied here, once - the stacks the
			// computation may see (the bank's, and what the player carries and wears when Y3's switch is on, folded
			// into one list), and the cash it may count. Nothing downstream asks about them again.
			in = new Inputs(stacksOf(bank, options), cashOf(bank, options),
				filter, options, snapshotBaselines, snapshotTables, inMemory, r0, r0Map, mapping, foldedNames, owners,
				snapshotQuotes, snapshotTraded, liveDay, liveOn, liveFailed, nowMillis / 1000L);
		}
		if (in.items.isEmpty())
		{
			finish(generation, in, null, null, null, Parts.EMPTY);
			return;
		}
		final boolean anyTable = in.anyTable();
		final boolean[] needsName = new boolean[in.items.size()];
		final boolean[] rewritten = new boolean[in.items.size()];
		for (int i = 0; i < needsName.length; i++)
		{
			final BankItem item = in.items.get(i);
			// An untradeable stack (Q5) has no guide price and no baseline of its own, so there is nothing to look
			// up for it here: no guide price, no wiki name, and no place in the agreement sample below. Since
			// addendum R its PARTS are looked up instead (below), which is a different set of ids.
			final boolean priceable = item != null && !item.untradeable;
			needsName[i] = priceable && anyTable && !in.mapping.containsKey(item.id);
			// Needs no game state, so it is settled here rather than on the client thread's hop.
			rewritten[i] = priceable && rewrittenByItemMapping(item.id);
		}
		// R2: the tradeable parts of every untradeable stack this computation covers, priced by exactly the same
		// per-id rules - RuneLite's table, or the newest guide table for an id RuneLite rewrites (B001).
		final Parts parts = Parts.of(in.items);
		for (int i = 0; i < parts.ids.length; i++)
		{
			parts.rewritten[i] = rewrittenByItemMapping(parts.ids[i]);
		}
		clientThread.invoke(() ->
		{
			if (superseded(generation))
			{
				return;
			}
			final int[] guide = new int[in.items.size()];
			final String[] names = new String[in.items.size()];
			for (int i = 0; i < guide.length; i++)
			{
				final BankItem item = in.items.get(i);
				// An untradeable stack is never asked for either (Q5): RuneLite has no guide price for an item the
				// exchange does not list, and leaving its price at 0 keeps it out of the L3 agreement sample, where
				// it could only add noise to the day the prices are dated by.
				if (item == null || item.untradeable)
				{
					continue;
				}
				// A rewritten id is never asked for: RuneLite would answer another item's price (class javadoc).
				guide[i] = rewritten[i] ? 0 : guidePrice(item.id);
				names[i] = needsName[i] ? membersName(item.id) : null;
			}
			for (int i = 0; i < parts.ids.length; i++)
			{
				parts.guide[i] = parts.rewritten[i] ? 0 : guidePrice(parts.ids[i]);
			}
			execute(() -> finish(generation, in, rewritten, guide, names, parts));
		});
	}

	/**
	 * Client thread only ({@code ItemManager.java:339} reads the composition first). L1: the {@code price}
	 * field, never the wiki toggle ({@code useWikiPrice = false}, :356). A composition the client cannot serve
	 * is not worth a failed computation: the item simply has no price.
	 *
	 * <p>The ONE call site is {@link #compute}, and it is skipped for an id
	 * {@link #rewrittenByItemMapping} answers true for - see the class javadoc's carve-out. Anything that wants
	 * "now" for such an id must take it from the guide table instead.
	 */
	private int guidePrice(final int id)
	{
		try
		{
			return itemManager.getItemPriceWithSource(id, false);
		}
		catch (final RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: no guide price for item {}", id, e);
			return 0;
		}
	}

	/**
	 * Whether {@code ItemManager.getItemPriceWithSource} would answer something OTHER than this item's own guide
	 * price - the carve-out on L1/K1 spelled out in the class javadoc. True exactly when
	 * {@code ItemMapping.map(id)} is non-null, which is the test {@code ItemManager.java:348} itself makes before
	 * it abandons its price table and sums the mapped tradeable components instead.
	 *
	 * <p>Pure, no game state: {@code ItemMapping}'s static initialiser only expands its own table through
	 * {@code ItemVariationMapping} (a classpath resource), so this is safe on any thread and in a headless test.
	 * A throw out of that initialisation is read as "not mapped", which is the pre-existing behaviour: the id
	 * then takes RuneLite's answer exactly as it did before.
	 */
	static boolean rewrittenByItemMapping(final int id)
	{
		try
		{
			return ItemMapping.map(id) != null;
		}
		catch (final RuntimeException | LinkageError e)
		{
			log.debug("bank-portfolio-tracker: ItemMapping could not be consulted for item {}", id, e);
			return false;
		}
	}

	/**
	 * Client thread only. {@code ItemComposition.getMembersName()} ({@code ItemComposition.java:43-49}): the
	 * name without the " (Members)" a free world appends to {@code getName()}, which is what the guide table is
	 * keyed by. Null when the client cannot serve the composition.
	 */
	@Nullable
	private String membersName(final int id)
	{
		try
		{
			final ItemComposition composition = itemManager.getItemComposition(id);
			return composition == null ? null : composition.getMembersName();
		}
		catch (final RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: no composition for item {}", id, e);
			return null;
		}
	}

	/**
	 * Executor: the rows and the anchor day, from one snapshot of inputs, in three steps.
	 *
	 * <p><b>The day</b> ({@link #agreement}, here): over every item with a RuneLite price and an R0 value, how many
	 * are equal. {@link #deriveAnchorDay} turns that into D, and {@link #agrees} checks the one direction that rule
	 * cannot see (B002). When fewer than {@value #AGREE_MIN_SAMPLES} items compare, the computation is DEGRADED and
	 * R0 is "now" (L3) - its value where it has one, RuneLite's price where it has none, so a priced item stays
	 * priced.
	 *
	 * <p><b>The rows and the bank value line</b> ({@link #rowsAndPortfolio}): each row is
	 * {@code MovementMath.row(item, now, baseline.get(id) or the L8 b fallback, 0)} - or, for an untradeable stack
	 * the switches admit, the same row over the SUM of its tradeable parts (R2) and failing that
	 * {@code MovementMath.alchRow(item)} (Q5) - and then
	 * {@code MovementMath.apply(rows, filter, options)}. The value line (M3) is built in the same pass from the same inputs -
	 * the "now" of every stack, every baseline in memory, the whole snapshot before the filter
	 * ({@link #summarise}) - so the two can never disagree about a price. An empty snapshot publishes
	 * {@link PortfolioSummary#EMPTY}, unless it holds cash (P1): coins are not a stack, so a bank of nothing but
	 * coins has no rows and still has a value.
	 *
	 * <p><b>The publish</b> ({@link #commit}): dropped when a newer computation exists; and if the anchor day
	 * changed, the baselines are re-picked at once (L3: "re-evaluated on every recompute, so the panel follows the
	 * Jagex rollover within one recompute").
	 *
	 * @param rewritten one flag per item: RuneLite's price for it is another item's ({@link #rewrittenByItemMapping});
	 *                  its "now" comes from R0 instead and it never enters the agreement sample. Null for an empty bank
	 * @param parts     the tradeable parts of the untradeable stacks and the guide prices read for them (R2);
	 *                  {@link Parts#EMPTY} when no stack names any
	 */
	private void finish(final long generation, final Inputs in, @Nullable final boolean[] rewritten,
		@Nullable final int[] guide, @Nullable final String[] names, final Parts parts)
	{
		final Agreement against = agreement(in, guide, names);
		final LocalDate r0Day = in.r0.dataDay();
		final LocalDate derived = in.items.isEmpty() ? null : deriveAnchorDay(against.matches, against.samples, r0Day);
		// The other direction (B002): deriveAnchorDay reads every disagreement as "RuneLite leads", so a table
		// that LAGS the wiki would be pushed a day the wrong way. Only the disagreement branch can be wrong that
		// way, and only a table in memory can settle it.
		final boolean behind = derived != null && r0Day != null && derived.isAfter(r0Day)
			&& agrees(in.tableForDay(r0Day.minusDays(1)), in, guide, names);
		final LocalDate anchor = behind ? r0Day.minusDays(1) : derived;
		final boolean degraded = !in.r0.isEmpty() && !in.items.isEmpty() && against.samples < AGREE_MIN_SAMPLES;

		commit(generation, in, rowsAndPortfolio(in, rewritten, guide, names, against.values, degraded, parts),
			anchor, against, degraded, behind);
	}

	/**
	 * How far RuneLite's prices agree with R0, and R0's value for each bank item on the way (L3). The value is the
	 * projected {@link Inputs#r0Map} first and the L8 name ladder second - the same ladder a row's "then" uses -
	 * and it is kept because {@link #rowsAndPortfolio} needs it as "now" in the degraded mode.
	 *
	 * <p>No R0 in memory means no value for anything and therefore no sample, which is what makes
	 * {@link #deriveAnchorDay} answer null and the rows fall back to RuneLite's own prices.
	 */
	private static Agreement agreement(final Inputs in, @Nullable final int[] guide, @Nullable final String[] names)
	{
		final int count = in.items.size();
		final Long[] values = new Long[count];
		if (in.r0.isEmpty())
		{
			return tally(values, guide);
		}
		for (int i = 0; i < count; i++)
		{
			final BankItem item = in.items.get(i);
			// Untradeable: no guide price to compare with, so no sample and no name lookup to pay for (Q5).
			if (item == null || item.untradeable)
			{
				continue;
			}
			final PricePoint point = in.r0Map.get(item.id);
			final Long value = point == null ? null : point.mid();
			values[i] = value != null ? value
				: fallbackThen(in.r0, names == null ? null : names[i], item.name, item.id, in.owners);
		}
		return tally(values, guide);
	}

	/**
	 * The L3 tally over one table's values: an item counts as a SAMPLE when RuneLite prices it and the table names
	 * it, and as a MATCH when the two are the same number. Shared by {@link #agreement} and {@link #agrees} so the
	 * question "does RuneLite hold this table's day?" is asked exactly one way whichever table is asked about.
	 *
	 * <p>An id {@link ItemMapping} rewrites carries price 0 here ({@link #compute} never asked RuneLite for it), so
	 * it drops out of the sample on its own. Deliberate: it would otherwise score a free match on every one of
	 * them - its "now" IS the table's value - and bias the anchor with items that cannot be compared at all.
	 */
	private static Agreement tally(final Long[] values, @Nullable final int[] guide)
	{
		int samples = 0;
		int matches = 0;
		for (int i = 0; i < values.length; i++)
		{
			final Long value = values[i];
			final int price = guide == null ? 0 : guide[i];
			if (price <= 0 || value == null)
			{
				continue;
			}
			samples++;
			if (value == price)
			{
				matches++;
			}
		}
		return new Agreement(values, samples, matches);
	}

	/**
	 * Every row, and the bank value line beside it (M3), from one snapshot of inputs: for each stack the guide
	 * "now" ({@code guide}, or R0 standing in - see below), the "then" its window's baseline resolves through the
	 * L8 ladder, then {@code MovementMath.row} and the filter. Pure: nothing here touches the service's state, so
	 * a computation that turns out to be superseded has changed nothing.
	 *
	 * @param r0Values R0's value per item from {@link #agreement}, the "now" of a degraded computation
	 * @param degraded fewer than {@value #AGREE_MIN_SAMPLES} items compared, so R0 IS "now" (L3)
	 * @param parts    the untradeable stacks' tradeable parts and their guide prices (R2)
	 */
	private static Computed rowsAndPortfolio(final Inputs in, @Nullable final boolean[] rewritten,
		@Nullable final int[] guide, @Nullable final String[] names, final Long[] r0Values, final boolean degraded,
		final Parts parts)
	{
		final int count = in.items.size();
		final List<MovementRow> all = new ArrayList<>(count);
		// T4: where a window's series differs from the headline's, for the bank value line. Only a live row ever
		// puts anything in either - with the switch off both stay empty and the portfolio reads exactly as before.
		final Map<MovementWindow, Map<Integer, Long>> windowNow = new EnumMap<>(MovementWindow.class);
		final Map<MovementWindow, Map<Integer, Long>> windowThen = new EnumMap<>(MovementWindow.class);
		int live = 0;
		int alch = 0;
		// The portfolio's inputs (M3), gathered as the rows are built: the one "now" per canonical id the rows
		// carry, and the names the L8 ladder resolves a "then" from, so every window's move is computed off exactly
		// what the rows were.
		final Map<Integer, Long> nowById = new HashMap<>(count * 2);
		final Map<Integer, String> membersNames = new HashMap<>();
		final Map<Integer, String> bankNames = new HashMap<>(count * 2);
		// R2/R3: the stacks that came out PARTS-priced, so the portfolio resolves their "then" the same way the
		// rows just did - as a sum over the parts, never as a lookup of the untradeable id itself.
		final Map<Integer, List<BankItem.Part>> partsById = new HashMap<>();
		final Map<Integer, Long> partNow = partsNow(in, parts, degraded);
		// T3, for the parts of every untradeable stack: which of them the traded feeds may price, and the first
		// check that refused the rest. A parts stack is live only when EVERY part is.
		final Map<Integer, Long> partLive = new HashMap<>();
		final Map<Integer, String> partRefusal = new HashMap<>();
		partsLive(in, parts, partNow, partLive, partRefusal);
		final PriceMap baseline = in.baseline();
		final GuideSnapshot table = in.table();
		final MovementWindow current = in.filter.window();
		int priced = 0;
		for (int i = 0; i < count; i++)
		{
			final BankItem item = in.items.get(i);
			if (item == null)
			{
				continue;
			}
			// R2: a stack the exchange does not list but RuneLite maps onto tradeable parts is worth the sum of
			// those parts - a real guide price, with a real baseline and a real move - so it is built as an ordinary
			// row over the two sums and then says where the price came from. Only a stack with no mapping, or one
			// whose parts cannot ALL be priced, keeps Q5's alch rule below: half a sum is not the item's price.
			if (item.untradeable && item.hasParts())
			{
				final Long unit = partsSum(item.parts, partNow::get);
				if (unit != null)
				{
					final Long partsThen = partsSum(item.parts,
						id -> thenPrice(baseline, table, id, parts.names.get(id), parts.names.get(id), in.owners));
					// T3: the whole stack is live only when every part is - the parts ARE the market for it, and a
					// sum of one live price and one guide price is neither series.
					final Long liveUnit = in.liveUsable() ? partsSum(item.parts, partLive::get) : null;
					final MovementRow.LiveFacts facts = in.liveUsable()
						? partsFacts(in, item.parts, liveUnit, partRefusal) : null;
					MovementRow row = MovementMath
						.row(item, guidePoint(unit), partsThen == null ? null : guidePoint(partsThen), 0)
						.asParts(item.parts);
					if (liveUnit != null)
					{
						final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
						final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
						final Long tradedThen = partsWindows(in, item.id, item.parts, unit, sources, days,
							windowNow, windowThen, current);
						row = rebuildLive(item, row, liveUnit, tradedThen)
							.asParts(item.parts)
							.asLive(liveUnit, sources, days, facts);
						live++;
					}
					else
					{
						row = row.withLiveRefusal(facts);
					}
					all.add(in.withSplit(row));
					// Priced: every gp in that sum came out of the guide table, which is what the word means here
					// and what the header's GUIDE source and its clock are about (R3).
					priced++;
					nowById.put(item.id, liveUnit != null ? liveUnit : unit);
					partsById.put(item.id, item.parts);
					continue;
				}
			}
			// Q5: an untradeable stack with no parts value is its own little row - the alch value, no baseline, no
			// move - and it takes no part in anything below it. It is not counted as PRICED either: that word means
			// "the guide table has a price for it", and an alch value is a constant of the item.
			if (item.untradeable)
			{
				all.add(in.withSplit(MovementMath.alchRow(item)));
				alch++;
				continue;
			}
			final int price = guide == null ? 0 : guide[i];
			// R0 stands in as "now" in L3's degraded mode, and for an id RuneLite would answer another item's
			// price for (the class javadoc's carve-out) - which leaves the row unpriced when R0 cannot name it.
			final boolean fromR0 = degraded || (rewritten != null && rewritten[i]);
			final Long nowGp = fromR0 && r0Values[i] != null ? r0Values[i] : (price > 0 ? Long.valueOf(price) : null);
			final String membersName = names == null ? null : names[i];
			final PricePoint now = nowGp == null ? null : guidePoint(nowGp);
			final Long thenGp = thenPrice(baseline, table, item.id, membersName, item.name, in.owners);
			final PricePoint then = thenGp == null ? null : guidePoint(thenGp);
			// T3 and V3/V4: the five checks, asked once per stack, in the one place that spells them.
			final MovementRow.LiveFacts facts = in.liveUsable() ? facts(in, item.id, nowGp) : null;
			final Long liveMid = facts != null && facts.live() ? in.quotes.get(item.id).mid() : null;
			MovementRow row;
			if (liveMid != null)
			{
				final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
				final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
				// T4/U3: every window in one pass - which series it uses, which DAY it compared against, and, where
				// that differs from the headline, the pair the bank value line is to compare for it.
				final Long tradedThen = windows(in, item.id, nowGp, sources, days, windowNow, windowThen, current);
				// The CURRENT window decides the figures on the row: the traded pair when that window is live,
				// the guide pair when it fell back - and the live mid is printed either way (T4, asLive).
				row = (tradedThen != null
					? MovementMath.row(item, guidePoint(liveMid), guidePoint(tradedThen), 0)
					: MovementMath.row(item, now, then, 0))
					.asLive(liveMid, sources, days, facts);
				live++;
			}
			else
			{
				row = MovementMath.row(item, now, then, 0).withLiveRefusal(facts);
			}
			if (row.unitPrice() != null)
			{
				priced++;
			}
			all.add(in.withSplit(row));
			// T5: the headline counts each stack at the price its own row prints - the live mid for a live stack.
			final Long ownNow = liveMid != null ? liveMid : nowGp;
			if (ownNow != null)
			{
				nowById.put(item.id, ownNow);
			}
			if (membersName != null)
			{
				membersNames.put(item.id, membersName);
			}
			bankNames.put(item.id, item.name);
		}
		// EMPTY only when there is genuinely nothing: a bank of nothing but coins has a value, and P1 puts it on the
		// card (the stacks are what EMPTY is about, and cash is not a stack).
		final PortfolioSummary summary = count == 0 && in.currencyGp <= 0L
			? PortfolioSummary.EMPTY
			: summarise(in, nowById, membersNames, bankNames, partsById, parts.names, windowNow, windowThen, live);
		return new Computed(MovementMath.apply(all, in.filter), summary, priced, live, alch);
	}

	/**
	 * T3 for one bank stack: the five checks against the feeds in hand, packed with the numbers they were made
	 * on so the tooltip can show either the live price or the reason it is not used (T6). Never null while the
	 * switch is on and a usable snapshot is in hand - a row that is refused still says so.
	 */
	private static MovementRow.LiveFacts facts(final Inputs in, final int id, @Nullable final Long guideNow)
	{
		final TradedPriceClient.Quote quote = in.quotes.get(id);
		final TradedPriceClient.Bucket yesterday = in.bucketYesterday(id);
		final String reason = liveRefusal(quote, yesterday, guideNow, in.nowSeconds);
		return new MovementRow.LiveFacts(quote == null ? null : quote.buy(), quote == null ? null : quote.sell(),
			yesterday == null ? 0L : yesterday.volume(), reason);
	}

	/**
	 * T4 for one LIVE stack, over EVERY window: records which series each window uses, fills the two per-window
	 * maps the bank value line reads where that window's pair differs from the headline's, and answers the
	 * CURRENT window's traded "then" (null when it fell back to the guide).
	 *
	 * <p>One pass rather than one per window per caller, because the row and the card must agree window by window:
	 * the sources map that ends up on the row and the pairs the portfolio sums are decided by the same test here.
	 */
	@Nullable
	private static Long windows(final Inputs in, final int id, @Nullable final Long guideNow,
		final Map<MovementWindow, MovementRow.PriceSource> sources, final Map<MovementWindow, LocalDate> days,
		final Map<MovementWindow, Map<Integer, Long>> windowNow, final Map<MovementWindow, Map<Integer, Long>> windowThen,
		final MovementWindow current)
	{
		Long currentThen = null;
		for (final MovementWindow window : MovementWindow.values())
		{
			final Long tradedThen = in.tradedThen(window, id);
			sources.put(window, tradedThen != null ? MovementRow.PriceSource.LIVE : MovementRow.PriceSource.GUIDE);
			// U3: the day this window really compared against - the bucket's own day when it is live, the guide
			// baseline's when it fell back - so the tooltip can print it instead of the header's one day.
			putDay(days, window, tradedThen != null ? in.tradedDayOf(window) : in.guideDayOf(window));
			if (tradedThen != null)
			{
				windowThen.computeIfAbsent(window, w -> new HashMap<>()).put(id, tradedThen);
			}
			else if (guideNow != null)
			{
				// The window fell back: it compares the GUIDE's two ends, so the card's "now" for it is the guide
				// price even though the row prints the live mid (T4, "never live-now against guide-then").
				windowNow.computeIfAbsent(window, w -> new HashMap<>()).put(id, guideNow);
			}
			if (window == current)
			{
				currentThen = tradedThen;
			}
		}
		return currentThen;
	}

	/** One day into the per-window map, null dropped: a window with no day is a window the row cannot date (U3). */
	private static void putDay(final Map<MovementWindow, LocalDate> days, final MovementWindow window,
		@Nullable final LocalDate day)
	{
		if (day != null)
		{
			days.put(window, day);
		}
	}

	/**
	 * {@link #windows} for a PARTS stack: a window is live for it only when EVERY part has a traded average that
	 * day, and its traded "then" is the sum of them - the same all-or-nothing rule R2 already applies to the guide
	 * side, for the same reason (half a sum is not the item's price).
	 */
	@Nullable
	private static Long partsWindows(final Inputs in, final int id, final List<BankItem.Part> itemParts,
		@Nullable final Long guideNow, final Map<MovementWindow, MovementRow.PriceSource> sources,
		final Map<MovementWindow, LocalDate> days,
		final Map<MovementWindow, Map<Integer, Long>> windowNow, final Map<MovementWindow, Map<Integer, Long>> windowThen,
		final MovementWindow current)
	{
		Long currentThen = null;
		for (final MovementWindow window : MovementWindow.values())
		{
			final Long tradedThen = partsSum(itemParts, partId -> in.tradedThen(window, partId));
			sources.put(window, tradedThen != null ? MovementRow.PriceSource.LIVE : MovementRow.PriceSource.GUIDE);
			putDay(days, window, tradedThen != null ? in.tradedDayOf(window) : in.guideDayOf(window));
			if (tradedThen != null)
			{
				windowThen.computeIfAbsent(window, w -> new HashMap<>()).put(id, tradedThen);
			}
			else if (guideNow != null)
			{
				windowNow.computeIfAbsent(window, w -> new HashMap<>()).put(id, guideNow);
			}
			if (window == current)
			{
				currentThen = tradedThen;
			}
		}
		return currentThen;
	}

	/**
	 * The row a LIVE parts stack is built from: the traded pair when the current window has one, the guide pair
	 * when it fell back. Separate from the tradeable branch above only because a parts row's two ends are sums
	 * rather than lookups; the rule is the same one, and {@code asLive} puts the live unit on either.
	 */
	private static MovementRow rebuildLive(final BankItem item, final MovementRow guideRow, final Long liveUnit,
		@Nullable final Long tradedThen)
	{
		if (tradedThen == null)
		{
			return guideRow;
		}
		return MovementMath.row(item, guidePoint(liveUnit), guidePoint(tradedThen), 0);
	}

	/**
	 * T3 for every tradeable PART of the untradeable stacks (R2): the parts that pass all FIVE checks, by id and
	 * at their live mid, and the first failing check for the rest. Two maps rather than one nullable value because
	 * {@link #partsSum} reads "no price" as a null lookup, which is exactly the all-or-nothing rule wanted here.
	 */
	private static void partsLive(final Inputs in, final Parts parts, final Map<Integer, Long> partNow,
		final Map<Integer, Long> intoLive, final Map<Integer, String> intoRefusal)
	{
		if (!in.liveUsable() || parts.isEmpty())
		{
			return;
		}
		for (final int id : parts.ids)
		{
			final TradedPriceClient.Quote quote = in.quotes.get(id);
			final String reason = liveRefusal(quote, in.bucketYesterday(id), partNow.get(id), in.nowSeconds);
			if (reason == null)
			{
				intoLive.put(id, quote.mid());
			}
			else
			{
				intoRefusal.put(id, reason);
			}
		}
	}

	/**
	 * The live facts of a PARTS stack (T6). The two sides are the parts' own sides summed the way the price is -
	 * so "buy" really is what buying the parts costs - and the volume is the SMALLEST of the parts', because that
	 * is the one that had to clear {@value #LIVE_MIN_VOLUME} for the stack to qualify at all. A refused stack
	 * carries the first failing part's reason, in the stack's own part order.
	 */
	private static MovementRow.LiveFacts partsFacts(final Inputs in, final List<BankItem.Part> itemParts,
		@Nullable final Long liveUnit, final Map<Integer, String> partRefusal)
	{
		if (liveUnit == null)
		{
			String reason = LIVE_NO_DATA;
			for (final BankItem.Part part : itemParts)
			{
				final String refused = part == null ? null : partRefusal.get(part.id);
				if (refused != null)
				{
					reason = refused;
					break;
				}
			}
			return new MovementRow.LiveFacts(null, null, 0L, reason);
		}
		final Long buy = partsSum(itemParts, id ->
		{
			final TradedPriceClient.Quote quote = in.quotes.get(id);
			return quote == null ? null : quote.buy();
		});
		final Long sell = partsSum(itemParts, id ->
		{
			final TradedPriceClient.Quote quote = in.quotes.get(id);
			return quote == null ? null : quote.sell();
		});
		long volume = Long.MAX_VALUE;
		for (final BankItem.Part part : itemParts)
		{
			final Long partVolume = part == null ? null : in.volumeYesterday(part.id);
			volume = Math.min(volume, partVolume == null ? 0L : partVolume);
		}
		return new MovementRow.LiveFacts(buy, sell, volume == Long.MAX_VALUE ? 0L : volume, null);
	}

	/**
	 * The guide price NOW of every tradeable part this computation needs (R2), by exactly the rule a bank stack's
	 * own "now" follows: RuneLite's table, except for an id RuneLite would answer another item's price for (B001)
	 * or in L3's degraded mode, where the newest guide table R0 stands in - by the part's id through the projected
	 * mapping first, then by the name the bank recorded for it (the L8 ladder). A part R0 cannot name either keeps
	 * RuneLite's price if it has one, and is absent from the map when it has none, which is what makes its stack
	 * fall back to the alch rule.
	 */
	private static Map<Integer, Long> partsNow(final Inputs in, final Parts parts, final boolean degraded)
	{
		if (parts.isEmpty())
		{
			return Collections.emptyMap();
		}
		final Map<Integer, Long> now = new HashMap<>(parts.ids.length * 2);
		for (int i = 0; i < parts.ids.length; i++)
		{
			final int id = parts.ids[i];
			final Long fromR0 = degraded || parts.rewritten[i] ? r0Value(in, id, parts.names.get(id)) : null;
			final Long gp = fromR0 != null ? fromR0
				: (parts.guide[i] > 0 ? Long.valueOf(parts.guide[i]) : null);
			if (gp != null && gp > 0L)
			{
				now.put(id, gp);
			}
		}
		return now;
	}

	/**
	 * The newest guide table's value for one id: the projection of the mapping first, then the L8 b name fallback
	 * for an id the mapping does not cover. The same ladder {@link #agreement} walks for a bank stack, reached from
	 * a part id instead - which is why it takes the name rather than reading it off a {@link BankItem}.
	 */
	@Nullable
	private static Long r0Value(final Inputs in, final int id, @Nullable final String name)
	{
		if (in.r0.isEmpty())
		{
			return null;
		}
		final PricePoint point = in.r0Map.get(id);
		final Long value = point == null ? null : point.mid();
		return value != null ? value : fallbackGp(in.r0, name, id, in.owners);
	}

	/**
	 * What a set of parts is worth at one set of prices (R2): the sum over them of {@code quantity x price}, or
	 * NULL when ANY part has no price - "a part that has no guide price at all makes the whole item fall back to
	 * the alch rule, never a partial sum". The one place that rule lives, used for the "now" side and for every
	 * window's "then" side alike, so the two can never be summed by different rules.
	 *
	 * <p>Clamped rather than wrapped, like every other total in this plugin: both factors are non-negative, so only
	 * the upper bound can be crossed.
	 *
	 * @param parts the stack's parts; null or empty is no price
	 * @param price what one of a part id costs, or null when nothing names it
	 */
	@Nullable
	private static Long partsSum(@Nullable final List<BankItem.Part> parts, final Function<Integer, Long> price)
	{
		if (parts == null || parts.isEmpty())
		{
			return null;
		}
		long sum = 0L;
		for (final BankItem.Part part : parts)
		{
			if (part == null || part.quantity <= 0L)
			{
				return null;
			}
			final Long gp = price.apply(part.id);
			if (gp == null || gp <= 0L)
			{
				return null;
			}
			sum = PortfolioMath.clampedAdd(sum, clampedMultiply(gp, part.quantity));
		}
		return sum <= 0L ? null : Long.valueOf(sum);
	}

	/** {@code gp x quantity} clamped at {@link Long#MAX_VALUE}; both operands are positive where this is called. */
	private static long clampedMultiply(final long gp, final long quantity)
	{
		try
		{
			return Math.multiplyExact(gp, quantity);
		}
		catch (final ArithmeticException overflow)
		{
			return Long.MAX_VALUE;
		}
	}

	/**
	 * Executor: takes the finished computation, drops it if a newer one exists, and publishes. The whole write of
	 * this computation happens inside ONE lock hold, so no listener can ever see the rows of one computation
	 * beside the anchor day of another.
	 *
	 * <p>A changed anchor day reconciles at once, AFTER the publish (L3: "the panel follows the Jagex rollover
	 * within one recompute").
	 */
	private void commit(final long generation, final Inputs in, final Computed computed, @Nullable final LocalDate anchor,
		final Agreement against, final boolean degraded, final boolean behind)
	{
		final long readAt = clockMillis.getAsLong();
		final boolean anchorChanged;
		synchronized (lock)
		{
			if (stopped || generation != computeGeneration)
			{
				return;
			}
			rows = computed.rows;
			portfolio = computed.summary;
			// The switches these figures were built under, published beside them (Q): a later setOptions has
			// already scheduled its own computation, and this status must not claim its settings.
			optionsUsed = in.options;
			liveOnUsed = in.liveOn;
			liveRows = computed.live;
			guideRows = computed.guide();
			alchRows = computed.alch;
			// U1/U4: the live calendar these rows were computed on - the snapshot's own UTC date, and the day each
			// window's bucket really held (U2's fallback included, because that is the day the rows compared
			// against and the day the tooltip prints).
			liveDayUsed = in.liveDay;
			windowDaysUsed.clear();
			for (final MovementWindow window : MovementWindow.values())
			{
				final LocalDate day = in.tradedDayOf(window);
				if (day != null)
				{
					windowDaysUsed.put(window, day);
				}
			}
			// T7: the switch was on, a fetch had failed, and this computation had no usable snapshot left - which
			// is exactly when every row is on the guide and the card must say so. Read off the inputs this
			// computation used, never off the state as it stands, so the sentence and the figures always agree.
			liveUnavailable = in.liveOn && in.liveFailed && in.quotes.isEmpty();
			pricedRows = computed.priced;
			guideReadAtMillis = computed.priced > 0 ? readAt : 0L;
			anchorChanged = !Objects.equals(anchorDay, anchor);
			anchorDay = anchor;
			agree = against.samples == 0 ? -1.0d : against.matches / (double) against.samples;
			agreeSamples = against.samples;
			anchorDegraded = degraded;
			anchorBehind = behind;
			status = buildStatusLocked(null);
		}
		publish();
		if (anchorChanged)
		{
			log.debug("bank-portfolio-tracker: anchor day {} (R0 day {}, {} of {} agree)", anchor, in.r0.dataDay(),
				against.matches, against.samples);
			reconcile(readAt, "anchor day " + anchor, false);
		}
	}

	/**
	 * Whether RuneLite's prices agree with a table OTHER than R0, by exactly the rule
	 * {@link #deriveAnchorDay} applies to R0 ({@value #AGREE_MIN_SAMPLES} comparable items,
	 * {@value #AGREE_THRESHOLD} of them equal). Only asked on the disagreement branch, and only of the table for
	 * the day before R0's.
	 *
	 * <p><b>Why it exists.</b> L3 was measured in one direction only - the wiki's bot publishes day D some hours
	 * after Jagex steps to it, so RuneLite LEADS the wiki for part of every day - and the code took every
	 * disagreement as that case. The other direction is real too: RuneLite loads its price table once and
	 * refreshes it every thirty minutes ({@code ItemManager.java:218}), so a long session across an outage of
	 * {@code api.runelite.net} leaves the client on an older Jagex day while the wiki's bot carries on. Agreement
	 * then collapses and the anchor was pushed to {@code r0Day + 1} - the WRONG way - putting every window two
	 * days from the day the prices are really from and inverting the sign of a 1d move. When RuneLite instead
	 * matches R0's PREDECESSOR the answer is {@code r0Day - 1}, and the service says so
	 * ({@link Status#degraded()}).
	 *
	 * <p>The predecessor is only consulted when its bytes are already in memory - which is precisely the shape
	 * of the failure: the table RuneLite still holds was R0 until the index moved on, and {@link #evictLocked()}
	 * keeps the superseded R0. Nothing is fetched to answer this question, so a client that starts up already
	 * lagging keeps today's behaviour (the anchor stays {@code r0Day + 1}) rather than issuing a request per
	 * recompute.
	 *
	 * <p><b>Why a quiet bank cannot trip it.</b> A bank of illiquid items can be 90 % unchanged over two days -
	 * but then it is at least as unchanged over ONE, so the agreement against R0 clears the same threshold and
	 * this method is never reached: it is asked only after that comparison has already failed. Reaching it on a
	 * bank that is not really behind would need most of the bank to have moved yesterday and moved back today.
	 *
	 * @param table the candidate; null or empty answers false
	 */
	private static boolean agrees(@Nullable final GuideSnapshot table, final Inputs in, @Nullable final int[] guide,
		@Nullable final String[] names)
	{
		if (table == null || table.isEmpty() || guide == null)
		{
			return false;
		}
		final int count = in.items.size();
		final Long[] values = new Long[count];
		final Map<String, Long> prices = table.pricesByName();
		for (int i = 0; i < count; i++)
		{
			final BankItem item = in.items.get(i);
			if (item == null || guide[i] <= 0)
			{
				continue;
			}
			// The folded key, not the wiki spelling: a table is keyed by exactly this, and folding the mapping's
			// name here would repeat that work for every bank item on every disagreement (L8).
			final String mapped = in.foldedNames.get(item.id);
			final Long value = mapped == null ? null : prices.get(mapped);
			values[i] = value != null ? value
				: fallbackThen(table, names == null ? null : names[i], item.name, item.id, in.owners);
		}
		return tally(values, guide).clears();
	}

	/** The L8 name ladder against a table in memory: the fresh members name first, then the bank's own name. */
	@Nullable
	private static Long fallbackThen(final GuideSnapshot table, @Nullable final String membersName,
		@Nullable final String bankName, final int id, final Map<String, Integer> owners)
	{
		Long gp = fallbackGp(table, membersName, id, owners);
		if (gp == null && !Objects.equals(membersName, bankName))
		{
			gp = fallbackGp(table, bankName, id, owners);
		}
		return gp;
	}

	/**
	 * One item's "then" against one window's baseline - the ladder the rows have always used, now shared with the
	 * portfolio (M3): the projected map first; when it lacks the id and the table behind it is in memory, the name
	 * fallback of L8 ({@link #fallbackThen}: the fresh members name, then the bank's own name, refused when the name
	 * belongs to another id). Null when nothing names the item.
	 */
	@Nullable
	private static Long thenPrice(final PriceMap baseline, @Nullable final GuideSnapshot table, final int id,
		@Nullable final String membersName, @Nullable final String bankName, final Map<String, Integer> owners)
	{
		final PricePoint point = baseline.get(id);
		if (point != null)
		{
			return point.mid();
		}
		if (table == null || table.isEmpty())
		{
			return null;
		}
		return fallbackThen(table, membersName, bankName, id, owners);
	}

	/**
	 * The bank value line (M3), from the SAME inputs as the rows: the guide "now" per canonical id exactly as the
	 * rows carry it (RuneLite's table, or R0 standing in for it in L3's degraded mode), every baseline in memory -
	 * not only the current window's - each resolved through {@link #thenPrice} so the current window's sum is the
	 * sum of the rows it is published beside, and the whole snapshot rather than the filtered rows. The bank's cash
	 * rides in with it ({@link Inputs#currencyGp}, P1), from the same snapshot as the stacks. See
	 * {@link PortfolioMath} for the signature choice.
	 *
	 * <p>A PARTS-priced stack (R2/R3) is resolved on BOTH sides as the sum over its parts: its "now" is already in
	 * {@code nowById} under its own id, and its "then" goes through {@code partsById} here, never through a lookup
	 * of the untradeable id - which no guide table names, and which a name fallback could only answer wrongly.
	 *
	 * @param nowById      the "now" price of every priced stack, by canonical id
	 * @param membersNames the fresh {@code getMembersName()} of every item the mapping did not cover, by id
	 * @param bankNames    every stack's own name as the bank captured it, by id
	 * @param partsById    the parts of every stack the rows priced as a parts sum, by that stack's id (R2)
	 * @param partNames    the name the bank recorded for each part id, for that sum's own L8 ladder
	 */
	private static PortfolioSummary summarise(final Inputs in, final Map<Integer, Long> nowById,
		final Map<Integer, String> membersNames, final Map<Integer, String> bankNames,
		final Map<Integer, List<BankItem.Part>> partsById, final Map<Integer, String> partNames,
		final Map<MovementWindow, Map<Integer, Long>> windowNow,
		final Map<MovementWindow, Map<Integer, Long>> windowThen, final int liveRows)
	{
		final Map<MovementWindow, Function<Integer, Long>> thenPrices = new EnumMap<>(MovementWindow.class);
		final Map<MovementWindow, Function<Integer, Long>> nowPrices = new EnumMap<>(MovementWindow.class);
		final Map<MovementWindow, LocalDate> thenDays = new EnumMap<>(MovementWindow.class);
		for (final MovementWindow window : MovementWindow.values())
		{
			final PriceMap baseline = in.baselines.get(window);
			if (baseline == null || !hasRevision(baseline))
			{
				continue;
			}
			final GuideSnapshot table = in.tables.get(window);
			// T4/T5: the traded "then" of a stack this window priced live, decided row by row in the loop above,
			// so the card sums exactly what the rows compared. Empty with the switch off.
			final Map<Integer, Long> tradedThen = windowThen.get(window);
			thenPrices.put(window, id ->
			{
				if (id == null)
				{
					return null;
				}
				final Long traded = tradedThen == null ? null : tradedThen.get(id);
				if (traded != null)
				{
					return traded;
				}
				final List<BankItem.Part> itemParts = partsById.get(id);
				if (itemParts != null)
				{
					return partsSum(itemParts, partId ->
						thenPrice(baseline, table, partId, partNames.get(partId), partNames.get(partId), in.owners));
				}
				return thenPrice(baseline, table, id, membersNames.get(id), bankNames.get(id), in.owners);
			});
			final Map<Integer, Long> guideNow = windowNow.get(window);
			if (guideNow != null && !guideNow.isEmpty())
			{
				nowPrices.put(window, guideNow::get);
			}
			final LocalDate day = baseline.dataDay();
			if (day != null)
			{
				thenDays.put(window, day);
			}
		}
		return PortfolioMath.summariseResolved(in.items, nowById::get, thenPrices, nowPrices, thenDays,
			in.currencyGp, liveRows);
	}

	/** A guide price is one number: both sides carry it, so {@code mid()} hands it back unchanged. */
	private static PricePoint guidePoint(final Long gp)
	{
		return new PricePoint(gp, gp);
	}

	private boolean superseded(final long generation)
	{
		synchronized (lock)
		{
			return stopped || generation != computeGeneration;
		}
	}

	// ---------------------------------------------------------------- status and publishing (C21, L7, L11, L13)

	/** Republishes the current rows (same list instance) with a freshly built status. */
	private void publishStatusOnly(@Nullable final Problem problemOverride)
	{
		synchronized (lock)
		{
			if (stopped)
			{
				return;
			}
			status = buildStatusLocked(problemOverride);
		}
		publish();
	}

	/** Hands the current rows and status to every listener, through {@code edt} and nowhere else. */
	private void publish()
	{
		final List<MovementRow> currentRows;
		final Status currentStatus;
		synchronized (lock)
		{
			currentRows = rows;
			currentStatus = status;
		}
		edt.accept(() ->
		{
			if (stopped)
			{
				return;
			}
			for (final Listener listener : listeners)
			{
				deliver(listener, currentRows, currentStatus);
			}
		});
	}

	private static void deliver(final Listener listener, final List<MovementRow> rows, final Status status)
	{
		try
		{
			listener.onRows(rows, status);
		}
		catch (final RuntimeException e)
		{
			log.warn("bank-portfolio-tracker: a listener threw", e);
		}
	}

	/** Under the lock. The C21/K13/L13 fields from the current state; {@code problemOverride} beats the derived one. */
	private Status buildStatusLocked(@Nullable final Problem problemOverride)
	{
		final MovementWindow window = filter.window();
		final PriceMap baseline = baselines.get(window);
		final boolean loaded = hasRevision(baseline);
		// T7 joins L3's and L11's flavours: the traded feed being unusable is a fallback the user should be told
		// about in the same red the guide failure gets, and it comes LAST because a guide problem is the graver
		// one - without the guide there is no movement at all, while without the traded feed there is still the
		// series every other addendum shipped.
		final boolean degraded = anchorDegraded || anchorBehind || historyFailed || liveUnavailable;
		final String reason;
		if (anchorDegraded)
		{
			reason = "fewer than " + AGREE_MIN_SAMPLES + " bank items compare with the newest guide table - it stands in as now";
		}
		else if (anchorBehind)
		{
			reason = "RuneLite's price table is behind the wiki - the baseline day is stepped back to match";
		}
		else if (historyFailed)
		{
			reason = "the last wiki history fetch failed - showing stored baselines";
		}
		else if (liveUnavailable)
		{
			reason = LIVE_UNAVAILABLE;
		}
		else
		{
			reason = null;
		}
		final Problem problem = problemOverride != null ? problemOverride : problemLocked();
		return new Status(
			guideReadAtMillis,
			bank.capturedAtMillis,
			bank.capturedAtMillis > 0L,
			loggedIn,
			pricedRows > 0 ? MovementRow.PriceSource.GUIDE : MovementRow.PriceSource.NONE,
			problem.text,
			problem.kind,
			rows.size(),
			// Counted under the options the rows were computed with (Q5), so "n of m stacks" and the summary's own
			// itemsTotal are the same m.
			itemCount(bank, optionsUsed),
			window,
			loaded,
			loaded ? baseline.bucketSeconds() : 0L,
			loaded ? baseline.revId() : 0L,
			mappingAtMillis,
			indexAtMillis,
			anchorDay,
			agree,
			agreeSamples,
			r0.dataDay(),
			r0.revId(),
			degraded,
			anchorDegraded,
			reason,
			portfolio,
			optionsUsed,
			// T8: OFF, not a set of zeroes, when the traded series was not available to the computation these
			// figures came from - so the bridge's echo with live prices off, and on a build with no traded client
			// at all, is byte for byte what it was before addendum T.
			liveOnUsed
				? new Status.LiveStatus(latestAtMillis, latest.size(), liveRows, guideRows, alchRows, liveDayUsed,
					windowDaysUsed)
				: Status.LiveStatus.OFF);
	}

	/**
	 * A baseline exists when it names a wiki REVISION, whatever it names of the bank. A revision fetched before
	 * the mapping arrived and before the bank was loaded projects onto no id at all, yet it is a real "then":
	 * it stays in memory and names bank items as they appear ({@link #finish}), and it is re-projected when the
	 * mapping lands. {@link PriceMap#EMPTY} and a trade-era file both carry revision 0.
	 */
	private static boolean hasRevision(final PriceMap map)
	{
		return map.revId() > 0L;
	}

	/**
	 * Under the lock. The K7/L11 sentences, derived from state rather than from the last event so that two
	 * events cannot fight over one line: a window WITH a baseline never complains (a stale revision is still a
	 * real past price, L11); one whose target predates the index says "No 180d history" (not an error); one
	 * without a baseline says "No 1d history yet" while any history request is out (the chain index, R0,
	 * anchor, bodies ends by serving the current window); and "unavailable" once the last attempt failed or every
	 * candidate revision proved unusable.
	 */
	private Problem problemLocked()
	{
		final MovementWindow window = filter.window();
		if (hasRevision(baselines.get(window)))
		{
			return Problem.NONE;
		}
		if (noHistory.contains(window))
		{
			return Problem.of(problemNoHistory(window), ProblemKind.NO_HISTORY);
		}
		if (indexInFlight || tablesInFlight)
		{
			return Problem.of(problemHistoryPending(window), ProblemKind.PENDING);
		}
		if (historyFailed || baselineFailed.contains(window) || exhausted.contains(window))
		{
			return Problem.of(problemHistoryUnavailable(), ProblemKind.HISTORY_DOWN);
		}
		return Problem.NONE;
	}

	// ---------------------------------------------------------------- staleness rules (L4, L10, K3)

	/** Under the lock. No index, unstamped, or fetched at least {@link #HISTORY_MAX_AGE_MS} ago (L4). */
	private boolean indexStaleLocked(final long now)
	{
		return index.isEmpty() || expired(indexAtMillis, now, HISTORY_MAX_AGE_MS);
	}

	/** Under the lock. Absent, unstamped, or fetched at least {@link #MAPPING_MAX_AGE_MS} ago. */
	private boolean mappingStaleLocked(final long now)
	{
		return mapping.isEmpty() || expired(mappingAtMillis, now, MAPPING_MAX_AGE_MS);
	}

	/**
	 * Under the lock. The traded snapshot is not usable: none in memory, unstamped, older than
	 * {@link #LIVE_LATEST_MAX_AGE_MS}, or stamped in the future (T7; {@link #expired} explains the last).
	 */
	private boolean latestStaleLocked(final long now)
	{
		return latest.isEmpty() || expired(latestAtMillis, now, LIVE_LATEST_MAX_AGE_MS);
	}

	/**
	 * Under the lock. Whether the TICK should ask for a new {@code /latest} snapshot (addendum AS, decision 3): none
	 * in memory, unstamped, at least {@link #LATEST_MIN_AGE_MS} old, or stamped in the future. {@link #onTick} is
	 * the only reader - a manual refresh asks whatever the age, and start-up keeps {@link #latestStaleLocked}.
	 *
	 * <p>The same {@link #expired} as every other stamp here, and the future case matters for the same reason: a
	 * snapshot stamped ahead of a clock corrected backwards is one {@link #latestStaleLocked} already refuses to
	 * price from, so trusting it as "fetched moments ago" would keep every row on the guide until real time caught
	 * up with the stamp.
	 */
	private boolean latestDueLocked(final long now)
	{
		return latest.isEmpty() || expired(latestAtMillis, now, LATEST_MIN_AGE_MS);
	}

	/**
	 * Whether something stamped at {@code stampedAt} is due to be fetched again: unstamped, older than
	 * {@code maxAgeMs}, or stamped in the FUTURE.
	 *
	 * <p>The future case is the one worth naming (B013). Both of these stamps are read back off disk from an
	 * earlier session and compared against a wall clock, so a machine whose clock was fast when the file was
	 * written - then corrected by NTP - produces a negative age. A plain {@code now - stampedAt >= maxAgeMs} reads
	 * that as "fetched moments ago" and would leave the plugin running on last session's index and mapping for as
	 * long as the correction was worth, with the tick silently declining to refetch. A stamp from the future
	 * cannot be trusted at all, so it counts as expired.
	 */
	private static boolean expired(final long stampedAt, final long now, final long maxAgeMs)
	{
		final long age = now - stampedAt;
		return stampedAt <= 0L || age < 0L || age >= maxAgeMs;
	}

	/** A snapshot with a capture time whose account hash and profile are the given ones. EMPTY never belongs. */
	private static boolean belongsTo(@Nullable final BankSnapshot snapshot, final long accountHash, final String profile)
	{
		if (snapshot == null || snapshot.capturedAtMillis <= 0L || snapshot.accountHash != accountHash)
		{
			return false;
		}
		return profile.equals(snapshot.profileType == null ? "" : snapshot.profileType);
	}

	// ---------------------------------------------------------------- executor plumbing

	/**
	 * Runs {@code task} on the executor unless the service is stopped by then. Throwables are logged here
	 * because {@code submit} wraps every task in a {@code FutureTask} that swallows them, and RuneLite's wrapper
	 * ({@code ExecutorServiceExceptionLogger.java:69-72}) only adds a log line - so without this catch an
	 * {@code OutOfMemoryError} or a {@code StackOverflowError} out of a parse would vanish without a trace on the
	 * client's shared thread.
	 *
	 * <p>{@code submit} rather than the executor's fire-and-forget method, for two reasons: {@code submit}'s
	 * {@code FutureTask} is what makes the catch above the only place an exception can be seen, and it keeps the
	 * word {@code execute} out of this package, which makes an eyeball scan against the Hub's forbidden
	 * {@code okhttp3.Call#execute()} trivially clean. The machine check is signature-based over bytecode and
	 * would not have confused the two either way - do not contort other code to avoid a method NAME.
	 */
	private void execute(final Runnable task)
	{
		if (stopped)
		{
			return;
		}
		try
		{
			executor.submit(() ->
			{
				if (stopped)
				{
					return;
				}
				try
				{
					task.run();
				}
				catch (final Throwable e)
				{
					log.warn("bank-portfolio-tracker: background task failed", e);
				}
			});
		}
		catch (final RuntimeException e)
		{
			// RejectedExecutionException: the client is shutting its executor down.
			log.debug("bank-portfolio-tracker: the executor refused a task", e);
		}
	}

	/**
	 * Queues a store write that runs even after {@link #stop()} and is tracked for {@link #flush()}. The store
	 * never throws (C18), so the catch is for a mock or a future change; the future completes either way.
	 */
	private void submitWrite(final String what, final Runnable write)
	{
		final CompletableFuture<Void> done = new CompletableFuture<>();
		synchronized (lock)
		{
			pendingWrites.removeIf(CompletableFuture::isDone);
			pendingWrites.add(done);
		}
		try
		{
			executor.submit(() ->
			{
				try
				{
					write.run();
				}
				catch (final RuntimeException e)
				{
					log.warn("bank-portfolio-tracker: {} failed", what, e);
				}
				finally
				{
					done.complete(null);
				}
			});
		}
		catch (final RuntimeException e)
		{
			done.complete(null);
			log.warn("bank-portfolio-tracker: {} could not be queued", what, e);
		}
	}

	/** A periodic task on the executor; null when the executor refuses (client shutting down). */
	@Nullable
	private ScheduledFuture<?> schedule(final String what, final Runnable task, final long initialDelayMs, final long periodMs)
	{
		try
		{
			return executor.scheduleWithFixedDelay(() ->
			{
				if (stopped)
				{
					return;
				}
				try
				{
					task.run();
				}
				catch (final RuntimeException e)
				{
					log.warn("bank-portfolio-tracker: {} failed", what, e);
				}
			}, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
		}
		catch (final RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: could not schedule {}", what, e);
			return null;
		}
	}

	private static void cancel(@Nullable final ScheduledFuture<?> future)
	{
		if (future != null)
		{
			future.cancel(false);
		}
	}

	private static <T> CompletableFuture<T> failed(final Throwable error)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(error);
		return future;
	}

	/**
	 * The fetched index unioned with the one already held, newest first, one entry per revision id and at most
	 * {@value #INDEX_KEPT} of them (B027; see {@link #finishIndex}). The fetched copy wins a collision, since its
	 * metadata is the fresher reading of the same immutable revision.
	 */
	private static List<RevisionRef> mergedIndex(final Collection<RevisionRef> held, final Collection<RevisionRef> fetched)
	{
		final Map<Long, RevisionRef> byId = new LinkedHashMap<>();
		for (final RevisionRef ref : fetched)
		{
			if (ref != null)
			{
				byId.put(ref.revId(), ref);
			}
		}
		for (final RevisionRef ref : held)
		{
			if (ref != null)
			{
				byId.putIfAbsent(ref.revId(), ref);
			}
		}
		final List<RevisionRef> copy = new ArrayList<>(byId.values());
		copy.sort(RevisionRef.NEWEST_FIRST);
		final List<RevisionRef> kept = copy.size() > INDEX_KEPT ? new ArrayList<>(copy.subList(0, INDEX_KEPT)) : copy;
		return Collections.unmodifiableList(kept);
	}

	/** The index sorted newest first and made unmodifiable, null entries dropped ({@code RevisionRef.NEWEST_FIRST}). */
	private static List<RevisionRef> sortedIndex(final Collection<RevisionRef> refs)
	{
		final List<RevisionRef> copy = new ArrayList<>(refs.size());
		for (final RevisionRef ref : refs)
		{
			if (ref != null)
			{
				copy.add(ref);
			}
		}
		copy.sort(RevisionRef.NEWEST_FIRST);
		return Collections.unmodifiableList(copy);
	}

	/**
	 * A baseline off the disk, or EMPTY when there is none - the one gate a baseline file has to pass before
	 * {@link #start()} adopts it, and the reason it is two tests rather than one:
	 * <ul>
	 * <li>{@code revId <= 0} - a trade-era map, written before addendum K. Those are what showed a 1,086 gp hat
	 * as "+12845.5 %"; the start-up sweep is the first lock on that door and this is the second.</li>
	 * <li>{@code schema < }{@link PriceMapDto#SCHEMA} - a file written by an older build, whose fields may no
	 * longer mean what this build reads them as. A K-era baseline carries a revision id exactly like a current
	 * one, so the first test cannot see it, and yet its {@code bucketSeconds} is the revision's SAVE time where
	 * this build reads the table's own {@code %LAST_UPDATE%} (L7) - a day apart often enough to flip the sign
	 * on a row, and once adopted it teaches {@code rememberDayLocked} that wrong day for a revision that
	 * {@code resolveLocked} will then accept without ever refetching the body.</li>
	 * </ul>
	 *
	 * <p>Refusing costs one refetch of that window - the same one the six-hour cadence makes anyway - and the
	 * window says "No 1d history yet" until it lands. The file is left on disk; the next save replaces it whole.
	 */
	private static PriceMap guideOrEmpty(@Nullable final PriceMap map)
	{
		return map == null || map.revId() <= 0L || map.schema() < PriceMapDto.SCHEMA ? PriceMap.EMPTY : map;
	}

	private static Map<Integer, String> orEmpty(@Nullable final Map<Integer, String> names)
	{
		return names == null ? Collections.emptyMap() : names;
	}

	private static List<RevisionRef> orEmptyList(@Nullable final List<RevisionRef> refs)
	{
		return refs == null ? Collections.emptyList() : refs;
	}

	/**
	 * A private copy of the item list: the snapshot's list is public and mutable, the computation is not under the
	 * lock. Every stack, untradeable ones included - {@link #stacksOf(BankSnapshot, ViewOptions)} is the one the
	 * computation takes.
	 */
	private static List<BankItem> itemsOf(@Nullable final BankSnapshot snapshot)
	{
		if (snapshot == null || snapshot.items == null || snapshot.items.isEmpty())
		{
			return Collections.emptyList();
		}
		return new ArrayList<>(snapshot.items);
	}

	/**
	 * The stacks a computation is to consider and where each one's quantity is (Q5, Y3) - the one place the two
	 * switches that decide WHICH STACKS EXIST are applied, so everything downstream simply works on "the bank" and
	 * neither switch can be half-applied.
	 *
	 * <ul>
	 * <li>"Include inventory and worn gear" OFF, or nothing carried: the bank's own stacks, and no split at all -
	 * which is the list the snapshot used to hold at all, so the whole computation downstream is the pre-Y one,
	 * figure for figure.</li>
	 * <li>ON: the bank's stacks, the inventory's and the worn gear's folded onto canonical ids with the quantities
	 * added, in the one row order this plugin has ({@link BankReader#BY_NAME_THEN_ID}), each carrying the three
	 * figures its row's hover names ({@link Stacks#splits}).</li>
	 * </ul>
	 *
	 * <p>Then "Include untradeable items" (Q5), over the merged list: a worn or carried untradeable follows that
	 * switch exactly as a banked one does.
	 */
	private static Stacks stacksOf(@Nullable final BankSnapshot snapshot, final ViewOptions options)
	{
		if (!options.countInventory() || snapshot == null || nothingCarried(snapshot))
		{
			return new Stacks(keep(itemsOf(snapshot), options), Collections.<Integer, int[]>emptyMap());
		}

		final Map<Integer, BankItem> merged = new LinkedHashMap<>();
		final Map<Integer, int[]> splits = new HashMap<>();
		// The bank first, so a stack held in two places keeps the name, the stackable flag and the untradeable
		// marks the BANK captured for it; the inventory before the worn gear, which is the order the hover reads.
		fold(merged, splits, snapshot.items, 0);
		fold(merged, splits, snapshot.inventory, 1);
		fold(merged, splits, snapshot.worn, 2);
		final List<BankItem> rows = new ArrayList<>(merged.values());
		rows.sort(BankReader.BY_NAME_THEN_ID);
		return new Stacks(keep(rows, options), splits);
	}

	/** Whether a snapshot has a carried half worth merging - a pre-Y file, and a logged-out one, have none. */
	private static boolean nothingCarried(final BankSnapshot snapshot)
	{
		return (snapshot.inventory == null || snapshot.inventory.isEmpty())
			&& (snapshot.worn == null || snapshot.worn.isEmpty());
	}

	/**
	 * One container's stacks folded into the merge (Y3): a stack already there gains the quantity, a new one is
	 * COPIED in ({@link BankItem#withQuantity}) rather than shared, because the lists belong to the persisted
	 * snapshot and a computation must never write to them.
	 *
	 * @param merged the fold map, keyed by canonical id and mutated in place
	 * @param splits id to {bank, inventory, worn}, mutated in place
	 * @param stacks one container's stacks; null or empty adds nothing
	 * @param slot   which of the three figures these stacks count towards
	 */
	private static void fold(final Map<Integer, BankItem> merged, final Map<Integer, int[]> splits,
		@Nullable final List<BankItem> stacks, final int slot)
	{
		if (stacks == null)
		{
			return;
		}

		for (final BankItem item : stacks)
		{
			if (item == null || item.id <= 0 || item.quantity <= 0)
			{
				continue;
			}

			final BankItem existing = merged.get(item.id);
			merged.put(item.id, existing == null
				? item.withQuantity(item.quantity)
				: existing.withQuantity(BankReader.addClamped(existing.quantity, item.quantity)));
			final int[] split = splits.get(item.id);
			if (split == null)
			{
				final int[] fresh = new int[3];
				fresh[slot] = item.quantity;
				splits.put(item.id, fresh);
			}
			else
			{
				split[slot] = BankReader.addClamped(split[slot], item.quantity);
			}
		}
	}

	/**
	 * Q5 over one stack list: every stack while "Include untradeable items" is on, and only the GE-tradeable ones
	 * while it is off. A fresh list either way - the caller's may be the snapshot's own.
	 */
	private static List<BankItem> keep(final List<BankItem> stacks, final ViewOptions options)
	{
		return options.countUntradeables() ? stacks : tradeableOnly(stacks);
	}

	/**
	 * The GE-tradeable stacks, whatever the untradeables switch says - what a baseline is PROJECTED over
	 * ({@link #project}). An untradeable item has no guide price on any day, so joining it onto a guide table
	 * could only find another item's name; keeping it out means the projections, and the {@code bucket-*.json}
	 * files they are written to, are the same bytes whether that switch is on or off.
	 *
	 * <p>Over the MERGED stacks since Y3: a projection has to name what the player is WEARING too, or a worn item
	 * the mapping does not cover would be the one row on the sidebar with no baseline. With "Include inventory and
	 * worn gear" off it is the bank's own tradeable stacks exactly, so those files are the bytes they were.
	 */
	private static List<BankItem> tradeableItemsOf(@Nullable final BankSnapshot snapshot, final ViewOptions options)
	{
		return tradeableOnly(stacksOf(snapshot, options).items);
	}

	/** One stack list without the stacks the Grand Exchange does not list; a fresh list, nulls kept as they were. */
	private static List<BankItem> tradeableOnly(final List<BankItem> stacks)
	{
		if (stacks.isEmpty())
		{
			return stacks;
		}
		final List<BankItem> tradeable = new ArrayList<>(stacks.size());
		for (final BankItem item : stacks)
		{
			if (item == null || !item.untradeable)
			{
				tradeable.add(item);
			}
		}
		return tradeable;
	}

	private static int itemCount(@Nullable final BankSnapshot snapshot)
	{
		return snapshot == null || snapshot.items == null ? 0 : snapshot.items.size();
	}

	/**
	 * How many stacks {@link #stacksOf(BankSnapshot, ViewOptions)} would hand a computation - the status's bank
	 * count, and the {@code m} of "n of m stacks". Since Y3 that is the MERGED count while "Include inventory and
	 * worn gear" is on: one row for a stack held in two places, counted once.
	 */
	private static int itemCount(@Nullable final BankSnapshot snapshot, final ViewOptions options)
	{
		if (snapshot == null || snapshot.items == null)
		{
			return 0;
		}
		if (options.countInventory() && !nothingCarried(snapshot))
		{
			return stacksOf(snapshot, options).items.size();
		}
		if (options.countUntradeables())
		{
			return snapshot.items.size();
		}
		int count = 0;
		for (final BankItem item : snapshot.items)
		{
			if (item == null || !item.untradeable)
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * The cash a computation may count (Q4, Y3): the bank's coins and platinum tokens, plus the ones in the
	 * player's own pockets while "Include inventory and worn gear" is on, and 0 with the cash switch off.
	 */
	private static long cashOf(@Nullable final BankSnapshot snapshot, final ViewOptions options)
	{
		if (snapshot == null || !options.countCash())
		{
			return 0L;
		}
		return options.countInventory()
			? PortfolioMath.clampedAdd(snapshot.currencyGp, snapshot.carriedGp)
			: snapshot.currencyGp;
	}

	private static String describe(@Nullable final Throwable error)
	{
		if (error == null)
		{
			return "no table";
		}
		final String message = error.getMessage();
		return error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
