package com.bankpricemovement;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

/**
 * The sidebar button's icon (contract C34), 16 x 16 px - the size the sidebar draws navigation icons at: the
 * 2hBuilds coin in the top-left corner and this plugin's four {@link ColorScheme#BRAND_ORANGE} bars rising to the
 * right along the bottom, on transparent. The coin is the family mark every 2hBuilds plugin carries, the same as
 * Why Lag's: a hammered gold disc 9 px across with its "2h" struck in dark brown, drawn by {@link CoinGlyph}, the
 * one picture every 2hBuilds plugin copies. The two never touch - the coin sits on the clear ground above the short
 * bars, so its letters stay readable at true size. Drawn in code, like Loot and Beam's {@code TabIcons}, so it needs
 * no image-loading helper. (The only image files the plugin ships are the brands' own marks beside the settings
 * icon, {@link SupportLinks#markIcon}, since 1.0.9.)
 *
 * <p>{@code NavigationButton.builder().icon(...)} takes a {@link BufferedImage}
 * ({@code runelite-client/src/main/java/net/runelite/client/ui/NavigationButton.java:47}), which is what this
 * returns; a fresh image per call, so a plugin restart never shares a bitmap with a button already removed.
 */
public final class NavIcon
{
	/** The sidebar icon size; MaterialTab and every bundled panel button use 16 px. */
	public static final int SIZE = 16;

	/** The bars: left edge and height of each, all 3 px wide with a 1 px gap, standing on the bottom row. */
	private static final int[] BAR_X = {1, 5, 9, 13};
	private static final int[] BAR_HEIGHT = {2, 4, 6, 9};
	private static final int BAR_WIDTH = 3;

	private NavIcon()
	{
	}

	/** A new 16 x 16 ARGB image of the icon. */
	public static BufferedImage create()
	{
		final BufferedImage img = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			// Four bars, each taller than the last, standing on the bottom edge.
			g.setColor(ColorScheme.BRAND_ORANGE);
			for (int i = 0; i < BAR_X.length; i++)
			{
				g.fillRect(BAR_X[i], SIZE - BAR_HEIGHT[i], BAR_WIDTH, BAR_HEIGHT[i]);
			}

			// The coin: the family mark, drawn by CoinGlyph (antialiased discs, then the letters crisp on top).
			CoinGlyph.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}
}
