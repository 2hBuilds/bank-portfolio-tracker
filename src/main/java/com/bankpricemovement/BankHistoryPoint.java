package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * One day's reading of the player's net worth (addendum AU; plan 7.5 item 2 and 7.7 items 3-5; phase 0 contract
 * sections 2 and 9.4): the total the Bank value card showed that day, saved IN PARTS so the History line can be
 * drawn under whatever counting switches are on NOW.
 *
 * <p><b>The ten cells.</b> WHERE (in the bank | carried = inventory + worn) times WHAT (tradeable stacks | coins
 * and platinum tokens | untradeables valued as their tradeable PARTS that day | untradeables at their High Alchemy
 * value that day) - eight cells, to which 1.0.9 part 3 added two for the third place a player's worth can be, the
 * Grand Exchange offers: the tradeable stacks in them and the coins committed to or earned by them. The Grand
 * Exchange trades tradeables and holds coins, so it has no parts or alch cell. An untradeable is filed by its ACTUAL
 * source that day, not by whether it has a mapping: a parts stack with one unpriced part ended at its alch value, and
 * so is an alch cell. Every cell is recorded whatever the switches say, so a switch flipped later redraws the whole
 * line instead of showing as a one-day jump. A reading written before the offers were counted holds eight cells, and
 * reads as ten with the two new ones at zero ({@code PriceStore.cells}).
 *
 * <p><b>Two sets of figures.</b> {@code card} is the day as the card priced it (live mids where "Use live prices"
 * priced them live); {@code guide} is the same stacks at guide prices alone. When the two are equal - every day
 * recorded with live prices off, and any day no stack went live - {@code guide} is not stored and
 * {@link #hasGuide()} is false.
 *
 * <p><b>The day is a LOCAL date</b> (plan 7.1 item 2), unlike every other day in this plugin, which are the wiki's
 * UTC days. The caller derives it with {@link BankHistoryMath#dayOf(long, java.time.ZoneId)}; nothing here reads a
 * clock or a zone.
 *
 * <p>Immutable, with value equality. The arrays are copied in and never handed out.
 */
public final class BankHistoryPoint
{
	/** Tradeable stacks in the bank. Counted always. */
	public static final int BANK_TRADEABLE = 0;
	/** Coins and platinum tokens in the bank. Counted with "Include coins and platinum tokens". */
	public static final int BANK_CASH = 1;
	/** Untradeables in the bank valued as their tradeable parts that day. Counted always (addendum AV). */
	public static final int BANK_PARTS = 2;
	/** Untradeables in the bank at their alch value that day. Counted with "Include alch-only untradeables". */
	public static final int BANK_ALCH = 3;
	/** Tradeable stacks carried (inventory + worn). Counted with "Include inventory and worn gear". */
	public static final int CARRIED_TRADEABLE = 4;
	/** Carried coins and platinum tokens. Counted with the inventory switch AND the coins switch. */
	public static final int CARRIED_CASH = 5;
	/** Carried untradeables valued as their parts. Counted with the inventory switch. */
	public static final int CARRIED_PARTS = 6;
	/** Carried untradeables at their alch value. Counted with the inventory switch AND the untradeables switch. */
	public static final int CARRIED_ALCH = 7;
	/**
	 * Tradeable stacks in Grand Exchange offers - unsold in a sell offer, bought and uncollected in a buy offer, at the
	 * item's price that day. Counted with "Include Grand Exchange offers".
	 */
	public static final int GE_TRADEABLE = 8;
	/**
	 * Coins committed to buy offers, or received by sell offers and not yet collected. Counted with the Grand Exchange
	 * switch AND the coins switch.
	 */
	public static final int GE_CASH = 9;
	/** How many cells a reading holds, in {@code card} and in {@code guide}. */
	public static final int CELLS = 10;

	private final LocalDate day;
	private final long readAtMillis;
	private final long bankAtMillis;
	private final long[] card;
	/** Null when the guide figures equal {@link #card} - never an array equal to it (amendment 9.4). */
	@Nullable
	private final long[] guide;

	/**
	 * @param day          the LOCAL date of the reading
	 * @param readAtMillis when the reading was taken, epoch millis - the tie-breaker when two readings name a day
	 * @param bankAtMillis when the bank it priced was captured, epoch millis
	 * @param card         the {@value #CELLS} cells as the card priced them; copied, a negative cell reads as 0
	 * @param guide        the same cells at guide prices alone, or null when they equal {@code card}; copied, and an
	 *                     array equal to {@code card} is stored as null so the two spellings of one reading are equal
	 * @throws NullPointerException     if {@code day} or {@code card} is null
	 * @throws IllegalArgumentException if {@code card} or {@code guide} does not hold exactly {@value #CELLS} cells
	 */
	public BankHistoryPoint(final LocalDate day, final long readAtMillis, final long bankAtMillis, final long[] card,
		@Nullable final long[] guide)
	{
		this.day = Objects.requireNonNull(day, "day");
		this.readAtMillis = readAtMillis;
		this.bankAtMillis = bankAtMillis;
		this.card = cells(Objects.requireNonNull(card, "card"), "card");
		final long[] g = guide == null ? null : cells(guide, "guide");
		this.guide = g == null || Arrays.equals(g, this.card) ? null : g;
	}

	/** A copy of exactly {@value #CELLS} cells, each at least 0 - there is no such thing as a negative holding. */
	private static long[] cells(final long[] values, final String name)
	{
		if (values.length != CELLS)
		{
			throw new IllegalArgumentException(name + " must hold " + CELLS + " cells, not " + values.length);
		}
		final long[] copy = new long[CELLS];
		for (int i = 0; i < CELLS; i++)
		{
			copy[i] = Math.max(0L, values[i]);
		}
		return copy;
	}

	/** The LOCAL date this reading is of. */
	public LocalDate day()
	{
		return day;
	}

	/** When the reading was taken, epoch millis. */
	public long readAtMillis()
	{
		return readAtMillis;
	}

	/** When the bank it priced was captured, epoch millis. */
	public long bankAtMillis()
	{
		return bankAtMillis;
	}

	/**
	 * One cell as the card priced it that day.
	 *
	 * @throws ArrayIndexOutOfBoundsException if {@code cell} is not one of the ten
	 */
	public long card(final int cell)
	{
		return card[cell];
	}

	/**
	 * One cell at guide prices alone; equal to {@link #card(int)} when no separate figure was stored.
	 *
	 * @throws ArrayIndexOutOfBoundsException if {@code cell} is not one of the ten
	 */
	public long guide(final int cell)
	{
		return guide == null ? card[cell] : guide[cell];
	}

	/** False when the guide figures equal the card's and are not stored. */
	public boolean hasGuide()
	{
		return guide != null;
	}

	/**
	 * The reading's total under these switches - the ONE rule every History figure uses (contract section 2), so
	 * the History figure always equals the Bank value card. The cells counted:
	 * <ul>
	 * <li>bank tradeable and bank parts - always;</li>
	 * <li>bank cash - {@code countCash}; bank alch - {@code countUntradeables};</li>
	 * <li>carried tradeable and carried parts - {@code countInventory};</li>
	 * <li>carried cash - {@code countInventory && countCash}; carried alch -
	 * {@code countInventory && countUntradeables};</li>
	 * <li>Grand Exchange tradeable - {@code countGrandExchange}; Grand Exchange cash -
	 * {@code countGrandExchange && countCash} (1.0.9 part 3).</li>
	 * </ul>
	 * Summed from {@code card} with {@code livePrices} on and from {@code guide} with it off, with the clamped
	 * addition {@link PortfolioSummary} uses ({@link PortfolioMath#clampedAdd}).
	 *
	 * @param options the switches; null reads as {@link ViewOptions#DEFAULT} (amendment 9.2)
	 */
	public long valueFor(@Nullable final ViewOptions options)
	{
		final ViewOptions o = options == null ? ViewOptions.DEFAULT : options;
		final long[] figures = o.livePrices() || guide == null ? card : guide;
		final boolean cash = o.countCash();
		final boolean alch = o.countUntradeables();
		final boolean carried = o.countInventory();
		final boolean offers = o.countGrandExchange();

		long sum = figures[BANK_TRADEABLE];
		sum = PortfolioMath.clampedAdd(sum, figures[BANK_PARTS]);
		if (cash)
		{
			sum = PortfolioMath.clampedAdd(sum, figures[BANK_CASH]);
		}
		if (alch)
		{
			sum = PortfolioMath.clampedAdd(sum, figures[BANK_ALCH]);
		}
		if (carried)
		{
			sum = PortfolioMath.clampedAdd(sum, figures[CARRIED_TRADEABLE]);
			sum = PortfolioMath.clampedAdd(sum, figures[CARRIED_PARTS]);
			if (cash)
			{
				sum = PortfolioMath.clampedAdd(sum, figures[CARRIED_CASH]);
			}
			if (alch)
			{
				sum = PortfolioMath.clampedAdd(sum, figures[CARRIED_ALCH]);
			}
		}
		if (offers)
		{
			sum = PortfolioMath.clampedAdd(sum, figures[GE_TRADEABLE]);
			if (cash)
			{
				sum = PortfolioMath.clampedAdd(sum, figures[GE_CASH]);
			}
		}
		return sum;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof BankHistoryPoint))
		{
			return false;
		}
		final BankHistoryPoint other = (BankHistoryPoint) o;
		return day.equals(other.day)
			&& readAtMillis == other.readAtMillis
			&& bankAtMillis == other.bankAtMillis
			&& Arrays.equals(card, other.card)
			&& Arrays.equals(guide, other.guide);
	}

	@Override
	public int hashCode()
	{
		int h = Objects.hash(day, readAtMillis, bankAtMillis);
		h = 31 * h + Arrays.hashCode(card);
		return 31 * h + Arrays.hashCode(guide);
	}

	@Override
	public String toString()
	{
		return "BankHistoryPoint{day=" + day + ", readAtMillis=" + readAtMillis + ", bankAtMillis=" + bankAtMillis
			+ ", card=" + Arrays.toString(card) + (guide == null ? "" : ", guide=" + Arrays.toString(guide)) + '}';
	}
}
