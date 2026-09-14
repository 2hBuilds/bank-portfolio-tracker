package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

/**
 * {@link PriceMap} (contract C4, extended by K11): a set of prices as an immutable index, and the round trip
 * through the stock Gson that {@code PriceStore} writes to disk.
 *
 * <p>The immutability tests are not ceremony: a map is built on an OkHttp thread, kept by the service and read
 * while rows are computed on the executor, so anything that could mutate it after publication is a data race.
 *
 * <p>Addendum K adds {@code revId} - the wiki revision of {@code Module:GEPrices/data.json} the prices came
 * from. It has to survive disk (it is what the status line and the tooltip name) and it has to be ABSENT-safe:
 * a baseline file written by the first build has no such key, and reading it back as 0 is exactly what
 * {@code PriceStore.deleteStaleFiles()} uses to recognise a pre-K trade map and sweep it away.
 *
 * <p>The round trip is tested against a plain {@code new Gson()} - which a TEST may construct, while main code
 * may not (hub blocker C46) - because the injected client Gson is a stock builder for these types.
 */
public class PriceMapTest
{
	private static Map<Integer, PricePoint> points()
	{
		final Map<Integer, PricePoint> points = new HashMap<>();
		points.put(4151, new PricePoint(1_600_000L, 1_500_000L));
		points.put(2, new PricePoint(null, 180L));
		points.put(995, new PricePoint(1L, null));
		return points;
	}

	/** 2026-09-07T19:55:12Z, when revision 15333448 of the guide table was published (calibration doc). */
	private static final long REVISION_SECONDS = 1_788_810_912L;
	/** 2026-09-07T19:45:22Z - that revision's own {@code %LAST_UPDATE%}, the day marker L7 stores here. */
	private static final long DATA_SECONDS = 1_788_810_322L;
	private static final long REV_ID = 15_333_448L;

	@Test
	public void aMapAnswersItsPointsAndStamps()
	{
		final PriceMap map = new PriceMap(points(), 1_700_000_000_000L, 0L);

		assertEquals(3, map.size());
		assertFalse(map.isEmpty());
		assertEquals(1_700_000_000_000L, map.fetchedAtMillis());
		assertEquals("a map with no revision behind it carries no moment", 0L, map.bucketSeconds());
		assertEquals("and no revision id", 0L, map.revId());
		assertEquals(new PricePoint(1_600_000L, 1_500_000L), map.get(4151));
	}

	/**
	 * A guide baseline: both sides of every point hold the same figure (the guide price is one number, K1), the
	 * moment is the wiki revision's publication time and the revision id rides along.
	 */
	@Test
	public void aGuideBaselineCarriesTheRevisionItWasReadFrom()
	{
		final Map<Integer, PricePoint> points = new HashMap<>();
		points.put(658, new PricePoint(1124L, 1124L));

		final PriceMap map = new PriceMap(points, 1_788_909_600_123L, REVISION_SECONDS, REV_ID);

		assertEquals(REV_ID, map.revId());
		assertEquals(REVISION_SECONDS, map.bucketSeconds());
		assertEquals(1_788_909_600_123L, map.fetchedAtMillis());
		assertEquals("Green hat's guide price a day before the live session",
			Long.valueOf(1124L), map.get(658).mid());
	}

	/**
	 * Addendum L (L7) changed what {@code bucketSeconds} holds: the table's own {@code %LAST_UPDATE%} rather
	 * than the revision's save time, because a human maintenance edit republishes the previous day's prices
	 * under a later timestamp (L-E). {@link PriceMap#dataDay()} is the DAY the status line and the tooltip print
	 * and the day a caller compares against {@code MovementWindow.targetDate(anchorDay)} to see whether a stored
	 * baseline is still the right one.
	 */
	@Test
	public void theStoredMomentIsADayMarkerAndReadsBackAsAUtcDate()
	{
		final Map<Integer, PricePoint> points = new HashMap<>();
		points.put(658, new PricePoint(1124L, 1124L));

		assertEquals(LocalDate.of(2026, 9, 7),
			new PriceMap(points, 1L, DATA_SECONDS, REV_ID).dataDay());
		assertNull("a map with no marker has no day", PriceMap.EMPTY.dataDay());
		assertNull(new PriceMap(points, 1L, 0L).dataDay());

		// Midnight UTC is the day boundary wherever the client runs (L9); this box is four hours behind it.
		assertEquals(LocalDate.of(2026, 9, 9), new PriceMap(points, 1L, 1_788_912_000L).dataDay());
		assertEquals(LocalDate.of(2026, 9, 8), new PriceMap(points, 1L, 1_788_911_999L).dataDay());
	}

