package com.bankpricemovement;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One revision of the wiki's Grand Exchange guide-price table: the revision it came from, the moment that
 * revision was SAVED, the DAY its prices belong to, the moment we read it, and the whole name-to-gp map
 * (contract K12, rewritten by addendum L - L7, L8).
 *
 * <p><b>The day marker, and why it is not the timestamp</b> (L7, evidence L-E). The revision timestamp says
 * when someone pressed save; it does not say which Jagex day the prices in the body are. Human maintenance
 * edits - 24 of 194 in-window revisions, and once the bot itself - republish the PREVIOUS day's table under the
 * next day's timestamp (revision 15329323, Riblet15, saved 2026-09-03T06:46Z, carries
 * {@code %LAST_UPDATE_F%} "02 September 2026 07:21:23"). The table's own {@code %LAST_UPDATE%} field is the
 * authoritative marker and was right on 38 of 38 sampled bodies against 34 of 38 for the timestamp, so
 * {@link #dataSeconds()} - not {@link #revisionSeconds()} - is what the status line, the tooltip and the L5 day
 * check read. {@link #revisionSeconds()} stays for cache identity beside {@link #revId()}.
 *
 * <p><b>Why the map is keyed by NAME.</b> {@code Module:GEPrices/data.json} is a wiki page written for wiki
 * templates, so its keys are item names ({@code {"Green hat": 1086, "Abyssal whip": 799012, ...}}, 4,566 of them,
 * 126 KB - {@code docs/research/bank-price-movement-calibration-2026-09-08.md}). Turning that into the id-keyed
 * {@link PriceMap} the rows are computed from needs the id-to-wiki-name table
 * ({@code prices.runescape.wiki/api/v1/osrs/mapping}, contract K3), which is the caller's business and is
 * refreshed on a completely different cadence (weekly, against six-hourly for the history - L10). Keeping this
 * type name-keyed means one fetched revision can be re-projected onto ids whenever the mapping changes, without
 * going back to the network.
 *
 * <p><b>Why every key goes through {@link #key(String)}</b> (L8, evidence L-F). The wiki case-renamed 81 keys on
 * 2026-08-12..20 ("3rd age amulet" became "3rd Age amulet"), so a byte-exact lookup silently loses three of the
 * user's bank items on every window of 30 days or more - the older revision spells them the other way.
 * Lower-casing with {@link Locale#ROOT} after an NFC normalise and a whitespace collapse recovers all of them and
 * was collision-free on all six sampled revisions. Both the parsed table and every lookup are keyed through the
 * one method, so a caller cannot accidentally compare a raw name against a folded key.
 *
 * <p>Immutable and safe to share across threads: the map is built on an OkHttp dispatcher thread, handed to the
 * service's executor and read while rows are computed, so it is copied once here and handed out
 * {@link Collections#unmodifiableMap unmodifiable}.
 */
public final class GuideSnapshot
{
	private static final Logger log = LoggerFactory.getLogger(GuideSnapshot.class);

	/** The "no guide table yet" snapshot: no revision, no day, no stamp, no prices. Immutable, safe to share. */
	public static final GuideSnapshot EMPTY = new GuideSnapshot(0L, 0L, 0L, 0L, Collections.emptyMap());

	/**
	 * The table's "this item has no price" value (L8 c). Three keys carried exactly 2,147,483,646 on the sampled
	 * revisions - "3rd Age axe", "3rd Age druidic robe top", "3rd Age pickaxe" - one short of
	 * {@link Integer#MAX_VALUE}, which is the wiki's way of writing "unpriced" in a field that must hold a
	 * number. Treated as a threshold rather than an equality so a future 2,147,483,647 cannot slip a two-billion
	 * gp price onto a row.
	 */
	public static final long NO_PRICE = 2_147_483_646L;

	/**
	 * Anything Java calls whitespace, plus U+00A0. {@code \s} does NOT match a non-breaking space, and a wiki
	 * name saved with one would otherwise never match the same name typed with an ordinary space.
	 */
	private static final Pattern WHITESPACE = Pattern.compile("[\\s\\u00A0]+");

	private final long revId;
	private final long revisionSeconds;
	private final long dataSeconds;
	private final long fetchedAtMillis;
	private final Map<String, Long> pricesByName;

	/**
	 * @param revId           the wiki revision id this table came from; 0 only for {@link #EMPTY}
	 * @param revisionSeconds when that revision was SAVED, unix seconds (the API's {@code timestamp}); cache
	 *                        identity only (L7)
	 * @param dataSeconds     the table's own {@code %LAST_UPDATE%}, unix seconds - the day marker every label
	 *                        and every day comparison uses (L7)
	 * @param fetchedAtMillis when we read it, epoch millis - the value the six-hourly refetch rule compares (L10)
	 * @param pricesByName    wiki item name to guide price in gp. Copied; keys are folded through
	 *                        {@link #key(String)}, null keys and values are dropped, and a value at or above
	 *                        {@link #NO_PRICE} is dropped because it is the table's "unpriced" marker, not a price
	 */
	public GuideSnapshot(final long revId, final long revisionSeconds, final long dataSeconds,
		final long fetchedAtMillis, final Map<String, Long> pricesByName)
	{
		final Map<String, Long> copy = new HashMap<>();
		if (pricesByName != null)
		{
			for (final Map.Entry<String, Long> entry : pricesByName.entrySet())
			{
				final String folded = key(entry.getKey());
				final Long price = entry.getValue();
				if (folded == null || folded.isEmpty() || price == null || price >= NO_PRICE)
				{
					continue;
				}

				final Long clash = copy.putIfAbsent(folded, price);
				if (clash != null && !clash.equals(price))
				{
					// Never seen: the fold was collision-free on all six sampled revisions (L-F). If the wiki
					// ever ships two spellings of one name with two prices, one of them wins arbitrarily and
					// this line is the only record of it - a wrong-by-a-rename row is still better than a throw
					// inside a fetch callback.
					log.debug("bank-portfolio-tracker: revision {} folds two spellings of \"{}\" onto {} gp (kept {})",
						revId, folded, price, clash);
				}
			}
		}

		this.revId = revId;
		this.revisionSeconds = revisionSeconds;
		this.dataSeconds = dataSeconds;
		this.fetchedAtMillis = fetchedAtMillis;
		this.pricesByName = Collections.unmodifiableMap(copy);
	}

	/**
	 * The wiki revision id this table is the content of. Never 0 for a fetched snapshot -
	 * {@code GuidePriceClient} drops a revision it could not identify rather than report one.
	 */
	public long revId()
	{
		return revId;
	}

	/**
	 * When the revision was SAVED, in unix seconds. Cache identity, not a day marker: a body whose
	 * {@link #dataSeconds()} disagrees with it by up to two days is an ordinary human edit (L-E), which is why
	 * nothing user-facing prints this value any more (L7).
	 */
	public long revisionSeconds()
	{
		return revisionSeconds;
	}

	/**
	 * The table's own {@code %LAST_UPDATE%} in unix seconds - the moment Jagex stamped the guide prices in this
	 * body. This is the authoritative day marker (L7): the status line and the tooltip print its DAY, and L5's
	 * "is this really day T's table?" check compares {@link #dataDay()} against the target date.
	 */
	public long dataSeconds()
	{
		return dataSeconds;
	}

	/**
	 * The UTC calendar day these prices belong to, or null when the body carried no marker ({@link #EMPTY}).
	 * UTC always (L9) - the guide price steps once a day and every date in this plugin's selection maths is a UTC
	 * date, so a local-zone date would put a user east of Greenwich a whole day out.
	 *
	 * <p><b>What this rests on, exactly.</b> Not a midnight stamp: the Jagex rollover was observed between 00:47
	 * and 02:06 UTC (L-A), and {@code %LAST_UPDATE%} is not the rollover at all - it is the wiki bot's own capture
	 * moment for that step, which lands anywhere in the day (02:11-22:20 UTC, L-C; the five stored baselines here
	 * read 03:35, 07:21, 09:22, 13:00 and 13:51 UTC, each a few minutes before its revision's save time). So the
	 * UTC date of the marker names the right Jagex day only while the bot runs AFTER the rollover - a margin of
	 * about five minutes between the latest observed rollover and the earliest observed bot run. A body whose
	 * marker falls outside the window its own save time allows is already refused upstream
	 * ({@code GuidePriceClient}, L7); a bot run that slipped in front of a late rollover would not be, and would
	 * date its table one day late. Nothing detects that case today - see {@code PriceService.deriveAnchorDay}.
	 */
	public LocalDate dataDay()
	{
		return dataSeconds <= 0L ? null : RevisionRef.dayOf(dataSeconds);
	}

	/**
	 * When this snapshot was read from the network, in epoch millis. The index and the newest table are refetched
	 * when this is more than six hours old (L10), because the table only changes about once a day.
	 */
	public long fetchedAtMillis()
	{
		return fetchedAtMillis;
	}

	/**
	 * The guide price for one wiki item name, or null when the table does not list it or lists it as unpriced.
	 * The name is folded through {@link #key(String)} first, so the caller may pass the {@code /mapping} name,
	 * the item composition name, or the table's own spelling in any case (L8).
	 */
	public Long get(final String name)
	{
		final String folded = key(name);
		return folded == null ? null : pricesByName.get(folded);
	}

	/** Every folded-name-to-gp entry, unmodifiable. Keys are {@link #key(String)} output, not wiki spelling. */
	public Map<String, Long> pricesByName()
	{
		return pricesByName;
	}

	public int size()
	{
		return pricesByName.size();
	}

	public boolean isEmpty()
	{
		return pricesByName.isEmpty();
	}

	/**
	 * The one name-folding rule (L8): NFC normalise, collapse every run of whitespace to a single space, trim,
	 * then lower-case with {@link Locale#ROOT}.
	 *
	 * <p>Each step earns its place. NFC because the wiki serves UTF-8 that may spell an accent either
	 * pre-composed or as a combining pair and the two are different Strings. The whitespace collapse because a
	 * double space between words is invisible in a wiki edit box. {@link Locale#ROOT} rather than the default
	 * locale because a Turkish user's JVM lower-cases "I" to a dotless "&#x131;", which would fold "Ice gloves"
	 * onto a key no table has - the classic locale bug, and this method runs on whatever machine the plugin is
	 * installed on.
	 *
	 * <p>Package-private: it is an internal key, and letting the panel or the service invent its own folding is
	 * exactly the drift L8 exists to prevent.
	 *
	 * @param name any name, including null
	 * @return the folded key, or null when {@code name} is null
	 */
	static String key(final String name)
	{
		if (name == null)
		{
			return null;
		}

		final String composed = Normalizer.normalize(name, Normalizer.Form.NFC);
		final String collapsed = WHITESPACE.matcher(composed).replaceAll(" ").trim();
		return collapsed.toLowerCase(Locale.ROOT);
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}

		if (!(o instanceof GuideSnapshot))
		{
			return false;
		}

		final GuideSnapshot other = (GuideSnapshot) o;
		return revId == other.revId
			&& revisionSeconds == other.revisionSeconds
			&& dataSeconds == other.dataSeconds
			&& fetchedAtMillis == other.fetchedAtMillis
			&& pricesByName.equals(other.pricesByName);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(revId, revisionSeconds, dataSeconds, fetchedAtMillis, pricesByName);
	}

	@Override
	public String toString()
	{
		return "GuideSnapshot{revId=" + revId
			+ ", dataDay=" + dataDay()
			+ ", revisionSeconds=" + revisionSeconds
			+ ", fetchedAtMillis=" + fetchedAtMillis
			+ ", names=" + pricesByName.size() + '}';
	}
}
