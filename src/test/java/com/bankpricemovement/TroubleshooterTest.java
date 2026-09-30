package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.runelite.client.util.Filepath;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link Troubleshooter}: the twelve-row verdict table (one test per row, plus the order), the five checks against a
 * faked OkHttp and a real temporary folder - the version check reading a manifest built here in the Hub's shape - and
 * the whole run's hand-back. No real socket is opened: the seam is {@code OkHttpClient.newCall}, answered by a mocked
 * {@link Call} that invokes the callback inline - the idiom {@code GuidePriceClientTest} uses.
 */
public class TroubleshooterTest
{
	/** 2026-09-30T14:05:09Z. */
	private static final long T0 = 1_790_777_109_000L;
	/** The Hub version the tests name; the manifest address follows from it. */
	private static final String HUB = "1.13.0";
	private static final String MANIFEST_URL = "https://repo.runelite.net/plugins/manifest/1.13.0_full.js";

	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private final AtomicLong clock = new AtomicLong(T0);
	private final Diagnostics diagnostics = new Diagnostics(clock::get, ZoneId.of("UTC"));
	private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
	/** How a URL is answered; anything not listed is a plain 200. */
	private final Map<String, Reply> replies = new HashMap<>();
	private final AtomicInteger cancelled = new AtomicInteger();

	private OkHttpClient http;
	private ScheduledExecutorService executor;
	private Supplier<Filepath> directory;
	/** What RuneLite names as the Hub version; a test sets it to null to have it say nothing. */
	private String hubVersion = HUB;

	private enum Mode
	{
		ANSWER, HANG, FAIL
	}

	private static final class Reply
	{
		final Mode mode;
		final int code;
		final IOException failure;
		final byte[] body;

		Reply(final Mode mode, final int code, final IOException failure)
		{
			this(mode, code, failure, new byte[0]);
		}

		Reply(final Mode mode, final int code, final IOException failure, final byte[] body)
		{
			this.mode = mode;
			this.code = code;
			this.failure = failure;
			this.body = body;
		}
	}

	/** An answer of 200 with this body. */
	private static Reply body(final byte[] body)
	{
		return new Reply(Mode.ANSWER, 200, null, body);
	}

	/**
	 * A manifest in the Hub's shape: a four-byte big-endian length, that many bytes of signature (junk that holds
	 * braces and quotes, which a parser that did not skip it would choke on), then the JSON as UTF-8.
	 */
	private static byte[] manifest(final int signatureLength, final String json)
	{
		final byte[] text = json.getBytes(StandardCharsets.UTF_8);
		final byte[] out = new byte[4 + signatureLength + text.length];
		out[0] = (byte) (signatureLength >>> 24);
		out[1] = (byte) (signatureLength >>> 16);
		out[2] = (byte) (signatureLength >>> 8);
		out[3] = (byte) signatureLength;
		for (int i = 0; i < signatureLength; i++)
		{
			out[4 + i] = (byte) (i % 3 == 0 ? '{' : i % 3 == 1 ? '"' : 0xFF);
		}
		System.arraycopy(text, 0, out, 4 + signatureLength, text.length);
		return out;
	}

	/** The Hub's JSON with our plugin among others: a non-ASCII name before it, so a byte offset that is wrong shows. */
	private static String display(final String ourEntry)
	{
		return "{\"display\":[{\"internalName\":\"other\",\"displayName\":\"Café ☃\",\"version\":\"9.9.9\"},"
			+ ourEntry + ",{\"internalName\":\"another\",\"version\":\"1\",\"unavailableReason\":\"x\"}],"
			+ "\"jars\":[{\"internalName\":\"bank-portfolio-tracker\",\"jarHash\":\"abc\"}]}";
	}

	private static String entry(final String version)
	{
		return "{\"internalName\":\"bank-portfolio-tracker\",\"displayName\":\"2h Bank Portfolio Tracker\",\"version\":\""
			+ version + "\",\"author\":\"2hBuilds\"}";
	}

	@Before
	public void setUp() throws Exception
	{
		// Everything healthy unless a test says otherwise: the Hub lists this very version.
		replies.put(MANIFEST_URL, body(manifest(512, display(entry(Version.CURRENT)))));
		http = mock(OkHttpClient.class);
		executor = mock(ScheduledExecutorService.class);
		directory = () -> TestFilepaths.rooted(folder.getRoot());
		when(http.newCall(any(Request.class))).thenAnswer(invocation ->
		{
			final Request request = invocation.getArgument(0);
			requests.add(request);
			final Call call = mock(Call.class);
			doAnswer(ignored ->
			{
				cancelled.incrementAndGet();
				return null;
			}).when(call).cancel();
			doAnswer(enqueued ->
			{
				final Callback callback = enqueued.getArgument(0);
				final Reply reply = replies.get(request.url().toString());
				if (reply == null)
				{
					callback.onResponse(call, response(request, 200, new byte[0]));
				}
				else if (reply.mode == Mode.ANSWER)
				{
					callback.onResponse(call, response(request, reply.code, reply.body));
				}
				else if (reply.mode == Mode.FAIL)
				{
					callback.onFailure(call, reply.failure);
				}
				return null;
			}).when(call).enqueue(any(Callback.class));
			return call;
		});
		when(executor.submit(any(Runnable.class))).thenAnswer(invocation ->
		{
			((Runnable) invocation.getArgument(0)).run();
			return null;
		});
	}

	private static Response response(final Request request, final int code, final byte[] body)
	{
		return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
			.header("Date", "Wed, 30 Sep 2026 14:05:04 GMT")
			.body(ResponseBody.create(MediaType.parse("application/json"), body)).build();
	}

	private Troubleshooter troubleshooter()
	{
		return new Troubleshooter(http, executor, directory, diagnostics, Runnable::run, new Gson(), hubVersion);
	}

	private Troubleshooter troubleshooter(final long checkMs, final long totalMs)
	{
		return new Troubleshooter(http, executor, directory, diagnostics, Runnable::run, new Gson(), hubVersion, checkMs,
			totalMs);
	}