	/** The three-argument constructor is the "no revision behind this" case, and must say so, not guess. */
	@Test
	public void theShortConstructorMeansNoRevision()
	{
		assertEquals(0L, new PriceMap(points(), 5L, 3600L).revId());
		assertEquals(new PriceMap(points(), 5L, 3600L), new PriceMap(points(), 5L, 3600L, 0L));
	}

	/**
	 * A missing id is the ORDINARY case for a bucket map: one {@code /1h} bucket covered only 63-74 % of the
	 * items {@code /latest} knows (research C1/C10), so "no baseline for this item" must read as null, not as
	 * an exception and not as a zero.
	 */
	@Test
	public void anIdTheResponseDidNotCarryIsNull()
	{
		final PriceMap map = new PriceMap(points(), 1L, 0L);
		assertNull(map.get(4152));
		assertNull(map.get(0));
		assertNull(map.get(-1));
	}

	@Test
	public void theMapIsCopiedAtConstructionAndHandedOutUnmodifiable()
	{
		final Map<Integer, PricePoint> source = points();
		final PriceMap map = new PriceMap(source, 1L, 0L);

		source.put(9999, new PricePoint(5L, 5L));
		source.remove(4151);
		assertEquals("the map does not follow the caller's map", 3, map.size());
		assertNull(map.get(9999));
		assertEquals(new PricePoint(1_600_000L, 1_500_000L), map.get(4151));

		try
		{
			map.points().put(1, PricePoint.EMPTY);
			throw new AssertionError("points() must be unmodifiable");
		}
		catch (UnsupportedOperationException expected)
		{
			// the point of the test
		}
	}

	@Test
	public void nullKeysAndValuesAreDroppedRatherThanStored()
	{
		final Map<Integer, PricePoint> source = new HashMap<>();
		source.put(4151, new PricePoint(10L, 8L));
		source.put(null, new PricePoint(1L, 1L));
		source.put(2, null);

		final PriceMap map = new PriceMap(source, 1L, 0L);
		assertEquals(1, map.size());
		assertNull(map.get(2));
	}

	@Test
	public void aNullSourceMapIsAnEmptyMap()
	{
		final PriceMap map = new PriceMap(null, 5L, 3600L);
		assertTrue(map.isEmpty());
		assertEquals(0, map.size());
		assertEquals(5L, map.fetchedAtMillis());
		assertEquals(3600L, map.bucketSeconds());
	}

	@Test
	public void emptyIsTheNothingFetchedYetMap()
	{
		assertTrue(PriceMap.EMPTY.isEmpty());
		assertEquals(0, PriceMap.EMPTY.size());
		assertEquals(0L, PriceMap.EMPTY.fetchedAtMillis());
		assertEquals(0L, PriceMap.EMPTY.bucketSeconds());
		assertEquals(0L, PriceMap.EMPTY.revId());
		assertNull(PriceMap.EMPTY.get(4151));
	}

	// ---------------------------------------------------------------- the on-disk form

	@Test
	public void theDtoRoundTripsThroughStockGson()
	{
		final PriceMap map = new PriceMap(points(), 1_700_000_000_000L, REVISION_SECONDS, REV_ID);
		final Gson gson = new Gson();

		final String json = gson.toJson(map.toDto());
		final PriceMap back = PriceMap.fromDto(gson.fromJson(json, PriceMapDto.class));

		assertEquals("the whole value survives disk", map, back);
		assertEquals(3, back.size());
		assertEquals(1_700_000_000_000L, back.fetchedAtMillis());
		assertEquals(REVISION_SECONDS, back.bucketSeconds());
		assertEquals("the revision id is what tells a later launch this file is from the guide era (K11)",
			REV_ID, back.revId());
		assertTrue("and it must actually be written, not just defaulted", json.contains("15333448"));
	}

