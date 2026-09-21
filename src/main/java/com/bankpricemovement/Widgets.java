package com.bankpricemovement;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import javax.annotation.Nullable;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.FlatTextField;

/**
 * The small widget factory behind the 2h Bank Portfolio Tracker sidebar (contract C28): every label, chip, field and
 * row holder is made here so the panel and its rows look like the rest of the RuneLite sidebar and, above all,
 * so every one of them keeps to the width rule below. Stateless and EDT-only, like {@code com.lootandbeam.ui.Ui}
 * whose {@code fixed / setFitted / column} this copies ({@code Ui} is package-private to its own package, and the
 * Hub copy-out of this plugin must not reach into another plugin's package).
 *
 * <p><b>The width rule.</b> A sidebar panel is {@link PluginPanel#PANEL_WIDTH} (225 px,
 * {@code runelite-client/src/main/java/net/runelite/client/ui/PluginPanel.java:38}) plus the 17 px scrollbar
 * ({@code :39}); the panel's own scroll pane never scrolls sideways, so a row that wants more than the column
 * has is clipped silently, not scrolled. Everything is therefore designed at {@link #CONTENT_WIDTH} = 213 px
 * (225 minus 6 px of margin each side, the same figure Loot and Beam lays out at) and measured against it in
 * the tests with {@link #widest(Container)}; anything that can grow with its text - a name, a status line,
 * a number - goes through {@link #setFitted} so it shrinks to the room it has instead of pushing the row out.
 *
 * <p><b>Addendum N</b> ({@code docs/bank-price-movement-addendum-N-2026-09-09.md}) added the second half of
 * this file: the sans type scale ({@link #sans}/{@link #sansBold}, N section 3 §2), the segmented chip
 * ({@link #segment}, {@link #chip}, N section 2), the text link ({@link #linkLabel}), the hero card's
 * coloured edge ({@link #card}) and the drawn glyphs ({@link #triangle}, {@link #clearIcon},
 * {@link #dotIcon}, N section 7). Two rules run through all of it. Every glyph is DRAWN and never typed,
 * because the bitmap RuneScape faces box a character they lack (playbook 7.5). Every colour is a
 * {@code ColorScheme} constant or its {@code .darker()}, so the sidebar cannot drift away from the client it
 * lives in - with ONE declared exception, {@link #MOVE_DOWN_TEXT}, which addendum N section 3 §2 pre-authorised
 * by name and for a measured reason. <b>Addendum O</b> ({@code docs/bank-price-movement-addendum-O-2026-09-09.md}, O1) made the
 * Ticker look the only one, and the helpers only One Bar used - the solid chip style, the funnel beside the
 * count target, the drawn refresh glyph - went with it; what is left is what the Ticker sidebar draws. The
 * quality pass of 2026-09-10 finished that sweep: the pre-N ringed chip and its {@code markSelected} pair, the
 * three RuneScape-face label makers no line of this sidebar is typed in any more, and the plain {@code button} /
 * {@code link} / {@code cardBorder} / {@code fixedWidth} aliases had no caller left in main code, and are gone.
 * The move colours arrived in their place ({@link #move}), so the one sign rule the rails, the figures and the
 * card's edge all draw is written once.
 */
final class Widgets
{
	/**
	 * Usable width of the sidebar content: {@link PluginPanel#PANEL_WIDTH} (225) minus a 6 px margin each side.
	 * Loot and Beam lays out at the same {@code CONTENT_WIDTH - 12} and its first live shots fitted at it.
	 */
	static final int CONTENT_WIDTH = PluginPanel.PANEL_WIDTH - 12;

	/**
	 * What a truncated text ends with. Three ASCII dots rather than U+2026: the bitmap RuneScape fonts draw a
	 * box for a glyph they lack (playbook 7.5), and every face has a full stop.
	 */
	static final String ELLIPSIS = "...";

	/** The grey the placeholder "gp" is drawn in - {@code PluginErrorPanel.java:56} uses it for its description. */
	static final Color PLACEHOLDER_COLOR = Color.GRAY;

	/**
	 * The red a FALLING FIGURE'S TEXT is printed in - the one colour in this sidebar that is not a
	 * {@code ColorScheme} constant, declared here with the reason addendum N section 3 §2 asks for.
	 *
	 * <p>{@code ColorScheme.PROGRESS_ERROR_COLOR} is (230, 30, 30), which measures <b>3.63:1</b> against the
	 * {@code DARKER_GRAY} card (30, 30, 30) it is drawn on. That clears the 3:1 bar for the 3 px rail and the
	 * card's edge, but not the 4.5:1 one for small text - and it is carried by the row's 12 px gp figure, its
	 * 14 px percentage and the hero card's 18 px move line, beside a green ({@code PROGRESS_COMPLETE_COLOR},
	 * 10.9:1) three times as bright. This lifted red, (240, 92, 84), measures <b>5.05:1</b>; addendum N
	 * pre-authorised exactly it ("ONE lifted red (240, 92, 84) for red text under 14 px, declared with its
	 * contrast reason") on the condition that the 12 px red read dark in the first render, which
	 * {@code docs/handoff/lab/ticker-2026-09-09.png} showed that it does.
	 *
	 * <p>TEXT ONLY. The rails ({@link MovementRowPanel#railColor}), the hero card's edge
	 * ({@code BankPriceMovementPanel.edgeColor}) and the move triangle keep
	 * {@code PROGRESS_ERROR_COLOR} and its {@code .darker()}: they are large marks whose darkness is deliberate,
	 * and lifting them would break the pairing between a row's rail and the card's edge.
	 */
	static final Color MOVE_DOWN_TEXT = new Color(240, 92, 84);

	/** The line border a text field wears normally; {@link #markInvalid} swaps it for the red one. */
	private static final Color FIELD_BORDER = ColorScheme.BORDER_COLOR;

