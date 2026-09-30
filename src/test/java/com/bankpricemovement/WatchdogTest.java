package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

/** The background-work watchdog (1.0.8): names, times, the queued count and the 30-second rule. */
public class WatchdogTest
{
	private static final long T0 = 1_000_000L;

	private final AtomicLong clock = new AtomicLong(T0);
	private final Watchdog dog = new Watchdog(clock::get);

	@Test
	public void aFreshWatchdogHasSeenNothing()
	{
		final Watchdog.Snapshot s = dog.snapshot();

		assertNull(s.lastName);
		assertEquals(0, s.queued);
		assertNull(s.runningName);
		assertEquals(0L, s.runningForSeconds);
		assertFalse(s.stuck());
	}

	@Test
	public void submittingCountsAndStartingUncountsTheQueue()
	{
		dog.submitted();
		dog.submitted();
		dog.submitted();
		assertEquals(3, dog.snapshot().queued);

		final long id = dog.started("pricing the bank", true);

		assertEquals(2, dog.snapshot().queued);
		dog.finished(id);
		assertEquals("finishing a task does not touch the queue", 2, dog.snapshot().queued);
	}

	@Test
	public void aPeriodicRunWasNeverQueuedAndLeavesTheCountAlone()
	{
		dog.submitted();

		final long id = dog.started("the tick", false);

		assertEquals(1, dog.snapshot().queued);
		dog.finished(id);
	}

	@Test
	public void aRefusedTaskLeavesTheQueueAndNeverGoesBelowZero()
	{
		dog.submitted();
		dog.rejected();
		dog.rejected();

		assertEquals(0, dog.snapshot().queued);
		dog.started("x", true);
		assertEquals("started never takes the count negative either", 0, dog.snapshot().queued);
	}

	@Test
	public void theLastTaskIsNamedWithItsStartAndFinish()
	{
		final long id = dog.started("saving the bank", false);
		clock.set(T0 + 2_500L);
		Watchdog.Snapshot running = dog.snapshot();
		assertEquals("saving the bank", running.lastName);
		assertEquals(T0, running.startedAtMillis);
		assertEquals("not finished yet", 0L, running.finishedAtMillis);
		assertEquals("saving the bank", running.runningName);
		assertEquals(2L, running.runningForSeconds);

		dog.finished(id);
		final Watchdog.Snapshot done = dog.snapshot();

		assertEquals(T0 + 2_500L, done.finishedAtMillis);
		assertNull(done.runningName);
		assertEquals(0L, done.runningForSeconds);
	}

	@Test
	public void aTaskIsStuckOnlyAfterThirtySeconds()
	{
		dog.started("a network call", false);

		clock.set(T0 + 30_000L);
		assertEquals(30L, dog.snapshot().runningForSeconds);
		assertFalse("thirty seconds exactly is not more than thirty", dog.snapshot().stuck());

		clock.set(T0 + 31_000L);
		assertTrue(dog.snapshot().stuck());
		assertEquals(Watchdog.STUCK_SECONDS, 30L);
	}

	@Test
	public void theLongestRunningTaskIsTheOneReported()
	{
		final long first = dog.started("first", false);
		clock.set(T0 + 10_000L);
		dog.started("second", false);
		clock.set(T0 + 40_000L);

		Watchdog.Snapshot s = dog.snapshot();
		assertEquals("first", s.runningName);
		assertEquals(40L, s.runningForSeconds);
		assertEquals("the last to START is still named as the last task", "second", s.lastName);

		dog.finished(first);
		s = dog.snapshot();
		assertEquals("second", s.runningName);
		assertEquals(30L, s.runningForSeconds);
		assertEquals("first finishing is not the last task finishing", 0L, s.finishedAtMillis);
	}
}
