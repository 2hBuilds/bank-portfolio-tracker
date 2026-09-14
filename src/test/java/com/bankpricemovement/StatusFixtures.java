package com.bankpricemovement;

import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.annotation.Nullable;

/**
 * The ONE place in the panel's tests that names {@link PriceService.Status}'s real constructor.
 *
 * <p><b>Why it exists.</b> Every consumer test - {@code BankPriceMovementPanelTest}, {@code BpmCommandsTest},
 * {@link LookRenderer} - drives a MOCKED {@code Status}, and deliberately: the class has grown an argument on
 * every addendum (K, L, M) and another agent owns it, so naming its constructor in each of them would pin an
 * argument list none of them has any business pinning. The cost of that seam is that no test ever hands the
 * panel a state the service can actually publish, and the mock can answer things the real class cannot -
 * {@code portfolio()} coerces null to {@link PortfolioSummary#EMPTY}, {@code text()} IS the problem sentence
 * when there is one - so a panel branch written for the mock's answer would never be caught.
 *
 * <p>So exactly one golden test crosses the seam ({@code BankPriceMovementPanelTest#aRealStatusFromTheService
 * RendersTheWholeHeader}), and it builds its state here: one file to edit when the argument list moves, and one
 * place to look for what a real status of this shape looks like.
 */
final class StatusFixtures
{
	private StatusFixtures()
	{
	}

	/**
	 * A LISTED status the service could really publish: logged in, a bank captured at
	 * {@code bankAtMillis}, guide prices read at {@code pricesAtMillis}, the current window's baseline being the
	 * guide table of {@code thenDay}, and {@code portfolio} beside the rows.
	 *
	 * @param problem   the one sentence that replaces the header line, or null; note the real class then answers
	 *                  it from {@code text()} too, which the mock does not. A sentence here is published as
	 *                  {@link PriceService.ProblemKind#HISTORY_DOWN} - the severe kind the panel paints red -
	 *                  because that is what a status carrying a fault looks like; a test that wants the grey
	 *                  flavour names the constructor itself
	 * @param thenDay   the baseline's UTC day, or null for a window with no baseline
	 * @param portfolio the whole-bank sums, or null - which the real class reads as
	 *                  {@link PortfolioSummary#EMPTY}
	 */
	static PriceService.Status listed(long pricesAtMillis, long bankAtMillis, MovementWindow window,
		@Nullable String problem, @Nullable LocalDate thenDay, @Nullable PortfolioSummary portfolio, int bankItems,
		int totalRows)
	{
		return listed(pricesAtMillis, bankAtMillis, window, problem, thenDay, portfolio, bankItems, totalRows,
			ViewOptions.DEFAULT);
	}

	/**
	 * The same status under a given set of addendum-Q view switches - what {@code Status.options()} answers, and
	 * what a golden panel test needs to render the card and the rows under anything but the defaults.
	 *
	 * @param options the three switches; null reads as {@link ViewOptions#DEFAULT}
	 */
	static PriceService.Status listed(long pricesAtMillis, long bankAtMillis, MovementWindow window,
		@Nullable String problem, @Nullable LocalDate thenDay, @Nullable PortfolioSummary portfolio, int bankItems,
		int totalRows, @Nullable ViewOptions options)
	{
		final long baselineSeconds = thenDay == null ? 0L : thenDay.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
		return new PriceService.Status(
			pricesAtMillis,
			bankAtMillis,
			true,
			true,
			MovementRow.PriceSource.GUIDE,
			problem,
			problem == null ? PriceService.ProblemKind.NONE : PriceService.ProblemKind.HISTORY_DOWN,
			totalRows,
			bankItems,
			window,
			thenDay != null,
			baselineSeconds,
			thenDay == null ? 0L : 15_333_448L,
			pricesAtMillis,
			pricesAtMillis,
			thenDay,
			0.94d,
			480,
			thenDay,
			15_333_999L,
			false,
			false,
			null,
			portfolio,
			options);
	}
}