	/**
	 * The coloured left edge of the hero card and of a mover's row (addendum N sections 2 and 3.1), in px.
	 * A border and not a painted rectangle, so it survives every hover repaint without a custom paint method.
	 */
	static final int EDGE_WIDTH = 3;

	/** The hero card's inner padding (N 3.1): 213 - 3 (edge) - 9 - 10 = 191 px of usable width. */
	private static final Insets CARD_INSETS = new Insets(8, 9, 8, 10);

	/** The chip face size, T5 of addendum N section 3 §2: "180d" measures 29 px bold, inside a 38 px cell. */
	private static final int CHIP_FONT_SIZE = 13;

	/**
	 * The height of a chip's underline, and the bottom inset an UNLIT chip carries in its place, so lighting
	 * a chip never changes the strip's height (N 4.4 control 1).
	 */
	static final int CHIP_UNDERLINE = 2;

	/**
	 * The two faces and the two borders {@link #chip(JLabel, boolean)} paints with - the lit chip's orange rule,
	 * and the empty border of the same height an unlit one wears in its place. Fonts and borders are immutable
	 * and shared by design (this is what {@code BorderFactory} does), so the nine cells of a repaint take one of
	 * these four rather than deriving a font and allocating a border each.
	 */
	private static final Font CHIP_LIT_FONT = sansBold(CHIP_FONT_SIZE);
	private static final Font CHIP_UNLIT_FONT = sans(CHIP_FONT_SIZE);
	private static final Border CHIP_LIT_BORDER = new MatteBorder(0, 0, CHIP_UNDERLINE, 0, ColorScheme.BRAND_ORANGE);
	private static final Border CHIP_UNLIT_BORDER = new EmptyBorder(0, 0, CHIP_UNDERLINE, 0);

	/** The size of {@link #triangle(boolean)} and of the sort-menu dot (N 3.3, 3.4), in px. */
	private static final int TRIANGLE_SIZE = 7;

	/**
	 * The size {@link #gearIcon} is drawn at on the hero card (addendum Q, line Q1), in px: a small control
	 * beside a 28 px total, sized like the 11 px "x" in the price fold rather than like a chip.
	 */
	static final int GEAR_SIZE = 12;
	/** How many teeth the gear has - eight, the smallest count that still reads as a gear and not as a star. */
	static final int GEAR_TEETH = 8;
	/** The smallest gear that keeps its hole; a smaller {@code size} is raised to it rather than drawn shut. */
	static final int GEAR_MIN = 7;
	/** The side of {@link #checkBox(boolean)}, in px: the cap height of the 12 px menu face it stands beside. */
	static final int CHECKBOX_SIZE = 11;
	/** Below this the outline's own stroke would fill the square and the tick would have nowhere to go. */
	static final int CHECKBOX_MIN = 7;

	/** Where {@link #chip} records whether a chip is lit, so {@link #isLit} and the hover can read it back. */
	private static final String KEY_LIT = "bpm.chip.lit";

	/** Where {@link #linkLabel} records the colour to return to when the mouse leaves. */
	private static final String KEY_LINK_BASE = "bpm.link.base";

	/** Where {@link #linkLabel} records the colour to paint while the mouse is over it. */
	private static final String KEY_LINK_HOVER = "bpm.link.hover";

	private Widgets()
	{
	}

	// ------------------------------------------------------- addendum N: the sans scale (N section 3 §2, §7)

	/**
	 * The plain Swing sans at {@code size} px: {@code FontManager.getDefaultFont()} - the logical
	 * {@code Font.DIALOG} at 16 ({@code runelite-client/src/main/java/net/runelite/client/ui/FontManager.java:93})
	 * - derived to the size asked for.
	 *
	 * <p><b>Why every new label sets its font through this.</b> {@code RuneLiteLAF} installs the 16 px bitmap
	 * RuneScape face as the look-and-feel default, so a label that is given no font inherits a face with no
	 * size between 11 px and 14 px of ink and no triangle, tick or chevron glyph (playbook 7.5 - a missing
	 * glyph is drawn as a box). Addendum N's type scale is 28 / 18 / 14 / 13 / 12 / 11 px of ONE family, and
	 * that family has to be the logical one, because it is the only one with those sizes.
	 *
	 * <p>{@link Font#deriveFont(float)} answers a NEW font, so the shared {@code FontManager} constant is
	 * never mutated by a caller here.
	 *
	 * @param size the point size, e.g. 28 for the bank total, 12 for the control row
	 */
	static Font sans(float size)
	{
		return FontManager.getDefaultFont().deriveFont(size);
	}

	/** {@link #sans(float)} for the whole-number sizes the panel actually asks for. */
	static Font sans(int size)
	{
		return sans((float) size);
	}

	/**
	 * The bold Swing sans at {@code size} px - {@code FontManager.getDefaultBoldFont()}
	 * ({@code FontManager.java:94}) derived. See {@link #sans(float)} for why the new code never leaves a
	 * label's font to the look and feel.
	 */
	static Font sansBold(float size)
	{
		return FontManager.getDefaultBoldFont().deriveFont(size);
	}

	/** {@link #sansBold(float)} for the whole-number sizes the panel actually asks for. */
	static Font sansBold(int size)
	{
		return sansBold((float) size);
	}

	/**
	 * A label with its font and colour set explicitly - the one-line form of "never inherit the LAF's face"
	 * (N section 7). A null text is an empty label, which still has a font, so a probe measured from it
	 * reports the height a filled one will have.
	 */
	static JLabel label(String text, Font font, Color colour)
	{
		final JLabel label = new JLabel(text == null ? "" : text);
		label.setFont(font);
		label.setForeground(colour);
		return label;
	}

	/**
	 * {@code text} cut to what {@code width} px of {@code font} can hold, ending in {@link #ELLIPSIS} when it
	 * had to be cut; the whole text when it fits. Measured with the same {@link FontMetrics} a label paints
	 * with, so "fits" here is "fits on the screen".
	 */
	static String fit(Font font, @Nullable String text, int width)
	{
		return fit(metrics(font), text, width);
	}