	private List<CheckResult> checks(final Troubleshooter t) throws Exception
	{
		return t.runChecks().get(10, TimeUnit.SECONDS);
	}

	private static CheckResult named(final List<CheckResult> results, final String name)
	{
		for (final CheckResult r : results)
		{
			if (r.name.equals(name))
			{
				return r;
			}
		}
		throw new AssertionError("no check named " + name + " in " + names(results));
	}

	private static List<String> names(final List<CheckResult> results)
	{
		final List<String> names = new ArrayList<>();
		for (final CheckResult r : results)
		{
			names.add(r.name);
		}
		return names;
	}

	// ---- the five checks

	@Test
	public void theFiveChecksRunInOrderAndAllPassWhenEveryoneAnswers() throws Exception
	{
		final List<CheckResult> results = checks(troubleshooter());

		assertEquals(Arrays.asList("plugin version", "wiki mapping", "wiki price index", "live prices", "data folder"),
			names(results));
		for (final CheckResult r : results)
		{
			assertTrue(r.name + " " + r.detail, r.ok);
		}
		assertEquals(200, named(results, "plugin version").httpStatus);
		assertEquals("up to date (" + Version.CURRENT + ")", named(results, "plugin version").detail);
		assertNull(named(results, "plugin version").update);
		assertEquals(200, named(results, "wiki mapping").httpStatus);
		assertEquals("reachable", named(results, "wiki mapping").detail);
		assertEquals("the folder check is not a request", 0, named(results, "data folder").httpStatus);
		assertEquals("wrote one byte and removed it", named(results, "data folder").detail);
	}

	@Test
	public void theRequestsAreTheRealUrlsWithTheRightMethodsAndTheClientsOwnUserAgent() throws Exception
	{
		checks(troubleshooter());

		assertEquals(4, requests.size());
		final Request version = requests.get(0);
		assertEquals("GET", version.method());
		assertEquals(MANIFEST_URL, version.url().toString());
		assertEquals(Troubleshooter.hubManifestUrl(HUB), version.url().toString());
		assertEquals("https://repo.runelite.net/plugins/manifest/1.12.39_full.js", Troubleshooter.hubManifestUrl("1.12.39"));
		final Request mapping = requests.get(1);
		assertEquals("HEAD", mapping.method());
		assertEquals(GuidePriceClient.MAPPING_URL, mapping.url().toString());
		final Request index = requests.get(2);
		assertEquals("the smallest GET the index client can make", "GET", index.method());
		assertEquals(GuidePriceClient.revisionIndexUrl(1), index.url().toString());
		assertTrue(index.url().toString().contains("rvlimit=1&"));
		assertFalse("the real fetch asks for 250", index.url().toString().contains("rvlimit=250"));
		assertEquals(GuidePriceClient.revisionIndexUrl(), GuidePriceClient.revisionIndexUrl(GuidePriceClient.INDEX_LIMIT));
		final Request live = requests.get(3);
		assertEquals("HEAD", live.method());
		assertEquals(TradedPriceClient.latestUrl(), live.url().toString());
		for (final Request request : requests)
		{
			assertEquals(GuidePriceClient.USER_AGENT, request.header("User-Agent"));
			assertFalse("nothing in a check's url comes from the player", request.url().toString().contains("id="));
		}
	}

	@Test
	public void aHubVersionRuneLiteDidNotNameMeansNoRequestAndAFailedCheckThatSaysSo() throws Exception
	{
		hubVersion = null;

		final List<CheckResult> results = checks(troubleshooter());

		assertEquals("only the three wiki checks went out", 3, requests.size());
		for (final Request request : requests)
		{
			assertFalse(request.url().toString().contains("manifest"));
		}
		final CheckResult version = named(results, "plugin version");
		assertFalse(version.ok);
		assertEquals(0, version.httpStatus);
		assertEquals("RuneLite did not name its Hub version", version.detail);
		assertNull(version.update);
		assertEquals("the others are untouched", 5, results.size());
		assertTrue(named(results, "wiki mapping").ok);

		hubVersion = "";
		requests.clear();
		assertEquals("an empty name is no name", "RuneLite did not name its Hub version",
			named(checks(troubleshooter()), "plugin version").detail);
		assertEquals(3, requests.size());
	}

	// ---- the version check: reading the manifest

