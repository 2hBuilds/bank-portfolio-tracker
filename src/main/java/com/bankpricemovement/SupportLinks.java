package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;
import net.runelite.client.ui.FontManager;
import net.runelite.client.util.ImageUtil;

/**
 * Where to find the people behind a 2hBuilds plugin (1.0.9): the support pair in the top-right corner of a card - the
 * Discord mark beside the settings icon - and the settings menu's header, the plugin's name and build over a row of
 * four brand marks (Discord, X, the 2hBuilds GitHub profile and this plugin's own GitHub page). The one place any of it
 * is drawn, and the one place its links are written.
 *
 * <p><b>Written to be copied.</b> This file is the same in every 2hBuilds plugin, with nothing changed but its
 * {@code package} line: it names no class of the plugin it sits in - no panel, no widget helper, no version class,
 * no config - only RuneLite's own ({@link ColorScheme}, {@link FontManager}, {@link ImageUtil}) and the JDK's. What a
 * plugin has to say about itself comes in through the constructor: the name it is shown under, its build, its own
 * GitHub page, what opens a link ({@link net.runelite.client.util.LinkBrowser#browse} in the client, a recorder in
 * a test) and what takes the settings menu down after a link has been pressed. The four mark files are the
 * brands' own, white on transparent and never redrawn, in the resource folder of the copy's package
 * ({@link #markIcon} loads them beside this class).
 *
 * <p>Every hover here is ONE word (or a few) and is always on: an icon with no text beside it has nothing else on
 * the screen to say what it is, so it names itself whatever a plugin's "Show hover text" switch says. The hovers are
 * set on the labels directly and are never offered to a switch.
 */
final class SupportLinks
{
	/** Where the Discord mark goes: the 2hBuilds Discord server, opened through the browser hook. */
	static final String DISCORD_URL = "https://discord.gg/nsam4CfWzf";
	/** Where the X mark goes: the 2hBuilds account. */
	static final String X_URL = "https://x.com/2hBuilds";
	/** Where the first GitHub mark goes: the 2hBuilds profile. */
	static final String GITHUB_PROFILE_URL = "https://github.com/2hBuilds";

	/** The settings icon's hover: one word, because the menu under it says the rest. */
	static final String SETTINGS_TIP = "Settings";
	/** The Discord mark's hover: one word. */
	static final String DISCORD_TIP = "Discord";
	/** The X mark's hover in the menu's header: one word. */
	static final String X_TIP = "X";
	/** The first GitHub mark's hover in the menu's header: the 2hBuilds profile. */
	static final String GITHUB_PROFILE_TIP = "2hBuilds on GitHub";
	/** The second GitHub mark's hover in the menu's header: the plugin's own page. */
	static final String GITHUB_TIP = "This plugin on GitHub";

	/** The brand marks' files, in the resource folder of this class's package; see {@link #markIcon}. */
	static final String DISCORD_12 = "discord_12.png";
	static final String DISCORD_16 = "discord_16.png";
	static final String X_16 = "x_16.png";
	static final String GITHUB_16 = "github_16.png";

	/** The gap between the Discord mark and the settings icon on a card, in px. */
	static final int PAIR_GAP = 8;
	/** The gap between two marks in the menu's header row, in px; the marks themselves are 16 px files. */
	static final int LINK_GAP = 6;
	/**
	 * The alpha a brand's mark rests at: 165 of 255, so a white mark reads as the grey 165 the settings icon and the
	 * captions are drawn in ({@link ColorScheme#LIGHT_GRAY_COLOR}).
	 */
	static final float MARK_REST_ALPHA = 165f / 255f;
	/**
	 * The header's version line's grey: a shade darker than the captions' {@link ColorScheme#LIGHT_GRAY_COLOR} (165),
	 * still about 4.6 : 1 against the menu's ground.
	 */
	static final Color HEADER_GREY = new Color(135, 135, 135);
	/** The header's title face: bold, one size up from the menu's 12 px. */
	static final int HEADER_TITLE_SIZE = 13;
	/**
	 * The part of a display name drawn in the brand orange, when the name opens with it and a space: the 2hBuilds mark,
	 * as on the sidebar icon. A name without it is drawn whole in white.
	 */
	static final String FAMILY_MARK = "2h";
	/** The menu's own type size, the version line's. */
	static final int MENU_FONT_SIZE = 12;

