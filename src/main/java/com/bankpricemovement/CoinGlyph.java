package com.bankpricemovement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * The 2hBuilds coin, the family mark every 2hBuilds plugin carries in the top-left corner of its sidebar icon: a
 * hammered gold disc 9 px across with its "2h" struck in dark brown, drawn in code so it needs no image file. It is
 * the coin half of {@link NavIcon}, pulled out so the one picture can be copied into another plugin's package
 * unchanged - it refers to nothing of this plugin's and to no class but the JDK's.
 *
 * <p>{@link #paint} draws it where it sits in a 16 x 16 icon, the disc centred on (4.5, 4.5): three jittered discs
 * with antialiasing on, then the two letters crisp on top, antialiasing off again, so a caller that draws more on
 * the same {@link Graphics2D} afterwards gets the hint it had not set itself.
 */
final class CoinGlyph
{
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

	private CoinGlyph()
	{
	}

	/**
	 * Draws the coin in the top-left of the 16 x 16 icon {@code g} paints on: three jittered discs with
	 * antialiasing, then the letters crisp on top. Leaves the antialiasing hint off and the colour at the letters'
	 * ink.
	 */
	static void paint(Graphics2D g)
	{
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
