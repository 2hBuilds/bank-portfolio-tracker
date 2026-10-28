package com.bankpricemovement;

import java.time.LocalDate;
import java.time.ZoneOffset;
import javax.annotation.Nullable;

/**
 * The ONE place in the panel's tests that names {@link PriceService.Status}'s real constructor.
 *
 * <p><b>Why it exists.</b> Every consumer test - {@code BankPriceMovementPanelTest}, the dev bridge's tests,
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
			options, null, null, 0, 0, 0, 0L, null, false);
	}

	/**
	 * The 26-argument form the tests built a status with until release 1.2.2: the six figures only the service
	 * knows (the bank history, the alch counts, the restarts, the now time and the grades) take their defaults, as
	 * they did from the old public constructors.
	 */
	static PriceService.Status status(long pricesAtMillis, long bankAtMillis, boolean bankLoaded, boolean loggedIn,
		@Nullable MovementRow.PriceSource source, @Nullable String problem,
		@Nullable PriceService.ProblemKind problemKind, int totalRows, int bankItems, @Nullable MovementWindow window,
		boolean baselineLoaded, long baselineRevisionSeconds, long baselineRevId, long mappingAtMillis,
		long indexAtMillis, @Nullable LocalDate anchorDay, double agree, int agreeSamples, @Nullable LocalDate r0Day,
		long r0RevId, boolean degraded, boolean anchorDegraded, @Nullable String degradedReason,
		@Nullable PortfolioSummary portfolio, @Nullable ViewOptions options, @Nullable PriceService.Status.LiveStatus live)
	{
		return new PriceService.Status(pricesAtMillis, bankAtMillis, bankLoaded, loggedIn, source, problem, problemKind,
			totalRows, bankItems, window, baselineLoaded, baselineRevisionSeconds, baselineRevId, mappingAtMillis,
			indexAtMillis, anchorDay, agree, agreeSamples, r0Day, r0RevId, degraded, anchorDegraded, degradedReason,
			portfolio, options, live, null, 0, 0, 0, 0L, null, false);
	}

	/** {@code base} with {@code series} as its bank history (null reads as EMPTY), every other figure copied. */
	static PriceService.Status withBankHistory(PriceService.Status base, @Nullable BankHistorySeries series)
	{
		return copy(base, series, base.nowSeconds, base.pouchUnread());
	}

	/** {@code base} with {@code seconds} as its now time (negative reads as 0), every other figure copied. */
	static PriceService.Status withNowSeconds(PriceService.Status base, long seconds)
	{
		return copy(base, base.bankHistory(), seconds, base.pouchUnread());
	}

	/** {@code base} with the pouch hint's flag set to {@code unread} (1.2.4), every other figure copied. */
	static PriceService.Status withPouchUnread(PriceService.Status base, boolean unread)
	{
		return copy(base, base.bankHistory(), base.nowSeconds, unread);
	}

	private static PriceService.Status copy(PriceService.Status base, @Nullable BankHistorySeries series, long nowSeconds,
		boolean pouchUnread)
	{
		return new PriceService.Status(base.pricesAtMillis(), base.bankAtMillis(), base.bankLoaded(), base.loggedIn(),
			base.source, base.problem(), base.problemKind(), base.totalRows, base.bankItems(), base.window(),
			base.baselineLoaded, base.baselineRevisionSeconds(), base.baselineRevId(), base.mappingAtMillis,
			base.indexAtMillis, base.anchorDay, base.agree, base.agreeSamples, base.r0Day, base.r0RevId, base.degraded(),
			base.anchorDegraded(), base.degradedReason(), base.portfolio(), base.options(), base.live(), series,
			base.alchStacks(), base.alchCounted(), base.placeholderRestarts(), nowSeconds, base.grades(), pouchUnread);
	}
}
