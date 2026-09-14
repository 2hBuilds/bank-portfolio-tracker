package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A set of prices indexed by item id, with the moment they belong to (contract C4, extended by K11 and by
 * addendum L line L7).
 *
 * <p>Since addendum K it is one thing only: the Grand Exchange GUIDE table as of one wiki revision - the "then"
 * side of every movement - stamped with its {@link #revId()} and with the moment its prices belong to
 * ({@link #bucketSeconds()}). Addendum L changed WHICH moment that is: no longer the revision's publication
 * time, but the table's own {@code %LAST_UPDATE%} day marker, because a human maintenance edit republishes the
 * previous day's prices under a later timestamp (L-E) and the label on a row must name the day the numbers are
 * from. The field keeps its pre-K name so a saved file from the first build still parses (see
 * {@link PriceMapDto#bucketSeconds}); {@link #dataDay()} is what callers should read.
 *
 * <p>Immutable and shared: the map is built on an OkHttp thread, stored on the executor and read while the
 * rows are computed, so it is copied once at construction and handed out {@link Collections#unmodifiableMap
 * unmodifiable}. Nothing downstream can mutate a map another thread is reading.
 *
 * <p>A baseline map is deliberately allowed to be SMALLER than the bank: the guide table lists 4,566 names and a
 * bank item only reaches one of them through the {@code /mapping} table, so a missing id is an ordinary case and
 * {@link #get(int)} answering null means "no baseline for this item", which the row renders as "-" and sorts
 * last (design D6).
 */
public final class PriceMap
{
	/**
	 * The "nothing fetched yet" map: no points, no timestamp, no revision. Safe to share - this is immutable.
	 */
	public static final PriceMap EMPTY = new PriceMap(Collections.emptyMap(), 0L, 0L, 0L);

	private final Map<Integer, PricePoint> points;
	private final long fetchedAtMillis;
	private final long bucketSeconds;
	private final long revId;
	private final int schema;

	/**
	 * A map with no revision behind it (revId 0). Kept as its own constructor because most callers - and every
	 * test that does not care about provenance - have nothing meaningful to pass, and because a file written by
	 * the first build reads back exactly this way.
	 *
	 * @param points          item id to its two sides; copied, and null keys or values are dropped
	 * @param fetchedAtMillis when the response arrived, epoch millis
	 * @param bucketSeconds   the moment the prices belong to, unix seconds, or 0 when there is none
	 */
	public PriceMap(final Map<Integer, PricePoint> points, final long fetchedAtMillis, final long bucketSeconds)
	{
		this(points, fetchedAtMillis, bucketSeconds, 0L);
	}

	/**
	 * A map built in memory, which is by definition this build's shape ({@link PriceMapDto#SCHEMA}). Only
	 * {@link #fromDto} takes a schema, because only a FILE can have been written by an older build.
	 *
	 * @param points          item id to its two sides; copied, and null keys or values are dropped
	 * @param fetchedAtMillis when the response arrived, epoch millis
	 * @param bucketSeconds   the moment the prices belong to, unix seconds - the guide table's own
	 *                        {@code %LAST_UPDATE%} since addendum L (L7), not the revision's save time
	 * @param revId           the wiki revision the prices came from, or 0 when they came from somewhere else
	 */
	public PriceMap(final Map<Integer, PricePoint> points, final long fetchedAtMillis, final long bucketSeconds,
		final long revId)
	{
		this(points, fetchedAtMillis, bucketSeconds, revId, PriceMapDto.SCHEMA);
	}

	/**
	 * @param points          item id to its two sides; copied, and null keys or values are dropped
	 * @param fetchedAtMillis when the response arrived, epoch millis
	 * @param bucketSeconds   the moment the prices belong to, unix seconds - the guide table's own
	 *                        {@code %LAST_UPDATE%} since addendum L (L7), not the revision's save time
	 * @param revId           the wiki revision the prices came from, or 0 when they came from somewhere else
	 * @param schema          the document shape this map was READ from ({@link PriceMapDto#schema}); 0 when the
	 *                        file predates the marker
	 */
	public PriceMap(final Map<Integer, PricePoint> points, final long fetchedAtMillis, final long bucketSeconds,
		final long revId, final int schema)
	{
		final Map<Integer, PricePoint> copy = new HashMap<>();
		if (points != null)
		{
			for (final Map.Entry<Integer, PricePoint> entry : points.entrySet())
			{
				if (entry.getKey() != null && entry.getValue() != null)
				{
					copy.put(entry.getKey(), entry.getValue());
				}
			}
		}

		this.points = Collections.unmodifiableMap(copy);
		this.fetchedAtMillis = fetchedAtMillis;
		this.bucketSeconds = bucketSeconds;
		this.revId = revId;
		this.schema = schema;
	}

	/**
	 * The item's prices, or null when this response did not carry that id.
	 */
	public PricePoint get(final int id)
	{
		return points.get(id);
	}

	public int size()
	{
		return points.size();
	}

	public boolean isEmpty()
	{
		return points.isEmpty();
	}

	/**
	 * When the response arrived, epoch millis; 0 when this map came from nowhere ({@link #EMPTY}).
	 */
	public long fetchedAtMillis()
	{
		return fetchedAtMillis;
	}

	/**
	 * The moment these prices belong to, in unix seconds: since addendum L, the table's own
	 * {@code %LAST_UPDATE%} - the instant Jagex stamped this guide table, which is where the DAY on the label
	 * comes from (L7). 0 when there is none.
	 *
	 * <p>It is deliberately not the revision's publication time any more. The wiki's price bot saves day D's
	 * table at a random hour of day D and human editors save the previous day's table under the next day's
	 * timestamp (L-C, L-E), so a publication time names the wrong day often enough to flip the sign on 3-5 % of
	 * the user's rows. It is also NOT what decides a refetch: a revision is immutable, so staleness is
	 * {@link #fetchedAtMillis()} against the six-hour rule (L10).
	 *
	 * @see #dataDay()
	 */
	public long bucketSeconds()
	{
		return bucketSeconds;
	}

	/**
	 * The UTC calendar day these prices belong to, or null when the map carries no marker ({@link #EMPTY}, or a
	 * baseline file from before addendum K).
	 *
	 * <p>This is the value the status line and the tooltip print - "1d vs 07 Sep" (L7) - and the one a caller
	 * compares against {@code MovementWindow.targetDate(anchorDay)} to see whether a stored baseline is still
	 * the right day. UTC always (L9): every date in the selection maths is a UTC date, so a local-zone date would
	 * put a user east of Greenwich a whole day out. What the marker itself rests on - the bot running after the
	 * day's rollover rather than any midnight stamp - is spelled out on {@code GuideSnapshot.dataDay()}.
	 */
	public LocalDate dataDay()
	{
		return bucketSeconds <= 0L ? null : RevisionRef.dayOf(bucketSeconds);
	}

	/**
	 * The wiki revision of {@code Module:GEPrices/data.json} these prices are the content of, or 0 when they
	 * came from anywhere else - which in practice means a baseline file written before addendum K, when the map
	 * held real-time TRADE averages. Those produced the absurd figures the addendum exists to fix, so
	 * {@code PriceStore.deleteStaleFiles()} treats a stored 0 as the marker to sweep the file away.
	 */
	public long revId()
	{
		return revId;
	}

	/**
	 * The document shape this map was read from: {@link PriceMapDto#SCHEMA} for anything built in memory or
	 * written by this build, and 0 for a file written before the marker existed.
	 *
	 * <p>What it buys, which {@link #revId()} cannot: a baseline from an older build carries a revision id just
	 * like a current one, but its fields may no longer MEAN what this build reads them as -
	 * {@link PriceMapDto#bucketSeconds} changed meaning between addendum K and addendum L without changing
	 * shape. {@code PriceService.guideOrEmpty} refuses a map below the current schema, so such a file is
	 * refetched instead of being believed.
	 */
	public int schema()
	{
		return schema;
	}

	/**
	 * Every point, unmodifiable.
	 */
	public Map<Integer, PricePoint> points()
	{
		return points;
	}

	/**
	 * This map in its on-disk form, stamped with the shape it holds ({@link #schema()}) rather than with the
	 * current constant: a map built in memory already carries {@link PriceMapDto#SCHEMA}, and re-stamping one
	 * read from an older file would claim its fields mean what this build reads them as, which is the very
	 * thing the marker exists to deny.
	 */
	public PriceMapDto toDto()
	{
		final PriceMapDto dto = new PriceMapDto();
		dto.schema = schema;
		dto.fetchedAtMillis = fetchedAtMillis;
		dto.bucketSeconds = bucketSeconds;
		dto.revId = revId;
		dto.points = new HashMap<>(points.size());

		for (final Map.Entry<Integer, PricePoint> entry : points.entrySet())
		{
			final PricePoint point = entry.getValue();
			dto.points.put(
				Integer.toString(entry.getKey()),
				new long[]{PriceMapDto.encode(point.high()), PriceMapDto.encode(point.low())});
		}

		return dto;
	}

	/**
	 * Reads a map back from its on-disk form. Every kind of damage a hand-edited or truncated file can carry -
	 * a null dto, a null point map, a key that is not a number, a null or short value array - is SKIPPED rather
	 * than thrown, because this runs on the store's executor at start-up and a stale price file must never
	 * stop the panel from opening (design D9, fail soft).
	 *
	 * @param dto the parsed file, or null
	 * @return the map, {@link #EMPTY} when the dto carried nothing usable
	 */
	public static PriceMap fromDto(final PriceMapDto dto)
	{
		if (dto == null)
		{
			return EMPTY;
		}

		final Map<Integer, PricePoint> points = new HashMap<>();
		if (dto.points != null)
		{
			for (final Map.Entry<String, long[]> entry : dto.points.entrySet())
			{
				final long[] sides = entry.getValue();
				if (entry.getKey() == null || sides == null || sides.length < 2)
				{
					continue;
				}

				final Integer id = parseId(entry.getKey());
				if (id == null)
				{
					continue;
				}

				points.put(id, new PricePoint(PriceMapDto.decode(sides[0]), PriceMapDto.decode(sides[1])));
			}
		}

		return new PriceMap(points, dto.fetchedAtMillis, dto.bucketSeconds, dto.revId, dto.schema);
	}

	private static Integer parseId(final String key)
	{
		try
		{
			return Integer.valueOf(key.trim());
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Value equality over the prices and their provenance. {@link #schema()} is deliberately NOT part of it: it
	 * names the build that wrote a FILE, not the prices in it, and two maps holding the same revision's numbers
	 * are the same map whoever's bytes they came out of.
	 */
	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof PriceMap))
		{
			return false;
		}

		final PriceMap other = (PriceMap) o;
		return fetchedAtMillis == other.fetchedAtMillis
			&& bucketSeconds == other.bucketSeconds
			&& revId == other.revId
			&& points.equals(other.points);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(points, fetchedAtMillis, bucketSeconds, revId);
	}

	@Override
	public String toString()
	{
		return "PriceMap{points=" + points.size()
			+ ", fetchedAtMillis=" + fetchedAtMillis
			+ ", bucketSeconds=" + bucketSeconds
			+ ", revId=" + revId
			+ ", schema=" + schema + '}';
	}
}
