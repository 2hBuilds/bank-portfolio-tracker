package com.bankpricemovement;

import java.text.ParseException;
import java.util.Arrays;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/**
 * The three quick bands under the band button, carried as one immutable value the way {@link ViewOptions} carries
 * the view switches (addendum Z, line Z1): three DISTINCT POSITIVE gp amounts, always held ASCENDING.
 *
 * <p>Before addendum Z these were two constants in the panel - {@code PRESET_MINS} and {@code PRESET_LABELS} - so
 * a bank whose cheapest interesting stack is worth ten million had three chips it never pressed. They are now a
 * config line the user edits in the boxes at the foot of the gear menu, and this value is what travels between
 * that line, those boxes, the fold's chips and the dev bridge.
 *
 * <p>Pure on purpose, like {@link MovementMath}: nothing here touches Swing, the client or the config, so every
 * rule below - what a typed line means, what is refused, how a band is written back out - is testable without a
 * client.
 *
 * <p><b>The invariant is the type.</b> Only {@link #of(long, long, long)} and {@link #parse(String)} build one,
 * and neither can produce a trio that is out of order, holds a duplicate or holds a zero. A duplicate would draw
 * two identical chips of which only one could ever light; a zero or a negative is not a price (the same rule
 * {@code MovementMath.parseGp} applies to the Min / Max fields).
 */
public final class BandPresets
{
	/**
	 * What the plugin has always offered and what "Reset to default" restores: 100k, 1m, 10m - the trio the fold
	 * drew from {@code PRESET_MINS} up to addendum W, so a profile that never touches the new config line sees
	 * exactly today's chips.
	 */
	public static final BandPresets DEFAULT = new BandPresets(100_000L, 1_000_000L, 10_000_000L);

	/** How many bands a trio holds. Three chips beside "All" is what the 213 px fold has room for. */
	private static final int BANDS = 3;

	/**
	 * What separates one band from the next in a typed line: a run of whitespace and slashes, and a comma that is
	 * NOT a thousands grouping comma.
	 *
	 * <p>The three alternatives, in order: {@code [\s/]} is always a separator; {@code (?<![0-9]),} is a comma that
	 * does not follow a digit ("100k, 1m"); {@code ,(?![0-9]{3}(?![0-9]))} is a comma that does not introduce a
	 * group of exactly three digits ("1000,2000"). What is left - a comma between a digit and exactly three digits -
	 * is the grouping comma of "1,000", which {@code MovementMath.parseGp} reads as part of the number and which
	 * {@link #format()} itself writes for any band under 10,000 ({@code QuantityFormatter} spells 1,000 as
	 * "1,000", not as "1k"). Were the split blind to it, {@code parse(of(1000, 2000, 3000).format())} - six parts -
	 * would refuse the very line this class had just written.
	 *
	 * <p>The trailing {@code +} makes a whole run of separators one match, so ", " does not yield an empty band
	 * between the comma and the space.
	 */
	private static final Pattern SEPARATORS = Pattern.compile("(?:[\\s/]|(?<![0-9]),|,(?![0-9]{3}(?![0-9])))+");

	private final long small;
	private final long middle;
	private final long large;

	/**
	 * The only constructor, and private: {@link #of(long, long, long)} is the one door, so no caller can build a
	 * trio that breaks the invariant. The arguments must already be ascending, distinct and positive.
	 */
	private BandPresets(final long small, final long middle, final long large)
	{
		this.small = small;
		this.middle = middle;
		this.large = large;
	}

	/**
	 * Reads the config line, a dev verb's argument or the three boxes joined together (Z1): the text is split on
	 * commas, slashes and whitespace, each part is read by {@link MovementMath#parseGp(String)} - so "100k",
	 * "1.5m", "2b" and "1,000" all work - and the trio is sorted.
	 *
	 * <p>Answers NULL rather than throwing, because every caller of this treats a line it cannot read the same
	 * way: keep the last good presets (a stored string that does not parse reads as {@link #DEFAULT}, never a
	 * crash - Z1). The three refusals are: not exactly three values, a value that is not positive, and two values
	 * that are equal.
	 *
	 * @param text what the user typed, or what the config holds; null and blank are refusals like any other
	 * @return the trio held ascending, or null when the text names no valid one
	 */
	@Nullable
	public static BandPresets parse(final String text)
	{
		if (text == null)
		{
			return null;
		}

		final String trimmed = text.trim();
		if (trimmed.isEmpty())
		{
			return null;
		}

		final long[] values = new long[BANDS];
		int found = 0;
		for (final String part : SEPARATORS.split(trimmed))
		{
			if (part.isEmpty())
			{
				// A separator run at the very start of the text yields one empty piece; it is not a band.
				continue;
			}

			if (found == BANDS)
			{
				// A fourth value: the user meant something this line cannot hold, so read none of it.
				return null;
			}

			try
			{
				values[found] = MovementMath.parseGp(part);
			}
			catch (ParseException notANumber)
			{
				return null;
			}

			found++;
		}

		if (found != BANDS || !valid(values[0], values[1], values[2]))
		{
			return null;
		}

		return of(values[0], values[1], values[2]);
	}

