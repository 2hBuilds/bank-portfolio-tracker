package com.bankpricemovement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * The "List options" icon (1.1.0 part G): TWO GEARS in a {@value #SIZE} x {@value #SIZE} box, the user's pick (icon 23 of the
 * sheet {@code docs/handoff/lab/search-row-icons-grey-2026-10-04.png}, in the grey the search box paints "Search items" in),
 * drawn in code and anti-aliased - no image file, and nothing about it depends on a font or a look and feel.
 *
 * <p>The geometry is the contract's, in units of the box (x to the right, y down): a BIG gear at ({@value #BIG_X},
 * {@value #BIG_Y}) with a body of radius {@value #BIG_BODY}, {@value #BIG_TEETH} teeth reaching from radius
 * {@value #BIG_TOOTH_IN} to {@value #BIG_TOOTH_OUT} ({@value #BIG_TOOTH_HALF} either side of their centre line) and a hole of
 * radius {@value #BIG_HOLE}; and a SMALL gear at ({@value #SMALL_X}, {@value #SMALL_Y}) with a body of radius
 * {@value #SMALL_BODY}, {@value #SMALL_TEETH} teeth from {@value #SMALL_TOOTH_IN} to {@value #SMALL_TOOTH_OUT}
 * ({@value #SMALL_TOOTH_HALF} either side) and a hole of radius {@value #SMALL_HOLE}. The first tooth of each points along
 * the positive x axis.
 */
final class GearsIcon
{
	/** The box's side in px: the same 12 px the settings icon and the eye are drawn at. */
	static final int SIZE = 12;

	static final double BIG_X = 4.3;
	static final double BIG_Y = 7.7;
	static final double BIG_BODY = 3.0;
	static final int BIG_TEETH = 7;
	static final double BIG_TOOTH_IN = 2.2;
	static final double BIG_TOOTH_OUT = 4.2;
	static final double BIG_TOOTH_HALF = 0.9;
	static final double BIG_HOLE = 1.2;

	static final double SMALL_X = 9.2;
	static final double SMALL_Y = 3.0;
	static final double SMALL_BODY = 2.0;
	static final int SMALL_TEETH = 6;
	static final double SMALL_TOOTH_IN = 1.4;
	static final double SMALL_TOOTH_OUT = 2.8;
	static final double SMALL_TOOTH_HALF = 0.7;
	static final double SMALL_HOLE = 0.8;

	private GearsIcon()
	{
	}

	/** The two gears as an icon, every pixel {@code ink} with the coverage the anti-aliasing gives it, the rest clear. */
	static ImageIcon icon(final Color ink)
	{
		final BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue()));
			g.fill(gear(BIG_X, BIG_Y, BIG_BODY, BIG_TEETH, BIG_TOOTH_IN, BIG_TOOTH_OUT, BIG_TOOTH_HALF, BIG_HOLE));
			g.fill(gear(SMALL_X, SMALL_Y, SMALL_BODY, SMALL_TEETH, SMALL_TOOTH_IN, SMALL_TOOTH_OUT, SMALL_TOOTH_HALF,
				SMALL_HOLE));
		}
		finally
		{
			g.dispose();
		}
		return new ImageIcon(image);
	}

	/**
	 * One gear's outline: the body disc, the teeth (rectangles standing on the body, evenly round it, the first along the
	 * positive x axis) and then the hole cut out of the lot.
	 */
	private static Area gear(final double centreX, final double centreY, final double body, final int teeth,
		final double toothIn, final double toothOut, final double toothHalf, final double hole)
	{
		final Area area = new Area(new Ellipse2D.Double(centreX - body, centreY - body, body * 2.0, body * 2.0));
		for (int i = 0; i < teeth; i++)
		{
			final Area tooth = new Area(new Rectangle2D.Double(toothIn, -toothHalf, toothOut - toothIn, toothHalf * 2.0));
			tooth.transform(AffineTransform.getRotateInstance(i * 2.0 * Math.PI / teeth));
			tooth.transform(AffineTransform.getTranslateInstance(centreX, centreY));
			area.add(tooth);
		}
		area.subtract(new Area(new Ellipse2D.Double(centreX - hole, centreY - hole, hole * 2.0, hole * 2.0)));
		return area;
	}
}
