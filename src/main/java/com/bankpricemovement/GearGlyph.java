package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;
import net.runelite.client.ui.ColorScheme;

/**
 * The 2hBuilds settings icon, drawn in code: a ring with eight teeth and a hole through the middle, the picture the
 * settings icon of every 2hBuilds plugin shows (STANDARD A4). It is the bank's icon, pulled out of its widget helper so
 * the one picture can be copied into another plugin unchanged; it refers to no class of the plugin it sits in.
 *
 * <p>The ring is a stroke and not a filled disc, so the middle stays transparent and the picture reads as a settings
 * icon at 12 px rather than as a cogged blob. The teeth are eight round-capped spokes every 45 degrees, starting on
 * the ring's own circle so each is rooted in the ring's metal and none reaches into the hole. Every length is a
 * fraction of the size, so the same shape comes out at any size.
 */
final class GearGlyph
{
	/** The number of teeth. */
	static final int TEETH = 8;
	/** The smallest side drawn: below it the hole closes and the teeth merge. */
	static final int MIN_SIZE = 7;

	private GearGlyph()
	{
	}

	/**
	 * A new square icon, transparent except for the ring and its teeth.
	 *
	 * @param size   the icon's side in px; anything under {@value #MIN_SIZE} is raised to it
	 * @param colour the ink; null falls back to the sidebar's quiet grey, {@link ColorScheme#LIGHT_GRAY_COLOR}
	 */
	static ImageIcon icon(int size, Color colour)
	{
		final int s = Math.max(MIN_SIZE, size);
		final BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setColor(colour == null ? ColorScheme.LIGHT_GRAY_COLOR : colour);
			final double centre = s / 2.0;
			// The ring's centre line, the width of its metal, and how far a tooth's centre line reaches - the last
			// one pulled in by half a tooth so the round cap lands inside the icon instead of being clipped.
			final double ring = centre * 0.52;
			final float ringWidth = (float) Math.max(1.5, s * 0.2);
			final float toothWidth = (float) Math.max(1.3, s * 0.16);
			final double toothOut = centre - toothWidth / 2.0 - 0.25;
			g.setStroke(new BasicStroke(ringWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Ellipse2D.Double(centre - ring, centre - ring, ring * 2.0, ring * 2.0));
			g.setStroke(new BasicStroke(toothWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			for (int i = 0; i < TEETH; i++)
			{
				final double angle = i * 2.0 * Math.PI / TEETH;
				final double cos = Math.cos(angle);
				final double sin = Math.sin(angle);
				g.draw(new Line2D.Double(centre + cos * ring, centre + sin * ring,
					centre + cos * toothOut, centre + sin * toothOut));
			}
		}
		finally
		{
			g.dispose();
		}
		return new ImageIcon(img);
	}
}