	/**
	 * B028, the migration lever. Every document this build writes says which SHAPE it is, because a revision id
	 * cannot: addendum K and addendum L both write one, and yet {@code bucketSeconds} means the revision's save
	 * time in a K-era file and the table's own {@code %LAST_UPDATE%} in an L-era one. A file written before the
	 * marker existed reads as 0 - and that absence IS the marker, which is why the number goes in before v1.
	 */
	@Test
	public void aSavedBaselineCarriesItsSchemaAndAFileWrittenBeforeTheMarkerReadsAsZero()
	{
		final Gson gson = new Gson();
		final PriceMap map = new PriceMap(points(), 1_700_000_000_000L, REVISION_SECONDS, REV_ID);
		assertEquals("a map built in memory is this build's shape", PriceMapDto.SCHEMA, map.schema());

		final String json = gson.toJson(map.toDto());
		assertTrue("and the number has to be written, not defaulted on the way back in",
			json.contains("\"schema\":" + PriceMapDto.SCHEMA));
		assertEquals(PriceMapDto.SCHEMA, PriceMap.fromDto(gson.fromJson(json, PriceMapDto.class)).schema());

		final PriceMap older = PriceMap.fromDto(gson.fromJson(
			"{\"fetchedAtMillis\":1788909600123,\"bucketSeconds\":1788822000,\"revId\":15333448,"
				+ "\"points\":{\"658\":[1124,1124]}}", PriceMapDto.class));
		assertEquals("a key the file has not got is what marks the build that wrote it", 0, older.schema());
		assertEquals("and it still parses WHOLE - refusing it is the service's call, not the reader's",
			1, older.size());
		assertEquals(15_333_448L, older.revId());
	}

	/**
	 * The reason for the sentinel: a side the wiki did not give must still be missing after the file has been
	 * written and read, whatever the Gson instance does with nulls (the injected client Gson drops them -
	 * playbook 7.8).
	 */
	@Test
	public void aMissingSideSurvivesTheRoundTripAsNull()
	{
		final Gson gson = new Gson();
		final Map<Integer, PricePoint> source = new HashMap<>();
		source.put(2, new PricePoint(null, 180L));
		source.put(3, new PricePoint(180L, null));
		source.put(4, new PricePoint(null, null));

		final PriceMap back = PriceMap.fromDto(
			gson.fromJson(gson.toJson(new PriceMap(source, 7L, 0L).toDto()), PriceMapDto.class));

		assertNull(back.get(2).high());
		assertEquals(Long.valueOf(180L), back.get(2).low());
		assertEquals(Long.valueOf(180L), back.get(3).high());
		assertNull(back.get(3).low());
		assertTrue(back.get(4).isEmpty());
	}

	@Test
	public void aZeroPriceIsKeptWhileTheSentinelReadsAsAbsent()
	{
		final Map<Integer, PricePoint> source = new HashMap<>();
		source.put(2, new PricePoint(0L, 0L));

		final PriceMap back = PriceMap.fromDto(new PriceMap(source, 1L, 0L).toDto());
		assertEquals(Long.valueOf(0L), back.get(2).high());
		assertEquals(Long.valueOf(0L), back.get(2).low());

		assertEquals(PriceMapDto.ABSENT, PriceMapDto.encode(null));
		assertEquals(Long.valueOf(0L), PriceMapDto.decode(0L));
		assertNull(PriceMapDto.decode(PriceMapDto.ABSENT));
		assertNull("anything negative reads as absent", PriceMapDto.decode(-99L));
	}

