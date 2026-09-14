package com.bankpricemovement;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Headless proof of the Ticker sidebar for the user (addendum N, line N5; Ticker only since addendum O, line
 * O1): paints the real {@link BankPriceMovementPanel} at {@value #WIDTH} x {@value #HEIGHT} over a recorded
 * 12-row fixture and writes four pictures, which the checker copies to {@code docs/handoff/lab/} and READS -
 * {@code build/look-ticker.png} with every hero figure shown, {@code build/look-ticker-hidden.png} with all
 * three hidden (O3: the card keeps its caption, chips and footnote and nothing else), and
 * {@code build/look-ticker-options.png} with the view switches of addendum Q on (Q7): two untradeable stacks
 * at the foot of the list wearing their "alch" tag, one valued at its tradeable PARTS beside them and moving
 * like any other row (addendum R, line R5), and every row printing what the whole STACK is worth and what it
 * moved - and {@code build/look-ticker-live.png} with the live switch of addendum T on (T8): the card's last
 * line reading "Live prices on - thin items daily" over three rows priced off the wiki's traded series and one
 * the liquidity checks left on the guide price, on the live calendar of addendum U ({@link #LIVE_DAY}).
 *
 * <p><b>The first three are drawn with the live switch OFF</b> ({@link #GUIDE_ONLY}), whatever
 * {@link ViewOptions#DEFAULT} says today: they are the pictures addenda Q, R and S are compared against BYTE for
 * byte, and addendum T's own promise is that the switch off is the sidebar those pictures show.
 *
 * <p><b>All four open on the price fold</b> since addendum AA (line AA3;
 * {@code docs/bank-price-movement-addendum-AA-2026-09-13.md}): {@link #FOLD_OPEN} is the shipped default, so the
 * chip strip and the Min / Max fields stand under the control row in every picture and everything below them
 * moves down by the fold's height. That is the one difference from the addendum W pictures, and the checker
 * measures it as one. {@link #build(HeroVisibility, ViewOptions, boolean)} still draws the CLOSED fold when it is
 * asked to, so the pictures taken before AA stay reproducible from this class.
 *
 * <p>Nothing here needs a display or the client: the panel is built on the EDT over a mocked
 * {@link ItemManager} (each sprite is a drawn 36 x 32 placeholder in a colour of the item's own) and a mocked
 * {@link PriceService} whose {@code currentRows} / {@code currentStatus} seed the panel exactly as the bridge
 * does, then sized, laid out top-down ({@link #layoutTree} - {@code validate()} needs a peer, {@code doLayout()}
 * does not) and printed into a {@link BufferedImage}. The fonts are the plain Swing sans through
 * {@code Widgets.sans} / {@code sansBold}, so the headless paint needs no client resources.
 *
 * <p>The fixture is the checker's 2026-09-09 probe of the user's bank (addendum M's live step 2: 275,385,539 gp
 * over 529 stacks, 1d -950,972 = -0.3%, 7d -1.5%, 30d -2.2%, 90d -9.9%, 180d -16.8%) with twelve rows in
 * "Percent change" order, biggest first (what the button called "Biggest gainers" before addendum W), that
 * between them cover every row state the specs name: a rise, a fall, a zero move,
 * L2's "-0.0%" red, a stack of 28,000, a name that truncates and a row with no baseline.
 *
 * <p>Run it from Gradle's test task ({@code BankPriceMovementPanelTest#lookRendererPaintsTheTickerAndTheHiddenCardIntoBuild})
 * or by hand: {@code java -cp <test runtime classpath> com.bankpricemovement.LookRenderer [dir]}.
 *
 * <p><b>Reproducing a picture BYTE for byte needs one JVM flag:</b> {@code -Dawt.useSystemAAFontSettings=off}.
 * The {@code KEY_TEXT_ANTIALIASING} hint {@link #paint} sets is not the one Swing draws text with - a
 * {@code JLabel} takes its antialiasing from the AATextInfo the look and feel read out of the DESKTOP's font
 * settings - so on a Windows box with ClearType on, every glyph comes out with coloured subpixel fringes, and on
 * one with it off the same glyph is grey. The picture is identical to the eye either way and different in about
 * 18,000 of its 202,500 pixels. The acceptance shots in {@code docs/handoff/lab/} were all taken with the flag
 * (measured 2026-09-11: with it, this class reproduces {@code ticker-2026-09-11-Q.png} and
 * {@code ticker-hidden-2026-09-11-Q.png} to the byte; without it, neither matches while the picture is the
 * same), so a comparison against them has to be taken the same way.
 */
public final class LookRenderer
{
	/** The sidebar's width: {@link PluginPanel#PANEL_WIDTH}. */
	public static final int WIDTH = PluginPanel.PANEL_WIDTH;
	/** Tall enough for the header, twelve 48 px rows and the foot without a scroll bar (N5: 225 x 900). */
	public static final int HEIGHT = 900;
	/**
	 * The fold's own height at this width, measured (addendum AA, line AA3): the chip strip over the field row,
	 * with the fold's padding - and therefore the distance everything under the control row moved when the fold
	 * stopped starting folded. It is a measurement and is pinned as one, by
	 * {@code BankPriceMovementPanelTest#theOpenFoldShiftsThePictureDownByItsOwnHeightAndChangesNothingElse}: a
	 * fold that grew would fail that test rather than quietly squeezing the picture below it.
	 */
	public static final int FOLD_HEIGHT = 57;
	/**
	 * What the options picture was tall before addendum AA (R5): the header, the fifteen rows and the 7 px of
	 * ground the last of them ended on. It is kept as a constant, and {@link #height(ViewOptions, boolean)}
	 * answers it for a CLOSED fold, so the addendum W picture can still be drawn from this class - a "reproducible"
	 * that came back at another size would not be one.
	 */
	public static final int OPTIONS_HEIGHT_CLOSED = HEIGHT + MovementRowPanel.ROW_HEIGHT + 3;
	/**
	 * The options picture's height (R5, and {@link #FOLD_HEIGHT} taller again since AA3). {@link #HEIGHT} holds the
	 * header and exactly FOURTEEN rows - the twelve of the fixture and addendum Q's two untradeable stacks - so the
	 * fifteenth row addendum R adds would fall off the bottom and, worse, bring a scroll bar that narrows the list
	 * under the header and squeezes every 213 px row card. One row pitch taller (the 48 px card plus the 3 px
	 * gutter between cards) and the picture is the same sidebar with room for it.
	 *
	 * <p><b>The open fold costs the list the same thing</b> (AA3): the header grew by {@link #FOLD_HEIGHT} and the
	 * list under it lost exactly that, and this was the one picture with no room to give - it ended on 7 px of bare
	 * ground, where the other three end on 106 or more. Left alone it grew a scroll bar, which is the failure R5
	 * describes, so it is given back what the fold took. The other three pictures are NOT given it: they keep the
	 * 900 px they are compared at, and the fold simply eats 57 px of the ground under their last row.
	 *
	 * <p>{@link #HEIGHT} itself is untouched on purpose: the acceptance shots are compared BYTE FOR BYTE against
	 * the pictures the addendum before left behind, and a picture of another size cannot be.
	 */
	public static final int OPTIONS_HEIGHT = OPTIONS_HEIGHT_CLOSED + FOLD_HEIGHT;
	/** The picture with every hero figure shown (N5's name). */
	public static final String TICKER_FILE = "look-ticker.png";
	/** The picture with the total, the gp move and the percentage all hidden (O3). */
	public static final String HIDDEN_FILE = "look-ticker-hidden.png";
	/** The picture with untradeables listed and the rows printing holdings (Q7); every hero figure shown. */
	public static final String OPTIONS_FILE = "look-ticker-options.png";
	/**
	 * The picture with the live switch ON (addendum T, line T8;
	 * {@code docs/bank-price-movement-addendum-T-2026-09-12.md}): three actively traded rows on the wiki's traded
	 * series and one thin row still on the guide price, under a card whose last line reads "Live prices on - thin
	 * items daily".
	 */
	public static final String LIVE_FILE = "look-ticker-live.png";
	/**
	 * What the three pictures above are drawn with: the live switch OFF (T8).
	 *
	 * <p>{@link ViewOptions#DEFAULT} turns it ON - it is the plugin's default (T1) - and every figure and the
	 * card's last line change with it, so the three acceptance shots this class has always written would no longer
	 * be the pictures they are compared against. They are the guide-only sidebar, byte for byte what addenda Q, R
	 * and S left behind, and they say so by naming this constant rather than by taking the default of the day.
	 */
	public static final ViewOptions GUIDE_ONLY = ViewOptions.DEFAULT.withLivePrices(false);
	/** What {@link #OPTIONS_FILE} is drawn with: untradeables counted and holdings on the rows (Q7), live off (T8). */
	public static final ViewOptions OPTIONS = new ViewOptions(true, true, true, false);
	/** What {@link #LIVE_FILE} is drawn with: the twelve-row fixture with the live switch on (T8). */
	public static final ViewOptions LIVE = ViewOptions.DEFAULT.withLivePrices(true);
	/**
	 * Whether the four pictures are drawn with the price fold OPEN (addendum AA, line AA3): true, which is the
	 * state a fresh install's sidebar is in ({@code BankPriceMovementPanel.Prefs.loadFoldOpen} reads null as open)
	 * and therefore the state an acceptance shot has to show.
	 *
	 * <p>It is a constant and a parameter rather than a fact of this class so the CLOSED sidebar - every picture
	 * taken between addenda N and W - can still be produced from here: pass false to
	 * {@link #build(HeroVisibility, ViewOptions, boolean)} or {@link #render(HeroVisibility, ViewOptions, boolean)}
	 * and the old picture comes back, which is what makes the AA comparison a measurement rather than a memory.
	 */
	public static final boolean FOLD_OPEN = true;

	/** The 1 d baseline's day in the fixture (the probe's "1d vs 08 Sep"). */
	static final LocalDate THEN_DAY = LocalDate.of(2026, 9, 8);
	/** When the fixture's bank was captured: 2026-09-09 13:16:00 UTC (the probe's "as captured 09:16" EDT). */
	static final long BANK_AT_MILLIS = 1_788_959_760_000L;
	/** The fixture's whole-bank value, to the gp (addendum M live step 2). */
	static final long VALUE_NOW = 275_385_539L;
	static final int BANK_ITEMS = 529;
	/** The two untradeable stacks addendum Q's picture adds, at their High Alchemy values (Q5). */
	static final long GRACEFUL_HOOD_ALCH = 20_000L;
	static final long AVERNIC_DEFENDER_ALCH = 42_000L;
	/**
	 * The untradeable stack addendum R's picture adds beside them (R5): a Crystal body, which RuneLite maps onto
	 * three Crystal armour seeds ({@code ItemMapping.ITEM_CRYSTAL_BODY(PRIF_ARMOUR_SEED, true, 3L,
	 * CRYSTAL_CHESTPLATE)}) - the ids are that mapping's own, 23975 for the body and 23956 for the seed.
	 */
	static final int CRYSTAL_BODY_ID = 23975;
	static final int CRYSTAL_SEED_ID = 23956;
	static final String CRYSTAL_SEED_NAME = "Crystal armour seed";
	static final long CRYSTAL_SEEDS = 3L;
	/** What one seed is worth now (R5's figure), and what it was worth on the 1 d baseline's day. */
	static final long CRYSTAL_SEED_NOW = 5_564_922L;
	static final long CRYSTAL_SEED_THEN = 6_100_000L;
	/** The body's unit price: the three seeds at today's guide price, 16,694,766 gp (R5). */
	static final long CRYSTAL_BODY_NOW = CRYSTAL_SEEDS * CRYSTAL_SEED_NOW;
	/**
	 * The three live figures the fourth picture is drawn from (T8), each picked to leave its row where "Biggest
	 * gainers" had already put it: Dragon claws at the traded mid against that day's traded average (+3.6 % where
	 * the guide series read +2.7 %), the Abyssal whip live NOW against its GUIDE baseline because its 1 d bucket
	 * was too thin (T4's fallback), and a Twisted bow that clears the volume check on 137 units.
	 */
	static final long CLAWS_LIVE_NOW = 41_480_000L;
	static final long CLAWS_TRADED_THEN = 40_050_000L;
	static final long WHIP_LIVE_NOW = 1_533_000L;
	static final long WHIP_GUIDE_NOW = 1_520_000L;
	static final long WHIP_GUIDE_THEN = 1_500_000L;
	static final long BOW_LIVE_NOW = 1_628_500_000L;
	static final long BOW_TRADED_THEN = 1_692_000_000L;
	/** Why the fourth picture's Green hat stayed on the guide price: the first of T3's checks to refuse it. */
	static final String THIN_REASON = "12 traded yesterday";
	/** How many stacks the live picture's whole-bank sums counted at a traded mid ({@code PortfolioSummary.liveRows}). */
	static final int LIVE_STACKS = 3;
	/**
	 * The UTC date of the live snapshot the fourth picture is drawn from (addendum U, line U1): 2026-09-09, the
	 * calendar day {@link #BANK_AT_MILLIS} falls on in UTC, which is the day the fixture's whole sidebar is "now"
	 * for.
	 *
	 * <p>It is chosen so that {@code liveDay - N} is the fixture's own guide baseline day for every one of the five
	 * windows - {@link #THEN_DAY} 08 Sep for 1 d, 02 Sep for 7 d, and so on down to 13 Mar - which is the state a
	 * client is in on any day Jagex has already published: the live calendar and the guide calendar agree, the
	 * footnote reads "1d vs 08 Sep" whichever of the two it takes, and the card's window lines stay on one day each.
	 * The picture therefore differs from {@link #TICKER_FILE} in the card's last line and four rows' numbers and in
	 * nothing else, exactly as T8 promised, while still being drawn down the live path with a real live calendar
	 * behind it (U3). The day the two DISAGREE has no picture: it is a tooltip and a footnote, and both are pinned
	 * by name in {@code BankPriceMovementPanelTest}.
	 */
	static final LocalDate LIVE_DAY = LocalDate.of(2026, 9, 9);
	/** How many of the fixture's twelve stacks the live picture leaves on the guide price. */
	static final int LIVE_GUIDE_STACKS = 9;
	/** How many items the live picture's {@code /latest} snapshot names (T8's {@code latestItems}). */
	static final int LIVE_LATEST_ITEMS = 4_535;

	private LookRenderer()
	{
	}

	/** Writes all four pictures into {@code args[0]}, or {@code build/} when no directory is given. */
	public static void main(String[] args) throws Exception
	{
		final File dir = new File(args.length > 0 ? args[0] : "build");
		System.out.println(write(dir, TICKER_FILE, HeroVisibility.ALL).getAbsolutePath());
		System.out.println(write(dir, HIDDEN_FILE, HeroVisibility.NONE).getAbsolutePath());
		System.out.println(write(dir, OPTIONS_FILE, HeroVisibility.ALL, OPTIONS).getAbsolutePath());
		System.out.println(write(dir, LIVE_FILE, HeroVisibility.ALL, LIVE).getAbsolutePath());
	}

	/**
	 * Renders the sidebar with {@code shown} and writes it as {@code <dir>/<name>} (the directory is created if
	 * it is missing). The picture and its file name are the caller's to pair: a renderer that derived the name
	 * from the visibility could only ever write the two the acceptance shots use, and silently overwrote one of
	 * them for any other combination of the three switches.
	 */
	public static File write(File dir, String name, HeroVisibility shown) throws Exception
	{
		return write(dir, name, shown, GUIDE_ONLY);
	}

	/**
	 * {@link #write(File, String, HeroVisibility)} under a set of view switches (Q7). The switches are the
	 * caller's, like the visibility and the name, for the same reason: the renderer draws what it is asked for,
	 * and which combinations are worth a picture is the acceptance shot's decision and not this class's.
	 *
	 * @param options the view switches; null reads as {@link #GUIDE_ONLY} (T8)
	 */
	public static File write(File dir, String name, HeroVisibility shown, ViewOptions options) throws Exception
	{
		return write(dir, name, shown, options, FOLD_OPEN);
	}

	/**
	 * {@link #write(File, String, HeroVisibility, ViewOptions)} with the price fold open or closed (AA3): the way
	 * to put a picture from before addendum AA back on disk, at the size it was taken at
	 * ({@link #height(ViewOptions, boolean)}).
	 */
	public static File write(File dir, String name, HeroVisibility shown, ViewOptions options, boolean foldOpen)
		throws Exception
	{
		final BufferedImage image = render(shown, options, foldOpen);
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			throw new IOException("could not create " + dir);
		}
		final File out = new File(dir, name);
		if (!ImageIO.write(image, "png", out))
		{
			throw new IOException("no PNG writer for " + out);
		}
		return out;
	}

	/**
	 * The panel over the fixture, painted at {@link #WIDTH} x {@link #HEIGHT}; built and painted on the EDT. The
	 * guide-only sidebar ({@link #GUIDE_ONLY}, T8), which is what the two acceptance shots are.
	 */
	public static BufferedImage render(HeroVisibility shown) throws Exception
	{
		return render(shown, GUIDE_ONLY);
	}

	/**
	 * {@link #render(HeroVisibility)} under a set of view switches (Q7), at the height those switches need
	 * ({@link #height}) - {@link #HEIGHT} for every picture whose list is the twelve-row fixture, so the two
	 * acceptance shots keep the size they are compared at.
	 */
	public static BufferedImage render(HeroVisibility shown, ViewOptions options) throws Exception
	{
		return render(shown, options, FOLD_OPEN);
	}

	/**
	 * {@link #render(HeroVisibility, ViewOptions)} with the price fold open or closed (AA3). The four acceptance
	 * shots are the {@link #FOLD_OPEN} ones; {@code false} draws the sidebar as it stood before addendum AA.
	 */
	public static BufferedImage render(HeroVisibility shown, ViewOptions options, boolean foldOpen) throws Exception
	{
		final ViewOptions view = options == null ? GUIDE_ONLY : options;
		final AtomicReference<BufferedImage> out = new AtomicReference<>();
		onEdt(() -> out.set(paint(build(shown, view, foldOpen), height(view, foldOpen))));
		return out.get();
	}

	/**
	 * How tall the picture is drawn for a set of view switches: {@link #HEIGHT}, and {@link #OPTIONS_HEIGHT} once
	 * the untradeable switch has put three more rows in the list than 900 px hold (R5).
	 */
	public static int height(ViewOptions options)
	{
		return height(options, FOLD_OPEN);
	}

	/**
	 * {@link #height(ViewOptions)} for a chosen fold state (AA3). The three 900 px pictures are unmoved by the
	 * fold - it eats 57 px of the bare ground under their last row and they have 106 px or more of it - so only
	 * the options picture has two heights, and its closed one is the picture addendum W left behind.
	 */
	public static int height(ViewOptions options, boolean foldOpen)
	{
		if (options == null || !options.countUntradeables())
		{
			return HEIGHT;
		}
		return foldOpen ? OPTIONS_HEIGHT : OPTIONS_HEIGHT_CLOSED;
	}

	/**
	 * EDT. The real panel over the mocked manager and service, seeded with the fixture, drawing {@code shown}
	 * under {@code options}.
	 *
	 * <p>The fixture follows the switches the way the service would (Q5, R3): with {@code countUntradeables} on,
	 * the two alch stacks and the parts-priced Crystal body are in the published rows and in the summary; with it
	 * off, neither the list nor the sums has ever heard of them. The panel is handed the same switches through its
	 * prefs, which is how the client starts up.
	 */
	static BankPriceMovementPanel build(HeroVisibility shown, ViewOptions options)
	{
		return build(shown, options, FOLD_OPEN);
	}

	/**
	 * {@link #build(HeroVisibility, ViewOptions)} with the price fold open or closed (AA3): the fixture's prefs
	 * answer {@code foldOpen} to {@code loadFoldOpen}, which is exactly how the client starts up - the plugin
	 * hands the panel the stored key, and a fresh install's null reads as open.
	 *
	 * @param foldOpen true for the sidebar as it ships (the four acceptance shots), false for the sidebar of
	 *                 addenda N to W, whose pictures this class must still be able to reproduce
	 */
	static BankPriceMovementPanel build(HeroVisibility shown, ViewOptions options, boolean foldOpen)
	{
		final ViewOptions view = options == null ? GUIDE_ONLY : options;
		final ItemManager itemManager = mock(ItemManager.class);
		when(itemManager.getImage(anyInt(), anyInt(), anyBoolean()))
			.thenAnswer(invocation -> sprite(invocation.getArgument(0)));
		final PriceService service = mock(PriceService.class);
		final PriceService.Status status = status(view);
		when(service.currentStatus()).thenReturn(status);
		when(service.currentRows()).thenReturn(rows(view));
		final BankPriceMovementPanel.Prefs prefs = new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return RowFilter.DEFAULT;
			}

			@Override
			public void save(RowFilter filter)
			{
			}

			@Override
			public HeroVisibility loadHero()
			{
				return shown;
			}

			@Override
			public ViewOptions loadOptions()
			{
				return view;
			}

			@Override
			public Boolean loadFoldOpen()
			{
				return foldOpen;
			}
		};
		final BankPriceMovementPanel panel = new BankPriceMovementPanel(itemManager, service, prefs);
		// The picture must be the same one whenever it is rendered, so "today" is the fixture's own day and not
		// the day the suite happens to run: the footnote stamps a bank captured on ANOTHER day with its date
		// rather than a clock (B044).
		panel.setClock(() -> BANK_AT_MILLIS + 60_000L);
		return panel;
	}

	/** EDT. Sizes, lays out and prints {@code panel} into a fresh {@link #WIDTH} x {@code height} image. */
	static BufferedImage paint(JComponent panel, int height)
	{
		panel.setSize(WIDTH, height);
		layoutTree(panel);
		final BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setColor(ColorScheme.DARK_GRAY_COLOR);
			g.fillRect(0, 0, WIDTH, height);
			panel.printAll(g);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/**
	 * Lays a component tree out top-down without a peer: {@code Container.validate()} returns at once when the
	 * component has no peer, {@code doLayout()} runs the layout manager regardless, and every child's bounds
	 * are set by its parent's layout before its own runs.
	 */
	static void layoutTree(Component c)
	{
		c.doLayout();
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				layoutTree(child);
			}
		}
	}

	// ---------------------------------------------------------------- the recorded fixture

	/**
	 * A 36 x 32 stand-in for the item sprite: a rounded block in a colour derived from the id with a lighter
	 * core, so the picture cell is visibly in use in the PNG. Never {@code loaded()}: the label paints the
	 * pixels that are there, and nothing waits on a client thread.
	 */
	static AsyncBufferedImage sprite(int id)
	{
		final AsyncBufferedImage image = new AsyncBufferedImage(null, MovementRowPanel.ICON_WIDTH,
			MovementRowPanel.ICON_HEIGHT, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			final float hue = ((id * 47) % 360) / 360f;
			g.setColor(Color.getHSBColor(hue, 0.55f, 0.55f));
			g.fillRoundRect(5, 3, 26, 26, 9, 9);
			g.setColor(Color.getHSBColor(hue, 0.35f, 0.85f));
			g.fillOval(11, 9, 14, 14);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}

	/**
	 * The published rows for a set of view switches (Q5, R5): the twelve, and with {@code countUntradeables} on
	 * three more - the Crystal body at what its seeds are worth, and the two alch stacks after it.
	 *
	 * <p>Each lands where the LIT ordering puts it, because that is the list the service would publish. "Biggest
	 * gainers" is percent descending with the rows that have no baseline last, so the two alch stacks - which can
	 * never have one - go at the end, and the Crystal body, which has a real baseline and a real move (R3), goes
	 * among the movers: its -8.8% is under Zulrah's scales and over the row with no baseline at all, which puts
	 * it two rows above the pair and makes the picture the comparison R5 asks for.
	 */
	static List<MovementRow> rows(ViewOptions options)
	{
		if (options != null && options.livePrices())
		{
			return liveRows();
		}
		if (options == null || !options.countUntradeables())
		{
			return rows();
		}
		final List<MovementRow> out = new ArrayList<>(rows());
		out.add(out.size() - 1, crystalBody());
		out.add(alchRow(11850, "Graceful hood", 1, GRACEFUL_HOOD_ALCH));
		out.add(alchRow(22322, "Avernic defender", 1, AVERNIC_DEFENDER_ALCH));
		return Collections.unmodifiableList(out);
	}

	/**
	 * The twelve rows as the service publishes them with the live switch ON (addendum T, line T8): the same list,
	 * with three stacks re-priced off the wiki's traded series and one marked as refused by the checks.
	 *
	 * <ul>
	 * <li><b>Dragon claws</b> - live, and its 1 d window compared against that day's traded average: the picture's
	 * example of a fully live row.</li>
	 * <li><b>Abyssal whip</b> - live NOW, but its 1 d traded bucket was too thin, so the window fell back to the
	 * guide's own two ends (T4): the one state that has to be visible somewhere, because it is the comparison the
	 * live study refused to make the other way round.</li>
	 * <li><b>Twisted bow</b> - live on a 137-unit day: a stack that is worth more than the rest of the list put
	 * together and still only just clears the volume check.</li>
	 * <li><b>Green hat</b> - a GUIDE row while the switch is on, carrying the first check that refused it ("12
	 * traded yesterday"), which is the line T6 puts on such a row's tooltip.</li>
	 * </ul>
	 *
	 * <p>The order is still "Percent change", biggest first: each live figure was picked to keep its row
	 * where the ordering had already put it, so the picture differs from {@link #TICKER_FILE} in the card's last
	 * line and in four rows' numbers and in nothing else.
	 */
	static List<MovementRow> liveRows()
	{
		final List<MovementRow> out = new ArrayList<>(rows());
		out.set(0, live(row(13652, "Dragon claws", 1, false, CLAWS_LIVE_NOW, CLAWS_TRADED_THEN), null,
			MovementRow.PriceSource.LIVE, facts(41_600_000L, 41_360_000L, 12_483L, null)));
		// T4's awkward case, built the way the service builds it: the GUIDE pair carries the move, and the live mid
		// is what the row prints and what the card counts it at.
		out.set(1, live(row(4151, "Abyssal whip", 12, false, WHIP_GUIDE_NOW, WHIP_GUIDE_THEN), WHIP_LIVE_NOW,
			MovementRow.PriceSource.GUIDE, facts(1_540_000L, 1_526_000L, 5_214L, null)));
		out.set(9, live(row(20997, "Twisted bow", 1, false, BOW_LIVE_NOW, BOW_TRADED_THEN), null,
			MovementRow.PriceSource.LIVE, facts(1_631_000_000L, 1_626_000_000L, 137L, null)));
		out.set(5, out.get(5).withLiveRefusal(facts(1_200L, 900L, 12L, THIN_REASON)));
		return Collections.unmodifiableList(out);
	}

	/**
	 * A row re-sourced as LIVE, with the series its 1 d window used, the DAY that window compared against (U3) and
	 * the facts behind its price (T4).
	 *
	 * <p>The day is {@link #liveDays}'s own, so the row agrees with the status above it: a live 1 d window compared
	 * against the traded bucket of {@code LIVE_DAY - 1}, and the one that fell back to the guide compared against
	 * the guide's baseline day - which on this fixture is the same 08 Sep, because the two calendars agree here.
	 *
	 * @param liveUnit the live mid to print when the row was built from the GUIDE pair, or null when the row is
	 *                 already the live pair's own
	 */
	private static MovementRow live(MovementRow row, @Nullable Long liveUnit, MovementRow.PriceSource windowSource,
		MovementRow.LiveFacts facts)
	{
		final Map<MovementWindow, MovementRow.PriceSource> sources = new EnumMap<>(MovementWindow.class);
		sources.put(MovementWindow.D1, windowSource);
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		days.put(MovementWindow.D1, windowSource == MovementRow.PriceSource.LIVE
			? liveDays().get(MovementWindow.D1) : THEN_DAY);
		return row.asLive(liveUnit, sources, days, facts);
	}

	/**
	 * The live calendar the fourth picture is drawn on (U1): {@code LIVE_DAY - N} for each of the five windows,
	 * which on this fixture is the guide baseline day for each of them too.
	 */
	static Map<MovementWindow, LocalDate> liveDays()
	{
		final Map<MovementWindow, LocalDate> days = new EnumMap<>(MovementWindow.class);
		for (MovementWindow window : MovementWindow.values())
		{
			days.put(window, LIVE_DAY.minusDays(window.days()));
		}
		return days;
	}

	private static MovementRow.LiveFacts facts(long buy, long sell, long volume, @Nullable String reason)
	{
		return new MovementRow.LiveFacts(buy, sell, volume, reason);
	}

	/**
	 * An untradeable stack as the service lists one (Q5): its High Alchemy value as the unit price, no baseline,
	 * no move, and {@link MovementRow.PriceSource#ALCH} - which is what makes the row draw its "alch" tag
	 * instead of a pair of dashes.
	 */
	static MovementRow alchRow(int id, String name, int quantity, long haPrice)
	{
		return new MovementRow(id, name, quantity, false, haPrice, null, null, null, haPrice * quantity,
			MovementRow.PriceSource.ALCH);
	}

	/**
	 * The untradeable stack addendum R values at its tradeable parts (R5): a Crystal body at three Crystal armour
	 * seeds - 16,694,766 gp now against 18,300,000 on the baseline day, so it shows a real fall of -1,605,234 gp
	 * (-8.8%) and paints exactly as a guide row does, rail and all.
	 */
	static MovementRow crystalBody()
	{
		final long then = CRYSTAL_SEEDS * CRYSTAL_SEED_THEN;
		final long delta = CRYSTAL_BODY_NOW - then;
		return new MovementRow(CRYSTAL_BODY_ID, "Crystal body", 1, false, CRYSTAL_BODY_NOW, then, delta,
			delta * 100.0 / then, CRYSTAL_BODY_NOW, MovementRow.PriceSource.PARTS,
			Collections.singletonList(new BankItem.Part(CRYSTAL_SEED_ID, CRYSTAL_SEEDS, CRYSTAL_SEED_NAME)));
	}

	/** Twelve rows in "Percent change" order, biggest first (the row without a baseline last). */
	static List<MovementRow> rows()
	{
		final List<MovementRow> out = new ArrayList<>(12);
		out.add(row(13652, "Dragon claws", 1, false, 41_200_000L, 40_100_000L));
		out.add(row(4151, "Abyssal whip", 12, false, 1_520_000L, 1_500_000L));
		out.add(row(26221, "Ancient ceremonial legs", 1, false, 4_300L, 4_260L));
		out.add(row(3159, "Karambwan vessel (baited)", 2, false, 3_235L, 3_223L));
		out.add(row(1127, "Rune platebody", 3, false, 38_600L, 38_550L));
		out.add(row(1044, "Green hat", 4, false, 1_086L, 1_086L));
		out.add(row(6585, "Amulet of fury", 1, false, 2_850_000L, 2_851_000L));
		out.add(row(385, "Shark", 1_000, true, 890L, 895L));
		out.add(row(29095, "Confliction gauntlets", 1, false, 64_300_000L, 65_151_000L));
		out.add(row(20997, "Twisted bow", 1, false, 1_632_000_000L, 1_690_000_000L));
		out.add(row(12934, "Zulrah's scales", 28_000, true, 121L, 130L));
		out.add(new MovementRow(11832, "Bandos chestplate", 1, false, 21_900_000L, null, null, null, 21_900_000L, null));
		return Collections.unmodifiableList(out);
	}

	private static MovementRow row(int id, String name, int quantity, boolean stackable, long unit, long then)
	{
		final long delta = unit - then;
		final double pct = then == 0L ? 0d : delta * 100.0 / then;
		return new MovementRow(id, name, quantity, stackable, unit, then, delta, pct, unit * quantity, null);
	}

	/**
	 * The whole-bank figures for a set of view switches (Q5, R3): the probe's, and with {@code countUntradeables}
	 * on the three untradeable stacks added to the VALUE and to {@code itemsTotal}.
	 *
	 * <p>The two alch stacks stop there - they have no guide price, so they are not {@code itemsPriced}, and no
	 * baseline, so no window covers them. The Crystal body is a priced stack with a baseline like any other
	 * (R3): it counts in {@code itemsPriced} as well, and in every window's covered set.
	 */
	static PortfolioSummary summary(ViewOptions options)
	{
		if (options != null && options.livePrices())
		{
			// T5: the same whole-bank figures, with the count the card's tooltip prints - "3 of 529 stacks live".
			final PortfolioSummary base = summary();
			return new PortfolioSummary(base.valueStacks(), base.itemsPriced(), base.itemsTotal(), base.moves(),
				base.currencyGp(), LIVE_STACKS);
		}
		if (options == null || !options.countUntradeables())
		{
			return summary();
		}
		final PortfolioSummary base = summary();
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		for (WindowMove m : base.moves().values())
		{
			moves.put(m.window(), new WindowMove(m.window(), m.thenDay(), m.valueThen(), m.valueNowCovered(),
				m.deltaGp(), m.deltaPct(), m.itemsCovered() + 1));
		}
		return new PortfolioSummary(
			base.valueStacks() + GRACEFUL_HOOD_ALCH + AVERNIC_DEFENDER_ALCH + CRYSTAL_BODY_NOW,
			base.itemsPriced() + 1, base.itemsTotal() + 3, moves);
	}

	/** The probe's whole-bank figures: one move per window, every stack covered. */
	static PortfolioSummary summary()
	{
		final Map<MovementWindow, WindowMove> moves = new EnumMap<>(MovementWindow.class);
		moves.put(MovementWindow.D1, move(MovementWindow.D1, THEN_DAY, -950_972L));
		moves.put(MovementWindow.D7, move(MovementWindow.D7, LocalDate.of(2026, 9, 2), -4_349_062L));
		moves.put(MovementWindow.D30, move(MovementWindow.D30, LocalDate.of(2026, 8, 10), -6_371_876L));
		moves.put(MovementWindow.D90, move(MovementWindow.D90, LocalDate.of(2026, 6, 11), -30_585_240L));
		moves.put(MovementWindow.D180, move(MovementWindow.D180, LocalDate.of(2026, 3, 13), -55_638_422L));
		return new PortfolioSummary(VALUE_NOW, BANK_ITEMS, BANK_ITEMS, moves);
	}

	private static WindowMove move(MovementWindow window, LocalDate thenDay, long deltaGp)
	{
		final long valueThen = VALUE_NOW - deltaGp;
		return new WindowMove(window, thenDay, valueThen, VALUE_NOW, deltaGp, deltaGp * 100.0 / valueThen, BANK_ITEMS);
	}

	/**
	 * A LIST status for the fixture. A mock rather than the 22-argument constructor, for the reason
	 * {@code BankPriceMovementPanelTest} gives: another agent owns that class and its argument list moves.
	 */
	static PriceService.Status status()
	{
		return status(GUIDE_ONLY);
	}

	/** {@link #status()} over the row list and the sums a set of view switches produces (Q5). */
	static PriceService.Status status(ViewOptions options)
	{
		final PriceService.Status s = mock(PriceService.Status.class);
		when(s.loggedIn()).thenReturn(true);
		when(s.bankLoaded()).thenReturn(true);
		when(s.bankItems()).thenReturn(BANK_ITEMS);
		when(s.totalRows()).thenReturn(rows(options).size());
		when(s.window()).thenReturn(MovementWindow.D1);
		when(s.problem()).thenReturn(null);
		// No sentence, so no kind: the panel reads this to colour the problem row, and a real status is never null
		// here (PriceService.Status normalises it).
		when(s.problemKind()).thenReturn(PriceService.ProblemKind.NONE);
		final String header = "Guide prices - 1d vs 08 Sep - Bank as of " + MovementMath.formatTime(BANK_AT_MILLIS);
		when(s.text()).thenReturn(header);
		when(s.headerText()).thenReturn(header);
		when(s.pricesAtMillis()).thenReturn(BANK_AT_MILLIS + 60_000L);
		when(s.bankAtMillis()).thenReturn(BANK_AT_MILLIS);
		when(s.baselineLoaded()).thenReturn(true);
		when(s.thenDay()).thenReturn(THEN_DAY);
		when(s.portfolio()).thenReturn(summary(options));
		// U3: with the switch on the card reads its DAY off the live calendar, so the live picture is drawn with one
		// - the three live stacks, the nine the checks left on the guide, and LIVE_DAY behind them. The three
		// guide-only pictures get no LiveStatus at all (the mock answers null, which the panel reads as OFF), which
		// is what a publish with the switch off carries.
		if (options != null && options.livePrices())
		{
			when(s.live()).thenReturn(new PriceService.Status.LiveStatus(BANK_AT_MILLIS + 60_000L,
				LIVE_LATEST_ITEMS, LIVE_STACKS, LIVE_GUIDE_STACKS, 0, LIVE_DAY, liveDays()));
		}
		return s;
	}

	/** The names of the fixture's rows, in order, for a test that checks the list. */
	static List<String> rowNames()
	{
		final List<String> names = new ArrayList<>();
		for (MovementRow row : rows())
		{
			names.add(row.name());
		}
		return Collections.unmodifiableList(names);
	}

	/** Runs {@code body} on the EDT and rethrows anything it threw, with the failure's own message. */
	static void onEdt(Runnable body) throws Exception
	{
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				body.run();
			}
			catch (Throwable t)
			{
				failure.set(t);
			}
		});
		final Throwable t = failure.get();
		if (t instanceof Exception)
		{
			throw (Exception) t;
		}
		if (t instanceof Error)
		{
			throw (Error) t;
		}
		if (t != null)
		{
			throw new IllegalStateException(t);
		}
	}
}
