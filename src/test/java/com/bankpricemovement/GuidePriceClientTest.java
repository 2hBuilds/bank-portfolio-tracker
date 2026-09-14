package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.junit.Before;
import org.junit.Test;

/**
 * {@link GuidePriceClient} against addendum L (L4, L6, L7, L8, L11), headless and offline: no real socket is
 * ever opened.
 *
 * <p>The seam is {@code OkHttpClient.newCall(Request)} - a public non-final method on a non-final class, so
 * Mockito 4.11 can mock it without mockito-inline. The mocked {@link Call} answers {@code enqueue} by invoking
 * the callback INLINE on the test thread, taking the next reply off a queue, so every future completes before
 * the fetch method returns and no latch and no sleep is needed anywhere (playbook 7.6). A FRESH {@link Response}
 * is built per reply - a response body can only be read once - and it is a real one built with
 * {@code Response.Builder}, so the production code runs against real OkHttp types (status, body,
 * {@code isSuccessful()}, {@code close()}), not against a mock's defaults. The harness is the one the addendum-K
 * build used, unchanged.
 *
 * <p><b>What changed under addendum L.</b> K fetched a baseline as two requests - "the newest revision at or
 * before instant X", then that revision's bytes from {@code index.php?action=raw}. Six analysts measured that
 * selector against live data and it names the wrong Jagex day on about half of all clock hours (L-C), so the
 * client now fetches the revision INDEX once (L4) and the bodies it needs in ONE batched {@code revids=} call
 * (L6); which revision a window wants is decided locally by {@link RevisionRef#pickThen}. Two rules moved into
 * this class with that change: a body is trusted only when its own {@code %LAST_UPDATE%} could belong to the
 * revision that carries it (L7), and every table key is folded through {@code GuideSnapshot.key} (L8).
 *
 * <p>Every fixture below is a REAL revision from
 * {@code docs/research/bank-price-movement-calibration-2026-09-08.md} and addendum L's evidence lines: revid
 * 15333448 @ 2026-09-07T19:55:12Z whose body carries {@code %LAST_UPDATE%} 1788810322 ("07 September 2026
 * 19:45:22 (UTC)"), Green hat 1124, Barrel 835, Abyssal whip 807253 and 3rd Age axe 2,147,483,646; and the
 * human edit 15329323 (Riblet15, 2026-09-03T06:46Z, "new items with initial ge prices") carrying the table
 * Jagex stamped on 2026-09-02.
 *
 * <p>{@code new Gson()} here follows the workspace's test precedent ({@code BeamStoreTest.java:76},
 * {@code PriceStoreTest.java:39} in this same package). The "no {@code new Gson(}" rule is a Plugin Hub
 * packaging rule for MAIN code, which {@link GuidePriceClient} obeys by taking the injected instance.
 */