	/**
	 * {@link #fit(Font, String, int)} against metrics the caller already holds. Every label owns the metrics it
	 * will paint with ({@link JComponent#getFontMetrics}), so the fitters take a {@link FontMetrics} rather than
	 * building a throwaway {@link JLabel} per call - {@link #fitName} used to build three for one name.
	 */
	private static String fit(FontMetrics fm, @Nullable String text, int width)
	{
		final String full = text == null ? "" : text;
		final int room = Math.max(10, width);
		if (fm.stringWidth(full) <= room)
		{
			return full;
		}
		final int dots = fm.stringWidth(ELLIPSIS);
		// Backwards, one prefix at a time, MEASURED with the same call the render will make: summing per-character
		// advances instead would round once per character where stringWidth rounds once per string, and the cut
		// would land on a different character on a face this machine does not have.
		for (int i = full.length() - 1; i > 0; i--)
		{
			if (fm.stringWidth(full.substring(0, i)) + dots <= room)
			{
				return full.substring(0, i) + ELLIPSIS;
			}
		}
		return ELLIPSIS;
	}

	/** The metrics a plain label of {@code font} paints with, for a caller that has no label to ask. */
	private static FontMetrics metrics(Font font)
	{
		return new JLabel().getFontMetrics(font);
	}

	/**
	 * Sets a label's text truncated to {@code width}, with the full text as its tooltip - the width rule in one
	 * call, for every label whose text comes from an item name, a status line or a number the user cannot be
	 * expected to keep short.
	 */
	static void setFitted(JLabel label, @Nullable String text, int width)
	{
		setFitted(label, text, width, Widgets::fit);
	}

	/**
	 * {@link #fit} for an ITEM NAME: the same cut, except that a dose suffix is kept whole.
	 *
	 * <p>Why this exists. The four doses of a potion differ only in their last three characters, and at the
	 * row's bold 14 they are all too wide for its 156 px text block - "Super combat potion(1..4)" measures 164 px
	 * each - so the plain fitter cuts every one of them to the SAME string, "Super combat potio...", and four
	 * consecutive rows lose the only thing that told them apart. Fitting the stem into what is left after the
	 * suffix gives "Super combat pot...(4)" (154 px), which is still one line and still says which potion it is.
	 *
	 * <p>The rule is deliberately narrow: only a trailing {@code (<digit>)}, which in OSRS is a dose and never a
	 * qualifier - a qualifier carries a space before its bracket ("Toxic blowpipe (empty)") and is left to the
	 * plain cut, where losing it costs nothing a reader needs. A name that fits is untouched, and a name whose
	 * stem cannot survive the cut at all falls back to {@link #fit} rather than printing "...(4)".
	 */
	static String fitName(Font font, @Nullable String text, int width)
	{
		return fitName(metrics(font), text, width);
	}

	/** {@link #fitName(Font, String, int)} against metrics the caller already holds; see {@link #fit}. */
	private static String fitName(FontMetrics fm, @Nullable String text, int width)
	{
		final String full = text == null ? "" : text;
		final int room = Math.max(10, width);
		if (fm.stringWidth(full) <= room || !hasDoseSuffix(full))
		{
			return fit(fm, full, width);
		}
		final String suffix = full.substring(full.length() - 3);
		final String stem = fit(fm, full.substring(0, full.length() - 3), room - fm.stringWidth(suffix));
		if (stem.isEmpty() || ELLIPSIS.equals(stem))
		{
			return fit(fm, full, width);
		}
		return stem + suffix;
	}

	/** Whether {@code name} ends in a dose bracket - "(1)" to "(9)" with no space before it. */
	private static boolean hasDoseSuffix(String name)
	{
		final int n = name.length();
		return n > 4 && name.charAt(n - 1) == ')' && name.charAt(n - 2) >= '0' && name.charAt(n - 2) <= '9'
			&& name.charAt(n - 3) == '(' && name.charAt(n - 4) != ' ';
	}

	/** {@link #setFitted} through {@link #fitName}: for a label whose text is an item name. */
	static void setFittedName(JLabel label, @Nullable String text, int width)
	{
		setFitted(label, text, width, Widgets::fitName);
	}

	/**
	 * The body of both setters: cut with {@code fitter}, keep the whole text on the tooltip - measured with the
	 * metrics of the LABEL, which are the metrics it will paint with.
	 */
	private static void setFitted(JLabel label, @Nullable String text, int width, Fitter fitter)
	{
		final String full = text == null ? "" : text;
		final Font font = label.getFont() == null ? FontManager.getRunescapeSmallFont() : label.getFont();
		label.setText(fitter.cut(label.getFontMetrics(font), full, width));
		label.setToolTipText(full.isEmpty() ? null : full);
	}

	/** How a text is cut to its room: {@link #fit} plainly, {@link #fitName} keeping a dose bracket. */
	private interface Fitter
	{
		String cut(FontMetrics fm, String text, int width);
	}