	@Test
	public void theManifestIsReadPastItsSignatureAndAnEntryNewerThanOursIsAnUpdate() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(512, display(entry("999.0.0")))));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertTrue(version.detail, version.ok);
		assertEquals(200, version.httpStatus);
		assertEquals("999.0.0", version.update);
		assertEquals("the Hub has 999.0.0, this client runs " + Version.CURRENT, version.detail);
	}

	@Test
	public void theSameVersionIsUpToDateAndNamesNoUpdate() throws Exception
	{
		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertTrue(version.ok);
		assertNull(version.update);
		assertEquals("up to date (" + Version.CURRENT + ")", version.detail);
	}

	@Test
	public void aBuildNewerThanTheHubsPassesAndNamesBothVersions() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(512, display(entry("0.0.1")))));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertTrue(version.ok);
		assertNull("ours is the newer: nothing to update to", version.update);
		assertEquals("this build (" + Version.CURRENT + ") is newer than the Hub's (0.0.1)", version.detail);
	}

	@Test
	public void theDecisionTakesAnyOursAndTheParsedEntry()
	{
		final Troubleshooter.Entry newer = entryOf("1.0.8");
		final CheckResult update = Troubleshooter.versionResult("1.0.7", HUB, newer, 200, 12L);
		assertTrue(update.ok);
		assertEquals("1.0.8", update.update);
		assertEquals("the Hub has 1.0.8, this client runs 1.0.7", update.detail);
		assertEquals(200, update.httpStatus);
		assertEquals(12L, update.millis);
		assertEquals("plugin version", update.name);

		final CheckResult same = Troubleshooter.versionResult("1.0.8", HUB, newer, 200, 1L);
		assertTrue(same.ok);
		assertNull(same.update);
		assertEquals("up to date (1.0.8)", same.detail);

		final CheckResult ahead = Troubleshooter.versionResult("1.0.8", HUB, entryOf("1.0.6"), 200, 1L);
		assertTrue(ahead.ok);
		assertNull(ahead.update);
		assertEquals("this build (1.0.8) is newer than the Hub's (1.0.6)", ahead.detail);

		final CheckResult tenth = Troubleshooter.versionResult("1.0.9", HUB, entryOf("1.0.10"), 200, 1L);
		assertEquals("1.0.10", tenth.update);
	}

	@Test
	public void aVersionThatIsNotDottedIntegersCannotBeComparedSoItPassesWithNothingToSay()
	{
		final CheckResult result = Troubleshooter.versionResult("1.0.8", HUB, entryOf("1.0.9-beta"), 200, 1L);

		assertTrue(result.ok);
		assertNull(result.update);
		assertEquals("the Hub has 1.0.9-beta, this client runs 1.0.8", result.detail);
	}

	@Test
	public void anEntryWithAnUnavailableReasonIsAFailedCheckWithTheScrubbedReason() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(512, display("{\"internalName\":\"bank-portfolio-tracker\","
			+ "\"version\":\"999.0.0\",\"unavailableReason\":\"C:\\\\Users\\\\John Smith\\\\.runelite\\\\cache\\\\x.bin: locked\"}"))));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertFalse(version.ok);
		assertNull("unavailable, so nothing to restart for", version.update);
		assertEquals("listed without a build for this client: <path>: locked", version.detail);
	}

	@Test
	public void noEntryForThePluginIsAFailedCheckNamingTheHubVersion() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(512, "{\"display\":[{\"internalName\":\"other\",\"version\":\"1.0.0\"}],"
			+ "\"jars\":[]}")));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertFalse(version.ok);
		assertNull(version.update);
		assertEquals("not listed for Hub version 1.13.0", version.detail);
	}

	@Test
	public void anEntryWithNoVersionIsAFailedCheck() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(0, display("{\"internalName\":\"bank-portfolio-tracker\"}"))));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertFalse(version.ok);
		assertNull(version.update);
		assertEquals("the Hub lists no version for it", version.detail);
	}

	@Test
	public void aSignatureOfAnyLengthIsSkippedIncludingNone() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(0, display(entry("999.0.0")))));
		assertEquals("999.0.0", named(checks(troubleshooter()), "plugin version").update);

		replies.put(MANIFEST_URL, body(manifest(1, display(entry("999.0.1")))));
		assertEquals("999.0.1", named(checks(troubleshooter()), "plugin version").update);
	}

	@Test
	public void aShortOrMalformedBodyIsAFailedCheckWithTheExceptionInWords() throws Exception
	{
		replies.put(MANIFEST_URL, body(new byte[0]));
		CheckResult version = named(checks(troubleshooter()), "plugin version");
		assertFalse(version.ok);
		assertEquals(200, version.httpStatus);
		assertEquals("IOException: the manifest is shorter than its length prefix", version.detail);

		replies.put(MANIFEST_URL, body(new byte[]{0, 0, 2, 0, 1, 2, 3}));
		version = named(checks(troubleshooter()), "plugin version");
		assertFalse(version.ok);
		assertEquals("IOException: the manifest is shorter than its signature says", version.detail);

		replies.put(MANIFEST_URL, body(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1}));
		version = named(checks(troubleshooter()), "plugin version");
		assertFalse("a length with its top bit set is not a length", version.ok);
		assertEquals("IOException: the manifest is shorter than its signature says", version.detail);

		replies.put(MANIFEST_URL, body(manifest(4, "{not json")));
		version = named(checks(troubleshooter()), "plugin version");
		assertFalse(version.ok);
		assertNull(version.update);
		assertTrue(version.detail, version.detail.contains("Exception"));

		replies.put(MANIFEST_URL, body(manifest(4, "{\"jars\":[]}")));
		version = named(checks(troubleshooter()), "plugin version");
		assertFalse(version.ok);
		assertEquals("IOException: the manifest has no display list", version.detail);

		replies.put(MANIFEST_URL, body(manifest(4, "")));
		version = named(checks(troubleshooter()), "plugin version");
		assertFalse("an empty JSON part", version.ok);
		assertEquals("IOException: the manifest has no display list", version.detail);
	}

	@Test
	public void aNon2xxAnswerToTheManifestIsAFailedCheckWithItsStatus() throws Exception
	{
		replies.put(MANIFEST_URL, new Reply(Mode.ANSWER, 404, null));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertFalse(version.ok);
		assertEquals(404, version.httpStatus);
		assertEquals("the server answered HTTP 404", version.detail);
		assertNull(version.update);
	}

	@Test
	public void aTransportFailureOnTheManifestIsAFailedCheckWithNoStatus() throws Exception
	{
		replies.put(MANIFEST_URL, new Reply(Mode.FAIL, 0, new IOException("connection reset")));

		final CheckResult version = named(checks(troubleshooter()), "plugin version");

		assertFalse(version.ok);
		assertEquals(0, version.httpStatus);
		assertEquals("IOException: connection reset", version.detail);
	}

	@Test
	public void aManifestThatNeverAnswersTimesOutLikeAnyOtherCheck() throws Exception
	{
		replies.put(MANIFEST_URL, new Reply(Mode.HANG, 0, null));

		final CheckResult version = named(checks(troubleshooter(200L, 2_000L)), "plugin version");

		assertFalse(version.ok);
		assertEquals("no answer within 200 ms", version.detail);
		assertNull(version.update);
		assertTrue("the hung call was cancelled", cancelled.get() >= 1);
	}

	@Test
	public void theHubsDateHeaderIsNotTheWikisClockAndDoesNotReachTheFetchLog() throws Exception
	{
		replies.put(GuidePriceClient.MAPPING_URL, new Reply(Mode.FAIL, 0, new IOException("down")));
		replies.put(GuidePriceClient.revisionIndexUrl(1), new Reply(Mode.FAIL, 0, new IOException("down")));
		replies.put(TradedPriceClient.latestUrl(), new Reply(Mode.FAIL, 0, new IOException("down")));

		final List<CheckResult> results = checks(troubleshooter());

		assertTrue("the manifest answered with a Date", named(results, "plugin version").ok);
		assertNull("and no clock skew was measured from it", diagnostics.fetchLog().clockSkewSeconds());
	}

	private static Troubleshooter.Entry entryOf(final String version)
	{
		final Troubleshooter.Entry entry = new Troubleshooter.Entry();
		entry.internalName = "bank-portfolio-tracker";
		entry.version = version;
		return entry;
	}

	// ---- the version comparison

	@Test
	public void versionsCompareAsDottedIntegersWithMissingPartsZero()
	{
		assertTrue(Troubleshooter.compareVersions("1.0.10", "1.0.9") > 0);
		assertTrue(Troubleshooter.compareVersions("1.0.9", "1.0.10") < 0);
		assertTrue(Troubleshooter.compareVersions("1.1", "1.0.9") > 0);
		assertTrue(Troubleshooter.compareVersions("1.0.9", "1.1") < 0);
		assertEquals(Integer.valueOf(0), Troubleshooter.compareVersions("1.0.8", "1.0.8"));
		assertEquals(Integer.valueOf(0), Troubleshooter.compareVersions("1.0", "1.0.0"));
		assertEquals(Integer.valueOf(0), Troubleshooter.compareVersions("1.0.0", "1"));
		assertTrue(Troubleshooter.compareVersions("2", "1.9.9") > 0);
		assertTrue(Troubleshooter.compareVersions("1.0.0.1", "1.0") > 0);
	}

	@Test
	public void aPartThatIsNotAnIntegerMakesTwoVersionsIncomparable()
	{
		assertNull(Troubleshooter.compareVersions("1.0.8-SNAPSHOT", "1.0.8"));
		assertNull(Troubleshooter.compareVersions("1.0.8", "1.x.8"));
		assertNull(Troubleshooter.compareVersions("1..8", "1.0.8"));
		assertNull(Troubleshooter.compareVersions("", "1.0.8"));
		assertNull(Troubleshooter.compareVersions("1.0.-1", "1.0.8"));
		assertNull("a bad part anywhere, even after a difference", Troubleshooter.compareVersions("2", "1.x"));
		assertNull("a part too long for a number", Troubleshooter.compareVersions("1.0.99999999999999999999", "1.0.8"));
	}

	@Test
	public void aNon2xxAnswerIsAFailedCheckWithItsStatus() throws Exception
	{
		replies.put(GuidePriceClient.MAPPING_URL, new Reply(Mode.ANSWER, 403, null));

		final CheckResult mapping = named(checks(troubleshooter()), "wiki mapping");

		assertFalse(mapping.ok);
		assertEquals(403, mapping.httpStatus);
		assertEquals("the server answered HTTP 403", mapping.detail);
	}

	@Test
	public void aTransportFailureIsAFailedCheckWithNoStatusAndTheExceptionInWords() throws Exception
	{
		replies.put(TradedPriceClient.latestUrl(), new Reply(Mode.FAIL, 0, new IOException("connection refused")));

		final CheckResult live = named(checks(troubleshooter()), "live prices");

		assertFalse(live.ok);
		assertEquals(0, live.httpStatus);
		assertEquals("IOException: connection refused", live.detail);
	}

	@Test
	public void aFailureMessageThatIsAPathIsScrubbed() throws Exception
	{
		replies.put(TradedPriceClient.latestUrl(), new Reply(Mode.FAIL, 0,
			new IOException("C:\\Users\\John Smith\\.runelite\\cache\\x.bin: locked")));

		final CheckResult live = named(checks(troubleshooter()), "live prices");

		assertEquals("IOException: <path>: locked", live.detail);
	}

	@Test
	public void theWikisDateHeaderReachesTheFetchLogSoTheReportCanSayHowFarTheClockIs() throws Exception
	{
		assertNull(diagnostics.fetchLog().clockSkewSeconds());

		checks(troubleshooter());

		assertEquals(Long.valueOf(5L), diagnostics.fetchLog().clockSkewSeconds());
	}

	@Test
	public void aRequestThatNeverAnswersTimesOutOnItsOwnAndIsCancelled() throws Exception
	{
		replies.put(GuidePriceClient.MAPPING_URL, new Reply(Mode.HANG, 0, null));

		final List<CheckResult> results = checks(troubleshooter(200L, 2_000L));

		final CheckResult mapping = named(results, "wiki mapping");
		assertFalse(mapping.ok);
		assertEquals(0, mapping.httpStatus);
		assertEquals("no answer within 200 ms", mapping.detail);
		assertEquals(200L, mapping.millis);
		assertTrue("the others are untouched", named(results, "live prices").ok);
		assertTrue("the hung call was cancelled to free its connection", cancelled.get() >= 1);
	}

	@Test
	public void theWholeRunAnswersInsideItsCeilingWhateverTheChecksDo() throws Exception
	{
		replies.put(GuidePriceClient.MAPPING_URL, new Reply(Mode.HANG, 0, null));
		replies.put(TradedPriceClient.latestUrl(), new Reply(Mode.HANG, 0, null));

		final long started = System.nanoTime();
		final List<CheckResult> results = checks(troubleshooter(60_000L, 150L));
		final long tookMs = (System.nanoTime() - started) / 1_000_000L;

		assertTrue("inside the ceiling, not the per-check timeout: " + tookMs + " ms", tookMs < 5_000L);
		assertEquals("every check still has an answer", 5, results.size());
		assertEquals("no answer within 150 ms", named(results, "wiki mapping").detail);
		assertEquals("no answer within 150 ms", named(results, "live prices").detail);
		assertTrue(named(results, "wiki price index").ok);
	}

	@Test
	public void theDefaultTimeoutsAreEightSecondsAndTen()
	{
		assertEquals(8_000L, Troubleshooter.CHECK_TIMEOUT_MS);
		assertEquals(10_000L, Troubleshooter.TOTAL_TIMEOUT_MS);
	}

	// ---- the data-folder check

	@Test
	public void theFolderCheckLeavesNoProbeFileBehind() throws Exception
	{
		checks(troubleshooter());

		assertFalse(new File(folder.getRoot(), Troubleshooter.PROBE_FILE).exists());
		assertEquals("troubleshoot-probe.tmp", Troubleshooter.PROBE_FILE);
	}

	@Test
	public void theFolderCheckCreatesTheFolderOfAPluginThatHasNeverSavedAnything() throws Exception
	{
		final File missing = new File(folder.getRoot(), "not-yet");
		directory = () -> TestFilepaths.rooted(folder.getRoot()).join("not-yet");

		final CheckResult result = named(checks(troubleshooter()), "data folder");

		assertTrue(result.detail, result.ok);
		assertTrue("the folder exists now, as the first real save would have made it", missing.isDirectory());
		assertFalse(new File(missing, Troubleshooter.PROBE_FILE).exists());
	}

	@Test
	public void noDataFolderAtAllIsAFailedCheck() throws Exception
	{
		directory = () -> null;

		final CheckResult result = named(checks(troubleshooter()), "data folder");

		assertFalse(result.ok);
		assertEquals("the plugin has no data folder", result.detail);
	}

	@Test
	public void aFolderThatCannotBeWrittenFailsWithTheExceptionAndNoPath() throws Exception
	{
		final File blocker = folder.newFile("a-file-where-the-folder-should-be");
		directory = () -> TestFilepaths.rooted(blocker);

		final CheckResult result = named(checks(troubleshooter()), "data folder");

		assertFalse(result.ok);
		assertEquals(0, result.httpStatus);
		assertFalse("no path in it: " + result.detail, result.detail.contains(folder.getRoot().getName()));
		assertFalse(result.detail, result.detail.contains("\\") || result.detail.contains("a-file-where"));
		assertTrue(result.detail, result.detail.contains("Exception"));
	}

	@Test
	public void aDirectorySupplierThatThrowsIsAFailedCheckNotAnException() throws Exception
	{
		directory = () ->
		{
			throw new IllegalStateException("no internal name");
		};

		final CheckResult result = named(checks(troubleshooter()), "data folder");

		assertFalse(result.ok);
		assertEquals("IllegalStateException: no internal name", result.detail);
	}

	@Test
	public void anExecutorThatRefusesTheFolderCheckIsAFailedCheck() throws Exception
	{
		when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("shutting down"));

		final CheckResult result = named(checks(troubleshooter()), "data folder");

		assertFalse(result.ok);
		assertTrue(result.detail, result.detail.startsWith("could not be queued: RejectedExecutionException"));
	}

	@Test
	public void theFolderCheckRunsOnTheExecutorNotOnTheCallingThread() throws Exception
	{
		checks(troubleshooter());

		verify(executor).submit(any(Runnable.class));
	}

	// ---- the verdict table: one test per row, then the order

	private static final String CARD_LIST = BankPriceMovementPanel.CARD_LIST;

	/** Everything healthy: logged in, the account named, a bank seen and read, nothing wrong. */
	private static Diagnostics.Facts.Builder healthy()
	{
		return Diagnostics.Facts.builder().loggedIn(true).accountKnown(true).bankEvents(5, 1, 2, 0).card(CARD_LIST)
			.lastErrorWhat(null).runningForSeconds(0L);
	}

	private static List<CheckResult> allOk()
	{
		return Arrays.asList(ok("plugin version"), ok("wiki mapping"), ok("wiki price index"), ok("live prices"),
			ok("data folder"));
	}

	private static CheckResult ok(final String name)
	{
		return new CheckResult(name, true, 0, 5L, "fine");
	}

	private static CheckResult bad(final String name)
	{
		return new CheckResult(name, false, 0, 5L, "broken");
	}

	private static List<CheckResult> allNetworkDown()
	{
		return Arrays.asList(ok("plugin version"), bad("wiki mapping"), bad("wiki price index"), bad("live prices"),
			ok("data folder"));
	}

	/** Every check fine except the version check, which found the Hub at {@code hubVersion}. */
	private static List<CheckResult> hubHas(final String hubVersion)
	{
		return Arrays.asList(new CheckResult("plugin version", true, 200, 5L, "the Hub has " + hubVersion, hubVersion),
			ok("wiki mapping"), ok("wiki price index"), ok("live prices"), ok("data folder"));
	}

	private static String verdict(final Diagnostics.Facts.Builder b, final List<CheckResult> checks)
	{
		return Troubleshooter.verdict(b.build(), checks);
	}

	@Test
	public void row0TheGameThreadNeverAnsweredWinsOverEveryOtherRule()
	{
		// The player facts default to "not logged in" when the game thread never fills them; without this rule the
		// verdict would blame the player for a client that did not answer.
		assertEquals(Troubleshooter.VERDICT_NO_CLIENT, verdict(healthy().clientAnswered(false), allOk()));
		assertEquals(Troubleshooter.VERDICT_NO_CLIENT, verdict(healthy().clientAnswered(false).loggedIn(false), allNetworkDown()));
		assertEquals("even over a newer version on the Hub", Troubleshooter.VERDICT_NO_CLIENT,
			verdict(healthy().clientAnswered(false), hubHas("9.9.9")));
	}

	@Test
	public void row1ANewerVersionOnTheHubSaysBothNumbersAndToRestart()
	{
		assertEquals("You have " + Version.CURRENT + ", the Hub has 1.0.99. Restart RuneLite to update.",
			verdict(healthy(), hubHas("1.0.99")));
		assertEquals(String.format(Troubleshooter.VERDICT_OUT_OF_DATE, Version.CURRENT, "1.0.99"),
			verdict(healthy(), hubHas("1.0.99")));
	}

	@Test
	public void row1IsBeatenOnlyByRule0AndBeatsEveryRuleBelowIt()
	{
		final String outOfDate = String.format(Troubleshooter.VERDICT_OUT_OF_DATE, Version.CURRENT, "9.9.9");
		assertEquals(outOfDate, verdict(healthy().loggedIn(false), hubHas("9.9.9")));
		assertEquals(outOfDate, verdict(healthy().accountKnown(false), hubHas("9.9.9")));
		assertEquals(outOfDate, verdict(healthy().bankEvents(0, 0, 0, 0), hubHas("9.9.9")));
		assertEquals(outOfDate, verdict(healthy().lastErrorWhat(Diagnostics.BANK_READ_FAILED).runningForSeconds(99L),
			hubHas("9.9.9")));
		assertEquals(outOfDate, verdict(healthy().card(BankPriceMovementPanel.CARD_NO_BANK), hubHas("9.9.9")));
		assertEquals(Troubleshooter.VERDICT_NO_CLIENT, verdict(healthy().clientAnswered(false), hubHas("9.9.9")));
	}

	@Test
	public void row1DoesNotFireForAnyOtherOutcomeOfTheVersionCheck()
	{
		final Troubleshooter.Entry entry = entryOf("1.0.8");
		final List<CheckResult> equal = Arrays.asList(Troubleshooter.versionResult("1.0.8", HUB, entry, 200, 5L));
		final List<CheckResult> oursNewer = Arrays.asList(Troubleshooter.versionResult("1.0.9", HUB, entry, 200, 5L));
		final List<CheckResult> incomparable = Arrays.asList(Troubleshooter.versionResult("1.0.8-x", HUB, entry, 200, 5L));
		final Troubleshooter.Entry gone = entryOf("9.9.9");
		gone.unavailableReason = "no build";
		final List<CheckResult> unavailable = Arrays.asList(Troubleshooter.versionResult("1.0.8", HUB, gone, 200, 5L));
		final List<CheckResult> notListed = Arrays.asList(Troubleshooter.versionResult("1.0.8", HUB, null, 200, 5L));
		for (final List<CheckResult> version : Arrays.asList(equal, oursNewer, incomparable, unavailable, notListed,
			Arrays.asList(bad("plugin version")), Collections.<CheckResult>emptyList()))
		{
			final List<CheckResult> checks = new ArrayList<>(version);
			checks.addAll(Arrays.asList(ok("wiki mapping"), ok("wiki price index"), ok("live prices"), ok("data folder")));
			assertEquals(version.toString(), Troubleshooter.VERDICT_FINE, verdict(healthy(), checks));
		}
		assertEquals("a failed check is not a newer version", Troubleshooter.VERDICT_NOT_LOGGED_IN,
			verdict(healthy().loggedIn(false), Arrays.asList(bad("plugin version"))));
	}

	@Test
	public void row2NotLoggedIn()
	{
		assertEquals("You are not logged in. Log in and open your bank once.",
			verdict(healthy().loggedIn(false), allOk()));
		assertEquals(Troubleshooter.VERDICT_NOT_LOGGED_IN, verdict(healthy().loggedIn(false), allOk()));
	}

	@Test
	public void row3TheAccountIsNotKnown()
	{
		assertEquals("RuneLite has not named your account yet. Log out and log in again.",
			verdict(healthy().accountKnown(false), allOk()));
	}

	@Test
	public void row3DroppedEventsAndNothingEverRead()
	{
		assertEquals(Troubleshooter.VERDICT_NO_ACCOUNT, verdict(healthy().bankEvents(3, 0, 0, 3), allOk()));
		assertEquals("dropped events are harmless once a read has happened", Troubleshooter.VERDICT_FINE,
			verdict(healthy().bankEvents(5, 0, 2, 1), allOk()));
	}

	@Test
	public void row4NoBankEventSeen()
	{
		assertEquals("The plugin has not seen your bank yet this session. Open your bank once, then close it.",
			verdict(healthy().bankEvents(0, 0, 0, 0), allOk()));
	}

	@Test
	public void row5TheBankReadFailed()
	{
		assertEquals("Reading your bank failed on one item. Open your bank again.",
			verdict(healthy().lastErrorWhat(Diagnostics.BANK_READ_FAILED), allOk()));
	}

	@Test
	public void row6BackgroundWorkStuckForMoreThanThirtySeconds()
	{
		assertEquals("The plugin's background work is stuck, probably a network call. Restart RuneLite.",
			verdict(healthy().runningForSeconds(31L), allOk()));
		assertEquals("thirty seconds is not more than thirty", Troubleshooter.VERDICT_FINE,
			verdict(healthy().runningForSeconds(30L), allOk()));
	}

	@Test
	public void row7PricingOrTheHistoryFailed()
	{
		final String expected = "Pricing your bank failed. Press Refresh, then open your bank again.";
		assertEquals(expected, verdict(healthy().lastErrorWhat(Diagnostics.COMPUTATION_FAILED), allOk()));
		assertEquals(expected, verdict(healthy().lastErrorWhat(Diagnostics.HISTORY_FAILED), allOk()));
		assertEquals(expected, verdict(healthy().lastErrorWhat(Diagnostics.HISTORY_CELLS_FAILED), allOk()));
		assertEquals("a failed background task is not one of the named errors", Troubleshooter.VERDICT_FINE,
			verdict(healthy().lastErrorWhat("background task failed"), allOk()));
	}

	@Test
	public void row8EveryWikiCheckFailed()
	{
		assertEquals("The price site cannot be reached from this computer. Check your connection or firewall, then press Refresh.",
			verdict(healthy(), allNetworkDown()));
	}

	@Test
	public void row8NeedsEveryWikiCheckToHaveFailedAndAtLeastOneToHaveRun()
	{
		assertEquals(Troubleshooter.VERDICT_FINE, verdict(healthy(), Arrays.asList(ok("plugin version"),
			bad("wiki mapping"), bad("wiki price index"), ok("live prices"), ok("data folder"))));
		assertEquals("no checks, no verdict about the network", Troubleshooter.VERDICT_FINE,
			verdict(healthy(), Collections.emptyList()));
		assertEquals("the folder alone does not count as the network", Troubleshooter.VERDICT_FINE,
			verdict(healthy(), Collections.singletonList(ok("data folder"))));
	}

	@Test
	public void row8TheHubIsNotThePriceSite()
	{
		assertEquals("the version check failing alone is not the price site down", Troubleshooter.VERDICT_FINE,
			verdict(healthy(), Arrays.asList(bad("plugin version"), ok("wiki mapping"), ok("wiki price index"),
				ok("live prices"), ok("data folder"))));
		assertEquals("the Hub alone failing is no network verdict", Troubleshooter.VERDICT_FINE,
			verdict(healthy(), Collections.singletonList(bad("plugin version"))));
		assertEquals("and the Hub answering does not hide the wiki being down", Troubleshooter.VERDICT_UNREACHABLE,
			verdict(healthy(), Arrays.asList(ok("plugin version"), bad("wiki mapping"), bad("wiki price index"),
				bad("live prices"))));
		assertEquals("nor does the Hub failing too", Troubleshooter.VERDICT_UNREACHABLE,
			verdict(healthy(), Arrays.asList(bad("plugin version"), bad("wiki mapping"), bad("wiki price index"),
				bad("live prices"))));
	}

	@Test
	public void row9TheDataFolderCheckFailed()
	{
		assertEquals("The plugin cannot write its data folder. Restart RuneLite.",
			verdict(healthy(), Arrays.asList(ok("plugin version"), ok("wiki mapping"), ok("wiki price index"),
				ok("live prices"), bad("data folder"))));
	}

	@Test
	public void row10ABankWasReadButTheCardSaysNoBank()
	{
		assertEquals("Something unexpected happened. Restart RuneLite.",
			verdict(healthy().card(BankPriceMovementPanel.CARD_NO_BANK), allOk()));
		assertEquals("no read, no mystery: rule 4 or 3 speaks first", Troubleshooter.VERDICT_NO_BANK_SEEN,
			verdict(healthy().bankEvents(0, 0, 0, 0).card(BankPriceMovementPanel.CARD_NO_BANK), allOk()));
	}

	@Test
	public void row11OtherwiseEverythingLooksFine()
	{
		assertEquals("Everything looks fine here. Open your bank once, then press Refresh.",
			verdict(healthy(), allOk()));
	}

	@Test
	public void theTwelveSentencesAreTheContractsVerbatim()
	{
		assertEquals(Arrays.asList(
			"The game client did not answer in time. Restart RuneLite.",
			"You have 1.0.7, the Hub has 1.0.8. Restart RuneLite to update.",
			"You are not logged in. Log in and open your bank once.",
			"RuneLite has not named your account yet. Log out and log in again.",
			"The plugin has not seen your bank yet this session. Open your bank once, then close it.",
			"Reading your bank failed on one item. Open your bank again.",
			"The plugin's background work is stuck, probably a network call. Restart RuneLite.",
			"Pricing your bank failed. Press Refresh, then open your bank again.",
			"The price site cannot be reached from this computer. Check your connection or firewall, then press Refresh.",
			"The plugin cannot write its data folder. Restart RuneLite.",
			"Something unexpected happened. Restart RuneLite.",
			"Everything looks fine here. Open your bank once, then press Refresh."),
			Arrays.asList(Troubleshooter.VERDICT_NO_CLIENT,
				String.format(Troubleshooter.VERDICT_OUT_OF_DATE, "1.0.7", "1.0.8"),
				Troubleshooter.VERDICT_NOT_LOGGED_IN, Troubleshooter.VERDICT_NO_ACCOUNT,
				Troubleshooter.VERDICT_NO_BANK_SEEN, Troubleshooter.VERDICT_READ_FAILED, Troubleshooter.VERDICT_STUCK,
				Troubleshooter.VERDICT_PRICING_FAILED, Troubleshooter.VERDICT_UNREACHABLE, Troubleshooter.VERDICT_FOLDER,
				Troubleshooter.VERDICT_SOMETHING_NEW, Troubleshooter.VERDICT_FINE));
		assertEquals("the fallback when the run itself breaks", "Troubleshoot could not finish.",
			Troubleshooter.VERDICT_UNFINISHED);
	}

	/** The first matching row wins: each pair below matches two rules and answers the earlier one. */
	@Test
	public void theFirstMatchingRowWinsWhenSeveralMatch()
	{
		// rules 0 and 1
		assertEquals(Troubleshooter.VERDICT_NO_CLIENT, verdict(healthy().clientAnswered(false), hubHas("9.9.9")));
		// rules 1 and 2: out of date AND not logged in -> 1
		assertEquals(String.format(Troubleshooter.VERDICT_OUT_OF_DATE, Version.CURRENT, "9.9.9"),
			verdict(healthy().loggedIn(false), hubHas("9.9.9")));
		// rules 4 and 8: nothing seen AND the network down -> 4
		assertEquals(Troubleshooter.VERDICT_NO_BANK_SEEN, verdict(healthy().bankEvents(0, 0, 0, 0), allNetworkDown()));
		// rules 2 and 3
		assertEquals(Troubleshooter.VERDICT_NOT_LOGGED_IN, verdict(healthy().loggedIn(false).accountKnown(false), allOk()));
		// rules 3 and 4
		assertEquals(Troubleshooter.VERDICT_NO_ACCOUNT, verdict(healthy().accountKnown(false).bankEvents(0, 0, 0, 0), allOk()));
		// rules 4 and 5
		assertEquals(Troubleshooter.VERDICT_NO_BANK_SEEN,
			verdict(healthy().bankEvents(0, 0, 0, 0).lastErrorWhat(Diagnostics.BANK_READ_FAILED), allOk()));
		// rules 5 and 6
		assertEquals(Troubleshooter.VERDICT_READ_FAILED,
			verdict(healthy().lastErrorWhat(Diagnostics.BANK_READ_FAILED).runningForSeconds(99L), allOk()));
		// rules 6 and 7
		assertEquals(Troubleshooter.VERDICT_STUCK,
			verdict(healthy().lastErrorWhat(Diagnostics.COMPUTATION_FAILED).runningForSeconds(99L), allOk()));
		// rules 7 and 8
		assertEquals(Troubleshooter.VERDICT_PRICING_FAILED,
			verdict(healthy().lastErrorWhat(Diagnostics.HISTORY_FAILED), allNetworkDown()));
		// rules 8 and 9
		assertEquals(Troubleshooter.VERDICT_UNREACHABLE, verdict(healthy(), Arrays.asList(ok("plugin version"),
			bad("wiki mapping"), bad("wiki price index"), bad("live prices"), bad("data folder"))));
		// rules 9 and 10
		assertEquals(Troubleshooter.VERDICT_FOLDER, verdict(healthy().card(BankPriceMovementPanel.CARD_NO_BANK),
			Arrays.asList(ok("plugin version"), ok("wiki mapping"), ok("wiki price index"), ok("live prices"),
				bad("data folder"))));
		// rules 10 and 11
		assertEquals(Troubleshooter.VERDICT_SOMETHING_NEW,
			verdict(healthy().card(BankPriceMovementPanel.CARD_NO_BANK), allOk()));
	}

	// ---- the whole run

	@Test
	public void aRunHandsTheVerdictAndTheReportToTheEdtHookWithTheChecksInIt() throws Exception
	{
		final Diagnostics.Facts.Builder facts = healthy();
		final CompletableFuture<Void> player = CompletableFuture.completedFuture(null);
		final CompletableFuture<TroubleshootDialog.Contents> got = new CompletableFuture<>();
		final List<String> onEdt = new ArrayList<>();
		final Troubleshooter t = new Troubleshooter(http, executor, directory, diagnostics, task ->
		{
			onEdt.add("hook");
			task.run();
		}, new Gson(), HUB);

		t.run(facts, player, got::complete);

		final TroubleshootDialog.Contents contents = got.get(5, TimeUnit.SECONDS);
		assertEquals("the hand-back went through the EDT hook, once", Arrays.asList("hook"), onEdt);
		assertEquals(Troubleshooter.VERDICT_FINE, contents.verdict);
		assertTrue(contents.report, contents.report.startsWith("2h Bank Portfolio Tracker " + Version.CURRENT
			+ " - diagnostics\nVerdict: " + Troubleshooter.VERDICT_FINE + "\n"));
		assertTrue(contents.report, contents.report.contains("\nChecks\nplugin version: ok, HTTP 200,"));
		assertTrue(contents.report, contents.report.contains("wiki mapping: ok, HTTP 200,"));
		assertTrue(contents.report, contents.report.contains("data folder: ok, "));
		assertTrue("the run leaves one line in the ring: " + contents.report,
			contents.report.contains(" troubleshoot: " + Troubleshooter.VERDICT_FINE + "\n"));
	}

	@Test
	public void aRunPutsTheLastErrorAndTheWatchdogIntoTheVerdict() throws Exception
	{
		diagnostics.error(Diagnostics.BANK_READ_FAILED, new IllegalStateException("planted"));
		final CompletableFuture<TroubleshootDialog.Contents> got = new CompletableFuture<>();

		troubleshooter().run(healthy(), CompletableFuture.completedFuture(null), got::complete);

		assertEquals(Troubleshooter.VERDICT_READ_FAILED, got.get(5, TimeUnit.SECONDS).verdict);

		final Diagnostics other = new Diagnostics(clock::get, ZoneId.of("UTC"));
		other.watchdog().started("a network call", false);
		clock.set(T0 + 45_000L);
		final CompletableFuture<TroubleshootDialog.Contents> stuck = new CompletableFuture<>();
		new Troubleshooter(http, executor, directory, other, Runnable::run, new Gson(), HUB)
			.run(healthy(), CompletableFuture.completedFuture(null), stuck::complete);

		assertEquals(Troubleshooter.VERDICT_STUCK, stuck.get(5, TimeUnit.SECONDS).verdict);
	}

	@Test
	public void aRunWaitsForThePlayerFactsButNotForEver() throws Exception
	{
		final CompletableFuture<Void> never = new CompletableFuture<>();
		final CompletableFuture<TroubleshootDialog.Contents> got = new CompletableFuture<>();

		troubleshooter(60_000L, 200L).run(healthy(), never, got::complete);

		final TroubleshootDialog.Contents contents = got.get(5, TimeUnit.SECONDS);
		assertNotNull("the window is never left on Checking...", contents);
		assertTrue(contents.report, contents.report.contains("\nclient thread: no answer within 200 ms\n"));
		assertFalse("the player's part never came", never.isDone());
	}

	@Test
	public void aRunWaitsForTheClientThreadsPartWhenItComesInTime() throws Exception
	{
		final Diagnostics.Facts.Builder facts = Diagnostics.Facts.builder();
		final CompletableFuture<Void> player = new CompletableFuture<>();
		final CompletableFuture<TroubleshootDialog.Contents> got = new CompletableFuture<>();

		troubleshooter().run(facts, player, got::complete);
		assertFalse("not yet: the player's facts are not in", got.isDone());

		facts.loggedIn(true).accountKnown(true).bankEvents(2, 0, 1, 0).card(CARD_LIST);
		player.complete(null);

		final TroubleshootDialog.Contents contents = got.get(5, TimeUnit.SECONDS);
		assertEquals(Troubleshooter.VERDICT_FINE, contents.verdict);
		assertFalse("it came in time, so nothing is said about the client thread", contents.report.contains("client thread"));
	}

	@Test
	public void aRunThatFindsANewerVersionOnTheHubPutsItFirstInTheVerdictAndTheReport() throws Exception
	{
		replies.put(MANIFEST_URL, body(manifest(512, display(entry("999.0.0")))));
		final CompletableFuture<TroubleshootDialog.Contents> got = new CompletableFuture<>();

		troubleshooter().run(healthy(), CompletableFuture.completedFuture(null), got::complete);

		final TroubleshootDialog.Contents contents = got.get(5, TimeUnit.SECONDS);
		final String outOfDate = "You have " + Version.CURRENT + ", the Hub has 999.0.0. Restart RuneLite to update.";
		assertEquals(outOfDate, contents.verdict);
		assertTrue(contents.report, contents.report.contains("\nVerdict: " + outOfDate + "\n"));
		assertTrue(contents.report, contents.report.contains("plugin version: ok, HTTP 200,"));
		assertTrue(contents.report, contents.report.contains("the Hub has 999.0.0, this client runs " + Version.CURRENT));
	}
}