	/**
	 * The sidebar's 6 px: the header's left and right padding - the column the captions under it start at - and the
	 * hit area an icon buys on the side the pointer arrives from. {@link #iconBorder} is where the second is written.
	 */
	private static final int INSET = 6;

	/** The marks already loaded, by file name: each PNG is read once (see {@link #markIcon}). */
	private static final Map<String, BufferedImage> MARKS = new ConcurrentHashMap<>();

	private final String displayName;
	private final String version;
	private final String pluginGitHubUrl;
	private final Consumer<String> browser;
	private final Runnable closeMenu;

	/**
	 * @param displayName     the name the plugin is shown under, e.g. "2h Bank Portfolio Tracker"
	 * @param version         the plugin's build, e.g. "1.0.9"; printed as "Version 1.0.9"
	 * @param pluginGitHubUrl the plugin's own repository page, what the fourth mark opens
	 * @param browser         what a pressed mark hands its URL to: RuneLite's {@code LinkBrowser::browse} in the client
	 * @param closeMenu       what takes the settings menu down once a header mark has been pressed, as OK does
	 */
	SupportLinks(String displayName, String version, String pluginGitHubUrl, Consumer<String> browser,
		Runnable closeMenu)
	{
		this.displayName = Objects.requireNonNull(displayName, "displayName");
		this.version = Objects.requireNonNull(version, "version");
		this.pluginGitHubUrl = Objects.requireNonNull(pluginGitHubUrl, "pluginGitHubUrl");
		this.browser = Objects.requireNonNull(browser, "browser");
		this.closeMenu = Objects.requireNonNull(closeMenu, "closeMenu");
	}

	/**
	 * The support pair, the top-right corner of a card: the Discord mark, {@link #PAIR_GAP} px of clear ground, then
	 * {@code settingsIcon} - the settings icon is the right-most thing. The gap is measured between the two ink boxes,
	 * so the strip between the labels is {@link #PAIR_GAP} less the 6 px inset the settings icon carries on its left
	 * ({@link #iconBorder}).
	 *
	 * <p>The pair is as tall as the icons with their 2 px insets, 16 px, and exactly as wide as the two labels and the
	 * strip between them, so a bar that holds it at its east end gives it no more room than that.
	 *
	 * @param settingsIcon the plugin's settings icon, a label carrying {@link #iconBorder}
	 */
	JPanel supportPair(JLabel settingsIcon)
	{
		final JLabel discord = discordMark();
		final JPanel pair = new JPanel();
		pair.setLayout(new BoxLayout(pair, BoxLayout.X_AXIS));
		pair.setOpaque(false);
		pair.add(discord);
		pair.add(Box.createHorizontalStrut(PAIR_GAP - INSET));
		pair.add(settingsIcon);
		final int width = discord.getPreferredSize().width + PAIR_GAP - INSET + settingsIcon.getPreferredSize().width;
		final int height = Math.max(discord.getPreferredSize().height, settingsIcon.getPreferredSize().height);
		pair.setPreferredSize(new Dimension(width, height));
		return pair;
	}

	/**
	 * The Discord mark beside the settings icon: 12 px, with the settings icon's own insets ({@link #iconBorder}), so
	 * the two read as a pair and each has the same 6 px of hit area on the side the pointer arrives from.
	 */
	JLabel discordMark()
	{
		final JLabel mark = brandMark(DISCORD_12, DISCORD_TIP, DISCORD_URL);
		mark.setBorder(iconBorder());
		return mark;
	}

