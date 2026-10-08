package com.bankpricemovement;

import java.util.Objects;

/**
 * The card's grade (contract 1.2.0, line L3): how the counted rows of one computation split between SOLID, SOFT and
 * NONE for the window on screen, how much of the bank's value the solid ones hold, and how much of its gp move the soft
 * ones supply. The card is SOFT ({@link #soft()}) when the solid rows hold less than
 * {@value GradeMath#CARD_SOLID_VALUE_PCT} % of the counted value or the soft rows supply more than
 * {@value GradeMath#CARD_SOFT_MOVE_PCT} % of the gp move; its hover then says "n of m rows solid" (L5).
 *
 * <p>The counted value is every stack the bank value adds up, cash left out; the gp move is the sum of every counted
 * row's stack move taken WITHOUT its sign, so a rise and a fall cannot cancel into a soft share of nothing. Immutable;
 * {@link #NONE} for a computation that graded nothing (live prices off, or no usable snapshot), which is never soft.
 */
public final class GradeSummary
{
	/** Nothing graded: live prices off or unusable. Never soft. */
	public static final GradeSummary NONE = new GradeSummary(false, 0, 0, 0, 0L, 0L, 0L, 0L);

	private final boolean graded;
	private final int solidRows;
	private final int softRows;
	private final int noneRows;
	private final long solidValue;
	private final long countedValue;
	private final long softMoveGp;
	private final long totalMoveGp;

	GradeSummary(final boolean graded, final int solidRows, final int softRows, final int noneRows,
		final long solidValue, final long countedValue, final long softMoveGp, final long totalMoveGp)
	{
		this.graded = graded;
		this.solidRows = Math.max(0, solidRows);
		this.softRows = Math.max(0, softRows);
		this.noneRows = Math.max(0, noneRows);
		this.solidValue = Math.max(0L, solidValue);
		this.countedValue = Math.max(0L, countedValue);
		this.softMoveGp = Math.max(0L, softMoveGp);
		this.totalMoveGp = Math.max(0L, totalMoveGp);
	}

	/**
	 * Gathers one computation's counted rows into a {@link GradeSummary} - what {@code PriceService} adds each counted
	 * stack's row to as it builds the bank value.
	 */
	static final class Tally
	{
		private final boolean graded;
		private int solid;
		private int soft;
		private int none;
		private long solidValue;
		private long countedValue;
		private long softMove;
		private long totalMove;

		/** @param graded whether this computation grades its rows at all */
		Tally(final boolean graded)
		{
			this.graded = graded;
		}

		/**
		 * One counted stack's row: its holding joins the counted value, its grade and stack move the shares. A row with
		 * no price at all is no row on the list and counts nothing.
		 */
		void add(final MovementRow row)
		{
			if (row == null || row.unitPrice() == null)
			{
				return;
			}
			countedValue = PortfolioMath.clampedAdd(countedValue, Math.max(0L, row.holdingValue()));
			final GradedMove move = row.graded();
			final long stackMove = row.hasMovement() ? abs(row.holdingDeltaGp()) : 0L;
			totalMove = PortfolioMath.clampedAdd(totalMove, stackMove);
			if (move == null)
			{
				return;
			}
			if (move.grade() == Grade.SOLID)
			{
				solid++;
				solidValue = PortfolioMath.clampedAdd(solidValue, Math.max(0L, row.holdingValue()));
			}
			else if (move.grade() == Grade.SOFT)
			{
				soft++;
				softMove = PortfolioMath.clampedAdd(softMove, stackMove);
			}
			else
			{
				none++;
			}
		}

		GradeSummary done()
		{
			return graded ? new GradeSummary(true, solid, soft, none, solidValue, countedValue, softMove, totalMove)
				: NONE;
		}

		private static long abs(final long gp)
		{
			return gp == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(gp);
		}
	}

	/** Whether the computation graded its rows at all - live prices on, with a usable snapshot. */
	public boolean graded()
	{
		return graded;
	}

	/** Counted rows whose figure on the window on screen is SOLID. */
	public int solidRows()
	{
		return solidRows;
	}

	/** Counted rows whose figure is SOFT. */
	public int softRows()
	{
		return softRows;
	}

	/** Counted rows with no figure (NONE). */
	public int noneRows()
	{
		return noneRows;
	}

	/** Every graded counted row - the m of "n of m rows solid". */
	public int gradedRows()
	{
		return solidRows + softRows + noneRows;
	}

	/** What the SOLID rows' stacks are worth, gp. */
	public long solidValue()
	{
		return solidValue;
	}

	/** What every counted stack is worth, gp, cash left out - the bank value's stacks. */
	public long countedValue()
	{
		return countedValue;
	}

	/** The SOFT rows' stack moves, summed without their signs, gp. */
	public long softMoveGp()
	{
		return softMoveGp;
	}

	/** Every counted row's stack move, summed without its sign, gp. */
	public long totalMoveGp()
	{
		return totalMoveGp;
	}

	/**
	 * Whether the CARD is soft (L3): graded, and either the solid rows hold under
	 * {@value GradeMath#CARD_SOLID_VALUE_PCT} % of the counted value or the soft rows supply over
	 * {@value GradeMath#CARD_SOFT_MOVE_PCT} % of the gp move. Integer arithmetic.
	 */
	public boolean soft()
	{
		if (!graded)
		{
			return false;
		}
		final boolean solidShort = countedValue > 0L
			&& GradeMath.underShare(solidValue, countedValue, GradeMath.CARD_SOLID_VALUE_PCT);
		final boolean softHeavy = totalMoveGp > 0L
			&& GradeMath.overShare(softMoveGp, totalMoveGp, GradeMath.CARD_SOFT_MOVE_PCT);
		return solidShort || softHeavy;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof GradeSummary))
		{
			return false;
		}
		final GradeSummary other = (GradeSummary) o;
		return graded == other.graded && solidRows == other.solidRows && softRows == other.softRows
			&& noneRows == other.noneRows && solidValue == other.solidValue && countedValue == other.countedValue
			&& softMoveGp == other.softMoveGp && totalMoveGp == other.totalMoveGp;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(graded, solidRows, softRows, noneRows, solidValue, countedValue, softMoveGp, totalMoveGp);
	}

	@Override
	public String toString()
	{
		return graded ? "GradeSummary{solid=" + solidRows + ", soft=" + softRows + ", none=" + noneRows
			+ ", solidValue=" + solidValue + "/" + countedValue + ", softMove=" + softMoveGp + "/" + totalMoveGp
			+ (soft() ? ", SOFT" : "") + '}' : "GradeSummary{not graded}";
	}
}
