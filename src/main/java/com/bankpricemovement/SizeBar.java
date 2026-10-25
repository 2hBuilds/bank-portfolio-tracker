package com.bankpricemovement;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The one-line bar of four cells - Small, Normal, Large, Largest - the List options menu's two size settings are chosen on
 * (release 1.2.1; the user's look 4 of the 2026-10-24 sheet): a 1 px {@link ColorScheme#MEDIUM_GRAY_COLOR} frame and 1 px
 * dividers, the unlit cells {@link ColorScheme#DARKER_GRAY_COLOR} with {@link ColorScheme#LIGHT_GRAY_COLOR} plain 11 px words, the
 * lit cell a grey ground (80, 80, 80) with a white bold 11 px word. The four cells share the width equally.
 *
 * <p>A LEFT press ({@link Widgets#isPress}) on an UNLIT cell hands its step to {@code onChoose}, and the caller lights the cell
 * ({@link #light}) from its own state - nothing here decides which cell is lit, so the bar can never show one size while the rows
 * are drawn in another. A press on the lit cell chooses nothing, since it is already the answer. Every left press, lit cell or
 * not, runs {@code onPress} first: the menu uses it to take the keyboard off its brightness box, which is how a click on the bar
 * ends the box's typing.
 */
final class SizeBar extends JPanel
{
	/** The lit cell's ground: grey, not the brand orange - the user's pick for a menu, where orange is a hover. */
	static final Color LIT_GROUND = new Color(80, 80, 80);
	/** The words' size, in px, lit or not. */
	static final int TEXT_PX = 11;
	/** The air every cell keeps at the least once its word is lit in bold, in px: the bar is never narrower than its words need. */
	static final int MIN_SPARE = 2;

	private final ChangeSize[] steps = ChangeSize.values();
	private final JLabel[] cells = new JLabel[steps.length];
	@Nullable
	private ChangeSize lit;

	SizeBar(Consumer<ChangeSize> onChoose, Runnable onPress)
	{
		super(new GridLayout(1, ChangeSize.values().length, 1, 0));
		setOpaque(true);
		setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		setBorder(new LineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		for (int i = 0; i < steps.length; i++)
		{
			final ChangeSize step = steps[i];
			final JLabel cell = new JLabel(step.toString());
			cell.setHorizontalAlignment(SwingConstants.CENTER);
			cell.setBorder(new EmptyBorder(2, 0, 2, 0));
			cell.setOpaque(true);
			cell.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mousePressed(MouseEvent e)
				{
					if (!Widgets.isPress(e))
					{
						return;
					}
					onPress.run();
					if (step != lit)
					{
						onChoose.accept(step);
					}
				}
			});
			cells[i] = cell;
			add(cell);
		}
		light(null);
	}

	/**
	 * The bar asks for what its words need in BOLD, the face of the lit cell: the widest word ("Largest") plus {@link #MIN_SPARE},
	 * four times over, with the frame and the three dividers. Without it the bar's width followed whichever cell was lit, and a
	 * menu with no wider row cut a lit "Largest" to its cell.
	 */
	@Override
	public Dimension getPreferredSize()
	{
		final Dimension wanted = super.getPreferredSize();
		final FontMetrics bold = getFontMetrics(Widgets.sansBold(TEXT_PX));
		int widest = 0;
		for (final ChangeSize step : steps)
		{
			widest = Math.max(widest, bold.stringWidth(step.toString()));
		}
		wanted.width = Math.max(wanted.width, steps.length * (widest + MIN_SPARE) + steps.length + 1);
		return wanted;
	}

	/** Lights the cell of {@code step} and unlights the others; null lights none. */
	void light(@Nullable ChangeSize step)
	{
		lit = step;
		for (int i = 0; i < steps.length; i++)
		{
			final boolean on = steps[i] == step;
			final JLabel cell = cells[i];
			cell.setFont(on ? Widgets.sansBold(TEXT_PX) : Widgets.sans(TEXT_PX));
			cell.setBackground(on ? LIT_GROUND : ColorScheme.DARKER_GRAY_COLOR);
			cell.setForeground(on ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
		}
		repaint();
	}

	/**
	 * The hover goes on the bar AND on every cell: a cell carries a mouse listener, so it is its own mouse target and a hover on
	 * the bar alone would be lost the moment the pointer crossed onto one (playbook 7.5).
	 */
	@Override
	public void setToolTipText(@Nullable String text)
	{
		super.setToolTipText(text);
		for (final JLabel cell : cells)
		{
			cell.setToolTipText(text);
		}
	}
}
