package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.Okio;
import okio.Source;
import okio.Timeout;
import org.junit.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

/**
 * The words the two wiki clients say when a request goes wrong, pinned as they are (contract 1.2.2 part 3, A1).
 *
 * <p>{@link GuidePriceClient} and {@link TradedPriceClient} each carry the same HTTP plumbing - build the request,
 * queue it, turn every way it can end into one {@link WikiPriceException}, log the first failure of the session
 * loudly and the rest quietly, cancel what is still on the wire - and differ only in the noun they put in front of
 * it ("wiki" and "traded-price"), the sentence that follows the first warning, and whether a MediaWiki
 * {@code error} body adds a detail to an HTTP failure. Those texts reach a player's {@code client.log} and the
 * panel's problem line, so they are fixed here, once, for both clients, with every expected line spelled out whole
 * (never built from a noun, or a refactor that derived them differently would pass its own derivation).
 *
 * <p>Everything goes through the clients' public fetches against a mocked {@link OkHttpClient}, the seam the two
 * client tests use. Not pinned here: the text of a request that cannot be BUILT ("could not build the ... request
 * for ..."), because the clients only ever build URLs from constants and numbers, so no public call reaches it;
 * {@link WikiCallsTest} holds that one at the shared helper.
 */
public class WikiClientTextsTest
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private static final String LOG_LEAD = "bank-portfolio-tracker: ";

	private final List<Request> requests = new ArrayList<>();

	private OkHttpClient http;
	private Call call;

	/** What the mocked call does when it is enqueued. */
	private Script script;

	// ---------------------------------------------------------------- the exception texts

	@Test
	public void theDeveloperSwitchFailsWithTheClientsOwnTextAndStaysSilent() throws Exception
	{
		each(subject ->
		{
			subject.enable.accept(false);
			final List<ILoggingEvent> lines = captured(subject, () ->
			{
				final WikiPriceException failure = failureOf(subject.fetch.get());
				assertEquals(subject.name, subject.off, failure.getMessage());
				assertFalse(subject.name, failure.hasHttpCode());
				assertNull(subject.name, failure.getCause());
			});

			verify(http, never()).newCall(any(Request.class));
			assertEquals(subject.name + ": a switched-off fetch reports no failure", Collections.emptyList(),
				failures(lines));

			subject.enable.accept(true);
			script = callback -> callback.onResponse(call, response(200, ResponseBody.create(JSON, "")));
			subject.fetch.get();
			verify(http, times(1)).newCall(any(Request.class));
		});
	}

	@Test
	public void aTransportFailureNamesTheClientAndCarriesTheCause() throws Exception
	{
		each(subject ->
		{
			final IOException reset = new IOException("connection reset");
			script = callback -> callback.onFailure(call, reset);

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.transport + "connection reset", failure.getMessage());
			assertSame(subject.name, reset, failure.getCause());
			assertFalse(subject.name, failure.hasHttpCode());
		});
	}

	@Test
	public void aTransportFailureWithNoMessageNamesItsType() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onFailure(call, new IOException());

			assertEquals(subject.name, subject.transport + "IOException", failureOf(subject.fetch.get()).getMessage());
		});
	}

	@Test
	public void aLongMessageIsCutAtTwoHundredCharactersWithThreeDots() throws Exception
	{
		final String exactly = repeat('a', 200);
		final String longer = repeat('b', 201);
		each(subject ->
		{
			script = callback -> callback.onFailure(call, new IOException(exactly));
			assertEquals(subject.name + ": 200 characters stay whole", subject.transport + exactly,
				failureOf(subject.fetch.get()).getMessage());

			script = callback -> callback.onFailure(call, new IOException(longer));
			assertEquals(subject.name + ": 201 are cut", subject.transport + repeat('b', 200) + "...",
				failureOf(subject.fetch.get()).getMessage());
		});
	}

	@Test
	public void aBadStatusNamesTheClientAndCarriesTheCode() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(500, ResponseBody.create(JSON, "<html>oops</html>")));

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.httpLead + "500", failure.getMessage());
			assertTrue(subject.name, failure.hasHttpCode());
			assertEquals(subject.name, 500, failure.getHttpCode());
			assertNull(subject.name, failure.getCause());
		});
	}

	@Test
	public void aBadStatusWithAnEmptyBodyIsTheSameSentence() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(403, ResponseBody.create(JSON, "")));

			assertEquals(subject.name, subject.httpLead + "403", failureOf(subject.fetch.get()).getMessage());
		});
	}

	/** The guide client adds the wiki's own {@code error} member to an HTTP failure; the traded client never reads the body. */
	@Test
	public void aWikiErrorBodyOnABadStatusAddsItsDetailOnlyForTheGuideClient() throws Exception
	{
		final String structured = "{\"error\":{\"code\":\"ratelimited\",\"info\":\"slow down\"}}";
		final String plain = "{\"error\":\"nope\"}";
		final String notAnObject = "[\"error\"]";
		final String longDetail = "{\"error\":\"" + repeat('d', 250) + "\"}";
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(429, ResponseBody.create(JSON, structured)));
			assertEquals(subject.name,
				subject.httpLead + "429" + subject.detail("{\"code\":\"ratelimited\",\"info\":\"slow down\"}"),
				failureOf(subject.fetch.get()).getMessage());

			script = callback -> callback.onResponse(call, response(429, ResponseBody.create(JSON, plain)));
			assertEquals(subject.name, subject.httpLead + "429" + subject.detail("nope"),
				failureOf(subject.fetch.get()).getMessage());

			script = callback -> callback.onResponse(call, response(429, ResponseBody.create(JSON, notAnObject)));
			assertEquals(subject.name, subject.httpLead + "429", failureOf(subject.fetch.get()).getMessage());

			script = callback -> callback.onResponse(call, response(502, ResponseBody.create(JSON, longDetail)));
			assertEquals(subject.name, subject.httpLead + "502" + subject.detail(repeat('d', 200) + "..."),
				failureOf(subject.fetch.get()).getMessage());
		});
	}

	@Test
	public void aBodyThatCannotBeReadIsAReadFailure() throws Exception
	{
		each(subject ->
		{
			final IOException closed = new IOException("socket closed");
			script = callback -> callback.onResponse(call, response(200, unreadable(closed)));

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.read + "socket closed", failure.getMessage());
			assertSame(subject.name, closed, failure.getCause());
			assertFalse(subject.name, failure.hasHttpCode());
		});
	}

	@Test
	public void aBodyThatCannotBeReadAndSaysNothingNamesItsType() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(200, unreadable(new IOException())));

			assertEquals(subject.name, subject.read + "IOException", failureOf(subject.fetch.get()).getMessage());
		});
	}

	@Test
	public void aFailureOutsideTheParserIsAParseFailureAndNothingIsRethrown() throws Exception
	{
		each(subject ->
		{
			final IllegalStateException kaput = new IllegalStateException("kaput");
			script = callback -> callback.onResponse(call, response(200, unreadable(kaput)));

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.parse + "kaput", failure.getMessage());
			assertSame(subject.name, kaput, failure.getCause());
			assertFalse(subject.name, failure.hasHttpCode());
		});
	}

	/** The future is answered FIRST and the Error goes on to the thread's own handler after it (B006). */
	@Test
	public void anErrorInsideTheCallbackAnswersTheFutureThenIsRethrown() throws Exception
	{
		each(subject ->
		{
			final OutOfMemoryError heap = new OutOfMemoryError("heap");
			final List<Throwable> escaped = new ArrayList<>();
			script = callback ->
			{
				try
				{
					callback.onResponse(call, response(200, unreadable(heap)));
				}
				catch (final Throwable t)
				{
					escaped.add(t);
				}
			};

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.parse + "heap", failure.getMessage());
			assertEquals(subject.name, Collections.singletonList(heap), escaped);
		});
	}

	/** A parser's own {@link WikiPriceException} is the failure as it is - not wrapped, not re-worded. */
	@Test
	public void theParsersOwnFailureKeepsItsOwnText() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(200, ResponseBody.create(JSON, "")));

			final WikiPriceException failure = failureOf(subject.fetch.get());

			assertEquals(subject.name, subject.emptyBody, failure.getMessage());
			assertNull(subject.name, failure.getCause());
			assertFalse(subject.name, failure.hasHttpCode());
		});
	}

	@Test
	public void aClientThatCannotQueueTheCallSaysSo() throws Exception
	{
		each(subject ->
		{
			doThrow(new IllegalStateException("dispatcher is shut down")).when(http).newCall(any(Request.class));
			assertEquals(subject.name, subject.queue + "dispatcher is shut down",
				failureOf(subject.fetch.get()).getMessage());

			doReturn(call).when(http).newCall(any(Request.class));
			script = callback ->
			{
				throw new IllegalStateException("queue is full");
			};
			assertEquals(subject.name, subject.queue + "queue is full", failureOf(subject.fetch.get()).getMessage());
		});
	}

	// ---------------------------------------------------------------- the log lines

	@Test
	public void theFirstFailureOfASessionWarnsAndEveryLaterOneIsDebug() throws Exception
	{
		each(subject ->
		{
			final IOException first = new IOException("no route");
			final IOException third = new IOException("timed out");
			final List<ILoggingEvent> lines = captured(subject, () ->
			{
				script = callback -> callback.onFailure(call, first);
				failureOf(subject.fetch.get());
				script = callback -> callback.onResponse(call, response(503, ResponseBody.create(JSON, "")));
				failureOf(subject.fetch.get());
				script = callback -> callback.onFailure(call, third);
				failureOf(subject.fetch.get());
			});

			assertEquals(subject.name, Arrays.asList(
				"WARN " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed" + subject.warnTail,
				"DEBUG " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed (HTTP 503)",
				"DEBUG " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed"), failures(lines));
			final List<ILoggingEvent> events = failureEvents(lines);
			assertEquals(subject.name, subject.loggerClass, events.get(0).getLoggerName());
			assertEquals(subject.name + ": the failure rides on the line", subject.transport + "no route",
				events.get(0).getThrowableProxy().getMessage());
			assertEquals(subject.name, subject.httpLead + "503", events.get(1).getThrowableProxy().getMessage());
		});
	}

	@Test
	public void aFirstFailureWithAStatusPutsTheStatusInTheWarning() throws Exception
	{
		each(subject ->
		{
			final List<ILoggingEvent> lines = captured(subject, () ->
			{
				script = callback -> callback.onResponse(call, response(403, ResponseBody.create(JSON, "")));
				failureOf(subject.fetch.get());
			});

			assertEquals(subject.name, Collections.singletonList(
				"WARN " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed (HTTP 403)" + subject.warnTail),
				failures(lines));
		});
	}

	@Test
	public void everyKindOfFailureIsReportedThroughTheSameGate() throws Exception
	{
		each(subject ->
		{
			final List<ILoggingEvent> lines = captured(subject, () ->
			{
				script = callback -> callback.onResponse(call, response(200, unreadable(new IOException("a"))));
				failureOf(subject.fetch.get());
				script = callback -> callback.onResponse(call, response(200, unreadable(new IllegalStateException("b"))));
				failureOf(subject.fetch.get());
				script = callback -> callback.onResponse(call, response(200, ResponseBody.create(JSON, "")));
				failureOf(subject.fetch.get());
				doThrow(new IllegalStateException("c")).when(http).newCall(any(Request.class));
				failureOf(subject.fetch.get());
			});

			assertEquals(subject.name, Arrays.asList(
				"WARN " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed" + subject.warnTail,
				"DEBUG " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed",
				"DEBUG " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed",
				"DEBUG " + LOG_LEAD + subject.noun + " request to " + subject.url + " failed"), failures(lines));
		});
	}

	@Test
	public void aCallThatRefusesToCancelIsLoggedAtDebugAndTheRestAreStillCancelled() throws Exception
	{
		each(subject ->
		{
			final Call stubborn = mock(Call.class);
			final Call willing = mock(Call.class);
			doThrow(new IllegalStateException("already done")).when(stubborn).cancel();
			final List<Call> calls = new ArrayList<>(Arrays.asList(stubborn, willing));
			doAnswer(invocation -> calls.remove(0)).when(http).newCall(any(Request.class));
			subject.fetch.get();
			subject.fetch.get();

			final List<ILoggingEvent> lines = captured(subject, subject.cancel::run);

			verify(stubborn).cancel();
			verify(willing).cancel();
			assertEquals(subject.name, 1, lines.size());
			assertEquals(subject.name, "DEBUG " + LOG_LEAD + "a " + subject.noun + " call refused to cancel",
				lines.get(0).getLevel() + " " + lines.get(0).getFormattedMessage());
			assertEquals(subject.name, "already done", lines.get(0).getThrowableProxy().getMessage());
			assertEquals(subject.name, subject.loggerClass, lines.get(0).getLoggerName());

			subject.cancel.run();
			verify(stubborn, times(1)).cancel();
			verify(willing, times(1)).cancel();
		});
	}

	// ---------------------------------------------------------------- the request itself

	@Test
	public void theCallCarriesTheDescriptiveUserAgentOnAPlainGet() throws Exception
	{
		each(subject ->
		{
			script = callback -> callback.onResponse(call, response(200, ResponseBody.create(JSON, "")));

			subject.fetch.get();

			assertEquals(subject.name, 1, requests.size());
			final Request request = requests.get(0);
			assertEquals(subject.name, subject.url, request.url().toString());
			assertEquals(subject.name, "GET", request.method());
			assertEquals(subject.name, "bank-portfolio-tracker (RuneLite plugin; github.com/2hBuilds)",
				request.header("User-Agent"));
			assertEquals(subject.name, 1, request.headers().size());
		});
	}

	@Test
	public void theCallIsQueuedAfterItIsBuiltAndStaysCancellableOnlyUntilItAnswers() throws Exception
	{
		each(subject ->
		{
			script = callback ->
			{
				// Not answered: still on the wire.
			};
			final CompletableFuture<?> pending = subject.fetch.get();
			assertFalse(subject.name, pending.isDone());

			final InOrder order = inOrder(http, call);
			order.verify(http).newCall(any(Request.class));
			order.verify(call).enqueue(any(Callback.class));

			subject.cancel.run();
			verify(call).cancel();
			subject.cancel.run();
			verify(call, times(1)).cancel();

			script = callback -> callback.onResponse(call, response(200, ResponseBody.create(JSON, "")));
			subject.fetch.get();
			subject.cancel.run();
			verify(call, times(1)).cancel();
		});
	}

	// ---------------------------------------------------------------- the two subjects

	private List<Subject> subjects()
	{
		final GuidePriceClient guide = new GuidePriceClient(http, new Gson());
		final TradedPriceClient traded = new TradedPriceClient(http, new Gson());
		return Arrays.asList(
			new Subject("guide", GuidePriceClient.class.getName(), "wiki", GuidePriceClient.MAPPING_URL,
				() -> guide.fetchMapping(1L), guide::setEnabled, guide::cancelInFlight,
				"wiki fetches are switched off (developer mode)",
				"wiki request failed: ",
				"wiki request failed with HTTP ",
				true,
				"empty response from the wiki",
				"could not read the wiki response: ",
				"could not parse the wiki response: ",
				"could not queue the wiki request: ",
				" - movement will show stored data until it recovers"),
			new Subject("traded", TradedPriceClient.class.getName(), "traded-price",
				"https://prices.runescape.wiki/api/v1/osrs/latest",
				() -> traded.fetchLatest(1L), traded::setEnabled, traded::cancelInFlight,
				"traded price fetches are switched off (developer mode)",
				"traded-price request failed: ",
				"traded-price request failed with HTTP ",
				false,
				"empty response from the wiki traded prices",
				"could not read the traded-price response: ",
				"could not parse the traded-price response: ",
				"could not queue the traded-price request: ",
				" - live prices will fall back to the guide prices until it recovers"));
	}

	/** One client under test, and every text it must keep. */
	private static final class Subject
	{
		final String name;
		final String loggerClass;
		/** The noun in the log lines. */
		final String noun;
		/** The one URL the fetch below asks for. */
		final String url;
		final Supplier<CompletableFuture<?>> fetch;
		final Consumer<Boolean> enable;
		final Runnable cancel;
		final String off;
		final String transport;
		final String httpLead;
		final boolean readsErrorDetail;
		final String emptyBody;
		final String read;
		final String parse;
		final String queue;
		final String warnTail;

		Subject(final String name, final String loggerClass, final String noun, final String url,
			final Supplier<CompletableFuture<?>> fetch, final Consumer<Boolean> enable, final Runnable cancel,
			final String off, final String transport, final String httpLead, final boolean readsErrorDetail,
			final String emptyBody, final String read, final String parse, final String queue, final String warnTail)
		{
			this.name = name;
			this.loggerClass = loggerClass;
			this.noun = noun;
			this.url = url;
			this.fetch = fetch;
			this.enable = enable;
			this.cancel = cancel;
			this.off = off;
			this.transport = transport;
			this.httpLead = httpLead;
			this.readsErrorDetail = readsErrorDetail;
			this.emptyBody = emptyBody;
			this.read = read;
			this.parse = parse;
			this.queue = queue;
			this.warnTail = warnTail;
		}

		/** What follows the status in an HTTP failure when the body carried this error text: the guide client's, only. */
		String detail(final String errorText)
		{
			return readsErrorDetail ? ": " + errorText : "";
		}
	}

	// ---------------------------------------------------------------- helpers

	/** What the mocked call does when the client queues it. */
	@FunctionalInterface
	private interface Script
	{
		void run(Callback callback) throws IOException;
	}

	/** A fresh mocked client and call, so one subject's calls are never counted against the next. */
	private void wire() throws Exception
	{
		requests.clear();
		http = mock(OkHttpClient.class);
		call = mock(Call.class);
		doAnswer(invocation ->
		{
			requests.add(invocation.getArgument(0));
			return call;
		}).when(http).newCall(any(Request.class));
		doAnswer(invocation ->
		{
			script.run(invocation.getArgument(0));
			return null;
		}).when(call).enqueue(any(Callback.class));
	}

	/** Runs {@code body} once for each client, each time against a freshly wired mock. */
	private void each(final Consumer<Subject> body) throws Exception
	{
		for (int i = 0; i < 2; i++)
		{
			wire();
			body.accept(subjects().get(i));
		}
	}

	private Response response(final int code, final ResponseBody body)
	{
		return new Response.Builder()
			.request(new Request.Builder().url("https://prices.runescape.wiki/api/v1/osrs/mapping").build())
			.protocol(Protocol.HTTP_1_1)
			.code(code)
			.message(code == 200 ? "OK" : "Error")
			.body(body)
			.build();
	}

	/** A 200 body whose bytes cannot be read: the source raises {@code failure} the moment anyone asks for them. */
	private static ResponseBody unreadable(final Throwable failure)
	{
		final Source source = new Source()
		{
			@Override
			public long read(final Buffer sink, final long byteCount) throws IOException
			{
				if (failure instanceof IOException)
				{
					throw (IOException) failure;
				}
				if (failure instanceof Error)
				{
					throw (Error) failure;
				}
				throw (RuntimeException) failure;
			}

			@Override
			public Timeout timeout()
			{
				return Timeout.NONE;
			}

			@Override
			public void close()
			{
				// Nothing is open, and nothing may be thrown from here.
			}
		};
		return ResponseBody.create(JSON, -1L, Okio.buffer(source));
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

	private static String repeat(final char c, final int times)
	{
		final char[] chars = new char[times];
		Arrays.fill(chars, c);
		return new String(chars);
	}

	/** Every line the subject's own logger writes on this thread while {@code action} runs, DEBUG and up. */
	private static List<ILoggingEvent> captured(final Subject subject, final Runnable action)
	{
		final org.slf4j.Logger slf4j = LoggerFactory.getLogger(subject.loggerClass);
		assertTrue("logback must be the slf4j binding here: " + slf4j,
			slf4j instanceof ch.qos.logback.classic.Logger);
		final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) slf4j;
		final String thread = Thread.currentThread().getName();
		final List<ILoggingEvent> lines = Collections.synchronizedList(new ArrayList<>());
		final AppenderBase<ILoggingEvent> capture = new AppenderBase<ILoggingEvent>()
		{
			@Override
			protected void append(final ILoggingEvent event)
			{
				if (thread.equals(event.getThreadName()))
				{
					lines.add(event);
				}
			}
		};
		capture.setName("wiki-client-texts-test");
		capture.start();
		final Level before = logger.getLevel();
		final boolean additive = logger.isAdditive();
		logger.setLevel(Level.DEBUG);
		logger.setAdditive(false);
		logger.addAppender(capture);
		try
		{
			action.run();
		}
		finally
		{
			logger.detachAppender(capture);
			capture.stop();
			logger.setAdditive(additive);
			logger.setLevel(before);
		}
		synchronized (lines)
		{
			return new ArrayList<>(lines);
		}
	}

	/** The lines that are about a failed request: the others (what is being fetched, a cancel) are not pinned here. */
	private static List<ILoggingEvent> failureEvents(final List<ILoggingEvent> lines)
	{
		final List<ILoggingEvent> out = new ArrayList<>();
		for (final ILoggingEvent line : lines)
		{
			if (line.getFormattedMessage().contains(" request to ") && line.getFormattedMessage().contains(" failed"))
			{
				out.add(line);
			}
		}
		return out;
	}

	/** {@code LEVEL message} of each failure line. */
	private static List<String> failures(final List<ILoggingEvent> lines)
	{
		final List<String> out = new ArrayList<>();
		for (final ILoggingEvent line : failureEvents(lines))
		{
			out.add(line.getLevel() + " " + line.getFormattedMessage());
		}
		return out;
	}
}
