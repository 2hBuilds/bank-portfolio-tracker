package com.bankpricemovement;

import java.time.LocalDate;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Test fixtures for the rows the service builds from a graded live figure (contract 1.2.0): a row re-sourced as
 * {@link MovementRow.PriceSource#LIVE} and stamped with the day each window compared against.
 *
 * <p>This is what {@code MovementRow.asLive} did before release 1.2.2's trim took it out of the shipped class - the
 * service builds a live row directly now, so only the tests that need a hand-made one (the pictures, the open block's
 * lines) still build it this way.
 */
final class LiveRows
{
	private LiveRows()
	{
	}

	/**
	 * {@code row} re-sourced as {@link MovementRow.PriceSource#LIVE}, printing {@code liveUnit} and carrying {@code days}.
	 * A {@link MovementRow.PriceSource#PARTS} row keeps that source, because "valued as its parts" is what a reader has
	 * to be told first; a row with no price at all answers itself, since it is not "the live traded price".
	 *
	 * <p>The unit price is a parameter for the one awkward case a hand-made row needs: a live row whose current window
	 * fell back to the guide pair is built from it, so its gp and percent are the guide's move, and still prints the
	 * live mid - the move stays as it was built, the unit price and the holding that follows from it become the live
	 * mid.
	 *
	 * @param liveUnit the live traded mid to print, or null (or not positive) to keep the unit price the row was built
	 *                 with - the case where the row was built from the live pair already
	 * @param days     the day each window's "then" came from, or null for none
	 */
	static MovementRow asLive(final MovementRow row, @Nullable final Long liveUnit,
		@Nullable final Map<MovementWindow, LocalDate> days)
	{
		if (row.unitPrice() == null)
		{
			return row;
		}
		final Long unit = liveUnit == null || liveUnit <= 0L ? row.unitPrice() : liveUnit;
		return new MovementRow(row.id(), row.name(), row.quantity(), row.stackable(), unit, row.thenPrice(),
			row.deltaGp(), row.deltaPct(), MovementMath.holdingValue(unit, row.quantity()),
			row.source() == MovementRow.PriceSource.PARTS ? MovementRow.PriceSource.PARTS : MovementRow.PriceSource.LIVE,
			row.parts(), days, row.bankQuantity(), row.inventoryQuantity(), row.wornQuantity(),
			row.exchangeQuantity(), row.graded());
	}
}
