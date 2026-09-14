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
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * {@link TradedPriceClient} against addendum T line T2, headless and offline: no real socket is ever opened.
 *
 * <p>The harness is {@code GuidePriceClientTest}'s, unchanged and for its reasons: the seam is
 * {@code OkHttpClient.newCall(Request)} - a public non-final method on a non-final class, so Mockito 4.11 can
 * mock it without mockito-inline - and the mocked {@link Call} answers {@code enqueue} by invoking the callback
 * INLINE on the test thread, taking the next reply off a queue, so every future completes before the fetch
 * method returns and no latch and no sleep is needed anywhere (playbook 7.6). Each reply gets a FRESH
 * {@link Response} built with {@code Response.Builder}, because a body is a one-shot stream and because the
 * production code should run against real OkHttp types rather than a mock's defaults.
 *
 * <p>The bodies are the real feeds' shapes, cut down: {@code {"data":{"<id>":{...}}}} for both endpoints, with
 * the nulls the API really serves (an item nobody has bought has no {@code high} and no {@code highTime}; a
 * daily bucket can carry a volume and no price).
 */
public class TradedPriceClientTest
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	/** 2026-09-11, the day before the addendum - one UTC day before the study's anchor. */
	private static final LocalDate SEP_11 = LocalDate.of(2026, 9, 11);

	/** 2026-09-11T12:00:00Z, inside that day, as unix seconds - the clock the freshness tests use. */
	private static final long NOON_SEP_11 = SEP_11.toEpochDay() * 86_400L + 12L * 3_600L;

	private static final int WHIP = 4151;
	private static final int BOW = 20_997;
	private static final int SHARK = 385;

	/** Both sides, both fresh - the ordinary liquid item. */
	private static final String LATEST_BODY = "{\"data\":{"
		+ "\"4151\":{\"high\":1700000,\"highTime\":1789041600,\"low\":1690000,\"lowTime\":1789041000},"
		+ "\"20997\":{\"high\":63600000,\"highTime\":1789040000,\"low\":63300000,\"lowTime\":1789039000},"
		+ "\"385\":{\"high\":1000,\"highTime\":1789041000,\"low\":null,\"lowTime\":null}"
		+ "}}";

	private static final String DAY_BODY = "{\"data\":{"
		+ "\"4151\":{\"avgHighPrice\":1699000,\"avgLowPrice\":1688000,\"highPriceVolume\":213,\"lowPriceVolume\":304},"
		+ "\"20997\":{\"avgHighPrice\":62200000,\"avgLowPrice\":62000000,\"highPriceVolume\":3,\"lowPriceVolume\":2},"
		+ "\"385\":{\"avgHighPrice\":null,\"avgLowPrice\":990,\"highPriceVolume\":0,\"lowPriceVolume\":41000}"
		+ "},\"timestamp\":1788998400}";

	private final List<Request> requests = new ArrayList<>();
	private final Deque<Reply> replies = new ArrayDeque<>();
	private final AtomicInteger bodyCloses = new AtomicInteger();
	/** What died on the simulated dispatcher thread, so a test can assert the Error was not swallowed. */
	private final List<Throwable> dispatched = Collections.synchronizedList(new ArrayList<>());

	private OkHttpClient http;
	private Call call;
	private TradedPriceClient client;

	@Before
	public void setUp()
	{
		http = mock(OkHttpClient.class);
		call = mock(Call.class);
		client = new TradedPriceClient(http, new Gson());

		doAnswer(invocation ->
		{
			requests.add(invocation.getArgument(0));
			return call;
		}).when(http).newCall(any(Request.class));

		doAnswer(invocation ->
		{
			final Callback callback = invocation.getArgument(0);
			final Reply reply = replies.poll();
			if (reply == null)
			{
				callback.onResponse(call, response(599, "TEST BUG: no reply queued for request " + requests.size()));
			}
			else if (reply.mode == Mode.PENDING)
			{
				return null;
			}
			else if (reply.mode == Mode.EXPLODE)
			{
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

	// ---------------------------------------------------------------- the endpoints and the timestamp (T2)

	@Test
	public void theEndpointsAndTheUserAgentAreTheOnesAddendumTNames()
	{
		assertEquals("https://prices.runescape.wiki/api/v1/osrs/latest", TradedPriceClient.LATEST_URL);
		assertEquals("https://prices.runescape.wiki/api/v1/osrs/24h", TradedPriceClient.DAILY_URL);
		assertEquals("one plugin, one User-Agent string - not a second spelling of the guide client's",
			GuidePriceClient.USER_AGENT, TradedPriceClient.USER_AGENT);
		assertEquals(86_400L, TradedPriceClient.SECONDS_PER_DAY);
	}

	/**
	 * T2 b: the bulk endpoint indexes a day by the UTC midnight that OPENS it, in seconds. Pinned against
	 * hand-computed values rather than against a second implementation of the same arithmetic, and across a
	 * month end and a leap day, which is where a "days x 86400 from an arbitrary epoch" bug would show.
	 */
	@Test
	public void theDayTimestampIsTheUtcMidnightThatOpensIt()
	{
		assertEquals("1970-01-01 is the epoch itself", 0L,
			TradedPriceClient.midnightSeconds(LocalDate.of(1970, 1, 1)));
		assertEquals(86_400L, TradedPriceClient.midnightSeconds(LocalDate.of(1970, 1, 2)));
		// 2026-09-11T00:00:00Z
		assertEquals(1_789_084_800L, TradedPriceClient.midnightSeconds(SEP_11));
		// The day after a month end, and a leap day, both by calendar arithmetic rather than by adding 86400.
		assertEquals(TradedPriceClient.midnightSeconds(LocalDate.of(2026, 8, 31)) + 86_400L,
			TradedPriceClient.midnightSeconds(LocalDate.of(2026, 9, 1)));
		assertEquals(TradedPriceClient.midnightSeconds(LocalDate.of(2024, 2, 28)) + 86_400L,
			TradedPriceClient.midnightSeconds(LocalDate.of(2024, 2, 29)));
	}

	@Test
	public void theDayUrlCarriesThatMidnightAndNothingElse()
	{
		assertEquals("https://prices.runescape.wiki/api/v1/osrs/24h?timestamp=1789084800",
			TradedPriceClient.dayUrl(SEP_11));
		assertEquals("the latest feed is a bare GET", "https://prices.runescape.wiki/api/v1/osrs/latest",
			TradedPriceClient.latestUrl());
		assertFalse(TradedPriceClient.latestUrl().contains("?"));
	}

	/**
	 * B035's rule, for the second host: NO request this plugin makes carries anything derived from the player, so
	 * every user's requests are byte-identical and the hosts learn only an IP. The traded feeds are bulk feeds -
	 * the whole game's prices, every time - precisely so no item id ever has to leave the client.
	 */
	@Test
	public void theRequestUrlsCarryNothingDerivedFromThePlayer()
	{
		final String day = TradedPriceClient.dayUrl(SEP_11);
		final String query = day.substring(day.indexOf('?') + 1);
		assertTrue("the only parameter is a UTC midnight the plugin computed: " + query,
			query.matches("timestamp=[0-9]+"));
		assertFalse("no item id is ever asked for by name or number", day.contains("id"));
	}

	@Test
	public void everyRequestCarriesTheUserAgentHeader()
	{
		queue(LATEST_BODY);
		client.fetchLatest(1L);
		queue(DAY_BODY);
		client.fetchDay(SEP_11, 1L);

		assertEquals(2, requests.size());
		for (final Request request : requests)
		{
			assertEquals(TradedPriceClient.USER_AGENT, request.header("User-Agent"));
			assertEquals("GET", request.method());
		}
		assertEquals(TradedPriceClient.latestUrl(), requests.get(0).url().toString());
		assertEquals(TradedPriceClient.dayUrl(SEP_11), requests.get(1).url().toString());
	}

	// ---------------------------------------------------------------- /latest (T2 a)

	@Test
	public void theLatestFeedIsReadAsQuotesWithBothSidesAndTheirTimes() throws Exception
	{
		queue(LATEST_BODY);

		final Map<Integer, TradedPriceClient.Quote> quotes = client.fetchLatest(1L).get(5, TimeUnit.SECONDS);

		assertEquals(3, quotes.size());
		assertEquals("the dearest item in the fixture round-trips like any other", Long.valueOf(63_450_000L),
			quotes.get(BOW).mid());
		final TradedPriceClient.Quote whip = quotes.get(WHIP);
		assertEquals(Long.valueOf(1_700_000L), whip.buy());
		assertEquals(Long.valueOf(1_690_000L), whip.sell());
		assertEquals(1_789_041_600L, whip.buySeconds());
		assertEquals(1_789_041_000L, whip.sellSeconds());
		assertTrue(whip.hasBothSides());
	}

	/** The API really serves nulls: an item nobody has sold has no {@code low} and no {@code lowTime}. */
	@Test
	public void aNullSideIsKeptAsNullAndNeverAsZero() throws Exception
	{
		queue(LATEST_BODY);

		final TradedPriceClient.Quote shark = client.fetchLatest(1L).get(5, TimeUnit.SECONDS).get(SHARK);

		assertEquals(Long.valueOf(1_000L), shark.buy());
		assertNull("a missing side is no price, not a price of zero", shark.sell());
		assertEquals("and its time is unknown rather than 1970", 0L, shark.sellSeconds());
		assertFalse(shark.hasBothSides());
		assertNull("a one-sided quote has no mid at all - T3 refuses it, and a mid would hide that", shark.mid());
		assertNull(shark.spread());
	}

	@Test
	public void anEntryWithNeitherSideIsDropped() throws Exception
	{
		queue("{\"data\":{\"4151\":{\"high\":10,\"highTime\":1,\"low\":10,\"lowTime\":1},"
			+ "\"385\":{\"high\":null,\"low\":null}}}");

		final Map<Integer, TradedPriceClient.Quote> quotes = client.fetchLatest(1L).get(5, TimeUnit.SECONDS);

		assertEquals(Collections.singleton(WHIP), quotes.keySet());
	}

	@Test
	public void aKeyThatIsNotAnItemIdIsSkippedRatherThanFatal() throws Exception
	{
		queue("{\"data\":{\"4151\":{\"high\":10,\"highTime\":1,\"low\":10,\"lowTime\":1},"
			+ "\"not-a-number\":{\"high\":1,\"low\":1},\"0\":{\"high\":1,\"low\":1}}}");

		assertEquals(Collections.singleton(WHIP), client.fetchLatest(1L).get(5, TimeUnit.SECONDS).keySet());
	}

	// ---------------------------------------------------------------- the mid and the spread (T3's inputs)

	/** T3: integer arithmetic, rounded HALF UP - so an odd sum rounds away from the buyer, not toward them. */
	@Test
	public void theMidIsTheTwoSidesRoundedHalfUp()
	{
		assertEquals(Long.valueOf(10L), quote(10L, 10L).mid());
		assertEquals("(10 + 11) / 2 = 10.5 -> 11", Long.valueOf(11L), quote(11L, 10L).mid());
		assertEquals("the sides either way round give the same mid", Long.valueOf(11L), quote(10L, 11L).mid());
		assertEquals(Long.valueOf(1_695_000L), quote(1_700_000L, 1_690_000L).mid());
		assertEquals("no overflow on a pair near Long.MAX_VALUE", Long.valueOf(Long.MAX_VALUE / 2L),
			quote(Long.MAX_VALUE / 2L, Long.MAX_VALUE / 2L).mid());
	}

	/** Absolute, because nothing guarantees which side of the book traded last (a negative gap would sail through). */
	@Test
	public void theSpreadIsTheAbsoluteGapBetweenTheSides()
	{
		assertEquals(Long.valueOf(10_000L), quote(1_700_000L, 1_690_000L).spread());
		assertEquals("a crossed book still reports a gap", Long.valueOf(10_000L),
			quote(1_690_000L, 1_700_000L).spread());
		assertEquals(Long.valueOf(0L), quote(5L, 5L).spread());
	}

	/** T3 check 2's freshness half: both sides inside the window, and clock skew forgiven. */
	@Test
	public void aQuoteIsFreshWhenBothSidesTradedInsideTheWindow()
	{
		final long day = 86_400L;
		assertTrue(new TradedPriceClient.Quote(10L, NOON_SEP_11 - day, 10L, NOON_SEP_11).tradedWithin(NOON_SEP_11, day));
		assertFalse("one second past the window is stale",
			new TradedPriceClient.Quote(10L, NOON_SEP_11 - day - 1L, 10L, NOON_SEP_11).tradedWithin(NOON_SEP_11, day));
		assertFalse("the OTHER side counts too",
			new TradedPriceClient.Quote(10L, NOON_SEP_11, 10L, NOON_SEP_11 - day - 1L).tradedWithin(NOON_SEP_11, day));
		assertTrue("a time in the future is clock skew, not a stale quote",
			new TradedPriceClient.Quote(10L, NOON_SEP_11 + 600L, 10L, NOON_SEP_11).tradedWithin(NOON_SEP_11, day));
		assertFalse("a side with no time at all is not fresh",
			new TradedPriceClient.Quote(10L, 0L, 10L, NOON_SEP_11).tradedWithin(NOON_SEP_11, day));
		assertFalse("and neither is a one-sided quote",
			new TradedPriceClient.Quote(10L, NOON_SEP_11, null, 0L).tradedWithin(NOON_SEP_11, day));
	}

	// ---------------------------------------------------------------- /24h (T2 b)

	@Test
	public void theDailyBucketIsReadAsAveragesAndVolumes() throws Exception
	{
		queue(DAY_BODY);

		final Map<Integer, TradedPriceClient.Bucket> buckets = client.fetchDay(SEP_11, 1L).get(5, TimeUnit.SECONDS);

		final TradedPriceClient.Bucket whip = buckets.get(WHIP);
		assertEquals(Long.valueOf(1_699_000L), whip.avgHigh());
		assertEquals(Long.valueOf(1_688_000L), whip.avgLow());
		assertEquals(213L, whip.highVolume());
		assertEquals(304L, whip.lowVolume());
		assertEquals("the volume check adds both sides", 517L, whip.volume());
	}

	/** T4: {@code (avgHigh x hv + avgLow x lv) / (hv + lv)}, rounded half up. */
	@Test
	public void theDaysOnePriceIsTheVolumeWeightedAverage()
	{
		// (1699000 * 213 + 1688000 * 304) / 517 = 1692531.9... -> 1692532
		assertEquals(Long.valueOf(1_692_532L),
			new TradedPriceClient.Bucket(1_699_000L, 213L, 1_688_000L, 304L).weightedAverage());
		assertEquals("equal weights give the plain mean", Long.valueOf(15L),
			new TradedPriceClient.Bucket(20L, 5L, 10L, 5L).weightedAverage());
		assertEquals("half up", Long.valueOf(11L), new TradedPriceClient.Bucket(11L, 1L, 10L, 1L).weightedAverage());
	}

	/** T4: "one side only when the other is absent" - whatever the volumes say. */
	@Test
	public void oneSidedAndUnweightedBucketsStillNameAPrice() throws Exception
	{
		queue(DAY_BODY);
		final TradedPriceClient.Bucket shark = client.fetchDay(SEP_11, 1L).get(5, TimeUnit.SECONDS).get(SHARK);

		assertNull("the buy side really is absent", shark.avgHigh());
		assertEquals("so the sell side IS the day's price", Long.valueOf(990L), shark.weightedAverage());
		assertEquals("and its volume still counts", 41_000L, shark.volume());

		assertEquals("a bucket with prices and no volume falls back to the plain mean rather than dividing by 0",
			Long.valueOf(15L), new TradedPriceClient.Bucket(20L, 0L, 10L, 0L).weightedAverage());
		assertNull("a bucket with no price at all names none",
			new TradedPriceClient.Bucket(null, 5L, null, 7L).weightedAverage());
	}

	/**
	 * Addendum V line V3's two inputs: the middle of the day's two averages, rounded half up by exactly the
	 * arithmetic {@code Quote.mid()} rounds the live pair with, and the absolute gap between them. Both are null
	 * for a one-sided day - one number has no middle and no gap - which V3 reads as "nothing to measure" rather
	 * than as a failure.
	 */
	@Test
	public void theDaysMiddleAndGapAreTheOnesTheGapCheckMeasures()
	{
		final TradedPriceClient.Bucket tinderbox = new TradedPriceClient.Bucket(37L, 5_063L, 12L, 484L);
		assertEquals("(37 + 12 + 1) / 2", Long.valueOf(25L), tinderbox.bucketMid());
		assertEquals("and a gap of exactly its own middle - 100 % apart", Long.valueOf(25L), tinderbox.gap());

		assertEquals("half up, as the live mid rounds", Long.valueOf(11L),
			new TradedPriceClient.Bucket(11L, 1L, 10L, 1L).bucketMid());
		assertEquals("a crossed day still reports a gap", Long.valueOf(10_000L),
			new TradedPriceClient.Bucket(1_690_000L, 5L, 1_700_000L, 5L).gap());
		assertEquals("no overflow on a pair near Long.MAX_VALUE", Long.valueOf(Long.MAX_VALUE / 2L),
			new TradedPriceClient.Bucket(Long.MAX_VALUE / 2L, 1L, Long.MAX_VALUE / 2L, 1L).bucketMid());

		final TradedPriceClient.Bucket boughtOnly = new TradedPriceClient.Bucket(1_000L, 5L, null, 0L);
		final TradedPriceClient.Bucket soldOnly = new TradedPriceClient.Bucket(null, 0L, 1_000L, 5L);
		assertNull(boughtOnly.bucketMid());
		assertNull(boughtOnly.gap());
		assertNull(soldOnly.bucketMid());
		assertNull(soldOnly.gap());
	}

	@Test
	public void aBucketWithAVolumeAndNoPriceIsKeptBecauseTheVolumeCheckReadsIt() throws Exception
	{
		queue("{\"data\":{\"4151\":{\"avgHighPrice\":null,\"avgLowPrice\":null,"
			+ "\"highPriceVolume\":700,\"lowPriceVolume\":300}}}");

		final TradedPriceClient.Bucket bucket = client.fetchDay(SEP_11, 1L).get(5, TimeUnit.SECONDS).get(WHIP);

		assertNotNull("kept: T3's volume check reads the volumes and T4's price rule reads the price", bucket);
		assertEquals(1_000L, bucket.volume());
		assertNull(bucket.weightedAverage());
	}

	@Test
	public void anEntryWithNoPriceAndNoVolumeIsDropped() throws Exception
	{
		queue("{\"data\":{\"4151\":{\"avgHighPrice\":10,\"avgLowPrice\":10,\"highPriceVolume\":5,"
			+ "\"lowPriceVolume\":5},\"385\":{\"avgHighPrice\":null,\"avgLowPrice\":null,"
			+ "\"highPriceVolume\":0,\"lowPriceVolume\":0}}}");

		assertEquals(Collections.singleton(WHIP), client.fetchDay(SEP_11, 1L).get(5, TimeUnit.SECONDS).keySet());
	}

	/** A negative volume can only come from a hand-edited proxy; it must never become a negative sum. */
	@Test
	public void aNegativeVolumeReadsAsZero()
	{
		assertEquals(0L, new TradedPriceClient.Bucket(10L, -5L, 10L, 0L).highVolume());
		assertEquals(0L, new TradedPriceClient.Bucket(10L, 0L, 10L, -5L).lowVolume());
	}

	// ---------------------------------------------------------------- the fail gate (one exception type)

	@Test
	public void aNonSuccessStatusFailsWithTheStatusOnTheException()
	{
		queue(403, "blocked");

		final WikiPriceException failure = failureOf(client.fetchLatest(1L));

		assertEquals(403, failure.getHttpCode());
		assertTrue(failure.hasHttpCode());
		assertTrue(failure.getMessage(), failure.getMessage().contains("403"));
	}

	@Test
	public void aTransportFailureBecomesTheSameExceptionType()
	{
		queue(new IOException("Blocking network calls are not allowed on the client thread"));

		final WikiPriceException failure = failureOf(client.fetchLatest(1L));

		assertEquals("a transport failure carries no status line", WikiPriceException.NO_HTTP_CODE,
			failure.getHttpCode());
		assertTrue(failure.getMessage(), failure.getMessage().contains("client thread"));
	}

	/** Every shape of an unusable body: one type out, never null, and never a half-read map. */
	@Test
	public void everyUnusableBodyFailsRatherThanAnsweringAnEmptyFeed()
	{
		assertFails("", "empty response");
		assertFails("   ", "empty response");
		assertFails("null", "not a JSON object");
		assertFails("[1,2,3]", "not a JSON object");
		assertFails("{\"oops\"", "malformed JSON");
		assertFails("{\"data\":[]}", "no data object");
		assertFails("{}", "no data object");
		assertFails("{\"data\":{}}", "carried no prices");
	}

	/**
	 * The running day answers an empty bucket, which is why {@code PriceService} only ever asks for a day that
	 * has closed - and why an empty answer must be a FAILURE here: stored as an empty bucket it would be
	 * believed, and every window of every row would silently fall back to the guide.
	 */
	@Test
	public void anEmptyDailyBucketIsAFailureAndNotAnEmptyDay()
	{
		queue("{\"data\":{}}");

		final WikiPriceException failure = failureOf(client.fetchDay(SEP_11, 1L));

		assertTrue(failure.getMessage(), failure.getMessage().contains("carried no entries"));
	}

	@Test
	public void aDayOfNullIsRefusedWithoutTouchingTheNetwork()
	{
		final WikiPriceException failure = failureOf(client.fetchDay(null, 1L));

		assertTrue(failure.getMessage(), failure.getMessage().contains("no day"));
		verify(http, never()).newCall(any(Request.class));
	}

	@Test
	public void theResponseIsClosedOnEveryPath() throws Exception
	{
		queue(LATEST_BODY);
		client.fetchLatest(1L).get(5, TimeUnit.SECONDS);
		queue(500, "boom");
		failureOf(client.fetchLatest(1L));
		queue("{\"data\":{}}");
		failureOf(client.fetchLatest(1L));

		assertEquals("a success, a bad status and an unusable body all close the body", 3, bodyCloses.get());
	}

	/**
	 * B006, for the second client: OkHttp 3.14.9 rethrows a Throwable from inside {@code onResponse} on its own
	 * dispatcher thread rather than routing it to {@code onFailure}, so the future must be completed BEFORE the
	 * Error is rethrown - otherwise the service's in-flight flag is claimed for the rest of the session.
	 */
	@Test
	public void anErrorInsideTheCallbackStillCompletesTheFuture()
	{
		queueAnErrorInsideTheCallback();

		final CompletableFuture<Map<Integer, TradedPriceClient.Quote>> future = client.fetchLatest(1L);

		assertTrue("the future must not be left hanging", future.isCompletedExceptionally());
		assertEquals("and the Error must still reach the JVM's own handler", 1, dispatched.size());
		assertTrue(dispatched.get(0) instanceof OutOfMemoryError);
	}

	@Test
	public void theDeveloperSwitchFailsEveryFetchWithoutTouchingTheNetwork()
	{
		assertTrue("shipped state", client.isEnabled());
		client.setEnabled(false);

		final WikiPriceException latest = failureOf(client.fetchLatest(1L));
		final WikiPriceException day = failureOf(client.fetchDay(SEP_11, 1L));

		assertTrue(latest.getMessage(), latest.getMessage().contains("switched off"));
		assertTrue(day.getMessage(), day.getMessage().contains("switched off"));
		verify(http, never()).newCall(any(Request.class));

		client.setEnabled(true);
		queue(LATEST_BODY);
		client.fetchLatest(1L);
		verify(http, times(1)).newCall(any(Request.class));
	}

	@Test
	public void cancelInFlightCancelsEveryCallStillOnTheWire()
	{
		queueNoAnswer();
		queueNoAnswer();
		final CompletableFuture<Map<Integer, TradedPriceClient.Quote>> pending = client.fetchLatest(1L);
		client.fetchDay(SEP_11, 1L);

		assertFalse(pending.isDone());
		client.cancelInFlight();

		verify(call, times(2)).cancel();
		// A cancelled call has left the in-flight list, so a second sweep cancels nothing again.
		client.cancelInFlight();
		verify(call, times(2)).cancel();
	}

	@Test
	public void anAnsweredCallIsNotCancelledAfterwards() throws Exception
	{
		queue(LATEST_BODY);
		client.fetchLatest(1L).get(5, TimeUnit.SECONDS);

		client.cancelInFlight();

		verify(call, never()).cancel();
	}

	@Test
	public void theClientRefusesToBeBuiltWithoutTheInjectedDependencies()
	{
		try
		{
			new TradedPriceClient(null, new Gson());
			fail("a null client must be refused - constructing one here is a Plugin Hub blocker");
		}
		catch (final IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("client"));
		}
		try
		{
			new TradedPriceClient(http, null);
			fail("a null Gson must be refused for the same reason");
		}
		catch (final IllegalArgumentException expected)
		{
			assertTrue(expected.getMessage(), expected.getMessage().contains("gson"));
		}
	}

	// ---------------------------------------------------------------- helpers

	private static TradedPriceClient.Quote quote(final Long buy, final Long sell)
	{
		return new TradedPriceClient.Quote(buy, NOON_SEP_11, sell, NOON_SEP_11);
	}

	private void assertFails(final String body, final String fragment)
	{
		queue(body);
		final WikiPriceException failure = failureOf(client.fetchLatest(1L));
		assertTrue(failure.getMessage() + " should mention " + fragment, failure.getMessage().contains(fragment));
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

	private void queueAnErrorInsideTheCallback()
	{
		replies.add(new Reply(200, null, null, Mode.EXPLODE));
	}

	private void queueNoAnswer()
	{
		replies.add(new Reply(0, null, null, Mode.PENDING));
	}

	/** A FRESH response per reply - a response body is a one-shot stream. A null body throws an Error when read. */
	private Response response(final int code, final String body)
	{
		final Request request = requests.isEmpty()
			? new Request.Builder().url(TradedPriceClient.LATEST_URL).build()
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

	/** How the mocked call answers: the ordinary way, not at all, or by raising an {@link Error}. */
	private enum Mode
	{
		NORMAL,
		PENDING,
		EXPLODE
	}

	/** A body whose bytes cannot be read without raising an {@link Error} - an OOM while parsing, say. */
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
			throw new OutOfMemoryError("simulated heap exhaustion while reading the traded feed");
		}

		@Override
		public void close()
		{
			// Nothing to close, and nothing may be thrown from here.
		}
	}

	/** A body that counts {@code close()} - the only hook there is, since {@link Response} is final. */
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