	/**
	 * A price file is read on the executor at start-up (C22): every kind of damage a hand-edited or truncated
	 * file can carry has to be skipped, because a stale price file must never stop the panel opening (D9).
	 */
	@Test
	public void damagedDtoContentIsSkippedRatherThanThrown()
	{
		final PriceMapDto dto = new PriceMapDto();
		dto.fetchedAtMillis = 12L;
		dto.bucketSeconds = 3600L;
		dto.points = new HashMap<>();
		dto.points.put("4151", new long[]{10L, 8L});
		dto.points.put("not-an-id", new long[]{1L, 1L});
		dto.points.put("7", null);
		dto.points.put("8", new long[]{5L});
		dto.points.put("9", new long[0]);

		final PriceMap map = PriceMap.fromDto(dto);
		assertEquals(1, map.size());
		assertEquals(new PricePoint(10L, 8L), map.get(4151));
		assertEquals(12L, map.fetchedAtMillis());
		assertEquals(3600L, map.bucketSeconds());
	}

	@Test
	public void aNullOrEmptyDtoIsTheEmptyMap()
	{
		assertSame(PriceMap.EMPTY, PriceMap.fromDto(null));

		final PriceMapDto dto = new PriceMapDto();
		final PriceMap map = PriceMap.fromDto(dto);
		assertTrue("a dto with no points map at all still reads", map.isEmpty());
		assertEquals(0L, map.fetchedAtMillis());
	}

	/**
	 * A file written before some future field existed must still parse - the back-compat rule every persisted
	 * class in this workspace carries.
	 */
	@Test
	public void jsonWithOnlyThePointsParses()
	{
		final PriceMap map = PriceMap.fromDto(
			new Gson().fromJson("{\"points\":{\"4151\":[100,90]}}", PriceMapDto.class));

		assertEquals(1, map.size());
		assertEquals(Long.valueOf(95L), map.get(4151).mid());
		assertEquals("a file with no stamp reads as never fetched", 0L, map.fetchedAtMillis());
		assertEquals(0L, map.bucketSeconds());
		assertEquals(0L, map.revId());
	}

	/**
	 * The exact shape the FIRST build wrote: {@code fetchedAtMillis}, {@code bucketSeconds} (an hourly trade
	 * bucket) and {@code points}, with no {@code revId} key at all. It must parse, and it must read back as
	 * revision 0 - that is the signal {@code PriceStore.deleteStaleFiles()} acts on to sweep a trade-era
	 * baseline away before it can show "+12845.5 %" again.
	 */
	@Test
	public void aBaselineFileFromBeforeAddendumKParsesAndReadsAsRevisionZero()
	{
		final PriceMap map = PriceMap.fromDto(new Gson().fromJson(
			"{\"fetchedAtMillis\":1788909600123,\"bucketSeconds\":1788822000,\"points\":{\"658\":[11,-1]}}",
			PriceMapDto.class));

		assertEquals(1, map.size());
		assertEquals("the 1h bucket that produced the absurd figure: nine units at an average high of 11",
			Long.valueOf(11L), map.get(658).mid());
		assertEquals(1_788_822_000L, map.bucketSeconds());
		assertEquals("no revId key means revision 0, which marks the file as pre-addendum-K", 0L, map.revId());
	}

	@Test
	public void theMapCompares()
	{
		assertEquals(new PriceMap(points(), 1L, 0L), new PriceMap(points(), 1L, 0L));
		assertEquals(new PriceMap(points(), 1L, 0L).hashCode(), new PriceMap(points(), 1L, 0L).hashCode());
		assertNotEquals(new PriceMap(points(), 2L, 0L), new PriceMap(points(), 1L, 0L));
		assertNotEquals(new PriceMap(points(), 1L, 3600L), new PriceMap(points(), 1L, 0L));
		assertNotEquals("two maps from different revisions are different maps",
			new PriceMap(points(), 1L, 0L, REV_ID), new PriceMap(points(), 1L, 0L, 0L));
		assertEquals(new PriceMap(points(), 1L, 0L, REV_ID).hashCode(),
			new PriceMap(points(), 1L, 0L, REV_ID).hashCode());
		assertNotEquals(PriceMap.EMPTY, new PriceMap(points(), 1L, 0L));
	}
}
