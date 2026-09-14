package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link NavIcon} (contract C34): the sidebar button's icon - the ONLY way a user reaches this plugin.
 *
 * <p><b>Why this file exists.</b> Its whole coverage was one {@code assertNotNull} on
 * {@code NavigationButton.getIcon()} in the wiring test, which a fully transparent 16 x 16 image satisfies. A
 * {@code setColor} moved after the fills, an alpha-0 colour or coordinates pushed off the raster while chasing
 * a crisper arrow would leave the user relaunching to a blank square in the sidebar with nothing to tell it
 * from a broken plugin, and every test would pass. So the ink is counted, and counted WHERE the bars and the
 * arrow are drawn - a global count alone would survive one bar going missing.
 *
 * <p>No golden bitmap: the arrow is stroked with antialiasing and {@code STROKE_PURE}, whose exact pixels
 * drift between JDK builds. This is the same idiom {@code WidgetsTest} uses for the panel's drawn glyphs, and
 * like that one it proves the drawing needs no display.
 */
public class NavIconTest
{
	@Test
	public void theIconIsSixteenSquareArgbAndHasOrangeInkInEveryBarAndTheArrow()
	{
		final BufferedImage img = NavIcon.create();
		assertEquals(NavIcon.SIZE, img.getWidth());
		assertEquals(NavIcon.SIZE, img.getHeight());
		assertEquals("16 x 16 ARGB, what NavigationButton.builder().icon(...) takes",
			BufferedImage.TYPE_INT_ARGB, img.getType());

		// The three fillRects alone are 3x6 + 3x9 + 3x12 = 81 opaque pixels, and fillRect is not antialiased, so
		// 81 is a hard floor before the arrow adds anything: 60 leaves real slack and still catches a blank icon.
		assertTrue("the icon put " + ink(img) + " px of ink down", ink(img) > 60);

		// One bar at a time: the three columns the bars stand in, on the bottom row they all reach.
		assertTrue("the short bar is missing", opaque(img, 1, 4, 15, 16));
		assertTrue("the middle bar is missing", opaque(img, 6, 9, 15, 16));
		assertTrue("the tall bar is missing", opaque(img, 11, 14, 15, 16));
		// ...and the arrow, which lives in the top-left quadrant above every bar.
		assertTrue("the arrow is missing", opaque(img, 0, 10, 0, 6));

		assertTrue("nothing is drawn in the brand colour", hasColour(img, ColorScheme.BRAND_ORANGE));
		assertEquals("the ground stays transparent", 0, img.getRGB(0, 0) >>> 24);
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
		final int was = second.getRGB(0, 0);
		first.setRGB(0, 0, Color.WHITE.getRGB());
		assertEquals("the two share no raster", was, second.getRGB(0, 0));
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
