package com.bankpricemovement;

/**
 * How far one live figure can be trusted (contract 1.2.0, line L3;
 * {@code docs/handoff/contract-1.2.0-graded-live-moves-2026-10-07.md}). Every row the traded feeds price carries one,
 * per window, beside its figure ({@link GradedMove#grade()}); a row computed with live prices off carries none.
 *
 * <p>The order is the order the % and gp sorts put the rows in, in both directions (L5): every SOLID row before every
 * SOFT one, and every SOFT one before every NONE one - so a soft figure, however large, never heads the list above a
 * solid one ({@link MovementMath#comparator}).
 */
public enum Grade
{
	/**
	 * Both sides traded today and yesterday, inside the plausibility anchor, with real money behind the thinner side, a
	 * price of at least 100 gp, and the two sides agreeing to within ten points ({@link GradeMath} has every line of
	 * the test). Drawn exactly as every figure was before 1.2.0.
	 */
	SOLID("solid"),
	/**
	 * A figure, but one fact of the solid test failed - and {@link GradedMove#word()} names the first that did, in the
	 * contract's order ({@link GradeWords}). Drawn with the same digits as a solid figure; only the word beside it
	 * differs (the user's pick, L5).
	 */
	SOFT("soft"),
	/** No trade today and nothing for a fallback to compare: no figure at all - the dash, with "no trades". */
	NONE("none");

	private final String label;

	Grade(final String label)
	{
		this.label = label;
	}

	/** The lower-case word the dev bridge echoes as {@code state.rows[].grade}: "solid", "soft" or "none". */
	public String label()
	{
		return label;
	}
}
