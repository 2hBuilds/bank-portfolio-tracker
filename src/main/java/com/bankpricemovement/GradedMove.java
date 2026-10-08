package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * One item's graded live figure for ONE window (contract 1.2.0, lines L2-L4): the same-side move, how far it can be
 * trusted, and every number it was made from - so the sidebar can print the figure, the word and the open block's
 * sentences without doing any arithmetic of its own. Built only by {@link GradeMath}; immutable, so a row can carry it
 * from the service's executor to the EDT.
 *
 * <p><b>The figure.</b> Per side (buy = the instant-buy prints, sell = the instant-sell ones), "now" is the median of
 * the last 24 hours' plausible observations and "then" the window's day's side average; the side's move is {@code now / then - 1}
 * and {@link #move()} is the mean of the side moves present. {@link #thenMark()} is the window day's two-sided average
 * (or its one side), {@link #deltaGp()} is {@code round(thenMark x move)} per item, and {@link #price()} is
 * {@code thenMark + deltaGp} - the microquant mark, so the % and the gp can never disagree. When exactly ONE side was
 * observed the figure stands on that side alone: {@link #thenMark()} is that side's own average, {@link #price()} its
 * own median and {@link #deltaGp()} the difference, so the worth, the was and the change agree on the one side. A
 * {@link #fallback()} figure is yesterday's move instead: "now" is yesterday's side average and "then" the day before's
 * (for 1d) or the window's day's (for longer windows).
 *
 * <p><b>NONE</b> carries no figure: {@link #move()}, {@link #thenMark()}, {@link #deltaGp()} and {@link #price()} are
 * null, the word is {@link GradeWords#NO_TRADES}, and the row prints the dash.
 */
public final class GradedMove
{
	/**
	 * One side of the book as one figure saw it: its "now" (the median of its observations in the last 24 hours), the window day's
	 * "then", the side's own move, how many observations "now" was taken over, and the side's newest print with its
	 * time - "last sold 1,250 at 14:02". Immutable.
	 */
	public static final class Side
	{
		/** A side nothing is known of. */
		static final Side EMPTY = new Side(null, null, null, 0, null, 0L);

		@Nullable
		private final Long now;
		@Nullable
		private final Long then;
		@Nullable
		private final Double move;
		private final int observations;
		@Nullable
		private final Long last;
		private final long lastSeconds;

		Side(@Nullable final Long now, @Nullable final Long then, @Nullable final Double move, final int observations,
			@Nullable final Long last, final long lastSeconds)
		{
			this.now = now;
			this.then = then;
			this.move = move;
			this.observations = Math.max(0, observations);
			this.last = last;
			this.lastSeconds = last == null ? 0L : Math.max(0L, lastSeconds);
		}

		/**
		 * This side's "now": the median of its plausible observations in the last 24 hours - or, on a {@link GradedMove#fallback()}
		 * figure, yesterday's side average. Null when there is none.
		 */
		@Nullable
		public Long now()
		{
			return now;
		}

		/** This side's "then": the window day's side average (volume at least one, inside the anchor), or null. */
		@Nullable
		public Long then()
		{
			return then;
		}

		/** {@code now / then - 1}, or null when either end is missing. A ratio: 0.05 is +5 %. */
		@Nullable
		public Double move()
		{
			return move;
		}

		/** How many plausible observations of this side in the last 24 hours "now" was taken over; 0 on a fallback figure. */
		public int observations()
		{
			return observations;
		}

		/**
		 * The newest print of this side in the {@code /latest} snapshot - what someone last paid (buy) or got (sell) -
		 * whatever its age, or null when the feed had none. The open block's "last sold 1,250 at 14:02".
		 */
		@Nullable
		public Long last()
		{
			return last;
		}

		/** When {@link #last()} traded, unix seconds; 0 when there is no last print. */
		public long lastSeconds()
		{
			return lastSeconds;
		}

		@Override
		public boolean equals(final Object o)
		{
			if (this == o)
			{
				return true;
			}
			if (!(o instanceof Side))
			{
				return false;
			}
			final Side other = (Side) o;
			return observations == other.observations && lastSeconds == other.lastSeconds
				&& Objects.equals(now, other.now) && Objects.equals(then, other.then)
				&& Objects.equals(move, other.move) && Objects.equals(last, other.last);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(now, then, move, observations, last, lastSeconds);
		}

		@Override
		public String toString()
		{
			return "{now=" + now + ", then=" + then + ", move=" + move + ", obs=" + observations
				+ (last == null ? "" : ", last=" + last + "@" + lastSeconds) + '}';
		}
	}

	private final Grade grade;
	private final String word;
	@Nullable
	private final Double move;
	@Nullable
	private final Long thenMark;
	@Nullable
	private final Long deltaGp;
	private final boolean fallback;
	private final Side buy;
	private final Side sell;
	@Nullable
	private final LocalDate nowDay;
	@Nullable
	private final LocalDate thenDay;
	@Nullable
	private final Long anchor;
	@Nullable
	private final Long spreadPct;

	/**
	 * @param grade    the grade; null reads as {@link Grade#NONE}
	 * @param word     the word ({@link GradeWords}); null reads as "" for SOLID and {@link GradeWords#NO_TRADES} for NONE
	 * @param move     the figure as a ratio, or null for NONE
	 * @param thenMark the window day's mark, or null for NONE
	 * @param deltaGp  {@code round(thenMark x move)}, or null for NONE
	 * @param fallback whether this is yesterday's move (the word {@link GradeWords#lastTraded}, or {@link GradeWords#YDAY}
	 *                 when no last-trade time is known)
	 * @param buy      the buy side; null reads as nothing known
	 * @param sell     the sell side; null reads as nothing known
	 * @param nowDay   the UTC day "now" is of - today for a measured figure, yesterday for a fallback; null for NONE
	 * @param thenDay  the UTC day "then" is of; null for NONE
	 * @param anchor   the plausibility anchor the figure was judged against; null when there was none
	 */
	GradedMove(@Nullable final Grade grade, @Nullable final String word, @Nullable final Double move,
		@Nullable final Long thenMark, @Nullable final Long deltaGp, final boolean fallback, @Nullable final Side buy,
		@Nullable final Side sell, @Nullable final LocalDate nowDay, @Nullable final LocalDate thenDay,
		@Nullable final Long anchor)
	{
		this(grade, word, move, thenMark, deltaGp, fallback, buy, sell, nowDay, thenDay, anchor, null);
	}

	/**
	 * As the constructor above, with the current pair's wide spread.
	 *
	 * @param spreadPct the current buy and sell pair's spread as a whole percentage of its middle when it is a CURRENT and
	 *                  WIDE one (see {@link #spreadPercent()}); null otherwise, and for a figure with no figure at all
	 */
	GradedMove(@Nullable final Grade grade, @Nullable final String word, @Nullable final Double move,
		@Nullable final Long thenMark, @Nullable final Long deltaGp, final boolean fallback, @Nullable final Side buy,
		@Nullable final Side sell, @Nullable final LocalDate nowDay, @Nullable final LocalDate thenDay,
		@Nullable final Long anchor, @Nullable final Long spreadPct)
	{
		this.grade = grade == null ? Grade.NONE : grade;
		this.word = word != null ? word : this.grade == Grade.NONE ? GradeWords.NO_TRADES : GradeWords.SOLID;
		final boolean figure = this.grade != Grade.NONE && move != null && thenMark != null && deltaGp != null;
		this.move = figure ? move : null;
		this.thenMark = figure ? thenMark : null;
		this.deltaGp = figure ? deltaGp : null;
		this.fallback = figure && fallback;
		this.buy = buy == null ? Side.EMPTY : buy;
		this.sell = sell == null ? Side.EMPTY : sell;
		this.nowDay = figure ? nowDay : null;
		this.thenDay = figure ? thenDay : null;
		this.anchor = anchor;
		this.spreadPct = figure ? spreadPct : null;
	}

	/** Never null. */
	public Grade grade()
	{
		return grade;
	}

	/**
	 * The word the sidebar prints under a soft figure (L5): {@link GradeWords#SOLID} ("") for a solid one,
	 * {@link GradeWords#NO_TRADES} for NONE, otherwise the first failing fact's word. Never null.
	 */
	public String word()
	{
		return word;
	}

	/** Whether there is a figure at all - SOLID or SOFT. */
	public boolean hasFigure()
	{
		return grade != Grade.NONE;
	}

	/** The figure as a ratio ({@code 0.05} is +5 %), or null for NONE. The row prints it x 100, truncated. */
	@Nullable
	public Double move()
	{
		return move;
	}

	/**
	 * The window day's mark - its two sides' averages, halved, when both are present and plausible, else the one -
	 * which the figure moves from; null for NONE. The row's "then" (L2).
	 */
	@Nullable
	public Long thenMark()
	{
		return thenMark;
	}

	/** One item's gp move: {@code round(thenMark x move)}; null for NONE. */
	@Nullable
	public Long deltaGp()
	{
		return deltaGp;
	}

	/**
	 * The mark this figure puts one item at: {@code thenMark + deltaGp}, i.e. {@code thenMark x (1 + move)} rounded
	 * (L4); null for NONE. On the 1d window this is the row's price; on the others it is that window's own "now".
	 */
	@Nullable
	public Long price()
	{
		return thenMark == null || deltaGp == null ? null : PortfolioMath.clampedAdd(thenMark, deltaGp);
	}

	/** Whether this is yesterday's move (no trade in the last 24 hours) rather than the last 24 hours' own; always SOFT. */
	public boolean fallback()
	{
		return fallback;
	}

	/** The buy side - what buyers paid. Never null. */
	public Side buy()
	{
		return buy;
	}

	/** The sell side - what sellers got. Never null. */
	public Side sell()
	{
		return sell;
	}

	/**
	 * Whether both sides moved and agree to within {@value GradeMath#SOLID_MAX_SIDE_GAP_POINTS} points - the open
	 * block's "Both sides agree." False when either side has no move.
	 */
	public boolean sidesAgree()
	{
		return GradeMath.sidesAgree(buy.move(), sell.move());
	}

	/** The UTC day "now" is of: the live day for a measured figure, yesterday for a fallback; null for NONE. */
	@Nullable
	public LocalDate nowDay()
	{
		return nowDay;
	}

	/** The UTC day "then" is of - the window's traded day, or the day before yesterday for a 1d fallback; null for NONE. */
	@Nullable
	public LocalDate thenDay()
	{
		return thenDay;
	}

	/** The plausibility anchor A every price here was judged against (L2), or null when there was none to judge with. */
	@Nullable
	public Long anchor()
	{
		return anchor;
	}

	/**
	 * The number in {@link GradeWords#spread} ("spread 55%"): the current buy and sell pair's gap as a whole percentage of its middle,
	 * when the pair is a CURRENT and WIDE spread - both prints from the last 24 hours and inside the anchor's range, and more
	 * than ten percent of their middle apart. Null when it is not (a side that has been silent for a day makes the pair no
	 * spread, so a figure reported on one side only has none), for a fallback figure and for NONE. The open block prints it
	 * ("The buy and sell prices are 30% apart.").
	 */
	@Nullable
	public Long spreadPercent()
	{
		return spreadPct;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof GradedMove))
		{
			return false;
		}
		final GradedMove other = (GradedMove) o;
		return grade == other.grade && fallback == other.fallback && word.equals(other.word)
			&& Objects.equals(move, other.move) && Objects.equals(thenMark, other.thenMark)
			&& Objects.equals(deltaGp, other.deltaGp) && buy.equals(other.buy) && sell.equals(other.sell)
			&& Objects.equals(nowDay, other.nowDay) && Objects.equals(thenDay, other.thenDay)
			&& Objects.equals(anchor, other.anchor) && Objects.equals(spreadPct, other.spreadPct);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(grade, word, move, thenMark, deltaGp, fallback, buy, sell, nowDay, thenDay, anchor, spreadPct);
	}

	@Override
	public String toString()
	{
		return "GradedMove{" + grade.label() + (word.isEmpty() ? "" : " '" + word + "'")
			+ (move == null ? "" : ", move=" + move + ", thenMark=" + thenMark + ", deltaGp=" + deltaGp)
			+ (fallback ? ", yday" : "") + ", buy=" + buy + ", sell=" + sell
			+ (thenDay == null ? "" : ", " + nowDay + " vs " + thenDay) + ", anchor=" + anchor
			+ (spreadPct == null ? "" : ", spread=" + spreadPct + "%") + '}';
	}
}
