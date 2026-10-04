package com.bankpricemovement;

import java.awt.Color;

/**
 * The three colour sets the settings menu and the List options menu offer under "Colour presets" (1.1.0 part H, renamed by
 * part J; the caption said "Colour sets" until then): a name and the pair of colours a click on it sets, a rise's first and
 * then a fall's. A preset is a shortcut and not a mode - nothing remembers which one was clicked, and either colour can be
 * changed after it. The menu ticks the first preset whose pair is the one in use (part J), which the colours decide and a
 * click does not. Slot 1, the fourth preset row, is not a constant of this enum: its pair is the reader's.
 */
enum ColourSet
{
	/** The colours the sidebar is drawn in until the reader chooses others: the built-in green and the lifted red. */
	CLASSIC("Classic", Widgets.MOVE_UP_DEFAULT, Widgets.MOVE_DOWN_TEXT),
	/** The 2hBuilds pair: #30E533 and #73BDFF (the user's pick, 2026-10-04). */
	TWO_H("2h", new Color(0x30, 0xE5, 0x33), new Color(0x73, 0xBD, 0xFF)),
	/**
	 * For colour-blind readers, an orange and a blue that stay apart where green and red do not: up #FF9F43, down #4DA3FF
	 * (the user swapped them, 2026-10-04).
	 */
	COLOUR_BLIND("Colour-blind", new Color(0xFF, 0x9F, 0x43), new Color(0x4D, 0xA3, 0xFF));

	private final String label;
	private final Color up;
	private final Color down;

	ColourSet(String label, Color up, Color down)
	{
		this.label = label;
		this.up = up;
		this.down = down;
	}

	/** The row's words. */
	String label()
	{
		return label;
	}

	/** The colour of a rise this set sets. */
	Color up()
	{
		return up;
	}

	/** The colour of a fall this set sets. */
	Color down()
	{
		return down;
	}
}
