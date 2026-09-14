package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

/**
 * One bank item in the sidebar list: a 213 x 48 card with the item's picture on the left, its name across the
 * whole text block, and a second line carrying the unit price, the stack and the window's movement as a gp
 * figure and a percentage (addendum N section 2 "Rows" and section 4.5, the "Ticker" row;
 * {@code docs/bank-price-movement-addendum-N-2026-09-09.md}). The 48 px height and this anatomy OVERRIDE
 * contract C31's 213 x 40 strip and its two-number line.
 *
 * <pre>
 * +-+----+-----------------------------+
 * |#|    | Abyssal whip                |
 * |#|icon| 1.52m  x12    +14.2k +1.8%  |
 * +-+----+-----------------------------+
 * </pre>
 *
 * <p><b>One look, since addendum O</b> ({@code docs/bank-price-movement-addendum-O-2026-09-09.md}, line O1).
 * Addendum N built this row in two designs behind a switch so the user could pick a winner; the user picked
 * Ticker - "i prefer the ticker look" (2026-09-09) - so the row now always prints the PAIR, the gp change and
 * then the percentage, both in the move's colour, and the two-design enum, its config item and the "one
 * loud number" branches are gone. The row's figures are the product and are not hideable: addendum O's three
 * show/hide switches are the hero CARD's, and never reach a row ({@link HeroVisibility}). Nothing here reads the
 * ORDERING either - it decided which figure LOOK A printed, and with that look deleted the row stopped varying
 * with it, so the quality pass of 2026-09-10 took the parameter out rather than leaving a recorded value no one
 * drew (the panel decides a rebuild from the filter it built the page from).
 *
 * <p><b>The rail, and why movers only.</b> The 3 px left edge is
 * {@code PROGRESS_COMPLETE_COLOR.darker()} for a rise, {@code PROGRESS_ERROR_COLOR.darker()} for a fall and
 * the card's own grey - invisible - for a flat row, a row with no baseline and a row with no price. A quiet
 * day is a quiet list. It is a {@link javax.swing.border.MatteBorder} through {@link Widgets#card}, not a
 * painted rectangle, so it survives every hover repaint without a custom {@code paintComponent}.
 *
 * <p><b>Width.</b> The row is pinned to {@link #ROW_WIDTH} x {@link #ROW_HEIGHT} and the text block gets
 * {@link #TEXT_WIDTH}. The fit order on line 2 is the M4 count idiom: the right-hand group is sized FIRST (it
 * is the point of the row), the unit price is fitted into what is left, and the stack text is added only if it
 * still fits - dropped otherwise, since "x28,000" is worth less than a whole price. The name is fitted to the
 * whole 156 px through {@link Widgets#setFittedName}, which keeps a potion's dose when it has to cut
 * ("Super combat pot...(4)"), because the four doses are otherwise cut to one identical headline. Everything cut
 * is whole in the tooltip.
 *
 * <p><b>The picture.</b> {@code ItemManager.getImage(id, quantity, stackable)} answers an
 * {@link AsyncBufferedImage} that may still be blank
 * ({@code runelite-client/src/main/java/net/runelite/client/game/ItemManager.java:527-529}: filled in later
 * on the client thread); {@link AsyncBufferedImage#addTo(JLabel)} ({@code AsyncBufferedImage.java:88-91})
 * registers the repaint for when it lands, which is how {@code GrandExchangeItemPanel.java:105} and
 * {@code LootTrackerBox.java:304} show theirs. The reference is kept on the row (the manager's cache holds
 * 128 images, {@code ItemManager.java:221-222}) so the picture survives the cache moving on.
 *
 * <p><b>Mouse.</b> The hover colour and the tooltip are on the row AND on every child: a component with a
 * tooltip becomes its own mouse target (playbook 7.5), so a listener on the row alone would lose the hover
 * the moment the pointer crossed onto the name. Hover is {@code DARKER_GRAY_HOVER_COLOR}, RuneLite's paired
 * hover for a {@code DARKER_GRAY} card (N section 2). There is no zebra any more - every card is
 * {@code DARKER_GRAY} on the {@code DARK_GRAY} ground with a 2 px gutter between them - so {@code setStripe}
 * and {@code stripe()} are gone with it.
 *
 * <p>Right-click opens a {@link JPopupMenu} through {@code setComponentPopupMenu} (the
 * {@code LootTrackerBox.java:310-312} idiom, inherited by the children) with the TWO entries of addendum K8,
 * unchanged: the item's page on the Grand Exchange site - the place the guide price on this row actually
 * comes from - and its price history on the wiki, both through {@link LinkBrowser#browse}. The tests hand in
 * their own browser through the package-private constructor; the public ones use RuneLite's.
 *
 * <p><b>Guide prices (addendum K), by calendar day (addendum L).</b> The unit price is the Jagex GUIDE price -
 * what the in-game Grand Exchange, the GE web site and RuneLite's own tooltip all show - and "then" is that same
 * table as it stood on an earlier DAY. So the tooltip names the figure ("Guide price:") and stamps the baseline
 * with the day the table's own {@code %LAST_UPDATE%} claims ("1d ago (07 Sep): 1,124 gp", L7): the series is one
 * step per UTC day (L-A) and the wiki's bot republishes it at a random hour (L-C), so the publication clock the
 * K build printed here said nothing about which day's prices the reader was looking at.
 *
 * <p>The tooltip is UNCHANGED by addendum N, and it is now load-bearing: the holding value and the exact gp
 * change both left the face of the row, so its "Holding:" and "Change per item:" lines are the only place they
 * exist.
 *
 * <p>The change is signed by the gp figure, not by the percentage (L2): a fall too small to survive
 * truncation still reads "-0.0%" and still paints red, exactly as the GE site's own row does.
 *
 * <p><b>Four switches reach the row</b> ({@link ViewOptions}, handed in at build time - a row is
 * built once and never re-read, so a switch that moves rebuilds the page).
 *
 * <ul>
 * <li><b>Untradeables</b> (Q5, as addendum R rewrote them;
 * {@code docs/bank-price-movement-addendum-R-2026-09-11.md}). A stack the Grand Exchange does not trade has no
 * guide price OF ITS OWN, and with {@code countUntradeables} on the service values it one of two ways and says
 * which through the row's source. When RuneLite can take the item apart ({@code ItemMapping}) the row is worth
 * what its tradeable PARTS are worth - a Crystal body is three Crystal armour seeds, R2 - so it has a real
 * baseline and a real move and it paints EXACTLY as a guide row does: price, gp figure, percentage, rail. Only
 * its tooltip says where the figure came from, in one line under the price ({@link #partsLine}, R4). When
 * nothing can take it apart the row is its High Alchemy value ({@link MovementRow.PriceSource#ALCH}), which is
 * a constant and not a series: line 2 prints that price exactly as it prints a guide price and, where the two
 * move figures would be, a small grey "alch" tag instead of a pair of dashes - because the dash means "not
 * known yet" everywhere else on this panel and this figure is never coming - and the tooltip says the whole of
 * it in a sentence ({@link #untradeableLine}) rather than stamping a baseline day and a change that cannot
 * exist.</li>
 * <li><b>Live prices</b> (addendum T, line T6; {@code docs/bank-price-movement-addendum-T-2026-09-12.md}). A row
 * the service priced from the wiki's live TRADED series paints exactly as a guide row does - same price line,
 * same gp figure, same percentage, same rail - because a live price is a price and a tag on every liquid row
 * would be a tag on most of the list. The tooltip is where the series is named: "Live traded price: ... (buy ...,
 * sell ...; 517 traded yesterday)", the window line's "(traded average)" or "(guide - too few or too scattered
 * trades that day)",
 * and, on a row that stayed on the guide while the switch was on, the one check that refused it. Nothing on the
 * face moves, which is the whole of T6. Since addendum U (line U3) that window line also stamps the day the ROW
 * compared against ({@link #stampedDay}) rather than the guide's baseline day, because the two are different days
 * whenever Jagex has not yet published today's table.</li>
 * <li><b>Holding on rows</b> (Q6). With {@code holdingOnRows} on the price figure is what the whole STACK is
 * worth and the gp figure is what the whole stack moved ({@link MovementRow#holdingDeltaGp()}) - the two
 * figures the tooltip has carried alone since addendum N. The percentage, the stack text and the tooltip are
 * the same either way: a percentage is the same number per item and per stack, and the tooltip already
 * carries both readings.</li>
 * <li><b>Inventory and worn gear</b> (addendum Y, line Y3). Nothing on the FACE moves: a merged stack is one row
 * with the combined quantity, which is what the row would have drawn had the bank held them all. The tooltip is
 * where the merge is explained, in one line under the holding ({@link #splitLine}), and only on a row that is not
 * entirely in the bank.</li>
 * </ul>
 *
 * <p>EDT only, like every Swing component.
 */