	/**
	 * The trio of three amounts in any order, sorted ascending (Z1) - what the gear menu's three boxes and the
	 * {@code presets=} verb build once their text has been read.
	 *
	 * <p>Throws rather than answering null, because a caller that has three longs in hand has already done the
	 * reading: this is the invariant's last gate, not the place a typed line is judged. A panel that reads three
	 * boxes catches it beside the {@link ParseException} the boxes themselves can throw
	 * ({@code catch (ParseException | IllegalArgumentException bad)}) and paints them red.
	 *
	 * @throws IllegalArgumentException when a value is not positive, or two of the three are equal
	 */
	public static BandPresets of(final long a, final long b, final long c)
	{
		if (!valid(a, b, c))
		{
			throw new IllegalArgumentException(
				"Three distinct positive gp bands are needed, not " + a + ", " + b + ", " + c);
		}

		final long[] sorted = {a, b, c};
		Arrays.sort(sorted);
		return new BandPresets(sorted[0], sorted[1], sorted[2]);
	}

	/**
	 * The three lower bounds, smallest first - what the fold's chips apply as {@code applyBand(min, 0)} (Z3).
	 *
	 * @return a fresh array each call, so a caller sorting or scaling it cannot reach back into the value
	 */
	public long[] mins()
	{
		return new long[]{small, middle, large};
	}

	/**
	 * The three chip labels, smallest first: each band in gp shorthand with a "+" after it, since a preset has a
	 * lower bound and no upper one (Z1). For {@link #DEFAULT} exactly "100k+", "1m+", "10m+" - the labels the fold
	 * has drawn since addendum N, which is why the default presets leave every picture byte-identical.
	 *
	 * @return a fresh array each call
	 */
	public String[] labels()
	{
		return new String[]{label(small), label(middle), label(large)};
	}

	/**
	 * The three bands as one line in gp shorthand, smallest first - "100k, 1m, 10m" (Z1): what the config item
	 * holds, what the gear menu's boxes print (one band each), and what {@link #parse(String)} reads back.
	 *
	 * <p>Shorthand is LOSSY where it must be, because it is the same shorthand the boxes and the Min / Max fields
	 * use: a band of 1,234,567 gp is written "1.23m" and read back as 1,230,000. Anything the game itself writes
	 * exactly - every round thousand, million and billion, and every amount under 10,000 - survives the trip.
	 */
	public String format()
	{
		return MovementMath.formatGp(small) + ", " + MovementMath.formatGp(middle) + ", "
			+ MovementMath.formatGp(large);
	}

	/** One chip's label: the band, then the "+" that says "and everything dearer". */
	private static String label(final long min)
	{
		return MovementMath.formatGp(min) + "+";
	}

	/**
	 * The invariant in one place: three positive amounts, no two the same. Order is not its business -
	 * {@link #of(long, long, long)} sorts.
	 */
	private static boolean valid(final long a, final long b, final long c)
	{
		return a > 0L && b > 0L && c > 0L && a != b && b != c && a != c;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof BandPresets))
		{
			return false;
		}
		final BandPresets other = (BandPresets) o;
		return small == other.small && middle == other.middle && large == other.large;
	}

	@Override
	public int hashCode()
	{
		int hash = Long.hashCode(small);
		hash = 31 * hash + Long.hashCode(middle);
		hash = 31 * hash + Long.hashCode(large);
		return hash;
	}

	/**
	 * A debugging line, in gp rather than in shorthand: {@link #format()} is the text the user sees, and a log
	 * that rounded 1,234,567 to "1.23m" would hide the very difference one would be reading a log to find.
	 */
	@Override
	public String toString()
	{
		return "BandPresets{" + small + ", " + middle + ", " + large + '}';
	}
}
