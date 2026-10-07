package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;

/**
 * The note icon on the Net Worth History tab (1.1.1 part I2, the user's pick of six drawn, 2026-10-07: variant 2, "Filled disc
 * + i"): a filled disc {@value #DISC} px across with a lower-case "i" cut out of it, in a {@value #SIZE} x {@value #SIZE} box
 * like the two gears it stands beside. Like {@link EyeIcon} it is a PIXEL icon - every pixel is the disc's ink, the cut's
 * colour or clear, no anti-aliasing, no blend - so it is as sharp as the game's own sprites at any display scale, and nothing
 * about it depends on a font or a look and feel. There is no image file.
 *
 * <p>The disc is the gears' grey at rest and white under the mouse ({@link BankPriceMovementPanel#legacyInfoIcon}); the "i" -
 * a one-pixel dot, a one-pixel gap and a seven-pixel stem - is cut out in the caption row's own background, so it reads as a
 * hole in the disc. The disc stands against the box's right edge and top, which is where the sheet drew it: the gap to the
 * gears beside it is then the gears' own {@link BankPriceMovementPanel#LIST_OPTIONS_GAP}, to the pixel.
 */
final class InfoIcon
{
	/** The box's side in px: the same 12 px the gears and the eye are drawn at. */
	static final int SIZE = 12;
	/** The disc's width and height in px. */
	static final int DISC = 11;
	/** The colour of the cut-out "i": the caption row's background, so the disc reads as holed. */
	static final Color CUT = ColorScheme.DARK_GRAY_COLOR;

	/**
	 * The disc, row by row: {@code X} is the disc's ink, {@code i} the cut-out and {@code .} clear. The dot is row 1, the stem
	 * rows 3 to 9; rows 2 and 10 are whole.
	 */
	private static final String[] ROWS = {
		"....XXXXX...",
		"...XXXiXXX..",
		"..XXXXXXXXX.",
		".XXXXXiXXXXX",
		".XXXXXiXXXXX",
		".XXXXXiXXXXX",
		".XXXXXiXXXXX",
		".XXXXXiXXXXX",
		"..XXXXiXXXX.",
		"...XXXiXXX..",
		"....XXXXX...",
		"............",
	};

	private InfoIcon()
	{
	}

	/**
	 * The icon with its disc in {@code disc}: every disc pixel that colour, every "i" pixel {@link #CUT}, every other pixel
	 * fully transparent.
	 */
	static ImageIcon icon(final Color disc)
	{
		final int ink = new Color(disc.getRed(), disc.getGreen(), disc.getBlue()).getRGB();
		final int cut = new Color(CUT.getRed(), CUT.getGreen(), CUT.getBlue()).getRGB();
		final BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < SIZE; y++)
		{
			for (int x = 0; x < SIZE; x++)
			{
				final char pixel = ROWS[y].charAt(x);
				if (pixel == 'X')
				{
					image.setRGB(x, y, ink);
				}
				else if (pixel == 'i')
				{
					image.setRGB(x, y, cut);
				}
			}
		}
		return new ImageIcon(image);
	}
}