	/**
	 * The settings menu's header: the title line - the family mark in the brand orange and the rest of the display name
	 * in white, both bold at {@link #HEADER_TITLE_SIZE} px, two labels side by side because one label has one colour;
	 * a name that does not open with {@link #FAMILY_MARK} and a space is one white label - over "Version" and the
	 * build in {@link #HEADER_GREY} at the menu's own {@link #MENU_FONT_SIZE} px, over the links row: four 16 px brand
	 * marks, {@link #LINK_GAP} px apart, the Discord mark, the X mark, the GitHub mark for the 2hBuilds profile and the
	 * same GitHub mark for this plugin's own page. Every line at the header's left edge, like the captions below it,
	 * with no hover but the marks' and no click but theirs.
	 *
	 * <p>The two GitHub marks are identical on purpose; only their hovers tell them apart. A press on a mark hands
	 * its URL to the browser and then takes the menu down, the way OK does: a reader who has clicked a link has
	 * finished with the menu. The header is ONE panel, so a menu that holds it counts it as one component.
	 */
	JPanel header()
	{
		final JPanel row = new JPanel(new DynamicGridLayout(0, 1, 0, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setOpaque(false);
		row.setBorder(new EmptyBorder(2, INSET, 2, INSET));
		row.add(titleRow());
		final JLabel versionLine = label("Version " + version, sans(MENU_FONT_SIZE), HEADER_GREY);
		versionLine.setHorizontalAlignment(SwingConstants.LEFT);
		versionLine.setAlignmentX(Component.LEFT_ALIGNMENT);
		row.add(versionLine);
		row.add(linksRow());
		return row;
	}

	/** The header's title line, see {@link #header}. */
	private JPanel titleRow()
	{
		final JPanel title = new JPanel();
		title.setLayout(new BoxLayout(title, BoxLayout.X_AXIS));
		title.setOpaque(false);
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		if (displayName.startsWith(FAMILY_MARK + " "))
		{
			title.add(label(FAMILY_MARK, sansBold(HEADER_TITLE_SIZE), ColorScheme.BRAND_ORANGE));
			title.add(label(displayName.substring(FAMILY_MARK.length()), sansBold(HEADER_TITLE_SIZE), Color.WHITE));
		}
		else
		{
			title.add(label(displayName, sansBold(HEADER_TITLE_SIZE), Color.WHITE));
		}
		title.add(Box.createHorizontalGlue());
		return title;
	}

	/** The header's links row, see {@link #header}: 2 px of air over it and the marks from the left edge. */
	private JPanel linksRow()
	{
		final JPanel links = new JPanel();
		links.setLayout(new BoxLayout(links, BoxLayout.X_AXIS));
		links.setOpaque(false);
		links.setAlignmentX(Component.LEFT_ALIGNMENT);
		links.setBorder(new EmptyBorder(2, 0, 0, 0));
		addLink(links, DISCORD_16, DISCORD_TIP, DISCORD_URL, false);
		addLink(links, X_16, X_TIP, X_URL, true);
		addLink(links, GITHUB_16, GITHUB_PROFILE_TIP, GITHUB_PROFILE_URL, true);
		addLink(links, GITHUB_16, GITHUB_TIP, pluginGitHubUrl, true);
		links.add(Box.createHorizontalGlue());
		return links;
	}

	/** One mark of {@link #linksRow}, after {@link #LINK_GAP} px of clear ground unless it is the first. */
	private void addLink(JPanel links, String resource, String name, String url, boolean gapBefore)
	{
		final JLabel mark = namedIconButton(markIcon(resource, MARK_REST_ALPHA), markIcon(resource, 1f), name, () ->
		{
			browser.accept(url);
			closeMenu.run();
		});
		if (gapBefore)
		{
			links.add(Box.createHorizontalStrut(LINK_GAP));
		}
		links.add(mark);
	}

	/**
	 * One brand mark: {@code resource} at the settings icon's grey by alpha at rest ({@link #MARK_REST_ALPHA}) and at
	 * full white under the mouse, {@code name} for an always-on hover, and a LEFT press hands {@code url} to the
	 * browser.
	 */
	private JLabel brandMark(String resource, String name, String url)
	{
		return namedIconButton(markIcon(resource, MARK_REST_ALPHA), markIcon(resource, 1f), name,
			() -> browser.accept(url));
	}

	/**
	 * The border a card's settings icon and the Discord mark beside it share: 2 px above and below and, on the
	 * left, the 6 px of hit area on the side the pointer arrives from - a 12 px glyph is a 12 px target otherwise,
	 * and a settings button is not a decoration.
	 */
	static Border iconBorder()
	{
		return new EmptyBorder(2, INSET, 2, 0);
	}

	/**
	 * An icon-only control that names itself: {@link #pressable}'s look and press, with a ONE-WORD hover that is
	 * always on. The word is set on the label directly and is never offered to a "Show hover text" switch, so the
	 * exemption is in how the label is made, not in a special case where a switch is applied.
	 */
	static JLabel namedIconButton(ImageIcon rest, ImageIcon hot, String name, Runnable onClick)
	{
		final JLabel label = pressable(rest, hot, onClick);
		label.setToolTipText(name);
		return label;
	}

	/**
	 * The look and the press of an icon button, with no hover yet: {@code rest} at rest, {@code hot} while the mouse is
	 * over it, a hand cursor, and a LEFT press runs {@code onClick} - a right-button press is a menu gesture
	 * everywhere in a sidebar, never a press, and so is a ctrl-click of the left button on macOS.
	 */
	static JLabel pressable(ImageIcon rest, ImageIcon hot, Runnable onClick)
	{
		final JLabel label = new JLabel(rest);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				if (SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger())
				{
					onClick.run();
				}
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setIcon(hot);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setIcon(rest);
			}
		});
		return label;
	}

	/**
	 * A brand's own mark, loaded from the classpath and never redrawn: the Discord symbol, the X logo and GitHub's
	 * Invertocat are white on transparent PNGs in this package's resource folder, resized and nothing else, because
	 * the brands allow white and none allows a mark drawn by somebody else. Loaded with RuneLite's
	 * {@link ImageUtil#loadImageResource} - the ordinary plugin way; a classpath resource is not file I/O - and each
	 * file ONCE, however many icons are made from it.
	 *
	 * <p>The look is chosen by {@code alpha}, never by recolouring: at 1 the mark is the file as it is (white), and
	 * below 1 {@link ImageUtil#alphaOffset(java.awt.Image, float)} scales only the alpha, so a white mark at
	 * {@link #MARK_REST_ALPHA} reads on the card as the settings icon's grey.
	 *
	 * @param resource a file name in this class's package's resource folder, e.g. {@code "discord_12.png"}
	 * @param alpha    1 for the mark at full white, less for it fainter
	 */
	static ImageIcon markIcon(String resource, float alpha)
	{
		final BufferedImage mark = MARKS.computeIfAbsent(resource,
			r -> ImageUtil.loadImageResource(SupportLinks.class, r));
		return new ImageIcon(alpha >= 1f ? mark : ImageUtil.alphaOffset(mark, alpha));
	}

	/** The Swing sans at {@code size} px: RuneLite's default font, derived, so the shared constant is never mutated. */
	private static Font sans(int size)
	{
		return FontManager.getDefaultFont().deriveFont((float) size);
	}

	/** The bold Swing sans at {@code size} px. */
	private static Font sansBold(int size)
	{
		return FontManager.getDefaultBoldFont().deriveFont((float) size);
	}

	/** A label with its font and colour set explicitly, never left to the look and feel. */
	private static JLabel label(String text, Font font, Color colour)
	{
		final JLabel label = new JLabel(text);
		label.setFont(font);
		label.setForeground(colour);
		return label;
	}
}
