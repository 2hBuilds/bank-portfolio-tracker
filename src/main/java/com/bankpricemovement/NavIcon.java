package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ColorScheme;

/**
 * The sidebar button's icon (contract C34): three bars rising left to right with an up-arrow over them,
 * {@link ColorScheme#BRAND_ORANGE} on transparent, 16 x 16 px - the size the sidebar draws navigation icons
 * at. Drawn in code, like Loot and Beam's {@code TabIcons}, so the plugin ships no resource files (contract
 * C46 forbids classpath resource loading in this package) and needs no image-loading helper.
 *
 * <p>{@code NavigationButton.builder().icon(...)} takes a {@link BufferedImage}
 * ({@code runelite-client/src/main/java/net/runelite/client/ui/NavigationButton.java:47}), which is what this
 * returns; a fresh image per call, so a plugin restart never shares a bitmap with a button already removed.
 */
public final class NavIcon
{
	/** The sidebar icon size; MaterialTab and every bundled panel button use 16 px. */
	public static final int SIZE = 16;

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
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setColor(ColorScheme.BRAND_ORANGE);

			// Three bars, each taller than the last, standing on the bottom edge.
			g.fillRect(1, 10, 3, 6);
			g.fillRect(6, 7, 3, 9);
			g.fillRect(11, 4, 3, 12);

			// The arrow: a shaft from the tallest bar's top-left towards the corner, with a head.
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Double(1.5, 7.5, 8.5, 1.5));
			final Path2D head = new Path2D.Double();
			head.moveTo(5.0, 1.0);
			head.lineTo(9.5, 1.0);
			head.lineTo(9.5, 5.5);
			head.closePath();
			g.fill(head);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}
}