public class MovementRowPanel extends JPanel
{
	/** The row's width: the sidebar's content width (contract C28/C31). */
	public static final int ROW_WIDTH = Widgets.CONTENT_WIDTH;
	/**
	 * The row's height (addendum N section 2 and section 3 §4, overriding contract C31's 40): 3 px of top
	 * padding, a 19 px name line, a 24 px figure line and 2 px below.
	 */
	public static final int ROW_HEIGHT = 48;
	/** The picture cell: an item sprite is 36 x 32 px ({@code ItemManager} draws them at that size). */
	public static final int ICON_WIDTH = 36;
	public static final int ICON_HEIGHT = 32;
	/** The wiki's price-history page for one item, followed by the item id (K8). */
	public static final String WIKI_ITEM_URL = "https://prices.runescape.wiki/osrs/item/";
	/**
	 * The Grand Exchange site's item page, followed by the item id (K8). The same address the calibration
	 * probe read "Green hat 1,086, today -38 gp (-3%)" off, which is the figure this row now shows.
	 */
	public static final String GE_ITEM_URL = "https://secure.runescape.com/m=itemdb_oldschool/viewitem?obj=";
	/** The second right-click entry's text. */
	public static final String OPEN_WIKI = "Open price history on the wiki";
	/** The first right-click entry's text: where the guide price on this row is published. */
	public static final String OPEN_GE = "Open on the Grand Exchange";
	/**
	 * What an untradeable row prints where the two move figures go (Q5): a word, not a dash. Lower case and
	 * grey, so it reads as a label on the price beside it rather than as a figure of its own.
	 *
	 * <p>Only an ALCH row wears it. A row valued at its tradeable parts has a move like any other and prints one
	 * (R4, {@link #partsLine}).
	 */
	public static final String ALCH_TAG = "alch";
	/**
	 * How a parts row's tooltip line opens (R4), before the parts themselves: the same "Untradeable - " the alch
	 * sentence opens with, because the two are the same fact told about two different valuations.
	 */
	public static final String PARTS_PREFIX = "Untradeable - valued as its parts: ";
	/**
	 * How a LIVE row's tooltip opens instead of "Guide price: " (addendum T, line T6;
	 * {@code docs/bank-price-movement-addendum-T-2026-09-12.md}). The row's FACE is the same as a guide row's -
	 * a live price is a price - so the tooltip is the one place that names the series, exactly as it is the one
	 * place that names a parts valuation.
	 */
	public static final String LIVE_PRICE_PREFIX = "Live traded price: ";
	/**
	 * What a live window's baseline figure is (T4): the volume-weighted average of that calendar day's bulk
	 * traded bucket, not a guide table's entry. Said on the line so a reader comparing the row against the Grand
	 * Exchange site knows why the two differ.
	 */
	public static final String TRADED_AVERAGE_NOTE = " (traded average)";
	/**
	 * What a live row's window says when that day's traded bucket could not answer (T4, T6): the window fell back
	 * to the row's guide figures, both ends of it, so the comparison is a guide one even though the price above is
	 * live. The dash rules and the arithmetic are unchanged; only this says which series answered.
	 *
	 * <p>"too few or too scattered" because there are now two ways a bucket is refused (addendum V, lines V3 and
	 * V5): too little volume, as since T4, or two daily averages more than a tenth apart - the Tinderbox bucket
	 * whose buy side averaged 37 gp on 5,063 trades against a sell side of 12 gp on 484. One note covers both
	 * deliberately: the reader of a window line wants to know that the figure beside it is a guide figure, and
	 * which of the two refusals it was is a fact about the wiki's day and not about this item.
	 */
	public static final String WINDOW_FELL_BACK_NOTE = " (guide - too few or too scattered trades that day)";
	/**
	 * How the one line a GUIDE row gains while the live switch is on opens (T6), before the check that refused it
	 * ({@link MovementRow.LiveFacts#reason()}). Without it a reader who switched live prices on has no way to tell
	 * a row the traded series moved from one it left alone - the two paint identically - and the whole question
	 * they have is which of their items are actually live.
	 */
	public static final String LIVE_NOT_USED_PREFIX = "Guide price - live not used: ";
	/**
	 * The three places a merged stack can be, as the split line names them (addendum Y, line Y3;
	 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}). Each is a suffix on its own figure, so the line
	 * reads "3 in bank, 1 in inventory, 1 worn" - "worn" without a preposition because that is the word the game's
	 * own tab uses and "in worn gear" reads like a fourth container.
	 */
	public static final String IN_BANK = " in bank";
	public static final String IN_INVENTORY = " in inventory";
	public static final String WORN = " worn";

