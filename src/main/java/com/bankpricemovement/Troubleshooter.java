package com.bankpricemovement;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.runelite.client.util.Filepath;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * What the settings menu's <i>Troubleshoot...</i> does when it is pressed (1.0.8): five small checks, and the
 * plain-words verdict that the report and the window put first.
 *
 * <p><b>The checks, and only when the button is pressed.</b> Nothing here ever runs by itself.
 * <ol>
 * <li>{@value #CHECK_VERSION} - a GET of the Plugin Hub's manifest for this client, to see whether a newer version
 * of this plugin is listed there;</li>
 * <li>{@value #CHECK_MAPPING} - a HEAD to the wiki's item table;</li>
 * <li>{@value #CHECK_INDEX} - the smallest GET the revision-index client can make (one revision);</li>
 * <li>{@value #CHECK_LIVE} - a HEAD to the traded {@code /latest};</li>
 * <li>{@value #CHECK_FOLDER} - not the network: one byte written to the plugin's data folder and removed again,
 * through {@link Filepath} like every other file this plugin touches.</li>
 * </ol>
 * All five start together. Each has its own timeout ({@value #CHECK_TIMEOUT_MS} ms), and the whole run answers
 * within {@value #TOTAL_TIMEOUT_MS} ms whatever the network does, because a window that says "Checking..." for ever
 * is worse than one that says a check got no answer.
 *
 * <p><b>Threads.</b> The four network checks go out through OkHttp's {@code enqueue} - the dispatcher's threads do
 * the waiting, and the version check's body is read and parsed there too, so the thread that starts them (the EDT, in
 * production) is never parked - and are NOT queued behind the client's one shared executor: when that executor is
 * what is stuck, a check queued on it would never start and would be reported as a network failure, which is the
 * opposite of what the report is for. The folder check does run on that executor, since it is disk work, and a stuck
 * executor then shows as that one check getting no answer. The finished report is handed back through the {@code edt}
 * hook, never built or shown on any other thread, and a batch of futures is joined with
 * {@link CompletableFuture#allOf} - never a latch, which the Hub's rules forbid.
 *
 * <p>The wiki asks every client for a User-Agent; the checks send this plugin's own, the one its real fetches send.
 */
public final class Troubleshooter
{
	/** One check's own timeout. */
	public static final long CHECK_TIMEOUT_MS = 8_000L;
	/** The whole run's ceiling. */
	public static final long TOTAL_TIMEOUT_MS = 10_000L;

	public static final String CHECK_VERSION = "plugin version";
	public static final String CHECK_MAPPING = "wiki mapping";
	public static final String CHECK_INDEX = "wiki price index";
	public static final String CHECK_LIVE = "live prices";
	public static final String CHECK_FOLDER = "data folder";

	/** The file the data-folder check writes one byte to and removes again. */
	static final String PROBE_FILE = "troubleshoot-probe.tmp";

	/**
	 * The Plugin Hub's manifest for one client version: a four-byte length, that many bytes of signature, then the
	 * JSON the client lists plugins from. The hub version it is asked for is RuneLite's own,
	 * {@code RuneLiteProperties.getPluginHubVersion()}.
	 */
	static final String HUB_MANIFEST_URL = "https://repo.runelite.net/plugins/manifest/";
	/** The version check's detail when RuneLite did not name its Hub version, so there is nothing to ask. */
	static final String NO_HUB_VERSION = "RuneLite did not name its Hub version";

	/**
	 * The verdict for each rule of the table, in the order the rules are tried; each says what to try, and the window
	 * puts {@link TroubleshootDialog#NEXT_TEXT} under it. Rule 0: the game thread never answered inside the ceiling,
	 * so nothing below it can be trusted.
	 */
	public static final String VERDICT_NO_CLIENT = "The game client did not answer in time. Restart RuneLite.";
	/** Rule 1, a format: this build's version, then the Hub's. */
	public static final String VERDICT_OUT_OF_DATE = "You have %s, the Hub has %s. Restart RuneLite to update.";
	public static final String VERDICT_NOT_LOGGED_IN = "You are not logged in. Log in and open your bank once.";
	public static final String VERDICT_NO_ACCOUNT = "RuneLite has not named your account yet. Log out and log in again.";
	public static final String VERDICT_NO_BANK_SEEN =
		"The plugin has not seen your bank yet this session. Open your bank once, then close it.";
	public static final String VERDICT_READ_FAILED = "Reading your bank failed on one item. Open your bank again.";
	public static final String VERDICT_STUCK =
		"The plugin's background work is stuck, probably a network call. Restart RuneLite.";
	public static final String VERDICT_PRICING_FAILED = "Pricing your bank failed. Press Refresh, then open your bank again.";
	public static final String VERDICT_UNREACHABLE =
		"The price site cannot be reached from this computer. Check your connection or firewall, then press Refresh.";
	public static final String VERDICT_FOLDER = "The plugin cannot write its data folder. Restart RuneLite.";
	public static final String VERDICT_SOMETHING_NEW = "Something unexpected happened. Restart RuneLite.";
	public static final String VERDICT_FINE = "Everything looks fine here. Open your bank once, then press Refresh.";
	/** What the window says when the run itself broke; its second line covers the sending. */
	static final String VERDICT_UNFINISHED = "Troubleshoot could not finish.";

	private final OkHttpClient http;
	private final ScheduledExecutorService executor;
	private final Supplier<Filepath> directory;
	private final Diagnostics diagnostics;
	private final Consumer<Runnable> edt;
	private final Gson gson;
	@Nullable
	private final String hubVersion;
	private final long checkTimeoutMs;
	private final long totalTimeoutMs;

	/**
	 * @param http      RuneLite's injected OkHttp client - the one the real fetches use
	 * @param executor  RuneLite's injected executor; the folder check runs on it
	 * @param directory the plugin's data folder, or null when there is none ({@code PriceStore::dir}); asked for on
	 *                  the executor, never on the thread that presses the button
	 * @param diagnostics the plugin's memory: the wiki's {@code Date} goes to its fetch log, and the report is built
	 *                  from it
	 * @param edt       {@code SwingUtilities::invokeLater} in the plugin
	 * @param gson      RuneLite's injected Gson, which reads the Hub's manifest
	 * @param hubVersion the version RuneLite asks the Hub for ({@code RuneLiteProperties.getPluginHubVersion()}), or
	 *                  null when it did not say - the version check then makes no request and says so
	 */
	public Troubleshooter(final OkHttpClient http, final ScheduledExecutorService executor,
		final Supplier<Filepath> directory, final Diagnostics diagnostics, final Consumer<Runnable> edt,
		final Gson gson, @Nullable final String hubVersion)
	{
		this(http, executor, directory, diagnostics, edt, gson, hubVersion, CHECK_TIMEOUT_MS, TOTAL_TIMEOUT_MS);
	}

	/** The same with the two timeouts named, which is how a test runs a hung check in milliseconds. */
	Troubleshooter(final OkHttpClient http, final ScheduledExecutorService executor,
		final Supplier<Filepath> directory, final Diagnostics diagnostics, final Consumer<Runnable> edt,
		final Gson gson, @Nullable final String hubVersion, final long checkTimeoutMs, final long totalTimeoutMs)
	{
		this.http = http;
		this.executor = executor;
		this.directory = directory;
		this.diagnostics = diagnostics;
		this.edt = edt;
		this.gson = gson;
		this.hubVersion = hubVersion;
		this.checkTimeoutMs = checkTimeoutMs;
		this.totalTimeoutMs = totalTimeoutMs;
	}

	/**
	 * Everything one press does after the window is open: starts the checks, waits for them and for the part of the
	 * facts only the client thread can read, builds the report and hands it to {@code done} on the EDT. Returns at
	 * once.
	 *
	 * @param facts      the builder the caller has filled (and is still filling, on the client thread)
	 * @param playerDone completes when the client-thread part of {@code facts} is in; the run waits for it only as long
	 *                   as its ceiling, so a client thread that never gets to it cannot hold the window at
	 *                   "Checking..." - the report then says the client thread did not answer
	 * @param done       receives the verdict and the report, on the EDT
	 */
	public void run(final Diagnostics.Facts.Builder facts, final CompletableFuture<Void> playerDone,
		final Consumer<TroubleshootDialog.Contents> done)
	{
		final CompletableFuture<List<CheckResult>> checks = runChecks();
		final CompletableFuture<Boolean> player = playerDone.thenApply(nothing -> Boolean.TRUE)
			.completeOnTimeout(Boolean.FALSE, totalTimeoutMs, TimeUnit.MILLISECONDS);
		CompletableFuture.allOf(player, checks).whenComplete((nothing, error) ->
		{
			TroubleshootDialog.Contents contents;
			try
			{
				if (!Boolean.TRUE.equals(player.getNow(Boolean.FALSE)))
				{
					// The client thread never got to its part, so "logged in" and the rest are unknown, not no.
					facts.clientAnswered(false);
					facts.line(Diagnostics.PLAYER, "client thread", "no answer within " + span(totalTimeoutMs));
				}
				final List<CheckResult> results = checks.isDone() && !checks.isCompletedExceptionally()
					? checks.getNow(Collections.emptyList()) : Collections.emptyList();
				facts.lastErrorWhat(diagnostics.lastErrorWhat());
				facts.runningForSeconds(diagnostics.watchdog().snapshot().runningForSeconds);
				contents = TroubleshootDialog.contents(diagnostics, facts.build(), results);
			}
			catch (final RuntimeException e)
			{
				diagnostics.error("troubleshoot failed", e);
				contents = new TroubleshootDialog.Contents(VERDICT_UNFINISHED,
					"Troubleshoot failed: " + e.getClass().getName() + "\n");
			}
			final TroubleshootDialog.Contents ready = contents;
			edt.accept(() -> done.accept(ready));
		});
	}

	/**
	 * Starts the five checks and answers with their results, in the order above, inside the run's ceiling. A check
	 * that has not answered by then is a failed check that says so; the future never completes exceptionally.
	 */
	public CompletableFuture<List<CheckResult>> runChecks()
	{
		final List<String> names = new ArrayList<>();
		final List<CompletableFuture<CheckResult>> checks = new ArrayList<>();
		names.add(CHECK_VERSION);
		checks.add(version());
		names.add(CHECK_MAPPING);
		checks.add(network(CHECK_MAPPING, GuidePriceClient.MAPPING_URL, true));
		names.add(CHECK_INDEX);
		checks.add(network(CHECK_INDEX, GuidePriceClient.revisionIndexUrl(1), false));
		names.add(CHECK_LIVE);
		checks.add(network(CHECK_LIVE, TradedPriceClient.latestUrl(), true));
		names.add(CHECK_FOLDER);
		checks.add(folder());
		return CompletableFuture.allOf(checks.toArray(new CompletableFuture<?>[0]))
			.completeOnTimeout(null, totalTimeoutMs, TimeUnit.MILLISECONDS)
			.thenApply(nothing ->
			{
				final List<CheckResult> results = new ArrayList<>();
				for (int i = 0; i < checks.size(); i++)
				{
					final CompletableFuture<CheckResult> check = checks.get(i);
					results.add(check.isDone() && !check.isCompletedExceptionally() ? check.getNow(null)
						: timedOut(names.get(i), totalTimeoutMs));
				}
				return results;
			});
	}

	/** What a check does with the answer it got - read the body, if it reads one, and judge. Runs on OkHttp's thread. */
	private interface Reader
	{
		CheckResult read(Response response, long startedNanos) throws IOException;
	}

	/** A wiki check: the server answered, or did not. The wiki's {@code Date} goes to the fetch log. */
	private CompletableFuture<CheckResult> network(final String name, final String url, final boolean head)
	{
		return enqueue(name, url, head, (response, started) ->
		{
			diagnostics.fetchLog().serverDate(response.header("Date"));
			final int code = response.code();
			return new CheckResult(name, response.isSuccessful(), code, elapsedMillis(started),
				response.isSuccessful() ? "reachable" : "the server answered HTTP " + code);
		});
	}

	/**
	 * The version check (part 15 of the 1.0.8 contract): the Hub's manifest for this client, read whole on OkHttp's
	 * thread and searched for this plugin's line. RuneLite not naming its Hub version means no request at all.
	 */
	private CompletableFuture<CheckResult> version()
	{
		final String hub = hubVersion;
		if (hub == null || hub.isEmpty())
		{
			return CompletableFuture.completedFuture(new CheckResult(CHECK_VERSION, false, 0, 0L, NO_HUB_VERSION));
		}
		return enqueue(CHECK_VERSION, hubManifestUrl(hub), false, (response, started) ->
		{
			final int code = response.code();
			if (!response.isSuccessful())
			{
				return new CheckResult(CHECK_VERSION, false, code, elapsedMillis(started),
					"the server answered HTTP " + code);
			}
			final ResponseBody body = response.body();
			if (body == null)
			{
				throw new IOException("the manifest had no body");
			}
			final Entry entry = findEntry(gson, body.bytes());
			return versionResult(Version.CURRENT, hub, entry, code, elapsedMillis(started));
		});
	}

	/**
	 * One request through OkHttp's {@code enqueue}: HEAD for a table that is large, GET for the smallest index call
	 * and the manifest. {@code reader} judges the answer on OkHttp's thread; whatever it throws is a failed check that
	 * says what, never an exception out of the callback.
	 */
	private CompletableFuture<CheckResult> enqueue(final String name, final String url, final boolean head,
		final Reader reader)
	{
		final CompletableFuture<CheckResult> out = new CompletableFuture<>();
		final long started = System.nanoTime();
		Call launched = null;
		try
		{
			final Request.Builder builder = new Request.Builder().url(url)
				.header(GuidePriceClient.USER_AGENT_HEADER, GuidePriceClient.USER_AGENT);
			launched = http.newCall((head ? builder.head() : builder.get()).build());
			launched.enqueue(new Callback()
			{
				@Override
				public void onFailure(final Call call, final IOException e)
				{
					out.complete(new CheckResult(name, false, 0, elapsedMillis(started), describe(e)));
				}

				@Override
				public void onResponse(final Call call, final Response response)
				{
					CheckResult result;
					try (Response closing = response)
					{
						result = reader.read(closing, started);
					}
					catch (final IOException | RuntimeException e)
					{
						result = new CheckResult(name, false, response.code(), elapsedMillis(started), describe(e));
					}
					out.complete(result);
				}
			});
		}
		catch (final RuntimeException e)
		{
			out.complete(new CheckResult(name, false, 0, elapsedMillis(started), describe(e)));
		}
		final Call call = launched;
		out.completeOnTimeout(timedOut(name, checkTimeoutMs), checkTimeoutMs, TimeUnit.MILLISECONDS);
		// A call that timed out is cancelled so it frees its connection; cancelling one that finished is a no-op.
		return out.whenComplete((result, error) ->
		{
			if (call != null)
			{
				call.cancel();
			}
		});
	}

	/** The data-folder check, queued on the executor: disk work never runs on the thread that pressed the button. */
	private CompletableFuture<CheckResult> folder()
	{
		final CompletableFuture<CheckResult> out = new CompletableFuture<>();
		final long started = System.nanoTime();
		try
		{
			executor.submit(() ->
			{
				out.complete(probeFolder(started));
			});
		}
		catch (final RuntimeException e)
		{
			out.complete(new CheckResult(CHECK_FOLDER, false, 0, elapsedMillis(started),
				"could not be queued: " + describe(e)));
		}
		return out.completeOnTimeout(timedOut(CHECK_FOLDER, checkTimeoutMs), checkTimeoutMs, TimeUnit.MILLISECONDS);
	}

	/**
	 * One byte into {@value #PROBE_FILE} in the plugin's folder, then deleted. The folder is created first, exactly as
	 * the first real save would: a plugin that has never saved anything has no folder yet, and a probe that failed on
	 * that would call a healthy install broken.
	 */
	private CheckResult probeFolder(final long started)
	{
		try
		{
			final Filepath dir = directory.get();
			if (dir == null)
			{
				return new CheckResult(CHECK_FOLDER, false, 0, elapsedMillis(started),
					"the plugin has no data folder");
			}
			if (!dir.isDirectory())
			{
				dir.createDirectories();
			}
			final Filepath probe = dir.joinSegment(PROBE_FILE);
			probe.write(new byte[]{'x'}, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE);
			probe.delete();
			return new CheckResult(CHECK_FOLDER, true, 0, elapsedMillis(started), "wrote one byte and removed it");
		}
		catch (final IOException | RuntimeException e)
		{
			return new CheckResult(CHECK_FOLDER, false, 0, elapsedMillis(started), describe(e));
		}
	}

	/**
	 * The one plain sentence for these facts and check results: the first row of the table that matches, in this
	 * order. The order is the point - a player who is not logged in must not be told about the network, and a player
	 * on an old version is told to update before anything else that might be that version's own bug.
	 */
	public static String verdict(final Diagnostics.Facts facts, final List<CheckResult> checks)
	{
		if (!facts.clientAnswered)
		{
			return VERDICT_NO_CLIENT;
		}
		final CheckResult version = find(checks, CHECK_VERSION);
		if (version != null && version.update != null)
		{
			return String.format(VERDICT_OUT_OF_DATE, Version.CURRENT, version.update);
		}
		if (!facts.loggedIn)
		{
			return VERDICT_NOT_LOGGED_IN;
		}
		if (!facts.accountKnown || (facts.eventsDropped > 0 && facts.bankReads == 0))
		{
			return VERDICT_NO_ACCOUNT;
		}
		if (facts.eventsSeen == 0)
		{
			return VERDICT_NO_BANK_SEEN;
		}
		final String what = facts.lastErrorWhat;
		if (Diagnostics.BANK_READ_FAILED.equals(what))
		{
			return VERDICT_READ_FAILED;
		}
		if (facts.runningForSeconds > Watchdog.STUCK_SECONDS)
		{
			return VERDICT_STUCK;
		}
		if (Diagnostics.COMPUTATION_FAILED.equals(what) || Diagnostics.HISTORY_FAILED.equals(what)
			|| Diagnostics.HISTORY_CELLS_FAILED.equals(what))
		{
			return VERDICT_PRICING_FAILED;
		}
		if (allNetworkChecksFailed(checks))
		{
			return VERDICT_UNREACHABLE;
		}
		final CheckResult folder = find(checks, CHECK_FOLDER);
		if (folder != null && !folder.ok)
		{
			return VERDICT_FOLDER;
		}
		if (facts.bankReads > 0 && BankPriceMovementPanel.CARD_NO_BANK.equals(facts.card))
		{
			return VERDICT_SOMETHING_NEW;
		}
		return VERDICT_FINE;
	}

	/**
	 * Whether there was a wiki check and every one of them failed. The folder is not the network, and neither is the
	 * Hub: rule 8 speaks about the price site, and a Hub outage is not that.
	 */
	private static boolean allNetworkChecksFailed(final List<CheckResult> checks)
	{
		boolean any = false;
		for (final CheckResult check : checks)
		{
			if (!CHECK_MAPPING.equals(check.name) && !CHECK_INDEX.equals(check.name) && !CHECK_LIVE.equals(check.name))
			{
				continue;
			}
			any = true;
			if (check.ok)
			{
				return false;
			}
		}
		return any;
	}

	/** The Hub's manifest for the client version RuneLite names: {@code manifest/<version>_full.js}. */
	static String hubManifestUrl(final String hubVersion)
	{
		return HUB_MANIFEST_URL + hubVersion + "_full.js";
	}

	/** The part of the Hub's manifest this check reads: its display list. Filled by Gson; every field may be absent. */
	static final class Manifest
	{
		@Nullable
		List<Entry> display;
	}

	/** One plugin's line of the display list; only the fields the check reads, each of which the Hub may leave out. */
	static final class Entry
	{
		@Nullable
		String internalName;
		@Nullable
		String version;
		/** Present when the Hub lists the plugin but has no build of it for this client. */
		@Nullable
		String unavailableReason;
	}

	/**
	 * This plugin's line of the manifest in {@code body}: a four-byte big-endian length, that many bytes of signature
	 * (skipped - this is a display, not a load, so it is not verified), then UTF-8 JSON. Null when the manifest does
	 * not list the plugin. A body too short to hold what it says, JSON that does not parse and a manifest with no
	 * display list all throw, and the check reports the exception.
	 */
	@Nullable
	static Entry findEntry(final Gson gson, final byte[] body) throws IOException
	{
		if (body.length < 4)
		{
			throw new IOException("the manifest is shorter than its length prefix");
		}
		final int signature = ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16) | ((body[2] & 0xFF) << 8)
			| (body[3] & 0xFF);
		if (signature < 0 || signature > body.length - 4)
		{
			throw new IOException("the manifest is shorter than its signature says");
		}
		final int from = 4 + signature;
		final Manifest manifest = gson.fromJson(new String(body, from, body.length - from, StandardCharsets.UTF_8),
			Manifest.class);
		if (manifest == null || manifest.display == null)
		{
			throw new IOException("the manifest has no display list");
		}
		for (final Entry entry : manifest.display)
		{
			if (entry != null && PriceStore.DIR_NAME.equals(entry.internalName))
			{
				return entry;
			}
		}
		return null;
	}

	/**
	 * What the version check answers for this manifest line, with {@code ours} as this build's version: a newer Hub
	 * version passes and is named in {@link CheckResult#update}, which the verdict turns into its first rule; equal,
	 * ours newer and two versions that cannot be compared pass with nothing to say; a plugin listed without a build,
	 * or not listed at all, fails.
	 */
	static CheckResult versionResult(final String ours, final String hubVersion, @Nullable final Entry entry,
		final int status, final long millis)
	{
		if (entry == null)
		{
			return new CheckResult(CHECK_VERSION, false, status, millis, "not listed for Hub version " + hubVersion);
		}
		if (entry.unavailableReason != null)
		{
			return new CheckResult(CHECK_VERSION, false, status, millis,
				"listed without a build for this client: " + Diagnostics.scrub(entry.unavailableReason));
		}
		final String theirs = entry.version;
		if (theirs == null)
		{
			return new CheckResult(CHECK_VERSION, false, status, millis, "the Hub lists no version for it");
		}
		final Integer order = compareVersions(theirs, ours);
		if (order == null)
		{
			return new CheckResult(CHECK_VERSION, true, status, millis,
				"the Hub has " + Diagnostics.scrub(theirs) + ", this client runs " + ours);
		}
		if (order > 0)
		{
			return new CheckResult(CHECK_VERSION, true, status, millis,
				"the Hub has " + theirs + ", this client runs " + ours, theirs);
		}
		if (order == 0)
		{
			return new CheckResult(CHECK_VERSION, true, status, millis, "up to date (" + ours + ")");
		}
		return new CheckResult(CHECK_VERSION, true, status, millis,
			"this build (" + ours + ") is newer than the Hub's (" + theirs + ")");
	}

	/**
	 * Two dotted versions as integers part by part, a missing part being 0: negative, zero or positive as {@code a} is
	 * older than, the same as or newer than {@code b} ({@code 1.0.10} is newer than {@code 1.0.9}, {@code 1.0} is the
	 * same as {@code 1.0.0}). Null when a part of either is not a plain integer, so the two cannot be compared.
	 */
	@Nullable
	static Integer compareVersions(final String a, final String b)
	{
		final long[] left = parts(a);
		final long[] right = parts(b);
		if (left == null || right == null)
		{
			return null;
		}
		for (int i = 0; i < Math.max(left.length, right.length); i++)
		{
			final int order = Long.compare(i < left.length ? left[i] : 0L, i < right.length ? right[i] : 0L);
			if (order != 0)
			{
				return order;
			}
		}
		return 0;
	}

	/** A dotted version as its numbers; null when any part is empty or has anything but digits in it. */
	@Nullable
	private static long[] parts(final String version)
	{
		final String[] texts = version.split("\\.", -1);
		final long[] numbers = new long[texts.length];
		for (int i = 0; i < texts.length; i++)
		{
			final Long number = part(texts[i]);
			if (number == null)
			{
				return null;
			}
			numbers[i] = number;
		}
		return numbers;
	}

	/** One part of a dotted version as a number; null when it is empty or has anything but digits in it. */
	@Nullable
	private static Long part(final String text)
	{
		if (text.isEmpty() || text.length() > 15)
		{
			return null;
		}
		for (int i = 0; i < text.length(); i++)
		{
			if (text.charAt(i) < '0' || text.charAt(i) > '9')
			{
				return null;
			}
		}
		return Long.valueOf(text);
	}

	@Nullable
	private static CheckResult find(final List<CheckResult> checks, final String name)
	{
		for (final CheckResult check : checks)
		{
			if (name.equals(check.name))
			{
				return check;
			}
		}
		return null;
	}

	private static CheckResult timedOut(final String name, final long afterMillis)
	{
		return new CheckResult(name, false, 0, afterMillis, "no answer within " + span(afterMillis));
	}

	/** A wait in words: whole seconds from one second up, milliseconds below. */
	private static String span(final long millis)
	{
		return millis >= 1000L ? millis / 1000L + " s" : millis + " ms";
	}

	private static long elapsedMillis(final long startedNanos)
	{
		return (System.nanoTime() - startedNanos) / 1_000_000L;
	}

	/** An exception as one scrubbed phrase: its class, and its message when it has one (a disk message is a path). */
	private static String describe(final Throwable e)
	{
		final String message = e.getMessage();
		return Diagnostics.scrub(e.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message));
	}
}
