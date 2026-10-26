package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.DynamicGridLayout;

/**
 * What the tests ask of {@link Widgets} that the shipped plugin no longer carries (release 1.2.2, the trim): the text
 * a face cuts a string to, and the width the widest row inside a column asks for. Nothing a player runs called them.
 *
 * <p>The cuts go through {@link Widgets#setFitted} and {@link Widgets#setFittedName} on a label of the face, which is
 * the road the sidebar's own labels take, so what is measured is what is painted.
 */
final class WidgetsProbe
{
	private WidgetsProbe()
	{
	}

	/** {@code text} cut to what {@code width} px of {@code font} can hold; the whole text when it fits. */
	static String fit(Font font, String text, int width)
	{
		final JLabel label = new JLabel();
		label.setFont(font);
		Widgets.setFitted(label, text, width);
		return label.getText();
	}

	/** {@link #fit} for an ITEM NAME: the same cut, except that a dose suffix is kept whole. */
	static String fitName(Font font, String text, int width)
	{
		final JLabel label = new JLabel();
		label.setFont(font);
		Widgets.setFittedName(label, text, width);
		return label.getText();
	}

	/**
	 * The width the widest row inside {@code c} asks for, in px: the width rule measured rather than trusted.
	 * Descends into nested columns (their own preferred width is only their widest row) and takes every other
	 * child at its preferred width - a chip row counts as its three chips plus gaps, which is exactly the
	 * width its cells need.
	 */
	static int widest(Container c)
	{
		int w = 0;
		for (Component child : c.getComponents())
		{
			if (child instanceof JPanel && ((JPanel) child).getLayout() instanceof DynamicGridLayout)
			{
				w = Math.max(w, widest((Container) child));
			}
			else
			{
				w = Math.max(w, child.getPreferredSize().width);
			}
		}
		return w;
	}
}
