package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import com.google.gson.Gson;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link WikiCalls} on its own: what {@link WikiClientTextsTest} cannot reach through the two clients' public
 * fetches. A request that cannot be built - an {@link IllegalArgumentException} out of {@code HttpUrl} for a url that
 * is not one - never happens to the clients, which build every url from constants and numbers, so its text is held
 * here with the noun each client hands over.
 */
public class WikiCallsTest
{
	private final OkHttpClient http = mock(OkHttpClient.class);

	private WikiCalls calls(final String noun)
	{
		return new WikiCalls(LoggerFactory.getLogger(WikiCallsTest.class), http, new Gson(), noun, "off", " - tail",
			body -> null);
	}

	@Test
	public void aUrlThatIsNotOneFailsTheFutureWithTheClientsNounAndNeverTouchesTheNetwork() throws Exception
	{
		for (final String noun : new String[] {"wiki", "traded-price"})
		{
			final CompletableFuture<String> future = calls(noun).request("not a url", body -> body);

			assertTrue(noun, future.isCompletedExceptionally());
			try
			{
				future.get();
				fail("the future should have failed");
			}
			catch (final ExecutionException e)
			{
				assertTrue(noun, e.getCause() instanceof WikiPriceException);
				assertEquals(noun, "could not build the " + noun + " request for not a url", e.getCause().getMessage());
				assertNotNull(noun + ": the cause is the url error", e.getCause().getCause());
				assertTrue(noun, e.getCause().getCause() instanceof IllegalArgumentException);
				assertEquals(noun, WikiPriceException.NO_HTTP_CODE, ((WikiPriceException) e.getCause()).getHttpCode());
			}
		}
		verify(http, never()).newCall(any(Request.class));
	}

	@Test
	public void theHelperRefusesAMissingClientOrGson()
	{
		try
		{
			new WikiCalls(LoggerFactory.getLogger(WikiCallsTest.class), null, new Gson(), "wiki", "off", "", body -> null);
			fail("a null OkHttpClient must be refused at construction");
		}
		catch (final IllegalArgumentException expected)
		{
			assertEquals("client is required (inject RuneLite's OkHttpClient)", expected.getMessage());
		}
		try
		{
			new WikiCalls(LoggerFactory.getLogger(WikiCallsTest.class), http, null, "wiki", "off", "", body -> null);
			fail("a null Gson must be refused at construction");
		}
		catch (final IllegalArgumentException expected)
		{
			assertEquals("gson is required (inject RuneLite's Gson)", expected.getMessage());
		}
	}
}