	/** The card's padding inside the coloured edge (N section 3 §4): 213 - 3 - 4 - 6 = 200 px of inner width. */
	private static final Insets PADDING = new Insets(3, 4, 2, 6);
	/** Between the picture and the text block. */
	private static final int GAP = 8;
	/**
	 * What the two text lines share: the row minus the rail, the padding, the picture and the gap after it.
	 * 213 - 3 - 4 - 6 - 36 - 8 = 156, against the 124-134 px the name had before addendum N.
	 */
	static final int TEXT_WIDTH = ROW_WIDTH - Widgets.EDGE_WIDTH - PADDING.left - PADDING.right
		- ICON_WIDTH - GAP;
	/** Between the price group on the left of line 2 and the figures on the right. */
	private static final int LINE2_GAP = 6;
	/** Between the unit price and the grey stack text. */
	private static final int STACK_GAP = 6;
	/** Between the small gp figure and the percentage beside it. */
	private static final int FIGURE_GAP = 6;

	/** T3 of addendum N section 3 §2: the item name, bold, white, the row's headline. */
	private static final int NAME_SIZE = 14;
	/** T4: the unit price. */
	private static final int PRICE_SIZE = 14;
	/** T7: the grey stack count. */
	private static final int SMALL_SIZE = 11;
	/** The percentage - bold, but one step of the scale down, because it shares the line with the gp figure. */
	private static final int PCT_SIZE = 14;
	/** The gp figure (N section 4, "the gp change (12 px, in the move colour)"). */
	private static final int GP_SIZE = 12;
	/** T6: the two right-click entries, the same face the sort menu one control row away is drawn in. */
	private static final int MENU_SIZE = 12;

	/**
	 * The row's six faces, derived ONCE. A page is up to {@link BankPriceMovementPanel#ROWS_PER_PAGE} rows and a
	 * rebuild happens on every bank change, so deriving six fonts per row was 1,500 {@code Font.deriveFont}
	 * calls per deposit; a {@link Font} is immutable and shared by design.
	 */
	private static final Font NAME_FONT = Widgets.sansBold(NAME_SIZE);
	private static final Font PRICE_FONT = Widgets.sans(PRICE_SIZE);
	private static final Font SMALL_FONT = Widgets.sans(SMALL_SIZE);
	private static final Font PCT_FONT = Widgets.sansBold(PCT_SIZE);
	private static final Font GP_FONT = Widgets.sans(GP_SIZE);
	private static final Font MENU_FONT = Widgets.sans(MENU_SIZE);

	private final MovementRow row;
	/** Kept so the sprite stays referenced while the row is on screen (see the class comment). */
	@Nullable
	private final AsyncBufferedImage icon;
	private final Consumer<String> browser;
	private final JLabel iconLabel;
	private final JLabel nameLabel;
	private final JLabel priceLabel;
	private final JLabel quantityLabel;
	private final JLabel changeLabel;
	private final JLabel gpLabel;
	private final Color rail;
	private final String tooltip;
	private boolean hovered;

