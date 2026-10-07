package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import java.awt.Component;
import java.awt.Color;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/**
 * The version of the build (1.0.8): one constant, {@link Version#CURRENT}, and the three places a player meets it -
 * the plugin's description, the header of the settings menu, and (pinned in the wiring test) the client log. The
 * number itself is not pinned to a literal here on purpose: a release bumps it by hand, and a test that named it would
 * fail the release for the right reason and the wrong place. The export's {@code publish.py} is what checks it
 * against the Hub's {@code version=}.
 */
public class VersionTest
{
	@Test
	public void theVersionIsThreeNumbersSeparatedByDots()
	{
		assertTrue(Version.CURRENT, Version.CURRENT.matches("\\d+\\.\\d+\\.\\d+"));
	}

	@Test
	public void theDescriptorsDescriptionEndsWithTheVersionInBrackets()
	{
		final PluginDescriptor d = BankPriceMovementPlugin.class.getAnnotation(PluginDescriptor.class);
		assertNotNull(d);
		assertTrue(d.description(), d.description().endsWith(" (v" + Version.CURRENT + ")"));
		assertTrue("and this build is the one the contract names", d.description().endsWith("(v1.1.1)"));
	}

	@Test
	public void theMenusLastRowIsTheVersionInSmallGreyType() throws Exception
	{
		final JPopupMenu menu = panelWith(url -> { }).heroMenu();

		// The user's third look (2026-09-30): the lines are the menu's HEADER - first, over a rule of their own - so
		// the OK row stays the menu's last thing.
		final Component last = menu.getComponent(0);

		assertTrue("a plain row, not an entry to click: " + last, last instanceof JPanel);
		assertTrue("a rule under it, so it reads as the menu's header (the user, 2026-09-30)",
			menu.getComponent(1) instanceof JSeparator);
		assertFalse("and nothing of it at the bottom: the OK row is last", menu.getComponent(menu.getComponentCount() - 1) instanceof JSeparator);
		final JLabel label = firstLabel((Container) last);
		assertNotNull(label);
		// The user's third look (2026-09-30): the name on its own line, "Version 1.0.8" on a second under it - and
		// their pick from six drawn choices: "2h" in the brand orange, the rest of the name white, both bold 13, the
		// version in the darker grey at 12.
		assertEquals("two lines and, since 1.0.9, the links row under them", 3, ((Container) last).getComponentCount());
		final Container title = (Container) ((Container) last).getComponent(0);
		final JLabel version = (JLabel) ((Container) last).getComponent(1);
		assertTrue("the third child is the row of link marks", ((Container) last).getComponent(2) instanceof JPanel);
		assertEquals("the mark, the rest of the name, and the glue that keeps them left", 3, title.getComponentCount());
		final JLabel mark = (JLabel) title.getComponent(0);
		final JLabel rest = (JLabel) title.getComponent(1);
		assertEquals("2h", mark.getText());
		assertEquals(SupportLinks.FAMILY_MARK, mark.getText());
		assertEquals(" Bank Portfolio Tracker", rest.getText());
		assertEquals("the two read as the one name", "2h Bank Portfolio Tracker", mark.getText() + rest.getText());
		assertEquals(BankPriceMovementPanel.VERSION_NAME_TEXT, mark.getText() + rest.getText());
		assertEquals(ColorScheme.BRAND_ORANGE, mark.getForeground());
		assertEquals(Color.WHITE, rest.getForeground());
		assertEquals("Version " + Version.CURRENT, version.getText());
		for (final JLabel part : new JLabel[]{mark, rest})
		{
			assertEquals("bold, one size up from the menu's 12", Widgets.sansBold(13), part.getFont());
			assertEquals(13, SupportLinks.HEADER_TITLE_SIZE);
			assertNull("no hover", part.getToolTipText());
			assertEquals("and not clickable: no mouse listener on the line", 0, part.getMouseListeners().length);
		}
		assertEquals("the menu's own 12 px - 10 px was hard to read (the user, 2026-09-30)", Widgets.sans(12),
			version.getFont());
		assertEquals("a shade darker than the captions' grey, still legible (the user, 2026-09-30)",
			SupportLinks.HEADER_GREY, version.getForeground());
		assertTrue("darker than the captions", SupportLinks.HEADER_GREY.getRed() < ColorScheme.LIGHT_GRAY_COLOR.getRed());
		assertTrue("but well clear of the menu's ground", SupportLinks.HEADER_GREY.getRed() >= 120);
		assertEquals("left-aligned under the title", Component.LEFT_ALIGNMENT, version.getAlignmentX(), 0f);
		assertEquals("the title row at the left too", Component.LEFT_ALIGNMENT, title.getAlignmentX(), 0f);
		assertEquals("the text at the left of its line", SwingConstants.LEFT, version.getHorizontalAlignment());
		assertNull("no hover", version.getToolTipText());
		assertEquals("and not clickable: no mouse listener on the line", 0, version.getMouseListeners().length);
		assertNull("no hover on the row either", ((JPanel) last).getToolTipText());
	}

