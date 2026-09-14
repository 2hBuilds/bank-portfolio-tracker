package com.bankpricemovement;

import java.io.IOException;

/**
 * The single failure type every {@link GuidePriceClient} future completes exceptionally with (contract C14/C15,
 * carried forward unchanged by K5: "keep {@code WikiPriceException}").
 *
 * <p>It extends {@link IOException} on purpose: the injected OkHttpClient already signals every one of its own
 * refusals as a plain {@code IOException} through {@code Callback.onFailure} - "Blocking network calls are not
 * allowed on the client thread", "...on the event dispatch thread" and "Network call to ... blocked outside of
 * LIVE environment" are all raised by the interceptor at
 * {@code runelite-client/src/main/java/net/runelite/client/RuneLiteModule.java:182-205} (clone tag
 * runelite-parent-1.12.37). Wrapping HTTP failures, wiki {@code {"error": ...}} bodies and malformed JSON in one
 * IOException subtype lets {@code PriceService} catch a single type and never string-match a message
 * (research C4 precision 1: "handle IOException generally and never string-match").</p>
 *
 * <p>{@link #getHttpCode()} is {@link #NO_HTTP_CODE} (0) whenever the failure happened without a status line -
 * a transport {@code IOException}, a malformed body, or an {@code {"error": ...}} body that arrived with HTTP 200
 * (MediaWiki reports every API error that way, and the prices API did the same for a misaligned timestamp -
 * research C10 addition 2 - so the code alone can never be the failure test).</p>
 */
public class WikiPriceException extends IOException
{
	private static final long serialVersionUID = 1L;

	/**
	 * The {@link #getHttpCode()} value meaning "this failure carries no HTTP status" - a transport error, a body
	 * that would not parse, or an error body that arrived with a 2xx status.
	 */
	public static final int NO_HTTP_CODE = 0;

	private final int httpCode;

	public WikiPriceException(final String message)
	{
		this(message, NO_HTTP_CODE, null);
	}

	public WikiPriceException(final String message, final int httpCode)
	{
		this(message, httpCode, null);
	}

	public WikiPriceException(final String message, final Throwable cause)
	{
		this(message, NO_HTTP_CODE, cause);
	}

	public WikiPriceException(final String message, final int httpCode, final Throwable cause)
	{
		super(message, cause);
		this.httpCode = httpCode;
	}

	/**
	 * The HTTP status the wiki answered with, or {@link #NO_HTTP_CODE} when the failure carried no status line.
	 * 403 is the one worth branching on: the prices API pre-emptively blocks default user agents (research C3),
	 * so a 403 means the User-Agent never reached it, not that the item data is missing.
	 */
	public int getHttpCode()
	{
		return httpCode;
	}

	/**
	 * True when {@link #getHttpCode()} is a real status. Kept separate from {@code getHttpCode() != 0} so callers
	 * do not have to know that 0 is the sentinel.
	 */
	public boolean hasHttpCode()
	{
		return httpCode != NO_HTTP_CODE;
	}
}
