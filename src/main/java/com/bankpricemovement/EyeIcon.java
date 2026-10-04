package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;

/**
 * The "Hide amounts" eye (1.1.0 part E): a PIXEL eye on a {@value #SIZE} x {@value #SIZE} grid, the user's pick (design D of
 * four drawn, 2026-10-04: "drawn on RuneLite's pixel grid; slashed when hidden"). Every pixel is the icon's ink or clear -
 * no anti-aliasing, no blend - so it is as sharp as the game's own sprites at any display scale and nothing about it
 * depends on a font or a look and feel. Built pixel by pixel into an image once per look; there is no image file.
 *
 * <p>While the amounts SHOW the eye is open: an almond outline round a pupil, in rows {@value #FIRST_ROW} to
 * {@value #LAST_ROW} of the grid with the rows above and below them clear. While they are HIDDEN the same eye carries a slash
 * from the bottom-left corner to the top-right: for each column {@code i} the pixel {@code (i, SIZE - 1 - i)} is ink and the
 * pixels either side of it on that row are cleared, so the slash stands apart from the eye it crosses instead of running
 * into its outline.
 *
 * <p>Its ink (1.1.0 part I, the user's pick, 2026-10-04) says which state it is in before it is read: grey 52, near the
 * card's ground and quiet, while the amounts SHOW - the open eye, nothing to draw attention to - and grey 165, the other
 * icons' grey ({@link ColorScheme#LIGHT_GRAY_COLOR}), while they are HIDDEN, the slashed eye that tells a reader why the
 * numbers are dots; white under the mouse in both. {@link #ink} is that rule.
 */
final class EyeIcon
{
	/** The grid's side in px: the same 12 px the settings icon is drawn at ({@link Widgets#GEAR_SIZE}). */
	static final int SIZE = 12;
	/** The open eye's ink at rest, while the amounts show (part I): grey 52 - quiet. */
	static final Color SHOWN_INK = new Color(52, 52, 52);
	/** The slashed eye's ink at rest, while the amounts are hidden (part I): the other icons' grey, 165. */
	static final Color HIDDEN_INK = ColorScheme.LIGHT_GRAY_COLOR;
	/** The open eye's first and last rows of ink; every other row of the grid is clear. */
	static final int FIRST_ROW = 2;
	static final int LAST_ROW = 9;

	/** The open eye, rows {@link #FIRST_ROW} to {@link #LAST_ROW}: {@code X} is ink, {@code .} is clear. */
	private static final String[] EYE = {
		"....XXXX....",
		"..XX....XX..",
		".X...XX...X.",
		"X...XXXX...X",
		"X...XXXX...X",
		".X...XX...X.",
		"..XX....XX..",
		"....XXXX....",
	};

	private EyeIcon()
	{
	}

	/**
	 * The ink the eye is drawn in (part I): white while {@code hot} - the pointer is over it - in either state, otherwise
	 * {@link #HIDDEN_INK} while {@code hidden} and {@link #SHOWN_INK} while the amounts show.
	 */
	static Color ink(final boolean hidden, final boolean hot)
	{
		if (hot)
		{
			return Color.WHITE;
		}
		return hidden ? HIDDEN_INK : SHOWN_INK;
	}

	/**
	 * The eye as an icon: open while {@code hidden} is false, slashed while it is true, every ink pixel {@code ink} and every
	 * other pixel fully transparent.
	 */
	static ImageIcon icon(final boolean hidden, final Color ink)
	{
		final int argb = new Color(ink.getRed(), ink.getGreen(), ink.getBlue()).getRGB();
		final BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int row = 0; row < EYE.length; row++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				if (EYE[row].charAt(x) == 'X')
				{
					image.setRGB(x, FIRST_ROW + row, argb);
				}
			}
		}
		if (hidden)
		{
			for (int i = 0; i < SIZE; i++)
			{
				final int y = SIZE - 1 - i;
				if (i > 0)
				{
					image.setRGB(i - 1, y, 0);
				}
				if (i < SIZE - 1)
				{
					image.setRGB(i + 1, y, 0);
				}
				image.setRGB(i, y, argb);
			}
		}
		return new ImageIcon(image);
	}
}
