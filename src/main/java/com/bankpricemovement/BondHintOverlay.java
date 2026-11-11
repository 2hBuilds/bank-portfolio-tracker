package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * The pouch hint's last step (1.2.5): while the hint is on, a gold outline pulses round both "View Your Bonds" buttons
 * of the "Old School Bonds" screen - the screen the bond pouch opens - because the pouch is read from "Your Bonds" alone.
 * Drawn after that interface only, so it costs nothing while the screen is shut; it draws on the screen and changes no click.
 */
final class BondHintOverlay extends Overlay
{
	/** One breath of the outline, full strength to faintest and back. */
	static final long PULSE_MILLIS = 2400L;
	/** The outline's alpha at its faintest: still there, so the buttons are never unmarked. */
	static final int FAINTEST = 70;

	private final Client client;
	private final BooleanSupplier wanted;
	private final LongSupplier clock;
	/** When the outline came on, by {@link #clock}; -1 while it is off, so each showing starts at full strength. */
	private long since = -1L;

	/**
	 * @param wanted whether the hint is on (CLIENT THREAD)
	 * @param clock  a monotonic clock in milliseconds
	 */
	BondHintOverlay(final Client client, final BooleanSupplier wanted, final LongSupplier clock)
	{
		this.client = client;
		this.wanted = wanted;
		this.clock = clock;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterInterface(InterfaceID.BOND_MAIN);
	}

	@Override
	public Dimension render(final Graphics2D graphics)
	{
		if (!wanted.getAsBoolean())
		{
			since = -1L;
			return null;
		}
		final long now = clock.getAsLong();
		if (since < 0L)
		{
			since = now;
		}
		// A cosine from the moment it came on: full strength first (the user: "so that the user sees the outlines immediately").
		final double wave = (1.0d + Math.cos(2.0d * Math.PI * (now - since) / PULSE_MILLIS)) / 2.0d;
		final Color gold = Widgets.CHART_COLOUR_DEFAULT;
		graphics.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(),
			FAINTEST + (int) Math.round((255 - FAINTEST) * wave)));
		graphics.setStroke(new BasicStroke(2f));
		outline(graphics, client.getWidget(InterfaceID.BondMain.BOND_POUCH_TOP));
		outline(graphics, client.getWidget(InterfaceID.BondMain.BOND_POUCH_BODY));
		return null;
	}

	private static void outline(final Graphics2D graphics, @Nullable final Widget button)
	{
		final Rectangle bounds = button == null || button.isHidden() ? null : button.getBounds();
		if (bounds != null && bounds.width > 0 && bounds.height > 0)
		{
			graphics.drawRect(bounds.x - 1, bounds.y - 1, bounds.width + 1, bounds.height + 1);
		}
	}
}
