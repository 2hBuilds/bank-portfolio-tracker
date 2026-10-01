package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.util.ImageUtil;
import org.junit.Test;

/**
 * 1.0.9, the brands' marks (the contract of 2026-09-30, section 2): four white-on-transparent PNGs under
 * {@code src/main/resources/com/bankpricemovement/}, resized and nothing else, loaded through RuneLite's
 * {@link ImageUtil#loadImageResource} by {@link SupportLinks#markIcon}. The Discord mark is 12 px beside the settings icon
 * and 16 px in the menu's header; X and GitHub are 16 px.
 */
public class SupportMarksTest
{
	/** The four files, by name, and the side each is drawn at. */
	private static final String[] NAMES = {"discord_12.png", "discord_16.png", "x_16.png", "github_16.png"};
	private static final int[] SIDES = {12, 16, 16, 16};

	@Test
	public void theFourMarksLoadAtTwelveSixteenSixteenAndSixteenPixels()
	{
		for (int i = 0; i < NAMES.length; i++)
		{
			final ImageIcon icon = SupportLinks.markIcon(NAMES[i], 1f);
			assertEquals(NAMES[i] + " is " + SIDES[i] + " px wide", SIDES[i], icon.getIconWidth());
			assertEquals(NAMES[i] + " is " + SIDES[i] + " px tall", SIDES[i], icon.getIconHeight());
		}
		assertEquals("the class's constants name the same four files", NAMES[0], SupportLinks.DISCORD_12);
		assertEquals(NAMES[1], SupportLinks.DISCORD_16);
		assertEquals(NAMES[2], SupportLinks.X_16);
		assertEquals(NAMES[3], SupportLinks.GITHUB_16);
	}

	/** White on transparent: every pixel that shows is pure white, so a mark is greyed by alpha and never recoloured. */
	@Test
	public void everyVisiblePixelOfEveryMarkIsWhite()
	{
		for (final String name : NAMES)
		{
			final BufferedImage mark = ImageUtil.loadImageResource(SupportLinks.class, name);
			assertNotNull(name, mark);
			int visible = 0;
			boolean opaque = false;
			for (int y = 0; y < mark.getHeight(); y++)
			{
				for (int x = 0; x < mark.getWidth(); x++)
				{
					final int argb = mark.getRGB(x, y);
					if (argb >>> 24 != 0)
					{
						visible++;
						assertEquals(name + " (" + x + ", " + y + ") is white", 0xFFFFFF, argb & 0xFFFFFF);
					}
					opaque |= argb >>> 24 == 255;
				}
			}
			assertTrue(name + " draws something", visible > 10);
			assertTrue(name + " has a fully opaque pixel", opaque);
		}
	}

	/**
	 * Each file is read ONCE: the full-white icon of a mark is the one loaded image however often it is asked for, and
	 * the fainter look is that image with its alpha scaled - 165 of 255 - and its colour left alone.
	 */
	@Test
	public void eachFileIsLoadedOnceAndTheRestingLookScalesOnlyTheAlpha()
	{
		for (final String name : NAMES)
		{
			final BufferedImage first = (BufferedImage) SupportLinks.markIcon(name, 1f).getImage();
			assertSame(name + " is read once", first, SupportLinks.markIcon(name, 1f).getImage());
			final BufferedImage rest = (BufferedImage) SupportLinks.markIcon(name, SupportLinks.MARK_REST_ALPHA).getImage();
			for (int y = 0; y < first.getHeight(); y++)
			{
				for (int x = 0; x < first.getWidth(); x++)
				{
					final int full = first.getRGB(x, y);
					final int faint = rest.getRGB(x, y);
					final int expected = Math.round((full >>> 24) * SupportLinks.MARK_REST_ALPHA);
					assertEquals(name + " alpha at (" + x + ", " + y + ")", expected, faint >>> 24, 1);
					if (full >>> 24 != 0)
					{
						assertEquals(name + " keeps its colour", full & 0xFFFFFF, faint & 0xFFFFFF);
					}
				}
			}
		}
		assertEquals("the settings icon's grey 165 as an alpha", 165f / 255f, SupportLinks.MARK_REST_ALPHA, 0f);
	}
}
