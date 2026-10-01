package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link NavIcon} (contract C34): the sidebar button's icon - the ONLY way a user reaches this plugin.
 *
 * <p><b>Why this file exists.</b> Its whole coverage was one {@code assertNotNull} on
 * {@code NavigationButton.getIcon()} in the wiring test, which a fully transparent 16 x 16 image satisfies. A
 * {@code setColor} moved after the fills, an alpha-0 colour or coordinates pushed off the raster while redrawing
 * the coin would leave the user relaunching to a blank square in the sidebar with nothing to tell it from a
 * broken plugin, and every test would pass. So the ink is counted, and counted WHERE the four bars and the coin
 * are drawn - a global count alone would survive one bar going missing.
 *
 * <p>No golden bitmap: the coin is filled with antialiasing, whose exact edge pixels drift between JDK builds.
 * The bars and the letters are not antialiased, so their pixels are pinned exactly; the coin is pinned by its
 * colours and by where it lives. This is the same idiom {@code WidgetsTest} uses for the panel's drawn glyphs,
 * and like that one it proves the drawing needs no display.
 */
public class NavIconTest
{
	/** The coin's face gold and the ink of its "2h", the colours Why Lag's icon carries too. */
	private static final Color FACE = new Color(196, 156, 58);
	private static final Color LETTER_INK = new Color(52, 36, 8);

	@Test
	public void theIconIsSixteenSquareArgbWithTheCoinAndFourOrangeBars()
	{
		final BufferedImage img = NavIcon.create();
		assertEquals(NavIcon.SIZE, img.getWidth());
		assertEquals(NavIcon.SIZE, img.getHeight());
		assertEquals("16 x 16 ARGB, what NavigationButton.builder().icon(...) takes",
			BufferedImage.TYPE_INT_ARGB, img.getType());

		// The four fillRects alone are 3x2 + 3x4 + 3x6 + 3x9 = 63 opaque pixels, and fillRect is not antialiased, so
		// 63 is a hard floor before the coin adds anything: 60 leaves real slack and still catches a blank icon.
		assertTrue("the icon put " + ink(img) + " px of ink down", ink(img) > 60);

		// One bar at a time: the four columns the bars stand in, on the bottom row they all reach.
		assertTrue("the first bar is missing", opaque(img, 1, 4, 15, 16));
		assertTrue("the second bar is missing", opaque(img, 5, 8, 15, 16));
		assertTrue("the third bar is missing", opaque(img, 9, 12, 15, 16));
		assertTrue("the fourth bar is missing", opaque(img, 13, 16, 15, 16));

		// ...and their heights, 2, 4, 6 and 9: the top pixel of each is ink and the one above it is ground, so a
		// bar drawn too short or too tall fails.
		assertTrue("the first bar is too short", opaque(img, 2, 3, 14, 15));
		assertFalse("the first bar is too tall", opaque(img, 2, 3, 13, 14));
		assertTrue("the second bar is too short", opaque(img, 6, 7, 12, 13));
		assertFalse("the second bar is too tall", opaque(img, 6, 7, 11, 12));
		assertTrue("the third bar is too short", opaque(img, 10, 11, 10, 11));
		assertFalse("the third bar is too tall", opaque(img, 10, 11, 9, 10));
		assertTrue("the fourth bar is too short", opaque(img, 14, 15, 7, 8));
		assertFalse("the fourth bar is too tall", opaque(img, 14, 15, 6, 7));

		// The coin: its face gold and the dark brown of its letters are both there, and it lives in the top-left.
		assertTrue("the coin's face is missing", hasColour(img, FACE));
		assertTrue("the coin's letters are missing", hasColour(img, LETTER_INK));
		assertTrue("the coin is not in the top-left corner", opaque(img, 2, 7, 2, 7));

		// The arrow this icon used to carry is gone: three pixels that were its head are ground now.
		assertEquals("the arrow head is still drawn at (12, 2)", 0, img.getRGB(12, 2) >>> 24);
		assertEquals("the arrow head is still drawn at (13, 2)", 0, img.getRGB(13, 2) >>> 24);
		assertEquals("the arrow head is still drawn at (9, 1)", 0, img.getRGB(9, 1) >>> 24);

		assertTrue("nothing is drawn in the brand colour", hasColour(img, ColorScheme.BRAND_ORANGE));
		// (15, 0), not (0, 0): the coin covers the top-left corner now, and the top-right is the clear ground.
		assertEquals("the ground stays transparent", 0, img.getRGB(15, 0) >>> 24);
	}

	/**
	 * The javadoc's promise: a fresh image per call, so a plugin restart never shares a bitmap with a button
	 * already removed. Proved by writing into one and reading the other, not by {@code !=} - which any
	 * {@code new BufferedImage} satisfies and which therefore pins nothing.
	 */
	@Test
	public void everyCallDrawsItsOwnImage()
	{
		final BufferedImage first = NavIcon.create();
		final BufferedImage second = NavIcon.create();
		assertNotSame(first, second);
		// (15, 0), a pixel the coin does not cover: the read-back is of ground, not of the coin's rim.
		final int was = second.getRGB(15, 0);
		first.setRGB(15, 0, Color.WHITE.getRGB());
		assertEquals("the two share no raster", was, second.getRGB(15, 0));
	}

	/**
	 * The whole 16 x 16, pinned as one number: {@link Arrays#hashCode(int[])} over the ARGB of every pixel, row by row.
	 * Computed on the code as it stood before the coin moved out into {@code CoinGlyph}, so the extraction is proved
	 * to have changed not one pixel - the antialiased rim included, which the per-pixel checks above leave free. A
	 * deliberate redraw of the icon updates this constant in the same commit; nothing else should.
	 */
	private static final int ICON_CHECKSUM = 966175792;

	@Test
	public void theWholeIconIsThePixelsItWasBeforeTheCoinMovedOut()
	{
		final BufferedImage img = NavIcon.create();
		final int[] pixels = img.getRGB(0, 0, NavIcon.SIZE, NavIcon.SIZE, null, 0, NavIcon.SIZE);
		assertEquals("the 16 x 16 ARGB checksum", ICON_CHECKSUM, Arrays.hashCode(pixels));
	}

	/** How many pixels are not fully transparent. */
	private static int ink(BufferedImage img)
	{
		int n = 0;
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					n++;
				}
			}
		}
		return n;
	}

	/** Whether any pixel in [x0, x1) x [y0, y1) has ink in it. */
	private static boolean opaque(BufferedImage img, int x0, int x1, int y0, int y1)
	{
		for (int x = x0; x < x1; x++)
		{
			for (int y = y0; y < y1; y++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasColour(BufferedImage img, Color colour)
	{
		for (int x = 0; x < img.getWidth(); x++)
		{
			for (int y = 0; y < img.getHeight(); y++)
			{
				final int argb = img.getRGB(x, y);
				if (((argb >>> 24) & 0xff) == 0xff && (argb & 0xffffff) == (colour.getRGB() & 0xffffff))
				{
					return true;
				}
			}
		}
		return false;
	}
}
