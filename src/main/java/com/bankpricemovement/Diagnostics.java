package com.bankpricemovement;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import net.runelite.client.RuneLiteProperties;
import org.slf4j.Logger;

/**
 * A short memory of what the plugin has been doing, for one purpose: the settings menu's <i>Troubleshoot...</i> window
 * builds its report from it, and a player pastes the report into a bug report. It exists because the plugin's log
 * lines are at DEBUG and a player's client log is rarely to hand; a report that says "the bank event was dropped
 * because the client had not named the account" saves a round of questions.
 *
 * <p><b>What it holds.</b> A ring of the last {@value #CAPACITY} notes, each stamped with the local time and a few
 * words; the last {@value #MAX_ERRORS} DISTINCT errors, each with at most {@value #MAX_FRAMES} of its stack frames;
 * the warning keys raised this session with how often ({@link #warnOnce}), which is also what keeps the client log
 * to one WARN line per kind of trouble; and the two small recorders the report reads, {@link FetchLog} (what the
 * price fetches did) and {@link Watchdog} (what the background work is doing). <b>What it never holds:</b> an
 * account hash, a player name, an item name or id, a quantity, or a file path. The first four are a rule for the
 * CALLERS, who hand it counts, times and states only; the class cannot tell an item id from any other number. The
 * path rule is kept here as well: every line that reaches the report passes {@link #scrub}, because an exception
 * message from the disk is very often the path itself, and a path holds the Windows user name.
 *
 * <p>Thread-safe: notes arrive from the client thread, the executor, OkHttp's threads and the EDT, and the report is
 * built on the EDT. Every method is one short critical section, so none of them can hold anything up.
 */
public final class Diagnostics
{
	/** The most notes kept; the oldest goes when a new one arrives. */
	public static final int CAPACITY = 150;

	/** The most stack frames kept of an error. */
	static final int MAX_FRAMES = 12;

	/** The most distinct errors kept; the oldest goes when a sixth arrives. */
	static final int MAX_ERRORS = 5;

	/** What {@link #error} is told when the bank could not be read - {@link Troubleshooter}'s rule 4 reads it. */
	public static final String BANK_READ_FAILED = "bank read failed";
	/** ...when the prices of a bank could not be worked out (rule 6). */
	public static final String COMPUTATION_FAILED = "computation failed";
	/** ...when the net worth reading's cells could not be worked out (rule 6). */
	public static final String HISTORY_CELLS_FAILED = "history cells failed";
	/** ...when the net worth history could not be folded in (rule 6). */
	public static final String HISTORY_FAILED = "net worth history failed";

	/** The sections a {@link Facts} fills, in the order the report prints them. */
	public static final String PLAYER = "Player";
	public static final String BANK = "Bank";
	public static final String PRICING = "Pricing";
	public static final String HISTORY = "History";
	public static final String SIDEBAR = "Sidebar";
	public static final String SETTINGS = "Settings";

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

	/**
	 * A Windows path: a drive and then directories (which may hold spaces) and a last component (which may not).
	 * A URL such as {@code https://} does not match, because the drive letter must stand alone.
	 */
	private static final Pattern WINDOWS_PATH = Pattern.compile(
		"\\b[A-Za-z]:[\\\\/](?:[^\\\\/:\"'<>|\\r\\n]+[\\\\/])*[^\\s\\\\/:\"'<>|,;)\\]]*");
	private static final Pattern UNC_PATH = Pattern.compile("\\\\\\\\[^\\s\"'<>|,;)\\]]+");
	/** A Unix path of at least two components, not the tail of a URL or of a word such as "and/or". */
	private static final Pattern UNIX_PATH = Pattern.compile(
		"(?<![\\w/:.~-])/(?:[^/\\s\"'<>|:]+/)+[^\\s/\"'<>|:,;)\\]]*");
	private static final String PATH_MARK = "<path>";

	/** One kept error: what was being done, the exception and how often this same one has been seen. */
	private static final class ErrorEntry
	{
		final String what;
		final String time;
		final String type;
		final String message;
		@Nullable
		final String cause;
		final List<String> frames;
		final int count;

		ErrorEntry(final String what, final String time, final String type, final String message,
			@Nullable final String cause, final List<String> frames, final int count)
		{
			this.what = what;
			this.time = time;
			this.type = type;
			this.message = message;
			this.cause = cause;
			this.frames = frames;
			this.count = count;
		}
	}