	/** The panel on the default settings - hover text OFF - with {@code browser} as what the link marks press into. */
	private static BankPriceMovementPanel panelWith(final Consumer<String> browser) throws Exception
	{
		final AtomicReference<BankPriceMovementPanel> built = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> built.set(new BankPriceMovementPanel(mock(ItemManager.class),
			mock(PriceService.class), new BankPriceMovementPanel.Prefs()
			{
				@Nullable
				@Override
				public RowFilter load()
				{
					return null;
				}

				@Override
				public void save(final RowFilter filter)
				{
				}
			}, browser)));
		return built.get();
	}

	/** The marks of the links row, left to right: the labels, without the strips and the glue between them. */
	private static List<JLabel> linkMarks(final Container links)
	{
		final List<JLabel> marks = new ArrayList<>();
		for (final Component child : links.getComponents())
		{
			if (child instanceof JLabel)
			{
				marks.add((JLabel) child);
			}
		}
		return marks;
	}

	/** The links row: the header's third child. */
	private static Container linksRowOf(final BankPriceMovementPanel panel)
	{
		return (Container) ((Container) panel.heroMenu().getComponent(0)).getComponent(2);
	}

	/**
	 * 1.0.9: the header's third child is a row of FOUR 16 px marks, 6 px apart from the left edge, in this order -
	 * Discord, X, GitHub for the 2hBuilds profile and GitHub again for this plugin's page (the two GitHub marks are the
	 * same picture on purpose, told apart by their hovers) - with the hovers "Discord", "X", "2hBuilds on GitHub" and
	 * "This plugin on GitHub", ON with the hover switch off (which is what this panel is built with). The row lives
	 * inside the header, so the menu's count is what the menu has: 27 since 1.1.0 part J's rule and Slot 1 rows (24 with
	 * part H's colour presets, 20 with part C's
	 * Single chart colour row, 19
	 * with part B's two colour rows, 17 before them; 21 before part A took Refresh and the start-tab dots out, 22 before
	 * 1.0.9 part 3's Grand Exchange item, 23 with the Troubleshoot item and its rule) and nothing else in it moved.
	 */
	@Test
	public void theHeadersThirdChildIsARowOfFourLinkMarks() throws Exception
	{
		final BankPriceMovementPanel panel = panelWith(url -> { });
		SwingUtilities.invokeAndWait(() ->
		{
			assertFalse("the switch is off", panel.options().showHoverText());
			assertEquals("the links row lives inside the header: the menu's count is what the menu has", 27,
				panel.heroMenu().getComponentCount());
			final Container links = linksRowOf(panel);
			final List<JLabel> marks = linkMarks(links);
			assertEquals(4, marks.size());
			assertEquals(Arrays.asList("Discord", "X", "2hBuilds on GitHub", "This plugin on GitHub"),
				Arrays.asList(marks.get(0).getToolTipText(), marks.get(1).getToolTipText(),
					marks.get(2).getToolTipText(), marks.get(3).getToolTipText()));
			assertEquals(SupportLinks.DISCORD_TIP, marks.get(0).getToolTipText());
			assertEquals(SupportLinks.X_TIP, marks.get(1).getToolTipText());
			assertEquals(SupportLinks.GITHUB_PROFILE_TIP, marks.get(2).getToolTipText());
			assertEquals(SupportLinks.GITHUB_TIP, marks.get(3).getToolTipText());
			for (final JLabel mark : marks)
			{
				assertEquals("16 px", 16, mark.getIcon().getIconWidth());
				assertEquals(16, mark.getIcon().getIconHeight());
				assertEquals("and no border: the label is the mark", mark.getIcon().getIconWidth(),
					mark.getPreferredSize().width);
			}
			assertTrue("the two GitHub marks are one picture", samePixels(marks.get(2), marks.get(3)));
			assertFalse("...and the Discord and X marks are not", samePixels(marks.get(0), marks.get(1)));

			// Left-aligned like the lines above it, 2 px under them, the glue last so nothing is stretched.
			assertEquals(Component.LEFT_ALIGNMENT, links.getAlignmentX(), 0f);
			assertEquals("2 px above", 2, ((JPanel) links).getInsets().top);
			assertTrue("the glue is last", links.getComponent(links.getComponentCount() - 1) instanceof Box.Filler);
			links.setSize(200, links.getPreferredSize().height);
			links.doLayout();
			assertEquals("the first mark is at the left edge", 0, marks.get(0).getX());
			for (int i = 1; i < marks.size(); i++)
			{
				assertEquals("6 px between marks " + (i - 1) + " and " + i, 6,
					marks.get(i).getX() - (marks.get(i - 1).getX() + marks.get(i - 1).getWidth()));
			}
			assertEquals(6, SupportLinks.LINK_GAP);
		});
	}

	/**
	 * 1.0.9: each mark's press browses its own URL - the invite, the X account, the 2hBuilds profile, this plugin's
	 * page - and then takes the menu down, as OK does; the resting mark is the white one at the settings icon's grey
	 * by alpha and the mark under the mouse is full white. A right-button press is nothing. Where there is a display
	 * the menu is really up when the mark is pressed, so "takes the menu down" is asked of a menu that is showing.
	 */
	@Test
	public void eachLinkMarkBrowsesItsOwnAddressAndClosesTheMenu() throws Exception
	{
		assertEquals("https://discord.gg/nsam4CfWzf", SupportLinks.DISCORD_URL);
		assertEquals("https://x.com/2hBuilds", SupportLinks.X_URL);
		assertEquals("https://github.com/2hBuilds", SupportLinks.GITHUB_PROFILE_URL);
		assertEquals("https://github.com/2hBuilds/bank-portfolio-tracker", BankPriceMovementPanel.GITHUB_URL);
		final List<String> visited = new ArrayList<>();
		final BankPriceMovementPanel panel = panelWith(visited::add);
		final List<String> wanted = Arrays.asList(SupportLinks.DISCORD_URL, SupportLinks.X_URL,
			SupportLinks.GITHUB_PROFILE_URL, BankPriceMovementPanel.GITHUB_URL);
		SwingUtilities.invokeAndWait(() ->
		{
			final List<JLabel> marks = linkMarks(linksRowOf(panel));
			for (int i = 0; i < marks.size(); i++)
			{
				final JLabel mark = marks.get(i);
				visited.clear();
				deliver(mark, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3);
				assertTrue("a right-button press is no press: " + visited, visited.isEmpty());
				deliver(mark, MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);
				assertEquals("mark " + i + " browses its own address, once", Collections.singletonList(wanted.get(i)),
					visited);
				assertFalse("and the menu is down", panel.heroMenu().isVisible());

				final BufferedImage rest = (BufferedImage) ((ImageIcon) mark.getIcon()).getImage();
				deliver(mark, MouseEvent.MOUSE_ENTERED, MouseEvent.NOBUTTON);
				final BufferedImage hot = (BufferedImage) ((ImageIcon) mark.getIcon()).getImage();
				deliver(mark, MouseEvent.MOUSE_EXITED, MouseEvent.NOBUTTON);
				assertSame("the mouse leaving brings the resting mark back", rest,
					((ImageIcon) mark.getIcon()).getImage());
				int opaque = -1;
				for (int y = 0; y < hot.getHeight() && opaque < 0; y++)
				{
					for (int x = 0; x < hot.getWidth() && opaque < 0; x++)
					{
						if (hot.getRGB(x, y) >>> 24 == 255)
						{
							opaque = y * hot.getWidth() + x;
						}
					}
				}
				assertTrue("mark " + i + " has a fully opaque pixel", opaque >= 0);
				final int px = opaque % hot.getWidth();
				final int py = opaque / hot.getWidth();
				assertEquals("under the mouse: white at 255", 0xFFFFFFFF, hot.getRGB(px, py));
				assertEquals("at rest: white at alpha about 165", 165, rest.getRGB(px, py) >>> 24, 1);
				assertEquals("...still white, not grey", 0xFFFFFF, rest.getRGB(px, py) & 0xFFFFFF);
			}
		});
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final JFrame frame = new JFrame();
		try
		{
			SwingUtilities.invokeAndWait(() ->
			{
				frame.setFocusableWindowState(false);
				frame.add(panel);
				frame.setSize(400, 900);
				frame.setLocation(-2000, -2000);
				frame.setVisible(true);
			});
			for (int i = 0; i < 4; i++)
			{
				final int which = i;
				visited.clear();
				SwingUtilities.invokeAndWait(() ->
				{
					final Component anchor = panel.shotComponents().get(1);
					panel.heroMenu().show(anchor, 0, 0);
					assertTrue("the menu is up", panel.heroMenu().isVisible());
					deliver(linkMarks(linksRowOf(panel)).get(which), MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1);
					assertEquals(Collections.singletonList(wanted.get(which)), visited);
					assertFalse("pressing a mark takes the menu down", panel.heroMenu().isVisible());
				});
			}
		}
		finally
		{
			SwingUtilities.invokeAndWait(() ->
			{
				panel.heroMenu().setVisible(false);
				frame.dispose();
			});
		}
	}

	/** Whether two marks, as they stand at rest, are the same picture pixel for pixel. */
	private static boolean samePixels(final JLabel a, final JLabel b)
	{
		final BufferedImage x = (BufferedImage) ((ImageIcon) a.getIcon()).getImage();
		final BufferedImage y = (BufferedImage) ((ImageIcon) b.getIcon()).getImage();
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
				default:
					l.mouseExited(e);
			}
		}
	}

	private static JLabel firstLabel(final Container parent)
	{
		for (final Component child : parent.getComponents())
		{
			if (child instanceof JLabel)
			{
				return (JLabel) child;
			}
		}
		return null;
	}
}
