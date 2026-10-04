package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;

/**
 * The plugin's {@link ColourPicker} (1.1.0 part B): RuneLite's own colour picker, the one its settings page and its
 * bundled plugins use, handed over by {@link ColorPickerManager} (an injectable singleton) with the alpha slider hidden
 * - a rise and a fall are opaque. It is modeless and reports every move of the reader's hand, so the sidebar can
 * redraw in the colour as it is chosen.
 *
 * <p><b>It stands to the left of the sidebar and never over it</b> (the user, 2026-10-03: the change is to be seen
 * live): {@link #pickerSpot} puts its top level with the panel's top and its right edge 8 px short of the panel's left,
 * which is the game's own canvas in the client's usual layout.
 */
final class RuneLiteColourPicker implements ColourPicker
{
	/** The air between the picker and the sidebar, in px. */
	static final int GAP = 8;

	/** Null in a test that builds the plugin without one: the rows then do nothing, as with no seam at all. */
	@Nullable
	private final ColorPickerManager manager;

	RuneLiteColourPicker(@Nullable ColorPickerManager manager)
	{
		this.manager = manager;
	}

	@Override
	public void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done)
	{
		if (manager == null)
		{
			return;
		}
		final Window window = SwingUtilities.getWindowAncestor(anchor);
		final RuneliteColorPicker picker = manager.create(window, start, title, true);
		if (picker == null)
		{
			return;
		}
		picker.setOnColorChange(live);
		picker.setOnClose(done);
		final GraphicsConfiguration screen = anchor.getGraphicsConfiguration();
		if (anchor.isShowing() && screen != null)
		{
			picker.setLocation(pickerSpot(new Rectangle(anchor.getLocationOnScreen(), anchor.getSize()), picker.getSize(),
				screen.getBounds()));
		}
		picker.setVisible(true);
	}

	/**
	 * Where the picker's top-left corner goes: to the LEFT of the sidebar, {@link #GAP} px clear of it and its top level
	 * with the panel's own, so the sidebar - what the picker is recolouring - stays whole in view.
	 *
	 * <p>Only when there is no room on the left (the client's window against the screen's left edge) does it go to the
	 * right of the panel instead, and only when neither side has room does it fall back to the screen's left edge,
	 * where it will cover part of the sidebar rather than leave the screen. The top is the panel's, moved up or down only as
	 * far as it takes to keep the picker inside the screen. A pure function of three rectangles, so each of those
	 * answers is testable without a screen.
	 *
	 * @param panelOnScreen the sidebar panel's bounds in screen coordinates
	 * @param picker        the picker's size
	 * @param screen        the screen the sidebar is on
	 */
	static Point pickerSpot(Rectangle panelOnScreen, Dimension picker, Rectangle screen)
	{
		final int top = Math.max(screen.y, Math.min(panelOnScreen.y, screen.y + screen.height - picker.height));
		final int left = panelOnScreen.x - picker.width - GAP;
		if (left >= screen.x)
		{
			return new Point(left, top);
		}
		final int right = panelOnScreen.x + panelOnScreen.width + GAP;
		if (right + picker.width <= screen.x + screen.width)
		{
			return new Point(right, top);
		}
		return new Point(screen.x, top);
	}
}
