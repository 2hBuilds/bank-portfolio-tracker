package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The five rows of one tab's change settings in its options menu, and the settings they edit (release 1.2.1; part 2 made the
 * Net Worth History tab's menu a second user of the same builder): the caption "GP change size" with a {@link SizeBar} under it,
 * the row "Opacity [box] %", the caption "Percent change size" and its bar. The Items tab's List options menu holds one of these
 * and the History tab's options menu another - each with its OWN {@link ChangeText}, so the two tabs never share a size or an
 * opacity. The menu code is written once here and nowhere else.
 *
 * <p><b>What a holder knows.</b> The settings in force ({@link #text}) and the two bars and the box drawn from them. A press on
 * an unlit bar cell, or the box's commit, hands the NEXT settings to {@code press} - the panel's own road for that tab, which
 * redraws the tab and stores the choice and so calls {@link #adopt} - and nothing here decides what is lit, so a bar can never show
 * one size while the rows are drawn in another. {@code pitch} is the tab's row pitch (a row's height and the gap under it) at a
 * given setting: a size change owes the list's scroll place a scale of the new pitch over the old ({@link #takeScale}), so the
 * reader stays on about the same row.
 *
 * <p><b>Opening the menu targets no box</b> (the preset boxes' Z6 rule): the menu hands the keyboard to the row
 * ({@link #focusTarget}) when it opens, so the caret blinks only in a box the reader clicked. A press anywhere in the menu that is not a menu item
 * and not the box ({@link #wireDrop}) commits what was typed at once, by Enter's rule, and takes the keyboard off the box; the box
 * also commits on Enter, on focus lost and when the menu closes. Anything but a whole number from 20 to 100 turns it red and
 * saves nothing.
 */
final class ChangeSettings
{
	/** The box's width, in px: its borders and padding take 14 of it, and "100" is 18 px in the small face, so 34 leaves 2 px to spare. */
	static final int FIELD_WIDTH = 34;
	/** The gap between the box and the "%" after it, in px. */
	private static final int FIELD_GAP = 3;

	private final Consumer<ChangeText> press;
	private final ToIntFunction<ChangeText> pitch;
	private final SizeBar gpBar;
	private final SizeBar pctBar;
	private final Widgets.PlaceholderField box;
	private final JPanel row;
	private ChangeText text;
	private double scale = 1.0d;

	/**
	 * @param initial the settings the menu opens on
	 * @param press   the tab's road for a choice the menu made: draws it and stores it
	 * @param pitch   one row's height plus the gap under it, in px, at the settings given
	 */
	ChangeSettings(final ChangeText initial, final Consumer<ChangeText> press, final ToIntFunction<ChangeText> pitch)
	{
		this.text = initial;
		this.press = press;
		this.pitch = pitch;
		gpBar = bar(BankPriceMovementPanel.GP_SIZE_TIP, size -> this.text.withGp(size));
		pctBar = bar(BankPriceMovementPanel.PCT_SIZE_TIP, size -> this.text.withPct(size));
		box = Widgets.gpField("");
		Widgets.fixed(box, FIELD_WIDTH, box.getPreferredSize().height);
		// On the panel AND the text field, as every box in this sidebar does: the field is the mouse target inside its panel.
		box.setToolTipText(BankPriceMovementPanel.OPACITY_TIP);
		box.getTextField().setToolTipText(BankPriceMovementPanel.OPACITY_TIP);
		box.addActionListener(e -> commit());
		box.getTextField().addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusLost(final FocusEvent e)
			{
				// A temporary loss is the window going away, not the reader leaving the box - the rule every box here follows.
				if (!e.isTemporary())
				{
					commit();
				}
			}
		});
		row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(1, BankPriceMovementPanel.ROW_GAP + 2, 4, BankPriceMovementPanel.ROW_GAP));
		// Focusable for the reason the settings menu's preset row is: it is where the keyboard is parked when the menu opens.
		row.setFocusable(true);
		row.add(Widgets.label(BankPriceMovementPanel.OPACITY_TEXT, Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR));
		row.add(Box.createHorizontalGlue());
		row.add(box);
		row.add(Box.createHorizontalStrut(FIELD_GAP));
		row.add(Widgets.label("%", Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR));
		render();
	}

	private SizeBar bar(final String tip, final Function<ChangeSize, ChangeText> with)
	{
		final SizeBar bar = new SizeBar(size -> press.accept(with.apply(size)), this::drop);
		bar.setToolTipText(tip);
		return bar;
	}

	/** The settings in force: what the bars light and the box prints. */
	ChangeText text()
	{
		return text;
	}

	/** Adds the five rows to {@code menu}, in order: caption, bar, the opacity row, caption, bar. */
	void addTo(final JPopupMenu menu)
	{
		menu.add(BankPriceMovementPanel.buildMenuCaption(BankPriceMovementPanel.GP_SIZE_TEXT));
		menu.add(barRow(gpBar));
		menu.add(row);
		menu.add(BankPriceMovementPanel.buildMenuCaption(BankPriceMovementPanel.PCT_SIZE_TEXT));
		menu.add(barRow(pctBar));
	}

	/** A bar in the menu's row: air above and below it, and its left edge on the menu's words (the captions' inset and two more). */
	private static JPanel barRow(final SizeBar bar)
	{
		final JPanel holder = new JPanel(new BorderLayout());
		holder.setOpaque(false);
		holder.setBorder(new EmptyBorder(3, BankPriceMovementPanel.ROW_GAP + 2, 4, BankPriceMovementPanel.ROW_GAP));
		holder.add(bar, BorderLayout.CENTER);
		return holder;
	}

	/**
	 * Hands the one hand-over to every part of {@code menu} a press can land on that is not a menu item - the popup itself, the
	 * rules, the captions, the bars' rows and this row - so a press anywhere in it that is not on the box takes the keyboard
	 * off the box ({@link #drop}). A menu item is left out: it closes the menu, and closing the menu commits the box. Called once
	 * the menu is complete.
	 */
	void wireDrop(final JPopupMenu menu)
	{
		final MouseAdapter leave = new MouseAdapter()
		{
			@Override
			public void mousePressed(final MouseEvent e)
			{
				if (Widgets.isPress(e))
				{
					drop();
				}
			}
		};
		menu.addMouseListener(leave);
		for (final Component part : menu.getComponents())
		{
			if (!(part instanceof JMenuItem))
			{
				part.addMouseListener(leave);
			}
		}
	}

	/**
	 * Where the keyboard goes when the menu opens (Z6's rule): the row, which is focusable, and never the box in it - a shown
	 * popup window gives its first focusable thing the keyboard, and that would be the box with its caret blinking.
	 */
	Component focusTarget()
	{
		return row;
	}

	/**
	 * Makes {@code want} the settings in force and draws the menu's controls from it. A change also owes the list's scroll place
	 * a scale ({@link #takeScale}). True when the settings changed.
	 */
	boolean adopt(final ChangeText want)
	{
		final boolean changed = !want.equals(text);
		if (changed)
		{
			scale *= (double) pitch.applyAsInt(want) / pitch.applyAsInt(text);
		}
		text = want;
		render();
		return changed;
	}

	/** The scroll scale owed since the last call - the rows' new pitch over the old, 1 when nothing is owed - and settles it. */
	double takeScale()
	{
		final double owed = scale;
		scale = 1.0d;
		return owed;
	}

	/**
	 * Draws the three controls from {@link #text}: each bar lit on its size, the box printing the opacity in force and not red. A
	 * box already printing that is left alone, so the caret of a box being typed in does not move.
	 */
	void render()
	{
		gpBar.light(text.gp());
		pctBar.light(text.pct());
		final String shown = Integer.toString(text.brightness());
		if (!shown.equals(box.getText()))
		{
			box.setText(shown);
		}
		Widgets.markInvalid(box, false);
	}

	/**
	 * A press inside the menu that is not on the box: what is in the box is committed at once, by Enter's rule, and when the box
	 * owns the keyboard it goes to the row beside it. Committed NOW and not left to the box's focus-lost, which a move of the
	 * keyboard only QUEUES - it would run after the press that follows (a bar's cell) had redrawn the box from the stored value
	 * and thrown the typed one away. The focus-lost commit that comes later finds the stored value there and saves nothing.
	 */
	void drop()
	{
		commit();
		if (box.getTextField().isFocusOwner())
		{
			row.requestFocusInWindow();
		}
	}

	/**
	 * The box's commit - on Enter, on focus lost and when the menu closes. The text is read as a whole number from
	 * {@link ChangeText#MIN_BRIGHTNESS} to {@link ChangeText#MAX_BRIGHTNESS}: a reading goes to {@code press}, and anything else
	 * turns the box red and saves nothing, the setting in force staying what it was. The same value again redraws nothing.
	 */
	void commit()
	{
		final String typed = box.getText().trim();
		final int value = typed.matches("[0-9]{1,3}") ? Integer.parseInt(typed) : -1;
		final boolean ok = ChangeText.isBrightness(value);
		Widgets.markInvalid(box, !ok);
		if (ok)
		{
			press.accept(text.withBrightness(value));
		}
	}
}
