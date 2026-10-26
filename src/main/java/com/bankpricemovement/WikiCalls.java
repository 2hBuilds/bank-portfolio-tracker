package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;

/**
 * The HTTP plumbing {@link GuidePriceClient} and {@link TradedPriceClient} share (contract 1.2.2 part 3, A1): build
 * the request, queue it, turn every way it can end into one {@link WikiPriceException}, log the first failure of a
 * session loudly and the rest quietly, and cancel what is still on the wire. The two clients differ only in the
 * noun they put in front of each text ("wiki", "traded-price"), the sentence after the first warning, and whether
 * an HTTP failure reads the wiki's {@code error} member out of the body; each keeps its own {@link Logger}, handed in
 * here, so a line still comes out under the client's own name. {@code WikiClientTextsTest} holds every text.
 *
 * <p><b>Why enqueue, never execute.</b> RuneLite's injected {@link OkHttpClient} throws for a blocking call made on
 * the client thread or the EDT in EVERY environment, so a call is queued and the returned future is completed on
 * OkHttp's dispatcher thread; no caller thread is ever parked. The response is CLOSED before the future completes, so
 * a dependent that starts another request never runs while a body is still open.
 *
 * <p><b>The future is completed on every path, including an {@link Error}</b> (B006). OkHttp 3.14.9 sets
 * {@code signalledCallback} before it invokes {@code onResponse}, so a Throwable thrown from inside the callback is
 * rethrown on the dispatcher thread and never routed to {@code onFailure}: the future would then never complete, and
 * {@code PriceService}'s in-flight flag would stay claimed for the rest of the session - the whole history subsystem
 * stopping with nothing on screen and nothing in the log. A parse of six 4,566-entry tables is exactly the shape that
 * can raise {@code OutOfMemoryError} on a small heap, or {@code StackOverflowError} on a hostile body, so the catch is
 * on {@code Throwable}, the future is completed FIRST, and an {@code Error} is rethrown afterwards for the JVM's own
 * handling. {@link #fail} and {@link #failAndRethrow} are that order, spelled once: every failure path goes through
 * them.
 *
 * <p>The call is remembered for {@link #cancelInFlight()} until it answers. The one flag it keeps beside the list is
 * {@link #enabled}, the developer bridge's kill switch: false fails every request at once without touching the
 * network, so the "no history" and "live prices unavailable" states can be exercised in a live client. Volatile
 * because it is written from the bridge/EDT and read on whichever thread starts a request. Never flipped by
 * user-facing code.
 */
final class WikiCalls
{
	/** Error and diagnostic text is cut to this many characters before it reaches a message or the log. */
	private static final int MAX_DETAIL_CHARS = 200;

	private final Logger log;
	private final OkHttpClient client;
	private final String noun;
	private final String off;
	private final String warnTail;
	private final Function<String, String> httpDetail;

	/**
	 * Every call enqueued and not yet answered, so {@link #cancelInFlight()} can drop them when the plugin stops
	 * (B012). Copy-on-write: at most a handful of entries, written from whichever thread starts a fetch and from
	 * OkHttp's dispatcher as each answers.
	 */
	private final List<Call> inFlight = new CopyOnWriteArrayList<>();

	/** Whether {@link #fail} has already logged a failure loudly this session (B010). */
	private volatile boolean reported;

	/** The developer bridge's switch; true is the shipped state. */
	volatile boolean enabled = true;

	/**
	 * @param log        the owning client's own logger
	 * @param client     RuneLite's injected OkHttpClient; constructing one is a Plugin Hub blocker
	 * @param gson       RuneLite's injected Gson, checked here for the same two clients (they parse with it)
	 * @param noun       what the texts call the feed: {@code wiki} or {@code traded-price}
	 * @param off        the failure text while the developer switch is off
	 * @param warnTail   what follows the session's first warning, after the url and status
	 * @param httpDetail the text a non-2xx body adds to the failure, or null when it adds none
	 */
	WikiCalls(final Logger log, final OkHttpClient client, final Gson gson, final String noun, final String off,
		final String warnTail, final Function<String, String> httpDetail)
	{
		if (client == null)
		{
			throw new IllegalArgumentException("client is required (inject RuneLite's OkHttpClient)");
		}
		if (gson == null)
		{
			throw new IllegalArgumentException("gson is required (inject RuneLite's Gson)");
		}
		this.log = log;
		this.client = client;
		this.noun = noun;
		this.off = off;
		this.warnTail = warnTail;
		this.httpDetail = httpDetail;
	}

	/**
	 * Builds the request, enqueues it and adapts OkHttp's callback pair onto one future. Nothing in here blocks: the
	 * method returns as soon as the call is queued.
	 */
	<T> CompletableFuture<T> request(final String url, final BodyParser<T> parser)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();

		if (!enabled)
		{
			future.completeExceptionally(new WikiPriceException(off));
			return future;
		}

