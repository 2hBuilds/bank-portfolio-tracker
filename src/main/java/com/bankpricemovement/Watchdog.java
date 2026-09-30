package com.bankpricemovement;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;

/**
 * What the plugin's background work is doing right now (1.0.8), for the Troubleshoot report and its verdict.
 *
 * <p>Everything the price service does off the client thread runs on RuneLite's ONE shared executor, so a task that
 * never comes back - a disk call that hangs, a lock that is never released - stops every task behind it and nothing
 * on the sidebar says why. {@link PriceService}'s executor wrapper tells this class when a task is queued, when it
 * starts and when it ends; the report then says which task ran last, how many wait behind it and, if one has been
 * running for more than {@value #STUCK_SECONDS} s, which one is holding the queue.
 *
 * <p><b>No timer and no thread.</b> Nothing here watches anything: "running for" is worked out from the clock when
 * {@link #snapshot()} is asked for, so a stuck task costs the plugin nothing until somebody looks. Thread-safe:
 * the wrapper calls in from the executor and from whichever thread queued the task, and the report reads it on the
 * EDT; every method is one short critical section.
 */
public final class Watchdog
{
	/** A task that has run longer than this many seconds is reported as stuck, and the verdict says so. */
	public static final long STUCK_SECONDS = 30L;

	/** One task that has started and not yet finished. */
	private static final class Running
	{
		final String name;
		final long startedAtMillis;

		Running(final String name, final long startedAtMillis)
		{
			this.name = name;
			this.startedAtMillis = startedAtMillis;
		}
	}

	/**
	 * The watchdog at one moment, immutable. {@code lastName} is the task that started most recently and
	 * {@code finishedAtMillis} is when that same task ended (0 while it is still running); the two {@code running}
	 * members describe the task that has been running the longest, if any.
	 */
	public static final class Snapshot
	{
		/** The task that started last, or null when none has started. */
		@Nullable
		public final String lastName;
		public final long startedAtMillis;
		public final long finishedAtMillis;
		/** Tasks submitted and not yet started. */
		public final int queued;
		/** The task running the longest, or null when nothing is running. */
		@Nullable
		public final String runningName;
		/** How long {@link #runningName} has been running, in whole seconds; 0 when nothing is. */
		public final long runningForSeconds;

		Snapshot(@Nullable final String lastName, final long startedAtMillis, final long finishedAtMillis,
			final int queued, @Nullable final String runningName, final long runningForSeconds)
		{
			this.lastName = lastName;
			this.startedAtMillis = startedAtMillis;
			this.finishedAtMillis = finishedAtMillis;
			this.queued = queued;
			this.runningName = runningName;
			this.runningForSeconds = runningForSeconds;
		}

		/** Whether the task running longest has passed {@value Watchdog#STUCK_SECONDS} s. */
		public boolean stuck()
		{
			return runningName != null && runningForSeconds > STUCK_SECONDS;
		}
	}

	private final LongSupplier clock;
	private final Object lock = new Object();
	private final Map<Long, Running> running = new LinkedHashMap<>();
	private long nextId;
	private int queued;
	@Nullable
	private String lastName;
	private long lastId = -1L;
	private long lastStartedAt;
	private long lastFinishedAt;

	/** @param clock wall clock in epoch milliseconds - the plugin's diagnostics clock, so the report agrees with itself */
	public Watchdog(final LongSupplier clock)
	{
		this.clock = clock;
	}

	/** A task has been handed to the executor and waits for its turn. */
	public void submitted()
	{
		synchronized (lock)
		{
			queued++;
		}
	}

	/** The executor refused a task that {@link #submitted} had counted: it will never start. */
	public void rejected()
	{
		synchronized (lock)
		{
			queued = Math.max(0, queued - 1);
		}
	}

	/**
	 * A task has begun.
	 *
	 * @param name      a short label with no identifiers in it, as the report prints it
	 * @param wasQueued whether {@link #submitted} counted it - a periodic run was never queued by anyone
	 * @return the id to hand to {@link #finished}
	 */
	public long started(final String name, final boolean wasQueued)
	{
		synchronized (lock)
		{
			if (wasQueued)
			{
				queued = Math.max(0, queued - 1);
			}
			final long id = nextId++;
			final long now = clock.getAsLong();
			running.put(id, new Running(name, now));
			lastId = id;
			lastName = name;
			lastStartedAt = now;
			lastFinishedAt = 0L;
			return id;
		}
	}

	/** The task {@link #started} named has ended, however it ended. */
	public void finished(final long id)
	{
		synchronized (lock)
		{
			if (running.remove(id) != null && id == lastId)
			{
				lastFinishedAt = clock.getAsLong();
			}
		}
	}

	/** Where things stand now; "running for" is computed here, from the clock. */
	public Snapshot snapshot()
	{
		synchronized (lock)
		{
			final long now = clock.getAsLong();
			Running longest = null;
			for (final Running task : running.values())
			{
				if (longest == null || task.startedAtMillis < longest.startedAtMillis)
				{
					longest = task;
				}
			}
			final long seconds = longest == null ? 0L : Math.max(0L, (now - longest.startedAtMillis) / 1000L);
			return new Snapshot(lastName, lastStartedAt, lastFinishedAt, queued, longest == null ? null : longest.name,
				seconds);
		}
	}
}
