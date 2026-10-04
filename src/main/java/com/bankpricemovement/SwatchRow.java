package com.bankpricemovement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import javax.swing.DefaultButtonModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.event.MenuDragMouseEvent;

/**
 * One of the settings menu's rows that carries a swatch of a colour at its right end (1.1.0): "Up colour", "Down colour"
 * and "Single chart colour". All three are {@link JCheckBoxMenuItem}s, because the look and feel lays a check item's
 * words out in the menu's own check column - the column every tick in the menu sits in - and a row that is not one
 * stands its words about 4 px to the left of it, whatever blank icon it is handed (found under RuneLite's look and feel,
 * 1.1.0 part C: the two colour rows of part B read left of the Include items around them).
 *
 * <p><b>Two kinds.</b> A colour row ({@code ticks} false) is a way into the colour picker and not a switch: its model
 * never selects, so no tick is ever drawn and no press can leave one behind, and a press ANYWHERE on it - words, gap or
 * swatch - is its action (Swing takes the menu down and fires the action listener). "Single chart colour" ({@code ticks}
 * true) is an ordinary check item whose swatch shows only while it is ticked, and there a press on the swatch opens the
 * picker ({@code onSwatch}) while a press anywhere else on the row ticks or unticks it as any check item does.
 *
 * <p><b>Telling the two presses apart.</b> Swing hands a menu item its click through {@link #doClick} with no position,
 * from either of its two mouse roads (the plain mouse event, and the menu-drag event the popup's own grabber builds), so
 * the row notes where the pointer last was from both and reads that when the click comes. The note is dropped on exit and
 * after every click, so a key press on the row (which has no pointer) always ticks.
 */
final class SwatchRow extends JCheckBoxMenuItem
{
	/** The gap a row keeps between its words and its swatch, at the least, in px. */
	private static final int SWATCH_GAP = 8;
	/** The swatch's 1 px frame: grey 110, so a swatch as dark as the menu still reads as a box. */
	private static final Color SWATCH_BORDER = new Color(110, 110, 110);

	private final Supplier<Color> colour;
	private final boolean ticks;
	@Nullable
	private final Runnable onSwatch;
	/** Where the pointer last was over this row, in its own coordinates; null when it has left or just clicked. */
	@Nullable
	private Point pointer;

	/**
	 * @param colour   the swatch's colour, asked at every paint
	 * @param ticks    true for a check item (swatch only while ticked), false for a colour row that never selects
	 * @param onSwatch what a press on a ticked check item's swatch does instead of ticking it; null for none
	 */
	SwatchRow(String text, Supplier<Color> colour, boolean ticks, @Nullable Runnable onSwatch)
	{
		super(text);
		this.colour = colour;
		this.ticks = ticks;
		this.onSwatch = onSwatch;
		if (!ticks)
		{
			setModel(new NeverSelected());
		}
		setFont(Widgets.sans(12));
	}

	/**
	 * A button model that cannot be selected: the colour rows' - a press fires the action and leaves no tick. The colour
	 * preset rows ({@link SetRow}) share this class's way of standing in the check items' column.
	 */
	static final class NeverSelected extends DefaultButtonModel
	{
		@Override
		public void setSelected(boolean selected)
		{
		}
	}

	/** Whether the swatch is drawn: always for a colour row, only while ticked for a check item. */
	private boolean swatchShown()
	{
		return !ticks || isSelected();
	}

	/** The swatch, 24 x 12, inset from the row's right as the menu's captions are and centred between its insets. */
	private Rectangle swatch()
	{
		return swatchBounds(this);
	}

	/**
	 * The swatch's place in a swatch row ({@code row}'s own size and insets): 24 x 12, {@link BankPriceMovementPanel#ROW_GAP}
	 * in from the row's right end as the menu's captions are, and centred between the row's insets. One rule for the rows
	 * of this class and the colour preset rows ({@link SetRow}), so every swatch in the menu ends on the same column.
	 */
	static Rectangle swatchBounds(JComponent row)
	{
		final Insets in = row.getInsets();
		final int x = row.getWidth() - BankPriceMovementPanel.ROW_GAP - BankPriceMovementPanel.SWATCH_WIDTH;
		final int y = in.top + (row.getHeight() - in.top - in.bottom - BankPriceMovementPanel.SWATCH_HEIGHT) / 2;
		return new Rectangle(x, y, BankPriceMovementPanel.SWATCH_WIDTH, BankPriceMovementPanel.SWATCH_HEIGHT);
	}

	/** {@code words}, the look and feel's size for a row's text, widened for the swatch's gap and its width. */
	static Dimension withSwatchRoom(Dimension words)
	{
		return new Dimension(words.width + SWATCH_GAP + BankPriceMovementPanel.SWATCH_WIDTH, words.height);
	}

	/** One box of colour: {@code colour} filled over {@code x, y, w, h} with the swatch's 1 px frame drawn inside it. */
	static void paintBox(Graphics g, int x, int y, int w, int h, Color colour)
	{
		g.setColor(colour);
		g.fillRect(x, y, w, h);
		g.setColor(SWATCH_BORDER);
		g.drawRect(x, y, w - 1, h - 1);
	}

	/** The swatch's end of the row, full height: a press a little beside the swatch is still aimed at it. */
	private boolean overSwatch(@Nullable Point p)
	{
		return p != null && p.x >= swatch().x && p.x < getWidth() && p.y >= 0 && p.y < getHeight();
	}

	/** The look and feel's size for the words, and room for the swatch beside them whether it is drawn or not. */
	@Override
	public Dimension getPreferredSize()
	{
		return withSwatchRoom(super.getPreferredSize());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (!swatchShown())
		{
			return;
		}
		final Rectangle s = swatch();
		paintBox(g, s.x, s.y, s.width, s.height, colour.get());
	}

	/** A click on a ticked check item's swatch is the swatch's action and ticks nothing; every other click is Swing's. */
	@Override
	public void doClick(int pressTime)
	{
		final Runnable swatchAction = onSwatch;
		final boolean aimed = swatchAction != null && ticks && isSelected() && overSwatch(pointer);
		pointer = null;
		if (aimed)
		{
			swatchAction.run();
			return;
		}
		super.doClick(pressTime);
	}

	@Override
	protected void processMouseEvent(MouseEvent e)
	{
		note(e);
		super.processMouseEvent(e);
	}

	@Override
	public void processMenuDragMouseEvent(MenuDragMouseEvent e)
	{
		note(e);
		super.processMenuDragMouseEvent(e);
	}

	private void note(MouseEvent e)
	{
		pointer = e.getID() == MouseEvent.MOUSE_EXITED ? null : e.getPoint();
	}
}
