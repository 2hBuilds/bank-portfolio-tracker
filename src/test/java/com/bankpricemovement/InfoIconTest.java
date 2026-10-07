package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 1.1.1 part I2: the "i" icon's pixels - the user's pick of six drawn (variant 2, "Filled disc + i", sheet
 * {@code docs/handoff/lab/history-info-icon-2026-10-07.png}): an 11 px disc in the gears' grey with a one-pixel dot and a
 * one-pixel-wide stem cut out in the row's background colour, white under the mouse, every pixel whole or clear, and nothing
 * outside its 12 x 12 box.
 */
public class InfoIconTest
{
	/** The box's own pixel pattern the sheet draws, by disc row: how many pixels of each row are the disc (cut or not). */
	private static final int[] ROW_WIDTHS = {5, 7, 9, 11, 11, 11, 11, 11, 9, 7, 5, 0};

	private static BufferedImage image(Color disc)
	{
		return (BufferedImage) InfoIcon.icon(disc).getImage();
	}

	private static boolean clear(BufferedImage image, int x, int y)
	{
		return image.getRGB(x, y) >>> 24 == 0;
	}

	@Test
	public void theBoxIsTwelveByTwelveLikeTheGears()
	{
		final BufferedImage image = image(Color.GRAY);
		assertEquals(12, InfoIcon.SIZE);
		assertEquals(GearsIcon.SIZE, InfoIcon.SIZE);
		assertEquals(InfoIcon.SIZE, image.getWidth());
		assertEquals(InfoIcon.SIZE, image.getHeight());
		assertEquals(InfoIcon.SIZE, InfoIcon.icon(Color.GRAY).getIconWidth());
		assertEquals(InfoIcon.SIZE, InfoIcon.icon(Color.GRAY).getIconHeight());
	}

	/** The disc is 11 px across and 11 tall, rounded: rows of 5, 7, 9, 11 (five of them), 9, 7 and 5, in the box's right 11 columns and top 11 rows. */
	@Test
	public void theDiscIsElevenAcrossAndRoundedAndStandsAgainstTheBoxsRightAndTop()
	{
		final BufferedImage image = image(Color.GRAY);
		assertEquals(11, InfoIcon.DISC);
		int minX = InfoIcon.SIZE;
		int maxX = -1;
		int minY = InfoIcon.SIZE;
		int maxY = -1;
		for (int y = 0; y < InfoIcon.SIZE; y++)
		{
			int width = 0;
			int first = -1;
			int last = -1;
			for (int x = 0; x < InfoIcon.SIZE; x++)
			{
				if (!clear(image, x, y))
				{
					width++;
					first = first < 0 ? x : first;
					last = x;
					minX = Math.min(minX, x);
					maxX = Math.max(maxX, x);
					minY = Math.min(minY, y);
					maxY = Math.max(maxY, y);
				}
			}
			assertEquals("row " + y + " is as wide as the disc is there", ROW_WIDTHS[y], width);
			if (width > 0)
			{
				assertEquals("row " + y + " has no hole of clear pixels in it", width, last - first + 1);
				assertEquals("row " + y + " is centred on the disc's centre column (6)", 12, first + last);
			}
		}
		assertEquals("the disc's left edge", 1, minX);
		assertEquals("its right edge is the box's", InfoIcon.SIZE - 1, maxX);
		assertEquals("its top is the box's", 0, minY);
		assertEquals("its width", InfoIcon.DISC, maxX - minX + 1);
		assertEquals("its height", InfoIcon.DISC, maxY - minY + 1);
	}

	/** The "i": a dot at row 1, a stem at rows 3 to 9, all in column 6 and all the row's colour; the rest of the disc is the ink. */
	@Test
	public void theIIsCutOutInTheRowsColourAndTheRestOfTheDiscIsTheInk()
	{
		for (Color ink : new Color[]{Color.GRAY, Color.WHITE, new Color(1, 2, 3)})
		{
			final BufferedImage image = image(ink);
			final int inkRgb = ink.getRGB();
			final int cutRgb = ColorScheme.DARK_GRAY_COLOR.getRGB();
			assertEquals("the cut is the caption row's background", ColorScheme.DARK_GRAY_COLOR, InfoIcon.CUT);
			int cut = 0;
			int inked = 0;
			for (int y = 0; y < InfoIcon.SIZE; y++)
			{
				for (int x = 0; x < InfoIcon.SIZE; x++)
				{
					if (clear(image, x, y))
					{
						continue;
					}
					assertEquals("pixel (" + x + ", " + y + ") is whole, not blended", 255, image.getRGB(x, y) >>> 24);
					final boolean iPixel = x == 6 && (y == 1 || y >= 3 && y <= 9);
					assertEquals("pixel (" + x + ", " + y + ")", iPixel ? cutRgb : inkRgb, image.getRGB(x, y));
					if (iPixel)
					{
						cut++;
					}
					else
					{
						inked++;
					}
				}
			}
			assertEquals("a one-pixel dot and a seven-pixel stem", 8, cut);
			assertEquals("the disc round them: 5 + 7 + 9 + 5 x 11 + 9 + 7 + 5 = 97 pixels, 8 of them cut", 97 - 8, inked);
			assertTrue("the gap between dot and stem is the disc's (row 2)", image.getRGB(6, 2) == inkRgb);
			assertTrue("and the rim under the stem (row 10)", image.getRGB(6, 10) == inkRgb);
		}
	}

	/** Under the mouse the disc turns white and nothing else moves: the two pictures differ in exactly the disc's pixels. */
	@Test
	public void whiteUnderTheMouseChangesOnlyTheDisc()
	{
		final BufferedImage grey = image(Color.GRAY);
		final BufferedImage white = image(Color.WHITE);
		for (int y = 0; y < InfoIcon.SIZE; y++)
		{
			for (int x = 0; x < InfoIcon.SIZE; x++)
			{
				if (clear(grey, x, y))
				{
					assertTrue("clear stays clear at (" + x + ", " + y + ")", clear(white, x, y));
				}
				else if (grey.getRGB(x, y) == ColorScheme.DARK_GRAY_COLOR.getRGB())
				{
					assertEquals("the cut is the same at (" + x + ", " + y + ")", grey.getRGB(x, y), white.getRGB(x, y));
				}
				else
				{
					assertEquals(Color.GRAY.getRGB(), grey.getRGB(x, y));
					assertEquals(Color.WHITE.getRGB(), white.getRGB(x, y));
				}
			}
		}
	}

	/** Nothing in the box's left column, bottom row or four corners - what the gears' box leaves to the gap and the line. */
	@Test
	public void nothingFallsOutsideTheDiscs11x11()
	{
		final BufferedImage image = image(Color.GRAY);
		for (int i = 0; i < InfoIcon.SIZE; i++)
		{
			assertTrue("the left column at row " + i, clear(image, 0, i));
			assertTrue("the bottom row at column " + i, clear(image, i, InfoIcon.SIZE - 1));
		}
		assertTrue(clear(image, InfoIcon.SIZE - 1, 0));
		assertTrue(clear(image, 1, 0));
		assertTrue(clear(image, InfoIcon.SIZE - 1, InfoIcon.SIZE - 2));
	}
}
