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
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
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
 * the plugin's description, the last row of the settings menu, and (pinned in the wiring and diagnostics tests) the
 * client log and the report. The number itself is not pinned to a literal here on purpose: a release bumps it by
 * hand, and a test that named it would fail the release for the right reason and the wrong place. The export's
 * {@code publish.py} is what checks it against the Hub's {@code version=}.
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
		assertTrue("and this build is the one the contract names", d.description().endsWith("(v1.0.8)"));
	}

	@Test
	public void theMenusLastRowIsTheVersionInSmallGreyType() throws Exception
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
			})));
		final JPopupMenu menu = built.get().heroMenu();

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
		assertEquals("two lines and nothing else", 2, ((Container) last).getComponentCount());
		final Container title = (Container) ((Container) last).getComponent(0);
		final JLabel version = (JLabel) ((Container) last).getComponent(1);
		assertEquals("the mark, the rest of the name, and the glue that keeps them left", 3, title.getComponentCount());
		final JLabel mark = (JLabel) title.getComponent(0);
		final JLabel rest = (JLabel) title.getComponent(1);
		assertEquals("2h", mark.getText());
		assertEquals(BankPriceMovementPanel.VERSION_MARK_TEXT, mark.getText());
		assertEquals(" Bank Portfolio Tracker", rest.getText());
		assertEquals("the two read as the one name", "2h Bank Portfolio Tracker", mark.getText() + rest.getText());
		assertEquals(BankPriceMovementPanel.VERSION_NAME_TEXT, mark.getText() + rest.getText());
		assertEquals(ColorScheme.BRAND_ORANGE, mark.getForeground());
		assertEquals(Color.WHITE, rest.getForeground());
		assertEquals("Version " + Version.CURRENT, version.getText());
		assertEquals(BankPriceMovementPanel.VERSION_TEXT, version.getText());
		for (final JLabel part : new JLabel[]{mark, rest})
		{
			assertEquals("bold, one size up from the menu's 12", Widgets.sansBold(13), part.getFont());
			assertEquals(13, BankPriceMovementPanel.HEADER_TITLE_SIZE);
			assertNull("no hover", part.getToolTipText());
			assertEquals("and not clickable: no mouse listener on the line", 0, part.getMouseListeners().length);
		}
		assertEquals("the menu's own 12 px - 10 px was hard to read (the user, 2026-09-30)", Widgets.sans(12),
			version.getFont());
		assertEquals("a shade darker than the captions' grey, still legible (the user, 2026-09-30)",
			BankPriceMovementPanel.HEADER_GREY, version.getForeground());
		assertTrue("darker than the captions", BankPriceMovementPanel.HEADER_GREY.getRed() < ColorScheme.LIGHT_GRAY_COLOR.getRed());
		assertTrue("but well clear of the menu's ground", BankPriceMovementPanel.HEADER_GREY.getRed() >= 120);
		assertEquals("left-aligned under the title", Component.LEFT_ALIGNMENT, version.getAlignmentX(), 0f);
		assertEquals("the title row at the left too", Component.LEFT_ALIGNMENT, title.getAlignmentX(), 0f);
		assertEquals("the text at the left of its line", SwingConstants.LEFT, version.getHorizontalAlignment());
		assertNull("no hover", version.getToolTipText());
		assertEquals("and not clickable: no mouse listener on the line", 0, version.getMouseListeners().length);
		assertNull("no hover on the row either", ((JPanel) last).getToolTipText());
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
