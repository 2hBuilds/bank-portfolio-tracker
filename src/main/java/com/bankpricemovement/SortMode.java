package com.bankpricemovement;

import java.util.Locale;

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
 * <p>{@link #parse(String)} takes the short verbs the dev bridge uses ({@code bpm sort=stack}) as well as the
 * labels and the constant names, so one code path serves the panel, the config and the terminal. Every verb
 * that ever worked still works, the pre-W labels ("% move", "gp move") included.
 *
 * <p>{@link #toString()} is the LABEL so RuneLite's config combo box reads "Percent change" rather than
 * "PERCENT_MOVE"; the stored value is unaffected because {@code ConfigManager} serialises an enum with
 * {@code name()} ({@code ConfigManager.java:1279-1281}) and reads it with {@code Enum.valueOf}
 * ({@code :1205-1207}).
 */
public enum SortMode
{
	/** The percentage the unit price moved over the window. The default column ({@link RowFilter#DEFAULT}). */
	PERCENT_MOVE("Percent change", "pct", "percent", "% move"),

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
	 * label and every alias are the ones they always were - but the list this column draws is ordered by a
	 * different number than it was before AO.
	 */
	GP_MOVE("gp change", "gp", "amount", "gp move"),

	/** What ONE item costs, under every switch (addendum W reverted addendum V's line V1). */
	UNIT_PRICE("Item price", "price", "unit"),

	/** What the whole stack is worth: unit price times quantity ({@link MovementRow#holdingValue()}). */
	STACK_VALUE("Stack price", "stack", "stacks", "holding");

	private final String label;
	private final String[] aliases;

	SortMode(final String label, final String... aliases)
	{
		this.label = label;
		this.aliases = aliases;
	}

	/**
	 * The button text the sidebar draws, and the menu entry's words ("Stack price").
	 */
	public String label()
	{
		return label;
	}

	/**
	 * Reads a column out of user or dev-bridge text: the constant name ("STACK_VALUE"), the label ("Stack
	 * price"), or one of the short verbs - "pct" / "percent" / "% move", "gp" / "amount" / "gp move", "price"
	 * / "unit", "stack" / "stacks" / "holding". Trimmed and case-insensitive.
	 *
	 * <p>The pre-addendum-W labels are among those verbs on purpose: "% move" and "gp move" were what the
	 * button printed for two years of scripts, and a rename of the words on screen is no reason for
	 * {@code bpm sort="% move"} to stop answering.
	 *
	 * @param text anything, including null
	 * @return the column, or null when the text names none (the caller keeps its current column)
	 */
	public static SortMode parse(final String text)
	{
		if (text == null)
		{
			return null;
		}

		final String key = text.trim().toLowerCase(Locale.ENGLISH);
		if (key.isEmpty())
		{
			return null;
		}

		for (final SortMode mode : values())
		{
			if (key.equals(mode.label.toLowerCase(Locale.ENGLISH))
				|| key.equals(mode.name().toLowerCase(Locale.ENGLISH)))
			{
				return mode;
			}

			for (final String alias : mode.aliases)
			{
				if (key.equals(alias))
				{
					return mode;
				}
			}
		}

		return null;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