public class GuidePriceClientTest
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** Used only to escape a table body into the JSON string the API wraps content in. */
	private static final Gson GSON = new Gson();

	private static final long NOW_MILLIS = 1_788_909_600_123L;

	/** 15333448, saved 2026-09-07T19:55:12Z; its body is stamped 2026-09-07T19:45:22Z. */
	private static final long REV_0907 = 15_333_448L;
	private static final String SAVED_0907 = "2026-09-07T19:55:12Z";
	private static final long SAVED_0907_SECONDS = 1_788_810_912L;
	private static final long DATA_0907_SECONDS = 1_788_810_322L;

	/** 15332677, saved 2026-09-06T19:15:12Z; body stamped 2026-09-06T19:05:00Z (1788721500). */
	private static final long REV_0906 = 15_332_677L;
	private static final String SAVED_0906 = "2026-09-06T19:15:12Z";
	private static final long DATA_0906_SECONDS = 1_788_721_500L;

	/**
	 * Every query parameter the plugin is allowed to send, spelled out (B035). One entry per
	 * {@code name=value} pair of L4's and L6's queries; {@code revids} is the only parameter whose value is
	 * chosen at runtime, and it may hold nothing but public wiki revision ids.
	 */
	private static final List<String> FIXED_PARAMETERS = Arrays.asList(
		"action=query",
		"prop=revisions",
		"titles=" + GuidePriceClient.PAGE,
		"rvlimit=" + GuidePriceClient.INDEX_LIMIT,
		"rvdir=older",
		"rvprop=ids|timestamp|user|comment",
		"rvprop=ids|timestamp|content",
		"rvslots=main",
		"format=json",
		"formatversion=2");

	/** 15329323 - Riblet15's edit, saved 2026-09-03T06:46:00Z with 2026-09-02T07:21:23Z prices in it. */
	private static final long REV_HUMAN = 15_329_323L;
	private static final String SAVED_HUMAN = "2026-09-03T06:46:00Z";
	private static final long DATA_HUMAN_SECONDS = 1_788_333_683L;

	/**
	 * The index answer of L4, in the {@code formatversion=2} shape the query asks for. Five real revisions, one
	 * of them the human edit that makes the bot preference necessary.
	 */
	private static final String INDEX_JSON = "{\"batchcomplete\":true,\"query\":{\"pages\":["
		+ "{\"pageid\":180412,\"ns\":828,\"title\":\"Module:GEPrices/data.json\",\"revisions\":["
		+ "{\"revid\":15334656,\"parentid\":15333448,\"timestamp\":\"2026-09-08T09:25:12Z\","
		+ "\"user\":\"Gaz GEBot\",\"comment\":\"GE update\"},"
		+ "{\"revid\":15333448,\"parentid\":15332677,\"timestamp\":\"2026-09-07T19:55:12Z\","
		+ "\"user\":\"Gaz GEBot\",\"comment\":\"GE update\"},"
		+ "{\"revid\":15332677,\"parentid\":15331360,\"timestamp\":\"2026-09-06T19:15:12Z\","
		+ "\"user\":\"Gaz GEBot\",\"comment\":\"GE update\"},"
		+ "{\"revid\":15330300,\"parentid\":15329323,\"timestamp\":\"2026-09-03T21:45:11Z\","
		+ "\"user\":\"Gaz GEBot\",\"comment\":\"GE update\"},"
		+ "{\"revid\":15329323,\"parentid\":15329000,\"timestamp\":\"2026-09-03T06:46:00Z\","
		+ "\"user\":\"Riblet15\",\"comment\":\"new items with initial ge prices\"}"
		+ "]}]}}";

	/**
	 * Revision 15333448's content, trimmed to six of its 4,566 keys - both {@code %} ones, and 3rd Age axe at
	 * 2,147,483,646, one short of {@code Integer.MAX_VALUE}. The page is served pretty-printed with tabs.
	 */
	private static final String TABLE_0907 = "{\n\t\"%LAST_UPDATE%\": 1788810322,\n"
		+ "\t\"%LAST_UPDATE_F%\": \"07 September 2026 19:45:22 (UTC)\",\n"
		+ "\t\"3rd Age axe\": 2147483646,\n"
		+ "\t\"Abyssal whip\": 807253,\n"
		+ "\t\"Barrel\": 835,\n"
		+ "\t\"Green hat\": 1124\n}";

	/** The day before, the same six keys with the figures moved a little. */
	private static final String TABLE_0906 = "{\"%LAST_UPDATE%\": 1788721500,"
		+ "\"%LAST_UPDATE_F%\": \"06 September 2026 19:05:00 (UTC)\","
		+ "\"Abyssal whip\": 799012,\"Barrel\": 796,\"Green hat\": 1086}";

	/** What Riblet15's edit republished: the table Jagex stamped a day before the edit was saved. */
	private static final String TABLE_HUMAN = "{\"%LAST_UPDATE%\": 1788333683,"
		+ "\"%LAST_UPDATE_F%\": \"02 September 2026 07:21:23 (UTC)\",\"Green hat\": 1090}";

	/**
	 * {@code /mapping}, trimmed: one full entry as served, a short one, and the item this table exists for -
	 * the bank's composition name for 8007 is "Varrock teleport", which is NOT a guide-table key, while the
	 * mapping's "Varrock teleport (tablet)" is (both checked live 2026-09-08).
	 */
	private static final String MAPPING_JSON = "["
		+ "{\"examine\":\"A weapon from the abyss.\",\"id\":4151,\"members\":true,\"lowalch\":72000,"
		+ "\"limit\":70,\"value\":120001,\"highalch\":108000,\"icon\":\"Abyssal whip.png\",\"name\":\"Abyssal whip\"},"
		+ "{\"id\":658,\"name\":\"Green hat\"},"
		+ "{\"id\":8007,\"name\":\"Varrock teleport (tablet)\"}"
		+ "]";

	private final List<Request> requests = new ArrayList<>();
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final AtomicInteger bodyCloses = new AtomicInteger();
	/** What died on the simulated dispatcher thread, so a test can assert the Error was not swallowed. */
	private final List<Throwable> dispatched = Collections.synchronizedList(new ArrayList<>());

	private OkHttpClient http;
	private Call call;
	private GuidePriceClient client;

	@Before
	public void setUp()
	{
		http = mock(OkHttpClient.class);
		call = mock(Call.class);
		client = new GuidePriceClient(http, new Gson());

		when(http.newCall(any(Request.class))).thenAnswer(invocation ->
		{
			requests.add(invocation.getArgument(0));
			return call;
		});

		doAnswer(invocation ->
		{
			final Callback callback = invocation.getArgument(0);
			final Reply reply = replies.poll();
			if (reply == null)
			{
				// Never silently: a missing reply shows up as an HTTP 599 in the assertion message.
				callback.onResponse(call, response(599, "TEST BUG: no reply queued for request " + requests.size()));
			}
			else if (reply.mode == Mode.PENDING)
			{
				// Still on the wire: the callback is never invoked, which is the state cancelInFlight exists for.
				return null;
			}
			else if (reply.mode == Mode.EXPLODE)
			{
				// OkHttp runs the callback on its OWN dispatcher thread and rethrows whatever escapes it there
				// (AsyncCall.execute sets signalledCallback first), so an Error must never reach the caller of
				// enqueue. Reproduced with a thread rather than inline, or the test could not hold the future.
				final Response exploding = response(200, null);
				final Thread dispatcher = new Thread(() ->
				{
					try
					{
						callback.onResponse(call, exploding);
					}
					catch (final IOException impossible)
					{
						throw new AssertionError(impossible);
					}
				});
				dispatcher.setUncaughtExceptionHandler((thread, thrown) -> dispatched.add(thrown));
				dispatcher.start();
				dispatcher.join();
			}
			else if (reply.failure != null)
			{
				callback.onFailure(call, reply.failure);
			}
			else
			{
				callback.onResponse(call, response(reply.code, reply.body));
			}
			return null;
		}).when(call).enqueue(any(Callback.class));
	}

	// ---------------------------------------------------------------- the constants and the URLs

	@Test
	public void theEndpointsAndTheUserAgentAreTheOnesTheContractNames()
	{
		assertEquals("https://oldschool.runescape.wiki/api.php", GuidePriceClient.API_URL);
		assertEquals("Module:GEPrices/data.json", GuidePriceClient.PAGE);
		assertEquals("https://prices.runescape.wiki/api/v1/osrs/mapping", GuidePriceClient.MAPPING_URL);
		assertEquals("the UA text is unchanged from the first build (K12)",
			"bank-portfolio-tracker (RuneLite plugin; github.com/2hBuilds)", GuidePriceClient.USER_AGENT);
		assertFalse("RuneLite only PREPENDS its own UA when ours does not already start with it "
				+ "(RuneLite.java:425-429) - starting with \"RuneLite\" would hide the client version",
			GuidePriceClient.USER_AGENT.startsWith("RuneLite"));

		assertEquals("250 is the anonymous rvlimit ceiling and reaches about 232 days back (L4)",
			250, GuidePriceClient.INDEX_LIMIT);
		assertEquals("MediaWiki's own revids ceiling (L6)", 50, GuidePriceClient.MAX_REVIDS);
		assertEquals("%LAST_UPDATE%", GuidePriceClient.LAST_UPDATE_KEY);
	}

	/** The query of L4, character for character - the shape a maintainer pastes into curl to reproduce a bug. */
	@Test
	public void theRevisionIndexUrlIsTheOneAddendumLNames()
	{
		assertEquals("https://oldschool.runescape.wiki/api.php?action=query&prop=revisions"
				+ "&titles=Module:GEPrices/data.json&rvlimit=250&rvdir=older"
				+ "&rvprop=ids|timestamp|user|comment&format=json&formatversion=2",
			GuidePriceClient.revisionIndexUrl());
	}

	/**
	 * The batched body call of L6. {@code rvslots=main} is not optional (since MediaWiki 1.32 a revision's text
	 * lives in a slot), and {@code action=raw} - K's route, served uncompressed at 126 KB per revision - must be
	 * gone from the client entirely.
	 */
	@Test
	public void theBatchedContentUrlAsksForEveryRevisionAtOnce()
	{
		final String url = GuidePriceClient.tablesUrl(Arrays.asList(REV_0907, REV_0906));

		assertEquals("https://oldschool.runescape.wiki/api.php?action=query&prop=revisions"
				+ "&revids=15333448|15332677&rvprop=ids|timestamp|content&rvslots=main"
				+ "&format=json&formatversion=2", url);
		assertFalse("index.php?action=raw is not used any more (L6)", url.contains("action=raw"));
		assertEquals("one id needs no separator", "&revids=15333448&",
			url(Collections.singletonList(REV_0907), "&revids=", "&"));
	}

	/**
	 * B035, the evidence the Hub manifest's {@code warning=} decision rests on: NO request this plugin makes
	 * carries anything derived from the player, so every user's requests are byte-identical and the hosts learn
	 * only an IP. The two URL tests above pin today's three routes character for character; this pins the RULE
	 * behind them, so that a fourth parameter - an item id, a name, a search box, an account marker - fails here
	 * rather than in a manifest review. Every parameter must be one of the fixed pieces the contract names, and
	 * the one variable parameter, {@code revids}, may carry nothing but public wiki revision ids.
	 */
	@Test
	public void theRequestUrlsCarryNothingDerivedFromThePlayer()
	{
		assertCarriesNoPlayerData(GuidePriceClient.revisionIndexUrl());
		assertCarriesNoPlayerData(GuidePriceClient.tablesUrl(Arrays.asList(REV_0907, REV_0906, REV_HUMAN)));

		assertEquals("the mapping table is a bare GET - a query on it could only be player data",
			"https://prices.runescape.wiki/api/v1/osrs/mapping", GuidePriceClient.MAPPING_URL);
		assertFalse(GuidePriceClient.MAPPING_URL.contains("?"));
	}

	/** Every query parameter of one built URL is a fixed constant, or {@code revids} carrying only ids. */
	private static void assertCarriesNoPlayerData(final String url)
	{
		final int query = url.indexOf('?');
		assertTrue(url + " should have a query to check", query > 0);

		for (final String parameter : url.substring(query + 1).split("&"))
		{
			if (parameter.startsWith("revids="))
			{
				assertTrue("revids may carry public wiki revision ids and nothing else: " + parameter,
					parameter.substring("revids=".length()).matches("[0-9|]+"));
				continue;
			}

			assertTrue(url + " carries a parameter no contract names - if it came from the player's bank it "
					+ "must not be sent at all, and if it did not, add it here: " + parameter,
				FIXED_PARAMETERS.contains(parameter));
		}
	}

	// ---------------------------------------------------------------- L4: the revision index

	@Test
	public void theIndexIsOneRequestCarryingTheDescriptiveUserAgent() throws Exception
	{
		queue(INDEX_JSON);

		final List<RevisionRef> index = client.fetchRevisionIndex(NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals("about 232 days of history in one call - never a request per window", 1, requests.size());
		assertEquals(GuidePriceClient.revisionIndexUrl(), requests.get(0).url().toString());
		assertEquals("GET", requests.get(0).method());
		assertEquals("without a descriptive UA the wiki hosts answer 403 (research C3)",
			GuidePriceClient.USER_AGENT, requests.get(0).header("User-Agent"));
		assertEquals(5, index.size());
	}

	@Test
	public void theIndexComesBackNewestFirstWithEachRevisionsDayAndAuthor() throws Exception
	{
		queue(INDEX_JSON);

		final List<RevisionRef> index = client.fetchRevisionIndex(NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals(15_334_656L, index.get(0).revId());
		assertEquals(REV_0907, index.get(1).revId());
		assertEquals(REV_HUMAN, index.get(4).revId());

		assertEquals(SAVED_0907_SECONDS, index.get(1).editSeconds());
		assertEquals(LocalDate.of(2026, 9, 7), index.get(1).editDay());
		assertEquals("Gaz GEBot", index.get(1).user());
		assertTrue(index.get(1).isBot());
	}

	/**
	 * The whole reason {@code user} and {@code comment} are in the query: Riblet15's edit is on the same date as
	 * a bot run, and its body carries the PREVIOUS day's prices (L-E). Without these two fields the selection
	 * rule could not tell the two apart.
	 */
	@Test
	public void theIndexKeepsWhoSavedEachRevisionSoTheBotCanBePreferred() throws Exception
	{
		queue(INDEX_JSON);

		final List<RevisionRef> index = client.fetchRevisionIndex(NOW_MILLIS).get(5, TimeUnit.SECONDS);
		final RevisionRef human = index.get(4);

		assertEquals("Riblet15", human.user());
		assertEquals("new items with initial ge prices", human.comment());
		assertFalse(human.isBot());
		assertEquals("both revisions of 2026-09-03 are in the index", LocalDate.of(2026, 9, 3), human.editDay());
		assertEquals("and the rule picks the bot's", 15_330_300L,
			RevisionRef.pickThen(index, LocalDate.of(2026, 9, 3)).revId());
	}

	/** Sorted rather than trusted: a change of default ordering at the wiki must not reorder the echo. */
	@Test
	public void theIndexIsSortedEvenWhenTheWikiAnswersOutOfOrder() throws Exception
	{
		final List<RevisionRef> index = client.parseRevisionIndex("{\"query\":{\"pages\":[{\"revisions\":["
			+ "{\"revid\":15332677,\"timestamp\":\"2026-09-06T19:15:12Z\"},"
			+ "{\"revid\":15334656,\"timestamp\":\"2026-09-08T09:25:12Z\"},"
			+ "{\"revid\":15333448,\"timestamp\":\"2026-09-07T19:55:12Z\"}]}]}}");

		assertEquals(15_334_656L, index.get(0).revId());
		assertEquals(15_333_448L, index.get(1).revId());
		assertEquals(15_332_677L, index.get(2).revId());
	}

	/** The wiki could switch its default output format; a silently empty index is worse than a loud failure. */
	@Test
	public void parseRevisionIndexAlsoReadsTheLegacyPagesObjectShape() throws Exception
	{
		final List<RevisionRef> index = client.parseRevisionIndex("{\"batchcomplete\":\"\",\"query\":{\"pages\":{"
			+ "\"180412\":{\"pageid\":180412,\"title\":\"Module:GEPrices/data.json\",\"revisions\":["
			+ "{\"revid\":15333448,\"timestamp\":\"2026-09-07T19:55:12Z\",\"user\":\"Gaz GEBot\","
			+ "\"comment\":\"GE update\"}]}}}}");

		assertEquals(1, index.size());
		assertEquals(REV_0907, index.get(0).revId());
		verify(http, never()).newCall(any(Request.class));
	}

	@Test
	public void aRevisionThatCannotBePlacedInTimeIsSkippedRatherThanFatal() throws Exception
	{
		final List<RevisionRef> index = client.parseRevisionIndex("{\"query\":{\"pages\":[{\"revisions\":["
			+ "{\"timestamp\":\"2026-09-08T09:25:12Z\"},"
			+ "{\"revid\":0,\"timestamp\":\"2026-09-08T09:25:12Z\"},"
			+ "{\"revid\":15333449,\"timestamp\":\"yesterday\"},"
			+ "{\"revid\":15333450},"
			+ "\"not an object\",null,"
			+ "{\"revid\":15333448,\"timestamp\":\"2026-09-07T19:55:12Z\"}]}]}}");

		assertEquals("without an id or a timestamp there is no calendar day to select on", 1, index.size());
		assertEquals(REV_0907, index.get(0).revId());
		assertEquals("a suppressed author is not a reason to drop a revision", "", index.get(0).user());
	}

	@Test
	public void parseRevisionIndexRefusesEveryShapeThatNamesNoRevision()
	{
		assertIndexFails("{\"query\":{\"pages\":[{\"pageid\":180412,\"title\":\"Module:GEPrices/data.json\"}]}}",
			"no usable revisions");
		// A missing page (a rename or a typo in PAGE).
		assertIndexFails("{\"query\":{\"pages\":[{\"ns\":828,\"title\":\"Module:Nope\",\"missing\":true}]}}",
			"no usable revisions");
		assertIndexFails("{\"query\":{\"pages\":[]}}", "no usable revisions");
		assertIndexFails("{\"query\":{}}", "no usable revisions");
		assertIndexFails("{\"batchcomplete\":true}", "no query object");
		// MediaWiki reports an API error with HTTP 200, so the body is the failure test.
		assertIndexFails("{\"error\":{\"code\":\"unknown_action\",\"info\":\"Unrecognized value\"}}", "wiki error");
		assertIndexFails("[1,2,3]", "not a JSON object");
		assertIndexFails("", "empty response");
		assertIndexFails(null, "empty response");
		assertIndexFails("{\"query\":{\"pages\":", "malformed");
	}

	// ---------------------------------------------------------------- L6: the batched bodies

	@Test
	public void everyNeededTableArrivesInOneRequest() throws Exception
	{
		queue(tablesJson(revision(REV_0907, SAVED_0907, TABLE_0907), revision(REV_0906, SAVED_0906, TABLE_0906)));

		final Map<Long, GuideSnapshot> tables =
			client.fetchTables(Arrays.asList(REV_0907, REV_0906), NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals("two whole tables, one call - 76.6 KB on the wire when the lead measured it (L-G)",
			1, requests.size());
		assertEquals(GuidePriceClient.tablesUrl(Arrays.asList(REV_0907, REV_0906)),
			requests.get(0).url().toString());
		assertEquals(GuidePriceClient.USER_AGENT, requests.get(0).header("User-Agent"));

		assertEquals(2, tables.size());
		assertEquals(Long.valueOf(1124L), tables.get(REV_0907).get("Green hat"));
		assertEquals(Long.valueOf(1086L), tables.get(REV_0906).get("Green hat"));
		assertEquals("the figure the GE site showed the same day", Long.valueOf(799_012L),
			tables.get(REV_0906).get("Abyssal whip"));
	}

	/**
	 * L7: the label on a row is the table's own {@code %LAST_UPDATE%} day, not the minute somebody pressed save.
	 */
	@Test
	public void aTableCarriesItsOwnDayMarkerBesideItsSaveTime() throws Exception
	{
		queue(tablesJson(revision(REV_0907, SAVED_0907, TABLE_0907)));

		final GuideSnapshot table = client.fetchTables(Collections.singletonList(REV_0907), NOW_MILLIS)
			.get(5, TimeUnit.SECONDS).get(REV_0907);

		assertEquals(REV_0907, table.revId());
		assertEquals("the save time, kept for cache identity only", SAVED_0907_SECONDS, table.revisionSeconds());
		assertEquals(DATA_0907_SECONDS, table.dataSeconds());
		assertEquals(LocalDate.of(2026, 9, 7), table.dataDay());
		assertEquals(NOW_MILLIS, table.fetchedAtMillis());
	}

	/**
	 * Riblet15's edit must come back with its real day (2026-09-02) rather than be thrown away: it is inside the
	 * 48-hour window a human republish lives in, and it is {@code PriceService} - comparing
	 * {@code dataDay()} against the date it asked for - that decides to retry with the revision underneath it
	 * (L5). Silently dropping it here would leave the window with no baseline at all.
	 */
	@Test
	public void aHumanEditsStaleTableIsKeptSoTheServiceCanNoticeItIsStale() throws Exception
	{
		queue(tablesJson(revision(REV_HUMAN, SAVED_HUMAN, TABLE_HUMAN)));

		final GuideSnapshot table = client.fetchTables(Collections.singletonList(REV_HUMAN), NOW_MILLIS)
			.get(5, TimeUnit.SECONDS).get(REV_HUMAN);

		assertEquals(DATA_HUMAN_SECONDS, table.dataSeconds());
		assertEquals("saved on the 3rd", LocalDate.of(2026, 9, 3), RevisionRef.dayOf(table.revisionSeconds()));
		assertEquals("carrying the 2nd's prices", LocalDate.of(2026, 9, 2), table.dataDay());
	}

	/**
	 * The L7 guard. A marker that cannot belong to the revision carrying it - days behind, or ahead of the edit
	 * itself, or missing altogether - means the body is not this page's data, and guessing a day for it would
	 * put a date on a row that its numbers are not from.
	 */
	@Test
	public void aBodyWhoseDayMarkerCannotBelongToItIsDropped() throws Exception
	{
		final String fiveDaysBehind = "{\"%LAST_UPDATE%\": 1788378112,\"Green hat\": 1090}";
		final String anHourAhead = "{\"%LAST_UPDATE%\": 1788814512,\"Green hat\": 1090}";
		final String noMarker = "{\"Green hat\": 1090}";

		queue(tablesJson(
			revision(REV_0907, SAVED_0907, TABLE_0907),
			revision(1L, SAVED_0907, fiveDaysBehind),
			revision(2L, SAVED_0907, anHourAhead),
			revision(3L, SAVED_0907, noMarker)));

		final Map<Long, GuideSnapshot> tables =
			client.fetchTables(Arrays.asList(REV_0907, 1L, 2L, 3L), NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals("only the body whose marker fits its own save time survives", 1, tables.size());
		assertNotNull(tables.get(REV_0907));
	}

	@Test
	public void oneUnusableRevisionDoesNotCostTheOthers() throws Exception
	{
		queue(tablesJson(
			revision(REV_0907, SAVED_0907, TABLE_0907),
			"{\"revid\":15330300,\"timestamp\":\"2026-09-03T21:45:11Z\"}",
			revision(REV_0906, SAVED_0906, TABLE_0906)));

		final Map<Long, GuideSnapshot> tables =
			client.fetchTables(Arrays.asList(REV_0907, 15_330_300L, REV_0906), NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals("a revision with no content is left out; the other two still land", 2, tables.size());
		assertNull(tables.get(15_330_300L));
	}

	@Test
	public void aBatchInWhichNothingSurvivedIsAFailure()
	{
		queue(tablesJson("{\"revid\":15330300,\"timestamp\":\"2026-09-03T21:45:11Z\"}"));

		assertTrue(failureOf(client.fetchTables(Collections.singletonList(15_330_300L), NOW_MILLIS))
			.getMessage().contains("no usable guide tables"));
	}

	@Test
	public void aBadRevidAnswerIsAFailureRatherThanAnEmptySuccess()
	{
		// What MediaWiki answers for an id that does not exist: the page list is absent and badrevids names it.
		assertTablesFail("{\"batchcomplete\":true,\"query\":{\"badrevids\":{\"999\":{\"revid\":999}}}}",
			"no usable guide tables");
		assertTablesFail("{\"error\":{\"code\":\"badvalue\",\"info\":\"Invalid value for revids\"}}", "wiki error");
		assertTablesFail("{\"batchcomplete\":true}", "no query object");
		assertTablesFail("", "empty response");
	}

	/** The bookkeeping keys and the "unpriced" sentinel must never reach a row (L8 c). */
	@Test
	public void theSentinelAndTheBookkeepingKeysNeverReachASnapshot() throws Exception
	{
		queue(tablesJson(revision(REV_0907, SAVED_0907, TABLE_0907)));

		final GuideSnapshot table = client.fetchTables(Collections.singletonList(REV_0907), NOW_MILLIS)
			.get(5, TimeUnit.SECONDS).get(REV_0907);

		assertEquals("three priced names out of six keys", 3, table.size());
		assertNull("2,147,483,646 means unpriced, not two billion gp", table.get("3rd Age axe"));
		assertNull(table.get("%LAST_UPDATE%"));
		assertNull(table.get("%LAST_UPDATE_F%"));
	}

	/** A lookup goes through the same fold as the table, so the August 2026 case rename cannot hide a row. */
	@Test
	public void aTableIsKeyedThroughTheFoldSoARenamedKeyIsStillFound() throws Exception
	{
		queue(tablesJson(revision(REV_0906, SAVED_0906,
			"{\"%LAST_UPDATE%\": 1788721500,\"3rd age amulet\": 2100000}")));

		final GuideSnapshot table = client.fetchTables(Collections.singletonList(REV_0906), NOW_MILLIS)
			.get(5, TimeUnit.SECONDS).get(REV_0906);

		assertEquals("the spelling the mapping uses today", Long.valueOf(2_100_000L),
			table.get("3rd Age amulet"));
	}

	/** Belt and braces on a MediaWiki that answers without slots: the text must still be found. */
	@Test
	public void parseTablesAlsoReadsTheUnslottedContentShapes() throws Exception
	{
		final Map<Long, GuideSnapshot> bare = client.parseTables("{\"query\":{\"pages\":[{\"revisions\":["
			+ "{\"revid\":15333448,\"timestamp\":\"2026-09-07T19:55:12Z\",\"content\":"
			+ GSON.toJson(TABLE_0907) + "}]}]}}", NOW_MILLIS);
		assertEquals(Long.valueOf(1124L), bare.get(REV_0907).get("Green hat"));

		final Map<Long, GuideSnapshot> legacy = client.parseTables("{\"query\":{\"pages\":{\"180412\":{\"revisions\":["
			+ "{\"revid\":15333448,\"timestamp\":\"2026-09-07T19:55:12Z\",\"*\":"
			+ GSON.toJson(TABLE_0907) + "}]}}}}", NOW_MILLIS);
		assertEquals(Long.valueOf(835L), legacy.get(REV_0907).get("Barrel"));
	}

	@Test
	public void aBatchWithNothingWorthAskingForNeverTouchesTheNetwork()
	{
		assertTrue(failureOf(client.fetchTables(Collections.emptyList(), NOW_MILLIS))
			.getMessage().contains("no usable revision ids"));
		assertNotNull(failureOf(client.fetchTables(null, NOW_MILLIS)));
		assertNotNull(failureOf(client.fetchTables(Arrays.asList(null, 0L, -5L), NOW_MILLIS)));

		final List<Long> tooMany = new ArrayList<>();
		for (long revId = 1L; revId <= GuidePriceClient.MAX_REVIDS + 1L; revId++)
		{
			tooMany.add(revId);
		}
		assertTrue(failureOf(client.fetchTables(tooMany, NOW_MILLIS)).getMessage().contains("at most 50"));

		verify(http, never()).newCall(any(Request.class));
	}

	@Test
	public void aDuplicateOrUnusableIdIsCollapsedRatherThanAskedForTwice() throws Exception
	{
		queue(tablesJson(revision(REV_0907, SAVED_0907, TABLE_0907), revision(REV_0906, SAVED_0906, TABLE_0906)));

		client.fetchTables(Arrays.asList(REV_0907, null, REV_0906, REV_0907, 0L), NOW_MILLIS)
			.get(5, TimeUnit.SECONDS);

		assertEquals(GuidePriceClient.tablesUrl(Arrays.asList(REV_0907, REV_0906)),
			requests.get(0).url().toString());
	}

	// ---------------------------------------------------------------- L11: failure shapes

	@Test
	public void everyRequestEnqueuesAndNoneExecutes() throws Exception
	{
		queue(INDEX_JSON);
		queue(tablesJson(revision(REV_0907, SAVED_0907, TABLE_0907)));

		client.fetchRevisionIndex(NOW_MILLIS).get(5, TimeUnit.SECONDS);
		client.fetchTables(Collections.singletonList(REV_0907), NOW_MILLIS).get(5, TimeUnit.SECONDS);

		verify(call, times(2)).enqueue(any(Callback.class));
		// A blocking call is refused outright on the client thread and the EDT (RuneLiteModule.java:185-192),
		// so Call.execute() must never appear on this path. This is the one deliberate mention of it.
		verify(call, never()).execute();
	}

	@Test
	public void anHttpFailureCarriesItsStatusCode()
	{
		queue(403, ".");
		assertEquals(403, failureOf(client.fetchRevisionIndex(NOW_MILLIS)).getHttpCode());

		queue(500, "upstream error");
		assertEquals(500, failureOf(client.fetchTables(Collections.singletonList(REV_0907), NOW_MILLIS))
			.getHttpCode());

		queue(500, "upstream error");
		assertEquals(500, failureOf(client.fetchMapping(NOW_MILLIS)).getHttpCode());
	}

	/**
	 * Everything the injected client refuses arrives as a plain {@code IOException} in {@code onFailure} - and
	 * {@code PriceService} catches ONE type and never string-matches a message (research C4).
	 */
	@Test
	public void everyTransportFailureArrivesAsAWikiPriceException()
	{
		queue(new IOException("Network call to https://oldschool.runescape.wiki/ blocked outside of LIVE environment"));

		final WikiPriceException failure = failureOf(client.fetchRevisionIndex(NOW_MILLIS));

		assertTrue(failure.getMessage(), failure.getMessage().contains("blocked outside of LIVE environment"));
		assertFalse(failure.hasHttpCode());
		assertTrue("the original IOException must stay as the cause", failure.getCause() instanceof IOException);
	}

	@Test
	public void everyResponseBodyIsClosed() throws Exception
	{
		queue(INDEX_JSON);
		client.fetchRevisionIndex(NOW_MILLIS).get(5, TimeUnit.SECONDS);
		assertEquals("a leaked body holds a connection open for the whole session", 1, bodyCloses.get());

		queue(403, ".");
		failureOf(client.fetchRevisionIndex(NOW_MILLIS));
		assertEquals("a failed body too", 2, bodyCloses.get());
	}

	// ---------------------------------------------------------------- L8: the mapping

	@Test
	public void theMappingIsOneRequestToTheExactUrlAndComesBackKeyedById() throws Exception
	{
		queue(MAPPING_JSON);

		final Map<Integer, String> names = client.fetchMapping(NOW_MILLIS).get(5, TimeUnit.SECONDS);

		assertEquals(1, requests.size());
		assertEquals(GuidePriceClient.MAPPING_URL, requests.get(0).url().toString());
		assertEquals(GuidePriceClient.USER_AGENT, requests.get(0).header("User-Agent"));

		assertEquals(3, names.size());
		assertEquals("names keep the wiki's own spelling - the fold happens at lookup time (L8)",
			"Abyssal whip", names.get(4151));
		assertEquals("Green hat", names.get(658));
		assertEquals("the whole point of the mapping: the bank calls 8007 \"Varrock teleport\", which the guide "
				+ "table does not list, and the mapping's spelling - which it does - is this one",
			"Varrock teleport (tablet)", names.get(8007));
	}

	@Test
	public void parseMappingReadsIdAndNameAndIgnoresEveryOtherField() throws Exception
	{
		final Map<Integer, String> names = client.parseMapping(MAPPING_JSON);

		assertEquals(3, names.size());
		assertEquals("examine, members, lowalch, limit, value, highalch and icon are all ignored",
			"Abyssal whip", names.get(4151));
	}

	@Test
	public void parseMappingSkipsEntriesItCannotUse() throws Exception
	{
		final Map<Integer, String> names = client.parseMapping("[{\"id\":4151,\"name\":\"Abyssal whip\"},"
			+ "{\"name\":\"No id\"},{\"id\":7},{\"id\":0,\"name\":\"Zero\"},{\"id\":8,\"name\":\"\"},"
			+ "\"not an object\",null,{\"id\":\"658\",\"name\":\"Green hat\"}]");

		assertEquals(2, names.size());
		assertEquals("Abyssal whip", names.get(4151));
		assertEquals("a quoted id still reads", "Green hat", names.get(658));
	}

	@Test
	public void parseMappingRefusesAnythingThatIsNotAUsableArray()
	{
		assertMappingFails("{\"data\":[]}", "not a JSON array");
		assertMappingFails("[]", "no entries");
		assertMappingFails("[{\"name\":\"No id\"}]", "no entries");
		// Gson's reader is lenient, so a bare word parses to a string primitive rather than throwing; the shape
		// check is what catches it.
		assertMappingFails("nonsense", "not a JSON array");
		assertMappingFails("[{\"id\":4151,", "malformed");
		assertMappingFails(null, "empty response");
	}

	// ---------------------------------------------------------------- parseGuideTable on its own

	@Test
	public void parseGuideTableKeepsThePricesAndDropsThePageBookkeeping() throws Exception
	{
		final Map<String, Long> table = client.parseGuideTable(TABLE_0907);

		assertEquals(4, table.size());
		assertEquals(Long.valueOf(1124L), table.get("green hat"));
		assertEquals(Long.valueOf(835L), table.get("barrel"));
		assertEquals(Long.valueOf(807_253L), table.get("abyssal whip"));
		assertEquals("the sentinel survives the PARSE and is dropped by the model, in one place (L8 c)",
			Long.valueOf(2_147_483_646L), table.get("3rd age axe"));
		assertNull("%LAST_UPDATE% is the page's own bookkeeping, not an item", table.get("%LAST_UPDATE%"));
		assertNull("and it is a NUMBER, so it would have parsed happily if the % rule were missing",
			table.get("%last_update_f%"));
		assertNull("every key is folded, so the raw spelling is not a key any more", table.get("Green hat"));
	}

	@Test
	public void parseGuideTableSkipsOneOddEntryRatherThanLosingTheOtherFourThousand() throws Exception
	{
		final Map<String, Long> table = client.parseGuideTable("{\"Green hat\":1124,\"Broken\":null,"
			+ "\"Alsobroken\":true,\"Worse\":\"soon\",\"Nested\":{\"gp\":5},\"Quoted\":\"1086\"}");

		assertEquals(2, table.size());
		assertEquals(Long.valueOf(1124L), table.get("green hat"));
		assertEquals("Gson reads a quoted number as a long", Long.valueOf(1086L), table.get("quoted"));
		assertNull(table.get("broken"));
		assertNull(table.get("alsobroken"));
		assertNull(table.get("nested"));
	}

	/**
	 * A JSON-shaped answer with no prices in it means the page moved or the revision was deleted. Publishing it
	 * would show "-" on every row and look exactly like a working plugin on a quiet market.
	 */
	@Test
	public void parseGuideTableRefusesATableWithNoPricesInIt()
	{
		assertTableFails("{}", "no prices");
		assertTableFails("{\"%LAST_UPDATE%\":1788810322}", "no prices");
		assertTableFails("[]", "not a JSON object");
		assertTableFails("{\"Green hat\":", "malformed");
		assertTableFails("   ", "empty response");
	}

	// ---------------------------------------------------------------- L13: the wiki=on|off dev switch

	@Test
	public void theSwitchIsOnByDefault()
	{
		assertTrue(client.isEnabled());
	}

	@Test
	public void switchingItOffFailsEveryFetchWithoutTouchingTheNetwork()
	{
		client.setEnabled(false);

		assertNotNull(failureOf(client.fetchRevisionIndex(NOW_MILLIS)));
		assertNotNull(failureOf(client.fetchTables(Collections.singletonList(REV_0907), NOW_MILLIS)));
		assertNotNull(failureOf(client.fetchMapping(NOW_MILLIS)));
		verify(http, never()).newCall(any(Request.class));

		client.setEnabled(true);
		assertTrue(client.isEnabled());
	}

	// ---------------------------------------------------------------- the constructor guards

	@Test
	public void theClientRefusesNullDependencies()
	{
		try
		{
			new GuidePriceClient(null, new Gson());
			fail("a null OkHttpClient must be refused at construction, not at the first fetch");
		}
		catch (final IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("client"));
		}

		try
		{
			new GuidePriceClient(http, null);
			fail("a null Gson must be refused at construction");
		}
		catch (final IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("gson"));
		}
	}

	@Test
	public void aRuntimeFailureFromTheHttpClientDoesNotEscapeToTheCaller()
	{
		when(http.newCall(any(Request.class))).thenThrow(new IllegalStateException("dispatcher is shut down"));

		assertTrue(failureOf(client.fetchMapping(NOW_MILLIS)).getMessage().contains("dispatcher is shut down"));
	}

	// ---------------------------------------------------------------- B006 / B012: the future always answers

	/**
	 * Every path out of the callback completes the caller's future, an {@link Error} included.
	 *
	 * <p>OkHttp 3.14.9 sets {@code signalledCallback} BEFORE it invokes {@code onResponse}, so a Throwable
	 * escaping the callback is rethrown on the dispatcher thread and never routed to {@code onFailure}. Catching
	 * only {@code RuntimeException} therefore left the future unanswered for good - and with it
	 * {@code PriceService}'s in-flight flag, which every later reconcile defers on: the whole history subsystem
	 * stopping for the session with nothing on screen, nothing in the log and no recovery but toggling the
	 * plugin. Parsing six 4,566-entry tables is exactly where an {@code OutOfMemoryError} belongs, so the future
	 * is completed FIRST and the Error is rethrown afterwards for the JVM's own handling.
	 */
	@Test
	public void anErrorInsideTheCallbackStillCompletesTheFuture()
	{
		queueAnErrorInsideTheCallback();

		final CompletableFuture<Map<Integer, String>> future = client.fetchMapping(NOW_MILLIS);

		assertTrue("the caller is answered rather than left waiting for ever", future.isDone());
		assertTrue(failureOf(future).getMessage(),
			failureOf(future).getMessage().contains("could not parse the wiki response"));
		assertEquals("and the Error itself is not swallowed", 1, dispatched.size());
		assertTrue("it is rethrown as it was", dispatched.get(0) instanceof OutOfMemoryError);
	}

	/**
	 * Nothing held the {@link Call}, so a batch already on the wire when the plugin was switched off was still
	 * downloaded in full - most of a megabyte - and still parsed into six 4,566-entry maps on OkHttp's thread,
	 * only for the stopped service to drop the result. {@code Call.cancel()} is safe from any thread.
	 */
	@Test
	public void cancelInFlightCancelsWhatIsStillOnTheWireAndNothingElse()
	{
		queueNoAnswer();
		final CompletableFuture<Map<Integer, String>> pending = client.fetchMapping(NOW_MILLIS);
		assertFalse("the request is still out", pending.isDone());

		client.cancelInFlight();

		verify(call).cancel();

		client.cancelInFlight();

		verify(call, times(1)).cancel();

		queue(MAPPING_JSON);
		client.fetchMapping(NOW_MILLIS).join();
		client.cancelInFlight();

		verify(call, times(1)).cancel();
	}

	// ---------------------------------------------------------------- helpers

	/** One revision object of a batched answer, with its table escaped into the JSON string the API wraps it in. */
	private static String revision(final long revId, final String timestamp, final String content)
	{
		return "{\"revid\":" + revId + ",\"parentid\":0,\"timestamp\":\"" + timestamp + "\","
			+ "\"slots\":{\"main\":{\"contentmodel\":\"json\",\"contentformat\":\"application/json\","
			+ "\"content\":" + GSON.toJson(content) + "}}}";
	}

	/** The batched answer: every requested revision of the one page, in one {@code revisions} array. */
	private static String tablesJson(final String... revisions)
	{
		final StringBuilder json = new StringBuilder("{\"batchcomplete\":true,\"query\":{\"pages\":[{"
			+ "\"pageid\":180412,\"ns\":828,\"title\":\"Module:GEPrices/data.json\",\"revisions\":[");
		for (int i = 0; i < revisions.length; i++)
		{
			if (i > 0)
			{
				json.append(',');
			}
			json.append(revisions[i]);
		}
		return json.append("]}]}}").toString();
	}

	/** The slice of a built url between two markers - used to read one query parameter back out of it. */
	private static String url(final List<Long> revIds, final String from, final String to)
	{
		final String built = GuidePriceClient.tablesUrl(revIds);
		final int start = built.indexOf(from);
		final int end = built.indexOf(to, start + from.length());
		return built.substring(start, end + to.length());
	}

	private void assertIndexFails(final String body, final String fragment)
	{
		try
		{
			client.parseRevisionIndex(body);
			fail("expected parseRevisionIndex to fail on: " + body);
		}
		catch (final WikiPriceException expected)
		{
			assertTrue(expected.getMessage() + " should mention " + fragment,
				expected.getMessage().contains(fragment));
		}
	}

	private void assertTablesFail(final String body, final String fragment)
	{
		try
		{
			client.parseTables(body, NOW_MILLIS);
			fail("expected parseTables to fail on: " + body);
		}
		catch (final WikiPriceException expected)
		{
			assertTrue(expected.getMessage() + " should mention " + fragment,
				expected.getMessage().contains(fragment));
		}
	}

	private void assertTableFails(final String body, final String fragment)
	{
		try
		{
			client.parseGuideTable(body);
			fail("expected parseGuideTable to fail on: " + body);
		}
		catch (final WikiPriceException expected)
		{
			assertTrue(expected.getMessage() + " should mention " + fragment,
				expected.getMessage().contains(fragment));
		}
	}

	private void assertMappingFails(final String body, final String fragment)
	{
		try
		{
			client.parseMapping(body);
			fail("expected parseMapping to fail on: " + body);
		}
		catch (final WikiPriceException expected)
		{
			assertTrue(expected.getMessage() + " should mention " + fragment,
				expected.getMessage().contains(fragment));
		}
	}

	private void queue(final String body)
	{
		replies.add(new Reply(200, body, null, Mode.NORMAL));
	}

	private void queue(final int code, final String body)
	{
		replies.add(new Reply(code, body, null, Mode.NORMAL));
	}

	private void queue(final IOException failure)
	{
		replies.add(new Reply(0, null, failure, Mode.NORMAL));
	}

	/** A reply whose body raises an {@link Error} when it is read - an OOM on a 126 KB table, say (B006). */
	private void queueAnErrorInsideTheCallback()
	{
		replies.add(new Reply(200, null, null, Mode.EXPLODE));
	}

	/** A request that never answers: what {@code cancelInFlight()} exists for (B012). */
	private void queueNoAnswer()
	{
		replies.add(new Reply(0, null, null, Mode.PENDING));
	}

	/** A FRESH response per reply - a response body is a one-shot stream. A null body throws an Error when read. */
	private Response response(final int code, final String body)
	{
		final Request request = requests.isEmpty()
			? new Request.Builder().url(GuidePriceClient.MAPPING_URL).build()
			: requests.get(requests.size() - 1);

		final ResponseBody carried = body == null ? new ExplodingBody() : ResponseBody.create(JSON, body);
		return new Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message(code == 200 ? "OK" : "Error")
			.body(new CountingBody(carried, bodyCloses))
			.build();
	}

	private static WikiPriceException failureOf(final CompletableFuture<?> future)
	{
		assertTrue("the future must have completed exceptionally", future.isCompletedExceptionally());
		try
		{
			future.get(5, TimeUnit.SECONDS);
			fail("expected the future to fail");
			return null;
		}
		catch (final ExecutionException e)
		{
			assertTrue("every failure must be a WikiPriceException, was " + e.getCause(),
				e.getCause() instanceof WikiPriceException);
			return (WikiPriceException) e.getCause();
		}
		catch (final Exception e)
		{
			throw new AssertionError("the future should already be complete", e);
		}
	}

	/** One queued answer: either a status and a body, or a transport failure. */
	private static final class Reply
	{
		private final int code;
		private final String body;
		private final IOException failure;
		private final Mode mode;

		private Reply(final int code, final String body, final IOException failure, final Mode mode)
		{
			this.code = code;
			this.body = body;
			this.failure = failure;
			this.mode = mode;
		}
	}

	/** How the mocked call answers: the ordinary way, not at all, or by raising an {@link Error} in the callback. */
	private enum Mode
	{
		NORMAL,
		PENDING,
		EXPLODE
	}

	/**
	 * A {@link ResponseBody} whose bytes cannot be read without raising an {@link Error} - the shape of an
	 * {@code OutOfMemoryError} while a 126 KB table is parsed on a small heap. {@code close()} is quiet, so the
	 * try-with-resources unwinding cannot replace the failure under test.
	 */
	private static final class ExplodingBody extends ResponseBody
	{
		@Override
		public MediaType contentType()
		{
			return JSON;
		}

		@Override
		public long contentLength()
		{
			return -1L;
		}

		@Override
		public BufferedSource source()
		{
			throw new OutOfMemoryError("simulated heap exhaustion while reading the wiki body");
		}

		@Override
		public void close()
		{
			// Nothing to close, and nothing may be thrown from here.
		}
	}

	/**
	 * A {@link ResponseBody} that counts {@code close()}. There is no other hook: {@link Response} is final and
	 * cannot be mocked, and {@code ResponseBody.string()} closes only the source, so counting here is the only
	 * way to prove the production code closed the RESPONSE.
	 */
	private static final class CountingBody extends ResponseBody
	{
		private final ResponseBody delegate;
		private final AtomicInteger closes;

		private CountingBody(final ResponseBody delegate, final AtomicInteger closes)
		{
			this.delegate = delegate;
			this.closes = closes;
		}

		@Override
		public MediaType contentType()
		{
			return delegate.contentType();
		}

		@Override
		public long contentLength()
		{
			return delegate.contentLength();
		}

		@Override
		public BufferedSource source()
		{
			return delegate.source();
		}

		@Override
		public void close()
		{
			closes.incrementAndGet();
			delegate.close();
		}
	}
}