	private final Supplier<Long> clock;
	private final ZoneId zone;
	private final FetchLog fetchLog;
	private final Watchdog watchdog;
	private final Object lock = new Object();
	private final Deque<String> notes = new ArrayDeque<>();
	/** Warning key to how many times it was raised, in the order first raised. */
	private final Map<String, Integer> warned = new LinkedHashMap<>();
	/** Distinct errors by class and message, least recent first. */
	private final Map<String, ErrorEntry> errors = new LinkedHashMap<>();

	/** The wall clock and the machine's own time zone: how the plugin builds it. */
	public Diagnostics()
	{
		this(System::currentTimeMillis, ZoneId.systemDefault());
	}

	/**
	 * @param clock wall clock in epoch milliseconds
	 * @param zone  the zone the notes' times and the report's header are written in
	 */
	public Diagnostics(final Supplier<Long> clock, final ZoneId zone)
	{
		this.clock = clock;
		this.zone = zone;
		this.fetchLog = new FetchLog(clock::get);
		this.watchdog = new Watchdog(clock::get);
	}

	/** What the price fetches have done this session; the two price clients write it. */
	public FetchLog fetchLog()
	{
		return fetchLog;
	}

	/** What the background work is doing; the price service's executor wrapper writes it. */
	public Watchdog watchdog()
	{
		return watchdog;
	}

	/**
	 * Adds one note, stamped {@code HH:mm:ss} in local time. The oldest is dropped once there are
	 * {@value #CAPACITY}. A line break in {@code text} becomes a space, so a note stays one line of the report.
	 */
	public void note(final String text)
	{
		final String line = TIME.format(now()) + " " + oneLine(text);
		synchronized (lock)
		{
			notes.addLast(line);
			while (notes.size() > CAPACITY)
			{
				notes.removeFirst();
			}
		}
	}

	/**
	 * Keeps {@code e} - what was being done, the exception's class and message, its cause and at most
	 * {@value #MAX_FRAMES} stack frames - and notes {@code what + ": " + e}. The last {@value #MAX_ERRORS} DISTINCT
	 * errors are kept, distinct by class and message: the same error again counts up and becomes the latest.
	 */
	public void error(final String what, final Throwable e)
	{
		final String message = e.getMessage() == null ? "" : e.getMessage();
		final String key = e.getClass().getName() + ": " + message;
		final Throwable cause = e.getCause();
		final List<String> frames = new ArrayList<>();
		final StackTraceElement[] trace = e.getStackTrace();
		for (int i = 0; i < trace.length && i < MAX_FRAMES; i++)
		{
			frames.add(trace[i].toString());
		}
		final String time = TIME.format(now());
		synchronized (lock)
		{
			final ErrorEntry before = errors.remove(key);
			errors.put(key, new ErrorEntry(oneLine(what), time, e.getClass().getName(), oneLine(message),
				cause == null ? null : oneLine(cause.getClass().getName()
					+ (cause.getMessage() == null ? "" : ": " + cause.getMessage())),
				frames, before == null ? 1 : before.count + 1));
			while (errors.size() > MAX_ERRORS)
			{
				final Iterator<String> oldest = errors.keySet().iterator();
				oldest.next();
				oldest.remove();
			}
		}
		note(what + ": " + e);
	}

	/** What the latest error was being done when it was raised, or null while there has been none. */
	@Nullable
	public String lastErrorWhat()
	{
		synchronized (lock)
		{
			ErrorEntry latest = null;
			for (final ErrorEntry entry : errors.values())
			{
				latest = entry;
			}
			return latest == null ? null : latest.what;
		}
	}

	/**
	 * Whether {@code key} is being raised for the FIRST time this session: true once, false ever after. Every call
	 * counts, so the report shows how often each kind of trouble came back; the first time it also notes
	 * "warning: key". Callers use it to log a trouble at WARN once and at DEBUG after that (see {@link #warn}).
	 *
	 * @param key a short kind of trouble, such as {@code bank-read} - no ids, no names
	 */
	public boolean warnOnce(final String key)
	{
		final boolean first;
		synchronized (lock)
		{
			final Integer before = warned.get(key);
			first = before == null;
			warned.put(key, before == null ? 1 : before + 1);
		}
		if (first)
		{
			note("warning: " + key);
		}
		return first;
	}

	/**
	 * Logs at WARN the first time {@code key} is raised this session and at DEBUG every time after - the plugin's
	 * one rule for trouble that can repeat. {@code args} follow SLF4J's rule: a trailing exception is the
	 * throwable.
	 */
	public void warn(final Logger log, final String key, final String message, final Object... args)
	{
		if (warnOnce(key))
		{
			log.warn(message, args);
		}
		else
		{
			log.debug(message, args);
		}
	}