	/**
	 * The five characters HTML gives a meaning that an item name must not - a tooltip is HTML so it can hold
	 * several lines, and "Zulrah's scales" or a name with an ampersand has to survive the trip.
	 */
	static String escapeHtml(@Nullable String text)
	{
		if (text == null)
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '&':
					sb.append("&amp;");
					break;
				case '<':
					sb.append("&lt;");
					break;
				case '>':
					sb.append("&gt;");
					break;
				case '"':
					sb.append("&quot;");
					break;
				case '\'':
					sb.append("&#39;");
					break;
				default:
					sb.append(c);
			}
		}
		return sb.toString();
	}

	// ---------------------------------------------------------------- the move colours (N sections 2 and 3.1, B045)

	/** What a move colour is FOR - the three roles the one sign rule is painted in; see {@link #move}. */
	enum Kind
	{
		/**
		 * A number: a row's gp change and percentage, the hero card's move line. A fall takes
		 * {@link #MOVE_DOWN_TEXT}, the lifted red, because those are 12 to 18 px of small text (B045).
		 */
		FIGURE,
		/** A direction mark: the card's move triangle. The {@code ColorScheme} constants, undimmed and unlifted. */
		MARK,
		/**
		 * A coloured edge: the hero card's left border and a row's 3 px rail. The constants DARKENED, and the
		 * card's own grey for a flat or absent move - an edge that is invisible without changing any width.
		 */
		EDGE,
		/**
		 * A number that must not out-shout the one beside it: a row's gp change, which shares its line with the
		 * percentage (addendum AL). {@link #FIGURE}'s colour mixed {@value #QUIET_MIX_PERCENT}% into the row's
		 * own background - still plainly green or red, so the figure keeps saying which way the price went, but
		 * no longer competing with the percentage for the same glance.
		 *
		 * <p>A FLAT move takes the quiet grey UNDIMMED, exactly as {@link #FIGURE} does: there is nothing there
		 * to out-shout, and pushing a dash toward the background only makes it hard to read.
		 */
		QUIET
	}

	/**
	 * How far {@link Kind#QUIET} is pushed toward the background, as a percentage (addendum AL). Measured by
	 * eye against the real list at 213 px: below about 40 the two figures still read as one, and above about 70
	 * the gp figure stops reading as green at all and the row loses half its direction cue.
	 */
	static final int QUIET_MIX_PERCENT = 55;

	/** {@code from} mixed {@code amount} of the way into {@code to}; 0 is unchanged, 1 is {@code to}. */
	private static Color towards(Color from, Color to, double amount)
	{
		final double a = Math.max(0.0, Math.min(1.0, amount));
		return new Color(
			(int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * a),
			(int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * a),
			(int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * a));
	}

	/**
	 * The one move rule, in whichever role is asking: green for a rise, red for a fall, and the quiet grey - or,
	 * for an {@link Kind#EDGE}, the card grey - for a flat move or none at all. Every colour in this sidebar that
	 * says which way a price went comes from here, so a row's rail, its figures and the hero card's edge cannot
	 * drift apart.
	 *
	 * @param signum which way it went: +1 up, -1 down, 0 for a flat move or no baseline at all - the caller reads
	 *               it off its own gp figure ({@code Long.signum}), which is what signs the percentage too (L2)
	 */
	static Color move(int signum, Kind kind)
	{
		if (signum == 0)
		{
			return kind == Kind.EDGE ? ColorScheme.DARKER_GRAY_COLOR : ColorScheme.LIGHT_GRAY_COLOR;
		}
		final Color colour = signum > 0 ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_ERROR_COLOR;
		switch (kind)
		{
			case FIGURE:
				return liftRed(colour);
			case QUIET:
				return towards(liftRed(colour), ColorScheme.DARKER_GRAY_COLOR, QUIET_MIX_PERCENT / 100.0);
			case EDGE:
				return colour.darker();
			default:
				return colour;
		}
	}

	/**
	 * {@link #MOVE_DOWN_TEXT} for the falling constant, {@code colour} untouched for anything else - the "as
	 * TEXT" half of {@link #move}, kept apart so the pairing is stated once and the marks are visibly not lifted.
	 */
	static Color liftRed(Color colour)
	{
		return ColorScheme.PROGRESS_ERROR_COLOR.equals(colour) ? MOVE_DOWN_TEXT : colour;
	}

	// ---------------------------------------------------------------- controls

	/**
	 * A compact button for header actions (small face, tight margins). The EMPTY card's "Clear price range" is
	 * the one left: the Ticker sidebar's other controls are text links and segments, because a
	 * {@code JButton} is painted by whatever {@code ButtonUI} the client's look and feel installs (see
	 * {@link #segment}), and this one is deliberately a button - it is the way out of a filter that hid
	 * everything, and it should look like the platform's own control.
	 */
	static JButton smallButton(String text, @Nullable String tooltip, ActionListener onClick)
	{
		final JButton b = new JButton(text);
		b.setToolTipText(tooltip);
		b.setFocusPainted(false);
		b.addActionListener(onClick);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setMargin(new Insets(1, 5, 1, 5));
		return b;
	}

	// ------------------------------------------- addendum N: segments, the underline chip, and text links

	/**
	 * One cell of a segmented strip - a window chip inside the hero card, or a preset in the price fold (N 4.4
	 * control 1, N 3.5): a {@link JLabel} with a hand cursor, centred text at the chip size, and a hover that
	 * follows what {@link #chip} last painted it as.
	 *
	 * <p><b>A JLabel and not a JButton.</b> This is {@code MaterialTab}'s own idiom
	 * ({@code runelite-client/src/main/java/net/runelite/client/ui/components/materialtabs/MaterialTab.java:51,
	 * :123-142} - a {@code JLabel} given a border, a foreground and a {@code MouseAdapter}), and it is the
	 * only way to be sure of the face: a {@code JButton} is painted by whatever {@code ButtonUI} the client's
	 * look and feel installs, which is free to ignore the colours entirely.
	 *
	 * <p>The size is NOT pinned here - a window chip is {@code fixed(38, 22)} inside the card's 191 px and a
	 * preset {@code fixed(49, 24)} in the fold (N 4.2, 3.5).
	 *
	 * <p>{@code onClick} does not fire while the segment is lit: re-selecting the window you are already on,
	 * or the price preset you already have, is a no-op by definition (N 3.2), and swallowing it here means no
	 * caller has to remember. Nor does it fire for anything but a LEFT-button press ({@link #isPress}): the
	 * chips sit inside the hero card, whose right-click is the card's own menu (N 3.1, O4), and a right-click
	 * that also changed the window would be two answers to one gesture.
	 *
	 * @param onClick run on a left press of an UNLIT segment; may be null for a segment that is only a light
	 */
	static JLabel segment(String text, @Nullable String tooltip, @Nullable Runnable onClick)
	{
		final JLabel cell = new JLabel(text == null ? "" : text);
		cell.setHorizontalAlignment(SwingConstants.CENTER);
		cell.setVerticalAlignment(SwingConstants.CENTER);
		cell.setToolTipText(tooltip);
		cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chip(cell, false);
		cell.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onClick != null && isPress(e) && !isLit(cell))
				{
					onClick.run();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				hoverChip(cell, true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				hoverChip(cell, false);
			}
		});
		return cell;
	}

	/**
	 * Paints a segment lit or unlit in the Ticker style (N 4.4 control 1, the only style since O1) and records
	 * it on the label so {@link #isLit} and the hover can read it back: transparent (the hero card's or the
	 * fold's ground shows through), lit = bold {@code BRAND_ORANGE} over a {@link #CHIP_UNDERLINE} px orange
	 * {@link MatteBorder}, unlit = plain {@code LIGHT_GRAY} over an {@link EmptyBorder} of the same height, so
	 * lighting a cell never moves the strip.
	 */
	static void chip(JLabel label, boolean lit)
	{
		final Color colour = lit ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR;
		// Already painted exactly this way: setFont and setBorder each fire a property change and invalidate the
		// label, and renderChips repaints all nine cells on every publish. The foreground is part of the test
		// because hoverChip paints an UNLIT cell orange without changing what is recorded here - a repaint under
		// the mouse must still put the resting colour back, as it always did.
		if (Boolean.valueOf(lit).equals(label.getClientProperty(KEY_LIT)) && colour.equals(label.getForeground()))
		{
			return;
		}
		label.putClientProperty(KEY_LIT, lit);
		label.setOpaque(false);
		label.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		label.setForeground(colour);
		label.setFont(lit ? CHIP_LIT_FONT : CHIP_UNLIT_FONT);
		label.setBorder(lit ? CHIP_LIT_BORDER : CHIP_UNLIT_BORDER);
	}

	/**
	 * Whether {@link #chip} last lit {@code label} - read by the tests, the bridge and the segment's own
	 * click gate. A label this class never painted is not lit.
	 */
	static boolean isLit(JLabel label)
	{
		final Object lit = label.getClientProperty(KEY_LIT);
		return lit instanceof Boolean && (Boolean) lit;
	}

	/**
	 * The hover half of {@link #segment}, exposed so a test can drive it without synthesising a
	 * {@link MouseEvent}: an UNLIT cell's text goes {@code BRAND_ORANGE} under the mouse and back to
	 * {@code LIGHT_GRAY} after. A LIT segment does not react - it is already the answer, and nothing about it
	 * is clickable (N 3.2).
	 */
	static void hoverChip(JLabel label, boolean entered)
	{
		if (isLit(label))
		{
			return;
		}
		label.setForeground(entered ? ColorScheme.BRAND_ORANGE : ColorScheme.LIGHT_GRAY_COLOR);
	}

	/**
	 * A text control that behaves like a link: a {@link JLabel} in {@code base}, repainted in {@code hover}
	 * while the mouse is over it, with a hand cursor and a press that runs {@code onClick} (N section 7).
	 * The sort word-button, the band button, "Refresh" and "Show 279 more" are all this.
	 *
	 * <p>A label rather than a borderless {@code JButton} for the same reason {@link #segment} is: under the
	 * client's look and feel a button paints a ground of its own choosing, and every one of these sits
	 * directly on the sidebar's ground.
	 *
	 * <p>The two colours are stored on the label so {@link #setLinkColors} can re-tint a link whose meaning
	 * has changed - without the next mouse-exit painting the old colour back. The Ticker sidebar never re-tints
	 * one (its band button states its band in words instead, N 4.4 control 3, and
	 * {@code BankPriceMovementPanelTest} pins that button in the ordinary text colour with a band set); the
	 * mechanism is kept because it is the only correct way to change a link's resting colour.
	 */
	static JLabel linkLabel(String text, Font font, Color base, Color hover, @Nullable Runnable onClick)
	{
		final JLabel link = new JLabel(text == null ? "" : text);
		link.setFont(font);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		setLinkColors(link, base, hover);
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (onClick != null && isPress(e))
				{
					onClick.run();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				link.setForeground(linkColor(link, KEY_LINK_HOVER, ColorScheme.BRAND_ORANGE));
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				link.setForeground(linkColor(link, KEY_LINK_BASE, ColorScheme.LIGHT_GRAY_COLOR));
			}
		});
		return link;
	}

	/**
	 * Re-tints a link made by {@link #linkLabel}: paints it {@code base} now and remembers both colours for
	 * the hover, so the next mouse-exit paints the NEW resting colour. Safe on any label - one that was never a
	 * link simply gains the two properties. {@link #linkLabel} itself calls this to record the pair.
	 */
	static void setLinkColors(JLabel link, Color base, Color hover)
	{
		link.putClientProperty(KEY_LINK_BASE, base);
		link.putClientProperty(KEY_LINK_HOVER, hover);
		link.setForeground(base);
	}

	private static Color linkColor(JLabel link, String key, Color fallback)
	{
		final Object colour = link.getClientProperty(key);
		return colour instanceof Color ? (Color) colour : fallback;
	}

	/**
	 * Whether a mouse-down is a PRESS of the control under it: the left button, and not the platform's popup
	 * trigger. Every clickable label in the sidebar - {@link #segment}, {@link #linkLabel}, the panel's icon
	 * buttons - asks this before acting, because the hero card answers a right-click with its own menu
	 * (Refresh and the three show / hide items, N 3.1, O4) and keeps its window chips and its "Refresh" link
	 * INSIDE that card: without the gate a right-click on "7d" would switch the window AND open the menu.
	 *
	 * <p>Both halves are needed. Windows raises the popup trigger on the RELEASE, so on the press only the
	 * button tells; macOS raises it on a ctrl-click of the LEFT button, so there only the trigger tells.
	 * {@code MaterialTab} (the idiom the segment borrows) has no such gate, but no {@code MaterialTab} sits
	 * inside a component with a popup menu.
	 */
	static boolean isPress(MouseEvent e)
	{
		return SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger();
	}

	/**
	 * A 7 px code-drawn triangle in the brand colour - the sort direction on the active sort chip (contract
	 * C29). Drawn rather than typed because the bitmap RuneScape fonts have no triangle glyph (playbook 7.5).
	 *
	 * @param down true for descending (point down), false for ascending (point up)
	 */
	static ImageIcon triangle(boolean down)
	{
		return triangle(down, TRIANGLE_SIZE, ColorScheme.BRAND_ORANGE);
	}

	/**
	 * {@link #triangle(boolean)} at a chosen size and colour (addendum N section 7). Same geometry, so the
	 * one-argument call above still draws exactly the icon it drew before this overload existed - a test
	 * compares the two images pixel for pixel.
	 *
	 * @param down   true to point down (descending / "open this menu"), false to point up
	 * @param size   the square icon's side in px; anything under 3 is raised to 3, below which the 1 px
	 *               inset would invert the path
	 * @param colour the fill
	 */
	static ImageIcon triangle(boolean down, int size, Color colour)
	{
		final int s = Math.max(3, size);
		return icon(s, s, g ->
		{
			g.setColor(colour);
			final Path2D p = new Path2D.Double();
			if (down)
			{
				p.moveTo(0, 1);
				p.lineTo(s, 1);
				p.lineTo(s / 2.0, s - 1);
			}
			else
			{
				p.moveTo(0, s - 1);
				p.lineTo(s, s - 1);
				p.lineTo(s / 2.0, 1);
			}
			p.closePath();
			g.fill(p);
		});
	}

	/** The 7 px triangle pointing down, in a colour of the caller's choosing (N section 5). */
	static ImageIcon triangleDown(Color colour)
	{
		return triangle(true, TRIANGLE_SIZE, colour);
	}

	/** The 7 px triangle pointing up, in a colour of the caller's choosing (N section 5). */
	static ImageIcon triangleUp(Color colour)
	{
		return triangle(false, TRIANGLE_SIZE, colour);
	}

	/**
	 * The 11 px "x" that clears both gp bounds in the price fold (addendum N section 3.5). Two strokes, not
	 * the letter: at 11 px a typed x in the bitmap face is a smudge, and the cross has to read as a control.
	 */
	static ImageIcon clearIcon(Color colour)
	{
		final int s = 11;
		return icon(s, s, g ->
		{
			g.setColor(colour);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(2, 2, s - 3, s - 3);
			g.drawLine(s - 3, 2, 2, s - 3);
		});
	}

	/**
	 * The mark on a sort-menu entry (addendum N section 3.4): a filled 7 px {@code BRAND_ORANGE} dot on the
	 * ordering in force, a 5 px {@code LIGHT_GRAY} ring on the other five. Both are drawn into the same 7 px
	 * box so the six labels line up, and neither depends on the look-and-feel's radio-button mark - which is
	 * why these are plain {@code JMenuItem}s and not {@code JRadioButtonMenuItem}s.
	 */
	static ImageIcon dotIcon(boolean filled)
	{
		final int s = TRIANGLE_SIZE;
		return icon(s, s, g ->
		{
			if (filled)
			{
				g.setColor(ColorScheme.BRAND_ORANGE);
				g.fill(new Ellipse2D.Double(0, 0, s, s));
				return;
			}

			g.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g.setStroke(new BasicStroke(1f));
			g.draw(new Ellipse2D.Double(1.5, 1.5, s - 3.0, s - 3.0));
		});
	}

	/**
	 * A code-drawn check box (addendum AH): an empty square when off, a square with a tick through it when on.
	 *
	 * <p><b>Why a drawn box rather than a {@link javax.swing.JCheckBoxMenuItem}.</b> RuneLite's look and feel
	 * paints a selected check item with a tick and an UNSELECTED one with nothing at all, which is right for a
	 * switch that ships on - the reader has seen it ticked and knows what the blank means. A switch that ships
	 * OFF is read for the first time in its unticked state, where blank space beside a label is
	 * indistinguishable from an ordinary command: nothing on screen says it is a switch, or that it has a state
	 * to change. The empty square says both. The bitmap RuneScape faces have no box or tick glyph either
	 * (playbook 7.5), which is the same reason {@link #triangle(boolean)} is drawn rather than typed.
	 *
	 * @param ticked whether the switch is on
	 */
	static ImageIcon checkBox(boolean ticked)
	{
		return checkBox(ticked, CHECKBOX_SIZE, ColorScheme.LIGHT_GRAY_COLOR, ColorScheme.BRAND_ORANGE);
	}

	/**
	 * {@link #checkBox(boolean)} at a chosen size and colours - the geometry is one square and one three-point
	 * path, both in fractions of the side, so the glyph is the same shape at every size.
	 *
	 * @param ticked whether to draw the tick inside the square
	 * @param size   the square icon's side in px; anything under {@value #CHECKBOX_MIN} is raised to it
	 * @param box    the outline
	 * @param tick   the mark inside it
	 */
	static ImageIcon checkBox(boolean ticked, int size, Color box, Color tick)
	{
		final int s = Math.max(CHECKBOX_MIN, size);
		return icon(s, s, g ->
		{
			// Half the stroke sits outside the path, so the rectangle is inset by half a line and shortened by a
			// whole one - without that the right and bottom edges paint into the icon's last pixel and blur.
			final float line = (float) Math.max(1.0, s * 0.1);
			g.setColor(box);
			g.setStroke(new BasicStroke(line));
			g.draw(new Rectangle2D.Double(line / 2.0, line / 2.0, s - line, s - line));
			if (ticked)
			{
				g.setColor(tick);
				g.setStroke(new BasicStroke((float) Math.max(1.5, s * 0.17),
					BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				final Path2D p = new Path2D.Double();
				p.moveTo(s * 0.24, s * 0.53);
				p.lineTo(s * 0.43, s * 0.73);
				p.lineTo(s * 0.78, s * 0.27);
				g.draw(p);
			}
		});
	}

	/**
	 * The options gear (addendum Q, line Q1): a ring with {@value #GEAR_TEETH} teeth and a HOLE through the
	 * middle, drawn in code at the size and colour the caller asks for - {@code LIGHT_GRAY} at rest on the hero
	 * card's total line, {@code BRAND_ORANGE} under the mouse. Drawn and not loaded, exactly as
	 * {@link NavIcon} draws the sidebar button: this package ships no image files (contract C46 forbids
	 * classpath resources here), and the bitmap RuneScape faces have no gear glyph to type.
	 *
	 * <p>The ring is a STROKE and not a filled disc, so the middle stays transparent and the glyph reads as a
	 * gear at 12 px rather than as a cogged blob; the teeth are eight round-capped spokes every 45 degrees,
	 * starting on the ring's own circle so each one is rooted inside the ring's metal and none of them reaches
	 * into the hole. Every length is a fraction of {@code size}, so the same shape comes out at any size.
	 *
	 * @param size   the square icon's side in px; anything under {@value #GEAR_MIN} is raised to it, below
	 *               which the hole closes and the teeth merge
	 * @param colour the ink; null falls back to the sidebar's quiet grey
	 */
	static ImageIcon gearIcon(int size, @Nullable Color colour)
	{
		final int s = Math.max(GEAR_MIN, size);
		return icon(s, s, g ->
		{
			g.setColor(colour == null ? ColorScheme.LIGHT_GRAY_COLOR : colour);
			final double centre = s / 2.0;
			// The ring's centre line, the width of its metal, and how far a tooth's centre line reaches - the
			// last one pulled in by half a tooth so the round cap lands inside the icon instead of being clipped.
			final double ring = centre * 0.52;
			final float ringWidth = (float) Math.max(1.5, s * 0.2);
			final float toothWidth = (float) Math.max(1.3, s * 0.16);
			final double toothOut = centre - toothWidth / 2.0 - 0.25;
			g.setStroke(new BasicStroke(ringWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Ellipse2D.Double(centre - ring, centre - ring, ring * 2.0, ring * 2.0));
			g.setStroke(new BasicStroke(toothWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			for (int i = 0; i < GEAR_TEETH; i++)
			{
				final double angle = i * 2.0 * Math.PI / GEAR_TEETH;
				final double cos = Math.cos(angle);
				final double sin = Math.sin(angle);
				g.draw(new Line2D.Double(centre + cos * ring, centre + sin * ring,
					centre + cos * toothOut, centre + sin * toothOut));
			}
		});
	}

	/**
	 * Paints one code-drawn icon into an off-screen image with antialiasing on, and hands back an
	 * {@link ImageIcon} of it. A {@link BufferedImage} rather than a live {@code Icon} implementation so the
	 * drawing happens ONCE, headless, at build time - which is also what lets the tests paint every glyph in
	 * a JVM with no display.
	 */
	private static ImageIcon icon(int width, int height, Painter painter)
	{
		final BufferedImage img = new BufferedImage(Math.max(1, width), Math.max(1, height),
			BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			painter.paint(g);
		}
		finally
		{
			g.dispose();
		}
		return new ImageIcon(img);
	}

	/** What {@link #icon} hands its prepared {@link Graphics2D} to. */
	private interface Painter
	{
		void paint(Graphics2D g);
	}

	// ---------------------------------------------------------------- addendum N: the card border

	/**
	 * The hero card's border (addendum N section 3.1): a {@link #EDGE_WIDTH} px left edge in the move's
	 * colour over the card's padding. Re-set with {@code setBorder} whenever the move changes sign, which is
	 * why the edge is a border and not a painted rectangle - it survives every repaint the panel does not
	 * own.
	 *
	 * @param edge the edge colour; null, or {@code DARKER_GRAY} for a flat or absent move, makes the edge
	 *             invisible against the card without changing the card's width
	 */
	static Border card(Color edge)
	{
		return card(edge, CARD_INSETS);
	}

	/** {@link #card(Color)} with padding of the caller's choosing. */
	static Border card(Color edge, Insets padding)
	{
		final Insets in = padding == null ? CARD_INSETS : padding;
		return new CompoundBorder(
			new MatteBorder(0, EDGE_WIDTH, 0, 0, edge == null ? ColorScheme.DARKER_GRAY_COLOR : edge),
			new EmptyBorder(in.top, in.left, in.bottom, in.right));
	}

	/**
	 * A {@link FlatTextField} for a gp bound: small face, the sidebar's field colours, a thin border that
	 * {@link #markInvalid} turns red, and {@code placeholder} drawn in grey while the field is empty and not
	 * being typed in. The placeholder is painted by the field itself (see {@link PlaceholderField}) so it
	 * needs no look-and-feel support and never becomes part of the text.
	 */
	static PlaceholderField gpField(String placeholder)
	{
		final PlaceholderField f = new PlaceholderField(placeholder);
		final JTextField tf = f.getTextField();
		tf.setFont(FontManager.getRunescapeSmallFont());
		tf.setForeground(ColorScheme.TEXT_COLOR);
		tf.setCaretColor(ColorScheme.TEXT_COLOR);
		f.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		f.setHoverBackgroundColor(ColorScheme.DARKER_GRAY_HOVER_COLOR);
		markInvalid(f, false);
		return f;
	}

	/**
	 * A red ring on a field whose text did not parse (contract C29: "an invalid entry turns the field border
	 * red and leaves the filter unchanged"), the normal thin border otherwise. The inner padding keeps the text
	 * where {@code FlatTextField}'s own 10 px inset ({@code FlatTextField.java:64}) puts it.
	 */
	static void markInvalid(FlatTextField f, boolean invalid)
	{
		f.setBorder(new CompoundBorder(
			new LineBorder(invalid ? ColorScheme.PROGRESS_ERROR_COLOR : FIELD_BORDER, 1),
			new EmptyBorder(1, 9, 1, 3)));
	}

	/** Whether {@link #markInvalid} last painted {@code f} red. */
	static boolean isMarkedInvalid(FlatTextField f)
	{
		final Border b = f.getBorder();
		if (!(b instanceof CompoundBorder))
		{
			return false;
		}
		final Border outer = ((CompoundBorder) b).getOutsideBorder();
		return outer instanceof LineBorder && ColorScheme.PROGRESS_ERROR_COLOR.equals(((LineBorder) outer).getLineColor());
	}

	/**
	 * A {@link FlatTextField} that paints a grey placeholder over its (transparent) text field while the text
	 * is empty and the field is not focused. Painted in {@code paintChildren} after the children, so it sits
	 * above the text field whatever look and feel is installed; a Swing client property would need the
	 * look-and-feel's cooperation, which the client's does not give.
	 */
	static final class PlaceholderField extends FlatTextField
	{
		private final String placeholder;

		PlaceholderField(String placeholder)
		{
			this.placeholder = placeholder == null ? "" : placeholder;
		}

		String placeholder()
		{
			return placeholder;
		}

		/** Whether the placeholder would be painted right now: empty and not being typed in. */
		boolean placeholderShowing()
		{
			return getText().isEmpty() && !getTextField().isFocusOwner();
		}

		@Override
		protected void paintChildren(Graphics g)
		{
			super.paintChildren(g);
			if (!placeholderShowing() || placeholder.isEmpty())
			{
				return;
			}
			final JTextField tf = getTextField();
			final Graphics2D g2 = (Graphics2D) g.create();
			try
			{
				g2.setFont(tf.getFont());
				g2.setColor(PLACEHOLDER_COLOR);
				final FontMetrics fm = g2.getFontMetrics();
				final Insets in = tf.getInsets();
				final int x = tf.getX() + in.left + 1;
				final int y = tf.getY() + (tf.getHeight() - fm.getHeight()) / 2 + fm.getAscent();
				g2.drawString(placeholder, x, y);
			}
			finally
			{
				g2.dispose();
			}
		}
	}

	// ---------------------------------------------------------------- layout

	/**
	 * Pins a component's width and height. The width rule only holds if the components that could grow -
	 * icons, fields, chips - are pinned, because a column stretches its rows to the widest one and the panel's
	 * scroll pane never scrolls sideways.
	 */
	static <T extends JComponent> T fixed(T c, int width, int height)
	{
		final Dimension d = new Dimension(width, height);
		c.setPreferredSize(d);
		c.setMinimumSize(d);
		c.setMaximumSize(d);
		return c;
	}

	/** A vertical stack (top to bottom, full width) on the panel background, 3 px between rows. */
	static JPanel column()
	{
		return column(3);
	}

	/**
	 * {@link #column()} with a chosen gap. {@link DynamicGridLayout} gives every row its preferred height and
	 * scales widths to the column's own width ({@code DynamicGridLayout.java:97-113}), which is what lets a
	 * 213 px design fill the 225 or 242 px the sidebar actually has.
	 */
	static JPanel column(int vgap)
	{
		final JPanel p = new JPanel();
		p.setLayout(new DynamicGridLayout(0, 1, 0, vgap));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		return p;
	}

	/** One line: something on the left, something filling the middle, something on the right. Any may be null. */
	static JPanel bar(int gap, @Nullable JComponent west, @Nullable JComponent centre, @Nullable JComponent east)
	{
		final JPanel p = new JPanel(new BorderLayout(gap, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		if (west != null)
		{
			p.add(west, BorderLayout.WEST);
		}
		if (centre != null)
		{
			p.add(centre, BorderLayout.CENTER);
		}
		if (east != null)
		{
			p.add(east, BorderLayout.EAST);
		}
		return p;
	}

	/** Equal-width cells in one line, for the chip rows. */
	static JPanel grid(int gap, JComponent... items)
	{
		final JPanel p = new JPanel(new GridLayout(1, items.length, gap, 0));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		for (JComponent c : items)
		{
			p.add(c);
		}
		return p;
	}

	/**
	 * Anchors {@code c} to the top of a BorderLayout holder. A scroll pane stretches a plain view to its
	 * viewport ({@code javax.swing.ViewportLayout}), and {@link DynamicGridLayout} would then scale every row
	 * TALLER to fill it; anchored north, the column keeps its rows' heights and the holder takes the slack.
	 */
	static JPanel north(JComponent c)
	{
		final JPanel holder = new JPanel(new BorderLayout());
		holder.setBackground(ColorScheme.DARK_GRAY_COLOR);
		holder.add(c, BorderLayout.NORTH);
		return holder;
	}

	/**
	 * The width the widest row inside {@code c} asks for, in px: the width rule measured rather than trusted.
	 * Descends into nested columns (their own preferred width is only their widest row) and takes every other
	 * child at its preferred width - a chip row counts as its three chips plus gaps, which is exactly the
	 * width its cells need.
	 */
	static int widest(Container c)
	{
		int w = 0;
		for (Component child : c.getComponents())
		{
			if (child instanceof JPanel && ((JPanel) child).getLayout() instanceof DynamicGridLayout)
			{
				w = Math.max(w, widest((Container) child));
			}
			else
			{
				w = Math.max(w, child.getPreferredSize().width);
			}
		}
		return w;
	}
}
