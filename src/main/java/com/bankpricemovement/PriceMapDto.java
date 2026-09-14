package com.bankpricemovement;

import java.util.Map;

/**
 * The on-disk form of a {@link PriceMap}: public fields, a no-arg constructor and nothing but JSON-friendly
 * types, so the INJECTED Gson can read and write it without an adapter (contract C4, extended by K11; the
 * workspace rule for a persisted class - playbook step 13 - is "public fields, no-arg ctor, stock Gson").
 *
 * <p>Two shape decisions, both because a saved map holds 3-4.5k entries:
 *
 * <ul>
 * <li>the keys are STRINGS. A JSON object key is always a string, and Gson round-trips a {@code Map<Integer,..>}
 * only by re-parsing those keys; spelling that out here keeps the file honest and lets a key that is not a
 * number be skipped rather than throw ({@link PriceMap#fromDto}).</li>
 * <li>each value is a two-slot {@code long[]} - {@code [high, low]} - with {@link #ABSENT} (-1) standing for
 * "the wiki gave no price on that side". A {@code Long[]} would carry null directly, but whether a null
 * survives depends on the Gson instance's null handling (the injected client Gson drops nulls in objects -
 * playbook 7.8), and a price can never be negative, so a sentinel is the shape that cannot be configured
 * away. {@code 0} stays a legal price.</li>
 * </ul>
 */
public class PriceMapDto
{
	/**
	 * The stored value that means "no price on this side" - see {@link PricePoint} on why a side goes missing.
	 * -1 is safe as a sentinel because the wiki never returns a negative price.
	 */
	public static final long ABSENT = -1L;

	/**
	 * The version of THIS document shape that the current build writes ({@link #schema}). Bump it whenever a
	 * stored field's MEANING changes, not when a field is merely added: a new field an old file lacks reads as
	 * its zero and costs nothing, while a field that means something else than it used to is a file this build
	 * must not believe.
	 *
	 * <p>Addendum K to L is the change that proves the need. Both eras write {@code revId > 0}, so the sweep's
	 * revision test cannot separate them, and yet {@link #bucketSeconds} means the REVISION'S SAVE TIME in a
	 * K-era file and the table's own {@code %LAST_UPDATE%} in an L-era one - a day apart often enough to flip
	 * the sign on a row. Nothing on any machine is K-era (v1 has never been published), so 1 is the first
	 * number this field has ever carried and every file written before it existed reads as 0, which is exactly
	 * the marker those two eras lack.
	 */
	public static final int SCHEMA = 1;

	/**
	 * Which build's shape this document is: {@link #SCHEMA} in anything this build wrote, and 0 in every file
	 * written before the field existed (Gson leaves an absent key at its zero - the whole point of the marker).
	 *
	 * <p>Read by {@code PriceService.guideOrEmpty}, alongside the {@code revId > 0} test it already made: a
	 * baseline below the current schema is REFUSED rather than adopted, so it can never teach the service a
	 * calendar day that its fields no longer mean. The cost of a refusal is one refetch of that window - the
	 * same refetch the six-hour cadence makes anyway - and the panel says "No 1d history yet" until it lands.
	 */
	public int schema;

	/**
	 * When the map was fetched, in epoch millis (0 when unknown).
	 */
	public long fetchedAtMillis;

	/**
	 * The moment this map's prices belong to, in unix seconds; 0 when it is a "now" reading with no history
	 * behind it.
	 *
	 * <p>The name is pre-addendum-K, when the value really was a wiki hourly TRADE bucket. Since the switch to
	 * the guide series (K11) it carries a moment out of {@code Module:GEPrices/data.json}, and since addendum L
	 * (L7) that moment is the table's own {@code %LAST_UPDATE%} - the DAY MARKER Jagex stamped the prices with -
	 * rather than the revision's save time, which a human maintenance edit can put a whole day later than the
	 * prices in the body (L-E). The field keeps its name so a saved baseline written by the first build still
	 * parses ({@code PriceMap.fromDto} reads it either way); {@link #revId} is what distinguishes the two eras,
	 * and {@code PriceStore.deleteStaleFiles()} uses exactly that to sweep a trade-era file away.
	 */
	public long bucketSeconds;

	/**
	 * The wiki revision {@link #bucketSeconds} and the prices came from (K11), or 0 when this map predates the
	 * guide switch or is a "now" reading.
	 *
	 * <p>A real revision id is in the fifteen millions (measured 2026-09-08), and
	 * {@code GuidePriceClient.parseRevisionIndex} drops anything that is not positive, so 0 is an unambiguous
	 * "not from the guide table". Absent from a file written by the first build, where Gson leaves it at 0 - the
	 * back-compat case {@code PriceMapTest} pins.
	 */
	public long revId;

	/**
	 * Item id (as text) to {@code [high, low]}, each side {@link #ABSENT} when there was no price on it. A guide
	 * map carries the same number on both sides (the guide price is a single figure, K1), which keeps one shape
	 * on disk for both eras.
	 */
	public Map<String, long[]> points;

	public PriceMapDto()
	{
	}

	/**
	 * Turns a nullable price side into its stored form.
	 */
	public static long encode(final Long side)
	{
		return side == null ? ABSENT : side;
	}

	/**
	 * Turns a stored side back into a nullable price. Anything negative reads as "absent", so a hand-edited
	 * or truncated file cannot inject a negative price into the maths.
	 */
	public static Long decode(final long stored)
	{
		return stored < 0 ? null : stored;
	}
}