	/**
	 * The facts the plugin, the service and the panel hand over when Troubleshoot is pressed (1.0.8): a handful of
	 * typed values {@link Troubleshooter#verdict} decides on, and the lines of the report's own sections. Immutable;
	 * built with {@link #builder()}, which every contributor fills in its own thread - the builder is synchronized,
	 * and nothing is read from it until {@link Builder#build()}.
	 *
	 * <p>Counts, times and states only: a caller never hands over an account hash, a player name, an item or a
	 * quantity, and {@link #scrub} takes a path out of anything that slips through.
	 */
	public static final class Facts
	{
		public final boolean loggedIn;
		public final boolean accountKnown;
		public final int eventsSeen;
		public final int eventsHeld;
		public final int bankReads;
		public final int eventsDropped;
		/** The card the sidebar shows ({@code BankPriceMovementPanel.CARD_*}), or empty while unknown. */
		public final String card;
		@Nullable
		public final String lastErrorWhat;
		/** How long the longest-running background task has been running; 0 when none is. */
		public final long runningForSeconds;
		/** False when the game thread never answered the troubleshoot within its ceiling, so the player facts above are unset. */
		public final boolean clientAnswered;
		private final Map<String, List<String>> sections;

		private Facts(final Builder b)
		{
			this.loggedIn = b.loggedIn;
			this.accountKnown = b.accountKnown;
			this.eventsSeen = b.eventsSeen;
			this.eventsHeld = b.eventsHeld;
			this.bankReads = b.bankReads;
			this.eventsDropped = b.eventsDropped;
			this.card = b.card;
			this.lastErrorWhat = b.lastErrorWhat;
			this.runningForSeconds = b.runningForSeconds;
			this.clientAnswered = b.clientAnswered;
			final Map<String, List<String>> copy = new LinkedHashMap<>();
			for (final Map.Entry<String, List<String>> entry : b.sections.entrySet())
			{
				copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
			}
			this.sections = Collections.unmodifiableMap(copy);
		}

		/** A new builder with nothing in it. */
		public static Builder builder()
		{
			return new Builder();
		}

		/** The {@code key: value} lines of one section, in the order they were added; empty when there are none. */
		List<String> lines(final String section)
		{
			final List<String> lines = sections.get(section);
			return lines == null ? Collections.emptyList() : lines;
		}

		/** Collects facts from several threads, then {@link #build builds} them. */
		public static final class Builder
		{
			private boolean loggedIn;
			private boolean accountKnown;
			private int eventsSeen;
			private int eventsHeld;
			private int bankReads;
			private int eventsDropped;
			private String card = "";
			@Nullable
			private String lastErrorWhat;
			private long runningForSeconds;
			private boolean clientAnswered = true;
			private final Map<String, List<String>> sections = new LinkedHashMap<>();

			private Builder()
			{
			}

			/** Whether the client is logged in: the verdict's rule 1, and the Player section's first line. */
			public synchronized Builder loggedIn(final boolean value)
			{
				this.loggedIn = value;
				return line(PLAYER, "logged in", yesNo(value));
			}

			/** Whether the client has named the account (never which): rule 2, and a Player line. */
			/** The game thread's answer never came: the report says so and the verdict names it first. */
			public synchronized Builder clientAnswered(final boolean value)
			{
				this.clientAnswered = value;
				return this;
			}

			public synchronized Builder accountKnown(final boolean value)
			{
				this.accountKnown = value;
				return line(PLAYER, "account known", yesNo(value));
			}

			/** The plugin's bank-event counters for this session: rules 2, 3 and 9, and four Bank lines. */
			public synchronized Builder bankEvents(final int seen, final int held, final int read, final int dropped)
			{
				this.eventsSeen = seen;
				this.eventsHeld = held;
				this.bankReads = read;
				this.eventsDropped = dropped;
				line(BANK, "bank events seen", String.valueOf(seen));
				line(BANK, "bank events held", String.valueOf(held));
				line(BANK, "bank reads made", String.valueOf(read));
				return line(BANK, "bank events dropped", String.valueOf(dropped));
			}

			/** The card the sidebar is showing: rule 9, and a Sidebar line. */
			public synchronized Builder card(final String value)
			{
				this.card = value == null ? "" : value;
				return line(SIDEBAR, "card", this.card);
			}

