package com.bankpricemovement;

import java.awt.Color;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** {@link GearGlyph}, in memory: its size, its transparent corners and hole, and the colour of its ring. */
public class GearGlyphTest
{
	private static final Color ORANGE = new Color(220, 138, 0);

	private static BufferedImage image(ImageIcon icon)
	{
		return (BufferedImage) icon.getImage();
	}

	@Test
	public void theIconIsAsBigAsAskedAndNoSmallerThanTheMinimum()
	{
		final ImageIcon twelve = GearGlyph.icon(12, ORANGE);
		assertEquals(12, twelve.getIconWidth());
		assertEquals(12, twelve.getIconHeight());
		assertEquals(GearGlyph.MIN_SIZE, GearGlyph.icon(3, ORANGE).getIconWidth());
	}

	@Test
	public void theFourCornersAndTheHoleAreTransparent()
	{
		final BufferedImage img = image(GearGlyph.icon(12, ORANGE));
		assertEquals(0, img.getRGB(0, 0) >>> 24);
		assertEquals(0, img.getRGB(11, 0) >>> 24);
		assertEquals(0, img.getRGB(0, 11) >>> 24);
		assertEquals(0, img.getRGB(11, 11) >>> 24);
		assertEquals(0, img.getRGB(5, 5) >>> 24);
		assertEquals(0, img.getRGB(6, 6) >>> 24);
	}

	@Test
	public void theRingIsSolidInTheGivenColour()
	{
		// (8, 7) sits on the ring between two teeth: well inside the metal, so fully opaque.
		assertEquals(0xFF000000 | ORANGE.getRGB(), image(GearGlyph.icon(12, ORANGE)).getRGB(8, 7));
	}

	@Test
	public void noColourMeansTheSidebarsGrey()
	{
		assertEquals(0xFF000000 | ColorScheme.LIGHT_GRAY_COLOR.getRGB(), image(GearGlyph.icon(12, null)).getRGB(8, 7));
		assertEquals(new Color(165, 165, 165), ColorScheme.LIGHT_GRAY_COLOR);
	}
}
