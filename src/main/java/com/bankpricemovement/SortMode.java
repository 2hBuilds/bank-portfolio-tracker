package com.bankpricemovement;

/**
 * The four columns the sidebar's sort button offers, in the order the user gave them (addendum W, line W1;
 * {@code docs/bank-price-movement-addendum-W-2026-09-13.md}): <b>"1) Percent change 2) GP change 3) Item price
 * 4) Stack price"</b>, with the gp one written lowercase as he asked.
 *
 * <p><b>Four, not three.</b> {@link #STACK_VALUE} is the column addendum W adds ("i dont see the sort by stack
 * value option", 2026-09-12): a hundred Robin hood hats ARE a large holding though one hat is not a dear item,
 * and before W the only way to see them near the top was to throw a display switch and change what
 * {@link #UNIT_PRICE} meant. Now the two questions have a column each and neither moves under a switch.
 *
 * <p><b>Direction is NOT part of this enum</b>: it rides beside it on {@link RowFilter#descending()}, because
 * the sidebar flips direction by pressing the column that is already lit (addendum W, line W2 - the arrow on
 * the button is the direction), so the column and the arrow are two independent pieces of state.
 *
 * <p><b>The constant names are frozen surface</b>: {@code sortMode} stores {@link #name()}, so a profile
 * written by an older build still reads. Adding a constant is allowed - that is what W did - renaming one is
 * not.
 *
 * <p>The short verbs the dev bridge takes ({@code bpm sort=stack}), the labels and the constant names are read by
 * the bridge's own parser in the test source set; every verb that ever worked still works there, the pre-W labels
 * ("% move", "gp move") included.
 *
 * <p>{@link #toString()} is the LABEL so RuneLite's config combo box reads "Percent change" rather than
 * "PERCENT_MOVE"; the stored value is unaffected because {@code ConfigManager} serialises an enum with
 * {@code name()} ({@code ConfigManager.java:1279-1281}) and reads it with {@code Enum.valueOf}
 * ({@code :1205-1207}).
 */
public enum SortMode
{
	/** The percentage the unit price moved over the window. The default column ({@link RowFilter#DEFAULT}). */
	PERCENT_MOVE("Percent change"),

	/**
	 * The gp the WHOLE STACK moved over the window ({@link MovementRow#holdingDeltaGp()}), under every switch
	 * (addendum AO, line AO1).
	 *
	 * <p>It used to be ONE item's move, with {@code holdingOnRows} turning it into the stack's (Q6, and for one
	 * addendum V1 did the same to {@link #UNIT_PRICE}); that key is deleted, and the stack is the reading that
	 * survived it. This is a portfolio tracker, so "biggest gainers" means the holding that gained the most gp -
	 * ten thousand feathers up a gp each are a bigger gain than one whip up two hundred - and since addendum AN
	 * the row's headline line IS the stack, so the column now orders by the figure the eye lands on.
	 *
	 * <p><b>That changes the default ordering of this column for every existing user</b>: the deleted switch
	 * defaulted to OFF, and OFF was the per-item reading. No stored {@code sortMode} moves - the constant, the
	 * label and every dev-bridge alias are the ones they always were - but the list this column draws is ordered by a
	 * different number than it was before AO.
	 */
	GP_MOVE("gp change"),

	/** What ONE item costs, under every switch (addendum W reverted addendum V's line V1). */
	UNIT_PRICE("Item price"),

	/** What the whole stack is worth: unit price times quantity ({@link MovementRow#holdingValue()}). */
	STACK_VALUE("Stack price");

	private final String label;

	SortMode(final String label)
	{
		this.label = label;
	}

	/**
	 * The button text the sidebar draws, and the menu entry's words ("Stack price").
	 */
	public String label()
	{
		return label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