			/** What the latest error was being done (from {@link Diagnostics#lastErrorWhat()}): rules 4 and 6. */
			public synchronized Builder lastErrorWhat(@Nullable final String value)
			{
				this.lastErrorWhat = value;
				return this;
			}

			/** How long the longest-running background task has run, in seconds (from the watchdog): rule 5. */
			public synchronized Builder runningForSeconds(final long value)
			{
				this.runningForSeconds = value;
				return this;
			}

			/** One {@code key: value} line of the report, in {@code section} ({@link #PLAYER}, {@link #BANK} ...). */
			public synchronized Builder line(final String section, final String key, final String value)
			{
				sections.computeIfAbsent(section, s -> new ArrayList<>()).add(key + ": " + value);
				return this;
			}

			/** The facts as collected so far, frozen. */
			public synchronized Facts build()
			{
				return new Facts(this);
			}
		}
	}

	/**
	 * The report, as it is shown and copied: a title, the verdict, then the sections in this order - Build, Player,
	 * Bank, Prices, Pricing, History, Sidebar, Background work, Settings, Errors, Checks and the recent events,
	 * oldest first. Each is a heading line and {@code key: value} lines.
	 *
	 * @param facts   what the plugin, the service and the panel handed over
	 * @param verdict the one plain sentence {@link Troubleshooter#verdict} chose
	 * @param checks  the active checks' results; empty when they did not run
	 */
	public String text(final Facts facts, final String verdict, final List<CheckResult> checks)
	{
		final ZonedDateTime now = now();
		final StringBuilder out = new StringBuilder(8192);
		out.append("2h Bank Portfolio Tracker ").append(Version.CURRENT).append(" - diagnostics\n");
		out.append("Verdict: ").append(oneLine(verdict)).append('\n');

		heading(out, "Build");
		line(out, "plugin version", Version.CURRENT);
		line(out, "RuneLite", runeLiteVersion(false));
		line(out, "launcher", runeLiteVersion(true));
		final String hubVersion = pluginHubVersion();
		line(out, "hub version", hubVersion == null ? "unknown" : hubVersion);
		line(out, "system", property("os.name") + " " + property("os.version"));
		line(out, "java", property("java.version"));
		line(out, "time zone", zone.getId());
		line(out, "local time", DATE_TIME.format(now));
		final Long skew = fetchLog.clockSkewSeconds();
		line(out, "clock skew", skew == null ? "unknown"
			: (skew > 0 ? "+" : "") + skew + " s (this computer minus the wiki)");

		facts(out, facts, PLAYER);
		facts(out, facts, BANK);

		heading(out, "Prices");
		for (final String source : FetchLog.SOURCES)
		{
			line(out, source, fetchLine(fetchLog.last(source)));
		}

		facts(out, facts, PRICING);
		facts(out, facts, HISTORY);
		facts(out, facts, SIDEBAR);

		heading(out, "Background work");
		final Watchdog.Snapshot work = watchdog.snapshot();
		line(out, "last task", work.lastName == null ? "none yet" : work.lastName);
		line(out, "started at", work.startedAtMillis <= 0L ? "-" : clockTime(work.startedAtMillis));
		line(out, "finished at", work.finishedAtMillis > 0L ? clockTime(work.finishedAtMillis)
			: (work.runningName != null ? "still running" : "-"));
		line(out, "queued now", String.valueOf(work.queued));
		line(out, "running for", work.runningName == null ? "-"
			: work.runningForSeconds + " s" + (work.stuck() ? " - STUCK" : ""));

		facts(out, facts, SETTINGS);

		synchronized (lock)
		{
			heading(out, "Errors");
			if (errors.isEmpty())
			{
				out.append("none\n");
			}
			final List<ErrorEntry> newestFirst = new ArrayList<>(errors.values());
			Collections.reverse(newestFirst);
			int number = 1;
			for (final ErrorEntry entry : newestFirst)
			{
				out.append(number++).append(". ").append(entry.time).append(' ').append(entry.what);
				if (entry.count > 1)
				{
					out.append(" (seen ").append(entry.count).append(" times)");
				}
				out.append('\n');
				out.append("   class: ").append(entry.type).append('\n');
				out.append("   message: ").append(scrub(entry.message)).append('\n');
				if (entry.cause != null)
				{
					out.append("   cause: ").append(scrub(entry.cause)).append('\n');
				}
				for (final String frame : entry.frames)
				{
					out.append("     at ").append(frame).append('\n');
				}
			}
			if (!warned.isEmpty())
			{
				out.append("warnings raised:\n");
				for (final Map.Entry<String, Integer> warning : warned.entrySet())
				{
					out.append("   ").append(scrub(oneLine(warning.getKey()))).append(": ").append(warning.getValue())
						.append(warning.getValue() == 1 ? " time\n" : " times\n");
				}
			}
		}

		heading(out, "Checks");
		if (checks.isEmpty())
		{
			out.append("not run\n");
		}
		for (final CheckResult check : checks)
		{
			line(out, check.name, (check.ok ? "ok" : "failed")
				+ (check.httpStatus > 0 ? ", HTTP " + check.httpStatus : "") + ", " + check.millis + " ms"
				+ (check.detail == null || check.detail.isEmpty() ? "" : " - " + check.detail));
		}

		synchronized (lock)
		{
			heading(out, "Recent events, oldest first");
			for (final String line : notes)
			{
				out.append(line).append('\n');
			}
		}
		return out.toString();
	}

