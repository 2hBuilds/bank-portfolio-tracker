package com.bankpricemovement;

import javax.annotation.Nullable;

/**
 * The answer of one Troubleshoot check (1.0.8): which check it was, whether it passed, the HTTP status the server
 * gave (0 for a check that is not a request, or a request nothing answered), how long it took and one line of
 * detail. Immutable; {@link Troubleshooter} makes them and {@link Diagnostics#text} prints them.
 *
 * <p>{@link #update} belongs to the version check alone: the Plugin Hub's version when it is newer than this build's,
 * and null for every other check and every other outcome of that one.
 */
public final class CheckResult
{
	public final String name;
	public final boolean ok;
	public final int httpStatus;
	public final long millis;
	public final String detail;
	/** The Hub's version when it is newer than this build's; null otherwise. */
	@Nullable
	public final String update;

	public CheckResult(final String name, final boolean ok, final int httpStatus, final long millis, final String detail)
	{
		this(name, ok, httpStatus, millis, detail, null);
	}

	public CheckResult(final String name, final boolean ok, final int httpStatus, final long millis, final String detail,
		@Nullable final String update)
	{
		this.name = name;
		this.ok = ok;
		this.httpStatus = httpStatus;
		this.millis = millis;
		this.detail = detail;
		this.update = update;
	}
}
