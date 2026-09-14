package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * The TRADED price feeds of addendum T line T2 - the OSRS Wiki's real-time prices, which is a different series
 * from the Jagex guide table {@link GuidePriceClient} reads and is only ever used for a stack that passes the
 * liquidity checks of T3 (three at addendum T, five since addendum V).
 *
 * <p>Two endpoints, and nothing else:
 * <ul>
 * <li>{@value #LATEST_URL} - the newest instant-buy and instant-sell per item, each with the second it was
 * traded at: {@code {"data":{"4151":{"high":1700000,"highTime":1788859512,"low":1690000,"lowTime":1788859480}}}}.
 * Any of the four members may be null (an item nobody has bought, or nobody has sold, since the API's own
 * horizon). Fetched on every accepted Refresh, on the thirty-minute tick and at start-up, and only while the
 * {@code livePrices} switch is on;</li>
 * <li>{@value #DAILY_URL}{@code ?timestamp=<UTC midnight of day D in seconds>} - the BULK daily bucket, one
 * entry per item traded that day: {@code {"data":{"4151":{"avgHighPrice":1699000,"avgLowPrice":1688000,
 * "highPriceVolume":213,"lowPriceVolume":304}},"timestamp":1788825600}}. A price may be null; a volume is 0 when
 * the side saw no trade. Fetched once per UTC day per window, for the day that window counts back to from the
 * live snapshot's own date ({@code liveDay - N}, addendum U line U1), and never for TODAY - the running day
 * answers an empty bucket, and every window is at least one day back. An EMPTY answer for a past day means the
 * wiki has not closed that day yet, and {@code PriceService} then asks once for the day before it (U2).</li>
 * </ul>
 *
 * <p><b>Why this is a second client and not three more methods on {@link GuidePriceClient}.</b> That class is
 * the GUIDE series - one host, one page, one set of failure shapes, and a class comment that promises every
 * printed figure comes from Jagex's daily table. The traded series answers a different question (what someone
 * actually paid, minutes ago), has its own hosts and its own fail gate, and is switched off entirely by one
 * config item. Keeping them apart is what makes "the switch off is byte-identical to today" a property of the
 * wiring rather than a promise about branches.
 *
 * <p><b>What leaves the client, exactly.</b> As with {@link GuidePriceClient}, neither URL carries anything
 * derived from the player: {@link #latestUrl()} is a bare GET, and {@link #dayUrl(LocalDate)} carries one UTC
 * midnight the plugin computed from a date it derived itself. No item id, no quantity, no account. Every user
 * of this plugin issues byte-identical requests, and the hosts learn only an IP and that the plugin is
 * installed. {@code theRequestUrlsCarryNothingDerivedFromThePlayer} in the tests keeps that true.
 *
 * <p><b>Why the client and the Gson are injected, and why enqueue.</b> Both for the reasons spelled out on
 * {@link GuidePriceClient}: RuneLite's {@code @Provides OkHttpClient} is the only one whose requests carry a
 * usable User-Agent and transparent gzip, constructing an OkHttpClient or a Gson in plugin code is a Plugin Hub
 * packaging blocker, and {@code RuneLiteModule} throws for a blocking call made on the client thread or the EDT.
 * The User-Agent string is {@link GuidePriceClient#USER_AGENT} itself rather than a second spelling of it, so an
 * operator reading either wiki host's logs sees one plugin.
 *
 * <p><b>One failure type</b> ({@link WikiPriceException}), exactly as the guide client has: a non-2xx, a body
 * that is not the expected shape, an empty feed and a transport {@code IOException} all complete the future
 * exceptionally, and the future never completes with null. {@code PriceService} keeps whatever it last stored
 * and carries on with the guide prices (T7).
 *
 * <p>This class holds no cache and no schedule: staleness, the six-hour rule and one-in-flight-per-feed are
 * {@code PriceService}'s. Its only state is {@link #setEnabled(boolean)}, the calls still on the wire for
 * {@link #cancelInFlight()}, and the "a failure has already been logged loudly" flag.
 */
public class TradedPriceClient
{
	private static final Logger log = LoggerFactory.getLogger(TradedPriceClient.class);

	/** The newest instant-buy / instant-sell per item (T2 a). */
	public static final String LATEST_URL = "https://prices.runescape.wiki/api/v1/osrs/latest";

	/** The bulk daily bucket (T2 b); one {@code timestamp} parameter names the UTC day. */
	public static final String DAILY_URL = "https://prices.runescape.wiki/api/v1/osrs/24h";

	/** The same descriptive User-Agent the guide client sends - one plugin, one string. */
	public static final String USER_AGENT = GuidePriceClient.USER_AGENT;

	/** Header name, spelled once so the request builder and the tests cannot drift apart. */
	public static final String USER_AGENT_HEADER = GuidePriceClient.USER_AGENT_HEADER;

	/** Seconds in a UTC calendar day - what {@link #midnightSeconds(LocalDate)} multiplies by. */
	public static final long SECONDS_PER_DAY = 86_400L;

	/** Error and diagnostic text is cut to this many characters before it reaches a message or the log. */
	private static final int MAX_DETAIL_CHARS = 200;

	private final OkHttpClient client;
	private final Gson gson;

	/**
	 * Developer-mode switch, the twin of {@link GuidePriceClient#setEnabled(boolean)}: when false every fetch
	 * fails immediately without touching the network, so T7's "live prices unavailable" state can be exercised in
	 * a live client. Volatile because it is written from the bridge/EDT and read on whichever thread starts a
	 * fetch. Never flipped by user-facing code.
	 */
	private volatile boolean enabled = true;

	/** Every call enqueued and not yet answered, so {@link #cancelInFlight()} can drop them when the plugin stops. */
	private final List<Call> inFlight = new CopyOnWriteArrayList<>();

	/** Whether {@link #report} has already logged a failure loudly this session. */
	private volatile boolean reported;

	/**
	 * @param client RuneLite's injected OkHttpClient; constructing one here is a Plugin Hub blocker
	 * @param gson   RuneLite's injected Gson, for the same reason
	 */
	public TradedPriceClient(final OkHttpClient client, final Gson gson)
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

	// ---------------------------------------------------------------- the two fetches (T2)

	/**
	 * The newest traded quote per item (T2 a).
	 *
	 * @param nowMillis the caller's clock; not part of the request, but taken so the SERVICE's settable clock -
	 *                  never {@code System.currentTimeMillis()} in here - decides what "six hours old" means (T7)
	 * @return a future completing with item id to {@link Quote}, or exceptionally with {@link WikiPriceException}
	 */
	public CompletableFuture<Map<Integer, Quote>> fetchLatest(final long nowMillis)
	{
		log.debug("bank-portfolio-tracker: fetching the traded /latest snapshot at {}", nowMillis);
		return request(latestUrl(), this::parseLatest);
	}

	/**
	 * One whole UTC calendar day's traded bucket (T2 b).
	 *
	 * @param day       the day; must be in the past - the running day answers an empty bucket, and
	 *                  {@code PriceService} never asks for it. Null is refused rather than turned into "today"
	 * @param nowMillis stamped by the caller onto whatever it stores; not part of the request
	 * @return a future completing with item id to {@link Bucket}, or exceptionally with {@link WikiPriceException}
	 */
	public CompletableFuture<Map<Integer, Bucket>> fetchDay(final LocalDate day, final long nowMillis)
	{
		if (day == null)
		{
			// Deliberately a failure and not an empty success: a caller with no day has a bug, and an empty map
			// would look like "that day traded nothing" to the code that stores buckets.
			return failed(new WikiPriceException("no day to fetch a traded bucket for"));
		}
		log.debug("bank-portfolio-tracker: fetching the traded bucket of {} at {}", day, nowMillis);
		return request(dayUrl(day), this::parseDay);
	}

	/**
	 * Developer-mode kill switch, the twin of {@link GuidePriceClient#setEnabled(boolean)}; true is the shipped
	 * state. User-facing behaviour must never depend on this (workspace rule: no user feature may need a dev hook).
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

	/**
	 * Cancels every call still on the wire, from {@code PriceService.stop()}. {@code Call.cancel()} is safe from
	 * any thread; a cancelled call's {@code onFailure} completes its future as an ordinary failure.
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
				log.debug("bank-portfolio-tracker: a traded-price call refused to cancel", e);
			}
		}
	}

	// ---------------------------------------------------------------- the exact URLs

	/** {@value #LATEST_URL}, with no parameters at all. */
	static String latestUrl()
	{
		return LATEST_URL;
	}

	/**
	 * {@value #DAILY_URL}{@code ?timestamp=<UTC midnight of the day, in seconds>} - the only parameter this
	 * plugin ever sends to either wiki host, and it is a date the plugin derived from the guide tables rather
	 * than anything about the player.
	 */
	static String dayUrl(final LocalDate day)
	{
		return DAILY_URL + "?timestamp=" + midnightSeconds(day);
	}

	/**
	 * The UTC midnight that opens a calendar day, in unix seconds - the {@code timestamp} the bulk endpoint
	 * indexes a day by.
	 *
	 * <p>{@link LocalDate#toEpochDay()} times {@value #SECONDS_PER_DAY} rather than a zoned conversion: every
	 * date in this plugin is already a UTC date (addendum L line L9), and a day here has exactly 86,400 seconds -
	 * there are no leap seconds in unix time and no zone to shift it.
	 */
	static long midnightSeconds(final LocalDate day)
	{
		Objects.requireNonNull(day, "day");
		return day.toEpochDay() * SECONDS_PER_DAY;
	}

	// ---------------------------------------------------------------- parsers (package-private, pure)

	/**
	 * The {@code /latest} body as item id to {@link Quote}. Shape:
	 * {@code {"data":{"2":{"high":193,"highTime":1616682200,"low":186,"lowTime":1616682209}, ...}}}.
	 *
	 * <p>Any of the four members may be null or absent, and an entry with NEITHER price is dropped: it names no
	 * traded price, so nothing downstream could do anything with it but skip it. An id that is not an integer is
	 * skipped rather than thrown, for the same reason the guide parsers skip an odd entry - one bad row must not
	 * cost the other four thousand.
	 *
	 * @throws WikiPriceException on a non-object body, a missing {@code data} object, or a feed with no usable
	 *                            entry in it (a route change, as opposed to one odd item)
	 */
	Map<Integer, Quote> parseLatest(final String json) throws WikiPriceException
	{
		final JsonObject data = dataOf(readObject(json), "latest");
		final Map<Integer, Quote> quotes = new LinkedHashMap<>();
		for (final Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			final Integer id = parseId(entry.getKey());
			final JsonObject value = objectOf(entry.getValue());
			if (id == null || value == null)
			{
				continue;
			}
			final Long high = optLong(value, "high");
			final Long low = optLong(value, "low");
			if (high == null && low == null)
			{
				continue;
			}
			quotes.put(id, new Quote(high, seconds(value, "highTime"), low, seconds(value, "lowTime")));
		}
		if (quotes.isEmpty())
		{
			throw new WikiPriceException("the wiki traded /latest feed carried no prices");
		}
		return quotes;
	}

	/**
	 * A {@code /24h?timestamp=} body as item id to {@link Bucket}. Shape:
	 * {@code {"data":{"2":{"avgHighPrice":190,"avgLowPrice":186,"highPriceVolume":100,"lowPriceVolume":200},
	 * ...},"timestamp":1615766400}}.
	 *
	 * <p>Either price may be null while the volumes stand - an item bought but never sold that day - and that
	 * entry is KEPT, because T3's volume check reads the volumes and T4's own rule reads the price separately.
	 * An entry with no price and no volume names nothing and is dropped.
	 *
	 * @throws WikiPriceException on a non-object body, a missing {@code data} object, or an empty bucket - which
	 *                            is what the RUNNING day answers, and which is why a caller must only ever ask
	 *                            for a day that has closed
	 */
	Map<Integer, Bucket> parseDay(final String json) throws WikiPriceException
	{
		final JsonObject data = dataOf(readObject(json), "24h");
		final Map<Integer, Bucket> buckets = new LinkedHashMap<>();
		for (final Map.Entry<String, JsonElement> entry : data.entrySet())
		{
			final Integer id = parseId(entry.getKey());
			final JsonObject value = objectOf(entry.getValue());
			if (id == null || value == null)
			{
				continue;
			}
			final Long avgHigh = optLong(value, "avgHighPrice");
			final Long avgLow = optLong(value, "avgLowPrice");
			final long highVolume = Math.max(0L, orZero(optLong(value, "highPriceVolume")));
			final long lowVolume = Math.max(0L, orZero(optLong(value, "lowPriceVolume")));
			if (avgHigh == null && avgLow == null && highVolume == 0L && lowVolume == 0L)
			{
				continue;
			}
			buckets.put(id, new Bucket(avgHigh, highVolume, avgLow, lowVolume));
		}
		if (buckets.isEmpty())
		{
			throw new WikiPriceException("the wiki traded daily bucket carried no entries");
		}
		return buckets;
	}

	// ---------------------------------------------------------------- the two value types

	/**
	 * One item's newest traded pair from {@code /latest}: the instant-BUY price someone paid ({@code high}), the
	 * instant-SELL price someone accepted ({@code low}), and the second each was traded at. Either price may be
	 * absent; a quote with neither never reaches here ({@link #parseLatest}).
	 *
	 * <p>Immutable and shared: built on OkHttp's dispatcher thread, stored on the service's executor, read while
	 * the rows are computed.
	 */
	public static final class Quote
	{
		private final Long buy;
		private final Long sell;
		private final long buySeconds;
		private final long sellSeconds;

		/**
		 * @param buy         the newest instant-buy price ({@code high}), or null
		 * @param buySeconds  when it was traded, unix seconds; 0 when unknown
		 * @param sell        the newest instant-sell price ({@code low}), or null
		 * @param sellSeconds when it was traded, unix seconds; 0 when unknown
		 */
		public Quote(final Long buy, final long buySeconds, final Long sell, final long sellSeconds)
		{
			this.buy = buy;
			this.sell = sell;
			this.buySeconds = buySeconds;
			this.sellSeconds = sellSeconds;
		}

		/** The newest instant-buy price ({@code high}), or null when nobody has bought since the API's horizon. */
		public Long buy()
		{
			return buy;
		}

		/** The newest instant-sell price ({@code low}), or null when nobody has sold. */
		public Long sell()
		{
			return sell;
		}

		/** When {@link #buy()} was traded, unix seconds; 0 when there is no buy side. */
		public long buySeconds()
		{
			return buySeconds;
		}

		/** When {@link #sell()} was traded, unix seconds; 0 when there is no sell side. */
		public long sellSeconds()
		{
			return sellSeconds;
		}

		/** Both sides present - the first half of T3's spread check. */
		public boolean hasBothSides()
		{
			return buy != null && sell != null;
		}

		/**
		 * The live price of one item: the mean of the two sides, rounded HALF UP, in integer arithmetic (T3).
		 * Null when a side is missing - a one-sided quote is never a live price, because T3 refuses it anyway and
		 * inventing a mid from it would make the refusal look like a number.
		 *
		 * <p>The halves are added separately so a pair near {@link Long#MAX_VALUE} cannot overflow the way
		 * {@code (buy + sell) / 2} would; every real price is far below that.
		 */
		public Long mid()
		{
			if (!hasBothSides())
			{
				return null;
			}
			final long half = buy / 2L + sell / 2L;
			final long remainder = (buy % 2L) + (sell % 2L);
			return half + (remainder + 1L) / 2L;
		}

		/**
		 * The gap between the two sides, as an absolute figure - T3's spread numerator. Null when a side is
		 * missing. Absolute rather than {@code buy - sell}: the two sides are the newest trade on each side of
		 * the book and nothing guarantees which is larger, and a negative gap would sail through the check.
		 */
		public Long spread()
		{
			return hasBothSides() ? Long.valueOf(Math.abs(buy - sell)) : null;
		}

		/**
		 * Whether BOTH sides were traded inside the last {@code maxAgeSeconds} - T3's "each with a time inside the
		 * last 24 h". A time in the FUTURE passes: that is clock skew between the viewer's machine and the wiki,
		 * not a stale quote, and refusing it would make the feature depend on an unsynchronised clock.
		 *
		 * @param nowSeconds     the caller's clock in unix seconds
		 * @param maxAgeSeconds  how old a side may be
		 */
		public boolean tradedWithin(final long nowSeconds, final long maxAgeSeconds)
		{
			if (!hasBothSides() || buySeconds <= 0L || sellSeconds <= 0L)
			{
				return false;
			}
			return nowSeconds - buySeconds <= maxAgeSeconds && nowSeconds - sellSeconds <= maxAgeSeconds;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Quote))
			{
				return false;
			}
			final Quote other = (Quote) o;
			return Objects.equals(buy, other.buy) && Objects.equals(sell, other.sell)
				&& buySeconds == other.buySeconds && sellSeconds == other.sellSeconds;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(buy, sell, buySeconds, sellSeconds);
		}

		@Override
		public String toString()
		{
			return "Quote{buy=" + buy + "@" + buySeconds + ", sell=" + sell + "@" + sellSeconds + '}';
		}
	}

	/**
	 * One item's whole-day traded bucket from {@code /24h}: the day's average on each side and how many units
	 * changed hands on each. Either price may be absent while its volume stands.
	 *
	 * <p>Immutable and shared, like {@link Quote}.
	 */
	public static final class Bucket
	{
		private final Long avgHigh;
		private final Long avgLow;
		private final long highVolume;
		private final long lowVolume;

		/**
		 * @param avgHigh    the day's average instant-buy price, or null when nobody bought
		 * @param highVolume how many units were bought; never negative
		 * @param avgLow     the day's average instant-sell price, or null when nobody sold
		 * @param lowVolume  how many units were sold; never negative
		 */
		public Bucket(final Long avgHigh, final long highVolume, final Long avgLow, final long lowVolume)
		{
			this.avgHigh = avgHigh;
			this.avgLow = avgLow;
			this.highVolume = Math.max(0L, highVolume);
			this.lowVolume = Math.max(0L, lowVolume);
		}

		/** The day's average instant-buy price, or null. */
		public Long avgHigh()
		{
			return avgHigh;
		}

		/** The day's average instant-sell price, or null. */
		public Long avgLow()
		{
			return avgLow;
		}

		/** How many units were bought that day. */
		public long highVolume()
		{
			return highVolume;
		}

		/** How many units were sold that day. */
		public long lowVolume()
		{
			return lowVolume;
		}

		/** Units traded that day, both sides - T3's volume check and the tooltip's "517 traded yesterday". */
		public long volume()
		{
			return PortfolioMath.clampedAdd(highVolume, lowVolume);
		}

		/**
		 * The day's one price: {@code (avgHigh x hv + avgLow x lv) / (hv + lv)}, rounded half up (T4). Null when
		 * the bucket carries no price at all.
		 *
		 * <p>Three shapes, exactly as T4 words them:
		 * <ul>
		 * <li>one side absent: the side that IS present, whatever the volumes say - an average of one number is
		 * that number;</li>
		 * <li>both sides present with volume: the volume-weighted mean;</li>
		 * <li>both sides present with NO volume on either (a bucket the feed carried prices for and zero counts):
		 * the plain mean, because a weighted mean of zero weights is not a number.</li>
		 * </ul>
		 * The weighted arithmetic is exact in {@code long} and falls back to {@code double} only if a product
		 * overflows, which needs a price and a volume no exchange has ever seen.
		 */
		public Long weightedAverage()
		{
			if (avgHigh == null)
			{
				return avgLow;
			}
			if (avgLow == null)
			{
				return avgHigh;
			}
			final long weight = highVolume + lowVolume;
			if (weight <= 0L)
			{
				return new Quote(avgHigh, 0L, avgLow, 0L).mid();
			}
			try
			{
				final long total = Math.addExact(Math.multiplyExact(avgHigh, highVolume),
					Math.multiplyExact(avgLow, lowVolume));
				// Half up, the same rounding the mid uses; both operands are non-negative here.
				return (total + weight / 2L) / weight;
			}
			catch (final ArithmeticException overflow)
			{
				return Math.round((avgHigh * (double) highVolume + avgLow * (double) lowVolume) / (double) weight);
			}
		}

		/**
		 * The middle of the day's two averages, {@code (avgHigh + avgLow + 1) / 2} - addendum V line V3's gap
		 * denominator. Integer arithmetic rounded HALF UP, the same shape {@link Quote#mid()} gives the live pair,
		 * so the bucket's gap is judged by exactly the rule and the rounding check 2 judges the quote's by.
		 *
		 * <p>Null when either side is absent: a bucket with one side has no middle, only that side - and V3 reads
		 * that null as "no gap to measure", never as a failure.
		 *
		 * <p>The halves are added separately for {@link Quote#mid()}'s reason: a pair near {@link Long#MAX_VALUE}
		 * must not overflow on its way to being refused.
		 */
		public Long bucketMid()
		{
			if (avgHigh == null || avgLow == null)
			{
				return null;
			}
			final long half = avgHigh / 2L + avgLow / 2L;
			final long remainder = (avgHigh % 2L) + (avgLow % 2L);
			return half + (remainder + 1L) / 2L;
		}

		/**
		 * How far the day's two averages sit apart, absolute - addendum V line V3's gap numerator, and the figure
		 * the refusal "buy/sell gap 100 % yesterday" names as a percentage of {@link #bucketMid()}.
		 *
		 * <p>Null when either side is absent, and ABSOLUTE for {@link Quote#spread()}'s reason: nothing guarantees
		 * which side of a day's book averaged higher, and a negative gap would sail through the check.
		 */
		public Long gap()
		{
			return avgHigh == null || avgLow == null ? null : Long.valueOf(Math.abs(avgHigh - avgLow));
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Bucket))
			{
				return false;
			}
			final Bucket other = (Bucket) o;
			return Objects.equals(avgHigh, other.avgHigh) && Objects.equals(avgLow, other.avgLow)
				&& highVolume == other.highVolume && lowVolume == other.lowVolume;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(avgHigh, avgLow, highVolume, lowVolume);
		}

		@Override
		public String toString()
		{
			return "Bucket{avgHigh=" + avgHigh + "x" + highVolume + ", avgLow=" + avgLow + "x" + lowVolume + '}';
		}
	}

	// ---------------------------------------------------------------- internals (the guide client's shapes)

	/**
	 * Builds the request, enqueues it and adapts OkHttp's callback pair onto one future - the same shape as
	 * {@code GuidePriceClient.request}, including the reason the future is completed on every path and BEFORE an
	 * {@link Error} is rethrown: OkHttp 3.14.9 rethrows a Throwable from inside {@code onResponse} on its own
	 * dispatcher thread rather than routing it to {@code onFailure}, so a rethrow first would leave the caller's
	 * in-flight flag claimed for the rest of the session.
	 */
	private <T> CompletableFuture<T> request(final String url, final BodyParser<T> parser)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();

		if (!enabled)
		{
			future.completeExceptionally(new WikiPriceException("traded price fetches are switched off (developer mode)"));
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
			future.completeExceptionally(new WikiPriceException("could not build the traded-price request for " + url, e));
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
					fail(future, url, new WikiPriceException("traded-price request failed: " + messageOf(e), e));
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
							fail(future, url, httpFailure(closing.code()));
							return;
						}
						value = parser.parse(body);
					}
					catch (final IOException e)
					{
						fail(future, url, e instanceof WikiPriceException
							? (WikiPriceException) e
							: new WikiPriceException("could not read the traded-price response: " + messageOf(e), e));
						return;
					}
					catch (final Throwable t)
					{
						failAndRethrow(future, url, new WikiPriceException(
							"could not parse the traded-price response: " + messageOf(t), t), t);
						return;
					}
					future.complete(value);
				}
			});
		}
		catch (final Throwable t)
		{
			failAndRethrow(future, url, new WikiPriceException("could not queue the traded-price request: " + messageOf(t), t), t);
		}

		return future;
	}

	/** The one way a request fails: {@link #report} it, THEN complete the future with it. */
	private <T> void fail(final CompletableFuture<T> future, final String url, final WikiPriceException failure)
	{
		report(url, failure);
		future.completeExceptionally(failure);
	}

	/** {@link #fail} for a {@code catch} on {@link Throwable}: complete first, rethrow an {@link Error} after. */
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
	 * Logs one failure: the FIRST of the session loudly, with the url and any HTTP status, every one after it at
	 * debug - the same rule {@code GuidePriceClient.report} follows, and for the same reason (an outage lasts
	 * hours, the tick is thirty minutes, and a repeated line is noise in someone else's log).
	 */
	private void report(final String url, final WikiPriceException failure)
	{
		final String status = failure.hasHttpCode() ? " (HTTP " + failure.getHttpCode() + ")" : "";
		if (reported)
		{
			log.debug("bank-portfolio-tracker: traded-price request to {} failed{}", url, status, failure);
			return;
		}
		reported = true;
		log.warn("bank-portfolio-tracker: traded-price request to {} failed{} - live prices will fall back to the"
			+ " guide prices until it recovers", url, status, failure);
	}

	/** A future that has already failed - the shape {@link #fetchDay} refuses a bad argument with. */
	private static <T> CompletableFuture<T> failed(final WikiPriceException failure)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(failure);
		return future;
	}

	/** The {@code data} member of either feed, or a failure naming which feed it was. */
	private static JsonObject dataOf(final JsonObject root, final String feed) throws WikiPriceException
	{
		final JsonElement data = root.get("data");
		if (data == null || !data.isJsonObject())
		{
			throw new WikiPriceException("the wiki traded " + feed + " response has no data object");
		}
		return data.getAsJsonObject();
	}

	/** Reads the whole body as text, tolerating a null body (a HEAD-shaped or synthetic response). */
	private static String bodyText(final Response response) throws IOException
	{
		final ResponseBody body = response.body();
		return body == null ? "" : body.string();
	}

	/**
	 * A body as a {@link JsonObject}, or a failure. Parsed as {@link JsonElement} first so a non-object body
	 * becomes a clean failure rather than a ClassCastException, and with the INJECTED Gson - constructing one in
	 * plugin code is a Plugin Hub blocker.
	 */
	private JsonObject readObject(final String json) throws WikiPriceException
	{
		if (json == null || json.trim().isEmpty())
		{
			throw new WikiPriceException("empty response from the wiki traded prices");
		}

		final JsonElement root;
		try
		{
			root = gson.fromJson(json, JsonElement.class);
		}
		catch (final RuntimeException e)
		{
			throw new WikiPriceException("malformed JSON from the wiki traded prices: " + messageOf(e), e);
		}

		if (root == null || root.isJsonNull() || !root.isJsonObject())
		{
			throw new WikiPriceException("the wiki traded-price response was not a JSON object");
		}
		return root.getAsJsonObject();
	}

	/** One JSON value as an object, or null when it is anything else. Never throws. */
	private static JsonObject objectOf(final JsonElement value)
	{
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
	}

	/** A nullable numeric member as a {@code Long}; absent, JSON null or non-numeric all read as null. */
	private static Long optLong(final JsonObject object, final String key)
	{
		final JsonElement value = object.get(key);
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
			// A boolean primitive, or a string that is not a number: skip the member, never fail the fetch.
			return null;
		}
	}

	/** A trade time member in unix seconds; absent or negative reads as 0, which {@link Quote} treats as unknown. */
	private static long seconds(final JsonObject object, final String key)
	{
		return Math.max(0L, orZero(optLong(object, key)));
	}

	private static long orZero(final Long value)
	{
		return value == null ? 0L : value;
	}

	/** An item id key, or null when the text is not an integer (skip the entry, never fail the fetch). */
	private static Integer parseId(final String key)
	{
		if (key == null)
		{
			return null;
		}
		try
		{
			final int id = Integer.parseInt(key.trim());
			return id > 0 ? Integer.valueOf(id) : null;
		}
		catch (final NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * A non-2xx answer. The status is carried on the exception because 403 is diagnosable on its own (the prices
	 * host pre-emptively blocks default user agents) while the body for it is unhelpful.
	 */
	private static WikiPriceException httpFailure(final int code)
	{
		return new WikiPriceException("traded-price request failed with HTTP " + code, code);
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

	/** The one thing that differs between the two requests once the bytes are in hand. */
	@FunctionalInterface
	private interface BodyParser<T>
	{
		T parse(String body) throws WikiPriceException;
	}
}
