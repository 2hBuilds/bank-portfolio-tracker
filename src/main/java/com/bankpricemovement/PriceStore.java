package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's four kinds of JSON file under {@code ~/.runelite/plugin-data/bank-portfolio-tracker/} (contract
 * C17-C19, rewritten by K11, extended by addendum L line L4 and moved into RuneLite's own plugin-data directory
 * by addendum AD):
 * <ul>
 * <li>{@code bank-<accountHash>-<profileType>.json} - the last bank seen for one account on one
 * {@code RuneScapeProfileType}, so the panel can show the items (and their movements) at the Grand Exchange
 * without walking to a bank, and immediately after a relaunch (decision D7). One file per account and profile
 * because a League or Deadman bank is a different bank.</li>
 * <li>{@value #MAPPING_FILE} - the wiki's item id to wiki name table (K3, L8). Refetched at most weekly: it
 * changes only when Jagex adds items, and it is the name source that keeps a bank item off ANOTHER item's price
 * - the composition-name fallback returns the wrong item's row for 287 of 4,662 ids, one of them in the user's
 * own bank (id 3159, "Karambwan vessel (baited)" - L-F).</li>
 * <li>{@value #REVINDEX_FILE} - the guide page's revision history (L4): about 232 days of
 * {@code {revId, editSeconds, user, comment}}, from which every window's baseline revision is chosen LOCALLY by
 * calendar day. Refetched when older than six hours. Persisting it is what lets a relaunch pick a baseline (and
 * notice that the stored one is still the right day) before any request comes back.</li>
 * <li>{@code baseline-<window>.json} - one guide-price map per {@link MovementWindow} (D1 / D7 / D30 / D90 /
 * D180), the "then" side of every movement, stamped with the wiki revision it was read from and with that
 * table's own day marker.</li>
 * <li>{@value #TRADED_LATEST_FILE} and {@code traded-<window>.json} - the TRADED feeds of addendum T line T2:
 * the newest {@code /latest} snapshot, and one whole-day {@code /24h} bucket per window (the calendar day that
 * window counts back to from the live snapshot's own UTC date, addendum U line U1 - which is not the guide
 * baseline's day and need not be). Both carry their own {@code schema}, both are swept by the rules below, and
 * neither is written or read while the {@code livePrices} switch is off.</li>
 * <li>{@code history-<accountHash>-<profileType>.json} - the player's own net worth, one reading per LOCAL day
 * (addendum AU), paired with the bank file of the same account and profile. The one file here that is not a cache:
 * nothing can fetch it again, so it has its own load with three outcomes ({@link #loadBankHistory}), every write
 * re-reads it and merges one point ({@link #recordBankHistory}), and a file that failed to read once is never
 * written over for the rest of the session. The sweep never touches it.</li>
 * </ul>
 *
 * <p><b>What addendum K took away.</b> The real-time trade endpoints are gone from the plugin, so there is no
 * {@code prices-latest.json} any more ("now" is RuneLite's own guide price, K1 - no request, nothing to cache)
 * and the {@code baseline-H1} / {@code baseline-H24} files name windows that no longer exist.
 * {@link #deleteStaleFiles()} sweeps all three away at startUp, along with any baseline still holding trade
 * averages - those are what produced "+12845.5 %" on a 1,086 gp hat in the first live session, and leaving one
 * on disk would show that number again for the minutes before the first refetch lands.
 *
 * <p><b>Files rather than config.</b> The workspace already learned this with {@code BeamStore}
 * ({@code BeamStore.java:57-59}): the remote config store has a size cap that a bank of several hundred stacks
 * would run into, and a file is trivially exportable and inspectable. Only the config keys live in RuneLite's
 * own config: the five filter keys of C39 plus addendum O line O2's three hero show/hide switches - eight in
 * all (see {@link BankPriceMovementConfig}).
 *
 * <p><b>Threading.</b> Every method here is <em>synchronous</em> and blocking on whatever thread calls it: the
 * caller ({@code PriceService}) owns the threading and runs these on its injected
 * {@code ScheduledExecutorService}, exactly so a disk write can never land on the client thread or the EDT - a
 * synchronous write on the client thread froze the client for 3-5 s once already (playbook 7.2). Nothing here
 * touches Swing or the client, and the only RuneLite state it reaches is the {@link Filepath} its
 * {@link Directory} hands it - {@code Plugin.getPluginDirectory()} in production - so the store needs no thread
 * of its own and holds no mutable state beyond the memo of that one lookup and the history owners whose file
 * failed to read this session (addendum AU); two callers on two threads only race
 * on the file system, where the atomic replace of {@link #writeAtomic} makes the last write win whole.
 *
 * <p><b>Every byte goes through {@link Filepath}</b> (addendum AD), RuneLite's sandboxed path wrapper: a
 * {@code Filepath} cannot name anything outside the root it was made from, so the store is unable - not merely
 * unwilling - to write outside its own directory. That is the Plugin Hub's file-I/O rule
 * ({@code templateplugin/AGENTS.md}, "All file i/o must go through the Filepath utility"), and
 * {@code FileIoRuleTest} holds the whole package to it.
 *
 * <p><b>Failure policy</b> (C18, copied in shape from {@code BeamStore.readJson}, {@code BeamStore.java:1404-1434}):
 * a missing file reads as EMPTY; a file that parses to nothing useful is moved aside as
 * {@code <name>.corrupt-<millis>} and reads as EMPTY, so the next save starts clean while the bad bytes stay on
 * disk for a bug report; a file that cannot be <em>read</em> (locked, permissions, a directory in the way) is
 * logged and reads as EMPTY, and is left exactly where it is. No load and no save ever throws: prices and a
 * remembered bank are a convenience, and losing them must never take the panel (or the client) down with them.
 * A save failure is logged and dropped - the next fetch writes again.
 *
 * <p><b>Not final, methods not final</b>, because {@code PriceService}'s tests mock the store and Mockito 4.11
 * without {@code mockito-inline} cannot mock a final class or stub a final method (playbook 7.6).
 */
public class PriceStore
{
	private static final Logger log = LoggerFactory.getLogger(PriceStore.class);

	/**
	 * The plugin's own directory name, and the one spelling of it (D10): the hub slug, the
	 * {@code @PluginDescriptor} {@code internalName} that {@code Plugin.getPluginDirectory()} requires, and the
	 * {@code legacyDataDirectory} whose {@code ~/.runelite/bank-portfolio-tracker/} RuneLite moves into
	 * {@code ~/.runelite/plugin-data/} the first time that method is called (addendum AD). An annotation takes
	 * only a constant expression, which is exactly what this is, so the descriptor and the store cannot drift.
	 */
	public static final String DIR_NAME = "bank-portfolio-tracker";
	/** Prefix of a remembered bank file; the account hash and profile type follow (C17). */
	public static final String BANK_PREFIX = "bank-";
	/** Prefix of a baseline file; the {@link MovementWindow} name follows (C17, K11). */
	public static final String BASELINE_PREFIX = "baseline-";
	/** The persisted id-to-wiki-name table (K11). */
	public static final String MAPPING_FILE = "mapping.json";
	/** The persisted revision history of the guide page (L4). */
	public static final String REVINDEX_FILE = "revindex.json";
	/** The newest traded {@code /latest} snapshot (addendum T, line T2). */
	public static final String TRADED_LATEST_FILE = "traded-latest.json";
	/**
	 * Prefix of a traded daily bucket; the {@link MovementWindow} name follows, giving {@code traded-D1.json} to
	 * {@code traded-D180.json} (T2). {@value #TRADED_LATEST_FILE} shares the prefix and is NOT one of them - see
	 * {@link #deleteStaleFiles()}.
	 */
	public static final String TRADED_PREFIX = "traded-";
	/**
	 * Prefix of a net-worth history file; the account hash and profile type follow, exactly as for a bank file
	 * (addendum AU, plan 7.1 item 1), so a bank and its history always pair up.
	 */
	public static final String HISTORY_PREFIX = "history-";
	/** Suffix of every file this store writes. */
	public static final String JSON_SUFFIX = ".json";

	/**
	 * The pre-addendum-K {@code /latest} spot map. Nothing writes or reads it any more - "now" is RuneLite's own
	 * guide price (K1) - and {@link #deleteStaleFiles()} removes it. Kept as a constant only so the sweep and
	 * its test name the same string.
	 */
	public static final String LEGACY_LATEST_FILE = "prices-latest.json";

	private static final String TMP_SUFFIX = ".tmp";
	/**
	 * How many temp NAMES {@link #writeAtomic(Filepath, String)} will try before it gives up. Every collision means
	 * another writer holds that exact name at that exact moment, so three is already far past unlucky.
	 */
	private static final int TMP_ATTEMPTS = 3;
	private static final String CORRUPT_SUFFIX = ".corrupt-";
	/** Stands in for a blank profile type so a file name can never be {@code bank-123-.json}. */
	private static final String UNKNOWN_PROFILE = "UNKNOWN";
	/**
	 * Everything outside this set is replaced in a file name. The profile type is a
	 * {@code RuneScapeProfileType} enum name in practice, so this never fires; it is here because the name
	 * arrives as a String from the plugin and a separator (or a {@code ..}) in a file name would write outside
	 * the store's directory.
	 */
	private static final Pattern UNSAFE_IN_NAME = Pattern.compile("[^A-Za-z0-9_-]");
	/**
	 * The two keys {@link #readBaselineHeader} looks for, spelled as they appear on disk. Gson has no
	 * {@code @SerializedName} on {@link PriceMapDto}, so a JSON key IS the field's name and renaming either field
	 * would have to change these with it - which is what {@code PriceStoreTest} pins by sweeping a baseline the
	 * store itself wrote rather than a hand-typed one.
	 */
	private static final String REV_ID_KEY = "revId";
	private static final String SCHEMA_KEY = "schema";

	private final Gson gson;
	private final Directory directory;
	/**
	 * The resolved directory, remembered after the first successful lookup. Volatile because two of the
	 * service's tasks can reach it on two threads; a race only costs a second call of {@link Directory#get()},
	 * which answers the same path.
	 */
	@Nullable
	private volatile Filepath resolved;
	/**
	 * Whether the failure below has already been logged. A broken disk would otherwise print the same warning
	 * a dozen times per refresh - once per path this store builds - and a per-event log line is exactly what
	 * the Plugin Hub's reviewers look for.
	 */
	private volatile boolean warnedNoDirectory;
	/**
	 * The history files that failed to read this session, by owner ({@link #historyOwner}): nothing is written to
	 * any of them again until the client restarts (addendum AU, plan 7.1 item 5). This is the rule that stops one
	 * locked or damaged read from replacing years of readings with a single point.
	 */
	private final Set<String> failedHistory = ConcurrentHashMap.newKeySet();
	/**
	 * The history owners whose file this session has already copied aside before a rewrite that drops something it
	 * held ({@link BankHistoryLoad#lossy()}; review finding H7) - one copy per owner per session is enough, because
	 * the copy holds the file as it was before this build first touched it.
	 */
	private final Set<String> backedUpHistory = ConcurrentHashMap.newKeySet();
	/**
	 * The history owners whose file this session has already warned it could not write (final review R5): a reading
	 * the store did not take is sent again with every later one (review finding H5), which with a file that reads but
	 * will not write is every bank change and every half-hourly re-check - one WARN per owner per session is the
	 * news, and the retries after it log at debug.
	 */
	private final Set<String> warnedHistoryWrite = ConcurrentHashMap.newKeySet();
	/**
	 * Held across a history record's read, merge and write, so two records from two threads of one store cannot
	 * each read the file before the other writes it and lose a day between them. Two CLIENTS still race; each
	 * re-reads before it writes, which narrows that race to the moment between one read and its own write.
	 */
	private final Object historyLock = new Object();

	/**
	 * Where the store's directory comes from, resolved on first use rather than in the constructor.
	 *
	 * <p>It is a seam because {@code Plugin.getPluginDirectory()} is neither free nor infallible: it creates
	 * {@code ~/.runelite/plugin-data/} (RuneLite's own folder, shared by every plugin that stores anything),
	 * performs the one-time move of this plugin's legacy folder, and throws {@link IOException} when the disk
	 * says no. Resolving it lazily keeps two properties the store had before addendum AD - no disk work on the
	 * thread that starts the plugin, and a transient failure costing one save rather than the whole session -
	 * and it is what lets a test hand over a {@code TemporaryFolder}.
	 *
	 * <p>The plugin's OWN directory is still not created until the first save: {@code getPluginDirectory()}
	 * names it without making it (RuneLite's {@code rooted()} does no {@code mkdir}), so an installed but
	 * never-used plugin leaves behind nothing of its own.
	 */
	public interface Directory
	{
		/** This plugin's own directory. */
		Filepath get() throws IOException;
	}

	/**
	 * @param gson      RuneLite's injected Gson ({@code RuneLiteModule.java:138} binds the one instance);
	 *                  constructing one here is a Plugin Hub blocker (C46)
	 * @param directory where the files live, asked for on the first read or write so an installed-but-never-used
	 *                  plugin leaves nothing behind; {@code BankPriceMovementPlugin::getPluginDirectory} in
	 *                  production, a {@code TemporaryFolder} in tests
	 */
	public PriceStore(final Gson gson, final Directory directory)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		this.directory = Objects.requireNonNull(directory, "directory");
	}

	/**
	 * The store on a directory that is already known - what a test builds, and what any caller holding a
	 * {@link Filepath} of its own can use. Production takes the {@link Directory} overload instead, because
	 * {@code Plugin.getPluginDirectory()} must not be called before it is needed.
	 */
	public PriceStore(final Gson gson, final Filepath dir)
	{
		this(gson, () -> dir);
		Objects.requireNonNull(dir, "dir");
	}

	/**
	 * The directory this store reads and writes, or {@code null} when it cannot be had at all - the disk refused,
	 * or the plugin has no {@code internalName}. Not created until the first save.
	 *
	 * <p>A failure is logged once per attempt and answered with null rather than thrown, on the store's standing
	 * failure policy: prices and a remembered bank are a convenience, and losing them must never take the panel
	 * down. Every path accessor below answers null in the same case, and every reader and writer here treats a
	 * null path as "no such file".
	 */
	@Nullable
	public Filepath dir()
	{
		final Filepath known = resolved;
		if (known != null)
		{
			return known;
		}

		final Filepath found;
		try
		{
			found = directory.get();
		}
		catch (IOException | RuntimeException e)
		{
			warnOnce(e);
			return null;
		}

		if (found == null)
		{
			warnOnce(null);
			return null;
		}
		resolved = found;
		// Once per session, at debug: the one line that answers "where did my files go?" after the addendum AD
		// move, and the only way to notice a directory that is not the one the user expects.
		log.debug("bank-portfolio-tracker: data directory is {}", found);
		return found;
	}

	/** The "no directory" warning, at most once per client session however many paths ask for one. */
	private void warnOnce(@Nullable final Exception cause)
	{
		if (warnedNoDirectory)
		{
			return;
		}
		warnedNoDirectory = true;
		if (cause == null)
		{
			log.warn("bank-portfolio-tracker: no data directory (nothing will be saved or loaded)");
		}
		else
		{
			log.warn("bank-portfolio-tracker: no data directory (nothing will be saved or loaded)", cause);
		}
	}

	/**
	 * One file in this store's directory, or null when there is no directory. The name is built by the callers
	 * below and {@link Filepath#join} checks it: a separator, a traversal or a Windows device name throws rather
	 * than escaping, which is the whole point of the wrapper (addendum AD).
	 */
	@Nullable
	private Filepath file(final String name)
	{
		final Filepath dir = dir();
		// joinSegment rather than join: every name here is ONE path component, and joinSegment is the overload
		// that says so - it refuses a separator outright where join would quietly resolve "a/b" into a
		// sub-directory. Nothing can reach it with a separator (safeProfile strips them, and the rest are
		// constants), which is exactly why the stricter call costs nothing.
		return dir == null ? null : dir.joinSegment(name);
	}

	/** {@code bank-<accountHash>-<profileType>.json} for one account on one profile (C17). */
	@Nullable
	public Filepath bankFile(final long accountHash, @Nullable final String profileType)
	{
		return file(BANK_PREFIX + accountHash + "-" + safeProfile(profileType) + JSON_SUFFIX);
	}

	/**
	 * {@code history-<accountHash>-<profileType>.json} for one account on one profile (addendum AU, plan 7.1 item
	 * 1) - named by the same two keys and the same {@code safeProfile} as {@link #bankFile}, because a Leagues or
	 * Deadman profile is a different bank and so a different history.
	 */
	@Nullable
	public Filepath historyFile(final long accountHash, @Nullable final String profileType)
	{
		return file(historyOwner(accountHash, profileType) + JSON_SUFFIX);
	}

	/** The history file's name without its suffix - also the key {@link #failedHistory} remembers an owner by. */
	private static String historyOwner(final long accountHash, @Nullable final String profileType)
	{
		return HISTORY_PREFIX + accountHash + "-" + safeProfile(profileType);
	}

	/**
	 * {@code baseline-<window name>.json} - one per window, so switching windows in the sidebar never has to
	 * re-fetch a baseline it already holds. Since K11 the file holds a guide-price map and its revision id; the
	 * method keeps its pre-K name because {@code PriceService} calls it and this wave does not rename across
	 * agent boundaries.
	 */
	@Nullable
	public Filepath bucketFile(final MovementWindow window)
	{
		Objects.requireNonNull(window, "window");
		return file(BASELINE_PREFIX + window.name() + JSON_SUFFIX);
	}

	/** {@value #MAPPING_FILE} (K11). */
	@Nullable
	public Filepath mappingFile()
	{
		return file(MAPPING_FILE);
	}

	/** {@value #REVINDEX_FILE} (L4). */
	@Nullable
	public Filepath revisionIndexFile()
	{
		return file(REVINDEX_FILE);
	}

	/** {@value #TRADED_LATEST_FILE} (T2). */
	@Nullable
	public Filepath tradedLatestFile()
	{
		return file(TRADED_LATEST_FILE);
	}

	/**
	 * {@code traded-<window name>.json} - one whole-day traded bucket per window (T2), holding the calendar day
	 * that window counts back to from the live snapshot (U1). The file is named for the WINDOW; which day it holds
	 * is inside it, and the two are only ever checked against each other by {@code PriceService}.
	 */
	@Nullable
	public Filepath tradedFile(final MovementWindow window)
	{
		Objects.requireNonNull(window, "window");
		return file(TRADED_PREFIX + window.name() + JSON_SUFFIX);
	}

	// ---- banks

	/**
	 * The remembered bank for one account and profile, or {@link BankSnapshot#EMPTY} when there is none, the
	 * file is corrupt (it is quarantined first) or it cannot be read (C18).
	 *
	 * <p>{@code normalize()} runs on whatever was read: the file may have been written by an older build or
	 * edited by hand, and every caller downstream (row computation, the panel) assumes a non-null item list and
	 * non-null names (C5).
	 */
	public BankSnapshot loadBank(final long accountHash, @Nullable final String profileType)
	{
		final BankSnapshot snapshot = readJson(bankFile(accountHash, profileType), BankSnapshot.class);
		if (snapshot == null)
		{
			return BankSnapshot.EMPTY;
		}
		snapshot.normalize();
		return snapshot;
	}

	/**
	 * Writes the snapshot to the file its own {@code accountHash} and {@code profileType} name, so a save and
	 * the {@link #loadBank} that follows it cannot disagree about the key. A null snapshot is ignored.
	 *
	 * <p><b>And so is a snapshot with no account behind it</b> (checker, 2026-09-09). A hash of 0 is this
	 * plugin's "nobody logged in" and -1 is the client's own "not logged in yet"
	 * ({@code com/jagex/oldscape/pub/OAuthApi.java:30-32}), so neither can be the key of a bank: the file would
	 * be {@code bank-0-STANDARD.json} or {@code bank--1-STANDARD.json}, which no login ever loads
	 * ({@link #loadBank} is always called with a live hash) and no sweep ever removes. Callers should not get
	 * here - both capture paths refuse first - and this is the backstop that makes "a bank file is one
	 * account's" a property of the store rather than a habit of its callers.
	 */
	public void saveBank(@Nullable final BankSnapshot snapshot)
	{
		if (snapshot == null)
		{
			return;
		}
		if (!BankSnapshot.isAccount(snapshot.accountHash))
		{
			log.debug("bank-portfolio-tracker: not saving a bank stamped with account {} - it has no owner",
				snapshot.accountHash);
			return;
		}
		write(bankFile(snapshot.accountHash, snapshot.profileType), snapshot);
	}

	// ---- the net-worth history (addendum AU)

	/**
	 * The version of the history file's shape this build writes. Bump it when a stored field's MEANING changes, AND
	 * when a field is ADDED - this build rewrites only the keys it knows, so a newer build's extra field would be
	 * lost the next time an older build wrote the file, while a higher number makes the older build leave it alone -
	 * and teach {@link #migrateHistory} the step; a file with a HIGHER number is a newer build's and is left alone -
	 * which is what an OLDER build does with the schema 2 this build writes: "written by a newer build - leaving it
	 * alone". Schema 2 is 1.0.9 part 3's: every entry's {@code card} and {@code guide} hold ten cells, the two new
	 * ones being the Grand Exchange offers' ({@link BankHistoryPoint#GE_TRADEABLE}, {@link BankHistoryPoint#GE_CASH}).
	 */
	static final int HISTORY_SCHEMA = 2;

	/** How many cells a schema 1 entry holds: the eight of addendum AU, before 1.0.9 part 3 added the offers' two. */
	private static final int SCHEMA_1_CELLS = 8;

	private static final String POINTS_KEY = "points";
	/**
	 * 1.0.9 part 5: the root key holding {@link BankHistorySeries#freshFrom()} as an ISO date. Written only while the
	 * series has a real one; a series whose every day is legacy ({@link LocalDate#MAX}) is a migrated file nothing has
	 * recorded into yet, which is read from its old schema and never written under this key.
	 */
	private static final String FRESH_FROM_KEY = "freshFrom";
	private static final String DAY_KEY = "day";
	private static final String READ_AT_KEY = "readAtMillis";
	private static final String BANK_AT_KEY = "bankAtMillis";
	private static final String CARD_KEY = "card";
	private static final String GUIDE_KEY = "guide";
	/** Every key a history document holds at its top level, and every key an entry holds, in this build. */
	private static final Set<String> HISTORY_ROOT_KEYS = new HashSet<>(Arrays.asList(SCHEMA_KEY, POINTS_KEY,
		FRESH_FROM_KEY));
	private static final Set<String> HISTORY_ENTRY_KEYS = new HashSet<>(Arrays.asList(DAY_KEY, READ_AT_KEY, BANK_AT_KEY,
		CARD_KEY, GUIDE_KEY));

	/**
	 * One account's history as it is on disk now (addendum AU, plan 7.1 item 5; contract section 4), with the
	 * outcome of the read - which, unlike every other load here, is not folded into one "empty" answer:
	 * <ul>
	 * <li>{@link BankHistoryLoad.State#MISSING} - no such file (or no owner, a hash of 0 or -1, which never has one):
	 * start a series.</li>
	 * <li>{@link BankHistoryLoad.State#LOADED} - the file read and parsed. An entry that does not parse is skipped
	 * and the rest kept; an older schema is migrated, never read as empty.</li>
	 * <li>{@link BankHistoryLoad.State#FAILED} - the file exists and could not be read (locked, a directory in the
	 * way; it is left exactly where it is), or it could not be parsed at all (moved aside as
	 * {@code .corrupt-<millis>}), or a newer build wrote it (left alone), or there is no data directory. After a
	 * FAILED read of a real file nothing is written to it for the rest of the session, and every later load of
	 * that owner answers FAILED at once, without reading or logging again (review finding H8: one WARN).</li>
	 * </ul>
	 * Never throws. Reads the file every time otherwise; nothing is cached.
	 */
	public BankHistoryLoad loadBankHistory(final long accountHash, @Nullable final String profileType)
	{
		if (!BankSnapshot.isAccount(accountHash))
		{
			return BankHistoryLoad.missing();
		}
		if (failedHistory.contains(historyOwner(accountHash, profileType)))
		{
			log.debug("bank-portfolio-tracker: not reading the history again - its file failed to read this session");
			return BankHistoryLoad.failed();
		}
		final Filepath file = historyFile(accountHash, profileType);
		final BankHistoryLoad load = readHistory(file);
		if (file != null && load.state() == BankHistoryLoad.State.FAILED)
		{
			failedHistory.add(historyOwner(accountHash, profileType));
		}
		return load;
	}

	/**
	 * Records one reading (addendum AU, plan 7.1 items 4 and 7; contract section 4): reads the file FRESH, puts
	 * {@code point} in as its day's reading (last wins for that day), drops every reading dated after
	 * {@code today}, and replaces the file atomically through {@link #writeAtomic(Filepath, String)}. The series in
	 * a caller's memory is never written straight out, so a second client's days are kept.
	 *
	 * <p>Writes NOTHING and answers false when: there is no owner, a hash of 0 or -1 (as {@link #saveBank}); the point
	 * is dated after {@code today}; there is no data directory; this session already failed to read the owner's file;
	 * the fresh read FAILS now - which is then remembered for the rest of the session; or the clock is BEHIND the
	 * file - at least as many stored readings are dated after {@code today} as on or before it, the new point
	 * counted (review finding H1: a clock set back to 2000 must not prune years of readings as "the future"; a
	 * few stray readings of a clock that WAS ahead are still dropped, plan 7.1 item 7). Writes nothing and answers
	 * true when the merge equals what was read (the point is already there). Otherwise answers whether the write
	 * succeeded. Never throws.
	 *
	 * <p>The fresh start (1.0.9 part 5): when the file read is a migrated one - its series answers
	 * {@link LocalDate#MAX} from {@link BankHistorySeries#freshFrom()} - the series written gets this point's day as
	 * its {@code freshFrom}, so the days before it stay on disk and are hidden from the tab until the reader asks for
	 * them. Later recordings keep the stored date.
	 *
	 * <p>A file that holds something the series does not ({@link BankHistoryLoad#lossy()}: an entry that did not
	 * parse, two entries for one day, a key this build does not know) is COPIED aside as
	 * {@code <name>.corrupt-<millis>} before its first rewrite of the session (review finding H7), and nothing is
	 * written when that copy fails. The write itself is durable: the temp file is written through
	 * {@code DSYNC} before the atomic move, so a power cut cannot leave the one file here that is not a cache as a
	 * file of zeros (review finding H6).
	 *
	 * @param point a reading, filed under its own {@link BankHistoryPoint#day()}
	 * @param today the caller's LOCAL today
	 * @return true when the file holds the point once this returns
	 */
	public boolean recordBankHistory(final long accountHash, @Nullable final String profileType,
		@Nullable final BankHistoryPoint point, @Nullable final LocalDate today)
	{
		if (point == null || today == null)
		{
			return false;
		}
		if (!BankSnapshot.isAccount(accountHash))
		{
			log.debug("bank-portfolio-tracker: not recording history for account {} - it has no owner", accountHash);
			return false;
		}
		if (point.day().isAfter(today))
		{
			log.debug("bank-portfolio-tracker: not recording a reading of {} on {} - it is in the future",
				point.day(), today);
			return false;
		}

		final String owner = historyOwner(accountHash, profileType);
		synchronized (historyLock)
		{
			if (failedHistory.contains(owner))
			{
				log.debug("bank-portfolio-tracker: not recording history - its file failed to read this session");
				return false;
			}
			final Filepath file = historyFile(accountHash, profileType);
			if (file == null)
			{
				log.debug("bank-portfolio-tracker: no data directory, so no history was recorded");
				return false;
			}

			final BankHistoryLoad read = readHistory(file);
			if (read.state() == BankHistoryLoad.State.FAILED)
			{
				failedHistory.add(owner);
				return false;
			}

			// 1.0.9 part 5: the first recording after a migration is the first day 1.0.9 counted the offers, so it is where
			// the fresh start stands; a reading on the newest old day's own date replaces that day and is fresh itself.
			BankHistorySeries added = read.series().with(point);
			if (LocalDate.MAX.equals(read.series().freshFrom()))
			{
				added = added.withFreshFrom(point.day());
			}
			final BankHistorySeries merged = added.upTo(today);
			final int ahead = added.size() - merged.size();
			if (ahead > 0 && ahead >= merged.size())
			{
				log.debug("bank-portfolio-tracker: not recording a reading of {} - the clock is behind {} of the stored"
					+ " readings", today, ahead);
				return false;
			}
			if (merged.equals(read.series()))
			{
				return true;
			}
			if (read.lossy() && !backedUpHistory.contains(owner))
			{
				try
				{
					file.copyTo(file.getParent().joinSegment(file.getFileName() + CORRUPT_SUFFIX
						+ System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
					backedUpHistory.add(owner);
					log.warn("{} held entries this build cannot keep - a copy of it was kept before rewriting it", file);
				}
				catch (IOException | RuntimeException e)
				{
					warnWriteOnce(owner, "could not copy {} aside, so it was not rewritten", file, e);
					return false;
				}
			}
			try
			{
				final Filepath parent = file.getParent();
				if (!parent.isDirectory())
				{
					parent.createDirectories();
				}
				writeAtomicDurably(file, historyJson(merged));
				return true;
			}
			catch (IOException | RuntimeException e)
			{
				warnWriteOnce(owner, "saving {} failed", file, e);
				return false;
			}
		}
	}

	/** A history write that failed: WARN the first time for this owner this session, debug after (R5). */
	private void warnWriteOnce(final String owner, final String message, final Filepath file, final Exception e)
	{
		if (warnedHistoryWrite.add(owner))
		{
			log.warn(message, file, e);
		}
		else
		{
			log.debug(message + " (again; it is retried with the next reading)", file, e);
		}
	}

	/** The three-outcome read behind {@link #loadBankHistory} and {@link #recordBankHistory}. */
	private BankHistoryLoad readHistory(@Nullable final Filepath file)
	{
		if (file == null)
		{
			// dir() has already warned once; nothing could be read, so nothing may be assumed missing either.
			return BankHistoryLoad.failed();
		}

		// No exists() check first (review finding H2): Files.exists answers false when it cannot TELL - a transient
		// error or a refused attribute read - and a file read as missing is a file the next record writes a one-point
		// document over. Only the open's own "no such file" is MISSING; every other failure is FAILED.
		final String json;
		try
		{
			json = readAll(file);
		}
		catch (NoSuchFileException e)
		{
			return BankHistoryLoad.missing();
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("could not read {} (keeping it, and writing nothing over it this session)", file, e);
			return BankHistoryLoad.failed();
		}

		JsonObject root;
		final int schema;
		try
		{
			final JsonElement tree = gson.fromJson(json, JsonElement.class);
			if (tree == null || !tree.isJsonObject())
			{
				throw new JsonParseException("not a history document");
			}
			root = tree.getAsJsonObject();
			final JsonElement stamped = root.get(SCHEMA_KEY);
			schema = stamped == null || stamped.isJsonNull() ? 0 : stamped.getAsInt();
			final JsonElement points = root.get(POINTS_KEY);
			if (points != null && !points.isJsonNull() && !points.isJsonArray())
			{
				throw new JsonParseException("points is not a list");
			}
		}
		catch (RuntimeException e)
		{
			// JsonParseException, or a schema that is not a number: unusable as a whole. Moved aside rather than
			// read as empty, because an empty read would let the next record write a one-point file over it.
			log.warn("could not parse {} (keeping a backup, and writing nothing over it this session)", file, e);
			quarantine(file);
			return BankHistoryLoad.failed();
		}

		if (schema > HISTORY_SCHEMA)
		{
			log.warn("{} was written by a newer build (schema {}) - leaving it alone", file, schema);
			return BankHistoryLoad.failed();
		}
		if (schema < HISTORY_SCHEMA)
		{
			log.debug("bank-portfolio-tracker: migrating {} from schema {}", file, schema);
			root = migrateHistory(root, schema);
		}

		boolean unknownKeys = !HISTORY_ROOT_KEYS.containsAll(root.keySet());
		final boolean migrated = schema < HISTORY_SCHEMA;
		LocalDate freshFrom = null;
		if (!migrated)
		{
			final JsonElement stamped = root.get(FRESH_FROM_KEY);
			if (stamped != null && !stamped.isJsonNull())
			{
				freshFrom = stamped.isJsonPrimitive() ? parseDay(stamped.getAsString()) : null;
				// A fresh start that does not read as a date is not understood: nothing is hidden by it, and the file is
				// copied aside before its first rewrite, which drops it.
				unknownKeys |= freshFrom == null;
			}
		}
		final JsonElement points = root.get(POINTS_KEY);
		if (points == null || points.isJsonNull())
		{
			return unknownKeys ? BankHistoryLoad.loadedLossy(BankHistorySeries.EMPTY)
				: BankHistoryLoad.loaded(BankHistorySeries.EMPTY);
		}
		final List<BankHistoryPoint> read = new ArrayList<>();
		int skipped = 0;
		for (final JsonElement entry : points.getAsJsonArray())
		{
			final BankHistoryPoint point = historyEntry(entry);
			if (point == null)
			{
				skipped++;
			}
			else
			{
				read.add(point);
				unknownKeys |= !HISTORY_ENTRY_KEYS.containsAll(entry.getAsJsonObject().keySet());
			}
		}
		if (skipped > 0)
		{
			log.debug("bank-portfolio-tracker: skipped {} unreadable entries of {}", skipped, file);
		}
		BankHistorySeries series = BankHistorySeries.of(read);
		// 1.0.9 part 5: a migrated file's days were all recorded before 1.0.9 - until a recording stamps a fresh start,
		// every load of it answers the same. A file with no days has nothing to hide.
		series = series.withFreshFrom(migrated ? LocalDate.MAX : freshFrom);
		// Anything the series does not hold - a skipped entry, a second entry for one day, a key this build does not
		// know - would be gone after a rewrite; recordBankHistory copies such a file aside first (H7).
		return skipped > 0 || unknownKeys || series.size() != read.size() ? BankHistoryLoad.loadedLossy(series)
			: BankHistoryLoad.loaded(series);
	}

	/**
	 * Brings a document written under an OLDER schema to this build's shape (plan 7.1 item 5: migrated, never read
	 * as empty). Schema 0 is a document with no {@code schema} key - the same shape as schema 1, so there was nothing
	 * to change. The next schema adds its step here.
	 *
	 * <p><b>1 -> 2</b> (1.0.9 part 3): schema 1 entries hold eight cells and schema 2 holds ten, the two new ones for
	 * the Grand Exchange offers. The step is the padding {@link #cells} performs as each entry is read - an
	 * eight-cell array is read as ten with the new cells at zero, which is the truth about a day recorded before the
	 * offers were counted, and nothing is lost by it, so no file is copied aside for it. The document itself is NOT
	 * rewritten by a load (a read never writes): a schema 1 file stays schema 1 on disk until the next recording that
	 * changes it, which writes schema 2 with ten cells in every entry, the earlier ones padded.
	 *
	 * <p>The padding makes those days readable, not right: they did not count the offers. So a migrated file's days are
	 * the LEGACY days of 1.0.9 part 5 - {@link #readHistory} answers a series whose {@link BankHistorySeries#freshFrom()}
	 * is {@link LocalDate#MAX} (an empty file has nothing to hide: null), and the first recording after it stamps the
	 * day of that reading into the {@code freshFrom} root key. Nothing is deleted or moved: the days stay in the file,
	 * and the tab shows them again when the reader asks.
	 */
	private static JsonObject migrateHistory(final JsonObject root, final int schema)
	{
		// 0 -> 1: the same shape; the number alone was missing.
		// 1 -> 2: the same document; cells() pads each entry's eight cells to ten as it reads them.
		return root;
	}

	/** One stored entry, or null when it does not parse - it is then skipped and the rest of the file kept. */
	@Nullable
	private static BankHistoryPoint historyEntry(@Nullable final JsonElement entry)
	{
		if (entry == null || !entry.isJsonObject())
		{
			return null;
		}
		try
		{
			final JsonObject o = entry.getAsJsonObject();
			final LocalDate day = parseDay(o.get(DAY_KEY).getAsString());
			final long[] card = cells(o.get(CARD_KEY));
			if (day == null || card == null)
			{
				return null;
			}
			final JsonElement guideElement = o.get(GUIDE_KEY);
			final long[] guide = guideElement == null || guideElement.isJsonNull() ? null : cells(guideElement);
			if (guideElement != null && !guideElement.isJsonNull() && guide == null)
			{
				return null;
			}
			return new BankHistoryPoint(day, o.get(READ_AT_KEY).getAsLong(), o.get(BANK_AT_KEY).getAsLong(), card,
				guide);
		}
		catch (RuntimeException e)
		{
			// A missing field (NullPointerException), a word where a number belongs, a wrong-length array.
			return null;
		}
	}

	/**
	 * Exactly {@link BankHistoryPoint#CELLS} numbers - or exactly {@link #SCHEMA_1_CELLS}, a schema 1 entry written
	 * before the Grand Exchange offers were counted, which is padded with zeros into the cells that did not exist
	 * yet - or null for any other length.
	 */
	@Nullable
	private static long[] cells(@Nullable final JsonElement element)
	{
		if (element == null || !element.isJsonArray())
		{
			return null;
		}
		final JsonArray array = element.getAsJsonArray();
		if (array.size() != BankHistoryPoint.CELLS && array.size() != SCHEMA_1_CELLS)
		{
			return null;
		}
		final long[] cells = new long[BankHistoryPoint.CELLS];
		for (int i = 0; i < array.size(); i++)
		{
			cells[i] = array.get(i).getAsLong();
		}
		return cells;
	}

	/**
	 * The file's text (contract section 4): {@code schema} first, then {@code freshFrom} when the series has one
	 * (1.0.9 part 5), then {@code points}, each entry
	 * {@code day, readAtMillis, bankAtMillis, card, guide} in that order, {@code guide} left out when it equals
	 * {@code card}. Built as a tree so the shape does not depend on how the injected Gson treats nulls.
	 */
	private String historyJson(final BankHistorySeries series)
	{
		final JsonObject root = new JsonObject();
		root.addProperty(SCHEMA_KEY, HISTORY_SCHEMA);
		final LocalDate freshFrom = series.freshFrom();
		if (freshFrom != null && !LocalDate.MAX.equals(freshFrom))
		{
			root.addProperty(FRESH_FROM_KEY, freshFrom.toString());
		}
		final JsonArray points = new JsonArray();
		for (final BankHistoryPoint point : series.points())
		{
			final JsonObject entry = new JsonObject();
			entry.addProperty(DAY_KEY, point.day().toString());
			entry.addProperty(READ_AT_KEY, point.readAtMillis());
			entry.addProperty(BANK_AT_KEY, point.bankAtMillis());
			final JsonArray card = new JsonArray();
			final JsonArray guide = new JsonArray();
			for (int i = 0; i < BankHistoryPoint.CELLS; i++)
			{
				card.add(point.card(i));
				guide.add(point.guide(i));
			}
			entry.add(CARD_KEY, card);
			if (point.hasGuide())
			{
				entry.add(GUIDE_KEY, guide);
			}
			points.add(entry);
		}
		root.add(POINTS_KEY, points);
		return gson.toJson(root);
	}

	// ---- baselines

	/** The persisted baseline for one window, or {@link PriceMap#EMPTY} (C18 rules). */
	public PriceMap loadBucket(final MovementWindow window)
	{
		return readMap(bucketFile(window));
	}

	/** Replaces one window's baseline file. A null map is ignored. */
	public void saveBucket(final MovementWindow window, @Nullable final PriceMap map)
	{
		if (map == null)
		{
			return;
		}
		write(bucketFile(window), map.toDto());
	}

	// ---- the id to wiki-name mapping (K11)

	/**
	 * The persisted id-to-wiki-name table WITH the moment it was fetched, or an empty map stamped 0 when there is
	 * none, it is corrupt (quarantined first) or it cannot be read. Never null; the returned map is unmodifiable.
	 *
	 * <p>The stamp comes back beside the names because both are one parse of the same 150 KB file and the caller
	 * needs both at start-up: reading the file twice - once for the names, once for the stamp - was 300 KB of JSON
	 * for one {@code long}.
	 *
	 * <p>An entry whose key is not an integer, or whose name is missing, is skipped rather than thrown, for the
	 * same reason every other load here is forgiving: this runs on the service's executor at start-up and a
	 * damaged cache file must never stop the panel from opening (D9, fail soft).
	 */
	public Stamped<Map<Integer, String>> loadMapping()
	{
		final MappingDto dto = readJson(mappingFile(), MappingDto.class);
		if (dto == null)
		{
			return Stamped.none(Collections.<Integer, String>emptyMap());
		}
		if (dto.names == null)
		{
			// No names, but the document parsed: its stamp still says how old the nothing is, which is what makes
			// the weekly rule refetch on schedule rather than on every start-up.
			return new Stamped<>(Collections.<Integer, String>emptyMap(), dto.fetchedAtMillis);
		}

		final Map<Integer, String> names = new LinkedHashMap<>();
		for (final Map.Entry<String, String> entry : dto.names.entrySet())
		{
			final Integer id = parseId(entry.getKey());
			if (id != null && entry.getValue() != null && !entry.getValue().isEmpty())
			{
				names.put(id, entry.getValue());
			}
		}
		return new Stamped<>(Collections.unmodifiableMap(names), dto.fetchedAtMillis);
	}

	/**
	 * Replaces {@value #MAPPING_FILE} with this table and stamps it.
	 *
	 * <p>A null OR EMPTY table is ignored, which is not the usual "null is ignored" politeness: writing an empty
	 * table would stamp it as freshly fetched, and the weekly cadence (K5) would then leave every bank item
	 * unpriced for a week. {@code GuidePriceClient.parseMapping} already refuses to produce an empty table, so
	 * this is the second lock on the same door.
	 *
	 * @param names           item id to wiki name
	 * @param fetchedAtMillis when it was fetched, epoch millis - what the weekly rule compares against
	 */
	public void saveMapping(@Nullable final Map<Integer, String> names, final long fetchedAtMillis)
	{
		if (names == null || names.isEmpty())
		{
			return;
		}

		final MappingDto dto = new MappingDto();
		dto.schema = MappingDto.SCHEMA;
		dto.fetchedAtMillis = fetchedAtMillis;
		dto.names = new HashMap<>(names.size());
		for (final Map.Entry<Integer, String> entry : names.entrySet())
		{
			if (entry.getKey() != null && entry.getValue() != null)
			{
				dto.names.put(Integer.toString(entry.getKey()), entry.getValue());
			}
		}
		write(mappingFile(), dto);
	}

	// ---- the guide page's revision history (L4)

	/**
	 * The persisted revision history WITH the moment it was fetched, newest first, or an empty list stamped 0 when
	 * there is none, it is corrupt (quarantined first) or it cannot be read. Never null; the returned list is
	 * unmodifiable. One parse answers both halves - see {@link #loadMapping()}.
	 *
	 * <p>Sorted here rather than trusted, for the same reason {@code GuidePriceClient.parseRevisionIndex} sorts:
	 * a hand-edited file must not be able to reorder a dev-bridge echo. Baseline SELECTION does not depend on the
	 * order at all ({@link RevisionRef#pickThen} scans the list), so a damaged file costs at worst the entries it
	 * damaged. An entry with no usable revision id or no edit time is skipped rather than thrown, for the same
	 * reason every other load here is forgiving: this runs on the service's executor at start-up and a damaged
	 * cache file must never stop the panel from opening (D9, fail soft).
	 */
	public Stamped<List<RevisionRef>> loadRevisionIndex()
	{
		final RevIndexDto dto = readJson(revisionIndexFile(), RevIndexDto.class);
		if (dto == null)
		{
			return Stamped.none(Collections.<RevisionRef>emptyList());
		}
		if (dto.revisions == null)
		{
			// The stamp of a document with no revisions still reads - see {@link #loadMapping()}.
			return new Stamped<>(Collections.<RevisionRef>emptyList(), dto.fetchedAtMillis);
		}

		final List<RevisionRef> index = new ArrayList<>(dto.revisions.size());
		for (final RevEntry entry : dto.revisions)
		{
			if (entry != null && entry.revId > 0L && entry.editSeconds > 0L)
			{
				index.add(new RevisionRef(entry.revId, entry.editSeconds, entry.user, entry.comment));
			}
		}

		index.sort(RevisionRef.NEWEST_FIRST);
		return new Stamped<>(Collections.unmodifiableList(index), dto.fetchedAtMillis);
	}

	/**
	 * Replaces {@value #REVINDEX_FILE} with this history and stamps it.
	 *
	 * <p>A null OR EMPTY history is ignored, which is not the usual "null is ignored" politeness: writing an
	 * empty index would stamp it as freshly fetched, and the six-hourly cadence (L4) would then leave every
	 * window without a baseline for six hours. {@code GuidePriceClient.parseRevisionIndex} already refuses to
	 * produce an empty index, so this is the second lock on the same door.
	 *
	 * @param index           the revision history, newest first; null entries are dropped
	 * @param fetchedAtMillis when it was fetched, epoch millis - what the six-hour rule compares against
	 */
	public void saveRevisionIndex(@Nullable final List<RevisionRef> index, final long fetchedAtMillis)
	{
		if (index == null || index.isEmpty())
		{
			return;
		}

		final RevIndexDto dto = new RevIndexDto();
		dto.schema = RevIndexDto.SCHEMA;
		dto.fetchedAtMillis = fetchedAtMillis;
		dto.revisions = new ArrayList<>(index.size());
		for (final RevisionRef ref : index)
		{
			if (ref == null)
			{
				continue;
			}

			final RevEntry entry = new RevEntry();
			entry.revId = ref.revId();
			entry.editSeconds = ref.editSeconds();
			entry.user = ref.user();
			entry.comment = ref.comment();
			dto.revisions.add(entry);
		}

		if (dto.revisions.isEmpty())
		{
			return;
		}
		write(revisionIndexFile(), dto);
	}

	// ---- the traded feeds (addendum T, line T2)

	/**
	 * The stored {@code /latest} snapshot WITH the moment it was fetched, or an empty map stamped 0 when there is
	 * none, it is corrupt (quarantined first), it cannot be read, or it was written by an older build
	 * ({@link TradedLatestDto#schema} below {@link TradedLatestDto#SCHEMA}). Never null; the map is unmodifiable.
	 *
	 * <p>The stamp is what T7's six-hour rule compares: a snapshot older than that is not used at all, and every
	 * row falls back to the guide price. Refusing an older SHAPE outright, rather than reading it and hoping, is
	 * the same rule {@code PriceService.guideOrEmpty} applies to a baseline - a field that means something else
	 * than it used to is a file this build must not believe, and the cost of refusing is one refetch.
	 *
	 * <p>An entry whose key is not an integer, or whose sides are truncated, is skipped rather than thrown: this
	 * runs on the service's executor and a damaged cache file must never stop the panel from opening (D9).
	 */
	public Stamped<Map<Integer, TradedPriceClient.Quote>> loadTradedLatest()
	{
		final TradedLatestDto dto = readJson(tradedLatestFile(), TradedLatestDto.class);
		if (dto == null || dto.schema < TradedLatestDto.SCHEMA)
		{
			return Stamped.none(Collections.<Integer, TradedPriceClient.Quote>emptyMap());
		}
		if (dto.quotes == null)
		{
			return new Stamped<>(Collections.<Integer, TradedPriceClient.Quote>emptyMap(), dto.fetchedAtMillis);
		}

		final Map<Integer, TradedPriceClient.Quote> quotes = new LinkedHashMap<>();
		for (final Map.Entry<String, long[]> entry : dto.quotes.entrySet())
		{
			final long[] sides = entry.getValue();
			final Integer id = parseId(entry.getKey());
			if (id == null || sides == null || sides.length < 4)
			{
				continue;
			}
			quotes.put(id, new TradedPriceClient.Quote(PriceMapDto.decode(sides[0]), Math.max(0L, sides[1]),
				PriceMapDto.decode(sides[2]), Math.max(0L, sides[3])));
		}
		return new Stamped<>(Collections.unmodifiableMap(quotes), dto.fetchedAtMillis);
	}

	/**
	 * Replaces {@value #TRADED_LATEST_FILE} with this snapshot and stamps it.
	 *
	 * <p>A null OR EMPTY snapshot is ignored, for the reason {@link #saveMapping} spells out: writing an empty
	 * feed would stamp it as freshly fetched, and every row would sit on the guide price for six hours with
	 * nothing due to be refetched. {@code TradedPriceClient.parseLatest} already refuses to produce an empty
	 * feed, so this is the second lock on the same door.
	 *
	 * @param quotes          item id to its newest traded pair
	 * @param fetchedAtMillis when it was fetched, epoch millis - what T7's six-hour rule compares against
	 */
	public void saveTradedLatest(@Nullable final Map<Integer, TradedPriceClient.Quote> quotes, final long fetchedAtMillis)
	{
		if (quotes == null || quotes.isEmpty())
		{
			return;
		}

		final TradedLatestDto dto = new TradedLatestDto();
		dto.schema = TradedLatestDto.SCHEMA;
		dto.fetchedAtMillis = fetchedAtMillis;
		dto.quotes = new HashMap<>(quotes.size());
		for (final Map.Entry<Integer, TradedPriceClient.Quote> entry : quotes.entrySet())
		{
			final TradedPriceClient.Quote quote = entry.getValue();
			if (entry.getKey() == null || quote == null)
			{
				continue;
			}
			dto.quotes.put(Integer.toString(entry.getKey()), new long[]{
				PriceMapDto.encode(quote.buy()), quote.buySeconds(),
				PriceMapDto.encode(quote.sell()), quote.sellSeconds()});
		}
		write(tradedLatestFile(), dto);
	}

	/**
	 * The stored traded bucket for one window, or {@link TradedDay#EMPTY} when there is none, it is corrupt, it
	 * cannot be read, it names no day, or an older build wrote it. Never null.
	 *
	 * <p>The DAY is part of the document and not derived from the window: a bucket is only usable while it names
	 * the day the LIVE calendar wants for that window (U1, and U2's one day further back), and a file that cannot
	 * name its own day can never be checked against one.
	 */
	public TradedDay loadTradedDay(final MovementWindow window)
	{
		final TradedDayDto dto = readJson(tradedFile(window), TradedDayDto.class);
		if (dto == null || dto.schema < TradedDayDto.SCHEMA)
		{
			return TradedDay.EMPTY;
		}
		final LocalDate day = parseDay(dto.day);
		if (day == null)
		{
			return TradedDay.EMPTY;
		}

		final Map<Integer, TradedPriceClient.Bucket> buckets = new LinkedHashMap<>();
		if (dto.buckets != null)
		{
			for (final Map.Entry<String, long[]> entry : dto.buckets.entrySet())
			{
				final long[] sides = entry.getValue();
				final Integer id = parseId(entry.getKey());
				if (id == null || sides == null || sides.length < 4)
				{
					continue;
				}
				buckets.put(id, new TradedPriceClient.Bucket(PriceMapDto.decode(sides[0]), Math.max(0L, sides[1]),
					PriceMapDto.decode(sides[2]), Math.max(0L, sides[3])));
			}
		}
		return new TradedDay(day, buckets, dto.fetchedAtMillis);
	}

	/**
	 * Replaces one window's traded bucket file. A null bucket set, an empty one and a null day are all ignored,
	 * for {@link #saveTradedLatest}'s reason: a stored empty day would be believed and never refetched, and every
	 * window of every row would silently fall back to the guide.
	 *
	 * @param window          the window this bucket serves
	 * @param day             the UTC calendar day the bucket is REALLY of - {@code liveDay - N} for that window,
	 *                        or the day before it when the wiki had not closed that one yet (U2)
	 * @param buckets         item id to that day's traded averages and volumes
	 * @param fetchedAtMillis when it was fetched, epoch millis
	 */
	public void saveTradedDay(final MovementWindow window, @Nullable final LocalDate day,
		@Nullable final Map<Integer, TradedPriceClient.Bucket> buckets, final long fetchedAtMillis)
	{
		if (day == null || buckets == null || buckets.isEmpty())
		{
			return;
		}

		final TradedDayDto dto = new TradedDayDto();
		dto.schema = TradedDayDto.SCHEMA;
		dto.fetchedAtMillis = fetchedAtMillis;
		dto.day = day.toString();
		dto.buckets = new HashMap<>(buckets.size());
		for (final Map.Entry<Integer, TradedPriceClient.Bucket> entry : buckets.entrySet())
		{
			final TradedPriceClient.Bucket bucket = entry.getValue();
			if (entry.getKey() == null || bucket == null)
			{
				continue;
			}
			dto.buckets.put(Integer.toString(entry.getKey()), new long[]{
				PriceMapDto.encode(bucket.avgHigh()), bucket.highVolume(),
				PriceMapDto.encode(bucket.avgLow()), bucket.lowVolume()});
		}
		write(tradedFile(window), dto);
	}

	/** An ISO date as a {@link LocalDate}, or null when the text names none (a hand-edited or older file). */
	@Nullable
	private static LocalDate parseDay(@Nullable final String text)
	{
		if (text == null || text.isEmpty())
		{
			return null;
		}
		try
		{
			return LocalDate.parse(text);
		}
		catch (final DateTimeParseException e)
		{
			return null;
		}
	}

	// ---- the addendum-K sweep

	/**
	 * Deletes the files addendum K left behind, at startUp (K11). Returns how many went, for the plugin's log.
	 *
	 * <p>Four rules, in order:
	 * <ol>
	 * <li>{@value #LEGACY_LATEST_FILE} - the {@code /latest} spot map. That endpoint is not called any more.</li>
	 * <li>any {@code baseline-<X>.json} whose {@code X} is not the name of a current {@link MovementWindow} -
	 * which is exactly {@code baseline-H1.json} and {@code baseline-H24.json}. Matched against
	 * {@link MovementWindow#name()} and deliberately NOT against {@link MovementWindow#parse(String)}, which
	 * maps the legacy spelling "H24" onto D1 and would keep the file alive under the wrong window.</li>
	 * <li>any surviving baseline whose stored {@code revId} is 0 - a map written before the guide switch, when
	 * it held real-time trade averages. {@code baseline-D7.json} is the one that can be in this state, since D7
	 * is a window in both eras, and its trade content is precisely what showed a 1,086 gp hat as +12845.5 %.
	 * Every guide baseline carries a revision id in the fifteen millions, so the test is unambiguous.</li>
	 * <li>any surviving baseline whose stored {@link PriceMapDto#schema} is below {@link PriceMapDto#SCHEMA} - a
	 * file written by an older build, whose fields need not mean what this one reads them as (B028; addendum K
	 * stored the revision's SAVE time in {@code bucketSeconds} where L stores the table's own
	 * {@code %LAST_UPDATE%}, and both eras write a revision id, so rule 3 cannot see the difference).
	 * {@code PriceService.guideOrEmpty} refuses such a map on the way in as well - this is what stops it being
	 * read and refused again on every launch until a fetch replaces it (checker, 2026-09-09).</li>
	 * </ol>
	 *
	 * <p>A baseline whose bytes cannot be READ is exempt from rules 3 and 4 and kept: an unreadable file says
	 * nothing at all about its content (see {@link #readBaselineHeader}).
	 *
	 * <p>Two more rules for the TRADED files of addendum T, on exactly the same reasoning:
	 * <ol start="5">
	 * <li>any {@code traded-<X>.json} whose {@code X} is neither a current {@link MovementWindow} name nor the
	 * word {@code latest} - a window this build dropped, or a stray;</li>
	 * <li>any surviving traded file whose stored {@code schema} is below this build's - a document an older
	 * build wrote, whose fields need not mean what this one reads them as. {@link #loadTradedLatest()} and
	 * {@link #loadTradedDay(MovementWindow)} refuse such a file on the way in as well; this is what stops it
	 * being read and refused again on every launch until a fetch replaces it.</li>
	 * </ol>
	 * {@value #TRADED_LATEST_FILE} is deliberately NOT judged by rule 5: it shares the prefix and is not a
	 * window, and sweeping it away every launch would cost a {@code /latest} fetch at every start-up.
	 *
	 * <p>No rule matches a {@code history-*} file (addendum AU) - nor a bank file, a quarantined backup or a temp
	 * file: the history is the one thing here that cannot be fetched again, and {@code PriceStoreHistoryTest} pins
	 * that the sweep leaves it and its {@code .corrupt-} backups alone.
	 *
	 * <p>A file that cannot be deleted is logged and left; the next launch tries again, and a stale baseline is
	 * refetched within six hours anyway (K5). Nothing here throws.
	 */
	public int deleteStaleFiles()
	{
		final Filepath dir = dir();
		if (dir == null)
		{
			return 0;
		}

		final List<Filepath> files = new ArrayList<>();
		try (Stream<Filepath> walk = dir.walk(1))
		{
			// Depth 1 is this directory and its children; the directory itself comes back first and is not a file
			// to judge. There are no sub-directories to descend into - the dev-mode shots folder is the only one,
			// and no rule below can match it.
			walk.forEach(entry ->
			{
				if (!dir.equals(entry))
				{
					files.add(entry);
				}
			});
		}
		catch (IOException | RuntimeException e)
		{
			// No directory yet (a fresh install), or it is not readable: nothing to sweep either way.
			return 0;
		}

		int deleted = 0;
		for (final Filepath file : files)
		{
			final String name = file.getFileName();
			if (!isStale(file, name))
			{
				continue;
			}

			try
			{
				file.delete();
				deleted++;
				log.debug("bank-portfolio-tracker: removed the stale file {}", file);
			}
			catch (NoSuchFileException e)
			{
				// Gone between the walk and the delete: somebody else's sweep, and not this one's to count.
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("could not delete the stale file {}", file, e);
			}
		}
		return deleted;
	}

	/** The six rules of {@link #deleteStaleFiles()}, in order. */
	private boolean isStale(final Filepath file, final String name)
	{
		if (LEGACY_LATEST_FILE.equals(name))
		{
			return true;
		}

		if (TRADED_LATEST_FILE.equals(name))
		{
			// Rule 6 only: it is not a window, so rule 5 must never see it.
			final Integer schema = readSchemaHeader(file);
			return schema != null && schema < TradedLatestDto.SCHEMA;
		}

		if (name.startsWith(TRADED_PREFIX) && name.endsWith(JSON_SUFFIX))
		{
			final String windowName = name.substring(TRADED_PREFIX.length(), name.length() - JSON_SUFFIX.length());
			if (!isCurrentWindowName(windowName))
			{
				return true;
			}
			final Integer schema = readSchemaHeader(file);
			return schema != null && schema < TradedDayDto.SCHEMA;
		}

		if (!name.startsWith(BASELINE_PREFIX) || !name.endsWith(JSON_SUFFIX))
		{
			return false;
		}

		final String windowName = name.substring(BASELINE_PREFIX.length(), name.length() - JSON_SUFFIX.length());
		if (!isCurrentWindowName(windowName))
		{
			return true;
		}

		// Null is "could not be read", which is NOT evidence of anything about the content: see readBaselineHeader.
		final BaselineHeader header = readBaselineHeader(file);
		return header != null && (header.revId == 0L || header.schema < PriceMapDto.SCHEMA);
	}

	/** True when the text is the {@code name()} of a window this build still has. */
	private static boolean isCurrentWindowName(final String windowName)
	{
		for (final MovementWindow window : MovementWindow.values())
		{
			if (window.name().equals(windowName))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The two header fields rules 3 and 4 of the sweep judge a baseline by - its {@code revId} and its
	 * {@link PriceMapDto#schema} - or {@code null} when the file's bytes could not be READ at all, which means
	 * nothing about the content and so must not cost the user their baseline. A file that reads but will not
	 * parse answers a header of zeroes, which both rules delete: it is no use as a guide baseline either way.
	 *
	 * <p>The read and the parse were one case until the checker separated them (2026-09-09): a sharing violation
	 * while a second client replaced the same file, an antivirus lock or a permissions blip all answered 0 and
	 * were indistinguishable from "written in the trade era", so a perfectly good 90 KB baseline could be deleted
	 * over a transient failure and every window left saying "No 30d history yet" until the next fetch landed.
	 * That is the same distinction {@link #readJson} already draws for every other file here: unparseable is
	 * quarantined, unreadable is left exactly where it is. The bytes are therefore still read WHOLE - a failure
	 * part way through one is a read failure, not a verdict on the content.
	 *
	 * <p><b>What it deliberately does not do is build the map</b> (B025). This runs over every baseline at
	 * startUp, and {@code gson.fromJson(json, PriceMapDto.class)} materialised a complete {@link PriceMapDto} -
	 * about 4,500 {@code long[2]} entries in a {@link HashMap}, five times over, roughly 440 KB of JSON - purely
	 * to read two numbers, and then {@code PriceService.start()} read and parsed all five again. A
	 * {@link JsonReader} walks the keys instead and stops at the first one that is neither: both fields are
	 * declared before {@code points} in {@link PriceMapDto}, and Gson writes fields in declaration order, so the
	 * body of a file this build wrote is never even lexed. A hand-edited file that puts them last still works -
	 * it just costs the walk.
	 *
	 * <p>Lenient like {@code Gson.fromJson}, so the sweep's verdict on a slightly off-spec file does not change
	 * with the parser it uses. Deliberately does NOT quarantine: a file it calls unparseable is about to be
	 * deleted anyway.
	 */
	@Nullable
	private BaselineHeader readBaselineHeader(final Filepath file)
	{
		final String json;
		try
		{
			json = readAll(file);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: could not read {} - keeping it rather than judging it", file, e);
			return null;
		}

		try (JsonReader reader = new JsonReader(new StringReader(json)))
		{
			reader.setLenient(true);
			long revId = 0L;
			int schema = 0;
			boolean sawRevId = false;
			boolean sawSchema = false;
			reader.beginObject();
			while (reader.hasNext() && !(sawRevId && sawSchema))
			{
				final String key = reader.nextName();
				if (REV_ID_KEY.equals(key))
				{
					revId = reader.nextLong();
					sawRevId = true;
				}
				else if (SCHEMA_KEY.equals(key))
				{
					schema = reader.nextInt();
					sawSchema = true;
				}
				else
				{
					reader.skipValue();
				}
			}
			return new BaselineHeader(revId, schema);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: could not read a revision id out of {}", file, e);
			return new BaselineHeader(0L, 0);
		}
	}

	/**
	 * A traded file's stored {@code schema}, or null when its bytes could not be READ - which, exactly as for
	 * {@link #readBaselineHeader}, says nothing about the content and so must not cost the user the file. A file
	 * that reads but will not parse, or that has no such key, answers 0, which rule 6 deletes: it is no use as a
	 * traded feed either way.
	 *
	 * <p>Stops at the key rather than materialising the document, for {@link #readBaselineHeader}'s reason (B025):
	 * {@code schema} is declared first in both traded DTOs and Gson writes fields in declaration order, so the four
	 * thousand entries below it are never even lexed in a file this build wrote.
	 */
	@Nullable
	private Integer readSchemaHeader(final Filepath file)
	{
		final String json;
		try
		{
			json = readAll(file);
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: could not read {} - keeping it rather than judging it", file, e);
			return null;
		}

		try (JsonReader reader = new JsonReader(new StringReader(json)))
		{
			reader.setLenient(true);
			reader.beginObject();
			while (reader.hasNext())
			{
				if (SCHEMA_KEY.equals(reader.nextName()))
				{
					return reader.nextInt();
				}
				reader.skipValue();
			}
			return 0;
		}
		catch (IOException | RuntimeException e)
		{
			log.debug("bank-portfolio-tracker: could not read a schema out of {}", file, e);
			return 0;
		}
	}

	/** What {@link #readBaselineHeader} answers: the two numbers the sweep judges a baseline by. */
	private static final class BaselineHeader
	{
		private final long revId;
		private final int schema;

		private BaselineHeader(final long revId, final int schema)
		{
			this.revId = revId;
			this.schema = schema;
		}
	}

	// ---- the file layer

	/**
	 * A whole file as UTF-8 text.
	 *
	 * <p>The bytes are read in one go and decoded afterwards, which is what {@code Files.readAllBytes} did before
	 * addendum AD and what every caller here depends on: a read that fails part way through must fail as a READ
	 * (the file is then kept, and judged by nothing) rather than arriving as a truncated document that parses to
	 * something wrong. {@link Filepath#openReader} would decode as it went and could not promise that.
	 *
	 * @throws IOException if the file is missing, locked, or cannot be read to the end
	 */
	private static String readAll(final Filepath file) throws IOException
	{
		try (InputStream in = file.openInputStream())
		{
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/**
	 * One document into {@code target}, replacing it whole (C19). The temp file is written in the same
	 * directory as the target so the move is a rename inside one file system, and it carries
	 * {@code System.nanoTime()} in its name so two writers (or two clients) never fill one temp file between
	 * them: each writes a whole document of its own and only the moves race, last one in place wins.
	 *
	 * <p><b>{@code CREATE_NEW} is what makes that a rule rather than a probability</b> (checker, 2026-09-09).
	 * Inside one JVM the name is unique because {@code nanoTime} is monotonic, but on Windows it is
	 * {@code QueryPerformanceCounter}, whose origin is the MACHINE: two RuneLite clients writing
	 * {@code bank-<hash>-<profile>.json} for the same account in the same counter tick could pick the same
	 * name, and a default {@code newBufferedWriter} (CREATE + TRUNCATE_EXISTING) would then have the second
	 * writer truncate and interleave into the first one's file, publishing a mixed document. Opening with
	 * {@code CREATE_NEW} makes the file system refuse that instead; the loser takes a fresh name and tries
	 * again, {@value #TMP_ATTEMPTS} times before it gives up and reports the collision.
	 *
	 * <p>{@code ATOMIC_MOVE} is what makes the replace safe. On Windows (JDK 17
	 * {@code sun.nio.fs.WindowsFileCopy.move}) it is a single
	 * {@code MoveFileEx(MOVEFILE_REPLACE_EXISTING)}; without it the same class does {@code DeleteFile(target)}
	 * and then a move, so an exit between the two leaves no file at all and the next load starts empty - the
	 * reason {@code BeamStore} was written this way ({@code BeamStore.java:1332-1335}).
	 * {@code REPLACE_EXISTING} rides along for the file systems that take the plain move but not the atomic
	 * one; a rename inside one directory is the only case, so the fallback is exotic and still beats losing the
	 * write.
	 *
	 * <p>A failed write takes its own temp file away again. Nothing sweeps the directory: a temp name is a
	 * nanosecond count, so a file left behind by a kill mid-write is never reused and never read - it is
	 * clutter, and deleting another process's in-flight temp file would be worse.
	 *
	 * @throws IOException if the bytes cannot be written or the move fails; the target is untouched
	 */
	public static void writeAtomic(final Filepath target, final String text) throws IOException
	{
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(text, "text");
		final Filepath parent = target.getParent();
		final String name = target.getFileName();
		writeAtomic(target, text, () -> parent.joinSegment(name + "." + System.nanoTime() + TMP_SUFFIX));
	}

	/**
	 * {@link #writeAtomic(Filepath, String)} with the temp NAMES handed in, which is the only way a test can stage
	 * the collision the {@code CREATE_NEW} rule exists for - a name is a nanosecond count and cannot be
	 * predicted from outside. Production passes the counter; nothing else should call this.
	 *
	 * @param tempNames a fresh candidate path each time it is asked, in the target's own directory
	 */
	static void writeAtomic(final Filepath target, final String text, final Supplier<Filepath> tempNames)
		throws IOException
	{
		writeAtomic(target, text, tempNames, false);
	}

	/**
	 * {@link #writeAtomic(Filepath, String)} with the temp file's bytes on the disk before the move (review finding
	 * H6): {@code DSYNC} makes each write reach the device, so the rename that follows can never publish a file whose
	 * data the file system had not yet stored - which after a power cut reads back as the right length of zeros.
	 * Only the history is written this way; every other file here is a cache the next fetch writes again.
	 */
	private static void writeAtomicDurably(final Filepath target, final String text) throws IOException
	{
		final Filepath parent = target.getParent();
		final String name = target.getFileName();
		writeAtomic(target, text, () -> parent.joinSegment(name + "." + System.nanoTime() + TMP_SUFFIX), true);
	}

	private static void writeAtomic(final Filepath target, final String text, final Supplier<Filepath> tempNames,
		final boolean durable) throws IOException
	{
		FileAlreadyExistsException taken = null;
		for (int attempt = 0; attempt < TMP_ATTEMPTS; attempt++)
		{
			final Filepath tmp = tempNames.get();
			try
			{
				// Filepath.write opens a UTF-8 writer, writes the whole string and closes it - the same three
				// steps the try-with-resources did before addendum AD, with the charset no longer a parameter
				// because Filepath has no other.
				if (durable)
				{
					tmp.write(text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.DSYNC);
				}
				else
				{
					tmp.write(text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				}
			}
			catch (FileAlreadyExistsException e)
			{
				// Somebody else is filling a temp file of that name RIGHT NOW. Their bytes are not touched and
				// this write takes a new name; deleting it would be corrupting their document, not recovering.
				taken = e;
				continue;
			}
			catch (IOException | RuntimeException e)
			{
				deleteQuietly(tmp);
				throw e;
			}
			try
			{
				try
				{
					tmp.moveTo(target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				}
				catch (AtomicMoveNotSupportedException e)
				{
					tmp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			catch (IOException | RuntimeException e)
			{
				deleteQuietly(tmp);
				throw e;
			}
			return;
		}
		throw taken;
	}

	/** Best effort: the write already failed, and the leftover is inert. */
	private static void deleteQuietly(final Filepath path)
	{
		try
		{
			path.deleteIfExists();
		}
		catch (IOException | RuntimeException ignored)
		{
			// Nothing to do about it, and nothing depends on it.
		}
	}

	/**
	 * Serialises one document and replaces {@code target} with it. The directory is created here, on the first
	 * write, rather than in the constructor.
	 *
	 * <p>A failure is logged and swallowed: these files are a cache of the wiki and a convenience copy of the
	 * bank, the caller is an executor task that would silently swallow the exception anyway, and the next fetch
	 * writes again.
	 */
	private void write(@Nullable final Filepath target, final Object document)
	{
		if (target == null)
		{
			// No directory at all. dir() has already said so once, at warn; this is the per-save line that
			// says WHICH save went nowhere, at debug so a broken disk cannot fill the client's log.
			log.debug("bank-portfolio-tracker: no data directory, so nothing was saved");
			return;
		}
		try
		{
			final Filepath parent = target.getParent();
			if (!parent.isDirectory())
			{
				// createDirectories is idempotent, so the check is only to keep the syscall off every save; two
				// threads racing here both succeed.
				parent.createDirectories();
			}
			writeAtomic(target, gson.toJson(document));
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("saving {} failed", target, e);
		}
	}

	/**
	 * One price map back, or {@link PriceMap#EMPTY}. Damage <em>inside</em> a file that parses (a key that is
	 * not a number, a truncated pair) is {@link PriceMap#fromDto}'s business and is skipped there rather than
	 * thrown; the catch is belt and braces, so that a future change in that method still cannot stop the panel
	 * from opening on a stale price file (D9, fail soft).
	 */
	private PriceMap readMap(@Nullable final Filepath file)
	{
		final PriceMapDto dto = readJson(file, PriceMapDto.class);
		if (dto == null)
		{
			return PriceMap.EMPTY;
		}
		try
		{
			final PriceMap map = PriceMap.fromDto(dto);
			return map == null ? PriceMap.EMPTY : map;
		}
		catch (RuntimeException e)
		{
			log.warn("could not rebuild the price map in {} (starting empty)", file, e);
			return PriceMap.EMPTY;
		}
	}

	/**
	 * One JSON file as the given type: null when it is missing, empty, or holds a JSON null; null after
	 * quarantining it when it cannot be parsed; null after a log line when it cannot be read.
	 *
	 * <p>The bytes are read before any parsing so an IO failure cannot masquerade as a parse failure: Gson
	 * wraps a reader's IOException in a JsonParseException, which would quarantine a file that is merely
	 * unreadable ({@code BeamStore.java:1416-1419}; on Linux a directory even opens fine and only fails on
	 * read).
	 */
	@Nullable
	private <T> T readJson(@Nullable final Filepath file, final Class<T> type)
	{
		if (file == null || !file.exists())
		{
			return null;
		}
		final String json;
		try
		{
			json = readAll(file);
		}
		catch (IOException | RuntimeException e)
		{
			// Left exactly where it is: it may be readable next time, and quarantining a file we never read
			// would throw away data over a permissions blip.
			log.warn("could not read {} (starting empty)", file, e);
			return null;
		}
		try
		{
			return gson.fromJson(json, type);
		}
		catch (JsonParseException e)
		{
			log.warn("could not parse {} (keeping a backup, starting empty)", file, e);
			quarantine(file);
			return null;
		}
	}

	/**
	 * Moves an unusable file aside as {@code <name>.corrupt-<millis>}. A failure to rename is logged and
	 * nothing else: the file still reads as EMPTY, and the next save replaces it whole anyway.
	 */
	private void quarantine(final Filepath file)
	{
		// Inside the try, all of it: getParent() throws on a Filepath that is its own root, and this method is
		// reached from a load that promises never to throw. Nothing can pass a root today - every path comes
		// from file(name) - and this is what keeps that a property of the method rather than of its callers.
		try
		{
			final Filepath backup = file.getParent()
				.joinSegment(file.getFileName() + CORRUPT_SUFFIX + System.currentTimeMillis());
			file.moveTo(backup, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("could not move {} aside", file, e);
		}
	}

	/**
	 * A profile type safe to put in a file name. In practice this is a {@code RuneScapeProfileType} enum name
	 * (STANDARD, DEADMAN, BETA...), but it reaches the store as a String, and a separator in it would write
	 * outside the store's directory. Load and save run the same mapping, so a sanitised name still finds its
	 * own file.
	 */
	private static String safeProfile(@Nullable final String profileType)
	{
		if (profileType == null)
		{
			return UNKNOWN_PROFILE;
		}
		final String safe = UNSAFE_IN_NAME.matcher(profileType.trim()).replaceAll("_");
		return safe.isEmpty() ? UNKNOWN_PROFILE : safe;
	}

	/** An item id key, or null when the text is not an integer (skip the entry, never fail the load). */
	@Nullable
	private static Integer parseId(@Nullable final String key)
	{
		if (key == null)
		{
			return null;
		}
		try
		{
			return Integer.valueOf(key.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * A loaded document and the moment it was fetched, from ONE parse of its file.
	 *
	 * <p>Why it exists (B028 follow-up): the mapping and the revision index are each read at start-up for their
	 * CONTENT and for their {@code fetchedAtMillis}, and the staleness rule needs both together. Two readers meant
	 * two full parses of the same file - 150 KB of JSON re-parsed for one {@code long} - and, worse, two chances
	 * for the pair to disagree if the file changed between them. This store still caches nothing (C18: every load
	 * reads the file); it simply hands back everything one read produced.
	 *
	 * @param <T> the loaded content - never null, and empty when the file was missing, refused or unreadable
	 */
	public static final class Stamped<T>
	{
		private final T value;
		private final long fetchedAtMillis;

		/**
		 * @param value           the loaded content
		 * @param fetchedAtMillis when the file says it was fetched, epoch millis; 0 when unknown
		 */
		public Stamped(final T value, final long fetchedAtMillis)
		{
			this.value = value;
			this.fetchedAtMillis = fetchedAtMillis;
		}

		/** {@code value} with no stamp at all - "there is no such file", which is what makes a fetch due. */
		public static <T> Stamped<T> none(final T value)
		{
			return new Stamped<>(value, 0L);
		}

		/** The loaded content; never null for anything this store hands back. */
		public T value()
		{
			return value;
		}

		/** When the file says it was fetched, epoch millis; 0 when there was no readable file of this build's shape. */
		public long fetchedAtMillis()
		{
			return fetchedAtMillis;
		}

		@Override
		public String toString()
		{
			return "Stamped{fetchedAtMillis=" + fetchedAtMillis + ", value=" + value + '}';
		}
	}

	/**
	 * What {@link #loadBankHistory} answers: the outcome of the read and the series it found (addendum AU, contract
	 * section 4). The series is never null - {@link BankHistorySeries#EMPTY} for MISSING and FAILED.
	 */
	public static final class BankHistoryLoad
	{
		/** The three outcomes of a history read. */
		public enum State
		{
			/** No such file: start a series. */
			MISSING,
			/** Read and parsed; damaged entries skipped, an older schema migrated. */
			LOADED,
			/** Exists but could not be read or parsed, or a newer build wrote it: write nothing over it. */
			FAILED
		}

		private static final BankHistoryLoad MISSING_LOAD = new BankHistoryLoad(State.MISSING, BankHistorySeries.EMPTY);
		private static final BankHistoryLoad FAILED_LOAD = new BankHistoryLoad(State.FAILED, BankHistorySeries.EMPTY);

		private final State state;
		private final BankHistorySeries series;
		/**
		 * The file held something the series does not: an entry that did not parse, two entries for one day, or a
		 * key this build does not know. Rewriting it from the series would lose that, so the first write of the
		 * session copies the file aside first ({@link #recordBankHistory}, review finding H7).
		 */
		private final boolean lossy;

		private BankHistoryLoad(final State state, final BankHistorySeries series)
		{
			this(state, series, false);
		}

		private BankHistoryLoad(final State state, final BankHistorySeries series, final boolean lossy)
		{
			this.state = state;
			this.series = series;
			this.lossy = lossy;
		}

		/** No such file. */
		public static BankHistoryLoad missing()
		{
			return MISSING_LOAD;
		}

		/** The file could not be used; nothing may be written over it. */
		public static BankHistoryLoad failed()
		{
			return FAILED_LOAD;
		}

		/** The file read as this series; null reads as {@link BankHistorySeries#EMPTY}. */
		public static BankHistoryLoad loaded(@Nullable final BankHistorySeries series)
		{
			return new BankHistoryLoad(State.LOADED, series == null ? BankHistorySeries.EMPTY : series);
		}

		/** {@link #loaded} for a file the series does not hold whole (see {@link #lossy}). */
		private static BankHistoryLoad loadedLossy(final BankHistorySeries series)
		{
			return new BankHistoryLoad(State.LOADED, series, true);
		}

		/** Whether rewriting this file from {@link #series()} would drop something it holds (see the field). */
		boolean lossy()
		{
			return lossy;
		}

		public State state()
		{
			return state;
		}

		/** What was read; {@link BankHistorySeries#EMPTY} unless {@link #state()} is LOADED. */
		public BankHistorySeries series()
		{
			return series;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof BankHistoryLoad))
			{
				return false;
			}
			final BankHistoryLoad other = (BankHistoryLoad) o;
			return state == other.state && lossy == other.lossy && series.equals(other.series);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(state, series, lossy);
		}

		@Override
		public String toString()
		{
			return "BankHistoryLoad{" + state + ", " + series + (lossy ? ", lossy" : "") + '}';
		}
	}

	/**
	 * The on-disk form of the id-to-wiki-name table (K11): {@code {"fetchedAtMillis": n, "names": {"<id>":
	 * "<wiki name>"}}}. Public fields, no-arg constructor, stock-Gson types - the workspace rule for a persisted
	 * class (playbook step 13).
	 *
	 * <p>Nested rather than a file of its own because it is the store's private on-disk shape and nothing else
	 * ever holds one: {@link #loadMapping()} hands back a plain {@code Map<Integer, String>}. The keys are
	 * STRINGS because a JSON object key always is, which lets a non-numeric key be skipped rather than throw -
	 * the same reasoning as {@link PriceMapDto}.
	 */
	static class MappingDto
	{
		/**
		 * The version of THIS document's shape that the current build writes ({@link #schema}), on the same rule as
		 * {@link PriceMapDto#SCHEMA}: bump it when a stored field's MEANING changes, not when a field is added.
		 */
		static final int SCHEMA = 1;

		/**
		 * Which build's shape this document is: {@link #SCHEMA} in anything this build wrote, 0 in a file written
		 * before the field existed (B028). Nothing gates on it TODAY and nothing should - this table has meant one
		 * thing since K3, and refusing it would cost a refetch of 127 KB against a weekly cadence. It is here so
		 * that the build which DOES change a meaning has a marker to test, which it cannot get retrospectively:
		 * added then, the field could not tell a file written before the change from one written after it.
		 */
		public int schema;

		/** When the table was fetched, epoch millis (0 when unknown). */
		public long fetchedAtMillis;

		/** Item id (as text) to the wiki's spelling of its name. */
		public Map<String, String> names;

		public MappingDto()
		{
		}
	}

	/**
	 * The on-disk form of the revision history (L4): {@code {"fetchedAtMillis": n, "revisions": [{...}, ...]}}.
	 * Public fields, no-arg constructor, stock-Gson types - the workspace rule for a persisted class
	 * (playbook step 13).
	 *
	 * <p>A list of small objects rather than the compact array-of-arrays {@link PriceMapDto} uses for prices,
	 * because there are only about 250 of them (a few KB) and because this is the file a maintainer will open by
	 * hand when a baseline looks wrong: {@code {"revId":15334656,"editSeconds":1788859512,"user":"Gaz GEBot",
	 * "comment":"GE update"}} says which day was chosen and why, where {@code [15334656,1788859512,...]} would
	 * not.
	 */
	static class RevIndexDto
	{
		/** The version of THIS document's shape that the current build writes - see {@link MappingDto#SCHEMA}. */
		static final int SCHEMA = 1;

		/**
		 * Which build's shape this document is: {@link #SCHEMA} in anything this build wrote, 0 in a file written
		 * before the field existed (B028). Nothing gates on it today, for the same reason as
		 * {@link MappingDto#schema}: an entry here is a revision id and an edit time, and neither has changed
		 * meaning. A per-document number rather than one shared constant, so a change to one shape does not
		 * invalidate the other three.
		 */
		public int schema;

		/** When the history was fetched, epoch millis (0 when unknown) - the six-hour rule's input. */
		public long fetchedAtMillis;

		/** The revisions, newest first. */
		public List<RevEntry> revisions;

		public RevIndexDto()
		{
		}
	}

	/**
	 * The on-disk form of the traded {@code /latest} snapshot (T2): {@code {"schema":1,"fetchedAtMillis":n,
	 * "quotes":{"<id>":[buy, buySeconds, sell, sellSeconds]}}}. Public fields, a no-arg constructor and
	 * stock-Gson types - the workspace rule for a persisted class (playbook step 13).
	 *
	 * <p>A four-slot {@code long[]} per item rather than an object, for {@link PriceMapDto}'s reason: there are
	 * about four thousand of them, and a missing price is {@link PriceMapDto#ABSENT} (-1) rather than a JSON null,
	 * because whether a null survives depends on the Gson instance's null handling while a sentinel cannot be
	 * configured away. A trade TIME is never negative, so 0 there means "unknown" and needs no sentinel.
	 */
	static class TradedLatestDto
	{
		/**
		 * The version of THIS document's shape that the current build writes ({@link #schema}), on
		 * {@link PriceMapDto#SCHEMA}'s rule: bump it when a stored field's MEANING changes, not when one is added.
		 */
		static final int SCHEMA = 1;

		/**
		 * Which build's shape this document is: {@link #SCHEMA} in anything this build wrote, 0 in a file written
		 * before the field existed. Unlike the mapping's marker this one IS gated on, both by
		 * {@link #loadTradedLatest()} and by the sweep: a live traded price is the only figure in this plugin
		 * whose meaning a reader cannot check against anything else, so a document whose fields might mean
		 * something else is refetched rather than believed. The cost is one {@code /latest} call.
		 */
		public int schema;

		/** When the snapshot was fetched, epoch millis (0 when unknown) - T7's six-hour rule reads this. */
		public long fetchedAtMillis;

		/** Item id (as text) to {@code [buy, buySeconds, sell, sellSeconds]}; a price of -1 means absent. */
		public Map<String, long[]> quotes;

		public TradedLatestDto()
		{
		}
	}

	/**
	 * The on-disk form of one window's traded daily bucket (T2): {@code {"schema":1,"fetchedAtMillis":n,
	 * "day":"2026-09-10","buckets":{"<id>":[avgHigh, highVolume, avgLow, lowVolume]}}}. Public fields, a no-arg
	 * constructor and stock-Gson types, and the same four-slot encoding {@link TradedLatestDto} uses.
	 *
	 * <p>The {@link #day} is an ISO date STRING rather than an epoch number, because this is the file a
	 * maintainer opens by hand when a window's move looks wrong and "2026-09-10" answers the question a
	 * timestamp only poses. A file whose day will not parse is refused whole: a bucket that cannot name its day
	 * cannot be checked against the guide baseline it is supposed to pair with.
	 */
	static class TradedDayDto
	{
		/** The version of THIS document's shape that the current build writes - see {@link TradedLatestDto#SCHEMA}. */
		static final int SCHEMA = 1;

		/** Which build's shape this document is; gated on for {@link TradedLatestDto#schema}'s reason. */
		public int schema;

		/** When the bucket was fetched, epoch millis (0 when unknown). */
		public long fetchedAtMillis;

		/** The UTC calendar day these buckets are of, ISO-8601 ({@code 2026-09-10}). */
		public String day;

		/** Item id (as text) to {@code [avgHigh, highVolume, avgLow, lowVolume]}; a price of -1 means absent. */
		public Map<String, long[]> buckets;

		public TradedDayDto()
		{
		}
	}

	/**
	 * One window's traded daily bucket as the service holds it: the UTC day it is of, the per-item averages and
	 * volumes, and when it was fetched. Immutable and shared - built on the executor, read while the rows are
	 * computed - and {@link #EMPTY} is what a missing, refused or unreadable file reads as.
	 *
	 * <p>The DAY rides with the buckets because it is half the value: {@code PriceService} only uses a bucket whose
	 * day is the one the live calendar wants for that window (U1), and every reader downstream - the row's
	 * tooltip, the card's footnote, {@code state.live.windowDays} - prints the day it names, so a set of numbers
	 * with no day attached could neither be used nor shown.
	 */
	public static final class TradedDay
	{
		/** No bucket: no day, no items, no stamp. Safe to share - this is immutable. */
		public static final TradedDay EMPTY = new TradedDay(null, Collections.<Integer, TradedPriceClient.Bucket>emptyMap(), 0L);

		@Nullable
		private final LocalDate day;
		private final Map<Integer, TradedPriceClient.Bucket> buckets;
		private final long fetchedAtMillis;

		/**
		 * @param day             the UTC calendar day these buckets are of, or null when there is none
		 * @param buckets         item id to that day's averages and volumes; copied, null keys or values dropped
		 * @param fetchedAtMillis when it was fetched, epoch millis
		 */
		public TradedDay(@Nullable final LocalDate day, @Nullable final Map<Integer, TradedPriceClient.Bucket> buckets,
			final long fetchedAtMillis)
		{
			this.day = day;
			final Map<Integer, TradedPriceClient.Bucket> copy = new HashMap<>();
			if (buckets != null)
			{
				for (final Map.Entry<Integer, TradedPriceClient.Bucket> entry : buckets.entrySet())
				{
					if (entry.getKey() != null && entry.getValue() != null)
					{
						copy.put(entry.getKey(), entry.getValue());
					}
				}
			}
			this.buckets = Collections.unmodifiableMap(copy);
			this.fetchedAtMillis = fetchedAtMillis;
		}

		/** The UTC calendar day these buckets are of; null only for {@link #EMPTY}. */
		@Nullable
		public LocalDate day()
		{
			return day;
		}

		/** One item's bucket for that day, or null when nothing traded it. */
		@Nullable
		public TradedPriceClient.Bucket get(final int id)
		{
			return buckets.get(id);
		}

		/** Every bucket, unmodifiable. */
		public Map<Integer, TradedPriceClient.Bucket> buckets()
		{
			return buckets;
		}

		/** When the bucket was fetched, epoch millis; 0 when it came from nowhere. */
		public long fetchedAtMillis()
		{
			return fetchedAtMillis;
		}

		/** True when there is no day or nothing traded - the reading that sends every window back to the guide. */
		public boolean isEmpty()
		{
			return day == null || buckets.isEmpty();
		}

		@Override
		public String toString()
		{
			return "TradedDay{day=" + day + ", items=" + buckets.size() + ", fetchedAtMillis=" + fetchedAtMillis + '}';
		}
	}

	/**
	 * One stored revision. Nested rather than reusing {@link RevisionRef} directly because that class is
	 * immutable with private fields and a Gson without an adapter cannot rebuild it - and giving a model class a
	 * no-arg constructor purely to satisfy a serialiser is how a "never null" invariant gets lost.
	 */
	static class RevEntry
	{
		/** The wiki revision id. An entry without one is skipped on load. */
		public long revId;

		/** When the revision was saved, unix seconds. An entry without one carries no calendar day. */
		public long editSeconds;

		/** The editor; null in a hand-edited file, which {@link RevisionRef} reads as "". */
		public String user;

		/** The edit comment; null when the edit had none. */
		public String comment;

		public RevEntry()
		{
		}
	}
}
