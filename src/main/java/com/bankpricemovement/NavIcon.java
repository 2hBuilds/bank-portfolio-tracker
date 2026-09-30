package com.bankpricemovement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

/**
 * The sidebar button's icon (contract C34), 16 x 16 px - the size the sidebar draws navigation icons at: the
 * 2hBuilds coin in the top-left corner and this plugin's four {@link ColorScheme#BRAND_ORANGE} bars rising to the
 * right along the bottom, on transparent. The coin is the family mark every 2hBuilds plugin carries, the same as
 * Why Lag's: a hammered gold disc 9 px across with its "2h" struck in dark brown. The two never touch - the coin
 * sits on the clear ground above the short bars, so its letters stay readable at true size. Drawn in code, like Loot
 * and Beam's {@code TabIcons}, so the plugin ships no resource files (contract C46 forbids classpath resource
 * loading in this package) and needs no image-loading helper.
 *
 * <p>{@code NavigationButton.builder().icon(...)} takes a {@link BufferedImage}
 * ({@code runelite-client/src/main/java/net/runelite/client/ui/NavigationButton.java:47}), which is what this
 * returns; a fresh image per call, so a plugin restart never shares a bitmap with a button already removed.
 */
public final class NavIcon
{
	/** The sidebar icon size; MaterialTab and every bundled panel button use 16 px. */
	public static final int SIZE = 16;

	private static final Color RIM = new Color(88, 62, 16);
	private static final Color EDGE = new Color(140, 102, 30);
	private static final Color FACE = new Color(196, 156, 58);
	private static final Color INK = new Color(52, 36, 8);

	/** The coin's centre and the three radii of its rim, edge and face. */
	private static final double COIN_X = 4.5, COIN_Y = 4.5;
	private static final double[] RADII = {4.7, 3.9, 3.3};
	private static final Color[] RINGS = {RIM, EDGE, FACE};
	/** The hammering: one offset per sixteenth of the edge. */
	private static final double[] JITTER = {0.3, -0.2, 0.4, 0.1, -0.3, 0.2, 0.4, -0.1, 0.2, -0.4, 0.3, 0.0, -0.3, 0.4, -0.1, 0.2};

	/** The letters, one character a pixel: "2" then "h", 3 wide and 5 tall each, a 1 px gap between. */
	private static final String[] TWO = {"###", "..#", "###", "#..", "###"};
	private static final String[] H = {"#..", "#..", "###", "#.#", "#.#"};
	private static final int LETTERS_X = 1, LETTERS_Y = 2;

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

			// The coin: three jittered discs with antialiasing, then the letters crisp on top.
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			for (int i = 0; i < RADII.length; i++)
			{
				g.setColor(RINGS[i]);
				g.fill(coin(RADII[i]));
			}
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
			glyph(g, TWO, LETTERS_X, LETTERS_Y);
			glyph(g, H, LETTERS_X + 4, LETTERS_Y);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	private static Path2D coin(double radius)
	{
		final Path2D path = new Path2D.Double();
		for (int i = 0; i < JITTER.length; i++)
		{
			final double angle = i * Math.PI * 2 / JITTER.length;
			final double r = radius + JITTER[i];
			final double x = COIN_X + Math.cos(angle) * r;
			final double y = COIN_Y + Math.sin(angle) * r;
			if (i == 0)
			{
				path.moveTo(x, y);
			}
			else
			{
				path.lineTo(x, y);
			}
		}
		path.closePath();
		return path;
	}

	private static void glyph(Graphics2D g, String[] rows, int x, int y)
	{
		g.setColor(INK);
		for (int r = 0; r < rows.length; r++)
		{
			for (int c = 0; c < rows[r].length(); c++)
			{
				if (rows[r].charAt(c) == '#')
				{
					g.fillRect(x + c, y + r, 1, 1);
				}
			}
		}
	}
}
