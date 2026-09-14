package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The plugin's only network code (addendum L, contract lines L4, L6, L7, L8, L11): the HISTORY of the Grand
 * Exchange guide price, read off the OSRS wiki page {@value #PAGE}, plus the id-to-wiki-name table that lets a
 * bank item find its row in it. "Now" needs no request at all - it is RuneLite's own guide price from
 * {@code ItemManager} (L1).
 *
 * <p><b>Why the guide table and not the real-time trade API.</b> The first live look (2026-09-08 19:20) showed
 * "Green hat +1,413 gp (+12845.5 %)" while the GE web site said -38 gp (-3 %). Nothing was broken: the wiki's
 * hourly TRADE bucket 24 h earlier held nine units at an average high of 11 gp with no low side. The number every
 * player recognises - in-game GE, the GE web site, RuneLite's own tooltips - is the Jagex daily GUIDE price, and
 * the wiki republishes the whole guide table as {@value #PAGE} (bot "Gaz GEBot", one revision a day, 4,566 names,
 * 126 KB). Its revisions reproduce Jagex's own daily history exactly: 70 of 70 archived values across 14 items
 * and five spans match {@code secure.runescape.com/m=itemdb_oldschool/api/graph/<id>.json} (L-A). Evidence:
 * {@code docs/research/bank-price-movement-calibration-2026-09-08.md} and addendum L.
 *
 * <p><b>Two calls a day, not two per window</b> (L4, L6, L10). Addendum K asked MediaWiki for "the newest
 * revision at or before instant X" and then fetched that revision's bytes - two requests per window, and, worse,
 * the wrong revision about half the time, because the bot publishes at a random hour (L-C). This client instead
 * fetches:
 * <ul>
 * <li>the revision INDEX once ({@link #fetchRevisionIndex}): one call, {@code rvlimit=250}, about 232 days of
 * history in roughly 3.6 KB gzipped. The baseline for any window is then chosen from that list LOCALLY, by
 * calendar day ({@link RevisionRef#pickThen}), with no request at all;</li>
 * <li>the bodies it still needs ({@link #fetchTables}): several whole tables in ONE call through
 * {@code revids=a|b|c}. Measured by the lead: two revisions, 76.6 KB on the wire, 0.37 s. The
 * {@code index.php?action=raw} route of K2 is gone - it is served uncompressed, 126 KB per revision (L-G).</li>
 * </ul>
 * Per-item requests are never made, whatever the bank holds.
 *
 * <p><b>What leaves the client, exactly</b> (B035; the evidence the Hub manifest's {@code warning=} decision
 * rests on - the decision itself is the lead's, beside D15). Three URLs exist in this plugin and NONE of them
 * carries anything derived from the player: {@link #revisionIndexUrl()} is a fixed query for one fixed page
 * title, {@link #tablesUrl(Collection)} carries only wiki revision ids the plugin picked out of that public
 * index, and {@link #MAPPING_URL} is a bare GET of a public table. No item id, no item name, no quantity, no
 * account and no search text is ever a parameter, so every user of this plugin issues BYTE-IDENTICAL requests
 * and the hosts learn only an IP - which the descriptive {@link #USER_AGENT} already discloses - and that this
 * plugin is installed. The bank itself never leaves the client: it is read on the client thread and persisted
 * under {@code ~/.runelite/bank-portfolio-tracker/} only. {@code theRequestUrlsCarryNothingDerivedFromThePlayer}
 * in the tests is what keeps that true as the routes change.
 *
 * <p><b>Why the client and the Gson are injected.</b> RuneLite's {@code @Provides OkHttpClient}
 * ({@code runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java:180-205}, clone tag
 * runelite-parent-1.12.37) is the only client whose requests carry a usable User-Agent: the boot interceptor at
 * {@code RuneLite.java:415-437} stamps {@code RuneLite/<version>-<commit>} when a request has no UA and PREPENDS
 * it to any UA that does not already start with "RuneLite" ({@code RuneLite.java:425-429}), so the wiki sees
 * both the client and this plugin. It also gives us transparent gzip (OkHttp sets {@code Accept-Encoding} itself
 * and decompresses, which is what makes the batched call cheap - so this class never sets that header), the
 * shared disk cache, and the three refusals that arrive as plain {@code IOException}s in {@code onFailure}
 * (client thread, EDT, and the non-LIVE environment block). Constructing an OkHttpClient or a Gson here is a
 * Plugin Hub packaging blocker (C46) as well.
 *
 * <p><b>Why enqueue, never execute.</b> {@code RuneLiteModule.java:185-192} throws for a blocking call made on
 * the client thread or the EDT in EVERY environment, and this client is driven from the panel and from the
 * plugin's scheduled executor. {@code enqueue} hands the work to OkHttp's dispatcher and completes the returned
 * future on that thread, so no caller thread is ever parked. Shape copied from
 * {@code LootTrackerClient.java:84-106} and {@code ConfigClient.java:151-193}; the {@code try (response)} close
 * idiom from {@code WikiDpsManager.java:425-448}.
 *
 * <p><b>Why every failure is one exception type</b> (L11, failure shapes L-G). A MediaWiki error arrives as
 * {@code {"error": {...}}} with HTTP 200, so neither the status nor the body alone is the failure test. Non-2xx,
 * an {@code error} member, a body that is not the expected shape, a page that is {@code missing}, a batch in
 * which no revision survived validation, an empty table and a transport {@code IOException} all complete the
 * future exceptionally with a {@link WikiPriceException}; the future never completes with null. Callers keep
 * their last good baseline and carry on (design D9, fail soft). A window whose target simply predates the page
 * is NOT a failure and never reaches here: {@code pickThen} answers null and the panel says "No 180d history".
 *
 * <p>This class holds no cache and no history: staleness, scheduling and one-in-flight-per-window are
 * {@code PriceService}'s rules (L10). Its only state is {@link #setEnabled(boolean)}, the list of calls still on
 * the wire for {@link #cancelInFlight()}, and the "a failure has already been logged loudly" flag.
 */
public class GuidePriceClient
{
	private static final Logger log = LoggerFactory.getLogger(GuidePriceClient.class);

	/** The wiki's MediaWiki API endpoint - the revision index (L4) and the batched bodies (L6) both go here. */
	public static final String API_URL = "https://oldschool.runescape.wiki/api.php";

	/**
	 * The guide-price table page. Its content is a flat JSON object of item name to guide gp plus two
	 * {@code %...%} bookkeeping keys (measured 2026-09-08: 4,566 names, 125,696 bytes).
	 */
	public static final String PAGE = "Module:GEPrices/data.json";

	/**
	 * The id-to-name table (K3, restated by L8). It is the PRIMARY name source, not a fallback: {@code /mapping}
	 * has zero id-to-name collisions and the guide table's keys are a strict subset of its names, and
	 * {@code table[mapping name(id)]} equalled the GE price for 28 of 28 probed ids (L-F).
	 */
	public static final String MAPPING_URL = "https://prices.runescape.wiki/api/v1/osrs/mapping";

	/**
	 * The descriptive User-Agent both wiki hosts' policies ask for; unchanged since the first build so an
	 * operator reading the wiki's logs sees one plugin across every change of route. RuneLite prepends its own
	 * {@code RuneLite/<version>-<commit>} because this string does not start with "RuneLite"
	 * ({@code RuneLite.java:425-429}).
	 */
	public static final String USER_AGENT = "bank-portfolio-tracker (RuneLite plugin; github.com/2hBuilds)";

	/** Header name, spelled once so the request builder and the tests cannot drift apart. */
	public static final String USER_AGENT_HEADER = "User-Agent";

	/**
	 * How many revisions the index asks for (L4). 250 is the anonymous {@code rvlimit} ceiling, and no
	 * continuation is followed, so ONE call sees at most this many revisions however far back the page goes.
	 *
	 * <p>The margin is thinner than it looks: 250 revisions is about 232 days at the one bot run a day L-C
	 * measured, against a longest window of 180 - but only about 178 days at 1.4 revisions a day, which a stretch
	 * of maintenance edits produces (the live index on this machine already averages 1.11). Because a shortfall
	 * would show as "No 180d history", the same sentence an honestly short page gets,
	 * {@code PriceService.finishIndex} MERGES each fetch with the index it already holds rather than replacing it,
	 * so a long-running install keeps reach it has already seen, and {@code PriceService.reconcile} logs the
	 * ambiguity once per session.
	 */
	public static final int INDEX_LIMIT = 250;

	/**
	 * How many revision ids one batched body call may carry (L6). 50 is MediaWiki's own {@code revids} ceiling
	 * for an anonymous client; the service never needs more than six (the newest table plus one per window).
	 */
	public static final int MAX_REVIDS = 50;

	/** The table's own day marker, and the authoritative one (L7, evidence L-E). */
	public static final String LAST_UPDATE_KEY = "%LAST_UPDATE%";

	/** Keys in the guide table that are bookkeeping, not items: {@code %LAST_UPDATE%}, {@code %LAST_UPDATE_F%}. */
	private static final char BOOKKEEPING_PREFIX = '%';

	/**
	 * How far BEHIND its own save time a body's {@code %LAST_UPDATE%} may be before the body is refused (L7).
	 * Two days: a human maintenance edit republishes the previous day's table (L-E), which is one day behind and
	 * must be accepted so L5's retry can recognise it; anything older than that is not this page's data.
	 */
	private static final long MAX_DATA_LAG_SECONDS = 48L * 60L * 60L;

	/**
	 * How far AHEAD of its own save time a body's {@code %LAST_UPDATE%} may be (L7). Five minutes of clock skew:
	 * a table cannot be stamped after the edit that published it, so anything further ahead is a body that does
	 * not belong to this revision.
	 */
	private static final long MAX_DATA_LEAD_SECONDS = 300L;

	/** Error and diagnostic text is cut to this many characters before it reaches a message or the log. */
	private static final int MAX_DETAIL_CHARS = 200;

	private final OkHttpClient client;
	private final Gson gson;

	/**
	 * Developer-mode switch behind the dev bridge's {@code wiki=on|off} verb (L13): when false every fetch fails
	 * immediately without touching the network, so the "no history" statuses can be exercised in a live client.
	 * Volatile because it is written from the bridge/EDT and read on whichever thread starts a fetch. Never
	 * flipped by user-facing code.
	 */
	private volatile boolean enabled = true;

	/**
	 * Every call enqueued and not yet answered, so {@link #cancelInFlight()} can drop them when the plugin stops
	 * (B012). Copy-on-write: at most a handful of entries, written from whichever thread starts a fetch and from
	 * OkHttp's dispatcher as each answers.
	 */
	private final List<Call> inFlight = new CopyOnWriteArrayList<>();

	/** Whether {@link #report} has already logged a failure loudly this session (B010). */
	private volatile boolean reported;

	public GuidePriceClient(final OkHttpClient client, final Gson gson)
	{
		if (client == null)
		{
			throw new IllegalArgumentException("client is required (inject RuneLite's OkHttpClient)");
		}
		if (gson == null)
		{
			throw new IllegalArgumentException("gson is required (inject RuneLite's Gson)");
		}
		this.client = client;
		this.gson = gson;
	}

	// ---------------------------------------------------------------- L4: the revision index

	/**
	 * The page's recent revision history, newest first - one call, no content (L4).
	 *
	 * <p>This is what makes calendar-day selection possible at all: with the whole history in hand, the baseline
	 * for any window is {@link RevisionRef#pickThen} on a date, decided locally and instantly, and a window
	 * change costs a request only when the chosen revision is one the store has not already got (L10).
	 *
	 * @param nowMillis the caller's clock; not used by the request itself, but taken so the SERVICE's settable
	 *                  clock - never {@code System.currentTimeMillis()} in here - decides when the index is
	 *                  stale (six hours, L4) and what stamp {@code revindex.json} is written with
	 * @return a future completing with the history, or exceptionally with {@link WikiPriceException}
	 */
	public CompletableFuture<List<RevisionRef>> fetchRevisionIndex(final long nowMillis)
	{
		log.debug("bank-portfolio-tracker: fetching the guide-table revision index at {}", nowMillis);
		return request(revisionIndexUrl(), this::parseRevisionIndex);
	}

	// ---------------------------------------------------------------- L6: the batched bodies

	/**
	 * The full guide table of every named revision, in ONE request (L6).
	 *
	 * <p>MediaWiki serves several revisions of a page from a single {@code revids=} query and the response is
	 * gzipped in flight, which is the whole reason this replaced K2's per-revision {@code action=raw} reads: the
	 * lead measured two whole tables at 76.6 KB on the wire in 0.37 s, against 126 KB uncompressed EACH the old
	 * way (L-G). A steady day therefore costs one index call and one body call (L10).
	 *
	 * <p>A revision that comes back unusable - no content, a table with no prices, or a {@code %LAST_UPDATE%}
	 * outside the window its own save time allows (L7) - is left OUT of the map rather than failing the batch:
	 * the caller keeps the stored baseline for that window and every other window still gets its table. Only a
	 * batch in which NOTHING survived fails, because that is a route change, not one odd revision. A revision
	 * the wiki declined to include at all (a {@code badrevids} entry, or a {@code continue} the caller is not
	 * asked to follow) is simply absent for the same reason: the map is what arrived, and the caller reads it by
	 * revision id rather than by position.
	 *
	 * @param revIds    the revisions to read; nulls and non-positive ids are dropped and duplicates collapse
	 * @param nowMillis stamped onto every snapshot as {@code fetchedAtMillis}; passed in rather than read here so
	 *                  the service's settable clock governs every staleness decision (C27)
	 * @return a future completing with revision id to table, or exceptionally with {@link WikiPriceException} -
	 *         including when no id is usable, or when more than {@value #MAX_REVIDS} are asked for at once
	 */
	public CompletableFuture<Map<Long, GuideSnapshot>> fetchTables(final Collection<Long> revIds, final long nowMillis)
	{
		final List<Long> ids = usableIds(revIds);
		if (ids.isEmpty())
		{
			// Deliberately a failure and not an empty success: a caller that asked for nothing has a bug, and an
			// empty map would look like "every requested revision was fetched" to the code that stores baselines.
			return failed(new WikiPriceException("no usable revision ids to fetch"));
		}

		if (ids.size() > MAX_REVIDS)
		{
			return failed(new WikiPriceException(
				"a batched revision fetch may carry at most " + MAX_REVIDS + " ids, was asked for " + ids.size()));
		}

		log.debug("bank-portfolio-tracker: fetching {} guide table(s) in one call: {}", ids.size(), ids);
		return request(tablesUrl(ids), body -> parseTables(body, nowMillis));
	}

	/**
	 * The wiki's id-to-name table (L8), fetched at most weekly by the caller and persisted as {@code mapping.json}.
	 *
	 * @param nowMillis unused by the request itself; present so the caller's clock, not this class, decides what
	 *                  "weekly" means and so the signature matches the other two fetches
	 * @return a future completing with item id to wiki name, or exceptionally with {@link WikiPriceException}
	 */
	public CompletableFuture<Map<Integer, String>> fetchMapping(final long nowMillis)
	{
		log.debug("bank-portfolio-tracker: fetching the id/name mapping at {}", nowMillis);
		return request(MAPPING_URL, this::parseMapping);
	}

	/**
	 * Developer-mode kill switch for the {@code wiki=on|off} bridge verb (L13); true is the shipped state.
	 * User-facing behaviour must never depend on this (workspace rule: no user feature may need a dev hook).
	 */
	public void setEnabled(final boolean enabled)
	{
		this.enabled = enabled;
	}

	/** @see #setEnabled(boolean) */
	public boolean isEnabled()
	{
		return enabled;
	}

	// ---------------------------------------------------------------- the exact URLs (L4, L6)

	/**
	 * The revision index query of L4, verbatim:
	 * {@code api.php?action=query&prop=revisions&titles=Module:GEPrices/data.json&rvlimit=250&rvdir=older&rvprop=ids|timestamp|user|comment&format=json&formatversion=2}.
	 *
	 * <p>{@code user} and {@code comment} are asked for because they are what separates the bot's daily run from
	 * a human's maintenance edit ({@link RevisionRef#isBot()}, L-E), and {@code rvdir=older} is what makes the
	 * answer start at the newest revision.
	 *
	 * <p>Assembled as a literal string rather than through {@code HttpUrl.Builder} because OkHttp's query
	 * canonicaliser leaves {@code :}, {@code /} and {@code |} alone (they are legal query characters, RFC 3986
	 * {@code pchar / "/" / "?"}) while {@code addQueryParameter} would still be a second spelling of the same
	 * thing; one literal is the shape a maintainer can paste into curl to reproduce a bug.
	 */
	static String revisionIndexUrl()
	{
		return API_URL
			+ "?action=query"
			+ "&prop=revisions"
			+ "&titles=" + PAGE
			+ "&rvlimit=" + INDEX_LIMIT
			+ "&rvdir=older"
			+ "&rvprop=ids|timestamp|user|comment"
			+ "&format=json"
			+ "&formatversion=2";
	}

	/**
	 * The batched content query of L6:
	 * {@code api.php?action=query&prop=revisions&revids=<a>|<b>&rvprop=ids|timestamp|content&rvslots=main&format=json&formatversion=2}.
	 *
	 * <p>{@code rvslots=main} is not optional: since MediaWiki 1.32 a revision's text lives in a slot, and asking
	 * for {@code content} without naming a slot answers a deprecation warning instead of the bytes.
	 * {@code timestamp} rides along because {@link #parseTables} needs each revision's own save time to bound the
	 * {@code %LAST_UPDATE%} check (L7).
	 */
	static String tablesUrl(final Collection<Long> revIds)
	{
		final StringBuilder ids = new StringBuilder();
		for (final Long revId : revIds)
		{
			if (ids.length() > 0)
			{
				ids.append('|');
			}
			ids.append(revId);
		}

		return API_URL
			+ "?action=query"
			+ "&prop=revisions"
			+ "&revids=" + ids
			+ "&rvprop=ids|timestamp|content"
			+ "&rvslots=main"
			+ "&format=json"
			+ "&formatversion=2";
	}

	// ---------------------------------------------------------------- parsers (package-private, pure, L4/L6)

	/**
	 * The revision index as a newest-first list (L4). Shape under {@code formatversion=2}:
	 * {@code {"batchcomplete":true,"query":{"pages":[{"pageid":180412,"title":"Module:GEPrices/data.json",
	 * "revisions":[{"revid":15334656,"parentid":15333448,"timestamp":"2026-09-08T09:25:12Z","user":"Gaz GEBot",
	 * "comment":"GE update"}, ...]}]}}}.
	 *
	 * <p>{@code pages} is an ARRAY under {@code formatversion=2} and an object keyed by page id in the default
	 * output format; both are read, because the wiki could switch its default and a silently empty index is
	 * worse than a loud failure. One revision that cannot be used - no id, or a timestamp that will not parse,
	 * without which no calendar day can be derived - is skipped; an index with nothing usable in it is fatal.
	 *
	 * <p>The result is SORTED newest-first rather than trusted to arrive that way, so a change of default
	 * ordering at the wiki could only make the dev bridge's echo untidy. {@link RevisionRef#pickThen} does not
	 * depend on the order either way.
	 *
	 * @throws WikiPriceException on a non-object body, an {@code error} member, a missing page or no usable
	 *                            revision at all
	 */
	List<RevisionRef> parseRevisionIndex(final String json) throws WikiPriceException
	{
		final JsonObject root = readObject(json);
		failOnErrorMember(root);

		final List<RevisionRef> index = new ArrayList<>();
		for (final JsonObject revision : revisionsOf(root))
		{
			final Long revId = optLong(revision, "revid");
			if (revId == null || revId <= 0L)
			{
				continue;
			}

			final Long editSeconds = optInstantSeconds(revision);
			if (editSeconds == null)
			{
				continue;
			}

			index.add(new RevisionRef(revId, editSeconds, memberText(revision, "user"),
				memberText(revision, "comment")));
		}

		if (index.isEmpty())
		{
			throw new WikiPriceException("the wiki listed no usable revisions of " + PAGE);
		}

		index.sort(RevisionRef.NEWEST_FIRST);
		return index;
	}

	/**
	 * Every revision body in a batched answer, keyed by revision id (L6). Shape is
	 * {@link #parseRevisionIndex}'s, with the text under
	 * {@code query.pages[0].revisions[i].slots.main.content} and several revisions of the one page in the array.
	 *
	 * <p>Each body is validated before it is kept (L7): its {@code %LAST_UPDATE%} must sit between 48 hours
	 * before and 5 minutes after the revision's own save time. That window is what a human maintenance edit
	 * looks like - it republishes the previous day's table under a later timestamp (L-E) - while a body with no
	 * marker at all, or one from another page entirely, falls outside it. A revision that fails is skipped with
	 * a log line, never guessed at: L5's "is this really day T's table?" step compares
	 * {@code GuideSnapshot.dataDay()} against the date it asked for, and a marker invented here would defeat it.
	 *
	 * @param nowMillis stamped onto every snapshot as {@code fetchedAtMillis}
	 * @throws WikiPriceException on a non-object body, an {@code error} member, or a batch in which no revision
	 *                            survived (a route change, as opposed to one odd revision)
	 */
	Map<Long, GuideSnapshot> parseTables(final String json, final long nowMillis) throws WikiPriceException
	{
		final JsonObject root = readObject(json);
		failOnErrorMember(root);

		final Map<Long, GuideSnapshot> tables = new LinkedHashMap<>();
		for (final JsonObject revision : revisionsOf(root))
		{
			final Long revId = optLong(revision, "revid");
			final Long editSeconds = optInstantSeconds(revision);
			if (revId == null || revId <= 0L || editSeconds == null)
			{
				continue;
			}

			final String content = contentOf(revision);
			if (content == null || content.trim().isEmpty())
			{
				log.debug("bank-portfolio-tracker: revision {} came back without content", revId);
				continue;
			}

			final JsonObject body;
			final Map<String, Long> prices;
			try
			{
				body = readObject(content);
				prices = tableOf(body);
			}
			catch (final WikiPriceException e)
			{
				log.debug("bank-portfolio-tracker: revision {} carried no usable table: {}", revId, e.getMessage());
				continue;
			}

			final long dataSeconds = dataSecondsOf(body);
			if (dataSeconds < editSeconds - MAX_DATA_LAG_SECONDS || dataSeconds > editSeconds + MAX_DATA_LEAD_SECONDS)
			{
				// No day marker, or one that cannot belong to this revision. Guessing a day here would produce a
				// row labelled with a date its numbers are not from - the exact defect addendum L exists to fix.
				log.debug("bank-portfolio-tracker: revision {} has a {} of {} against an edit time of {} - skipped",
					revId, LAST_UPDATE_KEY, dataSeconds, editSeconds);
				continue;
			}

			tables.put(revId, new GuideSnapshot(revId, editSeconds, dataSeconds, nowMillis, prices));
		}

		if (tables.isEmpty())
		{
			throw new WikiPriceException("the wiki returned no usable guide tables");
		}
		return tables;
	}

	/**
	 * One revision of {@value #PAGE} as folded item name to guide gp. Content shape (calibration doc):
	 * {@code {"Green hat":1086, "Abyssal whip":799012, ..., "%LAST_UPDATE%":1788859349,
	 * "%LAST_UPDATE_F%":"08 September 2026 09:22:29 (UTC)"}}.
	 *
	 * <p>Keys beginning with {@code %} are the page's own bookkeeping and are skipped; a value that is not a
	 * number is skipped rather than fatal, so one odd entry cannot cost the other four and a half thousand. Every
	 * surviving key is folded through {@code GuideSnapshot.key} (L8) - the wiki case-renamed 81 keys in August
	 * 2026, so a byte-exact table loses three of the user's bank items on any window of 30 days or more.
	 *
	 * <p>The table's "unpriced" sentinel (2,147,483,646) is left in the map here and dropped by
	 * {@link GuideSnapshot}: the model applies that rule once, for every construction path, including a baseline
	 * read back off disk.
	 *
	 * <p>An empty result IS fatal: a JSON-shaped body with no prices in it means the page moved or the revision
	 * was deleted, and publishing an empty baseline would quietly show "-" on every row as though the plugin
	 * were working.
	 *
	 * @throws WikiPriceException on a non-object body or a table with no prices in it
	 */
	Map<String, Long> parseGuideTable(final String json) throws WikiPriceException
	{
		return tableOf(readObject(json));
	}

	/**
	 * The {@code /mapping} array as item id to wiki name. Shape:
	 * {@code [{"id":4151,"name":"Abyssal whip","examine":"...","members":true,...}, ...]}, about 4,660 entries.
	 *
	 * <p>Only {@code id} and {@code name} are read; every other member is ignored so a new field cannot break a
	 * release. An entry that is not an object, or that lacks either member, is skipped. Names are kept in the
	 * wiki's own spelling - the fold to a table key happens at lookup time ({@code GuideSnapshot.get}) so the
	 * mapping stays readable on disk and in the dev bridge's echo.
	 *
	 * <p>An empty result is fatal for the same reason as {@link #parseGuideTable(String)}: without names, nothing
	 * can be priced.
	 *
	 * @throws WikiPriceException on a body that is not a JSON array, or an array with no usable entries
	 */
	Map<Integer, String> parseMapping(final String json) throws WikiPriceException
	{
		final JsonElement root = readElement(json);
		if (!root.isJsonArray())
		{
			throw new WikiPriceException("the wiki mapping response was not a JSON array");
		}

		final JsonArray array = root.getAsJsonArray();
		final Map<Integer, String> names = new LinkedHashMap<>();
		for (final JsonElement element : array)
		{
			if (element == null || !element.isJsonObject())
			{
				continue;
			}

			final JsonObject entry = element.getAsJsonObject();
			final Long id = optLong(entry, "id");
			final String name = memberText(entry, "name");
			if (id == null || name == null || name.isEmpty() || id <= 0L || id > Integer.MAX_VALUE)
			{
				continue;
			}
			names.put(id.intValue(), name);
		}

		if (names.isEmpty())
		{
			throw new WikiPriceException("the wiki mapping table carried no entries");
		}
		return names;
	}

	// ---------------------------------------------------------------- internals

	/**
	 * Builds the request, enqueues it and adapts OkHttp's callback pair onto one future. Nothing in here blocks:
	 * the method returns as soon as the call is queued. The response is CLOSED before the future completes, so a
	 * dependent that starts another request never runs while a body is still open.
	 *
	 * <p><b>The future is completed on every path, including an {@link Error}</b> (B006). OkHttp 3.14.9 sets
	 * {@code signalledCallback} before it invokes {@code onResponse}, so a Throwable thrown from inside the
	 * callback is rethrown on the dispatcher thread and never routed to {@code onFailure}: the future would then
	 * never complete, and {@code PriceService}'s in-flight flag would stay claimed for the rest of the session -
	 * the whole history subsystem stopping with nothing on screen and nothing in the log. A parse of six 4,566-entry
	 * tables is exactly the shape that can raise {@code OutOfMemoryError} on a small heap, or
	 * {@code StackOverflowError} on a hostile body, so the catch is on {@code Throwable}, the future is completed
	 * FIRST, and an {@code Error} is rethrown afterwards for the JVM's own handling. {@link #fail} and
	 * {@link #failAndRethrow} are that order, spelled once: every failure path here goes through them.
	 *
	 * <p>The call is remembered for {@link #cancelInFlight()} until it answers.
	 */
	private <T> CompletableFuture<T> request(final String url, final BodyParser<T> parser)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();

		if (!enabled)
		{
			future.completeExceptionally(new WikiPriceException("wiki fetches are switched off (developer mode)"));
			return future;
		}

		final Request request;
		try
		{
			request = new Request.Builder()
				.url(url)
				.header(USER_AGENT_HEADER, USER_AGENT)
				.get()
				.build();
		}
		catch (final RuntimeException e)
		{
			// IllegalArgumentException from HttpUrl for a url this class built itself: a programming error, but
			// it must not escape onto the caller's thread (the service's executor) as an unchecked throw.
			future.completeExceptionally(new WikiPriceException("could not build the wiki request for " + url, e));
			return future;
		}

		try
		{
			final Call call = client.newCall(request);
			inFlight.add(call);
			future.whenComplete((value, error) -> inFlight.remove(call));
			call.enqueue(new Callback()
			{
				@Override
				public void onFailure(final Call call, final IOException e)
				{
					// Everything the injected client refuses lands here: no network, DNS, TLS, and the three
					// RuneLiteModule interceptor throws (client thread, EDT, non-LIVE environment domain block).
					fail(future, url, new WikiPriceException("wiki request failed: " + messageOf(e), e));
				}

				@Override
				public void onResponse(final Call call, final Response response)
				{
					final T value;
					try (Response closing = response)
					{
						final String body = bodyText(closing);
						if (!closing.isSuccessful())
						{
							fail(future, url, httpFailure(closing.code(), body));
							return;
						}
						value = parser.parse(body);
					}
					catch (final IOException e)
					{
						fail(future, url, e instanceof WikiPriceException
							? (WikiPriceException) e
							: new WikiPriceException("could not read the wiki response: " + messageOf(e), e));
						return;
					}
					catch (final Throwable t)
					{
						failAndRethrow(future, url, new WikiPriceException(
							"could not parse the wiki response: " + messageOf(t), t), t);
						return;
					}
					future.complete(value);
				}
			});
		}
		catch (final Throwable t)
		{
			// Throwable for the same reason: this runs on the service's executor, whose own guard used to stop at
			// RuntimeException, and an unanswered future is a flag claimed for the session.
			failAndRethrow(future, url, new WikiPriceException("could not queue the wiki request: " + messageOf(t), t), t);
		}

		return future;
	}

	/**
	 * The one way a request fails: {@link #report} the failure, THEN complete the future with it. Every failure
	 * site in {@link #request} goes through here, so the once-per-session warning has a single gate and no path
	 * can leave the future unanswered - which is the whole of B006, since an unanswered future leaves
	 * {@code PriceService}'s in-flight flag claimed for the rest of the session.
	 */
	private <T> void fail(final CompletableFuture<T> future, final String url, final WikiPriceException failure)
	{
		report(url, failure);
		future.completeExceptionally(failure);
	}

	/**
	 * {@link #fail} for a {@code catch} on {@link Throwable}: the future is completed FIRST and an {@link Error}
	 * is rethrown afterwards, for the JVM's own handling. The order is the point (class note above) - OkHttp
	 * 3.14.9 rethrows on its dispatcher thread rather than routing to {@code onFailure}, so a rethrow before the
	 * completion would strand the caller.
	 *
	 * @param cause what was caught; rethrown only when it is an {@link Error}
	 */
	private <T> void failAndRethrow(final CompletableFuture<T> future, final String url,
		final WikiPriceException failure, final Throwable cause)
	{
		fail(future, url, failure);
		if (cause instanceof Error)
		{
			throw (Error) cause;
		}
	}

	/**
	 * Cancels every call still on the wire. Called from {@code PriceService.stop()} when the plugin is switched
	 * off or its config profile changes: a batched body download is most of a megabyte, and neither finishing it
	 * nor parsing six tables afterwards can produce anything but a discarded result. {@code Call.cancel()} is safe
	 * from any thread; a cancelled call's {@code onFailure} completes its future as an ordinary failure, which the
	 * stopped service drops.
	 */
	public void cancelInFlight()
	{
		for (final Call call : new ArrayList<>(inFlight))
		{
			inFlight.remove(call);
			try
			{
				call.cancel();
			}
			catch (final RuntimeException e)
			{
				log.debug("bank-portfolio-tracker: a wiki call refused to cancel", e);
			}
		}
	}

	/**
	 * Logs one failure. The FIRST of the session is a warning carrying the url and, where the wiki answered with
	 * a status, that status; every one after it is debug (B010).
	 *
	 * <p>Why the first one is loud: RuneLite logs at INFO, so before this every network failure in the plugin -
	 * an outage, a proxy serving HTML, the 403 the prices API returns to a default User-Agent - left NOTHING in
	 * the user's client.log, while every DISK failure in the same plugin was a warning. A bug report then could
	 * not tell a 403 from a DNS failure without asking the user to reproduce with debug logging on. Why only the
	 * first: an outage lasts hours and the tick is thirty minutes, and a repeated line is noise in someone else's
	 * log.
	 */
	private void report(final String url, final WikiPriceException failure)
	{
		final String status = failure.hasHttpCode() ? " (HTTP " + failure.getHttpCode() + ")" : "";
		if (reported)
		{
			log.debug("bank-portfolio-tracker: wiki request to {} failed{}", url, status, failure);
			return;
		}
		reported = true;
		log.warn("bank-portfolio-tracker: wiki request to {} failed{} - movement will show stored data until it recovers",
			url, status, failure);
	}

	/** A future that has already failed - the shape {@link #fetchTables} refuses a bad argument with. */
	private static <T> CompletableFuture<T> failed(final WikiPriceException failure)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(failure);
		return future;
	}

	/**
	 * The revision ids worth asking for: non-null, positive, in the caller's order and each named once. A
	 * duplicate would be answered once by MediaWiki anyway; collapsing it here keeps the {@code revids=} count
	 * honest against {@value #MAX_REVIDS}.
	 */
	private static List<Long> usableIds(final Collection<Long> revIds)
	{
		final Set<Long> unique = new LinkedHashSet<>();
		if (revIds != null)
		{
			for (final Long revId : revIds)
			{
				if (revId != null && revId > 0L)
				{
					unique.add(revId);
				}
			}
		}
		return new ArrayList<>(unique);
	}

	/**
	 * Every revision object in a {@code query} answer, across every page it lists. Reads both the
	 * {@code formatversion=2} array of pages and the default object keyed by page id; a page that is
	 * {@code missing} simply has no revisions and contributes nothing.
	 *
	 * @throws WikiPriceException when there is no {@code query} object at all (a shape this parser cannot read)
	 */
	private static List<JsonObject> revisionsOf(final JsonObject root) throws WikiPriceException
	{
		final JsonElement query = root.get("query");
		if (query == null || !query.isJsonObject())
		{
			throw new WikiPriceException("the wiki revision response has no query object");
		}

		final List<JsonObject> revisions = new ArrayList<>();
		final JsonElement pages = query.getAsJsonObject().get("pages");
		if (pages == null)
		{
			return revisions;
		}

		if (pages.isJsonObject())
		{
			for (final Map.Entry<String, JsonElement> page : pages.getAsJsonObject().entrySet())
			{
				collectRevisions(page.getValue(), revisions);
			}
		}
		else if (pages.isJsonArray())
		{
			for (final JsonElement page : pages.getAsJsonArray())
			{
				collectRevisions(page, revisions);
			}
		}
		return revisions;
	}

	/** Appends one page's {@code revisions} array; a missing page or a malformed entry adds nothing. */
	private static void collectRevisions(final JsonElement page, final List<JsonObject> into)
	{
		if (page == null || !page.isJsonObject())
		{
			return;
		}

		final JsonElement revisions = page.getAsJsonObject().get("revisions");
		if (revisions == null || !revisions.isJsonArray())
		{
			return;
		}

		for (final JsonElement revision : revisions.getAsJsonArray())
		{
			if (revision != null && revision.isJsonObject())
			{
				into.add(revision.getAsJsonObject());
			}
		}
	}

	/**
	 * One revision's wikitext. {@code slots.main.content} is the {@code rvslots=main} shape this client asks for;
	 * the bare {@code content} and the pre-1.32 {@code *} members are read as well so a change of API default
	 * cannot silently empty every table.
	 */
	private static String contentOf(final JsonObject revision)
	{
		final JsonElement slots = revision.get("slots");
		if (slots != null && slots.isJsonObject())
		{
			final JsonElement main = slots.getAsJsonObject().get("main");
			if (main != null && main.isJsonObject())
			{
				final String text = rawText(main.getAsJsonObject().get("content"));
				if (text != null)
				{
					return text;
				}
			}
		}

		final String direct = rawText(revision.get("content"));
		return direct != null ? direct : rawText(revision.get("*"));
	}

	/** A JSON string member as its whole text - unlike {@link #memberText} this is never truncated. */
	private static String rawText(final JsonElement value)
	{
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive())
		{
			return null;
		}
		try
		{
			return value.getAsString();
		}
		catch (final RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * The prices out of one parsed table body, folded through {@code GuideSnapshot.key} and without the
	 * {@code %...%} bookkeeping.
	 *
	 * @throws WikiPriceException when nothing in the object is a price
	 */
	private static Map<String, Long> tableOf(final JsonObject root) throws WikiPriceException
	{
		final Map<String, Long> prices = new HashMap<>();
		for (final Map.Entry<String, JsonElement> entry : root.entrySet())
		{
			final String name = entry.getKey();
			if (name == null || name.isEmpty() || name.charAt(0) == BOOKKEEPING_PREFIX)
			{
				continue;
			}

			final Long price = asLong(entry.getValue());
			if (price == null)
			{
				continue;
			}

			final String folded = GuideSnapshot.key(name);
			if (folded != null && !folded.isEmpty())
			{
				prices.put(folded, price);
			}
		}

		if (prices.isEmpty())
		{
			throw new WikiPriceException("the wiki guide table carried no prices");
		}
		return prices;
	}

	/**
	 * The body's {@code %LAST_UPDATE%} in unix seconds, or 0 when it is absent or not a number - which
	 * {@link #parseTables} treats as "outside the allowed window" and refuses (L7). Never guessed from the
	 * revision timestamp: the whole point of the marker is that the two disagree on 4 of 38 sampled bodies.
	 */
	private static long dataSecondsOf(final JsonObject root)
	{
		final Long seconds = optLong(root, LAST_UPDATE_KEY);
		return seconds == null ? 0L : seconds;
	}

	/**
	 * A revision's {@code timestamp} as unix seconds, or null when it is absent or not ISO-8601. Null costs the
	 * revision its place in the index or the batch: without a save time there is no calendar day to select on
	 * and no window to validate a body's day marker against.
	 */
	private static Long optInstantSeconds(final JsonObject revision)
	{
		final String timestamp = memberText(revision, "timestamp");
		if (timestamp == null)
		{
			return null;
		}

		try
		{
			return Instant.parse(timestamp).getEpochSecond();
		}
		catch (final DateTimeParseException e)
		{
			log.debug("bank-portfolio-tracker: revision timestamp is not ISO-8601: {}", timestamp);
			return null;
		}
	}

	/**
	 * MediaWiki reports an error as {@code {"error":{"code":"...","info":"..."}}} with HTTP 200, so the body -
	 * not the status - is the failure test. Checked before anything else is read out of the envelope.
	 */
	private void failOnErrorMember(final JsonObject root) throws WikiPriceException
	{
		final String error = memberText(root, "error");
		if (error != null)
		{
			throw new WikiPriceException("wiki error: " + error);
		}
	}

	/** Reads the whole body as text, tolerating a null body (a HEAD-shaped or synthetic response). */
	private static String bodyText(final Response response) throws IOException
	{
		final ResponseBody body = response.body();
		return body == null ? "" : body.string();
	}

	/**
	 * A body as a {@link JsonObject}, or a failure. Parsed as {@link JsonElement} first so a non-object body (an
	 * array, or a bare string from Gson's lenient reader) becomes a clean failure rather than a
	 * ClassCastException out of {@code fromJson(json, JsonObject.class)}.
	 */
	private JsonObject readObject(final String json) throws WikiPriceException
	{
		final JsonElement root = readElement(json);
		if (!root.isJsonObject())
		{
			throw new WikiPriceException("wiki response was not a JSON object");
		}
		return root.getAsJsonObject();
	}

	/**
	 * A body as any JSON value, with the INJECTED Gson - constructing a Gson or a GsonBuilder in plugin code is
	 * a Plugin Hub blocker (C46), and Gson 2.8.5 (the version the pinned client ships) has no static
	 * {@code JsonParser.parseString} to reach for instead.
	 */
	private JsonElement readElement(final String json) throws WikiPriceException
	{
		if (json == null || json.trim().isEmpty())
		{
			throw new WikiPriceException("empty response from the wiki");
		}

		final JsonElement root;
		try
		{
			root = gson.fromJson(json, JsonElement.class);
		}
		catch (final RuntimeException e)
		{
			throw new WikiPriceException("malformed JSON from the wiki: " + messageOf(e), e);
		}

		if (root == null || root.isJsonNull())
		{
			// A literal "null" body: JSON-shaped, but there is nothing in it for any caller to read.
			throw new WikiPriceException("wiki response was a JSON null");
		}
		return root;
	}

	/**
	 * A nullable numeric member as a {@code Long}. Guide prices, revision ids and unix seconds all exceed what an
	 * {@code int} comfortably holds (a revid is already past 15 million and a price past two billion), so every
	 * number is read as a long. A member that is absent, JSON null, or not a number reads as null.
	 */
	private static Long optLong(final JsonObject object, final String key)
	{
		return object == null ? null : asLong(object.get(key));
	}

	/** One JSON value as a {@code Long}, or null when it is absent, null, or not a number. Never throws. */
	private static Long asLong(final JsonElement value)
	{
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive())
		{
			return null;
		}
		try
		{
			return Long.valueOf(value.getAsLong());
		}
		catch (final RuntimeException e)
		{
			// A boolean primitive, or a string that is not a number: skip the entry, never fail the refresh.
			return null;
		}
	}

	/** A member rendered as short text, or null when it is absent or JSON null. Never throws. */
	private static String memberText(final JsonObject root, final String key)
	{
		final JsonElement value = root.get(key);
		if (value == null || value.isJsonNull())
		{
			return null;
		}
		if (value.isJsonPrimitive())
		{
			try
			{
				return truncate(value.getAsString());
			}
			catch (final RuntimeException e)
			{
				// fall through to the structural rendering
			}
		}
		return truncate(value.toString());
	}

	/**
	 * A non-2xx answer. The status is carried on the exception because 403 is diagnosable on its own (both wiki
	 * hosts pre-emptively block default user agents, research C3) while the body for it is unhelpful.
	 */
	private WikiPriceException httpFailure(final int code, final String body)
	{
		String detail = null;
		try
		{
			detail = memberText(readObject(body), "error");
		}
		catch (final WikiPriceException e)
		{
			// A non-JSON error body (an HTML error page, cloudflare's one-byte 403) is normal here - the status
			// is the information.
		}

		final String message = detail == null
			? "wiki request failed with HTTP " + code
			: "wiki request failed with HTTP " + code + ": " + detail;
		return new WikiPriceException(message, code);
	}

	/** {@code Throwable.getMessage()} is null for plenty of IO failures; fall back to the type name. */
	private static String messageOf(final Throwable t)
	{
		final String message = t.getMessage();
		return truncate(message == null || message.isEmpty() ? t.getClass().getSimpleName() : message);
	}

	private static String truncate(final String text)
	{
		if (text == null)
		{
			return null;
		}
		return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
	}

	/**
	 * The one thing that differs between the three requests once the bytes are in hand. A tiny interface rather
	 * than a {@code Function} because the parsers throw a checked {@link WikiPriceException}.
	 */
	@FunctionalInterface
	private interface BodyParser<T>
	{
		T parse(String body) throws WikiPriceException;
	}
}