		final Request request;
		try
		{
			request = new Request.Builder()
				.url(url)
				.header(GuidePriceClient.USER_AGENT_HEADER, GuidePriceClient.USER_AGENT)
				.get()
				.build();
		}
		catch (final RuntimeException e)
		{
			// IllegalArgumentException from HttpUrl for a url a client built itself: a programming error, but it must
			// not escape onto the caller's thread (the service's executor) as an unchecked throw.
			future.completeExceptionally(
				new WikiPriceException("could not build the " + noun + " request for " + url, e));
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
					fail(future, url, new WikiPriceException(noun + " request failed: " + messageOf(e), e));
				}

				@Override
				public void onResponse(final Call call, final Response response)
				{
					final T value;
					try (Response closing = response)
					{
						final ResponseBody responseBody = closing.body();
						final String body = responseBody == null ? "" : responseBody.string();
						if (!closing.isSuccessful())
						{
							// The status is carried on the exception because 403 is diagnosable on its own (both wiki
							// hosts pre-emptively block default user agents) while the body for it is unhelpful.
							final String detail = httpDetail.apply(body);
							fail(future, url, new WikiPriceException(noun + " request failed with HTTP "
								+ closing.code() + (detail == null ? "" : ": " + detail), closing.code()));
							return;
						}
						value = parser.parse(body);
					}
					catch (final IOException e)
					{
						fail(future, url, e instanceof WikiPriceException
							? (WikiPriceException) e
							: new WikiPriceException("could not read the " + noun + " response: " + messageOf(e), e));
						return;
					}
					catch (final Throwable t)
					{
						failAndRethrow(future, url, new WikiPriceException(
							"could not parse the " + noun + " response: " + messageOf(t), t), t);
						return;
					}
					future.complete(value);
				}
			});
		}
		catch (final Throwable t)
		{
			// Throwable for the same reason: this runs on the service's executor, and an unanswered future is a flag
			// claimed for the session.
			failAndRethrow(future, url,
				new WikiPriceException("could not queue the " + noun + " request: " + messageOf(t), t), t);
		}

		return future;
	}

	/**
	 * The one way a request fails: log the failure, THEN complete the future with it. The FIRST of the session is a
	 * warning carrying the url and, where the wiki answered with a status, that status; every one after it is debug
	 * (B010).
	 *
	 * <p>Why the first one is loud: RuneLite logs at INFO, so before this every network failure in the plugin - an
	 * outage, a proxy serving HTML, the 403 the prices API returns to a default User-Agent - left NOTHING in the
	 * user's client.log, while every DISK failure in the same plugin was a warning. Why only the first: an outage
	 * lasts hours and the tick is thirty minutes, and a repeated line is noise in someone else's log.
	 */
	private <T> void fail(final CompletableFuture<T> future, final String url, final WikiPriceException failure)
	{
		final String status = failure.hasHttpCode() ? " (HTTP " + failure.getHttpCode() + ")" : "";
		if (reported)
		{
			log.debug("bank-portfolio-tracker: " + noun + " request to {} failed{}", url, status, failure);
		}
		else
		{
			reported = true;
			log.warn("bank-portfolio-tracker: " + noun + " request to {} failed{}" + warnTail, url, status, failure);
		}
		future.completeExceptionally(failure);
	}

	/**
	 * {@link #fail} for a {@code catch} on {@link Throwable}: the future is completed FIRST and an {@link Error} is
	 * rethrown afterwards, for the JVM's own handling.
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
	 * Cancels every call still on the wire. Called from {@code PriceService.stop()} when the plugin is switched off or
	 * its config profile changes: a batched body download is most of a megabyte, and neither finishing it nor parsing
	 * six tables afterwards can produce anything but a discarded result. {@code Call.cancel()} is safe from any
	 * thread; a cancelled call's {@code onFailure} completes its future as an ordinary failure, which the stopped
	 * service drops.
	 */
	void cancelInFlight()
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
				log.debug("bank-portfolio-tracker: a " + noun + " call refused to cancel", e);
			}
		}
	}

	/** A future that has already failed - the shape a fetch refuses a bad argument with. */
	static <T> CompletableFuture<T> failed(final WikiPriceException failure)
	{
		final CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(failure);
		return future;
	}

	/** One JSON value as a {@code Long}, or null when it is absent, null, or not a number. Never throws. */
	static Long asLong(final JsonElement value)
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

	/** {@code Throwable.getMessage()} is null for plenty of IO failures; fall back to the type name. */
	static String messageOf(final Throwable t)
	{
		final String message = t.getMessage();
		return truncate(message == null || message.isEmpty() ? t.getClass().getSimpleName() : message);
	}

	static String truncate(final String text)
	{
		if (text == null)
		{
			return null;
		}
		return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
	}

	/**
	 * The one thing that differs between the requests once the bytes are in hand. A tiny interface rather than a
	 * {@code Function} because the parsers throw a checked {@link WikiPriceException}.
	 */
	@FunctionalInterface
	interface BodyParser<T>
	{
		T parse(String body) throws WikiPriceException;
	}
}
