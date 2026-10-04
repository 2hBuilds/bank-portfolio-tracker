package com.bankpricemovement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.DefaultButtonModel;
import javax.swing.JCheckBoxMenuItem;

/**
 * One of the "Colour presets" rows of the settings menu and the List options menu (1.1.0 parts H and J). Two kinds, both
 * standing their words in the check items' column:
 *
 * <p>A <b>preset row</b> ({@link #preset}) is the preset's name and, at the row's right end, its two colours as two small
 * squares - a rise's first, then a fall's - side by side in the space one {@link SwatchRow} swatch takes (24 px: 11, a 2 px
 * gap, 11), each in the swatch's own 1 px frame. A press anywhere on the row is its action; the panel's listener sets both
 * colours. The squares are read as the row paints, so a preset whose pair can change (Slot 1) is always drawn as it is.
 * The row can carry the menu's tick (part J) - the check item's own, drawn by the look and feel - and the tick is the
 * PANEL's to set ({@link #setTicked}): it says which preset's pair is in use, so a press never leaves one behind by
 * itself (the model ignores a selection from a click) and the panel puts the ticks right after the colours change.
 *
 * <p>A <b>plain row</b> ({@link #plain}) is the same row with no squares and no tick: the words alone ("Save current colours
 * to Slot 1"), its press the action.
 *
 * <p>Both are {@link JCheckBoxMenuItem}s for the reason {@link SwatchRow} is: the look and feel lays a check item's words
 * out in the menu's own check column and a row of any other kind stands them about 4 px to the left of it. The face, the
 * row's height and where the squares end are SwatchRow's, through the statics it shares.
 */
final class SetRow extends JCheckBoxMenuItem
{
	/** The gap between the two squares, in px. */
	private static final int SQUARE_GAP = 2;
	/** Each square's width: what is left of a swatch after the gap, halved (24 - 2 = 22, so 11). */
	private static final int SQUARE_WIDTH = (BankPriceMovementPanel.SWATCH_WIDTH - SQUARE_GAP) / 2;

	/** The rise's colour as the row paints, or null for a plain row. */
	@Nullable
	private final Supplier<Color> up;
	/** The fall's colour as the row paints, or null for a plain row. */
	@Nullable
	private final Supplier<Color> down;
	private final Ticked ticks = new Ticked();

	private SetRow(String label, @Nullable Supplier<Color> up, @Nullable Supplier<Color> down)
	{
		super(label);
		this.up = up;
		this.down = down;
		setModel(ticks);
		setFont(Widgets.sans(12));
	}

	/** A preset row: {@code label} and its two squares, each colour asked at every paint. */
	static SetRow preset(String label, Supplier<Color> up, Supplier<Color> down)
	{
		return new SetRow(label, up, down);
	}

	/** A plain row: {@code label} alone - no squares, no tick. */
	static SetRow plain(String label)
	{
		return new SetRow(label, null, null);
	}

	/**
	 * Puts the tick on or takes it off, and repaints the row when that changed it. Only the panel calls this: a click can
	 * not do it, so the tick can never disagree with the colours in use.
	 */
	void setTicked(boolean ticked)
	{
		ticks.tick(ticked);
	}

	/**
	 * A button model whose selection is set by {@link #tick} alone: {@code setSelected} - which a click's toggle would call -
	 * does nothing, and the look and feel reads the tick through {@code isSelected} as it does for any check item.
	 */
	private static final class Ticked extends DefaultButtonModel
	{
		private boolean ticked;

		void tick(boolean on)
		{
			if (ticked != on)
			{
				ticked = on;
				fireStateChanged();
			}
		}

		@Override
		public boolean isSelected()
		{
			return ticked;
		}

		@Override
		public void setSelected(boolean selected)
		{
		}
	}

	/** The look and feel's size for the words, and - for a preset row - room for the swatch the squares stand in. */
	@Override
	public Dimension getPreferredSize()
	{
		final Dimension words = super.getPreferredSize();
		return up == null ? words : SwatchRow.withSwatchRoom(words);
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		final Supplier<Color> rise = up;
		final Supplier<Color> fall = down;
		if (rise == null || fall == null)
		{
			return;
		}
		final Rectangle s = SwatchRow.swatchBounds(this);
		SwatchRow.paintBox(g, s.x, s.y, SQUARE_WIDTH, s.height, rise.get());
		SwatchRow.paintBox(g, s.x + s.width - SQUARE_WIDTH, s.y, SQUARE_WIDTH, s.height, fall.get());
	}
}
