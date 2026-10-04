package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.util.function.Consumer;

/**
 * The seam between the sidebar and the colour picker (1.1.0 part B): the settings menu's colour rows ask for a picker
 * and say what to do while the reader moves it and when they are done, and whatever stands behind the seam shows one.
 * The plugin's is RuneLite's own ({@link RuneLiteColourPicker}); a panel with none ({@code null}) leaves the rows doing
 * nothing, which is what the headless renderer and a throwaway test seam get.
 *
 * <p>A seam rather than a call into RuneLite's {@code ColorPickerManager} from the panel, for the reason every other
 * outward reach of this panel is one ({@code Prefs}, {@code LegacyPrompt}): the panel stays a plain Swing component the
 * tests can build and press without a client, and the tests answer the picker themselves.
 */
public interface ColourPicker
{
	/**
	 * Shows a colour picker.
	 *
	 * @param anchor the sidebar's own panel: the component the picker is placed beside and owned through
	 * @param start  the colour it opens on
	 * @param title  its title
	 * @param live   told the colour as the reader moves it, every time - the sidebar redraws in it and writes nothing
	 * @param done   told the colour it closed on - the sidebar stores it
	 */
	void open(Component anchor, Color start, String title, Consumer<Color> live, Consumer<Color> done);
}