	/**
	 * {@code text} with every file path in it replaced by {@code <path>}. An exception from the disk names the file
	 * it failed on, and a file lives under the player's home folder, which is named after them - so nothing of the
	 * kind may reach a report a player pastes into a public bug report. Windows paths, UNC paths and Unix paths of at
	 * least two components; a URL and a word such as "and/or" are left alone.
	 */
	public static String scrub(@Nullable final String text)
	{
		if (text == null || text.isEmpty())
		{
			return "";
		}
		String out = WINDOWS_PATH.matcher(text).replaceAll(PATH_MARK);
		out = UNC_PATH.matcher(out).replaceAll(PATH_MARK);
		return UNIX_PATH.matcher(out).replaceAll(PATH_MARK);
	}

	private void facts(final StringBuilder out, final Facts facts, final String section)
	{
		heading(out, section);
		final List<String> lines = facts.lines(section);
		if (lines.isEmpty())
		{
			out.append("nothing reported\n");
		}
		for (final String line : lines)
		{
			out.append(oneLine(line)).append('\n');
		}
	}

	private static void heading(final StringBuilder out, final String heading)
	{
		out.append('\n').append(heading).append('\n');
	}

	private static void line(final StringBuilder out, final String key, final String value)
	{
		out.append(key).append(": ").append(oneLine(value)).append('\n');
	}

	private String fetchLine(@Nullable final FetchLog.Record record)
	{
		if (record == null)
		{
			return "not fetched this session";
		}
		final StringBuilder line = new StringBuilder();
		line.append("last attempt ").append(clockTime(record.attemptMillis)).append(", ").append(record.ok ? "ok" : "failed");
		line.append(", HTTP ").append(record.httpStatus > 0 ? String.valueOf(record.httpStatus) : "none");
		line.append(", ").append(record.millis).append(" ms, ").append(record.bytes).append(" bytes, ")
			.append(record.items).append(" items, ").append(record.consecutiveFailures).append(" failures in a row");
		if (!record.ok && record.lastError != null && !record.lastError.isEmpty())
		{
			line.append(", last error: ").append(record.lastError);
		}
		return line.toString();
	}

	private String clockTime(final long millis)
	{
		return TIME.format(Instant.ofEpochMilli(millis).atZone(zone));
	}

	private ZonedDateTime now()
	{
		return Instant.ofEpochMilli(clock.get()).atZone(zone);
	}

	private static String yesNo(final boolean value)
	{
		return value ? "yes" : "no";
	}

	private static String property(final String name)
	{
		final String value = System.getProperty(name);
		return value == null ? "?" : value;
	}

	private static String runeLiteVersion(final boolean launcher)
	{
		try
		{
			final String version = launcher ? RuneLiteProperties.getLauncherVersion() : RuneLiteProperties.getVersion();
			return version == null ? "unknown" : version;
		}
		catch (final RuntimeException | LinkageError e)
		{
			return "unknown";
		}
	}

	/**
	 * The client version RuneLite asks the Plugin Hub for ({@code RuneLiteProperties.getPluginHubVersion()}), which is
	 * the address of the manifest the version check reads; null when RuneLite does not say or this client has no such
	 * method, so neither the report nor the check ever fails on it.
	 */
	@Nullable
	static String pluginHubVersion()
	{
		try
		{
			final String version = RuneLiteProperties.getPluginHubVersion();
			return version == null || version.isEmpty() ? null : version;
		}
		catch (final RuntimeException | LinkageError e)
		{
			return null;
		}
	}

	/** One line with no path in it: a line break becomes a space and {@link #scrub} runs. */
	private static String oneLine(final String text)
	{
		return text == null ? "" : scrub(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '));
	}
}
