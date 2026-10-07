package com.bankpricemovement;

import static com.bankpricemovement.BankHistorySeriesTest.sep;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.Gson;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.LoggerFactory;

/**
 * The final review's store finding (addendum AU, R5): a history file that reads but will not write logged one WARN
 * on EVERY retry - and review finding H5 retries with every later reading, which is every bank change and every
 * half-hourly re-check. Now the first failure for an owner warns, the retries after it log at debug, and another
 * owner's first failure still warns.
 */
public class HistoryFinalReviewTest
{
	private static final long ACCOUNT = 42L;
	private static final String PROFILE = "STANDARD";
	private static final LocalDate TODAY = sep(28);

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void aHistoryFileThatWillNotWriteWarnsOncePerOwnerPerSession()
	{
		final PriceStore store = new PriceStore(new Gson(), refusingHistoryWrites());
		final List<ILoggingEvent> lines = captured(() ->
		{
			for (int attempt = 0; attempt < 4; attempt++)
			{
				assertFalse("the write fails", store.recordBankHistory(ACCOUNT, PROFILE, point(attempt), TODAY, null));
			}
			assertFalse(store.recordBankHistory(ACCOUNT, "DEADMAN", point(9), TODAY, null));
		});

		final List<String> warnings = new ArrayList<>();
		int failures = 0;
		for (final ILoggingEvent line : lines)
		{
			if (line.getFormattedMessage().contains("failed"))
			{
				failures++;
				if (Level.WARN.equals(line.getLevel()))
				{
					warnings.add(line.getFormattedMessage());
				}
			}
		}
		assertEquals("every failure is still logged", 5, failures);
		assertEquals("one WARN per owner: " + warnings, 2, warnings.size());
		assertTrue(warnings.get(0), warnings.get(0).contains("history-42-STANDARD.json"));
		assertTrue(warnings.get(1), warnings.get(1).contains("history-42-DEADMAN.json"));
	}

	private static BankHistoryPoint point(final long base)
	{
		final long[] card = new long[BankHistoryPoint.CELLS];
		Arrays.fill(card, base + 1L);
		return new BankHistoryPoint(TODAY, base + 1L, base + 1L, card, null);
	}

	/**
	 * The temporary folder as a store root on which every history temp file's write throws, as a full disk or a
	 * locked folder would; the READ of the history file (there is none) answers MISSING as usual.
	 */
	private Filepath refusingHistoryWrites()
	{
		final Filepath real = TestFilepaths.rooted(tmp.getRoot());
		final Filepath[] root = new Filepath[1];
		root[0] = mock(Filepath.class, withSettings().spiedInstance(real).defaultAnswer(invocation ->
		{
			if (!"joinSegment".equals(invocation.getMethod().getName()))
			{
				return invocation.callRealMethod();
			}
			final Filepath child = (Filepath) invocation.callRealMethod();
			final String name = child.getFileName();
			return mock(Filepath.class, withSettings().spiedInstance(child).defaultAnswer(call ->
			{
				switch (call.getMethod().getName())
				{
					case "getParent":
						return root[0];
					case "write":
						if (name.startsWith(PriceStore.HISTORY_PREFIX))
						{
							throw new IOException("the disk is full");
						}
						return call.callRealMethod();
					default:
						return call.callRealMethod();
				}
			}));
		}));
		return root[0];
	}

	/** Every line the store's logger writes on this thread while {@code action} runs, DEBUG and up. */
	private static List<ILoggingEvent> captured(final Runnable action)
	{
		final org.slf4j.Logger slf4j = LoggerFactory.getLogger(PriceStore.class);
		assertTrue("logback must be the slf4j binding here: " + slf4j.getClass(),
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
		capture.setName("history-final-review-test");
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
}
