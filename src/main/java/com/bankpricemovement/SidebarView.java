package com.bankpricemovement;

/**
 * The sidebar's two views (addendum AU; {@code docs/handoff/plan-AU-history-2026-09-27.md} section 7.2 and the phase-0
 * contract's section 7): <b>Items</b>, the list of bank stacks every build before AU drew, and <b>Net Worth
 * History</b> (the constant {@link #HISTORY}), the bank's own total one reading a day.
 *
 * <p>The toggle strip under the Bank value card picks one ({@code BankPriceMovementPanel.pressView}), and the view
 * lasts for the session: the sidebar opens on Items every time it is built and nothing is stored.
 *
 * <p>{@link #toString()} answers the LABEL, which is what the toggle's two halves print.
 */
public enum SidebarView
{
	ITEMS("Items"),
	/**
	 * "Net Worth History" since 2026-09-29 (the user: "Net Worth History"; it read "Net worth tracker" from the first
	 * live look until then); the constant keeps its name, an identifier like the dev verb view=history.
	 */
	HISTORY("Net Worth History");

	/** What the view is called wherever a reader sees it: the toggle half and the settings page. */
	private final String label;

	SidebarView(final String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
