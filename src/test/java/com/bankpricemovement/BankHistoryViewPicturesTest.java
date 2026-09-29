package com.bankpricemovement;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Addendum AU, builder C: the History view's own pictures ({@link HistoryViewRenderer}) are drawn into
 * {@code build/history-view/} on every run - the pinned copies under {@code docs/handoff/lab/} are written by the
 * harness's {@code main} - and each is the picture it claims to be.
 */
public class BankHistoryViewPicturesTest
{
	@Test
	public void theFivePicturesAreDrawnAtTheirWidths() throws Exception
	{
		final List<File> files = HistoryViewRenderer.write(new File("build/history-view"));
		assertEquals(5, files.size());
		final BufferedImage at213 = ImageIO.read(files.get(0));
		final BufferedImage at230 = ImageIO.read(files.get(1));
		final BufferedImage empty = ImageIO.read(files.get(2));
		final BufferedImage one = ImageIO.read(files.get(3));
		final BufferedImage hover = ImageIO.read(files.get(4));
		assertEquals(213, at213.getWidth());
		assertEquals(230, at230.getWidth());
		assertEquals("the same content at both widths, so the same height", at213.getHeight(), at230.getHeight());
		assertEquals("the hover changes nothing's size", at213.getHeight(), hover.getHeight());
		assertTrue("the whole list is drawn: 40 days of rows", at213.getHeight() > 1_200);
		assertTrue("the empty view is one short sentence", empty.getHeight() < 60);
		assertTrue("one reading: the block and one row", one.getHeight() < 250);
		assertTrue("the lit chip is orange", count(at213, ColorScheme.BRAND_ORANGE) > 20);
		assertTrue("the hover is a different picture", differ(at213, hover));
	}

	static int count(final BufferedImage image, final java.awt.Color colour)
	{
		int n = 0;
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if ((image.getRGB(x, y) & 0xFFFFFF) == (colour.getRGB() & 0xFFFFFF))
				{
					n++;
				}
			}
		}
		return n;
	}

	static boolean differ(final BufferedImage a, final BufferedImage b)
	{
		for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++)
		{
			for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++)
			{
				if (a.getRGB(x, y) != b.getRGB(x, y))
				{
					return true;
				}
			}
		}
		return false;
	}
}
