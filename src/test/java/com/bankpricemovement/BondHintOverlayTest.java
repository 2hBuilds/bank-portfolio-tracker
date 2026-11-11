package com.bankpricemovement;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.2.5: the gold outline round both "View Your Bonds" buttons of the "Old School Bonds" screen while the pouch hint is on -
 * full strength the moment it shows, then breathing, never gone; nothing drawn while the hint is off or a button is not there.
 */
public class BondHintOverlayTest
{
	private static final Rectangle TAB = new Rectangle(10, 10, 100, 18);
	private static final Rectangle BUTTON = new Rectangle(40, 60, 150, 32);

	private final Client client = mock(Client.class);
	private boolean wanted = true;
	private long now;
	private final BondHintOverlay overlay = new BondHintOverlay(client, () -> wanted, () -> now);

	private static Widget button(final Rectangle bounds, final boolean hidden)
	{
		final Widget w = mock(Widget.class);
		when(w.getBounds()).thenReturn(bounds);
		when(w.isHidden()).thenReturn(hidden);
		return w;
	}

	private void bothButtons()
	{
		final Widget tab = button(TAB, false);
		final Widget body = button(BUTTON, false);
		when(client.getWidget(InterfaceID.BondMain.BOND_POUCH_TOP)).thenReturn(tab);
		when(client.getWidget(InterfaceID.BondMain.BOND_POUCH_BODY)).thenReturn(body);
	}

	private BufferedImage frame()
	{
		final BufferedImage img = new BufferedImage(240, 120, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			overlay.render(g);
		}
		finally
		{
			g.dispose();
		}
		return img;
	}

	/** The alpha painted on the left edge of a button's outline, 0 where nothing was drawn. */
	private static int alphaAt(final BufferedImage img, final Rectangle button)
	{
		return new Color(img.getRGB(button.x - 1, button.y + button.height / 2), true).getAlpha();
	}

	@Test
	public void bothButtonsAreOutlinedInTheHintsGoldAtFullStrengthFirst()
	{
		bothButtons();
		final BufferedImage img = frame();
		for (final Rectangle button : new Rectangle[]{TAB, BUTTON})
		{
			final Color edge = new Color(img.getRGB(button.x - 1, button.y + button.height / 2), true);
			assertEquals("full strength on the first frame", 255, edge.getAlpha());
			assertEquals(Widgets.CHART_COLOUR_DEFAULT.getRGB() & 0xFFFFFF, edge.getRGB() & 0xFFFFFF);
			assertEquals("the face of the button is left alone", 0,
				new Color(img.getRGB(button.x + button.width / 2, button.y + button.height / 2), true).getAlpha());
		}
	}

	@Test
	public void theOutlineBreathesButNeverGoesAndEachShowingStartsFull()
	{
		bothButtons();
		now = 1_000L;
		assertEquals(255, alphaAt(frame(), BUTTON));
		now = 1_000L + BondHintOverlay.PULSE_MILLIS / 2;
		final int faintest = alphaAt(frame(), BUTTON);
		assertTrue("fainter at the half breath, but there: " + faintest,
			faintest < 255 && faintest >= BondHintOverlay.FAINTEST - 2);
		now = 1_000L + BondHintOverlay.PULSE_MILLIS;
		assertEquals("back at full after one breath", 255, alphaAt(frame(), BUTTON));

		// Off, then on again part way through what would have been a breath: it starts full again.
		wanted = false;
		now += BondHintOverlay.PULSE_MILLIS / 2;
		assertEquals(0, alphaAt(frame(), BUTTON));
		wanted = true;
		assertEquals(255, alphaAt(frame(), BUTTON));
	}

	@Test
	public void nothingIsDrawnWhileTheHintIsOffOrAButtonIsNotThere()
	{
		bothButtons();
		wanted = false;
		assertEquals(0, alphaAt(frame(), TAB));
		assertEquals(0, alphaAt(frame(), BUTTON));

		// The screen changed again: one button gone, the other hidden. Nothing is drawn, nothing throws.
		wanted = true;
		when(client.getWidget(InterfaceID.BondMain.BOND_POUCH_TOP)).thenReturn(null);
		final Widget hidden = button(BUTTON, true);
		when(client.getWidget(InterfaceID.BondMain.BOND_POUCH_BODY)).thenReturn(hidden);
		assertEquals(0, alphaAt(frame(), TAB));
		assertEquals(0, alphaAt(frame(), BUTTON));
	}
}