	/** Contract C31's constructor: no baseline day known, so the tooltip stamps the "then" line with "-". */
	public MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window)
	{
		this(row, icon, window, null);
	}

	/**
	 * The list builder's constructor.
	 *
	 * <p>Since addendum O the row's face does not vary with the ORDERING at all - it always prints the gp figure
	 * and the percentage (O1) - so the sort the page was built under is no longer carried down here. What still
	 * decides whether a built page has to be rebuilt is the panel's business, and it reads it from the filter it
	 * built the page from ({@code BankPriceMovementPanel.onRows}).
	 *
	 * @param thenDay the UTC calendar day whose guide table {@link MovementRow#thenPrice()} came from - the day
	 *                the table's own {@code %LAST_UPDATE%} names, which is what {@code Status.thenDay()} carries
	 *                (L7); null = not known yet, and the tooltip stamps "-"
	 */
	public MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay)
	{
		this(row, icon, window, thenDay, ViewOptions.DEFAULT);
	}

	/**
	 * The list builder's constructor since addendum Q.
	 *
	 * @param options the view switches the page is being built under; null reads as {@link ViewOptions#DEFAULT}.
	 *                Only {@code holdingOnRows} reaches the face here - whether an untradeable stack is LISTED at
	 *                all is the service's decision (Q5), and by the time a row arrives the question is only how
	 *                to draw it - and, since addendum T, {@code livePrices} reaches the TOOLTIP
	 *                ({@link #tooltip(MovementRow, MovementWindow, LocalDate, ViewOptions)}): a live row paints
	 *                exactly as a guide row does (T6), so the face still reads only the one switch. Addendum Y's
	 *                {@code countInventory} is the third that reaches the tooltip alone (Y3) - a merged stack is
	 *                drawn as the one stack it is, and only the hover says where it is
	 */
	public MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay, @Nullable ViewOptions options)
	{
		this(row, icon, window, thenDay, options, LinkBrowser::browse);
	}

	/** @param browser what the two right-click entries hand their URL to; the tests record, the client browses */
	MovementRowPanel(MovementRow row, @Nullable AsyncBufferedImage icon, MovementWindow window,
		@Nullable LocalDate thenDay, @Nullable ViewOptions options, Consumer<String> browser)
	{
		final ViewOptions view = options == null ? ViewOptions.DEFAULT : options;
		this.row = Objects.requireNonNull(row, "row");
		this.icon = icon;
		this.browser = Objects.requireNonNull(browser, "browser");
		this.tooltip = tooltip(row, window, thenDay, view);
		this.rail = railColor(row);

		setLayout(new BorderLayout(GAP, 0));
		setBorder(Widgets.card(rail, PADDING));
		setBackground(ColorScheme.DARKER_GRAY_COLOR);
		Widgets.fixed(this, ROW_WIDTH, ROW_HEIGHT);

		iconLabel = new JLabel();
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);
		Widgets.fixed(iconLabel, ICON_WIDTH, ICON_HEIGHT);
		if (icon != null)
		{
			icon.addTo(iconLabel);
		}
		add(iconLabel, BorderLayout.WEST);

		// Line 1: the name across the whole block - the row's headline, and the only thing on its line. Fitted
		// through setFittedName, so a potion keeps its dose when it is cut (all four of "Super combat potion(1..4)"
		// are wider than the block, and the plain cut made one string of them).
		nameLabel = Widgets.label("", NAME_FONT, Color.WHITE);
		Widgets.setFittedName(nameLabel, row.name(), TEXT_WIDTH);

		// Line 2, right-hand group first (the fit order): the percentage, and the gp figure beside it - or, on an
		// untradeable row, the grey "alch" tag standing in for both (Q5).
		final Color moveColour = textChangeColor(row);
		changeLabel = isAlch(row)
			? Widgets.label(ALCH_TAG, SMALL_FONT, ColorScheme.LIGHT_GRAY_COLOR)
			: Widgets.label(changeText(row), PCT_FONT, moveColour);
		gpLabel = Widgets.label(gpText(row, view), GP_FONT, moveColour);
		// Every gap on this line is an inset and never a layout hgap: BorderLayout charges its hgap for the WEST
		// and the EAST child alike, so a 6 px hgap here would quietly cost 12 px and the fit order would hand the
		// price 6 px it does not have (measured, 2026-09-09: the widest gp row asked for 160 of 156).
		final JPanel figures = transparent(new BorderLayout(0, 0));
		figures.setBorder(new EmptyBorder(0, LINE2_GAP, 0, 0));
		if (!gpLabel.getText().isEmpty())
		{
			gpLabel.setBorder(new EmptyBorder(0, 0, 0, FIGURE_GAP));
			figures.add(gpLabel, BorderLayout.WEST);
		}
		figures.add(changeLabel, BorderLayout.EAST);

		// ...then the price into what is left, and the stack only if it still fits.
		priceLabel = Widgets.label("", PRICE_FONT, Color.WHITE);
		quantityLabel = Widgets.label("", SMALL_FONT, ColorScheme.LIGHT_GRAY_COLOR);
		final int room = TEXT_WIDTH - figures.getPreferredSize().width;
		Widgets.setFitted(priceLabel, priceText(row, view), room);
		final String stack = quantityText(row);
		final JPanel prices = transparent(new BorderLayout(0, 0));
		prices.add(priceLabel, BorderLayout.WEST);
		if (!stack.isEmpty())
		{
			quantityLabel.setText(stack);
			final int stackWidth = quantityLabel.getPreferredSize().width;
			if (priceLabel.getPreferredSize().width + STACK_GAP + stackWidth <= room)
			{
				quantityLabel.setBorder(new EmptyBorder(0, STACK_GAP, 0, 0));
				prices.add(quantityLabel, BorderLayout.CENTER);
			}
			else
			{
				quantityLabel.setText("");
			}
		}

		final JPanel line2 = transparent(new BorderLayout(0, 0));
		line2.add(prices, BorderLayout.WEST);
		line2.add(figures, BorderLayout.EAST);
		alignBaselines(priceLabel, quantityLabel, gpLabel, changeLabel);

		final JPanel text = transparent(new BorderLayout(0, 0));
		text.add(nameLabel, BorderLayout.NORTH);
		text.add(line2, BorderLayout.CENTER);
		add(text, BorderLayout.CENTER);

		// K8's two entries, in the order a reader wants them: first where this row's number is published, then
		// the history behind it. Unchanged by addendum N.
		// Both items set their font: a menu item that is given none inherits the look and feel's default, which
		// under RuneLiteLAF is the 16 px bitmap RuneScape face - so these two would be drawn in a different
		// typeface from the sort menu one control row away (which sets Widgets.sans(12)).
		final JPopupMenu menu = new JPopupMenu();
		menu.setBorder(new EmptyBorder(5, 5, 5, 5));
		final JMenuItem openGe = new JMenuItem(OPEN_GE);
		openGe.setFont(MENU_FONT);
		openGe.addActionListener(e -> openGrandExchange());
		menu.add(openGe);
		final JMenuItem open = new JMenuItem(OPEN_WIKI);
		open.setFont(MENU_FONT);
		open.addActionListener(e -> openWiki());
		menu.add(open);
		setComponentPopupMenu(menu);

		final MouseAdapter hover = new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				setHovered(true);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				setHovered(false);
			}
		};
		setToolTipText(tooltip);
		addMouseListener(hover);
		for (JComponent c : children())
		{
			c.setToolTipText(tooltip);
			c.setInheritsPopupMenu(true);
			c.addMouseListener(hover);
		}
	}

	/** Every component under the row, so the tooltip, the popup and the hover reach all of them (playbook 7.5). */
	private List<JComponent> children()
	{
		final List<JComponent> out = new ArrayList<>(12);
		collect(this, out);
		return out;
	}

	private static void collect(Container c, List<JComponent> into)
	{
		for (Component child : c.getComponents())
		{
			if (child instanceof JComponent)
			{
				into.add((JComponent) child);
			}
			if (child instanceof Container)
			{
				collect((Container) child, into);
			}
		}
	}

	private static JPanel transparent(LayoutManager layout)
	{
		final JPanel p = new JPanel(layout);
		p.setOpaque(false);
		return p;
	}

	/**
	 * Puts every label of line 2 on ONE baseline: each is bottom-aligned in its (full-height) cell and given a
	 * bottom inset of the difference between the deepest descent on the line and its own. The numbers come from
	 * {@link FontMetrics} at build time rather than from the addendum's table, because {@code Font.DIALOG} maps
	 * to a different face on every platform and a constant measured here would drift on the user's machine.
	 */
	private static void alignBaselines(JLabel... labels)
	{
		int deepest = 0;
		for (JLabel label : labels)
		{
			deepest = Math.max(deepest, label.getFontMetrics(label.getFont()).getDescent());
		}
		for (JLabel label : labels)
		{
			label.setVerticalAlignment(SwingConstants.BOTTOM);
			final int lift = deepest - label.getFontMetrics(label.getFont()).getDescent();
			if (lift <= 0)
			{
				continue;
			}
			final Insets was = label.getBorder() == null
				? new Insets(0, 0, 0, 0) : label.getBorder().getBorderInsets(label);
			label.setBorder(new EmptyBorder(was.top, was.left, was.bottom + lift, was.right));
		}
	}

	// ---------------------------------------------------------------- texts (pure, for the tests)

	/** The unit price as the game writes a stack ("1.52m"), or {@link MovementMath#DASH} without one. */
	public static String priceText(MovementRow row)
	{
		return priceText(row, ViewOptions.DEFAULT);
	}

	/**
	 * The price figure line 2 opens with: the price of ONE by default, and what the whole stack is worth while
	 * {@code holdingOnRows} is on (Q6) - the same stack-style formatting either way, and the {@code x<qty>}
	 * beside it stays, because a holding of 18.2m over "x12" is the reading the switch was asked for.
	 *
	 * <p>A row with no price prints {@link MovementMath#DASH} under both switches: {@link MovementRow#holdingValue()}
	 * is 0 without a unit price, and a "0" there would claim the stack is worthless rather than unpriced.
	 */
	public static String priceText(MovementRow row, @Nullable ViewOptions options)
	{
		if (row.unitPrice() == null)
		{
			return MovementMath.DASH;
		}
		return MovementMath.formatGp(holding(options) ? row.holdingValue() : row.unitPrice());
	}

	/**
	 * The stack count in stack style ("x12", "x28,000"), and NOTHING at a quantity of one (addendum N section
	 * 2): most of a bank is single items, and "x1" on every second row is noise that costs the price its width.
	 * The holding value it used to carry ("x12 - 18.2m") has left the face for the tooltip's "Holding:" line,
	 * which is now the only place it lives.
	 */
	public static String quantityText(MovementRow row)
	{
		return row.quantity() == 1 ? "" : "x" + MovementMath.formatGp(row.quantity());
	}

	/**
	 * The percentage ("+1.8%", "-0.0%"), or one dash when the row has no baseline - the outermost figure on
	 * line 2 and the row's boldest. Signed by the gp figure and not by itself (L2), so a fall too small to
	 * survive truncation still shows its minus.
	 *
	 * <p>Addendum N moved the gp half of the old "+12.3k +0.8%" out of here into its own label, so the two
	 * figures are sized separately - 14 px bold against 12 px plain - though they always share a colour
	 * ({@link #gpText(MovementRow)}).
	 */
	public static String changeText(MovementRow row)
	{
		if (!row.hasMovement())
		{
			return MovementMath.DASH;
		}
		return MovementMath.formatPct(row.deltaPct(), row.deltaGp());
	}

	/**
	 * The small gp figure beside the percentage ("+14.2k", "-851k"), or "" when the row has nothing to print
	 * there - an empty label rather than a dash, because the percentage beside it is already saying so.
	 *
	 * <p>"Nothing to print" is a row with no movement AND a row whose price did not move: a flat row's "0" beside
	 * its own "0.0%" is two symbols for one fact, and on a quiet day it takes ~13 px off the fit budget that the
	 * unit price and the stack count are competing for. A quiet row reads "1,086  x4      0.0%".
	 */
	public static String gpText(MovementRow row)
	{
		return gpText(row, ViewOptions.DEFAULT);
	}

	/**
	 * {@link #gpText(MovementRow)} under the view switches: the change in ONE item's price by default, and the
	 * change in the whole STACK while {@code holdingOnRows} is on (Q6, {@link MovementRow#holdingDeltaGp()}).
	 *
	 * <p>The "nothing to print" rule is the same in both readings and is asked of the figure that will be drawn:
	 * a stack whose unit moved by a gp but whose holding is one item still prints that gp, and a holding change
	 * that came out at zero prints nothing beside its own "0.0%".
	 */
	public static String gpText(MovementRow row, @Nullable ViewOptions options)
	{
		if (!row.hasMovement())
		{
			return "";
		}
		if (holding(options))
		{
			final long stack = row.holdingDeltaGp();
			return stack == 0L ? "" : MovementMath.formatDelta(stack);
		}
		final Long gp = row.deltaGp();
		return gp != null && gp != 0L ? MovementMath.formatDelta(gp) : "";
	}

	/** Whether the row prints the whole stack rather than one item; null options read as {@link ViewOptions#DEFAULT}. */
	private static boolean holding(@Nullable ViewOptions options)
	{
		return options != null && options.holdingOnRows();
	}

	/**
	 * Whether this row is an untradeable stack listed at its High Alchemy value (Q5). The row's SOURCE says so -
	 * {@link MovementRow.PriceSource#ALCH} - and nothing else does: an untradeable row has no baseline and no
	 * move, but so does a brand-new tradeable item the guide table has never priced, and the two must not draw
	 * the same.
	 */
	public static boolean isAlch(MovementRow row)
	{
		return row.source() == MovementRow.PriceSource.ALCH;
	}

	/**
	 * Whether this row is an untradeable stack valued at what its tradeable PARTS are worth (addendum R, line
	 * R2) - a Crystal body at three Crystal armour seeds rather than at its 900k alch value.
	 *
	 * <p>Nothing on the FACE turns on this: such a row has a guide-priced baseline and a real move, so it paints
	 * as any other priced row does (R4), and the flag exists for the one tooltip line that says so. It is asked
	 * of the row's SOURCE for the same reason {@link #isAlch} is: a sum of parts and a guide price are two
	 * different answers that happen to be the same kind of number.
	 */
	public static boolean isParts(MovementRow row)
	{
		return row.source() == MovementRow.PriceSource.PARTS;
	}

	/**
	 * The one line an untradeable row's tooltip says instead of a guide price, a baseline and a change (Q5):
	 * "Untradeable - High Alchemy value 20,000 gp; not in the movement figures". Both halves are load-bearing -
	 * WHICH price the row is showing, and why the movement columns are a tag instead of a figure.
	 */
	public static String untradeableLine(MovementRow row)
	{
		return "Untradeable - High Alchemy value "
			+ (row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatExact(row.unitPrice()))
			+ " gp; not in the movement figures";
	}

	/**
	 * The one line a PARTS row's tooltip adds under its price (R4): "Untradeable - valued as its parts: 3 x
	 * Crystal armour seed, Crystal shard". Each part is "&lt;n&gt; x &lt;name&gt;", the count left off at one
	 * because "1 x Black mask" is a quantity nobody asked about, and the parts joined with ", " in the order
	 * {@code ItemMapping} lists them.
	 *
	 * <p>Both halves are load-bearing, exactly as they are on an ALCH row's {@link #untradeableLine}: that the
	 * item is untradeable at all, and WHICH tradeable thing the figure above it is the price of. Without the
	 * second half a Crystal body simply reads 16.7m and a reader has no way to tell that from a guide price - and
	 * the whole reason the row is worth 16.7m rather than 900k is the three seeds it reverts to.
	 *
	 * <p>"" when the row carries no parts, which is every row that is not a PARTS row, so a caller can ask blind.
	 * Plain text, not HTML: the names come from the game, so the caller escapes what it appends.
	 */
	/**
	 * The one line a CARRIED row's tooltip adds under its "Holding:" line (addendum Y, line Y3;
	 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}): "3 in bank, 1 in inventory, 1 worn" - where the
	 * quantity on the line above it actually is.
	 *
	 * <p>It exists because addendum Y merges the three containers into ONE row: an item held in the bank and worn
	 * is a single 48 px card with the quantities summed, which is the row a reader wants and also a row whose
	 * quantity they cannot check against anything they can see. The three parts are named in the order a player
	 * would look for them - the bank the list is about, then what they are carrying, then what they have on -
	 * and a part at zero is DROPPED rather than printed as "0 worn", so a worn-only item reads "1 worn" and a
	 * stack split two ways reads "3 in bank, 1 in inventory".
	 *
	 * <p>"" whenever there is nothing to say: a row computed with the switch off (no split at all) and a row whose
	 * whole stack is in the bank ({@link MovementRow#allInBank()}), because "3 in bank" under "Holding: 3" is the
	 * same fact twice. So the line appears exactly on the rows addendum Y changed, and the tooltip of every other
	 * row is the tooltip addenda K to X wrote, line for line.
	 *
	 * <p>Plain text; the caller appends it to the tooltip's HTML. The figures are
	 * {@link MovementMath#formatExact} like the holding above them, so a stack of 28,000 scales split by a trip
	 * reads "27,000 in bank, 1,000 in inventory".
	 */
	public static String splitLine(MovementRow row)
	{
		if (row.allInBank())
		{
			return "";
		}
		final List<String> parts = new ArrayList<>(3);
		addWhere(parts, row.bankQuantity(), IN_BANK);
		addWhere(parts, row.inventoryQuantity(), IN_INVENTORY);
		addWhere(parts, row.wornQuantity(), WORN);
		return String.join(", ", parts);
	}

	/** One non-zero part of {@link #splitLine}: "3 in bank". Nothing is added at zero (Y3). */
	private static void addWhere(List<String> parts, int quantity, String where)
	{
		if (quantity > 0)
		{
			parts.add(MovementMath.formatExact(quantity) + where);
		}
	}

	public static String partsLine(MovementRow row)
	{
		final List<BankItem.Part> parts = row.parts();
		if (parts == null || parts.isEmpty())
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder(64).append(PARTS_PREFIX);
		boolean first = true;
		for (BankItem.Part part : parts)
		{
			if (part == null)
			{
				continue;
			}
			if (!first)
			{
				sb.append(", ");
			}
			first = false;
			if (part.quantity > 1L)
			{
				sb.append(MovementMath.formatExact(part.quantity)).append(" x ");
			}
			sb.append(part.name == null ? "" : part.name);
		}
		return first ? "" : sb.toString();
	}

	/**
	 * The first line of a LIVE row's tooltip, under the name (T6): "Live traded price: 63,437,264 gp (buy 63.6m,
	 * sell 63.3m; 517 traded yesterday)".
	 *
	 * <p>Three figures, and each is there because a reader who has switched live prices on asks for it: the mid
	 * this row is actually priced at, the two sides it is the middle of - so a wide spread is visible rather than
	 * hidden inside one number - and the volume that let the row qualify at all. The mid is exact and the two
	 * sides are in stack form, because the sides are context for the figure and not the figure.
	 *
	 * <p>"" when the row is not live ({@link MovementRow#isLive()}), so a caller can ask blind. A side the feed had
	 * no value for is simply left out rather than printed as a dash: the parenthetical is context, and half of it
	 * still helps.
	 */
	public static String liveLine(MovementRow row)
	{
		final MovementRow.LiveFacts facts = row.liveFacts();
		if (!row.isLive() || facts == null)
		{
			return "";
		}
		final StringBuilder sb = new StringBuilder(80).append(LIVE_PRICE_PREFIX);
		sb.append(row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatExact(row.unitPrice()) + " gp");
		final StringBuilder inner = new StringBuilder(48);
		if (facts.buy() != null)
		{
			inner.append("buy ").append(MovementMath.formatGp(facts.buy()));
		}
		if (facts.sell() != null)
		{
			inner.append(inner.length() == 0 ? "" : ", ").append("sell ").append(MovementMath.formatGp(facts.sell()));
		}
		if (facts.volumeYesterday() > 0L)
		{
			inner.append(inner.length() == 0 ? "" : "; ").append(MovementMath.formatExact(facts.volumeYesterday()))
				.append(" traded yesterday");
		}
		if (inner.length() > 0)
		{
			sb.append(" (").append(inner).append(')');
		}
		return sb.toString();
	}

	/**
	 * The one line a row that stayed on the guide gains while the live switch is on (T6): "Guide price - live not
	 * used: 12 traded yesterday". The phrase after the colon is the FIRST of the five checks that refused the row
	 * (T3's three, and addendum V's two on yesterday's own bucket), in their own order, as {@code PriceService}
	 * recorded it - this class states no rule of its
	 * own about liquidity, it prints the one the service applied.
	 *
	 * <p>"" when the row is live, or when the traded feeds never looked at it - which is every row built while the
	 * switch was off, and is what keeps a guide-only tooltip exactly what it was before addendum T.
	 */
	public static String liveRefusalLine(MovementRow row)
	{
		final MovementRow.LiveFacts facts = row.liveFacts();
		final String reason = facts == null ? null : facts.reason();
		return reason == null || reason.isEmpty() ? "" : LIVE_NOT_USED_PREFIX + reason;
	}

	/** Green for a rise, red for a fall, the dim grey for a dash or a zero move (contract C31, L2). */
	public static Color changeColor(MovementRow row)
	{
		return Widgets.move(signum(row), Widgets.Kind.MARK);
	}

	/**
	 * {@link #changeColor(MovementRow)} as the two FIGURES on line 2 are painted: the same green and the same
	 * grey, and {@link Widgets#MOVE_DOWN_TEXT} - the lifted red - for a fall.
	 *
	 * <p>Small red text on the card grey measures 3.63:1, under the 4.5:1 that 12 px and 14 px want, and beside a
	 * green at 10.9:1 a losing row reads visibly dimmer than a winning one. The lift is text-only, which is why it
	 * is a second method rather than a new value in {@link #changeColor}: the rail ({@link #railColor}) and the
	 * hero card's edge keep the constant, so the mark and the number still agree about the direction.
	 */
	public static Color textChangeColor(MovementRow row)
	{
		return Widgets.move(signum(row), Widgets.Kind.FIGURE);
	}

	/**
	 * The 3 px left rail (addendum N section 2 and section 3 §4): the move's colour DARKENED for a mover, and
	 * the card's own grey - so the rail is invisible without changing the card's width - for a flat row, a row
	 * with no baseline and a row with no price. Movers only: a quiet day must look like one.
	 *
	 * <p>The same sign rule as {@link #changeColor(MovementRow)}, from the gp figure (L2), so the rail and the
	 * number can never disagree about the direction.
	 */
	public static Color railColor(MovementRow row)
	{
		return Widgets.move(signum(row), Widgets.Kind.EDGE);
	}

	/**
	 * Which way this row went, for {@link Widgets#move}: its gp change, with "no change" and "no baseline at
	 * all" both reading as flat - a row with nothing to compare is not a mover (contract C31).
	 */
	private static int signum(MovementRow row)
	{
		final Long gp = row.deltaGp();
		return gp == null ? 0 : Long.signum(gp);
	}

	/**
	 * The HTML tooltip, addendum K8's five lines with addendum L7's day stamp: the name, the exact guide price,
	 * the baseline with the DAY it was read from, the holding, the change. Every figure exact
	 * ({@link MovementMath#formatExact}), every text from the item escaped. UNCHANGED by addendum N - and now
	 * load-bearing, because the holding value and the exact gp change both left the face of the row.
	 *
	 * <pre>
	 * Green hat
	 * Guide price: 1,086 gp
	 * 1d ago (07 Sep): 1,124 gp
	 * Holding: 3 = 3,258 gp
	 * 2 in bank, 1 in inventory
	 * Change per item: -38 gp (-3.4%)
	 * </pre>
	 *
	 * <p>The fifth line is addendum Y's and is there only when it has something to say ({@link #splitLine}): with
	 * "Include inventory and worn gear" off, or with the whole stack in the bank, the tooltip is the five lines
	 * addenda K and L wrote.
	 *
	 * <p><b>"per item" is load-bearing, not decoration</b> - and since addendum X (line X2;
	 * {@code docs/bank-price-movement-addendum-X-2026-09-13.md}) took the words off the sort button's hover,
	 * this line is the one place they are said. The change is the change in ONE item's guide
	 * price, printed two lines under a "Holding:" line
	 * that is the whole stack - so "Holding: 3 = 3,258 gp" over a bare "Change: -38 gp" invites the reading that
	 * the holding lost 38 gp when it lost 114, and on a 28,000 stack of scales that misreading is out by four
	 * orders of magnitude. The three words are the whole fix; the tooltip is HTML and has the room.
	 *
	 * <p>There is no "where the price came from" any more (the old "wiki HH:MM | RuneLite"): under addendum K
	 * there is ONE number and one place it comes from, RuneLite's own guide-price table (K1), so naming it in
	 * the line - "Guide price:" - says everything the parenthetical used to.
	 *
	 * <p>The stamp is a DAY and not a clock (L7). It is also the day the baseline table actually claims, which
	 * on a maintenance edit is not the day the window asked for (L5) - so a reader comparing the row against the
	 * GE site can see that the baseline came from the 2nd rather than the 3rd instead of guessing.
	 *
	 * <p><b>A merged row says where its quantity is</b> (addendum Y, line Y3;
	 * {@code docs/bank-price-movement-addendum-Y-2026-09-13.md}). With "Include inventory and worn gear" on, an
	 * item held in two places is ONE row with the quantities summed, so directly under the Holding line - and only
	 * when something is carried or worn - comes {@link #splitLine}: "3 in bank, 1 in inventory, 1 worn". It is the
	 * only place the split exists, exactly as the holding value and the exact change are, and a row entirely in the
	 * bank prints nothing new.
	 *
	 * <p><b>An untradeable row says one more thing, or another thing.</b> A row valued at its tradeable parts
	 * (R4) keeps all five lines - it has a price, a baseline and a change like any other - and gains a sixth
	 * between the price and the baseline, naming the parts the price is a sum of ({@link #partsLine}). An ALCH
	 * row (Q5) has no guide price, no baseline and no change at all, so the three lines that would all read "-"
	 * give way to one sentence ({@link #untradeableLine}) over the holding, which is the only figure it does
	 * have. The tooltip does not vary with {@code holdingOnRows} (Q6): it already carries the unit price and the
	 * holding, and it is where a reader goes for the reading the face is not showing.
	 *
	 * @param thenDay the baseline table's UTC day; null stamps {@link MovementMath#DASH}
	 */
	public static String tooltip(MovementRow row, @Nullable MovementWindow window, @Nullable LocalDate thenDay)
	{
		return tooltip(row, window, thenDay, ViewOptions.DEFAULT);
	}

	/**
	 * {@link #tooltip(MovementRow, MovementWindow, LocalDate)} under the view switches (addendum T, line T6). Only
	 * the live switch reaches it - the other three are answered by the row the service published - and it changes
	 * three things, none of which a row built with the switch off can show, because such a row carries no
	 * {@link MovementRow#liveFacts()} at all:
	 *
	 * <pre>
	 * Twisted bow
	 * Live traded price: 63,437,264 gp (buy 63.6m, sell 63.3m; 517 traded yesterday)
	 * 1d ago (10 Sep): 62,100,000 gp (traded average)
	 * Holding: 1 = 63,437,264 gp
	 * Change per item: +1,337,264 gp (+2.1%)
	 *
	 * Green hat
	 * Guide price: 1,086 gp
	 * Guide price - live not used: 12 traded yesterday
	 * 1d ago (07 Sep): 1,124 gp
	 * ...
	 * </pre>
	 *
	 * <p>The window line's own note is the third: a live row whose traded bucket for THAT day was missing, too thin
	 * or too scattered (V3) compares guide against guide for it (T4), and says so - "(guide - too few or too
	 * scattered trades that day)" - because a live price over a guide baseline is exactly the comparison addendum
	 * T's study refuted, and a reader looking at a row that did the right thing should be able to see that it did.
	 *
	 * <p><b>And the window line's DAY is the row's own</b> (addendum U, line U3, {@link #stampedDay}): "1d ago
	 * (11 Sep)" is the day the traded bucket behind that figure belongs to, which on a live row is {@code liveDay -
	 * N} and not the guide's anchor day. The live look that produced addendum U found a Partyhat set reading +30 %
	 * for "1d" because the two had drifted apart, so the day a line prints is now taken from the same place its
	 * number is.
	 *
	 * @param options the view switches the page was built under; null reads as {@link ViewOptions#DEFAULT}. With
	 *                {@code livePrices} off no live line is printed whatever the row carries - so the page built
	 *                after a switch is thrown never explains a series the panel is no longer on, and a row list
	 *                recorded while the switch was on cannot bleed a live line into a guide-only sidebar. The rows
	 *                already on screen keep the tooltips they were built with until the service's own recompute
	 *                publishes the new figures, which is the same beat their FACES change on. {@code countInventory}
	 *                reaches it the same way (Y3): with the switch off no split line is printed whatever the row
	 *                carries, so a list recorded while it was on cannot bleed one into a bank-only sidebar
	 */
	public static String tooltip(MovementRow row, @Nullable MovementWindow window, @Nullable LocalDate thenDay,
		@Nullable ViewOptions options)
	{
		final ViewOptions view = options == null ? ViewOptions.DEFAULT : options;
		final boolean live = view.livePrices();
		// Y3: where the quantity is, said under the Holding line it qualifies - on every kind of row, because a
		// worn slayer helmet is an alch row and a worn Crystal body a parts row. "" while the switch is off and
		// "" while the whole stack is in the bank, so the line appears exactly where it has something to add.
		final String split = view.countInventory() ? splitLine(row) : "";
		final StringBuilder sb = new StringBuilder(180);
		sb.append("<html><b>").append(Widgets.escapeHtml(row.name())).append("</b>");
		if (isAlch(row))
		{
			sb.append("<br>").append(untradeableLine(row));
			sb.append("<br>Holding: ").append(MovementMath.formatExact(row.quantity())).append(" = ");
			sb.append(row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatExact(row.holdingValue()) + " gp");
			if (!split.isEmpty())
			{
				sb.append("<br>").append(split);
			}
			return sb.append("</html>").toString();
		}
		// T6: the price line names its series. A live row says so and shows the two sides and the volume behind
		// it; every other row is the guide line this tooltip has carried since addendum K.
		final String liveLine = live ? liveLine(row) : "";
		if (!liveLine.isEmpty())
		{
			sb.append("<br>").append(liveLine);
		}
		else
		{
			sb.append("<br>Guide price: ");
			sb.append(row.unitPrice() == null
				? MovementMath.DASH + " (no price)" : MovementMath.formatExact(row.unitPrice()) + " gp");
		}
		// R4: on a parts row, ONE line under the price saying what that price is the sum of - and then the
		// ordinary then / holding / change lines, because a parts row has all three. Escaped: the part names come
		// from the game, like the item name above them.
		final String parts = partsLine(row);
		if (!parts.isEmpty())
		{
			sb.append("<br>").append(Widgets.escapeHtml(parts));
		}
		// T6: and under the price it qualifies, the check that kept this row on the guide series.
		final String refusal = live ? liveRefusalLine(row) : "";
		if (!refusal.isEmpty())
		{
			sb.append("<br>").append(Widgets.escapeHtml(refusal));
		}
		sb.append("<br>").append(window == null ? MovementWindow.DEFAULT.label() : window.label());
		sb.append(" ago (").append(MovementMath.formatDay(stampedDay(row, window, thenDay, live))).append("): ");
		sb.append(row.thenPrice() == null ? MovementMath.DASH : MovementMath.formatExact(row.thenPrice()) + " gp");
		// T4: which series THIS window compared, said only on a row the traded feeds actually touched - a guide-only
		// row's window line is what it always was.
		if (live && !liveLine.isEmpty())
		{
			sb.append(row.windowSource(window) == MovementRow.PriceSource.LIVE
				? TRADED_AVERAGE_NOTE : WINDOW_FELL_BACK_NOTE);
		}
		sb.append("<br>Holding: ").append(MovementMath.formatExact(row.quantity())).append(" = ");
		sb.append(row.unitPrice() == null ? MovementMath.DASH : MovementMath.formatExact(row.holdingValue()) + " gp");
		if (!split.isEmpty())
		{
			sb.append("<br>").append(split);
		}
		sb.append("<br>Change per item: ");
		if (row.hasMovement())
		{
			sb.append(signedExact(row.deltaGp())).append(" gp (")
				.append(MovementMath.formatPct(row.deltaPct(), row.deltaGp())).append(')');
		}
		else
		{
			sb.append(MovementMath.DASH);
		}
		sb.append("</html>");
		return sb.toString();
	}

	/**
	 * The calendar day ONE window line stamps (addendum U, line U3;
	 * {@code docs/bank-price-movement-addendum-U-2026-09-12.md}): the day this ROW actually compared against
	 * ({@link MovementRow#windowDay(MovementWindow)}) whenever it recorded one, and the status's own baseline day
	 * otherwise.
	 *
	 * <p>The two are the same day for every guide row, and they part company on a LIVE one. The live series counts
	 * back from the live snapshot's own UTC date (U1) while the guide baselines count back from Jagex's anchor day,
	 * and at any hour of the day where Jagex has not yet published the day's table those are different days - which
	 * is the bug addendum U was written for: a "1d" line stamped with the guide's day while the figure beside it
	 * compared against a bucket TWO days old. A row prints the day its own figure came from, so the day and the
	 * number on one line can never disagree.
	 *
	 * <p>{@code live} is the SWITCH, not the row: with live prices off this answers {@code thenDay} whatever the row
	 * carries, so a row list recorded while the switch was on cannot bleed a traded day into the guide-only tooltip
	 * addenda K to S wrote (T8). {@code thenDay} is the fallback everywhere else too - a window with no traded
	 * bucket, a guide row while the switch is on, and a null window - so a row that recorded nothing stamps exactly
	 * what it stamped before addendum U.
	 *
	 * @param window  the window whose line is being written; null answers {@code thenDay}
	 * @param thenDay the status's baseline day for that window; null stamps {@link MovementMath#DASH}
	 * @param live    whether the {@code livePrices} switch is on
	 */
	@Nullable
	static LocalDate stampedDay(MovementRow row, @Nullable MovementWindow window, @Nullable LocalDate thenDay,
		boolean live)
	{
		if (!live)
		{
			return thenDay;
		}
		final LocalDate day = row.windowDay(window);
		return day == null ? thenDay : day;
	}

	/**
	 * The exact change with its sign - "+20,000", "-4,100", "0" - for the tooltip, where every figure is exact;
	 * the row itself shows the stack-style {@link MovementMath#formatDelta} ("+20k").
	 */
	private static String signedExact(@Nullable Long gp)
	{
		if (gp == null)
		{
			return MovementMath.DASH;
		}
		return (gp > 0L ? "+" : "") + MovementMath.formatExact(gp);
	}

	/** The price wiki's history page for an item: {@link #WIKI_ITEM_URL} + id. */
	public static String wikiUrl(int id)
	{
		return WIKI_ITEM_URL + id;
	}

	/** The Grand Exchange site's page for an item: {@link #GE_ITEM_URL} + id (K8). */
	public static String geUrl(int id)
	{
		return GE_ITEM_URL + id;
	}

	// ---------------------------------------------------------------- state

	public MovementRow row()
	{
		return row;
	}

	public boolean hovered()
	{
		return hovered;
	}

	private void setHovered(boolean on)
	{
		hovered = on;
		setBackground(on ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
	}

	/** What the second right-click entry does: the wiki's history page for this item, through the row's browser. */
	void openWiki()
	{
		browser.accept(wikiUrl(row.id()));
	}

	/** What the first right-click entry does: the item's Grand Exchange page, through the row's browser. */
	void openGrandExchange()
	{
		browser.accept(geUrl(row.id()));
	}

	@Nullable
	AsyncBufferedImage icon()
	{
		return icon;
	}

	String tooltipHtml()
	{
		return tooltip;
	}

	String nameText()
	{
		return nameLabel.getText();
	}

	String priceText()
	{
		return priceLabel.getText();
	}

	/** "x3", or "" at a quantity of one and whenever the fit order had to drop it. */
	String quantityText()
	{
		return quantityLabel.getText();
	}

	/** The percentage, one dash, or the {@value #ALCH_TAG} tag on an untradeable row (Q5). */
	String changeText()
	{
		return changeLabel.getText();
	}

	/** The gp figure, or "" on a row with no movement - the dash beside it is already saying so. */
	String gpText()
	{
		return gpLabel.getText();
	}

	Color changeColor()
	{
		return changeLabel.getForeground();
	}

	/** The colour of the 3 px left rail as this row was built; {@code DARKER_GRAY} means "no rail". */
	Color railColor()
	{
		return rail;
	}

	Component iconLabel()
	{
		return iconLabel;
	}
}
