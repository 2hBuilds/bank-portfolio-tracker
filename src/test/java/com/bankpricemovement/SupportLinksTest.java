package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link SupportLinks}, the support pair and the settings menu's header pulled out of the panel so that the same file
 * can be copied into any 2hBuilds plugin: tested here on its own, with a recorder for the browser and a counter for the
 * menu hook, and no panel, no config and no service anywhere. A plugin that copies the class copies this test beside it.
 */
public class SupportLinksTest
{
	private static final String NAME = "2h Bank Portfolio Tracker";
	private static final String PLUGIN_URL = "https://github.com/2hBuilds/bank-portfolio-tracker";

	/** What the two hooks were handed: every URL the browser got, and how many times the menu was told to close. */
	private final List<String> visited = new ArrayList<>();
	private int closes;

	private SupportLinks links(final String displayName, final String version)
	{
		return new SupportLinks(displayName, version, PLUGIN_URL, visited::add, () -> closes++);
	}

	/** A stand-in settings icon: 12 px, carrying the border a plugin's real one carries. */
	private static JLabel settingsIcon()
	{
		final ImageIcon glyph = new ImageIcon(new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB));
		final JLabel icon = SupportLinks.namedIconButton(glyph, glyph, SupportLinks.SETTINGS_TIP, () -> { });
		icon.setBorder(SupportLinks.iconBorder());
		return icon;
	}

	// ---------------------------------------------------------------- the pair

	/**
	 * The pair, in the card's top-right corner: the Discord mark, then the strip, then the settings icon - the settings
	 * icon right-most - the Discord mark 12 px with the settings icon's own insets, the two 8 px apart ink to ink, and
	 * the pair exactly as wide as its two labels and the strip, as tall as the icons with their insets.
	 */
	@Test
	public void thePairIsTheDiscordMarkThenTheSettingsIconEightPixelsApartInkToInk()
	{
		final JLabel settings = settingsIcon();
		final JPanel pair = links(NAME, "1.0.9").supportPair(settings);

		assertEquals("the mark, the strip and the settings icon", 3, pair.getComponentCount());
		final JLabel discord = (JLabel) pair.getComponent(0);
		assertTrue("a strip of clear ground between them", pair.getComponent(1) instanceof Box.Filler);
		assertSame("the settings icon is the right-most thing", settings, pair.getComponent(2));
		assertFalse("not painted: the card's own ground shows", pair.isOpaque());

		assertEquals("the Discord mark is 12 px", 12, discord.getIcon().getIconWidth());
		assertEquals(12, discord.getIcon().getIconHeight());
		assertEquals(SupportLinks.DISCORD_TIP, discord.getToolTipText());
		final java.awt.Insets in = discord.getInsets();
		assertEquals("the settings icon's insets: 2 above, 6 left, 2 below, none right", Arrays.asList(2, 6, 2, 0),
			Arrays.asList(in.top, in.left, in.bottom, in.right));

		assertEquals("8 is the contract's number", 8, SupportLinks.PAIR_GAP);
		pair.setSize(pair.getPreferredSize());
		pair.doLayout();
		assertEquals("the two are 8 px apart, ink to ink", SupportLinks.PAIR_GAP,
			inkLeft(settings) - inkRight(discord));
		assertEquals("the settings icon ends where the pair does", pair.getWidth(), settings.getX() + settings.getWidth());
		assertEquals("as wide as the labels and the strip between them",
			discord.getPreferredSize().width + (SupportLinks.PAIR_GAP - 6) + settings.getPreferredSize().width,
			pair.getPreferredSize().width);
		assertEquals("as tall as the icons with their 2 px insets", 16, pair.getPreferredSize().height);
	}

	/**
	 * At rest the Discord mark is the white mark at alpha 165 - the settings icon's grey by ALPHA, never by recolouring -
	 * and under the mouse the same pixel is white at 255. Sampled at the first fully opaque pixel of the file: the 12 px
	 * mark's centre pixel is at 243 of 255, so the centre would read 157 and prove nothing about the rule.
	 */
	@Test
	public void theDiscordMarkRestsAtAlphaAbout165AndGoesWhiteUnderTheMouse()
	{
		final JLabel discord = links(NAME, "1.0.9").discordMark();
		final BufferedImage rest = imageOf(discord);
		deliver(discord, MouseEvent.MOUSE_ENTERED, MouseEvent.NOBUTTON);
		final BufferedImage hot = imageOf(discord);
		deliver(discord, MouseEvent.MOUSE_EXITED, MouseEvent.NOBUTTON);
		assertSame("leaving brings the resting mark back", rest, imageOf(discord));

		int at = -1;
		for (int y = 0; y < hot.getHeight() && at < 0; y++)
		{
			for (int x = 0; x < hot.getWidth() && at < 0; x++)
			{
				if (hot.getRGB(x, y) >>> 24 == 255)
				{
					at = y * hot.getWidth() + x;
				}
			}
		}
		assertTrue("the file has a fully opaque pixel", at >= 0);
		final int px = at % hot.getWidth();
		final int py = at / hot.getWidth();
		assertEquals("under the mouse: white at 255", 0xFFFFFFFF, hot.getRGB(px, py));
		assertEquals("at rest: alpha about 165", 165, rest.getRGB(px, py) >>> 24, 1);
		assertEquals("...still white, not grey", 0xFFFFFF, rest.getRGB(px, py) & 0xFFFFFF);
		assertEquals("the hand cursor, like every control", Cursor.HAND_CURSOR, discord.getCursor().getType());
	}

	/** The mark's press browses the Discord invite, once, and does not touch the menu: it is on a card, not in the menu. */
	@Test
	public void theDiscordMarkOnACardBrowsesTheInviteAndLeavesTheMenuAlone()
	{
		final JLabel discord = links(NAME, "1.0.9").discordMark();

		deliver(discord, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3);
		assertTrue("a right-button press is no press: " + visited, visited.isEmpty());
		deliver(discord, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);

		assertEquals(Collections.singletonList(SupportLinks.DISCORD_URL), visited);
		assertEquals("no menu is involved", 0, closes);
	}

	// ---------------------------------------------------------------- the header

	/**
	 * The header is ONE panel of three children - the title row, the version line, the links row - on the ground the
	 * menu draws, with the captions' column inset (2 above and below, 6 each side), so a menu that holds it counts it as
	 * one component.
	 */
	@Test
	public void theHeaderHasThreeChildrenTheTitleTheVersionAndTheLinks()
	{
		final JPanel header = links(NAME, "1.0.9").header();

		assertEquals(3, header.getComponentCount());
		assertTrue("a column, the way the sidebar stacks rows", header.getLayout() instanceof DynamicGridLayout);
		assertFalse("see-through over the menu's ground", header.isOpaque());
		final java.awt.Insets in = header.getInsets();
		assertEquals(Arrays.asList(2, 6, 2, 6), Arrays.asList(in.top, in.left, in.bottom, in.right));
		assertTrue("the title row", header.getComponent(0) instanceof JPanel);
		assertTrue("the version line", header.getComponent(1) instanceof JLabel);
		assertTrue("the links row", header.getComponent(2) instanceof JPanel);
	}

	/** "2h" in the brand orange, the rest of the name in white, both bold 13; the glue keeps them at the left. */
	@Test
	public void theTitleDrawsTheFamilyMarkInOrangeAndTheRestOfTheNameInWhite()
	{
		final Container title = (Container) links(NAME, "1.0.9").header().getComponent(0);

		assertEquals("the mark, the rest of the name, and the glue that keeps them left", 3, title.getComponentCount());
		final JLabel mark = (JLabel) title.getComponent(0);
		final JLabel rest = (JLabel) title.getComponent(1);
		assertTrue(title.getComponent(2) instanceof Box.Filler);
		assertEquals("2h", mark.getText());
		assertEquals(SupportLinks.FAMILY_MARK, mark.getText());
		assertEquals(" Bank Portfolio Tracker", rest.getText());
		assertEquals("the two read as the one name", NAME, mark.getText() + rest.getText());
		assertEquals(ColorScheme.BRAND_ORANGE, mark.getForeground());
		assertEquals(Color.WHITE, rest.getForeground());
		assertEquals(13, SupportLinks.HEADER_TITLE_SIZE);
		final java.awt.Font bold13 = FontManager.getDefaultBoldFont().deriveFont(13f);
		assertEquals("bold, 13", bold13, mark.getFont());
		assertEquals(bold13, rest.getFont());
		for (final JLabel part : new JLabel[]{mark, rest})
		{
			assertNull("no hover", part.getToolTipText());
			assertEquals("and not clickable: no mouse listener on the line", 0, part.getMouseListeners().length);
		}
		assertEquals(Component.LEFT_ALIGNMENT, title.getAlignmentX(), 0f);
	}

	/** A name that does not open with "2h " has no family mark: one white label, bold 13, and the glue. */
	@Test
	public void aNameWithoutTheFamilyMarkIsDrawnWholeInWhite()
	{
		for (final String name : new String[]{"Zen Bank", "2hBuilds Zen Bank", "2h", "Why Lag 2h"})
		{
			final Container title = (Container) links(name, "0.1.0").header().getComponent(0);

			assertEquals(name + ": one label and the glue", 2, title.getComponentCount());
			final JLabel whole = (JLabel) title.getComponent(0);
			assertEquals(name, whole.getText());
			assertEquals(Color.WHITE, whole.getForeground());
			assertEquals(FontManager.getDefaultBoldFont().deriveFont(13f), whole.getFont());
			assertTrue(title.getComponent(1) instanceof Box.Filler);
		}
		// ...and one that does open with it, however short the rest, splits.
		final Container split = (Container) links("2h Zen Bank", "0.1.0").header().getComponent(0);
		assertEquals("2h", ((JLabel) split.getComponent(0)).getText());
		assertEquals(" Zen Bank", ((JLabel) split.getComponent(1)).getText());
	}

	/** "Version " and the build, in the darker grey, at the menu's own 12 px, left-aligned, no hover, no click. */
	@Test
	public void theVersionLineIsGreyTwelveAtTheLeftWithNoHoverAndNoClick()
	{
		final JLabel version = (JLabel) links(NAME, "3.4.5").header().getComponent(1);

		assertEquals("Version 3.4.5", version.getText());
		assertEquals(SupportLinks.HEADER_GREY, version.getForeground());
		assertEquals(new Color(135, 135, 135), version.getForeground());
		assertTrue("darker than the captions", SupportLinks.HEADER_GREY.getRed() < ColorScheme.LIGHT_GRAY_COLOR.getRed());
		assertTrue("but well clear of the menu's ground", SupportLinks.HEADER_GREY.getRed() >= 120);
		assertEquals(12, SupportLinks.MENU_FONT_SIZE);
		assertEquals(FontManager.getDefaultFont().deriveFont(12f), version.getFont());
		assertEquals(Component.LEFT_ALIGNMENT, version.getAlignmentX(), 0f);
		assertEquals(SwingConstants.LEFT, version.getHorizontalAlignment());
		assertNull("no hover", version.getToolTipText());
		assertEquals("and not clickable", 0, version.getMouseListeners().length);
	}

	/**
	 * The links row: four 16 px marks from the left edge, 6 px apart, in this order - Discord, X, GitHub for the 2hBuilds
	 * profile and GitHub again for the plugin's page (one picture twice, told apart by the hover) - each hover set on the
	 * label itself, so it stays on: this class has no hover switch to turn it off.
	 */
	@Test
	public void theLinksRowHoldsFourMarksInOrderWithAlwaysOnHovers()
	{
		final JPanel row = (JPanel) links(NAME, "1.0.9").header().getComponent(2);
		final List<JLabel> marks = marksOf(row);

		assertEquals(4, marks.size());
		assertEquals(Arrays.asList("Discord", "X", "2hBuilds on GitHub", "This plugin on GitHub"),
			Arrays.asList(marks.get(0).getToolTipText(), marks.get(1).getToolTipText(), marks.get(2).getToolTipText(),
				marks.get(3).getToolTipText()));
		for (final JLabel mark : marks)
		{
			assertEquals("16 px", 16, mark.getIcon().getIconWidth());
			assertEquals(16, mark.getIcon().getIconHeight());
			assertEquals("and no border: the label is the mark", 16, mark.getPreferredSize().width);
		}
		assertTrue("the two GitHub marks are one picture", samePixels(marks.get(2), marks.get(3)));
		assertFalse("...and the Discord and X marks are not", samePixels(marks.get(0), marks.get(1)));
		assertEquals(Component.LEFT_ALIGNMENT, row.getAlignmentX(), 0f);
		assertEquals("2 px of air above", 2, row.getInsets().top);
		assertTrue("the glue is last", row.getComponent(row.getComponentCount() - 1) instanceof Box.Filler);

		row.setSize(200, row.getPreferredSize().height);
		row.doLayout();
		assertEquals("the first mark is at the left edge", 0, marks.get(0).getX());
		assertEquals(6, SupportLinks.LINK_GAP);
		for (int i = 1; i < marks.size(); i++)
		{
			assertEquals("6 px between marks " + (i - 1) + " and " + i, SupportLinks.LINK_GAP,
				marks.get(i).getX() - (marks.get(i - 1).getX() + marks.get(i - 1).getWidth()));
		}
	}

	/**
	 * Each mark's press hands its own URL to the browser exactly once and then takes the menu down exactly once, as OK
	 * does; a right-button press is nothing; the mark under the mouse is full white and leaving brings the resting one
	 * back. The fourth mark's URL is the one the plugin handed in.
	 */
	@Test
	public void eachMarkBrowsesItsOwnAddressOnceAndClosesTheMenuOnce()
	{
		final List<String> wanted = Arrays.asList(SupportLinks.DISCORD_URL, SupportLinks.X_URL,
			SupportLinks.GITHUB_PROFILE_URL, PLUGIN_URL);
		final List<JLabel> marks = marksOf((JPanel) links(NAME, "1.0.9").header().getComponent(2));

		for (int i = 0; i < marks.size(); i++)
		{
			final JLabel mark = marks.get(i);
			visited.clear();
			closes = 0;
			deliver(mark, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3);
			assertTrue("a right-button press is no press: " + visited, visited.isEmpty());
			assertEquals("nor does it close the menu", 0, closes);
			deliver(mark, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);
			assertEquals("mark " + i + " browses its own address, once", Collections.singletonList(wanted.get(i)), visited);
			assertEquals("and takes the menu down, once", 1, closes);

			final BufferedImage rest = imageOf(mark);
			deliver(mark, MouseEvent.MOUSE_ENTERED, MouseEvent.NOBUTTON);
			final BufferedImage hot = imageOf(mark);
			deliver(mark, MouseEvent.MOUSE_EXITED, MouseEvent.NOBUTTON);
			assertSame("the mouse leaving brings the resting mark back", rest, imageOf(mark));
			final int[] at = firstOpaque(hot);
			assertEquals("under the mouse: white at 255", 0xFFFFFFFF, hot.getRGB(at[0], at[1]));
			assertEquals("at rest: white at alpha about 165", 165, rest.getRGB(at[0], at[1]) >>> 24, 1);
		}
	}

	// ---------------------------------------------------------------- the pieces

	/** Each file is read once, whoever asks and at whatever alpha; the faint look scales only the alpha. */
	@Test
	public void markIconLoadsEachFileOnce()
	{
		final String[] files = {SupportLinks.DISCORD_12, SupportLinks.DISCORD_16, SupportLinks.X_16, SupportLinks.GITHUB_16};
		final int[] sides = {12, 16, 16, 16};
		for (int i = 0; i < files.length; i++)
		{
			final BufferedImage first = (BufferedImage) SupportLinks.markIcon(files[i], 1f).getImage();
			assertSame(files[i] + " is read once", first, SupportLinks.markIcon(files[i], 1f).getImage());
			assertEquals(sides[i], first.getWidth());
			assertEquals(sides[i], first.getHeight());
			final BufferedImage faint = (BufferedImage) SupportLinks.markIcon(files[i], SupportLinks.MARK_REST_ALPHA)
				.getImage();
			for (int y = 0; y < first.getHeight(); y++)
			{
				for (int x = 0; x < first.getWidth(); x++)
				{
					final int full = first.getRGB(x, y);
					assertEquals(files[i] + " alpha at (" + x + ", " + y + ")",
						Math.round((full >>> 24) * SupportLinks.MARK_REST_ALPHA), faint.getRGB(x, y) >>> 24, 1);
					if (full >>> 24 != 0)
					{
						assertEquals(files[i] + " keeps its colour", full & 0xFFFFFF, faint.getRGB(x, y) & 0xFFFFFF);
					}
				}
			}
		}
	}

	/** A left press runs the action and a right press never does; the icon follows the mouse in and out. */
	@Test
	public void aPressableIconRunsOnALeftPressAndFollowsTheMouse()
	{
		final BufferedImage restImage = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		final BufferedImage hotImage = new BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB);
		final ImageIcon rest = new ImageIcon(restImage);
		final ImageIcon hot = new ImageIcon(hotImage);
		final int[] presses = {0};
		final JLabel label = SupportLinks.pressable(rest, hot, () -> presses[0]++);

		assertNull("no hover yet: the caller decides", label.getToolTipText());
		assertSame(rest, label.getIcon());
		deliver(label, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3);
		assertEquals(0, presses[0]);
		deliver(label, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);
		assertEquals(1, presses[0]);
		deliver(label, MouseEvent.MOUSE_ENTERED, MouseEvent.NOBUTTON);
		assertSame(hot, label.getIcon());
		deliver(label, MouseEvent.MOUSE_EXITED, MouseEvent.NOBUTTON);
		assertSame(rest, label.getIcon());

		final JLabel named = SupportLinks.namedIconButton(rest, hot, "Settings", () -> presses[0]++);
		assertEquals("one word, set on the label itself", "Settings", named.getToolTipText());
		deliver(named, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);
		assertEquals(2, presses[0]);
	}

	@Test
	public void theIconBorderIsTwoSixTwoNone()
	{
		final EmptyBorder border = (EmptyBorder) SupportLinks.iconBorder();
		final java.awt.Insets in = border.getBorderInsets();
		assertEquals(Arrays.asList(2, 6, 2, 0), Arrays.asList(in.top, in.left, in.bottom, in.right));
	}

	/** The words and addresses, verbatim: a change to one is a decision, not a refactor. */
	@Test
	public void theConstantsAreVerbatim()
	{
		assertEquals("https://discord.gg/nsam4CfWzf", SupportLinks.DISCORD_URL);
		assertEquals("https://x.com/2hBuilds", SupportLinks.X_URL);
		assertEquals("https://github.com/2hBuilds", SupportLinks.GITHUB_PROFILE_URL);
		assertEquals("Discord", SupportLinks.DISCORD_TIP);
		assertEquals("X", SupportLinks.X_TIP);
		assertEquals("2hBuilds on GitHub", SupportLinks.GITHUB_PROFILE_TIP);
		assertEquals("This plugin on GitHub", SupportLinks.GITHUB_TIP);
		assertEquals("Settings", SupportLinks.SETTINGS_TIP);
		assertEquals("discord_12.png", SupportLinks.DISCORD_12);
		assertEquals("discord_16.png", SupportLinks.DISCORD_16);
		assertEquals("x_16.png", SupportLinks.X_16);
		assertEquals("github_16.png", SupportLinks.GITHUB_16);
		assertEquals(8, SupportLinks.PAIR_GAP);
		assertEquals(6, SupportLinks.LINK_GAP);
		assertEquals("the settings icon's grey 165 as an alpha", 165f / 255f, SupportLinks.MARK_REST_ALPHA, 0f);
		assertEquals(new Color(135, 135, 135), SupportLinks.HEADER_GREY);
		assertEquals(13, SupportLinks.HEADER_TITLE_SIZE);
		assertEquals("2h", SupportLinks.FAMILY_MARK);
		assertEquals(12, SupportLinks.MENU_FONT_SIZE);
	}

	// ---------------------------------------------------------------- helpers

	private static BufferedImage imageOf(final JLabel label)
	{
		return (BufferedImage) ((ImageIcon) label.getIcon()).getImage();
	}

	/** The marks of a row, left to right: the labels, without the strips and the glue between them. */
	private static List<JLabel> marksOf(final Container row)
	{
		final List<JLabel> marks = new ArrayList<>();
		for (final Component child : row.getComponents())
		{
			if (child instanceof JLabel)
			{
				marks.add((JLabel) child);
			}
		}
		return marks;
	}

	/** The x of the label's first column of ink, past its left inset. */
	private static int inkLeft(final JLabel label)
	{
		return label.getX() + label.getInsets().left;
	}

	/** The x just past the label's last column of ink, before its right inset. */
	private static int inkRight(final JLabel label)
	{
		return label.getX() + label.getWidth() - label.getInsets().right;
	}

	/** The first fully opaque pixel of an image, as {x, y}. */
	private static int[] firstOpaque(final BufferedImage image)
	{
		for (int y = 0; y < image.getHeight(); y++)
		{
			for (int x = 0; x < image.getWidth(); x++)
			{
				if (image.getRGB(x, y) >>> 24 == 255)
				{
					return new int[]{x, y};
				}
			}
		}
		throw new AssertionError("no fully opaque pixel");
	}

	/** Whether two marks, as they stand at rest, are the same picture pixel for pixel. */
	private static boolean samePixels(final JLabel a, final JLabel b)
	{
		final BufferedImage x = imageOf(a);
		final BufferedImage y = imageOf(b);
		if (x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight())
		{
			return false;
		}
		for (int j = 0; j < x.getHeight(); j++)
		{
			for (int i = 0; i < x.getWidth(); i++)
			{
				if (x.getRGB(i, j) != y.getRGB(i, j))
				{
					return false;
				}
			}
		}
		return true;
	}

	/** A mouse event of {@code id} delivered to every listener on {@code c}. */
	private static void deliver(final JLabel c, final int id, final int button)
	{
		final MouseEvent e = new MouseEvent(c, id, System.currentTimeMillis(), 0, 1, 1, 1, false, button);
		for (final MouseListener l : c.getMouseListeners())
		{
			switch (id)
			{
				case MouseEvent.MOUSE_PRESSED:
					l.mousePressed(e);
					break;
				case MouseEvent.MOUSE_ENTERED:
					l.mouseEntered(e);
					break;
				case MouseEvent.MOUSE_EXITED:
					l.mouseExited(e);
					break;
				default:
					throw new IllegalArgumentException("event " + id);
			}
		}
	}
}
